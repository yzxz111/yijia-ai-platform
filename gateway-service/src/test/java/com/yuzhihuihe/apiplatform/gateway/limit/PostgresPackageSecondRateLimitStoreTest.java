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
 * 当 Redis 无法判定时使用的降级秒数计数器。
 *
 * <p>它对当前分钟内、该套餐的 {@code VIDEO_SECOND} 用量行求和——这些行的数量存放在
 * {@code usage_units} 中——并且必须对 token 行视而不见：聊天行的 token 不是生成秒数，
 * 两者的求和绝不能混在一起。
 */
@Testcontainers
class PostgresPackageSecondRateLimitStoreTest {

    private static final UUID TENANT_ID = UUID.fromString("96500000-0000-4000-8000-000000000011");
    private static final UUID USER_ID = UUID.fromString("96500000-0000-4000-8000-000000000012");
    private static final UUID OTHER_USER_ID = UUID.fromString("96500000-0000-4000-8000-000000000013");
    private static final UUID PACKAGE_ID = UUID.fromString("96500000-0000-4000-8000-000000000014");
    private static final UUID OTHER_PACKAGE_ID = UUID.fromString("96500000-0000-4000-8000-000000000015");
    private static final UUID KEY_ID = UUID.fromString("96500000-0000-4000-8000-000000000016");
    private static final Instant MINUTE = Instant.parse("2026-08-02T02:03:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static PostgresPackageSecondRateLimitStore store;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway").schemas("gateway")
                .locations("classpath:db/migration").load().migrate();
        String url = "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                + "/" + POSTGRES.getDatabaseName();
        store = new PostgresPackageSecondRateLimitStore(
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
        seedVideoUsage("01", "req-seconds-in-minute-1", USER_ID, PACKAGE_ID,
                "2026-08-02T02:03:00Z", 30);
        seedVideoUsage("02", "req-seconds-in-minute-2", USER_ID, PACKAGE_ID,
                "2026-08-02T02:03:59Z", 20);
        seedVideoUsage("03", "req-seconds-before", USER_ID, PACKAGE_ID,
                "2026-08-02T02:02:59Z", 500);
        seedVideoUsage("04", "req-seconds-other-package", USER_ID, OTHER_PACKAGE_ID,
                "2026-08-02T02:03:10Z", 500);
        seedVideoUsage("05", "req-seconds-other-user", OTHER_USER_ID, OTHER_PACKAGE_ID,
                "2026-08-02T02:03:20Z", 500);
        seedTokenUsage("06", "req-tokens-in-minute", USER_ID, PACKAGE_ID,
                "2026-08-02T02:03:30Z", 5000);
    }

    @Test
    void countsOnlyTheSecondsThisPackageSettledInsideTheMinute() {
        assertThat(admit(51))
                .as("50 seconds were billed in this minute; the previous minute, the other package,"
                        + " the other user and this package's chat tokens must not consume the rate")
                .isTrue();
        assertThat(admit(50)).isFalse();
    }

    @Test
    void theNextMinuteStartsEmpty() {
        assertThat(admit(1, MINUTE.plusSeconds(60)))
                .as("this minute's settled seconds are before the next one")
                .isTrue();
    }

    @Test
    void usedReportsTheSecondsOfTheMinute() {
        assertThat(store.used(rate(50), USER_ID, MINUTE).block(Duration.ofSeconds(10)))
                .isEqualTo(50L);
        assertThat(store.used(rate(50), USER_ID, MINUTE.plusSeconds(60)).block(Duration.ofSeconds(10)))
                .isZero();
    }

