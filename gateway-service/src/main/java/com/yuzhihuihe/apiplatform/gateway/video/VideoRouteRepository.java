package com.yuzhihuihe.apiplatform.gateway.video;

import java.time.Duration;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.routing.CredentialCipher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 解析视频目录，以及用于服务某个视频别名的供应商路由。
 *
 * <p>这两处读取有意采用不同的准入边界。面向客户端的目录是
 * 权益/模型名录读取：一个生效的 {@code VIDEO_SECOND} 套餐、
 * 网关目录中一个已启用的视频模型，以及它已登记的别名档位，
 * 就足以展示一个模型。供应商就绪状态不属于该契约，
 * 因为运维正在配置某套餐的上游路由时，该套餐仍应可见。
 *
 * <p>{@link #candidates(ApiKeyService.ApiKeyContext, String, String)} 是
 * {@code POST /v1/videos} 的执行路径。它保持严格：只有已启用的
 * 映射/渠道/供应商、健康的渠道、真实的凭证，以及供应商特有的端点
 * 才可被使用。路由排序是先优先级后权重，同一优先级区间内随机打破平局。
 * 百炼接受其区域工作空间源站、{@code /api/v1}，或文档给出的完整
 * 提交端点；公共 DashScope 源站对 Wan 路由可用，但对 Kling 路由
 * 有意保持不可用，后者需要工作空间端点。
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class VideoRouteRepository {

    static final String VIDEO_CANDIDATES_SQL =
            """
            SELECT provider.id AS provider_id,
                   provider.provider_type,
                   channel.id AS channel_id,
                   channel.base_url,
                   channel.encrypted_api_key,
                   COALESCE(channel.timeout_ms, 60000) AS timeout_ms,
                   COALESCE(mapping.priority_override, channel.priority) AS route_priority,
                   COALESCE(mapping.weight_override, channel.weight) AS route_weight,
                   mapping.upstream_model
            FROM gateway.model_alias routing_alias
            JOIN gateway.model routing_model
              ON routing_model.id = routing_alias.model_id AND routing_model.enabled
            JOIN gateway.channel_model mapping
              ON mapping.model_id = routing_model.id AND mapping.enabled
            JOIN gateway.channel channel
              ON channel.id = mapping.channel_id
             AND channel.enabled
             AND channel.last_health_status = 'HEALTHY'
            JOIN gateway.provider provider
              ON provider.id = channel.provider_id AND provider.enabled
            WHERE routing_alias.alias = :model_alias
              AND mapping.capabilities @> CAST(:capability AS jsonb)
              AND (
                   (:allow_mock = TRUE AND provider.provider_type = 'MOCK')
                   OR (provider.provider_type <> 'MOCK'
                       AND channel.encrypted_api_key IS NOT NULL
                       AND octet_length(channel.encrypted_api_key) > 0
                       AND (provider.provider_type <> 'BAILIAN'
                            OR channel.base_url ~
                               '^https://[^./]+\\.cn-beijing\\.maas\\.aliyuncs\\.com(/api/v1(/services/aigc/video-generation/video-synthesis)?)?/?$'
                            OR (mapping.upstream_model NOT LIKE 'kling/%'
                                AND channel.base_url ~
                               '^https://dashscope\\.aliyuncs\\.com(/api/v1(/services/aigc/video-generation/video-synthesis)?)?/?$'))))
            ORDER BY route_priority DESC, route_weight DESC, random()
            """;

    static final String ALIAS_RESOLUTION_SQL =
            """
            SELECT resolution_tier
            FROM gateway.video_alias_tier
            WHERE model_code = :model_alias
            """;

    /**
     * 客户端可见的视频目录行。
     *
     * <p>本查询有意不连接 {@code channel_model}、{@code channel} 或
     * {@code provider}。那些表描述的是上游路由当前是否可调用，
     * 而不是套餐是否买下了该模型。特别是，凭证缺失、
     * 路由不健康/被禁用，或工作空间端点尚未配置，
     * 都不得把该模型从桌面端目录中藏起来。不选取任何供应商、
     * 上游模型或凭证。
     */
    static final String AVAILABLE_VIDEO_MODELS_SQL =
            """
            SELECT routing_alias.alias,
                   routing_model.display_name,
                   tier.resolution_tier
            FROM gateway.package_instance package
            JOIN gateway.model_alias routing_alias
              ON EXISTS (
                   SELECT 1
                   FROM jsonb_array_elements(package.allowed_models) allowed
                   WHERE allowed ->> 'modelCode' = routing_alias.alias)
            JOIN gateway.model routing_model
              ON routing_model.id = routing_alias.model_id
             AND routing_model.enabled
             -- V110 起客户端视频目录只露出合并模型（video-minimax）：
             -- 档位别名（video-budget 等）client_visible=false，仍可被
             -- 第三方兼容客户端按名字调用，只是不再出现在 /v1/video-models。
             AND routing_model.client_visible
             AND routing_model.capabilities @> '["VIDEO"]'::jsonb
            JOIN gateway.video_alias_tier tier
              ON tier.model_code = routing_alias.alias
            WHERE package.id = :package_id
              AND package.tenant_id = :tenant_id
              AND package.user_id = :user_id
              AND package.status = 'ACTIVE'
              AND package.expires_at > now()
              AND package.usage_dimension = 'VIDEO_SECOND'
            ORDER BY routing_alias.alias
            """;

    /**
     * 某个别名被注册到哪些上游模型上，用于回答「这个模型能生成什么参数」。
     *
     * <p>它有意不同于 {@link #candidates}：那条查询是执行路径，必须有
     * 健康渠道、真实凭据与厂商专属端点才返回任何东西；本查询只回答
     * 「这条别名指向哪些上游模型」。原因与目录本身相同——模型能生成
     * 什么参数是厂商契约，不是运维当下的健康状态：一条暂时不健康的
     * 渠道不应该让客户看到「这个模型不能生成视频」。
     *
     * <p>它同样不选出 {@code base_url} 与 {@code encrypted_api_key}：
     * 能力描述只需要厂商类型与上游模型名，凭证绝不为此离开数据库。
     */
    static final String CATALOG_ROUTES_SQL =
            """
            SELECT DISTINCT provider.provider_type, mapping.upstream_model
            FROM gateway.model_alias routing_alias
            JOIN gateway.model routing_model
              ON routing_model.id = routing_alias.model_id
            JOIN gateway.channel_model mapping
              ON mapping.model_id = routing_model.id
            JOIN gateway.channel channel
              ON channel.id = mapping.channel_id
            JOIN gateway.provider provider
              ON provider.id = channel.provider_id
            WHERE routing_alias.alias = :model_alias
            ORDER BY provider.provider_type, mapping.upstream_model
            """;

    private final DatabaseClient database;
    private final CredentialCipher credentialCipher;
    private final boolean allowMock;

    @Autowired
    VideoRouteRepository(
            DatabaseClient database,
            CredentialCipher credentialCipher,
            @Value("${gateway.video.allow-mock:false}") boolean allowMock) {
        this.database = database;
        this.credentialCipher = credentialCipher;
        this.allowMock = allowMock;
    }

    VideoRouteRepository(DatabaseClient database, CredentialCipher credentialCipher) {
        this(database, credentialCipher, false);
    }

    /**
     * 该别名所售的分辨率档位，别名未登记时为空。
     *
     * <p>空并不表示「任何分辨率都允许」：调用方会拒绝该请求。
     * 每个别名都按分辨率定价（video-budget 是 480P 别名，每秒 35
     * 分，1080P 为 115），因此档位未知的别名无法
     * 按其消耗计费。参见 gateway V54。
     */
    Mono<String> resolutionTier(String modelAlias) {
        if (modelAlias == null || modelAlias.isBlank()) {
            return Mono.empty();
        }
        return database.sql(ALIAS_RESOLUTION_SQL)
                .bind("model_alias", modelAlias)
                .map((row, metadata) -> row.get("resolution_tier", String.class))
                .one();
    }

    /**
     * 合并模型（V110）的档位映射，只返回该套餐**实际持有**内部别名的档位。
     *
     * <p>权利是逐档位表达的：套餐没有 video-budget 时，合并模型就不该
     * 在目录里承诺 480P——目录据此列出 resolutions，提交据此解析内部别名。
     * 没有映射行的别名返回空 Flux，调用方退回单档位路径。
     */
    Flux<ClientTierRow> clientTierMappings(
            UUID packageInstanceId, UUID tenantId, UUID userId, String clientModelCode) {
        if (packageInstanceId == null || clientModelCode == null || clientModelCode.isBlank()) {
            return Flux.empty();
        }
        return database.sql("""
                        SELECT mapping.resolution_tier, mapping.internal_model_code
                        FROM gateway.video_client_tier_mapping mapping
                        JOIN gateway.package_instance package
                          ON package.id = :package_id
                         AND package.tenant_id = :tenant_id
                         AND package.user_id = :user_id
                        WHERE mapping.client_model_code = :client_model
                          AND EXISTS (
                              SELECT 1
                              FROM jsonb_array_elements(package.allowed_models) allowed
                              WHERE allowed ->> 'modelCode' = mapping.internal_model_code)
                        ORDER BY mapping.position
                        """)
                .bind("package_id", packageInstanceId)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .bind("client_model", clientModelCode)
                .map((row, metadata) -> new ClientTierRow(
                        row.get("resolution_tier", String.class),
                        row.get("internal_model_code", String.class)))
                .all();
    }

    /**
     * 提交时的档位解析：{@code (client_model, resolution_tier)} → 内部别名。
     *
     * <p>与目录同一条权利边界：该套餐不持有内部别名时没有映射行，
     * 调用方保持提交的别名原样，随后在档位校验里以 422 fail-closed。
     */
    Mono<String> internalAliasFor(
            UUID packageInstanceId, UUID tenantId, UUID userId,
            String clientModelCode, String resolutionTier) {
        if (packageInstanceId == null || clientModelCode == null || clientModelCode.isBlank()
                || resolutionTier == null || resolutionTier.isBlank()) {
            return Mono.empty();
        }
        return database.sql("""
                        SELECT mapping.internal_model_code
                        FROM gateway.video_client_tier_mapping mapping
                        JOIN gateway.package_instance package
                          ON package.id = :package_id
                         AND package.tenant_id = :tenant_id
                         AND package.user_id = :user_id
                        WHERE mapping.client_model_code = :client_model
                          AND mapping.resolution_tier = :tier
                          AND EXISTS (
                              SELECT 1
                              FROM jsonb_array_elements(package.allowed_models) allowed
                              WHERE allowed ->> 'modelCode' = mapping.internal_model_code)
                        """)
                .bind("package_id", packageInstanceId)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .bind("client_model", clientModelCode)
                .bind("tier", resolutionTier)
                .map((row, metadata) -> row.get("internal_model_code", String.class))
                .one();
    }

    /**
     * 生效的秒数套餐有权展示的模型，即便其余额
     * 已经耗尽。
     *
     * <p>API key 自己的允许列表由 {@link VideoModelCatalogService} 施加；
     * 把它留在本查询之外，既避免了驱动特有的数组绑定，
     * 又仍然让数据库成为套餐归属、有效期、维度与允许模型条目的
     * 事实来源。配额耗尽有意不作为展示过滤器：预留与提交会在
     * 执行路径上强制执行它。本方法同样不主张某条供应商路由已就绪；
     * {@link #candidates} 负责那些执行期的检查。
     */
    Flux<VideoModelCatalogRow> availableModels(
            UUID packageInstanceId, UUID tenantId, UUID userId) {
        if (packageInstanceId == null || tenantId == null || userId == null) {
            return Flux.empty();
        }
        return database.sql(AVAILABLE_VIDEO_MODELS_SQL)
                .bind("package_id", packageInstanceId)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .map((row, metadata) -> new VideoModelCatalogRow(
                        row.get("alias", String.class),
                        row.get("display_name", String.class),
                        row.get("resolution_tier", String.class)))
                .all();
    }

    /**
     * 某个别名当前被注册到的上游模型（厂商类型 + 上游模型名）。
     *
     * <p>调用方据此把每条路由交给对应的适配器，问出「这个上游模型
     * 能生成什么参数」。没有配置任何路由时返回空 Flux——
     * 那时目录只能报告平台自身的准入边界，而不得声称
     * 上游支持任何档位。
     */
    Flux<VideoCatalogRoute> catalogRoutes(String modelAlias) {
        if (modelAlias == null || modelAlias.isBlank()) {
            return Flux.empty();
        }
        return database.sql(CATALOG_ROUTES_SQL)
                .bind("model_alias", modelAlias)
                .map((row, metadata) -> new VideoCatalogRoute(
                        row.get("provider_type", String.class),
                        row.get("upstream_model", String.class)))
                .all();
    }

    /**
     * 实际上有资格接受视频提交或轮询的供应商路由。
     *
     * <p>与 {@link #availableModels(UUID, UUID, UUID)} 不同，本查询必须与
     * 运行期的路由状态保持耦合。它会在返回任何能够触达
     * 上游的东西之前，检查已启用的映射/渠道/供应商状态、健康度、
     * 凭证以及供应商特有的端点。因此一行目录记录绝不意味着
     * 一次提交会成功。
     *
     * @param apiKey 已认证的 key，用于模型允许列表检查
     * @param modelAlias 所请求的逻辑视频别名
     * @param capabilityJson 一个 jsonb 数组字面量，例如 {@code ["VIDEO"]}
     */
    Flux<VideoGenerationAdapter.Channel> candidates(
            ApiKeyService.ApiKeyContext apiKey, String modelAlias, String capabilityJson) {
        if (apiKey == null || modelAlias == null || modelAlias.isBlank()) {
            return Flux.empty();
        }
        if (apiKey.allowedModels() != null && !apiKey.allowedModels().contains(modelAlias)) {
            // 该 key 根本不被允许使用这个别名；没有任何渠道能让它合法。
            return Flux.empty();
        }
        return database.sql(VIDEO_CANDIDATES_SQL)
                .bind("model_alias", modelAlias)
                .bind("capability", capabilityJson)
                .bind("allow_mock", allowMock)
                .map((row, metadata) -> new VideoGenerationAdapter.Channel(
                        row.get("channel_id", UUID.class),
                        row.get("provider_id", UUID.class),
                        row.get("provider_type", String.class),
                        row.get("base_url", String.class),
                        decrypt(row.get("encrypted_api_key", byte[].class)),
                        row.get("upstream_model", String.class)))
                .all();
    }

    private String decrypt(byte[] encrypted) {
        if (encrypted == null || encrypted.length == 0) {
            return null;
        }
        return credentialCipher.decrypt(encrypted);
    }

    static Duration timeout(long timeoutMs) {
        return Duration.ofMillis(timeoutMs);
    }

    record VideoModelCatalogRow(
            String alias,
            String displayName,
            String resolutionTier) { }

    /** 合并模型的单个档位：客户端提交这一档时，内部用哪个别名执行与计费。 */
    record ClientTierRow(String resolutionTier, String internalModelCode) { }

    /**
     * 一条路由的能力读取结果：足够挑选适配器，但不足以发起调用。
     *
     * @param providerType 用于挑选适配器的判别值，例如 {@code BAILIAN}
     * @param upstreamModel 该别名映射到的厂商模型名，例如 {@code wan2.6-t2v}
     */
    record VideoCatalogRoute(
            String providerType,
            String upstreamModel) { }
}
