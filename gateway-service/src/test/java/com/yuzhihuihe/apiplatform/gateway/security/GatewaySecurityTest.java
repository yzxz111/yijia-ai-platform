package com.yuzhihuihe.apiplatform.gateway.security;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.yuzhihuihe.apiplatform.gateway.usage.UsageAnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@AutoConfigureObservability(tracing = false)
@TestPropertySource(locations = {
        "file:src/main/resources/application.properties", "classpath:application.properties"
})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
                com.yuzhihuihe.apiplatform.gateway.GatewayServiceApplication.class,
                GatewaySecurityTest.TestEndpoints.class,
                GatewaySecurityTest.TestJwtConfiguration.class
        },
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                "security.api-key.pepper=test-api-key-pepper",
                "security.internal.hmac-secret=test-internal-secret",
                "spring.data.redis.host=127.0.0.1",
                "spring.data.redis.port=1",
                "content-safety.semantic.allow-mock=true",
                "content-safety.files.clamav-enabled=false",
                "gateway.multimodal.converter-base-url=https://converter.test.invalid",
                "gateway.multimodal.converter-auth-token=gateway-converter-test-token",
                "gateway.economics.outbox.enabled=false",
                "gateway.scheduling.enabled=false",
                "content-safety.outbox.enabled=false"
        })
@TestPropertySource(locations = "file:src/main/resources/application.properties")
class GatewaySecurityTest {

    private static final String ISSUER = "yuzhihuihe-platform";
    private static final KeyPair KEY_PAIR = generateKeyPair();

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    Environment environment;

    @MockitoBean
    DatabaseClient databaseClient;

    @MockitoBean
    TransactionalOperator transactionalOperator;

    @MockitoBean
    ConsoleTokenValidator consoleTokenValidator;

    @MockitoBean
    UsageAnalyticsService usageAnalyticsService;

    @MockitoBean
    ReactiveStringRedisTemplate redis;

    @SuppressWarnings("unchecked")
    private final ReactiveValueOperations<String, String> replayValues =
            mock(ReactiveValueOperations.class);

