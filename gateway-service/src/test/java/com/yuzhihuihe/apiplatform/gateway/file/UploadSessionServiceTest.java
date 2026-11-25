package com.yuzhihuihe.apiplatform.gateway.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class UploadSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T02:03:04Z");
    private static final String SHA256 = "0123456789abcdef".repeat(4);
    private UploadEntitlementService entitlementService;
    private UploadSessionRepository repository;
    private FileObjectStore objectStore;
    private UploadFormSigner signer;
    private FileStorageProperties properties;
    private SimpleMeterRegistry registry;
    private UploadSessionService service;

    @BeforeEach
    void setUp() {
        entitlementService = mock(UploadEntitlementService.class);
        repository = mock(UploadSessionRepository.class);
        objectStore = mock(FileObjectStore.class);
        signer = mock(UploadFormSigner.class);
        properties = new FileStorageProperties();
        properties.setDirectUploadEnabled(true);
        properties.setSignedUploadTtl(Duration.ofMinutes(5));
        properties.setObjectTtl(Duration.ofHours(24));
        registry = new SimpleMeterRegistry();
        service = new UploadSessionService(
                entitlementService, new FileUploadMetadataValidator(), repository, objectStore, signer,
                properties, new FileUploadMetrics(registry),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsOwnerScopedSessionBeforeSigningWithExactTtlsAndQuarantineKey() {
        ApiKeyService.ApiKeyContext context = context();
        var command = command();
        when(entitlementService.resolve(context)).thenReturn(Mono.just(
                new UploadEntitlementService.UploadEntitlement("PACKAGE", 20 * 1024 * 1024, "STARTER")));
        AtomicReference<UploadSessionRepository.UploadSession> saved = new AtomicReference<>();
        when(repository.createOrFind(any())).thenAnswer(invocation -> {
            UploadSessionRepository.UploadSession draft = invocation.getArgument(0);
            saved.set(draft);
            return Mono.just(draft);
        });
        when(signer.sign(any(), any(), any(Long.class), any())).thenAnswer(invocation -> Mono.just(
                new UploadFormSigner.SignedUploadForm(
                        "POST", URI.create("https://files.example.test/"),
                        Map.of("key", invocation.getArgument(0), "policy", "opaque"),
                        invocation.getArgument(3))));

        UploadSessionService.UploadSessionResult result = service.create(
                context, command, UUID.randomUUID().toString()).block();

        UploadSessionRepository.UploadSession persisted = saved.get();
        assertThat(persisted.apiKeyId()).isEqualTo(context.apiKeyId());
        assertThat(persisted.tenantId()).isEqualTo(context.tenantId());
        assertThat(persisted.ownerUserId()).isEqualTo(context.userId());
        assertThat(persisted.externalFileId()).startsWith("file_");
        assertThat(persisted.uploadSessionId()).startsWith("upl_");
        assertThat(persisted.objectKey()).matches("quarantine/" + context.tenantId() + "/"
                + context.userId() + "/2026/08/10/" + persisted.externalFileId() + "/[0-9a-f]{32}");
        assertThat(persisted.quarantineObjectKey()).isEqualTo(persisted.objectKey());
        assertThat(persisted.uploadExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(persisted.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(result.fileId()).isEqualTo(persisted.externalFileId());
        assertThat(result.uploadSessionId()).isEqualTo(persisted.uploadSessionId());
        assertThat(result.upload().fields()).containsEntry("key", persisted.objectKey());
        var order = inOrder(entitlementService, repository, signer);
        order.verify(entitlementService).resolve(context);
        order.verify(repository).createOrFind(any());
        order.verify(signer).sign(
                persisted.objectKey(), persisted.mimeType(), persisted.sizeBytes(), persisted.uploadExpiresAt());
        assertThat(count("created")).isEqualTo(1);
    }

    @Test
    void resolvesEntitlementBeforeMetadataValidationAndRejectsWithoutPersistenceOrSigning() {
        ApiKeyService.ApiKeyContext context = context();
        when(entitlementService.resolve(context)).thenReturn(Mono.just(
                new UploadEntitlementService.UploadEntitlement("PACKAGE", 1, "FREE")));

        assertReason(service.create(context, command(), UUID.randomUUID().toString()), "file_too_large");

        verify(entitlementService).resolve(context);
        verifyNoInteractions(repository, signer);
        assertThat(count("rejected")).isEqualTo(1);
    }

    @Test
    void replayResignsOnlyPersistedValuesAndReturnsOriginalSession() {
        ApiKeyService.ApiKeyContext context = context();
        String idempotencyKey = UUID.randomUUID().toString();
        when(entitlementService.resolve(context)).thenReturn(Mono.just(
                new UploadEntitlementService.UploadEntitlement("PACKAGE", 20 * 1024 * 1024, "STARTER")));
        UploadSessionRepository.UploadSession original = session(
                context, idempotencyKey, NOW.plusSeconds(120), "quarantine/persisted/exact-key");
        when(repository.createOrFind(any())).thenReturn(Mono.just(original));
        when(signer.sign(original.objectKey(), original.mimeType(), original.sizeBytes(), original.uploadExpiresAt()))
                .thenReturn(Mono.just(new UploadFormSigner.SignedUploadForm(
                        "POST", URI.create("https://files.example.test/"),
                        Map.of("key", original.objectKey(), "policy", "same"), original.uploadExpiresAt())));

        UploadSessionService.UploadSessionResult result = service.create(context, command(), idempotencyKey).block();

        assertThat(result.fileId()).isEqualTo(original.externalFileId());
        assertThat(result.uploadSessionId()).isEqualTo(original.uploadSessionId());
        assertThat(result.uploadExpiresAt()).isEqualTo(original.uploadExpiresAt());
        assertThat(result.fileExpiresAt()).isEqualTo(original.expiresAt());
        verify(repository).createOrFind(any());
        verify(signer).sign(
                original.objectKey(), original.mimeType(), original.sizeBytes(), original.uploadExpiresAt());
        assertThat(count("replayed")).isEqualTo(1);
        assertThat(count("created")).isZero();
    }

    @Test
    void rejectsDisabledInvalidAndExpiredSessionsWithoutLeakingIntoSigning() {
        ApiKeyService.ApiKeyContext context = context();
        properties.setDirectUploadEnabled(false);
        assertReason(service.create(context, command(), UUID.randomUUID().toString()), "direct_upload_disabled");
        verifyNoInteractions(entitlementService, repository, signer);

        properties.setDirectUploadEnabled(true);
        assertReason(service.create(context, command(), "not-a-uuid"), "invalid_idempotency_key");
        verifyNoInteractions(entitlementService, repository, signer);

        when(entitlementService.resolve(context)).thenReturn(Mono.just(
                new UploadEntitlementService.UploadEntitlement("PACKAGE", 20 * 1024 * 1024, "STARTER")));
        when(repository.createOrFind(any())).thenReturn(Mono.just(session(
                context, UUID.randomUUID().toString(), NOW, "quarantine/persisted/expired")));
        assertReason(service.create(context, command(), UUID.randomUUID().toString()), "upload_session_expired");
        verify(signer, never()).sign(any(), any(), any(Long.class), any());
        assertThat(count("rejected")).isEqualTo(3);
    }

    @Test
    void signingFailureUsesFailedMetricWithBoundedTagsOnly() {
        ApiKeyService.ApiKeyContext context = context();
        when(entitlementService.resolve(context)).thenReturn(Mono.just(
                new UploadEntitlementService.UploadEntitlement("PACKAGE", 20 * 1024 * 1024, "STARTER")));
        when(repository.createOrFind(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(signer.sign(any(), any(), any(Long.class), any()))
                .thenReturn(Mono.error(new IllegalStateException("provider secret")));

        assertThatThrownBy(() -> service.create(
                context, command(), UUID.randomUUID().toString()).block())
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("failed")).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> {
            assertThat(meter.getId().getName()).isEqualTo("gateway.file.upload_session");
            assertThat(meter.getId().getTags()).hasSize(1);
            assertThat(meter.getId().getTag("outcome"))
                    .isIn("created", "replayed", "rejected", "failed");
        });
    }

    @Test
    void completesOnlyAnOwnedUnexpiredPendingSessionUsingItsPersistedQuarantineKey() {
        ApiKeyService.ApiKeyContext context = context();
        UploadSessionRepository.UploadSession pending = session(
                context, UUID.randomUUID().toString(), NOW.plusSeconds(60), "quarantine/persisted/exact-key");
        UploadSessionRepository.UploadSession uploaded = withStatus(pending, "UPLOADED");
        when(repository.findOwned(pending.uploadSessionId(), context.apiKeyId(), context.tenantId(), context.userId()))
                .thenReturn(Mono.just(pending));
        when(objectStore.head(pending.objectKey())).thenReturn(Mono.just(new StoredObjectMetadata(
                pending.objectKey(), pending.sizeBytes(), "Application/PDF; charset=binary", "etag-safe", NOW)));
        when(repository.markUploaded(
                pending.id(), context.apiKeyId(), context.tenantId(), context.userId(), "etag-safe", NOW))
                .thenReturn(Mono.just(uploaded));

        FileObjectRepository.FileObject result = service.complete(
                context, pending.uploadSessionId(), UUID.randomUUID().toString()).block();

        assertThat(result.status()).isEqualTo("UPLOADED");
        assertThat(result.objectKey()).isEqualTo(pending.objectKey());
        verify(objectStore).head("quarantine/persisted/exact-key");
        verify(repository).markUploaded(
                pending.id(), context.apiKeyId(), context.tenantId(), context.userId(), "etag-safe", NOW);
        assertThat(completeCount("accepted")).isEqualTo(1);
    }

    @Test
    void rejectsForeignExpiredAndInvalidCompleteRequestsBeforeHead() {
        ApiKeyService.ApiKeyContext context = context();
        when(repository.findOwned("upl_foreign", context.apiKeyId(), context.tenantId(), context.userId()))
                .thenReturn(Mono.empty());
        UploadSessionRepository.UploadSession expired = session(
                context, UUID.randomUUID().toString(), NOW, "quarantine/persisted/expired");
        when(repository.findOwned(expired.uploadSessionId(), context.apiKeyId(), context.tenantId(), context.userId()))
                .thenReturn(Mono.just(expired));

        assertReason(service.complete(context, "upl_foreign", UUID.randomUUID().toString()),
                "upload_session_not_found");
        assertReason(service.complete(context, expired.uploadSessionId(), UUID.randomUUID().toString()),
                "upload_session_expired");
        assertReason(service.complete(context, expired.uploadSessionId(), "invalid"),
                "invalid_idempotency_key");

        verifyNoInteractions(objectStore);
    }

    @Test
    void missingObjectStaysRetryableWhileMetadataMismatchIsConditionallyRejected() {
        ApiKeyService.ApiKeyContext context = context();
        UploadSessionRepository.UploadSession missing = session(
                context, UUID.randomUUID().toString(), NOW.plusSeconds(60), "quarantine/persisted/missing");
        UploadSessionRepository.UploadSession mismatched = session(
                context, UUID.randomUUID().toString(), NOW.plusSeconds(60), "quarantine/persisted/mismatch");
        when(repository.findOwned(missing.uploadSessionId(), context.apiKeyId(), context.tenantId(), context.userId()))
                .thenReturn(Mono.just(missing));
        when(repository.findOwned(mismatched.uploadSessionId(), context.apiKeyId(), context.tenantId(), context.userId()))
                .thenReturn(Mono.just(mismatched));
        when(objectStore.head(missing.objectKey())).thenReturn(Mono.error(new FileObjectMissingException()));
        when(objectStore.head(mismatched.objectKey())).thenReturn(Mono.just(new StoredObjectMetadata(
                mismatched.objectKey(), mismatched.sizeBytes() + 1, "text/plain", "ignored", NOW)));
        when(repository.rejectPending(mismatched.id(), "upload_object_mismatch", NOW))
                .thenReturn(Mono.just(withStatus(mismatched, "REJECTED")));

        assertReason(service.complete(context, missing.uploadSessionId(), UUID.randomUUID().toString()),
                "upload_object_missing");
        assertReason(service.complete(context, mismatched.uploadSessionId(), UUID.randomUUID().toString()),
                "upload_object_mismatch");

        verify(repository, never()).rejectPending(missing.id(), "upload_object_missing", NOW);
        verify(repository).rejectPending(mismatched.id(), "upload_object_mismatch", NOW);
        assertThat(completeCount("missing")).isEqualTo(1);
        assertThat(completeCount("mismatch")).isEqualTo(1);
    }

    @Test
    void mapsStorageFailureToAStableRetryableErrorWithoutRejectingTheSession() {
        ApiKeyService.ApiKeyContext context = context();
        UploadSessionRepository.UploadSession pending = session(
                context, UUID.randomUUID().toString(), NOW.plusSeconds(60),
                "quarantine/persisted/storage-unavailable");
        when(repository.findOwned(pending.uploadSessionId(), context.apiKeyId(), context.tenantId(), context.userId()))
                .thenReturn(Mono.just(pending));
        when(objectStore.head(pending.quarantineObjectKey()))
                .thenReturn(Mono.error(new FileStorageException()));

        assertReason(service.complete(context, pending.uploadSessionId(), UUID.randomUUID().toString()),
                "file_storage_unavailable");

        verify(repository, never()).rejectPending(any(), any(), any());
        verify(repository, never()).markUploaded(any(), any(), any(), any(), any(), any());
        assertThat(completeCount("failed")).isEqualTo(1);
    }

    @Test
    void replaysEveryPostPendingStatusWithoutHeadOrAnotherScanUnit() {
        ApiKeyService.ApiKeyContext context = context();
        for (String status : Set.of("UPLOADED", "SCANNING", "READY", "REJECTED", "FAILED")) {
            UploadSessionRepository.UploadSession current = withStatus(session(
                    context, UUID.randomUUID().toString(), NOW.plusSeconds(60),
                    "quarantine/persisted/" + status.toLowerCase()), status);
            when(repository.findOwned(
                    current.uploadSessionId(), context.apiKeyId(), context.tenantId(), context.userId()))
                    .thenReturn(Mono.just(current));

            FileObjectRepository.FileObject replay = service.complete(
                    context, current.uploadSessionId(), UUID.randomUUID().toString()).block();

            assertThat(replay.status()).isEqualTo(status);
        }

        verifyNoInteractions(objectStore);
        verify(repository, never()).markUploaded(any(), any(), any(), any(), any(), any());
        assertThat(completeCount("replayed")).isEqualTo(5);
    }

    private double count(String outcome) {
        var counter = registry.find("gateway.file.upload_session").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private double completeCount(String outcome) {
        var counter = registry.find("gateway.file.direct_upload_complete")
                .tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private static ApiKeyService.ApiKeyContext context() {
        return new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Set.of("vision-standard"));
    }

    private static UploadSessionService.CreateUploadSessionCommand command() {
        return new UploadSessionService.CreateUploadSessionCommand(
                "report.pdf", 1024, "application/pdf", SHA256, "assistants");
    }

    private static UploadSessionRepository.UploadSession session(
            ApiKeyService.ApiKeyContext context, String idempotencyKey,
            Instant uploadExpiresAt, String objectKey) {
        return new UploadSessionRepository.UploadSession(
                UUID.randomUUID(), "file_original", context.apiKeyId(), context.tenantId(),
                context.userId(), "upl_" + UUID.randomUUID().toString().replace("-", ""),
                idempotencyKey, objectKey, objectKey,
                "persisted.pdf", SHA256, "application/pdf", 17, "PENDING_UPLOAD",
                uploadExpiresAt, NOW.plus(Duration.ofHours(24)), null, null, null, 0,
                null, null, NOW, NOW);
    }

    private static UploadSessionRepository.UploadSession withStatus(
            UploadSessionRepository.UploadSession session, String status) {
        return new UploadSessionRepository.UploadSession(
                session.id(), session.externalFileId(), session.apiKeyId(), session.tenantId(),
                session.ownerUserId(), session.uploadSessionId(), session.uploadIdempotencyKey(),
                session.objectKey(), session.quarantineObjectKey(), session.originalFilename(),
                session.declaredSha256(), session.mimeType(), session.sizeBytes(), status,
                session.uploadExpiresAt(), session.expiresAt(), session.etag(), session.scanClaimToken(),
                session.scanClaimedAt(), session.scanAttempts(), session.nextScanAt(),
                session.lastErrorCode(), session.createdAt(), session.updatedAt());
    }

    private static void assertReason(Mono<?> result, String reason) {
        assertThatThrownBy(result::block)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getReason())
                .isEqualTo(reason);
    }
}
