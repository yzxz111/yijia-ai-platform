package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.GatewayServiceApplication;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.apikey.ClientBootstrapRateLimiter;
import com.yuzhihuihe.apiplatform.gateway.apikey.ManagedApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import com.yuzhihuihe.apiplatform.gateway.file.FileSafetyScanJob;
import com.yuzhihuihe.apiplatform.gateway.safety.AccountEnforcementService;
import com.yuzhihuihe.apiplatform.gateway.security.ConsoleTokenValidator;
import com.yuzhihuihe.apiplatform.gateway.security.TerminalDeviceValidator;
import com.yuzhihuihe.apiplatform.gateway.usage.UsageAnalyticsService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

/**
 * Full-stack contract test for the API-key skill catalog and download endpoints (design 5.2 C1-C6):
 * published-only visibility, client-version gating, session creation with idempotency and
 * throttling, {@code Range} resume with {@code 206}/{@code 416}/{@code If-Range}, the completion
 * report that drives the counters and cross-key isolation.
 */
@Testcontainers
@SpringBootTest(
        classes = GatewayServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.default-schema=gateway",
                "spring.flyway.schemas=gateway",
                "spring.data.redis.repositories.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                "security.api-key.pepper=skill-client-test-pepper",
                "security.internal.hmac-secret=skill-client-test-secret",
                "gateway.economics.outbox.enabled=false",
                "content-safety.outbox.enabled=false",
                "gateway.skills.source=database"
        })
@Import(SkillClientDownloadTest.ObjectStoreConfiguration.class)
class SkillClientDownloadTest {

    private static final String ADMIN_TOKEN = "skill-client-admin-token";
    private static final String API_KEY = "sk-yzh-client-test-key-000000000000000000";
    private static final String OTHER_KEY = "sk-yzh-client-test-key-111111111111111111";
    private static final String DEVICE = "0123456789abcdef0123456789abcdef";
    private static final String CLIENT_VERSION = "2026.6.30";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class ObjectStoreConfiguration {

        @Bean
        @Primary
        FileObjectStore skillClientTestObjectStore() {
            return new FakeSkillObjectStore();
        }
    }

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    FileObjectStore objectStore;

    @Autowired
    DatabaseClient databaseClient;

    @MockitoBean
    ApiKeyService apiKeyService;

    @MockitoBean
    ManagedApiKeyService managedApiKeyService;

    @MockitoBean
    ClientBootstrapRateLimiter clientBootstrapRateLimiter;

    @MockitoBean
    SkillDownloadRateLimiter rateLimiter;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @MockitoBean
    ConsoleTokenValidator consoleTokenValidator;

    @MockitoBean
    TerminalDeviceValidator terminalDeviceValidator;

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

    private UUID apiKeyId = UUID.randomUUID();
    private UUID otherApiKeyId = UUID.randomUUID();
    private UUID userId = UUID.randomUUID();
    private UUID otherUserId = UUID.randomUUID();
    private UUID tenantId = UUID.randomUUID();

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void setUp() throws SQLException {
        apiKeyId = UUID.randomUUID();
        otherApiKeyId = UUID.randomUUID();
        userId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        execute("TRUNCATE gateway.skill_download, gateway.skill_version, gateway.skill CASCADE");
        execute("TRUNCATE gateway.admin_operation_audit");
        when(jwtDecoder.decode(anyString()))
                .thenReturn(Mono.error(new BadJwtException("unrecognised token")));
        when(jwtDecoder.decode(ADMIN_TOKEN)).thenReturn(Mono.just(token(ADMIN_TOKEN)));
        when(consoleTokenValidator.validate(anyString())).thenReturn(Mono.just(true));
        when(apiKeyService.authenticate(any())).thenReturn(Mono.empty());
        when(apiKeyService.authenticate(API_KEY)).thenReturn(Mono.just(context(apiKeyId)));
        when(apiKeyService.authenticate(OTHER_KEY)).thenReturn(Mono.just(
                new ApiKeyService.ApiKeyContext(
                        otherApiKeyId, tenantId, otherUserId, UUID.randomUUID(), Set.of("text-basic"))));
        when(accountEnforcementService.current(any(), any())).thenReturn(Mono.just(
                new AccountEnforcementService.Enforcement("CLEARED", null, null)));
        when(redis.opsForValue()).thenReturn(redisValues);
        when(redisValues.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(rateLimiter.acquire(anyString(), any())).thenReturn(Mono.empty());
    }

    // ---------------------------------------------------------------- permissions

    @Test
    void allowsAnonymousCatalogPreviewButProtectsEveryDownloadEndpoint() {
        webTestClient.get().uri("/v1/skills").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list");

        webTestClient.get().uri("/v1/skills/demo-skill").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/v1/skills/demo-skill/versions").exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri("/v1/skills/demo-skill/downloads").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/v1/skills/downloads/dl_x").exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri("/v1/skills/downloads/dl_x/complete").exchange().expectStatus().isUnauthorized();

    }

