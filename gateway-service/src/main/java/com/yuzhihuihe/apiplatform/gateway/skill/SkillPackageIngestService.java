package com.yuzhihuihe.apiplatform.gateway.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import com.yuzhihuihe.apiplatform.gateway.file.StoredObjectMetadata;
import com.yuzhihuihe.apiplatform.gateway.safety.ClamAvClient;
import com.yuzhihuihe.apiplatform.gateway.safety.ClamAvScanResult;
import com.yuzhihuihe.apiplatform.gateway.safety.ContentSafetyProperties;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillVersionView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The skill-package ingest pipeline (design 6.1).
 *
 * <ol>
 *   <li>pre-checks: the skill exists and is not deleted, the version is free, the size is inside
 *       {@code gateway.skills.max-package-bytes} (R-08)</li>
 *   <li>stream the multipart body to a temp file, then hash it with MD5 + SHA-256</li>
 *   <li>YCPS structure, identity, frontmatter and runtime-manifest validation (R-09/R-10); a
 *       package rejected here is refused before anything is persisted, so a malformed upload never
 *       occupies a version number</li>
 *   <li>new versions get a {@code DRAFT}/{@code PENDING} placeholder row; the package is uploaded
 *       to {@code quarantine/skills/{skillId}/{code}/{version}/r{revision}/{uuid}.zip}</li>
 *   <li>HEAD verification of the quarantined object</li>
 *   <li>stream the temporary ZIP through ClamAV; an unavailable engine fails closed</li>
 *   <li>promote with {@code copy} into
 *       {@code clean/skills/{skillId}/{code}/{version}/r{revision}/{sha256}.zip}
 *       and HEAD the promoted object</li>
 *   <li>converge on {@code status=READY} + {@code object_key} + {@code scan_status=PASSED} and
 *       refresh {@code skill.latest_version}</li>
 *   <li>audit {@code skill.version.upload} / {@code skill.version.replace}</li>
 * </ol>
 *
 * <p>The body never enters heap: {@link FilePart#transferTo(Path)} spills to disk and
 * {@link FileObjectStore#putFile} streams that file into the object store (R-26/R-32).
 *
 * <p>A replacement (A7) writes a single conditional update after the package is safely in
 * {@code clean/}, so a failed or slow replacement keeps serving the previous package.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillPackageIngestService {

    static final String ACTION_UPLOAD = "skill.version.upload";
    static final String ACTION_REPLACE = "skill.version.replace";
    static final String ACTION_PORTABLE_REVISION = "skill.version.portable_revision";
    static final int BLOCK_BYTES = 8192;

    private final SkillRepository skills;
    private final SkillVersionRepository versions;
    private final SkillMemberRepository members;
    private final FileObjectStore objectStore;
    private final SkillProperties properties;
    private final ClamAvClient clamAvClient;
    private final boolean clamAvEnabled;
    private final TransactionalOperator transactions;
    private final SkillPackageInspector inspector = SkillPackageInspector.create();

    @Autowired
    public SkillPackageIngestService(
            SkillRepository skills,
            SkillVersionRepository versions,
            SkillMemberRepository members,
            FileObjectStore objectStore,
            SkillProperties properties,
            ContentSafetyProperties safetyProperties,
            TransactionalOperator transactions) {
        this(
                skills,
                versions,
                members,
                objectStore,
                properties,
                new ClamAvClient(
                        safetyProperties.getFiles().getClamavHost(),
                        safetyProperties.getFiles().getClamavPort(),
                        safetyProperties.getFiles().getClamavConnectTimeout(),
                        safetyProperties.getFiles().getClamavReadTimeout()),
                safetyProperties.getFiles().isClamavEnabled(),
                transactions);
    }

    SkillPackageIngestService(
            SkillRepository skills,
            SkillVersionRepository versions,
            SkillMemberRepository members,
            FileObjectStore objectStore,
            SkillProperties properties) {
        this(skills, versions, members, objectStore, properties, null, false, null);
    }

    SkillPackageIngestService(
            SkillRepository skills,
            SkillVersionRepository versions,
            SkillMemberRepository members,
            FileObjectStore objectStore,
            SkillProperties properties,
            ClamAvClient clamAvClient,
            boolean clamAvEnabled) {
        this(skills, versions, members, objectStore, properties, clamAvClient, clamAvEnabled, null);
    }

    SkillPackageIngestService(
            SkillRepository skills,
            SkillVersionRepository versions,
            SkillMemberRepository members,
            FileObjectStore objectStore,
            SkillProperties properties,
            ClamAvClient clamAvClient,
            boolean clamAvEnabled,
            TransactionalOperator transactions) {
        this.skills = skills;
        this.versions = versions;
        this.members = members;
        this.objectStore = objectStore;
        this.properties = properties;
        this.clamAvClient = clamAvClient;
        this.clamAvEnabled = clamAvEnabled;
        this.transactions = transactions;
    }

    /** A6: a brand new version of an existing skill; a duplicate version conflicts (R-13). */
    public Mono<SkillVersionView> upload(
            UUID skillId,
            String version,
            String changelog,
            String minClientVersion,
            FilePart file,
            UUID actor) {
        return skills
                .skill(skillId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "skill_not_found")))
                .map(SkillView::of)
                .flatMap(skill -> versions
                        .versionByNumber(skillId, version, skill.publishedVersion())
                        .flatMap(existing -> Mono.<SkillVersionView>error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "skill_version_conflict")))
                        // The revision counter spans every record that ever carried this code, so a
                        // code deleted and recreated does not hand out an identity a client already
                        // holds with different bytes (that install would be refused as tampering).
                        .switchIfEmpty(Mono.defer(() -> versions
                                .nextRevision(skill.code(), version)
                                .flatMap(revision -> run(
                                        skill, null, version, revision, changelog, minClientVersion,
                                        file, actor, ACTION_UPLOAD)))));
    }

    /**
     * A27: reads what a package declares without persisting anything.
     *
     * <p>This is the read-only half of {@link #run}: same temp-file spill, same digest, same size
     * gate, same {@link SkillPackageInspector} validation — but it stops before the first write. No
     * placeholder row, no quarantine object, no audit entry, and the temp file is always removed.
     * That matters because the caller is the create form: the operator is inspecting a candidate
     * archive, and a rejected or abandoned inspection must leave zero trace.
     *
     * <p>{@code expectedCode} is deliberately {@code null}. The whole point of the call is to learn
     * the code, so an identity check against a target that does not exist yet would be circular.
     * The real check still happens on upload, where the created record supplies the expectation.
     */
    public Mono<InspectedPackage> inspectOnly(FilePart file) {
        if (file == null || !safeFileName(file).endsWith(".ycps.zip")) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "ycps_package_filename_required"));
        }
        return Mono.fromCallable(() -> Files.createTempFile("skill-inspect-", ".ycps.zip"))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(temp -> transfer(file, temp)
                        .then(Mono.defer(() -> digest(temp)))
                        .flatMap(digest -> checkSize(digest)
                                .then(Mono.fromCallable(
                                                () -> inspector.inspect(temp, properties, null))
                                        .subscribeOn(Schedulers.boundedElastic()))
                                .map(report -> new InspectedPackage(report, digest)))
                .doFinally(signal -> deleteQuietly(temp)));
    }

    /**
     * Read-only Inspector entry point for workflows that already own a temporary file.
     *
     * <p>The portable reverse-import flow stores its candidate in an isolated object only after
     * this method and {@link #scanOnly(Path)} succeed. Keeping both operations here means that
     * uploads use exactly the same size, digest and Inspector gates as ordinary package ingest,
     * without creating a skill/version placeholder.</p>
     */
    Mono<InspectedPackage> inspectOnly(Path file) {
        return digest(file)
                .flatMap(value -> checkSize(value)
                        .then(Mono.fromCallable(() -> inspector.inspect(file, properties, null))
                                .subscribeOn(Schedulers.boundedElastic()))
                        .map(report -> new InspectedPackage(report, value)));
    }

    /** Runs the existing malware scanner without touching any database row or object key. */
    Mono<MalwareScan> scanOnly(Path file) {
        return scan(file);
    }

    /** Streams a multipart body to a caller-owned temporary file with the normal size gate. */
    Mono<Void> transferOnly(FilePart file, Path temp) {
        return transfer(file, temp);
    }

    /** A7: a new revision of an existing version; the new object key never overwrites the old one.
     *
     * <p>A published version may be replaced in place. Its {@code revision} still increments and the
     * new package still lands as {@code READY} before it is re-promoted, so a failed upload leaves
     * the live package serving — the only difference from replacing a draft is that the version
     * number does not change, which is what the console's "替换包" action means. Sealing is applied
     * by the publish step, so replacing clears it and the caller re-publishes.
     */
    public Mono<SkillVersionView> replace(
            UUID skillId,
            UUID versionId,
            String changelog,
            String minClientVersion,
            FilePart file,
            UUID actor) {
        return skills
                .skill(skillId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "skill_not_found")))
                .map(SkillView::of)
                .flatMap(skill -> versions
                        .version(versionId, skill.publishedVersion())
                        .filter(existing -> existing.skillId().equals(skillId))
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "skill_version_not_found")))
                        .flatMap(existing -> versions
                                .nextRevision(skill.code(), existing.version())
                                // The successor must also clear this row, in case the global counter
                                // is somehow behind the record being replaced.
                                .map(next -> Math.max(next, existing.revision() + 1))
                                .flatMap(revision -> run(
                                        skill,
                                        existing,
                                        existing.version(),
                                        revision,
                                        changelog,
                                        minClientVersion == null
                                                ? existing.minClientVersion() : minClientVersion,
                                        file,
                                        actor,
                                        ACTION_REPLACE))));
    }

    /**
     * Ingests a package assembled by the server itself.  It deliberately enters the same digest,
     * Inspector, dependency, object-store promotion and malware-scan path as a multipart upload;
     * the only difference is that the caller supplies an already materialised temporary file and
     * the method always inserts a successor revision instead of replacing the selected row.
     */
    public Mono<SkillVersionView> ingestGenerated(
            UUID skillId,
            String version,
            String minClientVersion,
            Path generatedFile,
            String fileName,
            UUID actor,
            Map<String, String> provenance) {
        if (generatedFile == null || fileName == null || !fileName.endsWith(".ycps.zip")) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "ycps_package_filename_required"));
        }
        return skills
                .skill(skillId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "skill_not_found")))
                .map(SkillView::of)
                .flatMap(skill -> versions
                        .nextRevision(skill.code(), version)
                        .flatMap(revision -> Mono.defer(() -> digest(generatedFile))
                                .flatMap(digest -> checkSize(digest)
                                        .then(Mono.fromCallable(() -> inspector.inspect(
                                                generatedFile, properties, skill.code()))
                                                .subscribeOn(Schedulers.boundedElastic()))
                                        .flatMap(report -> {
                                            SkillPackageInspector.requireUploadIdentity(
                                                    report, skill.code(), version, skill.kind());
                                            return stage(
                                                    skill,
                                                    null,
                                                    version,
                                                    revision,
                                                    null,
                                                    stricterMinimum(minClientVersion,
                                                            report.minClientVersion()),
                                                    fileName,
                                                    actor,
                                                    ACTION_PORTABLE_REVISION,
                                                    generatedFile,
                                                    digest,
                                                    report,
                                                    provenance == null ? Map.of() : provenance);
                                        }))));
    }

    private Mono<SkillVersionView> run(
            SkillView skill,
            SkillVersionView target,
            String version,
            int revision,
            String changelog,
            String minClientVersion,
            FilePart file,
            UUID actor,
            String action) {
        if (file == null || !safeFileName(file).endsWith(".ycps.zip")) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "ycps_package_filename_required"));
        }
        return Mono.fromCallable(() -> Files.createTempFile("skill-package-", ".ycps.zip"))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(temp -> transfer(file, temp)
                        .then(Mono.defer(() -> digest(temp)))
                        .flatMap(digest -> checkSize(digest)
                                .then(Mono.fromCallable(
                                                () -> inspector.inspect(temp, properties, skill.code()))
                                        .subscribeOn(Schedulers.boundedElastic()))
                                .flatMap(report -> {
                                    SkillPackageInspector.requireUploadIdentity(
                                            report, skill.code(), version, skill.kind());
                                    return stage(
                                            skill,
                                            target,
                                            version,
                                            revision,
                                            changelog,
                                            stricterMinimum(minClientVersion, report.minClientVersion()),
                                            safeFileName(file),
                                            actor,
                                            action,
                                            temp,
                                            digest,
                                            report,
                                            Map.of());
                                }))
                        .doFinally(signal -> deleteQuietly(temp)));
    }

    private Mono<Void> transfer(FilePart file, Path temp) {
        if (file == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "skill_zip_invalid"));
        }
        long declared = file.headers().getContentLength();
        if (declared > properties.getMaxPackageBytes().toBytes()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "skill_package_too_large"));
        }
        // Streams straight to disk; the multipart body is never aggregated into a byte[].
        return file.transferTo(temp);
    }

    private Mono<Void> checkSize(Digest digest) {
        if (digest.sizeBytes() == 0) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "skill_zip_invalid"));
        }
        if (digest.sizeBytes() > properties.getMaxPackageBytes().toBytes()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "skill_package_too_large"));
        }
        return Mono.empty();
    }

    private Mono<SkillVersionView> stage(
            SkillView skill,
            SkillVersionView target,
            String version,
            int revision,
            String changelog,
            String minClientVersion,
            String fileName,
            UUID actor,
            String action,
            Path temp,
            Digest digest,
            SkillPackageInspector.Report report,
            Map<String, String> provenance) {
        String quarantineKey = quarantineKey(skill.id(), skill.code(), version, revision);
        String cleanKey = cleanKey(
                skill.id(), skill.code(), version, revision, digest.sha256());
        SkillVersionRepository.PackageCommand command = new SkillVersionRepository.PackageCommand(
                skill.id(),
                version,
                revision,
                quarantineKey,
                fileName,
                digest.sizeBytes(),
                digest.md5(),
                digest.sha256(),
                minClientVersion,
                changelog,
                actor,
                Instant.now());
        Mono<UUID> placeholder = target == null
                ? versions.insertVersion(command)
                        .onErrorMap(
                                DuplicateKeyException.class,
                                error -> new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "skill_version_conflict",
                                        error))
                : Mono.just(target.id());
        return placeholder
                .flatMap(id -> objectStore
                        .putFile(quarantineKey, temp, "application/vnd.yuzhihuihe.ycps+zip")
                        // Each step must be deferred: S3FileObjectStore builds its Mono with
                        // Mono.fromFuture(client.xxx(request)), which fires the HTTP request at
                        // *assembly* time. Without defer the HEAD/COPY race the PUT and fail with
                        // NoSuchKey even though the object lands correctly.
                        .then(Mono.defer(() -> verifyHead(quarantineKey, digest.sizeBytes())))
                        .then(validateDependencies(report))
                        .flatMap(resolvedDependencies -> Mono.defer(() -> {
                            ExpertManifest expert = ycpsManifest(report);
                            return scan(temp)
                                    .flatMap(scan -> Mono.defer(
                                                    () -> objectStore.copy(quarantineKey, cleanKey))
                                    .then(Mono.defer(() -> verifyHead(cleanKey, digest.sizeBytes())))
                                    .then(Mono.defer(() -> converge(
                                            skill,
                                            target,
                                            id,
                                            command,
                                            cleanKey,
                                            report,
                                            expert,
                                            resolvedDependencies,
                                            scan,
                                            digest,
                                            actor,
                                            action,
                                            provenance))))
                                    // Best-effort: the quarantine cleanup job retries failures.
                                    .then(Mono.defer(() -> objectStore.delete(quarantineKey))
                                            .onErrorResume(error -> Mono.empty()))
                                    .then(seedCatalogCopy(skill, report, actor))
                                    .then(versions.version(id, skill.publishedVersion()));
                        }))
                        .onErrorResume(error -> objectStore.delete(quarantineKey)
                                .onErrorResume(cleanupError -> Mono.empty())
                                .then(fail(id, target, error))));
    }

    /**
     * Seeds the catalog copy the uploaded package declares, so a new package shows a title and
     * summary without anyone retyping them in the console.
     *
     * <p>Only empty columns are filled ({@code SkillRepository.seedCatalogCopy}), so an operator's
     * wording always wins and re-uploading never reverts an edit. A package that declares only its
     * default language seeds just the default columns; the other language keeps whatever the console
     * has, and the client's own fallback chain covers the gap.
     */
    private Mono<Long> seedCatalogCopy(
            SkillView skill,
            SkillPackageInspector.Report report,
            UUID actor) {
        SkillPackageInspector.CatalogCopy copy = report.catalogCopy();
        if (copy == null) {
            return Mono.just(0L);
        }
        Map<String, String> defaults = copy.defaultCopy();
        Map<String, String> english = copy.forLanguage("en");
        // The console keeps the one-line blurb in both its summary and description columns, so a
        // single package key fans out to both.
        String zhSummary = defaults.get("summary");
        String enSummary = english.get("summary");
        return skills.seedCatalogCopy(
                skill.id(),
                defaults.get("title"),
                english.get("title"),
                zhSummary,
                enSummary,
                zhSummary,
                enSummary,
                actor,
                Instant.now());
    }

    private Mono<MalwareScan> scan(Path temp) {
        if (!clamAvEnabled) {
            return Mono.just(new MalwareScan(false, "disabled"));
        }
        if (clamAvClient == null) {
            return Mono.error(scanUnavailable());
        }
        return clamAvClient.scan(temp).flatMap(result -> {
            if (result == null || result.status() == ClamAvScanResult.Status.ERROR) {
                return Mono.error(scanUnavailable());
            }
            if (result.status() == ClamAvScanResult.Status.INFECTED) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "skill_package_rejected"));
            }
            return Mono.just(new MalwareScan(true, "clean"));
        });
    }

    private Mono<Void> converge(
            SkillView skill,
            SkillVersionView target,
            UUID versionId,
            SkillVersionRepository.PackageCommand command,
            String cleanKey,
            SkillPackageInspector.Report report,
            ExpertManifest expert,
            List<SkillVersionRepository.ResolvedDependency> resolvedDependencies,
            MalwareScan scan,
            Digest digest,
            UUID actor,
            String action,
            Map<String, String> provenance) {
        Mono<Long> ready = target == null
                ? versions.markReady(
                        versionId, cleanKey, report.fileCount(), report.extractedBytes(),
                        ycps(report),
                        scanReport(digest, scan, provenance))
                : versions.replaceReady(
                        versionId, command, command.revision() - 1, cleanKey, report.fileCount(),
                        report.extractedBytes(), ycps(report), scanReport(digest, scan, provenance));
        Mono<Void> databaseChanges = skills.lockSkill(skill.id())
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "skill_not_found")))
                // A15 locks the same row. Holding it through count==0 + replaceAll prevents a
                // package ingest from overwriting a roster that an administrator just saved.
                .then(ready)
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "skill_concurrent_modification")))
                .then(Mono.defer(() -> versions.replaceDependencies(
                        versionId, resolvedDependencies)))
                // The conditional version update is the gate. A losing replacement must not
                // publish its manifest or seed its roster.
                .then(Mono.defer(() -> storeManifest(skill, expert, actor)))
                .then(Mono.defer(() -> seedMembers(skill, expert)))
                .then(Mono.defer(() -> versions.highestReadyVersion(skill.id())))
                .flatMap(latest -> latest == null
                        ? Mono.empty()
                        : skills.setLatestVersion(skill.id(), latest, actor, Instant.now()))
                .then(Mono.defer(() -> members.countBySkill(skill.id())))
                .flatMap(memberCount -> {
                    long count = memberCount == null ? 0L : memberCount;
                    // Map.of caps at ten pairs; the expert detail pushes this over that limit.
                    return skills.audit(
                            actor,
                            action,
                            skill.id(),
                            Map.ofEntries(
                                    Map.entry("code", skill.code()),
                                    Map.entry("version", command.version()),
                                    Map.entry("revision", command.revision()),
                                    Map.entry("sizeBytes", digest.sizeBytes()),
                                    Map.entry("md5", digest.md5()),
                                    Map.entry("sha256", digest.sha256()),
                                    Map.entry("fileCount", report.fileCount()),
                                    Map.entry("scanStatus", "PASSED"),
                                    Map.entry("scanIntegrated", scan.integrated()),
                                    // Design 4 step 6: the operator can tell why a roster did not
                                    // change (D3: an existing roster is never overwritten).
                                    Map.entry("manifestStored", expert.rawJson() != null),
                                    Map.entry("membersSeeded", expert.seeded()),
                                    Map.entry("memberCount", count)));
                })
                .then();
        return transactions == null
                ? databaseChanges
                : transactions.transactional(databaseChanges);
    }

    /** The validated YCPS plugin is mirrored for the existing admin detail projection. */
    private static ExpertManifest ycpsManifest(SkillPackageInspector.Report report) {
        return new ExpertManifest(
                report.kind(),
                report.pluginManifest(),
                report.pluginJson(),
                seededRoster(report.runtimeManifest()));
    }

    /** Design 4 step 4: the verbatim manifest replaces the stored one (or clears it). */
    private Mono<Long> storeManifest(SkillView skill, ExpertManifest expert, UUID actor) {
        return skills.updateManifest(skill.id(), expert.rawJson(), actor, Instant.now());
    }

    /**
     * The display roster is seeded from the immutable runtime {@code agents[]} only while
     * the skill has no members at all. Once an admin has saved a roster the package never overwrites
     * it, and {@code membersSeeded=false} is recorded in the audit detail.
     */
    private Mono<Boolean> seedMembers(SkillView skill, ExpertManifest expert) {
        List<SkillMemberRepository.Member> roster = expert.roster();
        if (roster.isEmpty()) {
            return Mono.just(false);
        }
        return members
                .countBySkill(skill.id())
                .defaultIfEmpty(0L)
                .flatMap(count -> count > 0
                        ? Mono.just(false)
                        : members
                                .replaceAll(skill.id(), roster)
                                .doOnSuccess(ignored -> expert.seeded = true)
                                .thenReturn(true));
    }

    /** Runtime agents carry stable IDs/roles; localized presentation remains catalog metadata. */
    private static List<SkillMemberRepository.Member> seededRoster(JsonNode manifest) {
        JsonNode array = manifest.get("agents");
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<SkillMemberRepository.Member> seeded = new ArrayList<>();
        int index = 0;
        for (JsonNode entry : array) {
            String key = entry.path("id").asText();
            boolean lead = "LEAD".equals(entry.path("role").asText());
            seeded.add(new SkillMemberRepository.Member(
                    key,
                    key,
                    key,
                    null,
                    null,
                    lead ? "LEAD" : "MEMBER",
                    index++));
        }
        return seeded;
    }

    private Mono<List<SkillVersionRepository.ResolvedDependency>> validateDependencies(
            SkillPackageInspector.Report report) {
        List<SkillVersionRepository.DependencyRef> dependencies = new ArrayList<>();
        for (JsonNode entry : report.runtimeManifest().path("dependencies")) {
            JsonNode target = entry.path("package");
            dependencies.add(new SkillVersionRepository.DependencyRef(
                    entry.path("key").asText(),
                    entry.path("relation").asText(),
                    entry.path("required").asBoolean(),
                    target.path("code").asText(),
                    target.path("kind").asText(),
                    target.path("version").asText(),
                    target.path("revision").asInt(),
                    target.path("sha256").asText()));
        }
        return reactor.core.publisher.Flux.fromIterable(dependencies)
                .concatMap(dependency -> versions.resolveDependency(dependency)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "ycps_dependency_unresolved"))))
                .collectList();
    }

    /** Dependency resolver shared by the read-only portable import commit path. */
    Mono<List<SkillVersionRepository.ResolvedDependency>> validateDependenciesOnly(
            SkillPackageInspector.Report report) {
        return validateDependencies(report);
    }

    private static SkillVersionRepository.YcpsMetadata ycps(
            SkillPackageInspector.Report report) {
        return new SkillVersionRepository.YcpsMetadata(
                report.ycpsVersion(),
                report.kind(),
                report.pluginJson(),
                report.runtimeJson(),
                report.manifestSha256(),
                report.dependencyLockSha256());
    }

    private static String stricterMinimum(String operator, String declared) {
        if (operator == null || operator.isBlank()) {
            return declared;
        }
        if (declared == null || declared.isBlank()) {
            return operator.strip();
        }
        return SkillVersionRepository.compareVersion(operator.strip(), declared) >= 0
                ? operator.strip() : declared;
    }

    /** A new-version placeholder becomes {@code REJECTED}; a replacement keeps the old package. */
    private Mono<SkillVersionView> fail(UUID versionId, SkillVersionView target, Throwable error) {
        if (target != null) {
            return Mono.error(error);
        }
        String reason = error instanceof ResponseStatusException status && status.getReason() != null
                ? status.getReason()
                : "skill_package_mismatch";
        String scanStatus = "skill_scan_unavailable".equals(reason) ? "FAILED" : "REJECTED";
        return versions
                .markFailed(versionId, scanStatus, reason)
                .onErrorResume(markError -> Mono.empty())
                .then(Mono.error(error));
    }

    private Mono<Void> verifyHead(String objectKey, long expectedSize) {
        return objectStore
                .head(objectKey)
                .switchIfEmpty(Mono.error(mismatch()))
                .flatMap(metadata -> matches(metadata, expectedSize) ? Mono.empty() : Mono.error(mismatch()));
    }

    private static boolean matches(StoredObjectMetadata metadata, long expectedSize) {
        return metadata.sizeBytes() == expectedSize;
    }

    private static Map<String, Object> scanReport(
            Digest digest, MalwareScan scan, Map<String, String> provenance) {
        Map<String, Object> report = new java.util.LinkedHashMap<>();
        report.put("scanner", scan.integrated() ? "clamav" : "disabled");
        report.put("scanIntegrated", scan.integrated());
        report.put("result", scan.result());
        report.put("sha256", digest.sha256());
        if (provenance != null && !provenance.isEmpty()) {
            report.putAll(provenance);
            report.put("portableArtifactSha256", digest.sha256());
        }
        return report;
    }

    /** Reads the temp file once, updating MD5 and SHA-256 in the same pass. */
    private Mono<Digest> digest(Path file) {
        return Mono.fromCallable(() -> {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long total = 0;
            byte[] buffer = new byte[BLOCK_BYTES];
            try (InputStream raw = Files.newInputStream(file);
                    DigestInputStream inner = new DigestInputStream(raw, md5);
                    DigestInputStream outer = new DigestInputStream(inner, sha)) {
                int count;
                while ((count = outer.read(buffer)) != -1) {
                    total += count;
                }
            }
            return new Digest(total, HexFormat.of().formatHex(md5.digest()),
                    HexFormat.of().formatHex(sha.digest()));
        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(NoSuchAlgorithmException.class,
                        error -> new IllegalStateException("digest_unavailable", error))
                .onErrorMap(IOException.class,
                        error -> new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE, "skill_storage_unavailable"));
    }

    static String quarantineKey(UUID skillId, String code, String version, int revision) {
        return "quarantine/skills/" + skillId + "/" + code + "/" + version + "/r"
                + revision + "/"
                + UUID.randomUUID() + ".ycps.zip";
    }

    static String cleanKey(
            UUID skillId, String code, String version, int revision, String sha256) {
        return "clean/skills/" + skillId + "/" + code + "/" + version + "/r"
                + revision + "/" + sha256 + "-" + UUID.randomUUID() + ".ycps.zip";
    }

    private static ResponseStatusException mismatch() {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "skill_package_mismatch");
    }

    private static ResponseStatusException scanUnavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "skill_scan_unavailable");
    }

    private static String safeFileName(FilePart file) {
        return SkillPackageFileNames.safeFileName(file, "skill-package.ycps.zip");
    }

    private static void deleteQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // The OS temp cleaner is the fallback; failing here must not mask the pipeline result.
        }
    }

    /** Minimal skill projection the pipeline needs; {@code kind} drives the expertType check (E-02). */
    record SkillView(UUID id, String code, String kind, String publishedVersion) {

        static SkillView of(com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillView view) {
            return new SkillView(
                    view.id(),
                    view.code(),
                    view.kind() == null ? SkillRepository.KIND_SKILL : view.kind(),
                    view.publishedVersion());
        }
    }

    /**
     * The parsed {@code plugin.json} (design 4). {@code manifest} is {@code null} when the package
     * has none; {@code roster} is the pre-validated {@code members[]} the pipeline may seed;
     * {@code seeded} records whether that seed actually wrote rows, for the audit detail.
     */
    static final class ExpertManifest {

        private final String expertType;
        private final JsonNode manifest;
        private final String rawJson;
        private final List<SkillMemberRepository.Member> roster;
        private boolean seeded;

        private ExpertManifest(
                String expertType,
                JsonNode manifest,
                String rawJson,
                List<SkillMemberRepository.Member> roster) {
            this.expertType = expertType;
            this.manifest = manifest;
            this.rawJson = rawJson;
            this.roster = List.copyOf(roster);
        }

        static ExpertManifest empty() {
            return new ExpertManifest(null, null, null, List.of());
        }

        String expertType() {
            return expertType;
        }

        JsonNode manifest() {
            return manifest;
        }

        String rawJson() {
            return rawJson;
        }

        List<SkillMemberRepository.Member> roster() {
            return roster;
        }

        boolean seeded() {
            return seeded;
        }
    }

    record Digest(long sizeBytes, String md5, String sha256) {}

    /**
     * A27: a validated package plus the digests the create form wants to display.
     *
     * <p>Carries the {@link SkillPackageInspector.Report} rather than a flattened view so the
     * projection into {@code SkillPackageInspectView} stays in one place (the service), and the
     * pipeline keeps its single source of truth for what a package declares.
     */
    public record InspectedPackage(SkillPackageInspector.Report report, Digest digest) {}

    record MalwareScan(boolean integrated, String result) {}
}
