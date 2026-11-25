package com.yuzhihuihe.apiplatform.gateway.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ProviderPriceQueryRepository {

    /**
     * 每个别名的最高输出成本，单位为分/百万 token。
     *
     * <p>这是套餐发布利润守卫用来定价的成本基础，因此它包含什么就决定了「最坏情况」的含义。 它刻意<b>不</b>按 {@code provider.enabled}
     * 过滤：被禁用的渠道是一种配置状态，而不是价格上限。 若该基础只统计当前启用的供应商，运营人员就能以 45% 的利润发布一个版本，随后为同一个别名启用
     * 更贵的渠道，而已发布的版本会在无声无息中变得比它获批时所依据的下限更薄——且没有重新校验能 发现它，因为已发布的版本是不可变的。按每个拥有价格行的供应商来定价，使这次批准成为关于该别名
     * 整个可路由成本的声明，而这正是面向客户的价格真正需要经受得住的。
     *
     * <p>{@code m.enabled} 以及模型级的 {@code model.enabled} 过滤条件仍然保留，因为被禁用的模型 根本不会被售卖。
     */
    static final String NON_PROMOTIONAL_OUTPUT_SQL =
            """
            WITH direct_prices AS (
                SELECT m.code,
                   max(ceil(price.output_price_per_million
                            * price.internal_exchange_rate)) AS output_price
                FROM gateway.model m
                JOIN gateway.provider_model_price price ON price.model_id = m.id
                JOIN gateway.provider provider ON provider.id = price.provider_id
                WHERE m.code IN (:model_codes)
                  AND m.routing_strategy = 'DIRECT'
                  AND m.enabled = true
                  AND price.effective_from <= now()
                  AND (price.effective_to IS NULL OR price.effective_to > now())
                GROUP BY m.code
            ), configured_components AS (
                SELECT DISTINCT composite.code,
                       CASE
                           WHEN jsonb_typeof(composite.routing_policy -> component.policy_key) = 'string'
                               THEN NULLIF(btrim(composite.routing_policy ->> component.policy_key), '')
                       END AS alias
                FROM gateway.model composite
                CROSS JOIN LATERAL (VALUES
                    ('textModel'),
                    ('imageModel'),
                    ('fileModel'),
                    ('mixedFinalModel')
                ) AS component(policy_key)
                WHERE composite.code IN (:model_codes)
                  AND composite.routing_strategy = 'COMPOSITE'
                  AND composite.enabled = true
            ), component_prices AS (
                SELECT component.code, component.alias,
                       max(ceil(price.output_price_per_million
                                * price.internal_exchange_rate)) AS output_price
                FROM configured_components component
                JOIN gateway.model_alias alias ON alias.alias = component.alias
                JOIN gateway.model model ON model.id = alias.model_id
                JOIN gateway.provider_model_price price ON price.model_id = model.id
                JOIN gateway.provider provider ON provider.id = price.provider_id
                WHERE model.enabled = true
                  AND price.effective_from <= now()
                  AND (price.effective_to IS NULL OR price.effective_to > now())
                GROUP BY component.code, component.alias
            ), composite_prices AS (
                SELECT component.code, max(price.output_price) AS output_price
                FROM configured_components component
                LEFT JOIN component_prices price
                  ON price.code = component.code AND price.alias = component.alias
                GROUP BY component.code
                HAVING count(*) = count(price.alias)
            )
            SELECT code, output_price FROM direct_prices
            UNION ALL
            SELECT code, output_price FROM composite_prices
            """;

    /**
     * 生成别名（视频秒数 / 图片张数 / 音频秒数）的有效单位成本，单位为分。
     *
     * <p>三个视图在这里合并，因为平台把三个维度都送到了同一条查询上： {@code PackageVersionService.dimensionPolicy} 对 {@code
     * VIDEO_SECOND}、{@code IMAGE} 与 {@code AUDIO_SECOND} 一视同仁。只读视频视图曾让每一个图片别名解析不到成本， 平台发布守卫随即以
     * {@code provider_price_missing} fail-closed。
     *
     * <p>三个维度的单位不同（分/秒 与 分/张），因此一个别名若同时存在两个维度的当前行， 任选一行都是静默的定价错误。{@code count(DISTINCT
     * usage_dimension) = 1} 正是为此： 跨维度的别名被整体丢弃，调用方随之 fail-closed。
     *
     * <p>同一维度内的多个尺寸档位不是这种错误，因此不再被一并丢弃。图片别名自 V115 起 可以持有两行当前费率（2K 与 1.5K）。此前那条 {@code count(*) = 1}
     * 会把按档位定价 本身判成故障：一加 1.5K 费率行，{@code image-basic} 就从上架校验里消失， 每一个图片版本都会被 {@code
     * provider_price_missing} 拒绝。
     *
     * <p>现在同一维度内取 {@code max(effective_unit_cost)}，即最贵的一档，也就是平台 实际承担的最坏情况。这与毛利守卫的一贯口径一致，而且永远不会低报成本。
     *
     * <p>每个维度在并集里带一个字面量标记，而不是去读视图的列：三个视图各自硬过滤了 自己那一维（V45 / V81 / V96），因此字面量与行内容不可能不一致。
     *
     * <p>数据库侧的两条不变式由迁移断言：一个别名不跨维度（V81 / V93）， 同一维度可以有多个档位行（V115）。
     */
    static final String EFFECTIVE_UNIT_COST_SQL =
            """
            SELECT cost.model_code, max(cost.effective_unit_cost) AS effective_unit_cost
            FROM (
                SELECT model_code, effective_unit_cost, 'VIDEO_SECOND' AS usage_dimension
                FROM gateway.video_effective_cost
                UNION ALL
                SELECT model_code, effective_unit_cost, 'IMAGE'
                FROM gateway.image_effective_cost
                UNION ALL
                SELECT model_code, effective_unit_cost, 'AUDIO_SECOND'
                FROM gateway.audio_effective_cost
            ) cost
            WHERE cost.model_code IN (:model_codes)
            GROUP BY cost.model_code
            HAVING count(DISTINCT cost.usage_dimension) = 1
            """;

    /**
     * 网关实际会向这些别名收取的零售价。
     *
     * <p>这是发布守卫此前缺失的另一方。守卫依据供应商<b>成本</b>为套餐定价，但向客户收取的金额来自 {@code
     * gateway.model_billing_price}——一张不同的表，由不同的管理端点写入，并且可能与该守卫所用
     * 的成本快照不一致。当两者不一致时，套餐会按一个永远收不到的利润率被发布：这些别名以低于成本的价格 售出，而守卫却报告 30-40%
     * 的健康水平。把收取价暴露出来，平台就能在发布时拒绝这种组合，而不是等到 月度报表里才发现它。
     *
     * <p>只返回 input 和 output 两列：只有这两个价格可与套餐守卫的成本基础相比较。分类价格（缓存命中、
     * 写入、推理）刻意不属于本契约，因为某个分类价格可以合法地低于输出价而不使该版本变得不安全。
     */
    static final String RETAIL_PRICE_SQL =
            """
            SELECT price.model_code, price.input_price_per_million, price.output_price_per_million
            FROM gateway.model_billing_price price
            WHERE price.model_code IN (:model_codes)
              AND price.effective_from <= now()
              AND (price.effective_to IS NULL OR price.effective_to > now())
            ORDER BY price.model_code, price.effective_from DESC, price.id DESC
            """;

    static final String SNAPSHOT_SQL =
            """
            SELECT current_price.id, current_price.provider_code, current_price.model_code,
                   current_price.input_price_per_million, current_price.output_price_per_million,
                   current_price.cache_read_price_per_million,
                   current_price.cache_write_price_per_million,
                   current_price.reasoning_price_per_million,
                   current_price.currency, current_price.internal_exchange_rate,
                   current_price.official_source, current_price.effective_from,
                   current_price.effective_to
            FROM (
                SELECT DISTINCT ON (provider.id, model.id)
                       price.id, provider.code AS provider_code, model.code AS model_code,
                       price.input_price_per_million, price.output_price_per_million,
                       price.cache_read_price_per_million,
                       price.cache_write_price_per_million,
                       price.reasoning_price_per_million,
                       price.currency, price.internal_exchange_rate, price.official_source,
                       price.effective_from, price.effective_to
                FROM gateway.provider_model_price price
                JOIN gateway.provider provider ON provider.id = price.provider_id
                JOIN gateway.model model ON model.id = price.model_id
                WHERE price.effective_from <= now()
                  AND (price.effective_to IS NULL OR price.effective_to > now())
                ORDER BY provider.id, model.id, price.effective_from DESC, price.id DESC
            ) current_price
            ORDER BY current_price.model_code, current_price.provider_code
            """;

    private final DatabaseClient database;

    public ProviderPriceQueryRepository(DatabaseClient database) {
        this.database = database;
    }

    /**
     * 生成别名的有效单位成本，单位为分：视频别名是分/秒，图片别名是分/张。
     *
     * <p>视频别名不带 {@code provider_model_price} 行，因为那些列是按百万 token 计量的，无法表达 每秒（或每张）成本。这是生成维度对应于 {@link
     * #nonPromotionalOutputPrices(java.util.Set)} 的部分： 套餐发布利润守卫需要一个以秒或张为单位的配额的成本基础，而这个视图提供的成本基础已经包含
     * 该维度自己的重试额度。
     *
     * <p>调用方必须知道自己正在定价的别名属于哪个维度：这个 map 以 model code 为键， 而数值的单位由该别名在费率卡上的维度决定。一个同时持有两个维度当前行的别名
     * 会被本查询丢弃（见 {@link #EFFECTIVE_UNIT_COST_SQL}），因此它绝不会拿到错误单位的数字。
     */
    Mono<Map<String, BigDecimal>> effectiveUnitCosts(Set<String> modelCodes) {
        return database.sql(EFFECTIVE_UNIT_COST_SQL)
                .bind("model_codes", modelCodes)
                .map(
                        (row, metadata) ->
                                Map.entry(
                                        row.get("model_code", String.class),
                                        row.get("effective_unit_cost", BigDecimal.class)))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new);
    }

    Mono<Map<String, BigDecimal>> nonPromotionalOutputPrices(Set<String> modelCodes) {
        return database.sql(NON_PROMOTIONAL_OUTPUT_SQL)
                .bind("model_codes", modelCodes)
                .map(
                        (row, metadata) ->
                                Map.entry(
                                        row.get("code", String.class),
                                        row.get("output_price", BigDecimal.class)))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new);
    }

    /**
     * @return 当前按别名收取的零售价，以 model code 为键。没有当前行的别名会缺席，调用方必须把它 当作「网关无法为该别名收费」，而不是「免费」。
     */
    Mono<Map<String, RetailPrice>> retailPrices(Set<String> modelCodes) {
        return database.sql(RETAIL_PRICE_SQL)
                .bind("model_codes", modelCodes)
                .map(
                        (row, metadata) ->
                                Map.entry(
                                        row.get("model_code", String.class),
                                        new RetailPrice(
                                                row.get("input_price_per_million", Long.class),
                                                row.get("output_price_per_million", Long.class))))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new);
    }

    /**
     * @param inputPricePerMillion 实际收取的零售价，单位为分/百万输入 token
     * @param outputPricePerMillion 实际收取的零售价，单位为分/百万输出 token
     */
    record RetailPrice(long inputPricePerMillion, long outputPricePerMillion) {}

    Flux<ProviderPriceSnapshot> snapshots() {
        return database.sql(SNAPSHOT_SQL)
                .map(
                        (row, metadata) ->
                                new ProviderPriceSnapshot(
                                        row.get("id", UUID.class),
                                        row.get("provider_code", String.class),
                                        row.get("model_code", String.class),
                                        row.get("input_price_per_million", Long.class),
                                        row.get("output_price_per_million", Long.class),
                                        row.get("cache_read_price_per_million", Long.class),
                                        row.get("cache_write_price_per_million", Long.class),
                                        row.get("reasoning_price_per_million", Long.class),
                                        row.get("currency", String.class),
                                        row.get("internal_exchange_rate", BigDecimal.class),
                                        row.get("official_source", String.class),
                                        row.get("effective_from", Instant.class),
                                        row.get("effective_to", Instant.class)))
                .all();
    }
}
