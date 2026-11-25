<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Archive, BarChart3, ChevronDown, History, PackageCheck, Pencil, Plus, RefreshCw, RotateCcw, Search, Sparkles } from 'lucide-vue-next'

import {
  archivePackagePlan,
  createPackagePlan,
  createPlanVersion,
  listModels,
  listPackagePlanCatalog,
  publishPlanVersion,
  restorePackagePlan,
  updatePackagePlan,
  type AdminModel,
  type AdminPackagePlan,
  type AdminPackagePlanVersion,
  type PackagePlanCatalogParams,
  type PackagePlanKind,
} from '../../api/admin'
import { readApiErrorMessage } from '../../api/apiError'
import {
  isSecondDimension,
  listPlans as listPublishedPlans,
  quotaUnitLabel,
  type PackagePlan,
  type UsageDimension,
} from '../../api/platform'
import { adminDrillQuery } from '../../router/query'
import { adminStatusLabel } from '../../utils/adminDisplay'

const route = useRoute()
const initialDrill = adminDrillQuery(route.query, [], '')
const plans = ref<AdminPackagePlan[]>([])
const publishedPlans = ref<PackagePlan[]>([])
const models = ref<AdminModel[]>([])
const total = ref(0)
const catalogLoading = ref(true)
const publishedPlansLoading = ref(true)
const modelsLoading = ref(true)
const loading = computed(() => catalogLoading.value || publishedPlansLoading.value || modelsLoading.value)
const creatingPlan = ref(false)
const creatingVersion = ref(false)
const publishingId = ref('')
const lifecycleCode = ref('')
const editing = ref(false)
const planCreateVisible = ref(false)
const versionCreateVisible = ref(false)
const editVisible = ref(false)
const errorMessage = ref('')
const pageSizeOptions = [5, 10, 20]
const params = reactive<PackagePlanCatalogParams>({
  page: 0,
  size: 10,
  query: initialDrill.search,
  status: '',
})
const planForm = reactive({
  name: '',
  description: '',
  kind: 'STANDARD' as PackagePlanKind,
})
const versionForm = reactive({
  planCode: '',
  priceAmount: 0,
  totalTokenQuota: 1_000_000,
  /**
   * 方案 C 的第二份额度（音频秒）。只有「含音频别名且主维度是视频秒」的混合
   * 版本才允许有值，其余情况一律发 null —— 服务端对纯音频版本或没有音频
   * 别名的版本收到非 null 会返回 422。
   */
  audioSecondsQuota: null as number | null,
  durationDays: 30,
  maxApiKeys: 2,
  requestsPerMinute: 60,
  tokensPerMinute: 100_000,
  maxConcurrency: 5,
  dailyRequestLimit: null as number | null,
  maxOutputTokens: 4096 as number | null,
  maxFileSizeBytes: 20 * 1024 * 1024,
  oncePerUser: false,
  stockTotal: null as number | null,
  lowStockThreshold: 0,
  maxPurchasesPerUser: null as number | null,
  modelCodes: [] as string[],
  modelQuotas: {} as Record<string, number | null>,
})
const editForm = reactive({ code: '', name: '', description: '' })

type VersionPlan = Pick<AdminPackagePlan, 'code' | 'name' | 'kind'>
const selectedPlan = ref<VersionPlan | null>(null)

const activeCatalogs = computed(() => plans.value.filter(p => p.status === 'ACTIVE').length)
const archivedCatalogs = computed(() => plans.value.filter(p => p.status === 'ARCHIVED').length)
const allVersions = computed(() => plans.value.flatMap(p => p.versions))
const publishedVersions = computed(() => allVersions.value.filter(v => v.status === 'PUBLISHED'))
const allDraftVersions = computed(() => allVersions.value.filter(v => v.status === 'DRAFT'))
const allRetiredVersions = computed(() => allVersions.value.filter(v => v.status === 'RETIRED'))
const topPublishedPlans = computed(() => [...publishedPlans.value].sort((a, b) => (b.stockSold ?? 0) - (a.stockSold ?? 0)).slice(0, 5))
const totalSales = computed(() => publishedPlans.value.reduce((s, p) => s + (p.stockSold ?? 0), 0))

// 当前页内按状态分组：在售在主列表展示，已下架移入独立折叠面板
const activePagePlans = computed(() => plans.value.filter(p => p.status === 'ACTIVE'))
const archivedPagePlans = computed(() => plans.value.filter(p => p.status === 'ARCHIVED'))

async function loadCatalog(targetPage = params.page) {
  catalogLoading.value = true
  errorMessage.value = ''
  try {
    const result = await listPackagePlanCatalog({ ...params, page: targetPage })
    plans.value = result.items
    total.value = result.total
    params.page = result.page
  } catch (error: unknown) {
    errorMessage.value = readApiErrorMessage(error) || '套餐目录加载失败，请稍后重试。'
  } finally {
    catalogLoading.value = false
  }
}

async function loadModels() {
  modelsLoading.value = true
  try {
    const result = await listModels({
      page: 0, size: 100, query: '', status: 'ENABLED', sort: 'code', direction: 'asc',
    })
    models.value = result.items
  } catch (error: unknown) {
    errorMessage.value = readApiErrorMessage(error) || '网关模型目录加载失败，请稍后重试。'
  } finally {
    modelsLoading.value = false
  }
}

async function loadPublishedPlans() {
  publishedPlansLoading.value = true
  try {
    publishedPlans.value = await listPublishedPlans()
  } catch {
    publishedPlans.value = []
  } finally {
    publishedPlansLoading.value = false
  }
}

function load() {
  return Promise.all([loadCatalog(), loadModels(), loadPublishedPlans()])
}

async function refreshCatalog(targetPage = params.page) {
  await Promise.all([loadCatalog(targetPage), loadPublishedPlans()])
}

function changePageSize(size: number) {
  params.size = size
  void loadCatalog(0)
}

function currentVersion(plan: AdminPackagePlan) {
  return plan.versions.find(version => version.status === 'PUBLISHED') || null
}

function draftVersions(plan: AdminPackagePlan) {
  return plan.versions.filter(version => version.status === 'DRAFT')
}

function visibleVersions(plan: AdminPackagePlan) {
  const current = currentVersion(plan)
  return current ? [current, ...draftVersions(plan)] : draftVersions(plan)
}

function historyVersions(plan: AdminPackagePlan) {
  return plan.versions.filter(version => version.status === 'RETIRED')
}

function formatAmount(amount: number) {
  return `¥${(amount / 100).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`
}

function formatQuota(value: number) {
  return value > 0 ? `${value.toLocaleString('zh-CN')} 积分` : '不限'
}

/**
 * 音频秒那一份额度（方案 C）。单位**永远是秒**，不随版本主维度变化。
 *
 * 调用点用 `v-if="version.audioSecondsQuota != null"` 守住整块：null 表示这个
 * 版本不卖语音，那种情况显示 "0 秒" 就是一条假承诺（数据库的 CHECK 也正是
 * 为此拒绝 0 额度）。这里对 null 返回空串而不是 0，将来漏写守卫的调用点也
 * 不会凭空造出一个 "0 秒"。
 */
function formatAudioSecondsQuota(version: AdminPackagePlanVersion) {
  const seconds = version.audioSecondsQuota
  return seconds == null ? '' : `${seconds.toLocaleString('zh-CN')} 秒`
}

function formatStock(version: AdminPackagePlanVersion) {
  if (version.stockTotal === null) return `不限 · 已售 ${version.stockSold}`
  return `${version.remainingStock ?? 0} / ${version.stockTotal} · 已售 ${version.stockSold}`
}

function formatMargin(version: AdminPackagePlanVersion) {
  return version.estimatedMarginRatio == null ? '—' : `${(version.estimatedMarginRatio * 100).toFixed(1)}%`
}

/**
 * 预估成本（分 → 元）：null 表示该版本还没有成本口径，渲染破折号而不是 0，
 * 因为 0 会被读成"成本为零"，和"没有数据"是完全不同的结论。
 */
function formatEstimatedCost(version: AdminPackagePlanVersion) {
  return version.estimatedCostAmount == null ? '—' : formatAmount(version.estimatedCostAmount)
}

