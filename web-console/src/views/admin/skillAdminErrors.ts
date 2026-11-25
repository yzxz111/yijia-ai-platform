/**
 * `/console/gateway/admin/skills/**` 错误码 → 稳定中文文案。
 *
 * 管理面用的是网关管理端既有的**扁平信封** `{code,message}`（见设计 §5.1），
 * 与 `/v1/**`、`/console/gateway/skills` 的 OpenAI 信封
 * `{"error":{"code","message"},"request_id"}` **不是同一套**，
 * 所以这里用 `readApiErrorCode` 取值，而**不复用** `./skillErrors`。
 *
 * 页面只展示本文件的映射文案，不直接把服务端 `message` 渲染到界面上
 * （避免把英文原文、对象存储 Key、扫描规则细节暴露给管理员）。
 */
import { readApiErrorCode } from '../../api/apiError'

/** 专家聚合保存的图片错误码按角色生成，避免把大小、格式和尺寸提示写串。 */
const EXPERT_IMAGE_ROLES = {
  icon: { label: '头像', maxSize: '128 KiB', dimensions: '正方形，宽高均不超过 512 像素' },
  illustration: { label: '介绍插画', maxSize: '640 KiB', dimensions: '3:1，最大 1440 × 480 像素' },
  exampleImage: { label: '示例图片', maxSize: '512 KiB', dimensions: '最大 1440 × 1080 像素' },
} as const

function expertImageErrorMessages(): Record<string, string> {
  const messages: Record<string, string> = {}
  for (const [role, { label, maxSize, dimensions }] of Object.entries(EXPERT_IMAGE_ROLES)) {
    messages[`expert_image_file_required_${role}`] = `请重新选择${label}后再保存。`
    messages[`expert_image_missing_${role}`] = `已保存的${label}文件不可用，请重新上传。`
    messages[`expert_image_empty_${role}`] = `${label}文件为空，请重新选择。`
    messages[`expert_image_too_large_${role}`] = `${label}不能超过 ${maxSize}，请压缩后重试。`
    messages[`invalid_expert_image_action_${role}`] = `${label}的保存操作无效，请重新选择图片后重试。`
    messages[`invalid_expert_image_${role}_empty`] = `${label}文件为空，请重新选择。`
    messages[`invalid_expert_image_${role}_type`] = `${label}只支持 PNG、JPEG 或 WebP。`
    messages[`invalid_expert_image_${role}_magic`] = `${label}文件内容不是有效的 PNG、JPEG 或 WebP，请重新导出。`
    messages[`invalid_expert_image_${role}_type_magic_mismatch`] = `${label}文件格式与内容不一致，请重新导出。`
    messages[`invalid_expert_image_${role}_role`] = `${label}上传配置不合法，请刷新表单后重试。`
    messages[`invalid_expert_image_${role}_dimensions`] = `${label}尺寸无效，请检查图片后重新导出。`
    messages[`invalid_expert_image_${role}_png_header`] = `${label}的 PNG 文件已损坏，请重新导出。`
    messages[`invalid_expert_image_${role}_jpeg_header`] = `${label}的 JPEG 文件已损坏，请重新导出。`
    messages[`invalid_expert_image_${role}_webp_size`] = `${label}的 WebP 文件大小信息无效，请重新导出。`
    messages[`invalid_expert_image_${role}_webp_header`] = `${label}的 WebP 文件已损坏，请重新导出。`
    const dimensionReason = role === 'icon' ? 'icon_dimensions'
      : role === 'illustration' ? 'illustration_dimensions' : 'example_dimensions'
    messages[`invalid_expert_image_${role}_${dimensionReason}`] = `${label}尺寸要求：${dimensions}。`
  }
  return messages
}

