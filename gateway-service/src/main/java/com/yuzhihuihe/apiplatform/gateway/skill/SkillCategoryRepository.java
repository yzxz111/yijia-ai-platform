package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The administrator-managed category dictionary behind {@code gateway.skill_category}.
 *
 * <p>{@code gateway.skill.category} stays a free-text column: a YCPS package carries its category
 * as plain text, so an upload never resolves a foreign key. This repository owns the vocabulary the
 * console offers and validates against, plus the occupancy check that stops an operator from
 * deleting a category that packages still use.
 */
@Repository
public class SkillCategoryRepository {

    private static final String SELECT_CATEGORY = """
            SELECT id, kind, code, name, name_en, sort_order, enabled, row_version,
                   image_object_key, image_updated_at, created_at, updated_at
            """;

    private static final String FROM_CATEGORY = """
            FROM gateway.skill_category
            WHERE deleted_at IS NULL
            """;

    private final DatabaseClient databaseClient;

    public SkillCategoryRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /**
     * One kind's live vocabulary in display order. {@code kind} is already normalised by the caller
     * to {@code SKILL} or {@code EXPERT}, so no defensive branch is needed here.
     */
    Flux<CategoryRow> list(String kind) {
        return databaseClient
                .sql(SELECT_CATEGORY + FROM_CATEGORY
                        + " AND kind = :kind ORDER BY sort_order ASC, name ASC")
                .bind("kind", kind)
                .map((row, metadata) -> category(row))
                .all();
    }

    Mono<CategoryRow> category(UUID id) {
        return databaseClient
                .sql(SELECT_CATEGORY + FROM_CATEGORY + " AND id = :id")
                .bind("id", id)
                .map((row, metadata) -> category(row))
                .one();
    }

    /**
     * Locks the category row for the duration of the caller's transaction.
     *
     * <p>A rename is read-modify-write across two tables ({@code skill_category} and every live
     * {@code skill} carrying the old name), so the row has to stay still while the package rows are
     * migrated; otherwise two concurrent renames could each migrate only part of the packages.
     */
    Mono<CategoryRow> lockCategory(UUID id) {
        return databaseClient
                .sql(SELECT_CATEGORY + FROM_CATEGORY + " AND id = :id FOR UPDATE")
                .bind("id", id)
                .map((row, metadata) -> category(row))
                .one();
    }

    /** Case-insensitive name probe, so "内容创作" and " 内容创作 " collide as intended. */
    Mono<CategoryRow> byName(String kind, String name) {
        return databaseClient
                .sql(SELECT_CATEGORY + FROM_CATEGORY
                        + " AND kind = :kind AND lower(name) = lower(:name)")
                .bind("kind", kind)
                .bind("name", name)
                .map((row, metadata) -> category(row))
                .one();
    }

    Mono<CategoryRow> byCode(String kind, String code) {
        return databaseClient
                .sql(SELECT_CATEGORY + FROM_CATEGORY
                        + " AND kind = :kind AND lower(code) = lower(:code)")
                .bind("kind", kind)
                .bind("code", code)
                .map((row, metadata) -> category(row))
                .one();
    }

    /** Next display position: one past the current maximum, so new categories append. */
    Mono<Integer> nextSortOrder(String kind) {
        return databaseClient
                .sql("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM gateway.skill_category "
                        + "WHERE deleted_at IS NULL AND kind = :kind")
                .bind("kind", kind)
                .map((row, metadata) -> row.get(0, Integer.class))
                .one()
                .defaultIfEmpty(1);
    }