/** 毛利预警的完整解释：用户端不出现成本与毛利，这句只给运营看。 */
const MARGIN_WARNING_TEXT = '毛利预警：最坏情况毛利落在 30%–40% 目标区间内'

function versionModels(version: AdminPackagePlanVersion) {
  return publishedPlans.value.find(plan => plan.versionId === version.versionId)?.models || []
}

function versionStatusType(status: AdminPackagePlanVersion['status']) {
  return status === 'PUBLISHED' ? 'success' : status === 'DRAFT' ? 'info' : 'warning'
}

function planKindLabel(kind: PackagePlanKind) {
  return kind === 'FREE_CLAIM' ? '免费领取' : '标准套餐'
}

function planKindType(kind: PackagePlanKind) {
  return kind === 'FREE_CLAIM' ? 'success' : 'primary'
}

function openCreatePlan() {
  planForm.name = ''
  planForm.description = ''
  planForm.kind = 'STANDARD'
  planCreateVisible.value = true
}

function resetVersionForm(plan: VersionPlan) {
  versionForm.planCode = plan.code
  versionForm.priceAmount = 0
  versionForm.totalTokenQuota = 1_000_000
  versionForm.audioSecondsQuota = null
  versionForm.durationDays = 30
  versionForm.maxApiKeys = 2
  versionForm.requestsPerMinute = 60
  versionForm.tokensPerMinute = 100_000
  versionForm.maxConcurrency = 5
  versionForm.dailyRequestLimit = null
  versionForm.maxOutputTokens = 4096
  versionForm.maxFileSizeBytes = 20 * 1024 * 1024
  versionForm.oncePerUser = plan.kind === 'FREE_CLAIM'
  versionForm.stockTotal = null
  versionForm.lowStockThreshold = 0
  versionForm.maxPurchasesPerUser = plan.kind === 'FREE_CLAIM' ? 1 : null
  versionForm.modelCodes = []
  versionForm.modelQuotas = {}
}

const VIDEO_CAPABILITY = 'VIDEO'
/**
 * 音频别名的三种能力名（网关 V93 建立，平台 ALLOWED_CAPABILITIES 同步）。
 * 转写、合成、唇形同步同属 `AUDIO_SECOND` 一个维度，因此三者共用同一份
 * 音频秒额度；任一能力命中即算音频模型。
 */
const AUDIO_CAPABILITIES = ['AUDIO_TRANSCRIBE', 'AUDIO_SYNTHESIS', 'LIP_SYNC']

function modelCapabilities(code: string) {
  return models.value.find(model => model.code === code)?.capabilities || []
}

function isVideoModel(code: string) {
  return modelCapabilities(code).includes(VIDEO_CAPABILITY)
}

function isAudioModel(code: string) {
  const capabilities = modelCapabilities(code)
  return AUDIO_CAPABILITIES.some(capability => capabilities.includes(capability))
}

function modelOptionLabel(code: string) {
  const model = models.value.find(item => item.code === code)
  return model ? `${model.displayName} · ${model.code}` : code
}

/**
 * 版本的主维度，与服务端 `PackageVersionService.resolveDimension` 同一条判据：
 * 全是视频 → `VIDEO_SECOND`；全是音频 → `AUDIO_SECOND`；视频 + 音频 →
 * `VIDEO_SECOND`（音频秒走第二份桶）；其余组合是 `TOKEN`。
 *
 * 客户端只能按能力名推导，服务端用的是 `platform.model_usage_dimension`；
 * 两者不一致时服务端仍会拒绝，这里只是让运营在提交前就看到正确的单位文案。
 */
const usageDimension = computed<UsageDimension>(() => {
  const codes = versionForm.modelCodes
  if (!codes.length) return 'TOKEN'
  if (codes.every(isVideoModel)) return 'VIDEO_SECOND'
  if (codes.every(isAudioModel)) return 'AUDIO_SECOND'
  if (codes.some(isVideoModel) && codes.some(isAudioModel)) return 'VIDEO_SECOND'
  return 'TOKEN'
})

/**
 * 不能共存的维度组合：按秒的模型（视频/音频）与按积分（或按张）的模型混在
 * 一个版本里，服务端会以 `mixed_usage_dimensions_in_package` 拒绝。
 *
 * 视频 + 音频**不再**算混用：自方案 C 起视频秒与音频秒各有一列额度
 * （`total_token_quota` 与 `audio_seconds_quota`），"视频秒 + 音频秒"的套餐
 * 正是要卖的东西。token 与按秒在结构上不是同一列，仍然必须拆开。
 */
const mixedUsageDimensions = computed(() => {
  const perSecond = versionForm.modelCodes.some(code => isVideoModel(code) || isAudioModel(code))
  const perUnit = versionForm.modelCodes.some(code => !isVideoModel(code) && !isAudioModel(code))
  return perSecond && perUnit
})

/** 选中的模型里是否有音频别名。 */
const hasAudioSelection = computed(() => versionForm.modelCodes.some(isAudioModel))

/**
 * 是否需要填第二份额度：含音频别名**且**主维度是视频秒。
 * 纯音频版本的主额度列本身就是音频秒，再填一份会让"这次请求扣哪一列"出现
 * 两个都说得通的答案，因此服务端要求它必须是 null。
 */
const requiresAudioSecondsQuota = computed(() => usageDimension.value === 'VIDEO_SECOND'
  && hasAudioSelection.value)

/**
 * 音频秒那一格只在混合版本出现。收起时必须清空：运营先选"视频 + 音频"填了
 * 600、再删掉视频别名改成纯音频时，表单里那个 600 会被发出去，而服务端对
 * 纯音频版本要求它是 null（`audio_seconds_quota_on_a_pure_audio_version`）。
 */
watch(requiresAudioSecondsQuota, (required) => {
  if (!required) versionForm.audioSecondsQuota = null
})

// 单位文案统一走 api/platform 的判据，不再就地写一遍联合类型的比较。
const quotaUnit = computed(() => quotaUnitLabel(usageDimension.value))
// 套餐总额度按「秒数 / 积分」计数，分模型额度与速率则按「秒 / 积分」描述。
const totalQuotaUnit = computed(() => (isSecondDimension(usageDimension.value) ? '秒数' : '积分'))
const totalQuotaLabel = computed(() => `总${totalQuotaUnit.value}额度`)
const rateLabel = computed(() => `${quotaUnit.value} / 分钟`)
const modelQuotaLabel = computed(() => `${quotaUnit.value}额度`)

/**
 * 分模型额度的上限取自**它自己**那一份桶，与服务端 `validateRequest` 同一条
 * 规则：视频模型受总秒数额度约束；音频模型在混合版本里受音频秒额度约束，
 * 在没有第二份桶时（纯音频版本）受主额度约束。
 */
function modelQuotaCeiling(code: string): number {
  if (!isAudioModel(code)) return versionForm.totalTokenQuota
  return requiresAudioSecondsQuota.value
    ? (versionForm.audioSecondsQuota ?? 0)
    : versionForm.totalTokenQuota
}

// 清空输入或填入非正数表示该模型不设单独额度，可消耗整个套餐额度。
function setModelQuota(code: string, value: number | null | undefined) {
  versionForm.modelQuotas[code] = typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : null
}

function openCreateVersion(plan: VersionPlan) {
  selectedPlan.value = plan
  resetVersionForm(plan)
  versionCreateVisible.value = true
}

async function submitPlanCreate() {
  const name = planForm.name.trim()
  const description = planForm.description.trim()
  if (!name) {
    errorMessage.value = '套餐名称不能为空。'
    return
  }
  if (name.length > 100 || description.length > 500) {
    errorMessage.value = '套餐名称最多 100 个字符，描述最多 500 个字符。'
    return
  }
  creatingPlan.value = true
  errorMessage.value = ''
  try {
    const created = await createPackagePlan({ name, description: description || null, kind: planForm.kind })
    planCreateVisible.value = false
    await refreshCatalog(0)
    const catalogPlan = plans.value.find(plan => plan.code === created.code)
    openCreateVersion(catalogPlan || created)
    ElMessage.success('套餐已创建，请配置首个版本')
  } catch (error: unknown) {
    errorMessage.value = readApiErrorMessage(error) || '套餐创建失败，请稍后重试。'
  } finally {
    creatingPlan.value = false
  }
}

