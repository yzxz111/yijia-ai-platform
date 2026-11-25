package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 从 {@code platform.package_dimension_price_policy} 的一行转换为
 * 守卫用于定价的 policy，且不依赖数据库。
 *
 * <p>这里有两个契约。没有对应行的维度所得到的兜底行为，是
 * 守卫在读取该表之前就有的行为，它是有意记录的，而不是偶然如此。
 * 存在但不可用的一行则失败即关闭（fail-closed）：它绝不会被
 * 兜底值静默替换，因为一位把下限打错字的运维绝不能最终得到一个
 * 比其配置更弱的守卫。
 */
class PackageMarginPolicyTest {

    private static final Map<String, BigDecimal> COSTS = Map.of("text-pro", new BigDecimal("680.00"));

    @Test
    void fallbackTokenPolicyIsTheDocumentedConstant() {
        PackageMarginPolicy.DimensionPolicy policy = PackageMarginPolicy.tokenPolicy(COSTS);

        assertThat(policy.usageDimension()).isEqualTo("TOKEN");
        assertThat(policy.quotaUnitsPerCostUnit()).isEqualTo(1_000_000L);
        assertThat(policy.unitLabel()).isEqualTo("tokens");
        assertThat(policy.minimumMargin()).isEqualByComparingTo("0.30000");
        assertThat(policy.warningMargin()).isEqualByComparingTo("0.35000");
    }

    @Test
    void fallbackSecondPolicyIsTheDocumentedConstant() {
        PackageMarginPolicy.DimensionPolicy policy = PackageMarginPolicy.secondPolicy(COSTS);

        assertThat(policy.usageDimension()).isEqualTo("VIDEO_SECOND");
        assertThat(policy.quotaUnitsPerCostUnit()).isEqualTo(1L);
        assertThat(policy.unitLabel()).isEqualTo("seconds");
        assertThat(policy.minimumMargin()).isEqualByComparingTo("0.25000");
        assertThat(policy.warningMargin()).isEqualByComparingTo("0.30000");
    }

    /**
     * 目录能承载的每一个维度都有兜底值，包括 IMAGE；且每个都保持自己的名字，
     * 这样一次拒绝就能指出实际未定价的那个维度。
     */
    @Test
    void fallbackPolicyCoversEveryDimensionAndRejectsUnknownOnes() {
        assertThat(PackageMarginPolicy.fallbackPolicy("TOKEN", COSTS).usageDimension())
                .isEqualTo("TOKEN");
        assertThat(PackageMarginPolicy.fallbackPolicy("VIDEO_SECOND", COSTS).usageDimension())
                .isEqualTo("VIDEO_SECOND");

        PackageMarginPolicy.DimensionPolicy image =
                PackageMarginPolicy.fallbackPolicy("IMAGE", COSTS);
        assertThat(image.usageDimension()).isEqualTo("IMAGE");
        assertThat(image.quotaUnitsPerCostUnit()).isEqualTo(1L);
        assertThat(image.unitLabel()).isEqualTo("images");
        assertThat(image.minimumMargin()).isEqualByComparingTo("0.25000");

        assertThatThrownBy(() -> PackageMarginPolicy.fallbackPolicy("AUDIO_SECOND", COSTS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyRowValuesBecomeThePolicy() {
        PackageMarginPolicy.DimensionPolicy policy = PackageMarginPolicy.fromPolicyRow(
                "VIDEO_SECOND", COSTS, 1L,
                new BigDecimal("0.12000"), new BigDecimal("0.20000"), "seconds");

        assertThat(policy.usageDimension()).isEqualTo("VIDEO_SECOND");
        assertThat(policy.quotaUnitsPerCostUnit()).isEqualTo(1L);
        assertThat(policy.minimumMargin()).isEqualByComparingTo("0.12000");
        assertThat(policy.warningMargin()).isEqualByComparingTo("0.20000");
        assertThat(policy.unitLabel()).isEqualTo("seconds");
    }

    @Test
    void aPolicyRowWithoutAPositiveScalingFactorFailsClosed() {
        assertThatThrownBy(() -> PackageMarginPolicy.fromPolicyRow(
                "TOKEN", COSTS, 0L,
                new BigDecimal("0.30000"), new BigDecimal("0.35000"), "tokens"))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("invalid_package_dimension_price_policy:TOKEN")
                .hasMessageContaining("quota_units_per_cost_unit=0");
    }

    @Test
    void aPolicyRowWhoseWarningFloorIsBelowItsMinimumFailsClosed() {
        assertThatThrownBy(() -> PackageMarginPolicy.fromPolicyRow(
                "IMAGE", COSTS, 1L,
                new BigDecimal("0.40000"), new BigDecimal("0.35000"), "seconds"))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("invalid_package_dimension_price_policy:IMAGE")
                .hasMessageContaining("warning_margin_below_minimum_margin")
                .hasMessageContaining("warning=0.35000")
                .hasMessageContaining("minimum=0.40000");
    }

    /**
     * [0, 1) 是该表的 CHECK 约束所强制的范围。守卫重复这一约束，使一个
     * 被放宽的约束或手写的一行无法依据无意义的下限来定价：
     * 负下限会拒绝一切，而下限为 1 会接受任何有成本的
     * 东西——但恰好为 1 的毛利将意味着「绝不发布」，那是
     * 配置错误，而不是策略。
     */
    @Test
    void aPolicyRowWithAMarginOutsideTheUnitIntervalFailsClosed() {
        assertThatThrownBy(() -> PackageMarginPolicy.fromPolicyRow(
                "TOKEN", COSTS, 1_000_000L,
                new BigDecimal("-0.01000"), new BigDecimal("0.35000"), "tokens"))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("minimum_margin=-0.01000");

        assertThatThrownBy(() -> PackageMarginPolicy.fromPolicyRow(
                "TOKEN", COSTS, 1_000_000L,
                new BigDecimal("0.30000"), new BigDecimal("1.00000"), "tokens"))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("warning_margin=1.00000");

        assertThatThrownBy(() -> PackageMarginPolicy.fromPolicyRow(
                "TOKEN", COSTS, 1_000_000L,
                new BigDecimal("1.00000"), new BigDecimal("1.00000"), "tokens"))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("minimum_margin=1.00000");

        assertThatThrownBy(() -> PackageMarginPolicy.fromPolicyRow(
                "TOKEN", COSTS, 1_000_000L, null, new BigDecimal("0.35000"), "tokens"))
                .isInstanceOf(PackageMarginPolicy.MarginPolicyException.class)
                .hasMessageContaining("minimum_margin=null");
    }
}