    @BeforeEach
    void acceptFreshInternalRequestIds() {
        when(redis.opsForValue()).thenReturn(replayValues);
        when(replayValues.setIfAbsent(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("1"),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class)))
                .thenReturn(Mono.just(true));
    }

    @Test
    void consoleGatewayRequiresAValidPlatformJwt() {
        webTestClient.get().uri("/console/gateway/profile")
                .exchange()
                .expectStatus().isUnauthorized();

        String currentToken = validatedToken("USER");
        var result = webTestClient.get().uri("/console/gateway/profile")
                .headers(headers -> headers.setBearerAuth(currentToken))
                .exchange()
                .expectBody()
                .returnResult();

        verify(consoleTokenValidator).validate(currentToken);
        org.assertj.core.api.Assertions.assertThat(result.getStatus()).isEqualTo(org.springframework.http.HttpStatus.OK);
        org.assertj.core.api.Assertions.assertThat(new String(result.getResponseBody(), StandardCharsets.UTF_8))
                .contains("user@example.com");
    }

    @Test
    void staleConsoleTokenIsRejectedAfterJwtAuthentication() {
        String staleToken = token("USER");
        when(consoleTokenValidator.validate(staleToken)).thenReturn(Mono.just(false));

        webTestClient.get().uri("/console/gateway/profile")
                .headers(headers -> headers.setBearerAuth(staleToken))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void consoleTokenValidationFailsClosedWhenPlatformIsUnavailable() {
        String token = token("USER");
        when(consoleTokenValidator.validate(token))
                .thenReturn(Mono.error(new IllegalStateException("platform unavailable")));

        webTestClient.get().uri("/console/gateway/profile")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void consoleTokenValidationFailsClosedWhenValidatorCompletesEmpty() {
        String token = token("USER");
        when(consoleTokenValidator.validate(token)).thenReturn(Mono.empty());

        webTestClient.get().uri("/console/gateway/profile")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void gatewayAdminRouteRejectsUserRole() {
        webTestClient.get().uri("/console/gateway/admin/health")
                .headers(headers -> headers.setBearerAuth(validatedToken("USER")))
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.get().uri("/console/gateway/admin/health")
                .headers(headers -> headers.setBearerAuth(validatedToken("ADMIN")))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void gatewayAdminAnalyticsRouteRejectsUserAndAllowsAdmin() {
        when(usageAnalyticsService.adminAnalytics(30)).thenReturn(Mono.empty());

        webTestClient.get().uri("/console/gateway/admin/analytics?days=30")
                .headers(headers -> headers.setBearerAuth(validatedToken("USER")))
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.get().uri("/console/gateway/admin/analytics?days=30")
                .headers(headers -> headers.setBearerAuth(validatedToken("ADMIN")))
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * 桌面端登录后靠 quota / usage / client 这几条链路回显套餐与模型。管理员也会用桌面端，
     * 而网关没有角色层级（ROLE_ADMIN 不蕴含 ROLE_USER），只放 USER 会让管理员静默拿到 403，
     * 表现为「登录成功但套餐/模型空白」。这组路径现在与 platform-service 对齐，用 hasAnyRole。
     */
    @Test
    void desktopPackageRoutesAcceptBothUserAndAdminRoles() {
        webTestClient.get().uri("/console/gateway/usage/probe")
                .headers(headers -> headers.setBearerAuth(validatedToken("USER")))
                .exchange()
                .expectStatus().isOk();
        webTestClient.get().uri("/console/gateway/usage/probe")
                .headers(headers -> headers.setBearerAuth(validatedToken("ADMIN")))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void administratorCannotMutateUserApiKeys() {
        String administrator = validatedToken("ADMIN");
        webTestClient.post().uri("/console/gateway/api-keys")
                .headers(headers -> headers.setBearerAuth(administrator))
                .exchange()
                .expectStatus().isForbidden();
        webTestClient.post().uri("/console/gateway/api-keys/key-id/reset")
                .headers(headers -> headers.setBearerAuth(administrator))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void actuatorHealthProbesArePublic() {
        var health = webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectBody()
                .returnResult();
        org.assertj.core.api.Assertions.assertThat(health.getStatus().value()).isEqualTo(503);
        org.assertj.core.api.Assertions.assertThat(new String(health.getResponseBody(), StandardCharsets.UTF_8))
                .contains("\"status\":\"DOWN\"");
        webTestClient.get().uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk();
        webTestClient.get().uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void actuatorPrometheusRequiresAdminJwt() {
        webTestClient.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/actuator/prometheus")
                .headers(headers -> headers.setBearerAuth(token("USER")))
                .exchange()
                .expectStatus().isForbidden();
        webTestClient.get().uri("/actuator/prometheus")
                .headers(headers -> headers.setBearerAuth(token("ADMIN")))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/plain")
                .expectBody(String.class).value(body ->
                        org.assertj.core.api.Assertions.assertThat(body)
                                .contains("jvm_info"));
    }

    @Test
    void consoleLoggingUsesJsonStructure() {
        org.assertj.core.api.Assertions.assertThat(
                        environment.getProperty("logging.structured.format.console"))
                .isEqualTo("logstash");
        org.assertj.core.api.Assertions.assertThat(
                        environment.getProperty("gateway.console-token-validation.uri"))
                .isEqualTo("http://platform-service:8080/console/platform/auth/validate");
        org.assertj.core.api.Assertions.assertThat(
                        environment.getProperty("gateway.console-token-validation.timeout"))
                .isEqualTo("1s");
        org.assertj.core.api.Assertions.assertThat(
                        environment.getProperty("spring.data.redis.timeout"))
                .isEqualTo("1s");
        org.assertj.core.api.Assertions.assertThat(
                        environment.getProperty("spring.data.redis.connect-timeout"))
                .isEqualTo("1s");
    }

    @Test
    void v1DoesNotAcceptConsoleJwtBeforeApiKeyAuthentication() {
        webTestClient.get().uri("/v1/test")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/v1/test")
                .headers(headers -> headers.setBearerAuth(token("USER")))
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/v1/test")
                .headers(headers -> headers.setBearerAuth("sk-yzh-placeholder"))
                .exchange()
                .expectStatus().isUnauthorized();
        verifyNoInteractions(consoleTokenValidator);
    }

    @Test
    void internalRouteRequiresFreshValidHmac() throws Exception {
        webTestClient.get().uri("/internal/test")
                .exchange()
                .expectStatus().isUnauthorized();

        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String legacySignature = legacyInternalSignature(timestamp, "GET", "/internal/test");
        webTestClient.get().uri("/internal/test")
                .header("X-Internal-Timestamp", timestamp)
                .header("X-Internal-Signature", legacySignature)
                .exchange()
                .expectStatus().isUnauthorized();

        UUID requestId = UUID.randomUUID();
        String contentSha256 = sha256(new byte[0]);
        webTestClient.get().uri("/internal/test")
                .header("X-Internal-Timestamp", timestamp)
                .header("X-Internal-Request-Id", requestId.toString())
                .header("X-Internal-Content-Sha256", contentSha256)
                .header("X-Internal-Signature", internalSignature(
                        timestamp, requestId, "GET", "/internal/test", contentSha256))
                .exchange()
                .expectStatus().isOk();
    }

    private String internalSignature(
            String timestamp, UUID requestId, String method, String path, String contentSha256)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-internal-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(
                (timestamp + "\n" + requestId + "\n" + method + "\n" + path + "\n" + contentSha256)
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private String legacyInternalSignature(String timestamp, String method, String path) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-internal-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(
                (timestamp + "\n" + method + "\n" + path).getBytes(StandardCharsets.UTF_8)));
    }

    private String sha256(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    private String token(String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .id(UUID.randomUUID().toString())
                .claim("tenant_id", UUID.randomUUID().toString())
                .claim("role", role)
                .claim("email", "user@example.com")
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build(), claims)).getTokenValue();
    }

    private String validatedToken(String role) {
        String token = token(role);
        when(consoleTokenValidator.validate(token)).thenReturn(Mono.just(true));
        return token;
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration
    static class TestJwtConfiguration {
        @Bean
        @Primary
        ReactiveJwtDecoder testJwtDecoder() {
            NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                    .withPublicKey((RSAPublicKey) KEY_PAIR.getPublic()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                    new JwtTimestampValidator(), new JwtIssuerValidator(ISSUER)));
            return decoder;
        }

        @Bean
        JwtEncoder testJwtEncoder() {
            RSAKey key = new RSAKey.Builder((RSAPublicKey) KEY_PAIR.getPublic())
                    .privateKey((RSAPrivateKey) KEY_PAIR.getPrivate())
                    .keyID("test-key")
                    .build();
            return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
        }
    }

    @RestController
    static class TestEndpoints {
        @GetMapping("/console/gateway/profile")
        Profile profile(@AuthenticationPrincipal Jwt jwt) {
            return new Profile(jwt.getClaimAsString("email"));
        }

        @GetMapping("/console/gateway/admin/health")
        String adminHealth() {
            return "ok";
        }

        @GetMapping("/console/gateway/usage/probe")
        String usageProbe() {
            return "ok";
        }

        @GetMapping("/v1/test")
        String v1() {
            return "ok";
        }

        @GetMapping("/internal/test")
        String internal() {
            return "ok";
        }

        record Profile(String email) {
        }
    }
}
