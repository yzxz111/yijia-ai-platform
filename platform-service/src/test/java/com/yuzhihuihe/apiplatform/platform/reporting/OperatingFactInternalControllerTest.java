package com.yuzhihuihe.apiplatform.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OperatingFactInternalControllerTest {

    private static final String SECRET = "operating-fact-secret";
    private static final String PATH = "/internal/platform/operating-facts/usage-events";
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void authenticatesSignedBodyAndRejectsTamperingExpiryAndReplay() throws Exception {
        Set<UUID> requests = ConcurrentHashMap.newKeySet();
        InternalOperatingFactHmacFilter filter = new InternalOperatingFactHmacFilter(
                SECRET, Duration.ofMinutes(5), CLOCK, requests::add, 1024);
        byte[] body = "{\"version\":\"USAGE_ECONOMICS_V1\"}".getBytes(StandardCharsets.UTF_8);
        UUID requestId = UUID.randomUUID();

        MockFilterChain accepted = new MockFilterChain();
        filter.doFilter(signed(requestId, body, NOW), new MockHttpServletResponse(), accepted);
        assertThat(accepted.getRequest()).isNotNull();

        MockHttpServletResponse replay = new MockHttpServletResponse();
        filter.doFilter(signed(requestId, body, NOW), replay, new MockFilterChain());
        assertThat(replay.getStatus()).isEqualTo(401);

        MockHttpServletRequest tampered = signed(UUID.randomUUID(), body, NOW);
        tampered.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse tamperedResponse = new MockHttpServletResponse();
        filter.doFilter(tampered, tamperedResponse, new MockFilterChain());
        assertThat(tamperedResponse.getStatus()).isEqualTo(401);

        MockHttpServletResponse expired = new MockHttpServletResponse();
        filter.doFilter(signed(UUID.randomUUID(), body, NOW.minus(Duration.ofMinutes(6))),
                expired, new MockFilterChain());
        assertThat(expired.getStatus()).isEqualTo(401);
    }

    @Test
    void mapsIngestionResultsToContractStatuses() {
        assertThat(response(OperatingFactIngestionService.Result.ACCEPTED).getStatusCode().value()).isEqualTo(204);
        assertThat(response(OperatingFactIngestionService.Result.REPLAYED).getStatusCode().value()).isEqualTo(204);
        assertThat(response(OperatingFactIngestionService.Result.INVALID).getStatusCode().value()).isEqualTo(422);
        assertThat(response(OperatingFactIngestionService.Result.CONFLICT).getStatusCode().value()).isEqualTo(409);
    }

    private ResponseEntity<Void> response(OperatingFactIngestionService.Result result) {
        OperatingFactIngestionService service = org.mockito.Mockito.mock(OperatingFactIngestionService.class);
        org.mockito.Mockito.when(service.ingest(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(result);
        return new OperatingFactInternalController(service).ingest("payload".getBytes(StandardCharsets.UTF_8), "hash");
    }

    private MockHttpServletRequest signed(UUID requestId, byte[] body, Instant timestamp) {
        InternalRequestSigner.SignedHeaders signed = new InternalRequestSigner(SECRET, CLOCK).sign(
                timestamp.getEpochSecond(), requestId, "POST", PATH, body);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setContent(body);
        request.addHeader("X-Internal-Timestamp", signed.timestamp());
        request.addHeader("X-Internal-Request-Id", signed.requestId());
        request.addHeader("X-Internal-Content-Sha256", signed.contentSha256());
        request.addHeader("X-Internal-Signature", signed.signature());
        return request;
    }
}
