<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import SkillAvatar from '../../components/admin/SkillAvatar.vue'
import SkillCategoryManager from '../../components/admin/SkillCategoryManager.vue'
import ExpertWysiwygEditor from '../../components/admin/ExpertWysiwygEditor.vue'
import SkillPortableImportPanel from '../../components/admin/SkillPortableImportPanel.vue'
import {
  useSkillIconPreviews,
  useSkillIllustrationPreviews,
  useSkillExampleImagePreviews,
  useSkillImagePreviews,
} from '../../composables/useSkillIconPreviews'
import {
  BadgeCheck,
  Blocks,
  CircleAlert,
  Eye,
  FileText,
  GitBranch,
  ImagePlus,
  Inbox,
  KeyRound,
  Lock,
  Package,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  ShieldCheck,
  Trash2,
  Upload,
  Download,
} from 'lucide-vue-next'

import {
  Users,
} from 'lucide-vue-next'
import { FolderOpen, PackageOpen } from 'lucide-vue-next'
import { ChevronDown } from 'lucide-vue-next'
import { Undo2 } from 'lucide-vue-next'
import { confirmImageChanges } from '../../utils/imageChangeConfirm'

import {
  createAdminSkill,
  createAdminExpertAggregate,
  commitAdminClawhubSkill,
  createSkillCategory,
  deleteAdminClawhubVersion,
  deleteSkillCategory,
  deleteAdminSkill,
  deleteAdminSkillIcon,
  deleteAdminSkillIllustration,
  deleteAdminSkillExampleImage,
  deleteSkillVersion,
  downloadAdminClawhubSkillPackage,
  downloadAdminSkillPackage,
  downloadAdminSkillPackageBundle,
  downloadAdminClawhubVersion,
  fetchAdminSkillIcon,
  fetchAdminSkillIllustration,
  fetchAdminSkillExampleImage,
  fetchAdminExpertPresentation,
  fetchAdminRemoteClawhubSkill,
  fetchSkillCategoryImage,
  fetchSkillCodeAvailability,
  fetchSkillDependents,
  fetchSkillRelations,
  fetchSkillVersionDependencies,
  generateAdminSkillPortableRevision,
  getAdminSkill,
  inspectAdminSkillPackage,
  listAdminClawhubVersions,
  previewAdminClawhubSkill,
  listAdminSkills,
  listDeletedAdminSkills,
  purgeAdminSkill,
  publishAdminSkill,
  publishAdminClawhubSkill,
  publishAdminClawhubVersion,
  rescanAdminClawhubVersion,
  publishSkillVersion,
  replaceAdminSkillMembers,
  replaceSkillPackageWithRetry,
  restoreAdminSkill,
  skillDetailPlaceholder,
  unpublishAdminSkill,
  updateAdminSkill,
  updateAdminClawhubSourceVersion,
  updateAdminExpertAggregate,
  updateSkillCategory,
  uploadAdminSkillIcon,
  uploadAdminSkillIllustration,
  uploadAdminSkillExampleImage,
  uploadAdminClawhubVersion,
  uploadSkillCategoryImage,
  uploadSkillVersionWithRetry,
  type SkillAdminDetail,
  type SkillAdminListItem,
  type SkillAdminListParams,
  type SkillAdminPayload,
  type ExpertPresentationPayload,
  type ExpertAggregatePayload,
  type ExpertAggregateResponse,
  type ExpertImageAction,
  type AdminExpertPresentationResponse,
  type ClawhubConversionReport,
  type ClawhubSkillImportPayload,
  type ClawhubSkillImportMetadata,
  type ClawhubRemoteMetadata,
  type NativeClawhubVersionView,
  type NativeClawhubVersionList,
  type SkillAdminStatus,
  type SkillCodeAvailabilityView,
  type SkillCodeHolderView,
  type SkillDependencyView,
  type SkillDependentEntry,
  type SkillKind,
  type SkillKindFilter,
  type SkillMemberPayload,
  type SkillMemberRole,
  type SkillMemberView,
  type SkillPackageInspectView,
  type SkillRelationsView,
  type SkillVersionStatus,
  type SkillVersionUploadProgress,
  type SkillVersionView,
  type DeletedSkillView,
} from '../../api/admin'
import {
  fetchSkillIcon,
  fetchSkillMemberAvatar,
  getSkill,
  listNativeClawhubSkills,
  listSkills,
  type NativeClawhubSkillSummary,
  type SkillDetail,
  type SkillFileContent,
  type SkillMemberSummary,
  type SkillSummary,
} from '../../api/skills'
import { formatDateTime } from '../../utils/dateTime'
import {
  compareExpertCategoryNames,
  expertCategoryBadge,
  expertCategoryDisplayName,
  expertCategoryKey,
  expertCategoryLabel,
  expertCategoryScope,
  invalidateExpertCategories,
  loadExpertCategories,
  normalizeExpertCategoryInput,
  type ExpertCategoryDefinition,
} from './expertCategories'
import { skillAdminErrorMessage } from './skillAdminErrors'
import { skillErrorMessage, type SkillFailure } from './skillErrors'
import {
  EXPERT_WYSIWYG_LIMITS,
  createExpertWysiwygDraft,
  expertDraftFromAdmin,
  expertDraftToAdminFields,
  validateExpertWysiwygDraft,
  type ExpertImageRole,
  type ExpertWysiwygDraft,
} from '../../utils/expertWysiwygDraft'
import {
  EXPERT_IMAGE_NORMALIZATION_LIMITS,
  normalizeExpertImage,
} from '../../utils/expertImageNormalization'
import {
  clawhubPackageIconFileName,
  downloadSkillImageBlob,
  downloadSkillPackageBlob,
  packageBundleFileName,
  packageFileName,
  skillImageFileName,
  type SkillImageRole,
} from '../../utils/skillDownload'
import {
  buildZip,
  entriesFromDataTransfer,
  readEntryBytes,
  relativeEntriesFromFileList,
  stripContainerDirectory,
} from '../../utils/ycpsZipWriter'
import {
  preparePackageArchive,
  preparePackageDirectory,
  probeNativeSkillArchive,
  type PreparedPackageImport,
} from '../../utils/skillPackageBundleImport'
import {
  fetchClawhubDisplayName,
  inferClawhubIdentity,
  probeNativeSkillDirectory,
} from '../../utils/clawhubSourceImport'
import {
  skillSourceFromDrop,
  skillSourceFromFileSelection,
  type PreparedSkillSourceInput,
} from '../../utils/skillSourceInput'

/** 预览正文的截断上限，避免超大文件把 DOM 拖垮 */
const PREVIEW_LIMIT = 20_000
/** 卡片上一屏展示的卖点条数 */
const HIGHLIGHT_LIMIT = 3
/** 全局上传兜底（gateway.skills.max-package-bytes，设计 R-08），仅作前端前置校验 */
const MAX_PACKAGE_BYTES = 200 * 1024 * 1024
/** ClawHub native source upload cap; keep aligned with the gateway's 50 MiB limit. */
const MAX_CLAWHUB_SOURCE_BYTES = 50 * 1024 * 1024
const MAX_SKILL_TAG_LENGTH = 64
const MAX_SKILL_DESCRIPTION_LENGTH = 1000
const MAX_OTHER_DESCRIPTION_LENGTH = 4000
const STRICT_SEMVER_PATTERN = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/u
/** YCPS 1.0 按 kind 的压缩包上限（SkillPackageInspector.enforceProfileLimits） */
const YCPS_PACKAGE_LIMITS: Record<SkillKind, number> = {
  SKILL: 10 * 1024 * 1024,
  EXPERT: 2 * 1024 * 1024,
  TEAM: 5 * 1024 * 1024,
}
/** 审核面里需要额外提示的能力（可执行代码或改写工作区） */
const HIGH_RISK_CAPABILITIES = new Set(['process.execute', 'browser.automate', 'skill.manage'])
/** 图片图标上限（与后端 gateway.skills.icon-max-bytes 默认值 4 MB 对齐） */
const MAX_ICON_BYTES = 4 * 1024 * 1024
const ICON_MIME_TYPES = ['image/png', 'image/jpeg', 'image/svg+xml', 'image/webp']
/** 上传成功后按 1s 轮询版本状态（设计 §8.3） */
const VERSION_POLL_INTERVAL_MS = 1_000
const VERSION_POLL_LIMIT = 60

/** 技能标识与 YCPS schema 一致：小写字母/数字，单词间用连字符（SkillAdminService.CODE） */
const SKILL_CODE_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/
/** 成员标识：字母、数字、点、下划线、连字符，不能以符号开头（V39 ck_skill_member_key） */
const MEMBER_KEY_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/
const MIN_CLIENT_VERSION_PATTERN = /^\d{4}\.\d{1,2}(\.\d{1,2})?$/
/** 介绍插画与技能图标同一档限制（与后端 gateway.skills.icon-max-bytes 对齐） */
const MAX_ILLUSTRATION_BYTES = 4 * 1024 * 1024
const ILLUSTRATION_MIME_TYPES = ['image/png', 'image/jpeg', 'image/svg+xml', 'image/webp']
const MAX_EXAMPLE_IMAGE_BYTES = 4 * 1024 * 1024
const EXAMPLE_IMAGE_MIME_TYPES = ILLUSTRATION_MIME_TYPES
/** Expert aggregate asset contract (the generic Skill/Team endpoints retain their old limits). */
/** 依赖关系与目标版本状态的中文标签（审核面 A19/A20） */
const DEPENDENCY_RELATION_LABELS: Record<string, string> = {
  SKILL_BINDING: '技能绑定',
  TEAM_MEMBER: '专家团成员',
}
const DEPENDENCY_TARGET_LABELS: Record<string, string> = {
  DELETED: '已删除',
}
/** 成员字段长度与库表列宽一致（设计 §2.2） */
const MEMBER_FIELD_MAX = 64
const KIND_VALUES: SkillKind[] = ['SKILL', 'EXPERT', 'TEAM']

const activeTab = ref<'list' | 'preview'>('list')

/**
 * 同一套 Skill 管理实现服务三个独立页面：
 * - `/admin/skills`（默认）：只管理普通技能 `SKILL`；
 * - `/admin/experts`：只管理专家 `EXPERT`（`ExpertCatalogView` 传入 `kinds="EXPERT"`）；
 * - `/admin/teams`：只管理专家团 `TEAM`（`TeamCatalogView` 传入 `kinds="TEAM"`）。
 * 列表固定按 `kinds` 过滤，因此三个页面数据互不重叠；类型标签仍然展示。
 */
const props = withDefaults(defineProps<{ kinds?: string }>(), { kinds: 'SKILL' })

/**
 * 作用域文案：同一套实现服务「技能页」与「专家页」，所有用户可见的名词都按 scope 派生，
 * 使专家页统一说「专家 / 专家包 / 专家标识」，而 Skill 页逐字保持原措辞（既有 ~70 条测试依赖）。
 * 下方 SKILL 分支的每个值都必须与改动前完全一致，请勿顺手「优化」。
 */
const KIND_SCOPE = {
  SKILL: {
    noun: 'Skill',
    title: 'Skill 管理',
    intro: '新增、上架、版本与资源包管理；桌面客户端优先使用登录 JWT 拉取已上架技能，外部非交互客户端兼容 API Key。',
    createLabel: '新增 Skill',
    empty: '暂无 Skill，点击右上角「新增 Skill」创建第一个。',
    cnNoun: '技能',
    cnPackage: '技能包',
    cnCode: '技能标识',
    cnName: '技能名称',
    cnSummary: '技能简介',
    typeFieldLabel: '技能类型',
    codeLabel: '技能标识',
    nameLabel: '技能名称（中文，必填）',
    nameLabelEn: '技能名称（英文，可选）',
    summaryLabel: '技能简介（中文，必填）',
    summaryLabelEn: '技能简介（英文，可选）',
    namePlaceholder: '一句话说明这个技能能做什么',
    codeRule: '技能标识只能由小写字母和数字组成，单词之间用连字符（-）分隔，例如 data-analyst；最长 64 位。',
    nameRequired: '请填写技能名称。',
    nameMax: '技能名称最多 128 个字符。',
    summaryRequired: '请填写技能简介。',
    summaryMax: '技能简介最多 512 个字符。',
    created: '技能已创建，可继续上传首个技能包',
    updated: '技能信息已更新',
    published: '技能已上架，桌面客户端下一次请求即可见',
    unpublished: '技能已下架',
    deleted: '技能已移入回收站，可在 30 天内恢复',
    onlineVersionDeleteErr: '线上版本不能直接删除，请先发布另一个版本或下架技能。',
    unpublishTitle: '确认下架 Skill',
    unpublishConfirm: '下架后桌面客户端将立即无法搜索与下载该技能，已安装用户不受影响。',
    deleteTitle: '确认删除 Skill',
    deleteConfirm: '删除后将移入回收站并立即从桌面端隐藏，30 天内可恢复。',
    codeMismatch: '输入的技能标识不一致',
    leadLimit: '一个技能最多只能有一位主理人（LEAD）。',
    noReadyVersionHint: '该技能还没有线上版本，先上传一个技能包。',
    versionInherit: '缺省继承技能级',
    manifestHelp: '它由上传技能包时的 plugin.json 原文写入。',
    manifestEmpty: '该技能还没有包内清单（上传的包里没有 plugin.json，或技能尚未上传过技能包）。',
    versionTableEmpty: '还没有上传过技能包。',
    packageHelp: '技能包由桌面软件端优先携带登录 JWT 拉取，外部非交互客户端兼容 API Key。',
    downloadPackage: '下载YCPS包',
    downloadPackageTitle: '下载当前线上版本的 YCPS 技能包',
    downloadBundle: '含图完整可导入包（含图标）',
    downloadVersion: '下载',
    downloadVersionTitle: '下载该版本的完整技能包',
    noDownloadableVersion: '该技能还没有可下载的线上版本，请先上传并发布技能包。',
    packageListDesc: '已上架技能列表',
    currentCodeHelp: '当前技能标识：',
    readingPackage: '正在读取技能包内容…',
    noPublishedFiles: '该技能还没有对外发布的文件。',
    previewEmpty: '暂无已上架技能，客户端当前看不到任何技能。',
    previewNoMatch: '没有匹配的技能，换个关键词试试。',
    reverseDepTitle: '个版本引用了本技能',
    reverseDepEmpty: '没有其他包引用本技能；下架或删除不会影响其他技能。',
    searchAria: '搜索技能',
    categoryAria: '技能分类',
    previewSearchAria: '搜索客户端可见技能',
    versionPlaceholder: '请填写技能包版本号，例如 1.3.0。',
    scanRejected: '技能包未通过安全扫描，已拒绝。',
    packageFailed: '技能包处理失败，请重新上传。',
    scanning: '技能包仍在安全扫描中，请稍后刷新版本列表查看结果。',
    kindHelpCreate: '技能包提供可复用能力。',
    kindHelpEdit: '类型创建后不可修改；如需变更请新建技能。强行提交会被服务端以 422 invalid_skill_kind 拒绝。',
    editTitle: '编辑 Skill',
    importTitle: '从资源包导入',
    importHint: 'YCPS 包和原生 ClawHub 包有独立入口，请选择对应格式的 ZIP 或目录。拖入文件时会按包结构识别格式；预检后须确认才创建或上传。',
    importPickZip: '导入 YCPS / 完整包 ZIP',
    importPickFolder: '导入 YCPS 包目录',
    importPickClawhub: '导入 ClawHub 原生 ZIP',
    importPickClawhubFolder: '导入 ClawHub 原生目录',
    importClawhubIdentity: '请输入 @发布者/技能标识；若 ZIP 没有版本信息，请追加 @版本（如 @publisher/demo@1.2.3）',
    importClawhubIdentityInvalid: '格式应为 @发布者/技能标识 或 @发布者/技能标识@版本',
    importClawhubBlocked: 'ClawHub 包结构/安全预检未通过：',
    importing: '正在解析资源包…',
    importDone: '已按资源包填入表单，请核对后提交',
    importFolderEmpty: '所选目录里没有文件，请确认选中的是包含 plugin.json 的包目录。',
    importDropEmpty: '拖入的内容里没有可用文件，请拖一个 .ycps.zip 或包含 plugin.json 的包目录。',
    importButton: '导入',
    importDropActive: '松开即可导入',
    importSafetyNotice: '添加第三方能力包前请确认其安全性。',
    importCodeTaken: '资源包标识已被占用，请改用「选择 .ycps.zip」新建其他标识，或先处理占用记录。',
    importPendingPackage: '将随创建一并上传',
  },
  EXPERTS: {
    noun: '专家',
    title: '专家管理',
    intro: '单人专家的创建、展示信息与资源包管理；桌面客户端优先使用登录 JWT 拉取已上架专家，外部非交互客户端兼容 API Key。',
    createLabel: '新增专家',
    empty: '暂无专家，点击右上角「新增专家」创建第一个。',
    cnNoun: '专家',
    cnPackage: '专家包',
    cnCode: '专家标识',
    cnName: '专家名称',
    cnSummary: '专家简介',
    typeFieldLabel: '类型',
    codeLabel: '专家标识',
    nameLabel: '专家名称（中文，必填）',
    nameLabelEn: '专家名称（英文，可选）',
    summaryLabel: '专家简介（中文，必填）',
    summaryLabelEn: '专家简介（英文，可选）',
    namePlaceholder: '一句话说明这个专家能做什么',
    codeRule: '专家标识只能由小写字母和数字组成，单词之间用连字符（-）分隔，例如 data-analyst；最长 64 位。',
    nameRequired: '请填写专家名称。',
    nameMax: '专家名称最多 128 个字符。',
    summaryRequired: '请填写专家简介。',
    summaryMax: '专家简介最多 512 个字符。',
    created: '专家已创建，可从上方导入首个 YCPS 包',
    updated: '专家信息已更新',
    published: '专家已上架，桌面客户端下一次请求即可见',
    unpublished: '专家已下架',
    deleted: '专家已移入回收站，可在 30 天内恢复',
    onlineVersionDeleteErr: '线上版本不能直接删除，请先发布另一个版本或下架专家。',
    unpublishTitle: '确认下架专家',
    unpublishConfirm: '下架后桌面客户端将立即无法搜索与下载该专家，已安装用户不受影响。',
    deleteTitle: '确认删除专家',
    deleteConfirm: '删除后将移入回收站并立即从桌面端隐藏，30 天内可恢复。',
    codeMismatch: '输入的专家标识不一致',
    leadLimit: '一个专家最多只能有一位主理人（LEAD）。',
    noReadyVersionHint: '该专家还没有线上版本，请从上方导入 YCPS 包，再到版本管理设为线上。',
    versionInherit: '缺省继承专家级',
    manifestHelp: '它由上传专家包时的 plugin.json 原文写入。',
    manifestEmpty: '该专家还没有包内清单（上传的包里没有 plugin.json，或专家尚未上传过专家包）。',
    versionTableEmpty: '还没有导入过专家包。',
    packageHelp: '专家包由桌面软件端优先携带登录 JWT 拉取，外部非交互客户端兼容 API Key。',
    downloadPackage: '下载 YCPS 1.1 包（包含图片）',
    downloadPackageTitle: '生成或复用当前展示快照，并下载包含头像、介绍插画和功能示例图的 YCPS 1.1 专家包',
    downloadBundle: '含图完整可导入包（YCPS 1.1）',
    downloadVersion: '下载',
    downloadVersionTitle: '生成或复用该版本的 YCPS 1.1 完整包（包含图片）并下载',
    noDownloadableVersion: '该专家还没有可下载的线上版本，请先导入 YCPS 包并在版本管理中设为线上。',
    packageListDesc: '已上架专家列表',
    currentCodeHelp: '当前专家标识：',
    readingPackage: '正在读取专家包内容…',
    noPublishedFiles: '该专家还没有对外发布的文件。',
    previewEmpty: '暂无已上架专家，客户端当前看不到任何专家。',
    previewNoMatch: '没有匹配的专家，换个关键词试试。',
    reverseDepTitle: '个版本引用了本专家',
    reverseDepEmpty: '没有其他包引用本专家；下架或删除不会影响其他专家。',
    searchAria: '搜索专家',
    categoryAria: '专家分类',
    previewSearchAria: '搜索客户端可见专家',
    versionPlaceholder: '请填写专家包版本号，例如 1.3.0。',
    scanRejected: '专家包未通过安全扫描，已拒绝。',
    packageFailed: '专家包处理失败，请重新上传。',
    scanning: '专家包仍在安全扫描中，请稍后刷新版本列表查看结果。',
    kindHelpCreate: '单人专家由专家包自身定义。',
    kindHelpEdit: '类型创建后不可修改；如需变更请新建专家。强行提交会被服务端以 422 invalid_skill_kind 拒绝。',
    editTitle: '编辑专家',
    importTitle: '从资源包导入',
    importHint: '支持拖拽或选择 YCPS 包、YCPS 1.1 Expert 完整可导入包（.ycps.zip）、兼容包（已废弃）的含图完整包（.bundle.zip），以及它们解压后的文件夹。系统会自动填写包信息，并把包内展示内容和图片带入导入确认。',
    importPickZip: '导入 YCPS / 完整包',
    importPickFolder: '导入包文件夹',
    importPickClawhub: '导入 ClawHub 技能 ZIP',
    importPickClawhubFolder: '导入 ClawHub 原生目录',
    importClawhubIdentity: '请输入 @发布者/技能标识；若 ZIP 没有版本信息，请追加 @版本（如 @publisher/demo@1.2.3）',
    importClawhubIdentityInvalid: '格式应为 @发布者/技能标识 或 @发布者/技能标识@版本',
    importClawhubBlocked: 'ClawHub 包结构/安全预检未通过：',
    importing: '正在解析资源包…',
    importDone: '已按资源包填入表单，请核对后提交',
    importFolderEmpty: '所选目录里没有文件，请确认选中的是包含 plugin.json 的包目录。',
    importDropEmpty: '拖入的内容里没有可用文件，请拖一个 .ycps.zip 或包含 plugin.json 的包目录。',
    importButton: '导入',
    importDropActive: '松开即可导入',
    importSafetyNotice: '添加第三方能力包前请确认其安全性。',
    importCodeTaken: '资源包标识已被占用，请改用「选择 .ycps.zip」新建其他标识，或先处理占用记录。',
    importPendingPackage: '将随创建一并上传',
  },
  TEAM: {
    noun: '专家团',
    title: '专家团管理',
    intro: '专家团（多人协作）的创建、成员花名册与资源包管理；成员只能来自已上架的专家，专家团本身不能再作为成员。',
    createLabel: '新增专家团',
    empty: '暂无专家团，点击右上角「新增专家团」创建第一个。',
    cnNoun: '专家团',
    cnPackage: '专家团包',
    cnCode: '专家团标识',
    cnName: '专家团名称',
    cnSummary: '专家团简介',
    typeFieldLabel: '类型',
    codeLabel: '专家团标识',
    nameLabel: '专家团名称（中文，必填）',
    nameLabelEn: '专家团名称（英文，可选）',
    summaryLabel: '专家团简介（中文，必填）',
    summaryLabelEn: '专家团简介（英文，可选）',
    namePlaceholder: '一句话说明这个专家团能完成什么任务',
    codeRule: '专家团标识只能由小写字母和数字组成，单词之间用连字符（-）分隔，例如 marketing-review-team；最长 64 位。',
    nameRequired: '请填写专家团名称。',
    nameMax: '专家团名称最多 128 个字符。',
    summaryRequired: '请填写专家团简介。',
    summaryMax: '专家团简介最多 512 个字符。',
    created: '专家团已创建，可继续上传首个专家团包',
    updated: '专家团信息已更新',
    published: '专家团已上架，桌面客户端下一次请求即可见',
    unpublished: '专家团已下架',
    deleted: '专家团已移入回收站，可在 30 天内恢复',
    onlineVersionDeleteErr: '线上版本不能直接删除，请先发布另一个版本或下架专家团。',
    unpublishTitle: '确认下架专家团',
    unpublishConfirm: '下架后桌面客户端将立即无法搜索与下载该专家团，已安装用户不受影响。',
    deleteTitle: '确认删除专家团',
    deleteConfirm: '删除后将移入回收站并立即从桌面端隐藏，30 天内可恢复。',
    codeMismatch: '输入的专家团标识不一致',
    leadLimit: '一个专家团最多只能有一位主理人（LEAD）。',
    noReadyVersionHint: '该专家团还没有线上版本，先上传一个专家团包。',
    versionInherit: '缺省继承专家团级',
    manifestHelp: '它由上传专家团包时的 plugin.json 原文写入。',
    manifestEmpty: '该专家团还没有包内清单（上传的包里没有 plugin.json，或专家团尚未上传过专家团包）。',
    versionTableEmpty: '还没有上传过专家团包。',
    packageHelp: '专家团包由桌面软件端优先携带登录 JWT 拉取，外部非交互客户端兼容 API Key。',
    downloadPackage: '下载完整包',
    downloadPackageTitle: '下载当前线上版本的完整专家团包',
    downloadBundle: '兼容包（已废弃）：含图完整包（含头像与插画）',
    downloadVersion: '下载',
    downloadVersionTitle: '下载该版本的完整专家团包',
    noDownloadableVersion: '该专家团还没有可下载的线上版本，请先上传并发布资源包。',
    packageListDesc: '已上架专家团列表',
    currentCodeHelp: '当前专家团标识：',
    readingPackage: '正在读取专家团包内容…',
    noPublishedFiles: '该专家团还没有对外发布的文件。',
    previewEmpty: '暂无已上架专家团，客户端当前看不到任何专家团。',
    previewNoMatch: '没有匹配的专家团，换个关键词试试。',
    reverseDepTitle: '个版本引用了本专家团',
    reverseDepEmpty: '没有其他包引用本专家团；下架或删除不会影响其他专家团。',
    searchAria: '搜索专家团',
    categoryAria: '专家团分类',
    previewSearchAria: '搜索客户端可见专家团',
    versionPlaceholder: '请填写专家团包版本号，例如 1.3.0。',
    scanRejected: '专家团包未通过安全扫描，已拒绝。',
    packageFailed: '专家团包处理失败，请重新上传。',
    scanning: '专家团包仍在安全扫描中，请稍后刷新版本列表查看结果。',
    kindHelpCreate: '专家团需维护成员花名册，成员来自已上架的专家。',
    kindHelpEdit: '类型创建后不可修改；如需变更请新建专家团。强行提交会被服务端以 422 invalid_skill_kind 拒绝。',
    editTitle: '编辑专家团',
    importTitle: '从资源包导入',
    importHint: '请用 YCPS 入口导入 .ycps.zip、含图完整包或含 plugin.json 的 YCPS 目录。ClawHub 原生包请用单独的 ClawHub ZIP/目录入口；拖入时系统会按包结构识别格式。',
    importPickZip: '导入 YCPS / 完整包 ZIP',
    importPickFolder: '导入 YCPS 包目录',
    importPickClawhub: '导入 ClawHub 技能 ZIP',
    importPickClawhubFolder: '导入 ClawHub 原生目录',
    importClawhubIdentity: '请输入 @发布者/技能标识；若 ZIP 没有版本信息，请追加 @版本（如 @publisher/demo@1.2.3）',
    importClawhubIdentityInvalid: '格式应为 @发布者/技能标识 或 @发布者/技能标识@版本',
    importClawhubBlocked: 'ClawHub 包结构/安全预检未通过：',
    importing: '正在解析资源包…',
    importDone: '已按资源包填入表单，请核对后提交',
    importFolderEmpty: '所选目录里没有文件，请确认选中的是包含 plugin.json 的包目录。',
    importDropEmpty: '拖入的内容里没有可用文件，请拖一个 .ycps.zip 或包含 plugin.json 的包目录。',
    importButton: '导入',
    importDropActive: '松开即可导入',
    importSafetyNotice: '添加第三方能力包前请确认其安全性。',
    importCodeTaken: '资源包标识已被占用，请改用「选择 .ycps.zip」新建其他标识，或先处理占用记录。',
    importPendingPackage: '将随创建一并上传',
  },
} as const

/**
 * 当前页面的类型作用域：只允许创建该作用域内的类型（E-01：类型创建后不可修改）。
 *
 * 三个页面各占一域，与 YCPS 的三种 kind 一一对应：`/admin/skills`、`/admin/experts`、
 * `/admin/teams` 分别传 `SKILL`、`EXPERT`、`TEAM`。列表固定按 `kinds` 过滤，因此三页数据
 * 互不重叠；类型标签仍然展示。
 */
const scopeKinds = computed<SkillKind[]>(() =>
  props.kinds
    .split(',')
    .map((entry) => entry.trim().toUpperCase())
    .filter((entry): entry is SkillKind => (KIND_VALUES as readonly string[]).includes(entry)),
)
const scope = computed(() => {
  const single = scopeKinds.value.length === 1 ? scopeKinds.value[0] : null
  if (single === 'SKILL') return KIND_SCOPE.SKILL
  if (single === 'TEAM') return KIND_SCOPE.TEAM
  // EXPERT alone, or a multi-kind scope such as the legacy `EXPERT,TEAM`, reads as the expert page.
  return KIND_SCOPE.EXPERTS
})
/**
 * 是否展示「介绍插画」区块。
 *
 * 插画是**专家详情页**的展示素材（桌面端 `ExpertDetailDialog` 消费），技能与专家团
 * 都没有消费方，所以技能管理页不提供这一栏 —— 上传了也没有地方显示。
 * 保留在专家页是既有能力，不在本次收敛范围内。
 */
const showIllustration = computed(() => scope.value !== KIND_SCOPE.SKILL)
/** 示例图片只服务单个专家的功能展示，普通技能与专家团页面不提供该资源位。 */
const showExampleImage = computed(() => scope.value === KIND_SCOPE.EXPERTS)
const isExpertScope = computed(() => scope.value === KIND_SCOPE.EXPERTS)
const canImportClawhub = computed(() => scopeKinds.value.length === 1 && scopeKinds.value[0] === 'SKILL')
/**
 * 服务端错误码文案（`skillAdminErrors`）默认按普通技能写。专家页复用同一批 code，
 * 需要把「技能 / 技能包」换成「专家 / 专家包」，否则会出现「专家管理」页提示
 * 「技能包结构校验未通过」的自相矛盾。
 */
const adminCopyScope = computed(() => ({
  noun: scope.value.cnNoun,
  packageNoun: scope.value.cnPackage,
}))
/**
 * 本次保存会产生的图片改动清单（用于「保存修改」前的最终确认）。
 *
 * 三处图片位（图标 / 插画 / 分类封面）在编辑页里都只做**本地标记**，
 * 真正落库集中到提交那一刻。这里把待提交的改动摊平成人能读的句子，
 * 由 `confirmImageChanges` 在保存前统一弹一次确认 ——
 * 一次保存可能同时改了图标和插画，逐项弹窗会变成连续打断。
 */
const pendingImageChanges = computed<string[]>(() => {
  const changes: string[] = []
  if (iconFile.value) changes.push('替换图标为新选择的图片')
  else if (iconPendingClear.value) changes.push('清空已上传的图标')
  if (showIllustration.value) {
    if (illustrationFile.value) changes.push('替换介绍插画为新选择的图片')
    else if (illustrationPendingClear.value) changes.push('清空已上传的介绍插画')
  }
  if (showExampleImage.value) {
    if (exampleImageFile.value) changes.push('替换示例图片为新选择的图片')
    else if (exampleImagePendingClear.value) changes.push('清空已上传的示例图片')
  }
  return changes
})

/** 列表请求固定的类型集合；类型标签仍按记录自身的 kind 渲染。 */
const listKindFilter = computed<SkillKindFilter>(() => {
  if (scopeKinds.value.length === 1) return scopeKinds.value[0] ?? ''
  if (scopeKinds.value.includes('EXPERT') && scopeKinds.value.includes('TEAM')) return 'EXPERT,TEAM'
  return ''
})
/** 新增对话框的默认类型：专家页默认「专家」，Skill 页固定「技能」。 */
function defaultFormKind(): SkillKind {
  return scopeKinds.value.includes('EXPERT') ? 'EXPERT' : (scopeKinds.value[0] ?? 'SKILL')
}
/**
 * 类型单选项：作用域内的类型，外加记录自身已有的类型（编辑一条不属于本页作用域的老记录时，
 * 必须如实只读展示它的真实类型，而不是隐去）。
 */
const formKindOptions = computed<SkillKind[]>(() =>
  (KIND_VALUES as readonly string[]).includes(form.kind) && !scopeKinds.value.includes(form.kind)
    ? [...scopeKinds.value, form.kind]
    : scopeKinds.value,
)
const refreshing = ref(false)
const errorMessage = ref('')

const skills = ref<SkillAdminListItem[]>([])
const total = ref(0)
const loading = ref(true)
const pageSizeOptions = [10, 20, 50]
const params = reactive<SkillAdminListParams>({
  page: 0,
  size: 20,
  query: '',
  status: '',
  category: '',
  kind: '',
  // 上架/下架会更新 updated_at；列表按创建时间排序，状态切换后卡片位置保持稳定。
  sort: 'created_at',
  direction: 'desc',
})
/** 已应用的筛选（点「查询」才生效，避免输入即请求）；类型由页面作用域固定，不在筛选里 */
const applied = reactive({ query: '', status: '', category: '' })
const categoryOptions = ref<string[]>([])

/**
 * 专家 / 专家团页使用「二次分类卡片」，Skill 页保持原表格。
 * 一级分类是类型（由页面作用域固定），二级分类是业务分类。
 */
const expertCardView = computed(() => scopeKinds.value.some((kind) => kind === 'EXPERT' || kind === 'TEAM'))
/**
 * 分类封面只属于专家侧词表：精选场景卡片用它，技能页不展示卡片也不用封面。
 *
 * 与词表作用域（`expertCategoryScope`）保持同一口径，避免技能分类被加上一个用不到的封面入口。
 */
const categoryCoverEnabled = computed(() => expertCardView.value)
/** 二级分类筛选：'' 表示全部 */
const expertCategoryFilter = ref('')

/**
 * 二级分类词表由后端管理（A23/A24/A25）：按作用域拉一次，管理员增删改后重新拉取。
 *
 * Skill 页与专家页共用同一个「管理分类」对话框，因此两页都要拉词表；
 * 拉取失败返回空数组（见 loadExpertCategories），页面退化为「未分类」而不是整页报错。
 */
const expertCategories = ref<ExpertCategoryDefinition[]>([])

const loadCategories = async (): Promise<void> => {
  expertCategories.value = await loadExpertCategories(expertCategoryScope(scopeKinds.value[0]))
}

/**
 * 分类下拉的候选：受控词表优先，再补上「数据里出现过但不在词表里」的历史分类。
 *
 * 词表是权威值（管理员在「管理分类」里维护），但历史包可能带着已下架/改名前的老分类，
 * 若只给词表，这些包在筛选下拉里就选不到了 —— 所以词表之后追加数据里收集到的未知值，
 * 保证既有数据不会因为词表调整而变得不可筛。词表拉取失败时退化为纯数据收集。
 */
const categorySelectOptions = computed(() => {
  const fromVocabulary = expertCategories.value.map((category) => category.name)
  const known = new Set(fromVocabulary)
  return [...fromVocabulary, ...categoryOptions.value.filter((name) => !known.has(name))]
})

/**
 * 分类的稳定 key：词表里有这个分类时用它的 code，否则原样（空值给 uncategorized）。
 *
 * 用 code 而不是中文名，是为了让筛选与分组的标识与展示语言无关，也不会因为分类改名而失配。
 */
function categoryKeyFor(name: string | null | undefined) {
  const key = expertCategoryKey(name)
  return expertCategories.value.find((category) => expertCategoryKey(category.name) === key)?.code ?? key
}

/** 当前页出现过的二级分类（含计数），按词表顺序排序 */
const expertCategoryOptions = computed(() => {
  const counts = new Map<string, { label: string; count: number }>()
  for (const item of skills.value) {
    const key = categoryKeyFor(item.category)
    const entry = counts.get(key) ?? { label: expertCategoryLabel(item.category), count: 0 }
    entry.count += 1
    counts.set(key, entry)
  }
  return [...counts.entries()]
    .map(([key, entry]) => ({ key, label: entry.label, count: entry.count }))
    .sort((left, right) => compareExpertCategoryNames(left.label, right.label))
})

/** 二级分类分组：只在卡片视图下按分类聚合，空分类不渲染 */
const expertCategoryGroups = computed(() => {
  const groups = new Map<string, { key: string; label: string; items: SkillAdminListItem[] }>()
  for (const item of skills.value) {
    const key = categoryKeyFor(item.category)
    if (expertCategoryFilter.value && expertCategoryFilter.value !== key) continue
    const group = groups.get(key) ?? { key, label: expertCategoryLabel(item.category), items: [] }
    group.items.push(item)
    groups.set(key, group)
  }
  return [...groups.values()].sort((left, right) => compareExpertCategoryNames(left.label, right.label))
})

/* ---------------- 列表批量选择 / 批量操作 ---------------- */

const BULK_DELETE_PHRASE = '我确认批量删除'
const BULK_UNCATEGORIZED = '__uncategorized__'
const selectedSkillIds = ref<string[]>([])
const bulkCategory = ref('')
const bulkAction = ref<'' | 'publish' | 'unpublish' | 'category' | 'download' | 'download-bundle' | 'download-clawhub' | 'download-all' | 'delete'>('')
const bulkDownloadMenuOpen = ref(false)

/** 卡片视图只全选当前二级分类下可见的卡片；表格视图全选当前页。 */
const visibleListItems = computed<SkillAdminListItem[]>(() =>
  expertCardView.value
    ? expertCategoryGroups.value.flatMap((group) => group.items)
    : skills.value,
)
const selectedSkills = computed(() => {
  const ids = new Set(selectedSkillIds.value)
  return skills.value.filter((item) => ids.has(item.id))
})
const allVisibleSkillsSelected = computed(() =>
  visibleListItems.value.length > 0
  && visibleListItems.value.every((item) => selectedSkillIds.value.includes(item.id)),
)
const visibleSelectionIndeterminate = computed(() => {
  const selectedCount = visibleListItems.value.filter((item) => selectedSkillIds.value.includes(item.id)).length
  return selectedCount > 0 && selectedCount < visibleListItems.value.length
})
const bulkBusy = computed(() => Boolean(bulkAction.value))
const bulkPublishItems = computed(() => selectedSkills.value.filter((item) =>
  item.status !== 'PUBLISHED',
))
const bulkUnpublishItems = computed(() => selectedSkills.value.filter((item) => item.status === 'PUBLISHED'))
/** 批量下载与单条下载一致：只下载当前线上版本，没有线上版本的勾选项会跳过。 */
const bulkDownloadItems = computed(() => selectedSkills.value.filter((item) =>
  item.sourceFormat !== 'CLAWHUB_SKILL' && Boolean(item.publishedVersion),
))
const bulkClawhubDownloadItems = computed(() => selectedSkills.value.filter((item) =>
  item.sourceFormat === 'CLAWHUB_SKILL',
))
type BulkDownloadMode = 'all' | 'ycps-with-assets' | 'ycps' | 'clawhub'
const hasSelectedYcps = computed(() => selectedSkills.value.some((item) => item.sourceFormat !== 'CLAWHUB_SKILL'))
const hasSelectedClawhub = computed(() => bulkClawhubDownloadItems.value.length > 0)
const bulkDefaultDownloadMode = computed<BulkDownloadMode>(() => {
  if (hasSelectedYcps.value && hasSelectedClawhub.value) return 'all'
  if (hasSelectedClawhub.value) return 'clawhub'
  return 'ycps-with-assets'
})
const bulkDefaultDownloadLabel = computed(() => {
  if (bulkDefaultDownloadMode.value === 'all') return '全部下载'
  if (bulkDefaultDownloadMode.value === 'clawhub') return '下载 ClawHub ZIP'
  return isExpertScope.value ? '下载 YCPS 1.1 完整包' : '下载 YCPS 含图完整包'
})
const canBulkDownload = computed(() => bulkDownloadItems.value.length > 0 || hasSelectedClawhub.value)
const isBulkDownloading = computed(() => bulkAction.value.startsWith('download'))
watch(selectedSkillIds, () => { bulkDownloadMenuOpen.value = false })
const canBulkDelete = computed(() =>
  selectedSkills.value.length > 0
  && selectedSkills.value.every((item) => item.status === 'UNPUBLISHED'),
)

function isSkillSelected(id: string) {
  return selectedSkillIds.value.includes(id)
}

function setSkillSelected(id: string, checked: boolean | string | number) {
  const ids = new Set(selectedSkillIds.value)
  if (Boolean(checked)) ids.add(id)
  else ids.delete(id)
  selectedSkillIds.value = [...ids]
}

function toggleVisibleSelection(checked: boolean | string | number) {
  const ids = new Set(selectedSkillIds.value)
  for (const item of visibleListItems.value) {
    if (Boolean(checked)) ids.add(item.id)
    else ids.delete(item.id)
  }
  selectedSkillIds.value = [...ids]
}

function clearBulkSelection() {
  selectedSkillIds.value = []
}

/* ---------------- 30 天回收站 ---------------- */

const recycleVisible = ref(false)
const recycleLoading = ref(false)
const recycleQuery = ref('')
const recyclePage = ref(0)
const recycleItems = ref<DeletedSkillView[]>([])
const recycleTotal = ref(0)
const recycleSelectedIds = ref<string[]>([])
const recycleAction = ref<'' | 'restore' | 'purge'>('')
const recycleBusy = computed(() => Boolean(recycleAction.value))
const recycleSelectedItems = computed(() => {
  const ids = new Set(recycleSelectedIds.value)
  return recycleItems.value.filter((item) => ids.has(item.id))
})
const recycleAllSelected = computed(() =>
  recycleItems.value.length > 0
  && recycleItems.value.every((item) => recycleSelectedIds.value.includes(item.id)),
)
const recycleSelectionIndeterminate = computed(() =>
  recycleSelectedItems.value.length > 0
  && recycleSelectedItems.value.length < recycleItems.value.length,
)

function setRecycleSelected(id: string, checked: boolean | string | number) {
  const ids = new Set(recycleSelectedIds.value)
  if (Boolean(checked)) ids.add(id)
  else ids.delete(id)
  recycleSelectedIds.value = [...ids]
}

function toggleRecycleSelection(checked: boolean | string | number) {
  recycleSelectedIds.value = Boolean(checked) ? recycleItems.value.map((item) => item.id) : []
}

function recycleDaysLeft(item: DeletedSkillView) {
  const remaining = new Date(item.expiresAt).getTime() - Date.now()
  return Math.max(0, Math.ceil(remaining / 86_400_000))
}

async function loadRecycleBin() {
  recycleLoading.value = true
  try {
    const page = await listDeletedAdminSkills({
      page: recyclePage.value,
      size: 100,
      query: recycleQuery.value.trim(),
      kind: props.kinds as SkillKindFilter,
    })
    recycleItems.value = page.items ?? []
    recycleTotal.value = page.total ?? recycleItems.value.length
    const visibleIds = new Set(recycleItems.value.map((item) => item.id))
    recycleSelectedIds.value = recycleSelectedIds.value.filter((id) => visibleIds.has(id))
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    recycleLoading.value = false
  }
}

async function openRecycleBin() {
  recycleVisible.value = true
  recyclePage.value = 0
  recycleSelectedIds.value = []
  await loadRecycleBin()
}

function searchRecycleBin() {
  recyclePage.value = 0
  return loadRecycleBin()
}

function changeRecyclePage(page: number) {
  recyclePage.value = Math.max(0, page - 1)
  recycleSelectedIds.value = []
  return loadRecycleBin()
}

/** 回收站批量操作直接执行；失败项保留勾选，便于修正后重试。 */
async function runRecycleAction(
  actionName: 'restore' | 'purge',
  action: (id: string) => Promise<unknown>,
) {
  const items = recycleSelectedItems.value
  if (!items.length || recycleBusy.value) return
  recycleAction.value = actionName
  errorMessage.value = ''
  const failed: Array<{ item: DeletedSkillView; error: unknown }> = []
  let succeeded = 0
  try {
    for (const item of items) {
      try {
        await action(item.id)
        succeeded += 1
      } catch (error) {
        failed.push({ item, error })
      }
    }
    recycleSelectedIds.value = failed.map(({ item }) => item.id)
    await Promise.all([loadRecycleBin(), load(params.page, { silent: true })])
    if (failed.length) {
      errorMessage.value = `批量操作已完成 ${succeeded} 个，失败 ${failed.length} 个。${skillAdminErrorMessage(failed[0]?.error, adminCopyScope.value)}`
      return
    }
    ElMessage.success(actionName === 'restore'
      ? `已恢复 ${succeeded} 个${scope.value.cnNoun}，需重新上架后才会向桌面端展示`
      : `已彻底删除 ${succeeded} 个${scope.value.cnNoun}`)
  } finally {
    recycleAction.value = ''
  }
}

function restoreRecycleSelection() {
  return runRecycleAction('restore', restoreAdminSkill)
}

function purgeRecycleSelection() {
  return runRecycleAction('purge', purgeAdminSkill)
}

function itemCategoryLabel(item: SkillAdminListItem) {
  return expertCategoryLabel(item.category)
}

/** 卡片徽标：记录自带 icon 优先，否则退回分类单字 */
function itemBadge(item: SkillAdminListItem) {
  const icon = skillIcon(item)
  return icon || expertCategoryBadge(item.category)
}

/* ---------------- 分类管理（A23 / A24 / A26） ---------------- */

/**
 * 分类管理整块已抽成 `SkillCategoryManager.vue`：
 * 它只依赖「当前词表 + 词表作用域」，与列表、版本、花名册都没有交集。
 * 这里只保留开关与两个回调 —— 词表本身仍由本组件持有（列表筛选也要用），
 * 子组件改完词表后回调这里重新拉取，避免两处各存一份。
 */
const categoryDialogVisible = ref(false)
const categoryManagerRef = ref<InstanceType<typeof SkillCategoryManager> | null>(null)

function openCategoryDialog() {
  categoryManagerRef.value?.open()
}

/** 子组件删除分类后：被删的分类若正被筛选，回到「全部」以免停在空列表 */
function onCategoryRemoved(name: string) {
  if (expertCategoryFilter.value === expertCategoryKey(name)) {
    expertCategoryFilter.value = ''
  }
}

const publishingId = ref('')
const deletingId = ref('')
const drawerLoading = ref(false)
const versionActionId = ref('')
/** A25 正在下载的版本 id；非空时该行按钮转圈 */
const downloadingVersionId = ref('')
/** 抽屉底部「下载」展开菜单：默认动作不经过它，只有选含图完整包时才展开。 */
const downloadMenuOpen = ref(false)

const formVisible = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const submitting = ref(false)
const uploadingPackage = ref(false)
const formSkill = ref<SkillAdminDetail | null>(null)
const formDetailLoading = ref(false)

/**
 * 新增 / 编辑改为分步向导。
 *
 * 16 个字段平铺在一个对话框里，创建时要从头滚到尾才能确认自己填完了，
 * 编辑还要再叠上整个「资源包」区块。拆成三步后每屏只面对一组相关字段。
 *
 * 三步的区块用 `v-show` 而不是 `v-if`：字段始终在 DOM 里，
 * 这样「跨步填完后直接提交」和「校验失败时跳到出错的步骤」都能正常工作，
 * 不会因为字段被卸载而丢掉用户已经输入的值。
 */
const formStep = ref(0)
const FORM_STEPS = [
  { key: 'identity', title: '基础信息', hint: '编码、名称与分类决定客户端如何识别这个条目' },
  { key: 'presentation', title: '展示与说明', hint: '简介、标签与详细说明会出现在客户端的列表与详情里' },
  { key: 'delivery', title: '客户端与资源包', hint: '适配版本与资源包上传' },
] as const
const isLastFormStep = computed(() => formStep.value >= FORM_STEPS.length - 1)

/** 上一步 / 下一步；类型在编辑态不可改，因此不在这里拦截。 */
function goToFormStep(step: number): void {
  formStep.value = Math.min(Math.max(step, 0), FORM_STEPS.length - 1)
}

function nextFormStep(): void {
  goToFormStep(formStep.value + 1)
}

function previousFormStep(): void {
  goToFormStep(formStep.value - 1)
}

const form = reactive({
  code: '',
  name: '',
  nameEn: '',
  category: '',
  tags: [] as string[],
  icon: '',
  summary: '',
  summaryEn: '',
  description: '',
  descriptionEn: '',
  usageGuide: '',
  usageGuideEn: '',
  taskTypes: [] as string[],
  taskTypesEn: [] as string[],
  examplePrompts: [] as string[],
  examplePromptsEn: [] as string[],
  examplePreview: null as ExpertPresentationPayload['preview'],
  examplePreviewEn: null as ExpertPresentationPayload['previewEn'],
  /** Legacy scalar preview bridge for older gateway responses. */
  examplePreviewInput: '',
  examplePreviewInputEn: '',
  examplePreviewOutput: '',
  examplePreviewOutputEn: '',
  sortWeight: 0,
  minClientVersion: '2026.6.0',
  rowVersion: 0,
  kind: 'SKILL' as SkillKind,
})
const descriptionMaxLength = computed(() => form.kind === 'SKILL'
  ? MAX_SKILL_DESCRIPTION_LENGTH
  : form.kind === 'EXPERT' ? EXPERT_WYSIWYG_LIMITS.description : MAX_OTHER_DESCRIPTION_LENGTH)
const expertDraft = ref<ExpertWysiwygDraft>(createExpertWysiwygDraft())
const expertDraftBaseline = ref<ExpertWysiwygDraft>(createExpertWysiwygDraft())
const expertAdvancedBaseline = ref('')
// A package import can stage a canonical presentation before the base skill exists.
// Keep that intent separate from the draft diff so an imported draft whose baseline is
// already normalized still gets sent to the additive expert-presentation endpoint.
const expertPresentationPending = ref(false)
const expertDraftDirty = computed(() => JSON.stringify(expertDraft.value) !== JSON.stringify(expertDraftBaseline.value))
const expertDraftErrors = computed(() => validateExpertWysiwygDraft(expertDraft.value))
const iconFile = ref<File | null>(null)
const iconPreviewUrl = ref('')
const iconSaving = ref(false)
/** 正在清空已上传图标（提交阶段用） */
const iconDeleting = ref(false)
/** 正在下载已上传图标 */
const iconDownloading = ref(false)
/**
 * 「待清空已上传图标」标记：点「清空已上传图标」只置位，不碰后端。
 *
 * 之前是点了就当场删对象 + 立刻刷新列表，误点没有反悔余地（2026-09-19 需求）。
 * 现在与「换图」一样走延迟提交：标记 → 可撤销 → 点「保存修改」时统一确认并提交。
 */
const iconPendingClear = ref(false)
/** 介绍插画：本地待上传文件、其预览 URL、保存中、清空中、下载中 */
const illustrationFile = ref<File | null>(null)
const illustrationPreviewUrl = ref('')
const illustrationSaving = ref(false)
const illustrationDeleting = ref(false)
const illustrationDownloading = ref(false)
/** 介绍插画的「待清空」标记，语义同 `iconPendingClear` */
const illustrationPendingClear = ref(false)
/** 示例图片：本地文件、预览地址、提交状态与待清空标记。 */
const exampleImageFile = ref<File | null>(null)
const exampleImagePreviewUrl = ref('')
const exampleImageSaving = ref(false)
const exampleImageDeleting = ref(false)
const exampleImageDownloading = ref(false)
const exampleImagePendingClear = ref(false)

/**
 * The canvas and the advanced drawer are one atomic Expert edit. Keep a compact
 * baseline for fields that deliberately live outside `ExpertWysiwygDraft` so a
 * category or card-summary edit cannot leave the header saying everything is
 * already saved. `form.tags` remains an operational field in the aggregate
 * payload, but is intentionally not exposed as a second Expert editor here.
 */
function expertAdvancedSignature(minClientVersion = form.minClientVersion): string {
  return JSON.stringify({
    code: form.code,
    category: form.category,
    tags: form.tags,
    icon: form.icon,
    summary: form.summary,
    summaryEn: form.summaryEn,
    sortWeight: form.sortWeight,
    minClientVersion,
  })
}

function captureExpertAdvancedBaseline(minClientVersion = form.minClientVersion): void {
  expertAdvancedBaseline.value = expertAdvancedSignature(minClientVersion)
}

const expertEditorDirty = computed(() =>
  expertDraftDirty.value
  || expertPresentationPending.value
  || pendingImageChanges.value.length > 0
  || expertAdvancedSignature() !== expertAdvancedBaseline.value,
)

/**
 * A27：新建时从资源包导入。
 *
 * 过去新建只能手打 16 个字段，而包内其实已经声明了标识、名称、简介与类型 ——
 * 打错了要等到上传时才以 `package_identity_mismatch` 报出来。现在先读包再填表，
 * 操作者变成「核对」而不是「誊抄」。
 *
 * `pendingPackage` 是「解析通过、但还没上传」的那个包体：创建记录后由它完成首次上传。
 * 解析严格只读，所以在用户还没提交前它只存在于内存里，不产生任何后端痕迹。
 */
const importingPackage = ref(false)
interface PendingPackageImport {
  file: File
  version: string
  source: 'zip' | 'folder'
  completeBundle: boolean
}
const pendingPackage = ref<PendingPackageImport | null>(null)
/** 导入来源目录名，仅用于在表单里提示「这个包是从哪来的」 */
const pendingPackageLabel = ref('')
interface PendingClawhubImport extends ClawhubSkillImportPayload {
  report: ClawhubConversionReport
  remote?: Pick<ClawhubRemoteMetadata, 'sourceKind' | 'sourceUrl' | 'sourceRevision'> & { requestedUrl: string }
}
const pendingClawhub = ref<PendingClawhubImport | null>(null)
const clawhubReport = ref<ClawhubConversionReport | null>(null)
const remoteImportUrl = ref('')
const remoteImportLoading = ref(false)
const remoteImportError = ref('')
let remoteImportGeneration = 0
let remoteImportController: AbortController | null = null
let clawhubDraftBeforePreview: {
  fields: typeof form
  package: PendingPackageImport | null
  clawhub: PendingClawhubImport | null
  label: string
  report: ClawhubConversionReport | null
  availability: SkillCodeAvailabilityView | null
  step: number
} | null = null
/**
 * 两个原生 file input 的模板 ref。
 *
 * 模板里不能直接写 `document.getElementById(...)` —— 在 `<script setup>` 中
 * `document` 会被解析成组件实例上的属性，类型检查直接报 TS2339。
 */
const importZipInput = ref<HTMLInputElement | null>(null)
const importFolderInput = ref<HTMLInputElement | null>(null)
const importClawhubInput = ref<HTMLInputElement | null>(null)
const importClawhubFolderInput = ref<HTMLInputElement | null>(null)
/** 合并后的「导入」按钮：下拉里二选一（zip / 包目录），不再平铺两个按钮。 */
const importMenuOpen = ref(false)
/** 拖拽悬停高亮：dragover 会持续触发，用计数器抵消子元素的 dragleave 抖动。 */
const dragDepth = ref(0)
const dragActive = computed(() => dragDepth.value > 0)

/**
 * A22：编码预检。`form.code` 在新建时按防抖查询后端占用情况，
 * 把「上传后才报编码冲突」提前成可操作提示。编辑态不改编码，因此不查。
 */
const codeAvailability = ref<SkillCodeAvailabilityView | null>(null)
const codeAvailabilityLoading = ref(false)
let codeAvailabilityTimer: ReturnType<typeof setTimeout> | null = null

function codeHolderLabel(holder: SkillCodeHolderView | null | undefined): string {
  if (!holder) return ''
  const kind = KIND_LABELS[holder.kind] ?? holder.kind
  const status = STATUS_LABELS[holder.status] ?? holder.status
  return `${kind}（状态：${status}）`
}

function scheduleCodeAvailabilityCheck() {
  if (codeAvailabilityTimer) clearTimeout(codeAvailabilityTimer)
  const code = form.code.trim()
  if (formMode.value !== 'create' || !SKILL_CODE_PATTERN.test(code)) {
    codeAvailability.value = null
    codeAvailabilityLoading.value = false
    return
  }
  codeAvailabilityLoading.value = true
  codeAvailabilityTimer = setTimeout(async () => {
    try {
      const result = await fetchSkillCodeAvailability(code)
      if (form.code.trim() !== code) return
      codeAvailability.value = result
    } catch {
      codeAvailability.value = null
    } finally {
      if (form.code.trim() === code) codeAvailabilityLoading.value = false
    }
  }, 400)
}

/* ---------------- 专家花名册（详情抽屉内编辑） ---------------- */

const members = ref<SkillMemberView[]>([])
/** 本地草稿：所有编辑都留在这里，只有「保存花名册」才整表 PUT（设计 §3.1 D3） */
const roster = ref<SkillMemberPayload[]>([])
const membersSaving = ref(false)
/**
 * 可作为花名册成员的已上架专家（code → 显示名）。
 *
 * 花名册成员**必须是已上架的专家包**：成员标识就是那个专家的 `code`，头像与可安装性都来自
 * 它。列表按当前 `kind` 过滤（专家团页拿不到专家），所以这里单独拉一次 EXPERT。
 * 保存时后端还会再校验一遍，这里只是把「填了不存在的标识」提前成下拉选择。
 */
const expertOptions = ref<Array<{ code: string; name: string }>>([])
/**
 * 成员头像的 object URL 缓存。
 *
 * 成员是一个专家包引用，头像直接读那个专家包自己的图标（按 `memberKey` == 专家 code），
 * 所以这里只缓存只读预览，没有上传与移除——换头像要去那个专家包自己的表单里换。
 */
const memberAvatarUrls = ref<Record<string, string>>({})
let rosterBaseline = '[]'
let memberKeySeq = 0

const versionForm = reactive({ version: '', changelog: '', minClientVersion: '' })
const replaceTarget = ref<SkillVersionView | null>(null)
const uploadProgress = ref<SkillVersionUploadProgress | null>(null)
const uploadStatus = ref<'idle' | 'uploading' | 'scanning' | 'failed' | 'done'>('idle')
let uploadController: AbortController | null = null
let pollCancelled = false
let mounted = true
let listGeneration = 0
const iconObjectUrls = new Set<string>()
const expertAssetObjectUrls = new Set<string>()
const expertAssetRequestVersions: Record<ExpertImageRole, number> = {
  icon: 0,
  illustration: 0,
  exampleImage: 0,
}

function invalidateExpertAssetRequests(role?: ExpertImageRole) {
  if (role) {
    expertAssetRequestVersions[role] += 1
    return
  }
  ;(Object.keys(expertAssetRequestVersions) as ExpertImageRole[]).forEach((item) => {
    expertAssetRequestVersions[item] += 1
  })
}

const route = useRoute()
const router = useRouter()

const drawerOpen = ref(false)
const selected = ref<SkillAdminDetail | null>(null)
const selectedId = ref('')
const nativeClawhubVersions = ref<NativeClawhubVersionView[]>([])
const nativeClawhubCurrentVersionId = ref<string | null>(null)
const nativeClawhubVersionsLoading = ref(false)
const nativeClawhubVersionsError = ref('')
const nativeClawhubVersionUploading = ref(false)
const nativeClawhubVersionRescanningId = ref('')
const nativeClawhubVersionInput = ref<HTMLInputElement | null>(null)
const detailPackageFolderInput = ref<HTMLInputElement | null>(null)
const detailPackagePreparing = ref(false)
const detailPackageDragDepth = ref(0)
const detailPackageDragActive = computed(() => detailPackageDragDepth.value > 0)
const detailPackageBusy = computed(() =>
  detailPackagePreparing.value || nativeClawhubVersionUploading.value || uploadingPackage.value,
)
let nativeClawhubVersionsRequest = 0

const previewLoading = ref(false)
const previewFailure = ref<SkillFailure | null>(null)
const previewWarning = ref('')
type PreviewSkill = SkillSummary & { nativeClawhub?: NativeClawhubSkillSummary }
const previewSkills = ref<PreviewSkill[]>([])
const previewKeyword = ref('')
const detailOpen = ref(false)
const detailName = ref('')
const detail = ref<SkillDetail | null>(null)
const detailLoading = ref(false)
const detailFailure = ref<SkillFailure | null>(null)
const previewPath = ref('')

const bytesFormatter = new Intl.NumberFormat('zh-CN', {
  style: 'unit',
  unit: 'byte',
  notation: 'compact',
  maximumFractionDigits: 1,
})

const STATUS_LABELS: Record<SkillAdminStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已上架',
  UNPUBLISHED: '已下架',
  DELETED: '已删除',
}

const STATUS_TAG_TYPES: Record<SkillAdminStatus, 'success' | 'info' | 'warning' | 'danger'> = {
  PUBLISHED: 'success',
  UNPUBLISHED: 'info',
  DRAFT: 'warning',
  DELETED: 'danger',
}

const SCAN_LABELS: Record<string, string> = {
  PENDING: '待扫描',
  SCANNING: '扫描中',
  PASSED: '已通过',
  REJECTED: '已拒绝',
  FAILED: '扫描失败',
}

const VERSION_STATUS_LABELS: Record<SkillVersionStatus, string> = {
  DRAFT: '上传中',
  READY: '已就绪',
  PUBLISHED: '线上版本',
  ARCHIVED: '历史版本',
  REJECTED: '已拒绝',
  FAILED: '处理失败',
}

const KIND_LABELS: Record<SkillKind, string> = {
  SKILL: '技能',
  EXPERT: '专家',
  TEAM: '专家团',
}

/** 专家团=danger、专家=warning、技能=info（本轮验收点 V1） */
const KIND_TAG_TYPES: Record<SkillKind, 'danger' | 'warning' | 'info'> = {
  TEAM: 'danger',
  EXPERT: 'warning',
  SKILL: 'info',
}

const MEMBER_ROLE_LABELS: Record<SkillMemberRole, string> = {
  LEAD: '主理人',
  MEMBER: '成员',
}

const detailSummary = computed(() =>
  previewSkills.value.find((skill) => skill.name === detailName.value) ?? null,
)
const nativePreviewDetail = computed(() => detailSummary.value?.nativeClawhub ?? null)
const dialogTitle = computed(() => detailSummary.value?.title || detail.value?.title || detailName.value)
const previewSearchActive = computed(() => previewKeyword.value.trim().length > 0)
/** 名称 / 标题 / 描述 / 分类 / 标签（含英文）任一命中即保留 */
const filteredPreviewSkills = computed(() => {
  const needle = previewKeyword.value.trim().toLowerCase()
  if (!needle) return previewSkills.value
  return previewSkills.value.filter((skill) => {
    const haystack = [
      skill.name,
      skill.title,
      skill.titleEn ?? '',
      skill.description ?? '',
      skill.descriptionEn ?? '',
      skill.summaryEn ?? '',
      skill.category ?? '',
      ...(Array.isArray(skill.tags) ? skill.tags : []),
    ]
    return haystack.some((value) => String(value).toLowerCase().includes(needle))
  })
})
const previewFile = computed(() => {
  if (!detail.value || !previewPath.value) return null
  return detail.value.files.find((file) => file.path === previewPath.value) ?? null
})

/**
 * 客户端预览按 kind 分成三块：技能、专家、专家团。
 * 三类各自独立成块（空态也各自独立），不再把 Skills 与专家/专家团同屏混排。
 * 普通 YCPS 与原生 ClawHub 分别读取客户端的两个目录，再按 `kind` 拆分。
 */
const PREVIEW_SECTIONS: ReadonlyArray<{ kind: SkillKind; title: string; empty: string }> = [
  { kind: 'SKILL', title: '技能桌面端预览', empty: '暂无已上架技能，客户端当前看不到任何技能。' },
  { kind: 'EXPERT', title: '专家桌面端预览', empty: '暂无已上架专家，客户端当前看不到任何专家。' },
  { kind: 'TEAM', title: '专家团桌面端预览', empty: '暂无已上架专家团，客户端当前看不到任何专家团。' },
]

const previewSections = computed(() => PREVIEW_SECTIONS.map((section) => ({
  ...section,
  items: filteredPreviewSkills.value.filter((skill) => skill.kind === section.kind),
})))
const previewText = computed(() => {
  const content = previewFile.value?.content ?? ''
  return content.length > PREVIEW_LIMIT
    ? `${content.slice(0, PREVIEW_LIMIT)}\n…（正文过长，已截断预览）`
    : content
})

const versions = computed<SkillVersionView[]>(() => selected.value?.versions ?? [])
const publishedVersion = computed(() => versions.value.find(isOnlineVersion) ?? null)
const isNativeClawhubSelected = computed(() => selected.value?.sourceFormat === 'CLAWHUB_SKILL')
const nativeClawhubOnlineVersion = computed(() => selected.value?.status === 'PUBLISHED'
  ? nativeClawhubVersions.value.find(isCurrentOnlineNativeClawhubVersion) ?? null
  : null)
/** 编辑表单里的版本来自 formSkill（抽屉未打开时 selected 为空） */
const formVersions = computed<SkillVersionView[]>(() => formSkill.value?.versions ?? [])
const formPublishedVersion = computed(() => formVersions.value.find(isOnlineVersion) ?? null)
const hasPendingVersion = computed(() => {
  const row = selected.value
  if (!row) return false
  return Boolean(row.latestVersion && row.publishedVersion && row.latestVersion !== row.publishedVersion)
})
const uploadPhaseText = computed(() => {
  const progress = uploadProgress.value
  if (!progress) return ''
  if (progress.phase === 'hashing') return '校验文件'
  if (progress.phase === 'scanning') return '安全扫描中'
  return '上传中'
})
const uploadLocked = computed(() => uploadingPackage.value)

/** 详情抽屉与预览卡片都按 sortOrder 展示，避免依赖后端数组顺序 */
const orderedMembers = computed(() => [...members.value].sort((a, b) => a.sortOrder - b.sortOrder))
const previewMembers = computed(() =>
  [...(detail.value?.members ?? [])].sort((a, b) => a.sortOrder - b.sortOrder),
)
const hasLeadMember = computed(() => roster.value.some((member) => member.role === 'LEAD'))
/** 花名册是否有未保存改动 */
const rosterDirty = computed(() => JSON.stringify(roster.value) !== rosterBaseline)
/** 包内清单原文：只读折叠区展示用 */
const manifestText = computed(() => {
  const manifest = selected.value?.manifest
  if (manifest === null || manifest === undefined) return ''
  try {
    return JSON.stringify(manifest, null, 2)
  } catch {
    return ''
  }
})
/** 包内清单默认折叠：展开状态由 el-collapse 的 v-model 承载，折叠时不渲染 <pre> 与内容。 */
const manifestOpen = ref<Array<string | number>>([])
/** Expert presentation is the primary editor; operational/package knobs stay in a collapsed pane. */
const expertAdvancedOpen = ref<Array<string | number>>([])

/* ---------------- 列表 ---------------- */

function isCurrentList(generation: number) {
  return mounted && generation === listGeneration
}

async function load(targetPage = params.page, options: { silent?: boolean } = {}) {
  const generation = ++listGeneration
  if (options.silent) refreshing.value = true
  else loading.value = true
  errorMessage.value = ''
  params.page = targetPage
  try {
    const result = await listAdminSkills({
      // 列表类型由页面作用域固定（Skill 页 SKILL；专家页 EXPERT；专家团页 TEAM），不再来自筛选控件。
      page: targetPage,
      size: params.size,
      query: params.query.trim(),
      status: params.status,
      category: params.category,
      kind: listKindFilter.value,
      sort: params.sort,
      direction: params.direction,
    })
    if (!isCurrentList(generation)) return
    const items = Array.isArray(result?.items) ? result.items : []
    skills.value = items
    // 勾选只属于当前页：翻页或筛选后丢掉已不在列表中的 id，避免误操作隐藏项。
    const currentIds = new Set(items.map((item) => item.id))
    selectedSkillIds.value = selectedSkillIds.value.filter((id) => currentIds.has(id))
    total.value = typeof result?.total === 'number' ? result.total : items.length
    if (typeof result?.page === 'number') params.page = result.page
    if (typeof result?.size === 'number') params.size = result.size
    collectCategories(items)
    // 列表与卡片都要显示图标，所以整页一次性预取：只处理后端标了 iconUrl 的记录，
    // 其余跳过（不发注定 404 的请求）。拉取是并行的，不阻塞列表渲染。
    void iconPreviews.loadMany(items)
  } catch (error) {
    if (!isCurrentList(generation)) return
    skills.value = []
    total.value = 0
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    if (isCurrentList(generation)) {
      loading.value = false
      refreshing.value = false
    }
  }
}

function collectCategories(items: SkillAdminListItem[]) {
  // 专家页的二级分类是受控词表，不在数据里追加；Skill 页沿用既有行为（从数据里收集）。
  if (expertCardView.value) return
  const known = new Set(categoryOptions.value)
  let changed = false
  for (const item of items) {
    const category = (item.category ?? '').trim()
    if (category && !known.has(category)) {
      known.add(category)
      changed = true
    }
  }
  if (changed) categoryOptions.value = [...known].sort((a, b) => a.localeCompare(b, 'zh-CN'))
}

function applyFilters() {
  clearBulkSelection()
  applied.query = params.query.trim()
  applied.status = params.status
  applied.category = params.category
  syncQuery()
  void load(0)
}

function resetFilters() {
  params.query = ''
  params.status = ''
  params.category = ''
  expertCategoryFilter.value = ''
  applyFilters()
}

function changePageSize(size: number) {
  clearBulkSelection()
  params.size = size
  void load(0)
}

/** 设计 §8.5：筛选状态写入 URL query，刷新与分享都能保持 */
function syncQuery() {
  void router.replace({
    query: {
      ...route.query,
      query: applied.query || undefined,
      status: applied.status || undefined,
      category: applied.category || undefined,
    },
  })
}

function statusLabel(status: SkillAdminStatus) {
  return STATUS_LABELS[status] ?? status
}

function statusTagType(status: SkillAdminStatus) {
  return STATUS_TAG_TYPES[status] ?? 'info'
}

function publishAdminSkillRecord(item: SkillAdminListItem) {
  return item.sourceFormat === 'CLAWHUB_SKILL'
    ? publishAdminClawhubSkill(item.id)
    : publishAdminSkill(item.id)
}

type NativeClawhubPublishResolution =
  | { kind: 'ready'; version: NativeClawhubVersionView }
  | { kind: 'choose' }
  | { kind: 'invalid'; code: string }

/** A saved release always wins over newer drafts; only a never-published single source is implicit. */
function resolveNativeClawhubPublishTarget(list: NativeClawhubVersionList): NativeClawhubPublishResolution {
  const versions = list.items ?? []
  let version: NativeClawhubVersionView | undefined
  if (list.currentPublishedVersionId) {
    version = versions.find((entry) => entry.id === list.currentPublishedVersionId)
    if (!version) return { kind: 'invalid', code: 'native_clawhub_source_not_found' }
  } else {
    if (versions.length > 1) return { kind: 'choose' }
    version = versions[0]
    if (!version) return { kind: 'invalid', code: 'skill_has_no_ready_version' }
  }
  if (version.scanStatus !== 'PASSED') return { kind: 'invalid', code: 'native_clawhub_scan_not_passed' }
  if (!version.version || !STRICT_SEMVER_PATTERN.test(version.version)) {
    return { kind: 'invalid', code: 'native_clawhub_version_required' }
  }
  return { kind: 'ready', version }
}

function nativeClawhubPublishError(code: string) {
  return { response: { status: code === 'native_clawhub_source_not_found' ? 404 : 409, data: { code } } }
}

function needsClawhubSourceVersion(
  item: Pick<SkillAdminListItem, 'sourceFormat' | 'sourceVersion' | 'status'>,
) {
  return item.sourceFormat === 'CLAWHUB_SKILL'
    && item.status === 'DRAFT'
    && item.sourceVersion === null
}

async function promptClawhubSourceVersion(skillId: string) {
  const result = await ElMessageBox.prompt(
    '该原生 ClawHub 草稿缺少版本号。请输入严格 SemVer（如 1.2.3 或 1.2.3-rc.1）；补录只更新来源版本，不会发布到桌面。',
    '补录 ClawHub 版本号',
    {
      inputPlaceholder: '1.2.3',
      inputPattern: STRICT_SEMVER_PATTERN,
      inputErrorMessage: '版本号必须是严格 SemVer，例如 1.2.3 或 1.2.3-rc.1。',
      confirmButtonText: '保存版本号',
      cancelButtonText: '取消',
    },
  ).catch(() => null)
  if (!result) return false
  const version = result.value.trim()
  if (!STRICT_SEMVER_PATTERN.test(version)) return false

  errorMessage.value = ''
  try {
    await updateAdminClawhubSourceVersion(skillId, version)
    await load(params.page, { silent: true })
    if (selected.value?.id === skillId) await reloadDetail()
    ElMessage.success(`ClawHub 源版本已补录为 ${version}；记录仍是草稿，如需桌面可见请单独上架。`)
    return true
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
    return false
  }
}

async function confirmNativeClawhubReviewPublish(
  item: Pick<SkillAdminListItem, 'sourceFormat' | 'sourceReviewRequired'>,
  actionDetails = '',
) {
  if (item.sourceFormat !== 'CLAWHUB_SKILL' || item.sourceReviewRequired !== true) return true
  return ElMessageBox.confirm(
    `请确认已核对原生 ClawHub 技能的运行权限、依赖和安全扫描结果。上架后桌面端可获取该版本；启用仍需满足本地配置要求。${actionDetails ? `\n\n${actionDetails}` : ''}`,
    '确认上架原生 ClawHub 技能',
    { type: 'warning', confirmButtonText: '审核通过并上架', cancelButtonText: '取消' },
  ).then(() => true).catch(() => false)
}

function scanLabel(status: string) {
  return SCAN_LABELS[status] ?? status
}

function scanTagType(status: string) {
  if (status === 'PASSED') return 'success'
  if (status === 'REJECTED' || status === 'FAILED') return 'danger'
  if (status === 'SCANNING') return 'warning'
  return 'info'
}

function versionStatusLabel(status: SkillVersionStatus) {
  return VERSION_STATUS_LABELS[status] ?? status
}

function versionStatusTagType(status: SkillVersionStatus) {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'READY') return 'primary'
  if (status === 'REJECTED' || status === 'FAILED') return 'danger'
  if (status === 'DRAFT') return 'warning'
  return 'info'
}

/** 后端字段是 `online`；`status === 'PUBLISHED'` 作为兜底，避免旧数据判错 */
function isOnlineVersion(version: SkillVersionView) {
  return version.online === true || version.status === 'PUBLISHED'
}

/* ---------------- 版本审核面：YCPS 信任字段 / 权限 / 依赖（A19/A20） ---------------- */

const reviewOpen = ref(false)
const reviewLoading = ref(false)
const reviewVersion = ref<SkillVersionView | null>(null)
const reviewDependencies = ref<SkillDependencyView[]>([])
const reviewDependents = ref<SkillDependentEntry[]>([])
const reviewDependencyError = ref('')

const reviewPermission = computed(() => permissionSummary(reviewVersion.value))
const reviewHost = computed(() => hostRequirementsSummary(reviewVersion.value))

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : []
}

/** runtime manifest 的一个对象子节点；缺失或形状不对时返回空对象 */
function manifestSection(version: SkillVersionView | null, section: string): Record<string, unknown> {
  const manifest = version?.runtimeManifest
  if (!manifest || typeof manifest !== 'object') return {}
  const value = (manifest as Record<string, unknown>)[section]
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {}
}

function permissionSummary(version: SkillVersionView | null) {
  const requests = manifestSection(version, 'permissionRequests')
  return {
    tools: stringList(requests.tools),
    network: stringList(requests.network),
    filesystem: stringList(requests.filesystem),
  }
}

function hostRequirementsSummary(version: SkillVersionView | null) {
  const host = manifestSection(version, 'hostRequirements')
  return {
    minClientVersion: typeof host.minClientVersion === 'string' ? host.minClientVersion : null,
    platforms: stringList(host.platforms),
    architectures: stringList(host.architectures),
  }
}

function isHighRiskCapability(capability: string) {
  return HIGH_RISK_CAPABILITIES.has(capability)
}

/** 短摘要：展示前后各 8 位，完整值放 title（避免长哈希撑破抽屉） */
function shortDigest(value: string | null | undefined) {
  if (!value) return '—'
  return value.length > 20 ? `${value.slice(0, 8)}…${value.slice(-8)}` : value
}

function dependencyRelationLabel(relation: string) {
  return DEPENDENCY_RELATION_LABELS[relation] ?? relation
}

function dependencyTargetLabel(status: string) {
  return VERSION_STATUS_LABELS[status as SkillVersionStatus] ?? DEPENDENCY_TARGET_LABELS[status] ?? status
}

function dependencyTargetTagType(status: string) {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'READY') return 'primary'
  if (status === 'DELETED' || status === 'ARCHIVED') return 'info'
  return 'warning'
}

/** 打开审核详情：信任字段来自版本行本身，依赖与反向依赖再取 A19/A20 */
async function openVersionReview(version: SkillVersionView) {
  if (!selected.value) return
  reviewVersion.value = version
  reviewOpen.value = true
  reviewLoading.value = true
  reviewDependencyError.value = ''
  reviewDependencies.value = []
  reviewDependents.value = []
  try {
    const [dependenciesView, dependentsView] = await Promise.all([
      fetchSkillVersionDependencies(selected.value.id, version.id),
      fetchSkillDependents(selected.value.id),
    ])
    reviewDependencies.value = dependenciesView.dependencies ?? []
    reviewDependents.value = dependentsView.dependents ?? []
  } catch (error) {
    reviewDependencyError.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    reviewLoading.value = false
  }
}

function closeVersionReview() {
  reviewOpen.value = false
  reviewVersion.value = null
  reviewDependencies.value = []
  reviewDependents.value = []
  reviewDependencyError.value = ''
}

/**
 * A21：包级关系面板。A19 要先有 versionId、A20 只给反向，
 * 因此列表页的「关系」入口直接用 A21 一次取全双向，不必先切到版本页。
 */
const relationsOpen = ref(false)
const relationsLoading = ref(false)
const relationsSkill = ref<SkillAdminListItem | null>(null)
const relationsView = ref<SkillRelationsView | null>(null)
const relationsError = ref('')

async function openRelations(item: SkillAdminListItem) {
  relationsSkill.value = item
  relationsView.value = null
  relationsError.value = ''
  relationsOpen.value = true
  relationsLoading.value = true
  try {
    relationsView.value = await fetchSkillRelations(item.id)
  } catch (error) {
    relationsError.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    relationsLoading.value = false
  }
}

function closeRelations() {
  relationsOpen.value = false
  relationsSkill.value = null
  relationsView.value = null
  relationsError.value = ''
}

/**
 * 类型标签：只用后端的 `kind`，缺失（老数据/老后端）时返回空串，
 * 这样模板用 `v-if` 就能不渲染标签，不会凭空造一个「技能」。
 */
function kindCode(item: { kind?: SkillKind | null }) {
  const kind = item.kind ?? ''
  return KIND_VALUES.includes(kind as SkillKind) ? kind : ''
}

function kindLabel(kind: SkillKind | string) {
  return KIND_LABELS[kind as SkillKind] ?? String(kind)
}

function kindTagType(kind: SkillKind | string) {
  return KIND_TAG_TYPES[kind as SkillKind] ?? 'info'
}

function memberRoleLabel(role: SkillMemberRole) {
  return MEMBER_ROLE_LABELS[role] ?? role
}

/** 花名册是可编辑的：`LEAD` 最多一个，已占用时其他行的下拉要禁用该选项（E-04） */
function leadTakenByRow(index: number) {
  return roster.value.some((member, position) => position !== index && member.role === 'LEAD')
}

function memberAvatarUrl(key: string) {
  return memberAvatarUrls.value[key] ?? ''
}

/** 头像缺失时用中英名首个字符兜底占位 */
function memberInitial(member: { name?: string | null; nameEn?: string | null; memberKey: string }) {
  return (member.name || member.nameEn || member.memberKey || '?').trim().slice(0, 1).toUpperCase()
}

function formatBytes(bytes: number | null | undefined) {
  if (!bytes || bytes < 0) return '—'
  return bytesFormatter.format(bytes)
}

function humanizeBytes(bytes: number | null | undefined) {
  if (!bytes || bytes < 0) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function nativeClawhubPackageSize(bytes: number | null | undefined) {
  return bytes && bytes > 0 ? humanizeBytes(bytes) : '—'
}

function skillInitial(item: { name: string; title?: string | null }) {
  return (item.title || item.name || '?').trim().slice(0, 1).toUpperCase()
}

function createdDate(item: SkillAdminListItem) {
  const formatted = formatDateTime(item.createdAt)
  return formatted === '-' ? '-' : formatted.slice(0, 10)
}

/** 线上版本单元格：latest > published 时提示「有新版本待发布」 */
function versionCellText(item: SkillAdminListItem) {
  if (item.publishedVersion) return `v${item.publishedVersion}`
  if (item.latestVersion) return `v${item.latestVersion}`
  return '—'
}

function hasPendingRelease(item: SkillAdminListItem) {
  if (!item.latestVersion) return false
  if (!item.publishedVersion) return false
  return item.latestVersion !== item.publishedVersion
}

/* ---------------- 详情 ---------------- */

/**
 * 拉取可作为成员的已上架专家。只拉一次，抽屉反复开关不重复请求。
 *
 * `status: 'PUBLISHED'` 与后端 `requirePublishedExperts` 的校验口径一致，
 * 避免下拉里给出一个保存时必然被拒的选项。
 */
async function loadExpertOptions() {
  if (expertOptions.value.length) return
  try {
    const page = await listAdminSkills({
      page: 0,
      size: 200,
      query: '',
      status: 'PUBLISHED',
      category: '',
      kind: 'EXPERT',
      sort: '',
      direction: 'desc',
    })
    const items = (page as { items?: SkillAdminListItem[] }).items ?? []
    expertOptions.value = items.map((item) => ({ code: item.code, name: item.name }))
  } catch {
    // 选项拉不到不该阻塞抽屉：后端仍会校验，用户也可以手输 code。
    expertOptions.value = []
  }
}

async function loadNativeClawhubVersions(skillId: string) {
  const requestId = ++nativeClawhubVersionsRequest
  nativeClawhubVersionsLoading.value = true
  nativeClawhubVersionsError.value = ''
  try {
    const result = await listAdminClawhubVersions(skillId)
    if (!mounted || requestId !== nativeClawhubVersionsRequest || selectedId.value !== skillId) return
    nativeClawhubVersions.value = result.items ?? []
    nativeClawhubCurrentVersionId.value = result.currentPublishedVersionId ?? null
  } catch (error) {
    if (!mounted || requestId !== nativeClawhubVersionsRequest || selectedId.value !== skillId) return
    nativeClawhubVersions.value = []
    nativeClawhubCurrentVersionId.value = null
    nativeClawhubVersionsError.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    if (requestId === nativeClawhubVersionsRequest) nativeClawhubVersionsLoading.value = false
  }
}

async function openDrawer(item: SkillAdminListItem) {
  drawerOpen.value = true
  selectedId.value = item.id
  selected.value = null
  detailPackagePreparing.value = false
  detailPackageDragDepth.value = 0
  uploadProgress.value = null
  uploadStatus.value = 'idle'
  nativeClawhubVersions.value = []
  nativeClawhubCurrentVersionId.value = null
  nativeClawhubVersionsError.value = ''
  nativeClawhubVersionsRequest += 1
  resetRoster()
  drawerLoading.value = true
  try {
    const loaded = await getAdminSkill(item.id)
    if (!mounted || selectedId.value !== item.id) return
    selected.value = loaded
    applyMembers(loaded.kind === 'TEAM' ? loaded.members : [])
    if (loaded.sourceFormat === 'CLAWHUB_SKILL') {
      await loadNativeClawhubVersions(item.id)
    }
  } catch (error) {
    if (!mounted || selectedId.value !== item.id) return
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    if (mounted && selectedId.value === item.id) drawerLoading.value = false
  }
  if (selected.value?.kind === 'TEAM') {
    loadMemberAvatars(item.id)
    // 专家团成员只能来自已上架专家。
    void loadExpertOptions()
  }
  // 详情头部要显示图标；列表若已预取过就直接命中缓存，不会重复请求。
  void iconPreviews.load(item.id)
  // 介绍插画可选且列表不显示，只在这里按需拉取；没有就静默落到「未上传」。
  void illustrationPreviews.load(item.id)
}

async function reloadDetail() {
  if (!selectedId.value) return
  const skillId = selectedId.value
  try {
    const loaded = await getAdminSkill(skillId)
    selected.value = loaded
    applyMembers(loaded.kind === 'TEAM' ? loaded.members : [])
    if (loaded.sourceFormat === 'CLAWHUB_SKILL') {
      await loadNativeClawhubVersions(skillId)
    } else {
      nativeClawhubVersions.value = []
      nativeClawhubCurrentVersionId.value = null
      nativeClawhubVersionsError.value = ''
    }
    // 审核详情打开时同步版本信任字段，避免发布/替换后展示陈旧数据。
    const current = reviewVersion.value
    if (current) {
      reviewVersion.value = (loaded.versions ?? []).find((row) => row.id === current.id) ?? null
    }
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  }
}

/** Refresh either the open Expert drawer or the catalog after a portable import commit. */
async function onPortableImportCommitted(response?: { skillId?: string }) {
  const committedFromForm = formVisible.value
  if (committedFromForm) {
    formVisible.value = false
    formSkill.value = null
    if (selectedId.value && response?.skillId === selectedId.value) await reloadDetail()
  } else if (selectedId.value) {
    await reloadDetail()
  }
  await load(params.page, { silent: true })
}

function closeDrawer() {
  selectedId.value = ''
  selected.value = null
  detailPackagePreparing.value = false
  detailPackageDragDepth.value = 0
  nativeClawhubVersionsRequest += 1
  nativeClawhubVersions.value = []
  nativeClawhubCurrentVersionId.value = null
  nativeClawhubVersionsError.value = ''
  resetRoster()
}

/** 有未保存花名册时先二次确认，再真正关闭抽屉 */
async function handleDrawerClose(done: () => void) {
  if (rosterDirty.value) {
    const confirmed = await ElMessageBox.confirm(
      '花名册还有未保存的改动，关闭后这些改动会丢失。',
      '放弃未保存的花名册改动？',
      { type: 'warning', confirmButtonText: '放弃改动', cancelButtonText: '继续编辑' },
    ).then(() => true).catch(() => false)
    if (!confirmed) return
  }
  done()
}

/**
 * 把服务端花名册拷进本地草稿，并记录基线字符串。
 * 之后所有编辑只动草稿，点「保存花名册」才整表 PUT（D3）。
 */
function applyMembers(source: SkillMemberView[] | undefined) {
  members.value = Array.isArray(source) ? source : []
  roster.value = members.value.map((member) => ({
    memberKey: member.memberKey,
    name: member.name ?? '',
    nameEn: member.nameEn ?? '',
    profession: member.profession ?? '',
    professionEn: member.professionEn ?? '',
    role: member.role ?? 'MEMBER',
    sortOrder: typeof member.sortOrder === 'number' ? member.sortOrder : 0,
  }))
  rosterBaseline = JSON.stringify(roster.value)
}

/** 关抽屉/重开时释放头像 object URL，避免内存泄漏 */
function resetRoster() {
  releaseMemberAvatarUrls()
  members.value = []
  roster.value = []
  rosterBaseline = '[]'
}

function releaseMemberAvatarUrls() {
  for (const url of Object.values(memberAvatarUrls.value)) {
    if (typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url)
  }
  memberAvatarUrls.value = {}
}

/* ---------------- 花名册编辑（本地草稿） ---------------- */

function addMember() {
  memberKeySeq += 1
  roster.value.push({
    memberKey: '',
    name: '',
    nameEn: '',
    profession: '',
    professionEn: '',
    role: roster.value.some((member) => member.role === 'LEAD') ? 'MEMBER' : 'LEAD',
    sortOrder: roster.value.length,
  })
}

function removeMemberRow(index: number) {
  const [removed] = roster.value.splice(index, 1)
  if (removed) {
    const url = memberAvatarUrls.value[removed.memberKey]
    if (url && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url)
    delete memberAvatarUrls.value[removed.memberKey]
  }
  roster.value.forEach((member, position) => { member.sortOrder = position })
}

function moveMember(index: number, offset: number) {
  const target = index + offset
  if (target < 0 || target >= roster.value.length) return
  const next = [...roster.value]
  const [moved] = next.splice(index, 1)
  next.splice(target, 0, moved)
  next.forEach((member, position) => { member.sortOrder = position })
  roster.value = next
}

/**
 * 切换主理人：`LEAD` 全局最多一个（E-04），因此把角色设成 `LEAD` 时
 * 其余行自动降级为 `MEMBER`，而不是留给后端 422 `invalid_skill_member`。
 */
function onRoleChange(index: number) {
  const member = roster.value[index]
  if (!member || member.role !== 'LEAD') return
  roster.value.forEach((row, position) => {
    if (position !== index && row.role === 'LEAD') row.role = 'MEMBER'
  })
}

/** 手写校验：返回 '' 或中文错误文案（与技能表单同一套风格） */
function validateRoster(): string {
  const seen = new Set<string>()
  for (let index = 0; index < roster.value.length; index += 1) {
    const row = roster.value[index]
    const position = index + 1
    const key = (row.memberKey ?? '').trim()
    if (!MEMBER_KEY_PATTERN.test(key)) {
      return `第 ${position} 行的成员标识不符合规则：字母、数字、点、下划线与连字符，最长 64 位，且不能以符号开头。`
    }
    if (seen.has(key)) return `成员标识「${key}」重复了，花名册里的标识必须唯一。`
    seen.add(key)
    if ((row.name ?? '').trim().length > MEMBER_FIELD_MAX) return `第 ${position} 行的中文姓名最多 64 个字符。`
    if ((row.nameEn ?? '').trim().length > MEMBER_FIELD_MAX) return `第 ${position} 行的英文姓名最多 64 个字符。`
    if ((row.profession ?? '').trim().length > MEMBER_FIELD_MAX) return `第 ${position} 行的中文职业最多 64 个字符。`
    if ((row.professionEn ?? '').trim().length > MEMBER_FIELD_MAX) return `第 ${position} 行的英文职业最多 64 个字符。`
  }
  if (roster.value.filter((row) => row.role === 'LEAD').length > 1) {
    return scope.value.leadLimit
  }
  return ''
}

function rosterPayload(): SkillMemberPayload[] {
  return roster.value.map((row, index) => ({
    memberKey: (row.memberKey ?? '').trim(),
    name: (row.name ?? '').trim() || null,
    nameEn: (row.nameEn ?? '').trim() || null,
    profession: (row.profession ?? '').trim() || null,
    professionEn: (row.professionEn ?? '').trim() || null,
    role: row.role === 'LEAD' ? 'LEAD' : 'MEMBER',
    sortOrder: index,
  }))
}

/** 一次保存：整体替换 + 回读详情，保证界面顺序与服务端一致（V7） */
async function saveRoster() {
  if (selected.value?.kind !== 'TEAM' || membersSaving.value) return
  const validation = validateRoster()
  if (validation) {
    errorMessage.value = validation
    return
  }
  const skillId = selected.value.id
  membersSaving.value = true
  errorMessage.value = ''
  try {
    await replaceAdminSkillMembers(skillId, rosterPayload())
    await reloadDetail()
    await load(params.page, { silent: true })
    ElMessage.success('花名册已保存')
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    membersSaving.value = false
  }
}

/**
 * 成员头像不在这里上传：花名册成员是一个**专家包引用**，头像就是那个专家包自己的图标
 * （见 `SkillMemberViews` 返回的 `avatarUrl`）。要换头像就去改那个专家包。
 *
 * `avatarUrl` 需要带管理员 JWT，响应式 `<img src>` 拿不到，所以像技能图标一样
 * fetch 成 blob 再转 object URL；只拉一次并缓存在 `memberAvatarUrls` 里。
 */
async function loadMemberAvatar(skillId: string, memberKey: string) {
  if (!memberKey) return
  if (memberAvatarUrls.value[memberKey]) return
  try {
    const blob = await fetchSkillMemberAvatar(memberKey)
    if (!mounted) return
    memberAvatarUrls.value = {
      ...memberAvatarUrls.value,
      [memberKey]: URL.createObjectURL(blob),
    }
  } catch {
    // 头像是可选资源：拉取失败只回退到文字占位，不阻塞花名册
  }
}

function loadMemberAvatars(skillId: string) {
  for (const member of members.value) {
    // 后端在 `avatarUrl` 里告诉我们该成员有头像；没有就跳过，不发注定 404 的请求。
    if (!member.avatarUrl) continue
    void loadMemberAvatar(skillId, member.memberKey)
  }
}

/* ---------------- 客户端预览的花名册（只读） ---------------- */

const previewMemberAvatarUrls = ref<Record<string, string>>({})

async function loadPreviewMemberAvatars(source: SkillMemberSummary[]) {
  for (const member of source) {
    if (!member.avatarUrl) continue
    if (previewMemberAvatarUrls.value[member.memberKey]) continue
    try {
      const blob = await fetchSkillMemberAvatar(member.memberKey)
      if (!mounted) return
      previewMemberAvatarUrls.value = {
        ...previewMemberAvatarUrls.value,
        [member.memberKey]: URL.createObjectURL(blob),
      }
    } catch {
      // 预览头像失败不阻塞详情：回退到文字占位
    }
  }
}

function releasePreviewMemberAvatarUrls() {
  for (const url of Object.values(previewMemberAvatarUrls.value)) {
    if (typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url)
  }
  previewMemberAvatarUrls.value = {}
}

function previewMemberAvatarUrl(key: string) {
  return previewMemberAvatarUrls.value[key] ?? ''
}

/* ---------------- 上下架 / 删除 ---------------- */

/**
 * 删除二次确认里的关键词必须独占一行。nowrap + 横向滚动可避免长标识在连字符处断行，
 * 点击代码块会全选文本，随后可直接 Ctrl/Cmd+C。
 */
function deleteConfirmationMessage(description: string, keyword: string, instruction: string) {
  return h('div', { style: { width: '100%' } }, [
    h('p', { style: { margin: '0 0 12px', lineHeight: '1.65' } }, description),
    h('code', {
      title: '点击选中后可复制',
      tabindex: 0,
      style: {
        display: 'block',
        maxWidth: '100%',
        boxSizing: 'border-box',
        margin: '0 auto 12px',
        padding: '8px 12px',
        overflowX: 'auto',
        whiteSpace: 'nowrap',
        textAlign: 'center',
        color: 'var(--el-color-primary)',
        background: 'var(--el-fill-color-light)',
        border: '1px solid var(--el-border-color)',
        borderRadius: '6px',
        fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace',
        fontSize: '13px',
        lineHeight: '20px',
        userSelect: 'text',
        cursor: 'text',
      },
      onClick: (event: MouseEvent) => {
        const target = event.currentTarget
        if (!(target instanceof Node)) return
        const selection = window.getSelection()
        if (!selection) return
        const range = document.createRange()
        range.selectNodeContents(target)
        selection.removeAllRanges()
        selection.addRange(range)
      },
    }, keyword),
    h('p', { style: { margin: 0, lineHeight: '1.65' } }, instruction),
  ])
}

async function togglePublish(item: SkillAdminListItem) {
  if (publishingId.value || bulkBusy.value) return
  if (item.status === 'PUBLISHED') {
    const confirmed = await ElMessageBox.confirm(
      scope.value.unpublishConfirm,
      scope.value.unpublishTitle,
      { type: 'warning', confirmButtonText: '确认下架', cancelButtonText: '取消' },
    ).then(() => true).catch(() => false)
    if (!confirmed) return
    publishingId.value = item.id
    errorMessage.value = ''
    try {
      await unpublishAdminSkill(item.id)
      await load(params.page, { silent: true })
      ElMessage.success(scope.value.unpublished)
    } catch (error) {
      errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
    } finally {
      publishingId.value = ''
    }
    return
  }

  if (item.sourceFormat === 'CLAWHUB_SKILL') {
    publishingId.value = item.id
    errorMessage.value = ''
    try {
      const resolution = resolveNativeClawhubPublishTarget(await listAdminClawhubVersions(item.id))
      if (resolution.kind === 'choose') {
        await openDrawer(item)
        ElMessage.info('检测到多个原生 ClawHub 版本，请在详情列表中选择要上架的版本。')
        return
      }
      if (resolution.kind === 'invalid') {
        await openDrawer(item)
        errorMessage.value = skillAdminErrorMessage(nativeClawhubPublishError(resolution.code), adminCopyScope.value)
        return
      }
      await publishAdminClawhubSkill(item.id)
      if (selectedId.value === item.id) await reloadDetail()
      await load(params.page, { silent: true })
      ElMessage.success(scope.value.published)
    } catch (error) {
      errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
    } finally {
      publishingId.value = ''
    }
    return
  }
  if (needsClawhubSourceVersion(item)) {
    await promptClawhubSourceVersion(item.id)
    return
  }
  if (!await confirmNativeClawhubReviewPublish(item)) return

  publishingId.value = item.id
  errorMessage.value = ''
  try {
    await publishAdminSkillRecord(item)
    await load(params.page, { silent: true })
    ElMessage.success(scope.value.published)
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    publishingId.value = ''
  }
}

async function removeSkill(item: SkillAdminListItem) {
  if (item.status === 'PUBLISHED') return
  const result = await ElMessageBox.prompt(
    deleteConfirmationMessage(
      scope.value.deleteConfirm,
      item.code,
      `要确认，请在下方输入上方${scope.value.cnCode}。`,
    ),
    scope.value.deleteTitle,
    {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      inputPlaceholder: item.code,
      inputValidator: (value: string) => value === item.code || scope.value.codeMismatch,
    },
  ).catch(() => null)
  if (!result) return
  deletingId.value = item.id
  errorMessage.value = ''
  try {
    await deleteAdminSkill(item.id)
    await load(params.page, { silent: true })
    ElMessage.success(scope.value.deleted)
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    deletingId.value = ''
  }
}

type BulkActionName = Exclude<typeof bulkAction.value, ''>

/**
 * 批量动作继续使用既有单条接口，并逐条执行，避免一次向网关打出几十个并发请求。
 * 部分失败时已成功的不回滚；页面会保留失败项的勾选，方便管理员修正后重试。
 */
async function runBulkAction(
  actionName: BulkActionName,
  items: SkillAdminListItem[],
  action: (item: SkillAdminListItem) => Promise<unknown>,
  successVerb: string,
) {
  if (!items.length || bulkBusy.value) return false
  bulkAction.value = actionName
  errorMessage.value = ''
  const failed: Array<{ item: SkillAdminListItem; error: unknown }> = []
  let succeeded = 0
  try {
    for (const item of items) {
      try {
        await action(item)
        succeeded += 1
      } catch (error) {
        failed.push({ item, error })
      }
    }
    await load(params.page, { silent: true })
    selectedSkillIds.value = failed.map(({ item }) => item.id)
    if (failed.length) {
      const firstFailure = skillAdminErrorMessage(failed[0]?.error, adminCopyScope.value)
      errorMessage.value = `批量操作已完成 ${succeeded} 个，失败 ${failed.length} 个。${firstFailure}`
      return false
    }
    ElMessage.success(`已批量${successVerb} ${succeeded} 个${scope.value.cnNoun}`)
    return true
  } finally {
    bulkAction.value = ''
  }
}

async function bulkPublish() {
  if (bulkBusy.value || publishingId.value) return
  const items = bulkPublishItems.value
  if (!items.length) return
  const missingVersionItems = selectedSkills.value.filter(needsClawhubSourceVersion)
  const reviewPending = items.filter(item =>
    item.sourceFormat === 'CLAWHUB_SKILL' && item.sourceReviewRequired === true,
  )
  if (items.some((item) => item.sourceFormat !== 'CLAWHUB_SKILL')) {
    const confirmed = await ElMessageBox.confirm(
      `将所选 ${items.length} 个${scope.value.cnNoun}统一上架？${missingVersionItems.length ? `其中 ${missingVersionItems.length} 个 ClawHub 草稿待补源版本号：${missingVersionItems.map(item => item.code).join('、')}。` : ''}${reviewPending.length ? `其中 ${reviewPending.length} 个原生 ClawHub 技能需上架前核对：${reviewPending.map(item => item.code).join('、')}。` : ''}多个版本且未选过线上版本的项目需在详情中选择；没有可发布版本的项目会操作失败并保留勾选。`,
      `确认批量上架${scope.value.cnNoun}`,
      { type: 'warning', confirmButtonText: '批量上架', cancelButtonText: '取消' },
    ).then(() => true).catch(() => false)
    if (!confirmed) return
  }
  await runBulkAction('publish', items, async (item) => {
    if (item.sourceFormat === 'CLAWHUB_SKILL') {
      const resolution = resolveNativeClawhubPublishTarget(await listAdminClawhubVersions(item.id))
      if (resolution.kind !== 'ready') {
        throw nativeClawhubPublishError(
          resolution.kind === 'choose' ? 'native_clawhub_version_id_required' : resolution.code,
        )
      }
    }
    return publishAdminSkillRecord(item)
  }, '上架')
}

async function bulkUnpublish() {
  const items = bulkUnpublishItems.value
  if (!items.length) return
  const confirmed = await ElMessageBox.confirm(
    `将所选 ${items.length} 个${scope.value.cnNoun}统一下架？下架后桌面客户端将立即无法搜索与下载。`,
    `确认批量下架${scope.value.cnNoun}`,
    { type: 'warning', confirmButtonText: '批量下架', cancelButtonText: '取消' },
  ).then(() => true).catch(() => false)
  if (!confirmed) return
  await runBulkAction('unpublish', items, (item) => unpublishAdminSkill(item.id), '下架')
}

interface BulkWritableFileStream {
  write(data: Blob): Promise<void>
  close(): Promise<void>
}

interface BulkWritableFileHandle {
  createWritable(): Promise<BulkWritableFileStream>
}

interface BulkWritableDirectoryHandle {
  getDirectoryHandle(name: string, options: { create: true }): Promise<BulkWritableDirectoryHandle>
  getFileHandle(name: string, options: { create: true }): Promise<BulkWritableFileHandle>
}

type BulkDirectoryPicker = (options: { mode: 'readwrite' }) => Promise<BulkWritableDirectoryHandle>

async function writeBulkFile(directory: BulkWritableDirectoryHandle, name: string, blob: Blob) {
  const file = await directory.getFileHandle(name, { create: true })
  const writable = await file.createWritable()
  try {
    await writable.write(blob)
  } finally {
    await writable.close()
  }
}

function bulkFolderTimestamp(date = new Date()) {
  const two = (value: number) => String(value).padStart(2, '0')
  const three = (value: number) => String(value).padStart(3, '0')
  return `${date.getFullYear()}${two(date.getMonth() + 1)}${two(date.getDate())}`
    + `-${two(date.getHours())}${two(date.getMinutes())}${two(date.getSeconds())}-${three(date.getMilliseconds())}`
}

async function bulkDownloadPackages(withAssets: boolean, includeClawhub = false) {
  const items = bulkDownloadItems.value
  const clawhubItems = includeClawhub ? bulkClawhubDownloadItems.value : []
  if ((!items.length && !clawhubItems.length) || bulkBusy.value) {
    if (selectedSkills.value.length) ElMessage.warning(scope.value.noDownloadableVersion)
    return
  }
  // Expert YCPS 1.1 始终是含图完整包；普通技能保留纯 YCPS 选项。
  const effectiveWithAssets = withAssets || isExpertScope.value
  const actionName: BulkActionName = includeClawhub
    ? 'download-all'
    : effectiveWithAssets && !isExpertScope.value ? 'download-bundle' : 'download'
  bulkAction.value = actionName
  errorMessage.value = ''
  const picker = (window as Window & { showDirectoryPicker?: BulkDirectoryPicker }).showDirectoryPicker
  let destination: BulkWritableDirectoryHandle | null = null
  if (typeof picker === 'function') {
    try {
      // 必须在点击事件的用户手势中先请求目录权限，然后才能开始异步拉包。
      destination = await picker.call(window, { mode: 'readwrite' })
    } catch (error) {
      bulkAction.value = ''
      if (error instanceof DOMException && error.name === 'AbortError') return
      errorMessage.value = '未能打开保存文件夹，请重试。'
      return
    }
  }

  const failed: Array<{ item: SkillAdminListItem; error: unknown }> = []
  const failedIcons: Array<{ item: SkillAdminListItem; error: unknown }> = []
  let succeeded = 0
  try {
    const scopePrefix: Record<SkillKind, string> = { SKILL: 'skills', EXPERT: 'experts', TEAM: 'teams' }
    const prefix = scopePrefix[scopeKinds.value[0] ?? 'SKILL']
    const flavor = effectiveWithAssets ? 'with-assets' : 'ycps'
    const folderName = `${prefix}-${includeClawhub ? 'all' : flavor}-${bulkFolderTimestamp()}`
    const batchDirectory = destination
      ? await destination.getDirectoryHandle(folderName, { create: true })
      : null

    for (const item of items) {
      try {
        if (effectiveWithAssets) {
          if (item.kind === 'EXPERT') {
            // A complete Expert import is a YCPS 1.1 artifact, not the legacy wrapper ZIP.
            // The list endpoint only carries the online version string, so resolve the exact
            // version first; generation is idempotent and produces a stable successor.
            const detail = await getAdminSkill(item.id)
            const online = detail.versions?.find(isOnlineVersion)
            if (!online) throw new Error('skill_package_not_available')
            const portable = await generateAdminSkillPortableRevision(item.id, online.id)
            const result = await downloadAdminSkillPackage(item.id, portable.id)
            const fileName = packageFileName(result.fileName, item.code, portable.version)
            if (batchDirectory) {
              const itemDirectory = await batchDirectory.getDirectoryHandle(
                `${item.code}-${portable.version}`,
                { create: true },
              )
              await writeBulkFile(itemDirectory, fileName, result.blob)
            } else {
              downloadSkillPackageBlob(fileName, result.blob)
            }
          } else {
            const result = await downloadAdminSkillPackageBundle(item.id)
            const bundleName = packageBundleFileName(result.fileName, item.code, item.publishedVersion ?? '0')
            if (batchDirectory) {
              const prepared = await preparePackageArchive(new File([result.blob], bundleName, {
                type: 'application/zip',
              }))
              if (!prepared.completeBundle) throw new Error('bundle_structure_invalid')
              const itemDirectory = await batchDirectory.getDirectoryHandle(
                `${item.code}-${item.publishedVersion ?? '0'}`,
                { create: true },
              )
              await writeBulkFile(itemDirectory, prepared.packageFile.name, prepared.packageFile)
              for (const image of Object.values(prepared.images)) {
                if (image) await writeBulkFile(itemDirectory, image.name, image)
              }
            } else {
              // Safari / Firefox / 手机浏览器无法让网页直接创建本地目录：
              // 保留“每个项目一个文件”，下载后解压即可得到包与图片。
              downloadSkillPackageBlob(bundleName, result.blob)
            }
          }
        } else {
          const result = await downloadAdminSkillPackage(item.id)
          const fileName = packageFileName(result.fileName, item.code, item.publishedVersion ?? '0')
          if (batchDirectory) await writeBulkFile(batchDirectory, fileName, result.blob)
          else downloadSkillPackageBlob(fileName, result.blob)
        }
        succeeded += 1
      } catch (error) {
        failed.push({ item, error })
      }
    }

    // 混选时复用上方已获得的目录权限和批次目录，ClawHub 草稿也可导出原件。
    for (const item of clawhubItems) {
      try {
        const iconFailure = await writeBulkClawhubPackage(item, batchDirectory)
        succeeded += 1
        if (iconFailure) failedIcons.push({ item, error: iconFailure.error })
      } catch (error) {
        failed.push({ item, error })
      }
    }

    const skipped = selectedSkills.value.filter((item) =>
      item.sourceFormat !== 'CLAWHUB_SKILL' && !item.publishedVersion,
    ).length
    if (failed.length || failedIcons.length) {
      const firstFailure = skillAdminErrorMessage(failed[0]?.error, adminCopyScope.value)
      const iconFailure = failedIcons.length
        ? `，另有 ${failedIcons.length} 个 ClawHub 图标下载失败：${skillAdminErrorMessage(failedIcons[0].error, adminCopyScope.value)}`
        : ''
      errorMessage.value = `批量下载已写入 ${succeeded} 个资源包，包失败 ${failed.length} 个${iconFailure}。${failed.length ? firstFailure : ''}`
    }
    if (succeeded) {
      const skippedText = skipped > 0 ? `，跳过 ${skipped} 个无线上版本项` : ''
      if (failedIcons.length) {
        ElMessage.warning(`已导出 ${succeeded} 个资源包，其中 ${failedIcons.length} 个 ClawHub 图标下载失败`)
      } else if (batchDirectory) {
        ElMessage.success(`已写入文件夹“${folderName}”，共 ${succeeded} 个资源包${skippedText}`)
      } else {
        ElMessage.success(`已按项目分别下载 ${succeeded} 个资源包${skippedText}`)
      }
    }
  } catch (error) {
    errorMessage.value = `批量下载写入失败。${skillAdminErrorMessage(error, adminCopyScope.value)}`
  } finally {
    bulkAction.value = ''
  }
}

function clawhubPackageFileName(fileName: string, code: string, version: string | null | undefined) {
  const suggested = fileName.split(/[\\/]/u).at(-1)?.trim() ?? ''
  const safeSuggested = suggested.replace(/[<>:"|?*\u0000-\u001f]/gu, '_')
  if (safeSuggested.toLowerCase().endsWith('.zip')) return safeSuggested
  const safeCode = code.replace(/[^a-z0-9._-]/giu, '-') || 'clawhub-skill'
  return `clawhub-${safeCode}-${version || 'source'}.zip`
}

async function writeBulkClawhubPackage(
  item: SkillAdminListItem,
  batchDirectory: BulkWritableDirectoryHandle | null,
): Promise<{ error: unknown } | null> {
  const result = await downloadAdminClawhubSkillPackage(item.id)
  const fileName = clawhubPackageFileName(result.fileName, item.code, item.sourceVersion)
  if (batchDirectory) await writeBulkFile(batchDirectory, fileName, result.blob)
  else downloadSkillPackageBlob(fileName, result.blob)
  if (!item.iconUrl) return null
  try {
    const icon = await fetchAdminSkillIcon(item.id)
    const iconFileName = clawhubPackageIconFileName(fileName, icon)
    if (batchDirectory) await writeBulkFile(batchDirectory, iconFileName, icon)
    else downloadSkillImageBlob(iconFileName, icon)
    return null
  } catch (error) {
    return { error }
  }
}

async function chooseBulkDownload(mode: BulkDownloadMode) {
  bulkDownloadMenuOpen.value = false
  if (bulkBusy.value) return
  if (mode === 'clawhub') {
    await bulkDownloadClawhubPackages()
  } else {
    await bulkDownloadPackages(mode !== 'ycps', mode === 'all')
  }
}

/** Bulk native-source export has its own filter and endpoint; it never calls YCPS package APIs. */
async function bulkDownloadClawhubPackages() {
  const items = bulkClawhubDownloadItems.value
  if (!items.length || bulkBusy.value) return
  bulkAction.value = 'download-clawhub'
  errorMessage.value = ''
  const picker = (window as Window & { showDirectoryPicker?: BulkDirectoryPicker }).showDirectoryPicker
  let destination: BulkWritableDirectoryHandle | null = null
  if (typeof picker === 'function') {
    try {
      destination = await picker.call(window, { mode: 'readwrite' })
    } catch (error) {
      bulkAction.value = ''
      if (error instanceof DOMException && error.name === 'AbortError') return
      errorMessage.value = '未能打开保存文件夹，请重试。'
      return
    }
  }

  const failed: Array<{ item: SkillAdminListItem; error: unknown }> = []
  const failedIcons: Array<{ item: SkillAdminListItem; error: unknown }> = []
  let succeeded = 0
  const folderName = `skills-clawhub-${bulkFolderTimestamp()}`
  try {
    const batchDirectory = destination
      ? await destination.getDirectoryHandle(folderName, { create: true })
      : null
    for (const item of items) {
      try {
        const iconFailure = await writeBulkClawhubPackage(item, batchDirectory)
        succeeded += 1
        if (iconFailure) failedIcons.push({ item, error: iconFailure.error })
      } catch (error) {
        failed.push({ item, error })
      }
    }
    if (failed.length || failedIcons.length) {
      const packageFailure = failed.length
        ? `，ZIP 失败 ${failed.length} 个：${skillAdminErrorMessage(failed[0].error, adminCopyScope.value)}`
        : ''
      const iconFailure = failedIcons.length
        ? `，图标失败 ${failedIcons.length} 个：${skillAdminErrorMessage(failedIcons[0].error, adminCopyScope.value)}`
        : ''
      errorMessage.value = `ClawHub 原始 ZIP 导出成功 ${succeeded} 个${packageFailure}${iconFailure}。`
    }
    if (succeeded) {
      const message = batchDirectory
        ? `已写入“${folderName}”，共 ${succeeded} 个 ClawHub 原始 ZIP`
        : `已分别下载 ${succeeded} 个 ClawHub 原始 ZIP`
      if (failedIcons.length) ElMessage.warning(`${message}，其中 ${failedIcons.length} 个图标下载失败`)
      else ElMessage.success(message)
    }
  } catch (error) {
    errorMessage.value = `ClawHub 原始 ZIP 导出失败。${skillAdminErrorMessage(error, adminCopyScope.value)}`
  } finally {
    bulkAction.value = ''
  }
}

async function bulkChangeCategory() {
  const items = selectedSkills.value
  if (!items.length || !bulkCategory.value) return
  const category = bulkCategory.value === BULK_UNCATEGORIZED ? null : bulkCategory.value.trim()
  const categoryLabel = category || '未分类'
  const confirmed = await ElMessageBox.confirm(
    `将所选 ${items.length} 个${scope.value.cnNoun}统一切换到“${categoryLabel}”？`,
    `确认批量切换${scope.value.cnNoun}分类`,
    { type: 'warning', confirmButtonText: '确认切换', cancelButtonText: '取消' },
  ).then(() => true).catch(() => false)
  if (!confirmed) return

  const completed = await runBulkAction('category', items, async (item) => {
    // 列表不包含 description / sortWeight，先读详情才能在 PUT 时完整保留其他字段。
    const detail = await getAdminSkill(item.id)
    return updateAdminSkill(item.id, {
      name: detail.name,
      category,
      tags: detail.tags,
      summary: detail.summary,
      description: detail.description,
      icon: detail.icon,
      minClientVersion: detail.minClientVersion,
      sortWeight: detail.sortWeight,
      kind: detail.kind,
      nameEn: detail.nameEn,
      summaryEn: detail.summaryEn,
      descriptionEn: detail.descriptionEn,
      rowVersion: detail.rowVersion,
    })
  }, '切换分类')
  if (completed) bulkCategory.value = ''
}

async function bulkDelete() {
  const items = selectedSkills.value
  if (!canBulkDelete.value || !items.length) return
  const result = await ElMessageBox.prompt(
    deleteConfirmationMessage(
      `所选 ${items.length} 个已下架${scope.value.cnNoun}将移入回收站并立即从桌面端隐藏，30 天内可恢复。`,
      BULK_DELETE_PHRASE,
      '要确认，请在下方输入上方确认短语。',
    ),
    `确认批量删除${scope.value.cnNoun}`,
    {
      type: 'warning',
      confirmButtonText: '确认批量删除',
      cancelButtonText: '取消',
      inputPlaceholder: BULK_DELETE_PHRASE,
      inputValidator: (value: string) => value === BULK_DELETE_PHRASE || `请输入“${BULK_DELETE_PHRASE}”`,
    },
  ).catch(() => null)
  if (!result) return
  await runBulkAction('delete', items, (item) => deleteAdminSkill(item.id), '移入回收站')
}

async function removeVersion(version: SkillVersionView) {
  if (!selected.value) return
  if (version.online || version.status === 'PUBLISHED') {
    errorMessage.value = scope.value.onlineVersionDeleteErr
    return
  }
  const confirmed = await ElMessageBox.confirm(
    `确认删除版本 v${version.version}（revision ${version.revision}）？删除后不可恢复。`,
    '确认删除版本',
    { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
  ).then(() => true).catch(() => false)
  if (!confirmed) return
  versionActionId.value = version.id
  errorMessage.value = ''
  try {
    await deleteSkillVersion(selected.value.id, version.id)
    await reloadDetail()
    await load(params.page, { silent: true })
    ElMessage.success('版本已删除')
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    versionActionId.value = ''
  }
}

async function publishVersion(version: SkillVersionView) {
  if (!selected.value) return
  if (needsClawhubSourceVersion(selected.value)) {
    await promptClawhubSourceVersion(selected.value.id)
    return
  }
  const current = publishedVersion.value
  const impact = current && current.id !== version.id
    ? `当前线上 ${current.version} 将转为历史版本。`
    : ''
  const versionDetails = `设为线上版本后，客户端将在下次检查更新时下载 ${version.version}（约 ${humanizeBytes(version.sizeBytes)}）。${impact}`
  const reviewPending = selected.value.sourceFormat === 'CLAWHUB_SKILL'
    && selected.value.sourceReviewRequired === true
  const confirmed = reviewPending
    ? await confirmNativeClawhubReviewPublish(selected.value, versionDetails)
    : await ElMessageBox.confirm(
        versionDetails,
        '确认设为线上版本',
        { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '取消' },
      ).then(() => true).catch(() => false)
  if (!confirmed) return
  versionActionId.value = version.id
  errorMessage.value = ''
  try {
    if (selected.value.sourceFormat === 'CLAWHUB_SKILL'
      || version.packageFormat === 'CLAWHUB_SKILL') {
      errorMessage.value = '请在原生 ClawHub 版本列表中选择要上架的来源包。'
      return
    }
    await publishSkillVersion(selected.value.id, version.id)
    await reloadDetail()
    await load(params.page, { silent: true })
    ElMessage.success(`v${version.version} 已设为线上版本`)
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    versionActionId.value = ''
  }
}

/* ---------------- 新增 / 编辑 ---------------- */

/**
 * 整包下载（A25）。
 *
 * 默认按钮下「当前线上版本」；版本行上的按钮下该历史版本。走管理端专用接口，
 * 不做客户端下载会话，因此不消耗下载配额、也不产生设备记录，但会写一条
 * `skill.package.download` 管理审计。
 */
/** 展开按钮的菜单项：选中后收起菜单再下载，避免菜单挡住进度提示。 */
function chooseDownload(withAssets: boolean) {
  downloadMenuOpen.value = false
  void downloadPackage(undefined, withAssets)
}

/**
 * 下载整包。Expert 总是先幂等生成/复用当前展示快照的 YCPS 1.1 revision，
 * 并把已上传的三类图片直接嵌入包内。普通 SKILL/TEAM 才使用 `withAssets`
 * 区分纯 YCPS 与旧外层含图 bundle。
 *
 * 两者由同一个展开按钮触发，只是走不同端点，所以共用这里的进度态与错误处理。
 */
async function downloadPackage(version?: SkillVersionView, withAssets = false) {
  if (!selected.value) return
  if (selected.value.sourceFormat === 'CLAWHUB_SKILL' || version?.packageFormat === 'CLAWHUB_SKILL') {
    ElMessage.warning('这是 ClawHub 原生 ZIP，请从 ClawHub 原生版本列表导出。')
    return
  }
  const target = version ?? publishedVersion.value
  if (!target) {
    ElMessage.warning(scope.value.noDownloadableVersion)
    return
  }
  downloadingVersionId.value = target.id
  errorMessage.value = ''
  try {
    // A current Expert download is always a generated/reused YCPS 1.1 portable
    // revision.  Unlike the historical base artifact, that file embeds the cloud
    // presentation and all available image assets under the Entry Skill assets/.
    const effectiveWithAssets = withAssets || selected.value.kind === 'EXPERT'
    if (effectiveWithAssets) {
      if (selected.value.kind === 'EXPERT') {
        // Expert presentation lives in the cloud profile.  Generate (or reuse) the immutable
        // YCPS 1.1 successor so the downloaded file itself is accepted by portable import and
        // carries the profile plus its validated image assets.
        const portable = await generateAdminSkillPortableRevision(selected.value.id, target.id)
        const result = await downloadAdminSkillPackage(selected.value.id, portable.id)
        downloadSkillPackageBlob(
          packageFileName(result.fileName, selected.value.code, portable.version),
          result.blob,
        )
        ElMessage.success(`已开始下载 v${portable.version} YCPS 1.1 专家完整可导入包`)
      } else {
        const result = await downloadAdminSkillPackageBundle(selected.value.id, target.id)
        downloadSkillPackageBlob(
          packageBundleFileName(result.fileName, selected.value.code, target.version),
          result.blob,
        )
        ElMessage.success(
          result.assetCount > 0
            ? `已开始下载 v${target.version} 含图完整可导入包（含 ${result.assetCount} 张图片）`
            : `已开始下载 v${target.version} 含图完整可导入包（该记录暂无图片）`,
        )
      }
    } else {
      const result = await downloadAdminSkillPackage(selected.value.id, target.id)
      downloadSkillPackageBlob(
        packageFileName(result.fileName, selected.value.code, target.version),
        result.blob,
      )
      ElMessage.success(`已开始下载 v${target.version} 完整包`)
    }
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    downloadingVersionId.value = ''
  }
}

function nativeClawhubVersionStatusLabel(version: NativeClawhubVersionView) {
  if (isCurrentOnlineNativeClawhubVersion(version)) return '当前线上'
  if (selected.value?.status !== 'PUBLISHED' && version.id === nativeClawhubCurrentVersionId.value) return '上次发布'
  if (version.status === 'PUBLISHED') return '历史已上架'
  if (version.status === 'DRAFT') return '草稿'
  return version.status
}

function isCurrentOnlineNativeClawhubVersion(version: NativeClawhubVersionView) {
  return selected.value?.status === 'PUBLISHED'
    && (nativeClawhubCurrentVersionId.value
      ? version.id === nativeClawhubCurrentVersionId.value
      : version.online)
}

function isNativeClawhubVersionReady(version: NativeClawhubVersionView) {
  return version.scanStatus === 'PASSED'
    && Boolean(version.version && STRICT_SEMVER_PATTERN.test(version.version))
}

function canPublishNativeClawhubVersion(version: NativeClawhubVersionView) {
  return !isCurrentOnlineNativeClawhubVersion(version)
    && isNativeClawhubVersionReady(version)
}

function canDeleteNativeClawhubVersion(version: NativeClawhubVersionView) {
  return version.status === 'DRAFT'
    && !version.online
    && version.id !== nativeClawhubCurrentVersionId.value
}

function openNativeClawhubVersionPicker() {
  if (detailPackageBusy.value) return
  nativeClawhubVersionInput.value?.click()
}

function openDetailPackageFolderPicker() {
  if (detailPackageBusy.value) return
  detailPackageFolderInput.value?.click()
}

async function onNativeClawhubVersionPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (files.length === 0 || detailPackageBusy.value || selected.value?.kind !== 'SKILL') return
  await prepareDetailPackage(skillSourceFromFileSelection(files))
}

async function onDetailPackageFolderPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (files.length === 0 || detailPackageBusy.value || selected.value?.kind !== 'SKILL') return
  await prepareDetailPackage(skillSourceFromFileSelection(files))
}

function onDetailPackageDragOver(event: DragEvent) {
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
}

function onDetailPackageDragEnter(event: DragEvent) {
  event.preventDefault()
  detailPackageDragDepth.value += 1
}

function onDetailPackageDragLeave() {
  detailPackageDragDepth.value = Math.max(0, detailPackageDragDepth.value - 1)
}

async function onDetailPackageDrop(event: DragEvent) {
  event.preventDefault()
  detailPackageDragDepth.value = 0
  if (!event.dataTransfer || detailPackageBusy.value) return
  // `skillSourceFromDrop` snapshots DataTransfer synchronously before the first await.
  await prepareDetailPackage(skillSourceFromDrop(event.dataTransfer))
}

async function prepareDetailPackage(preparation: Promise<PreparedSkillSourceInput>) {
  const skill = selected.value
  if (!skill || skill.kind !== 'SKILL' || detailPackageBusy.value) return
  const skillId = skill.id
  detailPackagePreparing.value = true
  errorMessage.value = ''
  try {
    const prepared = await preparation
    if (!mounted || selectedId.value !== skillId || selected.value?.id !== skillId) return
    if (prepared.native) {
      if (skill.sourceFormat !== 'CLAWHUB_SKILL') {
        throw new Error('检测到原生 ClawHub 技能包，但当前记录是 YCPS Skill；两种来源不能混用。')
      }
      await uploadNativeClawhubDetailPackage(prepared.file, prepared.native, prepared.source)
      return
    }
    if (skill.sourceFormat === 'CLAWHUB_SKILL') {
      throw new Error('检测到 YCPS 技能包，但当前记录是原生 ClawHub Skill；请选择同一来源的 ClawHub ZIP 或文件夹。')
    }
    await uploadYcpsDetailPackage(prepared.file, prepared.source)
  } catch (error) {
    if (mounted) errorMessage.value = packageImportErrorMessage(error)
  } finally {
    if (mounted) detailPackagePreparing.value = false
  }
}

async function uploadNativeClawhubDetailPackage(
  file: File,
  probe: NonNullable<PreparedSkillSourceInput['native']>,
  source: PreparedSkillSourceInput['source'],
) {
  const skill = selected.value
  const sourceIdentity = nativeClawhubVersions.value.find(version => version.owner && version.slug)
  if (!skill || skill.sourceFormat !== 'CLAWHUB_SKILL' || nativeClawhubVersionUploading.value) return
  if (!sourceIdentity) {
    throw new Error('未能读取当前 ClawHub 来源信息，请刷新详情后重试。')
  }
  if (file.size > MAX_CLAWHUB_SOURCE_BYTES) {
    throw new Error('ClawHub 原始 ZIP 超过 50 MB 上限，请缩小技能包后重新上传。')
  }

  nativeClawhubVersionUploading.value = true
  errorMessage.value = ''
  try {
    // Folder imports use a generated archive name. Do not let that synthetic name
    // become an identity hint when the source itself did not declare a slug.
    const hints = inferClawhubIdentity(
      probe,
      source === 'folder' && file.name === 'clawhub-folder.zip' ? '' : file.name,
    )
    if (hints.owner && hints.owner !== sourceIdentity.owner) {
      throw new Error(`此包的发布者是 ${hints.owner}，与当前来源 ${sourceIdentity.owner} 不一致。`)
    }
    if (hints.slug && hints.slug !== sourceIdentity.slug) {
      throw new Error(`此包的技能标识是 ${hints.slug}，与当前来源 ${sourceIdentity.slug} 不一致。`)
    }

    const versionPrompt = await ElMessageBox.prompt(
      `将上传 @${sourceIdentity.owner}/${sourceIdentity.slug} 的原始 ZIP。请确认包内版本号；如果包内没有有效 SemVer，需要在此填写。`,
      '上传原生 ClawHub 版本',
      {
        inputValue: hints.version && STRICT_SEMVER_PATTERN.test(hints.version) ? hints.version : '',
        inputPlaceholder: '例如 1.2.3 或 1.2.3-rc.1',
        inputPattern: STRICT_SEMVER_PATTERN,
        inputErrorMessage: '版本号必须是严格 SemVer，例如 1.2.3 或 1.2.3-rc.1。',
        confirmButtonText: '继续预检',
        cancelButtonText: '取消',
      },
    ).catch(() => null)
    if (!versionPrompt) return
    const version = versionPrompt.value.trim()
    if (!STRICT_SEMVER_PATTERN.test(version)) {
      errorMessage.value = '版本号必须是严格 SemVer，例如 1.2.3 或 1.2.3-rc.1。'
      return
    }

    const preview = await previewAdminClawhubSkill(
      file, sourceIdentity.owner, sourceIdentity.slug, version,
    )
    if (preview.report.status === 'BLOCKED') {
      errorMessage.value = `此 ClawHub 包未通过预检，不能上传：${preview.report.blockingReasons.join('；')}`
      return
    }
    const reviewText = preview.report.status === 'REVIEW_REQUIRED'
      ? '预检提示需上架前核对；上传后只保存为草稿，仍需管理员选择版本上架。'
      : '预检通过；上传后仍需安全扫描，上传不会自动上架。'
    const decisions = preview.report.decisions.length
      ? `\n\n预检说明：${preview.report.decisions.join('；')}`
      : ''
    const confirmed = await ElMessageBox.confirm(
      `确认上传 @${sourceIdentity.owner}/${sourceIdentity.slug} v${version}？\n${reviewText}${decisions}`,
      '确认上传 ClawHub 版本',
      { type: 'warning', confirmButtonText: '上传并扫描', cancelButtonText: '取消' },
    ).then(() => true).catch(() => false)
    if (!confirmed) return
    if (!mounted || selectedId.value !== skill.id || selected.value?.id !== skill.id) return

    await uploadAdminClawhubVersion(skill.id, {
      file,
      version,
      expectedSha256: preview.report.sourceSha256,
    })
    await reloadDetail()
    await load(params.page, { silent: true })
    ElMessage.success(`ClawHub v${version} 已上传；请在版本列表查看扫描结果并选择是否上架。`)
  } catch (error) {
    errorMessage.value = packageImportErrorMessage(error)
  } finally {
    nativeClawhubVersionUploading.value = false
  }
}

async function uploadYcpsDetailPackage(file: File, source: 'zip' | 'folder') {
  const skill = selected.value
  if (!skill || skill.sourceFormat === 'CLAWHUB_SKILL' || uploadingPackage.value) return
  const limit = packageLimitFor(skill.kind)
  if (file.size > limit) {
    throw new Error(`${scope.value.cnPackage}超出大小限制：当前类型上限 ${humanizeBytes(limit)}。`)
  }

  const inspected = await inspectAdminSkillPackage(file)
  if (!mounted || selectedId.value !== skill.id || selected.value?.id !== skill.id) return
  if (inspected.code !== skill.code || inspected.kind !== skill.kind) {
    throw new Error('包内标识或类型与当前技能不一致，请选择该技能的 YCPS 包。')
  }
  const existing = (skill.versions ?? []).find(version => version.version === inspected.version) ?? null
  if (existing?.sealed) {
    throw new Error(`v${inspected.version} 已封存，不能原地替换；请提升 SemVer 并导入新的 YCPS 包。`)
  }

  const action = existing
    ? `替换 v${inspected.version} 的现有资源包（当前 revision ${existing.revision}）`
    : `新增 v${inspected.version}`
  const confirmed = await ElMessageBox.confirm(
    `已识别 ${source === 'folder' ? 'YCPS 包目录' : 'YCPS ZIP'}：${inspected.code} v${inspected.version}。\n确认${action}？提交后会进行安全扫描，不会自动设为线上版本。`,
    '确认导入 YCPS Skill',
    {
      type: 'warning',
      confirmButtonText: existing ? '确认替换并扫描' : '上传并扫描',
      cancelButtonText: '取消',
    },
  ).then(() => true).catch(() => false)
  if (!confirmed) return
  if (!mounted || selectedId.value !== skill.id || selected.value?.id !== skill.id) return

  uploadController = new AbortController()
  pollCancelled = false
  uploadingPackage.value = true
  uploadStatus.value = 'uploading'
  uploadProgress.value = { phase: 'uploading', percent: 0 }
  errorMessage.value = ''
  const fields = {
    changelog: '',
    minClientVersion: inspected.minClientVersion?.trim() ?? '',
  }
  try {
    if (existing) {
      await replaceSkillPackageWithRetry(skill.id, existing.id, file, fields, {
        signal: uploadController.signal,
        onProgress: (progress) => { uploadProgress.value = progress },
      })
    } else {
      await uploadSkillVersionWithRetry(skill.id, file, {
        version: inspected.version,
        ...fields,
      }, {
        signal: uploadController.signal,
        onProgress: (progress) => { uploadProgress.value = progress },
      })
    }
    uploadProgress.value = { phase: 'scanning', percent: 100 }
    uploadStatus.value = 'scanning'
    const settled = await pollVersion(skill.id, inspected.version)
    if (pollCancelled) {
      uploadStatus.value = 'idle'
      return
    }
    if (settled && (settled.status === 'REJECTED' || settled.status === 'FAILED')) {
      uploadStatus.value = 'failed'
      errorMessage.value = settled.status === 'REJECTED' ? scope.value.scanRejected : scope.value.packageFailed
      return
    }
    if (!settled) return
    uploadStatus.value = 'done'
    await reloadDetail()
    await load(params.page, { silent: true })
    ElMessage.success(`${scope.value.cnPackage} v${inspected.version} 已就绪`)
  } catch (error) {
    if (uploadController?.signal.aborted) {
      uploadStatus.value = 'idle'
      errorMessage.value = '上传已取消。'
      return
    }
    uploadStatus.value = 'failed'
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    uploadingPackage.value = false
    uploadController = null
  }
}

async function downloadNativeClawhubArchive(version: NativeClawhubVersionView) {
  if (!selected.value || selected.value.sourceFormat !== 'CLAWHUB_SKILL') return
  const skill = selected.value
  downloadingVersionId.value = version.id
  errorMessage.value = ''
  try {
    const result = await downloadAdminClawhubVersion(skill.id, version.id)
    const fileName = clawhubPackageFileName(result.fileName, skill.code, version.version)
    downloadSkillPackageBlob(fileName, result.blob)
    if (skill.iconUrl) {
      try {
        const icon = await fetchAdminSkillIcon(skill.id)
        downloadSkillImageBlob(clawhubPackageIconFileName(fileName, icon), icon)
      } catch (error) {
        errorMessage.value = `ClawHub ZIP 已导出，但图标下载失败。${skillAdminErrorMessage(error, adminCopyScope.value)}`
        ElMessage.warning('ClawHub ZIP 已导出，但图标下载失败。')
        return
      }
    }
    ElMessage.success(`已开始导出原生 ClawHub v${version.version || '未标版本'} 完整包${skill.iconUrl ? '及同名图标' : ''}。`)
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    downloadingVersionId.value = ''
  }
}

async function publishNativeClawhubVersion(version: NativeClawhubVersionView) {
  if (!selected.value || selected.value.sourceFormat !== 'CLAWHUB_SKILL') return
  if (versionActionId.value) return
  if (!canPublishNativeClawhubVersion(version)) {
    ElMessage.warning('此版本需要有效 SemVer 且安全扫描通过后才能上架。')
    return
  }
  const useDefaultPublish = version.id === nativeClawhubCurrentVersionId.value
    || (!nativeClawhubCurrentVersionId.value && nativeClawhubVersions.value.length === 1)
  const current = nativeClawhubOnlineVersion.value
  const impact = current && current.id !== version.id
    ? `当前线上 v${current.version || '未标版本'} 将转为历史版本。`
    : ''
  const details = `设为线上后，桌面端下次同步可看到 v${version.version}。更新安装由用户自主决定；已安装的旧版本仍可继续使用。${impact}`
  const confirmed = useDefaultPublish
    || (version.reviewRequired
      ? await confirmNativeClawhubReviewPublish(
          { sourceFormat: 'CLAWHUB_SKILL', sourceReviewRequired: true }, details,
        )
      : await ElMessageBox.confirm(
          details,
          '确认设为线上版本',
          { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '取消' },
        ).then(() => true).catch(() => false))
  if (!confirmed) return
  versionActionId.value = version.id
  errorMessage.value = ''
  try {
    if (useDefaultPublish) await publishAdminClawhubSkill(selected.value.id)
    else await publishAdminClawhubVersion(selected.value.id, version.id)
    await reloadDetail()
    await load(params.page, { silent: true })
    ElMessage.success(`原生 ClawHub v${version.version} 已设为线上版本。`)
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    versionActionId.value = ''
  }
}

async function rescanNativeClawhubVersion(version: NativeClawhubVersionView) {
  if (!selected.value || selected.value.sourceFormat !== 'CLAWHUB_SKILL') return
  if (version.status !== 'DRAFT' || !['PENDING', 'FAILED'].includes(version.scanStatus)) return
  nativeClawhubVersionRescanningId.value = version.id
  errorMessage.value = ''
  try {
    const result = await rescanAdminClawhubVersion(selected.value.id, version.id)
    await loadNativeClawhubVersions(selected.value.id)
    await load(params.page, { silent: true })
    ElMessage[result.scanStatus === 'PASSED' ? 'success' : 'warning'](
      `原生 ClawHub v${version.version || '未标版本'} 扫描状态：${scanLabel(result.scanStatus)}。`,
    )
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    nativeClawhubVersionRescanningId.value = ''
  }
}

async function deleteNativeClawhubVersion(version: NativeClawhubVersionView) {
  if (!selected.value || selected.value.sourceFormat !== 'CLAWHUB_SKILL') return
  if (!canDeleteNativeClawhubVersion(version)) return
  const confirmed = await ElMessageBox.confirm(
    `确认删除原生 ClawHub v${version.version || '未标版本'}？删除后原始 ZIP 不可恢复。`,
    '确认删除 ClawHub 版本',
    { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
  ).then(() => true).catch(() => false)
  if (!confirmed) return
  versionActionId.value = version.id
  errorMessage.value = ''
  try {
    await deleteAdminClawhubVersion(selected.value.id, version.id)
    await reloadDetail()
    await load(params.page, { silent: true })
    ElMessage.success(`ClawHub v${version.version || '未标版本'} 已删除。`)
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    versionActionId.value = ''
  }
}

function resetForm() {
  invalidateRemoteImportFetch()
  remoteImportUrl.value = ''
  remoteImportError.value = ''
  invalidateExpertAssetRequests()
  form.code = ''
  form.name = ''
  form.nameEn = ''
  form.category = ''
  form.tags = []
  form.icon = ''
  form.summary = ''
  form.summaryEn = ''
  form.description = ''
  form.descriptionEn = ''
  form.usageGuide = ''
  form.usageGuideEn = ''
  form.taskTypes = []
  form.taskTypesEn = []
  form.examplePrompts = []
  form.examplePromptsEn = []
  form.examplePreview = null
  form.examplePreviewEn = null
  form.examplePreviewInput = ''
  form.examplePreviewInputEn = ''
  form.examplePreviewOutput = ''
  form.examplePreviewOutputEn = ''
  form.sortWeight = 0
  form.minClientVersion = '2026.6.0'
  form.rowVersion = 0
  form.kind = defaultFormKind()
  expertPresentationPending.value = false
  releaseIconPreview()
  iconFile.value = null
  iconPendingClear.value = false
  releaseIllustrationPreview()
  illustrationFile.value = null
  illustrationPendingClear.value = false
  releaseExampleImagePreview()
  exampleImageFile.value = null
  exampleImagePendingClear.value = false
  versionForm.version = ''
  versionForm.changelog = ''
  versionForm.minClientVersion = ''
  replaceTarget.value = null
  uploadProgress.value = null
  uploadStatus.value = 'idle'
  codeAvailability.value = null
  codeAvailabilityLoading.value = false
  pendingPackage.value = null
  pendingPackageLabel.value = ''
  pendingClawhub.value = null
  clawhubReport.value = null
  clawhubDraftBeforePreview = null
  importingPackage.value = false
  formDetailLoading.value = false
  if (codeAvailabilityTimer) {
    clearTimeout(codeAvailabilityTimer)
    codeAvailabilityTimer = null
  }
  captureExpertAdvancedBaseline()
}

function openCreate() {
  formMode.value = 'create'
  formSkill.value = null
  resetForm()
  formStep.value = 0
  formVisible.value = true
  syncExpertDraftFromForm()
}

async function generatePortableRevision(version: SkillVersionView) {
  if (!selected.value || selected.value.kind !== 'EXPERT') return
  const confirmed = await ElMessageBox.confirm(
    `将根据当前专家展示内容生成 v${version.version} 的 YCPS 1.1 READY 修订包，不会自动发布。`,
    '生成 YCPS 1.1 完整包',
    { type: 'info', confirmButtonText: '生成', cancelButtonText: '取消' },
  ).then(() => true).catch(() => false)
  if (!confirmed) return
  versionActionId.value = version.id
  errorMessage.value = ''
  try {
    await generateAdminSkillPortableRevision(selected.value.id, version.id)
    await reloadDetail()
    await load(params.page, { silent: true })
    ElMessage.success(`v${version.version} 的 YCPS 1.1 完整包已生成`)
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    versionActionId.value = ''
  }
}

/* ---------------- A27：从资源包导入 ---------------- */

/**
 * 把解析结果填进表单。
 *
 * 两条硬规则：
 * 1. `kind` 一律以包内声明为准 —— YCPS 的 kind 是包的身份，写错会在上传时被
 *    `requireUploadIdentity` 拒绝，让用户在这里改只会制造一次必然失败的提交。
 * 2. 后端已经把 summary/description 的扇出做完了（一句话简介同时写进两列），
 *    所以这里逐字段照抄，不重新拼装。
 */
function applyInspectedPackage(inspected: SkillPackageInspectView, source: 'zip' | 'folder') {
  const editing = formMode.value === 'edit'
  if (!editing) form.code = inspected.code
  // 中文名缺失时用 code 兜底：名称是创建接口的必填项，留空会直接卡在校验上，
  // 而包本身没提供中文标题时，code 是最不容易误导用户的占位值。
  // 编辑时只覆盖包明确提供的文案，缺失字段保留当前值。
  form.name = inspected.title?.trim() || (editing ? form.name : inspected.code)
  form.nameEn = inspected.titleEn?.trim() || (editing ? form.nameEn : '')
  form.summary = inspected.summary?.trim() || (editing ? form.summary : '')
  form.summaryEn = inspected.summaryEn?.trim() || (editing ? form.summaryEn : '')
  form.description = inspected.description?.trim() || (editing ? form.description : '')
  form.descriptionEn = inspected.descriptionEn?.trim() || (editing ? form.descriptionEn : '')
  if (inspected.minClientVersion) form.minClientVersion = inspected.minClientVersion
  // Ordinary Skill imports retain the long-standing keywords → operational-tags
  // convenience. Expert packages are different: their keywords are package data,
  // while `tags` belong to the operator and must never be imported or rewritten.
  if (inspected.kind !== 'EXPERT') {
    const merged = [...form.tags, ...inspected.keywords]
    form.tags = [...new Set(merged.map((tag) => tag.trim()).filter(Boolean))].slice(0, 16)
  }
  if (!editing && KIND_VALUES.includes(inspected.kind)) form.kind = inspected.kind

  if (inspected.kind === 'EXPERT') {
    const importedDraft = expertDraftFromAdmin({
      name: inspected.title?.trim() || inspected.code,
      nameEn: inspected.titleEn ?? '',
      summary: inspected.summary ?? '',
      summaryEn: inspected.summaryEn ?? '',
      description: inspected.description ?? inspected.summary ?? '',
      descriptionEn: inspected.descriptionEn ?? inspected.summaryEn ?? '',
      usageGuide: inspected.usageGuide,
      usageGuideEn: inspected.usageGuideEn,
      // YCPS keywords are operational tags.  Expert task types are a separate
      // package/profile field and must never be inferred from `form.tags`.
      taskTypes: inspected.taskTypes ?? [],
      taskTypesEn: inspected.taskTypesEn ?? [],
      tags: form.tags,
      examplePrompts: inspected.examplePrompts,
      examplePromptsEn: inspected.examplePromptsEn,
      examplePreview: inspected.examplePreview,
      examplePreviewEn: inspected.examplePreviewEn,
      examplePreviewInput: inspected.examplePreviewInput,
      examplePreviewInputEn: inspected.examplePreviewInputEn,
      examplePreviewOutput: inspected.examplePreviewOutput,
      examplePreviewOutputEn: inspected.examplePreviewOutputEn,
      canvas: inspected.canvas,
      exampleImageLocale: inspected.exampleImageLocale,
    } as Partial<SkillAdminDetail>)
    onExpertDraftUpdated(importedDraft)
    // Import values are the new staged baseline; subsequent edits will show as unsaved.
    expertDraftBaseline.value = createExpertWysiwygDraft(importedDraft)
    expertPresentationPending.value = [
      'usageGuide', 'usageGuideEn', 'taskTypes', 'taskTypesEn',
      'examplePrompts', 'examplePromptsEn', 'examplePreview', 'examplePreviewEn',
      'examplePreviewInput', 'examplePreviewInputEn', 'examplePreviewOutput', 'examplePreviewOutputEn',
      'canvas', 'exampleImageLocale',
    ].some(key => Object.prototype.hasOwnProperty.call(inspected, key))
  }

  // 编码占用情况后端已经在同一趟里答了，直接落到预检状态上，
  // 免得用户要等 400ms 防抖再问一次同一个问题。
  if (!editing) {
    codeAvailability.value = {
      code: inspected.code,
      available: inspected.codeAvailable,
      occupiedBy: inspected.occupiedBy ?? null,
    }
    codeAvailabilityLoading.value = false
    if (codeAvailabilityTimer) {
      clearTimeout(codeAvailabilityTimer)
      codeAvailabilityTimer = null
    }
  }
  pendingPackageLabel.value = source === 'folder' ? pendingPackageLabel.value : ''

  // 回到第一屏：编码与名称是最需要人工核对的字段，也正是导入最先填上的两个。
  goToFormStep(0)
}

/** 把完整包里的三类图片放进现有新增表单；最终仍由提交动作统一上传。 */
function stageImportedImages(images: PreparedPackageImport['images']) {
  const icon = images.icon
  if (icon) {
    const allowed = isExpertScope.value
      ? ['image/png', 'image/jpeg', 'image/webp']
      : ICON_MIME_TYPES
    const maxBytes = isExpertScope.value
      ? EXPERT_IMAGE_NORMALIZATION_LIMITS.icon.bytes
      : MAX_ICON_BYTES
    if (!allowed.includes(icon.type)) {
      throw new Error(isExpertScope.value
        ? '含图完整包中的头像只支持 PNG、JPEG 或 WebP。'
        : '含图完整包中的头像/图标格式不正确或超过 4 MB。')
    }
    if (icon.size > maxBytes) {
      throw new Error(isExpertScope.value
        ? `含图完整包中的头像不能超过 ${maxBytes / 1024} KiB。`
        : '含图完整包中的头像/图标格式不正确或超过 4 MB。')
    }
    releaseIconPreview()
    iconFile.value = icon
    iconPendingClear.value = false
    if (typeof URL.createObjectURL === 'function') {
      iconPreviewUrl.value = URL.createObjectURL(icon)
      iconObjectUrls.add(iconPreviewUrl.value)
    }
  }

  const illustration = images.illustration
  if (illustration && showIllustration.value) {
    const allowed = isExpertScope.value
      ? ['image/png', 'image/jpeg', 'image/webp']
      : ILLUSTRATION_MIME_TYPES
    const maxBytes = isExpertScope.value
      ? EXPERT_IMAGE_NORMALIZATION_LIMITS.illustration.bytes
      : MAX_ILLUSTRATION_BYTES
    if (!allowed.includes(illustration.type)) {
      throw new Error(isExpertScope.value
        ? '含图完整包中的介绍插画只支持 PNG、JPEG 或 WebP。'
        : '含图完整包中的介绍插画格式不正确或超过 4 MB。')
    }
    if (illustration.size > maxBytes) {
      throw new Error(isExpertScope.value
        ? `含图完整包中的介绍插画不能超过 ${maxBytes / 1024} KiB。`
        : '含图完整包中的介绍插画格式不正确或超过 4 MB。')
    }
    releaseIllustrationPreview()
    illustrationFile.value = illustration
    illustrationPendingClear.value = false
    if (typeof URL.createObjectURL === 'function') {
      illustrationPreviewUrl.value = URL.createObjectURL(illustration)
    }
  }

  const example = images.example
  if (example && showExampleImage.value) {
    const allowed = isExpertScope.value
      ? ['image/png', 'image/jpeg', 'image/webp']
      : EXAMPLE_IMAGE_MIME_TYPES
    const maxBytes = isExpertScope.value
      ? EXPERT_IMAGE_NORMALIZATION_LIMITS.exampleImage.bytes
      : MAX_EXAMPLE_IMAGE_BYTES
    if (!allowed.includes(example.type)) {
      throw new Error(isExpertScope.value
        ? '含图完整包中的示例图片只支持 PNG、JPEG 或 WebP。'
        : '含图完整包中的示例图片格式不正确或超过 4 MB。')
    }
    if (example.size > maxBytes) {
      throw new Error(isExpertScope.value
        ? `含图完整包中的示例图片不能超过 ${maxBytes / 1024} KiB。`
        : '含图完整包中的示例图片格式不正确或超过 4 MB。')
    }
    releaseExampleImagePreview()
    exampleImageFile.value = example
    exampleImagePendingClear.value = false
    if (typeof URL.createObjectURL === 'function') {
      exampleImagePreviewUrl.value = URL.createObjectURL(example)
    }
  }
  // Keep the imported text draft intact. The local object URLs are media-only
  // changes and must not erase the canonical taskTypes/preview data we just read.
  if (isExpertScope.value) {
    const next = createExpertWysiwygDraft(expertDraft.value)
    next.media.icon.url = iconPreviewUrl.value
    next.media.illustration.url = illustrationPreviewUrl.value
    next.media.exampleImage.url = exampleImagePreviewUrl.value
    expertDraft.value = next
    expertDraftBaseline.value = createExpertWysiwygDraft(next)
  }
}

function syncExpertDraftFromForm(source?: Partial<SkillAdminDetail | SkillAdminListItem>) {
  const merged = {
    ...(source ?? formSkill.value ?? {}),
    name: form.name,
    nameEn: form.nameEn,
    summary: form.summary,
    summaryEn: form.summaryEn,
    description: form.description,
    descriptionEn: form.descriptionEn,
    usageGuide: form.usageGuide,
    usageGuideEn: form.usageGuideEn,
    // Keep the two namespaces independent. `tags` are operational metadata;
    // `taskTypes` are the Expert's business capability list.
    tags: form.tags,
    ...(source?.tagsEn !== undefined ? { tagsEn: source.tagsEn } : {}),
    // Always pass the canonical arrays, including an intentional empty array.
    // This prevents the draft compatibility adapter from treating operational
    // tags as a missing taskTypes field.
    taskTypes: form.taskTypes,
    taskTypesEn: form.taskTypesEn,
    examplePrompts: form.examplePrompts,
    examplePromptsEn: form.examplePromptsEn,
    examplePreview: form.examplePreview,
    examplePreviewEn: form.examplePreviewEn,
    examplePreviewInput: form.examplePreviewInput,
    examplePreviewInputEn: form.examplePreviewInputEn,
    examplePreviewOutput: form.examplePreviewOutput,
    examplePreviewOutputEn: form.examplePreviewOutputEn,
    category: form.category,
    icon: form.icon,
    sortWeight: form.sortWeight,
    minClientVersion: form.minClientVersion,
  }
  const draft = expertDraftFromAdmin(merged as Partial<SkillAdminDetail>)
  // The API paths require JWT and cannot be used directly by browser <img>
  // elements. Authenticated blob URLs are applied after the preview fetch.
  draft.media.icon.url = iconPreviewUrl.value || (source?.id && source.iconUrl ? iconPreviews.iconUrlFor(source.id) : '')
  draft.media.illustration.url = illustrationPreviewUrl.value || (source?.id && source.illustrationUrl ? illustrationPreviews.imageUrlFor(source.id) : '')
  draft.media.exampleImage.url = exampleImagePreviewUrl.value || (source?.id && source.exampleImageUrl ? exampleImagePreviews.imageUrlFor(source.id) : '')
  expertDraft.value = draft
  expertDraftBaseline.value = createExpertWysiwygDraft(draft)
  captureExpertAdvancedBaseline()
}

function syncExpertDraftMediaFromCache(source: SkillAdminDetail): void {
  const id = source.id
  if (!id || !isExpertScope.value) return
  const next = createExpertWysiwygDraft(expertDraft.value)
  next.media.icon.url = iconPreviewUrl.value || (source.iconUrl ? iconPreviews.iconUrlFor(id) : '')
  next.media.illustration.url = illustrationPreviewUrl.value || (source.illustrationUrl ? illustrationPreviews.imageUrlFor(id) : '')
  next.media.exampleImage.url = exampleImagePreviewUrl.value || (source.exampleImageUrl ? exampleImagePreviews.imageUrlFor(id) : '')
  expertDraft.value = next
  // Loading an existing asset is presentation hydration, not an edit.
  expertDraftBaseline.value = createExpertWysiwygDraft(next)
}

/** Merge the dedicated profile response into the canvas without touching operational tags. */
function applyLoadedExpertPresentation(response: AdminExpertPresentationResponse) {
  const presentation = response.presentation
  const loaded = expertDraftFromAdmin({
    name: presentation.title,
    nameEn: presentation.titleEn,
    summary: presentation.summary,
    summaryEn: presentation.summaryEn,
    // Keep explicit null/empty values: the draft adapter uses presence semantics
    // and must not resurrect summary when an editor intentionally cleared description.
    description: presentation.description,
    descriptionEn: presentation.descriptionEn,
    usageGuide: presentation.usageGuide,
    usageGuideEn: presentation.usageGuideEn,
    taskTypes: presentation.taskTypes,
    taskTypesEn: presentation.taskTypesEn,
    examplePrompts: presentation.examplePrompts,
    examplePromptsEn: presentation.examplePromptsEn,
    preview: presentation.preview,
    previewEn: presentation.previewEn,
    canvas: presentation.canvas,
    exampleImageLocale: presentation.exampleImageLocale,
    // These are protected API paths, not browser-safe image sources. The
    // authenticated preview cache is applied after `openEdit` fetches blobs.
    iconUrl: '',
    illustrationUrl: '',
    exampleImageUrl: '',
    category: form.category,
    icon: form.icon,
    sortWeight: form.sortWeight,
    minClientVersion: form.minClientVersion,
  } as Partial<SkillAdminDetail>)
  expertDraft.value = loaded
  expertDraftBaseline.value = createExpertWysiwygDraft(loaded)
  expertPresentationPending.value = false
  applyExpertDraftToForm(loaded)
  form.rowVersion = presentation.rowVersion ?? form.rowVersion
  captureExpertAdvancedBaseline()
}

function applyExpertDraftToForm(draft: ExpertWysiwygDraft) {
  const fields = expertDraftToAdminFields(draft)
  form.name = draft.title.zh
  form.nameEn = draft.title.en
  form.description = fields.description ?? ''
  form.descriptionEn = fields.descriptionEn ?? ''
  form.summary = fields.summary ?? ''
  form.summaryEn = fields.summaryEn ?? ''
  form.usageGuide = fields.usageGuide ?? ''
  form.usageGuideEn = fields.usageGuideEn ?? ''
  form.taskTypes = draft.taskTypes.zh.map(item => item.trim()).filter(Boolean)
  form.taskTypesEn = draft.taskTypes.en.map(item => item.trim()).filter(Boolean)
  form.examplePrompts = fields.examplePrompts ?? []
  form.examplePromptsEn = fields.examplePromptsEn ?? []
  form.examplePreview = fields.examplePreview ?? null
  form.examplePreviewEn = fields.examplePreviewEn ?? null
  form.examplePreviewInput = fields.examplePreviewInput ?? ''
  form.examplePreviewInputEn = fields.examplePreviewInputEn ?? ''
  form.examplePreviewOutput = fields.examplePreviewOutput ?? ''
  form.examplePreviewOutputEn = fields.examplePreviewOutputEn ?? ''
}

function onExpertDraftUpdated(next: ExpertWysiwygDraft) {
  expertDraft.value = next
  applyExpertDraftToForm(next)
}

async function onExpertAssetPicked(role: ExpertImageRole, file: File) {
  const requestVersion = expertAssetRequestVersions[role] + 1
  expertAssetRequestVersions[role] = requestVersion
  let normalized: File
  try {
    // Normalize before assigning the draft asset. This gives the WYSIWYG canvas and
    // FormData the exact same cropped/compressed bytes, while the server repeats all checks.
    normalized = await normalizeExpertImage(file, role)
  } catch (error) {
    if (!mounted || expertAssetRequestVersions[role] !== requestVersion) return
    errorMessage.value = error instanceof Error ? error.message : '无法处理图片，请重新选择文件。'
    const restored = createExpertWysiwygDraft(expertDraft.value)
    restored.media[role] = createExpertWysiwygDraft(expertDraftBaseline.value).media[role]
    expertDraft.value = restored
    applyExpertDraftToForm(restored)
    return
  }
  // A second selection, clear action or dialog reset supersedes this async
  // normalization. Never let an older result replace the operator's latest image.
  if (!mounted || expertAssetRequestVersions[role] !== requestVersion) return
  const next = createExpertWysiwygDraft(expertDraft.value)
  const previousUrl = next.media[role].url
  if (expertAssetObjectUrls.has(previousUrl)) {
    expertAssetObjectUrls.delete(previousUrl)
    if (typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(previousUrl)
  }
  let normalizedUrl = next.media[role].url
  if (typeof URL.createObjectURL === 'function') {
    normalizedUrl = URL.createObjectURL(normalized)
    expertAssetObjectUrls.add(normalizedUrl)
  }
  next.media[role] = {
    url: normalizedUrl,
    fileName: normalized.name,
    pendingClear: false,
    ...(role === 'exampleImage' ? { locale: next.media[role].locale ?? 'zh' } : {}),
  }
  expertDraft.value = next
  applyExpertDraftToForm(next)
  if (role === 'icon') {
    releaseIconPreview()
    iconFile.value = normalized
    iconPendingClear.value = false
  } else if (role === 'illustration') {
    releaseIllustrationPreview()
    illustrationFile.value = normalized
    illustrationPendingClear.value = false
  } else {
    releaseExampleImagePreview()
    exampleImageFile.value = normalized
    exampleImagePendingClear.value = false
  }
}

function onExpertAssetCleared(role: ExpertImageRole) {
  invalidateExpertAssetRequests(role)
  // Clearing the canvas means the saved asset must be empty, including when
  // the operator selected a replacement before clearing it.
  if (role === 'icon') {
    markIconClear()
  } else if (role === 'illustration') {
    markIllustrationClear()
  } else {
    markExampleImageClear()
  }
}

function packageImportErrorMessage(error: unknown): string {
  // 本地完整包解析错误没有 HTTP response；接口错误仍走统一错误码映射，不能把
  // Axios 的英文 "Request failed with status code ..." 直接展示给管理员。
  if (error instanceof Error && !('response' in error) && error.message) return error.message
  return skillAdminErrorMessage(error, adminCopyScope.value)
}

/**
 * 检查已准备好的 YCPS 包并填入表单。完整包在进入这里前已拆成内层包与图片，
 * 因而后端仍只接收标准 `.ycps.zip`，不会改变原有摄入契约。
 */
async function inspectPreparedImport(
  prepared: PreparedPackageImport,
  source: 'zip' | 'folder',
) {
  if (formMode.value === 'edit' && formSkill.value?.sourceFormat === 'CLAWHUB_SKILL') {
    throw new Error('该记录是原生 ClawHub 技能，不能用 YCPS 包更新；请使用 ClawHub 原生 ZIP 管理入口。')
  }
  const file = prepared.packageFile
  const limit = packageLimitFor(form.kind)
  if (file.size > limit) {
    throw new Error(`${scope.value.cnPackage}超出大小限制：当前类型上限 ${humanizeBytes(limit)}。`)
  }
  const inspected = await inspectAdminSkillPackage(file)
  if (!mounted) return
  if (formMode.value === 'edit') {
    const current = formSkill.value
    if (!current || inspected.code !== current.code || inspected.kind !== current.kind) {
      throw new Error('包内标识或类型与当前技能不一致，请选择该技能的 YCPS 包。')
    }
    const existing = formVersions.value.find(version => version.version === inspected.version) ?? null
    if (existing?.sealed) {
      throw new Error(`v${inspected.version} 已封存，请导入版本号更高的 YCPS 包。`)
    }
    replaceTarget.value = existing
    versionForm.version = inspected.version
    versionForm.changelog = ''
    versionForm.minClientVersion = inspected.minClientVersion?.trim() ?? ''
  }
  applyInspectedPackage(inspected, source)
  pendingClawhub.value = null
  clawhubReport.value = null
  clawhubDraftBeforePreview = null
  // kind 要等解析后才准确；此时页面也已知道哪些图片区块可以接收完整包资源。
  stageImportedImages(prepared.images)
  pendingPackage.value = {
    file,
    version: inspected.version,
    source,
    completeBundle: prepared.completeBundle,
  }
  if (formMode.value === 'create' && !inspected.codeAvailable) {
    // 编码被占用时仍然填入表单（用户能看到包内容），但必须明确告知这次提交会失败。
    errorMessage.value = scope.value.importCodeTaken
  } else {
    ElMessage.success(prepared.completeBundle
      ? '已识别含图完整包，信息与图片已填入，请核对后提交'
      : scope.value.importDone)
  }
}

/** ZIP 入口：普通 YCPS 原样检查，含图完整包先在浏览器内安全拆包。 */
async function importFromFile(file: File, source: 'zip' | 'folder') {
  if (importingPackage.value || uploadLocked.value || formDetailLoading.value) return
  importingPackage.value = true
  errorMessage.value = ''
  try {
    const native = await probeNativeSkillArchive(file)
    if (native) {
      if (formMode.value === 'edit') {
        throw new Error('技能编辑请导入当前技能的 YCPS 包；ClawHub 原生包仅支持新建导入。')
      }
      throw new Error('检测到原生 ClawHub 技能包。请从「ClawHub 原生 ZIP」入口导入；当前入口只接收 YCPS 包。')
    }
    const prepared = await preparePackageArchive(file)
    await inspectPreparedImport(prepared, source)
  } catch (error) {
    if (mounted) errorMessage.value = packageImportErrorMessage(error)
  } finally {
    if (mounted) importingPackage.value = false
  }
}

function onImportZipPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = ''
  if (file) void importFromFile(file, 'zip')
}

async function resolveClawhubIdentity(
  source: Awaited<ReturnType<typeof probeNativeSkillArchive>>,
  archiveName: string,
): Promise<{ owner: string; slug: string; version?: string } | null> {
  const hints = inferClawhubIdentity(source, archiveName)
  if (hints.owner && hints.slug) {
    return { owner: hints.owner, slug: hints.slug, version: hints.version ?? undefined }
  }
  const prompt = await ElMessageBox.prompt(
    `${scope.value.importClawhubIdentity}；没有版本信息时可留空，记录会保持待审核草稿。`,
    scope.value.importPickClawhub,
    {
      inputValue: hints.owner && hints.slug
        ? `@${hints.owner}/${hints.slug}${hints.version ? `@${hints.version}` : ''}`
        : '',
      inputPattern: /^@[a-z0-9][a-z0-9._-]*\/[a-z0-9]+(?:-[a-z0-9]+)*(?:@[0-9A-Za-z.+-]+)?$/u,
      inputErrorMessage: scope.value.importClawhubIdentityInvalid,
    },
  ).catch(() => null)
  if (!prompt) return null
  const match = /^@([^/]+)\/([^@]+)(?:@(.+))?$/u.exec(prompt.value)
  if (!match) return null
  return { owner: match[1], slug: match[2], version: match[3] }
}

async function previewClawhubImport(
  file: File,
  source: Awaited<ReturnType<typeof probeNativeSkillArchive>>,
) {
  if (file.size > MAX_CLAWHUB_SOURCE_BYTES) {
    errorMessage.value = 'ClawHub 原始 ZIP 超过 50 MB 预检上限，请缩小技能文件夹后重新导入。'
    return
  }
  const identity = await resolveClawhubIdentity(source, file.name)
  if (!identity) return
  const displayName = await fetchClawhubDisplayName(identity.owner, identity.slug)
  const preview = await previewAdminClawhubSkill(
    file, identity.owner, identity.slug, identity.version, displayName,
  )
  applyClawhubPreview(file, identity, displayName, preview)
}

function applyClawhubPreview(
  file: File,
  identity: { owner: string; slug: string; version?: string | null },
  displayName: string | null,
  preview: Awaited<ReturnType<typeof previewAdminClawhubSkill>>,
  remote?: PendingClawhubImport['remote'],
) {
  const before = pendingClawhub.value && clawhubDraftBeforePreview
    ? clawhubDraftBeforePreview : {
    fields: JSON.parse(JSON.stringify(form)) as typeof form,
    package: pendingPackage.value,
    clawhub: pendingClawhub.value,
    label: pendingPackageLabel.value,
    report: clawhubReport.value,
    availability: codeAvailability.value,
    step: formStep.value,
  }
  const sourceOwner = preview.report.owner?.trim() || identity.owner
  pendingPackage.value = null
  pendingClawhub.value = {
    file,
    owner: sourceOwner,
    slug: identity.slug,
    version: identity.version ?? null,
    displayName,
    expectedSha256: preview.report.sourceSha256,
    report: preview.report,
    remote,
  }
  const skillName = preview.report.name?.trim() || displayName || identity.slug
  const description = preview.report.description?.trim() || ''
  const sourceLabel = remote?.sourceKind === 'GITHUB' ? 'GitHub' : 'ClawHub'
  form.name = skillName
  form.summary = (description.split(/\r?\n/u).find(line => line.trim())?.trim() || skillName).slice(0, 512)
  form.description = description.slice(0, descriptionMaxLength.value)
  const sourceTagOwner = remote?.sourceKind === 'GITHUB'
    ? sourceOwner.replace(/^github\./iu, '') : sourceOwner
  form.tags = sourceTagOwner.length > MAX_SKILL_TAG_LENGTH || sourceTagOwner.toLowerCase() === sourceLabel.toLowerCase()
    ? [sourceLabel]
    : [sourceLabel, sourceTagOwner]
  pendingPackageLabel.value = `${sourceLabel} · ${skillName}`
  clawhubReport.value = preview.report
  form.kind = 'SKILL'
  if (preview.report.code?.trim()) {
    form.code = preview.report.code
    scheduleCodeAvailabilityCheck()
  } else {
    if (codeAvailabilityTimer) clearTimeout(codeAvailabilityTimer)
    codeAvailabilityTimer = null
    codeAvailability.value = null
    codeAvailabilityLoading.value = false
  }
  goToFormStep(0)
  clawhubDraftBeforePreview = before
  errorMessage.value = preview.report.status === 'BLOCKED'
    ? `${scope.value.importClawhubBlocked} ${preview.report.blockingReasons.join('；')}`
    : ''
}

function cancelClawhubImport() {
  // Undo only this local preview. Preserve edits and any package that were
  // already in the create draft before the ClawHub file was selected.
  const before = clawhubDraftBeforePreview
  if (before) {
    Object.assign(form, before.fields)
    pendingPackage.value = before.package
    pendingClawhub.value = before.clawhub
    pendingPackageLabel.value = before.label
    clawhubReport.value = before.report
    codeAvailability.value = before.availability
    goToFormStep(before.step)
  } else {
    pendingPackage.value = null
    pendingClawhub.value = null
    pendingPackageLabel.value = ''
    clawhubReport.value = null
  }
  clawhubDraftBeforePreview = null
  errorMessage.value = ''
}

function invalidateRemoteImportFetch() {
  remoteImportGeneration += 1
  remoteImportController?.abort()
  remoteImportController = null
  if (remoteImportLoading.value) importingPackage.value = false
  remoteImportLoading.value = false
}

function onRemoteImportUrlChanged() {
  invalidateRemoteImportFetch()
  remoteImportError.value = ''
  if (pendingClawhub.value?.remote) cancelClawhubImport()
}

async function fetchRemoteImport() {
  if (!canImportClawhub.value || remoteImportLoading.value || importingPackage.value
    || submitting.value || formMode.value !== 'create') return
  const requestedUrl = remoteImportUrl.value.trim()
  try {
    if (new URL(requestedUrl).protocol !== 'https:') throw new Error('远程链接必须使用 HTTPS。')
  } catch {
    remoteImportError.value = '请输入有效的 ClawHub 或 GitHub HTTPS 链接。'
    return
  }
  if (pendingClawhub.value?.remote) cancelClawhubImport()
  const generation = ++remoteImportGeneration
  const controller = new AbortController()
  remoteImportController = controller
  remoteImportLoading.value = true
  importingPackage.value = true
  remoteImportError.value = ''
  errorMessage.value = ''
  const current = () => mounted && formVisible.value && generation === remoteImportGeneration
    && remoteImportUrl.value.trim() === requestedUrl
  try {
    const fetched = await fetchAdminRemoteClawhubSkill(requestedUrl, controller.signal)
    if (!current()) return
    if (fetched.file.size > MAX_CLAWHUB_SOURCE_BYTES) {
      throw new Error('远程技能包超过 50 MB 预检上限。')
    }
    const { metadata } = fetched
    const preview = await previewAdminClawhubSkill(
      fetched.file, metadata.owner, metadata.slug, metadata.version, metadata.displayName,
    )
    if (!current()) return
    if (!preview.report.sourceSha256
      || preview.report.sourceSha256.toLowerCase() !== fetched.sha256) {
      throw new Error('远程包在预检过程中发生变化，请重新拉取。')
    }
    applyClawhubPreview(
      fetched.file,
      { owner: metadata.owner, slug: metadata.slug, version: metadata.version },
      metadata.displayName,
      preview,
      {
        sourceKind: metadata.sourceKind,
        sourceUrl: metadata.sourceUrl,
        sourceRevision: metadata.sourceRevision,
        requestedUrl,
      },
    )
  } catch (error) {
    if (current()) remoteImportError.value = packageImportErrorMessage(error)
  } finally {
    if (generation === remoteImportGeneration) {
      remoteImportController = null
      remoteImportLoading.value = false
      importingPackage.value = false
    }
  }
}

async function onClawhubZipPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = ''
  if (!file || importingPackage.value) return
  importingPackage.value = true
  errorMessage.value = ''
  try {
    if (file.size > MAX_CLAWHUB_SOURCE_BYTES) {
      throw new Error('ClawHub 原始 ZIP 超过 50 MB 预检上限，请缩小技能文件夹后重新导入。')
    }
    const native = await probeNativeSkillArchive(file)
    if (!native) {
      throw new Error('这个 ZIP 不是可识别的 ClawHub 原生包。YCPS 包请使用 YCPS ZIP 入口。')
    }
    await previewClawhubImport(file, native)
  } catch (error) {
    if (mounted) errorMessage.value = packageImportErrorMessage(error)
  } finally {
    if (mounted) importingPackage.value = false
  }
}

async function onImportFolderPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const picked = Array.from(input.files ?? [])
  input.value = ''
  if (picked.length === 0 || uploadLocked.value || formDetailLoading.value) return
  importingPackage.value = true
  errorMessage.value = ''
  try {
    const rawEntries = picked.map(file => ({
      path: (file.webkitRelativePath || file.name).replace(/\\/gu, '/'), file,
    }))
    const native = await probeNativeSkillDirectory(rawEntries)
    if (native) {
      if (formMode.value === 'edit') {
        throw new Error('技能编辑请导入当前技能的 YCPS 包；ClawHub 原生包仅支持新建导入。')
      }
      throw new Error('检测到原生 ClawHub 技能目录。请从「ClawHub 原生目录」入口导入；当前入口只接收 YCPS 包。')
    }
    const entries = relativeEntriesFromFileList(picked)
    if (entries.length === 0) {
      errorMessage.value = scope.value.importFolderEmpty
      return
    }
    // 目录名只用于提示，剥掉最外层后的结构才是包内容
    pendingPackageLabel.value = (picked[0].webkitRelativePath || '').split('/')[0] || ''
    const completeBundle = preparePackageDirectory(entries)
    if (completeBundle) {
      await inspectPreparedImport(completeBundle, 'folder')
      return
    }
    const zipped = await buildZip(await readEntryBytes(entries))
    const container = pendingPackageLabel.value || 'package'
    const file = new File([zipped as BlobPart], `${container}.ycps.zip`, {
      type: 'application/zip',
    })
    await inspectPreparedImport({ packageFile: file, images: {}, completeBundle: false }, 'folder')
  } catch (error) {
    if (mounted) errorMessage.value = packageImportErrorMessage(error)
  } finally {
    if (mounted) importingPackage.value = false
  }
}

/* ---------------- A27：拖拽导入 ---------------- */

/**
 * 从桌面拖入的 zip 或文件夹。
 *
 * 三个必须做对的点：
 * 1. **`dataTransfer.items` 必须在 drop 回调里同步取出来** —— 一旦 `await` 过，
 *    items 会被浏览器清空，之后 `webkitGetAsEntry()` 全部返回 null。
 * 2. **必须 `preventDefault()`**，否则浏览器会直接把文件当导航打开，
 *    整个管理台页面被替换掉。
 * 3. 拖入一个目录时 `dataTransfer.files` 只给目录本身（读不到内容），
 *    只能靠 `webkitGetAsEntry()` 递归遍历。
 */
async function onImportDrop(event: DragEvent) {
  event.preventDefault()
  dragDepth.value = 0
  importMenuOpen.value = false
  if (importingPackage.value || uploadLocked.value || formDetailLoading.value) return
  const transfer = event.dataTransfer
  if (!transfer) return

  // 同步取出 items —— 见上面第 1 点
  const items = Array.from(transfer.items ?? [])
  errorMessage.value = ''

  // 单文件且是 zip：直接走 zip 链路，省掉目录遍历
  if (items.length === 1 && items[0].kind === 'file') {
    const entry = typeof items[0].webkitGetAsEntry === 'function' ? items[0].webkitGetAsEntry() : null
    const single = entry && !entry.isDirectory ? await readDroppedFile(entry) : items[0].getAsFile()
    if (single && /\.zip$/iu.test(single.name)) {
      await importDroppedFile(single)
      return
    }
  }

  importingPackage.value = true
  try {
    const dropped = await entriesFromDataTransfer(items)
    if (dropped.length === 0) {
      errorMessage.value = scope.value.importDropEmpty
      return
    }
    // 拖进来的是单个 zip（含在 items 里但没被上面的快捷路径命中）
    const onlyZip = dropped.length === 1 && /\.zip$/iu.test(dropped[0].path)
    if (onlyZip) {
      importingPackage.value = false
      await importDroppedFile(dropped[0].file)
      return
    }
    const native = await probeNativeSkillDirectory(dropped)
    if (native) {
      if (formMode.value === 'edit') {
        throw new Error('技能编辑请导入当前技能的 YCPS 包；ClawHub 原生包仅支持新建导入。')
      }
      if (dropped.reduce((total, entry) => total + entry.file.size, 0) > MAX_CLAWHUB_SOURCE_BYTES) {
        throw new Error('ClawHub 原生技能目录超过 50 MB 预检上限，请缩小技能文件夹后重新导入。')
      }
      const zipped = await buildZip(await readEntryBytes(dropped))
      const file = new File([zipped as BlobPart], 'clawhub-folder.zip', { type: 'application/zip' })
      await previewClawhubImport(file, native)
      return
    }
    // 其余当目录处理：先识别解压后的完整包，否则按 YCPS 源目录打包。
    const entries = stripContainerDirectory(dropped)
    if (entries.length === 0) {
      errorMessage.value = scope.value.importFolderEmpty
      return
    }
    pendingPackageLabel.value = dropped[0].path.split('/')[0] || ''
    const completeBundle = preparePackageDirectory(entries)
    if (completeBundle) {
      await inspectPreparedImport(completeBundle, 'folder')
      return
    }
    const zipped = await buildZip(await readEntryBytes(entries))
    const file = new File([zipped as BlobPart], `${pendingPackageLabel.value || 'package'}.ycps.zip`, {
      type: 'application/zip',
    })
    await inspectPreparedImport({ packageFile: file, images: {}, completeBundle: false }, 'folder')
  } catch (error) {
    if (mounted) errorMessage.value = packageImportErrorMessage(error)
  } finally {
    if (mounted) importingPackage.value = false
  }
}

function readDroppedFile(entry: FileSystemEntry): Promise<File | null> {
  return new Promise((resolve) => {
    ;(entry as FileSystemFileEntry).file(
      (file) => resolve(file),
      () => resolve(null),
    )
  })
}

/** dragover 必须 preventDefault，否则 drop 事件根本不会触发。 */
function onImportDragOver(event: DragEvent) {
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
}

function onImportDragEnter(event: DragEvent) {
  event.preventDefault()
  dragDepth.value += 1
}

function onImportDragLeave() {
  // 子元素会各自触发 dragleave，用递减而非直接归零，避免高亮闪烁
  dragDepth.value = Math.max(0, dragDepth.value - 1)
}

function toggleImportMenu() {
  if (importingPackage.value || uploadLocked.value || formDetailLoading.value) return
  importMenuOpen.value = !importMenuOpen.value
}

function pickImportZip() {
  if (uploadLocked.value || formDetailLoading.value) return
  importMenuOpen.value = false
  importZipInput.value?.click()
}

/** Drag/drop has no format selector, so route only after inspecting the archive structure. */
async function importDroppedFile(file: File) {
  const native = await probeNativeSkillArchive(file)
  if (!native) {
    await importFromFile(file, 'zip')
    return
  }
  if (formMode.value === 'edit') {
    errorMessage.value = '技能编辑请导入当前技能的 YCPS 包；ClawHub 原生包仅支持新建导入。'
    return
  }
  if (importingPackage.value || uploadLocked.value || formDetailLoading.value) return
  importingPackage.value = true
  errorMessage.value = ''
  try {
    await previewClawhubImport(file, native)
  } catch (error) {
    if (mounted) errorMessage.value = packageImportErrorMessage(error)
  } finally {
    if (mounted) importingPackage.value = false
  }
}

function pickImportFolder() {
  if (uploadLocked.value || formDetailLoading.value) return
  importMenuOpen.value = false
  importFolderInput.value?.click()
}

function pickImportClawhub() {
  if (!canImportClawhub.value || formMode.value !== 'create') return
  importMenuOpen.value = false
  importClawhubInput.value?.click()
}

function pickImportClawhubFolder() {
  if (!canImportClawhub.value || formMode.value !== 'create') return
  importMenuOpen.value = false
  importClawhubFolderInput.value?.click()
}

async function openEdit(item: SkillAdminListItem) {
  formMode.value = 'edit'
  formSkill.value = skillDetailPlaceholder(item)
  resetForm()
  formStep.value = 0
  form.code = item.code
  form.name = item.name
  form.nameEn = item.nameEn ?? ''
  form.kind = (item.kind ?? 'SKILL') as SkillKind
  form.category = item.category ?? ''
  form.tags = Array.isArray(item.tags) ? [...item.tags] : []
  const tagsAtRequest = [...form.tags]
  form.icon = item.icon ?? ''
  form.summary = item.summary ?? ''
  form.summaryEn = item.summaryEn ?? ''
  form.descriptionEn = item.descriptionEn ?? ''
  // Older list responses can omit the effective version; keep resetForm's required default.
  const listMinClientVersion = item.minClientVersion?.trim()
  form.minClientVersion = listMinClientVersion || form.minClientVersion
  const minClientVersionAtRequest = form.minClientVersion
  form.rowVersion = item.rowVersion
  formVisible.value = true
  formDetailLoading.value = true
  syncExpertDraftFromForm(item)
  try {
    const loaded = await getAdminSkill(item.id)
    if (!mounted || formSkill.value?.id !== item.id) return
    formSkill.value = loaded
    form.kind = (loaded.kind ?? form.kind) as SkillKind
    form.description = loaded.description ?? ''
    form.nameEn = loaded.nameEn ?? form.nameEn
    form.summaryEn = loaded.summaryEn ?? form.summaryEn
    form.descriptionEn = loaded.descriptionEn ?? form.descriptionEn
    // The list can be stale after an import or another edit. Load the saved
    // tags from detail unless the operator already changed them meanwhile.
    if (form.tags.length === tagsAtRequest.length
      && form.tags.every((tag, index) => tag === tagsAtRequest[index])) {
      form.tags = Array.isArray(loaded.tags) ? [...loaded.tags] : tagsAtRequest
    }
    const loadedMinClientVersion = loaded.minClientVersion?.trim() || minClientVersionAtRequest
    // Detail may have the effective value absent from the list. Preserve edits made while it loaded.
    if (form.minClientVersion === minClientVersionAtRequest) {
      form.minClientVersion = loadedMinClientVersion
    }
    form.usageGuide = loaded.usageGuide ?? ''
    form.usageGuideEn = loaded.usageGuideEn ?? ''
    // Missing taskTypes means the profile did not provide business capabilities;
    // it must not be synthesized from the independent operational `tags` field.
    form.taskTypes = Array.isArray(loaded.taskTypes) ? [...loaded.taskTypes] : []
    form.taskTypesEn = Array.isArray(loaded.taskTypesEn) ? [...loaded.taskTypesEn] : []
    form.examplePrompts = loaded.examplePrompts ? [...loaded.examplePrompts] : []
    form.examplePromptsEn = loaded.examplePromptsEn ? [...loaded.examplePromptsEn] : []
    form.examplePreview = loaded.examplePreview ?? null
    form.examplePreviewEn = loaded.examplePreviewEn ?? null
    form.examplePreviewInput = loaded.examplePreviewInput ?? ''
    form.examplePreviewInputEn = loaded.examplePreviewInputEn ?? ''
    form.examplePreviewOutput = loaded.examplePreviewOutput ?? ''
    form.examplePreviewOutputEn = loaded.examplePreviewOutputEn ?? ''
    form.sortWeight = loaded.sortWeight ?? 0
    form.rowVersion = loaded.rowVersion ?? item.rowVersion
    syncExpertDraftFromForm(loaded)
    if (form.kind === 'EXPERT') {
      try {
        const presentation = await fetchAdminExpertPresentation(loaded.id)
        if (!mounted || formSkill.value?.id !== item.id) return
        applyLoadedExpertPresentation(presentation)
      } catch (error) {
        const status = (error as { response?: { status?: number } })?.response?.status
        // Old gateways do not expose the dedicated profile yet; retain the flat
        // response fallback instead of turning a successful edit into an error.
        if (status !== 404 && status !== 405) throw error
      }
    }
    // Presentation loading can capture the Expert baseline; restore the persisted
    // version baseline before media requests, which may fail independently.
    captureExpertAdvancedBaseline(loadedMinClientVersion)
    // 已上传资源必须先通过带 JWT 的 preview fetch 取得 blob，再交给画布。
    // 不能把受保护的对象 URL直接交给 <img>：浏览器不会为它补 Authorization，
    // 会先产生一次 401 控制台错误，随后才由带凭证的缓存请求成功。
    if (!loaded.iconUrl) iconPreviews.forget(loaded.id)
    if (!loaded.illustrationUrl) illustrationPreviews.forget(loaded.id)
    if (!loaded.exampleImageUrl) exampleImagePreviews.forget(loaded.id)
    await Promise.all([
      loaded.iconUrl ? iconPreviews.load(loaded.id) : Promise.resolve(),
      loaded.illustrationUrl ? illustrationPreviews.load(loaded.id) : Promise.resolve(),
      loaded.exampleImageUrl ? exampleImagePreviews.load(loaded.id) : Promise.resolve(),
    ])
    if (!mounted || formSkill.value?.id !== item.id) return
    syncExpertDraftMediaFromCache(loaded)
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    if (formSkill.value?.id === item.id) formDetailLoading.value = false
  }
}

async function onClawhubFolderPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const picked = Array.from(input.files ?? [])
  input.value = ''
  if (picked.length === 0 || importingPackage.value || formMode.value !== 'create') return
  importingPackage.value = true
  errorMessage.value = ''
  try {
    const totalBytes = picked.reduce((total, file) => total + file.size, 0)
    if (totalBytes > MAX_CLAWHUB_SOURCE_BYTES) {
      throw new Error('ClawHub 原生技能目录超过 50 MB 预检上限，请缩小技能文件夹后重新导入。')
    }
    const entries = picked.map(file => ({
      path: (file.webkitRelativePath || file.name).replace(/\\/gu, '/'), file,
    }))
    const native = await probeNativeSkillDirectory(entries)
    if (!native) {
      throw new Error('这个目录不是可识别的 ClawHub 原生技能目录。YCPS 目录请使用 YCPS 包目录入口。')
    }
    const zipped = await buildZip(await readEntryBytes(entries))
    const file = new File([zipped as BlobPart], 'clawhub-folder.zip', { type: 'application/zip' })
    await previewClawhubImport(file, native)
  } catch (error) {
    if (mounted) errorMessage.value = packageImportErrorMessage(error)
  } finally {
    if (mounted) importingPackage.value = false
  }
}

/**
 * 手写校验：返回出错文案与它所在的向导步骤（仓库没有 el-form :rules / FormInstance.validate）。
 * 双语规则：中文（默认列）必填，英文选填，但长度上限与中文一致（名称 128 / 简介 512 / 说明 4000）。
 *
 * 带上步骤是为了让提交失败时能自动跳到出错那一屏 —— 否则用户在第三步点提交，
 * 报错却指向第一步的字段，还得自己找回去。
 */
function validateSkillForm(): { message: string; step: number } | null {
  // 第 1 步：基础信息（编码 / 中英文名称 / 分类 / 类型）
  if (!SKILL_CODE_PATTERN.test(form.code.trim())) {
    return { message: scope.value.codeRule, step: 0 }
  }
  if (!form.name.trim()) return { message: scope.value.nameRequired, step: 0 }
  if (form.name.trim().length > 128) return { message: scope.value.nameMax, step: 0 }
  if (form.nameEn.trim().length > 128) return { message: '英文名称最多 128 个字符。', step: 0 }
  if (form.category.trim().length > 32) return { message: '分类最多 32 个字符。', step: 0 }
  if (!KIND_VALUES.includes(form.kind)) {
    return { message: '请选择技能类型：技能、专家或专家团。', step: 0 }
  }
  // 第 2 步：展示与说明（标签 / 徽标 / 简介 / 详细说明）
  if (form.tags.length > 16) return { message: '最多 16 个标签。', step: 1 }
  if (form.tags.some((tag) => tag.length > MAX_SKILL_TAG_LENGTH)) {
    return { message: '单个标签最多 64 个字符。', step: 1 }
  }
  if (form.icon.length > 8) return { message: '徽标文本最多 8 个字符。', step: 1 }
  if (!form.summary.trim()) return { message: scope.value.summaryRequired, step: 1 }
  if (form.summary.length > 512) return { message: scope.value.summaryMax, step: 1 }
  if (form.summaryEn.trim().length > 512) return { message: '英文简介最多 512 个字符。', step: 1 }
  if (form.description.length > descriptionMaxLength.value) {
    return { message: `详细说明最多 ${descriptionMaxLength.value} 个字符。`, step: 1 }
  }
  if (form.descriptionEn.trim().length > descriptionMaxLength.value) {
    return { message: `英文详细说明最多 ${descriptionMaxLength.value} 个字符。`, step: 1 }
  }
  // The aggregate endpoint is a full-save contract.  Validate the complete Expert
  // presentation even when the operator only changed an advanced field; otherwise an
  // incomplete legacy profile would make a round trip to the server before surfacing the
  // same field error (and create mode could submit an untouched empty canvas).
  if (form.kind === 'EXPERT' && expertDraftErrors.value.length > 0) {
    return { message: expertDraftErrors.value[0].message, step: 1 }
  }
  // 第 3 步：客户端适配
  if (!MIN_CLIENT_VERSION_PATTERN.test(form.minClientVersion.trim())) {
    return { message: '最低客户端版本格式应形如 2026.6.0。', step: 2 }
  }
  return null
}

function skillPayload(): SkillAdminPayload {
  return {
    name: form.name.trim(),
    // 已知分类归一到受控词表的中文名；未知分类原样保留，空值给 null（不臆造分类）。
    category: normalizeExpertCategoryInput(form.category),
    tags: form.tags.map((tag) => tag.trim()).filter(Boolean),
    summary: form.summary.trim() || null,
    description: form.description.trim() || null,
    icon: form.icon.trim() || null,
    minClientVersion: form.minClientVersion.trim() || null,
    sortWeight: Number.isFinite(form.sortWeight) ? form.sortWeight : 0,
    kind: form.kind,
    nameEn: form.nameEn.trim() || null,
    summaryEn: form.summaryEn.trim() || null,
    descriptionEn: form.descriptionEn.trim() || null,
  }
}

function clawhubImportMetadata(staged: PendingClawhubImport): ClawhubSkillImportMetadata {
  const payload = skillPayload()
  return {
    name: payload.name,
    nameEn: payload.nameEn,
    category: payload.category,
    tags: payload.tags,
    summary: payload.summary,
    summaryEn: payload.summaryEn,
    description: payload.description,
    descriptionEn: payload.descriptionEn,
    minClientVersion: payload.minClientVersion,
    sortWeight: payload.sortWeight,
    ...(staged.remote ? {
      sourceKind: staged.remote.sourceKind,
      sourceUrl: staged.remote.sourceUrl,
      sourceRevision: staged.remote.sourceRevision,
    } : {}),
  }
}

function expertImageAction(role: ExpertImageRole): ExpertImageAction {
  if (role === 'icon') {
    if (iconFile.value) return 'REPLACE'
    return iconPendingClear.value ? 'CLEAR' : (formMode.value === 'create' ? 'CLEAR' : 'KEEP')
  }
  if (role === 'illustration') {
    if (illustrationFile.value) return 'REPLACE'
    return illustrationPendingClear.value ? 'CLEAR' : (formMode.value === 'create' ? 'CLEAR' : 'KEEP')
  }
  if (exampleImageFile.value) return 'REPLACE'
  return exampleImagePendingClear.value ? 'CLEAR' : (formMode.value === 'create' ? 'CLEAR' : 'KEEP')
}

function expertAggregatePayload(expectedRowVersion?: number): ExpertAggregatePayload {
  const fields = expertDraftToAdminFields(expertDraft.value)
  const base = skillPayload()
  return {
    ...base,
    code: form.code.trim(),
    ...(expectedRowVersion == null ? {} : { expectedRowVersion }),
    taskTypes: fields.taskTypes ?? [],
    taskTypesEn: fields.taskTypesEn ?? [],
    usageGuide: fields.usageGuide,
    usageGuideEn: fields.usageGuideEn,
    examplePrompts: fields.examplePrompts ?? [],
    examplePromptsEn: fields.examplePromptsEn ?? [],
    preview: fields.preview,
    previewEn: fields.previewEn,
    canvas: fields.canvas,
    exampleImageLocale: fields.exampleImageLocale,
    images: {
      icon: expertImageAction('icon'),
      illustration: expertImageAction('illustration'),
      exampleImage: expertImageAction('exampleImage'),
    },
  }
}

function expertAggregateFiles() {
  return {
    icon: iconFile.value,
    illustration: illustrationFile.value,
    exampleImage: exampleImageFile.value,
  }
}

function applyExpertAggregateResponse(response: ExpertAggregateResponse) {
  const id = response.skill.id
  const savedIcon = response.presentation?.assets?.icon ?? response.skill.iconUrl
  const savedIllustration = response.presentation?.assets?.illustration ?? response.skill.illustrationUrl
  const savedExampleImage = response.presentation?.assets?.exampleImage ?? response.skill.exampleImageUrl
  const localIcon = iconFile.value ? expertDraft.value.media.icon.url : ''
  const localIllustration = illustrationFile.value ? expertDraft.value.media.illustration.url : ''
  const localExampleImage = exampleImageFile.value ? expertDraft.value.media.exampleImage.url : ''
  // Aggregate writes bypass the standalone image endpoints, so invalidate
  // their previews here after a successful clear/replacement. Reopening must
  // fetch the saved bytes instead of reusing the previous image.
  if (!savedIcon || expertImageAction('icon') !== 'KEEP') iconPreviews.forget(id)
  if (!savedIllustration || expertImageAction('illustration') !== 'KEEP') illustrationPreviews.forget(id)
  if (!savedExampleImage || expertImageAction('exampleImage') !== 'KEEP') exampleImagePreviews.forget(id)
  formSkill.value = response.skill
  form.rowVersion = response.presentation?.rowVersion ?? response.skill.rowVersion ?? form.rowVersion
  const presentation = response.presentation
  if (presentation) {
    const loaded = expertDraftFromAdmin({
      ...response.skill,
      name: presentation.title,
      nameEn: presentation.titleEn,
      summary: presentation.summary,
      summaryEn: presentation.summaryEn,
      description: presentation.description,
      descriptionEn: presentation.descriptionEn,
      taskTypes: presentation.taskTypes,
      taskTypesEn: presentation.taskTypesEn,
      usageGuide: presentation.usageGuide,
      usageGuideEn: presentation.usageGuideEn,
      examplePrompts: presentation.examplePrompts,
      examplePromptsEn: presentation.examplePromptsEn,
      preview: presentation.preview,
      previewEn: presentation.previewEn,
      canvas: presentation.canvas,
      exampleImageLocale: presentation.exampleImageLocale,
      iconUrl: savedIcon,
      illustrationUrl: savedIllustration,
      exampleImageUrl: savedExampleImage,
    })
    // The aggregate response deliberately returns authenticated API paths. A browser <img>
    // cannot attach the Axios bearer header to those paths, so keep using the already-safe
    // object URL while the create dialog remains open. Respect the server response as the
    // authority for presence: a successful CLEAR must not revive an older cached preview.
    loaded.media.icon.url = savedIcon
      ? (iconPreviewUrl.value || localIcon || iconPreviews.iconUrlFor(id))
      : ''
    loaded.media.illustration.url = savedIllustration
      ? (illustrationPreviewUrl.value || localIllustration || illustrationPreviews.imageUrlFor(id))
      : ''
    loaded.media.exampleImage.url = savedExampleImage
      ? (exampleImagePreviewUrl.value || localExampleImage || exampleImagePreviews.imageUrlFor(id))
      : ''
    expertDraft.value = loaded
    expertDraftBaseline.value = createExpertWysiwygDraft(loaded)
    expertPresentationPending.value = false
    applyExpertDraftToForm(loaded)
    captureExpertAdvancedBaseline()
  }
  iconFile.value = null
  illustrationFile.value = null
  exampleImageFile.value = null
  iconPendingClear.value = false
  illustrationPendingClear.value = false
  exampleImagePendingClear.value = false
}

async function submitForm(options?: { confirmedClawhub?: true }) {
  if (importingPackage.value || submitting.value) return
  // Enter on a form field must not bypass the ClawHub preview's explicit
  // confirmation button, even though the generic modal submit is hidden.
  if (clawhubReport.value && pendingClawhub.value) {
    if (!options?.confirmedClawhub) return
    if (clawhubReport.value.status === 'BLOCKED') {
      errorMessage.value = `${scope.value.importClawhubBlocked} ${clawhubReport.value.blockingReasons.join('；')}`
      return
    }
    if (!clawhubReport.value.code?.trim()) {
      errorMessage.value = '预检未返回可用技能标识，不能确认导入。'
      return
    }
    if (form.kind !== 'SKILL' || form.code.trim() !== clawhubReport.value.code) {
      errorMessage.value = '技能标识或类型与预检结果不一致，请取消后重新导入。'
      return
    }
    if (pendingClawhub.value.remote
      && remoteImportUrl.value.trim() !== pendingClawhub.value.remote.requestedUrl) {
      errorMessage.value = '远程链接已变化，请重新拉取并预检。'
      return
    }
    const validation = validateSkillForm()
    if (validation) {
      errorMessage.value = validation.message
      goToFormStep(validation.step)
      return
    }
    await commitClawhubImport()
    return
  }
  const validation = validateSkillForm()
  if (validation) {
    errorMessage.value = validation.message
    // 跳到出错的步骤，用户不用自己翻回去找哪个字段不合格
    goToFormStep(validation.step)
    return
  }
  /*
   * 图片改动的**最终确认**（2026-09-19 需求）。
   *
   * 编辑页里的「换图 / 清空」都只做了本地标记，真正的提交发生在这一刻，
   * 所以在这里把本次全部图片改动摊开给操作者确认一次。选择「返回修改」时
   * 直接返回、不改动任何状态 —— 这正是「点一下立即生效」与「经编辑页二次确认」
   * 的分界：在确认之前，后端和对象存储都还是原样。
   */
  if (pendingImageChanges.value.length > 0) {
    const confirmed = await confirmImageChanges(pendingImageChanges.value)
    if (!confirmed) return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    if (formMode.value === 'create' || !formSkill.value) {
      // A22：编码在三类之间全局唯一且不可复用，提交前再确认一次，
      // 避免把「已占用」留到后端 422 才告诉操作者。
      const availability = await fetchSkillCodeAvailability(form.code.trim()).catch(() => null)
      if (availability && !availability.available) {
        codeAvailability.value = availability
        errorMessage.value = `编码「${availability.code}」已被一个${codeHolderLabel(availability.occupiedBy)}占用。编码在普通技能、专家与专家团之间全局唯一且不可复用，请换一个编码。`
        return
      }
      const created = isExpertScope.value
        ? (applyExpertAggregateResponse(await createAdminExpertAggregate(
            { ...expertAggregatePayload(), code: form.code.trim() },
            expertAggregateFiles(),
          )), formSkill.value as SkillAdminDetail)
        : await createAdminSkill({ ...skillPayload(), code: form.code.trim() })
      formSkill.value = created
      formMode.value = 'edit'
      form.rowVersion = created.rowVersion ?? 0
      if (!isExpertScope.value) {
        if (iconFile.value) await saveIcon(created.id)
        if (illustrationFile.value) await saveIllustration(created.id)
        if (exampleImageFile.value) await saveExampleImage(created.id)
      }
      await load(0, { silent: true })
      // A27：从资源包导入的记录，创建成功后立刻把那个包传上去，
      // 省掉「建记录 → 再进编辑态 → 再选一次同一个文件」这段往返。
      // 上传失败不回滚已创建的记录：记录本身是有效的，包可以重新上传，
      // 所以只把错误抛给用户，由既有错误映射给出可操作文案。
      const staged = pendingPackage.value
      if (staged) {
        pendingPackage.value = null
        await uploadImportedPackage(staged)
        return
      }
      ElMessage.success(scope.value.created)
      return
    }
    const updated = isExpertScope.value
      ? (applyExpertAggregateResponse(await updateAdminExpertAggregate(
          formSkill.value.id,
          expertAggregatePayload(form.rowVersion),
          expertAggregateFiles(),
        )), formSkill.value as SkillAdminDetail)
      : await updateAdminSkill(formSkill.value.id, {
          ...skillPayload(),
          rowVersion: form.rowVersion,
        })
    formSkill.value = updated
    form.rowVersion = updated.rowVersion ?? form.rowVersion
    // 图标：待清空的标记优先于本地文件（两者本就互斥，`markIconClear` / `onIconPicked`
    // 都会把另一方复位；这里再排一次序，保证语义不依赖调用顺序）。
    if (!isExpertScope.value) {
      if (iconPendingClear.value) await commitIconClear(updated.id)
      else if (iconFile.value) await saveIcon(updated.id)
      if (showIllustration.value) {
        if (illustrationPendingClear.value) await commitIllustrationClear(updated.id)
        else if (illustrationFile.value) await saveIllustration(updated.id)
      }
      if (showExampleImage.value) {
        if (exampleImagePendingClear.value) await commitExampleImageClear(updated.id)
        else if (exampleImageFile.value) await saveExampleImage(updated.id)
      }
    }
    const staged = pendingPackage.value
    if (staged) {
      await confirmUpload(staged.file)
      if (uploadStatus.value === 'done' || uploadStatus.value === 'scanning') {
        pendingPackage.value = null
        formVisible.value = false
        await load(params.page, { silent: true })
        return
      }
      errorMessage.value = `技能信息已保存，但资源包未完成导入。${errorMessage.value}`
      return
    }
    formVisible.value = false
    await load(params.page, { silent: true })
    ElMessage.success(scope.value.updated)
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    submitting.value = false
  }
}

/**
 * A27：把导入时暂存的包上传为记录的首个版本，并沿用既有的轮询与错误处理。
 *
 * 复用 `confirmUpload` 而不是另写一条上传链路：版本号取自包内 `plugin.json`
 * （后端会校验它与表单字段一致），进度、取消、扫描轮询与失败文案都与手工上传完全一致。
 */
async function uploadImportedPackage(
  staged: PendingPackageImport,
) {
  versionForm.version = staged.version
  versionForm.changelog = ''
  versionForm.minClientVersion = ''
  replaceTarget.value = null
  await confirmUpload(staged.file)
  if (uploadStatus.value === 'done') {
    ElMessage.success(`${scope.value.created}，${scope.value.cnPackage} v${staged.version} 已就绪`)
  } else if (uploadStatus.value === 'scanning') {
    errorMessage.value = '技能记录已创建，资源包已提交，但扫描状态尚未确认；请稍后在版本管理中查看，勿重复创建。'
  } else {
    errorMessage.value = `技能记录已创建，但资源包未上传成功；请在版本管理中重新上传。${errorMessage.value ? ` ${errorMessage.value}` : ''}`
  }
}

async function commitClawhubImport() {
  const staged = pendingClawhub.value
  if (!staged || staged.report.status === 'BLOCKED') return
  submitting.value = true
  errorMessage.value = ''
  try {
    const created = await commitAdminClawhubSkill({
      file: staged.file,
      owner: staged.owner,
      slug: staged.slug,
      version: staged.version,
      displayName: staged.displayName,
      expectedSha256: staged.expectedSha256,
      metadata: clawhubImportMetadata(staged),
    })
    let iconUploadFailed = false
    let iconUploadError: unknown
    if (iconFile.value) {
      try {
        await saveIcon(created.skillId)
      } catch (error) {
        iconUploadFailed = true
        iconUploadError = error
      }
    }
    formVisible.value = false
    formSkill.value = null
    pendingClawhub.value = null
    clawhubReport.value = null
    clawhubDraftBeforePreview = null
    await load(params.page, { silent: true })
    if (iconUploadFailed) {
      errorMessage.value = `原生技能 ${created.code} 已创建为草稿，但图标上传失败；请在编辑页重新上传。${skillAdminErrorMessage(iconUploadError, adminCopyScope.value)}`
      return
    }
    ElMessage.success(`已新增${staged.remote?.sourceKind === 'GITHUB' ? ' GitHub' : '原生 ClawHub'} 技能 ${created.code}，状态：草稿（未上架），扫描：${scanLabel(created.scanStatus)}。`)
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    submitting.value = false
  }
}

function confirmClawhubImport() {
  void submitForm({ confirmedClawhub: true })
}

/* ---------------- 图片图标 ---------------- */
/**
 * 列表与详情里的图标都用同一个 composable 缓存：同一个技能只拉一次图片字节，
 * 组件卸载时统一释放 objectURL。表单里的待上传预览（iconPreviewUrl）是另一回事 ——
 * 那是用户刚选的本地文件，还没提交，跟已上传的图标不共缓存。
 */
const iconPreviews = useSkillIconPreviews()
/**
 * 客户端预览目录只有稳定的 code（`SkillSummary.name`），没有管理端 UUID，
 * 因此单独走 `/console/gateway/skills/{code}/icon` 并按 code 缓存 object URL。
 */
const previewIconPreviews = useSkillImagePreviews({ fetch: fetchSkillIcon })

/** 图片发生变更后让客户端目录重新拉取，并立即丢掉该 code 的旧 object URL。 */
function invalidatePreviewIcon(code: string) {
  if (code.trim()) previewIconPreviews.forget(code.trim())
}
/**
 * 介绍插画与图标同一套缓存语义，但**单独一个实例**：
 * 两者的 id 空间虽相同，缓存却要按图分开，否则详情页会把图标当插画显示。
 */
const illustrationPreviews = useSkillIllustrationPreviews()
const exampleImagePreviews = useSkillExampleImagePreviews()

function releaseIconPreview() {
  if (iconPreviewUrl.value) {
    iconObjectUrls.delete(iconPreviewUrl.value)
    if (typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(iconPreviewUrl.value)
    iconPreviewUrl.value = ''
  }
}

function iconPreviewError(message: string) {
  errorMessage.value = message
}

function onIconPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = ''
  if (!file) return
  if (!ICON_MIME_TYPES.includes(file.type)) {
    iconPreviewError('图标只支持 PNG、JPG 或 SVG 格式。')
    return
  }
  if (file.size > MAX_ICON_BYTES) {
    iconPreviewError('图标文件不能超过 4 MB。')
    return
  }
  releaseIconPreview()
  iconFile.value = file
  // 选了新文件就不再需要「清空」——新图会直接覆盖旧图，两个标记同时存在只会让人看不懂
  iconPendingClear.value = false
  if (typeof URL.createObjectURL === 'function') {
    iconPreviewUrl.value = URL.createObjectURL(file)
    iconObjectUrls.add(iconPreviewUrl.value)
  }
}

async function saveIcon(id: string) {
  if (!iconFile.value) return
  iconSaving.value = true
  try {
    await uploadAdminSkillIcon(id, iconFile.value)
    iconFile.value = null
    invalidatePreviewIcon(form.code)
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
    throw error
  } finally {
    iconSaving.value = false
  }
}

function clearIcon() {
  releaseIconPreview()
  iconFile.value = null
}

/**
 * 标记「清空**已上传**的图标」（区别于 `clearIcon`，后者只丢弃本地待上传的文件）。
 *
 * 2026-09-19 前这里是「点了就删」：直接调删除端点、当场删掉对象存储里的图并刷新列表，
 * 误点没有任何反悔余地。现在改成与「换图」对称的**延迟提交** ——
 * 只置 `iconPendingClear`，界面上显示「待清空」并可撤销，真正删除发生在
 * 点「保存修改」之后（由 `commitIconChange` 执行，且先经过一次最终确认）。
 */
function markIconClear() {
  // 已选新文件时，撤销「清空」没有意义：新文件会直接覆盖旧图
  releaseIconPreview()
  iconFile.value = null
  iconPendingClear.value = true
}

/** 撤销「待清空图标」标记，回到原状态（不碰后端） */
function undoIconClear() {
  iconPendingClear.value = false
}

/** 保存时真正清空图标；幂等端点，本来没图也不会报错 */
async function commitIconClear(id: string) {
  iconDeleting.value = true
  try {
    const updated = await deleteAdminSkillIcon(id)
    formSkill.value = { ...(formSkill.value ?? updated), ...updated }
    // 缓存里的 objectURL 还指向已删除的图，必须丢掉
    iconPreviews.forget(id)
    invalidatePreviewIcon(form.code)
    iconPendingClear.value = false
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
    throw error
  } finally {
    iconDeleting.value = false
  }
}

/**
 * 把**已上传**的图片下载到本地。
 *
 * 与「清空」「上传」不同，这是只读操作：不改后端状态、不写审计、失败也不需要回滚，
 * 所以共用一段实现 —— 三条取字节接口（图标 / 插画 / 分类封面）都返回裸 blob，
 * 文件名里的扩展名只能按 blob 的 MIME 推。
 *
 * 取字节而不是直接给 `<img src>` 一个链接，是因为这些路由都要管理员 JWT，
 * 而 `<a download>` 发不出凭据（与预览走同一条 fetch 路径）。
 */
async function downloadUploadedImage(
  code: string,
  role: SkillImageRole,
  fetcher: () => Promise<Blob>,
  busy: { value: boolean },
  label: string,
): Promise<void> {
  if (busy.value) return
  busy.value = true
  try {
    const blob = await fetcher()
    downloadSkillImageBlob(skillImageFileName(code, role, blob), blob)
    ElMessage.success(`${label}已开始下载。`)
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    busy.value = false
  }
}

/** 下载已上传的图标：用记录编码命名，与整包下载保持同一套命名习惯 */
async function downloadUploadedIcon() {
  const skill = formSkill.value
  if (!skill) return
  await downloadUploadedImage(
    skill.code,
    'icon',
    () => fetchAdminSkillIcon(skill.id),
    iconDownloading,
    '图标',
  )
}

/* ---------------- 介绍插画（可选，每包一张） ---------------- */
/**
 * 插画与图标一样是「已上传的图 + 本地待上传文件」两套预览，且都要带 JWT 取字节。
 * 与图标不同的是它**可缺省**：没有插画是常态，所以拉取失败只当作「没有」，不报错。
 */
function releaseIllustrationPreview() {
  if (illustrationPreviewUrl.value) {
    if (typeof URL.revokeObjectURL === 'function') {
      URL.revokeObjectURL(illustrationPreviewUrl.value)
    }
    illustrationPreviewUrl.value = ''
  }
}

function onIllustrationPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = ''
  if (!file) return
  if (!ILLUSTRATION_MIME_TYPES.includes(file.type)) {
    errorMessage.value = '介绍插画只支持 PNG、JPG、WebP 或 SVG 格式。'
    return
  }
  if (file.size > MAX_ILLUSTRATION_BYTES) {
    errorMessage.value = '介绍插画不能超过 4 MB。'
    return
  }
  releaseIllustrationPreview()
  illustrationFile.value = file
  // 同图标：选了新图就不再需要「清空」标记
  illustrationPendingClear.value = false
  if (typeof URL.createObjectURL === 'function') {
    illustrationPreviewUrl.value = URL.createObjectURL(file)
  }
}

function clearIllustration() {
  releaseIllustrationPreview()
  illustrationFile.value = null
}

async function saveIllustration(id: string) {
  if (!illustrationFile.value) return
  illustrationSaving.value = true
  try {
    await uploadAdminSkillIllustration(id, illustrationFile.value)
    illustrationFile.value = null
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
    throw error
  } finally {
    illustrationSaving.value = false
  }
}

/**
 * 标记「清空**已上传**的插画」（区别于 `clearIllustration`，后者只丢弃本地待上传的文件）。
 * 语义与 `markIconClear` 完全一致：只置位、可撤销，提交时才真正删除。
 */
function markIllustrationClear() {
  releaseIllustrationPreview()
  illustrationFile.value = null
  illustrationPendingClear.value = true
}

/** 撤销「待清空插画」标记，回到原状态（不碰后端） */
function undoIllustrationClear() {
  illustrationPendingClear.value = false
}

/** 保存时真正清空插画；幂等端点，本来没有也不会报错 */
async function commitIllustrationClear(id: string) {
  illustrationDeleting.value = true
  try {
    const updated = await deleteAdminSkillIllustration(id)
    formSkill.value = { ...(formSkill.value ?? updated), ...updated }
    releaseIllustrationPreview()
    illustrationPreviews.forget(id)
    illustrationPendingClear.value = false
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
    throw error
  } finally {
    illustrationDeleting.value = false
  }
}

/** 下载已上传的介绍插画：与图标共用命名规则，只是角色后缀不同 */
async function downloadUploadedIllustration() {
  const skill = formSkill.value
  if (!skill) return
  await downloadUploadedImage(
    skill.code,
    'illustration',
    () => fetchAdminSkillIllustration(skill.id),
    illustrationDownloading,
    '介绍插画',
  )
}

/* ---------------- 示例图片（可选，每包一张） ---------------- */
function releaseExampleImagePreview() {
  if (exampleImagePreviewUrl.value) {
    if (typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(exampleImagePreviewUrl.value)
    exampleImagePreviewUrl.value = ''
  }
}

function onExampleImagePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = ''
  if (!file) return
  if (!EXAMPLE_IMAGE_MIME_TYPES.includes(file.type)) {
    errorMessage.value = '示例图片只支持 PNG、JPG、WebP 或 SVG 格式。'
    return
  }
  if (file.size > MAX_EXAMPLE_IMAGE_BYTES) {
    errorMessage.value = '示例图片不能超过 4 MB。'
    return
  }
  releaseExampleImagePreview()
  exampleImageFile.value = file
  exampleImagePendingClear.value = false
  if (typeof URL.createObjectURL === 'function') {
    exampleImagePreviewUrl.value = URL.createObjectURL(file)
  }
}

function clearExampleImage() {
  releaseExampleImagePreview()
  exampleImageFile.value = null
}

async function saveExampleImage(id: string) {
  if (!exampleImageFile.value) return
  exampleImageSaving.value = true
  try {
    await uploadAdminSkillExampleImage(id, exampleImageFile.value)
    exampleImageFile.value = null
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
    throw error
  } finally {
    exampleImageSaving.value = false
  }
}

function markExampleImageClear() {
  releaseExampleImagePreview()
  exampleImageFile.value = null
  exampleImagePendingClear.value = true
}

function undoExampleImageClear() {
  exampleImagePendingClear.value = false
}

async function commitExampleImageClear(id: string) {
  exampleImageDeleting.value = true
  try {
    const updated = await deleteAdminSkillExampleImage(id)
    formSkill.value = { ...(formSkill.value ?? updated), ...updated }
    releaseExampleImagePreview()
    exampleImagePreviews.forget(id)
    exampleImagePendingClear.value = false
  } catch (error) {
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
    throw error
  } finally {
    exampleImageDeleting.value = false
  }
}

async function downloadUploadedExampleImage() {
  const skill = formSkill.value
  if (!skill) return
  await downloadUploadedImage(
    skill.code,
    'example',
    () => fetchAdminSkillExampleImage(skill.id),
    exampleImageDownloading,
    '示例图片',
  )
}

/* ---------------- 技能包上传 ---------------- */

/** 当前类型的 YCPS 压缩包上限；未知 kind 回退到技能档 */
function packageLimitFor(kind: SkillKind | null | undefined) {
  const code = kind && KIND_VALUES.includes(kind) ? kind : 'SKILL'
  return YCPS_PACKAGE_LIMITS[code] ?? MAX_PACKAGE_BYTES
}

/** 上传区提示：按类型说明上限与包结构（YCPS-only） */
function packageLimitHint(kind: SkillKind | null | undefined) {
  const code = kind && KIND_VALUES.includes(kind) ? kind : 'SKILL'
  return `当前类型（${kindLabel(code)}）的单个 YCPS 包上限为 ${humanizeBytes(packageLimitFor(code))}。各类型上限：技能 10 MB、专家 2 MB、专家团 5 MB。文件需以 .ycps.zip 结尾，根目录包含 plugin.json。`
}

async function startUpload(target: SkillVersionView) {
  if (!selected.value || target.sealed) return
  await openEdit(selected.value)
  goToFormStep(2)
}

async function confirmUpload(file: File) {
  if (!formSkill.value) return
  const version = versionForm.version.trim()
  if (!version) {
    errorMessage.value = scope.value.versionPlaceholder
    return
  }
  const skillId = formSkill.value.id
  uploadController = new AbortController()
  pollCancelled = false
  uploadingPackage.value = true
  uploadStatus.value = 'uploading'
  uploadProgress.value = { phase: 'uploading', percent: 0 }
  errorMessage.value = ''
  try {
    if (replaceTarget.value) {
      await replaceSkillPackageWithRetry(
        skillId,
        replaceTarget.value.id,
        file,
        { changelog: versionForm.changelog, minClientVersion: versionForm.minClientVersion },
        {
          signal: uploadController.signal,
          onProgress: (progress) => { uploadProgress.value = progress },
        },
      )
    } else {
      await uploadSkillVersionWithRetry(
        skillId,
        file,
        { version, changelog: versionForm.changelog, minClientVersion: versionForm.minClientVersion },
        {
          signal: uploadController.signal,
          onProgress: (progress) => { uploadProgress.value = progress },
        },
      )
    }
    uploadProgress.value = { phase: 'scanning', percent: 100 }
    uploadStatus.value = 'scanning'
    const settled = await pollVersion(skillId, version)
    if (pollCancelled) {
      uploadStatus.value = 'idle'
      return
    }
    if (settled && (settled.status === 'REJECTED' || settled.status === 'FAILED')) {
      uploadStatus.value = 'failed'
      errorMessage.value = settled.status === 'REJECTED'
        ? scope.value.scanRejected
        : scope.value.packageFailed
      return
    }
    if (!settled) {
      errorMessage.value = '资源包已提交，扫描状态尚未确认；请稍后在版本管理中查看。'
      return
    }
    uploadStatus.value = 'done'
    await reloadDetail()
    await load(params.page, { silent: true })
    ElMessage.success(`${scope.value.cnPackage} v${version} 已就绪`)
  } catch (error) {
    if (uploadController?.signal.aborted) {
      uploadStatus.value = 'idle'
      errorMessage.value = '上传已取消。'
      return
    }
    uploadStatus.value = 'failed'
    errorMessage.value = skillAdminErrorMessage(error, adminCopyScope.value)
  } finally {
    uploadingPackage.value = false
    uploadController = null
  }
}

/** 上传成功后按 1s 轮询，直到 READY / REJECTED / FAILED（设计 §8.3） */
async function pollVersion(skillId: string, version: string): Promise<SkillVersionView | null> {
  for (let attempt = 0; attempt < VERSION_POLL_LIMIT; attempt += 1) {
    if (pollCancelled || !mounted) return null
    await new Promise((resolve) => setTimeout(resolve, VERSION_POLL_INTERVAL_MS))
    if (pollCancelled || !mounted) return null
    let detailView: SkillAdminDetail
    try {
      detailView = await getAdminSkill(skillId)
    } catch {
      continue
    }
    if (!mounted) return null
    const match = (detailView.versions ?? []).find((candidate) => candidate.version === version)
    if (match && ['READY', 'REJECTED', 'FAILED'].includes(match.status)) return match
  }
  errorMessage.value = scope.value.scanning
  return null
}

function cancelUpload() {
  pollCancelled = true
  uploadController?.abort()
  uploadingPackage.value = false
  errorMessage.value = '上传已取消。'
  uploadStatus.value = 'idle'
}

/* ---------------- 客户端预览标签页 ---------------- */

function isNativeClawhubPreview(item: PreviewSkill | null): item is PreviewSkill & {
  nativeClawhub: NativeClawhubSkillSummary
} {
  return Boolean(item?.nativeClawhub)
}

function previewSkillIdentity(item: PreviewSkill): string {
  return isNativeClawhubPreview(item)
    ? `@${item.nativeClawhub.owner}/${item.nativeClawhub.slug}`
    : item.name
}

function nativeClawhubPreviewSkill(item: NativeClawhubSkillSummary): PreviewSkill {
  const description = item.description?.trim() || item.summary?.trim() || '来自 ClawHub 的原生技能'
  return {
    name: item.code,
    title: item.name,
    description,
    descriptionEn: item.descriptionEn?.trim() || item.summaryEn?.trim() || null,
    summary: item.summary,
    summaryEn: item.summaryEn,
    version: item.version,
    category: 'ClawHub',
    tags: ['ClawHub', item.owner],
    icon: null,
    iconUrl: item.iconUrl ?? null,
    highlights: [],
    updatedAt: item.updatedAt,
    fileCount: 0,
    totalBytes: item.sizeBytes ?? 0,
    files: [],
    status: item.status,
    publishedVersion: item.version,
    kind: 'SKILL',
    nativeClawhub: item,
  }
}

async function loadPreview(options: { silent?: boolean } = {}) {
  if (options.silent) refreshing.value = true
  else previewLoading.value = true
  previewFailure.value = null
  previewWarning.value = ''
  try {
    const [regularResult, nativeResult] = await Promise.allSettled([
      listSkills(),
      listNativeClawhubSkills(),
    ])
    if (!mounted) return
    const regularItems = regularResult.status === 'fulfilled' && Array.isArray(regularResult.value?.data)
      ? regularResult.value.data : []
    const nativeItems = nativeResult.status === 'fulfilled' && Array.isArray(nativeResult.value?.items)
      ? nativeResult.value.items
        .filter((item) => item.status === 'PUBLISHED' && item.packageFormat === 'CLAWHUB_SKILL')
        .map(nativeClawhubPreviewSkill)
      : []
    if (!regularItems.length && !nativeItems.length) {
      if (regularResult.status === 'rejected') throw regularResult.reason
      if (nativeResult.status === 'rejected') throw nativeResult.reason
    }
    if (regularResult.status === 'rejected') previewWarning.value = '普通技能目录加载失败，当前只显示原生 ClawHub 技能。'
    if (nativeResult.status === 'rejected') previewWarning.value = '原生 ClawHub 目录加载失败，当前只显示普通技能。'
    const knownCodes = new Set(regularItems.map((item) => item.name.toLowerCase()))
    const items = [...regularItems, ...nativeItems.filter((item) => !knownCodes.has(item.name.toLowerCase()))]
    previewSkills.value = items
    // iconUrl 只表示“有图”，真正字节需要控制台 JWT；按 code 拉成 blob URL 后再交给图片。
    // 每次刷新都 force，确保刚替换或清空的头像不会继续命中旧 object URL。
    void Promise.all(items
      .filter((item) => Boolean(item.iconUrl))
      .map((item) => previewIconPreviews.load(item.name, { force: true })))
  } catch (error) {
    if (!mounted) return
    previewSkills.value = []
    previewWarning.value = ''
    previewFailure.value = skillErrorMessage(error)
  } finally {
    if (mounted) {
      previewLoading.value = false
      refreshing.value = false
    }
  }
}

function resetDetail() {
  detail.value = null
  detailFailure.value = null
  detailLoading.value = false
  previewPath.value = ''
}

async function openPreviewDetail(name: string) {
  detailName.value = name
  detailOpen.value = true
  resetDetail()
  if (isNativeClawhubPreview(detailSummary.value)) return
  detailLoading.value = true
  try {
    const loaded = await getSkill(name)
    if (!mounted || detailName.value !== name) return
    detail.value = loaded
    if (loaded.iconUrl) void previewIconPreviews.load(loaded.name)
    if (loaded.kind === 'TEAM') void loadPreviewMemberAvatars(loaded.members ?? [])
  } catch (error) {
    if (!mounted || detailName.value !== name) return
    detailFailure.value = skillErrorMessage(error)
  } finally {
    if (mounted && detailName.value === name) detailLoading.value = false
  }
}

function closePreviewDetail() {
  detailName.value = ''
  resetDetail()
  releasePreviewMemberAvatarUrls()
}

function togglePreview(file: SkillFileContent) {
  if (file.encoding === 'base64') {
    ElMessage.warning('二进制文件不提供正文预览。')
    return
  }
  previewPath.value = previewPath.value === file.path ? '' : file.path
}

function skillIcon(item: { icon?: string | null }) {
  return (item.icon ?? '').trim()
}

watch(activeTab, (tab) => {
  if (tab === 'preview') void loadPreview()
})

watch(formVisible, (visible) => {
  if (!visible) invalidateRemoteImportFetch()
})

/**
 * 两个页面共用本组件（Vue Router 在 /admin/skills ↔ /admin/experts 之间复用实例）。
 * 作用域只在挂载时固定，因此这里不做重置——路由级切换用下面的 key 重建实例，
 * 避免在这里清空状态反而把「点新增」时用户刚填的内容抹掉。
 */
watch(() => props.kinds, () => {
  void load(0)
  void loadCategories()
})

onMounted(() => {
  void load()
  void loadCategories()
})

onBeforeUnmount(() => {
  mounted = false
  invalidateRemoteImportFetch()
  invalidateExpertAssetRequests()
  listGeneration += 1
  pollCancelled = true
  uploadController?.abort()
  for (const url of iconObjectUrls) {
    if (typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url)
  }
  iconObjectUrls.clear()
  for (const url of expertAssetObjectUrls) {
    if (typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url)
  }
  expertAssetObjectUrls.clear()
  // 分类封面的 objectURL 由 SkillCategoryManager 自己释放（随其卸载一起走）
  releaseMemberAvatarUrls()
  releasePreviewMemberAvatarUrls()
})
</script>

<template>
  <section class="admin-page" aria-labelledby="admin-skills-title">
    <header class="admin-page__header">
      <div>
        <h2 id="admin-skills-title">{{ scope.title }}</h2>
        <p>{{ scope.intro }}</p>
      </div>
      <div class="skill-header-actions">
        <el-button data-test="skills-recycle-open" @click="openRecycleBin">
          <Trash2 :size="16" aria-hidden="true" />
          回收站
        </el-button>
        <el-button :loading="refreshing" data-test="skills-refresh" @click="load(params.page, { silent: true })">
          <RefreshCw :size="16" aria-hidden="true" />
          刷新
        </el-button>
        <el-button type="primary" data-test="skill-create-open" @click="openCreate">
          <Plus :size="16" aria-hidden="true" />
          {{ scope.createLabel }}
        </el-button>
      </div>
    </header>

    <TopErrorMessage :message="errorMessage" />

    <el-tabs v-model="activeTab" class="skill-tabs">
      <el-tab-pane label="列表管理" name="list">
        <div class="admin-toolbar" data-test="skills-toolbar">
          <el-input
            v-model="params.query"
            data-test="skills-query"
            clearable
            :aria-label="scope.searchAria"
            placeholder="搜索标识、名称或简介"
            @keyup.enter="applyFilters"
          >
            <template #prefix><Search :size="15" aria-hidden="true" /></template>
          </el-input>
          <el-select
            v-model="params.status"
            data-test="skills-status"
            clearable
            aria-label="上架状态"
            placeholder="全部状态"
          >
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已上架" value="PUBLISHED" />
            <el-option label="已下架" value="UNPUBLISHED" />
          </el-select>
          <el-select
            v-model="params.category"
            data-test="skills-category"
            clearable
            filterable
            allow-create
            default-first-option
            :aria-label="scope.categoryAria"
            placeholder="全部分类"
          >
            <el-option v-for="category in categorySelectOptions" :key="category" :label="category" :value="category" />
          </el-select>
          <el-button type="primary" data-test="skills-filter" :loading="loading" @click="applyFilters">
            <Search :size="16" aria-hidden="true" />
            查询
          </el-button>
          <el-button data-test="skills-reset" @click="resetFilters">重置</el-button>
          <!-- 技能页没有卡片分组，分类只作为筛选与编辑项；管理入口与查询/重置并排 -->
          <el-button data-test="skills-category-manage" @click="openCategoryDialog">
            管理分类
          </el-button>
        </div>

        <div class="skill-bulk-toolbar" data-test="skills-bulk-toolbar">
          <div class="skill-bulk-toolbar__selection">
            <el-checkbox
              :model-value="allVisibleSkillsSelected"
              :indeterminate="visibleSelectionIndeterminate"
              :disabled="!visibleListItems.length || bulkBusy"
              data-test="skills-select-all"
              @change="toggleVisibleSelection"
            >
              全选当前{{ expertCardView && expertCategoryFilter ? '分类' : '页' }}
            </el-checkbox>
            <span
              v-if="selectedSkills.length"
              class="skill-bulk-toolbar__count"
              data-test="skills-selected-count"
              aria-live="polite"
            >
              已选 <strong>{{ selectedSkills.length }}</strong> 项
            </span>
            <span v-else class="skill-bulk-toolbar__hint" data-test="skills-selected-count">
              勾选条目后可批量管理状态与分类
            </span>
            <el-button
              v-if="selectedSkills.length"
              size="small"
              link
              data-test="skills-clear-selection"
              :disabled="bulkBusy"
              @click="clearBulkSelection"
            >
              取消选择
            </el-button>
          </div>

          <div v-if="selectedSkills.length" class="skill-bulk-toolbar__actions">
            <div class="skill-bulk-toolbar__action-group">
              <span class="skill-bulk-toolbar__label">状态</span>
              <el-button-group class="skill-bulk-toolbar__status-actions">
                <el-button
                  size="small"
                  type="primary"
                  plain
                  data-test="skills-bulk-publish"
                  :loading="bulkAction === 'publish'"
                  :disabled="!bulkPublishItems.length || bulkBusy"
                  @click="bulkPublish"
                >
                  上架所选
                </el-button>
                <el-button
                  size="small"
                  type="warning"
                  plain
                  data-test="skills-bulk-unpublish"
                  :loading="bulkAction === 'unpublish'"
                  :disabled="!bulkUnpublishItems.length || bulkBusy"
                  @click="bulkUnpublish"
                >
                  下架所选
                </el-button>
              </el-button-group>
            </div>
            <div class="skill-bulk-toolbar__action-group">
              <span class="skill-bulk-toolbar__label">资源包</span>
              <div class="skill-download-split skill-bulk-toolbar__download-actions">
                <el-button
                  size="small"
                  plain
                  data-test="skills-bulk-download-default"
                  :loading="isBulkDownloading"
                  :disabled="!canBulkDownload || bulkBusy"
                  :title="bulkDefaultDownloadMode === 'all' ? '默认导出 YCPS 含图完整包和 ClawHub 原始 ZIP；只选择一次保存文件夹' : bulkDefaultDownloadLabel"
                  @click="chooseBulkDownload(bulkDefaultDownloadMode)"
                >
                  {{ bulkDefaultDownloadLabel }}
                </el-button>
                <el-button
                  v-if="!isExpertScope && hasSelectedYcps"
                  size="small"
                  plain
                  class="skill-download-split__toggle"
                  data-test="skills-bulk-download-toggle"
                  :disabled="!canBulkDownload || bulkBusy"
                  :aria-expanded="bulkDownloadMenuOpen"
                  aria-label="选择资源包下载格式"
                  @click="bulkDownloadMenuOpen = !bulkDownloadMenuOpen"
                  @keydown.esc="bulkDownloadMenuOpen = false"
                >
                  <ChevronDown :size="14" aria-hidden="true" />
                </el-button>
                <div
                  v-if="!isExpertScope && bulkDownloadMenuOpen"
                  class="skill-download-split__menu skill-bulk-toolbar__download-menu"
                  role="menu"
                  aria-label="资源包下载选项"
                  data-test="skills-bulk-download-menu"
                  @keydown.esc="bulkDownloadMenuOpen = false"
                >
                  <button
                    v-if="hasSelectedYcps && hasSelectedClawhub"
                    type="button"
                    class="skill-download-split__item"
                    role="menuitem"
                    data-test="skills-bulk-download-all"
                    :disabled="!canBulkDownload || bulkBusy"
                    @click="chooseBulkDownload('all')"
                  >全部下载（YCPS 含图完整包 + ClawHub 原始 ZIP）</button>
                  <button
                    v-if="hasSelectedYcps"
                    type="button"
                    class="skill-download-split__item"
                    role="menuitem"
                    data-test="skills-bulk-download-bundle"
                    :disabled="!bulkDownloadItems.length || bulkBusy"
                    @click="chooseBulkDownload('ycps-with-assets')"
                  >仅 YCPS 含图完整包</button>
                  <button
                    v-if="hasSelectedYcps"
                    type="button"
                    class="skill-download-split__item"
                    role="menuitem"
                    data-test="skills-bulk-download-package"
                    :disabled="!bulkDownloadItems.length || bulkBusy"
                    @click="chooseBulkDownload('ycps')"
                  >仅 YCPS ZIP（不含图）</button>
                  <button
                    v-if="hasSelectedClawhub"
                    type="button"
                    class="skill-download-split__item"
                    role="menuitem"
                    data-test="skills-bulk-download-clawhub"
                    :disabled="bulkBusy"
                    @click="chooseBulkDownload('clawhub')"
                  >仅 ClawHub 原始 ZIP（含草稿）</button>
                </div>
              </div>
            </div>
            <div class="skill-bulk-toolbar__action-group skill-bulk-toolbar__category">
              <span class="skill-bulk-toolbar__label">分类</span>
              <el-select
                v-model="bulkCategory"
                size="small"
                filterable
                :allow-create="!expertCardView"
                default-first-option
                data-test="skills-bulk-category"
                :disabled="!selectedSkills.length || bulkBusy"
                placeholder="选择分类"
              >
                <el-option label="未分类" :value="BULK_UNCATEGORIZED" />
                <el-option
                  v-for="category in categorySelectOptions"
                  :key="category"
                  :label="category"
                  :value="category"
                />
              </el-select>
              <el-button
                size="small"
                data-test="skills-bulk-category-apply"
                :loading="bulkAction === 'category'"
                :disabled="!selectedSkills.length || !bulkCategory || bulkBusy"
                @click="bulkChangeCategory"
              >
                应用
              </el-button>
            </div>
            <span class="skill-bulk-toolbar__divider" aria-hidden="true" />
            <el-tooltip
              :disabled="canBulkDelete"
              content="删除所选仅支持已下架项"
              placement="top"
            >
              <span class="skill-bulk-toolbar__danger">
                <el-button
                  size="small"
                  type="danger"
                  plain
                  data-test="skills-bulk-delete"
                  :loading="bulkAction === 'delete'"
                  :disabled="!canBulkDelete || bulkBusy"
                  @click="bulkDelete"
                >
                  删除所选
                </el-button>
              </span>
            </el-tooltip>
          </div>
        </div>

        <el-skeleton v-if="loading" data-test="skills-skeleton" :rows="5" animated />

        <!--
          专家 / 专家团：二次分类卡片。
          一级分类是类型（由页面作用域固定，这里恒为一种），二级分类是业务分类，作为分组标题。
          二级分类筛选是本地过滤：它只改分组展示，不再多打一次列表请求（列表仍按页面作用域固定 kind）。
        -->
        <div v-else-if="expertCardView" class="expert-category-view">
          <div class="expert-category-toolbar" data-test="skills-category-tabs">
            <button
              type="button"
              class="expert-category-chip"
              :class="{ active: expertCategoryFilter === '' }"
              :aria-pressed="expertCategoryFilter === ''"
              data-test="skills-category-all"
              @click="expertCategoryFilter = ''"
            >
              全部
              <span class="expert-category-chip__count">{{ skills.length }}</span>
            </button>
            <button
              v-for="option in expertCategoryOptions"
              :key="option.key"
              type="button"
              class="expert-category-chip"
              :class="{ active: expertCategoryFilter === option.key }"
              :aria-pressed="expertCategoryFilter === option.key"
              :data-test="`skills-category-chip-${option.key}`"
              @click="expertCategoryFilter = option.key"
            >
              {{ option.label }}
              <span class="expert-category-chip__count">{{ option.count }}</span>
            </button>
          </div>

          <div v-if="!skills.length" class="admin-empty" data-test="skills-empty">
            {{ scope.empty }}
          </div>

          <div
            v-else-if="!expertCategoryGroups.length"
            class="admin-empty"
            data-test="skills-category-no-match"
          >
            该分类下暂无{{ scope.cnNoun }}，换个分类试试。
          </div>

          <section
            v-for="group in expertCategoryGroups"
            v-else
            :key="group.key"
            class="expert-category-group"
            :data-test="`skills-category-group-${group.key}`"
          >
            <h3 class="expert-category-group__title">
              {{ group.label }}
              <span class="expert-category-group__count">{{ group.items.length }}</span>
            </h3>
            <div class="expert-category-cards">
              <article
                v-for="item in group.items"
                :key="item.id"
                class="expert-category-card"
                :class="{ 'expert-category-card--selected': isSkillSelected(item.id) }"
                :data-test="`skill-card-${item.code}`"
              >
                <header class="expert-category-card__head">
                  <el-checkbox
                    :model-value="isSkillSelected(item.id)"
                    :aria-label="`选择${item.name}`"
                    :data-test="`skill-select-${item.code}`"
                    :disabled="bulkBusy"
                    @change="(checked: boolean | string | number) => setSkillSelected(item.id, checked)"
                  />
                  <SkillAvatar
                    :src="iconPreviews.iconUrlFor(item.id)"
                    :badge="skillIcon(item)"
                    :fallback="itemBadge(item)"
                    size="sm"
                  />
                  <div class="expert-category-card__identity">
                    <strong class="skill-cell__name">{{ item.name }}</strong>
                    <small v-if="item.nameEn" class="skill-cell__name-en" :data-test="`skill-name-en-${item.code}`">
                      {{ item.nameEn }}
                    </small>
                    <code class="skill-cell__code">{{ item.code }}</code>
                  </div>
                </header>

                <div class="expert-category-card__badges">
                  <el-tag
                    v-if="kindCode(item)"
                    size="small"
                    :type="kindTagType(kindCode(item))"
                    :data-test="`skill-kind-${item.code}`"
                  >
                    {{ kindLabel(kindCode(item)) }}
                  </el-tag>
                  <span class="skill-card__category" :data-test="`skill-category-${item.code}`">
                    {{ itemCategoryLabel(item) }}
                  </span>
                  <span
                    v-if="item.kind === 'TEAM' && item.memberCount > 0"
                    class="skill-member-count"
                    :data-test="`skill-member-count-${item.code}`"
                  >
                    成员 {{ item.memberCount }}
                  </span>
                </div>

                <p class="expert-category-card__summary">{{ item.summary || '暂无简介' }}</p>
                <p v-if="item.summaryEn" class="expert-category-card__summary expert-category-card__summary--en">
                  {{ item.summaryEn }}
                </p>

                <dl class="skill-card__meta">
                  <div>
                    <dt>版本</dt>
                    <dd>
                      <span data-test="skill-version-{{ item.code }}">{{ versionCellText(item) }}</span>
                      <span
                        v-if="hasPendingRelease(item)"
                        class="skill-badge-pending"
                        :data-test="`skill-pending-${item.code}`"
                      >
                        有新版本待发布
                      </span>
                    </dd>
                  </div>
                  <div><dt>大小</dt><dd class="admin-number">{{ formatBytes(item.sizeBytes) }}</dd></div>
                  <div>
                    <dt>依赖 / 被引用</dt>
                    <dd class="admin-number" :data-test="`skill-relation-counts-${item.code}`">
                      {{ item.dependencyCount }} / {{ item.dependentCount }}
                    </dd>
                  </div>
                  <div><dt>下载</dt><dd class="admin-number">{{ item.downloadCount }}</dd></div>
                  <div>
                    <dt>状态</dt>
                    <dd>
                      <el-tag
                        size="small"
                        :type="statusTagType(item.status)"
                        :data-test="`skill-status-${item.code}`"
                      >
                        {{ statusLabel(item.status) }}
                      </el-tag>
                      <el-tag
                        v-if="item.sourceFormat === 'CLAWHUB_SKILL'"
                        size="small"
                        type="info"
                        data-test="skill-source-clawhub"
                      >
                        原生技能
                      </el-tag>
                      <el-tag
                        v-if="needsClawhubSourceVersion(item)"
                        size="small"
                        type="warning"
                        data-test="clawhub-missing-version"
                      >
                        待补版本号
                      </el-tag>
                    </dd>
                  </div>
                  <div><dt>创建</dt><dd>{{ createdDate(item) }}</dd></div>
                </dl>

                <div class="skill-row-actions">
                  <el-button size="small" text :data-test="`skill-edit-${item.code}`" @click="openEdit(item)">
                    <Pencil :size="14" aria-hidden="true" />
                    编辑
                  </el-button>
                  <el-button size="small" text :data-test="`skill-detail-${item.code}`" @click="openDrawer(item)">
                    <Eye :size="14" aria-hidden="true" />
                    详情
                  </el-button>
                  <el-button
                    size="small"
                    text
                    :disabled="!(item.dependencyCount > 0 || item.dependentCount > 0)"
                    :data-test="`skill-relations-${item.code}`"
                    @click="openRelations(item)"
                  >
                    <GitBranch :size="14" aria-hidden="true" />
                    关系
                  </el-button>
                  <el-button
                    v-if="item.status === 'PUBLISHED'"
                    size="small"
                    text
                    type="warning"
                    :loading="publishingId === item.id"
                    :data-test="`skill-unpublish-${item.code}`"
                    @click="togglePublish(item)"
                  >
                    下架
                  </el-button>
                  <el-button
                    v-else
                    size="small"
                    text
                    type="primary"
                    :loading="publishingId === item.id"
                    :title="item.sourceFormat === 'CLAWHUB_SKILL'
                      ? '按上次线上版本上架；从未选版且仅有一个版本时直接上架，多个版本需在详情选择'
                      : item.sourceReviewRequired ? '发布前将二次确认；桌面端需完成本地审核/配置后才能启用' : ''"
                    :data-test="`skill-publish-${item.code}`"
                    @click="togglePublish(item)"
                  >
                    上架
                  </el-button>
                  <el-tooltip
                    v-if="item.status === 'PUBLISHED'"
                    content="需先下架"
                    placement="top"
                  >
                    <span class="skill-action-disabled">
                      <el-button size="small" text type="danger" disabled :data-test="`skill-delete-${item.code}`">
                        <Trash2 :size="14" aria-hidden="true" />
                        删除
                      </el-button>
                    </span>
                  </el-tooltip>
                  <el-button
                    v-else
                    size="small"
                    text
                    type="danger"
                    :loading="deletingId === item.id"
                    :data-test="`skill-delete-${item.code}`"
                    @click="removeSkill(item)"
                  >
                    <Trash2 :size="14" aria-hidden="true" />
                    删除
                  </el-button>
                </div>
              </article>
            </div>
          </section>

          <div class="admin-pagination">
            <span>共 {{ total }} 个 {{ scope.cnNoun }}</span>
            <el-pagination
              data-test="skills-pagination"
              size="small"
              background
              layout="sizes, prev, pager, next"
              :current-page="params.page + 1"
              :page-size="params.size"
              :page-sizes="pageSizeOptions"
              :total="total"
              @size-change="changePageSize"
              @current-change="(value: number) => load(value - 1)"
            />
          </div>
        </div>

        <div v-else class="admin-panel">
          <div class="table-scroll">
            <table class="admin-table skills-table" data-test="skills-table">
              <thead>
                <tr>
                  <th scope="col" class="skill-selection-cell">
                    <span class="sr-only">选择</span>
                  </th>
                  <th scope="col">图标</th>
                  <th scope="col">名称 / 标识</th>
                  <th scope="col">分类</th>
                  <th scope="col">线上版本</th>
                  <th scope="col">大小</th>
                  <th scope="col">依赖 / 被引用</th>
                  <th scope="col">下载次数</th>
                  <th scope="col">状态</th>
                  <th scope="col">创建时间</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in skills" :key="item.id" :data-test="`skill-row-${item.code}`">
                  <td class="skill-selection-cell">
                    <el-checkbox
                      :model-value="isSkillSelected(item.id)"
                      :aria-label="`选择${item.name}`"
                      :data-test="`skill-select-${item.code}`"
                      :disabled="bulkBusy"
                      @change="(checked: boolean | string | number) => setSkillSelected(item.id, checked)"
                    />
                  </td>
                  <td>
                    <SkillAvatar
                      :src="iconPreviews.iconUrlFor(item.id)"
                      :code="item.kind === 'SKILL' ? item.code : ''"
                      :badge="skillIcon(item)"
                      :fallback="skillInitial(item)"
                      size="sm"
                    />
                  </td>
                  <td>
                    <div class="skill-cell__identity">
                      <strong class="skill-cell__name">{{ item.name }}</strong>
                      <el-tag
                        v-if="kindCode(item)"
                        size="small"
                        :type="kindTagType(kindCode(item))"
                        class="skill-kind-tag"
                        :data-test="`skill-kind-${item.code}`"
                      >
                        {{ kindLabel(kindCode(item)) }}
                      </el-tag>
                      <span
                        v-if="item.kind === 'TEAM' && item.memberCount > 0"
                        class="skill-member-count"
                        :data-test="`skill-member-count-${item.code}`"
                      >
                        成员 {{ item.memberCount }}
                      </span>
                    </div>
                    <small v-if="item.nameEn" class="skill-cell__name-en" :data-test="`skill-name-en-${item.code}`">
                      {{ item.nameEn }}
                    </small>
                    <code class="skill-cell__code">{{ item.code }}</code>
                  </td>
                  <td>{{ item.category || '未分类' }}</td>
                  <td>
                    <span data-test="skill-version-{{ item.code }}">{{ versionCellText(item) }}</span>
                    <span
                      v-if="hasPendingRelease(item)"
                      class="skill-badge-pending"
                      :data-test="`skill-pending-${item.code}`"
                    >
                      有新版本待发布
                    </span>
                  </td>
                  <td
                    class="admin-number"
                    :title="item.sourceFormat === 'CLAWHUB_SKILL' ? '默认导出原始 ZIP 的大小；已上架过的显示当前或上次上架版本，否则显示最新草稿版本' : undefined"
                  >{{ item.sourceFormat === 'CLAWHUB_SKILL' ? nativeClawhubPackageSize(item.sizeBytes) : formatBytes(item.sizeBytes) }}</td>
                  <!-- G4：列表直接给出依赖/被引用计数，避免为看一眼关系而逐包下钻 A20 -->
                  <td class="admin-number" :data-test="`skill-relation-counts-${item.code}`">
                    {{ item.dependencyCount }} / {{ item.dependentCount }}
                  </td>
                  <td class="admin-number">{{ item.downloadCount }}</td>
                  <td>
                    <el-tag
                      size="small"
                      :type="statusTagType(item.status)"
                      :data-test="`skill-status-${item.code}`"
                    >
                      {{ statusLabel(item.status) }}
                    </el-tag>
                    <el-tag
                      v-if="item.sourceFormat === 'CLAWHUB_SKILL'"
                      size="small"
                      type="info"
                      data-test="skill-source-clawhub"
                    >
                      原生技能
                    </el-tag>
                    <el-tag
                      v-if="needsClawhubSourceVersion(item)"
                      size="small"
                      type="warning"
                      data-test="clawhub-missing-version"
                    >
                      待补版本号
                    </el-tag>
                  </td>
                  <td>{{ createdDate(item) }}</td>
                  <td>
                    <div class="skill-row-actions">
                      <el-button size="small" text :data-test="`skill-edit-${item.code}`" @click="openEdit(item)">
                        <Pencil :size="14" aria-hidden="true" />
                        编辑
                      </el-button>
                      <el-button size="small" text :data-test="`skill-detail-${item.code}`" @click="openDrawer(item)">
                        <Eye :size="14" aria-hidden="true" />
                        详情
                      </el-button>
                      <el-button
                        size="small"
                        text
                        :disabled="!(item.dependencyCount > 0 || item.dependentCount > 0)"
                        :data-test="`skill-relations-${item.code}`"
                        @click="openRelations(item)"
                      >
                        <GitBranch :size="14" aria-hidden="true" />
                        关系
                      </el-button>
                      <el-button
                        v-if="item.status === 'PUBLISHED'"
                        size="small"
                        text
                        type="warning"
                        :loading="publishingId === item.id"
                        :data-test="`skill-unpublish-${item.code}`"
                        @click="togglePublish(item)"
                      >
                        下架
                      </el-button>
                      <el-button
                        v-else
                        size="small"
                        text
                        type="primary"
                        :loading="publishingId === item.id"
                        :title="item.sourceFormat === 'CLAWHUB_SKILL'
                          ? '按上次线上版本上架；从未选版且仅有一个版本时直接上架，多个版本需在详情选择'
                          : item.sourceReviewRequired ? '发布前将二次确认；桌面端需完成本地审核/配置后才能启用' : ''"
                        :data-test="`skill-publish-${item.code}`"
                        @click="togglePublish(item)"
                      >
                        上架
                      </el-button>
                      <el-tooltip
                        v-if="item.status === 'PUBLISHED'"
                        content="需先下架"
                        placement="top"
                      >
                        <span class="skill-action-disabled">
                          <el-button size="small" text type="danger" disabled :data-test="`skill-delete-${item.code}`">
                            <Trash2 :size="14" aria-hidden="true" />
                            删除
                          </el-button>
                        </span>
                      </el-tooltip>
                      <el-button
                        v-else
                        size="small"
                        text
                        type="danger"
                        :loading="deletingId === item.id"
                        :data-test="`skill-delete-${item.code}`"
                        @click="removeSkill(item)"
                      >
                        <Trash2 :size="14" aria-hidden="true" />
                        删除
                      </el-button>
                    </div>
                  </td>
                </tr>
                <tr v-if="!skills.length">
                  <td class="admin-empty" colspan="11" data-test="skills-empty">
                    {{ scope.empty }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="admin-pagination">
            <span>共 {{ total }} 个 Skill</span>
            <el-pagination
              data-test="skills-pagination"
              size="small"
              background
              layout="sizes, prev, pager, next"
              :current-page="params.page + 1"
              :page-size="params.size"
              :page-sizes="pageSizeOptions"
              :total="total"
              @size-change="changePageSize"
              @current-change="(value: number) => load(value - 1)"
            />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="客户端预览" name="preview">
        <div class="admin-toolbar" data-test="skills-preview-toolbar">
          <el-input
            v-model="previewKeyword"
            data-test="skills-preview-query"
            clearable
            :aria-label="scope.previewSearchAria"
            placeholder="搜索名称、描述或标签"
          >
            <template #prefix><Search :size="15" aria-hidden="true" /></template>
          </el-input>
          <el-button data-test="skills-preview-refresh" :loading="previewLoading" @click="loadPreview({ silent: true })">
            <RefreshCw :size="16" aria-hidden="true" />
            刷新
          </el-button>
        </div>

        <el-skeleton v-if="previewLoading" data-test="skills-preview-skeleton" :rows="5" animated />

        <div v-else-if="previewFailure" class="admin-panel skill-preview-error" role="alert" data-test="skills-preview-error">
          <CircleAlert :size="18" aria-hidden="true" />
          <div>
            <strong data-test="skills-preview-error-message">{{ previewFailure.message }}</strong>
            <span v-if="previewFailure.code">错误码：<code>{{ previewFailure.code }}</code></span>
            <span v-if="previewFailure.requestId">请求编号：<code>{{ previewFailure.requestId }}</code></span>
          </div>
        </div>

        <template v-else>
          <p v-if="previewWarning" class="skill-preview-warning" role="status" data-test="skills-preview-warning">
            <CircleAlert :size="16" aria-hidden="true" />
            {{ previewWarning }}
          </p>
          <div v-if="!previewSkills.length" class="admin-empty" data-test="skills-preview-empty">
            {{ scope.previewEmpty }}
          </div>

          <div
            v-else-if="previewSearchActive && !filteredPreviewSkills.length"
            class="admin-empty"
            data-test="skills-preview-no-match"
          >
            {{ scope.previewNoMatch }}
          </div>

          <!--
            三类分开预览：技能、专家、专家团各自一个区块，不再同屏混排。
            每块只展示自己 kind 的已上架条目，空态与「无匹配」也各自独立。
          -->
          <template v-else>
            <section
              v-for="section in previewSections"
              :key="section.kind"
              class="skill-preview-section"
              :data-test="`skills-preview-section-${section.kind}`"
            >
              <header class="skill-preview-section__head">
                <h3 class="skill-preview-section__title" :data-test="`skills-preview-title-${section.kind}`">
                  {{ section.title }}
                  <span class="skill-preview-section__count">{{ section.items.length }}</span>
                </h3>
                <span v-if="!section.items.length" class="admin-muted" :data-test="`skills-preview-empty-${section.kind}`">
                  {{ section.empty }}
                </span>
              </header>

              <div v-if="section.items.length" class="skills-grid" role="list" :data-test="`skills-grid-${section.kind}`">
                <article
                  v-for="item in section.items"
                  :key="item.name"
                  class="skill-card"
                  role="listitem"
                  :data-test="`skill-card-${item.name}`"
                >
                  <div class="skill-card__head">
                    <SkillAvatar
                      class="skill-card__icon"
                      size="md"
                      :src="item.iconUrl ? previewIconPreviews.imageUrlFor(item.name) : ''"
                      :code="section.kind === 'SKILL' ? item.name : ''"
                      :badge="skillIcon(item)"
                      :fallback="skillInitial(item)"
                      :alt="`${item.title || item.name}图标`"
                      :data-test="`skill-card-avatar-${item.name}`"
                    />
                    <div class="skill-card__identity">
                      <h3 class="skill-card__title">{{ item.title || item.name }}</h3>
                      <small v-if="item.titleEn" class="skill-card__title-en" :data-test="`skill-card-title-en-${item.name}`">
                        {{ item.titleEn }}
                      </small>
                      <code class="skill-card__name">{{ previewSkillIdentity(item) }}</code>
                    </div>
                  </div>
                  <div class="skill-card__badges">
                    <el-tag
                      v-if="kindCode(item)"
                      size="small"
                      :type="kindTagType(kindCode(item))"
                      :data-test="`skill-card-kind-${item.name}`"
                    >
                      {{ kindLabel(kindCode(item)) }}
                    </el-tag>
                    <el-tag v-if="isNativeClawhubPreview(item)" size="small" type="primary" :data-test="`skill-card-native-${item.name}`">
                      原生技能
                    </el-tag>
                    <span v-if="item.category" class="skill-card__category">{{ item.category }}</span>
                  </div>

                  <p class="skill-card__desc">{{ item.description || '暂无描述' }}</p>
                  <p v-if="item.descriptionEn" class="skill-card__desc skill-card__desc--en" :data-test="`skill-card-desc-en-${item.name}`">
                    {{ item.descriptionEn }}
                  </p>

                  <dl class="skill-card__meta">
                    <div><dt>版本</dt><dd>v{{ item.version || '—' }}</dd></div>
                    <div><dt>文件</dt><dd>{{ isNativeClawhubPreview(item) ? '—' : item.fileCount }}</dd></div>
                    <div><dt>大小</dt><dd>{{ isNativeClawhubPreview(item) ? nativeClawhubPackageSize(item.nativeClawhub.sizeBytes) : formatBytes(item.totalBytes) }}</dd></div>
                    <div><dt>更新</dt><dd>{{ formatDateTime(item.updatedAt).slice(0, 10) }}</dd></div>
                  </dl>

                  <div class="skill-card__actions">
                    <el-button size="small" :data-test="`skill-card-detail-${item.name}`" @click="openPreviewDetail(item.name)">
                      <Eye :size="15" aria-hidden="true" />
                      查看详情
                    </el-button>
                  </div>
                </article>
              </div>
            </section>
          </template>

          <p v-if="previewSearchActive" class="skill-preview-summary" data-test="skills-preview-summary">
            筛选出 {{ filteredPreviewSkills.length }} 个{{ scope.cnNoun }}
          </p>
        </template>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="recycleVisible"
      :title="`${scope.cnNoun}回收站（保留 30 天）`"
      width="min(880px, calc(100vw - 24px))"
      :teleported="false"
      destroy-on-close
      data-test="skills-recycle-dialog"
    >
      <div class="skill-recycle-toolbar">
        <el-input
          v-model="recycleQuery"
          clearable
          data-test="skills-recycle-query"
          placeholder="搜索标识或名称"
          @keyup.enter="searchRecycleBin"
          @clear="searchRecycleBin"
        >
          <template #prefix><Search :size="15" aria-hidden="true" /></template>
        </el-input>
        <el-button
          data-test="skills-recycle-search"
          :loading="recycleLoading"
          @click="searchRecycleBin"
        >
          查询
        </el-button>
        <span class="admin-muted">共 {{ recycleTotal }} 条</span>
      </div>

      <div class="admin-table-wrap skill-recycle-table-wrap">
        <table class="admin-table skill-recycle-table" data-test="skills-recycle-table">
          <thead>
            <tr>
              <th scope="col" class="skill-selection-cell">
                <el-checkbox
                  :model-value="recycleAllSelected"
                  :indeterminate="recycleSelectionIndeterminate"
                  :disabled="!recycleItems.length || recycleBusy"
                  aria-label="全选回收站当前结果"
                  data-test="skills-recycle-select-all"
                  @change="toggleRecycleSelection"
                />
              </th>
              <th scope="col">名称 / 标识</th>
              <th scope="col">类型</th>
              <th scope="col">删除时间</th>
              <th scope="col">剩余时间</th>
            </tr>
          </thead>
          <tbody v-loading="recycleLoading">
            <tr v-for="item in recycleItems" :key="item.id" :data-test="`skills-recycle-row-${item.code}`">
              <td class="skill-selection-cell">
                <el-checkbox
                  :model-value="recycleSelectedIds.includes(item.id)"
                  :aria-label="`选择${item.name}`"
                  :data-test="`skills-recycle-select-${item.code}`"
                  :disabled="recycleBusy"
                  @change="(checked: boolean | string | number) => setRecycleSelected(item.id, checked)"
                />
              </td>
              <td>
                <strong>{{ item.name }}</strong>
                <small v-if="item.nameEn" class="skill-cell__name-en">{{ item.nameEn }}</small>
                <code>{{ item.code }}</code>
              </td>
              <td>{{ kindLabel(item.kind) }}</td>
              <td>{{ formatDateTime(item.deletedAt) }}</td>
              <td><strong>{{ recycleDaysLeft(item) }} 天</strong></td>
            </tr>
            <tr v-if="!recycleLoading && !recycleItems.length">
              <td colspan="5" class="admin-empty" data-test="skills-recycle-empty">回收站为空</td>
            </tr>
          </tbody>
        </table>
      </div>

      <el-pagination
        v-if="recycleTotal > 100"
        class="skill-recycle-pagination"
        background
        layout="prev, pager, next, total"
        :current-page="recyclePage + 1"
        :page-size="100"
        :total="recycleTotal"
        data-test="skills-recycle-pagination"
        @current-change="changeRecyclePage"
      />

      <template #footer>
        <div class="skill-recycle-footer">
          <span class="admin-muted">已选 {{ recycleSelectedItems.length }} 项；操作将直接执行，不再二次确认</span>
          <div>
            <el-button data-test="skills-recycle-close" :disabled="recycleBusy" @click="recycleVisible = false">关闭</el-button>
            <el-button
              type="primary"
              plain
              data-test="skills-recycle-restore"
              :loading="recycleAction === 'restore'"
              :disabled="!recycleSelectedItems.length || recycleBusy"
              @click="restoreRecycleSelection"
            >
              <Undo2 :size="15" aria-hidden="true" />
              批量恢复
            </el-button>
            <el-button
              type="danger"
              data-test="skills-recycle-purge"
              :loading="recycleAction === 'purge'"
              :disabled="!recycleSelectedItems.length || recycleBusy"
              @click="purgeRecycleSelection"
            >
              <Trash2 :size="15" aria-hidden="true" />
              批量彻底删除
            </el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <!-- 分类管理（A23 / A24 / A26）：新增 / 修改 / 删除 + 精选场景封面上传 -->
    <SkillCategoryManager
      ref="categoryManagerRef"
      v-model:visible="categoryDialogVisible"
      :categories="expertCategories"
      :scope="expertCategoryScope(scopeKinds[0])"
      :noun="scope.cnNoun"
      :package-noun="scope.cnPackage"
      :cover-enabled="categoryCoverEnabled"
      :error-scope="adminCopyScope"
      @changed="loadCategories"
      @category-removed="onCategoryRemoved"
    />

    <!-- 新增 / 编辑 -->
    <el-dialog
      v-model="formVisible"
      :title="formMode === 'create' ? scope.createLabel : `${scope.editTitle} · ${form.code}`"
      :width="isExpertScope ? 'min(1200px, calc(100vw - 32px))' : 'min(720px, calc(100vw - 32px))'"
      :teleported="false"
      destroy-on-close
      :close-on-click-modal="!uploadLocked"
      :show-close="!uploadLocked"
    >
        <el-form label-position="top" class="skill-form" @submit.prevent="submitForm()">
        <SkillPortableImportPanel
          v-if="isExpertScope"
          :skill="formSkill"
          @committed="onPortableImportCommitted"
        />
        <ExpertWysiwygEditor
          v-if="isExpertScope"
          :model-value="expertDraft"
          :disabled="uploadLocked || submitting"
          :dirty="expertEditorDirty"
          @update:model-value="onExpertDraftUpdated"
          @asset-picked="onExpertAssetPicked"
          @asset-cleared="onExpertAssetCleared"
        />
        <section v-if="isExpertScope" class="skill-form__section expert-advanced-settings" data-test="expert-advanced-settings">
          <el-collapse v-model="expertAdvancedOpen">
            <el-collapse-item name="advanced">
              <template #title>
                <span>高级设置（运营与资源包）</span>
              </template>
              <p class="field-help">
                专家详情画布负责标题、说明、任务类型、示例提示词、预览和三类图片；这里仅维护编码、分类、卡片摘要、最低客户端版本与资源包。
              </p>
              <div class="admin-form-grid">
                <el-form-item label="编码" required>
                  <el-input
                    v-model="form.code"
                    :readonly="formMode === 'edit'"
                    data-test="expert-advanced-code"
                    @input="scheduleCodeAvailabilityCheck"
                  />
                </el-form-item>
                <el-form-item label="分类">
                  <el-select
                    v-model="form.category"
                    data-test="expert-advanced-category"
                    filterable
                    allow-create
                    clearable
                    default-first-option
                    placeholder="例如：数据分析"
                  >
                    <el-option v-for="category in categorySelectOptions" :key="category" :label="category" :value="category" />
                  </el-select>
                </el-form-item>
                <el-form-item label="卡片摘要（中文）" required>
                  <el-input v-model="form.summary" maxlength="512" show-word-limit data-test="expert-advanced-summary" />
                </el-form-item>
                <el-form-item label="卡片摘要（英文）">
                  <el-input v-model="form.summaryEn" maxlength="512" show-word-limit data-test="expert-advanced-summary-en" />
                </el-form-item>
                <el-form-item label="排序权重">
                  <el-input-number v-model="form.sortWeight" :min="0" :max="9999" data-test="expert-advanced-sort" />
                </el-form-item>
                <el-form-item label="最低客户端版本" required>
                  <el-input v-model="form.minClientVersion" placeholder="2026.6.0" data-test="expert-advanced-min-client" />
                </el-form-item>
              </div>

              <section v-if="formMode === 'edit' && formSkill" class="skill-form__section" data-test="expert-advanced-package">
                <h3><Package :size="15" aria-hidden="true" />资源包</h3>
                <div v-if="formPublishedVersion" class="skill-version-card" data-test="expert-current-version">
                  <div><span>当前线上版本</span><strong>v{{ formPublishedVersion.version }} · revision {{ formPublishedVersion.revision }}</strong></div>
                  <div><span>大小</span><strong>{{ humanizeBytes(formPublishedVersion.sizeBytes) }}</strong></div>
                  <div><span>扫描状态</span><strong>{{ scanLabel(formPublishedVersion.scanStatus) }}</strong></div>
                </div>
                <p v-else class="field-help">{{ scope.noReadyVersionHint }}</p>
                <p class="field-help" data-test="expert-package-import-hint">
                  更新专家包请使用上方「导入 Expert YCPS 1.1」拖拽或选择文件并确认差异；导入后生成待发布修订，需在版本管理中设为线上。
                </p>
              </section>
            </el-collapse-item>
          </el-collapse>
        </section>
        <!--
          A27：新增和编辑共用的导入区。
          整块就是拖拽投放区：从桌面拖 zip 或文件夹进来即可解析。
          新增和编辑都用同一组 ZIP / 目录选择；编辑态不开放 ClawHub 原生包。
        -->
        <section
          v-if="!isExpertScope"
          class="skill-import"
          :class="{ 'skill-import--dragging': dragActive }"
          data-test="skill-import"
          @dragenter="onImportDragEnter"
          @dragover="onImportDragOver"
          @dragleave="onImportDragLeave"
          @drop="onImportDrop"
        >
          <div class="skill-import__head">
            <h3><PackageOpen :size="15" aria-hidden="true" />{{ scope.importTitle }}</h3>
            <span v-if="pendingPackage || pendingClawhub" class="skill-import__staged" data-test="skill-import-staged">
              {{ pendingPackageLabel || (pendingPackage ? `v${pendingPackage.version}` : 'ClawHub') }} ·
              {{ pendingClawhub ? '原生技能包预检结果，等待确认' : formMode === 'edit' ? '已识别，保存后上传' : scope.importPendingPackage }}
            </span>
          </div>
          <p class="field-help" data-test="skill-import-hint">
            {{ formMode === 'edit'
              ? '可将当前技能的 YCPS 包或含图完整包拖入此框，也可点击导入选择 ZIP。系统会读取包内版本和字段填入表单，请核对后保存。'
              : scope.importHint }}
          </p>
          <div v-if="clawhubReport && pendingClawhub" class="skill-import__clawhub-report" data-test="clawhub-conversion-report">
            <p>
              技能名称 {{ clawhubReport.name || pendingClawhub.displayName || pendingClawhub.slug }}；
              <template v-if="pendingClawhub.remote?.sourceKind === 'GITHUB'">GitHub 来源 {{ pendingClawhub.remote.sourceUrl }}；</template>
              <template v-else>原生来源 @{{ pendingClawhub.owner }}/{{ pendingClawhub.slug }}；</template>
              编码 {{ clawhubReport.code || '无法确定' }}；版本 {{ clawhubReport.version || '待补充' }}；
              预检状态 {{ clawhubReport.status === 'IMPORTABLE' ? '可创建草稿' : clawhubReport.status === 'REVIEW_REQUIRED' ? '需上架前核对' : '阻止导入' }}。
              适配器 {{ clawhubReport.adapterVersion }}，来源指纹 {{ clawhubReport.sourceSha256.slice(0, 12) }}。
            </p>
            <span v-if="clawhubReport.status === 'REVIEW_REQUIRED'">
              原生技能运行时权限或依赖需上架前核对；导入后将创建草稿，不会启用或发布。
            </span>
            <p class="field-help" data-test="clawhub-import-no-publish">
              确认仅会创建原生技能草稿并保存完整原包，不会发布到桌面。
            </p>
            <p class="field-help" data-test="clawhub-import-tags">
              导入后自动保存标签：{{ form.tags.join('、') }}。创建后可在编辑页调整。
            </p>
            <p v-if="!clawhubReport.version" class="field-help">
              原包没有版本号；可先导入为待补版本的草稿，补齐版本并完成审核后才可发布。
            </p>
            <ul v-if="clawhubReport.decisions.length || clawhubReport.blockingReasons.length">
              <li v-for="(decision, index) in clawhubReport.decisions" :key="`decision-${index}`">{{ decision }}</li>
              <li v-for="(reason, index) in clawhubReport.blockingReasons" :key="`block-${index}`">{{ reason }}</li>
            </ul>
          </div>

          <div class="skill-import__actions">
            <!-- 两个原生 input 都藏在按钮后面，用户只看到一个合并按钮 -->
            <input
              id="skill-import-zip-input"
              ref="importZipInput"
              type="file"
              accept=".ycps.zip,.bundle.zip,.zip,application/zip"
              class="skill-file-input"
              data-test="skill-import-zip"
              :disabled="importingPackage || uploadLocked || formDetailLoading"
              @change="onImportZipPicked"
            />
            <input
              id="skill-import-folder-input"
              ref="importFolderInput"
              type="file"
              webkitdirectory
              directory
              multiple
              class="skill-file-input"
              data-test="skill-import-folder"
              :disabled="importingPackage || uploadLocked || formDetailLoading"
              @change="onImportFolderPicked"
            />
            <input
              v-if="formMode === 'create' && canImportClawhub"
              id="skill-import-clawhub-input"
              ref="importClawhubInput"
              type="file"
              accept=".zip,application/zip"
              class="skill-file-input"
              data-test="skill-import-clawhub"
              :disabled="importingPackage"
              @change="onClawhubZipPicked"
            />
            <input
              v-if="formMode === 'create' && canImportClawhub"
              id="skill-import-clawhub-folder-input"
              ref="importClawhubFolderInput"
              type="file"
              webkitdirectory
              directory
              multiple
              class="skill-file-input"
              data-test="skill-import-clawhub-folder"
              :disabled="importingPackage"
              @change="onClawhubFolderPicked"
            />

            <!--
              分裂按钮 + 它的下拉。菜单放在按钮容器**内部**，
              这样 `position: absolute` 的锚点是按钮本身而不是整个动作区 ——
              否则菜单会按动作区的右边缘定位，和按钮错位。
            -->
            <div class="skill-import__split">
              <el-button
                class="skill-import__main"
                data-test="skill-import-zip-button"
                :loading="importingPackage"
                :disabled="importingPackage || uploadLocked || formDetailLoading"
                @click="pickImportZip"
              >
                <Upload :size="15" aria-hidden="true" />
                {{ scope.importButton }}
              </el-button>
              <el-button
                class="skill-import__toggle"
                data-test="skill-import-toggle"
                :disabled="importingPackage || uploadLocked || formDetailLoading"
                :aria-expanded="importMenuOpen"
                aria-haspopup="menu"
                @click="toggleImportMenu"
              >
                <ChevronDown :size="15" aria-hidden="true" />
              </el-button>

              <!-- 下拉菜单：与参考图一致，顶部一条安全提示 + 两个导入方式 -->
              <div v-if="importMenuOpen" class="skill-import__menu" role="menu" data-test="skill-import-menu">
                <p class="skill-import__notice" data-test="skill-import-notice">
                  {{ scope.importSafetyNotice }}
                </p>
                <button type="button" class="skill-import__item" role="menuitem" data-test="skill-import-menu-zip" @click="pickImportZip">
                  <Upload :size="16" aria-hidden="true" />
                  {{ scope.importPickZip }}
                </button>
                <button type="button" class="skill-import__item" role="menuitem" data-test="skill-import-menu-folder" @click="pickImportFolder">
                  <FolderOpen :size="16" aria-hidden="true" />
                  {{ scope.importPickFolder }}
                </button>
                <button v-if="formMode === 'create' && canImportClawhub" type="button" class="skill-import__item" role="menuitem" data-test="skill-import-menu-clawhub" @click="pickImportClawhub">
                  <PackageOpen :size="16" aria-hidden="true" />
                  {{ scope.importPickClawhub }}
                </button>
                <button v-if="formMode === 'create' && canImportClawhub" type="button" class="skill-import__item" role="menuitem" data-test="skill-import-menu-clawhub-folder" @click="pickImportClawhubFolder">
                  <FolderOpen :size="16" aria-hidden="true" />
                  {{ scope.importPickClawhubFolder }}
                </button>
              </div>
            </div>
            <span v-if="importingPackage" class="skill-import__busy" data-test="skill-import-busy">
              {{ scope.importing }}
            </span>
          </div>

          <div v-if="formMode === 'create' && canImportClawhub" class="skill-import__remote" data-test="skill-import-remote-panel">
            <label class="skill-import__remote-label" for="skill-import-remote-url">远程链接（ClawHub / GitHub）</label>
            <div class="skill-import__remote-row">
              <el-input
                id="skill-import-remote-url"
                v-model="remoteImportUrl"
                data-test="skill-import-remote-url"
                aria-label="ClawHub 或 GitHub 技能链接"
                placeholder="粘贴 ClawHub 技能页或 GitHub 技能目录链接"
                :disabled="submitting"
                @input="onRemoteImportUrlChanged"
                @keydown.enter.prevent="fetchRemoteImport"
              />
              <el-button
                type="primary"
                data-test="skill-import-remote-fetch"
                :loading="remoteImportLoading"
                :disabled="submitting || !remoteImportUrl.trim()"
                @click="fetchRemoteImport"
              >拉取预检</el-button>
            </div>
            <p class="field-help">拉取后可核对和修改名称、简介与说明；确认前不会创建技能。</p>
            <p v-if="remoteImportError" class="field-help field-help-error" role="alert" data-test="skill-import-remote-error">{{ remoteImportError }}</p>
          </div>

          <!-- 拖拽悬停时覆盖整块的提示 -->
          <div v-if="dragActive" class="skill-import__drop-hint" data-test="skill-import-drop-hint">
            <Upload :size="18" aria-hidden="true" />
            {{ scope.importDropActive }}
          </div>
        </section>

        <template v-if="!isExpertScope">
        <!--
          分步导航。每屏只面对一组相关字段，避免 16 个字段一次铺开。
          步骤可点击直接跳转：编辑时常见的是「只改某一项」，不必逐步点过去。
        -->
        <el-steps
          :active="formStep"
          align-center
          finish-status="success"
          class="skill-form__steps"
          data-test="skill-form-steps"
        >
          <el-step
            v-for="(step, index) in FORM_STEPS"
            :key="step.key"
            :title="step.title"
            class="skill-form__step-head"
            @click="goToFormStep(index)"
          >
            <template #description>
              <span class="skill-form__step-hint">{{ step.hint }}</span>
            </template>
          </el-step>
        </el-steps>

        <!-- 第 1 步：基础信息。编码、中英文名称与分类是客户端识别条目的依据，必须先定下来。 -->
        <div v-show="formStep === 0" class="skill-form__step" data-test="skill-form-step-identity">
        <section class="skill-form__section">
          <h3>基础信息</h3>
          <div class="admin-form-grid">
            <el-form-item class="is-full" :label="scope.typeFieldLabel" required>
              <el-radio-group
                v-model="form.kind"
                data-test="skill-form-kind"
                :disabled="formMode === 'edit' || Boolean(clawhubReport && pendingClawhub)"
                class="skill-kind-radio"
              >
                <el-radio-button v-for="option in formKindOptions" :key="option" :value="option">
                  {{ KIND_LABELS[option] }}
                </el-radio-button>
              </el-radio-group>
              <small class="field-help" data-test="skill-form-kind-help">
                <template v-if="formMode === 'edit'">
                  {{ scope.kindHelpEdit }}
                </template>
                <template v-else>
                  {{ scope.kindHelpCreate }}
                </template>
              </small>
            </el-form-item>
            <el-form-item :label="scope.codeLabel" required>
              <el-input
                v-model="form.code"
                data-test="skill-form-code"
                :readonly="formMode === 'edit' || Boolean(clawhubReport && pendingClawhub)"
                maxlength="64"
                placeholder="建议小写 kebab，例如 data-analyst"
                @input="scheduleCodeAvailabilityCheck"
              />
              <small class="field-help">
                只能由小写字母和数字组成，单词之间用连字符（-）分隔，例如 data-analyst；契约正则 ^[a-z0-9]+(?:-[a-z0-9]+)*$
              </small>
              <!-- A22：编码全局唯一，占用情况在上传前就告知，并指明占用者的类型与状态 -->
              <small
                v-if="codeAvailabilityLoading"
                class="field-help"
                data-test="skill-form-code-checking"
              >
                正在检查编码是否可用…
              </small>
              <small
                v-else-if="codeAvailability && !codeAvailability.available"
                class="field-help field-help-error"
                data-test="skill-form-code-taken"
              >
                编码已被一个{{ codeHolderLabel(codeAvailability.occupiedBy) }}占用，请换一个编码。
              </small>
              <small
                v-else-if="codeAvailability && codeAvailability.available"
                class="field-help"
                data-test="skill-form-code-available"
              >
                编码可用。
              </small>
            </el-form-item>
            <el-form-item :label="scope.nameLabel" required>
              <el-input v-model="form.name" data-test="skill-form-name" maxlength="128" placeholder="例如：数据分析助手" />
            </el-form-item>
            <el-form-item :label="scope.nameLabelEn">
              <el-input v-model="form.nameEn" data-test="skill-form-name-en" maxlength="128" placeholder="例如：Data Analyst" />
            </el-form-item>
            <el-form-item label="分类">
              <el-select
                v-model="form.category"
                data-test="skill-form-category"
                filterable
                allow-create
                clearable
                default-first-option
                placeholder="例如：数据分析"
              >
                <el-option v-for="category in categorySelectOptions" :key="category" :label="category" :value="category" />
              </el-select>
              <small v-if="expertCardView" class="field-help" data-test="skill-form-category-help">
                二级分类决定卡片在列表中的分组，请从受控词表中选取。
              </small>
            </el-form-item>
          </div>
        </section>
        </div>

        <!-- 第 2 步：展示与说明。客户端列表与详情里会读到的文案与图标。 -->
        <div v-show="formStep === 1" class="skill-form__step" data-test="skill-form-step-presentation">
        <section class="skill-form__section">
          <h3>展示与说明</h3>
          <div class="admin-form-grid">
            <el-form-item label="标签">
              <el-select
                v-model="form.tags"
                data-test="skill-form-tags"
                multiple
                filterable
                allow-create
                default-first-option
                :multiple-limit="16"
                :disabled="Boolean(clawhubReport && pendingClawhub)"
                placeholder="最多 16 个，每个不超过 64 字符"
              />
            </el-form-item>
            <el-form-item label="徽标文本（可填 emoji）">
              <el-input v-model="form.icon" data-test="skill-form-icon" maxlength="8" placeholder="例如 📊" />
            </el-form-item>
            <el-form-item label="排序权重">
              <el-input-number v-model="form.sortWeight" data-test="skill-form-sort" :min="0" :max="9999" />
            </el-form-item>
            <el-form-item class="is-full" :label="scope.summaryLabel" required>
              <el-input
                v-model="form.summary"
                data-test="skill-form-summary"
                maxlength="512"
                show-word-limit
                :placeholder="scope.namePlaceholder"
              />
            </el-form-item>
            <el-form-item class="is-full" :label="scope.summaryLabelEn">
              <el-input
                v-model="form.summaryEn"
                data-test="skill-form-summary-en"
                maxlength="512"
                show-word-limit
                placeholder="One-line summary shown to English clients"
              />
            </el-form-item>
            <el-form-item class="is-full" label="详细说明（中文）">
              <el-input
                v-model="form.description"
                data-test="skill-form-description"
                type="textarea"
                :rows="4"
                :maxlength="descriptionMaxLength"
                show-word-limit
                resize="none"
              />
            </el-form-item>
            <el-form-item class="is-full" label="详细说明（英文，可选）">
              <el-input
                v-model="form.descriptionEn"
                data-test="skill-form-description-en"
                type="textarea"
                :rows="4"
                :maxlength="descriptionMaxLength"
                show-word-limit
                resize="none"
                placeholder="Client falls back to the Chinese text when this is empty"
              />
            </el-form-item>
          </div>
        </section>
        </div>

        <!-- 第 3 步：客户端与资源包。适配版本与包体，属于交付环节。 -->
        <div v-show="formStep === 2" class="skill-form__step" data-test="skill-form-step-delivery">
        <section class="skill-form__section">
          <h3><ImagePlus :size="15" aria-hidden="true" />图片图标（可选）</h3>
          <p class="field-help">PNG / JPG / WebP / SVG，不超过 4 MB。存在图片时客户端优先显示图片，其次显示徽标文本。</p>
          <div class="skill-icon-row">
            <!--
              预览优先级：新选的本地文件 > 已上传的图标 > 文本徽标 > 名称首字。
              已上传的图标此前只显示文本，管理员看不出线上到底是什么样子。
              标记为待清空时不再回显已上传的图，让预览与「这次保存之后的样子」一致。
            -->
            <SkillAvatar
              :src="iconPendingClear ? '' : (iconPreviewUrl || iconPreviews.iconUrlFor(formSkill?.id ?? ''))"
              :badge="form.icon"
              :fallback="form.name.trim().slice(0, 1).toUpperCase()"
              size="lg"
            />
            <div class="skill-icon-actions">
              <input
                type="file"
                accept="image/png,image/jpeg,image/webp,image/svg+xml"
                class="skill-file-input"
                data-test="skill-icon-file"
                @change="onIconPicked"
              />
              <el-button v-if="iconPreviewUrl" size="small" data-test="skill-icon-clear" @click="clearIcon">
                清除选择
              </el-button>
              <!--
                已上传图片的操作组：下载是只读的，清空是破坏性的，两者都只在确实有图时出现。
                统一样式（small + text）与「清除选择」保持协调，避免按钮看起来像另一套体系。
              -->
              <el-button
                v-if="formSkill?.iconUrl"
                size="small"
                text
                data-test="skill-icon-download"
                :loading="iconDownloading"
                @click="downloadUploadedIcon"
              >
                <Download :size="14" aria-hidden="true" />
                下载
              </el-button>
              <!--
                「清空」不再立即删除：只置标记，点「保存修改」时才真正提交
                （提交前还会弹一次图片改动确认）。误点可直接撤销。
              -->
              <el-button
                v-if="formSkill?.iconUrl && !iconPendingClear"
                size="small"
                type="danger"
                text
                data-test="skill-icon-delete"
                @click="markIconClear"
              >
                清空已上传图标
              </el-button>
              <el-button
                v-if="formSkill?.iconUrl && iconPendingClear"
                size="small"
                text
                data-test="skill-icon-undo-clear"
                @click="undoIconClear"
              >
                <Undo2 :size="14" aria-hidden="true" />
                撤销清空
              </el-button>
            </div>
          </div>
          <p v-if="iconPendingClear" class="skill-pending-change" data-test="skill-icon-pending">
            图标已标记为清空，点「保存修改」后生效；在此之前随时可以撤销。
          </p>
        </section>

        <!--
          介绍插画是专家详情页的展示素材，只有专家/专家团有消费方；
          技能管理页不展示这一栏（上传了也没有地方显示）。见 showIllustration。
        -->
        <section
          v-if="showIllustration"
          class="skill-form__section"
          data-test="skill-form-illustration"
        >
          <h3><ImagePlus :size="15" aria-hidden="true" />介绍插画（可选上传）</h3>
          <p class="field-help">
            PNG / JPG / WebP / SVG，不超过 4 MB。每个资源包最多一张，用于客户端详情页的展示插画；
            不上传不影响任何功能。
          </p>
          <div class="skill-icon-row">
            <!-- 预览优先级：新选的本地文件 > 已上传的插画 > 未上传占位；待清空时不回显已上传的图 -->
            <div class="skill-illustration-frame" data-test="skill-illustration-preview">
              <img
                v-if="illustrationPreviewUrl || (!illustrationPendingClear && illustrationPreviews.imageUrlFor(formSkill?.id ?? ''))"
                :src="illustrationPreviewUrl || illustrationPreviews.imageUrlFor(formSkill?.id ?? '')"
                alt="介绍插画预览"
              />
              <span v-else class="skill-illustration-empty">未上传</span>
            </div>
            <div class="skill-icon-actions">
              <input
                type="file"
                accept="image/png,image/jpeg,image/webp,image/svg+xml"
                class="skill-file-input"
                data-test="skill-illustration-file"
                @change="onIllustrationPicked"
              />
              <el-button
                v-if="illustrationPreviewUrl"
                size="small"
                data-test="skill-illustration-clear"
                @click="clearIllustration"
              >
                清除选择
              </el-button>
              <!-- 「清除选择」只丢本地待上传的文件；已上传的插画要单独下载 / 清空。 -->
              <el-button
                v-if="formSkill?.illustrationUrl"
                size="small"
                text
                data-test="skill-illustration-download"
                :loading="illustrationDownloading"
                @click="downloadUploadedIllustration"
              >
                <Download :size="14" aria-hidden="true" />
                下载
              </el-button>
              <!-- 同图标：清空只置标记，点「保存修改」时才提交，期间可撤销 -->
              <el-button
                v-if="formSkill?.illustrationUrl && !illustrationPendingClear"
                size="small"
                type="danger"
                text
                data-test="skill-illustration-delete"
                @click="markIllustrationClear"
              >
                清空已上传插画
              </el-button>
              <el-button
                v-if="formSkill?.illustrationUrl && illustrationPendingClear"
                size="small"
                text
                data-test="skill-illustration-undo-clear"
                @click="undoIllustrationClear"
              >
                <Undo2 :size="14" aria-hidden="true" />
                撤销清空
              </el-button>
            </div>
          </div>
          <p
            v-if="illustrationPendingClear"
            class="skill-pending-change"
            data-test="skill-illustration-pending"
          >
            介绍插画已标记为清空，点「保存修改」后生效；在此之前随时可以撤销。
          </p>
        </section>

        <section
          v-if="showExampleImage"
          class="skill-form__section"
          data-test="skill-form-example-image"
        >
          <h3><ImagePlus :size="15" aria-hidden="true" />示例图片（可选上传）</h3>
          <p class="field-help">
            PNG / JPG / WebP / SVG，不超过 4 MB。每个专家最多一张，用于客户端展示专家功能或输出效果；
            不上传不影响其他功能。
          </p>
          <div class="skill-icon-row">
            <div class="skill-example-image-frame" data-test="skill-example-image-preview">
              <img
                v-if="exampleImagePreviewUrl || (!exampleImagePendingClear && exampleImagePreviews.imageUrlFor(formSkill?.id ?? ''))"
                :src="exampleImagePreviewUrl || exampleImagePreviews.imageUrlFor(formSkill?.id ?? '')"
                alt="示例图片预览"
              />
              <span v-else class="skill-illustration-empty">未上传</span>
            </div>
            <div class="skill-icon-actions">
              <input
                type="file"
                accept="image/png,image/jpeg,image/webp,image/svg+xml"
                class="skill-file-input"
                data-test="skill-example-image-file"
                @change="onExampleImagePicked"
              />
              <el-button
                v-if="exampleImagePreviewUrl"
                size="small"
                data-test="skill-example-image-clear"
                @click="clearExampleImage"
              >
                清除选择
              </el-button>
              <el-button
                v-if="formSkill?.exampleImageUrl"
                size="small"
                text
                data-test="skill-example-image-download"
                :loading="exampleImageDownloading"
                @click="downloadUploadedExampleImage"
              >
                <Download :size="14" aria-hidden="true" />
                下载
              </el-button>
              <el-button
                v-if="formSkill?.exampleImageUrl && !exampleImagePendingClear"
                size="small"
                type="danger"
                text
                data-test="skill-example-image-delete"
                @click="markExampleImageClear"
              >
                清空已上传示例图片
              </el-button>
              <el-button
                v-if="formSkill?.exampleImageUrl && exampleImagePendingClear"
                size="small"
                text
                data-test="skill-example-image-undo-clear"
                @click="undoExampleImageClear"
              >
                <Undo2 :size="14" aria-hidden="true" />
                撤销清空
              </el-button>
            </div>
          </div>
          <p
            v-if="exampleImagePendingClear"
            class="skill-pending-change"
            data-test="skill-example-image-pending"
          >
            示例图片已标记为清空，点「保存修改」后生效；在此之前随时可以撤销。
          </p>
        </section>

        <section class="skill-form__section">
          <h3>客户端适配</h3>
          <div class="admin-form-grid">
            <el-form-item label="最低客户端版本" required>
              <el-input
                v-model="form.minClientVersion"
                data-test="skill-form-min-client"
                placeholder="2026.6.0"
              />
              <small class="field-help">格式 2026.6.0 或 2026.6；客户端低于该版本时不可见。</small>
            </el-form-item>
          </div>
        </section>

        <section v-if="formMode === 'edit' && formSkill" class="skill-form__section" data-test="skill-form-package">
          <h3><Package :size="15" aria-hidden="true" />资源包</h3>

          <div v-if="formPublishedVersion" class="skill-version-card" data-test="skill-current-version">
            <div><span>当前线上版本</span><strong>v{{ formPublishedVersion.version }} · revision {{ formPublishedVersion.revision }}</strong></div>
            <div><span>大小</span><strong>{{ humanizeBytes(formPublishedVersion.sizeBytes) }}</strong></div>
            <div><span>MD5（诊断）</span><strong><code>{{ formPublishedVersion.md5 || '—' }}</code></strong></div>
            <div>
              <span>Manifest SHA-256</span>
              <strong>
                <code :title="formPublishedVersion.manifestSha256 || ''" data-test="skill-current-version-sha256">
                  {{ shortDigest(formPublishedVersion.manifestSha256 || formPublishedVersion.sha256) }}
                </code>
              </strong>
            </div>
            <div><span>封存</span><strong>{{ formPublishedVersion.sealed ? '已封存' : '未封存' }}</strong></div>
            <div><span>扫描状态</span><strong>{{ scanLabel(formPublishedVersion.scanStatus) }}</strong></div>
            <div><span>上传时间</span><strong>{{ formatDateTime(formPublishedVersion.createdAt) }}</strong></div>
          </div>
          <p v-else class="field-help" data-test="skill-no-version">{{ scope.noReadyVersionHint }}</p>

          <p
            v-if="formPublishedVersion?.sealed"
            class="field-help"
            data-test="skill-replace-sealed-hint"
          >
            线上版本已封存，不能原地替换；请从上方导入更高版本的 YCPS 包（例如 1.3.1）。
          </p>
          <p v-if="!pendingPackage" class="field-help" data-test="skill-package-import-hint">
            从上方拖入或选择资源包后，这里会显示识别出的版本信息。
          </p>
          <div v-if="pendingPackage" class="admin-form-grid skill-version-fields">
            <el-form-item label="版本号">
              <el-input
                v-model="versionForm.version"
                data-test="skill-version-number"
                readonly
              />
            </el-form-item>
            <el-form-item label="最低客户端版本（可选）">
              <el-input v-model="versionForm.minClientVersion" data-test="skill-version-min-client" :placeholder="scope.versionInherit" />
            </el-form-item>
            <el-form-item class="is-full" label="更新说明（可选）">
              <el-input v-model="versionForm.changelog" data-test="skill-version-changelog" maxlength="2000" />
            </el-form-item>
          </div>
          <p class="field-help">{{ packageLimitHint(formSkill?.kind) }}</p>

          <div v-if="uploadStatus !== 'idle'" class="skill-upload-progress" data-test="skill-upload-progress">
            <el-progress :percentage="uploadProgress?.percent ?? 0" :status="uploadStatus === 'failed' ? 'exception' : undefined" />
            <span class="skill-upload-phase" data-test="skill-upload-phase">{{ uploadPhaseText }}</span>
            <el-button v-if="uploadLocked" size="small" data-test="skill-upload-cancel" @click="cancelUpload">取消</el-button>
          </div>
        </section>
        </div>
        </template>
      </el-form>

      <template #footer>
        <template v-if="clawhubReport && pendingClawhub">
          <el-button
            type="primary"
            data-test="clawhub-confirm-import"
            :loading="submitting"
            :disabled="importingPackage || uploadLocked || clawhubReport.status === 'BLOCKED' || !clawhubReport.code?.trim() || codeAvailability?.available === false"
            @click="confirmClawhubImport"
          >
            确认导入并创建草稿
          </el-button>
          <el-button
            data-test="clawhub-cancel-import"
            :disabled="submitting || importingPackage"
            @click="cancelClawhubImport"
          >
            取消
          </el-button>
        </template>
        <template v-else>
        <el-button data-test="skill-form-cancel" :disabled="uploadLocked" @click="formVisible = false">取消</el-button>
        <!--
          分步导航。非首屏显示「上一步」，非末屏显示「下一步」作为推荐路径；
          但「提交」始终可用 —— 编辑时常见的是「只改一项就保存」，不该被迫点完三步。
          必填项由校验兜底，且校验失败会自动跳到出错的那一屏。
        -->
        <template v-if="!isExpertScope">
          <el-button
            v-if="formStep > 0"
            data-test="skill-form-prev"
            :disabled="uploadLocked"
            @click="previousFormStep"
          >
            上一步
          </el-button>
          <el-button
            v-if="!isLastFormStep"
            type="primary"
            data-test="skill-form-next"
            :disabled="uploadLocked"
            @click="nextFormStep"
          >
            下一步
          </el-button>
        </template>
        <el-button
          type="primary"
          data-test="skill-form-submit"
          :loading="submitting || iconSaving || illustrationSaving || illustrationDeleting || exampleImageSaving || exampleImageDeleting"
          :disabled="uploadLocked"
          @click="submitForm()"
        >
          {{ isExpertScope ? (formMode === 'create' ? '创建专家' : '保存专家') : (formMode === 'create' ? '创建 Skill' : '保存修改') }}
        </el-button>
        </template>
      </template>
    </el-dialog>

    <!-- 详情与版本管理 -->
    <el-drawer
      v-model="drawerOpen"
      title="Skill 详情与版本"
      size="min(620px, 100%)"
      :before-close="handleDrawerClose"
      @closed="closeDrawer"
    >
      <el-skeleton v-if="drawerLoading" data-test="skill-drawer-loading" :rows="6" animated />

      <div v-else-if="selected" class="skill-drawer" data-test="skill-drawer">
        <header class="skill-drawer__head">
          <SkillAvatar
            :src="iconPreviews.iconUrlFor(selected.id)"
            :code="selected.kind === 'SKILL' ? selected.code : ''"
            :badge="skillIcon(selected)"
            :fallback="skillInitial(selected)"
            size="md"
          />
          <div class="skill-drawer__identity">
            <strong>{{ selected.name }}</strong>
            <small v-if="selected.nameEn" class="skill-cell__name-en" data-test="skill-drawer-name-en">
              {{ selected.nameEn }}
            </small>
            <code>{{ selected.code }}</code>
          </div>
          <div class="skill-drawer__tags">
            <el-tag
              v-if="kindCode(selected)"
              size="small"
              :type="kindTagType(kindCode(selected))"
              :data-test="`skill-drawer-kind-${selected.kind}`"
            >
              {{ kindLabel(kindCode(selected)) }}
            </el-tag>
            <el-tag size="small" :type="statusTagType(selected.status)">{{ statusLabel(selected.status) }}</el-tag>
          </div>
        </header>

        <p v-if="hasPendingVersion" class="skill-pending-hint" data-test="skill-drawer-pending">
          有新版本待发布：最新就绪版本 v{{ selected.latestVersion }}，当前线上 v{{ selected.publishedVersion }}
        </p>
        <div v-if="needsClawhubSourceVersion(selected)" class="skill-pending-hint skill-clawhub-version-missing" data-test="clawhub-version-missing">
          <p>此原生 ClawHub 草稿缺少源版本号；补录严格 SemVer 后，仍须通过安全检查并单独发布。</p>
          <el-button
            size="small"
            type="warning"
            data-test="clawhub-version-fill"
            @click="promptClawhubSourceVersion(selected.id)"
          >
            补版本号
          </el-button>
        </div>
        <dl class="skill-drawer__meta">
          <div><dt>分类</dt><dd>{{ selected.category || '未分类' }}</dd></div>
          <div><dt>标签</dt><dd>{{ (selected.tags || []).join('、') || '—' }}</dd></div>
          <div><dt>线上版本</dt><dd>{{ selected.publishedVersion ? `v${selected.publishedVersion}` : '—' }}</dd></div>
          <div v-if="selected.sourceFormat === 'CLAWHUB_SKILL'"><dt>上架状态</dt><dd data-test="skill-drawer-publish-state">{{ selected.status === 'PUBLISHED' ? '已上架' : '未上架' }}</dd></div>
          <div v-if="selected.sourceFormat === 'CLAWHUB_SKILL'"><dt>原生包版本</dt><dd data-test="skill-drawer-source-version">{{ selected.sourceVersion || '待补版本号' }}</dd></div>
          <div v-if="selected.sourceFormat === 'CLAWHUB_SKILL'"><dt>原始技能包</dt><dd data-test="skill-drawer-source-package">{{ nativeClawhubVersionsLoading ? '读取中' : nativeClawhubVersionsError ? '读取失败' : `${nativeClawhubVersions.length} 个版本` }}</dd></div>
          <div v-if="selected.sourceFormat === 'CLAWHUB_SKILL'"><dt>源包扫描</dt><dd data-test="skill-drawer-source-scan">{{ scanLabel(selected.sourceScanStatus || 'PENDING') }}</dd></div>
          <div><dt>最低客户端</dt><dd>{{ selected.minClientVersion || '不限' }}</dd></div>
          <div><dt>大小</dt><dd>{{ formatBytes(selected.sizeBytes) }}</dd></div>
          <div><dt>下载次数</dt><dd>{{ selected.downloadCount }}</dd></div>
          <div v-if="selected.kind === 'TEAM'"><dt>成员数</dt><dd data-test="skill-drawer-member-count">{{ members.length }}</dd></div>
          <div><dt>排序权重</dt><dd>{{ selected.sortWeight }}</dd></div>
          <div><dt>更新时间</dt><dd>{{ formatDateTime(selected.updatedAt) }}</dd></div>
        </dl>

        <p class="skill-drawer__summary">{{ selected.summary || '暂无简介' }}</p>
        <p v-if="selected.summaryEn" class="skill-drawer__summary skill-drawer__i18n" data-test="skill-drawer-summary-en">
          {{ selected.summaryEn }}
        </p>
        <p v-if="selected.description" class="skill-drawer__description">{{ selected.description }}</p>
        <p
          v-if="selected.descriptionEn"
          class="skill-drawer__description skill-drawer__i18n"
          data-test="skill-drawer-description-en"
        >
          {{ selected.descriptionEn }}
        </p>

        <section
          v-if="selected.kind === 'SKILL'"
          class="skill-detail-import"
          :class="{ 'skill-detail-import--dragging': detailPackageDragActive }"
          :aria-busy="detailPackageBusy"
          data-test="skill-detail-import"
          @dragenter="onDetailPackageDragEnter"
          @dragover="onDetailPackageDragOver"
          @dragleave="onDetailPackageDragLeave"
          @drop="onDetailPackageDrop"
        >
          <input
            ref="nativeClawhubVersionInput"
            type="file"
            accept=".ycps.zip,.zip,application/zip"
            class="skill-detail-import__input"
            data-test="skill-detail-import-zip"
            :disabled="detailPackageBusy"
            @change="onNativeClawhubVersionPicked"
          />
          <input
            ref="detailPackageFolderInput"
            type="file"
            webkitdirectory
            directory
            multiple
            class="skill-detail-import__input"
            data-test="skill-detail-import-folder"
            :disabled="detailPackageBusy"
            @change="onDetailPackageFolderPicked"
          />
          <div class="skill-detail-import__icon" aria-hidden="true">
            <Upload :size="24" />
          </div>
          <div class="skill-detail-import__copy">
            <h3>拖拽导入技能包</h3>
            <p>
              将 ZIP 或完整文件夹拖到此处。系统会自动识别
              {{ isNativeClawhubSelected ? '原生 ClawHub' : 'YCPS' }} 格式并校验当前技能身份。
            </p>
          </div>
          <div class="skill-detail-import__actions">
            <el-button
              type="primary"
              data-test="skill-detail-import-pick-zip"
              :loading="detailPackagePreparing || nativeClawhubVersionUploading"
              :disabled="detailPackageBusy || (isNativeClawhubSelected && nativeClawhubVersionsLoading)"
              @click="openNativeClawhubVersionPicker"
            >
              <Upload :size="15" aria-hidden="true" />
              选择 ZIP
            </el-button>
            <el-button
              data-test="skill-detail-import-pick-folder"
              :disabled="detailPackageBusy || (isNativeClawhubSelected && nativeClawhubVersionsLoading)"
              @click="openDetailPackageFolderPicker"
            >
              <FolderOpen :size="15" aria-hidden="true" />
              选择文件夹
            </el-button>
          </div>
          <p class="skill-detail-import__notice">
            {{ isNativeClawhubSelected
              ? '仅接收同一发布者与技能标识的 ClawHub 源包；新上传的版本不会自动替换线上版本，可在版本列表手动切换。'
              : '包内 code 与 kind 必须匹配当前 Skill；同版本仅可替换未封存版本，已封存版本请提升 SemVer。' }}
          </p>
          <div v-if="uploadStatus !== 'idle'" class="skill-upload-progress" data-test="skill-detail-import-progress">
            <el-progress
              :percentage="uploadProgress?.percent ?? 0"
              :status="uploadStatus === 'failed' ? 'exception' : uploadStatus === 'done' ? 'success' : undefined"
            />
            <span class="skill-upload-phase" data-test="skill-detail-import-phase">{{ uploadPhaseText }}</span>
            <el-button v-if="uploadLocked" size="small" data-test="skill-detail-import-cancel" @click="cancelUpload">取消</el-button>
          </div>
          <div v-if="detailPackageDragActive" class="skill-detail-import__drop-hint" data-test="skill-detail-import-drop-hint">
            <Upload :size="18" aria-hidden="true" />
            松开即可预检并导入
          </div>
        </section>

        <SkillPortableImportPanel
          v-if="selected.kind === 'EXPERT'"
          :skill="selected"
          @committed="onPortableImportCommitted"
        />

        <section v-if="selected.kind === 'TEAM'" class="skill-members" data-test="skill-roster">
          <div class="skill-members__head">
            <h3 class="skill-drawer__section">
              <Users :size="15" aria-hidden="true" />
              专家团成员（{{ roster.length }}）
            </h3>
            <div class="skill-members__actions">
              <el-button size="small" data-test="skill-member-add" @click="addMember">添加成员</el-button>
              <el-button
                size="small"
                type="primary"
                data-test="skill-members-save"
                :loading="membersSaving"
                :disabled="!rosterDirty"
                @click="saveRoster"
              >
                保存花名册
              </el-button>
            </div>
          </div>
          <p class="field-help" data-test="skill-roster-hint">
            编辑后点击「保存花名册」提交；一个专家团最多一位主理人。
            <template v-if="rosterDirty">当前有未保存的改动。</template>
          </p>

          <div class="table-scroll">
            <table class="admin-table skill-member-table" data-test="skill-member-table">
              <thead>
                <tr>
                  <th scope="col">头像</th>
                  <th scope="col">成员标识</th>
                  <th scope="col">姓名（中 / 英）</th>
                  <th scope="col">职业（中 / 英）</th>
                  <th scope="col">角色</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(member, index) in roster" :key="index" :data-test="`skill-member-row-${index}`">
                  <td>
                    <!--
                      只读：成员标识就是那个专家包的 code，头像直接读该专家包自己的图标。
                      换头像要去那个专家包的表单里换，这里不再单独维护一份。
                    -->
                    <div class="skill-member-avatar" :data-test="`skill-member-avatar-${member.memberKey}`">
                      <span v-if="memberAvatarUrl(member.memberKey)" class="skill-member-avatar__image">
                        <img :src="memberAvatarUrl(member.memberKey)" :alt="`${member.memberKey} 头像`" />
                      </span>
                      <span v-else class="skill-member-avatar__placeholder" aria-hidden="true">
                        {{ memberInitial(member) }}
                      </span>
                    </div>
                  </td>
                  <td>
                    <!--
                      成员只能来自已上架专家：选项即专家 code，选中后头像自动读该专家包的图标。
                      allow-create 保留手输兜底（选项尚未加载时），后端仍会强校验。
                    -->
                    <el-select
                      v-model="member.memberKey"
                      size="small"
                      filterable
                      allow-create
                      default-first-option
                      placeholder="选择已上架专家"
                      :data-test="`skill-member-key-${index}`"
                    >
                      <el-option
                        v-for="expert in expertOptions"
                        :key="expert.code"
                        :label="`${expert.name}（${expert.code}）`"
                        :value="expert.code"
                      />
                    </el-select>
                  </td>
                  <td>
                    <el-input
                      v-model="member.name"
                      size="small"
                      maxlength="64"
                      placeholder="中文名"
                      :data-test="`skill-member-name-${index}`"
                    />
                    <el-input
                      v-model="member.nameEn"
                      size="small"
                      maxlength="64"
                      placeholder="English name"
                      class="skill-member-field"
                      :data-test="`skill-member-name-en-${index}`"
                    />
                  </td>
                  <td>
                    <el-input
                      v-model="member.profession"
                      size="small"
                      maxlength="64"
                      placeholder="中文职业"
                      :data-test="`skill-member-profession-${index}`"
                    />
                    <el-input
                      v-model="member.professionEn"
                      size="small"
                      maxlength="64"
                      placeholder="English profession"
                      class="skill-member-field"
                      :data-test="`skill-member-profession-en-${index}`"
                    />
                  </td>
                  <td>
                    <el-select
                      v-model="member.role"
                      size="small"
                      :data-test="`skill-member-role-${index}`"
                      @change="onRoleChange(index)"
                    >
                      <el-option
                        label="主理人 LEAD"
                        value="LEAD"
                        :disabled="leadTakenByRow(index)"
                      />
                      <el-option label="成员 MEMBER" value="MEMBER" />
                    </el-select>
                    <el-tag
                      v-if="member.role === 'LEAD'"
                      size="small"
                      type="warning"
                      class="skill-member-lead-tag"
                      :data-test="`skill-member-lead-${index}`"
                    >
                      LEAD
                    </el-tag>
                  </td>
                  <td>
                    <div class="skill-row-actions">
                      <el-button
                        size="small"
                        text
                        :disabled="index === 0"
                        :data-test="`skill-member-up-${index}`"
                        @click="moveMember(index, -1)"
                      >
                        上移
                      </el-button>
                      <el-button
                        size="small"
                        text
                        :disabled="index === roster.length - 1"
                        :data-test="`skill-member-down-${index}`"
                        @click="moveMember(index, 1)"
                      >
                        下移
                      </el-button>
                      <el-button
                        size="small"
                        text
                        type="danger"
                        :data-test="`skill-member-remove-${index}`"
                        @click="removeMemberRow(index)"
                      >
                        删除
                      </el-button>
                    </div>
                  </td>
                </tr>
                <tr v-if="!roster.length">
                  <td class="admin-empty" colspan="6" data-test="skill-members-empty">
                    还没有成员。可从包内配置生成初始成员，或在此手动添加。
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section v-if="!isNativeClawhubSelected" class="skill-manifest" data-test="skill-manifest">
          <el-collapse v-model="manifestOpen">
            <el-collapse-item name="manifest">
              <template #title>
                <span class="skill-manifest__title" data-test="skill-manifest-toggle">
                  包内清单（plugin.json，只读）
                </span>
              </template>
              <p class="field-help">
                包内清单仅供追溯，管理端不提供编辑入口：
                {{ scope.manifestHelp }}
              </p>
              <pre v-if="manifestText" class="skill-manifest__body" data-test="skill-manifest-body">{{ manifestText }}</pre>
              <p v-else class="field-help" data-test="skill-manifest-empty">
                {{ scope.manifestEmpty }}
              </p>
            </el-collapse-item>
          </el-collapse>
        </section>

        <section
          v-if="isNativeClawhubSelected"
          class="skill-native-clawhub-versions"
          data-test="clawhub-version-list"
        >
          <div class="skill-native-clawhub-versions__head">
            <div>
              <h3 class="skill-drawer__section">原生技能版本列表（{{ nativeClawhubVersions.length }}）</h3>
              <p class="field-help">每个版本保留原始 ClawHub ZIP。桌面端下次同步可看到线上版本；更新安装由用户自主决定。</p>
            </div>
          </div>
          <el-skeleton v-if="nativeClawhubVersionsLoading" :rows="3" animated data-test="clawhub-version-loading" />
          <div v-else-if="nativeClawhubVersionsError" class="skill-preview-error" role="alert" data-test="clawhub-version-error">
            <span>原始技能包读取失败：{{ nativeClawhubVersionsError }}</span>
            <el-button size="small" data-test="clawhub-version-retry" @click="loadNativeClawhubVersions(selected.id)">重试</el-button>
          </div>
          <div v-else class="table-scroll">
            <table class="admin-table skill-version-table" data-test="clawhub-version-table">
              <thead>
                <tr>
                  <th scope="col">版本</th>
                  <th scope="col">来源</th>
                  <th scope="col">大小</th>
                  <th scope="col">SHA-256</th>
                  <th scope="col">安全扫描</th>
                  <th scope="col">状态</th>
                  <th scope="col">创建时间</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="version in nativeClawhubVersions"
                  :key="version.id"
                  :data-test="`clawhub-version-row-${version.id}`"
                >
                  <td><strong>{{ version.version ? `v${version.version}` : '未标版本' }}</strong></td>
                  <td><code>@{{ version.owner }}/{{ version.slug }}</code></td>
                  <td class="admin-number">{{ humanizeBytes(version.sizeBytes) }}</td>
                  <td>
                    <code class="skill-digest" :title="version.sourceSha256">
                      {{ shortDigest(version.sourceSha256) }}
                    </code>
                  </td>
                  <td>
                    <el-tag size="small" :type="scanTagType(version.scanStatus)">
                      {{ scanLabel(version.scanStatus) }}
                    </el-tag>
                  </td>
                  <td>
                    <el-tag
                      v-if="isCurrentOnlineNativeClawhubVersion(version)"
                      size="small"
                      type="success"
                    >当前线上</el-tag>
                    <span v-else>{{ nativeClawhubVersionStatusLabel(version) }}</span>
                  </td>
                  <td>{{ formatDateTime(version.createdAt) }}</td>
                  <td>
                    <div class="skill-row-actions">
                      <el-button
                        size="small"
                        text
                        type="primary"
                      :loading="downloadingVersionId === version.id"
                      :data-test="`clawhub-version-download-${version.id}`"
                      title="导出此版本的 ClawHub 原生 ZIP（.zip）"
                      @click="downloadNativeClawhubArchive(version)"
                    >
                        导出 ClawHub ZIP（.zip）
                      </el-button>
                      <el-button
                        v-if="version.status === 'DRAFT' && ['PENDING', 'FAILED'].includes(version.scanStatus)"
                        size="small"
                        text
                        type="warning"
                        :loading="nativeClawhubVersionRescanningId === version.id"
                        :data-test="`clawhub-version-rescan-${version.id}`"
                        @click="rescanNativeClawhubVersion(version)"
                      >
                        重新扫描
                      </el-button>
                      <el-button
                      v-if="!isCurrentOnlineNativeClawhubVersion(version)"
                        size="small"
                        text
                        type="primary"
                        :disabled="!canPublishNativeClawhubVersion(version)"
                        :loading="versionActionId === version.id"
                        :title="canPublishNativeClawhubVersion(version) ? '' : '需有效 SemVer 且安全扫描通过后才能上架'"
                        :data-test="`clawhub-version-publish-${version.id}`"
                        @click="publishNativeClawhubVersion(version)"
                      >
                        设为线上
                      </el-button>
                      <el-button
                        size="small"
                        text
                        type="danger"
                        :disabled="!canDeleteNativeClawhubVersion(version)"
                        :loading="versionActionId === version.id"
                        :title="canDeleteNativeClawhubVersion(version) ? '' : '仅未上架过且非当前线上的草稿可删除'"
                        :data-test="`clawhub-version-delete-${version.id}`"
                        @click="deleteNativeClawhubVersion(version)"
                      >
                        删除
                      </el-button>
                    </div>
                  </td>
                </tr>
                <tr v-if="!nativeClawhubVersions.length">
                  <td class="admin-empty" colspan="8" data-test="clawhub-version-empty">
                    暂无可管理的原生 ClawHub 版本记录。
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <template v-else>
        <h3 class="skill-drawer__section">版本列表（{{ versions.length }}）</h3>
        <div class="table-scroll">
          <table class="admin-table skill-version-table" data-test="skill-version-table">
            <thead>
              <tr>
                <th scope="col">版本</th>
                <th scope="col">revision</th>
                <th scope="col">大小</th>
                <th scope="col">格式</th>
                <th scope="col">摘要</th>
                <th scope="col">封存</th>
                <th scope="col">扫描</th>
                <th scope="col">线上</th>
                <th scope="col">下载</th>
                <th scope="col">创建时间</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="version in versions" :key="version.id" :data-test="`skill-version-row-${version.version}`">
                <td><strong>v{{ version.version }}</strong></td>
                <td class="admin-number">{{ version.revision }}</td>
                <td class="admin-number">{{ humanizeBytes(version.sizeBytes) }}</td>
                <td>
                  <el-tag
                    size="small"
                    :type="version.packageFormat === 'YCPS' ? 'success' : 'info'"
                    :data-test="`skill-version-format-${version.version}`"
                  >
                    {{ version.packageFormat === 'YCPS'
                      ? `YCPS ${version.ycpsVersion || ''}`.trim()
                      : version.packageFormat === 'CLAWHUB_SKILL' ? '原生 ClawHub' : (version.packageFormat || 'Legacy') }}
                  </el-tag>
                  <small v-if="version.packageKind" class="admin-muted skill-version-kind">
                    {{ kindLabel(version.packageKind) }}
                  </small>
                </td>
                <td>
                  <code
                    class="skill-digest"
                    :title="version.manifestSha256 || version.sha256 || ''"
                    :data-test="`skill-version-manifest-${version.version}`"
                  >
                    {{ shortDigest(version.manifestSha256 || version.sha256) }}
                  </code>
                </td>
                <td>
                  <el-tag
                    v-if="version.sealed"
                    size="small"
                    type="warning"
                    :data-test="`skill-version-sealed-${version.version}`"
                  >
                    已封存
                  </el-tag>
                  <span v-else class="admin-muted">—</span>
                </td>
                <td>
                  <el-tag size="small" :type="scanTagType(version.scanStatus)">{{ scanLabel(version.scanStatus) }}</el-tag>
                </td>
                <td>
                  <el-tag v-if="isOnlineVersion(version)" size="small" type="success">线上</el-tag>
                  <span v-else class="admin-muted">{{ versionStatusLabel(version.status) }}</span>
                </td>
                <td class="admin-number">{{ version.downloadCount }}</td>
                <td>{{ formatDateTime(version.createdAt) }}</td>
                <td>
                  <div class="skill-row-actions">
                    <el-button
                      size="small"
                      text
                      type="primary"
                      :data-test="`skill-version-review-${version.version}`"
                      @click="openVersionReview(version)"
                    >
                      审核详情
                    </el-button>
                    <el-button
                      size="small"
                      text
                      type="primary"
                      :loading="downloadingVersionId === version.id"
                      :title="scope.downloadVersionTitle"
                      :data-test="`skill-version-download-${version.version}`"
                      @click="downloadPackage(version)"
                    >
                      {{ scope.downloadVersion }}
                    </el-button>
                    <el-button
                      v-if="!isOnlineVersion(version) && (version.status === 'READY' || version.status === 'ARCHIVED')"
                      size="small"
                      text
                      type="primary"
                      :loading="versionActionId === version.id"
                      :data-test="`skill-version-publish-${version.version}`"
                      @click="publishVersion(version)"
                    >
                      设为线上
                    </el-button>
                    <el-button
                      v-if="selected.kind === 'EXPERT' && version.packageFormat === 'YCPS' && version.ycpsVersion !== '1.1.0'"
                      size="small"
                      text
                      type="primary"
                      :loading="versionActionId === version.id"
                      :data-test="`skill-version-portable-revision-${version.version}`"
                      @click="generatePortableRevision(version)"
                    >
                      生成 YCPS 1.1 完整包
                    </el-button>
                    <el-button
                      v-if="!isExpertScope"
                      size="small"
                      text
                      :disabled="version.sealed"
                      :title="version.sealed ? '已封存版本不能原地替换，请发布新的 SemVer 版本' : ''"
                      :data-test="`skill-version-replace-${version.version}`"
                      @click="startUpload(version)"
                    >
                      替换包
                    </el-button>
                    <el-button
                      size="small"
                      text
                      type="danger"
                      :disabled="isOnlineVersion(version)"
                      :data-test="`skill-version-delete-${version.version}`"
                      @click="removeVersion(version)"
                    >
                      删除
                    </el-button>
                  </div>
                </td>
              </tr>
              <tr v-if="!versions.length">
                <td class="admin-empty" colspan="11" data-test="skill-version-empty">{{ scope.versionTableEmpty }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        </template>

        <section class="skill-endpoints" data-test="skill-client-endpoints">
          <h3><KeyRound :size="15" aria-hidden="true" />客户端下载接口</h3>
          <p>
            {{ scope.packageHelp }}管理端也可在浏览器直接下载：整包见下方接口与抽屉底部按钮，
            图片图标 / 介绍插画 / 示例图片 / 分类封面在对应编辑区各有「下载」按钮。
          </p>
          <ul>
            <li><code>GET /v1/skills</code>：{{ scope.packageListDesc }}（支持 <code>limit</code>/<code>after</code>/<code>category</code>）</li>
            <li><code>POST /v1/skills/{code}/downloads</code>：创建下载会话，返回下载地址与 MD5/SHA-256</li>
            <li><code>GET /v1/skills/downloads/{id}</code>：下载包体（支持 <code>Range</code> 断点续传）</li>
            <li><code>GET /console/gateway/admin/skills/{id}/package</code>：管理端下载线上版本完整包（<code>role=ADMIN</code>，记审计）</li>
            <li><code>GET /console/gateway/admin/skills/{id}/versions/{versionId}/package</code>：管理端下载指定历史版本</li>
          </ul>
          <p class="field-help">{{ scope.currentCodeHelp }}<code>{{ selected.code }}</code>；桌面客户端优先使用登录 JWT，外部非交互客户端可使用兼容 API Key。</p>
        </section>

        <div class="skill-drawer__foot">
          <el-button data-test="skill-drawer-close" @click="drawerOpen = false">关闭</el-button>
          <!--
            Expert 只显示自包含图片的 YCPS 1.1 下载。普通 Skill/Team 的展开按钮
            仍承载纯 YCPS 与旧外层含图 bundle 两种方式。
          -->
          <el-button
            v-if="isNativeClawhubSelected"
            data-test="clawhub-drawer-download"
            :disabled="!nativeClawhubOnlineVersion"
            :loading="downloadingVersionId === nativeClawhubOnlineVersion?.id"
            title="导出当前线上 ClawHub 原生 ZIP（.zip）"
            @click="nativeClawhubOnlineVersion && downloadNativeClawhubArchive(nativeClawhubOnlineVersion)"
          >
            导出 ClawHub 原生 ZIP（.zip）
            <span v-if="nativeClawhubOnlineVersion" class="admin-muted">
              （v{{ nativeClawhubOnlineVersion.version || '未标版本' }}）
            </span>
          </el-button>
          <div v-else class="skill-download-split">
            <el-button
              :disabled="!publishedVersion"
              :loading="downloadingVersionId !== '' && downloadingVersionId === publishedVersion?.id"
              :title="scope.downloadPackageTitle"
              data-test="skill-drawer-download"
              @click="downloadPackage()"
            >
              {{ scope.downloadPackage }}
              <span v-if="publishedVersion" class="admin-muted">（v{{ publishedVersion.version }}）</span>
            </el-button>
            <el-button
              v-if="!isExpertScope"
              :disabled="!publishedVersion"
              :title="scope.downloadBundle"
              class="skill-download-split__toggle"
              data-test="skill-drawer-download-toggle"
              @click="downloadMenuOpen = !downloadMenuOpen"
            >
              <ChevronDown :size="14" aria-hidden="true" />
            </el-button>

            <div v-if="!isExpertScope && downloadMenuOpen" class="skill-download-split__menu" role="menu" data-test="skill-drawer-download-menu">
              <button
                type="button"
                class="skill-download-split__item"
                role="menuitem"
                data-test="skill-drawer-download-menu-package"
                @click="chooseDownload(false)"
              >
                {{ scope.downloadPackage }}
              </button>
              <button
                type="button"
                class="skill-download-split__item"
                role="menuitem"
                data-test="skill-drawer-download-menu-bundle"
                @click="chooseDownload(true)"
              >
                {{ scope.downloadBundle }}
              </button>
            </div>
          </div>
          <el-button type="primary" data-test="skill-drawer-edit" @click="openEdit(selected)">编辑基础信息</el-button>
        </div>
      </div>
    </el-drawer>

    <!-- 版本审核详情：YCPS 信任字段 + 权限 + 运行环境 + 依赖/反向依赖 -->
    <el-dialog
      v-model="reviewOpen"
      :title="reviewVersion ? `审核详情 · v${reviewVersion.version}（revision ${reviewVersion.revision}）` : '审核详情'"
      width="min(920px, calc(100vw - 24px))"
      :teleported="false"
      destroy-on-close
      @closed="closeVersionReview"
    >
      <div v-if="reviewVersion" class="skill-review" data-test="skill-review-body">
        <section class="skill-review__card" data-test="skill-review-trust">
          <h3><ShieldCheck :size="15" aria-hidden="true" />包信任与完整性</h3>
          <dl class="skill-review__grid">
            <div><dt>包格式</dt><dd data-test="skill-review-format">{{ reviewVersion.packageFormat || '—' }}</dd></div>
            <div><dt>YCPS 版本</dt><dd>{{ reviewVersion.ycpsVersion || '—' }}</dd></div>
            <div><dt>包类型</dt><dd>{{ reviewVersion.packageKind ? kindLabel(reviewVersion.packageKind) : '—' }}</dd></div>
            <div><dt>状态</dt><dd>{{ versionStatusLabel(reviewVersion.status) }}</dd></div>
            <div><dt>封存</dt><dd>{{ reviewVersion.sealed ? `已封存 · ${formatDateTime(reviewVersion.sealedAt)}` : '未封存' }}</dd></div>
            <div><dt>文件数</dt><dd>{{ reviewVersion.fileCount }}</dd></div>
            <div><dt>解压后大小</dt><dd>{{ humanizeBytes(reviewVersion.extractedBytes) }}</dd></div>
            <div><dt>压缩包大小</dt><dd>{{ humanizeBytes(reviewVersion.sizeBytes) }}</dd></div>
            <div><dt>最低客户端</dt><dd>{{ reviewVersion.minClientVersion || '不限' }}</dd></div>
            <div class="is-full">
              <dt>Manifest SHA-256</dt>
              <dd><code data-test="skill-review-manifest-sha">{{ reviewVersion.manifestSha256 || '—' }}</code></dd>
            </div>
            <div class="is-full">
              <dt>依赖锁 SHA-256</dt>
              <dd><code data-test="skill-review-dependency-lock">{{ reviewVersion.dependencyLockSha256 || '—' }}</code></dd>
            </div>
          </dl>
          <p class="field-help">
            MD5 仅作非安全诊断信息（<code>{{ reviewVersion.md5 || '—' }}</code>）；桌面端安装门禁以 SHA-256、依赖锁与 manifest 校验为准。
          </p>
        </section>

        <section class="skill-review__card" data-test="skill-review-permissions">
          <h3><Lock :size="15" aria-hidden="true" />权限申请（permissionRequests）</h3>
          <div class="skill-review__row">
            <span>工具能力</span>
            <div v-if="reviewPermission.tools.length" class="skill-review__tags">
              <el-tag
                v-for="tool in reviewPermission.tools"
                :key="tool"
                size="small"
                :type="isHighRiskCapability(tool) ? 'danger' : 'info'"
                :data-test="`skill-review-tool-${tool}`"
              >
                {{ tool }}
              </el-tag>
            </div>
            <span v-else class="admin-muted">未声明</span>
          </div>
          <div class="skill-review__row">
            <span>网络访问</span>
            <div v-if="reviewPermission.network.length" class="skill-review__tags">
              <el-tag v-for="origin in reviewPermission.network" :key="origin" size="small" type="info">{{ origin }}</el-tag>
            </div>
            <span v-else class="admin-muted">未声明</span>
          </div>
          <div class="skill-review__row">
            <span>文件系统</span>
            <div v-if="reviewPermission.filesystem.length" class="skill-review__tags">
              <el-tag v-for="scope in reviewPermission.filesystem" :key="scope" size="small" type="info">{{ scope }}</el-tag>
            </div>
            <span v-else class="admin-muted">未声明</span>
          </div>
          <p class="field-help">
            数据来自包内 runtime manifest；未声明表示该包不申请此类权限，红色标签为可执行代码或工作区改写类高危能力。
          </p>
        </section>

        <section class="skill-review__card" data-test="skill-review-host">
          <h3><Blocks :size="15" aria-hidden="true" />运行环境要求（hostRequirements）</h3>
          <dl class="skill-review__grid">
            <div><dt>最低客户端</dt><dd>{{ reviewHost.minClientVersion || reviewVersion.minClientVersion || '不限' }}</dd></div>
            <div><dt>平台</dt><dd>{{ reviewHost.platforms.join('、') || '不限' }}</dd></div>
            <div><dt>架构</dt><dd>{{ reviewHost.architectures.join('、') || '不限' }}</dd></div>
          </dl>
        </section>

        <section class="skill-review__card" data-test="skill-review-dependencies-card">
          <h3><GitBranch :size="15" aria-hidden="true" />依赖（该版本引用 {{ reviewDependencies.length }} 个包）</h3>
          <el-skeleton v-if="reviewLoading" :rows="3" animated />
          <div v-else-if="reviewDependencyError" class="admin-empty" data-test="skill-review-error">
            {{ reviewDependencyError }}
          </div>
          <div v-else-if="reviewDependencies.length" class="table-scroll">
            <table class="admin-table skill-review__table" data-test="skill-review-dependencies">
              <thead>
                <tr>
                  <th scope="col">key</th>
                  <th scope="col">关系</th>
                  <th scope="col">必需</th>
                  <th scope="col">目标</th>
                  <th scope="col">版本</th>
                  <th scope="col">revision</th>
                  <th scope="col">SHA-256</th>
                  <th scope="col">目标状态</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="dependency in reviewDependencies" :key="`${dependency.key}-${dependency.targetVersionId}`">
                  <td><code>{{ dependency.key }}</code></td>
                  <td>{{ dependencyRelationLabel(dependency.relation) }}</td>
                  <td>{{ dependency.required ? '必需' : '可选' }}</td>
                  <td>
                    <code>{{ dependency.targetCode }}</code>
                    <small class="admin-muted">{{ kindLabel(dependency.targetKind) }}</small>
                  </td>
                  <td>v{{ dependency.targetVersion }}</td>
                  <td class="admin-number">{{ dependency.targetRevision }}</td>
                  <td><code :title="dependency.targetSha256">{{ shortDigest(dependency.targetSha256) }}</code></td>
                  <td>
                    <el-tag size="small" :type="dependencyTargetTagType(dependency.targetVersionStatus)">
                      {{ dependencyTargetLabel(dependency.targetVersionStatus) }}
                    </el-tag>
                    <el-tag v-if="dependency.targetOnline" size="small" type="success">线上</el-tag>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else class="field-help">该版本没有精确依赖（Legacy 或未锁定依赖的包）。</p>
        </section>

        <section class="skill-review__card" data-test="skill-review-dependents-card">
          <h3><GitBranch :size="15" aria-hidden="true" />反向依赖（{{ reviewDependents.length }} {{ scope.reverseDepTitle }}）</h3>
          <div v-if="reviewDependents.length" class="table-scroll">
            <table class="admin-table skill-review__table" data-test="skill-review-dependents">
              <thead>
                <tr>
                  <th scope="col">引用方</th>
                  <th scope="col">类型</th>
                  <th scope="col">版本</th>
                  <th scope="col">revision</th>
                  <th scope="col">状态</th>
                  <th scope="col">引用版本</th>
                  <th scope="col">必需</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="dependent in reviewDependents" :key="dependent.ownerVersionId">
                  <td><code>{{ dependent.ownerCode }}</code></td>
                  <td>{{ kindLabel(dependent.ownerKind) }}</td>
                  <td>v{{ dependent.ownerVersion }}</td>
                  <td class="admin-number">{{ dependent.ownerRevision }}</td>
                  <td>
                    <el-tag size="small" :type="statusTagType(dependent.ownerStatus)">{{ statusLabel(dependent.ownerStatus) }}</el-tag>
                    <el-tag v-if="dependent.ownerOnline" size="small" type="success">线上</el-tag>
                  </td>
                  <td>v{{ dependent.targetVersion }}（r{{ dependent.targetRevision }}）</td>
                  <td>{{ dependent.required ? '必需' : '可选' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else class="field-help">{{ scope.reverseDepEmpty }}</p>
          <p class="field-help">下架或删除前请确认这些引用方已完成迁移或同步发布新版本。</p>
        </section>
      </div>

      <template #footer>
        <el-button data-test="skill-review-close" @click="reviewOpen = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- A21 包级关系：一次取全「我依赖谁」与「谁依赖我」，无需先进版本页 -->
    <el-dialog
      v-model="relationsOpen"
      :title="relationsSkill ? `关系 · ${relationsSkill.name}` : '关系'"
      width="min(920px, calc(100vw - 24px))"
      :teleported="false"
      destroy-on-close
      @closed="closeRelations"
    >
      <div v-if="relationsLoading" class="skill-review" data-test="skill-relations-loading">
        <el-skeleton :rows="6" animated />
      </div>
      <div v-else class="skill-review" data-test="skill-relations-body">
        <p v-if="relationsError" class="skill-review__error" data-test="skill-relations-error">
          {{ relationsError }}
        </p>

        <section class="skill-review__card" data-test="skill-relations-depends-on-card">
          <h3>
            <Blocks :size="15" aria-hidden="true" />
            依赖能力（{{ relationsView?.dependsOn?.length ?? 0 }}）
            <small v-if="relationsView?.version" class="admin-muted">
              按已发布版本 v{{ relationsView.version }}（r{{ relationsView.revision }}）
            </small>
          </h3>
          <div v-if="relationsView?.dependsOn?.length" class="table-scroll">
            <table class="admin-table skill-review__table" data-test="skill-relations-depends-on">
              <thead>
                <tr>
                  <th scope="col">键</th>
                  <th scope="col">关系</th>
                  <th scope="col">必需</th>
                  <th scope="col">目标</th>
                  <th scope="col">类型</th>
                  <th scope="col">版本</th>
                  <th scope="col">状态</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="edge in relationsView.dependsOn" :key="`${edge.key}-${edge.code}`">
                  <td><code>{{ edge.key }}</code></td>
                  <td>{{ dependencyRelationLabel(edge.relation) }}</td>
                  <td>{{ edge.required ? '必需' : '可选' }}</td>
                  <td><code>{{ edge.code }}</code></td>
                  <td>{{ kindLabel(edge.kind) }}</td>
                  <td>
                    <template v-if="edge.version">v{{ edge.version }}（r{{ edge.revision }}）</template>
                    <template v-else>—</template>
                  </td>
                  <td>
                    <el-tag size="small" :type="dependencyTargetTagType(edge.status ?? 'DELETED')">
                      {{ dependencyTargetLabel(edge.status ?? 'DELETED') }}
                    </el-tag>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else class="field-help" data-test="skill-relations-depends-on-empty">
            {{ relationsView?.version ? '该包没有依赖能力。' : '该包还没有已发布版本，暂无依赖关系。' }}
          </p>
        </section>

        <section class="skill-review__card" data-test="skill-relations-depended-on-by-card">
          <h3><GitBranch :size="15" aria-hidden="true" />被引用（{{ relationsView?.dependedOnBy?.length ?? 0 }}）</h3>
          <div v-if="relationsView?.dependedOnBy?.length" class="table-scroll">
            <table class="admin-table skill-review__table" data-test="skill-relations-depended-on-by">
              <thead>
                <tr>
                  <th scope="col">引用方</th>
                  <th scope="col">类型</th>
                  <th scope="col">版本</th>
                  <th scope="col">状态</th>
                  <th scope="col">引用键</th>
                  <th scope="col">关系</th>
                  <th scope="col">必需</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="entry in relationsView.dependedOnBy" :key="entry.ownerVersionId">
                  <td><code>{{ entry.ownerCode }}</code></td>
                  <td>{{ kindLabel(entry.ownerKind) }}</td>
                  <td>v{{ entry.ownerVersion }}（r{{ entry.ownerRevision }}）</td>
                  <td>
                    <el-tag size="small" :type="statusTagType(entry.ownerStatus)">{{ statusLabel(entry.ownerStatus) }}</el-tag>
                    <el-tag v-if="entry.ownerOnline" size="small" type="success">线上</el-tag>
                  </td>
                  <td><code>{{ entry.key }}</code></td>
                  <td>{{ dependencyRelationLabel(entry.relation) }}</td>
                  <td>{{ entry.required ? '必需' : '可选' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else class="field-help" data-test="skill-relations-depended-on-by-empty">
            {{ scope.reverseDepEmpty }}
          </p>
          <p class="field-help">下架或删除前请确认这些引用方已完成迁移或同步发布新版本。</p>
        </section>
      </div>

      <template #footer>
        <el-button data-test="skill-relations-close" @click="relationsOpen = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 客户端预览详情（只读） -->
    <el-dialog
      v-model="detailOpen"
      :title="dialogTitle"
      width="min(880px, calc(100vw - 24px))"
      :teleported="false"
      @closed="closePreviewDetail"
    >
      <div class="skill-detail" data-test="skill-detail">
        <div v-if="detailSummary" class="skill-detail__head">
          <SkillAvatar
            class="skill-detail__icon"
            size="md"
            :src="detailSummary.iconUrl ? previewIconPreviews.imageUrlFor(detailSummary.name) : ''"
            :code="detailSummary.kind === 'SKILL' ? detailSummary.name : ''"
            :badge="skillIcon(detailSummary)"
            :fallback="skillInitial(detailSummary)"
            :alt="`${detailSummary.title || detailSummary.name}图标`"
            data-test="skill-detail-avatar"
          />
          <div class="skill-detail__identity">
            <strong>{{ detailSummary.title || detailSummary.name }}</strong>
            <small v-if="detailSummary.titleEn" class="skill-cell__name-en" data-test="skill-detail-title-en">
              {{ detailSummary.titleEn }}
            </small>
            <code>{{ detailSummary.name }}</code>
          </div>
          <div class="skill-detail__badges">
            <el-tag
              v-if="kindCode(detailSummary)"
              size="small"
              :type="kindTagType(kindCode(detailSummary))"
              :data-test="`skill-detail-kind-${detailSummary.name}`"
            >
              {{ kindLabel(kindCode(detailSummary)) }}
            </el-tag>
            <span v-if="detailSummary.category" class="skill-detail__category">{{ detailSummary.category }}</span>
          </div>
        </div>

          <div v-if="detailLoading" class="skill-detail__state" data-test="skill-detail-loading">
            <Package :size="16" aria-hidden="true" />
            {{ scope.readingPackage }}
          </div>

        <div v-else-if="detailFailure" class="skill-preview-error" role="alert" data-test="skill-detail-error">
          <CircleAlert :size="18" aria-hidden="true" />
          <div>
            <strong data-test="skill-detail-error-message">{{ detailFailure.message }}</strong>
            <span v-if="detailFailure.code">错误码：<code>{{ detailFailure.code }}</code></span>
            <span v-if="detailFailure.requestId">请求编号：<code data-test="skill-detail-error-request-id">{{ detailFailure.requestId }}</code></span>
          </div>
        </div>

        <template v-else-if="nativePreviewDetail">
          <dl class="skill-detail__meta" data-test="skill-detail-native-meta">
            <div><dt>版本</dt><dd>v{{ nativePreviewDetail.version }}</dd></div>
            <div><dt>来源</dt><dd>原生技能</dd></div>
            <div><dt>包大小</dt><dd>{{ nativeClawhubPackageSize(nativePreviewDetail.sizeBytes) }}</dd></div>
            <div><dt>更新时间</dt><dd>{{ formatDateTime(nativePreviewDetail.updatedAt) }}</dd></div>
            <div><dt>发布者</dt><dd>{{ nativePreviewDetail.owner }}</dd></div>
          </dl>
          <p class="skill-detail__desc" data-test="skill-detail-description">
            {{ nativePreviewDetail.description || nativePreviewDetail.summary || '原生技能暂无描述' }}
          </p>
          <p v-if="nativePreviewDetail.descriptionEn || nativePreviewDetail.summaryEn" class="skill-detail__desc skill-detail__i18n">
            {{ nativePreviewDetail.descriptionEn || nativePreviewDetail.summaryEn }}
          </p>
          <p class="skill-detail__desc">技能标识：<code>@{{ nativePreviewDetail.owner }}/{{ nativePreviewDetail.slug }}</code></p>
          <p class="admin-muted">原生技能客户端目录只提供卡片信息，不包含包内文件清单。</p>
        </template>

        <template v-else-if="detail">
          <dl class="skill-detail__meta">
            <div><dt>版本</dt><dd>v{{ detail.version || '—' }}</dd></div>
            <div><dt>文件数</dt><dd>{{ detail.fileCount }}</dd></div>
            <div><dt>总大小</dt><dd>{{ formatBytes(detail.totalBytes) }}</dd></div>
            <div><dt>更新时间</dt><dd>{{ formatDateTime(detail.updatedAt) }}</dd></div>
          </dl>

          <p class="skill-detail__desc" data-test="skill-detail-description">
            {{ detail.description || '暂无描述' }}
          </p>
          <p v-if="detail.descriptionEn" class="skill-detail__desc skill-detail__i18n" data-test="skill-detail-description-en">
            {{ detail.descriptionEn }}
          </p>

          <section v-if="detail.kind === 'TEAM' && previewMembers.length" class="skill-detail__members" data-test="skill-detail-members">
            <h3 class="skill-detail__section">
              <Users :size="15" aria-hidden="true" />
              专家团成员（{{ previewMembers.length }}）
            </h3>
            <div class="skill-member-cards">
              <article
                v-for="member in previewMembers"
                :key="member.memberKey"
                class="skill-member-card"
                :data-test="`skill-member-card-${member.memberKey}`"
              >
                <span class="skill-member-card__avatar" aria-hidden="true">
                  <img
                    v-if="previewMemberAvatarUrl(member.memberKey)"
                    :src="previewMemberAvatarUrl(member.memberKey)"
                    :alt="`${member.memberKey} 头像`"
                  />
                  <template v-else>{{ memberInitial(member) }}</template>
                </span>
                <div class="skill-member-card__body">
                  <div class="skill-member-card__name-row">
                    <strong>{{ member.name || member.memberKey }}</strong>
                    <el-tag v-if="member.role === 'LEAD'" size="small" type="warning">主理人</el-tag>
                  </div>
                  <small v-if="member.nameEn" class="skill-cell__name-en">{{ member.nameEn }}</small>
                  <span class="skill-member-card__profession">
                    {{ member.profession || '—' }}
                    <template v-if="member.professionEn"> / {{ member.professionEn }}</template>
                  </span>
                  <code class="skill-member-card__key">{{ member.memberKey }}</code>
                </div>
              </article>
            </div>
          </section>

          <div v-if="detail.tags?.length" class="skill-detail__tags" data-test="skill-detail-tags">
            <span v-for="tag in detail.tags" :key="tag" class="skill-chip">{{ tag }}</span>
          </div>

          <ul v-if="detail.highlights?.length" class="skill-detail__highlights">
            <li v-for="point in detail.highlights" :key="point">
              <BadgeCheck :size="13" aria-hidden="true" />
              <span>{{ point }}</span>
            </li>
          </ul>

          <h3 class="skill-detail__section">
            <Blocks :size="15" aria-hidden="true" />
            文件清单（{{ detail.files.length }}）
          </h3>

          <div class="table-scroll">
            <table class="admin-table skill-file-table" data-test="skill-detail-files">
              <thead>
                <tr>
                  <th scope="col">文件路径</th>
                  <th scope="col">大小</th>
                  <th scope="col">SHA-256</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(file, index) in detail.files"
                  :key="file.path"
                  :data-test="`skill-detail-file-${detail.name}-${index}`"
                >
                  <td>
                    <span class="skill-file-path">
                      <FileText :size="14" aria-hidden="true" />
                      <code>{{ file.path }}</code>
                    </span>
                  </td>
                  <td class="admin-number">{{ formatBytes(file.bytes) }}</td>
                  <td><code class="sha" :title="file.sha256">{{ file.sha256 }}</code></td>
                  <td>
                    <el-button
                      v-if="file.encoding !== 'base64'"
                      size="small"
                      text
                      :data-test="`skill-detail-preview-${detail.name}-${index}`"
                      :aria-label="`预览 ${file.path}`"
                      @click="togglePreview(file)"
                    >
                      <Eye :size="15" aria-hidden="true" />
                      预览
                    </el-button>
                    <span v-else class="skill-binary-hint" data-test="skill-detail-binary">二进制文件</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <p v-if="!detail.files.length" class="skill-detail__empty">{{ scope.noPublishedFiles }}</p>

          <section v-if="previewFile" class="skill-preview" data-test="skill-preview">
            <header class="skill-preview__head">
              <strong>{{ previewFile.path }}</strong>
              <span>· {{ formatBytes(previewFile.bytes) }}</span>
            </header>
            <pre class="skill-preview__body" data-test="skill-preview-body">{{ previewText }}</pre>
          </section>
        </template>

        <div class="skill-detail__foot">
          <el-button data-test="skill-detail-close" @click="detailOpen = false">关闭</el-button>
        </div>
      </div>
    </el-dialog>
  </section>
</template>

<style scoped>
.skill-header-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.skill-tabs {
  min-width: 0;
}

.table-scroll {
  overflow-x: auto;
}

/* 筛选与列表之间的批量工具条：左侧管选择，右侧只放对所选项的写操作。 */
.skill-bulk-toolbar {
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
  padding: 8px 12px;
  background: var(--app-surface);
  border: 1px solid var(--app-border-subtle);
  border-radius: var(--radius-md);
  box-shadow: var(--app-shadow-xs);
}

.skill-bulk-toolbar__selection,
.skill-bulk-toolbar__actions,
.skill-bulk-toolbar__action-group,
.skill-bulk-toolbar__category {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.skill-bulk-toolbar__selection {
  min-width: 250px;
  flex: 1 1 auto;
}

.skill-bulk-toolbar__hint {
  color: var(--app-muted);
  font-size: var(--text-caption);
}

.skill-bulk-toolbar__count {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 8px;
  color: var(--app-primary);
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: 999px;
  font-size: var(--text-caption);
  white-space: nowrap;
}

.skill-bulk-toolbar__count strong {
  font-variant-numeric: tabular-nums;
}

.skill-bulk-toolbar__actions {
  justify-content: flex-end;
  flex-wrap: wrap;
  flex: 0 1 auto;
}

.skill-bulk-toolbar__label {
  color: var(--app-muted-strong);
  font-size: var(--text-caption);
  white-space: nowrap;
}

.skill-bulk-toolbar__category :deep(.el-select) {
  width: 156px;
}

.skill-bulk-toolbar__category :deep(.el-button) {
  margin-left: -1px;
}

.skill-bulk-toolbar__divider {
  width: 1px;
  height: 24px;
  margin: 0 var(--space-1);
  background: var(--app-border-subtle);
}

.skill-bulk-toolbar__danger {
  display: inline-flex;
}

.skill-recycle-toolbar,
.skill-recycle-footer,
.skill-recycle-footer > div {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.skill-recycle-toolbar {
  margin-bottom: var(--space-3);
}

.skill-recycle-toolbar :deep(.el-input) {
  width: min(360px, 100%);
}

.skill-recycle-table-wrap {
  min-height: 180px;
  max-height: min(55vh, 520px);
  overflow: auto;
}

.skill-recycle-table {
  min-width: 700px;
}

.skill-recycle-pagination {
  justify-content: flex-end;
  margin-top: var(--space-3);
}

.skill-recycle-table td:first-of-type + td {
  display: grid;
  gap: 2px;
}

.skill-recycle-table code {
  color: var(--app-muted);
  font-size: var(--text-caption);
}

.skill-recycle-footer {
  justify-content: space-between;
  flex-wrap: wrap;
}

/* ---------- 列表表格 ---------- */
.skills-table {
  min-width: 1120px;
}

.skill-selection-cell {
  width: 42px;
  text-align: center;
}

.skill-cell__icon {
  display: inline-flex;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-sm);
  color: var(--app-primary);
  font-size: 16px;
  line-height: 1;
}

.skill-cell__name {
  display: block;
  color: var(--app-fg);
}

.skill-cell__code {
  display: block;
  margin-top: 2px;
  color: var(--app-muted);
  font-family: var(--font-mono);
  font-size: var(--text-caption);
}

.skill-badge-pending {
  display: inline-block;
  margin-left: var(--space-1);
  padding: 1px 6px;
  color: var(--app-warning-text);
  background: var(--app-warning-soft);
  border: 1px solid var(--app-warning-border);
  border-radius: var(--radius-xs);
  font-size: var(--text-caption);
  white-space: nowrap;
}

.skill-row-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px;
}

.skill-native-clawhub-versions__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
  margin: var(--space-2) 0 var(--space-3);
}

.skill-native-clawhub-versions__head .skill-drawer__section {
  margin: 0;
}

.skill-native-clawhub-versions__head .field-help {
  margin: var(--space-1) 0 0;
}

/* ---------- 专家 / 专家团：二次分类卡片 ---------- */

.expert-category-view {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

/* 二级分类筛选：本地过滤，不改列表请求 */
.expert-category-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.expert-category-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  min-height: 30px;
  padding: 4px 12px;
  color: var(--app-muted-strong);
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-lg);
  cursor: pointer;
  font-size: var(--text-caption);
  transition: color var(--app-motion-fast) ease, background var(--app-motion-fast) ease,
    border-color var(--app-motion-fast) ease;
}

.expert-category-chip:hover {
  color: var(--app-primary);
  border-color: var(--app-primary-border);
}

.expert-category-chip.active {
  color: var(--app-primary);
  background: var(--app-primary-soft);
  border-color: var(--app-primary);
  font-weight: 600;
}

.expert-category-chip__count {
  color: var(--app-muted);
  font-variant-numeric: tabular-nums;
}

.expert-category-chip.active .expert-category-chip__count {
  color: var(--app-primary);
}

/* ---------- 分类管理对话框 ---------- */

.category-manage-list {
  display: grid;
  gap: var(--space-1);
  margin: 0 0 var(--space-4);
  padding: 0;
  list-style: none;
}

.category-manage-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 6px 10px;
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border-subtle);
  border-radius: var(--radius-sm);
}

.category-manage-item__name {
  flex: 1;
  min-width: 0;
  font-size: var(--text-secondary);
}

.category-manage-item__count {
  color: var(--app-muted-strong);
  font-size: var(--text-caption);
  font-variant-numeric: tabular-nums;
}

/* 分类封面：与精选场景卡片同一张图，未上传时退回分类名首字徽标 */
.category-manage-item__cover {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 28px;
  overflow: hidden;
  background: var(--app-surface);
  border: 1px solid var(--app-border-subtle);
  border-radius: var(--radius-sm);
}

.category-manage-item__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.category-manage-item__badge {
  color: var(--app-muted-strong);
  font-size: var(--text-caption);
  font-weight: 600;
}

.category-manage-item__upload {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--el-color-primary);
  font-size: var(--text-caption);
  white-space: nowrap;
  cursor: pointer;
}

.category-manage-item__upload input {
  display: none;
}

.category-manage-form {
  padding-top: var(--space-3);
  border-top: 1px solid var(--app-border-subtle);
}

.category-manage-form h3 {
  margin: 0 0 var(--space-2);
  font-size: var(--text-body);
}

/* 二级分类分组 */
.expert-category-group {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.expert-category-group__title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
  font-size: var(--text-body);
}

.expert-category-group__count {
  padding: 1px 8px;
  color: var(--app-muted-strong);
  background: var(--app-surface-muted);
  border: 1px solid var(--app-border-subtle);
  border-radius: var(--radius-xs);
  font-size: var(--text-caption);
  font-variant-numeric: tabular-nums;
}

.expert-category-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(288px, 1fr));
  gap: var(--space-4);
}

.expert-category-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-4);
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius);
  box-shadow: var(--app-shadow-xs);
}

.expert-category-card--selected {
  border-color: var(--app-primary);
  box-shadow: 0 0 0 1px var(--app-primary-border);
}

.expert-category-card__head {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
}

.expert-category-card__icon {
  display: inline-flex;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  align-items: center;
  justify-content: center;
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-md);
  color: var(--app-primary);
  font-size: 20px;
  line-height: 1;
}

.expert-category-card__identity {
  min-width: 0;
  flex: 1;
}

.expert-category-card__badges {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-1);
}

.expert-category-card__summary {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  color: var(--app-muted-strong);
  font-size: var(--text-secondary);
  line-height: var(--leading-base);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

.expert-category-card__summary--en {
  color: var(--app-muted);
  font-style: italic;
  -webkit-line-clamp: 1;
  line-clamp: 1;
}

.expert-category-card .skill-card__meta {
  margin-top: 0;
}

.skill-action-disabled {
  display: inline-flex;
}

/* ---------- 表单 ---------- */
/*
 * A27 导入区。视觉上刻意与下方「分步表单」区分开：它是一条旁路入口
 * （把包读成字段），不是一个需要填的步骤，所以用虚线框而不是 .skill-form__section 的分隔线。
 */
.skill-import {
  margin-bottom: var(--space-4);
  padding: var(--space-3);
  border: 1px dashed var(--app-border-subtle);
  border-radius: var(--radius-md);
  background: var(--app-surface-subtle);
}

.skill-import__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.skill-import__head h3 {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  margin: 0;
  color: var(--app-fg);
  font-size: var(--text-body);
}

.skill-import__staged {
  color: var(--app-muted);
  font-size: var(--text-caption);
}

.skill-import__clawhub-report {
  margin-top: var(--space-3);
  padding: var(--space-3);
  color: var(--app-fg);
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-sm);
  font-size: var(--text-caption);
  line-height: var(--leading-base);
  overflow-wrap: anywhere;
}

.skill-import__clawhub-report p {
  margin: 0;
}

.skill-import__clawhub-report p + p,
.skill-import__clawhub-report span + p {
  margin-top: var(--space-2);
}

.skill-import__clawhub-report ul {
  display: grid;
  gap: var(--space-1);
  margin: var(--space-2) 0 0;
  padding-left: var(--space-4);
}

/*
 * 导入按钮居中。
 *
 * 用 `justify-content: center` 而不是给按钮加 `margin: 0 auto`：
 * 动作区里除了按钮还有「正在解析…」文字，居中要针对整行内容，
 * 否则文字出现/消失时按钮会左右跳。
 */
.skill-import__actions {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-top: var(--space-2);
}

.skill-import__busy {
  color: var(--app-muted);
  font-size: var(--text-caption);
}

.skill-import__remote {
  margin-top: var(--space-3);
}

.skill-import__remote-label {
  display: block;
  margin-bottom: var(--space-1);
  color: var(--app-fg);
  font-size: var(--text-caption);
}

.skill-import__remote-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.skill-import__remote-row :deep(.el-input) {
  flex: 1;
  min-width: 0;
}

.skill-import__remote-row :deep(.el-button) {
  margin-left: 0;
}

.skill-import__remote .field-help {
  margin: var(--space-1) 0 0;
}

@media (max-width: 560px) {
  .skill-import__remote-row { flex-wrap: wrap; }
  .skill-import__remote-row :deep(.el-input) { flex-basis: 100%; }
}

/*
 * 整块是拖拽投放区，所以要给出「这里能放东西」的视觉承诺：
 * 虚线框常驻；拖拽悬停时换成主色实线 + 浅底，并浮出中央提示。
 */
.skill-import--dragging {
  border-color: var(--app-primary);
  border-style: solid;
  background: var(--app-primary-soft);
}

.skill-import__drop-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-1);
  margin-top: var(--space-2);
  color: var(--app-primary);
  font-size: var(--text-secondary);
}

/*
 * 分裂按钮：主按钮 + 紧贴的下拉箭头，视觉上是一颗按钮。
 * 用 flex 拼接并各自去掉相邻圆角，避免出现缝。
 */
.skill-import__split {
  position: relative;
  display: inline-flex;
}

.skill-import__split :deep(.skill-import__main) {
  border-top-right-radius: 0;
  border-bottom-right-radius: 0;
}

.skill-import__split :deep(.skill-import__toggle) {
  border-top-left-radius: 0;
  border-bottom-left-radius: 0;
  /* 用 margin 抵消相邻按钮的默认间距 */
  margin-left: -1px;
  padding-left: var(--space-1);
  padding-right: var(--space-1);
}

/*
 * 下拉菜单：锚定在按钮容器上（`.skill-import__split` 是 relative），
 * 并**相对按钮水平居中** —— 按钮本身是居中的，若只贴右边缘，
 * 菜单会整块挂到按钮左侧、看起来像脱离了按钮。
 *
 * 注意别把它挪回 `.skill-import__actions` —— 那个容器现在 `justify-content: center`
 * 且包含「正在解析…」文字，按它定位会让菜单跟着文字宽度漂移。
 */
.skill-import__menu {
  position: absolute;
  z-index: 20;
  top: calc(100% + var(--space-1));
  left: 50%;
  transform: translateX(-50%);
  min-width: 220px;
  padding: var(--space-1) 0;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-md);
  box-shadow: var(--app-shadow-md);
}

.skill-import__notice {
  margin: 0;
  padding: var(--space-2) var(--space-3);
  color: #d97706;
  font-size: var(--text-caption);
  line-height: 1.4;
  border-bottom: 1px solid var(--app-border-subtle);
}

.skill-import__item {
  display: flex;
  width: 100%;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: none;
  border: 0;
  color: var(--app-fg);
  cursor: pointer;
  font: inherit;
  font-size: var(--text-body);
  text-align: left;
}

.skill-import__item:hover {
  background: var(--app-surface-subtle);
}

/*
 * 导入区内的原生 file input 必须藏起来：用户要的是「一个按钮」，
 * 而两个 input 各自会渲染出「选择文件 未选择任何文件」的系统控件。
 * 隐藏后仍可用 `ref.click()` 程序化唤起选择器（display:none 不影响 .click()）。
 *
 * 只作用于导入区：`.skill-file-input` 是共享类，版本上传区那边要照常显示。
 */
.skill-import .skill-file-input {
  display: none;
}

/* 分步向导的步骤条。步骤可点击跳转，所以给指针反馈。 */
.skill-form__steps {
  margin-bottom: var(--space-4);
}

.skill-form__step-head {
  cursor: pointer;
}

.skill-form__step-hint {
  color: var(--app-muted);
  font-size: var(--text-caption);
  line-height: 1.4;
}

.skill-form__section + .skill-form__section {
  margin-top: var(--space-4);
  padding-top: var(--space-3);
  border-top: 1px solid var(--app-border-subtle);
}

.skill-form__section h3 {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  margin: 0 0 var(--space-2);
  color: var(--app-fg);
  font-size: var(--text-body);
}

.skill-form__section h3 svg {
  color: var(--app-primary);
}

.field-help {
  display: block;
  margin: 4px 0 0;
  color: var(--app-muted);
  font-size: var(--text-caption);
  line-height: var(--leading-base);
}

/* A22：编码被占用时的提示用危险色，与「可用」的中性提示区分开 */
.field-help-error {
  color: var(--el-color-danger);
}

.skill-form :deep(.el-select),
.skill-form :deep(.el-input-number) {
  width: 100%;
}

.skill-icon-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.skill-icon-preview {
  display: inline-flex;
  width: 56px;
  height: 56px;
  flex: 0 0 56px;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-sm);
  font-size: 24px;
}

.skill-icon-preview img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

/* 介绍插画是宽幅图，预览框按 3:1 给，避免和方形图标用同一个框而看起来被压扁 */
.skill-illustration-frame {
  display: inline-flex;
  width: 168px;
  height: 56px;
  flex: 0 0 168px;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-sm);
}

.skill-illustration-frame img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 示例图展示功能输出，采用常见 16:9 缩略比例。 */
.skill-example-image-frame {
  display: inline-flex;
  width: 168px;
  height: 95px;
  flex: 0 0 168px;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-sm);
}

.skill-example-image-frame img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.skill-illustration-empty {
  color: var(--app-muted);
  font-size: 12px;
}

/* 待提交的图片改动提示：让「已标记但还没生效」这件事在界面上始终可见 */
.skill-pending-change {
  margin: var(--space-2) 0 0;
  padding: 6px 10px;
  color: var(--app-warning-fg, #b45309);
  font-size: var(--text-caption);
  background: var(--app-warning-soft, rgba(245, 158, 11, 0.12));
  border-radius: var(--radius-sm);
}

.skill-icon-preview--empty {
  color: var(--app-primary);
  font-weight: 700;
}

.skill-icon-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.skill-file-label {
  display: block;
  margin-bottom: var(--space-1);
  color: var(--app-fg);
  font-size: var(--text-caption);
  font-weight: 600;
}

.skill-file-input {
  color: var(--app-muted-strong);
  font-size: var(--text-caption);
}

.skill-version-card {
  display: grid;
  gap: var(--space-2);
  padding: var(--space-3);
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-sm);
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.skill-version-card span {
  display: block;
  color: var(--app-muted);
  font-size: var(--text-caption);
}

.skill-version-card strong {
  color: var(--app-fg);
  font-size: var(--text-caption);
  overflow-wrap: anywhere;
}

.skill-version-card code {
  font-family: var(--font-mono);
}

.skill-upload-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin: var(--space-3) 0;
}

.skill-upload-progress {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-2);
}

.skill-upload-progress :deep(.el-progress) {
  flex: 1;
}

.skill-upload-phase {
  color: var(--app-muted-strong);
  font-size: var(--text-caption);
  white-space: nowrap;
}

/* ---------- 详情抽屉 ---------- */
.skill-drawer {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.skill-detail-import {
  position: relative;
  display: grid;
  justify-items: center;
  gap: var(--space-2);
  padding: var(--space-4);
  overflow: hidden;
  text-align: center;
  background: var(--app-surface-subtle);
  border: 1px dashed var(--app-border);
  border-radius: var(--radius-md);
  transition: border-color 160ms ease, background-color 160ms ease, box-shadow 160ms ease;
}

.skill-detail-import--dragging {
  background: var(--app-primary-soft);
  border-color: var(--app-primary);
  border-style: solid;
  box-shadow: 0 0 0 3px var(--app-primary-soft);
}

.skill-detail-import__input {
  display: none;
}

.skill-detail-import__icon {
  display: inline-flex;
  width: 48px;
  height: 48px;
  align-items: center;
  justify-content: center;
  color: var(--app-primary);
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: 50%;
}

.skill-detail-import__copy h3,
.skill-detail-import__copy p,
.skill-detail-import__notice {
  margin: 0;
}

.skill-detail-import__copy h3 {
  color: var(--app-fg);
  font-size: var(--text-body);
}

.skill-detail-import__copy p {
  margin-top: var(--space-1);
  color: var(--app-muted-strong);
  font-size: var(--text-secondary);
  line-height: var(--leading-base);
}

.skill-detail-import__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-2);
}

.skill-detail-import__notice {
  max-width: 520px;
  color: var(--app-muted);
  font-size: var(--text-caption);
  line-height: var(--leading-base);
}

.skill-detail-import .skill-upload-progress {
  width: 100%;
  margin-top: var(--space-1);
}

.skill-detail-import__drop-hint {
  position: absolute;
  z-index: 2;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-1);
  color: var(--app-primary);
  background: var(--app-primary-soft);
  font-size: var(--text-body);
  font-weight: 600;
  pointer-events: none;
}

.skill-drawer__head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.skill-drawer__head strong {
  display: block;
  font-size: var(--text-section);
}

.skill-drawer__head code {
  color: var(--app-muted);
  font-family: var(--font-mono);
  font-size: var(--text-caption);
}

.skill-drawer__icon {
  display: inline-flex;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  align-items: center;
  justify-content: center;
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-md);
  font-size: 20px;
}

.skill-pending-hint {
  margin: 0;
  padding: var(--space-2) var(--space-3);
  color: var(--app-warning-text);
  background: var(--app-warning-soft);
  border: 1px solid var(--app-warning-border);
  border-radius: var(--radius-sm);
  font-size: var(--text-caption);
}

.skill-clawhub-version-missing {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.skill-clawhub-version-missing p {
  margin: 0;
}

.skill-drawer__meta {
  display: grid;
  gap: var(--space-2) var(--space-3);
  margin: 0;
  padding: var(--space-3);
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-sm);
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.skill-drawer__meta dt {
  color: var(--app-muted);
  font-size: var(--text-caption);
}

.skill-drawer__meta dd {
  margin: 2px 0 0;
  color: var(--app-fg);
  font-size: var(--text-caption);
  overflow-wrap: anywhere;
}

.skill-drawer__summary {
  margin: 0;
  color: var(--app-muted-strong);
  font-size: var(--text-secondary);
}

.skill-drawer__description {
  margin: 0;
  color: var(--app-muted-strong);
  font-size: var(--text-caption);
  line-height: var(--leading-base);
  white-space: pre-wrap;
}

.skill-drawer__section {
  margin: var(--space-2) 0 0;
  font-size: var(--text-body);
}

.skill-md5 {
  font-family: var(--font-mono);
  font-size: var(--text-caption);
}

.skill-digest {
  font-family: var(--font-mono);
  font-size: var(--text-caption);
}

.skill-version-kind {
  display: block;
  margin-top: 2px;
}

/* ---------- 版本审核详情（YCPS 信任字段 / 权限 / 依赖） ---------- */

.skill-review {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

/* A21：关系面板拉取失败时的提示 */
.skill-review__error {
  margin: 0;
  color: var(--el-color-danger);
}

.skill-review__card {
  padding: var(--space-3);
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-sm);
}

.skill-review__card h3 {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  margin: 0 0 var(--space-2);
  font-size: var(--text-body);
}

.skill-review__card h3 svg {
  color: var(--app-primary);
}

.skill-review__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--space-2);
  margin: 0;
}

.skill-review__grid .is-full {
  grid-column: 1 / -1;
}

.skill-review__grid dt {
  color: var(--app-muted-strong);
  font-size: var(--text-caption);
}

.skill-review__grid dd {
  margin: 2px 0 0;
  word-break: break-all;
}

.skill-review__grid code {
  font-family: var(--font-mono);
  font-size: var(--text-caption);
}

.skill-review__row {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-1) 0;
}

.skill-review__row > span:first-child {
  flex: 0 0 84px;
  color: var(--app-muted-strong);
  font-size: var(--text-caption);
}

.skill-review__tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}

.skill-review__table {
  font-size: var(--text-caption);
}

.skill-endpoints {
  padding: var(--space-3);
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-sm);
}

.skill-endpoints h3 {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  margin: 0 0 var(--space-2);
  font-size: var(--text-body);
}

.skill-endpoints h3 svg {
  color: var(--app-primary);
}

.skill-endpoints p {
  margin: 0 0 var(--space-2);
  color: var(--app-muted-strong);
  font-size: var(--text-caption);
}

.skill-endpoints ul {
  display: grid;
  gap: var(--space-1);
  margin: 0;
  padding-left: var(--space-4);
  color: var(--app-muted-strong);
  font-size: var(--text-caption);
}

.skill-endpoints code,
.skill-endpoints strong {
  overflow-wrap: anywhere;
}

.skill-drawer__foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

/*
 * 抽屉底部的「下载」展开按钮。主按钮与箭头贴合成一个视觉整体（负外边距抵消
 * el-button 之间的默认间距），菜单绝对定位在主按钮下方、右对齐。
 */
.skill-download-split {
  position: relative;
  display: inline-flex;
  align-items: center;
}

.skill-download-split__toggle {
  margin-left: -1px;
  padding: 0 var(--space-2);
}

.skill-download-split__menu {
  position: absolute;
  z-index: 20;
  top: calc(100% + var(--space-1));
  right: 0;
  min-width: 240px;
  padding: var(--space-1) 0;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-md);
  box-shadow: var(--app-shadow-md);
}

.skill-download-split__item {
  display: flex;
  width: 100%;
  align-items: center;
  padding: var(--space-2) var(--space-3);
  background: none;
  border: 0;
  color: var(--app-fg);
  cursor: pointer;
  font: inherit;
  font-size: var(--text-body);
  text-align: left;
}

.skill-download-split__item:hover {
  background: var(--app-surface-subtle);
}

.skill-download-split__item:disabled {
  color: var(--app-muted);
  cursor: not-allowed;
}

.skill-download-split__item:disabled:hover {
  background: none;
}

.skill-bulk-toolbar__download-menu {
  width: min(310px, calc(100vw - 32px));
  min-width: 0;
}

.skill-bulk-toolbar__download-actions .skill-download-split__toggle {
  flex: 0 0 auto;
}

/* ---------- 客户端预览（只读目录） ---------- */
.skill-preview-warning {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0 0 var(--space-4);
  padding: var(--space-3);
  color: var(--app-warning, #946200);
  background: var(--app-warning-soft, #fff8e5);
  border: 1px solid var(--app-warning-border, #f3d68a);
  border-radius: var(--radius-md);
}

.skills-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(268px, 1fr));
  gap: var(--space-4);
}

/* 三类分开预览：技能 / 专家 / 专家团 各自一块，不再同屏混排 */
.skill-preview-section + .skill-preview-section {
  margin-top: var(--space-5);
  padding-top: var(--space-4);
  border-top: 1px solid var(--app-border-subtle);
}

.skill-preview-section__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.skill-preview-section__title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
  font-size: var(--text-body);
}

.skill-preview-section__count {
  padding: 1px 8px;
  color: var(--app-muted-strong);
  background: var(--app-surface-muted);
  border: 1px solid var(--app-border-subtle);
  border-radius: var(--radius-xs);
  font-size: var(--text-caption);
  font-variant-numeric: tabular-nums;
}

.skill-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  padding: var(--space-4);
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius);
  box-shadow: var(--app-shadow-xs);
}

.skill-card__head {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
}

.skill-card__icon {
  display: inline-flex;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  align-items: center;
  justify-content: center;
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-md);
  color: var(--app-primary);
  font-size: 20px;
  line-height: 1;
}

.skill-card__identity {
  min-width: 0;
  flex: 1;
}

.skill-card__title {
  margin: 0;
  font-size: var(--text-section);
  line-height: var(--leading-tight);
  overflow-wrap: anywhere;
}

.skill-card__name {
  display: block;
  margin-top: 2px;
  color: var(--app-muted);
  font-family: var(--font-mono);
  font-size: var(--text-caption);
  overflow-wrap: anywhere;
}

.skill-card__category {
  flex: 0 0 auto;
  padding: 2px 8px;
  color: var(--app-primary);
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-xs);
  font-size: var(--text-caption);
  font-weight: 600;
  white-space: nowrap;
}

.skill-card__desc {
  display: -webkit-box;
  margin: var(--space-3) 0 0;
  overflow: hidden;
  color: var(--app-muted-strong);
  font-size: var(--text-secondary);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

.skill-card__meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-2) var(--space-3);
  margin: var(--space-4) 0 0;
  padding-top: var(--space-3);
  border-top: 1px solid var(--app-border-subtle);
}

.skill-card__meta div {
  display: flex;
  min-width: 0;
  gap: var(--space-1);
}

.skill-card__meta dt {
  flex: 0 0 auto;
  color: var(--app-muted);
  font-size: var(--text-caption);
}

.skill-card__meta dd {
  margin: 0;
  overflow: hidden;
  color: var(--app-muted-strong);
  font-size: var(--text-caption);
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-card__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-top: auto;
  padding-top: var(--space-4);
}

.skill-preview-summary {
  margin: var(--space-3) 0 0;
  color: var(--app-muted);
  font-size: var(--text-caption);
}

.skill-preview-error {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  color: var(--app-danger-text);
  background: var(--app-danger-soft);
  border: 1px solid var(--app-danger-border);
  border-radius: var(--app-radius);
  font-size: var(--text-secondary);
}

.skill-preview-error code {
  font-family: var(--font-mono);
}

/* ---------- 预览详情弹窗 ---------- */
.skill-detail {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.skill-detail__head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.skill-detail__icon {
  display: inline-flex;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  align-items: center;
  justify-content: center;
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-md);
  font-size: 20px;
}

.skill-detail__identity {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
}

.skill-detail__identity strong {
  font-size: var(--text-section);
}

.skill-detail__identity code {
  color: var(--app-muted);
  font-family: var(--font-mono);
  font-size: var(--text-caption);
}

.skill-detail__category {
  padding: 2px 8px;
  color: var(--app-primary);
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-xs);
  font-size: var(--text-caption);
  font-weight: 600;
}

.skill-detail__state {
  display: flex;
  min-height: 120px;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  color: var(--app-muted);
  font-size: var(--text-secondary);
}

.skill-detail__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-5);
  margin: 0;
  padding: var(--space-3);
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-sm);
}

.skill-detail__meta div {
  display: flex;
  gap: var(--space-1);
}

.skill-detail__meta dt {
  color: var(--app-muted);
  font-size: var(--text-caption);
}

.skill-detail__meta dd {
  margin: 0;
  color: var(--app-fg);
  font-size: var(--text-caption);
  font-variant-numeric: tabular-nums;
}

.skill-detail__desc {
  margin: 0;
  color: var(--app-muted-strong);
  font-size: var(--text-secondary);
  line-height: var(--leading-base);
  overflow-wrap: anywhere;
}

.skill-detail__tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}

.skill-chip {
  padding: 2px 8px;
  color: var(--app-muted-strong);
  background: var(--app-surface-muted);
  border: 1px solid var(--app-border-subtle);
  border-radius: var(--radius-xs);
  font-size: var(--text-caption);
}

.skill-detail__highlights {
  display: grid;
  gap: var(--space-1);
  margin: 0;
  padding: 0;
  list-style: none;
}

.skill-detail__highlights li {
  display: flex;
  align-items: flex-start;
  gap: var(--space-1);
  color: var(--app-muted-strong);
  font-size: var(--text-secondary);
}

.skill-detail__highlights svg {
  flex: 0 0 auto;
  margin-top: 3px;
  color: var(--app-success-text);
}

.skill-detail__section {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  margin: var(--space-2) 0 0;
  font-size: var(--text-body);
}

.skill-detail__section svg {
  color: var(--app-primary);
}

.skill-detail__empty {
  margin: 0;
  color: var(--app-muted);
  font-size: var(--text-secondary);
}

.skill-file-table {
  min-width: 640px;
}

.skill-file-path {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
}

.skill-file-path svg {
  flex: 0 0 auto;
  color: var(--app-primary);
}

.skill-file-path code {
  font-family: var(--font-mono);
  overflow-wrap: anywhere;
}

.sha {
  color: var(--app-muted-strong);
  font-family: var(--font-mono);
}

.skill-binary-hint {
  color: var(--app-warning-text);
  font-size: var(--text-caption);
  font-weight: 600;
}

.skill-preview {
  padding: var(--space-3);
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-sm);
}

.skill-preview__head {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  margin-bottom: var(--space-2);
  color: var(--app-muted-strong);
  font-size: var(--text-caption);
}

.skill-preview__head strong {
  color: var(--app-fg);
  font-family: var(--font-mono);
}

.skill-preview__body {
  max-height: 40vh;
  margin: 0;
  overflow: auto;
  color: var(--app-fg);
  font-family: var(--font-mono);
  font-size: var(--text-caption);
  line-height: var(--leading-base);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.skill-detail__foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-2);
}

.admin-muted {
  color: var(--app-muted);
  font-size: var(--text-caption);
}

/* ---------- 类型标签 / 双语次要行 ---------- */
.skill-cell__identity {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-1);
}

.skill-kind-tag {
  flex: 0 0 auto;
}

.skill-member-count {
  flex: 0 0 auto;
  color: var(--app-muted);
  font-size: var(--text-caption);
}

.skill-cell__name-en {
  display: block;
  margin-top: 2px;
  color: var(--app-muted);
  font-size: var(--text-caption);
  font-style: italic;
}

.skill-kind-radio :deep(.el-radio-button__inner) {
  white-space: nowrap;
}

/* ---------- 花名册编辑器 ---------- */
.skill-members {
  padding: var(--space-3);
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-sm);
}

.skill-members__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.skill-members__head h3 {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  margin: 0;
  font-size: var(--text-body);
}

.skill-members__head h3 svg {
  color: var(--app-primary);
}

.skill-members__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.skill-member-table {
  min-width: 620px;
  margin-top: var(--space-2);
}

.skill-member-table td {
  white-space: normal;
  vertical-align: top;
}

.skill-member-avatar {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.skill-member-avatar__image,
.skill-member-avatar__placeholder {
  display: inline-flex;
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-sm);
  color: var(--app-primary);
  font-weight: 700;
}

.skill-member-avatar__image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.skill-member-avatar__input {
  display: block;
  max-width: 150px;
  margin-top: var(--space-1);
}

.skill-member-field {
  margin-top: var(--space-1);
}

.skill-member-lead-tag {
  display: inline-block;
  margin-top: var(--space-1);
}

/* ---------- 包内清单（只读折叠区） ---------- */
.skill-manifest {
  padding: 0 var(--space-3);
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-sm);
}

.skill-manifest__title {
  color: var(--app-fg);
  font-size: var(--text-secondary);
  font-weight: 600;
}

.skill-manifest__body {
  max-height: 40vh;
  margin: 0 0 var(--space-2);
  padding: var(--space-2);
  overflow: auto;
  background: var(--app-surface-sunken);
  border: 1px solid var(--app-border-subtle);
  border-radius: var(--radius-xs);
  color: var(--app-fg);
  font-family: var(--font-mono);
  font-size: var(--text-caption);
  line-height: var(--leading-base);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* ---------- 抽屉里的双语次要行 ---------- */
.skill-drawer__identity {
  min-width: 0;
  flex: 1;
}

.skill-drawer__tags,
.skill-detail__badges,
.skill-card__badges {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-1);
  flex: 0 0 auto;
}

.skill-card__badges {
  margin-top: var(--space-2);
}

.skill-drawer__i18n,
.skill-detail__i18n,
.skill-card__desc--en {
  color: var(--app-muted);
  font-style: italic;
}

.skill-detail__members {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.skill-member-cards {
  display: grid;
  gap: var(--space-2);
  grid-template-columns: repeat(auto-fill, minmax(232px, 1fr));
}

.skill-member-card {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-3);
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-sm);
}

.skill-member-card__avatar {
  display: inline-flex;
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-sm);
  color: var(--app-primary);
  font-weight: 700;
}

.skill-member-card__avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.skill-member-card__body {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.skill-member-card__name-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-1);
}

.skill-member-card__profession {
  color: var(--app-muted-strong);
  font-size: var(--text-caption);
}

.skill-member-card__key {
  color: var(--app-muted);
  font-family: var(--font-mono);
  font-size: var(--text-caption);
  overflow-wrap: anywhere;
}

@media (max-width: 900px) {
  .skill-header-actions {
    flex-wrap: wrap;
  }

  .skill-bulk-toolbar {
    align-items: stretch;
  }

  .skill-bulk-toolbar__selection {
    width: 100%;
  }

  .skill-bulk-toolbar__actions {
    width: 100%;
    justify-content: flex-start;
    padding-top: var(--space-2);
    border-top: 1px solid var(--app-border-subtle);
  }

  .skill-bulk-toolbar__category {
    flex: 1 1 260px;
  }

  .skill-bulk-toolbar__category :deep(.el-select) {
    min-width: 140px;
    flex: 1 1 auto;
  }

  .skills-grid {
    grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  }
}

@media (max-width: 720px) {
  .skill-bulk-toolbar {
    padding: var(--space-3);
  }

  .skill-bulk-toolbar__selection {
    min-width: 0;
    flex-wrap: wrap;
  }

  .skill-bulk-toolbar__hint {
    width: 100%;
    padding-left: 24px;
  }

  .skill-bulk-toolbar__actions {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
  }

  .skill-bulk-toolbar__action-group {
    width: 100%;
  }

  .skill-bulk-toolbar__label {
    width: 32px;
    flex: 0 0 32px;
  }

  .skill-bulk-toolbar__status-actions,
  .skill-bulk-toolbar__status-actions :deep(.el-button),
  .skill-bulk-toolbar__download-actions {
    flex: 1 1 0;
  }

  .skill-bulk-toolbar__download-actions :deep(.el-button:first-child) {
    flex: 1 1 auto;
  }

  .skill-bulk-toolbar__category {
    flex-wrap: nowrap;
  }

  .skill-bulk-toolbar__category :deep(.el-select) {
    min-width: 0;
  }

  .skill-bulk-toolbar__divider {
    width: 100%;
    height: 1px;
    margin: var(--space-1) 0;
  }

  .skill-bulk-toolbar__danger,
  .skill-bulk-toolbar__danger :deep(.el-button) {
    width: 100%;
  }

  .skills-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .skill-version-card,
  .skill-drawer__meta {
    grid-template-columns: minmax(0, 1fr);
  }

  .skill-members__head {
    align-items: flex-start;
    flex-direction: column;
  }

  .skill-member-cards {
    grid-template-columns: minmax(0, 1fr);
  }

  .skill-drawer__foot,
  .skill-detail__foot {
    flex-direction: column-reverse;
  }

  .skill-drawer__foot :deep(.el-button),
  .skill-detail__foot :deep(.el-button) {
    width: 100%;
    margin-left: 0;
  }
}
</style>
