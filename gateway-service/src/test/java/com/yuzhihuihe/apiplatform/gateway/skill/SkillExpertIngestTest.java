package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
 * Full-stack contract test for the {@code plugin.json} half of the expert upgrade (design 4 and
 * 2026-09-14 spec 4/D3): manifest persistence (V4), the {@code expertType}/{@code kind} cross-check
 * (V5/E-02) and the "seed once, never overwrite" roster rule (V6/E-03).
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
                "security.api-key.pepper=skill-expert-ingest-pepper",
                "security.internal.hmac-secret=skill-expert-ingest-secret",
                "gateway.economics.outbox.enabled=false",
                "content-safety.outbox.enabled=false",
                "gateway.skills.source=database"
        })
@Import(SkillExpertIngestTest.ObjectStoreConfiguration.class)
class SkillExpertIngestTest {

    private static final String ADMIN_TOKEN = "skill-expert-admin-token";
    private static final String BASE = "/console/gateway/admin/skills";
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
        FileObjectStore skillExpertTestObjectStore() {
            return new FakeSkillObjectStore();
        }
    }

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    DatabaseClient databaseClient;

    /** The inspector runs on the same temp file the pipeline uses; this pins the raw capture too. */
    @Autowired
    SkillProperties skillProperties;

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
        when(jwtDecoder.decode(ADMIN_TOKEN)).thenReturn(Mono.just(token(ADMIN_TOKEN)));
        when(consoleTokenValidator.validate(anyString())).thenReturn(Mono.just(true));
        when(redis.opsForValue()).thenReturn(redisValues);
    }

    // ---------------------------------------------------------------- V4: manifest persistence

    @Test
    void storesThePluginManifestVerbatimAndSeedsTheRosterOnFirstUpload() throws Exception {
        UUID id = createExpert("content-distribution", "内容分发专家团", "TEAM");

        byte[] packageBytes = teamPackage(id, "1.0.0");
        upload(id, "1.0.0", packageBytes).expectStatus().isOk();

        JsonNode skill = MAPPER.readTree(get(BASE + "/" + id));
        // V4: A3 returns the manifest exactly as the package declared it.
        assertThat(skill.get("manifest")).isEqualTo(MAPPER.readTree(
                YcpsTestPackages.pluginJsonOf(packageBytes)));
        assertThat(skill.get("manifest").get("name").asText()).isEqualTo("content-distribution");
        assertThat(skill.get("manifest").get("extensions").get("com.yuzhihuihe.ycps")
                .get("kind").asText()).isEqualTo("TEAM");
        assertThat(skill.get("kind").asText()).isEqualTo("TEAM");

        // V6 (first half): the runtime agents[] seeded the roster in array order.
        assertThat(skill.get("memberCount").asInt()).isEqualTo(3);
        assertThat(skill.get("members").size()).isEqualTo(3);
        assertThat(skill.get("members").get(0).get("memberKey").asText())
                .isEqualTo("content-distribution-team-lead");
        assertThat(skill.get("members").get(0).get("role").asText()).isEqualTo("LEAD");
        // The runtime agent seeds the stable id; localized copy stays admin-editable metadata.
        assertThat(skill.get("members").get(0).get("name").asText())
                .isEqualTo("content-distribution-team-lead");
        assertThat(skill.get("members").get(0).get("profession").isNull()).isTrue();
        assertThat(skill.get("members").get(0).get("sortOrder").asInt()).isZero();
        assertThat(skill.get("members").get(1).get("role").asText()).isEqualTo("MEMBER");
        assertThat(skill.get("members").get(1).get("sortOrder").asInt()).isEqualTo(1);
        assertThat(skill.get("members").get(2).get("memberKey").asText())
                .isEqualTo("content-distribution-analyst");

        // Members are never exposed on the list (D5/E-08) and no avatar was invented.
        JsonNode list = MAPPER.readTree(get(BASE + "?kind=TEAM"));
        assertThat(list.get("total").asInt()).isEqualTo(1);
        JsonNode item = list.get("items").get(0);
        assertThat(item.get("kind").asText()).isEqualTo("TEAM");
        assertThat(item.get("memberCount").asInt()).isEqualTo(3);
        // D5/E-08: the list carries the member count but never the manifest document (it stays a
        // detail-only payload, reported here as null) and never the roster itself.
        assertThat(item.get("manifest").isNull()).isTrue();
        assertThat(item.has("members")).isFalse();

        // Design 4 step 6: the audit detail records what the pipeline did.
        JsonNode uploadAudit = auditDetail(id, "skill.version.upload");
        assertThat(uploadAudit.get("manifestStored").asBoolean()).isTrue();
        assertThat(uploadAudit.get("membersSeeded").asBoolean()).isTrue();
        assertThat(uploadAudit.get("memberCount").asLong()).isEqualTo(3L);
    }

    @Test
    void rejectsPackagesWithoutTheMandatoryYcpsEntries() throws Exception {
        UUID id = createExpert("plain-expert", "普通专家", "EXPERT");
        byte[] packageBytes = expertPackage(id, "1.0.0");

        upload(id, "1.0.0", YcpsTestPackages.without(
                        packageBytes, "com.yuzhihuihe.ycps/manifest.json"))
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("ycps_package_invalid");
        upload(id, "1.1.0", YcpsTestPackages.without(packageBytes, "plugin.json"))
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("ycps_package_invalid");

        JsonNode skill = MAPPER.readTree(get(BASE + "/" + id));
        assertThat(skill.get("manifest").isNull()).isTrue();
        assertThat(skill.get("members").size()).isZero();
        assertThat(skill.get("memberCount").asInt()).isZero();
        assertThat(memberRows(id)).isZero();
        // The YCPS structure and identity gate runs before the placeholder row, so a package that
        // is not a valid YCPS 1.0 archive is refused without occupying a version number.
        assertThat(versionStatuses(id)).isEmpty();
    }

    // ---------------------------------------------------------------- V5/E-02: kind cross-check

    @Test
    void rejectsAPackageWhoseExpertTypeContradictsTheSkillKind() throws Exception {
        UUID id = createExpert("content-distribution", "内容分发专家团", "TEAM");

        // E-02/V5: team package against kind=SKILL, and expert package against kind=TEAM.
        UUID plain = createExpert("plain-skill", "普通技能", "SKILL");
        upload(plain, "1.0.0", teamPackage(plain, "1.0.0"))
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("package_identity_mismatch");

        upload(id, "1.0.0", expertPackage(id, "1.0.0"))
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("package_identity_mismatch");

        // Nothing was promoted: no READY version, no manifest, no roster.
        webTestClient.post().uri(BASE + "/" + id + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_has_no_ready_version");

        JsonNode skill = MAPPER.readTree(get(BASE + "/" + id));
        assertThat(skill.get("manifest").isNull()).isTrue();
        assertThat(skill.get("members").size()).isZero();
        assertThat(memberRows(id)).isZero();

        // The identity gate runs before the placeholder row, so the rejected upload leaves no
        // version behind: only the stable 422 code above records why it was refused.
        assertThat(versionStatuses(id)).isEmpty();
    }

    @Test
    void recordsARejectedPlaceholderWhenTheLockedDependencyCannotBeResolved() throws Exception {
        UUID id = createExpert("dependency-holder", "依赖持有者", "SKILL");

        // The archive itself is valid YCPS 1.0, so it enters the pipeline and gets a placeholder
        // row; the unresolvable exact dependency then rejects that row with the stable reason.
        upload(id, "1.0.0", YcpsTestPackages.skillWithDependency(
                        "dependency-holder", "1.0.0", "absent-helper"))
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("ycps_dependency_unresolved");

        assertThat(versionStatuses(id)).containsExactly("REJECTED");
        assertThat(versionFailure(id)).isEqualTo("ycps_dependency_unresolved");
        assertThat(memberRows(id)).isZero();
        assertThat(MAPPER.readTree(get(BASE + "/" + id)).get("manifest").isNull()).isTrue();
    }

    @Test
    void rejectsPackagesWithAnInvalidPluginManifest() throws Exception {
        UUID id = createExpert("content-distribution", "内容分发专家团", "TEAM");
        byte[] valid = teamPackage(id, "1.0.0");

        upload(id, "1.0.0", YcpsTestPackages.replace(valid, "plugin.json", "{ not json"))
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("ycps_package_invalid");

        upload(id, "1.1.0", YcpsTestPackages.replace(valid, "plugin.json", "\"just a string\""))
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("ycps_package_invalid");

        upload(id, "1.2.0", YcpsTestPackages.replace(
                        valid,
                        "plugin.json",
                        YcpsTestPackages.pluginJsonOf(valid)
                                .replace("\"kind\":\"TEAM\"", "\"kind\":\"UNKNOWN\"")))
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("ycps_package_invalid");

        assertThat(memberRows(id)).isZero();
        assertThat(MAPPER.readTree(get(BASE + "/" + id)).get("manifest").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- V6: seed once

    @Test
    void seedsMembersOnlyOnTheFirstUploadAndNeverOverwritesTheAdminRoster() throws Exception {
        UUID id = createExpert("content-distribution", "内容分发专家团", "TEAM");

        upload(id, "1.0.0", teamPackage(id, "1.0.0")).expectStatus().isOk();
        assertThat(auditDetail(id, "skill.version.upload").get("membersSeeded").asBoolean()).isTrue();

        // 1) the admin edits the roster ...
        putJson(BASE + "/" + id + "/members", """
                {"members":[
                  {"memberKey":"content-distribution-team-lead","name":"安拓改",
                   "profession":"分发策略师","role":"LEAD","sortOrder":5}
                ]}
                """).expectStatus().isOk().expectBody().jsonPath("$.memberCount").isEqualTo(1);

        // 2) ... then the same package (same version number is a conflict, so a new revision of it)
        //    is uploaded again: members MUST stay on the admin version (E-03/D3).
        upload(id, "2.0.0", teamPackage(id, "2.0.0")).expectStatus().isOk();

        JsonNode skill = MAPPER.readTree(get(BASE + "/" + id));
        assertThat(skill.get("members").size()).isEqualTo(1);
        assertThat(skill.get("members").get(0).get("name").asText()).isEqualTo("安拓改");
        assertThat(skill.get("members").get(0).get("sortOrder").asInt()).isEqualTo(5);
        assertThat(memberRows(id)).isEqualTo(1);

        // The manifest itself is refreshed (it describes the package that is now staged) while the
        // audit says the roster was NOT seeded.
        assertThat(skill.get("manifest").get("extensions").get("com.yuzhihuihe.ycps")
                .get("kind").asText()).isEqualTo("TEAM");
        JsonNode second = auditDetailFor(id, "skill.version.upload", "2.0.0");
        assertThat(second.get("membersSeeded").asBoolean()).isFalse();
        assertThat(second.get("manifestStored").asBoolean()).isTrue();
        assertThat(second.get("memberCount").asLong()).isEqualTo(1L);
    }

    @Test
    void seedsTheSingleRuntimeAgentOfAnExpertPackage() throws Exception {
        // E-09: a declared expert without a localized roster is legal; the runtime agent is it.
        UUID id = createExpert("solo-expert", "单人专家", "EXPERT");

        upload(id, "1.0.0", expertPackage(id, "1.0.0")).expectStatus().isOk();

        JsonNode skill = MAPPER.readTree(get(BASE + "/" + id));
        assertThat(skill.get("kind").asText()).isEqualTo("EXPERT");
        assertThat(skill.get("members").size()).isEqualTo(1);
        assertThat(skill.get("memberCount").asInt()).isEqualTo(1);
        assertThat(skill.get("members").get(0).get("role").asText()).isEqualTo("LEAD");
        assertThat(skill.get("manifest").get("extensions").get("com.yuzhihuihe.ycps")
                .get("kind").asText()).isEqualTo("EXPERT");
        assertThat(auditDetail(id, "skill.version.upload").get("membersSeeded").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private WebTestClient.ResponseSpec upload(UUID id, String version, byte[] packageBytes) {
        return webTestClient.post().uri(BASE + "/" + id + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(packageForm(packageBytes, version)))
                .exchange();
    }

    private String skillCode(UUID id) {
        String code = databaseClient.sql("SELECT code FROM gateway.skill WHERE id = :id")
                .bind("id", id)
                .map((row, metadata) -> row.get("code", String.class))
                .one()
                .block();
        if (code == null || code.isBlank()) {
            throw new IllegalStateException("skill " + id + " has no code");
        }
        return code;
    }

    private WebTestClient.ResponseSpec putJson(String path, String body) {
        return webTestClient.put().uri(path)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private UUID createExpert(String code, String name, String kind) throws IOException {
        byte[] created = webTestClient.post().uri(BASE)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"code":"%s","name":"%s","kind":"%s","category":"内容分发",
                         "tags":["专家"],"summary":"多人协作分发","description":"详情"}
                        """.formatted(code, name, kind))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        return UUID.fromString(MAPPER.readTree(created).get("id").asText());
    }

    private byte[] get(String path) {
        return webTestClient.get().uri(path)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
    }

    private JsonNode auditDetail(UUID resourceId, String action) {
        String detail = databaseClient.sql("""
                        SELECT detail::text FROM gateway.admin_operation_audit
                        WHERE resource_id = :resource_id AND action = :action
                        """)
                .bind("resource_id", resourceId.toString())
                .bind("action", action)
                .map((row, metadata) -> row.get(0, String.class))
                .one()
                .block();
        return read(detail);
    }

    /** The audit row of one specific version, so a second upload can be told apart from the first. */
    private JsonNode auditDetailFor(UUID resourceId, String action, String version) {
        String detail = databaseClient.sql("""
                        SELECT detail::text FROM gateway.admin_operation_audit
                        WHERE resource_id = :resource_id AND action = :action
                          AND detail ->> 'version' = :version
                        """)
                .bind("resource_id", resourceId.toString())
                .bind("action", action)
                .bind("version", version)
                .map((row, metadata) -> row.get(0, String.class))
                .one()
                .block();
        return read(detail);
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long memberRows(UUID id) {
        Long count = databaseClient.sql("SELECT count(*) FROM gateway.skill_member WHERE skill_id = :id")
                .bind("id", id)
                .map((row, metadata) -> row.get(0, Long.class))
                .one()
                .block();
        return count == null ? 0L : count;
    }

    private List<String> versionStatuses(UUID id) {
        return databaseClient.sql("""
                        SELECT status FROM gateway.skill_version WHERE skill_id = :id ORDER BY version
                        """)
                .bind("id", id)
                .map((row, metadata) -> row.get("status", String.class))
                .all()
                .collectList()
                .block();
    }

    private String versionFailure(UUID id) {
        return databaseClient.sql("""
                        SELECT scan_report ->> 'reason' AS reason FROM gateway.skill_version
                        WHERE skill_id = :id
                        """)
                .bind("id", id)
                .map((row, metadata) -> row.get("reason", String.class))
                .one()
                .block();
    }

    private byte[] teamPackage(UUID id, String version) {
        return YcpsTestPackages.team(
                skillCode(id),
                version,
                "content-distribution-team-lead",
                List.of("content-distribution-editor", "content-distribution-analyst"));
    }

    private byte[] expertPackage(UUID id, String version) {
        return YcpsTestPackages.expert(skillCode(id), version);
    }

    private static MultiValueMap<String, HttpEntity<?>> packageForm(byte[] packageBytes, String version) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(packageBytes) {
            @Override
            public String getFilename() {
                return "skill-package.ycps.zip";
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);
        builder.part("version", version);
        builder.part("changelog", "专家包上传");
        return builder.build();
    }

    /**
     * A27 carries only the archive: the whole point of the call is that no target exists yet, so
     * there is no version or changelog to send.
     */
    private static MultiValueMap<String, HttpEntity<?>> inspectForm(byte[] packageBytes) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(packageBytes) {
            @Override
            public String getFilename() {
                return "skill-package.ycps.zip";
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);
        return builder.build();
    }

    /** Row count helper for the "inspection writes nothing" assertions. */
    private long count(String sql) {
        Long value = databaseClient.sql(sql)
                .map((row, metadata) -> row.get(0, Long.class))
                .one()
                .block();
        return value == null ? 0L : value;
    }

    /**
     * A22. The code namespace is shared by all three kinds, so a clash must name the holder:
     * "pick another code" is not actionable when the operator cannot tell what owns it.
     */
    @Test
    void reportsTheHolderWhenACodeIsAlreadyTakenByAnotherKind() throws Exception {
        UUID skillId = createExpert("content-writer", "内容生成助手", "SKILL");
        upload(skillId, "1.0.0", YcpsTestPackages.skill("content-writer", "1.0.0"))
                .expectStatus().isOk();

        // An EXPERT that wants the same code is refused, and the answer says a SKILL holds it.
        webTestClient.post().uri(BASE)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"code":"content-writer","name":"内容创作专家","kind":"EXPERT","tags":[]}
                        """)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_code_conflict")
                .jsonPath("$.details.occupiedBy.kind").isEqualTo("SKILL")
                .jsonPath("$.details.occupiedBy.id").isEqualTo(skillId.toString());
    }

    /** A22. The pre-flight answer the console uses before rendering the upload step. */
    @Test
    void answersCodeAvailabilityBeforeAnUploadIsAttempted() throws Exception {
        UUID taken = createExpert("taken-code", "已占用", "SKILL");

        webTestClient.get().uri(BASE + "/code-availability?code=taken-code")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo("taken-code")
                .jsonPath("$.available").isEqualTo(false)
                .jsonPath("$.occupiedBy.id").isEqualTo(taken.toString())
                .jsonPath("$.occupiedBy.kind").isEqualTo("SKILL");

        // A free code answers with no holder at all rather than a null-filled object.
        webTestClient.get().uri(BASE + "/code-availability?code=free-code")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.available").isEqualTo(true)
                .jsonPath("$.occupiedBy").doesNotExist();
    }

    /**
     * A21. Both directions in one read: what the package binds, and what binds it. A19 needs a
     * versionId first and A20 only answers the reverse, so neither can back a relations panel.
     */
    @Test
    void listsBothDirectionsOfAPackagesRelations() throws Exception {
        // The dependency must be published first: the closure resolves against live targets, and
        // the edge locks the target's real version/revision/sha256 rather than a placeholder.
        UUID helper = createExpert("helper-skill", "被依赖能力", "SKILL");
        upload(helper, "1.0.0", YcpsTestPackages.skill("helper-skill", "1.0.0"))
                .expectStatus().isOk();
        webTestClient.post().uri(BASE + "/" + helper + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();

        // A3 returns the version list; the edge must lock the target's real sha256.
        JsonNode helperDetail = MAPPER.readTree(get(BASE + "/" + helper));
        String helperSha = helperDetail.get("versions").get(0).get("sha256").asText();

        UUID owner = createExpert("binding-skill", "依赖方能力", "SKILL");
        upload(owner, "1.0.0", YcpsTestPackages.skillWithResolvableDependency(
                        "binding-skill", "1.0.0", "helper-skill", "1.0.0", 1, helperSha))
                .expectStatus().isOk();
        webTestClient.post().uri(BASE + "/" + owner + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();

        // Forward: the owner reports the exact edge it locks.
        webTestClient.get().uri(BASE + "/" + owner + "/relations")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo("binding-skill")
                .jsonPath("$.version").isEqualTo("1.0.0")
                .jsonPath("$.dependsOn.length()").isEqualTo(1)
                .jsonPath("$.dependsOn[0].code").isEqualTo("helper-skill")
                .jsonPath("$.dependsOn[0].kind").isEqualTo("SKILL")
                .jsonPath("$.dependsOn[0].relation").isEqualTo("SKILL_BINDING")
                .jsonPath("$.dependedOnBy.length()").isEqualTo(0);

        // Reverse: the target names the owner, which is the impact view for unpublish/delete.
        webTestClient.get().uri(BASE + "/" + helper + "/relations")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo("helper-skill")
                .jsonPath("$.dependsOn.length()").isEqualTo(0)
                .jsonPath("$.dependedOnBy.length()").isEqualTo(1)
                .jsonPath("$.dependedOnBy[0].ownerCode").isEqualTo("binding-skill");
    }

    /**
     * G4. The list must answer "is this package safe to take down?" without opening each package's
     * relation drawer, so both counts are aggregated in the same read as the page itself.
     */
    @Test
    void aggregatesRelationCountsIntoThePackageList() throws Exception {
        UUID helper = createExpert("counted-helper", "被引用能力", "SKILL");
        upload(helper, "1.0.0", YcpsTestPackages.skill("counted-helper", "1.0.0"))
                .expectStatus().isOk();
        webTestClient.post().uri(BASE + "/" + helper + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();
        String helperSha = MAPPER.readTree(get(BASE + "/" + helper))
                .get("versions").get(0).get("sha256").asText();

        UUID owner = createExpert("counted-owner", "引用方能力", "SKILL");
        upload(owner, "1.0.0", YcpsTestPackages.skillWithResolvableDependency(
                        "counted-owner", "1.0.0", "counted-helper", "1.0.0", 1, helperSha))
                .expectStatus().isOk();
        webTestClient.post().uri(BASE + "/" + owner + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();

        JsonNode list = MAPPER.readTree(get(BASE));
        JsonNode ownerRow = listRow(list, "counted-owner");
        JsonNode helperRow = listRow(list, "counted-helper");

        // Out-degree: only the published revision's own locked edges count.
        assertThat(ownerRow.get("dependencyCount").asInt()).isEqualTo(1);
        assertThat(ownerRow.get("dependentCount").asInt()).isEqualTo(0);
        // In-degree: every usable revision elsewhere that locks this code counts.
        assertThat(helperRow.get("dependencyCount").asInt()).isEqualTo(0);
        assertThat(helperRow.get("dependentCount").asInt()).isEqualTo(1);

        // The detail read agrees with the list, so the two surfaces can never disagree.
        JsonNode helperDetail = MAPPER.readTree(get(BASE + "/" + helper));
        assertThat(helperDetail.get("dependentCount").asInt()).isEqualTo(1);
        JsonNode ownerDetail = MAPPER.readTree(get(BASE + "/" + owner));
        assertThat(ownerDetail.get("dependencyCount").asInt()).isEqualTo(1);
    }

    /** The list is paginated, so a count assertion must read the row by code, not by position. */
    private static JsonNode listRow(JsonNode page, String code) {
        for (JsonNode item : page.get("items")) {
            if (code.equals(item.get("code").asText())) {
                return item;
            }
        }
        throw new AssertionError("no list row for code " + code);
    }

    // ---------------------------------------------------------------- A27: package pre-inspection

    /**
     * A27. The create form reads a package before any record exists, so the operator confirms the
     * archive's own identity instead of retyping it — and a typo can no longer reach the upload path
     * as a confusing {@code package_identity_mismatch}.
     *
     * <p>Asserts the whole projection, because the console copies these fields verbatim: a field that
     * silently stopped being populated would show up as an empty form rather than a failure.
     */
    @Test
    void readsAPackageIdentityAndCatalogCopyWithoutCreatingAnything() throws Exception {
        byte[] packageBytes = YcpsTestPackages.skill(
                "inspect-me", "2.1.0", "Prefill summary", "# Inspect Me\n");
        // A package that ships both languages: the console must see the zh and en copy separately.
        packageBytes = YcpsTestPackages.replace(packageBytes, "skills/inspect-me/SKILL.md", """
                ---
                name: inspect-me
                description: 默认语言简介
                metadata:
                  title.zh: '中文标题'
                  title.en: 'English Title'
                  summary.en: 'English summary copy.'
                ---

                # Inspect Me
                """);
        // keywords become prefilled tags; one entry exceeds the console's 64-char tag cap and one is
        // blank, so the projection must drop both rather than truncate or emit an empty tag.
        packageBytes = YcpsTestPackages.replace(packageBytes, "plugin.json", """
                {"$schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json",
                 "name":"inspect-me","version":"2.1.0","description":"Prefill summary",
                 "author":{"name":"Yuzhi Tests"},"license":"MIT",
                 "keywords":["summary","minutes","   ","%s"],
                 "extensions":{"com.yuzhihuihe.ycps":{"ycpsVersion":"1.0.0","kind":"SKILL",
                 "entrySkill":"inspect-me","manifest":"./com.yuzhihuihe.ycps/manifest.json",
                 "requiredFeatures":[]}}}
                """.formatted("x".repeat(65)));

        webTestClient.post().uri(BASE + "/inspect-package")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(inspectForm(packageBytes)))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo("inspect-me")
                .jsonPath("$.version").isEqualTo("2.1.0")
                .jsonPath("$.kind").isEqualTo("SKILL")
                .jsonPath("$.title").isEqualTo("中文标题")
                .jsonPath("$.titleEn").isEqualTo("English Title")
                // The bare description seeds the default language; the ingest fan-out copies the
                // one-line blurb into both the summary and description columns.
                .jsonPath("$.summary").isEqualTo("默认语言简介")
                .jsonPath("$.description").isEqualTo("默认语言简介")
                .jsonPath("$.summaryEn").isEqualTo("English summary copy.")
                .jsonPath("$.descriptionEn").isEqualTo("English summary copy.")
                .jsonPath("$.keywords.length()").isEqualTo(2)
                .jsonPath("$.keywords[0]").isEqualTo("summary")
                .jsonPath("$.keywords[1]").isEqualTo("minutes")
                .jsonPath("$.codeAvailable").isEqualTo(true)
                .jsonPath("$.occupiedBy").doesNotExist()
                .jsonPath("$.fileCount").isEqualTo(3);

        // Strictly a read: no skill row, no version row, no audit entry.
        assertThat(count("SELECT count(*) FROM gateway.skill")).isZero();
        assertThat(count("SELECT count(*) FROM gateway.skill_version")).isZero();
        assertThat(count("SELECT count(*) FROM gateway.admin_operation_audit")).isZero();
    }

    /**
     * A27. A code clash is the one conflict an archive cannot reveal about itself, so the same round
     * trip answers A22 — including the holder, because "pick another code" is not actionable when the
     * operator cannot tell what already owns it.
     */
    @Test
    void reportsTheCodeClashInTheSameInspection() throws Exception {
        createExpert("already-taken", "已有记录", "EXPERT");

        webTestClient.post().uri(BASE + "/inspect-package")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        inspectForm(YcpsTestPackages.skill("already-taken", "1.0.0"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo("already-taken")
                .jsonPath("$.codeAvailable").isEqualTo(false)
                .jsonPath("$.occupiedBy.code").isEqualTo("already-taken")
                .jsonPath("$.occupiedBy.kind").isEqualTo("EXPERT")
                .jsonPath("$.occupiedBy.name").isEqualTo("已有记录");
    }

    /**
     * A27. An invalid archive is refused with the same codes the upload path raises, so the operator
     * learns why a package will not be accepted before having typed anything.
     */
    @Test
    void refusesAnInvalidArchiveWithTheUploadErrorCodes() throws Exception {
        webTestClient.post().uri(BASE + "/inspect-package")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(inspectForm(
                        YcpsTestPackages.without(
                                YcpsTestPackages.skill("broken", "1.0.0"), "plugin.json"))))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("ycps_package_invalid");

        assertThat(count("SELECT count(*) FROM gateway.skill")).isZero();
    }

    /** A21. A draft package has no edges yet but may already be depended upon. */
    @Test
    void answersRelationsForAPackageWithNoPublishedRevision() throws Exception {
        UUID id = createExpert("draft-only", "未发布", "SKILL");

        webTestClient.get().uri(BASE + "/" + id + "/relations")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo("draft-only")
                .jsonPath("$.version").doesNotExist()
                .jsonPath("$.dependsOn.length()").isEqualTo(0)
                .jsonPath("$.dependedOnBy.length()").isEqualTo(0);
    }

    // ---------------------------------------------------------------- A23-A25: category dictionary

    /**
     * A25. Renaming a category must migrate the packages that still carry the old name in the same
     * transaction: renaming only the dictionary would leave those packages pointing at a label the
     * console no longer offers, so they would silently fall out of every group.
     */
    @Test
    void renamesACategoryAndRepointsEveryPackageStillCarryingIt() throws Exception {
        // A23 first: the category must exist in the dictionary before it can be renamed.
        webTestClient.post().uri(BASE + "/categories")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"kind":"SKILL","code":"rename-me","name":"内容分发"}
                        """)
                .exchange()
                .expectStatus().isCreated();

        // createExpert files both packages under 内容分发, so the rename has to move both.
        UUID kept = createExpert("renamed-owner", "改名归属包", "SKILL");
        UUID moved = createExpert("renamed-mover", "待迁移包", "SKILL");
        assertThat(MAPPER.readTree(get(BASE + "/" + kept)).get("category").asText())
                .isEqualTo("内容分发");

        // The dictionary row is renamed through A25; code stays the stable identifier.
        webTestClient.put().uri(BASE + "/categories/rename-me?kind=SKILL")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"新分类名","nameEn":"New Category"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo("rename-me")
                .jsonPath("$.name").isEqualTo("新分类名")
                .jsonPath("$.nameEn").isEqualTo("New Category");

        // Every live package still carrying the old name moved with it.
        assertThat(MAPPER.readTree(get(BASE + "/" + kept)).get("category").asText())
                .isEqualTo("新分类名");
        assertThat(MAPPER.readTree(get(BASE + "/" + moved)).get("category").asText())
                .isEqualTo("新分类名");

        // The renamed category is still occupied, so it is still refused for deletion (A24).
        webTestClient.delete().uri(BASE + "/categories/rename-me?kind=SKILL")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_category_in_use");
    }

    /**
     * A25. A rename onto a name another live category already owns is a readable 409 rather than a raw
     * constraint error, because the grouping would otherwise be ambiguous.
     */
    @Test
    void refusesARenameOntoAnExistingCategoryName() throws Exception {
        webTestClient.post().uri(BASE + "/categories")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"kind":"SKILL","code":"taken-name","name":"已被占用的名字"}
                        """)
                .exchange()
                .expectStatus().isCreated();

        webTestClient.post().uri(BASE + "/categories")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"kind":"SKILL","code":"free-name","name":"可以改的名字"}
                        """)
                .exchange()
                .expectStatus().isCreated();

        webTestClient.put().uri(BASE + "/categories/free-name?kind=SKILL")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"已被占用的名字"}
                        """)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_category_name_conflict");

        // An unknown code is a 404 instead of creating anything.
        webTestClient.put().uri(BASE + "/categories/missing-code?kind=SKILL")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"随便改个名字"}
                        """)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("skill_category_not_found");
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
