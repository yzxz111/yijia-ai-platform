package com.yuzhihuihe.apiplatform.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

class NotificationQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T04:00:00Z");
    private final JdbcTemplate jdbc = NotificationTestDatabase.jdbc();
    private final NotificationQueryService service = new NotificationQueryService(
            new NotificationQueryRepository(jdbc), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void reset() {
        NotificationTestDatabase.reset();
    }

    @Test
    void paginatesAndFiltersActualRowsWhileExcludingArchivedAndOtherScopes() {
        Identity owner = identity();
        Identity sameTenantOtherUser = identity(owner.tenantId());
        Identity otherTenant = identity();
        UUID expected = notification(owner, "BILLING", false, false, "2026-08-09T03:00:00Z");
        notification(owner, "BILLING", true, false, "2026-08-09T02:00:00Z");
        notification(owner, "BILLING", false, true, "2026-08-09T01:00:00Z");
        notification(owner, "SECURITY", false, false, "2026-08-09T00:00:00Z");
        notification(sameTenantOtherUser, "BILLING", false, false, "2026-08-09T03:30:00Z");
        notification(otherTenant, "BILLING", false, false, "2026-08-09T03:45:00Z");

        var page = service.list(owner.tenantId(), owner.userId(), "billing", false, 0, 1);

        assertThat(page.total()).isOne();
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(expected);
            assertThat(item.category()).isEqualTo("BILLING");
            assertThat(item.title()).isEqualTo("BILLING title");
            assertThat(item.readAt()).isNull();
            assertThat(item.archivedAt()).isNull();
        });
        assertThat(service.unreadCount(owner.tenantId(), owner.userId())).isEqualTo(2);
    }

    @Test
    void repeatedMutationsAreNoOpsAndCannotCrossTenantOrUserScope() {
        Identity owner = identity();
        Identity foreignOwner = identity();
        UUID first = notification(owner, "USAGE", false, false, "2026-08-09T01:00:00Z");
        UUID second = notification(owner, "SUPPORT", false, false, "2026-08-09T02:00:00Z");
        UUID foreign = notification(foreignOwner, "SECURITY", false, false, "2026-08-09T03:00:00Z");

        service.markRead(owner.tenantId(), owner.userId(), first);
        service.markRead(owner.tenantId(), owner.userId(), first);
        service.markRead(owner.tenantId(), owner.userId(), foreign);
        assertThat(service.unreadCount(owner.tenantId(), owner.userId())).isOne();
        assertThat(isNull("read_at", foreign)).isTrue();

        service.markAllRead(owner.tenantId(), owner.userId());
        service.markAllRead(owner.tenantId(), owner.userId());
        assertThat(service.unreadCount(owner.tenantId(), owner.userId())).isZero();

        service.archive(owner.tenantId(), owner.userId(), second);
        service.archive(owner.tenantId(), owner.userId(), second);
        service.archive(owner.tenantId(), owner.userId(), foreign);
        assertThat(service.list(owner.tenantId(), owner.userId(), null, null, 0, 20).total()).isOne();
        assertThat(isNull("archived_at", foreign)).isTrue();
    }

    @Test
    void rejectsInvalidFiltersAndPageOffsetOverflowBeforePageQuery() {
        Identity owner = identity();
        assertThatThrownBy(() -> service.list(owner.tenantId(), owner.userId(), null, null, -1, 20))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_notification_page");
        assertThatThrownBy(() -> service.list(owner.tenantId(), owner.userId(), "UNKNOWN", null, 0, 20))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_notification_category");
        assertThatThrownBy(() -> service.list(owner.tenantId(), owner.userId(), null, null,
                        Integer.MAX_VALUE, 100))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_notification_page");
    }

    private Identity identity() {
        return identity(UUID.randomUUID());
    }

    private Identity identity(UUID tenant) {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO platform.app_user(id,tenant_id,email,password_hash,display_name) VALUES (?,?,?,'x','user')",
                user, tenant, user + "@example.invalid");
        return new Identity(tenant, user);
    }

    private UUID notification(Identity identity, String category, boolean read, boolean archived,
            String createdAt) {
        UUID event = UUID.randomUUID();
        UUID notification = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.notification_event(
                    id,event_key,category,event_type,tenant_id,user_id,
                    aggregate_type,aggregate_id,payload,occurred_at)
                VALUES (?,?,?,'TEST_EVENT',?,?,'TEST',?,'{}'::jsonb,?::timestamptz)
                """, event, "event:" + event, category, identity.tenantId(), identity.userId(),
                event.toString(), createdAt);
        jdbc.update("""
                INSERT INTO platform.in_app_notification(
                    id,event_id,tenant_id,user_id,title,summary,read_at,archived_at,created_at)
                VALUES (?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz)
                """, notification, event, identity.tenantId(), identity.userId(),
                category + " title", category + " summary", read ? createdAt : null,
                archived ? createdAt : null, createdAt);
        return notification;
    }

    private boolean isNull(String column, UUID id) {
        if (!"read_at".equals(column) && !"archived_at".equals(column)) {
            throw new IllegalArgumentException("unsupported column");
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT " + column + " IS NULL FROM platform.in_app_notification WHERE id=?",
                Boolean.class, id));
    }

    record Identity(UUID tenantId, UUID userId) { }
}

final class NotificationTestDatabase {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final DataSource DATA_SOURCE;
    private static final JdbcTemplate JDBC;

    static {
        POSTGRES.start();
        DATA_SOURCE = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(DATA_SOURCE).defaultSchema("platform").schemas("platform")
                .locations("classpath:db/migration").load().migrate();
        JDBC = new JdbcTemplate(DATA_SOURCE);
    }

    private NotificationTestDatabase() { }

    static JdbcTemplate jdbc() { return JDBC; }

    static DataSource dataSource() { return DATA_SOURCE; }

    static void reset() {
        JDBC.execute("TRUNCATE platform.admin_audit_log, platform.in_app_notification, "
                + "platform.notification_preference, platform.notification_event, "
                + "platform.app_user CASCADE");
    }
}
