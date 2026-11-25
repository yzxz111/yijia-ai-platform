package com.yuzhihuihe.apiplatform.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatingFactIngestionServiceTest {

    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private static final UUID TENANT = UUID.fromString("91000000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.fromString("91000000-0000-4000-8000-000000000002");
    private static final UUID EVENT = UUID.fromString("91000000-0000-4000-8000-000000000003");
    private static final UUID PACKAGE = UUID.fromString("91000000-0000-4000-8000-000000000004");
    private static final UUID PACKAGE_VERSION = UUID.fromString("91000000-0000-4000-8000-000000000010");
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");

    @Test
    void splitsCompositeProviderCostsAndDoesNotCreateWalletRevenue() {
        MemoryStore store = new MemoryStore();
        store.wallet = new OperatingFactIngestionService.WalletSnapshot(300, 70, 30);
        store.allocation = new OperatingFactIngestionService.PackageAllocation(
                BigDecimal.valueOf(1900), 1_000_000, "PAID_ORDER", PACKAGE_VERSION);
        OperatingFactIngestionService service = service(store);

        var result = service.ingest(bytes(compositePayload()), HASH_A);

        assertThat(result).isEqualTo(OperatingFactIngestionService.Result.ACCEPTED);
        assertThat(store.facts).hasSize(2);
        assertThat(store.facts).allSatisfy(fact -> {
            assertThat(fact.eventType()).isEqualTo("PROVIDER_USAGE_COST");
            assertThat(fact.confirmedRevenueAmount()).isZero();
            assertThat(fact.metadata()).containsEntry("payloadHash", HASH_A);
            assertThat(fact.packagePlanVersionId()).isEqualTo(PACKAGE_VERSION);
        });
        assertThat(store.facts).extracting(OperatingFactIngestionService.FactLine::totalTokens)
                .containsExactly(40L, 60L);
        assertThat(store.facts).extracting(OperatingFactIngestionService.FactLine::providerCostAmount)
                .containsExactly(BigDecimal.valueOf(3), BigDecimal.valueOf(7));
        assertThat(store.facts).extracting(OperatingFactIngestionService.FactLine::allocatedUsageRevenueAmount)
                .containsExactly(new BigDecimal("120.076000"), new BigDecimal("180.114000"));
    }

    @Test
    void dimensionedWalletAllocationDrivesUsageSlicesWithoutDoubleCountingStandaloneRevenue() {
        MemoryStore ingestionStore = new MemoryStore();
        ingestionStore.wallet = new OperatingFactIngestionService.WalletSnapshot(300, 70, 30);
        ingestionStore.allocation = new OperatingFactIngestionService.PackageAllocation(
                BigDecimal.ZERO, 1_000_000, "ADMIN_GRANT", PACKAGE_VERSION);
        assertThat(service(ingestionStore).ingest(bytes(compositePayload()), HASH_A))
                .isEqualTo(OperatingFactIngestionService.Result.ACCEPTED);

        FactCapture factStore = new FactCapture();
        new OperatingFactService(Clock.fixed(NOW, ZoneOffset.UTC), factStore)
                .recordWalletUsage(new OperatingFactService.WalletUsage(
                        UUID.randomUUID(), TENANT, USER, "req-1", "composite", "pv-1",
                        300, 70, 30, "HYBRID", NOW));
        List<OperatingReportQueryService.DailyRow> rows = new ArrayList<>();
        OperatingFactService.Fact wallet = factStore.facts.getFirst();
        rows.add(new OperatingReportQueryService.DailyRow(LocalDate.of(2026, 8, 8),
                wallet.tenantId(), wallet.userId(), null, wallet.logicalModel(), null,
                null, wallet.provider(), wallet.billingMode(), wallet.currency(),
                BigDecimal.valueOf(wallet.confirmedRevenueAmount()), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0, 0, 0, 1, 0, 0));
        ingestionStore.facts.forEach(line -> rows.add(new OperatingReportQueryService.DailyRow(
                LocalDate.of(2026, 8, 8), line.tenantId(), line.userId(), null,
                line.logicalModel(), line.upstreamModel(), line.channel(), line.provider(),
                line.billingMode(), "CNY", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                line.providerCostAmount(), BigDecimal.ZERO, BigDecimal.ZERO,
                line.allocatedUsageRevenueAmount(), line.inputTokens(), line.outputTokens(),
                line.totalTokens(), 1, 0, 0)));
        OperatingReportQueryService reports = new OperatingReportQueryService(filter ->
                rows.stream().filter(filter::matches).toList());

        var company = reports.query(reportFilter(null, null, null, null));
        var bailian = reports.query(reportFilter("text-basic", "qwen-plus",
                "91000000-0000-4000-8000-000000000007",
                "91000000-0000-4000-8000-000000000006"));
        var deepseek = reports.query(reportFilter("text-reasoning", "deepseek-chat",
                "91000000-0000-4000-8000-000000000009",
                "91000000-0000-4000-8000-000000000008"));

        assertThat(company.total().confirmedRevenueAmount()).isEqualByComparingTo("300");
        assertThat(company.total().profitAmount()).isEqualByComparingTo("290");
        assertThat(bailian.total().allocatedUsageRevenueAmount()).isEqualByComparingTo("120");
        assertThat(bailian.total().profitAmount()).isEqualByComparingTo("117");
        assertThat(deepseek.total().allocatedUsageRevenueAmount()).isEqualByComparingTo("180");
        assertThat(deepseek.total().profitAmount()).isEqualByComparingTo("173");
        assertThat(bailian.total().allocatedUsageRevenueAmount()
                .add(deepseek.total().allocatedUsageRevenueAmount())).isEqualByComparingTo("300");
    }

    @Test
    void acceptedUsageFactsTriggerProviderCostAndPackageMarginAlerts() {
        MemoryStore store = new MemoryStore();
        store.wallet = new OperatingFactIngestionService.WalletSnapshot(300, 70, 30);
        store.allocation = new OperatingFactIngestionService.PackageAllocation(
                BigDecimal.valueOf(1900), 1_000_000, "PAID_ORDER", PACKAGE_VERSION);
        store.providerP95 = BigDecimal.ONE;
        RecordingAlerts alerts = new RecordingAlerts();
        OperatingFactIngestionService service = new OperatingFactIngestionService(
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), store, alerts);

        assertThat(service.ingest(bytes(compositePayload()), HASH_A))
                .isEqualTo(OperatingFactIngestionService.Result.ACCEPTED);
        assertThat(alerts.providerCalls).isEqualTo(2);
        assertThat(alerts.packageCalls).isEqualTo(1);
        assertThat(alerts.packageIds).containsExactly(PACKAGE_VERSION);
        assertThat(alerts.evidence).allMatch(ids -> !ids.isEmpty());
    }

    @Test
    void samePlanVersionAcrossInstancesUsesOnePackageAlertDimension() {
        RecordingAlerts alerts = new RecordingAlerts();
        for (int index = 0; index < 2; index++) {
            MemoryStore store = new MemoryStore();
            store.wallet = new OperatingFactIngestionService.WalletSnapshot(300, 70, 30);
            store.allocation = new OperatingFactIngestionService.PackageAllocation(
                    BigDecimal.valueOf(1900), 1_000_000, "PAID_ORDER", PACKAGE_VERSION);
            String payload = compositePayload()
                    .replace(EVENT.toString(), new UUID(EVENT.getMostSignificantBits(),
                            EVENT.getLeastSignificantBits() + index + 1).toString())
                    .replace(PACKAGE.toString(), new UUID(PACKAGE.getMostSignificantBits(),
                            PACKAGE.getLeastSignificantBits() + index + 1).toString());
            new OperatingFactIngestionService(new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                    store, alerts).ingest(bytes(payload), index == 0 ? HASH_A : HASH_B);
        }
        assertThat(alerts.packageIds).containsExactly(PACKAGE_VERSION, PACKAGE_VERSION);
    }

    @Test
    void hybridAllocatesProviderCostByPackageTokenRatio() {
        MemoryStore store = new MemoryStore();
        store.wallet = new OperatingFactIngestionService.WalletSnapshot(300, 70, 30);
        store.allocation = new OperatingFactIngestionService.PackageAllocation(
                BigDecimal.valueOf(200_000), 1_000_000, "PAID_ORDER", PACKAGE_VERSION);
        RecordingAlerts alerts = new RecordingAlerts();
        String payload = compositePayload().replace("\"package_settled_tokens\":100",
                "\"package_settled_tokens\":50");

        new OperatingFactIngestionService(new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                store, alerts).ingest(bytes(payload), HASH_A);

        assertThat(alerts.packageCosts).containsExactly(new BigDecimal("5.000000"));
    }

    @Test
    void exactReplayIsNoOpAndDifferentPayloadConflicts() {
        MemoryStore store = new MemoryStore();
        store.claimedHash = HASH_A;
        store.wallet = new OperatingFactIngestionService.WalletSnapshot(300, 70, 30);
        store.allocation = new OperatingFactIngestionService.PackageAllocation(
                BigDecimal.valueOf(1900), 1_000_000, "PAID_ORDER");
        OperatingFactIngestionService service = service(store);

        assertThat(service.ingest(bytes(compositePayload()), HASH_A))
                .isEqualTo(OperatingFactIngestionService.Result.REPLAYED);
        assertThat(service.ingest(bytes(compositePayload()), HASH_B))
                .isEqualTo(OperatingFactIngestionService.Result.CONFLICT);
        assertThat(store.facts).isEmpty();
    }

    @Test
    void concurrentDifferentPayloadsAtomicallySelectOneWholeEvent() throws Exception {
        MemoryStore store = new MemoryStore();
        store.wallet = new OperatingFactIngestionService.WalletSnapshot(300, 70, 30);
        store.allocation = new OperatingFactIngestionService.PackageAllocation(
                BigDecimal.valueOf(1900), 1_000_000, "PAID_ORDER");
        OperatingFactIngestionService service = service(store);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<OperatingFactIngestionService.Result>> calls = List.of(
                    () -> service.ingest(bytes(compositePayload()), HASH_A),
                    () -> service.ingest(bytes(compositePayload().replace("workflow", "workflow-v2")), HASH_B));
            var results = pool.invokeAll(calls).stream().map(future -> {
                try { return future.get(); } catch (Exception error) { throw new AssertionError(error); }
            }).toList();

            assertThat(results).containsExactlyInAnyOrder(
                    OperatingFactIngestionService.Result.ACCEPTED,
                    OperatingFactIngestionService.Result.CONFLICT);
            assertThat(store.facts).hasSize(2);
            assertThat(store.facts).extracting(fact -> fact.metadata().get("payloadHash"))
                    .containsOnly(store.claimedHash);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void infrastructureFailureWritingSecondLinePropagatesWithoutPartialFacts() {
        MemoryStore store = new MemoryStore();
        store.wallet = new OperatingFactIngestionService.WalletSnapshot(300, 70, 30);
        store.allocation = new OperatingFactIngestionService.PackageAllocation(
                BigDecimal.valueOf(1900), 1_000_000, "PAID_ORDER");
        store.failSecondLine = true;

        assertThatThrownBy(() -> service(store).ingest(bytes(compositePayload()), HASH_A))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database_write_failed");
        assertThat(store.facts).isEmpty();
    }

    @Test
    void acceptsCompositeWhenOnlyStageCostsAreAvailable() {
        MemoryStore store = new MemoryStore();
        store.wallet = new OperatingFactIngestionService.WalletSnapshot(300, 70, 30);
        store.allocation = new OperatingFactIngestionService.PackageAllocation(
                BigDecimal.ZERO, 1_000_000, "FREE_OR_ADMIN_GRANT");
        String payload = compositePayload()
                .replace("\"provider_input_cost_amount\":4,\"provider_output_cost_amount\":6,\"provider_total_cost_amount\":10,", "")
                .replace("\"provider_cost_currency\":\"CNY\",\"usage_estimated\":false,\"price_missing\":false,",
                        "\"provider_cost_currency\":\"CNY\",\"usage_estimated\":false,\"price_missing\":true,");

        assertThat(service(store).ingest(bytes(payload), HASH_A))
                .isEqualTo(OperatingFactIngestionService.Result.ACCEPTED);
        assertThat(store.facts).extracting(OperatingFactIngestionService.FactLine::providerCostAmount)
                .containsExactly(BigDecimal.valueOf(3), BigDecimal.valueOf(7));
        assertThat(store.facts).extracting(OperatingFactIngestionService.FactLine::allocatedUsageRevenueAmount)
                .containsExactly(new BigDecimal("120.000000"), new BigDecimal("180.000000"));
    }

    @Test
    void acceptsV2ProviderOnlyRetryCostAndAllocatesRevenueOnlyToCustomerBillableStage() {
        MemoryStore store = new MemoryStore();
        store.wallet = new OperatingFactIngestionService.WalletSnapshot(300, 30, 10);

        String payload = """
                {"version":"USAGE_ECONOMICS_V2","event_id":"%s","request_id":"req-v2-retry",
                 "reservation_id":"91000000-0000-4000-8000-000000000005",
                 "tenant_id":"%s","user_id":"%s","model_code":"text-basic",
                 "upstream_model":"deepseek-chat","provider_id":"91000000-0000-4000-8000-000000000006",
                 "channel_id":"91000000-0000-4000-8000-000000000007",
                 "input_tokens":30,"output_tokens":10,"total_tokens":40,
                 "billing_mode":"WALLET_ONLY","package_settled_tokens":0,"wallet_settled_amount":300,
                 "usage_estimated":true,"price_missing":true,
                 "stages":[
                   {"stage_index":0,"stage_type":"FINAL_RESPONSE","logical_model_code":"text-basic",
                    "upstream_model":"qwen-plus","provider_id":"91000000-0000-4000-8000-000000000006",
                    "channel_id":"91000000-0000-4000-8000-000000000007",
                    "input_tokens":90,"output_tokens":9,"total_tokens":99,
                    "provider_input_cost_amount":2,"provider_output_cost_amount":1,"provider_total_cost_amount":3,
                    "provider_cost_currency":"CNY","usage_source":"PROVIDER_ACTUAL",
                    "usage_estimated":false,
                    "customer_billable":false,"provider_billable":true,"reconciliation_status":"PENDING"},
                   {"stage_index":1,"stage_type":"FINAL_RESPONSE","logical_model_code":"text-basic",
                    "upstream_model":"deepseek-chat","provider_id":"91000000-0000-4000-8000-000000000008",
                    "channel_id":"91000000-0000-4000-8000-000000000009",
                    "input_tokens":30,"output_tokens":10,"total_tokens":40,
                    "provider_input_cost_amount":2,"provider_output_cost_amount":5,"provider_total_cost_amount":7,
                    "provider_cost_currency":"CNY","usage_source":"LOCAL_ESTIMATED",
                    "usage_estimated":true,
                    "customer_billable":true,"provider_billable":true,"reconciliation_status":"PENDING"}
                 ]}
                """.formatted(EVENT, TENANT, USER);

        assertThat(service(store).ingest(bytes(payload), HASH_A))
                .isEqualTo(OperatingFactIngestionService.Result.ACCEPTED);
        assertThat(store.facts).extracting(OperatingFactIngestionService.FactLine::providerCostAmount)
                .containsExactly(BigDecimal.valueOf(3), BigDecimal.valueOf(7));
        assertThat(store.facts).extracting(
                        OperatingFactIngestionService.FactLine::allocatedUsageRevenueAmount)
                .containsExactly(new BigDecimal("0.000000"), new BigDecimal("300.000000"));
        assertThat(store.facts).extracting(OperatingFactIngestionService.FactLine::estimated)
                .containsExactly(false, true);
    }

    @Test
    void rejectsV2StageWhenCacheCategoriesExceedInputTokens() {
        MemoryStore store = new MemoryStore();
        String payload = v2DetailedPayload(true, true)
                .replace("\"cache_write_tokens\":2", "\"cache_write_tokens\":7");

        assertThat(service(store).ingest(bytes(payload), HASH_A))
                .isEqualTo(OperatingFactIngestionService.Result.INVALID);
        assertThat(store.audits).containsExactly("invalid_stage_contract");
        assertThat(store.facts).isEmpty();
    }

    @Test
    void rejectsV2StageWhenCategoryCostsDoNotReconcile() {
        MemoryStore store = new MemoryStore();
        String payload = v2DetailedPayload(true, true)
                .replace("\"provider_uncached_input_cost_amount\":1",
                        "\"provider_uncached_input_cost_amount\":2");

        assertThat(service(store).ingest(bytes(payload), HASH_A))
                .isEqualTo(OperatingFactIngestionService.Result.INVALID);
        assertThat(store.audits).containsExactly("invalid_stage_contract");
        assertThat(store.facts).isEmpty();
    }

    @Test
    void rejectsV2NonProviderBillableStageWithDetailedCosts() {
        MemoryStore store = new MemoryStore();
        String payload = v2DetailedPayload(false, false);

        assertThat(service(store).ingest(bytes(payload), HASH_A))
                .isEqualTo(OperatingFactIngestionService.Result.INVALID);
        assertThat(store.audits).containsExactly("invalid_stage_contract");
        assertThat(store.facts).isEmpty();
    }

    @Test
    void acceptsV2NonEconomicFailedAttemptWithoutCreatingFact() {
        MemoryStore store = new MemoryStore();
        String payload = """
                {"version":"USAGE_ECONOMICS_V2","event_id":"%s","request_id":"req-v2-no-usage",
                 "reservation_id":"91000000-0000-4000-8000-000000000005",
                 "tenant_id":"%s","user_id":"%s","model_code":"text-basic",
                 "input_tokens":0,"output_tokens":0,"total_tokens":0,
                 "billing_mode":"PACKAGE_ONLY","package_settled_tokens":0,"wallet_settled_amount":0,
                 "usage_estimated":true,"price_missing":true,
                 "stages":[
                   {"stage_index":0,"stage_type":"FINAL_RESPONSE","logical_model_code":"text-basic",
                    "input_tokens":0,"output_tokens":0,"total_tokens":0,
                    "usage_source":"LOCAL_ESTIMATED","usage_estimated":true,"price_missing":true,
                    "customer_billable":false,"provider_billable":false,
                    "reconciliation_status":"NOT_APPLICABLE"}
                 ]}
                """.formatted(EVENT, TENANT, USER);

        assertThat(service(store).ingest(bytes(payload), HASH_A))
                .isEqualTo(OperatingFactIngestionService.Result.ACCEPTED);
        assertThat(store.audits).isEmpty();
        assertThat(store.facts).isEmpty();
    }

    @Test
    void nonZeroCatalogPriceFromAdminGrantAllocatesZeroRevenue() {
        MemoryStore store = new MemoryStore();
        store.wallet = new OperatingFactIngestionService.WalletSnapshot(300, 70, 30);
        store.allocation = new OperatingFactIngestionService.PackageAllocation(
                BigDecimal.valueOf(1900), 1_000_000, "ADMIN_GRANT");

        assertThat(service(store).ingest(bytes(compositePayload()), HASH_A))
                .isEqualTo(OperatingFactIngestionService.Result.ACCEPTED);
        assertThat(store.facts).extracting(
                OperatingFactIngestionService.FactLine::allocatedUsageRevenueAmount)
                .containsExactly(new BigDecimal("120.000000"), new BigDecimal("180.000000"));
    }

    @Test
    void rejectsInvalidTotalsTenantAndWalletReconciliationAndAuditsEachFailure() {
        MemoryStore store = new MemoryStore();
        OperatingFactIngestionService service = service(store);

        assertThat(service.ingest(bytes(compositePayload().replace("\"total_tokens\":100", "\"total_tokens\":99")), HASH_A))
                .isEqualTo(OperatingFactIngestionService.Result.INVALID);
        assertThat(service.ingest(bytes(compositePayload().replace(TENANT.toString(), "00000000-0000-0000-0000-000000000000")), HASH_B))
                .isEqualTo(OperatingFactIngestionService.Result.INVALID);
        store.wallet = new OperatingFactIngestionService.WalletSnapshot(299, 70, 30);
        assertThat(service.ingest(bytes(compositePayload()), "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"))
                .isEqualTo(OperatingFactIngestionService.Result.INVALID);

        assertThat(store.audits).hasSize(3);
        assertThat(store.facts).isEmpty();
    }

    private OperatingFactIngestionService service(MemoryStore store) {
        return new OperatingFactIngestionService(
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), store);
    }

    private String compositePayload() {
        return """
                {"version":"USAGE_ECONOMICS_V1","event_id":"%s","request_id":"req-1",
                 "reservation_id":"91000000-0000-4000-8000-000000000005",
                 "tenant_id":"%s","user_id":"%s","package_instance_id":"%s",
                 "model_code":"composite","upstream_model":"workflow","provider_id":"91000000-0000-4000-8000-000000000006",
                 "channel_id":"91000000-0000-4000-8000-000000000007","input_tokens":70,"output_tokens":30,"total_tokens":100,
                 "billing_mode":"HYBRID","package_settled_tokens":100,"wallet_settled_amount":300,
                 "provider_input_cost_amount":4,"provider_output_cost_amount":6,"provider_total_cost_amount":10,
                 "provider_cost_currency":"CNY","usage_estimated":false,"price_missing":false,
                 "stages":[
                   {"stage_index":0,"stage_type":"CHAT","logical_model_code":"text-basic","upstream_model":"qwen-plus",
                    "provider_id":"91000000-0000-4000-8000-000000000006","channel_id":"91000000-0000-4000-8000-000000000007",
                    "input_tokens":30,"output_tokens":10,"total_tokens":40,"provider_input_cost_amount":2,"provider_output_cost_amount":1,"provider_total_cost_amount":3,"provider_cost_currency":"CNY"},
                   {"stage_index":1,"stage_type":"CHAT","logical_model_code":"text-reasoning","upstream_model":"deepseek-chat",
                    "provider_id":"91000000-0000-4000-8000-000000000008","channel_id":"91000000-0000-4000-8000-000000000009",
                    "input_tokens":40,"output_tokens":20,"total_tokens":60,"provider_input_cost_amount":2,"provider_output_cost_amount":5,"provider_total_cost_amount":7,"provider_cost_currency":"CNY"}
                 ]}
                """.formatted(EVENT, TENANT, USER, PACKAGE);
    }

    private String v2DetailedPayload(boolean providerBillable, boolean includeProviderTotals) {
        String providerTotals = includeProviderTotals
                ? "\"provider_input_cost_amount\":2,\"provider_output_cost_amount\":3,"
                    + "\"provider_total_cost_amount\":5,\"provider_cost_currency\":\"CNY\","
                : "";
        return """
                {"version":"USAGE_ECONOMICS_V2","event_id":"%s","request_id":"req-v2-detail",
                 "reservation_id":"91000000-0000-4000-8000-000000000005",
                 "tenant_id":"%s","user_id":"%s","model_code":"text-basic",
                 "input_tokens":10,"output_tokens":4,"total_tokens":14,
                 "billing_mode":"PACKAGE_ONLY","package_settled_tokens":0,"wallet_settled_amount":0,
                 "usage_estimated":false,"price_missing":true,
                 "stages":[
                   {"stage_index":0,"stage_type":"FINAL_RESPONSE","logical_model_code":"text-basic",
                    "input_tokens":10,"output_tokens":4,"total_tokens":14,
                    %s
                    "usage_source":"PROVIDER_ACTUAL","usage_estimated":false,
                    "cache_hit_tokens":4,"cache_write_tokens":2,"reasoning_tokens":1,
                    "customer_billable":true,"provider_billable":%s,"reconciliation_status":"PENDING",
                    "price_missing":%s,
                    "provider_uncached_input_cost_amount":1,"provider_cache_read_cost_amount":1,
                    "provider_cache_write_cost_amount":0,"provider_normal_output_cost_amount":2,
                    "provider_reasoning_cost_amount":1}
                 ]}
                """.formatted(EVENT, TENANT, USER, providerTotals,
                        providerBillable, !includeProviderTotals);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private OperatingReportQueryService.Filter reportFilter(
            String model, String upstream, String channel, String provider) {
        return new OperatingReportQueryService.Filter(
                LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 8),
                OperatingReportQueryService.Grain.DAY, TENANT, USER, null,
                model, upstream, channel, provider, "HYBRID", "CNY");
    }

    private static final class FactCapture implements OperatingFactService.Store {
        private final List<OperatingFactService.Fact> facts = new ArrayList<>();
        @Override public OperatingFactService.Claim claim(String sourceEventId, String payloadHash) {
            return OperatingFactService.Claim.CLAIMED;
        }
        @Override public void insert(OperatingFactService.Fact fact) { facts.add(fact); }
    }

    private static final class MemoryStore implements OperatingFactIngestionService.Store {
        volatile String claimedHash;
        boolean failSecondLine;
        OperatingFactIngestionService.WalletSnapshot wallet;
        OperatingFactIngestionService.PackageAllocation allocation;

        /**
         * 摄入层向存储询问的维度。方案 C 起它决定"收入要摊到哪一份额度上"，
         * 因此它必须等于事件自己的维度——记下来以便断言，而不是只信一个返回值。
         */
        volatile String lastAllocationDimension;
        BigDecimal providerP95;
        final List<OperatingFactIngestionService.FactLine> facts = new ArrayList<>();
        final List<String> audits = new ArrayList<>();

        @Override public synchronized OperatingFactIngestionService.Claim claim(UUID eventId, String payloadHash) {
            if (claimedHash == null) {
                claimedHash = payloadHash;
                return OperatingFactIngestionService.Claim.CLAIMED;
            }
            return claimedHash.equals(payloadHash)
                    ? OperatingFactIngestionService.Claim.REPLAYED
                    : OperatingFactIngestionService.Claim.CONFLICT;
        }
        @Override public boolean tenantUserExists(UUID tenantId, UUID userId) {
            return TENANT.equals(tenantId) && USER.equals(userId);
        }
        @Override public OperatingFactIngestionService.WalletSnapshot walletSnapshot(
                UUID tenantId, UUID userId, String requestId) { return wallet; }
        @Override public OperatingFactIngestionService.PackageAllocation packageAllocation(
                UUID packageInstanceId, UUID tenantId, UUID userId, String usageDimension) {
            this.lastAllocationDimension = usageDimension;
            return allocation;
        }
        @Override public synchronized void insertAll(List<OperatingFactIngestionService.FactLine> lines) {
            List<OperatingFactIngestionService.FactLine> pending = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (failSecondLine && i == 1) throw new IllegalStateException("database_write_failed");
                pending.add(lines.get(i));
            }
            facts.addAll(pending);
        }
        @Override public BigDecimal providerCostP95(String model, String provider, Instant before) {
            return providerP95;
        }
        @Override public void audit(UUID eventId, String payloadHash, String reason) { audits.add(reason); }
    }

    private static final class RecordingAlerts implements OperatingFactIngestionService.AlertSink {
        int providerCalls;
        int packageCalls;
        final List<UUID> packageIds = new ArrayList<>();
        final List<BigDecimal> packageCosts = new ArrayList<>();
        final List<List<UUID>> evidence = new ArrayList<>();
        @Override public void providerCost(String model, String provider, BigDecimal requestCost,
                BigDecimal sevenDayP95, boolean missing, List<UUID> factIds) {
            providerCalls++; evidence.add(factIds);
        }
        @Override public void packageMargin(UUID packageId, BigDecimal revenue,
                BigDecimal providerCost, List<UUID> factIds) {
            packageCalls++; packageIds.add(packageId); packageCosts.add(providerCost);
            evidence.add(factIds);
        }
    }
}
