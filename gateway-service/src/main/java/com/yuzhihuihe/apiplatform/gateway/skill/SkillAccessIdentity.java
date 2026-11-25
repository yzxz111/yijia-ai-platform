package com.yuzhihuihe.apiplatform.gateway.skill;

import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Stable identity used to scope cloud-skill download sessions.
 *
 * <p>A signed-in account is the primary identity. API keys remain accepted as a compatibility
 * path for existing non-interactive clients, but desktop clients no longer need a package-bound
 * managed API key to access cloud skills.
 */
record SkillAccessIdentity(
        String ownerKey,
        String accountKey,
        UUID tenantId,
        UUID userId,
        UUID apiKeyId) {

    static Mono<SkillAccessIdentity> resolve(ServerWebExchange exchange) {
        ApiKeyService.ApiKeyContext apiKey = exchange.getAttribute(
                ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE);
        if (apiKey != null && apiKey.apiKeyId() != null) {
            String ownerKey = "api-key:" + apiKey.apiKeyId();
            String accountKey = apiKey.tenantId() == null || apiKey.userId() == null
                    ? ownerKey
                    : accountKey(apiKey.tenantId(), apiKey.userId());
            return Mono.just(new SkillAccessIdentity(
                    ownerKey, accountKey, apiKey.tenantId(), apiKey.userId(), apiKey.apiKeyId()));
        }
        return exchange.getPrincipal()
                .map(SkillAccessIdentity::jwt)
                .map(SkillAccessIdentity::fromJwt)
                .switchIfEmpty(Mono.error(authenticationRequired()));
    }

    static ResponseStatusException authenticationRequired() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "authentication_required");
    }

    private static String accountKey(UUID tenantId, UUID userId) {
        return "user:" + tenantId + ":" + userId;
    }

    private static Jwt jwt(java.security.Principal principal) {
        if (principal instanceof JwtAuthenticationToken token) {
            return token.getToken();
        }
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        throw authenticationRequired();
    }

    private static SkillAccessIdentity fromJwt(Jwt jwt) {
        try {
            UUID tenantId = UUID.fromString(jwt.getClaimAsString("tenant_id"));
            UUID userId = UUID.fromString(jwt.getSubject());
            String accountKey = accountKey(tenantId, userId);
            return new SkillAccessIdentity(accountKey, accountKey, tenantId, userId, null);
        } catch (RuntimeException exception) {
            throw authenticationRequired();
        }
    }
}
