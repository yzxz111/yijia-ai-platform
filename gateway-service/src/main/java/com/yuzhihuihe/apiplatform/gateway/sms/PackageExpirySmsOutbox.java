package com.yuzhihuihe.apiplatform.gateway.sms;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

final class PackageExpirySmsOutbox {

    private final DatabaseClient database;
    private final Clock clock;
    private final Duration claimTimeout;
    private final Duration initialBackoff;
    private final Duration maximumBackoff;
    private final int maximumAttempts;

    PackageExpirySmsOutbox(
            DatabaseClient database,
            Clock clock,
            Duration claimTimeout,
            Duration initialBackoff,
            Duration maximumBackoff,
            int maximumAttempts) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!positive(claimTimeout) || !positive(initialBackoff) || !positive(maximumBackoff)
                || initialBackoff.compareTo(maximumBackoff) > 0 || maximumAttempts <= 0) {
            throw new IllegalArgumentException("invalid_package_expiry_sms_outbox_configuration");
        }
        this.claimTimeout = claimTimeout;
        this.initialBackoff = initialBackoff;
        this.maximumBackoff = maximumBackoff;
        this.maximumAttempts = maximumAttempts;
    }

    Mono<Long> materialize(int limit) {
        return materialize("WARNING", """
                        package.status = 'ACTIVE'
                        AND package.expires_at > now()
                        AND package.expires_at <= now() + interval '3 days'
                        """, limit)
                .zipWith(materialize("EXPIRED", """
                        package.status IN ('ACTIVE', 'EXPIRED')
                        AND package.expires_at <= now()
                        """, limit), Long::sum);
    }

    Flux<ClaimedSignal> claim(int limit) {
        Instant staleBefore = clock.instant().minus(claimTimeout);
        return database.sql("""
                        WITH candidates AS (
                            SELECT id
                            FROM gateway.package_expiry_sms_outbox
                            WHERE attempt_count < :maximum_attempts
                              AND ((status IN ('PENDING', 'FAILED')
                                      AND next_attempt_at <= now())
                                OR (status = 'DELIVERING' AND claimed_at <= :stale_before))
                            ORDER BY next_attempt_at, created_at, id
                            FOR UPDATE SKIP LOCKED
                            LIMIT :batch_size
                        )
                        UPDATE gateway.package_expiry_sms_outbox AS outbox
                        SET status = 'DELIVERING',
                            attempt_count = outbox.attempt_count + 1,
                            claim_token = gen_random_uuid(),
                            claimed_at = now(),
                            updated_at = now()
                        FROM candidates
                        WHERE outbox.id = candidates.id
                        RETURNING outbox.id, outbox.package_instance_id,
                            outbox.tenant_id, outbox.user_id, outbox.expires_at,
                            outbox.signal_state, outbox.attempt_count, outbox.claim_token
                        """)
                .bind("maximum_attempts", maximumAttempts)
                .bind("stale_before", staleBefore)
                .bind("batch_size", limit)
                .map((row, metadata) -> new ClaimedSignal(
                        row.get("id", UUID.class),
                        row.get("package_instance_id", UUID.class),
                        row.get("tenant_id", UUID.class),
                        row.get("user_id", UUID.class),
                        row.get("expires_at", Instant.class),
                        row.get("signal_state", String.class),
                        requiredInt(row.get("attempt_count", Integer.class)),
                        row.get("claim_token", UUID.class)))
                .all();
    }

    Mono<Void> markSent(ClaimedSignal signal) {
        return database.sql("""
                        UPDATE gateway.package_expiry_sms_outbox
                        SET status = 'SENT', claim_token = NULL, claimed_at = NULL,
                            sent_at = now(), updated_at = now()
                        WHERE id = :id AND status = 'DELIVERING'
                          AND claim_token = :claim_token
                        """)
                .bind("id", signal.id())
                .bind("claim_token", signal.claimToken())
                .fetch().rowsUpdated().then();
    }

    Mono<Void> markFailed(ClaimedSignal signal) {
        Instant nextAttemptAt = clock.instant().plus(retryDelay(signal.attemptCount()));
        return database.sql("""
                        UPDATE gateway.package_expiry_sms_outbox
                        SET status = 'FAILED', claim_token = NULL, claimed_at = NULL,
                            next_attempt_at = :next_attempt_at, updated_at = now()
                        WHERE id = :id AND status = 'DELIVERING'
                          AND claim_token = :claim_token
                        """)
                .bind("next_attempt_at", nextAttemptAt)
                .bind("id", signal.id())
                .bind("claim_token", signal.claimToken())
                .fetch().rowsUpdated().then();
    }

    private Mono<Long> materialize(String signalState, String eligibility, int limit) {
        return database.sql("""
                        INSERT INTO gateway.package_expiry_sms_outbox(
                            id, package_instance_id, tenant_id, user_id, expires_at,
                            signal_state, status, next_attempt_at, created_at, updated_at)
                        SELECT gen_random_uuid(), package.id, package.tenant_id, package.user_id,
                            package.expires_at, :signal_state, 'PENDING', now(), now(), now()
                        FROM gateway.package_instance AS package
                        WHERE package.expires_at IS NOT NULL
                          AND (""" + eligibility + """
                          )
                          AND NOT EXISTS (
                              SELECT 1
                              FROM gateway.package_expiry_sms_outbox AS existing
                              WHERE existing.package_instance_id = package.id
                                AND existing.signal_state = :signal_state
                          )
                        ORDER BY package.expires_at, package.id
                        LIMIT :batch_size
                        ON CONFLICT (package_instance_id, signal_state) DO NOTHING
                        """)
                .bind("signal_state", signalState)
                .bind("batch_size", limit)
                .fetch().rowsUpdated();
    }

    private Duration retryDelay(int attemptCount) {
        Duration delay = initialBackoff;
        for (int attempt = 1;
                attempt < attemptCount && delay.compareTo(maximumBackoff) < 0;
                attempt++) {
            try {
                delay = delay.multipliedBy(2);
            } catch (ArithmeticException error) {
                return maximumBackoff;
            }
            if (delay.compareTo(maximumBackoff) > 0) {
                return maximumBackoff;
            }
        }
        return delay;
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

    private static int requiredInt(Integer value) {
        if (value == null) {
            throw new IllegalStateException("package_expiry_sms_attempt_count_missing");
        }
        return value;
    }

    record ClaimedSignal(
            UUID id,
            UUID packageId,
            UUID tenantId,
            UUID userId,
            Instant expiresAt,
            String signalState,
            int attemptCount,
            UUID claimToken) {
        ClaimedSignal {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(packageId, "packageId");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(signalState, "signalState");
            Objects.requireNonNull(claimToken, "claimToken");
        }
    }
}
