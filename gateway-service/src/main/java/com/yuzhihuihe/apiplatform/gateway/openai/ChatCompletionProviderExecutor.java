package com.yuzhihuihe.apiplatform.gateway.openai;

import com.yuzhihuihe.apiplatform.gateway.provider.ChannelFaultException;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelCircuitRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class ChatCompletionProviderExecutor {

    private final ChannelCircuitRegistry circuits;
    private final Map<String, ProviderAdapter> adapters;
    private final Scheduler timeoutScheduler;

    ChatCompletionProviderExecutor(
            ChannelCircuitRegistry circuits, List<ProviderAdapter> adapters) {
        this.circuits = circuits;
        this.timeoutScheduler = Schedulers.parallel();
        Map<String, ProviderAdapter> indexed = new LinkedHashMap<>();
        adapters.forEach(adapter -> indexed.put(adapter.providerType().toUpperCase(), adapter));
        this.adapters = Map.copyOf(indexed);
    }

    ProviderAdapter adapterFor(ProviderAdapter.Channel channel) {
        ProviderAdapter adapter = adapters.get(channel.providerType().toUpperCase());
        if (adapter == null) {
            throw unavailable("provider_adapter_unavailable");
        }
        return adapter;
    }

    Mono<ProviderAdapter.ProviderResponse> complete(
            ProviderAdapter adapter,
            ProviderAdapter.RoutedRequest request,
            String modelAlias,
            Duration timeout,
            Runnable attemptObserver,
            Consumer<ProviderAdapter.ProviderResponse> responseObserver) {
        return Mono.defer(
                () -> {
                    CircuitBreaker circuit =
                            circuits.circuitBreaker(request.channel().id(), modelAlias);
                    if (!circuit.tryAcquirePermission()) {
                        return Mono.error(unavailable("channel_circuit_open"));
                    }
                    long started = System.nanoTime();
                    Mono<ProviderAdapter.ProviderResponse> attempt;
                    try {
                        attemptObserver.run();
                        attempt = adapter.complete(request);
                    } catch (RuntimeException synchronousFailure) {
                        // **同步抛出必须在这里被接住。**
                        // adapter.complete 是同步调用：它若在返回 Mono 之前抛异常
                        // （各适配器的 Channel 紧凑构造器、请求记录校验、sanitize 都在这一层），
                        // 那么挂在"返回的 Mono"上的 doOnError 根本没机会挂上去 ——
                        // 熔断器什么都看不到，而 tryAcquirePermission 拿走的那个许可
                        // 既不会被 onSuccess/onError 消费、也不会被归还。
                        // 统一走 recordCircuitError，失败与归还两种判定都保持原策略。
                        recordCircuitError(circuit, started, synchronousFailure);
                        return Mono.error(synchronousFailure);
                    }
                    return attempt.timeout(timeout, Mono.error(attemptTimeout()), timeoutScheduler)
                            .switchIfEmpty(
                                    Mono.error(
                                            new ProviderAdapter.ProviderException(
                                                    502, "empty_provider_response")))
                            .doOnNext(responseObserver)
                            .doOnNext(
                                    response ->
                                            circuit.onSuccess(
                                                    System.nanoTime() - started,
                                                    TimeUnit.NANOSECONDS))
                            .doOnError(error -> recordCircuitError(circuit, started, error))
                            .doOnCancel(circuit::releasePermission);
                });
    }

    Flux<String> stream(
            ProviderAdapter adapter,
            ProviderAdapter.RoutedRequest request,
            String modelAlias,
            Duration timeout,
            Runnable attemptObserver,
            Function<ProviderAdapter.ProviderChunk, String> serializer) {
        return Flux.defer(
                () -> {
                    CircuitBreaker circuit =
                            circuits.circuitBreaker(request.channel().id(), modelAlias);
                    if (!circuit.tryAcquirePermission()) {
                        return Flux.error(unavailable("channel_circuit_open"));
                    }
                    long started = System.nanoTime();
                    Flux<ProviderAdapter.ProviderChunk> attempt;
                    try {
                        attemptObserver.run();
                        attempt = adapter.stream(request);
                    } catch (RuntimeException synchronousFailure) {
                        // 与 complete 路径同因同修：同步抛出的异常必须被接住并记账，
                        // 否则熔断器看不到、且 HALF_OPEN 的许可会泄漏。
                        recordCircuitError(circuit, started, synchronousFailure);
                        return Flux.error(synchronousFailure);
                    }
                    return attempt.timeout(timeout, Flux.error(attemptTimeout()), timeoutScheduler)
                            .<String>handle(
                                    (chunk, sink) -> {
                                        String serialized = serializer.apply(chunk);
                                        if (serialized != null) {
                                            sink.next(serialized);
                                        }
                                    })
                            .switchIfEmpty(
                                    Mono.error(
                                            new ProviderAdapter.ProviderException(
                                                    502, "empty_provider_stream")))
                            .doOnComplete(
                                    () ->
                                            circuit.onSuccess(
                                                    System.nanoTime() - started,
                                                    TimeUnit.NANOSECONDS))
                            .doOnError(error -> recordCircuitError(circuit, started, error))
                            .doOnCancel(circuit::releasePermission);
                });
    }

    private void recordCircuitError(CircuitBreaker circuit, long started, Throwable error) {
        if (isCircuitFailure(error)) {
            circuit.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, error);
        } else {
            circuit.releasePermission();
        }
    }

    private boolean isCircuitFailure(Throwable error) {
        // **选项 C：按异常类型分类，而不是按"像不像 IllegalArgumentException"。**
        //
        // 先判 ChannelFaultException：它表示"渠道自己坏了"（缺必需字段、配置值非法、
        // 不支持所请求的能力）。重试同一个请求、甚至换成任何合法请求，只要还走这个
        // 渠道就一定会再失败 —— 所以它**必须算熔断失败**，否则这种渠道永远熔不断、
        // 每次请求都白跑一趟。
        //
        // 注意顺序：ChannelFaultException 继承 IllegalArgumentException，
        // 所以这一支必须放在后面那些更宽的判断之前。
        if (error instanceof ChannelFaultException) {
            return true;
        }
        if (error instanceof ProviderAdapter.ProviderException provider) {
            return provider.status() >= 500;
        }
        if (error instanceof ResponseStatusException response) {
            return response.getStatusCode().is5xxServerError();
        }
        // 其余 IllegalArgumentException 仍然按"客户端的错"处理：不计失败、归还许可。
        // 这是有意的 —— 调用方传了坏参数不该把渠道熔断掉。
        // 代价是：任何**没有**改用 ChannelFaultException 的渠道配置错误，
        // 依然会被算成客户端错误。各适配器正逐步把 `invalid_*_channel` 一类
        // 换成 ChannelFaultException，这里保留旧行为以免一次性改动面过大。
        return !(error instanceof IllegalArgumentException);
    }

    private ProviderAdapter.ProviderException attemptTimeout() {
        return new ProviderAdapter.ProviderException(504, "provider_attempt_timeout");
    }

    private ResponseStatusException unavailable(String reason) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }
}
