package com.yuzhihuihe.apiplatform.gateway.file;

import java.time.Clock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ExpiredFileCleanupJob {

    private final FileObjectRepository repository;
    private final FileObjectStore objectStore;
    private final Clock clock;
    private final int batchSize;
    private final Counter deletedCounter;
    private final Counter failureCounter;

    @Autowired
    public ExpiredFileCleanupJob(
            FileObjectRepository repository,
            FileObjectStore objectStore,
            MeterRegistry meterRegistry,
            @Value("${storage.files.cleanup-batch-size:100}") int batchSize) {
        this(repository, objectStore, Clock.systemUTC(), meterRegistry, batchSize);
    }

    ExpiredFileCleanupJob(
            FileObjectRepository repository,
            FileObjectStore objectStore,
            Clock clock,
            MeterRegistry meterRegistry,
            int batchSize) {
        this.repository = repository;
        this.objectStore = objectStore;
        this.clock = clock;
        this.batchSize = batchSize;
        this.deletedCounter = meterRegistry.counter(
                "gateway.file.cleanup", "outcome", "deleted");
        this.failureCounter = meterRegistry.counter(
                "gateway.file.cleanup", "outcome", "failure");
    }

    @Scheduled(
            fixedDelayString = "${storage.files.cleanup-delay-ms:60000}",
            initialDelayString = "${storage.files.cleanup-initial-delay-ms:60000}")
    public Mono<Void> scheduledCleanup() {
        return Mono.defer(this::cleanup);
    }

    public Mono<Void> cleanup() {
        return repository.claimExpired(batchSize)
                .concatMap(file -> objectStore.delete(file.objectKey())
                        .thenReturn(true)
                        .doOnError(error -> failureCounter.increment())
                        .onErrorReturn(false)
                        .flatMap(deleted -> deleted
                                ? repository.markDeleted(
                                        file.id(), file.deletionClaimToken(), clock.instant())
                                        .then(Mono.fromRunnable(deletedCounter::increment))
                                        .doOnError(error -> failureCounter.increment())
                                        .onErrorResume(error -> Mono.empty())
                                : Mono.empty()))
                .then();
    }
}
