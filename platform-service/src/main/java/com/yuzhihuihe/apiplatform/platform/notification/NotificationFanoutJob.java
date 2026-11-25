package com.yuzhihuihe.apiplatform.platform.notification;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class NotificationFanoutJob {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final NotificationSmsDispatcher sms;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int batchSize;
    private final Duration claimTimeout;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final int maxAttempts;

    @Autowired
    public NotificationFanoutJob(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            NotificationSmsDispatcher sms,
            ObjectMapper mapper,
            Clock clock,
            @Value("${NOTIFICATION_FANOUT_BATCH_SIZE:${notification.fanout.batch-size:50}}") int batchSize,
            @Value("${notification.fanout.claim-timeout:PT2M}") Duration claimTimeout,
            @Value("${notification.fanout.initial-backoff:PT10S}") Duration initialBackoff,
            @Value("${notification.fanout.max-backoff:PT1H}") Duration maxBackoff,
            @Value("${notification.fanout.max-attempts:8}") int maxAttempts) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.sms = sms;
        this.mapper = mapper;
        this.clock = clock;
        this.batchSize = batchSize;
        this.claimTimeout = claimTimeout;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.maxAttempts = maxAttempts;
        validateBounds();
    }

    public NotificationFanoutJob(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            NotificationSmsDispatcher sms, Clock clock, int batchSize, Duration claimTimeout) {
        this(jdbc, transactionManager, sms, new ObjectMapper(), clock, batchSize, claimTimeout,
                Duration.ofSeconds(10), Duration.ofHours(1), 8);
    }

    public NotificationFanoutJob(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            NotificationSmsDispatcher sms, Clock clock, int batchSize, Duration claimTimeout,
            Duration initialBackoff, Duration maxBackoff, int maxAttempts) {
        this(jdbc, transactionManager, sms, new ObjectMapper(), clock, batchSize, claimTimeout,
                initialBackoff, maxBackoff, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${NOTIFICATION_FANOUT_FIXED_DELAY:${notification.fanout.fixed-delay:5000}}")
    public void scheduledRun() { runOnce(); }

    public int runOnce() {
        transactions.executeWithoutResult(status -> expireExhausted());
        List<Claim> claims = transactions.execute(status -> claim());
        int processed = 0;
        for (Claim claim : claims == null ? List.<Claim>of() : claims) {
            Boolean prepared;
            try {
                prepared = transactions.execute(status -> prepareInApp(claim));
            } catch (RuntimeException preparationFailure) {
                transactions.executeWithoutResult(status -> fail(claim, "IN_APP_DELIVERY_FAILED"));
                continue;
            }
            if (!Boolean.TRUE.equals(prepared)) continue;
            try {
                sms.dispatch(claim.id());
            } catch (RuntimeException dispatchFailure) {
                transactions.executeWithoutResult(status -> fail(claim, "SMS_DISPATCH_FAILED"));
                continue;
            }
            Boolean completed = transactions.execute(status -> complete(claim));
            if (Boolean.TRUE.equals(completed)) processed++;
        }
        return processed;
    }

    private List<Claim> claim() {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT id FROM platform.notification_event
                    WHERE attempt_count < ? AND next_attempt_at <= ?
                      AND (status IN ('PENDING','FAILED')
                           OR (status='PROCESSING' AND claimed_until < ?))
                    ORDER BY created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE platform.notification_event event
                SET status='PROCESSING', claim_token=gen_random_uuid(), claimed_until=?,
                    attempt_count=attempt_count+1, last_error=NULL
                FROM candidates WHERE event.id=candidates.id
                RETURNING event.id, event.claim_token, event.attempt_count
                """, (rs, row) -> new Claim(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3)),
                maxAttempts, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), batchSize,
                Timestamp.from(clock.instant().plus(claimTimeout)));
    }

    private void expireExhausted() {
        jdbc.update("""
                UPDATE platform.notification_event
                SET status='DEAD', claim_token=NULL, claimed_until=NULL,
                    last_error='LEASE_EXHAUSTED'
                WHERE status='PROCESSING' AND claimed_until <= ? AND attempt_count >= ?
                """, Timestamp.from(clock.instant()), maxAttempts);
        jdbc.update("""
                UPDATE platform.notification_event
                SET status='DEAD', claim_token=NULL, claimed_until=NULL,
                    last_error=COALESCE(last_error, 'ATTEMPTS_EXHAUSTED')
                WHERE status IN ('PENDING','FAILED') AND next_attempt_at <= ? AND attempt_count >= ?
                """, Timestamp.from(clock.instant()), maxAttempts);
    }

    private boolean prepareInApp(Claim claim) {
        List<Event> events = jdbc.query("""
                SELECT id,tenant_id,user_id,event_type,payload::text
                FROM platform.notification_event
                WHERE id=? AND status='PROCESSING' AND claim_token=?
                FOR UPDATE
                """, (rs, row) -> new Event(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), payload(rs.getString(5))),
                claim.id(), claim.token());
        if (events.isEmpty()) return false;
        Event event = events.getFirst();
        if (event.tenantId() == null || event.userId() == null) {
            fail(claim, "UNSCOPED_EVENT");
            return false;
        }
        NotificationTypeRegistry.TypeDefinition type = NotificationTypeRegistry.require(event.eventType());
        String title = render(type.titleTemplate(), event.payload());
        String summary = render(type.summaryTemplate(), event.payload());
        jdbc.update("""
                INSERT INTO platform.in_app_notification(id,event_id,tenant_id,user_id,title,summary,created_at)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT (event_id,user_id) DO NOTHING
                """, UUID.randomUUID(), event.id(), event.tenantId(), event.userId(), title, summary,
                Timestamp.from(clock.instant()));
        jdbc.update("""
                INSERT INTO platform.notification_channel_delivery(
                    id,event_id,tenant_id,user_id,channel,status,next_attempt_at,delivered_at,created_at,updated_at)
                VALUES (?,?,?,?,'IN_APP','DELIVERED',?,?,?,?)
                ON CONFLICT (event_id,user_id,channel) DO NOTHING
                """, UUID.randomUUID(), event.id(), event.tenantId(), event.userId(),
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
        return true;
    }

    private boolean complete(Claim claim) {
        return jdbc.update("""
                UPDATE platform.notification_event
                SET status='PROCESSED', processed_at=?, claim_token=NULL, claimed_until=NULL,
                    last_error=NULL
                WHERE id=? AND status='PROCESSING' AND claim_token=?
                """, Timestamp.from(clock.instant()), claim.id(), claim.token()) == 1;
    }

    private void fail(Claim claim, String error) {
        boolean exhausted = claim.attempt() >= maxAttempts;
        jdbc.update("""
                UPDATE platform.notification_event
                SET status=?, claim_token=NULL, claimed_until=NULL, last_error=?, next_attempt_at=?
                WHERE id=? AND status='PROCESSING' AND claim_token=?
                """, exhausted ? "DEAD" : "FAILED", error,
                Timestamp.from(exhausted ? clock.instant() : clock.instant().plus(backoff(claim.attempt()))),
                claim.id(), claim.token());
    }

    private String render(String template, Map<String, Object> payload) {
        String rendered = template;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            rendered = rendered.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return rendered;
    }

    private Map<String, Object> payload(String json) {
        try { return mapper.readValue(json, new TypeReference<Map<String, Object>>() { }); }
        catch (Exception error) { throw new IllegalStateException("invalid_notification_payload", error); }
    }

    private void validateBounds() {
        if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("invalid_notification_fanout_batch_size");
        if (claimTimeout.isZero() || claimTimeout.isNegative()) throw new IllegalArgumentException("invalid_notification_fanout_claim_timeout");
        if (initialBackoff == null || initialBackoff.isZero() || initialBackoff.isNegative()
                || maxBackoff == null || maxBackoff.isZero() || maxBackoff.isNegative()
                || initialBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("invalid_notification_fanout_backoff");
        }
        if (maxAttempts < 1 || maxAttempts > 100) throw new IllegalArgumentException("invalid_notification_fanout_max_attempts");
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

    private record Claim(UUID id, UUID token, int attempt) { }
    private record Event(UUID id, UUID tenantId, UUID userId, String eventType, Map<String, Object> payload) { }
}
