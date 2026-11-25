package com.yuzhihuihe.apiplatform.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.yuzhihuihe.apiplatform.gateway.billing.WalletUsageClient;

import reactor.core.publisher.Mono;

/**
 * 方案 C 第 5 步的行为核心：**扣哪一列由本次服务的模型的维度决定**。
 *
 * <p>一个"视频秒 + 音频秒"的混合套餐里，一次 ASR 请求必须扣音频秒、绝不扣视频秒，
 * 反之一次视频请求也不得动音频秒；两个桶各自独立耗尽。这条规则错了不会有任何
 * 报错——它只会算错钱，而两个方向都贵：用音频秒去结算一次视频生成会把 69 分/秒
 * 的收入记成 14 分/秒，用视频秒去结算一次 ASR 则是把 0.022 分/秒的成本按 69 分/秒
 * 卖给客户。
 *
 * <p>刻意用**真实数据库**而不是替身：本类要证明的正是几条 SQL 更新语句打在了
 * 哪一列上（{@code reserved_tokens}/{@code used_tokens} 还是
 * {@code audio_seconds_reserved}/{@code audio_seconds_used}），而一个把方法参数
 * 换成替身的测试会把整个被测对象删掉。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.default-schema=gateway",
                "spring.flyway.schemas=gateway",
                "spring.data.redis.repositories.enabled=false",
                "security.api-key.pepper=test-api-key-pepper",
                "security.internal.hmac-secret=test-internal-secret",
                "quota.recovery.initial-delay=1h"
        })
class AudioSecondBucketSettlementTest {

    private static final UUID TENANT_ID = UUID.fromString("63000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("73000000-0000-4000-8000-000000000001");
    private static final UUID API_KEY_ID = UUID.fromString("83000000-0000-4000-8000-000000000001");
    private static final UUID PACKAGE_ID = UUID.fromString("93000000-0000-4000-8000-000000000001");

    private static final String VIDEO_ALIAS = "video-basic";
    private static final String AUDIO_ALIAS = "audio-asr-basic";

    /** 视频秒总额度：小一点，好让"视频桶单独耗尽"只需几步。 */
    private static final long VIDEO_SECONDS = 60;
    /** 音频秒总额度。刻意与视频秒不同，这样把两个桶搞混一定会在断言里露出来。 */
    private static final long AUDIO_SECONDS = 600;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

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

    @Autowired
    QuotaService quotaService;

    @Autowired
    DatabaseClient databaseClient;

    @MockitoBean
    WalletUsageClient walletClient;

    @BeforeEach
    void seedMixedPackage() throws Exception {
        stubWalletClient();
        execute("TRUNCATE gateway.usage_log, gateway.quota_ledger, gateway.quota_reservation, "
                + "gateway.api_key, gateway.package_instance_model_quota, gateway.package_instance, "
                + "gateway.consumed_event CASCADE");
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id,
                    identity_key, package_plan_version_id, package_code, package_name,
                    price_amount, currency, allowed_models, total_token_quota,
                    audio_seconds_quota, max_api_keys, requests_per_minute,
                    tokens_per_minute, max_concurrency, duration_days, max_file_size_bytes,
                    once_per_user, usage_dimension, status, starts_at, expires_at, activated_at)
                VALUES (
                    '%s', '%s', '%s', '%s', '%s',
                    'audio-bucket-identity', '%s', 'VIDEO_AUDIO', 'Video and audio',
                    19900, 'CNY',
                    '[{"modelCode":"video-basic","modelTokenQuota":%d,"capabilities":["TEXT","IMAGE","VIDEO"],"usageDimension":"VIDEO_SECOND"},
                      {"modelCode":"audio-asr-basic","modelTokenQuota":%d,"capabilities":["AUDIO_TRANSCRIBE"],"usageDimension":"AUDIO_SECOND"}]',
                    %d, %d, 3, 20, 60, 3, 30, 20971520,
                    false, 'VIDEO_SECOND', 'ACTIVE', now() - interval '1 minute',
                    now() + interval '30 days', now() - interval '1 minute')
                """.formatted(
                PACKAGE_ID, UUID.randomUUID(), UUID.randomUUID(), TENANT_ID, USER_ID,
                UUID.randomUUID(), VIDEO_SECONDS, AUDIO_SECONDS, VIDEO_SECONDS, AUDIO_SECONDS));
        // 每个模型的自额度行写着**它自己的**维度，这正是发放时写下的形状
        // （PackageActivationService.initializeModelQuotas）。
        execute("""
                INSERT INTO gateway.package_instance_model_quota(
                    id, package_instance_id, model_code, usage_dimension, token_quota, seconds_quota)
                VALUES
                    ('%s', '%s', '%s', 'VIDEO_SECOND', NULL, %d),
                    ('%s', '%s', '%s', 'AUDIO_SECOND', NULL, %d)
                """.formatted(
                UUID.randomUUID(), PACKAGE_ID, VIDEO_ALIAS, VIDEO_SECONDS,
                UUID.randomUUID(), PACKAGE_ID, AUDIO_ALIAS, AUDIO_SECONDS));
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name,
                    key_prefix, key_hash, allowed_models, status, expires_at)
                VALUES (
                    '%s', '%s', '%s', '%s',
                    'Audio bucket test', 'sk-yzh-audio', 'audio-bucket-hash',
                    '["video-basic","audio-asr-basic"]', 'ACTIVE', now() + interval '30 days')
                """.formatted(API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID));
    }

    /**
     * 一次音频请求扣音频秒，一次视频请求扣视频秒——两列各自归零。
     *
     * <p>断言的是**两列同时**读回来的数字，而不是只看被扣的那一列：只看被扣的那一列
     * 无法区分"扣对了"与"两列都扣了"，而后者会让客户为一次调用付两次钱。
     */
    @Test
    void anAudioRequestDebitsAudioSecondsAndAVideoRequestDebitsVideoSeconds() {
        QuotaService.Reservation transcription = reserve(AUDIO_ALIAS, 30, "AUDIO_SECOND");
        assertThat(transcription.usageDimension()).isEqualTo("AUDIO_SECOND");
        assertThat(audioReserved()).isEqualTo(30);
        assertThat(videoReserved()).isZero();

        settle(transcription, 30);
        assertThat(audioUsed()).isEqualTo(30);
        assertThat(videoUsed()).isZero();
        assertThat(audioReserved()).isZero();
        assertThat(reservationDimension(transcription)).isEqualTo("AUDIO_SECOND");

        QuotaService.Reservation generation = reserve(VIDEO_ALIAS, 10, "VIDEO_SECOND");
        assertThat(generation.usageDimension()).isEqualTo("VIDEO_SECOND");
        assertThat(videoReserved()).isEqualTo(10);
        // 视频的那 10 秒绝不得落进音频桶。
        assertThat(audioReserved()).isZero();
        assertThat(audioUsed()).isEqualTo(30);

        settle(generation, 10);
        assertThat(videoUsed()).isEqualTo(10);
        assertThat(audioUsed()).isEqualTo(30);
    }

    /**
     * 释放也必须放回**被扣的那个桶**：扣在音频秒上、放回视频秒不会报错，
     * 只会让视频秒凭空多出一段、音频秒永久少一段。
     */
    @Test
    void releasingAnAudioReservationReturnsTheSecondsToTheAudioBucket() {
        QuotaService.Reservation reservation = reserve(AUDIO_ALIAS, 45, "AUDIO_SECOND");
        assertThat(audioReserved()).isEqualTo(45);

        quotaService.release(reservation.id(), QuotaService.ReleaseReason.UPSTREAM_FAILURE)
                .block(Duration.ofSeconds(10));

        assertThat(audioReserved()).isZero();
        assertThat(audioUsed()).isZero();
        assertThat(videoReserved()).isZero();
        assertThat(videoUsed()).isZero();
        assertThat(text("SELECT status FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isEqualTo("RELEASED");
    }

    /**
     * 两个桶各自独立耗尽：音频秒用完**不得**阻塞视频生成，视频秒用完也不得阻塞
     * 转写。这是产品决定里最容易被"一个套餐一个余额"的直觉写错的一条，而写错的
     * 方向是客户为两样东西付了钱、只拿到一样。
     */
    @Test
    void eachBucketExhaustsIndependently() {
        // 先把音频桶用光：600 秒里留 1 秒做边界。
        settle(reserve(AUDIO_ALIAS, AUDIO_SECONDS, "AUDIO_SECOND"), AUDIO_SECONDS);
        assertThat(audioUsed()).isEqualTo(AUDIO_SECONDS);

        // 音频请求现在必须以配额问题失败……
        assertThat(errorReason(reserveFailing(AUDIO_ALIAS, 1, "AUDIO_SECOND")))
                .isEqualTo("quota_exhausted");
        // ……而视频请求照旧。这就是"音频秒用完不阻塞视频"。
        QuotaService.Reservation generation = reserve(VIDEO_ALIAS, 10, "VIDEO_SECOND");
        settle(generation, 10);
        assertThat(videoUsed()).isEqualTo(10);

        // 反过来：把视频桶也用光，音频仍然不可用（已耗尽）……这里只证明
        // "视频耗尽后实例没有被终结"，因为音频的独立可用性已在上一步证明。
        settle(reserve(VIDEO_ALIAS, VIDEO_SECONDS - 10, "VIDEO_SECOND"), VIDEO_SECONDS - 10);
        assertThat(videoUsed()).isEqualTo(VIDEO_SECONDS);
        assertThat(errorReason(reserveFailing(VIDEO_ALIAS, 1, "VIDEO_SECOND")))
                .isEqualTo("quota_exhausted");
        // 视频桶已经空了，而音频桶还有 0 秒可用（上面已用光），因此实例现在
        // 应当被判定为用尽——两个桶都空的唯一情形。
        assertThat(text("SELECT status FROM gateway.package_instance WHERE id = '"
                + PACKAGE_ID + "'")).isEqualTo("EXHAUSTED");
    }

    /**
     * 一个只卖视频秒的实例不得接受音频请求——fail-closed，而不是落进某个桶的算术。
     *
     * <p>反面同样重要：如果这里"通过"了，网关就会用一次 ASR 请求去花视频秒，
     * 即约 5000 倍的错价（69 分/秒 对 0.013 分/秒），而且不会有任何报错。
     */
    @Test
    void anAudioRequestIsRefusedByAnInstanceThatSellsNoAudio() throws Exception {
        execute("UPDATE gateway.package_instance SET audio_seconds_quota = NULL WHERE id = '"
                + PACKAGE_ID + "'");

        assertThat(errorReason(reserveFailing(AUDIO_ALIAS, 10, "AUDIO_SECOND")))
                .isEqualTo("usage_dimension_mismatch");
        assertThat(audioReserved()).isZero();
        assertThat(videoReserved()).isZero();
    }

    /**
     * 音频桶还有余额时，一个视频秒花完的实例**不得**被终结。
     *
     * <p>终结一个实例会把它剩下的额度一起作废，因此这条规则错了的方向是
     * "客户为语音付过的钱在他还没用过一次转写时就消失了"。
     */
    @Test
    void anExhaustedVideoBucketDoesNotEndAnInstanceThatStillSellsAudioSeconds() {
        settle(reserve(VIDEO_ALIAS, VIDEO_SECONDS, "VIDEO_SECOND"), VIDEO_SECONDS);
        assertThat(videoUsed()).isEqualTo(VIDEO_SECONDS);
        assertThat(audioUsed()).isZero();

        // 结算尾部的 activateIfTerminal 会尝试终结这个实例；音频桶没空，因此它必须
        // 保持 ACTIVE。
        assertThat(text("SELECT status FROM gateway.package_instance WHERE id = '"
                + PACKAGE_ID + "'")).isEqualTo("ACTIVE");

        // 而且音频确实还能用——不只是状态没改。
        settle(reserve(AUDIO_ALIAS, 20, "AUDIO_SECOND"), 20);
        assertThat(audioUsed()).isEqualTo(20);
    }

    private QuotaService.Reservation reserve(String model, long units, String dimension) {
        return quotaService.reserve(command(model, units, dimension)).block(Duration.ofSeconds(10));
    }

    private reactor.core.publisher.Signal<QuotaService.Reservation> reserveFailing(
            String model, long units, String dimension) {
        return quotaService.reserve(command(model, units, dimension))
                .materialize().block(Duration.ofSeconds(10));
    }

    private void settle(QuotaService.Reservation reservation, long units) {
        quotaService.settle(
                        reservation.id(),
                        new QuotaService.TokenUsage(units, 0, units),
                        QuotaService.UsageQuality.ACTUAL)
                .block(Duration.ofSeconds(10));
    }

    private QuotaService.ReserveCommand command(String model, long units, String dimension) {
        return new QuotaService.ReserveCommand(
                UUID.randomUUID(), "audio-bucket-" + UUID.randomUUID(), TENANT_ID, USER_ID,
                API_KEY_ID, model, units, Instant.now().plusSeconds(60),
                units, 0, dimension);
    }

    private static String errorReason(reactor.core.publisher.Signal<?> signal) {
        assertThat(signal.isOnError()).isTrue();
        Throwable error = signal.getThrowable();
        assertThat(error).isInstanceOf(ResponseStatusException.class);
        return ((ResponseStatusException) error).getReason();
    }

    private long videoReserved() {
        return number("SELECT reserved_tokens FROM gateway.package_instance WHERE id = '"
                + PACKAGE_ID + "'");
    }

    private long videoUsed() {
        return number("SELECT used_tokens FROM gateway.package_instance WHERE id = '"
                + PACKAGE_ID + "'");
    }

    private long audioReserved() {
        return number("SELECT audio_seconds_reserved FROM gateway.package_instance WHERE id = '"
                + PACKAGE_ID + "'");
    }

    private long audioUsed() {
        return number("SELECT audio_seconds_used FROM gateway.package_instance WHERE id = '"
                + PACKAGE_ID + "'");
    }

    private String reservationDimension(QuotaService.Reservation reservation) {
        return text("SELECT usage_dimension FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'");
    }

    private void stubWalletClient() {
        when(walletClient.reserve(any(WalletUsageClient.WalletRequest.class)))
                .thenAnswer(invocation -> {
                    WalletUsageClient.WalletRequest request = invocation.getArgument(0);
                    return Mono.just(new WalletUsageClient.WalletResponse(
                            request.reservationId(), request.requestId(), request.estimatedAmount(),
                            null, "RESERVED", request.modelCode(), request.priceVersion()));
                });
        when(walletClient.settle(any(UUID.class), any(WalletUsageClient.WalletSettlement.class)))
                .thenAnswer(invocation -> {
                    UUID reservationId = invocation.getArgument(0);
                    WalletUsageClient.WalletSettlement settlement = invocation.getArgument(1);
                    return Mono.just(new WalletUsageClient.WalletResponse(
                            reservationId, null, 0, settlement.settledAmount(),
                            "SETTLED", null, null));
                });
        when(walletClient.release(any(UUID.class)))
                .thenAnswer(invocation -> Mono.just(new WalletUsageClient.WalletResponse(
                        invocation.getArgument(0), null, 0, null, "RELEASED", null, null)));
    }

    private void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String text(String sql) {
        // R2DBC 的列下标从 0 开始；这些查询都只选一列。
        return databaseClient.sql(sql)
                .map((row, metadata) -> row.get(0, String.class))
                .one()
                .block(Duration.ofSeconds(10));
    }

    private long number(String sql) {
        Long value = databaseClient.sql(sql)
                .map((row, metadata) -> row.get(0, Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        return value == null ? -1 : value;
    }
}
