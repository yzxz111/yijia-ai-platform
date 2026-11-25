package com.yuzhihuihe.apiplatform.platform.reconciliation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ReconciliationRepository {

    private final JdbcTemplate jdbc;

    ReconciliationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<RunRow> findRun(UUID runId) {
        return jdbc
                .query(
                        """
                        SELECT id, provider, bill_date, status, bill_sha256,
                               summary::text, started_at, completed_at, created_at
                        FROM platform.reconciliation_run
                        WHERE id = ?
                        """,
                        (result, rowNumber) -> run(result),
                        runId)
                .stream()
                .findFirst();
    }

    long countItems(UUID runId) {
        Long count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform.reconciliation_item WHERE run_id = ?",
                        Long.class,
                        runId);
        return count == null ? 0 : count;
    }

    List<ItemRow> findItems(
            UUID runId, ItemSort sort, SortDirection direction, int limit, int offset) {
        String order =
                " ORDER BY "
                        + sort.column()
                        + " "
                        + direction.keyword()
                        + ", id "
                        + direction.keyword();
        return jdbc.query(
                """
                SELECT id, provider_record_id, transaction_no, refund_no,
                       difference_type, status, provider_amount, platform_amount,
                       detail::text, resolved_by, resolution_note, resolved_at, created_at
                FROM platform.reconciliation_item
                WHERE run_id = ?
                """
                        + order
                        + " LIMIT ? OFFSET ?",
                (result, rowNumber) -> item(result),
                runId,
                limit,
                offset);
    }

    List<String> enabledProviders() {
        return jdbc
                .query(
                        """
                        SELECT DISTINCT provider FROM platform.payment_config
                        WHERE enabled ORDER BY provider
                        """,
                        (result, rowNumber) -> result.getString("provider"))
                .stream()
                .distinct()
                .toList();
    }

    int insertClaim(UUID runId, String provider, LocalDate billDate, UUID claimToken) {
        return jdbc.update(
                """
                INSERT INTO platform.reconciliation_run(
                    id, provider, bill_date, status, started_at,
                    job_claim_token, job_claimed_until)
                VALUES (?, ?, ?, 'RUNNING', now(), ?, now() + interval '30 minutes')
                ON CONFLICT (provider, bill_date) DO NOTHING
                """,
                runId,
                provider,
                billDate,
                claimToken);
    }

    ExistingClaim findClaim(String provider, LocalDate billDate) {
        return jdbc.queryForObject(
                """
                SELECT id, status FROM platform.reconciliation_run
                WHERE provider = ? AND bill_date = ?
                """,
                (result, rowNumber) ->
                        new ExistingClaim(
                                result.getObject("id", UUID.class), result.getString("status")),
                provider,
                billDate);
    }

    int reclaim(UUID runId, UUID claimToken) {
        return jdbc.update(
                """
                UPDATE platform.reconciliation_run
                SET status = 'RUNNING', bill_sha256 = NULL,
                    summary = '{}'::jsonb, started_at = now(), completed_at = NULL,
                    updated_at = now(),
                    job_claim_token = ?,
                    job_claimed_until = now() + interval '30 minutes'
                WHERE id = ? AND (
                    status = 'FAILED'
                    OR (status = 'RUNNING' AND (
                        job_claimed_until IS NULL OR job_claimed_until < now())))
                """,
                claimToken,
                runId);
    }

    void deleteItems(UUID runId) {
        jdbc.update("DELETE FROM platform.reconciliation_item WHERE run_id = ?", runId);
    }

    int auditStart(UUID actorUserId, UUID runId, String provider, LocalDate billDate) {
        return jdbc.update(
                """
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type,
                    resource_id, outcome, detail)
                SELECT tenant_id, ?, 'RECONCILIATION_STARTED', 'RECONCILIATION_RUN',
                       ?, 'SUCCESS', jsonb_build_object(
                           'provider', ?, 'billDate', ?)
                FROM platform.app_user WHERE id = ?
                """,
                actorUserId,
                runId.toString(),
                provider,
                billDate.toString(),
                actorUserId);
    }

    long countSuccessfulPayments(String provider, Instant from, Instant to) {
        Long count =
                jdbc.queryForObject(
                        """
                SELECT count(*)
                FROM platform.payment_transaction payment
                WHERE payment.provider = ? AND payment.status = 'SUCCEEDED'
                  AND payment.paid_at >= ? AND payment.paid_at < ?
                """,
                        Long.class,
                        provider,
                        Timestamp.from(from),
                        Timestamp.from(to));
        return count == null ? 0 : count;
    }

    long countSuccessfulRefunds(String provider, Instant from, Instant to) {
        Long count =
                jdbc.queryForObject(
                        """
                SELECT count(*)
                FROM platform.refund refund
                WHERE refund.provider = ? AND refund.status = 'COMPLETED'
                  AND refund.completed_at >= ? AND refund.completed_at < ?
                """,
                        Long.class,
                        provider,
                        Timestamp.from(from),
                        Timestamp.from(to));
        return count == null ? 0 : count;
    }

    List<PlatformPaymentSuccessRow> findSuccessfulPayments(
            String provider, Instant from, Instant to, String afterTransactionNo, int limit) {
        return jdbc.query(
                """
                SELECT payment.id, payment.transaction_no, payment.order_id,
                       COALESCE(recharge.recharge_no, payment.transaction_no)
                           AS reconciliation_business_no,
                       payment.amount
                FROM platform.payment_transaction payment
                LEFT JOIN platform.recharge_order recharge
                  ON recharge.id = payment.recharge_order_id
                WHERE payment.provider = ? AND payment.status = 'SUCCEEDED'
                  AND payment.paid_at >= ? AND payment.paid_at < ?
                  AND payment.transaction_no > ?
                ORDER BY payment.transaction_no
                LIMIT ?
                """,
                (result, rowNumber) ->
                        new PlatformPaymentSuccessRow(
                                result.getObject("id", UUID.class),
                                result.getString("transaction_no"),
                                result.getString("reconciliation_business_no"),
                                result.getObject("order_id", UUID.class),
                                result.getLong("amount")),
                provider,
                Timestamp.from(from),
                Timestamp.from(to),
                afterTransactionNo,
                limit);
    }

    List<PlatformRefundSuccessRow> findSuccessfulRefunds(
            String provider, Instant from, Instant to, String afterRefundNo, int limit) {
        return jdbc.query(
                """
                SELECT refund.id, refund.refund_no, refund.order_id,
                       refund.payment_transaction_id, refund.amount
                FROM platform.refund refund
                WHERE refund.provider = ? AND refund.status = 'COMPLETED'
                  AND refund.completed_at >= ? AND refund.completed_at < ?
                  AND refund.refund_no > ?
                ORDER BY refund.refund_no
                LIMIT ?
                """,
                (result, rowNumber) ->
                        new PlatformRefundSuccessRow(
                                result.getObject("id", UUID.class),
                                result.getString("refund_no"),
                                result.getObject("order_id", UUID.class),
                                result.getObject("payment_transaction_id", UUID.class),
                                result.getLong("amount")),
                provider,
                Timestamp.from(from),
                Timestamp.from(to),
                afterRefundNo,
                limit);
    }

    int completeRun(UUID runId, UUID claimToken, String billSha256, String summaryJson) {
        return jdbc.update(
                """
                UPDATE platform.reconciliation_run
                SET status = 'COMPLETED', bill_sha256 = ?, summary = ?::jsonb,
                    completed_at = now(), updated_at = now(),
                    job_claim_token = NULL, job_claimed_until = NULL
                WHERE id = ? AND status = 'RUNNING' AND job_claim_token = ?
                """,
                billSha256,
                summaryJson,
                runId,
                claimToken);
    }

    int renewLease(UUID runId, UUID claimToken) {
        return jdbc.update(
                """
                UPDATE platform.reconciliation_run
                SET job_claimed_until = now() + interval '30 minutes', updated_at = now()
                WHERE id = ? AND status = 'RUNNING' AND job_claim_token = ?
                """,
                runId,
                claimToken);
    }

    void insertItems(UUID runId, List<ItemInsert> items, int batchSize) {
        jdbc.batchUpdate(
                """
                INSERT INTO platform.reconciliation_item(
                    id, run_id, provider_record_id, transaction_no, refund_no,
                    difference_type, status, provider_amount, platform_amount, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (run_id, provider_record_id) DO NOTHING
                """,
                items,
                batchSize,
                (statement, item) -> {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, runId);
                    statement.setString(3, item.providerRecordId());
                    statement.setString(4, item.transactionNo());
                    statement.setString(5, item.refundNo());
                    statement.setString(6, item.differenceType());
                    statement.setString(7, item.status());
                    statement.setObject(8, item.providerAmount());
                    statement.setObject(9, item.platformAmount());
                    statement.setString(10, item.detailJson());
                });
    }

    int failRun(UUID runId, UUID claimToken, String summaryJson) {
        return jdbc.update(
                """
                UPDATE platform.reconciliation_run
                SET status = 'FAILED', summary = ?::jsonb, completed_at = now(),
                    updated_at = now(), job_claim_token = NULL, job_claimed_until = NULL
                WHERE id = ? AND status = 'RUNNING' AND job_claim_token = ?
                """,
                summaryJson,
                runId,
                claimToken);
    }

    void deleteUnclaimedAnomalies(List<UUID> anomalyIds) {
        if (anomalyIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(anomalyIds.size(), "?"));
        jdbc.update(
                """
                DELETE FROM platform.payment_anomaly
                WHERE status = 'OPEN' AND assignee IS NULL AND lock_version = 0
                  AND id IN (
                """
                        + placeholders
                        + ")",
                anomalyIds.toArray());
    }

    List<PaymentRow> findPayments(String provider, List<String> transactionNos) {
        if (transactionNos.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(transactionNos.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(provider);
        parameters.addAll(transactionNos);
        return jdbc.query(
                """
                SELECT payment.id, payment.transaction_no, payment.order_id,
                       payment.amount,
                       COALESCE(orders.currency, recharge.currency) AS currency,
                       payment.status,
                       COALESCE(orders.status, recharge.status) AS order_status
                FROM platform.payment_transaction payment
                LEFT JOIN platform.customer_order orders
                  ON orders.id = payment.order_id
                LEFT JOIN platform.recharge_order recharge
                  ON recharge.id = payment.recharge_order_id
                WHERE payment.provider = ? AND payment.transaction_no IN (
                """
                        + placeholders
                        + ")",
                (result, rowNumber) ->
                        new PaymentRow(
                                result.getObject("id", UUID.class),
                                result.getString("transaction_no"),
                                result.getObject("order_id", UUID.class),
                                result.getLong("amount"),
                                result.getString("currency"),
                                result.getString("status"),
                                result.getString("order_status")),
                parameters.toArray());
    }

    List<RefundRow> findRefunds(String provider, List<String> refundNos) {
        if (refundNos.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(refundNos.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(provider);
        parameters.addAll(refundNos);
        return jdbc.query(
                """
                SELECT id, refund_no, order_id, payment_transaction_id,
                       amount, status, provider_status
                FROM platform.refund
                WHERE provider = ? AND refund_no IN (
                """
                        + placeholders
                        + ")",
                (result, rowNumber) ->
                        new RefundRow(
                                result.getObject("id", UUID.class),
                                result.getString("refund_no"),
                                result.getObject("order_id", UUID.class),
                                result.getObject("payment_transaction_id", UUID.class),
                                result.getLong("amount"),
                                result.getString("status"),
                                result.getString("provider_status")),
                parameters.toArray());
    }

    private RunRow run(ResultSet result) throws SQLException {
        return new RunRow(
                result.getObject("id", UUID.class),
                result.getString("provider"),
                result.getObject("bill_date", LocalDate.class),
                result.getString("status"),
                result.getString("bill_sha256"),
                result.getString("summary"),
                instant(result, "started_at"),
                instant(result, "completed_at"),
                instant(result, "created_at"));
    }

    private ItemRow item(ResultSet result) throws SQLException {
        return new ItemRow(
                result.getObject("id", UUID.class),
                result.getString("provider_record_id"),
                result.getString("transaction_no"),
                result.getString("refund_no"),
                result.getString("difference_type"),
                result.getString("status"),
                nullableLong(result, "provider_amount"),
                nullableLong(result, "platform_amount"),
                result.getString("detail"),
                result.getObject("resolved_by", UUID.class),
                result.getString("resolution_note"),
                instant(result, "resolved_at"),
                instant(result, "created_at"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    enum ItemSort {
        CREATED_AT("created_at"),
        DIFFERENCE_TYPE("difference_type"),
        STATUS("status"),
        PROVIDER_AMOUNT("provider_amount"),
        PLATFORM_AMOUNT("platform_amount");

        private final String column;

        ItemSort(String column) {
            this.column = column;
        }

        String column() {
            return column;
        }
    }

    enum SortDirection {
        ASC("ASC"),
        DESC("DESC");

        private final String keyword;

        SortDirection(String keyword) {
            this.keyword = keyword;
        }

        String keyword() {
            return keyword;
        }
    }

    record ExistingClaim(UUID id, String status) {}

    record RunRow(
            UUID id,
            String provider,
            LocalDate billDate,
            String status,
            String billSha256,
            String summary,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt) {}

    record ItemRow(
            UUID id,
            String providerRecordId,
            String transactionNo,
            String refundNo,
            String differenceType,
            String status,
            Long providerAmount,
            Long platformAmount,
            String detail,
            UUID resolvedBy,
            String resolutionNote,
            Instant resolvedAt,
            Instant createdAt) {}

    record PlatformPaymentSuccessRow(
            UUID id,
            String transactionNo,
            String reconciliationBusinessNo,
            UUID orderId,
            long amount) {}

    record PlatformRefundSuccessRow(
            UUID id, String refundNo, UUID orderId, UUID paymentId, long amount) {}

    record PaymentRow(
            UUID id,
            String transactionNo,
            UUID orderId,
            long amount,
            String currency,
            String status,
            String orderStatus) {}

    record RefundRow(
            UUID id,
            String refundNo,
            UUID orderId,
            UUID paymentId,
            long amount,
            String status,
            String providerStatus) {}

    record ItemInsert(
            String providerRecordId,
            String transactionNo,
            String refundNo,
            String differenceType,
            String status,
            Long providerAmount,
            Long platformAmount,
            String detailJson) {}
}
