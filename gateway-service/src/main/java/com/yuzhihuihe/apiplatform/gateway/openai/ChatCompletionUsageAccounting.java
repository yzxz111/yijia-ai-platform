package com.yuzhihuihe.apiplatform.gateway.openai;

import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;

final class ChatCompletionUsageAccounting {

    static final String SUPPRESS_USAGE_ECONOMICS_CONTEXT_KEY = "suppressUsageEconomics";

    Mono<Void> settleReleaseAndLog(
            Mono<Void> settlement, Mono<Void> release, Mono<Void> log) {
        return settlement.materialize().flatMap(settlementSignal ->
                release.materialize().flatMap(releaseSignal ->
                        (settlementSignal.isOnError()
                                ? log.contextWrite(context -> context.put(
                                        SUPPRESS_USAGE_ECONOMICS_CONTEXT_KEY, true))
                                : log)
                        .materialize().flatMap(logSignal -> {
                            Throwable failure = firstFailure(
                                    settlementSignal, releaseSignal, logSignal);
                            return failure == null ? Mono.empty() : Mono.error(failure);
                        })));
    }

    QuotaService.TokenUsage quotaUsage(ProviderAdapter.TokenUsage usage) {
        // 供应商已经上报了缓存与推理的拆分，成本侧也已经为它们定价；在这里透传它们，
        // 正是让零售侧能够把命中缓存计在未命中之下、而不是按完整输入价计费的原因。
        // 不上报拆分的供应商会让这些值为 null，此时计费与以往完全一致。
        //
        // 缓存 WRITE 计数也会被透传。价目表把写入定价在未命中之上
        // （供应商会为填充缓存收取溢价），而计算器在应用输入价之前
        // 会先把写入 token 从基础输入计数中剔除，因此遗漏它们会悄悄把每次写入
        // 都按未命中价计费。
        return new QuotaService.TokenUsage(
                usage.inputTokens(), usage.outputTokens(), usage.totalTokens(),
                usage.cacheHitTokens(), usage.reasoningTokens(), usage.cacheWriteTokens());
    }

    <T> List<T> append(List<T> values, T value) {
        List<T> appended = new ArrayList<>(values.size() + 1);
        appended.addAll(values);
        appended.add(value);
        return List.copyOf(appended);
    }

    AggregatedUsage aggregate(List<? extends CustomerUsageStage> stages) {
        long input = 0;
        long output = 0;
        boolean estimated = false;
        CustomerUsageStage singleBillable = null;
        int billableCount = 0;
        for (CustomerUsageStage stage : stages) {
            if (!stage.customerBillable()) {
                continue;
            }
            singleBillable = stage;
            billableCount++;
            input = Math.addExact(input, stage.usage().inputTokens());
            output = Math.addExact(output, stage.usage().outputTokens());
            estimated |= stage.quality() == QuotaService.UsageQuality.ESTIMATED;
        }
        if (billableCount == 1) {
            return new AggregatedUsage(singleBillable.usage(), singleBillable.quality());
        }
        return new AggregatedUsage(
                new ProviderAdapter.TokenUsage(input, output, Math.addExact(input, output)),
                billableCount == 0 || estimated
                        ? QuotaService.UsageQuality.ESTIMATED
                        : QuotaService.UsageQuality.ACTUAL);
    }

    /**
     * 用量日志 {@code error_summary} 的内容：异常类 + 受控原因码。
     *
     * <p>过去这里只有 {@code "ProviderException(status=502)"}，于是 2026-10-14 的 11 次生产
     * 失败在库里的摘要完全一致，无法区分「上游非 2xx」「流缺 [DONE]」「响应块非法」
     * 「用量字段矛盾」。这四种的定责方不同，因此原因码必须活到这条记录里
     * （{@link ProviderAdapter.ProviderException#reason()}）。
     *
     * <p>格式 `{@code <类名>(<键=值>[: <原因码>])}`：冒号仍与
     * {@code "ProviderException(status=502)"} 一样是类名与细节的分隔符，因此按前缀或
     * 按状态码匹配的既有解析不会被破坏。原因码为 null（异常 message 不是受控标识符）
     * 时整段省略，逐字退回旧格式——这条路径同时保证**自由文本永远不会被写进用量表**，
     * 而它正是 {@code errorSummariesExposeOnlyStableCategoriesAndStatuses} 钉住的契约。
     */
    /**
     * {@code usage_stage.error_category} 的内容：本次尝试失败的**稳定分类**。
     *
     * <p>上游失败优先取 {@link ProviderAdapter.ProviderException#reason()} —— 这才是
     * 分得清「上游真的返回非 2xx」（{@code provider_http_502}）与「上游流少了 [DONE]」
     * （{@code provider_stream_missing_done}）的那一列。只写 {@code "ProviderException"}
     * 等于把四种处置方不同的原因压成同一个格子：2026-10-14 的 11 次生产失败因此在库里
     * 完全不可区分，定责被迫停在生产日志、无法只靠用量表完成。
     *
     * <p>没有可用原因码时（自由文本异常、或其它异常类型）退回类名：那已经是稳定的分类，
     * 而且比 null 有用。该列是 {@code error_category varchar(64)}，这里可能写入的取值
     * 都远低于上限，且全部是网关自己写下的常量或类名，不含任何厂商原文。
     */
    String failureCategory(Throwable error) {
        if (error == null) {
            return null;
        }
        if (error instanceof ProviderAdapter.ProviderException provider
                && provider.reason() != null) {
            return provider.reason();
        }
        return error.getClass().getSimpleName();
    }

    String safeErrorSummary(Throwable error) {
        if (error == null) {
            return null;
        }
        if (error instanceof ProviderAdapter.ProviderException provider) {
            String reason = provider.reason();
            return "ProviderException(status=" + provider.status()
                    + (reason == null ? "" : ": " + reason) + ")";
        }
        if (error instanceof ResponseStatusException response) {
            return "ResponseStatusException(status=" + response.getStatusCode().value() + ")";
        }
        return error.getClass().getSimpleName();
    }

    @SafeVarargs
    private final Throwable firstFailure(Signal<Void>... signals) {
        for (Signal<Void> signal : signals) {
            if (signal.isOnError()) {
                return signal.getThrowable();
            }
        }
        return null;
    }

    interface CustomerUsageStage {
        ProviderAdapter.TokenUsage usage();

        QuotaService.UsageQuality quality();

        boolean customerBillable();
    }

    record AggregatedUsage(
            ProviderAdapter.TokenUsage usage,
            QuotaService.UsageQuality quality) { }
}
