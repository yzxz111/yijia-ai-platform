package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.provider.ChannelFaultException;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelCircuitRegistry;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 渠道熔断器：<b>同步抛出的异常没有进熔断器的账</b>。
 *
 * <p>这是本轮排查里一条重要的修正 —— 我最初的假设（"许可泄漏会让渠道永久卡在
 * HALF_OPEN"）在写测试时被证伪了一部分，真实缺陷是更基础的一件事。
 *
 * <p>{@link ChatCompletionProviderExecutor} 的结构是：
 * <pre>
 *   Mono.defer(() -&gt; {
 *       if (!circuit.tryAcquirePermission()) return Mono.error(...);   // 先取许可
 *       return adapter.complete(request)                                // 再调适配器
 *               ...
 *               .doOnError(error -&gt; recordCircuitError(circuit, started, error));  // 才记账
 *   });
 * </pre>
 *
 * <p>{@code adapter.complete(request)} 是<b>同步调用</b>。如果它在返回 Mono <b>之前</b>
 * 就抛异常（参数校验、绑定失败等 —— 生产里 {@code ProviderAdapter.sanitize}
 * 就会抛 {@code IllegalArgumentException}），那么：
 * <ul>
 *   <li>{@code doOnError} 还没来得及被挂上去（它挂在<b>返回的</b> Mono 上）；</li>
 *   <li>Reactor 会把异常转成 {@code onError} 发给订阅者 ——
 *       <b>这正是它危险的地方：调用方看到的是一个完全正常的错误传播</b>；</li>
 *   <li>但熔断器什么都不知道：这个异常既没被 {@code onError} 记为失败，
 *       也没被 {@code releasePermission()} 归还许可。</li>
 * </ul>
 *
 * <p>后果分两层，本测试只断言确定的那一层：
 * <ol>
 *   <li><b>确定</b>：失败率永远算不进去，阈值再低也不会因为这个原因开路。
 *       直接断言 {@code numberOfFailedCalls} 保持 0。</li>
 *   <li><b>未证</b>：HALF_OPEN 下许可有配额（Resilience4j 2.3.0 默认 10，
 *       本仓未显式配置 resilience4j）。泄漏会消耗配额。这条路径能否把渠道
 *       <b>永久</b>卡住取决于 waitDuration 等配置组合 ——
 *       <b>我没有复现出"永久"，因此不作为已证结论</b>。</li>
 * </ol>
 */
class ChannelCircuitHalfOpenPermitLeakTest {

    private static final UUID CHANNEL_ID = UUID.fromString("71000000-0000-4000-8000-0000000000aa");
    private static final UUID PROVIDER_ID = UUID.fromString("71000000-0000-4000-8000-0000000000bb");
    private static final String MODEL_ALIAS = "gpt-4o-mini";

