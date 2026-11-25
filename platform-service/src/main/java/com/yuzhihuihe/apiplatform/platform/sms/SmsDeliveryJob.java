package com.yuzhihuihe.apiplatform.platform.sms;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.security.SecretEncryptionService;

@Component
@ConditionalOnProperty(prefix = "sms", name = "enabled", havingValue = "true")
public class SmsDeliveryJob implements AutoCloseable {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PhoneProtectionService phones;
    private final SecretEncryptionService encryption;
    private final ObjectMapper objectMapper;
    private final SmsProvider provider;
    private final Clock clock;
    private final int batchSize;
    private final Duration claimTimeout;
    private final Duration renewalHorizon;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final int maxAttempts;
    private final ScheduledExecutorService heartbeatScheduler;
    private final boolean ownsHeartbeatScheduler;

    @Autowired
    public SmsDeliveryJob(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            PhoneProtectionService phones,
            SecretEncryptionService encryption,
            ObjectMapper objectMapper,
            SmsProvider provider,
            Clock clock,
            @Value("${sms.jobs.batch-size:50}") int batchSize,
            @Value("${sms.jobs.claim-timeout:30s}") Duration claimTimeout,
            @Value("${sms.jobs.initial-backoff:10s}") Duration initialBackoff,
            @Value("${sms.jobs.max-backoff:1h}") Duration maxBackoff,
            @Value("${sms.jobs.max-attempts:8}") int maxAttempts) {
        this(jdbc, transactionManager, phones, encryption, objectMapper, provider, clock,
                batchSize, claimTimeout, initialBackoff, maxBackoff, maxAttempts,
                null, true);
    }

    SmsDeliveryJob(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            PhoneProtectionService phones,
            SecretEncryptionService encryption,
            ObjectMapper objectMapper,
            SmsProvider provider,
            Clock clock,
            int batchSize,
            Duration claimTimeout,
            Duration initialBackoff,
            Duration maxBackoff,
            int maxAttempts,
            ScheduledExecutorService heartbeatScheduler) {
        this(jdbc, transactionManager, phones, encryption, objectMapper, provider, clock,
                batchSize, claimTimeout, initialBackoff, maxBackoff, maxAttempts,
                heartbeatScheduler, false);
    }

