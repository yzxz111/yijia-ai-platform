package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.ServerSentEventHttpMessageWriter;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * SSE 心跳：静默期要发、数据流结束要停、能关掉、且线上真的是一行注释。
 *
 * <p>最后一条（{@link #springSerialisesTheHeartbeatAsAnSseCommentLine()}）不是形式主义：
 * 心跳若被写成 {@code data:} 帧，客户端会把它当成一次空增量甚至一次错误的分块；
 * 若序列化器对"只有注释、没有 data"的事件什么都不写，那这套机制就是完全不生效的，
 * 而单测里看 Flux 层却一切正常。
 */
class ChatStreamHeartbeatTest {

    private final ChatStreamHeartbeat heartbeat = new ChatStreamHeartbeat(Duration.ofSeconds(15));

    @Test
    void emitsKeepAliveWhileTheStreamIsSilent() {
        StepVerifier.withVirtualTime(() -> heartbeat.attach(
                        Flux.just("a").concatWith(Flux.never())))
                .expectNextMatches(event -> "a".equals(event.data()))
                .thenAwait(Duration.ofSeconds(16))
                .expectNextMatches(event -> ChatStreamHeartbeat.COMMENT.equals(event.comment())
                        && event.data() == null)
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void doesNotEmitAnythingBeforeTheInterval() {
        StepVerifier.withVirtualTime(() -> heartbeat.attach(
                        Flux.just("a").concatWith(Flux.never())))
                .expectNextMatches(event -> "a".equals(event.data()))
                .thenAwait(Duration.ofSeconds(10))
                .expectNoEvent(Duration.ofSeconds(4))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void theHeartbeatStopsWhenTheDataStreamCompletes() {
        // 关键回归点：Flux.interval 永不结束，直接 mergeWith 会让响应永远不关闭。
        StepVerifier.withVirtualTime(() -> heartbeat.attach(Flux.just("a", "b")))
                .expectNextMatches(event -> "a".equals(event.data()))
                .expectNextMatches(event -> "b".equals(event.data()))
                .thenAwait(Duration.ofSeconds(120))
                .verifyComplete();
    }

    @Test
    void propagatesErrorsInsteadOfSwallowingThem() {
        StepVerifier.withVirtualTime(() -> heartbeat.attach(
                        Flux.concat(Flux.just("a"), Flux.error(new IllegalStateException("boom")))))
                .expectNextMatches(event -> "a".equals(event.data()))
                .expectError(IllegalStateException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void canBeDisabledWithAZeroInterval() {
        ChatStreamHeartbeat off = new ChatStreamHeartbeat(Duration.ZERO);
        assertThat(off.enabled()).isFalse();
        StepVerifier.withVirtualTime(() -> off.attach(Flux.just("a").concatWith(Flux.never())))
                .expectNextMatches(event -> "a".equals(event.data()))
                .thenAwait(Duration.ofSeconds(60))
                .expectNoEvent(Duration.ofSeconds(1))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void cancellationPropagatesThroughTheHeartbeatToTheUpstream() {
        // 客户端断开时必须把取消传到上游：ChatCompletionService 依赖这次取消去
        // 释放/结算额度，如果心跳包装把取消吞掉，预留会一直挂到 TTL 才被回收。
        AtomicBoolean cancelled = new AtomicBoolean();
        Flux<String> source = Flux.<String>never().doOnCancel(() -> cancelled.set(true));

        StepVerifier.create(heartbeat.attach(source))
                .thenAwait(Duration.ofMillis(50))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(cancelled).as("取消必须穿透到上游").isTrue();
    }

    @Test
    void springSerialisesTheHeartbeatAsAnSseCommentLine() {        ServerSentEventHttpMessageWriter writer = new ServerSentEventHttpMessageWriter();
        MockServerHttpResponse response = new MockServerHttpResponse();
        response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);

        writer.write(
                        Mono.just(ServerSentEvent.<String>builder()
                                .comment(ChatStreamHeartbeat.COMMENT)
                                .build()),
                        ResolvableType.forClassWithGenerics(ServerSentEvent.class, String.class),
                        MediaType.TEXT_EVENT_STREAM,
                        response,
                        Map.of())
                .block(Duration.ofSeconds(5));

        assertThat(response.getBodyAsString().block(Duration.ofSeconds(5)))
                .as("心跳必须落在线上，且必须是注释行而不是 data 帧")
                .isEqualTo(":keep-alive\n\n");
    }

    @Test
    void dataFramesKeepTheirDataField() {
        ServerSentEventHttpMessageWriter writer = new ServerSentEventHttpMessageWriter();
        MockServerHttpResponse response = new MockServerHttpResponse();
        response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);

        writer.write(
                        Mono.just(ServerSentEvent.builder("hello").build()),
                        ResolvableType.forClassWithGenerics(ServerSentEvent.class, String.class),
                        MediaType.TEXT_EVENT_STREAM,
                        response,
                        Map.of())
                .block(Duration.ofSeconds(5));

        assertThat(response.getBodyAsString().block(Duration.ofSeconds(5)))
                .isEqualTo("data:hello\n\n");
    }
}
