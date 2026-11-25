package com.yuzhihuihe.apiplatform.gateway.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class InternalResponseSigner {

    private static final int MAXIMUM_PAYLOAD_BYTES = 1_048_576;
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] secret;
    private final Clock clock;

    @Autowired
    public InternalResponseSigner(
            @Value("${security.internal.hmac-secret:}") String secret) {
        this(secret, Clock.systemUTC());
    }

    InternalResponseSigner(String secret, Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("security.internal.hmac-secret must be configured");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SignedInternalResponse sign(UUID requestId, int httpStatus, byte[] payload) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(payload, "payload");
        if (httpStatus < 100 || httpStatus > 599 || payload.length > MAXIMUM_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("invalid_internal_response");
        }
        try {
            long timestamp = clock.instant().getEpochSecond();
            String contentSha256 = HEX.formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
            String canonical = timestamp + "\n" + requestId + "\n"
                    + httpStatus + "\n" + contentSha256;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String signature = HEX.formatHex(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            return new SignedInternalResponse(
                    requestId, timestamp, contentSha256, signature,
                    Base64.getEncoder().encodeToString(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("internal_response_signing_failed", exception);
        }
    }

    public record SignedInternalResponse(
            UUID requestId,
            long timestamp,
            String contentSha256,
            String signature,
            String payloadBase64) {
    }
}
