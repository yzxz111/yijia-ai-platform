package com.yuzhihuihe.apiplatform.platform.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 桌面端 OAuth 刷新令牌的持久化。轮换时会写入 {@code replaced_by}，以便 被重放的令牌可追溯到它所属的整个令牌族。 */
@Repository
class OAuthRefreshTokenRepository {

    private static final String COLUMNS =
            """
            id, token_hash, token_family_id, client_id, user_id, tenant_id, device_id,
            terminal_session_id, scope, expires_at, revoked_at, revocation_reason, replaced_by,
            created_at, last_used_at
            """;

    private static final RowMapper<RefreshToken> MAPPER =
            (result, index) ->
                    new RefreshToken(
                            result.getObject("id", UUID.class),
                            result.getString("token_hash"),
                            result.getObject("token_family_id", UUID.class),
                            result.getString("client_id"),
                            result.getObject("user_id", UUID.class),
                            result.getObject("tenant_id", UUID.class),
                            result.getString("device_id"),
                            result.getObject("terminal_session_id", UUID.class),
                            result.getString("scope"),
                            result.getTimestamp("expires_at").toInstant(),
                            instant(result.getTimestamp("revoked_at")),
                            result.getString("revocation_reason"),
                            result.getObject("replaced_by", UUID.class),
                            result.getTimestamp("created_at").toInstant(),
                            instant(result.getTimestamp("last_used_at")));

    private final JdbcTemplate jdbc;

    OAuthRefreshTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(RefreshToken row) {
        jdbc.update(
                """
                INSERT INTO platform.oauth_refresh_tokens(
                    id, token_hash, token_family_id, client_id, user_id, tenant_id, device_id,
                    terminal_session_id, scope, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.id(),
                row.tokenHash(),
                row.tokenFamilyId(),
                row.clientId(),
                row.userId(),
                row.tenantId(),
                row.deviceId(),
                row.terminalSessionId(),
                row.scope(),
                Timestamp.from(row.expiresAt()));
    }

    Optional<RefreshToken> lockByHash(String tokenHash) {
        List<RefreshToken> rows =
                jdbc.query(
                        "SELECT "
                                + COLUMNS
                                + " FROM platform.oauth_refresh_tokens WHERE token_hash = ? FOR UPDATE",
                        MAPPER,
                        tokenHash);
        return rows.stream().findFirst();
    }

    /** 轮换单个令牌：将其吊销并记录后继令牌。 */
    int markRotated(UUID id, UUID replacedBy, Instant revokedAt) {
        return jdbc.update(
                """
                UPDATE platform.oauth_refresh_tokens
                SET revoked_at = ?, revocation_reason = 'ROTATED', replaced_by = ?,
                    last_used_at = ?
                WHERE id = ? AND revoked_at IS NULL
                """,
                Timestamp.from(revokedAt),
                replacedBy,
                Timestamp.from(revokedAt),
                id);
    }

    int touchLastUsed(UUID id, Instant usedAt) {
        return jdbc.update(
                """
                UPDATE platform.oauth_refresh_tokens
                SET last_used_at = ?
                WHERE id = ?
                """,
                Timestamp.from(usedAt),
                id);
    }

    /** 吊销单个令牌（由 /auth/revoke 使用）。 */
    int revoke(UUID id, String reason, Instant revokedAt) {
        return jdbc.update(
                """
                UPDATE platform.oauth_refresh_tokens
                SET revoked_at = ?, revocation_reason = ?
                WHERE id = ? AND revoked_at IS NULL
                """,
                Timestamp.from(revokedAt),
                reason,
                id);
    }

    /** 吊销某个令牌族中所有仍有效的令牌，包括该族的根令牌。 */
    int revokeFamily(UUID familyId, String reason, Instant revokedAt) {
        return jdbc.update(
                """
                UPDATE platform.oauth_refresh_tokens
                SET revoked_at = ?, revocation_reason = ?
                WHERE token_family_id = ? AND revoked_at IS NULL
                """,
                Timestamp.from(revokedAt),
                reason,
                familyId);
    }

    int revokeAllForUserDevice(UUID userId, String deviceId, String reason, Instant revokedAt) {
        return jdbc.update(
                """
                UPDATE platform.oauth_refresh_tokens
                SET revoked_at = ?, revocation_reason = ?
                WHERE user_id = ? AND device_id = ? AND revoked_at IS NULL
                """,
                Timestamp.from(revokedAt),
                reason,
                userId,
                deviceId);
    }

    int deleteRetired(Instant now, Instant revokedCutoff) {
        return jdbc.update(
                """
                DELETE FROM platform.oauth_refresh_tokens
                WHERE expires_at < ?
                   OR (revoked_at IS NOT NULL AND revoked_at < ?)
                """,
                Timestamp.from(now),
                Timestamp.from(revokedCutoff));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    record RefreshToken(
            UUID id,
            String tokenHash,
            UUID tokenFamilyId,
            String clientId,
            UUID userId,
            UUID tenantId,
            String deviceId,
            UUID terminalSessionId,
            String scope,
            Instant expiresAt,
            Instant revokedAt,
            String revocationReason,
            UUID replacedBy,
            Instant createdAt,
            Instant lastUsedAt) {}
}
