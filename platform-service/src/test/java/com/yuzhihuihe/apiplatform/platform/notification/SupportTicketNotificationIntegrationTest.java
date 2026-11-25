package com.yuzhihuihe.apiplatform.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.support.SupportServiceTestFactory;
import com.yuzhihuihe.apiplatform.platform.support.SupportTicketService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class SupportTicketNotificationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static final UUID TENANT = UUID.fromString("7a000000-0000-4000-8000-000000000010");
    static final UUID USER = UUID.fromString("7a000000-0000-4000-8000-000000000011");
    static final UUID ADMIN = UUID.fromString("7a000000-0000-4000-8000-000000000012");
    static final UUID OTHER_TENANT = UUID.fromString("7a000000-0000-4000-8000-000000000020");
    static final UUID OTHER_USER = UUID.fromString("7a000000-0000-4000-8000-000000000021");
    static DriverManagerDataSource dataSource;

    @BeforeAll
    static void migrateAndSeed() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("platform").schemas("platform").locations("classpath:db/migration")
                .load().migrate();
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new JdbcTemplate(dataSource).update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name)
                VALUES (?, ?, 'ticket-user@example.com', 'hash', 'Ticket User'),
                       (?, ?, 'ticket-admin@example.com', 'hash', 'Ticket Admin'),
                       (?, ?, 'ticket-other@example.com', 'hash', 'Other User')
                """, USER, TENANT, ADMIN, OTHER_TENANT, OTHER_USER, OTHER_TENANT);
    }

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-09T02:00:00Z"), ZoneOffset.UTC);

    @Test
    void recordsOnlyFirstCompletionWithTicketNumberPayloadAndStableBusinessNumber() {
        Fixture fixture = fixture();
        SupportTicketService.TicketView created = fixture.tx.execute(status ->
                fixture.service.create(TENANT, USER, "Cannot call API", "Plain text details"));
        assertThat(created.ticketNo()).startsWith("TK-").hasSize(23);

        SupportTicketService.TicketView processing = fixture.tx.execute(status -> {
            fixture.service.claim(ADMIN, created.id());
            fixture.service.reply(ADMIN, created.id(), "We are investigating");
            return fixture.service.process(ADMIN, created.id());
        });
        assertThat(processing.status()).isEqualTo(SupportTicketService.Status.PROCESSING);

        SupportTicketService.TicketView completed = fixture.tx.execute(status ->
                fixture.service.complete(ADMIN, created.id(), "Configuration corrected", "SUP-1001"));
        SupportTicketService.TicketView replay = fixture.tx.execute(status ->
                fixture.service.complete(ADMIN, created.id(), "Ignored replay text", "SUP-1001"));
        assertThat(completed.status()).isEqualTo(SupportTicketService.Status.COMPLETED);
        assertThat(replay.completionBusinessNo()).isEqualTo("SUP-1001");

        assertThatThrownBy(() -> fixture.tx.execute(status -> fixture.service.complete(
                ADMIN, created.id(), "Different", "SUP-1002")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(fixture.jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_event
                WHERE event_type='SUPPORT_TICKET_COMPLETED' AND aggregate_id=?
                """, Long.class, created.id().toString())).isEqualTo(1L);
        assertThat(fixture.jdbc.queryForObject("""
                SELECT payload::text FROM platform.notification_event
                WHERE event_type='SUPPORT_TICKET_COMPLETED' AND aggregate_id=?
                """, String.class, created.id().toString())).isEqualTo(
                        "{\"ticketNo\": \"" + created.ticketNo() + "\"}");
        assertThat(fixture.jdbc.queryForObject("SELECT count(*) FROM platform.sms_delivery", Long.class))
                .isZero();
        fixture.tx.executeWithoutResult(status -> fixture.service.closeAdmin(ADMIN, created.id()));
        assertThat(fixture.jdbc.queryForList("""
                SELECT action FROM platform.admin_audit_log
                WHERE tenant_id=? AND actor_user_id=? AND resource_id=? ORDER BY action
                """, String.class, TENANT, ADMIN, created.id().toString())).containsExactlyInAnyOrder(
                        "SUPPORT_TICKET_CLAIMED", "SUPPORT_TICKET_PROCESSING",
                        "SUPPORT_TICKET_REPLIED", "SUPPORT_TICKET_COMPLETED", "SUPPORT_TICKET_CLOSED");
        assertThat(fixture.jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE tenant_id=? AND actor_user_id=? AND resource_id=?
                  AND (detail::text ILIKE '%body%' OR detail::text ILIKE '%comment%'
                       OR detail::text ILIKE '%resolution%')
                """, Long.class, TENANT, ADMIN, created.id().toString())).isZero();
    }

    @Test
    void enforcesOwnershipCommentsAndOpenToCompletedToClosedPath() {
        Fixture fixture = fixture();
        SupportTicketService.TicketView created = fixture.tx.execute(status ->
                fixture.service.create(TENANT, USER, "Billing question", "Please review"));
        fixture.tx.executeWithoutResult(status ->
                fixture.service.addUserComment(TENANT, USER, created.id(), "User follow-up"));
        fixture.tx.executeWithoutResult(status ->
                fixture.service.reply(ADMIN, created.id(), "Admin response"));

        assertThat(fixture.service.comments(TENANT, created.id()))
                .extracting(SupportTicketService.CommentView::authorRole)
                .containsExactly("USER", "ADMIN");
        assertThat(fixture.service.listUser(TENANT, USER)).extracting(SupportTicketService.TicketView::id)
                .contains(created.id());
        assertThat(fixture.service.listAdmin(null)).extracting(SupportTicketService.TicketView::id)
                .contains(created.id());
        assertThatThrownBy(() -> fixture.service.detailUser(TENANT, ADMIN, created.id()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(fixture.service.detailAdmin(created.id()).tenantId()).isEqualTo(TENANT);

        SupportTicketService.TicketView closed = fixture.tx.execute(status -> {
            fixture.service.complete(ADMIN, created.id(), "Answered", "SUP-2001");
            return fixture.service.closeUser(TENANT, USER, created.id());
        });
        assertThat(closed.status()).isEqualTo(SupportTicketService.Status.CLOSED);
    }

    @Test
    void persistsTicketAttachmentBytesAndRestrictsReadsToTheOwner() {
        Fixture fixture = fixture();
        byte[] bytes = new byte[] {0, 1, 2, (byte) 0xff, 10};
        SupportTicketService.TicketView created = fixture.tx.execute(status ->
                fixture.service.create(TENANT, USER, "Attachment issue", "Please inspect the log",
                        List.of(new SupportTicketService.AttachmentUpload(
                                "error-log.txt", "text/plain", bytes))));

        List<SupportTicketService.AttachmentView> attachments =
                fixture.service.attachmentsUser(TENANT, USER, created.id());
        assertThat(attachments).hasSize(1);
        SupportTicketService.AttachmentView uploaded = attachments.getFirst();
        assertThat(uploaded.filename()).isEqualTo("error-log.txt");
        assertThat(uploaded.contentType()).isEqualTo("text/plain");
        assertThat(uploaded.sizeBytes()).isEqualTo(bytes.length);
        assertThat(uploaded.commentId()).isNull();

        SupportTicketService.AttachmentContent downloaded =
                fixture.service.attachmentUser(TENANT, USER, created.id(), uploaded.id());
        assertThat(downloaded.filename()).isEqualTo("error-log.txt");
        assertThat(downloaded.contentType()).isEqualTo("text/plain");
        assertThat(downloaded.bytes()).containsExactly(bytes);

        assertThatThrownBy(() -> fixture.service.attachmentsUser(TENANT, ADMIN, created.id()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> fixture.service.attachmentUser(
                TENANT, ADMIN, created.id(), uploaded.id()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void administratorReplyCreatesVisibleNotificationWithReplyContent() {
        Fixture fixture = fixture();
        SupportTicketService.TicketView created = fixture.tx.execute(status ->
                fixture.service.create(TENANT, USER, "Model access", "Please check my account"));

        fixture.tx.executeWithoutResult(status ->
                fixture.service.reply(ADMIN, created.id(), "已为您重新开通模型权限，请重试。"));

        UUID eventId = fixture.jdbc.queryForObject("""
                SELECT id FROM platform.notification_event
                WHERE event_type='SUPPORT_TICKET_REPLIED' AND aggregate_id=?
                """, UUID.class, created.id().toString());
        assertThat(eventId).isNotNull();
        assertThat(fixture.jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_event
                WHERE event_type='SUPPORT_TICKET_REPLIED' AND aggregate_id=?
                """, Long.class, created.id().toString())).isOne();
        fixture.jdbc.update("UPDATE platform.notification_event SET next_attempt_at=? WHERE id=?",
                java.sql.Timestamp.from(clock.instant()), eventId);

        NotificationSmsDispatcher sms = new NotificationSmsDispatcher(
                fixture.jdbc, new ObjectMapper(),
                (com.yuzhihuihe.apiplatform.platform.sms.SmsNotificationService) null, clock);
        NotificationFanoutJob fanout = new NotificationFanoutJob(
                fixture.jdbc, new DataSourceTransactionManager(dataSource), sms,
                clock, 20, Duration.ofMinutes(1));
        assertThat(fanout.runOnce()).isGreaterThanOrEqualTo(1);

        assertThat(fixture.jdbc.queryForMap("""
                SELECT title, summary FROM platform.in_app_notification
                WHERE event_id=? AND tenant_id=? AND user_id=?
                """, eventId, TENANT, USER)).containsEntry("title", "支持工单有新回复")
                .containsEntry("summary", "工单 " + created.ticketNo()
                        + "：已为您重新开通模型权限，请重试。");
    }

    @Test
    void rollsBackCompletionWhenNotificationIdentityConflicts() {
        Fixture fixture = fixture();
        SupportTicketService.TicketView created = fixture.tx.execute(status ->
                fixture.service.create(TENANT, USER, "Rollback case", "Completion must be atomic"));
        fixture.jdbc.update("""
                INSERT INTO platform.notification_event(
                    event_key, category, event_type, tenant_id, user_id,
                    aggregate_type, aggregate_id, payload, occurred_at)
                VALUES ('support-ticket-completed:SUP-3001', 'SUPPORT', 'SUPPORT_TICKET_COMPLETED',
                    ?, ?, 'SUPPORT_TICKET', 'different-ticket', '{"ticketNo":"TK-DIFFERENT"}'::jsonb,
                    '2026-08-09T02:00:00Z')
                """, TENANT, USER);

        assertThatThrownBy(() -> fixture.tx.execute(status -> fixture.service.complete(
                ADMIN, created.id(), "Resolved", "SUP-3001")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        SupportTicketService.TicketView unchanged = fixture.service.detailAdmin(created.id());
        assertThat(unchanged.status()).isEqualTo(SupportTicketService.Status.OPEN);
        assertThat(unchanged.resolution()).isNull();
        assertThat(unchanged.completionBusinessNo()).isNull();
    }

    @Test
    void serializesUserCommentAgainstConcurrentClose() throws Exception {
        Fixture fixture = fixture();
        SupportTicketService.TicketView created = fixture.tx.execute(status -> {
            SupportTicketService.TicketView ticket = fixture.service.create(
                    TENANT, USER, "Concurrent close", "Comment ordering must be stable");
            return fixture.service.complete(ADMIN, ticket.id(), "Resolved", "SUP-4001");
        });
        fixture.jdbc.execute("""
                CREATE OR REPLACE FUNCTION platform.pause_support_comment_insert()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    PERFORM pg_sleep(2);
                    RETURN NEW;
                END;
                $$
                """);
        fixture.jdbc.execute("""
                CREATE TRIGGER trg_pause_support_comment_insert
                BEFORE INSERT ON platform.support_ticket_comment
                FOR EACH ROW EXECUTE FUNCTION platform.pause_support_comment_insert()
                """);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var comment = executor.submit(() -> fixture.tx.execute(status ->
                    fixture.service.addUserComment(TENANT, USER, created.id(), "Final comment")));
            waitForSleepingComment(fixture.jdbc);
            var close = executor.submit(() -> fixture.tx.execute(status ->
                    fixture.service.closeUser(TENANT, USER, created.id())));

            Thread.sleep(400);
            assertThat(close.isDone()).as("close waits for the comment transaction row lock").isFalse();
            comment.get(5, TimeUnit.SECONDS);
            assertThat(close.get(5, TimeUnit.SECONDS).status())
                    .isEqualTo(SupportTicketService.Status.CLOSED);
        } finally {
            fixture.jdbc.execute("DROP TRIGGER IF EXISTS trg_pause_support_comment_insert "
                    + "ON platform.support_ticket_comment");
            fixture.jdbc.execute("DROP FUNCTION IF EXISTS platform.pause_support_comment_insert()");
        }
        assertThat(fixture.service.comments(TENANT, created.id()))
                .extracting(SupportTicketService.CommentView::body).containsExactly("Final comment");
    }

    private static void waitForSleepingComment(JdbcTemplate jdbc) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            Long active = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE pid <> pg_backend_pid()
                      AND state='active' AND wait_event='PgSleep'
                      AND query ILIKE '%INSERT INTO platform.support_ticket_comment%'
                    """, Long.class);
            if (active != null && active > 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("comment insert did not reach PostgreSQL trigger");
    }

    private Fixture fixture() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        NotificationEventService events = new NotificationEventService(
                new NotificationEventRepository(jdbc), new ObjectMapper());
        SupportTicketService service = SupportServiceTestFactory.ticket(jdbc, events);
        return new Fixture(jdbc, service,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private record Fixture(JdbcTemplate jdbc, SupportTicketService service, TransactionTemplate tx) { }
}
