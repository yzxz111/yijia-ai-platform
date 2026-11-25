package com.yuzhihuihe.apiplatform.gateway.file;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class UploadSessionService {

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final UploadEntitlementService entitlementService;
    private final FileUploadMetadataValidator metadataValidator;
    private final UploadSessionRepository repository;
    private final FileObjectStore objectStore;
    private final UploadFormSigner signer;
    private final FileStorageProperties properties;
    private final FileUploadMetrics metrics;
    private final Clock clock;

    @Autowired
    public UploadSessionService(
            UploadEntitlementService entitlementService,
            FileUploadMetadataValidator metadataValidator,
            UploadSessionRepository repository,
            FileObjectStore objectStore,
            ObjectProvider<UploadFormSigner> signerProvider,
            FileStorageProperties properties,
            FileUploadMetrics metrics) {
        this(entitlementService, metadataValidator, repository, objectStore,
                signerProvider.getIfAvailable(), properties, metrics, Clock.systemUTC());
    }

    UploadSessionService(
            UploadEntitlementService entitlementService,
            FileUploadMetadataValidator metadataValidator,
            UploadSessionRepository repository,
            FileObjectStore objectStore,
            UploadFormSigner signer,
            FileStorageProperties properties,
            FileUploadMetrics metrics,
            Clock clock) {
        this.entitlementService = entitlementService;
        this.metadataValidator = metadataValidator;
        this.repository = repository;
        this.objectStore = objectStore;
        this.signer = signer;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    public Mono<UploadSessionResult> create(
            ApiKeyService.ApiKeyContext context,
            CreateUploadSessionCommand command,
            String idempotencyKey) {
        return Mono.defer(() -> {
                    if (!properties.isDirectUploadEnabled() || signer == null) {
                        return Mono.error(error(HttpStatus.NOT_IMPLEMENTED, "direct_upload_disabled"));
                    }
                    String normalizedKey = requireIdempotencyKey(idempotencyKey);
                    if (context == null) {
                        return Mono.error(error(HttpStatus.UNAUTHORIZED, "invalid_api_key"));
                    }
                    return entitlementService.resolve(context)
                            .map(entitlement -> metadataValidator.validate(
                                    declared(command), entitlement.maximumBytes()))
                            .flatMap(validated -> persistAndSign(context, validated, normalizedKey));
                })
                .doOnSuccess(result -> metrics.record(result.replayed() ? "replayed" : "created"))
                .doOnError(error -> metrics.record(isRejected(error) ? "rejected" : "failed"));
    }

    public Mono<FileObjectRepository.FileObject> complete(
            ApiKeyService.ApiKeyContext context, String uploadSessionId, String idempotencyKey) {
        return Mono.defer(() -> {
                    requireIdempotencyKey(idempotencyKey);
                    if (context == null || context.apiKeyId() == null
                            || context.tenantId() == null || context.userId() == null) {
                        return Mono.error(error(HttpStatus.UNAUTHORIZED, "invalid_api_key"));
                    }
                    if (uploadSessionId == null || uploadSessionId.isBlank()) {
                        return Mono.error(error(HttpStatus.NOT_FOUND, "upload_session_not_found"));
                    }
                    return repository.findOwned(
                                    uploadSessionId, context.apiKeyId(), context.tenantId(), context.userId())
                            .switchIfEmpty(Mono.error(error(HttpStatus.NOT_FOUND, "upload_session_not_found")))
                            .flatMap(session -> completeOwned(context, session));
                })
                .doOnSuccess(completion -> metrics.recordComplete(completion.outcome()))
                .doOnError(error -> metrics.recordComplete(completeErrorOutcome(error)))
                .map(Completion::file);
    }

    private Mono<Completion> completeOwned(
            ApiKeyService.ApiKeyContext context, UploadSessionRepository.UploadSession session) {
        if (!"PENDING_UPLOAD".equals(session.status())) {
            return Mono.just(new Completion(file(session), "replayed"));
        }
        if (session.uploadExpiresAt() == null || !session.uploadExpiresAt().isAfter(clock.instant())) {
            return Mono.error(error(HttpStatus.GONE, "upload_session_expired"));
        }
        String quarantineKey = session.quarantineObjectKey();
        if (quarantineKey == null || !quarantineKey.startsWith("quarantine/")) {
            return Mono.error(error(HttpStatus.SERVICE_UNAVAILABLE, "file_storage_unavailable"));
        }
        return objectStore.head(quarantineKey)
                .onErrorMap(FileObjectMissingException.class,
                        missing -> error(HttpStatus.UNPROCESSABLE_ENTITY, "upload_object_missing"))
                .onErrorMap(FileStorageException.class,
                        storage -> error(HttpStatus.SERVICE_UNAVAILABLE, "file_storage_unavailable"))
                .flatMap(metadata -> {
                    if (metadata.sizeBytes() != session.sizeBytes()
                            || !normalizeMime(metadata.contentType()).equals(normalizeMime(session.mimeType()))) {
                        return repository.rejectPending(session.id(), "upload_object_mismatch", clock.instant())
                                .then(Mono.error(error(
                                        HttpStatus.UNPROCESSABLE_ENTITY, "upload_object_mismatch")));
                    }
                    Instant now = clock.instant();
                    return repository.markUploaded(
                                    session.id(), context.apiKeyId(), context.tenantId(), context.userId(),
                                    sanitizeEtag(metadata.eTag()), now)
                            .map(updated -> new Completion(file(updated), "accepted"))
                            .switchIfEmpty(repository.findOwned(
                                            session.uploadSessionId(), context.apiKeyId(),
                                            context.tenantId(), context.userId())
                                    .switchIfEmpty(Mono.error(error(
                                            HttpStatus.SERVICE_UNAVAILABLE, "upload_session_unavailable")))
                                    .map(current -> new Completion(file(current), "replayed")));
                });
    }

    private static FileObjectRepository.FileObject file(UploadSessionRepository.UploadSession session) {
        return new FileObjectRepository.FileObject(
                session.id(), session.externalFileId(), session.tenantId(), session.ownerUserId(),
                session.objectKey(), session.originalFilename(), session.declaredSha256(),
                session.mimeType(), session.sizeBytes(), session.status(), session.expiresAt(),
                null, session.createdAt());
    }

    private static String normalizeMime(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        int separator = mimeType.indexOf(';');
        return (separator < 0 ? mimeType : mimeType.substring(0, separator))
                .strip().toLowerCase(Locale.ROOT);
    }

    private static String sanitizeEtag(String eTag) {
        if (eTag == null) {
            return "";
        }
        String sanitized = eTag.replaceAll("[\\p{Cntrl}]", "").strip();
        return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 512);
    }

    private static String completeErrorOutcome(Throwable error) {
        if (error instanceof ResponseStatusException response) {
            if ("upload_object_missing".equals(response.getReason())) {
                return "missing";
            }
            if ("upload_object_mismatch".equals(response.getReason())) {
                return "mismatch";
            }
        }
        return "failed";
    }

    private Mono<UploadSessionResult> persistAndSign(
            ApiKeyService.ApiKeyContext context,
            FileUploadMetadataValidator.ValidatedUpload upload,
            String idempotencyKey) {
        Instant now = clock.instant();
        UUID draftId = UUID.randomUUID();
        String fileId = externalId("file_");
        String objectKey = "quarantine/" + context.tenantId() + "/" + context.userId() + "/"
                + LocalDate.ofInstant(now, ZoneOffset.UTC).format(DATE_PATH) + "/"
                + fileId + "/" + compactUuid();
        UploadSessionRepository.UploadSession draft = new UploadSessionRepository.UploadSession(
                draftId,
                fileId,
                context.apiKeyId(),
                context.tenantId(),
                context.userId(),
                externalId("upl_"),
                idempotencyKey,
                objectKey,
                objectKey,
                upload.sanitizedFilename(),
                upload.sha256(),
                upload.mimeType(),
                upload.bytes(),
                "PENDING_UPLOAD",
                now.plus(properties.getSignedUploadTtl()),
                now.plus(properties.getObjectTtl()),
                null,
                null,
                null,
                0,
                null,
                null,
                now,
                now);
        return repository.createOrFind(draft).switchIfEmpty(Mono.error(
                        error(HttpStatus.SERVICE_UNAVAILABLE, "upload_session_unavailable")))
                .flatMap(persisted -> {
                    if (!persisted.uploadExpiresAt().isAfter(clock.instant())) {
                        return Mono.error(error(HttpStatus.GONE, "upload_session_expired"));
                    }
                    boolean replayed = !persisted.id().equals(draftId);
                    return signer.sign(
                                    persisted.objectKey(), persisted.mimeType(), persisted.sizeBytes(),
                                    persisted.uploadExpiresAt())
                            .map(form -> new UploadSessionResult(
                                    persisted.externalFileId(), persisted.uploadSessionId(),
                                    "pending_upload", form, persisted.uploadExpiresAt(),
                                    persisted.expiresAt(), replayed));
                });
    }

    private static FileUploadMetadataValidator.DeclaredUpload declared(
            CreateUploadSessionCommand command) {
        if (command == null) {
            return null;
        }
        return new FileUploadMetadataValidator.DeclaredUpload(
                command.filename(), command.bytes(), command.mimeType(), command.sha256(),
                command.purpose());
    }

    private static String requireIdempotencyKey(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException();
            }
            return value.toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException error) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_idempotency_key");
        }
    }

    private static boolean isRejected(Throwable error) {
        if (!(error instanceof ResponseStatusException response)) {
            return false;
        }
        return response.getStatusCode().is4xxClientError()
                || "direct_upload_disabled".equals(response.getReason());
    }

    private static String externalId(String prefix) {
        return prefix + compactUuid();
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static ResponseStatusException error(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }

    public record CreateUploadSessionCommand(
            String filename,
            long bytes,
            @JsonProperty("mime_type") String mimeType,
            String sha256,
            String purpose) { }

    public record UploadSessionResult(
            @JsonProperty("file_id") String fileId,
            @JsonProperty("upload_session_id") String uploadSessionId,
            String status,
            UploadFormSigner.SignedUploadForm upload,
            @JsonProperty("upload_expires_at") Instant uploadExpiresAt,
            @JsonProperty("file_expires_at") Instant fileExpiresAt,
            boolean replayed) { }

    private record Completion(FileObjectRepository.FileObject file, String outcome) { }
}
