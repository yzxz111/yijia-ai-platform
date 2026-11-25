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
class TerminalSessionRepository {

    private final JdbcTemplate jdbc;

    TerminalSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean lockUser(IdentityService.UserProfile user) {
        return jdbc.query(
                                """
                SELECT id FROM platform.app_user
                WHERE id = ? AND tenant_id = ? AND status IN ('ACTIVE', 'DISABLED')
                FOR UPDATE
                """,
                                (result, rowNumber) -> result.getObject(1, UUID.class),
                                user.id(),
                                user.tenantId())
                        .size()
                == 1;
    }

    int revokeTerminalForReplacement(
            UUID terminalSessionId, UUID tenantId, UUID userId, String reason) {
        return jdbc.update(
                """
                UPDATE platform.terminal_session
                SET status = 'REVOKED', revoked_at = now(), revocation_reason = ?
                WHERE id = ? AND tenant_id = ? AND user_id = ?
                  AND client_type = 'DESKTOP_WEB' AND status = 'ACTIVE'
                """,
                reason,
                terminalSessionId,
                tenantId,
                userId);
    }

    void revokeRefreshesForTerminal(UUID terminalSessionId, String reason) {
        jdbc.update(
                """
                UPDATE platform.refresh_token
                SET revoked_at = COALESCE(revoked_at, now()),
                    revocation_reason = COALESCE(revocation_reason, ?)
                WHERE terminal_session_id = ? AND revoked_at IS NULL
                """,
                reason,
                terminalSessionId);
    }

    boolean activeTerminalMatches(
            UUID terminalSessionId,
            UUID tenantId,
            UUID userId,
            String clientType,
            String deviceHash) {
        Integer matches =
                jdbc.queryForObject(
                        """
                SELECT count(*) FROM platform.terminal_session
                WHERE id = ? AND tenant_id = ? AND user_id = ? AND client_type = ?
                  AND device_hash = ? AND status = 'ACTIVE' AND expires_at > now()
                """,
                        Integer.class,
                        terminalSessionId,
                        tenantId,
                        userId,
                        clientType,
                        deviceHash);
        return matches != null && matches == 1;
    }

    boolean requestBrowserClose(
            UUID terminalSessionId,
            UUID tenantId,
            UUID userId,
            String clientType,
            String deviceHash,
            Instant requestedAt) {
        return jdbc.update(
                        """
                UPDATE platform.terminal_session
                SET browser_close_requested_at = ?
                WHERE id = ? AND tenant_id = ? AND user_id = ? AND client_type = ?
                  AND device_hash = ? AND status = 'ACTIVE' AND expires_at > now()
                """,
                        Timestamp.from(requestedAt),
                        terminalSessionId,
                        tenantId,
                        userId,
                        clientType,
                        deviceHash)
                == 1;
    }

    boolean continueActiveTerminal(
            UUID terminalSessionId,
            UUID tenantId,
            UUID userId,
            String clientType,
            String deviceHash) {
        return jdbc.update(
                        """
                UPDATE platform.terminal_session
                SET browser_close_requested_at = NULL
                WHERE id = ? AND tenant_id = ? AND user_id = ? AND client_type = ?
                  AND device_hash = ? AND status = 'ACTIVE' AND expires_at > now()
                """,
                        terminalSessionId,
                        tenantId,
                        userId,
                        clientType,
                        deviceHash)
                == 1;
    }

    Optional<RefreshTokenRow> lockRefreshByTokenHash(String tokenHash) {
        return refreshByTokenHash(tokenHash, true);
    }

    Optional<RefreshTokenRow> findRefreshByTokenHash(String tokenHash) {
        return refreshByTokenHash(tokenHash, false);
    }

