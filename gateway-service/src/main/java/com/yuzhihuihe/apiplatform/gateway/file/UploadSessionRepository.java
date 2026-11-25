package com.yuzhihuihe.apiplatform.gateway.file;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UploadSessionRepository {

    Mono<UploadSession> createOrFind(UploadSession draft);

    Mono<UploadSession> findOwned(String uploadSessionId, UUID apiKeyId, UUID tenantId, UUID userId);

    Mono<UploadSession> markUploaded(
            UUID id, UUID apiKeyId, UUID tenantId, UUID userId, String etag, Instant now);

    Mono<UploadSession> rejectPending(UUID id, String errorCode, Instant now);

    Flux<ScanClaim> claimForScan(int limit, Instant now, Duration lease);

    Mono<Boolean> markReady(UUID id, UUID claimToken, String cleanObjectKey, Instant now);

    Mono<Boolean> markRejected(UUID id, UUID claimToken, String errorCode, Instant now);

    Mono<Boolean> retryOrFail(
            UUID id,
            UUID claimToken,
            String errorCode,
            Instant nextAttemptAt,
            int maximumAttempts,
            Instant now);

    Flux<QuarantineCleanupClaim> claimQuarantineCleanup(int limit, Instant now, Duration lease);

    Mono<Boolean> markQuarantineDeleted(UUID id, UUID cleanupClaimToken, Instant now);

    record UploadSession(
            UUID id,
            String externalFileId,
            UUID apiKeyId,
            UUID tenantId,
            UUID ownerUserId,
            String uploadSessionId,
            String uploadIdempotencyKey,
            String objectKey,
            String quarantineObjectKey,
            String originalFilename,
            String declaredSha256,
            String mimeType,
            long sizeBytes,
            String status,
            Instant uploadExpiresAt,
            Instant expiresAt,
            String etag,
            UUID scanClaimToken,
            Instant scanClaimedAt,
            int scanAttempts,
            Instant nextScanAt,
            String lastErrorCode,
            Instant createdAt,
            Instant updatedAt) { }

    record ScanClaim(UploadSession session, UUID claimToken) { }

    record QuarantineCleanupClaim(UUID id, String quarantineObjectKey, UUID cleanupClaimToken) { }
}
