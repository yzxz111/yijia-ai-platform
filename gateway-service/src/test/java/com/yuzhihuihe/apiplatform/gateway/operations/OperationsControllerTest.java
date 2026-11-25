package com.yuzhihuihe.apiplatform.gateway.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

class OperationsControllerTest {

    @Test
    void exposesHealthAndManualRecoveryTriggers() {
        UUID actorId = UUID.fromString("71000000-0000-4000-8000-000000000001");
        UUID tenantId = UUID.fromString("61000000-0000-4000-8000-000000000001");
        OperationsService service = mock(OperationsService.class);
        AtomicReference<reactor.util.context.ContextView> operationContext = new AtomicReference<>();
        when(service.health()).thenReturn(Mono.just(new OperationsService.HealthSnapshot(
                "UP", "UP", 2, 3, List.of())));
        when(service.recoverReservations()).thenReturn(Mono.deferContextual(context -> {
            operationContext.set(context);
            return Mono.just(new OperationsService.OperationResult(
                    "recover-reservations", "COMPLETED", 4));
        }));
        when(service.cleanupFiles()).thenReturn(Mono.just(new OperationsService.OperationResult(
                "cleanup-files", "COMPLETED", null)));
        Jwt jwt = Jwt.withTokenValue("admin-token")
                .header("alg", "none")
                .subject(actorId.toString())
                .claim("tenant_id", tenantId.toString())
                .build();
        WebTestClient client = WebTestClient.bindToController(new OperationsController(service))
                .webFilter((exchange, chain) -> chain.filter(exchange).contextWrite(
                        ReactiveSecurityContextHolder.withAuthentication(
                                new JwtAuthenticationToken(jwt))))
                .build();

        client.get().uri("/console/gateway/admin/operations/health")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.expiredReservations").isEqualTo(2)
                .jsonPath("$.cleanupCandidates").isEqualTo(3);
        client.post().uri("/console/gateway/admin/operations/recover-reservations")
                .header("Idempotency-Key", "idem-controller-1")
                .header("X-Request-Id", "req-controller-1")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.affected").isEqualTo(4);
        client.post().uri("/console/gateway/admin/operations/cleanup-files")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.status").isEqualTo("COMPLETED");

        verify(service).health();
        verify(service).recoverReservations();
        verify(service).cleanupFiles();
        assertThat((UUID) operationContext.get().get("operationActorUserId")).isEqualTo(actorId);
        assertThat((UUID) operationContext.get().get("operationTenantId")).isEqualTo(tenantId);
        assertThat((String) operationContext.get().get("operationIdempotencyKey"))
                .isEqualTo("idem-controller-1");
        assertThat((String) operationContext.get().get("operationRequestId"))
                .isEqualTo("req-controller-1");
    }
}
