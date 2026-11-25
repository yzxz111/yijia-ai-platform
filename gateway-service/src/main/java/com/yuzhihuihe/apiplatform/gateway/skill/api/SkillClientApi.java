package com.yuzhihuihe.apiplatform.gateway.skill.api;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;

/** Request/response contracts for the API-key skill download endpoints (design 5.2 C4-C6). */
public final class SkillClientApi {

    private SkillClientApi() {}

    /**
     * Optional exact-dependency selector. {@code parentDownloadId} binds the requested archived
     * revision to a dependency edge in an already-authorized parent session.
     */
    public record DownloadRequest(
            String parentDownloadId,
            String version,
            Integer revision,
            String kind,
            String sha256) {}

    /** C4 body. */
    public record DownloadSession(
            String downloadId,
            String version,
            long sizeBytes,
            String md5,
            String sha256,
            int revision,
            String kind,
            String packageFormat,
            String ycpsVersion,
            String manifestSha256,
            String dependencyLockSha256,
            JsonNode dependencies,
            String url,
            long expiresAt) {}

    /**
     * C6 body. The canonical names are camelCase like the rest of the API; the design document's
     * snake_case spellings are accepted as aliases so both desktop-client generations work.
     */
    public record DownloadReport(
            String status,
            @JsonAlias("bytes_received") Long bytesReceived,
            @JsonAlias("md5_verified") Boolean md5Verified,
            @JsonAlias("sha256_verified") Boolean sha256Verified,
            Boolean installed,
            @JsonAlias("client_version") String clientVersion,
            @JsonAlias("client_platform") String clientPlatform,
            @JsonAlias("failure_code") String failureCode) {}

    /** C6 response. */
    public record DownloadCompletion(
            String downloadId,
            String status,
            boolean counted,
            @JsonAlias("md5_verified") boolean md5Verified,
            @JsonAlias("sha256_verified") boolean sha256Verified,
            boolean installed) {}

    /** C3 body. */
    public record VersionList(String object, java.util.List<VersionSummary> data) {}

    public record VersionSummary(
            String version,
            int revision,
            long sizeBytes,
            String md5,
            String sha256,
            String minClientVersion,
            String changelog,
            Instant createdAt) {}
}
