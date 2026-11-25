package com.yuzhihuihe.apiplatform.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class OperatingReconciliationServiceTest {

    private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void canBeCreatedForWebSliceTestsWithoutARealDataSource() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThatCode(() -> new OperatingReconciliationService(
                new OperatingReconciliationRepository(jdbc),
                mock(GatewayEconomicsHealthClient.class), new ObjectMapper(),
                mock(PlatformTransactionManager.class)))
                .doesNotThrowAnyException();
    }

    @Test
    void classifiesEveryAuthoritativeSourceAndUsesZeroCentTolerance() {
        List<OperatingReconciliationService.SourceSnapshot> sources = List.of(
                source("PACKAGE_ORDER", "order-1", 1000, 1),
                source("MODEL_USAGE_LEDGER", "usage-1", 200, 1),
                source("RECHARGE_LEDGER", "recharge-1", 3000, 1),
                source("COMPLETED_REFUND", "refund-1", 500, 1),
                source("GATEWAY_ECONOMICS", "gateway-1", 80, 2),
                source("PROVIDER_FEE_BILL", "fee-1", 12, 1));
        List<OperatingReconciliationService.FactSnapshot> facts = List.of(
                fact("order-1", 1000, 1, false, false, "1000.004"),
                fact("usage-1", 201, 1, false, false, "201"),
                fact("recharge-1", 3000, 2, false, false, "3000"),
                fact("refund-1", 500, 1, true, false, "500"),
                fact("gateway-1", 80, 2, false, true, "79.999"));
        MemoryStore store = new MemoryStore(sources, facts,
                new OperatingReconciliationService.HealthSnapshot(BigDecimal.valueOf(4300),
                        BigDecimal.valueOf(4299),
                        new BigDecimal("4300.003"), new BigDecimal("4300.000")));

        OperatingReconciliationService.Result result =
                new OperatingReconciliationService(store, (from, to, sink) ->
                        new GatewayEconomicsHealthClient.Status(3, 0, 0, 0, FROM))
                        .reconcile(FROM, TO);

        assertThat(result.items()).extracting(OperatingReconciliationService.Item::type)
                .containsExactlyInAnyOrder(
                        OperatingReconciliationService.DifferenceType.AMOUNT_MISMATCH,
                        OperatingReconciliationService.DifferenceType.DUPLICATE,
                        OperatingReconciliationService.DifferenceType.ESTIMATED_NOT_ACTUAL,
                        OperatingReconciliationService.DifferenceType.MISSING_SOURCE,
                        OperatingReconciliationService.DifferenceType.MISSING,
                        OperatingReconciliationService.DifferenceType.GATEWAY_OUTBOX_BACKLOG,
                        OperatingReconciliationService.DifferenceType.AGGREGATE_MISMATCH);
        assertThat(result.items()).filteredOn(item -> item.sourceEventId().equals("usage-1"))
                .singleElement().satisfies(item -> assertThat(item.differenceAmount())
                        .isEqualByComparingTo("1"));
        assertThat(result.allocationRoundingDifference()).isEqualByComparingTo("0.003");
        assertThat(store.saved).containsExactlyElementsOf(result.items());
    }

    @Test
    void allocatedUsageRevenueRoundingNeverCreatesFinancialDifference() {
        MemoryStore store = new MemoryStore(
                List.of(source("GATEWAY_ECONOMICS", "gateway-2", 100, 1)),
                List.of(fact("gateway-2", 100, 1, false, false, "99.999999")),
                new OperatingReconciliationService.HealthSnapshot(BigDecimal.valueOf(100),
                        BigDecimal.valueOf(100),
                        new BigDecimal("99.999999"), new BigDecimal("100.000000")));

        var result = new OperatingReconciliationService(store).reconcile(FROM, TO);

        assertThat(result.items()).isEmpty();
        assertThat(result.allocationRoundingDifference()).isEqualByComparingTo("-0.000001");
    }

    @Test
    void gatewaySentSourceWithoutReceiptIsMissingAndBacklogUsesGatewayStatus() {
        MemoryStore store = new MemoryStore(List.of(), List.of(),
                new OperatingReconciliationService.HealthSnapshot(BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        GatewayEconomicsHealthClient.SourceEvent source = new GatewayEconomicsHealthClient.SourceEvent(
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", FROM);
        OperatingReconciliationService service = new OperatingReconciliationService(store,
                (from, to, sink) -> { sink.accept(List.of(source));
                    return new GatewayEconomicsHealthClient.Status(2, 1, 3, 1, FROM); });

        var result = service.reconcile(FROM, TO);

        assertThat(result.items()).extracting(OperatingReconciliationService.Item::type)
                .containsExactlyInAnyOrder(OperatingReconciliationService.DifferenceType.MISSING,
                        OperatingReconciliationService.DifferenceType.GATEWAY_OUTBOX_BACKLOG);
        assertThat(result.items()).filteredOn(item -> item.type()
                == OperatingReconciliationService.DifferenceType.GATEWAY_OUTBOX_BACKLOG)
                .singleElement().satisfies(item -> assertThat(item.sourceAmount())
                        .isEqualByComparingTo("7"));
    }

    @Test
    void returnsValidatedDifferencePagesAfterPersistingTheCompleteResult() {
        List<OperatingReconciliationService.SourceSnapshot> sources = List.of(
                source("PACKAGE_ORDER", "order-1", 100, 1),
                source("PACKAGE_ORDER", "order-2", 200, 1),
                source("PACKAGE_ORDER", "order-3", 300, 1),
                source("PACKAGE_ORDER", "order-4", 400, 1),
                source("PACKAGE_ORDER", "order-5", 500, 1));
        List<OperatingReconciliationService.FactSnapshot> facts = List.of(
                fact("order-1", 101, 1, false, false, "0"),
                fact("order-2", 201, 1, false, false, "0"),
                fact("order-3", 301, 1, false, false, "0"),
                fact("order-4", 401, 1, false, false, "0"),
                fact("order-5", 501, 1, false, false, "0"));
        MemoryStore store = new MemoryStore(sources, facts,
                new OperatingReconciliationService.HealthSnapshot(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        OperatingReconciliationService service = new OperatingReconciliationService(store);

        OperatingReconciliationService.PageResult result =
                service.reconcile(FROM, TO, 1, 2);

        assertThat(result.items()).extracting(OperatingReconciliationService.Item::sourceEventId)
                .containsExactly("order-3", "order-4");
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(2);
        assertThat(store.saved).hasSize(5);
        assertThatThrownBy(() -> service.reconcile(FROM, TO, -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid_reconciliation_page");
        assertThatThrownBy(() -> service.reconcile(FROM, TO, 0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid_reconciliation_page");
    }

    @Test
    void factHealthSqlReversesEstimatesButKeepsRefundPositive() {
        assertThat(OperatingReconciliationRepository.FACT_HEALTH_SQL)
                .contains("confirmed_revenue_amount * sign")
                .contains("provider_input_cost_amount * sign")
                .contains("payment_provider_fee_amount * sign")
                .contains("allocated_usage_revenue_amount * sign")
                .contains("+ refund_amount")
                .doesNotContain("refund_amount * sign");
    }

    @Test
    void localSourceMatchesAuthoritativeFactWriterAndEvidenceIsPersistableJson() throws Exception {
        assertThat(OperatingReconciliationService.LOCAL_SOURCE).isEqualTo(OperatingFactService.SOURCE);
        assertThat(OperatingReconciliationRepository.SOURCE_SQL)
                .contains("'" + OperatingFactService.SOURCE + "' source_service")
                .doesNotContain("'platform-service'");
        String json = OperatingReconciliationService.serializeEvidence(
                new ObjectMapper(), Map.of("sourceType", "PACKAGE_ORDER", "pending", 2));
        assertThat(new ObjectMapper().readValue(json, new TypeReference<Map<String, Object>>() { }))
                .containsEntry("sourceType", "PACKAGE_ORDER").containsEntry("pending", 2);
        assertThat(OperatingReconciliationRepository.EVIDENCE_INSERT_SQL).contains("CAST(? AS jsonb)");
    }

    @Test
    void walletReservationDifferenceKeepsTheActualRequestIdAsDrillEvidence() {
        String eventId = "wallet-reservation:10000000-0000-4000-8000-000000000009:settled:v1";
        var walletSource = new OperatingReconciliationService.SourceSnapshot(
                "MODEL_USAGE_LEDGER", "platform", eventId, "CNY", BigDecimal.valueOf(200), 1,
                Map.of("requestId", "req-real-42"));
        MemoryStore store = new MemoryStore(List.of(walletSource),
                List.of(fact(eventId, 201, 1, false, false, "201")),
                new OperatingReconciliationService.HealthSnapshot(BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        var result = new OperatingReconciliationService(store).reconcile(FROM, TO);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.sourceEventId()).isEqualTo(eventId);
            assertThat(item.evidence()).containsEntry("sourceType", "MODEL_USAGE_LEDGER")
                    .containsEntry("requestId", "req-real-42");
        });
        assertThat(OperatingReconciliationRepository.SOURCE_SQL)
                .contains("wallet_usage_reservation")
                .contains("request_id");
    }

    private static OperatingReconciliationService.SourceSnapshot source(
            String type, String eventId, long amount, int expectedLines) {
        return new OperatingReconciliationService.SourceSnapshot(
                type, "platform", eventId, "CNY", BigDecimal.valueOf(amount), expectedLines,
                Map.of());
    }

    private static OperatingReconciliationService.FactSnapshot fact(
            String eventId, long amount, int lines, boolean estimated, boolean missing,
            String allocatedRevenue) {
        return new OperatingReconciliationService.FactSnapshot(
                "platform", eventId, "CNY", BigDecimal.valueOf(amount),
                new BigDecimal(allocatedRevenue), lines, estimated, missing);
    }

    private static final class MemoryStore implements OperatingReconciliationService.Store {
        private final List<OperatingReconciliationService.SourceSnapshot> sources;
        private final List<OperatingReconciliationService.FactSnapshot> facts;
        private final OperatingReconciliationService.HealthSnapshot health;
        private final List<OperatingReconciliationService.Item> saved = new ArrayList<>();

        private MemoryStore(List<OperatingReconciliationService.SourceSnapshot> sources,
                List<OperatingReconciliationService.FactSnapshot> facts,
                OperatingReconciliationService.HealthSnapshot health) {
            this.sources = sources;
            this.facts = facts;
            this.health = health;
        }

        @Override public List<OperatingReconciliationService.SourceSnapshot> sources(
                Instant from, Instant to) { return sources; }
        @Override public List<OperatingReconciliationService.FactSnapshot> facts(
                Instant from, Instant to) { return facts; }
        @Override public OperatingReconciliationService.HealthSnapshot health(
                Instant from, Instant to) { return health; }
        @Override public java.util.Map<String, OperatingReconciliationService.GatewayReceipt>
                gatewayReceipts(List<String> eventIds) { return java.util.Map.of(); }
        @Override public void save(Instant from, Instant to,
                List<OperatingReconciliationService.Item> items) { saved.addAll(items); }
    }
}
