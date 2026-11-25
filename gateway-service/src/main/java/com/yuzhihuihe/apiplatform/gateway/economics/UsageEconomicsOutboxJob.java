package com.yuzhihuihe.apiplatform.gateway.economics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "gateway.economics.outbox.enabled", havingValue = "true")
public final class UsageEconomicsOutboxJob {

    static final String PATH = "/internal/platform/operating-facts/usage-events";

    private final DatabaseClient database;
    private final UsageEconomicsOutboxService outboxService;
    private final DeliveryClient client;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease;
    private final Duration initialBackoff;
    private final Duration maximumBackoff;
    private final int maximumAttempts;

    @Autowired
    public UsageEconomicsOutboxJob(
            DatabaseClient database,
            UsageEconomicsOutboxService outboxService,
            @Value("${gateway.economics.outbox.platform-url:http://platform-service:8080}") URI platformUrl,
            @Value("${security.internal.hmac-secret:}") String secret,
            @Value("${gateway.economics.outbox.timeout:3s}") Duration timeout,
            @Value("${gateway.economics.outbox.batch-size:100}") int batchSize,
            @Value("${gateway.economics.outbox.lease:5m}") Duration lease,
            @Value("${gateway.economics.outbox.initial-backoff:1m}") Duration initialBackoff,
            @Value("${gateway.economics.outbox.maximum-backoff:1h}") Duration maximumBackoff,
            @Value("${gateway.economics.outbox.maximum-attempts:10}") int maximumAttempts) {
        this(database, outboxService,
                new HmacDeliveryClient(platformUrl, secret, timeout, Clock.systemUTC()),
                Clock.systemUTC(), batchSize, lease, initialBackoff, maximumBackoff, maximumAttempts);
    }

    UsageEconomicsOutboxJob(
            DatabaseClient database, DeliveryClient client, Clock clock, int batchSize,
            Duration lease, Duration initialBackoff, Duration maximumBackoff, int maximumAttempts) {
        this(database, null, client, clock, batchSize, lease,
                initialBackoff, maximumBackoff, maximumAttempts);
    }

    private UsageEconomicsOutboxJob(
            DatabaseClient database, UsageEconomicsOutboxService outboxService,
            DeliveryClient client, Clock clock, int batchSize,
            Duration lease, Duration initialBackoff, Duration maximumBackoff, int maximumAttempts) {
        this.database = Objects.requireNonNull(database, "database");
        this.outboxService = outboxService;
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (batchSize <= 0 || batchSize > 1000 || !positive(lease) || !positive(initialBackoff)
                || !positive(maximumBackoff) || initialBackoff.compareTo(maximumBackoff) > 0
                || maximumAttempts <= 0) {
            throw new IllegalArgumentException("invalid_usage_economics_outbox_configuration");
        }
        this.batchSize = batchSize;
        this.lease = lease;
        this.initialBackoff = initialBackoff;
        this.maximumBackoff = maximumBackoff;
        this.maximumAttempts = maximumAttempts;
    }

    @Scheduled(
            initialDelayString = "${gateway.economics.outbox.initial-delay:30s}",
            fixedDelayString = "${gateway.economics.outbox.fixed-delay:10s}")
    public Mono<Integer> scheduledRun() {
        return Mono.defer(this::runOnce);
    }

    public Mono<Integer> runOnce() {
        Mono<Integer> recovery = outboxService == null
                ? Mono.just(0) : outboxService.enqueueRecoveredSettledUsage(batchSize);
        return recovery.thenMany(claim(batchSize)).concatMap(claim -> client.deliver(claim.payload())
                        .flatMap(status -> complete(claim, status))
                        .onErrorResume(error -> markFailed(claim, deliveryError(error))))
                .reduce(0, Integer::sum);
    }

    Mono<Claim> claimOne() {
        return claim(1).next();
    }

    Flux<Claim> claim(int limit) {
        Instant now = clock.instant();
        return database.sql("""
                        WITH candidates AS (
                            SELECT event_id FROM gateway.usage_economics_outbox
                            WHERE ((status IN ('PENDING','FAILED')
                                      AND attempt_count < :maximum_attempts
                                      AND next_attempt_at <= :now)
                                OR (status = 'SENDING' AND claimed_until <= :now))
                            ORDER BY next_attempt_at, created_at, event_id
                            FOR UPDATE SKIP LOCKED LIMIT :batch_size
                        )
                        UPDATE gateway.usage_economics_outbox outbox
                        SET status=CASE
                                WHEN outbox.status='SENDING'
                                  AND outbox.attempt_count >= :maximum_attempts THEN 'DEAD'
                                ELSE 'SENDING'
                            END,
                            attempt_count=CASE
                                WHEN outbox.status='SENDING'
                                  AND outbox.attempt_count >= :maximum_attempts
                                    THEN outbox.attempt_count
                                ELSE outbox.attempt_count + 1
                            END,
                            claim_token=CASE
                                WHEN outbox.status='SENDING'
                                  AND outbox.attempt_count >= :maximum_attempts THEN NULL
                                ELSE gen_random_uuid()
                            END,
                            claimed_until=CASE
                                WHEN outbox.status='SENDING'
                                  AND outbox.attempt_count >= :maximum_attempts THEN NULL
                                ELSE :claimed_until
                            END,
                            last_error=CASE
                                WHEN outbox.status='SENDING'
                                  AND outbox.attempt_count >= :maximum_attempts
                                    THEN 'maximum_attempts_exhausted'
                                ELSE outbox.last_error
                            END,
                            updated_at=:now
                        FROM candidates WHERE outbox.event_id=candidates.event_id
                        RETURNING outbox.event_id, outbox.payload::text AS payload,
                                  outbox.attempt_count, outbox.claim_token
                        """)
                .bind("maximum_attempts", maximumAttempts).bind("now", now)
                .bind("claimed_until", now.plus(lease)).bind("batch_size", limit)
                .map((row, metadata) -> new Claim(row.get("event_id", UUID.class),
                        row.get("payload", String.class), required(row.get("attempt_count", Integer.class)),
                        row.get("claim_token", UUID.class)))
                .all()
                .filter(claim -> claim.claimToken() != null);
    }

