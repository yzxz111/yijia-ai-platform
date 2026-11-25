package com.yuzhihuihe.apiplatform.gateway.skill;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectMissingException;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import com.yuzhihuihe.apiplatform.gateway.file.StoredObjectMetadata;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.CreateSkillCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillView;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/** Native ClawHub source archive ingest and explicit publication. */
@Service
// 与 skill 包内其它每个类一致：本服务依赖 SkillAdminService，而后者是
// web-reactive 条件 bean（SkillAdminService:61）。少了这个条件，一个以
// spring.main.web-application-type=none 装配的上下文（GatewayServiceApplicationTests）
// 会因为"需要一个不存在的 SkillAdminService"而整块起不来——
// 一次装配失败会带走该类所有测试。它唯一的消费者 ClawhubSkillImportController
// 本身就带这个条件，因此非 web 场景下把本类一并排除是安全的。
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
final class ClawhubSkillImportService {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    /** 唯一一条日志通道：只用于那些"静默降级"必须留痕的地方（例如扫描器被关闭）。 */
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ClawhubSkillImportService.class);
    private static final String PACKAGE_FORMAT = ClawhubSkillAdapter.SOURCE_FORMAT;
    private static final String SCAN_PENDING = "PENDING";
    private static final String SCAN_PASSED = "PASSED";
    private static final String SCAN_REJECTED = "REJECTED";
    private static final String SCAN_FAILED = "FAILED";
    private static final String SOURCE_TAG = "ClawHub";
    private static final ObjectMapper METADATA_JSON = new ObjectMapper();
    private static final int PUBLISHED_OBJECT_VISIBILITY_RETRIES = 6;
    private static final Duration PUBLISHED_OBJECT_VISIBILITY_RETRY_DELAY = Duration.ofMillis(100);
    private static final Duration PUBLISHED_OBJECT_VISIBILITY_MAX_RETRY_DELAY = Duration.ofMillis(500);

    record Preview(
            ClawhubSkillAdapter.Report report,
            @JsonInclude(JsonInclude.Include.ALWAYS) String packageBase64) {}

    record Commit(
            UUID skillId,
            UUID sourceId,
            String code,
            String version,
            String status,
            String packageFormat,
            String sourceSha256,
            long sizeBytes,
            boolean reviewRequired,
            String scanStatus) {}

    record VersionItem(
            UUID id,
            UUID skillId,
            String owner,
            String slug,
            String version,
            String sourceSha256,
            long sizeBytes,
            String scanStatus,
            boolean reviewRequired,
            String status,
            Instant createdAt,
            Instant publishedAt,
            boolean online) {}

    record VersionList(List<VersionItem> items, UUID currentPublishedVersionId) {
        VersionList {
            items = List.copyOf(items == null ? List.of() : items);
        }
    }

    record SourceArchive(String objectKey, long sizeBytes, String sha256, String code) {}

    private final SkillPackageIngestService ingest;
    private final SkillAdminService admin;
    private final SkillRepository skills;
    private final NativeClawhubSkillRepository nativeSkills;
    private final FileObjectStore objectStore;
    private final TransactionalOperator transactions;
    private final ClawhubSkillAdapter adapter = new ClawhubSkillAdapter();
    private final ClawhubRegistryDisplayNameClient clawhubDisplayNames;

    ClawhubSkillImportService(
            SkillPackageIngestService ingest,
            SkillAdminService admin,
            SkillRepository skills,
            NativeClawhubSkillRepository nativeSkills,
            FileObjectStore objectStore,
            TransactionalOperator transactions,
            ClawhubRegistryDisplayNameClient clawhubDisplayNames) {
        this.ingest = ingest;
        this.admin = admin;
        this.skills = skills;
        this.nativeSkills = nativeSkills;
        this.objectStore = objectStore;
        this.transactions = transactions;
        this.clawhubDisplayNames = clawhubDisplayNames;
    }

    Mono<Preview> preview(
            FilePart file, String owner, String slug, String version, String displayName) {
        ResponseStatusException invalid = validateZipPart(file);
        if (invalid != null) return Mono.error(invalid);
        if (declaredSize(file) > ClawhubSkillAdapter.MAX_SOURCE_ZIP_BYTES) {
            return Mono.just(new Preview(blockedReport(owner, slug, version,
                    "Archive exceeds the 50 MiB source package limit"), null));
        }
        return temporaryRoot("clawhub-preview-")
                .flatMap(root -> {
                    Path source = root.resolve("source.zip");
                    return ingest.transferOnly(file, source)
                            .then(inspect(source, owner, slug, version, displayName))
                            .map(report -> new Preview(report, null))
                            .doFinally(signal -> Schedulers.boundedElastic()
                                    .schedule(() -> deleteTreeQuietly(root)));
                });
    }

    Mono<Commit> commit(
            FilePart file,
            String owner,
            String slug,
            String version,
            String displayName,
            String expectedSha256,
            UUID actor) {
        return commit(file, owner, slug, version, displayName, expectedSha256, null, actor);
    }

    Mono<Commit> commit(
            FilePart file,
            String owner,
            String slug,
            String version,
            String displayName,
            String expectedSha256,
            String metadataJson,
            UUID actor) {
        ResponseStatusException invalid = validateZipPart(file);
        if (invalid != null) return Mono.error(invalid);
        if (declaredSize(file) > ClawhubSkillAdapter.MAX_SOURCE_ZIP_BYTES) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "clawhub_archive_unsafe"));
        }
        if (!validSha256(expectedSha256)) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "clawhub_expected_sha256_invalid"));
        }
        ImportMetadata metadata;
        try {
            metadata = parseMetadata(metadataJson);
        } catch (ResponseStatusException invalidMetadata) {
            return Mono.error(invalidMetadata);
        }
        return temporaryRoot("clawhub-commit-")
                .flatMap(root -> {
                    Path source = root.resolve("source.zip");
                    return ingest.transferOnly(file, source)
                            .then(inspect(source, owner, slug, version, displayName))
                            .flatMap(report -> commitInspected(
                                    source, file.filename(), report, expectedSha256, metadata, actor))
                            .doFinally(signal -> Schedulers.boundedElastic()
                                    .schedule(() -> deleteTreeQuietly(root)));
                });
    }

    Mono<VersionList> versions(UUID skillId) {
        return skills.skill(skillId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "skill_not_found")))
                .flatMap(skill -> {
                    if (!PACKAGE_FORMAT.equals(skill.sourceFormat())) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "native_clawhub_source_format_mismatch"));
                    }
                    return nativeSkills.versions(skillId)
                            .map(ClawhubSkillImportService::versionItem)
                            .collectList()
                            .flatMap(items -> nativeSkills.currentSourceId(skillId)
                                    .map(current -> new VersionList(items, current))
                                    .defaultIfEmpty(new VersionList(items, null)));
                });
    }

    Mono<NativeClawhubSkillRepository.Source> sourceArchive(UUID skillId, UUID sourceId) {
        return requireActiveNativeSkill(skillId).then(nativeSkills.sourceById(skillId, sourceId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "native_clawhub_source_not_found")))
                .flatMap(source -> originalSourceArchive(source)
                        .thenReturn(source));
    }

    private Mono<Void> requireActiveNativeSkill(UUID skillId) {
        return skills.skill(skillId)
                .filter(skill -> PACKAGE_FORMAT.equals(skill.sourceFormat()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "native_clawhub_source_not_found")))
                .then();
    }

    Mono<Void> deleteVersion(UUID skillId, UUID sourceId, UUID actor) {
        Mono<NativeClawhubSkillRepository.Source> delete = transactions.transactional(
                skills.lockSkill(skillId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "skill_not_found")))
                        .flatMap(skill -> {
                            if (!PACKAGE_FORMAT.equals(skill.sourceFormat())) {
                                return Mono.error(new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "native_clawhub_source_format_mismatch"));
                            }
                            return nativeSkills.sourceIncludingDeleted(skillId, sourceId)
                                    .switchIfEmpty(Mono.error(new ResponseStatusException(
                                            HttpStatus.NOT_FOUND,
                                            "native_clawhub_source_not_found")))
                                    .flatMap(source -> {
                                        if ("DELETED".equals(source.status())) {
                                            return Mono.just(source);
                                        }
                                        if (!"DRAFT".equals(source.status())
                                                || source.publishedAt() != null) {
                                            return Mono.error(new ResponseStatusException(
                                                    HttpStatus.CONFLICT,
                                                    "native_clawhub_published_version_delete_forbidden"));
                                        }
                                        return nativeSkills.release(skillId)
                                                .filter(release -> sourceId.equals(release.sourceId()))
                                                .hasElement()
                                                .flatMap(isCurrent -> isCurrent
                                                        ? Mono.error(new ResponseStatusException(
                                                                HttpStatus.CONFLICT,
                                                                "native_clawhub_current_version_delete_forbidden"))
                                                        : nativeSkills.markDeletedDraft(sourceId)
                                                                .filter(updated -> updated == 1L)
                                                                .switchIfEmpty(Mono.error(
                                                                        new ResponseStatusException(
                                                                                HttpStatus.CONFLICT,
                                                                                "native_clawhub_version_delete_forbidden")))
                                                                .then(skills.audit(
                                                                        actor,
                                                                        SkillAdminService.ACTION_UPDATE,
                                                                        skillId,
                                                                        Map.of(
                                                                                "code", skill.code(),
                                                                                "field", "native_clawhub.version_deleted",
                                                                                "version", source.version() == null
                                                                                        ? "" : source.version(),
                                                                                "sourceId", source.id().toString())))
                                                                .thenReturn(source));
                                    });
                        }));
        return delete.flatMap(source -> objectStore.delete(source.objectKey()));
    }

    private Mono<VersionItem> versionItem(UUID skillId, UUID sourceId) {
        return nativeSkills.versions(skillId)
                .filter(item -> item.id().equals(sourceId))
                .next()
                .map(ClawhubSkillImportService::versionItem)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "native_clawhub_source_not_found")));
    }

    private static VersionItem versionItem(NativeClawhubSkillRepository.VersionEntry item) {
        return new VersionItem(
                item.id(), item.skillId(), item.owner(), item.slug(), item.version(),
                item.sourceSha256(), item.sizeBytes(), item.scanStatus(), item.reviewRequired(),
                item.status(), item.createdAt(), item.publishedAt(), item.online());
    }

    Mono<VersionItem> uploadVersion(
            UUID skillId,
            FilePart file,
            String version,
            String expectedSha256,
            UUID actor) {
        ResponseStatusException invalid = validateZipPart(file);
        if (invalid != null) return Mono.error(invalid);
        if (declaredSize(file) > ClawhubSkillAdapter.MAX_SOURCE_ZIP_BYTES) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "clawhub_archive_unsafe"));
        }
        if (version != null && !version.isBlank()
                && !ClawhubSkillAdapter.isPublishableVersion(version)) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "native_clawhub_version_invalid"));
        }
        if (!validSha256(expectedSha256)) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "clawhub_expected_sha256_invalid"));
        }
        return skills.skill(skillId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "skill_not_found")))
                .flatMap(skill -> {
                    if (!PACKAGE_FORMAT.equals(skill.sourceFormat())) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "native_clawhub_source_format_mismatch"));
                    }
                    return nativeSkills.source(skillId)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(
                                    HttpStatus.NOT_FOUND, "native_clawhub_source_not_found")))
                            .flatMap(identity -> temporaryRoot("clawhub-version-")
                                    .flatMap(root -> {
                                        Path local = root.resolve("source.zip");
                                        return ingest.transferOnly(file, local)
                                                .then(inspectMatchingIdentity(
                                                        local, identity,
                                                        version == null || version.isBlank()
                                                                ? null : version.strip()))
                                                .flatMap(report -> saveVersion(
                                                        skillId, skill.code(), local, file.filename(),
                                                        identity, report, expectedSha256, actor))
                                                .doFinally(signal -> Schedulers.boundedElastic()
                                                        .schedule(() -> deleteTreeQuietly(root)));
                                    }));
                });
    }

    private Mono<VersionItem> saveVersion(
            UUID skillId,
            String code,
            Path local,
            String originalFileName,
            NativeClawhubSkillRepository.Source identity,
            ClawhubSkillAdapter.Report report,
            String expectedSha256,
            UUID actor) {
        if (ClawhubSkillAdapter.BLOCKED.equals(report.status())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "clawhub_archive_blocked"));
        }
        if (!identity.owner().equals(report.owner()) || !identity.slug().equals(report.slug())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "native_clawhub_identity_mismatch"));
        }
        if (report.version() == null || !ClawhubSkillAdapter.isPublishableVersion(report.version())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "native_clawhub_version_invalid"));
        }
        if (expectedSha256 != null && !expectedSha256.isBlank()
                && !expectedSha256.strip().equalsIgnoreCase(report.sourceSha256())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "clawhub_source_changed"));
        }
        return fileSize(local).flatMap(size -> scanStatus(local).flatMap(scan -> {
            if (SCAN_REJECTED.equals(scan)) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "skill_package_rejected"));
            }
            UUID sourceId = UUID.randomUUID();
            String objectKey = "quarantine/native-clawhub/" + sourceId + "/"
                    + report.sourceSha256() + ".zip";
            Instant now = Instant.now();
            NativeClawhubSkillRepository.Source record = new NativeClawhubSkillRepository.Source(
                    sourceId,
                    skillId,
                    identity.owner(),
                    identity.slug(),
                    report.version(),
                    report.name(),
                    report.description(),
                    report.sourceSha256(),
                    size,
                    SkillPackageFileNames.safeFileName(originalFileName, "clawhub-source.zip"),
                    objectKey,
                    "DRAFT",
                    scan,
                    report.reviewRequired() || !SCAN_PASSED.equals(scan),
                    report.reviewRequired(),
                    report.adapterVersion(),
                    report.decisions(),
                    report.blockingReasons(),
                    actor,
                    now,
                    null);
            return objectStore.putFile(objectKey, local, "application/zip")
                    .then(Mono.defer(() -> objectStore.head(objectKey)
                            .switchIfEmpty(Mono.error(new IllegalStateException(
                                    "native_clawhub_source_upload_missing")))
                            .flatMap(metadata -> metadata.sizeBytes() == size
                                    ? Mono.empty()
                                    : Mono.error(new IllegalStateException(
                                            "native_clawhub_source_size_mismatch")))))
                    .then(transactions.transactional(skills.lockSkill(skillId)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(
                                    HttpStatus.NOT_FOUND, "skill_not_found")))
                            .flatMap(skill -> {
                                if (!PACKAGE_FORMAT.equals(skill.sourceFormat())) {
                                    return Mono.error(new ResponseStatusException(
                                            HttpStatus.CONFLICT,
                                            "native_clawhub_source_format_mismatch"));
                                }
                                return nativeSkills.insertSource(record)
                                        .filter(updated -> updated == 1L)
                                        .switchIfEmpty(Mono.error(new IllegalStateException(
                                                "native_clawhub_source_not_saved")))
                                        .then(nativeSkills.markNativeVersionUploaded(
                                                skillId, report.version(), actor, now))
                                        .filter(updated -> updated == 1L)
                                        .switchIfEmpty(Mono.error(new IllegalStateException(
                                                "native_clawhub_latest_version_not_saved")))
                                        .then(skills.audit(actor, SkillAdminService.ACTION_UPDATE,
                                                skillId, Map.of(
                                                        "code", code,
                                                        "field", "native_clawhub.version_uploaded",
                                                        "version", report.version(),
                                                        "sourceSha256", report.sourceSha256(),
                                                        "sourceId", sourceId.toString())))
                                        .thenReturn(versionItem(new NativeClawhubSkillRepository.VersionEntry(
                                                sourceId, skillId, identity.owner(), identity.slug(),
                                                report.version(), report.sourceSha256(), size, scan,
                                                record.reviewRequired(), "DRAFT", now, null, false)));
                            })))
                    .onErrorMap(DuplicateKeyException.class, error -> new ResponseStatusException(
                            HttpStatus.CONFLICT, "native_clawhub_version_already_exists", error))
                    .onErrorResume(error -> objectStore.delete(objectKey)
                            .onErrorResume(cleanupError -> Mono.empty())
                            .then(Mono.error(error)));
        }));
    }

    private Mono<ClawhubSkillAdapter.Report> inspectMatchingIdentity(
            Path local, NativeClawhubSkillRepository.Source identity, String requestedVersion) {
        if (identity.owner().startsWith("github.")) {
            // GitHub packages can carry an unrelated ClawHub origin declaration. Keep the
            // namespaced repository identity for later source versions and require review.
            return inspect(local, identity.owner(), identity.slug(), requestedVersion, null);
        }
        return inspect(local, null, null, requestedVersion, null).flatMap(report -> {
            if (ClawhubSkillAdapter.BLOCKED.equals(report.status())) return Mono.just(report);
            if (report.decisions().contains("Archive origin does not match the selected owner and slug")) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "native_clawhub_identity_mismatch"));
            }
            if ((report.owner() != null && !identity.owner().equals(report.owner()))
                    || (report.slug() != null && !identity.slug().equals(report.slug()))) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "native_clawhub_identity_mismatch"));
            }
            String ownerFallback = report.owner() == null ? identity.owner() : null;
            String slugFallback = report.slug() == null ? identity.slug() : null;
            if (ownerFallback == null && slugFallback == null) return Mono.just(report);
            return inspect(local, ownerFallback, slugFallback, requestedVersion, null);
        });
    }

    private Mono<Commit> commitInspected(
            Path source,
            String originalFileName,
            ClawhubSkillAdapter.Report report,
            String expectedSha256,
            ImportMetadata metadata,
            UUID actor) {
        if (ClawhubSkillAdapter.BLOCKED.equals(report.status())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    report.blockingReasons().isEmpty()
                            ? "clawhub_archive_unsafe"
                            : "clawhub_archive_blocked"));
        }
        if (report.owner() == null || report.slug() == null || report.code() == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "clawhub_owner_slug_required"));
        }
        if (metadata != null && metadata.sourceUrl() != null) {
            RemoteSkillFetchService.Reference sourceRef =
                    RemoteSkillFetchService.parseReference(metadata.sourceUrl());
            boolean matches = "CLAWHUB".equals(sourceRef.sourceKind())
                    ? report.owner().equals(sourceRef.owner())
                            && report.slug().equals(sourceRef.slug())
                    : report.owner().equals("github." + sourceRef.owner());
            if (!matches) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "clawhub_import_metadata_invalid"));
            }
        }
        if (expectedSha256 != null && !expectedSha256.isBlank()
                && !expectedSha256.strip().equalsIgnoreCase(report.sourceSha256())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "clawhub_source_changed"));
        }
        if (report.version() != null && report.version().length() > 128) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "clawhub_version_too_long"));
        }
        return fileSize(source)
                .flatMap(size -> scanStatus(source).flatMap(scanStatus -> {
                    if (SCAN_REJECTED.equals(scanStatus)) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY, "skill_package_rejected"));
                    }
                    String safeName = SkillPackageFileNames.safeFileName(originalFileName, "clawhub-source.zip");
                    return nativeSkills.sourceByIdentity(report.owner(), report.slug())
                            .flatMap(existing -> Mono.<Commit>error(new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    SCAN_REJECTED.equals(existing.scanStatus())
                                            ? "native_clawhub_source_rejected"
                                            : "native_clawhub_already_exists")))
                            .switchIfEmpty(Mono.defer(() -> saveNewSource(
                                    source, size, safeName, report, scanStatus, metadata, actor)));
                }));
    }

    private Mono<Commit> saveNewSource(
            Path source,
            long size,
            String fileName,
            ClawhubSkillAdapter.Report report,
            String scanStatus,
            ImportMetadata metadata,
            UUID actor) {
        UUID sourceId = UUID.randomUUID();
        String objectKey = "quarantine/native-clawhub/" + sourceId + "/"
                + report.sourceSha256() + ".zip";
        Instant now = Instant.now();

        return objectStore.putFile(objectKey, source, "application/zip")
                .then(Mono.defer(() -> objectStore.head(objectKey)
                        .switchIfEmpty(Mono.error(new IllegalStateException(
                                "native_clawhub_source_upload_missing")))
                        .flatMap(objectMetadata -> objectMetadata.sizeBytes() == size
                                ? Mono.empty()
                                : Mono.error(new IllegalStateException(
                                        "native_clawhub_source_size_mismatch")))))
                .then(transactions.transactional(
                        admin.create(createSkillCommand(report, metadata), actor)
                                .flatMap(skill -> {
                                    NativeClawhubSkillRepository.Source record =
                                            new NativeClawhubSkillRepository.Source(
                                                    sourceId,
                                                    skill.id(),
                                                    report.owner(),
                                                    report.slug(),
                                                    report.version(),
                                                    report.name(),
                                                    report.description(),
                                                    report.sourceSha256(),
                                                    size,
                                                    fileName,
                                                    objectKey,
                                                    "DRAFT",
                                                    scanStatus,
                                                    report.reviewRequired()
                                                            || !SCAN_PASSED.equals(scanStatus),
                                                    report.reviewRequired(),
                                                    report.adapterVersion(),
                                                    sourceDecisions(report, metadata),
                                                    report.blockingReasons(),
                                                    actor,
                                                    now,
                                                    null);
                                    return nativeSkills.markSkillNative(skill.id())
                                            .filter(updated -> updated == 1L)
                                            .switchIfEmpty(Mono.error(new IllegalStateException(
                                                    "native_clawhub_skill_missing")))
                                            .then(nativeSkills.insertSource(record))
                                            .filter(updated -> updated == 1L)
                                            .switchIfEmpty(Mono.error(new IllegalStateException(
                                                    "native_clawhub_source_not_saved")))
                                            .then(report.version() != null
                                                    && ClawhubSkillAdapter.isPublishableVersion(report.version())
                                                    ? nativeSkills.markNativeVersionUploaded(
                                                            skill.id(), report.version(), actor, now)
                                                            .filter(updated -> updated == 1L)
                                                            .switchIfEmpty(Mono.error(new IllegalStateException(
                                                                    "native_clawhub_latest_version_not_saved")))
                                                    : Mono.just(1L))
                                            .thenReturn(new Commit(
                                                    skill.id(),
                                                    sourceId,
                                                    report.code(),
                                                    report.version(),
                                                    "DRAFT",
                                                    PACKAGE_FORMAT,
                                                    report.sourceSha256(),
                                                    size,
                                                    record.reviewRequired(),
                                                    scanStatus));
                                })))
                .onErrorResume(DuplicateKeyException.class, error -> Mono.error(
                        new ResponseStatusException(
                                HttpStatus.CONFLICT, "native_clawhub_already_exists", error)))
                .onErrorResume(error -> objectStore.delete(objectKey)
                        .onErrorResume(cleanupError -> Mono.empty())
                        .then(Mono.error(error)));
    }

    Mono<Commit> addMissingVersion(UUID skillId, String version, UUID actor) {
        if (!ClawhubSkillAdapter.isPublishableVersion(version)) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "native_clawhub_version_invalid"));
        }
        return transactions.transactional(skills.lockSkill(skillId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "skill_not_found")))
                .flatMap(skill -> {
                    if (!PACKAGE_FORMAT.equals(skill.sourceFormat())) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "native_clawhub_source_format_mismatch"));
                    }
                    if (!"DRAFT".equals(skill.status())) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "native_clawhub_version_draft_only"));
                    }
                    return nativeSkills.source(skillId)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(
                                    HttpStatus.NOT_FOUND, "native_clawhub_source_not_found")))
                            .flatMap(source -> {
                                if (!"DRAFT".equals(source.status())) {
                                    return Mono.error(new ResponseStatusException(
                                            HttpStatus.CONFLICT, "native_clawhub_version_draft_only"));
                                }
                                if (source.version() != null) {
                                    return Mono.error(new ResponseStatusException(
                                            HttpStatus.CONFLICT, "native_clawhub_version_already_set"));
                                }
                                return nativeSkills.setSourceVersionIfMissing(source.id(), version)
                                        .filter(updated -> updated == 1L)
                                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                                HttpStatus.CONFLICT,
                                                "native_clawhub_version_already_set")))
                                        .then(skills.audit(actor, SkillAdminService.ACTION_UPDATE,
                                                skillId, Map.of(
                                                        "code", skill.code(),
                                                        "field", "native_clawhub.source_version",
                                                        "value", version,
                                                        "sourceId", source.id().toString())))
                                        .then(nativeSkills.markNativeVersionUploaded(
                                                skillId, version, actor, Instant.now()))
                                        .thenReturn(new Commit(
                                                source.skillId(),
                                                source.id(),
                                                ClawhubSkillAdapter.nativeCode(
                                                        source.owner(), source.slug()),
                                                version,
                                                "DRAFT",
                                                PACKAGE_FORMAT,
                                                source.sha256(),
                                                source.sizeBytes(),
                                                source.reviewRequired(),
                                                source.scanStatus()));
                            });
                }));
    }

    Mono<Commit> rescan(UUID skillId, UUID actor) {
        return nativeSkills.versions(skillId).take(2).collectList().flatMap(versions -> {
            if (versions.isEmpty()) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "native_clawhub_source_not_found"));
            }
            if (versions.size() > 1) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "native_clawhub_version_id_required"));
            }
            return nativeSkills.sourceById(skillId, versions.get(0).id())
                    .switchIfEmpty(Mono.error(new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "native_clawhub_source_not_found")))
                    .flatMap(source -> rescanSource(source, actor));
        });
    }

    Mono<VersionItem> rescanVersion(UUID skillId, UUID sourceId, UUID actor) {
        return nativeSkills.sourceById(skillId, sourceId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "native_clawhub_source_not_found")))
                .flatMap(source -> rescanSource(source, actor)
                        .then(versionItem(skillId, sourceId)));
    }

    private Mono<Commit> rescanSource(
            NativeClawhubSkillRepository.Source source, UUID actor) {
        if (!"DRAFT".equals(source.status())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "native_clawhub_rescan_draft_only"));
        }
        if (SCAN_REJECTED.equals(source.scanStatus())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "native_clawhub_source_rejected"));
        }
        if (!SCAN_PENDING.equals(source.scanStatus()) && !SCAN_FAILED.equals(source.scanStatus())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "native_clawhub_rescan_not_required"));
        }
        return temporaryRoot("native-clawhub-rescan-")
                .flatMap(root -> {
                    Path local = root.resolve("source.zip");
                    return materializeAndVerify(source, local)
                            .then(scanStatus(local))
                            .flatMap(status -> recordRescan(source, status, actor))
                            .doFinally(signal -> Schedulers.boundedElastic()
                                    .schedule(() -> deleteTreeQuietly(root)));
                });
    }

    Mono<SourceArchive> sourceArchive(UUID skillId) {
        return requireActiveNativeSkill(skillId).then(nativeSkills.release(skillId))
                .flatMap(release -> nativeSkills.sourceById(skillId, release.sourceId())
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "native_clawhub_release_conflict")))
                        .flatMap(source -> publishedSourceArchive(source, release)))
                .switchIfEmpty(Mono.defer(() -> nativeSkills.latestDraftSource(skillId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "native_clawhub_source_not_found")))
                        .flatMap(this::originalSourceArchive)));
    }

    /** Resolve only a published release, including a verified original if its clean copy is gone. */
    Mono<SourceArchive> publishedSourceArchive(UUID skillId) {
        return nativeSkills.release(skillId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "native_clawhub_package_not_found")))
                .flatMap(release -> nativeSkills.sourceById(skillId, release.sourceId())
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "native_clawhub_release_conflict")))
                        .flatMap(source -> publishedSourceArchive(source, release)));
    }

    private Mono<SourceArchive> originalSourceArchive(NativeClawhubSkillRepository.Source source) {
        if (SCAN_REJECTED.equals(source.scanStatus())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "native_clawhub_source_rejected"));
        }
        String expectedQuarantineKey = "quarantine/native-clawhub/"
                + source.id() + "/" + source.sha256() + ".zip";
        if (!expectedQuarantineKey.equals(source.objectKey())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "native_clawhub_source_integrity_failed"));
        }
        return verifySourceArchive(new SourceArchive(
                source.objectKey(), source.sizeBytes(), source.sha256(),
                ClawhubSkillAdapter.nativeCode(source.owner(), source.slug())));
    }

    private Mono<SourceArchive> publishedSourceArchive(
            NativeClawhubSkillRepository.Source source,
            NativeClawhubSkillRepository.Release release) {
        if (SCAN_REJECTED.equals(source.scanStatus())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "native_clawhub_source_rejected"));
        }
        if (!"PUBLISHED".equals(source.status())
                || !PACKAGE_FORMAT.equals(release.packageFormat())
                || source.version() == null
                || !java.util.Objects.equals(source.version(), release.version())
                || !java.util.Objects.equals(source.sha256(), release.sha256())
                || source.sizeBytes() != release.sizeBytes()
                || !cleanKey(source).equals(release.objectKey())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "native_clawhub_release_conflict"));
        }
        return verifySourceArchive(new SourceArchive(
                release.objectKey(), release.sizeBytes(), release.sha256(),
                ClawhubSkillAdapter.nativeCode(source.owner(), source.slug())))
                // Admin export may use the original upload when a historical clean copy is
                // missing. Keep the release checks above and verify the original bytes too.
                .onErrorResume(ClawhubSkillImportService::isMissingPublishedObject,
                        ignored -> originalSourceArchive(source));
    }

    private Mono<SourceArchive> verifySourceArchive(SourceArchive archive) {
        if (archive.sizeBytes() <= 0
                || archive.sizeBytes() > ClawhubSkillAdapter.MAX_SOURCE_ZIP_BYTES
                || archive.sha256() == null
                || !SHA256.matcher(archive.sha256()).matches()
                || archive.code() == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "native_clawhub_source_integrity_failed"));
        }
        return objectStore.head(archive.objectKey())
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "native_clawhub_source_missing")))
                .flatMap(metadata -> metadata.sizeBytes() == archive.sizeBytes()
                        ? verifyObjectBytes(archive).thenReturn(archive)
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "native_clawhub_source_integrity_failed")))
                .onErrorMap(FileObjectMissingException.class, error -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "native_clawhub_source_missing", error));
    }

    private Mono<Void> verifyObjectBytes(SourceArchive archive) {
        return Mono.<Void>defer(() -> {
            MessageDigest digest = sha256Digest();
            AtomicLong byteCount = new AtomicLong();
            return objectStore.getRange(archive.objectKey(), 0L, archive.sizeBytes())
                    .doOnNext(buffer -> {
                        ByteBuffer bytes = buffer.duplicate();
                        byteCount.addAndGet(bytes.remaining());
                        digest.update(bytes);
                    })
                    .then(Mono.fromRunnable(() -> {
                        String actualSha256 = HexFormat.of().formatHex(digest.digest());
                        if (byteCount.get() != archive.sizeBytes()
                                || !archive.sha256().equals(actualSha256)) {
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "native_clawhub_source_integrity_failed");
                        }
                    }));
        }).onErrorMap(FileObjectMissingException.class, error -> new ResponseStatusException(
                HttpStatus.CONFLICT, "native_clawhub_source_missing", error));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private Mono<Void> materializeAndVerify(
            NativeClawhubSkillRepository.Source source, Path destination) {
        if (source.sizeBytes() <= 0
                || source.sizeBytes() > ClawhubSkillAdapter.MAX_SOURCE_ZIP_BYTES) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "native_clawhub_source_integrity_failed"));
        }
        return Mono.defer(() -> objectStore.head(source.objectKey()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "native_clawhub_source_missing")))
                .flatMap(metadata -> metadata.sizeBytes() == source.sizeBytes()
                        ? Mono.empty()
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "native_clawhub_source_integrity_failed")))
                .then(DataBufferUtils.write(
                        Flux.defer(() -> objectStore.getRange(
                                        source.objectKey(), 0L, source.sizeBytes())
                                .map(DefaultDataBufferFactory.sharedInstance::wrap)),
                        destination,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE))
                .then(fileSize(destination))
                .flatMap(size -> size == source.sizeBytes()
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "native_clawhub_source_integrity_failed")))
                .then(Mono.fromCallable(() -> ClawhubSkillAdapter.sha256(destination))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(sha256 -> source.sha256().equals(sha256)
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "native_clawhub_source_integrity_failed")))
                .onErrorMap(FileObjectMissingException.class, error ->
                        new ResponseStatusException(
                                HttpStatus.CONFLICT, "native_clawhub_source_missing", error));
    }

    private Mono<Commit> recordRescan(
            NativeClawhubSkillRepository.Source source, String scanStatus, UUID actor) {
        return transactions.transactional(skills.lockSkill(source.skillId())
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "skill_not_found")))
                .flatMap(skill -> {
                    if (!PACKAGE_FORMAT.equals(skill.sourceFormat())) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "native_clawhub_source_format_mismatch"));
                    }
                    return nativeSkills.updateVersionScanStatus(
                                    source.id(), source.scanStatus(), scanStatus)
                            .filter(updated -> updated == 1L)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(
                                    HttpStatus.CONFLICT, "native_clawhub_rescan_not_required")))
                            .then(skills.audit(actor, SkillAdminService.ACTION_UPDATE,
                                    source.skillId(), Map.of(
                                            "code", skill.code(),
                                            "field", "native_clawhub.scan_status",
                                            "from", source.scanStatus(),
                                            "to", scanStatus,
                                            "sourceId", source.id().toString(),
                                            "sourceSha256", source.sha256())))
                            .then(nativeSkills.sourceById(source.skillId(), source.id()))
                            .switchIfEmpty(Mono.error(new IllegalStateException(
                                    "native_clawhub_source_missing_after_rescan")))
                            .map(updated -> new Commit(
                                    updated.skillId(),
                                    updated.id(),
                                    ClawhubSkillAdapter.nativeCode(updated.owner(), updated.slug()),
                                    updated.version(),
                                    "DRAFT",
                                    PACKAGE_FORMAT,
                                    updated.sha256(),
                                    updated.sizeBytes(),
                                    updated.reviewRequired(),
                                    updated.scanStatus()));
                }));
    }

    Mono<SkillView> publish(UUID skillId, UUID actor) {
        Mono<UUID> selectedSourceId = nativeSkills.currentSourceId(skillId)
                .switchIfEmpty(Mono.defer(() -> nativeSkills.versions(skillId)
                        .take(2)
                        .collectList()
                        .flatMap(items -> {
                            if (items.isEmpty()) {
                                return Mono.error(new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "native_clawhub_source_not_found"));
                            }
                            if (items.size() > 1) {
                                return Mono.error(new ResponseStatusException(
                                        HttpStatus.CONFLICT, "native_clawhub_version_id_required"));
                            }
                            return Mono.just(items.getFirst().id());
                        })));
        return selectedSourceId.flatMap(sourceId -> publishVersion(skillId, sourceId, actor));
    }

    Mono<SkillView> publishVersion(UUID skillId, UUID sourceId, UUID actor) {
        return nativeSkills.sourceById(skillId, sourceId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "native_clawhub_source_not_found")))
                .flatMap(source -> {
                    if (!SCAN_PASSED.equals(source.scanStatus())) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "native_clawhub_scan_not_passed"));
                    }
                    if (!ClawhubSkillAdapter.isPublishableVersion(source.version())) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "native_clawhub_version_required"));
                    }
                    String cleanKey = cleanKey(source);
                    return ensurePublishedObject(source, cleanKey)
                            .then(publishDatabase(source, cleanKey, actor))
                            .then(admin.detail(skillId));
                });
    }

    private Mono<Boolean> ensurePublishedObject(
            NativeClawhubSkillRepository.Source source, String cleanKey) {
        String code = ClawhubSkillAdapter.nativeCode(source.owner(), source.slug());
        SourceArchive expectedSource = new SourceArchive(
                source.objectKey(), source.sizeBytes(), source.sha256(), code);
        SourceArchive expectedRelease = new SourceArchive(
                cleanKey, source.sizeBytes(), source.sha256(), code);
        return verifySourceArchiveEventually(expectedSource)
                .then(objectStore.head(cleanKey)
                        .onErrorResume(FileObjectMissingException.class, ignored -> Mono.empty())
                        .flatMap(metadata -> metadata.sizeBytes() == source.sizeBytes()
                                ? verifySourceArchiveEventually(expectedRelease).thenReturn(false)
                                : Mono.error(new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "native_clawhub_release_integrity_failed")))
                        .switchIfEmpty(Mono.defer(() -> promoteSource(source, cleanKey)
                                .then(verifySourceArchiveEventually(expectedRelease))
                                .thenReturn(true))));
    }

    private Mono<SourceArchive> verifySourceArchiveEventually(SourceArchive archive) {
        return Mono.defer(() -> verifySourceArchive(archive))
                .retryWhen(publishedObjectVisibilityRetry());
    }

    private Mono<StoredObjectMetadata> headWithVisibilityRetry(String objectKey) {
        return Mono.defer(() -> objectStore.head(objectKey)
                        .switchIfEmpty(Mono.error(new FileObjectMissingException())))
                .retryWhen(publishedObjectVisibilityRetry())
                .onErrorMap(FileObjectMissingException.class, error -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "native_clawhub_source_missing", error));
    }

    private static Retry publishedObjectVisibilityRetry() {
        return Retry.backoff(
                        PUBLISHED_OBJECT_VISIBILITY_RETRIES,
                        PUBLISHED_OBJECT_VISIBILITY_RETRY_DELAY)
                .maxBackoff(PUBLISHED_OBJECT_VISIBILITY_MAX_RETRY_DELAY)
                .jitter(0)
                .filter(ClawhubSkillImportService::isMissingPublishedObject)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    private static boolean isMissingPublishedObject(Throwable error) {
        return error instanceof FileObjectMissingException
                || error instanceof ResponseStatusException status
                        && "native_clawhub_source_missing".equals(status.getReason());
    }

    private Mono<Void> promoteSource(
            NativeClawhubSkillRepository.Source source, String cleanKey) {
        return headWithVisibilityRetry(source.objectKey())
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "native_clawhub_source_missing")))
                .flatMap(metadata -> metadata.sizeBytes() == source.sizeBytes()
                        ? Mono.empty()
                        : Mono.error(new IllegalStateException("native_clawhub_source_size_mismatch")))
                .then(Mono.defer(() -> objectStore.copy(source.objectKey(), cleanKey)))
                .then(Mono.defer(() -> headWithVisibilityRetry(cleanKey)
                        .switchIfEmpty(Mono.error(new IllegalStateException(
                                "native_clawhub_release_missing")))
                        .flatMap(metadata -> metadata.sizeBytes() == source.sizeBytes()
                                ? Mono.empty()
                                : Mono.error(new IllegalStateException(
                                        "native_clawhub_release_size_mismatch")))));
    }

    private Mono<Long> publishDatabase(
            NativeClawhubSkillRepository.Source source, String cleanKey, UUID actor) {
        Instant now = Instant.now();
        return transactions.transactional(skills.lockSkill(source.skillId())
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "skill_not_found")))
                .flatMap(skill -> {
                    if (!PACKAGE_FORMAT.equals(skill.sourceFormat())) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "native_clawhub_source_format_mismatch"));
                    }
                    NativeClawhubSkillRepository.Release release =
                            new NativeClawhubSkillRepository.Release(
                                    UUID.randomUUID(),
                                    source.skillId(),
                                    source.id(),
                                    source.version(),
                                    PACKAGE_FORMAT,
                                    source.sha256(),
                                    source.sizeBytes(),
                                    cleanKey,
                                    actor,
                                    now);
                    return nativeSkills.sourceById(source.skillId(), source.id())
                            .filter(current -> SCAN_PASSED.equals(current.scanStatus())
                                    && source.version().equals(current.version())
                                    && source.sha256().equals(current.sha256()))
                            .switchIfEmpty(Mono.error(new ResponseStatusException(
                                    HttpStatus.CONFLICT, "native_clawhub_source_changed")))
                            .then(nativeSkills.saveCurrentRelease(release))
                            .filter(inserted -> inserted == 1L)
                            .switchIfEmpty(Mono.error(new IllegalStateException(
                                    "native_clawhub_release_not_saved")))
                            .then(nativeSkills.markSourcePublished(source.id()))
                            .filter(updated -> updated == 1L)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(
                                    HttpStatus.CONFLICT, "native_clawhub_scan_not_passed")))
                            .then(skills.markNativePublished(
                                    source.skillId(), source.version(), actor, now))
                            .filter(updated -> updated == 1L)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(
                                    HttpStatus.CONFLICT, "skill_concurrent_modification")))
                            .then(skills.audit(actor, SkillAdminService.ACTION_PUBLISH,
                                    source.skillId(), Map.of(
                                            "code", skill.code(),
                                            "version", source.version(),
                                            "packageFormat", PACKAGE_FORMAT,
                                            "owner", source.owner(),
                                            "slug", source.slug(),
                                            "sourceSha256", source.sha256(),
                                            "sourceId", source.id().toString())))
                            .thenReturn(1L);
                }));
    }

    private Mono<ClawhubSkillAdapter.Report> inspect(
            Path source, String owner, String slug, String version, String displayName) {
        return fileSize(source)
                .flatMap(size -> size > ClawhubSkillAdapter.MAX_SOURCE_ZIP_BYTES
                        ? Mono.just(blockedReport(owner, slug, version,
                                "Archive exceeds the 50 MiB source package limit"))
                        : Mono.fromCallable(() -> adapter.inspect(
                                source, owner, slug, version, displayName))
                                .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(report -> applyRegistryDisplayName(report, displayName))
                .onErrorResume(ResponseStatusException.class, error -> Mono.just(blockedReport(
                        owner, slug, version, "Uploaded file is not a safe ClawHub Skill archive")));
    }

    private Mono<ClawhubSkillAdapter.Report> applyRegistryDisplayName(
            ClawhubSkillAdapter.Report report, String requestedDisplayName) {
        if (ClawhubSkillAdapter.BLOCKED.equals(report.status())
                || (requestedDisplayName != null && !requestedDisplayName.isBlank())
                || report.slug() == null) {
            return Mono.just(report);
        }
        return clawhubDisplayNames.displayName(report.owner(), report.slug())
                .map(name -> withName(report, name))
                .defaultIfEmpty(report);
    }

    private static ClawhubSkillAdapter.Report withName(
            ClawhubSkillAdapter.Report report, String displayName) {
        return new ClawhubSkillAdapter.Report(
                report.sourceFormat(),
                report.adapterVersion(),
                report.sourceSha256(),
                report.code(),
                report.version(),
                report.status(),
                report.decisions(),
                report.blockingReasons(),
                displayName,
                report.owner(),
                report.slug(),
                report.description());
    }

    private Mono<String> scanStatus(Path source) {
        return ingest.scanOnly(source)
                .map(result -> {
                    if (!result.integrated()) {
                        // 扫描器被显式关闭（开发/CI 环境常常没有 ClamAV）。与 YCPS 那条链路
                        // 保持一致：视为通过。否则这些环境永远发不了原生包 —— 导入成功、预览正常，
                        // 点发布永远 409 native_clawhub_scan_not_passed，而重扫只能从
                        // PENDING/FAILED 出发，形成一个没有出口的死循环。
                        log.warn("native_clawhub_scan_disabled_publishing_without_antivirus");
                        return SCAN_PASSED;
                    }
                    return "clean".equals(result.result()) ? SCAN_PASSED : SCAN_PENDING;
                })
                .onErrorResume(ResponseStatusException.class, error ->
                        Mono.just("skill_package_rejected".equals(error.getReason())
                                ? SCAN_REJECTED
                                : error.getStatusCode().is5xxServerError() ? SCAN_PENDING : SCAN_FAILED))
                .onErrorReturn(SCAN_FAILED);
    }

    record ImportMetadata(
            String name,
            String nameEn,
            String category,
            List<String> tags,
            String summary,
            String summaryEn,
            String description,
            String descriptionEn,
            String minClientVersion,
            Integer sortWeight,
            String sourceKind,
            String sourceUrl,
            String sourceRevision) {}

    private static ImportMetadata parseMetadata(String json) {
        if (json == null || json.isBlank()) return null;
        if (json.length() > 65_536) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "clawhub_import_metadata_invalid");
        }
        try {
            ImportMetadata metadata = METADATA_JSON.readValue(json, ImportMetadata.class);
            if (metadata.sourceUrl() != null) {
                RemoteSkillFetchService.Reference ref =
                        RemoteSkillFetchService.parseReference(metadata.sourceUrl());
                if (!ref.sourceKind().equals(metadata.sourceKind())
                        || metadata.sourceRevision() != null
                                && !metadata.sourceRevision().matches("(?i)^[a-f0-9]{40}$")) {
                    throw new IllegalArgumentException("remote source metadata mismatch");
                }
            } else if (metadata.sourceKind() != null || metadata.sourceRevision() != null) {
                throw new IllegalArgumentException("remote source metadata incomplete");
            }
            return metadata;
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "clawhub_import_metadata_invalid", invalid);
        }
    }

    private static CreateSkillCommand createSkillCommand(
            ClawhubSkillAdapter.Report report, ImportMetadata metadata) {
        String description = report.description();
        String summary = description == null || description.isBlank()
                ? null : description.substring(0, Math.min(description.length(), 512));
        List<String> defaultTags = report.owner() != null && report.owner().startsWith("github.")
                ? githubSourceTags(report.owner()) : sourceTags(report.owner());
        return new CreateSkillCommand(
                report.code(), select(metadata == null ? null : metadata.name(), report.name()),
                metadata == null ? null : metadata.nameEn(),
                metadata == null ? null : metadata.category(),
                metadata != null && metadata.tags() != null ? metadata.tags() : defaultTags,
                select(metadata == null ? null : metadata.summary(), summary),
                metadata == null ? null : metadata.summaryEn(),
                select(metadata == null ? null : metadata.description(), description),
                metadata == null ? null : metadata.descriptionEn(),
                null,
                metadata == null ? null : metadata.minClientVersion(),
                "SKILL", metadata == null || metadata.sortWeight() == null ? 0 : metadata.sortWeight());
    }

    private static String select(String requested, String fallback) {
        return requested == null ? fallback : requested;
    }

    private static List<String> sourceDecisions(
            ClawhubSkillAdapter.Report report, ImportMetadata metadata) {
        if (metadata == null || metadata.sourceUrl() == null) return report.decisions();
        List<String> decisions = new java.util.ArrayList<>(report.decisions());
        decisions.add("Remote import source (operator provided): " + metadata.sourceUrl());
        if (metadata.sourceRevision() != null) {
            decisions.add("Remote import source revision: " + metadata.sourceRevision());
        }
        return List.copyOf(decisions);
    }

    private static List<String> githubSourceTags(String owner) {
        String actualOwner = owner.substring("github.".length());
        return actualOwner.isBlank() || actualOwner.length() > SkillAdminService.TAG_MAX
                || "GitHub".equalsIgnoreCase(actualOwner)
                ? List.of("GitHub") : List.of("GitHub", actualOwner);
    }

    static List<String> sourceTags(String owner) {
        // The catalog limit admits every valid ClawHub owner (up to 64 characters).
        // Keep the source tag if this helper is ever given an invalid longer owner.
        if (owner == null || owner.isBlank() || owner.length() > SkillAdminService.TAG_MAX
                || SOURCE_TAG.equalsIgnoreCase(owner)) {
            return List.of(SOURCE_TAG);
        }
        return List.of(SOURCE_TAG, owner);
    }

    private static String cleanKey(NativeClawhubSkillRepository.Source source) {
        return "clean/native-clawhub/" + source.skillId() + "/" + source.sha256() + ".zip";
    }

    private static Preview invalidPreview(String owner, String slug, String version, String reason) {
        return new Preview(blockedReport(owner, slug, version, reason), null);
    }

    private static ClawhubSkillAdapter.Report blockedReport(
            String owner, String slug, String version, String reason) {
        String normalizedOwner = ClawhubSkillAdapter.normalizeOwner(owner);
        String normalizedSlug = ClawhubSkillAdapter.normalizeSlug(slug);
        String code = normalizedOwner == null || normalizedSlug == null
                ? null : ClawhubSkillAdapter.nativeCode(normalizedOwner, normalizedSlug);
        return ClawhubSkillAdapter.blocked(null, code, version, normalizedOwner, normalizedSlug, reason);
    }

    private static ResponseStatusException validateZipPart(FilePart file) {
        if (file == null || file.filename() == null
                || !file.filename().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "clawhub_zip_required");
        }
        return null;
    }

    private static long declaredSize(FilePart file) {
        return file.headers().getContentLength();
    }

    private static boolean validSha256(String expected) {
        return expected == null || expected.isBlank() || SHA256.matcher(expected.strip()).matches();
    }

    private Mono<Path> temporaryRoot(String prefix) {
        return Mono.fromCallable(() -> Files.createTempDirectory(prefix))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Long> fileSize(Path source) {
        return Mono.fromCallable(() -> Files.size(source))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static void deleteTreeQuietly(Path root) {
        if (root == null) return;
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // OS temp cleanup is the fallback; never mask the request result.
                        }
                    });
        } catch (IOException ignored) {
            // OS temp cleanup is the fallback; never mask the request result.
        }
    }
}
