package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class SkillPackageInspectorTest {

    private static final long EOCD = 0x06054b50L;
    private static final long CENTRAL = 0x02014b50L;

    @TempDir
    Path directory;

    @Test
    void acceptsMinimalYcpsAndReturnsProtocolMetadata() throws Exception {
        SkillPackageInspector.Report report = inspect(zip(validEntries()), "minimal-skill");

        assertThat(report.code()).isEqualTo("minimal-skill");
        assertThat(report.version()).isEqualTo("1.0.0+vector.1");
        assertThat(report.kind()).isEqualTo("SKILL");
        assertThat(report.ycpsVersion()).isEqualTo("1.0.0");
        assertThat(report.manifestSha256()).matches("[0-9a-f]{64}");
        assertThat(report.dependencyLockSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void acceptsYcps11ExpertPresentationWithoutChangingPluginSchema() throws Exception {
        Map<String, byte[]> entries = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));

        SkillPackageInspector.Report report = inspect(zip(entries), "minimal-expert-presentation");

        assertThat(report.ycpsVersion()).isEqualTo("1.1.0");
        assertThat(report.kind()).isEqualTo("EXPERT");
        assertThat(report.expertPresentation().taskTypes()).containsExactly("文本核验");
        assertThat(report.expertPresentation().examplePrompts())
                .containsExactly("请核验这段文本。");
        assertThat(report.expertPresentation().preview().outputParagraphs())
                .containsExactly("文本通过了最小展示契约校验。");
        assertThat(report.expertPresentation().assets()).isEmpty();
    }

    @Test
    void acceptsOptionalCanvasPerLocaleAndExposesTheApiShape() throws Exception {
        Map<String, byte[]> entries = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        String skillPath = "skills/minimal-expert-presentation/SKILL.md";
        String canvas = "{\"version\":1,\"width\":1080,\"height\":704,"
                + "\"nodes\":[{\"id\":\"heading\",\"field\":\"preview.heading\","
                + "\"kind\":\"text\",\"x\":24,\"y\":24,\"width\":600,\"height\":80,"
                + "\"runs\":[{\"text\":\"示例\",\"fontSize\":32,\"color\":\"#112233\"}]}]}";
        String skill = replaceFixture(
                fixtureText(entries.get(skillPath)),
                "  expert.taskType.01.zh: 文本核验",
                "  expert.taskType.01.zh: 文本核验\n"
                        + "  expert.canvas.v1.zh: '" + canvas + "'");
        entries.put(skillPath, skill.getBytes(StandardCharsets.UTF_8));

        SkillPackageInspector.ExpertPresentation presentation =
                inspect(zip(entries), "minimal-expert-presentation").expertPresentation();

        assertThat(presentation.canvas()).isNotNull();
        assertThat(presentation.canvas().path("zh").path("version").asInt()).isEqualTo(1);
        assertThat(presentation.canvas().path("zh").path("nodes")).hasSize(1);
    }

    @Test
    void acceptsStructuredPortablePreviewTableWithStyle() throws Exception {
        Map<String, byte[]> entries = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        String skillPath = "skills/minimal-expert-presentation/SKILL.md";
        String table = "{\"headers\":[\"姓名\",\"应出勤\",\"实际出勤\",\"差额\"],"
                + "\"columnWidths\":[25,25,25,25],"
                + "\"rows\":[[\"林然\",22,21,1],[\"合计\",22,21,1]],"
                + "\"totalRow\":true,\"style\":{\"fontSize\":14,"
                + "\"headerBackgroundColor\":\"#f1f1f1\",\"borderColor\":\"#dddddd\"}}";
        String skill = replaceFixture(
                fixtureText(entries.get(skillPath)),
                "  expert.taskType.01.zh: 文本核验",
                "  expert.taskType.01.zh: 文本核验\n"
                        + "  expert.preview.table.v1.zh: '" + table + "'");
        entries.put(skillPath, skill.getBytes(StandardCharsets.UTF_8));

        SkillPackageInspector.ExpertPresentation presentation =
                inspect(zip(entries), "minimal-expert-presentation").expertPresentation();

        assertThat(presentation.preview().table()).isNotNull();
        assertThat(presentation.preview().table().rows()).hasSize(2);
        assertThat(presentation.preview().table().style().headerBackgroundColor())
                .isEqualTo("#f1f1f1");
    }

    @Test
    void acceptsPortablePreviewIntroAndNormalizesItsTone() throws Exception {
        Map<String, byte[]> entries = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        String skillPath = "skills/minimal-expert-presentation/SKILL.md";
        String intro = "{\"text\":\" 单位：天 \",\"tone\":\"muted\"}";
        String skill = replaceFixture(
                fixtureText(entries.get(skillPath)),
                "  expert.taskType.01.zh: 文本核验",
                "  expert.taskType.01.zh: 文本核验\n"
                        + "  expert.preview.intro.v1.zh: '" + intro + "'");
        entries.put(skillPath, skill.getBytes(StandardCharsets.UTF_8));

        SkillPackageInspector.ExpertPresentation presentation =
                inspect(zip(entries), "minimal-expert-presentation").expertPresentation();

        assertThat(presentation.preview().intro()).isNotNull();
        assertThat(presentation.preview().intro().text()).isEqualTo("单位：天");
        assertThat(presentation.preview().intro().tone()).isEqualTo("muted");
    }

    @Test
    void doesNotMakeEnglishPreviewMandatoryForAnEnglishSemanticIntroAlone() throws Exception {
        Map<String, byte[]> entries = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        String skillPath = "skills/minimal-expert-presentation/SKILL.md";
        String skill = replaceFixture(
                fixtureText(entries.get(skillPath)),
                "  expert.taskType.01.zh: 文本核验",
                "  expert.taskType.01.zh: 文本核验\n"
                        + "  expert.preview.intro.v1.en: '{\"text\":\"Units: days\"}'");
        entries.put(skillPath, skill.getBytes(StandardCharsets.UTF_8));

        SkillPackageInspector.ExpertPresentation presentation =
                inspect(zip(entries), "minimal-expert-presentation").expertPresentation();

        assertThat(presentation.previewEn()).isNull();
    }

    @Test
    void acceptsExampleImageLocaleAndRejectsUnknownValues() throws Exception {
        Map<String, byte[]> accepted = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        String skillPath = "skills/minimal-expert-presentation/SKILL.md";
        accepted.put(skillPath, replaceFixture(
                fixtureText(accepted.get(skillPath)),
                "metadata:\n",
                "metadata:\n  expert.asset.exampleImage.locale: neutral\n")
                .getBytes(StandardCharsets.UTF_8));

        SkillPackageInspector.ExpertPresentation presentation =
                inspect(zip(accepted), "minimal-expert-presentation").expertPresentation();
        assertThat(presentation.exampleImageLocale()).isEqualTo("neutral");

        Map<String, byte[]> rejected = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        rejected.put(skillPath, replaceFixture(
                fixtureText(rejected.get(skillPath)),
                "metadata:\n",
                "metadata:\n  expert.asset.exampleImage.locale: fr\n")
                .getBytes(StandardCharsets.UTF_8));
        assertInvalid(zip(rejected), "minimal-expert-presentation",
                "expert_presentation_example_image_locale_invalid");
    }

    @Test
    void rejectsLocalizedEnvelopeInsideOneCanvasMetadataKey() throws Exception {
        Map<String, byte[]> entries = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        String skillPath = "skills/minimal-expert-presentation/SKILL.md";
        String canvas = "{\"zh\":{\"version\":1,\"width\":1080,\"height\":704,"
                + "\"nodes\":[]}}";
        String skill = replaceFixture(
                fixtureText(entries.get(skillPath)),
                "  expert.taskType.01.zh: 文本核验",
                "  expert.taskType.01.zh: 文本核验\n"
                        + "  expert.canvas.v1.zh: '" + canvas + "'");
        entries.put(skillPath, skill.getBytes(StandardCharsets.UTF_8));

        assertInvalid(zip(entries), "minimal-expert-presentation",
                "expert_presentation_canvas_invalid");
    }

    @Test
    void acceptsAllSixCuratedPortableExpertFixtures() {
        for (String code : List.of(
                "ai-office-assistant",
                "content-summarizer",
                "content-writing-expert",
                "document-review-expert",
                "sales-analysis-expert",
                "social-media-operations-expert")) {
            SkillPackageInspector.Report report = inspect(
                    curatedFixturePackages().resolve(code + "-1.0.0.ycps.zip"), code);

            assertThat(report.ycpsVersion()).as(code).isEqualTo("1.1.0");
            assertThat(report.kind()).as(code).isEqualTo("EXPERT");
            assertThat(report.expertPresentation()).as(code).isNotNull();
            assertThat(report.expertPresentation().assets()).as(code)
                    .containsOnlyKeys("icon", "illustration", "exampleImage");
        }
    }

    @Test
    void keepsUnrelatedAuthorMetadataCompatibleButRejectsUnknownExpertKeys() throws Exception {
        Map<String, byte[]> accepted = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        String skillPath = "skills/minimal-expert-presentation/SKILL.md";
        accepted.put(skillPath, replaceFixture(
                fixtureText(accepted.get(skillPath)),
                "metadata:\n",
                "metadata:\n  author.note: 'kept for authors'\n")
                .getBytes(StandardCharsets.UTF_8));
        SkillPackageInspector.ExpertPresentation acceptedPresentation =
                inspect(zip(accepted), "minimal-expert-presentation").expertPresentation();
        assertThat(acceptedPresentation).isNotNull();
        assertThat(acceptedPresentation.warnings()).contains("author_metadata_ignored:author.note");

        Map<String, byte[]> oversizedAuthor = new LinkedHashMap<>(accepted);
        oversizedAuthor.put(skillPath, replaceFixture(
                fixtureText(oversizedAuthor.get(skillPath)),
                "author.note: 'kept for authors'",
                "author.note: '" + "x".repeat(1025) + "'")
                .getBytes(StandardCharsets.UTF_8));
        assertInvalid(zip(oversizedAuthor), "minimal-expert-presentation", "skill_metadata_invalid");

        Map<String, byte[]> oversizedTitle = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        oversizedTitle.put(skillPath, replaceFixture(
                fixtureText(oversizedTitle.get(skillPath)),
                "title.zh: 最小专家展示",
                "title.zh: '" + "x".repeat(129) + "'")
                .getBytes(StandardCharsets.UTF_8));
        assertInvalid(zip(oversizedTitle), "minimal-expert-presentation",
                "expert_presentation_catalog_copy_invalid");

        Map<String, byte[]> rejected = new LinkedHashMap<>(accepted);
        rejected.put(skillPath, replaceFixture(
                fixtureText(rejected.get(skillPath)),
                "author.note: 'kept for authors'",
                "expert.unknown.zh: 'must reject'")
                .getBytes(StandardCharsets.UTF_8));
        assertInvalid(zip(rejected), "minimal-expert-presentation",
                "expert_presentation_metadata_invalid");
    }

    @Test
    void rejectsNonContiguousYcps11PresentationIndexes() throws Exception {
        Map<String, byte[]> entries = directoryEntries(sharedVectors11()
                .resolve("invalid/presentation-gap/package"));

        assertInvalid(zip(entries), "presentation-gap", "expert_presentation_prompts_invalid");
    }

    @Test
    void rejectsPartialEnglishPreviewInsteadOfSilentlyDroppingIt() throws Exception {
        Map<String, byte[]> entries = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        String skillPath = "skills/minimal-expert-presentation/SKILL.md";
        entries.put(skillPath, replaceFixture(
                fixtureText(entries.get(skillPath)),
                "expert.preview.heading.zh: 文本核验示例",
                "expert.preview.heading.zh: 文本核验示例\n  expert.preview.heading.en: Preview")
                .getBytes(StandardCharsets.UTF_8));

        assertInvalid(zip(entries), "minimal-expert-presentation",
                "expert_presentation_preview_invalid");
    }

    @Test
    void capturesOptionalPortableIconAndEnforcesItsDeclaredRole() throws Exception {
        Map<String, byte[]> entries = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        String skillPath = "skills/minimal-expert-presentation/SKILL.md";
        entries.put(skillPath, replaceFixture(
                fixtureText(entries.get(skillPath)),
                "metadata:\n",
                "metadata:\n  expert.asset.icon.path: assets/icon.png\n")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("skills/minimal-expert-presentation/assets/icon.png", png(128, 128));

        SkillPackageInspector.Report report = inspect(zip(entries), "minimal-expert-presentation");

        assertThat(report.expertPresentation().assets()).containsKey("icon");
        assertThat(report.expertPresentation().assets().get("icon").sizeBytes()).isEqualTo(24);
    }

    @Test
    void requiresEachPortableAssetRoleToUseItsFixedPackageFilename() throws Exception {
        Map<String, byte[]> entries = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        String skillPath = "skills/minimal-expert-presentation/SKILL.md";
        entries.put(skillPath, replaceFixture(
                fixtureText(entries.get(skillPath)),
                "metadata:\n",
                "metadata:\n  expert.asset.icon.path: assets/example-image.png\n")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("skills/minimal-expert-presentation/assets/example-image.png", png(128, 128));

        assertInvalid(zip(entries), "minimal-expert-presentation",
                "expert_presentation_asset_path_invalid");
    }

    @Test
    void keepsYcps10FeatureFreeAndGatesYcps11ToExpertPresentation() throws Exception {
        Map<String, byte[]> legacy = validEntries();
        legacy.put("plugin.json", bytes(pluginJson().replace(
                "\"requiredFeatures\":[]", "\"requiredFeatures\":[\"expert-presentation-v1\"]")));
        assertInvalid(zip(legacy), "minimal-skill", "required_features_unsupported");

        Map<String, byte[]> nonExpert = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        nonExpert.put("plugin.json", replaceFixture(
                fixtureText(nonExpert.get("plugin.json")),
                "\"kind\": \"EXPERT\"",
                "\"kind\": \"SKILL\"")
                .getBytes(StandardCharsets.UTF_8));
        assertInvalid(zip(nonExpert), "minimal-expert-presentation",
                "expert_presentation_profile_invalid");

        Map<String, byte[]> unknownFeature = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        unknownFeature.put("plugin.json", replaceFixture(
                fixtureText(unknownFeature.get("plugin.json")),
                "\"requiredFeatures\": [\"expert-presentation-v1\"]",
                "\"requiredFeatures\": [\"expert-presentation-v1\", \"unknown-feature\"]")
                .getBytes(StandardCharsets.UTF_8));
        assertInvalid(zip(unknownFeature), "minimal-expert-presentation",
                "expert_presentation_profile_invalid");
    }

    @Test
    void readsLossyVp8WebpDimensionsFromTheFrameHeaderBoundary() throws Exception {
        Map<String, byte[]> entries = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        String skillPath = "skills/minimal-expert-presentation/SKILL.md";
        entries.put(skillPath, replaceFixture(
                fixtureText(entries.get(skillPath)),
                "metadata:\n",
                "metadata:\n  expert.asset.icon.path: assets/icon.webp\n")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("skills/minimal-expert-presentation/assets/icon.webp", webpVp8(128, 128));

        SkillPackageInspector.Report report = inspect(zip(entries), "minimal-expert-presentation");

        assertThat(report.expertPresentation().assets().get("icon").sizeBytes()).isEqualTo(30);
    }

    @Test
    void rejectsMissingChineseRequiredPresentationField() throws Exception {
        Map<String, byte[]> entries = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        String skillPath = "skills/minimal-expert-presentation/SKILL.md";
        entries.put(skillPath, replaceFixture(
                fixtureText(entries.get(skillPath)),
                "expert.usageGuide.zh: 输入待核验文本，专家会返回一条确定性结论。",
                "author.note: 'usage guide intentionally omitted'")
                .getBytes(StandardCharsets.UTF_8));

        assertInvalid(zip(entries), "minimal-expert-presentation",
                "expert_presentation_usage_guide_required");
    }

    @Test
    void rejectsInvalidPortableAssetPathMagicDimensionsAndSize() throws Exception {
        String skillPath = "skills/minimal-expert-presentation/SKILL.md";
        String declaration = "metadata:\n  expert.asset.icon.path: %s\n";

        Map<String, byte[]> path = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        path.put(skillPath, replaceFixture(
                fixtureText(path.get(skillPath)),
                "metadata:\n",
                declaration.formatted("assets/icon.svg"))
                .getBytes(StandardCharsets.UTF_8));
        assertInvalid(zip(path), "minimal-expert-presentation",
                "expert_presentation_asset_path_invalid");

        Map<String, byte[]> magic = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        magic.put(skillPath, replaceFixture(
                fixtureText(magic.get(skillPath)),
                "metadata:\n",
                declaration.formatted("assets/icon.png"))
                .getBytes(StandardCharsets.UTF_8));
        magic.put("skills/minimal-expert-presentation/assets/icon.png", bytes("not a png"));
        assertInvalid(zip(magic), "minimal-expert-presentation",
                "expert_presentation_asset_invalid");

        Map<String, byte[]> dimensions = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        dimensions.put(skillPath, replaceFixture(
                fixtureText(dimensions.get(skillPath)),
                "metadata:\n",
                declaration.formatted("assets/icon.png"))
                .getBytes(StandardCharsets.UTF_8));
        dimensions.put("skills/minimal-expert-presentation/assets/icon.png", png(513, 512));
        assertInvalid(zip(dimensions), "minimal-expert-presentation",
                "expert_presentation_asset_dimensions_invalid");

        Map<String, byte[]> malformedWebp = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        malformedWebp.put(skillPath, replaceFixture(
                fixtureText(malformedWebp.get(skillPath)),
                "metadata:\n",
                declaration.formatted("assets/icon.webp"))
                .getBytes(StandardCharsets.UTF_8));
        byte[] inconsistentRiff = webpVp8(128, 128);
        inconsistentRiff[4] = 1; // Declared RIFF payload is shorter than the mandatory WEBP header.
        malformedWebp.put("skills/minimal-expert-presentation/assets/icon.webp", inconsistentRiff);
        assertInvalid(zip(malformedWebp), "minimal-expert-presentation",
                "expert_presentation_asset_invalid");

        Map<String, byte[]> size = directoryEntries(sharedVectors11()
                .resolve("valid/minimal-expert-presentation/package"));
        size.put(skillPath, replaceFixture(
                fixtureText(size.get(skillPath)),
                "metadata:\n",
                declaration.formatted("assets/icon.png"))
                .getBytes(StandardCharsets.UTF_8));
        byte[] oversized = new byte[128 * 1024 + 1];
        new java.util.Random(1).nextBytes(oversized);
        System.arraycopy(png(128, 128), 0, oversized, 0, 24);
        size.put("skills/minimal-expert-presentation/assets/icon.png", oversized);
        assertInvalid(zip(size), "minimal-expert-presentation",
                "expert_presentation_asset_too_large");
    }

    @Test
    void readsCatalogCopyThePackageDeclaresInFrontmatterMetadata() throws Exception {
        Map<String, byte[]> entries = validEntries();
        entries.put("skills/minimal-skill/SKILL.md", bytes("""
                ---
                name: minimal-skill
                description: 默认语言简介
                metadata:
                  title.zh: '中文标题'
                  title.en: 'English Title'
                  summary.en: 'English summary copy.'
                ---

                # Minimal Skill
                Body stays untouched.
                """));

        SkillPackageInspector.CatalogCopy copy =
                inspect(zip(entries), "minimal-skill").catalogCopy();

        // The bare description always seeds the default language, with metadata keys layered on top.
        assertThat(copy.defaultCopy())
                .containsEntry("summary", "默认语言简介")
                .containsEntry("title", "中文标题");
        assertThat(copy.forLanguage("en"))
                .containsEntry("title", "English Title")
                .containsEntry("summary", "English summary copy.");
    }

    @Test
    void reportsNoLocalizedCopyWhenThePackageDeclaresOnlyItsDefaultLanguage() throws Exception {
        SkillPackageInspector.CatalogCopy copy =
                inspect(zip(validEntries()), "minimal-skill").catalogCopy();

        assertThat(copy.defaultCopy()).containsEntry("summary", "Minimal YCPS fixture");
        assertThat(copy.localized()).isEmpty();
    }

    @Test
    void ignoresMetadataKeysThatAreNotCatalogCopy() throws Exception {
        Map<String, byte[]> entries = validEntries();
        entries.put("skills/minimal-skill/SKILL.md", bytes("""
                ---
                name: minimal-skill
                description: 默认语言简介
                metadata:
                  author.note: 'not catalog copy'
                  summary.e: 'not a language tag'
                  summary.en: 'kept'
                ---

                # Minimal Skill
                Body.
                """));

        SkillPackageInspector.CatalogCopy copy =
                inspect(zip(entries), "minimal-skill").catalogCopy();

        assertThat(copy.forLanguage("en")).containsExactly(Map.entry("summary", "kept"));
        assertThat(copy.defaultCopy()).doesNotContainKey("note");
    }

    @Test
    void rejectsLegacyRootFiles() throws Exception {
        Map<String, byte[]> legacy = new LinkedHashMap<>();
        legacy.put("skill.json", bytes("{}"));
        legacy.put("SKILL.md", bytes("# Legacy"));

        assertInvalid(zip(legacy), "minimal-skill", "root_entry_not_allowed");
    }

    @Test
    void rejectsIdentityRootShapeAndFixedNameCase() throws Exception {
        assertInvalid(zip(validEntries()), "another-code", "upload target code");

        Map<String, byte[]> unknown = validEntries();
        unknown.put("README.md", bytes("not allowed"));
        assertInvalid(zip(unknown), "minimal-skill", "root_entry_not_allowed");

        Map<String, byte[]> nestedRootFile = validEntries();
        nestedRootFile.put("LICENSE/child", bytes("not allowed"));
        assertInvalid(zip(nestedRootFile), "minimal-skill", "root_entry_not_allowed");

        Map<String, byte[]> wrongCase = validEntries();
        wrongCase.remove("skills/minimal-skill/SKILL.md");
        wrongCase.put("skills/minimal-skill/Skill.md", bytes(skillDocument()));
        assertInvalid(zip(wrongCase), "minimal-skill", "fixed_entry_case_invalid");
    }

    @Test
    void rejectsUnknownToolAndMalformedDependencyLock() throws Exception {
        Map<String, byte[]> unknownTool = validEntries();
        unknownTool.put(SkillPackageInspector.RUNTIME, bytes(runtimeJson(
                "[]", "[\"unknown.capability\"]", "[]")));
        assertInvalid(zip(unknownTool), "minimal-skill", "permission_tools_invalid");

        Map<String, byte[]> badLock = validEntries();
        badLock.put(SkillPackageInspector.RUNTIME, bytes(runtimeJson(
                "[{\"key\":\"bad\",\"relation\":\"SKILL_BINDING\",\"required\":true,"
                        + "\"package\":{\"code\":\"dependency\",\"kind\":\"SKILL\","
                        + "\"version\":\"1.0.0\",\"revision\":0,\"sha256\":\"bad\"}}]",
                "[]", "[]")));
        assertInvalid(zip(badLock), "minimal-skill", "dependency_invalid");
    }

    @Test
    void validatesMcpSchemaPermissionsAndCredentialRules() throws Exception {
        Map<String, byte[]> valid = validEntries();
        valid.put("mcp.json", bytes("""
                {"$schema":"https://agent-plugins.org/schemas/1.0.0/mcp.schema.json",
                 "mcpServers":{"remote":{"type":"streamable-http",
                 "url":"https://mcp.example.com/rpc"}}}
                """));
        valid.put(SkillPackageInspector.RUNTIME,
                bytes(runtimeJson("[]", "[]", "[\"https://mcp.example.com\"]")));
        assertThat(inspect(zip(valid), "minimal-skill").code()).isEqualTo("minimal-skill");

        Map<String, byte[]> invalid = new LinkedHashMap<>(valid);
        invalid.put("mcp.json", bytes("""
                {"$schema":"https://agent-plugins.org/schemas/1.0.0/mcp.schema.json",
                 "mcpServers":{"remote":{"type":"streamable-http",
                 "url":"https://mcp.example.com/rpc",
                 "headers":{"Authorization":"Bearer embedded"}}}}
                """));
        invalid.put(SkillPackageInspector.RUNTIME,
                bytes(runtimeJson("[]", "[]", "[]")));
        assertInvalid(zip(invalid), "minimal-skill", "mcp_permission_missing");

        Map<String, byte[]> stdio = validEntries();
        stdio.put("mcp.json", bytes("""
                {"$schema":"https://agent-plugins.org/schemas/1.0.0/mcp.schema.json",
                 "mcpServers":{"local":{"type":"stdio","command":"node",
                 "cwd":"${PLUGIN_ROOT}/scripts"}}}
                """));
        assertInvalid(zip(stdio), "minimal-skill", "mcp_permission_missing");
    }

    @Test
    void executableEntrySkillFilesRequireProcessExecute() throws Exception {
        Map<String, byte[]> denied = validEntries();
        denied.put("skills/minimal-skill/scripts/run.py", bytes("print('ok')"));
        assertInvalid(zip(denied), "minimal-skill", "process_permission_missing");

        Map<String, byte[]> allowed = new LinkedHashMap<>(denied);
        allowed.put(SkillPackageInspector.RUNTIME,
                bytes(runtimeJson("[]", "[\"process.execute\"]", "[]")));
        assertThat(inspect(zip(allowed), "minimal-skill").code()).isEqualTo("minimal-skill");
    }

    @Test
    void acceptsSchemaUnboundedPluginTextAndExactlyThirtyTwoDependencies() throws Exception {
        Map<String, byte[]> entries = validEntries();
        String expanded = pluginJson()
                .replace("Minimal YCPS fixture", "d".repeat(5_000))
                .replace("Yuzhi Tests", "a".repeat(300))
                .replace("\"MIT\"", "\"" + "l".repeat(200) + "\"");
        entries.put("plugin.json", bytes(expanded));
        entries.put(SkillPackageInspector.RUNTIME,
                bytes(runtimeJson(dependencies(32), "[]", "[]")));

        assertThat(inspect(zip(entries), "minimal-skill").code()).isEqualTo("minimal-skill");

        entries.put(SkillPackageInspector.RUNTIME,
                bytes(runtimeJson(dependencies(33), "[]", "[]")));
        assertInvalid(zip(entries), "minimal-skill", "dependencies_invalid");
    }

    @Test
    void readsCatalogCopyFromAnExpertPackageToo() throws Exception {
        // The catalog copy is a property of the Entry Skill frontmatter, not of the SKILL kind, so an
        // EXPERT package seeds the console columns through exactly the same path.
        Map<String, byte[]> expert = directoryEntries(
                sharedVectors().resolve("valid/minimal-expert/package"));
        String skillPath = expert.keySet().stream()
                .filter(path -> path.startsWith("skills/") && path.endsWith("/SKILL.md"))
                .findFirst()
                .orElseThrow();
        expert.put(skillPath, bytes("""
                ---
                name: minimal-expert
                description: 专家包默认简介
                metadata:
                  title.zh: '专家包中文标题'
                  title.en: 'Expert Package Title'
                  summary.en: 'Expert package English summary.'
                ---

                # Minimal Expert
                Lead the roster.
                """));

        SkillPackageInspector.CatalogCopy copy =
                inspect(zip(expert), "minimal-expert").catalogCopy();

        assertThat(copy.defaultCopy())
                .containsEntry("title", "专家包中文标题")
                .containsEntry("summary", "专家包默认简介");
        assertThat(copy.forLanguage("en"))
                .containsEntry("title", "Expert Package Title")
                .containsEntry("summary", "Expert package English summary.");
    }

    @Test
    void validatesEmbeddedAgentMarkdownForExpertAndTeam() throws Exception {
        Path expertRoot = sharedVectors().resolve("valid/minimal-expert/package");
        Map<String, byte[]> expert = directoryEntries(expertRoot);
        String expertRuntime = Files.readString(
                expertRoot.resolve("com.yuzhihuihe.ycps/manifest.json"))
                .replace("{ \"type\": \"ENTRY_SKILL\" }",
                        "{ \"type\": \"EMBEDDED\", \"path\": "
                                + "\"./com.yuzhihuihe.ycps/agents/main.md\" }");
        expert.put(SkillPackageInspector.RUNTIME, bytes(expertRuntime));
        expert.put("com.yuzhihuihe.ycps/agents/main.md", bytes("""
                ---
                id: wrong-id
                description: Invalid identity
                ---
                body
                """));
        assertInvalid(zip(expert), "minimal-expert", "embedded_agent_invalid");

        Path teamRoot = sharedVectors().resolve("valid/minimal-team/package");
        Map<String, byte[]> team = directoryEntries(teamRoot);
        team.put("com.yuzhihuihe.ycps/agents/lead.md", bytes("""
                ﻿---
                id: lead
                description: Invalid BOM
                ---
                body
                """));
        assertInvalid(zip(team), "minimal-team", "embedded_agent_invalid");

        team = directoryEntries(teamRoot);
        team.put("com.yuzhihuihe.ycps/agents/reviewer.md", bytes("""
                ---
                id: reviewer
                id: reviewer
                description: Duplicate key
                ---
                """));
        assertInvalid(zip(team), "minimal-team", "embedded_agent_invalid");
    }

    @Test
    void executesEverySharedDirectoryConformanceVector() throws Exception {
        Path vectors = sharedVectors();
        try (Stream<Path> cases = Files.list(vectors.resolve("valid"))) {
            for (Path vector : cases.sorted().toList()) {
                Path packageRoot = vector.resolve("package");
                String code = new ObjectMapper().readTree(
                        Files.readString(packageRoot.resolve("plugin.json"))).path("name").asText();
                assertThat(inspect(zipDirectory(packageRoot), code).code()).isEqualTo(code);
            }
        }
        int rejected = 0;
        try (Stream<Path> cases = Files.list(vectors.resolve("invalid"))) {
            for (Path vector : cases.sorted().toList()) {
                Path packageRoot = vector.resolve("package");
                if (!Files.isDirectory(packageRoot)) {
                    continue;
                }
                rejected++;
                Path archive = zipDirectory(packageRoot);
                assertThatThrownBy(() -> inspect(archive, null))
                        .isInstanceOf(ResponseStatusException.class);
            }
        }
        assertThat(rejected).isGreaterThanOrEqualTo(8);
    }

    @Test
    void rejectsCaseCollisionsAndWin32AmbiguousNames() throws Exception {
        Map<String, byte[]> collision = validEntries();
        collision.put("Plugin.json", bytes("{}"));
        assertInvalidReason(zip(collision), "minimal-skill", "ycps_package_invalid");

        Map<String, byte[]> trailingDot = validEntries();
        trailingDot.put("skills/minimal-skill/notes.txt.", new byte[] {1});
        assertInvalidReason(zip(trailingDot), "minimal-skill", "ycps_package_unsafe_entry");

        Map<String, byte[]> reserved = validEntries();
        reserved.put("skills/minimal-skill/CON.txt", new byte[] {1});
        assertInvalidReason(zip(reserved), "minimal-skill", "ycps_package_unsafe_entry");
    }

    @Test
    void rejectsDuplicateJsonAndYamlKeys() throws Exception {
        Map<String, byte[]> duplicateJson = validEntries();
        duplicateJson.put("plugin.json", bytes(pluginJson().replace(
                "\"name\":\"minimal-skill\"",
                "\"name\":\"minimal-skill\",\"name\":\"minimal-skill\"")));
        assertInvalidReason(zip(duplicateJson), "minimal-skill", "ycps_package_invalid");

        Map<String, byte[]> duplicateYaml = validEntries();
        duplicateYaml.put("skills/minimal-skill/SKILL.md", bytes("""
                ---
                name: minimal-skill
                name: minimal-skill
                description: duplicate
                ---
                body
                """));
        assertInvalidReason(zip(duplicateYaml), "minimal-skill", "ycps_package_invalid");
    }

    @Test
    void rejectsZip64MarkersInsteadOfSkippingTheUnixModeScan() throws Exception {
        Path archive = zip(validEntries());
        byte[] bytes = Files.readAllBytes(archive);
        int eocd = lastSignature(bytes, EOCD);
        assertThat(eocd).isNotNegative();
        try (RandomAccessFile file = new RandomAccessFile(archive.toFile(), "rw")) {
            file.seek(eocd + 10L);
            file.write(0xff);
            file.write(0xff);
        }
        assertThat(SkillPackageInspector.hasSymlinkEntry(archive)).isTrue();
    }

    @Test
    void rejectsMacOsSymlinkModeAndTrailingBytes() throws Exception {
        Path archive = zip(validEntries());
        byte[] bytes = Files.readAllBytes(archive);
        int central = lastSignature(bytes, CENTRAL);
        assertThat(central).isNotNegative();
        try (RandomAccessFile file = new RandomAccessFile(archive.toFile(), "rw")) {
            file.seek(central + 5L);
            file.write(19);
            file.seek(central + 40L);
            file.write(0xff);
            file.write(0xa1);
        }
        assertThat(SkillPackageInspector.hasSymlinkEntry(archive)).isTrue();

        Path trailing = zip(validEntries());
        try (RandomAccessFile file = new RandomAccessFile(trailing.toFile(), "rw")) {
            file.seek(file.length());
            file.write(0x7f);
        }
        assertThat(SkillPackageInspector.hasSymlinkEntry(trailing)).isTrue();
    }

    @Test
    void scansPastOneMegabyteOfCentralDirectoryAndRejectsLateSpecialFile() throws Exception {
        Map<String, byte[]> entries = validEntries();
        for (int index = 0; index < 28; index++) {
            entries.put("skills/minimal-skill/" + String.format("%02d-", index)
                    + "a".repeat(40_000), new byte[] {1});
        }
        Path archive = zip(entries);
        byte[] bytes = Files.readAllBytes(archive);
        int lastCentral = lastSignature(bytes, CENTRAL);
        assertThat(lastCentral).isGreaterThan(1 << 20);
        try (RandomAccessFile file = new RandomAccessFile(archive.toFile(), "rw")) {
            file.seek(lastCentral + 5L);
            file.write(3);
            file.seek(lastCentral + 40L);
            file.write(0);
            file.write(0x10);
        }
        assertThat(SkillPackageInspector.hasSymlinkEntry(archive)).isTrue();
    }

    private SkillPackageInspector.Report inspect(Path archive, String expectedCode) {
        return SkillPackageInspector.create().inspect(archive, new SkillProperties(), expectedCode);
    }

    private void assertInvalid(Path archive, String code, String detail) {
        assertThatThrownBy(() -> inspect(archive, code))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getReason()).isIn(
                        "ycps_package_invalid", "ycps_package_identity_mismatch",
                        "package_identity_mismatch");
                    assertThat(error.getCause()).hasMessageContaining(detail);
                });
    }

    private void assertInvalidReason(Path archive, String code, String reason) {
        assertThatThrownBy(() -> inspect(archive, code))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getReason()).isEqualTo(reason));
    }

    private Path zip(Map<String, byte[]> entries) throws Exception {
        Path archive = Files.createTempFile(directory, "skill-", ".ycps.zip");
        try (OutputStream output = Files.newOutputStream(archive);
                ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return archive;
    }

    private Path zipDirectory(Path packageRoot) throws Exception {
        return zip(directoryEntries(packageRoot));
    }

    private static Map<String, byte[]> directoryEntries(Path packageRoot) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(packageRoot)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                entries.put(packageRoot.relativize(file).toString().replace('\\', '/'),
                        Files.readAllBytes(file));
            }
        }
        return entries;
    }

    private static Path sharedVectors() {
        return fixtureDirectory("docs/standards/ycps/1.0.0/test-vectors");
    }

    private static Path sharedVectors11() {
        return fixtureDirectory("docs/standards/ycps/1.1.0/test-vectors");
    }

    private static Path curatedFixturePackages() {
        return fixtureDirectory("fixtures/catalog-snapshot/packages");
    }

    private static Path fixtureDirectory(String relativePath) {
        for (Path start : List.of(Path.of("").toAbsolutePath(), testClassLocation())) {
            for (Path ancestor = start; ancestor != null; ancestor = ancestor.getParent()) {
                Path candidate = ancestor.resolve(relativePath);
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("Missing test fixture directory: " + relativePath);
    }

    private static Path testClassLocation() {
        try {
            return Path.of(SkillPackageInspectorTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath();
        } catch (URISyntaxException error) {
            throw new IllegalStateException("Cannot locate compiled test classes", error);
        }
    }

    private static String fixtureText(byte[] content) {
        return new String(content, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String replaceFixture(String original, String expected, String replacement) {
        assertThat(original).as("Fixture must contain exactly one occurrence of %s", expected)
                .containsOnlyOnce(expected);
        return original.replace(expected, replacement);
    }

    private static Map<String, byte[]> validEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("plugin.json", bytes(pluginJson()));
        entries.put(SkillPackageInspector.RUNTIME, bytes(runtimeJson("[]", "[]", "[]")));
        entries.put("skills/minimal-skill/SKILL.md", bytes(skillDocument()));
        return entries;
    }

    private static String pluginJson() {
        return """
                {"$schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json",
                 "name":"minimal-skill","version":"1.0.0+vector.1",
                 "description":"Minimal YCPS fixture","author":{"name":"Yuzhi Tests"},
                 "license":"MIT","extensions":{"com.yuzhihuihe.ycps":{
                 "ycpsVersion":"1.0.0","kind":"SKILL","entrySkill":"minimal-skill",
                 "manifest":"./com.yuzhihuihe.ycps/manifest.json","requiredFeatures":[]}}}
                """;
    }

    private static String runtimeJson(String dependencies, String tools, String network) {
        return """
                {"$schema":"https://schemas.yuzhihuihe.com/ycps/1.0.0/skill-manifest.schema.json",
                 "dependencies":%s,"permissionRequests":{"tools":%s,"network":%s,
                 "filesystem":[]},"hostRequirements":{}}
                """.formatted(dependencies, tools, network);
    }

    private static String dependencies(int count) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append("{\"key\":\"dependency-").append(index)
                    .append("\",\"relation\":\"SKILL_BINDING\",\"required\":true,")
                    .append("\"package\":{\"code\":\"dependency-").append(index)
                    .append("\",\"kind\":\"SKILL\",\"version\":\"1.0.0\",")
                    .append("\"revision\":1,\"sha256\":\"")
                    .append("a".repeat(64)).append("\"}}");
        }
        return result.append(']').toString();
    }

    private static String skillDocument() {
        return """
                ---
                name: minimal-skill
                description: Minimal YCPS fixture
                ---

                # Minimal Skill
                Return a deterministic acknowledgement.
                """;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] png(int width, int height) {
        byte[] image = new byte[24];
        image[0] = (byte) 0x89;
        image[1] = 'P';
        image[2] = 'N';
        image[3] = 'G';
        image[4] = 0x0d;
        image[5] = 0x0a;
        image[6] = 0x1a;
        image[7] = 0x0a;
        image[12] = 'I';
        image[13] = 'H';
        image[14] = 'D';
        image[15] = 'R';
        image[16] = (byte) (width >>> 24);
        image[17] = (byte) (width >>> 16);
        image[18] = (byte) (width >>> 8);
        image[19] = (byte) width;
        image[20] = (byte) (height >>> 24);
        image[21] = (byte) (height >>> 16);
        image[22] = (byte) (height >>> 8);
        image[23] = (byte) height;
        return image;
    }

    private static byte[] webpVp8(int width, int height) {
        // RIFF + WEBP + VP8 chunk, with a minimal lossy VP8 frame tag and dimensions.
        byte[] image = new byte[30];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, image, 0, 4);
        int riffSize = image.length - 8;
        image[4] = (byte) riffSize;
        image[5] = (byte) (riffSize >>> 8);
        image[6] = (byte) (riffSize >>> 16);
        image[7] = (byte) (riffSize >>> 24);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, image, 8, 4);
        System.arraycopy("VP8 ".getBytes(StandardCharsets.US_ASCII), 0, image, 12, 4);
        image[16] = 10;
        image[20] = 0;
        image[21] = 0;
        image[22] = 0;
        image[23] = (byte) 0x9d;
        image[24] = 0x01;
        image[25] = 0x2a;
        image[26] = (byte) width;
        image[27] = (byte) (width >>> 8);
        image[28] = (byte) height;
        image[29] = (byte) (height >>> 8);
        return image;
    }

    private static int lastSignature(byte[] bytes, long signature) {
        for (int index = bytes.length - 4; index >= 0; index--) {
            long value = (bytes[index] & 0xffL)
                    | ((bytes[index + 1] & 0xffL) << 8)
                    | ((bytes[index + 2] & 0xffL) << 16)
                    | ((bytes[index + 3] & 0xffL) << 24);
            if (value == signature) {
                return index;
            }
        }
        return -1;
    }
}
