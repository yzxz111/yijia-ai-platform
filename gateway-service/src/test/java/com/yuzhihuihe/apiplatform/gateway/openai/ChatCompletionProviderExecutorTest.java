package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelCircuitRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ChatCompletionProviderExecutorTest {

    private static final UUID CHANNEL_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000071");
    private static final String MODEL_ALIAS = "text-basic";

    private final ObjectMapper json = new ObjectMapper();

    private ChannelCircuitRegistry circuits;
    private ProviderAdapter adapter;
    private ProviderAdapter.Channel channel;
    private ProviderAdapter.RoutedRequest request;

    @BeforeEach
    void setUp() {
        circuits = new ChannelCircuitRegistry(CircuitBreakerRegistry.ofDefaults());
        adapter = mock(ProviderAdapter.class);
        when(adapter.providerType()).thenReturn("mock");
        channel = new ProviderAdapter.Channel(
                CHANNEL_ID, UUID.randomUUID(), "MoCk", "https://provider.example", "secret",
                1_000, "upstream-model");
        request = new ProviderAdapter.RoutedRequest(
                "req_provider_executor", MODEL_ALIAS, json.createObjectNode(), channel);
    }

    @Test
    void resolvesAdapterByProviderTypeIgnoringCase() {
        ChatCompletionProviderExecutor executor = executor(List.of(adapter));

        assertThat(executor.adapterFor(channel)).isSameAs(adapter);
    }

    @Test
    void rejectsMissingAdapterWithTheStableServiceUnavailableReason() {
        ChatCompletionProviderExecutor executor = executor(List.of());

        assertThatThrownBy(() -> executor.adapterFor(channel))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("provider_adapter_unavailable");
                });
    }

    @Test
    void observesCompletionBeforeRecordingCircuitSuccess() {
        ProviderAdapter.ProviderResponse response =
                new ProviderAdapter.ProviderResponse(json.createObjectNode(), null);
        List<String> events = new ArrayList<>();
        when(adapter.complete(any())).thenAnswer(invocation -> {
            events.add("adapter");
            return Mono.just(response);
        });
        ChatCompletionProviderExecutor executor = executor(List.of(adapter));
        CircuitBreaker circuit = circuits.circuitBreaker(CHANNEL_ID, MODEL_ALIAS);

        ProviderAdapter.ProviderResponse actual = executor.complete(
                        adapter, request, MODEL_ALIAS, Duration.ofSeconds(1),
                        () -> events.add("attempt"), value -> {
                            events.add("response");
                            assertThat(circuit.getMetrics().getNumberOfSuccessfulCalls()).isZero();
                        })
                .block(Duration.ofSeconds(2));

        assertThat(actual).isSameAs(response);
        assertThat(events).containsExactly("attempt", "adapter", "response");
        assertThat(circuit.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    void rejectsForcedOpenCircuitBeforeAttemptOrProviderInvocation() {
        AtomicBoolean attempted = new AtomicBoolean();
        circuits.circuitBreaker(CHANNEL_ID, MODEL_ALIAS).transitionToForcedOpenState();
        ChatCompletionProviderExecutor executor = executor(List.of(adapter));

        assertThatThrownBy(() -> executor.complete(
                        adapter, request, MODEL_ALIAS, Duration.ofSeconds(1),
                        () -> attempted.set(true), ignored -> {})
                .block(Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(ResponseStatusException.class, response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(response.getReason()).isEqualTo("channel_circuit_open");
                });

        assertThat(attempted).isFalse();
        verify(adapter, never()).complete(any());
    }

    @Test
    void providerClientErrorReleasesCircuitPermission() {
        when(adapter.complete(any())).thenReturn(Mono.error(
                new ProviderAdapter.ProviderException(400, "invalid_request")));
        ChatCompletionProviderExecutor executor = executor(List.of(adapter));
        CircuitBreaker circuit = circuits.circuitBreaker(CHANNEL_ID, MODEL_ALIAS);

        assertThatThrownBy(() -> executor.complete(
                        adapter, request, MODEL_ALIAS, Duration.ofSeconds(1), () -> {}, ignored -> {})
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(ProviderAdapter.ProviderException.class);

        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void emptyCompletionIsAStableProviderFailure() {
        when(adapter.complete(any())).thenReturn(Mono.empty());
        ChatCompletionProviderExecutor executor = executor(List.of(adapter));
        CircuitBreaker circuit = circuits.circuitBreaker(CHANNEL_ID, MODEL_ALIAS);

        assertThatThrownBy(() -> executor.complete(
                        adapter, request, MODEL_ALIAS, Duration.ofSeconds(1), () -> {}, ignored -> {})
                .block(Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, provider -> {
                    assertThat(provider.status()).isEqualTo(502);
                    assertThat(provider.getMessage()).isEqualTo("empty_provider_response");
                });

        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void streamCancellationReleasesCircuitPermission() {
        when(adapter.stream(any())).thenReturn(Flux.never());
        ChatCompletionProviderExecutor executor = executor(List.of(adapter));
        CircuitBreaker circuit = circuits.circuitBreaker(CHANNEL_ID, MODEL_ALIAS);

        Disposable subscription = executor.stream(
                        adapter, request, MODEL_ALIAS, Duration.ofSeconds(1), () -> {}, ignored -> "unused")
                .subscribe();
        subscription.dispose();

        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    private ChatCompletionProviderExecutor executor(List<ProviderAdapter> adapters) {
        return new ChatCompletionProviderExecutor(circuits, adapters);
    }
}
