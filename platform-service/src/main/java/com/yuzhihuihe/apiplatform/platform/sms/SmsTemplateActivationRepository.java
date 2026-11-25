package com.yuzhihuihe.apiplatform.platform.sms;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class SmsTemplateActivationRepository {

    private final JdbcTemplate jdbc;

    SmsTemplateActivationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    TemplateRow lockTemplate(String templateKey) {
        return jdbc.query("""
                SELECT template_key,template_code,template_type,variable_names::text,integration_status
                FROM platform.sms_template WHERE template_key=? FOR UPDATE
                """, (result, row) -> new TemplateRow(
                result.getString(1), result.getString(2), result.getString(3),
                result.getString(4), result.getString(5)), templateKey)
                .stream().findFirst().orElse(null);
    }

    void activate(String templateKey) {
        jdbc.update("UPDATE platform.sms_template SET integration_status='ACTIVE',updated_at=now() WHERE template_key=? AND integration_status='PENDING_INTEGRATION'",
                templateKey);
    }

    void audit(UUID actorUserId, String templateKey,
            String providerBusinessNo, String templateCode) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(actor_user_id,action,resource_type,resource_id,request_id,outcome,detail)
                VALUES (?,'SMS_TEMPLATE_ACTIVATED','SMS_TEMPLATE',?,?,'SUCCESS',
                        jsonb_build_object('templateKey',?,'providerTemplateNo',?))
                """, actorUserId, templateKey, providerBusinessNo, templateKey, templateCode);
    }

    record TemplateRow(
            String key, String code, String type, String variablesJson, String status) {
    }
}
