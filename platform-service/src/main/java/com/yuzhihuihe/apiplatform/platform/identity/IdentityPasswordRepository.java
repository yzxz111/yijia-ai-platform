package com.yuzhihuihe.apiplatform.platform.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class IdentityPasswordRepository {

    private final JdbcTemplate jdbc;

    IdentityPasswordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<PasswordAccount> lockActiveAccount(UUID tenantId, UUID userId) {
        return jdbc
                .query(
                        """
                        SELECT password_hash, phone_verified_at IS NOT NULL AS phone_verified
                        FROM platform.app_user
                        WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                        FOR UPDATE
                        """,
                        (result, rowNumber) ->
                                new PasswordAccount(
                                        result.getString("password_hash"),
                                        result.getBoolean("phone_verified")),
                        tenantId,
                        userId)
                .stream()
                .findFirst();
    }

    List<PasswordResetAccount> lockActiveAccountsByEmail(String normalizedEmail) {
        return jdbc.query(
                """
                SELECT tenant_id, id
                FROM platform.app_user
                WHERE lower(email) = ? AND status = 'ACTIVE'
                LIMIT 2
                FOR UPDATE
                """,
                (result, rowNumber) ->
                        new PasswordResetAccount(
                                result.getObject("tenant_id", UUID.class),
                                result.getObject("id", UUID.class)),
                normalizedEmail);
    }

    int updatePassword(UUID tenantId, UUID userId, String passwordHash) {
        return jdbc.update(
                """
                UPDATE platform.app_user
                SET password_hash = ?, auth_version = auth_version + 1, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """,
                passwordHash,
                tenantId,
                userId);
    }

    int updateActivePassword(UUID tenantId, UUID userId, String passwordHash) {
        return jdbc.update(
                """
                UPDATE platform.app_user
                SET password_hash = ?, auth_version = auth_version + 1, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                """,
                passwordHash,
                tenantId,
                userId);
    }

    void auditPasswordChanged(UUID tenantId, UUID userId, boolean smsProtected) {
        jdbc.update(
                """
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type, resource_id, outcome, detail)
                VALUES (?, ?, 'PASSWORD_CHANGED', 'USER', ?, 'SUCCESS',
                        jsonb_build_object('smsProtected', ?))
                """,
                tenantId,
                userId,
                userId.toString(),
                smsProtected);
    }

    void auditPasswordReset(UUID tenantId, UUID userId, String verificationChannel) {
        jdbc.update(
                """
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type, resource_id, outcome, detail)
                VALUES (?, ?, 'PASSWORD_RESET', 'USER', ?, 'SUCCESS',
                        jsonb_build_object('verificationChannel', ?))
                """,
                tenantId,
                userId,
                userId.toString(),
                verificationChannel);
    }

    record PasswordAccount(String passwordHash, boolean phoneVerified) {}

    record PasswordResetAccount(UUID tenantId, UUID userId) {}
}
