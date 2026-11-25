package com.yuzhihuihe.apiplatform.platform.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpServer;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.admin.AdminManagementService;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementRequests.RetryOutboxRequest;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.OutboxView;
import com.yuzhihuihe.apiplatform.platform.outbox.OutboxPublisher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
        "outbox.publisher.initial-backoff=10m",
        "outbox.publisher.max-attempts=2",
        "outbox.publisher.claim-timeout=1m",
        "gateway.internal.connect-timeout=100ms",
        "gateway.internal.read-timeout=250ms",
        "refund.jobs.preparation-delay-ms=3600000",
        "refund.jobs.compensation-delay-ms=3600000"
})
class RefundOutboxTest {

    private static final String SECRET = "test-internal-secret";
    private static final UUID TENANT_ID =
            UUID.fromString("45000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("45000000-0000-4000-8000-000000000002");
    private static final UUID ADMIN_ID =
            UUID.fromString("45000000-0000-4000-8000-000000000003");
    private static final UUID ORDER_ID =
            UUID.fromString("45000000-0000-4000-8000-000000000004");
    private static final UUID PAYMENT_ID =
            UUID.fromString("45000000-0000-4000-8000-000000000005");
    private static final UUID GRANT_ID =
            UUID.fromString("45000000-0000-4000-8000-000000000006");
    private static final UUID PACKAGE_ID =
            UUID.fromString("45000000-0000-4000-8000-000000000007");
    private static final UUID REFUND_ID =
            UUID.fromString("45000000-0000-4000-8000-000000000008");
    private static final UUID EVENT_ID =
            UUID.fromString("45000000-0000-4000-8000-000000000009");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("45000000-0000-4000-8000-00000000000a");
    private static final UUID DEBIT_LEDGER_ID =
            UUID.fromString("45000000-0000-4000-8000-00000000000b");
    private static final UUID SUBSCRIPTION_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static final String ORDER_NO = "ord_refund_outbox";
    private static final String REFUND_NO = "ref_outbox_001";
    private static final String BUSINESS_KEY = "refund-revoke:" + REFUND_NO;
    private static final AtomicReference<String> DELIVERED_PATH = new AtomicReference<>();
    private static final AtomicReference<String> DELIVERED_BODY = new AtomicReference<>();
    private static final AtomicReference<String> DELIVERED_TIMESTAMP = new AtomicReference<>();
    private static final AtomicReference<String> DELIVERED_REQUEST_ID = new AtomicReference<>();
    private static final AtomicReference<String> DELIVERED_CONTENT_SHA256 = new AtomicReference<>();
    private static final AtomicReference<String> DELIVERED_SIGNATURE = new AtomicReference<>();
    private static final AtomicReference<UUID> RESPONSE_PACKAGE_ID =
            new AtomicReference<>(PACKAGE_ID);
    private static final AtomicReference<String> RESPONSE_STATUS = new AtomicReference<>("REVOKED");
    private static final AtomicInteger RESPONSE_HTTP_STATUS = new AtomicInteger(200);
    private static final AtomicInteger DELIVERY_COUNT = new AtomicInteger();
    private static final ExecutorService GATEWAY_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer GATEWAY = startGateway();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("gateway.internal.base-url",
                () -> "http://127.0.0.1:" + GATEWAY.getAddress().getPort());
        registry.add("security.internal.hmac-secret", () -> SECRET);
        TestJwtKeyRing.configure(registry);
    }

