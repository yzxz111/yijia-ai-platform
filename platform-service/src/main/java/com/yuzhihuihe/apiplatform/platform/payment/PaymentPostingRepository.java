package com.yuzhihuihe.apiplatform.platform.payment;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PaymentPostingRepository {

    private final JdbcTemplate jdbc;

    PaymentPostingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录一条无效回调异常，把共享同一调用方窗口化指纹的每次回调
     * 都折叠进同一条未关闭的行。
     *
     * <p>指纹派生自提供方与一个粗粒度时间桶（参见
     * {@code PaymentPostingService#recordInvalidCallback}），而非派生自请求体，因此每次尝试都
     * 变化的调用方无法插入无限多的行。{@code attempt_count} 保留折叠后的
     * 计数，而不是静默丢弃证据。
     */
    void insertInvalidCallback(UUID id, String fingerprint, PaymentProvider provider) {
        jdbc.update("""
                INSERT INTO platform.payment_anomaly(
                    id, fingerprint, anomaly_type, provider, status, detail, attempt_count)
                VALUES (?, ?, 'CALLBACK_SIGNATURE_INVALID', ?, 'OPEN',
                        '{"reason":"SIGNATURE_INVALID"}'::jsonb, 1)
                ON CONFLICT (fingerprint) WHERE status IN ('OPEN', 'INVESTIGATING')
                DO UPDATE SET attempt_count = platform.payment_anomaly.attempt_count + 1,
                              updated_at = now()
                """, id, fingerprint, provider.name());
    }

    /**
     * 至多删除 {@code batchSize} 条在 {@code cutoff} 之前创建的异常，最旧的优先，并
     * 返回删除了多少行。限制批次大小可使单次运行的锁与 WAL 工作量
     * 保持很小；调用方会在下一次调度运行时重复，直到积压清空。
     */
    int deleteAnomaliesCreatedBefore(Instant cutoff, int batchSize) {
        return jdbc.update("""
                DELETE FROM platform.payment_anomaly
                WHERE id IN (
                    SELECT id
                    FROM platform.payment_anomaly
                    WHERE created_at < ?
                    ORDER BY created_at, id
                    LIMIT ?
                )
                """, Timestamp.from(cutoff), batchSize);
    }

    Optional<String> findProvider(String transactionNo) {
        return jdbc.query("""
                        SELECT provider
                        FROM platform.payment_transaction
                        WHERE transaction_no = ?
                        """,
                (result, rowNumber) -> result.getString("provider"), transactionNo)
                .stream().findFirst();
    }

    void lockExternalTradeNo(PaymentProvider provider, String externalTradeNo) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, provider.name() + ":" + externalTradeNo);
                statement.execute();
                return null;
            }
        });
    }

    Optional<TransactionRow> lockTransaction(String transactionNo) {
        return jdbc.query("""
                        SELECT id, transaction_no, order_id, recharge_order_id,
                               provider, payment_product, amount, status,
                               external_trade_no, callback_hash,
                               request_payload_hash, expires_at, applied_to_order
                        FROM platform.payment_transaction
                        WHERE transaction_no = ?
                        FOR UPDATE
                        """,
                (result, rowNumber) -> new TransactionRow(
                        result.getObject("id", UUID.class),
                        result.getString("transaction_no"),
                        result.getObject("order_id", UUID.class),
                        result.getObject("recharge_order_id", UUID.class),
                        PaymentProvider.valueOf(result.getString("provider")),
                        result.getString("payment_product"),
                        result.getLong("amount"),
                        result.getString("status"),
                        result.getString("external_trade_no"),
                        result.getString("callback_hash"),
                        result.getString("request_payload_hash"),
                        toInstant(result.getObject("expires_at", OffsetDateTime.class)),
                        result.getBoolean("applied_to_order")),
                transactionNo).stream().findFirst();
    }

    boolean externalTradeOwnedByAnother(
            PaymentProvider provider, String externalTradeNo, UUID transactionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1
                    FROM platform.payment_transaction
                    WHERE provider = ? AND external_trade_no = ? AND id <> ?)
                """, Boolean.class, provider.name(), externalTradeNo, transactionId));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    record TransactionRow(
            UUID id,
            String transactionNo,
            UUID orderId,
            UUID rechargeOrderId,
            PaymentProvider provider,
            String paymentProduct,
            long amount,
            String status,
            String externalTradeNo,
            String callbackHash,
            String requestPayloadHash,
            Instant expiresAt,
            boolean appliedToOrder) {
    }
}
