package com.yuzhihuihe.apiplatform.platform.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.PlatformServiceApplication;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationService;
import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        classes = PlatformServiceApplication.class,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "security.internal.hmac-secret=internal-sms-filter-test-secret",
                "security.internal.allowed-clock-skew=5m",
                "security.internal.request-replay-ttl=11m",
                "security.internal.maximum-body-size-bytes=1024"
        })
@AutoConfigureMockMvc
class InternalSmsFilterIntegrationTest {

    private static final String SECRET = "internal-sms-filter-test-secret";
    private static final String RATE_LIMIT_PATH =
            "/internal/platform/sms/signals/rate-limit";
    private static final String PROOF_CONSUME_PATH =
            "/internal/platform/sms/proofs/consume";

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        TestJwtKeyRing.configure(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean JdbcTemplate jdbcTemplate;
    @MockitoBean PlatformTransactionManager transactionManager;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean SmsProofService proofs;
    @MockitoBean CommercialNotificationService notifications;

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> replayValues = mock(ValueOperations.class);

    @BeforeEach
    void acceptFreshRequestIds() {
        when(redis.opsForValue()).thenReturn(replayValues);
        when(replayValues.setIfAbsent(
                anyString(), org.mockito.ArgumentMatchers.eq("1"),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(11))))
                .thenReturn(true);
    }

    @Test
    void bypassesHmacForAdjacentButUnlistedInternalSmsPath() throws Exception {
        // HMAC 过滤器只守护 AUTHENTICATED_PATHS 中的三条路径，因此这个相邻的
        // 路径不由它处理：与 protectsEveryDeclaredInternalSmsPathBeforeTheSecurityChain
        // 不同，响应是带有 nosniff 的框架响应体，而不是过滤器那个空 401。它过去
        // 会到达分发器并返回 404；自从安全链拒绝每一个未列出的 /internal/** 路径后，
        // 授权入口点会先以 4xx 作答。
        mockMvc.perform(post("/internal/platform/sms/signals/not-an-endpoint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is4xxClientError())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void protectsEveryDeclaredInternalSmsPathBeforeTheSecurityChain() throws Exception {
        for (String path : List.of(
                "/internal/platform/sms/proofs/consume",
                "/internal/platform/sms/signals/package-expiry",
                RATE_LIMIT_PATH)) {
            mockMvc.perform(post(path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().bytes(new byte[0]))
                    .andExpect(header().doesNotExist("X-Content-Type-Options"));
        }
    }

    @Test
    void readsAuthenticatedCachedBodyThroughControllerRequestBody() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        byte[] body = objectMapper.writeValueAsBytes(Map.of(
                "tenantId", tenantId,
                "userId", userId,
                "modelCode", "text-basic",
                "cooldownBucket", 17));

        mockMvc.perform(signedPost(RATE_LIMIT_PATH, requestId, body))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        verify(notifications).notifyApiRateLimited(tenantId, userId, "text-basic", 17);
    }

    @Test
    void returnsUnsignedPlain413BeforeTheSecurityAndControllerChains() throws Exception {
        mockMvc.perform(post(RATE_LIMIT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[1_025]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().bytes(new byte[0]))
                .andExpect(header().doesNotExist("X-Content-Type-Options"));
    }

    @Test
    void signsAuthenticatedControllerExceptionInsideTheRealFilterChain() throws Exception {
        when(notifications.notifyApiRateLimited(any(), any(), anyString(), any(Long.class)))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "sms_delivery_unavailable"));
        UUID requestId = UUID.randomUUID();
        byte[] body = objectMapper.writeValueAsBytes(Map.of(
                "tenantId", UUID.randomUUID(),
                "userId", UUID.randomUUID(),
                "modelCode", "text-basic",
                "cooldownBucket", 17));

        MvcResult result = mockMvc.perform(signedPost(RATE_LIMIT_PATH, requestId, body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();

        JsonNode envelope = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        byte[] payload = Base64.getDecoder().decode(envelope.path("payloadBase64").asText());
        assertThat(new InternalRequestSigner(SECRET).verifyResponse(
                envelope.path("timestamp").asLong(), requestId,
                envelope.path("requestId").asText(), 503, payload,
                envelope.path("contentSha256").asText(),
                envelope.path("signature").asText())).isTrue();
        assertThat(new String(payload, StandardCharsets.UTF_8))
                .contains("sms_delivery_unavailable");
    }

    @Test
    void signsProofDataAccessFailureAsSanitizedInternalServerError() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        String proof = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        when(proofs.consumeForOperation(
                proof, tenantId, userId, SmsPurpose.API_KEY_RESET, operationId))
                .thenThrow(new DataAccessResourceFailureException(
                        "sensitive database topology"));
        byte[] body = objectMapper.writeValueAsBytes(Map.of(
                "proof", proof,
                "tenantId", tenantId,
                "userId", userId,
                "operationId", operationId));

        MvcResult result = mockMvc.perform(signedPost(PROOF_CONSUME_PATH, requestId, body))
                .andExpect(status().isInternalServerError())
                .andReturn();

        assertSignedError(result, requestId, 500, "internal_sms_error");
        JsonNode envelope = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        String payload = new String(
                Base64.getDecoder().decode(envelope.path("payloadBase64").asText()),
                StandardCharsets.UTF_8);
        assertThat(payload).doesNotContain("sensitive database topology");
    }

    @Test
    void signsProofResponseStatusFailureAsSanitizedInternalServerError() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        String proof = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        when(proofs.consumeForOperation(
                proof, tenantId, userId, SmsPurpose.API_KEY_RESET, operationId))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "sensitive-proof-service-detail"));
        byte[] body = objectMapper.writeValueAsBytes(Map.of(
                "proof", proof,
                "tenantId", tenantId,
                "userId", userId,
                "operationId", operationId));

