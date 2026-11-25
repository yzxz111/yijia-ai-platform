package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import com.yuzhihuihe.apiplatform.gateway.file.StoredObjectMetadata;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillVersionView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportViews.ImportResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Real archive inspection and offline cloud revision compilation for one new Expert code. */
class WechatMiniappDeveloperPackageAcceptanceTest {

    private static final String CODE = "wechat-miniapp-developer";
    private static final String VERSION = "1.0.0";
    private static final String PACKAGE_NAME = CODE + "-" + VERSION + ".ycps.zip";

    @Test
    void inspectsTheBilingualExpertPackageAndItsRuntime() throws Exception {
        SkillPackageInspector.Report report = inspect(fixtureArchive());

        assertThat(report.code()).isEqualTo(CODE);
        assertThat(report.version()).isEqualTo(VERSION);
        assertThat(report.kind()).isEqualTo("EXPERT");
        assertThat(report.ycpsVersion()).isEqualTo("1.1.0");
        assertThat(report.minClientVersion()).isEqualTo("2026.6.30");
        assertThat(report.pluginManifest().path("extensions").path("com.yuzhihuihe.ycps")
                .path("requiredFeatures").get(0).asText())
                .isEqualTo("expert-presentation-v1");
        JsonNode runtime = report.runtimeManifest();
        assertThat(runtime.path("entryAgent").asText()).isEqualTo("main");
        assertThat(runtime.path("agents")).hasSize(1);
        assertThat(runtime.path("agents").get(0).path("role").asText()).isEqualTo("LEAD");
        assertThat(runtime.path("agents").get(0).path("instructions").path("type").asText())
                .isEqualTo("ENTRY_SKILL");
        assertThat(runtime.path("dependencies")).isEmpty();
        assertThat(runtime.path("permissionRequests").path("tools")).isEmpty();

        SkillPackageInspector.ExpertPresentation presentation = report.expertPresentation();
        assertThat(presentation).isNotNull();
        JsonNode expected = new ObjectMapper().readTree(Files.readAllBytes(
                fixtureArchive().getParent().resolve("expected-admin-presentation.json")));
        assertThat(presentation.title()).isEqualTo(expected.path("name").asText());
        assertThat(presentation.titleEn()).isEqualTo(expected.path("nameEn").asText());
        assertThat(presentation.summary()).isEqualTo(expected.path("summary").asText());
        assertThat(presentation.summaryEn()).isEqualTo(expected.path("summaryEn").asText());
        assertThat(presentation.description()).isEqualTo(expected.path("description").asText());
        assertThat(presentation.descriptionEn()).isEqualTo(expected.path("descriptionEn").asText());
        assertThat(toJson(presentation.taskTypes()))
                .isEqualTo(expected.path("taskTypes"));
        assertThat(toJson(presentation.taskTypesEn()))
                .isEqualTo(expected.path("taskTypesEn"));
        assertThat(toJson(presentation.examplePrompts()))
                .isEqualTo(expected.path("examplePrompts"));
        assertThat(toJson(presentation.examplePromptsEn()))
                .isEqualTo(expected.path("examplePromptsEn"));
        assertThat(presentation.preview().heading())
                .isEqualTo(expected.path("preview").path("heading").asText());
        assertThat(presentation.previewEn().heading())
                .isEqualTo(expected.path("previewEn").path("heading").asText());
        assertThat(presentation.canvas()).isEqualTo(expected.path("canvas"));
        assertThat(presentation.title()).isNotBlank();
        assertThat(presentation.titleEn()).isNotBlank();
        assertThat(presentation.summary()).isNotBlank();
        assertThat(presentation.summaryEn()).isNotBlank();
        assertThat(presentation.description()).isNotBlank();
        assertThat(presentation.descriptionEn()).isNotBlank();
        assertThat(presentation.usageGuide()).isNotBlank();
        assertThat(presentation.usageGuideEn()).isNotBlank();
        assertThat(presentation.taskTypes()).isNotEmpty();
        assertThat(presentation.taskTypesEn()).hasSameSizeAs(presentation.taskTypes());
        assertThat(presentation.examplePrompts()).isNotEmpty();
        assertThat(presentation.examplePromptsEn()).hasSameSizeAs(presentation.examplePrompts());
        assertThat(presentation.preview()).isNotNull();
        assertThat(presentation.previewEn()).isNotNull();
        assertThat(presentation.preview().heading()).isNotBlank();
        assertThat(presentation.previewEn().heading()).isNotBlank();
        assertThat(presentation.assets()).containsOnlyKeys("icon", "illustration", "exampleImage");
        for (String role : List.of("icon", "illustration", "exampleImage")) {
            SkillPackageInspector.PortableAsset asset = presentation.assets().get(role);
            assertThat(asset.packagePath()).startsWith("skills/" + CODE + "/assets/");
            assertThat(asset.sizeBytes()).isGreaterThan(0L);
            assertThat(asset.sha256()).isEqualTo(sha256(asset.content()));
        }
        if (presentation.canvas() != null) {
            for (String locale : List.of("zh", "en")) {
                JsonNode canvas = presentation.canvas().path(locale);
                assertThat(canvas.path("version").asInt()).isEqualTo(1);
                assertThat(canvas.path("width").asInt()).isEqualTo(1080);
                assertThat(canvas.path("height").asInt()).isEqualTo(704);
                assertThat(canvas.path("nodes").size()).isGreaterThan(0);
            }
        }
    }