    // ---------------------------------------------------------------- C1/C2/C3

    @Test
    void listsOnlyPublishedSkillsWithTheMarketplaceAndPackageFields() throws Exception {
        UUID published = seedPublishedSkill("article-writer", "文章写作", "2026.6.0");
        seedDraftSkill("draft-skill");
        UUID unpublished = seedPublishedSkill("content-writer", "内容生成助手", null);
        unpublish(unpublished);
        UUID deleted = seedPublishedSkill("ecommerce-ops", "电商运营助手", null);
        unpublish(deleted);
        delete(deleted);

        webTestClient.get().uri("/v1/skills")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("x-request-id")
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].name").isEqualTo("article-writer")
                .jsonPath("$.data[0].category").isEqualTo("文档与数据")
                .jsonPath("$.data[0].tags[0]").isEqualTo("报表")
                .jsonPath("$.data[0].icon").isEqualTo("📊")
                .jsonPath("$.data[0].latestVersion").isEqualTo("1.0.0")
                .jsonPath("$.data[0].minClientVersion").isEqualTo("2026.6.0")
                .jsonPath("$.data[0].sizeBytes").isNotEmpty()
                .jsonPath("$.data[0].md5").isNotEmpty()
                .jsonPath("$.data[0].sha256").isNotEmpty()
                .jsonPath("$.data[0].packageFormat").isEqualTo("YCPS")
                .jsonPath("$.data[0].ycpsVersion").isEqualTo("1.0.0")
                .jsonPath("$.data[0].kind").isEqualTo("SKILL")
                .jsonPath("$.data[0].revision").isEqualTo(1)
                .jsonPath("$.data[0].manifestSha256").isNotEmpty()
                .jsonPath("$.data[0].dependencyLockSha256").isNotEmpty()
                .jsonPath("$.data[0].downloadUrl").isEqualTo("/v1/skills/article-writer/downloads")
                .jsonPath("$.hasMore").isEqualTo(false)
                .jsonPath("$.next").doesNotExist();

        // C2 keeps the existing object/name/files fields and adds the package fields.
        webTestClient.get().uri("/v1/skills/article-writer")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header("X-Client-Version", CLIENT_VERSION)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("skill")
                .jsonPath("$.name").isEqualTo("article-writer")
                .jsonPath("$.category").isEqualTo("文档与数据")
                .jsonPath("$.version").isEqualTo("1.0.0")
                .jsonPath("$.md5").isNotEmpty()
                .jsonPath("$.packageFormat").isEqualTo("YCPS")
                .jsonPath("$.ycpsVersion").isEqualTo("1.0.0")
                .jsonPath("$.kind").isEqualTo("SKILL")
                .jsonPath("$.revision").isEqualTo(1)
                .jsonPath("$.manifestSha256").isNotEmpty()
                .jsonPath("$.dependencyLockSha256").isNotEmpty()
                .jsonPath("$.sizeBytes").isNotEmpty()
                .jsonPath("$.downloadUrl").isEqualTo("/v1/skills/article-writer/downloads");

