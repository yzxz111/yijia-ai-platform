package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.CreateSkillCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.SkillListQuery;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.UpdateSkillCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.PageResult;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.DeletedSkillView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillVersionView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillCatalogViews.SkillSummary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Parameterised R2DBC SQL for {@code gateway.skill}: admin pagination/search/whitelisted sorting,
 * conditional state transitions with the {@code row_version} optimistic lock, the client-facing
 * published catalog read and the gateway admin audit trail. Business decisions stay in the service.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillRepository {

    private static final Map<String, String> SORTS =
            Map.ofEntries(
                    Map.entry("updated_at", "skill.updated_at"),
                    Map.entry("created_at", "skill.created_at"),
                    Map.entry("download_count", "skill.download_count"),
                    Map.entry("sort_weight", "skill.sort_weight"),
                    Map.entry("name", "skill.name"),
                    Map.entry("code", "skill.code"),
                    Map.entry("status", "skill.status"),
                    Map.entry("published_version", "skill.published_version"));

    private static final Set<String> STATUSES =
            Set.of("DRAFT", "PUBLISHED", "UNPUBLISHED", "DELETED");

    /** D1: the frozen three-value presentation kind. */
    static final Set<String> KINDS = Set.of("SKILL", "EXPERT", "TEAM");

    static final String KIND_SKILL = "SKILL";

    private static final String SELECT_SKILL = """
            SELECT skill.id, skill.code, skill.name, skill.name_en, skill.category,
                   COALESCE(to_json(skill.tags), '[]')::text AS tags,
                   skill.summary, skill.summary_en, skill.description, skill.description_en,
                   skill.icon, skill.icon_object_key, skill.icon_updated_at,
                   skill.illustration_object_key, skill.illustration_updated_at,
                   skill.example_image_object_key, skill.example_image_updated_at,
                   skill.kind, skill.manifest::text AS manifest,
                   skill.min_client_version, skill.status, skill.source_format, skill.published_version,
                   skill.latest_version, skill.download_count, skill.sort_weight,
                   skill.row_version, skill.created_at, skill.updated_at,
                   skill.published_at, skill.unpublished_at,
                   COALESCE(native_source.review_required, false) AS source_review_required,
                   native_source.source_version AS source_version,
                   native_source.scan_status AS source_scan_status,
                   (SELECT count(*) FROM gateway.skill_member member
                     WHERE member.skill_id = skill.id) AS member_count,
                   -- G4: out-degree/in-degree of the package's relation graph. The console needs
                   -- both on the list so an unpublish/delete can be triaged without opening A20
                   -- per package. The in-degree counts exactly the rows A20 reports as blocking,
                   -- so the two surfaces never disagree.
                   (SELECT count(*) FROM gateway.skill_version_dependency dependency
                     JOIN gateway.skill_version owner ON owner.id = dependency.owner_version_id
                      WHERE owner.skill_id = skill.id
                        AND owner.deleted_at IS NULL
                        AND owner.status = 'PUBLISHED'
                        AND owner.version = skill.published_version) AS dependency_count,
                   (SELECT count(*) FROM gateway.skill_version_dependency dependency
                     JOIN gateway.skill_version owner ON owner.id = dependency.owner_version_id
                     WHERE dependency.target_code = skill.code
                       AND owner.deleted_at IS NULL
                       AND owner.status IN ('READY', 'PUBLISHED', 'ARCHIVED')) AS dependent_count,
                   -- The online join is the client visibility gate, so it stays strictly bound to
                   -- published_version. The package metadata below falls back to the newest sealed
                   -- revision through `latest`: an unpublish clears published_version but keeps the
                   -- artifact on disk, and the console must keep showing its size instead of "—".
                   online.id AS online_version_id,
                   -- Native default ZIP export selects the release even after unpublish;
                   -- only skills never published export their newest draft source.
                   CASE WHEN skill.source_format = 'CLAWHUB_SKILL'
                        THEN COALESCE(native_release.size_bytes, native_source.size_bytes)
                        ELSE COALESCE(online.size_bytes, latest.size_bytes)
                   END AS online_size_bytes,
                   COALESCE(online.md5, latest.md5) AS online_md5,
                   COALESCE(online.sha256, latest.sha256) AS online_sha256,
                   COALESCE(online.revision, latest.revision) AS online_revision,
                   COALESCE(online.package_format, latest.package_format) AS online_package_format,
                   COALESCE(online.ycps_version, latest.ycps_version) AS online_ycps_version,
                   COALESCE(online.manifest_sha256, latest.manifest_sha256) AS online_manifest_sha256,
                   COALESCE(online.dependency_lock_sha256, latest.dependency_lock_sha256)
                       AS online_dependency_lock_sha256,
                   COALESCE(online.file_count, latest.file_count) AS online_file_count,
                   COALESCE(online.min_client_version, latest.min_client_version)
                       AS online_min_client_version
            """;

    private static final String FROM_SKILL = """
            FROM gateway.skill skill
            LEFT JOIN gateway.skill_version online
             ON online.skill_id = skill.id
             AND online.deleted_at IS NULL
             AND online.version = skill.published_version
             AND online.status = 'PUBLISHED'
             AND online.package_format = 'YCPS'
             AND online.ycps_version IN ('1.0.0', '1.1.0')
             AND online.sealed
            LEFT JOIN LATERAL (
                SELECT candidate.id, candidate.version, candidate.revision,
                       candidate.size_bytes, candidate.md5, candidate.sha256,
                       candidate.package_format, candidate.ycps_version,
                       candidate.manifest_sha256, candidate.dependency_lock_sha256,
                       candidate.file_count, candidate.min_client_version
                  FROM gateway.skill_version candidate
                 WHERE candidate.skill_id = skill.id
                   AND candidate.deleted_at IS NULL
                   AND candidate.package_format = 'YCPS'
                   AND candidate.ycps_version IN ('1.0.0', '1.1.0')
                   AND candidate.sealed
                  ORDER BY (candidate.version = skill.published_version) DESC NULLS LAST,
                           candidate.revision DESC, candidate.created_at DESC,
                           candidate.version DESC, candidate.id DESC
                 LIMIT 1
            ) latest ON TRUE
            LEFT JOIN gateway.native_clawhub_release native_release
              ON native_release.skill_id = skill.id
             AND skill.source_format = 'CLAWHUB_SKILL'
            LEFT JOIN LATERAL (
                SELECT source.review_required, source.source_version, source.scan_status,
                       source.size_bytes
                  FROM gateway.native_clawhub_source source
                  LEFT JOIN gateway.native_clawhub_release release
                    ON release.skill_id = skill.id
                 WHERE source.skill_id = skill.id
                   AND source.status <> 'DELETED'
                 ORDER BY (skill.status = 'PUBLISHED'
                           AND source.id = release.source_id) DESC,
                          (source.status = 'DRAFT') DESC,
                          (source.id = release.source_id) DESC,
                          source.created_at DESC, source.id DESC
                 LIMIT 1
            ) native_source ON skill.source_format = 'CLAWHUB_SKILL'
            """;

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    Mono<PageResult<SkillView>> listSkills(SkillListQuery request) {
        PageRequest page = page(request.page(), request.size());
        String status = statusFilter(request.status());
        String category = blank(request.category()) ? null : request.category().strip();
        // A1 accepts one kind or a comma-separated set ("EXPERT,TEAM"): the 专家 page shows experts and
        // expert teams, while the Skill page pins kind=SKILL. An empty set means "no filter".
        List<String> kinds = kindFilter(request.kind());
        String search = search(request.query());
        String where = """
                WHERE skill.deleted_at IS NULL
                  AND (:status IS NULL OR skill.status = :status)
                  AND (:category IS NULL OR skill.category = :category)
                  AND (:kinds IS NULL OR skill.kind = ANY(:kinds))
                  AND (:search IS NULL
                       OR lower(skill.code) LIKE :search ESCAPE '!'
                       OR lower(skill.name) LIKE :search ESCAPE '!'
                       OR lower(COALESCE(skill.name_en, '')) LIKE :search ESCAPE '!'
                       OR lower(COALESCE(skill.summary, '')) LIKE :search ESCAPE '!')
                """;
        String ordering = ordering(request);
        DatabaseClient.GenericExecuteSpec items = databaseClient
                .sql(SELECT_SKILL + FROM_SKILL + where + ordering + " LIMIT :limit OFFSET :offset")
                .bind("limit", page.size())
                .bind("offset", page.offset());
        DatabaseClient.GenericExecuteSpec count =
                databaseClient.sql("SELECT count(*) " + FROM_SKILL + where);
        Object kindBinding = kinds.isEmpty() ? null : kinds.toArray(String[]::new);
        items = bindFilters(items, status, category, kindBinding, search);
        count = bindFilters(count, status, category, kindBinding, search);
        Mono<List<SkillView>> rows = items.map((row, metadata) -> skillView(row)).all().collectList();
        Mono<Long> total = count.map((row, metadata) -> row.get(0, Long.class)).one();
        return Mono.zip(rows, total)
                .map(result -> new PageResult<>(
                        result.getT1(), result.getT2(), page.page(), page.size()));
    }

    Mono<SkillView> skill(UUID id) {
        return databaseClient
                .sql(SELECT_SKILL + FROM_SKILL + " WHERE skill.id = :id AND skill.deleted_at IS NULL")
                .bind("id", id)
                .map((row, metadata) -> skillView(row))
                .one();
    }

    /** {@code SELECT ... FOR UPDATE}; callers must run inside a transaction. */
    Mono<SkillView> lockSkill(UUID id) {
        return databaseClient
                .sql(SELECT_SKILL + FROM_SKILL + " WHERE skill.id = :id AND skill.deleted_at IS NULL FOR UPDATE OF skill")
                .bind("id", id)
                .map((row, metadata) -> skillView(row))
                .one();
    }

    /** A11-only lock that can see a tombstone, making repeated logical deletion idempotent. */
    Mono<SkillView> lockSkillIncludingDeleted(UUID id) {
        return databaseClient
                .sql(SELECT_SKILL + FROM_SKILL + " WHERE skill.id = :id FOR UPDATE OF skill")
                .bind("id", id)
                .map((row, metadata) -> skillView(row))
                .one();
    }

    Mono<SkillView> skillByCode(String code) {
        return databaseClient
                .sql(SELECT_SKILL + FROM_SKILL + " WHERE skill.code = :code AND skill.deleted_at IS NULL")
                .bind("code", code)
                .map((row, metadata) -> skillView(row))
                .one();
    }

    Mono<String> iconObjectKey(UUID id) {
        return databaseClient.sql("""
                        SELECT icon_object_key
                        FROM gateway.skill
                        WHERE id = :id AND deleted_at IS NULL
                          AND icon_object_key IS NOT NULL
                        """)
                .bind("id", id)
                .map((row, metadata) -> row.get("icon_object_key", String.class))
                .one();
    }

    Mono<String> publishedIconObjectKey(String code) {
        return databaseClient.sql("""
                        SELECT icon_object_key
                        FROM gateway.skill
                        WHERE code = :code
                          AND status = 'PUBLISHED'
                          AND deleted_at IS NULL
                          AND icon_object_key IS NOT NULL
                        """)
                .bind("code", code)
                .map((row, metadata) -> row.get("icon_object_key", String.class))
                .one();
    }

    Mono<String> illustrationObjectKey(UUID id) {
        return databaseClient.sql("""
                        SELECT illustration_object_key
                        FROM gateway.skill
                        WHERE id = :id AND deleted_at IS NULL
                          AND illustration_object_key IS NOT NULL
                        """)
                .bind("id", id)
                .map((row, metadata) -> row.get("illustration_object_key", String.class))
                .one();
    }

    Mono<String> publishedIllustrationObjectKey(String code) {
        return databaseClient.sql("""
                        SELECT illustration_object_key
                        FROM gateway.skill
                        WHERE code = :code
                          AND status = 'PUBLISHED'
                          AND deleted_at IS NULL
                          AND illustration_object_key IS NOT NULL
                        """)
                .bind("code", code)
                .map((row, metadata) -> row.get("illustration_object_key", String.class))
                .one();
    }

    /** Tombstones that have not crossed the configured permanent-deletion boundary. */
    Mono<PageResult<DeletedSkillView>> listDeletedSkills(
            int pageNumber,
            int pageSize,
            String query,
            String kindFilter,
            Duration retention,
            Instant now) {
        PageRequest page = page(pageNumber, pageSize);
        List<String> kinds = kindFilter(kindFilter);
        String search = search(query);
        Instant cutoff = now.minus(retention);
        String where = """
                WHERE skill.deleted_at IS NOT NULL
                  AND skill.status = 'DELETED'
                  AND skill.deleted_at > :cutoff
                  AND (:kinds IS NULL OR skill.kind = ANY(:kinds))
                  AND (:search IS NULL
                       OR lower(skill.code) LIKE :search ESCAPE '!'
                       OR lower(skill.name) LIKE :search ESCAPE '!'
                       OR lower(COALESCE(skill.name_en, '')) LIKE :search ESCAPE '!')
                """;
        Object kindBinding = kinds.isEmpty() ? null : kinds.toArray(String[]::new);
        DatabaseClient.GenericExecuteSpec items = databaseClient.sql("""
                        SELECT skill.id, skill.code, skill.name, skill.name_en, skill.category,
                               skill.kind, skill.deleted_at
                          FROM gateway.skill skill
                        """ + where + " ORDER BY skill.deleted_at DESC, skill.id DESC"
                        + " LIMIT :limit OFFSET :offset")
                .bind("cutoff", cutoff)
                .bind("limit", page.size())
                .bind("offset", page.offset());
        DatabaseClient.GenericExecuteSpec count = databaseClient
                .sql("SELECT count(*) FROM gateway.skill skill " + where)
                .bind("cutoff", cutoff);
        items = bindTrashFilters(items, kindBinding, search);
        count = bindTrashFilters(count, kindBinding, search);
        Mono<List<DeletedSkillView>> rows = items.map((row, metadata) -> {
                    Instant deletedAt = instant(row.get("deleted_at", java.time.OffsetDateTime.class));
                    return new DeletedSkillView(
                            row.get("id", UUID.class),
                            row.get("code", String.class),
                            row.get("name", String.class),
                            row.get("name_en", String.class),
                            row.get("category", String.class),
                            kind(row.get("kind", String.class)),
                            deletedAt,
                            deletedAt.plus(retention));
                })
                .all()
                .collectList();
        Mono<Long> total = count.map((row, metadata) -> row.get(0, Long.class)).one();
        return Mono.zip(rows, total)
                .map(result -> new PageResult<>(result.getT1(), result.getT2(), page.page(), page.size()));
    }

    /** Locks one tombstone while restore checks and the state transition run in one transaction. */
    Mono<DeletedSkillView> lockDeletedSkill(UUID id, Duration retention) {
        return databaseClient.sql("""
                        SELECT id, code, name, name_en, category, kind, deleted_at
                          FROM gateway.skill
                         WHERE id = :id AND deleted_at IS NOT NULL AND status = 'DELETED'
                         FOR UPDATE
                        """)
                .bind("id", id)
                .map((row, metadata) -> {
                    Instant deletedAt = instant(row.get("deleted_at", java.time.OffsetDateTime.class));
                    return new DeletedSkillView(
                            row.get("id", UUID.class),
                            row.get("code", String.class),
                            row.get("name", String.class),
                            row.get("name_en", String.class),
                            row.get("category", String.class),
                            kind(row.get("kind", String.class)),
                            deletedAt,
                            deletedAt.plus(retention));
                })
                .one();
    }

    Mono<String> exampleImageObjectKey(UUID id) {
        return databaseClient.sql("""
                        SELECT example_image_object_key
                        FROM gateway.skill
                        WHERE id = :id AND deleted_at IS NULL
                          AND example_image_object_key IS NOT NULL
                        """)
                .bind("id", id)
                .map((row, metadata) -> row.get("example_image_object_key", String.class))
                .one();
    }

    Mono<String> publishedExampleImageObjectKey(String code) {
        return databaseClient.sql("""
                        SELECT example_image_object_key
                        FROM gateway.skill
                        WHERE code = :code
                          AND status = 'PUBLISHED'
                          AND deleted_at IS NULL
                          AND example_image_object_key IS NOT NULL
                        """)
                .bind("code", code)
                .map((row, metadata) -> row.get("example_image_object_key", String.class))
                .one();
    }

    /**
     * The subset of {@code codes} that names a published expert package. A roster entry must resolve
     * to one of these, so the team can render the member portrait from that expert's own icon.
     */
    Flux<String> publishedExpertCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Flux.empty();
        }
        return databaseClient.sql("""
                        SELECT code
                        FROM gateway.skill
                        WHERE code = ANY(:codes)
                          AND kind = 'EXPERT'
                          AND status = 'PUBLISHED'
                          AND deleted_at IS NULL
                        """)
                .bind("codes", List.copyOf(codes).toArray(String[]::new))
                .map((row, metadata) -> row.get("code", String.class))
                .all();
    }

    Mono<Boolean> codeExists(String code) {
        return databaseClient
                .sql("SELECT EXISTS (SELECT 1 FROM gateway.skill WHERE code = :code AND deleted_at IS NULL)")
                .bind("code", code)
                .map((row, metadata) -> Boolean.TRUE.equals(row.get(0, Boolean.class)))
                .one();
    }

    /**
     * E-08/D5: {@code code -> id} for a page of catalog entries, so their {@code memberCount} can be
     * read with a single grouped query instead of one query per skill.
     */
    Mono<Map<String, UUID>> idsByCodes(List<String> codes) {
        List<String> requested = codes == null ? List.of() : List.copyOf(codes);
        if (requested.isEmpty()) {
            return Mono.just(Map.of());
        }
        return databaseClient
                .sql("""
                        SELECT code, id FROM gateway.skill
                        WHERE deleted_at IS NULL AND code = ANY(:codes)
                        """)
                .bind("codes", requested.toArray(String[]::new))
                .map((row, metadata) -> Map.entry(
                        row.get("code", String.class), row.get("id", UUID.class)))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    Mono<UUID> insertSkill(CreateSkillCommand command, UUID actor, Instant now) {
        UUID id = UUID.randomUUID();
        return databaseClient
                .sql("""
                        INSERT INTO gateway.skill(
                            id, code, name, name_en, category, tags, summary, summary_en,
                            description, description_en, icon, kind,
                            min_client_version, status, download_count, sort_weight, row_version,
                            created_by, created_at, updated_by, updated_at)
                        VALUES (:id, :code, :name, :name_en, :category, :tags::text[], :summary,
                            :summary_en, :description, :description_en, :icon, :kind,
                            :min_client_version, 'DRAFT', 0, :sort_weight, 1,
                            :actor, :now, :actor, :now)
                        """)
                .bind("id", id)
                .bind("code", command.code().strip())
                .bind("name", command.name().strip())
                .bind("name_en", nullable(command.nameEn()))
                .bind("category", nullable(command.category()))
                .bind("tags", textArray(command.tags()))
                .bind("summary", nullable(command.summary()))
                .bind("summary_en", nullable(command.summaryEn()))
                .bind("description", nullable(command.description()))
                .bind("description_en", nullable(command.descriptionEn()))
                .bind("icon", nullable(command.icon()))
                .bind("kind", createKind(command.kind()))
                .bind("min_client_version", nullable(command.minClientVersion()))
                .bind("sort_weight", command.sortWeight() == null ? 0 : command.sortWeight())
                .bind("actor", actor)
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("gateway_skill_not_inserted")))
                .thenReturn(id);
    }

    Mono<Long> updateSkill(UUID id, UpdateSkillCommand command, UUID actor, Instant now) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill
                        SET name = :name,
                            name_en = :name_en,
                            category = :category,
                            tags = :tags::text[],
                            summary = :summary,
                            summary_en = :summary_en,
                            description = :description,
                            description_en = :description_en,
                            icon = :icon,
                            min_client_version = :min_client_version,
                            sort_weight = :sort_weight,
                            row_version = row_version + 1,
                            updated_by = :actor,
                            updated_at = :now
                        WHERE id = :id AND deleted_at IS NULL AND row_version = :row_version
                        """)
                .bind("id", id)
                .bind("name", command.name().strip())
                .bind("name_en", nullable(command.nameEn()))
                .bind("category", nullable(command.category()))
                .bind("tags", textArray(command.tags()))
                .bind("summary", nullable(command.summary()))
                .bind("summary_en", nullable(command.summaryEn()))
                .bind("description", nullable(command.description()))
                .bind("description_en", nullable(command.descriptionEn()))
                .bind("icon", nullable(command.icon()))
                .bind("min_client_version", nullable(command.minClientVersion()))
                .bind("sort_weight", command.sortWeight() == null ? 0 : command.sortWeight())
                .bind("actor", actor)
                .bind("now", now)
                .bind("row_version", command.rowVersion())
                .fetch()
                .rowsUpdated();
    }

    /**
     * Seeds the catalog copy a package declares, without ever overwriting copy an operator wrote.
     *
     * <p>YCPS §16 makes {@code gateway.skill} the authoritative home for titles and summaries so
     * wording can be fixed without cutting a release. The gap was that a freshly uploaded package
     * carried no catalog copy at all, so every new package needed a human to retype its title and
     * summary. The ingest pipeline now passes the copy the Entry Skill declares, and this fills only
     * the columns that are still empty ({@code NULL} or blank) — an operator's edit always wins, and
     * re-uploading a package never reverts it.
     *
     * <p>Returns the number of rows updated, which is 0 when every column was already populated.
     * Each language is a separate statement so a package that declares only the default language
     * still seeds the default columns.
     */
    Mono<Long> seedCatalogCopy(
            UUID id,
            String name,
            String nameEn,
            String summary,
            String summaryEn,
            String description,
            String descriptionEn,
            UUID actor,
            Instant now) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill
                        SET name = CASE WHEN btrim(coalesce(name, '')) = '' THEN :name ELSE name END,
                            name_en = CASE WHEN btrim(coalesce(name_en, '')) = '' THEN :name_en ELSE name_en END,
                            summary = CASE WHEN btrim(coalesce(summary, '')) = '' THEN :summary ELSE summary END,
                            summary_en = CASE WHEN btrim(coalesce(summary_en, '')) = '' THEN :summary_en ELSE summary_en END,
                            description = CASE WHEN btrim(coalesce(description, '')) = '' THEN :description ELSE description END,
                            description_en = CASE WHEN btrim(coalesce(description_en, '')) = '' THEN :description_en ELSE description_en END,
                            updated_by = :actor,
                            updated_at = :now
                        WHERE id = :id AND deleted_at IS NULL
                        """)
                .bind("id", id)
                .bind("name", nullable(name))
                .bind("name_en", nullable(nameEn))
                .bind("summary", nullable(summary))
                .bind("summary_en", nullable(summaryEn))
                .bind("description", nullable(description))
                .bind("description_en", nullable(descriptionEn))
                .bind("actor", actor)
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    /**
     * D4: the package manifest is written verbatim and never edited by an admin. Only called by the
     * ingest pipeline, which clears it ({@code null}) when a replacement package has no
     * {@code plugin.json} so the column can never describe a package that is no longer online.
     */
    Mono<Long> updateManifest(UUID id, String manifestJson, UUID actor, Instant now) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill
                        SET manifest = :manifest::jsonb,
                            updated_by = :actor,
                            updated_at = :now
                        WHERE id = :id AND deleted_at IS NULL
                        """)
                .bind("id", id)
                .bind("manifest", manifestJson == null
                        ? Parameter.empty(String.class)
                        : Parameter.from(manifestJson))
                .bind("actor", actor)
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> updateIconObjectKey(UUID id, String objectKey, UUID actor, Instant now) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill
                        SET icon_object_key = :object_key,
                            icon_updated_at = :now,
                            row_version = row_version + 1,
                            updated_by = :actor,
                            updated_at = :now
                        WHERE id = :id AND deleted_at IS NULL
                        """)
                .bind("id", id)
                .bind("object_key", nullable(objectKey))
                .bind("actor", actor)
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> updateIllustrationObjectKey(UUID id, String objectKey, UUID actor, Instant now) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill
                        SET illustration_object_key = :object_key,
                            illustration_updated_at = :now,
                            row_version = row_version + 1,
                            updated_by = :actor,
                            updated_at = :now
                        WHERE id = :id AND deleted_at IS NULL
                        """)
                .bind("id", id)
                .bind("object_key", nullable(objectKey))
                .bind("actor", actor)
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> updateExampleImageObjectKey(UUID id, String objectKey, UUID actor, Instant now) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill
                        SET example_image_object_key = :object_key,
                            example_image_updated_at = :now,
                            row_version = row_version + 1,
                            updated_by = :actor,
                            updated_at = :now
                        WHERE id = :id AND deleted_at IS NULL
                        """)
                .bind("id", id)
                .bind("object_key", nullable(objectKey))
                .bind("actor", actor)
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> markPublished(UUID id, String version, UUID actor, Instant now) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill
                        SET status = 'PUBLISHED',
                            published_version = :version,
                            published_at = :now,
                            unpublished_at = NULL,
                            row_version = row_version + 1,
                            updated_by = :actor,
                            updated_at = :now
                        WHERE id = :id AND deleted_at IS NULL AND status <> 'DELETED'
                        """)
                .bind("id", id)
                .bind("version", nullable(version))
                .bind("actor", actor)
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> markUnpublished(UUID id, UUID actor, Instant now) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill
                        SET status = 'UNPUBLISHED',
                            published_version = NULL,
                            unpublished_at = :now,
                            row_version = row_version + 1,
                            updated_by = :actor,
                            updated_at = :now
                        WHERE id = :id AND deleted_at IS NULL AND status = 'PUBLISHED'
                        """)
                .bind("id", id)
                .bind("actor", actor)
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> markDeleted(UUID id, UUID actor, Instant now) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill
                        SET status = 'DELETED',
                            deleted_at = :now,
                            deleted_by = :actor,
                            published_version = NULL,
                            row_version = row_version + 1,
                            updated_by = :actor,
                            updated_at = :now
                        WHERE id = :id AND deleted_at IS NULL AND status IN ('DRAFT', 'UNPUBLISHED')
                        """)
                .bind("id", id)
                .bind("actor", actor)
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> markNativePublished(UUID id, String version, UUID actor, Instant now) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill
                        SET status = 'PUBLISHED',
                            source_format = 'CLAWHUB_SKILL',
                            published_version = :version,
                            published_at = :now,
                            unpublished_at = NULL,
                            row_version = row_version + 1,
                            updated_by = :actor,
                            updated_at = :now
                        WHERE id = :id AND deleted_at IS NULL AND status <> 'DELETED'
                        """)
                .bind("id", id)
                .bind("version", version)
                .bind("actor", actor)
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    /** Restores a tombstone as a non-client-visible record. */
    Mono<Long> restoreDeleted(UUID id, UUID actor, Instant now, Instant deletedAfter) {
        return databaseClient.sql("""
                        UPDATE gateway.skill target
                           SET status = CASE
                                   WHEN target.unpublished_at IS NULL THEN 'DRAFT'
                                   ELSE 'UNPUBLISHED'
                               END,
                               deleted_at = NULL,
                               deleted_by = NULL,
                               published_version = NULL,
                               row_version = row_version + 1,
                               updated_by = :actor,
                               updated_at = :now
                         WHERE target.id = :id
                           AND target.status = 'DELETED'
                           AND target.deleted_at > :deleted_after
                           AND NOT EXISTS (
                               SELECT 1
                                 FROM gateway.skill active
                                WHERE active.code = target.code
                                  AND active.deleted_at IS NULL
                                  AND active.id <> target.id
                           )
                        """)
                .bind("id", id)
                .bind("actor", actor)
                .bind("now", now)
                .bind("deleted_after", deletedAfter)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> setLatestVersion(UUID id, String version, UUID actor, Instant now) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill
                        SET latest_version = :version,
                            updated_by = :actor,
                            updated_at = :now
                        WHERE id = :id AND deleted_at IS NULL
                        """)
                .bind("id", id)
                .bind("version", nullable(version))
                .bind("actor", actor)
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> incrementDownloadCount(UUID id) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill SET download_count = download_count + 1
                        WHERE id = :id AND deleted_at IS NULL
                        """)
                .bind("id", id)
                .fetch()
                .rowsUpdated();
    }

    /** Published catalog (design R-01): {@code status='PUBLISHED'} with a resolvable online version. */
    Flux<PublishedSkill> listPublished() {
        return listCatalog(false);
    }

    /**
     * Client catalogue projection. The opt-in form also returns unavailable entries after an
     * operator unpublishes them, but never exposes a package URL or checksum for those rows.
     */
    Flux<PublishedSkill> listCatalog(boolean includeUnpublished) {
        String visibility = includeUnpublished
                ? """
                          AND ((skill.status = 'PUBLISHED'
                                AND skill.published_version IS NOT NULL
                                AND online.id IS NOT NULL)
                               OR skill.status = 'UNPUBLISHED')
                        """
                : """
                          AND skill.status = 'PUBLISHED'
                          AND skill.published_version IS NOT NULL
                          AND online.id IS NOT NULL
                        """;
        return databaseClient
                .sql(SELECT_SKILL + FROM_SKILL
                + """
                        WHERE skill.deleted_at IS NULL
                          AND skill.source_format <> 'CLAWHUB_SKILL'
                        """
                        + visibility
                        + """
                        ORDER BY skill.sort_weight DESC, skill.updated_at DESC, skill.code ASC
                        """)
                .map((row, metadata) -> published(row))
                .all();
    }

    Mono<PublishedSkill> publishedSkill(String code) {
        return databaseClient
                .sql(SELECT_SKILL + FROM_SKILL
                        + """
                        WHERE skill.deleted_at IS NULL
                          AND skill.source_format <> 'CLAWHUB_SKILL'
                          AND skill.status = 'PUBLISHED'
                          AND skill.published_version IS NOT NULL
                          AND online.id IS NOT NULL
                          AND skill.code = :code
                        """)
                .bind("code", code)
                .map((row, metadata) -> published(row))
                .one();
    }

    private PublishedSkill published(io.r2dbc.spi.Row row) {
        return new PublishedSkill(row.get("id", UUID.class), summary(row));
    }

    /** The published skill plus the internal id the client contracts deliberately do not expose. */
    record PublishedSkill(UUID id, SkillSummary summary) {}

    Mono<Void> audit(UUID actorUserId, String action, UUID resourceId, Map<String, Object> detail) {
        ObjectNode node = objectMapper.createObjectNode();
        detail.forEach((key, value) -> node.set(key, objectMapper.valueToTree(value)));
        return databaseClient
                .sql("""
                        INSERT INTO gateway.admin_operation_audit(
                            operation_id, actor_user_id, action, status, affected,
                            completed_at, resource_type, resource_id, detail)
                        VALUES (:operation_id, :actor_user_id, :action, 'COMPLETED', 1,
                            now(), 'SKILL', :resource_id, :detail::jsonb)
                        """)
                .bind("operation_id", UUID.randomUUID())
                .bind("actor_user_id", actorUserId)
                .bind("action", action)
                .bind("resource_id", resourceId.toString())
                .bind("detail", json(node))
                .fetch()
                .rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("gateway_audit_not_written")))
                .then();
    }

    /** Audit failures must never mask the original admin error. */
    Mono<Void> auditOutcome(
            UUID actorUserId, String action, UUID resourceId, Map<String, Object> detail, boolean success) {
        ObjectNode node = objectMapper.createObjectNode();
        detail.forEach((key, value) -> node.set(key, objectMapper.valueToTree(value)));
        return databaseClient
                .sql("""
                        INSERT INTO gateway.admin_operation_audit(
                            operation_id, actor_user_id, action, status, affected,
                            completed_at, resource_type, resource_id, detail)
                        VALUES (:operation_id, :actor_user_id, :action, :status, :affected,
                            now(), 'SKILL', :resource_id, :detail::jsonb)
                        """)
                .bind("operation_id", UUID.randomUUID())
                .bind("actor_user_id", actorUserId)
                .bind("action", action)
                .bind("status", success ? "COMPLETED" : "FAILED")
                .bind("affected", success ? 1 : 0)
                .bind("resource_id", resourceId.toString())
                .bind("detail", json(node))
                .fetch()
                .rowsUpdated()
                .then();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(
            DatabaseClient.GenericExecuteSpec spec,
            String status,
            String category,
            Object kindBinding,
            String search) {
        DatabaseClient.GenericExecuteSpec bound =
                status == null ? spec.bindNull("status", String.class) : spec.bind("status", status);
        bound = category == null
                ? bound.bindNull("category", String.class)
                : bound.bind("category", category);
        // The kind filter is a text[] (one or more kinds) so A1 can span EXPERT+TEAM.
        bound = kindBinding == null
                ? bound.bindNull("kinds", String[].class)
                : bound.bind("kinds", kindBinding);
        return search == null ? bound.bindNull("search", String.class) : bound.bind("search", search);
    }

    private DatabaseClient.GenericExecuteSpec bindTrashFilters(
            DatabaseClient.GenericExecuteSpec spec, Object kindBinding, String search) {
        DatabaseClient.GenericExecuteSpec bound = kindBinding == null
                ? spec.bindNull("kinds", String[].class)
                : spec.bind("kinds", kindBinding);
        return search == null ? bound.bindNull("search", String.class) : bound.bind("search", search);
    }

    private SkillView skillView(io.r2dbc.spi.Row row) {
        UUID id = row.get("id", UUID.class);
        return new SkillView(
                id,
                row.get("code", String.class),
                row.get("name", String.class),
                row.get("name_en", String.class),
                row.get("category", String.class),
                stringList(row.get("tags", String.class)),
                row.get("summary", String.class),
                row.get("summary_en", String.class),
                row.get("description", String.class),
                row.get("description_en", String.class),
                row.get("icon", String.class),
                adminIconUrl(id, row.get("icon_object_key", String.class)),
                instant(row.get("icon_updated_at", java.time.OffsetDateTime.class)),
                adminIllustrationUrl(id, row.get("illustration_object_key", String.class)),
                instant(row.get("illustration_updated_at", java.time.OffsetDateTime.class)),
                adminExampleImageUrl(id, row.get("example_image_object_key", String.class)),
                instant(row.get("example_image_updated_at", java.time.OffsetDateTime.class)),
                kind(row.get("kind", String.class)),
                row.get("status", String.class),
                row.get("published_version", String.class),
                row.get("latest_version", String.class),
                effectiveMinClientVersion(
                        row.get("min_client_version", String.class),
                        row.get("online_min_client_version", String.class)),
                row.get("online_size_bytes", Long.class),
                row.get("online_md5", String.class),
                row.get("online_sha256", String.class),
                orZero(row.get("download_count", Long.class)),
                orZero(row.get("sort_weight", Integer.class)),
                orZero(row.get("row_version", Integer.class)),
                instant(row.get("created_at", java.time.OffsetDateTime.class)),
                instant(row.get("updated_at", java.time.OffsetDateTime.class)),
                instant(row.get("published_at", java.time.OffsetDateTime.class)),
                instant(row.get("unpublished_at", java.time.OffsetDateTime.class)),
                (int) orZero(row.get("member_count", Long.class)),
                // G4: the relation counters are aggregated in SQL so A1 stays a single round trip.
                (int) orZero(row.get("dependency_count", Long.class)),
                (int) orZero(row.get("dependent_count", Long.class)),
                // D5/E-08: A1 is the lightweight list projection. The manifest is carried by the
                // @JsonView(DetailView) component of SkillView, which the list endpoint does not
                // activate, so list items never expose it (SkillAdminService.detail refills it).
                null,
                null,
                List.of(),
                row.get("source_format", String.class),
                Boolean.TRUE.equals(row.get("source_review_required", Boolean.class)),
                row.get("source_version", String.class),
                row.get("source_scan_status", String.class));
    }

    private SkillSummary summary(io.r2dbc.spi.Row row) {
        UUID id = row.get("id", UUID.class);
        String status = row.get("status", String.class);
        boolean isPublished = "PUBLISHED".equals(status);
        String onlineVersion = row.get("published_version", String.class);
        String latestVersion = row.get("latest_version", String.class);
        String version = isPublished ? onlineVersion : latestVersion;
        return new SkillSummary(
                row.get("code", String.class),
                row.get("name", String.class),
                row.get("description", String.class),
                row.get("summary", String.class),
                version,
                row.get("category", String.class),
                stringList(row.get("tags", String.class)),
                row.get("icon", String.class),
                List.of(),
                status,
                latestVersion,
                onlineVersion,
                effectiveMinClientVersion(
                        row.get("min_client_version", String.class),
                        row.get("online_min_client_version", String.class)),
                isPublished ? row.get("online_size_bytes", Long.class) : null,
                isPublished ? row.get("online_md5", String.class) : null,
                isPublished ? row.get("online_sha256", String.class) : null,
                isPublished ? orZero(row.get("online_revision", Integer.class)) : 0,
                isPublished ? row.get("online_package_format", String.class) : null,
                isPublished ? row.get("online_ycps_version", String.class) : null,
                isPublished ? row.get("online_manifest_sha256", String.class) : null,
                isPublished ? row.get("online_dependency_lock_sha256", String.class) : null,
                isPublished
                        ? "/v1/skills/" + row.get("code", String.class) + "/downloads"
                        : null,
                clientIconUrl(
                        row.get("code", String.class),
                        row.get("icon_object_key", String.class)),
                instant(row.get("icon_updated_at", java.time.OffsetDateTime.class)),
                isPublished
                        ? clientIllustrationUrl(
                                row.get("code", String.class),
                                row.get("illustration_object_key", String.class))
                        : null,
                instant(row.get("illustration_updated_at", java.time.OffsetDateTime.class)),
                isPublished
                        ? clientExampleImageUrl(
                                row.get("code", String.class),
                                row.get("example_image_object_key", String.class))
                        : null,
                instant(row.get("example_image_updated_at", java.time.OffsetDateTime.class)),
                instant(row.get("updated_at", java.time.OffsetDateTime.class)),
                orZero(row.get("download_count", Long.class)),
                instant(row.get("published_at", java.time.OffsetDateTime.class)),
                isPublished ? orZero(row.get("online_file_count", Integer.class)) : 0,
                isPublished ? orZero(row.get("online_size_bytes", Long.class)) : 0L,
                kind(row.get("kind", String.class)),
                row.get("name_en", String.class),
                row.get("summary_en", String.class),
                row.get("description_en", String.class),
                List.of(),
                List.of(),
                (int) orZero(row.get("member_count", Long.class)),
                List.of());
    }

    /** R-36: the client picks the stricter of the skill-level and version-level minimum version. */
    static String effectiveMinClientVersion(String skillLevel, String versionLevel) {
        if (skillLevel == null || skillLevel.isBlank()) {
            return versionLevel;
        }
        if (versionLevel == null || versionLevel.isBlank()) {
            return skillLevel;
        }
        return ClientVersion.compare(skillLevel, versionLevel) >= 0 ? skillLevel : versionLevel;
    }

    private static String adminIconUrl(UUID id, String iconObjectKey) {
        if (iconObjectKey == null || iconObjectKey.isBlank()) {
            return null;
        }
        return "/console/gateway/admin/skills/" + id + "/icon";
    }

    private static String clientIconUrl(String code, String iconObjectKey) {
        if (iconObjectKey == null || iconObjectKey.isBlank()) {
            return null;
        }
        return "/v1/skills/" + code + "/icon";
    }

    private static String adminIllustrationUrl(UUID id, String illustrationObjectKey) {
        if (illustrationObjectKey == null || illustrationObjectKey.isBlank()) {
            return null;
        }
        return "/console/gateway/admin/skills/" + id + "/illustration";
    }

    private static String clientIllustrationUrl(String code, String illustrationObjectKey) {
        if (illustrationObjectKey == null || illustrationObjectKey.isBlank()) {
            return null;
        }
        return "/v1/skills/" + code + "/illustration";
    }

    private static String adminExampleImageUrl(UUID id, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        return "/console/gateway/admin/skills/" + id + "/example-image";
    }

    private static String clientExampleImageUrl(String code, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        return "/v1/skills/" + code + "/example-image";
    }

    private String ordering(SkillListQuery request) {
        boolean defaultRequest = blank(request.sort()) && blank(request.direction());
        String sort = blank(request.sort()) ? "updated_at" : request.sort().strip();
        String column = SORTS.get(sort);
        if (column == null) {
            throw invalid("invalid_sort");
        }
        String direction = blank(request.direction())
                ? "DESC"
                : request.direction().strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ASC", "DESC").contains(direction)) {
            throw invalid("invalid_sort_direction");
        }
        return defaultRequest
                ? " ORDER BY skill.updated_at DESC, skill.id DESC"
                : " ORDER BY " + column + " " + direction + ", skill.id " + direction;
    }

    private PageRequest page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw invalid("invalid_pagination");
        }
        try {
            return new PageRequest(page, size, Math.multiplyExact(page, size));
        } catch (ArithmeticException error) {
            throw invalid("invalid_pagination");
        }
    }

    private String statusFilter(String status) {
        if (blank(status)) {
            return null;
        }
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw invalid("invalid_skill_status");
        }
        return normalized;
    }

    /**
     * A1: optional {@code kind} filter. One value ({@code EXPERT}) or a comma-separated set
     * ({@code EXPERT,TEAM}) is accepted; an unknown value is a stable 422 (E-01 style validation).
     * An empty list means "every kind".
     */
    private List<String> kindFilter(String kind) {
        if (blank(kind)) {
            return List.of();
        }
        List<String> kinds = java.util.Arrays.stream(kind.split(","))
                .map(String::strip)
                .filter(entry -> !entry.isEmpty())
                .map(entry -> entry.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        if (kinds.isEmpty()) {
            throw invalid("invalid_skill_kind");
        }
        for (String entry : kinds) {
            if (!KINDS.contains(entry)) {
                throw invalid("invalid_skill_kind");
            }
        }
        return kinds;
    }

    /** The single stored kind written by A2 (create); missing means SKILL, unknown is rejected. */
    private String createKind(String kind) {
        String normalized = singleKindFilter(kind);
        return normalized == null ? KIND_SKILL : normalized;
    }

    /** The single stored kind written by A2 (create); an unknown value is rejected the same way. */
    private String singleKindFilter(String kind) {
        if (blank(kind)) {
            return null;
        }
        String normalized = kind.strip().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(normalized)) {
            throw invalid("invalid_skill_kind");
        }
        return normalized;
    }

    /** A stored kind is never null (column default), but a degraded read must not break A1/A3. */
    private static String kind(String value) {
        return blank(value) ? KIND_SKILL : value.strip().toUpperCase(Locale.ROOT);
    }

    private String search(String query) {
        if (blank(query)) {
            return null;
        }
        String escaped = query.strip()
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    static String textArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('"')
                    .append(values.get(index).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return builder.append('}').toString();
    }

    private List<String> stringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readerForListOf(String.class).readValue(json);
        } catch (Exception exception) {
            throw new IllegalStateException("json_deserialization_failed", exception);
        }
    }

    /**
     * D4/D5: the read-only package manifest of one skill, attached by A3/A14 only. It is read as a
     * separate projection so the paged list (A1) never carries {@code manifest} or {@code members}.
     *
     * <p>A skill without a {@code plugin.json} stores SQL {@code NULL}, and an empty {@code Mono}
     * would silently truncate the caller's {@code flatMap} chain (the create/detail response would
     * become a 500), so an explicit JSON {@code null} is emitted instead.
     */
    Mono<JsonNode> skillManifest(UUID id) {
        return databaseClient
                .sql("SELECT skill.manifest::text AS manifest FROM gateway.skill skill"
                        + " WHERE skill.id = :id AND skill.deleted_at IS NULL")
                .bind("id", id)
                .map((row, metadata) -> {
                    JsonNode parsed = manifest(row.get("manifest", String.class));
                    return parsed == null ? NullNode.getInstance() : parsed;
                })
                .one()
                .defaultIfEmpty(NullNode.getInstance());
    }

    /**
     * The {@code manifest} jsonb column is read as text and handed to Jackson as a tree, so the admin
     * response carries the original JSON document ({@code A3.manifest.expertType}) instead of a
     * double-encoded string. A malformed value degrades to {@code {"raw": …}} rather than breaking
     * the detail read.
     */
    private JsonNode manifest(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("raw", json);
            return fallback;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("json_serialization_failed", exception);
        }
    }

    /**
     * R2DBC rejects {@code null} in {@code bind(name, value)} ("Value for parameter X must not be
     * null"), so optional columns must be bound as {@link Parameter#empty(Class)}. Returning a
     * {@code String} here made every skill without a category/summary/description/icon/min-version
     * blow up with a 500 on create and update.
     */
    private static Parameter nullable(String value) {
        return value == null || value.isBlank()
                ? Parameter.empty(String.class)
                : Parameter.from(value.strip());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static Instant instant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private record PageRequest(int page, int size, int offset) {}

    static SkillVersionView versionView(io.r2dbc.spi.Row row, String onlineVersion) {
        String version = row.get("version", String.class);
        String status = row.get("status", String.class);
        return new SkillVersionView(
                row.get("id", UUID.class),
                row.get("skill_id", UUID.class),
                version,
                orZero(row.get("revision", Integer.class)),
                status,
                row.get("size_bytes", Long.class),
                row.get("md5", String.class),
                row.get("sha256", String.class),
                row.get("package_format", String.class),
                row.get("ycps_version", String.class),
                row.get("package_kind", String.class),
                parsedJson(row.get("plugin_manifest", String.class)),
                parsedJson(row.get("runtime_manifest", String.class)),
                row.get("manifest_sha256", String.class),
                row.get("dependency_lock_sha256", String.class),
                Boolean.TRUE.equals(row.get("sealed", Boolean.class)),
                instant(row.get("sealed_at", java.time.OffsetDateTime.class)),
                orZero(row.get("file_count", Integer.class)),
                orZero(row.get("extracted_bytes", Long.class)),
                row.get("min_client_version", String.class),
                row.get("changelog", String.class),
                row.get("scan_status", String.class),
                orZero(row.get("download_count", Long.class)),
                // The semantic version is no longer unique: a READY successor may share the
                // published version number while the older row remains the only online artifact.
                // Status is the authoritative per-row visibility signal for this DTO.
                "PUBLISHED".equals(status) && version != null && version.equals(onlineVersion),
                instant(row.get("created_at", java.time.OffsetDateTime.class)));
    }

    private static JsonNode parsedJson(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new ObjectMapper().readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("invalid_ycps_json_in_database", exception);
        }
    }
}
