package com.yuzhihuihe.apiplatform.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

class ApiKeyLoggingContextTest {

    @Test
    void apiKeyStoreFailureReturnsDependencyUnavailableWithoutCallingTheApplication() {
        String rawApiKey = "sk-yzh-abcdefgh-this-is-a-valid-length-api-key-for-the-test";
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        when(apiKeyService.authenticate(rawApiKey))
                .thenReturn(Mono.error(new IllegalStateException("database unavailable")));
        ApiKeyWebFilter filter = new ApiKeyWebFilter(apiKeyService);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawApiKey)
                        .build());
        WebFilterChain chain = mock(WebFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"type\":\"server_error\"")
                .contains("\"code\":\"dependency_unavailable\"")
                .doesNotContain("database unavailable");
        verifyNoInteractions(chain);
    }

    @Test
    void authenticatedRequestPropagatesOnlyTraceIdAndApiKeyPrefix() {
        String rawApiKey = "sk-yzh-abcdefgh-this-part-must-never-enter-logging-context";
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        when(apiKeyService.authenticate(rawApiKey)).thenReturn(Mono.just(new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Set.of("qwen-plus"))));
        ApiKeyWebFilter filter = new ApiKeyWebFilter(apiKeyService);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawApiKey)
                        .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                        .build());
        AtomicReference<ContextView> observedContext = new AtomicReference<>();
        WebFilterChain chain = authenticated -> Mono.deferContextual(context -> {
            observedContext.set(context);
            assertThat(authenticated.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                    .isNull();
            return Mono.empty();
        });

        filter.filter(exchange, chain).block();

        assertThat(observedContext.get().hasKey("traceId")).isTrue();
        assertThat(observedContext.get().hasKey("apiKeyPrefix")).isTrue();
        String observedTraceId = observedContext.get().get("traceId");
        String observedApiKeyPrefix = observedContext.get().get("apiKeyPrefix");
        assertThat(observedTraceId).isEqualTo(traceId);
        assertThat(observedApiKeyPrefix).isEqualTo("sk-yzh-abcdefgh");
        assertThat(observedContext.get().stream().map(entry -> String.valueOf(entry.getValue())))
                .doesNotContain(rawApiKey);
    }
}
