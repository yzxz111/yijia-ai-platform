package com.yuzhihuihe.apiplatform.gateway.file;

import static java.nio.charset.StandardCharsets.UTF_8;
import static com.yuzhihuihe.apiplatform.gateway.file.FileObjectStoreValidator.requireKey;
import static com.yuzhihuihe.apiplatform.gateway.file.FileObjectStoreValidator.requireListLimit;
import static com.yuzhihuihe.apiplatform.gateway.file.FileObjectStoreValidator.requireRange;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterators;
import java.util.concurrent.Callable;
import java.util.stream.StreamSupport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.MatchMode;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PolicyConditions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class AliyunOssFileObjectStore implements FileObjectStore, UploadFormSigner, AutoCloseable {

    private static final int BLOCK_BYTES = 8192;

    private final OSS backendClient;
    private final OSS publicClient;
    private final CredentialsProvider credentialsProvider;
    private final String bucket;
    private final URI uploadUrl;

    public AliyunOssFileObjectStore(
            OSS backendClient,
            OSS publicClient,
            CredentialsProvider credentialsProvider,
            FileStorageProperties properties) {
        this.backendClient = backendClient;
        this.publicClient = publicClient;
        this.credentialsProvider = credentialsProvider;
        this.bucket = properties.getBucket();
        this.uploadUrl = bucketUrl(properties.getPublicEndpoint(), bucket);
    }

    @Override
    public Mono<Void> put(String objectKey, byte[] content, String mimeType) {
        requireKey(objectKey);
        return blocking(() -> {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(content.length);
            metadata.setContentType(mimeType);
            metadata.setObjectAcl(CannedAccessControlList.Private);
            metadata.setServerSideEncryption(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
            try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
                backendClient.putObject(bucket, objectKey, input, metadata);
            }
            return null;
        }).then();
    }

    @Override
    public Mono<Void> putFile(String objectKey, Path file, String mimeType) {
        requireKey(objectKey);
        if (file == null) {
            throw new IllegalArgumentException("invalid_file_object_path");
        }
        return blocking(() -> {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(Files.size(file));
            metadata.setContentType(mimeType);
            metadata.setObjectAcl(CannedAccessControlList.Private);
            metadata.setServerSideEncryption(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
            backendClient.putObject(bucket, objectKey, file.toFile(), metadata);
            return null;
        }).then();
    }

    @Override
    public Flux<ByteBuffer> getRange(String objectKey, long offset, long length) {
        requireKey(objectKey);
        requireRange(offset, length);
        GetObjectRequest request = new GetObjectRequest(bucket, objectKey);
        // 负数结束偏移会让 SDK 发送 "bytes=<start>-"（RangeSpec.START_TO）。
        request.setRange(offset, length <= 0 ? -1L : offset + length - 1);
        return Mono.fromCallable(() -> backendClient.getObject(request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(object -> Flux.using(
                        () -> object,
                        AliyunOssFileObjectStore::content,
                        AliyunOssFileObjectStore::closeQuietly))
                .onErrorMap(this::mapException);
    }

    /**
     * 每个下游请求只拉取一个 8 KiB 块，因此 200 MB 的安装包绝不会被整个缓冲到堆内存中。
     */
    private static Flux<ByteBuffer> content(OSSObject object) {
        InputStream input = object.getObjectContent();
        Iterator<ByteBuffer> blocks = new Iterator<>() {
            private ByteBuffer next;
            private boolean fetched;
            private boolean exhausted;

            @Override
            public boolean hasNext() {
                fetch();
                return next != null;
            }

            @Override
            public ByteBuffer next() {
                fetch();
                if (next == null) {
                    throw new NoSuchElementException();
                }
                ByteBuffer value = next;
                next = null;
                fetched = false;
                return value;
            }

            private void fetch() {
                if (fetched || exhausted) {
                    return;
                }
                fetched = true;
                byte[] buffer = new byte[BLOCK_BYTES];
                int total = 0;
                try {
                    while (total < buffer.length) {
                        int count = input.read(buffer, total, buffer.length - total);
                        if (count < 0) {
                            break;
                        }
                        total += count;
                    }
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
                if (total <= 0) {
                    exhausted = true;
                    return;
                }
                next = ByteBuffer.wrap(buffer, 0, total);
            }
        };
        return Flux.fromStream(
                        StreamSupport.stream(Spliterators.spliteratorUnknownSize(blocks, 0), false))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static void closeQuietly(OSSObject object) {
        try {
            object.close();
        } catch (IOException | RuntimeException ignored) {
            // 关闭客户端已经放弃的流，绝不能掩盖真正的错误。
        }
    }

    @Override
    public Mono<byte[]> get(String objectKey, long maximumBytes) {
        requireKey(objectKey);
        BoundedObjectContentReader.requireLimit(maximumBytes);
        return blocking(() -> {
            try (OSSObject object = backendClient.getObject(bucket, objectKey)) {
                return BoundedObjectContentReader.read(object.getObjectContent(), maximumBytes);
            }
        });
    }

    @Override
    public Mono<StoredObjectMetadata> head(String objectKey) {
        requireKey(objectKey);
        return blocking(() -> metadata(objectKey, backendClient.headObject(bucket, objectKey)));
    }

    @Override
    public Mono<Void> copy(String sourceObjectKey, String targetObjectKey) {
        requireKey(sourceObjectKey);
        requireKey(targetObjectKey);
        return blocking(() -> {
            backendClient.copyObject(bucket, sourceObjectKey, bucket, targetObjectKey);
            return null;
        }).then();
    }

    @Override
    public Mono<StoredObjectPage> list(String prefix, String continuationToken, int limit) {
        requireKey(prefix);
        requireListLimit(limit);
        return blocking(() -> {
            ListObjectsV2Request request = new ListObjectsV2Request(bucket)
                    .withPrefix(prefix)
                    .withContinuationToken(continuationToken)
                    .withMaxKeys(limit);
            ListObjectsV2Result result = backendClient.listObjectsV2(request);
            List<StoredObjectMetadata> objects = result.getObjectSummaries().stream()
                    .map(AliyunOssFileObjectStore::metadata)
                    .toList();
            return new StoredObjectPage(objects, result.getNextContinuationToken());
        });
    }

    @Override
    public Mono<Void> delete(String objectKey) {
        requireKey(objectKey);
        return blocking(() -> {
            backendClient.deleteObject(bucket, objectKey);
            return null;
        }).then();
    }

    @Override
    public Mono<SignedUploadForm> sign(
            String objectKey, String mimeType, long sizeBytes, Instant expiresAt) {
        requireKey(objectKey);
        if (mimeType == null || mimeType.isBlank() || sizeBytes < 0 || expiresAt == null) {
            throw new IllegalArgumentException("invalid_signed_upload_request");
        }
        return blocking(() -> {
            PolicyConditions conditions = new PolicyConditions();
            conditions.addConditionItem("bucket", bucket);
            conditions.addConditionItem(MatchMode.Exact, PolicyConditions.COND_KEY, objectKey);
            conditions.addConditionItem(MatchMode.Exact, PolicyConditions.COND_CONTENT_TYPE, mimeType);
            conditions.addConditionItem(PolicyConditions.COND_CONTENT_LENGTH_RANGE, sizeBytes, sizeBytes);
            conditions.addConditionItem(PolicyConditions.COND_SUCCESS_ACTION_STATUS, "204");
            conditions.addConditionItem("x-oss-object-acl", "private");
            conditions.addConditionItem("x-oss-server-side-encryption", "AES256");

            String rawPolicy = publicClient.generatePostPolicy(Date.from(expiresAt), conditions);
            Credentials credentials = credentialsProvider.getCredentials();
            String encodedPolicy = Base64.getEncoder().encodeToString(rawPolicy.getBytes(UTF_8));
            String signature = calculatePostSignature(credentials, encodedPolicy);
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("OSSAccessKeyId", credentials.getAccessKeyId());
            if (credentials.useSecurityToken()) {
                fields.put("x-oss-security-token", credentials.getSecurityToken());
            }
            fields.put("policy", encodedPolicy);
            fields.put("Signature", signature);
            fields.put("key", objectKey);
            fields.put("Content-Type", mimeType);
            fields.put("success_action_status", "204");
            fields.put("x-oss-object-acl", "private");
            fields.put("x-oss-server-side-encryption", "AES256");
            return new SignedUploadForm("POST", uploadUrl, fields, expiresAt);
        });
    }

    @Override
    public void close() {
        backendClient.shutdown();
        if (publicClient != backendClient) {
            publicClient.shutdown();
        }
    }

    private <T> Mono<T> blocking(Callable<T> operation) {
        return Mono.fromCallable(operation)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::mapException);
    }

    private RuntimeException mapException(Throwable error) {
        if (error instanceof FileObjectMissingException missing) {
            return missing;
        }
        if (error instanceof FileStorageException storageException) {
            return storageException;
        }
        if (error instanceof FileObjectTooLargeException tooLarge) {
            return tooLarge;
        }
        if (error instanceof OSSException ossException && isMissing(ossException)) {
            return new FileObjectMissingException();
        }
        return new FileStorageException();
    }

    private static boolean isMissing(OSSException error) {
        return "NoSuchKey".equals(error.getErrorCode())
                || "NoSuchObject".equals(error.getErrorCode());
    }

    private static StoredObjectMetadata metadata(String key, ObjectMetadata metadata) {
        return new StoredObjectMetadata(
                key,
                metadata.getContentLength(),
                metadata.getContentType(),
                metadata.getETag(),
                instant(metadata.getLastModified()));
    }

    private static StoredObjectMetadata metadata(OSSObjectSummary summary) {
        return new StoredObjectMetadata(
                summary.getKey(), summary.getSize(), null, summary.getETag(), instant(summary.getLastModified()));
    }

    private static Instant instant(Date value) {
        return value == null ? null : value.toInstant();
    }

    private static String calculatePostSignature(Credentials credentials, String encodedPolicy)
            throws java.security.GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(credentials.getSecretAccessKey().getBytes(UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(encodedPolicy.getBytes(UTF_8)));
    }

    private static URI bucketUrl(URI endpoint, String bucket) {
        if (endpoint == null || endpoint.getScheme() == null || endpoint.getAuthority() == null) {
            throw new IllegalStateException("storage_files_public_endpoint_required");
        }
        return URI.create(endpoint.getScheme() + "://" + bucket + "." + endpoint.getAuthority());
    }
}
