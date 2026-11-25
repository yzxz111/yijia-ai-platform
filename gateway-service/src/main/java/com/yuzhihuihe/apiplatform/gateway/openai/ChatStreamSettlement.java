package com.yuzhihuihe.apiplatform.gateway.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

final class ChatStreamSettlement {

    private static final Logger LOG = LoggerFactory.getLogger(ChatStreamSettlement.class);

    private final ChatCompletionUsageAccounting usageAccounting;
    private final QuotaService quotaService;
    private final RequestLimitService requestLimitService;
    private final Context context;
    private final Function<Report, Mono<Void>> logWriter;
    private final ChatCompletionUsageEstimator.StreamUsageEstimate streamUsageEstimate;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicReference<ProviderAdapter.TokenUsage> actualUsage =
            new AtomicReference<>();
    private final AtomicReference<Target> target = new AtomicReference<>();
    private final AtomicReference<RequestLimitService.LimitLease> lease =
            new AtomicReference<>();
    private final AtomicLong firstTokenMs = new AtomicLong(-1);
    /**
     * 上游对这一条流给出的结束原因（{@code stop} / {@code length} / {@code tool_calls} …）。
     *
     * <p>由序列化器在读到最后一个块时写入，因此它记的是**上游说了什么**，而不是网关
     * 推测了什么。这正是排查 2026-10-15 那次截断时缺的那条信息：用量行上只有 token 数，
     * 而 4096 与"自然结束"在数字上无从区分。
     */
    private final AtomicReference<String> finishReason = new AtomicReference<>();
    private final List<Stage> stages = new ArrayList<>();
    private long attemptStartedNanos;
    private boolean attemptActive;

    ChatStreamSettlement(
            ChatCompletionUsageEstimator usageEstimator,
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
        this.streamUsageEstimate = usageEstimator.streamUsageEstimate(
                context.inputEstimate(), context.reservationEstimate());
        this.target.set(target);
        this.lease.set(lease);
    }

    String requestId() {
        return context.requestId();
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

    void firstByte() {
        firstTokenMs.compareAndSet(
                -1,
                Math.max(0, Duration.between(context.startedAt(), Instant.now()).toMillis()));
    }

    void actualUsage(ProviderAdapter.TokenUsage usage) {
        actualUsage.set(usage);
    }

    void beginAttempt(Target value) {
        target.set(value);
        actualUsage.set(null);
        streamUsageEstimate.reset();
        attemptStartedNanos = 0;
        attemptActive = false;
    }

    void upstreamStarted() {
        attemptStartedNanos = System.nanoTime();
        attemptActive = true;
    }

    void failedAttempt(Throwable error) {
        recordAttempt("FAILED", error, false);
    }

    void received(JsonNode chunk) {
        streamUsageEstimate.received(chunk);
    }

    /**
     * 流是否已经产出了该请求所预留的全部内容。
     *
     * <p>供应商已经被告知 {@code max_tokens = effectiveCap}，但供应商可以无视它，而一旦无视
     * 就会生成客户的预付余额从未覆盖的 token。因此这里也会把流切断，切点就是收到的输出达到
     * 预留所持有的容量之时。到那一刻为止实际产出的内容才是参与结算的内容，完全等同于一个
     * 遵守了自身限制的供应商会产出的内容。
     */
    boolean outputCapReached() {
        return streamUsageEstimate.outputCapReached();
    }

    /**
     * 这条流是否**因为撞上输出上限而被切断**（确实产出了内容，只是没写完）。
     *
     * <p>用于给客户端一个诚实的终态：命中上限时最后一个内容块的 {@code finish_reason} 必须是
     * {@code length}，而不是让流看起来像正常结束。见
     * {@link ChatCompletionUsageEstimator.StreamUsageEstimate#truncatedByOutputCap()}。
     */
    boolean truncatedByOutputCap() {
        return streamUsageEstimate.truncatedByOutputCap();
    }

    ProviderAdapter.TokenUsage usage() {
        ProviderAdapter.TokenUsage actual = actualUsage.get();
        return actual == null ? streamUsageEstimate.usage() : actual;
    }

    /**
     * 记下上游给出的结束原因。
     *
     * <p>在序列化器里逐块调用；只有非空值会被写入，因此"上游没给"与"上游给了空串"
     * 都保持 null，而不会把一个空字符串当成一个原因存进用量行。
     *
     * <p>只接受白名单形状的值（字母、数字、下划线、连字符），长度上限 32——与
     * {@code usage_log.finish_reason} 的列宽一致。上游理论上可以在这一个字段里塞任意
     * 文本，而它会落进一张给运维看的表：把上游原文无过滤地写进数据库，是本次改造
     * 在别处（{@code ProviderException.reason()}）已经明确拒绝过的做法，这里保持同一口径。
     */
    void observeFinishReason(String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty() || trimmed.length() > 32
                || !trimmed.matches("[A-Za-z0-9_-]+")) {
            return;
        }
        finishReason.set(trimmed);
    }

