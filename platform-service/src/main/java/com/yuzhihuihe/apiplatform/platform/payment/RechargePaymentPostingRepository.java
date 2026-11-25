package com.yuzhihuihe.apiplatform.platform.payment;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RechargePaymentPostingRepository {

    private final JdbcTemplate jdbc;

    RechargePaymentPostingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<RechargeRow> lockRecharge(UUID rechargeOrderId) {
        return jdbc.query("""
                        SELECT id, recharge_no, tenant_id, user_id,
                               paid_amount, currency, status
                        FROM platform.recharge_order
                        WHERE id = ?
                        FOR UPDATE
                        """,
                (result, rowNumber) -> new RechargeRow(
                        result.getObject("id", UUID.class),
                        result.getString("recharge_no"),
                        result.getObject("tenant_id", UUID.class),
                        result.getObject("user_id", UUID.class),
                        result.getLong("paid_amount"),
                        result.getString("currency"),
                        result.getString("status")),
                rechargeOrderId).stream().findFirst();
    }

    int markRechargePaid(UUID id, UUID tenantId, UUID userId, java.time.Instant paidAt) {
        return jdbc.update("""
                UPDATE platform.recharge_order
                SET status = 'PAID', paid_at = ?, closed_at = NULL, updated_at = now()
                WHERE id = ? AND tenant_id = ? AND user_id = ?
                  AND status IN ('PENDING', 'CLOSED')
                """, Timestamp.from(paidAt), id, tenantId, userId);
    }

    int markTransactionSucceeded(
            UUID transactionId,
            PaymentAdapter.ProviderPayment payment,
            boolean appliedToOrder) {
        // 这里没有 closed_reason：该列属于 customer_order（V7），而 recharge_order 的
        // 关闭原因记录在审计日志里。platform.payment_transaction 从来没有过
        // 这一列，所以给它赋值会让这条语句在每次调用时解析失败——这意味着
        // 由提供方确认的钱包充值永远无法入账。PackagePaymentPostingRepository
        // 在标记同一张表时不带它，并且一直工作正常。
        return jdbc.update("""
                UPDATE platform.payment_transaction
                SET external_trade_no = ?, status = 'SUCCEEDED',
                    provider_status = ?, provider_error_code = ?,
                    callback_hash = ?, callback_received_at = now(),
                    paid_at = ?, applied_to_order = ?, updated_at = now(),
                    lock_version = lock_version + 1
                WHERE id = ? AND status <> 'SUCCEEDED'
                """, payment.externalTradeNo(), payment.providerStatus(),
                payment.providerErrorCode(), payment.payloadHash(),
                Timestamp.from(payment.paidAt()), appliedToOrder, transactionId);
    }

    void auditPosting(
            UUID tenantId,
            String rechargeNo,
            String transactionNo,
            PaymentProvider provider,
            long amount,
            String currency,
            long balanceAfter,
            boolean latePayment) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, action, resource_type, resource_id, outcome, detail)
                VALUES (?, 'RECHARGE_PAYMENT_POSTED', 'RECHARGE_ORDER', ?, 'SUCCESS',
                        jsonb_build_object(
                            'transactionNo', ?,
                            'provider', ?,
                            'amount', ?,
                            'currency', ?,
                            'balanceAfter', ?,
                            'appliedToOrder', true,
                            'latePayment', ?))
                """, tenantId, rechargeNo, transactionNo, provider.name(),
                amount, currency, balanceAfter, latePayment);
    }

    void insertDuplicateAnomaly(
            UUID id,
            String fingerprint,
            PaymentProvider provider,
            UUID transactionId,
            String rechargeNo,
            String transactionNo) {
        jdbc.update("""
                INSERT INTO platform.payment_anomaly(
                    id, fingerprint, anomaly_type, provider,
                    payment_transaction_id, status, detail)
                VALUES (?, ?, 'DUPLICATE_PAYMENT', ?, ?, 'OPEN',
                        jsonb_build_object(
                            'reason', 'RECHARGE_ALREADY_PAID',
                            'rechargeNo', ?,
                            'transactionNo', ?))
                ON CONFLICT (fingerprint) WHERE status IN ('OPEN', 'INVESTIGATING')
                DO NOTHING
                """, id, fingerprint, provider.name(), transactionId,
                rechargeNo, transactionNo);
    }

    record RechargeRow(
            UUID id,
            String rechargeNo,
            UUID tenantId,
            UUID userId,
            long paidAmount,
            String currency,
            String status) {
    }
}
