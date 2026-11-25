package com.yuzhihuihe.apiplatform.platform.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false",
        "outbox.publisher.initial-delay-ms=3600000"
})
class RefundApplicationServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("42000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("42000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("42000000-0000-4000-8000-000000000003");
    private static final UUID ADMIN_ID =
            UUID.fromString("42000000-0000-4000-8000-000000000004");
    private static final UUID ORDER_ID =
            UUID.fromString("42000000-0000-4000-8000-000000000005");
    private static final UUID PAYMENT_ID =
            UUID.fromString("42000000-0000-4000-8000-000000000006");
    private static final UUID GRANT_ID =
            UUID.fromString("42000000-0000-4000-8000-000000000007");
    private static final UUID PACKAGE_ID =
            UUID.fromString("42000000-0000-4000-8000-000000000008");
    private static final UUID VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static final String ORDER_NO = "ord_refund_test";
    private static final UUID ACCOUNT_ID =
            UUID.fromString("42000000-0000-4000-8000-000000000009");
    private static final UUID DEBIT_LEDGER_ID =
            UUID.fromString("42000000-0000-4000-8000-00000000000a");
    private static final UUID RECHARGE_ID =
            UUID.fromString("42000000-0000-4000-8000-00000000000b");
    private static final String RECHARGE_NO = "rch_refund_not_supported";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    RefundApplicationService service;

    @Autowired
    MeterRegistry meterRegistry;

    @MockitoBean
    GatewayRefundClient gatewayRefundClient;

    private final Map<String, GatewayRefundClient.RefundSnapshot> stableSnapshots =
            new ConcurrentHashMap<>();

    @BeforeEach
    void resetDatabase() {
        reset(gatewayRefundClient);
        stableSnapshots.clear();
        jdbcTemplate.execute("TRUNCATE platform.admin_audit_log, platform.outbox_event, "
                + "platform.refund, platform.payment_transaction, platform.customer_order, "
                + "platform.grant_request, platform.refund_policy_version, "
                + "platform.refresh_token, platform.user_role, platform.invitation, "
                + "platform.app_user RESTART IDENTITY CASCADE");
        insertUser(USER_ID, "refund-user@example.com");
        insertUser(OTHER_USER_ID, "refund-other@example.com");
        insertUser(ADMIN_ID, "refund-admin@example.com");
        jdbcTemplate.update("""
                INSERT INTO platform.refund_policy_version(
                    version, fee_bps, minimum_fee, refund_window_days,
                    maximum_usage_bps, effective_at, published, created_by)
                VALUES (1, 500, 100, 7, 1000, now() - interval '1 day', true, ?)
                """, ADMIN_ID);
        insertPaidOrder();
    }

    @Test
    void onlyPaidOrderOwnerCanApplyAndSnapshotCopiesAllServerFacts() {
        when(gatewayRefundClient.prepare(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> queuedReady(invocation.getArgument(0)));

        assertThatThrownBy(() -> service.apply(
                TENANT_ID, OTHER_USER_ID, ORDER_NO,
                new RefundApplicationService.ApplyRefundCommand("not mine")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("paid_order_not_found");

        double requestsBefore = counter("platform.refund.request", "PENDING_APPROVAL");
        RefundApplicationService.RefundView refund = service.apply(
                TENANT_ID, USER_ID, ORDER_NO,
                new RefundApplicationService.ApplyRefundCommand("no longer needed"));

        // 从未激活过的套餐全额退款：整个已付金额，不收手续费。策略的
        // 手续费与用量上限条款仍会被复制进快照，因为
        // 快照是该申请所适用策略的不可变记录。
        assertThat(refund.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(refund.refundType()).isEqualTo("FULL");
        assertThat(refund.amount()).isEqualTo(10_001);
        RefundApplicationService.CalculationSnapshot snapshot = refund.calculation();
        assertThat(snapshot.paidAmount()).isEqualTo(10_001);
        assertThat(snapshot.totalTokens()).isEqualTo(1_000_000);
        assertThat(snapshot.usedTokens()).isZero();
        assertThat(snapshot.remainingTokens()).isEqualTo(1_000_000);
        assertThat(snapshot.feeBps()).isEqualTo(500);
        assertThat(snapshot.minimumFee()).isEqualTo(100);
        assertThat(snapshot.maximumUsageBps()).isEqualTo(1000);
        assertThat(snapshot.baseRefund()).isEqualTo(10_001);
        assertThat(snapshot.fee()).isZero();
        assertThat(snapshot.refundAmount()).isEqualTo(10_001);
        assertThat(snapshot.rounding()).isEqualTo("BASE_FLOOR_FEE_HALF_UP");
        assertThat(snapshot.policyVersion()).isEqualTo(1);
        assertThat(snapshot.refundType()).isEqualTo("FULL");
        assertThat(snapshot.paidAt()).isNotNull();
        assertThat(snapshot.requestedAt()).isNotNull();
        assertThat(snapshot.requestedAt()).isBeforeOrEqualTo(snapshot.calculatedAt());
        assertThat(snapshot.calculatedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT funding_source, payment_transaction_id, provider,
                       balance_refund_ledger_id
                FROM platform.refund WHERE refund_no = ?
                """, refund.refundNo()))
                .containsEntry("funding_source", "EXTERNAL")
                .containsEntry("payment_transaction_id", PAYMENT_ID)
                .containsEntry("provider", "MOCK")
                .containsEntry("balance_refund_ledger_id", null);
        verify(gatewayRefundClient).prepare("refund-hold:" + refund.refundNo(), GRANT_ID);
        assertThat(counter("platform.refund.request", "PENDING_APPROVAL"))
                .isEqualTo(requestsBefore + 1);
    }

    @Test
    void walletFundedPaidOrderCopiesBalanceSourceWithoutPaymentOrProvider() {
        convertOrderToWalletFunding();
        when(gatewayRefundClient.prepare(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> queuedReady(invocation.getArgument(0)));

        RefundApplicationService.RefundView refund = service.apply(
                TENANT_ID, USER_ID, ORDER_NO,
                new RefundApplicationService.ApplyRefundCommand("return to wallet"));

        assertThat(refund.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(refund.refundType()).isEqualTo("FULL");
        assertThat(refund.amount()).isEqualTo(10_001);
        assertThat(refund.calculation().paidAmount()).isEqualTo(10_001);
        assertThat(refund.calculation().usedTokens()).isZero();
        assertThat(refund.calculation().baseRefund()).isEqualTo(10_001);
        assertThat(refund.calculation().fee()).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT funding_source, payment_transaction_id, provider,
                       balance_refund_ledger_id
                FROM platform.refund WHERE refund_no = ?
                """, refund.refundNo()))
                .containsEntry("funding_source", "BALANCE")
                .containsEntry("payment_transaction_id", null)
                .containsEntry("provider", null)
                .containsEntry("balance_refund_ledger_id", null);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action = 'REFUND_APPLIED' AND resource_id = ?
                """, Long.class, refund.refundNo())).isEqualTo(1);
        verify(gatewayRefundClient).prepare("refund-hold:" + refund.refundNo(), GRANT_ID);
    }

    @Test
    void rechargeNumberAndIdCannotEnterPackageRefundApplication() {
        insertPaidRechargeOrder();

        for (String unsupported : List.of(RECHARGE_NO, RECHARGE_ID.toString())) {
            assertThatThrownBy(() -> service.apply(
                    TENANT_ID, USER_ID, unsupported,
                    new RefundApplicationService.ApplyRefundCommand("not a package")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("paid_order_not_found");
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.refund", Long.class)).isZero();
        assertThat(singleString(
                "SELECT status FROM platform.recharge_order WHERE id = ?", RECHARGE_ID))
                .isEqualTo("PAID");
    }

    @Test
    void waitingStaysPreparingAndIneligibleRequestIsRejectedAfterRelease() {
        when(gatewayRefundClient.prepare(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> waiting(invocation.getArgument(0)));
        RefundApplicationService.RefundView waiting = service.apply(
                TENANT_ID, USER_ID, ORDER_NO,
                new RefundApplicationService.ApplyRefundCommand("wait"));
        assertThat(waiting.status()).isEqualTo("PREPARING");
        assertThat(waiting.calculation()).isNull();

        jdbcTemplate.update("UPDATE platform.refund SET status = 'CANCELED' WHERE refund_no = ?",
                waiting.refundNo());
        when(gatewayRefundClient.prepare(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> stableReady(invocation.getArgument(0), 100_001));
        when(gatewayRefundClient.release(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> new GatewayRefundClient.ReleaseResult(
                        invocation.getArgument(0), "RELEASED"));

        RefundApplicationService.RefundView rejected = service.apply(
                TENANT_ID, USER_ID, ORDER_NO,
                new RefundApplicationService.ApplyRefundCommand("too much usage"));

        assertThat(rejected.status()).isEqualTo("REJECTED");
        // 用量是激活状态的证据，而激活正是现在适用的拒绝理由；旧的
        // USAGE_LIMIT_EXCEEDED 理由属于部分退款策略，而一旦有任何
        // 消耗，退款就不再可用。
        assertThat(rejected.failureReason()).isEqualTo("PACKAGE_ACTIVATED");
        verify(gatewayRefundClient).release("refund-hold:" + rejected.refundNo(), GRANT_ID);
    }

    /**
     * 收紧后的规则存在就是为了阻止的这种滥用，端到端地：客户激活了
     * 套餐，在 token 计数器上没有任何结算 —— 视频秒数与图片不会推动
     * 它 —— 却申请全额退款。激活记录拒绝该申请，预留被
     * 释放，退款被持久地置为 REJECTED，而不是留在不确定状态。
     */
    @Test
    void activatedButUnusedPackageIsRejectedEvenWithAZeroTokenCounter() {
        activateTheGrant();
        when(gatewayRefundClient.prepare(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> stableReady(invocation.getArgument(0), 0));
        when(gatewayRefundClient.release(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> new GatewayRefundClient.ReleaseResult(
                        invocation.getArgument(0), "RELEASED"));

        RefundApplicationService.RefundView rejected = service.apply(
                TENANT_ID, USER_ID, ORDER_NO,
                new RefundApplicationService.ApplyRefundCommand("changed my mind"));

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.failureReason()).isEqualTo("PACKAGE_ACTIVATED");
        assertThat(rejected.calculation()).isNull();
        verify(gatewayRefundClient).release("refund-hold:" + rejected.refundNo(), GRANT_ID);
    }

    /** 对一个已激活随后被部分使用的套餐，采用同样的拒绝。 */
    @Test
    void partiallyUsedPackageIsRejected() {
        activateTheGrant();
        when(gatewayRefundClient.prepare(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> stableReady(invocation.getArgument(0), 100_000));
        when(gatewayRefundClient.release(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> new GatewayRefundClient.ReleaseResult(
                        invocation.getArgument(0), "RELEASED"));

        RefundApplicationService.RefundView rejected = service.apply(
                TENANT_ID, USER_ID, ORDER_NO,
                new RefundApplicationService.ApplyRefundCommand("used a little"));

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.failureReason()).isEqualTo("PACKAGE_ACTIVATED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.refund
                WHERE order_id = ? AND refund_type IS NOT NULL
                """, Long.class, ORDER_ID)).isZero();
    }

    /**
     * 网关报告实例为 QUEUED 本身并不足够：两份记录都必须
     * 表明该套餐从未激活。这里平台自己的权益发放行显示 ACTIVE，因此
     * 该申请被拒绝 —— 「无法证明它从未激活过」
     * 不得换来一次退款。
     */
    @Test
    void anActivatedGrantRefusesEvenWhenTheSnapshotSaysTheInstanceIsQueued() {
        activateTheGrant();
        when(gatewayRefundClient.prepare(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> {
                    String businessNo = invocation.getArgument(0);
                    return stableSnapshots.computeIfAbsent(businessNo, ignored ->
                            new GatewayRefundClient.RefundSnapshot(
                                    businessNo, PACKAGE_ID, "READY", "QUEUED",
                                    1_000_000L, 0L, 1_000_000L, null, Instant.now()));
                });
        when(gatewayRefundClient.release(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> new GatewayRefundClient.ReleaseResult(
                        invocation.getArgument(0), "RELEASED"));

        RefundApplicationService.RefundView rejected = service.apply(
                TENANT_ID, USER_ID, ORDER_NO,
                new RefundApplicationService.ApplyRefundCommand("queued?"));

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.failureReason()).isEqualTo("PACKAGE_ACTIVATED");
    }

    @Test
    void queuedPackageWithoutExpiryProducesFullRefund() {
        when(gatewayRefundClient.prepare(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> {
                    String businessNo = invocation.getArgument(0);
                    return stableSnapshots.computeIfAbsent(businessNo, ignored ->
                            new GatewayRefundClient.RefundSnapshot(
                                    businessNo, PACKAGE_ID, "READY", "QUEUED",
                                    1_000_000L, 0L, 1_000_000L, null, Instant.now()));
                });

        RefundApplicationService.RefundView refund = service.apply(
                TENANT_ID, USER_ID, ORDER_NO,
                new RefundApplicationService.ApplyRefundCommand("queued package"));

        assertThat(refund.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(refund.refundType()).isEqualTo("FULL");
        assertThat(refund.amount()).isEqualTo(10_001L);
        assertThat(refund.calculation().originalPackageStatus()).isEqualTo("QUEUED");
        assertThat(refund.calculation().packageExpiresAt()).isNull();
    }

    @Test
    void rejectsExpiredWindowAndExpiredPackageAfterReleasingTheirHolds() {
        when(gatewayRefundClient.release(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> new GatewayRefundClient.ReleaseResult(
                        invocation.getArgument(0), "RELEASED"));
        jdbcTemplate.update(
                "UPDATE platform.customer_order SET paid_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(8L * 86_400)), ORDER_ID);
        when(gatewayRefundClient.prepare(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> queuedReady(invocation.getArgument(0)));

        RefundApplicationService.RefundView outsideWindow = service.apply(
                TENANT_ID, USER_ID, ORDER_NO,
                new RefundApplicationService.ApplyRefundCommand("outside window"));

        assertThat(outsideWindow.status()).isEqualTo("REJECTED");
        assertThat(outsideWindow.failureReason()).isEqualTo("REFUND_WINDOW_EXPIRED");

        jdbcTemplate.update(
                "UPDATE platform.customer_order SET paid_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(86_400)), ORDER_ID);
        when(gatewayRefundClient.prepare(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> expiredReady(invocation.getArgument(0)));

        RefundApplicationService.RefundView expiredPackage = service.apply(
                TENANT_ID, USER_ID, ORDER_NO,
                new RefundApplicationService.ApplyRefundCommand("expired package"));

        assertThat(expiredPackage.status()).isEqualTo("REJECTED");
        assertThat(expiredPackage.failureReason()).isEqualTo("PACKAGE_EXPIRED");
        verify(gatewayRefundClient, times(2)).release(anyString(), eq(GRANT_ID));
    }

    @Test
    void gatewayFailureLeavesDurablePreparingRefundForTheRecoveryJob() {
        when(gatewayRefundClient.prepare(anyString(), eq(GRANT_ID)))
                .thenThrow(new IllegalStateException("gateway unavailable"));

        assertThatThrownBy(() -> service.apply(
                TENANT_ID, USER_ID, ORDER_NO,
                new RefundApplicationService.ApplyRefundCommand("retry later")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway unavailable");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.refund
                WHERE order_id = ? AND status = 'PREPARING'
                  AND gateway_hold_business_no LIKE 'refund-hold:%'
                  AND policy_version = 1
                  AND created_at = updated_at
                """, Long.class, ORDER_ID)).isEqualTo(1);
    }

    @Test
    void concurrentApplicationsLeaveOnlyOneOpenRefund() throws Exception {
        when(gatewayRefundClient.prepare(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> queuedReady(invocation.getArgument(0)));
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = List.of(
                    executor.submit(() -> applyAfter(start)),
                    executor.submit(() -> applyAfter(start)));
            start.countDown();
            List<Object> results = List.of(futures.get(0).get(), futures.get(1).get());
            assertThat(results.stream().filter(RefundApplicationService.RefundView.class::isInstance))
                    .hasSize(1);
            assertThat(results.stream().filter(ResponseStatusException.class::isInstance))
                    .hasSize(1);
        }
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.refund
                WHERE order_id = ? AND status = 'PENDING_APPROVAL'
                """, Long.class, ORDER_ID)).isEqualTo(1);
        verify(gatewayRefundClient, times(1)).prepare(anyString(), eq(GRANT_ID));
    }

    @Test
    void cancelDuringDelayedPrepareWaitsAndThenReleasesThePreparedHold() throws Exception {
        CountDownLatch prepareEntered = new CountDownLatch(1);
        CountDownLatch finishPrepare = new CountDownLatch(1);
        when(gatewayRefundClient.prepare(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> {
                    prepareEntered.countDown();
                    if (!finishPrepare.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("prepare_test_timeout");
                    }
                    return queuedReady(invocation.getArgument(0));
                });
        when(gatewayRefundClient.release(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> new GatewayRefundClient.ReleaseResult(
                        invocation.getArgument(0), "RELEASED"));

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RefundApplicationService.RefundView> application = executor.submit(() ->
                    service.apply(TENANT_ID, USER_ID, ORDER_NO,
                            new RefundApplicationService.ApplyRefundCommand("cancel race")));
            assertThat(prepareEntered.await(5, TimeUnit.SECONDS)).isTrue();
            String refundNo = singleString(
                    "SELECT refund_no FROM platform.refund WHERE order_id = ?", ORDER_ID);

            CountDownLatch cancelStarted = new CountDownLatch(1);
            Future<RefundApplicationService.RefundView> cancellation = executor.submit(() -> {
                cancelStarted.countDown();
                return service.cancel(TENANT_ID, USER_ID, refundNo);
            });
            assertThat(cancelStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(cancellation.isDone()).isFalse();
            finishPrepare.countDown();

            assertThat(application.get(5, TimeUnit.SECONDS).status())
                    .isEqualTo("PENDING_APPROVAL");
            assertThat(cancellation.get(5, TimeUnit.SECONDS).status()).isEqualTo("CANCELED");
            verify(gatewayRefundClient, times(1))
                    .release("refund-hold:" + refundNo, GRANT_ID);
        } finally {
            finishPrepare.countDown();
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void approvalReplaysExactSnapshotBeforeAuditAndSubmitting() {
        when(gatewayRefundClient.prepare(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> queuedReady(invocation.getArgument(0)));
        RefundApplicationService.RefundView applied = service.apply(
                TENANT_ID, USER_ID, ORDER_NO,
                new RefundApplicationService.ApplyRefundCommand("approve me"));

        double decisionsBefore = counter("platform.refund.decision", "APPROVED");
        RefundApplicationService.RefundView approved = service.approve(ADMIN_ID, applied.refundNo());

        assertThat(approved.status()).isEqualTo("SUBMITTING");
        assertThat(approved.reviewedBy()).isEqualTo(ADMIN_ID);
        assertThat(approved.reviewedAt()).isNotNull();
        verify(gatewayRefundClient, times(2))
                .prepare("refund-hold:" + applied.refundNo(), GRANT_ID);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action = 'REFUND_APPROVED' AND resource_id = ?
                """, Long.class, applied.refundNo())).isEqualTo(1);
        assertThat(counter("platform.refund.decision", "APPROVED"))
                .isEqualTo(decisionsBefore + 1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void changedSnapshotBlocksApprovalWithoutActorOrAudit() {
        when(gatewayRefundClient.prepare(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> queuedReady(invocation.getArgument(0)));
        RefundApplicationService.RefundView applied = service.apply(
                TENANT_ID, USER_ID, ORDER_NO,
                new RefundApplicationService.ApplyRefundCommand("changed"));
        // 预留会在审批时被重新读取，且已不再与备好的快照匹配。这里
        // 被测的只是这次比较，因此重新读取的是任意一个不同的快照。
        when(gatewayRefundClient.prepare("refund-hold:" + applied.refundNo(), GRANT_ID))
                .thenReturn(newReady("refund-hold:" + applied.refundNo(), 99_999));

        assertThatThrownBy(() -> service.approve(ADMIN_ID, applied.refundNo()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("refund_snapshot_changed");
        assertThat(singleString("SELECT status FROM platform.refund WHERE refund_no = ?",
                applied.refundNo())).isEqualTo("PENDING_APPROVAL");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action = 'REFUND_APPROVED' AND resource_id = ?
                """, Long.class, applied.refundNo())).isZero();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectionAndCancellationReleaseOnceAndApprovedRefundCannotBeCanceled() {
        when(gatewayRefundClient.prepare(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> queuedReady(invocation.getArgument(0)));
        when(gatewayRefundClient.release(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> new GatewayRefundClient.ReleaseResult(
                        invocation.getArgument(0), "RELEASED"));
        RefundApplicationService.RefundView rejected = service.apply(
                TENANT_ID, USER_ID, ORDER_NO,
                new RefundApplicationService.ApplyRefundCommand("reject"));
        double decisionsBefore = counter("platform.refund.decision", "REJECTED");
        service.reject(ADMIN_ID, rejected.refundNo(),
                new RefundApplicationService.RejectRefundCommand("not approved"));
        service.reject(ADMIN_ID, rejected.refundNo(),
                new RefundApplicationService.RejectRefundCommand("not approved"));
        verify(gatewayRefundClient, times(1))
                .release("refund-hold:" + rejected.refundNo(), GRANT_ID);
        assertThat(counter("platform.refund.decision", "REJECTED"))
                .isEqualTo(decisionsBefore + 1);

        RefundApplicationService.RefundView canceledBase = newRefundAfterClosing(rejected.refundNo());
        service.cancel(TENANT_ID, USER_ID, canceledBase.refundNo());
        service.cancel(TENANT_ID, USER_ID, canceledBase.refundNo());
        verify(gatewayRefundClient, times(1))
                .release("refund-hold:" + canceledBase.refundNo(), GRANT_ID);

        RefundApplicationService.RefundView approvedBase = newRefundAfterClosing(canceledBase.refundNo());
        service.approve(ADMIN_ID, approvedBase.refundNo());
        assertThatThrownBy(() -> service.cancel(TENANT_ID, USER_ID, approvedBase.refundNo()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("refund_cannot_be_canceled");
    }

    @Test
    @WithMockUser(roles = "USER")
    void nonAdministratorCannotApproveOrReject() {
        when(gatewayRefundClient.prepare(anyString(), eq(GRANT_ID)))
                .thenAnswer(invocation -> queuedReady(invocation.getArgument(0)));
        RefundApplicationService.RefundView applied = service.apply(
                TENANT_ID, USER_ID, ORDER_NO,
                new RefundApplicationService.ApplyRefundCommand("admin only"));

        assertThatThrownBy(() -> service.approve(ADMIN_ID, applied.refundNo()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.reject(
                ADMIN_ID, applied.refundNo(),
                new RefundApplicationService.RejectRefundCommand("admin only")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.adminDetail(applied.refundNo()))
                .isInstanceOf(AccessDeniedException.class);
    }

    private double counter(String name, String outcome) {
        var counter = meterRegistry.find(name).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private Object applyAfter(CountDownLatch start) {
        try {
            start.await();
            return service.apply(TENANT_ID, USER_ID, ORDER_NO,
                    new RefundApplicationService.ApplyRefundCommand("concurrent"));
        } catch (ResponseStatusException exception) {
            return exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private RefundApplicationService.RefundView newRefundAfterClosing(String refundNo) {
        jdbcTemplate.update("UPDATE platform.refund SET status = 'CANCELED' WHERE refund_no = ?",
                refundNo);
        return service.apply(TENANT_ID, USER_ID, ORDER_NO,
                new RefundApplicationService.ApplyRefundCommand("next"));
    }

    private GatewayRefundClient.RefundSnapshot stableReady(String businessNo, long usedTokens) {
        return stableSnapshots.computeIfAbsent(
                businessNo, ignored -> newReady(businessNo, usedTokens));
    }

    /**
     * 网关对一个从未激活的套餐给出的快照：该实例仍为
     * QUEUED，因此不携带到期时间，而这正是网关自身的校验
     * 绑定在一起的一对。这是唯一一种可以退款的形态，因此它是
     * 每个可退款用例的起点；实例为 ACTIVE 的快照
     * 无论其 token 计数器如何都会被拒绝。
     */
    private GatewayRefundClient.RefundSnapshot queuedReady(String businessNo) {
        return stableSnapshots.computeIfAbsent(businessNo, ignored ->
                new GatewayRefundClient.RefundSnapshot(
                        businessNo, PACKAGE_ID, "READY", "QUEUED",
                        1_000_000L, 0L, 1_000_000L, null, Instant.now()));
    }

    private GatewayRefundClient.RefundSnapshot newReady(String businessNo, long usedTokens) {
        Instant now = Instant.now();
        return new GatewayRefundClient.RefundSnapshot(
                businessNo, PACKAGE_ID, "READY", "ACTIVE",
                1_000_000L, usedTokens, 1_000_000L - usedTokens,
                now.plusSeconds(30L * 86_400), now);
    }

    private GatewayRefundClient.RefundSnapshot waiting(String businessNo) {
        Instant now = Instant.now();
        return new GatewayRefundClient.RefundSnapshot(
                businessNo, PACKAGE_ID, "WAITING", "ACTIVE",
                null, null, null, now.plusSeconds(30L * 86_400), null);
    }

    private GatewayRefundClient.RefundSnapshot expiredReady(String businessNo) {
        Instant now = Instant.now();
        return new GatewayRefundClient.RefundSnapshot(
                businessNo, PACKAGE_ID, "READY", "ACTIVE",
                1_000_000L, 0L, 1_000_000L, now.minusSeconds(1), now.minusSeconds(2));
    }

    private void insertUser(UUID id, String email) {
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'hash', 'Refund User', 'ACTIVE')
                """, id, TENANT_ID, email);
    }

    private void insertPaidOrder() {
        Instant paidAt = Instant.now().minusSeconds(86_400);
        jdbcTemplate.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, paid_at,
                    package_snapshot, expires_at, next_close_at, settlement_method)
                VALUES (?, ?, ?, ?, ?, 10001, 'CNY', 'PAID', 'MOCK', ?,
                        '{}'::jsonb, ?, ?, 'EXTERNAL')
                """, ORDER_ID, ORDER_NO, TENANT_ID, USER_ID, VERSION_ID,
                Timestamp.from(paidAt), Timestamp.from(paidAt.plusSeconds(1800)),
                Timestamp.from(paidAt.plusSeconds(1800)));
        jdbcTemplate.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status,
                    payment_product, paid_at, applied_to_order)
                VALUES (?, 'pay_refund_test', ?, 'MOCK', 10001, 'SUCCEEDED',
                        'MOCK', ?, true)
                """, PAYMENT_ID, ORDER_ID, Timestamp.from(paidAt));
        jdbcTemplate.update("""
                UPDATE platform.customer_order
                SET successful_payment_transaction_id = ? WHERE id = ?
                """, PAYMENT_ID, ORDER_ID);
        // QUEUED，而非 ACTIVE：退款仅在套餐被激活之前可用，
        // 因此可退款的夹具是一个其激活握手从未完成的订单
        // （completed_at 保持为 NULL，这正是网关报告
        // QUEUED 期间发件箱发布器的行为）。需要已激活套餐的测试会调用
        // activateTheGrant()。
        jdbcTemplate.update("""
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason, created_by)
                VALUES (?, ?, ?, ?, ?, 'QUEUED', 'ORDER', 'paid order', ?)
                """, GRANT_ID, "order:" + ORDER_NO, TENANT_ID, USER_ID, VERSION_ID, USER_ID);
    }

    /**
     * 把夹具的权益发放推进到网关确认激活后发件箱发布器所写入的状态，
     * 而正是这个状态使该套餐不可退款。
     */
    private void activateTheGrant() {
        jdbcTemplate.update("""
                UPDATE platform.grant_request
                SET status = 'ACTIVE', completed_at = now()
                WHERE id = ?
                """, GRANT_ID);
    }

    private void convertOrderToWalletFunding() {
        jdbcTemplate.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount, version)
                VALUES (?, ?, ?, 'CNY', 0, 1)
                """, ACCOUNT_ID, TENANT_ID, USER_ID);
        jdbcTemplate.update("""
                INSERT INTO platform.balance_ledger(
                    id, entry_no, tenant_id, user_id, account_id,
                    entry_type, amount_delta, paid_amount, bonus_amount,
                    balance_before, balance_after, business_type, business_id,
                    business_no, idempotency_key)
                VALUES (?, 'wle_refund_application_debit', ?, ?, ?,
                        'PACKAGE_PURCHASE', -10001, 0, 0,
                        10001, 0, 'ORDER', ?, ?, ?)
                """, DEBIT_LEDGER_ID, TENANT_ID, USER_ID, ACCOUNT_ID,
                ORDER_ID, ORDER_NO, "wallet-pay:" + ORDER_NO);
        jdbcTemplate.update("""
                UPDATE platform.customer_order
                SET settlement_method = 'BALANCE', payment_provider = 'UNSELECTED',
                    successful_payment_transaction_id = NULL,
                    balance_debit_ledger_id = ?
                WHERE id = ?
                """, DEBIT_LEDGER_ID, ORDER_ID);
    }

    private void insertPaidRechargeOrder() {
        Instant paidAt = Instant.now().minusSeconds(60);
        jdbcTemplate.update("""
                INSERT INTO platform.recharge_order(
                    id, recharge_no, tenant_id, user_id,
                    paid_amount, bonus_amount, credited_amount,
                    currency, status, idempotency_key, expires_at, paid_at,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 1000, 0, 1000,
                        'CNY', 'PAID', 'refund-recharge-guard', ?, ?, ?, ?)
                """, RECHARGE_ID, RECHARGE_NO, TENANT_ID, USER_ID,
                Timestamp.from(paidAt.plusSeconds(600)), Timestamp.from(paidAt),
                Timestamp.from(paidAt.minusSeconds(60)), Timestamp.from(paidAt));
    }

    private String singleString(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, String.class, arguments);
    }
}
