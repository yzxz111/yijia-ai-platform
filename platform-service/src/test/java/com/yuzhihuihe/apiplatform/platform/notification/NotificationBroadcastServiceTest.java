package com.yuzhihuihe.apiplatform.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

class NotificationBroadcastServiceTest {
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
    private final NotificationBroadcastService service = new NotificationBroadcastService(
            new NotificationBroadcastRepository(jdbc), new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    private UUID actor;
    private UUID tenant;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE platform.notification_broadcast_recipient, "
                + "platform.notification_broadcast, platform.notification_event, "
                + "platform.notification_preference, platform.admin_audit_log, "
                + "platform.app_user CASCADE");
        tenant = UUID.randomUUID();
        actor = user(tenant, "actor", "ACTIVE");
    }

    @Test
    void scheduleFreezesAllActiveTenantUsersAndLaterUsersDoNotReceive() {
        UUID first = user(tenant, "first", "ACTIVE");
        user(tenant, "disabled", "DISABLED");
        user(UUID.randomUUID(), "other-tenant", "ACTIVE");
        NotificationBroadcastService.BroadcastView draft = service.create(command(
                tenant, NotificationBroadcastService.AudienceKind.ALL_ACTIVE_USERS, List.of()), actor);

        NotificationBroadcastService.BroadcastView scheduled =
                service.schedule(draft.id(), NOW.plusSeconds(300), actor);
        UUID createdAfterSchedule = user(tenant, "late", "ACTIVE");

        assertThat(scheduled.status()).isEqualTo("SCHEDULED");
        assertThat(service.detail(draft.id()).recipientCount()).isEqualTo(2);
        assertThat(recipientIds(draft.id())).containsExactlyInAnyOrder(actor, first)
                .doesNotContain(createdAfterSchedule);
        assertThatThrownBy(() -> service.edit(draft.id(), edit("Changed"), actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("broadcast_not_editable");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE resource_id=? AND action IN ('NOTIFICATION_BROADCAST_CREATED',
                    'NOTIFICATION_BROADCAST_SCHEDULED')
                """, Integer.class, draft.id().toString())).isEqualTo(2);
    }

    @Test
    void publishFreezesCurrentRecipientsAndMakesThemImmediatelyEligible() {
        UUID first = user(tenant, "first-publish", "ACTIVE");
        user(tenant, "disabled-publish", "DISABLED");
        NotificationBroadcastService.BroadcastView draft = service.create(command(
                tenant, NotificationBroadcastService.AudienceKind.ALL_ACTIVE_USERS, List.of()), actor);

        NotificationBroadcastService.BroadcastView published = service.publish(draft.id(), actor);
        UUID createdAfterPublish = user(tenant, "late-publish", "ACTIVE");

        assertThat(published.status()).isEqualTo("SCHEDULED");
        assertThat(published.scheduledAt()).isEqualTo(NOW);
        assertThat(recipientIds(draft.id())).containsExactlyInAnyOrder(actor, first)
                .doesNotContain(createdAfterPublish);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.notification_broadcast_recipient
                WHERE broadcast_id=? AND next_attempt_at=?
                """, Integer.class, draft.id(), java.sql.Timestamp.from(NOW))).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE resource_id=? AND action='NOTIFICATION_BROADCAST_PUBLISHED'
                """, Integer.class, draft.id().toString())).isOne();
    }

    @Test
    void explicitAudienceRequiresExistingActiveUsersInsideTheBroadcastTenant() {
        UUID active = user(tenant, "active", "ACTIVE");
        UUID disabled = user(tenant, "disabled-explicit", "DISABLED");
        UUID outside = user(UUID.randomUUID(), "outside", "ACTIVE");

        assertThatThrownBy(() -> service.create(command(
                tenant, NotificationBroadcastService.AudienceKind.USER_IDS,
                List.of(active, disabled)), actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_broadcast_audience");
        assertThatThrownBy(() -> service.create(command(
                tenant, NotificationBroadcastService.AudienceKind.USER_IDS,
                List.of(active, outside)), actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_broadcast_audience");
        assertThatThrownBy(() -> service.create(command(
                tenant, NotificationBroadcastService.AudienceKind.USER_IDS,
                List.of(active, UUID.randomUUID())), actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_broadcast_audience");
    }

    @Test
    void draftCanBeEditedAndScheduledBroadcastCanPauseResumeOrCancel() {
        UUID target = user(tenant, "target", "ACTIVE");
        NotificationBroadcastService.BroadcastView draft = service.create(command(
                tenant, NotificationBroadcastService.AudienceKind.USER_IDS, List.of(target)), actor);

        NotificationBroadcastService.BroadcastView edited =
                service.edit(draft.id(), edit("Updated maintenance"), actor);
        service.schedule(draft.id(), NOW.plusSeconds(120), actor);
        NotificationBroadcastService.BroadcastView paused = service.pause(draft.id(), actor);
        NotificationBroadcastService.BroadcastView resumed =
                service.resume(draft.id(), NOW.plusSeconds(240), actor);
        NotificationBroadcastService.BroadcastView canceled = service.cancel(draft.id(), actor);

        assertThat(edited.body()).isEqualTo("Updated maintenance");
        assertThat(paused.status()).isEqualTo("SCHEDULED");
        assertThat(paused.paused()).isTrue();
        assertThat(resumed.paused()).isFalse();
        assertThat(resumed.scheduledAt()).isEqualTo(NOW.plusSeconds(240));
        assertThat(canceled.status()).isEqualTo("CANCELED");
        assertThatThrownBy(() -> service.resume(draft.id(), NOW.plusSeconds(300), actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_broadcast_transition");
    }

    @Test
    void validatesMaintenanceFieldsAndPaginatesDetailsWithoutSensitiveAuditValues() {
        assertThatThrownBy(() -> service.create(new NotificationBroadcastService.CreateCommand(
                " ", "body", "tomorrow", "business-1", tenant,
                NotificationBroadcastService.AudienceKind.ALL_ACTIVE_USERS, List.of()), actor))
                .isInstanceOf(ResponseStatusException.class);

        NotificationBroadcastService.BroadcastView generated = service.create(
                new NotificationBroadcastService.CreateCommand(
                        "title", "body", "tomorrow", " ", tenant,
                        NotificationBroadcastService.AudienceKind.ALL_ACTIVE_USERS, List.of()),
                actor);
        assertThat(generated.providerBusinessNo())
                .matches("broadcast-[0-9a-f-]{36}");
        assertThat(jdbc.queryForObject("SELECT provider_business_no FROM "
                + "platform.notification_broadcast WHERE id=?", String.class, generated.id()))
                .isEqualTo(generated.providerBusinessNo());

        NotificationBroadcastService.BroadcastView explicit = service.create(command(tenant,
                NotificationBroadcastService.AudienceKind.ALL_ACTIVE_USERS, List.of()), actor);
        assertThat(explicit.providerBusinessNo()).isEqualTo("provider-business-001");

        assertThatThrownBy(() -> service.create(
                new NotificationBroadcastService.CreateCommand(
                        "title", "body", "tomorrow", "provider-business-001", tenant,
                        NotificationBroadcastService.AudienceKind.ALL_ACTIVE_USERS, List.of()),
                actor))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(409);
                    assertThat(error.getReason())
                            .isEqualTo("notification_broadcast_business_no_conflict");
                });
        NotificationBroadcastService.PageResult page = service.list(0, 20, "DRAFT");

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(2);
        List<String> audits = jdbc.queryForList("SELECT detail::text FROM "
                + "platform.admin_audit_log WHERE action='NOTIFICATION_BROADCAST_CREATED'",
                String.class);
        assertThat(audits).allSatisfy(audit -> assertThat(audit)
                .doesNotContain("Maintenance body", "maintainTime", "providerBusinessNo"));
    }

    @Test
    void scheduleAndResumeRequireTimeStrictlyAfterInjectedClock() {
        NotificationBroadcastService.BroadcastView draft = service.create(command(
                tenant, NotificationBroadcastService.AudienceKind.ALL_ACTIVE_USERS, List.of()), actor);

        assertThatThrownBy(() -> service.schedule(draft.id(), null, actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_broadcast_schedule");
        assertThatThrownBy(() -> service.schedule(draft.id(), NOW.minusSeconds(1), actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_broadcast_schedule");
        assertThatThrownBy(() -> service.schedule(draft.id(), NOW, actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_broadcast_schedule");
        assertThat(service.detail(draft.id()).status()).isEqualTo("DRAFT");

        service.schedule(draft.id(), NOW.plusSeconds(1), actor);
        service.pause(draft.id(), actor);
        assertThatThrownBy(() -> service.resume(draft.id(), null, actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_broadcast_schedule");
        assertThatThrownBy(() -> service.resume(draft.id(), NOW.minusSeconds(1), actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_broadcast_schedule");
        assertThatThrownBy(() -> service.resume(draft.id(), NOW, actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_broadcast_schedule");
        assertThat(service.detail(draft.id()).paused()).isTrue();
    }

    private NotificationBroadcastService.CreateCommand command(
            UUID scope, NotificationBroadcastService.AudienceKind kind, List<UUID> users) {
        return new NotificationBroadcastService.CreateCommand(
                "Planned maintenance", "Maintenance body", "2026-08-09 18:00-18:30",
                "provider-business-001", scope, kind, users);
    }

    private NotificationBroadcastService.EditCommand edit(String body) {
        return new NotificationBroadcastService.EditCommand(
                "Planned maintenance", body, "2026-08-09 18:00-18:30",
                "provider-business-002", tenant,
                NotificationBroadcastService.AudienceKind.ALL_ACTIVE_USERS, List.of());
    }

    private UUID user(UUID tenantId, String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.app_user(id,tenant_id,email,password_hash,display_name,status)
                VALUES (?,?,?,'hash',?,?)
                """, id, tenantId, id + "@example.invalid", name, status);
        return id;
    }

    private List<UUID> recipientIds(UUID broadcastId) {
        return jdbc.queryForList("SELECT user_id FROM platform.notification_broadcast_recipient "
                + "WHERE broadcast_id=? ORDER BY user_id", UUID.class, broadcastId);
    }
}
