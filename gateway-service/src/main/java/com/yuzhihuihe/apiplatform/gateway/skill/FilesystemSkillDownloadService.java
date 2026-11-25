package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.yuzhihuihe.apiplatform.gateway.skill.SkillCatalogService.FilesystemArchive;
import com.yuzhihuihe.apiplatform.gateway.skill.SkillDownloadService.RequestMeta;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillClientApi.DownloadCompletion;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillClientApi.DownloadReport;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillClientApi.DownloadRequest;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillClientApi.DownloadSession;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillClientApi.VersionList;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillClientApi.VersionSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * YCPS-only filesystem implementation of C3-C6.
 *
 * <p>Its state is deliberately process-local: the database backend owns durable sessions, while
 * this deployment mode reads only prebuilt {@code *.ycps.zip} artifacts. Each session freezes the
 * original bytes selected at C4.
 */
@Component
final class FilesystemSkillDownloadService {

    private static final String STATUS_STARTED = "STARTED";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private final SkillCatalogService catalog;
    private final SkillProperties properties;
    private final Clock clock;
    private final Supplier<UUID> ids;
    private final Map<UUID, SessionState> sessions = new HashMap<>();
    private final Map<IdempotencyScope, UUID> idempotency = new HashMap<>();
    private final Map<RateScope, RateWindow> rateWindows = new HashMap<>();
    private final Map<DedupeScope, Instant> counted = new HashMap<>();
    /** One canonical immutable byte array per package digest, shared by all live sessions. */
    private final Map<String, byte[]> archives = new HashMap<>();

    @Autowired
    FilesystemSkillDownloadService(SkillCatalogService catalog, SkillProperties properties) {
        this(catalog, properties, Clock.systemUTC(), UUID::randomUUID);
    }

    FilesystemSkillDownloadService(
            SkillCatalogService catalog,
            SkillProperties properties,
            Clock clock,
            Supplier<UUID> ids) {
        this.catalog = catalog;
        this.properties = properties;
        this.clock = clock;
        this.ids = ids;
    }

    Mono<DownloadSession> create(
            String code, SkillAccessIdentity identity, RequestMeta request) {
        return create(code, identity, request, null);
    }