function openEdit(plan: AdminPackagePlan) {
  editForm.code = plan.code
  editForm.name = plan.name
  editForm.description = plan.description || ''
  editVisible.value = true
}

async function submitPlanEdit() {
  const name = editForm.name.trim()
  const description = editForm.description.trim()
  if (!name) {
    errorMessage.value = '套餐名称不能为空。'
    return
  }
  if (name.length > 100 || description.length > 500) {
    errorMessage.value = '套餐名称最多 100 个字符，描述最多 500 个字符。'
    return
  }
  editing.value = true
  errorMessage.value = ''
  try {
    await updatePackagePlan(editForm.code, { name, description: description || null })
    editVisible.value = false
    await refreshCatalog()
    ElMessage.success('套餐信息已更新')
  } catch (error: unknown) {
    errorMessage.value = readApiErrorMessage(error) || '套餐信息更新失败，请稍后重试。'
  } finally {
    editing.value = false
  }
}

async function archivePlan(plan: AdminPackagePlan) {
  const confirmed = await ElMessageBox.confirm(
    `下架"${plan.name}"后将停止新购买、领取和管理员发放，现有套餐权益不受影响。`,
    '确认下架套餐',
    { type: 'warning', confirmButtonText: '确认下架', cancelButtonText: '取消' },
  ).then(() => true).catch(() => false)
  if (!confirmed) return

  lifecycleCode.value = plan.code
  errorMessage.value = ''
  try {
    await archivePackagePlan(plan.code)
    await refreshCatalog()
    ElMessage.success('套餐已下架')
  } catch (error: unknown) {
    errorMessage.value = readApiErrorMessage(error) || '套餐下架失败，请稍后重试。'
  } finally {
    lifecycleCode.value = ''
  }
}

async function restorePlan(plan: AdminPackagePlan) {
  lifecycleCode.value = plan.code
  errorMessage.value = ''
  try {
    await restorePackagePlan(plan.code)
    await refreshCatalog()
    ElMessage.success('套餐已恢复上线')
  } catch (error: unknown) {
    errorMessage.value = readApiErrorMessage(error) || '套餐恢复失败，请稍后重试。'
  } finally {
    lifecycleCode.value = ''
  }
}

function validateVersionDraft() {
  if (!selectedPlan.value) return '请选择套餐。'
  if (selectedPlan.value.kind === 'STANDARD' && versionForm.priceAmount <= 0) return '标准套餐售价必须大于 0。'
  if (selectedPlan.value.kind === 'FREE_CLAIM' && versionForm.priceAmount !== 0) return '免费领取套餐售价必须为 0。'
  if (versionForm.totalTokenQuota <= 0 || versionForm.durationDays <= 0) return '额度与有效期必须大于 0。'
  if (!versionForm.modelCodes.length) return '至少选择一个可用模型。'
  if (mixedUsageDimensions.value) {
    return '同一版本不能同时选择视频模型与其他模型：视频模型按秒计费，其他模型按积分（或按张）计费；音频模型（ASR/TTS/唇形同步）同样不能与它们混在一个版本里。请拆分为两个版本后再创建（mixed_usage_dimensions_in_package）。'
  }
  if (requiresAudioSecondsQuota.value
    && (versionForm.audioSecondsQuota === null || versionForm.audioSecondsQuota <= 0)) {
    return '含音频别名（ASR/TTS/唇形同步）的视频版本必须单独填写音频秒额度且大于 0：音频用量扣的是这一份，视频秒不会替它买单（audio_alias_requires_audio_seconds_quota）。'
  }
  const modelQuotaError = validateModelQuotas()
  if (modelQuotaError) return modelQuotaError
  if (versionForm.stockTotal !== null && versionForm.stockTotal < 0) return '库存总量不能小于 0。'
  if (versionForm.lowStockThreshold < 0 || (versionForm.stockTotal !== null && versionForm.lowStockThreshold > versionForm.stockTotal)) return '低库存阈值无效。'
  if (versionForm.maxPurchasesPerUser !== null && versionForm.maxPurchasesPerUser <= 0) return '单用户购买上限无效。'
  return ''
}

/**
 * 分模型额度的上限校验，与服务端 `invalid_model_permission` 同一条规则。
 *
 * 在本地说清楚是哪一个模型、超了哪一份额度，运营就不必从一个 422 的错误码
 * 反推自己填错了哪一格——把两个维度的数字对调是最容易犯的错。
 */
function validateModelQuotas(): string {
  for (const code of versionForm.modelCodes) {
    const quota = versionForm.modelQuotas[code]
    if (quota === null || quota === undefined) continue
    if (!Number.isFinite(quota) || quota <= 0) {
      return `「${code}」的单独额度必须大于 0，或留空表示不设单独额度（invalid_model_permission）。`
    }
    const ceiling = modelQuotaCeiling(code)
    if (quota > ceiling) {
      const bucket = isAudioModel(code) && requiresAudioSecondsQuota.value ? '音频秒额度' : '套餐总额度'
      return `「${code}」的单独额度不能超过${bucket} ${ceiling.toLocaleString('zh-CN')}（invalid_model_permission）。`
    }
  }
  return ''
}

async function submitVersionDraft() {
  const validation = validateVersionDraft()
  if (validation) {
    errorMessage.value = validation
    return
  }
  creatingVersion.value = true
  errorMessage.value = ''
  try {
    await createPlanVersion({
      planCode: versionForm.planCode,
      priceAmount: versionForm.priceAmount,
      totalTokenQuota: versionForm.totalTokenQuota,
      // 非混合版本必须显式发 null：服务端把"省略"与 null 同样读成"不卖语音"，
      // 但显式写出这件事，才不会在下次改字段时静默漏掉一整个维度。
      audioSecondsQuota: requiresAudioSecondsQuota.value ? versionForm.audioSecondsQuota : null,
      durationDays: versionForm.durationDays,
      maxApiKeys: versionForm.maxApiKeys,
      requestsPerMinute: versionForm.requestsPerMinute,
      tokensPerMinute: versionForm.tokensPerMinute,
      maxConcurrency: versionForm.maxConcurrency,
      dailyRequestLimit: versionForm.dailyRequestLimit,
      maxOutputTokens: versionForm.maxOutputTokens,
      maxFileSizeBytes: versionForm.maxFileSizeBytes,
      oncePerUser: selectedPlan.value?.kind === 'FREE_CLAIM' ? true : versionForm.oncePerUser,
      stockTotal: versionForm.stockTotal,
      lowStockThreshold: versionForm.lowStockThreshold,
      maxPurchasesPerUser: versionForm.maxPurchasesPerUser,
      models: versionForm.modelCodes.map(modelCode => ({
        modelCode,
        modelTokenQuota: versionForm.modelQuotas[modelCode] ?? null,
        capabilities: models.value.find(model => model.code === modelCode)?.capabilities || [],
      })),
    })
    versionCreateVisible.value = false
    await loadCatalog()
    ElMessage.success('套餐版本草稿已创建')
  } catch (error: unknown) {
    errorMessage.value = readApiErrorMessage(error)
      || '套餐版本创建失败。请检查售价、额度、模型权限和平台毛利。'
  } finally {
    creatingVersion.value = false
  }
}

async function publish(plan: AdminPackagePlan, version: AdminPackagePlanVersion) {
  if (version.status !== 'DRAFT') return
  const current = currentVersion(plan)
  const message = current
    ? `发布 v${version.version} 后，当前 v${current.version} 将自动退役并保留在历史版本中。`
    : `确认发布"${plan.name}"v${version.version}？`
  const confirmed = await ElMessageBox.confirm(message, '确认发布版本', {
    type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '取消',
  }).then(() => true).catch(() => false)
  if (!confirmed) return

  publishingId.value = version.versionId
  errorMessage.value = ''
  try {
    await publishPlanVersion(plan.code, version.version)
    await refreshCatalog()
    ElMessage.success('新版本已发布，旧版本已转入历史')
  } catch (error: unknown) {
    errorMessage.value = readApiErrorMessage(error) || '套餐发布失败，请检查平台毛利、成本快照或版本状态。'
  } finally {
    publishingId.value = ''
  }
}

