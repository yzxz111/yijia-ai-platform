package com.yuzhihuihe.apiplatform.platform.payment;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PaymentAttemptRepository {

    private final JdbcTemplate jdbc;

    PaymentAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<OrderRow> lockOrder(UUID tenantId, UUID userId, String orderNo) {
        return jdbc.query("""
                        SELECT id, order_no, amount, currency, status, expires_at
                        FROM platform.customer_order
                        WHERE tenant_id = ? AND user_id = ? AND order_no = ?
                        FOR UPDATE
                        """,
                (result, rowNumber) -> new OrderRow(
                        result.getObject("id", UUID.class), result.getString("order_no"),
                        result.getLong("amount"), result.getString("currency"),
                        result.getString("status"),
                        result.getObject("expires_at", OffsetDateTime.class).toInstant()),
                tenantId, userId, orderNo).stream().findFirst();
    }

    boolean hasActiveAttempt(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM platform.payment_transaction
                    WHERE order_id = ? AND status IN ('CREATED', 'PENDING'))
                """, Boolean.class, orderId));
    }

    boolean isPhoneVerified(UUID tenantId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT phone_verified_at IS NOT NULL
                FROM platform.app_user
                WHERE tenant_id = ? AND id = ?
                """, Boolean.class, tenantId, userId));
    }

    void insertAttempt(
            UUID paymentId,
            String transactionNo,
            UUID orderId,
            PaymentProvider provider,
            long amount,
            PaymentProduct product,
            Instant expiresAt,
            String requestHash) {
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status,
                    payment_product, expires_at, request_payload_hash)
                VALUES (?, ?, ?, ?, ?, 'CREATED', ?, ?, ?)
                """, paymentId, transactionNo, orderId, provider.name(),
                amount, product.name(), Timestamp.from(expiresAt), requestHash);
    }

    int applyCreation(
            String transactionNo,
            String status,
            String externalTradeNo,
            String providerStatus,
            String actionJson) {
        return jdbc.update("""
                UPDATE platform.payment_transaction
                SET status = ?, external_trade_no = ?, provider_status = ?,
                    payment_action = ?::jsonb, updated_at = now()
                WHERE transaction_no = ? AND status = 'CREATED'
                """, status, externalTradeNo, providerStatus, actionJson, transactionNo);
    }

    record OrderRow(
            UUID id,
            String orderNo,
            long amount,
            String currency,
            String status,
            Instant expiresAt) {
    }
}
