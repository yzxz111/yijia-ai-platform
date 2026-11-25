package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

/**
 * Full-stack contract test for the skill administration API (design 5.1 A1-A14): real HTTP stack
 * (security chain, multipart decoding, exception handler), real PostgreSQL, in-memory object store.
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
                "security.api-key.pepper=skill-admin-test-pepper",
                "security.internal.hmac-secret=skill-admin-test-secret",
                "gateway.economics.outbox.enabled=false",
                "content-safety.outbox.enabled=false",
                "gateway.skills.source=database"
        })
@Import(SkillAdminControllerTest.ObjectStoreConfiguration.class)
class SkillAdminControllerTest {

    private static final String ADMIN_TOKEN = "skill-admin-token";
    private static final String USER_TOKEN = "skill-user-token";
    private static final String API_KEY = "sk-yzh-example-image-test-key";
    private static final String BASE = "/console/gateway/admin/skills";
    private static final String NATIVE_IMPORT_BASE = "/console/gateway/admin/skill-source-imports";
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
        FileObjectStore skillTestObjectStore() {
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
    ClawhubRegistryDisplayNameClient clawhubDisplayNames;

    @MockitoBean
    RemoteSkillFetchService remoteSkillFetch;

    @MockitoBean
    AccountEnforcementService accountEnforcementService;

    @MockitoBean
    ReactiveStringRedisTemplate redis;

    @MockitoBean
    ReactiveValueOperations<String, String> redisValues;

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
        execute("TRUNCATE gateway.skill_download, gateway.skill_version, gateway.skill CASCADE");
        execute("TRUNCATE gateway.admin_operation_audit");
        when(jwtDecoder.decode(anyString()))
                .thenReturn(Mono.error(new BadJwtException("unrecognised token")));
        when(jwtDecoder.decode(ADMIN_TOKEN)).thenReturn(Mono.just(token(ADMIN_TOKEN, "ADMIN")));
        when(jwtDecoder.decode(USER_TOKEN)).thenReturn(Mono.just(token(USER_TOKEN, "USER")));
        when(consoleTokenValidator.validate(anyString())).thenReturn(Mono.just(true));
        when(redis.opsForValue()).thenReturn(redisValues);
        when(apiKeyService.authenticate(anyString())).thenReturn(Mono.empty());
        when(apiKeyService.authenticate(API_KEY)).thenReturn(Mono.just(
                new ApiKeyService.ApiKeyContext(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        Set.of("text-basic"))));
        when(accountEnforcementService.current(any(), any())).thenReturn(Mono.just(
                new AccountEnforcementService.Enforcement("CLEARED", null, null)));
        when(clawhubDisplayNames.displayName(any(), any())).thenReturn(Mono.empty());
    }

    // ---------------------------------------------------------------- permissions

    @Test
    void rejectsAnonymousCallersWithUnauthorized() {
        webTestClient.get().uri(BASE).exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri(BASE).exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri(BASE + "/" + UUID.randomUUID()).exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri(NATIVE_IMPORT_BASE + "/clawhub/" + UUID.randomUUID() + "/package")
                .exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/remote/fetch")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("url", "https://github.com/example/skill"))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void rejectsRegularUsersBecauseTheAdminApiIsAdminOnly() {
        webTestClient.get().uri(BASE)
                .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                .exchange()
                .expectStatus().isForbidden();
        webTestClient.post().uri(BASE)
                .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"code\":\"x\",\"name\":\"x\"}")
                .exchange()
                .expectStatus().isForbidden();
        webTestClient.get().uri(NATIVE_IMPORT_BASE + "/clawhub/" + UUID.randomUUID() + "/package")
                .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                .exchange()
                .expectStatus().isForbidden();
        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/remote/fetch")
                .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("url", "https://github.com/example/skill"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void adminNativeClawhubExportReturnsNotFoundWithoutAnAssociatedSourceRecord() throws Exception {
        webTestClient.get().uri(NATIVE_IMPORT_BASE + "/clawhub/" + UUID.randomUUID() + "/package")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("native_clawhub_source_not_found");
    }

    @Test
    void nativeClawhubVersionDownloadRequiresSourceIdToBelongToRequestedSkill() throws Exception {
        JsonNode first = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/commit")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeImportForm(
                        nativeClawhubZip("first"), "first.zip", "first-owner", "first-skill",
                        null, null)))
                .exchange()
                .expectStatus().isCreated()));
        JsonNode second = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/commit")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeImportForm(
                        nativeClawhubZip("second"), "second.zip", "second-owner", "second-skill",
                        null, null)))
                .exchange()
                .expectStatus().isCreated()));

        webTestClient.get()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + first.path("skillId").asText()
                        + "/versions/" + second.path("sourceId").asText() + "/download")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("native_clawhub_source_not_found");
    }

    @Test
    void importsNativeClawhubAsDraftAllowsOneTimeVersionCompletionAndRejectsYcpsPublish() throws Exception {
        byte[] archive = nativeClawhubZip();
        String owner = "a".repeat(64);
        MultiValueMap<String, HttpEntity<?>> previewForm = nativeImportForm(
                archive, "demo.zip", owner, "unversioned", null, null);
        JsonNode preview = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/preview")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(previewForm))
                .exchange()
                .expectStatus().isOk()));

        assertThat(preview.path("report").path("sourceFormat").asText()).isEqualTo("CLAWHUB_SKILL");
        assertThat(preview.path("report").path("status").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(preview.path("report").path("version").isNull()).isTrue();
        assertThat(preview.path("packageBase64").isNull()).isTrue();
        String sha256 = preview.path("report").path("sourceSha256").asText();

        JsonNode imported = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/commit")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeImportForm(
                        archive, "demo.zip", owner, "unversioned", null, sha256)))
                .exchange()
                .expectStatus().isCreated()));
        UUID id = UUID.fromString(imported.path("skillId").asText());
        UUID sourceId = UUID.fromString(imported.path("sourceId").asText());
        UUID sourceSkillId = databaseClient.sql("""
                        SELECT skill_id FROM gateway.native_clawhub_source WHERE id = :source_id
                        """)
                .bind("source_id", sourceId)
                .map((row, metadata) -> row.get("skill_id", UUID.class))
                .one()
                .block();
        assertThat(sourceSkillId).isEqualTo(id);

        byte[] draftDownload = webTestClient.get()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + id + "/package")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.parseMediaType("application/zip"))
                .expectHeader().valueEquals("X-Skill-Package-Format", "CLAWHUB_SKILL")
                .expectHeader().valueEquals("X-Skill-Sha256", sha256)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        assertThat(draftDownload).containsExactly(archive);

        assertThat(imported.path("status").asText()).isEqualTo("DRAFT");
        assertThat(imported.path("packageFormat").asText()).isEqualTo("CLAWHUB_SKILL");
        assertThat(imported.path("sourceSha256").asText()).isEqualTo(sha256);
        assertThat(imported.path("version").isNull()).isTrue();
        assertThat(imported.path("reviewRequired").asBoolean()).isTrue();
        JsonNode adminDetail = json(getBody(webTestClient.get().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        // 导入会现场扫描一次。有扫描器时是 PENDING/FAILED；CI/开发环境通常没有
        // ClamAV，此时代码**有意**把结果记为 PASSED（理由见
        // ClawhubSkillImportService.scanStatus：否则这些环境永远发不了原生包，
        // 而重扫只能从 PENDING/FAILED 出发，会形成没有出口的死循环）。
        // 因此这里不写死某个状态，而是断言**导入响应与技能详情一致**——
        // 这条跨端点一致性检查与扫描器是否存在无关，也正是接口该保证的事。
        assertThat(imported.path("scanStatus").asText())
                .isEqualTo(adminDetail.path("sourceScanStatus").asText());
        assertThat(adminDetail.path("tags").size()).isEqualTo(2);
        assertThat(adminDetail.path("tags").get(0).asText()).isEqualTo("ClawHub");
        assertThat(adminDetail.path("tags").get(1).asText()).isEqualTo(owner);
        String storedTagsJson = databaseClient.sql(
                        "SELECT to_json(tags)::text AS tags_json FROM gateway.skill WHERE id = :id")
                .bind("id", id)
                .map((row, metadata) -> row.get("tags_json", String.class))
                .one().block();
        JsonNode storedTags = MAPPER.readTree(storedTagsJson);
        assertThat(storedTags.size()).isEqualTo(2);
        assertThat(storedTags.get(0).asText()).isEqualTo("ClawHub");
        assertThat(storedTags.get(1).asText()).isEqualTo(owner);
        assertThat(adminDetail.path("sourceFormat").asText()).isEqualTo("CLAWHUB_SKILL");
        assertThat(adminDetail.path("sourceReviewRequired").asBoolean()).isTrue();
        assertThat(adminDetail.path("sourceVersion").isNull()).isTrue();
        // 与导入响应一致已在上文断言过；这里再钉住取值本身。
        // 没有 ClamAV 的环境里是有意记下的 PASSED（理由同上），有扫描器时是
        // PENDING/FAILED。三者都合法，但非法值——包括字段缺失导致的空串——
        // 仍然会让这条失败。
        assertThat(adminDetail.path("sourceScanStatus").asText())
                .isIn("PENDING", "FAILED", "PASSED");
        Map<String, Object> editedSkill = new LinkedHashMap<>();
        editedSkill.put("name", adminDetail.path("name").asText());
        for (String field : List.of("nameEn", "category", "summary", "summaryEn",
                "description", "descriptionEn", "icon", "minClientVersion")) {
            editedSkill.put(field, adminDetail.path(field).asText(null));
        }
        editedSkill.put("tags", List.of("自定义", owner));
        editedSkill.put("kind", adminDetail.path("kind").asText());
        editedSkill.put("sortWeight", adminDetail.path("sortWeight").asInt());
        editedSkill.put("rowVersion", adminDetail.path("rowVersion").asInt());
        webTestClient.put().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(editedSkill)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tags[0]").isEqualTo("自定义")
                .jsonPath("$.tags[1]").isEqualTo(owner);
        Map<String, Object> overlongTag = new LinkedHashMap<>(editedSkill);
        overlongTag.put("tags", List.of("a".repeat(65)));
        overlongTag.put("rowVersion", adminDetail.path("rowVersion").asInt() + 1);
        webTestClient.put().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(overlongTag)
                .exchange().expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.message").value(message ->
                        assertThat(message.toString()).contains("64"));
        JsonNode sourceVersions = json(getBody(webTestClient.get()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + id + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(sourceVersions.path("items")).hasSize(1);
        assertThat(sourceVersions.path("items").get(0).path("id").asText())
                .isEqualTo(sourceId.toString());
        assertThat(sourceVersions.path("items").get(0).path("status").asText()).isEqualTo("DRAFT");

        // 重扫的入口只接受 PENDING / FAILED：一个已经 PASSED 的归档没有重扫的理由。
        // 而没有 ClamAV 的环境里导入即 PASSED，于是这条重扫路径在本环境永远走不到
        // ——这正是 ClawhubSkillImportService 注释里说的"没有出口的死循环"。
        // 要验证重扫，先把状态显式退回 PENDING。
        databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source
                           SET scan_status = 'PENDING'
                         WHERE id = :source_id
                        """)
                .bind("source_id", sourceId)
                .fetch().rowsUpdated().block();
        JsonNode rescanned = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + id + "/rescan")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(rescanned.path("skillId").asText()).isEqualTo(id.toString());
        assertThat(rescanned.path("sourceSha256").asText()).isEqualTo(sha256);
        assertThat(rescanned.path("status").asText()).isEqualTo("DRAFT");
        // 重扫**真的重扫**：这里拿到的是本次扫描的结论。
        // CI/开发环境通常没有 ClamAV，此时扫描器被显式关闭，
        // ClawhubSkillImportService.scanStatus 会**视为通过**（它记录的理由：
        // 否则这些环境永远发不了原生包——发布永远 409，而重扫只能从
        // PENDING/FAILED 出发，会形成没有出口的死循环）。因此三种取值都合法，
        // 真正要守住的是紧接着那句：技能上的 sourceScanStatus 必须与本次响应一致。
        assertThat(rescanned.path("scanStatus").asText()).isIn("PENDING", "FAILED", "PASSED");
        JsonNode afterRescan = json(getBody(webTestClient.get().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(afterRescan.path("sourceScanStatus").asText())
                .isEqualTo(rescanned.path("scanStatus").asText());
        assertThat(afterRescan.path("tags").size()).isEqualTo(2);
        assertThat(afterRescan.path("tags").get(0).asText()).isEqualTo("自定义");
        assertThat(afterRescan.path("tags").get(1).asText()).isEqualTo(owner);
        assertThat(auditActions(id)).contains("skill.update");

        webTestClient.post().uri(BASE + "/" + id + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("native_clawhub_ycps_route_forbidden");

        webTestClient.post().uri(BASE + "/" + id + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(packageForm(
                        YcpsTestPackages.skill("blocked-native", "1.0.0"), "1.0.0", null, null)))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("native_clawhub_ycps_route_forbidden");

        JsonNode version = json(getBody(webTestClient.put()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + id + "/version")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("version", "1.0.0"))
                .exchange()
                .expectStatus().isOk()));
        assertThat(version.path("version").asText()).isEqualTo("1.0.0");
        assertThat(version.path("status").asText()).isEqualTo("DRAFT");
        assertThat(version.path("reviewRequired").asBoolean()).isTrue();
        assertThat(json(getBody(webTestClient.get().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk())).path("sourceVersion").asText())
                .isEqualTo("1.0.0");
        assertThat(auditActions(id)).contains("skill.update");

        webTestClient.put().uri(NATIVE_IMPORT_BASE + "/clawhub/" + id + "/version")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("version", "2.0.0"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("native_clawhub_version_already_set");

        // 下面要断言的那条守卫是"未通过扫描的归档不得发布"。没有 ClamAV 的环境里
        // 重扫返回的是 PASSED，这条守卫就测不到了（有扫描器时它才是 409）。
        // 因此先把状态显式置回 PENDING，让它成为一个确定的前提；
        // 之后测试会把对象挪到 clean/ 并置 PASSED，再验证发布成功。
        databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source
                           SET scan_status = 'PENDING'
                         WHERE id = :source_id
                        """)
                .bind("source_id", sourceId)
                .fetch().rowsUpdated().block();
        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/" + id + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("native_clawhub_scan_not_passed");

        String quarantineKey = databaseClient.sql("""
                        SELECT object_key FROM gateway.native_clawhub_source WHERE id = :source_id
                        """)
                .bind("source_id", sourceId)
                .map((row, metadata) -> row.get("object_key", String.class))
                .one()
                .block();
        String cleanKey = "clean/native-clawhub/" + id + "/" + sha256 + ".zip";
        objectStore.copy(quarantineKey, cleanKey).block();
        databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source
                           SET status = 'PUBLISHED'
                         WHERE id = :source_id
                        """)
                .bind("source_id", sourceId)
                .fetch().rowsUpdated().block();
        databaseClient.sql("""
                        INSERT INTO gateway.native_clawhub_release(
                            id, skill_id, source_id, version, package_format, source_sha256,
                            size_bytes, object_key, published_at)
                        VALUES (:id, :skill_id, :source_id, '1.0.0', 'CLAWHUB_SKILL',
                                :sha256, :size, :object_key, now())
                        """)
                .bind("id", UUID.randomUUID())
                .bind("skill_id", id)
                .bind("source_id", sourceId)
                .bind("sha256", sha256)
                .bind("size", (long) archive.length)
                .bind("object_key", cleanKey)
                .fetch().rowsUpdated().block();
        byte[] publishedDownload = webTestClient.get()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + id + "/package")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Content-Disposition",
                        "attachment; filename=\"clawhub-" + imported.path("code").asText() + ".zip\"")
                .expectHeader().valueEquals("Content-Length", Integer.toString(archive.length))
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        assertThat(publishedDownload).containsExactly(archive);

        // A historical release can outlive its clean object. Admin export must still return
        // the exact original upload, and must reject a damaged original instead of trusting it.
        objectStore.delete(cleanKey).block();
        byte[] fallbackDownload = webTestClient.get()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + id + "/package")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Skill-Sha256", sha256)
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        assertThat(fallbackDownload).containsExactly(archive);
        ((FakeSkillObjectStore) objectStore).seed(quarantineKey, new byte[archive.length]);
        webTestClient.get().uri(NATIVE_IMPORT_BASE + "/clawhub/" + id + "/package")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("native_clawhub_source_integrity_failed");

        JsonNode catalog = json(getBody(webTestClient.get().uri("/v1/native-clawhub/skills")
                .exchange().expectStatus().isOk()));
        assertThat(catalog.path("items").size()).isZero();
        webTestClient.get().uri("/v1/native-clawhub/skills/" + imported.path("code").asText() + "/package")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void publishesFirstNativeClawhubVersionOnFirstDetailPublishDespiteTemporaryCopyVisibility404s()
            throws Exception {
        byte[] archive = nativeClawhubZip("First detail publication");
        String sha256 = sha256(archive);
        JsonNode imported = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/commit")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeImportForm(
                        archive, "first.zip", "publisher", "first-detail-publish", "1.0.0", sha256)))
                .exchange()
                .expectStatus().isCreated()));
        UUID skillId = UUID.fromString(imported.path("skillId").asText());
        UUID sourceId = UUID.fromString(imported.path("sourceId").asText());

        databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source
                           SET scan_status = 'PASSED', review_required = inspection_review_required
                         WHERE id = :source_id
                        """)
                .bind("source_id", sourceId)
                .fetch().rowsUpdated().block();
        String cleanKey = "clean/native-clawhub/" + skillId + "/" + sha256 + ".zip";
        ((FakeSkillObjectStore) objectStore).delayFirstPostCopyReads(cleanKey);

        JsonNode firstPublished = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId
                        + "/versions/" + sourceId + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(firstPublished.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(firstPublished.path("publishedVersion").asText()).isEqualTo("1.0.0");
        assertThat(databaseClient.sql("""
                        SELECT source_id FROM gateway.native_clawhub_release WHERE skill_id = :skill_id
                        """)
                .bind("skill_id", skillId)
                .map((row, metadata) -> row.get("source_id", UUID.class))
                .one()
                .block()).isEqualTo(sourceId);
        JsonNode versions = json(getBody(webTestClient.get()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(versions.path("items")).hasSize(1);
        assertThat(versions.path("items").get(0).path("online").asBoolean()).isTrue();
    }

    @Test
    void nativeClawhubCatalogServesUploadedIconOnlyWhileTheSkillIsVisible() throws Exception {
        byte[] archive = nativeClawhubZip("Native skill with a catalog icon");
        String digest = sha256(archive);
        JsonNode imported = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/commit")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeImportForm(
                        archive, "icon.zip", "publisher", "icon-skill", "1.0.0", digest)))
                .exchange().expectStatus().isCreated()));
        UUID skillId = UUID.fromString(imported.path("skillId").asText());
        UUID sourceId = UUID.fromString(imported.path("sourceId").asText());
        String iconUrl = "/v1/native-clawhub/skills/" + imported.path("code").asText() + "/icon";

        webTestClient.post().uri(BASE + "/" + skillId + "/icon")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(iconForm()))
                .exchange().expectStatus().isOk();
        assertThat(json(getBody(webTestClient.get().uri("/v1/native-clawhub/skills")
                .exchange().expectStatus().isOk())).path("items").size()).isZero();
        webTestClient.get().uri(iconUrl).exchange().expectStatus().isNotFound();

        databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source
                           SET scan_status = 'PASSED', review_required = inspection_review_required
                         WHERE id = :source_id
                        """)
                .bind("source_id", sourceId)
                .fetch().rowsUpdated().block();
        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId
                        + "/versions/" + sourceId + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk();

        JsonNode published = json(getBody(webTestClient.get().uri("/v1/native-clawhub/skills")
                .exchange().expectStatus().isOk())).path("items").get(0);
        assertThat(published.path("iconUrl").asText()).isEqualTo(iconUrl);
        assertThat(published.path("iconUpdatedAt").asText()).isNotBlank();
        webTestClient.get().uri(iconUrl).exchange().expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_PNG)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody(byte[].class).isEqualTo(new byte[] {
                        (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3});

        unpublish(skillId);
        JsonNode unpublished = json(getBody(webTestClient.get().uri("/v1/native-clawhub/skills")
                .exchange().expectStatus().isOk())).path("items").get(0);
        assertThat(unpublished.path("status").asText()).isEqualTo("UNPUBLISHED");
        assertThat(unpublished.path("iconUrl").asText()).isEqualTo(iconUrl);
        webTestClient.get().uri(iconUrl).exchange().expectStatus().isOk();

        webTestClient.delete().uri(BASE + "/" + skillId)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk();
        webTestClient.get().uri(iconUrl).exchange().expectStatus().isNotFound();
    }

    @Test
    void nativeClawhubDefaultPublishStillRequiresValidVersionOnOnlySource() throws Exception {
        byte[] archive = nativeClawhubZip("Unversioned source");
        JsonNode imported = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/commit")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeImportForm(
                        archive, "source.zip", "publisher", "unversioned-default",
                        null, sha256(archive))))
                .exchange().expectStatus().isCreated()));
        UUID skillId = UUID.fromString(imported.path("skillId").asText());
        UUID sourceId = UUID.fromString(imported.path("sourceId").asText());
        databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source
                           SET scan_status = 'PASSED', review_required = inspection_review_required
                         WHERE id = :source_id
                        """)
                .bind("source_id", sourceId)
                .fetch().rowsUpdated().block();

        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("native_clawhub_version_required");
        webTestClient.put().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/version")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("version", "1.0.0"))
                .exchange().expectStatus().isOk();
        JsonNode published = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(published.path("publishedVersion").asText()).isEqualTo("1.0.0");
    }

    @Test
    void nativeClawhubDefaultPublishRequiresSelectionWhenNoReleaseHasMultipleVersions()
            throws Exception {
        byte[] firstArchive = nativeClawhubZip("First unpublished version");
        JsonNode imported = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/commit")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeImportForm(
                        firstArchive, "first.zip", "publisher", "unselected-versions",
                        "1.0.0", sha256(firstArchive))))
                .exchange().expectStatus().isCreated()));
        UUID skillId = UUID.fromString(imported.path("skillId").asText());

        byte[] secondArchive = nativeClawhubZip("Second unpublished version");
        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeVersionForm(
                        secondArchive, "second.zip", "2.0.0", sha256(secondArchive))))
                .exchange().expectStatus().isCreated();
        databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source
                           SET scan_status = 'PASSED', review_required = inspection_review_required
                         WHERE skill_id = :skill_id
                        """)
                .bind("skill_id", skillId)
                .fetch().rowsUpdated().block();

        JsonNode versions = json(getBody(webTestClient.get()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(versions.path("items")).hasSize(2);
        assertThat(versions.path("currentPublishedVersionId").isNull()).isTrue();
        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("native_clawhub_version_id_required");
        assertThat(databaseClient.sql(
                        "SELECT count(*) FROM gateway.native_clawhub_release WHERE skill_id = :skill_id")
                .bind("skill_id", skillId)
                .map((row, metadata) -> row.get(0, Long.class))
                .one().block()).isZero();
    }

    @Test
    void nativeClawhubDefaultPublishRetainsSelectedVersionAfterUnpublishAndDoesNotFallback()
            throws Exception {
        byte[] firstArchive = nativeClawhubZip("First selected version");
        JsonNode imported = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/commit")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeImportForm(
                        firstArchive, "first.zip", "publisher", "selected-version",
                        "1.0.0", sha256(firstArchive))))
                .exchange().expectStatus().isCreated()));
        UUID skillId = UUID.fromString(imported.path("skillId").asText());
        UUID firstId = UUID.fromString(imported.path("sourceId").asText());
        databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source
                           SET scan_status = 'PASSED', review_required = inspection_review_required
                         WHERE id = :source_id
                        """)
                .bind("source_id", firstId)
                .fetch().rowsUpdated().block();
        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk();

        byte[] secondArchive = nativeClawhubZip("Second ready draft");
        JsonNode second = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeVersionForm(
                        secondArchive, "second.zip", "2.0.0", sha256(secondArchive))))
                .exchange().expectStatus().isCreated()));
        UUID secondId = UUID.fromString(second.path("id").asText());
        databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source
                           SET scan_status = 'PASSED', review_required = inspection_review_required
                         WHERE id = :source_id
                        """)
                .bind("source_id", secondId)
                .fetch().rowsUpdated().block();
        unpublish(skillId);

        JsonNode versions = json(getBody(webTestClient.get()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(versions.path("currentPublishedVersionId").asText()).isEqualTo(firstId.toString());
        assertThat(versions.path("items")).hasSize(2);
        assertThat(versions.path("items").get(0).path("online").asBoolean()).isFalse();
        assertThat(versions.path("items").get(1).path("online").asBoolean()).isFalse();

        databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source SET scan_status = 'FAILED'
                         WHERE id = :source_id
                        """)
                .bind("source_id", firstId)
                .fetch().rowsUpdated().block();
        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("native_clawhub_scan_not_passed");
        databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source SET scan_status = 'PASSED'
                         WHERE id = :source_id
                        """)
                .bind("source_id", firstId)
                .fetch().rowsUpdated().block();

        JsonNode republished = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(republished.path("publishedVersion").asText()).isEqualTo("1.0.0");
        assertThat(republished.path("latestVersion").asText()).isEqualTo("2.0.0");
        JsonNode republishedVersions = json(getBody(webTestClient.get()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(republishedVersions.path("currentPublishedVersionId").asText())
                .isEqualTo(firstId.toString());
        assertThat(republishedVersions.path("items").get(0).path("online").asBoolean()).isFalse();
        assertThat(republishedVersions.path("items").get(1).path("online").asBoolean()).isTrue();
    }

    @Test
    void retainsNativeClawhubArchiveVersionsExportsAndSwitchesOnlyTheCurrentRelease() throws Exception {
        byte[] firstArchive = nativeClawhubZip("First original package bytes");
        String firstSha = sha256(firstArchive);
        JsonNode imported = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/commit")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeImportForm(
                        firstArchive, "first.zip", "publisher", "versioned-skill", "1.0.0", firstSha)))
                .exchange()
                .expectStatus().isCreated()));
        UUID skillId = UUID.fromString(imported.path("skillId").asText());
        UUID firstId = UUID.fromString(imported.path("sourceId").asText());

        // The existing V84 source/release shape remains publishable after V85 migration.
        databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source
                           SET scan_status = 'PASSED', review_required = inspection_review_required
                         WHERE id = :source_id
                        """)
                .bind("source_id", firstId)
                .fetch().rowsUpdated().block();
        JsonNode firstPublished = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(firstPublished.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(firstPublished.path("publishedVersion").asText()).isEqualTo("1.0.0");
        assertThat(databaseClient.sql("""
                        SELECT source_id FROM gateway.native_clawhub_release WHERE skill_id = :skill_id
                        """)
                .bind("skill_id", skillId)
                .map((row, metadata) -> row.get("source_id", UUID.class))
                .one()
                .block()).isEqualTo(firstId);
        JsonNode firstPublishedDetail = json(getBody(webTestClient.get().uri(BASE + "/" + skillId)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(firstPublishedDetail.path("sourceFormat").asText()).isEqualTo("CLAWHUB_SKILL");
        assertThat(firstPublishedDetail.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(firstPublishedDetail.path("sourceVersion").asText()).isEqualTo("1.0.0");
        assertThat(firstPublishedDetail.path("sizeBytes").asLong()).isEqualTo(firstArchive.length);
        JsonNode firstPublishedVersions = json(getBody(webTestClient.get()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(firstPublishedVersions.path("items")).hasSize(1);
        assertThat(firstPublishedVersions.path("items").get(0).path("status").asText())
                .isEqualTo("PUBLISHED");
        assertThat(firstPublishedVersions.path("items").get(0).path("online").asBoolean()).isTrue();

        byte[] secondArchive = nativeClawhubZip("Second original package bytes");
        String secondSha = sha256(secondArchive);
        JsonNode second = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeVersionForm(
                        secondArchive, "second.zip", "2.0.0", secondSha)))
                .exchange()
                .expectStatus().isCreated()));
        UUID secondId = UUID.fromString(second.path("id").asText());
        // 同上：新上传的版本会现场扫描一次。没有 ClamAV 的环境里扫描器关闭、
        // 按"通过"落库；有扫描器时是 PENDING 或 FAILED。三种都合法。
        assertThat(second.path("scanStatus").asText()).isIn("PENDING", "FAILED", "PASSED");
        // 不带版本号的 publish 会重新发布"当前已发布"的那个版本（73d9a98 起的新语义：
        // 下架后再上架、批量导出后重发都走这条），因此这里不再是 409。
        JsonNode defaultPublished = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(defaultPublished.path("publishedVersion").asText()).isEqualTo("1.0.0");
        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/rescan")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("native_clawhub_version_id_required");
        // 让"扫描未通过"成为一个**确定**状态，而不是依赖环境里有没有 ClamAV：
        // 没有扫描器时代码会有意把新版本记为 PASSED（理由见
        // ClawhubSkillImportService.scanStatus），那样下面这条守卫就永远测不到，
        // 而它保护的正是"未通过扫描的归档不得发布"。这里显式置回 PENDING。
        databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source
                           SET scan_status = 'PENDING'
                         WHERE id = :source_id
                        """)
                .bind("source_id", secondId)
                .fetch().rowsUpdated().block();
        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId
                        + "/versions/" + secondId + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("native_clawhub_scan_not_passed");

        databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source
                           SET scan_status = 'PASSED', review_required = inspection_review_required
                         WHERE id = :source_id
                        """)
                .bind("source_id", secondId)
                .fetch().rowsUpdated().block();

        JsonNode history = json(getBody(webTestClient.get()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(history.path("items")).hasSize(2);
        assertThat(history.path("currentPublishedVersionId").asText()).isEqualTo(firstId.toString());
        assertThat(history.path("items").get(0).path("version").asText()).isEqualTo("2.0.0");
        assertThat(history.path("items").get(0).path("online").asBoolean()).isFalse();
        assertThat(history.path("items").get(1).path("online").asBoolean()).isTrue();

        webTestClient.get().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId
                        + "/versions/" + secondId + "/download")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Skill-Sha256", secondSha)
                .expectBody(byte[].class).isEqualTo(secondArchive);
        webTestClient.get().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId
                        + "/versions/" + secondId + "/download")
                .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                .exchange().expectStatus().isForbidden();
        webTestClient.get().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/versions")
                .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                .exchange().expectStatus().isForbidden();
        UUID unrelatedSkill = createSkillId("unrelated-skill", "无关技能", "通用");
        webTestClient.get().uri(NATIVE_IMPORT_BASE + "/clawhub/" + unrelatedSkill
                        + "/versions/" + secondId + "/download")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isNotFound();

        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId
                        + "/versions/" + secondId + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk();
        databaseClient.sql("""
                        UPDATE gateway.skill
                           SET description = :description, description_en = :description_en,
                               summary = :summary, summary_en = :summary_en
                         WHERE id = :id
                        """)
                .bind("description", "云端编辑的中文介绍")
                .bind("description_en", "Cloud edited English description")
                .bind("summary", "中文摘要")
                .bind("summary_en", "English summary")
                .bind("id", skillId)
                .fetch().rowsUpdated().block();
        JsonNode catalog = json(getBody(webTestClient.get().uri("/v1/native-clawhub/skills")
                .exchange().expectStatus().isOk()));
        assertThat(catalog.path("items")).hasSize(1);
        assertThat(catalog.path("items").get(0).path("description").asText())
                .isEqualTo("云端编辑的中文介绍");
        assertThat(catalog.path("items").get(0).path("descriptionEn").asText())
                .isEqualTo("Cloud edited English description");
        assertThat(catalog.path("items").get(0).path("summary").asText())
                .isEqualTo("中文摘要");
        assertThat(catalog.path("items").get(0).path("summaryEn").asText())
                .isEqualTo("English summary");
        assertThat(catalog.path("items").get(0).path("version").asText()).isEqualTo("2.0.0");
        assertThat(catalog.path("items").get(0).path("sha256").asText()).isEqualTo(secondSha);
        assertThat(catalog.path("items").get(0).path("sizeBytes").asLong()).isEqualTo(secondArchive.length);
        assertThat(catalog.path("items").get(0).path("updatedAt").asText()).isNotBlank();
        assertThat(catalog.path("items").get(0).path("status").asText()).isEqualTo("PUBLISHED");
        byte[] onlineSecond = webTestClient.get()
                .uri("/v1/native-clawhub/skills/" + imported.path("code").asText() + "/package")
                .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                .exchange().expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(onlineSecond).containsExactly(secondArchive);

        // The public download can use the exact original after a historical clean copy was lost.
        String secondSourceKey = "quarantine/native-clawhub/" + secondId + "/" + secondSha + ".zip";
        String secondCleanKey = "clean/native-clawhub/" + skillId + "/" + secondSha + ".zip";
        objectStore.delete(secondCleanKey).block();
        byte[] publicFallback = webTestClient.get()
                .uri("/v1/native-clawhub/skills/" + imported.path("code").asText() + "/package")
                .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Skill-Sha256", secondSha)
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(publicFallback).containsExactly(secondArchive);
        ((FakeSkillObjectStore) objectStore).seed(secondSourceKey, new byte[secondArchive.length]);
        webTestClient.get()
                .uri("/v1/native-clawhub/skills/" + imported.path("code").asText() + "/package")
                .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                .exchange().expectStatus().isEqualTo(409)
                .expectHeader().doesNotExist("X-Skill-Sha256");
        ((FakeSkillObjectStore) objectStore).seed(secondSourceKey, secondArchive);
        objectStore.copy(secondSourceKey, secondCleanKey).block();
        String wrongReleaseKey = "clean/native-clawhub/" + skillId + "/" + firstSha + ".zip";
        databaseClient.sql("""
                        UPDATE gateway.native_clawhub_release
                           SET object_key = :object_key
                         WHERE skill_id = :skill_id
                        """)
                .bind("object_key", wrongReleaseKey)
                .bind("skill_id", skillId)
                .fetch().rowsUpdated().block();
        webTestClient.get()
                .uri("/v1/native-clawhub/skills/" + imported.path("code").asText() + "/package")
                .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                .exchange().expectStatus().isEqualTo(409)
                .expectHeader().doesNotExist("X-Skill-Sha256");
        databaseClient.sql("""
                        UPDATE gateway.native_clawhub_release
                           SET object_key = :object_key
                         WHERE skill_id = :skill_id
                        """)
                .bind("object_key", secondCleanKey)
                .bind("skill_id", skillId)
                .fetch().rowsUpdated().block();

        // Selecting an earlier source is a supported rollback; it changes the client hash, not latest upload.
        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId
                        + "/versions/" + firstId + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk();
        JsonNode rolledBackCatalog = json(getBody(webTestClient.get().uri("/v1/native-clawhub/skills")
                .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(rolledBackCatalog.path("items").get(0).path("version").asText()).isEqualTo("1.0.0");
        assertThat(rolledBackCatalog.path("items").get(0).path("sha256").asText()).isEqualTo(firstSha);
        JsonNode detail = json(getBody(webTestClient.get().uri(BASE + "/" + skillId)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(detail.path("latestVersion").asText()).isEqualTo("2.0.0");
        assertThat(rolledBackCatalog.path("items").get(0).path("updatedAt").asText())
                .isEqualTo(detail.path("updatedAt").asText());
        assertThat(databaseClient.sql("SELECT count(*) FROM gateway.native_clawhub_release WHERE skill_id = :id")
                .bind("id", skillId)
                .map((row, metadata) -> row.get(0, Long.class))
                .one().block()).isEqualTo(1L);

        unpublish(skillId);
        JsonNode unpublishedCatalog = json(getBody(webTestClient.get()
                .uri("/v1/native-clawhub/skills")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")));
        assertThat(unpublishedCatalog.path("items")).hasSize(1);
        JsonNode unavailable = unpublishedCatalog.path("items").get(0);
        assertThat(unavailable.path("code").asText()).isEqualTo(imported.path("code").asText());
        assertThat(unavailable.path("status").asText()).isEqualTo("UNPUBLISHED");
        assertThat(unavailable.path("description").asText()).isEqualTo("云端编辑的中文介绍");
        assertThat(unavailable.has("sha256")).isFalse();
        assertThat(unavailable.has("sizeBytes")).isFalse();
        assertThat(unavailable.has("downloadUrl")).isFalse();
        assertThat(unavailable.path("updatedAt").asText()).isNotBlank();
        byte[] unpublishedDraftArchive = nativeClawhubZip("Third draft package bytes");
        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeVersionForm(
                        unpublishedDraftArchive, "third.zip", "3.0.0",
                        sha256(unpublishedDraftArchive))))
                .exchange().expectStatus().isCreated();
        JsonNode unpublishedAdminList = json(getBody(webTestClient.get()
                .uri(BASE + "?query=versioned-skill")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()));
        assertThat(unpublishedAdminList.path("items")).hasSize(1);
        assertThat(unpublishedAdminList.path("items").get(0).path("sourceVersion").asText())
                .isEqualTo("3.0.0");
        assertThat(unpublishedAdminList.path("items").get(0).path("sizeBytes").asLong())
                .isEqualTo(firstArchive.length);
        webTestClient.get()
                .uri("/v1/native-clawhub/skills/" + imported.path("code").asText() + "/package")
                .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                .exchange().expectStatus().isNotFound();
        byte[] adminExportAfterUnpublish = webTestClient.get()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/package")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Skill-Sha256", firstSha)
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(adminExportAfterUnpublish).containsExactly(firstArchive);
    }

    @Test
    void nativeClawhubExportsRejectSourcesRejectedByALaterScan() throws Exception {
        byte[] archive = nativeClawhubZip("Source awaiting malware rescan");
        JsonNode imported = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/commit")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeImportForm(
                        archive, "rejected.zip", "scan-owner", "rejected-source", "1.0.0", sha256(archive))))
                .exchange().expectStatus().isCreated()));
        String skillId = imported.path("skillId").asText();
        String sourceId = imported.path("sourceId").asText();
        databaseClient.sql("UPDATE gateway.native_clawhub_source SET scan_status = 'REJECTED' WHERE id = :id")
                .bind("id", UUID.fromString(sourceId)).fetch().rowsUpdated().block();
        for (String route : List.of("/package", "/versions/" + sourceId + "/download")) {
            webTestClient.get().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + route)
                    .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                    .exchange().expectStatus().isEqualTo(409)
                    .expectBody().jsonPath("$.code").isEqualTo("native_clawhub_source_rejected");
        }
    }

    @Test
    void remoteFetchReturnsExactZipWithUnicodeMetadataAndCreatesNoSkillRecords() throws Exception {
        byte[] archive = nativeClawhubZip("Remote archive awaiting explicit confirmation");
        String sourceUrl = "https://clawhub.ai/publisher/skills/sample";
        String metadata = MAPPER.writeValueAsString(Map.of(
                "sourceKind", "CLAWHUB", "owner", "publisher", "slug", "sample",
                "version", "1.0.0", "displayName", "中文技能与图标", "sourceUrl", sourceUrl,
                "fileName", "sample.zip"));
        when(remoteSkillFetch.fetch(sourceUrl)).thenReturn(Mono.just(
                new RemoteSkillFetchService.Result(archive, sha256(archive), metadata)));
        var response = webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/remote/fetch")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("url", sourceUrl))
                .exchange().expectStatus().isOk()
                .expectHeader().contentType("application/zip")
                .expectHeader().contentLength(archive.length)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().valueEquals("X-Skill-Sha256", sha256(archive))
                .expectBody(byte[].class).returnResult();
        assertThat(response.getResponseBody()).containsExactly(archive);
        assertThat(java.net.URLDecoder.decode(
                response.getResponseHeaders().getFirst("X-Skill-Remote-Metadata"), StandardCharsets.UTF_8))
                .isEqualTo(metadata);
        assertThat(databaseClient.sql("SELECT count(*) FROM gateway.skill")
                .map((row, column) -> row.get(0, Long.class)).one().block()).isZero();
        assertThat(databaseClient.sql("SELECT count(*) FROM gateway.native_clawhub_source")
                .map((row, column) -> row.get(0, Long.class)).one().block()).isZero();
    }

    @Test
    void nativeClawhubConfirmationStoresEditedMetadataAndCompleteArchiveThroughPublication() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: complete-source\ndescription: Original source description\nversion: 1.2.3\n---\n# Complete Skill\nRun the included scripts.\n".getBytes(StandardCharsets.UTF_8));
        files.put(".clawhub/origin.json", "{\"ownerHandle\":\"complete-owner\",\"slug\":\"complete-source\",\"installedVersion\":\"1.2.3\"}".getBytes(StandardCharsets.UTF_8));
        files.put("_meta.json", "{\"original\":true}".getBytes(StandardCharsets.UTF_8));
        files.put(".config/settings.json", "{\"enabled\":false}".getBytes(StandardCharsets.UTF_8));
        files.put("scripts/run.py", "print('source only')\n".getBytes(StandardCharsets.UTF_8));
        files.put("references/说明.md", "完整保留附带文档。".getBytes(StandardCharsets.UTF_8));
        files.put("assets/icon.png", new byte[] { (byte) 0x89, 'P', 'N', 'G', 0, 1, 2, (byte) 0xff });
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        byte[] archive = output.toByteArray();
        String fingerprint = sha256(archive);
        JsonNode preview = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/preview")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeImportForm(
                        archive, "complete.zip", "complete-owner", "complete-source", null, null)))
                .exchange().expectStatus().isOk()));
        assertThat(preview.path("report").path("sourceSha256").asText()).isEqualTo(fingerprint);
        assertThat(databaseClient.sql("SELECT count(*) FROM gateway.skill")
                .map((row, metadata) -> row.get(0, Long.class)).one().block()).isZero();
        var form = nativeImportForm(archive, "complete.zip", "complete-owner", "complete-source", null, fingerprint);
        form.add("metadata", new HttpEntity<>("""
                {"name":"远程技能中文名称","nameEn":"Complete Remote Skill",
                 "description":"确认前编辑的完整介绍","summary":"中文摘要",
                 "tags":["ClawHub","complete-owner"],"sortWeight":3}
                """));
        JsonNode imported = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/commit")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(form))
                .exchange().expectStatus().isCreated()));
        UUID skillId = UUID.fromString(imported.path("skillId").asText());
        UUID sourceId = UUID.fromString(imported.path("sourceId").asText());
        assertThat(imported.path("sizeBytes").asLong()).isEqualTo(archive.length);
        assertThat(imported.path("status").asText()).isEqualTo("DRAFT");
        JsonNode detail = json(getBody(webTestClient.get().uri(BASE + "/" + skillId)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN)).exchange().expectStatus().isOk()));
        assertThat(detail.path("name").asText()).isEqualTo("远程技能中文名称");
        assertThat(detail.path("nameEn").asText()).isEqualTo("Complete Remote Skill");
        assertThat(detail.path("description").asText()).isEqualTo("确认前编辑的完整介绍");
        assertThat(detail.path("summary").asText()).isEqualTo("中文摘要");
        assertThat(detail.path("sortWeight").asInt()).isEqualTo(3);
        databaseClient.sql("UPDATE gateway.native_clawhub_source SET scan_status = 'PASSED' WHERE id = :id")
                .bind("id", sourceId).fetch().rowsUpdated().block();
        for (int round = 0; round < 2; round++) {
            webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/publish")
                    .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                    .exchange().expectStatus().isOk();
            JsonNode publicCatalogItem = json(getBody(webTestClient.get()
                    .uri("/v1/native-clawhub/skills")
                    .exchange().expectStatus().isOk())).path("items").get(0);
            assertThat(publicCatalogItem.path("name").asText()).isEqualTo("远程技能中文名称");
            assertThat(publicCatalogItem.path("nameEn").asText()).isEqualTo("Complete Remote Skill");
            byte[] publicZip = webTestClient.get()
                    .uri("/v1/native-clawhub/skills/" + imported.path("code").asText() + "/package")
                    .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                    .exchange().expectStatus().isOk()
                    .expectBody(byte[].class).returnResult().getResponseBody();
            assertThat(publicZip).containsExactly(archive);
            unpublish(skillId);
            JsonNode unpublishedCatalogItem = json(getBody(webTestClient.get()
                    .uri("/v1/native-clawhub/skills")
                    .exchange().expectStatus().isOk())).path("items").get(0);
            assertThat(unpublishedCatalogItem.path("nameEn").asText())
                    .isEqualTo("Complete Remote Skill");
            webTestClient.get()
                    .uri("/v1/native-clawhub/skills/" + imported.path("code").asText() + "/package")
                    .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                    .exchange().expectStatus().isNotFound();
            for (String route : List.of("/package", "/versions/" + sourceId + "/download")) {
                byte[] downloaded = webTestClient.get().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + route)
                        .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                        .exchange().expectStatus().isOk()
                        .expectHeader().valueEquals("X-Skill-Sha256", fingerprint)
                        .expectBody(byte[].class).returnResult().getResponseBody();
                assertThat(downloaded).containsExactly(archive);
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "remote.skill.smoke", matches = "true")
    void livePrismfyRemoteImportCanConfirmPublishAndExportTheCompleteArchive() throws Exception {
        String sourceUrl = "https://clawhub.ai/uroboros1205/skills/prismfy-search?version=1.3.8";
        RemoteSkillFetchService.Result fetched = new RemoteSkillFetchService().fetchBlocking(sourceUrl);
        JsonNode remote = MAPPER.readTree(fetched.metadataJson());
        byte[] archive = fetched.archive();
        String owner = remote.path("owner").asText();
        String slug = remote.path("slug").asText();
        String version = remote.path("version").asText();
        assertThat(version).isEqualTo("1.3.8");
        JsonNode preview = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/preview")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeImportForm(
                        archive, "prismfy-search.zip", owner, slug, version, null)))
                .exchange().expectStatus().isOk()));
        assertThat(preview.path("report").path("sourceSha256").asText()).isEqualTo(fetched.sha256());
        assertThat(preview.path("report").path("version").asText()).isEqualTo(version);
        assertThat(databaseClient.sql("SELECT count(*) FROM gateway.skill")
                .map((row, column) -> row.get(0, Long.class)).one().block()).isZero();
        var form = nativeImportForm(archive, "prismfy-search.zip", owner, slug, version, fetched.sha256());
        form.add("metadata", new HttpEntity<>(MAPPER.writeValueAsString(Map.of(
                "name", remote.path("displayName").asText(),
                "sourceKind", "CLAWHUB", "sourceUrl", sourceUrl))));
        JsonNode imported = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/commit")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(form))
                .exchange().expectStatus().isCreated()));
        UUID skillId = UUID.fromString(imported.path("skillId").asText());
        UUID sourceId = UUID.fromString(imported.path("sourceId").asText());
        assertThat(imported.path("sizeBytes").asLong()).isEqualTo(archive.length);
        databaseClient.sql("UPDATE gateway.native_clawhub_source SET scan_status = 'PASSED' WHERE id = :id")
                .bind("id", sourceId).fetch().rowsUpdated().block();
        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk();
        byte[] published = webTestClient.get()
                .uri("/v1/native-clawhub/skills/" + imported.path("code").asText() + "/package")
                .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                .exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(published).containsExactly(archive);
        unpublish(skillId);
        byte[] exported = webTestClient.get().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + "/package")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(exported).containsExactly(archive);
    }

    @Test
    void nativeClawhubConfirmationRejectsChangedBytesAndInvalidMetadataWithoutCreatingRecords() throws Exception {
        byte[] archive = nativeClawhubZip("Remote file held unchanged between preview and confirmation");
        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/commit")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeImportForm(
                        archive, "immutable.zip", "immutable-owner", "immutable-skill", "1.0.0", "0".repeat(64))))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("clawhub_source_changed");
        var malformed = nativeImportForm(archive, "immutable.zip", "immutable-owner", "immutable-skill", "1.0.0", sha256(archive));
        malformed.add("metadata", new HttpEntity<>("{invalid json"));
        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/commit")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(malformed))
                .exchange().expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("clawhub_import_metadata_invalid");
        var invalidName = nativeImportForm(archive, "immutable.zip", "immutable-owner", "immutable-skill", "1.0.0", sha256(archive));
        invalidName.add("metadata", new HttpEntity<>("{\"name\":\"\"}"));
        webTestClient.post().uri(NATIVE_IMPORT_BASE + "/clawhub/commit")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(invalidName))
                .exchange().expectStatus().isEqualTo(422);
        assertThat(databaseClient.sql("SELECT count(*) FROM gateway.skill")
                .map((row, metadata) -> row.get(0, Long.class)).one().block()).isZero();
        assertThat(databaseClient.sql("SELECT count(*) FROM gateway.native_clawhub_source")
                .map((row, metadata) -> row.get(0, Long.class)).one().block()).isZero();
    }

    @Test
    void nativeClawhubDeletedSkillsCannotExportUntilRestored() throws Exception {
        byte[] archive = nativeClawhubZip("Retained archive for recycle-bin restore");
        JsonNode imported = json(getBody(webTestClient.post()
                .uri(NATIVE_IMPORT_BASE + "/clawhub/commit")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(nativeImportForm(
                        archive, "restore.zip", "deleted-owner", "restore-source", "1.0.0", sha256(archive))))
                .exchange().expectStatus().isCreated()));
        String skillId = imported.path("skillId").asText();
        String sourceId = imported.path("sourceId").asText();
        webTestClient.delete().uri(BASE + "/" + skillId)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk();
        for (String route : List.of("/package", "/versions/" + sourceId + "/download")) {
            webTestClient.get().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + route)
                    .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                    .exchange().expectStatus().isNotFound();
        }
        webTestClient.post().uri(BASE + "/" + skillId + "/restore")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange().expectStatus().isOk();
        for (String route : List.of("/package", "/versions/" + sourceId + "/download")) {
            byte[] restored = webTestClient.get().uri(NATIVE_IMPORT_BASE + "/clawhub/" + skillId + route)
                    .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                    .exchange().expectStatus().isOk()
                    .expectBody(byte[].class).returnResult().getResponseBody();
            assertThat(restored).containsExactly(archive);
        }
    }

    // ---------------------------------------------------------------- A1-A4

    /**
     * Regression: every optional column (category/summary/description/icon/minClientVersion) is
     * nullable, and R2DBC rejects {@code bind(name, null)} — a create with only code+name must be a
     * clean 201 instead of the 500 it produced before the {@code Parameter.empty} fix.
     */
    @Test
    void createsAndUpdatesASkillWithEveryOptionalColumnEmpty() throws Exception {
        byte[] created = getBody(webTestClient.post().uri(BASE)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"code":"minimal-skill","name":"极简技能"}
                        """)
                .exchange()
                .expectStatus().isCreated());
        JsonNode skill = MAPPER.readTree(created);
        UUID id = UUID.fromString(skill.get("id").asText());
        assertThat(skill.get("status").asText()).isEqualTo("DRAFT");
        for (String nullable : List.of(
                "category", "summary", "description", "icon", "minClientVersion", "sizeBytes")) {
            assertThat(skill.get(nullable).isNull())
                    .as("optional column %s must stay null", nullable)
                    .isTrue();
        }
        assertThat(skill.get("tags").isEmpty()).isTrue();

        // A4: clearing the same optional columns must stay a 200, not a 500.
        webTestClient.put().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"极简技能","category":null,"tags":[],"summary":null,
                         "description":null,"icon":null,"minClientVersion":null,"rowVersion":1}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.category").doesNotExist()
                .jsonPath("$.summary").doesNotExist()
                .jsonPath("$.description").doesNotExist()
                .jsonPath("$.icon").doesNotExist()
                .jsonPath("$.minClientVersion").doesNotExist()
                .jsonPath("$.rowVersion").isEqualTo(2);

        // The same nullable bind path is hit by an upload without changelog/minClientVersion.
        UUID versionId = versionId(uploadVersion(id, "1.0.0", null, null));
        webTestClient.get().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.versions[0].id").isEqualTo(versionId.toString())
                .jsonPath("$.versions[0].changelog").doesNotExist()
                .jsonPath("$.versions[0].minClientVersion").doesNotExist();
    }

    @Test
    void createsListsShowsAndUpdatesASkillWithOptimisticLocking() throws Exception {
        JsonNode created = createSkill("article-writer", "文章写作", "内容与营销");
        UUID id = UUID.fromString(created.get("id").asText());
        assertThat(created.get("status").asText()).isEqualTo("DRAFT");
        assertThat(created.get("rowVersion").asInt()).isEqualTo(1);

        // A1: paging/search/filter/sort, all in the PlatformPage shape {items,total,page,size}.
        webTestClient.get().uri(uri -> uri.path(BASE)
                        .queryParam("page", 0)
                        .queryParam("size", 10)
                        .queryParam("query", "数据")
                        .queryParam("status", "DRAFT")
                        .queryParam("category", "内容与营销")
                        .queryParam("sort", "updated_at")
                        .queryParam("direction", "desc")
                        .build())
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(1)
                .jsonPath("$.page").isEqualTo(0)
                .jsonPath("$.size").isEqualTo(10)
                .jsonPath("$.items[0].code").isEqualTo("article-writer")
                .jsonPath("$.items[0].category").isEqualTo("内容与营销")
                .jsonPath("$.items[0].tags[0]").isEqualTo("报表");

        // A3
        webTestClient.get().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo("article-writer")
                .jsonPath("$.versions.length()").isEqualTo(0);

        // A4
        webTestClient.put().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"文章写作 v2","category":"内容与营销","tags":["报表","SQL"],
                         "summary":"更新后的简介","description":"详情","icon":"📊",
                         "minClientVersion":"2026.6.0","sortWeight":7,"rowVersion":1}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("文章写作 v2")
                .jsonPath("$.sortWeight").isEqualTo(7)
                .jsonPath("$.rowVersion").isEqualTo(2);

        // Stale row_version -> optimistic-lock conflict.
        webTestClient.put().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"再次修改","tags":[],"rowVersion":1}
                        """)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_concurrent_modification");
    }

    @Test
    void rejectsDuplicateCodeAndInvalidInput() throws Exception {
        createSkill("content-writer", "内容生成助手", "内容创作");

        webTestClient.post().uri(BASE)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"code":"content-writer","name":"重复","tags":[]}
                        """)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_code_conflict")
                // The holder is named so the console can say which kind owns the code instead of
                // asking the operator to guess.
                .jsonPath("$.details.code").isEqualTo("content-writer")
                .jsonPath("$.details.occupiedBy.code").isEqualTo("content-writer")
                .jsonPath("$.details.occupiedBy.kind").isEqualTo("SKILL")
                .jsonPath("$.details.occupiedBy.status").isEqualTo("DRAFT")
                .jsonPath("$.details.occupiedBy.id").isNotEmpty();

        webTestClient.post().uri(BASE)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"code":"-bad","name":"非法标识","tags":[]}
                        """)
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_skill_input")
                .jsonPath("$.message").isNotEmpty();

        // R-36: the legacy ingestion caps still apply at the database boundary.
        webTestClient.post().uri(BASE)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"code":"too-many-tags","name":"标签过多","tags":["1","2","3","4","5","6","7",
                         "8","9","10","11","12","13","14","15","16","17"]}
                        """)
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.message").value(message ->
                        assertThat(message.toString()).contains("标签"));
    }

    @Test
    void rejectsPaginationAboveTheDocumentedLimit() {
        webTestClient.get().uri(uri -> uri.path(BASE).queryParam("page", 0).queryParam("size", 101).build())
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_pagination");

        webTestClient.get().uri(uri -> uri.path(BASE).queryParam("page", -1).queryParam("size", 10).build())
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_pagination");

        webTestClient.get().uri(uri -> uri.path(BASE).queryParam("sort", "drop table").build())
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_sort");
    }

    // ---------------------------------------------------------------- A5-A8

    @Test
    void uploadsAVersionPackageAndKeepsTheRequestedMinimumClientVersion() throws Exception {
        UUID id = createSkillId("article-writer", "文章写作", "内容与营销");

        // A6: the multipart field is camelCase minClientVersion (the web-console contract).
        webTestClient.post().uri(BASE + "/" + id + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(packageForm(
                        zip("article-writer", "1.3.0"), "1.3.0", "首次上传", "2026.6.0")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.version").isEqualTo("1.3.0")
                .jsonPath("$.revision").isEqualTo(1)
                .jsonPath("$.status").isEqualTo("READY")
                .jsonPath("$.scanStatus").isEqualTo("PASSED")
                .jsonPath("$.fileCount").isEqualTo(3)
                .jsonPath("$.md5").isNotEmpty()
                .jsonPath("$.sha256").isNotEmpty()
                .jsonPath("$.minClientVersion").isEqualTo("2026.6.0");

        JsonNode skill = json(get(BASE + "/" + id));
        assertThat(skill.get("latestVersion").asText()).isEqualTo("1.3.0");
        UUID versionId = UUID.fromString(skill.get("versions").get(0).get("id").asText());

        // A6 with the same version number is a conflict (R-13).
        webTestClient.post().uri(BASE + "/" + id + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        packageForm(zip("article-writer", "1.3.0"), "1.3.0", null, null)))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_version_conflict");

        // A7: replacing the package bumps the revision and changes the digest and the object key.
        String firstMd5 = skill.get("versions").get(0).get("md5").asText();
        String firstObjectKey = currentObjectKey();
        webTestClient.put().uri(BASE + "/" + id + "/versions/" + versionId + "/package")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(replacementForm(
                        zip("article-writer", "1.3.0", "# article-writer v2\n"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.version").isEqualTo("1.3.0")
                .jsonPath("$.revision").isEqualTo(2)
                .jsonPath("$.status").isEqualTo("READY")
                .jsonPath("$.md5").value(md5 -> assertThat(md5).isNotEqualTo(firstMd5));

        assertThat(currentObjectKey()).isNotEqualTo(firstObjectKey);
        assertThat(((FakeSkillObjectStore) objectStore).content(firstObjectKey)).isNotNull();

        // A8: making one version the online version.
        webTestClient.post().uri(BASE + "/" + id + "/versions/" + versionId + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("PUBLISHED")
                .jsonPath("$.online").isEqualTo(true);
    }

    // ---------------------------------------------------------------- A25: console package download

    @Test
    void downloadsTheOnlinePackageThroughTheAdminRoute() throws Exception {
        UUID id = createSkillId("article-writer", "文章写作", "内容与营销");
        byte[] packageBytes = zip("article-writer", "1.2.0");
        uploadVersion(id, "1.2.0", "2026.6.0");
        publish(id);

        // Default route: no versionId in the path, so the online version is resolved server-side.
        byte[] body = webTestClient.get().uri(BASE + "/" + id + "/package")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Content-Type", "application/vnd.yuzhi.ycps+zip")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Skill-Version", "1.2.0")
                .expectHeader().valueMatches("Content-Disposition",
                        "attachment; filename=\"article-writer-1\\.2\\.0\\.ycps\\.zip\"")
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isEqualTo(packageBytes);
    }

    @Test
    void downloadsTheCompletePackageBundleWithTheCuratedImages() throws Exception {
        UUID id = createSkillId("bundle-asset-probe", "打包探针", "内容与营销");
        uploadVersion(id, "1.0.0", "2026.6.0");
        publish(id);

        byte[] iconBytes = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        byte[] coverBytes = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 4, 5, 6};
        byte[] exampleBytes = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 7, 8, 9};

        // All three curated images exist for this record: avatar, introduction art and example.
        webTestClient.post().uri(BASE + "/" + id + "/icon")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        avatarForm(iconBytes, "avatar.png", MediaType.IMAGE_PNG)))
                .exchange()
                .expectStatus().isOk();
        webTestClient.post().uri(BASE + "/" + id + "/illustration")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        avatarForm(coverBytes, "cover.png", MediaType.IMAGE_PNG)))
                .exchange()
                .expectStatus().isOk();
        webTestClient.post().uri(BASE + "/" + id + "/example-image")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        avatarForm(exampleBytes, "example.png", MediaType.IMAGE_PNG)))
                .exchange()
                .expectStatus().isOk();

        byte[] bundle = webTestClient.get().uri(BASE + "/" + id + "/package/bundle")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                // A plain folder-shaped ZIP, deliberately not the YCPS media type: it is not uploadable.
                .expectHeader().valueEquals("Content-Type", "application/zip")
                .expectHeader().valueEquals("X-Skill-Version", "1.0.0")
                .expectHeader().valueEquals("X-Skill-Bundle-Assets", "3")
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();

        // The wrapper holds one folder with the untouched inner package plus all three images.
        // Image names follow the console's own convention (`<code>-icon`), not the package's.
        Map<String, byte[]> entries = unzipEntries(bundle);
        assertThat(entries.keySet()).containsExactlyInAnyOrder(
                "bundle-asset-probe-1.0.0/bundle-asset-probe-1.0.0.ycps.zip",
                "bundle-asset-probe-1.0.0/bundle-asset-probe-icon.png",
                "bundle-asset-probe-1.0.0/bundle-asset-probe-illustration.png",
                "bundle-asset-probe-1.0.0/bundle-asset-probe-example.png");
        assertThat(entries.get("bundle-asset-probe-1.0.0/bundle-asset-probe-icon.png"))
                .isEqualTo(iconBytes);
        assertThat(entries.get("bundle-asset-probe-1.0.0/bundle-asset-probe-illustration.png"))
                .isEqualTo(coverBytes);
        assertThat(entries.get("bundle-asset-probe-1.0.0/bundle-asset-probe-example.png"))
                .isEqualTo(exampleBytes);
    }

    @Test
    void bundleStillDownloadsWhenTheRecordHasNoCuratedImages() throws Exception {
        UUID id = createSkillId("article-writer", "文章写作", "内容与营销");
        byte[] packageBytes = zip("article-writer", "1.2.0");
        uploadVersion(id, "1.2.0", "2026.6.0");
        publish(id);

        byte[] bundle = webTestClient.get().uri(BASE + "/" + id + "/package/bundle")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Skill-Bundle-Assets", "0")
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();

        // Missing artwork must not fail the download: the package alone is still a complete answer.
        Map<String, byte[]> entries = unzipEntries(bundle);
        assertThat(entries.keySet())
                .containsExactly("article-writer-1.2.0/article-writer-1.2.0.ycps.zip");
        assertThat(entries.get("article-writer-1.2.0/article-writer-1.2.0.ycps.zip"))
                .isEqualTo(packageBytes);
    }

    /** Reads every entry of a wrapper ZIP into memory; the bundles under test are small by design. */
    private static Map<String, byte[]> unzipEntries(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    @Test
    void downloadsOneExactHistoricalVersionByItsId() throws Exception {
        UUID id = createSkillId("article-writer", "文章写作", "内容与营销");
        uploadVersion(id, "1.2.0", "2026.6.0");
        publish(id);
        // A second, newer version that is uploaded but never promoted: it stays off the online slot.
        byte[] newer = zip("article-writer", "1.3.0");
        uploadVersion(id, "1.3.0", "2026.6.0");

        JsonNode skill = json(get(BASE + "/" + id));
        String newerVersionId = null;
        for (JsonNode version : skill.get("versions")) {
            if ("1.3.0".equals(version.get("version").asText())) {
                newerVersionId = version.get("id").asText();
            }
        }
        assertThat(newerVersionId).isNotNull();

        byte[] body = webTestClient.get()
                .uri(BASE + "/" + id + "/versions/" + newerVersionId + "/package")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Skill-Version", "1.3.0")
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isEqualTo(newer);
    }

    @Test
    void refusesAPackageDownloadForAVersionOfAnotherSkill() throws Exception {
        UUID first = createSkillId("article-writer", "文章写作", "内容与营销");
        uploadVersion(first, "1.2.0", "2026.6.0");
        UUID second = createSkillId("report-writer", "报告撰写", "写作");
        uploadVersion(second, "2.0.0", "2026.6.0");

        JsonNode otherSkill = json(get(BASE + "/" + second));
        String foreignVersionId = otherSkill.get("versions").get(0).get("id").asText();

        // The version exists, but not under this skill: the pair must not resolve.
        webTestClient.get().uri(BASE + "/" + first + "/versions/" + foreignVersionId + "/package")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_package_not_available");
    }

    @Test
    void refusesAPackageDownloadWithoutAnAdminToken() throws Exception {
        UUID id = createSkillId("article-writer", "文章写作", "内容与营销");
        uploadVersion(id, "1.2.0", "2026.6.0");
        publish(id);

        webTestClient.get().uri(BASE + "/" + id + "/package")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void concurrentUploadsOfTheSameVersionReturnOneSuccessAndOneStableConflict() throws Exception {        UUID id = createSkillId("article-writer", "文章写作", "内容与营销");
        byte[] packageBytes = zip("article-writer", "1.0.0");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.function.Supplier<Integer> request = () -> {
            ready.countDown();
            try {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent_upload_start_timeout");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return webTestClient.post().uri(BASE + "/" + id + "/versions")
                    .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(
                            packageForm(packageBytes, "1.0.0", null, null)))
                    .exchange()
                    .returnResult(byte[].class)
                    .getStatus()
                    .value();
        };
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(request);
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(request);
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200, 409);
    }

    @Test
    void rejectsUnsafeAndMismatchingPackagesWithStableCodes() throws Exception {
        UUID id = createSkillId("article-writer", "文章写作", "内容与营销");

        // The database column is varchar(32); reject longer regex-compatible values at the API
        // boundary instead of leaking a PostgreSQL truncation error as a 500.
        webTestClient.post().uri(BASE + "/" + id + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(packageForm(
                        zip("article-writer"), "1".repeat(33), null, null)))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_skill_input");

        // Not a ZIP at all.
        webTestClient.post().uri(BASE + "/" + id + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(packageForm(
                        "not a zip".getBytes(StandardCharsets.UTF_8), "1.0.0", null, null)))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("ycps_package_invalid");

        // Zip-slip style entry names (R-10).
        webTestClient.post().uri(BASE + "/" + id + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(packageForm(
                        zipWithEntry("skills/../escape.txt"), "1.0.1", null, null)))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("ycps_package_unsafe_entry");

        // Manifest code contradicts the skill code.
        webTestClient.post().uri(BASE + "/" + id + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(packageForm(
                        zip("other-skill", "1.0.2"), "1.0.2", null, null)))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("package_identity_mismatch");

        // No ready version was left behind by any of the rejected uploads.
        webTestClient.post().uri(BASE + "/" + id + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_has_no_ready_version");
    }

    @Test
    void uploadsAnIconAndPreviewsTheClientContract() throws Exception {
        UUID id = createSkillId("article-writer", "文章写作", "内容与营销");
        uploadVersion(id, "1.0.0", "2026.6.0");
        publish(id);

        webTestClient.post().uri(BASE + "/" + id + "/icon")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(iconForm()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.iconUrl").isEqualTo(BASE + "/" + id + "/icon");

        // A14 renders through the shared client DTO.
        webTestClient.get().uri(BASE + "/" + id + "/preview")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("skill")
                .jsonPath("$.name").isEqualTo("article-writer")
                .jsonPath("$.version").isEqualTo("1.0.0")
                .jsonPath("$.minClientVersion").isEqualTo("2026.6.0")
                .jsonPath("$.downloadUrl").isEqualTo("/v1/skills/article-writer/downloads")
                .jsonPath("$.sizeBytes").isNotEmpty()
                .jsonPath("$.md5").isNotEmpty();
    }

    // ---------------------------------------------------------------- A9-A12

    @Test
    void publishesAndUnpublishesWithIdempotentRepeats() throws Exception {
        UUID id = createSkillId("article-writer", "文章写作", "内容与营销");

        // A9 without a READY version.
        webTestClient.post().uri(BASE + "/" + id + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_has_no_ready_version");

        uploadVersion(id, "1.0.0", null);
        JsonNode published = json(publish(id));
        assertThat(published.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.get("publishedVersion").asText()).isEqualTo("1.0.0");

        // Idempotent repeat: same state, still 200.
        assertThat(json(publish(id)).get("status").asText()).isEqualTo("PUBLISHED");

        // A11 refuses while published.
        webTestClient.delete().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_cannot_delete_published");

        // A10 + idempotent repeat.
        JsonNode unpublished = json(unpublish(id));
        assertThat(unpublished.get("status").asText()).isEqualTo("UNPUBLISHED");
        assertThat(unpublished.get("publishedVersion").isNull()).isTrue();
        webTestClient.post().uri(BASE + "/" + id + "/unpublish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();

        // R-12: the code is still taken while the skill exists in a non-deleted state.
        webTestClient.post().uri(BASE)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"code":"article-writer","name":"重复","tags":[]}
                        """)
                .exchange()
                .expectStatus().isEqualTo(409);

        // A11: logical delete (200, like the other gateway admin DELETE endpoints).
        webTestClient.delete().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();
        assertThat(deletedAt(id)).isNotNull();
        // A11 is idempotent even though normal reads deliberately hide the tombstone.
        webTestClient.delete().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();
        createSkill("article-writer", "重新发布的数据分析", "内容与营销");
    }

    @Test
    void listsRestoresAndPermanentlyDeletesRecycleBinEntries() throws Exception {
        UUID id = createExpert("recycle-expert", "回收站专家", "EXPERT");

        webTestClient.delete().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();

        // Normal administration reads and desktop-facing reads do not expose tombstones.
        webTestClient.get().uri(BASE + "?page=0&size=20&kind=EXPERT")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(0);

        webTestClient.get().uri(BASE + "/trash?page=0&size=100&kind=EXPERT")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(1)
                .jsonPath("$.items[0].id").isEqualTo(id.toString())
                .jsonPath("$.items[0].code").isEqualTo("recycle-expert")
                .jsonPath("$.items[0].deletedAt").isNotEmpty()
                .jsonPath("$.items[0].expiresAt").isNotEmpty();

        // Restore never republishes: a draft returns as a draft and remains desktop-invisible.
        webTestClient.post().uri(BASE + "/" + id + "/restore")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("DRAFT")
                .jsonPath("$.publishedVersion").isEmpty();
        assertThat(skillIsDeleted(id)).isFalse();

        // A subsequent delete can be purged immediately from the recycle bin.
        webTestClient.delete().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();
        webTestClient.delete().uri(BASE + "/" + id + "/purge")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();
        assertThat(skillRowCount(id)).isZero();
        assertThat(auditActions(id)).contains("skill.restore", "skill.purge");
    }

    @Test
    void deletesANonOnlineVersionAndRefusesTheOnlineOne() throws Exception {
        UUID id = createSkillId("article-writer", "文章写作", "内容与营销");
        UUID first = versionId(uploadVersion(id, "1.0.0", null));
        UUID second = versionId(uploadVersion(id, "1.1.0", null));
        publish(id);

        // The online version cannot be deleted.
        webTestClient.delete().uri(BASE + "/" + id + "/versions/" + second)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_version_online");

        // A8: switch the online version, then the previous one can go.
        webTestClient.post().uri(BASE + "/" + id + "/versions/" + first + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();

        webTestClient.delete().uri(BASE + "/" + id + "/versions/" + second)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();
        assertThat(versionDeletedAt(second)).isNotNull();
    }

    @Test
    void recreatingADeletedCodeDoesNotRestartTheRevisionCounter() throws Exception {
        // `code@version#revision` is the immutable content identity a desktop client stores. A code
        // that is deleted and recreated must not hand out a revision the client already holds with
        // different bytes: the client rejects that install as tampering and the user sees
        // "downloaded and verified, but automatic extraction failed".
        UUID first = createSkillId("article-writer", "文章写作", "内容与营销");
        webTestClient.post().uri(BASE + "/" + first + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        packageForm(zip("article-writer", "1.0.0", "# first\n"), "1.0.0", null, null)))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.revision").isEqualTo(1);

        webTestClient.delete().uri(BASE + "/" + first)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();

        // The recreated record uploads the same version number with different bytes.
        UUID second = createSkillId("article-writer", "重新发布的数据分析", "内容与营销");
        webTestClient.post().uri(BASE + "/" + second + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        packageForm(zip("article-writer", "1.0.0", "# second\n"), "1.0.0", null, null)))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                // Not 1: that identity is already taken by the deleted record's package.
                .jsonPath("$.revision").isEqualTo(2);
    }

    @Test
    void deletingAnArchivedLatestVersionKeepsAnotherArchivedVersionReusable() throws Exception {
        UUID id = createSkillId("article-writer", "文章写作", "内容与营销");
        UUID first = versionId(uploadVersion(id, "1.0.0", null));
        UUID second = versionId(uploadVersion(id, "1.1.0", null));
        publish(id);
        unpublish(id);

        webTestClient.delete().uri(BASE + "/" + id + "/versions/" + second)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();

        JsonNode detail = json(get(BASE + "/" + id));
        assertThat(detail.path("latestVersion").asText()).isEqualTo("1.0.0");
        webTestClient.post().uri(BASE + "/" + id + "/versions/" + first + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("PUBLISHED");
    }

    @Test
    void refusesToDeleteAVersionWithAnUnexpiredStartedDownload() throws Exception {
        UUID id = createSkillId("article-writer", "文章写作", "内容与营销");
        UUID version = versionId(uploadVersion(id, "1.0.0", null));
        publish(id);
        unpublish(id);
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        execute("""
                INSERT INTO gateway.skill_download (
                    id, skill_id, skill_version_id, version, user_id, tenant_id,
                    status, content_length, identity_key, skill_code, started_at, expires_at)
                VALUES ('%s', '%s', '%s', '1.0.0', '%s', '%s',
                    'STARTED', 1, 'user:%s:%s', 'article-writer', now(), now() + interval '1 hour')
                """.formatted(
                UUID.randomUUID(), id, version, user, tenant, tenant, user));

        webTestClient.delete().uri(BASE + "/" + id + "/versions/" + version)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_version_has_active_downloads");
        assertThat(versionIsDeleted(version)).isFalse();
    }

    /**
     * Regression: V42 retired the Legacy rows but kept their historical labels ("1.1"). Deleting such
     * a row re-validated it against the strict YCPS SemVer check and answered 500, so administrators
     * could never clear the unusable Legacy versions out of the console.
     */
    @Test
    void deletesARetiredLegacyVersionWhoseLabelIsNotSemver() throws Exception {
        UUID id = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        execute("""
                INSERT INTO gateway.skill(id, code, name, status)
                VALUES ('%s', 'legacy-retired', 'Legacy Retired', 'UNPUBLISHED')
                """.formatted(id));
        execute("""
                INSERT INTO gateway.skill_version(
                    id, skill_id, version, revision, status, object_key, file_name,
                    size_bytes, md5, sha256, file_count, extracted_bytes, scan_status)
                VALUES ('%s', '%s', '1.1', 1, 'READY',
                    'clean/skills/%s/legacy-retired/1.1/r1/legacy.zip', 'legacy-retired-1.1.zip',
                    12, 'a0755344e7d80f2a46b97df527f0cea9', '%s', 2, 10, 'PASSED')
                """.formatted(version, id, id, "f".repeat(64)));

        webTestClient.delete().uri(BASE + "/" + id + "/versions/" + version)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();
        assertThat(versionDeletedAt(version)).isNotNull();
    }

    // ---------------------------------------------------------------- A13 + audit

    @Test
    void listsDownloadSessionsAndWritesSkillAuditRows() throws Exception {
        UUID id = createSkillId("article-writer", "文章写作", "内容与营销");
        UUID versionId = versionId(uploadVersion(id, "1.0.0", null));
        publish(id);

        webTestClient.get().uri(uri -> uri.path(BASE + "/" + id + "/downloads")
                        .queryParam("page", 0).queryParam("size", 20).build())
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(0)
                .jsonPath("$.items.length()").isEqualTo(0);

        // Audit trail: one row per write, resource_type=SKILL, no object keys in the detail.
        assertThat(auditActions(id)).containsExactlyInAnyOrder(
                "skill.create", "skill.version.upload", "skill.publish");
        assertThat(auditDetailsContainObjectKeys(id)).isFalse();
        assertThat(versionId).isNotNull();
    }

    // ---------------------------------------------------------------- kind / i18n (V1-V2/E-01)

    @Test
    void createsAllThreeKindsAndFiltersTheListByKind() throws Exception {
        UUID skill = createSkillId("plain-skill", "普通技能", "通用");
        createExpert("data-expert", "数据分析专家", "EXPERT");
        createExpert("content-team", "内容分发专家团", "TEAM");

        // Default: a create without kind is a plain SKILL.
        JsonNode created = MAPPER.readTree(get(BASE + "/" + skill));
        assertThat(created.get("kind").asText()).isEqualTo("SKILL");

        // V1: ?kind=TEAM returns only the expert team.
        JsonNode teams = MAPPER.readTree(get(BASE + "?kind=TEAM"));
        assertThat(teams.get("total").asInt()).isEqualTo(1);
        assertThat(teams.get("items").get(0).get("code").asText()).isEqualTo("content-team");
        assertThat(teams.get("items").get(0).get("kind").asText()).isEqualTo("TEAM");

        JsonNode experts = MAPPER.readTree(get(BASE + "?kind=EXPERT"));
        assertThat(experts.get("total").asInt()).isEqualTo(1);
        assertThat(experts.get("items").get(0).get("code").asText()).isEqualTo("data-expert");

        JsonNode skills = MAPPER.readTree(get(BASE + "?kind=SKILL"));
        assertThat(skills.get("total").asInt()).isEqualTo(1);
        assertThat(skills.get("items").get(0).get("code").asText()).isEqualTo("plain-skill");

        assertThat(MAPPER.readTree(get(BASE)).get("total").asInt()).isEqualTo(3);

        // The kind filter is validated like every other filter: stable 422 + flat envelope.
        webTestClient.get().uri(uri -> uri.path(BASE).queryParam("kind", "WIZARD").build())
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_skill_kind")
                .jsonPath("$.message").isNotEmpty();

        webTestClient.post().uri(BASE)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"code":"wizard-skill","name":"非法类型","kind":"WIZARD"}
                        """)
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_skill_kind");
    }

    @Test
    void roundTripsTheEnglishOverlayWithoutTouchingTheDefaultColumns() throws Exception {
        UUID id = createSkillId("article-writer", "文章写作", "内容与营销");

        // V2: A4 writes the *_en columns ...
        webTestClient.put().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"文章写作","nameEn":"Data Analyst","category":"内容与营销",
                         "tags":["报表"],"summary":"把原始数据整理成结论","summaryEn":"Turns raw data into conclusions",
                         "description":"详情","descriptionEn":"Full description",
                         "kind":"SKILL","rowVersion":1}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("文章写作")
                .jsonPath("$.nameEn").isEqualTo("Data Analyst")
                .jsonPath("$.summaryEn").isEqualTo("Turns raw data into conclusions")
                .jsonPath("$.descriptionEn").isEqualTo("Full description");

        // ... and A3 reads them back unchanged (the default columns keep their values).
        webTestClient.get().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("文章写作")
                .jsonPath("$.nameEn").isEqualTo("Data Analyst")
                .jsonPath("$.summary").isEqualTo("把原始数据整理成结论")
                .jsonPath("$.summaryEn").isEqualTo("Turns raw data into conclusions")
                .jsonPath("$.descriptionEn").isEqualTo("Full description");

        // A14 renders through the same client DTO, so the overlay survives the preview too.
        webTestClient.get().uri(BASE + "/" + id + "/preview")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.title").isEqualTo("文章写作")
                .jsonPath("$.titleEn").isEqualTo("Data Analyst")
                .jsonPath("$.summaryEn").isEqualTo("Turns raw data into conclusions")
                .jsonPath("$.descriptionEn").isEqualTo("Full description");
    }

    @Test
    void limitsBothSkillDescriptionsToOneThousandCharacters() throws Exception {
        String description = "中".repeat(1000);
        String descriptionEn = "E".repeat(1000);
        JsonNode created = json(getBody(webTestClient.post().uri(BASE)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "code", "description-limit",
                        "name", "详细说明边界",
                        "description", description,
                        "descriptionEn", descriptionEn))
                .exchange().expectStatus().isCreated()));
        assertThat(created.path("description").asText()).isEqualTo(description);
        assertThat(created.path("descriptionEn").asText()).isEqualTo(descriptionEn);

        String uri = BASE + "/" + created.path("id").asText();
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("name", "详细说明边界");
        update.put("rowVersion", created.path("rowVersion").asInt());
        update.put("description", "中".repeat(1001));
        update.put("descriptionEn", descriptionEn);
        webTestClient.put().uri(uri)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(update)
                .exchange().expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_skill_input")
                .jsonPath("$.message").isEqualTo("详细说明最多 1000 个字符。");

        update.put("description", description);
        update.put("descriptionEn", "E".repeat(1001));
        webTestClient.put().uri(uri)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(update)
                .exchange().expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_skill_input")
                .jsonPath("$.message").isEqualTo("英文详细说明最多 1000 个字符。");
    }

    @Test
    void refusesToChangeTheKindOnUpdate() throws Exception {
        UUID id = createExpert("content-team", "内容分发专家团", "TEAM");

        // E-01: editing the kind is a 422 with the stable code, on any value.
        webTestClient.put().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"内容分发专家团","kind":"EXPERT","rowVersion":1}
                        """)
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_skill_kind");

        // The row is untouched, so the next legitimate update still works with rowVersion=1.
        webTestClient.put().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"内容分发专家团 v2","kind":"TEAM","rowVersion":1}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("内容分发专家团 v2")
                .jsonPath("$.kind").isEqualTo("TEAM")
                .jsonPath("$.rowVersion").isEqualTo(2);

        // Admitting the stored kind is a no-op, not an error.
        webTestClient.put().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"内容分发专家团 v3","kind":"team","rowVersion":2}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.kind").isEqualTo("TEAM");
    }

    // ---------------------------------------------------------------- A15 roster (V7/E-04/E-05)

    @Test
    void replacesTheWholeRosterIdempotentlyInSortOrder() throws Exception {
        UUID id = createExpert("content-team", "内容分发专家团", "TEAM");
        // Roster entries must name published experts, so the keys the shared helper uses have to
        // exist as packages before the roster can reference them.
        seedRosterExperts();

        // V7: the console sends the whole table at once.
        webTestClient.put().uri(BASE + "/" + id + "/members")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(roster("content-team-lead", 0, "林禾", 1, "周其", 2))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.memberCount").isEqualTo(3)
                .jsonPath("$.members.length()").isEqualTo(3)
                .jsonPath("$.members[0].memberKey").isEqualTo("content-team-lead")
                .jsonPath("$.members[0].role").isEqualTo("LEAD")
                .jsonPath("$.members[0].name").isEqualTo("安拓")
                .jsonPath("$.members[0].nameEn").isEqualTo("Atlas")
                .jsonPath("$.members[0].profession").isEqualTo("分发策略师")
                .jsonPath("$.members[0].professionEn").isEqualTo("Distribution Strategist")
                .jsonPath("$.members[0].sortOrder").isEqualTo(0)
                // The portrait is the referenced expert's own icon, so it is always present.
                .jsonPath("$.members[0].avatarUrl")
                .isEqualTo("/console/gateway/skills/content-team-lead/icon")
                .jsonPath("$.members[2].memberKey").isEqualTo("content-team-analyst")
                .jsonPath("$.members[2].sortOrder").isEqualTo(2);

        assertThat(memberRows(id)).isEqualTo(3);

        // Idempotent: replaying the same body leaves exactly the same rows.
        webTestClient.put().uri(BASE + "/" + id + "/members")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(roster("content-team-lead", 0, "林禾", 1, "周其", 2))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.members.length()").isEqualTo(3);
        assertThat(memberRows(id)).isEqualTo(3);

        // A3 keeps returning the roster in (sortOrder, memberKey) order after a shrink.
        webTestClient.put().uri(BASE + "/" + id + "/members")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"members":[
                          {"memberKey":"content-team-analyst","name":"周其","role":"LEAD","sortOrder":9},
                          {"memberKey":"content-team-lead","name":"安拓","role":"MEMBER","sortOrder":1}
                        ]}
                        """)
                .exchange()
                .expectStatus().isOk();

        webTestClient.get().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.memberCount").isEqualTo(2)
                .jsonPath("$.members[0].memberKey").isEqualTo("content-team-lead")
                .jsonPath("$.members[0].role").isEqualTo("MEMBER")
                .jsonPath("$.members[1].memberKey").isEqualTo("content-team-analyst")
                .jsonPath("$.members[1].role").isEqualTo("LEAD");

        // An empty roster is legal: it is how a team drops every member (E-09 stays valid).
        webTestClient.put().uri(BASE + "/" + id + "/members")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"members\":[]}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.memberCount").isEqualTo(0)
                .jsonPath("$.members.length()").isEqualTo(0);
        assertThat(memberRows(id)).isZero();
    }

    @Test
    void rejectsMultipleLeadsAndDuplicateMemberKeys() throws Exception {
        UUID id = createExpert("content-team", "内容分发专家团", "TEAM");

        // E-04: at most one LEAD.
        webTestClient.put().uri(BASE + "/" + id + "/members")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"members":[
                          {"memberKey":"lead-one","name":"甲","role":"LEAD","sortOrder":0},
                          {"memberKey":"lead-two","name":"乙","role":"LEAD","sortOrder":1}
                        ]}
                        """)
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_skill_member")
                .jsonPath("$.message").isNotEmpty();

        // E-05: a member key may appear only once in a whole-roster replacement.
        webClientPutMembers(id, """
                {"members":[
                  {"memberKey":"dup-key","name":"甲","role":"LEAD","sortOrder":0},
                  {"memberKey":"dup-key","name":"乙","role":"MEMBER","sortOrder":1}
                ]}
                """)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_skill_member");

        // Illegal keys and roles are rejected with the same stable code.
        webClientPutMembers(id, """
                {"members":[{"memberKey":"-bad-key","name":"甲","role":"MEMBER","sortOrder":0}]}
                """)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_skill_member");

        webClientPutMembers(id, """
                {"members":[{"memberKey":"good-key","name":"甲","role":"OWNER","sortOrder":0}]}
                """)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_skill_member");

        // Every one of those failed before touching the table.
        assertThat(memberRows(id)).isZero();
    }

    // ---------------------------------------------------------------- introduction illustration

    @Test
    void uploadsReadsAndClearsTheIntroductionIllustration() throws Exception {
        UUID id = createExpert("content-team", "内容分发专家团", "TEAM");

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 9, 8, 7};
        webTestClient.post().uri(BASE + "/" + id + "/illustration")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        avatarForm(png, "cover.png", MediaType.IMAGE_PNG)))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.illustrationUrl")
                .isEqualTo(BASE + "/" + id + "/illustration");

        // The object key follows the documented layout, and the bytes come back byte-exact.
        String firstKey = illustrationObjectKey(id);
        assertThat(firstKey)
                .startsWith("clean/skills/" + id + "/content-team/illustration/")
                .endsWith(".png");
        webTestClient.get().uri(BASE + "/" + id + "/illustration")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_PNG)
                .expectBody(byte[].class)
                .isEqualTo(png);

        // Re-upload uses a fresh immutable key, switches the pointer transactionally and only
        // then removes the superseded object.
        byte[] replacement = {
                (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 4, 5, 6
        };
        webTestClient.post().uri(BASE + "/" + id + "/illustration")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        avatarForm(replacement, "cover.png", MediaType.IMAGE_PNG)))
                .exchange()
                .expectStatus().isOk();
        String replacementKey = illustrationObjectKey(id);
        assertThat(replacementKey).isNotEqualTo(firstKey);
        assertThat(((FakeSkillObjectStore) objectStore).content(firstKey)).isNull();
        assertThat(((FakeSkillObjectStore) objectStore).content(replacementKey))
                .isEqualTo(replacement);

        // DELETE clears the key and the byte read stops answering 200.
        webTestClient.delete().uri(BASE + "/" + id + "/illustration")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.illustrationUrl").doesNotExist();
        assertThat(illustrationObjectKey(id)).isNull();

        webTestClient.get().uri(BASE + "/" + id + "/illustration")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_illustration_not_found");

        // Clearing again is idempotent: a package that never had one is not a 404.
        webTestClient.delete().uri(BASE + "/" + id + "/illustration")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.illustrationUrl").doesNotExist();
    }

    @Test
    void rejectsAnOversizedOrWrongTypeIllustration() throws Exception {
        UUID id = createExpert("content-team", "内容分发专家团", "TEAM");

        // The illustration shares the icon ceiling.
        byte[] oversized = new byte[4 * 1024 * 1024 + 1];
        webTestClient.post().uri(BASE + "/" + id + "/illustration")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        avatarForm(oversized, "big.png", MediaType.IMAGE_PNG)))
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_illustration_too_large");

        // PNG/JPEG/WebP/SVG only.
        webTestClient.post().uri(BASE + "/" + id + "/illustration")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        avatarForm("not an image".getBytes(StandardCharsets.UTF_8),
                                "notes.txt", MediaType.TEXT_PLAIN)))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_skill_input");

        assertThat(illustrationObjectKey(id)).isNull();
    }

    // ---------------------------------------------------------------- expert example image

    @Test
    void uploadsReadsReplacesAndClearsTheExpertExampleImage() throws Exception {
        UUID id = createExpert("document-review-expert", "文档审校专家", "EXPERT");

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 3, 2, 1};
        webTestClient.post().uri(BASE + "/" + id + "/example-image")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        avatarForm(png, "document-review-expert-example.png", MediaType.IMAGE_PNG)))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.exampleImageUrl")
                .isEqualTo(BASE + "/" + id + "/example-image");

        String firstKey = exampleImageObjectKey(id);
        assertThat(firstKey)
                .startsWith("clean/skills/" + id + "/document-review-expert/example-image/")
                .endsWith(".png");
        assertThat(exampleImageUpdatedAt(id)).isNotNull();
        webTestClient.get().uri(BASE + "/" + id + "/example-image")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_PNG)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals(
                        "Content-Security-Policy",
                        "default-src 'none'; style-src 'none'; script-src 'none'; sandbox")
                .expectBody(byte[].class)
                .isEqualTo(png);

        // Replacement swaps to a fresh immutable object before deleting the superseded bytes.
        byte[] replacement = {
                (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 7, 8, 9
        };
        webTestClient.post().uri(BASE + "/" + id + "/example-image")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        avatarForm(replacement, "document-review-expert-example.png",
                                MediaType.IMAGE_PNG)))
                .exchange()
                .expectStatus().isOk();
        String replacementKey = exampleImageObjectKey(id);
        assertThat(replacementKey).isNotEqualTo(firstKey);
        assertThat(((FakeSkillObjectStore) objectStore).content(firstKey)).isNull();
        assertThat(((FakeSkillObjectStore) objectStore).content(replacementKey))
                .isEqualTo(replacement);

        webTestClient.delete().uri(BASE + "/" + id + "/example-image")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.exampleImageUrl").doesNotExist();
        assertThat(exampleImageObjectKey(id)).isNull();
        assertThat(((FakeSkillObjectStore) objectStore).content(replacementKey)).isNull();

        webTestClient.get().uri(BASE + "/" + id + "/example-image")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_example_image_not_found");

        // Clearing an already-empty slot stays idempotent.
        webTestClient.delete().uri(BASE + "/" + id + "/example-image")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.exampleImageUrl").doesNotExist();
    }

    @Test
    void rejectsAnOversizedOrWrongTypeExpertExampleImage() throws Exception {
        UUID id = createExpert("document-review-expert", "文档审校专家", "EXPERT");

        byte[] oversized = new byte[4 * 1024 * 1024 + 1];
        webTestClient.post().uri(BASE + "/" + id + "/example-image")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        avatarForm(oversized, "too-large.png", MediaType.IMAGE_PNG)))
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_example_image_too_large");

        webTestClient.post().uri(BASE + "/" + id + "/example-image")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        avatarForm("not an image".getBytes(StandardCharsets.UTF_8),
                                "example.txt", MediaType.TEXT_PLAIN)))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_skill_input");

        assertThat(exampleImageObjectKey(id)).isNull();
    }

    @Test
    void servesTheExampleImageThroughClientAndConsoleCodeRoutes() throws Exception {
        String code = "example-image-route-expert";
        UUID id = createExpert(code, "示例图路由专家", "EXPERT");
        webTestClient.post().uri(BASE + "/" + id + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        packageForm(YcpsTestPackages.expert(code, "1.0.0"),
                                "1.0.0", null, "2026.6.0")))
                .exchange()
                .expectStatus().isOk();
        publish(id);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 4, 2};
        webTestClient.post().uri(BASE + "/" + id + "/example-image")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        avatarForm(png, "example.png", MediaType.IMAGE_PNG)))
                .exchange()
                .expectStatus().isOk();

        webTestClient.get().uri("/v1/skills/" + code + "/example-image")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_PNG)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody(byte[].class).isEqualTo(png);
        webTestClient.get().uri("/v1/skills/" + code + "/example-image")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_PNG)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody(byte[].class).isEqualTo(png);

        webTestClient.get().uri("/console/gateway/skills/" + code + "/example-image")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/console/gateway/skills/" + code + "/example-image")
                .headers(headers -> headers.setBearerAuth(USER_TOKEN))
                .exchange()
                .expectStatus().isForbidden();
        webTestClient.get().uri("/console/gateway/skills/" + code + "/example-image")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_PNG)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody(byte[].class).isEqualTo(png);
    }

    @Test
    void rejectsARosterMemberThatIsNotAPublishedExpert() throws Exception {
        UUID id = createExpert("content-team", "内容分发专家团", "TEAM");

        // A member is a reference to another expert package; an unknown key must be refused rather
        // than stored, otherwise the team could never render that member's portrait.
        webClientPutMembers(id, """
                {"members":[
                  {"memberKey":"ghost-member","name":"幽灵","role":"LEAD","sortOrder":0}
                ]}
                """)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_skill_member")
                .jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ghost-member"));

        assertThat(memberRows(id)).isZero();

        // A draft expert is not enough: it has to be published to be referenceable.
        UUID draftId = createExpert("draft-expert", "草稿专家", "EXPERT");
        webClientPutMembers(id, """
                {"members":[
                  {"memberKey":"draft-expert","name":"草稿","role":"LEAD","sortOrder":0}
                ]}
                """)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_skill_member");

        // Publishing it makes the very same body succeed, and the portrait points at its icon.
        webTestClient.post().uri(BASE + "/" + draftId + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        packageForm(
                                YcpsTestPackages.expert("draft-expert", "1.0.0"),
                                "1.0.0", null, null)))
                .exchange()
                .expectStatus().isOk();
        publish(draftId);
        webClientPutMembers(id, """
                {"members":[
                  {"memberKey":"draft-expert","name":"草稿","role":"LEAD","sortOrder":0}
                ]}
                """)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.members[0].avatarUrl")
                .isEqualTo("/console/gateway/skills/draft-expert/icon");
    }

    @Test
    void keepsRosterRowsWhenTheRosterIsReplaced() throws Exception {
        UUID id = createExpert("content-team", "内容分发专家团", "TEAM");
        seedRosterExperts();
        webClientPutMembers(id, roster("content-team-lead", 0, "林禾", 1, "周其", 2))
                .expectStatus().isOk();

        // A whole-roster save that renames the lead must not silently drop the row.
        webTestClient.put().uri(BASE + "/" + id + "/members")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"members":[
                          {"memberKey":"content-team-lead","name":"安拓（改名）","role":"LEAD","sortOrder":0}
                        ]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.members[0].name").isEqualTo("安拓（改名）")
                // Renaming the member keeps the reference, so its portrait still resolves.
                .jsonPath("$.members[0].avatarUrl")
                .isEqualTo("/console/gateway/skills/content-team-lead/icon");
    }

    // ---------------------------------------------------------------- A23-A26: category dictionary

    /**
     * A rename re-points the packages that still carry the old name in the same transaction, so a
     * renamed category never drops its packages into "未分类", and the code (the stable identifier the
     * console filters on) does not change.
     */
    @Test
    void renamingACategoryMigratesThePackagesStillCarryingTheOldName() throws Exception {
        createCategory("test-rename", "改名前的分类");
        UUID id = createExpert("test-rename-expert", "改名验证专家", "EXPERT", "改名前的分类");

        webTestClient.put().uri(BASE + "/categories/test-rename?kind=EXPERT")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"改名后的分类","nameEn":"Renamed Category"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo("test-rename")
                .jsonPath("$.name").isEqualTo("改名后的分类");

        // The package followed the rename instead of being orphaned under a label the
        // console no longer offers.
        webTestClient.get().uri(BASE + "/" + id)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.category").isEqualTo("改名后的分类");

        webTestClient.get().uri(BASE + "/categories?kind=EXPERT")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.categories[?(@.code=='test-rename')].name").isEqualTo("改名后的分类");
    }

    /** A rename onto a name another live category owns is a readable 409, not a raw constraint error. */
    @Test
    void refusesToRenameOntoANameAnotherCategoryOwns() throws Exception {
        createCategory("test-conflict-a", "冲突分类甲");
        createCategory("test-conflict-b", "冲突分类乙");

        webTestClient.put().uri(BASE + "/categories/test-conflict-b?kind=EXPERT")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"冲突分类甲"}
                        """)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_category_name_conflict");
    }

    /**
     * A26: the cover is uploaded, previewed and reported on the admin contract, so the console can
     * show the image and the desktop can cache it.
     */
    @Test
    void uploadsAndPreviewsACategoryCover() throws Exception {
        createCategory("test-cover", "封面分类");

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 7, 3};
        webTestClient.post().uri(BASE + "/categories/test-cover/image?kind=EXPERT")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(avatarForm(png, "cover.png", MediaType.IMAGE_PNG)))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.imageUrl").isEqualTo(BASE + "/categories/test-cover/image");

        // The console preview and the desktop download read the same bytes.
        webTestClient.get().uri(BASE + "/categories/test-cover/image?kind=EXPERT")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_PNG)
                .expectBody(byte[].class).isEqualTo(png);
    }

    /** A category with no cover answers 404 rather than an empty image, so the desktop keeps its own. */
    @Test
    void refusesToPreviewACategoryCoverThatWasNeverUploaded() throws Exception {
        createCategory("test-no-cover", "无封面分类");

        webTestClient.get().uri(BASE + "/categories/test-no-cover/image?kind=EXPERT")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isNotFound();
    }

    // ---------------------------------------------------------------- helpers

    private void createCategory(String code, String name) throws IOException {
        getBody(webTestClient.post().uri(BASE + "/categories")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"kind":"EXPERT","code":"%s","name":"%s"}
                        """.formatted(code, name))
                .exchange()
                .expectStatus().isCreated());
    }

    private JsonNode createSkill(String code, String name, String category) throws IOException {
        return MAPPER.readTree(getBody(webTestClient.post().uri(BASE)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"code":"%s","name":"%s","category":"%s","tags":["报表","SQL"],
                         "summary":"把原始数据整理成结论","description":"详情","icon":"📊",
                         "minClientVersion":"2026.6.0","sortWeight":5}
                        """.formatted(code, name, category))
                .exchange()
                .expectStatus().isCreated()));
    }

    private UUID createSkillId(String code, String name, String category) throws IOException {
        return UUID.fromString(createSkill(code, name, category).get("id").asText());
    }

    private UUID createExpert(String code, String name, String kind) throws IOException {
        return createExpert(code, name, kind, "内容创作");
    }

    /** Looks up an already created package by code (the roster tests need its id to publish it). */
    private UUID createExpertId(String code) {
        return databaseClient.sql("SELECT id FROM gateway.skill WHERE code = :code")
                .bind("code", code)
                .map((row, metadata) -> row.get("id", UUID.class))
                .one()
                .block();
    }

    private UUID createExpert(String code, String name, String kind, String category)
            throws IOException {
        byte[] created = getBody(webTestClient.post().uri(BASE)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"code":"%s","name":"%s","kind":"%s","category":"%s","tags":["专家"],
                         "summary":"多人协作","description":"详情"}
                        """.formatted(code, name, kind, category))
                .exchange()
                .expectStatus().isCreated());
        return UUID.fromString(MAPPER.readTree(created).get("id").asText());
    }
    /** The A15 body: one LEAD plus two members with explicit sort orders. */
    private static String roster(
            String leadKey, int leadOrder, String memberName, int memberOrder, String otherName,
            int otherOrder) {
        return """
                {"members":[
                  {"memberKey":"%s","name":"安拓","nameEn":"Atlas","profession":"分发策略师",
                   "professionEn":"Distribution Strategist","role":"LEAD","sortOrder":%d},
                  {"memberKey":"content-team-editor","name":"%s","nameEn":"Lin He",
                   "profession":"内容编辑","professionEn":"Content Editor","role":"MEMBER",
                   "sortOrder":%d},
                  {"memberKey":"content-team-analyst","name":"%s","nameEn":"Zhou Qi",
                   "profession":"内容与营销","professionEn":"Data Analyst","role":"MEMBER",
                   "sortOrder":%d}
                ]}
                """.formatted(leadKey, leadOrder, memberName, memberOrder, otherName, otherOrder);
    }

    private WebTestClient.ResponseSpec webClientPutMembers(UUID id, String body) {
        return webTestClient.put().uri(BASE + "/" + id + "/members")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private static MultiValueMap<String, HttpEntity<?>> avatarForm(
            byte[] content, String fileName, MediaType contentType) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        }).contentType(contentType);
        return builder.build();
    }

    private long memberRows(UUID id) {
        Long count = databaseClient.sql("SELECT count(*) FROM gateway.skill_member WHERE skill_id = :id")
                .bind("id", id)
                .map((row, metadata) -> row.get(0, Long.class))
                .one()
                .block();
        return count == null ? 0L : count;
    }

    /**
     * {@code null} is a legitimate value here (no illustration). An R2DBC mapper must not emit a
     * {@code null} element (the driver fails with {@code NullPointerException: data}), so "none"
     * travels through a sentinel and is mapped back to {@code null} on the way out.
     */
    private static final String NO_AVATAR = "\u0000none";

    /** Reads the stored illustration pointer straight from the row (never from the view). */
    private String illustrationObjectKey(UUID id) {
        String stored = databaseClient.sql("""
                        SELECT illustration_object_key FROM gateway.skill
                        WHERE id = :id
                        """)
                .bind("id", id)
                .map((row, metadata) -> {
                    String value = row.get("illustration_object_key", String.class);
                    return value == null ? NO_AVATAR : value;
                })
                .one()
                .defaultIfEmpty(NO_AVATAR)
                .block();
        return NO_AVATAR.equals(stored) ? null : stored;
    }

    /** Reads the example-image pointer directly so URL projection cannot hide persistence bugs. */
    private String exampleImageObjectKey(UUID id) {
        String stored = databaseClient.sql("""
                        SELECT example_image_object_key FROM gateway.skill
                        WHERE id = :id
                        """)
                .bind("id", id)
                .map((row, metadata) -> {
                    String value = row.get("example_image_object_key", String.class);
                    return value == null ? NO_AVATAR : value;
                })
                .one()
                .defaultIfEmpty(NO_AVATAR)
                .block();
        return NO_AVATAR.equals(stored) ? null : stored;
    }

    private Instant exampleImageUpdatedAt(UUID id) {
        return databaseClient.sql("""
                        SELECT example_image_updated_at FROM gateway.skill
                        WHERE id = :id
                        """)
                .bind("id", id)
                .map((row, metadata) -> row.get(
                        "example_image_updated_at", java.time.OffsetDateTime.class))
                .one()
                .blockOptional()
                .map(value -> value == null ? null : value.toInstant())
                .orElse(null);
    }

    private byte[] uploadVersion(UUID id, String version, String minClientVersion) {
        return uploadVersion(id, version, "更新说明", minClientVersion);
    }

    private byte[] uploadVersion(
            UUID id, String version, String changelog, String minClientVersion) {
        return getBody(webTestClient.post().uri(BASE + "/" + id + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        packageForm(zip(codeOf(id), version), version, changelog, minClientVersion)))
                .exchange()
                .expectStatus().isOk());
    }

    private byte[] publish(UUID id) {
        return getBody(webTestClient.post().uri(BASE + "/" + id + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk());
    }

    /**
     * Publishes one EXPERT so it can be referenced by a roster.
     *
     * <p>A roster entry is a reference to another expert package (memberKey == that package's code),
     * and the service rejects keys that are not published experts. Roster tests therefore have to
     * seed the experts they name instead of inventing keys. The package has to be a real expert
     * package (plugin.json declares expertType), not a plain skill one.
     */
    private String publishedExpert(String code, String name) throws IOException {
        UUID id = createExpert(code, name, "EXPERT");
        webTestClient.post().uri(BASE + "/" + id + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        packageForm(YcpsTestPackages.expert(code, "1.0.0"), "1.0.0", null, null)))
                .exchange()
                .expectStatus().isOk();
        publish(id);
        return code;
    }

    /** Seeds the three experts the shared roster helper references. */
    private void seedRosterExperts() throws IOException {
        publishedExpert("content-team-lead", "安拓");
        publishedExpert("content-team-editor", "林禾");
        publishedExpert("content-team-analyst", "周其");
    }

    private byte[] unpublish(UUID id) {
        return getBody(webTestClient.post().uri(BASE + "/" + id + "/unpublish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk());
    }

    private byte[] get(String path) {
        return getBody(webTestClient.get().uri(path)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk());
    }

    private static byte[] getBody(
            org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec spec) {
        return spec.expectBody(byte[].class).returnResult().getResponseBody();
    }

    private String codeOf(UUID id) {
        return databaseClient.sql("SELECT code FROM gateway.skill WHERE id = :id")
                .bind("id", id)
                .map((row, metadata) -> row.get("code", String.class))
                .one()
                .block();
    }

    private UUID versionId(byte[] body) {
        try {
            return UUID.fromString(MAPPER.readTree(body).get("id").asText());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String currentObjectKey() {
        return databaseClient.sql("""
                        SELECT object_key FROM gateway.skill_version
                        WHERE object_key IS NOT NULL AND deleted_at IS NULL
                        """)
                .map((row, metadata) -> row.get("object_key", String.class))
                .one()
                .block();
    }

    private Instant deletedAt(UUID id) {
        return databaseClient.sql("SELECT deleted_at FROM gateway.skill WHERE id = :id")
                .bind("id", id)
                .map((row, metadata) -> row.get("deleted_at", java.time.OffsetDateTime.class))
                .one()
                .blockOptional()
                .map(value -> value == null ? null : value.toInstant())
                .orElse(null);
    }

    private long skillRowCount(UUID id) {
        Long count = databaseClient.sql("SELECT count(*) FROM gateway.skill WHERE id = :id")
                .bind("id", id)
                .map((row, metadata) -> row.get(0, Long.class))
                .one()
                .block();
        return count == null ? 0L : count;
    }

    private boolean skillIsDeleted(UUID id) {
        return Boolean.TRUE.equals(databaseClient.sql("""
                        SELECT deleted_at IS NOT NULL AS deleted
                        FROM gateway.skill WHERE id = :id
                        """)
                .bind("id", id)
                .map((row, metadata) -> row.get("deleted", Boolean.class))
                .one()
                .block());
    }

    private Instant versionDeletedAt(UUID id) {
        return databaseClient.sql("SELECT deleted_at FROM gateway.skill_version WHERE id = :id")
                .bind("id", id)
                .map((row, metadata) -> row.get("deleted_at", java.time.OffsetDateTime.class))
                .one()
                .blockOptional()
                .map(value -> value == null ? null : value.toInstant())
                .orElse(null);
    }

    private boolean versionIsDeleted(UUID id) {
        return Boolean.TRUE.equals(databaseClient.sql("""
                        SELECT deleted_at IS NOT NULL AS deleted
                        FROM gateway.skill_version WHERE id = :id
                        """)
                .bind("id", id)
                .map((row, metadata) -> row.get("deleted", Boolean.class))
                .one()
                .block());
    }

    private List<String> auditActions(UUID resourceId) {
        return databaseClient.sql("""
                        SELECT action FROM gateway.admin_operation_audit
                        WHERE resource_type = 'SKILL' AND resource_id = :resource_id
                        ORDER BY action
                        """)
                .bind("resource_id", resourceId.toString())
                .map((row, metadata) -> row.get("action", String.class))
                .all()
                .collectList()
                .block();
    }

    private boolean auditDetailsContainObjectKeys(UUID resourceId) {
        Long count = databaseClient.sql("""
                        SELECT count(*) FROM gateway.admin_operation_audit
                        WHERE resource_id = :resource_id
                          AND detail::text LIKE '%quarantine/%'
                        """)
                .bind("resource_id", resourceId.toString())
                .map((row, metadata) -> row.get(0, Long.class))
                .one()
                .block();
        return count != null && count > 0;
    }

    private static MultiValueMap<String, HttpEntity<?>> nativeImportForm(
            byte[] archive,
            String fileName,
            String owner,
            String slug,
            String version,
            String expectedSha256) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(archive) {
            @Override
            public String getFilename() {
                return fileName;
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);
        if (owner != null) builder.part("owner", owner);
        if (slug != null) builder.part("slug", slug);
        if (version != null) builder.part("version", version);
        if (expectedSha256 != null) builder.part("expectedSha256", expectedSha256);
        return builder.build();
    }

    private static MultiValueMap<String, HttpEntity<?>> nativeVersionForm(
            byte[] archive, String fileName, String version, String expectedSha256) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(archive) {
            @Override
            public String getFilename() {
                return fileName;
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);
        if (version != null) builder.part("version", version);
        if (expectedSha256 != null) builder.part("expectedSha256", expectedSha256);
        return builder.build();
    }

    private static byte[] nativeClawhubZip() throws IOException {
        return nativeClawhubZip("Follow the user request.");
    }

    private static byte[] nativeClawhubZip(String body) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(output)) {
            archive.putNextEntry(new ZipEntry("SKILL.md"));
            archive.write(("# Native Skill\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static MultiValueMap<String, HttpEntity<?>> packageForm(
            byte[] packageBytes, String version, String changelog, String minClientVersion) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(packageBytes) {
            @Override
            public String getFilename() {
                return "skill-package.ycps.zip";
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);
        builder.part("version", version);
        if (changelog != null) {
            builder.part("changelog", changelog);
        }
        if (minClientVersion != null) {
            builder.part("minClientVersion", minClientVersion);
        }
        return builder.build();
    }

    private static MultiValueMap<String, HttpEntity<?>> replacementForm(byte[] packageBytes) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(packageBytes) {
            @Override
            public String getFilename() {
                return "skill-package.ycps.zip";
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);
        builder.part("changelog", "替换资源包");
        return builder.build();
    }

    private static MultiValueMap<String, HttpEntity<?>> iconForm() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(new byte[] {
                (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3}) {
            @Override
            public String getFilename() {
                return "icon.png";
            }
        }).contentType(MediaType.IMAGE_PNG);
        return builder.build();
    }

    private static byte[] zip(String code) {
        return zip(code, "1.0.0");
    }

    private static byte[] zip(String code, String version) {
        return YcpsTestPackages.skill(code, version);
    }

    private static byte[] zip(String code, String version, String document) {
        return YcpsTestPackages.skill(code, version, document);
    }

    /** A valid package plus one extra entry, so the rejection is about that entry alone. */
    private static byte[] zipWithEntry(String entryName) {
        return YcpsTestPackages.replace(
                YcpsTestPackages.skill("article-writer", "1.0.1"), entryName, "escape");
    }

    private static JsonNode json(byte[] body) throws IOException {
        return MAPPER.readTree(body);
    }

    private static Jwt token(String token, String role) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .claim("role", role)
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
