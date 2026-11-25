package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import com.yuzhihuihe.apiplatform.gateway.file.StoredObjectMetadata;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileRequests.ExpertAggregateCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportRequests.CommitRequest;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportViews.CommitResponse;
import com.yuzhihuihe.apiplatform.gateway.skill.SkillPortableImportRepository.ImportRecord;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportViews.ImportResponse;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillVersionView;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Isolated upload-half acceptance test for the checked-in AI Learning Assistant package.
 *
 * <p>The real inspector reads the real archive and the real upload staging path writes only to
 * Mockito-backed quarantine calls. No database or cloud object store is touched.
 */
class AiLearningAssistantPortableUploadTest {

    private static final String CODE = "ai-learning-assistant";
    private static final String VERSION = "1.0.0";
    private static final String PACKAGE_NAME = CODE + "-" + VERSION + ".ycps.zip";

    @Test
    void stagesTheRealFixtureAndPreservesPresentationAndAssetMappings() throws Exception {
        Path archive = fixtureArchive();
        byte[] archiveBytes = Files.readAllBytes(archive);
        SkillPackageInspector.Report report = SkillPackageInspector.create()
                .inspect(archive, new SkillProperties(), CODE);
        String sha256 = digest("SHA-256", archiveBytes);
        String md5 = digest("MD5", archiveBytes);
        SkillPackageIngestService.InspectedPackage inspected =
                new SkillPackageIngestService.InspectedPackage(
                        report, new SkillPackageIngestService.Digest(archiveBytes.length, md5, sha256));

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
            long size = stagedSize(key, sha256, archiveBytes.length, report);
            return size < 0
                    ? Mono.empty()
                    : Mono.just(new StoredObjectMetadata(key, size, "application/octet-stream",
                            null, Instant.parse("2026-09-20T00:00:00Z")));
        });

        ImportRecord[] inserted = new ImportRecord[1];
        when(imports.insert(any(ImportRecord.class))).thenAnswer(invocation -> {
            inserted[0] = invocation.getArgument(0, ImportRecord.class);
            return Mono.just(1L);
        });

        SkillPortableImportService service = new SkillPortableImportService(
                skills, admin, profiles, aggregates, ingest, versions, imports, objects, null,
                new com.fasterxml.jackson.databind.ObjectMapper());
        UUID actor = UUID.randomUUID();

        ImportResponse response = service.upload(upload, actor).block();

        assertThat(response).isNotNull();
        assertThat(response.artifactSha256()).isEqualTo(sha256);
        assertThat(response.identity().code()).isEqualTo(CODE);
        assertThat(response.identity().version()).isEqualTo(VERSION);
        assertThat(response.identity().revision()).isEqualTo(1);
        assertThat(response.status()).isEqualTo("SCANNED");
        assertThat(response.resources().count()).isEqualTo(3);
        assertThat(response.resources().resources()).extracting(resource -> resource.role())
                .containsExactlyInAnyOrder("icon", "illustration", "exampleImage");
        assertThat(response.fields().get("canvas").present()).isTrue();
        assertThat(response.fields().get("canvas").packageValue().path("zh").path("width").asInt())
                .isEqualTo(1080);
        assertThat(response.fields().get("canvas").packageValue().path("zh").path("nodes"))
                .hasSize(22);
        JsonNode zhCanvas = response.fields().get("canvas").packageValue().path("zh");
        assertThat(canvasNode(zhCanvas, "preview.request").path("width").asInt())
                .isEqualTo(536);
        assertThat(canvasNode(zhCanvas, "preview.material").path("width").asInt())
                .isEqualTo(536);
        assertThat(canvasNode(zhCanvas, "preview.request").path("height").asInt()
                + canvasNode(zhCanvas, "preview.material").path("height").asInt())
                .isEqualTo(140);
        assertThat(response.fields().get("exampleImageLocale").packageValue().asText())
                .isEqualTo("zh");
        assertThat(response.images()).containsOnlyKeys("icon", "illustration", "exampleImage");
        assertThat(response.images().get("exampleImage").packageValue().path("path").asText())
                .endsWith("assets/example-image.png");

        assertThat(inserted[0]).isNotNull();
        assertThat(inserted[0].importId()).isEqualTo(response.importId());
        assertThat(inserted[0].objectKey())
                .startsWith("quarantine/skill-portable-imports/" + response.importId() + "/artifact-");
        assertThat(inserted[0].assetKeys().fieldNames()).toIterable()
                .containsExactlyInAnyOrder("icon", "illustration", "exampleImage");
        assertThat(inserted[0].presentation().path("canvas").path("en").path("height").asInt())
                .isEqualTo(704);
        assertThat(inserted[0].presentation().path("preview").path("table").path("rows"))
                .hasSize(3);
        assertThat(inserted[0].createdBy()).isEqualTo(actor);
    }

    @Test
    void commitsTheRealFixtureIntoAggregateAndPromotesAllThreeImagePointers() throws Exception {
        Path archive = fixtureArchive();
        byte[] archiveBytes = Files.readAllBytes(archive);
        SkillPackageInspector.Report report = SkillPackageInspector.create()
                .inspect(archive, new SkillProperties(), CODE);
        String sha256 = digest("SHA-256", archiveBytes);
        String md5 = digest("MD5", archiveBytes);
        SkillPackageIngestService.InspectedPackage inspected =
                new SkillPackageIngestService.InspectedPackage(
                        report, new SkillPackageIngestService.Digest(archiveBytes.length, md5, sha256));

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
        // Keep the new-skill path isolated: no existing catalog row or profile is read or changed.
        when(skills.skillByCode(CODE)).thenReturn(Mono.empty());
        when(versions.nextRevision(CODE, VERSION)).thenReturn(Mono.just(1));
        when(ingest.validateDependenciesOnly(report)).thenReturn(Mono.just(List.of()));

        when(objects.putFile(anyString(), any(Path.class), anyString())).thenReturn(Mono.empty());
        when(objects.put(anyString(), any(byte[].class), anyString())).thenReturn(Mono.empty());
        when(objects.getRange(anyString(), anyLong(), anyLong()))
                .thenReturn(Flux.just(ByteBuffer.wrap(archiveBytes)));
        when(objects.get(anyString(), anyLong())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            return report.expertPresentation().assets().values().stream()
                    .filter(asset -> key.contains(asset.sha256()))
                    .findFirst()
                    .map(asset -> Mono.just(asset.content()))
                    .orElse(Mono.empty());
        });
        when(objects.copy(anyString(), anyString())).thenReturn(Mono.empty());
        when(objects.delete(anyString())).thenReturn(Mono.empty());
        when(objects.head(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            if (key.contains(sha256)) return Mono.just(metadata(key, archiveBytes.length));
            return report.expertPresentation().assets().values().stream()
                    .filter(asset -> key.contains(asset.sha256()))
                    .findFirst()
                    .map(asset -> Mono.just(metadata(key, asset.sizeBytes())))
                    .orElse(Mono.empty());
        });

        ImportRecord[] staged = new ImportRecord[1];
        when(imports.insert(any(ImportRecord.class))).thenAnswer(invocation -> {
            staged[0] = invocation.getArgument(0, ImportRecord.class);
            return Mono.just(1L);
        });
        when(imports.lock(any(UUID.class))).thenAnswer(invocation -> Mono.just(staged[0]));
        when(imports.find(any(UUID.class))).thenAnswer(invocation -> Mono.just(staged[0]));
        when(imports.insertReadyRevision(
                any(UUID.class), any(UUID.class), anyString(), anyInt(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), nullable(String.class), anyInt(), anyLong(),
                any(JsonNode.class), any(JsonNode.class), nullable(String.class), nullable(String.class),
                any(JsonNode.class), any(UUID.class), any(Instant.class)))
                .thenReturn(Mono.just(1L));
        when(imports.markCommitted(any(UUID.class), any(UUID.class), any(UUID.class), anyInt(),
                anyString(), any(Instant.class))).thenReturn(Mono.just(1L));

        ExpertAggregateCommand[] command = new ExpertAggregateCommand[1];
        String[] iconKey = new String[1];
        String[] illustrationKey = new String[1];
        String[] exampleKey = new String[1];
        when(aggregates.insert(any(UUID.class), any(ExpertAggregateCommand.class),
                nullable(String.class), nullable(String.class), nullable(String.class),
                any(UUID.class), any(Instant.class))).thenAnswer(invocation -> {
            command[0] = invocation.getArgument(1, ExpertAggregateCommand.class);
            iconKey[0] = invocation.getArgument(2, String.class);
            illustrationKey[0] = invocation.getArgument(3, String.class);
            exampleKey[0] = invocation.getArgument(4, String.class);
            return Mono.just(1L);
        });
        when(profiles.updatePortableMetadata(any(UUID.class), any(JsonNode.class), anyInt(), anyInt()))
                .thenReturn(Mono.just(1L));
        when(versions.replaceDependencies(any(UUID.class), anyList())).thenReturn(Mono.empty());
        when(versions.highestReadyVersion(any(UUID.class))).thenReturn(Mono.just(VERSION));
        when(skills.setLatestVersion(any(UUID.class), eq(VERSION), any(UUID.class), any(Instant.class)))
                .thenReturn(Mono.just(1L));
        when(admin.detail(any(UUID.class))).thenReturn(Mono.just(mock(SkillView.class)));
        when(versions.version(any(UUID.class), isNull(String.class)))
                .thenReturn(Mono.just(mock(SkillAdminResponses.SkillVersionView.class)));

        SkillPortableImportService service = new SkillPortableImportService(
                skills, admin, profiles, aggregates, ingest, versions, imports, objects, null,
                new com.fasterxml.jackson.databind.ObjectMapper());
        UUID actor = UUID.randomUUID();
        ImportResponse stagedResponse = service.upload(upload, actor).block();

        CommitResponse committed = service.commit(
                stagedResponse.importId(), new CommitRequest(null, null, Map.of(), Map.of()), actor).block();

        assertThat(committed).isNotNull();
        assertThat(committed.status()).isEqualTo("COMMITTED");
        assertThat(command[0]).isNotNull();
        assertThat(command[0].code()).isEqualTo(CODE);
        assertThat(command[0].name()).isEqualTo("AI学习助手");
        assertThat(command[0].nameEn()).isEqualTo("AI Learning Assistant");
        assertThat(command[0].taskTypes()).containsExactly("学习计划", "主动回忆", "间隔复习", "错题复盘");
        assertThat(command[0].preview().table().headers()).containsExactly("效率方法", "用时", "具体行动", "检查点");
        assertThat(command[0].preview().table().rows()).hasSize(3);
        assertThat(command[0].preview().intro().text())
                .isEqualTo("每天45分钟｜先回忆，再练习，最后检查");
        assertThat(command[0].previewEn().table().headers())
                .containsExactly("Technique", "Time", "Action", "Check");
        assertThat(command[0].canvas().path("zh").path("width").asInt()).isEqualTo(1080);
        assertThat(command[0].canvas().path("en").path("height").asInt()).isEqualTo(704);
        assertThat(command[0].exampleImageLocale()).isEqualTo("zh");
        assertThat(iconKey[0]).startsWith("clean/skills/").contains("/portable-assets/icon-");
        assertThat(illustrationKey[0]).startsWith("clean/skills/").contains("/portable-assets/illustration-");
        assertThat(exampleKey[0]).startsWith("clean/skills/").contains("/portable-assets/exampleImage-");
        assertThat(iconKey[0]).contains(staged[0].importId().toString());
        assertThat(illustrationKey[0]).contains(staged[0].importId().toString());
        assertThat(exampleKey[0]).contains(staged[0].importId().toString());

        // Feed the values that the commit would persist back into the ordinary revision generator.
        // The generator receives the same clean media pointers and bytes that the commit promoted,
        // while its base package is the real fixture archive.
        UUID skillId = UUID.randomUUID();
        UUID baseVersionId = UUID.randomUUID();
        Instant fixedNow = Instant.parse("2026-09-20T00:00:00Z");
        SkillView persistedSkill = skillFromCommand(skillId, command[0], fixedNow);
        SkillExpertProfileRepository.StoredProfile persistedProfile = profileFromCommand(
                skillId, command[0], fixedNow);
        when(skills.skill(skillId)).thenReturn(Mono.just(persistedSkill));
        when(profiles.findBySkillId(skillId)).thenReturn(Mono.just(persistedProfile));
        when(skills.iconObjectKey(skillId)).thenReturn(Mono.just(iconKey[0]));
        when(skills.illustrationObjectKey(skillId)).thenReturn(Mono.just(illustrationKey[0]));
        when(skills.exampleImageObjectKey(skillId)).thenReturn(Mono.just(exampleKey[0]));
        SkillVersionRepository.PackageRef baseRef = new SkillVersionRepository.PackageRef(
                skillId, baseVersionId, VERSION, "quarantine/base-fixture.ycps.zip",
                archiveBytes.length, md5, sha256, report.minClientVersion(), 1, "YCPS", "1.1.0",
                "EXPERT", JsonNodeFactory.instance.arrayNode(), report.manifestSha256(),
                report.dependencyLockSha256());
        when(versions.packageByVersionId(skillId, baseVersionId)).thenReturn(Mono.just(baseRef));
        when(versions.portableRevisionByInputs(eq(skillId), eq(VERSION), eq(sha256), anyString()))
                .thenReturn(Mono.empty());
        SkillVersionView generatedVersion = mock(SkillVersionView.class);
        byte[][] generatedBytes = new byte[1][];
        when(ingest.ingestGenerated(eq(skillId), eq(VERSION),
                eq(SkillPortableRevisionService.DEFAULT_MIN_CLIENT_VERSION), any(Path.class),
                anyString(), eq(actor), anyMap())).thenAnswer(invocation -> {
            Path generatedArchive = invocation.getArgument(3, Path.class);
            return Mono.fromCallable(() -> {
                generatedBytes[0] = Files.readAllBytes(generatedArchive);
                return generatedVersion;
            });
        });

        SkillPortableRevisionService revisions = new SkillPortableRevisionService(
                skills, versions, profiles, ingest, objects, new SkillProperties());
        revisions.generate(skillId, baseVersionId, actor).block();

        assertThat(generatedBytes[0]).isNotNull();
        Path generatedArchive = Files.createTempFile("ai-learning-assistant-roundtrip-", ".ycps.zip");
        try {
            Files.write(generatedArchive, generatedBytes[0]);
            SkillPackageInspector.Report regenerated = SkillPackageInspector.create()
                    .inspect(generatedArchive, new SkillProperties(), CODE);
            assertThat(regenerated.ycpsVersion()).isEqualTo("1.1.0");
            assertThat(regenerated.kind()).isEqualTo("EXPERT");
            assertThat(regenerated.expertPresentation().title())
                    .isEqualTo(report.expertPresentation().title());
            assertThat(regenerated.expertPresentation().titleEn())
                    .isEqualTo(report.expertPresentation().titleEn());
            assertThat(regenerated.expertPresentation().summary())
                    .isEqualTo(report.expertPresentation().summary());
            assertThat(regenerated.expertPresentation().summaryEn())
                    .isEqualTo(report.expertPresentation().summaryEn());
            assertThat(regenerated.expertPresentation().description())
                    .isEqualTo(report.expertPresentation().description());
            assertThat(regenerated.expertPresentation().descriptionEn())
                    .isEqualTo(report.expertPresentation().descriptionEn());
            assertThat(regenerated.expertPresentation().taskTypes())
                    .containsExactlyElementsOf(report.expertPresentation().taskTypes());
            assertThat(regenerated.expertPresentation().taskTypesEn())
                    .containsExactlyElementsOf(report.expertPresentation().taskTypesEn());
            assertThat(regenerated.expertPresentation().usageGuide())
                    .isEqualTo(report.expertPresentation().usageGuide());
            assertThat(regenerated.expertPresentation().usageGuideEn())
                    .isEqualTo(report.expertPresentation().usageGuideEn());
            assertThat(regenerated.expertPresentation().examplePrompts())
                    .containsExactlyElementsOf(report.expertPresentation().examplePrompts());
            assertThat(regenerated.expertPresentation().examplePromptsEn())
                    .containsExactlyElementsOf(report.expertPresentation().examplePromptsEn());
            assertThat(regenerated.expertPresentation().preview())
                    .isEqualTo(report.expertPresentation().preview());
            assertThat(regenerated.expertPresentation().previewEn())
                    .isEqualTo(report.expertPresentation().previewEn());
            assertThat(regenerated.expertPresentation().canvas())
                    .isEqualTo(report.expertPresentation().canvas());
            assertThat(regenerated.expertPresentation().exampleImageLocale()).isEqualTo("zh");
            for (String role : List.of("icon", "illustration", "exampleImage")) {
                assertThat(regenerated.expertPresentation().assets().get(role).sha256())
                        .as("round-trip %s asset SHA", role)
                        .isEqualTo(report.expertPresentation().assets().get(role).sha256());
            }
        } finally {
            Files.deleteIfExists(generatedArchive);
        }
    }

    private static SkillView skillFromCommand(
            UUID id, ExpertAggregateCommand command, Instant now) {
        return new SkillView(
                id, CODE, command.name(), command.nameEn(), null, List.of(),
                command.summary(), command.summaryEn(), command.description(), command.descriptionEn(),
                command.icon(), null, null, null, null, null, null,
                "EXPERT", "DRAFT", VERSION, VERSION, command.minClientVersion(),
                null, null, null, 0, command.sortWeight() == null ? 0 : command.sortWeight(), 1,
                now, now, null, null, 0, 0, 0, null, List.of(), List.of(), "YCPS", false, null, null);
    }

    private static SkillExpertProfileRepository.StoredProfile profileFromCommand(
            UUID id, ExpertAggregateCommand command, Instant now) {
        return new SkillExpertProfileRepository.StoredProfile(
                id, CODE, "EXPERT", "DRAFT", command.taskTypes(), command.taskTypesEn(),
                command.usageGuide(), command.usageGuideEn(), command.examplePrompts(),
                command.examplePromptsEn(), command.preview(), command.previewEn(), command.canvas(),
                command.exampleImageLocale(), JsonNodeFactory.instance.objectNode(), 0, 1, now, now);
    }

    private static long stagedSize(
            String key, String archiveSha256, long archiveSize, SkillPackageInspector.Report report) {
        if (key.contains("artifact-" + archiveSha256)) return archiveSize;
        return report.expertPresentation().assets().values().stream()
                .filter(asset -> key.contains("asset-" + asset.role() + "-" + asset.sha256()))
                .mapToLong(SkillPackageInspector.PortableAsset::sizeBytes)
                .findFirst().orElse(-1L);
    }

    private static StoredObjectMetadata metadata(String key, long size) {
        return new StoredObjectMetadata(key, size, "application/octet-stream", null,
                Instant.parse("2026-09-20T00:00:00Z"));
    }

    private static JsonNode canvasNode(JsonNode canvas, String field) {
        for (JsonNode node : canvas.path("nodes")) {
            if (field.equals(node.path("field").asText())) return node;
        }
        throw new AssertionError("Missing canvas field: " + field);
    }

    private static String digest(String algorithm, byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
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
