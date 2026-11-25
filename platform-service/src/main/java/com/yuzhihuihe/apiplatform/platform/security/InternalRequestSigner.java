package com.yuzhihuihe.apiplatform.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class InternalRequestSigner {

    private static final Duration ALLOWED_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] secret;
    private final Clock clock;

    @Autowired
    public InternalRequestSigner(@Value("${security.internal.hmac-secret:}") String secret) {
        this(secret, Clock.systemUTC());
    }

    public InternalRequestSigner(String secret, Clock clock) {
        this.secret = Objects.requireNonNull(secret, "secret").getBytes(StandardCharsets.UTF_8);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SignedHeaders sign(long timestamp, UUID requestId, String method, String path, byte[] body) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        String bodyHash = sha256Hex(body);
        String canonical = timestamp + "\n" + requestId + "\n"
                + method.toUpperCase(Locale.ROOT) + "\n" + path + "\n" + bodyHash;
        return new SignedHeaders(
                Long.toString(timestamp), requestId.toString(), bodyHash, hmacHex(canonical));
    }

    public boolean verifyResponse(
            long timestamp,
            UUID expectedRequestId,
            String actualRequestId,
            int httpStatus,
            byte[] payload,
            String contentSha256,
            String signature) {
        if (secret.length == 0
                || !fresh(timestamp)
                || expectedRequestId == null
                || actualRequestId == null
                || !constantTimeEquals(expectedRequestId.toString(), actualRequestId)
                || !lowercaseSha256(contentSha256)
                || !lowercaseSha256(signature)) {
            return false;
        }
        String actualPayloadHash = sha256Hex(payload);
        if (!constantTimeHexEquals(actualPayloadHash, contentSha256)) {
            return false;
        }
        String canonical = timestamp + "\n" + expectedRequestId + "\n"
                + httpStatus + "\n" + contentSha256;
        return constantTimeHexEquals(hmacHex(canonical), signature);
    }

    public boolean verify(
            long timestamp, UUID requestId, String method, String path, byte[] body,
            String contentSha256, String signature) {
        if (secret.length == 0 || requestId == null || method == null || path == null
                || !fresh(timestamp) || !lowercaseSha256(contentSha256) || !lowercaseSha256(signature)) {
            return false;
        }
        String bodyHash = sha256Hex(body);
        if (!constantTimeHexEquals(bodyHash, contentSha256)) {
            return false;
        }
        String canonical = timestamp + "\n" + requestId + "\n"
                + method.toUpperCase(Locale.ROOT) + "\n" + path + "\n" + contentSha256;
        return constantTimeHexEquals(hmacHex(canonical), signature);
    }

    public SignedResponse signResponse(UUID requestId, int httpStatus, byte[] payload) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(payload, "payload");
        if (secret.length == 0 || httpStatus < 100 || httpStatus > 599) {
            throw new IllegalStateException("internal_response_signing_unavailable");
        }
        long timestamp = clock.instant().getEpochSecond();
        String contentSha256 = sha256Hex(payload);
        String canonical = timestamp + "\n" + requestId + "\n"
                + httpStatus + "\n" + contentSha256;
        return new SignedResponse(
                requestId, timestamp, contentSha256, hmacHex(canonical),
                Base64.getEncoder().encodeToString(payload));
    }

    private boolean fresh(long timestamp) {
        try {
            Duration age = Duration.between(Instant.ofEpochSecond(timestamp), clock.instant()).abs();
            return age.compareTo(ALLOWED_CLOCK_SKEW) <= 0;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String sha256Hex(byte[] value) {
        Objects.requireNonNull(value, "value");
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("internal_sha256_unavailable", exception);
        }
    }

    private String hmacHex(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("internal_hmac_unavailable", exception);
        }
    }

    private boolean lowercaseSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private boolean constantTimeHexEquals(String expected, String actual) {
        try {
            return MessageDigest.isEqual(HEX.parseHex(expected), HEX.parseHex(actual));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    public record SignedHeaders(
            String timestamp, String requestId, String contentSha256, String signature) {
    }

    public record SignedResponse(
            UUID requestId,
            long timestamp,
            String contentSha256,
            String signature,
            String payloadBase64) {
    }
}
