package com.yuzhihuihe.apiplatform.gateway.safety;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.assertThat;

class SafetyStreamScannerTest {

    @Test
    void finishesAnAsynchronousInspectionBeforePropagatingAnUpstreamError() {
        OutputSafetyInspector inspector = mock(OutputSafetyInspector.class);
        when(inspector.inspectText(null, "partial", "request-2"))
                .thenReturn(Mono.delay(Duration.ofMillis(50))
                        .map(ignored -> new OutputSafetyInspector.Decision(false, "ALLOW")));
        ContentSafetyProperties properties = new ContentSafetyProperties();
        properties.getStream().setMaxWait(Duration.ofSeconds(1));
        SafetyStreamScanner scanner = new SafetyStreamScanner(
                new com.fasterxml.jackson.databind.ObjectMapper(), inspector, properties,
                mock(ContentSafetyMetrics.class));
        String chunk = "{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}";
        IllegalStateException failure = new IllegalStateException("upstream interrupted");

        List<reactor.core.publisher.Signal<String>> signals = scanner.scan(
                        Flux.concat(Flux.just(chunk), Flux.error(failure)), null, "request-2")
                .materialize()
                .collectList()
                .block(Duration.ofSeconds(2));

        assertThat(signals).hasSize(2);
        assertThat(signals.getFirst().get()).isEqualTo(chunk);
        assertThat(signals.getLast().getThrowable()).isSameAs(failure);
    }

    @Test
    void waitsForDownstreamDemandWithoutOverflowingTheSafetyBuffer() throws Exception {
        OutputSafetyInspector inspector = mock(OutputSafetyInspector.class);
        when(inspector.inspectText(any(), any(), any()))
                .thenReturn(Mono.just(new OutputSafetyInspector.Decision(false, "ALLOW")));
        ContentSafetyProperties properties = new ContentSafetyProperties();
        properties.getStream().setMaxWait(Duration.ofMillis(5));
        ContentSafetyMetrics metrics = mock(ContentSafetyMetrics.class);
        SafetyStreamScanner scanner = new SafetyStreamScanner(
                new com.fasterxml.jackson.databind.ObjectMapper(), inspector, properties, metrics);
        Flux<String> source = Flux.create(sink -> {
            IntStream.range(0, 128).forEach(index -> sink.next(
                    "{\"choices\":[{\"delta\":{\"content\":\"chunk-" + index + "\"}}]}"));
            sink.complete();
        }, reactor.core.publisher.FluxSink.OverflowStrategy.IGNORE);

        ManualSubscriber subscriber = new ManualSubscriber();
        scanner.scan(source, null, "request-1").subscribe(subscriber);
        Thread.sleep(20);
        subscriber.request(128);

        assertThat(subscriber.completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(subscriber.error).isNull();
        assertThat(subscriber.values).hasSize(128);
    }

    /**
     * 非法块必须 fail-closed，且**错误帧之后不得补 {@code [DONE]}**。
     *
     * <p>这条断言在 2026-10-15 被改过一次，方向值得记下来：它原先断言输出是
     * 两帧、最后一帧为 {@code [DONE]}（{@code hasSize(2)} + {@code getLast()}）。
     * 那与本次发布的流协议契约直接冲突——{@code [DONE]} 是**唯一的**
     * "这次生成完整结束"标记，而内容安全拦截是一次失败终态。错误帧后补
     * {@code [DONE]}，会让客户端把一次被拦截的回答读成成功，正是这次要消灭的
     * "假完成信号"，只不过走的是安全这条通路。
     *
     * <p>契约依据：{@code docs/operations/chat-stream-client-notice.md}
     * （2026-10-14 与桌面端约定的联调基线）以及
     * {@code 中台任务链路中断排查与修复要求.md} 的终态表——
     * "首帧后发生真实失败：输出结构化 error 帧与 request_id，随后结束；不得补 [DONE]"。
     *
     * <p>所以现在断言的是"只有那一帧错误帧"，并且明确断言 {@code [DONE]} **缺席**：
     * 只断言 hasSize(1) 的话，将来若有人把 [DONE] 换成别的终止帧，这条用例仍然会绿，
     * 而契约要挡的恰恰是那个终止标记本身。
     */
    @Test
    void malformedChunkIsNotForwardedAndFailsClosed() {
        OutputSafetyInspector inspector = mock(OutputSafetyInspector.class);
        ContentSafetyMetrics metrics = mock(ContentSafetyMetrics.class);
        SafetyStreamScanner scanner = new SafetyStreamScanner(
                new com.fasterxml.jackson.databind.ObjectMapper(), inspector,
                new ContentSafetyProperties(), metrics);

        List<String> output = scanner.scan(
                        Flux.just("{malformed-json"), null, "request-malformed")
                .collectList()
                .block(Duration.ofSeconds(2));

        assertThat(output).hasSize(1);
        assertThat(output.getFirst()).contains("content_safety_unavailable");
        assertThat(output.getFirst()).isNotEqualTo("[DONE]");
        assertThat(output).doesNotContain("[DONE]");
        org.mockito.Mockito.verify(metrics).streamStopped("content_safety_unavailable");
    }

    private static final class ManualSubscriber implements Subscriber<String> {
        private final List<String> values = new CopyOnWriteArrayList<>();
        private final CountDownLatch completed = new CountDownLatch(1);
        private volatile Subscription subscription;
        private volatile Throwable error;

        @Override
        public void onSubscribe(Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(String value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
            completed.countDown();
        }

        @Override
        public void onComplete() {
            completed.countDown();
        }

        private void request(long count) {
            subscription.request(count);
        }
    }
}