/** 聚合保存接口的稳定错误码。服务端原始 message 始终不进入界面。 */
const EXPERT_AGGREGATE_ERROR_MESSAGES: Record<string, string> = {
  expert_aggregate_required: '专家提交内容为空，请刷新表单后重试。',
  expert_aggregate_json_required: '专家提交内容为空，请刷新表单后重试。',
  expert_aggregate_json_invalid: '专家提交内容格式不正确，请刷新表单后重试。',
  expert_aggregate_concurrent_modification: '专家内容已被他人修改，请重新打开后再保存。',
  expected_row_version_required: '缺少专家内容版本号，请重新打开后再保存。',
  expected_row_version_invalid: '专家内容版本号无效，请重新打开后再保存。',
  skill_code_required: '请填写专家编码。',
  invalid_skill_code: '专家编码只能由小写字母、数字及单词间的连字符组成，最长 64 位。',
  skill_code_immutable: '专家编码创建后不能修改，请重新打开表单。',
  skill_name_required: '请填写专家名称。',
  skill_name_invalid: '专家名称不能超过 128 个字符。',
  skill_name_en_invalid: '英文名称不能超过 128 个字符。',
  skill_category_invalid: '分类名称不能超过 32 个字符。',
  skill_tags_invalid: '运营标签最多 16 个，单个标签最多 64 个字符，且不能重复或留空。',
  skill_summary_required: '请填写中文卡片摘要。',
  skill_summary_invalid: '中文卡片摘要不能超过 512 个字符。',
  skill_summary_en_invalid: '英文卡片摘要不能超过 512 个字符。',
  skill_description_required: '请填写中文详细说明。',
  skill_description_invalid: '中文详细说明不能超过 1024 个字符。',
  skill_description_en_invalid: '英文详细说明不能超过 1024 个字符。',
  skill_min_client_version_invalid: '最低客户端版本格式应形如 2026.6.0。',
  skill_sort_weight_invalid: '排序权重不能小于 0。',
  skill_expert_profile_required: '请填写专家展示内容。',
  skill_expert_profile_kind_required: '只有专家类型可以维护专家展示内容。',
  skill_expert_profile_concurrent_modification: '专家展示内容已被他人修改，请重新打开后再保存。',
  skill_expert_profile_row_version_required: '缺少专家展示内容版本号，请重新打开后再保存。',
  skill_expert_profile_task_types_invalid: '业务类型最多 6 个，单项最多 64 个字符，且不能重复或留空。',
  skill_expert_profile_task_types_required: '请至少填写一个中文业务类型。',
  skill_expert_profile_usage_guide_invalid: '使用说明不能超过 1024 个字符。',
  skill_expert_profile_usage_guide_required: '请填写中文使用说明。',
  skill_expert_profile_prompts_invalid: '示例提示词最多 8 条，单条最多 300 个字符，且不能重复或留空。',
  skill_expert_profile_prompts_required: '请至少填写一条中文示例提示词。',
  skill_expert_profile_preview_invalid: '示例预览结构或内容不合法，请检查标题、段落及表格。',
  skill_expert_profile_preview_required: '请填写中文示例预览及输出段落。',
  skill_expert_profile_canvas_invalid: '展示画布结构或布局不合法，请检查画布区块。',
  skill_expert_profile_canvas_too_large: '展示画布内容超过 32 KiB，请精简区块或文案。',
  skill_expert_profile_example_image_locale_invalid: '示例图片语言只能选择中文、英文或通用。',
  expert_images_total_too_large: '头像、介绍插画和示例图片合计不能超过 1.25 MiB，请压缩图片。',
  invalid_expert_image_image_dimensions: '图片尺寸无效，请检查后重新导出。',
  ...expertImageErrorMessages(),
}

