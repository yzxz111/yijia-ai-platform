package com.yuzhihuihe.apiplatform.gateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModelBillingPriceCalculatorTest {

    @Test
    void rejectsZeroInputOrOutputPrice() {
        assertThatThrownBy(() -> ModelBillingPriceCalculator.amount(1, 1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_model_billing_values");
        assertThatThrownBy(() -> ModelBillingPriceCalculator.amount(1, 1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_model_billing_values");
    }

    @Test
    void pricesEveryCategoryAgainstItsOwnRate() {
        // text-basic，高峰：未命中 30，缓存写入 38，缓存读取 8，输出 110 每 M。
        // 使用 100M 量级的计数，使每个类别的贡献在「分」上都清晰可辨。
        long amount = ModelBillingPriceCalculator.amount(
                3_000_000, 1_000_000, 1_000_000, 1_000_000, 400_000,
                30, 110, 8, 38, 110);

        // 1,000,000 未命中 * 30 = 30,000,000
        // 1,000,000 缓存读取 * 8 = 8,000,000
        // 1,000,000 缓存写入 * 38 = 38,000,000
        //   600,000 普通输出 * 110 = 66,000,000
        //   400,000 推理 * 110 = 44,000,000
        // 合计 186,000,000 每百万 -> 186 分
        assertThat(amount).isEqualTo(186L);
    }

    @Test
    void unsetCategoryPricesFallBackToTheBaseCategoryPrice() {
        // 这就是如下保证：在按类别定价存在之前发布的价格行，收取的费用与它此前
        // 收取的完全一致。
        long withCategories = ModelBillingPriceCalculator.amount(
                3_000_000, 1_000_000, 1_000_000, 1_000_000, 400_000, 30, 110, 0, 0, 0);
        long legacy = ModelBillingPriceCalculator.amount(3_000_000, 1_000_000, 30, 110);

        assertThat(withCategories).isEqualTo(legacy);
        // 3,000,000 * 30 + 1,000,000 * 110 = 200,000,000 每百万 -> 200 分
        assertThat(legacy).isEqualTo(200L);
    }

    @Test
    void aCachedRequestCostsLessThanAnUncachedOneWithTheSameCounts() {
        long cached = ModelBillingPriceCalculator.amount(
                1_000_000, 0, 1_000_000, 0, 0, 30, 110, 8, 38, 110);
        long uncached = ModelBillingPriceCalculator.amount(
                1_000_000, 0, 0, 0, 0, 30, 110, 8, 38, 110);

        assertThat(cached).isEqualTo(8L);
        assertThat(uncached).isEqualTo(30L);
        assertThat(cached).isLessThan(uncached);
    }

    @Test
    void chargesReasoningAtItsOwnPriceWhenConfigured() {
        // 1M 推理 token，按 100 分/M 计，而输出价格为 300。
        long amount = ModelBillingPriceCalculator.amount(
                0, 1_000_000, 0, 0, 1_000_000, 30, 300, 8, 38, 100);

        assertThat(amount).isEqualTo(100L);
    }

    @Test
    void neverChargesLessThanOneFenForBillableUsage() {
        assertThat(ModelBillingPriceCalculator.amount(1, 0, 0, 0, 0, 1, 1, 1, 1, 1))
                .isEqualTo(1L);
    }

    @Test
    void rejectsCategoryCountsThatDoNotFitInsideTheTotals() {
        // 缓存类别是从输入中划分出来的，推理类别是从输出中划分出来的。调用方
        // 传入的类别 token 多于基础 token 时，否则会对同一 token 计费两次。
        assertThatThrownBy(() -> ModelBillingPriceCalculator.amount(
                100, 10, 60, 60, 0, 30, 110, 8, 38, 110))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_model_billing_values");

        assertThatThrownBy(() -> ModelBillingPriceCalculator.amount(
                100, 10, 0, 0, 11, 30, 110, 8, 38, 110))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_model_billing_values");

        assertThatThrownBy(() -> ModelBillingPriceCalculator.amount(
                100, 10, -1, 0, 0, 30, 110, 8, 38, 110))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_model_billing_values");
    }

    @Test
    void priceRecordCarriesTheCategoryRates() {
        ModelBillingPriceCalculator.Price price = new ModelBillingPriceCalculator.Price(
                "text-basic", 30, 110, "v1", 8L, 38L, 110L);

        assertThat(price.amount(3_000_000, 1_000_000, 1_000_000, 1_000_000, 400_000))
                .isEqualTo(186L);
        // 双参数形式把每个输入都按未命中计价，与之前一致。
        assertThat(price.amount(1_000_000, 0)).isEqualTo(30L);
    }
}
