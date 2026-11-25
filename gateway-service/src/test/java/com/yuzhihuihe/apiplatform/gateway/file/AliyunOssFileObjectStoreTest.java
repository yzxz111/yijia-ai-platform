package com.yuzhihuihe.apiplatform.gateway.file;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentials;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PolicyConditions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AliyunOssFileObjectStoreTest {

    private static final String BUCKET = "private-files";
    private static final Instant NOW = Instant.parse("2026-08-10T04:00:00Z");

    @Test
    void performsEveryBlockingObjectOperationOnBoundedElastic() throws Exception {
        OSS backend = mock(OSS.class);
        OSS publicClient = mock(OSS.class);
        List<String> threads = new ArrayList<>();
        ObjectMetadata metadata = metadata(5, "text/plain", "etag-1", NOW);
        OSSObject object = mock(OSSObject.class);
        when(object.getObjectContent()).thenReturn(new ByteArrayInputStream("hello".getBytes(UTF_8)));
        when(backend.putObject(eq(BUCKET), eq("files/legacy"), any(InputStream.class),
                any(ObjectMetadata.class)))
                .thenAnswer(invocation -> { threads.add(Thread.currentThread().getName()); return null; });
        when(backend.getObject(BUCKET, "clean/source"))
                .thenAnswer(invocation -> { threads.add(Thread.currentThread().getName()); return object; });
        when(backend.headObject(BUCKET, "clean/source"))
                .thenAnswer(invocation -> { threads.add(Thread.currentThread().getName()); return metadata; });
        when(backend.copyObject(BUCKET, "clean/source", BUCKET, "exports/target"))
                .thenAnswer(invocation -> { threads.add(Thread.currentThread().getName()); return null; });
        doAnswer(invocation -> { threads.add(Thread.currentThread().getName()); return null; })
                .when(backend).deleteObject(BUCKET, "exports/target");
        ListObjectsV2Result listing = new ListObjectsV2Result();
        OSSObjectSummary summary = new OSSObjectSummary();
        summary.setKey("test-runs/run/object");
        summary.setSize(5);
        summary.setETag("etag-2");
        summary.setLastModified(Date.from(NOW));
        listing.addObjectSummary(summary);
        listing.setNextContinuationToken("next-token");
        when(backend.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenAnswer(invocation -> { threads.add(Thread.currentThread().getName()); return listing; });

        AliyunOssFileObjectStore store = store(backend, publicClient, credentials(false));
        store.put("files/legacy", "hello".getBytes(UTF_8), "text/plain").block(Duration.ofSeconds(2));
        assertThat(store.get("clean/source", 5).block(Duration.ofSeconds(2)))
                .isEqualTo("hello".getBytes(UTF_8));
        assertThat(store.head("clean/source").block(Duration.ofSeconds(2)))
                .isEqualTo(new StoredObjectMetadata("clean/source", 5, "text/plain", "etag-1", NOW));
        store.copy("clean/source", "exports/target").block(Duration.ofSeconds(2));
        store.delete("exports/target").block(Duration.ofSeconds(2));
        StoredObjectPage page = store.list("test-runs/run/", "token", 25).block(Duration.ofSeconds(2));

        assertThat(page.objects()).containsExactly(
                new StoredObjectMetadata("test-runs/run/object", 5, null, "etag-2", NOW));
        assertThat(page.nextContinuationToken()).isEqualTo("next-token");
        assertThat(threads).hasSize(6).allSatisfy(name -> assertThat(name).contains("boundedElastic"));
        verify(object).close();
        ArgumentCaptor<ListObjectsV2Request> request = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(backend).listObjectsV2(request.capture());
        assertThat(request.getValue().getBucketName()).isEqualTo(BUCKET);
        assertThat(request.getValue().getPrefix()).isEqualTo("test-runs/run/");
        assertThat(request.getValue().getContinuationToken()).isEqualTo("token");
        assertThat(request.getValue().getMaxKeys()).isEqualTo(25);
    }

    @Test
    void rejectsUnsafeKeysAndInvalidListLimitsBeforeCallingOss() {
        OSS backend = mock(OSS.class);
        AliyunOssFileObjectStore store = store(backend, mock(OSS.class), credentials(false));

        assertThatThrownBy(() -> store.get("other/object", 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_file_object_key");
        assertThatThrownBy(() -> store.list("clean/", null, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_file_object_list_limit");
        assertThatThrownBy(() -> store.list("clean/", null, 1001))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_file_object_list_limit");
        verify(backend, never()).getObject(anyString(), anyString());
        verify(backend, never()).listObjectsV2(any(ListObjectsV2Request.class));
    }

    @Test
    void distinguishesMissingObjectsAndSanitizesOtherSdkFailures() {
        OSS backend = mock(OSS.class);
        OSSException missing = mock(OSSException.class);
        when(missing.getErrorCode()).thenReturn("NoSuchKey");
        when(backend.headObject(BUCKET, "quarantine/missing")).thenThrow(missing);
        when(backend.getObject(BUCKET, "quarantine/failure"))
                .thenThrow(new ClientException("secret endpoint and credential detail"));
        AliyunOssFileObjectStore store = store(backend, mock(OSS.class), credentials(false));

        assertThatThrownBy(() -> store.head("quarantine/missing").block(Duration.ofSeconds(2)))
                .isInstanceOf(FileObjectMissingException.class).hasMessage("file_object_missing");
        assertThatThrownBy(() -> store.get("quarantine/failure", 1).block(Duration.ofSeconds(2)))
                .isInstanceOf(FileStorageException.class).hasMessage("file_storage_unavailable")
                .hasMessageNotContaining("secret");
    }

    @Test
    void rejectsAnObjectThatExceedsTheCallerReadLimit() throws Exception {
        OSS backend = mock(OSS.class);
        OSSObject object = mock(OSSObject.class);
        when(object.getObjectContent())
                .thenReturn(new ByteArrayInputStream("too-large".getBytes(UTF_8)));
        when(backend.getObject(BUCKET, "quarantine/oversized")).thenReturn(object);
        AliyunOssFileObjectStore store = store(backend, mock(OSS.class), credentials(false));

        assertThatThrownBy(() -> store.get("quarantine/oversized", 3)
                        .block(Duration.ofSeconds(2)))
                .isInstanceOf(FileObjectTooLargeException.class)
                .hasMessage("file_object_too_large");

        verify(object).close();
    }

    @Test
    void sanitizesCredentialRefreshFailuresWithoutRetainingTheCause() {
        OSS publicClient = mock(OSS.class);
        when(publicClient.generatePostPolicy(any(), any())).thenReturn("{}");
        CredentialsProvider failing = mock(CredentialsProvider.class);
        when(failing.getCredentials()).thenThrow(new IllegalStateException("rotated-secret-value"));
        AliyunOssFileObjectStore store = store(mock(OSS.class), publicClient, failing);

        assertThatThrownBy(() -> store.sign(
                "quarantine/refresh", "application/pdf", 10, NOW.plusSeconds(300))
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(FileStorageException.class)
                .hasMessage("file_storage_unavailable")
                .hasMessageNotContaining("secret")
                .hasNoCause();
    }

    @Test
    void signsAnExactPostPolicyWithoutExposingTheSecret() throws Exception {
        OSS backend = mock(OSS.class);
        OSS publicClient = mock(OSS.class);
        CredentialsProvider credentials = credentials(true);
        String rawPolicy = "{\"expiration\":\"2026-08-10T04:05:00.000Z\",\"conditions\":[]}";
        when(publicClient.generatePostPolicy(any(Date.class), any(PolicyConditions.class))).thenReturn(rawPolicy);
        AliyunOssFileObjectStore store = store(backend, publicClient, credentials);
        Instant expiresAt = NOW.plus(Duration.ofMinutes(5));

        UploadFormSigner.SignedUploadForm form = store.sign(
                "quarantine/opaque", "application/pdf", 1234, expiresAt).block(Duration.ofSeconds(2));

        assertThat(form.method()).isEqualTo("POST");
        assertThat(form.url()).isEqualTo(URI.create("https://private-files.oss-cn-beijing.aliyuncs.com"));
        assertThat(form.expiresAt()).isEqualTo(expiresAt);
        assertThat(form.fields())
                .containsEntry("OSSAccessKeyId", "access-id")
                .containsEntry("x-oss-security-token", "session-token")
                .containsEntry("policy", Base64.getEncoder().encodeToString(rawPolicy.getBytes(UTF_8)))
                .containsEntry("Signature", hmacSha1(
                        "secret-value", Base64.getEncoder().encodeToString(rawPolicy.getBytes(UTF_8))))
                .containsEntry("key", "quarantine/opaque")
                .containsEntry("Content-Type", "application/pdf")
                .containsEntry("success_action_status", "204")
                .containsEntry("x-oss-object-acl", "private")
                .containsEntry("x-oss-server-side-encryption", "AES256")
                .doesNotContainValue("secret-value");
        ArgumentCaptor<Date> expiration = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<PolicyConditions> conditions = ArgumentCaptor.forClass(PolicyConditions.class);
        verify(publicClient).generatePostPolicy(expiration.capture(), conditions.capture());
        assertThat(expiration.getValue()).isEqualTo(Date.from(expiresAt));
        assertThat(conditions.getValue().jsonize())
                .contains("\"bucket\":\"private-files\"")
                .contains("[\"eq\",\"$key\",\"quarantine/opaque\"]")
                .contains("[\"eq\",\"$Content-Type\",\"application/pdf\"]")
                .contains("[\"content-length-range\",1234,1234]")
                .contains("\"success_action_status\":\"204\"")
                .contains("\"x-oss-object-acl\":\"private\"")
                .contains("\"x-oss-server-side-encryption\":\"AES256\"");
        verify(publicClient, never()).calculatePostSignature(anyString());
    }

    @Test
    void signsAndBuildsFieldsFromOneRotatingCredentialSnapshot() throws Exception {
        CredentialsProvider rotating = mock(CredentialsProvider.class);
        when(rotating.getCredentials()).thenReturn(
                new DefaultCredentials("first-id", "first-secret", "first-token"),
                new DefaultCredentials("second-id", "second-secret", "second-token"));
        OSS publicClient = new OSSClientBuilder().build(
                "https://oss-cn-beijing.aliyuncs.com", rotating);
        AliyunOssFileObjectStore store = store(mock(OSS.class), publicClient, rotating);

        UploadFormSigner.SignedUploadForm form = store.sign(
                "quarantine/rotating", "application/pdf", 12, NOW.plusSeconds(300))
                .block(Duration.ofSeconds(2));

        assertThat(form.fields())
                .containsEntry("OSSAccessKeyId", "first-id")
                .containsEntry("x-oss-security-token", "first-token")
                .containsEntry("Signature", hmacSha1("first-secret", form.fields().get("policy")));
        verify(rotating, times(1)).getCredentials();
        store.close();
    }

    @Test
    void omitsSecurityTokenForLongLivedCredentialsAndClosesOwnedClients() {
        OSS backend = mock(OSS.class);
        OSS publicClient = mock(OSS.class);
        when(publicClient.generatePostPolicy(any(), any())).thenReturn("{}");
        AliyunOssFileObjectStore store = store(backend, publicClient, credentials(false));

        UploadFormSigner.SignedUploadForm form = store.sign(
                "quarantine/opaque", "image/png", 1, NOW).block(Duration.ofSeconds(2));
        store.close();

        assertThat(form.fields()).doesNotContainKey("x-oss-security-token");
        verify(backend).shutdown();
        verify(publicClient).shutdown();
    }

    private static AliyunOssFileObjectStore store(
            OSS backend, OSS publicClient, CredentialsProvider credentials) {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setBucket(BUCKET);
        properties.setPublicEndpoint(URI.create("https://oss-cn-beijing.aliyuncs.com"));
        properties.setBackendEndpoint(URI.create("https://oss-cn-beijing-internal.aliyuncs.com"));
        return new AliyunOssFileObjectStore(backend, publicClient, credentials, properties);
    }

    private static CredentialsProvider credentials(boolean temporary) {
        CredentialsProvider provider = mock(CredentialsProvider.class);
        when(provider.getCredentials()).thenReturn(temporary
                ? new DefaultCredentials("access-id", "secret-value", "session-token")
                : new DefaultCredentials("access-id", "secret-value"));
        return provider;
    }

    private static ObjectMetadata metadata(long length, String type, String etag, Instant modified) {
        ObjectMetadata metadata = mock(ObjectMetadata.class);
        when(metadata.getContentLength()).thenReturn(length);
        when(metadata.getContentType()).thenReturn(type);
        when(metadata.getETag()).thenReturn(etag);
        when(metadata.getLastModified()).thenReturn(Date.from(modified));
        return metadata;
    }

    private static String hmacSha1(String secret, String policy) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(policy.getBytes(UTF_8)));
    }
}
