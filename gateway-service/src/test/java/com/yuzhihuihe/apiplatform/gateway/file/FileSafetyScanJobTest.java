package com.yuzhihuihe.apiplatform.gateway.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuzhihuihe.apiplatform.gateway.safety.ContentSafetyOrchestrator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

class FileSafetyScanJobTest {
    private static final Instant NOW = Instant.parse("2026-08-10T03:00:00Z");
    private final byte[] bytes = "safe text".getBytes(StandardCharsets.UTF_8);
    private UploadSessionRepository repository;
    private FileObjectStore objectStore;
    private FileContentValidator validator;
    private ContentSafetyOrchestrator safety;
    private SimpleMeterRegistry registry;
    private FileSafetyScanJob job;
    private Scheduler fileSafetyScheduler;

    @Test
    void declaresTheProductionConstructorForSpringInjection() {
        long injectableConstructors = java.util.Arrays.stream(FileSafetyScanJob.class.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .count();

        assertThat(injectableConstructors).isEqualTo(1);
    }

    @BeforeEach
    void setUp() {
        repository = mock(UploadSessionRepository.class);
        objectStore = mock(FileObjectStore.class);
        validator = mock(FileContentValidator.class);
        safety = mock(ContentSafetyOrchestrator.class);
        registry = new SimpleMeterRegistry();
        fileSafetyScheduler = Schedulers.newBoundedElastic(1, 10, "file-safety-test");
        job = new FileSafetyScanJob(repository, objectStore, validator, safety,
                new FileScanMetrics(registry), Clock.fixed(NOW, ZoneOffset.UTC),
                20, Duration.ofMinutes(5), 5, Duration.ofSeconds(30), Duration.ofMinutes(15),
                Duration.ofSeconds(5), 2, fileSafetyScheduler);
    }

    @AfterEach
    void tearDown() {
        fileSafetyScheduler.dispose();
    }

    @Test
    void scansPromotesAndFencesReadyBeforeDeletingQuarantine() {
        UploadSessionRepository.ScanClaim claim = claim(0, sha256(bytes));
        AtomicReference<String> getThread = new AtomicReference<>();
        when(repository.claimForScan(20, NOW, Duration.ofMinutes(5))).thenReturn(Flux.just(claim));
        when(objectStore.get(
                        claim.session().quarantineObjectKey(), claim.session().sizeBytes()))
                .thenReturn(Mono.defer(() -> {
            getThread.set(Thread.currentThread().getName());
            return Mono.just(bytes);
        }));
        when(validator.validateUpload("safe.txt", "text/plain", bytes, bytes.length))
                .thenReturn(new FileContentValidator.ValidatedContent("text/plain", sha256(bytes), bytes.length));
        when(safety.inspectFile(any(), eq(bytes), eq("safe.txt"), eq("text/plain"), any()))
                .thenReturn(Mono.just(new ContentSafetyOrchestrator.FileDecision(true, false, "allowed")));
        String cleanKey = cleanKey(claim);
        when(objectStore.copy(claim.session().quarantineObjectKey(), cleanKey)).thenReturn(Mono.empty());
        when(objectStore.head(cleanKey)).thenReturn(Mono.just(new StoredObjectMetadata(
                cleanKey, bytes.length, "text/plain", "etag", NOW)));
        when(repository.markReady(claim.session().id(), claim.claimToken(), cleanKey, NOW)).thenReturn(Mono.just(true));
        when(objectStore.delete(claim.session().quarantineObjectKey())).thenReturn(Mono.empty());

        job.runOnce().block();

        InOrder order = inOrder(repository, objectStore, validator, safety);
        order.verify(repository).claimForScan(20, NOW, Duration.ofMinutes(5));
        order.verify(objectStore).get(
                claim.session().quarantineObjectKey(), claim.session().sizeBytes());
        order.verify(validator).validateUpload("safe.txt", "text/plain", bytes, bytes.length);
        order.verify(safety).inspectFile(any(), eq(bytes), eq("safe.txt"), eq("text/plain"), any());
        order.verify(objectStore).copy(claim.session().quarantineObjectKey(), cleanKey);
        order.verify(objectStore).head(cleanKey);
        order.verify(repository).markReady(claim.session().id(), claim.claimToken(), cleanKey, NOW);
        order.verify(objectStore).delete(claim.session().quarantineObjectKey());
        assertThat(getThread.get()).contains("file-safety-test");
        assertThat(counter("gateway.file.scan", "outcome", "ready")).isEqualTo(1);
    }

    @Test
    void rejectsInvalidOrMaliciousFileAndDeletesQuarantine() {
        UploadSessionRepository.ScanClaim claim = claim(0, sha256(bytes));
        when(repository.claimForScan(anyInt(), any(), any())).thenReturn(Flux.just(claim));
        when(objectStore.get(any(), anyLong())).thenReturn(Mono.just(bytes));
        when(validator.validateUpload(any(), any(), any(), any(Long.class)))
                .thenThrow(new IllegalArgumentException("invalid_file_content"));
        when(repository.markRejected(claim.session().id(), claim.claimToken(), "invalid_file_content", NOW))
                .thenReturn(Mono.just(true));
        when(objectStore.delete(claim.session().quarantineObjectKey())).thenReturn(Mono.empty());

        job.runOnce().block();

        verify(repository).markRejected(claim.session().id(), claim.claimToken(), "invalid_file_content", NOW);
        verify(objectStore).delete(claim.session().quarantineObjectKey());
        verify(safety, never()).inspectFile(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsAnOversizedStoredObjectWithoutRetrying() {
        UploadSessionRepository.ScanClaim claim = claim(0, sha256(bytes));
        when(repository.claimForScan(anyInt(), any(), any())).thenReturn(Flux.just(claim));
        when(objectStore.get(
                        claim.session().quarantineObjectKey(), claim.session().sizeBytes()))
                .thenReturn(Mono.error(new FileObjectTooLargeException()));
        when(repository.markRejected(
                        claim.session().id(),
                        claim.claimToken(),
                        "file_object_too_large",
                        NOW))
                .thenReturn(Mono.just(true));
        when(objectStore.delete(claim.session().quarantineObjectKey())).thenReturn(Mono.empty());

        job.runOnce().block();

        verify(repository).markRejected(
                claim.session().id(), claim.claimToken(), "file_object_too_large", NOW);
        verify(repository, never()).retryOrFail(
                any(), any(), any(), any(), anyInt(), any());
        verify(objectStore).delete(claim.session().quarantineObjectKey());
    }

    @Test
    void retriesUnavailableSafetyWithExponentialBackoffAndFifthAttemptFails() {
        UploadSessionRepository.ScanClaim claim = claim(4, sha256(bytes));
        when(repository.claimForScan(anyInt(), any(), any())).thenReturn(Flux.just(claim));
        when(objectStore.get(any(), anyLong())).thenReturn(Mono.just(bytes));
        when(validator.validateUpload(any(), any(), any(), any(Long.class)))
                .thenReturn(new FileContentValidator.ValidatedContent("text/plain", sha256(bytes), bytes.length));
        when(safety.inspectFile(any(), any(), any(), any(), any())).thenReturn(Mono.just(
                new ContentSafetyOrchestrator.FileDecision(false, true, "content_safety_unavailable")));
        when(repository.retryOrFail(claim.session().id(), claim.claimToken(), "content_safety_unavailable",
                NOW.plus(Duration.ofMinutes(8)), 5, NOW)).thenReturn(Mono.just(true));

        job.runOnce().block();

        verify(repository).retryOrFail(claim.session().id(), claim.claimToken(), "content_safety_unavailable",
                NOW.plus(Duration.ofMinutes(8)), 5, NOW);
        verify(objectStore, never()).copy(any(), any());
    }

    @Test
    void claimUniqueCleanKeysLetStaleWorkerDeleteOnlyItsOwnCopy() {
        UploadSessionRepository.ScanClaim stale = claim(0, sha256(bytes));
        UploadSessionRepository.ScanClaim newer = new UploadSessionRepository.ScanClaim(
                stale.session(), UUID.randomUUID());
        String staleKey = cleanKey(stale);
        String newerKey = cleanKey(newer);
        assertThat(staleKey).isNotEqualTo(newerKey);
        when(repository.claimForScan(anyInt(), any(), any())).thenReturn(Flux.just(stale, newer));
        when(objectStore.get(any(), anyLong())).thenReturn(Mono.just(bytes));
        when(validator.validateUpload(any(), any(), any(), any(Long.class)))
                .thenReturn(new FileContentValidator.ValidatedContent("text/plain", sha256(bytes), bytes.length));
        when(safety.inspectFile(any(), any(), any(), any(), any())).thenReturn(Mono.just(
                new ContentSafetyOrchestrator.FileDecision(true, false, "allowed")));
        when(objectStore.copy(any(), any())).thenReturn(Mono.empty());
        when(objectStore.head(any())).thenAnswer(invocation -> Mono.just(new StoredObjectMetadata(
                invocation.getArgument(0), bytes.length, "text/plain", "etag", NOW)));
        when(repository.markReady(stale.session().id(), stale.claimToken(), staleKey, NOW)).thenReturn(Mono.just(false));
        when(repository.markReady(newer.session().id(), newer.claimToken(), newerKey, NOW)).thenReturn(Mono.just(true));
        when(objectStore.delete(any())).thenReturn(Mono.empty());

        job.runOnce().block();

        verify(repository).markReady(stale.session().id(), stale.claimToken(), staleKey, NOW);
        verify(repository).markReady(newer.session().id(), newer.claimToken(), newerKey, NOW);
        verify(objectStore).delete(staleKey);
        verify(objectStore, never()).delete(newerKey);
        assertThat(counter("gateway.file.orphan.object", "type", "clean_fence_lost")).isEqualTo(1);
    }

    @Test
    void keepsReadyWhenQuarantineDeleteFailsAndCountsCleanupDebt() {
        UploadSessionRepository.ScanClaim claim = claim(0, sha256(bytes));
        arrangeAllowed(claim);
        String cleanKey = cleanKey(claim);
        when(repository.markReady(claim.session().id(), claim.claimToken(), cleanKey, NOW)).thenReturn(Mono.just(true));
        when(objectStore.delete(claim.session().quarantineObjectKey()))
                .thenReturn(Mono.error(new IllegalStateException("oss unavailable")));

        job.runOnce().block();

        verify(repository).markReady(claim.session().id(), claim.claimToken(), cleanKey, NOW);
        assertThat(counter("gateway.file.orphan.object", "type", "quarantine_cleanup_debt")).isEqualTo(1);
    }

    private void arrangeAllowed(UploadSessionRepository.ScanClaim claim) {
        when(repository.claimForScan(anyInt(), any(), any())).thenReturn(Flux.just(claim));
        when(objectStore.get(any(), anyLong())).thenReturn(Mono.just(bytes));
        when(validator.validateUpload(any(), any(), any(), any(Long.class)))
                .thenReturn(new FileContentValidator.ValidatedContent("text/plain", sha256(bytes), bytes.length));
        when(safety.inspectFile(any(), any(), any(), any(), any())).thenReturn(Mono.just(
                new ContentSafetyOrchestrator.FileDecision(true, false, "allowed")));
        when(objectStore.copy(any(), any())).thenReturn(Mono.empty());
        when(objectStore.head(any())).thenReturn(Mono.just(new StoredObjectMetadata(
                "clean/tenant/user/file-id/object", bytes.length, "text/plain", "etag", NOW)));
    }

    private UploadSessionRepository.ScanClaim claim(int attempts, String declaredSha256) {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UploadSessionRepository.UploadSession session = new UploadSessionRepository.UploadSession(
                id, "file-id", UUID.randomUUID(), tenant, user, "upl-id", UUID.randomUUID().toString(),
                "quarantine/tenant/user/file-id/object", "quarantine/tenant/user/file-id/object",
                "safe.txt", declaredSha256, "text/plain", bytes.length, "SCANNING",
                NOW.plusSeconds(60), NOW.plusSeconds(3600), "etag", token, NOW, attempts,
                NOW, null, NOW, NOW);
        return new UploadSessionRepository.ScanClaim(session, token);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String cleanKey(UploadSessionRepository.ScanClaim claim) {
        return "clean/tenant/user/file-id/object/claims/" + claim.claimToken();
    }

    private double counter(String name, String tag, String value) {
        return registry.find(name).tag(tag, value).counter().count();
    }
}
