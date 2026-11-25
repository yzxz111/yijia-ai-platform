package com.yuzhihuihe.apiplatform.gateway.file;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.safety.ContentSafetyOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class FileService {

    static final long FREE_MAX_BYTES = 5L * 1024 * 1024;
    static final long PAID_MAX_BYTES = 20L * 1024 * 1024;
    private static final Duration FILE_TTL = Duration.ofHours(24);
    private static final Set<String> PUBLIC_METADATA_STATUSES = Set.of(
            "PENDING_UPLOAD", "UPLOADED", "SCANNING", "READY", "REJECTED", "FAILED");

    private final FileObjectRepository repository;
    private final FileObjectStore objectStore;
    private final FileContentValidator validator;
    private final FileUploadMetadataValidator metadataValidator;
    private final UploadEntitlementService entitlementService;
    private final ContentSafetyOrchestrator safety;
    private final Clock clock;

    @Autowired
    public FileService(
            FileObjectRepository repository,
            FileObjectStore objectStore,
            FileContentValidator validator,
            FileUploadMetadataValidator metadataValidator,
            UploadEntitlementService entitlementService,
            ContentSafetyOrchestrator safety) {
        this(repository, objectStore, validator, metadataValidator,
                entitlementService, safety, Clock.systemUTC());
    }

    FileService(
            FileObjectRepository repository,
            FileObjectStore objectStore,
            FileContentValidator validator,
            FileUploadMetadataValidator metadataValidator,
            UploadEntitlementService entitlementService,
            ContentSafetyOrchestrator safety,
            Clock clock) {
        this.repository = repository;
        this.objectStore = objectStore;
        this.validator = validator;
        this.metadataValidator = metadataValidator;
        this.entitlementService = entitlementService;
        this.safety = safety;
        this.clock = clock;
    }

    public Mono<FileObjectRepository.FileObject> upload(
            ApiKeyService.ApiKeyContext context,
            String filename,
            String declaredMimeType,
            byte[] content) {
        return upload(context, filename, declaredMimeType, content,
                "req_" + UUID.randomUUID().toString().replace("-", ""));
    }

    public Mono<FileObjectRepository.FileObject> upload(
            ApiKeyService.ApiKeyContext context,
            String filename,
            String declaredMimeType,
            byte[] content,
            String requestId) {
        requireContext(context);
        return entitlementService.resolve(context)
                .flatMap(entitlement -> {
                    return Mono.fromCallable(() -> {
                            FileContentValidator.ValidatedContent validated =
                                    validator.validateUpload(filename, declaredMimeType, content,
                                            entitlement.maximumBytes());
                            FileUploadMetadataValidator.ValidatedUpload metadata = metadataValidator.validate(
                                    new FileUploadMetadataValidator.DeclaredUpload(
                                            filename, validated.sizeBytes(), validated.mimeType(),
                                            validated.sha256(), "assistants"),
                                    entitlement.maximumBytes());
                            return new ValidatedMultipart(metadata.sanitizedFilename(), validated);
                        })
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .flatMap(validated -> safety.inspectFile(context, content, validated.filename(),
                                validated.content().mimeType(), requestId)
                        .flatMap(decision -> {
                            if (!decision.allowed()) {
                                HttpStatus status = decision.retryable()
                                        ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNPROCESSABLE_ENTITY;
                                return Mono.error(new ResponseStatusException(status,
                                        decision.code() == null ? "content_safety_blocked" : decision.code()));
                            }
                            return Mono.just(validated);
                        }))
                .flatMap(validated -> {
                    Instant now = clock.instant();
                    UUID id = UUID.randomUUID();
                    FileObjectRepository.FileObject uploading = new FileObjectRepository.FileObject(
                            id,
                            "file_" + UUID.randomUUID().toString().replace("-", ""),
                            context.tenantId(),
                            context.userId(),
                            "clean/" + UUID.randomUUID(),
                            validated.filename(),
                            validated.content().sha256(),
                            validated.content().mimeType(),
                            validated.content().sizeBytes(),
                            "UPLOADING",
                            now.plus(FILE_TTL),
                            null,
                            now);
                    return repository.insert(uploading)
                            .then(objectStore.put(uploading.objectKey(), content,
                                    validated.content().mimeType()))
                            .then(repository.markReady(id))
                            .thenReturn(uploading.withStatus("READY"))
                            .onErrorResume(error -> repository.markFailed(id)
                                    .onErrorResume(ignored -> Mono.empty())
                                    .then(Mono.error(error)));
                });
    }

    public Mono<FileObjectRepository.FileObject> get(
            ApiKeyService.ApiKeyContext context, String externalFileId) {
        requireContext(context);
        return repository.find(externalFileId, context.tenantId(), context.userId())
                .filter(this::isVisible)
                .switchIfEmpty(notFound());
    }

    public Flux<FileObjectRepository.FileObject> list(ApiKeyService.ApiKeyContext context) {
        requireContext(context);
        return repository.list(context.tenantId(), context.userId()).filter(this::isVisible);
    }

    public Mono<Void> delete(ApiKeyService.ApiKeyContext context, String externalFileId) {
        requireContext(context);
        return repository.claimDelete(externalFileId, context.tenantId(), context.userId())
                .switchIfEmpty(notFound())
                .flatMap(file -> objectStore.delete(file.objectKey())
                        .onErrorResume(error -> repository.restoreReady(
                                        file.id(), file.deletionClaimToken())
                                .onErrorResume(ignored -> Mono.empty())
                                .then(Mono.error(error)))
                        .then(Mono.defer(() -> repository.markDeleted(
                                file.id(), file.deletionClaimToken(), clock.instant()))));
    }

    public Mono<FileObjectRepository.FileObject> requireUsable(
            ApiKeyService.ApiKeyContext context, String externalFileId) {
        requireContext(context);
        return repository.find(externalFileId, context.tenantId(), context.userId())
                .filter(this::isVisible)
                .switchIfEmpty(notFound())
                .flatMap(file -> isUsable(file)
                        ? Mono.just(file)
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "file_processing")));
    }

    private boolean isUsable(FileObjectRepository.FileObject file) {
        return "READY".equals(file.status()) && file.objectKey() != null
                && file.objectKey().startsWith("clean/");
    }

    private boolean isVisible(FileObjectRepository.FileObject file) {
        return file.deletedAt() == null && PUBLIC_METADATA_STATUSES.contains(file.status())
                && file.expiresAt() != null && file.expiresAt().isAfter(clock.instant());
    }

    private void requireContext(ApiKeyService.ApiKeyContext context) {
        if (context == null || context.apiKeyId() == null
                || context.tenantId() == null || context.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "api_key_required");
        }
    }

    private <T> Mono<T> notFound() {
        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "file_not_found"));
    }

    private record ValidatedMultipart(
            String filename, FileContentValidator.ValidatedContent content) { }
}