/** 设计 §5.1 / §5.2 / §9.5 里管理面会返回的全部稳定错误码 */
export const SKILL_ADMIN_ERROR_MESSAGES: Record<string, string> = {
  // --- 写接口（§5.1 A1–A13）---
  skill_code_conflict: '该技能标识已被占用，请换一个。',
  skill_version_conflict: '该版本号已存在，或正被其他上传占用，请刷新后重试。',
  skill_cannot_delete_published: '已上架的技能不能删除，请先下架。',
  skill_has_no_ready_version: '该技能还没有可发布（已就绪）的版本，请先上传技能包。',
  skill_concurrent_modification: '内容已被他人修改，请关闭后重新打开。',
  skill_zip_invalid: '技能包结构校验未通过：请确认上传的是 YCPS 包（.ycps.zip），根目录包含 plugin.json，入口技能位于 skills/<code>/SKILL.md。',
  skill_zip_unsafe_entry: '技能包包含不安全的条目（如 ..、绝对路径或符号链接），已拒绝。',
  skill_manifest_mismatch: '技能包内 plugin.json 的 code 与当前技能标识不一致。',
  skill_package_too_large: '技能包超出大小上限：YCPS 压缩包按类型限制（技能 10 MB、专家 2 MB、专家团 5 MB），全局上限 200 MB。',
  skill_package_mismatch: '技能包上传后校验失败，请重新上传。',
  skill_package_unavailable: '技能包文件不可用，请重新上传后再试。',
  skill_package_rejected: '技能包未通过内容安全扫描，已拒绝入库。',
  skill_scan_unavailable: '内容安全扫描服务暂时不可用，请稍后重试。',
  // 早期网关错误码别名，保留以支持滚动升级。
  skill_scan_rejected: '技能包未通过安全扫描，已拒绝。',
  skill_scan_failed: '安全扫描服务暂时不可用，请稍后重试。',
  skill_version_published: '线上版本不能直接删除，请先发布另一个版本或下架技能。',
  /** 后端实际使用的错误码（SkillAdminExceptionHandler） */
  skill_version_online: '线上版本不能直接删除，请先发布其它版本或下架技能。',
  skill_version_has_active_downloads: '该版本仍有未过期的下载会话，请稍后再删除。',
  skill_version_not_found: '该版本不存在或已被删除，请刷新后重试。',
  native_clawhub_version_already_exists: '这个 ClawHub 版本或来源包已存在，请检查版本号后再上传。',
  native_clawhub_version_invalid: 'ClawHub 版本号必须是严格 SemVer，例如 1.2.3 或 1.2.3-rc.1。',
  native_clawhub_version_required: '该 ClawHub 包没有有效版本号，请补充严格 SemVer 后重试。',
  native_clawhub_version_id_required: '该 ClawHub 技能有多个版本且尚未选择线上版本，请在详情中明确选择一个版本。',
  native_clawhub_identity_mismatch: 'ClawHub 包的发布者或技能标识与当前技能不一致，不能作为新版本上传。',
  native_clawhub_scan_not_passed: '该 ClawHub 版本尚未通过安全扫描，不能设为线上。',
  native_clawhub_source_rejected: '该 ClawHub 包未通过安全扫描，不能导出或上架。',
  native_clawhub_published_version_delete_forbidden: '已上架过的 ClawHub 版本不能删除。',
  native_clawhub_current_version_delete_forbidden: '当前或上次发布的 ClawHub 版本不能删除。',
  native_clawhub_version_delete_forbidden: '仅从未上架且不是发布指向的 ClawHub 草稿可以删除。',
  native_clawhub_source_not_found: 'ClawHub 版本不存在或已被删除，请刷新后重试。',
  native_clawhub_release_conflict: '上次线上 ClawHub 版本记录不完整，请检查来源关联后重试。',
  clawhub_archive_blocked: 'ClawHub 包未通过预检，已拒绝上传。',
  clawhub_archive_unsafe: 'ClawHub ZIP 包含不安全的压缩条目，已拒绝上传。',
  clawhub_source_changed: '预检后所选 ClawHub 文件发生变化，请重新选择并预检。',
  clawhub_import_metadata_invalid: '导入信息格式无效，请检查名称、介绍和来源信息后重试。',
  remote_skill_url_invalid: '请输入公开的 ClawHub 技能页或 GitHub 技能目录 HTTPS 链接。',
  remote_skill_not_found: '远程技能、仓库或指定版本不存在，或当前链接不是公开资源。',
  remote_skill_rate_limited: '远程站点请求过于频繁，请稍后重新拉取。',
  remote_skill_fetch_failed: '远程技能拉取失败或超时，请检查链接后重试。',
  remote_skill_timeout: '远程技能下载超时，请稍后重新拉取。',
  remote_skill_upstream_failed: '远程站点暂时无法提供技能包，请稍后重试。',
  remote_skill_blocked: '远程站点暂不允许下载此技能，请检查其公开状态或稍后重试。',
  remote_skill_json_invalid: '远程站点返回的信息格式不正确，请检查链接后重试。',
  remote_skill_archive_too_large: '远程技能包超过 50 MB 导入上限。',
  remote_skill_response_too_large: '远程下载内容超过大小上限，请使用更小的技能目录。',
  remote_skill_archive_unsafe: '远程技能包包含无效路径、链接或压缩条目，无法导入。',
  remote_skill_document_ambiguous: '链接中没有唯一的技能文档，请粘贴含 SKILL.md 的具体技能目录链接。',
  remote_skill_redirect_limit: '远程下载跳转次数过多，请使用技能页的原始链接。',
  remote_skill_redirect_missing: '远程站点未返回有效下载地址，请稍后重试。',
  remote_skill_redirect_unsafe: '远程下载跳转到了不支持的地址，请使用 ClawHub 或 GitHub 原始链接。',
  clawhub_owner_mismatch: 'ClawHub 返回的发布者或技能标识与链接不一致，请检查链接。',
  clawhub_install_unavailable: '此 ClawHub 技能当前不可下载，请检查其上架和扫描状态。',
  clawhub_install_response_invalid: 'ClawHub 返回的安装信息无法识别，请稍后重试。',
  clawhub_version_invalid: 'ClawHub 链接或安装信息中的版本号无效。',
  clawhub_version_manifest_invalid: 'ClawHub 未返回有效的版本文件清单，无法确认原包完整性。',
  clawhub_version_manifest_mismatch: '下载文件与 ClawHub 版本清单不一致，请重新拉取。',
  clawhub_download_url_invalid: 'ClawHub 下载地址与所选技能或版本不一致。',
  clawhub_download_not_zip: 'ClawHub 未返回有效的原始 ZIP 包。',
  clawhub_handoff_invalid: 'ClawHub 返回的 GitHub 来源信息不完整，无法导入。',
  clawhub_github_content_hash_mismatch: 'GitHub 技能内容与 ClawHub 公布的指纹不一致，请重新拉取。',
  github_repository_invalid: 'GitHub 仓库信息无效，请检查仓库是否公开可用。',
  github_reference_invalid: 'GitHub 分支、标签或提交无效，请检查链接。',
  github_commit_invalid: '无法确定 GitHub 技能的固定提交，请重新拉取。',
  github_tree_path_invalid: 'GitHub 分支或技能目录不存在，请粘贴具体技能目录链接。',
  github_repository_archive_too_many_entries: 'GitHub 仓库文件过多，请使用具体技能目录链接。',
  github_tree_response_invalid: 'GitHub 未返回完整目录信息，请稍后重新拉取。',
  github_tree_entry_unsafe: 'GitHub 技能目录含链接、子模块或不支持的文件，无法完整导入。',
  github_tree_entry_limit: 'GitHub 技能目录文件数量超过导入上限，请缩小目录范围。',
  github_tree_blob_mismatch: 'GitHub 文件内容与指定提交不一致，请重新拉取。',
  github_document_link_invalid: '请使用 GitHub 技能目录或 SKILL.md 文档链接。',
  github_skill_path_empty: 'GitHub 指定目录中没有可导入的技能文件。',
  github_archive_empty: 'GitHub 返回了空的资源包。',
  github_archive_not_zip: 'GitHub 未返回有效的 ZIP 资源包。',
  github_archive_root_invalid: 'GitHub 资源包目录结构无效，无法定位技能。',
  github_archive_expanded_too_large: 'GitHub 技能目录展开后超过导入上限，请缩小目录范围。',
  skill_icon_invalid: '图标文件不合法，请上传 4 MB 以内的 PNG、JPG、WebP 或 SVG。',
  skill_icon_too_large: '图标文件不能超过 4 MB。',
  skill_storage_unavailable: '技能存储暂时不可用，请稍后重试。',
  skill_unpublished: '该技能已下架，请先重新上架再操作。',
  skill_not_found: '技能不存在或已被删除。',
  invalid_skill_input: '技能信息不合法，请检查填写内容。',
  invalid_skill_name: '技能标识不符合命名规则：只能由小写字母和数字组成，单词之间用连字符（-）分隔，例如 finance-analyst。',
  invalid_skill_metadata: '技能信息不合法，请检查填写内容。',
  invalid_skill_status: '状态筛选值不合法。',
  invalid_sort: '排序字段不合法。',
  invalid_sort_direction: '排序方向只能是 asc 或 desc。',
  invalid_pagination: '分页参数不合法：页码从 0 开始，每页最多 100 条。',
  gateway_skill_operation_failed: 'Skill 管理操作失败，请稍后重试。',
  skill_file_too_large: '技能包内单个文件超出大小限制。',
  skill_too_large: '技能包解压后超出大小限制。',
  skill_too_many_files: '技能包内文件数量超出限制。',
  idempotency_key_reused: '该操作已提交过，请刷新列表确认结果。',
  // --- 专家化改造新增（设计 2026-09-14 补充 §3.1 / §5）---
  invalid_skill_kind: '技能类型不合法：只能在创建时选择技能、专家或专家团，创建后不可修改。',
  invalid_expert_manifest: '技能包内的 plugin.json 无法解析或结构不合法，已拒绝该包。',
  expert_kind_mismatch: '技能包内 plugin.json 的 expertType 与技能类型不一致，已拒绝该包。',
  invalid_skill_member: '花名册不合法：成员标识不能重复，且一个技能最多只能有一位主理人（LEAD）。',
  skill_member_not_found: '该成员不存在或已被移除，请刷新后重试。',
  skill_illustration_not_found: '该资源包没有介绍插画。',
  skill_illustration_too_large: '介绍插画不能超过 4 MB，请压缩后重试。',
  skill_example_image_not_found: '该资源包没有示例图片。',
  skill_example_image_too_large: '示例图片不能超过 4 MB，请压缩后重试。',
  skill_restore_expired: '该记录已超过 30 天恢复期限，无法恢复。',
  skill_recycle_delete_blocked: '仍有进行中的下载，暂时无法彻底删除。',
  // --- YCPS 1.0 摄入链路（SkillPackageInspector / SkillPackageIngestService 实际冒泡的 code）---
  ycps_package_filename_required: '请上传以 .ycps.zip 结尾的 YCPS 包（文件名需与包内 code 对应）。',
  ycps_package_invalid: '资源包不符合 YCPS 1.0 结构或 Manifest 约束，已拒绝。',
  package_identity_mismatch: 'YCPS 包内的 code、version 或 kind 与上传目标不一致，已拒绝。',
  ycps_package_unsafe_entry: 'YCPS 包包含不安全路径、链接、重名或异常压缩条目，已拒绝。',
  ycps_package_too_large: 'YCPS 包超过对应类型的大小上限（技能 10 MB、专家 2 MB、专家团 5 MB）。',
  ycps_dependency_unresolved: '该包引用了未发布或摘要不匹配的精确依赖，无法入库。',
  // --- YCPS 1.1 Expert 可移植导入 ---
  skill_portable_import_profile_required: '仅支持 YCPS 1.1 Expert 包，请重新导出后再试。',
  skill_portable_import_expired: '导入确认已过期，请重新上传专家包。',
  skill_portable_import_sha256_changed: '暂存专家包的摘要已变化，请重新上传。',
  skill_portable_import_code_kind_conflict: '包标识或类型与当前专家不一致。',
  skill_portable_import_operational_field: '分类、标签、排序和发布状态由运营端维护，不能从包导入。',
  skill_portable_import_merge_not_supported: '只有任务类型和示例提示词支持合并。',
  skill_portable_import_field_action_invalid: '导入字段的处理方式无效，请重新检查。',
  skill_portable_import_image_action_invalid: '导入图片的处理方式无效，请重新检查。',
  skill_portable_import_not_found: '导入记录不存在或已被清理，请重新上传。',
  skill_portable_import_not_committable: '导入记录当前不可确认，请重新上传。',
  expert_presentation_asset_invalid: '专家展示图片只支持 PNG、JPEG 或 WebP，请重新选择。',
  expert_presentation_asset_too_large: '专家展示图片超过便携包大小上限，请压缩后重试。',
  expert_presentation_catalog_copy_invalid: '专家展示文案超过目录字段长度上限，请精简名称、摘要或说明。',
  expert_presentation_example_image_locale_invalid: '示例图片语言只能选择中文、英文或通用。',
  // --- 不可变版本与依赖闭包（SkillAdminService / SkillVersionRepository）---
  skill_version_sealed: '该版本已发布并封存，不能原地替换；请发布新的 SemVer 版本。',
  skill_version_not_publishable: '该 YCPS 版本无法封存或发布，请重新上传后重试。',
  skill_version_has_dependents: '该精确版本仍被 YCPS 包引用，不能删除。',
  skill_has_active_dependents: '该技能仍被 YCPS 包引用，不能删除。',
  skill_dependency_cycle: '依赖闭包存在循环引用，无法发布。',
  skill_dependency_depth_exceeded: '依赖闭包深度超过 32 层，无法发布。',
  skill_dependency_count_exceeded: '依赖闭包包数量超过 128，无法发布。',
  skill_dependency_lock_conflict: '依赖闭包对同一包锁定了不同版本或摘要，无法发布。',
  skill_icon_not_found: '该技能还没有上传过图标。',
  skill_download_idempotency_mismatch: '重复的下载请求与原请求不一致，已拒绝。',
  authentication_required: '登录状态已失效，请重新登录后再试。',
  // --- 客户端只读接口（§9.5）在管理端预览时的兜底 ---
  client_version_too_low: '客户端版本低于该技能的最低适配版本。',
  rate_limited: '操作过于频繁，请稍后再试。',
  download_session_expired: '下载会话已过期，客户端会重新开始下载。',
  download_not_found: '下载会话不存在或已失效。',
  account_safety_restricted: '账号安全状态异常，暂时无法下载技能。',
  content_safety_unavailable: '内容安全服务暂时不可用，请稍后重试。',
  // --- 分类词表（A23 / A24 / A25 / A26）---
  invalid_skill_category: '分类不合法：名称不能为空且不超过 32 个字符，编码只能是小写字母、数字与连字符。',
  skill_category_name_conflict: '该分类名称已存在，请换一个名称。',
  skill_category_code_conflict: '该分类编码已被占用，请换一个编码。',
  skill_category_not_found: '该分类不存在或已被删除，请刷新后重试。',
  skill_category_in_use: '该分类下仍有包在使用，请先改归类或移除这些包后再删除。',
  skill_category_image_not_found: '该分类还没有上传过封面。',
}

