package com.yuzhihuihe.apiplatform.gateway.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class InternalHmacV2Test {

    private static final String SECRET = "test-internal-secret";
    private static final String PATH = "/internal/echo";
    private static final byte[] BODY = "{\"message\":\"hello\"}".getBytes(StandardCharsets.UTF_8);

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new EchoController())
                .webFilter(new InternalHmacWebFilter(SECRET, 300))
                .build();
    }

    @Test
    void verifiesExactBodyAndPreservesItForTheController() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        UUID requestId = UUID.randomUUID();

        signedRequest(PATH, timestamp, requestId, BODY, BODY)
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("{\"message\":\"hello\"}");
    }

    @Test
    void rejectsMissingRequestId() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        UUID requestId = UUID.randomUUID();
        String hash = sha256(BODY);

        client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Timestamp", timestamp)
                .header("X-Internal-Content-Sha256", hash)
                .header("X-Internal-Signature", signature(timestamp, requestId, PATH, hash))
                .bodyValue(BODY)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsStaleTimestamp() throws Exception {
        String timestamp = Long.toString(Instant.now().minusSeconds(301).getEpochSecond());
        signedRequest(PATH, timestamp, UUID.randomUUID(), BODY, BODY)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void rejectsMismatchedContentHash() throws Exception {
        signedRequest(PATH, Long.toString(Instant.now().getEpochSecond()), UUID.randomUUID(),
                        "different".getBytes(StandardCharsets.UTF_8), BODY)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void rejectsChangedBody() throws Exception {
        signedRequest(PATH, Long.toString(Instant.now().getEpochSecond()), UUID.randomUUID(),
                        BODY, "{\"message\":\"changed\"}".getBytes(StandardCharsets.UTF_8))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void rejectsReplayOfTheSameSignedRequestId() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        UUID requestId = UUID.randomUUID();

        signedRequest(PATH, timestamp, requestId, BODY, BODY)
                .exchange().expectStatus().isOk();
        signedRequest(PATH, timestamp, requestId, BODY, BODY)
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void rejectsMalformedHexSignature() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        UUID requestId = UUID.randomUUID();
        String hash = sha256(BODY);
        client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Timestamp", timestamp)
                .header("X-Internal-Request-Id", requestId.toString())
                .header("X-Internal-Content-Sha256", hash)
                .header("X-Internal-Signature", "not-hex")
                .bodyValue(BODY)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsSignatureCopiedToAnotherPath() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        UUID requestId = UUID.randomUUID();
        String hash = sha256(BODY);
        client.post().uri("/internal/other").contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Timestamp", timestamp)
                .header("X-Internal-Request-Id", requestId.toString())
                .header("X-Internal-Content-Sha256", hash)
                .header("X-Internal-Signature", signature(timestamp, requestId, PATH, hash))
                .bodyValue(BODY)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private WebTestClient.RequestHeadersSpec<?> signedRequest(
            String path, String timestamp, UUID requestId, byte[] signedBody, byte[] sentBody) throws Exception {
        String hash = sha256(signedBody);
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Timestamp", timestamp)
                .header("X-Internal-Request-Id", requestId.toString())
                .header("X-Internal-Content-Sha256", hash)
                .header("X-Internal-Signature", signature(timestamp, requestId, path, hash))
                .bodyValue(sentBody);
    }

    private String signature(String timestamp, UUID requestId, String path, String bodyHash) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String canonical = timestamp + "\n" + requestId + "\nPOST\n" + path + "\n" + bodyHash;
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private String sha256(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    @RestController
    static class EchoController {
        @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
        byte[] echo(@RequestBody byte[] body) {
            return body;
        }

        @PostMapping("/internal/other")
        byte[] other(@RequestBody byte[] body) {
            return body;
        }
    }
}
