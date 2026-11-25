package com.yuzhihuihe.apiplatform.gateway.apikey;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/console/gateway/admin/managed-api-keys")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class AdminManagedApiKeyController {

    private final ManagedApiKeyService managedApiKeyService;

    public AdminManagedApiKeyController(ManagedApiKeyService managedApiKeyService) {
        this.managedApiKeyService = managedApiKeyService;
    }

    @PostMapping("/{keyId}/rotate")
    Mono<ManagedApiKeyService.ManagedKeyRotation> rotate(
            @PathVariable UUID keyId,
            @RequestBody(required = false) RotationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID operationId = request == null ? null : request.operationId();
        return managedApiKeyService.rotate(
                UUID.fromString(jwt.getClaimAsString("tenant_id")),
                UUID.fromString(jwt.getSubject()),
                keyId,
                operationId);
    }

    public record RotationRequest(UUID operationId) {}
}
