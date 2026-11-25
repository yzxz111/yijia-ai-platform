package com.yuzhihuihe.apiplatform.gateway.safety;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "content-safety.outbox.enabled", havingValue = "true", matchIfMissing = true)
final class ContentSafetyEventOutboxJob {

    private final DatabaseClient database;
    private final ObjectMapper objectMapper;
    private final DeliveryClient deliveryClient;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease;
    private final Duration initialBackoff;
    private final Duration maximumBackoff;
    private final int maximumAttempts;
    private final Duration deliveryTimeout;

    @Autowired
    ContentSafetyEventOutboxJob(
            DatabaseClient database,
            ObjectMapper objectMapper,
            ContentSafetyEventPublisher publisher,
            @Value("${content-safety.outbox.batch-size:100}") int batchSize,
            @Value("${content-safety.outbox.lease:5m}") Duration lease,
            @Value("${content-safety.outbox.initial-backoff:1m}") Duration initialBackoff,
            @Value("${content-safety.outbox.maximum-backoff:1h}") Duration maximumBackoff,
            @Value("${content-safety.outbox.maximum-attempts:10}") int maximumAttempts,
            @Value("${content-safety.outbox.delivery-timeout:10s}") Duration deliveryTimeout) {
        this(database, objectMapper, publisher::publish, Clock.systemUTC(), batchSize, lease,
                initialBackoff, maximumBackoff, maximumAttempts, deliveryTimeout);
    }

    ContentSafetyEventOutboxJob(
            DatabaseClient database, ObjectMapper objectMapper, DeliveryClient deliveryClient,
            Clock clock, int batchSize, Duration lease, Duration initialBackoff,
            Duration maximumBackoff, int maximumAttempts, Duration deliveryTimeout) {
        this.database = Objects.requireNonNull(database, "database");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.deliveryClient = Objects.requireNonNull(deliveryClient, "deliveryClient");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (batchSize <= 0 || batchSize > 1000 || !positive(lease) || !positive(initialBackoff)
                || !positive(maximumBackoff) || initialBackoff.compareTo(maximumBackoff) > 0
                || maximumAttempts <= 0 || !positive(deliveryTimeout)
                || deliveryTimeout.compareTo(lease) >= 0) {
            throw new IllegalArgumentException("invalid_content_safety_outbox_configuration");
        }
        this.batchSize = batchSize;
        this.lease = lease;
        this.initialBackoff = initialBackoff;
        this.maximumBackoff = maximumBackoff;
        this.maximumAttempts = maximumAttempts;
        this.deliveryTimeout = deliveryTimeout;
    }

    @Scheduled(
            initialDelayString = "${content-safety.outbox.initial-delay:10s}",
            fixedDelayString = "${content-safety.outbox.fixed-delay:5s}")
    Mono<Integer> scheduledRun() {
        return Mono.defer(this::runOnce);
    }

    Mono<Integer> runOnce() {
        return claim(batchSize).concatMap(claim -> Mono.fromCallable(
                        () -> objectMapper.readValue(claim.payload(), SafetyEventPayload.class))
                .flatMap(event -> deliveryClient.deliver(event).timeout(deliveryTimeout))
                .then(markDelivered(claim.id(), claim.claimToken()))
                .thenReturn(1)
                .onErrorResume(error -> markFailed(claim, stableError(error)).thenReturn(0)))
                .reduce(0, Integer::sum);
    }

    Mono<Claim> claimOne() {
        return claim(1).next();
    }

