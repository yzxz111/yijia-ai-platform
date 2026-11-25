package com.yuzhihuihe.apiplatform.gateway.image;

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
 * 解析图片生成目录，以及用于服务某个生图别名的供应商路由。
 *
 * <p>这两处读取有意采用不同的准入边界，与视频路径相同。面向客户端的目录是
 * 权益/模型名录读取：一个生效的 {@code IMAGE} 套餐、网关目录中一个已启用的
 * 生图模型，以及它已登记的尺寸档位，就足以展示一个模型。供应商就绪状态
 * 不属于该契约，因为运维正在配置某套餐的上游路由时，该套餐仍应可见。
 *
 * <p>{@link #candidates(ApiKeyService.ApiKeyContext, String, String)} 是
 * {@code POST /v1/images/generations} 的执行路径。它保持严格：只有已启用的
 * 映射/渠道/供应商、健康的渠道与真实的凭证才可被使用。路由排序是先优先级
 * 后权重，同一优先级区间内随机打破平局。
 *
 * <p>与视频不同，这里没有针对特定厂商源站的 URL 正则。视频需要它，是因为
 * 百炼的对话源站与视频源站不同、共用会拼出无效路径；图片路径由
 * {@link TianYiYunImageGenerationAdapter#apiRoot(ImageGenerationAdapter.Channel)}
 * 从渠道 base URL 组合而成，并且同时容忍源站、带版本的根，以及运维原样粘贴的
 * 完整生成端点，因此把源站写死进准入查询只会拒绝合法配置。
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class ImageRouteRepository {

    static final String IMAGE_CANDIDATES_SQL =
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
                       AND octet_length(channel.encrypted_api_key) > 0))
            ORDER BY route_priority DESC, route_weight DESC, random()
            """;

    static final String ALIAS_SIZE_SQL =
            """
            SELECT resolution_tier
            FROM gateway.image_alias_tier
            WHERE model_code = :model_alias
            """;

    /**
     * 某个别名被注册到哪些上游模型上，用于回答「这个模型能生成什么参数」。
     *
     * <p>它有意不同于 {@link #candidates}: 那条是执行路径，必须有已启用的映射、
     * 健康的渠道与真实凭据才返回任何东西；本查询只回答「这条别名指向哪些上游
     * 模型」。理由与目录本身相同——模型能生成什么参数是**厂商契约**，不是运维
     * 当下的健康状态：一条暂时不健康的渠道不该让客户看到「这个模型不能生图」。
     *
     * <p>它同样不选出 {@code base_url} 与 {@code encrypted_api_key}：能力描述
     * 只需要厂商类型与上游模型名，凭证绝不为此离开数据库。
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

    /**
     * 客户端可见的生图目录行。
     *
     * <p>本查询有意不连接 {@code channel_model}、{@code channel} 或
     * {@code provider}。那些表描述的是上游路由当前是否可调用，
     * 而不是套餐是否买下了该模型。特别是，凭证缺失、路由不健康/被禁用，
     * 都不得把该模型从桌面端目录中藏起来。不选取任何供应商、
     * 上游模型或凭证。
     */
    static final String AVAILABLE_IMAGE_MODELS_SQL =
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
             AND routing_model.capabilities @> '["IMAGE_GEN"]'::jsonb
            JOIN gateway.image_alias_tier tier
              ON tier.model_code = routing_alias.alias
            WHERE package.id = :package_id
              AND package.tenant_id = :tenant_id
              AND package.user_id = :user_id
              AND package.status = 'ACTIVE'
              AND package.expires_at > now()
              AND package.usage_dimension = 'IMAGE'
            ORDER BY routing_alias.alias
            """;

    private final DatabaseClient database;
    private final CredentialCipher credentialCipher;
    private final boolean allowMock;

    @Autowired
    ImageRouteRepository(
            DatabaseClient database,
            CredentialCipher credentialCipher,
            @Value("${gateway.image.allow-mock:false}") boolean allowMock) {
        this.database = database;
        this.credentialCipher = credentialCipher;
        this.allowMock = allowMock;
    }

    ImageRouteRepository(DatabaseClient database, CredentialCipher credentialCipher) {
        this(database, credentialCipher, false);
    }

    /**
     * 该别名出售的尺寸档位，别名未登记时为空。
     *
     * <p>空并不表示「任何尺寸都允许」：调用方会拒绝该请求。
     * 生图别名按张定价，并且价格是按它登记的档位报出的，因此档位未知的别名
     * 无法按其消耗计费。参见 gateway V81。
     */
    Mono<String> sizeTier(String modelAlias) {
        if (modelAlias == null || modelAlias.isBlank()) {
            return Mono.empty();
        }
        return database.sql(ALIAS_SIZE_SQL)
                .bind("model_alias", modelAlias)
                .map((row, metadata) -> row.get("resolution_tier", String.class))
                .one();
    }

    /**
     * 该别名指向的上游模型（厂商类型 + 模型名），供能力目录使用。
     *
     * <p>它不施加任何健康/启用/凭据谓词，理由见 {@link #CATALOG_ROUTES_SQL}。
     */
    Flux<ImageCatalogRoute> catalogRoutes(String modelAlias) {
        if (modelAlias == null || modelAlias.isBlank()) {
            return Flux.empty();
        }
        return database.sql(CATALOG_ROUTES_SQL)
                .bind("model_alias", modelAlias)
                .map((row, metadata) -> new ImageCatalogRoute(
                        row.get("provider_type", String.class),
                        row.get("upstream_model", String.class)))
                .all();
    }

    /**
     * 生效的图片套餐有权展示的模型，即便其余额已经耗尽。
     *
     * <p>API key 自己的允许列表由 {@link ImageModelCatalogService} 施加；
     * 把它留在本查询之外，既避免了驱动特有的数组绑定，
     * 又仍然让数据库成为套餐归属、有效期、维度与允许模型条目的事实来源。
     * 配额耗尽有意不作为展示过滤器：预留与调用会在执行路径上强制执行它。
     * 本方法同样不主张某条供应商路由已就绪；{@link #candidates} 负责那些
     * 执行期的检查。
     */
    Flux<ImageModelCatalogRow> availableModels(
            UUID packageInstanceId, UUID tenantId, UUID userId) {
        if (packageInstanceId == null || tenantId == null || userId == null) {
            return Flux.empty();
        }
        return database.sql(AVAILABLE_IMAGE_MODELS_SQL)
                .bind("package_id", packageInstanceId)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .map((row, metadata) -> new ImageModelCatalogRow(
                        row.get("alias", String.class),
                        row.get("display_name", String.class),
                        row.get("resolution_tier", String.class)))
                .all();
    }

    /**
     * 实际上有资格接受生图调用的供应商路由。
     *
     * <p>与 {@link #availableModels(UUID, UUID, UUID)} 不同，本查询必须与
     * 运行期的路由状态保持耦合。它会在返回任何能够触达上游的东西之前，
     * 检查已启用的映射/渠道/供应商状态、健康度与凭证。因此一行目录记录
     * 绝不意味着一次调用会成功。
     *
     * @param apiKey 已认证的 key，用于模型允许列表检查
     * @param modelAlias 所请求的逻辑生图别名
     * @param capabilityJson 一个 jsonb 数组字面量，例如 {@code ["IMAGE_GEN"]}
     */
    Flux<ImageGenerationAdapter.Channel> candidates(
            ApiKeyService.ApiKeyContext apiKey, String modelAlias, String capabilityJson) {
        if (apiKey == null || modelAlias == null || modelAlias.isBlank()) {
            return Flux.empty();
        }
        if (apiKey.allowedModels() != null && !apiKey.allowedModels().contains(modelAlias)) {
            // 该 key 根本不被允许使用这个别名；没有任何渠道能让它合法。
            return Flux.empty();
        }
        return database.sql(IMAGE_CANDIDATES_SQL)
                .bind("model_alias", modelAlias)
                .bind("capability", capabilityJson)
                .bind("allow_mock", allowMock)
                .map((row, metadata) -> new ImageGenerationAdapter.Channel(
                        row.get("channel_id", UUID.class),
                        row.get("provider_id", UUID.class),
                        row.get("provider_type", String.class),
                        row.get("base_url", String.class),
                        decrypt(row.get("encrypted_api_key", byte[].class)),
                        row.get("upstream_model", String.class),
                        // 这个值以前被查出来又丢掉：查询里选了 timeout_ms，
                        // 构造函数却不接它，于是运维在渠道上配的超时被**静默忽略**，
                        // 实际生效的只有适配器里那个常量。现在它真的会生效。
                        row.get("timeout_ms", Integer.class)))
                .all();
    }

    /**
     * 一个供应商行 id 对应的适配器判别值。
     *
     * <p>恢复路径手上只有任务行，而任务行记的是 {@code provider_id} 而不是
     * {@code provider_type}；适配器注册表按后者索引。因此「拿到正确的适配器去问
     * 上游」需要这一步读取。
     *
     * <p>它**有意只读 {@code provider} 表**，不经过 {@code candidates} 那套
     * 渠道准入：一次恢复发生在 30 分钟之后，那个渠道完全可能已经被停用或不再健康，
     * 而「渠道当时是好的、现在坏了」正是我们要收尾的场景。用准入查询会把需要
     * 恢复的行正好筛掉。
     */
    Mono<String> providerType(UUID providerId) {
        if (providerId == null) {
            return Mono.empty();
        }
        return database.sql("SELECT provider_type FROM gateway.provider WHERE id = :id")
                .bind("id", providerId)
                .map((row, metadata) -> row.get("provider_type", String.class))
                .one();
    }

    private String decrypt(byte[] encrypted) {
        if (encrypted == null || encrypted.length == 0) {
            return null;
        }
        return credentialCipher.decrypt(encrypted);
    }

    record ImageModelCatalogRow(
            String alias,
            String displayName,
            String resolutionTier) { }

    /**
     * @param providerType 用于挑适配器的判别值
     * @param upstreamModel 该渠道配置的厂商模型名
     */
    record ImageCatalogRoute(String providerType, String upstreamModel) { }
}
