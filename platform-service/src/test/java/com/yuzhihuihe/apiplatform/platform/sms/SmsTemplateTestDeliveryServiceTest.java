package com.yuzhihuihe.apiplatform.platform.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SmsTemplateTestDeliveryServiceTest extends SmsPostgresTestSupport {

    @Test
    void queuesOneEncryptedAuditedDeliveryAndReplaysByBusinessNumber() {
        UUID actor = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        insertUser(tenant, actor, true);
        SmsTemplateTestDeliveryService service = service();

        var first = service.enqueue(
                "SUPPORT_TICKET_COMPLETED",
                new SmsTemplateTestDeliveryService.TestDeliveryRequest(
                        "15089118963", Map.of("ticketNo", "TK-SMS-TEST-20260809"),
                        "sms-template-test-support-20260809"), actor);
        var replay = service.enqueue(
                "SUPPORT_TICKET_COMPLETED",
                new SmsTemplateTestDeliveryService.TestDeliveryRequest(
                        "15089118963", Map.of("ticketNo", "TK-SMS-TEST-20260809"),
                        "sms-template-test-support-20260809"), actor);

        assertThat(replay.deliveryId()).isEqualTo(first.deliveryId());
        assertThat(first.maskedPhone()).isEqualTo("*******8963");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.sms_delivery", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT template_key, phone_last4, status, variables::text,
                       phone_ciphertext IS NOT NULL AS encrypted
                FROM platform.sms_delivery WHERE id=?
                """, first.deliveryId()))
                .containsEntry("template_key", "SUPPORT_TICKET_COMPLETED")
                .containsEntry("phone_last4", "8963")
                .containsEntry("status", "PENDING")
                .containsEntry("encrypted", true);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action='SMS_TEMPLATE_TEST_QUEUED' AND request_id=?
                """, Integer.class, "sms-template-test-support-20260809")).isEqualTo(1);
    }

    @Test
    void encryptsVerificationVariablesForAdministratorTemplateTest() {
        UUID actor = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        insertUser(tenant, actor, true);

        var delivery = service().enqueue(
                "LOGIN_CODE",
                request("15089118963", Map.of("code", "123456"),
                        "sms-template-test-login-code-20260809"),
                actor);

        Map<String, Object> stored = jdbc.queryForMap("""
                SELECT variables::text AS variables,
                       sensitive_variables_ciphertext
                FROM platform.sms_delivery WHERE id=?
                """, delivery.deliveryId());
        assertThat(stored.get("variables")).isEqualTo("{}");
        assertThat(stored.get("sensitive_variables_ciphertext")).isInstanceOf(byte[].class);
        byte[] plaintext = encryption.decrypt(
                (byte[]) stored.get("sensitive_variables_ciphertext"));
        assertThat(new String(plaintext, StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":\"123456\"}");
    }

    @Test
    void rejectsInactiveReservedTemplateWrongVariablesPhoneAndBusinessNumber() {
        UUID actor = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        insertUser(tenant, actor, true);
        SmsTemplateTestDeliveryService service = service();

        assertThatThrownBy(() -> service.enqueue("GENERIC_VERIFICATION",
                request("15089118963", Map.of("code", "123456"),
                        "sms-template-test-reserved"), actor))
                .hasMessage("sms_template_not_active");
        assertThatThrownBy(() -> service.enqueue("SUPPORT_TICKET_COMPLETED",
                request("15089118963", Map.of("wrong", "value"),
                        "sms-template-test-vars"), actor))
                .hasMessage("sms_template_variables_mismatch");
        assertThatThrownBy(() -> service.enqueue("SUPPORT_TICKET_COMPLETED",
                request("123", Map.of("ticketNo", "TK-1"),
                        "sms-template-test-phone"), actor))
                .hasMessage("invalid_mainland_phone");
        assertThatThrownBy(() -> service.enqueue("SUPPORT_TICKET_COMPLETED",
                request("15089118963", Map.of("ticketNo", "TK-1"), "bad"), actor))
                .hasMessage("invalid_sms_template_test_business_no");
    }

    private SmsTemplateTestDeliveryService.TestDeliveryRequest request(
            String phone, Map<String, Object> variables, String businessNo) {
        return new SmsTemplateTestDeliveryService.TestDeliveryRequest(
                phone, variables, businessNo);
    }

    private SmsTemplateTestDeliveryService service() {
        return new SmsTemplateTestDeliveryService(
                new SmsTemplateTestDeliveryRepository(jdbc), phones, notifications());
    }
}
