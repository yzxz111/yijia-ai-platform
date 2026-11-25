package com.yuzhihuihe.apiplatform.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class InternalResponseSignerTest {

    private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");
    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void signsExactPayloadBytesRequestIdTimestampAndStatus() throws Exception {
        byte[] payload = "{\"status\":\"READY\"}".getBytes(StandardCharsets.UTF_8);
        InternalResponseSigner signer = new InternalResponseSigner(
                "test-internal-secret", Clock.fixed(NOW, ZoneOffset.UTC));

        InternalResponseSigner.SignedInternalResponse response =
                signer.sign(REQUEST_ID, 200, payload);

        String payloadHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload));
        String canonical = NOW.getEpochSecond() + "\n" + REQUEST_ID + "\n200\n" + payloadHash;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "test-internal-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

        assertThat(response.requestId()).isEqualTo(REQUEST_ID);
        assertThat(response.timestamp()).isEqualTo(NOW.getEpochSecond());
        assertThat(response.contentSha256()).isEqualTo(payloadHash);
        assertThat(response.signature()).isEqualTo(HexFormat.of().formatHex(
                mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
        assertThat(Base64.getDecoder().decode(response.payloadBase64())).isEqualTo(payload);
    }

    @Test
    void onePayloadByteOrHttpStatusChangesTheSignature() {
        InternalResponseSigner signer = new InternalResponseSigner(
                "test-internal-secret", Clock.fixed(NOW, ZoneOffset.UTC));

        var original = signer.sign(REQUEST_ID, 200, "a".getBytes(StandardCharsets.UTF_8));
        var changedBody = signer.sign(REQUEST_ID, 200, "b".getBytes(StandardCharsets.UTF_8));
        var changedStatus = signer.sign(REQUEST_ID, 409, "a".getBytes(StandardCharsets.UTF_8));

        assertThat(changedBody.signature()).isNotEqualTo(original.signature());
        assertThat(changedStatus.signature()).isNotEqualTo(original.signature());
    }
}