    @AfterAll
    static void stopGateway() {
        GATEWAY.stop(0);
        GATEWAY_EXECUTOR.shutdownNow();
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxPublisher outboxPublisher;

    @Autowired
    AdminManagementService adminManagementService;

    @BeforeEach
    void resetState() {
        jdbc.update("TRUNCATE platform.notification_event, platform.operating_fact, "
                + "platform.operating_fact_event_receipt CASCADE");
        jdbc.update("TRUNCATE platform.outbox_retry_request, platform.payment_anomaly, "
                + "platform.admin_audit_log, platform.outbox_event, platform.refund, "
                + "platform.payment_transaction, platform.customer_order, "
                + "platform.grant_request, platform.refresh_token, platform.user_role, "
                + "platform.invitation, platform.app_user RESTART IDENTITY CASCADE");
        insertUser(USER_ID, "refund-outbox-user@example.com");
        insertUser(ADMIN_ID, "refund-outbox-admin@example.com");
        jdbc.update("""
                INSERT INTO platform.refund_policy_version(
                    version, fee_bps, minimum_fee, refund_window_days,
                    maximum_usage_bps, effective_at, published)
                VALUES (1, 500, 100, 7, 1000, now() - interval '1 day', true)
                """);
        insertRefundEvent("PARTIAL", 8_500L);
        DELIVERED_PATH.set(null);
        DELIVERED_BODY.set(null);
        DELIVERED_TIMESTAMP.set(null);
        DELIVERED_REQUEST_ID.set(null);
        DELIVERED_CONTENT_SHA256.set(null);
        DELIVERED_SIGNATURE.set(null);
        RESPONSE_PACKAGE_ID.set(PACKAGE_ID);
        RESPONSE_STATUS.set("REVOKED");
        RESPONSE_HTTP_STATUS.set(200);
        DELIVERY_COUNT.set(0);
    }

    @Test
    void partialRefundUsesExactHmacV2CompletesPartiallyAndAcknowledgesReplaySafely()
            throws Exception {
        outboxPublisher.publishPending();

        assertThat(DELIVERED_PATH).hasValue("/internal/events/package-refunded");
        assertThat(DELIVERED_BODY.get())
                .contains("\"eventType\":\"PACKAGE_REFUNDED_V1\"")
                .contains("\"businessKey\":\"" + BUSINESS_KEY + "\"")
                .contains("\"refundNo\":\"" + REFUND_NO + "\"")
                .contains("\"revocationBusinessNo\":\"" + BUSINESS_KEY + "\"");
        assertThat(DELIVERED_CONTENT_SHA256).hasValue(sha256(DELIVERED_BODY.get()));
        assertThat(DELIVERED_SIGNATURE).hasValue(internalSignature(
                DELIVERED_TIMESTAMP.get(), DELIVERED_REQUEST_ID.get(),
                "/internal/events/package-refunded", DELIVERED_CONTENT_SHA256.get()));
        assertCompleted("PARTIALLY_REFUNDED");

        jdbc.update("""
                UPDATE platform.outbox_event
                SET status = 'PENDING', attempt_count = 0, delivered_at = NULL,
                    next_attempt_at = now()
                WHERE id = ?
                """, EVENT_ID);
        assertThatCode(outboxPublisher::publishPending).doesNotThrowAnyException();

        assertCompleted("PARTIALLY_REFUNDED");
        assertThat(DELIVERY_COUNT).hasValue(2);
    }

    @Test
    void acknowledgementMustMatchPackageInstanceAndRevokedStatus() {
        RESPONSE_PACKAGE_ID.set(UUID.randomUUID());
        outboxPublisher.publishPending();

        assertThat(outboxState())
                .containsEntry("outbox_status", "PENDING")
                .containsEntry("refund_status", "REVOKING")
                .containsEntry("grant_status", "ACTIVE")
                .containsEntry("order_status", "PAID");

        jdbc.update("UPDATE platform.outbox_event SET next_attempt_at = now() WHERE id = ?", EVENT_ID);
        RESPONSE_PACKAGE_ID.set(PACKAGE_ID);
        RESPONSE_STATUS.set("ACTIVE");
        outboxPublisher.publishPending();

        assertThat(outboxState())
                .containsEntry("outbox_status", "FAILED")
                .containsEntry("refund_status", "REVOKING")
                .containsEntry("grant_status", "ACTIVE")
                .containsEntry("order_status", "PAID");
    }

    @Test
    void exhaustedDeliveryFailsOutboxCreatesAnomalyAndManualRetryKeepsRefundRevoking() {
        jdbc.update("UPDATE platform.outbox_event SET attempt_count = 1 WHERE id = ?", EVENT_ID);
        RESPONSE_HTTP_STATUS.set(503);

        outboxPublisher.publishPending();

        assertThat(outboxState())
                .containsEntry("outbox_status", "FAILED")
                .containsEntry("refund_status", "REVOKING")
                .containsEntry("grant_status", "ACTIVE")
                .containsEntry("order_status", "PAID");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.payment_anomaly
                WHERE anomaly_type = 'REFUND_OUTBOX_EXHAUSTED' AND refund_id = ?
                """, Long.class, REFUND_ID)).isEqualTo(1);

        OutboxView retried = adminManagementService.retryOutbox(
                EVENT_ID,
                new RetryOutboxRequest("outbox-refund-retry-001"),
                ADMIN_ID);

        assertThat(retried.status()).isEqualTo("PENDING");
        assertThat(outboxState())
                .containsEntry("outbox_status", "PENDING")
                .containsEntry("attempt_count", 0)
                .containsEntry("refund_status", "REVOKING")
                .containsEntry("grant_status", "ACTIVE")
                .containsEntry("order_status", "PAID");
    }

    @Test
    void fullRefundCompletionAtomicallyRevokesGrantCompletesRefundAndRefundsOrder() {
        jdbc.update("""
                UPDATE platform.refund
                SET refund_type = 'FULL', amount = 10001
                WHERE id = ?
                """, REFUND_ID);

        outboxPublisher.publishPending();

        assertCompleted("REFUNDED");
    }

    @Test
    void walletCompletionCreditsCanonicalLedgerOnceOnlyAfterRevocationAndReplay() {
        convertRefundToWalletFunding();

        assertThat(walletBalance()).isZero();
        assertThat(refundLedgerCount()).isZero();
        assertThat(completionAuditCount()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT balance_refund_ledger_id FROM platform.refund WHERE id = ?
                """, UUID.class, REFUND_ID)).isNull();

