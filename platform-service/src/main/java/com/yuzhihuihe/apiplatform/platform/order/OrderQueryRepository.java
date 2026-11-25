package com.yuzhihuihe.apiplatform.platform.order;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OrderQueryRepository {

    private final JdbcTemplate jdbc;

    OrderQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    OrderPageRows list(
            UUID tenantId, UUID userId, String status, int size, int offset) {
        String where = " WHERE o.tenant_id = ? AND o.user_id = ?"
                + (status == null ? "" : " AND o.status = ?");
        List<Object> filters = new ArrayList<>(List.of(tenantId, userId));
        if (status != null) {
            filters.add(status);
        }
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM platform.customer_order o" + where,
                Long.class, filters.toArray());
        List<Object> itemArguments = new ArrayList<>(filters);
        itemArguments.add(size);
        itemArguments.add(offset);
        List<OrderRow> items = jdbc.query("""
                        SELECT o.id, o.order_no,
                               COALESCE(o.package_snapshot->>'packageCode', p.code) AS package_code,
                               COALESCE(o.package_snapshot->>'packageName', p.name) AS package_name,
                               o.amount, o.currency, o.status, o.settlement_method,
                               o.payment_provider,
                               (SELECT pt.status FROM platform.payment_transaction pt
                                WHERE pt.order_id = o.id
                                ORDER BY pt.updated_at DESC, pt.id DESC LIMIT 1) AS payment_status,
                               (SELECT r.status FROM platform.refund r
                                WHERE r.order_id = o.id
                                ORDER BY r.updated_at DESC, r.id DESC LIMIT 1) AS refund_status,
                               o.paid_at, o.closed_at, o.created_at, o.updated_at
                        FROM platform.customer_order o
                        JOIN platform.package_plan_version pv ON pv.id = o.package_plan_version_id
                        JOIN platform.package_plan p ON p.id = pv.package_plan_id
                        """ + where + """

                        ORDER BY o.created_at DESC, o.id DESC
                        LIMIT ? OFFSET ?
                        """, (result, rowNumber) -> orderRow(result), itemArguments.toArray());
        return new OrderPageRows(items, total == null ? 0 : total);
    }

    Optional<DetailBaseRow> findDetailBase(UUID tenantId, UUID userId, String orderNo) {
        return jdbc.query("""
                        SELECT o.id, o.order_no,
                               COALESCE(o.package_snapshot->>'packageCode', p.code) AS package_code,
                               COALESCE(o.package_snapshot->>'packageName', p.name) AS package_name,
                               o.amount, o.currency, o.status, o.settlement_method,
                               o.payment_provider,
                               (SELECT pt.status FROM platform.payment_transaction pt
                                WHERE pt.order_id = o.id
                                ORDER BY pt.updated_at DESC, pt.id DESC LIMIT 1) AS payment_status,
                               (SELECT r.status FROM platform.refund r
                                WHERE r.order_id = o.id
                                ORDER BY r.updated_at DESC, r.id DESC LIMIT 1) AS refund_status,
                               o.paid_at, o.closed_at, o.created_at, o.updated_at,
                               o.package_snapshot::text, o.expires_at
                        FROM platform.customer_order o
                        JOIN platform.package_plan_version pv ON pv.id = o.package_plan_version_id
                        JOIN platform.package_plan p ON p.id = pv.package_plan_id
                        WHERE o.tenant_id = ? AND o.user_id = ? AND o.order_no = ?
                        """, (result, rowNumber) -> new DetailBaseRow(
                result.getObject("id", UUID.class),
                orderRow(result),
                result.getString("package_snapshot"),
                result.getObject("expires_at", OffsetDateTime.class).toInstant()),
                tenantId, userId, orderNo).stream().findFirst();
    }

    List<PaymentAttemptRow> findPaymentAttempts(UUID orderId) {
        return jdbc.query("""
                        SELECT transaction_no, provider, payment_product, status,
                               provider_status, provider_error_code,
                               CASE WHEN status IN ('CREATED', 'PENDING')
                                    THEN payment_action::text END AS payment_action,
                               paid_at, created_at
                        FROM platform.payment_transaction
                        WHERE order_id = ?
                        ORDER BY created_at, id
                        """, (result, rowNumber) -> new PaymentAttemptRow(
                result.getString("transaction_no"),
                result.getString("provider"),
                result.getString("payment_product"),
                result.getString("status"),
                result.getString("provider_status"),
                result.getString("provider_error_code"),
                result.getString("payment_action"),
                instant(result.getObject("paid_at", OffsetDateTime.class)),
                result.getObject("created_at", OffsetDateTime.class).toInstant()), orderId);
    }

    Optional<GrantRow> findGrant(String businessNo) {
        return jdbc.query("""
                        SELECT id, status, updated_at
                        FROM platform.grant_request
                        WHERE business_no = ?
                        """, (result, rowNumber) -> new GrantRow(
                result.getObject("id", UUID.class),
                result.getString("status"),
                result.getObject("updated_at", OffsetDateTime.class).toInstant()),
                businessNo).stream().findFirst();
    }

    Optional<RefundRow> findLatestRefund(UUID orderId) {
        return jdbc.query("""
                        SELECT refund_no, status, refund_type, amount,
                               created_at, reviewed_at, completed_at
                        FROM platform.refund
                        WHERE order_id = ?
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """, (result, rowNumber) -> new RefundRow(
                result.getString("refund_no"),
                result.getString("status"),
                result.getString("refund_type"),
                result.getLong("amount"),
                result.getObject("created_at", OffsetDateTime.class).toInstant(),
                instant(result.getObject("reviewed_at", OffsetDateTime.class)),
                instant(result.getObject("completed_at", OffsetDateTime.class))),
                orderId).stream().findFirst();
    }

    private static OrderRow orderRow(java.sql.ResultSet result) throws java.sql.SQLException {
        return new OrderRow(
                result.getObject("id", UUID.class),
                result.getString("order_no"),
                result.getString("package_code"),
                result.getString("package_name"),
                result.getLong("amount"),
                result.getString("currency"),
                result.getString("status"),
                result.getString("settlement_method"),
                result.getString("payment_provider"),
                result.getString("payment_status"),
                result.getString("refund_status"),
                instant(result.getObject("paid_at", OffsetDateTime.class)),
                instant(result.getObject("closed_at", OffsetDateTime.class)),
                result.getObject("created_at", OffsetDateTime.class).toInstant(),
                result.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    record OrderPageRows(List<OrderRow> items, long total) {
    }

    record DetailBaseRow(UUID orderId, OrderRow order, String packageSnapshot, Instant expiresAt) {
    }

    record OrderRow(
            UUID id, String orderNo, String packageCode, String packageName,
            long amount, String currency, String status, String settlementMethod,
            String paymentProvider, String paymentStatus, String refundStatus,
            Instant paidAt, Instant closedAt, Instant createdAt, Instant updatedAt) {
    }

    record PaymentAttemptRow(
            String transactionNo, String provider, String paymentProduct, String status,
            String providerStatus, String providerErrorCode, String paymentAction,
            Instant paidAt, Instant createdAt) {
    }

    record GrantRow(UUID id, String status, Instant updatedAt) {
    }

    record RefundRow(
            String refundNo, String status, String refundType, Long amount,
            Instant requestedAt, Instant reviewedAt, Instant completedAt) {
    }
}
