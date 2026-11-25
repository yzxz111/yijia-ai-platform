package com.yuzhihuihe.apiplatform.gateway.file;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.EnvironmentVariableCredentialsProvider;
import com.aliyun.oss.model.SetBucketCORSRequest;
import com.aliyun.oss.model.SetBucketCORSRequest.CORSRule;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "OSS_INTEGRATION_ENABLED", matches = "true")
class AliyunOssFileObjectStoreIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void signedPostAndBackendObjectLifecycleUseOnlyExactRunKeys() throws Exception {
        configureCorsIfRequested();
        String runPrefix = "test-runs/" + UUID.randomUUID() + "/";
        String uploadedKey = runPrefix + "signed-post.txt";
        String copiedKey = runPrefix + "copied.txt";
        String rejectedKey = runPrefix + "wrong-key.txt";
        byte[] content = "aliyun-oss-direct-upload".getBytes(UTF_8);
        AliyunOssFileObjectStore store = createStore();

        try {
            UploadFormSigner.SignedUploadForm form = store.sign(
                    uploadedKey, "text/plain", content.length, Instant.now().plusSeconds(300))
                    .block(TIMEOUT);
            assertThat(post(form, form.fields(), content)).isEqualTo(204);
            assertThat(store.head(uploadedKey).block(TIMEOUT).sizeBytes()).isEqualTo(content.length);
            assertThat(store.get(uploadedKey, content.length).block(TIMEOUT)).isEqualTo(content);

            store.copy(uploadedKey, copiedKey).block(TIMEOUT);
            assertThat(store.head(copiedKey).block(TIMEOUT).sizeBytes()).isEqualTo(content.length);
            store.delete(copiedKey).block(TIMEOUT);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> store.head(copiedKey).block(TIMEOUT))
                    .isInstanceOf(FileObjectMissingException.class)
                    .hasMessage("file_object_missing");

            Map<String, String> wrongKey = new LinkedHashMap<>(form.fields());
            wrongKey.put("key", rejectedKey);
            assertThat(post(form, wrongKey, content)).isGreaterThanOrEqualTo(400);
            assertThat(post(form, form.fields(), "short".getBytes(UTF_8))).isGreaterThanOrEqualTo(400);
        } finally {
            // 清理刻意只针对确切的 key。绝不列举或删除 Bucket 根。
            store.delete(rejectedKey).onErrorResume(ignored -> reactor.core.publisher.Mono.empty()).block(TIMEOUT);
            store.delete(copiedKey).onErrorResume(ignored -> reactor.core.publisher.Mono.empty()).block(TIMEOUT);
            store.delete(uploadedKey).onErrorResume(ignored -> reactor.core.publisher.Mono.empty()).block(TIMEOUT);
            store.close();
        }
    }

    private static void configureCorsIfRequested() {
        if (!"true".equalsIgnoreCase(System.getenv("OSS_CONFIGURE_CORS"))) {
            return;
        }
        String bucket = required("OSS_TEST_BUCKET");
        String origin = required("OSS_CORS_ORIGIN");
        CredentialsProvider credentials = new EnvironmentVariableCredentialsProvider();
        OSS client = new OSSClientBuilder().build(required("OSS_BACKEND_ENDPOINT"), credentials);
        try {
            List<CORSRule> rules;
            try {
                rules = new ArrayList<>(client.getBucketCORSRules(bucket));
            } catch (OSSException error) {
                if (!"NoSuchCORSConfiguration".equals(error.getErrorCode())) {
                    throw error;
                }
                rules = new ArrayList<>();
            }
            boolean configured = rules.stream().anyMatch(rule ->
                    rule.getAllowedOrigins().contains(origin)
                            && rule.getAllowedMethods().contains("POST"));
            if (!configured) {
                CORSRule rule = new CORSRule();
                rule.addAllowdOrigin(origin);
                rule.addAllowedMethod("POST");
                rule.addAllowedHeader("*");
                rule.addExposeHeader("ETag");
                rule.setMaxAgeSeconds(600);
                rules.add(rule);
                SetBucketCORSRequest request = new SetBucketCORSRequest(bucket);
                request.setCorsRules(rules);
                client.setBucketCORS(request);
            }
        } finally {
            client.shutdown();
        }
    }

    private static AliyunOssFileObjectStore createStore() {
        String bucket = required("OSS_TEST_BUCKET");
        URI publicEndpoint = URI.create(required("OSS_PUBLIC_ENDPOINT"));
        URI backendEndpoint = URI.create(required("OSS_BACKEND_ENDPOINT"));
        CredentialsProvider credentials = new EnvironmentVariableCredentialsProvider();
        OSS backend = new OSSClientBuilder().build(backendEndpoint.toString(), credentials);
        OSS publicClient = publicEndpoint.equals(backendEndpoint)
                ? backend
                : new OSSClientBuilder().build(publicEndpoint.toString(), credentials);
        FileStorageProperties properties = new FileStorageProperties();
        properties.setBucket(bucket);
        properties.setPublicEndpoint(publicEndpoint);
        properties.setBackendEndpoint(backendEndpoint);
        return new AliyunOssFileObjectStore(backend, publicClient, credentials, properties);
    }

    private static int post(
            UploadFormSigner.SignedUploadForm form, Map<String, String> fields, byte[] content)
            throws IOException, InterruptedException {
        String boundary = "yzh-oss-" + UUID.randomUUID();
        byte[] body = multipart(boundary, fields, content);
        HttpRequest request = HttpRequest.newBuilder(form.url())
                .timeout(TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
                .send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static byte[] multipart(String boundary, Map<String, String> fields, byte[] content)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            write(output, "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
                    + field.getValue() + "\r\n");
        }
        write(output, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"upload.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n");
        output.write(content);
        write(output, "\r\n--" + boundary + "--\r\n");
        return output.toByteArray();
    }

    private static void write(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(UTF_8));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the opt-in OSS integration test");
        }
        return value;
    }
}
