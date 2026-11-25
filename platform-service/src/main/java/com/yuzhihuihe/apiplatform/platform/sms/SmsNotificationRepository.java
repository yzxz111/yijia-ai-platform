package com.yuzhihuihe.apiplatform.platform.sms;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class SmsNotificationRepository {

    private final JdbcTemplate jdbc;

    SmsNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<RecipientRow> notificationRecipients(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT phone_ciphertext, phone_last4
                FROM platform.app_user
                WHERE tenant_id = ? AND id = ?
                  AND phone_verified_at IS NOT NULL
                  AND sms_notifications_enabled = true
                """, (result, rowNumber) -> new RecipientRow(
                result.getBytes("phone_ciphertext"), result.getString("phone_last4")),
                tenantId, userId);
    }

    List<RecipientRow> controlledRecipients(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT phone_ciphertext, phone_last4
                FROM platform.app_user
                WHERE tenant_id = ? AND id = ?
                  AND phone_verified_at IS NOT NULL
                """, (result, rowNumber) -> new RecipientRow(
                result.getBytes("phone_ciphertext"), result.getString("phone_last4")),
                tenantId, userId);
    }

    List<GrantRow> grant(UUID grantId) {
        return jdbc.query("""
                SELECT grant_request.tenant_id, grant_request.user_id,
                       grant_request.completed_at, customer_order.amount,
                       (customer_order.package_snapshot->>'durationDays')::integer AS duration_days
                FROM platform.grant_request AS grant_request
                JOIN platform.customer_order AS customer_order
                  ON grant_request.business_no = 'order:' || customer_order.order_no
                WHERE grant_request.id = ? AND grant_request.status = 'ACTIVE'
                  AND grant_request.completed_at IS NOT NULL
                  AND jsonb_exists(customer_order.package_snapshot, 'durationDays')
                """, (result, rowNumber) -> new GrantRow(
                result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getObject("completed_at", OffsetDateTime.class),
                result.getLong("amount"), result.getInt("duration_days")), grantId);
    }

    int insert(UUID id, UUID tenantId, UUID userId, String templateKey, String templateCode,
            byte[] phoneCiphertext, String phoneLast4, String variablesJson,
            byte[] sensitiveVariables, String deduplicationKey,
            Instant nextAttemptAt, Instant createdAt, Instant updatedAt) {
        return jdbc.update("""
                INSERT INTO platform.sms_delivery(
                    id, tenant_id, user_id, template_key, template_code,
                    phone_ciphertext, phone_last4, variables,
                    sensitive_variables_ciphertext, deduplication_key,
                    next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)
                ON CONFLICT (deduplication_key) DO NOTHING
                """, id, tenantId, userId, templateKey, templateCode,
                phoneCiphertext, phoneLast4, variablesJson, sensitiveVariables,
                deduplicationKey, Timestamp.from(nextAttemptAt),
                Timestamp.from(createdAt), Timestamp.from(updatedAt));
    }

    void insertControlled(UUID id, UUID tenantId, UUID userId,
            String templateKey, String templateCode,
            byte[] phoneCiphertext, String phoneLast4, String variablesJson,
            byte[] sensitiveVariables, String deduplicationKey,
            Instant nextAttemptAt, Instant createdAt, Instant updatedAt) {
        jdbc.update("""
                INSERT INTO platform.sms_delivery(
                    id, tenant_id, user_id, template_key, template_code,
                    phone_ciphertext, phone_last4, variables,
                    sensitive_variables_ciphertext, deduplication_key,
                    next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)
                ON CONFLICT (deduplication_key) DO NOTHING
                """, id, tenantId, userId, templateKey, templateCode,
                phoneCiphertext, phoneLast4, variablesJson, sensitiveVariables,
                deduplicationKey, Timestamp.from(nextAttemptAt),
                Timestamp.from(createdAt), Timestamp.from(updatedAt));
    }

    UUID findDeliveryId(String deduplicationKey) {
        return jdbc.queryForObject(
                "SELECT id FROM platform.sms_delivery WHERE deduplication_key = ?",
                UUID.class, deduplicationKey);
    }

    List<TemplateRow> templates(String templateKey) {
        return jdbc.query("""
                SELECT template_key, template_code, template_type,
                       variable_names::text, integration_status
                FROM platform.sms_template WHERE template_key = ?
                """, (result, rowNumber) -> new TemplateRow(
                result.getString("template_key"), result.getString("template_code"),
                result.getString("template_type"), result.getString("variable_names"),
                result.getString("integration_status")), templateKey);
    }

    record RecipientRow(byte[] ciphertext, String last4) {
    }

    record GrantRow(
            UUID tenantId, UUID userId, OffsetDateTime completedAt,
            long amountFen, int durationDays) {
    }

    record TemplateRow(
            String key, String code, String type, String variableNamesJson, String status) {
    }
}
