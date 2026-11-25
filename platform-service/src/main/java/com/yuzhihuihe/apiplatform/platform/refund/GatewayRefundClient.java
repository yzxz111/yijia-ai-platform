package com.yuzhihuihe.apiplatform.platform.refund;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class GatewayRefundClient {

    private static final String PREPARE_PATH = "/internal/refunds/prepare";
    private static final String RELEASE_PATH = "/internal/refunds/release";
    private static final int MAXIMUM_RESPONSE_BYTES = 1_048_576;

    private final ObjectMapper objectMapper;
    private final URI gatewayBaseUri;
    private final InternalRequestSigner requestSigner;
    private final Clock clock;
    private final Duration readTimeout;
    private final Supplier<UUID> requestIds;
    private final HttpClient httpClient;
    private final boolean signingConfigured;

    @Autowired
    public GatewayRefundClient(
            ObjectMapper objectMapper,
            @Value("${gateway.internal.base-url:http://gateway-service:8081}") URI gatewayBaseUri,
            @Value("${security.internal.hmac-secret:}") String hmacSecret) {
        this(
                objectMapper,
                gatewayBaseUri,
                hmacSecret,
                Clock.systemUTC(),
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                UUID::randomUUID);
    }

    GatewayRefundClient(
            ObjectMapper objectMapper,
            URI gatewayBaseUri,
            String hmacSecret,
            Clock clock,
            Duration connectTimeout,
            Duration readTimeout,
            Supplier<UUID> requestIds) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.gatewayBaseUri = Objects.requireNonNull(gatewayBaseUri, "gatewayBaseUri");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.readTimeout = positive(readTimeout, "readTimeout");
        this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
        String secret = Objects.requireNonNull(hmacSecret, "hmacSecret");
        this.requestSigner = new InternalRequestSigner(secret, clock);
        this.signingConfigured = !secret.isBlank();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(positive(connectTimeout, "connectTimeout"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public RefundSnapshot prepare(String businessNo, UUID grantRequestId) {
        validateCommand(businessNo, grantRequestId);
        RefundSnapshot snapshot = post(
                PREPARE_PATH,
                new PrepareRefundCommand(businessNo, grantRequestId),
                RefundSnapshot.class);
        validateSnapshot(snapshot, businessNo);
        return snapshot;
    }

    public ReleaseResult release(String businessNo, UUID grantRequestId) {
        validateCommand(businessNo, grantRequestId);
        ReleaseResult result = post(
                RELEASE_PATH,
                new ReleaseRefundCommand(businessNo, grantRequestId),
                ReleaseResult.class);
        if (result == null
                || !businessNo.equals(result.businessNo())
                || !"RELEASED".equals(result.status())) {
            throw new IllegalStateException("gateway_refund_release_schema_invalid");
        }
        return result;
    }

    private <T> T post(String path, Object command, Class<T> responseType) {
        if (!signingConfigured) {
            throw new IllegalStateException("security.internal.hmac-secret must be configured");
        }
        UUID requestId = Objects.requireNonNull(requestIds.get(), "requestId");
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(command);
        } catch (Exception exception) {
            throw new IllegalStateException("gateway_refund_request_serialization_failed", exception);
        }
        long timestamp = clock.instant().getEpochSecond();
        InternalRequestSigner.SignedHeaders signed =
                requestSigner.sign(timestamp, requestId, "POST", path, body);
        HttpRequest request = HttpRequest.newBuilder(gatewayBaseUri.resolve(path))
                .timeout(readTimeout)
                .header("Content-Type", "application/json")
                .header("X-Internal-Timestamp", signed.timestamp())
                .header("X-Internal-Request-Id", signed.requestId())
                .header("X-Internal-Content-Sha256", signed.contentSha256())
                .header("X-Internal-Signature", signed.signature())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("gateway_refund_request_interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("gateway_refund_request_failed", exception);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("gateway_refund_http_status:" + response.statusCode());
        }
        if (response.body() == null || response.body().length > MAXIMUM_RESPONSE_BYTES) {
            throw new IllegalStateException("gateway_refund_response_envelope_invalid");
        }

        SignedInternalResponse envelope;
        try {
            envelope = objectMapper.readerFor(SignedInternalResponse.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(response.body());
        } catch (Exception exception) {
            throw new IllegalStateException("gateway_refund_response_envelope_invalid", exception);
        }
        byte[] payload = decodePayload(envelope);
        boolean verified = envelope != null && requestSigner.verifyResponse(
                envelope.timestamp(),
                requestId,
                envelope.requestId() == null ? null : envelope.requestId().toString(),
                response.statusCode(),
                payload,
                envelope.contentSha256(),
                envelope.signature());
        if (!verified) {
            throw new IllegalStateException("gateway_refund_response_signature_invalid");
        }
        try {
            return objectMapper.readerFor(responseType)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("gateway_refund_response_payload_invalid", exception);
        }
    }

    private byte[] decodePayload(SignedInternalResponse envelope) {
        if (envelope == null || envelope.payloadBase64() == null) {
            throw new IllegalStateException("gateway_refund_response_envelope_invalid");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(envelope.payloadBase64());
            if (payload.length > MAXIMUM_RESPONSE_BYTES) {
                throw new IllegalStateException("gateway_refund_response_envelope_invalid");
            }
            return payload;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("gateway_refund_response_envelope_invalid", exception);
        }
    }

    private void validateSnapshot(RefundSnapshot snapshot, String businessNo) {
        if (snapshot == null
                || !businessNo.equals(snapshot.businessNo())
                || snapshot.packageInstanceId() == null
                || !("ACTIVE".equals(snapshot.originalStatus())
                        || "QUEUED".equals(snapshot.originalStatus()))
                || ("ACTIVE".equals(snapshot.originalStatus())
                        && snapshot.packageExpiresAt() == null)
                || ("QUEUED".equals(snapshot.originalStatus())
                        && snapshot.packageExpiresAt() != null)) {
            throw new IllegalStateException("gateway_refund_snapshot_schema_invalid");
        }
        if ("WAITING".equals(snapshot.status())) {
            if (snapshot.totalTokens() != null
                    || snapshot.usedTokens() != null
                    || snapshot.remainingTokens() != null
                    || snapshot.snapshotAt() != null) {
                throw new IllegalStateException("gateway_refund_waiting_snapshot_invalid");
            }
            return;
        }
        if (!"READY".equals(snapshot.status())
                || snapshot.totalTokens() == null
                || snapshot.usedTokens() == null
                || snapshot.remainingTokens() == null
                || snapshot.snapshotAt() == null) {
            throw new IllegalStateException("gateway_refund_snapshot_schema_invalid");
        }
        long total = snapshot.totalTokens();
        long used = snapshot.usedTokens();
        long remaining = snapshot.remainingTokens();
        if (total <= 0 || used < 0 || used > total || remaining < 0 || remaining != total - used) {
            throw new IllegalStateException("gateway_refund_snapshot_arithmetic_invalid");
        }
    }

    private void validateCommand(String businessNo, UUID grantRequestId) {
        if (businessNo == null
                || businessNo.isBlank()
                || businessNo.length() > 128
                || grantRequestId == null) {
            throw new IllegalArgumentException("invalid_gateway_refund_command");
        }
    }

    private Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public record PrepareRefundCommand(String businessNo, UUID grantRequestId) {
    }

    public record ReleaseRefundCommand(String businessNo, UUID grantRequestId) {
    }

    public record RefundSnapshot(
            String businessNo,
            UUID packageInstanceId,
            String status,
            String originalStatus,
            Long totalTokens,
            Long usedTokens,
            Long remainingTokens,
            Instant packageExpiresAt,
            Instant snapshotAt) {
    }

    public record ReleaseResult(String businessNo, String status) {
    }

    public record SignedInternalResponse(
            UUID requestId,
            long timestamp,
            String contentSha256,
            String signature,
            String payloadBase64) {
    }
}
