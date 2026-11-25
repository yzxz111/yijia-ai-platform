package com.yuzhihuihe.apiplatform.platform.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAvailabilityService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false",
        "outbox.publisher.initial-delay-ms=3600000",
        "refund.jobs.preparation-delay-ms=3600000",
        "refund.jobs.compensation-delay-ms=3600000",
        "refund.jobs.batch-size=2",
        "refund.jobs.claim-timeout=30s",
        "refund.jobs.initial-backoff=5s",
        "refund.jobs.max-backoff=1h",
        "refund.jobs.max-attempts=3"
})
class RefundJobsTest {

    private static final UUID TENANT_ID =
            UUID.fromString("44000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("44000000-0000-4000-8000-000000000002");
    private static final UUID VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000102");

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
    JdbcTemplate jdbc;

    @Autowired
    RefundPreparationJob preparationJob;

    @Autowired
    RefundCompensationJob compensationJob;

    @MockitoBean
    GatewayRefundClient gatewayRefundClient;

    @MockitoBean
    PaymentAvailabilityService availabilityService;

    PaymentAdapter adapter;
    List<Fixture> fixtures;

    @BeforeEach
    void resetState() {
        reset(gatewayRefundClient, availabilityService);
        adapter = mock(PaymentAdapter.class);
        when(availabilityService.requireOperational(PaymentProvider.MOCK, PaymentProduct.MOCK))
                .thenReturn(adapter);
        jdbc.update("TRUNCATE platform.payment_anomaly, platform.admin_audit_log, "
                + "platform.outbox_event, platform.refund, platform.payment_transaction, "
                + "platform.customer_order, platform.grant_request, platform.refresh_token, "
                + "platform.user_role, platform.invitation, platform.app_user "
                + "RESTART IDENTITY CASCADE");
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'refund-jobs@example.com', 'hash', 'Refund Jobs', 'ACTIVE')
                """, USER_ID, TENANT_ID);
        jdbc.update("""
                INSERT INTO platform.refund_policy_version(
                    version, fee_bps, minimum_fee, refund_window_days,
                    maximum_usage_bps, effective_at, published)
                VALUES (1, 500, 100, 7, 1000, now() - interval '1 day', true)
                """);
        fixtures = new ArrayList<>();
    }

    @Test
    void twoPreparationWorkersUseSkipLockedClaimsAndDoNotHoldLocksDuringGatewayIo()
            throws Exception {
        Fixture first = insertRefund("claim_1", "PREPARING", 0, null);
        Fixture second = insertRefund("claim_2", "PREPARING", 0, null);
        Fixture third = insertRefund("claim_3", "PREPARING", 0, null);
        when(gatewayRefundClient.prepare(anyString(), any())).thenAnswer(invocation -> {
            String businessNo = invocation.getArgument(0);
            updateFromIndependentConnection("""
                    UPDATE platform.refund SET updated_at = now()
                    WHERE gateway_hold_business_no = ?
                    """, businessNo);
            return queued(businessNo);
        });
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> workers = List.of(
                    executor.submit(() -> runAfter(start, preparationJob)),
                    executor.submit(() -> runAfter(start, preparationJob)));
            start.countDown();
            assertThat(workers.get(0).get() + workers.get(1).get()).isEqualTo(3);
        }

        verify(gatewayRefundClient, times(3)).prepare(anyString(), any());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.refund
                WHERE id IN (?, ?, ?) AND status = 'PENDING_APPROVAL'
                """, Long.class, first.refundId(), second.refundId(), third.refundId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.refund
                WHERE job_claim_token IS NOT NULL OR job_claimed_until IS NOT NULL
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.refund WHERE provider_attempt_count = 1
                """, Long.class)).isEqualTo(3);
    }

    @Test
    void preparationClaimsEachBatchItemImmediatelyBeforeGatewayIo() {
        insertRefund("lease_prepare_1", "PREPARING", 0, null);
        insertRefund("lease_prepare_2", "PREPARING", 0, null);
        when(gatewayRefundClient.prepare(anyString(), any())).thenAnswer(invocation -> {
            String businessNo = invocation.getArgument(0);
            assertThat(jdbc.queryForObject("""
                    SELECT job_claimed_until > now() FROM platform.refund
                    WHERE gateway_hold_business_no = ?
                    """, Boolean.class, businessNo)).isTrue();
            jdbc.update("""
                    UPDATE platform.refund
                    SET job_claimed_until = now() - interval '1 second'
                    WHERE status = 'PREPARING'
                      AND gateway_hold_business_no <> ?
                      AND job_claim_token IS NOT NULL
                    """, businessNo);
            return queued(businessNo);
        });

        assertThat(preparationJob.runOnce()).isEqualTo(2);

        verify(gatewayRefundClient, times(2)).prepare(anyString(), any());
    }

    @Test
    void compensationClaimsEachBatchItemImmediatelyBeforeProviderIo() {
        insertRefund("lease_query_1", "PROCESSING", 8_500L, "PARTIAL");
        insertRefund("lease_query_2", "PROCESSING", 8_500L, "PARTIAL");
        when(adapter.queryRefund(any())).thenAnswer(invocation -> {
            PaymentAdapter.RefundQuery query = invocation.getArgument(0);
            assertThat(jdbc.queryForObject("""
                    SELECT job_claimed_until > now() FROM platform.refund
                    WHERE refund_no = ?
                    """, Boolean.class, query.refundNo())).isTrue();
            jdbc.update("""
                    UPDATE platform.refund
                    SET job_claimed_until = now() - interval '1 second'
                    WHERE status = 'PROCESSING'
                      AND refund_no <> ?
                      AND job_claim_token IS NOT NULL
                    """, query.refundNo());
            return providerRefund(ProviderOutcome.SUCCEEDED, query.refundNo(), query.amount());
        });

        assertThat(compensationJob.runOnce()).isEqualTo(2);

        verify(adapter, times(2)).queryRefund(any());
    }

    @Test
    void waitingPreparationRetriesStoredPolicyAndOriginalRequestTimeThenReleasesIfIneligible() {
        Fixture waiting = insertRefund("waiting", "PREPARING", 0, null);
        when(gatewayRefundClient.prepare(waiting.holdBusinessNo(), waiting.grantId()))
                .thenReturn(waiting(waiting.holdBusinessNo()))
                .thenReturn(queued(waiting.holdBusinessNo()));

        assertThat(preparationJob.runOnce()).isEqualTo(1);
        assertThat(singleString("SELECT status FROM platform.refund WHERE id = ?", waiting.refundId()))
                .isEqualTo("PREPARING");
        jdbc.update("""
                UPDATE platform.refund SET next_attempt_at = now() - interval '1 second' WHERE id = ?
                """, waiting.refundId());
        assertThat(preparationJob.runOnce()).isEqualTo(1);

        assertThat(jdbc.queryForMap("""
                SELECT status, policy_version, amount,
                       calculation_snapshot->>'policyVersion' AS snapshot_policy,
                       calculation_snapshot->>'requestedAt' AS requested_at
                FROM platform.refund WHERE id = ?
                """, waiting.refundId()))
                // 从未激活过的套餐按全额退款；已存储的策略版本与
                // 原始请求时间仍然是准备阶段所持久化的值，而这
                // 正是本用例所关注的内容。
                .containsEntry("status", "PENDING_APPROVAL")
                .containsEntry("policy_version", 1)
                .containsEntry("amount", 10_001L)
                .containsEntry("snapshot_policy", "1");
        Instant persistedRequest = jdbc.queryForObject(
                "SELECT created_at FROM platform.refund WHERE id = ?",
                Timestamp.class, waiting.refundId()).toInstant();
        assertThat(Instant.parse((String) jdbc.queryForMap("""
                SELECT calculation_snapshot->>'requestedAt' AS requested_at
                FROM platform.refund WHERE id = ?
                """, waiting.refundId()).get("requested_at")))
                .isEqualTo(persistedRequest);

        Fixture ineligible = insertRefund("ineligible", "PREPARING", 0, null);
        jdbc.update("UPDATE platform.customer_order SET paid_at = now() - interval '8 days' WHERE id = ?",
                ineligible.orderId());
        when(gatewayRefundClient.prepare(ineligible.holdBusinessNo(), ineligible.grantId()))
                .thenReturn(ready(ineligible.holdBusinessNo(), 100_000L));
        when(gatewayRefundClient.release(ineligible.holdBusinessNo(), ineligible.grantId()))
                .thenReturn(new GatewayRefundClient.ReleaseResult(
                        ineligible.holdBusinessNo(), "RELEASED"));

        assertThat(preparationJob.runOnce()).isEqualTo(1);

        assertThat(singleString("SELECT status FROM platform.refund WHERE id = ?", ineligible.refundId()))
                .isEqualTo("REJECTED");
        verify(gatewayRefundClient).release(ineligible.holdBusinessNo(), ineligible.grantId());
    }

    @Test
    void compensationQueryConvertsSuccessOrFinalFailureWithoutChangingRefundNumbers() {
        Fixture success = insertRefund("query_success", "PROCESSING", 8_500L, "PARTIAL");
        Fixture failure = insertRefund("query_failure", "PROCESSING", 8_500L, "PARTIAL");
        when(adapter.queryRefund(any())).thenAnswer(invocation -> {
            PaymentAdapter.RefundQuery query = invocation.getArgument(0);
            ProviderOutcome outcome = query.refundNo().equals(success.refundNo())
                    ? ProviderOutcome.SUCCEEDED : ProviderOutcome.CLOSED;
            return providerRefund(outcome, query.refundNo(), query.amount());
        });
        when(gatewayRefundClient.release(failure.holdBusinessNo(), failure.grantId()))
                .thenReturn(new GatewayRefundClient.ReleaseResult(
                        failure.holdBusinessNo(), "RELEASED"));

        assertThat(compensationJob.runOnce()).isEqualTo(2);

        assertThat(singleString("SELECT status FROM platform.refund WHERE id = ?", success.refundId()))
                .isEqualTo("REVOKING");
        assertThat(singleString("SELECT status FROM platform.refund WHERE id = ?", failure.refundId()))
                .isEqualTo("FAILED");
        verify(gatewayRefundClient).release(failure.holdBusinessNo(), failure.grantId());
        ArgumentCaptor<PaymentAdapter.RefundQuery> queries =
                ArgumentCaptor.forClass(PaymentAdapter.RefundQuery.class);
        verify(adapter, times(2)).queryRefund(queries.capture());
        assertThat(queries.getAllValues()).extracting(PaymentAdapter.RefundQuery::refundNo)
                .containsExactlyInAnyOrder(success.refundNo(), failure.refundNo());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.refund
                WHERE refund_no IN (?, ?)
                """, Long.class, success.refundNo(), failure.refundNo())).isEqualTo(2);
    }

    @Test
    void compensationRecoversBalanceSubmissionWithoutProviderQueryOrIdentity() {
        Fixture wallet = insertRefund("wallet_submit", "SUBMITTING", 8_500L, "PARTIAL");
        convertToWalletFunding(wallet);

        assertThat(compensationJob.runOnce()).isEqualTo(1);

        assertThat(jdbc.queryForMap("""
                SELECT status, funding_source, payment_transaction_id, provider,
                       external_refund_no, provider_attempt_count
                FROM platform.refund WHERE id = ?
                """, wallet.refundId()))
                .containsEntry("status", "REVOKING")
                .containsEntry("funding_source", "BALANCE")
                .containsEntry("payment_transaction_id", null)
                .containsEntry("provider", null)
                .containsEntry("external_refund_no", null)
                .containsEntry("provider_attempt_count", 0);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.outbox_event
                WHERE aggregate_id = ? AND event_type = 'PACKAGE_REFUNDED_V1'
                """, Long.class, wallet.refundId())).isEqualTo(1);
        verify(availabilityService, never()).requireOperational(any(), any());
        verify(adapter, never()).createRefund(any());
        verify(adapter, never()).queryRefund(any());
    }

    @Test
    void providerUncertaintyRetainsProcessingWithExponentialRetryAndSameNumber() {
        Fixture refund = insertRefund("uncertain", "PROCESSING", 8_500L, "PARTIAL");
        when(adapter.queryRefund(any())).thenAnswer(invocation -> {
            PaymentAdapter.RefundQuery query = invocation.getArgument(0);
            return providerRefund(ProviderOutcome.UNCERTAIN, query.refundNo(), query.amount());
        });

        assertThat(compensationJob.runOnce()).isEqualTo(1);
        Duration first = durationUntilAttempt(refund.refundId());
        assertThat(first).isBetween(Duration.ofSeconds(3), Duration.ofSeconds(7));
        jdbc.update("""
                UPDATE platform.refund SET next_attempt_at = now() - interval '1 second' WHERE id = ?
                """, refund.refundId());
        assertThat(compensationJob.runOnce()).isEqualTo(1);
        Duration second = durationUntilAttempt(refund.refundId());
        assertThat(second).isBetween(Duration.ofSeconds(8), Duration.ofSeconds(12));
        assertThat(singleString("SELECT status FROM platform.refund WHERE id = ?", refund.refundId()))
                .isEqualTo("PROCESSING");

        ArgumentCaptor<PaymentAdapter.RefundQuery> queries =
                ArgumentCaptor.forClass(PaymentAdapter.RefundQuery.class);
        verify(adapter, times(2)).queryRefund(queries.capture());
        assertThat(queries.getAllValues()).extracting(PaymentAdapter.RefundQuery::refundNo)
                .containsOnly(refund.refundNo());
    }

    @Test
    void maximumAutomatedAttemptsCreatesOneAnomalyAndStopsRetrying() {
        Fixture refund = insertRefund("exhausted", "PROCESSING", 8_500L, "PARTIAL");
        jdbc.update("""
                UPDATE platform.refund
                SET provider_attempt_count = 2,
                    next_attempt_at = now() - interval '1 second'
                WHERE id = ?
                """, refund.refundId());
        when(adapter.queryRefund(any())).thenReturn(providerRefund(
                ProviderOutcome.UNCERTAIN, refund.refundNo(), 8_500L));

        assertThat(compensationJob.runOnce()).isEqualTo(1);
        assertThat(compensationJob.runOnce()).isZero();

        assertThat(singleString("SELECT status FROM platform.refund WHERE id = ?", refund.refundId()))
                .isEqualTo("PROCESSING");
        assertThat(singleString("SELECT next_attempt_at::text FROM platform.refund WHERE id = ?",
                refund.refundId())).isEqualTo("infinity");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.payment_anomaly
                WHERE anomaly_type = 'REFUND_QUERY_EXHAUSTED' AND refund_id = ?
                """, Long.class, refund.refundId())).isEqualTo(1);
        assertThat(singleString("SELECT refund_no FROM platform.refund WHERE id = ?", refund.refundId()))
                .isEqualTo(refund.refundNo());
    }

    @Test
    void expiredFinalAttemptClaimsAreFencedAndExhaustedWithoutExternalIo() {
        Fixture preparation = insertRefund("crashed_prepare", "PREPARING", 0, null);
        Fixture compensation = insertRefund(
                "crashed_compensation", "PROCESSING", 8_500L, "PARTIAL");
        jdbc.update("""
                UPDATE platform.refund
                SET provider_attempt_count = 3,
                    next_attempt_at = now() - interval '1 second',
                    job_claim_token = ?, job_claimed_until = now() - interval '1 second'
                WHERE id IN (?, ?)
                """, UUID.randomUUID(), preparation.refundId(), compensation.refundId());

        assertThat(preparationJob.runOnce()).isEqualTo(1);
        assertThat(compensationJob.runOnce()).isEqualTo(1);
        assertThat(preparationJob.runOnce()).isZero();
        assertThat(compensationJob.runOnce()).isZero();

        verify(gatewayRefundClient, never()).prepare(anyString(), any());
        verify(gatewayRefundClient, never()).release(anyString(), any());
        verify(adapter, never()).createRefund(any());
        verify(adapter, never()).queryRefund(any());
        assertExhausted(preparation, "REFUND_PREPARATION_EXHAUSTED");
        assertExhausted(compensation, "REFUND_QUERY_EXHAUSTED");
    }

    private void assertExhausted(Fixture fixture, String anomalyType) {
        assertThat(jdbc.queryForMap("""
                SELECT provider_attempt_count, next_attempt_at::text AS next_attempt_at,
                       job_claim_token, job_claimed_until
                FROM platform.refund WHERE id = ?
                """, fixture.refundId()))
                .containsEntry("provider_attempt_count", 3)
                .containsEntry("next_attempt_at", "infinity")
                .containsEntry("job_claim_token", null)
                .containsEntry("job_claimed_until", null);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.payment_anomaly
                WHERE anomaly_type = ? AND refund_id = ?
                """, Long.class, anomalyType, fixture.refundId())).isEqualTo(1);
    }

    private int runAfter(CountDownLatch start, RefundPreparationJob job) throws Exception {
        start.await();
        return job.runOnce();
    }

    private Fixture insertRefund(String suffix, String status, long amount, String refundType) {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        String orderNo = "ord_" + suffix;
        String refundNo = "ref_" + suffix;
        String holdBusinessNo = "refund-hold:" + refundNo;
        Instant paidAt = Instant.now().minus(Duration.ofDays(1));
        Instant requestedAt = paidAt.plus(Duration.ofHours(1));
        jdbc.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, paid_at,
                    package_snapshot, expires_at, next_close_at)
                VALUES (?, ?, ?, ?, ?, 10001, 'CNY', 'PAID', 'MOCK', ?,
                        '{}'::jsonb, ?, ?)
                """, orderId, orderNo, TENANT_ID, USER_ID, VERSION_ID, Timestamp.from(paidAt),
                Timestamp.from(paidAt.plusSeconds(1_800)), Timestamp.from(paidAt.plusSeconds(1_800)));
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, external_trade_no,
                    amount, status, payment_product, paid_at, applied_to_order)
                VALUES (?, ?, ?, 'MOCK', ?, 10001, 'SUCCEEDED', 'MOCK', ?, true)
                """, paymentId, "pay_" + suffix, orderId, "external_" + suffix,
                Timestamp.from(paidAt));
        jdbc.update("UPDATE platform.customer_order SET successful_payment_transaction_id = ? WHERE id = ?",
                paymentId, orderId);
        // 是 QUEUED 而不是 ACTIVE：仅当套餐尚未激活时才可退款，
        // 因此可退款的测试夹具是一个激活握手从未完成的订单。
        // completed_at 保持为 NULL，这正是发件箱发布器在
        // 网关报告 QUEUED 时所写入的内容。
        jdbc.update("""
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason, created_by)
                VALUES (?, ?, ?, ?, ?, 'QUEUED', 'ORDER', 'paid order', ?)
                """, grantId, "order:" + orderNo, TENANT_ID, USER_ID, VERSION_ID, USER_ID);
        if ("PREPARING".equals(status)) {
            jdbc.update("""
                    INSERT INTO platform.refund(
                        id, refund_no, order_id, payment_transaction_id, amount, status,
                        policy_version, reason, requested_by, provider,
                        gateway_hold_business_no, provider_attempt_count,
                        next_attempt_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 0, 'PREPARING', 1, 'requested', ?, 'MOCK',
                            ?, 0, now(), ?, ?)
                    """, refundId, refundNo, orderId, paymentId, USER_ID, holdBusinessNo,
                    Timestamp.from(requestedAt), Timestamp.from(requestedAt));
        } else {
            String calculation = """
                    {"paidAmount":10001,"paidAt":"%s","requestedAt":"%s",
                     "totalTokens":1000000,"usedTokens":100000,"remainingTokens":900000,
                     "feeBps":500,"minimumFee":100,"maximumUsageBps":1000,
                     "refundWindowDays":7,"baseRefund":9000,"fee":500,
                     "refundAmount":8500,"refundType":"PARTIAL",
                     "rounding":"BASE_FLOOR_FEE_HALF_UP","policyVersion":1,
                     "calculatedAt":"%s","packageInstanceId":"%s",
                     "originalPackageStatus":"ACTIVE","packageExpiresAt":"%s",
                     "gatewaySnapshotAt":"%s"}
                    """.formatted(paidAt, requestedAt, requestedAt, UUID.randomUUID(),
                    requestedAt.plus(Duration.ofDays(30)), requestedAt);
            jdbc.update("""
                    INSERT INTO platform.refund(
                        id, refund_no, order_id, payment_transaction_id, amount, status,
                        refund_type, policy_version, calculation_snapshot, reason, requested_by,
                        provider, gateway_hold_business_no, provider_attempt_count,
                        next_attempt_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?::jsonb, 'requested', ?, 'MOCK',
                            ?, 0, now(), ?, ?)
                    """, refundId, refundNo, orderId, paymentId, amount, status, refundType,
                    calculation, USER_ID, holdBusinessNo,
                    Timestamp.from(requestedAt), Timestamp.from(requestedAt));
        }
        Fixture fixture = new Fixture(
                refundId, refundNo, orderId, grantId, holdBusinessNo, requestedAt);
        fixtures.add(fixture);
        return fixture;
    }

    private void convertToWalletFunding(Fixture fixture) {
        UUID accountId = UUID.randomUUID();
        UUID debitLedgerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount, version)
                VALUES (?, ?, ?, 'CNY', 0, 1)
                """, accountId, TENANT_ID, USER_ID);
        jdbc.update("""
                INSERT INTO platform.balance_ledger(
                    id, entry_no, tenant_id, user_id, account_id,
                    entry_type, amount_delta, paid_amount, bonus_amount,
                    balance_before, balance_after, business_type, business_id,
                    business_no, idempotency_key)
                VALUES (?, ?, ?, ?, ?, 'PACKAGE_PURCHASE', -10001, 0, 0,
                        10001, 0, 'ORDER', ?, ?, ?)
                """, debitLedgerId, "wle_" + UUID.randomUUID().toString().replace("-", ""),
                TENANT_ID, USER_ID, accountId, fixture.orderId(),
                "ord_wallet_submit", "wallet-pay:" + fixture.orderId());
        jdbc.update("""
                UPDATE platform.customer_order
                SET successful_payment_transaction_id = NULL,
                    settlement_method = 'BALANCE', payment_provider = 'UNSELECTED',
                    balance_debit_ledger_id = ?
                WHERE id = ?
                """, debitLedgerId, fixture.orderId());
        jdbc.update("""
                UPDATE platform.refund
                SET payment_transaction_id = NULL, funding_source = 'BALANCE', provider = NULL
                WHERE id = ?
                """, fixture.refundId());
        jdbc.update("DELETE FROM platform.payment_transaction WHERE order_id = ?",
                fixture.orderId());
    }

    private GatewayRefundClient.RefundSnapshot waiting(String businessNo) {
        return new GatewayRefundClient.RefundSnapshot(
                businessNo, UUID.randomUUID(), "WAITING", "ACTIVE",
                null, null, null, Instant.now().plus(Duration.ofDays(30)), null);
    }

    private GatewayRefundClient.RefundSnapshot ready(String businessNo, long usedTokens) {
        Instant now = Instant.now();
        return new GatewayRefundClient.RefundSnapshot(
                businessNo, UUID.randomUUID(), "READY", "ACTIVE",
                1_000_000L, usedTokens, 1_000_000L - usedTokens,
                now.plus(Duration.ofDays(30)), now);
    }

    /**
     * 网关对从未激活的套餐给出的快照：实例仍处于
     * QUEUED，因此不带到期时间，这正是网关自身的校验
     * 绑定在一起的一对字段。仅在此状态下才可退款，所以它也是每个
     * 成功准备用例的起始快照。
     */
    private GatewayRefundClient.RefundSnapshot queued(String businessNo) {
        return new GatewayRefundClient.RefundSnapshot(
                businessNo, UUID.randomUUID(), "READY", "QUEUED",
                1_000_000L, 0L, 1_000_000L, null, Instant.now());
    }

    private PaymentAdapter.ProviderRefund providerRefund(
            ProviderOutcome outcome, String refundNo, long amount) {
        return new PaymentAdapter.ProviderRefund(
                outcome, refundNo, "external-" + refundNo, amount, outcome.name(),
                outcome == ProviderOutcome.CLOSED ? "CLOSED" : null);
    }

    private Duration durationUntilAttempt(UUID refundId) {
        return Duration.between(Instant.now(), jdbc.queryForObject(
                "SELECT next_attempt_at FROM platform.refund WHERE id = ?",
                Timestamp.class, refundId).toInstant());
    }

    private void updateFromIndependentConnection(String sql, Object value) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(2);
            statement.setObject(1, value);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private String singleString(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, String.class, arguments);
    }

    private record Fixture(
            UUID refundId,
            String refundNo,
            UUID orderId,
            UUID grantId,
            String holdBusinessNo,
            Instant requestedAt) {
    }
}
