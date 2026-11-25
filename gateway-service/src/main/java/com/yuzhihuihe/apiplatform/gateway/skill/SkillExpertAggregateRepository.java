package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileRequests.ExpertAggregateCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewContent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/** SQL boundary for the atomic Expert aggregate write. */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class SkillExpertAggregateRepository {

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    SkillExpertAggregateRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    Mono<Long> insert(
            UUID id,
            ExpertAggregateCommand command,
            String iconKey,
            String illustrationKey,
            String exampleImageKey,
            UUID actor,
            Instant now) {
        return databaseClient
                .sql("""
                        INSERT INTO gateway.skill(
                            id, code, name, name_en, category, tags, summary, summary_en,
                            description, description_en, icon, icon_object_key, icon_updated_at,
                            illustration_object_key, illustration_updated_at,
                            example_image_object_key, example_image_updated_at, kind,
                            min_client_version, status, download_count, sort_weight, row_version,
                            created_by, created_at, updated_by, updated_at)
                        VALUES (:id, :code, :name, :name_en, :category, :tags::text[],
                            :summary, :summary_en, :description, :description_en, :icon,
                            :icon_key, CASE WHEN :icon_key IS NULL THEN NULL ELSE :now END,
                            :illustration_key,
                            CASE WHEN :illustration_key IS NULL THEN NULL ELSE :now END,
                            :example_image_key,
                            CASE WHEN :example_image_key IS NULL THEN NULL ELSE :now END,
                            'EXPERT',
                            :min_client_version, 'DRAFT', 0, :sort_weight, 1,
                            :actor, :now, :actor, :now)
                        """)
                .bind("id", id)
                .bind("code", command.code().strip())
                .bind("name", command.name().strip())
                .bind("name_en", nullable(command.nameEn()))
                .bind("category", nullable(command.category()))
                .bind("tags", SkillRepository.textArray(command.tags()))
                .bind("summary", nullable(command.summary()))
                .bind("summary_en", nullable(command.summaryEn()))
                .bind("description", nullable(command.description()))
                .bind("description_en", nullable(command.descriptionEn()))
                .bind("icon", nullable(command.icon()))
                .bind("icon_key", nullable(iconKey))
                .bind("illustration_key", nullable(illustrationKey))
                .bind("example_image_key", nullable(exampleImageKey))
                .bind("min_client_version", nullable(command.minClientVersion()))
                .bind("sort_weight", command.sortWeight() == null ? 0 : command.sortWeight())
                .bind("actor", nullable(actor))
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .filter(rows -> rows == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("gateway_skill_not_inserted")))
                .then(upsertProfile(id, command, actor, now));
    }

    Mono<Long> update(
            UUID id,
            ExpertAggregateCommand command,
            int expectedRowVersion,
            String iconKey,
            String illustrationKey,
            String exampleImageKey,
            UUID actor,
            Instant now) {
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
                            icon_object_key = :icon_key,
                            icon_updated_at = CASE WHEN :icon_key IS DISTINCT FROM icon_object_key
                                THEN :now ELSE icon_updated_at END,
                            illustration_object_key = :illustration_key,
                            illustration_updated_at = CASE WHEN :illustration_key IS DISTINCT FROM illustration_object_key
                                THEN :now ELSE illustration_updated_at END,
                            example_image_object_key = :example_image_key,
                            example_image_updated_at = CASE WHEN :example_image_key IS DISTINCT FROM example_image_object_key
                                THEN :now ELSE example_image_updated_at END,
                            row_version = row_version + 1,
                            updated_by = :actor,
                            updated_at = :now
                        WHERE id = :id AND deleted_at IS NULL AND kind = 'EXPERT'
                          AND row_version = :expected_row_version
                        """)
                .bind("id", id)
                .bind("name", command.name().strip())
                .bind("name_en", nullable(command.nameEn()))
                .bind("category", nullable(command.category()))
                .bind("tags", SkillRepository.textArray(command.tags()))
                .bind("summary", nullable(command.summary()))
                .bind("summary_en", nullable(command.summaryEn()))
                .bind("description", nullable(command.description()))
                .bind("description_en", nullable(command.descriptionEn()))
                .bind("icon", nullable(command.icon()))
                .bind("min_client_version", nullable(command.minClientVersion()))
                .bind("sort_weight", command.sortWeight() == null ? 0 : command.sortWeight())
                .bind("icon_key", nullable(iconKey))
                .bind("illustration_key", nullable(illustrationKey))
                .bind("example_image_key", nullable(exampleImageKey))
                .bind("expected_row_version", expectedRowVersion)
                .bind("actor", nullable(actor))
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .filter(rows -> rows == 1L)
                .switchIfEmpty(Mono.error(new ConcurrentAggregateException()))
                .then(upsertProfile(id, command, actor, now));
    }

    private Mono<Long> upsertProfile(
            UUID id, ExpertAggregateCommand command, UUID actor, Instant now) {
        return databaseClient
                .sql("""
                        INSERT INTO gateway.skill_expert_profile(
                            skill_id, task_types, task_types_en, usage_guide, usage_guide_en,
                            example_prompts, example_prompts_en, preview_zh, preview_en,
                            canvas_zh, canvas_en, example_image_locale,
                            portable_asset_manifest, portable_revision,
                            created_by, created_at, updated_by, updated_at)
                        VALUES (:skill_id, :task_types::text[], :task_types_en::text[],
                            :usage_guide, :usage_guide_en, :example_prompts::jsonb,
                            :example_prompts_en::jsonb, :preview_zh::jsonb, :preview_en::jsonb,
                            :canvas_zh::jsonb, :canvas_en::jsonb, :example_image_locale,
                            '{}'::jsonb, 0, :actor, :now, :actor, :now)
                        ON CONFLICT (skill_id) DO UPDATE SET
                            task_types = EXCLUDED.task_types,
                            task_types_en = EXCLUDED.task_types_en,
                            usage_guide = EXCLUDED.usage_guide,
                            usage_guide_en = EXCLUDED.usage_guide_en,
                            example_prompts = EXCLUDED.example_prompts,
                            example_prompts_en = EXCLUDED.example_prompts_en,
                            preview_zh = EXCLUDED.preview_zh,
                            preview_en = EXCLUDED.preview_en,
                            canvas_zh = EXCLUDED.canvas_zh,
                            canvas_en = EXCLUDED.canvas_en,
                            example_image_locale = EXCLUDED.example_image_locale,
                            updated_by = EXCLUDED.updated_by,
                            updated_at = EXCLUDED.updated_at
                        """)
                .bind("skill_id", id)
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
                .fetch()
                .rowsUpdated();
    }

    private String json(Object value) {
        if (value == null) return "null";
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid_expert_presentation_json", error);
        }
    }

    private Parameter jsonParameter(Object value) {
        return value == null ? Parameter.empty(String.class) : Parameter.from(json(value));
    }

    private static com.fasterxml.jackson.databind.JsonNode canvasLocale(
            com.fasterxml.jackson.databind.JsonNode canvas, String language) {
        if (canvas == null || canvas.isNull()) return null;
        if (canvas.has("version")) return "zh".equals(language) ? canvas : null;
        com.fasterxml.jackson.databind.JsonNode value = canvas.get(language);
        return value == null || value.isNull() ? null : value;
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

    static final class ConcurrentAggregateException extends RuntimeException {
        ConcurrentAggregateException() {
            super("expert_aggregate_concurrent_modification");
        }
    }
}
