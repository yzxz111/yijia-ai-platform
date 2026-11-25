package com.yuzhihuihe.apiplatform.platform.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationService;
import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationTestFactory;
import com.yuzhihuihe.apiplatform.platform.notification.NotificationEventTestFactory;
import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import com.yuzhihuihe.apiplatform.platform.payment.ExpiredOrderJob;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAnomalyService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAvailabilityService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentCompensationJob;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentLifecycleService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentPostingService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentServiceTestFactory;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SmsLifecycleNotificationIntegrationTest extends SmsPostgresTestSupport {

    private static final UUID TENANT_ID =
            UUID.fromString("9b000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("9b000000-0000-4000-8000-000000000002");
    private static final UUID STARTER_VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000102");

    @BeforeEach
    void resetLifecycleState() {
        jdbc.execute("""
                TRUNCATE TABLE platform.sms_delivery, platform.payment_anomaly,
                    platform.admin_audit_log, platform.outbox_event, platform.grant_request,
                    platform.refund, platform.payment_transaction, platform.customer_order,
                    platform.app_user CASCADE
                """);
        insertUser(TENANT_ID, USER_ID, true);
        bindPhone(USER_ID, "13800138000");
    }

    @Test
    void compensationFinalFailureUsesLifecycleAuthorityAndEnqueuesOnce() {
        UUID orderId = insertOrder("ord_sms_real_compensation", false);
        UUID paymentId = insertPayment(
                orderId, "pay_sms_real_compensation", "PENDING", true);
        PaymentAdapter adapter = mock(PaymentAdapter.class);
        when(adapter.queryPayment(any())).thenReturn(new PaymentAdapter.ProviderPayment(
                ProviderOutcome.FAILED, "pay_sms_real_compensation", null,
                12345, "CNY", null, "FAILED", "DECLINED", null));
        PaymentLifecycleService lifecycle = lifecycle(adapter);
        PaymentCompensationJob job = compensationJob(lifecycle);

        assertThat(job.runOnce()).isEqualTo(1);
        assertThat(job.runOnce()).isZero();

        assertThat(transactionStatus(paymentId)).isEqualTo("FAILED");
        assertThat(deliveryCount("payment-failed:" + paymentId)).isEqualTo(1);
    }

    @Test
    void systemTimeoutWithoutPaymentAttemptEnqueuesOnceAcrossRepeatedJobs() {
        UUID orderId = insertOrder("ord_sms_real_no_attempt", true);
        PaymentLifecycleService lifecycle = lifecycle(mock(PaymentAdapter.class));
        ExpiredOrderJob job = expiredOrderJob(lifecycle);

        assertThat(job.runOnce()).isEqualTo(1);
        assertThat(job.runOnce()).isZero();

        assertThat(orderStatus(orderId)).isEqualTo("CLOSED");
        assertThat(deliveryCount("order-timeout:" + orderId)).isEqualTo(1);
    }

    @Test
    void systemTimeoutAfterProviderFinalUnpaidEnqueuesOnceAcrossRepeatedJobs() {
        UUID orderId = insertOrder("ord_sms_real_provider_unpaid", true);
        UUID paymentId = insertPayment(
                orderId, "pay_sms_real_provider_unpaid", "PENDING", false);
        PaymentAdapter adapter = mock(PaymentAdapter.class);
        when(adapter.queryPayment(any())).thenReturn(new PaymentAdapter.ProviderPayment(
                ProviderOutcome.CLOSED, "pay_sms_real_provider_unpaid", null,
                12345, "CNY", null, "CLOSED", null, null));
        PaymentLifecycleService lifecycle = lifecycle(adapter);
        ExpiredOrderJob job = expiredOrderJob(lifecycle);

        assertThat(job.runOnce()).isEqualTo(1);
        assertThat(job.runOnce()).isZero();

        assertThat(orderStatus(orderId)).isEqualTo("CLOSED");
        assertThat(transactionStatus(paymentId)).isEqualTo("CLOSED");
        assertThat(deliveryCount("order-timeout:" + orderId)).isEqualTo(1);
    }

    @Test
    void manualCloseDoesNotEnqueueSystemTimeoutNotification() {
        UUID orderId = insertOrder("ord_sms_real_manual_close", true);
        PaymentLifecycleService lifecycle = lifecycle(mock(PaymentAdapter.class));

        PaymentLifecycleService.CloseOrderResult result = lifecycle.resolveAndClose(
                "ord_sms_real_manual_close",
                new PaymentLifecycleService.Actor(TENANT_ID, USER_ID));

        assertThat(result.orderStatus()).isEqualTo("CLOSED");
        assertThat(orderStatus(orderId)).isEqualTo("CLOSED");
        assertThat(deliveryCount("order-timeout:" + orderId)).isZero();
    }

    private PaymentLifecycleService lifecycle(PaymentAdapter adapter) {
        PaymentAvailabilityService availability = mock(PaymentAvailabilityService.class);
        when(availability.requireOperational(
                PaymentProvider.MOCK, PaymentProduct.MOCK)).thenReturn(adapter);
        return PaymentServiceTestFactory.lifecycle(
                jdbc, availability, mock(PaymentPostingService.class),
                mock(PaymentAnomalyService.class),
                new CommercialTransactionMetrics(new SimpleMeterRegistry()),
                transactions, clock, Duration.ofSeconds(5), commercialNotifications());
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

    private UUID insertOrder(String orderNo, boolean expired) {
        UUID orderId = UUID.randomUUID();
        Timestamp expiresAt = Timestamp.from(clock.instant().plusSeconds(expired ? -60 : 900));
        jdbc.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, expires_at,
                    package_snapshot, next_close_at)
                VALUES (?, ?, ?, ?, ?, 12345, 'CNY', 'PENDING', 'MOCK', ?,
                        '{"packageCode":"STARTER","durationDays":30}'::jsonb, ?)
                """, orderId, orderNo, TENANT_ID, USER_ID, STARTER_VERSION_ID,
                expiresAt, expiresAt);
        return orderId;
    }

    private UUID insertPayment(
            UUID orderId, String transactionNo, String status, boolean dueForQuery) {
        UUID paymentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status,
                    payment_product, next_query_at)
                VALUES (?, ?, ?, 'MOCK', 12345, ?, 'MOCK', ?)
                """, paymentId, transactionNo, orderId, status,
                Timestamp.from(clock.instant().plusSeconds(dueForQuery ? -1 : 900)));
        return paymentId;
    }

    private String transactionStatus(UUID paymentId) {
        return jdbc.queryForObject(
                "SELECT status FROM platform.payment_transaction WHERE id = ?",
                String.class, paymentId);
    }

    private String orderStatus(UUID orderId) {
        return jdbc.queryForObject(
                "SELECT status FROM platform.customer_order WHERE id = ?",
                String.class, orderId);
    }

    private long deliveryCount(String deduplicationKey) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_event WHERE event_key = ?
                """, Long.class, deduplicationKey);
    }

    private CommercialNotificationService commercialNotifications() {
        return CommercialNotificationTestFactory.create(
                jdbc, NotificationEventTestFactory.create(jdbc, objectMapper), clock);
    }
}
