package com.yuzhihuihe.apiplatform.platform.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationService;
import com.yuzhihuihe.apiplatform.platform.safety.InternalContentSafetyPolicyHmacFilter;
import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(
        controllers = InternalSmsController.class,
        properties = "security.internal.hmac-secret=internal-sms-error-test-secret",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = jakarta.servlet.Filter.class))
@AutoConfigureMockMvc(addFilters = false)
class InternalSmsErrorAdviceTest {

    private static final String SECRET = "internal-sms-error-test-secret";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean CommercialNotificationService notifications;

    @Test
    void signsAuthenticatedUnprocessableEntityWithTrustedRequestIdAndActualStatus()
            throws Exception {
        UUID trustedRequestId = UUID.randomUUID();
        UUID untrustedHeaderRequestId = UUID.randomUUID();

        MvcResult result = mockMvc.perform(post("/internal/platform/sms/signals/rate-limit")
                        .requestAttr(
                                InternalSmsHmacFilter.AUTHENTICATED_REQUEST_ID_ATTRIBUTE,
                                trustedRequestId)
                        .header("X-Internal-Request-Id", untrustedHeaderRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        assertSignedError(result, trustedRequestId, 422, "invalid_sms_signal");
    }

    @Test
    void signsAuthenticatedServiceUnavailableWithActualStatus() throws Exception {
        when(notifications.notifyApiRateLimited(any(), any(), anyString(), any(Long.class)))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "sms_delivery_unavailable"));
        UUID requestId = UUID.randomUUID();

        MvcResult result = performValidRateLimit(requestId)
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        assertSignedError(result, requestId, 503, "sms_delivery_unavailable");
    }

    @Test
    void signsUnexpectedAuthenticatedFailureAsInternalServerError() throws Exception {
        when(notifications.notifyApiRateLimited(any(), any(), anyString(), any(Long.class)))
                .thenThrow(new IllegalStateException("sensitive downstream failure"));
        UUID requestId = UUID.randomUUID();

        MvcResult result = performValidRateLimit(requestId)
                .andExpect(status().isInternalServerError())
                .andReturn();

        assertSignedError(result, requestId, 500, "internal_sms_error");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("sensitive downstream failure");
    }

    @Test
    void neverSignsFromUntrustedRequestHeaderWithoutAuthenticatedAttribute() throws Exception {
        UUID headerRequestId = UUID.randomUUID();

        MvcResult result = mockMvc.perform(post("/internal/platform/sms/signals/rate-limit")
                        .header("X-Internal-Request-Id", headerRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.path("code").asText()).isEqualTo("invalid_sms_signal");
        assertThat(body.has("requestId")).isFalse();
        assertThat(body.has("signature")).isFalse();
        assertThat(body.has("payloadBase64")).isFalse();
    }

    private org.springframework.test.web.servlet.ResultActions performValidRateLimit(
            UUID requestId) throws Exception {
        return mockMvc.perform(post("/internal/platform/sms/signals/rate-limit")
                .requestAttr(
                        InternalSmsHmacFilter.AUTHENTICATED_REQUEST_ID_ATTRIBUTE, requestId)
                .header("X-Internal-Request-Id", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "tenantId":"10000000-0000-4000-8000-000000000001",
                          "userId":"20000000-0000-4000-8000-000000000002",
                          "modelCode":"text-basic",
                          "cooldownBucket":17
                        }
                        """));
    }

    private void assertSignedError(
            MvcResult result, UUID requestId, int status, String code) throws Exception {
        JsonNode envelope = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        byte[] payload = Base64.getDecoder().decode(envelope.path("payloadBase64").asText());
        assertThat(envelope.path("requestId").asText()).isEqualTo(requestId.toString());
        assertThat(new InternalRequestSigner(SECRET).verifyResponse(
                envelope.path("timestamp").asLong(),
                requestId,
                envelope.path("requestId").asText(),
                status,
                payload,
                envelope.path("contentSha256").asText(),
                envelope.path("signature").asText())).isTrue();
        assertThat(new String(payload, StandardCharsets.UTF_8)).contains(code);
    }
}