onMounted(load)
watch(() => route.query, query => {
  params.query = adminDrillQuery(query, [], '').search
  void loadCatalog(0)
}, { deep: true })
</script>

<template>
  <section class="admin-page" aria-labelledby="admin-plans-title">
    <header class="admin-page__header">
      <div><h2 id="admin-plans-title">套餐管理</h2><p>管理套餐目录、当前版本与历史版本</p></div>
      <div class="plan-header-actions">
        <el-button :loading="loading" @click="load"><RefreshCw :size="16" />刷新</el-button>
        <el-button data-test="plan-create-toggle" type="primary" @click="openCreatePlan"><Plus :size="16" />新建套餐</el-button>
      </div>
    </header>

    <TopErrorMessage :message="errorMessage" />

    <section class="plan-stats-grid" aria-label="版本统计">
      <div class="plan-stat-card">
        <span class="plan-stat-card__icon plan-stat-card__icon--total"><PackageCheck :size="18" /></span>
        <span class="plan-stat-card__label">总套餐目录</span>
        <strong class="plan-stat-card__value">{{ total }}</strong>
        <span class="plan-stat-card__hint">{{ allVersions.length }} 个版本</span>
      </div>
      <div class="plan-stat-card">
        <span class="plan-stat-card__icon plan-stat-card__icon--active"><Sparkles :size="18" /></span>
        <span class="plan-stat-card__label">生效版本</span>
        <strong class="plan-stat-card__value">{{ publishedVersions.length }}</strong>
        <span class="plan-stat-card__hint">{{ allDraftVersions.length }} 个草稿待发布</span>
      </div>
      <div class="plan-stat-card">
        <span class="plan-stat-card__icon plan-stat-card__icon--archived"><Archive :size="18" /></span>
        <span class="plan-stat-card__label">已下架目录</span>
        <strong class="plan-stat-card__value">{{ archivedCatalogs }}</strong>
        <span class="plan-stat-card__hint">{{ allRetiredVersions.length }} 个已退役版本</span>
      </div>
      <div class="plan-stat-card">
        <span class="plan-stat-card__icon plan-stat-card__icon--sales"><BarChart3 :size="18" /></span>
        <span class="plan-stat-card__label">累计销量</span>
        <strong class="plan-stat-card__value">{{ totalSales }}</strong>
        <span class="plan-stat-card__hint">已上线版本合计</span>
      </div>
    </section>

    <div class="plan-main-layout">
      <div class="plan-main-layout__main">
    <section class="plan-catalog-section" data-test="plan-catalog" aria-labelledby="plan-catalog-title">
      <div class="plan-section-header">
        <div><h3 id="plan-catalog-title">套餐目录</h3><p>展示在售套餐的当前版本和草稿，已下架套餐收纳在下方独立区域</p></div>
      </div>
      <div class="admin-toolbar plans-toolbar">
        <el-input v-model="params.query" data-test="plans-query" aria-label="搜索套餐" clearable placeholder="搜索套餐代码或名称" @keyup.enter="loadCatalog(0)" />
        <el-select v-model="params.status" data-test="plans-status" aria-label="套餐状态" clearable placeholder="全部状态"><el-option label="已上线" value="ACTIVE" /><el-option label="已下架" value="ARCHIVED" /></el-select>
        <el-button data-test="plans-filter" type="primary" :loading="catalogLoading" @click="loadCatalog(0)"><Search :size="16" />查询</el-button>
      </div>

      <el-skeleton v-if="catalogLoading" :rows="6" animated />
      <div v-else class="plan-list">
        <div v-if="activePagePlans.length" class="plan-group-header plan-group-header--active" data-test="plan-active-group-header">
          <span class="plan-group-header__label"><Sparkles :size="15" aria-hidden="true" />在售套餐</span>
          <span class="plan-group-header__count">{{ activePagePlans.length }} 个</span>
          <span class="plan-group-header__hint">正常售卖、领取与管理员发放</span>
        </div>
        <template v-for="plan in activePagePlans" :key="plan.code">
          <article class="plan-item" :class="{ 'plan-item--archived': plan.status === 'ARCHIVED' }">
          <header class="plan-item__header">
            <div class="plan-item__identity">
              <div class="plan-item__title"><h3>{{ plan.name }}</h3><el-tag size="small" :type="planKindType(plan.kind)">{{ planKindLabel(plan.kind) }}</el-tag><el-tag size="small" :type="plan.status === 'ACTIVE' ? 'success' : 'info'">{{ plan.status === 'ACTIVE' ? '已上线' : '已下架' }}</el-tag></div>
              <code>{{ plan.code }}</code>
              <p>{{ plan.description || '暂无套餐描述' }}</p>
            </div>
            <div class="plan-item__actions">
              <el-button :data-test="`plan-version-create-${plan.code}`" size="small" type="primary" plain @click="openCreateVersion(plan)"><Plus :size="15" />新建版本</el-button>
              <el-button :data-test="`plan-edit-${plan.code}`" size="small" @click="openEdit(plan)"><Pencil :size="15" />编辑</el-button>
              <el-button v-if="plan.status === 'ACTIVE'" :data-test="`plan-archive-${plan.code}`" size="small" type="danger" plain :loading="lifecycleCode === plan.code" @click="archivePlan(plan)"><Archive :size="15" />下架</el-button>
              <el-button v-else :data-test="`plan-restore-${plan.code}`" size="small" type="primary" plain :loading="lifecycleCode === plan.code" @click="restorePlan(plan)"><RotateCcw :size="15" />恢复</el-button>
            </div>
          </header>

          <div v-if="visibleVersions(plan).length" class="plan-versions">
            <section v-for="version in visibleVersions(plan)" :key="version.versionId" class="plan-version-row" :class="{ 'plan-version-row--draft': version.status === 'DRAFT', 'plan-version-row--published': version.status === 'PUBLISHED' }">
              <div class="plan-version-row__head">
                <div><strong>v{{ version.version }}</strong><el-tag size="small" :type="versionStatusType(version.status)">{{ adminStatusLabel(version.status) }}</el-tag><span v-if="version.status === 'PUBLISHED'" class="current-version-label">当前版本</span></div>
                <el-button v-if="version.status === 'DRAFT'" :data-test="`plan-publish-${version.versionId}`" type="primary" size="small" :loading="publishingId === version.versionId" @click="publish(plan, version)">发布版本</el-button>
              </div>
              <div class="plan-spec-grid">
                <div class="plan-spec"><span>定价</span><strong class="plan-spec-price">{{ version.priceAmount === 0 ? '免费' : formatAmount(version.priceAmount) }}</strong></div>
                <div class="plan-spec"><span>积分额度</span><strong>{{ formatQuota(version.totalTokenQuota) }}</strong></div>
                <!-- 方案 C 的第二份额度：null 表示这个版本不卖语音，整块不渲染（不显示 0 秒） -->
                <div v-if="version.audioSecondsQuota != null" class="plan-spec" data-test="plan-audio-seconds-quota">
                  <span>音频秒额度</span><strong>{{ formatAudioSecondsQuota(version) }}</strong>
                </div>
                <div class="plan-spec"><span>请求速率</span><strong>{{ version.requestsPerMinute.toLocaleString('zh-CN') }} 次/分钟</strong></div>
                <div class="plan-spec"><span>API 密钥</span><strong>{{ version.maxApiKeys > 0 ? `${version.maxApiKeys} 个` : '不限' }}</strong></div>
                <div class="plan-spec"><span>库存</span><strong :class="{ 'plan-spec-danger': version.soldOut, 'plan-spec-warning': version.lowStock && !version.soldOut }">{{ version.soldOut ? '已售罄' : formatStock(version) }}</strong></div>
                <!-- 成本与毛利是内部成本结构：只在这里（管理端套餐版本）展示，用户端套餐列表不出现 -->
                <div class="plan-spec" data-test="plan-margin">
                  <span>最坏毛利</span>
                  <div class="plan-spec__inline">
                    <strong data-test="plan-margin-ratio" :class="{ 'plan-spec-warning': version.marginWarning }">{{ formatMargin(version) }}</strong>
                    <el-tag v-if="version.marginWarning" data-test="plan-margin-badge" size="small" type="warning" :title="MARGIN_WARNING_TEXT">预警</el-tag>
                  </div>
                </div>
                <div class="plan-spec" data-test="plan-cost"><span>预估成本</span><strong data-test="plan-cost-amount">{{ formatEstimatedCost(version) }}</strong></div>
              </div>
              <p v-if="version.marginWarning" class="plan-margin-warning" data-test="plan-margin-warning">{{ MARGIN_WARNING_TEXT }}</p>
              <div class="plan-version-row__footer"><div class="plan-models"><span>可调用模型：</span><template v-if="versionModels(version).length"><el-tag v-for="model in versionModels(version)" :key="model.modelCode" size="small" type="info">{{ model.modelCode }}</el-tag></template><span v-else class="admin-muted">{{ version.modelCount }} 个模型</span></div><small>有效期 {{ version.durationDays }} 天 · 最大并发 {{ version.maxConcurrency }} · 积分/分钟 {{ version.tokensPerMinute.toLocaleString('zh-CN') }}</small></div>
            </section>
          </div>
          <div v-else class="plan-no-version"><span>尚未创建套餐版本</span><el-button type="primary" plain size="small" @click="openCreateVersion(plan)">创建首个版本</el-button></div>

          <details v-if="historyVersions(plan).length" class="plan-history" :data-test="`plan-history-${plan.code}`">
            <summary><span><History :size="15" />历史版本（{{ historyVersions(plan).length }}）</span><ChevronDown :size="16" /></summary>
            <div class="plan-history__list">
              <div v-for="version in historyVersions(plan)" :key="version.versionId" class="plan-history-row">
                <div><strong>v{{ version.version }}</strong><el-tag size="small" type="warning">已退役</el-tag><span>{{ version.priceAmount === 0 ? '免费' : formatAmount(version.priceAmount) }}</span><span>{{ formatQuota(version.totalTokenQuota) }}</span><span v-if="version.audioSecondsQuota != null">音频 {{ formatAudioSecondsQuota(version) }}</span></div>
                <small>已售 {{ version.stockSold }} · 发布于 {{ version.publishedAt ? new Date(version.publishedAt).toLocaleDateString('zh-CN') : '-' }} · 退役于 {{ version.retiredAt ? new Date(version.retiredAt).toLocaleDateString('zh-CN') : '-' }}</small>
              </div>
            </div>
          </details>
        </article>
        </template>
        <div v-if="!plans.length" class="admin-empty">暂无套餐，可先新建套餐。</div>
      </div>

      <!-- 已下架套餐：独立区块，与在售套餐完全分离 -->
      <details v-if="!catalogLoading && archivedPagePlans.length" class="plan-archived-panel" data-test="plan-archived-panel">
        <summary class="plan-archived-panel__header">
          <span class="plan-archived-panel__label"><Archive :size="16" aria-hidden="true" />已下架套餐</span>
          <span class="plan-archived-panel__count">{{ archivedPagePlans.length }} 个</span>
          <span class="plan-archived-panel__hint">已停止售卖与发放，点击展开管理，可恢复上架</span>
          <ChevronDown :size="16" class="plan-archived-panel__chevron" aria-hidden="true" />
        </summary>
        <div class="plan-list plan-archived-panel__list">
          <article v-for="plan in archivedPagePlans" :key="plan.code" class="plan-item plan-item--archived">
          <header class="plan-item__header">
            <div class="plan-item__identity">
              <div class="plan-item__title"><h3>{{ plan.name }}</h3><el-tag size="small" :type="planKindType(plan.kind)">{{ planKindLabel(plan.kind) }}</el-tag><el-tag size="small" :type="plan.status === 'ACTIVE' ? 'success' : 'info'">{{ plan.status === 'ACTIVE' ? '已上线' : '已下架' }}</el-tag></div>
              <code>{{ plan.code }}</code>
              <p>{{ plan.description || '暂无套餐描述' }}</p>
            </div>
            <div class="plan-item__actions">
              <el-button :data-test="`plan-version-create-${plan.code}`" size="small" type="primary" plain @click="openCreateVersion(plan)"><Plus :size="15" />新建版本</el-button>
              <el-button :data-test="`plan-edit-${plan.code}`" size="small" @click="openEdit(plan)"><Pencil :size="15" />编辑</el-button>
              <el-button v-if="plan.status === 'ACTIVE'" :data-test="`plan-archive-${plan.code}`" size="small" type="danger" plain :loading="lifecycleCode === plan.code" @click="archivePlan(plan)"><Archive :size="15" />下架</el-button>
              <el-button v-else :data-test="`plan-restore-${plan.code}`" size="small" type="primary" plain :loading="lifecycleCode === plan.code" @click="restorePlan(plan)"><RotateCcw :size="15" />恢复</el-button>
            </div>
          </header>

          <div v-if="visibleVersions(plan).length" class="plan-versions">
            <section v-for="version in visibleVersions(plan)" :key="version.versionId" class="plan-version-row" :class="{ 'plan-version-row--draft': version.status === 'DRAFT', 'plan-version-row--published': version.status === 'PUBLISHED' }">
              <div class="plan-version-row__head">
                <div><strong>v{{ version.version }}</strong><el-tag size="small" :type="versionStatusType(version.status)">{{ adminStatusLabel(version.status) }}</el-tag><span v-if="version.status === 'PUBLISHED'" class="current-version-label">当前版本</span></div>
                <el-button v-if="version.status === 'DRAFT'" :data-test="`plan-publish-${version.versionId}`" type="primary" size="small" :loading="publishingId === version.versionId" @click="publish(plan, version)">发布版本</el-button>
              </div>
              <div class="plan-spec-grid">
                <div class="plan-spec"><span>定价</span><strong class="plan-spec-price">{{ version.priceAmount === 0 ? '免费' : formatAmount(version.priceAmount) }}</strong></div>
                <div class="plan-spec"><span>积分额度</span><strong>{{ formatQuota(version.totalTokenQuota) }}</strong></div>
                <!-- 方案 C 的第二份额度：null 表示这个版本不卖语音，整块不渲染（不显示 0 秒） -->
                <div v-if="version.audioSecondsQuota != null" class="plan-spec" data-test="plan-audio-seconds-quota">
                  <span>音频秒额度</span><strong>{{ formatAudioSecondsQuota(version) }}</strong>
                </div>
                <div class="plan-spec"><span>请求速率</span><strong>{{ version.requestsPerMinute.toLocaleString('zh-CN') }} 次/分钟</strong></div>
                <div class="plan-spec"><span>API 密钥</span><strong>{{ version.maxApiKeys > 0 ? `${version.maxApiKeys} 个` : '不限' }}</strong></div>
                <div class="plan-spec"><span>库存</span><strong :class="{ 'plan-spec-danger': version.soldOut, 'plan-spec-warning': version.lowStock && !version.soldOut }">{{ version.soldOut ? '已售罄' : formatStock(version) }}</strong></div>
                <!-- 成本与毛利是内部成本结构：只在这里（管理端套餐版本）展示，用户端套餐列表不出现 -->
                <div class="plan-spec" data-test="plan-margin">
                  <span>最坏毛利</span>
                  <div class="plan-spec__inline">
                    <strong data-test="plan-margin-ratio" :class="{ 'plan-spec-warning': version.marginWarning }">{{ formatMargin(version) }}</strong>
                    <el-tag v-if="version.marginWarning" data-test="plan-margin-badge" size="small" type="warning" :title="MARGIN_WARNING_TEXT">预警</el-tag>
                  </div>
                </div>
                <div class="plan-spec" data-test="plan-cost"><span>预估成本</span><strong data-test="plan-cost-amount">{{ formatEstimatedCost(version) }}</strong></div>
              </div>
              <p v-if="version.marginWarning" class="plan-margin-warning" data-test="plan-margin-warning">{{ MARGIN_WARNING_TEXT }}</p>
              <div class="plan-version-row__footer"><div class="plan-models"><span>可调用模型：</span><template v-if="versionModels(version).length"><el-tag v-for="model in versionModels(version)" :key="model.modelCode" size="small" type="info">{{ model.modelCode }}</el-tag></template><span v-else class="admin-muted">{{ version.modelCount }} 个模型</span></div><small>有效期 {{ version.durationDays }} 天 · 最大并发 {{ version.maxConcurrency }} · 积分/分钟 {{ version.tokensPerMinute.toLocaleString('zh-CN') }}</small></div>
            </section>
          </div>
          <div v-else class="plan-no-version"><span>尚未创建套餐版本</span><el-button type="primary" plain size="small" @click="openCreateVersion(plan)">创建首个版本</el-button></div>

          <details v-if="historyVersions(plan).length" class="plan-history" :data-test="`plan-history-${plan.code}`">
            <summary><span><History :size="15" />历史版本（{{ historyVersions(plan).length }}）</span><ChevronDown :size="16" /></summary>
            <div class="plan-history__list">
              <div v-for="version in historyVersions(plan)" :key="version.versionId" class="plan-history-row">
                <div><strong>v{{ version.version }}</strong><el-tag size="small" type="warning">已退役</el-tag><span>{{ version.priceAmount === 0 ? '免费' : formatAmount(version.priceAmount) }}</span><span>{{ formatQuota(version.totalTokenQuota) }}</span><span v-if="version.audioSecondsQuota != null">音频 {{ formatAudioSecondsQuota(version) }}</span></div>
                <small>已售 {{ version.stockSold }} · 发布于 {{ version.publishedAt ? new Date(version.publishedAt).toLocaleDateString('zh-CN') : '-' }} · 退役于 {{ version.retiredAt ? new Date(version.retiredAt).toLocaleDateString('zh-CN') : '-' }}</small>
              </div>
            </div>
          </details>
        </article>
        </div>
      </details>

      <div class="admin-pagination"><span>共 {{ total }} 个套餐</span><el-pagination data-test="plans-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="params.page + 1" :page-size="params.size" :page-sizes="pageSizeOptions" :total="total" @size-change="changePageSize" @current-change="(value: number) => loadCatalog(value - 1)" /></div>
    </section>
      </div>

      <aside class="plan-main-layout__side">
        <div class="admin-panel plan-side-panel">
          <div class="plan-side-panel__header">
            <div>
              <h3>发放量排行</h3>
              <p>已上线版本累计销量</p>
            </div>
            <BarChart3 :size="18" aria-hidden="true" />
          </div>
          <ul class="plan-rank-list">
            <li v-for="(plan, idx) in topPublishedPlans" :key="plan.versionId">
              <span class="plan-rank-list__rank" :class="{ 'plan-rank-list__rank--top': idx < 3 }">{{ idx + 1 }}</span>
              <div class="plan-rank-list__info">
                <strong>{{ plan.name }}</strong>
                <small>{{ plan.code }}</small>
              </div>
              <div class="plan-rank-list__sales">
                <strong>{{ plan.stockSold ?? 0 }}</strong>
                <small>份</small>
              </div>
            </li>
            <li v-if="!topPublishedPlans.length" class="plan-rank-list__empty">暂无已上线套餐</li>
          </ul>
        </div>

        <div class="admin-panel plan-side-panel">
          <div class="plan-side-panel__header">
            <div>
              <h3>版本结构概览</h3>
              <p>草稿 / 生效 / 退役</p>
            </div>
          </div>
          <div class="plan-version-breakdown">
            <div class="plan-version-breakdown__bar" aria-hidden="true">
              <span class="plan-version-breakdown__seg plan-version-breakdown__seg--draft" :style="{ width: `${allVersions.length ? (allDraftVersions.length / allVersions.length * 100) : 0}%` }"></span>
              <span class="plan-version-breakdown__seg plan-version-breakdown__seg--published" :style="{ width: `${allVersions.length ? (publishedVersions.length / allVersions.length * 100) : 0}%` }"></span>
              <span class="plan-version-breakdown__seg plan-version-breakdown__seg--retired" :style="{ width: `${allVersions.length ? (allRetiredVersions.length / allVersions.length * 100) : 0}%` }"></span>
            </div>
            <div class="plan-version-breakdown__legend">
              <span><i class="plan-version-breakdown__dot plan-version-breakdown__dot--published"></i>生效 {{ publishedVersions.length }}</span>
              <span><i class="plan-version-breakdown__dot plan-version-breakdown__dot--draft"></i>草稿 {{ allDraftVersions.length }}</span>
              <span><i class="plan-version-breakdown__dot plan-version-breakdown__dot--retired"></i>退役 {{ allRetiredVersions.length }}</span>
            </div>
          </div>
        </div>
      </aside>
    </div>

    <el-dialog v-model="planCreateVisible" title="新建套餐" width="min(520px, calc(100vw - 32px))" :teleported="false" destroy-on-close>
      <el-form label-position="top" @submit.prevent="submitPlanCreate">
        <el-form-item label="套餐名称"><el-input v-model="planForm.name" data-test="new-plan-name" maxlength="100" placeholder="例如：开发者基础版" /></el-form-item>
        <el-form-item label="套餐类型"><el-select v-model="planForm.kind" data-test="new-plan-kind"><el-option label="标准套餐" value="STANDARD" /><el-option label="免费领取套餐" value="FREE_CLAIM" /></el-select><p class="field-help">免费领取套餐固定为 0 元且每位用户只能领取一次，同时只能上线一个。</p></el-form-item>
        <el-form-item label="套餐描述"><el-input v-model="planForm.description" type="textarea" :rows="3" maxlength="500" resize="none" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="planCreateVisible = false">取消</el-button><el-button data-test="plan-create" type="primary" :loading="creatingPlan" @click="submitPlanCreate">创建并配置版本</el-button></template>
    </el-dialog>

    <el-dialog v-model="versionCreateVisible" :title="`新建版本 · ${selectedPlan?.name || ''}`" width="min(900px, calc(100vw - 32px))" top="5vh" :teleported="false" destroy-on-close>
      <div data-test="plan-form" class="admin-form-grid plan-form plan-form--compact">
        <el-form-item class="plan-code-field" label="所属套餐"><el-input :model-value="selectedPlan?.name || ''" disabled /><small class="field-help">{{ versionForm.planCode }}</small></el-form-item>
        <el-form-item label="售价（分）"><el-input-number v-model="versionForm.priceAmount" data-test="plan-price" :min="0" :disabled="selectedPlan?.kind === 'FREE_CLAIM'" /></el-form-item>
        <el-form-item :label="totalQuotaLabel"><el-input-number v-model="versionForm.totalTokenQuota" data-test="plan-total-quota" :min="1" /></el-form-item>
        <!-- 方案 C：只有"含音频别名 + 主维度视频秒"的混合版本才有第二份额度 -->
        <el-form-item v-if="requiresAudioSecondsQuota" label="音频秒额度" data-test="plan-audio-seconds">
          <el-input-number v-model="versionForm.audioSecondsQuota" data-test="plan-audio-seconds-input" :min="1" />
          <small class="field-help" data-test="plan-audio-seconds-help">本版本同时含视频模型与音频别名：视频秒在“总秒数额度”，音频秒（ASR/TTS/唇形同步）走这一份，两者互不挤占。</small>
        </el-form-item>
        <el-form-item label="有效期（天）"><el-input-number v-model="versionForm.durationDays" :min="1" /></el-form-item>
        <el-form-item label="API 密钥上限"><el-input-number v-model="versionForm.maxApiKeys" :min="1" /></el-form-item>
        <el-form-item label="请求 / 分钟"><el-input-number v-model="versionForm.requestsPerMinute" :min="1" /></el-form-item>
        <el-form-item :label="rateLabel"><el-input-number v-model="versionForm.tokensPerMinute" :min="1" /></el-form-item>
        <el-form-item label="最大并发"><el-input-number v-model="versionForm.maxConcurrency" :min="1" /></el-form-item>
        <el-form-item label="库存总量（留空不限）"><el-input-number v-model="versionForm.stockTotal" :min="0" :controls="false" /></el-form-item>
        <el-form-item label="低库存阈值"><el-input-number v-model="versionForm.lowStockThreshold" :min="0" /></el-form-item>
        <el-form-item label="单用户购买上限（留空不限）"><el-input-number v-model="versionForm.maxPurchasesPerUser" :min="1" :controls="false" :disabled="selectedPlan?.kind === 'FREE_CLAIM'" /></el-form-item>
        <el-form-item class="is-full plan-model-selector" label="允许模型"><el-select v-model="versionForm.modelCodes" data-test="plan-models" multiple filterable collapse-tags collapse-tags-tooltip :max-collapse-tags="2"><el-option v-for="model in models" :key="model.code" :label="`${model.displayName} · ${model.code}`" :value="model.code" /></el-select></el-form-item>
        <div v-if="versionForm.modelCodes.length" class="is-full plan-model-quotas">
          <el-form-item v-for="code in versionForm.modelCodes" :key="`quota-${code}`" :label="`${modelOptionLabel(code)} · ${modelQuotaLabel}`"><el-input-number :model-value="versionForm.modelQuotas[code] ?? null" data-test="plan-model-quota" :aria-label="`${modelOptionLabel(code)} ${modelQuotaLabel}`" :min="0" :controls="false" placeholder="留空不限" @update:model-value="(value: number | null | undefined) => setModelQuota(code, value)" /></el-form-item>
          <p class="field-help plan-model-quotas__help" data-test="plan-model-quota-help">留空表示该模型不设单独额度，可消耗整个套餐额度。</p>
          <p v-if="mixedUsageDimensions" class="field-help plan-model-quotas__warning" data-test="plan-mixed-dimension-warning">同一版本不能同时选择视频模型（或音频模型）与其他按积分计费的模型，请拆分为两个版本后再创建。</p>
        </div>
        <div class="is-full admin-actions plan-form-actions"><el-checkbox v-model="versionForm.oncePerUser" :disabled="selectedPlan?.kind === 'FREE_CLAIM'">每位用户仅可购买一次</el-checkbox><div><el-button @click="versionCreateVisible = false">取消</el-button><el-button data-test="plan-version-create" type="primary" :loading="creatingVersion" @click="submitVersionDraft">创建草稿</el-button></div></div>
      </div>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑套餐信息" width="min(520px, calc(100vw - 32px))" :teleported="false" destroy-on-close>
      <el-form label-position="top" @submit.prevent="submitPlanEdit">
        <el-form-item label="套餐代码"><el-input :model-value="editForm.code" disabled /></el-form-item>
        <el-form-item label="套餐名称"><el-input v-model="editForm.name" maxlength="100" show-word-limit /></el-form-item>
        <el-form-item label="套餐描述"><el-input v-model="editForm.description" type="textarea" :rows="3" maxlength="500" show-word-limit resize="none" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="editVisible = false">取消</el-button><el-button type="primary" :loading="editing" @click="submitPlanEdit">保存修改</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.plan-header-actions { display: flex; align-items: center; justify-content: flex-end; gap: 10px; }
