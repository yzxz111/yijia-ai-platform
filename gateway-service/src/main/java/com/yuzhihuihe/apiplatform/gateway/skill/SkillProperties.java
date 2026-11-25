package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

@Component
@ConfigurationProperties(prefix = "gateway.skills")
public class SkillProperties {

    /** Catalog source: both database and filesystem modes serve YCPS-only artifacts. */
    public static final String SOURCE_DATABASE = "database";
    public static final String SOURCE_FILESYSTEM = "filesystem";

    private static final Set<String> SOURCES = Set.of(SOURCE_DATABASE, SOURCE_FILESYSTEM);

    private String root = "skills";
    private DataSize maxFileBytes = DataSize.ofMegabytes(1);
    private DataSize maxTotalBytes = DataSize.ofMegabytes(4);
    private int maxFilesPerSkill = 64;
    // The managed database catalog is authoritative. Filesystem is an YCPS-only emergency source.
    private String source = SOURCE_DATABASE;
    private DataSize maxPackageBytes = DataSize.ofMegabytes(200);
    private int filesystemArchiveCacheMaxEntries = 64;
    private DataSize filesystemArchiveCacheMaxBytes = DataSize.ofMegabytes(64);
    private int filesystemDownloadMaxSessions = 1000;
    private DataSize filesystemDownloadMaxArchiveBytes = DataSize.ofMegabytes(512);
    private DataSize maxExtractedBytes = DataSize.ofMegabytes(500);
    private int maxPackageEntries = 2000;
    private int minCompressionRatio = 100;
    private DataSize iconMaxBytes = DataSize.ofMegabytes(4);
    private Duration deletedRetention = Duration.ofDays(30);
    private Duration downloadSessionTtl = Duration.ofHours(24);
    private Duration logRetention = Duration.ofDays(90);
    private int downloadLimit = 60;
    private Duration downloadWindow = Duration.ofMinutes(1);
    private Duration downloadRateLimitTimeout = Duration.ofSeconds(1);

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("gateway.skills.root must not be blank");
        }
        this.root = root.strip();
    }

    public DataSize getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(DataSize maxFileBytes) {
        if (maxFileBytes == null || maxFileBytes.toBytes() <= 0) {
            throw new IllegalArgumentException("gateway.skills.max-file-bytes must be positive");
        }
        this.maxFileBytes = maxFileBytes;
    }

    public DataSize getMaxTotalBytes() {
        return maxTotalBytes;
    }

    public void setMaxTotalBytes(DataSize maxTotalBytes) {
        if (maxTotalBytes == null || maxTotalBytes.toBytes() <= 0) {
            throw new IllegalArgumentException("gateway.skills.max-total-bytes must be positive");
        }
        this.maxTotalBytes = maxTotalBytes;
    }

    public int getMaxFilesPerSkill() {
        return maxFilesPerSkill;
    }

    public void setMaxFilesPerSkill(int maxFilesPerSkill) {
        if (maxFilesPerSkill <= 0) {
            throw new IllegalArgumentException("gateway.skills.max-files-per-skill must be positive");
        }
        this.maxFilesPerSkill = maxFilesPerSkill;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        if (source == null || !SOURCES.contains(source.strip().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "gateway.skills.source must be one of " + SOURCES);
        }
        this.source = source.strip().toLowerCase(Locale.ROOT);
    }

    /** True when the catalog is served from {@code gateway.skill} instead of YCPS package roots. */
    public boolean databaseSource() {
        return SOURCE_DATABASE.equals(source);
    }

    public DataSize getMaxPackageBytes() {
        return maxPackageBytes;
    }

    public int getFilesystemArchiveCacheMaxEntries() {
        return filesystemArchiveCacheMaxEntries;
    }

    public void setFilesystemArchiveCacheMaxEntries(int filesystemArchiveCacheMaxEntries) {
        if (filesystemArchiveCacheMaxEntries <= 0) {
            throw new IllegalArgumentException(
                    "gateway.skills.filesystem-archive-cache-max-entries must be positive");
        }
        this.filesystemArchiveCacheMaxEntries = filesystemArchiveCacheMaxEntries;
    }

    public DataSize getFilesystemArchiveCacheMaxBytes() {
        return filesystemArchiveCacheMaxBytes;
    }

    public void setFilesystemArchiveCacheMaxBytes(DataSize filesystemArchiveCacheMaxBytes) {
        if (filesystemArchiveCacheMaxBytes == null
                || filesystemArchiveCacheMaxBytes.toBytes() <= 0) {
            throw new IllegalArgumentException(
                    "gateway.skills.filesystem-archive-cache-max-bytes must be positive");
        }
        this.filesystemArchiveCacheMaxBytes = filesystemArchiveCacheMaxBytes;
    }

    public int getFilesystemDownloadMaxSessions() {
        return filesystemDownloadMaxSessions;
    }

    public void setFilesystemDownloadMaxSessions(int filesystemDownloadMaxSessions) {
        if (filesystemDownloadMaxSessions <= 0) {
            throw new IllegalArgumentException(
                    "gateway.skills.filesystem-download-max-sessions must be positive");
        }
        this.filesystemDownloadMaxSessions = filesystemDownloadMaxSessions;
    }

    public DataSize getFilesystemDownloadMaxArchiveBytes() {
        return filesystemDownloadMaxArchiveBytes;
    }

    public void setFilesystemDownloadMaxArchiveBytes(DataSize filesystemDownloadMaxArchiveBytes) {
        if (filesystemDownloadMaxArchiveBytes == null
                || filesystemDownloadMaxArchiveBytes.toBytes() <= 0) {
            throw new IllegalArgumentException(
                    "gateway.skills.filesystem-download-max-archive-bytes must be positive");
        }
        this.filesystemDownloadMaxArchiveBytes = filesystemDownloadMaxArchiveBytes;
    }

    public void setMaxPackageBytes(DataSize maxPackageBytes) {
        if (maxPackageBytes == null || maxPackageBytes.toBytes() <= 0) {
            throw new IllegalArgumentException("gateway.skills.max-package-bytes must be positive");
        }
        this.maxPackageBytes = maxPackageBytes;
    }

    public DataSize getMaxExtractedBytes() {
        return maxExtractedBytes;
    }

    public void setMaxExtractedBytes(DataSize maxExtractedBytes) {
        if (maxExtractedBytes == null || maxExtractedBytes.toBytes() <= 0) {
            throw new IllegalArgumentException("gateway.skills.max-extracted-bytes must be positive");
        }
        this.maxExtractedBytes = maxExtractedBytes;
    }

    public int getMaxPackageEntries() {
        return maxPackageEntries;
    }

    public void setMaxPackageEntries(int maxPackageEntries) {
        if (maxPackageEntries <= 0) {
            throw new IllegalArgumentException("gateway.skills.max-package-entries must be positive");
        }
        this.maxPackageEntries = maxPackageEntries;
    }

    public int getMinCompressionRatio() {
        return minCompressionRatio;
    }

    public void setMinCompressionRatio(int minCompressionRatio) {
        if (minCompressionRatio <= 0) {
            throw new IllegalArgumentException("gateway.skills.min-compression-ratio must be positive");
        }
        this.minCompressionRatio = minCompressionRatio;
    }

    public DataSize getIconMaxBytes() {
        return iconMaxBytes;
    }

    public void setIconMaxBytes(DataSize iconMaxBytes) {
        if (iconMaxBytes == null || iconMaxBytes.toBytes() <= 0) {
            throw new IllegalArgumentException("gateway.skills.icon-max-bytes must be positive");
        }
        this.iconMaxBytes = iconMaxBytes;
    }

    public Duration getDeletedRetention() {
        return deletedRetention;
    }

    public void setDeletedRetention(Duration deletedRetention) {
        this.deletedRetention = requirePositive(deletedRetention, "gateway.skills.deleted-retention");
    }

    public Duration getDownloadSessionTtl() {
        return downloadSessionTtl;
    }

    public void setDownloadSessionTtl(Duration downloadSessionTtl) {
        this.downloadSessionTtl =
                requirePositive(downloadSessionTtl, "gateway.skills.download-session-ttl");
    }

    public Duration getLogRetention() {
        return logRetention;
    }

    public void setLogRetention(Duration logRetention) {
        this.logRetention = requirePositive(logRetention, "gateway.skills.log-retention");
    }

    public int getDownloadLimit() {
        return downloadLimit;
    }

    public void setDownloadLimit(int downloadLimit) {
        if (downloadLimit <= 0) {
            throw new IllegalArgumentException("gateway.skills.download-limit must be positive");
        }
        this.downloadLimit = downloadLimit;
    }

    public Duration getDownloadWindow() {
        return downloadWindow;
    }

    public void setDownloadWindow(Duration downloadWindow) {
        this.downloadWindow = requirePositive(downloadWindow, "gateway.skills.download-window");
    }

    public Duration getDownloadRateLimitTimeout() {
        return downloadRateLimitTimeout;
    }

    public void setDownloadRateLimitTimeout(Duration downloadRateLimitTimeout) {
        this.downloadRateLimitTimeout = requirePositive(
                downloadRateLimitTimeout, "gateway.skills.download-rate-limit-timeout");
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
        return value;
    }
}
