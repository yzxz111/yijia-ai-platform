package com.yuzhihuihe.apiplatform.gateway.skill;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Stable {@code {code,message,details?}} envelope for {@code /console/gateway/admin/skills/**}, mirroring
 * {@code GatewayAdminExceptionHandler} (design 5.1): the console parses {@code readApiErrorMessage}
 * and maps the stable {@code code} to Chinese copy itself.
 */
@RestControllerAdvice(assignableTypes = {
        SkillAdminController.class,
        SkillExpertAggregateController.class,
        SkillExpertProfileAdminController.class,
        SkillPortableRevisionAdminController.class,
        SkillPortableImportController.class,
        ClawhubSkillImportController.class
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillAdminExceptionHandler {

    private static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("skill_not_found", "技能不存在或已被删除。"),
            Map.entry("skill_code_conflict", "该技能标识已被占用，请换一个。"),
            Map.entry("skill_version_conflict", "该版本号已存在，请使用替换资源包或换一个版本号。"),
            Map.entry("skill_concurrent_modification", "内容已被他人修改，请刷新后重试。"),
            Map.entry("skill_has_no_ready_version", "该技能还没有可发布的版本，请先上传并等待校验通过。"),
            Map.entry("skill_cannot_delete_published", "已上架的技能不能删除，请先下架。"),
            Map.entry("skill_version_online", "线上版本不能删除，请先发布其它版本或下架技能。"),
            Map.entry("skill_version_has_active_downloads", "该版本仍有未过期的下载会话，请稍后再删除。"),
            Map.entry("skill_version_not_found", "技能版本不存在或已被删除。"),
            Map.entry("ycps_package_filename_required", "请选择 .ycps.zip 资源包后再上传。"),
            Map.entry("clawhub_zip_required", "请选择下载的 ClawHub 技能 ZIP 包。"),
            Map.entry("clawhub_identity_invalid", "ClawHub 发布者或技能标识格式无效。"),
            Map.entry("clawhub_archive_unsafe", "ClawHub 包过大，或包含不安全的路径、链接或压缩条目。"),
            Map.entry("clawhub_archive_blocked", "ClawHub 技能包未通过结构安全检查，未保存。"),
            Map.entry("clawhub_expected_sha256_invalid", "上传包摘要格式无效，请重新预览后上传。"),
            Map.entry("clawhub_source_changed", "预览后文件内容发生变化，请重新预览并上传。"),
            Map.entry("clawhub_import_metadata_invalid", "导入信息格式无效，请检查名称、介绍和来源信息后重试。"),
            Map.entry("remote_skill_url_invalid", "请输入公开的 ClawHub 技能页或 GitHub 技能目录 HTTPS 链接。"),
            Map.entry("remote_skill_not_found", "远程技能、仓库或指定版本不存在，或当前链接不是公开资源。"),
            Map.entry("remote_skill_rate_limited", "远程站点请求过于频繁，请稍后重新拉取。"),
            Map.entry("remote_skill_fetch_failed", "远程技能拉取失败或超时，请检查链接后重试。"),
            Map.entry("remote_skill_timeout", "远程技能下载超时，请稍后重新拉取。"),
            Map.entry("remote_skill_upstream_failed", "远程站点暂时无法提供技能包，请稍后重试。"),
            Map.entry("remote_skill_blocked", "远程站点暂不允许下载此技能，请检查其公开状态或稍后重试。"),
            Map.entry("remote_skill_json_invalid", "远程站点返回的信息格式不正确，请检查链接后重试。"),
            Map.entry("remote_skill_archive_too_large", "远程技能包超过 50 MB 导入上限。"),
            Map.entry("remote_skill_response_too_large", "远程下载内容超过大小上限，请使用更小的技能目录。"),
            Map.entry("remote_skill_archive_unsafe", "远程技能包包含无效路径、链接或压缩条目，无法导入。"),
            Map.entry("remote_skill_document_ambiguous", "链接中没有唯一的技能文档，请粘贴含 SKILL.md 的具体技能目录链接。"),
            Map.entry("remote_skill_redirect_limit", "远程下载跳转次数过多，请使用技能页的原始链接。"),
            Map.entry("remote_skill_redirect_missing", "远程站点未返回有效下载地址，请稍后重试。"),
            Map.entry("remote_skill_redirect_unsafe", "远程下载跳转到了不支持的地址，请使用 ClawHub 或 GitHub 原始链接。"),
            Map.entry("clawhub_owner_mismatch", "ClawHub 返回的发布者或技能标识与链接不一致，请检查链接。"),
            Map.entry("clawhub_install_unavailable", "此 ClawHub 技能当前不可下载，请检查其上架和扫描状态。"),
            Map.entry("clawhub_install_response_invalid", "ClawHub 返回的安装信息无法识别，请稍后重试。"),
            Map.entry("clawhub_version_invalid", "ClawHub 链接或安装信息中的版本号无效。"),
            Map.entry("clawhub_version_manifest_invalid", "ClawHub 未返回有效的版本文件清单，无法确认原包完整性。"),
            Map.entry("clawhub_version_manifest_mismatch", "下载文件与 ClawHub 版本清单不一致，请重新拉取。"),
            Map.entry("clawhub_download_url_invalid", "ClawHub 下载地址与所选技能或版本不一致。"),
            Map.entry("clawhub_download_not_zip", "ClawHub 未返回有效的原始 ZIP 包。"),
            Map.entry("clawhub_handoff_invalid", "ClawHub 返回的 GitHub 来源信息不完整，无法导入。"),
            Map.entry("clawhub_github_content_hash_mismatch", "GitHub 技能内容与 ClawHub 公布的指纹不一致，请重新拉取。"),
            Map.entry("github_repository_invalid", "GitHub 仓库信息无效，请检查仓库是否公开可用。"),
            Map.entry("github_reference_invalid", "GitHub 分支、标签或提交无效，请检查链接。"),
            Map.entry("github_commit_invalid", "无法确定 GitHub 技能的固定提交，请重新拉取。"),
            Map.entry("github_tree_path_invalid", "GitHub 分支或技能目录不存在，请粘贴具体技能目录链接。"),
            Map.entry("github_repository_archive_too_many_entries", "GitHub 仓库文件过多，请使用具体技能目录链接。"),
            Map.entry("github_tree_response_invalid", "GitHub 未返回完整目录信息，请稍后重新拉取。"),
            Map.entry("github_tree_entry_unsafe", "GitHub 技能目录含链接、子模块或不支持的文件，无法完整导入。"),
            Map.entry("github_tree_entry_limit", "GitHub 技能目录文件数量超过导入上限，请缩小目录范围。"),
            Map.entry("github_tree_blob_mismatch", "GitHub 文件内容与指定提交不一致，请重新拉取。"),
            Map.entry("github_document_link_invalid", "请使用 GitHub 技能目录或 SKILL.md 文档链接。"),
            Map.entry("github_skill_path_empty", "GitHub 指定目录中没有可导入的技能文件。"),
            Map.entry("github_archive_empty", "GitHub 返回了空的资源包。"),
            Map.entry("github_archive_not_zip", "GitHub 未返回有效的 ZIP 资源包。"),
            Map.entry("github_archive_root_invalid", "GitHub 资源包目录结构无效，无法定位技能。"),
            Map.entry("github_archive_expanded_too_large", "GitHub 技能目录展开后超过导入上限，请缩小目录范围。"),
            Map.entry("clawhub_skill_missing", "ClawHub 包中未找到 SKILL.md。"),
            Map.entry("clawhub_multiple_skill_roots", "ClawHub 包中存在多个技能根目录，请只保留一个技能。"),
            Map.entry("clawhub_frontmatter_invalid", "SKILL.md 的开头信息无效，请检查 YAML 格式。"),
            Map.entry("clawhub_origin_invalid", "ClawHub 来源记录无法读取，请重新下载技能包。"),
            Map.entry("native_clawhub_already_exists", "该 ClawHub 发布者与技能标识已导入，不能覆盖现有原始包。"),
            Map.entry("native_clawhub_version_required", "请先补录有效的 ClawHub 版本号，再发布。"),
            Map.entry("native_clawhub_version_invalid", "版本号应为有效的 SemVer，例如 1.2.3。"),
            Map.entry("native_clawhub_version_already_set", "该原始来源已有版本号，不能覆盖。"),
            Map.entry("native_clawhub_version_already_exists", "该技能已存在相同版本号或相同原始包，请检查后再上传。"),
            Map.entry("native_clawhub_version_id_required", "该技能已有多个原始版本，请在版本列表中指定要上架的版本。"),
            Map.entry("native_clawhub_identity_mismatch", "上传包中的 ClawHub 发布者或技能标识与当前技能不一致。"),
            Map.entry("native_clawhub_current_version_delete_forbidden", "当前上架版本不能删除，请先切换上架版本。"),
            Map.entry("native_clawhub_published_version_delete_forbidden", "曾上架过的原始版本会保留为历史记录，不能删除。"),
            Map.entry("native_clawhub_version_delete_forbidden", "只有未上架过的草稿版本可以删除。"),
            Map.entry("native_clawhub_version_draft_only", "仅待审核的草稿来源可以补录版本号。"),
            Map.entry("native_clawhub_rescan_draft_only", "仅待审核的原生 ClawHub 草稿可以重新扫描。"),
            Map.entry("native_clawhub_rescan_not_required", "该来源当前无需重新扫描，请刷新后重试。"),
            Map.entry("native_clawhub_source_rejected", "该原始包已被安全扫描拒绝，不能重复导入或发布。"),
            Map.entry("native_clawhub_source_not_found", "找不到该技能对应的 ClawHub 原始包。"),
            Map.entry("native_clawhub_source_integrity_failed", "ClawHub 原始包大小或摘要校验失败。"),
            Map.entry("native_clawhub_source_missing", "ClawHub 原始包对象不存在，无法下载或扫描。"),
            Map.entry("native_clawhub_release_conflict", "已发布的 ClawHub 原始包记录不完整，请检查来源关联。"),
            Map.entry("native_clawhub_ycps_route_forbidden", "这是 ClawHub 原生包，请使用原生来源的发布入口。"),
            Map.entry("native_clawhub_scan_not_passed", "原始包尚未通过恶意软件扫描，不能发布。"),
            Map.entry("native_clawhub_source_format_mismatch", "技能来源类型不匹配，请刷新后重试。"),
            Map.entry("ycps_package_invalid", "资源包不符合 YCPS 1.0 结构或 Manifest 约束。"),
            Map.entry("package_identity_mismatch", "YCPS 包内 code、version 或 kind 与上传目标不一致。"),
            Map.entry("ycps_package_unsafe_entry", "YCPS 包包含不安全路径、链接、重名或异常压缩条目。"),
            Map.entry("ycps_package_too_large", "YCPS 包超过对应类型的大小上限。"),
            Map.entry("ycps_dependency_unresolved", "YCPS 包引用了未发布或摘要不匹配的精确依赖。"),
            Map.entry("skill_version_has_dependents", "该精确版本仍被 YCPS 包引用，不能删除。"),
            Map.entry("skill_has_active_dependents", "该技能仍被 YCPS 包引用，不能删除。"),
            Map.entry("skill_dependency_cycle", "依赖闭包存在循环引用，无法发布。"),
            Map.entry("skill_dependency_depth_exceeded", "依赖闭包深度超过 32 层，无法发布。"),
            Map.entry("skill_dependency_count_exceeded", "依赖闭包包数量超过 128，无法发布。"),
            Map.entry("skill_dependency_lock_conflict", "依赖闭包对同一包锁定了不同版本或摘要。"),
            Map.entry("skill_version_sealed", "该版本已发布并封存，不能原地替换；请发布新的 SemVer。"),
            Map.entry("skill_version_not_publishable", "该 YCPS 版本无法封存或发布。"),
            Map.entry("skill_package_too_large", "技能包超过大小上限。"),
            Map.entry("skill_package_mismatch", "技能包上传后校验不一致，请重试。"),
            Map.entry("skill_package_rejected", "技能包未通过内容安全扫描，已拒绝上传。"),
            Map.entry("skill_scan_unavailable", "内容安全扫描服务暂时不可用，请稍后重试。"),
            Map.entry("skill_icon_too_large", "图标文件超过大小上限。"),
            Map.entry("skill_storage_unavailable", "对象存储暂时不可用，请稍后重试。"),
            Map.entry("invalid_skill_kind", "技能类型只能是 SKILL、EXPERT 或 TEAM，且只能在创建时设定。"),
            Map.entry("invalid_expert_manifest", "技能包内的 plugin.json 不是合法的专家清单。"),
            Map.entry("expert_kind_mismatch", "技能包 plugin.json 的 expertType 与技能类型不一致，已拒绝上传。"),
            Map.entry("invalid_skill_member", "成员花名册无效：标识重复、角色非法或出现多位主理人。"),
            Map.entry("skill_member_not_found", "成员不存在。"),
            Map.entry("skill_illustration_not_found", "该资源包没有介绍插画。"),
            Map.entry("skill_illustration_too_large", "介绍插画超过大小上限。"),
            Map.entry("skill_example_image_not_found", "该资源包没有示例图片。"),
            Map.entry("skill_example_image_too_large", "示例图片超过大小上限。"),
            Map.entry("expert_presentation_asset_invalid", "专家展示图片不是可便携的 PNG、JPEG 或 WebP。"),
            Map.entry("expert_presentation_asset_too_large", "专家展示图片超过便携包限制。"),
            Map.entry("skill_expert_profile_required", "请填写专家展示内容。"),
            Map.entry("skill_expert_profile_kind_required", "只有 EXPERT 类型可以维护专家展示内容。"),
            Map.entry("skill_expert_profile_concurrent_modification", "专家展示内容已被他人修改，请刷新后重试。"),
            Map.entry("skill_expert_profile_row_version_required", "缺少专家展示内容版本号。"),
            Map.entry("skill_expert_profile_task_types_invalid", "专家业务标签数量或长度无效。"),
            Map.entry("skill_expert_profile_task_types_required", "至少填写一个中文专家业务类型。"),
            Map.entry("skill_expert_profile_usage_guide_invalid", "专家使用指南长度无效。"),
            Map.entry("skill_expert_profile_usage_guide_required", "请填写中文专家使用指南。"),
            Map.entry("skill_expert_profile_prompts_invalid", "专家示例提示词数量、长度或重复项无效。"),
            Map.entry("skill_expert_profile_prompts_required", "至少填写一个中文示例提示词。"),
            Map.entry("skill_expert_profile_preview_invalid", "专家示例预览结构无效。"),
            Map.entry("skill_expert_profile_preview_required", "请填写中文专家示例预览及输出段落。"),
            Map.entry("skill_expert_profile_example_image_locale_invalid", "示例图片语言只能是 zh、en 或 neutral。"),
            Map.entry("skill_portable_import_profile_required", "仅支持 YCPS 1.1 Expert presentation 包。"),
            Map.entry("skill_portable_import_expired", "导入确认已过期，请重新上传资源包。"),
            Map.entry("skill_portable_import_sha256_changed", "导入对象摘要已变化，请重新上传资源包。"),
            Map.entry("skill_portable_import_code_kind_conflict", "包标识或类型与目标专家不一致。"),
            Map.entry("skill_portable_import_operational_field", "分类、标签、排序和发布状态由运营端维护，不能从包导入。"),
            Map.entry("skill_portable_import_merge_not_supported", "只有任务类型和示例提示词支持合并。"),
            Map.entry("skill_portable_import_field_action_invalid", "导入字段动作无效。"),
            Map.entry("skill_portable_import_image_action_invalid", "导入图片动作无效。"),
            Map.entry("skill_portable_import_not_found", "导入记录不存在或已被清理。"),
            Map.entry("skill_portable_import_not_committable", "导入记录当前不可确认。"),
            Map.entry("expert_presentation_catalog_copy_invalid", "专家展示文案超出目录字段长度限制。"),
            Map.entry("expert_presentation_example_image_locale_invalid", "示例图片语言只能是 zh、en 或 neutral。"),
            Map.entry("skill_restore_expired", "该记录已超过 30 天恢复期限，无法恢复。"),
            Map.entry("skill_recycle_delete_blocked", "仍有进行中的下载，暂时无法彻底删除。"),
            Map.entry("invalid_pagination", "分页参数无效：page 从 0 开始，size 取值 1-100。"),
            Map.entry("invalid_sort", "排序字段不受支持。"),
            Map.entry("invalid_sort_direction", "排序方向只能是 asc 或 desc。"),
            Map.entry("invalid_skill_status", "技能状态筛选无效。"),
            Map.entry("invalid_skill_category", "分类无效：名称不能为空且不超过 32 个字符，编码只能是小写字母、数字与连字符。"),
            Map.entry("skill_category_name_conflict", "该分类名称已存在，请换一个。"),
            Map.entry("skill_category_code_conflict", "该分类编码已被占用，请换一个。"),
            Map.entry("skill_category_not_found", "分类不存在或已被删除。"),
            Map.entry("skill_category_in_use", "该分类下仍有包在使用，请先改归类或移除这些包后再删除。"));

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<AdminErrorResponse> handle(
            ResponseStatusException error, ServerWebExchange exchange) {
        String code = error.getReason() == null ? "gateway_skill_operation_failed" : error.getReason();
        Object matched = exchange.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        ExpertAggregateValidationErrors.Descriptor expert = matched instanceof HandlerMethod method
                && SkillExpertAggregateController.class.isAssignableFrom(method.getBeanType())
                        ? ExpertAggregateValidationErrors.describe(code) : null;
        Map<String, Object> details = expert == null ? Map.of()
                : error instanceof ExpertFieldValidationException fieldError
                        ? Map.of("field", fieldError.field()) : expert.details();
        return ResponseEntity.status(error.getStatusCode())
                .body(expert == null
                        ? new AdminErrorResponse(code,
                                message(code, error.getStatusCode().is4xxClientError()))
                        : new AdminErrorResponse(code, expert.message(), details));
    }

    @ExceptionHandler(SkillAdminException.class)
    ResponseEntity<AdminErrorResponse> handle(SkillAdminException error) {
        return ResponseEntity.status(error.status())
                .body(new AdminErrorResponse(error.code(), error.getMessage(), error.details()));
    }

    private static String message(String code, boolean clientError) {
        String known = MESSAGES.get(code);
        if (known != null) {
            return known;
        }
        return clientError ? "请求未通过校验，请检查后重试。" : "技能管理服务暂时不可用，请稍后重试。";
    }

    /**
     * {@code details} is omitted when empty so every existing code keeps its exact
     * {@code {code,message}} shape; only codes that opt in gain the extra key.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record AdminErrorResponse(String code, String message, Map<String, Object> details) {
        AdminErrorResponse(String code, String message) {
            this(code, message, Map.of());
        }
    }
}