.plan-catalog-section { display: grid; gap: 14px; }
.plan-section-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.plan-section-header h3 { margin: 0; color: var(--app-text); font-size: 18px; }
.plan-section-header p { margin: 4px 0 0; color: var(--app-muted); font-size: 13px; }
.plans-toolbar { grid-template-columns: minmax(220px, 1fr) 180px auto; margin-bottom: 0; padding: 0; }
.plan-list { display: grid; gap: 14px; }
/* 在售套餐分区标题 */
.plan-group-header {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 12px 16px;
  border-radius: 8px;
}
.plan-group-header--active {
  background: var(--el-color-success-light-9, var(--app-success-soft));
  border: 1px solid var(--el-color-success-light-7, #E1F3D8);
  border-left: 4px solid var(--app-success);
}
.plan-group-header__label { display: flex; align-items: center; gap: 7px; color: var(--app-success); font-size: 15px; font-weight: 800; }
.plan-group-header__count { padding: 2px 10px; border-radius: 11px; background: var(--app-success); color: #fff; font-size: 12px; font-weight: 700; }
.plan-group-header__hint { color: var(--app-muted-strong); font-size: 12px; }
/* 已下架套餐独立折叠面板 */
.plan-archived-panel {
  margin-top: 26px;
  border: 1px solid var(--el-color-danger-light-7, #FDE2E2);
  border-radius: var(--app-radius);
  background: var(--app-surface);
  box-shadow: var(--app-shadow-xs);
  overflow: hidden;
}
.plan-archived-panel__header {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 13px 16px;
  cursor: pointer;
  user-select: none;
  background: var(--el-color-danger-light-9, var(--app-danger-soft));
  border-left: 4px solid var(--app-danger);
  list-style: none;
}
.plan-archived-panel__header::-webkit-details-marker { display: none; }
.plan-archived-panel__label { display: flex; align-items: center; gap: 7px; color: var(--app-danger); font-size: 15px; font-weight: 800; }
.plan-archived-panel__count { padding: 2px 10px; border-radius: 11px; background: var(--app-danger); color: #fff; font-size: 12px; font-weight: 700; }
.plan-archived-panel__hint { color: var(--app-muted-strong); font-size: 12px; margin-left: auto; }
.plan-archived-panel__chevron { color: var(--app-muted-strong); transition: transform .2s; }
.plan-archived-panel[open] .plan-archived-panel__chevron { transform: rotate(180deg); }
.plan-archived-panel__list { padding: 14px; background: var(--app-surface-muted); display: grid; gap: 14px; }
.plan-item { min-width: 0; overflow: hidden; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: var(--app-radius); box-shadow: var(--app-shadow-sm); }
.plan-item--archived { border-color: var(--app-border-strong); background: linear-gradient(180deg, var(--app-surface-muted) 0%, var(--app-surface) 120px); }
.plan-item--archived .plan-item__title h3 { color: var(--app-muted-strong); }
.plan-item--archived .plan-versions { opacity: .6; }
.plan-item__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 16px 20px; border-bottom: 1px solid var(--app-border); }
.plan-item__identity { min-width: 0; }
.plan-item__title { display: flex; min-width: 0; align-items: center; flex-wrap: wrap; gap: 8px; }
.plan-item__title h3 { margin: 0; color: var(--app-text); font-size: 17px; }
.plan-item__identity code { display: block; margin-top: 5px; color: var(--app-muted); font-size: 11px; }
.plan-item__identity p { max-width: 680px; margin: 5px 0 0; color: var(--app-muted); font-size: 12px; line-height: 1.5; }
.plan-item__actions { display: flex; flex: 0 0 auto; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
.plan-item__actions .el-button { margin-left: 0; }
.plan-versions { display: grid; }
.plan-version-row { padding: 16px 20px; }
.plan-version-row + .plan-version-row { border-top: 1px solid var(--app-border); }
.plan-version-row--draft { background: var(--el-color-primary-light-9); }
.plan-version-row--published {
  border-left: 3px solid var(--app-primary);
  background: linear-gradient(90deg, var(--app-primary-soft, var(--app-primary-soft)) 0%, transparent 100%);
}
.plan-version-row__head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.plan-version-row__head > div { display: flex; align-items: center; gap: 8px; }
.plan-version-row__head strong { color: var(--app-text); font-size: 15px; }
.current-version-label { color: var(--app-success); font-size: 12px; font-weight: 700; }
.plan-spec-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(118px, 1fr)); gap: 12px; }
.plan-spec { min-width: 0; padding: 10px 12px; background: var(--el-fill-color-light); border-radius: 7px; }
.plan-spec > span { display: block; margin-bottom: 5px; color: var(--app-muted); font-size: 11px; font-weight: 700; }
.plan-spec > strong { display: block; color: var(--app-text); font-size: 14px; font-weight: 800; line-height: 1.3; overflow-wrap: anywhere; }
.plan-spec__inline { display: flex; min-width: 0; align-items: center; gap: 6px; }
.plan-spec__inline > strong { overflow: hidden; color: var(--app-text); font-size: 14px; font-weight: 800; line-height: 1.3; text-overflow: ellipsis; white-space: nowrap; }
.plan-spec-price { color: var(--app-primary) !important; font-size: 15px !important; }
.plan-spec-danger { color: var(--app-danger) !important; }
.plan-spec-warning { color: var(--app-warning) !important; }
.plan-margin-warning { margin: 10px 0 0; color: var(--app-warning-text); font-size: 12px; line-height: 1.5; }
.plan-version-row__footer { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-top: 12px; }
.plan-version-row__footer small { flex: 0 0 auto; color: var(--app-muted); }
.plan-models { display: flex; min-width: 0; align-items: center; flex-wrap: wrap; gap: 6px; color: var(--app-muted); font-size: 12px; }
.plan-models > span:first-child { font-weight: 700; }
.plan-no-version { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 20px; color: var(--app-muted); }
.plan-history { border-top: 1px solid var(--app-border); }
.plan-history summary { display: flex; min-height: 44px; align-items: center; justify-content: space-between; gap: 12px; padding: 0 20px; color: var(--app-muted); cursor: pointer; font-size: 13px; font-weight: 700; list-style: none; }
.plan-history summary::-webkit-details-marker { display: none; }
.plan-history summary > span { display: flex; align-items: center; gap: 7px; }
.plan-history summary > svg { transition: transform 180ms ease; }
.plan-history[open] summary > svg { transform: rotate(180deg); }
.plan-history__list { padding: 0 20px 14px; }
.plan-history-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 10px 12px; background: var(--el-fill-color-lighter); border-top: 1px solid var(--app-border); }
.plan-history-row > div { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; color: var(--app-muted); font-size: 12px; }
.plan-history-row strong { color: var(--app-text); }
.plan-history-row small { color: var(--app-muted); text-align: right; }
.plan-form { padding: 2px 0; }
.plan-form--compact { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.plan-form--compact .plan-code-field { grid-column: span 2; }
.plan-form :deep(.el-form-item) { display: block; margin-bottom: 2px; }
.plan-form :deep(.el-form-item__label) { display: block; height: auto; margin-bottom: 5px; color: var(--app-text); font-weight: 600; }
.plan-form :deep(.el-input-number), .plan-form .el-select { width: 100%; }
.plan-model-selector { max-width: 100%; }
/* 已选模型的分模型额度：每个模型一个可选留空的输入框 */
.plan-model-quotas { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 10px 12px; }
.plan-model-quotas__help, .plan-model-quotas__warning { grid-column: 1 / -1; }
.plan-model-quotas__warning { color: var(--app-danger); }
.plan-form-actions { justify-content: space-between; padding-top: 12px; border-top: 1px solid var(--app-border); }
.field-help { display: block; margin: 5px 0 0; color: var(--app-muted); font-size: 12px; line-height: 1.5; }

/* 统计栅格 */
.plan-stats-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.plan-stat-card {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  grid-template-rows: auto auto auto;
  padding: 14px 16px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 8px;
  transition: border-color 150ms ease;
}
.plan-stat-card:hover { border-color: var(--app-border-strong); }
.plan-stat-card__icon {
  display: inline-grid;
  place-items: center;
  width: 30px; height: 30px;
  border-radius: 6px;
  grid-row: 1 / span 3;
}
.plan-stat-card__icon--total  { color: var(--app-primary);  background: var(--app-primary-soft, var(--app-primary-soft)); }
.plan-stat-card__icon--active { color: var(--app-success);  background: #e6f4ea; }
.plan-stat-card__icon--archived { color: var(--app-muted); background: var(--app-border); }
.plan-stat-card__icon--sales  { color: var(--app-primary);  background: var(--app-primary-soft, var(--app-primary-soft)); }
.plan-stat-card__label { color: var(--app-muted); font-size: 12px; }
.plan-stat-card__value { margin-top: 2px; font-size: 24px; font-weight: 700; font-variant-numeric: tabular-nums; color: var(--app-text); }
.plan-stat-card__hint { margin-top: 2px; color: var(--app-muted); font-size: 11px; }

/* 布局 */
.plan-main-layout {
  display: grid;
  gap: 16px;
  grid-template-columns: minmax(0, 3fr) minmax(260px, 1fr);
  align-items: start;
}
.plan-main-layout__main { min-width: 0; }
.plan-main-layout__side {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 侧边面板 */
.admin-panel {
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 8px;
  overflow: hidden;
}
.plan-side-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--app-border);
}
.plan-side-panel__header h3 { margin: 0; font-size: 14px; font-weight: 700; color: var(--app-text); }
.plan-side-panel__header p { margin: 2px 0 0; font-size: 12px; color: var(--app-muted); }
.plan-side-panel__header svg { color: var(--app-muted); }

/* 排行列表 */
.plan-rank-list { list-style: none; margin: 0; padding: 6px 14px 12px; }
.plan-rank-list li {
  display: grid;
  align-items: center;
  gap: 10px;
  grid-template-columns: 22px minmax(0, 1fr) auto;
  padding: 10px 0;
  border-top: 1px solid var(--app-border);
}
.plan-rank-list li:first-child { border-top: 0; }
.plan-rank-list__rank {
  display: inline-grid;
  place-items: center;
  width: 22px; height: 22px;
  border-radius: 5px;
  font-size: 11px;
  font-weight: 700;
  color: var(--app-muted);
  background: var(--el-fill-color-light);
}
.plan-rank-list__rank--top { color: var(--app-primary); background: var(--app-primary-soft, var(--app-primary-soft)); }
.plan-rank-list__info strong { display: block; font-size: 13px; font-weight: 600; color: var(--app-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.plan-rank-list__info small { font-size: 11px; color: var(--app-muted); }
.plan-rank-list__sales { text-align: right; }
.plan-rank-list__sales strong { font-size: 15px; font-weight: 700; color: var(--app-text); font-variant-numeric: tabular-nums; }
.plan-rank-list__sales small { font-size: 10px; color: var(--app-muted); margin-left: 2px; }
.plan-rank-list__empty { padding: 20px 0; text-align: center; color: var(--app-muted); font-size: 13px; border-top: 0; }

/* 版本分布 */
.plan-version-breakdown { padding: 14px; }
.plan-version-breakdown__bar {
  display: flex;
  overflow: hidden;
  height: 6px;
  margin-bottom: 10px;
  border-radius: 3px;
  background: var(--app-border);
}
.plan-version-breakdown__seg { height: 100%; transition: width 300ms ease; }
.plan-version-breakdown__seg--published { background: var(--app-success); }
.plan-version-breakdown__seg--draft { background: var(--el-color-primary); }
.plan-version-breakdown__seg--retired { background: var(--app-muted); }
.plan-version-breakdown__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  color: var(--app-muted);
  font-size: 12px;
}
.plan-version-breakdown__legend i {
  display: inline-block;
  width: 8px; height: 8px;
  margin-right: 5px;
  border-radius: 50%;
  vertical-align: middle;
  font-style: normal;
}
.plan-version-breakdown__dot--published { background: var(--app-success); }
.plan-version-breakdown__dot--draft { background: var(--el-color-primary); }
.plan-version-breakdown__dot--retired { background: var(--app-muted); }

@media (max-width: 1100px) {
  .plan-stats-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .plan-main-layout { grid-template-columns: minmax(0, 1fr); }
  .plan-spec-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .plan-form--compact { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 760px) {
  .plans-toolbar { grid-template-columns: minmax(0, 1fr); }
  .plan-item__header, .plan-version-row__footer, .plan-history-row { align-items: stretch; flex-direction: column; }
  .plan-item__actions { justify-content: flex-start; }
  .plan-history-row small { text-align: left; }
}
@media (max-width: 600px) {
  .plan-header-actions { width: 100%; align-items: stretch; flex-direction: column-reverse; }
  .plan-header-actions .el-button { width: 100%; margin-left: 0; }
  .plan-item__header, .plan-version-row { padding: 14px; }
  .plan-stats-grid { grid-template-columns: minmax(0, 1fr); }
  .plan-spec-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .plan-form--compact { grid-template-columns: minmax(0, 1fr); }
  .plan-form--compact .plan-code-field { grid-column: auto; }
  .plan-form-actions { align-items: stretch; flex-direction: column; }
  .plan-form-actions > div { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
}
</style>
