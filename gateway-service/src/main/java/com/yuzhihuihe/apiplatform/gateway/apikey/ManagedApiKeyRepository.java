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
public class ManagedApiKeyRepository {

    private final DatabaseClient database;

    public ManagedApiKeyRepository(DatabaseClient database) {
        this.database = database;
    }

    Mono<Void> lockUser(UUID userId) {
        return database.sql("""
                        SELECT pg_advisory_xact_lock(hashtextextended(:scope, 0)) AS locked
                        """)
                .bind("scope", "managed-api-key:" + userId)
                .map((row, metadata) -> row.get("locked"))
                .one()
                .then();
    }

    /**
     * 该用户当前所有维度的生效实例，按维度再按激活时间排序。
     *
     * <p>返回集合而不是单行：自从唯一性按维度收窄（V80），一个用户可能同时持有
     * 一个文本实例与一个视频实例。调用方必须显式选维度，而不是让
     * {@code LIMIT 1} 替它决定 —— 那正是「买了视频套餐之后桌面端拿到视频 key，
     * 文本调用全废」的成因。
     */
    Flux<ActivePackageRow> findActivePackagesForUpdate(UUID tenantId, UUID userId) {
        return database.sql("""
                        SELECT package_instance.id, package_instance.package_code,
                               package_instance.expires_at,
                               package_instance.usage_dimension,
                               package_instance.allowed_models::text AS allowed_models,
                               EXISTS (
                                   SELECT 1
                                   FROM gateway.package_refund_hold AS refund_hold
                                   WHERE refund_hold.package_instance_id = package_instance.id
                                     AND refund_hold.status IN ('WAITING', 'READY')
                               ) AS refund_in_progress
                        FROM gateway.package_instance AS package_instance
                        WHERE package_instance.tenant_id = :tenant_id
                          AND package_instance.user_id = :user_id
                          AND package_instance.status = 'ACTIVE'
                          AND package_instance.expires_at > now()
                          -- 方案 C：内部客户端同样要问两个桶。只看主桶会让一个
                          -- 视频秒已经花完、而音频秒还有余额的实例在这里消失，
                          -- 于是 AudioGenerationService 拿不到托管 key 可用的套餐。
                          AND (package_instance.used_tokens < package_instance.total_token_quota
                               OR (package_instance.audio_seconds_quota IS NOT NULL
                                   AND package_instance.audio_seconds_used
                                       < package_instance.audio_seconds_quota))
                        ORDER BY package_instance.usage_dimension,
                                 package_instance.activated_at DESC NULLS LAST,
                                 package_instance.created_at DESC,
                                 package_instance.id DESC
                        FOR UPDATE OF package_instance
                        """)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .map((row, metadata) -> new ActivePackageRow(
                        row.get("id", UUID.class),
                        row.get("package_code", String.class),
                        row.get("expires_at", Instant.class),
                        row.get("allowed_models", String.class),
                        Boolean.TRUE.equals(row.get("refund_in_progress", Boolean.class)),
                        row.get("usage_dimension", String.class)))
                .all();
    }

    /**
     * 客户端模型目录（{@code /v1/models} 等）会露出的别名：{@code gateway.model} 里
     * {@code client_visible = true} 且 {@code enabled = true} 的行，连同能力串。
     *
     * <p>Bootstrap 回给桌面端的 {@code allowedModels} 用这份清单做交集，于是客户端
     * 的「套餐模型」下拉与目录接口一致：套餐里那些被 {@code client_visible = false}
     * 收起的别名（text-* / vision-* / file-basic / multimodal-basic、以及 V110 起
     * 的档位视频别名）不再以下拉项出现。能力串供调用方按套餐维度过滤——视频入口
     * 不该出现音频别名。这与「能否调用」无关：鉴权与路由仍认套餐的完整
     * {@code allowed_models}，因此第三方兼容客户端按名字调用照旧。
     */
    Flux<ClientVisibleModelRow> findClientVisibleModels() {
        return database.sql("""
                        SELECT alias.alias, model.capabilities::text AS capabilities
                        FROM gateway.model_alias alias
                        JOIN gateway.model model ON model.id = alias.model_id
                        WHERE model.client_visible AND model.enabled
                        """)
                .map((row, metadata) -> new ClientVisibleModelRow(
                        row.get("alias", String.class),
                        row.get("capabilities", String.class)))
                .all();
    }

    record ClientVisibleModelRow(String alias, String capabilitiesJson) { }

    Mono<ManagedKeyRow> findManagedKey(UUID tenantId, UUID userId, UUID packageId) {
        return database.sql("""
                        SELECT id, key_prefix, secret_ciphertext
                        FROM gateway.api_key
                        WHERE tenant_id = :tenant_id AND user_id = :user_id
                          AND package_instance_id = :package_id
                          AND managed AND status = 'ACTIVE'
                          AND expires_at > now()
                        """)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .bind("package_id", packageId)
                .map((row, metadata) -> new ManagedKeyRow(
                        row.get("id", UUID.class),
                        row.get("key_prefix", String.class),
                        row.get("secret_ciphertext", byte[].class)))
                .one();
    }

