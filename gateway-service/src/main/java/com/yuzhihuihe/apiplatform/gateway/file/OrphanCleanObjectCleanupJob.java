package com.yuzhihuihe.apiplatform.gateway.file;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class OrphanCleanObjectCleanupJob {

    private static final String CLEAN_PREFIX = "clean/";
    private static final Duration MINIMUM_AGE = Duration.ofHours(1);

    private final FileObjectRepository repository;
    private final FileObjectStore objectStore;
    private final Clock clock;
    private final int maxObjects;
    private final int pageSize;

    @Autowired
    public OrphanCleanObjectCleanupJob(
            FileObjectRepository repository,
            FileObjectStore objectStore,
            @Value("${storage.files.orphan-cleanup-max-objects:1000}") int maxObjects,
            @Value("${storage.files.orphan-cleanup-page-size:100}") int pageSize) {
        this(repository, objectStore, Clock.systemUTC(), maxObjects, pageSize);
    }

    OrphanCleanObjectCleanupJob(
            FileObjectRepository repository,
            FileObjectStore objectStore,
            Clock clock,
            int maxObjects,
            int pageSize) {
        this.repository = repository;
        this.objectStore = objectStore;
        this.clock = clock;
        this.maxObjects = Math.max(1, Math.min(maxObjects, 1000));
        this.pageSize = Math.max(1, Math.min(pageSize, 1000));
    }

    @Scheduled(
            fixedDelayString = "${storage.files.orphan-cleanup-delay-ms:300000}",
            initialDelayString = "${storage.files.orphan-cleanup-initial-delay-ms:300000}")
    public Mono<Void> scheduledCleanup() {
        return Mono.defer(this::cleanup);
    }

    public Mono<Void> cleanup() {
        Instant cutoff = clock.instant().minus(MINIMUM_AGE);
        return inventory(null, maxObjects, new HashSet<>())
                .concatMap(object -> deleteIfOrphan(object, cutoff))
                .then()
                .onErrorResume(DatabaseLookupFailure.class, ignored -> Mono.empty());
    }

    private Flux<StoredObjectMetadata> inventory(
            String continuationToken, int remaining, Set<String> seenTokens) {
        if (remaining <= 0) {
            return Flux.empty();
        }
        if (continuationToken != null && !seenTokens.add(continuationToken)) {
            return Flux.error(new IllegalStateException("repeated_object_continuation_token"));
        }
        int requestLimit = Math.min(pageSize, remaining);
        return objectStore.list(CLEAN_PREFIX, continuationToken, requestLimit)
                .flatMapMany(page -> {
                    int emitted = Math.min(page.objects().size(), remaining);
                    Flux<StoredObjectMetadata> current = Flux.fromIterable(page.objects()).take(emitted);
                    String next = page.nextContinuationToken();
                    if (emitted >= remaining || next == null || next.isBlank()) {
                        return current;
                    }
                    return current.concatWith(inventory(next, remaining - emitted, seenTokens));
                });
    }

    private Mono<Void> deleteIfOrphan(StoredObjectMetadata object, Instant cutoff) {
        if (!object.objectKey().startsWith(CLEAN_PREFIX)) {
            return Mono.error(new IllegalArgumentException("invalid_clean_object_key"));
        }
        if (object.lastModified() == null || object.lastModified().isAfter(cutoff)) {
            return Mono.empty();
        }
        return repository.existsLiveObjectKey(object.objectKey())
                .onErrorMap(DatabaseLookupFailure::new)
                .flatMap(referenced -> referenced
                        ? Mono.empty()
                        : objectStore.delete(object.objectKey()));
    }

    private static final class DatabaseLookupFailure extends RuntimeException {
        private DatabaseLookupFailure(Throwable cause) {
            super(cause);
        }
    }
}
