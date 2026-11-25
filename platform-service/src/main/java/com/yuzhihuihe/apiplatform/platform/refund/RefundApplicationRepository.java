package com.yuzhihuihe.apiplatform.platform.refund;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RefundApplicationRepository {

    private final JdbcTemplate jdbc;

    RefundApplicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    String lockStatus(UUID refundId) {
        return jdbc.queryForObject(
                "SELECT status FROM platform.refund WHERE id = ? FOR UPDATE",
                String.class, refundId);
    }

    int markPrepared(
            UUID refundId,
            String refundType,
            int policyVersion,
            long amount,
            String calculationSnapshotJson) {
        return jdbc.update("""
                UPDATE platform.refund
                SET status = 'PENDING_APPROVAL', refund_type = ?, policy_version = ?,
                    amount = ?, calculation_snapshot = ?::jsonb, updated_at = now()
                WHERE id = ? AND status = 'PREPARING'
                """, refundType, policyVersion, amount, calculationSnapshotJson, refundId);
    }

    int rejectPreparation(UUID refundId, String reason) {
        return jdbc.update("""
                UPDATE platform.refund
                SET status = 'REJECTED', provider_error_code = ?, updated_at = now()
                WHERE id = ? AND status = 'PREPARING'
                """, reason, refundId);
    }

    void insertRefund(
            UUID refundId,
            String refundNo,
            OrderRow order,
            String reason,
            UUID userId,
            String businessNo,
            int policyVersion,
            Instant requestedAt) {
        jdbc.update("""
                INSERT INTO platform.refund(
                    id, refund_no, order_id, payment_transaction_id, funding_source,
                    amount, status, reason, requested_by, provider,
                    gateway_hold_business_no, policy_version, next_attempt_at,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, 'PREPARING', ?, ?, ?, ?, ?, ?, ?, ?)
                """, refundId, refundNo, order.id(), order.paymentTransactionId(),
                order.fundingSource(), reason, userId, order.provider(), businessNo, policyVersion,
                Timestamp.from(requestedAt), Timestamp.from(requestedAt),
                Timestamp.from(requestedAt));
    }

    int cancel(UUID refundId) {
        return jdbc.update("""
                UPDATE platform.refund
                SET status = 'CANCELED', updated_at = now(), lock_version = lock_version + 1
                WHERE id = ? AND status IN ('PREPARING', 'PENDING_APPROVAL')
                """, refundId);
    }

    int approve(UUID refundId, UUID actorId, Instant reviewedAt) {
        return jdbc.update("""
                UPDATE platform.refund
                SET status = 'SUBMITTING', reviewed_by = ?, reviewed_at = ?,
                    updated_at = now(), next_attempt_at = ?, lock_version = lock_version + 1
                WHERE id = ? AND status = 'PENDING_APPROVAL'
                """, actorId, Timestamp.from(reviewedAt), Timestamp.from(reviewedAt), refundId);
    }

    void reject(UUID refundId, UUID actorId, Instant reviewedAt, String reviewNote) {
        jdbc.update("""
                UPDATE platform.refund
                SET status = 'REJECTED', reviewed_by = ?, reviewed_at = ?,
                    review_note = ?, updated_at = now(), lock_version = lock_version + 1
                WHERE id = ? AND status = 'PENDING_APPROVAL'
                """, actorId, Timestamp.from(reviewedAt), reviewNote, refundId);
    }

    Optional<OrderRow> lockPaidOrder(UUID tenantId, UUID userId, String orderNo) {
        return jdbc.query("""
                        SELECT o.id, o.order_no, o.tenant_id, o.user_id, o.amount,
                               o.paid_at, o.successful_payment_transaction_id,
                               o.settlement_method, payment.provider,
                               grant_request.id AS grant_request_id,
                               grant_request.status AS grant_status,
                               grant_request.completed_at AS grant_completed_at
                        FROM platform.customer_order AS o
                        LEFT JOIN platform.payment_transaction AS payment
                          ON payment.id = o.successful_payment_transaction_id
                         AND payment.status = 'SUCCEEDED' AND payment.applied_to_order
                        JOIN platform.grant_request AS grant_request
                          ON grant_request.business_no = 'order:' || o.order_no
                        WHERE o.tenant_id = ? AND o.user_id = ? AND o.order_no = ?
                          AND o.status = 'PAID' AND o.paid_at IS NOT NULL
                          AND ((o.settlement_method = 'EXTERNAL' AND payment.id IS NOT NULL)
                               OR (o.settlement_method = 'BALANCE'
                                   AND o.successful_payment_transaction_id IS NULL))
                        FOR UPDATE OF o
                        """,
                (result, rowNumber) -> new OrderRow(
                        result.getObject("id", UUID.class), result.getString("order_no"),
                        result.getObject("tenant_id", UUID.class),
                        result.getObject("user_id", UUID.class), result.getLong("amount"),
                        result.getObject("paid_at", OffsetDateTime.class).toInstant(),
                        result.getObject("successful_payment_transaction_id", UUID.class),
                        result.getString("provider"), result.getString("settlement_method"),
                        result.getObject("grant_request_id", UUID.class),
                        result.getString("grant_status"),
                        instant(result.getObject("grant_completed_at", OffsetDateTime.class))),
                tenantId, userId, orderNo).stream().findFirst();
    }

    boolean hasOpenRefund(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM platform.refund
                    WHERE order_id = ?
                      AND status IN ('PREPARING', 'PENDING_APPROVAL', 'SUBMITTING',
                                     'PROCESSING', 'REVOKING'))
                """, Boolean.class, orderId));
    }

    Optional<RefundRow> findOwned(
            UUID tenantId, UUID userId, String refundNo, boolean lock) {
        String sql = refundSelect()
                + " WHERE refund.refund_no = ? AND customer_order.tenant_id = ?"
                + " AND customer_order.user_id = ?"
                + (lock ? " FOR UPDATE OF refund" : "");
        return jdbc.query(sql, (result, rowNumber) -> refundRow(result),
                        refundNo, tenantId, userId)
                .stream().findFirst();
    }

    Optional<RefundRow> find(String refundNo, boolean lock) {
        return jdbc.query(
                        refundSelect() + " WHERE refund.refund_no = ?"
                                + (lock ? " FOR UPDATE OF refund" : ""),
                        (result, rowNumber) -> refundRow(result), refundNo)
                .stream().findFirst();
    }

    Optional<RefundRow> findById(UUID refundId) {
        return jdbc.query(
                        refundSelect() + " WHERE refund.id = ?",
                        (result, rowNumber) -> refundRow(result), refundId)
                .stream().findFirst();
    }

    void auditApplied(
            UUID tenantId, UUID actorId, String refundNo, String orderNo) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type,
                    resource_id, outcome, detail)
                VALUES (?, ?, 'REFUND_APPLIED', 'REFUND', ?, 'SUCCESS',
                        jsonb_build_object('orderNo', ?, 'status', 'PREPARING'))
                """, tenantId, actorId, refundNo, orderNo);
    }

    void auditApproved(
            UUID tenantId, UUID actorId, String refundNo, long amount, String refundType) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type,
                    resource_id, outcome, detail)
                VALUES (?, ?, 'REFUND_APPROVED', 'REFUND', ?, 'SUCCESS',
                        jsonb_build_object('amount', ?, 'refundType', ?))
                """, tenantId, actorId, refundNo, amount, refundType);
    }

    void auditRejected(UUID tenantId, UUID actorId, String refundNo, long amount) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type,
                    resource_id, outcome, detail)
                VALUES (?, ?, 'REFUND_REJECTED', 'REFUND', ?, 'SUCCESS',
                        jsonb_build_object('amount', ?))
                """, tenantId, actorId, refundNo, amount);
    }

    private String refundSelect() {
        return """
                SELECT refund.id, refund.refund_no, refund.status, refund.refund_type,
                       refund.amount, refund.reason, refund.calculation_snapshot::text,
                       refund.reviewed_by, refund.reviewed_at, refund.review_note,
                       refund.provider_error_code, refund.gateway_hold_business_no,
                       refund.created_at, refund.updated_at,
                       customer_order.order_no, customer_order.tenant_id,
                       customer_order.user_id,
                       grant_request.id AS grant_request_id
                FROM platform.refund AS refund
                JOIN platform.customer_order AS customer_order
                  ON customer_order.id = refund.order_id
                JOIN platform.grant_request AS grant_request
                  ON grant_request.business_no = 'order:' || customer_order.order_no
                """;
    }

    private RefundRow refundRow(java.sql.ResultSet result) throws java.sql.SQLException {
        return new RefundRow(
                result.getObject("id", UUID.class), result.getString("refund_no"),
                result.getString("order_no"), result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class), result.getString("status"),
                result.getString("refund_type"), result.getLong("amount"),
                result.getString("reason"), result.getString("calculation_snapshot"),
                result.getObject("reviewed_by", UUID.class),
                instant(result.getObject("reviewed_at", OffsetDateTime.class)),
                result.getString("review_note"), result.getString("provider_error_code"),
                result.getString("gateway_hold_business_no"),
                result.getObject("grant_request_id", UUID.class),
                result.getObject("created_at", OffsetDateTime.class).toInstant(),
                result.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * @param grantStatus 平台自身为该订单记录的激活记录，由发件箱发布器
     *                    根据网关的激活响应写入
     *                    (QUEUED / ACTIVE / FAILED / REVOKED)
     * @param grantCompletedAt 仅当网关确认 ACTIVE 后才设置
     */
    record OrderRow(
            UUID id,
            String orderNo,
            UUID tenantId,
            UUID userId,
            long paidAmount,
            Instant paidAt,
            UUID paymentTransactionId,
            String provider,
            String fundingSource,
            UUID grantRequestId,
            String grantStatus,
            Instant grantCompletedAt) {
    }

    record RefundRow(
            UUID id,
            String refundNo,
            String orderNo,
            UUID tenantId,
            UUID userId,
            String status,
            String refundType,
            long amount,
            String reason,
            String calculationSnapshotJson,
            UUID reviewedBy,
            Instant reviewedAt,
            String reviewNote,
            String failureReason,
            String gatewayHoldBusinessNo,
            UUID grantRequestId,
            Instant createdAt,
            Instant updatedAt) {
    }
}
