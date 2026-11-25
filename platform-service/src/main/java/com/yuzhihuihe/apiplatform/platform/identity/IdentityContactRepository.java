package com.yuzhihuihe.apiplatform.platform.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class IdentityContactRepository {

    private final JdbcTemplate jdbc;

    IdentityContactRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<UUID> lockActiveUser(UUID tenantId, UUID userId) {
        return jdbc
                .query(
                        """
                        SELECT id FROM platform.app_user
                        WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                        FOR UPDATE
                        """,
                        (result, rowNumber) -> result.getObject("id", UUID.class),
                        tenantId,
                        userId)
                .stream()
                .findFirst();
    }

    boolean emailUsedByAnotherUser(String normalizedEmail, UUID userId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                SELECT EXISTS (
                    SELECT 1 FROM platform.app_user WHERE lower(email) = ? AND id <> ?
                )
                """,
                        Boolean.class,
                        normalizedEmail,
                        userId));
    }

    void bindEmail(UUID tenantId, UUID userId, String normalizedEmail) {
        jdbc.update(
                """
                UPDATE platform.app_user SET email = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """,
                normalizedEmail,
                tenantId,
                userId);
    }

    Optional<Boolean> lockPhoneBindingState(UUID tenantId, UUID userId) {
        return jdbc
                .query(
                        """
                        SELECT phone_verified_at IS NOT NULL
                        FROM platform.app_user
                        WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                        FOR UPDATE
                        """,
                        (result, rowNumber) -> result.getBoolean(1),
                        tenantId,
                        userId)
                .stream()
                .findFirst();
    }

    void bindPhone(
            UUID tenantId,
            UUID userId,
            byte[] phoneCiphertext,
            String phoneLookupHash,
            String phoneLast4) {
        jdbc.update(
                """
                UPDATE platform.app_user
                SET phone_ciphertext = ?, phone_lookup_hash = ?, phone_last4 = ?,
                    phone_verified_at = now(), updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """,
                phoneCiphertext,
                phoneLookupHash,
                phoneLast4,
                tenantId,
                userId);
    }

    void auditPhoneBinding(UUID tenantId, UUID userId, String action, String maskedPhone) {
        jdbc.update(
                """
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type, resource_id, outcome, detail)
                VALUES (?, ?, ?, 'USER_PHONE', ?, 'SUCCESS',
                        jsonb_build_object('maskedPhone', ?))
                """,
                tenantId,
                userId,
                action,
                userId.toString(),
                maskedPhone);
    }
}
