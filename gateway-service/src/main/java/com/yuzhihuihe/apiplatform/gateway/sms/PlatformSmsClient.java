package com.yuzhihuihe.apiplatform.gateway.sms;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

@Component
public final class PlatformSmsClient {

    private static final String PROOF_PATH = "/internal/platform/sms/proofs/consume";
    private static final String PACKAGE_EXPIRY_PATH = "/internal/platform/sms/signals/package-expiry";
    private static final String RATE_LIMIT_PATH = "/internal/platform/sms/signals/rate-limit";
    private static final int MAXIMUM_RESPONSE_BYTES = 1_048_576;
    private static final HexFormat HEX = HexFormat.of();

    private final ObjectMapper objectMapper;
    private final URI platformBaseUri;
    private final byte[] secret;
    private final Clock clock;
    private final Duration timeout;
    private final Supplier<UUID> requestIds;
    private final HttpClient httpClient;

    @Autowired
    public PlatformSmsClient(
            ObjectMapper objectMapper,
            @Value("${platform.sms.internal-base-url:http://platform-service:8080}") URI platformBaseUri,
            @Value("${security.internal.hmac-secret:}") String hmacSecret,
            @Value("${platform.sms.timeout:3s}") Duration timeout) {
        this(objectMapper, platformBaseUri, hmacSecret, Clock.systemUTC(), timeout, UUID::randomUUID);
    }

