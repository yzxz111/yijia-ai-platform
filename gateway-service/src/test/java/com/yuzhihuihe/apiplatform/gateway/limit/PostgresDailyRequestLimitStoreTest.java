package com.yuzhihuihe.apiplatform.gateway.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Redis 无法判定时使用的降级存储。它统计套餐在对外声明的上海自然日内
 * 已经结算的请求数，这与控制台向客户展示的「今日请求数」是同一个数字。
 */
@Testcontainers
class PostgresDailyRequestLimitStoreTest {

    private static final UUID TENANT_ID = UUID.fromString("96000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("96000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_USER_ID = UUID.fromString("96000000-0000-4000-8000-000000000003");
    private static final UUID PACKAGE_ID = UUID.fromString("96000000-0000-4000-8000-000000000004");
    private static final UUID OTHER_PACKAGE_ID = UUID.fromString("96000000-0000-4000-8000-000000000005");
    private static final UUID KEY_ID = UUID.fromString("96000000-0000-4000-8000-000000000006");
    private static final LocalDate DAY = LocalDate.parse("2026-08-02");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static PostgresDailyRequestLimitStore store;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway").schemas("gateway")
                .locations("classpath:db/migration").load().migrate();
        String url = "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                + "/" + POSTGRES.getDatabaseName();
        store = new PostgresDailyRequestLimitStore(DatabaseClient.create(ConnectionFactories.get(url)));
    }

    @BeforeEach
    void seed() throws Exception {
        execute("TRUNCATE gateway.usage_log, gateway.quota_reservation, gateway.api_key, "
                + "gateway.package_instance CASCADE");
        seedPackage(PACKAGE_ID, USER_ID);
        seedPackage(OTHER_PACKAGE_ID, OTHER_USER_ID);
        seedApiKey(KEY_ID, USER_ID, PACKAGE_ID);
        // Asia/Shanghai 时区的 2026-08-02 从 2026-08-01T16:00:00Z 开始。
        seedUsage("01", "req-daily-today-1", USER_ID, PACKAGE_ID, "2026-08-01T16:00:00Z");
        seedUsage("02", "req-daily-today-2", USER_ID, PACKAGE_ID, "2026-08-02T01:00:00Z");
        seedUsage("03", "req-daily-yesterday", USER_ID, PACKAGE_ID, "2026-08-01T15:59:59Z");
        seedUsage("04", "req-daily-other-package", USER_ID, OTHER_PACKAGE_ID, "2026-08-02T02:00:00Z");
        seedUsage("05", "req-daily-other-user", OTHER_USER_ID, OTHER_PACKAGE_ID, "2026-08-02T03:00:00Z");
    }

    @Test
    void countsOnlyThisPackagesSettledRequestsInsideTheShanghaiDay() throws Exception {
        assertThat(admit(3, DAY))
                .as("two settled requests today; yesterday, the other package and the other user"
                        + " must not consume this package's cap")
                .isTrue();
        assertThat(admit(2, DAY)).isFalse();
    }

    @Test
    void aNewShanghaiDayStartsEmpty() {
        assertThat(admit(1, DAY.plusDays(1)))
                .as("today's settled requests are before the next Shanghai day")
                .isTrue();
    }

    @Test
    void unavailableDatabaseIsReportedAsAFailure() {
        DatabaseClient broken = DatabaseClient.create(ConnectionFactories.get(
                "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                        + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/missing_db"));
        PostgresDailyRequestLimitStore failing = new PostgresDailyRequestLimitStore(broken);

        assertThatThrownBy(() -> failing.admit(
                        RequestLimitService.PackageDailyLimit.of(PACKAGE_ID, 10), USER_ID, DAY)
                .block(Duration.ofSeconds(10)))
                .as("an undecidable fallback must error rather than admit")
                .isInstanceOf(RuntimeException.class);
    }

    private static boolean admit(int limit, LocalDate day) {
        Boolean admitted = store.admit(
                        RequestLimitService.PackageDailyLimit.of(PACKAGE_ID, limit), USER_ID, day)
                .block(Duration.ofSeconds(10));
        return Boolean.TRUE.equals(admitted);
    }

    private static void seedPackage(UUID packageId, UUID userId) throws Exception {
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id, identity_key,
                    package_plan_version_id, package_code, package_name, price_amount,
                    allowed_models, total_token_quota, max_api_keys, requests_per_minute,
                    tokens_per_minute, max_concurrency, duration_days, status,
                    starts_at, expires_at, activated_at, daily_request_limit)
                VALUES ('%s', gen_random_uuid(), gen_random_uuid(), '%s', '%s', 'identity-%s',
                    gen_random_uuid(), 'FREE', 'Free', 0,
                    '[{"modelCode":"text-basic"}]', 10000, 2, 20,
                    100000, 3, 30, 'ACTIVE', now(), now() + interval '30 days', now(), 20)
                """.formatted(packageId, TENANT_ID, userId, userId));
    }

    private static void seedApiKey(UUID keyId, UUID userId, UUID packageId) throws Exception {
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name, key_prefix,
                    key_hash, allowed_models, status)
                VALUES ('%s', '%s', '%s', '%s', 'Daily', 'sk-yzh-daily', '%s',
                        '["text-basic"]', 'ACTIVE')
                """.formatted(keyId, TENANT_ID, userId, packageId, keyId.toString().replace("-", "")));
    }

    private static void seedUsage(
            String suffix, String requestId, UUID userId, UUID packageId, String createdAt)
            throws Exception {
        String reservationId = "97000000-0000-4000-8000-0000000000" + suffix;
        String usageId = "98000000-0000-4000-8000-0000000000" + suffix;
        execute("""
                INSERT INTO gateway.quota_reservation(
                    id, request_id, tenant_id, user_id, api_key_id, package_instance_id,
                    model_code, reserved_tokens, package_reserved_tokens,
                    package_settled_tokens, actual_input_tokens, actual_output_tokens,
                    actual_total_tokens, status, expires_at, settled_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s', 'text-basic', 30, 30, 30,
                        10, 20, 30, 'SETTLED', TIMESTAMPTZ '%s', TIMESTAMPTZ '%s')
                """.formatted(reservationId, requestId, TENANT_ID, userId, KEY_ID, packageId,
                createdAt, createdAt));
        execute("""
                INSERT INTO gateway.usage_log(
                    id, request_id, reservation_id, tenant_id, user_id, api_key_id,
                    package_instance_id, model_id, model_code, provider_id, channel_id,
                    input_tokens, output_tokens, total_tokens, usage_estimated, stream,
                    duration_ms, platform_added_latency_ms, http_status, outcome, created_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s',
                    (SELECT id FROM gateway.model WHERE code='text-basic'), 'text-basic',
                    (SELECT id FROM gateway.provider WHERE code='bailian'),
                    (SELECT id FROM gateway.channel WHERE code='bailian-primary'),
                    10, 20, 30, false, false, 50, 5, 200, 'SUCCEEDED', TIMESTAMPTZ '%s')
                """.formatted(usageId, requestId, reservationId, TENANT_ID, userId, KEY_ID,
                packageId, createdAt));
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
