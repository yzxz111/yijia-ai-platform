package com.yuzhihuihe.apiplatform.platform.payment;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PaymentAnomalyRepository {

    private static final int BATCH_SIZE = 1_000;
    private static final String INSERT_SQL = """
            INSERT INTO platform.payment_anomaly(
                id, fingerprint, anomaly_type, provider, order_id,
                payment_transaction_id, refund_id, status, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?::jsonb)
            ON CONFLICT (fingerprint) WHERE status IN ('OPEN', 'INVESTIGATING')
            DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    PaymentAnomalyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(InsertRow insert) {
        jdbc.update(
                INSERT_SQL, insert.id(), insert.fingerprint(), insert.anomalyType(),
                insert.provider(), insert.orderId(), insert.transactionId(),
                insert.refundId(), insert.detailJson());
    }

    List<UUID> insertAll(List<InsertRow> inserts) {
        List<UUID> insertedIds = new ArrayList<>();
        for (int from = 0; from < inserts.size(); from += BATCH_SIZE) {
            List<InsertRow> batch = inserts.subList(
                    from, Math.min(from + BATCH_SIZE, inserts.size()));
            int[][] updateCounts = jdbc.batchUpdate(
                    INSERT_SQL, batch, BATCH_SIZE,
                    (statement, insert) -> {
                        statement.setObject(1, insert.id());
                        statement.setString(2, insert.fingerprint());
                        statement.setString(3, insert.anomalyType());
                        statement.setString(4, insert.provider());
                        statement.setObject(5, insert.orderId());
                        statement.setObject(6, insert.transactionId());
                        statement.setObject(7, insert.refundId());
                        statement.setString(8, insert.detailJson());
                    });
            int index = 0;
            for (int[] counts : updateCounts) {
                for (int count : counts) {
                    if (count > 0 || count == Statement.SUCCESS_NO_INFO) {
                        insertedIds.add(batch.get(index).id());
                    }
                    index++;
                }
            }
            if (index != batch.size()) {
                throw new IllegalStateException("payment_anomaly_batch_result_mismatch");
            }
        }
        return List.copyOf(insertedIds);
    }

    Optional<AnomalyRow> findActiveByFingerprint(String fingerprint) {
        return jdbc.query("""
                        SELECT id, fingerprint, anomaly_type, provider, order_id,
                               payment_transaction_id, refund_id, status, assignee,
                               detail::text, resolution_note, resolved_at,
                               created_at, updated_at, lock_version
                        FROM platform.payment_anomaly
                        WHERE fingerprint = ? AND status IN ('OPEN', 'INVESTIGATING')
                        """, (result, row) -> anomaly(result), fingerprint)
                .stream().findFirst();
    }

    Optional<AnomalyRow> findById(UUID id) {
        return jdbc.query("""
                        SELECT id, fingerprint, anomaly_type, provider, order_id,
                               payment_transaction_id, refund_id, status, assignee,
                               detail::text, resolution_note, resolved_at,
                               created_at, updated_at, lock_version
                        FROM platform.payment_anomaly WHERE id = ?
                        """, (result, row) -> anomaly(result), id)
                .stream().findFirst();
    }

    Optional<AnomalyRow> lockById(UUID id) {
        return jdbc.query("""
                        SELECT id, fingerprint, anomaly_type, provider, order_id,
                               payment_transaction_id, refund_id, status, assignee,
                               detail::text, resolution_note, resolved_at,
                               created_at, updated_at, lock_version
                        FROM platform.payment_anomaly WHERE id = ? FOR UPDATE
                        """, (result, row) -> anomaly(result), id)
                .stream().findFirst();
    }

    PageRows list(Query query) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> parameters = new ArrayList<>();
        add(where, parameters, "anomaly_type = ?", query.anomalyType());
        add(where, parameters, "status = ?", query.status());
        add(where, parameters, "provider = ?", query.provider());
        add(where, parameters, "created_at >= ?", timestamp(query.from()));
        add(where, parameters, "created_at < ?", timestamp(query.to()));
        add(where, parameters, "order_id = ?", query.orderId());
        String ordering = " ORDER BY " + query.sort().column() + " "
                + query.direction().keyword() + ", anomaly.id " + query.direction().keyword();
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM platform.payment_anomaly anomaly" + where,
                Long.class, parameters.toArray());
        List<Object> itemParameters = new ArrayList<>(parameters);
        itemParameters.add(query.size());
        itemParameters.add(query.offset());
        List<AnomalyRow> items = jdbc.query("""
                        SELECT id, fingerprint, anomaly_type, provider, order_id,
                               payment_transaction_id, refund_id, status, assignee,
                               detail::text, resolution_note, resolved_at,
                               created_at, updated_at, lock_version
                        FROM platform.payment_anomaly anomaly
                        """ + where + ordering + " LIMIT ? OFFSET ?",
                (result, row) -> anomaly(result), itemParameters.toArray());
        return new PageRows(items, total == null ? 0 : total);
    }

    int updateState(
            UUID id,
            long lockVersion,
            String status,
            UUID assignee,
            String resolutionNote) {
        return jdbc.update("""
                UPDATE platform.payment_anomaly
                SET status = ?, assignee = ?, resolution_note = ?,
                    resolved_at = CASE WHEN ? = 'RESOLVED' THEN now() ELSE NULL END,
                    updated_at = now(), lock_version = lock_version + 1
                WHERE id = ? AND lock_version = ?
                """, status, assignee, resolutionNote, status, id, lockVersion);
    }

    int auditUpdate(
            UUID actorUserId,
            UUID anomalyId,
            String orderId,
            String transactionId,
            String refundId,
            String oldStatus,
            String newStatus,
            String assignee) {
        return jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type,
                    resource_id, outcome, detail)
                SELECT tenant_id, ?, 'PAYMENT_ANOMALY_UPDATED', 'PAYMENT_ANOMALY',
                       ?, 'SUCCESS', jsonb_strip_nulls(jsonb_build_object(
                           'anomalyId', ?, 'orderId', ?,
                           'paymentTransactionId', ?, 'refundId', ?,
                           'oldStatus', ?, 'newStatus', ?, 'assignee', ?))
                FROM platform.app_user WHERE id = ?
                """, actorUserId, anomalyId.toString(), anomalyId.toString(),
                orderId, transactionId, refundId, oldStatus, newStatus, assignee, actorUserId);
    }

    private AnomalyRow anomaly(java.sql.ResultSet result) throws java.sql.SQLException {
        return new AnomalyRow(
                result.getObject("id", UUID.class), result.getString("fingerprint"),
                result.getString("anomaly_type"), result.getString("provider"),
                result.getObject("order_id", UUID.class),
                result.getObject("payment_transaction_id", UUID.class),
                result.getObject("refund_id", UUID.class), result.getString("status"),
                result.getObject("assignee", UUID.class), result.getString("detail"),
                result.getString("resolution_note"), instant(result, "resolved_at"),
                instant(result, "created_at"), instant(result, "updated_at"),
                result.getLong("lock_version"));
    }

    private static void add(
            StringBuilder where, List<Object> parameters, String condition, Object value) {
        if (value != null) {
            where.append(" AND ").append(condition);
            parameters.add(value);
        }
    }

    private static Instant instant(java.sql.ResultSet result, String column)
            throws java.sql.SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    enum Sort {
        CREATED_AT("anomaly.created_at"),
        UPDATED_AT("anomaly.updated_at"),
        ANOMALY_TYPE("anomaly.anomaly_type"),
        STATUS("anomaly.status");

        private final String column;

        Sort(String column) {
            this.column = column;
        }

        String column() {
            return column;
        }
    }

    enum Direction {
        ASC,
        DESC;

        String keyword() {
            return name();
        }
    }

    record InsertRow(
            UUID id,
            String fingerprint,
            String anomalyType,
            String provider,
            UUID orderId,
            UUID transactionId,
            UUID refundId,
            String detailJson) {
    }

    record Query(
            String anomalyType,
            String status,
            String provider,
            Instant from,
            Instant to,
            UUID orderId,
            Sort sort,
            Direction direction,
            int size,
            int offset) {
    }

    record AnomalyRow(
            UUID id,
            String fingerprint,
            String anomalyType,
            String provider,
            UUID orderId,
            UUID transactionId,
            UUID refundId,
            String status,
            UUID assignee,
            String detailJson,
            String resolutionNote,
            Instant resolvedAt,
            Instant createdAt,
            Instant updatedAt,
            long lockVersion) {
    }

    record PageRows(List<AnomalyRow> items, long total) {
    }
}
