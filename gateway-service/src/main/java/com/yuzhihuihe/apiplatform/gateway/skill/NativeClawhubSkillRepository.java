package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** SQL boundary for byte-preserved ClawHub Skills; YCPS stays in {@code gateway.skill_version}. */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class NativeClawhubSkillRepository {

    /** Shared visibility gate for the public catalog and its artwork. */
    private static final String CATALOG_FROM = """
                           FROM gateway.native_clawhub_release release
                           JOIN gateway.native_clawhub_source source ON source.id = release.source_id
                           JOIN gateway.skill skill ON skill.id = release.skill_id
                          WHERE skill.deleted_at IS NULL
                            AND skill.status IN ('PUBLISHED', 'UNPUBLISHED')
                            AND skill.source_format = 'CLAWHUB_SKILL'
                            AND (skill.status = 'UNPUBLISHED'
                                 OR skill.published_version = release.version)
                            AND source.source_version = release.version
                            AND source.source_sha256 = release.source_sha256
                            AND source.size_bytes = release.size_bytes
                            AND source.status = 'PUBLISHED'
                            AND source.scan_status = 'PASSED'
                        """;

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    NativeClawhubSkillRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    Mono<Source> source(UUID skillId) {
        return databaseClient.sql("""
                        SELECT id, skill_id, owner, slug, source_version, display_name, description,
                               source_sha256, size_bytes, file_name, object_key, status, scan_status,
                               review_required, inspection_review_required, adapter_version,
                               decisions::text AS decisions,
                               blocking_reasons::text AS blocking_reasons, created_by, created_at,
                               published_at
                          FROM gateway.native_clawhub_source
                         WHERE skill_id = :skill_id AND status <> 'DELETED'
                         ORDER BY created_at ASC, id ASC
                         LIMIT 1
                        """)
                .bind("skill_id", skillId)
                .map((row, metadata) -> mapSource(row))
                .one();
    }

    Mono<Source> latestDraftSource(UUID skillId) {
        return databaseClient.sql("""
                        SELECT id, skill_id, owner, slug, source_version, display_name, description,
                               source_sha256, size_bytes, file_name, object_key, status, scan_status,
                               review_required, inspection_review_required, adapter_version,
                               decisions::text AS decisions,
                               blocking_reasons::text AS blocking_reasons, created_by, created_at,
                               published_at
                          FROM gateway.native_clawhub_source
                         WHERE skill_id = :skill_id AND status = 'DRAFT'
                         ORDER BY created_at DESC, id DESC
                         LIMIT 1
                        """)
                .bind("skill_id", skillId)
                .map((row, metadata) -> mapSource(row))
                .one();
    }

    Mono<Source> sourceByIdentity(String owner, String slug) {
        return databaseClient.sql("""
                        SELECT id, skill_id, owner, slug, source_version, display_name, description,
                               source_sha256, size_bytes, file_name, object_key, status, scan_status,
                               review_required, inspection_review_required, adapter_version,
                               decisions::text AS decisions,
                               blocking_reasons::text AS blocking_reasons, created_by, created_at,
                               published_at
                          FROM gateway.native_clawhub_source
                         WHERE owner = :owner AND slug = :slug AND status <> 'DELETED'
                         ORDER BY created_at ASC, id ASC
                         LIMIT 1
                        """)
                .bind("owner", owner)
                .bind("slug", slug)
                .map((row, metadata) -> mapSource(row))
                .one();
    }

    Mono<Source> sourceById(UUID sourceId) {
        return databaseClient.sql("""
                        SELECT id, skill_id, owner, slug, source_version, display_name, description,
                               source_sha256, size_bytes, file_name, object_key, status, scan_status,
                               review_required, inspection_review_required, adapter_version,
                               decisions::text AS decisions,
                               blocking_reasons::text AS blocking_reasons, created_by, created_at,
                               published_at
                          FROM gateway.native_clawhub_source
                         WHERE id = :source_id AND status <> 'DELETED'
                        """)
                .bind("source_id", sourceId)
                .map((row, metadata) -> mapSource(row))
                .one();
    }

    Mono<Source> sourceById(UUID skillId, UUID sourceId) {
        return databaseClient.sql("""
                        SELECT id, skill_id, owner, slug, source_version, display_name, description,
                               source_sha256, size_bytes, file_name, object_key, status, scan_status,
                               review_required, inspection_review_required, adapter_version,
                               decisions::text AS decisions,
                               blocking_reasons::text AS blocking_reasons, created_by, created_at,
                               published_at
                          FROM gateway.native_clawhub_source
                         WHERE skill_id = :skill_id AND id = :source_id AND status <> 'DELETED'
                        """)
                .bind("skill_id", skillId)
                .bind("source_id", sourceId)
                .map((row, metadata) -> mapSource(row))
                .one();
    }

    Mono<Source> sourceIncludingDeleted(UUID skillId, UUID sourceId) {
        return databaseClient.sql("""
                        SELECT id, skill_id, owner, slug, source_version, display_name, description,
                               source_sha256, size_bytes, file_name, object_key, status, scan_status,
                               review_required, inspection_review_required, adapter_version,
                               decisions::text AS decisions,
                               blocking_reasons::text AS blocking_reasons, created_by, created_at,
                               published_at
                          FROM gateway.native_clawhub_source
                         WHERE skill_id = :skill_id AND id = :source_id
                        """)
                .bind("skill_id", skillId)
                .bind("source_id", sourceId)
                .map((row, metadata) -> mapSource(row))
                .one();
    }

    /** Release pointer is retained after unpublish; versions.online still requires PUBLISHED. */
    Mono<UUID> currentSourceId(UUID skillId) {
        return databaseClient.sql("""
                        SELECT release.source_id
                          FROM gateway.native_clawhub_release release
                          JOIN gateway.skill skill ON skill.id = release.skill_id
                         WHERE release.skill_id = :skill_id
                           AND skill.deleted_at IS NULL
                        """)
                .bind("skill_id", skillId)
                .map((row, metadata) -> row.get("source_id", UUID.class))
                .one();
    }

    Flux<VersionEntry> versions(UUID skillId) {
        return databaseClient.sql("""
                        SELECT source.id, source.skill_id, source.owner, source.slug,
                               source.source_version, source.source_sha256, source.size_bytes,
                               source.status, source.scan_status, source.review_required,
                               source.created_at, source.published_at,
                               (release.source_id = source.id
                                   AND skill.status = 'PUBLISHED'
                                   AND skill.published_version = release.version) AS online
                          FROM gateway.native_clawhub_source source
                          LEFT JOIN gateway.native_clawhub_release release
                            ON release.skill_id = source.skill_id
                          LEFT JOIN gateway.skill skill ON skill.id = source.skill_id
                         WHERE source.skill_id = :skill_id AND source.status <> 'DELETED'
                         ORDER BY source.created_at DESC, source.id DESC
                        """)
                .bind("skill_id", skillId)
                .map((row, metadata) -> new VersionEntry(
                        row.get("id", UUID.class),
                        row.get("skill_id", UUID.class),
                        row.get("owner", String.class),
                        row.get("slug", String.class),
                        row.get("source_version", String.class),
                        trimmed(row.get("source_sha256", String.class)),
                        row.get("size_bytes", Long.class),
                        row.get("scan_status", String.class),
                        Boolean.TRUE.equals(row.get("review_required", Boolean.class)),
                        row.get("status", String.class),
                        instant(row.get("created_at", OffsetDateTime.class)),
                        instant(row.get("published_at", OffsetDateTime.class)),
                        Boolean.TRUE.equals(row.get("online", Boolean.class))))
                .all();
    }

    Mono<Long> markSkillNative(UUID skillId) {
        return databaseClient.sql("""
                        UPDATE gateway.skill
                           SET source_format = 'CLAWHUB_SKILL'
                         WHERE id = :skill_id AND deleted_at IS NULL
                        """)
                .bind("skill_id", skillId)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> markNativeVersionUploaded(UUID skillId, String version, UUID actor, Instant now) {
        return databaseClient.sql("""
                        UPDATE gateway.skill
                           SET latest_version = :version,
                               row_version = row_version + 1,
                               updated_by = :actor,
                               updated_at = :now
                         WHERE id = :skill_id
                           AND source_format = 'CLAWHUB_SKILL'
                           AND deleted_at IS NULL
                        """)
                .bind("version", version)
                .bind("actor", nullable(actor, UUID.class))
                .bind("now", now)
                .bind("skill_id", skillId)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> setSourceVersionIfMissing(UUID sourceId, String version) {
        return databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source
                           SET source_version = :version, updated_at = now()
                         WHERE id = :source_id
                           AND source_version IS NULL
                           AND status = 'DRAFT'
                        """)
                .bind("version", version)
                .bind("source_id", sourceId)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> insertSource(Source source) {
        return databaseClient.sql("""
                        INSERT INTO gateway.native_clawhub_source(
                            id, skill_id, owner, slug, source_version, display_name, description,
                            source_sha256, size_bytes, file_name, object_key, status, scan_status,
                            review_required, inspection_review_required, adapter_version,
                            decisions, blocking_reasons,
                            created_by, created_at, updated_at, published_at)
                        VALUES (
                            :id, :skill_id, :owner, :slug, :source_version, :display_name,
                            :description, :source_sha256, :size_bytes, :file_name, :object_key,
                            'DRAFT', :scan_status, :review_required, :inspection_review_required,
                            :adapter_version,
                            :decisions::jsonb, :blocking_reasons::jsonb, :created_by, :now, :now, NULL)
                        """)
                .bind("id", source.id())
                .bind("skill_id", source.skillId())
                .bind("owner", source.owner())
                .bind("slug", source.slug())
                .bind("source_version", nullable(source.version(), String.class))
                .bind("display_name", source.name())
                .bind("description", nullable(source.description(), String.class))
                .bind("source_sha256", source.sha256())
                .bind("size_bytes", source.sizeBytes())
                .bind("file_name", source.fileName())
                .bind("object_key", source.objectKey())
                .bind("scan_status", source.scanStatus())
                .bind("review_required", source.reviewRequired())
                .bind("inspection_review_required", source.inspectionReviewRequired())
                .bind("adapter_version", source.adapterVersion())
                .bind("decisions", json(source.decisions()))
                .bind("blocking_reasons", json(source.blockingReasons()))
                .bind("created_by", nullable(source.createdBy(), UUID.class))
                .bind("now", source.createdAt())
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> updateVersionScanStatus(UUID sourceId, String expectedScanStatus, String scanStatus) {
        return databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source
                           SET scan_status = :scan_status,
                               review_required = inspection_review_required OR :scan_status <> 'PASSED',
                               updated_at = now()
                         WHERE id = :source_id
                           AND status = 'DRAFT'
                           AND scan_status = :expected_scan_status
                           AND scan_status IN ('PENDING', 'FAILED')
                        """)
                .bind("scan_status", scanStatus)
                .bind("expected_scan_status", expectedScanStatus)
                .bind("source_id", sourceId)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> markDeletedDraft(UUID sourceId) {
        return databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source source
                           SET status = 'DELETED', updated_at = now()
                         WHERE source.id = :source_id
                           AND source.status = 'DRAFT'
                           AND source.published_at IS NULL
                           AND NOT EXISTS (
                               SELECT 1 FROM gateway.native_clawhub_release release
                                WHERE release.source_id = source.id)
                        """)
                .bind("source_id", sourceId)
                .fetch()
                .rowsUpdated();
    }

    Mono<Release> release(UUID skillId) {
        return databaseClient.sql("""
                        SELECT id, skill_id, source_id, version, package_format, source_sha256,
                               size_bytes, object_key, published_by, published_at
                          FROM gateway.native_clawhub_release
                         WHERE skill_id = :skill_id
                        """)
                .bind("skill_id", skillId)
                .map((row, metadata) -> mapRelease(row))
                .one();
    }

    Mono<Long> saveCurrentRelease(Release release) {
        return databaseClient.sql("""
                        INSERT INTO gateway.native_clawhub_release(
                            id, skill_id, source_id, version, package_format, source_sha256,
                            size_bytes, object_key, published_by, published_at)
                        VALUES (:id, :skill_id, :source_id, :version, 'CLAWHUB_SKILL',
                                :source_sha256, :size_bytes, :object_key, :published_by, :published_at)
                        ON CONFLICT (skill_id) DO UPDATE
                           SET source_id = EXCLUDED.source_id,
                               version = EXCLUDED.version,
                               package_format = EXCLUDED.package_format,
                               source_sha256 = EXCLUDED.source_sha256,
                               size_bytes = EXCLUDED.size_bytes,
                               object_key = EXCLUDED.object_key,
                               published_by = EXCLUDED.published_by,
                               published_at = EXCLUDED.published_at
                        """)
                .bind("id", release.id())
                .bind("skill_id", release.skillId())
                .bind("source_id", release.sourceId())
                .bind("version", release.version())
                .bind("source_sha256", release.sha256())
                .bind("size_bytes", release.sizeBytes())
                .bind("object_key", release.objectKey())
                .bind("published_by", nullable(release.publishedBy(), UUID.class))
                .bind("published_at", release.publishedAt())
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> markSourcePublished(UUID sourceId) {
        return databaseClient.sql("""
                        UPDATE gateway.native_clawhub_source
                           SET status = 'PUBLISHED',
                               published_at = COALESCE(published_at, now()),
                               updated_at = now()
                         WHERE id = :source_id AND scan_status = 'PASSED'
                        """)
                .bind("source_id", sourceId)
                .fetch()
                .rowsUpdated();
    }

    Flux<CatalogSkill> catalogSkills() {
        return databaseClient.sql("""
                        SELECT skill.code, skill.name, skill.name_en, skill.description, skill.description_en,
                                skill.summary, skill.summary_en, skill.status,
                                release.version, release.package_format,
                                release.source_sha256, release.size_bytes, source.owner, source.slug,
                                skill.icon_object_key, skill.icon_updated_at, skill.updated_at
                        """ + CATALOG_FROM + """
                          ORDER BY skill.sort_weight DESC, skill.updated_at DESC, skill.code ASC
                        """)
                .map((row, metadata) -> {
                    String status = row.get("status", String.class);
                    boolean published = "PUBLISHED".equals(status);
                    String code = row.get("code", String.class);
                    String iconKey = row.get("icon_object_key", String.class);
                    boolean hasIcon = iconKey != null && !iconKey.isBlank();
                    return new CatalogSkill(
                            code,
                            row.get("name", String.class),
                            row.get("name_en", String.class),
                            row.get("description", String.class),
                            row.get("description_en", String.class),
                            row.get("summary", String.class),
                            row.get("summary_en", String.class),
                            status,
                            row.get("version", String.class),
                            row.get("package_format", String.class),
                            published ? trimmed(row.get("source_sha256", String.class)) : null,
                            published ? row.get("size_bytes", Long.class) : null,
                            row.get("owner", String.class),
                            row.get("slug", String.class),
                            published ? "/v1/native-clawhub/skills/" + code + "/package" : null,
                            hasIcon ? "/v1/native-clawhub/skills/" + code + "/icon" : null,
                            hasIcon ? instant(row.get("icon_updated_at", OffsetDateTime.class)) : null,
                            instant(row.get("updated_at", OffsetDateTime.class)));
                })
                .all();
    }

    Mono<String> catalogIconObjectKey(String code) {
        return databaseClient.sql("""
                        SELECT skill.icon_object_key
                        """ + CATALOG_FROM + """
                            AND skill.code = :code
                            AND skill.icon_object_key IS NOT NULL
                            AND btrim(skill.icon_object_key) <> ''
                        """)
                .bind("code", code)
                .map((row, metadata) -> row.get("icon_object_key", String.class))
                .one();
    }

    Mono<PackageFile> publishedPackage(String code) {
        return databaseClient.sql("""
                        SELECT skill.id AS skill_id, skill.code, skill.name,
                               release.version, release.package_format,
                               release.source_sha256, release.size_bytes, release.object_key
                          FROM gateway.native_clawhub_release release
                          JOIN gateway.native_clawhub_source source ON source.id = release.source_id
                          JOIN gateway.skill skill ON skill.id = release.skill_id
                         WHERE skill.code = :code
                           AND skill.deleted_at IS NULL
                           AND skill.status = 'PUBLISHED'
                           AND skill.source_format = 'CLAWHUB_SKILL'
                           AND skill.published_version = release.version
                           AND source.skill_id = release.skill_id
                           AND source.source_version = release.version
                           AND source.source_sha256 = release.source_sha256
                           AND source.size_bytes = release.size_bytes
                           AND source.status = 'PUBLISHED'
                           AND source.scan_status = 'PASSED'
                        """)
                .bind("code", code)
                .map((row, metadata) -> new PackageFile(
                        row.get("skill_id", UUID.class),
                        row.get("code", String.class),
                        row.get("name", String.class),
                        row.get("version", String.class),
                        row.get("package_format", String.class),
                        trimmed(row.get("source_sha256", String.class)),
                        row.get("size_bytes", Long.class),
                        row.get("object_key", String.class)))
                .one();
    }

    private Source mapSource(io.r2dbc.spi.Row row) {
        return new Source(
                row.get("id", UUID.class),
                row.get("skill_id", UUID.class),
                row.get("owner", String.class),
                row.get("slug", String.class),
                row.get("source_version", String.class),
                row.get("display_name", String.class),
                row.get("description", String.class),
                trimmed(row.get("source_sha256", String.class)),
                row.get("size_bytes", Long.class),
                row.get("file_name", String.class),
                row.get("object_key", String.class),
                row.get("status", String.class),
                row.get("scan_status", String.class),
                row.get("review_required", Boolean.class),
                row.get("inspection_review_required", Boolean.class),
                row.get("adapter_version", String.class),
                readList(row.get("decisions", String.class)),
                readList(row.get("blocking_reasons", String.class)),
                row.get("created_by", UUID.class),
                instant(row.get("created_at", OffsetDateTime.class)),
                instant(row.get("published_at", OffsetDateTime.class)));
    }

    private static Release mapRelease(io.r2dbc.spi.Row row) {
        return new Release(
                row.get("id", UUID.class),
                row.get("skill_id", UUID.class),
                row.get("source_id", UUID.class),
                row.get("version", String.class),
                row.get("package_format", String.class),
                trimmed(row.get("source_sha256", String.class)),
                row.get("size_bytes", Long.class),
                row.get("object_key", String.class),
                row.get("published_by", UUID.class),
                instant(row.get("published_at", OffsetDateTime.class)));
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception error) {
            throw new IllegalArgumentException("native_clawhub_json_invalid", error);
        }
    }

    private List<String> readList(String value) {
        if (value == null) return List.of();
        try {
            return objectMapper.readValue(value,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception error) {
            return List.of();
        }
    }

    private static Parameter nullable(Object value, Class<?> type) {
        return value == null ? Parameter.empty(type) : Parameter.from(value);
    }

    private static String trimmed(String value) {
        return value == null ? null : value.strip();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    record Source(
            UUID id,
            UUID skillId,
            String owner,
            String slug,
            String version,
            String name,
            String description,
            String sha256,
            long sizeBytes,
            String fileName,
            String objectKey,
            String status,
            String scanStatus,
            boolean reviewRequired,
            boolean inspectionReviewRequired,
            String adapterVersion,
            List<String> decisions,
            List<String> blockingReasons,
            UUID createdBy,
            Instant createdAt,
            Instant publishedAt) {}

    record VersionEntry(
            UUID id,
            UUID skillId,
            String owner,
            String slug,
            String version,
            String sourceSha256,
            long sizeBytes,
            String scanStatus,
            boolean reviewRequired,
            String status,
            Instant createdAt,
            Instant publishedAt,
            boolean online) {}

    record Release(
            UUID id,
            UUID skillId,
            UUID sourceId,
            String version,
            String packageFormat,
            String sha256,
            long sizeBytes,
            String objectKey,
            UUID publishedBy,
            Instant publishedAt) {}

    record CatalogSkill(
            String code,
            String name,
            @JsonInclude(JsonInclude.Include.NON_NULL) String nameEn,
            String description,
            String descriptionEn,
            String summary,
            String summaryEn,
            String status,
            String version,
            String packageFormat,
            @JsonInclude(JsonInclude.Include.NON_NULL) String sha256,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long sizeBytes,
            String owner,
            String slug,
            @JsonInclude(JsonInclude.Include.NON_NULL) String downloadUrl,
            @JsonInclude(JsonInclude.Include.NON_NULL) String iconUrl,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant iconUpdatedAt,
            Instant updatedAt) {}

    record PackageFile(
            UUID skillId,
            String code,
            String name,
            String version,
            String packageFormat,
            String sha256,
            long sizeBytes,
            String objectKey) {}
}
