package com.yuzhihuihe.apiplatform.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class UsageQueryServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("61000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_USER_ID = UUID.fromString("71000000-0000-4000-8000-000000000002");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private UsageQueryService service;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void reset() throws Exception {
        execute("TRUNCATE gateway.usage_log, gateway.quota_ledger, gateway.quota_reservation, "
                + "gateway.api_key, gateway.package_instance_model_quota, "
                + "gateway.package_instance, gateway.consumed_event CASCADE");
        seedPackage("81000000-0000-4000-8000-000000000011", USER_ID, "ACTIVE", 30, 120);
        seedPackage("81000000-0000-4000-8000-000000000012", USER_ID, "QUEUED", 0, 0);
        seedPackage("81000000-0000-4000-8000-000000000021", OTHER_USER_ID, "ACTIVE", 0, 10);
        execute("""
                INSERT INTO gateway.package_instance_model_quota(
                    id, package_instance_id, model_code, token_quota, reserved_tokens, used_tokens)
                VALUES ('8a000000-0000-4000-8000-000000000001',
                        '81000000-0000-4000-8000-000000000011', 'text-pro', 500, 20, 80)
                """);
        seedApiKey("83000000-0000-4000-8000-000000000011", USER_ID,
                "81000000-0000-4000-8000-000000000011", "sk-yzh-user");
        seedApiKey("83000000-0000-4000-8000-000000000021", OTHER_USER_ID,
                "81000000-0000-4000-8000-000000000021", "sk-yzh-other");
        seedReservation("86000000-0000-4000-8000-000000000011", "req-user-001", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-pro", "SETTLED", 100, 40, 60, 60L);
        seedReservation("86000000-0000-4000-8000-000000000012", "req-user-002", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "SETTLED", 50, 20, 30);
        seedReservation("86000000-0000-4000-8000-000000000013", "req-user-open", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "RESERVED", 30, null, null);
        seedReservation("86000000-0000-4000-8000-000000000021", "req-other-001", OTHER_USER_ID,
                "83000000-0000-4000-8000-000000000021",
                "81000000-0000-4000-8000-000000000021", "text-basic", "SETTLED", 10, 4, 6);
        seedLedger("87000000-0000-4000-8000-000000000011",
                "86000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-pro", "SETTLE",
                -100, 900, 40L, 440L);
        seedLedger("87000000-0000-4000-8000-000000000021",
                "86000000-0000-4000-8000-000000000021",
                "81000000-0000-4000-8000-000000000021", "text-basic", "SETTLE", -10, 990);
        seedUsage("88000000-0000-4000-8000-000000000011", "req-user-001",
                "86000000-0000-4000-8000-000000000011", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-pro", "SUCCEEDED", 100);
        seedUsage("88000000-0000-4000-8000-000000000012", "req-user-002",
                "86000000-0000-4000-8000-000000000012", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "FAILED", 50);
        seedUsage("88000000-0000-4000-8000-000000000021", "req-other-001",
                "86000000-0000-4000-8000-000000000021", OTHER_USER_ID,
                "83000000-0000-4000-8000-000000000021",
                "81000000-0000-4000-8000-000000000021", "text-basic", "SUCCEEDED", 10);

        ConnectionFactoryOptions options = ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                .option(ConnectionFactoryOptions.PORT, POSTGRES.getFirstMappedPort())
                .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
                .build();
        ObjectMapper objectMapper = new ObjectMapper();
        UsageQueryRepository repository = new UsageQueryRepository(
                DatabaseClient.create(ConnectionFactories.get(options)));
        service = new UsageQueryService(repository, objectMapper);
    }

    @Test
    void summarizesOnlyTheCurrentUsersActiveAndQueuedPackages() {
        UsageQueryService.QuotaSummary summary = service.summary(TENANT_ID, USER_ID).block();

        assertThat(summary).isNotNull();
        assertThat(summary.activePackage().packageCode()).isEqualTo("STARTER");
        assertThat(summary.activePackage().totalTokens()).isEqualTo(1000);
        assertThat(summary.activePackage().reservedTokens()).isEqualTo(30);
        assertThat(summary.activePackage().usedTokens()).isEqualTo(120);
        assertThat(summary.activePackage().remainingTokens()).isEqualTo(850);
        assertThat(summary.activePackage().dailyRequestLimit()).isEqualTo(200);
        assertThat(summary.activePackage().allowedModels()).containsExactly("text-basic");
        assertThat(summary.activePackage().modelQuotas()).singleElement()
                .satisfies(quota -> {
                    assertThat(quota.modelCode()).isEqualTo("text-pro");
                    assertThat(quota.remainingTokens()).isEqualTo(400);
                });
        assertThat(summary.queuedPackages()).hasSize(1);
    }

    @Test
    void paginatesUserLedgerAndUsageWithoutCrossUserRows() throws Exception {
        UsageQueryService.PageResult<UsageQueryService.LedgerEntry> ledger = service
                .ledger(TENANT_ID, USER_ID, new UsageQueryService.PageRequest(0, 10), null, null)
                .block();
        UsageQueryService.PageResult<UsageQueryService.UsageEntry> usage = service
                .usage(TENANT_ID, USER_ID, new UsageQueryService.PageRequest(0, 1), null, null, null)
                .block();

        assertThat(ledger.total()).isEqualTo(1);
        assertThat(ledger.items()).extracting(UsageQueryService.LedgerEntry::requestId)
                .containsExactly("req-user-001");
        assertThat(usage.total()).isEqualTo(2);
        assertThat(usage.items()).hasSize(1);
        assertThat(usage.items().getFirst().userId()).isEqualTo(USER_ID);
        assertThat(usage.items().getFirst().upstreamModel()).isNull();
        assertThat(usage.items().getFirst().stages()).isEmpty();

        execute("UPDATE gateway.usage_log SET upstream_model = 'qwen3.7-plus' "
                + "WHERE request_id = 'req-user-001'");

        UsageQueryService.PageResult<UsageQueryService.UsageEntry> filtered = service
                .usage(TENANT_ID, USER_ID, new UsageQueryService.PageRequest(0, 10),
                        "text-pro", "SUCCEEDED", "req-user-001")
                .block();
        assertThat(filtered.total()).isEqualTo(1);
        assertThat(filtered.items().getFirst().requestId()).isEqualTo("req-user-001");
        assertThat(filtered.items().getFirst().upstreamModel()).isEqualTo("qwen3.7-plus");
    }

    @Test
    void calculatesPlatformAddedLatencyP95FromRecentNonNullCurrentUserRows() throws Exception {
        seedReservation("86000000-0000-4000-8000-000000000014", "req-user-old", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "SETTLED", 10, 4, 6);
        seedUsage("88000000-0000-4000-8000-000000000014", "req-user-old",
                "86000000-0000-4000-8000-000000000014", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "SUCCEEDED", 10);
        seedReservation("86000000-0000-4000-8000-000000000015", "req-user-null", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "SETTLED", 10, 4, 6);
        seedUsage("88000000-0000-4000-8000-000000000015", "req-user-null",
                "86000000-0000-4000-8000-000000000015", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "SUCCEEDED", 10);
        execute("""
                UPDATE gateway.usage_log
                SET platform_added_latency_ms = CASE request_id
                    WHEN 'req-user-001' THEN 100
                    WHEN 'req-user-002' THEN 900
                    WHEN 'req-user-old' THEN 9999
                    WHEN 'req-other-001' THEN 8888
                    ELSE NULL
                END,
                created_at = CASE WHEN request_id = 'req-user-old'
                    THEN now() - interval '25 hours' ELSE now() END
                """);

        UsageQueryService.UsageMetrics metrics = service.metrics(TENANT_ID, USER_ID).block();

        assertThat(metrics).isNotNull();
        assertThat(metrics.sampleCount()).isEqualTo(2);
        assertThat(metrics.platformAddedP95Ms()).isEqualTo(900);
    }

    @Test
    void exposesGlobalAdminUsageReservationsAndProviderPrices() throws Exception {
        execute("""
                UPDATE gateway.provider_model_price
                SET cache_read_price_per_million = 11,
                    cache_write_price_per_million = 12,
                    reasoning_price_per_million = 13
                WHERE id = (
                    SELECT price.id
                    FROM gateway.provider_model_price price
                    JOIN gateway.provider provider ON provider.id = price.provider_id
                    WHERE provider.code = 'bailian'
                    ORDER BY price.id
                    LIMIT 1)
                """);
        UsageQueryService.PageResult<UsageQueryService.UsageEntry> usage = service
                .adminUsage(new UsageQueryService.PageRequest(0, 10), null, null, null, null,
                        null, null)
                .block();
        UsageQueryService.PageResult<UsageQueryService.ReservationEntry> reservations = service
                .adminReservations(new UsageQueryService.PageRequest(0, 10), null, null, null, false,
                        null, null)
                .block();
        UsageQueryService.PageResult<UsageQueryService.ProviderPriceEntry> prices = service
                .providerPrices(new UsageQueryService.PageRequest(0, 20), "bailian", null,
                        null, null)
                .block();
        UsageQueryService.PageResult<UsageQueryService.AdminLedgerEntry> ledger = service
                .adminLedger(new UsageQueryService.PageRequest(0, 20), null, null, null,
                        null, null)
                .block();

        assertThat(usage.total()).isEqualTo(3);
        assertThat(usage.items()).extracting(UsageQueryService.UsageEntry::userId)
                .contains(USER_ID, OTHER_USER_ID);
        assertThat(reservations.total()).isEqualTo(4);
        assertThat(reservations.items()).extracting(UsageQueryService.ReservationEntry::status)
                .contains("RESERVED", "SETTLED");
        assertThat(prices.total()).isGreaterThanOrEqualTo(6);
        assertThat(prices.items()).allSatisfy(price ->
                assertThat(price.providerCode()).isEqualTo("bailian"));
        assertThat(prices.items()).anySatisfy(price -> {
            assertThat(price.cacheReadPricePerMillion()).isEqualTo(11);
            assertThat(price.cacheWritePricePerMillion()).isEqualTo(12);
            assertThat(price.reasoningPricePerMillion()).isEqualTo(13);
        });
        assertThat(ledger.total()).isEqualTo(2);
        assertThat(ledger.items()).extracting(UsageQueryService.AdminLedgerEntry::userId)
                .contains(USER_ID, OTHER_USER_ID);
    }

    @Test
    void exposesSanitizedStagesOnlyToAdminUsageInOnePageEnrichment() throws Exception {
        execute("""
                INSERT INTO gateway.usage_stage(
                    id, usage_log_id, stage_index, stage_type, logical_model_code,
                    model_id, provider_id, channel_id, upstream_model,
                    input_tokens, output_tokens, total_tokens, usage_estimated,
                    duration_ms, outcome, error_category,
                    provider_cost_amount, provider_cost_currency, price_estimated)
                VALUES
                    (gen_random_uuid(), '88000000-0000-4000-8000-000000000011', 0,
                     'FINAL_RESPONSE', 'vision-basic',
                     (SELECT id FROM gateway.model WHERE code = 'vision-basic'),
                     (SELECT id FROM gateway.provider WHERE code = 'bailian'),
                     (SELECT id FROM gateway.channel WHERE code = 'bailian-primary'),
                     'qwen-vl-plus', 40, 60, 100, false, 45, 'SUCCEEDED', NULL,
                     7, 'CNY', false),
                    (gen_random_uuid(), '88000000-0000-4000-8000-000000000012', 0,
                     'FINAL_RESPONSE', 'text-basic',
                     (SELECT id FROM gateway.model WHERE code = 'text-basic'),
                     (SELECT id FROM gateway.provider WHERE code = 'bailian'),
                     (SELECT id FROM gateway.channel WHERE code = 'bailian-primary'),
                     'qwen-plus', 20, 30, 50, true, 50, 'FAILED', 'provider_error',
                     NULL, NULL, true)
                """);

        UsageQueryService.PageResult<UsageQueryService.UsageEntry> userUsage = service
                .usage(TENANT_ID, USER_ID, new UsageQueryService.PageRequest(0, 10),
                        null, null, null)
                .block();
        UsageQueryService.PageResult<UsageQueryService.UsageEntry> adminUsage = service
                .adminUsage(new UsageQueryService.PageRequest(0, 10), USER_ID,
                        null, null, null, null, null)
                .block();

        assertThat(userUsage.items()).allSatisfy(entry -> assertThat(entry.stages()).isEmpty());
        assertThat(adminUsage.items()).allSatisfy(entry -> assertThat(entry.stages()).hasSize(1));
        UsageQueryService.UsageStageEntry successful = adminUsage.items().stream()
                .filter(entry -> entry.requestId().equals("req-user-001"))
                .findFirst().orElseThrow().stages().getFirst();
        assertThat(successful.logicalModelCode()).isEqualTo("vision-basic");
        assertThat(successful.providerCode()).isEqualTo("bailian");
        assertThat(successful.channelCode()).isEqualTo("bailian-primary");
        assertThat(successful.upstreamModel()).isEqualTo("qwen-vl-plus");
        assertThat(successful.totalTokens()).isEqualTo(100);
        assertThat(successful.providerCostAmount()).isEqualTo(7);
        assertThat(successful.providerCostCurrency()).isEqualTo("CNY");
        assertThat(successful.priceEstimated()).isFalse();
        var stageJson = new ObjectMapper().valueToTree(successful);
        assertThat(stageJson.has("logicalModelCode")).isTrue();
        assertThat(stageJson.has("componentModelCode")).isFalse();
        assertThat(stageJson.has("usageEstimated")).isFalse();
        assertThat(stageJson.has("errorCategory")).isFalse();
    }

    @Test
    void sortsEveryAdminListAscendingAndDescendingWithEndpointLocalKeys() {
        UsageQueryService.PageRequest page = new UsageQueryService.PageRequest(0, 100);

        List<String> usageAscending = service.adminUsage(
                        page, null, null, null, null, "requestId", "asc").block().items().stream()
                .map(UsageQueryService.UsageEntry::requestId).toList();
        List<String> usageDescending = service.adminUsage(
                        page, null, null, null, null, "requestId", "desc").block().items().stream()
                .map(UsageQueryService.UsageEntry::requestId).toList();
        List<String> ledgerAscending = service.adminLedger(
                        page, null, null, null, "requestId", "asc").block().items().stream()
                .map(UsageQueryService.AdminLedgerEntry::requestId).toList();
        List<String> ledgerDescending = service.adminLedger(
                        page, null, null, null, "requestId", "desc").block().items().stream()
                .map(UsageQueryService.AdminLedgerEntry::requestId).toList();
        List<String> reservationAscending = service.adminReservations(
                        page, null, null, null, false, "modelCode", "asc").block().items().stream()
                .map(UsageQueryService.ReservationEntry::modelCode).toList();
        List<String> reservationDescending = service.adminReservations(
                        page, null, null, null, false, "modelCode", "desc").block().items().stream()
                .map(UsageQueryService.ReservationEntry::modelCode).toList();
        List<String> priceAscending = service.providerPrices(
                        page, null, null, "providerCode", "asc").block().items().stream()
                .map(UsageQueryService.ProviderPriceEntry::providerCode).toList();
        List<String> priceDescending = service.providerPrices(
                        page, null, null, "providerCode", "desc").block().items().stream()
                .map(UsageQueryService.ProviderPriceEntry::providerCode).toList();

        assertThat(usageAscending).containsExactly("req-other-001", "req-user-001", "req-user-002");
        assertThat(usageDescending).containsExactly("req-user-002", "req-user-001", "req-other-001");
        assertThat(ledgerAscending).containsExactly("req-other-001", "req-user-001");
        assertThat(ledgerDescending).containsExactly("req-user-001", "req-other-001");
        assertThat(reservationAscending).isSorted();
        assertThat(reservationDescending).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(priceAscending).isSorted();
        assertThat(priceDescending).isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void usesCreatedAtDescendingAndIdDescendingAsAdminDefaults() throws Exception {
        execute("UPDATE gateway.usage_log SET created_at = TIMESTAMPTZ '2026-07-31 12:00:00Z'");
        execute("UPDATE gateway.quota_reservation SET created_at = TIMESTAMPTZ '2026-07-31 12:00:00Z'");

        UsageQueryService.PageRequest page = new UsageQueryService.PageRequest(0, 100);
        UsageQueryService.PageResult<UsageQueryService.UsageEntry> usage = service.adminUsage(
                page, null, null, null, null, null, null).block();
        UsageQueryService.PageResult<UsageQueryService.AdminLedgerEntry> ledger = service.adminLedger(
                page, null, null, null, null, null).block();
        UsageQueryService.PageResult<UsageQueryService.ReservationEntry> reservations =
                service.adminReservations(
                        page, null, null, null, false, null, null).block();

        assertThat(usage.items()).extracting(UsageQueryService.UsageEntry::id)
                .containsExactly(
                        UUID.fromString("88000000-0000-4000-8000-000000000021"),
                        UUID.fromString("88000000-0000-4000-8000-000000000012"),
                        UUID.fromString("88000000-0000-4000-8000-000000000011"));
        assertThat(ledger.items()).extracting(UsageQueryService.AdminLedgerEntry::id)
                .containsExactly(
                        UUID.fromString("87000000-0000-4000-8000-000000000021"),
                        UUID.fromString("87000000-0000-4000-8000-000000000011"));
        assertThat(reservations.items()).extracting(UsageQueryService.ReservationEntry::id)
                .containsExactly(
                        UUID.fromString("86000000-0000-4000-8000-000000000021"),
                        UUID.fromString("86000000-0000-4000-8000-000000000013"),
                        UUID.fromString("86000000-0000-4000-8000-000000000012"),
                        UUID.fromString("86000000-0000-4000-8000-000000000011"));
    }

    @Test
    void usesEffectiveProviderModelAndIdAsProviderPriceDefaults() throws Exception {
        UUID latestPriceId = UUID.fromString("8f000000-0000-4000-8000-000000000099");
        execute("""
                INSERT INTO gateway.provider_model_price(
                    id, provider_id, model_id, input_price_per_million, output_price_per_million,
                    currency, internal_exchange_rate, official_source, effective_from)
                SELECT '%s', provider.id, model.id, 1, 2, 'CNY', 1,
                       'https://example.test/phase7-price', TIMESTAMPTZ '2099-01-01 00:00:00Z'
                FROM gateway.provider provider
                CROSS JOIN gateway.model model
                WHERE provider.code = 'bailian' AND model.code = 'text-basic'
                """.formatted(latestPriceId));

        List<UsageQueryService.ProviderPriceEntry> prices = service.providerPrices(
                        new UsageQueryService.PageRequest(0, 100), null, null, null, null)
                .block().items();
        Comparator<UsageQueryService.ProviderPriceEntry> expectedOrder = Comparator
                .comparing(UsageQueryService.ProviderPriceEntry::effectiveFrom,
                        Comparator.reverseOrder())
                .thenComparing(UsageQueryService.ProviderPriceEntry::providerCode)
                .thenComparing(UsageQueryService.ProviderPriceEntry::modelCode)
                .thenComparing(price -> price.id().toString());

        assertThat(prices).first().extracting(UsageQueryService.ProviderPriceEntry::id)
                .isEqualTo(latestPriceId);
        assertThat(prices).isSortedAccordingTo(expectedOrder);
    }

    @Test
    void usesIdTieBreakerInRequestedDirectionForEqualProviderPriceSortKeys() throws Exception {
        UUID lowerId = UUID.fromString("8e000000-0000-4000-8000-000000000091");
        UUID higherId = UUID.fromString("8e000000-0000-4000-8000-000000000092");
        execute("""
                INSERT INTO gateway.provider_model_price(
                    id, provider_id, model_id, input_price_per_million, output_price_per_million,
                    currency, internal_exchange_rate, official_source, effective_from)
                SELECT seed.id, provider.id, model.id, 1, 2, 'CNY', 1,
                       'https://example.test/phase7-price-tie', seed.effective_from
                FROM (VALUES
                    ('%s'::uuid, TIMESTAMPTZ '2097-01-01 00:00:00Z'),
                    ('%s'::uuid, TIMESTAMPTZ '2098-01-01 00:00:00Z')
                ) AS seed(id, effective_from)
                CROSS JOIN gateway.provider provider
                CROSS JOIN gateway.model model
                WHERE provider.code = 'bailian' AND model.code = 'text-basic'
                """.formatted(lowerId, higherId));

        List<UUID> ascending = service.providerPrices(
                        new UsageQueryService.PageRequest(0, 100), "bailian", "text-basic",
                        "currency", "asc")
                .block().items().stream().map(UsageQueryService.ProviderPriceEntry::id)
                .filter(id -> id.equals(lowerId) || id.equals(higherId)).toList();
        List<UUID> descending = service.providerPrices(
                        new UsageQueryService.PageRequest(0, 100), "bailian", "text-basic",
                        "currency", "desc")
                .block().items().stream().map(UsageQueryService.ProviderPriceEntry::id)
                .filter(id -> id.equals(lowerId) || id.equals(higherId)).toList();

        assertThat(ascending).containsExactly(lowerId, higherId);
        assertThat(descending).containsExactly(higherId, lowerId);
    }

    @Test
    void rejectsUnknownSortKeysAndDirectionsForEveryAdminList() {
        UsageQueryService.PageRequest page = new UsageQueryService.PageRequest(0, 20);

        assertInvalid(() -> service.adminUsage(
                page, null, null, null, null, "tenantId", "asc").block());
        assertInvalid(() -> service.adminUsage(
                page, null, null, null, null, "createdAt", "sideways").block());
        assertInvalid(() -> service.adminLedger(
                page, null, null, null, "tenantId", "asc").block());
        assertInvalid(() -> service.adminLedger(
                page, null, null, null, "createdAt", "sideways").block());
        assertInvalid(() -> service.adminReservations(
                page, null, null, null, false, "tenantId", "asc").block());
        assertInvalid(() -> service.adminReservations(
                page, null, null, null, false, "createdAt", "sideways").block());
        assertInvalid(() -> service.providerPrices(
                page, null, null, "internalExchangeRate", "asc").block());
        assertInvalid(() -> service.providerPrices(
                page, null, null, "effectiveFrom", "sideways").block());
    }

    @Test
    void exposesAuthoritativeUncoveredTokenAnomalies() throws Exception {
        execute("""
                UPDATE gateway.quota_reservation
                SET uncovered_tokens = CASE request_id
                    WHEN 'req-user-001' THEN 17
                    ELSE 0
                END
                """);

        UsageQueryService.PageResult<UsageQueryService.ReservationEntry> anomalies = service
                .adminReservations(new UsageQueryService.PageRequest(0, 20), null, null, null, true,
                        null, null)
                .block();

        assertThat(anomalies.total()).isEqualTo(1);
        assertThat(anomalies.items()).singleElement().satisfies(row -> {
            assertThat(row.requestId()).isEqualTo("req-user-001");
            assertThat(row.uncoveredTokens()).isEqualTo(17);
        });
    }

    /**
     * 每条用量记录上的真实扣量（{@code quotaCharge}）。
     *
     * <p>这条测试钉的是三件容易搞错的事：**预占不是实扣**（RESERVED 必须全空、
     * 不能把 30 报成已扣）、**差额不是消耗**（实扣 = 预占 − SETTLE 差额）、
     * 以及**"没有独立模型额度"与"扣了 0"不是一回事**（一个报 {@code null}、
     * 一个报 {@code 0}）。同时确认补这张附表不会让用量行变多。
     */
    @Test
    void reportsActualQuotaChargePerUsageRow() throws Exception {
        // fixture 里的 011 已带 SETTLE 账本行（model_token_delta = 40）：预占 100、
        // 套餐实结 60、独立模型池实扣 100 − 40 = 60。
        // 已释放：预占已放回，确认没有扣减 ⇒ 三项都是 0（不是 null）。
        seedReservation("86000000-0000-4000-8000-000000000016", "req-user-released", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "RELEASED", 20, null, null);
        seedLedger("87000000-0000-4000-8000-000000000032",
                "86000000-0000-4000-8000-000000000016",
                "81000000-0000-4000-8000-000000000011", "text-basic", "RESERVE",
                -20, 880, -20L, 500L);
        seedUsage("88000000-0000-4000-8000-000000000016", "req-user-released",
                "86000000-0000-4000-8000-000000000016", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "FAILED", 20);
        // 已过期：同上，但这次预留时**没有**独立模型额度 ⇒ 模型那项只能是 null。
        seedReservation("86000000-0000-4000-8000-000000000017", "req-user-expired", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "EXPIRED", 20, null, null);
        seedUsage("88000000-0000-4000-8000-000000000017", "req-user-expired",
                "86000000-0000-4000-8000-000000000017", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "FAILED", 20);
        // 图片调用：单位必须按用量维度映射成张，而不是积分。
        seedReservation("86000000-0000-4000-8000-000000000018", "req-user-image", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "image-basic", "SETTLED", 1, 0, 1);
        seedUsage("88000000-0000-4000-8000-000000000018", "req-user-image",
                "86000000-0000-4000-8000-000000000018", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "image-basic", "SUCCEEDED", 0, "IMAGE");

        // 仅预占：还未结算，三个实扣字段都必须是 null——预占 30 不是已扣 30。
        seedReservation("86000000-0000-4000-8000-000000000019", "req-user-reserved", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "RESERVED", 30, null, null);
        seedUsage("88000000-0000-4000-8000-000000000019", "req-user-reserved",
                "86000000-0000-4000-8000-000000000019", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "SUCCEEDED", 30);

        UsageQueryService.PageResult<UsageQueryService.UsageEntry> usage = service
                .usage(TENANT_ID, USER_ID, new UsageQueryService.PageRequest(0, 20),
                        null, null, null)
                .block();
        UsageQueryService.PageResult<UsageQueryService.UsageEntry> admin = service
                .adminUsage(new UsageQueryService.PageRequest(0, 20), null, null, null, null,
                        null, null)
                .block();

        // 补扣量附表不得让用量行重复，也不得改变分页总数。
        assertThat(usage.total()).isEqualTo(6);
        assertThat(usage.items()).hasSize(6);
        assertThat(admin.total()).isEqualTo(7);

        Map<String, UsageQueryService.UsageEntry> byRequest = usage.items().stream()
                .collect(Collectors.toMap(UsageQueryService.UsageEntry::requestId, entry -> entry));
        assertThat(byRequest.keySet()).containsExactlyInAnyOrder(
                "req-user-001", "req-user-002", "req-user-released", "req-user-expired",
                "req-user-image", "req-user-reserved");

        UsageQueryService.QuotaCharge settled = byRequest.get("req-user-001").quotaCharge();
        assertThat(settled).isNotNull();
        assertThat(settled.status()).isEqualTo("SETTLED");
        assertThat(settled.quotaUnit()).isEqualTo("CREDIT");
        assertThat(settled.packageChargedUnits()).isEqualTo(60);
        // 100 − 40：差额是调整量，实扣要反解出来，且**不可**与套餐实扣相加。
        assertThat(settled.modelChargedUnits()).isEqualTo(60);
        assertThat(settled.walletChargedAmount()).isZero();
        assertThat(settled.settledAt()).isNotNull();

        // 已结算但没有 SETTLE 账本行 ⇒ 模型实扣无从计算，只能是未知。
        UsageQueryService.QuotaCharge noLedger = byRequest.get("req-user-002").quotaCharge();
        assertThat(noLedger).isNotNull();
        assertThat(noLedger.status()).isEqualTo("SETTLED");
        assertThat(noLedger.packageChargedUnits()).isEqualTo(50);
        assertThat(noLedger.modelChargedUnits()).isNull();

        // 仅预占：还未结算，三个实扣字段都必须是 null——预占 30 不是已扣 30。
        UsageQueryService.QuotaCharge reserved = byRequest.get("req-user-reserved").quotaCharge();
        assertThat(reserved).isNotNull();
        assertThat(reserved.status()).isEqualTo("RESERVED");
        assertThat(reserved.quotaUnit()).isEqualTo("CREDIT");
        assertThat(reserved.packageChargedUnits()).isNull();
        assertThat(reserved.modelChargedUnits()).isNull();
        assertThat(reserved.walletChargedAmount()).isNull();
        assertThat(reserved.settledAt()).isNull();

        UsageQueryService.QuotaCharge released = byRequest.get("req-user-released").quotaCharge();
        assertThat(released).isNotNull();
        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(released.packageChargedUnits()).isZero();
        assertThat(released.modelChargedUnits()).isZero();
        assertThat(released.walletChargedAmount()).isZero();
        assertThat(released.settledAt()).isNull();

        UsageQueryService.QuotaCharge expired = byRequest.get("req-user-expired").quotaCharge();
        assertThat(expired).isNotNull();
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThat(expired.packageChargedUnits()).isZero();
        // 这次预留没有独立模型额度：0 会读成"模型额度一分没扣"，而事实是"不适用"。
        assertThat(expired.modelChargedUnits()).isNull();
        assertThat(expired.walletChargedAmount()).isZero();

        UsageQueryService.QuotaCharge image = byRequest.get("req-user-image").quotaCharge();
        assertThat(image).isNotNull();
        assertThat(image.status()).isEqualTo("SETTLED");
        assertThat(image.quotaUnit()).isEqualTo("IMAGE");
        assertThat(image.packageChargedUnits()).isEqualTo(1);
    }

    /**
     * 混合计费（钱包拆走一段）下，模型实扣必须相对**套餐承担的那一段**反解，
     * 而不是相对总预占。
     *
     * <p>这是 {@code reportsActualQuotaChargePerUsageRow} 看不见的那一半：那条测试的
     * 夹具把同一个数写进 {@code reserved_tokens} 与 {@code package_reserved_tokens}，
     * 于是两列取哪一列都对，缺陷被夹具本身掩盖了。生产上 978 笔预占恰好全是
     * {@code PACKAGE_ONLY}（两列相等），所以这个错误至今没有在真实数据上暴露——
     * 但只要有人把套餐用到接近耗尽、余额由钱包补上，它就会开始返回错数。
     *
     * <p>算术来自账本自己的恒等式（{@code QuotaReservationRepository.settle}）：
     * 模型池当初被扣的是 {@code package_reserved_tokens}，
     * SETTLE 行记的差额是 {@code package_reserved_tokens − modelCharge}，
     * 因此实扣 = {@code package_reserved_tokens − delta}。
     *
     * <p>用例里的数字刻意选成能一眼看出错在哪：总量 120、套餐承担 60、实扣 45
     * ⇒ 差额 15。正确答案 45；取错列会得到 120 − 15 = 105，**比模型池自己的预占
     * （60）还大**，是个不可能的值。
     */
    @Test
    void solvesModelChargeAgainstThePackageShareNotTheTotalReserve() throws Exception {
        seedHybridReservation("86000000-0000-4000-8000-0000000000a1", "req-user-hybrid",
                USER_ID, "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic",
                120, 60, 60);
        seedLedger("87000000-0000-4000-8000-0000000000a1",
                "86000000-0000-4000-8000-0000000000a1",
                "81000000-0000-4000-8000-000000000011", "text-basic", "SETTLE",
                0, 0, 15L, 0L);
        seedUsage("88000000-0000-4000-8000-0000000000a1", "req-user-hybrid",
                "86000000-0000-4000-8000-0000000000a1", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "SUCCEEDED", 100);

        UsageQueryService.PageResult<UsageQueryService.UsageEntry> usage = service
                .usage(TENANT_ID, USER_ID, new UsageQueryService.PageRequest(0, 20),
                        null, null, null)
                .block();

        UsageQueryService.QuotaCharge charge = usage.items().stream()
                .filter(entry -> "req-user-hybrid".equals(entry.requestId()))
                .findFirst()
                .orElseThrow()
                .quotaCharge();
        assertThat(charge).isNotNull();
        assertThat(charge.status()).isEqualTo("SETTLED");
        // 套餐实扣：账本与预留行都是 60，两列取哪一列都对——所以要单独钉。
        assertThat(charge.packageChargedUnits()).isEqualTo(60);
        // 模型实扣：60 − 15 = 45。取 reserved_tokens 会得到 105，且大于模型池预占本身。
        assertThat(charge.modelChargedUnits()).isEqualTo(45);
    }

    /**
     * 关联不到预占记录时整块为 {@code null}，而不是三个 0。
     *
     * <p>这条走服务层的纯映射：最终 schema 上 {@code usage_log.reservation_id} 是
     * NOT NULL，所以"没有预留"只可能来自读取侧（历史行、或预留行已不存在），
     * 构造它需要一个数据库查不到的行——那不是这条规则本身。
     */
    @Test
    void returnsNullQuotaChargeWhenNoReservationIsAssociated() {
        assertThat(UsageQueryService.quotaCharge(null, "TOKEN")).isNull();
    }

    /**
     * 未知维度不得默认成积分，但对象本身仍然存在（客户端要能显示"未知"）。
     */
    @Test
    void mapsUnknownUsageDimensionToNullUnitInsteadOfCredit() {
        assertThat(UsageQueryService.quotaUnit(null)).isNull();
        assertThat(UsageQueryService.quotaUnit("")).isNull();
        assertThat(UsageQueryService.quotaUnit("SOMETHING_NEW")).isNull();
        assertThat(UsageQueryService.quotaUnit("TOKEN")).isEqualTo("CREDIT");
        assertThat(UsageQueryService.quotaUnit("IMAGE")).isEqualTo("IMAGE");
        assertThat(UsageQueryService.quotaUnit("VIDEO_SECOND")).isEqualTo("SECOND");
        assertThat(UsageQueryService.quotaUnit("AUDIO_SECOND")).isEqualTo("SECOND");
        // 数据库里存的是大写，但读取侧不该因为大小写或空格而换一个单位。
        assertThat(UsageQueryService.quotaUnit(" image ")).isEqualTo("IMAGE");
    }

    private static void seedPackage(
            String id, UUID userId, String status, long reserved, long used) throws Exception {
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id,
                    identity_key, package_plan_version_id, package_code, package_name,
                    price_amount, currency, allowed_models, total_token_quota,
                    reserved_tokens, used_tokens, max_api_keys, requests_per_minute,
                    tokens_per_minute, max_concurrency, duration_days, daily_request_limit,
                    max_file_size_bytes, once_per_user, status, starts_at, expires_at, activated_at)
                VALUES ('%s', gen_random_uuid(), gen_random_uuid(), '%s', '%s',
                    'identity-%s', gen_random_uuid(), 'STARTER', 'Starter', 1900, 'CNY',
                    '[{"modelCode":"text-basic","capabilities":["TEXT"]}]', 1000,
                %d, %d, 3, 20, 100000, 3, 30, 200, 20971520, false, '%s',
                    CASE WHEN '%s'='ACTIVE' THEN now() ELSE NULL END,
                    CASE WHEN '%s'='ACTIVE' THEN now() + interval '30 days' ELSE NULL END,
                    CASE WHEN '%s'='ACTIVE' THEN now() ELSE NULL END)
                """.formatted(id, TENANT_ID, userId, userId, reserved, used,
                status, status, status, status));
    }

    private static void seedApiKey(String id, UUID userId, String packageId, String prefix)
            throws Exception {
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name, key_prefix,
                    key_hash, allowed_models, status)
                VALUES ('%s', '%s', '%s', '%s', 'Test', '%s', '%s',
                        '["text-basic","text-pro"]', 'ACTIVE')
                """.formatted(id, TENANT_ID, userId, packageId, prefix, id.replace("-", "")));
    }

    private static void seedReservation(
            String id, String requestId, UUID userId, String apiKeyId, String packageId,
            String modelCode, String status, long reserved, Integer input, Integer output)
            throws Exception {
        seedReservation(id, requestId, userId, apiKeyId, packageId, modelCode, status,
                reserved, input, output, null);
    }

    /**
     * 一条预留，{@code reserved_tokens} 与 {@code package_reserved_tokens} **不相等**。
     *
     * <p>存在的理由只有一个：上面那个重载把同一个数写进两列，于是**任何**把被减数
     * 取错列的缺陷都测不出来——{@code quotaCharge.modelChargedUnits} 就曾经因此
     * 把 {@code reserved_tokens} 当成模型池预占，在混合计费（钱包拆走一段）下
     * 把模型实扣算成「总量 − 差额」，比模型池自己的预占还大。
     *
     * <p>钱包拆分（{@code billing_mode='HYBRID'}）下两列必然不同：总量 120、
     * 套餐承担 60、其余走钱包。模型池当初被扣的是**套餐那一段**，所以账本里的
     * 差额也是相对它写的。
     */
    private static void seedHybridReservation(
            String id, String requestId, UUID userId, String apiKeyId, String packageId,
            String modelCode, long totalReserved, long packageReserved,
            long packageSettledTokens) throws Exception {
        execute("""
                INSERT INTO gateway.quota_reservation(
                    id, request_id, tenant_id, user_id, api_key_id, package_instance_id,
                    model_code, reserved_tokens, package_reserved_tokens,
                    package_settled_tokens, actual_input_tokens, actual_output_tokens,
                    actual_total_tokens, billing_mode, wallet_reserved_amount,
                    wallet_settled_amount, status, expires_at, settled_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', %d, %d,
                        %d, %d, 0, %d, 'HYBRID', %d, %d, 'SETTLED',
                        now() + interval '5 minutes', now())
                """.formatted(id, requestId, TENANT_ID, userId, apiKeyId, packageId,
                modelCode, totalReserved, packageReserved, packageSettledTokens,
                (int) packageSettledTokens, (int) packageSettledTokens,
                totalReserved - packageReserved, totalReserved - packageReserved));
    }

    /**
     * 一条预留，可显式指定套餐实结量。
     *
     * <p>默认让套餐实结量等于实际用量（{@code input + output}），这是常见情形；
     * 显式传值时可以得到"套餐池与模型池扣得不一样"的那类真实行
     * （钱包承担了一部分、或计费量与 token 数本就不同源）。
     */
    private static void seedReservation(
            String id, String requestId, UUID userId, String apiKeyId, String packageId,
            String modelCode, String status, long reserved, Integer input, Integer output,
            Long packageSettledTokens) throws Exception {
        String actuals = input == null ? "NULL, NULL, NULL" : input + ", " + output + ", "
                + (input + output);
        long settled = packageSettledTokens != null ? packageSettledTokens
                : input == null ? 0 : input + output;
        execute("""
                INSERT INTO gateway.quota_reservation(
                    id, request_id, tenant_id, user_id, api_key_id, package_instance_id,
                    model_code, reserved_tokens, package_reserved_tokens,
                    package_settled_tokens, actual_input_tokens, actual_output_tokens,
                    actual_total_tokens, status, expires_at, settled_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', %d, %d,
                        CASE WHEN '%s'='SETTLED' THEN %d ELSE 0 END,
                        %s, '%s', now() + interval '5 minutes',
                        CASE WHEN '%s'='SETTLED' THEN now() ELSE NULL END)
                """.formatted(id, requestId, TENANT_ID, userId, apiKeyId, packageId,
                modelCode, reserved, reserved, status, settled,
                actuals, status, status));
    }

    /**
     * 不带独立模型账本的账本行：{@code model_token_delta} 与 {@code model_balance_after}
     * 都留空，表示这次预留没有占用独立模型子额度。
     */
    private static void seedLedger(
            String id, String reservationId, String packageId, String modelCode,
            String type, long delta, long balance) throws Exception {
        seedLedger(id, reservationId, packageId, modelCode, type, delta, balance, null, null);
    }

    /**
     * 一条账本行，可带独立模型子额度。
     *
     * <p>{@code modelTokenDelta} 是**相对预占额的调整差额**，不是实际消耗：
     * 结算写入的是（预占 − 实扣），见 {@code QuotaReservationRepository.settle}。
     * 传 {@code null} 表示这次预留没有独立模型额度。
     */
    private static void seedLedger(
            String id, String reservationId, String packageId, String modelCode,
            String type, long delta, long balance,
            Long modelTokenDelta, Long modelBalanceAfter) throws Exception {
        String modelDelta = modelTokenDelta == null ? "NULL" : modelTokenDelta.toString();
        String modelBalance = modelBalanceAfter == null ? "NULL" : modelBalanceAfter.toString();
        execute("""
                INSERT INTO gateway.quota_ledger(
                    id, business_no, reservation_id, package_instance_id, model_code,
                    entry_type, total_token_delta, model_token_delta, balance_after,
                    model_balance_after, created_at)
                VALUES ('%s', 'ledger:%s', '%s', '%s', '%s', '%s', %d, %s, %d, %s,
                        TIMESTAMPTZ '2026-07-31 12:00:00Z')
                """.formatted(id, id, reservationId, packageId, modelCode, type, delta,
                modelDelta, balance, modelBalance));
    }

    private static void seedUsage(
            String id, String requestId, String reservationId, UUID userId, String apiKeyId,
            String packageId, String modelCode, String outcome, long total) throws Exception {
        seedUsage(id, requestId, reservationId, userId, apiKeyId, packageId, modelCode,
                outcome, total, "TOKEN");
    }

    /**
     * 带计量维度的一条用量记录。
     *
     * <p>{@code usageUnits} 给 1：维度不是 TOKEN 时 token 三列必须为 0
     * （{@code ck_usage_log_usage_units}），而视频/图片的那一列才是真实计量量，
     * 因此这里不能拿 {@code total} 当用量。
     */
    private static void seedUsage(
            String id, String requestId, String reservationId, UUID userId, String apiKeyId,
            String packageId, String modelCode, String outcome, long total,
            String usageDimension) throws Exception {
        boolean token = "TOKEN".equals(usageDimension);
        long usageUnits = token ? total : 1;
        execute("""
                INSERT INTO gateway.usage_log(
                    id, request_id, reservation_id, tenant_id, user_id, api_key_id,
                    package_instance_id, model_id, model_code, provider_id, channel_id,
                    input_tokens, output_tokens, total_tokens, usage_estimated, stream,
                    first_token_ms, duration_ms, http_status, outcome,
                    usage_dimension, usage_units)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s',
                    (SELECT id FROM gateway.model WHERE code='%s'), '%s',
                    (SELECT id FROM gateway.provider WHERE code='bailian'),
                    (SELECT id FROM gateway.channel WHERE code='bailian-primary'),
                    %d, %d, %d, false, false, 12, 50,
                    CASE WHEN '%s'='SUCCEEDED' THEN 200 ELSE 502 END, '%s', '%s', %d)
                """.formatted(id, requestId, reservationId, TENANT_ID, userId, apiKeyId,
                packageId, modelCode, modelCode, total / 2, total - total / 2, total,
                outcome, outcome, usageDimension, usageUnits));
    }

    /**
     * 查询必须能把"被输出上限截断"与"自然结束"分开（T5/T3，V116）。
     *
     * <p>这条用例复现的是 2026-10-15 那次排查卡住的地方：同一次请求在中台这边是
     * {@code outcome=SUCCEEDED} + {@code http_status=200} + {@code output_tokens=4096}，
     * 与一次正常结束的请求**逐字段相同**，导出的 CSV 因此只能得出"任务完成了"的结论；
     * 而桌面端对同一次请求明确记下了 {@code finish_reason=length} 与
     * {@code model_output_truncated}。
     *
     * <p>所以这里放两行用量：一行截断、一行自然结束，两者除新字段外完全一样，
     * 然后断言查询结果**能区分它们**。这正是"计量结果与生成结果要能分开"这句话的判据。
     */
    @Test
    void distinguishesATruncatedGenerationFromACompletedOne() throws Exception {
        // 用量行引用配额预留（usage_log.reservation_id 是指向 quota_reservation 的外键），
        // 而每个 reservation_id 唯一、且 @BeforeEach 已经占用了 0011/0012：
        // 夹具必须为这两行各准备一条没被占用的预留，否则连插入都过不去。
        seedReservation("86000000-0000-4000-8000-0000000000c1", "req-truncated", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "SETTLED", 4_096, 2_048, 2_048);
        seedReservation("86000000-0000-4000-8000-0000000000c2", "req-completed", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "SETTLED", 4_096, 2_048, 2_048);
        seedUsage("88000000-0000-4000-8000-0000000000b1", "req-truncated",
                "86000000-0000-4000-8000-0000000000c1", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "SUCCEEDED", 4_096);
        seedUsage("88000000-0000-4000-8000-0000000000b2", "req-completed",
                "86000000-0000-4000-8000-0000000000c2", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", "SUCCEEDED", 4_096);
        execute("""
                UPDATE gateway.usage_log
                SET generation_status = 'truncated', finish_reason = 'length',
                    effective_max_output_tokens = 4096, ceiling_source = 'package',
                    attempt_count = 1
                WHERE request_id = 'req-truncated'
                """);
        execute("""
                UPDATE gateway.usage_log
                SET generation_status = 'completed', finish_reason = 'stop',
                    effective_max_output_tokens = 8192, ceiling_source = 'client',
                    attempt_count = 2
                WHERE request_id = 'req-completed'
                """);

        UsageQueryService.PageResult<UsageQueryService.UsageEntry> usage = service
                .usage(TENANT_ID, USER_ID, new UsageQueryService.PageRequest(0, 20),
                        null, null, null)
                .block();
        Map<String, UsageQueryService.UsageEntry> byRequest = usage.items().stream()
                .collect(Collectors.toMap(UsageQueryService.UsageEntry::requestId, e -> e));

        UsageQueryService.UsageEntry truncated = byRequest.get("req-truncated");
        UsageQueryService.UsageEntry completed = byRequest.get("req-completed");

        // 前三列完全一样——这正是"分不开"的原因，把它们写出来是为了让这条用例
        // 在将来有人改坏 outcome/http_status 时也能说明问题。
        assertThat(truncated.outcome()).isEqualTo(completed.outcome());
        assertThat(truncated.httpStatus()).isEqualTo(completed.httpStatus());
        assertThat(truncated.outputTokens()).isEqualTo(completed.outputTokens());

        // 而生成终态与上限来源把两者分开了。
        assertThat(truncated.generationStatus()).isEqualTo("truncated");
        assertThat(truncated.finishReason()).isEqualTo("length");
        assertThat(truncated.effectiveMaxOutputTokens()).isEqualTo(4_096L);
        assertThat(truncated.ceilingSource()).isEqualTo("package");
        assertThat(truncated.attemptCount()).isEqualTo(1);

        assertThat(completed.generationStatus()).isEqualTo("completed");
        assertThat(completed.finishReason()).isEqualTo("stop");
        assertThat(completed.effectiveMaxOutputTokens()).isEqualTo(8_192L);
        assertThat(completed.ceilingSource()).isEqualTo("client");
        assertThat(completed.attemptCount()).isEqualTo(2);
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }
}
