package com.yuzhihuihe.apiplatform.platform.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.server.ResponseStatusException;

class ContentSafetyAdminOperationServiceTest {

    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final String ACTION = "CONTENT_SAFETY_ENFORCEMENT_APPLY";
    private static final String KEY = "content-safety-operation-0001";

    private final ContentSafetyAdminOperationRepository repository =
            mock(ContentSafetyAdminOperationRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ContentSafetyAdminOperationService service =
            new ContentSafetyAdminOperationService(repository, objectMapper);

    @Test
    void executesOnceAndReplaysThePersistedResponseForTheSameRequest() throws Exception {
        ContentSafetyAdminService.EnforcementView expected =
                new ContentSafetyAdminService.EnforcementView(
                        TENANT_ID, USER_ID, "FROZEN", "manual_review", null, 1);
        Map<String, Object> request = Map.of(
                "tenantId", TENANT_ID, "userId", USER_ID, "state", "FROZEN");
        AtomicInteger mutations = new AtomicInteger();
        when(repository.tryCreate(any(), eq(ACTOR_ID), eq(ACTION), eq(KEY), any()))
                .thenReturn(1, 0);

        ContentSafetyAdminService.EnforcementView first = service.execute(
                ACTOR_ID, KEY, ACTION, request,
                ContentSafetyAdminService.EnforcementView.class,
                () -> {
                    mutations.incrementAndGet();
                    return expected;
                },
                ignored -> new ContentSafetyAdminOperationService.AuditTarget(
                        TENANT_ID, "CONTENT_SAFETY_ENFORCEMENT", USER_ID.toString()));

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(repository).tryCreate(any(), eq(ACTOR_ID), eq(ACTION), eq(KEY), hash.capture());
        when(repository.lock(ACTOR_ID, ACTION, KEY)).thenReturn(
                new ContentSafetyAdminOperationRepository.OperationRow(
                        UUID.randomUUID(), hash.getValue(), objectMapper.writeValueAsString(expected)));

        ContentSafetyAdminService.EnforcementView replay = service.execute(
                ACTOR_ID, KEY, ACTION, request,
                ContentSafetyAdminService.EnforcementView.class,
                () -> {
                    mutations.incrementAndGet();
                    return expected;
                },
                ignored -> new ContentSafetyAdminOperationService.AuditTarget(
                        TENANT_ID, "CONTENT_SAFETY_ENFORCEMENT", USER_ID.toString()));

        assertThat(first).isEqualTo(expected);
        assertThat(replay).isEqualTo(expected);
        assertThat(mutations).hasValue(1);
        verify(repository).complete(
                any(), any(), eq("CONTENT_SAFETY_ENFORCEMENT"), eq(USER_ID.toString()));
        verify(repository).auditSuccess(
                any(), eq(TENANT_ID), eq(ACTOR_ID), eq(ACTION),
                eq("CONTENT_SAFETY_ENFORCEMENT"), eq(USER_ID.toString()),
                eq(KEY), eq(hash.getValue()));
    }

    @Test
    void rejectsReuseOfTheSameKeyForADifferentPayload() {
        when(repository.tryCreate(any(), eq(ACTOR_ID), eq(ACTION), eq(KEY), any()))
                .thenReturn(0);
        when(repository.lock(ACTOR_ID, ACTION, KEY)).thenReturn(
                new ContentSafetyAdminOperationRepository.OperationRow(
                        UUID.randomUUID(), "different-request-hash", "{}"));

        assertThatThrownBy(() -> service.execute(
                ACTOR_ID, KEY, ACTION, Map.of("state", "BANNED"),
                ContentSafetyAdminService.EnforcementView.class,
                () -> new ContentSafetyAdminService.EnforcementView(
                        TENANT_ID, USER_ID, "BANNED", "manual_review", null, 1),
                ignored -> new ContentSafetyAdminOperationService.AuditTarget(
                        TENANT_ID, "CONTENT_SAFETY_ENFORCEMENT", USER_ID.toString())))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getReason()).isEqualTo("content_safety_idempotency_payload_mismatch");
                });

        verify(repository, never()).complete(any(), any(), any(), any());
        verify(repository, never()).auditSuccess(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsMissingOrMalformedIdempotencyKeysBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.execute(
                ACTOR_ID, " ", ACTION, Map.of(), Object.class,
                Object::new, ignored -> new ContentSafetyAdminOperationService.AuditTarget(
                        null, "CONTENT_SAFETY_POLICY", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("invalid_idempotency_key");
                });

        verify(repository, never()).tryCreate(any(), any(), any(), any(), any());
    }

    @Test
    void everyAdministratorMutationDeclaresTheIdempotencyHeader() {
        var mutations = Stream.of(AdminContentSafetyController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .toList();

        assertThat(mutations).hasSize(8).allSatisfy(method -> assertThat(
                Stream.of(method.getParameters())
                        .map(parameter -> parameter.getAnnotation(RequestHeader.class))
                        .filter(annotation -> annotation != null)
                        .anyMatch(annotation -> "Idempotency-Key".equals(annotation.name())))
                .as(method.getName() + " idempotency header")
                .isTrue());
    }
}
