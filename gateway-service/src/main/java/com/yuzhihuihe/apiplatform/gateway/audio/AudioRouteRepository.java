package com.yuzhihuihe.apiplatform.gateway.audio;

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
 * 解析音频目录，以及用于服务某个音频别名的供应商路由。
 *
 * <p>与 {@code ImageRouteRepository} 同形：两处读取采用不同的准入边界。
 * 面向客户端的目录是权益读取——一个生效的 {@code AUDIO_SECOND} 套餐加上
 * 网关目录里一个已启用的音频模型，就足以展示它；供应商是否配好不属于该契约，
 * 因为运维正在给一个已售套餐配路由时，它仍然应该可见。
 * {@link #candidates} 则是执行路径，它保持严格：已启用的映射/渠道/供应商、
 * 健康的渠道与真实的凭证。
 *
 * <p><b>为什么不复用 {@code ImageRouteRepository} 的查询。</b>它的能力谓词是
 * 一个参数，但它的别名档位来自 {@code image_alias_tier}，而音频的档位在
 * {@code audio_alias_tier}；更要紧的是它把渠道映射成
 * {@code ImageGenerationAdapter.Channel}，而音频的三个适配器各自声明了自己的
 * {@code Channel} 记录。因此本仓库返回一个**中性的** {@link AudioRoute}，
 * 由调用方翻译成对应适配器的形状。
 *
 * <p><b>为什么是中性的而不是三个方法。</b>三个 {@code Channel} 记录字段完全相同，
 * 差别只是类型——它们各自存在是因为三个适配器是三个接口，而不是因为渠道
 * 在音频内部真有三种形状。让仓库返回三个几乎相同的对象只会把那份重复
 * 从适配器层搬到仓库层。翻译放在调用处，那是唯一知道要哪个适配器的地方。
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class AudioRouteRepository {

    /**
     * 有资格接受一次音频调用的供应商路由。
     *
     * <p>{@code :capability} 是三个能力名之一，因此同一个查询服务三个端点。
     * 与图片路径一样，这里**没有**针对特定厂商源站的 URL 正则：音频三个适配器
     * 都从渠道 base URL 组合自己的路径，并同时容忍源站、带版本的根，
     * 以及运维原样粘贴的完整端点。把源站写死进准入查询只会拒绝合法配置。
     */
    static final String AUDIO_CANDIDATES_SQL =
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

    /**
     * 客户端可见的音频目录行。
     *
     * <p>有意不连接 {@code channel_model}、{@code channel} 或 {@code provider}。
     * 那些表描述的是上游路由当前是否可调用，而不是套餐是否买下了该模型。
     * 凭证缺失、路由不健康或被禁用，都不得把该模型从目录中藏起来。
     *
     * <p><b>可卖音频的两种形态（V106）。</b>纯音频套餐
     * （{@code usage_dimension = 'AUDIO_SECOND'}）的音频秒住在主额度列里；
     * 而**混合**套餐（视频秒 + 音频秒，平台 V103 发的那五个）主维度是
     * {@code VIDEO_SECOND}，音频秒住在第二份额度 {@code audio_seconds_quota} 里。
     * 两者都必须能在这里看到——只看主维度会让「买了视频套餐的人也能用配音」
     * 这句话在客户端目录里不成立：接口 {@code /v1/audio-models} 会返回空，
     * 客户端根本没有理由去调音频端点，而那份额度一直躺着。
     *
     * <p>判据因此写成「主维度是 AUDIO_SECOND，或它带着一份音频额度」。
     * 后半句用的是 {@code > 0} 而不是 {@code IS NOT NULL}：一个额度为 0 的实例
     * 没有任何一秒可以花，列进目录等于承诺它卖语音。token / 图片套餐两边都不满足，
     * 因此它们仍然不会出现在音频目录里（这是这条目录一直以来的性质）。
     */
    static final String AVAILABLE_AUDIO_MODELS_SQL =
            """
            SELECT routing_alias.alias,
                   routing_model.display_name,
                   routing_model.capabilities::text AS capabilities,
                   tier.billing_unit
            FROM gateway.package_instance package
            JOIN gateway.model_alias routing_alias
              ON EXISTS (
                   SELECT 1
                   FROM jsonb_array_elements(package.allowed_models) allowed
                   WHERE allowed ->> 'modelCode' = routing_alias.alias)
            JOIN gateway.model routing_model
              ON routing_model.id = routing_alias.model_id
             AND routing_model.enabled
             AND (routing_model.capabilities @> '["AUDIO_TRANSCRIBE"]'::jsonb
                  OR routing_model.capabilities @> '["AUDIO_SYNTHESIS"]'::jsonb
                  OR routing_model.capabilities @> '["LIP_SYNC"]'::jsonb)
            JOIN gateway.audio_alias_tier tier
              ON tier.model_code = routing_alias.alias
            WHERE package.id = :package_id
              AND package.tenant_id = :tenant_id
              AND package.user_id = :user_id
              AND package.status = 'ACTIVE'
              AND package.expires_at > now()
              AND (package.usage_dimension = 'AUDIO_SECOND'
                   OR coalesce(package.audio_seconds_quota, 0) > 0)
            ORDER BY routing_alias.alias
            """;

    /**
     * 该音频别名登记的计费单位。
     *
     * <p>空并**不**表示「不计量」：调用方会拒绝该请求。音频别名按秒定价，
     * 而一个没有登记单位的别名无法按其消耗计费——这正是 V92 建立
     * {@code audio_alias_tier} 的原因。空返回意味着运维建了一个别名却没有
     * 告诉网关它按什么卖，那时的正确答案是拒绝而不是猜。
     */
    static final String ALIAS_UNIT_SQL =
            """
            SELECT billing_unit
            FROM gateway.audio_alias_tier
            WHERE model_code = :model_alias
            """;

    private final DatabaseClient database;
    private final CredentialCipher credentialCipher;
    private final boolean allowMock;

    @Autowired
    AudioRouteRepository(
            DatabaseClient database,
            CredentialCipher credentialCipher,
            @Value("${gateway.audio.allow-mock:false}") boolean allowMock) {
        this.database = database;
        this.credentialCipher = credentialCipher;
        this.allowMock = allowMock;
    }

    AudioRouteRepository(DatabaseClient database, CredentialCipher credentialCipher) {
        this(database, credentialCipher, false);
    }

    /**
     * 该别名登记的计费单位，未登记时为空。
     *
     * <p>空不是「任意单位都可以」：调用方据此拒绝该请求。
     * 参见 gateway V92 与 V93。
     */
    Mono<String> billingUnit(String modelAlias) {
        if (modelAlias == null || modelAlias.isBlank()) {
            return Mono.empty();
        }
        return database.sql(ALIAS_UNIT_SQL)
                .bind("model_alias", modelAlias)
                .map((row, metadata) -> row.get("billing_unit", String.class))
                .one();
    }

    /**
     * 生效的音频套餐有权展示的模型，即便其余额已经耗尽。
     *
     * <p>配额耗尽有意不作为展示过滤器：预留与调用会在执行路径上强制执行它。
     * 本方法同样不主张某条供应商路由已就绪；{@link #candidates} 负责那些
     * 执行期的检查。
     */
    Flux<AudioModelCatalogRow> availableModels(
            UUID packageInstanceId, UUID tenantId, UUID userId) {
        if (packageInstanceId == null || tenantId == null || userId == null) {
            return Flux.empty();
        }
        return database.sql(AVAILABLE_AUDIO_MODELS_SQL)
                .bind("package_id", packageInstanceId)
                .bind("tenant_id", tenantId)
                .bind("user_id", userId)
                .map((row, metadata) -> new AudioModelCatalogRow(
                        row.get("alias", String.class),
                        row.get("display_name", String.class),
                        row.get("capabilities", String.class),
                        row.get("billing_unit", String.class)))
                .all();
    }

    /**
     * 实际上有资格接受音频调用的供应商路由。
     *
     * <p>它会在返回任何能够触达上游的东西之前，检查已启用的映射/渠道/供应商
     * 状态、健康度与凭证。因此一行目录记录绝不意味着一次调用会成功。
     *
     * @param apiKey 已认证的 key，用于模型允许列表检查
     * @param modelAlias 所请求的逻辑音频别名
     * @param capabilityJson 一个 jsonb 数组字面量，三个能力名之一
     */
    Flux<AudioRoute> candidates(
            ApiKeyService.ApiKeyContext apiKey, String modelAlias, String capabilityJson) {
        if (apiKey == null || modelAlias == null || modelAlias.isBlank()) {
            return Flux.empty();
        }
        if (apiKey.allowedModels() != null && !apiKey.allowedModels().contains(modelAlias)) {
            // 该 key 根本不被允许使用这个别名；没有任何渠道能让它合法。
            return Flux.empty();
        }
        return database.sql(AUDIO_CANDIDATES_SQL)
                .bind("model_alias", modelAlias)
                .bind("capability", capabilityJson)
                .bind("allow_mock", allowMock)
                .map((row, metadata) -> new AudioRoute(
                        row.get("channel_id", UUID.class),
                        row.get("provider_id", UUID.class),
                        row.get("provider_type", String.class),
                        row.get("base_url", String.class),
                        decrypt(row.get("encrypted_api_key", byte[].class)),
                        row.get("upstream_model", String.class),
                        row.get("timeout_ms", Integer.class)))
                .all();
    }

    private String decrypt(byte[] encrypted) {
        if (encrypted == null || encrypted.length == 0) {
            return null;
        }
        return credentialCipher.decrypt(encrypted);
    }

    /**
     * 一条解析出的音频路由，字段与三个适配器的 {@code Channel} 记录完全一致。
     *
     * <p>中性的类型在这里是刻意的：三个适配器各自声明 {@code Channel}
     * 是因为它们是三个接口，而不是因为渠道在音频内部真有三种形状。
     * 调用方把它翻译成所需适配器的 {@code Channel}，那是唯一知道
     * 目标适配器的地方。
     *
     * @param timeoutMs 渠道的超时预算，已带上默认值
     */
    record AudioRoute(
            UUID channelId,
            UUID providerId,
            String providerType,
            String baseUrl,
            String apiKey,
            String upstreamModel,
            Integer timeoutMs) {

        /** 该路由的超时预算，缺失时取 60 秒——与三个适配器读渠道列时的默认值一致。 */
        int timeoutOrDefault() {
            return timeoutMs == null || timeoutMs <= 0 ? 60_000 : timeoutMs;
        }
    }

    record AudioModelCatalogRow(
            String alias,
            String displayName,
            String capabilities,
            String billingUnit) { }
}
