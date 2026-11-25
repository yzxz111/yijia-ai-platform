package com.yuzhihuihe.apiplatform.gateway.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.routing.CredentialCipher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

class ManagedApiKeyRotationServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void rotatesOnceAndDoesNotRevealSecretOnOperationRetry() {
        ManagedApiKeyRepository repository = mock(ManagedApiKeyRepository.class);
        TransactionalOperator transactions = mock(TransactionalOperator.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        doAnswer(invocation -> invocation.getArgument(0))
                .when(transactions).transactional(any(Mono.class));

        UUID tenantId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        AtomicReference<UUID> replacementId = new AtomicReference<>();
        AtomicInteger lockCount = new AtomicInteger();
        when(repository.findManagedKeyUser(tenantId, keyId)).thenReturn(Mono.just(userId));
        when(repository.lockUser(userId)).thenReturn(Mono.empty());
        when(repository.insertRotationIntent(
                operationId, tenantId, actorUserId, userId, keyId))
                .thenReturn(Mono.just(1L), Mono.just(0L));
        when(repository.lockRotation(operationId)).thenAnswer(invocation -> {
            if (lockCount.getAndIncrement() == 0) {
                return Mono.just(new ManagedApiKeyRepository.RotationRow(
                        operationId, tenantId, actorUserId, userId, keyId,
                        null, "PENDING", null));
            }
            return Mono.just(new ManagedApiKeyRepository.RotationRow(
                    operationId, tenantId, actorUserId, userId, keyId, replacementId.get(),
                    "COMPLETED", "sk-yzh-newprefix"));
        });
        when(repository.lockManagedKeyForRotation(tenantId, userId, keyId))
                .thenReturn(Mono.just(new ManagedApiKeyRepository.ManagedKeyForRotationRow(
                        keyId, tenantId, userId, packageId, "Internal client",
                        "sk-yzh-oldprefix", "a".repeat(64), "[\"text-basic\"]",
                        Instant.now().plus(Duration.ofDays(30)), "TOKEN",
                        // 套餐当前的名单比旧 Key 的快照多一个客户端文本通道：替代 Key
                        // 必须带**套餐的**那一份，否则轮换会把一份过期的授权名单再钉一次。
                        """
                        [{"modelCode":"text-basic","modelTokenQuota":null,
                          "capabilities":["TEXT"]},
                         {"modelCode":"chat-standard","modelTokenQuota":null,
                          "capabilities":["TEXT"]}]
                        """)));
        when(apiKeyService.generateSecret()).thenReturn(new ApiKeyService.GeneratedSecret(
                "sk-yzh-new-secret-value-that-is-long-enough-for-authentication",
                "sk-yzh-newprefix", "b".repeat(64)));
        when(cipher.encrypt(any())).thenReturn(new byte[32]);
        when(repository.revokeManagedKey(keyId)).thenReturn(Mono.just(1L));
        when(repository.insertManagedKey(
                any(UUID.class), eq(tenantId), eq(userId), eq(packageId),
                eq("Internal client"), eq("sk-yzh-newprefix"), eq("b".repeat(64)),
                eq("[\"chat-standard\",\"text-basic\"]"), any(Instant.class), any(byte[].class),
                anyShort(), eq("TOKEN")))
                .thenReturn(Mono.just(1L));
        when(repository.insertAudit(
                eq(operationId), eq(tenantId), eq(actorUserId),
                eq("MANAGED_API_KEY_ROTATE"), eq(keyId.toString()), any()))
                .thenReturn(Mono.just(1L));
        when(repository.completeRotation(eq(operationId), any(UUID.class)))
                .thenAnswer(invocation -> {
                    replacementId.set(invocation.getArgument(1));
                    return Mono.just(1L);
                });
        when(apiKeyService.evictAuthenticationCache(any())).thenReturn(Mono.empty());
        ManagedApiKeyService service = new ManagedApiKeyService(
                repository, transactions, cipher, apiKeyService, new ObjectMapper());

        ManagedApiKeyService.ManagedKeyRotation first = service.rotate(
                tenantId, actorUserId, keyId, operationId).block(Duration.ofSeconds(2));
        ManagedApiKeyService.ManagedKeyRotation retried = service.rotate(
                tenantId, actorUserId, keyId, operationId).block(Duration.ofSeconds(2));

        assertThat(first).isNotNull();
        assertThat(first.secretAvailable()).isTrue();
        assertThat(first.apiKey()).startsWith("sk-yzh-");
        assertThat(retried).isNotNull();
        assertThat(retried.newKeyId()).isEqualTo(first.newKeyId());
        assertThat(retried.secretAvailable()).isFalse();
        assertThat(retried.apiKey()).isNull();
        verify(repository, times(1)).revokeManagedKey(keyId);
        verify(apiKeyService).evictAuthenticationCache("a".repeat(64));
        verify(apiKeyService).evictAuthenticationCache("b".repeat(64));
        ArgumentCaptor<String> auditDetail = ArgumentCaptor.forClass(String.class);
        verify(repository).insertAudit(
                eq(operationId), eq(tenantId), eq(actorUserId),
                eq("MANAGED_API_KEY_ROTATE"), eq(keyId.toString()), auditDetail.capture());
        assertThat(auditDetail.getValue())
                .contains(operationId.toString(), keyId.toString(), replacementId.get().toString())
                .doesNotContain(first.apiKey(), "ciphertext", "secret");
    }
}
