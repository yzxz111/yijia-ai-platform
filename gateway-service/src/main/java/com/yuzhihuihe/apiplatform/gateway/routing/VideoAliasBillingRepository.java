package com.yuzhihuihe.apiplatform.gateway.routing;

import java.math.BigDecimal;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 写入一个视频别名在能够售卖之前所需的那两行记录。
 *
 * <p>{@code gateway.video_alias_tier} 是该别名售卖的唯一定档 —— 即
 * {@code VideoGenerationService} 强制执行的映射，因此一个按 480P 定价的套餐
 * 不能花在一次 1080P 渲染上。{@code gateway.model_usage_price} 是每秒成本与零售价，
 * 套餐发布守卫据此定价，并通过 {@code gateway.video_effective_cost} 和
 * {@code /internal/prices/effective-unit-cost} 暴露给平台。
 *
 * <p>在本仓库出现之前，这两者都只能靠迁移写入：运维人员可以添加一个视频别名、
 * 在控制台里接好它的供应商与凭证，却仍然无法售卖它 —— 网关拒绝每一个请求
 * （没有注册定档），平台也拒绝发布授予该别名的套餐（没有成本依据）。
 * 它们正是 V54/V40 迁移为内置别名种下的那两行，只不过这里改为从控制台写入。
 *
 * <p>价目表只会被取代，绝不会被编辑：当前行被关闭（{@code effective_to}），
 * 并在新的 {@code price_version} 下插入新的一行，因此一笔正按旧版本结算的扣费
 * 仍然能够解析成功。这与迁移历史所遵循的纪律相同（V46/V47/V50）。
 */
@Repository
class VideoAliasBillingRepository {

    static final String IS_VIDEO_ALIAS_SQL =
            """
            SELECT code FROM gateway.model
            WHERE code = :model_code AND capabilities @> '["VIDEO"]'::jsonb
            """;

    static final String BILLING_SQL =
            """
            SELECT tier.model_code,
                   tier.resolution_tier,
                   price.provider_unit_cost,
                   price.unit_price,
                   price.retry_rate,
                   price.target_margin_ratio,
                   price.min_absolute_margin,
                   price.price_version
            FROM gateway.video_alias_tier tier
            LEFT JOIN gateway.video_effective_cost price
              ON price.model_code = tier.model_code
            """;

    static final String CLOSE_CURRENT_SQL =
            """
            UPDATE gateway.model_usage_price
            SET effective_to = now()
            WHERE model_code = :model_code
              AND usage_dimension = 'VIDEO_SECOND'
              AND effective_to IS NULL
            """;

    static final String INSERT_BILLING_SQL =
            """
            INSERT INTO gateway.model_usage_price(
                id, model_code, usage_dimension, resolution_tier,
                minimum_units, minimum_charge, unit_price,
                provider_unit_cost, retry_rate, target_margin_ratio, min_absolute_margin,
                price_version, effective_from, official_source)
            VALUES (gen_random_uuid(), :model_code, 'VIDEO_SECOND', :resolution_tier,
                    1, :unit_price, :unit_price,
                    :provider_unit_cost, :retry_rate, :target_margin_ratio, :min_absolute_margin,
                    :price_version, now(), 'console video billing')
            """;

    static final String UPSERT_TIER_SQL =
            """
            INSERT INTO gateway.video_alias_tier(model_code, resolution_tier)
            VALUES (:model_code, :resolution_tier)
            ON CONFLICT (model_code) DO UPDATE
            SET resolution_tier = EXCLUDED.resolution_tier,
                registered_at = now()
            """;

    private final DatabaseClient database;

    VideoAliasBillingRepository(DatabaseClient database) {
        this.database = database;
    }

    /** @return 别名存在且支持 VIDEO 时返回其 code，否则为空。 */
    Mono<String> videoAlias(String modelCode) {
        return database.sql(IS_VIDEO_ALIAS_SQL)
                .bind("model_code", modelCode)
                .map((row, metadata) -> row.get("code", String.class))
                .one();
    }

    Flux<GatewayAdminResponses.VideoAliasBillingView> all() {
        return database.sql(BILLING_SQL + " ORDER BY tier.model_code")
                .map(VideoAliasBillingRepository::toView)
                .all();
    }

    Mono<GatewayAdminResponses.VideoAliasBillingView> one(String modelCode) {
        return database.sql(BILLING_SQL + " WHERE tier.model_code = :model_code")
                .bind("model_code", modelCode)
                .map(VideoAliasBillingRepository::toView)
                .one();
    }

    Mono<Long> closeCurrentBilling(String modelCode) {
        return database.sql(CLOSE_CURRENT_SQL)
                .bind("model_code", modelCode)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> insertBilling(
            String modelCode,
            String resolutionTier,
            int providerUnitCost,
            int unitPrice,
            BigDecimal retryRate,
            BigDecimal targetMarginRatio,
            int minAbsoluteMargin,
            String priceVersion) {
        return database.sql(INSERT_BILLING_SQL)
                .bind("model_code", modelCode)
                .bind("resolution_tier", resolutionTier)
                .bind("provider_unit_cost", providerUnitCost)
                .bind("unit_price", unitPrice)
                .bind("retry_rate", retryRate)
                .bind("target_margin_ratio", targetMarginRatio)
                .bind("min_absolute_margin", minAbsoluteMargin)
                .bind("price_version", priceVersion)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> registerTier(String modelCode, String resolutionTier) {
        return database.sql(UPSERT_TIER_SQL)
                .bind("model_code", modelCode)
                .bind("resolution_tier", resolutionTier)
                .fetch()
                .rowsUpdated();
    }

    private static GatewayAdminResponses.VideoAliasBillingView toView(
            io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
        Long priceVersionId = row.get("unit_price", Long.class);
        return new GatewayAdminResponses.VideoAliasBillingView(
                row.get("model_code", String.class),
                row.get("resolution_tier", String.class),
                row.get("provider_unit_cost", Long.class) == null
                        ? null
                        : row.get("provider_unit_cost", Long.class).intValue(),
                priceVersionId == null ? null : priceVersionId.intValue(),
                row.get("retry_rate", BigDecimal.class),
                row.get("target_margin_ratio", BigDecimal.class),
                row.get("min_absolute_margin", Long.class) == null
                        ? null
                        : row.get("min_absolute_margin", Long.class).intValue(),
                row.get("price_version", String.class));
    }
}
