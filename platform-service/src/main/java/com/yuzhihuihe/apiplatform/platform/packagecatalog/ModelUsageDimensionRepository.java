package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 网关价目表（rate card）的读模型：哪些模型别名按秒计费。
 *
 * <p>用于在不每次写入都对网关发起同步调用的前提下，
 * 推导某个套餐版本的用量维度。它只是建议性的：网关会在交付时
 * 重新校验，其自身的触发器会拒绝权益中把视频别名与
 * token 别名混在一起的版本。
 *
 * <p>没有对应行的模型即为 token 别名。刻意以「不存在」为默认，因此
 * 在首次价目表同步之前，token 目录仍可正常工作。
 */
@Repository
class ModelUsageDimensionRepository {

    private final JdbcTemplate jdbc;

    ModelUsageDimensionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Map<String, String> dimensionByModelCode() {
        Map<String, String> dimensions = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT model_code, usage_dimension
                FROM platform.model_usage_dimension
                """,
                result -> {
                    dimensions.put(result.getString("model_code"), result.getString("usage_dimension"));
                });
        return dimensions;
    }

    List<ModelDimensionRow> all() {
        return jdbc.query(
                """
                SELECT model_code, usage_dimension, resolution_tier,
                       effective_unit_cost, retail_unit_price, price_version
                FROM platform.model_usage_dimension
                ORDER BY model_code
                """,
                (result, rowNumber) -> new ModelDimensionRow(
                        result.getString("model_code"),
                        result.getString("usage_dimension"),
                        result.getString("resolution_tier"),
                        result.getObject("effective_unit_cost", Long.class),
                        result.getObject("retail_unit_price", Long.class),
                        result.getString("price_version")));
    }

    /**
     * 登记（或修正）某个别名所使用的计量维度。
     *
     * <p>按设计，没有对应行的别名就是 TOKEN 别名（{@link #dimensionByModelCode()}），
     * 因此通过控制台新增的视频别名，其套餐会被发布为 token 套餐，
     * 并在发放时被网关的权益触发器拒绝 —— 而那时距运维人员
     * 本可以看出原因的时点已过去很久。这次写入正是用来防止这种情况发生的。
     *
     * <p>价格相关列只是对网关价目表所含内容的记录；
     * 发布校验改为从网关读取实时数值，因此这些列可以从控制台填写，
     * 但它们并不是套餐定价所依据的数值。
     */
    void upsert(ModelDimensionRow row) {
        jdbc.update(
                """
                INSERT INTO platform.model_usage_dimension(
                    model_code, usage_dimension, resolution_tier,
                    effective_unit_cost, retail_unit_price, price_version, synced_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (model_code) DO UPDATE SET
                    usage_dimension = EXCLUDED.usage_dimension,
                    resolution_tier = EXCLUDED.resolution_tier,
                    effective_unit_cost = EXCLUDED.effective_unit_cost,
                    retail_unit_price = EXCLUDED.retail_unit_price,
                    price_version = EXCLUDED.price_version,
                    synced_at = now()
                """,
                row.modelCode(),
                row.usageDimension(),
                row.resolutionTier(),
                row.effectiveUnitCost(),
                row.retailUnitPrice(),
                row.priceVersion());
    }

    record ModelDimensionRow(
            String modelCode,
            String usageDimension,
            String resolutionTier,
            Long effectiveUnitCost,
            Long retailUnitPrice,
            String priceVersion) {
    }
}
