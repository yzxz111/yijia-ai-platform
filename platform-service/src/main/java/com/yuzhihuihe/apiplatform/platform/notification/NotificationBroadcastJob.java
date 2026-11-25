package com.yuzhihuihe.apiplatform.platform.notification;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class NotificationBroadcastJob {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final NotificationEventService events;
    private final Clock clock;
    private final int batchSize;
    private final Duration claimTimeout;
    private final Duration initialBackoff;
    private final int maxAttempts;

    @Autowired
    public NotificationBroadcastJob(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            NotificationEventService events, Clock clock,
            @Value("${NOTIFICATION_BROADCAST_BATCH_SIZE:${notification.broadcast.batch-size:50}}") int batchSize,
            @Value("${notification.broadcast.claim-timeout:PT2M}") Duration claimTimeout,
            @Value("${notification.broadcast.initial-backoff:PT10S}") Duration initialBackoff,
            @Value("${notification.broadcast.max-attempts:8}") int maxAttempts) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.events = events;
        this.clock = clock;
        this.batchSize = batchSize;
        this.claimTimeout = claimTimeout;
        this.initialBackoff = initialBackoff;
        this.maxAttempts = maxAttempts;
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("invalid_broadcast_batch_size");
        }
        if (claimTimeout == null || claimTimeout.isZero() || claimTimeout.isNegative()
                || initialBackoff == null || initialBackoff.isZero() || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("invalid_broadcast_retry_configuration");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("invalid_broadcast_max_attempts");
        }
    }

    @Scheduled(fixedDelayString = "${NOTIFICATION_BROADCAST_FIXED_DELAY:${notification.broadcast.fixed-delay:5000}}")
    public void scheduledRun() { runOnce(); }

    public int runOnce() {
        BroadcastClaim broadcast = transactions.execute(status -> claimBroadcast());
        if (broadcast == null) return 0;
        List<RecipientClaim> recipients = transactions.execute(
                status -> claimRecipients(broadcast));
        int completed = 0;
        for (RecipientClaim recipient : recipients == null
                ? List.<RecipientClaim>of() : recipients) {
            try {
                Boolean updated = transactions.execute(
                        status -> processRecipient(broadcast, recipient));
                if (Boolean.TRUE.equals(updated)) completed++;
            } catch (RuntimeException error) {
                transactions.executeWithoutResult(status -> failRecipient(recipient));
            }
        }
        transactions.executeWithoutResult(status -> finishOrRelease(broadcast));
        return completed;
    }

    boolean processRecipient(BroadcastClaim broadcast, RecipientClaim recipient) {
        List<BroadcastFence> broadcasts = jdbc.query("""
                SELECT status,paused_at,job_claim_token,job_claimed_until
                FROM platform.notification_broadcast WHERE id=? FOR UPDATE
                """, (result, row) -> new BroadcastFence(
                result.getString("status"), result.getTimestamp("paused_at"),
                result.getObject("job_claim_token", UUID.class),
                result.getTimestamp("job_claimed_until")), broadcast.id());
        List<RecipientFence> recipients = jdbc.query("""
                SELECT status,job_claim_token,job_claimed_until
                FROM platform.notification_broadcast_recipient
                WHERE id=? AND broadcast_id=? FOR UPDATE
                """, (result, row) -> new RecipientFence(
                result.getString("status"), result.getObject("job_claim_token", UUID.class),
                result.getTimestamp("job_claimed_until")), recipient.id(), broadcast.id());
        boolean valid = !broadcasts.isEmpty() && !recipients.isEmpty()
                && broadcastFenceValid(broadcasts.getFirst(), broadcast)
                && recipientFenceValid(recipients.getFirst(), recipient);
        if (!valid) {
            releaseStaleRecipient(recipient);
            return false;
        }
        NotificationEventService.RecordedEvent event = events.record(
                "broadcast:" + broadcast.id() + ":user:" + recipient.userId(),
                "PLATFORM_MAINTENANCE", recipient.tenantId(), recipient.userId(),
                "NOTIFICATION_BROADCAST", broadcast.id().toString(),
                Map.of("maintainTime", broadcast.maintainTime(),
                        "title", broadcast.title(), "summary", broadcast.summary(),
                        "broadcastKey", broadcast.broadcastKey()),
                OffsetDateTime.ofInstant(broadcast.scheduledAt(), ZoneOffset.UTC));
        if (!completeRecipient(recipient, event.id())) {
            throw new IllegalStateException("broadcast_recipient_completion_fence_lost");
        }
        return true;
    }

    private boolean broadcastFenceValid(BroadcastFence fence, BroadcastClaim claim) {
        return "SENDING".equals(fence.status()) && fence.pausedAt() == null
                && claim.token().equals(fence.token()) && active(fence.claimedUntil());
    }

    private boolean recipientFenceValid(RecipientFence fence, RecipientClaim claim) {
        return "PROCESSING".equals(fence.status()) && claim.token().equals(fence.token())
                && active(fence.claimedUntil());
    }

    private boolean active(Timestamp claimedUntil) {
        return claimedUntil != null && claimedUntil.toInstant().isAfter(clock.instant());
    }

    private void releaseStaleRecipient(RecipientClaim recipient) {
        jdbc.update("""
                UPDATE platform.notification_broadcast_recipient
                SET status='PENDING',next_attempt_at=?,job_claim_token=NULL,job_claimed_until=NULL
                WHERE id=? AND status='PROCESSING' AND job_claim_token=?
                """, now(), recipient.id(), recipient.token());
    }

    BroadcastClaim claimBroadcast() {
        List<BroadcastClaim> claims = jdbc.query("""
                WITH candidate AS (
                    SELECT id FROM platform.notification_broadcast
                    WHERE paused_at IS NULL AND scheduled_at <= ?
                      AND (status='SCHEDULED' OR (status='SENDING'
                           AND (job_claim_token IS NULL OR job_claimed_until <= ?)))
                    ORDER BY scheduled_at,created_at,id
                    FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE platform.notification_broadcast broadcast
                SET status='SENDING',started_at=COALESCE(started_at,?),
                    job_claim_token=gen_random_uuid(),job_claimed_until=?,updated_at=?
                FROM candidate WHERE broadcast.id=candidate.id
                RETURNING broadcast.id,broadcast.broadcast_key,broadcast.title,
                    broadcast.summary,broadcast.maintain_time,broadcast.job_claim_token,
                    broadcast.tenant_id,broadcast.created_by,broadcast.scheduled_at
                """, (result, row) -> new BroadcastClaim(
                result.getObject("id", UUID.class), result.getString("broadcast_key"),
                result.getString("title"), result.getString("summary"),
                result.getString("maintain_time"),
                result.getObject("job_claim_token", UUID.class),
                result.getObject("tenant_id", UUID.class),
                result.getObject("created_by", UUID.class),
                result.getTimestamp("scheduled_at").toInstant()),
                now(), now(), now(), Timestamp.from(clock.instant().plus(claimTimeout)), now());
        if (claims.isEmpty()) return null;
        BroadcastClaim claim = claims.getFirst();
        audit(claim, "SENDING", "SENDING");
        return claim;
    }

    List<RecipientClaim> claimRecipients(BroadcastClaim broadcast) {
        expireExhaustedRecipients(broadcast.id());
        return jdbc.query("""
                WITH candidates AS (
                    SELECT recipient.id FROM platform.notification_broadcast_recipient recipient
                    JOIN platform.notification_broadcast broadcast
                      ON broadcast.id=recipient.broadcast_id
                    WHERE recipient.broadcast_id=? AND broadcast.status='SENDING'
                      AND broadcast.paused_at IS NULL AND broadcast.job_claim_token=?
                      AND recipient.attempt_count < ? AND recipient.next_attempt_at <= ?
                      AND (recipient.status IN ('PENDING','FAILED') OR
                           (recipient.status='PROCESSING' AND recipient.job_claimed_until <= ?))
                    ORDER BY recipient.created_at,recipient.id
                    FOR UPDATE OF recipient SKIP LOCKED LIMIT ?
                )
                UPDATE platform.notification_broadcast_recipient recipient
                SET status='PROCESSING',attempt_count=attempt_count+1,
                    job_claim_token=gen_random_uuid(),job_claimed_until=?,last_error=NULL
                FROM candidates WHERE recipient.id=candidates.id
                RETURNING recipient.id,recipient.tenant_id,recipient.user_id,
                    recipient.job_claim_token,recipient.attempt_count
                """, (result, row) -> new RecipientClaim(
                result.getObject("id", UUID.class), result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getObject("job_claim_token", UUID.class), result.getInt("attempt_count")),
                broadcast.id(), broadcast.token(), maxAttempts, now(), now(), batchSize,
                Timestamp.from(clock.instant().plus(claimTimeout)));
    }

    private boolean completeRecipient(RecipientClaim recipient, UUID eventId) {
        return jdbc.update("""
                UPDATE platform.notification_broadcast_recipient
                SET status='COMPLETED',notification_event_id=?,processed_at=?,
                    job_claim_token=NULL,job_claimed_until=NULL,last_error=NULL
                WHERE id=? AND status='PROCESSING' AND job_claim_token=?
                """, eventId, now(), recipient.id(), recipient.token()) == 1;
    }

    private void failRecipient(RecipientClaim recipient) {
        boolean exhausted = recipient.attempt() >= maxAttempts;
        jdbc.update("""
                UPDATE platform.notification_broadcast_recipient
                SET status=?,processed_at=?,next_attempt_at=?,job_claim_token=NULL,
                    job_claimed_until=NULL,last_error='EVENT_RECORD_FAILED'
                WHERE id=? AND status='PROCESSING' AND job_claim_token=?
                """, exhausted ? "DEAD" : "FAILED", exhausted ? now() : null,
                Timestamp.from(clock.instant().plus(backoff(recipient.attempt()))),
                recipient.id(), recipient.token());
    }

    private void finishOrRelease(BroadcastClaim broadcast) {
        Long unfinished = jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_broadcast_recipient
                WHERE broadcast_id=? AND status IN ('PENDING','PROCESSING','FAILED')
                """, Long.class, broadcast.id());
        if (unfinished != null && unfinished == 0) {
            int completed = jdbc.update("""
                    UPDATE platform.notification_broadcast
                    SET status='COMPLETED',completed_at=?,job_claim_token=NULL,
                        job_claimed_until=NULL,updated_at=?
                    WHERE id=? AND status='SENDING' AND job_claim_token=? AND paused_at IS NULL
                    """, now(), now(), broadcast.id(), broadcast.token());
            if (completed == 1) audit(broadcast, "COMPLETED", "COMPLETED");
        } else {
            jdbc.update("""
                    UPDATE platform.notification_broadcast
                    SET job_claim_token=NULL,job_claimed_until=NULL,updated_at=?
                    WHERE id=? AND status='SENDING' AND job_claim_token=?
                    """, now(), broadcast.id(), broadcast.token());
        }
    }

    private void expireExhaustedRecipients(UUID broadcastId) {
        jdbc.update("""
                UPDATE platform.notification_broadcast_recipient
                SET status='DEAD',processed_at=?,job_claim_token=NULL,job_claimed_until=NULL,
                    last_error='LEASE_EXHAUSTED'
                WHERE broadcast_id=? AND status='PROCESSING' AND job_claimed_until <= ?
                  AND attempt_count >= ?
                """, now(), broadcastId, now(), maxAttempts);
        jdbc.update("""
                UPDATE platform.notification_broadcast_recipient
                SET status='DEAD',processed_at=?,last_error=COALESCE(last_error,'ATTEMPTS_EXHAUSTED')
                WHERE broadcast_id=? AND status IN ('PENDING','FAILED')
                  AND next_attempt_at <= ? AND attempt_count >= ?
                """, now(), broadcastId, now(), maxAttempts);
    }

    private void audit(BroadcastClaim broadcast, String action, String state) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id,actor_user_id,action,resource_type,resource_id,outcome,detail)
                VALUES (?,?,?,'NOTIFICATION_BROADCAST',?,'SUCCESS',
                    jsonb_build_object('status',?))
                """, broadcast.tenantId(), broadcast.actorId(),
                "NOTIFICATION_BROADCAST_" + action, broadcast.id().toString(), state);
    }

    private Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(30, Math.max(0, attempt - 1));
        try {
            return initialBackoff.multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            return Duration.ofDays(1);
        }
    }

    private Timestamp now() { return Timestamp.from(clock.instant()); }
    record BroadcastClaim(UUID id, String broadcastKey, String title,
            String summary, String maintainTime, UUID token, UUID tenantId, UUID actorId,
            java.time.Instant scheduledAt) { }
    record RecipientClaim(UUID id, UUID tenantId, UUID userId,
            UUID token, int attempt) { }
    private record BroadcastFence(String status, Timestamp pausedAt,
            UUID token, Timestamp claimedUntil) { }
    private record RecipientFence(String status, UUID token, Timestamp claimedUntil) { }
}
