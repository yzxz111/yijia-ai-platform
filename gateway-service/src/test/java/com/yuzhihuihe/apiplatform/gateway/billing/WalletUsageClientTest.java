package com.yuzhihuihe.apiplatform.gateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;

class WalletUsageClientTest {

    private static final String SECRET = "wallet-usage-client-secret";
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
    private HttpServer server;
    private int responseStatus;
    private String responseBody;
    private WalletUsageClient client;

    @BeforeEach
    void setUp() throws Exception {
        responseStatus = 200;
        responseBody = "{\"usable\":true,\"availableAmount\":17,\"currency\":\"CNY\"}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = new WalletUsageClient(WebClient.builder(), new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), SECRET);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void signsGetWithoutAJsonBodyAndMapsEligibility() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        WalletUsageClient.WalletEligibility result = client.eligibility(tenantId, userId).block();

        assertThat(result).isEqualTo(new WalletUsageClient.WalletEligibility(true, 17L, "CNY"));
        CapturedRequest request = captured.get();
        String expectedPath = "/internal/platform/wallet/eligibility/" + tenantId + "/" + userId;
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo(expectedPath);
        assertThat(request.body()).isEmpty();
        assertThat(request.contentType()).isNull();
        assertThat(request.contentSha256()).isEqualTo(EMPTY_SHA256);
        long timestamp = Long.parseLong(request.timestamp());
        UUID requestId = UUID.fromString(request.requestId());
        assertThat(Instant.ofEpochSecond(timestamp)).isBetween(
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(1));
        assertThat(request.signature()).isEqualTo(hmac(timestamp + "\n" + requestId
                + "\nGET\n" + expectedPath + "\n" + EMPTY_SHA256));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 500})
    void mapsHttpFailuresWithoutExposingSensitiveResponseDetails(int status) {
        responseStatus = status;
        responseBody = "upstream-sensitive-detail-" + SECRET;

        Throwable error = catchThrowable(() ->
                client.eligibility(UUID.randomUUID(), UUID.randomUUID()).block());

        assertThat(error).isInstanceOf(WalletUsageClient.WalletUsageClientException.class)
                .hasMessage("wallet_billing_unavailable")
                .extracting(candidate ->
                        ((WalletUsageClient.WalletUsageClientException) candidate).status())
                .isEqualTo(status);
        assertThat(error.toString()).doesNotContain(SECRET);
    }

    @Test
    void preservesApprovedWalletCodeWithoutExposingTheInternalReason() {
        responseStatus = 422;
        responseBody = "{\"code\":\"wallet_insufficient_balance\","
                + "\"reason\":\"sensitive-" + SECRET + "\"}";

        Throwable error = catchThrowable(() -> client.reserve(walletRequest()).block());

        assertThat(error).isInstanceOf(WalletUsageClient.WalletUsageClientException.class)
                .hasMessage("wallet_insufficient_balance");
        WalletUsageClient.WalletUsageClientException walletError =
                (WalletUsageClient.WalletUsageClientException) error;
        assertThat(walletError.code()).isEqualTo("wallet_insufficient_balance");
        assertThat(walletError.status()).isEqualTo(422);
        assertThat(error.toString()).doesNotContain(SECRET);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"code\":\"unapproved_internal_failure\",\"reason\":\"sensitive-detail\"}",
            "not-json"
    })
    void rejectsUnapprovedOrMalformedWalletErrors(String body) {
        responseStatus = 422;
        responseBody = body;

        Throwable error = catchThrowable(() -> client.reserve(walletRequest()).block());

        assertThat(error).isInstanceOf(WalletUsageClient.WalletUsageClientException.class)
                .hasMessage("wallet_billing_unavailable");
        WalletUsageClient.WalletUsageClientException walletError =
                (WalletUsageClient.WalletUsageClientException) error;
        assertThat(walletError.code()).isEqualTo("wallet_billing_unavailable");
        assertThat(walletError.status()).isEqualTo(422);
        assertThat(error.toString()).doesNotContain(body);
    }

    private WalletUsageClient.WalletRequest walletRequest() {
        return new WalletUsageClient.WalletRequest(
                UUID.randomUUID(), "request-1", UUID.randomUUID(), UUID.randomUUID(),
                "text-basic", 10, 5, 5, "price-v1", "wallet-test");
    }

    private void handle(HttpExchange exchange) {
        try (exchange) {
            captured.set(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestBody().readAllBytes(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("X-Internal-Timestamp"),
                    exchange.getRequestHeaders().getFirst("X-Internal-Request-Id"),
                    exchange.getRequestHeaders().getFirst("X-Internal-Content-Sha256"),
                    exchange.getRequestHeaders().getFirst("X-Internal-Signature")));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private String hmac(String canonical) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(
                mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private record CapturedRequest(
            String method,
            String path,
            byte[] body,
            String contentType,
            String timestamp,
            String requestId,
            String contentSha256,
            String signature) {
    }
}
