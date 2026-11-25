package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 估算某个套餐版本能否按标价售出。
 *
 * <p>该估算刻意保守：每一项权限都按当前最贵的路由计价，
 * 并假定整个额度都被消耗完，
 * 这是一个套餐可能产生的最坏情况。
 *
 * <p>存在两个用量维度。TOKEN 套餐的额度以 token 计，其成本
 * 基准是每百万 token 的价格；VIDEO_SECOND 套餐的额度以秒计，
 * 其成本基准是每秒的价格。一旦显式携带换算因子，
 * 两者的算式完全相同，这正是 {@link DimensionPolicy}
 * 所做的。
 *
 * <p>换算因子与两个下限都来自
 * {@code platform.package_dimension_price_policy}，由
 * {@link DimensionPricePolicyRepository} 在每次创建和每次发布时按用量维度读取，
 * 并由 {@link #fromPolicyRow} 转换为 {@link DimensionPolicy}。因此运维编辑
 * 该表就会改变发布的结果，而这正是它的 V54 注释一直以来的说法：
 * 在有两个发布版本期间该注释是错的，没有任何代码读取该表，
 * 当时实际生效的下限就是下面的这些常量。
 *
 * <p>这些常量仅作为某个维度没有对应行时的回退值而保留，也作为
 * 回退路径被断言时对照的取值。对于存在但不可用的行，
 * 绝不会用它们静默替换：参见 {@link #fromPolicyRow}。
 */
@Component
public class PackageMarginPolicy {

    /**
     * TOKEN 维度的回退换算因子与下限，仅当
     * {@code platform.package_dimension_price_policy} 没有 TOKEN 行时使用：每百万
     * token 多少分的价格、30% 的硬下限和 35% 的预警下限。
     *
     * <p>这些是守卫在读取该表之前使用的取值，而预置的
     * TOKEN 行携带的正是它们，因此在预置目录上回退值与表值
     * 无法区分。
     */
    static final long FALLBACK_TOKEN_QUOTA_UNITS_PER_COST_UNIT = 1_000_000L;
    static final BigDecimal FALLBACK_TOKEN_MINIMUM_MARGIN = new BigDecimal("0.30000");
    static final BigDecimal FALLBACK_TOKEN_WARNING_MARGIN = new BigDecimal("0.35000");

    /**
     * 秒维度（VIDEO_SECOND 和 IMAGE）的回退换算因子与下限，
     * 仅当该表没有它们的行时使用：对以秒计的额度按每秒多少分
     * 计价、25% 的硬下限和 30% 的预警下限。较低的下限是刻意的——
     * 视频转售市场的成交价低于一方标价，所以在最便宜的
     * 档位上若要保持竞争力，30% 的下限是不可达的。
     */
    static final long FALLBACK_SECOND_QUOTA_UNITS_PER_COST_UNIT = 1L;
    static final BigDecimal FALLBACK_SECOND_MINIMUM_MARGIN = new BigDecimal("0.25000");
    static final BigDecimal FALLBACK_SECOND_WARNING_MARGIN = new BigDecimal("0.30000");

    /**
     * 为一个以某一维度表示的额度定价。
     *
     * @param unitCosts 按模型代码给出的成本基准，单位为分，对应本维度的一个成本单位
     * @param quotaUnitsPerCostUnit 多少个额度单位构成一个成本单位
     *        （每百万 token 多少分的价格为 1_000_000，每秒多少分的价格为 1）
     * @param minimumMargin 硬下限，低于它的版本不得发布
     * @param warningMargin 预警下限，低于它的版本会带预警发布
     * @param unitLabel 该维度的单位，仅用于描述它的那些消息
     */
    public record DimensionPolicy(
            String usageDimension,
            Map<String, BigDecimal> unitCosts,
            long quotaUnitsPerCostUnit,
            BigDecimal minimumMargin,
            BigDecimal warningMargin,
            String unitLabel) {

        public DimensionPolicy {
            if (usageDimension == null || usageDimension.isBlank()) {
                throw new IllegalArgumentException("invalid_package_margin_input");
            }
            if (quotaUnitsPerCostUnit <= 0) {
                throw new IllegalArgumentException("invalid_package_margin_input");
            }
            if (minimumMargin == null || warningMargin == null
                    || minimumMargin.signum() < 0 || minimumMargin.compareTo(BigDecimal.ONE) >= 0
                    || warningMargin.signum() < 0 || warningMargin.compareTo(BigDecimal.ONE) >= 0
                    || warningMargin.compareTo(minimumMargin) < 0) {
                throw new IllegalArgumentException("invalid_package_margin_input");
            }
            unitCosts = unitCosts == null ? Map.of() : Map.copyOf(unitCosts);
            unitLabel = unitLabel == null || unitLabel.isBlank() ? "units" : unitLabel;
        }
    }

    /**
     * token 维度的回退值：面对以 token 计的额度，按每百万 token
     * 多少分的价格计价。仅当策略表没有 TOKEN 行时使用。
     */
    public static DimensionPolicy tokenPolicy(Map<String, BigDecimal> costCentsPerMillion) {
        return new DimensionPolicy(
                "TOKEN", costCentsPerMillion, FALLBACK_TOKEN_QUOTA_UNITS_PER_COST_UNIT,
                FALLBACK_TOKEN_MINIMUM_MARGIN, FALLBACK_TOKEN_WARNING_MARGIN, "tokens");
    }

    /**
     * 秒维度的回退值：面对以秒计的额度，按每秒多少分的成本计价，
     * 并采用视频转售市场所需的较低下限。仅当策略表
     * 没有 VIDEO_SECOND 行时使用。
     */
    public static DimensionPolicy secondPolicy(Map<String, BigDecimal> costCentsPerSecond) {
        return secondsPolicy("VIDEO_SECOND", costCentsPerSecond);
    }

    /**
     * 图片维度的回退值：面对以张计的额度，按每张多少分的成本计价。
     *
     * <p>换算系数与两个下限与秒维度相同——一张图与一秒视频都是
     * 一次生成，成本量级也都在几十分这一档——但单位标签必须是
     * 「张」。V54 建立该表时把 IMAGE 行照抄成 {@code 'seconds'}，
     * 因为当时的 CHECK 只有 tokens/seconds 两个词；那是单位被写错的
     * 历史遗留，不是可以继承的约定。这个标签正是用来描述单位的，
     * 所以按张计费就必须说张：拒绝消息与控制台都读它。
     *
     * <p>仅当策略表没有 IMAGE 行时使用。
     */
    public static DimensionPolicy imagePolicy(Map<String, BigDecimal> costCentsPerImage) {
        return new DimensionPolicy(
                "IMAGE", costCentsPerImage, FALLBACK_SECOND_QUOTA_UNITS_PER_COST_UNIT,
                FALLBACK_SECOND_MINIMUM_MARGIN, FALLBACK_SECOND_WARNING_MARGIN, "images");
    }

    /**
     * 针对在 {@code platform.package_dimension_price_policy} 中
     * 没有对应行的用量维度，这里给出有文档记载的回退值。
     *
     * <p>目录可能承载的每个维度都有一个回退值，因此该回退绝不依赖
     * 一行被误删的记录：守卫会降级到读取该表之前
     * 的行为，而绝不会降级到「完全没有下限」。
     *
     * @throws IllegalArgumentException 对该守卫无法定价的维度抛出
     */
    public static DimensionPolicy fallbackPolicy(
            String usageDimension, Map<String, BigDecimal> unitCosts) {
        return switch (usageDimension == null ? "" : usageDimension) {
            case "TOKEN" -> tokenPolicy(unitCosts);
            case "VIDEO_SECOND" -> secondPolicy(unitCosts);
            case "IMAGE" -> imagePolicy(unitCosts);
            default -> throw new IllegalArgumentException("invalid_package_margin_input");
        };
    }

    /**
     * 根据 {@code platform.package_dimension_price_policy} 的一行
     * 构建守卫用于定价的策略。
     *
     * <p>存在但无法使用的行会失败即关闭（fail-closed），消息中会带上
     * 该维度以及出问题的列。有两种替代方案被否决了：静默回退到
     * 这些常量，等于交给运维一个比他们自认为已配置的更弱的守卫——
     * 一个本意是抬高下限的笔误会被忽略——而接受这个坏行
     * 则会用一个毫无意义的下限来定价。该表自己的
     * {@code ck_package_dimension_policy_values} 已经会拒绝这些形态；这是
     * 守卫自身的防线，因此被放宽的约束、超级用户写入或将来的写入方
     * 都无法悄悄去掉这个下限。
     *
     * @throws MarginPolicyException 当该行不可用时，携带
     *         {@code invalid_package_dimension_price_policy}
     */
    public static DimensionPolicy fromPolicyRow(
            String usageDimension,
            Map<String, BigDecimal> unitCosts,
            long quotaUnitsPerCostUnit,
            BigDecimal minimumMargin,
            BigDecimal warningMargin,
            String unitLabel) {
        if (quotaUnitsPerCostUnit <= 0) {
            throw invalidPolicyRow(
                    usageDimension, "quota_units_per_cost_unit=" + quotaUnitsPerCostUnit);
        }
        if (minimumMargin == null || minimumMargin.signum() < 0
                || minimumMargin.compareTo(BigDecimal.ONE) >= 0) {
            throw invalidPolicyRow(usageDimension, "minimum_margin=" + minimumMargin);
        }
        if (warningMargin == null || warningMargin.signum() < 0
                || warningMargin.compareTo(BigDecimal.ONE) >= 0) {
            throw invalidPolicyRow(usageDimension, "warning_margin=" + warningMargin);
        }
        if (warningMargin.compareTo(minimumMargin) < 0) {
            throw invalidPolicyRow(usageDimension,
                    "warning_margin_below_minimum_margin:warning=" + warningMargin
                            + ":minimum=" + minimumMargin);
        }
        return new DimensionPolicy(usageDimension, unitCosts, quotaUnitsPerCostUnit,
                minimumMargin, warningMargin, unitLabel);
    }

    private static DimensionPolicy secondsPolicy(
            String usageDimension, Map<String, BigDecimal> unitCosts) {
        return new DimensionPolicy(
                usageDimension, unitCosts, FALLBACK_SECOND_QUOTA_UNITS_PER_COST_UNIT,
                FALLBACK_SECOND_MINIMUM_MARGIN, FALLBACK_SECOND_WARNING_MARGIN, "seconds");
    }

    private static MarginPolicyException invalidPolicyRow(String usageDimension, String detail) {
        return new MarginPolicyException(
                "invalid_package_dimension_price_policy:" + usageDimension + ":" + detail);
    }

    /**
     * @param priceAmount 该版本的标价，单位为分
     * @param totalQuota 整个套餐的额度，单位为 {@code policy} 的单位
     * @param permissions 该版本的模型权限
     * @param policy 该维度的成本基准、换算因子与毛利下限
     */
    public MarginAssessment assess(
            long priceAmount,
            long totalQuota,
            Set<PackageVersionService.ModelPermission> permissions,
            DimensionPolicy policy) {
        if (priceAmount < 0 || totalQuota <= 0 || permissions == null || permissions.isEmpty()) {
            throw new IllegalArgumentException("invalid_package_margin_input");
        }
        if (policy == null) {
            throw new IllegalArgumentException("invalid_package_margin_input");
        }

        long remaining = totalQuota;
        BigDecimal cost = BigDecimal.ZERO;
        var pricedPermissions = permissions.stream()
                .map(permission -> new PricedPermission(
                        permission,
                        requirePrice(permission.modelCode(), policy)))
                .sorted(Comparator.comparing(PricedPermission::price).reversed())
                .toList();
        for (PricedPermission priced : pricedPermissions) {
            if (remaining == 0) {
                break;
            }
            Long modelQuota = priced.permission().modelTokenQuota();
            long capacity = modelQuota == null ? remaining : Math.min(modelQuota, remaining);
            cost = cost.add(priced.price()
                    .multiply(BigDecimal.valueOf(capacity))
                    .divide(BigDecimal.valueOf(policy.quotaUnitsPerCostUnit()),
                            10, RoundingMode.HALF_UP));
            remaining -= capacity;
        }

        long estimatedCostAmount = cost.setScale(0, RoundingMode.CEILING).longValueExact();
        if (priceAmount == 0) {
            return new MarginAssessment(null, estimatedCostAmount, false, true);
        }
        BigDecimal margin = BigDecimal.valueOf(priceAmount - estimatedCostAmount)
                .divide(BigDecimal.valueOf(priceAmount), 5, RoundingMode.HALF_UP);
        return new MarginAssessment(
                margin,
                estimatedCostAmount,
                margin.compareTo(policy.warningMargin()) < 0,
                margin.compareTo(policy.minimumMargin()) >= 0);
    }

    private BigDecimal requirePrice(String modelCode, DimensionPolicy policy) {
        BigDecimal price = policy.unitCosts().get(modelCode);
        if (price == null || price.signum() <= 0) {
            // 成本缺失是发布层面的问题，而不是服务端故障：管理员无法
            // 发布一个成本未知的版本，并且响应必须说明是哪个模型、
            // 哪个维度没有定价。此前这里抛出 IllegalStateException，
            // 表现为 HTTP 500。
            //
            // ZERO 被拒绝的理由与缺失相同，而它此前是被接受的。
            // 零成本基准会让 estimatedCostAmount 为 0 并报告 100% 的毛利率，于是
            // 任何有价格的版本都能顺利发布——而客户的实际用量却由
            // 路由解析到的那个真实提供方承担。零是可以达到的：
            // ck_provider_price 允许 output_price_per_million >= 0，
            // ck_model_usage_price_values 允许 provider_unit_cost >= 0，而一个桩提供方
            // 或尚未谈妥的提供方记录正好就是 0。一个确实不花钱的路由
            // 在这里与一个没人录入过的成本无法区分，而
            // 在涉及资金的路径上，对「无成本」的安全解读就是「拒绝售卖」。
            throw new MarginPolicyException(
                    "provider_price_missing:" + modelCode + ":" + policy.usageDimension());
        }
        return price;
    }

    private record PricedPermission(
            PackageVersionService.ModelPermission permission,
            BigDecimal price) {
    }

    public record MarginAssessment(
            BigDecimal marginRatio,
            long estimatedCostAmount,
            boolean warning,
            boolean publishable) {
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public static class MarginPolicyException extends IllegalArgumentException {
        public MarginPolicyException(String message) {
            super(message);
        }
    }
}
