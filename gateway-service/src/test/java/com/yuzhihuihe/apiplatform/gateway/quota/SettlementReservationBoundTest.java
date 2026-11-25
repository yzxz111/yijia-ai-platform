package com.yuzhihuihe.apiplatform.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.billing.WalletUsageClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import reactor.core.publisher.Mono;

/**
 * 结算绝不能因为实际用量超出了预留而失败。
 *
 * <p>本类所针对的缺陷是：{@code QuotaService.settle} 把实际零售金额与预留所持有的金额作比较，
 * 当实际金额更大时抛出 {@code billing_reconciliation_required}（409）。于是结算事务回滚，
 * 预留永远停留在 {@code RESERVED}，既没有扣费也没有释放，而恢复任务无法修复它，
 * 因为它也是通过同一个方法结算的 —— 结果平台为没有任何客户余额覆盖的用量向供应商付了钱。
 *
 * <p>{@link #settleAboveTheReservationSettlesAndRecordsTheDeficit} 就是这个缺陷的复现。
 * 本类其余部分固定了取代它的那些不变式：结算以预留为上限，余量被释放，
 * 超额在可以收取时会被收取，并且每条终态路径都不留下任何被悬置的东西。
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
class SettlementReservationBoundTest {

    private static final UUID TENANT_ID = UUID.fromString("63000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("73000000-0000-4000-8000-000000000001");
    private static final UUID API_KEY_ID = UUID.fromString("83000000-0000-4000-8000-000000000001");
    private static final UUID PACKAGE_ID = UUID.fromString("93000000-0000-4000-8000-000000000001");
    private static final String MODEL = "text-pro";

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
    QuotaReservationRepository reservationRepository;

    @MockitoBean
    WalletUsageClient walletClient;

    @BeforeEach
    void resetData() throws Exception {
        stubCollectableWallet();
        execute("TRUNCATE gateway.usage_log, gateway.quota_ledger, gateway.quota_reservation, "
                + "gateway.api_key, gateway.package_instance_model_quota, gateway.package_instance, "
                + "gateway.consumed_event CASCADE");
        seedPackage();
    }

    // ------------------------------------------------------------------------------------------
    // 缺陷
    // ------------------------------------------------------------------------------------------

    @Test
    void settleAboveTheReservationSettlesAndRecordsTheDeficit() throws Exception {
        QuotaService.Reservation reservation = reserve("over-run", 1_000, 500, 500);
        long held = value("SELECT wallet_reserved_amount FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'");
        assertThat(held).isPositive();

        // 供应商计费的用量是请求获准所依据的十倍：五千输入 token 与五千输出 token，
        // 而预留只有一千。
        QuotaService.Settlement settlement = quotaService.settle(
                        reservation.id(), new QuotaService.TokenUsage(5_000, 5_000, 10_000),
                        QuotaService.UsageQuality.ACTUAL)
                .block(Duration.ofSeconds(15));

        assertThat(settlement).isNotNull();
        assertThat(settlement.status()).isEqualTo("SETTLED");
        assertThat(text("SELECT status FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isEqualTo("SETTLED");
        assertThat(value("SELECT count(*) FROM gateway.quota_reservation WHERE status = 'RESERVED'"))
                .isZero();
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance")).isZero();
        assertThat(value("SELECT wallet_settled_amount FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isEqualTo(held);
        // 缺口被记录下来而不是被自行吸收，因为该测试的钱包覆盖不了它
        // （见下面的 cannotCollectOverage）—— 而这里钱包能覆盖，所以该标记被清除。
        assertThat(value("SELECT wallet_deficit_amount FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isZero();
    }

    @Test
    void overageThatCannotBeCollectedIsRecordedOnTheReservationRatherThanAbsorbed() throws Exception {
        stubUncollectableWallet();
        QuotaService.Reservation reservation = reserve("uncollectable", 1_000, 500, 500);
        long held = value("SELECT wallet_reserved_amount FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'");

        QuotaService.Settlement settlement = quotaService.settle(
                        reservation.id(), new QuotaService.TokenUsage(5_000, 5_000, 10_000),
                        QuotaService.UsageQuality.ACTUAL)
                .block(Duration.ofSeconds(15));

        assertThat(settlement.status()).isEqualTo("SETTLED");
        assertThat(text("SELECT status FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isEqualTo("SETTLED");
        assertThat(value("SELECT count(*) FROM gateway.quota_reservation WHERE status = 'RESERVED'"))
                .isZero();
        // 预留的金额已被收取；其余部分是存在风险的钱，并且如实标注出来。
        assertThat(value("SELECT wallet_settled_amount FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isEqualTo(held);
        assertThat(value("SELECT wallet_deficit_amount FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isPositive();
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance")).isZero();
    }

    @Test
    void theReservationCoversTheWorstCaseAtTheBoundary() throws Exception {
        // 按 2 000 token 获准。结算时供应商报告：其中每一个 token 都属于它可能落入的最贵类别：
        // 全部输入都写入缓存（在本模型上缓存写入的单价高于未命中的单价），
        // 并且全部输出都花在推理上。
        QuotaService.Reservation reservation = reserve("at-the-boundary", 2_000, 1_000, 1_000);
        long held = value("SELECT wallet_reserved_amount FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'");

        QuotaService.Settlement settlement = quotaService.settle(
                        reservation.id(),
                        new QuotaService.TokenUsage(1_000, 1_000, 2_000, 0L, 1_000L, 1_000L),
                        QuotaService.UsageQuality.ACTUAL)
                .block(Duration.ofSeconds(15));

        assertThat(settlement.status()).isEqualTo("SETTLED");
        assertThat(settlement.uncoveredTokens()).isZero();
        assertThat(value("SELECT wallet_deficit_amount FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isZero();
        assertThat(value("SELECT wallet_settled_amount FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isLessThanOrEqualTo(held);
        assertThat(value("SELECT used_tokens FROM gateway.package_instance")).isEqualTo(2_000);
    }

    @Test
    void settlingBelowTheReservationReleasesTheRemainder() throws Exception {
        QuotaService.Reservation reservation = reserve("under-run", 4_000, 2_000, 2_000);
        long held = value("SELECT wallet_reserved_amount FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'");

        quotaService.settle(
                        reservation.id(), new QuotaService.TokenUsage(100, 100, 200),
                        QuotaService.UsageQuality.ACTUAL)
                .block(Duration.ofSeconds(15));

        long settled = value("SELECT wallet_settled_amount FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'");
        assertThat(settled).isLessThan(held);
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance")).isZero();
        assertThat(value("SELECT used_tokens FROM gateway.package_instance")).isEqualTo(200);
        assertThat(value("SELECT wallet_deficit_amount FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isZero();
    }

    @Test
    void settlingWalksNoReservationIntoTheRecoveryJob() throws Exception {
        QuotaService.Reservation reservation = reserve("recovery-after-over-run", 1_000, 500, 500);
        stubUncollectableWallet();
        quotaService.settle(
                        reservation.id(), new QuotaService.TokenUsage(5_000, 5_000, 10_000),
                        QuotaService.UsageQuality.ACTUAL)
                .block(Duration.ofSeconds(15));
        execute("UPDATE gateway.quota_reservation SET expires_at = now() - interval '1 minute'");

        // databaseClient 有意传 null：这个用例关心的是「结算已经就地完成、
        // 没有任何东西留给恢复任务」，因此这条分支根本不该被走到。
        int recovered = new ReservationRecoveryJob(
                        reservationRepository, quotaService, null, 100, "2h")
                .recoverOnce()
                .block(Duration.ofSeconds(15));

        // 恢复任务已没有可修复的东西：超出的部分就地完成了结算。
        assertThat(recovered).isZero();
        assertThat(value("SELECT count(*) FROM gateway.quota_reservation WHERE status = 'RESERVED'"))
                .isZero();
    }

    @Test
    void aUsageOverflowIsStillRefusedWithoutChangingTheReservation() throws Exception {
        QuotaService.Reservation reservation = reserve("overflow", 1_000, 500, 500);

        var signal = quotaService.settle(
                        reservation.id(),
                        new QuotaService.TokenUsage(Long.MAX_VALUE, 1, Long.MAX_VALUE),
                        QuotaService.UsageQuality.ACTUAL)
                .materialize()
                .block(Duration.ofSeconds(15));

        assertThat(signal.isOnError()).isTrue();
        assertThat(signal.getThrowable()).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_settlement_request");
        assertThat(text("SELECT status FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isEqualTo("RESERVED");
    }

    // ------------------------------------------------------------------------------------------
    // 终态路径
    // ------------------------------------------------------------------------------------------

    @Test
    void providerErrorReleaseReturnsEverythingAndIsIdempotent() throws Exception {
        QuotaService.Reservation reservation = reserve("provider-error", 2_000, 1_000, 1_000);

        quotaService.release(reservation.id(), QuotaService.ReleaseReason.UPSTREAM_FAILURE)
                .block(Duration.ofSeconds(15));
        quotaService.release(reservation.id(), QuotaService.ReleaseReason.UPSTREAM_FAILURE)
                .block(Duration.ofSeconds(15));

        assertThat(text("SELECT status FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isEqualTo("RELEASED");
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance")).isZero();
        assertThat(value("SELECT used_tokens FROM gateway.package_instance")).isZero();
        assertThat(value("SELECT count(*) FROM gateway.quota_ledger")).isEqualTo(2);
    }

    @Test
    void clientCancellationSettlesWhatWasProducedAndReleasesTheRest() throws Exception {
        QuotaService.Reservation reservation = reserve("cancelled", 2_000, 1_000, 1_000);

        quotaService.settle(
                        reservation.id(), new QuotaService.TokenUsage(300, 200, 500),
                        QuotaService.UsageQuality.ESTIMATED)
                .block(Duration.ofSeconds(15));

        assertThat(text("SELECT status FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isEqualTo("SETTLED");
        assertThat(value("SELECT used_tokens FROM gateway.package_instance")).isEqualTo(500);
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance")).isZero();
        assertThat(value("SELECT wallet_deficit_amount FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isZero();
    }

    @Test
    void retryingASettlementNeverDoubleChargesOrDoubleReleases() throws Exception {
        QuotaService.Reservation reservation = reserve("retry", 2_000, 1_000, 1_000);

        QuotaService.Settlement first = quotaService.settle(
                        reservation.id(), new QuotaService.TokenUsage(5_000, 5_000, 10_000),
                        QuotaService.UsageQuality.ACTUAL)
                .block(Duration.ofSeconds(15));
        QuotaService.Settlement second = quotaService.settle(
                        reservation.id(), new QuotaService.TokenUsage(5_000, 5_000, 10_000),
                        QuotaService.UsageQuality.ACTUAL)
                .block(Duration.ofSeconds(15));

        assertThat(second).isEqualTo(first);
        assertThat(value("SELECT count(*) FROM gateway.quota_ledger WHERE entry_type = 'SETTLE'"))
                .isEqualTo(1);
        assertThat(value("SELECT used_tokens FROM gateway.package_instance")).isEqualTo(10_000);
        assertThat(value("SELECT count(*) FROM gateway.quota_reservation WHERE status = 'RESERVED'"))
                .isZero();
    }

    // ------------------------------------------------------------------------------------------

    private QuotaService.Reservation reserve(
            String requestId, long tokens, long inputTokens, long outputTokens) {
        return quotaService.reserve(new QuotaService.ReserveCommand(
                        UUID.randomUUID(), requestId, TENANT_ID, USER_ID, API_KEY_ID, MODEL,
                        tokens, Instant.now().plusSeconds(60), inputTokens, outputTokens))
                .block(Duration.ofSeconds(15));
    }

    /** 一个持有该预留、并且还能覆盖超额的钱包。 */
    private void stubCollectableWallet() {
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

    /**
     * 一个持有该预留但已无力覆盖超额的钱包：为缺口发起的预留会被拒绝，
     * 拒绝方式与平台完全一致，即 {@code wallet_insufficient_balance}。
     */
    private void stubUncollectableWallet() {
        stubCollectableWallet();
        when(walletClient.reserve(any(WalletUsageClient.WalletRequest.class)))
                .thenAnswer(invocation -> {
                    WalletUsageClient.WalletRequest request = invocation.getArgument(0);
                    if (request.requestId() != null && request.requestId().endsWith(":overage")) {
                        return Mono.error(new WalletUsageClient.WalletUsageClientException(
                                "wallet_insufficient_balance", 422));
                    }
                    return Mono.just(new WalletUsageClient.WalletResponse(
                            request.reservationId(), request.requestId(), request.estimatedAmount(),
                            null, "RESERVED", request.modelCode(), request.priceVersion()));
                });
    }

    private static void seedPackage() throws Exception {
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id,
                    identity_key, package_plan_version_id, package_code, package_name,
                    price_amount, currency, allowed_models, total_token_quota,
                    max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency,
                    duration_days, max_file_size_bytes, once_per_user,
                    status, starts_at, expires_at, activated_at, created_at)
                VALUES (
                    '93000000-0000-4000-8000-000000000001', gen_random_uuid(), gen_random_uuid(),
                    '63000000-0000-4000-8000-000000000001', '73000000-0000-4000-8000-000000000001',
                    'settle-bound-identity', gen_random_uuid(), 'ADVANCED', 'Advanced',
                    5900, 'CNY', '[{"modelCode":"text-pro","modelTokenQuota":100000,"capabilities":["TEXT"]}]',
                    100000, 3, 20, 100000, 3, 30, 20971520, false,
                    'ACTIVE', now() - interval '1 minute', now() + interval '30 days',
                    now() - interval '1 minute', now())
                """);
        // 模型子配额被刻意设得比套餐配额小，因此这里的预留在构成上是 HYBRID 的：一部分来自套餐，
        // 一部分来自钱包。正是这种形态过去会留下被悬置的预留，
        // 因为结算时只有钱包那一半可能被拒绝。
        execute("""
                INSERT INTO gateway.package_instance_model_quota(
                    id, package_instance_id, model_code, token_quota)
                VALUES (gen_random_uuid(), '93000000-0000-4000-8000-000000000001', 'text-pro', 500)
                """);
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name,
                    key_prefix, key_hash, allowed_models, status, expires_at)
                VALUES (
                    '83000000-0000-4000-8000-000000000001',
                    '63000000-0000-4000-8000-000000000001',
                    '73000000-0000-4000-8000-000000000001',
                    '93000000-0000-4000-8000-000000000001',
                    'Settle bound test', 'sk-yzh-bound', 'settle-bound-hash', '["text-pro"]',
                    'ACTIVE', now() + interval '30 days')
                """);
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long value(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String text(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
