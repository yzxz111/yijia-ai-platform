package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Boots the real gateway HTTP stack (security chain, console token filter, controller, config
 * binding) without external infrastructure, so the console skill marketplace endpoints are
 * exercised end to end for signed-in users and for anonymous callers.
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
                "gateway.skills.root=target/skills-console-test",
                // Seeds the prebuilt archive directory, so it pins the documented rollback source.
                "gateway.skills.source=filesystem"
        })
class SkillsConsoleControllerTest {

    private static final String CONSOLE_PATH = "/console/gateway/skills";
    /** Only {@code sk-yzh-} bearers are API keys; any other bearer is a desktop platform JWT. */
    private static final String API_KEY = "sk-yzh-test-placeholder-not-a-real-secret";
    private static final String USER_TOKEN = "console-user-token";
    private static final String ADMIN_TOKEN = "console-admin-token";
    private static final Path SKILLS_ROOT = Path.of("target", "skills-console-test");
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        when(jwtDecoder.decode(anyString()))
                .thenReturn(Mono.error(new BadJwtException("unrecognised token")));
        when(jwtDecoder.decode(USER_TOKEN)).thenReturn(Mono.just(consoleToken(USER_TOKEN, "USER")));
        when(jwtDecoder.decode(ADMIN_TOKEN)).thenReturn(Mono.just(consoleToken(ADMIN_TOKEN, "ADMIN")));
    }

    @Test
    void rejectsAnonymousCallersWithUnauthorized() {
        webTestClient.get().uri(CONSOLE_PATH).exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri(CONSOLE_PATH + "/demo-skill").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri(CONSOLE_PATH)
                .headers(headers -> headers.setBearerAuth("not-a-console-token"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsAuthenticatedCallersWithoutTheAdminRole() {
        when(jwtDecoder.decode("console-guest-token"))
                .thenReturn(Mono.just(consoleToken("console-guest-token", "GUEST")));

        webTestClient.get().uri(CONSOLE_PATH)
                .headers(headers -> headers.setBearerAuth("console-guest-token"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void rejectsRegularUsersBecauseTheConsoleCatalogIsAdminOnly() {
        webTestClient.get().uri(CONSOLE_PATH)
                .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                .exchange()
                .expectStatus().isForbidden();
        webTestClient.get().uri(CONSOLE_PATH + "/content-writer")
                .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void listsTheCatalogForAdministratorsWithThePackageDigests() throws IOException {
        writeSkill("content-writer", "0.1.0", "一键写作");

        webTestClient.get().uri(CONSOLE_PATH)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("x-request-id")
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].name").isEqualTo("content-writer")
                .jsonPath("$.data[0].title").isEqualTo("content-writer")
                .jsonPath("$.data[0].description").isEqualTo("一键写作")
                .jsonPath("$.data[0].version").isEqualTo("0.1.0")
                .jsonPath("$.data[0].status").isEqualTo("PUBLISHED")
                .jsonPath("$.data[0].packageFormat").isEqualTo("YCPS")
                .jsonPath("$.data[0].md5").isNotEmpty()
                .jsonPath("$.data[0].sha256").isNotEmpty()
                // The rollback source serves one immutable archive per code, so it never
                // materializes per-file metadata.
                .jsonPath("$.data[0].fileCount").isEqualTo(0)
                .jsonPath("$.data[0].files.length()").isEqualTo(0);
    }

    @Test
    void acceptsAdministrators() throws IOException {
        writeSkill("article-writer", "1.0.0", "文章写作");

        webTestClient.get().uri(CONSOLE_PATH)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].name").isEqualTo("article-writer");
    }

    @Test
    void degradesMissingOptionalMetadataToNullsAndEmptyArrays() throws IOException {
        writeSkill("example-skill", "1.0.0", "示例 Skill");

        webTestClient.get().uri(CONSOLE_PATH)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].category").doesNotExist()
                .jsonPath("$.data[0].icon").doesNotExist()
                .jsonPath("$.data[0].tags.length()").isEqualTo(0)
                .jsonPath("$.data[0].highlights.length()").isEqualTo(0);
    }

    @Test
    void servesTheSameJsonContractAsTheApiKeyEndpoint() throws IOException {
        writeSkill("content-writer", "0.1.0", "一键写作");

        JsonNode consoleList = json(webTestClient.get().uri(CONSOLE_PATH)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()
                .returnResult(byte[].class).getResponseBody().blockFirst());
        JsonNode apiKeyList = json(webTestClient.get().uri("/v1/skills")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange().expectStatus().isOk()
                .returnResult(byte[].class).getResponseBody().blockFirst());
        assertThat(consoleList).isEqualTo(apiKeyList);

        JsonNode consoleSkill = json(webTestClient.get().uri(CONSOLE_PATH + "/content-writer")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()
                .returnResult(byte[].class).getResponseBody().blockFirst());
        JsonNode apiKeySkill = json(webTestClient.get().uri("/v1/skills/content-writer")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange().expectStatus().isOk()
                .returnResult(byte[].class).getResponseBody().blockFirst());
        assertThat(consoleSkill.get("object").asText()).isEqualTo("skill");
        assertThat(consoleSkill.get("files")).isEqualTo(apiKeySkill.get("files"));
        assertThat(consoleSkill.get("packageFormat").asText()).isEqualTo("YCPS");
    }

    @Test
    void returnsThePackageDetailForOneSkill() throws IOException {
        writeSkill("article-writer", "1.2.3", "文章写作");

        webTestClient.get().uri(CONSOLE_PATH + "/article-writer")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("x-request-id")
                .expectBody()
                .jsonPath("$.object").isEqualTo("skill")
                .jsonPath("$.name").isEqualTo("article-writer")
                .jsonPath("$.version").isEqualTo("1.2.3")
                .jsonPath("$.description").isEqualTo("文章写作")
                .jsonPath("$.kind").isEqualTo("SKILL")
                .jsonPath("$.packageFormat").isEqualTo("YCPS")
                .jsonPath("$.downloadUrl").isEqualTo("/v1/skills/article-writer/downloads")
                .jsonPath("$.files.length()").isEqualTo(0);
    }

    @Test
    void reportsUnknownAndMalformedSkillNamesWithStableCodes() {
        webTestClient.get().uri(CONSOLE_PATH + "/missing-skill")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("x-request-id")
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("skill_not_found")
                .jsonPath("$.request_id").isNotEmpty();

        // `-bad-name` is a legal URL path segment but not a legal skill name, so the request
        // reaches the controller and must come back with the documented stable code.
        webTestClient.get().uri(CONSOLE_PATH + "/-bad-name")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_skill_name");
    }

    @Test
    void echoesTheInboundRequestIdHeaderForConsoleLogCorrelation() {
        webTestClient.get().uri(CONSOLE_PATH)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .header("x-request-id", "req_console_corr_1")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("x-request-id", "req_console_corr_1")
                .expectBody()
                .jsonPath("$.object").isEqualTo("list");
    }

    @Test
    void returnsAnEmptyCatalogWhenNoSkillIsPublished() {
        webTestClient.get().uri(CONSOLE_PATH)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data.length()").isEqualTo(0);
    }

    private static JsonNode json(byte[] body) throws IOException {
        return MAPPER.readTree(body);
    }

    private static Jwt consoleToken(String token, String role) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .claim("role", role)
                .claim("tenant_id", UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }

    private static void writeSkill(String name, String version, String description)
            throws IOException {
        Files.write(SKILLS_ROOT.resolve(name + ".ycps.zip"), YcpsTestPackages.skill(
                name, version, description, "# " + name + "\n"));
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
