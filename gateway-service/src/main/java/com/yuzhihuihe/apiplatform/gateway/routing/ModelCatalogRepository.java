package com.yuzhihuihe.apiplatform.gateway.routing;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class ModelCatalogRepository {

    private final DatabaseClient database;

    ModelCatalogRepository(DatabaseClient database) {
        this.database = database;
    }

    /**
     * 返回某个 API key 所在套餐的、客户端可见的文本/模型目录。
     *
     * <p>这有意是一次权益读取，而不是执行路由读取。套餐拥有的模型
     * 必须在运维配置或修复其上游渠道期间保持可见。因此渠道映射、
     * 供应商健康度、凭证、熔断状态与剩余配额都由请求路径强制执行，
     * 而不是由这条目录查询。
     *
     * <p><b>生成类别名不在这里。</b>{@code VIDEO} 与 {@code IMAGE_GEN} 都是
     * **生成**能力：它们的别名不出现在对话目录里，而是各自走
     * {@code /v1/video-models} 与 {@code /v1/image-models}。
     * 复用一个词是行不通的——{@code IMAGE} 是输入模态（视觉对话的图片入参），
     * 因此生图需要自己的词，而目录必须把这个词也排除掉，
     * 否则一个生图别名会以一个「可以聊天」的模型出现在客户端面前。
     *
     * <p><b>判据是正向白名单，不是逐个排除。</b>这里先用
     * {@code capabilities @> '["TEXT"]'} 要求"这个别名能对话"，再排除两类生成能力。
     * 之前的版本只写了"排除 VIDEO / 排除 IMAGE_GEN"，于是任何**新增**的家族
     * （音频就是这样：V93 建立了 {@code audio-*} 别名与 {@code audio_alias_tier}，
     * 但这条查询没有跟着改）都会默认**落进**对话目录——那正是这个缺陷的形态。
     * 正向白名单让新增家族的默认失败方向变成"少列"而不是"错列"，
     * 显式的 {@code audio_alias_tier} 与 {@code AUDIO_SECOND} 判断是第二道防线。
     */
    Flux<CatalogModelRow> findEntitledModels(
            UUID packageInstanceId, UUID tenantId, UUID userId) {
        DatabaseClient.GenericExecuteSpec query = database.sql("""
                        SELECT alias.alias, model.display_name,
                               model.capabilities::text AS capabilities,
                               model.context_window, model.max_output_tokens,
                               model.routing_strategy
                        FROM gateway.model_alias alias
                        LEFT JOIN gateway.package_instance package
                          ON package.id = :package_id
                         AND package.tenant_id = :tenant_id
                         AND package.user_id = :user_id
                        JOIN gateway.model model ON model.id = alias.model_id AND model.enabled
                         -- 客户端目录只露出显式标记为可见的模型。这条过滤与「能否调用」
                         -- 是两件事：鉴权与执行只认套餐的 allowed_models，因此一个
                         -- client_visible = false 的别名仍能被第三方兼容客户端按名字调用，
                         -- 只是不再出现在 /v1/models 的列表里。
                         AND model.client_visible
                         AND model.capabilities @> '["TEXT"]'::jsonb
                         AND NOT (model.capabilities @> '["VIDEO"]'::jsonb)
                         AND NOT (model.capabilities @> '["IMAGE_GEN"]'::jsonb)
                         AND NOT EXISTS (
                             SELECT 1
                             FROM gateway.model_usage_price video_price
                             WHERE video_price.model_code = alias.alias
                               AND video_price.usage_dimension = 'VIDEO_SECOND'
                               AND video_price.effective_to IS NULL)
                         AND NOT EXISTS (
                             SELECT 1
                             FROM gateway.video_alias_tier video_alias
                             WHERE video_alias.model_code = alias.alias)
                         AND NOT EXISTS (
                             SELECT 1
                             FROM gateway.image_alias_tier image_alias
                             WHERE image_alias.model_code = alias.alias)
                         AND NOT EXISTS (
                             SELECT 1
                             FROM gateway.audio_alias_tier audio_alias
                             WHERE audio_alias.model_code = alias.alias)
                        WHERE :package_id IS NULL OR (
                              package.id IS NOT NULL
                          AND package.status = 'ACTIVE'
                          AND package.expires_at > now()
                          AND COALESCE(package.usage_dimension, 'TOKEN') <> 'VIDEO_SECOND'
                          AND COALESCE(package.usage_dimension, 'TOKEN') <> 'IMAGE'
                          AND COALESCE(package.usage_dimension, 'TOKEN') <> 'AUDIO_SECOND'
                          AND EXISTS (
                              SELECT 1
                              FROM jsonb_array_elements(package.allowed_models) allowed
                              WHERE allowed ->> 'modelCode' = alias.alias))
                        ORDER BY alias.alias
                        """);
        query = packageInstanceId == null
                ? query.bindNull("package_id", UUID.class)
                : query.bind("package_id", packageInstanceId);
        return query.bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .map((row, metadata) -> new CatalogModelRow(
                        row.get("alias", String.class),
                        row.get("display_name", String.class),
                        row.get("capabilities", String.class),
                        row.get("context_window", Integer.class),
                        row.get("max_output_tokens", Integer.class),
                        row.get("routing_strategy", String.class)))
                .all();
    }

    record CatalogModelRow(
            String alias,
            String displayName,
            String capabilitiesJson,
            int contextWindow,
            int maxOutputTokens,
            String routingStrategy) {}

    /**
     * 这条套餐**本来**该有对话模型吗？——与 {@link #findEntitledModels} 逐字相同的
     * 查询，只是**去掉 Key 那一层过滤**。
     *
     * <p>只用于解释空目录（P1-5 的延伸）：如果套餐这一侧本来有可用的对话别名，
     * 而加上 Key 的名单之后就空了，那问题不在套餐、也不在模型，而在**两份名单
     * 不一致**——2026-10-09 生产故障里最花时间的那一段，客户端与运维都被
     * {@code no_chat_models}（"套餐不含聊天模型"）指去了错误的方向。
     *
     * <p>复用同一条 SQL 而不是另写一份判断：两份"什么算可用的对话模型"的定义
     * 一旦分叉，这个诊断就会在真正需要它的那一天说谎。
     */
    Mono<Boolean> hasEntitledChatModels(UUID packageInstanceId, UUID tenantId, UUID userId) {
        return findEntitledModels(packageInstanceId, tenantId, userId).hasElements();
    }

    /**
     * 只用于解释"目录为什么是空的"（P1-5）：套餐的状态、有效期与计费维度。
     *
     * <p>它刻意只在这条目录**返回空**时才被调用——非空时客户端不需要理由，
     * 也就不该为一次额外的查询付代价。这里不读取任何额度或路由信息：
     * 空目录的原因只可能出在"这个 Key 的套餐"与"别名算不算聊天模型"上。
     */
    Mono<PackageStateRow> findPackageState(UUID packageInstanceId) {
        return database.sql("""
                        SELECT status, expires_at, usage_dimension
                        FROM gateway.package_instance
                        WHERE id = :package_id
                        """)
                .bind("package_id", packageInstanceId)
                .map((row, metadata) -> new PackageStateRow(
                        row.get("status", String.class),
                        row.get("expires_at", OffsetDateTime.class),
                        row.get("usage_dimension", String.class)))
                .one();
    }

    record PackageStateRow(String status, OffsetDateTime expiresAt, String usageDimension) {}
}