    Mono<Void> markSent(UUID eventId, UUID token) {
        return database.sql("""
                        UPDATE gateway.usage_economics_outbox
                        SET status='SENT', claim_token=NULL, claimed_until=NULL,
                            sent_at=:now, last_error=NULL, updated_at=:now
                        WHERE event_id=:event_id AND status='SENDING' AND claim_token=:claim_token
                        """).bind("now", clock.instant()).bind("event_id", eventId)
                .bind("claim_token", token).fetch().rowsUpdated().then();
    }

    private Mono<Integer> complete(Claim claim, int status) {
        if (status >= 200 && status < 300) {
            return markSent(claim.eventId(), claim.claimToken()).thenReturn(1);
        }
        if (status == 408 || status == 429) {
            return markFailed(claim, "http_retryable_4xx");
        }
        if (status >= 400 && status < 500) {
            return markDead(claim, "http_4xx").thenReturn(0);
        }
        return markFailed(claim, status >= 500 ? "http_5xx" : "unexpected_http_status");
    }

    private Mono<Integer> markFailed(Claim claim, String error) {
        if (claim.attemptCount() >= maximumAttempts) {
            return markDead(claim, error).thenReturn(0);
        }
        return database.sql("""
                        UPDATE gateway.usage_economics_outbox
                        SET status='FAILED', claim_token=NULL, claimed_until=NULL,
                            next_attempt_at=:next_attempt_at, last_error=:last_error, updated_at=:now
                        WHERE event_id=:event_id AND status='SENDING' AND claim_token=:claim_token
                        """).bind("next_attempt_at", clock.instant().plus(retryDelay(claim.attemptCount())))
                .bind("last_error", bounded(error)).bind("now", clock.instant())
                .bind("event_id", claim.eventId()).bind("claim_token", claim.claimToken())
                .fetch().rowsUpdated().thenReturn(0);
    }

    private Mono<Void> markDead(Claim claim, String error) {
        return database.sql("""
                        UPDATE gateway.usage_economics_outbox
                        SET status='DEAD', claim_token=NULL, claimed_until=NULL,
                            last_error=:last_error, updated_at=:now
                        WHERE event_id=:event_id AND status='SENDING' AND claim_token=:claim_token
                        """).bind("last_error", bounded(error)).bind("now", clock.instant())
                .bind("event_id", claim.eventId()).bind("claim_token", claim.claimToken())
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

    private static String deliveryError(Throwable error) {
        Throwable cause = reactor.core.Exceptions.unwrap(error);
        return cause instanceof java.util.concurrent.TimeoutException ? "delivery_timeout" : "delivery_failed";
    }

    private static String bounded(String error) {
        return error == null ? "delivery_failed" : error.substring(0, Math.min(error.length(), 1000));
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

    private static int required(Integer value) {
        if (value == null) throw new IllegalStateException("usage_economics_attempt_count_missing");
        return value;
    }

    @FunctionalInterface
    interface DeliveryClient {
        Mono<Integer> deliver(String payload);
    }

    record Claim(UUID eventId, String payload, int attemptCount, UUID claimToken) {
    }

    private static final class HmacDeliveryClient implements DeliveryClient {
        private static final HexFormat HEX = HexFormat.of();
        private final URI baseUri;
        private final byte[] secret;
        private final Duration timeout;
        private final Clock clock;
        private final HttpClient http;

        private HmacDeliveryClient(URI baseUri, String secret, Duration timeout, Clock clock) {
            this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
            this.secret = Objects.requireNonNull(secret, "secret").getBytes(StandardCharsets.UTF_8);
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            this.clock = clock;
            this.http = HttpClient.newBuilder().connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        }

        @Override
        public Mono<Integer> deliver(String payload) {
            if (secret.length == 0) return Mono.error(new IllegalStateException("internal_hmac_unavailable"));
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            UUID requestId = UUID.randomUUID();
            long timestamp = clock.instant().getEpochSecond();
            String bodyHash = UsageEconomicsOutboxService.sha256(body);
            String canonical = timestamp + "\n" + requestId + "\nPOST\n" + PATH + "\n" + bodyHash;
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(PATH)).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Timestamp", Long.toString(timestamp))
                    .header("X-Internal-Request-Id", requestId.toString())
                    .header("X-Internal-Content-Sha256", bodyHash)
                    .header("X-Internal-Signature", hmac(canonical)).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            return Mono.fromFuture(http.sendAsync(request, HttpResponse.BodyHandlers.discarding()))
                    .timeout(timeout).map(HttpResponse::statusCode);
        }

        private String hmac(String canonical) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret, "HmacSHA256"));
                return HEX.formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception error) {
                throw new IllegalStateException("internal_hmac_unavailable", error);
            }
        }
    }
}
