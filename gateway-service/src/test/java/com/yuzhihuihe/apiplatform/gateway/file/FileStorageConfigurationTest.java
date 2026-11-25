package com.yuzhihuihe.apiplatform.gateway.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;

import com.aliyun.oss.OSS;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentials;
import com.aliyun.oss.common.auth.EnvironmentVariableCredentialsProvider;
import com.aliyun.oss.common.auth.InstanceProfileCredentialsProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3AsyncClient;

class FileStorageConfigurationTest {

    private final ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FileStorageConfiguration.class));

    @Test
    void configuredMinioCreatesExactlyOneStore() {
        contextRunner.withPropertyValues(
                "storage.files.access-key=test-access",
                "storage.files.secret-key=test-secret").run(context -> {
            assertThat(context).hasSingleBean(FileObjectStore.class);
            assertThat(context).hasSingleBean(S3FileObjectStore.class);
            assertThat(context).hasSingleBean(S3AsyncClient.class);
            assertThat(context).doesNotHaveBean(AliyunOssFileObjectStore.class);
        });
    }

    @Test
    void rejectsMinioWhenCredentialsAreMissing() {
        contextRunner.run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause()
                .hasMessage("storage_files_credentials_required"));
    }

    @Test
    void selectsAliyunExplicitlyAndKeepsPublicAndBackendEndpointsSeparate() {
        contextRunner
                .withUserConfiguration(TestCredentialsConfiguration.class)
                .withPropertyValues(
                        "storage.files.provider=aliyun-oss",
                        "storage.files.bucket=private-files",
                        "storage.files.public-endpoint=https://oss-cn-beijing.aliyuncs.com",
                        "storage.files.backend-endpoint=https://oss-cn-beijing-internal.aliyuncs.com")
                .run(context -> {
                    assertThat(context).hasSingleBean(FileObjectStore.class);
                    assertThat(context).hasSingleBean(AliyunOssFileObjectStore.class);
                    assertThat(context).doesNotHaveBean(S3FileObjectStore.class);
                    assertThat(context).doesNotHaveBean(S3AsyncClient.class);
                    FileStorageProperties properties = context.getBean(FileStorageProperties.class);
                    assertThat(properties.getPublicEndpoint())
                            .isEqualTo(URI.create("https://oss-cn-beijing.aliyuncs.com"));
                    assertThat(properties.getBackendEndpoint())
                            .isEqualTo(URI.create("https://oss-cn-beijing-internal.aliyuncs.com"));
                });
    }

    @Test
    void createsTheConfiguredCredentialProviderTypes() {
        FileStorageConfiguration configuration = new FileStorageConfiguration();
        FileStorageProperties properties = new FileStorageProperties();
        properties.setCredentialSource("ENV");
        assertThat(configuration.createCredentialsProvider(properties))
                .isInstanceOf(EnvironmentVariableCredentialsProvider.class);

        properties.setCredentialSource("ECS");
        properties.setInstanceRoleName("gateway-files-role");
        assertThat(configuration.createCredentialsProvider(properties))
                .isInstanceOf(InstanceProfileCredentialsProvider.class);
    }

    @Test
    void rejectsMissingRoleAndSanitizesUnavailableCredentials() {
        FileStorageConfiguration configuration = new FileStorageConfiguration();
        FileStorageProperties properties = new FileStorageProperties();
        properties.setCredentialSource("ECS");

        assertThatThrownBy(() -> configuration.createCredentialsProvider(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("storage_files_instance_role_required");

        CredentialsProvider unavailable = mock(CredentialsProvider.class);
        when(unavailable.getCredentials()).thenThrow(new IllegalStateException("secret-value"));
        assertThatThrownBy(() -> configuration.validateCredentials(unavailable))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("file_storage_credentials_unavailable")
                .hasMessageNotContaining("secret-value");
    }

    @Test
    void s3RejectsUnsafeKeysAndInvalidListLimitsBeforeCallingTheClient() {
        S3AsyncClient client = mock(S3AsyncClient.class);
        S3FileObjectStore store = new S3FileObjectStore(client, "private-files");

        assertThatThrownBy(() -> store.get("other/object", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_file_object_key");
        assertThatThrownBy(() -> store.list("clean/", null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_file_object_list_limit");
        verifyNoInteractions(client);
    }

    @Test
    void rejectsHttpForBothOssEndpointsWithoutLeakingEndpointCredentials() {
        FileStorageConfiguration configuration = new FileStorageConfiguration();
        FileStorageProperties properties = new FileStorageProperties();
        CredentialsProvider provider = mock(CredentialsProvider.class);

        properties.setBackendEndpoint(URI.create("http://access-id:backend-secret@oss.example.com"));
        assertThatThrownBy(() -> configuration.aliyunOssBackendClient(properties, provider))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("storage_files_https_endpoint_required")
                .hasMessageNotContaining("secret");

        properties.setBackendEndpoint(URI.create("https://oss.example.com"));
        properties.setPublicEndpoint(URI.create("http://access-id:public-secret@oss.example.com"));
        assertThatThrownBy(() -> configuration.aliyunOssPublicClient(
                properties, provider, mock(OSS.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("storage_files_https_endpoint_required")
                .hasMessageNotContaining("secret");
    }

    @Configuration(proxyBeanMethods = false)
    static class TestCredentialsConfiguration {

        @Bean
        CredentialsProvider testCredentialsProvider() {
            CredentialsProvider provider = mock(CredentialsProvider.class);
            when(provider.getCredentials()).thenReturn(new DefaultCredentials("id", "secret"));
            return provider;
        }
    }
}
