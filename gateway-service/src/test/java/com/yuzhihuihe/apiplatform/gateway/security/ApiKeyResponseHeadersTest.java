package com.yuzhihuihe.apiplatform.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class ApiKeyResponseHeadersTest {
    @Test
    void anonymousCatalogResponseHasATraceIdWithoutAuthenticating() {
        ApiKeyService service = mock(ApiKeyService.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/skills").build());
        new ApiKeyWebFilter(service).filter(exchange, next -> next.getResponse().setComplete()).block();
        assertThat(exchange.getResponse().getHeaders().getFirst("x-request-id"))
                .matches("req_[0-9a-f]{32}");
        verifyNoInteractions(service);
    }

    @Test
    void existingApplicationTraceIdIsPreserved() {
        ApiKeyService service = mock(ApiKeyService.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/skills").build());
        new ApiKeyWebFilter(service).filter(exchange, next -> {
            next.getResponse().getHeaders().set("x-request-id", "req_owned_by_controller");
            return next.getResponse().setComplete();
        }).block();
        assertThat(exchange.getResponse().getHeaders().getFirst("x-request-id"))
                .isEqualTo("req_owned_by_controller");
    }
}
