package com.yuzhihuihe.apiplatform.platform.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 一次性桌面授权码的持久化。只存储 SHA-256 摘要。 */
@Repository
class OAuthAuthorizationCodeRepository {

    private static final String COLUMNS =
            """
            id, code_hash, client_id, user_id, tenant_id, redirect_uri, code_challenge,
            code_challenge_method, device_id, scope, expires_at, consumed_at, created_at
            """;

    private static final RowMapper<AuthorizationCode> MAPPER =
            (result, index) ->
                    new AuthorizationCode(
                            result.getObject("id", UUID.class),
                            result.getString("code_hash"),
                            result.getString("client_id"),
                            result.getObject("user_id", UUID.class),
                            result.getObject("tenant_id", UUID.class),
                            result.getString("redirect_uri"),
                            result.getString("code_challenge"),
                            result.getString("code_challenge_method"),
                            result.getString("device_id"),
                            result.getString("scope"),
                            result.getTimestamp("expires_at").toInstant(),
                            instant(result.getTimestamp("consumed_at")),
                            result.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    OAuthAuthorizationCodeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(AuthorizationCode row) {
        jdbc.update(
                """
                INSERT INTO platform.oauth_authorization_codes(
                    id, code_hash, client_id, user_id, tenant_id, redirect_uri, code_challenge,
                    code_challenge_method, device_id, scope, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.id(),
                row.codeHash(),
                row.clientId(),
                row.userId(),
                row.tenantId(),
                row.redirectUri(),
                row.codeChallenge(),
                row.codeChallengeMethod(),
                row.deviceId(),
                row.scope(),
                Timestamp.from(row.expiresAt()));
    }

    Optional<AuthorizationCode> lockByHash(String codeHash) {
        List<AuthorizationCode> rows =
                jdbc.query(
                        "SELECT "
                                + COLUMNS
                                + " FROM platform.oauth_authorization_codes WHERE code_hash = ? FOR UPDATE",
                        MAPPER,
                        codeHash);
        return rows.stream().findFirst();
    }

    /** 将该授权码标记为已消费。若另一事务已消费它则返回 0。 */
    int consume(UUID id, Instant consumedAt) {
        return jdbc.update(
                """
                UPDATE platform.oauth_authorization_codes
                SET consumed_at = ?
                WHERE id = ? AND consumed_at IS NULL
                """,
                Timestamp.from(consumedAt),
                id);
    }

    /** 只删除超出保留窗口的记录。过期（含未消费即过期）的授权码先留一段时间， 否则失败的兑换尝试会立刻失去可追溯证据。 */
    int deleteExpired(Instant now, Instant retentionCutoff) {
        return jdbc.update(
                """
                DELETE FROM platform.oauth_authorization_codes
                WHERE COALESCE(consumed_at, expires_at) < ?
                """,
                Timestamp.from(retentionCutoff));
    }

    record AuthorizationCode(
            UUID id,
            String codeHash,
            String clientId,
            UUID userId,
            UUID tenantId,
            String redirectUri,
            String codeChallenge,
            String codeChallengeMethod,
            String deviceId,
            String scope,
            Instant expiresAt,
            Instant consumedAt,
            Instant createdAt) {}

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
