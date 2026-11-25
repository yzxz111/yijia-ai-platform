package com.yuzhihuihe.apiplatform.gateway.apikey;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.GatewayServiceApplication;
import com.yuzhihuihe.apiplatform.gateway.file.FileSafetyScanJob;
import com.yuzhihuihe.apiplatform.gateway.security.ConsoleTokenValidator;
import com.yuzhihuihe.apiplatform.gateway.security.TerminalDeviceValidator;
import com.yuzhihuihe.apiplatform.gateway.usage.UsageAnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@SpringBootTest(
        classes = GatewayServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                "security.api-key.pepper=test-api-key-pepper",
                "security.internal.hmac-secret=test-internal-secret",
                "gateway.client.api-base-url=http://8.138.108.191/v1",
                "gateway.economics.outbox.enabled=false",
                "content-safety.outbox.enabled=false"
        })
class ClientBootstrapControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("61000000-0000-4000-8000-000000000031");
    private static final UUID USER_ID = UUID.fromString("71000000-0000-4000-8000-000000000031");
    private static final UUID SESSION_ID = UUID.fromString("21000000-0000-4000-8000-000000000031");
    private static final UUID PACKAGE_ID = UUID.fromString("81000000-0000-4000-8000-000000000031");
    private static final String DEVICE_ID = "0123456789abcdef0123456789abcdef";
    private static final String API_KEY = "sk-yzh-0123456789abcdefghijklmnopqrstuvwxyzABCDEFG";

    @Autowired
    WebTestClient webTestClient;

    @MockitoBean
    ManagedApiKeyService managedApiKeyService;

    @MockitoBean
    ClientBootstrapRateLimiter rateLimiter;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @MockitoBean
    ConsoleTokenValidator consoleTokenValidator;

    @MockitoBean
    TerminalDeviceValidator terminalDeviceValidator;

    @MockitoBean
    DatabaseClient databaseClient;

    @MockitoBean
    TransactionalOperator transactions;

    @MockitoBean
    UsageAnalyticsService usageAnalyticsService;

    @MockitoBean
    FileSafetyScanJob fileSafetyScanJob;

    @MockitoBean
    ReactiveStringRedisTemplate redis;

    @MockitoBean
    ReactiveValueOperations<String, String> redisValues;

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            return Mono.just(jwt(token, !"non-terminal-token".equals(token)));
        });
        when(consoleTokenValidator.validate(anyString())).thenReturn(Mono.just(true));
        when(terminalDeviceValidator.validate(anyString(), anyString())).thenReturn(Mono.just(true));
        when(rateLimiter.acquire(any(UUID.class), any(UUID.class))).thenReturn(Mono.empty());
        when(managedApiKeyService.bootstrap(TENANT_ID, USER_ID, null)).thenReturn(Mono.just(
                new ManagedApiKeyService.ClientBootstrap(
                        API_KEY, PACKAGE_ID, "ADVANCED",
                        Instant.parse("2026-09-14T12:00:00Z"),
                        Set.of("text-basic", "multimodal-basic"),
                        "VIDEO_SECOND")));
        when(redis.opsForValue()).thenReturn(redisValues);
        when(redisValues.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(Mono.just(true));
    }

    @Test
    void returnsConfiguredBaseUrlSecretPackageAndNoStoreHeaders() {
        bootstrap("valid-token", DEVICE_ID)
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .expectHeader().valueEquals(HttpHeaders.PRAGMA, "no-cache")
                .expectBody()
                .jsonPath("$.apiBaseUrl").isEqualTo("http://8.138.108.191/v1")
                .jsonPath("$.apiKey").isEqualTo(API_KEY)
                .jsonPath("$.packageInstanceId").isEqualTo(PACKAGE_ID.toString())
                .jsonPath("$.packageCode").isEqualTo("ADVANCED")
                .jsonPath("$.packageExpiresAt").isEqualTo("2026-09-14T12:00:00Z")
                .jsonPath("$.allowedModels.length()").isEqualTo(2)
                .jsonPath("$.usageDimension").isEqualTo("VIDEO_SECOND");

        verify(rateLimiter).acquire(USER_ID, SESSION_ID);
        verify(managedApiKeyService).bootstrap(TENANT_ID, USER_ID, null);
        verify(terminalDeviceValidator).validate("valid-token", DEVICE_ID);
    }

    @Test
    void requiresDesktopSessionClaimsAndValidDeviceHeader() {
        bootstrap("non-terminal-token", DEVICE_ID)
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_credentials");
        bootstrap("valid-token", "raw-device-id")
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_credentials");
    }

    @Test
    void rejectsUnboundDeviceBeforeRateLimitAndManagedKeyLookup() {
        when(terminalDeviceValidator.validate("valid-token", DEVICE_ID))
                .thenReturn(Mono.just(false));

        bootstrap("valid-token", DEVICE_ID)
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_credentials")
                .jsonPath("$.apiKey").doesNotExist();

        verify(rateLimiter, never()).acquire(any(), any());
        verify(managedApiKeyService, never()).bootstrap(any(), any(), any());
    }

    @Test
    void failsClosedWhenTerminalDeviceValidationIsUnavailable() {
        when(terminalDeviceValidator.validate("valid-token", DEVICE_ID))
                .thenReturn(Mono.error(new IllegalStateException("platform unavailable")));

        bootstrap("valid-token", DEVICE_ID)
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_credentials");

        verify(rateLimiter, never()).acquire(any(), any());
        verify(managedApiKeyService, never()).bootstrap(any(), any(), any());
    }

    @Test
    void failsClosedWhenTerminalDeviceValidationReturnsNoDecision() {
        when(terminalDeviceValidator.validate("valid-token", DEVICE_ID)).thenReturn(Mono.empty());

        bootstrap("valid-token", DEVICE_ID)
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_credentials");

        verify(rateLimiter, never()).acquire(any(), any());
        verify(managedApiKeyService, never()).bootstrap(any(), any(), any());
    }

    @Test
    void mapsPackageAndEncryptionErrorsWithoutSecret() {
        when(managedApiKeyService.bootstrap(TENANT_ID, USER_ID, null)).thenReturn(Mono.error(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "active_package_required")));

        bootstrap("valid-token", DEVICE_ID)
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("active_package_required")
                .jsonPath("$.apiKey").doesNotExist();

        when(managedApiKeyService.bootstrap(TENANT_ID, USER_ID, null)).thenReturn(Mono.error(
                new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "credential_encryption_unavailable")));
        bootstrap("valid-token", DEVICE_ID)
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("credential_encryption_unavailable")
                .jsonPath("$.apiKey").doesNotExist();
    }

    @Test
    void preservesRetryAfterForBootstrapRateLimitResponses() {
        HttpHeaders rateLimitHeaders = new HttpHeaders();
        rateLimitHeaders.set(HttpHeaders.RETRY_AFTER, "60");
        ResponseStatusException rateLimited = new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "client_bootstrap_rate_limited") {
            @Override
            public HttpHeaders getHeaders() {
                return rateLimitHeaders;
            }
        };
        when(rateLimiter.acquire(USER_ID, SESSION_ID)).thenReturn(Mono.error(rateLimited));

        bootstrap("valid-token", DEVICE_ID)
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "60")
                .expectBody()
                .jsonPath("$.code").isEqualTo("client_bootstrap_rate_limited")
                .jsonPath("$.apiKey").doesNotExist();

        verify(managedApiKeyService, never()).bootstrap(any(), any(), any());
    }

    @Test
    void replacedConsoleTokenIsRejectedBeforeBootstrapService() {
        when(consoleTokenValidator.validate("stale-token")).thenReturn(Mono.just(false));

        bootstrap("stale-token", DEVICE_ID).expectStatus().isUnauthorized();

        verify(managedApiKeyService, never()).bootstrap(any(), any(), any());
        verify(rateLimiter, never()).acquire(any(), any());
    }

    @Test
    void forwardsTheRequestedUsageDimensionToTheService() {
        when(managedApiKeyService.bootstrap(TENANT_ID, USER_ID, "VIDEO_SECOND"))
                .thenReturn(Mono.just(new ManagedApiKeyService.ClientBootstrap(
                        API_KEY, PACKAGE_ID, "VIDEO_STARTER",
                        Instant.parse("2026-09-14T12:00:00Z"),
                        Set.of("video-basic"), "VIDEO_SECOND")));

        webTestClient.post().uri("/console/gateway/client/bootstrap?usageDimension=VIDEO_SECOND")
                .headers(headers -> headers.setBearerAuth("valid-token"))
                .header("X-Device-Id", DEVICE_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.packageCode").isEqualTo("VIDEO_STARTER")
                .jsonPath("$.usageDimension").isEqualTo("VIDEO_SECOND");

        verify(managedApiKeyService).bootstrap(TENANT_ID, USER_ID, "VIDEO_SECOND");
    }

    private WebTestClient.ResponseSpec bootstrap(String token, String deviceId) {
        return webTestClient.post().uri("/console/gateway/client/bootstrap")
                .headers(headers -> headers.setBearerAuth(token))
                .header("X-Device-Id", deviceId)
                .exchange();
    }

    private Jwt jwt(String token, boolean terminal) {
        Jwt.Builder builder = Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .claim("tenant_id", TENANT_ID.toString())
                .claim("role", "USER")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900));
        if (terminal) {
            builder.claim("session_id", SESSION_ID.toString())
                    .claim("client_type", "DESKTOP_WEB");
        }
        return builder.build();
    }
}
