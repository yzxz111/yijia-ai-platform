package com.yuzhihuihe.apiplatform.gateway.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class ModelBillingPriceCalculator {

    private static final BigDecimal TOKENS_PER_MILLION = BigDecimal.valueOf(1_000_000L);

    private ModelBillingPriceCalculator() { }

    public static long amount(long inputTokens, long outputTokens,
            long inputPricePerMillion, long outputPricePerMillion) {
        return amount(inputTokens, outputTokens, 0L, 0L, 0L,
                inputPricePerMillion, outputPricePerMillion, 0L, 0L, 0L);
    }

    /**
     * 为一个输入与输出被拆分为多个计费类别的请求计价。
     *
     * <p>一次缓存命中与一个推理 token 给供应商带来的成本远低于它们所属的基础类别
     * —— 视供应商而定，缓存读取价格是缓存未命中的 1/5 到 1/50 —— 因此按基础价格
     * 给它们计费会向客户多收费。这里由本方法而非调用方从基础计数中
     * 扣除各类别的计数，所以这四个计数器不会重复计算。
     *
     * @param inputTokens 输入 token 总数，包含各缓存类别
     * @param outputTokens 输出 token 总数，包含推理部分
     * @param cacheHitTokens 由缓存提供的输入 token
     * @param cacheWriteTokens 写入缓存的输入 token
     * @param reasoningTokens 用于推理的输出 token
     * @param cacheHitPricePerMillion 缓存命中的零售价；未配置时回退到
     *        输入价格（迁移之前的行为）
     * @param cacheWritePricePerMillion 缓存写入的零售价；未配置时
     *        回退到输入价格
     * @param reasoningPricePerMillion 推理 token 的零售价；未配置时
     *        回退到输出价格
     */
    public static long amount(long inputTokens, long outputTokens,
            long cacheHitTokens, long cacheWriteTokens, long reasoningTokens,
            long inputPricePerMillion, long outputPricePerMillion,
            long cacheHitPricePerMillion, long cacheWritePricePerMillion,
            long reasoningPricePerMillion) {
        if (inputTokens < 0 || outputTokens < 0
                || inputPricePerMillion <= 0 || outputPricePerMillion <= 0) {
            throw new IllegalArgumentException("invalid_model_billing_values");
        }
        if (cacheHitTokens < 0 || cacheWriteTokens < 0 || reasoningTokens < 0
                || cacheHitTokens + cacheWriteTokens > inputTokens
                || reasoningTokens > outputTokens) {
            throw new IllegalArgumentException("invalid_model_billing_values");
        }
        long cacheReadPrice = positiveOr(cacheHitPricePerMillion, inputPricePerMillion);
        long cacheWrittenPrice = positiveOr(cacheWritePricePerMillion, inputPricePerMillion);
        long reasoningPrice = positiveOr(reasoningPricePerMillion, outputPricePerMillion);

        long uncachedInput = inputTokens - cacheHitTokens - cacheWriteTokens;
        long normalOutput = outputTokens - reasoningTokens;

        BigDecimal total = BigDecimal.valueOf(uncachedInput)
                .multiply(BigDecimal.valueOf(inputPricePerMillion))
                .add(BigDecimal.valueOf(cacheHitTokens)
                        .multiply(BigDecimal.valueOf(cacheReadPrice)))
                .add(BigDecimal.valueOf(cacheWriteTokens)
                        .multiply(BigDecimal.valueOf(cacheWrittenPrice)))
                .add(BigDecimal.valueOf(normalOutput)
                        .multiply(BigDecimal.valueOf(outputPricePerMillion)))
                .add(BigDecimal.valueOf(reasoningTokens)
                        .multiply(BigDecimal.valueOf(reasoningPrice)))
                .divide(TOKENS_PER_MILLION, 0, RoundingMode.CEILING);
        if ((inputTokens > 0 || outputTokens > 0) && total.signum() == 0) {
            return 1;
        }
        return total.longValueExact();
    }

    private static long positiveOr(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    public static void validateMargin(long inputPrice, long outputPrice,
            long providerInputCost, long providerOutputCost, BigDecimal targetMargin) {
        if (inputPrice < providerInputCost || outputPrice < providerOutputCost
                || targetMargin == null || targetMargin.signum() < 0
                || targetMargin.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("model_price_margin_invalid");
        }
        BigDecimal minimumInput = BigDecimal.valueOf(providerInputCost)
                .divide(BigDecimal.ONE.subtract(targetMargin), 8, RoundingMode.CEILING);
        BigDecimal minimumOutput = BigDecimal.valueOf(providerOutputCost)
                .divide(BigDecimal.ONE.subtract(targetMargin), 8, RoundingMode.CEILING);
        if (BigDecimal.valueOf(inputPrice).compareTo(minimumInput) < 0
                || BigDecimal.valueOf(outputPrice).compareTo(minimumOutput) < 0) {
            throw new IllegalArgumentException("model_price_margin_invalid");
        }
    }

    /**
     * 一条已发布的零售价。
     *
     * <p>各分类价格可以为 null：null 表示「未配置，使用基础类别价格」，
     * 与供应商成本侧解析自身类别的方式一致。因此在按类别计价出现之前
     * 发布的行，收取的费用与它以前收取的完全一样。
     */
    public record Price(String modelCode, long inputPricePerMillion, long outputPricePerMillion,
            String priceVersion,
            Long cacheHitPricePerMillion,
            Long cacheWritePricePerMillion,
            Long reasoningPricePerMillion) {

        /** 向后兼容的形式：未配置任何类别价格。 */
        public Price(String modelCode, long inputPricePerMillion, long outputPricePerMillion,
                String priceVersion) {
            this(modelCode, inputPricePerMillion, outputPricePerMillion, priceVersion,
                    null, null, null);
        }

        public long amount(long inputTokens, long outputTokens) {
            return amount(inputTokens, outputTokens, 0L, 0L, 0L);
        }

        public long amount(long inputTokens, long outputTokens,
                long cacheHitTokens, long cacheWriteTokens, long reasoningTokens) {
            return ModelBillingPriceCalculator.amount(
                    inputTokens, outputTokens, cacheHitTokens, cacheWriteTokens, reasoningTokens,
                    inputPricePerMillion, outputPricePerMillion,
                    orZero(cacheHitPricePerMillion), orZero(cacheWritePricePerMillion),
                    orZero(reasoningPricePerMillion));
        }

        private static long orZero(Long value) {
            return value == null ? 0L : value;
        }
    }
}