    Mono<UUID> findManagedKeyUser(UUID tenantId, UUID keyId) {
        return database.sql("""
                        SELECT user_id
                        FROM gateway.api_key
                        WHERE id = :id AND tenant_id = :tenant_id AND managed
                        """)
                .bind("id", keyId)
                .bind("tenant_id", tenantId)
                .map((row, metadata) -> row.get("user_id", UUID.class))
                .one();
    }

    Mono<Long> insertRotationIntent(
            UUID operationId,
            UUID tenantId,
            UUID actorUserId,
            UUID targetUserId,
            UUID oldKeyId) {
        return database.sql("""
                        INSERT INTO gateway.managed_api_key_rotation(
                            operation_id, tenant_id, actor_user_id,
                            target_user_id, old_api_key_id)
                        VALUES (
                            :operation_id, :tenant_id, :actor_user_id,
                            :target_user_id, :old_api_key_id)
                        ON CONFLICT (operation_id) DO NOTHING
                        """)
                .bind("operation_id", operationId)
                .bind("tenant_id", tenantId)
                .bind("actor_user_id", actorUserId)
                .bind("target_user_id", targetUserId)
                .bind("old_api_key_id", oldKeyId)
                .fetch()
                .rowsUpdated();
    }

    Mono<RotationRow> lockRotation(UUID operationId) {
        return database.sql("""
                        SELECT rotation.operation_id, rotation.tenant_id,
                               rotation.actor_user_id, rotation.target_user_id,
                               rotation.old_api_key_id,
                               rotation.new_api_key_id, rotation.status,
                               replacement.key_prefix AS new_key_prefix
                        FROM gateway.managed_api_key_rotation AS rotation
                        LEFT JOIN gateway.api_key AS replacement
                          ON replacement.id = rotation.new_api_key_id
                        WHERE rotation.operation_id = :operation_id
                        FOR UPDATE OF rotation
                        """)
                .bind("operation_id", operationId)
                .map((row, metadata) -> new RotationRow(
                        row.get("operation_id", UUID.class),
                        row.get("tenant_id", UUID.class),
                        row.get("actor_user_id", UUID.class),
                        row.get("target_user_id", UUID.class),
                        row.get("old_api_key_id", UUID.class),
                        row.get("new_api_key_id", UUID.class),
                        row.get("status", String.class),
                        row.get("new_key_prefix", String.class)))
                .one();
    }

    Mono<ManagedKeyForRotationRow> lockManagedKeyForRotation(
            UUID tenantId, UUID userId, UUID keyId) {
        return database.sql("""
                        SELECT api_key.id, api_key.tenant_id, api_key.user_id,
                               api_key.package_instance_id, api_key.name,
                               api_key.key_prefix, api_key.key_hash,
                               api_key.allowed_models::text AS allowed_models,
                               api_key.expires_at,
                               api_key.usage_dimension,
                               -- 替代 Key 的授权名单取**套餐当前**的那一份，而不是
                               -- 旧 Key 的快照：轮换与 Bootstrap 是同一个语义
                               -- （托管 Key 没有子集），照抄旧快照会把一份过期的
                               -- 名单再钉一次。
                               package_instance.allowed_models::text AS package_allowed_models
                        FROM gateway.api_key AS api_key
                        JOIN gateway.package_instance AS package_instance
                          ON package_instance.id = api_key.package_instance_id
                         AND package_instance.tenant_id = api_key.tenant_id
                         AND package_instance.user_id = api_key.user_id
                        WHERE api_key.id = :id
                          AND api_key.tenant_id = :tenant_id
                          AND api_key.user_id = :user_id
                          AND api_key.managed AND api_key.status = 'ACTIVE'
                          AND api_key.expires_at > now()
                          AND package_instance.status = 'ACTIVE'
                          AND package_instance.expires_at > now()
                          -- 同上：两份额度里只要还有一份没用完，这把 key 就仍然可轮换。
                          AND (package_instance.used_tokens < package_instance.total_token_quota
                               OR (package_instance.audio_seconds_quota IS NOT NULL
                                   AND package_instance.audio_seconds_used
                                       < package_instance.audio_seconds_quota))
                          AND NOT EXISTS (
                              SELECT 1
                              FROM gateway.package_refund_hold AS refund_hold
                              WHERE refund_hold.package_instance_id = package_instance.id
                                AND refund_hold.status IN ('WAITING', 'READY')
                          )
                        FOR UPDATE OF api_key, package_instance
                        """)
                .bind("id", keyId)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .map((row, metadata) -> new ManagedKeyForRotationRow(
                        row.get("id", UUID.class),
                        row.get("tenant_id", UUID.class),
                        row.get("user_id", UUID.class),
                        row.get("package_instance_id", UUID.class),
                        row.get("name", String.class),
                        row.get("key_prefix", String.class),
                        row.get("key_hash", String.class),
                        row.get("allowed_models", String.class),
                        row.get("expires_at", Instant.class),
                        row.get("usage_dimension", String.class),
                        row.get("package_allowed_models", String.class)))
                .one();
    }

