package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** SQL boundary for the staged YCPS 1.1 reverse-import record. */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class SkillPortableImportRepository {

    private static final String SELECT = """
            SELECT import_id, object_key, artifact_sha256, artifact_md5, size_bytes, file_name, status,
                   expires_at, package_code, package_kind, package_version, package_revision,
                   plugin_manifest::text AS plugin_manifest, runtime_manifest::text AS runtime_manifest,
                   presentation::text AS presentation, manifest_sha256, dependency_lock_sha256,
                   min_client_version, file_count, extracted_bytes, scan_status,
                   scan_report::text AS scan_report, field_diff::text AS field_diff,
                   inventory::text AS inventory, warnings::text AS warnings, asset_keys::text AS asset_keys,
                   target_skill_id, target_row_version, commit_version_id, committed_revision,
                   commit_result::text AS commit_result,
                   created_by, created_at, updated_at, committed_at
            FROM gateway.skill_portable_import
            """;

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    SkillPortableImportRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    Mono<Long> insert(ImportRecord value) {
        return databaseClient.sql("""
                INSERT INTO gateway.skill_portable_import(
                    import_id, object_key, artifact_sha256, artifact_md5, size_bytes, file_name, status, expires_at,
                    package_code, package_kind, package_version, package_revision,
                    plugin_manifest, runtime_manifest, presentation, manifest_sha256,
                    dependency_lock_sha256, min_client_version, file_count, extracted_bytes,
                    scan_status, scan_report, field_diff, inventory, warnings, asset_keys,
                    target_skill_id, target_row_version, created_by, created_at, updated_at)
                VALUES (:import_id, :object_key, :artifact_sha256, :artifact_md5, :size_bytes, :file_name, :status,
                    :expires_at, :package_code, :package_kind, :package_version, :package_revision,
                    :plugin_manifest::jsonb, :runtime_manifest::jsonb, :presentation::jsonb,
                    :manifest_sha256, :dependency_lock_sha256, :min_client_version, :file_count,
                    :extracted_bytes, :scan_status, :scan_report::jsonb, :field_diff::jsonb,
                    :inventory::jsonb, :warnings::jsonb, :asset_keys::jsonb,
                    :target_skill_id, :target_row_version, :created_by, :created_at, :created_at)
                """)
                .bind("import_id", value.importId())
                .bind("object_key", value.objectKey())
                .bind("artifact_sha256", value.artifactSha256())
                .bind("artifact_md5", value.artifactMd5())
                .bind("size_bytes", value.sizeBytes())
                .bind("file_name", value.fileName())
                .bind("status", value.status())
                .bind("expires_at", value.expiresAt())
                .bind("package_code", value.packageCode())
                .bind("package_kind", value.packageKind())
                .bind("package_version", value.packageVersion())
                .bind("package_revision", value.packageRevision())
                .bind("plugin_manifest", json(value.pluginManifest()))
                .bind("runtime_manifest", json(value.runtimeManifest()))
                .bind("presentation", json(value.presentation()))
                .bind("manifest_sha256", value.manifestSha256())
                .bind("dependency_lock_sha256", value.dependencyLockSha256())
                .bind("min_client_version", nullable(value.minClientVersion()))
                .bind("file_count", value.fileCount())
                .bind("extracted_bytes", value.extractedBytes())
                .bind("scan_status", value.scanStatus())
                .bind("scan_report", json(value.scanReport()))
                .bind("field_diff", json(value.fieldDiff()))
                .bind("inventory", json(value.inventory()))
                .bind("warnings", json(value.warnings()))
                .bind("asset_keys", json(value.assetKeys()))
                .bind("target_skill_id", nullable(value.targetSkillId()))
                .bind("target_row_version", value.targetRowVersion() == null
                        ? Parameter.empty(Integer.class) : Parameter.from(value.targetRowVersion()))
                .bind("created_by", nullable(value.createdBy()))
                .bind("created_at", value.createdAt())
                .fetch().rowsUpdated();
    }

    Mono<ImportRecord> find(UUID importId) {
        return databaseClient.sql(SELECT + " WHERE import_id = :import_id")
                .bind("import_id", importId)
                .map((row, metadata) -> map(row))
                .one();
    }

    /** The commit row is locked so two browser retries converge on one database transaction. */
    Mono<ImportRecord> lock(UUID importId) {
        return databaseClient.sql(SELECT + " WHERE import_id = :import_id FOR UPDATE")
                .bind("import_id", importId)
                .map((row, metadata) -> map(row))
                .one();
    }

    Mono<Long> markCommitted(
            UUID importId,
            UUID skillId,
            UUID versionId,
            int revision,
            String resultJson,
            Instant now) {
        return databaseClient.sql("""
                UPDATE gateway.skill_portable_import
                SET status = 'COMMITTED', target_skill_id = :skill_id,
                    commit_version_id = :version_id, committed_revision = :revision,
                    commit_result = :result::jsonb, updated_at = :now, committed_at = :now
                WHERE import_id = :import_id AND status = 'SCANNED' AND expires_at > :now
                """)
                .bind("import_id", importId)
                .bind("skill_id", skillId)
                .bind("version_id", versionId)
                .bind("revision", revision)
                .bind("result", resultJson)
                .bind("now", now)
                .fetch().rowsUpdated();
    }

    Mono<Long> markExpired(UUID importId, Instant now) {
        return databaseClient.sql("""
                UPDATE gateway.skill_portable_import
                SET status = 'EXPIRED', updated_at = :now
                WHERE import_id = :import_id AND status = 'SCANNED' AND expires_at <= :now
                """)
                .bind("import_id", importId)
                .bind("now", now)
                .fetch().rowsUpdated();
    }

    Mono<Long> markFailed(UUID importId, Instant now, String reason) {
        return databaseClient.sql("""
                UPDATE gateway.skill_portable_import
                SET status = 'FAILED', scan_status = 'FAILED',
                    scan_report = COALESCE(scan_report, '{}'::jsonb) || jsonb_build_object('failure', :reason),
                    updated_at = :now
                WHERE import_id = :import_id AND status = 'SCANNED'
                """)
                .bind("import_id", importId)
                .bind("reason", reason)
                .bind("now", now)
                .fetch().rowsUpdated();
    }

    Flux<ImportRecord> expired(Instant now, int limit) {
        return databaseClient.sql(SELECT + """
                -- COMMITTED rows are retained only for the idempotent retry window.  Once the
                -- staging TTL elapses their quarantine objects and result row can be reclaimed;
                -- the promoted skill_version/package revision is independent and remains intact.
                WHERE expires_at <= :now AND status IN ('SCANNED', 'COMMITTED', 'EXPIRED', 'FAILED')
                ORDER BY expires_at ASC LIMIT :limit
                """)
                .bind("now", now)
                .bind("limit", limit)
                .map((row, metadata) -> map(row))
                .all();
    }

    Mono<Long> delete(UUID importId) {
        return databaseClient.sql("DELETE FROM gateway.skill_portable_import WHERE import_id = :import_id")
                .bind("import_id", importId)
                .fetch().rowsUpdated();
    }

    Mono<Long> insertReadyRevision(
            UUID versionId,
            UUID skillId,
            String version,
            int revision,
            String objectKey,
            String fileName,
            long sizeBytes,
            String md5,
            String sha256,
            String minClientVersion,
            int fileCount,
            long extractedBytes,
            JsonNode pluginManifest,
            JsonNode runtimeManifest,
            String manifestSha256,
            String dependencyLockSha256,
            JsonNode scanReport,
            UUID actor,
            Instant now) {
        return databaseClient.sql("""
                INSERT INTO gateway.skill_version(
                    id, skill_id, version, revision, status, object_key, file_name, size_bytes,
                    md5, sha256, file_count, extracted_bytes, min_client_version, changelog,
                    scan_status, scan_report, created_by, created_at, package_format, ycps_version,
                    package_kind, plugin_manifest, runtime_manifest, manifest_sha256,
                    dependency_lock_sha256, sealed)
                VALUES (:id, :skill_id, :version, :revision, 'READY', :object_key, :file_name,
                    :size_bytes, :md5, :sha256, :file_count, :extracted_bytes, :min_client_version,
                    'portable reverse import', 'PASSED', :scan_report::jsonb, :actor, :now,
                    'YCPS', '1.1.0', 'EXPERT', :plugin_manifest::jsonb, :runtime_manifest::jsonb,
                    :manifest_sha256, :dependency_lock_sha256, false)
                """)
                .bind("id", versionId)
                .bind("skill_id", skillId)
                .bind("version", version)
                .bind("revision", revision)
                .bind("object_key", objectKey)
                .bind("file_name", fileName)
                .bind("size_bytes", sizeBytes)
                .bind("md5", md5)
                .bind("sha256", sha256)
                .bind("file_count", fileCount)
                .bind("extracted_bytes", extractedBytes)
                .bind("min_client_version", nullable(minClientVersion))
                .bind("scan_report", json(scanReport))
                .bind("actor", nullable(actor))
                .bind("now", now)
                .bind("plugin_manifest", json(pluginManifest))
                .bind("runtime_manifest", json(runtimeManifest))
                .bind("manifest_sha256", manifestSha256)
                .bind("dependency_lock_sha256", dependencyLockSha256)
                .fetch().rowsUpdated();
    }

    private ImportRecord map(io.r2dbc.spi.Row row) {
        return new ImportRecord(
                row.get("import_id", UUID.class),
                row.get("object_key", String.class),
                row.get("artifact_sha256", String.class),
                row.get("artifact_md5", String.class),
                number(row.get("size_bytes", Long.class)),
                row.get("file_name", String.class),
                row.get("status", String.class),
                instant(row.get("expires_at", OffsetDateTime.class)),
                row.get("package_code", String.class),
                row.get("package_kind", String.class),
                row.get("package_version", String.class),
                number(row.get("package_revision", Integer.class)),
                jsonNode(row.get("plugin_manifest", String.class)),
                jsonNode(row.get("runtime_manifest", String.class)),
                jsonNode(row.get("presentation", String.class)),
                row.get("manifest_sha256", String.class),
                row.get("dependency_lock_sha256", String.class),
                row.get("min_client_version", String.class),
                number(row.get("file_count", Integer.class)),
                number(row.get("extracted_bytes", Long.class)),
                row.get("scan_status", String.class),
                jsonNode(row.get("scan_report", String.class)),
                jsonNode(row.get("field_diff", String.class)),
                jsonNode(row.get("inventory", String.class)),
                jsonNode(row.get("warnings", String.class)),
                jsonNode(row.get("asset_keys", String.class)),
                row.get("target_skill_id", UUID.class),
                row.get("target_row_version", Integer.class),
                jsonNode(row.get("commit_result", String.class)),
                row.get("commit_version_id", UUID.class),
                row.get("committed_revision", Integer.class),
                row.get("created_by", UUID.class),
                instant(row.get("created_at", OffsetDateTime.class)),
                instant(row.get("updated_at", OffsetDateTime.class)),
                instant(row.get("committed_at", OffsetDateTime.class)));
    }

    private JsonNode jsonNode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readTree(value);
        } catch (Exception error) {
            throw new IllegalStateException("skill_portable_import_json_invalid", error);
        }
    }

    private String json(Object value) {
        if (value == null) return "null";
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("skill_portable_import_json_invalid", error);
        }
    }

    private static Parameter nullable(String value) {
        return value == null || value.isBlank() ? Parameter.empty(String.class) : Parameter.from(value);
    }

    private static Parameter nullable(UUID value) {
        return value == null ? Parameter.empty(UUID.class) : Parameter.from(value);
    }

    private static long number(Long value) { return value == null ? 0L : value; }

    private static int number(Integer value) { return value == null ? 0 : value; }

    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }

    record ImportRecord(
            UUID importId,
            String objectKey,
            String artifactSha256,
            String artifactMd5,
            long sizeBytes,
            String fileName,
            String status,
            Instant expiresAt,
            String packageCode,
            String packageKind,
            String packageVersion,
            int packageRevision,
            JsonNode pluginManifest,
            JsonNode runtimeManifest,
            JsonNode presentation,
            String manifestSha256,
            String dependencyLockSha256,
            String minClientVersion,
            int fileCount,
            long extractedBytes,
            String scanStatus,
            JsonNode scanReport,
            JsonNode fieldDiff,
            JsonNode inventory,
            JsonNode warnings,
            JsonNode assetKeys,
            UUID targetSkillId,
            Integer targetRowVersion,
            JsonNode commitResult,
            UUID commitVersionId,
            Integer committedRevision,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            Instant committedAt) {}
}