/** 未识别 code 时按 HTTP 状态兜底 */
const SKILL_ADMIN_STATUS_MESSAGES: Record<number, string> = {
  400: '请求不符合 Skill 管理接口要求，请检查输入。',
  401: '登录状态已失效，请重新登录后再试。',
  403: '当前账号没有管理 Skill 的权限。',
  404: '目标技能或版本不存在，请刷新列表后重试。',
  409: '当前状态不允许该操作，请刷新列表后重试。',
  413: '技能包超出大小上限：YCPS 压缩包按类型限制（技能 10 MB、专家 2 MB、专家团 5 MB），全局上限 200 MB。',
  415: '不支持的文件类型，请上传 ZIP 技能包。',
  422: '提交的内容不符合接口要求。',
  429: '操作过于频繁，请稍后再试。',
  500: 'Skill 管理服务暂时不可用，请稍后重试。',
  503: 'Skill 存储暂时不可用，请稍后重试。',
}

export const SKILL_ADMIN_NETWORK_MESSAGE = '无法连接到 Skill 管理服务，请检查网络后重试。'
export const SKILL_ADMIN_UNKNOWN_MESSAGE = 'Skill 管理操作失败，请稍后重试。'

/**
 * 专家页（`/admin/experts`）复用同一套管理组件，同一批错误码也需要换成「专家」措辞。
 *
 * 本字典默认按普通技能写；调用方传入本作用域后，把面向当前对象的「技能 / 技能包」
 * 换成「专家 / 专家包」。**不传时文案逐字不变**，SKILL 页与既有断言不受影响。
 */
