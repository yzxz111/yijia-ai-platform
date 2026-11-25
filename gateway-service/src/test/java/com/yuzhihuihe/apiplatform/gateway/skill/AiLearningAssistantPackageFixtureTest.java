package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * Offline acceptance coverage for the AI Learning Assistant YCPS fixture.
 *
 * <p>This deliberately calls the same inspector used by the admin upload path. It reads only the
 * checked-in archive, so it does not create a quarantine import or touch a cloud database.
 */
class AiLearningAssistantPackageFixtureTest {

    private static final String CODE = "ai-learning-assistant";
    private static final String VERSION = "1.0.0";
    private static final String PACKAGE_NAME = CODE + "-" + VERSION + ".ycps.zip";

    @Test
    void inspectsTheCompleteBilingualPresentationAndAllThreeAssets() throws Exception {
        Path archive = fixtureArchive();

        SkillPackageInspector.Report report = SkillPackageInspector.create()
                .inspect(archive, new SkillProperties(), CODE);

        assertThat(report.code()).isEqualTo(CODE);
        assertThat(report.version()).isEqualTo(VERSION);
        assertThat(report.kind()).isEqualTo("EXPERT");
        assertThat(report.ycpsVersion()).isEqualTo("1.1.0");
        assertThat(report.minClientVersion()).isEqualTo("2026.6.30");
        assertThat(report.fileCount()).isEqualTo(6);

        JsonNode extension = report.pluginManifest()
                .path("extensions")
                .path("com.yuzhihuihe.ycps");
        assertThat(extension.path("ycpsVersion").asText()).isEqualTo("1.1.0");
        assertThat(extension.path("kind").asText()).isEqualTo("EXPERT");
        assertThat(extension.path("requiredFeatures")).hasSize(1);
        assertThat(extension.path("requiredFeatures").get(0).asText())
                .isEqualTo("expert-presentation-v1");

        SkillPackageInspector.ExpertPresentation presentation = report.expertPresentation();
        assertThat(presentation).isNotNull();
        assertThat(presentation.title()).isEqualTo("AI学习助手");
        assertThat(presentation.titleEn()).isEqualTo("AI Learning Assistant");
        assertThat(presentation.taskTypes())
                .containsExactly("学习计划", "主动回忆", "间隔复习", "错题复盘");
        assertThat(presentation.taskTypesEn())
                .containsExactly("Study plans", "Active recall", "Spaced review", "Mistake review");
        assertThat(presentation.examplePrompts()).hasSize(3);
        assertThat(presentation.examplePromptsEn()).hasSize(3);
        assertThat(presentation.usageGuide()).contains("学习目标", "课程材料或错题");
        assertThat(presentation.usageGuideEn()).contains("goal", "missed questions");

        assertPreviewTable(presentation.preview(),
                List.of("效率方法", "用时", "具体行动", "检查点"),
                List.of("主动回忆", "10分钟", "合上笔记写出今天的3个关键概念", "能用自己的话解释"));
        assertPreviewTable(presentation.previewEn(),
                List.of("Technique", "Time", "Action", "Check"),
                List.of("Active recall", "10 min", "Close the notes and write 3 key ideas",
                        "Explain them in your own words"));
        assertThat(presentation.preview().intro().text())
                .isEqualTo("每天45分钟｜先回忆，再练习，最后检查");
        assertThat(presentation.previewEn().intro().text())
                .isEqualTo("45 minutes a day | recall, practice, then check");

        JsonNode canvas = presentation.canvas();
        assertThat(canvas).isNotNull();
        assertCanvas(canvas, "zh", "AI", "学习助手", "效率提示：");
        assertCanvas(canvas, "en", "AI ", "Learning Assistant", "Efficiency tip: ");

        assertThat(presentation.exampleImageLocale()).isEqualTo("zh");
        assertThat(presentation.assets()).containsOnlyKeys("icon", "illustration", "exampleImage");
        assertAsset(presentation.assets().get("icon"),
                "icon", "skills/ai-learning-assistant/assets/icon.webp", "image/webp", "RIFF");
        assertAsset(presentation.assets().get("illustration"),
                "illustration", "skills/ai-learning-assistant/assets/illustration.webp", "image/webp", "RIFF");
        assertAsset(presentation.assets().get("exampleImage"),
                "exampleImage", "skills/ai-learning-assistant/assets/example-image.png", "image/png", "\u0089PNG");
    }

    private static void assertPreviewTable(
            com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewContent preview,
            List<String> headers,
            List<String> firstRow) {
        assertThat(preview).isNotNull();
        assertThat(preview.table()).isNotNull();
        assertThat(preview.table().headers()).containsExactlyElementsOf(headers);
        assertThat(preview.table().columnWidths()).containsExactly(22, 16, 38, 24);
        assertThat(preview.table().rows()).hasSize(3);
        assertThat(preview.table().rows().get(0)).containsExactlyElementsOf(firstRow);
        assertThat(preview.table().style().fontSize()).isEqualTo(13);
        assertThat(preview.table().style().headerColor()).isEqualTo("#3152a0");
        assertThat(preview.table().style().headerBackgroundColor()).isEqualTo("#eaf0ff");
        assertThat(preview.table().style().borderColor()).isEqualTo("#d8e2f3");
        assertThat(preview.table().style().rowHeight()).isEqualTo(39);
    }

