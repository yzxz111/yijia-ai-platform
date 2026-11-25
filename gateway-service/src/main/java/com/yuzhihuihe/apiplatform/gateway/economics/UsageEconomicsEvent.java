package com.yuzhihuihe.apiplatform.gateway.economics;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 一次网关请求的用量经济证据，按交付给平台的形式描述。
 *
 * <p><b>版本 3 标明了单位。</b>V1 与 V2 是 token 形态的：载荷中的每一个数量都是
 * token 计数，因此视频秒数无处安放。把秒数写进 {@code output_tokens} 是平台当时
 * 唯一接受的形态，而它把按秒计量的数量登记成了 token 计数，运营报表随后又把它
 * 求和。V3 增加了 {@code usage_dimension} 以及带维度限定的单位——
 *
 * <ul>
 *   <li>{@code usage_dimension}：{@code TOKEN}、{@code VIDEO_SECOND} 或 {@code IMAGE}；
 *   <li>{@code usage_units}：该事件在该维度下的总数量；
 *   <li>{@code package_settled_units}：其中由套餐支付的部分；
 *   <li>每个阶段各自的 {@code usage_dimension} 与 {@code usage_units}。
 * </ul>
 *
 * 并按维度保留原有的 token 不变式：对于 {@code TOKEN}，单位数本身就是 token 总量，
 * 已结算单位数本身就是已结算的 token 数；对于其他任何维度，token 计数器保持为零。
 * 平台仍然接受 {@code USAGE_ECONOMICS_V1}/{@code V2}（新的平台不会让旧网关失效），
 * 在那里它们按其本来面目——即按 token 计价的契约——被读取。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UsageEconomicsEvent(
        String version,
        UUID eventId,
        String requestId,
        UUID reservationId,
        UUID tenantId,
        UUID userId,
        UUID packageInstanceId,
        String modelCode,
        String upstreamModel,
        UUID providerId,
        UUID channelId,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        String usageDimension,
        long usageUnits,
        String billingMode,
        long packageSettledTokens,
        long packageSettledUnits,
        long walletSettledAmount,
        UUID providerPriceId,
        String providerCostCurrency,
        BigDecimal providerExchangeRate,
        Long providerInputCostAmount,
        Long providerOutputCostAmount,
        Long providerTotalCostAmount,
        BigDecimal providerOriginalInputCostAmount,
        BigDecimal providerOriginalOutputCostAmount,
        BigDecimal providerOriginalCostAmount,
        String providerOriginalCostCurrency,
        boolean usageEstimated,
        boolean priceMissing,
        List<Stage> stages) {

    public static final String VERSION = "USAGE_ECONOMICS_V3";
    public static final String LEGACY_VERSION = "USAGE_ECONOMICS_V1";
    public static final String PREVIOUS_VERSION = "USAGE_ECONOMICS_V2";

    public static final String DIMENSION_TOKEN = "TOKEN";
    public static final String DIMENSION_VIDEO_SECOND = "VIDEO_SECOND";
    /**
     * 一次图片生成计费的单位是**张**：{@code usage_units} 携带已计费张数，
     * 三个 token 计数器一律为零。与 {@code VIDEO_SECOND} 同形，由
     * {@code ImageGenerationService} 结算时写入（网关 V83 为该维度加入了
     * {@code IMAGE_GENERATION} stage 类型）。
     */
    public static final String DIMENSION_IMAGE = "IMAGE";
    /**
     * 一次音频生成计费的单位是**秒**：{@code usage_units} 携带已计费秒数，
     * 三个 token 计数器一律为零。与 {@code VIDEO_SECOND} 同单位、同形，
     * 但**绝不能与它合并**：一个视频秒的成本是 69 分，而一个音频秒是 1 分
     * （ASR）到 14 分（换口型）——把两者放进同一个维度求和，
     * 运营报表会把一段视频的秒数与一次配音的秒数加在一起。
     *
     * <p>由 {@code AudioGenerationService} 结算时写入（网关 V92 为该维度加入了
     * {@code AUDIO_GENERATION} stage 类型）。
     */
    public static final String DIMENSION_AUDIO_SECOND = "AUDIO_SECOND";

    public UsageEconomicsEvent {
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    /**
     * 一个按 token 维度构造、但未声明其维度的事件。
     *
     * <p>对于 {@code TOKEN} 事件，带维度限定的单位数本身就是 token 计数器，因此这是
     * 同一个载荷，而不是有损的简写。保留它是为了让没有维度相关信息要表达的调用方
     * 不会意外发出与其 token 不一致的单位数。
     */
    public UsageEconomicsEvent(
            String version,
            UUID eventId,
            String requestId,
            UUID reservationId,
            UUID tenantId,
            UUID userId,
            UUID packageInstanceId,
            String modelCode,
            String upstreamModel,
            UUID providerId,
            UUID channelId,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            String billingMode,
            long packageSettledTokens,
            long walletSettledAmount,
            UUID providerPriceId,
            String providerCostCurrency,
            BigDecimal providerExchangeRate,
            Long providerInputCostAmount,
            Long providerOutputCostAmount,
            Long providerTotalCostAmount,
            BigDecimal providerOriginalInputCostAmount,
            BigDecimal providerOriginalOutputCostAmount,
            BigDecimal providerOriginalCostAmount,
            String providerOriginalCostCurrency,
            boolean usageEstimated,
            boolean priceMissing,
            List<Stage> stages) {
        this(version, eventId, requestId, reservationId, tenantId, userId, packageInstanceId,
                modelCode, upstreamModel, providerId, channelId, inputTokens, outputTokens,
                totalTokens, DIMENSION_TOKEN, totalTokens, billingMode, packageSettledTokens,
                packageSettledTokens, walletSettledAmount, providerPriceId, providerCostCurrency,
                providerExchangeRate, providerInputCostAmount, providerOutputCostAmount,
                providerTotalCostAmount, providerOriginalInputCostAmount,
                providerOriginalOutputCostAmount, providerOriginalCostAmount,
                providerOriginalCostCurrency, usageEstimated, priceMissing, stages);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Stage(
            int stageIndex,
            String stageType,
            String logicalModelCode,
            String upstreamModel,
            UUID providerId,
            UUID channelId,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            UUID providerPriceId,
            String providerCostCurrency,
            BigDecimal providerExchangeRate,
            Long providerInputCostAmount,
            Long providerOutputCostAmount,
            Long providerTotalCostAmount,
            BigDecimal providerOriginalInputCostAmount,
            BigDecimal providerOriginalOutputCostAmount,
            BigDecimal providerOriginalCostAmount,
            String providerOriginalCostCurrency,
            boolean usageEstimated,
            boolean priceMissing,
            Long cacheHitTokens,
            Long cacheMissTokens,
            Long cacheWriteTokens,
            Long reasoningTokens,
            Long textTokens,
            Long imageTokens,
            Long audioTokens,
            String usageSource,
            String providerRequestId,
            boolean customerBillable,
            boolean providerBillable,
            String reconciliationStatus,
            Long providerUncachedInputCostAmount,
            Long providerCacheReadCostAmount,
            Long providerCacheWriteCostAmount,
            Long providerNormalOutputCostAmount,
            Long providerReasoningCostAmount,
            String usageDimension,
            long usageUnits) {

        /** 按 token 维度构造的阶段：其带维度限定的单位数就是它的 token 总量。 */
        public Stage(
                int stageIndex, String stageType, String logicalModelCode, String upstreamModel,
                UUID providerId, UUID channelId, long inputTokens, long outputTokens,
                long totalTokens, UUID providerPriceId, String providerCostCurrency,
                BigDecimal providerExchangeRate, Long providerInputCostAmount,
                Long providerOutputCostAmount, Long providerTotalCostAmount,
                BigDecimal providerOriginalInputCostAmount,
                BigDecimal providerOriginalOutputCostAmount,
                BigDecimal providerOriginalCostAmount, String providerOriginalCostCurrency,
                boolean usageEstimated, boolean priceMissing) {
            this(stageIndex, stageType, logicalModelCode, upstreamModel, providerId, channelId,
                    inputTokens, outputTokens, totalTokens, providerPriceId, providerCostCurrency,
                    providerExchangeRate, providerInputCostAmount, providerOutputCostAmount,
                    providerTotalCostAmount, providerOriginalInputCostAmount,
                    providerOriginalOutputCostAmount, providerOriginalCostAmount,
                    providerOriginalCostCurrency, usageEstimated, priceMissing,
                    null, null, null, null, null, null, null,
                    usageEstimated ? "LOCAL_ESTIMATED" : "PROVIDER_ACTUAL", null,
                    true, true, "NOT_APPLICABLE", null, null, null, null, null,
                    DIMENSION_TOKEN, totalTokens);
        }

        /**
         * 一个已声明其维度的阶段。视频阶段按秒计价，因此它把秒数放在
         * {@code usageUnits} 中，而 token 数为零。
         */
        public Stage(
                int stageIndex, String stageType, String logicalModelCode, String upstreamModel,
                UUID providerId, UUID channelId, long inputTokens, long outputTokens,
                long totalTokens, UUID providerPriceId, String providerCostCurrency,
                BigDecimal providerExchangeRate, Long providerInputCostAmount,
                Long providerOutputCostAmount, Long providerTotalCostAmount,
                BigDecimal providerOriginalInputCostAmount,
                BigDecimal providerOriginalOutputCostAmount,
                BigDecimal providerOriginalCostAmount, String providerOriginalCostCurrency,
                boolean usageEstimated, boolean priceMissing,
                String usageDimension, long usageUnits) {
            this(stageIndex, stageType, logicalModelCode, upstreamModel, providerId, channelId,
                    inputTokens, outputTokens, totalTokens, providerPriceId, providerCostCurrency,
                    providerExchangeRate, providerInputCostAmount, providerOutputCostAmount,
                    providerTotalCostAmount, providerOriginalInputCostAmount,
                    providerOriginalOutputCostAmount, providerOriginalCostAmount,
                    providerOriginalCostCurrency, usageEstimated, priceMissing,
                    null, null, null, null, null, null, null,
                    usageEstimated ? "LOCAL_ESTIMATED" : "PROVIDER_ACTUAL", null,
                    true, true, "NOT_APPLICABLE", null, null, null, null, null,
                    usageDimension, usageUnits);
        }
    }
}
