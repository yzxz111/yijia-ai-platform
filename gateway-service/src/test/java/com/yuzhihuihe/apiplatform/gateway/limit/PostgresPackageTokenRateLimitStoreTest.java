package com.yuzhihuihe.apiplatform.gateway.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
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
 * Redis 无法判定时使用的降级额度计数器。它累加套餐在当前这一分钟内已经结算的量，
 * 这与每日上限的降级存储所读取的行集合相同，也与客户用量页面针对该分钟展示的数字相同。
 */
@Testcontainers
class PostgresPackageTokenRateLimitStoreTest {

    private static final UUID TENANT_ID = UUID.fromString("96500000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("96500000-0000-4000-8000-000000000002");
    private static final UUID OTHER_USER_ID = UUID.fromString("96500000-0000-4000-8000-000000000003");
    private static final UUID PACKAGE_ID = UUID.fromString("96500000-0000-4000-8000-000000000004");
    private static final UUID OTHER_PACKAGE_ID = UUID.fromString("96500000-0000-4000-8000-000000000005");
    private static final UUID KEY_ID = UUID.fromString("96500000-0000-4000-8000-000000000006");
    private static final Instant MINUTE = Instant.parse("2026-08-02T02:03:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static PostgresPackageTokenRateLimitStore store;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway").schemas("gateway")
                .locations("classpath:db/migration").load().migrate();
        String url = "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                + "/" + POSTGRES.getDatabaseName();
        store = new PostgresPackageTokenRateLimitStore(
                DatabaseClient.create(ConnectionFactories.get(url)));
    }

    @BeforeEach
    void seed() throws Exception {
        execute("TRUNCATE gateway.usage_log, gateway.quota_reservation, gateway.api_key, "
                + "gateway.package_instance CASCADE");
        seedPackage(PACKAGE_ID, USER_ID);
        seedPackage(OTHER_PACKAGE_ID, OTHER_USER_ID);
        seedApiKey(KEY_ID, USER_ID, PACKAGE_ID);
        // 被测的这一分钟从 02:03:00Z 到 02:04:00Z。
        seedUsage("01", "req-credits-in-minute-1", USER_ID, PACKAGE_ID, "2026-08-02T02:03:00Z", 30);
        seedUsage("02", "req-credits-in-minute-2", USER_ID, PACKAGE_ID, "2026-08-02T02:03:59Z", 20);
        seedUsage("03", "req-credits-before", USER_ID, PACKAGE_ID, "2026-08-02T02:02:59Z", 500);
        seedUsage("04", "req-credits-other-package", USER_ID, OTHER_PACKAGE_ID,
                "2026-08-02T02:03:10Z", 500);
        seedUsage("05", "req-credits-other-user", OTHER_USER_ID, OTHER_PACKAGE_ID,
                "2026-08-02T02:03:20Z", 500);
    }

    @Test
    void countsOnlyTheCreditsThisPackageSettledInsideTheMinute() {
        assertThat(admit(51))
                .as("50 credits are charged in this minute; the previous minute, the other package"
                        + " and the other user must not consume this package's rate")
                .isTrue();
        assertThat(admit(50)).isFalse();
    }

    @Test
    void theNextMinuteStartsEmpty() {
        assertThat(admit(1, MINUTE.plusSeconds(60)))
                .as("this minute's settled credits are before the next one")
                .isTrue();
    }

    @Test
    void unavailableDatabaseIsReportedAsAFailure() {
        DatabaseClient broken = DatabaseClient.create(ConnectionFactories.get(
                "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                        + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                        + "/missing_db"));
        PostgresPackageTokenRateLimitStore failing = new PostgresPackageTokenRateLimitStore(broken);

        assertThatThrownBy(() -> failing
                        .admit(RequestLimitService.PackageTokenLimit.of(PACKAGE_ID, 10L), USER_ID,
                                MINUTE)
                        .block(Duration.ofSeconds(10)))
                .as("an undecidable fallback must error rather than admit")
                .isInstanceOf(RuntimeException.class);
    }

    private static boolean admit(long rate) {
        return admit(rate, MINUTE);
    }

    private static boolean admit(long rate, Instant minute) {
        Boolean admitted = store
                .admit(RequestLimitService.PackageTokenLimit.of(PACKAGE_ID, rate), USER_ID, minute)
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
                    '[{"modelCode":"text-basic"}]', 100000, 2, 20,
                    30, 3, 30, 'ACTIVE', now(), now() + interval '30 days', now(), NULL)
                """.formatted(packageId, TENANT_ID, userId, userId));
    }

    private static void seedApiKey(UUID keyId, UUID userId, UUID packageId) throws Exception {
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name, key_prefix,
                    key_hash, allowed_models, status)
                VALUES ('%s', '%s', '%s', '%s', 'Credits', 'sk-yzh-credits', '%s',
                        '["text-basic"]', 'ACTIVE')
                """.formatted(keyId, TENANT_ID, userId, packageId,
                keyId.toString().replace("-", "")));
    }

    private static void seedUsage(
            String suffix, String requestId, UUID userId, UUID packageId, String createdAt,
            long totalTokens)
            throws Exception {
        String reservationId = "97500000-0000-4000-8000-0000000000" + suffix;
        String usageId = "98500000-0000-4000-8000-0000000000" + suffix;
        execute("""
                INSERT INTO gateway.quota_reservation(
                    id, request_id, tenant_id, user_id, api_key_id, package_instance_id,
                    model_code, reserved_tokens, package_reserved_tokens,
                    package_settled_tokens, actual_input_tokens, actual_output_tokens,
                    actual_total_tokens, status, expires_at, settled_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s', 'text-basic', %d, %d, %d,
                        10, 20, %d, 'SETTLED', TIMESTAMPTZ '%s', TIMESTAMPTZ '%s')
                """.formatted(reservationId, requestId, TENANT_ID, userId, KEY_ID, packageId,
                totalTokens, totalTokens, totalTokens, totalTokens, createdAt, createdAt));
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
                    10, 20, %d, false, false, 50, 5, 200, 'SUCCEEDED', TIMESTAMPTZ '%s')
                """.formatted(usageId, requestId, reservationId, TENANT_ID, userId, KEY_ID,
                packageId, totalTokens, createdAt));
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