    private Optional<RefreshTokenRow> refreshByTokenHash(String tokenHash, boolean lock) {
        return jdbc
                .query(
                        """
                SELECT rt.id AS refresh_id, rt.expires_at, rt.revoked_at, rt.revocation_reason,
                       rt.csrf_token_hash,
                       rt.terminal_session_id, rt.client_type, rt.device_hash,
                       u.id, u.tenant_id, u.email, u.display_name,
                       u.password_hash IS NOT NULL AS password_set, u.status, u.auth_version,
                       r.code AS role
                FROM platform.refresh_token rt
                JOIN platform.app_user u ON u.id = rt.user_id
                JOIN platform.user_role ur ON ur.user_id = u.id
                JOIN platform.app_role r ON r.id = ur.role_id
                WHERE rt.token_hash = ? AND u.status IN ('ACTIVE', 'DISABLED')
                ORDER BY CASE r.code WHEN 'ADMIN' THEN 0 ELSE 1 END
                LIMIT 1
                """ + (lock ? " FOR UPDATE OF rt" : ""),
                        (result, rowNumber) ->
                                new RefreshTokenRow(
                                        result.getObject("refresh_id", UUID.class),
                                        result.getObject("expires_at", OffsetDateTime.class)
                                                .toInstant(),
                                        result.getObject("revoked_at", OffsetDateTime.class),
                                        result.getString("revocation_reason"),
                                        result.getString("csrf_token_hash"),
                                        result.getObject("terminal_session_id", UUID.class),
                                        result.getString("client_type"),
                                        result.getString("device_hash"),
                                        new IdentityService.UserProfile(
                                                result.getObject("id", UUID.class),
                                                result.getObject("tenant_id", UUID.class),
                                                result.getString("email"),
                                                result.getString("display_name"),
                                                result.getString("role"),
                                                result.getBoolean("password_set"),
                                                result.getString("status"),
                                                result.getLong("auth_version"))),
                        tokenHash)
                .stream()
                .findFirst();
    }

    void updateTerminalExpiry(UUID terminalSessionId, Instant expiresAt) {
        jdbc.update(
                "UPDATE platform.terminal_session SET expires_at = ? WHERE id = ?",
                Timestamp.from(expiresAt),
                terminalSessionId);
    }

    Optional<LogoutTokenRow> lockLogoutByTokenHash(String tokenHash) {
        return jdbc
                .query(
                        """
                SELECT terminal_session_id, csrf_token_hash, expires_at, revoked_at
                FROM platform.refresh_token
                WHERE token_hash = ?
                FOR UPDATE
                """,
                        (result, rowNumber) ->
                                new LogoutTokenRow(
                                        result.getObject("terminal_session_id", UUID.class),
                                        result.getString("csrf_token_hash"),
                                        result.getObject("expires_at", OffsetDateTime.class)
                                                .toInstant(),
                                        result.getObject("revoked_at", OffsetDateTime.class)),
                        tokenHash)
                .stream()
                .findFirst();
    }

    void revokeLegacyRefreshByTokenHash(String tokenHash) {
        jdbc.update(
                """
                UPDATE platform.refresh_token
                SET revoked_at = now(), revocation_reason = 'LOGOUT'
                WHERE token_hash = ? AND revoked_at IS NULL
                """,
                tokenHash);
    }

    void revokeTerminal(UUID terminalSessionId, String reason) {
        jdbc.update(
                """
                UPDATE platform.terminal_session
                SET status = 'REVOKED', revoked_at = now(), revocation_reason = ?
                WHERE id = ? AND status = 'ACTIVE'
                """,
                reason,
                terminalSessionId);
    }

    void revokeAll(UUID tenantId, UUID userId, String reason) {
        jdbc.update(
                """
                UPDATE platform.terminal_session
                SET status = 'REVOKED', revoked_at = now(), revocation_reason = ?
                WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'
                """,
                reason,
                tenantId,
                userId);
        jdbc.update(
                """
                UPDATE platform.refresh_token
                SET revoked_at = COALESCE(revoked_at, now()),
                    revocation_reason = COALESCE(revocation_reason, ?)
                WHERE tenant_id = ? AND user_id = ? AND revoked_at IS NULL
                """,
                reason,
                tenantId,
                userId);
    }

    int countActiveTerminals(UUID userId) {
        Integer active =
                jdbc.queryForObject(
                        """
                SELECT count(*) FROM platform.terminal_session
                WHERE user_id = ? AND client_type = 'DESKTOP_WEB' AND status = 'ACTIVE'
                """,
                        Integer.class,
                        userId);
        return active == null ? 0 : active;
    }

    int countActiveDesktopDevices(UUID tenantId, UUID userId) {
        Integer active =
                jdbc.queryForObject(
                        """
                SELECT count(*) FROM platform.terminal_session
                WHERE tenant_id = ? AND user_id = ? AND client_type = 'DESKTOP_WEB'
                  AND status = 'ACTIVE' AND expires_at > now()
                """,
                        Integer.class,
                        tenantId,
                        userId);
        return active == null ? 0 : active;
    }