    Mono<DownloadSession> create(
            String code,
            SkillAccessIdentity identity,
            RequestMeta request,
            DownloadRequest exactDependency) {
        UUID parentDownloadId = null;
        if (exactDependency != null) {
            synchronized (this) {
                SessionState parent = load(exactDependency.parentDownloadId(), identity);
                if (!STATUS_STARTED.equals(parent.status)
                        || !containsDependency(parent.dependencies, code, exactDependency)) {
                    return Mono.error(new ResponseStatusException(
                            HttpStatus.CONFLICT, "exact_dependency_not_in_parent"));
                }
                parentDownloadId = parent.id;
            }
        }
        UUID authorizedParent = parentDownloadId;
        return catalog.filesystemArchive(code)
                .flatMap(archive -> exactDependency == null
                        || exactDependency.version().equals(archive.version())
                                && exactDependency.revision() == archive.revision()
                                && exactDependency.kind().equals(archive.packageKind())
                                && exactDependency.sha256().equals(archive.sha256())
                        ? Mono.just(createNow(archive, identity, request, authorizedParent))
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "exact_dependency_artifact_unavailable")));
    }

    private static boolean containsDependency(
            com.fasterxml.jackson.databind.JsonNode dependencies,
            String code,
            DownloadRequest exact) {
        if (exact.parentDownloadId() == null || exact.version() == null
                || exact.revision() == null || exact.kind() == null || exact.sha256() == null
                || dependencies == null || !dependencies.isArray()) {
            return false;
        }
        for (com.fasterxml.jackson.databind.JsonNode dependency : dependencies) {
            com.fasterxml.jackson.databind.JsonNode target = dependency.path("package");
            if (code.equals(target.path("code").asText())
                    && exact.kind().equals(target.path("kind").asText())
                    && exact.version().equals(target.path("version").asText())
                    && exact.revision() == target.path("revision").asInt()
                    && exact.sha256().equals(target.path("sha256").asText())) {
                return true;
            }
        }
        return false;
    }

    Mono<VersionList> versions(
            String code, SkillAccessIdentity identity, String clientVersion) {
        return catalog.filesystemArchive(code)
                .map(archive -> {
                    SkillDownloadService.checkClientVersion(
                            archive.minClientVersion(), clientVersion);
                    return new VersionList("list", java.util.List.of(new VersionSummary(
                            archive.version(),
                            archive.revision(),
                            archive.sizeBytes(),
                            archive.md5(),
                            archive.sha256(),
                            archive.minClientVersion(),
                            null,
                            null)));
                });
    }

    Mono<Resolved> resolve(String downloadId, SkillAccessIdentity identity) {
        SessionState session;
        synchronized (this) {
            session = load(downloadId, identity);
        }
        // A directory is the filesystem backend's published state. Removing it blocks old
        // sessions immediately, while edits to an existing directory do not alter frozen bytes.
        return requirePublished(session)
                .thenReturn(session.resolved());
    }

    Mono<DownloadCompletion> complete(
            String downloadId, SkillAccessIdentity identity, DownloadReport report) {
        return Mono.defer(() -> {
            SessionState session;
            synchronized (this) {
                session = load(downloadId, identity);
            }
            return requirePublished(session)
                    .then(Mono.fromCallable(() -> completeNow(downloadId, identity, report)));
        });
    }

    Mono<Void> recordServed(UUID downloadId, long bytes, Long rangeFrom, Long rangeTo) {
        if (bytes <= 0) {
            return Mono.empty();
        }
        synchronized (this) {
            SessionState session = sessions.get(downloadId);
            if (session != null && STATUS_STARTED.equals(session.status)) {
                session.servedBytes = saturatedAdd(session.servedBytes, bytes);
                session.rangeFrom = rangeFrom;
                session.rangeTo = rangeTo;
            }
        }
        return Mono.empty();
    }

    private synchronized DownloadSession createNow(
            FilesystemArchive archive,
            SkillAccessIdentity identity,
            RequestMeta request,
            UUID parentDownloadId) {
        Instant now = clock.instant();
        prune(now);
        SkillDownloadService.checkClientVersion(
                archive.minClientVersion(), request.clientVersion());

        if (request.idempotencyKey() != null) {
            IdempotencyScope scope = new IdempotencyScope(identity.ownerKey(), request.idempotencyKey());
            UUID existingId = idempotency.get(scope);
            SessionState existing = existingId == null ? null : sessions.get(existingId);
            if (existing != null && existing.expiresAt.isAfter(now)) {
                if (!archive.name().equals(existing.skillCode)
                        || !java.util.Objects.equals(parentDownloadId, existing.parentDownloadId)) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "skill_download_idempotency_mismatch");
                }
                return existing.view();
            }
            idempotency.remove(scope);
        }

        ensureCapacity(archive);
        acquire(identity.accountKey(), request.deviceIdHash(), now);
        UUID id = ids.get();
        if (id == null || sessions.containsKey(id)) {
            throw new IllegalStateException("gateway_skill_download_id_collision");
        }
        byte[] frozen = archives.computeIfAbsent(archive.sha256(), ignored -> archive.content());
        SessionState session = new SessionState(
                id,
                identity.ownerKey(),
                identity.accountKey(),
                archive.name(),
                archive.version(),
                frozen,
                archive.sizeBytes(),
                archive.md5(),
                archive.sha256(),
                archive.revision(),
                archive.packageFormat(),
                archive.ycpsVersion(),
                archive.packageKind(),
                archive.manifestSha256(),
                archive.dependencyLockSha256(),
                archive.dependencies(),
                parentDownloadId,
                request.deviceIdHash(),
                request.idempotencyKey(),
                now,
                now.plus(properties.getDownloadSessionTtl()));
        sessions.put(id, session);
        if (request.idempotencyKey() != null) {
            idempotency.put(
                    new IdempotencyScope(identity.ownerKey(), request.idempotencyKey()), id);
        }
        return session.view();
    }

    private void ensureCapacity(FilesystemArchive archive) {
        if (sessions.size() >= properties.getFilesystemDownloadMaxSessions()) {
            throw capacityExceeded();
        }
        if (archives.containsKey(archive.sha256())) {
            return;
        }
        long retainedBytes = 0;
        for (byte[] content : archives.values()) {
            retainedBytes = saturatedAdd(retainedBytes, content.length);
        }
        long nextBytes = archive.content().length;
        long maxBytes = properties.getFilesystemDownloadMaxArchiveBytes().toBytes();
        if (nextBytes > maxBytes || retainedBytes > maxBytes - nextBytes) {
            throw capacityExceeded();
        }
    }

    private synchronized DownloadCompletion completeNow(
            String downloadId, SkillAccessIdentity identity, DownloadReport report) {
        SessionState session = load(downloadId, identity);
        DownloadReport submitted = report == null
                ? new DownloadReport("completed", null, null, null, null, null, null, null)
                : report;
        if (!STATUS_STARTED.equals(session.status)) {
            return session.completion(false);
        }
        String status = SkillDownloadService.normalizeStatus(submitted);
        long bytesReceived = submitted.bytesReceived() == null
                ? session.servedBytes
                : submitted.bytesReceived();
        if (bytesReceived < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid_download_request");
        }
        session.status = status;
        session.bytesReceived = bytesReceived;
        session.md5Verified = Boolean.TRUE.equals(submitted.md5Verified());
        session.sha256Verified = Boolean.TRUE.equals(submitted.sha256Verified());
        session.installed = Boolean.TRUE.equals(submitted.installed());
        session.completedAt = clock.instant();

        boolean incremented = false;
        if (session.parentDownloadId == null && STATUS_COMPLETED.equals(status)
                && session.md5Verified
                && session.sha256Verified && session.installed) {
            DedupeScope scope = new DedupeScope(
                    session.sha256,
                    session.accountKey,
                    normalizeDevice(session.deviceIdHash));
            Instant previous = counted.get(scope);
            Instant cutoff = session.completedAt.minus(properties.getDownloadSessionTtl());
            if (previous == null || previous.isBefore(cutoff)) {
                counted.put(scope, session.completedAt);
                incremented = true;
            }
        }
        return session.completion(incremented);
    }

    private SessionState load(String downloadId, SkillAccessIdentity identity) {
        UUID id = SkillDownloadService.parseId(downloadId);
        SessionState session = id == null ? null : sessions.get(id);
        if (session == null || !session.ownerKey.equals(identity.ownerKey())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "download_not_found");
        }
        if (!session.expiresAt.isAfter(clock.instant())) {
            throw new ResponseStatusException(
                    HttpStatus.GONE, "download_session_expired");
        }
        return session;
    }

    private void acquire(String ownerKey, String deviceIdHash, Instant now) {
        RateScope scope = new RateScope(ownerKey, normalizeDevice(deviceIdHash));
        RateWindow current = rateWindows.get(scope);
        if (current == null || !current.endsAt.isAfter(now)) {
            rateWindows.put(scope, new RateWindow(1, now.plus(properties.getDownloadWindow())));
            return;
        }
        if (current.count >= properties.getDownloadLimit()) {
            throw new FilesystemRateLimitException(current.endsAt, now);
        }
        current.count++;
    }

    private void prune(Instant now) {
        Iterator<Map.Entry<UUID, SessionState>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, SessionState> entry = iterator.next();
            if (!entry.getValue().expiresAt.isAfter(now)) {
                iterator.remove();
            }
        }
        idempotency.entrySet().removeIf(entry -> !sessions.containsKey(entry.getValue()));
        rateWindows.entrySet().removeIf(entry -> !entry.getValue().endsAt.isAfter(now));
        Instant dedupeCutoff = now.minus(properties.getDownloadSessionTtl());
        counted.entrySet().removeIf(entry -> entry.getValue().isBefore(dedupeCutoff));
        HashSet<String> liveDigests = new HashSet<>();
        sessions.values().forEach(session -> liveDigests.add(session.sha256));
        archives.keySet().retainAll(liveDigests);
    }

    private Mono<Void> requirePublished(SessionState session) {
        if (session.parentDownloadId != null) {
            SessionState parent;
            synchronized (this) {
                parent = sessions.get(session.parentDownloadId);
                if (parent == null || !parent.ownerKey.equals(session.ownerKey)
                        || !parent.expiresAt.isAfter(clock.instant())
                        || "ABORTED".equals(parent.status) || "FAILED".equals(parent.status)) {
                    return Mono.error(new ResponseStatusException(
                            HttpStatus.CONFLICT, "skill_unpublished"));
                }
            }
            return requirePublished(parent);
        }
        return catalog.filesystemPublished(session.skillCode)
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "skill_unpublished")))
                .then();
    }

    private static String normalizeDevice(String value) {
        return value == null || value.isBlank()
                ? "no-device"
                : value.strip().toLowerCase(Locale.ROOT);
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static ResponseStatusException capacityExceeded() {
        // Reuse the established client-facing availability error. Operators can raise the
        // explicit filesystem limits or finish the database cut-over; no unbounded heap growth
        // is permitted in rollback mode.
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "skill_storage_unavailable");
    }

    record Resolved(
            UUID sessionId,
            String version,
            String code,
            byte[] content,
            long sizeBytes,
            String md5,
            String sha256) {

    }

    private static final class SessionState {
        private final UUID id;
        private final String ownerKey;
        private final String accountKey;
        private final String skillCode;
        private final String version;
        private final byte[] content;
        private final long sizeBytes;
        private final String md5;
        private final String sha256;
        private final int revision;
        private final String packageFormat;
        private final String ycpsVersion;
        private final String packageKind;
        private final String manifestSha256;
        private final String dependencyLockSha256;
        private final com.fasterxml.jackson.databind.JsonNode dependencies;
        private final UUID parentDownloadId;
        private final String deviceIdHash;
        private final String idempotencyKey;
        private final Instant startedAt;
        private final Instant expiresAt;
        private String status = STATUS_STARTED;
        private long servedBytes;
        private long bytesReceived;
        private Long rangeFrom;
        private Long rangeTo;
        private boolean md5Verified;
        private boolean sha256Verified;
        private boolean installed;
        private Instant completedAt;

        private SessionState(
                UUID id,
                String ownerKey,
                String accountKey,
                String skillCode,
                String version,
                byte[] content,
                long sizeBytes,
                String md5,
                String sha256,
                int revision,
                String packageFormat,
                String ycpsVersion,
                String packageKind,
                String manifestSha256,
                String dependencyLockSha256,
                com.fasterxml.jackson.databind.JsonNode dependencies,
                UUID parentDownloadId,
                String deviceIdHash,
                String idempotencyKey,
                Instant startedAt,
                Instant expiresAt) {
            this.id = id;
            this.ownerKey = ownerKey;
            this.accountKey = accountKey;
            this.skillCode = skillCode;
            this.version = version;
            this.content = content;
            this.sizeBytes = sizeBytes;
            this.md5 = md5;
            this.sha256 = sha256;
            this.revision = revision;
            this.packageFormat = packageFormat;
            this.ycpsVersion = ycpsVersion;
            this.packageKind = packageKind;
            this.manifestSha256 = manifestSha256;
            this.dependencyLockSha256 = dependencyLockSha256;
            this.dependencies = dependencies;
            this.parentDownloadId = parentDownloadId;
            this.deviceIdHash = deviceIdHash;
            this.idempotencyKey = idempotencyKey;
            this.startedAt = startedAt;
            this.expiresAt = expiresAt;
        }

        private DownloadSession view() {
            String external = SkillDownloadService.externalId(id);
            return new DownloadSession(
                    external,
                    version,
                    sizeBytes,
                    md5,
                    sha256,
                    revision,
                    packageKind,
                    packageFormat,
                    ycpsVersion,
                    manifestSha256,
                    dependencyLockSha256,
                    dependencies,
                    "/v1/skills/downloads/" + external,
                    expiresAt.getEpochSecond());
        }

        private Resolved resolved() {
            return new Resolved(id, version, skillCode, content, sizeBytes, md5, sha256);
        }

        private DownloadCompletion completion(boolean incremented) {
            return new DownloadCompletion(
                    SkillDownloadService.externalId(id),
                    status,
                    incremented,
                    md5Verified,
                    sha256Verified,
                    installed);
        }
    }

    private record IdempotencyScope(String ownerKey, String key) {}

    private record DedupeScope(String sha256, String accountKey, String deviceIdHash) {}

    private record RateScope(String ownerKey, String deviceIdHash) {}

    private static final class RateWindow {
        private int count;
        private final Instant endsAt;

        private RateWindow(int count, Instant endsAt) {
            this.count = count;
            this.endsAt = endsAt;
        }
    }

    private static final class FilesystemRateLimitException extends ResponseStatusException {

        private final HttpHeaders headers = new HttpHeaders();

        private FilesystemRateLimitException(Instant endsAt, Instant now) {
            super(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
            headers.set(HttpHeaders.RETRY_AFTER,
                    Long.toString(Math.max(1L, endsAt.getEpochSecond() - now.getEpochSecond())));
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}
