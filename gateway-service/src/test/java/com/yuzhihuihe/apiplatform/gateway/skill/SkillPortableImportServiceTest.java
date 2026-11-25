package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportRequests.FieldAction;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportRequests.ImageAction;
import org.junit.jupiter.api.Test;

/** Pure action semantics for the isolated Expert portable-import commit path. */
class SkillPortableImportServiceTest {

    private static final String IMPORT_ID = "00000000-0000-4000-8000-000000000001";

    @Test
    void fillEmptyCreatesAValueButNeverOverwritesExistingContent() {
        assertThat(SkillPortableImportService.choose("package", null, FieldAction.FILL_EMPTY, false))
                .isEqualTo("package");
        assertThat(SkillPortableImportService.choose("package", "current", FieldAction.FILL_EMPTY, false))
                .isEqualTo("current");
    }

    @Test
    void overwriteReplacesScalarAndEmptyPackageValuesNeverClear() {
        assertThat(SkillPortableImportService.choose("package", "current", FieldAction.OVERWRITE, false))
                .isEqualTo("package");
        assertThat(SkillPortableImportService.choose("", "current", FieldAction.OVERWRITE, false))
                .isEqualTo("current");
        @SuppressWarnings("unchecked")
        List<String> kept = (List<String>) SkillPortableImportService.choose(
                List.of(), List.of("current"), FieldAction.OVERWRITE, true);
        assertThat(kept)
                .containsExactly("current");
    }

    @Test
    void mergeKeepsCurrentOrderAndAppendsOnlyNewValues() {
        @SuppressWarnings("unchecked")
        List<String> merged = (List<String>) SkillPortableImportService.choose(
                List.of("b", "c", "c"), List.of("a", "b"), FieldAction.MERGE, true);
        assertThat(merged)
                .containsExactly("a", "b", "c");
    }

    @Test
    void exampleImageImportCarriesItsLocaleWithTheImageBytes() {
        assertThat(SkillPortableImportService.exampleImageLocaleAfterImport(
                "en", "zh", true, true, ImageAction.OVERWRITE)).isEqualTo("en");
        assertThat(SkillPortableImportService.exampleImageLocaleAfterImport(
                "neutral", "zh", true, false, ImageAction.FILL_EMPTY)).isEqualTo("neutral");
        assertThat(SkillPortableImportService.exampleImageLocaleAfterImport(
                "en", "zh", true, true, ImageAction.FILL_EMPTY)).isEqualTo("zh");
        // An omitted package locale is the legacy zh semantic and must not retain a stale
        // explicit locale when the package image is accepted.
        assertThat(SkillPortableImportService.exampleImageLocaleAfterImport(
                null, "en", true, true, ImageAction.OVERWRITE)).isNull();
        assertThat(SkillPortableImportService.exampleImageLocaleAfterImport(
                "en", "zh", false, true, ImageAction.OVERWRITE)).isEqualTo("zh");
    }

    @Test
    void replacedImagesOnlyReturnsPointersActuallyReplaced() {
        Map<String, String> previous = new LinkedHashMap<>();
        previous.put("icon", "old-icon");
        previous.put("illustration", "old-illustration");
        previous.put("exampleImage", "old-example");
        Map<String, String> current = new LinkedHashMap<>();
        current.put("icon", "new-icon");
        current.put("illustration", "old-illustration");
        current.put("exampleImage", null);

        assertThat(SkillPortableImportService.replacedImages(previous, current))
                .containsExactly("old-icon");
    }

    @Test
    void assetRoleInventoryRejectsMissingAndExtraStagedRoles() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode onlyIcon = mapper.createObjectNode();
        onlyIcon.putObject("icon");
        ObjectNode iconAndExtra = onlyIcon.deepCopy();
        iconAndExtra.putObject("unexpected");

        assertThat(SkillPortableImportService.stagedAssetRolesMatch(onlyIcon, Set.of("icon")))
                .isTrue();
        assertThat(SkillPortableImportService.stagedAssetRolesMatch(onlyIcon,
                Set.of("icon", "illustration"))).isFalse();
        assertThat(SkillPortableImportService.stagedAssetRolesMatch(iconAndExtra, Set.of("icon")))
                .isFalse();
    }

    @Test
    void assetMetadataMustPointAtTheImportQuarantineAndFreshInspectorValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String sha256 = "a".repeat(64);
        SkillPackageInspector.PortableAsset asset = new SkillPackageInspector.PortableAsset(
                "icon", "skills/demo/assets/icon.png", "image/png", 3, sha256,
                new byte[] {1, 2, 3});
        ObjectNode stored = mapper.createObjectNode()
                .put("key", "quarantine/skill-portable-imports/" + IMPORT_ID + "/asset-icon-" + sha256 + ".png")
                .put("path", asset.packagePath())
                .put("mediaType", asset.mediaType())
                .put("sizeBytes", asset.sizeBytes())
                .put("sha256", sha256);

        assertThat(SkillPortableImportService.stagedAssetMetadataMatches(
                stored, asset, "quarantine/skill-portable-imports/" + IMPORT_ID + "/"))
                .isTrue();
        assertThat(SkillPortableImportService.stagedAssetMetadataMatches(
                stored.put("sha256", "b".repeat(64)), asset,
                "quarantine/skill-portable-imports/" + IMPORT_ID + "/"))
                .isFalse();
        assertThat(SkillPortableImportService.stagedAssetMetadataMatches(
                stored.put("sha256", sha256).put("key", "clean/not-this-import.png"), asset,
                "quarantine/skill-portable-imports/" + IMPORT_ID + "/"))
                .isFalse();
    }
}