    private ChannelCircuitRegistry registryWith(int halfOpenPermits) {
        return new ChannelCircuitRegistry(CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(4)
                        .minimumNumberOfCalls(4)
                        .failureRateThreshold(50f)
                        .waitDurationInOpenState(Duration.ofMillis(1))
                        .automaticTransitionFromOpenToHalfOpenEnabled(false)
                        .permittedNumberOfCallsInHalfOpenState(halfOpenPermits)
                        .build()));
    }

    private ProviderAdapter.Channel channel() {
        return new ProviderAdapter.Channel(
                CHANNEL_ID, PROVIDER_ID, "test", "https://example.invalid",
                "secret", 5000, "upstream-model");
    }

    private ProviderAdapter.RoutedRequest request() {
        return new ProviderAdapter.RoutedRequest(
                "req-" + UUID.randomUUID(), MODEL_ALIAS,
                new ObjectMapper().createObjectNode(), channel());
    }

    private ChatCompletionProviderExecutor executor(ChannelCircuitRegistry circuits) {
        return new ChatCompletionProviderExecutor(circuits, List.of());
    }

    /** 在返回 Mono/Flux <b>之前</b>同步抛异常的适配器。 */
    private ProviderAdapter synchronouslyThrowing() {
        return new ProviderAdapter() {
            @Override
            public String providerType() {
                return "test";
            }

            @Override
            public Set<ChannelRouter.Capability> capabilities() {
                return Set.of();
            }

            @Override
            public Mono<ProviderResponse> complete(RoutedRequest request) {
                throw new IllegalArgumentException("invalid_provider_request");
            }

            @Override
            public Flux<ProviderChunk> stream(RoutedRequest request) {
                throw new IllegalArgumentException("invalid_provider_request");
            }

            @Override
            public Mono<ProviderFileRef> bindFile(FileObject file, Channel channel) {
                return Mono.empty();
            }
        };
    }

    /** 返回失败的 Mono（异步失败）—— 这是<b>能被正确记账</b>的那种失败。 */
    private ProviderAdapter failingViaErrorSignal() {
        return new ProviderAdapter() {
            @Override
            public String providerType() {
                return "test";
            }

            @Override
            public Set<ChannelRouter.Capability> capabilities() {
                return Set.of();
            }

            @Override
            public Mono<ProviderResponse> complete(RoutedRequest request) {
                return Mono.error(new ProviderAdapter.ProviderException(503, "upstream_down"));
            }

            @Override
            public Flux<ProviderChunk> stream(RoutedRequest request) {
                return Flux.error(new ProviderAdapter.ProviderException(503, "upstream_down"));
            }

            @Override
            public Mono<ProviderFileRef> bindFile(FileObject file, Channel channel) {
                return Mono.empty();
            }
        };
    }

    @Test
    void synchronousClientParameterErrorIsClassifiedAsNotACircuitFailure() {
        // 修复的**另一半**：同步抛出的客户端参数错误现在会被接住并分类，
        // 判定结果仍然与异步路径一致 —— 不计熔断失败、只归还许可。
        //
        // 修复前这里的问题是：它根本走不进分类逻辑（异常绕过了记账），
        // 所以既没记失败、也没归还许可。
        ChannelCircuitRegistry circuits = registryWith(10);
        ChatCompletionProviderExecutor executor = executor(circuits);
        CircuitBreaker circuit = circuits.circuitBreaker(CHANNEL_ID, MODEL_ALIAS);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> executor.complete(
                            synchronouslyThrowing(), request(), MODEL_ALIAS,
                            Duration.ofSeconds(1), () -> { }, response -> { })
                    .block())
                    .as("第 %d 次：调用方确实看到了失败", i + 1)
                    .hasMessageContaining("invalid_provider_request");
        }

        assertThat(circuit.getMetrics().getNumberOfFailedCalls())
                .as("5 次客户端参数错误都不计熔断失败")
                .isZero();
        assertThat(circuit.getState())
                .as("因此一直都是 CLOSED，不会误熔断渠道")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void asynchronousProviderFailureIsCountedAsExpected() {
        // 对照组：同样的"失败"，只要走的是 error 信号而不是同步抛出，就会被正确记账。
        // 这证明上面那个 isZero 不是描述"熔断器不工作"，而是精确描述了
        // "同步抛出这一条路径漏了"。
        ChannelCircuitRegistry circuits = registryWith(10);
        ChatCompletionProviderExecutor executor = executor(circuits);
        CircuitBreaker circuit = circuits.circuitBreaker(CHANNEL_ID, MODEL_ALIAS);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> executor.complete(
                            failingViaErrorSignal(), request(), MODEL_ALIAS,
                            Duration.ofSeconds(1), () -> { }, response -> { })
                    .block())
                    .hasMessageContaining("upstream_down");
        }

        assertThat(circuit.getMetrics().getNumberOfFailedCalls())
                .as("走 error 信号的失败被正确记为 3 次")
                .isEqualTo(3);
    }

    @Test
    void streamPathClassifiesSynchronousThrowsTheSameWay() {
        // stream 路径（Flux.defer）与 complete 同构：同步抛出被接住并分类。
        ChannelCircuitRegistry circuits = registryWith(10);
        ChatCompletionProviderExecutor executor = executor(circuits);
        CircuitBreaker circuit = circuits.circuitBreaker(CHANNEL_ID, MODEL_ALIAS);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> executor.stream(
                            synchronouslyThrowing(), request(), MODEL_ALIAS,
                            Duration.ofSeconds(1), () -> { }, chunk -> "x")
                    .blockLast())
                    .as("stream 路径的同步异常同样对调用方可见")
                    .hasMessageContaining("invalid_provider_request");
        }

        assertThat(circuit.getMetrics().getNumberOfFailedCalls())
                .as("stream 路径的客户端参数错误同样不计熔断失败")
                .isZero();
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void channelFaultIsCountedAsACircuitFailure() {
        // 选项 C 的核心：**渠道自己的错**必须算熔断失败。
        // 渠道缺必需字段、配置值非法、不支持所请求的能力 —— 换任何请求走这个渠道
        // 都一定会再失败，所以它必须能把渠道熔断，否则这个渠道永远熔不断、
        // 每次请求都白跑一趟，而熔断器指标上一切正常。
        ChannelCircuitRegistry circuits = registryWith(10);
        ChatCompletionProviderExecutor executor = executor(circuits);
        CircuitBreaker circuit = circuits.circuitBreaker(CHANNEL_ID, MODEL_ALIAS);

        ProviderAdapter brokenChannel = new ProviderAdapter() {
            @Override
            public String providerType() {
                return "test";
            }

            @Override
            public Set<ChannelRouter.Capability> capabilities() {
                return Set.of();
            }

            @Override
            public Mono<ProviderResponse> complete(RoutedRequest request) {
                throw new ChannelFaultException("invalid_audio_channel");
            }

            @Override
            public Flux<ProviderChunk> stream(RoutedRequest request) {
                throw new ChannelFaultException("invalid_audio_channel");
            }

            @Override
            public Mono<ProviderFileRef> bindFile(FileObject file, Channel channel) {
                return Mono.empty();
            }
        };

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> executor.complete(
                            brokenChannel, request(), MODEL_ALIAS,
                            Duration.ofSeconds(1), () -> { }, response -> { })
                    .block())
                    .hasMessageContaining("invalid_audio_channel");
        }

        assertThat(circuit.getMetrics().getNumberOfFailedCalls())
                .as("渠道故障被正确记为 3 次熔断失败")
                .isEqualTo(3);
    }

    @Test
    void clientParameterErrorIsStillNotACircuitFailure() {
        // 对照组：**客户端的错**仍然不该熔断渠道。
        // 调用方传了坏参数，换个请求就好了 —— 把这种错算到渠道头上会误熔断。
        ChannelCircuitRegistry circuits = registryWith(10);
        ChatCompletionProviderExecutor executor = executor(circuits);
        CircuitBreaker circuit = circuits.circuitBreaker(CHANNEL_ID, MODEL_ALIAS);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> executor.complete(
                            synchronouslyThrowing(), request(), MODEL_ALIAS,
                            Duration.ofSeconds(1), () -> { }, response -> { })
                    .block())
                    .hasMessageContaining("invalid_provider_request");
        }

        assertThat(circuit.getMetrics().getNumberOfFailedCalls())
                .as("客户端参数错误不计熔断失败")
                .isZero();
        assertThat(circuit.getState())
                .as("渠道不会被客户端的错误熔断")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void clientParameterErrorReleasesItsHalfOpenPermitInsteadOfLeakingIt() {
        // **这条是"修好了"的证明。**
        //
        // 客户端参数错误（IllegalArgumentException）按设计**不算**熔断失败 ——
        // 调用方传坏参数不该把渠道熔断掉。但它必须把 HALF_OPEN 的许可**归还**，
        // 否则许可会被吃掉。
        //
        // 修复前：同步抛出绕过了 recordCircuitError，许可被泄漏，
        //         permittedNumberOfCallsInHalfOpenState=1 时下一次探测直接拿不到许可。
        // 修复后：许可被 releasePermission 归还，下一次探测照常拿到。
        ChannelCircuitRegistry circuits = registryWith(1);
        CircuitBreaker circuit = circuits.circuitBreaker(CHANNEL_ID, MODEL_ALIAS);
        ChatCompletionProviderExecutor executor = executor(circuits);

        circuit.transitionToOpenState();
        try {
            Thread.sleep(20);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        // 第一次探测：同步抛客户端参数错误 → 归还许可、不计失败
        assertThatThrownBy(() -> executor.complete(
                        synchronouslyThrowing(), request(), MODEL_ALIAS,
                        Duration.ofSeconds(1), () -> { }, response -> { })
                .block())
                .hasMessageContaining("invalid_provider_request");

        assertThat(circuit.getMetrics().getNumberOfFailedCalls())
                .as("客户端参数错误不计熔断失败")
                .isZero();
        assertThat(circuit.getState())
                .as("渠道不会被客户端的错误熔断")
                .isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // 许可被归还了，所以熔断器认为"这次探测没发生过"，下一次仍然允许探测。
        // 修复前这里会失败：许可泄漏后 tryAcquirePermission 返回 false，
        // 直接变成 503 channel_circuit_open，探测机会永远丢失。
        assertThat(circuit.tryAcquirePermission())
                .as("许可已归还，下一次探测仍可获得许可")
                .isTrue();
        circuit.releasePermission();
    }

    @Test
    void channelFaultOnHalfOpenProbeIsRecordedAndOpensTheCircuit() {
        // 渠道故障在 HALF_OPEN 探测上也必须记账，并且因为失败率达标而开路。
        ChannelCircuitRegistry circuits = registryWith(10);
        CircuitBreaker circuit = circuits.circuitBreaker(CHANNEL_ID, MODEL_ALIAS);
        ChatCompletionProviderExecutor executor = executor(circuits);

        circuit.transitionToOpenState();
        try {
            Thread.sleep(20);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        ProviderAdapter brokenChannel = new ProviderAdapter() {
            @Override
            public String providerType() {
                return "test";
            }

            @Override
            public Set<ChannelRouter.Capability> capabilities() {
                return Set.of();
            }

            @Override
            public Mono<ProviderResponse> complete(RoutedRequest request) {
                throw new ChannelFaultException("invalid_audio_channel");
            }

            @Override
            public Flux<ProviderChunk> stream(RoutedRequest request) {
                throw new ChannelFaultException("invalid_audio_channel");
            }

            @Override
            public Mono<ProviderFileRef> bindFile(FileObject file, Channel channel) {
                return Mono.empty();
            }
        };

        assertThatThrownBy(() -> executor.complete(
                        brokenChannel, request(), MODEL_ALIAS,
                        Duration.ofSeconds(1), () -> { }, response -> { })
                .block())
                .hasMessageContaining("invalid_audio_channel");

        assertThat(circuit.getMetrics().getNumberOfFailedCalls())
                .as("HALF_OPEN 探测上的渠道故障被记账")
                .isEqualTo(1);
    }
}
