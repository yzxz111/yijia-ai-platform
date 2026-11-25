package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileRequests.ExpertPresentationCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewContent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/** R2DBC persistence for the one-to-one structured Expert presentation row. */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillExpertProfileRepository {

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillExpertProfileRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    Mono<StoredProfile> findBySkillId(UUID skillId) {
        return databaseClient
                .sql(select()
                        + " WHERE skill.id = :skill_id AND skill.deleted_at IS NULL"
                        + " AND profile.skill_id IS NOT NULL")
                .bind("skill_id", skillId)
                .map((row, metadata) -> map(row))
                .one();
    }

    Mono<StoredProfile> findPublishedByCode(String code) {
        return databaseClient
                .sql(select()
                        + " WHERE skill.code = :code AND skill.kind = 'EXPERT'"
                        + " AND skill.status = 'PUBLISHED' AND skill.deleted_at IS NULL"
                        + " AND profile.skill_id IS NOT NULL")
                .bind("code", code)
                .map((row, metadata) -> map(row))
                .one();
    }

    Mono<Long> insert(
            UUID skillId,
            ExpertPresentationCommand command,
            int expectedRowVersion,
            UUID actor,
            Instant now) {
        return databaseClient
                .sql("""
                        WITH locked AS (
                            UPDATE gateway.skill
                            SET row_version = row_version + 1,
                                updated_by = :actor,
                                updated_at = :now
                            WHERE id = :skill_id AND deleted_at IS NULL
                              AND kind = 'EXPERT' AND row_version = :row_version
                            RETURNING id
                        )
                        INSERT INTO gateway.skill_expert_profile(
                            skill_id, task_types, task_types_en, usage_guide, usage_guide_en,
                            example_prompts, example_prompts_en, preview_zh, preview_en,
                            canvas_zh, canvas_en, example_image_locale,
                            portable_asset_manifest, portable_revision,
                            created_by, created_at, updated_by, updated_at)
                        SELECT locked.id, :task_types::text[], :task_types_en::text[],
                            :usage_guide, :usage_guide_en, :example_prompts::jsonb,
                            :example_prompts_en::jsonb, :preview_zh::jsonb, :preview_en::jsonb,
                            :canvas_zh::jsonb, :canvas_en::jsonb, :example_image_locale,
                            '{}'::jsonb, 0, :actor, :now, :actor, :now
                        FROM locked
                        """)
                .bind("skill_id", skillId)
                .bind("task_types", SkillRepository.textArray(command.taskTypes()))
                .bind("task_types_en", SkillRepository.textArray(command.taskTypesEn()))
                .bind("usage_guide", nullable(command.usageGuide()))
                .bind("usage_guide_en", nullable(command.usageGuideEn()))
                .bind("example_prompts", json(command.examplePrompts()))
                .bind("example_prompts_en", json(command.examplePromptsEn()))
                .bind("preview_zh", jsonParameter(command.preview()))
                .bind("preview_en", jsonParameter(command.previewEn()))
                .bind("canvas_zh", jsonParameter(canvasLocale(command.canvas(), "zh")))
                .bind("canvas_en", jsonParameter(canvasLocale(command.canvas(), "en")))
                .bind("example_image_locale", nullableExampleImageLocale(command.exampleImageLocale()))
                .bind("actor", nullable(actor))
                .bind("now", now)
                .bind("row_version", expectedRowVersion)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> update(
            UUID skillId,
            ExpertPresentationCommand command,
            int expectedRowVersion,
            UUID actor,
            Instant now) {
        return databaseClient
                .sql("""
                        WITH locked AS (
                            UPDATE gateway.skill
                            SET row_version = row_version + 1,
                                updated_by = :actor,
                                updated_at = :now
                            WHERE id = :skill_id AND deleted_at IS NULL
                              AND kind = 'EXPERT' AND row_version = :row_version
                            RETURNING id
                        )
                        UPDATE gateway.skill_expert_profile profile
                        SET task_types = :task_types::text[],
                            task_types_en = :task_types_en::text[],
                            usage_guide = :usage_guide,
                            usage_guide_en = :usage_guide_en,
                            example_prompts = :example_prompts::jsonb,
                            example_prompts_en = :example_prompts_en::jsonb,
                            preview_zh = :preview_zh::jsonb,
                            preview_en = :preview_en::jsonb,
                            canvas_zh = :canvas_zh::jsonb,
                            canvas_en = :canvas_en::jsonb,
                            example_image_locale = :example_image_locale,
                            updated_by = :actor,
                            updated_at = :now
                        FROM locked
                        WHERE profile.skill_id = locked.id
                        """)
                .bind("skill_id", skillId)
                .bind("task_types", SkillRepository.textArray(command.taskTypes()))
                .bind("task_types_en", SkillRepository.textArray(command.taskTypesEn()))
                .bind("usage_guide", nullable(command.usageGuide()))
                .bind("usage_guide_en", nullable(command.usageGuideEn()))
                .bind("example_prompts", json(command.examplePrompts()))
                .bind("example_prompts_en", json(command.examplePromptsEn()))
                .bind("preview_zh", jsonParameter(command.preview()))
                .bind("preview_en", jsonParameter(command.previewEn()))
                .bind("canvas_zh", jsonParameter(canvasLocale(command.canvas(), "zh")))
                .bind("canvas_en", jsonParameter(canvasLocale(command.canvas(), "en")))
                .bind("example_image_locale", nullableExampleImageLocale(command.exampleImageLocale()))
                .bind("actor", nullable(actor))
                .bind("now", now)
                .bind("row_version", expectedRowVersion)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> delete(UUID skillId, int expectedRowVersion, UUID actor) {
        return databaseClient
                .sql("""
                        WITH locked AS (
                            UPDATE gateway.skill
                            SET row_version = row_version + 1,
                                updated_by = :actor,
                                updated_at = now()
                            WHERE id = :skill_id AND deleted_at IS NULL
                              AND kind = 'EXPERT' AND row_version = :row_version
                            RETURNING id
                        )
                        DELETE FROM gateway.skill_expert_profile profile
                        USING locked
                        WHERE profile.skill_id = locked.id
                        """)
                .bind("skill_id", skillId)
                .bind("row_version", expectedRowVersion)
                .bind("actor", nullable(actor))
                .fetch()
                .rowsUpdated();
    }

    /**
     * Records the immutable inputs used by the latest generated portable revision. The profile
     * content remains independently editable; this manifest is only generation provenance.
     */
    Mono<Long> updatePortableMetadata(
            UUID skillId,
            JsonNode manifest,
            int expectedRowVersion,
            int portableRevision) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill_expert_profile profile
                        SET portable_asset_manifest = :manifest::jsonb,
                            portable_revision = :portable_revision,
                            updated_at = now()
                        WHERE profile.skill_id = :skill_id
                          AND profile.portable_revision = :expected_portable_revision
                          AND EXISTS (
                              SELECT 1
                              FROM gateway.skill skill
                              WHERE skill.id = profile.skill_id
                                AND skill.deleted_at IS NULL
                                AND skill.row_version = :expected_row_version
                          )
                        """)
                .bind("skill_id", skillId)
                .bind("manifest", json(manifest))
                .bind("expected_row_version", expectedRowVersion)
                .bind("expected_portable_revision", portableRevision - 1)
                .bind("portable_revision", portableRevision)
                .fetch()
                .rowsUpdated();
    }

    private String select() {
        return """
                SELECT skill.id AS skill_id, skill.code, skill.kind, skill.status,
                       COALESCE(to_json(profile.task_types), '[]')::text AS task_types,
                       COALESCE(to_json(profile.task_types_en), '[]')::text AS task_types_en,
                       profile.usage_guide, profile.usage_guide_en,
                       profile.example_prompts::text AS example_prompts,
                       profile.example_prompts_en::text AS example_prompts_en,
                       profile.preview_zh::text AS preview_zh,
                       profile.preview_en::text AS preview_en,
                       profile.canvas_zh::text AS canvas_zh,
                       profile.canvas_en::text AS canvas_en,
                       profile.example_image_locale,
                       profile.portable_asset_manifest::text AS portable_asset_manifest,
                       profile.portable_revision,
                       skill.row_version,
                       profile.created_at, profile.updated_at
                FROM gateway.skill skill
                LEFT JOIN gateway.skill_expert_profile profile
                  ON profile.skill_id = skill.id
                """;
    }

    private StoredProfile map(io.r2dbc.spi.Row row) {
        return new StoredProfile(
                row.get("skill_id", UUID.class),
                row.get("code", String.class),
                row.get("kind", String.class),
                row.get("status", String.class),
                list(row.get("task_types", String.class)),
                list(row.get("task_types_en", String.class)),
                row.get("usage_guide", String.class),
                row.get("usage_guide_en", String.class),
                list(row.get("example_prompts", String.class)),
                list(row.get("example_prompts_en", String.class)),
                preview(row.get("preview_zh", String.class)),
                preview(row.get("preview_en", String.class)),
                canvas(row.get("canvas_zh", String.class), row.get("canvas_en", String.class)),
                row.get("example_image_locale", String.class),
                jsonNode(row.get("portable_asset_manifest", String.class)),
                number(row.get("portable_revision", Integer.class)),
                number(row.get("row_version", Integer.class)),
                instant(row.get("created_at", OffsetDateTime.class)),
                instant(row.get("updated_at", OffsetDateTime.class)));
    }

    private List<String> list(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readerForListOf(String.class).readValue(value);
        } catch (Exception error) {
            throw new IllegalStateException("skill_expert_profile_json_invalid", error);
        }
    }

    private ExpertPreviewContent preview(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return null;
        }
        try {
            return objectMapper.treeToValue(objectMapper.readTree(value), ExpertPreviewContent.class);
        } catch (Exception error) {
            throw new IllegalStateException("skill_expert_profile_preview_invalid", error);
        }
    }

    private JsonNode jsonNode(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception error) {
            throw new IllegalStateException("skill_expert_profile_asset_manifest_invalid", error);
        }
    }

    private JsonNode jsonNodeOrNull(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) return null;
        try {
            return objectMapper.readTree(value);
        } catch (Exception error) {
            throw new IllegalStateException("skill_expert_profile_canvas_invalid", error);
        }
    }

    private JsonNode canvas(String zh, String en) {
        com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
        JsonNode zhNode = jsonNodeOrNull(zh);
        JsonNode enNode = jsonNodeOrNull(en);
        if (zhNode != null) result.set("zh", zhNode);
        if (enNode != null) result.set("en", enNode);
        return result.isEmpty() ? null : result;
    }

    private JsonNode canvasLocale(JsonNode canvas, String language) {
        if (canvas == null || canvas.isNull()) return null;
        if (canvas.has("version")) return "zh".equals(language) ? canvas : null;
        JsonNode value = canvas.get(language);
        return value == null || value.isNull() ? null : value;
    }

    private Parameter jsonParameter(Object value) {
        return value == null ? Parameter.empty(String.class) : Parameter.from(json(value));
    }

    private String json(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("skill_expert_profile_json_invalid", error);
        }
    }

    private static Parameter nullable(String value) {
        return value == null || value.isBlank()
                ? Parameter.empty(String.class)
                : Parameter.from(value.strip());
    }

    private static Parameter nullable(UUID value) {
        return value == null ? Parameter.empty(UUID.class) : Parameter.from(value);
    }

    private static Parameter nullableExampleImageLocale(String value) {
        String normalized = ExpertImageLocale.normalize(value);
        return normalized == null
                ? Parameter.empty(String.class)
                : Parameter.from(normalized);
    }

    private static int number(Integer value) {
        return value == null ? 0 : value;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    record StoredProfile(
            UUID skillId,
            String code,
            String kind,
            String status,
            List<String> taskTypes,
            List<String> taskTypesEn,
            String usageGuide,
            String usageGuideEn,
            List<String> examplePrompts,
            List<String> examplePromptsEn,
            ExpertPreviewContent preview,
            ExpertPreviewContent previewEn,
            JsonNode canvas,
            String exampleImageLocale,
            JsonNode assetManifest,
            int portableRevision,
            int rowVersion,
            Instant createdAt,
            Instant updatedAt) {
        /** Compatibility constructor for tests/legacy callers before canvas was persisted. */
        StoredProfile(
                UUID skillId,
                String code,
                String kind,
                String status,
                List<String> taskTypes,
                List<String> taskTypesEn,
                String usageGuide,
                String usageGuideEn,
                List<String> examplePrompts,
                List<String> examplePromptsEn,
                ExpertPreviewContent preview,
                ExpertPreviewContent previewEn,
                JsonNode assetManifest,
                int portableRevision,
                int rowVersion,
                Instant createdAt,
                Instant updatedAt) {
            this(skillId, code, kind, status, taskTypes, taskTypesEn, usageGuide, usageGuideEn,
                    examplePrompts, examplePromptsEn, preview, previewEn, null, null, assetManifest,
                    portableRevision, rowVersion, createdAt, updatedAt);
        }

        /** Compatibility constructor for callers that persist a canvas but predate the locale. */
        StoredProfile(
                UUID skillId,
                String code,
                String kind,
                String status,
                List<String> taskTypes,
                List<String> taskTypesEn,
                String usageGuide,
                String usageGuideEn,
                List<String> examplePrompts,
                List<String> examplePromptsEn,
                ExpertPreviewContent preview,
                ExpertPreviewContent previewEn,
                JsonNode canvas,
                JsonNode assetManifest,
                int portableRevision,
                int rowVersion,
                Instant createdAt,
                Instant updatedAt) {
            this(skillId, code, kind, status, taskTypes, taskTypesEn, usageGuide, usageGuideEn,
                    examplePrompts, examplePromptsEn, preview, previewEn, canvas, null,
                    assetManifest, portableRevision, rowVersion, createdAt, updatedAt);
        }
    }
}
