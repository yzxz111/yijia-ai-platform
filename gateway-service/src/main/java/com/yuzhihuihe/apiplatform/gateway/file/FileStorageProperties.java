package com.yuzhihuihe.apiplatform.gateway.file;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("storage.files")
public class FileStorageProperties {

    private String provider = "minio";
    private String region = "us-east-1";
    private String bucket = "api-platform-files";
    private URI endpoint = URI.create("http://minio:9000");
    private URI publicEndpoint;
    private URI backendEndpoint;
    private String accessKey = "";
    private String secretKey = "";
    private String credentialSource = "ENV";
    private String instanceRoleName;
    private Duration signedUploadTtl = Duration.ofMinutes(5);
    private Duration objectTtl = Duration.ofHours(24);
    private boolean directUploadEnabled;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public URI getEndpoint() { return endpoint; }
    public void setEndpoint(URI endpoint) { this.endpoint = endpoint; }
    public URI getPublicEndpoint() { return publicEndpoint; }
    public void setPublicEndpoint(URI publicEndpoint) { this.publicEndpoint = publicEndpoint; }
    public URI getBackendEndpoint() { return backendEndpoint; }
    public void setBackendEndpoint(URI backendEndpoint) { this.backendEndpoint = backendEndpoint; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getCredentialSource() { return credentialSource; }
    public void setCredentialSource(String credentialSource) { this.credentialSource = credentialSource; }
    public String getInstanceRoleName() { return instanceRoleName; }
    public void setInstanceRoleName(String instanceRoleName) { this.instanceRoleName = instanceRoleName; }
    public Duration getSignedUploadTtl() { return signedUploadTtl; }
    public void setSignedUploadTtl(Duration signedUploadTtl) { this.signedUploadTtl = signedUploadTtl; }
    public Duration getObjectTtl() { return objectTtl; }
    public void setObjectTtl(Duration objectTtl) { this.objectTtl = objectTtl; }
    public boolean isDirectUploadEnabled() { return directUploadEnabled; }
    public void setDirectUploadEnabled(boolean directUploadEnabled) {
        this.directUploadEnabled = directUploadEnabled;
    }
}
