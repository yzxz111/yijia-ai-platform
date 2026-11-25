package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.file.FileObjectMissingException;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillClientApi.DownloadCompletion;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillClientApi.DownloadReport;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillClientApi.DownloadRequest;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillClientApi.DownloadSession;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillClientApi.VersionList;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillClientApi.VersionSummary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Client-facing download orchestration (design 5.2 C3-C6 and 6.5/6.6/6.7): session creation with the
 * per-identity throttle and {@code Idempotency-Key} replay, session ownership/expiry/visibility
 * checks, resume bookkeeping and the idempotent completion report that drives the counters.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillDownloadService {

    static final String STATUS_STARTED = "STARTED";
    static final String STATUS_COMPLETED = "COMPLETED";
    static final String STATUS_ABORTED = "ABORTED";
    static final String STATUS_FAILED = "FAILED";

    private static final String DOWNLOAD_ID_PREFIX = "dl_";
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final List<String> VISIBLE_VERSION_STATUSES =
            List.of("READY", "PUBLISHED", "ARCHIVED");

    private final SkillRepository skills;
    private final SkillVersionRepository versions;
    private final SkillDownloadRepository downloads;
    private final SkillDownloadRateLimiter rateLimiter;
    private final SkillProperties properties;
    private final TransactionalOperator transactions;
    private final FilesystemSkillDownloadService filesystem;

    public SkillDownloadService(
            SkillRepository skills,
            SkillVersionRepository versions,
            SkillDownloadRepository downloads,
            SkillDownloadRateLimiter rateLimiter,
            SkillProperties properties,
            TransactionalOperator transactions,
            FilesystemSkillDownloadService filesystem) {
        this.skills = skills;
        this.versions = versions;
        this.downloads = downloads;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.transactions = transactions;
        this.filesystem = filesystem;
    }

    /** C4: create (or replay) a download session for a published skill. */
    public Mono<DownloadSession> create(
            String code, SkillAccessIdentity identity, RequestMeta request) {
        return create(code, identity, request, null);
    }

    public Mono<DownloadSession> create(
            String code,
            SkillAccessIdentity identity,
            RequestMeta request,
            DownloadRequest exactDependency) {
        requireIdentity(identity);
        if (code == null || code.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_skill_name"));
        }
        String normalizedCode = code.strip();
        RequestMeta normalizedRequest = normalize(request);
        if (!properties.databaseSource()) {
            return filesystem.create(
                    normalizedCode, identity, normalizedRequest, exactDependency);
        }
        if (exactDependency != null) {
            return createExactDependency(
                    normalizedCode, identity, normalizedRequest, exactDependency);
        }
        return replay(identity, normalizedCode, normalizedRequest)
                .map(Created::new)
                .switchIfEmpty(Mono.defer(() -> rateLimiter
                        .acquire(identity.accountKey(), normalizedRequest.deviceIdHash())
                        .then(skills.publishedSkill(normalizedCode))
                        .switchIfEmpty(Mono.error(notFound()))
                        .flatMap(skill -> {
                            checkClientVersion(
                                    skill.summary().minClientVersion(),
                                    normalizedRequest.clientVersion());
                            return insert(identity, normalizedRequest, skill);
                        })))
                .map(created -> session(created.row()));
    }

    private Mono<DownloadSession> createExactDependency(
            String code,
            SkillAccessIdentity identity,
            RequestMeta request,
            DownloadRequest exact) {
        if (exact.parentDownloadId() == null || exact.parentDownloadId().isBlank()
                || exact.version() == null || exact.version().isBlank()
                || exact.revision() == null || exact.revision() < 1
                || exact.kind() == null || exact.kind().isBlank()
                || exact.sha256() == null || !exact.sha256().matches("[0-9a-f]{64}")) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid_exact_dependency_request"));
        }
        // Validate the parent on every call, including an idempotency replay. Otherwise a caller
        // who knows a previous key could substitute an unrelated parent id and bypass the exact
        // dependency-closure proof.
        return load(exact.parentDownloadId(), identity)
                .filter(parent -> STATUS_STARTED.equals(parent.status()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "parent_download_not_active")))
                .flatMap(parent -> replay(identity, code, request)
                        .flatMap(row -> exact.version().equals(row.version())
                                        && exact.revision() == row.revision()
                                        && exact.kind().equals(row.packageKind())
                                        && exact.sha256().equals(row.sha256())
                                        && parent.id().equals(row.parentDownloadId())
                                ? Mono.just(new Created(row))
                                : Mono.error(new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "skill_download_idempotency_mismatch")))
                        .switchIfEmpty(rateLimiter
                                .acquire(identity.accountKey(), request.deviceIdHash())
                                .then(versions.dependencyPackage(
                                        parent.skillVersionId(), code, exact.kind(),
                                        exact.version(), exact.revision(), exact.sha256()))
                                .switchIfEmpty(Mono.error(new ResponseStatusException(
                                        HttpStatus.CONFLICT, "exact_dependency_not_in_parent")))
                                .flatMap(packageRef -> {
                                    checkClientVersion(
                                            packageRef.minClientVersion(), request.clientVersion());
                                    return insertPackage(
                                            identity, request, code, packageRef, parent.id());
                                })))
                .map(created -> session(created.row()));
    }

    /** C3: versions a client may compare against, newest first. */
    public Mono<VersionList> versions(
            String code, SkillAccessIdentity identity, String clientVersion) {
        requireIdentity(identity);
        if (!properties.databaseSource()) {
            return filesystem.versions(code == null ? "" : code.strip(), identity, clientVersion);
        }
        return skills.publishedSkill(code == null ? "" : code.strip())
                .switchIfEmpty(Mono.error(notFound()))
                .flatMapMany(skill -> {
                    checkClientVersion(skill.summary().minClientVersion(), clientVersion);
                    return versions
                            .listVersions(skill.id(), skill.summary().version())
                            .filter(version -> VISIBLE_VERSION_STATUSES.contains(version.status()))
                            .map(version -> new VersionSummary(
                                    version.version(),
                                    version.revision(),
                                    version.sizeBytes() == null ? 0L : version.sizeBytes(),
                                    version.md5(),
                                    version.sha256(),
                                    version.minClientVersion(),
                                    version.changelog(),
                                    version.createdAt()));
                })
                .collectList()
                .map(list -> new VersionList("list", list));
    }

    /** C5: resolve the byte source after ownership, expiry and visibility checks. */
    public Mono<Resolved> resolve(String downloadId, SkillAccessIdentity identity) {
        requireIdentity(identity);
        if (!properties.databaseSource()) {
            return filesystem.resolve(downloadId, identity)
                    .map(resolved -> new Resolved(
                            resolved.sessionId(),
                            resolved.version(),
                            resolved.code(),
                            null,
                            resolved.content(),
                            resolved.sizeBytes(),
                            resolved.md5(),
                            resolved.sha256()));
        }
        return load(downloadId, identity)
                .flatMap(session -> requirePublished(session)
                        .then(Mono.defer(() -> {
                            if (session.objectKey() == null || session.objectKey().isBlank()
                                    || session.sha256() == null || session.sha256().isBlank()) {
                                return Mono.error(new ResponseStatusException(
                                        HttpStatus.SERVICE_UNAVAILABLE,
                                        "skill_package_unavailable"));
                            }
                            // Visibility is current, bytes are the immutable package snapshot
                            // selected at C4. Switching the online version cannot change C5.
                            return Mono.just(new Resolved(
                                    session.id(),
                                    session.version(),
                                    session.skillCode(),
                                    session.objectKey(),
                                    null,
                                    session.contentLength(),
                                    session.md5(),
                                    session.sha256()));
                        })));
    }

    /** C6: only a completed, installed package with both digests verified counts. */
    public Mono<DownloadCompletion> complete(
            String downloadId, SkillAccessIdentity identity, DownloadReport report) {
        requireIdentity(identity);
        if (!properties.databaseSource()) {
            return filesystem.complete(downloadId, identity, report);
        }
        DownloadReport submitted = report == null
                ? new DownloadReport("completed", null, null, null, null, null, null, null)
                : report;
        return load(downloadId, identity).flatMap(session -> {
            Mono<DownloadCompletion> guardedUpdate = requirePublishedForUpdate(session)
                    .then(Mono.defer(() -> {
            String status = normalizeStatus(submitted);
            if (!STATUS_STARTED.equals(session.status())) {
                return Mono.just(new DownloadCompletion(
                        externalId(session.id()), session.status(), false,
                        session.md5Verified(), session.sha256Verified(), session.installed()));
            }
            boolean md5Verified = Boolean.TRUE.equals(submitted.md5Verified());
            boolean sha256Verified = Boolean.TRUE.equals(submitted.sha256Verified());
            boolean installed = Boolean.TRUE.equals(submitted.installed());
            long bytesReceived = submitted.bytesReceived() == null
                    ? session.servedBytes()
                    : submitted.bytesReceived();
            if (bytesReceived < 0) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "invalid_download_request"));
            }
            boolean counts = session.parentDownloadId() == null && STATUS_COMPLETED.equals(status)
                    && md5Verified && sha256Verified && installed;
            Mono<DownloadCompletion> update = downloads
                    .complete(
                            session.id(),
                            status,
                            bytesReceived,
                            md5Verified,
                            sha256Verified,
                            installed,
                            submitted.failureCode(),
                            submitted.clientVersion(),
                            submitted.clientPlatform())
                    .flatMap(updated -> {
                        if (updated != 1L) {
                            return downloads.byId(session.id())
                                    .map(current -> new DownloadCompletion(
                                            externalId(current.id()), current.status(), false,
                                            current.md5Verified(), current.sha256Verified(),
                                            current.installed()));
                        }
                        return counts
                                ? countSession(session)
                                : Mono.just(new DownloadCompletion(
                                        externalId(session.id()), status, false,
                                        md5Verified, sha256Verified, installed));
            });
            return update;
        }));
            return transactions.transactional(guardedUpdate);
        });
    }

    /** Accumulates the bytes of one streamed response for ops observability (design 6.6). */
    public Mono<Void> recordServed(UUID downloadId, long bytes, Long rangeFrom, Long rangeTo) {
        if (bytes <= 0) {
            return Mono.empty();
        }
        if (!properties.databaseSource()) {
            return filesystem.recordServed(downloadId, bytes, rangeFrom, rangeTo);
        }
        return downloads.accumulateServed(downloadId, bytes, rangeFrom, rangeTo).then();
    }

    private Mono<DownloadCompletion> countSession(
            SkillDownloadRepository.DownloadRow session) {
        Instant since = Instant.now().minus(properties.getDownloadSessionTtl());
        return versions.lockForDownloadCount(session.skillVersionId())
                .then(downloads.countCountedSessions(
                        session.skillVersionId(),
                        session.tenantId(),
                        session.userId(),
                        session.identityKey(),
                        session.deviceIdHash(),
                        since))
                .flatMap(counted -> {
                    if (counted != null && counted > 1) {
                        return Mono.just(new DownloadCompletion(
                                externalId(session.id()), STATUS_COMPLETED,
                                false, true, true, true));
                    }
                    return versions
                            .incrementDownloadCount(session.skillVersionId())
                            .filter(updated -> updated == 1L)
                            .switchIfEmpty(Mono.error(new IllegalStateException(
                                    "gateway_skill_version_counter_not_updated")))
                            .then(skills.incrementDownloadCount(session.skillId()))
                            .filter(updated -> updated == 1L)
                            .switchIfEmpty(Mono.error(new IllegalStateException(
                                    "gateway_skill_counter_not_updated")))
                            .thenReturn(new DownloadCompletion(
                                    externalId(session.id()), STATUS_COMPLETED,
                                    true, true, true, true));
                });
    }

    private Mono<SkillDownloadRepository.DownloadRow> load(
            String downloadId, SkillAccessIdentity identity) {
        requireIdentity(identity);
        UUID id = parseId(downloadId);
        if (id == null) {
            return Mono.error(notFoundDownload());
        }
        return downloads
                .byId(id)
                .switchIfEmpty(Mono.error(notFoundDownload()))
                .flatMap(session -> {
                    // R-20: ownership is bound to the exact credential identity. API keys belonging
                    // to the same account do not share sessions; JWT sessions are account scoped.
                    boolean owned = session.identityKey().equals(identity.ownerKey());
                    if (!owned) {
                        return Mono.error(notFoundDownload());
                    }
                    if (session.expiresAt() != null && session.expiresAt().isBefore(Instant.now())) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.GONE, "download_session_expired"));
                    }
                    return Mono.just(session);
                });
    }

    private Mono<Void> requirePublished(SkillDownloadRepository.DownloadRow session) {
        if (session.parentDownloadId() != null) {
            return requireAuthorizedDependency(session);
        }
        return skills.publishedSkill(session.skillCode())
                .filter(current -> current.id().equals(session.skillId()))
                .switchIfEmpty(Mono.error(unpublished()))
                .then();
    }

    /** Locks the current Skill row so an unpublish cannot race a successful C6 update/count. */
    private Mono<Void> requirePublishedForUpdate(
            SkillDownloadRepository.DownloadRow session) {
        if (session.parentDownloadId() != null) {
            return requireAuthorizedDependency(session);
        }
        return skills.lockSkill(session.skillId())
                .filter(current -> current.id().equals(session.skillId())
                        && current.code().equals(session.skillCode())
                        && "PUBLISHED".equals(current.status()))
                .switchIfEmpty(Mono.error(unpublished()))
                .then();
    }

    private Mono<Void> requireAuthorizedDependency(
            SkillDownloadRepository.DownloadRow session) {
        return downloads.byId(session.parentDownloadId())
                .filter(parent -> parent.identityKey().equals(session.identityKey())
                        && parent.expiresAt() != null
                        && parent.expiresAt().isAfter(Instant.now())
                        && !STATUS_ABORTED.equals(parent.status())
                        && !STATUS_FAILED.equals(parent.status()))
                .switchIfEmpty(Mono.error(unpublished()))
                .flatMap(parent -> versions.dependencyPackage(
                        parent.skillVersionId(), session.skillCode(), session.packageKind(),
                        session.version(), session.revision(), session.sha256()))
                .switchIfEmpty(Mono.error(unpublished()))
                .then();
    }

    private Mono<SkillDownloadRepository.DownloadRow> replay(
            SkillAccessIdentity identity, String code, RequestMeta request) {
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            return Mono.empty();
        }
        return downloads
                .deactivateExpiredIdempotency(
                        identity.ownerKey(), request.idempotencyKey(), Instant.now())
                .then(downloads.byIdempotencyKey(identity.ownerKey(), request.idempotencyKey()))
                .flatMap(existing -> {
                    if (!code.equals(existing.skillCode())) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "skill_download_idempotency_mismatch"));
                    }
                    return Mono.just(existing);
                });
    }

    private Mono<Created> insert(
            SkillAccessIdentity identity,
            RequestMeta request,
            SkillRepository.PublishedSkill skill) {
        return versions
                .onlinePackage(skill.id())
                .switchIfEmpty(Mono.error(unpublished()))
                .flatMap(packageRef -> insertPackage(
                        identity, request, skill.summary().name(), packageRef, null));
    }

    private Mono<Created> insertPackage(
            SkillAccessIdentity identity,
            RequestMeta request,
            String skillCode,
            SkillVersionRepository.PackageRef packageRef,
            UUID parentDownloadId) {
        Instant now = Instant.now();
        return downloads
                        .insert(new SkillDownloadRepository.SessionCommand(
                                packageRef.skillId(),
                                packageRef.versionId(),
                                packageRef.version(),
                                identity.userId(),
                                identity.tenantId(),
                                identity.apiKeyId(),
                                identity.ownerKey(),
                                skillCode,
                                packageRef.objectKey(),
                                packageRef.md5(),
                                packageRef.sha256(),
                                packageRef.revision(),
                                packageRef.packageKind(),
                                packageRef.packageFormat(),
                                packageRef.ycpsVersion(),
                                packageRef.manifestSha256(),
                                packageRef.dependencyLockSha256(),
                                packageRef.dependencies(),
                                parentDownloadId,
                                request.deviceIdHash(),
                                packageRef.sizeBytes(),
                                request.clientVersion(),
                                request.clientPlatform(),
                                request.requestId(),
                                request.idempotencyKey(),
                                now,
                                now.plus(properties.getDownloadSessionTtl())))
                        .flatMap(downloads::byId)
                        .map(Created::new)
                        .switchIfEmpty(Mono.defer(() -> request.idempotencyKey() == null
                                ? Mono.error(new IllegalStateException(
                                        "gateway_skill_download_not_inserted"))
                                : replay(
                                        identity,
                                        skillCode,
                                        request).map(Created::new)));
    }

    private static DownloadSession session(SkillDownloadRepository.DownloadRow row) {
        return new DownloadSession(
                externalId(row.id()),
                row.version(),
                row.contentLength(),
                row.md5(),
                row.sha256(),
                row.revision(),
                row.packageKind(),
                row.packageFormat(),
                row.ycpsVersion(),
                row.manifestSha256(),
                row.dependencyLockSha256(),
                row.dependencies(),
                "/v1/skills/downloads/" + externalId(row.id()),
                row.expiresAt() == null ? 0L : row.expiresAt().getEpochSecond());
    }

    private record Created(SkillDownloadRepository.DownloadRow row) {}

    static String externalId(UUID id) {
        return DOWNLOAD_ID_PREFIX + id.toString().replace("-", "");
    }

    static UUID parseId(String downloadId) {
        if (downloadId == null || downloadId.isBlank()) {
            return null;
        }
        String value = downloadId.strip();
        if (value.startsWith(DOWNLOAD_ID_PREFIX)) {
            value = value.substring(DOWNLOAD_ID_PREFIX.length());
        }
        try {
            if (value.length() == 32) {
                return UUID.fromString(value.replaceFirst(
                        "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                        "$1-$2-$3-$4-$5"));
            }
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static void requireIdentity(SkillAccessIdentity identity) {
        if (identity == null || identity.ownerKey() == null || identity.ownerKey().isBlank()) {
            throw SkillAccessIdentity.authenticationRequired();
        }
    }

    private static RequestMeta normalize(RequestMeta request) {
        RequestMeta value = request == null
                ? new RequestMeta(null, null, null, null, null)
                : request;
        String key = value.idempotencyKey() == null ? null : value.idempotencyKey().strip();
        if (key != null && key.isEmpty()) {
            key = null;
        }
        if (key != null && (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH
                || key.chars().anyMatch(Character::isISOControl))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_idempotency_key");
        }
        return new RequestMeta(
                value.deviceIdHash(),
                value.clientVersion(),
                value.clientPlatform(),
                value.requestId(),
                key);
    }

    static String normalizeStatus(DownloadReport report) {
        String status = report.status() == null ? "completed" : report.status().strip();
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "completed" -> STATUS_COMPLETED;
            case "aborted", "cancelled", "canceled" -> STATUS_ABORTED;
            case "failed" -> STATUS_FAILED;
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid_download_request");
        };
    }

    static void checkClientVersion(String minimum, String clientVersion) {
        if (minimum == null || minimum.isBlank()) {
            return;
        }
        // R-07: no X-Client-Version -> unknown client, refuse instead of guessing compatibility.
        if (clientVersion == null || !ClientVersion.valid(clientVersion)) {
            throw new ResponseStatusException(HttpStatus.UPGRADE_REQUIRED, "client_version_too_low");
        }
        if (ClientVersion.compare(clientVersion, minimum) < 0) {
            throw new ResponseStatusException(HttpStatus.UPGRADE_REQUIRED, "client_version_too_low");
        }
    }

    /** A missing object behind a live session is an unavailable package, not an internal error. */
    static Throwable mapStorageFailure(Throwable error) {
        if (error instanceof FileObjectMissingException) {
            return new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "skill_package_unavailable");
        }
        return error;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "skill_not_found");
    }

    private static ResponseStatusException notFoundDownload() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "download_not_found");
    }

    private static ResponseStatusException unpublished() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "skill_unpublished");
    }

    /** Request metadata captured on the session for troubleshooting (design 4.3). */
    public record RequestMeta(
            String deviceIdHash,
            String clientVersion,
            String clientPlatform,
            String requestId,
            String idempotencyKey) {}

    /** A resolved session plus everything the streaming response needs. */
    public record Resolved(
            UUID sessionId,
            String version,
            String code,
            String objectKey,
            byte[] content,
            long sizeBytes,
            String md5,
            String sha256) {}
}
