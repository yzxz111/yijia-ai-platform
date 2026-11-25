package com.yuzhihuihe.apiplatform.gateway.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.routing.CredentialCipher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ManagedApiKeyBootstrapServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void failedAuthorizationRefreshDoesNotRevealOrCreateAKey() {
        ManagedApiKeyRepository repository = mock(ManagedApiKeyRepository.class);
        TransactionalOperator transactions = mock(TransactionalOperator.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        doAnswer(invocation -> invocation.getArgument(0))
                .when(transactions).transactional(any(Mono.class));

        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        byte[] ciphertext = new byte[32];
        AtomicBoolean auditSubscribed = new AtomicBoolean();
        when(repository.lockUser(userId)).thenReturn(Mono.empty());
        when(repository.findActivePackagesForUpdate(tenantId, userId))
                .thenReturn(Flux.just(new ManagedApiKeyRepository.ActivePackageRow(
                        packageId, "ADVANCED", Instant.now().plus(Duration.ofDays(30)),
                        "[{\"modelCode\":\"text-max\"}]", false, "TOKEN")));
        when(repository.findManagedKey(tenantId, userId, packageId))
                .thenReturn(Mono.just(new ManagedApiKeyRepository.ManagedKeyRow(
                        keyId, "sk-yzh-testprefix", ciphertext)));
        when(cipher.decrypt(ciphertext)).thenReturn("test-existing-managed-secret");
        // 合并后 reveal 会先取客户端可见名单做交集，再走带守卫的 同步+标记已揭晓。
        // 这里让后者返回 0 行，模拟授权刷新失败。
        when(repository.findClientVisibleModels()).thenReturn(Flux.empty());
        when(repository.synchronizeAndMarkRevealed(
                keyId, tenantId, userId, packageId, "[\"text-max\"]"))
                .thenReturn(Mono.just(0L));
        when(repository.insertAudit(
                any(UUID.class), eq(tenantId), eq(userId),
                eq("MANAGED_API_KEY_REVEAL"), eq(keyId.toString()), any()))
                .thenReturn(Mono.defer(() -> {
                    auditSubscribed.set(true);
                    return Mono.just(1L);
                }));
        ManagedApiKeyService service = new ManagedApiKeyService(
                repository, transactions, cipher, apiKeyService, new ObjectMapper());

        assertThatThrownBy(() -> service.bootstrap(tenantId, userId, "TOKEN")
                .block(Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("client_bootstrap_unavailable");
                });
        assertThat(auditSubscribed).isFalse();
        verify(apiKeyService, never()).generateSecret();
    }
}
