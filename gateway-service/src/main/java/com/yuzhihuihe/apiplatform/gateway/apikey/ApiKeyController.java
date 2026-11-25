package com.yuzhihuihe.apiplatform.gateway.apikey;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/console/gateway/api-keys")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @GetMapping
    Flux<ApiKeyService.ApiKeyView> list(@AuthenticationPrincipal Jwt jwt) {
        return apiKeyService.list(tenantId(jwt), userId(jwt));
    }

    @GetMapping("/eligible-models")
    Mono<List<String>> eligibleModels(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String usageDimension) {
        return apiKeyService.eligibleModels(tenantId(jwt), userId(jwt), usageDimension).collectList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Mono<ApiKeyService.CreatedApiKey> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody ApiKeyService.CreateApiKeyRequest request) {
        return apiKeyService.create(tenantId(jwt), userId(jwt), request);
    }

    @PatchMapping("/{id}/status")
    Mono<ApiKeyService.ApiKeyView> changeStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody StatusRequest request) {
        return apiKeyService.changeStatus(tenantId(jwt), userId(jwt), id, request.status());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return apiKeyService.revoke(tenantId(jwt), userId(jwt), id);
    }

    @PostMapping("/{id}/reset")
    Mono<ApiKeyService.ResetApiKey> reset(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestHeader(value = "X-SMS-Proof", required = false) String proof) {
        return apiKeyService.reset(tenantId(jwt), userId(jwt), id, proof);
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }

    public record StatusRequest(String status) { }
}
