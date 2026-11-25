package com.yuzhihuihe.apiplatform.platform.payment;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class MockPaymentRepository {

    private final JdbcTemplate jdbc;

    MockPaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void auditSimulation(
            UUID tenantId, UUID actorUserId, String orderNo, String externalTradeNo) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type,
                    resource_id, outcome, detail)
                VALUES (?, ?, 'MOCK_PAYMENT_SIMULATED', 'ORDER', ?, 'SUCCESS',
                        jsonb_build_object('externalTradeNo', ?))
                """, tenantId, actorUserId, orderNo, externalTradeNo);
    }

    List<AttemptRow> findCallbackAttempt(String orderNo, String externalTradeNo) {
        return jdbc.query("""
                        SELECT t.transaction_no, t.external_trade_no,
                               COALESCE(o.order_no, r.recharge_no) AS order_no,
                               COALESCE(o.tenant_id, r.tenant_id) AS tenant_id,
                               COALESCE(o.amount, r.paid_amount) AS amount,
                               COALESCE(o.currency, r.currency) AS currency
                        FROM platform.payment_transaction t
                        LEFT JOIN platform.customer_order o ON o.id = t.order_id
                        LEFT JOIN platform.recharge_order r ON r.id = t.recharge_order_id
                        WHERE COALESCE(o.order_no, r.recharge_no) = ?
                          AND t.provider = 'MOCK'
                          AND (t.external_trade_no = ? OR t.external_trade_no IS NULL)
                        ORDER BY
                            CASE WHEN t.external_trade_no = ? THEN 0 ELSE 1 END,
                            CASE WHEN t.status IN ('CREATED', 'PENDING') THEN 0 ELSE 1 END,
                            t.created_at DESC
                        LIMIT 1
                        """, this::attempt,
                orderNo, externalTradeNo, externalTradeNo);
    }

    List<AttemptRow> findSimulationAttempt(String orderNo) {
        return jdbc.query("""
                        SELECT t.transaction_no, t.external_trade_no,
                               o.order_no, o.tenant_id, o.amount, o.currency
                        FROM platform.payment_transaction t
                        JOIN platform.customer_order o ON o.id = t.order_id
                        WHERE o.order_no = ? AND t.provider = 'MOCK'
                        ORDER BY
                            CASE WHEN t.status IN ('CREATED', 'PENDING') THEN 0 ELSE 1 END,
                            t.created_at DESC
                        LIMIT 1
                        """, this::attempt, orderNo);
    }

    private AttemptRow attempt(java.sql.ResultSet result, int rowNumber)
            throws java.sql.SQLException {
        return new AttemptRow(
                result.getString("transaction_no"),
                result.getString("external_trade_no"),
                result.getString("order_no"),
                result.getObject("tenant_id", UUID.class),
                result.getLong("amount"),
                result.getString("currency"));
    }

    record AttemptRow(
            String transactionNo,
            String externalTradeNo,
            String orderNo,
            UUID tenantId,
            long amount,
            String currency) {
    }
}
