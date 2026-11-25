package com.yuzhihuihe.apiplatform.gateway.file;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.safety.ContentSafetyOrchestrator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.core.scheduler.Scheduler;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class FileSafetyScanJob {
    private final UploadSessionRepository repository;
    private final FileObjectStore objectStore;
    private final FileContentValidator validator;
    private final ContentSafetyOrchestrator safety;
    private final FileScanMetrics metrics;
    private final Clock clock;
    private final int batchSize;
    private final Duration claimLease;
    private final int maximumAttempts;
    private final Duration initialBackoff;
    private final Duration maximumBackoff;
    private final Duration operationTimeout;
    private final int concurrency;
    private final Scheduler fileSafetyScheduler;

    @Autowired
    public FileSafetyScanJob(
            UploadSessionRepository repository,
            FileObjectStore objectStore,
            FileContentValidator validator,
            ContentSafetyOrchestrator safety,
            FileScanMetrics metrics,
            @Value("${storage.files.scan.batch-size:20}") int batchSize,
            @Value("${storage.files.scan.claim-lease:PT5M}") Duration claimLease,
            @Value("${storage.files.scan.maximum-attempts:5}") int maximumAttempts,
            @Value("${storage.files.scan.initial-backoff:PT30S}") Duration initialBackoff,
            @Value("${storage.files.scan.maximum-backoff:PT15M}") Duration maximumBackoff,
            @Value("${storage.files.scan.operation-timeout:PT30S}") Duration operationTimeout,
            @Value("${storage.files.scan.concurrency:2}") int concurrency,
            @Qualifier("fileSafetyScheduler") Scheduler fileSafetyScheduler) {
        this(repository, objectStore, validator, safety, metrics, Clock.systemUTC(), batchSize,
                claimLease, maximumAttempts, initialBackoff, maximumBackoff, operationTimeout,
                concurrency, fileSafetyScheduler);
    }

    FileSafetyScanJob(
            UploadSessionRepository repository, FileObjectStore objectStore,
            FileContentValidator validator, ContentSafetyOrchestrator safety,
            FileScanMetrics metrics, Clock clock, int batchSize, Duration claimLease,
            int maximumAttempts, Duration initialBackoff, Duration maximumBackoff,
            Duration operationTimeout, int concurrency) {
        this(repository, objectStore, validator, safety, metrics, clock, batchSize,
                claimLease, maximumAttempts, initialBackoff, maximumBackoff,
                operationTimeout, concurrency, Schedulers.immediate());
    }

    FileSafetyScanJob(
            UploadSessionRepository repository, FileObjectStore objectStore,
            FileContentValidator validator, ContentSafetyOrchestrator safety,
            FileScanMetrics metrics, Clock clock, int batchSize, Duration claimLease,
            int maximumAttempts, Duration initialBackoff, Duration maximumBackoff,
            Duration operationTimeout, int concurrency, Scheduler fileSafetyScheduler) {
        this.repository = repository;
        this.objectStore = objectStore;
        this.validator = validator;
        this.safety = safety;
        this.metrics = metrics;
        this.clock = clock;
        this.batchSize = Math.max(1, batchSize);
        this.claimLease = claimLease;
        this.maximumAttempts = Math.max(1, maximumAttempts);
        this.initialBackoff = initialBackoff;
        this.maximumBackoff = maximumBackoff;
        this.operationTimeout = operationTimeout;
        this.concurrency = Math.max(1, concurrency);
        this.fileSafetyScheduler = fileSafetyScheduler;
    }

    @Scheduled(fixedDelayString = "${storage.files.scan.fixed-delay:PT5S}")
    public Mono<Void> scan() {
        return Mono.defer(this::runOnce);
    }

    Mono<Void> runOnce() {
        Instant now = clock.instant();
        return repository.claimForScan(batchSize, now, claimLease)
                .buffer(concurrency)
                .concatMap(batch -> Flux.fromIterable(batch)
                        .flatMap(claim -> process(claim, now), concurrency)
                        .then())
                .then();
    }

    private Mono<Void> process(UploadSessionRepository.ScanClaim claim, Instant now) {
        long started = System.nanoTime();
        UploadSessionRepository.UploadSession session = claim.session();
        return operation(
                        "get",
                        objectStore
                                .get(session.quarantineObjectKey(), session.sizeBytes())
                                .subscribeOn(fileSafetyScheduler))
                .flatMap(bytes -> validateAndScan(claim, bytes, now))
                .onErrorResume(error -> isInvalid(error)
                        ? reject(claim, errorCode(error), now)
                        : retry(claim, errorCode(error), now))
                .doFinally(ignored -> metrics.duration(
                        Duration.ofNanos(System.nanoTime() - started)));
    }

    private Mono<Void> validateAndScan(
            UploadSessionRepository.ScanClaim claim, byte[] bytes, Instant now) {
        UploadSessionRepository.UploadSession session = claim.session();
        return Mono.fromCallable(() -> validator.validateUpload(
                        session.originalFilename(), session.mimeType(), bytes, session.sizeBytes()))
                .subscribeOn(fileSafetyScheduler)
                .flatMap(validated -> {
                    if (!validated.sha256().equals(session.declaredSha256())) {
                        return reject(claim, "file_sha256_mismatch", now);
                    }
                    ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                            session.apiKeyId(), session.tenantId(), session.ownerUserId(), null, Set.of());
                    String requestId = "file-scan-" + session.id() + "-" + claim.claimToken();
                    return safety.inspectFile(context, bytes, session.originalFilename(),
                                    session.mimeType(), requestId)
                            .timeout(operationTimeout)
                            .flatMap(decision -> {
                                if (decision.allowed()) {
                                    return promote(claim, now);
                                }
                                return decision.retryable()
                                        ? retry(claim, decision.code(), now)
                                        : reject(claim, decision.code(), now);
                            });
                })
                .onErrorResume(error -> isInvalid(error)
                        ? reject(claim, errorCode(error), now)
                        : Mono.error(error));
    }

    private Mono<Void> promote(UploadSessionRepository.ScanClaim claim, Instant now) {
        UploadSessionRepository.UploadSession session = claim.session();
        String cleanKey = cleanKey(session.quarantineObjectKey(), claim.claimToken());
        return operation("copy", objectStore.copy(session.quarantineObjectKey(), cleanKey))
                .then(operation("head", objectStore.head(cleanKey)))
                .flatMap(metadata -> {
                    if (metadata.sizeBytes() != session.sizeBytes()) {
                        return deleteClean(cleanKey).then(Mono.error(
                                new IllegalStateException("clean_object_validation_failed")));
                    }
                    return repository.markReady(session.id(), claim.claimToken(), cleanKey, now);
                })
                .flatMap(marked -> {
                    if (!marked) {
                        metrics.outcome("fence_lost");
                        metrics.orphan("clean_fence_lost");
                        return deleteClean(cleanKey);
                    }
                    metrics.outcome("ready");
                    return operation("delete", objectStore.delete(session.quarantineObjectKey()))
                            .onErrorResume(error -> {
                                metrics.orphan("quarantine_cleanup_debt");
                                return Mono.empty();
                            });
                });
    }

    private Mono<Void> reject(UploadSessionRepository.ScanClaim claim, String code, Instant now) {
        UploadSessionRepository.UploadSession session = claim.session();
        return repository.markRejected(session.id(), claim.claimToken(), code, now)
                .flatMap(marked -> {
                    if (marked) {
                        metrics.outcome("rejected");
                    }
                    return marked
                        ? operation("delete", objectStore.delete(session.quarantineObjectKey()))
                                .onErrorResume(error -> {
                                    metrics.orphan("quarantine_cleanup_debt");
                                    return Mono.empty();
                                })
                        : Mono.empty();
                });
    }

    private Mono<Void> retry(UploadSessionRepository.ScanClaim claim, String code, Instant now) {
        long multiplier = 1L << Math.min(30, Math.max(0, claim.session().scanAttempts()));
        Duration delay = initialBackoff.multipliedBy(multiplier);
        if (delay.compareTo(maximumBackoff) > 0) {
            delay = maximumBackoff;
        }
        return repository.retryOrFail(claim.session().id(), claim.claimToken(), code,
                now.plus(delay), maximumAttempts, now)
                .doOnNext(marked -> {
                    if (marked) {
                        metrics.outcome(claim.session().scanAttempts() + 1 >= maximumAttempts
                                ? "failed" : "retry");
                    }
                }).then();
    }

    private <T> Mono<T> operation(String operation, Mono<T> action) {
        return action.timeout(operationTimeout)
                .doOnSuccess(ignored -> metrics.objectOperation(operation, "success"))
                .doOnError(error -> metrics.objectOperation(operation, "failed"));
    }

    private Mono<Void> deleteClean(String cleanKey) {
        return operation("delete", objectStore.delete(cleanKey))
                .onErrorResume(error -> {
                    metrics.orphan("clean_delete_failed");
                    return Mono.empty();
                });
    }

    private String cleanKey(String quarantineKey, java.util.UUID claimToken) {
        if (quarantineKey == null || !quarantineKey.startsWith("quarantine/")) {
            throw new IllegalArgumentException("invalid_quarantine_object_key");
        }
        return "clean/" + quarantineKey.substring("quarantine/".length())
                + "/claims/" + claimToken;
    }

    private boolean isInvalid(Throwable error) {
        return error instanceof ResponseStatusException
                || error instanceof IllegalArgumentException
                || error instanceof FileObjectTooLargeException;
    }

    private String errorCode(Throwable error) {
        if (error instanceof ResponseStatusException status && status.getReason() != null) {
            return status.getReason();
        }
        String message = error.getMessage();
        return message != null && message.matches("[a-z0-9_]+") ? message : "file_scan_unavailable";
    }

}
