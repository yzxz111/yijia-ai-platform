package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ChatCompletionSettlementTest {

    private static final UUID RESERVATION_ID =
            UUID.fromString("30000000-0000-4000-8000-000000000101");
    private static final UUID KEY_ID =
            UUID.fromString("30000000-0000-4000-8000-000000000102");
    private static final UUID USER_ID =
            UUID.fromString("30000000-0000-4000-8000-000000000103");
    private static final UUID MODEL_ID =
            UUID.fromString("30000000-0000-4000-8000-000000000104");
    private static final UUID PROVIDER_A =
            UUID.fromString("30000000-0000-4000-8000-000000000105");
    private static final UUID PROVIDER_B =
            UUID.fromString("30000000-0000-4000-8000-000000000106");
    private static final UUID CHANNEL_A =
            UUID.fromString("30000000-0000-4000-8000-000000000107");
    private static final UUID CHANNEL_B =
            UUID.fromString("30000000-0000-4000-8000-000000000108");

    private final ChatCompletionUsageAccounting usageAccounting =
            new ChatCompletionUsageAccounting();

    private QuotaService quota;
    private RequestLimitService limits;
    private RequestLimitService.LimitLease leaseA;
    private RequestLimitService.LimitLease leaseB;

    @BeforeEach
    void setUp() {
        quota = mock(QuotaService.class);
        limits = mock(RequestLimitService.class);
        leaseA = lease(CHANNEL_A);
        leaseB = lease(CHANNEL_B);
        when(quota.settle(any(), any(), any())).thenAnswer(invocation -> Mono.just(
                new QuotaService.Settlement(
                        invocation.getArgument(0), 1, 1, 0, "SETTLED",
                        invocation.getArgument(2))));
        when(quota.release(any(), any())).thenReturn(Mono.empty());
        when(limits.release(any())).thenReturn(Mono.empty());
    }

    @Test
    void successfulCompletionSettlesThenReleasesThenLogs() {
        List<String> calls = new ArrayList<>();
        AtomicReference<ChatCompletionSettlement.Report> report = new AtomicReference<>();
        when(quota.settle(any(), any(), any())).thenAnswer(invocation -> Mono.defer(() -> {
            calls.add("settle");
            return Mono.just(new QuotaService.Settlement(
                    invocation.getArgument(0), 14, 14, 0, "SETTLED",
                    invocation.getArgument(2)));
        }));
        when(limits.release(leaseA)).thenReturn(Mono.defer(() -> {
            calls.add("release");
            return Mono.empty();
        }));
        ChatCompletionSettlement settlement = settlement(
                target(PROVIDER_A, CHANNEL_A), leaseA, value -> Mono.fromRunnable(() -> {
                    calls.add("log");
                    report.set(value);
                }));

        settlement.beginAttempt(target(PROVIDER_A, CHANNEL_A));
        settlement.upstreamStarted();
        settlement.actualUsage(new ProviderAdapter.TokenUsage(10, 4, 14));
        settlement.succeed(
                        new ProviderAdapter.TokenUsage(10, 4, 14),
                        QuotaService.UsageQuality.ACTUAL)
                .block();

        assertThat(calls).containsExactly("settle", "release", "log");
        verify(quota).settle(
                RESERVATION_ID, new QuotaService.TokenUsage(10, 4, 14),
                QuotaService.UsageQuality.ACTUAL);
        assertThat(report.get().outcome()).isEqualTo("SUCCEEDED");
        assertThat(report.get().stages()).singleElement().satisfies(stage -> {
            assertThat(stage.customerBillable()).isTrue();
            assertThat(stage.providerBillable()).isTrue();
        });
    }

    @Test
    void retryRetainsFailedProviderUsageButBillsOnlyTheSuccessfulAttempt() {
        AtomicReference<ChatCompletionSettlement.Report> report = new AtomicReference<>();
        ChatCompletionSettlement settlement = settlement(
                target(PROVIDER_A, CHANNEL_A), leaseA,
                value -> Mono.fromRunnable(() -> report.set(value)));

        settlement.beginAttempt(target(PROVIDER_A, CHANNEL_A));
        settlement.upstreamStarted();
        settlement.actualUsage(new ProviderAdapter.TokenUsage(7, 3, 10));
        settlement.failedAttempt(new IllegalStateException("first failed"));
        settlement.lease(leaseB);
        settlement.beginAttempt(target(PROVIDER_B, CHANNEL_B));
        settlement.upstreamStarted();
        settlement.succeed(
                        new ProviderAdapter.TokenUsage(12, 1, 13),
                        QuotaService.UsageQuality.ESTIMATED)
                .block();

        verify(quota).settle(
                RESERVATION_ID, new QuotaService.TokenUsage(12, 1, 13),
                QuotaService.UsageQuality.ESTIMATED);
        verify(limits).release(leaseB);
        assertThat(report.get().target()).isEqualTo(target(PROVIDER_B, CHANNEL_B));
        assertThat(report.get().stages()).hasSize(2);
        assertThat(report.get().stages().getFirst()).satisfies(stage -> {
            assertThat(stage.usage()).isEqualTo(new ProviderAdapter.TokenUsage(7, 3, 10));
            assertThat(stage.customerBillable()).isFalse();
            assertThat(stage.providerBillable()).isTrue();
            assertThat(stage.outcome()).isEqualTo("FAILED");
        });
        assertThat(report.get().stages().getLast()).satisfies(stage -> {
            assertThat(stage.usage()).isEqualTo(new ProviderAdapter.TokenUsage(12, 1, 13));
            assertThat(stage.customerBillable()).isTrue();
            assertThat(stage.providerBillable()).isTrue();
            assertThat(stage.outcome()).isEqualTo("SUCCEEDED");
        });
    }

    @Test
    void providerRejectionBeforeInvocationCreatesNoStageAndReleasesReservation() {
        AtomicReference<ChatCompletionSettlement.Report> report = new AtomicReference<>();
        ChatCompletionSettlement settlement = settlement(
                target(PROVIDER_A, CHANNEL_A), leaseA,
                value -> Mono.fromRunnable(() -> report.set(value)));

        settlement.beginAttempt(target(PROVIDER_A, CHANNEL_A));
        settlement.fail(new IllegalStateException("circuit open")).block();

        verify(quota, never()).settle(any(), any(), any());
        verify(quota).release(RESERVATION_ID, QuotaService.ReleaseReason.UPSTREAM_FAILURE);
        verify(limits).release(leaseA);
        assertThat(report.get().usage()).isEqualTo(new ProviderAdapter.TokenUsage(0, 0, 0));
        assertThat(report.get().stages()).isEmpty();
    }

    @Test
    void responseFailureReleasesCustomerQuotaAndRetainsProviderUsageOnly() {
        AtomicReference<ChatCompletionSettlement.Report> report = new AtomicReference<>();
        ChatCompletionSettlement settlement = settlement(
                target(PROVIDER_A, CHANNEL_A), leaseA,
                value -> Mono.fromRunnable(() -> report.set(value)));
        IllegalStateException failure = new IllegalStateException("rewrite failed");

        settlement.beginAttempt(target(PROVIDER_A, CHANNEL_A));
        settlement.upstreamStarted();
        settlement.failResponse(
                        new ProviderAdapter.TokenUsage(12, 2, 14),
                        QuotaService.UsageQuality.ESTIMATED,
                        failure)
                .block();

        verify(quota, never()).settle(any(), any(), any());
        verify(quota).release(RESERVATION_ID, QuotaService.ReleaseReason.UPSTREAM_FAILURE);
        assertThat(report.get().usage()).isEqualTo(new ProviderAdapter.TokenUsage(0, 0, 0));
        assertThat(report.get().quality()).isEqualTo(QuotaService.UsageQuality.ESTIMATED);
        assertThat(report.get().stages()).singleElement().satisfies(stage -> {
            assertThat(stage.usage()).isEqualTo(new ProviderAdapter.TokenUsage(12, 2, 14));
            assertThat(stage.customerBillable()).isFalse();
            assertThat(stage.providerBillable()).isTrue();
            assertThat(stage.errorCategory()).isEqualTo("IllegalStateException");
        });
    }

    @Test
    void settlementFailureStillReleasesAndLogsWithEconomicsSuppressed() {
        List<String> calls = new ArrayList<>();
        AtomicBoolean economicsSuppressed = new AtomicBoolean();
        when(quota.settle(any(), any(), any())).thenReturn(
                Mono.error(new IllegalStateException("settlement unavailable")));
        when(limits.release(leaseA)).thenReturn(Mono.fromRunnable(() -> calls.add("release")));
        ChatCompletionSettlement settlement = settlement(
                target(PROVIDER_A, CHANNEL_A), leaseA, value -> Mono.deferContextual(context -> {
                    calls.add("log");
                    economicsSuppressed.set(context.getOrDefault(
                            ChatCompletionUsageAccounting.SUPPRESS_USAGE_ECONOMICS_CONTEXT_KEY,
                            false));
                    return Mono.empty();
                }));

        settlement.beginAttempt(target(PROVIDER_A, CHANNEL_A));
        settlement.upstreamStarted();

        assertThatThrownBy(() -> settlement.succeed(
                        new ProviderAdapter.TokenUsage(10, 4, 14),
                        QuotaService.UsageQuality.ACTUAL)
                .block())
                .hasMessage("settlement unavailable");
        assertThat(calls).containsExactly("release", "log");
        assertThat(economicsSuppressed).isTrue();
    }

    private ChatCompletionSettlement settlement(
            ChatCompletionSettlement.Target target,
            RequestLimitService.LimitLease lease,
            Function<ChatCompletionSettlement.Report, Mono<Void>> logWriter) {
        return new ChatCompletionSettlement(
                usageAccounting,
                quota,
                limits,
                new ChatCompletionSettlement.Context(
                        RESERVATION_ID, Instant.now().minusMillis(10), false),
                target,
                lease,
                logWriter);
    }

    private static ChatCompletionSettlement.Target target(UUID providerId, UUID channelId) {
        return new ChatCompletionSettlement.Target(
                MODEL_ID, "text-basic", "provider-model", providerId, channelId);
    }

    private static RequestLimitService.LimitLease lease(UUID channelId) {
        return new RequestLimitService.LimitLease(
                UUID.randomUUID(), KEY_ID, USER_ID, "text-basic", channelId,
                100, 10, RequestLimitService.Store.REDIS);
    }
}