    private static void assertCanvas(
            JsonNode canvas,
            String locale,
            String firstTitleRun,
            String secondTitleRun,
            String tipRun) {
        JsonNode localized = canvas.path(locale);
        assertThat(localized.path("version").asInt()).isEqualTo(1);
        assertThat(localized.path("width").asInt()).isEqualTo(1080);
        assertThat(localized.path("height").asInt()).isEqualTo(704);
        assertThat(localized.path("nodes")).hasSize(22);

        JsonNode title = canvasNode(localized, "title");
        assertThat(title.path("runs")).hasSize(2);
        assertThat(title.path("runs").get(0).path("text").asText()).isEqualTo(firstTitleRun);
        assertThat(title.path("runs").get(0).path("color").asText()).isEqualTo("#436fc6");
        assertThat(title.path("runs").get(0).path("fontSize").asInt()).isGreaterThanOrEqualTo(22);
        assertThat(title.path("runs").get(1).path("text").asText()).isEqualTo(secondTitleRun);
        assertThat(title.path("runs").get(1).path("color").asText()).isEqualTo("#182a47");

        JsonNode heading = canvasNode(localized, "preview.heading");
        assertThat(heading.path("runs")).hasSize(2);
        assertThat(heading.path("runs").get(0).path("text").asText())
                .isEqualTo("zh".equals(locale) ? "一句话" : "One sentence");
        assertThat(heading.path("runs").get(0).path("color").asText()).isEqualTo("#5574b5");
        assertThat(heading.path("runs").get(1).has("color")).isFalse();
        assertThat(canvasNode(localized, "tag.0").path("runs").get(0).has("color")).isFalse();

        JsonNode request = canvasNode(localized, "preview.request");
        JsonNode material = canvasNode(localized, "preview.material");
        assertThat(request.path("x").asInt()).isEqualTo(32);
        assertThat(request.path("width").asInt()).isEqualTo(536);
        assertThat(material.path("x").asInt()).isEqualTo(32);
        assertThat(material.path("width").asInt()).isEqualTo(536);
        assertThat(material.path("y").asInt() + material.path("height").asInt()
                - request.path("y").asInt()).isEqualTo(140);

        JsonNode output = canvasNode(localized, "preview.outputParagraphs.2");
        assertThat(output.path("runs")).hasSize(2);
        assertThat(output.path("runs").get(0).path("text").asText()).isEqualTo(tipRun);
        assertThat(output.path("runs").get(0).path("color").asText()).isEqualTo("#b45a24");
        assertThat(output.path("runs").get(0).path("fontWeight").asInt()).isEqualTo(700);
        assertThat(output.path("runs").get(1).path("color").asText()).isEqualTo("#475569");

        JsonNode table = canvasNode(localized, "preview.table");
        assertThat(table.path("kind").asText()).isEqualTo("table");
        assertThat(table.path("table").path("headers")).hasSize(4);
        assertThat(canvasNode(localized, "asset.illustration").path("assetRole").asText())
                .isEqualTo("illustration");
        assertThat(canvasNode(localized, "asset.icon").path("assetRole").asText())
                .isEqualTo("icon");
    }

    private static JsonNode canvasNode(JsonNode canvas, String field) {
        for (JsonNode node : canvas.path("nodes")) {
            if (field.equals(node.path("field").asText())) {
                return node;
            }
        }
        throw new AssertionError("Missing canvas field: " + field);
    }

    private static void assertAsset(
            SkillPackageInspector.PortableAsset asset,
            String role,
            String packagePath,
            String mediaType,
            String magic) {
        assertThat(asset).isNotNull();
        assertThat(asset.role()).isEqualTo(role);
        assertThat(asset.packagePath()).isEqualTo(packagePath);
        assertThat(asset.mediaType()).isEqualTo(mediaType);
        assertThat(asset.sizeBytes()).isGreaterThan(0L);
        assertThat(asset.sha256()).matches("[0-9a-f]{64}");
        assertThat(asset.sha256()).isEqualTo(sha256(asset.content()));
        assertThat(new String(asset.content(), 0, Math.min(asset.content().length, magic.length()),
                StandardCharsets.ISO_8859_1))
                .isEqualTo(magic);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static Path fixtureArchive() {
        List<Path> candidates = List.of(
                Path.of("..", "fixtures", "portable-import", CODE, PACKAGE_NAME),
                Path.of("fixtures", "portable-import", CODE, PACKAGE_NAME),
                Path.of("system", "fixtures", "portable-import", CODE, PACKAGE_NAME));
        return candidates.stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "AI Learning Assistant fixture not found; checked " + candidates));
    }
}
