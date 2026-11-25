package com.yuzhihuihe.apiplatform.platform.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.identity.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false"
})
@AutoConfigureMockMvc
class LegalDocumentApiTest {

    private static final UUID ACTOR = UUID.fromString("70000000-0000-4000-8000-0000000000aa");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenService jwtTokens;

    @BeforeEach
    void resetDocuments() {
        // 已发布的行按设计是不可变的，因此重置时只降级并删除本测试
        // 所拥有的行；每个测试类用一个全新容器让这一操作开销很小。
        jdbc.update("""
                UPDATE platform.legal_document_version
                SET published = false, unpublished_at = now(), updated_at = now()
                WHERE published
                """);
        jdbc.update("""
                DELETE FROM platform.legal_document_version
                WHERE version LIKE '2026-%' OR version LIKE 'v%'
                """);
        String content = """
                {"placeholder":"placeholder","sections":[{"heading":"一、种子条款","paragraphs":["seed"]}]}
                """;
        jdbc.update("""
                INSERT INTO platform.legal_document_version(
                    kind, version, title, content, published, published_at)
                VALUES ('TERMS', '2026-01-01', '平台服务协议', ?::jsonb, true, now()),
                       ('PRIVACY', '2026-01-01', '隐私政策', ?::jsonb, true, now())
                """, content, content);
        jdbc.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, display_name, status)
                VALUES (?, ?, 'legal-admin@example.com', 'Legal Admin', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """, ACTOR, ACTOR);
    }

    @Test
    void servesThePublishedDocumentsToAnonymousCallers() throws Exception {
        mockMvc.perform(get("/console/legal/TERMS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("TERMS"))
                .andExpect(jsonPath("$.version").value("2026-01-01"))
                .andExpect(jsonPath("$.title").value("平台服务协议"))
                .andExpect(jsonPath("$.content.sections[0].heading").value("一、种子条款"));

        mockMvc.perform(get("/console/legal/PRIVACY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("PRIVACY"));

        mockMvc.perform(get("/console/legal/NOPE"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void savingADraftOverwritesOnlyTheDraftAndPublishingCreatesANewVersion() throws Exception {
        String draft = """
                {"title":"平台服务协议（修订版）","content":{"placeholder":"p",
                 "sections":[{"heading":"一、新条款","paragraphs":["new"],"items":["a","b"]}]}}
                """;
        mockMvc.perform(put("/console/platform/admin/legal-documents/TERMS/draft")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draft))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].draft.title").value("平台服务协议（修订版）"))
                .andExpect(jsonPath("$.documents[0].published.version").value("2026-01-01"));

        // 保存草稿不会改动已发布的文本。
        mockMvc.perform(get("/console/legal/TERMS"))
                .andExpect(jsonPath("$.version").value("2026-01-01"))
                .andExpect(jsonPath("$.title").value("平台服务协议"));

        String publish = """
                {"version":"2026-02-02","title":"平台服务协议（修订版）","content":{"placeholder":"p",
                 "sections":[{"heading":"一、新条款","paragraphs":["new"]}]}}
                """;
        mockMvc.perform(post("/console/platform/admin/legal-documents/TERMS/publish")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publish))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documents[0].published.version").value("2026-02-02"));

        mockMvc.perform(get("/console/legal/TERMS"))
                .andExpect(jsonPath("$.version").value("2026-02-02"))
                .andExpect(jsonPath("$.content.sections[0].heading").value("一、新条款"));

        // 每种类型只允许一行已发布记录，而此前的版本仍留在历史中。
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.legal_document_version
                WHERE kind = 'TERMS' AND published
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.legal_document_version
                WHERE kind = 'TERMS' AND version = '2026-01-01'
                """, Integer.class)).isOne();
    }

    @Test
    void publishedVersionsAreImmutableInTheDatabaseButCanBeSuperseded() {
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE platform.legal_document_version
                SET title = 'rewritten', content = '{"sections":[]}'::jsonb
                WHERE kind = 'TERMS' AND published
                """))
                .hasMessageContaining("published legal document content is immutable");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM platform.legal_document_version
                WHERE kind = 'TERMS' AND published
                """))
                .hasMessageContaining("published legal document versions cannot be deleted");

        // 取代某个版本是唯一被允许的状态转换：它只翻转发布标志。
        jdbc.update("""
                UPDATE platform.legal_document_version
                SET published = false, updated_at = now()
                WHERE kind = 'TERMS' AND published
                """);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.legal_document_version
                WHERE kind = 'TERMS' AND published
                """, Integer.class)).isZero();
    }

    @Test
    void rejectsReusingAVersionStringAndInvalidContent() throws Exception {
        String publish = """
                {"version":"2026-01-01","title":"平台服务协议","content":{"placeholder":"p",
                 "sections":[{"heading":"一、条款","paragraphs":["x"]}]}}
                """;
        mockMvc.perform(post("/console/platform/admin/legal-documents/TERMS/publish")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publish))
                .andExpect(status().isConflict());

        for (String body : new String[] {
                """
                {"version":"v2","title":"标题","content":{"sections":[]}}
                """,
                """
                {"version":"v2","title":"标题","content":{"placeholder":"p","sections":[{"paragraphs":["x"]}]}}
                """,
                """
                {"version":"","title":"标题","content":{"placeholder":"p","sections":[{"heading":"h","paragraphs":["x"]}]}}
                """,
                """
                {"version":"v3","title":"","content":{"placeholder":"p","sections":[{"heading":"h","paragraphs":["x"]}]}}
                """}) {
            mockMvc.perform(post("/console/platform/admin/legal-documents/TERMS/publish")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    @Test
    void restoresAnEarlierVersionAndAuditsEveryAdministrativeWrite() throws Exception {
        String publish = """
                {"version":"2026-03-03","title":"平台服务协议","content":{"placeholder":"p",
                 "sections":[{"heading":"一、第三版","paragraphs":["3"]}]}}
                """;
        mockMvc.perform(post("/console/platform/admin/legal-documents/TERMS/publish")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publish))
                .andExpect(status().isCreated());

        mockMvc.perform(post(
                        "/console/platform/admin/legal-documents/TERMS/versions/2026-01-01/restore")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].published.version").value("2026-01-01"));

        mockMvc.perform(get("/console/legal/TERMS"))
                .andExpect(jsonPath("$.version").value("2026-01-01"));

        mockMvc.perform(post(
                        "/console/platform/admin/legal-documents/TERMS/versions/2026-01-01/restore")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isConflict());
        mockMvc.perform(post(
                        "/console/platform/admin/legal-documents/TERMS/versions/1999-01-01/restore")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForList("""
                SELECT action FROM platform.admin_audit_log
                WHERE resource_type = 'LEGAL_DOCUMENT' ORDER BY id
                """, String.class))
                .contains("LEGAL_DOCUMENT_PUBLISHED", "LEGAL_DOCUMENT_RESTORED");
    }

    @Test
    void reportsThePublishedVersionThroughTheServiceBoundary() {
        LegalDocumentService service = new LegalDocumentService(
                new LegalDocumentRepository(jdbc), objectMapper);

        assertThat(service.currentVersion(LegalDocumentService.KIND_TERMS))
                .contains("2026-01-01");
        assertThat(service.published(LegalDocumentService.KIND_TERMS).title())
                .isEqualTo("平台服务协议");

        assertThatThrownBy(() -> service.requireCurrentVersion("TERMS", "1999-01-01"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("legal_document_version_stale")
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        service.requireCurrentVersion("TERMS", "2026-01-01");
        service.requireCurrentVersion("TERMS", null);
    }

    @Test
    void rejectsAnonymousAdministrativeAccess() throws Exception {
        mockMvc.perform(get("/console/platform/admin/legal-documents"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/console/platform/admin/legal-documents/TERMS/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"x","content":{"sections":[{"heading":"h","paragraphs":["p"]}]}}
                                """))
                .andExpect(status().isUnauthorized());
    }

    private String adminToken() {
        return jwtTokens.createAccessToken(new com.yuzhihuihe.apiplatform.platform.identity
                .IdentityService.UserProfile(ACTOR, ACTOR, "legal-admin@example.com", "Legal Admin",
                        "ADMIN"));
    }
}
