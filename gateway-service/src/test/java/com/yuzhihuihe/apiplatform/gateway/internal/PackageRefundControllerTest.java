package com.yuzhihuihe.apiplatform.gateway.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.quota.PackageRefundService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.default-schema=gateway",
                "spring.flyway.schemas=gateway",
                "spring.data.redis.repositories.enabled=false",
                "security.api-key.pepper=test-api-key-pepper",
                "security.internal.hmac-secret=test-internal-secret",
                "quota.recovery.initial-delay=1h"
        })
class PackageRefundControllerTest {

    private static final String PREPARE_PATH = "/internal/refunds/prepare";
    private static final String RELEASE_PATH = "/internal/refunds/release";
    private static final UUID GRANT_ID =
            UUID.fromString("51000000-0000-4000-8000-000000000001");
    private static final UUID PACKAGE_ID =
            UUID.fromString("91000000-0000-4000-8000-000000000001");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    // Signed internal requests must exercise the real, isolated Redis replay fence.
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost()
                + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    PackageRefundService service;

    @Test
    void prepareAndReleaseReturnSignedExactPayloadEnvelopes() throws Exception {
        Instant snapshotAt = Instant.parse("2030-01-02T03:04:05Z");
        var prepare = new PackageRefundService.PrepareRefundCommand(
                "refund-hold:controller", GRANT_ID);
        when(service.prepare(prepare)).thenReturn(Mono.just(
                new PackageRefundService.RefundSnapshot(
                        prepare.businessNo(), PACKAGE_ID, "READY", "ACTIVE",
                        10_000L, 250L, 9_750L, snapshotAt.plusSeconds(3600), snapshotAt)));
        var release = new PackageRefundService.ReleaseRefundCommand(
                prepare.businessNo(), GRANT_ID);
        when(service.release(release)).thenReturn(Mono.just(
                new PackageRefundService.ReleaseResult(prepare.businessNo(), "RELEASED")));

        JsonNode prepared = signedPost(PREPARE_PATH, prepare).expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        JsonNode released = signedPost(RELEASE_PATH, release).expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();

        assertThat(decodedPayload(prepared).path("status").asText()).isEqualTo("READY");
        assertThat(decodedPayload(prepared).path("remainingTokens").asLong()).isEqualTo(9_750);
        assertThat(decodedPayload(released).path("status").asText()).isEqualTo("RELEASED");
        assertThat(prepared.path("signature").asText()).matches("[0-9a-f]{64}");
        assertThat(released.path("signature").asText()).matches("[0-9a-f]{64}");
    }

    @Test
    void internalRefundRoutesRejectUnsignedBodiesBeforeServiceInvocation() {
        webTestClient.post().uri(PREPARE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"businessNo\":\"refund-hold:unsigned\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private WebTestClient.ResponseSpec signedPost(String path, Object value) throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(value);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        UUID requestId = UUID.randomUUID();
        String contentSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(body));
        return webTestClient.post().uri(path)
                .header("X-Internal-Timestamp", timestamp)
                .header("X-Internal-Request-Id", requestId.toString())
                .header("X-Internal-Content-Sha256", contentSha256)
                .header("X-Internal-Signature", signature(
                        timestamp, requestId, path, contentSha256))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private String signature(
            String timestamp, UUID requestId, String path, String contentSha256)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "test-internal-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(
                (timestamp + "\n" + requestId + "\nPOST\n" + path + "\n" + contentSha256)
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode decodedPayload(JsonNode envelope) throws Exception {
        assertThat(envelope).isNotNull();
        return objectMapper.readTree(Base64.getDecoder().decode(
                envelope.path("payloadBase64").asText()));
    }
}
