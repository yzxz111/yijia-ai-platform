package com.yuzhihuihe.apiplatform.gateway.file;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class QuarantineObjectCleanupJob {

    private final UploadSessionRepository repository;
    private final FileObjectStore objectStore;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease;

    @Autowired
    public QuarantineObjectCleanupJob(
            UploadSessionRepository repository,
            FileObjectStore objectStore,
            @Value("${storage.files.quarantine-cleanup-batch-size:100}") int batchSize,
            @Value("${storage.files.quarantine-cleanup-lease:PT5M}") Duration lease) {
        this(repository, objectStore, Clock.systemUTC(), batchSize, lease);
    }

    QuarantineObjectCleanupJob(
            UploadSessionRepository repository,
            FileObjectStore objectStore,
            Clock clock,
            int batchSize,
            Duration lease) {
        this.repository = repository;
        this.objectStore = objectStore;
        this.clock = clock;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
        this.lease = lease;
    }

    @Scheduled(
            fixedDelayString = "${storage.files.quarantine-cleanup-delay-ms:60000}",
            initialDelayString = "${storage.files.quarantine-cleanup-initial-delay-ms:60000}")
    public Mono<Void> scheduledCleanup() {
        return Mono.defer(this::cleanup);
    }

    public Mono<Void> cleanup() {
        var now = clock.instant();
        return repository.claimQuarantineCleanup(batchSize, now, lease)
                .concatMap(claim -> objectStore.delete(claim.quarantineObjectKey())
                        .then(Mono.defer(() -> repository.markQuarantineDeleted(
                                claim.id(), claim.cleanupClaimToken(), now)))
                        .onErrorResume(ignored -> Mono.empty()))
                .then();
    }
}
