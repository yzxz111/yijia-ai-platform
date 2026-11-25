package com.yuzhihuihe.apiplatform.platform.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

class GatewaySettlementDeficitClientTest {

    private static final String SECRET = "test-internal-secret";
    private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");
    private static final Instant FROM = Instant.parse("2026-01-02T03:04:05Z");
    private static final Instant TO = Instant.parse("2026-01-03T03:04:05Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RESERVATION_ID =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID USER_ID =
            UUID.fromString("66666666-6666-4666-8666-666666666666");

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
    void asksForTheRequestedWindowUnchangedWithSignedHeadersAndParsesItems() throws Exception {
        server.createContext(GatewaySettlementDeficitClient.PATH, request -> {
            requests.incrementAndGet();
            exchange.set(request);
            respond(request, 200, body(1, oneItem()));
        });

        GatewaySettlementDeficitClient.SettlementDeficit deficit = client().fetch(FROM, TO);

        assertThat(deficit.totalAmount()).isEqualTo(12_345L);
        assertThat(deficit.totalTokens()).isEqualTo(67_890L);
        assertThat(deficit.count()).isEqualTo(1L);
        assertThat(deficit.items()).hasSize(1);
        GatewaySettlementDeficitClient.SettlementDeficit.Item item = deficit.items().getFirst();
        assertThat(item.reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(item.amount()).isEqualTo(100L);
        assertThat(item.tokens()).isEqualTo(200L);
        assertThat(item.occurredAt()).isEqualTo(FROM);
        assertThat(item.planCode()).isEqualTo("STARTER");
        assertThat(item.userId()).isEqualTo(USER_ID);

        HttpExchange captured = exchange.get();
        assertThat(captured.getRequestMethod()).isEqualTo("GET");
        assertThat(captured.getRequestURI().getPath())
                .isEqualTo(GatewaySettlementDeficitClient.PATH);
        assertThat(captured.getRequestURI().getRawQuery()).isEqualTo(
                "from=2026-01-02T03%3A04%3A05Z&to=2026-01-03T03%3A04%3A05Z");
        assertThat(decoded(captured, "from")).isEqualTo(FROM.toString());
        assertThat(decoded(captured, "to")).isEqualTo(TO.toString());
        assertThat(captured.getRequestHeaders().getFirst("X-Internal-Timestamp"))
                .isEqualTo(Long.toString(NOW.getEpochSecond()));
        assertThat(captured.getRequestHeaders().getFirst("X-Internal-Request-Id"))
                .isEqualTo(REQUEST_ID.toString());
        assertThat(captured.getRequestHeaders().getFirst("X-Internal-Content-Sha256")).isNotBlank();
        assertThat(captured.getRequestHeaders().getFirst("X-Internal-Signature")).isNotBlank();
        assertThat(new InternalRequestSigner(SECRET, CLOCK).verify(
                NOW.getEpochSecond(), REQUEST_ID, "GET", GatewaySettlementDeficitClient.PATH,
                new byte[0],
                captured.getRequestHeaders().getFirst("X-Internal-Content-Sha256"),
                captured.getRequestHeaders().getFirst("X-Internal-Signature"))).isTrue();
        assertThat(requests).hasValue(1);
    }

    @Test
    void omitsTheQueryWhenNoWindowWasRequested() {
        server.createContext(GatewaySettlementDeficitClient.PATH, request -> {
            exchange.set(request);
            respond(request, 200, body(0, ""));
        });

        GatewaySettlementDeficitClient.SettlementDeficit deficit = client().fetch(null, null);

        assertThat(deficit.items()).isEmpty();
        assertThat(deficit.count()).isZero();
        assertThat(exchange.get().getRequestURI().toString())
                .isEqualTo(GatewaySettlementDeficitClient.PATH);
    }

    @Test
    void acceptsTheGatewaysEmptyAnswerAsAnEmptyResult() {
        server.createContext(GatewaySettlementDeficitClient.PATH, request -> respond(
                request, 200,
                "{\"totalAmount\":0,\"totalTokens\":0,\"count\":0,\"items\":[]}"));

        GatewaySettlementDeficitClient.SettlementDeficit deficit = client().fetch(FROM, TO);

        assertThat(deficit.totalAmount()).isZero();
        assertThat(deficit.totalTokens()).isZero();
        assertThat(deficit.count()).isZero();
        assertThat(deficit.items()).isEmpty();
    }

    @Test
    void rejectsAnAnswerWhoseItemsDisagreeWithItsOwnCount() {
        server.createContext(GatewaySettlementDeficitClient.PATH, request ->
                respond(request, 200, body(2, oneItem())));

        assertThatThrownBy(() -> client().fetch(FROM, TO))
                .isInstanceOf(GatewayInternalUnavailableException.class)
                .hasMessageContaining("gateway_settlement_deficit_payload_invalid");
    }

    @Test
    void acceptsATruncatedAnswerWhoseListIsASliceOfTheCount() throws Exception {
        // 610 条已吸收的预留，而网关只返回最近的一个切片，
        // 且 truncated=true。正是这种形态过去会导致校验失败并让管理页面返回 503，
        // 从而隐藏平台已经损失的资金。
        server.createContext(GatewaySettlementDeficitClient.PATH, request ->
                respond(request, 200, truncatedBody(610, true, oneItem())));

        GatewaySettlementDeficitClient.SettlementDeficit deficit = client().fetch(FROM, TO);

        assertThat(deficit.totalAmount()).isEqualTo(12_345L);
        assertThat(deficit.totalTokens()).isEqualTo(67_890L);
        assertThat(deficit.count()).isEqualTo(610L);
        assertThat(deficit.truncated()).isTrue();
        assertThat(deficit.items()).hasSize(1);
    }

    @Test
    void rejectsATruncatedAnswerThatListsMoreItemsThanItsPopulation() {
        server.createContext(GatewaySettlementDeficitClient.PATH, request ->
                respond(request, 200, truncatedBody(1, true, twoItems())));

        assertThatThrownBy(() -> client().fetch(FROM, TO))
                .isInstanceOf(GatewayInternalUnavailableException.class)
                .hasMessageContaining("gateway_settlement_deficit_payload_invalid");
    }

    @Test
    void rejectsAnExplicitlyCompleteAnswerWhoseItemsDisagreeWithItsCount() {
        // truncated=false 表示网关在说「这就是全部总体」，因此该列表必须
        // 覆盖其中每一条。
        server.createContext(GatewaySettlementDeficitClient.PATH, request ->
                respond(request, 200, truncatedBody(2, false, oneItem())));

        assertThatThrownBy(() -> client().fetch(FROM, TO))
                .isInstanceOf(GatewayInternalUnavailableException.class)
                .hasMessageContaining("gateway_settlement_deficit_payload_invalid");
    }

    @Test
    void passesAnAbsentTruncationFlagThroughAsUnknown() throws Exception {
        // 控制台必须能够区分「网关说这就是全部」与「网关没有
        // 说」：后者渲染为「—」，而不是声称列表完整。
        server.createContext(GatewaySettlementDeficitClient.PATH, request ->
                respond(request, 200, body(1, oneItem())));

        assertThat(client().fetch(FROM, TO).truncated()).isNull();
    }

    @Test
    void rejectsAnItemWithoutAReservationAnOwnerOrAnOccurrence() {
        server.createContext(GatewaySettlementDeficitClient.PATH, request -> {
            requests.incrementAndGet();
            respond(request, 200, """
                    {"totalAmount":100,"totalTokens":200,"count":1,"items":[
                      {"reservationId":null,"amount":100,"tokens":200,
                       "occurredAt":"2026-01-02T03:04:05Z","planCode":"STARTER","userId":"%s"}]}
                    """.formatted(USER_ID));
        });

        assertThatThrownBy(() -> client().fetch(FROM, TO))
                .isInstanceOf(GatewayInternalUnavailableException.class)
                .hasMessageContaining("gateway_settlement_deficit_payload_invalid");
        assertThat(requests).hasValue(1);
    }

    @Test
    void rejectsANonOkAnswerWithoutRetrying() {
        server.createContext(GatewaySettlementDeficitClient.PATH, request -> {
            requests.incrementAndGet();
            respond(request, 500, "{\"code\":\"settlement_deficit_unavailable\"}");
        });

        assertThatThrownBy(() -> client().fetch(FROM, TO))
                .isInstanceOf(GatewayInternalUnavailableException.class)
                .hasMessageContaining("gateway_internal_http_status:500");
        assertThat(requests).hasValue(1);
    }

    @Test
    void rejectsAnUnparseableBody() {
        server.createContext(GatewaySettlementDeficitClient.PATH, request ->
                respond(request, 200, "<html>proxy</html>"));

        assertThatThrownBy(() -> client().fetch(FROM, TO))
                .isInstanceOf(GatewayInternalUnavailableException.class)
                .hasMessageContaining("gateway_settlement_deficit_payload_invalid");
    }

    @Test
    void rejectsAnIncompleteReversedOrOverlongWindowWithoutCallingTheGateway() {
        GatewaySettlementDeficitClient client = client();

        for (Instant[] window : new Instant[][] {
                {FROM, null},
                {null, TO},
                {TO, FROM},
                {FROM, FROM},
                {FROM, FROM.plus(Duration.ofDays(367))}}) {
            assertThatThrownBy(() -> client.fetch(window[0], window[1]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("gateway_settlement_deficit_range_invalid");
        }
        assertThat(requests).hasValue(0);
    }

    @Test
    void refusedConnectionIsUnavailable() {
        GatewaySettlementDeficitClient client = new GatewaySettlementDeficitClient(
                objectMapper, internalGet(closedPortUri(), SECRET));

        assertThatThrownBy(() -> client.fetch(FROM, TO))
                .isInstanceOf(GatewayInternalUnavailableException.class)
                .hasMessageContaining("gateway_internal_request_failed");
        assertThat(requests).hasValue(0);
    }

    @Test
    void refusesToCallTheGatewayWithoutAnHmacSecret() {
        server.createContext(GatewaySettlementDeficitClient.PATH, request -> {
            requests.incrementAndGet();
            respond(request, 200, body(0, ""));
        });
        GatewaySettlementDeficitClient client = new GatewaySettlementDeficitClient(
                objectMapper, internalGet(stubUri(), ""));

        assertThatThrownBy(() -> client.fetch(FROM, TO))
                .isInstanceOf(GatewayInternalUnavailableException.class)
                .hasMessageContaining("security.internal.hmac-secret must be configured");
        assertThat(requests).hasValue(0);
    }

    private GatewaySettlementDeficitClient client() {
        return new GatewaySettlementDeficitClient(objectMapper, internalGet(stubUri(), SECRET));
    }

    private GatewayInternalGet internalGet(URI baseUri, String secret) {
        return new GatewayInternalGet(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(1))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                baseUri, secret, CLOCK, () -> REQUEST_ID, Duration.ofSeconds(2));
    }

    private URI stubUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private URI closedPortUri() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String decoded(HttpExchange captured, String name) {
        for (String pair : captured.getRequestURI().getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name)) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("missing query parameter " + name);
    }

    private static String oneItem() {
        return """
                {"reservationId":"%s","amount":100,"tokens":200,
                 "occurredAt":"2026-01-02T03:04:05Z","planCode":"STARTER","userId":"%s"}
                """.formatted(RESERVATION_ID, USER_ID);
    }

    private static String body(long count, String items) {
        return """
                {"totalAmount":12345,"totalTokens":67890,"count":%d,"items":[%s]}
                """.formatted(count, items);
    }

    /** 网关总会发送 {@code truncated}；这些响应体固定住它的两个取值。 */
    private static String truncatedBody(long count, boolean truncated, String items) {
        return """
                {"totalAmount":12345,"totalTokens":67890,"count":%d,"truncated":%s,"items":[%s]}
                """.formatted(count, truncated, items);
    }

    private static String twoItems() {
        return oneItem() + "," + oneItem();
    }

    private static void respond(HttpExchange request, int status, String body) throws IOException {
        request.setAttribute("captured-body", request.getRequestBody().readAllBytes());
        byte[] serialized = body.getBytes(StandardCharsets.UTF_8);
        request.getResponseHeaders().set("Content-Type", "application/json");
        request.sendResponseHeaders(status, serialized.length);
        request.getResponseBody().write(serialized);
        request.close();
    }
}
