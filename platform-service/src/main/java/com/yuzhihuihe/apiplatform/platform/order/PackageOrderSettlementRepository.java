package com.yuzhihuihe.apiplatform.platform.order;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PackageOrderSettlementRepository {

    private final JdbcTemplate jdbc;

    PackageOrderSettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<OrderRow> lockOrder(UUID orderId) {
        return queryLockedOrder("WHERE id = ?", new Object[] {orderId});
    }

    Optional<OrderRow> lockOwnedOrder(UUID tenantId, UUID userId, String orderNo) {
        return queryLockedOrder(
                "WHERE tenant_id = ? AND user_id = ? AND order_no = ?",
                new Object[] {tenantId, userId, orderNo});
    }

    int markPaid(
            UUID orderId,
            Instant paidAt,
            String settlementMethod,
            String provider,
            UUID paymentTransactionId,
            UUID balanceDebitLedgerId) {
        return jdbc.update("""
                UPDATE platform.customer_order
                SET status = 'PAID', paid_at = ?, settlement_method = ?,
                    payment_provider = ?, successful_payment_transaction_id = ?,
                    balance_debit_ledger_id = ?, updated_at = now(),
                    lock_version = lock_version + 1
                WHERE id = ? AND status = 'PENDING'
                """, Timestamp.from(paidAt), settlementMethod, provider,
                paymentTransactionId, balanceDebitLedgerId, orderId);
    }

    int backfillSnapshot(UUID orderId, String packageSnapshot) {
        return jdbc.update("""
                UPDATE platform.customer_order
                SET package_snapshot = ?::jsonb, updated_at = now()
                WHERE id = ? AND package_snapshot IS NULL
                """, packageSnapshot, orderId);
    }

    void insertAudit(
            UUID tenantId,
            String orderNo,
            String transactionNo,
            String provider,
            long amount,
            String currency,
            String settlementMethod,
            boolean legacySnapshotBackfill) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, action, resource_type, resource_id, outcome, detail)
                VALUES (?, 'PAYMENT_POSTED', 'ORDER', ?, 'SUCCESS',
                        jsonb_build_object(
                            'transactionNo', CAST(? AS text),
                            'provider', ?,
                            'amount', ?,
                            'currency', ?,
                            'settlementMethod', ?,
                            'appliedToOrder', true,
                            'legacySnapshotBackfill', ?))
                """, tenantId, orderNo, transactionNo, provider, amount, currency,
                settlementMethod, legacySnapshotBackfill);
    }

    private Optional<OrderRow> queryLockedOrder(String predicate, Object[] arguments) {
        return jdbc.query("""
                        SELECT id, order_no, tenant_id, user_id, package_plan_version_id,
                               amount, currency, status, package_snapshot::text,
                               idempotency_key, successful_payment_transaction_id,
                               settlement_method, balance_debit_ledger_id,
                               payment_provider, paid_at, expires_at, lock_version
                        FROM platform.customer_order
                        """ + predicate + " FOR UPDATE",
                (result, rowNumber) -> new OrderRow(
                        result.getObject("id", UUID.class),
                        result.getString("order_no"),
                        result.getObject("tenant_id", UUID.class),
                        result.getObject("user_id", UUID.class),
                        result.getObject("package_plan_version_id", UUID.class),
                        result.getLong("amount"),
                        result.getString("currency"),
                        result.getString("status"),
                        result.getString("package_snapshot"),
                        result.getString("idempotency_key"),
                        result.getObject("successful_payment_transaction_id", UUID.class),
                        result.getString("settlement_method"),
                        result.getObject("balance_debit_ledger_id", UUID.class),
                        result.getString("payment_provider"),
                        instant(result.getObject("paid_at", OffsetDateTime.class)),
                        instant(result.getObject("expires_at", OffsetDateTime.class)),
                        result.getLong("lock_version")),
                arguments).stream().findFirst();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    record OrderRow(
            UUID id,
            String orderNo,
            UUID tenantId,
            UUID userId,
            UUID packageVersionId,
            long amount,
            String currency,
            String status,
            String packageSnapshot,
            String idempotencyKey,
            UUID successfulPaymentTransactionId,
            String settlementMethod,
            UUID balanceDebitLedgerId,
            String paymentProvider,
            Instant paidAt,
            Instant expiresAt,
            long lockVersion) {
    }
}
