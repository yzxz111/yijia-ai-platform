package com.yuzhihuihe.apiplatform.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 两个等待窗口的语义，以及它们与渠道硬上限的关系。
 *
 * <p>这些用例存在的理由是：把「等第一个 token」和「等下一个 token」分开，是修
 * 「长任务跑到一半断了」的核心动作；而 Reactor 的 {@code timeout} 一旦有人图省事改回
 * 单值重载，行为会静默退化成"总时长受限"，线上要过几天才会被发现。
 */
class ChatStreamTimeoutPolicyTest {

    private final ChatStreamTimeoutPolicy policy = ChatStreamTimeoutPolicy.defaults();

    @Test
    void defaultsAreTheDocumentedWindows() {
        assertThat(policy.firstToken()).isEqualTo(Duration.ofSeconds(180));
        assertThat(policy.idle()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void windowsAreClampedByTheChannelCeiling() {
        // 渠道比窗口小 → 取渠道（硬上限）；渠道比窗口大 → 取窗口。
        assertThat(ChatStreamTimeoutPolicy.shorter(Duration.ofSeconds(180), Duration.ofSeconds(30)))
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(ChatStreamTimeoutPolicy.shorter(Duration.ofSeconds(60), Duration.ofSeconds(180)))
                .isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void rejectsInvalidWindows() {
        assertThatThrownBy(() -> new ChatStreamTimeoutPolicy(null, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatStreamTimeoutPolicy(Duration.ZERO, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new ChatStreamTimeoutPolicy(Duration.ofSeconds(1), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFirstTokenMayTakeLongerThanTheIdleWindow() {
        // 第一个元素 120 秒后才出现：远超 idle(60s)，但仍在 first-token(180s) 之内 → 应当放行。
        // 这条钉住的正是「推理模型思考久一点就被判死」的回归。
        StepVerifier.withVirtualTime(() -> policy.apply(
                        Mono.delay(Duration.ofSeconds(120)).thenMany(Flux.just("first")),
                        Duration.ofSeconds(180)))
                .thenAwait(Duration.ofSeconds(121))
                .expectNext("first")
                .verifyComplete();
    }

    @Test
    void silenceBeforeTheFirstTokenFailsAtTheFirstTokenBudget() {
        StepVerifier.withVirtualTime(() -> policy.apply(Flux.never(), Duration.ofSeconds(180)))
                .thenAwait(Duration.ofSeconds(181))
                .expectError(TimeoutException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void aLongStreamIsNotCutByTheFirstTokenBudget() {
        // 每 30 秒一个元素、共 12 个（总 360 秒）：总时长远超 180 秒的首帧预算，
        // 但元素间隔 30 秒 < idle 60 秒，因此**不应**被任何一层切断。
        // 如果有人把实现改回 timeout(Duration)，这条会立刻变红。
        StepVerifier.withVirtualTime(() -> policy.apply(
                        Flux.interval(Duration.ofSeconds(30)).take(12),
                        Duration.ofSeconds(180)))
                .thenAwait(Duration.ofSeconds(361))
                .expectNextCount(12)
                .verifyComplete();
    }

    @Test
    void aGapLongerThanTheIdleWindowFailsEvenMidStream() {
        // 第一个元素 90 秒到达（在首帧预算内），之后每 90 秒一个：间隔 90 > idle 60，
        // 第二个元素永远等不到 → 在 90+60=150 秒处失败。
        StepVerifier.withVirtualTime(() -> policy.apply(
                        Flux.interval(Duration.ofSeconds(90)).take(3),
                        Duration.ofSeconds(600)))
                .thenAwait(Duration.ofSeconds(91))
                .expectNext(0L)
                .thenAwait(Duration.ofSeconds(60))
                .expectError(TimeoutException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void theChannelCeilingShrinksBothWindows() {
        // 渠道配 30 秒时，两个窗口都被压到 30 秒：首帧 40 秒才来就必须失败。
        StepVerifier.withVirtualTime(() -> policy.apply(
                        Mono.delay(Duration.ofSeconds(40)).thenMany(Flux.just("late")),
                        Duration.ofSeconds(30)))
                .thenAwait(Duration.ofSeconds(41))
                .expectError(TimeoutException.class)
                .verify(Duration.ofSeconds(5));
    }
}
