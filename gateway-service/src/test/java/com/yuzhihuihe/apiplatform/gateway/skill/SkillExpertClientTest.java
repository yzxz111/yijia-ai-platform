package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
 * Full-stack contract test for the client half of the expert upgrade (2026-09-14 spec 3.2 / V9):
 * C1/C2 carry {@code kind} and the English overlay, C2 carries {@code members[]} with the
 * API-key avatar URL, and {@code GET /v1/skills/{code}/members/{key}/avatar} serves bytes to a key
 * holder only.
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
                "security.api-key.pepper=skill-expert-client-pepper",
                "security.internal.hmac-secret=skill-expert-client-secret",
                "gateway.economics.outbox.enabled=false",
                "content-safety.outbox.enabled=false",
                "gateway.skills.source=database"
        })
@Import(SkillExpertClientTest.ObjectStoreConfiguration.class)
class SkillExpertClientTest {

    private static final String ADMIN_TOKEN = "skill-expert-client-admin-token";
    private static final String API_KEY = "sk-yzh-expert-client-key-0000000000000000";
    private static final String ADMIN_BASE = "/console/gateway/admin/skills";
    private static final String CLIENT_BASE = "/v1/skills";
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
        FileObjectStore skillExpertClientObjectStore() {
            return new FakeSkillObjectStore();
        }
    }

    @Autowired
    WebTestClient webTestClient;

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
    AccountEnforcementService accountEnforcementService;

    @MockitoBean
    ReactiveStringRedisTemplate redis;

    @MockitoBean
    ReactiveValueOperations<String, String> redisValues;

    private final UUID apiKeyId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

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
        when(apiKeyService.authenticate(any())).thenReturn(Mono.empty());
        when(apiKeyService.authenticate(API_KEY)).thenReturn(Mono.just(new ApiKeyService.ApiKeyContext(
                apiKeyId, tenantId, userId, UUID.randomUUID(), Set.of("text-basic"))));
        when(accountEnforcementService.current(any(), any())).thenReturn(Mono.just(
                new AccountEnforcementService.Enforcement("CLEARED", null, null)));
        when(redis.opsForValue()).thenReturn(redisValues);
        when(redisValues.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));
    }

    // ---------------------------------------------------------------- V9

    @Test
    void servesTheExpertFieldsAndRosterThroughTheApiKeyCatalog() throws Exception {
        UUID id = seedPublishedExpert();
        seedRoster(id);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 3, 1, 4, 1, 5};
        uploadMemberExpertIcon("content-team-lead", png);

        // C1: the list exposes kind/titleEn/summaryEn/descriptionEn/memberCount but no roster.
        // The member experts are published packages too, so they appear in the list as well —
        // select the team by code instead of relying on its position.
        JsonNode list = clientJson(CLIENT_BASE);
        JsonNode item = null;
        for (JsonNode candidate : list.get("data")) {
            if ("content-team".equals(candidate.get("name").asText())) {
                item = candidate;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.get("title").asText()).isEqualTo("内容分发专家团");
        assertThat(item.get("titleEn").asText()).isEqualTo("Content Distribution Team");
        assertThat(item.get("summaryEn").asText()).isEqualTo("Multi-agent distribution");
        assertThat(item.get("descriptionEn").asText()).isEqualTo("English description");
        assertThat(item.get("kind").asText()).isEqualTo("TEAM");
        assertThat(item.get("memberCount").asInt()).isEqualTo(2);
        assertThat(item.has("members")).isFalse();

        // C2: title stays the default column, titleEn is the overlay (V3 fallback is client side).
        JsonNode detail = clientJson(CLIENT_BASE + "/content-team");
        assertThat(detail.get("object").asText()).isEqualTo("skill");
        assertThat(detail.get("name").asText()).isEqualTo("content-team");
        assertThat(detail.get("title").asText()).isEqualTo("内容分发专家团");
        assertThat(detail.get("titleEn").asText()).isEqualTo("Content Distribution Team");
        assertThat(detail.get("summary").asText()).isEqualTo("多人协作分发");
        assertThat(detail.get("summaryEn").asText()).isEqualTo("Multi-agent distribution");
        assertThat(detail.get("descriptionEn").asText()).isEqualTo("English description");
        assertThat(detail.get("kind").asText()).isEqualTo("TEAM");

        // C2 members[] mirrors the admin shape, with the /v1 avatar URL.
        assertThat(detail.get("memberCount").asInt()).isEqualTo(2);
        JsonNode members = detail.get("members");
        assertThat(members.size()).isEqualTo(2);
        assertThat(members.get(0).get("memberKey").asText()).isEqualTo("content-team-lead");
        assertThat(members.get(0).get("role").asText()).isEqualTo("LEAD");
        assertThat(members.get(0).get("name").asText()).isEqualTo("安拓");
        assertThat(members.get(0).get("nameEn").asText()).isEqualTo("Atlas");
        assertThat(members.get(0).get("profession").asText()).isEqualTo("分发策略师");
        assertThat(members.get(0).get("professionEn").asText()).isEqualTo("Distribution Strategist");
        assertThat(members.get(0).get("sortOrder").asInt()).isZero();
        // The portrait is the referenced expert package's own icon.
        assertThat(members.get(0).get("avatarUrl").asText())
                .isEqualTo("/v1/skills/content-team-lead/icon");
        assertThat(members.get(1).get("memberKey").asText()).isEqualTo("content-team-editor");
        assertThat(members.get(1).get("avatarUrl").asText())
                .isEqualTo("/v1/skills/content-team-editor/icon");

        // The icon bytes are reachable with an API key, byte-exact.
        webTestClient.get().uri(CLIENT_BASE + "/content-team-lead/icon")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_PNG)
                .expectHeader().exists("x-request-id")
                .expectBody(byte[].class)
                .isEqualTo(png);
    }

    @Test
    void projectsCompactExpertCapabilitiesInTheListAndTheCompletePresentationInDetail()
            throws Exception {
        UUID presentationId = seedPublishedPresentationExpert();
        seedMemberExpert("legacy-expert", "旧版专家");
        seedPublishedPlainSkill();
        savePresentation(presentationId);

        JsonNode list = clientJson(CLIENT_BASE + "?kind=EXPERT&limit=10");
        JsonNode presentation = findSkill(list, "presentation-expert");
        assertThat(presentation.get("taskTypes").toString()).isEqualTo("[\"文章写作\"]");
        assertThat(presentation.get("taskTypesEn").toString()).isEqualTo("[\"Article writing\"]");
        assertThat(presentation.has("expertPresentation")).isFalse();
        assertThat(presentation.has("preview")).isFalse();

        JsonNode detail = clientJson(CLIENT_BASE + "/presentation-expert");
        JsonNode expertPresentation = detail.get("expertPresentation");
        assertThat(expertPresentation).isNotNull();
        assertThat(expertPresentation.get("taskTypes").toString())
                .isEqualTo("[\"文章写作\"]");
        assertThat(expertPresentation.get("preview").get("outputParagraphs").get(0).asText())
                .isEqualTo("输出一段介绍。");
        assertThat(expertPresentation.get("assets").get("icon").asText()).isEmpty();
        assertThat(expertPresentation.get("assets").get("illustration").asText()).isEmpty();
        assertThat(expertPresentation.get("assets").get("exampleImage").asText()).isEmpty();

        JsonNode legacyDetail = clientJson(CLIENT_BASE + "/legacy-expert");
        assertThat(legacyDetail.has("expertPresentation")).isFalse();

        JsonNode skillDetail = clientJson(CLIENT_BASE + "/article-writer");
        assertThat(skillDetail.get("taskTypes").size()).isZero();
        assertThat(skillDetail.has("expertPresentation")).isFalse();
    }

    @Test
    void servesPublishedAndUnpublishedPresentationAnonymouslyButKeepsPackageOperationsProtected()
            throws Exception {
        UUID presentationId = seedPublishedPresentationExpert();
        savePresentation(presentationId);

        webTestClient.get().uri(CLIENT_BASE + "/presentation-expert/expert-presentation")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo("presentation-expert")
                .jsonPath("$.presentation.usageGuide").isEqualTo("输入主题和受众。")
                .jsonPath("$.presentation.examplePrompts[0]")
                .isEqualTo("请写一段产品介绍。")
                .jsonPath("$.presentation.preview.outputParagraphs[0]")
                .isEqualTo("输出一段介绍。");

        webTestClient.get().uri(CLIENT_BASE + "/presentation-expert")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("authentication_required");
        webTestClient.post().uri(CLIENT_BASE + "/presentation-expert/downloads")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("authentication_required");

        webTestClient.post().uri(ADMIN_BASE + "/" + presentationId + "/unpublish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();
        webTestClient.get().uri(CLIENT_BASE + "/presentation-expert/expert-presentation")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.presentation.usageGuide").isEqualTo("输入主题和受众。")
                .jsonPath("$.presentation.examplePrompts[0]").isEqualTo("请写一段产品介绍。")
                .jsonPath("$.presentation.preview.outputParagraphs[0]").isEqualTo("输出一段介绍。");
        webTestClient.get().uri(CLIENT_BASE + "/presentation-expert")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("skill_not_found");
    }

    @Test
    void servesArtworkAnonymouslyForEveryPublishedCatalogKindButNotDrafts() throws Exception {
        UUID expertId = seedPublishedPresentationExpert();
        UUID skillId = seedPublishedPlainSkill();
        UUID teamId = seedPublishedExpert();
        UUID draftId = seedDraftPlainSkill();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 7};
        for (String role : new String[] {"icon", "illustration", "example-image"}) {
            uploadArtwork(expertId, role, png);
            uploadArtwork(skillId, role, png);
            uploadArtwork(teamId, role, png);
            uploadArtwork(draftId, role, png);

            for (String code : new String[] {"presentation-expert", "article-writer", "content-team"}) {
                webTestClient.get().uri(CLIENT_BASE + "/" + code + "/" + role)
                        .exchange()
                        .expectStatus().isOk()
                        .expectHeader().contentType(MediaType.IMAGE_PNG)
                        .expectBody(byte[].class).isEqualTo(png);
            }

            // Anonymous storefront requests cannot read artwork from a draft.
            webTestClient.get().uri(CLIENT_BASE + "/draft-skill/" + role)
                    .exchange()
                    .expectStatus().isNotFound()
                    .expectBody()
                    .jsonPath("$.error.code").isEqualTo("skill_not_found");

            if ("icon".equals(role)) {
                // Signed-in clients retain their existing access to an unlisted package's icon.
                webTestClient.get().uri(CLIENT_BASE + "/draft-skill/icon")
                        .headers(headers -> headers.setBearerAuth(API_KEY))
                        .exchange()
                        .expectStatus().isOk()
                        .expectHeader().contentType(MediaType.IMAGE_PNG)
                        .expectBody(byte[].class).isEqualTo(png);
            }
        }

        // The public catalog can include UNPUBLISHED Expert cards, which still display their icon.
        webTestClient.post().uri(ADMIN_BASE + "/" + skillId("presentation-expert") + "/unpublish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();
        webTestClient.get().uri(CLIENT_BASE + "/presentation-expert/icon")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_PNG)
                .expectBody(byte[].class).isEqualTo(png);
        for (String role : new String[] {"illustration", "example-image"}) {
            webTestClient.get().uri(CLIENT_BASE + "/presentation-expert/" + role)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentType(MediaType.IMAGE_PNG)
                    .expectBody(byte[].class).isEqualTo(png);
        }
    }

    @Test
    void keepsTheUnfilteredCatalogCompatibleAndFiltersKindsBeforeTheCursorWindow()
            throws Exception {
        // The TEAM is written last and therefore sorts before the SKILL. A one-item SKILL page must
        // still contain the skill, proving that kind filtering precedes cursor pagination.
        seedPublishedPlainSkill();
        seedPublishedExpert();

        JsonNode unfiltered = clientJson(CLIENT_BASE + "?limit=10");
        assertThat(unfiltered.get("data").size()).isEqualTo(2);

        JsonNode skillsOnly = clientJson(CLIENT_BASE + "?kind=skill&limit=1");
        assertThat(skillsOnly.get("data").size()).isEqualTo(1);
        assertThat(skillsOnly.get("data").get(0).get("name").asText())
                .isEqualTo("article-writer");
        assertThat(skillsOnly.get("data").get(0).get("kind").asText()).isEqualTo("SKILL");

        JsonNode experts = clientJson(CLIENT_BASE + "?kind=EXPERT,TEAM&limit=10");
        assertThat(experts.get("data").size()).isEqualTo(1);
        assertThat(experts.get("data").get(0).get("name").asText())
                .isEqualTo("content-team");

        webTestClient.get().uri(CLIENT_BASE + "?kind=UNKNOWN")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_skill_kind");
    }

    @Test
    void servesPublishedPortraitsAnonymouslyAndReportsAMissingOne() throws Exception {
        seedPublishedExpert();
        // The portrait under test is a member expert's own icon, so that package has to exist.
        seedMemberExpert("content-team-lead", "安拓");

        // Storefront artwork is public, but a package without an uploaded portrait still returns 404.
        webTestClient.get().uri(CLIENT_BASE + "/content-team-lead/icon")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("skill_icon_not_found");

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1};
        uploadMemberExpertIcon("content-team-lead", png);
        webTestClient.get().uri(CLIENT_BASE + "/content-team-lead/icon")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_PNG)
                .expectBody(byte[].class)
                .isEqualTo(png);

        // The unpublished Expert remains visible as an unavailable storefront card, so its
        // portrait still needs to load even though package downloads stay blocked.
        webTestClient.post().uri(ADMIN_BASE + "/" + skillId("content-team-lead") + "/unpublish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();

        webTestClient.get().uri(CLIENT_BASE + "/content-team-lead/icon")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_PNG)
                .expectBody(byte[].class).isEqualTo(png);
    }

    @Test
    void hidesDraftExpertsFromTheClientCatalog() throws Exception {
        seedDraftExpert();

        JsonNode list = clientJson(CLIENT_BASE);
        assertThat(list.get("data").size()).isZero();

        webTestClient.get().uri(CLIENT_BASE + "/content-team")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("skill_not_found");
        webTestClient.get().uri(CLIENT_BASE + "/content-team/expert-presentation")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("skill_not_found");
    }

    // ---------------------------------------------------------------- helpers

    private UUID seedPublishedExpert() throws IOException {
        UUID id = seedDraftExpert();
        uploadVersion(id, YcpsTestPackages.team(
                "content-team", "1.0.0", "content-team-lead", List.of("content-team-editor")));
        publish(id);
        return id;
    }

    private UUID seedPublishedPresentationExpert() throws IOException {
        byte[] created = webTestClient.post().uri(ADMIN_BASE)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"code":"presentation-expert","name":"内容写作专家","nameEn":"Writing Expert",
                         "kind":"EXPERT","summary":"写作摘要","summaryEn":"Writing summary",
                         "description":"写作详情","descriptionEn":"Writing description"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        UUID id = UUID.fromString(MAPPER.readTree(created).get("id").asText());
        uploadVersion(id, YcpsTestPackages.expert("presentation-expert", "1.0.0"));
        publish(id);
        return id;
    }

    private void savePresentation(UUID id) throws IOException {
        byte[] current = webTestClient.get().uri(ADMIN_BASE + "/" + id + "/expert-presentation")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        int rowVersion = MAPPER.readTree(current).get("presentation").get("rowVersion").asInt();
        webTestClient.put().uri(ADMIN_BASE + "/" + id + "/expert-presentation")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"taskTypes":["文章写作"],"taskTypesEn":["Article writing"],
                         "usageGuide":"输入主题和受众。","usageGuideEn":"Provide topic and audience.",
                         "examplePrompts":["请写一段产品介绍。"],
                         "examplePromptsEn":["Write a product introduction."],
                         "preview":{"heading":"示例","scene":"营销","request":"请写产品介绍",
                           "material":"产品资料","outputTitle":"输出",
                           "outputParagraphs":["输出一段介绍。"]},
                         "rowVersion":%d}
                        """.formatted(rowVersion))
                .exchange()
                .expectStatus().isOk();
    }

    private static JsonNode findSkill(JsonNode list, String code) {
        for (JsonNode item : list.get("data")) {
            if (code.equals(item.get("name").asText())) return item;
        }
        throw new AssertionError("missing skill " + code);
    }

    /**
     * Publishes one EXPERT so a roster can reference it.
     *
     * <p>A roster entry names another expert package (memberKey == its code) and the service rejects
     * keys that are not published experts, so the members the team helper names have to exist.
     */
    private void seedMemberExpert(String code, String name) throws IOException {
        byte[] created = webTestClient.post().uri(ADMIN_BASE)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"code":"%s","name":"%s","kind":"EXPERT"}
                        """.formatted(code, name))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        UUID id = UUID.fromString(MAPPER.readTree(created).get("id").asText());
        uploadVersion(id, YcpsTestPackages.expert(code, "1.0.0"));
        publish(id);
    }

    private UUID seedPublishedPlainSkill() throws IOException {
        byte[] created = webTestClient.post().uri(ADMIN_BASE)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"code":"article-writer","name":"文章写作","kind":"SKILL"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        UUID id = UUID.fromString(MAPPER.readTree(created).get("id").asText());
        uploadVersion(id, YcpsTestPackages.skill("article-writer", "1.0.0"));
        publish(id);
        return id;
    }

    private UUID seedDraftPlainSkill() throws IOException {
        byte[] created = webTestClient.post().uri(ADMIN_BASE)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"code":"draft-skill","name":"Draft skill","kind":"SKILL"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        return UUID.fromString(MAPPER.readTree(created).get("id").asText());
    }

    private UUID seedDraftExpert() throws IOException {
        byte[] created = webTestClient.post().uri(ADMIN_BASE)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"code":"content-team","name":"内容分发专家团","nameEn":"Content Distribution Team",
                         "kind":"TEAM","category":"内容创作","tags":["专家"],
                         "summary":"多人协作分发","summaryEn":"Multi-agent distribution",
                         "description":"详情","descriptionEn":"English description"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        UUID id = UUID.fromString(MAPPER.readTree(created).get("id").asText());
        return id;
    }

    /**
     * Replaces the team's roster with two published member experts.
     *
     * <p>Kept out of {@link #seedDraftExpert()} on purpose: the two seeded experts show up in the
     * client catalog, which the catalog-window tests count. Only the tests that assert the roster
     * itself should call this.
     */
    private void seedRoster(UUID teamId) throws IOException {
        seedMemberExpert("content-team-lead", "安拓");
        seedMemberExpert("content-team-editor", "林禾");
        webTestClient.put().uri(ADMIN_BASE + "/" + teamId + "/members")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"members":[
                          {"memberKey":"content-team-lead","name":"安拓","nameEn":"Atlas",
                           "profession":"分发策略师","professionEn":"Distribution Strategist",
                           "role":"LEAD","sortOrder":0},
                          {"memberKey":"content-team-editor","name":"林禾","nameEn":"Lin He",
                           "profession":"内容编辑","professionEn":"Content Editor",
                           "role":"MEMBER","sortOrder":1}
                        ]}
                        """)
                .exchange()
                .expectStatus().isOk();
    }

    private void uploadVersion(UUID id, byte[] packageBytes) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(packageBytes) {
            @Override
            public String getFilename() {
                return "skill-package.ycps.zip";
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);
        builder.part("version", "1.0.0");
        MultiValueMap<String, HttpEntity<?>> form = builder.build();
        webTestClient.post().uri(ADMIN_BASE + "/" + id + "/versions")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(form))
                .exchange()
                .expectStatus().isOk();
    }

    private void publish(UUID id) {
        webTestClient.post().uri(ADMIN_BASE + "/" + id + "/publish")
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * Uploads the icon of one expert package.
     *
     * <p>A roster member's portrait IS that expert's icon, so seeding a member portrait means
     * uploading an icon on the member's own package — there is no per-member avatar any more.
     */
    private void uploadMemberExpertIcon(String memberKey, byte[] content) {
        uploadArtwork(skillId(memberKey), "icon", content);
    }

    private void uploadArtwork(UUID skillId, String role, byte[] content) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return role + ".png";
            }
        }).contentType(MediaType.IMAGE_PNG);
        webTestClient.post().uri(ADMIN_BASE + "/" + skillId + "/" + role)
                .headers(headers -> headers.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk();
    }

    /** Looks up a package id by code, so tests can address it on the admin surface. */
    private UUID skillId(String code) {
        return databaseClient.sql("SELECT id FROM gateway.skill WHERE code = :code")
                .bind("code", code)
                .map((row, metadata) -> row.get("id", UUID.class))
                .one()
                .block();
    }

    private JsonNode clientJson(String path) throws IOException {
        byte[] body = webTestClient.get().uri(path)
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .header("X-Client-Version", CLIENT_VERSION)
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        return MAPPER.readTree(body);
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
