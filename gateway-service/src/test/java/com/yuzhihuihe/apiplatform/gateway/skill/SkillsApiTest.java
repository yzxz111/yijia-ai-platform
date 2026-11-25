package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.yuzhihuihe.apiplatform.gateway.GatewayServiceApplication;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.apikey.ClientBootstrapRateLimiter;
import com.yuzhihuihe.apiplatform.gateway.apikey.ManagedApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.file.FileSafetyScanJob;
import com.yuzhihuihe.apiplatform.gateway.safety.AccountEnforcementService;
import com.yuzhihuihe.apiplatform.gateway.security.ConsoleTokenValidator;
import com.yuzhihuihe.apiplatform.gateway.security.TerminalDeviceValidator;
import com.yuzhihuihe.apiplatform.gateway.usage.UsageAnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Boots the real gateway HTTP stack (security chain, API-key filter, controller, config binding)
 * without external infrastructure, so the skill download endpoints are exercised end to end.
 */
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
                "gateway.economics.outbox.enabled=false",
                "content-safety.outbox.enabled=false",
                "gateway.skills.root=target/skills-api-test",
                // Seeds the prebuilt archive directory, so it pins the documented rollback source.
                "gateway.skills.source=filesystem"
        })
class SkillsApiTest {

    /** Only {@code sk-yzh-} bearers are API keys; any other bearer is a desktop platform JWT. */
    private static final String API_KEY = "sk-yzh-test-placeholder-not-a-real-secret";
    private static final Path SKILLS_ROOT = Path.of("target", "skills-api-test");

    @Autowired
    WebTestClient webTestClient;

    @MockitoBean
    ApiKeyService apiKeyService;

    @MockitoBean
    ManagedApiKeyService managedApiKeyService;

    @MockitoBean
    ClientBootstrapRateLimiter clientBootstrapRateLimiter;

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
    AccountEnforcementService accountEnforcementService;

    @MockitoBean
    ReactiveStringRedisTemplate redis;

    @MockitoBean
    ReactiveValueOperations<String, String> redisValues;

    @BeforeEach
    void setUp() throws IOException {
        resetSkillsRoot();
        when(apiKeyService.authenticate(any())).thenReturn(Mono.empty());
        when(apiKeyService.authenticate(API_KEY)).thenReturn(Mono.just(new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Set.of("text-basic"))));
        when(accountEnforcementService.current(any(), any())).thenReturn(Mono.just(
                new AccountEnforcementService.Enforcement("CLEARED", null, null)));
        when(redis.opsForValue()).thenReturn(redisValues);
        when(redisValues.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(Mono.just(true));
        when(consoleTokenValidator.validate(anyString())).thenReturn(Mono.just(true));
        when(jwtDecoder.decode("platform-jwt")).thenReturn(Mono.just(Jwt.withTokenValue("platform-jwt")
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .claim("tenant_id", UUID.randomUUID().toString())
                .claim("role", "USER")
                .issuedAt(java.time.Instant.now().minusSeconds(60))
                .expiresAt(java.time.Instant.now().plusSeconds(3600))
                .build()));
    }

    @Test
    void skillCatalogIsPublicButSkillDetailsRequireCredentials() {
        webTestClient.get().uri("/v1/skills").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data.length()").isEqualTo(0);

        webTestClient.get().uri("/v1/skills/demo-skill").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void listsAndDownloadsASkillThroughTheRealHttpStack() throws IOException {
        writeSkill();

        webTestClient.get().uri("/v1/skills")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("x-request-id")
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data[0].name").isEqualTo("demo-skill")
                .jsonPath("$.data[0].version").isEqualTo("1.2.3")
                .jsonPath("$.data[0].sha256").isNotEmpty()
                // The rollback source serves one immutable archive, so it never materializes the
                // per-file bodies of the package (C1).
                .jsonPath("$.data[0].fileCount").isEqualTo(0);

        webTestClient.get().uri("/v1/skills/demo-skill")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("skill")
                .jsonPath("$.name").isEqualTo("demo-skill")
                .jsonPath("$.version").isEqualTo("1.2.3")
                .jsonPath("$.packageFormat").isEqualTo("YCPS")
                .jsonPath("$.files.length()").isEqualTo(0);
    }

    @Test
    void acceptsAPlatformJwtForTheDesktopSkillCatalog() throws IOException {
        writeSkill();

        webTestClient.get().uri("/v1/skills")
                .headers(headers -> headers.setBearerAuth("platform-jwt"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].name").isEqualTo("demo-skill");
    }

    /**
     * The featured-scene cards read this vocabulary, so the desktop reaches it like the public
     * catalog (no session needed), and a category with no published package is left out rather than
     * rendered as an empty card.
     */
    @Test
    void servesTheCategoryVocabularyOnlyForPublishedPackages() {
        webTestClient.get().uri("/v1/skill-categories?kind=EXPERT")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("x-request-id")
                .expectBody()
                .jsonPath("$.length()").isEqualTo(0);
    }

    private static void writeSkill() throws IOException {
        Files.write(SKILLS_ROOT.resolve("demo-skill.ycps.zip"),
                YcpsTestPackages.skill("demo-skill", "1.2.3", "Demo Skill", "# Demo\n"));
    }

    private static void resetSkillsRoot() throws IOException {
        if (Files.exists(SKILLS_ROOT)) {
            try (Stream<Path> paths = Files.walk(SKILLS_ROOT)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.createDirectories(SKILLS_ROOT);
    }
}
