package com.yuzhihuihe.apiplatform.platform.wallet;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RechargeOrderRepository {

    private final JdbcTemplate jdbc;

    RechargeOrderRepository(JdbcTemplate jdbc) {
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

    OrderRow findExisting(UUID tenantId, UUID userId, String idempotencyKey) {
        return jdbc.query("""
                SELECT id, recharge_no, option_id, paid_amount, bonus_amount,
                       credited_amount, currency, status, expires_at,
                       paid_at, closed_at, created_at, updated_at
                FROM platform.recharge_order
                WHERE tenant_id = ? AND user_id = ? AND idempotency_key = ?
                FOR SHARE
                """, (result, rowNumber) -> order(result), tenantId, userId, idempotencyKey)
                .stream().findFirst().orElse(null);
    }

    void insert(
            UUID id, String rechargeNo, UUID tenantId, UUID userId, UUID optionId,
            long amount, String idempotencyKey, Instant expiresAt, Instant createdAt) {
        jdbc.update("""
                INSERT INTO platform.recharge_order(
                    id, recharge_no, tenant_id, user_id, option_id,
                    paid_amount, bonus_amount, credited_amount, currency,
                    status, idempotency_key, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, 'CNY', 'PENDING', ?, ?, ?, ?)
                """, id, rechargeNo, tenantId, userId, optionId, amount, amount,
                idempotencyKey, Timestamp.from(expiresAt), Timestamp.from(createdAt),
                Timestamp.from(createdAt));
    }

    long count(UUID tenantId, UUID userId) {
        return jdbc.queryForObject("""
                SELECT count(*)
                FROM platform.recharge_order AS recharge
                WHERE recharge.tenant_id = ? AND recharge.user_id = ?
                """, Long.class, tenantId, userId);
    }

    int closeExpiredForOwner(UUID tenantId, UUID userId, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        return jdbc.update("""
                UPDATE platform.recharge_order
                SET status = 'CLOSED', closed_at = ?, updated_at = ?
                WHERE tenant_id = ? AND user_id = ?
                  AND status = 'PENDING' AND expires_at <= ?
                """, timestamp, timestamp, tenantId, userId, timestamp);
    }

    int closeExpiredBatch(Instant now, int batchSize) {
        Timestamp timestamp = Timestamp.from(now);
        return jdbc.query("""
                WITH candidate AS (
                    SELECT id
                    FROM platform.recharge_order
                    WHERE status = 'PENDING' AND expires_at <= ?
                    ORDER BY expires_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE platform.recharge_order AS recharge
                SET status = 'CLOSED', closed_at = ?, updated_at = ?
                FROM candidate
                WHERE recharge.id = candidate.id
                RETURNING recharge.id
                """, (result, rowNumber) -> result.getObject("id", UUID.class),
                timestamp, batchSize, timestamp, timestamp).size();
    }

    List<OrderRow> findAll(
            UUID tenantId, UUID userId, int size, int offset,
            OrderSort sort, SortDirection direction) {
        String ordering = " ORDER BY " + sort.column + " " + direction
                + ", recharge.id " + direction;
        return jdbc.query("""
                        SELECT recharge_no, option_id, paid_amount, bonus_amount,
                               credited_amount, currency, status, expires_at,
                               paid_at, closed_at, created_at, updated_at
                        FROM platform.recharge_order AS recharge
                        WHERE recharge.tenant_id = ? AND recharge.user_id = ?
                        """ + ordering + " LIMIT ? OFFSET ?",
                (result, rowNumber) -> order(result), tenantId, userId, size, offset);
    }

    List<OptionRow> findOptions(UUID tenantId) {
        return jdbc.query("""
                SELECT id, amount, display_order
                FROM platform.recharge_option
                WHERE tenant_id = ? AND enabled
                ORDER BY display_order, amount, id
                """, (result, rowNumber) -> new OptionRow(
                result.getObject("id", UUID.class),
                result.getLong("amount"),
                result.getInt("display_order")), tenantId);
    }

    OrderRow findByNumber(UUID tenantId, UUID userId, String rechargeNo) {
        return jdbc.query("""
                SELECT recharge_no, option_id, paid_amount, bonus_amount,
                       credited_amount, currency, status, expires_at,
                       paid_at, closed_at, created_at, updated_at
                FROM platform.recharge_order
                WHERE tenant_id = ? AND user_id = ? AND recharge_no = ?
                """, (result, rowNumber) -> order(result), tenantId, userId, rechargeNo)
                .stream().findFirst().orElse(null);
    }

    boolean optionAvailable(UUID tenantId, UUID optionId, long amount) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1
                    FROM platform.recharge_option
                    WHERE tenant_id = ? AND id = ? AND enabled AND amount = ?)
                """, Boolean.class, tenantId, optionId, amount));
    }

    boolean activeUserExists(UUID tenantId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM platform.app_user
                    WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE')
                """, Boolean.class, tenantId, userId));
    }

    private OrderRow order(ResultSet result) throws SQLException {
        return new OrderRow(
                result.getString("recharge_no"),
                result.getObject("option_id", UUID.class),
                result.getLong("paid_amount"),
                result.getLong("bonus_amount"),
                result.getLong("credited_amount"),
                result.getString("currency"),
                result.getString("status"),
                instant(result, "expires_at"),
                instant(result, "paid_at"),
                instant(result, "closed_at"),
                instant(result, "created_at"),
                instant(result, "updated_at"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    enum OrderSort {
        CREATED_AT("recharge.created_at"),
        PAID_AMOUNT("recharge.paid_amount"),
        STATUS("recharge.status");

        private final String column;

        OrderSort(String column) {
            this.column = column;
        }
    }

    enum SortDirection {
        ASC,
        DESC
    }

    record OrderRow(
            String rechargeNo,
            UUID optionId,
            long paidAmount,
            long bonusAmount,
            long creditedAmount,
            String currency,
            String status,
            Instant expiresAt,
            Instant paidAt,
            Instant closedAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    record OptionRow(UUID id, long amount, int displayOrder) {
    }
}
