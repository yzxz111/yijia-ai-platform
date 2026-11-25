package com.yuzhihuihe.apiplatform.gateway.apikey;

import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ApiKeyRepository {

    private final DatabaseClient database;

    public ApiKeyRepository(DatabaseClient database) {
        this.database = database;
    }

    Flux<ApiKeyViewRow> list(UUID tenantId, UUID userId) {
        return database.sql("""
                        SELECT id, name, key_prefix, allowed_models::text AS allowed_models,
                               status, last_used_at, expires_at, created_at
                        FROM gateway.api_key
                        WHERE tenant_id = :tenant_id AND user_id = :user_id
                          AND NOT managed
                        ORDER BY created_at DESC, id
                        """)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .map((row, metadata) -> new ApiKeyViewRow(
                        row.get("id", UUID.class),
                        row.get("name", String.class),
                        row.get("key_prefix", String.class),
                        row.get("allowed_models", String.class),
                        row.get("status", String.class),
                        row.get("last_used_at", Instant.class),
                        row.get("expires_at", Instant.class),
                        row.get("created_at", Instant.class)))
                .all();
    }

    Mono<PackageSnapshotRow> findActivePackageForUpdate(
            UUID tenantId, UUID userId, String usageDimension) {
        return packageQuery(tenantId, userId, usageDimension, true);
    }

    Mono<PackageSnapshotRow> findActivePackage(
            UUID tenantId, UUID userId, String usageDimension) {
        return packageQuery(tenantId, userId, usageDimension, false);
    }

    /**
     * 自 V80 起一个用户可能同时持有一个文本实例与一个视频实例，因此「生效套餐」
     * 必须由维度限定；缺了它，{@code .one()} 会在两行之间任意挑一个，
     * 而 key 的模型白名单与 max_api_keys 都会取自错误的套餐。
     *
     * <p>方案 C 起"还有额度"必须问**两个桶**：一个既卖视频秒又卖音频秒的实例，
     * 视频秒花完时它的音频秒仍然有效。只看主桶会让一把本来还能做转写的 key
     * 无法创建——客户手里有余额，而控制台告诉他"没有可用套餐"。
     * 反向（音频花完而不给视频）同样由这一个谓词挡住，因为它是"任一桶还有余量"。
     */
    private Mono<PackageSnapshotRow> packageQuery(
            UUID tenantId, UUID userId, String usageDimension, boolean forUpdate) {
        String sql = """
                SELECT id, allowed_models::text AS allowed_models, max_api_keys, expires_at
                FROM gateway.package_instance
                WHERE tenant_id = :tenant_id AND user_id = :user_id
                  AND usage_dimension = :usage_dimension
                  AND status = 'ACTIVE' AND expires_at > now()
                  AND (used_tokens + reserved_tokens < total_token_quota
                       OR (audio_seconds_quota IS NOT NULL
                           AND audio_seconds_reserved + audio_seconds_used
                               < audio_seconds_quota))
                """ + (forUpdate ? "FOR UPDATE" : "");
        return database.sql(sql)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .bind("usage_dimension", usageDimension)
                .map((row, metadata) -> new PackageSnapshotRow(
                        row.get("id", UUID.class),
                        row.get("allowed_models", String.class),
                        row.get("max_api_keys", Integer.class),
                        row.get("expires_at", Instant.class)))
                .one();
    }

    Flux<String> findEnabledModelAliases() {
        return database.sql("""
                        SELECT alias.alias
                        FROM gateway.model_alias alias
                        JOIN gateway.model model ON model.id = alias.model_id
                        WHERE model.enabled
                        ORDER BY alias.alias
                        """)
                .map((row, metadata) -> row.get("alias", String.class))
                .all();
    }

    Mono<Void> lockCreation(UUID userId) {
        return database.sql("""
                        SELECT pg_advisory_xact_lock(hashtextextended(:scope, 0)) AS locked
                        """)
                .bind("scope", "api-key-create:" + userId)
                .map((row, metadata) -> row.get("locked"))
                .one()
                .then();
    }

    Mono<Long> countLiveKeys(UUID tenantId, UUID userId) {
        return database.sql("""
                        SELECT count(*) AS key_count
                        FROM gateway.api_key
                        WHERE tenant_id = :tenant_id AND user_id = :user_id
                          AND NOT managed AND status <> 'REVOKED'
                        """)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .map((row, metadata) -> row.get("key_count", Long.class))
                .one();
    }

    Mono<Long> insertKey(
            UUID id,
            UUID tenantId,
            UUID userId,
            UUID packageId,
            String name,
            String prefix,
            String keyHash,
            String allowedModelsJson,
            Instant expiresAt,
            String usageDimension) {
        DatabaseClient.GenericExecuteSpec insert = database.sql("""
                        INSERT INTO gateway.api_key(
                            id, tenant_id, user_id, package_instance_id, name,
                            key_prefix, key_hash, allowed_models, status, expires_at,
                            usage_dimension)
                        VALUES (:id, :tenant_id, :user_id, :package_id, :name,
                                :key_prefix, :key_hash, :allowed_models::jsonb, 'ACTIVE', :expires_at,
                                :usage_dimension)
                        """)
                .bind("id", id)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .bind("name", name)
                .bind("key_prefix", prefix)
                .bind("key_hash", keyHash)
                .bind("allowed_models", allowedModelsJson)
                .bind("usage_dimension", usageDimension);
        insert = packageId == null
                ? insert.bindNull("package_id", UUID.class)
                : insert.bind("package_id", packageId);
        insert = expiresAt == null
                ? insert.bindNull("expires_at", Instant.class)
                : insert.bind("expires_at", expiresAt);
        return insert.fetch().rowsUpdated();
    }

    Mono<UpdatedKeyRow> updateStatus(
            UUID tenantId, UUID userId, UUID id, String status, boolean revoke) {
        String sql = revoke ? """
                UPDATE gateway.api_key
                SET status = 'REVOKED', revoked_at = now(), updated_at = now()
                WHERE id = :id AND tenant_id = :tenant_id AND user_id = :user_id
                  AND NOT managed AND status <> 'REVOKED'
                RETURNING id, name, key_prefix, key_hash, allowed_models::text AS allowed_models,
                          status, last_used_at, expires_at, created_at
                """ : """
                UPDATE gateway.api_key
                SET status = :status, updated_at = now()
                WHERE id = :id AND tenant_id = :tenant_id AND user_id = :user_id
                  AND NOT managed AND status <> 'REVOKED'
                RETURNING id, name, key_prefix, key_hash, allowed_models::text AS allowed_models,
                          status, last_used_at, expires_at, created_at
                """;
        DatabaseClient.GenericExecuteSpec spec = database.sql(sql)
                .bind("id", id)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId);
        if (!revoke) {
            spec = spec.bind("status", status);
        }
        return spec.map((row, metadata) -> new UpdatedKeyRow(
                        row.get("key_hash", String.class),
                        new ApiKeyViewRow(
                                row.get("id", UUID.class),
                                row.get("name", String.class),
                                row.get("key_prefix", String.class),
                                row.get("allowed_models", String.class),
                                row.get("status", String.class),
                                row.get("last_used_at", Instant.class),
                                row.get("expires_at", Instant.class),
                                row.get("created_at", Instant.class))))
                .one();
    }

    Mono<ApiKeyContextRow> findContext(String keyHash) {
        return database.sql("""
                        SELECT k.id, k.tenant_id, k.user_id, k.package_instance_id,
                               k.allowed_models::text AS allowed_models
                        FROM gateway.api_key k
                        JOIN platform.gateway_user_status u
                          ON u.tenant_id = k.tenant_id AND u.id = k.user_id
                        WHERE k.key_hash = :key_hash AND k.status = 'ACTIVE'
                          AND u.status = 'ACTIVE'
                          AND (k.expires_at IS NULL OR k.expires_at > now())
                        """)
                .bind("key_hash", keyHash)
                .map((row, metadata) -> new ApiKeyContextRow(
                        row.get("id", UUID.class),
                        row.get("tenant_id", UUID.class),
                        row.get("user_id", UUID.class),
                        row.get("package_instance_id", UUID.class),
                        row.get("allowed_models", String.class)))
                .one();
    }

    Mono<CachedContextRow> findCachedContext(UUID id, UUID userId, String keyHash) {
        return database.sql("""
                        SELECT k.tenant_id, k.package_instance_id,
                               k.allowed_models::text AS key_allowed_models
                        FROM gateway.api_key k
                        JOIN platform.gateway_user_status u
                          ON u.tenant_id = k.tenant_id AND u.id = k.user_id
                        WHERE k.id = :id AND k.user_id = :user_id
                          AND k.key_hash = :key_hash AND k.status = 'ACTIVE'
                          AND u.status = 'ACTIVE'
                          AND (k.expires_at IS NULL OR k.expires_at > now())
                        """)
                .bind("id", id)
                .bind("user_id", userId)
                .bind("key_hash", keyHash)
                .map((row, metadata) -> new CachedContextRow(
                        row.get("tenant_id", UUID.class),
                        row.get("package_instance_id", UUID.class),
                        row.get("key_allowed_models", String.class)))
                .one();
    }

    Mono<Void> markUsed(UUID apiKeyId) {
        return database.sql("UPDATE gateway.api_key SET last_used_at = now() WHERE id = :id")
                .bind("id", apiKeyId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    Mono<Void> deletePendingResetIntent(
            UUID tenantId, UUID userId, UUID keyId, UUID operationId) {
        return database.sql("""
                        DELETE FROM gateway.api_key_reset_intent
                        WHERE operation_id = :operation_id
                          AND tenant_id = :tenant_id AND user_id = :user_id
                          AND api_key_id = :api_key_id AND status = 'PENDING'
                        """)
                .bind("operation_id", operationId)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .bind("api_key_id", keyId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    Mono<Long> insertResetIntent(
            UUID operationId,
            UUID tenantId,
            UUID userId,
            UUID keyId,
            String proofFingerprint) {
        return database.sql("""
                        INSERT INTO gateway.api_key_reset_intent(
                            operation_id, tenant_id, user_id, api_key_id, proof_fingerprint)
                        VALUES (:operation_id, :tenant_id, :user_id, :api_key_id, :proof_fingerprint)
                        ON CONFLICT DO NOTHING
                        """)
                .bind("operation_id", operationId)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .bind("api_key_id", keyId)
                .bind("proof_fingerprint", proofFingerprint)
                .fetch()
                .rowsUpdated();
    }

    Mono<ResetIntentRow> findResetIntent(String fingerprint) {
        return database.sql("""
                        SELECT operation_id, tenant_id, user_id, api_key_id,
                               proof_fingerprint, status
                        FROM gateway.api_key_reset_intent
                        WHERE proof_fingerprint = :proof_fingerprint
                        """)
                .bind("proof_fingerprint", fingerprint)
                .map(ApiKeyRepository::mapResetIntent)
                .one();
    }

    Mono<ResetIntentRow> findPendingResetIntent(UUID keyId) {
        return database.sql("""
                        SELECT operation_id, tenant_id, user_id, api_key_id,
                               proof_fingerprint, status
                        FROM gateway.api_key_reset_intent
                        WHERE api_key_id = :api_key_id AND status = 'PENDING'
                        """)
                .bind("api_key_id", keyId)
                .map(ApiKeyRepository::mapResetIntent)
                .one();
    }

    private static ResetIntentRow mapResetIntent(
            io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
        return new ResetIntentRow(
                row.get("operation_id", UUID.class),
                row.get("tenant_id", UUID.class),
                row.get("user_id", UUID.class),
                row.get("api_key_id", UUID.class),
                row.get("proof_fingerprint", String.class),
                row.get("status", String.class));
    }

    Mono<String> lockPendingResetIntent(
            UUID tenantId, UUID userId, UUID keyId, UUID operationId) {
        return database.sql("""
                        SELECT status
                        FROM gateway.api_key_reset_intent
                        WHERE operation_id = :operation_id
                          AND tenant_id = :tenant_id AND user_id = :user_id
                          AND api_key_id = :api_key_id
                        FOR UPDATE
                        """)
                .bind("operation_id", operationId)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .bind("api_key_id", keyId)
                .map((row, metadata) -> row.get("status", String.class))
                .one();
    }

    Mono<LockedKeyRow> lockResettableKey(UUID tenantId, UUID userId, UUID id) {
        return database.sql("""
                        SELECT key_hash, key_prefix
                        FROM gateway.api_key
                        WHERE id = :id AND tenant_id = :tenant_id AND user_id = :user_id
                          AND NOT managed AND status <> 'REVOKED'
                        FOR UPDATE
                        """)
                .bind("id", id)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .map((row, metadata) -> new LockedKeyRow(
                        row.get("key_hash", String.class),
                        row.get("key_prefix", String.class)))
                .one();
    }

    Mono<UUID> findResettableKey(UUID tenantId, UUID userId, UUID id) {
        return database.sql("""
                        SELECT id
                        FROM gateway.api_key
                        WHERE id = :id AND tenant_id = :tenant_id AND user_id = :user_id
                          AND NOT managed AND status <> 'REVOKED'
                        """)
                .bind("id", id)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .map((row, metadata) -> row.get("id", UUID.class))
                .one();
    }

    Mono<Long> updateKeySecret(
            UUID tenantId, UUID userId, UUID id, String prefix, String hash) {
        return database.sql("""
                        UPDATE gateway.api_key
                        SET key_prefix = :key_prefix, key_hash = :key_hash, updated_at = now()
                        WHERE id = :id AND tenant_id = :tenant_id AND user_id = :user_id
                          AND NOT managed AND status <> 'REVOKED'
                        """)
                .bind("key_prefix", prefix)
                .bind("key_hash", hash)
                .bind("id", id)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> insertResetAudit(
            UUID operationId,
            UUID tenantId,
            UUID userId,
            UUID keyId,
            String detailJson) {
        return database.sql("""
                        INSERT INTO gateway.admin_operation_audit(
                            operation_id, tenant_id, actor_user_id, action, status, affected,
                            completed_at, resource_type, resource_id, detail)
                        VALUES (:operation_id, :tenant_id, :actor_user_id,
                            'API_KEY_RESET', 'COMPLETED', 1, now(),
                            'API_KEY', :resource_id, :detail::jsonb)
                        """)
                .bind("operation_id", operationId)
                .bind("tenant_id", tenantId)
                .bind("actor_user_id", userId)
                .bind("resource_id", keyId.toString())
                .bind("detail", detailJson)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> completeResetIntent(UUID operationId) {
        return database.sql("""
                        UPDATE gateway.api_key_reset_intent
                        SET status = 'COMPLETED', completed_at = now(), updated_at = now()
                        WHERE operation_id = :operation_id AND status = 'PENDING'
                        """)
                .bind("operation_id", operationId)
                .fetch()
                .rowsUpdated();
    }

    record PackageSnapshotRow(
            UUID id, String allowedModelsJson, int maxApiKeys, Instant expiresAt) {}

    record ApiKeyViewRow(
            UUID id,
            String name,
            String keyPrefix,
            String allowedModelsJson,
            String status,
            Instant lastUsedAt,
            Instant expiresAt,
            Instant createdAt) {}

    record UpdatedKeyRow(String keyHash, ApiKeyViewRow view) {}

    record ApiKeyContextRow(
            UUID id,
            UUID tenantId,
            UUID userId,
            UUID packageInstanceId,
            String allowedModelsJson) {}

    record CachedContextRow(
            UUID tenantId, UUID packageInstanceId, String allowedModelsJson) {}

    record ResetIntentRow(
            UUID operationId,
            UUID tenantId,
            UUID userId,
            UUID apiKeyId,
            String proofFingerprint,
            String status) {}

    record LockedKeyRow(String hash, String prefix) {}
}
