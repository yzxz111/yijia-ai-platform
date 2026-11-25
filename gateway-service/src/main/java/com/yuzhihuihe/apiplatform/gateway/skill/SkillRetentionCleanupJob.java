package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.file.FileObjectRepository;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Mono;

/**
 * Permanently removes expired Skill tombstones and their object-store content, and bounds download
 * log retention. Admin operation audit rows are intentionally untouched.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillRetentionCleanupJob {

    private final SkillRetentionRepository retention;
    private final SkillDownloadRepository downloads;
    private final FileObjectStore objectStore;
    private final FileObjectRepository fileObjects;
    private final SkillProperties properties;
    private final TransactionalOperator transactions;
    private final Clock clock;
    private final int batchSize;
    private final int maxDownloadBatches;

    @Autowired
    public SkillRetentionCleanupJob(
            SkillRetentionRepository retention,
            SkillDownloadRepository downloads,
            FileObjectStore objectStore,
            FileObjectRepository fileObjects,
            SkillProperties properties,
            TransactionalOperator transactions,
            @Value("${gateway.skills.cleanup-batch-size:500}") int batchSize,
            @Value("${gateway.skills.cleanup-max-download-batches:20}") int maxDownloadBatches) {
        this(
                retention,
                downloads,
                objectStore,
                fileObjects,
                properties,
                transactions,
                Clock.systemUTC(),
                batchSize,
                maxDownloadBatches);
    }

    SkillRetentionCleanupJob(
            SkillRetentionRepository retention,
            SkillDownloadRepository downloads,
            FileObjectStore objectStore,
            FileObjectRepository fileObjects,
            SkillProperties properties,
            TransactionalOperator transactions,
            Clock clock,
            int batchSize,
            int maxDownloadBatches) {
        this.retention = retention;
        this.downloads = downloads;
        this.objectStore = objectStore;
        this.fileObjects = fileObjects;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = Math.max(1, Math.min(batchSize, 1000));
        this.maxDownloadBatches = Math.max(1, Math.min(maxDownloadBatches, 100));
    }

    @Scheduled(
            fixedDelayString = "${gateway.skills.cleanup-delay-ms:3600000}",
            initialDelayString = "${gateway.skills.cleanup-initial-delay-ms:3600000}")
    public Mono<Void> scheduledCleanup() {
        return Mono.defer(this::cleanup);
    }

    public Mono<Void> cleanup() {
        Instant now = clock.instant();
        Instant logCutoff = now.minus(properties.getLogRetention());
        Instant deletedCutoff = now.minus(properties.getDeletedRetention());
        return downloads.deactivateExpiredIdempotencyKeys(now)
                .then(drainDownloadLogs(logCutoff, maxDownloadBatches))
                .thenMany(retention.purgeCandidates(deletedCutoff, now, batchSize))
                .concatMap(skillId -> purgeSkill(skillId, deletedCutoff, now))
                .then();
    }

    /**
     * Administrator-requested permanent deletion from the recycle bin. The same referential and
     * object-key safety checks as scheduled retention apply; active download sessions still block
     * removal so an in-flight client never loses its bytes midway through a transfer.
     */
    public Mono<Void> purgeNow(UUID skillId) {
        Instant now = clock.instant();
        return purgeSkill(skillId, now, now);
    }

    private Mono<Void> purgeSkill(UUID skillId, Instant deletedCutoff, Instant now) {
        // First atomically remove the tombstoned rows while retaining their keys in memory. Only
        // after commit do we test every key against all live tables and delete it. This ordering
        // cannot destroy bytes if the candidate changes concurrently, and also protects legacy
        // keys that may still be shared by a newer row.
        Mono<List<String>> purgeRows = retention.objectKeys(skillId)
                .distinct()
                .collectList()
                .flatMap(keys ->
                        retention.deleteMembers(skillId)
                                .then(retention.deleteVersions(skillId))
                                .then(retention.deleteNativeReleases(skillId))
                                .then(retention.deleteNativeSources(skillId))
                                .then(retention.deleteSkill(skillId, deletedCutoff, now))
                                .flatMap(deleted -> deleted == 1L
                                        ? Mono.just(keys)
                                        : Mono.error(new IllegalStateException(
                                                "skill_retention_candidate_changed"))));
        return transactions.transactional(purgeRows)
                .flatMapMany(reactor.core.publisher.Flux::fromIterable)
                .concatMap(key -> fileObjects.existsLiveObjectKey(key)
                        .flatMap(live -> Boolean.TRUE.equals(live)
                                ? Mono.empty()
                                : objectStore.delete(key)))
                .then();
    }

    /** Drains a bounded number of full batches so normal daily traffic cannot outgrow retention. */
    private Mono<Void> drainDownloadLogs(Instant cutoff, int remainingBatches) {
        return downloads.deleteBefore(cutoff, batchSize)
                .flatMap(deleted -> deleted != null
                                && deleted >= batchSize
                                && remainingBatches > 1
                        ? drainDownloadLogs(cutoff, remainingBatches - 1)
                        : Mono.empty())
                .then();
    }
}
