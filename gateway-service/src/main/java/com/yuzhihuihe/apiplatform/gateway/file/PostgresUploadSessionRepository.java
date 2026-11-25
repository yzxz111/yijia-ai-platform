package com.yuzhihuihe.apiplatform.gateway.file;

import java.time.Duration;
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
public class PostgresUploadSessionRepository implements UploadSessionRepository {

    private static final String COLUMNS = """
            id, external_file_id, api_key_id, tenant_id, owner_user_id,
            upload_session_id, upload_idempotency_key, object_key, quarantine_object_key,
            original_filename, declared_sha256, mime_type, size_bytes, status,
            upload_expires_at, expires_at, etag, scan_claim_token, scan_claimed_at,
            scan_attempts, next_scan_at, last_error_code, created_at, updated_at
            """;

    private static final String FILE_COLUMNS = """
            file.id, file.external_file_id, file.api_key_id, file.tenant_id, file.owner_user_id,
            file.upload_session_id, file.upload_idempotency_key, file.object_key,
            file.quarantine_object_key, file.original_filename, file.declared_sha256,
            file.mime_type, file.size_bytes, file.status, file.upload_expires_at,
            file.expires_at, file.etag, file.scan_claim_token, file.scan_claimed_at,
            file.scan_attempts, file.next_scan_at, file.last_error_code,
            file.created_at, file.updated_at
            """;

    private final DatabaseClient databaseClient;

    public PostgresUploadSessionRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public Mono<UploadSession> createOrFind(UploadSession draft) {
        return databaseClient.sql("""
                        INSERT INTO gateway.file_object(
                            id, external_file_id, api_key_id, tenant_id, owner_user_id,
                            upload_session_id, upload_idempotency_key, object_key,
                            quarantine_object_key, original_filename, sha256, declared_sha256,
                            mime_type, size_bytes, status, upload_expires_at, expires_at,
                            created_at, updated_at)
                        VALUES (
                            :id, :external_file_id, :api_key_id, :tenant_id, :owner_user_id,
                            :upload_session_id, :upload_idempotency_key, :object_key,
                            :quarantine_object_key, :original_filename, :sha256, :declared_sha256,
                            :mime_type, :size_bytes, 'PENDING_UPLOAD', :upload_expires_at,
                            :expires_at, :created_at, :updated_at)
                        ON CONFLICT (api_key_id, upload_idempotency_key)
                            WHERE upload_idempotency_key IS NOT NULL
                        DO NOTHING
                        RETURNING
                        """ + COLUMNS)
                .bind("id", draft.id())
                .bind("external_file_id", draft.externalFileId())
                .bind("api_key_id", draft.apiKeyId())
                .bind("tenant_id", draft.tenantId())
                .bind("owner_user_id", draft.ownerUserId())
                .bind("upload_session_id", draft.uploadSessionId())
                .bind("upload_idempotency_key", draft.uploadIdempotencyKey())
                .bind("object_key", draft.objectKey())
                .bind("quarantine_object_key", draft.quarantineObjectKey())
                .bind("original_filename", draft.originalFilename())
                .bind("sha256", draft.declaredSha256())
                .bind("declared_sha256", draft.declaredSha256())
                .bind("mime_type", draft.mimeType())
                .bind("size_bytes", draft.sizeBytes())
                .bind("upload_expires_at", draft.uploadExpiresAt())
                .bind("expires_at", draft.expiresAt())
                .bind("created_at", draft.createdAt())
                .bind("updated_at", draft.updatedAt())
                .map((row, metadata) -> session(row))
                .one()
                .switchIfEmpty(findByIdempotencyKey(draft.apiKeyId(), draft.uploadIdempotencyKey()));
    }

