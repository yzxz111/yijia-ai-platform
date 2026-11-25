package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.DownloadView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.PageResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Parameterised R2DBC SQL for {@code gateway.skill_download}: session creation (with the
 * {@code Idempotency-Key} unique index), resume bookkeeping ({@code served_bytes},
 * {@code range_from/range_to}), the completion report and the 24h count de-duplication lookup.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillDownloadRepository {

    private static final String SELECT_DOWNLOAD = """
            SELECT id, skill_id, skill_version_id, version, user_id, tenant_id, api_key_id,
                   identity_key, skill_code, object_key, md5, sha256, bytes_received,
                   revision, package_kind, package_format, ycps_version, manifest_sha256,
                   dependency_lock_sha256, dependencies::text AS dependencies, parent_download_id,
                   idempotency_active,
                   device_id_hash, status, content_length, served_bytes, range_from, range_to,
                   md5_verified, sha256_verified, installed, failure_code, client_version, client_platform,
                   request_id, idempotency_key, started_at, completed_at, expires_at
            """;

    private final DatabaseClient databaseClient;

    public SkillDownloadRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    Mono<UUID> insert(SessionCommand command) {
        UUID id = UUID.randomUUID();
        DatabaseClient.GenericExecuteSpec spec = databaseClient
                .sql("""
                        INSERT INTO gateway.skill_download(
                            id, skill_id, skill_version_id, version, user_id, tenant_id, api_key_id,
                            identity_key, skill_code, object_key, md5, sha256, bytes_received,
                            revision, package_kind, package_format, ycps_version, manifest_sha256,
                            dependency_lock_sha256, dependencies, parent_download_id,
                            device_id_hash, status, content_length, served_bytes, md5_verified,
                            sha256_verified, installed, client_version, client_platform, request_id, idempotency_key,
                            idempotency_active, started_at, expires_at)
                        VALUES (:id, :skill_id, :skill_version_id, :version, :user_id, :tenant_id,
                            :api_key_id, :identity_key, :skill_code, :object_key, :md5, :sha256, 0,
                            :revision, :package_kind, :package_format, :ycps_version,
                            :manifest_sha256, :dependency_lock_sha256, :dependencies::jsonb,
                            :parent_download_id,
                            :device_id_hash, 'STARTED', :content_length, 0, false, false,
                            false, :client_version, :client_platform, :request_id, :idempotency_key,
                            true, :started_at, :expires_at)
                        ON CONFLICT DO NOTHING
                        RETURNING id
                        """)
                .bind("id", id)
                .bind("skill_id", command.skillId())
                .bind("skill_version_id", command.skillVersionId())
                .bind("version", command.version())
                .bind("identity_key", command.identityKey())
                .bind("skill_code", command.skillCode())
                .bind("object_key", command.objectKey())
                .bind("revision", command.revision())
                .bind("package_kind", command.packageKind())
                .bind("package_format", command.packageFormat())
                .bind("ycps_version", command.ycpsVersion())
                .bind("manifest_sha256", command.manifestSha256())
                .bind("dependency_lock_sha256", command.dependencyLockSha256())
                .bind("dependencies", command.dependencies().toString())
                .bind("content_length", command.contentLength())
                .bind("started_at", command.startedAt())
                .bind("expires_at", command.expiresAt());
        spec = bindNullable(spec, "user_id", command.userId(), UUID.class);
        spec = bindNullable(spec, "tenant_id", command.tenantId(), UUID.class);
        spec = bindNullable(spec, "api_key_id", command.apiKeyId(), UUID.class);
        spec = bindNullable(spec, "parent_download_id", command.parentDownloadId(), UUID.class);
        spec = bindNullable(spec, "md5", command.md5(), String.class);
        spec = bindNullable(spec, "sha256", command.sha256(), String.class);
        spec = bindNullable(spec, "device_id_hash", command.deviceIdHash(), String.class);
        spec = bindNullable(spec, "client_version", command.clientVersion(), String.class);
        spec = bindNullable(spec, "client_platform", command.clientPlatform(), String.class);
        spec = bindNullable(spec, "request_id", command.requestId(), String.class);
        spec = bindNullable(spec, "idempotency_key", command.idempotencyKey(), String.class);
        return spec.map((row, metadata) -> row.get("id", UUID.class)).one();
    }

    Mono<DownloadRow> byId(UUID id) {
        return databaseClient
                .sql(SELECT_DOWNLOAD + " FROM gateway.skill_download WHERE id = :id")
                .bind("id", id)
                .map((row, metadata) -> downloadRow(row))
                .one();
    }

    Mono<DownloadRow> byIdempotencyKey(String identityKey, String idempotencyKey) {
        return databaseClient
                .sql(SELECT_DOWNLOAD
                        + """
                        FROM gateway.skill_download
                        WHERE identity_key = :identity_key
                          AND idempotency_key = :idempotency_key
                          AND idempotency_active
                        """)
                .bind("identity_key", identityKey)
                .bind("idempotency_key", idempotencyKey)
                .map((row, metadata) -> downloadRow(row))
                .one();
    }

    Mono<Long> deactivateExpiredIdempotency(
            String identityKey, String idempotencyKey, Instant now) {
        return databaseClient.sql("""
                        UPDATE gateway.skill_download
                        SET idempotency_active = false
                        WHERE identity_key = :identity_key
                          AND idempotency_key = :idempotency_key
                          AND idempotency_active
                          AND expires_at <= :now
                        """)
                .bind("identity_key", identityKey)
                .bind("idempotency_key", idempotencyKey)
                .bind("now", now)
                .fetch().rowsUpdated();
    }

    /** Accumulates the resumed/downloaded bytes and remembers the last served byte range (6.6). */
    Mono<Long> accumulateServed(UUID id, long servedBytes, Long rangeFrom, Long rangeTo) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient
                .sql("""
                        UPDATE gateway.skill_download
                        SET served_bytes = served_bytes + :served_bytes,
                            range_from = :range_from,
                            range_to = :range_to
                        WHERE id = :id
                        """)
                .bind("id", id)
                .bind("served_bytes", servedBytes);
        spec = bindNullable(spec, "range_from", rangeFrom, Long.class);
        spec = bindNullable(spec, "range_to", rangeTo, Long.class);
        return spec.fetch().rowsUpdated();
    }

    Mono<Long> complete(
            UUID id,
            String status,
            long bytesReceived,
            boolean md5Verified,
            boolean sha256Verified,
            boolean installed,
            String failureCode,
            String clientVersion,
            String clientPlatform) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient
                .sql("""
                        UPDATE gateway.skill_download
                        SET status = :status,
                            md5_verified = :md5_verified,
                            sha256_verified = :sha256_verified,
                            bytes_received = :bytes_received,
                            installed = :installed,
                            completed_at = now(),
                            failure_code = :failure_code,
                            client_version = COALESCE(:client_version, client_version),
                            client_platform = COALESCE(:client_platform, client_platform)
                        WHERE id = :id AND status = 'STARTED'
                        """)
                .bind("id", id)
                .bind("status", status)
                .bind("bytes_received", bytesReceived)
                .bind("md5_verified", md5Verified)
                .bind("sha256_verified", sha256Verified)
                .bind("installed", installed);
        spec = bindNullable(spec, "failure_code", failureCode, String.class);
        spec = bindNullable(spec, "client_version", clientVersion, String.class);
        spec = bindNullable(spec, "client_platform", clientPlatform, String.class);
        return spec.fetch().rowsUpdated();
    }

    /**
     * Counts the sessions that already counted towards the download counter for the same
     * {@code (account, device, version)} inside the de-duplication window (design 6.5). API keys
     * belonging to one tenant/user share the account scope; a credential without account columns
     * safely falls back to its exact identity key.
     */
    Mono<Long> countCountedSessions(
            UUID skillVersionId,
            UUID tenantId,
            UUID userId,
            String identityKey,
            String deviceIdHash,
            Instant since) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient
                .sql("""
                        SELECT count(*) FROM gateway.skill_download
                        WHERE skill_version_id = :skill_version_id
                          AND status = 'COMPLETED'
                          AND md5_verified = true
                          AND sha256_verified = true
                          AND installed = true
                          AND completed_at >= :since
                          AND COALESCE(device_id_hash, '') = COALESCE(:device_id_hash, '')
                          AND (
                              (:tenant_id IS NOT NULL AND :user_id IS NOT NULL
                                  AND tenant_id = :tenant_id AND user_id = :user_id)
                              OR ((:tenant_id IS NULL OR :user_id IS NULL)
                                  AND identity_key = :identity_key)
                          )
                        """)
                .bind("skill_version_id", skillVersionId)
                .bind("since", since);
        spec = bindNullable(spec, "device_id_hash", deviceIdHash, String.class);
        spec = bindNullable(spec, "tenant_id", tenantId, UUID.class);
        spec = bindNullable(spec, "user_id", userId, UUID.class);
        spec = spec.bind("identity_key", identityKey);
        return spec.map((row, metadata) -> row.get(0, Long.class)).one();
    }

    Mono<Long> deactivateExpiredIdempotencyKeys(Instant now) {
        return databaseClient.sql("""
                        UPDATE gateway.skill_download
                        SET idempotency_active = false
                        WHERE idempotency_active AND expires_at <= :now
                        """)
                .bind("now", now)
                .fetch().rowsUpdated();
    }

    Mono<Long> deleteBefore(Instant cutoff, int limit) {
        return databaseClient.sql("""
                        WITH victims AS (
                            SELECT id
                            FROM gateway.skill_download
                            WHERE started_at < :cutoff
                            ORDER BY started_at, id
                            LIMIT :limit
                        )
                        DELETE FROM gateway.skill_download download
                        USING victims
                        WHERE download.id = victims.id
                        """)
                .bind("cutoff", cutoff)
                .bind("limit", limit)
                .fetch().rowsUpdated();
    }

    Mono<Boolean> hasUnexpiredSessionForVersion(UUID versionId, Instant now) {
        return databaseClient.sql("""
                        SELECT EXISTS(
                            SELECT 1
                            FROM gateway.skill_download
                            WHERE skill_version_id = :version_id
                              AND expires_at > :now
                        ) AS present
                        """)
                .bind("version_id", versionId)
                .bind("now", now)
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("present", Boolean.class)))
                .one()
                .defaultIfEmpty(false);
    }

    Mono<PageResult<DownloadView>> listBySkill(UUID skillId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "invalid_pagination");
        }
        long offset = (long) page * size;
        Mono<java.util.List<DownloadView>> rows = databaseClient
                .sql(SELECT_DOWNLOAD
                        + """
                        FROM gateway.skill_download
                        WHERE skill_id = :skill_id
                        ORDER BY started_at DESC, id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .bind("skill_id", skillId)
                .bind("limit", size)
                .bind("offset", offset)
                .map((row, metadata) -> downloadView(row))
                .all()
                .collectList();
        Mono<Long> total = databaseClient
                .sql("SELECT count(*) FROM gateway.skill_download WHERE skill_id = :skill_id")
                .bind("skill_id", skillId)
                .map((row, metadata) -> row.get(0, Long.class))
                .one();
        return Mono.zip(rows, total)
                .map(result -> new PageResult<>(result.getT1(), result.getT2(), page, size));
    }

    private DownloadRow downloadRow(io.r2dbc.spi.Row row) {
        return new DownloadRow(
                row.get("id", UUID.class),
                row.get("skill_id", UUID.class),
                row.get("skill_version_id", UUID.class),
                row.get("version", String.class),
                row.get("user_id", UUID.class),
                row.get("tenant_id", UUID.class),
                row.get("api_key_id", UUID.class),
                row.get("identity_key", String.class),
                row.get("skill_code", String.class),
                row.get("object_key", String.class),
                row.get("md5", String.class),
                row.get("sha256", String.class),
                row.get("revision", Integer.class) == null ? 0 : row.get("revision", Integer.class),
                row.get("package_kind", String.class),
                row.get("package_format", String.class),
                row.get("ycps_version", String.class),
                row.get("manifest_sha256", String.class),
                row.get("dependency_lock_sha256", String.class),
                jsonNode(row.get("dependencies", String.class)),
                row.get("parent_download_id", UUID.class),
                orZero(row.get("bytes_received", Long.class)),
                Boolean.TRUE.equals(row.get("idempotency_active", Boolean.class)),
                row.get("device_id_hash", String.class),
                row.get("status", String.class),
                orZero(row.get("content_length", Long.class)),
                orZero(row.get("served_bytes", Long.class)),
                row.get("range_from", Long.class),
                row.get("range_to", Long.class),
                Boolean.TRUE.equals(row.get("md5_verified", Boolean.class)),
                Boolean.TRUE.equals(row.get("sha256_verified", Boolean.class)),
                Boolean.TRUE.equals(row.get("installed", Boolean.class)),
                row.get("failure_code", String.class),
                row.get("client_version", String.class),
                row.get("client_platform", String.class),
                row.get("request_id", String.class),
                row.get("idempotency_key", String.class),
                instant(row.get("started_at", java.time.OffsetDateTime.class)),
                instant(row.get("completed_at", java.time.OffsetDateTime.class)),
                instant(row.get("expires_at", java.time.OffsetDateTime.class)));
    }

    private DownloadView downloadView(io.r2dbc.spi.Row row) {
        DownloadRow download = downloadRow(row);
        return new DownloadView(
                download.id(),
                download.skillId(),
                download.skillVersionId(),
                download.version(),
                download.userId(),
                download.apiKeyId(),
                download.deviceIdHash(),
                download.status(),
                download.contentLength(),
                download.servedBytes(),
                download.rangeFrom(),
                download.rangeTo(),
                download.md5Verified(),
                download.sha256Verified(),
                download.installed(),
                download.failureCode(),
                download.clientVersion(),
                download.clientPlatform(),
                download.startedAt(),
                download.completedAt());
    }

    private static DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, Object value, Class<?> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private static Instant instant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    record SessionCommand(
            UUID skillId,
            UUID skillVersionId,
            String version,
            UUID userId,
            UUID tenantId,
            UUID apiKeyId,
            String identityKey,
            String skillCode,
            String objectKey,
            String md5,
            String sha256,
            int revision,
            String packageKind,
            String packageFormat,
            String ycpsVersion,
            String manifestSha256,
            String dependencyLockSha256,
            JsonNode dependencies,
            UUID parentDownloadId,
            String deviceIdHash,
            long contentLength,
            String clientVersion,
            String clientPlatform,
            String requestId,
            String idempotencyKey,
            Instant startedAt,
            Instant expiresAt) {}

    record DownloadRow(
            UUID id,
            UUID skillId,
            UUID skillVersionId,
            String version,
            UUID userId,
            UUID tenantId,
            UUID apiKeyId,
            String identityKey,
            String skillCode,
            String objectKey,
            String md5,
            String sha256,
            int revision,
            String packageKind,
            String packageFormat,
            String ycpsVersion,
            String manifestSha256,
            String dependencyLockSha256,
            JsonNode dependencies,
            UUID parentDownloadId,
            long bytesReceived,
            boolean idempotencyActive,
            String deviceIdHash,
            String status,
            long contentLength,
            long servedBytes,
            Long rangeFrom,
            Long rangeTo,
            boolean md5Verified,
            boolean sha256Verified,
            boolean installed,
            String failureCode,
            String clientVersion,
            String clientPlatform,
            String requestId,
            String idempotencyKey,
            Instant startedAt,
            Instant completedAt,
            Instant expiresAt) {}

    private static JsonNode jsonNode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new ObjectMapper().readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("invalid_download_json", exception);
        }
    }
}
