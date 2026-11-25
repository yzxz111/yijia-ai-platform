package com.yuzhihuihe.apiplatform.gateway.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

final class ConsoleTokenVersionWebFilter implements WebFilter {

    private static final String CONSOLE_PATH = "/console/gateway/";
    private static final String SKILLS_PATH = "/v1/skills";
    private static final String BEARER_PREFIX = "Bearer ";

    private final ConsoleTokenValidator validator;

    ConsoleTokenVersionWebFilter(ConsoleTokenValidator validator) {
        this.validator = validator;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean consoleRequest = path.startsWith(CONSOLE_PATH);
        boolean skillRequest = SKILLS_PATH.equals(path) || path.startsWith(SKILLS_PATH + "/");
        boolean nativeClawhubRequest = "/v1/native-clawhub/skills".equals(path)
                || path.startsWith("/v1/native-clawhub/skills/");
        if (!consoleRequest && !skillRequest && !nativeClawhubRequest) {
            return chain.filter(exchange);
        }
        if (ApiKeyWebFilter.isAnonymousSkillCatalogRequest(exchange)) {
            return chain.filter(exchange);
        }
        // Legacy API-key skill requests were already authenticated by ApiKeyWebFilter and have
        // their Authorization header removed. Platform token validation applies only to JWTs.
        if ((skillRequest || nativeClawhubRequest)
                && exchange.getAttribute(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE) != null) {
            return chain.filter(exchange);
        }
        String token = bearerToken(exchange);
        if (token == null) {
            return unauthorized(exchange);
        }
        return validator.validate(token)
                .onErrorReturn(false)
                .defaultIfEmpty(false)
                .flatMap(valid -> valid ? chain.filter(exchange) : unauthorized(exchange));
    }

    private String bearerToken(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }
}
