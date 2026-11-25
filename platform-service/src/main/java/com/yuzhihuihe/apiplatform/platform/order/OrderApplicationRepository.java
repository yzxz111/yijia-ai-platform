package com.yuzhihuihe.apiplatform.platform.order;

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
class OrderApplicationRepository {

    private final JdbcTemplate jdbc;

    OrderApplicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void lock(UUID userId, String idempotencyKey) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, userId + ":" + idempotencyKey);
                statement.execute();
                return null;
            }
        });
    }

    boolean hasActiveUser(UUID tenantId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM platform.app_user
                    WHERE id = ? AND tenant_id = ? AND status = 'ACTIVE')
                """, Boolean.class, userId, tenantId));
    }

    Optional<ExistingOrderRow> findExisting(UUID userId, String idempotencyKey) {
        return jdbc.query("""
                        SELECT tenant_id, package_plan_version_id, order_no, status,
                               amount, currency, expires_at, package_snapshot::text
                        FROM platform.customer_order
                        WHERE user_id = ? AND idempotency_key = ?
                        FOR SHARE
                        """,
                (result, rowNumber) -> new ExistingOrderRow(
                        result.getObject("tenant_id", UUID.class),
                        result.getObject("package_plan_version_id", UUID.class),
                        result.getString("order_no"),
                        result.getString("status"),
                        result.getLong("amount"),
                        result.getString("currency"),
                        result.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        result.getString("package_snapshot")),
                userId, idempotencyKey).stream().findFirst();
    }

    void insertOrder(
            UUID orderId,
            String orderNo,
            UUID tenantId,
            UUID userId,
            UUID packageVersionId,
            long amount,
            String currency,
            Instant expiresAt,
            String packageSnapshot,
            String idempotencyKey) {
        jdbc.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, expires_at,
                    package_snapshot, idempotency_key, next_close_at, inventory_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 'UNSELECTED', ?, ?::jsonb, ?, ?, 'RESERVED')
                """, orderId, orderNo, tenantId, userId, packageVersionId,
                amount, currency, Timestamp.from(expiresAt), packageSnapshot,
                idempotencyKey, Timestamp.from(expiresAt));
    }

    void insertAudit(
            UUID tenantId,
            UUID userId,
            String orderNo,
            UUID packageVersionId,
            long amount,
            String currency,
            String idempotencyKey) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type,
                    resource_id, outcome, detail)
                VALUES (?, ?, 'ORDER_CREATED', 'ORDER', ?, 'SUCCESS',
                        jsonb_build_object(
                            'packageVersionId', ?,
                            'amount', ?,
                            'currency', ?,
                            'idempotencyKey', ?))
                """, tenantId, userId, orderNo, packageVersionId,
                amount, currency, idempotencyKey);
    }

    record ExistingOrderRow(
            UUID tenantId,
            UUID packageVersionId,
            String orderNo,
            String status,
            long amount,
            String currency,
            Instant expiresAt,
            String packageSnapshot) {
    }
}
