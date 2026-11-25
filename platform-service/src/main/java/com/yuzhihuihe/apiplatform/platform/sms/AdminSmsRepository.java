package com.yuzhihuihe.apiplatform.platform.sms;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
class AdminSmsRepository {

    private final JdbcTemplate jdbc;

    AdminSmsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<TemplateRow> listTemplates() {
        return jdbc.query("""
                SELECT template_key, template_code, template_type, integration_status,
                       created_at, updated_at
                FROM platform.sms_template
                ORDER BY template_key
                """, (result, row) -> new TemplateRow(
                result.getString("template_key"), result.getString("template_code"),
                result.getString("template_type"), result.getString("integration_status"),
                instant(result, "created_at"), instant(result, "updated_at")));
    }

    PageRows<DeliveryRow> listDeliveries(DeliveryFilter filter) {
        String where = " WHERE 1=1"
                + (filter.status() == null ? "" : " AND delivery.status = ?")
                + (filter.templateKey() == null ? "" : " AND delivery.template_key = ?")
                + (filter.searchField() == null ? ""
                        : " AND " + searchPredicate(filter.searchField()));
        List<Object> filters = new ArrayList<>();
        if (filter.status() != null) filters.add(filter.status());
        if (filter.templateKey() != null) filters.add(filter.templateKey());
        if (filter.searchField() != null) filters.add(filter.searchValue());

        String from = " FROM platform.sms_delivery delivery LEFT JOIN platform.app_user app_user ON app_user.id = delivery.user_id";
        long total = jdbc.queryForObject(
                "SELECT count(*)" + from + where, Long.class, filters.toArray());
        List<Object> arguments = new ArrayList<>(filters);
        arguments.add(filter.size());
        arguments.add(filter.offset());
        List<DeliveryRow> items = jdbc.query("""
                SELECT delivery.id, delivery.user_id, app_user.user_no, app_user.display_name,
                       delivery.template_key, delivery.template_code,
                       delivery.phone_last4, delivery.status, delivery.attempt_count,
                       delivery.next_attempt_at, delivery.sanitized_error,
                       delivery.sent_at, delivery.created_at, delivery.updated_at
                """ + from + where + order(filter) + " LIMIT ? OFFSET ?",
                (result, row) -> delivery(result), arguments.toArray());
        return new PageRows<>(items, total);
    }

    void lockRetryBusinessNo(String businessNo) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (RowCallbackHandler) ignored -> { }, businessNo);
    }

    List<RetryAuditRow> retryAudits(String businessNo) {
        return jdbc.query("""
                SELECT resource_id
                FROM platform.admin_audit_log
                WHERE action = 'SMS_DELIVERY_RETRIED' AND request_id = ?
                ORDER BY id
                LIMIT 1
                """, (result, row) -> new RetryAuditRow(
                result.getString("resource_id")), businessNo);
    }

    RetryTargetRow lockRetryTarget(UUID deliveryId) {
        return jdbc.query("""
                SELECT id, tenant_id, status
                FROM platform.sms_delivery
                WHERE id = ?
                FOR UPDATE
                """, (result, row) -> new RetryTargetRow(
                result.getObject("id", UUID.class),
                result.getObject("tenant_id", UUID.class),
                result.getString("status")), deliveryId).stream().findFirst().orElse(null);
    }

    int resetForRetry(UUID deliveryId) {
        return jdbc.update("""
                UPDATE platform.sms_delivery
                SET status = 'PENDING', attempt_count = 0,
                    claim_token = NULL, claimed_until = NULL,
                    next_attempt_at = now(), provider_request_id = NULL,
                    provider_biz_id = NULL, sanitized_error = NULL,
                    sent_at = NULL, updated_at = now()
                WHERE id = ? AND status IN ('FAILED', 'DEAD')
                """, deliveryId);
    }

    void auditRetry(RetryTargetRow target, UUID actorUserId,
            UUID deliveryId, String businessNo) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type,
                    resource_id, request_id, outcome, detail)
                VALUES (?, ?, 'SMS_DELIVERY_RETRIED', 'SMS_DELIVERY', ?, ?, 'SUCCESS',
                        jsonb_build_object(
                            'previousStatus', ?, 'status', 'PENDING'))
                """, target.tenantId(), actorUserId, deliveryId.toString(),
                businessNo, target.status());
    }

    DeliveryRow findDelivery(UUID id) {
        return jdbc.query("""
                SELECT delivery.id, delivery.user_id, app_user.user_no, app_user.display_name,
                       delivery.template_key, delivery.template_code, delivery.phone_last4, delivery.status,
                       delivery.attempt_count, delivery.next_attempt_at, delivery.sanitized_error,
                       delivery.sent_at, delivery.created_at, delivery.updated_at
                FROM platform.sms_delivery delivery
                LEFT JOIN platform.app_user app_user ON app_user.id = delivery.user_id
                WHERE delivery.id = ?
                """, (result, row) -> delivery(result), id).stream().findFirst().orElse(null);
    }

    private static DeliveryRow delivery(java.sql.ResultSet result) throws java.sql.SQLException {
        return new DeliveryRow(
                result.getObject("id", UUID.class), result.getObject("user_id", UUID.class),
                result.getString("user_no"), result.getString("display_name"),
                result.getString("template_key"), result.getString("template_code"),
                result.getString("phone_last4"), result.getString("status"),
                result.getInt("attempt_count"), instant(result, "next_attempt_at"),
                result.getString("sanitized_error"), instant(result, "sent_at"),
                instant(result, "created_at"), instant(result, "updated_at"));
    }

    private static String searchPredicate(SearchField field) {
        return switch (field) {
            case DELIVERY_ID -> "delivery.id = ?";
            case PHONE_LAST4 -> "delivery.phone_last4 = ?";
            case TEMPLATE_KEY -> "delivery.template_key = ?";
        };
    }

    private static String order(DeliveryFilter filter) {
        String column = switch (filter.sort()) {
            case CREATED_AT -> "delivery.created_at";
            case UPDATED_AT -> "delivery.updated_at";
            case STATUS -> "delivery.status";
            case ATTEMPT_COUNT -> "delivery.attempt_count";
            case NEXT_ATTEMPT_AT -> "delivery.next_attempt_at";
            case TEMPLATE_KEY -> "delivery.template_key";
        };
        return " ORDER BY " + column + " " + filter.direction()
                + ", delivery.id " + filter.direction();
    }

    private static Instant instant(java.sql.ResultSet result, String column)
            throws java.sql.SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    enum Sort {
        CREATED_AT,
        UPDATED_AT,
        STATUS,
        ATTEMPT_COUNT,
        NEXT_ATTEMPT_AT,
        TEMPLATE_KEY
    }

    enum SearchField {
        DELIVERY_ID,
        PHONE_LAST4,
        TEMPLATE_KEY
    }

    record DeliveryFilter(
            String status, String templateKey, SearchField searchField, Object searchValue,
            Sort sort, String direction, int size, int offset) {
    }

    record PageRows<T>(List<T> items, long total) {
    }

    record TemplateRow(
            String templateKey, String templateCode, String templateType,
            String integrationStatus, Instant createdAt, Instant updatedAt) {
    }

    record DeliveryRow(
            UUID id, UUID userId, String userNo, String userDisplayName,
            String templateKey, String templateCode, String phoneLast4, String status,
            int attemptCount, Instant nextAttemptAt, String sanitizedError,
            Instant sentAt, Instant createdAt, Instant updatedAt) {
    }

    record RetryAuditRow(String resourceId) {
    }

    record RetryTargetRow(UUID id, UUID tenantId, String status) {
    }
}
