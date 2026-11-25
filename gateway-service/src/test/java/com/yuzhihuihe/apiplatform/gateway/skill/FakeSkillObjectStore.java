package com.yuzhihuihe.apiplatform.gateway.skill;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.yuzhihuihe.apiplatform.gateway.file.FileObjectMissingException;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import com.yuzhihuihe.apiplatform.gateway.file.StoredObjectMetadata;
import com.yuzhihuihe.apiplatform.gateway.file.StoredObjectPage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * In-memory {@link FileObjectStore} for the full-stack skill tests: no MinIO/OSS is available inside
 * CI, but the ingest pipeline, the promotion and the range streaming must still be exercised for
 * real (including {@code head} sizes and byte-exact ranges).
 */
public class FakeSkillObjectStore implements FileObjectStore {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Set<String> delayNextCopyHead = ConcurrentHashMap.newKeySet();
    private final Set<String> delayNextCopyRangeRead = ConcurrentHashMap.newKeySet();
    private final Set<String> delayFirstPostCopyHead = ConcurrentHashMap.newKeySet();
    private final Set<String> delayFirstPostCopyRangeRead = ConcurrentHashMap.newKeySet();

    public Map<String, byte[]> objects() {
        return objects;
    }

    public byte[] content(String objectKey) {
        return objects.get(objectKey);
    }

    public String singleKeyStartingWith(String prefix) {
        return objects.keySet().stream().filter(key -> key.startsWith(prefix)).findFirst().orElse(null);
    }

    public void seed(String objectKey, byte[] content) {
        objects.put(objectKey, content.clone());
    }

    public void delayFirstPostCopyReads(String objectKey) {
        delayNextCopyHead.add(objectKey);
        delayNextCopyRangeRead.add(objectKey);
    }

    @Override
    public Mono<Void> put(String objectKey, byte[] content, String mimeType) {
        return Mono.fromRunnable(() -> seed(objectKey, content)).then();
    }

    @Override
    public Mono<Void> putFile(String objectKey, Path file, String mimeType) {
        return Mono.fromCallable(() -> Files.readAllBytes(file))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(content -> seed(objectKey, content))
                .then();
    }

    @Override
    public Mono<byte[]> get(String objectKey, long maximumBytes) {
        return Mono.fromCallable(() -> {
            byte[] content = objects.get(objectKey);
            if (content == null) {
                throw new FileObjectMissingException();
            }
            if (content.length > maximumBytes) {
                throw new IllegalStateException("file_object_too_large");
            }
            return content.clone();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<ByteBuffer> getRange(String objectKey, long offset, long length) {
        return Mono.fromCallable(() -> {
                    if (delayFirstPostCopyRangeRead.remove(objectKey)) {
                        throw new FileObjectMissingException();
                    }
                    byte[] content = objects.get(objectKey);
                    if (content == null) {
                        throw new FileObjectMissingException();
                    }
                    long from = Math.min(offset, content.length);
                    long to = length <= 0
                            ? content.length
                            : Math.min(content.length, from + length);
                    byte[] slice = new byte[(int) (to - from)];
                    System.arraycopy(content, (int) from, slice, 0, slice.length);
                    return slice;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(slice -> {
                    int block = 16;
                    java.util.List<ByteBuffer> chunks = new java.util.ArrayList<>();
                    for (int index = 0; index < slice.length; index += block) {
                        chunks.add(ByteBuffer.wrap(
                                slice, index, Math.min(block, slice.length - index)));
                    }
                    return Flux.fromIterable(chunks);
                });
    }

    @Override
    public Mono<StoredObjectMetadata> head(String objectKey) {
        return Mono.fromCallable(() -> {
            if (delayFirstPostCopyHead.remove(objectKey)) {
                throw new FileObjectMissingException();
            }
            byte[] content = objects.get(objectKey);
            if (content == null) {
                throw new FileObjectMissingException();
            }
            return new StoredObjectMetadata(
                    objectKey, content.length, "application/zip", "\"etag\"", Instant.now());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> copy(String sourceObjectKey, String targetObjectKey) {
        return Mono.fromRunnable(() -> {
            byte[] content = objects.get(sourceObjectKey);
            if (content == null) {
                throw new FileObjectMissingException();
            }
            seed(targetObjectKey, content);
            // S3-compatible storage can briefly return 404 for a just-copied object.
            // A test can fail one HEAD and one range read after the next copy.
            if (delayNextCopyHead.remove(targetObjectKey)) {
                delayFirstPostCopyHead.add(targetObjectKey);
            }
            if (delayNextCopyRangeRead.remove(targetObjectKey)) {
                delayFirstPostCopyRangeRead.add(targetObjectKey);
            }
        }).then();
    }

    @Override
    public Mono<StoredObjectPage> list(String prefix, String continuationToken, int limit) {
        java.util.List<StoredObjectMetadata> page = objects.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .limit(limit)
                .map(entry -> new StoredObjectMetadata(
                        entry.getKey(), entry.getValue().length, null, "\"etag\"", Instant.now()))
                .toList();
        return Mono.just(new StoredObjectPage(page, null));
    }

    @Override
    public Mono<Void> delete(String objectKey) {
        return Mono.fromRunnable(() -> objects.remove(objectKey)).then();
    }
}

