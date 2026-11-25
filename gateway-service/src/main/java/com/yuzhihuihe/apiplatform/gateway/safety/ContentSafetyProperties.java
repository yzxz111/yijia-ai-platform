package com.yuzhihuihe.apiplatform.gateway.safety;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "content-safety")
public class ContentSafetyProperties {

    private boolean enabled = true;
    private boolean failClosed = true;
    private Duration policyCacheTtl = Duration.ofSeconds(30);
    private String semanticCredential = "";
    private final Semantic semantic = new Semantic();
    private final Stream stream = new Stream();
    private final Files files = new Files();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isFailClosed() { return failClosed; }
    public void setFailClosed(boolean failClosed) { this.failClosed = failClosed; }
    public Duration getPolicyCacheTtl() { return policyCacheTtl; }
    public void setPolicyCacheTtl(Duration policyCacheTtl) { this.policyCacheTtl = policyCacheTtl; }
    public String getSemanticCredential() { return semanticCredential; }
    public void setSemanticCredential(String semanticCredential) { this.semanticCredential = semanticCredential; }
    public Semantic getSemantic() { return semantic; }
    public Stream getStream() { return stream; }
    public Files getFiles() { return files; }

    public static class Semantic {
        private String provider = "MOCK";
        private String baseUrl = "http://wiremock:8080";
        private boolean allowMock;
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(2);
        private Duration totalTimeout = Duration.ofSeconds(3);
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public boolean isAllowMock() { return allowMock; }
        public void setAllowMock(boolean allowMock) { this.allowMock = allowMock; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
        public Duration getTotalTimeout() { return totalTimeout; }
        public void setTotalTimeout(Duration totalTimeout) { this.totalTimeout = totalTimeout; }
    }

    public static class Stream {
        private int windowCodePoints = 512;
        private Duration maxWait = Duration.ofMillis(200);
        public int getWindowCodePoints() { return windowCodePoints; }
        public void setWindowCodePoints(int windowCodePoints) { this.windowCodePoints = windowCodePoints; }
        public Duration getMaxWait() { return maxWait; }
        public void setMaxWait(Duration maxWait) { this.maxWait = maxWait; }
    }

    public static class Files {
        private long maxExpandedBytes = 20_971_520L;
        private int maxArchiveEntries = 1000;
        private int maxArchiveDepth = 3;
        private boolean clamavEnabled = true;
        private String clamavHost = "clamav";
        private int clamavPort = 3310;
        private Duration clamavConnectTimeout = Duration.ofSeconds(2);
        private Duration clamavReadTimeout = Duration.ofMinutes(10);
        public long getMaxExpandedBytes() { return maxExpandedBytes; }
        public void setMaxExpandedBytes(long maxExpandedBytes) { this.maxExpandedBytes = maxExpandedBytes; }
        public int getMaxArchiveEntries() { return maxArchiveEntries; }
        public void setMaxArchiveEntries(int maxArchiveEntries) { this.maxArchiveEntries = maxArchiveEntries; }
        public int getMaxArchiveDepth() { return maxArchiveDepth; }
        public void setMaxArchiveDepth(int maxArchiveDepth) { this.maxArchiveDepth = maxArchiveDepth; }
        public boolean isClamavEnabled() { return clamavEnabled; }
        public void setClamavEnabled(boolean clamavEnabled) { this.clamavEnabled = clamavEnabled; }
        public String getClamavHost() { return clamavHost; }
        public void setClamavHost(String clamavHost) { this.clamavHost = clamavHost; }
        public int getClamavPort() { return clamavPort; }
        public void setClamavPort(int clamavPort) { this.clamavPort = clamavPort; }
        public Duration getClamavConnectTimeout() { return clamavConnectTimeout; }
        public void setClamavConnectTimeout(Duration clamavConnectTimeout) {
            this.clamavConnectTimeout = clamavConnectTimeout;
        }
        public Duration getClamavReadTimeout() { return clamavReadTimeout; }
        public void setClamavReadTimeout(Duration clamavReadTimeout) {
            this.clamavReadTimeout = clamavReadTimeout;
        }
    }
}
