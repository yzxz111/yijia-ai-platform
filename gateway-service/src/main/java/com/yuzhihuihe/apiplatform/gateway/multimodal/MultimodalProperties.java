package com.yuzhihuihe.apiplatform.gateway.multimodal;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

@Component
@ConfigurationProperties(prefix = "gateway.multimodal")
public class MultimodalProperties {

    private int maxAttachments = 8;
    private int maxPdfPages = 200;
    private int maxRenderedPdfPages = 4;
    private DataSize maxPdfDecodedBytes = DataSize.ofMegabytes(20);
    private int maxPdfWorkUnits = 100_000;
    private int maxSheets = 20;
    private int maxRowsPerSheet = 10_000;
    private int maxCells = 100_000;
    private int maxCharacters = 200_000;
    private DataSize maxConvertedOutput = DataSize.ofMegabytes(20);
    private Duration preprocessingTimeout = Duration.ofSeconds(20);
    private Duration cacheTtl = Duration.ofHours(1);
    private String extractorVersion = "multimodal-v1";
    private String policyVersion = "multimodal-v1";
    private String converterBaseUrl = "";
    private String converterAuthToken = "";
    private boolean converterAllowInsecureLocal;
    private Set<String> converterAllowedHosts = Set.of();

    public int getMaxAttachments() {
        return maxAttachments;
    }

    public void setMaxAttachments(int maxAttachments) {
        this.maxAttachments = positive(maxAttachments, "maxAttachments");
    }

    public int getMaxPdfPages() {
        return maxPdfPages;
    }

    public void setMaxPdfPages(int maxPdfPages) {
        this.maxPdfPages = positive(maxPdfPages, "maxPdfPages");
    }

    public int getMaxRenderedPdfPages() {
        return maxRenderedPdfPages;
    }

    public void setMaxRenderedPdfPages(int maxRenderedPdfPages) {
        this.maxRenderedPdfPages = positive(maxRenderedPdfPages, "maxRenderedPdfPages");
    }

    public DataSize getMaxPdfDecodedBytes() {
        return maxPdfDecodedBytes;
    }

    public void setMaxPdfDecodedBytes(DataSize maxPdfDecodedBytes) {
        if (maxPdfDecodedBytes == null || maxPdfDecodedBytes.toBytes() <= 0) {
            throw new IllegalArgumentException("maxPdfDecodedBytes must be positive");
        }
        this.maxPdfDecodedBytes = maxPdfDecodedBytes;
    }

    public int getMaxPdfWorkUnits() {
        return maxPdfWorkUnits;
    }

    public void setMaxPdfWorkUnits(int maxPdfWorkUnits) {
        this.maxPdfWorkUnits = positive(maxPdfWorkUnits, "maxPdfWorkUnits");
    }

    public int getMaxSheets() {
        return maxSheets;
    }

    public void setMaxSheets(int maxSheets) {
        this.maxSheets = positive(maxSheets, "maxSheets");
    }

    public int getMaxRowsPerSheet() {
        return maxRowsPerSheet;
    }

    public void setMaxRowsPerSheet(int maxRowsPerSheet) {
        this.maxRowsPerSheet = positive(maxRowsPerSheet, "maxRowsPerSheet");
    }

    public int getMaxCells() {
        return maxCells;
    }

    public void setMaxCells(int maxCells) {
        this.maxCells = positive(maxCells, "maxCells");
    }

    public int getMaxCharacters() {
        return maxCharacters;
    }

    public void setMaxCharacters(int maxCharacters) {
        this.maxCharacters = positive(maxCharacters, "maxCharacters");
    }

    public DataSize getMaxConvertedOutput() {
        return maxConvertedOutput;
    }

    public void setMaxConvertedOutput(DataSize maxConvertedOutput) {
        if (maxConvertedOutput == null || maxConvertedOutput.toBytes() <= 0) {
            throw new IllegalArgumentException("maxConvertedOutput must be positive");
        }
        this.maxConvertedOutput = maxConvertedOutput;
    }

    public Duration getPreprocessingTimeout() {
        return preprocessingTimeout;
    }

    public void setPreprocessingTimeout(Duration preprocessingTimeout) {
        this.preprocessingTimeout = positive(preprocessingTimeout, "preprocessingTimeout");
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = positive(cacheTtl, "cacheTtl");
    }

    public String getExtractorVersion() {
        return extractorVersion;
    }

    public void setExtractorVersion(String extractorVersion) {
        this.extractorVersion = text(extractorVersion, "extractorVersion");
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = text(policyVersion, "policyVersion");
    }

    public String getConverterBaseUrl() {
        return converterBaseUrl;
    }

    public void setConverterBaseUrl(String converterBaseUrl) {
        this.converterBaseUrl = normalize(converterBaseUrl);
    }

    public String getConverterAuthToken() {
        return converterAuthToken;
    }

    public void setConverterAuthToken(String converterAuthToken) {
        this.converterAuthToken = normalize(converterAuthToken);
    }

    public boolean isConverterAllowInsecureLocal() {
        return converterAllowInsecureLocal;
    }

    public void setConverterAllowInsecureLocal(boolean converterAllowInsecureLocal) {
        this.converterAllowInsecureLocal = converterAllowInsecureLocal;
    }

    public Set<String> getConverterAllowedHosts() {
        return converterAllowedHosts;
    }

    public void setConverterAllowedHosts(Set<String> converterAllowedHosts) {
        this.converterAllowedHosts = converterAllowedHosts == null
                ? Set.of()
                : converterAllowedHosts.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(value -> value.strip().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
