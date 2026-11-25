package com.yuzhihuihe.apiplatform.gateway.skill.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillView;

/** Admin/public DTOs for the WYSIWYG Expert presentation contract. */
public final class SkillExpertProfileViews {

    private SkillExpertProfileViews() {}

    /** The structured left-column example; it intentionally contains no HTML. */
    public record ExpertPreviewContent(
            String heading,
            String headingPrefix,
            String scene,
            String request,
            String material,
            String outputTitle,
            List<String> outputParagraphs,
            ExpertPreviewBlock intro,
            ExpertPreviewTable table,
            List<ExpertPreviewSection> sections,
            List<ExpertPreviewBlock> blocks) {
        /** Backwards-compatible six-field constructor used by YCPS 1.1 legacy callers. */
        public ExpertPreviewContent(
                String heading,
                String scene,
                String request,
                String material,
                String outputTitle,
                List<String> outputParagraphs) {
            this(heading, null, scene, request, material, outputTitle, outputParagraphs,
                    null, null, null, null);
        }

        public ExpertPreviewContent {
            outputParagraphs = outputParagraphs == null ? List.of() : List.copyOf(outputParagraphs);
            if (sections != null) sections = List.copyOf(sections);
            if (blocks != null) blocks = List.copyOf(blocks);
        }
    }

    /** A rich preview paragraph.  The optional tone is a presentation hint, never HTML. */
    public record ExpertPreviewBlock(String tone, String text) {}

    /** A semantic table carried by a preview.  Cell values are JSON scalar values. */
    public record ExpertPreviewTable(
            List<String> headers,
            List<Integer> columnWidths,
            List<List<Object>> rows,
            Boolean totalRow,
            ExpertPreviewTableStyle style) {
        /** Backwards-compatible constructor for the original semantic table contract. */
        public ExpertPreviewTable(
                List<String> headers,
                List<Integer> columnWidths,
                List<List<Object>> rows,
                Boolean totalRow) {
            this(headers, columnWidths, rows, totalRow, null);
        }

        public ExpertPreviewTable {
            headers = headers == null ? List.of() : List.copyOf(headers);
            columnWidths = columnWidths == null ? List.of() : List.copyOf(columnWidths);
            rows = rows == null ? List.of() : rows.stream().map(row ->
                    row == null ? List.of() : List.copyOf(row)).toList();
        }
    }

    /**
     * Optional presentation hints for a semantic preview table.  Values are deliberately
     * table-scoped: cell-level typography continues to live in the canvas table node when an
     * editor needs per-cell runs, while this style is sufficient for public/detail renderers and
     * for the compact YCPS table metadata value.
     */
    public record ExpertPreviewTableStyle(
            Integer fontSize,
            String color,
            String headerColor,
            String headerBackgroundColor,
            String borderColor,
            Integer borderRadius,
            Integer cellPadding,
            Integer rowHeight) {
        /** Compatibility constructor for the initial six-field style contract. */
        public ExpertPreviewTableStyle(
                Integer fontSize,
                String color,
                String headerColor,
                String headerBackgroundColor,
                String borderColor,
                Integer borderRadius) {
            this(fontSize, color, headerColor, headerBackgroundColor, borderColor,
                    borderRadius, null, null);
        }
    }

    /** A titled group of rich preview paragraphs. */
    public record ExpertPreviewSection(String title, List<ExpertPreviewBlock> blocks) {
        public ExpertPreviewSection {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
        }
    }

    /**
     * Shared shape used by the admin editor and the public detail response.  The asset values are
     * role-to-URL/path mappings; a public response contains URLs while a portable import report
     * contains package-relative paths.
     */
    public record ExpertPresentation(
            String title,
            String titleEn,
            String summary,
            String summaryEn,
            String description,
            String descriptionEn,
            List<String> taskTypes,
            List<String> taskTypesEn,
            String usageGuide,
            String usageGuideEn,
            List<String> examplePrompts,
            List<String> examplePromptsEn,
            ExpertPreviewContent preview,
            ExpertPreviewContent previewEn,
            Map<String, String> assets,
            int portableRevision,
            int rowVersion,
            Instant updatedAt,
            JsonNode canvas,
            String exampleImageLocale) {
        /** Backwards-compatible constructor for callers that predate the optional canvas. */
        public ExpertPresentation(
                String title,
                String titleEn,
                String summary,
                String summaryEn,
                String description,
                String descriptionEn,
                List<String> taskTypes,
                List<String> taskTypesEn,
                String usageGuide,
                String usageGuideEn,
                List<String> examplePrompts,
                List<String> examplePromptsEn,
                ExpertPreviewContent preview,
                ExpertPreviewContent previewEn,
                Map<String, String> assets,
                int portableRevision,
                int rowVersion,
                Instant updatedAt) {
            this(title, titleEn, summary, summaryEn, description, descriptionEn,
                    taskTypes, taskTypesEn, usageGuide, usageGuideEn, examplePrompts,
                    examplePromptsEn, preview, previewEn, assets, portableRevision,
                    rowVersion, updatedAt, null, null);
        }

        /** Backwards-compatible constructor for callers that already persist a canvas snapshot. */
        public ExpertPresentation(
                String title,
                String titleEn,
                String summary,
                String summaryEn,
                String description,
                String descriptionEn,
                List<String> taskTypes,
                List<String> taskTypesEn,
                String usageGuide,
                String usageGuideEn,
                List<String> examplePrompts,
                List<String> examplePromptsEn,
                ExpertPreviewContent preview,
                ExpertPreviewContent previewEn,
                Map<String, String> assets,
                int portableRevision,
                int rowVersion,
                Instant updatedAt,
                JsonNode canvas) {
            this(title, titleEn, summary, summaryEn, description, descriptionEn,
                    taskTypes, taskTypesEn, usageGuide, usageGuideEn, examplePrompts,
                    examplePromptsEn, preview, previewEn, assets, portableRevision,
                    rowVersion, updatedAt, canvas, null);
        }

        public ExpertPresentation {
            taskTypes = taskTypes == null ? List.of() : List.copyOf(taskTypes);
            taskTypesEn = taskTypesEn == null ? List.of() : List.copyOf(taskTypesEn);
            examplePrompts = examplePrompts == null ? List.of() : List.copyOf(examplePrompts);
            examplePromptsEn = examplePromptsEn == null ? List.of() : List.copyOf(examplePromptsEn);
            assets = assets == null ? Map.of() : Map.copyOf(assets);
            canvas = canvas == null ? null : canvas.deepCopy();
        }
    }

    public record AdminExpertPresentation(
            UUID skillId,
            String code,
            String kind,
            ExpertPresentation presentation) {}

    /** Response envelope for the atomic Expert create/save endpoints. */
    public record ExpertAggregateResponse(
            UUID skillId,
            SkillView skill,
            ExpertPresentation presentation) {}

    public record PublicExpertPresentation(
            String object,
            String code,
            String kind,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            Instant iconUpdatedAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            Instant illustrationUpdatedAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            Instant exampleImageUpdatedAt,
            ExpertPresentation presentation) {
        public static final String OBJECT = "expert_presentation";

        public PublicExpertPresentation {
            object = object == null ? OBJECT : object;
        }
    }
}
