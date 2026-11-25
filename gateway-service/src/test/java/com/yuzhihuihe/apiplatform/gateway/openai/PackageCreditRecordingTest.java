package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import com.yuzhihuihe.apiplatform.gateway.quota.TokenEstimator;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 已结算的扣费是已售每分钟积分速率（{@code tokens_per_minute}，显示为 积分/分钟）唯一诚实的
 * 数字，因此聊天请求的每一种结束方式都必须把它交给限流器：已完成的 completion、已完成的流，
 * 以及客户端在流已经产生可计费输出之后才取消的流。没有出售此类速率的套餐必须什么都不记录。
 */
class PackageCreditRecordingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID RESERVATION_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000201");
    private static final UUID KEY_ID = UUID.fromString("20000000-0000-4000-8000-000000000202");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-4000-8000-000000000203");
    private static final UUID PACKAGE_ID = UUID.fromString("20000000-0000-4000-8000-000000000204");
    private static final UUID MODEL_ID = UUID.fromString("20000000-0000-4000-8000-000000000205");
    private static final UUID PROVIDER_ID = UUID.fromString("20000000-0000-4000-8000-000000000206");
    private static final UUID CHANNEL_ID = UUID.fromString("20000000-0000-4000-8000-000000000207");
    private static final long CHARGED_CREDITS = 30;
    private static final long MODEL_CHARGED_CREDITS = 17;

    private final ChatCompletionUsageEstimator usageEstimator =
            new ChatCompletionUsageEstimator(new TokenEstimator(3, 1.25), JSON);
    private final ChatCompletionUsageAccounting usageAccounting =
            new ChatCompletionUsageAccounting();

    private QuotaService quota;
    private RequestLimitService limits;
    private Recorder recorder;
    private final CountDownLatch settled = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        quota = mock(QuotaService.class);
        when(quota.settle(any(), any(), any())).thenAnswer(invocation -> {
            settled.countDown();
            return Mono.just(new QuotaService.Settlement(
                    RESERVATION_ID, CHARGED_CREDITS, MODEL_CHARGED_CREDITS, 5, "SETTLED",
                    QuotaService.UsageQuality.ACTUAL));
        });
        when(quota.release(any(), any())).thenReturn(Mono.empty());
        recorder = new Recorder();
        limits = mock(RequestLimitService.class);
        when(limits.release(any())).thenReturn(Mono.empty());
        doAnswer(invocation -> {
            recorder.record(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(limits).recordPackageConsumption(any(), anyLong());
    }

    @Test
    void recordsTheCreditsThePackageWasChargedWhenACompletionSettles() {
        ChatCompletionSettlement settlement = completionSettlement(soldRate());

        settle(settlement);

        assertThat(recorder.limit.get()).isEqualTo(soldRate());
        assertThat(recorder.credits.get())
                .as("the package's own charge is what the package's per-minute rate limits")
                .isEqualTo(CHARGED_CREDITS);
    }

    @Test
    void recordsTheCreditsThePackageWasChargedWhenAStreamSettles() {
        ChatStreamSettlement settlement = streamSettlement(soldRate());
        settlement.beginAttempt(streamTarget());
        settlement.upstreamStarted();
        settlement.actualUsage(new ProviderAdapter.TokenUsage(10, 20, 30));

        settlement.finish("SUCCEEDED", null);

        assertThat(awaitRecord()).as("a finished stream settles and must record").isTrue();
        assertThat(recorder.limit.get()).isEqualTo(soldRate());
        assertThat(recorder.credits.get()).isEqualTo(CHARGED_CREDITS);
    }

    @Test
    void recordsTheCreditsOfAStreamTheClientCancelledAfterBillableOutput() {
        ChatStreamSettlement settlement = streamSettlement(soldRate());
        settlement.beginAttempt(streamTarget());
        settlement.upstreamStarted();
        settlement.firstByte();
        settlement.received(chunk("hello"));
        settlement.actualUsage(new ProviderAdapter.TokenUsage(10, 20, 30));

        // ChatCompletionService 的响应 doFinally(CANCEL) 所调用的内容。
        settlement.finish("CANCELLED", null);

        assertThat(awaitRecord()).as("a billable cancellation is charged and must record").isTrue();
        assertThat(recorder.credits.get()).isEqualTo(CHARGED_CREDITS);
    }

    @Test
    void reportsTheRateTheCatalogGaveItEvenWhenThePackageSellsNone() {
        ChatCompletionSettlement settlement =
                completionSettlement(RequestLimitService.PackageTokenLimit.unlimited());
        ChatStreamSettlement stream = streamSettlement(null);
        stream.beginAttempt(streamTarget());
        stream.upstreamStarted();
        stream.actualUsage(new ProviderAdapter.TokenUsage(10, 20, 30));

        settle(settlement);
        stream.finish("SUCCEEDED", null);

        assertThat(awaitSettlement()).as("the stream's settlement must have finished").isTrue();
        // 没有出售每分钟速率的套餐对限流器而言是空操作
        // （RequestLimitService.recordPackageConsumption），结算绝不能凭空造出一个速率。
        assertThat(recorder.limit.get()).isEqualTo(RequestLimitService.PackageTokenLimit.unlimited());
        assertThat(recorder.limit.get().enforced()).isFalse();
        assertThat(recorder.calls).isEqualTo(2);
    }

    private boolean awaitSettlement() {
        try {
            return settled.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void settle(ChatCompletionSettlement settlement) {
        settlement.beginAttempt(completionTarget());
        settlement.upstreamStarted();
        settlement.actualUsage(new ProviderAdapter.TokenUsage(10, 20, 30));
        settlement.succeed(new ProviderAdapter.TokenUsage(10, 20, 30), QuotaService.UsageQuality.ACTUAL)
                .block(Duration.ofSeconds(5));
    }

    private boolean awaitRecord() {
        try {
            return recorder.latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static RequestLimitService.PackageTokenLimit soldRate() {
        return RequestLimitService.PackageTokenLimit.of(PACKAGE_ID, 1000L);
    }

    private ChatCompletionSettlement completionSettlement(
            RequestLimitService.PackageTokenLimit limit) {
        return new ChatCompletionSettlement(
                usageAccounting,
                quota,
                limits,
                new ChatCompletionSettlement.Context(RESERVATION_ID, Instant.now(), false, limit),
                completionTarget(),
                lease(),
                report -> Mono.empty());
    }

    private ChatStreamSettlement streamSettlement(RequestLimitService.PackageTokenLimit limit) {
        return new ChatStreamSettlement(
                usageEstimator,
                usageAccounting,
                quota,
                limits,
                new ChatStreamSettlement.Context(
                        RESERVATION_ID, 12, 32, Instant.now(), "req-credits", "trace-credits",
                        "sk-yzh-credits", true, limit),
                streamTarget(),
                lease(),
                report -> Mono.empty());
    }

    private static ChatCompletionSettlement.Target completionTarget() {
        return new ChatCompletionSettlement.Target(
                MODEL_ID, "text-basic", "provider-model", PROVIDER_ID, CHANNEL_ID);
    }

    private static ChatStreamSettlement.Target streamTarget() {
        return new ChatStreamSettlement.Target(
                MODEL_ID, "text-basic", "provider-model", PROVIDER_ID, CHANNEL_ID);
    }

    private static RequestLimitService.LimitLease lease() {
        return new RequestLimitService.LimitLease(
                UUID.randomUUID(), KEY_ID, USER_ID, "text-basic", CHANNEL_ID,
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

    private static final class Recorder {

        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicReference<RequestLimitService.PackageTokenLimit> limit =
                new AtomicReference<>();
        private final AtomicReference<Long> credits = new AtomicReference<>();
        private int calls;

        void record(RequestLimitService.PackageTokenLimit recordedLimit, long recordedCredits) {
            calls++;
            limit.set(recordedLimit);
            credits.set(recordedCredits);
            latch.countDown();
        }
    }
}
