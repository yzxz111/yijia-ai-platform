package com.yuzhihuihe.apiplatform.gateway.skill.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/** Response contracts for {@code /console/gateway/admin/skills/**} (design 5.1 A1-A14). */
public final class SkillAdminResponses {

    private SkillAdminResponses() {}

    /** Same shape as the existing gateway admin page model: {@code {items,total,page,size}}. */
    public record PageResult<T>(List<T> items, long total, int page, int size) {}

    /**
     * One recoverable tombstone in the 30-day recycle bin. It deliberately exposes only catalog
     * metadata: deleted packages and images stay inaccessible until the row is restored.
     */
    public record DeletedSkillView(
            UUID id,
            String code,
            String name,
            String nameEn,
            String category,
            String kind,
            Instant deletedAt,
            Instant expiresAt) {}

    /**
     * A1/A3/A4. A3/A14 return the read-only package manifest and the roster; A1 does not (D5/E-08).
     *
     * <p>Both reads share this record, so the two shapes are produced by what the projection writes
     * rather than by a second DTO: the list sends {@code null} for both fields, and
     * {@link ManifestSerializer} plus {@code NON_NULL} on {@code members} turn that into
     * {@code "manifest":null} without a {@code members} key. A3/A14 pass the parsed document (or
     * {@code null} when the package had no {@code plugin.json}) and always a roster list.
     */
    public record SkillView(
            UUID id,
            String code,
            String name,
            String nameEn,
            String category,
            List<String> tags,
            String summary,
            String summaryEn,
            String description,
            String descriptionEn,
            String icon,
            String iconUrl,
            Instant iconUpdatedAt,
            /**
             * Optional package introduction illustration. {@code null} means none uploaded, which is
             * the normal case: it is artwork, not a functional asset.
             */
            String illustrationUrl,
            Instant illustrationUpdatedAt,
            /** Optional image demonstrating the expert's features or output. */
            String exampleImageUrl,
            Instant exampleImageUpdatedAt,
            String kind,
            String status,
            String publishedVersion,
            String latestVersion,
            String minClientVersion,
            Long sizeBytes,
            String md5,
            String sha256,
            long downloadCount,
            int sortWeight,
            int rowVersion,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            Instant unpublishedAt,
            int memberCount,
            /**
             * G4: how many locked edges this package's published revision declares (out-degree),
             * and how many usable revisions elsewhere lock this package's code (in-degree). Both are
             * aggregated by the list query so an operator can judge whether a package is safe to
             * unpublish without drilling into A20 per package.
             */
            int dependencyCount,
            int dependentCount,
            @JsonSerialize(using = ManifestSerializer.class)
            JsonNode manifest,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<MemberView> members,
            List<SkillVersionView> versions,
            String sourceFormat,
            boolean sourceReviewRequired,
            String sourceVersion,
            String sourceScanStatus) {
        public SkillView {
            tags = tags == null ? List.of() : List.copyOf(tags);
            // `null` survives the compact constructor: it is the A1 signal that the roster is not part
            // of the list projection, and NON_NULL above turns that into an absent key. A3/A14 always
            // pass a list, so an expert without members still answers `"members":[]`.
            members = members == null ? null : List.copyOf(members);
            versions = versions == null ? List.of() : List.copyOf(versions);
            memberCount = members == null || members.isEmpty() ? memberCount : members.size();
            // G4: the counters are a read-only convenience. A3/A14 rebuild a single package from an
            // already-loaded SkillView, so they carry the list projection's counts over unchanged; a
            // negative value would mean the aggregate was never computed, which is never intended.
            dependencyCount = Math.max(dependencyCount, 0);
            dependentCount = Math.max(dependentCount, 0);
        }
    }

