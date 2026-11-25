package com.yuzhihuihe.apiplatform.platform.limitusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuzhihuihe.apiplatform.platform.internalapi.GatewayInternalGet;
import com.yuzhihuihe.apiplatform.platform.internalapi.GatewayInternalUnavailableException;
import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewayLimitUsageClientTest {

    private static final String SECRET = "test-internal-secret";
    private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PACKAGE_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OTHER_PACKAGE_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<HttpExchange> exchange = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void asksForTheCallersInstanceWithSignedHeadersAndParsesEveryField() throws Exception {
        server.createContext(GatewayLimitUsageClient.PATH, request -> {
            requests.incrementAndGet();
            exchange.set(request);
            respond(request, 200, fullBody(PACKAGE_ID, "TOKEN"));
        });

        GatewayLimitUsageClient.LimitUsage usage = client().fetch(PACKAGE_ID);

        assertThat(usage.packageInstanceId()).isEqualTo(PACKAGE_ID);
        assertThat(usage.usageDimension()).isEqualTo("TOKEN");
        assertThat(usage.perMinute().limit()).isEqualTo(100_000L);
        assertThat(usage.perMinute().used()).isEqualTo(1_234L);
        assertThat(usage.daily().limit()).isEqualTo(2_000L);
        assertThat(usage.daily().used()).isEqualTo(17L);
        assertThat(usage.blockedLast24h()).isEqualTo(3L);
        assertThat(usage.blockedLast24HoursByReason().dailyRequestLimitExceeded()).isEqualTo(1L);
        assertThat(usage.blockedLast24HoursByReason().tokensPerMinuteExceeded()).isEqualTo(2L);

        HttpExchange captured = exchange.get();
        assertThat(captured.getRequestMethod()).isEqualTo("GET");
        assertThat(captured.getRequestURI().toString())
                .isEqualTo(GatewayLimitUsageClient.PATH + "?packageInstanceId=" + PACKAGE_ID);
        assertThat(captured.getRequestHeaders().getFirst("X-Internal-Timestamp"))
                .isEqualTo(Long.toString(NOW.getEpochSecond()));
        assertThat(captured.getRequestHeaders().getFirst("X-Internal-Request-Id"))
                .isEqualTo(REQUEST_ID.toString());
        assertThat(captured.getRequestHeaders().getFirst("X-Internal-Content-Sha256"))
                .isEqualTo(sha256(new byte[0]));
        assertThat(captured.getRequestHeaders().getFirst("X-Internal-Signature")).isNotBlank();
        assertSignatureCoversThePathOnly(captured);
        assertThat(requests).hasValue(1);
    }

    @Test
    void reportsAnUnstatedWindowAsNullInsteadOfAnInventedZero() {
        server.createContext(GatewayLimitUsageClient.PATH, request -> respond(request, 200, """
                {"packageInstanceId":"%s","usageDimension":"VIDEO_SECOND",
                 "perMinute":{"limit":null,"used":30},"daily":null,"blockedLast24h":0,
                 "blockedLast24HoursByReason":{"daily_request_limit_exceeded":0,
                                               "tokens_per_minute_exceeded":0}}
                """.formatted(PACKAGE_ID)));

        GatewayLimitUsageClient.LimitUsage usage = client().fetch(PACKAGE_ID);

        assertThat(usage.perMinute().limit()).isNull();
        assertThat(usage.perMinute().used()).isEqualTo(30L);
        assertThat(usage.daily()).isNull();
        assertThat(usage.blockedLast24h()).isZero();
    }

    @Test
    void rejectsANonOkAnswerWithoutRetrying() {
        server.createContext(GatewayLimitUsageClient.PATH, request -> {
            requests.incrementAndGet();
            respond(request, 503, "{\"code\":\"limit_usage_unavailable\"}");
        });

        assertThatThrownBy(() -> client().fetch(PACKAGE_ID))
                .isInstanceOf(GatewayInternalUnavailableException.class)
                .hasMessageContaining("gateway_internal_http_status:503");
        assertThat(requests).hasValue(1);
    }

    @Test
    void rejectsAnUnparseableBody() {
        server.createContext(GatewayLimitUsageClient.PATH, request ->
                respond(request, 200, "not-json"));

        assertThatThrownBy(() -> client().fetch(PACKAGE_ID))
                .isInstanceOf(GatewayInternalUnavailableException.class)
                .hasMessageContaining("gateway_limit_usage_payload_invalid");
    }

    @Test
    void rejectsAnAnswerAboutAnotherInstanceOrWithoutADimension() {
        server.createContext(GatewayLimitUsageClient.PATH, request -> {
            requests.incrementAndGet();
            respond(request, 200, requests.get() == 1
                    ? fullBody(OTHER_PACKAGE_ID, "TOKEN")
                    : fullBody(PACKAGE_ID, "   "));
        });

        assertThatThrownBy(() -> client().fetch(PACKAGE_ID))
                .isInstanceOf(GatewayInternalUnavailableException.class)
                .hasMessageContaining("gateway_limit_usage_payload_invalid");
        assertThatThrownBy(() -> client().fetch(PACKAGE_ID))
                .isInstanceOf(GatewayInternalUnavailableException.class)
                .hasMessageContaining("gateway_limit_usage_payload_invalid");
        assertThat(requests).hasValue(2);
    }

    @Test
    void rejectsAnEmptyBody() {
        server.createContext(GatewayLimitUsageClient.PATH, request -> respond(request, 200, ""));

        assertThatThrownBy(() -> client().fetch(PACKAGE_ID))
                .isInstanceOf(GatewayInternalUnavailableException.class)
                .hasMessageContaining("gateway_internal_response_body_invalid");
    }

    @Test
    void boundedReadTimeoutFailsInsteadOfHangingWithoutRetry() {
        server.createContext(GatewayLimitUsageClient.PATH, request -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(400);
                respond(request, 200, fullBody(PACKAGE_ID, "TOKEN"));
            } catch (Exception ignored) {
                // 预期客户端会放弃这个已超时的 HttpExchange。
            } finally {
                request.close();
            }
        });
        GatewayLimitUsageClient client = client(stubUri(), SECRET, Duration.ofMillis(50));

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> client.fetch(PACKAGE_ID))
                .isInstanceOf(GatewayInternalUnavailableException.class)
                .hasMessageContaining("gateway_internal_request_failed");

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                .isLessThan(Duration.ofSeconds(2));
        assertThat(requests).hasValue(1);
    }

    @Test
    void refusedConnectionIsUnavailable() {
        GatewayLimitUsageClient client = client(closedPortUri(), SECRET, Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.fetch(PACKAGE_ID))
                .isInstanceOf(GatewayInternalUnavailableException.class)
                .hasMessageContaining("gateway_internal_request_failed");
        assertThat(requests).hasValue(0);
    }

    @Test
    void refusesToCallTheGatewayWithoutAnHmacSecret() {
        server.createContext(GatewayLimitUsageClient.PATH, request -> {
            requests.incrementAndGet();
            respond(request, 200, fullBody(PACKAGE_ID, "TOKEN"));
        });

        assertThatThrownBy(() -> client(stubUri(), "", Duration.ofSeconds(2)).fetch(PACKAGE_ID))
                .isInstanceOf(GatewayInternalUnavailableException.class)
                .hasMessageContaining("security.internal.hmac-secret must be configured");
        assertThat(requests).hasValue(0);
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        assertThatThrownBy(() -> new GatewayInternalGet(
                HttpClient.newHttpClient(), stubUri(), SECRET, CLOCK, () -> REQUEST_ID, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("readTimeout");
        assertThat(requests).hasValue(0);
    }

    private void assertSignatureCoversThePathOnly(HttpExchange captured) {
        boolean verified = new InternalRequestSigner(SECRET, CLOCK).verify(
                NOW.getEpochSecond(),
                REQUEST_ID,
                "GET",
                GatewayLimitUsageClient.PATH,
                new byte[0],
                captured.getRequestHeaders().getFirst("X-Internal-Content-Sha256"),
                captured.getRequestHeaders().getFirst("X-Internal-Signature"));
        assertThat(verified).isTrue();
    }

    private GatewayLimitUsageClient client() {
        return client(stubUri(), SECRET, Duration.ofSeconds(2));
    }

    private GatewayLimitUsageClient client(URI baseUri, String secret, Duration readTimeout) {
        GatewayInternalGet internalGet = new GatewayInternalGet(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(1))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                baseUri, secret, CLOCK, () -> REQUEST_ID, readTimeout);
        return new GatewayLimitUsageClient(objectMapper, internalGet);
    }

    private URI stubUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private URI closedPortUri() {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String fullBody(UUID packageInstanceId, String usageDimension) {
        return """
                {"packageInstanceId":"%s","usageDimension":"%s",
                 "perMinute":{"limit":100000,"used":1234},
                 "daily":{"limit":2000,"used":17},
                 "blockedLast24h":3,
                 "blockedLast24HoursByReason":{"daily_request_limit_exceeded":1,
                                               "tokens_per_minute_exceeded":2}}
                """.formatted(packageInstanceId, usageDimension);
    }

    private static void respond(HttpExchange request, int status, String body) throws IOException {
        request.setAttribute("captured-body", request.getRequestBody().readAllBytes());
        byte[] serialized = body.getBytes(StandardCharsets.UTF_8);
        request.getResponseHeaders().set("Content-Type", "application/json");
        request.sendResponseHeaders(status, serialized.length);
        request.getResponseBody().write(serialized);
        request.close();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
