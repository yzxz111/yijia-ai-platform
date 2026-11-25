package com.yuzhihuihe.apiplatform.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

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
class UsageAnalyticsServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("61000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_USER_ID = UUID.fromString("71000000-0000-4000-8000-000000000002");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-02T04:00:00Z"), ZoneOffset.UTC);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private UsageAnalyticsService service;

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
        execute("TRUNCATE gateway.usage_log, gateway.quota_reservation, gateway.api_key, "
                + "gateway.package_instance CASCADE");
        seedPackage("81000000-0000-4000-8000-000000000011", USER_ID, "ACTIVE");
        seedPackage("81000000-0000-4000-8000-000000000012", USER_ID, "QUEUED");
        seedPackage("81000000-0000-4000-8000-000000000021", OTHER_USER_ID, "ACTIVE");
        seedApiKey("83000000-0000-4000-8000-000000000011", USER_ID,
                "81000000-0000-4000-8000-000000000011");
        seedApiKey("83000000-0000-4000-8000-000000000021", OTHER_USER_ID,
                "81000000-0000-4000-8000-000000000021");
        seedUsage("01", "req-user-today", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-pro", "qwen3.7-plus",
                "SUCCEEDED", 100, 25, "2026-08-01T16:00:00Z");
        seedUsage("02", "req-user-yesterday", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-basic", null,
                "FAILED", 35, 12, "2026-08-01T15:59:59Z");
        seedUsage("03", "req-other-today", OTHER_USER_ID,
                "83000000-0000-4000-8000-000000000021",
                "81000000-0000-4000-8000-000000000021", "text-basic", "qwen3.7-flash",
                "FAILED", 999, 40, "2026-08-02T01:00:00Z");
        seedUsage("04", "req-user-old", USER_ID,
                "83000000-0000-4000-8000-000000000011",
                "81000000-0000-4000-8000-000000000011", "text-pro", "qwen3.7-plus",
                "SUCCEEDED", 500, 50, "2026-07-20T01:00:00Z");

        ConnectionFactoryOptions options = ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                .option(ConnectionFactoryOptions.PORT, POSTGRES.getFirstMappedPort())
                .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
                .build();
        service = new UsageAnalyticsService(
                new UsageAnalyticsRepository(
                        DatabaseClient.create(ConnectionFactories.get(options))),
                CLOCK);
    }

    @Test
    void aggregatesOnlyTheCurrentIdentityAcrossShanghaiDaysAndZeroFillsTheRange() {
        UsageAnalyticsService.UsageAnalytics result = service
                .userAnalytics(TENANT_ID, USER_ID, 7).block();

        assertThat(result.range().days()).isEqualTo(7);
        assertThat(result.range().timezone()).isEqualTo("Asia/Shanghai");
        assertThat(result.range().from()).isEqualTo(LocalDate.parse("2026-07-27"));
        assertThat(result.range().to()).isEqualTo(LocalDate.parse("2026-08-02"));
        assertThat(result.daily()).hasSize(7);
        assertThat(result.summary().todayRequests()).isEqualTo(1);
        assertThat(result.summary().todaySuccessfulRequests()).isEqualTo(1);
        assertThat(result.summary().todayFailedRequests()).isZero();
        assertThat(result.summary().todayTokens()).isEqualTo(100);
        assertThat(result.summary().periodRequests()).isEqualTo(2);
        assertThat(result.summary().periodTokens()).isEqualTo(135);
        assertThat(result.summary().platformAddedP95Ms()).isEqualTo(25);
        assertThat(result.daily().stream().filter(day -> day.requestCount() == 0).count())
                .isEqualTo(5);
        assertThat(result.daily().getLast().date()).isEqualTo(LocalDate.parse("2026-08-02"));
        assertThat(result.daily().getLast().requestCount()).isEqualTo(1);
        assertThat(result.models()).anySatisfy(model -> {
            assertThat(model.modelCode()).isEqualTo("text-pro");
            assertThat(model.providerCode()).isEqualTo("bailian");
            assertThat(model.upstreamModel()).isEqualTo("qwen3.7-plus");
            assertThat(model.totalTokens()).isEqualTo(100);
        }).anySatisfy(model -> {
            assertThat(model.modelCode()).isEqualTo("text-basic");
            assertThat(model.upstreamModel()).isNull();
            assertThat(model.totalTokens()).isEqualTo(35);
        });
    }

    @Test
    void aggregatesPlatformUsageAndCountsOnlyActivePackagesForAdministrators() {
        UsageAnalyticsService.GatewayAdminAnalytics result = service.adminAnalytics(30).block();

        assertThat(result.daily()).hasSize(30);
        assertThat(result.summary().todayRequests()).isEqualTo(2);
        assertThat(result.summary().todaySuccessfulRequests()).isEqualTo(1);
        assertThat(result.summary().todayFailedRequests()).isEqualTo(1);
        assertThat(result.summary().todayTokens()).isEqualTo(1_099);
        assertThat(result.summary().periodRequests()).isEqualTo(4);
        assertThat(result.summary().periodTokens()).isEqualTo(1_634);
        assertThat(result.packages()).singleElement().satisfies(item -> {
            assertThat(item.packageCode()).isEqualTo("STARTER");
            assertThat(item.packageName()).isEqualTo("Starter");
            assertThat(item.activeCount()).isEqualTo(2);
        });
    }

    @Test
    void rejectsUnboundedDayRanges() {
        assertThatThrownBy(() -> service.userAnalytics(TENANT_ID, USER_ID, 8))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode())
                                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> service.adminAnalytics(365))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode())
                                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    private static void seedPackage(String id, UUID userId, String status) throws Exception {
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id,
                    identity_key, package_plan_version_id, package_code, package_name,
                    price_amount, currency, allowed_models, total_token_quota,
                    reserved_tokens, used_tokens, max_api_keys, requests_per_minute,
                    tokens_per_minute, max_concurrency, duration_days,
                    max_file_size_bytes, once_per_user, status, starts_at, expires_at, activated_at)
                VALUES ('%s', gen_random_uuid(), gen_random_uuid(), '%s', '%s',
                    'identity-%s', gen_random_uuid(), 'STARTER', 'Starter', 1900, 'CNY',
                    '[{"modelCode":"text-basic","capabilities":["TEXT"]}]', 1000,
                    0, 0, 3, 20, 100000, 3, 30, 20971520, false, '%s',
                    CASE WHEN '%s'='ACTIVE' THEN now() ELSE NULL END,
                    CASE WHEN '%s'='ACTIVE' THEN now() + interval '30 days' ELSE NULL END,
                    CASE WHEN '%s'='ACTIVE' THEN now() ELSE NULL END)
                """.formatted(id, TENANT_ID, userId, userId, status, status, status, status));
    }

    private static void seedApiKey(String id, UUID userId, String packageId) throws Exception {
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name, key_prefix,
                    key_hash, allowed_models, status)
                VALUES ('%s', '%s', '%s', '%s', 'Analytics', 'sk-yzh-analytics', '%s',
                        '["text-basic","text-pro"]', 'ACTIVE')
                """.formatted(id, TENANT_ID, userId, packageId, id.replace("-", "")));
    }

    private static void seedUsage(
            String suffix, String requestId, UUID userId, String apiKeyId, String packageId,
            String modelCode, String upstreamModel, String outcome, long totalTokens,
            long platformLatencyMs, String createdAt) throws Exception {
        String reservationId = "86000000-0000-4000-8000-0000000000" + suffix;
        String usageId = "88000000-0000-4000-8000-0000000000" + suffix;
        execute("""
                INSERT INTO gateway.quota_reservation(
                    id, request_id, tenant_id, user_id, api_key_id, package_instance_id,
                    model_code, reserved_tokens, package_reserved_tokens,
                    package_settled_tokens, actual_input_tokens, actual_output_tokens,
                    actual_total_tokens, status, expires_at, settled_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', %d, %d, %d,
                        %d, %d, %d, 'SETTLED', TIMESTAMPTZ '%s', TIMESTAMPTZ '%s')
                """.formatted(reservationId, requestId, TENANT_ID, userId, apiKeyId, packageId,
                modelCode, totalTokens, totalTokens, totalTokens,
                totalTokens / 2, totalTokens - totalTokens / 2,
                totalTokens, createdAt, createdAt));
        String upstreamSql = upstreamModel == null ? "NULL" : "'" + upstreamModel + "'";
        execute("""
                INSERT INTO gateway.usage_log(
                    id, request_id, reservation_id, tenant_id, user_id, api_key_id,
                    package_instance_id, model_id, model_code, upstream_model,
                    provider_id, channel_id, input_tokens, output_tokens, total_tokens,
                    usage_estimated, stream, duration_ms, platform_added_latency_ms,
                    http_status, outcome, created_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s',
                    (SELECT id FROM gateway.model WHERE code='%s'), '%s', %s,
                    (SELECT id FROM gateway.provider WHERE code='bailian'),
                    (SELECT id FROM gateway.channel WHERE code='bailian-primary'),
                    %d, %d, %d, false, false, 50, %d,
                    CASE WHEN '%s'='SUCCEEDED' THEN 200 ELSE 502 END, '%s', TIMESTAMPTZ '%s')
                """.formatted(usageId, requestId, reservationId, TENANT_ID, userId, apiKeyId,
                packageId, modelCode, modelCode, upstreamSql, totalTokens / 2,
                totalTokens - totalTokens / 2, totalTokens, platformLatencyMs,
                outcome, outcome, createdAt));
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