    @Test
    void stagesTheRealArchiveThroughThePortableUploadPath() throws Exception {
        Path archive = fixtureArchive();
        byte[] archiveBytes = Files.readAllBytes(archive);
        SkillPackageInspector.Report report = inspect(archive);
        String archiveSha256 = sha256(archiveBytes);
        SkillPackageIngestService.InspectedPackage inspected =
                new SkillPackageIngestService.InspectedPackage(report,
                        new SkillPackageIngestService.Digest(
                                archiveBytes.length, digest("MD5", archiveBytes), archiveSha256));

        FilePart upload = mock(FilePart.class);
        when(upload.filename()).thenReturn(PACKAGE_NAME);
        SkillRepository skills = mock(SkillRepository.class);
        SkillAdminService admin = mock(SkillAdminService.class);
        SkillExpertProfileRepository profiles = mock(SkillExpertProfileRepository.class);
        SkillExpertAggregateRepository aggregates = mock(SkillExpertAggregateRepository.class);
        SkillPackageIngestService ingest = mock(SkillPackageIngestService.class);
        SkillVersionRepository versions = mock(SkillVersionRepository.class);
        SkillPortableImportRepository imports = mock(SkillPortableImportRepository.class);
        FileObjectStore objects = mock(FileObjectStore.class);

        when(ingest.transferOnly(eq(upload), any(Path.class))).thenAnswer(invocation -> {
            Files.copy(archive, invocation.getArgument(1, Path.class),
                    StandardCopyOption.REPLACE_EXISTING);
            return Mono.empty();
        });
        when(ingest.inspectOnly(any(Path.class))).thenReturn(Mono.just(inspected));
        when(ingest.scanOnly(any(Path.class)))
                .thenReturn(Mono.just(new SkillPackageIngestService.MalwareScan(false, "DISABLED")));
        when(skills.skillByCode(CODE)).thenReturn(Mono.empty());
        when(versions.nextRevision(CODE, VERSION)).thenReturn(Mono.just(1));
        when(objects.putFile(anyString(), any(Path.class), anyString())).thenReturn(Mono.empty());
        when(objects.put(anyString(), any(byte[].class), anyString())).thenReturn(Mono.empty());
        when(objects.head(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            long size = key.contains("artifact-" + archiveSha256) ? archiveBytes.length
                    : report.expertPresentation().assets().values().stream()
                            .filter(asset -> key.contains(
                                    "asset-" + asset.role() + "-" + asset.sha256()))
                            .mapToLong(SkillPackageInspector.PortableAsset::sizeBytes)
                            .findFirst().orElse(-1L);
            return size < 0 ? Mono.empty()
                    : Mono.just(new StoredObjectMetadata(key, size,
                            "application/octet-stream", null,
                            Instant.parse("2026-10-08T00:00:00Z")));
        });
        when(imports.insert(any(SkillPortableImportRepository.ImportRecord.class)))
                .thenReturn(Mono.just(1L));

        ImportResponse staged = new SkillPortableImportService(
                skills, admin, profiles, aggregates, ingest, versions, imports, objects,
                null, new ObjectMapper()).upload(upload, UUID.randomUUID()).block();

        assertThat(staged).isNotNull();
        assertThat(staged.status()).isEqualTo("SCANNED");
        assertThat(staged.artifactSha256()).isEqualTo(archiveSha256);
        assertThat(staged.identity().code()).isEqualTo(CODE);
        assertThat(staged.identity().version()).isEqualTo(VERSION);
        assertThat(staged.resources().count()).isEqualTo(3);
        assertThat(staged.fields().get("name").packageValue().asText())
                .isEqualTo(report.expertPresentation().title());
        assertThat(staged.fields().get("nameEn").packageValue().asText())
                .isEqualTo(report.expertPresentation().titleEn());
    }

