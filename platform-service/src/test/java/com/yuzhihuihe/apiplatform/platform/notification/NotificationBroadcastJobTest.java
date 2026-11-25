package com.yuzhihuihe.apiplatform.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class NotificationBroadcastJobTest {
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");
    private static final DataSource DATA_SOURCE;
    private static final Instant NOW = Instant.parse("2026-08-09T04:00:00Z");
    static {
        POSTGRES.start();
        DATA_SOURCE = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(DATA_SOURCE).defaultSchema("platform").schemas("platform")
                .locations("classpath:db/migration").load().migrate();
    }

    private final JdbcTemplate jdbc = new JdbcTemplate(DATA_SOURCE);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final DataSourceTransactionManager transactions =
            new DataSourceTransactionManager(DATA_SOURCE);

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE platform.notification_broadcast_recipient, "
                + "platform.notification_broadcast, platform.notification_channel_delivery, "
                + "platform.in_app_notification, platform.notification_event, "
                + "platform.notification_preference, platform.app_user CASCADE");
    }

    @Test
    void reclaimsExpiredBroadcastAndRecipientLeasesWithBoundedStableEventFanout() {
        UUID first = user("first");
        UUID second = user("second");
        UUID broadcast = broadcast("SENDING", NOW.minusSeconds(120), UUID.randomUUID(),
                NOW.minusSeconds(1), null);
        recipient(broadcast, first, "PROCESSING", UUID.randomUUID(), NOW.minusSeconds(1), 1);
        recipient(broadcast, second, "PENDING", null, null, 0);
        NotificationBroadcastJob job = job(new NotificationEventService(
                new NotificationEventRepository(jdbc), mapper), 2, 4);

        assertThat(job.runOnce()).isEqualTo(2);

        assertThat(jdbc.queryForList("""
                SELECT event_key FROM platform.notification_event ORDER BY event_key
                """, String.class)).containsExactlyInAnyOrder(
                "broadcast:" + broadcast + ":user:" + first,
                "broadcast:" + broadcast + ":user:" + second);
        assertThat(jdbc.queryForObject("SELECT payload::text FROM platform.notification_event "
                + "WHERE user_id=?", String.class, first))
                .contains("maintainTime", "title", "summary", "broadcastKey")
                .doesNotContain("provider-business");
        assertThat(jdbc.queryForObject("SELECT status FROM platform.notification_broadcast "
                + "WHERE id=?", String.class, broadcast)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.sms_delivery",
                Integer.class)).isZero();
    }

    @Test
    void isolatesRecipientFailureRetriesItAndDoesNotBlockOtherRecipients() {
        UUID failing = user("failing");
        UUID succeeding = user("succeeding");
        UUID broadcast = broadcast("SCHEDULED", NOW.minusSeconds(1), null, null, null);
        recipient(broadcast, failing, "PENDING", null, null, 0);
        recipient(broadcast, succeeding, "PENDING", null, null, 0);
        NotificationEventService events = new NotificationEventService(
                new NotificationEventRepository(jdbc), mapper) {
            @Override
            public RecordedEvent record(String eventKey, String eventType, UUID tenantId,
                    UUID userId, String aggregateType, String aggregateId, Map<String, ?> payload,
                    java.time.OffsetDateTime occurredAt) {
                if (userId.equals(failing)) throw new IllegalStateException("isolated failure");
                return super.record(eventKey, eventType, tenantId, userId, aggregateType,
                        aggregateId, payload, occurredAt);
            }
        };
        NotificationBroadcastJob job = job(events, 10, 2);

        assertThat(job.runOnce()).isOne();

        assertThat(jdbc.queryForObject("SELECT status FROM "
                + "platform.notification_broadcast_recipient WHERE user_id=?",
                String.class, succeeding)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM "
                + "platform.notification_broadcast_recipient WHERE user_id=?",
                String.class, failing)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM "
                + "platform.notification_broadcast_recipient WHERE user_id=?",
                String.class, failing)).isEqualTo("EVENT_RECORD_FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM platform.notification_broadcast "
                + "WHERE id=?", String.class, broadcast)).isEqualTo("SENDING");

        jdbc.update("UPDATE platform.notification_broadcast_recipient SET next_attempt_at=? "
                + "WHERE user_id=?", java.sql.Timestamp.from(NOW.minusSeconds(1)), failing);
        assertThat(job(new NotificationEventService(
                new NotificationEventRepository(jdbc), mapper), 10, 2).runOnce()).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM platform.notification_broadcast "
                + "WHERE id=?", String.class, broadcast)).isEqualTo("COMPLETED");
    }

    @Test
    void pausedBroadcastIsNotClaimedAndOperationsSmsRemainsDelegatedToExplicitOptIn() {
        UUID user = user("paused");
        UUID paused = broadcast("SCHEDULED", NOW.minusSeconds(1), null, null, NOW.minusSeconds(30));
        recipient(paused, user, "PENDING", null, null, 0);

        assertThat(job(new NotificationEventService(
                new NotificationEventRepository(jdbc), mapper), 10, 3).runOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.notification_event",
                Integer.class)).isZero();

        jdbc.update("UPDATE platform.notification_broadcast SET paused_at=NULL WHERE id=?", paused);
        assertThat(job(new NotificationEventService(
                new NotificationEventRepository(jdbc), mapper), 10, 3).runOnce()).isOne();
        UUID event = jdbc.queryForObject("SELECT id FROM platform.notification_event WHERE user_id=?",
                UUID.class, user);
        NotificationSmsDispatcher dispatcher = new NotificationSmsDispatcher(
                jdbc, mapper,
                (com.yuzhihuihe.apiplatform.platform.sms.SmsNotificationService) null, clock);
        dispatcher.dispatch(event);
        assertThat(jdbc.queryForObject("SELECT suppression_reason FROM "
                + "platform.notification_channel_delivery WHERE event_id=? AND channel='SMS'",
                String.class, event)).isEqualTo("PREFERENCE_DISABLED");
    }

    @Test
    void expiresExhaustedCrashLeaseAndAuditsJobStateTransitionsWithSchedulingActor() {
        UUID actor = user("actor");
        UUID broadcast = broadcast("SCHEDULED", NOW.minusSeconds(1), null, null, null);
        UUID tenant = jdbc.queryForObject("SELECT tenant_id FROM platform.app_user WHERE id=?",
                UUID.class, actor);
        jdbc.update("UPDATE platform.notification_broadcast SET created_by=?,tenant_id=? WHERE id=?",
                actor, tenant, broadcast);
        recipient(broadcast, actor, "PROCESSING", UUID.randomUUID(), NOW.minusSeconds(1), 2);

        assertThat(job(new NotificationEventService(
                new NotificationEventRepository(jdbc), mapper), 10, 2).runOnce()).isZero();

        assertThat(jdbc.queryForObject("SELECT status FROM "
                + "platform.notification_broadcast_recipient WHERE user_id=?",
                String.class, actor)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT status FROM platform.notification_broadcast "
                + "WHERE id=?", String.class, broadcast)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForList("SELECT action FROM platform.admin_audit_log "
                + "WHERE resource_id=? ORDER BY id", String.class, broadcast.toString()))
                .containsExactly("NOTIFICATION_BROADCAST_SENDING",
                        "NOTIFICATION_BROADCAST_COMPLETED");
        assertThat(jdbc.queryForList("SELECT actor_user_id FROM platform.admin_audit_log "
                + "WHERE resource_id=?", UUID.class, broadcast.toString()))
                .containsOnly(actor);
    }

    @Test
    void retryAfterEventCommitReplaysWithStableScheduledOccurrenceTime() {
        UUID user = user("event-committed");
        Instant scheduledAt = NOW.minusSeconds(60);
        UUID broadcast = broadcast("SCHEDULED", scheduledAt, null, null, null);
        recipient(broadcast, user, "PENDING", null, null, 0);
        UUID tenant = jdbc.queryForObject("SELECT tenant_id FROM platform.app_user WHERE id=?",
                UUID.class, user);
        NotificationEventService events = new NotificationEventService(
                new NotificationEventRepository(jdbc), mapper);
        events.record("broadcast:" + broadcast + ":user:" + user,
                "PLATFORM_MAINTENANCE", tenant, user, "NOTIFICATION_BROADCAST",
                broadcast.toString(), Map.of(
                        "maintainTime", "2026-08-09 18:00-18:30",
                        "title", "Maintenance title", "summary", "Maintenance body",
                        "broadcastKey", "broadcast:" + broadcast),
                java.time.OffsetDateTime.ofInstant(scheduledAt, ZoneOffset.UTC));

        assertThat(job(events, 10, 3).runOnce()).isOne();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.notification_event "
                + "WHERE event_key=?", Integer.class,
                "broadcast:" + broadcast + ":user:" + user)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM "
                + "platform.notification_broadcast_recipient WHERE user_id=?",
                String.class, user)).isEqualTo("COMPLETED");
    }

    @Test
    void staleClaimAfterPauseIsFencedAndResumeRetriesExactlyOnce() {
        UUID actor = user("pause-actor");
        UUID broadcast = broadcast("SCHEDULED", NOW.minusSeconds(1), null, null, null);
        recipient(broadcast, actor, "PENDING", null, null, 0);
        NotificationEventService events = new NotificationEventService(
                new NotificationEventRepository(jdbc), mapper);
        NotificationBroadcastJob job = job(events, 10, 3);
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        NotificationBroadcastJob.BroadcastClaim broadcastClaim =
                transaction.execute(status -> job.claimBroadcast());
        NotificationBroadcastJob.RecipientClaim recipientClaim = transaction.execute(
                status -> job.claimRecipients(broadcastClaim).getFirst());
        NotificationBroadcastService service = new NotificationBroadcastService(
                new NotificationBroadcastRepository(jdbc), mapper, clock);

        transaction.executeWithoutResult(status -> service.pause(broadcast, actor));
        Boolean staleProcessed = transaction.execute(
                status -> job.processRecipient(broadcastClaim, recipientClaim));

        assertThat(staleProcessed).isFalse();
        assertThat(service.detail(broadcast).paused()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM "
                + "platform.notification_broadcast_recipient WHERE broadcast_id=?",
                String.class, broadcast)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.notification_event "
                + "WHERE aggregate_id=?", Integer.class, broadcast.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM "
                + "platform.notification_channel_delivery", Integer.class)).isZero();

        service.resume(broadcast, NOW.plusSeconds(1), actor);
        Clock afterResume = Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC);
        NotificationBroadcastJob resumed = new NotificationBroadcastJob(
                jdbc, transactions, events, afterResume, 10,
                Duration.ofMinutes(1), Duration.ofSeconds(10), 3);
        assertThat(resumed.runOnce()).isOne();
        assertThat(resumed.runOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.notification_event "
                + "WHERE aggregate_id=?", Integer.class, broadcast.toString())).isOne();
    }

    private NotificationBroadcastJob job(
            NotificationEventService events, int batchSize, int maxAttempts) {
        return new NotificationBroadcastJob(jdbc, transactions, events, clock, batchSize,
                Duration.ofMinutes(1), Duration.ofSeconds(10), maxAttempts);
    }

    private UUID user(String name) {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO platform.app_user(id,tenant_id,email,password_hash,display_name) "
                + "VALUES (?,?,?,'hash',?)", user, tenant, user + "@example.invalid", name);
        return user;
    }

    private UUID broadcast(String status, Instant scheduledAt, UUID token,
            Instant claimedUntil, Instant pausedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.notification_broadcast(
                    id,broadcast_key,title,summary,maintain_time,provider_business_no,
                    audience_type,status,scheduled_at,started_at,job_claim_token,
                    job_claimed_until,paused_at)
                VALUES (?,?, 'Maintenance title','Maintenance body','2026-08-09 18:00-18:30',
                    'provider-business-1','ALL_ACTIVE_USERS',?,?,?,?,?,?)
                """, id, "broadcast:" + id, status, java.sql.Timestamp.from(scheduledAt),
                "SENDING".equals(status) ? java.sql.Timestamp.from(NOW.minusSeconds(120)) : null,
                token, claimedUntil == null ? null : java.sql.Timestamp.from(claimedUntil),
                pausedAt == null ? null : java.sql.Timestamp.from(pausedAt));
        return id;
    }

    private void recipient(UUID broadcast, UUID user, String status, UUID token,
            Instant claimedUntil, int attempts) {
        UUID tenant = jdbc.queryForObject("SELECT tenant_id FROM platform.app_user WHERE id=?",
                UUID.class, user);
        jdbc.update("""
                INSERT INTO platform.notification_broadcast_recipient(
                    broadcast_id,tenant_id,user_id,recipient_snapshot,status,attempt_count,
                    next_attempt_at,job_claim_token,job_claimed_until)
                VALUES (?,?,?,jsonb_build_object('userId',?::text,'tenantId',?::text),?,?,?, ?,?)
                """, broadcast, tenant, user, user, tenant, status, attempts,
                java.sql.Timestamp.from(NOW.minusSeconds(1)), token,
                claimedUntil == null ? null : java.sql.Timestamp.from(claimedUntil));
    }
}