    int revokeBrowserClosedTerminals(Instant requestedBefore, Instant revokedAt) {
        Timestamp revokedTimestamp = Timestamp.from(revokedAt);
        int revoked =
                jdbc.update(
                        """
                UPDATE platform.terminal_session
                SET status = 'REVOKED', revoked_at = ?, revocation_reason = 'BROWSER_CLOSED'
                WHERE status = 'ACTIVE' AND browser_close_requested_at IS NOT NULL
                  AND browser_close_requested_at <= ?
                """,
                        revokedTimestamp,
                        Timestamp.from(requestedBefore));
        if (revoked == 0) return 0;
        jdbc.update(
                """
                UPDATE platform.refresh_token rt
                SET revoked_at = COALESCE(rt.revoked_at, ?),
                    revocation_reason = COALESCE(rt.revocation_reason, 'BROWSER_CLOSED')
                FROM platform.terminal_session terminal
                WHERE rt.terminal_session_id = terminal.id
                  AND terminal.status = 'REVOKED'
                  AND terminal.revocation_reason = 'BROWSER_CLOSED'
                  AND terminal.revoked_at = ?
                  AND rt.revoked_at IS NULL
                """,
                revokedTimestamp,
                revokedTimestamp);
        return revoked;
    }

    List<SessionRow> recentSessions(UUID tenantId, UUID userId) {
        return jdbc.query(
                """
                SELECT id, created_at, expires_at, revoked_at, status
                FROM platform.terminal_session
                WHERE tenant_id = ? AND user_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT 50
                """,
                (result, rowNumber) ->
                        new SessionRow(
                                result.getObject("id", UUID.class),
                                result.getString("status"),
                                result.getObject("created_at", OffsetDateTime.class).toInstant(),
                                result.getObject("expires_at", OffsetDateTime.class).toInstant(),
                                toInstant(result.getObject("revoked_at", OffsetDateTime.class))),
                tenantId,
                userId);
    }

    void revokeLegacyRefreshes(UUID userId) {
        jdbc.update(
                """
                UPDATE platform.refresh_token
                SET revoked_at = COALESCE(revoked_at, now()),
                    revocation_reason = 'LEGACY_SESSION_REPLACED'
                WHERE user_id = ? AND terminal_session_id IS NULL AND revoked_at IS NULL
                """,
                userId);
    }

    UUID insertTerminal(IdentityService.UserProfile user, String deviceHash, Instant expiresAt) {
        UUID terminalSessionId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO platform.terminal_session(
                    id, tenant_id, user_id, client_type, device_hash, status, expires_at)
                VALUES (?, ?, ?, 'DESKTOP_WEB', ?, 'ACTIVE', ?)
                """,
                terminalSessionId,
                user.tenantId(),
                user.id(),
                deviceHash,
                Timestamp.from(expiresAt));
        return terminalSessionId;
    }

    void insertRefresh(
            IdentityService.UserProfile user,
            UUID refreshId,
            String jti,
            String tokenHash,
            Instant expiresAt,
            UUID terminalSessionId,
            String deviceHash,
            String csrfTokenHash) {
        jdbc.update(
                """
                INSERT INTO platform.refresh_token(
                    id, tenant_id, user_id, jti, token_hash, expires_at,
                    terminal_session_id, client_type, device_hash, csrf_token_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'DESKTOP_WEB', ?, ?)
                """,
                refreshId,
                user.tenantId(),
                user.id(),
                jti,
                tokenHash,
                Timestamp.from(expiresAt),
                terminalSessionId,
                deviceHash,
                csrfTokenHash);
    }

    void revokeRefresh(UUID refreshId, String reason) {
        jdbc.update(
                """
                UPDATE platform.refresh_token
                SET revoked_at = COALESCE(revoked_at, now()), revocation_reason = ?
                WHERE id = ?
                """,
                reason,
                refreshId);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    record RefreshTokenRow(
            UUID id,
            Instant expiresAt,
            OffsetDateTime revokedAt,
            String revocationReason,
            String csrfTokenHash,
            UUID terminalSessionId,
            String clientType,
            String deviceHash,
            IdentityService.UserProfile user) {}

    record LogoutTokenRow(
            UUID terminalSessionId,
            String csrfTokenHash,
            Instant expiresAt,
            OffsetDateTime revokedAt) {}

    record SessionRow(
            UUID id, String status, Instant createdAt, Instant expiresAt, Instant revokedAt) {}
}
