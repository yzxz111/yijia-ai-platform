package com.yuzhihuihe.apiplatform.platform.referral;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ReferralRepository {

    private final JdbcTemplate jdbc;

    ReferralRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean lockActiveOwner(UUID tenantId, UUID userId) {
        return !jdbc.query("""
                SELECT id FROM platform.app_user
                WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                  AND password_hash IS NOT NULL
                FOR UPDATE
                """, (row, number) -> row.getObject("id", UUID.class), tenantId, userId)
                .isEmpty();
    }

    Optional<CodeRow> current(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT id, inviter_tenant_id, inviter_user_id, code, revoked_at
                FROM platform.referral_code
                WHERE inviter_tenant_id = ? AND inviter_user_id = ? AND revoked_at IS NULL
                """, (row, number) -> codeRow(row), tenantId, userId).stream().findFirst();
    }

    Optional<CodeRow> lockByCode(String code) {
        return jdbc.query("""
                SELECT id, inviter_tenant_id, inviter_user_id, code, revoked_at
                FROM platform.referral_code
                WHERE code = ?
                FOR SHARE
                """, (row, number) -> codeRow(row), code).stream().findFirst();
    }

    Optional<Owner> ownerByCode(String code) {
        return jdbc.query("""
                SELECT inviter_tenant_id, inviter_user_id
                FROM platform.referral_code WHERE code = ?
                """, (row, number) -> new Owner(
                        row.getObject("inviter_tenant_id", UUID.class),
                        row.getObject("inviter_user_id", UUID.class)), code)
                .stream().findFirst();
    }

    boolean lockActiveInviterForAttribution(UUID tenantId, UUID userId) {
        return !jdbc.query("""
                SELECT id FROM platform.app_user
                WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                  AND password_hash IS NOT NULL
                FOR SHARE
                """, (row, number) -> row.getObject("id", UUID.class), tenantId, userId)
                .isEmpty();
    }

    void insertCode(UUID id, UUID tenantId, UUID userId, String code) {
        jdbc.update("""
                INSERT INTO platform.referral_code(
                    id, inviter_tenant_id, inviter_user_id, code)
                VALUES (?, ?, ?, ?)
                """, id, tenantId, userId, code);
    }

    void revokeCurrent(UUID tenantId, UUID userId) {
        jdbc.update("""
                UPDATE platform.referral_code
                SET revoked_at = now()
                WHERE inviter_tenant_id = ? AND inviter_user_id = ? AND revoked_at IS NULL
                """, tenantId, userId);
    }

    void insertAttribution(UUID inviteeId, UUID inviterTenantId,
            UUID inviterUserId, UUID codeId) {
        int inserted = jdbc.update("""
                INSERT INTO platform.referral_attribution(
                    invitee_user_id, inviter_tenant_id, inviter_user_id,
                    referral_code_id, registered_at)
                SELECT u.id, ?, ?, ?, u.created_at
                FROM platform.app_user u
                WHERE u.id = ? AND u.password_hash IS NOT NULL
                """, inviterTenantId, inviterUserId, codeId, inviteeId);
        if (inserted != 1) {
            throw new IllegalStateException("referral_invitee_account_missing");
        }
    }

    boolean hasAttribution(UUID inviteeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM platform.referral_attribution
                               WHERE invitee_user_id = ?)
                """, Boolean.class, inviteeId));
    }

    long successCount(UUID tenantId, UUID userId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM platform.referral_attribution
                WHERE inviter_tenant_id = ? AND inviter_user_id = ?
                """, Long.class, tenantId, userId);
        return count == null ? 0 : count;
    }

    List<InviteeRow> invitees(UUID tenantId, UUID userId, int limit, Cursor cursor) {
        if (cursor == null) {
            return jdbc.query("""
                    SELECT a.invitee_user_id, u.display_name, a.registered_at
                    FROM platform.referral_attribution a
                    JOIN platform.app_user u ON u.id = a.invitee_user_id
                    WHERE a.inviter_tenant_id = ? AND a.inviter_user_id = ?
                    ORDER BY a.registered_at DESC, a.invitee_user_id DESC
                    LIMIT ?
                    """, (row, number) -> inviteeRow(row), tenantId, userId, limit);
        }
        return jdbc.query("""
                SELECT a.invitee_user_id, u.display_name, a.registered_at
                FROM platform.referral_attribution a
                JOIN platform.app_user u ON u.id = a.invitee_user_id
                WHERE a.inviter_tenant_id = ? AND a.inviter_user_id = ?
                  AND (a.registered_at, a.invitee_user_id) < (?::timestamptz, ?::uuid)
                ORDER BY a.registered_at DESC, a.invitee_user_id DESC
                LIMIT ?
                """, (row, number) -> inviteeRow(row), tenantId, userId,
                Timestamp.from(cursor.registeredAt()), cursor.inviteeUserId(), limit);
    }

    private CodeRow codeRow(ResultSet row) throws SQLException {
        OffsetDateTime revokedAt = row.getObject("revoked_at", OffsetDateTime.class);
        return new CodeRow(
                row.getObject("id", UUID.class),
                row.getObject("inviter_tenant_id", UUID.class),
                row.getObject("inviter_user_id", UUID.class),
                row.getString("code"),
                revokedAt == null ? null : revokedAt.toInstant());
    }

    private InviteeRow inviteeRow(ResultSet row) throws SQLException {
        return new InviteeRow(
                row.getObject("invitee_user_id", UUID.class),
                row.getString("display_name"),
                row.getObject("registered_at", OffsetDateTime.class).toInstant());
    }

    record CodeRow(UUID id, UUID inviterTenantId, UUID inviterUserId,
            String code, Instant revokedAt) { }

    record Owner(UUID tenantId, UUID userId) { }

    record Cursor(Instant registeredAt, UUID inviteeUserId) { }

    record InviteeRow(UUID inviteeUserId, String displayName, Instant registeredAt) { }
}
