package com.yuzhihuihe.apiplatform.gateway.openai;

import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Mono;

final class ChatCompletionSettlement {

    private final ChatCompletionUsageAccounting usageAccounting;
    private final QuotaService quotaService;
    private final RequestLimitService requestLimitService;
    private final Context context;
    private final Function<Report, Mono<Void>> logWriter;
    private final AtomicReference<ProviderAdapter.TokenUsage> actualUsage = new AtomicReference<>();
    private final AtomicReference<Target> target = new AtomicReference<>();
    private final AtomicReference<RequestLimitService.LimitLease> lease = new AtomicReference<>();
    private final AtomicLong attemptStartedNanos = new AtomicLong();
    private final List<Stage> stages = new ArrayList<>();

    ChatCompletionSettlement(
            ChatCompletionUsageAccounting usageAccounting,
            QuotaService quotaService,
            RequestLimitService requestLimitService,
            Context context,
            Target target,
            RequestLimitService.LimitLease lease,
            Function<Report, Mono<Void>> logWriter) {
        this.usageAccounting = usageAccounting;
        this.quotaService = quotaService;
        this.requestLimitService = requestLimitService;
        this.context = context;
        this.logWriter = logWriter;
        this.target.set(target);
        this.lease.set(lease);
    }

    Instant startedAt() {
        return context.startedAt();
    }

    boolean routeActivation() {
        return context.routeActivation();
    }

    void lease(RequestLimitService.LimitLease value) {
        lease.set(value);
    }

    RequestLimitService.LimitLease lease() {
        return lease.get();
    }

    void beginAttempt(Target value) {
        target.set(value);
        actualUsage.set(null);
        attemptStartedNanos.set(0);
    }

    void upstreamStarted() {
        attemptStartedNanos.set(System.nanoTime());
    }

    void actualUsage(ProviderAdapter.TokenUsage usage) {
        actualUsage.set(usage);
    }

    void failedAttempt(Throwable error) {
        long attemptStarted = attemptStartedNanos.getAndSet(0);
        if (attemptStarted == 0) {
            return;
        }
        ProviderAdapter.TokenUsage actual = actualUsage.get();
        addStage(
                actual == null ? new ProviderAdapter.TokenUsage(0, 0, 0) : actual,
                actual == null
                        ? QuotaService.UsageQuality.ESTIMATED
                        : QuotaService.UsageQuality.ACTUAL,
                attemptDurationMs(attemptStarted),
                "FAILED",
                error,
                false,
                actual != null);
    }

    Mono<Void> fail(Throwable error) {
        List<Stage> completedStages = List.copyOf(stages);
        ChatCompletionUsageAccounting.AggregatedUsage aggregated =
                usageAccounting.aggregate(completedStages);
        boolean customerBillable = completedStages.stream().anyMatch(Stage::customerBillable);
        Mono<Void> reservationAccounting =
                Mono.defer(
                        () ->
                                customerBillable
                                        ? settleAndRecordCredits(
                                                usageAccounting.quotaUsage(aggregated.usage()),
                                                aggregated.quality())
                                        : quotaService.release(
                                                context.reservationId(),
                                                QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        return finish(
                target.get(), "FAILED", error, completedStages, aggregated, reservationAccounting);
    }

    Mono<Void> failResponse(
            ProviderAdapter.TokenUsage usage, QuotaService.UsageQuality quality, Throwable error) {
        addStage(
                usage,
                quality,
                attemptDurationMs(attemptStartedNanos.getAndSet(0)),
                "FAILED",
                error,
                false,
                true);
        List<Stage> completedStages = List.copyOf(stages);
        ChatCompletionUsageAccounting.AggregatedUsage aggregated =
                usageAccounting.aggregate(completedStages);
        return finish(
                target.get(),
                "FAILED",
                error,
                completedStages,
                aggregated,
                Mono.defer(
                        () ->
                                quotaService.release(
                                        context.reservationId(),
                                        QuotaService.ReleaseReason.UPSTREAM_FAILURE)));
    }

    Mono<Void> succeed(ProviderAdapter.TokenUsage usage, QuotaService.UsageQuality quality) {
        addStage(
                usage,
                quality,
                attemptDurationMs(attemptStartedNanos.getAndSet(0)),
                "SUCCEEDED",
                null,
                true,
                true);
        List<Stage> completedStages = List.copyOf(stages);
        ChatCompletionUsageAccounting.AggregatedUsage aggregated =
                usageAccounting.aggregate(completedStages);
        return finish(
                target.get(),
                "SUCCEEDED",
                null,
                completedStages,
                aggregated,
                Mono.defer(
                        () ->
                                settleAndRecordCredits(
                                        usageAccounting.quotaUsage(aggregated.usage()),
                                        aggregated.quality())));
    }

    /**
     * 结算该请求，并把实际向套餐收取的积分交给已售出的每分钟积分速率 （{@code tokens_per_minute}，展示为 积分/分钟）。
     *
     * <p>这个数字只在这里存在：{@code Settlement.chargedTokens} 才是套餐自身额度损失掉的
     * 量，也正是该上限所限制的量。这条记录在已经提交的结算的尾部运行，而且记录器会立即 返回，因此它既不会延迟客户看到的扣费，也不会让该扣费失败。
     */
    private Mono<Void> settleAndRecordCredits(
            QuotaService.TokenUsage usage, QuotaService.UsageQuality quality) {
        return quotaService
                .settle(context.reservationId(), usage, quality)
                .doOnNext(
                        settlement ->
                                requestLimitService.recordPackageConsumption(
                                        context.tokenLimit(), settlement.chargedTokens()))
                .then();
    }

    private Mono<Void> finish(
            Target completedTarget,
            String outcome,
            Throwable error,
            List<Stage> completedStages,
            ChatCompletionUsageAccounting.AggregatedUsage aggregated,
            Mono<Void> reservationAccounting) {
        Mono<Void> log =
                Mono.defer(
                        () ->
                                logWriter.apply(
                                        new Report(
                                                completedTarget,
                                                aggregated.usage(),
                                                aggregated.quality(),
                                                Math.max(
                                                        0,
                                                        Duration.between(
                                                                        context.startedAt(),
                                                                        Instant.now())
                                                                .toMillis()),
                                                outcome,
                                                error,
                                                completedStages,
                                                // 非流式路径不会被网关的输出上限截断：截断靠
                                                // takeUntil(outputCapReached) 作用在流上，
                                                // 而同步补全一次性拿到完整响应。因此这里只有
                                                // 两种生成终态，"truncated" 不会出现。
                                                "SUCCEEDED".equals(outcome)
                                                        ? "completed"
                                                        : "failed",
                                                null,
                                                context.effectiveMaxOutputTokens(),
                                                context.ceilingSource(),
                                                completedStages.size())));
        return usageAccounting.settleReleaseAndLog(
                reservationAccounting, requestLimitService.release(lease()), log);
    }

    private void addStage(
            ProviderAdapter.TokenUsage usage,
            QuotaService.UsageQuality quality,
            long durationMs,
            String outcome,
            Throwable error,
            boolean customerBillable,
            boolean providerBillable) {
        Target attemptTarget = target.get();
        stages.add(
                new Stage(
                        attemptTarget.routingModelId(),
                        attemptTarget.routingModelAlias(),
                        attemptTarget.upstreamModel(),
                        attemptTarget.providerId(),
                        attemptTarget.channelId(),
                        usage,
                        quality,
                        durationMs,
                        outcome,
                        usageAccounting.failureCategory(error),
                        customerBillable,
                        providerBillable));
    }

    private long attemptDurationMs(long attemptStarted) {
        return attemptStarted == 0
                ? 0
                : TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - attemptStarted));
    }

