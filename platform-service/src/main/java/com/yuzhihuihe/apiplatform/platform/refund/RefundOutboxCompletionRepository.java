package com.yuzhihuihe.apiplatform.platform.refund;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RefundOutboxCompletionRepository {

    private final JdbcTemplate jdbc;

    RefundOutboxCompletionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<UUID> lockOrder(UUID refundId) {
        return jdbc.query("""
                        SELECT customer_order.id
                        FROM platform.customer_order AS customer_order
                        JOIN platform.refund AS refund ON refund.order_id = customer_order.id
                        WHERE refund.id = ?
                        FOR UPDATE OF customer_order
                        """, (result, rowNumber) -> result.getObject("id", UUID.class), refundId)
                .stream().findFirst();
    }

    List<CompletionRow> lockTarget(UUID refundId, UUID orderId) {
        return jdbc.query("""
                SELECT refund.id AS refund_id, refund.status AS refund_status,
                       refund.refund_no, refund.refund_type, refund.funding_source,
                       refund.balance_refund_ledger_id,
                       refund.calculation_snapshot->>'packageInstanceId' AS package_instance_id,
                       customer_order.id AS order_id, customer_order.status AS order_status,
                       customer_order.tenant_id, customer_order.user_id, refund.amount,
                       customer_order.currency,
                       grant_request.id AS grant_request_id,
                       grant_request.status AS grant_status
                FROM platform.refund AS refund
                JOIN platform.customer_order AS customer_order
                  ON customer_order.id = refund.order_id
                JOIN platform.grant_request AS grant_request
                  ON grant_request.business_no = 'order:' || customer_order.order_no
                WHERE refund.id = ? AND customer_order.id = ?
                FOR UPDATE OF refund, grant_request
                """, (result, rowNumber) -> new CompletionRow(
                        result.getObject("refund_id", UUID.class),
                        result.getString("refund_status"), result.getString("refund_no"),
                        result.getString("refund_type"), result.getString("funding_source"),
                        result.getObject("balance_refund_ledger_id", UUID.class),
                        UUID.fromString(result.getString("package_instance_id")),
                        result.getObject("order_id", UUID.class),
                        result.getString("order_status"),
                        result.getObject("tenant_id", UUID.class),
                        result.getObject("user_id", UUID.class), result.getLong("amount"),
                        result.getString("currency"),
                        result.getObject("grant_request_id", UUID.class),
                        result.getString("grant_status")), refundId, orderId);
    }

    int markGrantRevoked(UUID grantRequestId) {
        return jdbc.update("""
                UPDATE platform.grant_request
                SET status = 'REVOKED', completed_at = COALESCE(completed_at, now()),
                    failure_reason = NULL, updated_at = now()
                WHERE id = ? AND status IN ('QUEUED', 'ACTIVE', 'REVOKED')
                """, grantRequestId);
    }

    int markRefundCompleted(UUID refundId, UUID balanceRefundLedgerId) {
        return jdbc.update("""
                UPDATE platform.refund
                SET status = 'COMPLETED', completed_at = COALESCE(completed_at, now()),
                    balance_refund_ledger_id = COALESCE(?, balance_refund_ledger_id),
                    provider_error_code = NULL, updated_at = now(),
                    lock_version = lock_version + 1
                WHERE id = ? AND status = 'REVOKING'
                """, balanceRefundLedgerId, refundId);
    }

    int markOrderRefunded(UUID orderId, String targetStatus, String alternateStatus) {
        return jdbc.update("""
                UPDATE platform.customer_order
                SET status = ?, updated_at = now(), lock_version = lock_version + 1
                WHERE id = ? AND status IN ('PAID', ?, ?)
                """, targetStatus, orderId, targetStatus, alternateStatus);
    }

    void auditCompletion(
            UUID tenantId,
            String refundNo,
            long amount,
            String refundType,
            String fundingSource,
            String orderStatus) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, action, resource_type, resource_id, outcome, detail)
                VALUES (?, 'REFUND_COMPLETED', 'REFUND', ?, 'SUCCESS',
                        jsonb_build_object(
                            'amount', ?,
                            'refundType', ?,
                            'fundingSource', ?,
                            'orderStatus', ?))
                """, tenantId, refundNo, amount, refundType, fundingSource, orderStatus);
    }

    record CompletionRow(
            UUID refundId,
            String refundStatus,
            String refundNo,
            String refundType,
            String fundingSource,
            UUID balanceRefundLedgerId,
            UUID packageInstanceId,
            UUID orderId,
            String orderStatus,
            UUID tenantId,
            UUID userId,
            long amount,
            String currency,
            UUID grantRequestId,
            String grantStatus) {
    }
}
