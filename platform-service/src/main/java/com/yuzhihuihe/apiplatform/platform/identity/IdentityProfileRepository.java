package com.yuzhihuihe.apiplatform.platform.identity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class IdentityProfileRepository {

    private final JdbcTemplate jdbc;

    IdentityProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<ProfileAccount> findProfile(UUID tenantId, UUID userId) {
        return jdbc
                .query(
                        """
                        SELECT id, tenant_id, user_no, email, display_name, status,
                               last_login_at, created_at, phone_ciphertext, phone_last4,
                               phone_verified_at, sms_notifications_enabled,
                               password_hash IS NOT NULL AS password_set,
                               avatar_updated_at, max_login_devices
                        FROM platform.app_user
                        WHERE tenant_id = ? AND id = ?
                        """,
                        (result, rowNumber) ->
                                new ProfileAccount(
                                        result.getObject("id", UUID.class),
                                        result.getObject("tenant_id", UUID.class),
                                        result.getString("user_no"),
                                        result.getString("email"),
                                        result.getString("display_name"),
                                        result.getString("status"),
                                        toInstant(
                                                result.getObject(
                                                        "last_login_at", OffsetDateTime.class)),
                                        result.getObject("created_at", OffsetDateTime.class)
                                                .toInstant(),
                                        result.getBytes("phone_ciphertext"),
                                        result.getString("phone_last4"),
                                        toInstant(
                                                result.getObject(
                                                        "phone_verified_at", OffsetDateTime.class)),
                                        result.getBoolean("sms_notifications_enabled"),
                                        result.getBoolean("password_set"),
                                        toInstant(
                                                result.getObject(
                                                        "avatar_updated_at", OffsetDateTime.class)),
                                        result.getInt("max_login_devices")),
                        tenantId,
                        userId)
                .stream()
                .findFirst();
    }

    int updateAvatar(UUID tenantId, UUID userId, byte[] bytes, String contentType) {
        return jdbc.update(
                """
                UPDATE platform.app_user
                SET avatar_bytes = ?, avatar_content_type = ?, avatar_updated_at = now(), updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                """,
                bytes,
                contentType,
                tenantId,
                userId);
    }

    Optional<BoundEmail> findBoundEmail(UUID tenantId, UUID userId) {
        return jdbc
                .query(
                        """
                        SELECT email
                        FROM platform.app_user
                        WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                        """,
                        (result, rowNumber) -> new BoundEmail(result.getString("email")),
                        tenantId,
                        userId)
                .stream()
                .findFirst();
    }

    Optional<Avatar> findAvatar(UUID tenantId, UUID userId) {
        return jdbc
                .query(
                        """
                        SELECT avatar_bytes, avatar_content_type
                        FROM platform.app_user
                        WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                        """,
                        (result, rowNumber) ->
                                new Avatar(
                                        result.getBytes("avatar_bytes"),
                                        result.getString("avatar_content_type")),
                        tenantId,
                        userId)
                .stream()
                .filter(
                        content ->
                                content.bytes() != null
                                        && content.bytes().length > 0
                                        && content.contentType() != null)
                .findFirst();
    }

    int updateSmsPreference(UUID tenantId, UUID userId, boolean enabled) {
        return jdbc.update(
                """
                UPDATE platform.app_user
                SET sms_notifications_enabled = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                """,
                enabled,
                tenantId,
                userId);
    }

    void auditSmsPreference(UUID tenantId, UUID userId, boolean enabled) {
        jdbc.update(
                """
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type, resource_id, outcome, detail)
                VALUES (?, ?, 'SMS_PREFERENCE_CHANGED', 'USER', ?, 'SUCCESS',
                        jsonb_build_object('enabled', ?))
                """,
                tenantId,
                userId,
                userId.toString(),
                enabled);
    }

    int updateDisplayName(UUID tenantId, UUID userId, String displayName) {
        return jdbc.update(
                """
                UPDATE platform.app_user
                SET display_name = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                """,
                displayName,
                tenantId,
                userId);
    }

    void auditDisplayName(UUID tenantId, UUID userId) {
        jdbc.update(
                """
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type, resource_id, outcome, detail)
                VALUES (?, ?, 'DISPLAY_NAME_CHANGED', 'USER', ?, 'SUCCESS', '{}'::jsonb)
                """,
                tenantId,
                userId,
                userId.toString());
    }

    int updateLoginDeviceLimit(UUID tenantId, UUID userId, int maxLoginDevices) {
        return jdbc.update(
                """
                UPDATE platform.app_user
                SET max_login_devices = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                """,
                maxLoginDevices,
                tenantId,
                userId);
    }

    void auditLoginDeviceLimit(UUID tenantId, UUID userId, int maxLoginDevices) {
        jdbc.update(
                """
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type, resource_id, outcome, detail)
                VALUES (?, ?, 'LOGIN_DEVICE_LIMIT_CHANGED', 'USER', ?, 'SUCCESS',
                        jsonb_build_object('maxLoginDevices', ?))
                """,
                tenantId,
                userId,
                userId.toString(),
                maxLoginDevices);
    }

    Optional<ProfileAccountState> findAccountState(UUID tenantId, UUID userId) {
        return jdbc
                .query(
                        """
                        SELECT status, disabled_reason, disabled_at
                        FROM platform.app_user
                        WHERE tenant_id = ? AND id = ?
                        """,
                        (result, rowNumber) ->
                                new ProfileAccountState(
                                        result.getString("status"),
                                        result.getString("disabled_reason"),
                                        toInstant(
                                                result.getObject(
                                                        "disabled_at", OffsetDateTime.class))),
                        tenantId,
                        userId)
                .stream()
                .findFirst();
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    record ProfileAccount(
            UUID id,
            UUID tenantId,
            String userNo,
            String email,
            String displayName,
            String status,
            Instant lastLoginAt,
            Instant createdAt,
            byte[] phoneCiphertext,
            String phoneLast4,
            Instant phoneVerifiedAt,
            boolean smsNotificationsEnabled,
            boolean passwordSet,
            Instant avatarUpdatedAt,
            int maxLoginDevices) {}

    record BoundEmail(String email) {}

    record Avatar(byte[] bytes, String contentType) {}

    record ProfileAccountState(String status, String disabledReason, Instant disabledAt) {}
}
