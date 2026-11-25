package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuzhihuihe.apiplatform.gateway.routing.PlatformPackageModelClient.ModelReferenceView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ModelView;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class GatewayAdminLifecycleServiceTest {

    private GatewayAdminRepository repository;
    private PlatformPackageModelClient packageModels;
    private GatewayAdminService service;

    @BeforeEach
    void setUp() {
        repository = mock(GatewayAdminRepository.class);
        packageModels = mock(PlatformPackageModelClient.class);
        TransactionalOperator transactions = mock(TransactionalOperator.class);
        doAnswer(invocation -> invocation.getArgument(0))
                .when(transactions).transactional(any(Mono.class));
        service = new GatewayAdminService(
                repository, transactions, mock(CredentialCipher.class), packageModels);
    }

    @Test
    void changesModelStatusAndAuditsTheLifecycleAction() {
        UUID id = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        ModelView before = model(id, true);
        ModelView after = model(id, false);
        when(repository.model(id)).thenReturn(Mono.just(before), Mono.just(after));
        when(repository.updateModelEnabled(id, false)).thenReturn(Mono.empty());
        when(repository.audit(actor, "GATEWAY_MODEL_DISABLED", "GATEWAY_MODEL",
                id, before, after, null)).thenReturn(Mono.empty());

        assertThat(service.setModelEnabled(id, false, actor).block()).isEqualTo(after);
        verify(repository).updateModelEnabled(id, false);
        verify(repository).audit(actor, "GATEWAY_MODEL_DISABLED", "GATEWAY_MODEL",
                id, before, after, null);
    }

    @Test
    void refusesDeletionWhenAnyPackageVersionReferencesTheModel() {
        UUID id = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        ModelView model = model(id, false);
        when(repository.model(id)).thenReturn(Mono.just(model));
        when(repository.countCompositeModelReferences(id)).thenReturn(Mono.just(0L));
        when(packageModels.references(model.code(), "admin-token"))
                .thenReturn(Mono.just(new ModelReferenceView(model.code(), 2, List.of())));

        assertThatThrownBy(() -> service.deleteModel(id, actor, "admin-token").block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getReason()).isEqualTo("model_referenced_by_package");
                });
        verify(repository, never()).deleteModel(id);
    }

    @Test
    void deletesAndAuditsAnUnreferencedModel() {
        UUID id = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        ModelView model = model(id, false);
        when(repository.model(id)).thenReturn(Mono.just(model));
        when(repository.countCompositeModelReferences(id)).thenReturn(Mono.just(0L));
        when(packageModels.references(model.code(), "admin-token"))
                .thenReturn(Mono.just(new ModelReferenceView(model.code(), 0, List.of())));
        when(repository.deleteModel(id)).thenReturn(Mono.empty());
        when(repository.audit(
                org.mockito.ArgumentMatchers.eq(actor),
                org.mockito.ArgumentMatchers.eq("GATEWAY_MODEL_DELETED"),
                org.mockito.ArgumentMatchers.eq("GATEWAY_MODEL"),
                org.mockito.ArgumentMatchers.eq(id),
                org.mockito.ArgumentMatchers.eq(model),
                any(),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(Mono.empty());

        service.deleteModel(id, actor, "admin-token").block();

        verify(repository).deleteModel(id);
        verify(repository).audit(
                org.mockito.ArgumentMatchers.eq(actor),
                org.mockito.ArgumentMatchers.eq("GATEWAY_MODEL_DELETED"),
                org.mockito.ArgumentMatchers.eq("GATEWAY_MODEL"),
                org.mockito.ArgumentMatchers.eq(id),
                org.mockito.ArgumentMatchers.eq(model),
                any(),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void refusesDeletionWhenACompositeModelUsesTheModelForRouting() {
        UUID id = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        ModelView model = model(id, false);
        when(repository.model(id)).thenReturn(Mono.just(model));
        when(repository.countCompositeModelReferences(id)).thenReturn(Mono.just(1L));

        assertThatThrownBy(() -> service.deleteModel(id, actor, "admin-token").block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getReason()).isEqualTo("model_referenced_by_routing");
                });
        verify(packageModels, never()).references(model.code(), "admin-token");
        verify(repository, never()).deleteModel(id);
    }

    private ModelView model(UUID id, boolean enabled) {
        return new ModelView(
                id,
                "lifecycle-model",
                "Lifecycle Model",
                Set.of("lifecycle-model"),
                Set.of("TEXT"),
                8192,
                2048,
                false,
                enabled,
                20,
                2,
                "DIRECT",
                List.of());
    }
}
