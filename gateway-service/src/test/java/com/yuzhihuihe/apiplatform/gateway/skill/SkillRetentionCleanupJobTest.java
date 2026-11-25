package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.file.FileObjectRepository;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class SkillRetentionCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");

    @Test
    void purgesRowsAtTheRetentionBoundaryThenDeletesOnlyUnreferencedObjects() {
        SkillRetentionRepository retention = mock(SkillRetentionRepository.class);
        SkillDownloadRepository downloads = mock(SkillDownloadRepository.class);
        FileObjectStore store = mock(FileObjectStore.class);
        FileObjectRepository files = mock(FileObjectRepository.class);
        TransactionalOperator transactions = passThroughTransactions();
        SkillProperties properties = new SkillProperties();
        UUID skillId = UUID.randomUUID();
        Instant deletedCutoff = NOW.minus(Duration.ofDays(30));
        Instant logCutoff = NOW.minus(Duration.ofDays(90));
        when(downloads.deactivateExpiredIdempotencyKeys(NOW)).thenReturn(Mono.just(0L));
        when(downloads.deleteBefore(logCutoff, 500)).thenReturn(Mono.just(0L));
        when(retention.purgeCandidates(deletedCutoff, NOW, 500))
                .thenReturn(Flux.just(skillId));
        when(retention.objectKeys(skillId))
                .thenReturn(Flux.just("clean/shared", "clean/orphan", "clean/orphan"));
        when(retention.deleteMembers(skillId)).thenReturn(Mono.just(2L));
        when(retention.deleteVersions(skillId)).thenReturn(Mono.just(1L));
        // 原生化（ClawHub）之后清理流程还会删原生来源与发布记录；这两步没有桩会让 mock 返回
        // null，Mono.then(null) 直接 NPE —— 之前这条用例就是这么红的。
        when(retention.deleteNativeReleases(skillId)).thenReturn(Mono.just(0L));
        when(retention.deleteNativeSources(skillId)).thenReturn(Mono.just(0L));
        when(retention.deleteSkill(skillId, deletedCutoff, NOW)).thenReturn(Mono.just(1L));
        when(files.existsLiveObjectKey("clean/shared")).thenReturn(Mono.just(true));
        when(files.existsLiveObjectKey("clean/orphan")).thenReturn(Mono.just(false));
        when(store.delete("clean/orphan")).thenReturn(Mono.empty());

        job(retention, downloads, store, files, properties, transactions, 500, 20)
                .cleanup().block(Duration.ofSeconds(1));

        verify(store, never()).delete("clean/shared");
        verify(store).delete("clean/orphan");
        verify(retention).deleteSkill(skillId, deletedCutoff, NOW);
    }

    @Test
    void drainsMultipleFullDownloadLogBatchesWithinTheBoundedRun() {
        SkillRetentionRepository retention = mock(SkillRetentionRepository.class);
        SkillDownloadRepository downloads = mock(SkillDownloadRepository.class);
        FileObjectStore store = mock(FileObjectStore.class);
        FileObjectRepository files = mock(FileObjectRepository.class);
        SkillProperties properties = new SkillProperties();
        Instant cutoff = NOW.minus(Duration.ofDays(90));
        when(downloads.deactivateExpiredIdempotencyKeys(NOW)).thenReturn(Mono.just(0L));
        when(downloads.deleteBefore(cutoff, 500))
                .thenReturn(Mono.just(500L), Mono.just(500L), Mono.just(7L));
        when(retention.purgeCandidates(
                NOW.minus(Duration.ofDays(30)), NOW, 500)).thenReturn(Flux.empty());

        job(retention, downloads, store, files, properties, passThroughTransactions(), 500, 20)
                .cleanup().block(Duration.ofSeconds(1));

        verify(downloads, times(3)).deleteBefore(cutoff, 500);
    }

    private static SkillRetentionCleanupJob job(
            SkillRetentionRepository retention,
            SkillDownloadRepository downloads,
            FileObjectStore store,
            FileObjectRepository files,
            SkillProperties properties,
            TransactionalOperator transactions,
            int batchSize,
            int maxDownloadBatches) {
        return new SkillRetentionCleanupJob(
                retention,
                downloads,
                store,
                files,
                properties,
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC),
                batchSize,
                maxDownloadBatches);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TransactionalOperator passThroughTransactions() {
        TransactionalOperator transactions = mock(TransactionalOperator.class);
        when(transactions.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return transactions;
    }
}