    /**
     * One roster entry. A member is a reference to another expert package: {@code memberKey} names
     * that package, and {@code avatarUrl} points at that package's own icon — the console preview
     * endpoint on the admin surface, {@code /v1/skills/{memberKey}/icon} on the client surface.
     * There is no per-member image, so the URL is always present.
     */
    public record MemberView(
            UUID id,
            String memberKey,
            String name,
            String nameEn,
            String profession,
            String professionEn,
            String role,
            int sortOrder,
            String avatarUrl) {}

    public record SkillVersionView(
            UUID id,
            UUID skillId,
            String version,
            int revision,
            String status,
            Long sizeBytes,
            String md5,
            String sha256,
            String packageFormat,
            String ycpsVersion,
            String packageKind,
            JsonNode pluginManifest,
            JsonNode runtimeManifest,
            String manifestSha256,
            String dependencyLockSha256,
            boolean sealed,
            Instant sealedAt,
            int fileCount,
            long extractedBytes,
            String minClientVersion,
            String changelog,
            String scanStatus,
            long downloadCount,
            boolean online,
            Instant createdAt) {}

    public record DownloadView(
            UUID id,
            UUID skillId,
            UUID skillVersionId,
            String version,
            UUID userId,
            UUID apiKeyId,
            String deviceIdHash,
            String status,
            long contentLength,
            long servedBytes,
            Long rangeFrom,
            Long rangeTo,
            boolean md5Verified,
            boolean sha256Verified,
            boolean installed,
            String failureCode,
            String clientVersion,
            String clientPlatform,
            Instant startedAt,
            Instant completedAt) {}

    /**
     * A19. One resolved edge of {@code gateway.skill_version_dependency}: the locked identity from
     * the package plus the live state of the target version, so the review dialog can flag a target
     * that has been deleted or is no longer the online revision.
     */
    public record SkillDependencyView(
            String key,
            String relation,
            boolean required,
            String targetCode,
            String targetKind,
            String targetVersion,
            int targetRevision,
            String targetSha256,
            UUID targetVersionId,
            UUID targetSkillId,
            /** Target version status; {@code DELETED} when the row was logically removed. */
            String targetVersionStatus,
            boolean targetOnline) {}

    /** A19. The dependency manifest of one immutable revision. */
    public record SkillVersionDependenciesView(
            UUID skillId,
            String code,
            String kind,
            UUID versionId,
            String version,
            int revision,
            boolean sealed,
            List<SkillDependencyView> dependencies) {}

    /** A20. One revision of another skill that locks the reviewed code. */
    public record SkillDependentEntry(
            UUID ownerSkillId,
            String ownerCode,
            String ownerKind,
            UUID ownerVersionId,
            String ownerVersion,
            int ownerRevision,
            String ownerStatus,
            boolean ownerOnline,
            String key,
            String relation,
            boolean required,
            String targetVersion,
            int targetRevision) {}

    /** A20. Reverse dependencies of one skill: unpublished/deleted owners are omitted. */
    public record SkillDependentsView(
            UUID skillId,
            String code,
            List<SkillDependentEntry> dependents) {}

    /**
     * A22. The record already holding a code. Codes are globally unique across kinds, so the
     * console needs the holder's kind to explain the clash instead of asking for a blind retry.
     */
    public record SkillCodeHolderView(
            UUID id,
            String code,
            String name,
            String kind,
            String status) {}

    /** A22. Pre-flight answer for a code the operator is about to use. */
    public record SkillCodeAvailabilityView(
            String code,
            boolean available,
            @JsonInclude(JsonInclude.Include.NON_NULL) SkillCodeHolderView occupiedBy) {}

    /** A21. One edge of the package's dependency graph, resolved against the live target. */
    public record SkillRelationEdgeView(
            String key,
            String relation,
            boolean required,
            String code,
            String kind,
            String version,
            int revision,
            String status,
            UUID skillId) {}

