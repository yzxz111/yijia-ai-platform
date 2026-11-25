package com.yuzhihuihe.apiplatform.gateway.file;

import java.time.Instant;
import java.util.UUID;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface FileObjectRepository {

    Mono<PackageEntitlement> packageEntitlement(
            UUID tenantId, UUID userId, UUID packageInstanceId);

    Mono<Void> insert(FileObject file);

    Mono<Void> markReady(UUID id);

    Mono<Void> markFailed(UUID id);

    Mono<FileObject> find(String externalFileId, UUID tenantId, UUID ownerUserId);

    /**
     * 按外部文件 ID 读一行**可对外交付**的图片，**不带归属过滤**。
     *
     * <p>这是唯一一条不按 tenant+user 收窄的文件读取，因此它的调用者必须已经
     * 用别的方式证明了授权——目前的唯一调用者是公网匿名读图端点，它的授权
     * 来自 URL 里那个由我们签发的 {@code (fileId, exp)} 签名。归属校验发生在
     * **签发**那一侧（{@code FileService.requireUsable}）。
     *
     * <p>谓词本身也再收窄一次：只有 {@code READY}、未删除、未过期、
     * 且对象键落在 {@code clean/} 下的行才会被返回。一个还没过安全扫描的上传物
     * 不该因为「有人拿到了它的 id」就被发到公网。
     */
    Mono<FileObject> findReadyForRead(String externalFileId);

    Flux<FileObject> list(UUID tenantId, UUID ownerUserId);

    Mono<FileObject> claimDelete(String externalFileId, UUID tenantId, UUID ownerUserId);

    Flux<FileObject> claimExpired(int limit);

    Mono<Boolean> existsLiveObjectKey(String objectKey);

    Mono<Void> markDeleted(UUID id, UUID deletionClaimToken, Instant deletedAt);

    Mono<Void> restoreReady(UUID id, UUID deletionClaimToken);

    record PackageEntitlement(String packageCode, long maximumBytes) {
    }

    record FileObject(
            UUID id,
            String externalFileId,
            UUID tenantId,
            UUID ownerUserId,
            String objectKey,
            String originalFilename,
            String sha256,
            String mimeType,
            long sizeBytes,
            String status,
            Instant expiresAt,
            Instant deletedAt,
            Instant createdAt,
            UUID deletionClaimToken) {

        public FileObject(
                UUID id,
                String externalFileId,
                UUID tenantId,
                UUID ownerUserId,
                String objectKey,
                String originalFilename,
                String sha256,
                String mimeType,
                long sizeBytes,
                String status,
                Instant expiresAt,
                Instant deletedAt,
                Instant createdAt) {
            this(id, externalFileId, tenantId, ownerUserId, objectKey, originalFilename,
                    sha256, mimeType, sizeBytes, status, expiresAt, deletedAt, createdAt, null);
        }

        public FileObject withStatus(String nextStatus) {
            return new FileObject(
                    id, externalFileId, tenantId, ownerUserId, objectKey, originalFilename,
                    sha256, mimeType, sizeBytes, nextStatus, expiresAt, deletedAt, createdAt,
                    deletionClaimToken);
        }

        public FileObject withDeletionClaimToken(UUID nextClaimToken) {
            return new FileObject(
                    id, externalFileId, tenantId, ownerUserId, objectKey, originalFilename,
                    sha256, mimeType, sizeBytes, status, expiresAt, deletedAt, createdAt,
                    nextClaimToken);
        }
    }
}
