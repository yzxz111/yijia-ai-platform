package com.yuzhihuihe.apiplatform.gateway.operations;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/console/gateway/admin/operations")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class OperationsController {

    private final OperationsService service;

    public OperationsController(OperationsService service) {
        this.service = service;
    }

    @GetMapping("/health")
    Mono<OperationsService.HealthSnapshot> health() {
        return service.health();
    }

    @PostMapping("/recover-reservations")
    Mono<OperationsService.OperationResult> recoverReservations(ServerWebExchange exchange) {
        return withOperationContext(exchange, service.recoverReservations());
    }

    @PostMapping("/cleanup-files")
    Mono<OperationsService.OperationResult> cleanupFiles(ServerWebExchange exchange) {
        return withOperationContext(exchange, service.cleanupFiles());
    }

    private <T> Mono<T> withOperationContext(ServerWebExchange exchange, Mono<T> operation) {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .ofType(JwtAuthenticationToken.class)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "admin_authentication_required")))
                .flatMap(authentication -> {
                    UUID actorUserId = uuid(authentication.getToken().getSubject());
                    UUID tenantId = uuid(authentication.getToken().getClaimAsString("tenant_id"));
                    String requestId = header(exchange, "X-Request-Id");
                    if (requestId == null) {
                        requestId = "req_" + UUID.randomUUID().toString().replace("-", "");
                    }
                    String idempotencyKey = header(exchange, "Idempotency-Key");
                    if (idempotencyKey == null) {
                        idempotencyKey = requestId;
                    }
                    String finalRequestId = requestId;
                    String finalIdempotencyKey = idempotencyKey;
                    return operation.contextWrite(context -> context
                            .put(OperationsService.TENANT_ID_CONTEXT_KEY, tenantId)
                            .put(OperationsService.ACTOR_USER_ID_CONTEXT_KEY, actorUserId)
                            .put(OperationsService.IDEMPOTENCY_KEY_CONTEXT_KEY, finalIdempotencyKey)
                            .put(OperationsService.REQUEST_ID_CONTEXT_KEY, finalRequestId));
                });
    }

    private String header(ServerWebExchange exchange, String name) {
        String value = exchange.getRequest().getHeaders().getFirst(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > 128) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "operation_header_too_long");
        }
        return normalized;
    }

    private UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_admin_identity");
        }
    }
}
