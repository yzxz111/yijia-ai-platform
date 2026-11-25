package com.yuzhihuihe.apiplatform.gateway.file;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Set;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface FileObjectStore {

    Mono<Void> put(String objectKey, byte[] content, String mimeType);

    /**
     * 把本地文件流式写入对象存储，而不把它整体载入堆内存。用于远大于
     * 内存 put 上限的载荷（技能包，最大 200 MB）。
     */
    Mono<Void> putFile(String objectKey, Path file, String mimeType);

    Mono<byte[]> get(String objectKey, long maximumBytes);

    /**
     * 以字节流方式读取对象的 {@code [offset, offset + length)} 区间。非正的
     * {@code length} 表示「从 {@code offset} 到对象末尾」。需要总大小的调用方
     * 必须先调用 {@link #head(String)}；本方法从不缓冲整个对象。
     */
    Flux<ByteBuffer> getRange(String objectKey, long offset, long length);

    Mono<StoredObjectMetadata> head(String objectKey);

    Mono<Void> copy(String sourceObjectKey, String targetObjectKey);

    Mono<StoredObjectPage> list(String prefix, String continuationToken, int limit);

    Mono<Void> delete(String objectKey);
}

class FileStorageException extends RuntimeException {

    FileStorageException() {
        super("file_storage_unavailable");
    }
}

class FileObjectTooLargeException extends RuntimeException {

    FileObjectTooLargeException() {
        super("file_object_too_large");
    }
}

final class FileObjectStoreValidator {

    private static final Set<String> ALLOWED_PREFIXES =
            Set.of("quarantine/", "clean/", "exports/", "test-runs/", "files/");

    private FileObjectStoreValidator() {
    }

    static void requireKey(String objectKey) {
        if (objectKey == null || ALLOWED_PREFIXES.stream().noneMatch(objectKey::startsWith)) {
            throw new IllegalArgumentException("invalid_file_object_key");
        }
    }

    static void requireListLimit(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("invalid_file_object_list_limit");
        }
    }

    static void requireRange(long offset, long length) {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("invalid_file_object_range");
        }
    }
}