        outboxPublisher.publishPending();

        assertCompleted("PARTIALLY_REFUNDED");
        assertThat(walletBalance()).isEqualTo(8_500L);
        assertThat(refundLedgerCount()).isEqualTo(1);
        Map<String, Object> ledger = jdbc.queryForMap("""
                SELECT id, entry_type, amount_delta, paid_amount, bonus_amount,
                       business_type, business_id, business_no, idempotency_key
                FROM platform.balance_ledger
                WHERE entry_type = 'PACKAGE_REFUND' AND business_id = ?
                """, REFUND_ID);
        assertThat(ledger)
                .containsEntry("entry_type", "PACKAGE_REFUND")
                .containsEntry("amount_delta", 8_500L)
                .containsEntry("paid_amount", 0L)
                .containsEntry("bonus_amount", 0L)
                .containsEntry("business_type", "REFUND")
                .containsEntry("business_id", REFUND_ID)
                .containsEntry("business_no", REFUND_NO)
                .containsEntry("idempotency_key", "refund:" + REFUND_NO);
        assertThat(jdbc.queryForObject("""
                SELECT balance_refund_ledger_id FROM platform.refund WHERE id = ?
                """, UUID.class, REFUND_ID)).isEqualTo(ledger.get("id"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.payment_transaction", Long.class)).isZero();
        assertThat(completionAuditCount()).isEqualTo(1);
        assertThat(completionAudit())
                .containsEntry("tenant_id", TENANT_ID)
                .containsEntry("actor_user_id", null)
                .containsEntry("action", "REFUND_COMPLETED")
                .containsEntry("resource_type", "REFUND")
                .containsEntry("resource_id", REFUND_NO)
                .containsEntry("outcome", "SUCCESS")
                .containsEntry("detail_sanitized", true);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_event
                WHERE event_key = ? AND event_type = 'REFUND_SUCCEEDED'
                """, Integer.class, "refund-completed:" + REFUND_ID)).isOne();

        jdbc.update("""
                UPDATE platform.outbox_event
                SET status = 'PENDING', attempt_count = 0, delivered_at = NULL,
                    next_attempt_at = now()
                WHERE id = ?
                """, EVENT_ID);
        outboxPublisher.publishPending();

        assertThat(walletBalance()).isEqualTo(8_500L);
        assertThat(refundLedgerCount()).isEqualTo(1);
        assertThat(completionAuditCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_event
                WHERE event_key = ? AND event_type = 'REFUND_SUCCEEDED'
                """, Integer.class, "refund-completed:" + REFUND_ID)).isOne();
    }

    @Test
    void walletPostingConflictRollsBackCompletionAndLeavesRevocationRetryable() {
        convertRefundToWalletFunding();
        insertConflictingWalletLedger();

        outboxPublisher.publishPending();

        assertThat(outboxState())
                .containsEntry("outbox_status", "PENDING")
                .containsEntry("attempt_count", 1)
                .containsEntry("refund_status", "REVOKING")
                .containsEntry("grant_status", "ACTIVE")
                .containsEntry("order_status", "PAID");
        assertThat(walletBalance()).isEqualTo(100L);
        assertThat(refundLedgerCount()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT balance_refund_ledger_id FROM platform.refund WHERE id = ?
                """, UUID.class, REFUND_ID)).isNull();
        assertThat(completionAuditCount()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_event
                WHERE event_key = ?
                """, Integer.class, "refund-completed:" + REFUND_ID)).isZero();
    }

    @Test
    void missingWalletAccountRollsBackCompletionWithoutAuditOrSms() {
        convertRefundToMissingWalletFunding();

        outboxPublisher.publishPending();

        assertThat(outboxState())
                .containsEntry("outbox_status", "PENDING")
                .containsEntry("attempt_count", 1)
                .containsEntry("refund_status", "REVOKING")
                .containsEntry("grant_status", "ACTIVE")
                .containsEntry("order_status", "PAID");
        assertThat(refundLedgerCount()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT balance_refund_ledger_id FROM platform.refund WHERE id = ?
                """, UUID.class, REFUND_ID)).isNull();
        assertThat(completionAuditCount()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_event
                WHERE event_key = ?
                """, Integer.class, "refund-completed:" + REFUND_ID)).isZero();
    }

    @Test
    void orderCompletionFailureRollsBackWalletCreditLedgerLinkAuditAndSms() {
        convertRefundToWalletFunding();
        jdbc.update("UPDATE platform.customer_order SET status = 'CLOSED' WHERE id = ?", ORDER_ID);

        outboxPublisher.publishPending();

        assertThat(outboxState())
                .containsEntry("outbox_status", "PENDING")
                .containsEntry("attempt_count", 1)
                .containsEntry("refund_status", "REVOKING")
                .containsEntry("grant_status", "ACTIVE")
                .containsEntry("order_status", "CLOSED");
        assertThat(walletBalance()).isZero();
        assertThat(refundLedgerCount()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT balance_refund_ledger_id FROM platform.refund WHERE id = ?
                """, UUID.class, REFUND_ID)).isNull();
        assertThat(completionAuditCount()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_event
                WHERE event_key = ?
                """, Integer.class, "refund-completed:" + REFUND_ID)).isZero();
    }

    /**
     * 退款完成后必须**终止**订阅（设计 §10 第 447-449 行）。
     *
     * <p>实测过的缺陷：退款被批准后订阅仍是 {@code ACTIVE} / {@code auto_renew=true}，
     * 用户既拿到退款又会被继续扣下一期。{@code platform/refund/**} 当时没有任何订阅引用。
     */
    @Test
    void completingARefundTerminatesTheSubscriptionThatOwnsTheOrder() throws Exception {
        openSubscriptionForGrant();

        outboxPublisher.publishPending();

        assertCompleted("PARTIALLY_REFUNDED");
        assertThat(subscriptionRow())
                .containsEntry("status", "TERMINATED")
                .containsEntry("auto_renew", false)
                .containsEntry("cancel_at_period_end", true)
                .containsEntry("last_skip_reason", "refund_terminated");
        assertThat(jdbc.queryForObject(
                "SELECT terminated_at FROM platform.subscription WHERE id = ?",
                Timestamp.class, SUBSCRIPTION_ID)).isNotNull();
        // 终止之后不该再有任何在途扣款。
        assertThat(jdbc.queryForObject(
                "SELECT claim_token FROM platform.subscription WHERE id = ?",
                UUID.class, SUBSCRIPTION_ID)).isNull();
    }

    /**
     * **一次性购买不受影响**：同一笔退款，但发放不属于任何订阅账期。
     *
     * <p>这是本接线最重要的边界——退款交叉只应作用于订阅来源的订单。
     */
    @Test
    void completingARefundLeavesANonSubscriptionOrderAlone() throws Exception {
        // 刻意不建账期行：这时的发放就是一次性购买产生的。
        UUID subscriptionId = insertSubscription();

        outboxPublisher.publishPending();

        assertCompleted("PARTIALLY_REFUNDED");
        assertThat(subscriptionRow())
                .containsEntry("status", "ACTIVE")
                .containsEntry("auto_renew", true);
        assertThat(subscriptionId).isNotNull();
    }

    /** 建一份订阅，并把账期行挂到本测试的发放请求上，使退款可被归属到它。 */
    private UUID openSubscriptionForGrant() {
        UUID subscriptionId = insertSubscription();
        jdbc.update("""
                INSERT INTO platform.subscription_cycle(
                    id, subscription_id, period_index, business_key, status,
                    period_start, period_end, amount, currency, grant_request_id)
                VALUES (?, ?, 1, ?, 'CHARGED', now() - interval '30 days', now(),
                        10001, 'CNY', ?)
                """, UUID.randomUUID(), subscriptionId, "refund-outbox:cycle:" + subscriptionId, GRANT_ID);
        return subscriptionId;
    }

    private UUID insertSubscription() {
        jdbc.update("""
                INSERT INTO platform.subscription(
                    id, tenant_id, user_id, package_plan_id, status, auto_renew,
                    cancel_at_period_end, current_period_start, current_period_end, next_charge_at)
                SELECT ?, ?, ?, package_plan_id, 'ACTIVE', true, false,
                       now() - interval '30 days', now(), now() - interval '1 day'
                FROM platform.package_plan_version WHERE id = ?
                """, SUBSCRIPTION_ID, TENANT_ID, USER_ID, VERSION_ID);
        return SUBSCRIPTION_ID;
    }

    private Map<String, Object> subscriptionRow() {
        return jdbc.queryForMap(
                "SELECT * FROM platform.subscription WHERE id = ?", SUBSCRIPTION_ID);
    }

    private void assertCompleted(String orderStatus) {        assertThat(outboxState())
                .containsEntry("outbox_status", "DELIVERED")
                .containsEntry("refund_status", "COMPLETED")
                .containsEntry("grant_status", "REVOKED")
                .containsEntry("order_status", orderStatus);
        assertThat(jdbc.queryForObject(
                "SELECT completed_at FROM platform.refund WHERE id = ?",
                Timestamp.class, REFUND_ID)).isNotNull();
    }

    private Map<String, Object> outboxState() {
        return jdbc.queryForMap("""
                SELECT event.status AS outbox_status, event.attempt_count, event.last_error,
                       refund.status AS refund_status, grant_request.status AS grant_status,
                       customer_order.status AS order_status
                FROM platform.outbox_event AS event
                JOIN platform.refund AS refund ON refund.id = event.aggregate_id
                JOIN platform.customer_order AS customer_order ON customer_order.id = refund.order_id
                JOIN platform.grant_request AS grant_request
                  ON grant_request.business_no = 'order:' || customer_order.order_no
                WHERE event.id = ?
                """, EVENT_ID);
    }

    private void insertRefundEvent(String refundType, long amount) {
        Instant paidAt = Instant.now().minusSeconds(86_400);
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
                """.formatted(paidAt, paidAt.plusSeconds(60), paidAt.plusSeconds(61), PACKAGE_ID,
                paidAt.plusSeconds(30L * 86_400), paidAt.plusSeconds(61));
        jdbc.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, paid_at,
                    package_snapshot, expires_at, next_close_at, settlement_method)
                VALUES (?, ?, ?, ?, ?, 10001, 'CNY', 'PAID', 'MOCK', ?,
                        '{}'::jsonb, ?, ?, 'EXTERNAL')
                """, ORDER_ID, ORDER_NO, TENANT_ID, USER_ID, VERSION_ID, Timestamp.from(paidAt),
                Timestamp.from(paidAt.plusSeconds(1_800)), Timestamp.from(paidAt.plusSeconds(1_800)));
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, external_trade_no,
                    amount, status, payment_product, paid_at, applied_to_order)
                VALUES (?, 'pay_refund_outbox', ?, 'MOCK', 'mock-trade-outbox',
                        10001, 'SUCCEEDED', 'MOCK', ?, true)
                """, PAYMENT_ID, ORDER_ID, Timestamp.from(paidAt));
        jdbc.update("UPDATE platform.customer_order SET successful_payment_transaction_id = ? WHERE id = ?",
                PAYMENT_ID, ORDER_ID);
        jdbc.update("""
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason, created_by)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', 'ORDER', 'paid order', ?)
                """, GRANT_ID, "order:" + ORDER_NO, TENANT_ID, USER_ID, VERSION_ID, USER_ID);
        jdbc.update("""
                INSERT INTO platform.refund(
                    id, refund_no, order_id, payment_transaction_id, amount, status,
                    refund_type, policy_version, calculation_snapshot, reason, requested_by,
                    provider, gateway_hold_business_no, revocation_business_no,
                    submitted_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, 'REVOKING', ?, 1, ?::jsonb, 'requested', ?, 'MOCK',
                        ?, ?, now(), now())
                """, REFUND_ID, REFUND_NO, ORDER_ID, PAYMENT_ID, amount, refundType,
                calculation, USER_ID, "refund-hold:" + REFUND_NO, BUSINESS_KEY);
        jdbc.update("""
                INSERT INTO platform.outbox_event(
                    id, aggregate_type, aggregate_id, event_type, business_key, payload)
                VALUES (?, 'REFUND', ?, 'PACKAGE_REFUNDED_V1', ?,
                        jsonb_build_object(
                            'refundNo', ?, 'revocationBusinessNo', ?,
                            'grantRequestId', ?, 'userId', ?))
                """, EVENT_ID, REFUND_ID, BUSINESS_KEY, REFUND_NO, BUSINESS_KEY,
                GRANT_ID, USER_ID);
    }

    private void convertRefundToWalletFunding() {
        jdbc.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount, version)
                VALUES (?, ?, ?, 'CNY', 0, 1)
                """, ACCOUNT_ID, TENANT_ID, USER_ID);
        jdbc.update("""
                INSERT INTO platform.balance_ledger(
                    id, entry_no, tenant_id, user_id, account_id,
                    entry_type, amount_delta, paid_amount, bonus_amount,
                    balance_before, balance_after, business_type, business_id,
                    business_no, idempotency_key)
                VALUES (?, 'wle_refund_outbox_debit', ?, ?, ?,
                        'PACKAGE_PURCHASE', -10001, 0, 0, 10001, 0,
                        'ORDER', ?, ?, ?)
                """, DEBIT_LEDGER_ID, TENANT_ID, USER_ID, ACCOUNT_ID,
                ORDER_ID, ORDER_NO, "wallet-pay:" + ORDER_NO);
        jdbc.update("""
                UPDATE platform.customer_order
                SET successful_payment_transaction_id = NULL,
                    settlement_method = 'BALANCE', payment_provider = 'UNSELECTED',
                    balance_debit_ledger_id = ?
                WHERE id = ?
                """, DEBIT_LEDGER_ID, ORDER_ID);
        jdbc.update("""
                UPDATE platform.refund
                SET payment_transaction_id = NULL, funding_source = 'BALANCE', provider = NULL
                WHERE id = ?
                """, REFUND_ID);
        jdbc.update("DELETE FROM platform.payment_transaction WHERE id = ?", PAYMENT_ID);
    }

    private void convertRefundToMissingWalletFunding() {
        jdbc.update("""
                UPDATE platform.customer_order
                SET successful_payment_transaction_id = NULL
                WHERE id = ?
                """, ORDER_ID);
        jdbc.update("""
                UPDATE platform.refund
                SET payment_transaction_id = NULL, funding_source = 'BALANCE', provider = NULL
                WHERE id = ?
                """, REFUND_ID);
        jdbc.update("DELETE FROM platform.payment_transaction WHERE id = ?", PAYMENT_ID);
    }

    private void insertConflictingWalletLedger() {
        jdbc.update("""
                INSERT INTO platform.balance_ledger(
                    id, entry_no, tenant_id, user_id, account_id,
                    entry_type, amount_delta, paid_amount, bonus_amount,
                    balance_before, balance_after, business_type, business_id,
                    business_no, idempotency_key)
                VALUES (?, 'wle_refund_outbox_conflict', ?, ?, ?,
                        'RECHARGE', 100, 100, 0, 0, 100,
                        'RECHARGE_ORDER', ?, 'rch_refund_conflict', ?)
                """, UUID.randomUUID(), TENANT_ID, USER_ID, ACCOUNT_ID,
                UUID.randomUUID(), "refund:" + REFUND_NO);
        jdbc.update("""
                UPDATE platform.account_balance
                SET available_amount = 100, version = version + 1
                WHERE id = ?
                """, ACCOUNT_ID);
    }

    private long walletBalance() {
        return jdbc.queryForObject("""
                SELECT available_amount FROM platform.account_balance WHERE id = ?
                """, Long.class, ACCOUNT_ID);
    }

    private long refundLedgerCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM platform.balance_ledger
                WHERE entry_type = 'PACKAGE_REFUND' AND business_id = ?
                """, Long.class, REFUND_ID);
    }

    private long completionAuditCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE tenant_id = ? AND action = 'REFUND_COMPLETED'
                  AND resource_type = 'REFUND' AND resource_id = ?
                """, Long.class, TENANT_ID, REFUND_NO);
    }

    private Map<String, Object> completionAudit() {
        return jdbc.queryForMap("""
                SELECT tenant_id, actor_user_id, action, resource_type,
                       resource_id, outcome,
                       detail = jsonb_build_object(
                           'amount', 8500,
                           'refundType', 'PARTIAL',
                           'fundingSource', 'BALANCE',
                           'orderStatus', 'PARTIALLY_REFUNDED') AS detail_sanitized
                FROM platform.admin_audit_log
                WHERE tenant_id = ? AND action = 'REFUND_COMPLETED'
                  AND resource_type = 'REFUND' AND resource_id = ?
                """, TENANT_ID, REFUND_NO);
    }

    private void insertUser(UUID id, String email) {
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'hash', 'Refund Outbox', 'ACTIVE')
                """, id, TENANT_ID, email);
    }

    private static HttpServer startGateway() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(GATEWAY_EXECUTOR);
            server.createContext("/internal/events/package-refunded", exchange -> {
                DELIVERY_COUNT.incrementAndGet();
                DELIVERED_PATH.set(exchange.getRequestURI().getPath());
                DELIVERED_TIMESTAMP.set(exchange.getRequestHeaders().getFirst("X-Internal-Timestamp"));
                DELIVERED_REQUEST_ID.set(exchange.getRequestHeaders().getFirst("X-Internal-Request-Id"));
                DELIVERED_CONTENT_SHA256.set(
                        exchange.getRequestHeaders().getFirst("X-Internal-Content-Sha256"));
                DELIVERED_SIGNATURE.set(exchange.getRequestHeaders().getFirst("X-Internal-Signature"));
                DELIVERED_BODY.set(new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = ("{\"packageInstanceId\":\"" + RESPONSE_PACKAGE_ID.get()
                        + "\",\"status\":\"" + RESPONSE_STATUS.get()
                        + "\",\"activatedPackageInstanceId\":null,"
                        + "\"activatedPackageStatus\":null}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(RESPONSE_HTTP_STATUS.get(), response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private String internalSignature(
            String timestamp, String requestId, String path, String contentSha256) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String canonical = timestamp + "\n" + requestId + "\nPOST\n" + path + "\n" + contentSha256;
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private String sha256(String body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
    }
}
