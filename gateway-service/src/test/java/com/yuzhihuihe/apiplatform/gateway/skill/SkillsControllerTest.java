package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class SkillsControllerTest {

    /** Only {@code sk-yzh-} bearers are API keys; any other bearer is a desktop platform JWT. */
    private static final String API_KEY = "sk-yzh-test-placeholder-not-a-real-secret";
    private static final String SKILLS_PATH = "/v1/skills";

    @TempDir
    Path skillsRoot;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = clientFor(properties(skillsRoot.toString()));
    }

    @Test
    void allowsAnonymousCatalogPreviewButRejectsAnInvalidApiKey() {
        client.get().uri(SKILLS_PATH).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data.length()").isEqualTo(0);

        client.get().uri(SKILLS_PATH)
                .headers(headers -> headers.setBearerAuth("sk-yzh-not-a-real-key"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("authentication_error");
    }

    @Test
    void listsSkillMetadataWithDigestsButWithoutContent() throws IOException {
        writeSkill("demo-skill", "1.2.3", "Demo Skill");

        client.get().uri(SKILLS_PATH)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("x-request-id")
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].name").isEqualTo("demo-skill")
                .jsonPath("$.data[0].title").isEqualTo("demo-skill")
                .jsonPath("$.data[0].description").isEqualTo("Demo Skill")
                .jsonPath("$.data[0].version").isEqualTo("1.2.3")
                .jsonPath("$.data[0].status").isEqualTo("PUBLISHED")
                .jsonPath("$.data[0].packageFormat").isEqualTo("YCPS")
                .jsonPath("$.data[0].md5").isNotEmpty()
                .jsonPath("$.data[0].sha256").isNotEmpty()
                // The rollback source serves the prebuilt archive as one immutable artifact, so the
                // catalog never materializes per-file bodies (C1).
                .jsonPath("$.data[0].fileCount").isEqualTo(0)
                .jsonPath("$.data[0].files.length()").isEqualTo(0);
    }

    @Test
    void filtersTheCatalogBySingleOrCommaSeparatedKindsBeforePagination() throws IOException {
        writeSkill("demo-skill", "1.2.3", "Demo Skill");

        client.get().uri(SKILLS_PATH + "?kind=skill&limit=1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].kind").isEqualTo("SKILL");

        client.get().uri(SKILLS_PATH + "?kind=EXPERT,TEAM")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(0);

        client.get().uri(SKILLS_PATH + "?kind=SKILL,TEAM")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1);
    }

    @Test
    void rejectsAnInvalidCatalogKindWithTheStableClientError() {
        client.get().uri(SKILLS_PATH + "?kind=MCP")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectHeader().exists("x-request-id")
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_skill_kind");
    }

    @Test
    void returnsThePackageDetailWithoutFileBodiesForOneSkill() throws IOException {
        writeSkill("demo-skill", "1.2.3", "Demo Skill");

        client.get().uri(SKILLS_PATH + "/demo-skill")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("skill")
                .jsonPath("$.name").isEqualTo("demo-skill")
                .jsonPath("$.version").isEqualTo("1.2.3")
                .jsonPath("$.description").isEqualTo("Demo Skill")
                .jsonPath("$.kind").isEqualTo("SKILL")
                .jsonPath("$.packageFormat").isEqualTo("YCPS")
                .jsonPath("$.downloadUrl").isEqualTo("/v1/skills/demo-skill/downloads")
                .jsonPath("$.sizeBytes").isNotEmpty()
                .jsonPath("$.manifestSha256").isNotEmpty()
                .jsonPath("$.files.length()").isEqualTo(0);
    }

    @Test
    void returnsAnEmptyCatalogWhenTheSkillRootDoesNotExist() {
        WebTestClient missingRootClient = clientFor(properties(skillsRoot.resolve("absent").toString()));

        missingRootClient.get().uri(SKILLS_PATH)
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data.length()").isEqualTo(0);
    }

    @Test
    void reportsMissingAndMalformedSkillNamesWithStableCodes() {
        client.get().uri(SKILLS_PATH + "/missing-skill")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("skill_not_found");

        client.get().uri(SKILLS_PATH + "/demo%20skill")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_skill_name");
    }

    @Test
    void refusesPackagesAboveTheConfiguredSizeLimit() throws IOException {
        SkillProperties small = properties(skillsRoot.toString());
        small.setMaxPackageBytes(DataSize.ofBytes(4));
        WebTestClient limitedClient = clientFor(small);
        Files.write(skillsRoot.resolve("demo-skill.ycps.zip"),
                YcpsTestPackages.skill("demo-skill", "1.2.3"));

        limitedClient.get().uri(SKILLS_PATH + "/demo-skill")
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("ycps_package_too_large");
    }

    @Test
    void reportsAnInvalidPackageInsteadOfSilentlyServingIt() throws IOException {
        Files.write(skillsRoot.resolve("demo-skill.ycps.zip"),
                "{ not a zip".getBytes(StandardCharsets.UTF_8));

        client.get().uri(SKILLS_PATH)
                .headers(headers -> headers.setBearerAuth(API_KEY))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("ycps_package_invalid");
    }

    @Test
    void neverEscapesTheSkillRoot() {
        SkillCatalogService service = new SkillCatalogService(
                properties(skillsRoot.toString()), new ObjectMapper());

        assertThatThrownBy(() -> service.loadSkill("../secret").block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_skill_name");
    }

    private WebTestClient clientFor(SkillProperties properties) {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        when(apiKeyService.authenticate(any())).thenReturn(Mono.empty());
        when(apiKeyService.authenticate(API_KEY)).thenReturn(Mono.just(new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Set.of("text-basic"))));
        SkillCatalogService catalog = new SkillCatalogService(properties, new ObjectMapper());
        return WebTestClient.bindToController(new SkillsController(catalog))
                .webFilter(new ApiKeyWebFilter(apiKeyService))
                .build();
    }

    private static SkillProperties properties(String root) {
        SkillProperties properties = new SkillProperties();
        properties.setRoot(root);
        // This suite exercises the file-system rollback source (gateway.skills.source), which reads
        // prebuilt .ycps.zip artifacts and never a package directory.
        properties.setSource(SkillProperties.SOURCE_FILESYSTEM);
        return properties;
    }

    private void writeSkill(String name, String version, String description) throws IOException {
        Files.write(skillsRoot.resolve(name + ".ycps.zip"), YcpsTestPackages.skill(
                name, version, description, "# " + name + "\n"));
    }
}