    record Context(
            UUID reservationId,
            Instant startedAt,
            boolean routeActivation,
            RequestLimitService.PackageTokenLimit tokenLimit,
            /**
             * 生效上限；{@code null} 表示"未知"。用包装类型而不是 {@code long}，因为 这条路径确实存在"上限还没算出来"的时刻，而 0
             * 是一个**合法的上限**表达 不出来的意思——把"未知"写成 0 会让用量行声称一个从未被采纳的上限。
             */
            Long effectiveMaxOutputTokens,
            String ceilingSource) {

        Context {
            tokenLimit =
                    tokenLimit == null
                            ? RequestLimitService.PackageTokenLimit.unlimited()
                            : tokenLimit;
        }

        Context(UUID reservationId, Instant startedAt, boolean routeActivation) {
            this(
                    reservationId,
                    startedAt,
                    routeActivation,
                    RequestLimitService.PackageTokenLimit.unlimited(),
                    // 上限"未知"：0 与 null 是这个词的正确表达——填一个像 65536 的默认值
                    // 会让用量行声称一个从未被采纳的上限。
                    0L,
                    null);
        }

        /**
         * 带上限之外的旧签名（测试与内部调用大量使用）。
         *
         * <p>保留它是因为"上限未知"在语义上是合法的：只有真正算出上限的那条路径才知道 它是什么。让每一个构造点都写一遍 {@code 0L, null} 不会让任何测试更有意义。
         */
        Context(
                UUID reservationId,
                Instant startedAt,
                boolean routeActivation,
                RequestLimitService.PackageTokenLimit tokenLimit) {
            this(reservationId, startedAt, routeActivation, tokenLimit, 0L, null);
        }
    }

    record Target(
            UUID routingModelId,
            String routingModelAlias,
            String upstreamModel,
            UUID providerId,
            UUID channelId) {}

    record Stage(
            UUID modelId,
            String logicalModelCode,
            String upstreamModel,
            UUID providerId,
            UUID channelId,
            ProviderAdapter.TokenUsage usage,
            QuotaService.UsageQuality quality,
            long durationMs,
            String outcome,
            String errorCategory,
            boolean customerBillable,
            boolean providerBillable)
            implements ChatCompletionUsageAccounting.CustomerUsageStage {}

    record Report(
            Target target,
            ProviderAdapter.TokenUsage usage,
            QuotaService.UsageQuality quality,
            long durationMs,
            String outcome,
            Throwable error,
            List<Stage> stages,
            /** {@code completed} / {@code failed} / {@code truncated}；与计量口径分开表达。 */
            String generationStatus,
            /** 上游给出的结束原因；只有流式路径观察得到，非流式恒为 null。 */
            String finishReason,
            /** 本次请求实际被准入的那个输出上限（四层 min() 的结果）；null 表示未知。 */
            Long effectiveMaxOutputTokens,
            /** 上限是被哪一层压出来的：client / model / package / system。 */
            String ceilingSource,
            /** 这次请求一共产生了几次供应商调用（含重试与换渠道）。 */
            int attemptCount) {

        Report {
            stages = List.copyOf(stages);
        }
    }
}
