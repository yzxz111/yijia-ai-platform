package com.yuzhihuihe.apiplatform.gateway.skill;

import java.util.HashMap;
import java.util.Map;

/**
 * Operator-facing descriptions for the Expert aggregate's validation codes. Only server-defined
 * codes and fields are returned: request values, filenames, parser exceptions and storage keys
 * must never become error copy.
 */
final class ExpertAggregateValidationErrors {

    private static final Map<String, Descriptor> ERRORS = build();

    private ExpertAggregateValidationErrors() {}

    static Descriptor describe(String code) {
        return ERRORS.get(code);
    }

    private static Map<String, Descriptor> build() {
        Map<String, Descriptor> errors = new HashMap<>();
        add(errors, "expert_aggregate_json_required", "提交内容为空，请重新保存。", "data");
        add(errors, "expert_aggregate_json_invalid", "提交内容格式无效，请刷新页面后重试。", "data");
        add(errors, "expert_aggregate_required", "缺少专家内容，请重新保存。", "data");
        add(errors, "expected_row_version_required", "缺少内容版本号，请刷新页面后重试。",
                "expectedRowVersion");
        add(errors, "expected_row_version_invalid", "内容版本号无效，请刷新页面后重试。",
                "expectedRowVersion");

        add(errors, "skill_code_required", "请填写专家标识。", "code");
        add(errors, "invalid_skill_code", "专家标识只能包含小写字母、数字和中间的连字符，最多 64 个字符。", "code");
        add(errors, "skill_code_immutable", "专家标识创建后不能修改。", "code");
        add(errors, "skill_name_required", "请填写中文名称。", "name");
        add(errors, "skill_name_invalid", "中文名称不能超过 128 个字符。", "name");
        add(errors, "skill_name_en_invalid", "英文名称不能超过 128 个字符。", "nameEn");
        add(errors, "skill_category_invalid", "分类不能超过 32 个字符。", "category");
        add(errors, "skill_tags_invalid", "标签最多 16 个，每个标签不能为空且不能超过 64 个字符。", "tags");
        add(errors, "skill_summary_required", "请填写中文简介。", "summary");
        add(errors, "skill_summary_invalid", "中文简介不能超过 512 个字符。", "summary");
        add(errors, "skill_summary_en_invalid", "英文简介不能超过 512 个字符。", "summaryEn");
        add(errors, "skill_description_required", "请填写中文详情。", "description");
        add(errors, "skill_description_invalid", "中文详情不能超过 1024 个字符。", "description");
        add(errors, "skill_description_en_invalid", "英文详情不能超过 1024 个字符。", "descriptionEn");
        add(errors, "skill_icon_invalid", "文字图标不能超过 8 个字符。", "icon");
        add(errors, "skill_min_client_version_invalid", "最低客户端版本格式无效，请使用年.月或年.月.修订号。",
                "minClientVersion");
        add(errors, "skill_sort_weight_invalid", "排序权重不能小于 0。", "sortWeight");

        add(errors, "skill_expert_profile_task_types_required", "请填写至少一个中文专家业务类型。", "taskTypes");
        add(errors, "skill_expert_profile_task_types_invalid",
                "专家业务类型最多 6 个，每项不能为空或超过 64 个字符，且不能重复。", "taskTypes");
        add(errors, "skill_expert_profile_usage_guide_required", "请填写中文使用指南。", "usageGuide");
        add(errors, "skill_expert_profile_usage_guide_invalid", "使用指南不能超过 1024 个字符。",
                "usageGuide");
        add(errors, "skill_expert_profile_prompts_required", "请填写至少一个中文示例提示词。",
                "examplePrompts");
        add(errors, "skill_expert_profile_prompts_invalid",
                "示例提示词最多 8 个，每项不能为空或超过 300 个字符，且不能重复。", "examplePrompts");
        add(errors, "skill_expert_profile_preview_required", "请填写中文示例预览及输出段落。",
                "preview");
        add(errors, "skill_expert_profile_preview_invalid", "示例预览的文字或表格内容无效。", "preview");
        add(errors, "skill_expert_profile_canvas_invalid", "画布的节点、尺寸或表格内容无效。", "canvas");
        add(errors, "skill_expert_profile_canvas_too_large", "画布内容超过 32 KiB 限制。", "canvas");
        add(errors, "skill_expert_profile_example_image_locale_invalid",
                "示例图片语言只能是 zh、en 或 neutral。", "exampleImageLocale");

        add(errors, "expert_images_total_too_large", "三张专家图片总大小不能超过 1.25 MiB。", "images");
        add(errors, "invalid_expert_image_image_dimensions", "图片尺寸无效。", "images");
        image(errors, "icon", "图标", "128 KiB");
        image(errors, "illustration", "介绍插画", "640 KiB");
        image(errors, "exampleImage", "示例图片", "512 KiB");
        return Map.copyOf(errors);
    }

