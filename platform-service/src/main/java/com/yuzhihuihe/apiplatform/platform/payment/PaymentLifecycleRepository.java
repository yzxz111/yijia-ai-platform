package com.yuzhihuihe.apiplatform.platform.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PaymentLifecycleRepository {

    private final JdbcTemplate jdbc;

    PaymentLifecycleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean paymentWindowOpen(UUID transactionId, Instant now) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                SELECT EXISTS(SELECT 1 FROM platform.payment_transaction
                    WHERE id = ? AND status IN ('CREATED', 'PENDING') AND expires_at > ?)
                """,
                        Boolean.class,
                        transactionId,
                        Timestamp.from(now)));
    }

    Optional<OrderRow> findOrder(String orderNo) {
        return jdbc
                .query(
                        """
                        SELECT id, order_no, tenant_id, user_id, status
                        FROM platform.customer_order
                        WHERE order_no = ?
                        """,
                        this::orderRow,
                        orderNo)
                .stream()
                .findFirst();
    }

    Optional<OrderRow> findOrder(String orderNo, UUID tenantId, UUID userId) {
        return jdbc
                .query(
                        """
                        SELECT id, order_no, tenant_id, user_id, status
                        FROM platform.customer_order
                        WHERE order_no = ? AND tenant_id = ? AND user_id = ?
                        """,
                        this::orderRow,
                        orderNo,
                        tenantId,
                        userId)
                .stream()
                .findFirst();
    }

    Optional<RechargeRow> findRecharge(String rechargeNo, UUID tenantId, UUID userId) {
        return jdbc
                .query(
                        """
                        SELECT id, recharge_no, tenant_id, user_id, status
                        FROM platform.recharge_order
                        WHERE recharge_no = ? AND tenant_id = ? AND user_id = ?
                        """,
                        this::rechargeRow,
                        rechargeNo,
                        tenantId,
                        userId)
                .stream()
                .findFirst();
    }

    Optional<OrderRow> lockOrder(UUID orderId) {
        return jdbc
                .query(
                        """
                        SELECT id, order_no, tenant_id, user_id, status
                        FROM platform.customer_order
                        WHERE id = ?
                        FOR UPDATE
                        """,
                        this::orderRow,
                        orderId)
                .stream()
                .findFirst();
    }

    Optional<RechargeRow> lockRecharge(UUID rechargeId) {
        return jdbc
                .query(
                        """
                        SELECT id, recharge_no, tenant_id, user_id, status
                        FROM platform.recharge_order
                        WHERE id = ?
                        FOR UPDATE
                        """,
                        this::rechargeRow,
                        rechargeId)
                .stream()
                .findFirst();
    }

    Optional<String> lockRechargeStatus(UUID rechargeId) {
        return jdbc
                .query(
                        """
                        SELECT status
                        FROM platform.recharge_order
                        WHERE id = ?
                        FOR UPDATE
                        """,
                        (result, rowNumber) -> result.getString("status"),
                        rechargeId)
                .stream()
                .findFirst();
    }

    Optional<TransactionRow> findLatestOrderTransaction(UUID orderId) {
        return jdbc
                .query(
                        """
                        SELECT payment_transaction.id, transaction_no, order_id,
                               payment_transaction.recharge_order_id, provider,
                               payment_product, payment_transaction.amount,
                               customer_order.tenant_id, customer_order.user_id,
                               customer_order.currency, payment_transaction.status,
                               external_trade_no
                        FROM platform.payment_transaction
                        JOIN platform.customer_order
                          ON customer_order.id = payment_transaction.order_id
                        WHERE payment_transaction.order_id = ?
                        ORDER BY payment_transaction.created_at DESC,
                                 payment_transaction.id DESC
                        LIMIT 1
                        """,
                        this::transactionRow,
                        orderId)
                .stream()
                .findFirst();
    }

    Optional<TransactionRow> findLatestRechargeTransaction(UUID rechargeId) {
        return jdbc
                .query(
                        """
                        SELECT payment_transaction.id, transaction_no, order_id,
                               payment_transaction.recharge_order_id, provider,
                               payment_product, payment_transaction.amount,
                               recharge_order.tenant_id, recharge_order.user_id,
                               recharge_order.currency, payment_transaction.status,
                               external_trade_no
                        FROM platform.payment_transaction
                        JOIN platform.recharge_order
                          ON recharge_order.id = payment_transaction.recharge_order_id
                        WHERE payment_transaction.recharge_order_id = ?
                        ORDER BY payment_transaction.created_at DESC,
                                 payment_transaction.id DESC
                        LIMIT 1
                        """,
                        this::transactionRow,
                        rechargeId)
                .stream()
                .findFirst();
    }

    Optional<TransactionRow> findTransaction(String transactionNo) {
        return jdbc
                .query(
                        """
                        SELECT payment_transaction.id, transaction_no, order_id,
                               payment_transaction.recharge_order_id, provider,
                               payment_product, payment_transaction.amount,
                               COALESCE(customer_order.tenant_id, recharge_order.tenant_id)
                                   AS tenant_id,
                               COALESCE(customer_order.user_id, recharge_order.user_id)
                                   AS user_id,
                               COALESCE(customer_order.currency, recharge_order.currency)
                                   AS currency,
                               payment_transaction.status,
                               external_trade_no
                        FROM platform.payment_transaction
                        LEFT JOIN platform.customer_order
                          ON customer_order.id = payment_transaction.order_id
                        LEFT JOIN platform.recharge_order
                          ON recharge_order.id = payment_transaction.recharge_order_id
                        WHERE payment_transaction.transaction_no = ?
                        """,
                        this::transactionRow,
                        transactionNo)
                .stream()
                .findFirst();
    }

    Optional<TransactionRow> lockTransaction(UUID transactionId) {
        return jdbc
                .query(
                        """
                        SELECT payment_transaction.id, transaction_no, order_id,
                               payment_transaction.recharge_order_id, provider,
                               payment_product, payment_transaction.amount,
                               COALESCE(customer_order.tenant_id, recharge_order.tenant_id)
                                   AS tenant_id,
                               COALESCE(customer_order.user_id, recharge_order.user_id)
                                   AS user_id,
                               COALESCE(customer_order.currency, recharge_order.currency)
                                   AS currency,
                               payment_transaction.status,
                               external_trade_no
                        FROM platform.payment_transaction
                        LEFT JOIN platform.customer_order
                          ON customer_order.id = payment_transaction.order_id
                        LEFT JOIN platform.recharge_order
                          ON recharge_order.id = payment_transaction.recharge_order_id
                        WHERE payment_transaction.id = ?
                        FOR UPDATE OF payment_transaction
                        """,
                        this::transactionRow,
                        transactionId)
                .stream()
                .findFirst();
    }

    int updateQueryState(
            UUID transactionId,
            String status,
            String providerStatus,
            String providerErrorCode,
            Instant queriedAt,
            Instant nextQueryAt) {
        return jdbc.update(
                """
                UPDATE platform.payment_transaction
                SET status = ?, provider_status = ?, provider_error_code = ?,
                    last_queried_at = ?, next_query_at = ?, updated_at = now(),
                    lock_version = lock_version + 1
                WHERE id = ?
                """,
                status,
                providerStatus,
                providerErrorCode,
                Timestamp.from(queriedAt),
                Timestamp.from(nextQueryAt),
                transactionId);
    }

    void schedulePendingQuery(
            UUID transactionId,
            String providerStatus,
            String providerErrorCode,
            Instant queriedAt,
            Instant nextQueryAt) {
        jdbc.update(
                """
                UPDATE platform.payment_transaction
                SET provider_status = ?, provider_error_code = ?,
                    last_queried_at = ?, next_query_at = ?, updated_at = now(),
                    lock_version = lock_version + 1
                WHERE id = ? AND status IN ('CREATED', 'PENDING')
                """,
                providerStatus,
                providerErrorCode,
                Timestamp.from(queriedAt),
                Timestamp.from(nextQueryAt),
                transactionId);
    }

    void scheduleRetry(UUID transactionId, Instant queriedAt, Instant nextQueryAt) {
        jdbc.update(
                """
                UPDATE platform.payment_transaction
                SET last_queried_at = ?, next_query_at = ?, updated_at = now(),
                    lock_version = lock_version + 1
                WHERE id = ? AND status IN ('CREATED', 'PENDING')
                """,
                Timestamp.from(queriedAt),
                Timestamp.from(nextQueryAt),
                transactionId);
    }

    void closeTransaction(UUID transactionId, Instant queriedAt) {
        jdbc.update(
                """
                UPDATE platform.payment_transaction
                SET status = 'CLOSED', provider_status = 'CLOSED',
                    last_queried_at = ?, updated_at = now(),
                    lock_version = lock_version + 1
                WHERE id = ? AND status IN ('CREATED', 'PENDING', 'FAILED')
                """,
                Timestamp.from(queriedAt),
                transactionId);
    }

    int closeOrder(UUID orderId, String reason) {
        return jdbc.update(
                """
                UPDATE platform.customer_order
                SET status = 'CLOSED', closed_reason = ?, updated_at = now(),
                    lock_version = lock_version + 1
                WHERE id = ? AND status = 'PENDING'
                """,
                reason,
                orderId);
    }

    void auditOrderClosed(String orderNo, String reason) {
        jdbc.update(
                """
                INSERT INTO platform.admin_audit_log(
                    action, resource_type, resource_id, outcome, detail)
                VALUES ('ORDER_CLOSED', 'ORDER', ?, 'SUCCESS',
                        jsonb_build_object('reason', ?))
                """,
                orderNo,
                reason);
    }

    int closeRecharge(UUID rechargeId, Instant closedAt) {
        Timestamp timestamp = Timestamp.from(closedAt);
        return jdbc.update(
                """
                UPDATE platform.recharge_order
                SET status = 'CLOSED',
                    closed_at = GREATEST(?, created_at),
                    updated_at = GREATEST(?, created_at)
                WHERE id = ? AND status = 'PENDING'
                """,
                timestamp,
                timestamp,
                rechargeId);
    }

    void auditRechargeClosed(String rechargeNo, String reason) {
        jdbc.update(
                """
                INSERT INTO platform.admin_audit_log(
                    action, resource_type, resource_id, outcome, detail)
                VALUES ('RECHARGE_ORDER_CLOSED', 'RECHARGE_ORDER', ?, 'SUCCESS',
                        jsonb_build_object('reason', ?))
                """,
                rechargeNo,
                reason);
    }

    private OrderRow orderRow(ResultSet result, int rowNumber) throws SQLException {
        return new OrderRow(
                result.getObject("id", UUID.class),
                result.getString("order_no"),
                result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getString("status"));
    }

    private RechargeRow rechargeRow(ResultSet result, int rowNumber) throws SQLException {
        return new RechargeRow(
                result.getObject("id", UUID.class),
                result.getString("recharge_no"),
                result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getString("status"));
    }

    private TransactionRow transactionRow(ResultSet result, int rowNumber) throws SQLException {
        return new TransactionRow(
                result.getObject("id", UUID.class),
                result.getString("transaction_no"),
                result.getObject("order_id", UUID.class),
                result.getObject("recharge_order_id", UUID.class),
                result.getString("provider"),
                result.getString("payment_product"),
                result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getLong("amount"),
                result.getString("currency"),
                result.getString("status"),
                result.getString("external_trade_no"));
    }

    record OrderRow(UUID id, String orderNo, UUID tenantId, UUID userId, String status) {}

    record RechargeRow(UUID id, String rechargeNo, UUID tenantId, UUID userId, String status) {}

    record TransactionRow(
            UUID transactionId,
            String transactionNo,
            UUID orderId,
            UUID rechargeOrderId,
            String provider,
            String paymentProduct,
            UUID tenantId,
            UUID userId,
            long amount,
            String currency,
            String transactionStatus,
            String externalTradeNo) {}
}