    @Test
    void recompilesTheRealArchiveWithoutLosingPresentationAssetsOrReferenceFiles() throws Exception {
        Path archive = fixtureArchive();
        byte[] archiveBytes = Files.readAllBytes(archive);
        SkillPackageInspector.Report original = inspect(archive);
        SkillPackageInspector.ExpertPresentation presentation = original.expertPresentation();
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-08T00:00:00Z");

        SkillRepository skills = mock(SkillRepository.class);
        SkillVersionRepository versions = mock(SkillVersionRepository.class);
        SkillExpertProfileRepository profiles = mock(SkillExpertProfileRepository.class);
        SkillPackageIngestService ingest = mock(SkillPackageIngestService.class);
        FileObjectStore objects = mock(FileObjectStore.class);
        when(skills.skill(skillId)).thenReturn(Mono.just(skillView(skillId, presentation, now)));
        when(profiles.findBySkillId(skillId)).thenReturn(Mono.just(
                new SkillExpertProfileRepository.StoredProfile(
                        skillId, CODE, "EXPERT", "DRAFT",
                        presentation.taskTypes(), presentation.taskTypesEn(),
                        presentation.usageGuide(), presentation.usageGuideEn(),
                        presentation.examplePrompts(), presentation.examplePromptsEn(),
                        presentation.preview(), presentation.previewEn(), presentation.canvas(),
                        presentation.exampleImageLocale(), JsonNodeFactory.instance.objectNode(),
                        0, 1, now, now)));
        when(versions.packageByVersionId(skillId, versionId)).thenReturn(Mono.just(
                new SkillVersionRepository.PackageRef(
                        skillId, versionId, VERSION, "quarantine/base-fixture.ycps.zip",
                        archiveBytes.length, digest("MD5", archiveBytes), sha256(archiveBytes),
                        original.minClientVersion(), 1, "YCPS", "1.1.0", "EXPERT",
                        JsonNodeFactory.instance.arrayNode(), original.manifestSha256(),
                        original.dependencyLockSha256())));
        when(versions.portableRevisionByInputs(eq(skillId), eq(VERSION),
                eq(sha256(archiveBytes)), anyString())).thenReturn(Mono.empty());
        when(objects.getRange(anyString(), eq(0L), eq((long) archiveBytes.length)))
                .thenReturn(Flux.just(ByteBuffer.wrap(archiveBytes)));
        for (String role : List.of("icon", "illustration", "exampleImage")) {
            SkillPackageInspector.PortableAsset asset = presentation.assets().get(role);
            String objectKey = "clean/skills/" + CODE + "/" + role + "-" + asset.sha256()
                    + asset.packagePath().substring(asset.packagePath().lastIndexOf('.'));
            switch (role) {
                case "icon" -> when(skills.iconObjectKey(skillId)).thenReturn(Mono.just(objectKey));
                case "illustration" -> when(skills.illustrationObjectKey(skillId))
                        .thenReturn(Mono.just(objectKey));
                default -> when(skills.exampleImageObjectKey(skillId))
                        .thenReturn(Mono.just(objectKey));
            }
            when(objects.get(eq(objectKey), anyLong())).thenReturn(Mono.just(asset.content()));
        }

        byte[][] generatedBytes = new byte[1][];
        SkillVersionView generatedVersion = mock(SkillVersionView.class);
        when(ingest.ingestGenerated(eq(skillId), eq(VERSION),
                eq(SkillPortableRevisionService.DEFAULT_MIN_CLIENT_VERSION), any(Path.class),
                anyString(), eq(actor), anyMap())).thenAnswer(invocation -> Mono.fromCallable(() -> {
                    generatedBytes[0] = Files.readAllBytes(invocation.getArgument(3, Path.class));
                    return generatedVersion;
                }));
        when(profiles.updatePortableMetadata(eq(skillId), any(JsonNode.class), anyInt(), anyInt()))
                .thenReturn(Mono.just(1L));

        new SkillPortableRevisionService(skills, versions, profiles, ingest, objects,
                new SkillProperties()).generate(skillId, versionId, actor).block();

        assertThat(generatedBytes[0]).isNotNull();
        Path generatedArchive = Files.createTempFile("wechat-miniapp-developer-roundtrip-", ".ycps.zip");
        try {
            Files.write(generatedArchive, generatedBytes[0]);
            SkillPackageInspector.Report regenerated = inspect(generatedArchive);
            SkillPackageInspector.ExpertPresentation result = regenerated.expertPresentation();
            assertThat(regenerated.code()).isEqualTo(CODE);
            assertThat(regenerated.kind()).isEqualTo("EXPERT");
            assertThat(result.title()).isEqualTo(presentation.title());
            assertThat(result.titleEn()).isEqualTo(presentation.titleEn());
            assertThat(result.summary()).isEqualTo(presentation.summary());
            assertThat(result.summaryEn()).isEqualTo(presentation.summaryEn());
            assertThat(result.description()).isEqualTo(presentation.description());
            assertThat(result.descriptionEn()).isEqualTo(presentation.descriptionEn());
            assertThat(result.taskTypes()).containsExactlyElementsOf(presentation.taskTypes());
            assertThat(result.taskTypesEn()).containsExactlyElementsOf(presentation.taskTypesEn());
            assertThat(result.examplePrompts())
                    .containsExactlyElementsOf(presentation.examplePrompts());
            assertThat(result.examplePromptsEn())
                    .containsExactlyElementsOf(presentation.examplePromptsEn());
            assertThat(result.preview()).isEqualTo(presentation.preview());
            assertThat(result.previewEn()).isEqualTo(presentation.previewEn());
            assertThat(result.canvas()).isEqualTo(presentation.canvas());
            for (String role : List.of("icon", "illustration", "exampleImage")) {
                assertThat(result.assets().get(role).sha256())
                        .as("round-trip %s asset SHA", role)
                        .isEqualTo(presentation.assets().get(role).sha256());
            }
            try (ZipFile before = new ZipFile(archive.toFile());
                    ZipFile after = new ZipFile(generatedArchive.toFile())) {
                for (String file : List.of("deliverable-templates.md", "worked-example.md")) {
                    String name = "skills/" + CODE + "/references/" + file;
                    assertThat(before.getEntry(name)).as("source reference %s", name).isNotNull();
                    assertThat(after.getEntry(name)).as("round-trip reference %s", name).isNotNull();
                    byte[] source = before.getInputStream(before.getEntry(name)).readAllBytes();
                    byte[] roundTripped = after.getInputStream(after.getEntry(name)).readAllBytes();
                    assertThat(sha256(roundTripped)).isEqualTo(sha256(source));
                }
            }
        } finally {
            Files.deleteIfExists(generatedArchive);
        }
    }