        // C3 exposes the visible versions for update comparison.
        webTestClient.get().uri("/v1/skills/article-writer/versions")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header("X-Client-Version", CLIENT_VERSION)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].version").isEqualTo("1.0.0")
                .jsonPath("$.data[0].md5").isNotEmpty();

        assertThat(published).isNotNull();
    }

    @Test
    void optionallyListsAnUnpublishedSkillWithoutDownloadMetadata() throws Exception {
        UUID id = seedPublishedSkill("content-writer", "内容生成助手", "2099.1.0");
        unpublish(id);

        webTestClient.get().uri(uriBuilder -> uriBuilder
                        .path("/v1/skills")
                        .queryParam("include_unpublished", true)
                        .build())
                .header("X-Client-Version", "2026.1.0")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].name").isEqualTo("content-writer")
                .jsonPath("$.data[0].status").isEqualTo("UNPUBLISHED")
                .jsonPath("$.data[0].version").isEqualTo("1.0.0")
                .jsonPath("$.data[0].latestVersion").isEqualTo("1.0.0")
                .jsonPath("$.data[0].publishedVersion").doesNotExist()
                .jsonPath("$.data[0].sizeBytes").doesNotExist()
                .jsonPath("$.data[0].md5").doesNotExist()
                .jsonPath("$.data[0].sha256").doesNotExist()
                .jsonPath("$.data[0].downloadUrl").doesNotExist();

        webTestClient.post().uri("/v1/skills/content-writer/downloads")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header("X-Client-Version", CLIENT_VERSION)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("skill_not_found");
    }

    @Test
    void filtersTheCatalogByClientVersionAndRefusesTooOldClients() throws Exception {
        seedPublishedSkill("article-writer", "文章写作", "2026.6.0");

        // R-06: listed for a new enough client ...
        webTestClient.get().uri("/v1/skills")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header("X-Client-Version", "2026.6.30")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1);

        // ... filtered out for an older one ...
        webTestClient.get().uri("/v1/skills")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header("X-Client-Version", "2026.5.0")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(0);

        // ... and R-07: a missing X-Client-Version still lists, but never downloads.
        webTestClient.get().uri("/v1/skills")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1);

        webTestClient.post().uri("/v1/skills/article-writer/downloads")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isEqualTo(426)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("client_version_too_low");

        webTestClient.post().uri("/v1/skills/article-writer/downloads")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header("X-Client-Version", "2026.5.0")
                .exchange()
                .expectStatus().isEqualTo(426)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("client_version_too_low");

        webTestClient.get().uri("/v1/skills/article-writer")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header("X-Client-Version", "2026.5.0")
                .exchange()
                .expectStatus().isEqualTo(426);
    }

    // ---------------------------------------------------------------- C4/C5/C6

    @Test
    void acceptsVendorTypedYcpsArchiveAndRejectsLegacyArchiveAtIngest() throws Exception {
        UUID accepted = seedDraftSkill("ycps-upload");
        uploadVersion(accepted, "ycps-upload");
        assertThat(versionText("ycps-upload", "1.0.0", "package_format"))
                .isEqualTo("YCPS");

        UUID rejected = seedDraftSkill("legacy-upload");
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(legacyZip("legacy-upload")) {
            @Override
            public String getFilename() {
                return "legacy-upload.ycps.zip";
            }
        }).contentType(MediaType.parseMediaType("application/vnd.yuzhi.ycps+zip"));
        builder.part("version", "1.0.0");
        webTestClient.post().uri("/console/gateway/admin/skills/" + rejected + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("ycps_package_invalid");
    }

    @Test
    void createsADownloadSessionAndStreamsTheWholePackage() throws Exception {
        seedPublishedSkill("article-writer", "文章写作", "2026.6.0");

        JsonNode session = createSession("article-writer", null);
        assertThat(session.get("version").asText()).isEqualTo("1.0.0");
        assertThat(session.get("sizeBytes").asLong()).isPositive();
        assertThat(session.get("md5").asText()).hasSize(32);
        assertThat(session.get("sha256").asText()).hasSize(64);
        assertThat(session.get("revision").asInt()).isEqualTo(1);
        assertThat(session.get("kind").asText()).isEqualTo("SKILL");
        assertThat(session.get("packageFormat").asText()).isEqualTo("YCPS");
        assertThat(session.get("ycpsVersion").asText()).isEqualTo("1.0.0");
        assertThat(session.get("manifestSha256").asText()).hasSize(64);
        assertThat(session.get("dependencyLockSha256").asText()).hasSize(64);
        assertThat(session.get("dependencies").isArray()).isTrue();
        assertThat(session.get("url").asText())
                .isEqualTo("/v1/skills/downloads/" + session.get("downloadId").asText());
        assertThat(session.get("expiresAt").asLong()).isGreaterThan(Instant.now().getEpochSecond());

        byte[] packageBytes = packageBytes("article-writer");
        byte[] body = webTestClient.get().uri("/v1/skills/downloads/" + session.get("downloadId").asText())
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCEPT_RANGES, "bytes")
                .expectHeader().valueEquals("X-Skill-Code", "article-writer")
                .expectHeader().valueEquals("X-Skill-Version", "1.0.0")
                .expectHeader().valueEquals("X-Skill-Md5", session.get("md5").asText())
                .expectHeader().valueEquals(HttpHeaders.ETAG, "\"" + session.get("sha256").asText() + "\"")
                .expectHeader().valueEquals(HttpHeaders.CONTENT_LENGTH, String.valueOf(packageBytes.length))
                .expectHeader().contentType("application/vnd.yuzhi.ycps+zip")
                .expectHeader().valueEquals(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"article-writer-1.0.0.ycps.zip\"")
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isEqualTo(packageBytes);

        // served_bytes accounts for the full response.
        assertThat(downloadValue(session.get("downloadId").asText(), "served_bytes"))
                .isEqualTo(packageBytes.length);

        // C6 counts the download exactly once.
        webTestClient.post().uri("/v1/skills/downloads/" + session.get("downloadId").asText() + "/complete")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"status":"completed","bytes_received":%d,"md5_verified":true,
                         "sha256_verified":true,"installed":true,
                         "client_version":"2026.6.30","client_platform":"windows"}
                        """.formatted(packageBytes.length))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("COMPLETED")
                .jsonPath("$.counted").isEqualTo(true)
                .jsonPath("$.md5Verified").isEqualTo(true)
                .jsonPath("$.sha256Verified").isEqualTo(true)
                .jsonPath("$.installed").isEqualTo(true);

        assertThat(skillDownloadCount("article-writer")).isEqualTo(1L);
        assertThat(versionDownloadCount("article-writer")).isEqualTo(1L);

        // Idempotent replay of the same completion report must not double count.
        webTestClient.post().uri("/v1/skills/downloads/" + session.get("downloadId").asText() + "/complete")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"status":"completed","md5_verified":true,"sha256_verified":true,
                         "installed":true}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.counted").isEqualTo(false);
        assertThat(skillDownloadCount("article-writer")).isEqualTo(1L);
    }

    @Test
    void resumesWithRangeAndRejectsUnsatisfiableRanges() throws Exception {
        seedPublishedSkill("article-writer", "文章写作", "2026.6.0");
        JsonNode session = createSession("article-writer", null);
        String downloadId = session.get("downloadId").asText();
        String etag = "\"" + session.get("sha256").asText() + "\"";
        byte[] packageBytes = packageBytes("article-writer");
        long size = packageBytes.length;
        int from = 5;
        int to = 15;

        // 206 with Content-Range, ETag and the partial body.
        byte[] partial = webTestClient.get().uri("/v1/skills/downloads/" + downloadId)
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header(HttpHeaders.RANGE, "bytes=" + from + "-" + to)
                .exchange()
                .expectStatus().isEqualTo(206)
                .expectHeader().valueEquals(
                        HttpHeaders.CONTENT_RANGE, "bytes " + from + "-" + to + "/" + size)
                .expectHeader().valueEquals(HttpHeaders.ACCEPT_RANGES, "bytes")
                .expectHeader().valueEquals(HttpHeaders.ETAG, etag)
                .expectHeader().valueEquals(HttpHeaders.CONTENT_LENGTH, String.valueOf(to - from + 1))
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();

        byte[] expected = new byte[to - from + 1];
        System.arraycopy(packageBytes, from, expected, 0, expected.length);
        assertThat(partial).isEqualTo(expected);

        // The resume bookkeeping is observable on the session (design 6.6).
        assertThat(downloadValue(downloadId, "served_bytes")).isEqualTo(to - from + 1);
        assertThat(downloadValue(downloadId, "range_from")).isEqualTo((long) from);
        assertThat(downloadValue(downloadId, "range_to")).isEqualTo((long) to);

        // Open-ended range: bytes=<from>-
        byte[] openEnded = webTestClient.get().uri("/v1/skills/downloads/" + downloadId)
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header(HttpHeaders.RANGE, "bytes=" + from + "-")
                .exchange()
                .expectStatus().isEqualTo(206)
                .expectHeader().valueEquals(
                        HttpHeaders.CONTENT_RANGE, "bytes " + from + "-" + (size - 1) + "/" + size)
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        assertThat(openEnded).hasSize((int) (size - from));

        // Satisfiable-but-stale range: start beyond the end -> 416 + bytes */size (R-16).
        webTestClient.get().uri("/v1/skills/downloads/" + downloadId)
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header(HttpHeaders.RANGE, "bytes=" + (size + 10) + "-")
                .exchange()
                .expectStatus().isEqualTo(416)
                .expectHeader().valueEquals(HttpHeaders.CONTENT_RANGE, "bytes */" + size)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_range");

        // Multi-range is ignored by spec: full 200 (design 6.6).
        webTestClient.get().uri("/v1/skills/downloads/" + downloadId)
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header(HttpHeaders.RANGE, "bytes=0-1,4-5")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CONTENT_LENGTH, String.valueOf(size));

        // R-17: a stale If-Range (package replaced) degrades to a full 200.
        webTestClient.get().uri("/v1/skills/downloads/" + downloadId)
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header(HttpHeaders.RANGE, "bytes=" + from + "-")
                .header(HttpHeaders.IF_RANGE, "\"stale-etag\"")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CONTENT_LENGTH, String.valueOf(size));

        // A matching If-Range keeps the resume.
        webTestClient.get().uri("/v1/skills/downloads/" + downloadId)
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header(HttpHeaders.RANGE, "bytes=" + from + "-")
                .header(HttpHeaders.IF_RANGE, etag)
                .exchange()
                .expectStatus().isEqualTo(206);
    }

    @Test
    void countsOneDownloadPerDeviceAndVersionInsideTheWindow() throws Exception {
        seedPublishedSkill("article-writer", "文章写作", "2026.6.0");

        // MD5 alone is insufficient in YCPS: both digests and installation must be confirmed.
        JsonNode missingSha = createSession("article-writer", null);
        webTestClient.post().uri("/v1/skills/downloads/"
                        + missingSha.get("downloadId").asText() + "/complete")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"status":"completed","md5_verified":true,"sha256_verified":false,
                         "installed":true}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.counted").isEqualTo(false)
                .jsonPath("$.md5Verified").isEqualTo(true)
                .jsonPath("$.sha256Verified").isEqualTo(false);
        assertThat(skillDownloadCount("article-writer")).isZero();

        JsonNode first = createSession("article-writer", null);
        complete(first.get("downloadId").asText(), true, true);
        assertThat(skillDownloadCount("article-writer")).isEqualTo(1L);

        // A second session from the same device within 24h must not count again (design 6.5).
        JsonNode second = createSession("article-writer", null);
        webTestClient.post().uri("/v1/skills/downloads/" + second.get("downloadId").asText() + "/complete")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"status":"completed","md5_verified":true,"sha256_verified":true,
                         "installed":true}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.counted").isEqualTo(false);
        assertThat(skillDownloadCount("article-writer")).isEqualTo(1L);

        // A failed checksum never counts.
        JsonNode third = createSession("article-writer", null);
        webTestClient.post().uri("/v1/skills/downloads/" + third.get("downloadId").asText() + "/complete")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"status":"failed","md5_verified":false,"sha256_verified":false,
                         "installed":false,
                         "failure_code":"checksum_mismatch"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("FAILED")
                .jsonPath("$.counted").isEqualTo(false);
        assertThat(skillDownloadCount("article-writer")).isEqualTo(1L);
        assertThat(downloadText(third.get("downloadId").asText(), "failure_code"))
                .isEqualTo("checksum_mismatch");
    }

    @Test
    void replaysSessionCreationForTheSameIdempotencyKey() throws Exception {
        seedPublishedSkill("article-writer", "文章写作", "2026.6.0");

        JsonNode first = createSession("article-writer", "idem-key-1");
        JsonNode replay = createSession("article-writer", "idem-key-1");
        assertThat(replay.get("downloadId").asText())
                .isEqualTo(first.get("downloadId").asText());
        assertThat(downloadRows()).isEqualTo(1L);
    }

    @Test
    void isolatesDownloadSessionsPerApiKey() throws Exception {
        seedPublishedSkill("article-writer", "文章写作", "2026.6.0");
        JsonNode session = createSession("article-writer", null);

        // R-20: another key gets 404, exactly like a session that does not exist.
        webTestClient.get().uri("/v1/skills/downloads/" + session.get("downloadId").asText())
                .headers(headers -> headers.setBearerAuth(OTHER_KEY))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("download_not_found");

        webTestClient.post().uri("/v1/skills/downloads/" + session.get("downloadId").asText() + "/complete")
                .headers(headers -> headers.setBearerAuth(OTHER_KEY))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"status\":\"completed\",\"md5_verified\":true}")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("download_not_found");
    }

    @Test
    void invalidatesAnExistingSessionWhenTheSkillIsUnpublished() throws Exception {
        UUID id = seedPublishedSkill("article-writer", "文章写作", "2026.6.0");
        JsonNode session = createSession("article-writer", null);
        unpublish(id);

        // R-02: the catalog hides it and new sessions are refused ...
        webTestClient.get().uri("/v1/skills")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header("X-Client-Version", CLIENT_VERSION)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(0);

        webTestClient.post().uri("/v1/skills/article-writer/downloads")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header("X-Client-Version", CLIENT_VERSION)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("skill_not_found");

        // ... and R-04: the established session stops serving bytes.
        webTestClient.get().uri("/v1/skills/downloads/" + session.get("downloadId").asText())
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("skill_unpublished");
    }

    @Test
    void downloadsAnExactArchivedDependencyOnlyThroughItsAuthorizedParent() throws Exception {
        UUID dependencySkill = seedPublishedSkill("dependency-skill", "依赖技能", null);
        String dependencySha = versionText("dependency-skill", "1.0.0", "sha256");
        UUID dependencyVersion = versionId("dependency-skill", "1.0.0");

        UUID parentSkill = seedDraftSkill("parent-skill", "父技能", null);
        String dependencyLock = """
                [{"key":"dependency-binding","relation":"SKILL_BINDING","required":true,
                  "package":{"code":"dependency-skill","kind":"SKILL","version":"1.0.0",
                  "revision":1,"sha256":"%s"}}]
                """.formatted(dependencySha);
        uploadVersion(parentSkill, "parent-skill", "1.0.0", dependencyLock);
        publish(parentSkill);
        JsonNode parent = createSession("parent-skill", null);
        assertThat(parent.path("dependencies").size()).isEqualTo(1);
        assertThat(parent.at("/dependencies/0/package/sha256").asText())
                .isEqualTo(dependencySha);

        // Move the dependency's marketplace head to v2. The parent remains locked to sealed v1.
        uploadVersion(dependencySkill, "dependency-skill", "2.0.0", "[]");
        publishVersion(dependencySkill, versionId("dependency-skill", "2.0.0"));
        assertThat(versionText("dependency-skill", "1.0.0", "status"))
                .isEqualTo("ARCHIVED");

        String selector = """
                {"parentDownloadId":"%s","version":"1.0.0","revision":1,
                 "kind":"SKILL","sha256":"%s"}
                """.formatted(parent.path("downloadId").asText(), dependencySha);
        JsonNode exact = MAPPER.readTree(webTestClient.post()
                .uri("/v1/skills/dependency-skill/downloads")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header("X-Client-Version", CLIENT_VERSION)
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(selector)
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody());
        assertThat(exact.path("version").asText()).isEqualTo("1.0.0");
        assertThat(exact.path("revision").asInt()).isEqualTo(1);
        assertThat(exact.path("sha256").asText()).isEqualTo(dependencySha);
        assertThat(exact.path("packageFormat").asText()).isEqualTo("YCPS");
        webTestClient.get().uri(exact.path("url").asText())
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/vnd.yuzhi.ycps+zip");

        // The old revision is immutable while the published parent retains the exact edge.
        webTestClient.delete().uri("/console/gateway/admin/skills/" + dependencySkill
                        + "/versions/" + dependencyVersion)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_version_has_dependents");

        // A fabricated parent, a different credential, or a hash outside the parent's lock fails.
        String forgedParent = selector.replace(parent.path("downloadId").asText(),
                "dl_00000000000000000000000000000000");
        exactDependencyRequest(API_KEY, forgedParent).expectStatus().isNotFound();
        exactDependencyRequest(OTHER_KEY, selector).expectStatus().isNotFound();
        String wrongHash = selector.replace(dependencySha, "f".repeat(64));
        exactDependencyRequest(API_KEY, wrongHash)
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error.code")
                .isEqualTo("exact_dependency_not_in_parent");
    }

    @Test
    void reportsExpiredAndUnknownDownloadSessions() throws Exception {
        seedPublishedSkill("article-writer", "文章写作", "2026.6.0");
        JsonNode session = createSession("article-writer", null);
        UUID downloadId = SkillDownloadService.parseId(session.get("downloadId").asText());
        databaseClient.sql("UPDATE gateway.skill_download SET expires_at = now() - interval '1 minute'"
                        + " WHERE id = :id")
                .bind("id", downloadId)
                .fetch()
                .rowsUpdated()
                .block();

        webTestClient.get().uri("/v1/skills/downloads/" + session.get("downloadId").asText())
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isEqualTo(410)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("download_session_expired");

        webTestClient.get().uri("/v1/skills/downloads/dl_00000000000000000000000000000000")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("download_not_found");
    }

    @Test
    void throttlesSessionCreationAndFailsClosedWithoutRedis() throws Exception {
        seedPublishedSkill("article-writer", "文章写作", "2026.6.0");

        // Retry-After must survive the OpenAI error envelope (the limiter attaches it to the error).
        HttpHeaders retryAfter = new HttpHeaders();
        retryAfter.set(HttpHeaders.RETRY_AFTER, "60");
        ResponseStatusException limited =
                new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited") {
                    @Override
                    public HttpHeaders getHeaders() {
                        return retryAfter;
                    }
                };
        when(rateLimiter.acquire(anyString(), any())).thenReturn(Mono.error(limited));

        webTestClient.post().uri("/v1/skills/article-writer/downloads")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header("X-Client-Version", CLIENT_VERSION)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "60")
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("rate_limited")
                .jsonPath("$.error.type").isEqualTo("rate_limit_error");

        // R-34: Redis unavailable fails closed instead of letting everything through.
        when(rateLimiter.acquire(anyString(), any())).thenReturn(Mono.error(
                new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "skill_storage_unavailable")));

        webTestClient.post().uri("/v1/skills/article-writer/downloads")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header("X-Client-Version", CLIENT_VERSION)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("skill_storage_unavailable");
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode createSession(String code, String idempotencyKey) throws IOException {
        WebTestClient.RequestHeadersSpec<?> request = webTestClient.post()
                .uri("/v1/skills/" + code + "/downloads")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header("X-Client-Version", CLIENT_VERSION)
                .header("X-Device-Id", DEVICE);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return MAPPER.readTree(request.exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody());
    }

    private WebTestClient.ResponseSpec exactDependencyRequest(String apiKey, String selector) {
        return webTestClient.post().uri("/v1/skills/dependency-skill/downloads")
                .headers(headers -> headers.setBearerAuth(apiKey))
                .header("X-Client-Version", CLIENT_VERSION)
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(selector)
                .exchange();
    }

    private void complete(String downloadId, boolean md5Verified, boolean installed) {
        webTestClient.post().uri("/v1/skills/downloads/" + downloadId + "/complete")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"status":"completed","md5_verified":%s,"sha256_verified":%s,
                         "installed":%s}
                        """.formatted(md5Verified, md5Verified, installed))
                .exchange()
                .expectStatus().isOk();
    }

    private UUID seedPublishedSkill(String code, String name, String minClientVersion)
            throws IOException {
        UUID id = seedDraftSkill(code, name, minClientVersion);
        uploadVersion(id, code);
        publish(id);
        return id;
    }

    private UUID seedDraftSkill(String code) throws IOException {
        return seedDraftSkill(code, code, null);
    }

    private UUID seedDraftSkill(String code, String name, String minClientVersion) throws IOException {
        String body = """
                {"code":"%s","name":"%s","category":"文档与数据","tags":["报表","SQL"],
                 "summary":"把原始数据整理成结论","description":"详情","icon":"📊",
                 "minClientVersion":%s,"sortWeight":5}
                """.formatted(
                code,
                name,
                minClientVersion == null ? "null" : "\"" + minClientVersion + "\"");
        byte[] created = webTestClient.post().uri("/console/gateway/admin/skills")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        return UUID.fromString(MAPPER.readTree(created).get("id").asText());
    }

    private void uploadVersion(UUID id, String code) {
        uploadVersion(id, code, "1.0.0", "[]");
    }

    private void uploadVersion(UUID id, String code, String version, String dependencies) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(zipPackage(code, version, dependencies)) {
            @Override
            public String getFilename() {
                return "skill-package.ycps.zip";
            }
        }).contentType(MediaType.parseMediaType("application/vnd.yuzhi.ycps+zip"));
        builder.part("version", version);
        MultiValueMap<String, HttpEntity<?>> form = builder.build();
        webTestClient.post().uri("/console/gateway/admin/skills/" + id + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(form))
                .exchange()
                .expectStatus().isOk();
    }

    private void publish(UUID id) {
        webTestClient.post().uri("/console/gateway/admin/skills/" + id + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();
    }

    /** A8: promote one exact version to the marketplace head, as the admin console does. */
    private void publishVersion(UUID id, UUID versionId) {
        webTestClient.post().uri("/console/gateway/admin/skills/" + id
                        + "/versions/" + versionId + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();
    }

    private void unpublish(UUID id) {
        webTestClient.post().uri("/console/gateway/admin/skills/" + id + "/unpublish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();
    }

    private void delete(UUID id) {
        webTestClient.delete().uri("/console/gateway/admin/skills/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();
    }

    /** The bytes the fake object store holds for the online package of one skill. */
    private byte[] packageBytes(String code) {
        String objectKey = databaseClient.sql("""
                        SELECT version.object_key FROM gateway.skill_version version
                        JOIN gateway.skill skill ON skill.id = version.skill_id
                        WHERE skill.code = :code AND version.object_key IS NOT NULL
                        """)
                .bind("code", code)
                .map((row, metadata) -> row.get("object_key", String.class))
                .one()
                .block();
        return ((FakeSkillObjectStore) objectStore).content(objectKey);
    }

    private long skillDownloadCount(String code) {
        Long value = databaseClient.sql("SELECT download_count FROM gateway.skill WHERE code = :code")
                .bind("code", code)
                .map((row, metadata) -> row.get("download_count", Long.class))
                .one()
                .block();
        return value == null ? 0L : value;
    }

    private long versionDownloadCount(String code) {
        Long value = databaseClient.sql("""
                        SELECT version.download_count FROM gateway.skill_version version
                        JOIN gateway.skill skill ON skill.id = version.skill_id
                        WHERE skill.code = :code AND version.object_key IS NOT NULL
                        """)
                .bind("code", code)
                .map((row, metadata) -> row.get("download_count", Long.class))
                .one()
                .block();
        return value == null ? 0L : value;
    }

    private long downloadRows() {
        Long value = databaseClient.sql("SELECT count(*) FROM gateway.skill_download")
                .map((row, metadata) -> row.get(0, Long.class))
                .one()
                .block();
        return value == null ? 0L : value;
    }

    private long downloadValue(String externalId, String column) {
        UUID id = SkillDownloadService.parseId(externalId);
        return databaseClient.sql("SELECT " + column + " FROM gateway.skill_download WHERE id = :id")
                .bind("id", id)
                .map((row, metadata) -> row.get(column, Number.class))
                .one()
                .block()
                .longValue();
    }

    private String downloadText(String externalId, String column) {
        UUID id = SkillDownloadService.parseId(externalId);
        return databaseClient.sql("SELECT " + column + " FROM gateway.skill_download WHERE id = :id")
                .bind("id", id)
                .map((row, metadata) -> row.get(column, String.class))
                .one()
                .block();
    }

    private UUID versionId(String code, String version) {
        return databaseClient.sql("""
                        SELECT package.id FROM gateway.skill_version package
                        JOIN gateway.skill skill ON skill.id = package.skill_id
                        WHERE skill.code = :code AND package.version = :version
                        """)
                .bind("code", code)
                .bind("version", version)
                .map((row, metadata) -> row.get("id", UUID.class))
                .one().block();
    }

    private String versionText(String code, String version, String column) {
        return databaseClient.sql("SELECT package." + column + " FROM gateway.skill_version package "
                        + "JOIN gateway.skill skill ON skill.id = package.skill_id "
                        + "WHERE skill.code = :code AND package.version = :version")
                .bind("code", code)
                .bind("version", version)
                .map((row, metadata) -> row.get(column, String.class))
                .one().block();
    }

    private ApiKeyService.ApiKeyContext context(UUID keyId) {
        return new ApiKeyService.ApiKeyContext(
                keyId, tenantId, userId, UUID.randomUUID(), Set.of("text-basic"));
    }

    private static byte[] zipPackage(String code) {
        return zipPackage(code, "1.0.0", "[]");
    }

    private static byte[] zipPackage(String code, String version, String dependencies) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                writeEntry(zip, "plugin.json", """
                        {"$schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json",
                         "name":"%s","version":"%s","description":"Article writer",
                         "author":{"name":"Yuzhi Tests"},"license":"MIT",
                         "extensions":{"com.yuzhihuihe.ycps":{"ycpsVersion":"1.0.0",
                         "kind":"SKILL","entrySkill":"%s",
                         "manifest":"./com.yuzhihuihe.ycps/manifest.json","requiredFeatures":[]}}}
                        """.formatted(code, version, code));
                writeEntry(zip, "com.yuzhihuihe.ycps/manifest.json", """
                        {"$schema":"https://schemas.yuzhihuihe.com/ycps/1.0.0/skill-manifest.schema.json",
                         "dependencies":%s,"permissionRequests":{"tools":[],"network":[],
                         "filesystem":[]},"hostRequirements":{}}
                        """.formatted(dependencies));
                writeEntry(zip, "skills/" + code + "/SKILL.md", """
                        ---
                        name: %s
                        description: Article writer
                        ---

                        # %s
                        %s
                        """.formatted(code, code, "0123456789".repeat(8)));
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] legacyZip(String code) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                writeEntry(zip, "skill.json", "{\"name\":\"" + code
                        + "\",\"version\":\"1.0.0\"}");
                writeEntry(zip, "SKILL.md", "---\nname: " + code
                        + "\ndescription: legacy\n---\n\n# Legacy\n");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static Jwt token(String value) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(value)
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .claim("role", "ADMIN")
                .claim("tenant_id", UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .build();
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
