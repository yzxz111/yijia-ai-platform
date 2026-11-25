package com.yuzhihuihe.apiplatform.platform.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 浏览器端 OAuth 登录事务的持久化。 */
@Repository
class OAuthLoginTransactionRepository {

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_AUTHENTICATED = "AUTHENTICATED";
    static final String STATUS_COMPLETED = "COMPLETED";
    static final String STATUS_CANCELLED = "CANCELLED";

    private static final String COLUMNS =
            """
            id, transaction_id, client_id, redirect_uri, device_id, scope, state,
            code_challenge, code_challenge_method, browser_session_id, user_id, tenant_id,
            status, expires_at, created_at, completed_at
            """;

    private static final RowMapper<LoginTransaction> MAPPER =
            (result, index) ->
                    new LoginTransaction(
                            result.getObject("id", UUID.class),
                            result.getString("transaction_id"),
                            result.getString("client_id"),
                            result.getString("redirect_uri"),
                            result.getString("device_id"),
                            result.getString("scope"),
                            result.getString("state"),
                            result.getString("code_challenge"),
                            result.getString("code_challenge_method"),
                            result.getString("browser_session_id"),
                            result.getObject("user_id", UUID.class),
                            result.getObject("tenant_id", UUID.class),
                            result.getString("status"),
                            result.getTimestamp("expires_at").toInstant(),
                            result.getTimestamp("created_at").toInstant(),
                            instant(result.getTimestamp("completed_at")));

    private final JdbcTemplate jdbc;

    OAuthLoginTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(LoginTransaction row) {
        jdbc.update(
                """
                INSERT INTO platform.oauth_login_transactions(
                    id, transaction_id, client_id, redirect_uri, device_id, scope, state,
                    code_challenge, code_challenge_method, browser_session_id, status, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.id(),
                row.transactionId(),
                row.clientId(),
                row.redirectUri(),
                row.deviceId(),
                row.scope(),
                row.state(),
                row.codeChallenge(),
                row.codeChallengeMethod(),
                row.browserSessionId(),
                row.status(),
                Timestamp.from(row.expiresAt()));
    }

    Optional<LoginTransaction> findById(UUID id) {
        return one(
                "SELECT " + COLUMNS + " FROM platform.oauth_login_transactions WHERE id = ?", id);
    }

    Optional<LoginTransaction> lockById(UUID id) {
        return one(
                "SELECT "
                        + COLUMNS
                        + " FROM platform.oauth_login_transactions WHERE id = ? FOR UPDATE",
                id);
    }

    int markAuthenticated(UUID id, UUID userId, UUID tenantId) {
        return jdbc.update(
                """
                UPDATE platform.oauth_login_transactions
                SET status = ?, user_id = ?, tenant_id = ?
                WHERE id = ? AND status = ?
                """,
                STATUS_AUTHENTICATED,
                userId,
                tenantId,
                id,
                STATUS_PENDING);
    }

    int complete(UUID id, Instant completedAt) {
        return jdbc.update(
                """
                UPDATE platform.oauth_login_transactions
                SET status = ?, completed_at = ?
                WHERE id = ? AND status IN (?, ?)
                """,
                STATUS_COMPLETED,
                Timestamp.from(completedAt),
                id,
                STATUS_PENDING,
                STATUS_AUTHENTICATED);
    }

    int cancel(UUID id, Instant completedAt) {
        return jdbc.update(
                """
                UPDATE platform.oauth_login_transactions
                SET status = ?, completed_at = ?
                WHERE id = ? AND status IN (?, ?)
                """,
                STATUS_CANCELLED,
                Timestamp.from(completedAt),
                id,
                STATUS_PENDING,
                STATUS_AUTHENTICATED);
    }

    /** 只删除超出保留窗口的事务，保证失败登录仍可追溯。 */
    int deleteRetired(Instant now, Instant retentionCutoff) {
        return jdbc.update(
                """
                DELETE FROM platform.oauth_login_transactions
                WHERE COALESCE(completed_at, expires_at) < ?
                """,
                Timestamp.from(retentionCutoff));
    }

    private Optional<LoginTransaction> one(String sql, UUID id) {
        List<LoginTransaction> rows = jdbc.query(sql, MAPPER, id);
        return rows.stream().findFirst();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    record LoginTransaction(
            UUID id,
            String transactionId,
            String clientId,
            String redirectUri,
            String deviceId,
            String scope,
            String state,
            String codeChallenge,
            String codeChallengeMethod,
            String browserSessionId,
            UUID userId,
            UUID tenantId,
            String status,
            Instant expiresAt,
            Instant createdAt,
            Instant completedAt) {}
}
