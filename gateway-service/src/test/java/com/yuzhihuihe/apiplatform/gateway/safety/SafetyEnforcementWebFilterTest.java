package com.yuzhihuihe.apiplatform.gateway.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class SafetyEnforcementWebFilterTest {

    private static final String REQUEST_ID = "req_safety_contract";

    @Test
    void enforcementQueryFailureIsNotConvertedToAnUnrestrictedAccount() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AccountEnforcementRepository repository = mock(AccountEnforcementRepository.class);
        when(repository.current(tenantId, userId))
                .thenReturn(Mono.error(new IllegalStateException("database unavailable")));

        AccountEnforcementService service = new AccountEnforcementService(repository);

        assertThatThrownBy(() -> service.current(tenantId, userId).block())
                .hasMessage("database unavailable");
    }

    @Test
    void enforcementDependencyFailureReturnsServiceUnavailableWithoutCallingDownstream() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AccountEnforcementService enforcement = mock(AccountEnforcementService.class);
        when(enforcement.current(tenantId, userId))
                .thenReturn(Mono.error(new IllegalStateException("database unavailable")));
        SafetyEnforcementWebFilter filter = new SafetyEnforcementWebFilter(enforcement);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions").build());
        exchange.getAttributes().put(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE,
                new ApiKeyService.ApiKeyContext(
                        UUID.randomUUID(), tenantId, userId, UUID.randomUUID(), Set.of("qwen-plus")));
        exchange.getAttributes().put(ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID);
        WebFilterChain chain = mock(WebFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst("x-request-id")).isEqualTo(REQUEST_ID);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"content_safety_unavailable\"")
                .contains("\"request_id\":\"" + REQUEST_ID + "\"")
                .doesNotContain("database unavailable");
        verifyNoInteractions(chain);
    }

    @Test
    void restrictedAccountReturnsForbiddenWithoutCallingDownstream() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AccountEnforcementService enforcement = mock(AccountEnforcementService.class);
        when(enforcement.current(tenantId, userId)).thenReturn(Mono.just(
                new AccountEnforcementService.Enforcement("FROZEN", "policy.test", "CASE-1")));
        SafetyEnforcementWebFilter filter = new SafetyEnforcementWebFilter(enforcement);
        MockServerWebExchange exchange = exchange(tenantId, userId);
        WebFilterChain chain = mock(WebFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getFirst("x-request-id")).isEqualTo(REQUEST_ID);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"account_safety_restricted\"")
                .contains("\"request_id\":\"" + REQUEST_ID + "\"");
        verifyNoInteractions(chain);
    }

    @Test
    void enforcementCacheMissReturnsServiceUnavailableWithoutCallingDownstream() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AccountEnforcementService enforcement = mock(AccountEnforcementService.class);
        when(enforcement.current(tenantId, userId)).thenReturn(Mono.empty());
        SafetyEnforcementWebFilter filter = new SafetyEnforcementWebFilter(enforcement);
        MockServerWebExchange exchange = exchange(tenantId, userId);
        WebFilterChain chain = mock(WebFilterChain.class);
        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"content_safety_unavailable\"");
        verifyNoInteractions(chain);
    }

    @Test
    void explicitlyClearedAccountContinuesToDownstream() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AccountEnforcementService enforcement = mock(AccountEnforcementService.class);
        when(enforcement.current(tenantId, userId)).thenReturn(Mono.just(
                new AccountEnforcementService.Enforcement("CLEARED", "manual_clear", null)));
        SafetyEnforcementWebFilter filter = new SafetyEnforcementWebFilter(enforcement);
        MockServerWebExchange exchange = exchange(tenantId, userId);
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    private MockServerWebExchange exchange(UUID tenantId, UUID userId) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions").build());
        exchange.getAttributes().put(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE,
                new ApiKeyService.ApiKeyContext(
                        UUID.randomUUID(), tenantId, userId, UUID.randomUUID(), Set.of("qwen-plus")));
        exchange.getAttributes().put(ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID);
        return exchange;
    }
}