    private SmsDeliveryJob(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            PhoneProtectionService phones,
            SecretEncryptionService encryption,
            ObjectMapper objectMapper,
            SmsProvider provider,
            Clock clock,
            int batchSize,
            Duration claimTimeout,
            Duration initialBackoff,
            Duration maxBackoff,
            int maxAttempts,
            ScheduledExecutorService heartbeatScheduler,
            boolean ownsHeartbeatScheduler) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                java.util.Objects.requireNonNull(transactionManager, "transactionManager"));
        this.phones = java.util.Objects.requireNonNull(phones, "phones");
        this.encryption = java.util.Objects.requireNonNull(encryption, "encryption");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
        this.provider = java.util.Objects.requireNonNull(provider, "provider");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.batchSize = bounded(batchSize, 1, 100, "sms delivery batch size");
        this.claimTimeout = positive(claimTimeout, "sms delivery claim timeout");
        this.renewalHorizon = doubled(claimTimeout);
        this.initialBackoff = positive(initialBackoff, "sms delivery initial backoff");
        this.maxBackoff = positive(maxBackoff, "sms delivery maximum backoff");
        this.maxAttempts = bounded(maxAttempts, 1, 100, "sms delivery maximum attempts");
        if (initialBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("sms delivery backoff range is invalid");
        }
        if (heartbeatScheduler == null) {
            this.heartbeatScheduler = newHeartbeatScheduler();
            this.ownsHeartbeatScheduler = true;
        } else {
            this.heartbeatScheduler = heartbeatScheduler;
            this.ownsHeartbeatScheduler = ownsHeartbeatScheduler;
        }
    }

    @Scheduled(
            fixedDelayString = "${sms.jobs.delivery-delay-ms:5000}",
            initialDelayString = "${sms.jobs.delivery-delay-ms:5000}")
    public void scheduledRun() {
        runOnce();
    }

    public int runOnce() {
        transactions.executeWithoutResult(ignored -> expireExhaustedLeases());
        int processed = 0;
        while (processed < batchSize) {
            DeliveryClaim claim = transactions.execute(ignored -> claimOne());
            if (claim == null) {
                break;
            }
            SmsProvider.Result result = sendWithHeartbeat(claim);
            transactions.executeWithoutResult(ignored -> finish(claim, result));
            processed++;
        }
        return processed;
    }

    private void expireExhaustedLeases() {
        Instant now = clock.instant();
        jdbc.update("""
                UPDATE platform.sms_delivery
                SET status = 'DEAD', claim_token = NULL, claimed_until = NULL,
                    sanitized_error = 'TRANSIENT:LEASE_EXHAUSTED', updated_at = ?
                WHERE status = 'SENDING' AND claimed_until <= ? AND attempt_count >= ?
                """, Timestamp.from(now), Timestamp.from(now), maxAttempts);
        jdbc.update("""
                UPDATE platform.notification_channel_delivery channel
                SET status='DEAD', attempt_count=delivery.attempt_count,
                    claim_token=NULL, claimed_until=NULL,
                    last_error=delivery.sanitized_error, updated_at=?
                FROM platform.sms_delivery delivery
                WHERE channel.sms_delivery_id=delivery.id AND delivery.status='DEAD'
                  AND channel.channel='SMS' AND channel.status <> 'SENT'
                """, Timestamp.from(now));
    }

    private DeliveryClaim claimOne() {
        Instant now = clock.instant();
        UUID token = UUID.randomUUID();
        List<DeliveryClaim> claims = jdbc.query("""
                WITH candidate AS (
                    SELECT id FROM platform.sms_delivery
                    WHERE attempt_count < ? AND (
                        (status IN ('PENDING', 'FAILED') AND next_attempt_at <= ?)
                        OR (status = 'SENDING' AND claimed_until <= ?)
                    )
                    ORDER BY next_attempt_at, created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE platform.sms_delivery AS delivery
                SET status = 'SENDING', attempt_count = attempt_count + 1,
                    claim_token = ?, claimed_until = ?, updated_at = ?
                FROM candidate
                WHERE delivery.id = candidate.id
                RETURNING delivery.id, delivery.template_key, delivery.template_code,
                          delivery.phone_ciphertext, delivery.variables::text,
                          delivery.sensitive_variables_ciphertext, delivery.attempt_count
                """, (result, rowNumber) -> new DeliveryClaim(
                        result.getObject("id", UUID.class),
                        token,
                        result.getString("template_key"),
                        result.getString("template_code"),
                        result.getBytes("phone_ciphertext"),
                        result.getString("variables"),
                        result.getBytes("sensitive_variables_ciphertext"),
                        result.getInt("attempt_count")),
                maxAttempts, Timestamp.from(now), Timestamp.from(now),
                token, Timestamp.from(now.plus(claimTimeout)), Timestamp.from(now));
        if (!claims.isEmpty()) {
            DeliveryClaim claim = claims.getFirst();
            jdbc.update("""
                    UPDATE platform.notification_channel_delivery
                    SET status='PROCESSING', attempt_count=?, claim_token=?, claimed_until=?,
                        last_error=NULL, updated_at=?
                    WHERE sms_delivery_id=? AND channel='SMS' AND status <> 'SENT'
                    """, claim.attempt(), claim.token(), Timestamp.from(now.plus(claimTimeout)),
                    Timestamp.from(now), claim.id());
        }
        return claims.isEmpty() ? null : claims.getFirst();
    }

    private SmsProvider.Result sendWithHeartbeat(DeliveryClaim claim) {
        if (!renewLease(claim)) {
            return SmsProvider.Result.transientFailure("LEASE_LOST");
        }
        long periodMillis = Math.max(1L, claimTimeout.toMillis() / 3L);
        ScheduledFuture<?> heartbeat;
        try {
            heartbeat = heartbeatScheduler.scheduleWithFixedDelay(
                    () -> renewLease(claim), periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException schedulerUnavailable) {
            return SmsProvider.Result.transientFailure("LEASE_HEARTBEAT_UNAVAILABLE");
        }
        try {
            return send(claim);
        } finally {
            heartbeat.cancel(false);
        }
    }

    private boolean renewLease(DeliveryClaim claim) {
        try {
            Instant now = clock.instant();
            Timestamp claimedUntil = Timestamp.from(now.plus(renewalHorizon));
            boolean renewed = jdbc.update("""
                    UPDATE platform.sms_delivery
                    SET claimed_until = ?, updated_at = ?
                    WHERE id = ? AND claim_token = ? AND status = 'SENDING'
                    """, claimedUntil, Timestamp.from(now), claim.id(), claim.token()) == 1;
            if (renewed) {
                jdbc.update("""
                        UPDATE platform.notification_channel_delivery
                        SET claimed_until=?, updated_at=?
                        WHERE sms_delivery_id=? AND channel='SMS'
                          AND status='PROCESSING' AND claim_token=?
                        """, claimedUntil, Timestamp.from(now), claim.id(), claim.token());
            }
            return renewed;
        } catch (RuntimeException ignored) {
            // 临时性的续租失败会在下一个心跳周期重试。
            return false;
        }
    }

    private SmsProvider.Result send(DeliveryClaim delivery) {
        SmsProvider.Request request;
        try {
            String phone = phones.decrypt(delivery.phoneCiphertext());
            Map<String, String> variables = new LinkedHashMap<>();
            variables.putAll(readVariables(delivery.variablesJson().getBytes(StandardCharsets.UTF_8)));
            if (delivery.sensitiveVariablesCiphertext() != null) {
                variables.putAll(readVariables(
                        encryption.decrypt(delivery.sensitiveVariablesCiphertext())));
            }
            request = new SmsProvider.Request(phone, delivery.templateCode(), variables);
        } catch (RuntimeException invalidPayload) {
            return SmsProvider.Result.permanentFailure("INVALID_DELIVERY_PAYLOAD");
        }
        try {
            SmsProvider.Result result = provider.send(request);
            return result == null
                    ? SmsProvider.Result.permanentFailure("UNCLASSIFIED_PROVIDER_ERROR") : result;
        } catch (RuntimeException providerFailure) {
            return SmsProvider.Result.transientFailure("PROVIDER_NETWORK_ERROR");
        }
    }

    private void finish(DeliveryClaim delivery, SmsProvider.Result result) {
        if (result.success()) {
            int updated = jdbc.update("""
                    UPDATE platform.sms_delivery
                    SET status = 'SENT', provider_request_id = ?, provider_biz_id = ?,
                        sanitized_error = NULL, sent_at = ?, claim_token = NULL,
                        claimed_until = NULL, updated_at = ?
                    WHERE id = ? AND claim_token = ? AND status = 'SENDING'
                    """, result.requestId(), result.bizId(), Timestamp.from(clock.instant()),
                    Timestamp.from(clock.instant()), delivery.id(), delivery.token());
            if (updated == 1) {
                jdbc.update("""
                        UPDATE platform.notification_channel_delivery
                        SET status='SENT', delivered_at=?, attempt_count=?,
                            claim_token=NULL, claimed_until=NULL, last_error=NULL, updated_at=?
                        WHERE sms_delivery_id=? AND channel='SMS'
                        """, Timestamp.from(clock.instant()), delivery.attempt(),
                        Timestamp.from(clock.instant()), delivery.id());
            }
            return;
        }
        String sanitizedError = result.resultClass().name() + ":" + result.errorCode();
        if (result.retryable() && delivery.attempt() < maxAttempts) {
            Instant now = clock.instant();
            Instant nextAttemptAt = now.plus(backoff(delivery.attempt()));
            int updated = jdbc.update("""
                    UPDATE platform.sms_delivery
                    SET status = 'FAILED', sanitized_error = ?, next_attempt_at = ?,
                        claim_token = NULL, claimed_until = NULL, updated_at = ?
                    WHERE id = ? AND claim_token = ? AND status = 'SENDING'
                    """, sanitizedError, Timestamp.from(nextAttemptAt),
                    Timestamp.from(now), delivery.id(), delivery.token());
            if (updated == 1) {
                jdbc.update("""
                        UPDATE platform.notification_channel_delivery
                        SET status='FAILED', attempt_count=?, next_attempt_at=?,
                            claim_token=NULL, claimed_until=NULL, last_error=?, updated_at=?
                        WHERE sms_delivery_id=? AND channel='SMS'
                        """, delivery.attempt(), Timestamp.from(nextAttemptAt), sanitizedError,
                        Timestamp.from(now), delivery.id());
            }
            return;
        }
        int updated = jdbc.update("""
                UPDATE platform.sms_delivery
                SET status = 'DEAD', sanitized_error = ?, claim_token = NULL,
                    claimed_until = NULL, updated_at = ?
                WHERE id = ? AND claim_token = ? AND status = 'SENDING'
                """, sanitizedError, Timestamp.from(clock.instant()), delivery.id(), delivery.token());
        if (updated == 1) {
            jdbc.update("""
                    UPDATE platform.notification_channel_delivery
                    SET status='DEAD', attempt_count=?, claim_token=NULL, claimed_until=NULL,
                        last_error=?, updated_at=?
                    WHERE sms_delivery_id=? AND channel='SMS'
                    """, delivery.attempt(), sanitizedError, Timestamp.from(clock.instant()), delivery.id());
        }
    }

    private Map<String, String> readVariables(byte[] json) {
        try {
            Map<String, Object> values = objectMapper.readValue(
                    json, new TypeReference<Map<String, Object>>() { });
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> result.put(key, String.valueOf(value)));
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("sms_delivery_payload_invalid");
        }
    }

    private Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(30, Math.max(0, attempt - 1));
        try {
            Duration value = initialBackoff.multipliedBy(multiplier);
            return value.compareTo(maxBackoff) > 0 ? maxBackoff : value;
        } catch (ArithmeticException overflow) {
            return maxBackoff;
        }
    }

    private int bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " is out of range");
        }
        return value;
    }

    private Duration positive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private Duration doubled(Duration value) {
        try {
            return value.multipliedBy(2);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("sms delivery claim timeout is too large", overflow);
        }
    }

    private static ScheduledExecutorService newHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sms-delivery-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    @PreDestroy
    public void close() {
        if (ownsHeartbeatScheduler) {
            heartbeatScheduler.shutdownNow();
        }
    }

    private record DeliveryClaim(
            UUID id,
            UUID token,
            String templateKey,
            String templateCode,
            byte[] phoneCiphertext,
            String variablesJson,
            byte[] sensitiveVariablesCiphertext,
            int attempt) {
    }
}
