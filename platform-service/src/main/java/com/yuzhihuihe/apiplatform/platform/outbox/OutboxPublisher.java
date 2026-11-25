package com.yuzhihuihe.apiplatform.platform.outbox;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationService;
import com.yuzhihuihe.apiplatform.platform.subscription.SubscriptionNotifications;
import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import com.yuzhihuihe.apiplatform.platform.refund.RefundOutboxCompletionService;
import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Service
public class OutboxPublisher {

    private static final String GRANT_EVENT = "PACKAGE_GRANTED_V1";
    private static final String REFUND_EVENT = "PACKAGE_REFUNDED_V1";
    private static final String GRANT_PATH = "/internal/events/package-granted";
    private static final String REFUND_PATH = "/internal/events/package-refunded";
    private static final Duration GRANT_QUEUED_RECHECK = Duration.ofMinutes(5);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<PlatformTransactionManager> transactionManagerProvider;
    private final RefundOutboxCompletionService refundCompletionService;
    private final CommercialTransactionMetrics metrics;
    private final RestClient gatewayClient;
    private final InternalRequestSigner requestSigner;
    private final Clock clock;
    private final Duration claimTimeout;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final int maxAttempts;
    private final CommercialNotificationService notifications;
    /**
     * 「周期真正开始」的订阅通知（设计 §9.1）。
     *
     * <p>挂在这里是因为**这里就是「实例真正激活」的唯一权威时点**：
     * 既有的 notifyGrantActive 也在这个分支里被调用，而订阅续期的实例正是
     * 在队列前移、被网关翻成 ACTIVE 的这一刻才生效的。
     */
    private final SubscriptionNotifications subscriptionNotifications;

    @Autowired
    public OutboxPublisher(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
            RefundOutboxCompletionService refundCompletionService,
            CommercialTransactionMetrics metrics,
            Clock clock,
            RestClient.Builder gatewayClientBuilder,
            @Value("${gateway.internal.base-url:http://localhost:8081}") String gatewayBaseUrl,
            @Value("${gateway.internal.connect-timeout:2s}") Duration connectTimeout,
            @Value("${gateway.internal.read-timeout:10s}") Duration readTimeout,
            @Value("${security.internal.hmac-secret:}") String hmacSecret,
            @Value("${outbox.publisher.claim-timeout:30s}") Duration claimTimeout,
            @Value("${outbox.publisher.initial-backoff:1s}") Duration initialBackoff,
            @Value("${outbox.publisher.max-backoff:1h}") Duration maxBackoff,
            @Value("${outbox.publisher.max-attempts:5}") int maxAttempts,
            ObjectProvider<CommercialNotificationService> notifications,
            ObjectProvider<SubscriptionNotifications> subscriptionNotifications) {
        this(jdbcTemplate, objectMapper, transactionManagerProvider, refundCompletionService,
                metrics, clock, gatewayClientBuilder, gatewayBaseUrl, connectTimeout, readTimeout,
                hmacSecret,
                claimTimeout, initialBackoff, maxBackoff, maxAttempts,
                notifications.getIfAvailable(), subscriptionNotifications.getIfAvailable());
    }

    public OutboxPublisher(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
            RefundOutboxCompletionService refundCompletionService,
            CommercialTransactionMetrics metrics,
            Clock clock,
            String gatewayBaseUrl,
            Duration connectTimeout,
            Duration readTimeout,
            String hmacSecret,
            Duration claimTimeout,
            Duration initialBackoff,
            Duration maxBackoff,
            int maxAttempts,
            CommercialNotificationService notifications,
            SubscriptionNotifications subscriptionNotifications) {
        this(jdbcTemplate, objectMapper, transactionManagerProvider, refundCompletionService,
                metrics, clock, RestClient.builder(), gatewayBaseUrl, connectTimeout, readTimeout,
                hmacSecret, claimTimeout, initialBackoff, maxBackoff, maxAttempts, notifications,
                subscriptionNotifications);
    }