    Mono<Long> revokeManagedKey(UUID keyId) {
        return database.sql("""
                        UPDATE gateway.api_key
                        SET status = 'REVOKED', revoked_at = now(), updated_at = now()
                        WHERE id = :id AND managed AND status = 'ACTIVE'
                        """)
                .bind("id", keyId)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> completeRotation(UUID operationId, UUID newKeyId) {
        return database.sql("""
                        UPDATE gateway.managed_api_key_rotation
                        SET new_api_key_id = :new_api_key_id, status = 'COMPLETED',
                            completed_at = now(), updated_at = now()
                        WHERE operation_id = :operation_id AND status = 'PENDING'
                        """)
                .bind("operation_id", operationId)
                .bind("new_api_key_id", newKeyId)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> insertManagedKey(
            UUID keyId,
            UUID tenantId,
            UUID userId,
            UUID packageId,
            String name,
            String prefix,
            String hash,
            String allowedModelsJson,
            Instant expiresAt,
            byte[] ciphertext,
            short secretKeyVersion,
            String usageDimension) {
        return database.sql("""
                        INSERT INTO gateway.api_key(
                            id, tenant_id, user_id, package_instance_id, name,
                            key_prefix, key_hash, allowed_models, status, expires_at,
                            managed, secret_ciphertext, secret_key_version, usage_dimension)
                        VALUES (
                            :id, :tenant_id, :user_id, :package_id, :name,
                            :key_prefix, :key_hash, :allowed_models::jsonb, 'ACTIVE', :expires_at,
                            true, :secret_ciphertext, :secret_key_version, :usage_dimension)
                        """)
                .bind("id", keyId)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .bind("package_id", packageId)
                .bind("name", name)
                .bind("key_prefix", prefix)
                .bind("key_hash", hash)
                .bind("allowed_models", allowedModelsJson)
                .bind("expires_at", expiresAt)
                .bind("secret_ciphertext", ciphertext)
                .bind("secret_key_version", secretKeyVersion)
                .bind("usage_dimension", usageDimension)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> synchronizeAndMarkRevealed(
            UUID keyId, UUID tenantId, UUID userId, UUID packageId,
            String allowedModelsJson) {
        return database.sql("""
                        UPDATE gateway.api_key
                        SET allowed_models = :allowed_models::jsonb,
                            last_revealed_at = now(), updated_at = now()
                        WHERE id = :id AND managed AND status = 'ACTIVE'
                          AND tenant_id = :tenant_id AND user_id = :user_id
                          AND package_instance_id = :package_id
                          AND expires_at > now()
                        """)
                .bind("id", keyId)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .bind("package_id", packageId)
                .bind("allowed_models", allowedModelsJson)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> insertAudit(
            UUID operationId,
            UUID tenantId,
            UUID actorUserId,
            String action,
            String resourceId,
            String detailJson) {
        return database.sql("""
                        INSERT INTO gateway.admin_operation_audit(
                            operation_id, tenant_id, actor_user_id, action, status,
                            affected, completed_at, resource_type, resource_id, detail)
                        VALUES (
                            :operation_id, :tenant_id, :actor_user_id, :action, 'COMPLETED',
                            1, now(), 'MANAGED_API_KEY', :resource_id, :detail::jsonb)
                        """)
                .bind("operation_id", operationId)
                .bind("tenant_id", tenantId)
                .bind("actor_user_id", actorUserId)
                .bind("action", action)
                .bind("resource_id", resourceId)
                .bind("detail", detailJson)
                .fetch()
                .rowsUpdated();
    }

    record ActivePackageRow(
            UUID id,
            String packageCode,
            Instant expiresAt,
            String allowedModelsJson,
            boolean refundInProgress,
            String usageDimension) {}

    record ManagedKeyRow(UUID id, String prefix, byte[] ciphertext) {}

    record RotationRow(
            UUID operationId,
            UUID tenantId,
            UUID actorUserId,
            UUID targetUserId,
            UUID oldKeyId,
            UUID newKeyId,
            String status,
            String newKeyPrefix) {}

    record ManagedKeyForRotationRow(
            UUID id,
            UUID tenantId,
            UUID userId,
            UUID packageId,
            String name,
            String prefix,
            String hash,
            String allowedModelsJson,
            Instant expiresAt,
            String usageDimension,
            String packageAllowedModelsJson) {}
}