    Mono<Long> insert(
            UUID id, String kind, String code, String name, String nameEn, int sortOrder,
            UUID actor, Instant now) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient
                .sql("""
                        INSERT INTO gateway.skill_category
                            (id, kind, code, name, name_en, sort_order, enabled,
                             created_by, created_at, updated_by, updated_at)
                        VALUES
                            (:id, :kind, :code, :name, :nameEn, :sortOrder, true,
                             :actor, :now, :actor, :now)
                        """)
                .bind("id", id)
                .bind("kind", kind)
                .bind("code", code)
                .bind("name", name)
                .bind("sortOrder", sortOrder)
                .bind("actor", actor)
                .bind("now", now);
        return bindNullable(spec, "nameEn", nullable(nameEn))
                .fetch()
                .rowsUpdated();
    }

    /**
     * Logical delete. The caller must have already proven the category is unused, because this
     * statement cannot see {@code gateway.skill} and would otherwise silently orphan packages.
     */
    Mono<Long> markDeleted(UUID id, UUID actor, Instant now) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill_category
                        SET deleted_at = :now,
                            deleted_by = :actor,
                            enabled = false,
                            row_version = row_version + 1,
                            updated_by = :actor,
                            updated_at = :now
                        WHERE id = :id AND deleted_at IS NULL
                        """)
                .bind("id", id)
                .bind("actor", actor)
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    /**
     * Renames a category and optionally replaces its cover pointer.
     *
     * <p>{@code name} is the value written into {@code gateway.skill.category}, so the caller has
     * already re-pointed every live package from the old name to the new one. The cover columns are
     * only written when a new image was uploaded, so a plain rename never clears an existing cover.
     */
    Mono<Long> update(
            UUID id, String name, String nameEn, String imageObjectKey, boolean replaceImage,
            Instant now) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient
                .sql("""
                        UPDATE gateway.skill_category
                        SET name = :name,
                            name_en = :nameEn,
                            image_object_key = CASE WHEN :replaceImage THEN :imageObjectKey
                                                   ELSE image_object_key END,
                            image_updated_at = CASE WHEN :replaceImage THEN :now
                                                    ELSE image_updated_at END,
                            row_version = row_version + 1,
                            updated_at = :now
                        WHERE id = :id AND deleted_at IS NULL
                        """)
                .bind("id", id)
                .bind("name", name)
                .bind("replaceImage", replaceImage)
                .bind("now", now);
        return bindNullable(bindNullable(spec, "nameEn", nullable(nameEn)),
                        "imageObjectKey", nullable(imageObjectKey))
                .fetch()
                .rowsUpdated();
    }

    /**
     * Re-points every live package that still carries the old name.
     *
     * <p>This is the rename cascade: without it the packages would keep a label the console no
     * longer offers, so they would fall out of every group. Scoped to the category's own kinds, so a
     * skill rename can never re-group an expert.
     */
    Mono<Long> renamePackages(String kind, String fromName, String toName) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill
                        SET category = :toName,
                            row_version = row_version + 1
                        WHERE deleted_at IS NULL
                          AND category IS NOT NULL
                          AND lower(category) = lower(:fromName)
                          AND kind = ANY(:kinds)
                        """)
                .bind("fromName", fromName)
                .bind("toName", toName)
                .bind("kinds", kinds(kind))
                .fetch()
                .rowsUpdated();
    }

    /**
     * How many live <em>published</em> packages still carry this category name.
     *
     * <p>This is the desktop card gate: the featured-scene section only offers a category that has
     * at least one expert behind it, so a card never renders as an empty entry.
     */
    Mono<Long> publishedUsageCount(String kind, String name) {
        return databaseClient
                .sql("""
                        SELECT count(*) FROM gateway.skill
                        WHERE deleted_at IS NULL
                          AND status = 'PUBLISHED'
                          AND category IS NOT NULL
                          AND lower(category) = lower(:name)
                          AND kind = ANY(:kinds)
                        """)
                .bind("name", name)
                .bind("kinds", kinds(kind))
                .map((row, metadata) -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    /** The object key of a category cover, or empty when none was uploaded. */
    Mono<String> imageObjectKey(UUID id) {
        return databaseClient
                .sql("""
                        SELECT image_object_key FROM gateway.skill_category
                        WHERE id = :id AND deleted_at IS NULL AND image_object_key IS NOT NULL
                        """)
                .bind("id", id)
                .map((row, metadata) -> row.get("image_object_key", String.class))
                .one();
    }

    /**
     * How many live packages still carry this category name, scoped to the kind's own package
     * kinds: a SKILL category is only occupied by skills, an EXPERT category by experts and teams.
     * This is the guard that turns "delete" into a readable refusal instead of a dangling label.
     */
    Mono<Long> usageCount(String kind, String name) {
        return databaseClient
                .sql("""
                        SELECT count(*) FROM gateway.skill
                        WHERE deleted_at IS NULL
                          AND category IS NOT NULL
                          AND lower(category) = lower(:name)
                          AND kind = ANY(:kinds)
                        """)
                .bind("name", name)
                .bind("kinds", kinds(kind))
                .map((row, metadata) -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    /**
     * The packages still using a category, so the console can name them in the refusal instead of
     * only reporting a count.
     */
    Flux<String> usageCodes(String kind, String name, int limit) {
        return databaseClient
                .sql("""
                        SELECT code FROM gateway.skill
                        WHERE deleted_at IS NULL
                          AND category IS NOT NULL
                          AND lower(category) = lower(:name)
                          AND kind = ANY(:kinds)
                        ORDER BY code ASC
                        LIMIT :limit
                        """)
                .bind("name", name)
                .bind("kinds", kinds(kind))
                .bind("limit", limit)
                .map((row, metadata) -> row.get("code", String.class))
                .all();
    }

    /** The package kinds one vocabulary scope covers: teams share the expert list. */
    private static String[] kinds(String kind) {
        return "SKILL".equals(kind) ? new String[] {"SKILL"} : new String[] {"EXPERT", "TEAM"};
    }

    private CategoryRow category(io.r2dbc.spi.Row row) {
        return new CategoryRow(
                row.get("id", UUID.class),
                row.get("kind", String.class),
                row.get("code", String.class),
                row.get("name", String.class),
                row.get("name_en", String.class),
                row.get("sort_order", Integer.class) == null ? 0 : row.get("sort_order", Integer.class),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                row.get("row_version", Integer.class) == null ? 1 : row.get("row_version", Integer.class),
                row.get("image_object_key", String.class),
                row.get("image_updated_at", Instant.class));
    }

    /**
     * Binds an optional column. {@code bind} rejects null and would surface as a 500, so a
     * nullable value is always bound through {@code bindNull} instead.
     */
    private static org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec bindNullable(
            org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec spec,
            String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** One dictionary entry. */
    public record CategoryRow(
            UUID id,
            String kind,
            String code,
            String name,
            String nameEn,
            int sortOrder,
            boolean enabled,
            int rowVersion,
            String imageObjectKey,
            Instant imageUpdatedAt) {}
}
