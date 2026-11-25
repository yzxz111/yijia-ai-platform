package com.yuzhihuihe.apiplatform.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class NotificationCenterMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final PostgreSQLContainer<?> BACKFILL_POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final PostgreSQLContainer<?> LOCALIZATION_POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void createsNotificationCenterTablesAndIndexes() throws SQLException {
        assertThat(tableNames()).contains(
                "notification_event", "in_app_notification", "notification_preference",
                "notification_channel_delivery", "notification_trigger_state",
                "login_activity", "trusted_login_device", "notification_broadcast",
                "notification_broadcast_recipient", "support_ticket", "support_ticket_comment");
        assertThat(tableNames()).contains("support_ticket_attachment");

        assertThat(columnNames("notification_event")).contains(
                "event_key", "category", "event_type", "tenant_id", "user_id",
                "aggregate_type", "aggregate_id", "payload", "status",
                "claim_token", "claimed_until", "attempt_count");
        assertThat(indexDefinition("uq_notification_event_key")).contains("UNIQUE", "event_key");
        assertThat(indexDefinition("uq_in_app_notification_event_user"))
                .contains("UNIQUE", "event_id", "user_id");
        assertThat(indexDefinition("ix_in_app_notification_user_created"))
                .contains("user_id", "created_at DESC");
        assertThat(indexDefinition("ix_in_app_notification_user_read"))
                .contains("user_id", "read_at");
        assertThat(indexDefinition("ix_in_app_notification_user_archived"))
                .contains("user_id", "archived_at", "created_at DESC");
        assertThat(indexDefinition("uq_notification_channel_delivery_event_user_channel"))
                .contains("UNIQUE", "event_id", "user_id", "channel");
        assertThat(columnNames("notification_broadcast")).contains(
                "event_type", "maintain_time", "provider_business_no", "audience_type",
                "tenant_id", "job_claim_token", "job_claimed_until", "paused_at");
        assertThat(indexDefinition("uq_notification_broadcast_provider_business_no"))
                .contains("UNIQUE", "provider_business_no");
        assertThat(constraintDefinition("ck_notification_broadcast_status"))
                .contains("DRAFT", "SCHEDULED", "SENDING", "COMPLETED", "CANCELED")
                .doesNotContain("CANCELLED");
        assertThat(constraintDefinition("ck_notification_broadcast_event_type"))
                .contains("PLATFORM_MAINTENANCE");
        assertThat(constraintDefinition("ck_notification_broadcast_audience_type"))
                .contains("ALL_ACTIVE_USERS", "USER_IDS");
        assertThat(columnNames("notification_broadcast_recipient")).contains(
                "attempt_count", "next_attempt_at", "job_claim_token", "job_claimed_until",
                "last_error");
        assertThat(constraintDefinition("ck_notification_broadcast_recipient_status"))
                .contains("PENDING", "PROCESSING", "COMPLETED", "FAILED", "DEAD", "CANCELED");
        assertThat(indexDefinition("ix_notification_broadcast_claim"))
                .contains("status", "scheduled_at", "job_claimed_until");
        assertThat(indexDefinition("ix_notification_broadcast_recipient_claim"))
                .contains("broadcast_id", "status", "next_attempt_at", "job_claimed_until");
        assertThat(constraintDefinition("ck_wallet_usage_reservation_billing_mode"))
                .contains("PACKAGE_ONLY", "HYBRID", "WALLET_ONLY", "UNKNOWN");
    }

    @Test
    void rejectsDuplicateBroadcastProviderBusinessNumber() throws SQLException {
        executeUpdate("""
                INSERT INTO platform.notification_broadcast(
                    broadcast_key, title, summary, maintain_time, provider_business_no)
                VALUES ('broadcast:unique-business-no:first', 'Notice', 'First broadcast',
                    '2026-08-16 18:00', 'provider-business-no-duplicate')
                """);

        assertSqlRejected("""
                INSERT INTO platform.notification_broadcast(
                    broadcast_key, title, summary, maintain_time, provider_business_no)
                VALUES ('broadcast:unique-business-no:second', 'Notice', 'Second broadcast',
                    '2026-08-16 19:00', 'provider-business-no-duplicate')
                """, "uq_notification_broadcast_provider_business_no");
    }

    @Test
    void enforcesSupportTicketLifecycleAndOwnershipContract() throws SQLException {
        assertThat(columnNames("support_ticket")).contains(
                "ticket_number", "tenant_id", "user_id", "assignee_user_id", "subject",
                "description", "status", "resolution", "completion_business_no", "completed_at");
        assertThat(columnType("support_ticket", "subject")).isEqualTo("character varying(120)");
        assertThat(constraintDefinition("ck_support_ticket_status"))
                .contains("OPEN", "PROCESSING", "COMPLETED", "CLOSED")
                .doesNotContain("IN_PROGRESS", "WAITING_USER");
        assertThat(constraintDefinition("ck_support_ticket_text"))
                .contains("subject", "120", "description", "5000");
        assertThat(constraintDefinition("ck_support_ticket_completion"))
                .contains("resolution", "completion_business_no", "completed_at", "COMPLETED", "CLOSED");
        assertThat(constraintDefinition("fk_support_ticket_assignee"))
                .contains("assignee_user_id")
                .doesNotContain("tenant_id");
        assertThat(indexDefinition("ix_support_ticket_tenant_created"))
                .contains("tenant_id", "created_at DESC");
        assertThat(indexDefinition("ix_support_ticket_tenant_status_updated"))
                .contains("tenant_id", "status", "updated_at DESC");

        assertThat(columnNames("support_ticket_comment")).contains(
                "ticket_id", "tenant_id", "author_user_id", "author_role", "body");
        assertThat(constraintDefinition("ck_support_ticket_comment_author_role"))
                .contains("USER", "ADMIN");
        assertThat(constraintDefinition("ck_support_ticket_comment_body"))
                .contains("5000");
        assertThat(constraintDefinition("fk_support_ticket_comment_author"))
                .contains("author_user_id")
                .doesNotContain("tenant_id");
        assertThat(columnNames("support_ticket_attachment")).contains(
                "ticket_id", "tenant_id", "comment_id", "uploader_user_id",
                "filename", "content_type", "size_bytes", "content");
        assertThat(constraintDefinition("ck_support_ticket_attachment_size"))
                .contains("5242880", "octet_length");
        assertThat(constraintDefinition("ck_content_safety_appeal_status"))
                .contains("PENDING", "APPROVED", "REJECTED", "WITHDRAWN");
    }

    @Test
    void allowsGlobalSupportTicketAssigneeFromAnotherTenant() throws SQLException {
        insertOwnershipFixtures();
        executeUpdate("""
                INSERT INTO platform.support_ticket(
                    ticket_number, tenant_id, user_id, assignee_user_id, subject, description)
                VALUES ('TK-CROSS-TENANT',
                    '30000000-0000-0000-0000-000000000011',
                    '30000000-0000-0000-0000-000000000001',
                    '30000000-0000-0000-0000-000000000002', 'Subject', 'Body')
                """);
    }

    @Test
    void protectsEventBusinessFieldsAndBroadcastRecipientSnapshots() throws SQLException {
        insertOwnershipFixtures();
        executeUpdate("""
                INSERT INTO platform.notification_event(
                    event_key, category, event_type, aggregate_type, aggregate_id, payload)
                VALUES ('event:immutable', 'SECURITY', 'LOGIN_WARNING', 'USER', 'user-1',
                    '{"region":"Shanghai"}'::jsonb)
                """);
        assertSqlRejected("""
                UPDATE platform.notification_event SET event_type='CHANGED'
                WHERE event_key='event:immutable'
                """, "notification_event_business_fields_immutable");
        assertSqlRejected("""
                DELETE FROM platform.notification_event WHERE event_key='event:immutable'
                """, "notification_event_business_fields_immutable");

        executeUpdate("""
                INSERT INTO platform.notification_broadcast(
                    broadcast_key, title, summary, maintain_time, provider_business_no)
                VALUES ('broadcast:immutable', 'Notice', 'Maintenance',
                    '2026-08-09 18:00', 'broadcast-business-immutable')
                """);
        executeUpdate("""
                INSERT INTO platform.notification_broadcast_recipient(
                    broadcast_id, tenant_id, user_id, recipient_snapshot, notification_event_id)
                SELECT broadcast.id, app_user.tenant_id, app_user.id,
                    '{"displayName":"Snapshot"}'::jsonb, notification_event.id
                FROM platform.notification_broadcast broadcast
                JOIN platform.app_user app_user ON app_user.email='owner-a@example.com'
                JOIN platform.notification_event notification_event
                  ON notification_event.event_key='event:owner-a'
                WHERE broadcast.broadcast_key='broadcast:immutable'
                """);
        assertSqlRejected("""
                UPDATE platform.notification_broadcast_recipient
                SET recipient_snapshot='{"displayName":"Changed"}'::jsonb
                WHERE user_id='30000000-0000-0000-0000-000000000001'
                """, "notification_broadcast_recipient_snapshot_immutable");
    }

    @Test
    void rejectsNotificationChildrenAndRecipientsOutsideEventOwnership() throws SQLException {
        insertOwnershipFixtures();
        executeUpdate("""
                INSERT INTO platform.notification_broadcast(
                    broadcast_key, title, summary, maintain_time, provider_business_no)
                VALUES ('broadcast:ownership', 'Notice', 'Ownership checks',
                    '2026-08-09 18:00', 'broadcast-business-ownership')
                """);

        assertSqlRejected(inAppSql(
                "30000000-0000-0000-0000-000000000099",
                "30000000-0000-0000-0000-000000000011",
                "30000000-0000-0000-0000-000000000001"));
        assertSqlRejected(inAppSql(
                "30000000-0000-0000-0000-000000000001",
                "30000000-0000-0000-0000-000000000012",
                "30000000-0000-0000-0000-000000000002"),
                "fk_in_app_notification_event_scope");
        assertSqlRejected(inAppSql(
                "30000000-0000-0000-0000-000000000001",
                "30000000-0000-0000-0000-000000000011",
                "30000000-0000-0000-0000-000000000099"));

        assertSqlRejected(channelDeliverySql(
                "30000000-0000-0000-0000-000000000099",
                "30000000-0000-0000-0000-000000000011",
                "30000000-0000-0000-0000-000000000001"));
        assertSqlRejected(channelDeliverySql(
                "30000000-0000-0000-0000-000000000001",
                "30000000-0000-0000-0000-000000000012",
                "30000000-0000-0000-0000-000000000002"),
                "fk_notification_channel_delivery_event_scope");
        assertSqlRejected(channelDeliverySql(
                "30000000-0000-0000-0000-000000000001",
                "30000000-0000-0000-0000-000000000011",
                "30000000-0000-0000-0000-000000000099"));

        assertSqlRejected(broadcastRecipientSql(
                "30000000-0000-0000-0000-000000000011",
                "30000000-0000-0000-0000-000000000099", null),
                "fk_notification_broadcast_recipient_user");
        assertSqlRejected(broadcastRecipientSql(
                "30000000-0000-0000-0000-000000000011",
                "30000000-0000-0000-0000-000000000002", null),
                "fk_notification_broadcast_recipient_user");
        assertSqlRejected(broadcastRecipientSql(
                "30000000-0000-0000-0000-000000000012",
                "30000000-0000-0000-0000-000000000002",
                "30000000-0000-0000-0000-000000000001"),
                "fk_notification_broadcast_recipient_event_scope");

        executeUpdate(broadcastRecipientSql(
                "30000000-0000-0000-0000-000000000011",
                "30000000-0000-0000-0000-000000000001",
                "30000000-0000-0000-0000-000000000001"));
    }

    @Test
    void recursivelyRejectsSensitiveNotificationPayloadKeys() throws SQLException {
        executeUpdate(eventSql("event:benign-nested", """
                {"context":{"region":"Shanghai","risk":{"level":"LOW"}},"tags":["login"]}
                """));

        List<String> forbiddenKeys = List.of(
                "phone", "mobile", "password", "token", "accessToken", "refreshToken",
                "apiKey", "secret", "prompt", "response", "content", "attachment",
                "fileBytes", "rawIp", "ipAddress", "userAgent", "deviceId");
        for (int index = 0; index < forbiddenKeys.size(); index++) {
            String forbiddenKey = forbiddenKeys.get(index);
            assertSqlRejected(eventSql(
                    "event:nested-sensitive-" + index,
                    """
                            {"context":{"items":[{"%s":"secret"}]}}
                            """.formatted(forbiddenKey)),
                    "ck_notification_event_payload");
        }
    }

    @Test
    void initializesRequiredPreferencesForExistingUsers() throws SQLException {
        migrateThrough(BACKFILL_POSTGRES, "20");
        executeUpdate(BACKFILL_POSTGRES, """
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, sms_notifications_enabled)
                VALUES
                    ('20000000-0000-0000-0000-000000000001',
                     '20000000-0000-0000-0000-000000000011', 'sms-on@example.com', 'hash', 'SMS On', true),
                    ('20000000-0000-0000-0000-000000000002',
                     '20000000-0000-0000-0000-000000000012', 'sms-off@example.com', 'hash', 'SMS Off', false)
                """);

        migrateThrough(BACKFILL_POSTGRES, "21");

        assertThat(singleLong(BACKFILL_POSTGRES,
                "SELECT count(*) FROM platform.notification_preference"))
                .isEqualTo(20);
        assertThat(singleLong(BACKFILL_POSTGRES, """
                SELECT count(*) FROM platform.notification_preference
                WHERE channel='IN_APP' AND enabled
                  AND quiet_start='22:00' AND quiet_end='08:00' AND timezone='Asia/Shanghai'
                """)).isEqualTo(10);
        assertThat(singleLong(BACKFILL_POSTGRES, """
                SELECT count(*) FROM platform.notification_preference
                WHERE category='SECURITY' AND channel='SMS' AND enabled
                """)).isEqualTo(2);
        assertThat(singleLong(BACKFILL_POSTGRES, """
                SELECT count(*) FROM platform.notification_preference
                WHERE category='OPERATIONS' AND channel='SMS' AND NOT enabled
                """)).isEqualTo(2);
        assertThat(singleLong(BACKFILL_POSTGRES, """
                SELECT count(*) FROM platform.notification_preference preference
                JOIN platform.app_user app_user ON app_user.id=preference.user_id
                WHERE preference.channel='SMS'
                  AND preference.category IN ('BILLING','USAGE','SUPPORT')
                  AND preference.enabled=app_user.sms_notifications_enabled
                """)).isEqualTo(6);
        assertThat(allColumnNames()).noneMatch(name -> name.contains("low_balance_threshold"));
    }

    @Test
    void localizesExistingBuiltInNotificationsWithoutOverwritingBroadcastContent() throws SQLException {
        migrateThrough(LOCALIZATION_POSTGRES, "25");
        executeUpdate(LOCALIZATION_POSTGRES, """
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name)
                VALUES ('40000000-0000-0000-0000-000000000001',
                        '40000000-0000-0000-0000-000000000011',
                        'localized@example.com', 'hash', 'Localized User')
                """);
        executeUpdate(LOCALIZATION_POSTGRES, """
                INSERT INTO platform.notification_event(
                    id, event_key, category, event_type, tenant_id, user_id,
                    aggregate_type, aggregate_id, payload)
                VALUES
                    ('40000000-0000-0000-0000-000000000021', 'event:account-frozen',
                     'SECURITY', 'ACCOUNT_FROZEN',
                     '40000000-0000-0000-0000-000000000011',
                     '40000000-0000-0000-0000-000000000001', 'USER', 'localized-user',
                     '{"reasonCode":"RISK","caseNo":"CASE-1"}'::jsonb),
                    ('40000000-0000-0000-0000-000000000022', 'event:custom-maintenance',
                     'OPERATIONS', 'PLATFORM_MAINTENANCE',
                     '40000000-0000-0000-0000-000000000011',
                     '40000000-0000-0000-0000-000000000001', 'PLATFORM', 'maintenance',
                     '{"title":"Custom notice","summary":"Keep this exact text", "maintainTime":"soon", "broadcastKey":"custom"}'::jsonb)
                """);
        executeUpdate(LOCALIZATION_POSTGRES, """
                INSERT INTO platform.in_app_notification(
                    event_id, tenant_id, user_id, title, summary)
                VALUES
                    ('40000000-0000-0000-0000-000000000021',
                     '40000000-0000-0000-0000-000000000011',
                     '40000000-0000-0000-0000-000000000001',
                     'Account frozen', 'Your account was frozen for security review'),
                    ('40000000-0000-0000-0000-000000000022',
                     '40000000-0000-0000-0000-000000000011',
                     '40000000-0000-0000-0000-000000000001',
                     'Custom notice', 'Keep this exact text')
                """);

        migrateThrough(LOCALIZATION_POSTGRES, "26");

        assertThat(singleString(LOCALIZATION_POSTGRES, """
                SELECT title FROM platform.in_app_notification
                WHERE event_id='40000000-0000-0000-0000-000000000021'
                """)).isEqualTo("账户已冻结");
        assertThat(singleString(LOCALIZATION_POSTGRES, """
                SELECT summary FROM platform.in_app_notification
                WHERE event_id='40000000-0000-0000-0000-000000000021'
                """)).contains("安全审核");
        assertThat(singleString(LOCALIZATION_POSTGRES, """
                SELECT title FROM platform.in_app_notification
                WHERE event_id='40000000-0000-0000-0000-000000000022'
                """)).isEqualTo("Custom notice");
    }

    @Test
    void storesOnlyHashedOrHmacLoginIdentifiers() throws SQLException {
        assertThat(columnNames("login_activity")).contains(
                "ip_hmac", "subnet_hmac", "device_hash", "user_agent_hash", "region");
        assertThat(columnNames("trusted_login_device")).contains(
                "ip_hmac", "subnet_hmac", "device_hash", "user_agent_hash", "region");
        assertThat(columnNames("login_activity")).doesNotContain(
                "ip", "ip_address", "user_agent", "device", "device_id");
        assertThat(columnNames("trusted_login_device")).doesNotContain(
                "ip", "ip_address", "user_agent", "device", "device_id");
    }

    private static void insertOwnershipFixtures() throws SQLException {
        executeUpdate("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name)
                VALUES
                    ('30000000-0000-0000-0000-000000000001',
                     '30000000-0000-0000-0000-000000000011',
                     'owner-a@example.com', 'hash', 'Owner A'),
                    ('30000000-0000-0000-0000-000000000002',
                     '30000000-0000-0000-0000-000000000012',
                     'owner-b@example.com', 'hash', 'Owner B')
                ON CONFLICT (id) DO NOTHING
                """);
        executeUpdate("""
                INSERT INTO platform.notification_event(
                    id, event_key, category, event_type, tenant_id, user_id,
                    aggregate_type, aggregate_id)
                VALUES
                    ('30000000-0000-0000-0000-000000000001', 'event:owner-a',
                     'SECURITY', 'LOGIN_WARNING',
                     '30000000-0000-0000-0000-000000000011',
                     '30000000-0000-0000-0000-000000000001', 'USER', 'owner-a'),
                    ('30000000-0000-0000-0000-000000000002', 'event:owner-b',
                     'SECURITY', 'LOGIN_WARNING',
                     '30000000-0000-0000-0000-000000000012',
                     '30000000-0000-0000-0000-000000000002', 'USER', 'owner-b')
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private static String inAppSql(String eventId, String tenantId, String userId) {
        return """
                INSERT INTO platform.in_app_notification(
                    event_id, tenant_id, user_id, title, summary)
                VALUES ('%s', '%s', '%s', 'Title', 'Summary')
                """.formatted(eventId, tenantId, userId);
    }

    private static String channelDeliverySql(String eventId, String tenantId, String userId) {
        return """
                INSERT INTO platform.notification_channel_delivery(
                    event_id, tenant_id, user_id, channel)
                VALUES ('%s', '%s', '%s', 'IN_APP')
                """.formatted(eventId, tenantId, userId);
    }

    private static String broadcastRecipientSql(
            String tenantId, String userId, String notificationEventId) {
        String eventId = notificationEventId == null ? "NULL" : "'%s'".formatted(notificationEventId);
        return """
                INSERT INTO platform.notification_broadcast_recipient(
                    broadcast_id, tenant_id, user_id, recipient_snapshot, notification_event_id)
                SELECT id, '%s', '%s', '{"displayName":"Snapshot"}'::jsonb, %s
                FROM platform.notification_broadcast WHERE broadcast_key='broadcast:ownership'
                """.formatted(tenantId, userId, eventId);
    }

    private static String eventSql(String eventKey, String payload) {
        return """
                INSERT INTO platform.notification_event(
                    event_key, category, event_type, aggregate_type, aggregate_id, payload)
                VALUES ('%s', 'SECURITY', 'LOGIN_WARNING', 'USER', 'payload-test', '%s'::jsonb)
                """.formatted(eventKey, payload.strip());
    }

    private static void migrateThrough(PostgreSQLContainer<?> postgres, String version) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    private static void executeUpdate(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void executeUpdate(PostgreSQLContainer<?> postgres, String sql) throws SQLException {
        try (Connection connection = connection(postgres); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void assertSqlRejected(String sql, String expectedMessage) {
        assertThatThrownBy(() -> executeUpdate(sql))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(expectedMessage);
    }

    private static void assertSqlRejected(String sql) {
        assertThatThrownBy(() -> executeUpdate(sql)).isInstanceOf(SQLException.class);
    }

    private static Set<String> tableNames() throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = connection();
                ResultSet result = connection.getMetaData().getTables(null, "platform", "%", new String[] {"TABLE"})) {
            while (result.next()) {
                names.add(result.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private static Set<String> columnNames(String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = connection(); var statement = connection.prepareStatement("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema='platform' AND table_name=?
                """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) names.add(result.getString(1));
            }
        }
        return names;
    }

    private static Set<String> allColumnNames() throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = connection(); var statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT column_name FROM information_schema.columns WHERE table_schema='platform'
                        """)) {
            while (result.next()) names.add(result.getString(1));
        }
        return names;
    }

    private static String indexDefinition(String index) throws SQLException {
        try (Connection connection = connection(); var statement = connection.prepareStatement("""
                SELECT indexdef FROM pg_indexes WHERE schemaname='platform' AND indexname=?
                """)) {
            statement.setString(1, index);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("index platform.%s exists", index).isTrue();
                return result.getString(1);
            }
        }
    }

    private static String constraintDefinition(String constraint) throws SQLException {
        try (Connection connection = connection(); var statement = connection.prepareStatement("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE connamespace='platform'::regnamespace AND conname=?
                """)) {
            statement.setString(1, constraint);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("constraint platform.%s exists", constraint).isTrue();
                return result.getString(1);
            }
        }
    }

    private static String columnType(String table, String column) throws SQLException {
        try (Connection connection = connection(); var statement = connection.prepareStatement("""
                SELECT format_type(a.atttypid, a.atttypmod)
                FROM pg_attribute a
                WHERE a.attrelid=('platform.' || ?)::regclass AND a.attname=?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private static long singleLong(PostgreSQLContainer<?> postgres, String sql) throws SQLException {
        try (Connection connection = connection(postgres); var statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static String singleString(PostgreSQLContainer<?> postgres, String sql) throws SQLException {
        try (Connection connection = connection(postgres); var statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static Connection connection() throws SQLException {
        return connection(POSTGRES);
    }

    private static Connection connection(PostgreSQLContainer<?> postgres) throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