    private static SkillView skillView(
            UUID id, SkillPackageInspector.ExpertPresentation presentation, Instant now) {
        return new SkillView(
                id, CODE, presentation.title(), presentation.titleEn(), null, List.of(),
                presentation.summary(), presentation.summaryEn(), presentation.description(),
                presentation.descriptionEn(), null, null, null, null, null, null, null,
                "EXPERT", "DRAFT", VERSION, VERSION, "2026.6.30",
                null, null, null, 0, 0, 1,
                now, now, null, null, 0, 0, 0, null, List.of(), List.of(), "YCPS",
                false, null, null);
    }

    private static SkillPackageInspector.Report inspect(Path archive) {
        return SkillPackageInspector.create().inspect(archive, new SkillProperties(), CODE);
    }

    private static JsonNode toJson(Object value) {
        return new ObjectMapper().valueToTree(value);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return digest("SHA-256", bytes);
    }

    private static String digest(String algorithm, byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    }

    private static Path fixtureArchive() {
        List<Path> candidates = List.of(
                Path.of("..", "fixtures", "expert-expansion", CODE, PACKAGE_NAME),
                Path.of("fixtures", "expert-expansion", CODE, PACKAGE_NAME),
                Path.of("system", "fixtures", "expert-expansion", CODE, PACKAGE_NAME));
        return candidates.stream().filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new AssertionError("WeChat Miniapp Developer package not found: "
                        + candidates));
    }
}


