package com.yuzhihuihe.apiplatform.platform.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationService;
import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.beans.factory.ObjectProvider;

class InternalSmsControllerTest {

    private static final String SECRET = "internal-sms-test-secret";
    private static final String PATH = "/internal/platform/sms/signals/rate-limit";
    private static final String AUTHENTICATED_REQUEST_ID_ATTRIBUTE =
            InternalSmsHmacFilter.class.getName() + ".authenticatedRequestId";
    private static final Instant NOW = Instant.parse("2026-08-04T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void claimsRequestIdsDurablyWithRedisSetIfAbsentAndBoundedTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        UUID requestId = UUID.randomUUID();
        Duration ttl = Duration.ofMinutes(10);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent("internal:sms:request-id:" + requestId, "1", ttl))
                .thenReturn(true, false);
        RedisInternalRequestReplayGuard replay = new RedisInternalRequestReplayGuard(redis, ttl);

        assertThat(replay.claim(requestId)).isTrue();
        assertThat(replay.claim(requestId)).isFalse();

        verify(values, org.mockito.Mockito.times(2)).setIfAbsent(
                "internal:sms:request-id:" + requestId, "1", ttl);
    }

    @Test
    void sharesReplayClaimsAcrossFilterInstancesAndFailsClosedWhenRedisIsUnavailable()
            throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        Duration ttl = Duration.ofMinutes(10);
        UUID requestId = UUID.randomUUID();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent("internal:sms:request-id:" + requestId, "1", ttl))
                .thenReturn(true, false);
        InternalSmsHmacFilter first = new InternalSmsHmacFilter(
                SECRET, Duration.ofMinutes(5), CLOCK,
                new RedisInternalRequestReplayGuard(redis, ttl)::claim);
        InternalSmsHmacFilter second = new InternalSmsHmacFilter(
                SECRET, Duration.ofMinutes(5), CLOCK,
                new RedisInternalRequestReplayGuard(redis, ttl)::claim);

        MockFilterChain acceptedChain = new MockFilterChain();
        first.doFilter(
                signedRequest(requestId, body), new MockHttpServletResponse(), acceptedChain);
        MockFilterChain replayChain = new MockFilterChain();
        MockHttpServletResponse replayResponse = new MockHttpServletResponse();
        second.doFilter(signedRequest(requestId, body), replayResponse, replayChain);

        assertThat(acceptedChain.getRequest()).isNotNull();
        assertThat(acceptedChain.getRequest().getAttribute(
                AUTHENTICATED_REQUEST_ID_ATTRIBUTE))
                .isEqualTo(requestId);
        assertThat(replayResponse.getStatus()).isEqualTo(401);
        assertThat(replayChain.getRequest()).isNull();

        UUID unavailableId = UUID.randomUUID();
        when(values.setIfAbsent("internal:sms:request-id:" + unavailableId, "1", ttl))
                .thenThrow(new IllegalStateException("redis unavailable"));
        InternalSmsHmacFilter unavailable = new InternalSmsHmacFilter(
                SECRET, Duration.ofMinutes(5), CLOCK,
                new RedisInternalRequestReplayGuard(redis, ttl)::claim);
        MockHttpServletResponse unavailableResponse = new MockHttpServletResponse();
        MockFilterChain unavailableChain = new MockFilterChain();

        unavailable.doFilter(
                signedRequest(unavailableId, body), unavailableResponse, unavailableChain);

        assertThat(unavailableResponse.getStatus()).isEqualTo(401);
        assertThat(unavailableChain.getRequest()).isNull();
    }

    @Test
    void requiresReplayTtlToCoverTheFullClockAcceptanceWindow() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> new InternalSmsHmacFilter(
                SECRET,
                Duration.ofMinutes(5),
                Duration.ofMinutes(10),
                1_048_576,
                redisProvider))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internal_sms_replay_ttl_too_short");
        assertThatCode(() -> new InternalSmsHmacFilter(
                SECRET,
                Duration.ofMinutes(5),
                Duration.ofMinutes(11),
                1_048_576,
                redisProvider))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsFutureBoundaryTimestampReplayThroughoutItsFullAcceptanceWindow()
            throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        Clock movingClock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        Duration replayTtl = Duration.ofMinutes(11);
        AtomicReference<Instant> claimedUntil = new AtomicReference<>();
        java.util.function.Predicate<UUID> replayClaim = ignored -> {
            Instant expiry = claimedUntil.get();
            if (expiry != null && now.get().isBefore(expiry)) {
                return false;
            }
            claimedUntil.set(now.get().plus(replayTtl));
            return true;
        };
        InternalSmsHmacFilter filter = new InternalSmsHmacFilter(
                SECRET, Duration.ofMinutes(5), movingClock, replayClaim);
        UUID requestId = UUID.randomUUID();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Instant futureBoundary = NOW.plus(Duration.ofMinutes(5));

        MockFilterChain acceptedChain = new MockFilterChain();
        filter.doFilter(
                signedRequest(requestId, body, futureBoundary),
                new MockHttpServletResponse(),
                acceptedChain);
        now.set(NOW.plus(Duration.ofMinutes(10)).minusSeconds(1));
        MockHttpServletResponse replayResponse = new MockHttpServletResponse();
        MockFilterChain replayChain = new MockFilterChain();
        filter.doFilter(
                signedRequest(requestId, body, futureBoundary), replayResponse, replayChain);

        assertThat(acceptedChain.getRequest()).isNotNull();
        assertThat(replayResponse.getStatus()).isEqualTo(401);
        assertThat(replayChain.getRequest()).isNull();
    }

    @Test
    void consumesProofWithApiKeyResetPurposeFixedByTheEndpoint() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String proof = "proof-token-is-never-returned";
        SmsProofService proofs = mock(SmsProofService.class);
        when(proofs.consumeForOperation(
                proof, tenantId, userId, SmsPurpose.API_KEY_RESET, operationId))
                .thenReturn(new SmsProofService.ProofClaims(
                        tenantId, userId, SmsPurpose.API_KEY_RESET));
        InternalSmsController controller = controller(proofs);

        controller.consumeProof(requestId,
                new InternalSmsController.ProofConsumeRequest(
                        proof, tenantId, userId, operationId));

        verify(proofs).consumeForOperation(
                proof, tenantId, userId, SmsPurpose.API_KEY_RESET, operationId);
    }

    @Test
    void signsProofConsumeResponseUsingExistingInternalV2Convention() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String proof = "proof-token-is-never-returned";
        SmsProofService proofs = mock(SmsProofService.class);
        when(proofs.consumeForOperation(
                proof, tenantId, userId, SmsPurpose.API_KEY_RESET, operationId))
                .thenReturn(new SmsProofService.ProofClaims(
                        tenantId, userId, SmsPurpose.API_KEY_RESET));
        InternalSmsController controller = controller(proofs);

        InternalSmsController.SignedInternalResponse response = controller.consumeProof(
                requestId, new InternalSmsController.ProofConsumeRequest(
                        proof, tenantId, userId, operationId));
        byte[] payload = Base64.getDecoder().decode(response.payloadBase64());

        assertThat(new InternalRequestSigner(SECRET, CLOCK).verifyResponse(
                response.timestamp(), requestId, response.requestId().toString(), 200, payload,
                response.contentSha256(), response.signature())).isTrue();
        JsonNode json = new ObjectMapper().readTree(payload);
        assertThat(json.path("consumed").asBoolean()).isTrue();
        assertThat(new String(payload, StandardCharsets.UTF_8)).doesNotContain(proof);
    }

    @Test
    void enqueuesPackageExpirySignalsWithStateScopedDeterministicDeduplication() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        CommercialNotificationService notifications = mock(CommercialNotificationService.class);
        InternalSmsController controller = controller(mock(SmsProofService.class), notifications);
        InternalSmsController.PackageExpirySignal expiring =
                new InternalSmsController.PackageExpirySignal(
                        tenantId, userId, packageId, NOW.plus(Duration.ofDays(2)));
        InternalSmsController.PackageExpirySignal expired =
                new InternalSmsController.PackageExpirySignal(
                        tenantId, userId, packageId, NOW.minusSeconds(1));

        controller.packageExpiry(UUID.randomUUID(), expiring);
        controller.packageExpiry(UUID.randomUUID(), expiring);
        controller.packageExpiry(UUID.randomUUID(), expired);

        verify(notifications, org.mockito.Mockito.times(2)).notifyPackageExpiry(
                tenantId, userId, packageId, expiring.expiresAt());
        verify(notifications).notifyPackageExpiry(
                tenantId, userId, packageId, expired.expiresAt());
        assertThat(InternalSmsController.PackageExpirySignal.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("tenantId", "userId", "packageId", "expiresAt");
    }

    @Test
    void enqueuesRateLimitSignalWithUserModelBucketDeduplicationAndNoSensitivePayload() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        CommercialNotificationService notifications = mock(CommercialNotificationService.class);
        InternalSmsController controller = controller(mock(SmsProofService.class), notifications);
        InternalSmsController.RateLimitSignal signal = new InternalSmsController.RateLimitSignal(
                tenantId, userId, "text-basic", 82491L);

        controller.rateLimit(UUID.randomUUID(), signal);
        controller.rateLimit(UUID.randomUUID(), signal);

        verify(notifications, org.mockito.Mockito.times(2)).notifyApiRateLimited(
                tenantId, userId, "text-basic", 82491L);
        assertThat(InternalSmsController.RateLimitSignal.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("tenantId", "userId", "modelCode", "cooldownBucket");
    }

    @Test
    void rejectsSignatureWhenRequestBodyIsTampered() throws Exception {
        Set<UUID> requestIds = ConcurrentHashMap.newKeySet();
        InternalSmsHmacFilter filter = new InternalSmsHmacFilter(
                SECRET, Duration.ofMinutes(5), CLOCK, requestIds::add);
        UUID requestId = UUID.randomUUID();
        byte[] signedBody = "{\"tenantId\":\"10000000-0000-4000-8000-000000000001\"}"
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signedRequest(requestId, signedBody);
        request.setContent("{\"tenantId\":\"20000000-0000-4000-8000-000000000002\"}"
                .getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(requestIds).isEmpty();
    }

    @Test
    void rejectsOversizedSignedBodyBeforeReplayClaim() throws Exception {
        Set<UUID> requestIds = ConcurrentHashMap.newKeySet();
        InternalSmsHmacFilter filter = new InternalSmsHmacFilter(
                SECRET, Duration.ofMinutes(5), CLOCK, requestIds::add, 8);
        byte[] body = "{\"tooLarge\":true}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(signedRequest(UUID.randomUUID(), body), response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
        assertThat(requestIds).isEmpty();
    }

    @Test
    void rejectsReplayedRequestIdAfterFirstAuthenticatedRequest() throws Exception {
        Set<UUID> requestIds = ConcurrentHashMap.newKeySet();
        InternalSmsHmacFilter filter = new InternalSmsHmacFilter(
                SECRET, Duration.ofMinutes(5), CLOCK, requestIds::add);
        UUID requestId = UUID.randomUUID();
        byte[] body = "{\"tenantId\":\"10000000-0000-4000-8000-000000000001\"}"
                .getBytes(StandardCharsets.UTF_8);

        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        MockFilterChain firstChain = new MockFilterChain();
        filter.doFilter(signedRequest(requestId, body), firstResponse, firstChain);

        MockHttpServletResponse replayResponse = new MockHttpServletResponse();
        MockFilterChain replayChain = new MockFilterChain();
        filter.doFilter(signedRequest(requestId, body), replayResponse, replayChain);

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(firstChain.getRequest()).isNotNull();
        assertThat(replayResponse.getStatus()).isEqualTo(401);
        assertThat(replayChain.getRequest()).isNull();
        assertThat(requestIds).containsExactly(requestId);
    }

    private MockHttpServletRequest signedRequest(UUID requestId, byte[] body) {
        return signedRequest(requestId, body, NOW);
    }

    private MockHttpServletRequest signedRequest(
            UUID requestId, byte[] body, Instant timestamp) {
        InternalRequestSigner.SignedHeaders signed = new InternalRequestSigner(SECRET, CLOCK).sign(
                timestamp.getEpochSecond(), requestId, "POST", PATH, body);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setContentType("application/json");
        request.setContent(body);
        request.addHeader("X-Internal-Timestamp", signed.timestamp());
        request.addHeader("X-Internal-Request-Id", signed.requestId());
        request.addHeader("X-Internal-Content-Sha256", signed.contentSha256());
        request.addHeader("X-Internal-Signature", signed.signature());
        return request;
    }

    private InternalSmsController controller(SmsProofService proofs) {
        return controller(proofs, mock(CommercialNotificationService.class));
    }

    private InternalSmsController controller(
            SmsProofService proofs, CommercialNotificationService notifications) {
        return new InternalSmsController(
                proofs, notifications, new ObjectMapper(), CLOCK, SECRET);
    }
}