    void finish(String outcome, Throwable error) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        recordAttempt(outcome, error, true);
        List<Stage> completedStages = List.copyOf(stages);
        ChatCompletionUsageAccounting.AggregatedUsage aggregated =
                usageAccounting.aggregate(completedStages);
        Report report = report(outcome, error, completedStages, aggregated, true);
        boolean customerBillable = completedStages.stream().anyMatch(Stage::customerBillable);
        boolean settleCustomerUsage = "SUCCEEDED".equals(outcome)
                || ("CANCELLED".equals(outcome) && customerBillable);
        Mono<Void> reservationAccounting = Mono.defer(() -> settleCustomerUsage
                                ? settleAndRecordCredits(
                                        usageAccounting.quotaUsage(aggregated.usage()),
                                        aggregated.quality())
                                : quotaService.release(
                                        context.reservationId(),
                                        "CANCELLED".equals(outcome)
                                                ? QuotaService.ReleaseReason.CLIENT_CANCELLED
                                                : QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        Mono<Void> work = usageAccounting.settleReleaseAndLog(
                reliable(reservationAccounting),
                reliable(Mono.defer(() -> requestLimitService.release(lease()))),
                reliable(Mono.defer(() -> logWriter.apply(report))));
        withFinalFailureLogging(withLoggingContext(work)).subscribe(
                ignored -> { },
                failure -> { });
    }

    Mono<Void> fail(Throwable error) {
        if (!finished.compareAndSet(false, true)) {
            return Mono.empty();
        }
        // **收尾這次失敗的 attempt。** 過去這裡直接讀 stages，於是「首段已發出之後才失敗」
        // 的請求會在用量記錄裡完全沒有 attempt —— 一筆真實發生、供應商可能已經計費的調用
        // 因此不留痕跡（成本側因此無從對帳），而用戶側那筆又被判成不計費。finish() 一直有
        // 這一行，fail() 沒有，這個不對稱就是缺口。
        //
        // recordAttempt 內部以 attemptActive 自我保護：已經走過 failedAttempt() 的調用點
        // 不會被記錄第二次，因此這次補齊既不重複計費、也不改動既有的預失敗路徑。
        recordAttempt("FAILED", error, true);
        List<Stage> completedStages = List.copyOf(stages);
        ChatCompletionUsageAccounting.AggregatedUsage aggregated =
                usageAccounting.aggregate(completedStages);
        boolean customerBillable =
                completedStages.stream().anyMatch(Stage::customerBillable);
        Mono<Void> reservationAccounting = Mono.defer(() -> customerBillable
                ? settleAndRecordCredits(
                                usageAccounting.quotaUsage(aggregated.usage()),
                                aggregated.quality())
                : quotaService.release(
                        context.reservationId(), QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        Mono<Void> log = Mono.defer(() -> logWriter.apply(
                report("FAILED", error, completedStages, aggregated, false)));
        return withFinalFailureLogging(withLoggingContext(usageAccounting.settleReleaseAndLog(
                reliable(reservationAccounting),
                reliable(Mono.defer(() -> requestLimitService.release(lease()))),
                reliable(log))));
    }

    /**
     * 结算该流，并把实际针对套餐计费的积分交给已售出的每分钟积分速率
     * （{@code tokens_per_minute}，显示为 积分/分钟）。这覆盖了流结束的三种方式：
     * {@code SUCCEEDED}、响应 {@code doFinally} 中可计费的 {@code CANCELLED}，
     * 以及可计费的 {@code FAILED}。记录器立即返回，因此速率计数器既不能延迟、也不能让一个
     * 已经服务完成的流的结算失败。
     */
    private Mono<Void> settleAndRecordCredits(
            QuotaService.TokenUsage usage, QuotaService.UsageQuality quality) {
        return quotaService
                .settle(context.reservationId(), usage, quality)
                .doOnNext(settlement -> requestLimitService.recordPackageConsumption(
                        context.tokenLimit(), settlement.chargedTokens()))
                .then();
    }

    private Mono<Void> withFinalFailureLogging(Mono<Void> work) {
        return work.doOnError(failure -> LOG.error(
                "stream terminal accounting failed for request {} reservation {}",
                context.requestId(), context.reservationId(), failure));
    }

    private Mono<Void> reliable(Mono<Void> work) {
        return work.retryWhen(Retry.max(2)
                .filter(this::retryable)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    private boolean retryable(Throwable failure) {
        if (failure instanceof org.springframework.web.server.ResponseStatusException response) {
            return response.getStatusCode().is5xxServerError();
        }
        if (failure instanceof com.yuzhihuihe.apiplatform.gateway.billing.WalletUsageClient
                .WalletUsageClientException wallet) {
            return wallet.status() == null || wallet.status() >= 500;
        }
        return true;
    }

    private Report report(
            String outcome,
            Throwable error,
            List<Stage> completedStages,
            ChatCompletionUsageAccounting.AggregatedUsage aggregated,
            boolean includeFirstToken) {
        return new Report(
                target.get(),
                aggregated.usage(),
                aggregated.quality(),
                includeFirstToken && firstTokenMs.get() >= 0 ? firstTokenMs.get() : null,
                Math.max(0, Duration.between(context.startedAt(), Instant.now()).toMillis()),
                outcome,
                error,
                completedStages,
                generationStatus(outcome),
                finishReason.get(),
                context.effectiveMaxOutputTokens(),
                context.ceilingSource(),
                completedStages.size());
    }

    /**
     * 生成终态：{@code completed} / {@code failed} / {@code truncated}。
     *
     * <p><b>为什么必须有第三个值。</b>一次被输出上限截断的请求在 {@code outcome} 上是
     * SUCCEEDED、在 {@code http_status} 上是 200、在 {@code error_summary} 上是空——
     * 也就是说它在现有的用量记录里与一次自然结束的请求**完全无法区分**。2026-10-15
     * 桌面端对同一次请求记下了 {@code finish_reason=length} 与
     * {@code model_output_truncated}，而中台这边什么都没有，读报表的人只能得出
     * "这个任务完成了"的结论。这一列就是那个区分。
     *
     * <p>判据用截断标记而不是 {@code finish_reason}：上游自己也可能返回
     * {@code length}（例如它自己的默认上限），那同样是"没有写完"，截断标记与
     * finish_reason 两条通路在这里都必须落到 truncated 上。
     */
    private String generationStatus(String outcome) {
        if (truncatedByOutputCap()) {
            return "truncated";
        }
        return "SUCCEEDED".equals(outcome) ? "completed" : "failed";
    }

    private void recordAttempt(String outcome, Throwable error, boolean estimateIfMissing) {
        if (!attemptActive) {
            return;
        }
        ProviderAdapter.TokenUsage actual = actualUsage.get();
        boolean succeeded = "SUCCEEDED".equals(outcome);
        boolean outputWasObserved = firstTokenMs.get() >= 0 || actual != null;
        boolean customerBillable = succeeded || ("CANCELLED".equals(outcome) && outputWasObserved);
        boolean providerBillable = actual != null || succeeded;
        ProviderAdapter.TokenUsage usage = actual != null
                ? actual
                : estimateIfMissing && (succeeded || customerBillable)
                        ? streamUsageEstimate.usage()
                        : new ProviderAdapter.TokenUsage(0, 0, 0);
        QuotaService.UsageQuality quality = actual == null
                ? QuotaService.UsageQuality.ESTIMATED
                : QuotaService.UsageQuality.ACTUAL;
        Target attemptTarget = target.get();
        stages.add(new Stage(
                attemptTarget.routingModelId(),
                attemptTarget.routingModelAlias(),
                attemptTarget.upstreamModel(),
                attemptTarget.providerId(),
                attemptTarget.channelId(),
                usage,
                quality,
                TimeUnit.NANOSECONDS.toMillis(
                        Math.max(0, System.nanoTime() - attemptStartedNanos)),
                outcome,
                usageAccounting.failureCategory(error),
                customerBillable,
                providerBillable));
        attemptActive = false;
    }

    private Mono<Void> withLoggingContext(Mono<Void> work) {
        return work.contextWrite(value -> value
                .put("traceId", context.traceId())
                .put("apiKeyPrefix", context.apiKeyPrefix()));
    }

    record Context(
            UUID reservationId,
            long inputEstimate,
            long reservationEstimate,
            Instant startedAt,
            String requestId,
            String traceId,
            String apiKeyPrefix,
            boolean routeActivation,
            RequestLimitService.PackageTokenLimit tokenLimit,
            /** 生效上限；{@code null} 表示"未知"（见 Report 里的同名字段说明）。 */
            Long effectiveMaxOutputTokens,
            String ceilingSource) {

        Context {
            tokenLimit = tokenLimit == null
                    ? RequestLimitService.PackageTokenLimit.unlimited() : tokenLimit;
        }

        Context(
                UUID reservationId,
                long inputEstimate,
                long reservationEstimate,
                Instant startedAt,
                String requestId,
                String traceId,
                String apiKeyPrefix,
                boolean routeActivation) {
            this(reservationId, inputEstimate, reservationEstimate, startedAt, requestId, traceId,
                    apiKeyPrefix, routeActivation, RequestLimitService.PackageTokenLimit.unlimited(),
                    // 上限"未知"：0 与 null 是正确表达，不拿一个像 65536 的默认值冒充真实上限。
                    0L, null);
        }

        /**
         * 带限流租约、但没有上限的旧签名。
         *
         * <p>与三参数那版同理：上限只有在真正算出来的那条路径上才是已知的，其余场合
         * 留空表示"未知"比让每个构造点写一遍 {@code 0L, null} 更诚实也更省。
         */
        Context(
                UUID reservationId,
                long inputEstimate,
                long reservationEstimate,
                Instant startedAt,
                String requestId,
                String traceId,
                String apiKeyPrefix,
                boolean routeActivation,
                RequestLimitService.PackageTokenLimit tokenLimit) {
            this(reservationId, inputEstimate, reservationEstimate, startedAt, requestId, traceId,
                    apiKeyPrefix, routeActivation, tokenLimit, 0L, null);
        }
    }

    record Target(
            UUID routingModelId,
            String routingModelAlias,
            String upstreamModel,
            UUID providerId,
            UUID channelId) { }

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
            implements ChatCompletionUsageAccounting.CustomerUsageStage { }

    record Report(
            Target target,
            ProviderAdapter.TokenUsage usage,
            QuotaService.UsageQuality quality,
            Long firstTokenMs,
            long durationMs,
            String outcome,
            Throwable error,
            List<Stage> stages,
            /** {@code completed} / {@code failed} / {@code truncated}。 */
            String generationStatus,
            /** 上游给出的结束原因；从未观察到时为 null。 */
            String finishReason,
            /**
             * 本次请求实际被准入的那个输出上限；{@code null} 表示未知。
             *
             * <p>用包装类型而不是 {@code long}：这块信息要落进
             * {@code usage_log.effective_max_output_tokens}，而那一列可空——把"未知"
             * 写成 0 会让报表显示一个从未被采纳的上限。
             */
            Long effectiveMaxOutputTokens,
            String ceilingSource,
            int attemptCount) {

        Report {
            stages = List.copyOf(stages);
        }
    }
}
