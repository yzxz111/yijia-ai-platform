package com.yuzhihuihe.apiplatform.gateway.file;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class OrphanCleanObjectCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");

    @Test
    void paginatesCleanInventoryAndDeletesOnlyOldUnreferencedObjects() {
        FileObjectRepository repository = mock(FileObjectRepository.class);
        FileObjectStore store = mock(FileObjectStore.class);
        StoredObjectMetadata orphanOne = object("clean/orphan-one", NOW.minus(Duration.ofHours(2)));
        StoredObjectMetadata young = object("clean/young", NOW.minus(Duration.ofMinutes(59)));
        StoredObjectMetadata referenced = object("clean/referenced", NOW.minus(Duration.ofHours(3)));
        StoredObjectMetadata orphanTwo = object("clean/orphan-two", NOW.minus(Duration.ofHours(4)));
        when(store.list("clean/", null, 3))
                .thenReturn(Mono.just(new StoredObjectPage(List.of(orphanOne, young, referenced), "next")));
        when(store.list("clean/", "next", 3))
                .thenReturn(Mono.just(new StoredObjectPage(List.of(orphanTwo), null)));
        when(repository.existsLiveObjectKey("clean/orphan-one")).thenReturn(Mono.just(false));
        when(repository.existsLiveObjectKey("clean/referenced")).thenReturn(Mono.just(true));
        when(repository.existsLiveObjectKey("clean/orphan-two")).thenReturn(Mono.just(false));
        when(store.delete("clean/orphan-one")).thenReturn(Mono.empty());
        when(store.delete("clean/orphan-two")).thenReturn(Mono.empty());

        job(repository, store, 10, 3).cleanup().block(Duration.ofSeconds(1));

        verify(store).delete("clean/orphan-one");
        verify(store).delete("clean/orphan-two");
        verify(store, never()).delete("clean/young");
        verify(store, never()).delete("clean/referenced");
        verify(repository, never()).existsLiveObjectKey("clean/young");
    }

    @Test
    void deletesAnUnreferencedObjectExactlyOneHourOld() {
        FileObjectRepository repository = mock(FileObjectRepository.class);
        FileObjectStore store = mock(FileObjectStore.class);
        StoredObjectMetadata boundary = object("clean/exactly-one-hour",
                NOW.minus(Duration.ofHours(1)));
        when(store.list("clean/", null, 10))
                .thenReturn(Mono.just(new StoredObjectPage(List.of(boundary), null)));
        when(repository.existsLiveObjectKey(boundary.objectKey())).thenReturn(Mono.just(false));
        when(store.delete(boundary.objectKey())).thenReturn(Mono.empty());

        job(repository, store, 10, 10).cleanup().block(Duration.ofSeconds(1));

        verify(repository).existsLiveObjectKey(boundary.objectKey());
        verify(store).delete(boundary.objectKey());
    }

    @Test
    void stopsAtConfiguredInventoryLimitAcrossPages() {
        FileObjectRepository repository = mock(FileObjectRepository.class);
        FileObjectStore store = mock(FileObjectStore.class);
        StoredObjectMetadata first = object("clean/first", NOW.minus(Duration.ofHours(2)));
        StoredObjectMetadata second = object("clean/second", NOW.minus(Duration.ofHours(2)));
        StoredObjectMetadata third = object("clean/third", NOW.minus(Duration.ofHours(2)));
        when(store.list("clean/", null, 1))
                .thenReturn(Mono.just(new StoredObjectPage(List.of(first), "two")));
        when(store.list("clean/", "two", 1))
                .thenReturn(Mono.just(new StoredObjectPage(List.of(second), "three")));
        when(repository.existsLiveObjectKey("clean/first")).thenReturn(Mono.just(true));
        when(repository.existsLiveObjectKey("clean/second")).thenReturn(Mono.just(true));

        job(repository, store, 2, 1).cleanup().block(Duration.ofSeconds(1));

        verify(store, never()).list("clean/", "three", 1);
        verify(repository, never()).existsLiveObjectKey(third.objectKey());
    }

    @Test
    void databaseFailureStopsTheRunBeforeFurtherDeletes() {
        FileObjectRepository repository = mock(FileObjectRepository.class);
        FileObjectStore store = mock(FileObjectStore.class);
        StoredObjectMetadata failed = object("clean/database-failure", NOW.minus(Duration.ofHours(2)));
        StoredObjectMetadata later = object("clean/later", NOW.minus(Duration.ofHours(2)));
        when(store.list("clean/", null, 10))
                .thenReturn(Mono.just(new StoredObjectPage(List.of(failed, later), null)));
        when(repository.existsLiveObjectKey(failed.objectKey()))
                .thenReturn(Mono.error(new IllegalStateException("database_unavailable")));

        job(repository, store, 10, 10).cleanup().block(Duration.ofSeconds(1));

        verify(repository, never()).existsLiveObjectKey(later.objectKey());
        verify(store, never()).delete(later.objectKey());
    }

    @Test
    void rejectsAnInventoryKeyOutsideCleanPrefix() {
        FileObjectRepository repository = mock(FileObjectRepository.class);
        FileObjectStore store = mock(FileObjectStore.class);
        when(store.list("clean/", null, 10)).thenReturn(Mono.just(new StoredObjectPage(
                List.of(object("quarantine/not-clean", NOW.minus(Duration.ofHours(2)))), null)));

        assertThatThrownBy(() -> job(repository, store, 10, 10)
                        .cleanup().block(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_clean_object_key");

        verify(store, never()).delete("quarantine/not-clean");
    }

    private static OrphanCleanObjectCleanupJob job(
            FileObjectRepository repository, FileObjectStore store, int maxObjects, int pageSize) {
        return new OrphanCleanObjectCleanupJob(
                repository, store, Clock.fixed(NOW, ZoneOffset.UTC), maxObjects, pageSize);
    }

    private static StoredObjectMetadata object(String key, Instant lastModified) {
        return new StoredObjectMetadata(key, 10, "application/pdf", "etag", lastModified);
    }
}
