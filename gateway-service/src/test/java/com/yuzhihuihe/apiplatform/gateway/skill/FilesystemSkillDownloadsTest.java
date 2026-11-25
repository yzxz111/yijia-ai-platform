package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class FilesystemSkillDownloadsTest {

    private static final String API_KEY = "sk-yzh-test-placeholder-not-a-real-secret";

    @TempDir
    Path root;

    private final ObjectMapper mapper = new ObjectMapper();
    private FileObjectStore objectStore;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        SkillProperties properties = new SkillProperties();
        properties.setRoot(root.toString());
        properties.setSource(SkillProperties.SOURCE_FILESYSTEM);
        properties.setDownloadLimit(100);
        SkillCatalogService catalog = filesystemCatalog(properties);
        FilesystemSkillDownloadService filesystem = new FilesystemSkillDownloadService(
                catalog, properties, Clock.systemUTC(), UUID::randomUUID);
        SkillDownloadService downloads = new SkillDownloadService(
                null, null, null, null, properties, null, filesystem);
        objectStore = mock(FileObjectStore.class);

        ApiKeyService apiKeys = mock(ApiKeyService.class);
        when(apiKeys.authenticate(any())).thenReturn(Mono.empty());
        when(apiKeys.authenticate(API_KEY)).thenReturn(Mono.just(new ApiKeyService.ApiKeyContext(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                UUID.fromString("30000000-0000-0000-0000-000000000001"),
                UUID.fromString("40000000-0000-0000-0000-000000000001"),
                Set.of("text-basic"))));
        client = WebTestClient.bindToController(
                        new SkillsController(catalog),
                        new SkillDownloadsController(
                                downloads, new SkillDeviceFingerprint("test-secret"), objectStore))
                .webFilter(new ApiKeyWebFilter(apiKeys))
                .build();
    }

    @Test
    void c1AndC3ToC6UseOneDeterministicFrozenFilesystemPackage() throws Exception {
        writeSkill("article-writer", "1.2.3", "# Original\n");

        JsonNode listed = json(client.get().uri("/v1/skills")
                .headers(this::authenticate)
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody());
        JsonNode summary = listed.path("data").path(0);
        assertThat(summary.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(summary.path("downloadUrl").asText())
                .isEqualTo("/v1/skills/article-writer/downloads");
        assertThat(summary.path("sizeBytes").asLong()).isPositive();
        assertThat(summary.path("md5").asText()).matches("[0-9a-f]{32}");
        assertThat(summary.path("sha256").asText()).matches("[0-9a-f]{64}");

        client.get().uri("/v1/skills/article-writer/versions")
                .headers(this::authenticate)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].version").isEqualTo("1.2.3")
                .jsonPath("$.data[0].sha256").isEqualTo(summary.path("sha256").asText());

        JsonNode session = json(client.post().uri("/v1/skills/article-writer/downloads")
                .headers(headers -> {
                    authenticate(headers);
                    headers.set("Idempotency-Key", "filesystem-session");
                    headers.set("X-Client-Version", "2026.9.15");
                })
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody());
        assertThat(session.path("md5").asText()).isEqualTo(summary.path("md5").asText());
        assertThat(session.path("sha256").asText()).isEqualTo(summary.path("sha256").asText());
        assertThat(session.path("sizeBytes").asLong()).isEqualTo(summary.path("sizeBytes").asLong());

        byte[] original = client.get().uri(session.path("url").asText())
                .headers(this::authenticate)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ETAG,
                        "\"" + session.path("sha256").asText() + "\"")
                .expectBody().returnResult().getResponseBody();
        assertThat(original).isNotNull();
        assertThat(digest("MD5", original)).isEqualTo(session.path("md5").asText());
        assertThat(digest("SHA-256", original)).isEqualTo(session.path("sha256").asText());
        assertThat(zipEntries(original)).containsExactly(
                "plugin.json", "com.yuzhihuihe.ycps/manifest.json",
                "skills/article-writer/SKILL.md");

        // Replacing the prebuilt archive updates future sessions, not this immutable C4 snapshot.
        writeSkill("article-writer", "1.2.3", "# Changed\n");
        client.get().uri(session.path("url").asText())
                .headers(this::authenticate)
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).isEqualTo(original);

        // C6 is accepted immediately after C5 and counts only once.
        Map<String, Object> completion = Map.of(
                "status", "completed", "bytesReceived", original.length,
                "md5Verified", true, "sha256Verified", true, "installed", true);
        client.post().uri(session.path("url").asText() + "/complete")
                .headers(this::authenticate)
                .bodyValue(completion)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.counted").isEqualTo(true);
        client.post().uri(session.path("url").asText() + "/complete")
                .headers(this::authenticate)
                .bodyValue(completion)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.counted").isEqualTo(false);

        verifyNoInteractions(objectStore);
    }

    @Test
    void removingTheDirectoryImmediatelyBlocksC5AndC6ForAnExistingSession() throws Exception {
        writeSkill("article-writer", "1.2.3", "# Original\n");
        JsonNode session = json(client.post().uri("/v1/skills/article-writer/downloads")
                .headers(this::authenticate)
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody());
        Files.delete(root.resolve("article-writer.ycps.zip"));

        client.get().uri(session.path("url").asText())
                .headers(this::authenticate)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("skill_unpublished");
        client.post().uri(session.path("url").asText() + "/complete")
                .headers(this::authenticate)
                .bodyValue(Map.of("status", "completed", "md5Verified", true,
                        "sha256Verified", true, "installed", true))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("skill_unpublished");
    }

    @Test
    void filesystemSourceIgnoresPackageDirectoriesAndRequiresPrebuiltArchive() throws Exception {
        Path packageRoot = root.resolve("directory-only");
        Files.createDirectories(packageRoot.resolve("skills/directory-only"));
        Files.writeString(packageRoot.resolve("plugin.json"), "{}", StandardCharsets.UTF_8);

        client.get().uri("/v1/skills")
                .headers(this::authenticate)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(0);
        client.post().uri("/v1/skills/directory-only/downloads")
                .headers(this::authenticate)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void filesystemSessionAndArchiveRetentionHaveHardHeapBounds() throws Exception {
        writeSkill("article-writer", "1.2.3", "# Original\n");
        SkillAccessIdentity identity = new SkillAccessIdentity(
                "owner", "account", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        SkillDownloadService.RequestMeta request = new SkillDownloadService.RequestMeta(
                "device", "2026.9.15", "test", "request", null);

        SkillProperties sessionProperties = filesystemProperties();
        sessionProperties.setFilesystemDownloadMaxSessions(1);
        FilesystemSkillDownloadService sessionBound = filesystemService(sessionProperties);
        sessionBound.create("article-writer", identity, request).block();
        assertThatThrownBy(() -> sessionBound.create("article-writer", identity, request).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        SkillProperties byteProperties = filesystemProperties();
        byteProperties.setFilesystemDownloadMaxArchiveBytes(DataSize.ofBytes(1));
        FilesystemSkillDownloadService byteBound = filesystemService(byteProperties);
        assertThatThrownBy(() -> byteBound.create("article-writer", identity, request).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("skill_storage_unavailable");
    }

    @Test
    void completionDedupeExpiresEvenWhenNoNewSessionIsCreatedAfterTheWindow() throws Exception {
        writeSkill("article-writer", "1.2.3", "# Original\n");
        MutableClock clock = new MutableClock(Instant.parse("2026-09-15T00:00:00Z"));
        SkillProperties properties = filesystemProperties();
        FilesystemSkillDownloadService filesystem = new FilesystemSkillDownloadService(
                filesystemCatalog(properties), properties, clock, UUID::randomUUID);
        SkillAccessIdentity identity = new SkillAccessIdentity(
                "owner", "account", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        SkillDownloadService.RequestMeta request = new SkillDownloadService.RequestMeta(
                "device", "2026.9.15", "test", null, null);
        var completed = new com.yuzhihuihe.apiplatform.gateway.skill.api.SkillClientApi.DownloadReport(
                "completed", 0L, true, true, true, null, null, null);

        var first = filesystem.create("article-writer", identity, request).block();
        assertThat(filesystem.complete(first.downloadId(), identity, completed).block().counted())
                .isTrue();
        clock.advance(Duration.ofHours(23));
        var second = filesystem.create("article-writer", identity, request).block();
        clock.advance(Duration.ofHours(2));

        assertThat(filesystem.complete(second.downloadId(), identity, completed).block().counted())
                .isTrue();
    }

    private SkillProperties filesystemProperties() {
        SkillProperties properties = new SkillProperties();
        properties.setRoot(root.toString());
        properties.setSource(SkillProperties.SOURCE_FILESYSTEM);
        properties.setDownloadLimit(100);
        return properties;
    }

    private FilesystemSkillDownloadService filesystemService(SkillProperties properties) {
        return new FilesystemSkillDownloadService(
                filesystemCatalog(properties),
                properties,
                Clock.systemUTC(),
                UUID::randomUUID);
    }

    private void writeSkill(String code, String version, String document) throws Exception {
        Path archive = root.resolve(code + ".ycps.zip");
        try (OutputStream output = Files.newOutputStream(archive);
                ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
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
                     "dependencies":[],"permissionRequests":{"tools":[],"network":[],
                     "filesystem":[]},"hostRequirements":{}}
                    """);
            writeEntry(zip, "skills/" + code + "/SKILL.md", """
                    ---
                    name: %s
                    description: Article writer
                    ---

                    %s
                    """.formatted(code, document));
        }
    }

    private SkillCatalogService filesystemCatalog(SkillProperties properties) {
        return new SkillCatalogService(properties, mapper, (SkillRepository) null, null, null);
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content)
            throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void authenticate(HttpHeaders headers) {
        headers.setBearerAuth(API_KEY);
    }

    private JsonNode json(byte[] bytes) throws Exception {
        return mapper.readTree(bytes);
    }

    private static String digest(String algorithm, byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    }

    private static List<String> zipEntries(byte[] bytes) throws Exception {
        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        return entries;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
