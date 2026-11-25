package com.yuzhihuihe.apiplatform.gateway.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

class AdminManagedApiKeyControllerTest {

    @Test
    void forwardsTenantActorTargetAndOperationToRotationService() {
        ManagedApiKeyService service = mock(ManagedApiKeyService.class);
        AdminManagedApiKeyController controller = new AdminManagedApiKeyController(service);
        UUID tenantId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("admin-token")
                .header("alg", "none")
                .subject(actorUserId.toString())
                .claim("tenant_id", tenantId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        ManagedApiKeyService.ManagedKeyRotation expected =
                new ManagedApiKeyService.ManagedKeyRotation(
                        operationId, keyId, UUID.randomUUID(), "sk-yzh-prefix",
                        "sk-yzh-secret", true, "COMPLETED");
        when(service.rotate(tenantId, actorUserId, keyId, operationId))
                .thenReturn(Mono.just(expected));

        ManagedApiKeyService.ManagedKeyRotation result = controller.rotate(
                keyId, new AdminManagedApiKeyController.RotationRequest(operationId), jwt).block();

        assertThat(result).isEqualTo(expected);
        verify(service).rotate(tenantId, actorUserId, keyId, operationId);
    }
}