export interface SkillAdminCopyScope {
  /** 当前页面的对象名词，例如「专家」。 */
  noun: string
  /** 当前页面的资源包名词，例如「专家包」。 */
  packageNoun: string
}

/** 三类上限的列举句在专家页同样成立（它本就在说明三种类型各自的限制），替换时必须整体跳过。 */
const PACKAGE_LIMIT_LIST = '（技能 10 MB、专家 2 MB、专家团 5 MB）'
/** `invalid_skill_kind` 在枚举 kind 的可选值，三个词都要如实出现，不能把第一个也换掉。 */
const KIND_ENUM = '技能、专家或专家团'
/** YCPS 协议术语（入口技能 entry skill），与当前页面对象无关。 */
const ENTRY_SKILL = '入口技能'
const PROTECTED: Array<[string, string]> = [
  [PACKAGE_LIMIT_LIST, '\u0000ycps-limits\u0000'],
  [KIND_ENUM, '\u0000kind-enum\u0000'],
  [ENTRY_SKILL, '\u0000entry-skill\u0000'],
]

/**
 * 把文案里的对象名词换成当前页面作用域。
 *
 * 顺序很关键：先整体保护枚举句与协议术语，再换「技能包」（长词优先，避免被「技能」
 * 先吃掉），最后换单个「技能」。
 */
