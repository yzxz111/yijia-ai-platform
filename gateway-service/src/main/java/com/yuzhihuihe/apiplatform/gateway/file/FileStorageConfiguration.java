package com.yuzhihuihe.apiplatform.gateway.file;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.EnvironmentVariableCredentialsProvider;
import com.aliyun.oss.common.auth.InstanceProfileCredentialsProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@EnableConfigurationProperties(FileStorageProperties.class)
public class FileStorageConfiguration {

    @Bean
    @ConditionalOnMissingBean(S3AsyncClient.class)
    @ConditionalOnProperty(prefix = "storage.files", name = "provider",
            havingValue = "minio", matchIfMissing = true)
    S3AsyncClient fileS3AsyncClient(FileStorageProperties properties) {
        if (properties.getAccessKey() == null || properties.getAccessKey().isBlank()
                || properties.getSecretKey() == null || properties.getSecretKey().isBlank()) {
            throw new IllegalStateException("storage_files_credentials_required");
        }
        return S3AsyncClient.builder()
                .endpointOverride(properties.getEndpoint())
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(NettyNioAsyncHttpClient.builder())
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "storage.files", name = "provider",
            havingValue = "minio", matchIfMissing = true)
    FileObjectStore s3FileObjectStore(S3AsyncClient client, FileStorageProperties properties) {
        return new S3FileObjectStore(client, properties.getBucket());
    }

    @Bean
    @ConditionalOnMissingBean(CredentialsProvider.class)
    @ConditionalOnProperty(prefix = "storage.files", name = "provider", havingValue = "aliyun-oss")
    CredentialsProvider aliyunOssCredentialsProvider(FileStorageProperties properties) {
        CredentialsProvider provider = createCredentialsProvider(properties);
        validateCredentials(provider);
        return provider;
    }

    CredentialsProvider createCredentialsProvider(FileStorageProperties properties) {
        if ("ENV".equalsIgnoreCase(properties.getCredentialSource())) {
            return new EnvironmentVariableCredentialsProvider();
        }
        if ("ECS".equalsIgnoreCase(properties.getCredentialSource())) {
            if (properties.getInstanceRoleName() == null || properties.getInstanceRoleName().isBlank()) {
                throw new IllegalStateException("storage_files_instance_role_required");
            }
            return new InstanceProfileCredentialsProvider(properties.getInstanceRoleName());
        }
        throw new IllegalStateException("storage_files_credential_source_invalid");
    }

    void validateCredentials(CredentialsProvider provider) {
        try {
            Credentials credentials = provider.getCredentials();
            if (credentials == null || credentials.getAccessKeyId() == null
                    || credentials.getAccessKeyId().isBlank()
                    || credentials.getSecretAccessKey() == null
                    || credentials.getSecretAccessKey().isBlank()) {
                throw new IllegalStateException("invalid credentials");
            }
        } catch (RuntimeException error) {
            throw new IllegalStateException("file_storage_credentials_unavailable");
        }
    }

    @Bean(name = "aliyunOssBackendClient", destroyMethod = "")
    @ConditionalOnProperty(prefix = "storage.files", name = "provider", havingValue = "aliyun-oss")
    OSS aliyunOssBackendClient(FileStorageProperties properties, CredentialsProvider provider) {
        requireOssEndpoint(properties.getBackendEndpoint(), "storage_files_backend_endpoint_required");
        return new OSSClientBuilder().build(properties.getBackendEndpoint().toString(), provider);
    }

    @Bean(name = "aliyunOssPublicClient", destroyMethod = "")
    @ConditionalOnProperty(prefix = "storage.files", name = "provider", havingValue = "aliyun-oss")
    OSS aliyunOssPublicClient(
            FileStorageProperties properties,
            CredentialsProvider provider,
            @Qualifier("aliyunOssBackendClient") OSS backendClient) {
        requireOssEndpoint(properties.getPublicEndpoint(), "storage_files_public_endpoint_required");
        if (properties.getPublicEndpoint().equals(properties.getBackendEndpoint())) {
            return backendClient;
        }
        return new OSSClientBuilder().build(properties.getPublicEndpoint().toString(), provider);
    }

    @Bean
    @ConditionalOnProperty(prefix = "storage.files", name = "provider", havingValue = "aliyun-oss")
    AliyunOssFileObjectStore aliyunOssFileObjectStore(
            @Qualifier("aliyunOssBackendClient") OSS backendClient,
            @Qualifier("aliyunOssPublicClient") OSS publicClient,
            CredentialsProvider provider,
            FileStorageProperties properties) {
        return new AliyunOssFileObjectStore(backendClient, publicClient, provider, properties);
    }

    private static void requireOssEndpoint(java.net.URI endpoint, String errorCode) {
        if (endpoint == null || endpoint.getScheme() == null || endpoint.getHost() == null) {
            throw new IllegalStateException(errorCode);
        }
        if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalStateException("storage_files_https_endpoint_required");
        }
    }
}
