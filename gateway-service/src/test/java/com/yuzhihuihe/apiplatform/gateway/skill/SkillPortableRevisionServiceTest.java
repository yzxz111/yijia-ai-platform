package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillVersionView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewContent;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewBlock;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewTable;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewTableStyle;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class SkillPortableRevisionServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void producesByteIdenticalArchivesForTheSameBaseAndPresentation() throws Exception {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        String code = "portable-test-expert";
        byte[] base = baseArchive(code);
        String baseSha = sha256(base);

        SkillRepository skills = mock(SkillRepository.class);
        SkillVersionRepository versions = mock(SkillVersionRepository.class);
        SkillExpertProfileRepository profiles = mock(SkillExpertProfileRepository.class);
        SkillPackageIngestService ingest = mock(SkillPackageIngestService.class);
        FileObjectStore objects = mock(FileObjectStore.class);
        SkillPortableRevisionService service = new SkillPortableRevisionService(
                skills, versions, profiles, ingest, objects, mock(SkillProperties.class));

        SkillView skill = skill(skillId, code);
        var profile = profile(skillId, code);
        var baseRef = new SkillVersionRepository.PackageRef(
                skillId, versionId, "1.0.0", "packages/base.zip", base.length,
                null, baseSha, null, 2, "YCPS", "1.0.0", "EXPERT",
                JsonNodeFactory.instance.arrayNode(), "manifest-sha", "lock-sha");
        SkillVersionView generated = generated(skillId);
        List<byte[]> archives = new ArrayList<>();

        when(skills.skill(skillId)).thenReturn(Mono.just(skill));
        when(versions.packageByVersionId(skillId, versionId)).thenReturn(Mono.just(baseRef));
        when(profiles.findBySkillId(skillId)).thenReturn(Mono.just(profile));
        when(versions.portableRevisionByInputs(eq(skillId), eq("1.0.0"), eq(baseSha), anyString()))
                .thenReturn(Mono.empty());
        when(skills.iconObjectKey(skillId)).thenReturn(Mono.empty());
        when(skills.illustrationObjectKey(skillId)).thenReturn(Mono.empty());
        when(skills.exampleImageObjectKey(skillId)).thenReturn(Mono.empty());
        when(objects.getRange("packages/base.zip", 0L, base.length))
                .thenReturn(Flux.just(ByteBuffer.wrap(base)));
        when(profiles.updatePortableMetadata(eq(skillId), any(), eq(7), eq(1)))
                .thenReturn(Mono.just(1L));
        when(ingest.ingestGenerated(
                eq(skillId), eq("1.0.0"),
                eq(SkillPortableRevisionService.DEFAULT_MIN_CLIENT_VERSION),
                any(Path.class), anyString(), eq(actor), anyMap()))
                .thenAnswer(invocation -> {
                    Path generatedArchive = invocation.getArgument(3, Path.class);
                    // `then(other)` assembles `other` before the rewrite Mono runs. Delay
                    // reading the temporary archive until subscription, after the ZIP is closed.
                    return Mono.fromCallable(() -> {
                        archives.add(Files.readAllBytes(generatedArchive));
                        return generated;
                    });
                });

        service.generate(skillId, versionId, actor).block();
        service.generate(skillId, versionId, actor).block();

        assertThat(archives).hasSize(2);
        assertThat(archives.get(1)).isEqualTo(archives.get(0));
        assertThat(zipNames(archives.get(0))).containsExactly(
                "com.yuzhihuihe.ycps/manifest.json",
                "plugin.json",
                "skills/portable-test-expert/SKILL.md");
        assertThat(zipText(archives.get(0), "plugin.json"))
                .contains("\"ycpsVersion\":\"1.1.0\"")
                .contains("\"requiredFeatures\":[\"expert-presentation-v1\"]");
        assertThat(zipText(archives.get(0), "skills/portable-test-expert/SKILL.md"))
                .contains("expert.taskType.01.zh")
                .contains("expert.preview.outputParagraph.01.zh")
                .contains("author.note: \"retained\"")
                .doesNotStartWith("---\n---\n");

        Path portable = Files.createTempFile("portable-revision-acceptance-", ".ycps.zip");
        try {
            Files.write(portable, archives.get(0));
            SkillPackageInspector.Report inspected = SkillPackageInspector.create()
                    .inspect(portable, new SkillProperties(), code);
            assertThat(inspected.ycpsVersion()).isEqualTo("1.1.0");
        assertThat(inspected.kind()).isEqualTo("EXPERT");
        assertThat(inspected.expertPresentation().taskTypes()).containsExactly("文本核验");
        assertThat(inspected.expertPresentation().canvas().path("zh").path("nodes")).hasSize(1);
        } finally {
            Files.deleteIfExists(portable);
        }
    }

    @Test
    void reusesTheCurrentPortableArtifactWhenItsProvenanceStillMatches() throws Exception {
        UUID skillId = UUID.randomUUID();
        UUID legacyVersionId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        String code = "portable-current-expert";
        byte[] base = baseArchive(code);
        String baseSha = sha256(base);

        SkillRepository skills = mock(SkillRepository.class);
        SkillVersionRepository versions = mock(SkillVersionRepository.class);
        SkillExpertProfileRepository profiles = mock(SkillExpertProfileRepository.class);
        SkillPackageIngestService ingest = mock(SkillPackageIngestService.class);
        FileObjectStore objects = mock(FileObjectStore.class);
        SkillPortableRevisionService service = new SkillPortableRevisionService(
                skills, versions, profiles, ingest, objects, mock(SkillProperties.class));

        SkillView skill = skill(skillId, code);
        ObjectNode provenance = JSON.createObjectNode();
        var profile = profile(skillId, code, provenance);
        SkillVersionView current = generated(skillId, baseSha);
        SkillVersionRepository.PackageRef legacy = new SkillVersionRepository.PackageRef(
                skillId, legacyVersionId, "1.0.0", "packages/legacy.zip", base.length,
                null, baseSha, null, 2, "YCPS", "1.0.0", "EXPERT",
                JsonNodeFactory.instance.arrayNode(), "manifest-sha", "lock-sha");
        SkillVersionRepository.PackageRef portable = new SkillVersionRepository.PackageRef(
                skillId, current.id(), "1.0.0", "packages/portable.zip", base.length,
                null, baseSha, null, current.revision(), "YCPS", "1.1.0", "EXPERT",
                JsonNodeFactory.instance.arrayNode(), "manifest-sha", "lock-sha");

        when(skills.skill(skillId)).thenReturn(Mono.just(skill));
        when(versions.packageByVersionId(skillId, legacyVersionId))
                .thenReturn(Mono.just(legacy));
        when(versions.packageByVersionId(skillId, current.id()))
                .thenReturn(Mono.just(portable));
        when(profiles.findBySkillId(skillId)).thenReturn(Mono.just(profile));
        when(versions.portableRevisionByInputs(eq(skillId), eq("1.0.0"), eq(baseSha), anyString()))
                .thenReturn(Mono.empty());
        when(skills.iconObjectKey(skillId)).thenReturn(Mono.empty());
        when(skills.illustrationObjectKey(skillId)).thenReturn(Mono.empty());
        when(skills.exampleImageObjectKey(skillId)).thenReturn(Mono.empty());
        when(objects.getRange(anyString(), eq(0L), eq((long) base.length)))
                .thenReturn(Flux.just(ByteBuffer.wrap(base)));
        when(profiles.updatePortableMetadata(eq(skillId), any(), eq(7), eq(1)))
                .thenAnswer(invocation -> {
                    provenance.removeAll();
                    provenance.setAll(invocation.getArgument(1, ObjectNode.class));
                    return Mono.just(1L);
                });
        when(ingest.ingestGenerated(
                eq(skillId), eq("1.0.0"),
                eq(SkillPortableRevisionService.DEFAULT_MIN_CLIENT_VERSION),
                any(Path.class), anyString(), eq(actor), anyMap()))
                .thenReturn(Mono.just(current));
        when(versions.version(current.id(), skill.publishedVersion()))
                .thenReturn(Mono.just(current));

        service.generate(skillId, legacyVersionId, actor).block();
        SkillVersionView reused = service.generate(skillId, current.id(), actor).block();

        assertThat(reused).isSameAs(current);
        verify(versions).version(current.id(), skill.publishedVersion());
        verify(ingest, times(1)).ingestGenerated(
                eq(skillId), eq("1.0.0"),
                eq(SkillPortableRevisionService.DEFAULT_MIN_CLIENT_VERSION),
                any(Path.class), anyString(), eq(actor), anyMap());
    }

    @Test
    void rejectsLegacyIllustrationsThatDoNotMeetPortableDimensions() {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        SkillRepository skills = mock(SkillRepository.class);
        SkillVersionRepository versions = mock(SkillVersionRepository.class);
        SkillExpertProfileRepository profiles = mock(SkillExpertProfileRepository.class);
        FileObjectStore objects = mock(FileObjectStore.class);
        SkillPortableRevisionService service = new SkillPortableRevisionService(
                skills, versions, profiles, mock(SkillPackageIngestService.class),
                objects, mock(SkillProperties.class));

        when(skills.skill(skillId)).thenReturn(Mono.just(skill(skillId, "portable-test-expert")));
        when(versions.packageByVersionId(skillId, versionId)).thenReturn(Mono.just(
                new SkillVersionRepository.PackageRef(
                        skillId, versionId, "1.0.0", "base.zip", 1, null, "0".repeat(64),
                        null, 1, "YCPS", "1.0.0", "EXPERT",
                        JsonNodeFactory.instance.arrayNode(), null, null)));
        when(profiles.findBySkillId(skillId))
                .thenReturn(Mono.just(profile(skillId, "portable-test-expert")));
        when(skills.iconObjectKey(skillId)).thenReturn(Mono.empty());
        when(skills.illustrationObjectKey(skillId)).thenReturn(Mono.just("legacy/illustration.png"));
        when(skills.exampleImageObjectKey(skillId)).thenReturn(Mono.empty());
        when(objects.get("legacy/illustration.png", 640L * 1024L))
                .thenReturn(Mono.just(pngHeader(1441, 480)));

        assertThatThrownBy(() -> service.generate(skillId, versionId, UUID.randomUUID()).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("expert_presentation_asset_invalid");
    }

    @Test
    void omitsNullTableStyleFromGeneratedMetadata() throws Exception {
        GeneratedTables tables = generatedTableMetadata(table(null));

        for (JsonNode value : List.of(tables.zh(), tables.en())) {
            assertThat(value.has("style")).isFalse();
            assertThat(value.toString()).doesNotContain("null");
        }
    }

    @Test
    void omitsNullFieldsInsideTableStyleFromGeneratedMetadata() throws Exception {
        ExpertPreviewTableStyle style = new ExpertPreviewTableStyle(
                12, "#112233", null, null, null, null, 8, null);
        GeneratedTables tables = generatedTableMetadata(table(style));

        for (JsonNode value : List.of(tables.zh(), tables.en())) {
            JsonNode exportedStyle = value.path("style");
            assertThat(exportedStyle.path("fontSize").asInt()).isEqualTo(12);
            assertThat(exportedStyle.path("color").asText()).isEqualTo("#112233");
            assertThat(exportedStyle.path("cellPadding").asInt()).isEqualTo(8);
            assertThat(exportedStyle.has("headerColor")).isFalse();
            assertThat(exportedStyle.has("headerBackgroundColor")).isFalse();
            assertThat(exportedStyle.has("borderColor")).isFalse();
            assertThat(exportedStyle.has("borderRadius")).isFalse();
            assertThat(exportedStyle.has("rowHeight")).isFalse();
            assertThat(value.toString()).doesNotContain("null");
        }
    }

    @Test
    void exportsPreviewIntroAsACompactLocalizedMetadataValue() throws Exception {
        GeneratedTables generated = generatedTableMetadata(
                table(null), new ExpertPreviewBlock("muted", "单位：天"));

        assertThat(generated.introZh().path("text").asText()).isEqualTo("单位：天");
        assertThat(generated.introZh().path("tone").asText()).isEqualTo("muted");
        assertThat(generated.introEn().path("text").asText()).isEqualTo("单位：天");
    }

    private static GeneratedTables generatedTableMetadata(ExpertPreviewTable table)
            throws Exception {
        return generatedTableMetadata(table, null);
    }

    private static GeneratedTables generatedTableMetadata(
            ExpertPreviewTable table, ExpertPreviewBlock intro) throws Exception {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        String code = "portable-table-null-expert";
        byte[] base = baseArchive(code);
        String baseSha = sha256(base);

        SkillRepository skills = mock(SkillRepository.class);
        SkillVersionRepository versions = mock(SkillVersionRepository.class);
        SkillExpertProfileRepository profiles = mock(SkillExpertProfileRepository.class);
        SkillPackageIngestService ingest = mock(SkillPackageIngestService.class);
        FileObjectStore objects = mock(FileObjectStore.class);
        SkillPortableRevisionService service = new SkillPortableRevisionService(
                skills, versions, profiles, ingest, objects, mock(SkillProperties.class));

        SkillView skill = skill(skillId, code);
        ExpertPreviewContent preview = preview(table, intro);
        var profile = profile(skillId, code, JsonNodeFactory.instance.objectNode(), preview, preview);
        var baseRef = new SkillVersionRepository.PackageRef(
                skillId, versionId, "1.0.0", "packages/base.zip", base.length,
                null, baseSha, null, 2, "YCPS", "1.0.0", "EXPERT",
                JsonNodeFactory.instance.arrayNode(), "manifest-sha", "lock-sha");
        SkillVersionView generated = generated(skillId);
        List<byte[]> archives = new ArrayList<>();

        when(skills.skill(skillId)).thenReturn(Mono.just(skill));
        when(versions.packageByVersionId(skillId, versionId)).thenReturn(Mono.just(baseRef));
        when(profiles.findBySkillId(skillId)).thenReturn(Mono.just(profile));
        when(versions.portableRevisionByInputs(eq(skillId), eq("1.0.0"), eq(baseSha), anyString()))
                .thenReturn(Mono.empty());
        when(skills.iconObjectKey(skillId)).thenReturn(Mono.empty());
        when(skills.illustrationObjectKey(skillId)).thenReturn(Mono.empty());
        when(skills.exampleImageObjectKey(skillId)).thenReturn(Mono.empty());
        when(objects.getRange("packages/base.zip", 0L, base.length))
                .thenReturn(Flux.just(ByteBuffer.wrap(base)));
        when(profiles.updatePortableMetadata(eq(skillId), any(), eq(7), eq(1)))
                .thenReturn(Mono.just(1L));
        when(ingest.ingestGenerated(
                eq(skillId), eq("1.0.0"),
                eq(SkillPortableRevisionService.DEFAULT_MIN_CLIENT_VERSION),
                any(Path.class), anyString(), eq(actor), anyMap()))
                .thenAnswer(invocation -> {
                    Path generatedArchive = invocation.getArgument(3, Path.class);
                    return Mono.fromCallable(() -> {
                        archives.add(Files.readAllBytes(generatedArchive));
                        return generated;
                    });
                });

        service.generate(skillId, versionId, actor).block();

        String document = zipText(archives.get(0), "skills/" + code + "/SKILL.md");
        int firstBreak = document.indexOf('\n');
        int close = document.indexOf("\n---", firstBreak + 1);
        JsonNode frontmatter = new YAMLMapper().readTree(document.substring(firstBreak + 1, close + 1));
        JsonNode metadata = frontmatter.path("metadata");
        return new GeneratedTables(
                JSON.readTree(metadata.path("expert.preview.table.v1.zh").asText()),
                JSON.readTree(metadata.path("expert.preview.table.v1.en").asText()),
                JSON.readTree(metadata.path("expert.preview.intro.v1.zh").asText()),
                JSON.readTree(metadata.path("expert.preview.intro.v1.en").asText()));
    }

    private static ExpertPreviewTable table(ExpertPreviewTableStyle style) {
        return new ExpertPreviewTable(
                List.of("状态", "数量"), List.of(60, 40),
                List.of(List.<Object>of("通过", 1)), Boolean.FALSE, style);
    }

    private static ExpertPreviewContent preview(ExpertPreviewTable table) {
        return preview(table, null);
    }

    private static ExpertPreviewContent preview(
            ExpertPreviewTable table, ExpertPreviewBlock intro) {
        return new ExpertPreviewContent(
                "示例", null, "场景", "请求", "材料", "输出", List.of("第一段"),
                intro, table, null, null);
    }

    private static SkillView skill(UUID id, String code) {
        Instant now = Instant.parse("2026-09-20T00:00:00Z");
        return new SkillView(
                id, code, "测试专家", "Test Expert", "测试", List.of("operational"),
                "卡片摘要", "Card summary", "详情简介", "Detail description",
                null, null, null, null, null, null, null,
                "EXPERT", "PUBLISHED", "1.0.0", "1.0.0",
                null, null, null, null, 0, 0, 7, now, now, now, null,
                0, 0, 0, null, List.of(), List.of(), "YCPS", false, null, null);
    }

    private static SkillExpertProfileRepository.StoredProfile profile(UUID id, String code) {
        return profile(id, code, JsonNodeFactory.instance.objectNode());
    }

    private static SkillExpertProfileRepository.StoredProfile profile(
            UUID id, String code, JsonNode assetManifest) {
        ExpertPreviewContent preview = new ExpertPreviewContent(
                "示例", "场景", "请求", "材料", "输出", List.of("第一段"));
        return profile(id, code, assetManifest, preview, null);
    }

    private static SkillExpertProfileRepository.StoredProfile profile(
            UUID id,
            String code,
            JsonNode assetManifest,
            ExpertPreviewContent preview,
            ExpertPreviewContent previewEn) {
        Instant now = Instant.parse("2026-09-20T00:00:00Z");
        return new SkillExpertProfileRepository.StoredProfile(
                id, code, "EXPERT", "PUBLISHED",
                List.of("文本核验"), List.of("Text review"),
                "提供材料并说明目标。", "Provide the material and desired outcome.",
                List.of("请核验这份材料"), List.of("Review this material"),
                preview, previewEn, canvas(), assetManifest, 0, 7, now, now);
    }

    private static JsonNode canvas() {
        ObjectNode layout = JSON.createObjectNode();
        layout.put("version", 1);
        layout.put("width", 1080);
        layout.put("height", 704);
        ObjectNode node = layout.putArray("nodes").addObject();
        node.put("id", "heading");
        node.put("field", "preview.heading");
        node.put("kind", "text");
        node.put("x", 24);
        node.put("y", 24);
        node.put("width", 600);
        node.put("height", 80);
        ObjectNode run = node.putArray("runs").addObject();
        run.put("text", "示例");
        run.put("fontSize", 32);
        run.put("color", "#112233");
        ObjectNode localized = JSON.createObjectNode();
        localized.set("zh", layout);
        return localized;
    }

    private static SkillVersionView generated(UUID skillId) {
        return generated(skillId, "1".repeat(64));
    }

    private static SkillVersionView generated(UUID skillId, String sha256) {
        return new SkillVersionView(
                UUID.randomUUID(), skillId, "1.0.0", 3, "READY", 1024L,
                "0".repeat(32), sha256, "YCPS", "1.1.0", "EXPERT",
                null, null, "2".repeat(64), "3".repeat(64), true,
                Instant.parse("2026-09-20T00:00:00Z"), 3, 2048,
                SkillPortableRevisionService.DEFAULT_MIN_CLIENT_VERSION,
                null, "CLEAN", 0, false, Instant.parse("2026-09-20T00:00:00Z"));
    }

    private static byte[] baseArchive(String code) throws Exception {
        String plugin = """
                {"$schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json",
                "name":"%s","version":"1.0.0","description":"Portable revision test Expert",
                "author":{"name":"Yuzhi Tests"},"license":"MIT",
                "extensions":{"com.yuzhihuihe.ycps":{"ycpsVersion":"1.0.0",
                "kind":"EXPERT","entrySkill":"%s",
                "manifest":"./com.yuzhihuihe.ycps/manifest.json","requiredFeatures":[]}}}
                """.formatted(code, code);
        String runtime = """
                {"$schema":"https://schemas.yuzhihuihe.com/ycps/1.0.0/expert-manifest.schema.json",
                "entryAgent":"main","agents":[{"id":"main","role":"LEAD",
                "instructions":{"type":"ENTRY_SKILL"},"bindings":[]}],
                "dependencies":[],"permissionRequests":{"tools":[],"network":[],"filesystem":[]},
                "hostRequirements":{"minClientVersion":"1.0.0"}}
                """;
        String skill = """
                ---
                name: %s
                description: Portable revision runtime instructions.
                metadata:
                  author.note: retained
                  expert.preview.heading.zh: stale
                ---
                # Runtime body
                """.formatted(code);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            add(zip, "plugin.json", plugin);
            add(zip, "com.yuzhihuihe.ycps/manifest.json", runtime);
            add(zip, "skills/" + code + "/SKILL.md", skill);
        }
        return output.toByteArray();
    }

    private static void add(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static List<String> zipNames(byte[] archive) throws Exception {
        List<String> result = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                result.add(entry.getName());
            }
        }
        return result;
    }

    private static String zipText(byte[] archive, String wanted) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (wanted.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("missing zip entry: " + wanted);
    }

    private static byte[] pngHeader(int width, int height) {
        byte[] value = new byte[24];
        System.arraycopy(new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a},
                0, value, 0, 8);
        value[12] = 'I';
        value[13] = 'H';
        value[14] = 'D';
        value[15] = 'R';
        putInt(value, 16, width);
        putInt(value, 20, height);
        return value;
    }

    private static void putInt(byte[] value, int offset, int number) {
        value[offset] = (byte) (number >>> 24);
        value[offset + 1] = (byte) (number >>> 16);
        value[offset + 2] = (byte) (number >>> 8);
        value[offset + 3] = (byte) number;
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record GeneratedTables(JsonNode zh, JsonNode en, JsonNode introZh, JsonNode introEn) {}
}