function localizeCopy(message: string, scope?: SkillAdminCopyScope): string {
  if (!scope || scope.noun === '技能') return message
  let localized = message
  for (const [phrase, token] of PROTECTED) localized = localized.split(phrase).join(token)
  localized = localized
    .split('技能包').join(scope.packageNoun)
    .split('技能').join(scope.noun)
  for (const [phrase, token] of PROTECTED) localized = localized.split(token).join(phrase)
  return localized
}

export interface SkillAdminFailure {
  /** 后端错误码，未识别时为空字符串 */
  code: string
  /** 可直接展示的中文文案 */
  message: string
}

/**
 * 编码占用的对象类型 → 中文名词。
 *
 * 编码在 YCPS 中全局唯一（普通技能、专家、专家团共用一个命名空间），所以撞名时必须说明
 * 占用者属于哪一类，否则「已被占用，请换一个」无法让管理员判断是自己搞错了页面还是真撞码。
 */
const CODE_HOLDER_NOUN: Record<string, string> = {
  SKILL: '普通技能',
  EXPERT: '专家',
  TEAM: '专家团',
}

/** 只把约定的专家字段路径转成显示名称，不展示服务端传来的路径或任意原文。 */
const EXPERT_FIELD_LABELS: Record<string, string> = {
  data: '提交内容',
  code: '编码',
  name: '中文名称',
  nameEn: '英文名称',
  category: '分类',
  tags: '运营标签',
  summary: '中文卡片摘要',
  summaryEn: '英文卡片摘要',
  description: '中文详细说明',
  descriptionEn: '英文详细说明',
  icon: '徽标文本',
  minClientVersion: '最低客户端版本',
  sortWeight: '排序权重',
  expectedRowVersion: '内容版本号',
  taskTypes: '中文业务类型',
  taskTypesEn: '英文业务类型',
  usageGuide: '中文使用说明',
  usageGuideEn: '英文使用说明',
  examplePrompts: '中文示例提示词',
  examplePromptsEn: '英文示例提示词',
  preview: '中文示例预览',
  previewEn: '英文示例预览',
  canvas: '展示画布',
  exampleImageLocale: '示例图片语言',
  images: '展示图片',
  'images.total': '展示图片合计大小',
  'images.icon': '头像',
  'images.illustration': '介绍插画',
  'images.exampleImage': '示例图片',
}

