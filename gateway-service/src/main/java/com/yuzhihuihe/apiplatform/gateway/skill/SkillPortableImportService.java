package com.yuzhihuihe.apiplatform.gateway.skill;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import com.yuzhihuihe.apiplatform.gateway.skill.SkillPortableImportRepository.ImportRecord;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileRequests.ExpertAggregateCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportRequests.FieldAction;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportRequests.CommitRequest;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportRequests.ImageAction;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportViews.CommitResponse;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportViews.FieldDiff;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportViews.ImageDiff;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportViews.ImportResponse;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportViews.PackageIdentity;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportViews.Resource;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillPortableImportViews.ResourceInventory;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewContent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.transaction.reactive.TransactionalOperator;

/** Two-phase, isolated reverse import of a YCPS 1.1 Expert presentation package. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillPortableImportService {

    private static final Set<String> SCALAR_FIELDS = Set.of(
            "name", "nameEn", "summary", "summaryEn", "description", "descriptionEn",
            "minClientVersion", "usageGuide", "usageGuideEn", "preview", "previewEn", "canvas",
            "exampleImageLocale");
    private static final Set<String> LIST_FIELDS = Set.of(
            "taskTypes", "taskTypesEn", "examplePrompts", "examplePromptsEn");
    private static final Set<String> IMAGE_FIELDS = Set.of("icon", "illustration", "exampleImage");
    private static final Set<String> OPERATIONAL_FIELDS = Set.of(
            "category", "tags", "sortWeight", "status", "publish");
    private static final String ROLE_ICON = "icon";
    private static final String ROLE_ILLUSTRATION = "illustration";
    private static final String ROLE_EXAMPLE = "exampleImage";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final SkillRepository skills;
    private final SkillAdminService admin;
    private final SkillExpertProfileRepository profiles;
    private final SkillExpertAggregateRepository aggregates;
    private final SkillPackageIngestService ingest;
    private final SkillVersionRepository versions;
    private final SkillPortableImportRepository imports;
    private final FileObjectStore objectStore;
    private final TransactionalOperator transactions;
    private final ObjectMapper objectMapper;

    @Value("${gateway.skills.portable-import.ttl:PT30M}")
    private Duration importTtl = DEFAULT_TTL;

    @Autowired
    public SkillPortableImportService(
            SkillRepository skills,
            SkillAdminService admin,
            SkillExpertProfileRepository profiles,
            SkillExpertAggregateRepository aggregates,
            SkillPackageIngestService ingest,
            SkillVersionRepository versions,
            SkillPortableImportRepository imports,
            FileObjectStore objectStore,
            TransactionalOperator transactions,
            ObjectMapper objectMapper) {
        this.skills = skills;
        this.admin = admin;
        this.profiles = profiles;
        this.aggregates = aggregates;
        this.ingest = ingest;
        this.versions = versions;
        this.imports = imports;
        this.objectStore = objectStore;
        this.transactions = transactions;
        this.objectMapper = objectMapper.copy();
    }

    public Mono<ImportResponse> upload(FilePart file, UUID actor) {
        if (file == null || !safeFileName(file).toLowerCase(Locale.ROOT).endsWith(".ycps.zip")) {
            return Mono.error(invalid("ycps_package_filename_required"));
        }
        UUID importId = UUID.randomUUID();
        return Mono.fromCallable(() -> Files.createTempFile("skill-portable-import-", ".ycps.zip"))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(temp -> ingest.transferOnly(file, temp)
                        .then(ingest.inspectOnly(temp))
                        .flatMap(inspected -> validatePortable(inspected)
                                .then(ingest.scanOnly(temp))
                                .flatMap(scan -> stage(importId, temp, file, inspected, scan, actor)))
                        .doFinally(signal -> deleteQuietly(temp)));
    }

    private Mono<ImportResponse> stage(
            UUID importId,
            Path temp,
            FilePart file,
            SkillPackageIngestService.InspectedPackage inspected,
            SkillPackageIngestService.MalwareScan scan,
            UUID actor) {
        SkillPackageInspector.Report report = inspected.report();
        Instant expires = Instant.now().plus(importTtl == null ? DEFAULT_TTL : importTtl);
        return skills.skillByCode(report.code())
                .map(SkillTarget::new)
                .defaultIfEmpty(new SkillTarget(null))
                .flatMap(target -> {
                    UUID targetSkillId = target.skill() == null ? null : target.skill().id();
                    Mono<ProfileValues> profile = targetSkillId == null
                            ? Mono.just(ProfileValues.empty())
                            : profiles.findBySkillId(targetSkillId)
                                    .map(ProfileValues::new)
                                    .defaultIfEmpty(ProfileValues.empty());
                    return profile.flatMap(current -> versions.nextRevision(report.code(), report.version())
                            .flatMap(nextRevision -> buildStagedRecord(
                                    importId, temp, file, inspected, scan, actor, expires,
                                    target.skill(), current, nextRevision)));
                });
    }

    private Mono<ImportResponse> buildStagedRecord(
            UUID importId,
            Path temp,
            FilePart file,
            SkillPackageIngestService.InspectedPackage inspected,
            SkillPackageIngestService.MalwareScan scan,
            UUID actor,
            Instant expires,
            SkillView target,
            ProfileValues current,
            int nextRevision) {
        SkillPackageInspector.Report report = inspected.report();
        SkillPackageInspector.ExpertPresentation packageValue = report.expertPresentation();
        String artifactKey = "quarantine/skill-portable-imports/" + importId + "/artifact-"
                + inspected.digest().sha256() + ".ycps.zip";
        Map<String, String> assetKeys = new LinkedHashMap<>();
        List<String> staged = new ArrayList<>();
        // Register keys before issuing PUT: a successful PUT followed by a HEAD/DB failure must
        // still be cleaned on the error path.
        staged.add(artifactKey);
        Mono<Void> putArtifact = Mono.defer(() -> objectStore.putFile(
                        artifactKey, temp, "application/vnd.yuzhihe.ycps+zip"))
                .then(Mono.defer(() -> verifyHead(
                        artifactKey, inspected.digest().sizeBytes())));
        Mono<Void> putAssets = Flux.fromIterable(packageValue.assets().entrySet())
                .concatMap(entry -> {
                    SkillPackageInspector.PortableAsset asset = entry.getValue();
                    String key = "quarantine/skill-portable-imports/" + importId + "/asset-"
                            + asset.role() + "-" + asset.sha256() + extension(asset.mediaType());
                    assetKeys.put(asset.role(), key);
                    staged.add(key);
                    return Mono.defer(() -> objectStore.put(
                                    key, asset.content(), asset.mediaType()))
                            .then(Mono.defer(() -> verifyHead(
                                    key, asset.sizeBytes())));
                })
                .then();
        return putArtifact.then(putAssets)
                .then(Mono.defer(() -> {
                    Map<String, Object> packageFields = packageFields(packageValue, report.minClientVersion());
                    Map<String, Object> currentFields = currentFields(target, current);
                    Map<String, FieldDiff> fields = fieldDiff(packageFields, currentFields);
                    Map<String, ImageDiff> images = imageDiff(packageValue, target);
                    ResourceInventory inventory = inventory(packageValue);
                    List<String> warnings = warnings(report, scan);
                    JsonNode presentation = presentationJson(packageValue);
                    JsonNode inventoryJson = objectMapper.valueToTree(inventory);
                    JsonNode assetKeysJson = assetKeysJson(packageValue, assetKeys);
                    ObjectNode scanReport = objectMapper.createObjectNode();
                    scanReport.put("scanner", scan.integrated() ? "clamav" : "disabled");
                    scanReport.put("result", scan.result());
                    scanReport.put("artifactSha256", inspected.digest().sha256());
                    scanReport.put("assetsValidatedByInspector", true);
                    scanReport.set("warnings", objectMapper.valueToTree(warnings));
                    SkillPortableImportRepository.ImportRecord row = new SkillPortableImportRepository.ImportRecord(
                            importId, artifactKey, inspected.digest().sha256(), inspected.digest().md5(),
                            inspected.digest().sizeBytes(),
                            safeFileName(file), "SCANNED", expires, report.code(), report.kind(),
                            report.version(), nextRevision, report.pluginManifest(), report.runtimeManifest(),
                            presentation, report.manifestSha256(), report.dependencyLockSha256(),
                            report.minClientVersion(), report.fileCount(), report.extractedBytes(), "PASSED",
                            scanReport, objectMapper.valueToTree(fields), inventoryJson,
                            objectMapper.valueToTree(warnings), assetKeysJson,
                            target == null ? null : target.id(), target == null ? null : target.rowVersion(),
                            null, null, null, actor, Instant.now(), Instant.now(), null);
                    return imports.insert(row)
                            .filter(count -> count == 1L)
                            .switchIfEmpty(Mono.error(new IllegalStateException("skill_portable_import_not_inserted")))
                            .thenReturn(response(row, fields, images, inventory, warnings));
                }))
                .onErrorResume(error -> cleanup(staged).then(Mono.error(mapError(error))));
    }

    public Mono<CommitResponse> commit(UUID importId, CommitRequest request, UUID actor) {
        if (importId == null) return Mono.error(notFound("skill_portable_import_not_found"));
        CommitRequest command = request == null ? new CommitRequest(null, null, Map.of(), Map.of()) : request;
        Instant now = Instant.now();
        Mono<CommitResponse> action = imports.lock(importId)
                .switchIfEmpty(Mono.error(notFound("skill_portable_import_not_found")))
                .flatMap(row -> {
                    if ("COMMITTED".equals(row.status())) return hydrateCommitted(row);
                    if (!"SCANNED".equals(row.status())) {
                        return Mono.error(expiredOrFailed(row));
                    }
                    if (row.expiresAt() == null || !row.expiresAt().isAfter(now)) {
                        // Do not delete objects while this transaction is still able to roll back;
                        // the scheduled cleaner marks the row and removes its isolated objects.
                        return Mono.error(expired("skill_portable_import_expired"));
                    }
                    return commitScanned(row, command, actor);
                });
        Mono<CommitResponse> committed = transactions == null ? action : transactions.transactional(action);
        return committed.flatMap(response -> imports.find(importId)
                .flatMap(row -> cleanupImportObjects(row)
                        .then(cleanupReplacedImages(row))
                        .onErrorResume(error -> Mono.empty())
                        .thenReturn(response)))
                .onErrorResume(error -> {
                    RuntimeException mapped = mapError(error);
                    if (!"skill_portable_import_sha256_changed".equals(reason(mapped))) {
                        return Mono.error(mapped);
                    }
                    // The transactional action has already rolled back here. Persist the failed
                    // state and remove the isolated artifact only after rollback, so a TOCTOU
                    // failure cannot leave a retryable SCANNED row or quarantine residue.
                    return imports.markFailed(importId, Instant.now(), "artifact_sha256_changed")
                            .then(imports.find(importId)
                                    .flatMap(this::cleanupImportObjects)
                                    .onErrorResume(cleanupError -> Mono.empty()))
                            .then(Mono.error(mapped));
                });
    }

    private Mono<CommitResponse> commitScanned(ImportRecord row, CommitRequest request, UUID actor) {
        UUID provisionalId = row.targetSkillId() == null ? UUID.randomUUID() : row.targetSkillId();
        List<String> cleanObjects = new ArrayList<>();
        Path temp;
        try {
            temp = Files.createTempFile("skill-portable-commit-", ".ycps.zip");
        } catch (Exception error) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "skill_storage_unavailable", error));
        }
        Path finalTemp = temp;
        return downloadObject(row.objectKey(), row.sizeBytes(), finalTemp)
                .then(ingest.inspectOnly(finalTemp))
                .flatMap(inspected -> verifyArtifact(row, inspected)
                        .then(validatePortable(inspected))
                        .then(verifyStoredAssets(row, inspected.report()))
                        .then(resolveTarget(row, request))
                        .flatMap(target -> prepareCommit(row, request, actor, target,
                                inspected.report(), provisionalId, cleanObjects)))
                .doFinally(signal -> deleteQuietly(finalTemp))
                .onErrorResume(error -> cleanup(cleanObjects)
                        .then(Mono.error(mapError(error))));
    }

    private Mono<CommitTarget> resolveTarget(ImportRecord row, CommitRequest request) {
        Mono<SkillView> existing = row.targetSkillId() == null
                ? skills.skillByCode(row.packageCode())
                : skills.lockSkill(row.targetSkillId());
        return existing
                .flatMap(found -> {
                    if (!row.packageCode().equals(found.code())
                            || !"EXPERT".equalsIgnoreCase(found.kind())) {
                        return Mono.error(conflict("skill_portable_import_code_kind_conflict"));
                    }
                    // The diff was computed against the stage-time target.  If a code that was
                    // new at stage time appeared before commit, do not silently turn a new-skill
                    // import into an update of that race winner.
                    if (row.targetSkillId() == null) {
                        return Mono.error(conflict("skill_portable_import_code_kind_conflict"));
                    }
                    if (request.skillId() != null && !request.skillId().equals(found.id())) {
                        return Mono.error(conflict("skill_portable_import_code_kind_conflict"));
                    }
                    if (row.targetSkillId() != null && row.targetRowVersion() != null
                            && (request.expectedRowVersion() == null
                                    || !row.targetRowVersion().equals(request.expectedRowVersion()))) {
                        return Mono.error(conflict("skill_concurrent_modification"));
                    }
                    if (request.expectedRowVersion() == null) {
                        return Mono.error(invalid("expected_row_version_required"));
                    }
                    if (request.expectedRowVersion() != found.rowVersion()) {
                        return Mono.error(conflict("skill_concurrent_modification"));
                    }
                    return profiles.findBySkillId(found.id())
                            .map(ProfileValues::new)
                            .defaultIfEmpty(ProfileValues.empty())
                            .flatMap(profile -> currentObjectKeys(found.id())
                                    .map(images -> new CommitTarget(found, profile, images, false)));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    if (row.targetSkillId() != null) {
                        // A target disappeared after staging; never recreate it under the old
                        // diff or let a stale import mutate a different row.
                        return Mono.error(conflict("skill_concurrent_modification"));
                    }
                    if (request.skillId() != null || request.expectedRowVersion() != null) {
                        return Mono.error(conflict("skill_portable_import_code_kind_conflict"));
                    }
                    Map<String, String> emptyImages = new LinkedHashMap<>();
                    emptyImages.put(ROLE_ICON, null);
                    emptyImages.put(ROLE_ILLUSTRATION, null);
                    emptyImages.put(ROLE_EXAMPLE, null);
                    return Mono.just(new CommitTarget(null, ProfileValues.empty(), emptyImages, true));
                }));
    }

    private Mono<CommitResponse> prepareCommit(
            ImportRecord row,
            CommitRequest request,
            UUID actor,
            CommitTarget target,
            SkillPackageInspector.Report report,
            UUID provisionalId,
            List<String> cleanObjects) {
        validateActions(request);
        UUID skillId = target.newSkill() ? provisionalId : target.skill().id();
        Map<String, FieldAction> actions = normalizedFieldActions(request.fields());
        Map<String, ImageAction> imageActions = normalizedImageActions(request.images());
        Map<String, Object> packageValues = packageFields(report.expertPresentation(), report.minClientVersion());
        Map<String, Object> currentValues = currentFields(target.skill(), target.profile());
        Map<String, Object> values = applyFields(packageValues, currentValues, actions);
        Map<String, String> imagePointers = new LinkedHashMap<>();
        return ingest.validateDependenciesOnly(report)
                .flatMap(dependencies -> copyAssets(row, report, skillId, target.images(), imageActions, cleanObjects)
                .map(copied -> {
                    // Start with every current pointer. A missing package asset is never a clear
                    // instruction; only an Inspector-validated asset can replace a role.
                    imagePointers.putAll(target.images());
                    copied.forEach((role, value) -> {
                        if (value != null) imagePointers.put(role, value);
                    });
                    return imagePointers;
                })
                .flatMap(images -> {
                    values.put("exampleImageLocale", exampleImageLocaleAfterImport(
                            report.expertPresentation().exampleImageLocale(),
                            target.profile().exampleImageLocale(),
                            report.expertPresentation().assets().containsKey(ROLE_EXAMPLE),
                            target.images().get(ROLE_EXAMPLE) != null,
                            imageActions.getOrDefault(ROLE_EXAMPLE, ImageAction.FILL_EMPTY)));
                    ExpertAggregateCommand command = command(row, report.expertPresentation(), values,
                            target.skill(), skillId, images, request);
                    Instant now = Instant.now();
                    Mono<Void> write = target.newSkill()
                            ? aggregates.insert(skillId, command, images.get(ROLE_ICON),
                                    images.get(ROLE_ILLUSTRATION), images.get(ROLE_EXAMPLE), actor, now).then()
                            : aggregates.update(skillId, command, request.expectedRowVersion(),
                                    images.get(ROLE_ICON), images.get(ROLE_ILLUSTRATION),
                                    images.get(ROLE_EXAMPLE), actor, now).then();
                    int expectedAfter = target.newSkill()
                            ? 1 : request.expectedRowVersion() + 1;
                    int portableRevision = target.profile().portableRevision() + 1;
                    JsonNode manifest = objectMapper.createObjectNode();
                    ObjectNode manifestObject = (ObjectNode) manifest;
                    manifestObject.put("artifactSha256", row.artifactSha256());
                    manifestObject.put("importId", row.importId().toString());
                    manifestObject.set("inventory", row.inventory());
                    return write
                            .then(profiles.updatePortableMetadata(
                                    skillId, manifestObject, expectedAfter, portableRevision))
                            .filter(count -> count == 1L)
                            .switchIfEmpty(Mono.error(conflict("skill_concurrent_modification")))
                            .then(versions.nextRevision(row.packageCode(), row.packageVersion()))
                            .flatMap(revision -> {
                                UUID versionId = UUID.randomUUID();
                                String cleanKey = SkillPackageIngestService.cleanKey(
                                        skillId, row.packageCode(), row.packageVersion(), revision,
                                        row.artifactSha256());
                                cleanObjects.add(cleanKey);
                                JsonNode scanReport = row.scanReport() == null
                                        ? objectMapper.createObjectNode() : row.scanReport();
                                return Mono.defer(() -> objectStore.copy(
                                                row.objectKey(), cleanKey))
                                        .then(Mono.defer(() -> verifyHead(
                                                cleanKey, row.sizeBytes())))
                                        .then(Mono.defer(() -> imports.insertReadyRevision(
                                                versionId, skillId, row.packageVersion(), revision, cleanKey,
                                                row.fileName(), row.sizeBytes(), row.artifactMd5(), row.artifactSha256(),
                                                row.minClientVersion(), row.fileCount(), row.extractedBytes(),
                                                report.pluginManifest(), report.runtimeManifest(),
                                                report.manifestSha256(), report.dependencyLockSha256(),
                                                scanReport, actor, Instant.now())))
                                        .filter(count -> count == 1L)
                                        .switchIfEmpty(Mono.error(new IllegalStateException(
                                                "skill_portable_revision_not_inserted")))
                                        .then(versions.replaceDependencies(versionId, dependencies))
                                        // Keep the catalog pointer consistent with ordinary ingest,
                                        // but select the highest READY SemVer so importing an older
                                        // package can never downgrade latest_version.
                                        .then(versions.highestReadyVersion(skillId))
                                        .flatMap(latest -> latest == null
                                                ? Mono.empty()
                                                : skills.setLatestVersion(skillId, latest, actor, Instant.now()))
                                .then(Mono.defer(() -> {
                                            ObjectNode result = objectMapper.createObjectNode();
                                            result.put("skillId", skillId.toString());
                                            result.put("versionId", versionId.toString());
                                            result.put("revision", revision);
                                            result.set("fieldActions", objectMapper.valueToTree(actions));
                                            result.set("imageActions", objectMapper.valueToTree(imageActions));
                                            result.set("replacedImages", objectMapper.valueToTree(
                                                    replacedImages(target.images(), images)));
                                            return imports.markCommitted(
                                                    row.importId(), skillId, versionId, revision,
                                                    result.toString(), Instant.now())
                                                    .filter(updated -> updated == 1L)
                                                    .switchIfEmpty(Mono.error(conflict(
                                                            "skill_portable_import_concurrent_commit")))
                                                    .thenReturn(new CommitKey(
                                                            row.importId(), skillId, versionId, revision, actions,
                                                            imageActions, replacedImages(target.images(), images)));
                                        }));
                            });
                }))
                .flatMap(this::hydrateCommittedKey)
                .onErrorMap(DuplicateKeyException.class,
                        error -> conflict("skill_portable_import_code_kind_conflict"));
    }

    private Mono<CommitResponse> hydrateCommitted(ImportRecord row) {
        if (row.commitVersionId() == null || row.targetSkillId() == null || row.committedRevision() == null) {
            return Mono.error(new IllegalStateException("skill_portable_import_commit_result_invalid"));
        }
        Map<String, FieldAction> fields = readActions(row.commitResult(), "fieldActions", FieldAction.class);
        Map<String, ImageAction> images = readActions(row.commitResult(), "imageActions", ImageAction.class);
        return hydrate(row.importId(), row.targetSkillId(), row.commitVersionId(),
                row.committedRevision(), fields, images);
    }

    private Mono<CommitResponse> hydrateCommittedKey(CommitKey key) {
        return hydrate(key.importId(), key.skillId(), key.versionId(), key.revision(),
                key.fields(), key.images());
    }

    private Mono<CommitResponse> hydrate(
            UUID importId,
            UUID skillId,
            UUID versionId,
            int revision,
            Map<String, FieldAction> fields,
            Map<String, ImageAction> images) {
        return Mono.zip(admin.detail(skillId), versions.version(versionId, null))
                .map(values -> new CommitResponse(
                        importId, "COMMITTED", skillId, revision, values.getT1(), values.getT2(),
                        fields, images));
    }

    private Mono<Void> verifyArtifact(ImportRecord row, SkillPackageIngestService.InspectedPackage inspected) {
        if (!row.artifactSha256().equalsIgnoreCase(inspected.digest().sha256())
                || row.sizeBytes() != inspected.digest().sizeBytes()) {
            return Mono.error(invalid("skill_portable_import_sha256_changed"));
        }
        return Mono.empty();
    }

    private Mono<Void> verifyStoredAssets(ImportRecord row, SkillPackageInspector.Report report) {
        if (row.assetKeys() == null || !row.assetKeys().isObject()) {
            return Mono.error(invalid("skill_portable_import_asset_missing"));
        }
        Set<String> expectedRoles = report.expertPresentation().assets().keySet();
        if (!stagedAssetRolesMatch(row.assetKeys(), expectedRoles)) {
            // A package mutation, a truncated stage row, and an injected extra role must all fail
            // closed.  In particular, never let an absent asset_keys object disable verification.
            return Mono.error(invalid("skill_portable_import_asset_changed"));
        }
        String quarantinePrefix = "quarantine/skill-portable-imports/" + row.importId() + "/";
        return Flux.fromIterable(report.expertPresentation().assets().entrySet())
                .concatMap(entry -> {
                    String role = entry.getKey();
                    SkillPackageInspector.PortableAsset asset = entry.getValue();
                    JsonNode stored = row.assetKeys().get(role);
                    if (stored == null || !stored.isObject()) {
                        return Mono.error(invalid("skill_portable_import_asset_missing"));
                    }
                    if (!stagedAssetMetadataMatches(stored, asset, quarantinePrefix)) {
                        return Mono.error(invalid("skill_portable_import_asset_changed"));
                    }
                    String key = stored.path("key").asText("");
                    String digest = stored.path("sha256").asText("");
                    return objectStore.head(key)
                            .switchIfEmpty(Mono.error(invalid("skill_portable_import_asset_missing")))
                            .flatMap(head -> head.sizeBytes() == asset.sizeBytes()
                                    ? objectStore.get(key, asset.sizeBytes() + 1)
                                    : Mono.<byte[]>error(invalid("skill_portable_import_asset_changed")))
                            .switchIfEmpty(Mono.error(invalid("skill_portable_import_asset_missing")))
                            .flatMap(bytes -> bytes.length == asset.sizeBytes()
                                    && digest(bytes).equalsIgnoreCase(digest)
                                    && digest(bytes).equalsIgnoreCase(asset.sha256())
                                    ? Mono.empty()
                                    : Mono.error(invalid("skill_portable_import_asset_sha256_changed")));
                })
                .then();
    }

    /** Keeps the stage record and the freshly inspected package's role inventory in lockstep. */
    static boolean stagedAssetRolesMatch(JsonNode assetKeys, Set<String> expectedRoles) {
        if (assetKeys == null || !assetKeys.isObject() || expectedRoles == null) return false;
        Set<String> actualRoles = new java.util.HashSet<>();
        assetKeys.fieldNames().forEachRemaining(actualRoles::add);
        return expectedRoles.equals(actualRoles);
    }

    /** Checks only server-generated asset metadata; bytes are verified by the caller below. */
    static boolean stagedAssetMetadataMatches(
            JsonNode stored, SkillPackageInspector.PortableAsset asset, String quarantinePrefix) {
        if (stored == null || !stored.isObject() || asset == null
                || quarantinePrefix == null || quarantinePrefix.isBlank()) return false;
        String key = stored.path("key").asText("");
        String digest = stored.path("sha256").asText("");
        return !key.isBlank() && key.startsWith(quarantinePrefix)
                && Objects.equals(stored.path("path").asText(null), asset.packagePath())
                && Objects.equals(stored.path("mediaType").asText(null), asset.mediaType())
                && stored.path("sizeBytes").asLong(-1) == asset.sizeBytes()
                && digest.equalsIgnoreCase(asset.sha256());
    }

    private Mono<Map<String, String>> copyAssets(
            ImportRecord row,
            SkillPackageInspector.Report report,
            UUID skillId,
            Map<String, String> current,
            Map<String, ImageAction> actions,
            List<String> cleanObjects) {
        Map<String, String> result = new LinkedHashMap<>();
        return Flux.fromIterable(report.expertPresentation().assets().entrySet())
                .concatMap(entry -> {
                    String role = entry.getKey();
                    ImageAction action = actions.getOrDefault(role, ImageAction.FILL_EMPTY);
                    if (action == ImageAction.FILL_EMPTY && current.get(role) != null) {
                        result.put(role, current.get(role));
                        return Mono.empty();
                    }
                    JsonNode source = row.assetKeys() == null ? null : row.assetKeys().get(role);
                    if (source == null || !source.isObject()) return Mono.empty();
                    String sourceKey = source.path("key").asText("");
                    String hash = source.path("sha256").asText("");
                    // Keep every import's promoted bytes private to that import.  A deterministic
                    // role/hash key would be unsafe on a failed retry: cleanup(cleanObjects)
                    // could delete an object that a previous successful import already points at.
                    String targetKey = "clean/skills/" + skillId + "/portable-assets/" + role + "-"
                            + hash + "-" + row.importId() + extension(entry.getValue().mediaType());
                    cleanObjects.add(targetKey);
                    result.put(role, targetKey);
                    return Mono.defer(() -> objectStore.copy(sourceKey, targetKey))
                            .then(Mono.defer(() -> verifyHead(
                                    targetKey, entry.getValue().sizeBytes())));
                })
                .then(Mono.just(result));
    }

    static List<String> replacedImages(
            Map<String, String> previous, Map<String, String> current) {
        List<String> replaced = new ArrayList<>();
        previous.forEach((role, oldKey) -> {
            String newKey = current.get(role);
            if (oldKey != null && newKey != null && !oldKey.equals(newKey)) replaced.add(oldKey);
        });
        return replaced;
    }

    private ExpertAggregateCommand command(
            ImportRecord row,
            SkillPackageInspector.ExpertPresentation packageValue,
            Map<String, Object> values,
            SkillView target,
            UUID skillId,
            Map<String, String> images,
            CommitRequest request) {
        return new ExpertAggregateCommand(
                target == null ? null : request.expectedRowVersion(),
                row.packageCode(), string(values.get("name")), string(values.get("nameEn")),
                target == null ? null : target.category(),
                target == null ? List.of() : target.tags(),
                string(values.get("summary")), string(values.get("summaryEn")),
                string(values.get("description")), string(values.get("descriptionEn")),
                target == null ? null : target.icon(), string(values.get("minClientVersion")),
                target == null ? 0 : target.sortWeight(), target == null ? "DRAFT" : target.status(),
                strings(values.get("taskTypes")), strings(values.get("taskTypesEn")),
                string(values.get("usageGuide")), string(values.get("usageGuideEn")),
                strings(values.get("examplePrompts")), strings(values.get("examplePromptsEn")),
                preview(values.get("preview")), preview(values.get("previewEn")), null, null,
                "KEEP", "KEEP", "KEEP", canvas(values.get("canvas")),
                string(values.get("exampleImageLocale")));
    }

    private Map<String, Object> applyFields(
            Map<String, Object> packageValues,
            Map<String, Object> currentValues,
            Map<String, FieldAction> actions) {
        Map<String, Object> result = new LinkedHashMap<>(currentValues);
        for (String field : SCALAR_FIELDS) {
            result.put(field, choose(packageValues.get(field), currentValues.get(field),
                    actions.getOrDefault(field, FieldAction.FILL_EMPTY), false));
        }
        for (String field : LIST_FIELDS) {
            result.put(field, choose(packageValues.get(field), currentValues.get(field),
                    actions.getOrDefault(field, FieldAction.FILL_EMPTY), true));
        }
        return result;
    }

    static Object choose(Object packageValue, Object currentValue, FieldAction action,
            boolean list) {
        if (empty(packageValue)) return currentValue;
        if (action == FieldAction.OVERWRITE) return packageValue;
        if (action == FieldAction.MERGE && list) {
            List<Object> merged = new ArrayList<>();
            if (currentValue instanceof Collection<?> collection) merged.addAll(collection);
            if (packageValue instanceof Collection<?> collection) {
                for (Object value : collection) if (!merged.contains(value)) merged.add(value);
            }
            return merged;
        }
        return empty(currentValue) ? packageValue : currentValue;
    }

    /**
     * Keep the locale and the example-image bytes as one logical value during reverse import.
     * FILL_EMPTY may copy a package image when the target has no image; OVERWRITE always copies
     * it. In either case the package locale wins, and an omitted package locale deliberately
     * clears an old explicit locale so the client falls back to the historical zh semantics.
     */
    static String exampleImageLocaleAfterImport(
            String packageLocale,
            String currentLocale,
            boolean packageHasImage,
            boolean currentHasImage,
            ImageAction action) {
        if (!packageHasImage || action == null
                || (action == ImageAction.FILL_EMPTY && currentHasImage)) {
            return currentLocale;
        }
        return packageLocale;
    }

    static boolean empty(Object value) {
        if (value == null) return true;
        if (value instanceof String string) return string.isBlank();
        if (value instanceof Collection<?> collection) return collection.isEmpty();
        return value instanceof JsonNode node && (node.isNull() || node.isEmpty());
    }

    private void validateActions(CommitRequest request) {
        for (Map.Entry<String, FieldAction> entry : request.fields().entrySet()) {
            String field = entry.getKey();
            FieldAction action = entry.getValue();
            if (field == null || action == null || (!SCALAR_FIELDS.contains(field) && !LIST_FIELDS.contains(field))) {
                if (OPERATIONAL_FIELDS.contains(field)) {
                    throw invalid("skill_portable_import_operational_field");
                }
                throw invalid("skill_portable_import_field_action_invalid");
            }
            if (action == FieldAction.MERGE && !LIST_FIELDS.contains(field)) {
                throw invalid("skill_portable_import_merge_not_supported");
            }
        }
        for (Map.Entry<String, ImageAction> entry : request.images().entrySet()) {
            if (!IMAGE_FIELDS.contains(entry.getKey()) || entry.getValue() == null) {
                throw invalid("skill_portable_import_image_action_invalid");
            }
        }
    }

    private Map<String, FieldAction> normalizedFieldActions(Map<String, FieldAction> actions) {
        Map<String, FieldAction> values = new LinkedHashMap<>();
        for (String field : SCALAR_FIELDS) values.put(field, actions.getOrDefault(field, FieldAction.FILL_EMPTY));
        for (String field : LIST_FIELDS) values.put(field, actions.getOrDefault(field, FieldAction.FILL_EMPTY));
        return values;
    }

    private Map<String, ImageAction> normalizedImageActions(Map<String, ImageAction> actions) {
        Map<String, ImageAction> values = new LinkedHashMap<>();
        for (String field : IMAGE_FIELDS) values.put(field, actions.getOrDefault(field, ImageAction.FILL_EMPTY));
        return values;
    }

    private Map<String, Object> packageFields(
            SkillPackageInspector.ExpertPresentation value, String minClientVersion) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", value.title());
        fields.put("nameEn", value.titleEn());
        fields.put("summary", value.summary());
        fields.put("summaryEn", value.summaryEn());
        fields.put("description", value.description());
        fields.put("descriptionEn", value.descriptionEn());
        fields.put("minClientVersion", minClientVersion);
        fields.put("taskTypes", value.taskTypes());
        fields.put("taskTypesEn", value.taskTypesEn());
        fields.put("usageGuide", value.usageGuide());
        fields.put("usageGuideEn", value.usageGuideEn());
        fields.put("examplePrompts", value.examplePrompts());
        fields.put("examplePromptsEn", value.examplePromptsEn());
        fields.put("preview", value.preview());
        fields.put("previewEn", value.previewEn());
        fields.put("canvas", value.canvas());
        fields.put("exampleImageLocale", value.exampleImageLocale());
        return fields;
    }

    private Map<String, Object> currentFields(SkillView value, ProfileValues profile) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", value == null ? null : value.name());
        fields.put("nameEn", value == null ? null : value.nameEn());
        fields.put("summary", value == null ? null : value.summary());
        fields.put("summaryEn", value == null ? null : value.summaryEn());
        fields.put("description", value == null ? null : value.description());
        fields.put("descriptionEn", value == null ? null : value.descriptionEn());
        fields.put("minClientVersion", value == null ? null : value.minClientVersion());
        fields.put("taskTypes", profile.taskTypes());
        fields.put("taskTypesEn", profile.taskTypesEn());
        fields.put("usageGuide", profile.usageGuide());
        fields.put("usageGuideEn", profile.usageGuideEn());
        fields.put("examplePrompts", profile.examplePrompts());
        fields.put("examplePromptsEn", profile.examplePromptsEn());
        fields.put("preview", profile.preview());
        fields.put("previewEn", profile.previewEn());
        fields.put("canvas", profile.canvas());
        fields.put("exampleImageLocale", profile.exampleImageLocale());
        return fields;
    }

    private Map<String, FieldDiff> fieldDiff(Map<String, Object> packageFields, Map<String, Object> current) {
        Map<String, FieldDiff> result = new LinkedHashMap<>();
        for (String field : SCALAR_FIELDS) {
            result.put(field, diff(packageFields.get(field), current.get(field),
                    List.of(FieldAction.FILL_EMPTY, FieldAction.OVERWRITE)));
        }
        for (String field : LIST_FIELDS) {
            result.put(field, diff(packageFields.get(field), current.get(field),
                    List.of(FieldAction.FILL_EMPTY, FieldAction.OVERWRITE, FieldAction.MERGE)));
        }
        return result;
    }

    private FieldDiff diff(Object packageValue, Object current, List<FieldAction> actions) {
        boolean present = !empty(packageValue);
        String change = !present ? "MISSING" : empty(current) ? "ADD"
                : objectMapper.valueToTree(packageValue).equals(objectMapper.valueToTree(current))
                        ? "UNCHANGED" : "UPDATE";
        return new FieldDiff(present, node(packageValue), node(current), change,
                FieldAction.FILL_EMPTY, actions);
    }

    private Map<String, ImageDiff> imageDiff(SkillPackageInspector.ExpertPresentation packageValue,
            SkillView current) {
        Map<String, ImageDiff> result = new LinkedHashMap<>();
        Map<String, SkillPackageInspector.PortableAsset> assets = packageValue.assets();
        for (String role : IMAGE_FIELDS) {
            SkillPackageInspector.PortableAsset asset = assets.get(role);
            boolean present = asset != null;
            JsonNode packageNode = asset == null ? NullNode.getInstance() : objectMapper.createObjectNode()
                    .put("path", asset.packagePath()).put("sha256", asset.sha256())
                    .put("sizeBytes", asset.sizeBytes());
            boolean hasCurrent = current != null && switch (role) {
                case ROLE_ICON -> current.iconUrl() != null;
                case ROLE_ILLUSTRATION -> current.illustrationUrl() != null;
                case ROLE_EXAMPLE -> current.exampleImageUrl() != null;
                default -> false;
            };
            String change = !present ? "MISSING" : hasCurrent ? "UPDATE" : "ADD";
            result.put(role, new ImageDiff(present, packageNode,
                    objectMapper.valueToTree(hasCurrent), change, ImageAction.FILL_EMPTY,
                    List.of(ImageAction.FILL_EMPTY, ImageAction.OVERWRITE)));
        }
        return result;
    }

    private ResourceInventory inventory(SkillPackageInspector.ExpertPresentation value) {
        List<Resource> resources = value.assets().values().stream()
                .map(asset -> new Resource(asset.role(), asset.packagePath(), asset.mediaType(),
                        asset.sizeBytes(), asset.sha256()))
                .sorted(java.util.Comparator.comparing(Resource::role))
                .toList();
        return new ResourceInventory(resources.size(), resources.stream().mapToLong(Resource::sizeBytes).sum(), resources);
    }

    private List<String> warnings(SkillPackageInspector.Report report,
            SkillPackageIngestService.MalwareScan scan) {
        List<String> values = new ArrayList<>();
        values.add("operational_fields_not_imported:category,tags,sortWeight,status,publish");
        values.addAll(report.expertPresentation().warnings());
        if (!scan.integrated()) values.add("malware_scanner_disabled");
        if (report.expertPresentation().assets().isEmpty()) values.add("package_has_no_presentation_assets");
        return List.copyOf(values);
    }

    private JsonNode presentationJson(SkillPackageInspector.ExpertPresentation value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("title", value.title());
        put(node, "titleEn", value.titleEn());
        put(node, "summary", value.summary());
        put(node, "summaryEn", value.summaryEn());
        put(node, "description", value.description());
        put(node, "descriptionEn", value.descriptionEn());
        node.set("taskTypes", objectMapper.valueToTree(value.taskTypes()));
        node.set("taskTypesEn", objectMapper.valueToTree(value.taskTypesEn()));
        put(node, "usageGuide", value.usageGuide());
        put(node, "usageGuideEn", value.usageGuideEn());
        node.set("examplePrompts", objectMapper.valueToTree(value.examplePrompts()));
        node.set("examplePromptsEn", objectMapper.valueToTree(value.examplePromptsEn()));
        node.set("preview", node(value.preview()));
        node.set("previewEn", node(value.previewEn()));
        node.set("canvas", node(value.canvas()));
        put(node, "exampleImageLocale", value.exampleImageLocale());
        return node;
    }

    private JsonNode assetKeysJson(SkillPackageInspector.ExpertPresentation value,
            Map<String, String> keys) {
        ObjectNode result = objectMapper.createObjectNode();
        value.assets().forEach((role, asset) -> {
            ObjectNode item = result.putObject(role);
            item.put("key", keys.get(role));
            item.put("path", asset.packagePath());
            item.put("mediaType", asset.mediaType());
            item.put("sizeBytes", asset.sizeBytes());
            item.put("sha256", asset.sha256());
        });
        return result;
    }

    private ImportResponse response(
            ImportRecord row,
            Map<String, FieldDiff> fields,
            Map<String, ImageDiff> images,
            ResourceInventory inventory,
            List<String> warnings) {
        return new ImportResponse(row.importId(), row.expiresAt(), row.artifactSha256(),
                new PackageIdentity(row.packageCode(), row.packageKind(), row.packageVersion(),
                        row.packageRevision()), fields, images, inventory, warnings, row.status(),
                row.targetSkillId(), row.targetRowVersion());
    }

    private Mono<Void> validatePortable(SkillPackageIngestService.InspectedPackage inspected) {
        SkillPackageInspector.Report report = inspected.report();
        if (!SkillPackageInspector.YCPS_PORTABLE_VERSION.equals(report.ycpsVersion())
                || !"EXPERT".equals(report.kind())) {
            return Mono.error(invalid("skill_portable_import_profile_required"));
        }
        return Mono.empty();
    }

    private Mono<Map<String, String>> currentObjectKeys(UUID id) {
        return Mono.zip(skills.iconObjectKey(id).defaultIfEmpty(""),
                        skills.illustrationObjectKey(id).defaultIfEmpty(""),
                        skills.exampleImageObjectKey(id).defaultIfEmpty(""))
                .map(value -> {
                    Map<String, String> result = new LinkedHashMap<>();
                    result.put(ROLE_ICON, blankToNull(value.getT1()));
                    result.put(ROLE_ILLUSTRATION, blankToNull(value.getT2()));
                    result.put(ROLE_EXAMPLE, blankToNull(value.getT3()));
                    return result;
                });
    }

    private Mono<Void> downloadObject(String key, long size, Path target) {
        return objectStore.head(key)
                .switchIfEmpty(Mono.error(invalid("skill_portable_import_artifact_missing")))
                .flatMap(head -> head.sizeBytes() == size
                        ? Mono.empty() : Mono.error(invalid("skill_portable_import_artifact_changed")))
                .then(Mono.fromCallable(() -> {
                    try (OutputStream output = Files.newOutputStream(target)) {
                        for (ByteBuffer chunk : objectStore.getRange(key, 0, size).toIterable()) {
                            ByteBuffer copy = chunk.duplicate();
                            byte[] bytes = new byte[copy.remaining()];
                            copy.get(bytes);
                            output.write(bytes);
                        }
                    }
                    return (Void) null;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<Void> verifyHead(String key, long size) {
        return objectStore.head(key)
                .switchIfEmpty(Mono.error(invalid("skill_portable_import_object_missing")))
                .flatMap(metadata -> metadata.sizeBytes() == size
                        ? Mono.empty() : Mono.error(invalid("skill_portable_import_object_mismatch")));
    }

    private Mono<Void> cleanupImportObjects(ImportRecord row) {
        List<String> keys = new ArrayList<>();
        if (row.objectKey() != null) keys.add(row.objectKey());
        if (row.assetKeys() != null && row.assetKeys().isObject()) {
            row.assetKeys().fields().forEachRemaining(entry -> {
                if (entry.getValue().isObject()) keys.add(entry.getValue().path("key").asText());
            });
        }
        return cleanup(keys);
    }

    private Mono<Void> cleanupReplacedImages(ImportRecord row) {
        if (row.commitResult() == null || !row.commitResult().has("replacedImages")) {
            return Mono.empty();
        }
        List<String> keys = new ArrayList<>();
        row.commitResult().path("replacedImages").forEach(node -> keys.add(node.asText()));
        return cleanup(keys);
    }

    private Mono<Void> cleanup(Collection<String> keys) {
        return Flux.fromIterable(keys == null ? List.<String>of() : keys)
                .filter(key -> key != null && !key.isBlank())
                .concatMap(key -> objectStore.delete(key).onErrorResume(error -> Mono.empty()))
                .then();
    }

    @Scheduled(fixedDelayString = "${gateway.skills.portable-import.cleanup-delay:PT10M}")
    public void cleanupExpired() {
        imports.expired(Instant.now(), 100)
                .concatMap(row -> imports.markExpired(row.importId(), Instant.now())
                        .then(cleanupImportObjects(row))
                        .then(cleanupReplacedImages(row))
                        .then(imports.delete(row.importId())))
                .onErrorResume(error -> Mono.empty())
                .subscribe();
    }

    private <E extends Enum<E>> Map<String, E> readActions(JsonNode result, String name, Class<E> type) {
        if (result == null || !result.has(name)) return Map.of();
        Map<String, E> values = new LinkedHashMap<>();
        result.path(name).fields().forEachRemaining(entry -> values.put(entry.getKey(),
                Enum.valueOf(type, entry.getValue().asText())));
        return values;
    }

    private JsonNode node(Object value) {
        return value == null ? NullNode.getInstance() : objectMapper.valueToTree(value);
    }

    private static void put(ObjectNode node, String name, String value) {
        if (value == null) node.set(name, NullNode.getInstance()); else node.put(name, value);
    }

    private ExpertPreviewContent preview(Object value) {
        if (value == null || value instanceof NullNode) return null;
        return objectMapper.convertValue(value, ExpertPreviewContent.class);
    }

    private JsonNode canvas(Object value) {
        if (value == null || value instanceof NullNode) return null;
        return objectMapper.valueToTree(value);
    }

    private static String string(Object value) {
        if (value == null) return null;
        return value instanceof String string ? string : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        return values.stream().map(String::valueOf).toList();
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private static String extension(String mediaType) {
        return switch (mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".png";
        };
    }

    private static String safeFileName(FilePart file) {
        return SkillPackageFileNames.safeFileName(file, "skill-portable.ycps.zip");
    }

    private static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new IllegalStateException("digest_unavailable", error); }
    }

    private static void deleteQuietly(Path file) {
        try { Files.deleteIfExists(file); } catch (Exception ignored) { }
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static ResponseStatusException expired(String reason) {
        return new ResponseStatusException(HttpStatus.GONE, reason);
    }

    private static ResponseStatusException notFound(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }

    private static RuntimeException expiredOrFailed(ImportRecord row) {
        return "EXPIRED".equals(row.status())
                ? expired("skill_portable_import_expired")
                : invalid("skill_portable_import_not_committable");
    }

    private static RuntimeException mapError(Throwable error) {
        if (error instanceof ResponseStatusException response) return response;
        return error instanceof RuntimeException runtime ? runtime : new RuntimeException(error);
    }

    private static String reason(Throwable error) {
        return error instanceof ResponseStatusException response ? response.getReason() : null;
    }

    private record SkillTarget(SkillView skill) {}

    private record ProfileValues(
            List<String> taskTypes,
            List<String> taskTypesEn,
            String usageGuide,
            String usageGuideEn,
            List<String> examplePrompts,
            List<String> examplePromptsEn,
            ExpertPreviewContent preview,
            ExpertPreviewContent previewEn,
            JsonNode canvas,
            String exampleImageLocale,
            int portableRevision,
            int rowVersion) {
        ProfileValues(SkillExpertProfileRepository.StoredProfile value) {
            this(value.taskTypes(), value.taskTypesEn(), value.usageGuide(), value.usageGuideEn(),
                    value.examplePrompts(), value.examplePromptsEn(), value.preview(), value.previewEn(),
                    value.canvas(), value.exampleImageLocale(), value.portableRevision(), value.rowVersion());
        }

        static ProfileValues empty() {
            return new ProfileValues(List.of(), List.of(), null, null, List.of(), List.of(), null, null,
                    null, null, 0, 0);
        }
    }

    private record CommitTarget(
            SkillView skill,
            ProfileValues profile,
            Map<String, String> images,
            boolean newSkill) {}

    private record CommitKey(
            UUID importId,
            UUID skillId,
            UUID versionId,
            int revision,
            Map<String, FieldAction> fields,
            Map<String, ImageAction> images,
            List<String> replacedImages) {}
}
