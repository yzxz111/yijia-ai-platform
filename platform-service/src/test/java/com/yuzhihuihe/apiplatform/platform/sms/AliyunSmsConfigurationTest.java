package com.yuzhihuihe.apiplatform.platform.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.annotation.MergedAnnotations;

import com.aliyun.tea.TeaException;

class AliyunSmsConfigurationTest {

    @Test
    void isConditionalOnExplicitSmsEnablement() {
        ConditionalOnExpression conditional = MergedAnnotations
                .from(AliyunSmsConfiguration.class)
                .get(ConditionalOnExpression.class)
                .synthesize();

        assertThat(conditional.value()).isEqualTo(
                "'${sms.enabled:false}' == 'true' and '${sms.provider:aliyun}' == 'aliyun'");
    }

    @Test
    void validatesAllRequiredProviderConfigurationWithoutEchoingValues() {
        assertInvalid("", "AKID-SENTINEL", "AKSECRET-SENTINEL", "sign", "cn-hangzhou",
                "sms_aliyun_endpoint_required");
        assertInvalid("endpoint", "", "AKSECRET-SENTINEL", "sign", "cn-hangzhou",
                "sms_aliyun_access_key_id_required");
        assertInvalid("endpoint", "AKID-SENTINEL", "", "sign", "cn-hangzhou",
                "sms_aliyun_access_key_secret_required");
        assertInvalid("endpoint", "AKID-SENTINEL", "AKSECRET-SENTINEL", "", "cn-hangzhou",
                "sms_aliyun_sign_name_required");
        assertInvalid("endpoint", "AKID-SENTINEL", "AKSECRET-SENTINEL", "sign", "",
                "sms_aliyun_region_id_required");
    }

    @Test
    void classifiesAliyunResultsIntoSanitizedSuccessTransientAndPermanentOutcomes() {
        assertThat(AliyunSmsProvider.mapResult("OK", "request", "biz"))
                .isEqualTo(SmsProvider.Result.success("request", "biz"));
        assertThat(AliyunSmsProvider.mapResult("isv.BUSINESS_LIMIT_CONTROL", "request", null))
                .isEqualTo(SmsProvider.Result.transientFailure("ISV_BUSINESS_LIMIT_CONTROL"));
        assertThat(AliyunSmsProvider.mapResult("isp.SYSTEM_ERROR", "request", null))
                .isEqualTo(SmsProvider.Result.transientFailure("ISP_SYSTEM_ERROR"));
        assertThat(AliyunSmsProvider.mapResult("isv.MOBILE_NUMBER_ILLEGAL", "request", null))
                .isEqualTo(SmsProvider.Result.permanentFailure("ISV_MOBILE_NUMBER_ILLEGAL"));
        assertThat(AliyunSmsProvider.mapResult("isv.SMS_TEMPLATE_ILLEGAL", "request", null))
                .isEqualTo(SmsProvider.Result.permanentFailure("ISV_SMS_TEMPLATE_ILLEGAL"));
        assertThat(AliyunSmsProvider.mapResult("raw unsafe response: 15012348963", null, null).errorCode())
                .isEqualTo("UNCLASSIFIED_PROVIDER_ERROR");
        assertThat(AliyunSmsProvider.mapResult("E15012348963CODE654321", null, null).errorCode())
                .isEqualTo("UNCLASSIFIED_PROVIDER_ERROR");
    }

    @Test
    void classifiesSdkExceptionsWithoutPersistingSdkMessagesOrUnknownCodes() {
        TeaException timeout = new TeaException();
        timeout.setCode("RequestTimeout");
        TeaException authentication = new TeaException();
        authentication.setCode("InvalidAccessKeyId.NotFound");
        TeaException unsafe = new TeaException();
        unsafe.setCode("E15012348963CODE654321");
        unsafe.setMessage("phone=15012348963 code=654321");
        TeaException unavailable = new TeaException();
        unavailable.setCode("ServiceUnavailable");
        TeaException throttled = new TeaException();
        throttled.setCode("Throttling");
        TeaException badSignature = new TeaException();
        badSignature.setCode("SignatureDoesNotMatch");

        assertThat(AliyunSmsProvider.mapException(new SocketTimeoutException("secret payload")))
                .isEqualTo(SmsProvider.Result.transientFailure("PROVIDER_TIMEOUT"));
        assertThat(AliyunSmsProvider.mapException(timeout))
                .isEqualTo(SmsProvider.Result.transientFailure("REQUEST_TIMEOUT"));
        assertThat(AliyunSmsProvider.mapException(authentication))
                .isEqualTo(SmsProvider.Result.permanentFailure("INVALID_ACCESS_KEY_ID_NOT_FOUND"));
        assertThat(AliyunSmsProvider.mapException(unavailable))
                .isEqualTo(SmsProvider.Result.transientFailure("SERVICE_UNAVAILABLE"));
        assertThat(AliyunSmsProvider.mapException(throttled))
                .isEqualTo(SmsProvider.Result.transientFailure("THROTTLING"));
        assertThat(AliyunSmsProvider.mapException(badSignature))
                .isEqualTo(SmsProvider.Result.permanentFailure("SIGNATURE_DOES_NOT_MATCH"));
        assertThat(AliyunSmsProvider.mapException(unsafe))
                .isEqualTo(SmsProvider.Result.permanentFailure("UNCLASSIFIED_PROVIDER_ERROR"));
    }

    @Test
    void safeDefaultsContainNoUsableKeysAndNoUnusedChallengeExpiry() throws Exception {
        String yaml;
        try (var input = getClass().getClassLoader().getResourceAsStream("application.yaml")) {
            yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(yaml).contains(
                "phone-lookup-key: ${SMS_PHONE_LOOKUP_KEY:}",
                "code-pepper: ${SMS_CODE_PEPPER:}");
        assertThat(yaml).doesNotContain("challenge-expiry:");
    }

    private void assertInvalid(
            String endpoint,
            String accessKeyId,
            String accessKeySecret,
            String signName,
            String regionId,
            String expectedMessage) {
        var assertion = assertThatThrownBy(() -> new AliyunSmsConfiguration(
                endpoint, accessKeyId, accessKeySecret, signName, regionId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expectedMessage);
        if (!accessKeyId.isEmpty()) {
            assertion.hasMessageNotContaining(accessKeyId);
        }
        if (!accessKeySecret.isEmpty()) {
            assertion.hasMessageNotContaining(accessKeySecret);
        }
    }
}