    /**
     * A21. Both directions of one package's relations in a single read.
     *
     * <p>{@code dependsOn} is the published revision's dependency edges (A19 needs a versionId,
     * this does not), and {@code dependedOnBy} mirrors A20. Together they answer "what does this
     * package need" and "what would break if it went away" without visiting the version page.
     *
     * <p>{@code version}/{@code revision} are absent for a package with no published revision,
     * which is why they carry {@code NON_NULL} rather than serialising as {@code null}.
     */
    public record SkillRelationsView(
            UUID skillId,
            String code,
            String kind,
            @JsonInclude(JsonInclude.Include.NON_NULL) String version,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer revision,
            List<SkillRelationEdgeView> dependsOn,
            List<SkillDependentEntry> dependedOnBy) {}

    /**
     * A23/A24. One administrator-managed category of a kind's vocabulary.
     *
     * <p>{@code name} is the value packages carry in {@code gateway.skill.category}, and
     * {@code usageCount} is how many live packages currently carry it. The console uses the count to
     * disable the delete action before it is attempted, and A24 re-checks it server-side so a stale
     * console can never orphan a package.
     *
     * <p>{@code imageUrl} is null until an administrator uploads a cover, and
     * {@code imageUpdatedAt} is the cache validator the desktop compares against the copy it cached.
     */
    public record SkillCategoryView(
            UUID id,
            String kind,
            String code,
            String name,
            String nameEn,
            int sortOrder,
            boolean enabled,
            int usageCount,
            @JsonInclude(JsonInclude.Include.NON_NULL) String imageUrl,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant imageUpdatedAt) {}

    /** A23/A24. One kind's whole vocabulary plus the categories currently in use. */
    public record SkillCategoryListView(
            String kind,
            List<SkillCategoryView> categories) {}

    /**
     * A27. What a YCPS package declares, read <em>before</em> any record exists.
     *
     * <p>Creating a package used to be pure typing: the operator retyped the code, title and summary
     * that the archive already carried, and only discovered a typo when the upload refused the
     * identity mismatch. This view lets the console prefill the create form from the archive itself,
     * so the operator confirms rather than transcribes.
     *
     * <p>The projection is deliberately the same field set the ingest path would seed
     * ({@code SkillPackageInspector.Report} + {@code CatalogCopy}), so what the operator sees here is
     * exactly what the catalog would end up holding. {@code title}/{@code summary} are the
     * default-language copy (the frontmatter {@code description} and any {@code *.zh} keys);
     * {@code titleEn}/{@code summaryEn} are the {@code *.en} keys, which are absent for a package
     * that declares only one language.
     *
     * <p>{@code description}/{@code descriptionEn} repeat the summary rather than exposing a second
     * package field, because the ingest fan-out
     * ({@code SkillPackageIngestService.seedCatalogCopy}) already writes the one-line blurb into both
     * the {@code summary} and {@code description} columns. Reporting all four here keeps that rule on
     * the server: the console copies field for field instead of re-implementing the fan-out.
     *
     * <p>{@code codeAvailable}/{@code occupiedBy} answer the A22 question in the same round trip,
     * because a code clash is the one conflict the archive itself cannot reveal.
     */
    public record SkillPackageInspectView(
            String code,
            String version,
            String kind,
            @JsonInclude(JsonInclude.Include.NON_NULL) String minClientVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) String title,
            @JsonInclude(JsonInclude.Include.NON_NULL) String titleEn,
            @JsonInclude(JsonInclude.Include.NON_NULL) String summary,
            @JsonInclude(JsonInclude.Include.NON_NULL) String summaryEn,
            @JsonInclude(JsonInclude.Include.NON_NULL) String description,
            @JsonInclude(JsonInclude.Include.NON_NULL) String descriptionEn,
            List<String> keywords,
            int fileCount,
            long extractedBytes,
            long sizeBytes,
            String sha256,
            String md5,
            @JsonInclude(JsonInclude.Include.NON_NULL) String manifestSha256,
            boolean codeAvailable,
            @JsonInclude(JsonInclude.Include.NON_NULL) SkillCodeHolderView occupiedBy) {}
}
