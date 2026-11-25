package com.yuzhihuihe.apiplatform.platform.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.web.server.ResponseStatusException;

class SmsTemplateActivationServiceTest extends SmsPostgresTestSupport {

    @BeforeEach
    void resetTemplateStatuses() {
        jdbc.update("UPDATE platform.sms_template SET integration_status='PENDING_INTEGRATION' WHERE template_key IN ('BALANCE_LOW','ACCOUNT_BANNED')");
    }

    @Test
    void activatesOnlyApprovedPendingRegistryTemplateAndAuditsActorWithoutSecrets() {
        UUID tenant = UUID.randomUUID(); UUID actor = UUID.randomUUID();
        insertUser(tenant, actor, true);
        SmsTemplateActivationService service = new SmsTemplateActivationService(
                new SmsTemplateActivationRepository(jdbc));

        SmsTemplateActivationService.ActivationResult result = service.activate(
                new SmsTemplateActivationService.ActivationRequest(
                        "BALANCE_LOW", true, "provider-business-17", "SMS_511390111"), actor);

        assertThat(result.integrationStatus()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT integration_status FROM platform.sms_template WHERE template_key='BALANCE_LOW'", String.class)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT integration_status FROM platform.sms_template WHERE template_key='ACCOUNT_BANNED'", String.class)).isEqualTo("PENDING_INTEGRATION");
        assertThat(jdbc.queryForMap("SELECT actor_user_id, action, detail::text FROM platform.admin_audit_log WHERE resource_id='BALANCE_LOW'"))
                .containsEntry("actor_user_id", actor).containsEntry("action", "SMS_TEMPLATE_ACTIVATED");
    }

    @Test
    void rejectsUnapprovedWrongProviderNumberAndVariableMismatchWithoutChangingStatus() {
        UUID tenant = UUID.randomUUID(); UUID actor = UUID.randomUUID(); insertUser(tenant, actor, true);
        SmsTemplateActivationService service = new SmsTemplateActivationService(
                new SmsTemplateActivationRepository(jdbc));
        assertThatThrownBy(() -> service.activate(new SmsTemplateActivationService.ActivationRequest(
                "BALANCE_LOW", false, "business", "SMS_511390111"), actor)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.activate(new SmsTemplateActivationService.ActivationRequest(
                "BALANCE_LOW", true, "business", "SMS_WRONG"), actor)).isInstanceOf(ResponseStatusException.class);
        jdbc.update("UPDATE platform.sms_template SET variable_names='[]'::jsonb WHERE template_key='BALANCE_LOW'");
        assertThatThrownBy(() -> service.activate(new SmsTemplateActivationService.ActivationRequest(
                "BALANCE_LOW", true, "business", "SMS_511390111"), actor)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT integration_status FROM platform.sms_template WHERE template_key='BALANCE_LOW'", String.class)).isEqualTo("PENDING_INTEGRATION");
    }
}
