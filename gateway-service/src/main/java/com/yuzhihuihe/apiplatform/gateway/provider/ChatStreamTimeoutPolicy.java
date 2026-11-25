package com.yuzhihuihe.apiplatform.gateway.provider;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 一次上游流式调用的两个**不同**的等待预算：等第一个 token，与等下一个 token。
 *
 * <p>把它们合成一个值，是「长任务跑到一半断了」的一个真实成因：推理模型在吐第一个字之前
 * 可能安静很久（思考、排队），而一旦开始吐字，两个分块之间的间隔通常很短。用一个 60 秒的
 * 单一窗口同时管这两件事，就只有两个结果——要么思考时间稍长就被判死，要么卡死时也要白等
 * 整整一个窗口。
 *
 * <p><b>与 {@code channel.timeout_ms} 的关系必须写清楚：渠道配置是硬上限，这里的两个值
 * 是窗口，取 {@code min}。</b> 这样运维只调渠道那一处也仍然安全：把渠道超时调到 30 秒，
 * 两个窗口都会跟着收缩到 30 秒，不会出现「渠道说 30 秒、代码还在等 180 秒」的分歧。
 *
 * <p>为什么不直接改 {@code channel.timeout_ms}：它是**按渠道**的一个数，而这里需要的是
 * 同一渠道上的两种不同语义。一个数无法同时表达「首个 token 可以等 3 分钟」和「两个
 * token 之间最多等 60 秒」。
 */
@Component
public final class ChatStreamTimeoutPolicy {

    /**
     * 代码里的兜底值。配置缺失时用它，但**主配置里必须显式声明**（见 application.properties），
     * 否则运维看不到也调不动——那正是 30 秒总预算当年出问题的形态。
     */
    static final Duration DEFAULT_FIRST_TOKEN = Duration.ofSeconds(180);

    static final Duration DEFAULT_IDLE = Duration.ofSeconds(60);

    private final Duration firstToken;
    private final Duration idle;

    public ChatStreamTimeoutPolicy(
            @Value("${gateway.chat.first-token-timeout:180s}") Duration firstToken,
            @Value("${gateway.chat.idle-timeout:60s}") Duration idle) {
        if (invalid(firstToken) || invalid(idle)) {
            throw new IllegalArgumentException("invalid_chat_stream_timeout");
        }
        this.firstToken = firstToken;
        this.idle = idle;
    }

    /** 没有 Spring 上下文时的默认策略（等价于主配置里的默认值）。 */
    public static ChatStreamTimeoutPolicy defaults() {
        return new ChatStreamTimeoutPolicy(DEFAULT_FIRST_TOKEN, DEFAULT_IDLE);
    }

    /**
     * 把两个窗口套到一条流上。
     *
     * <p>用的是 Reactor 的 {@code timeout(firstTimeout, nextTimeoutFactory)}：第一个发布者
     * 只约束**第一个**元素，之后每个元素各自重新计时。这与 {@code timeout(Duration)} 的
     * 「首元素 + 元素间隔都同一个值」不同，正是本类要表达的区别。
     *
     * @param source 上游流
     * @param ceiling 渠道配置的硬上限（{@code channel.timeout_ms}）
     */
    public <T> Flux<T> apply(Flux<T> source, Duration ceiling) {
        if (source == null) {
            throw new IllegalArgumentException("invalid_provider_stream");
        }
        Duration firstWindow = shorter(firstToken, ceiling);
        Duration idleWindow = shorter(idle, ceiling);
        return source.timeout(
                Mono.delay(firstWindow).then(),
                chunk -> Mono.delay(idleWindow).then());
    }

    public Duration firstToken() {
        return firstToken;
    }

    public Duration idle() {
        return idle;
    }

    static Duration shorter(Duration window, Duration ceiling) {
        if (ceiling == null || ceiling.isZero() || ceiling.isNegative()) {
            return window;
        }
        return window.compareTo(ceiling) <= 0 ? window : ceiling;
    }

    private static boolean invalid(Duration value) {
        return value == null || value.isZero() || value.isNegative();
    }
}
