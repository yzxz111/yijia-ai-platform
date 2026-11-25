package com.yuzhihuihe.apiplatform.platform.sms;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class SmsProofRepository {

    private final JdbcTemplate jdbc;

    SmsProofRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, String tokenHash, UUID tenantId, UUID userId,
            SmsPurpose purpose, Instant expiresAt, Instant now) {
        jdbc.update("""
                INSERT INTO platform.sms_proof(
                    id, token_hash, tenant_id, user_id, purpose, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, tokenHash, tenantId, userId, purpose.name(),
                Timestamp.from(expiresAt), Timestamp.from(now));
    }

    List<ClaimsRow> consume(String tokenHash, UUID tenantId, UUID userId,
            SmsPurpose purpose, Instant now) {
        return jdbc.query("""
                UPDATE platform.sms_proof
                SET consumed_at = ?
                WHERE token_hash = ? AND tenant_id = ? AND user_id = ? AND purpose = ?
                  AND consumed_at IS NULL AND expires_at >= ?
                RETURNING tenant_id, user_id, purpose
                """, (result, rowNumber) -> claims(result),
                Timestamp.from(now), tokenHash, tenantId, userId, purpose.name(),
                Timestamp.from(now));
    }

    List<ClaimsRow> consumeForOperation(String tokenHash, UUID tenantId, UUID userId,
            SmsPurpose purpose, UUID operationId, Instant now) {
        List<ClaimsRow> consumed = jdbc.query("""
                UPDATE platform.sms_proof
                SET consumed_at = ?, consumed_operation_id = ?
                WHERE token_hash = ? AND tenant_id = ? AND user_id = ? AND purpose = ?
                  AND consumed_at IS NULL AND expires_at >= ?
                RETURNING tenant_id, user_id, purpose
                """, (result, rowNumber) -> claims(result),
                Timestamp.from(now), operationId, tokenHash, tenantId, userId,
                purpose.name(), Timestamp.from(now));
        if (!consumed.isEmpty()) {
            return consumed;
        }
        return jdbc.query("""
                SELECT tenant_id, user_id, purpose
                FROM platform.sms_proof
                WHERE token_hash = ? AND tenant_id = ? AND user_id = ? AND purpose = ?
                  AND consumed_operation_id = ?
                """, (result, rowNumber) -> claims(result),
                tokenHash, tenantId, userId, purpose.name(), operationId);
    }

    private static ClaimsRow claims(java.sql.ResultSet result) throws java.sql.SQLException {
        return new ClaimsRow(
                result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                SmsPurpose.valueOf(result.getString("purpose")));
    }

    record ClaimsRow(UUID tenantId, UUID userId, SmsPurpose purpose) {
    }
}
