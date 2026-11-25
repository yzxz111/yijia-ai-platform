package com.yuzhihuihe.apiplatform.gateway.file;

import static com.yuzhihuihe.apiplatform.gateway.file.FileObjectStoreValidator.requireKey;
import static com.yuzhihuihe.apiplatform.gateway.file.FileObjectStoreValidator.requireListLimit;
import static com.yuzhihuihe.apiplatform.gateway.file.FileObjectStoreValidator.requireRange;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class S3FileObjectStore implements FileObjectStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(S3FileObjectStore.class);

    private final S3AsyncClient client;
    private final String bucket;

    public S3FileObjectStore(
            S3AsyncClient client,
            String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public Mono<Void> put(String objectKey, byte[] content, String mimeType) {
        requireKey(objectKey);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(mimeType)
                .contentLength((long) content.length)
                .build();
        return Mono.fromFuture(client.putObject(request, AsyncRequestBody.fromBytes(content)))
                .then()
                .onErrorMap(this::mapException);
    }

    @Override
    public Mono<Void> putFile(String objectKey, Path file, String mimeType) {
        requireKey(objectKey);
        if (file == null) {
            throw new IllegalArgumentException("invalid_file_object_path");
        }
        long contentLength;
        try {
            contentLength = Files.size(file);
        } catch (IOException exception) {
            return Mono.error(new FileStorageException());
        }
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(mimeType)
                // 不显式指定长度时，异步文件主体会以空/chunked PUT 发往 MinIO，
                // 对象永远不会落到 bucket 中（与 put(byte[]) 保持一致）。
                .contentLength(contentLength)
                .build();
        return Mono.fromFuture(client.putObject(request, AsyncRequestBody.fromFile(file)))
                .then()
                .onErrorMap(this::mapException);
    }

    @Override
    public Flux<ByteBuffer> getRange(String objectKey, long offset, long length) {
        requireKey(objectKey);
        requireRange(offset, length);
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .range(rangeHeader(offset, length))
                .build();
        return Mono.fromFuture(client.getObject(request, AsyncResponseTransformer.toPublisher()))
                .flatMapMany(Flux::from)
                .onErrorMap(this::mapException);
    }

    private static String rangeHeader(long offset, long length) {
        if (length <= 0) {
            return "bytes=" + offset + "-";
        }
        return "bytes=" + offset + "-" + (offset + length - 1);
    }

    @Override
    public Mono<byte[]> get(String objectKey, long maximumBytes) {
        requireKey(objectKey);
        BoundedObjectContentReader.requireLimit(maximumBytes);
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(objectKey).build();
        return Mono.fromFuture(client.getObject(request, AsyncResponseTransformer.toPublisher()))
                .flatMap(response -> BoundedObjectContentReader.collect(response, maximumBytes))
                .onErrorMap(this::mapException);
    }

    @Override
    public Mono<StoredObjectMetadata> head(String objectKey) {
        requireKey(objectKey);
        HeadObjectRequest request = HeadObjectRequest.builder().bucket(bucket).key(objectKey).build();
        return Mono.fromFuture(client.headObject(request))
                .map(response -> new StoredObjectMetadata(
                        objectKey,
                        response.contentLength(),
                        response.contentType(),
                        response.eTag(),
                        response.lastModified()))
                .onErrorMap(this::mapException);
    }

    @Override
    public Mono<Void> copy(String sourceObjectKey, String targetObjectKey) {
        requireKey(sourceObjectKey);
        requireKey(targetObjectKey);
        CopyObjectRequest request = CopyObjectRequest.builder()
                .copySource(bucket + "/" + sourceObjectKey)
                .destinationBucket(bucket)
                .destinationKey(targetObjectKey)
                .build();
        return Mono.fromFuture(client.copyObject(request)).then().onErrorMap(this::mapException);
    }

    @Override
    public Mono<StoredObjectPage> list(String prefix, String continuationToken, int limit) {
        requireKey(prefix);
        requireListLimit(limit);
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .continuationToken(continuationToken)
                .maxKeys(limit)
                .build();
        return Mono.fromFuture(client.listObjectsV2(request))
                .map(response -> new StoredObjectPage(
                        response.contents().stream()
                                .map(object -> new StoredObjectMetadata(
                                        object.key(), object.size(), null, object.eTag(), object.lastModified()))
                                .toList(),
                        response.nextContinuationToken()))
                .onErrorMap(this::mapException);
    }

    @Override
    public Mono<Void> delete(String objectKey) {
        requireKey(objectKey);
        DeleteObjectRequest request = DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build();
        return Mono.fromFuture(client.deleteObject(request)).then().onErrorMap(this::mapException);
    }

    private RuntimeException mapException(Throwable error) {
        Throwable actual = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        if (actual instanceof S3Exception s3Exception && s3Exception.statusCode() == 404) {
            // 写/复制操作上的 404 并不等于「对象缺失」；把供应商的细节保留在日志里，
            // 以便定位真正的原因（bucket、key、权限）。
            LOGGER.warn(
                    "s3_object_not_found bucket={} status={} awsError={} message={}",
                    bucket,
                    s3Exception.statusCode(),
                    s3Exception.awsErrorDetails() == null ? null : s3Exception.awsErrorDetails().errorCode(),
                    s3Exception.getMessage());
            return new FileObjectMissingException();
        }
        LOGGER.warn("s3_object_store_failure bucket={} error={}", bucket, actual.toString());
        if (actual instanceof FileObjectTooLargeException tooLarge) {
            return tooLarge;
        }
        return new FileStorageException();
    }
}
