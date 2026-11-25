package com.yuzhihuihe.apiplatform.gateway.skill.api;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.MemberView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPresentation;

/**
 * Client-facing skill catalog contracts, shared by the public/authenticated client endpoints
 * ({@code /v1/skills}) and the admin console mirror ({@code /console/gateway/skills}) so both keep
 * serialising byte-for-byte the same body. Design 5.2 C1/C2 and 11.2.
 *
 * <p>Locale rule (design 3.2): when {@code locale} is English the client prefers the {@code *En}
 * overlay and falls back to the default column when it is empty; the identifier fields
 * ({@code name}/{@code code}) are never translated.
 */
public final class SkillCatalogViews {

    private SkillCatalogViews() {}

    /** {@code {"object":"list","data":[…]}} plus the OpenAI-style cursor fields (design 5.2 C1). */
    public record SkillList(String object, List<SkillSummary> data, boolean hasMore, String next) {

        public SkillList(String object, List<SkillSummary> data) {
            this(object, data, false, null);
        }
    }

    public record SkillFileInfo(String path, long bytes, String sha256) {}

    /**
     * One published category the desktop renders as a featured-scene card.
     *
     * <p>{@code code} is the stable category identity and the desktop cache key. {@code imageUrl}
     * is null until an administrator uploads a cover; in that case the desktop displays its neutral
     * card background. {@code imageUpdatedAt} is the cache validator: the desktop re-downloads a
     * cover whose timestamp moved and keeps the copy it already cached otherwise.
     */
    public record CategorySummary(
            String code,
            String name,
            String nameEn,
            int sortOrder,
            String imageUrl,
            Instant imageUpdatedAt) {}

    public record SkillFileView(String path, String encoding, long bytes, String sha256, String content) {}

    /**
     * C1 list item. {@code kind} / {@code titleEn} / {@code summaryEn} / {@code descriptionEn} /
     * {@code memberCount} are additive; the roster itself is only returned by C2 (D5/E-08).
     */
    public record SkillSummary(
            String name,
            String title,
            String description,
            String summary,
            String version,
            String category,
            List<String> tags,
            String icon,
            List<String> highlights,
            String status,
            String latestVersion,
            String publishedVersion,
            String minClientVersion,
            Long sizeBytes,
            String md5,
            String sha256,
            int revision,
            String packageFormat,
            String ycpsVersion,
            String manifestSha256,
            String dependencyLockSha256,
            String downloadUrl,
            String iconUrl,
            /** Independent portrait cache validator; metadata-only edits do not change it. */
            Instant iconUpdatedAt,
            /** Optional introduction illustration; {@code null} when none was uploaded. */
            String illustrationUrl,
            /** Independent introduction-illustration cache validator. */
            Instant illustrationUpdatedAt,
            /** Optional image demonstrating the expert's features or output. */
            String exampleImageUrl,
            /** Independent example-image cache validator. */
            Instant exampleImageUpdatedAt,
            Instant updatedAt,
            long downloadCount,
            Instant publishedAt,
            int fileCount,
            long totalBytes,
            String kind,
            String titleEn,
            String summaryEn,
            String descriptionEn,
            /** Expert business capabilities; kept small so list responses do not carry previews. */
            List<String> taskTypes,
            List<String> taskTypesEn,
            int memberCount,
            List<SkillFileInfo> files) {

        public SkillSummary {
            tags = tags == null ? List.of() : List.copyOf(tags);
            highlights = highlights == null ? List.of() : List.copyOf(highlights);
            taskTypes = taskTypes == null ? List.of() : List.copyOf(taskTypes);
            taskTypesEn = taskTypesEn == null ? List.of() : List.copyOf(taskTypesEn);
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    /** {@code /v1/skills/{code}} body; {@code object} is always {@code "skill"} (design 5.2 C2). */
    public record SkillDetail(
            String object,
            String name,
            String title,
            String description,
            String summary,
            String version,
            String category,
            List<String> tags,
            String icon,
            List<String> highlights,
            String status,
            String latestVersion,
            String publishedVersion,
            String minClientVersion,
            Long sizeBytes,
            String md5,
            String sha256,
            int revision,
            String packageFormat,
            String ycpsVersion,
            String manifestSha256,
            String dependencyLockSha256,
            String downloadUrl,
            String iconUrl,
            Instant iconUpdatedAt,
            /** Optional introduction illustration; {@code null} when none was uploaded. */
            String illustrationUrl,
            Instant illustrationUpdatedAt,
            /** Optional image demonstrating the expert's features or output. */
            String exampleImageUrl,
            Instant exampleImageUpdatedAt,
            Instant updatedAt,
            int fileCount,
            long totalBytes,
            String kind,
            String titleEn,
            String summaryEn,
            String descriptionEn,
            List<String> taskTypes,
            List<String> taskTypesEn,
            int memberCount,
            List<MemberView> members,
            /** Full Expert presentation; omitted for non-EXPERT details. */
            @JsonInclude(JsonInclude.Include.NON_NULL)
            ExpertPresentation expertPresentation,
            List<SkillFileView> files) {

        public static final String SKILL_OBJECT = "skill";

        public SkillDetail {
            object = object == null ? SKILL_OBJECT : object;
            tags = tags == null ? List.of() : List.copyOf(tags);
            highlights = highlights == null ? List.of() : List.copyOf(highlights);
            taskTypes = taskTypes == null ? List.of() : List.copyOf(taskTypes);
            taskTypesEn = taskTypesEn == null ? List.of() : List.copyOf(taskTypesEn);
            members = members == null ? List.of() : List.copyOf(members);
            files = files == null ? List.of() : List.copyOf(files);
        }
    }
}
