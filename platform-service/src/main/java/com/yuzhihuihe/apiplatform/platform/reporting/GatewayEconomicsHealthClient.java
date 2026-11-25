package com.yuzhihuihe.apiplatform.platform.reporting;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GatewayEconomicsHealthClient implements OperatingReconciliationService.GatewaySource {

    static final String PATH = "/internal/usage-economics/outbox-health";
    private static final int PAGE_SIZE = 500;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI gatewayBaseUri;
    private final InternalRequestSigner signer;
    private final boolean configured;

    @Autowired
    public GatewayEconomicsHealthClient(ObjectMapper mapper,
            @Value("${gateway.internal.base-url:http://gateway-service:8081}") URI gatewayBaseUri,
            @Value("${security.internal.hmac-secret:}") String secret) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), mapper,
                gatewayBaseUri, new InternalRequestSigner(secret), !secret.isBlank());
    }

    GatewayEconomicsHealthClient(HttpClient http, ObjectMapper mapper, URI gatewayBaseUri,
            InternalRequestSigner signer, boolean configured) {
        this.http = http;
        this.mapper = mapper;
        this.gatewayBaseUri = gatewayBaseUri;
        this.signer = signer;
        this.configured = configured;
    }

    @Override
    public Status scan(Instant from, Instant to, Consumer<List<SourceEvent>> pageSink) {
        if (!configured) throw new IllegalStateException("security.internal.hmac-secret must be configured");
        if (from == null || to == null || !to.isAfter(from)
                || Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0) {
            throw new IllegalArgumentException("gateway_economics_range_invalid");
        }
        Status status = null;
        Instant cursorTime = null;
        UUID cursorId = null;
        boolean includeStatus = true;
        while (true) {
            Page page = fetch(from, to, cursorTime, cursorId, includeStatus);
            if (status == null) status = page.status();
            pageSink.accept(List.copyOf(page.events()));
            if (!page.hasMore()) return status;
            if (page.nextCreatedAt() == null || page.nextEventId() == null
                    || (page.nextCreatedAt().equals(cursorTime) && page.nextEventId().equals(cursorId))) {
                throw new IllegalStateException("gateway_economics_cursor_invalid");
            }
            cursorTime = page.nextCreatedAt();
            cursorId = page.nextEventId();
            includeStatus = false;
        }
    }

    private Page fetch(Instant from, Instant to, Instant cursorTime, UUID cursorId,
            boolean includeStatus) {
        StringBuilder query = new StringBuilder("from=").append(encode(from.toString()))
                .append("&to=").append(encode(to.toString())).append("&limit=").append(PAGE_SIZE)
                .append("&includeStatus=").append(includeStatus);
        if (cursorTime != null) query.append("&cursorCreatedAt=").append(encode(cursorTime.toString()))
                .append("&cursorEventId=").append(cursorId);
        URI uri = gatewayBaseUri.resolve(PATH + "?" + query);
        UUID requestId = UUID.randomUUID();
        InternalRequestSigner.SignedHeaders signed = signer.sign(
                Instant.now().getEpochSecond(), requestId, "GET", PATH, new byte[0]);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("X-Internal-Timestamp", signed.timestamp())
                .header("X-Internal-Request-Id", signed.requestId())
                .header("X-Internal-Content-Sha256", signed.contentSha256())
                .header("X-Internal-Signature", signed.signature()).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("gateway_economics_health_failed:" + response.statusCode());
            }
            Response body = mapper.readValue(response.body(), Response.class);
            return new Page(body.status(), body.events() == null ? List.of() : body.events(),
                    body.hasMore(), body.nextCreatedAt(), body.nextEventId());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("gateway_economics_health_interrupted", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("gateway_economics_health_failed", exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record SourceEvent(UUID eventId, String payloadHash, Instant createdAt) { }
    public record Status(long pending, long sending, long failed, long dead, Instant oldestPending) {
        public long backlog() {
            return Math.addExact(Math.addExact(pending, sending), Math.addExact(failed, dead));
        }
    }
    private record Response(long pending, long sending, long failed, long dead,
            Instant oldestPending, List<SourceEvent> events, boolean hasMore,
            Instant nextCreatedAt, UUID nextEventId) {
        Status status() { return new Status(pending, sending, failed, dead, oldestPending); }
    }
    private record Page(Status status, List<SourceEvent> events, boolean hasMore,
            Instant nextCreatedAt, UUID nextEventId) { }
}
