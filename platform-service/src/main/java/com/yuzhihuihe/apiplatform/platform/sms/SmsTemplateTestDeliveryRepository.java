package com.yuzhihuihe.apiplatform.platform.sms;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
class SmsTemplateTestDeliveryRepository {

    private final JdbcTemplate jdbc;

    SmsTemplateTestDeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void lockBusinessNo(String businessNo) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (RowCallbackHandler) ignored -> { }, businessNo);
    }

    List<DeliveryRow> findByBusinessNo(String businessNo) {
        return jdbc.query("""
                SELECT delivery.id, delivery.template_key,
                       delivery.phone_last4, delivery.status
                FROM platform.admin_audit_log audit
                JOIN platform.sms_delivery delivery
                  ON delivery.id::text = audit.resource_id
                WHERE audit.action='SMS_TEMPLATE_TEST_QUEUED'
                  AND audit.request_id=?
                ORDER BY audit.id LIMIT 1
                """, (result, row) -> delivery(result), businessNo);
    }

    void audit(UUID actorUserId, UUID deliveryId, String businessNo, String templateKey) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    actor_user_id, action, resource_type, resource_id,
                    request_id, outcome, detail)
                VALUES (?, 'SMS_TEMPLATE_TEST_QUEUED', 'SMS_DELIVERY', ?, ?, 'SUCCESS',
                        jsonb_build_object('templateKey', ?))
                """, actorUserId, deliveryId.toString(), businessNo, templateKey);
    }

    DeliveryRow find(UUID deliveryId) {
        return jdbc.query("""
                SELECT id, template_key, phone_last4, status
                FROM platform.sms_delivery WHERE id=?
                """, (result, row) -> delivery(result), deliveryId)
                .stream().findFirst().orElse(null);
    }

    private static DeliveryRow delivery(java.sql.ResultSet result) throws java.sql.SQLException {
        return new DeliveryRow(
                result.getObject("id", UUID.class), result.getString("template_key"),
                result.getString("phone_last4"), result.getString("status"));
    }

    record DeliveryRow(UUID deliveryId, String templateKey, String phoneLast4, String status) {
    }
}
