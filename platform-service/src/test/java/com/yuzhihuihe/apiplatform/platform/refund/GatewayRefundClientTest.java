package com.yuzhihuihe.apiplatform.platform.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.http.HttpConnectTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class GatewayRefundClientTest {

    private static final String SECRET = "test-internal-secret";
    private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");
    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID GRANT_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PACKAGE_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");

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
    void sendsOnceSerializedBodyWithExactHmacV2HeadersAndAcceptsReadySnapshot() throws Exception {
        byte[] payload = objectMapper.writeValueAsBytes(new GatewayRefundClient.RefundSnapshot(
                "refund-hold:refund_1", PACKAGE_ID, "READY", "ACTIVE",
                10_000L, 250L, 9_750L, NOW.plusSeconds(3600), NOW));
        server.createContext("/internal/refunds/prepare", request -> {
            requests.incrementAndGet();
            exchange.set(request);
            respond(request, 200, signedEnvelope(payload, REQUEST_ID, 200));
        });
        GatewayRefundClient client = client(Duration.ofSeconds(1));

        GatewayRefundClient.RefundSnapshot snapshot = client.prepare(
                "refund-hold:refund_1", GRANT_ID);

        assertThat(snapshot.status()).isEqualTo("READY");
        assertThat(snapshot.remainingTokens()).isEqualTo(9_750);
        assertThat(requests).hasValue(1);
        HttpExchange captured = exchange.get();
        byte[] requestBody = captured.getAttribute("captured-body") instanceof byte[] body
                ? body : new byte[0];
        byte[] expected = objectMapper.writeValueAsBytes(
                new GatewayRefundClient.PrepareRefundCommand("refund-hold:refund_1", GRANT_ID));
        assertThat(requestBody).isEqualTo(expected);
        assertThat(captured.getRequestHeaders().getFirst("X-Internal-Timestamp"))
                .isEqualTo(Long.toString(NOW.getEpochSecond()));
        assertThat(captured.getRequestHeaders().getFirst("X-Internal-Request-Id"))
                .isEqualTo(REQUEST_ID.toString());
        assertThat(captured.getRequestHeaders().getFirst("X-Internal-Content-Sha256"))
                .isEqualTo(sha256(expected));
        assertThat(captured.getRequestHeaders().getFirst("X-Internal-Signature"))
                .isEqualTo(requestSignature(expected, "/internal/refunds/prepare"));
    }

    @Test
    void acceptsWaitingAndValidatesReleaseIdentifiers() throws Exception {
        byte[] waiting = objectMapper.writeValueAsBytes(new GatewayRefundClient.RefundSnapshot(
                "refund-hold:refund_2", PACKAGE_ID, "WAITING", "ACTIVE",
                null, null, null, NOW.plusSeconds(3600), null));
        byte[] released = objectMapper.writeValueAsBytes(
                new GatewayRefundClient.ReleaseResult("refund-hold:refund_2", "RELEASED"));
        server.createContext("/internal/refunds/prepare", request ->
                respond(request, 200, signedEnvelope(waiting, REQUEST_ID, 200)));
        server.createContext("/internal/refunds/release", request ->
                respond(request, 200, signedEnvelope(released, REQUEST_ID, 200)));
        GatewayRefundClient client = client(Duration.ofSeconds(1));

        assertThat(client.prepare("refund-hold:refund_2", GRANT_ID).status()).isEqualTo("WAITING");
        assertThat(client.release("refund-hold:refund_2", GRANT_ID).status()).isEqualTo("RELEASED");
    }

    @Test
    void acceptsQueuedReadySnapshotWithoutExpiryButRejectsActiveSnapshotWithoutExpiry()
            throws Exception {
        byte[] queued = objectMapper.writeValueAsBytes(new GatewayRefundClient.RefundSnapshot(
                "refund-hold:refund_queued", PACKAGE_ID, "READY", "QUEUED",
                10_000L, 0L, 10_000L, null, NOW));
        byte[] active = objectMapper.writeValueAsBytes(new GatewayRefundClient.RefundSnapshot(
                "refund-hold:refund_active", PACKAGE_ID, "READY", "ACTIVE",
                10_000L, 0L, 10_000L, null, NOW));
        AtomicInteger invocation = new AtomicInteger();
        server.createContext("/internal/refunds/prepare", request -> {
            byte[] payload = invocation.getAndIncrement() == 0 ? queued : active;
            respond(request, 200, signedEnvelope(payload, REQUEST_ID, 200));
        });
        GatewayRefundClient client = client(Duration.ofSeconds(1));

        assertThat(client.prepare("refund-hold:refund_queued", GRANT_ID).originalStatus())
                .isEqualTo("QUEUED");
        assertThatThrownBy(() -> client.prepare("refund-hold:refund_active", GRANT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway_refund_snapshot_schema_invalid");
    }

    @Test
    void rejectsTamperedEnvelopeBeforeParsingMalformedPayload() throws Exception {
        byte[] malformedPayload = "not-json".getBytes(StandardCharsets.UTF_8);
        GatewayRefundClient.SignedInternalResponse signed =
                signedEnvelope(malformedPayload, REQUEST_ID, 200);
        GatewayRefundClient.SignedInternalResponse tampered = new GatewayRefundClient.SignedInternalResponse(
                signed.requestId(), signed.timestamp(), signed.contentSha256(),
                "0".repeat(64), signed.payloadBase64());
        server.createContext("/internal/refunds/prepare", request -> respond(request, 200, tampered));

        assertThatThrownBy(() -> client(Duration.ofSeconds(1))
                .prepare("refund-hold:refund_3", GRANT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway_refund_response_signature_invalid")
                .hasMessageNotContaining("parse");
    }

    @Test
    void rejectsChangedDecodedPayloadAgainstSignedContentHashBeforeParsing() throws Exception {
        byte[] originalPayload = "not-json".getBytes(StandardCharsets.UTF_8);
        GatewayRefundClient.SignedInternalResponse signed =
                signedEnvelope(originalPayload, REQUEST_ID, 200);
        GatewayRefundClient.SignedInternalResponse tampered =
                new GatewayRefundClient.SignedInternalResponse(
                        signed.requestId(), signed.timestamp(), signed.contentSha256(),
                        signed.signature(), Base64.getEncoder().encodeToString(
                                "changed-not-json".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/internal/refunds/prepare", request ->
                respond(request, 200, tampered));

        assertThatThrownBy(() -> client(Duration.ofSeconds(1))
                .prepare("refund-hold:refund_payload", GRANT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway_refund_response_signature_invalid")
                .hasMessageNotContaining("payload_invalid");
    }

    @Test
    void rejectsWrongRequestIdTimestampHashStatusAndSnapshotArithmetic() throws Exception {
        byte[] validPayload = objectMapper.writeValueAsBytes(new GatewayRefundClient.RefundSnapshot(
                "refund-hold:refund_4", PACKAGE_ID, "READY", "ACTIVE",
                10_000L, 250L, 9_751L, NOW.plusSeconds(3600), NOW));
        AtomicInteger invocation = new AtomicInteger();
        server.createContext("/internal/refunds/prepare", request -> {
            int current = invocation.getAndIncrement();
            GatewayRefundClient.SignedInternalResponse response = switch (current) {
                case 0 -> signedEnvelope(validPayload, UUID.randomUUID(), 200);
                case 1 -> signedEnvelope(validPayload, REQUEST_ID, 200, NOW.minusSeconds(301));
                case 2 -> {
                    var valid = signedEnvelope(validPayload, REQUEST_ID, 200);
                    yield new GatewayRefundClient.SignedInternalResponse(
                            valid.requestId(), valid.timestamp(), "0".repeat(64),
                            valid.signature(), valid.payloadBase64());
                }
                default -> signedEnvelope(validPayload, REQUEST_ID, 200);
            };
            respond(request, current == 3 ? 201 : 200, response);
        });
        GatewayRefundClient client = client(Duration.ofSeconds(1));

        assertInvalid(client, "gateway_refund_response_signature_invalid");
        assertInvalid(client, "gateway_refund_response_signature_invalid");
        assertInvalid(client, "gateway_refund_response_signature_invalid");
        assertInvalid(client, "gateway_refund_http_status:201");
        assertInvalid(client, "gateway_refund_snapshot_arithmetic_invalid");
        assertThat(invocation).hasValue(5);
    }

    @Test
    void rejectsRedirectsAndDoesNotRetryFailures() throws Exception {
        server.createContext("/internal/refunds/prepare", request -> {
            requests.incrementAndGet();
            request.getResponseHeaders().add("Location", "/redirected");
            request.sendResponseHeaders(307, -1);
            request.close();
        });
        server.createContext("/redirected", request -> {
            requests.incrementAndGet();
            request.sendResponseHeaders(500, -1);
            request.close();
        });

        assertThatThrownBy(() -> client(Duration.ofSeconds(1))
                .prepare("refund-hold:refund_5", GRANT_ID))
                .hasMessageContaining("gateway_refund_http_status:307");
        assertThat(requests).hasValue(1);
    }

    @Test
    void rejectsSignedPayloadWithUnknownSchemaFields() {
        byte[] payload = ("""
                {
                  "businessNo":"refund-hold:refund_schema",
                  "packageInstanceId":"%s",
                  "status":"READY",
                  "originalStatus":"ACTIVE",
                  "totalTokens":10000,
                  "usedTokens":250,
                  "remainingTokens":9750,
                  "packageExpiresAt":"2030-01-02T04:04:05Z",
                  "snapshotAt":"2030-01-02T03:04:05Z",
                  "unexpected":"must-not-be-ignored"
                }
                """).formatted(PACKAGE_ID).getBytes(StandardCharsets.UTF_8);
        server.createContext("/internal/refunds/prepare", request ->
                respond(request, 200, signedEnvelope(payload, REQUEST_ID, 200)));

        assertThatThrownBy(() -> client(Duration.ofSeconds(1))
                .prepare("refund-hold:refund_schema", GRANT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway_refund_response_payload_invalid");
    }

    @Test
    void enforcesBoundedReadTimeoutWithoutRetry() {
        server.createContext("/internal/refunds/prepare", request -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(300);
                request.sendResponseHeaders(500, -1);
            } catch (Exception ignored) {
                // 预期客户端会关闭这个已超时的交换。
            } finally {
                request.close();
            }
        });

        assertThatThrownBy(() -> client(Duration.ofMillis(50))
                .prepare("refund-hold:refund_6", GRANT_ID))
                .hasMessageContaining("gateway_refund_request_failed");
        assertThat(requests).hasValue(1);
    }

    @Test
    void enforcesActualConnectTimeoutWithoutRetry() {
        GatewayRefundClient client = new GatewayRefundClient(
                objectMapper,
                URI.create("http://198.51.100.1:81"),
                SECRET,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMillis(100),
                Duration.ofSeconds(2),
                () -> REQUEST_ID);
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> client.prepare("refund-hold:refund_connect", GRANT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway_refund_request_failed")
                .cause()
                .isInstanceOf(HttpConnectTimeoutException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                .isLessThan(Duration.ofSeconds(1));
        assertThat(requests).hasValue(0);
    }

    @Test
    void doesNotLogSnapshotPayloadBusinessNumberOrInternalSecret(CapturedOutput output)
            throws Exception {
        String businessNo = "refund-hold:refund_log_redaction";
        byte[] payload = objectMapper.writeValueAsBytes(new GatewayRefundClient.RefundSnapshot(
                businessNo, PACKAGE_ID, "READY", "ACTIVE",
                10_000L, 250L, 9_750L, NOW.plusSeconds(3600), NOW));
        server.createContext("/internal/refunds/prepare", request ->
                respond(request, 200, signedEnvelope(payload, REQUEST_ID, 200)));

        assertThat(client(Duration.ofSeconds(1)).prepare(businessNo, GRANT_ID).status())
                .isEqualTo("READY");

        assertThat(output.getAll())
                .doesNotContain(SECRET)
                .doesNotContain(businessNo)
                .doesNotContain("totalTokens")
                .doesNotContain(Base64.getEncoder().encodeToString(payload));
    }

    @Test
    void rejectsNonPositiveConnectAndReadTimeouts() {
        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());

        assertThatThrownBy(() -> new GatewayRefundClient(
                objectMapper, baseUri, SECRET, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ZERO, Duration.ofSeconds(1), () -> REQUEST_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectTimeout");
        assertThatThrownBy(() -> new GatewayRefundClient(
                objectMapper, baseUri, SECRET, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(1), Duration.ZERO, () -> REQUEST_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("readTimeout");
        assertThat(requests).hasValue(0);
    }

    private void assertInvalid(GatewayRefundClient client, String reason) {
        assertThatThrownBy(() -> client.prepare("refund-hold:refund_4", GRANT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reason);
    }

    private GatewayRefundClient client(Duration readTimeout) {
        return new GatewayRefundClient(
                objectMapper,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                SECRET,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(1),
                readTimeout,
                () -> REQUEST_ID);
    }

    private void respond(
            HttpExchange request,
            int status,
            GatewayRefundClient.SignedInternalResponse body) throws IOException {
        request.setAttribute("captured-body", request.getRequestBody().readAllBytes());
        byte[] serialized = objectMapper.writeValueAsBytes(body);
        request.getResponseHeaders().set("Content-Type", "application/json");
        request.sendResponseHeaders(status, serialized.length);
        request.getResponseBody().write(serialized);
        request.close();
    }

    private GatewayRefundClient.SignedInternalResponse signedEnvelope(
            byte[] payload, UUID requestId, int status) {
        return signedEnvelope(payload, requestId, status, NOW);
    }

    private GatewayRefundClient.SignedInternalResponse signedEnvelope(
            byte[] payload, UUID requestId, int status, Instant timestamp) {
        try {
            String hash = sha256(payload);
            String canonical = timestamp.getEpochSecond() + "\n" + requestId + "\n" + status + "\n" + hash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return new GatewayRefundClient.SignedInternalResponse(
                    requestId,
                    timestamp.getEpochSecond(),
                    hash,
                    HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))),
                    Base64.getEncoder().encodeToString(payload));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String requestSignature(byte[] body, String path) throws Exception {
        String hash = sha256(body);
        String canonical = NOW.getEpochSecond() + "\n" + REQUEST_ID + "\nPOST\n" + path + "\n" + hash;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
