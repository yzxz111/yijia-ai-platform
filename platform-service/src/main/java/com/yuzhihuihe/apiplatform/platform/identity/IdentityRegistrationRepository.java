package com.yuzhihuihe.apiplatform.platform.identity;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class IdentityRegistrationRepository {

    private final JdbcTemplate jdbc;

    IdentityRegistrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean emailExists(String normalizedEmail) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM platform.app_user WHERE lower(email) = ?)",
                        Boolean.class,
                        normalizedEmail));
    }

    boolean phoneExists(String phoneLookupHash) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM platform.app_user WHERE phone_lookup_hash = ?)",
                        Boolean.class,
                        phoneLookupHash));
    }

    void createPasswordAccount(
            UUID userId,
            UUID tenantId,
            String normalizedEmail,
            String passwordHash,
            String displayName,
            byte[] phoneCiphertext,
            String phoneLookupHash,
            String phoneLast4) {
        jdbc.update(
                """
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status,
                    phone_ciphertext, phone_lookup_hash, phone_last4, phone_verified_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, now())
                """,
                userId,
                tenantId,
                normalizedEmail,
                passwordHash,
                displayName,
                phoneCiphertext,
                phoneLookupHash,
                phoneLast4);
        initializeSafetyEnforcementCache(userId, tenantId);
    }

    void createPhoneAccount(
            UUID userId,
            UUID tenantId,
            String displayName,
            byte[] phoneCiphertext,
            String phoneLookupHash,
            String phoneLast4) {
        jdbc.update(
                """
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status,
                    phone_ciphertext, phone_lookup_hash, phone_last4, phone_verified_at,
                    last_login_at)
                VALUES (?, ?, NULL, NULL, ?, 'ACTIVE', ?, ?, ?, now(), now())
                """,
                userId,
                tenantId,
                displayName,
                phoneCiphertext,
                phoneLookupHash,
                phoneLast4);
        initializeSafetyEnforcementCache(userId, tenantId);
    }

    void createEmailPasswordAccount(
            UUID userId, UUID tenantId, String normalizedEmail, String passwordHash) {
        jdbc.update(
                """
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status, last_login_at)
                VALUES (?, ?, ?, ?, '邮箱用户', 'ACTIVE', now())
                """,
                userId, tenantId, normalizedEmail, passwordHash);
        initializeSafetyEnforcementCache(userId, tenantId);
    }

    void assignUserRole(UUID tenantId, UUID userId) {
        Long roleId =
                jdbc.queryForObject(
                        "SELECT id FROM platform.app_role WHERE code = 'USER'", Long.class);
        jdbc.update(
                """
                INSERT INTO platform.user_role(tenant_id, user_id, role_id)
                VALUES (?, ?, ?)
                """,
                tenantId,
                userId,
                roleId);
    }

    private void initializeSafetyEnforcementCache(UUID userId, UUID tenantId) {
        jdbc.update(
                """
                INSERT INTO gateway.safety_enforcement_cache(
                    id, tenant_id, user_id, state, reason_code,
                    event_version, effective_at, updated_at)
                VALUES (?, ?, ?, 'CLEARED', 'account_initialized', 1, now(), now())
                ON CONFLICT (tenant_id, user_id) DO NOTHING
                """,
                UUID.randomUUID(),
                tenantId,
                userId);
    }
}
