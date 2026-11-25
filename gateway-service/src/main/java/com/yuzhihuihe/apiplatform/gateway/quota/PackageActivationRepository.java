package com.yuzhihuihe.apiplatform.gateway.quota;

import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class PackageActivationRepository {

    private final DatabaseClient database;

    public PackageActivationRepository(DatabaseClient database) {
        this.database = database;
    }

    Mono<Long> insertConsumedEvent(UUID eventId, String eventType, String payloadHash) {
        return database.sql("""
                        INSERT INTO gateway.consumed_event(event_id, event_type, payload_hash)
                        VALUES (:event_id, :event_type, :payload_hash)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .bind("event_id", eventId)
                .bind("event_type", eventType)
                .bind("payload_hash", payloadHash)
                .fetch()
                .rowsUpdated();
    }

    Mono<String> findConsumedEventHash(UUID eventId) {
        return database.sql("""
                        SELECT payload_hash
                        FROM gateway.consumed_event
                        WHERE event_id = :event_id
                        """)
                .bind("event_id", eventId)
                .map((row, metadata) -> row.get("payload_hash", String.class))
                .one();
    }

    Mono<Void> lockUser(UUID userId) {
        return database.sql("SELECT pg_advisory_xact_lock(hashtextextended(:user_id, 0))")
                .bind("user_id", userId.toString())
                .fetch()
                .rowsUpdated()
                .then();
    }

    Flux<ExpiredPackageRow> expiredActivePackages(int limit) {
        return database.sql("""
                        SELECT id, user_id, usage_dimension
                        FROM gateway.package_instance
                        WHERE status = 'ACTIVE' AND expires_at <= now()
                        ORDER BY expires_at, id
                        LIMIT :limit
                        """)
                .bind("limit", limit)
                .map((row, metadata) -> new ExpiredPackageRow(
                        row.get("id", UUID.class),
                        row.get("user_id", UUID.class),
                        row.get("usage_dimension", String.class)))
                .all();
    }

    /**
     * 终结该用户在该维度上已用尽或已过期的实例。
     *
     * <p>「用尽」自方案 C 起要求**两个桶都用尽**：一个既卖视频秒又卖音频秒的
     * 实例，视频秒花完时它的音频秒仍然有效。只看主桶会在那一刻终结实例、
     * 于是客户为语音付过的钱在他还没用过一次转写时就消失了
     * （{@code used_tokens >= total_token_quota} 是主桶，音频秒在另一列）。
     * 反过来也一样：音频秒花完不得让视频生成停下。
     *
     * <p>代价是一条已知的产品限制：主桶花完而音频桶还有余额时，实例保持
     * ACTIVE，因此队列里下一个同维度套餐不会激活（一个用户在一个维度上
     * 只能有一个生效实例）。要表达「视频秒花了就换下一支视频套餐」需要
     * 一份独立的维度队列，那是另一项改动，不在方案 C 里。
     */
    Mono<Long> closeTerminalActive(UUID userId, String usageDimension) {
        return database.sql("""
                        UPDATE gateway.package_instance
                        SET status = CASE
                                WHEN used_tokens >= total_token_quota
                                     AND (audio_seconds_quota IS NULL
                                          OR audio_seconds_used >= audio_seconds_quota)
                                    THEN 'EXHAUSTED'
                                ELSE 'EXPIRED'
                            END,
                            ended_at = now(),
                            updated_at = now(),
                            lock_version = lock_version + 1
                        WHERE user_id = :user_id
                          AND usage_dimension = :usage_dimension
                          AND status = 'ACTIVE'
                          AND (expires_at <= now()
                               OR (used_tokens >= total_token_quota
                                   AND (audio_seconds_quota IS NULL
                                        OR audio_seconds_used >= audio_seconds_quota)))
                          AND NOT EXISTS (
                              SELECT 1
                              FROM gateway.package_refund_hold AS refund_hold
                              WHERE refund_hold.package_instance_id = package_instance.id
                                AND refund_hold.status IN ('WAITING', 'READY')
                          )
                        """)
                .bind("user_id", userId)
                .bind("usage_dimension", usageDimension)
                .fetch()
                .rowsUpdated();
    }

    /**
     * 该用户在该维度上是否已有生效实例。维度谓词是「同时持有文本与视频套餐」的全部要害：
     * 少了它，一个视频实例会把文本实例挡在队列里（反之亦然）。
     */
    Mono<Boolean> hasActivePackage(UUID userId, String usageDimension) {
        return database.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM gateway.package_instance
                            WHERE user_id = :user_id AND status = 'ACTIVE'
                              AND usage_dimension = :usage_dimension
                        ) AS active
                        """)
                .bind("user_id", userId)
                .bind("usage_dimension", usageDimension)
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("active", Boolean.class)))
                .one();
    }

    Mono<ActivationRow> findCurrentActive(UUID userId, String usageDimension) {
        return database.sql("""
                        SELECT id, status
                        FROM gateway.package_instance
                        WHERE user_id = :user_id AND status = 'ACTIVE'
                          AND usage_dimension = :usage_dimension
                        """)
                .bind("user_id", userId)
                .bind("usage_dimension", usageDimension)
                .map((row, metadata) -> new ActivationRow(
                        row.get("id", UUID.class), row.get("status", String.class)))
                .one();
    }

    /**
     * 该维度上最旧的排队实例。队列必须按维度选取：一个文本实例终结时激活一个
     * 排队的视频实例，会让用户拿到的额度单位与他的 key 完全不符。
     */
    Mono<QueuedPackageRow> findOldestQueuedForUpdate(UUID userId, String usageDimension) {
        return database.sql("""
                SELECT id, duration_days, allowed_models::text AS allowed_models
                       , tenant_id, usage_dimension
                FROM gateway.package_instance
                        WHERE user_id = :user_id AND status = 'QUEUED'
                          AND usage_dimension = :usage_dimension
                        ORDER BY created_at, id
                        LIMIT 1
                        FOR UPDATE
                        """)
                .bind("user_id", userId)
                .bind("usage_dimension", usageDimension)
                .map((row, metadata) -> new QueuedPackageRow(
                        row.get("id", UUID.class),
                        row.get("tenant_id", UUID.class),
                        row.get("duration_days", Integer.class),
                        row.get("allowed_models", String.class),
                        row.get("usage_dimension", String.class)))
                .one();
    }

    Mono<Long> activateQueued(UUID id, Instant activatedAt, Instant expiresAt) {
        return database.sql("""
                        UPDATE gateway.package_instance
                        SET status = 'ACTIVE', starts_at = :starts_at,
                            activated_at = :activated_at, expires_at = :expires_at,
                            updated_at = now(), lock_version = lock_version + 1
                        WHERE id = :id AND status = 'QUEUED'
                        """)
                .bind("starts_at", activatedAt)
                .bind("activated_at", activatedAt)
                .bind("expires_at", expiresAt)
                .bind("id", id)
                .fetch()
                .rowsUpdated();
    }

    /**
     * 该用户是否存在已终结的套餐实例。
     *
     * <p>这是 SWITCH 台账的前驱。{@code ck_ledger_scope} 要求
     * {@code entry_type = 'SWITCH'} 的行必须带 {@code source_package_instance_id}，
     * 而一个从未终结过的实例不可能是 source —— 因此「没有前驱」不是台账写入失败，
     * 而是本来就没有可记的切换，调用方必须据此跳过写入而不是报错。
     */
    Mono<Boolean> hasTerminalPackage(UUID userId) {
        return database.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM gateway.package_instance
                            WHERE user_id = :user_id
                              AND status IN ('EXHAUSTED', 'EXPIRED', 'REVOKED')
                        ) AS present
                        """)
                .bind("user_id", userId)
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("present", Boolean.class)))
                .one();
    }

    Mono<Long> insertPackageSwitch(
            UUID ledgerId, String businessNo, UUID userId, UUID targetPackageId) {
        return database.sql("""
                        INSERT INTO gateway.quota_ledger(
                            id, business_no, reservation_id, package_instance_id,
                            source_package_instance_id, model_code, entry_type,
                            total_token_delta, model_token_delta,
                            balance_after, model_balance_after)
                        SELECT :ledger_id, :business_no, NULL, target.id,
                               source.id, '*', 'SWITCH', 0, NULL,
                               target.total_token_quota, NULL
                        FROM gateway.package_instance AS target
                        JOIN LATERAL (
                            SELECT previous.id
                            FROM gateway.package_instance AS previous
                            WHERE previous.user_id = :user_id
                              AND previous.id <> target.id
                              AND previous.status IN ('EXHAUSTED', 'EXPIRED', 'REVOKED')
                            ORDER BY previous.ended_at DESC NULLS LAST,
                                     previous.created_at DESC,
                                     previous.id DESC
                            LIMIT 1
                        ) AS source ON true
                        WHERE target.id = :target_id
                        """)
                .bind("ledger_id", ledgerId)
                .bind("business_no", businessNo)
                .bind("user_id", userId)
                .bind("target_id", targetPackageId)
                .fetch()
                .rowsUpdated();
    }

    /**
     * 把该维度上尚未托管的 key 重绑到刚激活的实例。
     *
     * <p>{@code key.usage_dimension = :usage_dimension} 是这次改动新增的谓词，也是
     * 它取代了「激活即抢走全部 key」的地方：激活一个视频套餐不再动文本 key，
     * 因此文本调用不会因为买了一支视频套餐而失效。
     *
     * <p><b>为什么不能写 {@code jsonb_array_elements_text}。</b>那张列里真的有
     * 两种形态（{@code ApiKeyService} 与 {@code ManagedApiKeyService} 各写一种）：
     * 托管 Key 写 {@code ["video-basic", ...]}，自助创建的 Key 写
     * {@code [{"modelCode": "video-basic", "capabilities": [...],
     * "modelTokenQuota": 100}]}。{@code jsonb_array_elements_text} 对**对象**元素
     * 返回的是它的 JSON 文本（{@code {"modelCode": ...}}），于是
     * {@code key_model.model_code} 永远不等于 {@code package_model.value ->> 'modelCode'}，
     * 过滤后一行不剩、{@code COALESCE} 落回 {@code '[]'}：**一次续费/重新购买就把
     * 这些 Key 的允许模型清空**，而那把 Key 之后调任何模型都会被拒。
     *
     * <p>这里对两种形态一视同仁地取 {@code modelCode}（对象）或元素本身（字符串），
     * 并把结果**规范化成字符串数组**——与 {@code ManagedApiKeyService} 写入的形状
     * 一致，也正是读取方（{@code ApiKeyService.readStringSet}）认的形状。
     */
    Mono<Void> rebindApiKeys(
            UUID packageId,
            String packageModelsJson,
            UUID tenantId,
            UUID userId,
            String usageDimension) {
        return database.sql("""
                        UPDATE gateway.api_key AS key
                        SET package_instance_id = :package_id,
                            allowed_models = COALESCE((
                                SELECT jsonb_agg(key_model.model_code ORDER BY key_model.model_code)
                                FROM jsonb_array_elements(key.allowed_models)
                                    AS key_element(value)
                                CROSS JOIN LATERAL (
                                    SELECT CASE
                                        WHEN jsonb_typeof(key_element.value) = 'object'
                                            THEN key_element.value ->> 'modelCode'
                                        ELSE key_element.value #>> '{}'
                                    END AS model_code
                                ) AS key_model
                                WHERE key_model.model_code IS NOT NULL
                                  AND EXISTS (
                                    SELECT 1
                                    FROM jsonb_array_elements(:package_models::jsonb)
                                        AS package_model(value)
                                    WHERE package_model.value ->> 'modelCode' = key_model.model_code
                                )
                            ), '[]'::jsonb),
                            updated_at = now()
                        WHERE key.tenant_id = :tenant_id
                          AND key.user_id = :user_id
                          AND key.usage_dimension = :usage_dimension
                          AND NOT key.managed
                          AND key.status <> 'REVOKED'
                        """)
                .bind("package_id", packageId)
                .bind("package_models", packageModelsJson)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .bind("usage_dimension", usageDimension)
                .fetch()
                .rowsUpdated()
                .then();
    }

    Mono<Boolean> hasFreeClaim(String identityKey) {
        return database.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM gateway.package_instance
                            WHERE identity_key = :identity_key AND package_kind = 'FREE_CLAIM'
                        ) AS granted
                        """)
                .bind("identity_key", identityKey)
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("granted", Boolean.class)))
                .one();
    }

    Mono<Long> insertPackage(NewPackageRow value) {
        DatabaseClient.GenericExecuteSpec insert = database.sql("""
                        INSERT INTO gateway.package_instance(
                            id, grant_event_id, grant_request_id, tenant_id, user_id,
                            identity_key, package_plan_version_id, package_code, package_name,
                            package_kind,
                            price_amount, currency, allowed_models, total_token_quota,
                            audio_seconds_quota,
                            max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency,
                            duration_days, daily_request_limit, max_output_tokens,
                            max_file_size_bytes, once_per_user, usage_dimension,
                            status, starts_at, expires_at, activated_at)
                        VALUES (
                            :id, :event_id, :grant_id, :tenant_id, :user_id,
                            :identity_key, :version_id, :package_code, :package_name,
                            :package_kind,
                            :price_amount, :currency, :allowed_models::jsonb, :total_tokens,
                            :audio_seconds_quota,
                            :max_api_keys, :rpm, :tpm, :max_concurrency,
                            :duration_days, :daily_limit, :max_output_tokens,
                            :max_file_size, :once_per_user, :usage_dimension,
                            :status, :starts_at, :expires_at, :activated_at)
                        """)
                .bind("id", value.id())
                .bind("event_id", value.eventId())
                .bind("grant_id", value.grantRequestId())
                .bind("tenant_id", value.tenantId())
                .bind("user_id", value.userId())
                .bind("identity_key", value.identityKey())
                .bind("version_id", value.packageVersionId())
                .bind("package_code", value.packageCode())
                .bind("package_name", value.packageName())
                .bind("package_kind", value.packageKind())
                .bind("price_amount", value.priceAmount())
                .bind("currency", value.currency())
                .bind("allowed_models", value.allowedModelsJson())
                .bind("total_tokens", value.totalTokenQuota())
                .bind("max_api_keys", value.maxApiKeys())
                .bind("rpm", value.requestsPerMinute())
                .bind("tpm", value.tokensPerMinute())
                .bind("max_concurrency", value.maxConcurrency())
                .bind("duration_days", value.durationDays())
                .bind("max_file_size", value.maxFileSizeBytes())
                .bind("once_per_user", value.oncePerUser())
                .bind("usage_dimension", value.usageDimension())
                .bind("status", value.status());
        insert = bindNullable(insert, "audio_seconds_quota", value.audioSecondsQuota(), Long.class);
        insert = bindNullable(insert, "daily_limit", value.dailyRequestLimit(), Integer.class);
        insert = bindNullable(insert, "max_output_tokens", value.maxOutputTokens(), Integer.class);
        insert = bindNullable(insert, "starts_at", value.activatedAt(), Instant.class);
        insert = bindNullable(insert, "activated_at", value.activatedAt(), Instant.class);
        insert = bindNullable(insert, "expires_at", value.expiresAt(), Instant.class);
        return insert.fetch().rowsUpdated();
    }

    Mono<Long> insertModelQuota(
            UUID id, UUID packageInstanceId, String modelCode, String usageDimension, long quota) {
        // 每个维度一个计数器列：秒数行不得携带 token 数值，
        // 而 CHECK 约束强制只设置其中一个。
        boolean tokenDimension = "TOKEN".equals(usageDimension);
        DatabaseClient.GenericExecuteSpec insert = database.sql("""
                        INSERT INTO gateway.package_instance_model_quota(
                            id, package_instance_id, model_code, usage_dimension,
                            token_quota, seconds_quota)
                        VALUES (:id, :instance_id, :model_code, :usage_dimension,
                                :token_quota, :seconds_quota)
                        """)
                .bind("id", id)
                .bind("instance_id", packageInstanceId)
                .bind("model_code", modelCode)
                .bind("usage_dimension", usageDimension);
        insert = tokenDimension
                ? insert.bind("token_quota", quota).bindNull("seconds_quota", Long.class)
                : insert.bindNull("token_quota", Long.class).bind("seconds_quota", quota);
        return insert.fetch().rowsUpdated();
    }

    Mono<ActivationRow> findExistingResult(UUID eventId) {
        return database.sql("""
                        SELECT id, status
                        FROM gateway.package_instance
                        WHERE grant_event_id = :event_id
                        """)
                .bind("event_id", eventId)
                .map((row, metadata) -> new ActivationRow(
                        row.get("id", UUID.class), row.get("status", String.class)))
                .one();
    }

    private <T> DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    record ActivationRow(UUID packageInstanceId, String status) {}

    record ExpiredPackageRow(UUID packageInstanceId, UUID userId, String usageDimension) {}

    record QueuedPackageRow(
            UUID id, UUID tenantId, int durationDays, String allowedModelsJson,
            String usageDimension) {}

    record NewPackageRow(
            UUID id,
            UUID eventId,
            UUID grantRequestId,
            UUID tenantId,
            UUID userId,
            String identityKey,
            UUID packageVersionId,
            String packageCode,
            String packageName,
            String packageKind,
            long priceAmount,
            String currency,
            String allowedModelsJson,
            long totalTokenQuota,
            /**
             * 方案 C 的第二份额度；为 null 表示这个实例不卖语音。
             * 与 {@code totalTokenQuota} 并列而不是相加：它们是两个不同的桶，
             * 单位相同（秒）但成本相差三个数量级。
             */
            Long audioSecondsQuota,
            int maxApiKeys,
            int requestsPerMinute,
            long tokensPerMinute,
            int maxConcurrency,
            int durationDays,
            Integer dailyRequestLimit,
            Integer maxOutputTokens,
            long maxFileSizeBytes,
            boolean oncePerUser,
            String usageDimension,
            String status,
            Instant activatedAt,
            Instant expiresAt) {}
}
