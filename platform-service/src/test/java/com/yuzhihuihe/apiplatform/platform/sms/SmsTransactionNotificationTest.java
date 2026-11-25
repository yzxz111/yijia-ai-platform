package com.yuzhihuihe.apiplatform.platform.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationService;
import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationTestFactory;
import com.yuzhihuihe.apiplatform.platform.notification.NotificationEventTestFactory;
import com.yuzhihuihe.apiplatform.platform.outbox.OutboxPublisher;
import com.yuzhihuihe.apiplatform.platform.grant.GrantService;
import com.yuzhihuihe.apiplatform.platform.grant.PackageFulfillmentService;
import com.yuzhihuihe.apiplatform.platform.grant.PackageSnapshotService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentActionType;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAttemptService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAvailabilityService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAnomalyService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentCompensationJob;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentLifecycleService;
import com.yuzhihuihe.apiplatform.platform.payment.PackagePaymentPostingService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentServiceTestFactory;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import com.yuzhihuihe.apiplatform.platform.payment.ExpiredOrderJob;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentPostingService;
import com.yuzhihuihe.apiplatform.platform.payment.RechargePaymentPostingService;
import com.yuzhihuihe.apiplatform.platform.refund.RefundOutboxCompletionService;
import com.yuzhihuihe.apiplatform.platform.refund.RefundServiceTestFactory;
import com.yuzhihuihe.apiplatform.platform.order.PackageOrderSettlementService;
import com.yuzhihuihe.apiplatform.platform.order.PackageOrderSettlementTestFactory;
import com.yuzhihuihe.apiplatform.platform.wallet.WalletServiceTestFactory;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SmsTransactionNotificationTest extends SmsPostgresTestSupport {

    private static final UUID TENANT_ID =
            UUID.fromString("9a000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("9a000000-0000-4000-8000-000000000002");
    private static final UUID STARTER_VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000102");

    @Test
    void rechargeSuccessFormatsMinorUnitsAndDeduplicatesByBusinessIdentity() {
        insertUser(TENANT_ID, USER_ID, true);
        bindPhone(USER_ID, "13800138000");
        CommercialNotificationService notifications = commercialNotifications();

        assertThat(notifications.notifyRechargeSucceeded(
                "rch_sms_1", TENANT_ID, USER_ID, 12_345, 67_890)).isTrue();
        assertThat(notifications.notifyRechargeSucceeded(
                "rch_sms_1", TENANT_ID, USER_ID, 12_345, 67_890)).isFalse();

        assertThat(jdbc.queryForMap("""
                SELECT event_type, payload->>'payMoney' AS pay_money,
                       payload->>'balance' AS balance, event_key
                FROM platform.notification_event
                """))
                .containsEntry("event_type", "BALANCE_RECHARGE_SUCCEEDED")
                .containsEntry("pay_money", "123.45")
                .containsEntry("balance", "678.90")
                .containsEntry("event_key", "recharge-succeeded:rch_sms_1");
    }

    @Test
    void rechargeSuccessRollbackEnqueuesNothing() {
        insertUser(TENANT_ID, USER_ID, true);
        bindPhone(USER_ID, "13800138000");

        new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    commercialNotifications().notifyRechargeSucceeded(
                            "rch_sms_rollback", TENANT_ID, USER_ID, 1_000, 2_000);
                    status.setRollbackOnly();
                });

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.notification_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.sms_delivery", Integer.class)).isZero();
    }

    @Test
    void boundUserRequiresPaymentConfirmProof() {
        insertUser(TENANT_ID, USER_ID, true);
        bindPhone(USER_ID, "13800138000");
        insertOrder("ord_sms_proof_required");

        assertThatThrownBy(() -> paymentAttempts(proofs(new java.security.SecureRandom())).create(
                TENANT_ID, USER_ID, "ord_sms_proof_required", request(null), "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_sms_proof");
    }

    @Test
    void unboundUserRemainsCompatible() {
        insertUser(TENANT_ID, USER_ID, true);
        insertOrder("ord_sms_unbound");

        PaymentAttemptService.PaymentAttemptResult result =
                paymentAttempts(proofs(new java.security.SecureRandom())).create(
                        TENANT_ID, USER_ID, "ord_sms_unbound", request(null), "127.0.0.1");

        assertThat(result.status()).isEqualTo("PENDING");
    }

    @Test
    void verifiedPhonePaymentConsumesMatchingProofOnlyOnce() {
        insertUser(TENANT_ID, USER_ID, true);
        bindPhone(USER_ID, "13800138000");
        insertOrder("ord_sms_proof_1");
        insertOrder("ord_sms_proof_2");
        SmsProofService proofService = proofs(new java.security.SecureRandom());
        String proof = proofService.issue(
                TENANT_ID, USER_ID, SmsPurpose.PAYMENT_CONFIRM).token();
        PaymentAttemptService service = paymentAttempts(proofService);

        PaymentAttemptService.PaymentAttemptResult result = service.create(
                TENANT_ID, USER_ID, "ord_sms_proof_1", request(proof), "127.0.0.1");

        assertThat(result.status()).isEqualTo("PENDING");
        assertThatThrownBy(() -> service.create(
                TENANT_ID, USER_ID, "ord_sms_proof_2", request(proof), "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_sms_proof");
    }

    @Test
    void paymentPrepareFailureRollsBackProofConsumption() {
        insertUser(TENANT_ID, USER_ID, true);
        bindPhone(USER_ID, "13800138000");
        insertOrder("ord_sms_rollback");
        SmsProofService proofService = proofs(new java.security.SecureRandom());
        String proof = proofService.issue(
                TENANT_ID, USER_ID, SmsPurpose.PAYMENT_CONFIRM).token();
        SmsProofService failingProofService = new SmsProofService(
                new SmsProofRepository(jdbc), transactions, clock, new java.security.SecureRandom(),
                java.time.Duration.ofMinutes(5)) {
            @Override
            public ProofClaims consume(
                    String token, UUID tenantId, UUID userId, SmsPurpose purpose) {
                ProofClaims claims = super.consume(token, tenantId, userId, purpose);
                throw new IllegalStateException("fail_after_proof_consumption");
            }
        };

        assertThatThrownBy(() -> paymentAttempts(failingProofService).create(
                TENANT_ID, USER_ID, "ord_sms_rollback", request(proof), "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("fail_after_proof_consumption");

        assertThat(proofService.consume(
                proof, TENANT_ID, USER_ID, SmsPurpose.PAYMENT_CONFIRM).purpose())
                .isEqualTo(SmsPurpose.PAYMENT_CONFIRM);
    }

    @Test
    void finalPaymentFailureEnqueuesOnceWithFenFormattedAsYuan() {
        insertUser(TENANT_ID, USER_ID, true);
        bindPhone(USER_ID, "13800138000");
        UUID paymentId = UUID.randomUUID();
        CommercialNotificationService notification = commercialNotifications();

        assertThat(notification.notifyPaymentFailed(
                paymentId, TENANT_ID, USER_ID, 12345L)).isTrue();
        assertThat(notification.notifyPaymentFailed(
                paymentId, TENANT_ID, USER_ID, 12345L)).isFalse();

        assertThat(delivery()).containsEntry("template_key", "ORDER_PAYMENT_FAILED")
                .containsEntry("value", "123.45")
                .containsEntry("deduplication_key", "payment-failed:" + paymentId);
    }

    @Test
    void closedOrderEnqueuesTimeoutOnce() {
        insertUser(TENANT_ID, USER_ID, true);
        bindPhone(USER_ID, "13800138000");
        UUID orderId = UUID.randomUUID();
        CommercialNotificationService notification = commercialNotifications();

        assertThat(notification.notifyOrderTimedOut(
                orderId, TENANT_ID, USER_ID, "ord_sms_timeout")).isTrue();
        assertThat(notification.notifyOrderTimedOut(
                orderId, TENANT_ID, USER_ID, "ord_sms_timeout")).isFalse();

        assertThat(delivery()).containsEntry("template_key", "ORDER_TIMED_OUT")
                .containsEntry("value", "ord_sms_timeout")
                .containsEntry("deduplication_key", "order-timeout:" + orderId);
    }

    @Test
    void completedRefundEnqueuesOnceWithFenFormattedAsYuan() {
        insertUser(TENANT_ID, USER_ID, true);
        bindPhone(USER_ID, "13800138000");
        UUID refundId = UUID.randomUUID();
        CommercialNotificationService notification = commercialNotifications();

        assertThat(notification.notifyRefundSucceeded(
                refundId, TENANT_ID, USER_ID, 505L)).isTrue();
        assertThat(notification.notifyRefundSucceeded(
                refundId, TENANT_ID, USER_ID, 505L)).isFalse();

        assertThat(delivery()).containsEntry("template_key", "REFUND_SUCCEEDED")
                .containsEntry("value", "5.05")
                .containsEntry("deduplication_key", "refund-completed:" + refundId);
    }

    @Test
    void queuedGrantDoesNotNotifyAndActiveGrantEnqueuesOnce() {
        insertUser(TENANT_ID, USER_ID, true);
        bindPhone(USER_ID, "13800138000");
        insertOrder("ord_sms_grant");
        UUID grantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason)
                VALUES (?, 'order:ord_sms_grant', ?, ?, ?, 'QUEUED', 'ORDER', 'sms test')
                """, grantId, TENANT_ID, USER_ID, STARTER_VERSION_ID);

        CommercialNotificationService notification = commercialNotifications();
        assertThat(notification.notifyGrantActive(grantId)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.notification_event", Integer.class)).isZero();

        jdbc.update("""
                UPDATE platform.grant_request
                SET status = 'ACTIVE', completed_at = ?
                WHERE id = ?
                """, Timestamp.from(clock.instant()), grantId);
        assertThat(notification.notifyGrantActive(grantId)).isTrue();
        assertThat(notification.notifyGrantActive(grantId)).isFalse();

        assertThat(jdbc.queryForMap("""
                SELECT event_type, payload->>'payMoney' AS pay_money,
                       payload->>'expireDate' AS expire_date, event_key
                FROM platform.notification_event
                """)).containsEntry("event_type", "PACKAGE_PAYMENT_SUCCEEDED")
                .containsEntry("pay_money", "123.45")
                .containsEntry("expire_date", "2026-09-03")
                .containsEntry("event_key", "grant-active:" + grantId);
    }

    @Test
    void compensationFinalFailureEnqueuesExactlyOnce() {
        insertUser(TENANT_ID, USER_ID, true);
        bindPhone(USER_ID, "13800138000");
        insertOrder("ord_sms_compensation");
        UUID paymentId = UUID.randomUUID();
        insertPendingPayment(paymentId, "pay_sms_compensation", "ord_sms_compensation");
        assertThat(jdbc.queryForObject("""
                SELECT next_query_at <= ?
                FROM platform.payment_transaction
                WHERE id = ?
                """, Boolean.class, Timestamp.from(clock.instant()), paymentId)).isTrue();
        PaymentLifecycleService lifecycle = mock(PaymentLifecycleService.class);
        when(lifecycle.queryForCompensation("pay_sms_compensation")).thenAnswer(ignored -> {
            int updated = jdbc.update("""
                    UPDATE platform.payment_transaction
                    SET status = 'FAILED' WHERE id = ? AND status = 'PENDING'
                    """, paymentId);
            if (updated == 1) {
                commercialNotifications().notifyPaymentFailed(
                        paymentId, TENANT_ID, USER_ID, 12345);
            }
            return new PaymentLifecycleService.PaymentQueryResult(
                    "pay_sms_compensation", "FAILED", false);
        });
        PaymentCompensationJob job = compensationJob(lifecycle);

        assertThat(job.runOnce()).isEqualTo(1);
        assertThat(job.runOnce()).isZero();

        assertThat(delivery()).containsEntry("template_key", "ORDER_PAYMENT_FAILED")
                .containsEntry("value", "123.45")
                .containsEntry("deduplication_key", "payment-failed:" + paymentId);
    }

    @Test
    void authoritativeOrderCloseEnqueuesExactlyOnce() {
        insertUser(TENANT_ID, USER_ID, true);
        bindPhone(USER_ID, "13800138000");
        UUID orderId = insertOrder(
                "ord_sms_authoritative_timeout", clock.instant().minusSeconds(60));
        PaymentLifecycleService lifecycle = mock(PaymentLifecycleService.class);
        when(lifecycle.resolveAndCloseSystem("ord_sms_authoritative_timeout"))
                .thenAnswer(ignored -> {
                    int updated = jdbc.update("""
                            UPDATE platform.customer_order
                            SET status = 'CLOSED' WHERE id = ? AND status = 'PENDING'
                            """, orderId);
                    if (updated == 1) {
                        commercialNotifications().notifyOrderTimedOut(
                                orderId, TENANT_ID, USER_ID,
                                "ord_sms_authoritative_timeout");
                    }
                    return new PaymentLifecycleService.CloseOrderResult(
                            "ord_sms_authoritative_timeout", "CLOSED", null, false);
                });
        ExpiredOrderJob job = expiredOrderJob(lifecycle);

        assertThat(job.runOnce()).isEqualTo(1);
        assertThat(job.runOnce()).isZero();

        assertThat(delivery()).containsEntry("template_key", "ORDER_TIMED_OUT")
                .containsEntry("value", "ord_sms_authoritative_timeout")
                .containsEntry("deduplication_key", "order-timeout:" + orderId);
    }

    @Test
    void authoritativeRefundCompletionEnqueuesExactlyOnce() {
        insertUser(TENANT_ID, USER_ID, true);
        bindPhone(USER_ID, "13800138000");
        UUID orderId = insertOrder("ord_sms_refund", clock.instant().plusSeconds(900));
        jdbc.update("UPDATE platform.customer_order SET status = 'PAID' WHERE id = ?", orderId);
        UUID paymentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status)
                VALUES (?, 'pay_sms_refund', ?, 'MOCK', 12345, 'SUCCEEDED')
                """, paymentId, orderId);
        UUID packageInstanceId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason, completed_at)
                VALUES (?, 'order:ord_sms_refund', ?, ?, ?, 'ACTIVE', 'ORDER',
                        'sms refund test', now())
                """, grantId, TENANT_ID, USER_ID, STARTER_VERSION_ID);
        UUID refundId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.refund(
                    id, refund_no, order_id, payment_transaction_id, amount, status,
                    refund_type, calculation_snapshot, reason, requested_by, provider)
                VALUES (?, 'ref_sms_completed', ?, ?, 505, 'REVOKING', 'FULL',
                        jsonb_build_object('packageInstanceId', ?::text),
                        'sms refund test', ?, 'MOCK')
                """, refundId, orderId, paymentId, packageInstanceId, USER_ID);
        RefundOutboxCompletionService completion = refundCompletion();
        RefundOutboxCompletionService.RevocationResult response =
                new RefundOutboxCompletionService.RevocationResult(
                        packageInstanceId, "REVOKED", null, null);

        new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(ignored -> completion.complete(refundId, response));
        new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(ignored -> completion.complete(refundId, response));

        assertThat(delivery()).containsEntry("template_key", "REFUND_SUCCEEDED")
                .containsEntry("value", "5.05")
                .containsEntry("deduplication_key", "refund-completed:" + refundId);
    }

    @Test
    void immediateFinalPaymentFailureEnqueuesInApplyTransaction() {
        insertUser(TENANT_ID, USER_ID, true);
        bindPhone(USER_ID, "13800138000");
        insertOrder("ord_sms_immediate_failure");
        SmsProofService proofService = proofs(new java.security.SecureRandom());
        String proof = proofService.issue(
                TENANT_ID, USER_ID, SmsPurpose.PAYMENT_CONFIRM).token();

        PaymentAttemptService.PaymentAttemptResult result =
                paymentAttempts(proofService, ProviderOutcome.FAILED).create(
                        TENANT_ID, USER_ID, "ord_sms_immediate_failure",
                        request(proof), "127.0.0.1");

        UUID paymentId = jdbc.queryForObject("""
                SELECT id FROM platform.payment_transaction WHERE transaction_no = ?
                """, UUID.class, result.transactionNo());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(delivery()).containsEntry("template_key", "ORDER_PAYMENT_FAILED")
                .containsEntry("value", "123.45")
                .containsEntry("deduplication_key", "payment-failed:" + paymentId);
    }

    @Test
    void outboxQueuedDoesNotNotifyAndActiveTransitionEnqueuesOnce() throws Exception {
        insertUser(TENANT_ID, USER_ID, true);
        bindPhone(USER_ID, "13800138000");
        AtomicReference<String> activationStatus = new AtomicReference<>("QUEUED");
        UUID packageInstanceId = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicInteger deliveryAttempts =
                new java.util.concurrent.atomic.AtomicInteger();
        HttpServer gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/internal/events/package-granted", exchange -> {
            exchange.getRequestBody().readAllBytes();
            deliveryAttempts.incrementAndGet();
            byte[] response = ("{\"packageInstanceId\":\"" + packageInstanceId
                    + "\",\"status\":\"" + activationStatus.get() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        gateway.start();
        try {
            UUID grantId = insertGrantWithOutbox("ord_sms_outbox_queued_active");
            OutboxPublisher publisher = outboxPublisher(gateway.getAddress().getPort());
            publisher.publishPending();
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM platform.grant_request WHERE id = ?",
                    String.class, grantId)).isEqualTo("QUEUED");
            Map<String, Object> queuedOutbox = jdbc.queryForMap("""
                    SELECT status, attempt_count, claim_token,
                           next_attempt_at BETWEEN now() + interval '4 minutes'
                                   AND now() + interval '6 minutes' AS recheck_delayed
                    FROM platform.outbox_event
                    WHERE aggregate_id = ?
                    """, grantId);
            assertThat(queuedOutbox)
                    .containsEntry("status", "PENDING")
                    .containsEntry("attempt_count", 0)
                    .containsEntry("recheck_delayed", true);
            assertThat(queuedOutbox.get("claim_token")).isNull();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM platform.notification_event", Integer.class)).isZero();

            activationStatus.set("ACTIVE");
            jdbc.update("""
                    UPDATE platform.outbox_event SET next_attempt_at = now()
                    WHERE aggregate_id = ?
                    """, grantId);
            publisher.publishPending();
            publisher.publishPending();

            assertThat(jdbc.queryForObject(
                    "SELECT status FROM platform.grant_request WHERE id = ?",
                    String.class, grantId)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM platform.outbox_event WHERE aggregate_id = ?",
                    String.class, grantId)).isEqualTo("DELIVERED");
            assertThat(deliveryAttempts).hasValue(2);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM platform.notification_event", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT event_key FROM platform.notification_event
                    """, String.class)).isEqualTo("grant-active:" + grantId);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM platform.sms_delivery", Integer.class)).isZero();
        } finally {
            gateway.stop(0);
        }
    }

    @Test
    void paymentPostingActiveGrantReplayEnqueuesOnce() {
        insertUser(TENANT_ID, USER_ID, true);
        bindPhone(USER_ID, "13800138000");
        UUID orderId = insertOrder("ord_sms_posting_active");
        jdbc.update("""
                UPDATE platform.customer_order SET package_snapshot = ?::jsonb WHERE id = ?
                """, fullSnapshot(), orderId);
        UUID grantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason, completed_at)
                VALUES (?, 'order:ord_sms_posting_active', ?, ?, ?, 'ACTIVE', 'ORDER',
                        'existing active grant', ?)
                """, grantId, TENANT_ID, USER_ID, STARTER_VERSION_ID,
                Timestamp.from(clock.instant()));
        Instant expiresAt = clock.instant().plusSeconds(900);
        String transactionNo = "pay_sms_posting_active";
        String requestHash = sha256(String.join("\n",
                transactionNo, "ord_sms_posting_active", "12345", "CNY",
                "MOCK", "MOCK", expiresAt.toString()));
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status,
                    payment_product, expires_at, request_payload_hash)
                VALUES (?, ?, ?, 'MOCK', 12345, 'PENDING', 'MOCK', ?, ?)
                """, UUID.randomUUID(), transactionNo, orderId,
                Timestamp.from(expiresAt), requestHash);
        PackageFulfillmentService fulfillment = mock(PackageFulfillmentService.class);
        when(fulfillment.fulfill(any())).thenReturn(new GrantService.GrantResult(
                grantId, UUID.randomUUID(), "order:ord_sms_posting_active", "ACTIVE"));
        PaymentPostingService posting = paymentPosting(fulfillment);

        posting.postSucceeded(new PaymentAdapter.ProviderPayment(
                ProviderOutcome.SUCCEEDED, transactionNo, "trade-sms-posting-active",
                12345, "CNY", clock.instant(), "SUCCESS", null, "a".repeat(64)));

        assertThat(delivery()).containsEntry("template_key", "PACKAGE_PAYMENT_SUCCEEDED")
                .containsEntry("deduplication_key", "grant-active:" + grantId);
    }

    private PaymentAttemptService paymentAttempts(SmsProofService proofService) {
        return paymentAttempts(proofService, ProviderOutcome.PENDING);
    }

    private PaymentAttemptService paymentAttempts(
            SmsProofService proofService, ProviderOutcome outcome) {
        PaymentAvailabilityService availability = mock(PaymentAvailabilityService.class);
        PaymentAdapter adapter = mock(PaymentAdapter.class);
        when(availability.requireCheckout(PaymentProvider.MOCK, PaymentProduct.MOCK))
                .thenReturn(adapter);
        when(adapter.createPayment(any())).thenReturn(new PaymentAdapter.PaymentCreation(
                outcome, outcome == ProviderOutcome.FAILED ? null : "external-sms-test",
                outcome == ProviderOutcome.FAILED ? "REJECTED" : "WAIT_BUYER_PAY",
                new PaymentAdapter.PaymentAction(
                        PaymentActionType.NONE, null, Map.of(), null,
                        clock.instant().plusSeconds(300))));
        return PaymentServiceTestFactory.paymentAttempt(
                jdbc, availability,
                new CommercialTransactionMetrics(new SimpleMeterRegistry()),
                transactions, Clock.fixed(clock.instant(), ZoneOffset.UTC),
                proofService, commercialNotifications());
    }

    private PaymentCompensationJob compensationJob(PaymentLifecycleService lifecycle) {
        return new PaymentCompensationJob(
                jdbc, lifecycle, mock(PaymentAnomalyService.class), transactions, clock,
                10, Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofHours(1),
                3);
    }

    private ExpiredOrderJob expiredOrderJob(PaymentLifecycleService lifecycle) {
        return new ExpiredOrderJob(
                jdbc, lifecycle, mock(PaymentAnomalyService.class), transactions, clock,
                10, Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofHours(1),
                3);
    }

    private RefundOutboxCompletionService refundCompletion() {
        return RefundServiceTestFactory.outboxCompletion(jdbc, commercialNotifications());
    }

    private OutboxPublisher outboxPublisher(int port) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("transactionManager", transactions);
        ObjectProvider<org.springframework.transaction.PlatformTransactionManager> provider =
                beans.getBeanProvider(org.springframework.transaction.PlatformTransactionManager.class);
        return new OutboxPublisher(
                jdbc, objectMapper, provider,
                RefundServiceTestFactory.outboxCompletion(jdbc, commercialNotifications()),
                new CommercialTransactionMetrics(new SimpleMeterRegistry()),
                clock, "http://127.0.0.1:" + port,
                Duration.ofSeconds(1), Duration.ofSeconds(2),
                "test-internal-secret", Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofHours(1), 3, commercialNotifications(),
                // 订阅周期开始的通知：本测试只关心既有的发放/短信链路，传 null 即关闭该分支。
                null);
    }

    private PaymentPostingService paymentPosting(PackageFulfillmentService fulfillment) {
        CommercialNotificationService notifications = commercialNotifications();
        PackageOrderSettlementService settlement = PackageOrderSettlementTestFactory.create(
                jdbc, objectMapper, mock(PackageSnapshotService.class),
                fulfillment, notifications);
        return PaymentServiceTestFactory.paymentPosting(
                jdbc, settlement, WalletServiceTestFactory.balancePosting(jdbc), notifications,
                Clock.systemUTC(), Duration.ofHours(1));
    }

    private Map<String, Object> delivery() {
        return jdbc.queryForMap("""
                SELECT event_type AS template_key,
                       COALESCE(payload->>'payMoney', payload->>'refundMoney',
                                payload->>'orderNo') AS value,
                       event_key AS deduplication_key
                FROM platform.notification_event
                """);
    }

    private CommercialNotificationService commercialNotifications() {
        return CommercialNotificationTestFactory.create(
                jdbc, NotificationEventTestFactory.create(jdbc, objectMapper), clock);
    }

    private PaymentAttemptService.CreatePaymentAttemptRequest request(String proof) {
        return new PaymentAttemptService.CreatePaymentAttemptRequest(
                PaymentProvider.MOCK, PaymentProduct.MOCK, proof);
    }

    private void insertPendingPayment(UUID paymentId, String transactionNo, String orderNo) {
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status,
                    payment_product, next_query_at)
                SELECT ?, ?, id, 'MOCK', amount, 'PENDING', 'MOCK', ?
                FROM platform.customer_order WHERE order_no = ?
                """, paymentId, transactionNo,
                Timestamp.from(clock.instant().minusSeconds(1)), orderNo);
    }

    private UUID insertGrantWithOutbox(String orderNo) {
        insertOrder(orderNo);
        UUID grantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason)
                VALUES (?, ?, ?, ?, ?, 'QUEUED', 'ORDER', 'sms outbox test')
                """, grantId, "order:" + orderNo, TENANT_ID, USER_ID, STARTER_VERSION_ID);
        jdbc.update("""
                INSERT INTO platform.outbox_event(
                    id, aggregate_type, aggregate_id, event_type, business_key, payload)
                VALUES (?, 'GRANT', ?, 'PACKAGE_GRANTED_V1', ?, '{}'::jsonb)
                """, UUID.randomUUID(), grantId, "order:" + orderNo);
        return grantId;
    }

    private String fullSnapshot() {
        return """
                {
                  "packageVersionId":"%s",
                  "packageCode":"STARTER",
                  "packageName":"Starter",
                  "priceAmount":12345,
                  "currency":"CNY",
                  "totalTokenQuota":5000000,
                  "durationDays":30,
                  "maxApiKeys":3,
                  "requestsPerMinute":20,
                  "tokensPerMinute":100000,
                  "maxConcurrency":3,
                  "dailyRequestLimit":null,
                  "maxOutputTokens":null,
                  "maxFileSizeBytes":20971520,
                  "oncePerUser":false,
                  "models":[]
                }
                """.formatted(STARTER_VERSION_ID);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("test_hash_failed", error);
        }
    }

    private UUID insertOrder(String orderNo) {
        return insertOrder(orderNo, clock.instant().plusSeconds(900));
    }

    private UUID insertOrder(String orderNo, Instant expiresAt) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, expires_at,
                    package_snapshot, next_close_at)
                VALUES (?, ?, ?, ?, ?, 12345, 'CNY', 'PENDING', 'UNSELECTED', ?,
                        '{"packageCode":"STARTER","durationDays":30}'::jsonb, ?)
                """, orderId, orderNo, TENANT_ID, USER_ID, STARTER_VERSION_ID,
                Timestamp.from(expiresAt), Timestamp.from(expiresAt));
        return orderId;
    }
}