    @Override
    public Mono<UploadSession> findOwned(
            String uploadSessionId, UUID apiKeyId, UUID tenantId, UUID userId) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM gateway.file_object "
                        + "WHERE upload_session_id = :upload_session_id AND api_key_id = :api_key_id "
                        + "AND tenant_id = :tenant_id AND owner_user_id = :owner_user_id "
                        + "AND status <> 'DELETED'")
                .bind("upload_session_id", uploadSessionId)
                .bind("api_key_id", apiKeyId)
                .bind("tenant_id", tenantId)
                .bind("owner_user_id", userId)
                .map((row, metadata) -> session(row))
                .one();
    }

    @Override
    public Mono<UploadSession> markUploaded(
            UUID id, UUID apiKeyId, UUID tenantId, UUID userId, String etag, Instant now) {
        return databaseClient.sql("""
                        UPDATE gateway.file_object
                        SET status = 'UPLOADED', etag = :etag, next_scan_at = :now,
                            last_error_code = NULL, updated_at = :now
                        WHERE id = :id AND status = 'PENDING_UPLOAD'
                          AND api_key_id = :api_key_id AND tenant_id = :tenant_id
                          AND owner_user_id = :owner_user_id AND upload_session_id IS NOT NULL
                          AND upload_expires_at > :now
                        RETURNING
                        """ + COLUMNS)
                .bind("id", id)
                .bind("api_key_id", apiKeyId)
                .bind("tenant_id", tenantId)
                .bind("owner_user_id", userId)
                .bind("etag", etag)
                .bind("now", now)
                .map((row, metadata) -> session(row))
                .one();
    }

    @Override
    public Mono<UploadSession> rejectPending(UUID id, String errorCode, Instant now) {
        return databaseClient.sql("""
                        UPDATE gateway.file_object
                        SET status = 'REJECTED', last_error_code = :error_code, updated_at = :now
                        WHERE id = :id AND status = 'PENDING_UPLOAD'
                          AND api_key_id IS NOT NULL AND upload_session_id IS NOT NULL
                        RETURNING
                        """ + COLUMNS)
                .bind("id", id)
                .bind("error_code", errorCode)
                .bind("now", now)
                .map((row, metadata) -> session(row))
                .one();
    }

    @Override
    public Flux<ScanClaim> claimForScan(int limit, Instant now, Duration lease) {
        return databaseClient.sql("""
                        WITH candidates AS (
                            SELECT id
                            FROM gateway.file_object
                            WHERE (status = 'UPLOADED' AND COALESCE(next_scan_at, created_at) <= :now)
                               OR (status = 'SCANNING' AND scan_claimed_at < :lease_cutoff)
                            ORDER BY COALESCE(next_scan_at, created_at), created_at, id
                            FOR UPDATE SKIP LOCKED
                            LIMIT :limit
                        )
                        UPDATE gateway.file_object file
                        SET status = 'SCANNING', scan_claim_token = gen_random_uuid(),
                            scan_claimed_at = :now, updated_at = :now
                        FROM candidates
                        WHERE file.id = candidates.id
                        RETURNING
                        """ + FILE_COLUMNS)
                .bind("now", now)
                .bind("lease_cutoff", now.minus(lease))
                .bind("limit", limit)
                .map((row, metadata) -> {
                    UploadSession session = session(row);
                    return new ScanClaim(session, session.scanClaimToken());
                })
                .all();
    }

    @Override
    public Mono<Boolean> markReady(UUID id, UUID claimToken, String cleanObjectKey, Instant now) {
        return fencedUpdate("""
                        UPDATE gateway.file_object
                        SET status = 'READY', object_key = :object_key, scan_claim_token = NULL,
                            scan_claimed_at = NULL, next_scan_at = NULL, last_error_code = NULL,
                            updated_at = :now
                        WHERE id = :id AND status = 'SCANNING' AND scan_claim_token = :claim_token
                """, id, claimToken, now, spec -> spec.bind("object_key", cleanObjectKey));
    }

    @Override
    public Mono<Boolean> markRejected(UUID id, UUID claimToken, String errorCode, Instant now) {
        return fencedUpdate("""
                        UPDATE gateway.file_object
                        SET status = 'REJECTED', scan_claim_token = NULL, scan_claimed_at = NULL,
                            next_scan_at = NULL, last_error_code = :error_code, updated_at = :now
                        WHERE id = :id AND status = 'SCANNING' AND scan_claim_token = :claim_token
                        """, id, claimToken, now, spec -> spec.bind("error_code", errorCode));
    }

    @Override
    public Mono<Boolean> retryOrFail(
            UUID id,
            UUID claimToken,
            String errorCode,
            Instant nextAttemptAt,
            int maximumAttempts,
            Instant now) {
        return databaseClient.sql("""
                        UPDATE gateway.file_object
                        SET status = CASE WHEN scan_attempts + 1 >= :maximum_attempts
                                          THEN 'FAILED' ELSE 'UPLOADED' END,
                            scan_attempts = scan_attempts + 1,
                            scan_claim_token = NULL, scan_claimed_at = NULL,
                            next_scan_at = CASE WHEN scan_attempts + 1 >= :maximum_attempts
                                                THEN NULL ELSE :next_attempt_at END,
                            last_error_code = :error_code, updated_at = :now
                        WHERE id = :id AND status = 'SCANNING' AND scan_claim_token = :claim_token
                        """)
                .bind("id", id)
                .bind("claim_token", claimToken)
                .bind("error_code", errorCode)
                .bind("next_attempt_at", nextAttemptAt)
                .bind("maximum_attempts", maximumAttempts)
                .bind("now", now)
                .fetch().rowsUpdated()
                .map(rows -> rows == 1);
    }

    @Override
    public Flux<QuarantineCleanupClaim> claimQuarantineCleanup(int limit, Instant now, Duration lease) {
        return databaseClient.sql("""
                        WITH candidates AS (
                            SELECT id
                            FROM gateway.file_object
                            WHERE quarantine_object_key IS NOT NULL
                              AND status IN ('READY', 'REJECTED', 'FAILED')
                              AND (quarantine_cleanup_claim_token IS NULL
                                   OR quarantine_cleanup_claimed_at < :lease_cutoff)
                            ORDER BY created_at, id
                            FOR UPDATE SKIP LOCKED
                            LIMIT :limit
                        )
                        UPDATE gateway.file_object file
                        SET quarantine_cleanup_claim_token = gen_random_uuid(),
                            quarantine_cleanup_claimed_at = :now, updated_at = :now
                        FROM candidates
                        WHERE file.id = candidates.id
                        RETURNING file.id, file.quarantine_object_key,
                                  file.quarantine_cleanup_claim_token
                        """)
                .bind("lease_cutoff", now.minus(lease))
                .bind("now", now)
                .bind("limit", limit)
                .map((row, metadata) -> new QuarantineCleanupClaim(
                        row.get("id", UUID.class),
                        row.get("quarantine_object_key", String.class),
                        row.get("quarantine_cleanup_claim_token", UUID.class)))
                .all();
    }

    @Override
    public Mono<Boolean> markQuarantineDeleted(UUID id, UUID cleanupClaimToken, Instant now) {
        return databaseClient.sql("""
                        UPDATE gateway.file_object
                        SET quarantine_object_key = NULL,
                            quarantine_cleanup_claim_token = NULL,
                            quarantine_cleanup_claimed_at = NULL, updated_at = :now
                        WHERE id = :id
                          AND quarantine_cleanup_claim_token = :cleanup_claim_token
                        """)
                .bind("id", id)
                .bind("cleanup_claim_token", cleanupClaimToken)
                .bind("now", now)
                .fetch().rowsUpdated()
                .map(rows -> rows == 1);
    }

    private Mono<UploadSession> findByIdempotencyKey(UUID apiKeyId, String idempotencyKey) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM gateway.file_object "
                        + "WHERE api_key_id = :api_key_id "
                        + "AND upload_idempotency_key = :upload_idempotency_key")
                .bind("api_key_id", apiKeyId)
                .bind("upload_idempotency_key", idempotencyKey)
                .map((row, metadata) -> session(row))
                .one();
    }

    private Mono<Boolean> fencedUpdate(
            String sql,
            UUID id,
            UUID claimToken,
            Instant now,
            java.util.function.UnaryOperator<DatabaseClient.GenericExecuteSpec> extraBindings) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql)
                .bind("id", id)
                .bind("claim_token", claimToken)
                .bind("now", now);
        return extraBindings.apply(spec).fetch().rowsUpdated().map(rows -> rows == 1);
    }

    private UploadSession session(Row row) {
        Long sizeBytes = row.get("size_bytes", Long.class);
        Integer scanAttempts = row.get("scan_attempts", Integer.class);
        return new UploadSession(
                row.get("id", UUID.class),
                row.get("external_file_id", String.class),
                row.get("api_key_id", UUID.class),
                row.get("tenant_id", UUID.class),
                row.get("owner_user_id", UUID.class),
                row.get("upload_session_id", String.class),
                row.get("upload_idempotency_key", String.class),
                row.get("object_key", String.class),
                row.get("quarantine_object_key", String.class),
                row.get("original_filename", String.class),
                row.get("declared_sha256", String.class),
                row.get("mime_type", String.class),
                sizeBytes == null ? 0 : sizeBytes,
                row.get("status", String.class),
                row.get("upload_expires_at", Instant.class),
                row.get("expires_at", Instant.class),
                row.get("etag", String.class),
                row.get("scan_claim_token", UUID.class),
                row.get("scan_claimed_at", Instant.class),
                scanAttempts == null ? 0 : scanAttempts,
                row.get("next_scan_at", Instant.class),
                row.get("last_error_code", String.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class));
    }
}
