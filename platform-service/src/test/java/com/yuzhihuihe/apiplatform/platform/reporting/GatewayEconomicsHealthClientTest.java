package com.yuzhihuihe.apiplatform.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import org.junit.jupiter.api.Test;

class GatewayEconomicsHealthClientTest {

    @Test
    @SuppressWarnings("unchecked")
    void signsAndScansBoundedPagesWithoutReceivingPayload() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> first = mock(HttpResponse.class);
        HttpResponse<String> second = mock(HttpResponse.class);
        when(first.statusCode()).thenReturn(200);
        when(second.statusCode()).thenReturn(200);
        when(first.body()).thenReturn("""
                {"pending":2,"sending":1,"failed":3,"dead":1,"oldestPending":"2026-08-01T00:00:00Z",
                 "events":[{"eventId":"10000000-0000-4000-8000-000000000001","payloadHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","createdAt":"2026-08-01T01:00:00Z"}],
                 "hasMore":true,"nextCreatedAt":"2026-08-01T01:00:00Z","nextEventId":"10000000-0000-4000-8000-000000000001"}
                """);
        when(second.body()).thenReturn("""
                {"pending":2,"sending":1,"failed":3,"dead":1,"oldestPending":"2026-08-01T00:00:00Z",
                 "events":[],"hasMore":false,"nextCreatedAt":null,"nextEventId":null}
                """);
        AtomicReference<HttpRequest> firstRequest = new AtomicReference<>();
        List<HttpRequest> requests = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            firstRequest.compareAndSet(null, request);
            requests.add(request);
            return calls.getAndIncrement() == 0 ? first : second;
        });
        InternalRequestSigner signer = new InternalRequestSigner("secret");
        GatewayEconomicsHealthClient client = new GatewayEconomicsHealthClient(http,
                new ObjectMapper().findAndRegisterModules(), URI.create("http://gateway:8081"), signer, true);
        List<GatewayEconomicsHealthClient.SourceEvent> events = new ArrayList<>();

        var status = client.scan(Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-02T00:00:00Z"), events::addAll);

        assertThat(status.backlog()).isEqualTo(7);
        assertThat(events).singleElement().satisfies(event ->
                assertThat(event.payloadHash()).hasSize(64));
        assertThat(calls).hasValue(2);
        assertThat(requests.get(0).uri().getQuery()).contains("includeStatus=true");
        assertThat(requests.get(1).uri().getQuery()).contains("includeStatus=false");
        HttpRequest request = firstRequest.get();
        assertThat(request.uri().getPath()).isEqualTo(GatewayEconomicsHealthClient.PATH);
        assertThat(request.uri().getQuery()).contains("limit=500", "from=", "to=");
        long timestamp = Long.parseLong(request.headers().firstValue("X-Internal-Timestamp").orElseThrow());
        UUID requestId = UUID.fromString(request.headers().firstValue("X-Internal-Request-Id").orElseThrow());
        assertThat(signer.verify(timestamp, requestId, "GET", GatewayEconomicsHealthClient.PATH,
                new byte[0], request.headers().firstValue("X-Internal-Content-Sha256").orElseThrow(),
                request.headers().firstValue("X-Internal-Signature").orElseThrow())).isTrue();
        assertThat(first.body()).doesNotContain("payload\"");
    }
}
