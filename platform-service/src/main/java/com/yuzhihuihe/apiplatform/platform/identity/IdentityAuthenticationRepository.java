package com.yuzhihuihe.apiplatform.platform.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class IdentityAuthenticationRepository {

    private final JdbcTemplate jdbc;

    IdentityAuthenticationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<AuthenticationUser> findPasswordUser(
            LoginIdentifierType identifierType, String lookupValue) {
        String predicate =
                switch (identifierType) {
                    case EMAIL -> "lower(u.email)";
                    case PHONE -> "u.phone_lookup_hash";
                };
        return jdbc
                .query(
                        ("""
                        SELECT u.id, u.tenant_id, u.email, u.display_name, u.password_hash,
                               u.status, u.auth_version, r.code AS role
                        FROM platform.app_user u
                        JOIN platform.user_role ur ON ur.user_id = u.id
                        JOIN platform.app_role r ON r.id = ur.role_id
                        WHERE %s = ?
                        ORDER BY CASE r.code WHEN 'ADMIN' THEN 0 ELSE 1 END
                        LIMIT 1
                        """)
                                .formatted(predicate),
                        this::authenticationUser,
                        lookupValue)
                .stream()
                .findFirst();
    }

    Optional<AuthenticationUser> lockPhoneUser(String phoneLookupHash) {
        return jdbc
                .query(
                        """
                        SELECT u.id, u.tenant_id, u.email, u.display_name, u.password_hash,
                               u.status, u.auth_version, r.code AS role
                        FROM platform.app_user u
                        JOIN platform.user_role ur ON ur.user_id = u.id
                        JOIN platform.app_role r ON r.id = ur.role_id
                        WHERE u.phone_lookup_hash = ? AND u.phone_verified_at IS NOT NULL
                        ORDER BY CASE r.code WHEN 'ADMIN' THEN 0 ELSE 1 END
                        LIMIT 1
                        FOR UPDATE OF u
                        """,
                        this::authenticationUser,
                        phoneLookupHash)
                .stream()
                .findFirst();
    }

    Optional<AuthenticationUser> lockEmailUser(String normalizedEmail) {
        return jdbc
                .query(
                        """
                        SELECT u.id, u.tenant_id, u.email, u.display_name, u.password_hash,
                               u.status, u.auth_version, r.code AS role
                        FROM platform.app_user u
                        JOIN platform.user_role ur ON ur.user_id = u.id
                        JOIN platform.app_role r ON r.id = ur.role_id
                        WHERE lower(u.email) = ?
                        ORDER BY CASE r.code WHEN 'ADMIN' THEN 0 ELSE 1 END
                        LIMIT 1
                        FOR UPDATE OF u
                        """,
                        this::authenticationUser,
                        normalizedEmail)
                .stream()
                .findFirst();
    }

    Optional<AuthenticationUser> findActiveUser(UUID tenantId, UUID userId) {
        return jdbc
                .query(
                        """
                        SELECT u.id, u.tenant_id, u.email, u.display_name, u.password_hash,
                               u.status, u.auth_version, r.code AS role
                        FROM platform.app_user u
                        JOIN platform.user_role ur ON ur.user_id = u.id
                        JOIN platform.app_role r ON r.id = ur.role_id
                        WHERE u.tenant_id = ? AND u.id = ? AND u.status = 'ACTIVE'
                        ORDER BY CASE r.code WHEN 'ADMIN' THEN 0 ELSE 1 END
                        LIMIT 1
                        """,
                        this::authenticationUser,
                        tenantId,
                        userId)
                .stream()
                .findFirst();
    }

    Optional<AuthenticationUser> lockSmsLoginUser(UUID challengeId, UUID userId, UUID tenantId) {
        return jdbc
                .query(
                        """
                        SELECT u.id, u.tenant_id, u.email, u.display_name, u.password_hash,
                               u.status, u.auth_version, r.code AS role
                        FROM platform.app_user u
                        JOIN platform.user_role ur ON ur.user_id = u.id
                        JOIN platform.app_role r ON r.id = ur.role_id
                        JOIN platform.sms_verification_challenge challenge
                          ON challenge.tenant_id = u.tenant_id AND challenge.user_id = u.id
                        WHERE challenge.id = ? AND challenge.purpose = 'LOGIN'
                          AND challenge.verified_at IS NOT NULL AND challenge.consumed_at IS NOT NULL
                          AND u.id = ? AND u.tenant_id = ?
                          AND u.status IN ('ACTIVE', 'DISABLED')
                          AND u.phone_verified_at IS NOT NULL
                          AND u.phone_lookup_hash = challenge.phone_lookup_hash
                        ORDER BY CASE r.code WHEN 'ADMIN' THEN 0 ELSE 1 END
                        LIMIT 1
                        FOR UPDATE OF u
                        """,
                        this::authenticationUser,
                        challengeId,
                        userId,
                        tenantId)
                .stream()
                .findFirst();
    }

    void updateLastLogin(UUID userId) {
        jdbc.update("UPDATE platform.app_user SET last_login_at = now() WHERE id = ?", userId);
    }

    private AuthenticationUser authenticationUser(ResultSet result, int rowNumber)
            throws SQLException {
        return new AuthenticationUser(
                result.getObject("id", UUID.class),
                result.getObject("tenant_id", UUID.class),
                result.getString("email"),
                result.getString("display_name"),
                result.getString("role"),
                result.getString("password_hash"),
                result.getString("status"),
                result.getLong("auth_version"));
    }

    enum LoginIdentifierType {
        EMAIL,
        PHONE
    }

    record AuthenticationUser(
            UUID id,
            UUID tenantId,
            String email,
            String displayName,
            String role,
            String passwordHash,
            String status,
            long authVersion) {}
}
