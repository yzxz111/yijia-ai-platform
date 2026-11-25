package com.yuzhihuihe.apiplatform.platform.safety;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalContentSafetyPolicyHmacFilterTest {

    private static final String SECRET = "policy-internal-secret";
    private static final Instant NOW = Instant.parse("2026-08-29T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void acceptsSignedGetOnceAndRejectsReplayOrTampering() throws Exception {
        Set<UUID> claimed = ConcurrentHashMap.newKeySet();
        InternalRequestSigner signer = new InternalRequestSigner(SECRET, CLOCK);
        InternalContentSafetyPolicyHmacFilter filter =
                new InternalContentSafetyPolicyHmacFilter(signer, claimed::add, 1024);
        UUID requestId = UUID.randomUUID();

        MockFilterChain accepted = new MockFilterChain();
        filter.doFilter(request(requestId, new byte[0]), new MockHttpServletResponse(), accepted);
        assertThat(accepted.getRequest()).isNotNull();

        MockHttpServletResponse replay = new MockHttpServletResponse();
        filter.doFilter(request(requestId, new byte[0]), replay, new MockFilterChain());
        assertThat(replay.getStatus()).isEqualTo(401);

        MockHttpServletRequest tampered = request(UUID.randomUUID(), new byte[0]);
        tampered.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse tamperedResponse = new MockHttpServletResponse();
        filter.doFilter(tampered, tamperedResponse, new MockFilterChain());
        assertThat(tamperedResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsMissingReplayStoreAndNonEmptyGetBody() throws Exception {
        InternalRequestSigner signer = new InternalRequestSigner(SECRET, CLOCK);
        InternalContentSafetyPolicyHmacFilter filter =
                new InternalContentSafetyPolicyHmacFilter(signer, ignored -> false, 1024);
        MockHttpServletResponse unavailable = new MockHttpServletResponse();
        filter.doFilter(request(UUID.randomUUID(), new byte[0]), unavailable, new MockFilterChain());
        assertThat(unavailable.getStatus()).isEqualTo(401);

        MockHttpServletRequest body = request(UUID.randomUUID(), "body".getBytes());
        MockHttpServletResponse bodyResponse = new MockHttpServletResponse();
        filter.doFilter(body, bodyResponse, new MockFilterChain());
        assertThat(bodyResponse.getStatus()).isEqualTo(401);
    }

    private MockHttpServletRequest request(UUID requestId, byte[] body) {
        InternalRequestSigner.SignedHeaders headers = new InternalRequestSigner(SECRET, CLOCK).sign(
                NOW.getEpochSecond(), requestId, "GET",
                InternalContentSafetyPolicyHmacFilter.PATH, body);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", InternalContentSafetyPolicyHmacFilter.PATH);
        request.setContent(body);
        request.addHeader("X-Internal-Timestamp", headers.timestamp());
        request.addHeader("X-Internal-Request-Id", headers.requestId());
        request.addHeader("X-Internal-Content-Sha256", headers.contentSha256());
        request.addHeader("X-Internal-Signature", headers.signature());
        return request;
    }
}
