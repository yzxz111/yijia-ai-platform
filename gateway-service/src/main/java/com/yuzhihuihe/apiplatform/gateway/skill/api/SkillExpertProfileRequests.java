package com.yuzhihuihe.apiplatform.gateway.skill.api;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** Request contracts for the structured Expert presentation editor. */
public final class SkillExpertProfileRequests {

    private SkillExpertProfileRequests() {}

    /**
     * A complete replacement of the editable presentation fields.  The parent skill's
     * {@code row_version} is used as the aggregate optimistic-lock token; a missing token is only
     * accepted when the profile does not exist yet.
     */
    public record ExpertPresentationCommand(
            List<String> taskTypes,
            List<String> taskTypesEn,
            String usageGuide,
            String usageGuideEn,
            List<String> examplePrompts,
            List<String> examplePromptsEn,
            SkillExpertProfileViews.ExpertPreviewContent preview,
            SkillExpertProfileViews.ExpertPreviewContent previewEn,
            Integer rowVersion,
            JsonNode canvas,
            String exampleImageLocale) {
        /** Backwards-compatible constructor for the legacy profile-only endpoint. */
        public ExpertPresentationCommand(
                List<String> taskTypes,
                List<String> taskTypesEn,
                String usageGuide,
                String usageGuideEn,
                List<String> examplePrompts,
                List<String> examplePromptsEn,
                SkillExpertProfileViews.ExpertPreviewContent preview,
                SkillExpertProfileViews.ExpertPreviewContent previewEn,
                Integer rowVersion) {
            this(taskTypes, taskTypesEn, usageGuide, usageGuideEn, examplePrompts,
                    examplePromptsEn, preview, previewEn, rowVersion, null, null);
        }

        /** Compatibility constructor for clients that do not send the optional image locale. */
        public ExpertPresentationCommand(
                List<String> taskTypes,
                List<String> taskTypesEn,
                String usageGuide,
                String usageGuideEn,
                List<String> examplePrompts,
                List<String> examplePromptsEn,
                SkillExpertProfileViews.ExpertPreviewContent preview,
                SkillExpertProfileViews.ExpertPreviewContent previewEn,
                Integer rowVersion,
                JsonNode canvas) {
            this(taskTypes, taskTypesEn, usageGuide, usageGuideEn, examplePrompts,
                    examplePromptsEn, preview, previewEn, rowVersion, canvas, null);
        }

        public ExpertPresentationCommand {
            canvas = canvas == null ? null : canvas.deepCopy();
        }
    }

    /**
     * The aggregate write contract used by the Expert WYSIWYG editor.  Unlike the legacy JSON
     * profile command above, this command carries the complete management form and the image
     * actions in one request.  The image action object is intentionally a JsonNode: accepting both
     * {@code images} and the older draft bridge's {@code imageActions} spelling keeps the browser
     * contract forwards compatible without making image files part of JSON.
     */
    public record ExpertAggregateCommand(
            Integer expectedRowVersion,
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
            String minClientVersion,
            Integer sortWeight,
            String status,
            List<String> taskTypes,
            List<String> taskTypesEn,
            String usageGuide,
            String usageGuideEn,
            List<String> examplePrompts,
            List<String> examplePromptsEn,
            SkillExpertProfileViews.ExpertPreviewContent preview,
            SkillExpertProfileViews.ExpertPreviewContent previewEn,
            JsonNode images,
            JsonNode imageActions,
            String iconAction,
            String illustrationAction,
            String exampleImageAction,
            JsonNode canvas,
            String exampleImageLocale) {

        /** Backwards-compatible constructor for callers that predate canvas persistence. */
        public ExpertAggregateCommand(
                Integer expectedRowVersion,
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
                String minClientVersion,
                Integer sortWeight,
                String status,
                List<String> taskTypes,
                List<String> taskTypesEn,
                String usageGuide,
                String usageGuideEn,
                List<String> examplePrompts,
                List<String> examplePromptsEn,
                SkillExpertProfileViews.ExpertPreviewContent preview,
                SkillExpertProfileViews.ExpertPreviewContent previewEn,
                JsonNode images,
                JsonNode imageActions,
                String iconAction,
                String illustrationAction,
                String exampleImageAction) {
            this(expectedRowVersion, code, name, nameEn, category, tags, summary, summaryEn,
                    description, descriptionEn, icon, minClientVersion, sortWeight, status,
                    taskTypes, taskTypesEn, usageGuide, usageGuideEn, examplePrompts,
                    examplePromptsEn, preview, previewEn, images, imageActions, iconAction,
                    illustrationAction, exampleImageAction, null, null);
        }

        /** Compatibility constructor for callers that do not send the optional image locale. */
        public ExpertAggregateCommand(
                Integer expectedRowVersion,
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
                String minClientVersion,
                Integer sortWeight,
                String status,
                List<String> taskTypes,
                List<String> taskTypesEn,
                String usageGuide,
                String usageGuideEn,
                List<String> examplePrompts,
                List<String> examplePromptsEn,
                SkillExpertProfileViews.ExpertPreviewContent preview,
                SkillExpertProfileViews.ExpertPreviewContent previewEn,
                JsonNode images,
                JsonNode imageActions,
                String iconAction,
                String illustrationAction,
                String exampleImageAction,
                JsonNode canvas) {
            this(expectedRowVersion, code, name, nameEn, category, tags, summary, summaryEn,
                    description, descriptionEn, icon, minClientVersion, sortWeight, status,
                    taskTypes, taskTypesEn, usageGuide, usageGuideEn, examplePrompts,
                    examplePromptsEn, preview, previewEn, images, imageActions, iconAction,
                    illustrationAction, exampleImageAction, canvas, null);
        }

        public ExpertAggregateCommand {
            tags = tags == null ? List.of() : List.copyOf(tags);
            taskTypes = taskTypes == null ? List.of() : List.copyOf(taskTypes);
            taskTypesEn = taskTypesEn == null ? List.of() : List.copyOf(taskTypesEn);
            examplePrompts = examplePrompts == null ? List.of() : List.copyOf(examplePrompts);
            examplePromptsEn = examplePromptsEn == null ? List.of() : List.copyOf(examplePromptsEn);
            canvas = canvas == null ? null : canvas.deepCopy();
        }

        /** Resolves an action from the canonical object and the compatibility scalar aliases. */
        public ImageAction imageAction(String role, boolean creating) {
            String scalar = switch (role) {
                case "icon" -> iconAction;
                case "illustration" -> illustrationAction;
                case "exampleImage" -> exampleImageAction;
                default -> null;
            };
            String value = scalar;
            JsonNode object = images != null && images.isObject() ? images : imageActions;
            if ((value == null || value.isBlank()) && object != null && object.isObject()) {
                JsonNode node = object.get(role);
                if (node != null && node.isTextual()) {
                    value = node.asText();
                }
            }
            if (value == null || value.isBlank()) {
                return creating ? ImageAction.CLEAR : ImageAction.KEEP;
            }
            try {
                return ImageAction.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("invalid_expert_image_action_" + role);
            }
        }
    }

    public enum ImageAction {
        KEEP,
        REPLACE,
        CLEAR
    }
}
