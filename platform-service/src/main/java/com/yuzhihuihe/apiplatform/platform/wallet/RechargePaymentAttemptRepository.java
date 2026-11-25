package com.yuzhihuihe.apiplatform.platform.wallet;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RechargePaymentAttemptRepository {

    private final JdbcTemplate jdbc;

    RechargePaymentAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    RechargeRow lockRecharge(UUID tenantId, UUID userId, String rechargeNo) {
        return jdbc.query("""
                        SELECT id, recharge_no, paid_amount, currency, status, expires_at
                        FROM platform.recharge_order
                        WHERE tenant_id = ? AND user_id = ? AND recharge_no = ?
                        FOR UPDATE
                        """,
                (result, rowNumber) -> new RechargeRow(
                        result.getObject("id", UUID.class),
                        result.getString("recharge_no"),
                        result.getLong("paid_amount"),
                        result.getString("currency"),
                        result.getString("status"),
                        result.getObject("expires_at", OffsetDateTime.class).toInstant()),
                tenantId, userId, rechargeNo).stream().findFirst().orElse(null);
    }

    boolean activeAttemptExists(UUID rechargeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM platform.payment_transaction
                    WHERE recharge_order_id = ? AND status IN ('CREATED', 'PENDING'))
                """, Boolean.class, rechargeId));
    }

    boolean phoneVerified(UUID tenantId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT phone_verified_at IS NOT NULL
                FROM platform.app_user
                WHERE tenant_id = ? AND id = ?
                """, Boolean.class, tenantId, userId));
    }

    void insertTransaction(
            UUID paymentId,
            String transactionNo,
            UUID rechargeId,
            String provider,
            long amount,
            String product,
            Instant expiresAt,
            String requestHash) {
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, recharge_order_id, provider,
                    amount, status, payment_product, expires_at, request_payload_hash)
                VALUES (?, ?, NULL, ?, ?, ?, 'CREATED', ?, ?, ?)
                """, paymentId, transactionNo, rechargeId, provider, amount, product,
                Timestamp.from(expiresAt), requestHash);
    }

    int updateTransaction(
            String transactionNo,
            String status,
            String externalTradeNo,
            String providerStatus) {
        return jdbc.update("""
                UPDATE platform.payment_transaction
                SET status = ?, external_trade_no = ?, provider_status = ?, updated_at = now()
                WHERE transaction_no = ? AND status = 'CREATED'
                """, status, externalTradeNo, providerStatus, transactionNo);
    }

    record RechargeRow(
            UUID id,
            String rechargeNo,
            long amount,
            String currency,
            String status,
            Instant expiresAt) {
    }
}