        MvcResult result = mockMvc.perform(signedPost(PROOF_CONSUME_PATH, requestId, body))
                .andReturn();

        JsonNode envelope = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        byte[] payload = Base64.getDecoder().decode(envelope.path("payloadBase64").asText());
        int actualStatus = result.getResponse().getStatus();
        String payloadText = new String(payload, StandardCharsets.UTF_8);
        assertSoftly(softly -> {
            softly.assertThat(new InternalRequestSigner(SECRET).verifyResponse(
                    envelope.path("timestamp").asLong(), requestId,
                    envelope.path("requestId").asText(), actualStatus, payload,
                    envelope.path("contentSha256").asText(),
                    envelope.path("signature").asText())).isTrue();
            softly.assertThat(actualStatus).isEqualTo(500);
            softly.assertThat(payloadText).contains("internal_sms_error");
            softly.assertThat(payloadText).doesNotContain("sensitive-proof-service-detail");
        });
    }

    @Test
    void signsAuthenticatedMalformedJsonWithBadRequestStatus() throws Exception {
        UUID requestId = UUID.randomUUID();
        byte[] malformedJson = "{\"tenantId\":".getBytes(StandardCharsets.UTF_8);

        MvcResult result = mockMvc.perform(signedRequest(
                        HttpMethod.POST, RATE_LIMIT_PATH, requestId,
                        malformedJson, MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertSignedError(result, requestId, 400, "invalid_request_body");
    }

    @Test
    void signsAuthenticatedUnsupportedMethodWithMethodNotAllowedStatus() throws Exception {
        UUID requestId = UUID.randomUUID();

        MvcResult result = mockMvc.perform(signedRequest(
                        HttpMethod.GET, RATE_LIMIT_PATH, requestId,
                        new byte[0], MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed())
                .andReturn();

        assertSignedError(result, requestId, 405, "method_not_allowed");
    }

    @Test
    void signsAuthenticatedUnsupportedMediaTypeWithActualStatus() throws Exception {
        UUID requestId = UUID.randomUUID();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        MvcResult result = mockMvc.perform(signedRequest(
                        HttpMethod.POST, RATE_LIMIT_PATH, requestId,
                        body, MediaType.TEXT_PLAIN))
                .andExpect(status().isUnsupportedMediaType())
                .andReturn();

        assertSignedError(result, requestId, 415, "unsupported_media_type");
    }

    private MockHttpServletRequestBuilder signedPost(
            String path, UUID requestId, byte[] body) {
        return signedRequest(HttpMethod.POST, path, requestId, body, MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder signedRequest(
            HttpMethod method,
            String path,
            UUID requestId,
            byte[] body,
            MediaType contentType) {
        long timestamp = Instant.now().getEpochSecond();
        InternalRequestSigner.SignedHeaders signed =
                new InternalRequestSigner(SECRET).sign(
                        timestamp, requestId, method.name(), path, body);
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .request(method, path)
                .contentType(contentType)
                .content(body)
                .header("X-Internal-Timestamp", signed.timestamp())
                .header("X-Internal-Request-Id", signed.requestId())
                .header("X-Internal-Content-Sha256", signed.contentSha256())
                .header("X-Internal-Signature", signed.signature());
    }

    private void assertSignedError(
            MvcResult result, UUID requestId, int status, String code) throws Exception {
        JsonNode envelope = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        byte[] payload = Base64.getDecoder().decode(envelope.path("payloadBase64").asText());
        assertThat(new InternalRequestSigner(SECRET).verifyResponse(
                envelope.path("timestamp").asLong(), requestId,
                envelope.path("requestId").asText(), status, payload,
                envelope.path("contentSha256").asText(),
                envelope.path("signature").asText())).isTrue();
        assertThat(new String(payload, StandardCharsets.UTF_8)).contains(code);
    }

    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
