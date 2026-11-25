package com.yuzhihuihe.apiplatform.gateway.openai;

import java.time.Duration;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

/**
 * 流式回答期间的 SSE 心跳帧。
 *
 * <p><b>它解决的是一个服务端看不见的问题。</b> 网关在等上游时不会向客户端写任何字节，
 * 而"没有字节"与"连接已死"在客户端和中间代理眼里是同一件事：代理的读空闲超时、
 * 客户端的看门狗、移动网络的 NAT 老化，任何一层先到，用户看到的就是"回答写到一半断了"，
 * 而服务端日志里可能只有一条客户端取消。心跳把"我还活着"变成可观测的事实。
 *
 * <p><b>三个必须写清楚的边界。</b>
 *
 * <ol>
 *   <li>心跳是一条 SSE <b>注释行</b>（{@code : keep-alive}），不是 {@code data:} 帧。按 SSE 规范
 *       客户端必须忽略它，因此它不会污染回答内容，也不会被解析成一次空增量。
 *   <li>它只覆盖**首个 chunk 之后**的静默期。首字之前的等待发生在 {@code FirstChunkGate}
 *       里，响应还没提交，此时发不出任何心跳——那一段只能靠首帧等待预算
 *       （见 {@code ChatStreamTimeoutPolicy}）与客户端超时配合。
 *   <li>{@link Flux#interval} 永不结束，因此**不能**直接 {@code mergeWith}：那样数据流结束后
 *       响应也不会关闭，客户端会一直挂着。这里用 {@code takeUntilOther(shared.ignoreElements())}
 *       在数据流终止时停掉心跳。
 * </ol>
 */
@Component
public final class ChatStreamHeartbeat {

    /** 默认间隔。15 秒小于绝大多数代理与客户端的读空闲超时（常见 30~60 秒）。 */
    static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(15);

    /** 注释帧的内容。客户端会整行忽略，它存在的意义是"有字节流动"。 */
    static final String COMMENT = "keep-alive";

    private final Duration interval;

    public ChatStreamHeartbeat(
            @Value("${gateway.chat.stream-heartbeat-interval:15s}") Duration interval) {
        this.interval = interval == null ? DEFAULT_INTERVAL : interval;
    }

    /** 关闭心跳的方式：把间隔配成 0 或负数。 */
    public boolean enabled() {
        return !interval.isZero() && !interval.isNegative();
    }

    public Duration interval() {
        return interval;
    }

    /**
     * 给一条数据流接上心跳。
     *
     * @param stream 已经过安全扫描、准备写回客户端的数据帧
     * @return 可直接交给 {@code ResponseEntity} 的 SSE 事件流
     */
    public Flux<ServerSentEvent<String>> attach(Flux<String> stream) {
        Objects.requireNonNull(stream, "stream");
        Flux<ServerSentEvent<String>> data =
                stream.map(value -> ServerSentEvent.builder(value).build());
        if (!enabled()) {
            return data;
        }
        Flux<ServerSentEvent<String>> beats = Flux.interval(interval, interval)
                .map(tick -> ServerSentEvent.<String>builder().comment(COMMENT).build());
        // publish 让上游只被订阅一次：takeUntilOther 需要另一个订阅来观察"数据流何时结束"，
        // 不共享就会出现两条上游请求。
        return data.publish(shared -> Flux.merge(
                shared, beats.takeUntilOther(shared.ignoreElements())));
    }
}