    @Test
    void unavailableDatabaseIsReportedAsAFailure() {
        DatabaseClient broken = DatabaseClient.create(ConnectionFactories.get(
                "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                        + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                        + "/missing_db"));
        PostgresPackageSecondRateLimitStore failing = new PostgresPackageSecondRateLimitStore(broken);

        assertThatThrownBy(() -> failing.admit(rate(10), USER_ID, MINUTE).block(Duration.ofSeconds(10)))
                .as("an undecidable fallback must error rather than admit")
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * 方案 C：混合套餐（视频秒 + 音频秒）的每分钟秒数上限是**两者之和**。
     *
     * <p>售出的那个数字是 {@code package_instance.tokens_per_minute}——一个套餐一列、
     * 一个上限——而 Redis 那条主计数器从一开始就是**按套餐实例一个 key**、不分维度。
     * 降级路径原先按实例的 {@code usage_dimension} 过滤，因此一个混合实例上的语音用量
     * 完全不被计入：那个上限对语音形同不存在，而它是客户已经买下的东西。
     *
     * <p>这条测试与 {@link RedisPackageSecondRateLimitStoreTest} 的
     * per-package 计数是同一件事的两半：主路径与降级路径必须数同一个数字，
     * 否则"降级"会悄悄改变客户被允许的用量。
     */
    @Test
    void aMixedPackagesVideoAndAudioSecondsBothCountAgainstTheOneSoldCeiling() throws Exception {
        // 另一个用户：`uq_active_package_per_user_dimension` 只允许一个用户在一个维度上
        // 有一个生效实例，而这里刻意要让混合实例与上面那个单维度实例并存。
        UUID mixedUser = UUID.fromString("96500000-0000-4000-8000-000000000022");
        UUID mixedPackage = UUID.fromString("96500000-0000-4000-8000-000000000021");
        seedPackage(mixedPackage, mixedUser, "VIDEO_SECOND");
        seedSecondUsage("31", "req-mixed-video", mixedUser, mixedPackage,
                "2026-08-02T02:03:05Z", 25, "VIDEO_SECOND", "video-basic");
        seedSecondUsage("32", "req-mixed-audio", mixedUser, mixedPackage,
                "2026-08-02T02:03:25Z", 15, "AUDIO_SECOND", "audio-asr-basic");
        // token 行即便挂在这个套餐上也不得计入：它的 usage_units 是 token 数。
        seedTokenUsage("33", "req-mixed-tokens", mixedUser, mixedPackage,
                "2026-08-02T02:03:35Z", 5000);

        RequestLimitService.PackageSecondLimit limit =
                RequestLimitService.PackageSecondLimit.of(mixedPackage, 40L);

        assertThat(store.used(limit, mixedUser, MINUTE).block(Duration.ofSeconds(10)))
                .as("25 视频秒 + 15 音频秒 = 40；若只数主维度会得到 25，"
                        + "于是这个上限对语音完全不生效")
                .isEqualTo(40L);
        assertThat(store.admit(limit, mixedUser, MINUTE).block(Duration.ofSeconds(10)))
                .as("已经到了售出的 40 秒，必须拒绝")
                .isFalse();
        assertThat(store.admit(RequestLimitService.PackageSecondLimit.of(mixedPackage, 41L),
                        mixedUser, MINUTE).block(Duration.ofSeconds(10)))
                .isTrue();
    }

    private static boolean admit(long rate) {
        return admit(rate, MINUTE);
    }

    private static boolean admit(long rate, Instant minute) {
        Boolean admitted = store.admit(rate(rate), USER_ID, minute).block(Duration.ofSeconds(10));
        return Boolean.TRUE.equals(admitted);
    }

    private static RequestLimitService.PackageSecondLimit rate(long seconds) {
        return RequestLimitService.PackageSecondLimit.of(PACKAGE_ID, seconds);
    }

    private static void seedPackage(UUID packageId, UUID userId) throws Exception {
        seedPackage(packageId, userId, "VIDEO_SECOND");
    }

    private static void seedPackage(UUID packageId, UUID userId, String usageDimension)
            throws Exception {
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id, identity_key,
                    package_plan_version_id, package_code, package_name, price_amount,
                    allowed_models, total_token_quota, max_api_keys, requests_per_minute,
                    tokens_per_minute, max_concurrency, duration_days, status,
                    starts_at, expires_at, activated_at, daily_request_limit, usage_dimension)
                VALUES ('%s', gen_random_uuid(), gen_random_uuid(), '%s', '%s', 'identity-%s',
                    gen_random_uuid(), 'VIDEO_STARTER', 'Video Starter', 0,
                    '[{"modelCode":"video-basic"}]', 60, 2, 20,
                    60, 3, 30, 'ACTIVE', now(), now() + interval '30 days', now(), NULL,
                    '%s')
                """.formatted(packageId, TENANT_ID, userId, userId, usageDimension));
    }

    private static void seedApiKey(UUID keyId, UUID userId, UUID packageId) throws Exception {
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name, key_prefix,
                    key_hash, allowed_models, status)
                VALUES ('%s', '%s', '%s', '%s', 'Seconds', 'sk-yzh-seconds', '%s',
                        '["video-basic"]', 'ACTIVE')
                """.formatted(keyId, TENANT_ID, userId, packageId,
                keyId.toString().replace("-", "")));
    }

    private static void seedVideoUsage(
            String suffix, String requestId, UUID userId, UUID packageId, String createdAt,
            long billedSeconds)
            throws Exception {
        seedSecondUsage(suffix, requestId, userId, packageId, createdAt, billedSeconds,
                "VIDEO_SECOND", "video-basic");
    }

    /**
     * 一条按单位计费的用量行。{@code dimension} 是它的 {@code usage_dimension}——
     * 对混合套餐而言，同一个套餐下会同时存在 {@code VIDEO_SECOND} 与
     * {@code AUDIO_SECOND} 两类行，这正是"上限必须两者一起数"的由来。
     */
    private static void seedSecondUsage(
            String suffix, String requestId, UUID userId, UUID packageId, String createdAt,
            long billedUnits, String dimension, String modelCode)
            throws Exception {
        String reservationId = "97500000-0000-4000-8000-0000000000" + suffix;
        String usageId = "98500000-0000-4000-8000-0000000000" + suffix;
        execute("""
                INSERT INTO gateway.quota_reservation(
                    id, request_id, tenant_id, user_id, api_key_id, package_instance_id,
                    model_code, reserved_tokens, package_reserved_tokens,
                    package_settled_tokens, actual_input_tokens, actual_output_tokens,
                    actual_total_tokens, status, expires_at, settled_at, usage_dimension)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', %d, %d, %d,
                        0, 0, 0, 'SETTLED', TIMESTAMPTZ '%s', TIMESTAMPTZ '%s', '%s')
                """.formatted(reservationId, requestId, TENANT_ID, userId, KEY_ID, packageId,
                modelCode, billedUnits, billedUnits, billedUnits, createdAt, createdAt, dimension));
        execute("""
                INSERT INTO gateway.usage_log(
                    id, request_id, reservation_id, tenant_id, user_id, api_key_id,
                    package_instance_id, model_id, model_code, provider_id, channel_id,
                    input_tokens, output_tokens, total_tokens, usage_dimension, usage_units,
                    usage_estimated, stream, duration_ms, platform_added_latency_ms, http_status,
                    outcome, created_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s',
                    (SELECT id FROM gateway.model WHERE code='%s'), '%s',
                    (SELECT id FROM gateway.provider WHERE code='bailian'),
                    (SELECT id FROM gateway.channel WHERE code='bailian-primary'),
                    0, 0, 0, '%s', %d,
                    false, false, 50, 5, 200, 'SUCCEEDED', TIMESTAMPTZ '%s')
                """.formatted(usageId, requestId, reservationId, TENANT_ID, userId, KEY_ID,
                packageId, modelCode, modelCode, dimension, billedUnits, createdAt));
    }

    private static void seedTokenUsage(
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
                    input_tokens, output_tokens, total_tokens, usage_dimension, usage_units,
                    usage_estimated, stream, duration_ms, platform_added_latency_ms, http_status,
                    outcome, created_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s',
                    (SELECT id FROM gateway.model WHERE code='text-basic'), 'text-basic',
                    (SELECT id FROM gateway.provider WHERE code='bailian'),
                    (SELECT id FROM gateway.channel WHERE code='bailian-primary'),
                    10, 20, %d, 'TOKEN', %d,
                    false, false, 50, 5, 200, 'SUCCEEDED', TIMESTAMPTZ '%s')
                """.formatted(usageId, requestId, reservationId, TENANT_ID, userId, KEY_ID,
                packageId, totalTokens, totalTokens, createdAt));
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
