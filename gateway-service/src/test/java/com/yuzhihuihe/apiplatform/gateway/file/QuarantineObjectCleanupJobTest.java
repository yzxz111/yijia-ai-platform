package com.yuzhihuihe.apiplatform.gateway.file;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class QuarantineObjectCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");

    @Test
    void deletesThePersistedQuarantineKeyAndClearsOnlyTheClaimingToken() {
        UploadSessionRepository repository = mock(UploadSessionRepository.class);
        FileObjectStore store = mock(FileObjectStore.class);
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        var claim = new UploadSessionRepository.QuarantineCleanupClaim(
                id, "quarantine/exact-key", token);
        when(repository.claimQuarantineCleanup(100, NOW, Duration.ofMinutes(5)))
                .thenReturn(Flux.just(claim));
        when(store.delete(claim.quarantineObjectKey())).thenReturn(Mono.empty());
        when(repository.markQuarantineDeleted(id, token, NOW)).thenReturn(Mono.just(true));

        job(repository, store).cleanup().block(Duration.ofSeconds(1));

        verify(store).delete("quarantine/exact-key");
        verify(repository).markQuarantineDeleted(id, token, NOW);
    }

    @Test
    void failedDeleteLeavesDebtForARotatedLeaseToken() {
        UploadSessionRepository repository = mock(UploadSessionRepository.class);
        FileObjectStore store = mock(FileObjectStore.class);
        UUID id = UUID.randomUUID();
        UUID firstToken = UUID.randomUUID();
        UUID retryToken = UUID.randomUUID();
        var first = new UploadSessionRepository.QuarantineCleanupClaim(
                id, "quarantine/exact-key", firstToken);
        var retry = new UploadSessionRepository.QuarantineCleanupClaim(
                id, "quarantine/exact-key", retryToken);
        when(repository.claimQuarantineCleanup(100, NOW, Duration.ofMinutes(5)))
                .thenReturn(Flux.just(first), Flux.just(retry));
        when(store.delete("quarantine/exact-key"))
                .thenReturn(Mono.error(new IllegalStateException("storage_unavailable")), Mono.empty());
        when(repository.markQuarantineDeleted(id, retryToken, NOW)).thenReturn(Mono.just(true));

        QuarantineObjectCleanupJob job = job(repository, store);
        job.cleanup().block(Duration.ofSeconds(1));
        job.cleanup().block(Duration.ofSeconds(1));

        verify(store, times(2)).delete("quarantine/exact-key");
        verify(repository, never()).markQuarantineDeleted(id, firstToken, NOW);
        verify(repository).markQuarantineDeleted(id, retryToken, NOW);
    }

    private static QuarantineObjectCleanupJob job(
            UploadSessionRepository repository, FileObjectStore store) {
        return new QuarantineObjectCleanupJob(
                repository, store, Clock.fixed(NOW, ZoneOffset.UTC), 100, Duration.ofMinutes(5));
    }
}