    private static void image(Map<String, Descriptor> errors, String role,
            String label, String limit) {
        String field = "images." + role;
        add(errors, "invalid_expert_image_action_" + role, label + "的操作无效。", field);
        add(errors, "expert_image_file_required_" + role,
                "已选择替换" + label + "，请上传对应文件。", field);
        add(errors, "expert_image_missing_" + role,
                label + "文件不存在，请重新上传。", field);
        add(errors, "expert_image_empty_" + role, label + "文件为空，请重新上传。", field);
        add(errors, "expert_image_too_large_" + role,
                label + "超过 " + limit + " 大小限制。", field);
        add(errors, "invalid_expert_image_" + role + "_empty", label + "文件为空。", field);
        add(errors, "invalid_expert_image_" + role + "_type",
                label + "只能使用 PNG、JPEG 或 WebP 图片。", field);
        add(errors, "invalid_expert_image_" + role + "_magic",
                label + "文件内容不是有效的 PNG、JPEG 或 WebP 图片。", field);
        add(errors, "invalid_expert_image_" + role + "_type_magic_mismatch",
                label + "文件类型与实际图片格式不一致。", field);
        add(errors, "invalid_expert_image_" + role + "_dimensions", label + "尺寸无效。", field);
        add(errors, "invalid_expert_image_" + role + "_png_header",
                label + "的 PNG 文件头无效。", field);
        add(errors, "invalid_expert_image_" + role + "_jpeg_header",
                label + "的 JPEG 文件头无效。", field);
        add(errors, "invalid_expert_image_" + role + "_webp_size",
                label + "的 WebP 文件大小信息无效。", field);
        add(errors, "invalid_expert_image_" + role + "_webp_header",
                label + "的 WebP 文件头无效。", field);
        add(errors, "invalid_expert_image_" + role + "_role", label + "的图片类型无效。", field);
        switch (role) {
            case "icon" -> add(errors, "invalid_expert_image_icon_icon_dimensions",
                    "图标必须为正方形，宽高均不超过 512 像素。", field);
            case "illustration" -> add(errors,
                    "invalid_expert_image_illustration_illustration_dimensions",
                    "介绍插画必须为 3:1，宽不超过 1440 像素、高不超过 480 像素。", field);
            case "exampleImage" -> add(errors,
                    "invalid_expert_image_exampleImage_example_dimensions",
                    "示例图片宽不能超过 1440 像素、高不能超过 1080 像素。", field);
            default -> throw new IllegalArgumentException("unsupported image role");
        }
    }

    private static void add(Map<String, Descriptor> errors, String code, String message,
            String field) {
        if (errors.putIfAbsent(code, new Descriptor(message, field)) != null) {
            throw new IllegalStateException("duplicate expert validation code: " + code);
        }
    }

    record Descriptor(String message, String field) {
        Map<String, Object> details() {
            return Map.of("field", field);
        }
    }
}
