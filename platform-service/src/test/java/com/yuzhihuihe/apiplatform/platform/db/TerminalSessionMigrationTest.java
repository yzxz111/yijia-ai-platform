package com.yuzhihuihe.apiplatform.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class TerminalSessionMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void createsFiveDeviceTerminalPolicyAndHandoffState() throws Exception {
        migrate();

        assertThat(columns("terminal_session")).contains(
                "id", "tenant_id", "user_id", "client_type", "device_hash",
                "status", "created_at", "expires_at", "revoked_at", "revocation_reason",
                "browser_close_requested_at");
        assertThat(columns("login_handoff_request")).contains(
                "id", "tenant_id", "user_id", "client_type", "old_terminal_session_id",
                "new_terminal_session_id", "new_device_hash", "request_token_hash", "status",
                "region", "user_agent_summary", "requested_at", "expires_at", "decided_at",
                "decided_by_terminal_session_id", "completed_at");
        assertThat(columns("refresh_token")).contains(
                "terminal_session_id", "client_type", "device_hash", "revocation_reason",
                "csrf_token_hash");
        assertThat(columns("app_user")).contains("max_login_devices");
        assertThat(indexDefinition("uq_terminal_session_active_device"))
                .contains("UNIQUE", "user_id", "client_type", "device_hash", "ACTIVE");
        assertThat(indexDefinition("ix_terminal_session_pending_browser_close"))
                .contains("browser_close_requested_at", "ACTIVE");
        assertThat(constraintDefinition("app_user", "ck_app_user_max_login_devices"))
                .contains("max_login_devices", "1", "10");
        assertThat(indexDefinition("uq_login_handoff_pending_slot"))
                .contains("UNIQUE", "user_id", "client_type", "PENDING");
        assertThat(constraintDefinition("terminal_session", "ck_terminal_session_status"))
                .contains("ACTIVE", "REVOKED", "EXPIRED");
        assertThat(constraintDefinition("login_handoff_request", "ck_login_handoff_status"))
                .contains("PENDING", "APPROVED", "DENIED", "CANCELLED", "SUPERSEDED",
                        "COMPLETED", "EXPIRED");
        assertThat(constraintDefinition("refresh_token", "ck_refresh_token_csrf_hash"))
                .contains("csrf_token_hash", "[0-9a-f]{64}");
    }

    @Test
    void databaseAllowsDistinctActiveDevicesAndRejectsDuplicateDeviceAndPendingHandoffs() throws Exception {
        migrate();
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        execute("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name, status)
                VALUES ('%s', '%s', '%s', 'hash', 'Terminal User', 'ACTIVE')
                """.formatted(userId, tenantId, userId + "@example.test"));

        UUID firstSession = UUID.randomUUID();
        insertActiveSession(firstSession, tenantId, userId, "a".repeat(64));
        insertActiveSession(UUID.randomUUID(), tenantId, userId, "b".repeat(64));
        assertThatThrownBy(() -> insertActiveSession(
                UUID.randomUUID(), tenantId, userId, "a".repeat(64)))
                .isInstanceOf(SQLException.class);

        assertThat(singleString(
                "SELECT max_login_devices::text FROM platform.app_user WHERE id = ?::uuid", userId.toString()))
                .isEqualTo("5");

        insertPendingHandoff(tenantId, userId, firstSession, "c".repeat(64), "d".repeat(64));
        assertThatThrownBy(() -> insertPendingHandoff(
                tenantId, userId, firstSession, "e".repeat(64), "f".repeat(64)))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void revokesRefreshTokensThatPredateCsrfBinding() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID refreshId = UUID.randomUUID();
        try (PostgreSQLContainer<?> upgradeDatabase =
                new PostgreSQLContainer<>("postgres:17-alpine")) {
            upgradeDatabase.start();
            Flyway.configure()
                    .dataSource(upgradeDatabase.getJdbcUrl(), upgradeDatabase.getUsername(),
                            upgradeDatabase.getPassword())
                    .defaultSchema("platform")
                    .schemas("platform")
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("38"))
                    .load()
                    .migrate();
            try (Connection connection = DriverManager.getConnection(
                    upgradeDatabase.getJdbcUrl(), upgradeDatabase.getUsername(),
                    upgradeDatabase.getPassword());
                    var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO platform.app_user(
                            id, tenant_id, email, password_hash, display_name, status)
                        VALUES ('%s', '%s', '%s', 'hash', 'Legacy CSRF User', 'ACTIVE')
                        """.formatted(userId, tenantId, userId + "@example.test"));
                statement.executeUpdate("""
                        INSERT INTO platform.refresh_token(
                            id, tenant_id, user_id, jti, token_hash, expires_at)
                        VALUES ('%s', '%s', '%s', '%s', '%s', now() + interval '1 day')
                        """.formatted(refreshId, tenantId, userId, UUID.randomUUID(),
                                "a".repeat(64)));
            }

            Flyway.configure()
                    .dataSource(upgradeDatabase.getJdbcUrl(), upgradeDatabase.getUsername(),
                            upgradeDatabase.getPassword())
                    .defaultSchema("platform")
                    .schemas("platform")
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection connection = DriverManager.getConnection(
                    upgradeDatabase.getJdbcUrl(), upgradeDatabase.getUsername(),
                    upgradeDatabase.getPassword());
                    var statement = connection.prepareStatement("""
                            SELECT csrf_token_hash, revoked_at, revocation_reason
                            FROM platform.refresh_token
                            WHERE id = ?
                            """)) {
                statement.setObject(1, refreshId);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("csrf_token_hash")).isEqualTo("0".repeat(64));
                    assertThat(rows.getTimestamp("revoked_at")).isNotNull();
                    assertThat(rows.getString("revocation_reason"))
                            .isEqualTo("CSRF_BINDING_REQUIRED");
                }
            }
        }
    }

    private void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private Set<String> columns(String table) throws SQLException {
        Set<String> result = new HashSet<>();
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'platform' AND table_name = ?
                        """)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(rows.getString(1));
                }
            }
        }
        return result;
    }

    private String indexDefinition(String name) throws SQLException {
        return singleString("SELECT indexdef FROM pg_indexes WHERE schemaname = 'platform' AND indexname = ?", name);
    }

    private String constraintDefinition(String table, String name) throws SQLException {
        return singleString("""
                SELECT pg_get_constraintdef(c.oid)
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = 'platform' AND t.relname = ? AND c.conname = ?
                """, table, name);
    }

    private String singleString(String sql, String... parameters) throws SQLException {
        try (Connection connection = connection(); var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private void insertActiveSession(UUID id, UUID tenantId, UUID userId, String deviceHash) throws SQLException {
        execute("""
                INSERT INTO platform.terminal_session(
                    id, tenant_id, user_id, client_type, device_hash, status, expires_at)
                VALUES ('%s', '%s', '%s', 'DESKTOP_WEB', '%s', 'ACTIVE', now() + interval '30 days')
                """.formatted(id, tenantId, userId, deviceHash));
    }

    private void insertPendingHandoff(
            UUID tenantId, UUID userId, UUID oldSessionId, String deviceHash, String requestHash)
            throws SQLException {
        execute("""
                INSERT INTO platform.login_handoff_request(
                    id, tenant_id, user_id, client_type, old_terminal_session_id,
                    new_device_hash, request_token_hash, status, expires_at)
                VALUES ('%s', '%s', '%s', 'DESKTOP_WEB', '%s', '%s', '%s', 'PENDING',
                        now() + interval '10 minutes')
                """.formatted(UUID.randomUUID(), tenantId, userId, oldSessionId, deviceHash, requestHash));
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
