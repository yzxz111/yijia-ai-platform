package com.yuzhihuihe.apiplatform.gateway.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class UsageQueryRows {

    private UsageQueryRows() {}

    record PageSpec(int page, int size) {
        int offset() {
            return Math.multiplyExact(page, size);
        }
    }

    record PageRows<T>(List<T> items, long total, int page, int size) {}

    /**
     * {@code activePackage} 是兼容字段：自 V80 起一个用户可以每个维度各持有一个生效实例
     * （文本 + 视频），因此真正的载荷是 {@code activePackages}。它保留的是「文本实例优先，
     * 否则唯一那个」——也就是这次改动之前每个消费者实际会看到的那一个。
     */
    record SummaryRow(
            ActivePackageRow activePackage,
            List<ActivePackageRow> activePackages,
            List<QueuedPackageRow> queuedPackages) {}

    record ActivePackageRow(
            UUID packageInstanceId,
            String packageCode,
            String packageName,
            String status,
            long totalTokens,
            long reservedTokens,
            long usedTokens,
            Integer dailyRequestLimit,
            Instant expiresAt,
            String allowedModelsJson,
            /**
             * TOKEN、VIDEO_SECOND、IMAGE 或 AUDIO_SECOND：上面那些配额计数器所用的单位。
             * VIDEO_SECOND 套餐的 "tokens" 是秒数，控制台绝不能
             * 把它们打印成 credits —— 也根本不能打印它们（它会对客户
             * 隐藏视频套餐方案的秒数）。
             */
            String usageDimension,
            List<ModelQuotaRow> modelQuotas,
            /**
             * 方案 C 的第二份额度（音频秒）。{@code null} 表示这个实例不卖语音——
             * 与"卖了 0 秒"是两件事，而控制台必须能区分：前者不渲染这一块，
             * 后者是一个假承诺（V92 的 CHECK 也拒绝 0）。
             *
             * <p>它一定以秒计（第二份桶只属于 {@code AUDIO_SECOND}），因此不随
             * {@code usageDimension} 变化。
             */
            Long audioSecondsQuota,
            Long audioSecondsReserved,
            Long audioSecondsUsed) {}

    /**
     * 一条分模型额度。
     *
     * <p>{@code usageDimension} 是这一行**计的是哪个桶**（TOKEN / VIDEO_SECOND / IMAGE /
     * AUDIO_SECOND）。读取侧（{@code COALESCE(token_quota, seconds_quota)}）已经把两个
     * 计数列合并成一个数，合并之后就再也看不出单位了——而"这个模型的剩余额度"在
     * 客户端只有配上单位才是一句有意义的话（1200 是 1200 个积分还是 1200 秒）。
     * 约束 {@code ck_model_quota_values} 保证计数列与维度一致，因此这里没有第二个答案。
     */
    record ModelQuotaRow(
            String modelCode,
            String usageDimension,
            long totalTokens,
            long reservedTokens,
            long usedTokens) {}

    /**
     * 排队中的实例现在也必须带维度：队列是按维度推进的，一个排队的视频套餐
     * 与一个排队的文本套餐在控制台上必须能被区分，否则秒数会被渲染成 credits。
     */
    record QueuedPackageRow(
            UUID packageInstanceId,
            String packageCode,
            String packageName,
            long totalTokens,
            Instant createdAt,
            String usageDimension) {}

    record LedgerRow(
            UUID id,
            String requestId,
            UUID reservationId,
            UUID packageInstanceId,
            String modelCode,
            String entryType,
            long totalTokenDelta,
            Long modelTokenDelta,
            long balanceAfter,
            Long modelBalanceAfter,
            Instant createdAt) {}

    record AdminLedgerRow(
            UUID id,
            String requestId,
            UUID tenantId,
            UUID userId,
            UUID reservationId,
            UUID packageInstanceId,
            String modelCode,
            String entryType,
            long totalTokenDelta,
            Long modelTokenDelta,
            long balanceAfter,
            Long modelBalanceAfter,
            Instant createdAt) {}

    record UsageRow(
            UUID id,
            String requestId,
            UUID reservationId,
            UUID tenantId,
            UUID userId,
            UUID apiKeyId,
            String apiKeyPrefix,
            UUID packageInstanceId,
            String modelCode,
            String providerCode,
            String channelCode,
            String upstreamModel,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            /**
             * TOKEN / VIDEO_SECOND / IMAGE，以及以该单位计量的消耗量。一次视频
             * 生成以秒（SECONDS）结算：它的输入/输出/总 token 计数器按约束全部
             * 为零，因此只读 token 计数器的控制台会把一次视频调用显示为
             * "0 credits consumed"。{@code usageUnits} 携带真实数值
             * （计费秒数），{@code usageDimension} 说明该如何标注它。
             */
            String usageDimension,
            long usageUnits,
            boolean usageEstimated,
            boolean stream,
            Long firstTokenMs,
            long durationMs,
            int httpStatus,
            String outcome,
            String errorCategory,
            String errorSummary,
            /**
             * 生成终态：{@code completed} / {@code failed} / {@code truncated}；历史行为 null。
             *
             * <p>与 {@code outcome}（计量口径）和 {@code httpStatus}（传输口径）并列而不是
             * 替代：一次被输出上限截断的请求，这三者分别是
             * {@code SUCCEEDED} / {@code 200} / {@code truncated}。只看前两个字段时，
             * 它与一次自然结束的请求完全一样——2026-10-15 的排查正是卡在这里。
             */
            String generationStatus,
            /** 上游给出的结束原因（{@code stop} / {@code length} …）；未观察到为 null。 */
            String finishReason,
            /** 本次请求被准入的输出上限；NULL 表示未知（历史行、或上限尚未算出）。 */
            Long effectiveMaxOutputTokens,
            /** 上限来源：{@code client} / {@code model} / {@code package} / {@code system}。 */
            String ceilingSource,
            /** 这次请求产生了几次供应商调用（含重试与换渠道）。 */
            Integer attemptCount,
            Instant createdAt,
            List<UsageStageRow> stages,
            /**
             * 这次调用真实扣掉的额度，或 {@code null} 表示这条用量记录关联不到
             * 任何预占／结算记录（{@code usage_log.reservation_id} 为空，或对应
             * 预留行已不存在）。历史行属于后者，必须原样返回 {@code null}，
             * 而不是回填一个估算值。
             */
            UsageChargeRow quotaCharge) {

        UsageRow withStages(List<UsageStageRow> value) {
            return withDetails(value, quotaCharge);
        }

        UsageRow withQuotaCharge(UsageChargeRow value) {
            return withDetails(stages, value);
        }

        private UsageRow withDetails(List<UsageStageRow> stageRows, UsageChargeRow charge) {
            return new UsageRow(
                    id,
                    requestId,
                    reservationId,
                    tenantId,
                    userId,
                    apiKeyId,
                    apiKeyPrefix,
                    packageInstanceId,
                    modelCode,
                    providerCode,
                    channelCode,
                    upstreamModel,
                    inputTokens,
                    outputTokens,
                    totalTokens,
                    usageDimension,
                    usageUnits,
                    usageEstimated,
                    stream,
                    firstTokenMs,
                    durationMs,
                    httpStatus,
                    outcome,
                    errorCategory,
                    errorSummary,
                    generationStatus,
                    finishReason,
                    effectiveMaxOutputTokens,
                    ceilingSource,
                    attemptCount,
                    createdAt,
                    stageRows,
                    charge);
        }
    }

    /**
     * 一条 {@code gateway.quota_reservation} 及其两条账本事实，用于回答"这次调用到底扣了多少"。
     *
     * <p><b>为什么读账本而不是自己算。</b>预占是配额在途量的估计值，结算才是真实扣减；
     * 两者的差额记在 {@code quota_ledger} 的 SETTLE 行上
     * （{@code total_token_delta = 预占 − 实扣}，见 QuotaReservationRepository.settle）。
     * 因此"实扣 = 预占 − 差额"是账本自己的恒等式，不是这里的推测。
     *
     * <p>{@code hasSettleLedger} 与 {@code settleRecorded} 是**表里有没有那一行**
     * 与**那一行的差额是不是空值**两件事：预留时如果这个模型没有独立额度，
     * {@code model_token_delta} 在 RESERVE 与 SETTLE 两行上都是 NULL，那表示
     * "这次消费不涉及独立模型池"，与"扣了 0"是两回事。
     *
     * @param status           {@code RESERVED} / {@code SETTLED} / {@code RELEASED} / {@code EXPIRED}
     * @param packageReservedTokens 这次预占向**模型子额度池**要的那一段，取自
     *                        {@code quota_reservation.package_reserved_tokens}。
     *                        模型实扣量是"预占 − 结算差额"，而这里的"预占"必须是模型池
     *                        当初真正被扣的那一个数——账本自己就是这么写的：
     *                        {@code QuotaReservationRepository.settle} 记
     *                        {@code model_token_delta = package_reserved_tokens − modelCharge}，
     *                        并且模型池的余额换算用的是
     *                        {@code modelQuota.reservedTokens() - packageReservedTokens()}
     *                        （见同文件 settle 里的 modelBalance）。所以这一列才是被减数。
     *                        <p><b>不要用 {@code reserved_tokens} 代替它。</b>那是这次请求
     *                        <em>总</em>预占（套餐段 + 钱包段）；在 V21 的钱包拆分
     *                        （{@code billing_mode='HYBRID'}）下两者不相等，用总量去减
     *                        会把模型实扣高估出「钱包承担的那一段」，甚至算出比模型池
     *                        自己的预占还大的数。
     *                        已结算的套餐实扣只读 {@code package_settled_tokens}，不读这一列
     * @param packageSettledTokens  已结算的套餐额度，未结算时为 0
     * @param walletSettledAmount   已结算的钱包扣款（分），未结算时为 0
     * @param hasReserveModelDelta  预留时是否占用了独立模型额度
     * @param hasSettleLedger       是否存在 SETTLE 账本行
     * @param settleModelDelta      SETTLE 行上的模型差额；无该行或该列为空时为 {@code null}
     */
    record UsageChargeRow(
            UUID reservationId,
            String status,
            long packageReservedTokens,
            long packageSettledTokens,
            long walletSettledAmount,
            Instant settledAt,
            boolean hasReserveModelDelta,
            boolean hasSettleLedger,
            Long settleModelDelta) {}

    record UsageStageRow(
            int stageIndex,
            String stageType,
            String logicalModelCode,
            String providerCode,
            String channelCode,
            String upstreamModel,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long durationMs,
            String outcome,
            Long providerCostAmount,
            String providerCostCurrency,
            boolean priceEstimated) {}

    record ReservationRow(
            UUID id,
            String requestId,
            UUID tenantId,
            UUID userId,
            UUID apiKeyId,
            UUID packageInstanceId,
            String modelCode,
            long reservedTokens,
            Long actualInputTokens,
            Long actualOutputTokens,
            Long actualTotalTokens,
            long uncoveredTokens,
            boolean usageEstimated,
            String status,
            Instant expiresAt,
            Instant settledAt,
            Instant releasedAt,
            Instant createdAt) {}

    record ProviderPriceRow(
            UUID id,
            String providerCode,
            String modelCode,
            long inputPricePerMillion,
            long outputPricePerMillion,
            Long cacheReadPricePerMillion,
            Long cacheWritePricePerMillion,
            Long reasoningPricePerMillion,
            String currency,
            BigDecimal internalExchangeRate,
            String officialSource,
            Instant effectiveFrom,
            Instant effectiveTo) {}

    record UsageMetricsRow(long sampleCount, long platformAddedP95Ms) {}

    record IndexedUsageStageRow(UUID usageLogId, UsageStageRow entry) {}
}
