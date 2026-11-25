package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 读取由运维维护的、用于把关发布的按维度定价策略。
 *
 * <p>{@code platform.package_dimension_price_policy} 是用量维度的
 * 缩放系数、单位标签以及两个毛利下限的权威来源。它在每次
 * 创建版本和每次发布时都会被读取 —— 刻意不加缓存 —— 因此运维的
 * 修改会在下一次发布时生效，而不是在下次重启时生效。
 *
 * <p>在该仓储出现之前，这张表由 V54 写入却无人读取：
 * 守卫使用的是 {@link PackageMarginPolicy} 中的常量，而种子数据行恰好
 * 携带相同的数值，因此运维编辑该表完全不改变任何结果。
 *
 * <p>行不存在不是错误。它意味着该维度沿用已记录的兜底值
 * {@link PackageMarginPolicy}，这也是守卫在读取该表之前的行为。
 * 行存在但不可用则是另一回事，调用方对此失败即关闭
 * （fail-closed）。
 */
@Repository
class DimensionPricePolicyRepository {

    private final JdbcTemplate jdbc;

    DimensionPricePolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return 某个用量维度的行；当该维度没有对应行时为空。
     */
    Optional<Row> find(String usageDimension) {
        if (usageDimension == null || usageDimension.isBlank()) {
            return Optional.empty();
        }
        List<Row> rows = jdbc.query(
                """
                SELECT usage_dimension, quota_units_per_cost_unit, unit_label,
                       minimum_margin, warning_margin
                FROM platform.package_dimension_price_policy
                WHERE usage_dimension = ?
                """,
                (result, index) -> new Row(
                        result.getString("usage_dimension"),
                        result.getLong("quota_units_per_cost_unit"),
                        result.getString("unit_label"),
                        result.getBigDecimal("minimum_margin"),
                        result.getBigDecimal("warning_margin")),
                usageDimension);
        return rows.stream().findFirst();
    }

    /**
     * 策略表中的一行。
     *
     * <p>数值按读取到的原样携带，包括表上 CHECK 约束本应
     * 拒绝的数值：校验它们是守卫的职责，而守卫不得信任一个
     * 可被后续迁移、超级用户或手工编辑绕过的约束。
     */
    record Row(
            String usageDimension,
            long quotaUnitsPerCostUnit,
            String unitLabel,
            BigDecimal minimumMargin,
            BigDecimal warningMargin) {
    }
}