    PlatformSmsClient(
            ObjectMapper objectMapper,
            URI platformBaseUri,
            String hmacSecret,
            Clock clock,
            Duration timeout,
            Supplier<UUID> requestIds) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.platformBaseUri = Objects.requireNonNull(platformBaseUri, "platformBaseUri");
        this.secret = Objects.requireNonNull(hmacSecret, "hmacSecret")
                .getBytes(StandardCharsets.UTF_8);
        this.clock = Objects.requireNonNull(clock, "clock");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("platform_sms_timeout_required");
        }
        this.timeout = timeout;
        this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Mono<Void> consumeApiKeyResetProof(
            UUID tenantId, UUID userId, UUID operationId, String proof) {
        if (tenantId == null || userId == null || operationId == null
                || proof == null || proof.isBlank()) {
            return Mono.error(new IllegalArgumentException("invalid_sms_proof_scope"));
        }
        return post(PROOF_PATH,
                new OperationProofConsumeRequest(proof, tenantId, userId, operationId));
    }

    public Mono<Void> reportPackageExpiry(
            UUID tenantId, UUID userId, UUID packageId, java.time.Instant expiresAt) {
        if (tenantId == null || userId == null || packageId == null || expiresAt == null) {
            return Mono.error(new IllegalArgumentException("invalid_package_expiry_signal"));
        }
        return post(PACKAGE_EXPIRY_PATH,
                new PackageExpirySignal(tenantId, userId, packageId, expiresAt));
    }

    public Mono<Void> reportRateLimit(
            UUID tenantId, UUID userId, String modelCode, long cooldownBucket) {
        if (tenantId == null || userId == null || modelCode == null || modelCode.isBlank()
                || cooldownBucket < 0) {
            return Mono.error(new IllegalArgumentException("invalid_rate_limit_signal"));
        }
        return post(RATE_LIMIT_PATH,
                new RateLimitSignal(tenantId, userId, modelCode, cooldownBucket));
    }

    private Mono<Void> post(String path, Object payload) {
        return Mono.defer(() -> {
            if (secret.length == 0) {
                return Mono.error(new PlatformSmsClientException(
                        "platform_sms_signing_unavailable", null));
            }
            UUID requestId = Objects.requireNonNull(requestIds.get(), "requestId");
            byte[] body;
            try {
                body = objectMapper.writeValueAsBytes(payload);
            } catch (Exception error) {
                return Mono.error(new PlatformSmsClientException(
                        "platform_sms_request_serialization_failed", null));
            }
            long timestamp = clock.instant().getEpochSecond();
            String contentSha256 = sha256(body);
            String canonical = timestamp + "\n" + requestId + "\nPOST\n"
                    + path + "\n" + contentSha256;
            HttpRequest request = HttpRequest.newBuilder(platformBaseUri.resolve(path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Timestamp", Long.toString(timestamp))
                    .header("X-Internal-Request-Id", requestId.toString())
                    .header("X-Internal-Content-Sha256", contentSha256)
                    .header("X-Internal-Signature", hmac(canonical))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            return Mono.fromFuture(httpClient.sendAsync(request, limitedBodyHandler()))
                    .timeout(timeout)
                    .flatMap(response -> verifyResponse(requestId, response));
        });
    }

    private HttpResponse.BodyHandler<byte[]> limitedBodyHandler() {
        return responseInfo -> {
            long contentLength = responseInfo.headers()
                    .firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > MAXIMUM_RESPONSE_BYTES) {
                return new FailedBodySubscriber(responseEnvelopeInvalid());
            }
            return new LimitedByteArraySubscriber(MAXIMUM_RESPONSE_BYTES);
        };
    }

    private Mono<Void> verifyResponse(UUID requestId, HttpResponse<byte[]> response) {
        byte[] envelopeBytes = response.body();
        if (envelopeBytes == null || envelopeBytes.length > MAXIMUM_RESPONSE_BYTES) {
            return Mono.error(new PlatformSmsClientException(
                    "platform_sms_response_envelope_invalid", null));
        }
        SignedInternalResponse envelope;
        byte[] payload;
        try {
            envelope = objectMapper.readerFor(SignedInternalResponse.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(envelopeBytes);
            payload = Base64.getDecoder().decode(envelope.payloadBase64());
        } catch (Exception error) {
            return Mono.error(new PlatformSmsClientException(
                    "platform_sms_response_envelope_invalid", null));
        }
        if (payload.length > MAXIMUM_RESPONSE_BYTES
                || !verifySignedResponse(requestId, response.statusCode(), envelope, payload)) {
            return Mono.error(new PlatformSmsClientException(
                    "platform_sms_response_signature_invalid", null));
        }
        if (response.statusCode() != 200) {
            return Mono.error(new PlatformSmsClientException(
                    "platform_sms_http_status", response.statusCode()));
        }
        return Mono.empty();
    }

    private boolean verifySignedResponse(
            UUID requestId, int status, SignedInternalResponse envelope, byte[] payload) {
        if (envelope == null || envelope.requestId() == null
                || !MessageDigest.isEqual(
                        requestId.toString().getBytes(StandardCharsets.UTF_8),
                        envelope.requestId().toString().getBytes(StandardCharsets.UTF_8))
                || !fresh(envelope.timestamp()) || !lowercaseHash(envelope.contentSha256())
                || !lowercaseHash(envelope.signature())) {
            return false;
        }
        String actualHash = sha256(payload);
        if (!constantTimeHex(actualHash, envelope.contentSha256())) {
            return false;
        }
        String canonical = envelope.timestamp() + "\n" + requestId + "\n"
                + status + "\n" + envelope.contentSha256();
        return constantTimeHex(hmac(canonical), envelope.signature());
    }

    private boolean fresh(long timestamp) {
        try {
            return Duration.between(
                    java.time.Instant.ofEpochSecond(timestamp), clock.instant()).abs()
                    .compareTo(Duration.ofMinutes(5)) <= 0;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private String sha256(byte[] value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception error) {
            throw new IllegalStateException("internal_sha256_unavailable", error);
        }
    }

    private String hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("internal_hmac_unavailable", error);
        }
    }

    private boolean lowercaseHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private boolean constantTimeHex(String expected, String actual) {
        try {
            return MessageDigest.isEqual(HEX.parseHex(expected), HEX.parseHex(actual));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static PlatformSmsClientException responseEnvelopeInvalid() {
        return new PlatformSmsClientException(
                "platform_sms_response_envelope_invalid", null);
    }

    private static final class FailedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {

        private final CompletableFuture<byte[]> body = new CompletableFuture<>();

        private FailedBodySubscriber(RuntimeException error) {
            body.completeExceptionally(error);
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            // 响应体在发出请求量信号之前就已根据响应头被拒绝。
        }

        @Override
        public void onError(Throwable throwable) {
            // 保留已经通过 getBody() 暴露出去的大小上限错误。
        }

        @Override
        public void onComplete() {
            // 保留已经通过 getBody() 暴露出去的大小上限错误。
        }
    }

    private static final class LimitedByteArraySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {

        private static final int COPY_BUFFER_SIZE = 8_192;

        private final int maximumBytes;
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private final byte[] copyBuffer = new byte[COPY_BUFFER_SIZE];
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean terminated;

        private LimitedByteArraySubscriber(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (terminated) {
                return;
            }
            long batchBytes = 0;
            for (ByteBuffer buffer : buffers) {
                batchBytes += buffer.remaining();
                if (batchBytes > maximumBytes) {
                    reject();
                    return;
                }
            }
            if (received.size() > maximumBytes - batchBytes) {
                reject();
                return;
            }
            for (ByteBuffer buffer : buffers) {
                while (buffer.hasRemaining()) {
                    int copied = Math.min(buffer.remaining(), copyBuffer.length);
                    buffer.get(copyBuffer, 0, copied);
                    received.write(copyBuffer, 0, copied);
                }
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            if (!terminated) {
                terminated = true;
                body.completeExceptionally(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (!terminated) {
                terminated = true;
                body.complete(received.toByteArray());
            }
        }

        private void reject() {
            terminated = true;
            subscription.cancel();
            body.completeExceptionally(responseEnvelopeInvalid());
        }
    }

    public static final class PlatformSmsClientException extends RuntimeException {
        private final Integer httpStatus;

        public PlatformSmsClientException(String message, Integer httpStatus) {
            super(message);
            this.httpStatus = httpStatus;
        }

        public Integer httpStatus() {
            return httpStatus;
        }
    }

    private record OperationProofConsumeRequest(
            String proof, UUID tenantId, UUID userId, UUID operationId) {
    }

    private record PackageExpirySignal(
            UUID tenantId, UUID userId, UUID packageId, java.time.Instant expiresAt) {
    }

    private record RateLimitSignal(
            UUID tenantId, UUID userId, String modelCode, long cooldownBucket) {
    }

    private record SignedInternalResponse(
            UUID requestId,
            long timestamp,
            String contentSha256,
            String signature,
            String payloadBase64) {
    }
}
