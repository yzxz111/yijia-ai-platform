package com.yuzhihuihe.apiplatform.platform.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class LoginHandoffRepository {

    private final JdbcTemplate jdbc;

    LoginHandoffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    int expirePendingRequests(Instant decidedAt, Instant expiredBefore) {
        return jdbc.update(
                """
                UPDATE platform.login_handoff_request
                SET status = 'EXPIRED', decided_at = ?
                WHERE status = 'PENDING' AND expires_at < ?
                """,
                Timestamp.from(decidedAt),
                Timestamp.from(expiredBefore));
    }

    int expireActiveSessions(Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        return jdbc.update(
                """
                UPDATE platform.terminal_session
                SET status = 'EXPIRED', revoked_at = ?, revocation_reason = 'TOKEN_EXPIRED'
                WHERE status = 'ACTIVE' AND expires_at <= ?
                """,
                timestamp,
                timestamp);
    }

    int deleteCompletedRequestsBefore(Instant cutoff) {
        return jdbc.update(
                """
                DELETE FROM platform.login_handoff_request
                WHERE status IN ('DENIED','CANCELLED','SUPERSEDED','COMPLETED','EXPIRED')
                  AND COALESCE(completed_at, decided_at, expires_at) < ?
                """,
                Timestamp.from(cutoff));
    }

    void expireActiveTerminals(UUID userId, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update(
                """
                UPDATE platform.terminal_session
                SET status = 'EXPIRED', revoked_at = ?, revocation_reason = 'TOKEN_EXPIRED'
                WHERE user_id = ? AND client_type = 'DESKTOP_WEB'
                  AND status = 'ACTIVE' AND expires_at <= ?
                """,
                timestamp,
                userId,
                timestamp);
    }

    List<ActiveTerminalRow> lockActiveTerminals(UUID userId) {
        return jdbc.query(
                """
                SELECT id, device_hash FROM platform.terminal_session
                WHERE user_id = ? AND client_type = 'DESKTOP_WEB' AND status = 'ACTIVE'
                ORDER BY created_at
                FOR UPDATE
                """,
                (result, rowNumber) ->
                        new ActiveTerminalRow(
                                result.getObject("id", UUID.class),
                                result.getString("device_hash")),
                userId);
    }

    Integer maxLoginDevices(UUID userId, UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT max_login_devices FROM platform.app_user WHERE id = ? AND tenant_id = ?",
                Integer.class,
                userId,
                tenantId);
    }

    Optional<PendingApprovalRow> findPendingApproval(UUID userId, UUID oldSessionId) {
        return jdbc
                .query(
                        """
                        SELECT id, requested_at, expires_at, region, user_agent_summary
                        FROM platform.login_handoff_request
                        WHERE user_id = ? AND old_terminal_session_id = ?
                          AND client_type = 'DESKTOP_WEB' AND status = 'PENDING'
                        ORDER BY requested_at DESC LIMIT 1
                        """,
                        (result, rowNumber) ->
                                new PendingApprovalRow(
                                        result.getObject("id", UUID.class),
                                        result.getObject("requested_at", OffsetDateTime.class)
                                                .toInstant(),
                                        result.getObject("expires_at", OffsetDateTime.class)
                                                .toInstant(),
                                        result.getString("region"),
                                        result.getString("user_agent_summary")),
                        userId,
                        oldSessionId)
                .stream()
                .findFirst();
    }

    void approve(UUID requestId, UUID newSessionId, Instant decidedAt, UUID decidedBySessionId) {
        jdbc.update(
                """
                UPDATE platform.login_handoff_request
                SET status = 'APPROVED', new_terminal_session_id = ?, decided_at = ?,
                    decided_by_terminal_session_id = ?
                WHERE id = ?
                """,
                newSessionId,
                Timestamp.from(decidedAt),
                decidedBySessionId,
                requestId);
    }

    void deny(UUID requestId, Instant decidedAt, UUID decidedBySessionId) {
        jdbc.update(
                """
                UPDATE platform.login_handoff_request
                SET status = 'DENIED', decided_at = ?, decided_by_terminal_session_id = ?
                WHERE id = ?
                """,
                Timestamp.from(decidedAt),
                decidedBySessionId,
                requestId);
    }

    void expireRequest(UUID requestId, Instant decidedAt) {
        jdbc.update(
                """
                UPDATE platform.login_handoff_request
                SET status = 'EXPIRED', decided_at = ? WHERE id = ?
                """,
                Timestamp.from(decidedAt),
                requestId);
    }

    void approveAfterTimeout(UUID requestId, UUID newSessionId, Instant decidedAt) {
        jdbc.update(
                """
                UPDATE platform.login_handoff_request
                SET status = 'APPROVED', new_terminal_session_id = ?, decided_at = ?
                WHERE id = ?
                """,
                newSessionId,
                Timestamp.from(decidedAt),
                requestId);
    }

    void complete(UUID requestId, Instant completedAt) {
        jdbc.update(
                """
                UPDATE platform.login_handoff_request
                SET status = 'COMPLETED', completed_at = COALESCE(completed_at, ?)
                WHERE id = ?
                """,
                Timestamp.from(completedAt),
                requestId);
    }

    void cancel(UUID requestId, Instant decidedAt) {
        jdbc.update(
                """
                UPDATE platform.login_handoff_request
                SET status = 'CANCELLED', decided_at = ? WHERE id = ?
                """,
                Timestamp.from(decidedAt),
                requestId);
    }

    boolean lockUser(UUID userId, UUID tenantId) {
        List<UUID> rows =
                jdbc.query(
                        """
                SELECT id FROM platform.app_user
                WHERE id = ? AND tenant_id = ? AND status IN ('ACTIVE', 'DISABLED') FOR UPDATE
                """,
                        (result, rowNumber) -> result.getObject(1, UUID.class),
                        userId,
                        tenantId);
        return rows.size() == 1;
    }

    Optional<HandoffRow> findRequest(UUID requestId, boolean lock) {
        String sql =
                """
                SELECT id, tenant_id, user_id, old_terminal_session_id,
                       new_terminal_session_id, new_device_hash, request_token_hash,
                       status, requested_at, expires_at
                FROM platform.login_handoff_request WHERE id = ?
                """
                        + (lock ? " FOR UPDATE" : "");
        return jdbc
                .query(
                        sql,
                        (result, rowNumber) ->
                                new HandoffRow(
                                        result.getObject("id", UUID.class),
                                        result.getObject("tenant_id", UUID.class),
                                        result.getObject("user_id", UUID.class),
                                        result.getObject("old_terminal_session_id", UUID.class),
                                        result.getObject("new_terminal_session_id", UUID.class),
                                        result.getString("new_device_hash"),
                                        result.getString("request_token_hash"),
                                        result.getString("status"),
                                        result.getObject("requested_at", OffsetDateTime.class)
                                                .toInstant(),
                                        result.getObject("expires_at", OffsetDateTime.class)
                                                .toInstant()),
                        requestId)
                .stream()
                .findFirst();
    }

    Optional<UserRow> findUser(UUID userId, UUID tenantId) {
        return jdbc
                .query(
                        """
                        SELECT u.id, u.tenant_id, u.email, u.display_name, u.status,
                               u.password_hash IS NOT NULL AS password_set, u.auth_version, r.code AS role
                        FROM platform.app_user u
                        JOIN platform.user_role ur ON ur.user_id = u.id
                        JOIN platform.app_role r ON r.id = ur.role_id
                        WHERE u.id = ? AND u.tenant_id = ? AND u.status IN ('ACTIVE', 'DISABLED')
                        ORDER BY CASE r.code WHEN 'ADMIN' THEN 0 ELSE 1 END LIMIT 1
                        """,
                        (result, rowNumber) ->
                                new UserRow(
                                        result.getObject("id", UUID.class),
                                        result.getObject("tenant_id", UUID.class),
                                        result.getString("email"),
                                        result.getString("display_name"),
                                        result.getString("role"),
                                        result.getBoolean("password_set"),
                                        result.getString("status"),
                                        result.getLong("auth_version")),
                        userId,
                        tenantId)
                .stream()
                .findFirst();
    }

    record ActiveTerminalRow(UUID id, String deviceHash) {}

    record PendingApprovalRow(
            UUID id,
            Instant requestedAt,
            Instant expiresAt,
            String region,
            String userAgentSummary) {}

    record HandoffRow(
            UUID id,
            UUID tenantId,
            UUID userId,
            UUID oldSessionId,
            UUID newSessionId,
            String newDeviceHash,
            String requestTokenHash,
            String status,
            Instant requestedAt,
            Instant expiresAt) {}

    record UserRow(
            UUID id,
            UUID tenantId,
            String email,
            String displayName,
            String role,
            boolean passwordSet,
            String status,
            long authVersion) {}
}
