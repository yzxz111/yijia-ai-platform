package com.yuzhihuihe.apiplatform.gateway.usage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;

/**
 * 套餐权益视图需要的两张价目卡与档位登记表。
 *
 * <p><b>为什么是两张表而不是一张。</b>两者都在生产里活着，但各自的权威范围不同：
 *
 * <ul>
 *   <li>{@code gateway.model_billing_price} 是**钱包计费**读的那张卡
 *       （{@code ModelBillingPriceService} → {@code ModelBillingPriceRepository}）。
 *       只有早期那批 token 别名在里面；
 *   <li>{@code gateway.model_usage_price} 是**按维度**的费率卡：VIDEO_SECOND / IMAGE /
 *       AUDIO_SECOND 的每秒、每张零售价只在这里，视频与图片目录、按秒计费与按张结算
 *       读的都是它。V102 起新加的客户端文本别名（{@code chat-standard} /
 *       {@code chat-deep}）也只在这里有价。
 * </ul>
 *
 * <p>因此"这个别名的参考单价"没有单一来源，只能按维度取：token 先问计费卡、再回退
 * 费率卡（V51 之后费率卡上的 TOKEN 行已不再被计费读取，但新别名只有它有价），
 * 其余维度只问费率卡。选择规则写在 {@link QuotaEntitlementsService} 里，不在这里——
 * 这一层只负责把两个来源原样搬回来，不做解释。
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class QuotaEntitlementsRepository {

    private final DatabaseClient databaseClient;

    QuotaEntitlementsRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /**
     * 计费卡当前生效的 token 零售价（分/百万 token）。
     *
     * <p>按时间窗口取当前行，而不是只判 {@code effective_to IS NULL}：一条已经排定
     * 生效时间、但尚未到点的行不该被当成现价。V34 的
     * {@code uq_model_billing_price_current} 保证每个别名至多一行，因此这里不必去重。
     */
    Flux<TokenPriceRow> tokenPrices() {
        return databaseClient.sql("""
                        SELECT model_code, input_price_per_million, output_price_per_million,
                               cache_hit_price_per_million, cache_write_price_per_million,
                               reasoning_price_per_million
                        FROM gateway.model_billing_price
                        WHERE effective_from <= now()
                          AND (effective_to IS NULL OR effective_to > now())
                        """)
                .map((row, metadata) -> new TokenPriceRow(
                        row.get("model_code", String.class),
                        row.get("input_price_per_million", Long.class),
                        row.get("output_price_per_million", Long.class),
                        row.get("cache_hit_price_per_million", Long.class),
                        row.get("cache_write_price_per_million", Long.class),
                        row.get("reasoning_price_per_million", Long.class)))
                .all();
    }

    /**
     * 维度费率卡的当前生效行。
     *
     * <p>一个别名在一个维度上可能有多档（{@code uq_model_usage_price_current} 的键是
     * 「别名 × 维度 × 档位」），例如 {@code video-minimax} 的 480P 与 720P 是两条独立
     * 的行。这一层把全部当前行都取回来，由上层按别名登记的售卖档位（{@link #aliasTiers()}）
     * 选一行，而不是在这里 LIMIT 1 —— 那样选出来的行会取决于返回顺序。
     *
     * <p><b>为什么这里不判 {@code effective_from <= now()}，而 {@link #tokenPrices()}
     * 判了。</b>两张表的"当前"定义不同：这张表的唯一索引就是
     * {@code uq_model_usage_price_current ... WHERE effective_to IS NULL}，视频、图片、
     * 音频三条计费路径也都按这个条件读它，多加一个时间谓词会让本接口的"现价"与
     * 它们实际计费所用的那一行出现分歧。计费卡那边则相反：它的读取方
     * （{@code ModelBillingPriceRepository.findCurrent}）本身就带时间窗口，
     * 因此这里逐字对齐各自的读取方，而不是在这里统一。
     */
    Flux<UnitPriceRow> unitPrices() {
        return databaseClient.sql("""
                        SELECT model_code, usage_dimension, resolution_tier, unit_price,
                               minimum_units, minimum_charge,
                               input_price_per_million, output_price_per_million,
                               cache_hit_price_per_million, cache_write_price_per_million,
                               reasoning_price_per_million
                        FROM gateway.model_usage_price
                        WHERE effective_to IS NULL
                        """)
                .map((row, metadata) -> new UnitPriceRow(
                        row.get("model_code", String.class),
                        row.get("usage_dimension", String.class),
                        row.get("resolution_tier", String.class),
                        row.get("unit_price", Long.class),
                        row.get("minimum_units", Long.class),
                        row.get("minimum_charge", Long.class),
                        row.get("input_price_per_million", Long.class),
                        row.get("output_price_per_million", Long.class),
                        row.get("cache_hit_price_per_million", Long.class),
                        row.get("cache_write_price_per_million", Long.class),
                        row.get("reasoning_price_per_million", Long.class)))
                .all();
    }

    /**
     * 每个按秒/按张计费的别名**登记在卖**的那一档。
     *
     * <p>只有视频与图片两张登记表：音频没有档位阶梯（{@code audio_alias_tier} 的列是
     * {@code billing_unit}，恒为 {@code SECOND}，根本没有 {@code resolution_tier}），
     * 它的费率行档位就是空串，而 {@code ck_model_usage_price_tier}（V92）也要求
     * AUDIO_SECOND 行必须是空档位——因此音频别名本来就只有一行，不需要档位来选。
     *
     * <p>两张表的主键都是 {@code model_code}，即一个别名只卖一档；UNION 起来只是让上层
     * 少查一次，不是为了允许一个别名同时出现在两张表里。真的同时出现说明档位登记已经
     * 坏了，上层取先到的那一行，而**单位**仍取自额度行的 {@code usage_dimension}，
     * 不是这张表。
     */
    Flux<AliasTierRow> aliasTiers() {
        return databaseClient.sql("""
                        SELECT model_code, resolution_tier FROM gateway.video_alias_tier
                        UNION ALL
                        SELECT model_code, resolution_tier FROM gateway.image_alias_tier
                        """)
                .map((row, metadata) -> new AliasTierRow(
                        row.get("model_code", String.class),
                        row.get("resolution_tier", String.class)))
                .all();
    }

    record TokenPriceRow(
            String modelCode,
            Long inputPerMillion,
            Long outputPerMillion,
            Long cacheHitPerMillion,
            Long cacheWritePerMillion,
            Long reasoningPerMillion) {}

    record UnitPriceRow(
            String modelCode,
            String usageDimension,
            String resolutionTier,
            Long unitPrice,
            Long minimumUnits,
            Long minimumCharge,
            Long inputPerMillion,
            Long outputPerMillion,
            Long cacheHitPerMillion,
            Long cacheWritePerMillion,
            Long reasoningPerMillion) {}

    record AliasTierRow(String modelCode, String resolutionTier) {}
}
