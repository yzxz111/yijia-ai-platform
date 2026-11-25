package com.yuzhihuihe.apiplatform.platform.payment;

import java.sql.Timestamp;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PackagePaymentPostingRepository {

    private final JdbcTemplate jdbc;

    PackagePaymentPostingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    int markTransactionSucceeded(
            UUID transactionId,
            PaymentAdapter.ProviderPayment payment,
            boolean appliedToOrder) {
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

    void insertDuplicateAnomaly(
            UUID id,
            String fingerprint,
            PaymentProvider provider,
            UUID orderId,
            UUID transactionId,
            String orderNo,
            String transactionNo) {
        jdbc.update("""
                INSERT INTO platform.payment_anomaly(
                    id, fingerprint, anomaly_type, provider, order_id,
                    payment_transaction_id, status, detail)
                VALUES (?, ?, 'DUPLICATE_PAYMENT', ?, ?, ?, 'OPEN',
                        jsonb_build_object(
                            'reason', 'ORDER_ALREADY_PAID',
                            'orderNo', ?,
                            'transactionNo', ?))
                ON CONFLICT (fingerprint) WHERE status IN ('OPEN', 'INVESTIGATING')
                DO NOTHING
                """, id, fingerprint, provider.name(), orderId,
                transactionId, orderNo, transactionNo);
    }
}
