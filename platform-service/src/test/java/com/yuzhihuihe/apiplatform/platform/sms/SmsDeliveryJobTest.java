package com.yuzhihuihe.apiplatform.platform.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SmsDeliveryJobTest extends SmsPostgresTestSupport {

    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "sms-delivery-test-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    @AfterEach
    void stopHeartbeatScheduler() {
        heartbeatScheduler.shutdownNow();
    }

    @Test
    void validatesTemplateAllowlistTypeStatusVariablesPreferenceAndDeduplication() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId, true);
        bindPhone(userId, "15012348963");
        SmsNotificationService service = notifications();

        assertThat(service.enqueue(
                tenantId, userId, "ORDER_PAYMENT_FAILED", Map.of("payMoney", "10.00"), "order:1"))
                .isTrue();
        assertThat(service.enqueue(
                tenantId, userId, "ORDER_PAYMENT_FAILED", Map.of("payMoney", "10.00"), "order:1"))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.sms_delivery WHERE deduplication_key = 'order:1'",
                Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> service.enqueue(
                tenantId, userId, "ORDER_PAYMENT_FAILED", Map.of(), "order:2"))
                .hasMessage("sms_template_variables_mismatch");
        assertThatThrownBy(() -> service.enqueue(
                tenantId, userId, "ORDER_PAYMENT_FAILED", Map.of("payMoney", Map.of("unsafe", "nested")), "order:3"))
                .hasMessage("invalid_sms_template_variable");
        assertThatThrownBy(() -> service.enqueue(
                tenantId, userId, "REGISTER_CODE", Map.of("code", "123456"), "order:4"))
                .hasMessage("sms_template_type_mismatch");
        assertThat(service.enqueue(
                tenantId, userId, "BALANCE_LOW", Map.of("warnMoney", "1.00"), "order:5"))
                .isTrue();
        assertThatThrownBy(() -> service.enqueue(
                tenantId, userId, "GENERIC_VERIFICATION", Map.of("code", "123456"), "order:reserved"))
                .hasMessage("sms_template_not_active");

        jdbc.update("UPDATE platform.app_user SET sms_notifications_enabled = false WHERE id = ?", userId);
        assertThat(service.enqueue(
                tenantId, userId, "ORDER_PAYMENT_FAILED", Map.of("payMoney", "10.00"), "order:6"))
                .isFalse();
    }

    @Test
    void verificationBypassesBusinessPreferenceAndEncryptsSensitiveVariables() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId, false);
        PhoneProtectionService.ProtectedPhone recipient = phones.protect("15112348963");

        assertThat(notifications().enqueueVerification(
                tenantId, userId, "PASSWORD_CODE", recipient,
                Map.of("code", "654321"), "verification:test"))
                .isTrue();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT variables::text AS variables, sensitive_variables_ciphertext
                FROM platform.sms_delivery WHERE deduplication_key = 'verification:test'
                """);
        assertThat(row.get("variables").toString()).doesNotContain("code", "654321");
        assertThat(new String((byte[]) row.get("sensitive_variables_ciphertext"), StandardCharsets.UTF_8))
                .doesNotContain("654321");
        assertThat(new String(encryption.decrypt((byte[]) row.get("sensitive_variables_ciphertext")),
                StandardCharsets.UTF_8)).contains("654321");
    }

    @Test
    void claimsBoundedLeaseAndMarksSuccessWithSanitizedProviderIdentifiers() {
        enqueueDeliveries(3);
        RecordingProvider provider = new RecordingProvider(SmsProvider.Result.success("request-1", "biz-1"));
        SmsDeliveryJob job = job(provider, 2, 3);

        assertThat(job.runOnce()).isEqualTo(2);

        assertThat(provider.requests).hasSize(2);
        assertThat(provider.requests.getFirst().phoneNumber()).startsWith("+86");
        assertThat(provider.requests.getFirst().toString()).doesNotContain("15012348963");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.sms_delivery WHERE status = 'SENT'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.sms_delivery
                WHERE status = 'SENT' AND claim_token IS NULL AND claimed_until IS NULL
                  AND provider_request_id = 'request-1' AND provider_biz_id = 'biz-1'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.sms_delivery WHERE status = 'PENDING'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void claimsOnlyOneDeliveryImmediatelyBeforeEachProviderCall() {
        enqueueDeliveries(3);
        List<Integer> sendingCounts = new ArrayList<>();
        SmsProvider provider = request -> {
            sendingCounts.add(jdbc.queryForObject(
                    "SELECT count(*) FROM platform.sms_delivery WHERE status = 'SENDING'",
                    Integer.class));
            return SmsProvider.Result.success("request", "biz");
        };

        assertThat(job(provider, 3, 3).runOnce()).isEqualTo(3);

        assertThat(sendingCounts).containsExactly(1, 1, 1);
    }

    @Test
    void heartbeatPreventsSecondWorkerReclaimDuringSlowProviderCall() throws Exception {
        enqueueDeliveries(1);
        jdbc.update("UPDATE platform.sms_delivery SET next_attempt_at = now()");
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        SmsProvider delayed = request -> {
            invocations.incrementAndGet();
            providerStarted.countDown();
            try {
                if (!releaseProvider.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("provider_test_timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("provider_test_interrupted");
            }
            return SmsProvider.Result.success("request", "biz");
        };
        Duration lease = Duration.ofMillis(60);
        SmsDeliveryJob firstJob = job(delayed, 1, 3, lease, Clock.systemUTC());
        SmsDeliveryJob secondJob = job(delayed, 1, 3, lease, Clock.systemUTC());

        try (var worker = Executors.newSingleThreadExecutor()) {
            var firstRun = worker.submit(firstJob::runOnce);
            assertThat(providerStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(lease.multipliedBy(4).toMillis());

            assertThat(secondJob.runOnce()).isZero();

            releaseProvider.countDown();
            assertThat(firstRun.get(2, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            releaseProvider.countDown();
        }
        assertThat(invocations).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT status FROM platform.sms_delivery", String.class))
                .isEqualTo("SENT");
    }

    @Test
    void retriesTransientFailureWithBoundedBackoffThenMarksDeadAtMaxAttempts() {
        enqueueDeliveries(1);
        RecordingProvider provider = new RecordingProvider(
                SmsProvider.Result.transientFailure("THROTTLED"));
        SmsDeliveryJob job = job(provider, 1, 2);

        assertThat(job.runOnce()).isEqualTo(1);
        Map<String, Object> first = jdbc.queryForMap("""
                SELECT status, attempt_count, next_attempt_at, claim_token, sanitized_error
                FROM platform.sms_delivery
                """);
        assertThat(first.get("status")).isEqualTo("FAILED");
        assertThat(first.get("attempt_count")).isEqualTo(1);
        assertThat(first.get("claim_token")).isNull();
        assertThat(first.get("sanitized_error")).isEqualTo("TRANSIENT:THROTTLED");

        clock.advance(Duration.ofSeconds(10));
        assertThat(job.runOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM platform.sms_delivery", String.class))
                .isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT claim_token IS NULL AND claimed_until IS NULL FROM platform.sms_delivery",
                Boolean.class)).isTrue();
    }

    @Test
    void mapsPermanentAndNetworkFailuresWithoutPersistingRawSecrets() {
        enqueueDeliveries(1);
        RecordingProvider permanent = new RecordingProvider(
                SmsProvider.Result.permanentFailure("INVALID_PHONE"));
        SmsDeliveryJob permanentJob = job(permanent, 1, 3);
        permanentJob.runOnce();
        assertThat(jdbc.queryForObject("SELECT status FROM platform.sms_delivery", String.class))
                .isEqualTo("DEAD");

        jdbc.update("DELETE FROM platform.sms_delivery");
        jdbc.update("DELETE FROM platform.app_user");
        enqueueDeliveries(1);
        SmsProvider networkFailure = request -> {
            throw new IllegalStateException("raw-response phone=15012348963 code=654321");
        };
        job(networkFailure, 1, 3).runOnce();
        String sanitized = jdbc.queryForObject("SELECT sanitized_error FROM platform.sms_delivery", String.class);
        assertThat(sanitized).isEqualTo("TRANSIENT:PROVIDER_NETWORK_ERROR");
        assertThat(sanitized).doesNotContain("15012348963", "654321", "raw-response");
    }

    @Test
    void corruptLocalPayloadIsPermanentAndNeverSentToProvider() {
        enqueueDeliveries(1);
        jdbc.update("""
                UPDATE platform.sms_delivery
                SET phone_ciphertext = decode('00', 'hex')
                """);
        AtomicInteger invocations = new AtomicInteger();
        SmsProvider provider = request -> {
            invocations.incrementAndGet();
            return SmsProvider.Result.success("request", "biz");
        };

        job(provider, 1, 3).runOnce();

        assertThat(invocations).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT status FROM platform.sms_delivery", String.class))
                .isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT sanitized_error FROM platform.sms_delivery", String.class))
                .isEqualTo("PERMANENT:INVALID_DELIVERY_PAYLOAD");
    }

    @Test
    void recoversAnExpiredLeaseWithAUniqueNewClaim() {
        enqueueDeliveries(1);
        UUID staleToken = UUID.randomUUID();
        jdbc.update("""
                UPDATE platform.sms_delivery
                SET status = 'SENDING', claim_token = ?, claimed_until = ?, attempt_count = 1
                """, staleToken, Timestamp.from(clock.instant().minusSeconds(1)));
        RecordingProvider provider = new RecordingProvider(
                SmsProvider.Result.success("request", "biz"));

        assertThat(job(provider, 1, 3).runOnce()).isEqualTo(1);

        assertThat(provider.requests).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT status FROM platform.sms_delivery", String.class))
                .isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM platform.sms_delivery", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void rejectsUnsafeDeliveryJobBounds() {
        RecordingProvider provider = new RecordingProvider(SmsProvider.Result.success("r", "b"));
        assertThatThrownBy(() -> job(provider, 0, 3)).hasMessageContaining("batch");
        assertThatThrownBy(() -> new SmsDeliveryJob(
                jdbc, transactions, phones, encryption, objectMapper, provider, clock,
                1, Duration.ZERO, Duration.ofSeconds(10), Duration.ofHours(1), 3,
                heartbeatScheduler))
                .hasMessageContaining("claim timeout");
    }

    @Test
    void linkedNotificationChannelTracksSentLifecycle() {
        enqueueDeliveries(1);
        UUID delivery = linkNotificationChannel();

        assertThat(job(new RecordingProvider(SmsProvider.Result.success("request", "biz")), 1, 3).runOnce()).isOne();

        assertThat(channel(delivery)).containsEntry("status", "SENT")
                .containsEntry("attempt_count", 1).containsEntry("last_error", null);
        assertThat(channel(delivery).get("delivered_at")).isNotNull();
    }

    @Test
    void linkedNotificationChannelTracksRetryableFailureLifecycle() {
        enqueueDeliveries(1);
        UUID delivery = linkNotificationChannel();

        assertThat(job(new RecordingProvider(SmsProvider.Result.transientFailure("THROTTLED")), 1, 3).runOnce()).isOne();

        assertThat(channel(delivery)).containsEntry("status", "FAILED")
                .containsEntry("attempt_count", 1)
                .containsEntry("last_error", "TRANSIENT:THROTTLED");
        assertThat(((Timestamp) channel(delivery).get("next_attempt_at")).toInstant()).isEqualTo(
                jdbc.queryForObject("SELECT next_attempt_at FROM platform.sms_delivery WHERE id=?", Instant.class, delivery));
    }

    @Test
    void linkedNotificationChannelTracksDeadLifecycle() {
        enqueueDeliveries(1);
        UUID delivery = linkNotificationChannel();

        assertThat(job(new RecordingProvider(SmsProvider.Result.permanentFailure("INVALID_PHONE")), 1, 3).runOnce()).isOne();

        assertThat(channel(delivery)).containsEntry("status", "DEAD")
                .containsEntry("attempt_count", 1)
                .containsEntry("last_error", "PERMANENT:INVALID_PHONE");
    }

    private SmsDeliveryJob job(SmsProvider provider, int batchSize, int maxAttempts) {
        return new SmsDeliveryJob(
                jdbc, transactions, phones, encryption, objectMapper, provider, clock,
                batchSize, Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofHours(1), maxAttempts, heartbeatScheduler);
    }

    private SmsDeliveryJob job(
            SmsProvider provider,
            int batchSize,
            int maxAttempts,
            Duration claimTimeout,
            Clock jobClock) {
        return new SmsDeliveryJob(
                jdbc, transactions, phones, encryption, objectMapper, provider, jobClock,
                batchSize, claimTimeout, Duration.ofSeconds(10),
                Duration.ofHours(1), maxAttempts, heartbeatScheduler);
    }

    private void enqueueDeliveries(int count) {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId, true);
        bindPhone(userId, "15012348963");
        for (int index = 0; index < count; index++) {
            notifications().enqueue(
                    tenantId, userId, "ORDER_PAYMENT_FAILED",
                    Map.of("payMoney", index + ".00"), "delivery:" + index);
        }
    }

    private UUID linkNotificationChannel() {
        Map<String, Object> delivery = jdbc.queryForMap(
                "SELECT id,tenant_id,user_id FROM platform.sms_delivery LIMIT 1");
        UUID deliveryId = (UUID) delivery.get("id");
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO platform.notification_event(id,event_key,category,event_type,tenant_id,user_id,aggregate_type,aggregate_id,payload,occurred_at) VALUES (?,?, 'BILLING','ORDER_PAYMENT_FAILED',?,?,'ORDER',?,'{\"payMoney\":\"1.00\"}'::jsonb,now())",
                eventId, "event:" + eventId, delivery.get("tenant_id"), delivery.get("user_id"), eventId.toString());
        jdbc.update("INSERT INTO platform.notification_channel_delivery(id,event_id,tenant_id,user_id,channel,status,sms_delivery_id) VALUES (?,?,?,?, 'SMS','PENDING',?)",
                UUID.randomUUID(), eventId, delivery.get("tenant_id"), delivery.get("user_id"), deliveryId);
        return deliveryId;
    }

    private Map<String, Object> channel(UUID deliveryId) {
        return jdbc.queryForMap("SELECT status,attempt_count,last_error,next_attempt_at,delivered_at FROM platform.notification_channel_delivery WHERE sms_delivery_id=?", deliveryId);
    }

    private static final class RecordingProvider implements SmsProvider {
        private final Result result;
        private final List<Request> requests = new ArrayList<>();

        private RecordingProvider(Result result) {
            this.result = result;
        }

        @Override
        public Result send(Request request) {
            requests.add(request);
            return result;
        }
    }
}
