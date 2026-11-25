package com.yuzhihuihe.apiplatform.gateway.safety;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public final class SafetyEnforcementWebFilter implements WebFilter, Ordered {
    private final AccountEnforcementService enforcement;

    public SafetyEnforcementWebFilter(AccountEnforcementService enforcement) { this.enforcement = enforcement; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/v1/")) return chain.filter(exchange);
        ApiKeyService.ApiKeyContext key = exchange.getAttribute(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE);
        if (key == null) return chain.filter(exchange);
        return enforcement.current(key.tenantId(), key.userId())
                .switchIfEmpty(Mono.error(new EnforcementLookupException(
                        new IllegalStateException("enforcement_cache_miss"))))
                .onErrorMap(EnforcementLookupException::new)
                .flatMap(state -> switch (state.state()) {
                    case "FROZEN", "BANNED" -> blocked(exchange).thenReturn(true);
                    case "CLEARED" -> chain.filter(exchange).thenReturn(false);
                    default -> Mono.error(new EnforcementLookupException(
                            new IllegalStateException("invalid_enforcement_state")));
                })
                .then()
                .onErrorResume(EnforcementLookupException.class,
                        error -> unavailable(exchange));
    }

    private Mono<Void> blocked(ServerWebExchange exchange) {
        String requestId = requestId(exchange);
        byte[] body = ("{\"error\":{\"message\":\"Account access is restricted.\","
                + "\"type\":\"permission_error\",\"code\":\"account_safety_restricted\"},"
                + "\"request_id\":\"" + requestId + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set("x-request-id", requestId);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private Mono<Void> unavailable(ServerWebExchange exchange) {
        String requestId = requestId(exchange);
        byte[] body = ("{\"error\":{\"message\":\"Account safety status is temporarily unavailable.\","
                + "\"type\":\"server_error\",\"code\":\"content_safety_unavailable\"},"
                + "\"request_id\":\"" + requestId + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set("x-request-id", requestId);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private String requestId(ServerWebExchange exchange) {
        return exchange.getAttributeOrDefault(
                ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE,
                "req_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 15; }

    private static final class EnforcementLookupException extends RuntimeException {
        private EnforcementLookupException(Throwable cause) {
            super(cause);
        }
    }
}