    private OutboxPublisher(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
            RefundOutboxCompletionService refundCompletionService,
            CommercialTransactionMetrics metrics,
            Clock clock,
            RestClient.Builder gatewayClientBuilder,
            String gatewayBaseUrl,
            Duration connectTimeout,
            Duration readTimeout,
            String hmacSecret,
            Duration claimTimeout,
            Duration initialBackoff,
            Duration maxBackoff,
            int maxAttempts,
            CommercialNotificationService notifications,
            SubscriptionNotifications subscriptionNotifications) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactionManagerProvider = transactionManagerProvider;
        this.refundCompletionService = refundCompletionService;
        this.metrics = metrics;
        this.clock = clock;
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()
                || connectTimeout.plus(readTimeout).compareTo(claimTimeout) >= 0) {
            throw new IllegalArgumentException("outbox_http_timeouts_must_fit_claim_timeout");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.gatewayClient = gatewayClientBuilder
                .baseUrl(gatewayBaseUrl)
                .requestFactory(requestFactory)
                .build();
        this.requestSigner = new InternalRequestSigner(hmacSecret);
        this.claimTimeout = claimTimeout;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.maxAttempts = maxAttempts;
        this.notifications = notifications;
        this.subscriptionNotifications = subscriptionNotifications;
    }

    @Scheduled(
            fixedDelayString = "${outbox.publisher.delay-ms:1000}",
            initialDelayString = "${outbox.publisher.initial-delay-ms:1000}")
    public void publishPending() {
        TransactionTemplate transactionTemplate = transactionTemplate();
        ClaimedEvent event = transactionTemplate.execute(status -> claimOne());
        if (event == null) {
            return;
        }

        try {
            byte[] body = serialize(new EventEnvelope(
                    event.id(), event.eventType(), event.businessKey(), event.payload()));
            String eventPath = eventPath(event.eventType());
            InternalRequestSigner.SignedHeaders signed = requestSigner.sign(
                    clock.instant().getEpochSecond(), UUID.randomUUID(), "POST", eventPath, body);
            if (GRANT_EVENT.equals(event.eventType())) {
                ActivationResponse activationResponse = post(
                        eventPath, body, signed, ActivationResponse.class);
                String activationStatus = activationStatus(activationResponse);
                transactionTemplate.executeWithoutResult(
                        status -> markGrantDelivered(event, activationStatus));
            } else {
                RevocationResponse response = post(
                        eventPath, body, signed, RevocationResponse.class);
                RefundOutboxCompletionService.RevocationResult revocation =
                        revocationResult(response);
                transactionTemplate.executeWithoutResult(
                        status -> markRefundDelivered(event, revocation));
            }
        } catch (Exception exception) {
            transactionTemplate.executeWithoutResult(status -> markDeliveryFailure(event, exception));
        }
    }

    private <T> T post(
            String path,
            byte[] body,
            InternalRequestSigner.SignedHeaders signed,
            Class<T> responseType) {
        return gatewayClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Timestamp", signed.timestamp())
                .header("X-Internal-Request-Id", signed.requestId())
                .header("X-Internal-Content-Sha256", signed.contentSha256())
                .header("X-Internal-Signature", signed.signature())
                .body(body)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                    throw new IllegalStateException(
                            "gateway_delivery_http_status_" + response.getStatusCode().value());
                })
                .body(responseType);
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManagerProvider.getObject());
    }

    private ClaimedEvent claimOne() {
        UUID claimToken = UUID.randomUUID();
        List<ClaimedEvent> claimed = jdbcTemplate.query("""
                WITH candidate AS (
                    SELECT id
                    FROM platform.outbox_event
                    WHERE event_type IN ('PACKAGE_GRANTED_V1', 'PACKAGE_REFUNDED_V1')
                      AND status IN ('PENDING', 'DELIVERING')
                      AND next_attempt_at <= now()
                    ORDER BY created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE platform.outbox_event event
                SET status = 'DELIVERING',
                    claim_token = ?,
                    next_attempt_at = now() + (? * interval '1 millisecond'),
                    last_error = NULL
                FROM candidate
                WHERE event.id = candidate.id
                RETURNING event.id, event.aggregate_id, event.event_type,
                          event.business_key, event.payload::text, event.attempt_count,
                          event.claim_token, event.created_at
                """, (result, rowNumber) -> new ClaimedEvent(
                        result.getObject("id", UUID.class),
                        result.getObject("aggregate_id", UUID.class),
                        result.getString("event_type"),
                        result.getString("business_key"),
                        readPayload(result.getString("payload")),
                        result.getInt("attempt_count"),
                        result.getObject("claim_token", UUID.class),
                        result.getObject("created_at", OffsetDateTime.class).toInstant()),
                claimToken, claimTimeout.toMillis());
        if (claimed.isEmpty()) {
            return null;
        }

        return claimed.getFirst();
    }

    private void markGrantDelivered(ClaimedEvent event, String activationStatus) {
        if ("QUEUED".equals(activationStatus)) {
            jdbcTemplate.update("""
                    UPDATE platform.outbox_event
                    SET status = 'PENDING', claim_token = NULL,
                        attempt_count = 0,
                        next_attempt_at = now() + (? * interval '1 millisecond'),
                        last_error = NULL
                    WHERE id = ? AND status = 'DELIVERING' AND claim_token = ?
                    """, GRANT_QUEUED_RECHECK.toMillis(), event.id(), event.claimToken());
            return;
        }
        int updated = jdbcTemplate.update("""
                UPDATE platform.outbox_event
                SET status = 'DELIVERED', claim_token = NULL,
                    delivered_at = now(), last_error = NULL
                WHERE id = ? AND status = 'DELIVERING' AND claim_token = ?
                """, event.id(), event.claimToken());
        if (updated == 0) {
            return;
        }
        int grantUpdated = jdbcTemplate.update("""
                UPDATE platform.grant_request
                SET status = ?,
                    completed_at = CASE WHEN ? = 'ACTIVE' THEN now() ELSE NULL END,
                    failure_reason = NULL, updated_at = now()
                WHERE id = ? AND status = 'QUEUED'
                """, activationStatus, activationStatus, event.aggregateId());
        if (grantUpdated == 1 && "ACTIVE".equals(activationStatus) && notifications != null) {
            notifications.notifyGrantActive(event.aggregateId());
        }
        // 订阅的「周期真正开始」也挂在这一刻：续期实例此前是 QUEUED、expires_at 为 NULL，
        // 只有现在才算得出有效期（设计 §9.1）。这不是可选的——若挂在扣款成功那一刻，
        // 通知里只能写一个算不出来的日期。找不到对应账期说明这笔发放不是订阅产生的，
        // notifyPeriodStarted 会静默返回。
        //
        // 刻意**不传** clock.instant()：有效期不是「现在」，而是实例的 expires_at
        // （activated_at + duration_days，由网关侧激活事务写入）。早先传了调用方时钟，
        // 实测导致通知少报 30 天（见 SubscriptionNotifications.notifyPeriodStarted）。
        if (grantUpdated == 1 && "ACTIVE".equals(activationStatus)
                && subscriptionNotifications != null) {
            subscriptionNotifications.notifyPeriodStarted(event.aggregateId());
        }
    }

    private void markRefundDelivered(
            ClaimedEvent event,
            RefundOutboxCompletionService.RevocationResult response) {
        int updated = jdbcTemplate.update("""
                UPDATE platform.outbox_event
                SET status = 'DELIVERED', claim_token = NULL,
                    delivered_at = now(), last_error = NULL
                WHERE id = ? AND status = 'DELIVERING' AND claim_token = ?
                """, event.id(), event.claimToken());
        if (updated == 0) {
            return;
        }
        refundCompletionService.complete(event.aggregateId(), response);
        metrics.refundRevoke("DELIVERED");
        metrics.refundOutboxDuration(
                "DELIVERED", Duration.between(event.createdAt(), clock.instant()));
    }

    private void markDeliveryFailure(ClaimedEvent event, Exception exception) {
        String error = deliveryError(exception);
        int failureCount = event.attemptCount() + 1;
        if (failureCount >= maxAttempts) {
            int updated = jdbcTemplate.update("""
                    UPDATE platform.outbox_event
                    SET status = 'FAILED', claim_token = NULL,
                        attempt_count = ?, last_error = ?
                    WHERE id = ? AND status = 'DELIVERING' AND claim_token = ?
                    """, failureCount, error, event.id(), event.claimToken());
            if (updated > 0) {
                if (GRANT_EVENT.equals(event.eventType())) {
                    jdbcTemplate.update("""
                            UPDATE platform.grant_request
                            SET status = 'FAILED', failure_reason = ?, updated_at = now()
                            WHERE id = ? AND status = 'QUEUED'
                            """, error, event.aggregateId());
                } else {
                    recordRefundOutboxExhausted(event, error);
                    metrics.refundRevoke("FAILED");
                    metrics.refundOutboxDuration(
                            "FAILED", Duration.between(event.createdAt(), clock.instant()));
                }
            }
            return;
        }

        int updated = jdbcTemplate.update("""
                UPDATE platform.outbox_event
                SET status = 'PENDING', claim_token = NULL,
                    attempt_count = ?,
                    next_attempt_at = now() + (? * interval '1 millisecond'),
                    last_error = ?
                WHERE id = ? AND status = 'DELIVERING' AND claim_token = ?
                """, failureCount, retryDelay(failureCount).toMillis(), error,
                event.id(), event.claimToken());
        if (updated > 0 && GRANT_EVENT.equals(event.eventType())) {
            jdbcTemplate.update("""
                    UPDATE platform.grant_request
                    SET failure_reason = ?, updated_at = now()
                    WHERE id = ? AND status = 'QUEUED'
                    """, error, event.aggregateId());
        } else if (updated > 0) {
            metrics.refundRevoke("RETRY");
            metrics.refundOutboxDuration(
                    "RETRY", Duration.between(event.createdAt(), clock.instant()));
        }
    }

    private String activationStatus(ActivationResponse response) {
        if (response == null || !List.of("QUEUED", "ACTIVE").contains(response.status())) {
            throw new IllegalStateException("gateway_activation_status_invalid");
        }
        return response.status();
    }

    private RefundOutboxCompletionService.RevocationResult revocationResult(
            RevocationResponse response) {
        if (response == null) {
            throw new IllegalStateException("gateway_revocation_response_invalid");
        }
        return new RefundOutboxCompletionService.RevocationResult(
                response.packageInstanceId(), response.status(),
                response.activatedPackageInstanceId(), response.activatedPackageStatus());
    }

    private String eventPath(String eventType) {
        return switch (eventType) {
            case GRANT_EVENT -> GRANT_PATH;
            case REFUND_EVENT -> REFUND_PATH;
            default -> throw new IllegalStateException("unsupported_outbox_event_type");
        };
    }

    private void recordRefundOutboxExhausted(ClaimedEvent event, String error) {
        RefundAnomalyTarget target = jdbcTemplate.query("""
                        SELECT refund.id AS refund_id, refund.order_id, refund.provider,
                               refund.refund_no
                        FROM platform.refund AS refund
                        WHERE refund.id = ? AND refund.status = 'REVOKING'
                        FOR UPDATE
                        """, (result, rowNumber) -> new RefundAnomalyTarget(
                                result.getObject("refund_id", UUID.class),
                                result.getObject("order_id", UUID.class),
                                result.getString("provider"),
                                result.getString("refund_no")), event.aggregateId())
                .stream().findFirst().orElse(null);
        if (target == null) {
            return;
        }
        String fingerprint = sha256(
                "REFUND_OUTBOX_EXHAUSTED\n" + target.refundNo());
        jdbcTemplate.update("""
                INSERT INTO platform.payment_anomaly(
                    id, fingerprint, anomaly_type, provider, order_id,
                    refund_id, status, detail)
                VALUES (?, ?, 'REFUND_OUTBOX_EXHAUSTED', ?, ?, ?, 'OPEN',
                        jsonb_build_object('reason', 'MAXIMUM_ATTEMPTS', 'lastError', ?))
                ON CONFLICT (fingerprint) WHERE status IN ('OPEN', 'INVESTIGATING')
                DO NOTHING
                """, UUID.randomUUID(), fingerprint, target.provider(), target.orderId(),
                target.refundId(), error);
    }

    private Duration retryDelay(int attemptCount) {
        Duration delay = initialBackoff;
        for (int attempt = 1; attempt < attemptCount && delay.compareTo(maxBackoff) < 0; attempt++) {
            Duration doubled;
            try {
                doubled = delay.multipliedBy(2);
            } catch (ArithmeticException exception) {
                return maxBackoff;
            }
            delay = doubled.compareTo(maxBackoff) > 0 ? maxBackoff : doubled;
        }
        return delay;
    }

    private String deliveryError(Exception exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return message.length() <= 2_000 ? message : message.substring(0, 2_000);
    }

    private JsonNode readPayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("outbox_payload_deserialization_failed", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("refund_anomaly_hash_failed", exception);
        }
    }

    private byte[] serialize(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsBytes(envelope);
        } catch (Exception exception) {
            throw new IllegalStateException("outbox_payload_serialization_failed", exception);
        }
    }

    private record ClaimedEvent(
            UUID id,
            UUID aggregateId,
            String eventType,
            String businessKey,
            JsonNode payload,
            int attemptCount,
            UUID claimToken,
            Instant createdAt) {
    }

    private record ActivationResponse(UUID packageInstanceId, String status) {
    }

    private record RevocationResponse(
            UUID packageInstanceId,
            String status,
            UUID activatedPackageInstanceId,
            String activatedPackageStatus) {
    }

    private record RefundAnomalyTarget(
            UUID refundId,
            UUID orderId,
            String provider,
            String refundNo) {
    }

    private record EventEnvelope(
            UUID eventId,
            String eventType,
            String businessKey,
            JsonNode payload) {
    }
}
