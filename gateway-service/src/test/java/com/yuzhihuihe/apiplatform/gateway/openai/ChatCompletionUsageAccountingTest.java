package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class ChatCompletionUsageAccountingTest {

    private final ChatCompletionUsageAccounting accounting =
            new ChatCompletionUsageAccounting();

    @Test
    void aggregatesOnlyCustomerBillableStagesAndPropagatesEstimatedQuality() {
        List<Stage> stages = List.of(
                stage(90, 9, 99, QuotaService.UsageQuality.ACTUAL, false),
                stage(10, 2, 12, QuotaService.UsageQuality.ACTUAL, true),
                stage(3, 4, 7, QuotaService.UsageQuality.ESTIMATED, true));

        ChatCompletionUsageAccounting.AggregatedUsage aggregated =
                accounting.aggregate(stages);

        assertThat(aggregated.usage())
                .isEqualTo(new ProviderAdapter.TokenUsage(13, 6, 19));
        assertThat(aggregated.quality()).isEqualTo(QuotaService.UsageQuality.ESTIMATED);
    }

    @Test
    void preservesTheSingleCustomerBillableStageExactly() {
        ProviderAdapter.TokenUsage usage = new ProviderAdapter.TokenUsage(5, 7, 12);

        ChatCompletionUsageAccounting.AggregatedUsage aggregated = accounting.aggregate(List.of(
                new Stage(usage, QuotaService.UsageQuality.ACTUAL, true)));

        assertThat(aggregated.usage()).isSameAs(usage);
        assertThat(aggregated.quality()).isEqualTo(QuotaService.UsageQuality.ACTUAL);
    }

    @Test
    void noCustomerBillableStageProducesEstimatedZeroUsage() {
        ChatCompletionUsageAccounting.AggregatedUsage aggregated = accounting.aggregate(List.of(
                stage(9, 8, 17, QuotaService.UsageQuality.ACTUAL, false)));

        assertThat(aggregated.usage()).isEqualTo(new ProviderAdapter.TokenUsage(0, 0, 0));
        assertThat(aggregated.quality()).isEqualTo(QuotaService.UsageQuality.ESTIMATED);
    }

    @Test
    void aggregationRetainsExactOverflowFailure() {
        assertThatThrownBy(() -> accounting.aggregate(List.of(
                stage(Long.MAX_VALUE, 0, Long.MAX_VALUE,
                        QuotaService.UsageQuality.ACTUAL, true),
                stage(1, 0, 1, QuotaService.UsageQuality.ACTUAL, true))))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void appendsAnImmutableCopyAndConvertsQuotaUsageExactly() {
        List<String> original = new ArrayList<>(List.of("first"));

        List<String> appended = accounting.append(original, "second");
        original.add("later");

        assertThat(appended).containsExactly("first", "second");
        assertThatThrownBy(() -> appended.add("third"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(accounting.quotaUsage(new ProviderAdapter.TokenUsage(2, 3, 5)))
                .isEqualTo(new QuotaService.TokenUsage(2, 3, 5));
    }

    @Test
    void errorSummariesExposeOnlyStableCategoriesAndStatuses() {
        assertThat(accounting.safeErrorSummary(null)).isNull();
        assertThat(accounting.safeErrorSummary(
                new ProviderAdapter.ProviderException(429, "provider secret")))
                .isEqualTo("ProviderException(status=429)");
        assertThat(accounting.safeErrorSummary(new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "request secret")))
                .isEqualTo("ResponseStatusException(status=400)");
        assertThat(accounting.safeErrorSummary(new IllegalStateException("internal secret")))
                .isEqualTo("IllegalStateException");
    }

    /**
     * 这四条原因码是 2026-10-14 那 11 次生产失败唯一无法区分的东西。
     *
     * <p>它们过去全部落成 {@code ProviderException(status=502)}，于是「上游真的返回了非 2xx」
     * 与「上游流少了 [DONE]」在用量表里长得一模一样，而这两件事要分别去找供应商和找网关。
     */
    @Test
    void usageStagesCarryTheSafeReasonCodeThatDistinguishesProviderFromProtocolFailure() {
        assertThat(accounting.failureCategory(
                new ProviderAdapter.ProviderException(502, "provider_http_502: model_not_found")))
                .isEqualTo("provider_http_502");
        assertThat(accounting.failureCategory(
                new ProviderAdapter.ProviderException(502, "provider_stream_missing_done")))
                .isEqualTo("provider_stream_missing_done");
        assertThat(accounting.failureCategory(
                new ProviderAdapter.ProviderException(502, "invalid_provider_stream_chunk")))
                .isEqualTo("invalid_provider_stream_chunk");
        assertThat(accounting.failureCategory(
                new ProviderAdapter.ProviderException(504, "provider_attempt_timeout")))
                .isEqualTo("provider_attempt_timeout");
    }

    @Test
    void errorSummaryCarriesTheReasonCodeAfterTheStatus() {
        assertThat(accounting.safeErrorSummary(
                new ProviderAdapter.ProviderException(502, "provider_stream_missing_done")))
                .isEqualTo("ProviderException(status=502: provider_stream_missing_done)");
        assertThat(accounting.safeErrorSummary(
                new ProviderAdapter.ProviderException(502, "provider_http_502: model_not_found")))
                .isEqualTo("ProviderException(status=502: provider_http_502)");
    }

    /**
     * 原因码的白名单：**只有网关自己写下的标识符才可能落进用量表**。
     *
     * <p>自由文本、上游原文、带空格或大写的东西一律退回类名。这不是稳妥起见，而是必须的：
     * 原因码会被持久化，而一次上游报错里可能带着与本次调用无关的内容。
     */
    @Test
    void onlyGatewayAuthoredIdentifiersAreAcceptedAsReasonCodes() {
        assertThat(new ProviderAdapter.ProviderException(429, "provider secret").reason())
                .isNull();
        assertThat(new ProviderAdapter.ProviderException(502, "Provider_HTTP_502").reason())
                .isNull();
        assertThat(new ProviderAdapter.ProviderException(502, "upstream said: nope").reason())
                .isNull();
        assertThat(new ProviderAdapter.ProviderException(400, "invalid_request").reason())
                .isEqualTo("invalid_request");
    }

    @Test
    void unreasonedProviderFailuresStillReportTheStableExceptionCategory() {
        assertThat(accounting.failureCategory(new ProviderAdapter.ProviderException(
                429, "provider secret")))
                .isEqualTo("ProviderException");
        assertThat(accounting.failureCategory(new IllegalStateException("secret")))
                .isEqualTo("IllegalStateException");
        assertThat(accounting.failureCategory(null)).isNull();
    }

    @Test
    void performsSettlementReleaseAndLogSequentially() {
        List<String> calls = new ArrayList<>();
        AtomicBoolean economicsSuppressed = new AtomicBoolean(true);

        accounting.settleReleaseAndLog(
                        action(calls, "settle"),
                        action(calls, "release"),
                        Mono.deferContextual(context -> {
                            calls.add("log");
                            economicsSuppressed.set(context.getOrDefault(
                                    ChatCompletionService.SUPPRESS_USAGE_ECONOMICS_CONTEXT_KEY, false));
                            return Mono.empty();
                        }))
                .block();

        assertThat(calls).containsExactly("settle", "release", "log");
        assertThat(economicsSuppressed).isFalse();
    }

    @Test
    void settlementFailureStillReleasesAndLogsWithEconomicsSuppressed() {
        List<String> calls = new ArrayList<>();
        IllegalStateException settlementFailure = new IllegalStateException("settlement");
        IllegalStateException releaseFailure = new IllegalStateException("release");
        IllegalStateException logFailure = new IllegalStateException("log");
        AtomicBoolean economicsSuppressed = new AtomicBoolean();

        Mono<Void> result = accounting.settleReleaseAndLog(
                failingAction(calls, "settle", settlementFailure),
                failingAction(calls, "release", releaseFailure),
                Mono.deferContextual(context -> {
                    calls.add("log");
                    economicsSuppressed.set(context.getOrDefault(
                            ChatCompletionService.SUPPRESS_USAGE_ECONOMICS_CONTEXT_KEY, false));
                    return Mono.error(logFailure);
                }));

        assertThatThrownBy(result::block).isSameAs(settlementFailure);
        assertThat(calls).containsExactly("settle", "release", "log");
        assertThat(economicsSuppressed).isTrue();
    }

    @Test
    void releaseFailureWinsOverALaterLogFailureWithoutSuppressingEconomics() {
        List<String> calls = new ArrayList<>();
        IllegalStateException releaseFailure = new IllegalStateException("release");
        IllegalStateException logFailure = new IllegalStateException("log");
        AtomicBoolean economicsSuppressed = new AtomicBoolean(true);

        Mono<Void> result = accounting.settleReleaseAndLog(
                action(calls, "settle"),
                failingAction(calls, "release", releaseFailure),
                Mono.deferContextual(context -> {
                    calls.add("log");
                    economicsSuppressed.set(context.getOrDefault(
                            ChatCompletionService.SUPPRESS_USAGE_ECONOMICS_CONTEXT_KEY, false));
                    return Mono.error(logFailure);
                }));

        assertThatThrownBy(result::block).isSameAs(releaseFailure);
        assertThat(calls).containsExactly("settle", "release", "log");
        assertThat(economicsSuppressed).isFalse();
    }

    private static Stage stage(
            long input,
            long output,
            long total,
            QuotaService.UsageQuality quality,
            boolean customerBillable) {
        return new Stage(
                new ProviderAdapter.TokenUsage(input, output, total), quality, customerBillable);
    }

    private static Mono<Void> action(List<String> calls, String name) {
        return Mono.defer(() -> {
            calls.add(name);
            return Mono.empty();
        });
    }

    private static Mono<Void> failingAction(
            List<String> calls, String name, RuntimeException failure) {
        return Mono.defer(() -> {
            calls.add(name);
            return Mono.error(failure);
        });
    }

    private record Stage(
            ProviderAdapter.TokenUsage usage,
            QuotaService.UsageQuality quality,
            boolean customerBillable)
            implements ChatCompletionUsageAccounting.CustomerUsageStage { }
}
