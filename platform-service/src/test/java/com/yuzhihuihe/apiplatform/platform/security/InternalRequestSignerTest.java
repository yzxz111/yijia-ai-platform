package com.yuzhihuihe.apiplatform.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class InternalRequestSignerTest {

    private static final String SECRET = "test-secret";
    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final long NOW = 1_722_470_400L;

    @Test
    void signsTimestampRequestIdMethodPathAndExactBodyHash() {
        InternalRequestSigner signer = signer();
        byte[] body = "{\"eventId\":\"e1\"}".getBytes(StandardCharsets.UTF_8);

        InternalRequestSigner.SignedHeaders signed = signer.sign(
                NOW, REQUEST_ID, "post", "/internal/events/package-granted", body);

        assertThat(signed.timestamp()).isEqualTo(Long.toString(NOW));
        assertThat(signed.requestId()).isEqualTo(REQUEST_ID.toString());
        assertThat(signed.contentSha256())
                .isEqualTo("e88b9f819e77d99a9d9244df066be599376c003ac0e34b05c5e87e0bfacc3cdc");
        assertThat(signed.signature())
                .isEqualTo("a58f16a80928150611f0080a03cbdd6aa6e48cb3e4d5e4ad898ff8a91104c542");
        assertThat(signer.sign(NOW, REQUEST_ID, "POST", "/internal/events/package-granted", body))
                .isEqualTo(signed);
        assertThat(signer.sign(
                        NOW, REQUEST_ID, "POST", "/internal/events/package-granted",
                        "{\"eventId\":\"e2\"}".getBytes(StandardCharsets.UTF_8)).signature())
                .isNotEqualTo(signed.signature());
    }

    @Test
    void verifiesOnlyFreshExactResponseEnvelope() throws Exception {
        InternalRequestSigner signer = signer();
        byte[] payload = "{\"status\":\"READY\"}".getBytes(StandardCharsets.UTF_8);
        String payloadHash = sha256(payload);
        String signature = responseSignature(NOW, REQUEST_ID, 200, payloadHash);

        assertThat(signer.verifyResponse(
                NOW, REQUEST_ID, REQUEST_ID.toString(), 200, payload, payloadHash, signature)).isTrue();
        assertThat(signer.verifyResponse(
                NOW - 301, REQUEST_ID, REQUEST_ID.toString(), 200, payload, payloadHash, signature)).isFalse();
        assertThat(signer.verifyResponse(
                NOW, REQUEST_ID, UUID.randomUUID().toString(), 200, payload, payloadHash, signature)).isFalse();
        assertThat(signer.verifyResponse(
                NOW, REQUEST_ID, REQUEST_ID.toString(), 201, payload, payloadHash, signature)).isFalse();
        assertThat(signer.verifyResponse(
                NOW, REQUEST_ID, REQUEST_ID.toString(), 200,
                "{\"status\":\"WAITING\"}".getBytes(StandardCharsets.UTF_8), payloadHash, signature)).isFalse();
        assertThat(signer.verifyResponse(
                NOW, REQUEST_ID, REQUEST_ID.toString(), 200, payload, "not-a-hash", signature)).isFalse();
        assertThat(signer.verifyResponse(
                NOW, REQUEST_ID, REQUEST_ID.toString(), 200, payload, payloadHash, "xyz")).isFalse();
    }

    private InternalRequestSigner signer() {
        return new InternalRequestSigner(
                SECRET, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
    }

    private String responseSignature(long timestamp, UUID requestId, int status, String payloadHash)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String canonical = timestamp + "\n" + requestId + "\n" + status + "\n" + payloadHash;
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private String sha256(byte[] payload) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
    }
}
