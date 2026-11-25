package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import com.yuzhihuihe.apiplatform.gateway.quota.TokenEstimator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ChatStreamSettlementTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID RESERVATION_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000101");
    private static final UUID KEY_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000102");
    private static final UUID USER_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000103");
    private static final UUID MODEL_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000104");
    private static final UUID PROVIDER_A =
            UUID.fromString("20000000-0000-4000-8000-000000000105");
    private static final UUID PROVIDER_B =
            UUID.fromString("20000000-0000-4000-8000-000000000106");
    private static final UUID CHANNEL_A =
            UUID.fromString("20000000-0000-4000-8000-000000000107");
    private static final UUID CHANNEL_B =
            UUID.fromString("20000000-0000-4000-8000-000000000108");

    private final ChatCompletionUsageEstimator usageEstimator =
            new ChatCompletionUsageEstimator(new TokenEstimator(3, 1.25), JSON);
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
    void successfulFinishSettlesThenReleasesThenLogsWithStructuredContext() {
        List<String> calls = new ArrayList<>();
        AtomicReference<ChatStreamSettlement.Report> report = new AtomicReference<>();
        AtomicReference<String> traceId = new AtomicReference<>();
        AtomicReference<String> apiKeyPrefix = new AtomicReference<>();
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
        ChatStreamSettlement settlement = settlement(
                target(PROVIDER_A, CHANNEL_A), leaseA, value -> Mono.deferContextual(context -> {
                    calls.add("log");
                    report.set(value);
                    traceId.set(context.get("traceId"));
                    apiKeyPrefix.set(context.get("apiKeyPrefix"));
                    return Mono.empty();
                }));

        settlement.beginAttempt(target(PROVIDER_A, CHANNEL_A));
        settlement.upstreamStarted();
        settlement.actualUsage(new ProviderAdapter.TokenUsage(10, 4, 14));
        settlement.firstByte();
        settlement.finish("SUCCEEDED", null);

        assertThat(calls).containsExactly("settle", "release", "log");
        verify(quota).settle(
                RESERVATION_ID, new QuotaService.TokenUsage(10, 4, 14),
                QuotaService.UsageQuality.ACTUAL);
        assertThat(traceId).hasValue("trace-stream");
        assertThat(apiKeyPrefix).hasValue("sk-yzh-stream");
        assertThat(report.get().outcome()).isEqualTo("SUCCEEDED");
        assertThat(report.get().firstTokenMs()).isNotNull().isNotNegative();
        assertThat(report.get().stages()).singleElement().satisfies(stage -> {
            assertThat(stage.customerBillable()).isTrue();
            assertThat(stage.providerBillable()).isTrue();
        });
    }

    @Test
    void retryKeepsProviderFailureButBillsOnlyTheSuccessfulAttempt() {
        AtomicReference<ChatStreamSettlement.Report> report = new AtomicReference<>();
        ChatStreamSettlement settlement = settlement(
                target(PROVIDER_A, CHANNEL_A), leaseA,
                value -> Mono.fromRunnable(() -> report.set(value)));

        settlement.beginAttempt(target(PROVIDER_A, CHANNEL_A));
        settlement.upstreamStarted();
        settlement.actualUsage(new ProviderAdapter.TokenUsage(7, 3, 10));
        settlement.failedAttempt(new IllegalStateException("first failed"));
        settlement.lease(leaseB);
        settlement.beginAttempt(target(PROVIDER_B, CHANNEL_B));
        settlement.upstreamStarted();
        settlement.received(chunk("abc"));
        settlement.finish("SUCCEEDED", null);

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
    void cancellationSettlesConfirmedUsageAndReleasesLeaseOnlyOnce() {
        AtomicReference<ChatStreamSettlement.Report> report = new AtomicReference<>();
        ChatStreamSettlement settlement = settlement(
                target(PROVIDER_A, CHANNEL_A), leaseA,
                value -> Mono.fromRunnable(() -> report.set(value)));

        settlement.beginAttempt(target(PROVIDER_A, CHANNEL_A));
        settlement.upstreamStarted();
        settlement.actualUsage(new ProviderAdapter.TokenUsage(11, 5, 16));
        settlement.finish("CANCELLED", null);
        settlement.finish("CANCELLED", null);
        settlement.fail(new IllegalStateException("late failure")).block();

        // Cancellation does not make already consumed tokens free. The confirmed
        // provider usage is settled once, even if cancellation and a late failure race.
        verify(quota).settle(
                RESERVATION_ID, new QuotaService.TokenUsage(11, 5, 16),
                QuotaService.UsageQuality.ACTUAL);
        verify(quota, never()).release(any(), any());
        verify(limits).release(leaseA);
        assertThat(report.get().outcome()).isEqualTo("CANCELLED");
        assertThat(report.get().usage()).isEqualTo(new ProviderAdapter.TokenUsage(11, 5, 16));
        assertThat(report.get().stages()).singleElement().satisfies(stage -> {
            assertThat(stage.usage()).isEqualTo(new ProviderAdapter.TokenUsage(11, 5, 16));
            assertThat(stage.customerBillable()).isTrue();
            assertThat(stage.providerBillable()).isTrue();
        });
    }

    @Test
    void cancellationAfterUpstreamStartWithoutOutputReleasesReservationAndLeaseOnlyOnce() {
        AtomicInteger logCalls = new AtomicInteger();
        ChatStreamSettlement settlement = settlement(
                target(PROVIDER_A, CHANNEL_A), leaseA,
                report -> Mono.fromRunnable(logCalls::incrementAndGet));

        settlement.beginAttempt(target(PROVIDER_A, CHANNEL_A));
        settlement.upstreamStarted();
        settlement.finish("CANCELLED", null);
        settlement.finish("CANCELLED", null);
        settlement.fail(new IllegalStateException("late failure")).block();

        verify(quota, never()).settle(any(), any(), any());
        verify(quota).release(RESERVATION_ID, QuotaService.ReleaseReason.CLIENT_CANCELLED);
        verify(limits).release(leaseA);
        assertThat(logCalls).hasValue(1);
    }

    @Test
    void cancellationAfterOutputWithoutProviderUsageSettlesEstimatedUsageOnlyOnce() {
        AtomicReference<ChatStreamSettlement.Report> report = new AtomicReference<>();
        ChatStreamSettlement settlement = settlement(
                target(PROVIDER_A, CHANNEL_A), leaseA,
                value -> Mono.fromRunnable(() -> report.set(value)));

        settlement.beginAttempt(target(PROVIDER_A, CHANNEL_A));
        settlement.upstreamStarted();
        settlement.firstByte();
        settlement.received(chunk("abc"));
        settlement.finish("CANCELLED", null);
        settlement.finish("CANCELLED", null);
        settlement.fail(new IllegalStateException("late failure")).block();

        verify(quota).settle(
                RESERVATION_ID, new QuotaService.TokenUsage(12, 1, 13),
                QuotaService.UsageQuality.ESTIMATED);
        verify(quota, never()).release(any(), any());
        verify(limits).release(leaseA);
        assertThat(report.get().usage()).isEqualTo(new ProviderAdapter.TokenUsage(12, 1, 13));
        assertThat(report.get().stages()).singleElement().satisfies(stage -> {
            assertThat(stage.quality()).isEqualTo(QuotaService.UsageQuality.ESTIMATED);
            assertThat(stage.customerBillable()).isTrue();
            assertThat(stage.providerBillable()).isFalse();
        });
    }

    @Test
    void terminalFailureReleasesUpstreamReservationAndIsSubscriberDrivenAndIdempotent() {
        AtomicReference<ChatStreamSettlement.Report> report = new AtomicReference<>();
        AtomicBoolean logged = new AtomicBoolean();
        ChatStreamSettlement settlement = settlement(
                target(PROVIDER_A, CHANNEL_A), leaseA, value -> Mono.fromRunnable(() -> {
                    report.set(value);
                    logged.set(true);
                }));
        IllegalStateException failure = new IllegalStateException("unavailable");

        settlement.beginAttempt(target(PROVIDER_A, CHANNEL_A));
        settlement.upstreamStarted();
        settlement.failedAttempt(failure);
        Mono<Void> work = settlement.fail(failure);

        assertThat(logged).isFalse();
        work.block();
        settlement.fail(new IllegalStateException("late")).block();

        assertThat(logged).isTrue();
        verify(quota).release(RESERVATION_ID, QuotaService.ReleaseReason.UPSTREAM_FAILURE);
        verify(limits).release(leaseA);
        assertThat(report.get().outcome()).isEqualTo("FAILED");
        assertThat(report.get().firstTokenMs()).isNull();
        assertThat(report.get().stages()).singleElement().satisfies(stage ->
                assertThat(stage.errorCategory()).isEqualTo("IllegalStateException"));
    }

    @Test
    void settlementFailureStillReleasesAndLogsWithEconomicsSuppressed() {
        List<String> calls = new ArrayList<>();
        AtomicBoolean economicsSuppressed = new AtomicBoolean();
        when(quota.settle(any(), any(), any())).thenReturn(
                Mono.error(new IllegalStateException("settlement unavailable")));
        when(limits.release(leaseA)).thenReturn(Mono.fromRunnable(() -> calls.add("release")));
        ChatStreamSettlement settlement = settlement(
                target(PROVIDER_A, CHANNEL_A), leaseA, value -> Mono.deferContextual(context -> {
                    calls.add("log");
                    economicsSuppressed.set(context.getOrDefault(
                            ChatCompletionUsageAccounting.SUPPRESS_USAGE_ECONOMICS_CONTEXT_KEY,
                            false));
                    return Mono.empty();
                }));

        settlement.beginAttempt(target(PROVIDER_A, CHANNEL_A));
        settlement.upstreamStarted();
        settlement.actualUsage(new ProviderAdapter.TokenUsage(10, 4, 14));
        settlement.finish("SUCCEEDED", null);

        assertThat(calls).containsExactly("release", "log");
        assertThat(economicsSuppressed).isTrue();
    }

    @Test
    void retriesTransientSettlementBeforeWritingTheUsageLog() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean logged = new AtomicBoolean();
        when(quota.settle(any(), any(), any())).thenAnswer(invocation -> Mono.defer(() -> {
            if (attempts.incrementAndGet() < 3) {
                return Mono.error(new IllegalStateException("database temporarily unavailable"));
            }
            return Mono.just(new QuotaService.Settlement(
                    RESERVATION_ID, 14, 14, 0, "SETTLED",
                    QuotaService.UsageQuality.ACTUAL));
        }));
        ChatStreamSettlement settlement = settlement(
                target(PROVIDER_A, CHANNEL_A), leaseA,
                report -> Mono.fromRunnable(() -> logged.set(true)));

        settlement.beginAttempt(target(PROVIDER_A, CHANNEL_A));
        settlement.upstreamStarted();
        settlement.actualUsage(new ProviderAdapter.TokenUsage(10, 4, 14));
        settlement.finish("SUCCEEDED", null);

        assertThat(attempts).hasValue(3);
        assertThat(logged).isTrue();
        verify(limits).release(leaseA);
    }

    private ChatStreamSettlement settlement(
            ChatStreamSettlement.Target target,
            RequestLimitService.LimitLease lease,
            Function<ChatStreamSettlement.Report, Mono<Void>> logWriter) {
        return new ChatStreamSettlement(
                usageEstimator,
                usageAccounting,
                quota,
                limits,
                new ChatStreamSettlement.Context(
                        RESERVATION_ID, 12, 32, Instant.now().minusMillis(10),
                        "req-stream", "trace-stream", "sk-yzh-stream", true),
                target,
                lease,
                logWriter);
    }

    private static ChatStreamSettlement.Target target(UUID providerId, UUID channelId) {
        return new ChatStreamSettlement.Target(
                MODEL_ID, "text-basic", "provider-model", providerId, channelId);
    }

    private static RequestLimitService.LimitLease lease(UUID channelId) {
        return new RequestLimitService.LimitLease(
                UUID.randomUUID(), KEY_ID, USER_ID, "text-basic", channelId,
                100, 10, RequestLimitService.Store.REDIS);
    }

    private static ObjectNode chunk(String content) {
        ObjectNode chunk = JSON.createObjectNode();
        chunk.putArray("choices")
                .addObject()
                .put("index", 0)
                .putObject("delta")
                .put("content", content);
        return chunk;
    }
}