const PREVIEW_FIELD_LABELS: Record<string, string> = {
  heading: '预览标题',
  headingPrefix: '预览标题前缀',
  scene: '预览场景',
  request: '示例请求',
  material: '示例素材',
  outputTitle: '输出标题',
  outputParagraphs: '输出段落',
  intro: '预览引言',
  table: '预览表格',
  sections: '预览章节',
  blocks: '预览内容区块',
}

function readExpertFieldLabel(error: unknown): string | null {
  const details = (error as { response?: { data?: { details?: { field?: unknown } } } })
    ?.response?.data?.details
  const field = details?.field
  if (typeof field !== 'string') return null
  if (Object.prototype.hasOwnProperty.call(EXPERT_FIELD_LABELS, field)) {
    return EXPERT_FIELD_LABELS[field]
  }
  // 深层路径由后端验证器给出；只解析白名单首段及字段名，不显示原始下标或路径。
  const preview = /^(preview|previewEn)\.([A-Za-z]+)(?:\..*|\[\d+\].*)?$/u.exec(field)
  if (preview && Object.prototype.hasOwnProperty.call(PREVIEW_FIELD_LABELS, preview[2])) {
    const locale = preview[1] === 'previewEn' ? '英文' : '中文'
    return `${locale}${PREVIEW_FIELD_LABELS[preview[2]]}`
  }
  const canvas = /^canvas\.(zh|en)(?:\.nodes\[(\d{1,3})\](?:\.(id|runs(?:\[\d{1,3}\])?|table|style|assetRole))?)?$/u.exec(field)
  if (canvas) {
    const locale = canvas[1] === 'en' ? '英文' : '中文'
    if (canvas[2] === undefined) return `${locale}展示画布`
    const index = Number(canvas[2])
    if (index >= 128) return null
    const node = `${locale}展示画布第 ${index + 1} 个区块`
    const property = canvas[3]
    if (property === 'table') return `${node}的表格`
    if (property?.startsWith('runs')) return `${node}的富文本`
    if (property === 'style') return `${node}的样式`
    if (property === 'assetRole') return `${node}的图片引用`
    if (property === 'id') return `${node}的标识`
    return node
  }
  return null
}

