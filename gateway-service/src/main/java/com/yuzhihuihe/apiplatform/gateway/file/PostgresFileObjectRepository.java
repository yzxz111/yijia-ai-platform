package com.yuzhihuihe.apiplatform.gateway.file;

import java.time.Instant;
import java.util.UUID;

import io.r2dbc.spi.Row;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class PostgresFileObjectRepository implements FileObjectRepository {

    private static final String COLUMNS = """
            id, external_file_id, tenant_id, owner_user_id, object_key, original_filename,
            sha256, mime_type, size_bytes, status, expires_at, deleted_at, created_at,
            deletion_claim_token
            """;
    private final DatabaseClient databaseClient;

    public PostgresFileObjectRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public Mono<PackageEntitlement> packageEntitlement(
            UUID tenantId, UUID userId, UUID packageInstanceId) {
        return databaseClient.sql("""
                        SELECT package_code, max_file_size_bytes
                        FROM gateway.package_instance
                        WHERE id = :id AND tenant_id = :tenant_id AND user_id = :user_id
                          AND status = 'ACTIVE' AND expires_at > now()
                        """)
                .bind("id", packageInstanceId)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .map((row, metadata) -> new PackageEntitlement(
                        row.get("package_code", String.class),
                        row.get("max_file_size_bytes", Long.class)))
                .one();
    }

    @Override
    public Mono<Void> insert(FileObject file) {
        return databaseClient.sql("""
                        INSERT INTO gateway.file_object(
                            id, external_file_id, tenant_id, owner_user_id, object_key,
                            original_filename, sha256, mime_type, size_bytes, status, expires_at,
                            created_at, updated_at)
                        VALUES (:id, :external_id, :tenant_id, :owner_id, :object_key,
                            :filename, :sha256, :mime_type, :size_bytes, :status, :expires_at,
                            :created_at, :created_at)
                        """)
                .bind("id", file.id())
                .bind("external_id", file.externalFileId())
                .bind("tenant_id", file.tenantId())
                .bind("owner_id", file.ownerUserId())
                .bind("object_key", file.objectKey())
                .bind("filename", file.originalFilename())
                .bind("sha256", file.sha256())
                .bind("mime_type", file.mimeType())
                .bind("size_bytes", file.sizeBytes())
                .bind("status", file.status())
                .bind("expires_at", file.expiresAt())
                .bind("created_at", file.createdAt())
                .fetch().rowsUpdated().then();
    }

    @Override
    public Mono<Void> markReady(UUID id) {
        return status(id, "READY");
    }

    @Override
    public Mono<Void> markFailed(UUID id) {
        return status(id, "FAILED");
    }

    @Override
    public Mono<FileObject> find(String externalFileId, UUID tenantId, UUID ownerUserId) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM gateway.file_object "
                        + "WHERE external_file_id = :external_id AND tenant_id = :tenant_id "
                        + "AND owner_user_id = :owner_id AND status <> 'DELETED'")
                .bind("external_id", externalFileId)
                .bind("tenant_id", tenantId)
                .bind("owner_id", ownerUserId)
                .map((row, metadata) -> file(row))
                .one();
    }

    @Override
    public Mono<FileObject> findReadyForRead(String externalFileId) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM gateway.file_object "
                        + "WHERE external_file_id = :external_id AND status = 'READY' "
                        + "AND deleted_at IS NULL AND expires_at > now() "
                        + "AND object_key LIKE 'clean/%'")
                .bind("external_id", externalFileId)
                .map((row, metadata) -> file(row))
                .one();
    }

    @Override
    public Flux<FileObject> list(UUID tenantId, UUID ownerUserId) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM gateway.file_object "
                        + "WHERE tenant_id = :tenant_id AND owner_user_id = :owner_id "
                        + "AND status <> 'DELETED' ORDER BY created_at DESC, id DESC")
                .bind("tenant_id", tenantId)
                .bind("owner_id", ownerUserId)
                .map((row, metadata) -> file(row))
                .all();
    }

    @Override
    public Mono<FileObject> claimDelete(String externalFileId, UUID tenantId, UUID ownerUserId) {
        UUID claimToken = UUID.randomUUID();
        return databaseClient.sql("""
                        UPDATE gateway.file_object
                        SET status = 'DELETING', deletion_claim_token = :claim_token,
                            updated_at = now()
                        WHERE external_file_id = :external_id
                          AND tenant_id = :tenant_id AND owner_user_id = :owner_id
                          AND status IN ('READY', 'FAILED')
                        RETURNING
                        """ + COLUMNS)
                .bind("external_id", externalFileId)
                .bind("tenant_id", tenantId)
                .bind("owner_id", ownerUserId)
                .bind("claim_token", claimToken)
                .map((row, metadata) -> file(row))
                .one();
    }

    @Override
    public Flux<FileObject> claimExpired(int limit) {
        return databaseClient.sql("""
                        WITH candidates AS (
                            SELECT id
                            FROM gateway.file_object
                            WHERE (status = 'PENDING_UPLOAD'
                                   AND COALESCE(upload_expires_at, expires_at) <= now())
                               OR (status IN ('UPLOADED', 'REJECTED', 'FAILED', 'READY')
                                   AND expires_at <= now())
                               OR (status = 'SCANNING' AND expires_at <= now()
                                   AND (scan_claimed_at IS NULL
                                        OR scan_claimed_at < now() - interval '5 minutes'))
                               OR (status = 'DELETING'
                                   AND updated_at < now() - interval '5 minutes')
                            ORDER BY expires_at, id
                            FOR UPDATE SKIP LOCKED
                            LIMIT :limit
                        )
                        UPDATE gateway.file_object file
                        SET status = 'DELETING', deletion_claim_token = gen_random_uuid(),
                            updated_at = now()
                        FROM candidates
                        WHERE file.id = candidates.id
                        RETURNING file.""" + COLUMNS.replace(", ", ", file."))
                .bind("limit", limit)
                .map((row, metadata) -> file(row))
                .all();
    }

    @Override
    public Mono<Boolean> existsLiveObjectKey(String objectKey) {
        return databaseClient.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM gateway.file_object
                            WHERE object_key = :object_key AND status <> 'DELETED'
                            UNION ALL
                            SELECT 1 FROM gateway.file_object
                            WHERE quarantine_object_key = :object_key
                              AND status <> 'DELETED'
                            UNION ALL
                            SELECT 1 FROM gateway.skill
                            WHERE icon_object_key = :object_key
                            UNION ALL
                            SELECT 1 FROM gateway.skill
                            WHERE illustration_object_key = :object_key
                            UNION ALL
                            SELECT 1 FROM gateway.skill
                            WHERE example_image_object_key = :object_key
                            UNION ALL
                            SELECT 1
                            FROM gateway.skill_version version
                            JOIN gateway.skill skill ON skill.id = version.skill_id
                            WHERE version.object_key = :object_key
                              AND (version.deleted_at IS NULL OR skill.deleted_at IS NOT NULL)
                            UNION ALL
                            SELECT 1
                            FROM gateway.skill_version version
                            WHERE version.quarantine_key = :object_key
                              AND version.deleted_at IS NULL
                            UNION ALL
                            SELECT 1 FROM gateway.skill_download
                            WHERE object_key = :object_key AND expires_at > now()
                            UNION ALL
                            SELECT 1 FROM gateway.native_clawhub_source
                            WHERE object_key = :object_key AND status <> 'DELETED'
                            UNION ALL
                            SELECT 1 FROM gateway.native_clawhub_release
                            WHERE object_key = :object_key
                            UNION ALL
                            SELECT 1 FROM gateway.skill_category
                            WHERE image_object_key = :object_key
                            -- 原生 ClawHub 归档：草稿在 quarantine/ 前缀，发布后复制到 clean/ 前缀
                            -- 并只登记在 native_clawhub_release.object_key 上。少了这两条，
                            -- OrphanCleanObjectCleanupJob 会把已发布的包当成孤儿删掉（约 1 小时后），
                            -- 而数据库仍显示 PUBLISHED —— 下载随即 500。
                            UNION ALL
                            SELECT 1 FROM gateway.native_clawhub_source
                            WHERE object_key = :object_key AND status <> 'DELETED'
                            UNION ALL
                            SELECT 1 FROM gateway.native_clawhub_release
                            WHERE object_key = :object_key
                        ) AS present
                        """)
                .bind("object_key", objectKey)
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("present", Boolean.class)))
                .one()
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<Void> markDeleted(UUID id, UUID deletionClaimToken, Instant deletedAt) {
        return databaseClient.sql("""
                        UPDATE gateway.file_object
                        SET status = 'DELETED', deletion_claim_token = NULL,
                            deleted_at = :deleted_at, updated_at = :deleted_at
                        WHERE id = :id AND status = 'DELETING'
                          AND deletion_claim_token = :claim_token
                        """)
                .bind("id", id)
                .bind("claim_token", deletionClaimToken)
                .bind("deleted_at", deletedAt)
                .fetch().rowsUpdated().then();
    }

    @Override
    public Mono<Void> restoreReady(UUID id, UUID deletionClaimToken) {
        return databaseClient.sql("""
                        UPDATE gateway.file_object
                        SET status = 'READY', deletion_claim_token = NULL, updated_at = now()
                        WHERE id = :id AND status = 'DELETING'
                          AND deletion_claim_token = :claim_token
                        """)
                .bind("id", id)
                .bind("claim_token", deletionClaimToken)
                .fetch().rowsUpdated().then();
    }

    private Mono<Void> status(UUID id, String status) {
        return databaseClient.sql("""
                        UPDATE gateway.file_object SET status = :status, updated_at = now()
                        WHERE id = :id
                        """)
                .bind("id", id)
                .bind("status", status)
                .fetch().rowsUpdated().then();
    }

    private FileObject file(Row row) {
        Long size = row.get("size_bytes", Long.class);
        return new FileObject(
                row.get("id", UUID.class),
                row.get("external_file_id", String.class),
                row.get("tenant_id", UUID.class),
                row.get("owner_user_id", UUID.class),
                row.get("object_key", String.class),
                row.get("original_filename", String.class),
                row.get("sha256", String.class),
                row.get("mime_type", String.class),
                size == null ? 0 : size,
                row.get("status", String.class),
                row.get("expires_at", Instant.class),
                row.get("deleted_at", Instant.class),
                row.get("created_at", Instant.class),
                row.get("deletion_claim_token", UUID.class));
    }
}