    private Flux<Claim> claim(int limit) {
        Instant now = clock.instant();
        return database.sql("""
                        WITH candidates AS (
                            SELECT id
                            FROM gateway.content_safety_event_outbox
                            WHERE ((status IN ('PENDING', 'FAILED')
                                      AND attempt_count < :maximum_attempts
                                      AND next_attempt_at <= :now)
                                OR (status = 'DELIVERING' AND claimed_until <= :now))
                            ORDER BY next_attempt_at, created_at, id
                            FOR UPDATE SKIP LOCKED
                            LIMIT :batch_size
                        )
                        UPDATE gateway.content_safety_event_outbox outbox
                        SET status=CASE
                                WHEN outbox.status='DELIVERING'
                                  AND outbox.attempt_count >= :maximum_attempts THEN 'FAILED'
                                ELSE 'DELIVERING'
                            END,
                            attempt_count=CASE
                                WHEN outbox.status='DELIVERING'
                                  AND outbox.attempt_count >= :maximum_attempts
                                    THEN outbox.attempt_count
                                ELSE outbox.attempt_count + 1
                            END,
                            claim_token=CASE
                                WHEN outbox.status='DELIVERING'
                                  AND outbox.attempt_count >= :maximum_attempts THEN NULL
                                ELSE gen_random_uuid()
                            END,
                            claimed_until=CASE
                                WHEN outbox.status='DELIVERING'
                                  AND outbox.attempt_count >= :maximum_attempts THEN NULL
                                ELSE :claimed_until
                            END,
                            last_error=CASE
                                WHEN outbox.status='DELIVERING'
                                  AND outbox.attempt_count >= :maximum_attempts
                                    THEN 'maximum_attempts_exhausted'
                                ELSE outbox.last_error
                            END,
                            updated_at=:now
                        FROM candidates
                        WHERE outbox.id=candidates.id
                        RETURNING outbox.id, outbox.payload::text AS payload,
                                  outbox.attempt_count, outbox.claim_token
                        """)
                .bind("maximum_attempts", maximumAttempts)
                .bind("now", now)
                .bind("claimed_until", now.plus(lease))
                .bind("batch_size", limit)
                .map((row, metadata) -> new Claim(
                        row.get("id", UUID.class), row.get("payload", String.class),
                        required(row.get("attempt_count", Integer.class)),
                        row.get("claim_token", UUID.class)))
                .all()
                .filter(claim -> claim.claimToken() != null);
    }

    Mono<Void> markDelivered(UUID id, UUID token) {
        Instant now = clock.instant();
        return database.sql("""
                        UPDATE gateway.content_safety_event_outbox
                        SET status='DELIVERED', claim_token=NULL, claimed_until=NULL,
                            delivered_at=:now, last_error=NULL, updated_at=:now
                        WHERE id=:id AND status='DELIVERING' AND claim_token=:claim_token
                        """)
                .bind("now", now)
                .bind("id", id)
                .bind("claim_token", token)
                .fetch().rowsUpdated().then();
    }

    private Mono<Void> markFailed(Claim claim, String error) {
        return database.sql("""
                        UPDATE gateway.content_safety_event_outbox
                        SET status='FAILED', claim_token=NULL, claimed_until=NULL,
                            next_attempt_at=:next_attempt_at, last_error=:last_error, updated_at=:now
                        WHERE id=:id AND status='DELIVERING' AND claim_token=:claim_token
                        """)
                .bind("next_attempt_at", clock.instant().plus(retryDelay(claim.attemptCount())))
                .bind("last_error", error)
                .bind("now", clock.instant())
                .bind("id", claim.id())
                .bind("claim_token", claim.claimToken())
                .fetch().rowsUpdated().then();
    }

    private Duration retryDelay(int attempts) {
        Duration delay = initialBackoff;
        for (int i = 1; i < attempts && delay.compareTo(maximumBackoff) < 0; i++) {
            try {
                delay = delay.multipliedBy(2);
            } catch (ArithmeticException error) {
                return maximumBackoff;
            }
        }
        return delay.compareTo(maximumBackoff) > 0 ? maximumBackoff : delay;
    }

    private static String stableError(Throwable error) {
        Throwable current = reactor.core.Exceptions.unwrap(error);
        while (current != null) {
            if (current instanceof JsonProcessingException) {
                return "invalid_content_safety_payload";
            }
            current = current.getCause();
        }
        return "delivery_failed";
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

    private static int required(Integer value) {
        if (value == null) {
            throw new IllegalStateException("content_safety_attempt_count_missing");
        }
        return value;
    }

    @FunctionalInterface
    interface DeliveryClient {
        Mono<Void> deliver(SafetyEventPayload event);
    }

    record Claim(UUID id, String payload, int attemptCount, UUID claimToken) {
    }
}