/** 读取 `skill_code_conflict` 响应里的 `details.occupiedBy`（A22 起提供，旧响应没有）。 */
function readCodeHolder(error: unknown): { kind: string; code: string } | null {
  const details = (error as { response?: { data?: { details?: { occupiedBy?: unknown } } } })
    ?.response?.data?.details
  const holder = details?.occupiedBy
  if (!holder || typeof holder !== 'object') return null
  const record = holder as { kind?: unknown; code?: unknown }
  const kind = typeof record.kind === 'string' ? record.kind.trim().toUpperCase() : ''
  const code = typeof record.code === 'string' ? record.code.trim() : ''
  if (!kind || !code) return null
  return { kind, code }
}

/**
 * 读取 `skill_category_in_use` 响应里的 `details`（A24）。
 *
 * 删除分类被拒时，只说「仍有包在使用」管理员无法判断该改哪一个，所以后端会带上分类名、
 * 占用数量与前几个包标识，这里把它们渲染成一句可操作的提示。
 */
function readCategoryUsage(
  error: unknown,
): { name: string; usageCount: number; packages: string[] } | null {
  const details = (error as {
    response?: { data?: { details?: { name?: unknown; usageCount?: unknown; packages?: unknown } } }
  })?.response?.data?.details
  if (!details) return null
  const name = typeof details.name === 'string' ? details.name.trim() : ''
  const usageCount = typeof details.usageCount === 'number' ? details.usageCount : 0
  const packages = Array.isArray(details.packages)
    ? details.packages.filter((entry): entry is string => typeof entry === 'string')
    : []
  if (!name || usageCount <= 0) return null
  return { name, usageCount, packages }
}

/** 取出错误码对应的中文文案；未识别时按状态兜底，绝不回显服务端原文 */
export function skillAdminFailure(error: unknown, scope?: SkillAdminCopyScope): SkillAdminFailure {
  const code = readApiErrorCode(error) ?? ''
  const response = (error as { response?: { status?: number } })?.response
  const status = response?.status
  if (!response) return { code, message: localizeCopy(SKILL_ADMIN_NETWORK_MESSAGE, scope) }
  // A naming clash is the one conflict the operator can act on, and only if the message says
  // what already holds the code. The generic mapping stays as the fallback for older backends.
  if (code === 'skill_code_conflict') {
    const holder = readCodeHolder(error)
    if (holder) {
      const kindNoun = CODE_HOLDER_NOUN[holder.kind] ?? holder.kind
      return {
        code,
        message: `编码「${holder.code}」已被一个${kindNoun}占用。编码在普通技能、专家与专家团之间全局唯一且不可复用，请换一个编码。`,
      }
    }
  }
  // A category still carried by packages cannot be deleted; name the packages so the operator
  // knows exactly what to re-categorise instead of being told "in use" and left guessing.
  if (code === 'skill_category_in_use') {
    const usage = readCategoryUsage(error)
    if (usage) {
      const sample = usage.packages.length ? `：${usage.packages.join('、')}` : ''
      const suffix = usage.usageCount > usage.packages.length ? ' 等' : ''
      return {
        code,
        message: `分类「${usage.name}」下还有 ${usage.usageCount} 个包${sample}${suffix}，请先改归类或移除这些包后再删除该分类。`,
      }
    }
  }
  const knownCode = code && Object.prototype.hasOwnProperty.call(SKILL_ADMIN_ERROR_MESSAGES, code)
  const expertScope = scope?.noun === '专家'
  const expertMapped = expertScope && code
    && Object.prototype.hasOwnProperty.call(EXPERT_AGGREGATE_ERROR_MESSAGES, code)
    ? EXPERT_AGGREGATE_ERROR_MESSAGES[code] : ''
  const mapped = expertScope && code === 'skill_icon_invalid'
    ? '徽标文本不能超过 8 个字符。'
    : expertMapped || (knownCode ? SKILL_ADMIN_ERROR_MESSAGES[code] : '')
  let message = localizeCopy(
    mapped
      || (typeof status === 'number' ? SKILL_ADMIN_STATUS_MESSAGES[status] : '')
      || SKILL_ADMIN_UNKNOWN_MESSAGE,
    scope,
  )
  if (expertScope && code && (
    Object.prototype.hasOwnProperty.call(EXPERT_AGGREGATE_ERROR_MESSAGES, code)
    || code === 'skill_icon_invalid'
  )) {
    const fieldLabel = readExpertFieldLabel(error)
    if (fieldLabel) message = `问题位置：${fieldLabel}。${message}`
  }
  return { code, message }
}

export function skillAdminErrorMessage(error: unknown, scope?: SkillAdminCopyScope): string {
  return skillAdminFailure(error, scope).message
}
