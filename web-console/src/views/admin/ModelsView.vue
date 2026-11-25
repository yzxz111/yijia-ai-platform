<script setup lang="ts">
import { isAxiosError } from 'axios'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Boxes, Boxes as BoxesIcon, CircleAlert, Cpu, KeyRound, ListPlus, Pencil, Plus, RefreshCw, Search, ServerCog, TestTube2, Trash2, TrendingUp, Waypoints } from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'

import {
  createProvider,
  deleteModel,
  deleteProvider,
  listChannels,
  listModels,
  listProviderTypeCapabilities,
  listProviders,
  listVideoAliasBilling,
  onboardChannelRoutes,
  probeUpstreamModels,
  registerModelDimension,
  saveModelConfiguration,
  saveVideoAliasBilling,
  setModelEnabled,
  testChannel,
  testChannelModel,
  type AdminChannel,
  type AdminChannelModelTest,
  type AdminListParams,
  type AdminModel,
  type AdminModelRoute,
  type AdminProvider,
  type AdminProviderTypeCapabilities,
  type AdminRouteOnboardingInput,
  type AdminRouteOnboardingResult,
  type AdminRoutesOnboarding,
  type VideoAliasBilling,
} from '../../api/admin'

const route = useRoute()
const requestedStatus = typeof route.query.status === 'string' ? route.query.status : ''
const initialStatus = ['ENABLED', 'DISABLED'].includes(requestedStatus)
  ? requestedStatus
  : 'ENABLED'
const models = ref<AdminModel[]>([])
const providers = ref<AdminProvider[]>([])
const routeOptions = ref<AdminModelRoute[]>([])
const selectedRouteKey = ref('')
const total = ref(0)
const loading = ref(true)
const saving = ref(false)
const dialogOpen = ref(false)
const providerDialogOpen = ref(false)
const providerSaving = ref(false)
/** 正在删除的服务商 id（只让那一行的按钮转圈）。 */
const providerDeletingId = ref('')
const mutatingId = ref('')
const deletingId = ref('')
const testingId = ref('')
const preferredProviderId = ref('')
const errorMessage = ref('')
const pageSizeOptions = [5, 10, 20]
const params = reactive<AdminListParams>({
  page: 0,
  size: 20,
  query: typeof route.query.query === 'string' ? route.query.query : '',
  status: initialStatus,
  sort: 'code',
  direction: 'asc',
})
const form = reactive({
  id: '',
  code: '',
  displayName: '',
  aliasText: '',
  capabilities: ['TEXT'] as string[],
  contextWindow: 32768,
  maxOutputTokens: 4096,
  premium: false,
  requestsPerMinute: 60,
  maxConcurrency: 5,
  channelId: '',
  componentModelCode: '',
  providerId: '',
  environment: 'PRODUCTION',
  baseUrl: '',
  apiKey: '',
  upstreamModel: '',
  routeEnabled: true,
  priority: 100,
  weight: 100,
  timeoutMs: 60_000,
  routeRequestsPerMinute: 300,
  routeMaxConcurrency: 50,
  credentialConfigured: false,
  // 视频计费：只在该模型勾选了「视频」能力时出现。分辨率档位决定网关允许的调用档位，
  // 每秒成本是定价守卫算毛利的依据，每秒零售价必须过 30% 毛利地板（保存时校验）。
  videoResolutionTier: '720P',
  videoProviderUnitCost: 0,
  videoUnitPrice: 0,
  videoRetryRatePercent: 15,
})
const providerForm = reactive({
  code: '',
  name: '',
  providerType: 'DEEPSEEK',
  baseUrl: '',
  enabled: true,
})

/**
 * 服务商类型的能力目录（挂载时取一次）。它的价值在于把「这把 key 挂错了类型」
 * 从「等第一次调用 503」提前到保存之前：`TIANYIYUN` 这类类型 `chatCapabilities`
 * 是空的，挂在上面的文本/读图模型每次请求都会被拒。
 */
const providerTypeCapabilities = ref<AdminProviderTypeCapabilities[]>([])
/** 最近一次「检测密钥」拿到的上游模型清单；既填「上游真实模型」下拉，也给批量接入复用。 */
const probedModels = ref<string[]>([])
const probingKey = ref(false)

/** 可增删行需要稳定的 key，提交时再剥掉它，载荷里只有契约里的三个字段。 */
type OnboardingRow = AdminRouteOnboardingInput & { key: string }

/** 批量接入对话框的状态。 */
const onboardingOpen = ref(false)
const onboardingLoading = ref(false)
const onboardingSaving = ref(false)
const onboardingChannels = ref<AdminChannel[]>([])
const onboardingChannelId = ref('')
const onboardingRows = ref<OnboardingRow[]>([])
const onboardingResult = ref<AdminRoutesOnboarding | null>(null)

let onboardingRowSeed = 0
function newOnboardingRow(): OnboardingRow {
  onboardingRowSeed += 1
  return { key: `onboard-row-${onboardingRowSeed}`, modelCode: '', upstreamModel: '', enabled: true }
}

/** 已登记的视频别名计费（网关侧），用于编辑时带出当前值。 */
const videoBillingRows = ref<VideoAliasBilling[]>([])
const isVideoModel = computed(() => form.capabilities.includes('VIDEO'))
/**
 * 30% 毛利地板下的最低每秒零售价（分/秒），与网关校验用的是同一个公式
 * （ceil(成本 / 0.7)），所以页面提示和保存时的拒绝口径一致。
 */
const videoPriceFloor = computed(() =>
  form.videoProviderUnitCost > 0 ? Math.ceil(form.videoProviderUnitCost / 0.7) : 0)

async function loadVideoBilling() {
  try {
    videoBillingRows.value = await listVideoAliasBilling()
  } catch {
    // 读不到就用默认值，保存时的校验仍在，不阻塞配置流程。
    videoBillingRows.value = []
  }
}

/**
 * 选中的服务商所对应的类型能力；目录没加载到（或服务商还没选）时为 null。
 * `chatCapabilities` 兜底成空数组，因为目录是提示而不是校验依据。
 */
const selectedProviderCapabilities = computed<AdminProviderTypeCapabilities | null>(() => {
  const providerType = providers.value.find((provider) => provider.id === form.providerId)?.providerType
  if (!providerType) return null
  return providerTypeCapabilities.value.find((entry) => entry.providerType === providerType) ?? null
})

const selectedProviderChatCapabilities = computed(() => selectedProviderCapabilities.value?.chatCapabilities ?? [])

/** 能力目录的一行文案：`对话能力：TEXT/IMAGE/FILE · 生图：是 · 视频：否`。 */
function capabilityHintText(capabilities: AdminProviderTypeCapabilities) {
  const chat = capabilities.chatCapabilities?.length ? capabilities.chatCapabilities.join('/') : '无'
  return `对话能力：${chat} · 生图：${capabilities.imageGeneration ? '是' : '否'}`
    + ` · 视频：${capabilities.videoGeneration ? '是' : '否'}`
}

const providerCapabilityHint = computed(() => {
  const capabilities = selectedProviderCapabilities.value
  return capabilities ? capabilityHintText(capabilities) : ''
})

/**
 * 该类型没有对话适配器，而当前模型却勾了需要对话能力的能力 —— 保存时后端会以
 * `provider_capability_unavailable` 拒绝这条路由，所以在这里先警告。
 */
const providerCapabilityConflict = computed(() => {
  const capabilities = selectedProviderCapabilities.value
  if (!capabilities) return false
  if (selectedProviderChatCapabilities.value.length) return false
  return form.capabilities.some((capability) => ['TEXT', 'IMAGE', 'FILE'].includes(capability))
})

/** 新增服务商时的类型提示（服务商还没保存，没有 providerId 可查，直接看表单里的协议）。 */
const providerFormCapabilityHint = computed(() => {
  const capabilities = providerTypeCapabilities.value.find(
    (entry) => entry.providerType === providerForm.providerType,
  )
  return capabilities ? capabilityHintText(capabilities) : ''
})

async function loadProviderTypeCapabilities() {
  try {
    providerTypeCapabilities.value = await listProviderTypeCapabilities()
  } catch {
    // 能力目录只用来提前提示；读不到就不提示，不影响保存链路。
    providerTypeCapabilities.value = []
  }
}

/**
 * 「检测密钥」：拿表单里当前填的 baseUrl + apiKey 真向上游列一次模型。
 *
 * 它回答的是保存之前的问题——「这把还没入库的 key 能用吗、上游有哪些模型」，
 * 所以必须带 `Authorization` 头（401/403 如实报出），且密钥只用于这一次请求、不落库。
 * 保存之后的「测试模型」（testModelChannel）才回答「这条配置现在能不能真的跑通」：
 * 它走线上同一条链路发一次真实对话请求。
 */
async function detectKey() {
  if (!form.baseUrl.trim() || !form.apiKey.trim()) {
    ElMessage.warning('请先填写接口地址和 API 密钥，再检测密钥。')
    return
  }
  probingKey.value = true
  try {
    const result = await probeUpstreamModels({
      baseUrl: form.baseUrl.trim(),
      apiKey: form.apiKey.trim(),
    })
    if (result.keyValid) {
      probedModels.value = [...result.models]
      ElMessage.success(`密钥可用，上游返回 ${result.models.length} 个模型。`)
      return
    }
    probedModels.value = []
    if (result.httpStatus === 401 || result.httpStatus === 403) {
      ElMessage.error(`密钥检测失败（HTTP ${result.httpStatus}）：密钥无效或没有该地址的访问权限。`)
    } else if (result.httpStatus === 0) {
      ElMessage.error('密钥检测失败（HTTP 0）：无法连接上游，请检查接口地址和网络。')
    } else {
      ElMessage.error(`密钥检测失败（HTTP ${result.httpStatus}）：上游拒绝了这次请求。`)
    }
  } catch {
    probedModels.value = []
    ElMessage.error('密钥检测请求失败，请稍后重试。')
  } finally {
    probingKey.value = false
  }
}

/** 批量接入里 `REJECTED` 的稳定原因到中文的映射。 */
const routeRejectionReasons: Record<string, string> = {
  provider_capability_unavailable: '该服务商类型没有能服务这个模型的能力',
  model_not_found: '平台别名不存在',
}

function routeRejectionLabel(reason: string | null) {
  if (!reason) return '未说明原因'
  return routeRejectionReasons[reason] ?? reason
}

function routeStatusLabel(status: AdminRouteOnboardingResult['status']) {
  return ({ CREATED: '已创建', UPDATED: '已更新', REJECTED: '已拒绝' } as Record<string, string>)[status] || status
}

function routeStatusType(status: AdminRouteOnboardingResult['status']) {
  return ({ CREATED: 'success', UPDATED: 'success', REJECTED: 'danger' } as const)[status] ?? 'info'
}

async function openOnboarding() {
  onboardingResult.value = null
  onboardingChannelId.value = ''
  onboardingRows.value = [newOnboardingRow()]
  onboardingOpen.value = true
  onboardingLoading.value = true
  try {
    // 分页接口，取一页就够（渠道数量远小于一页上限）。
    const result = await listChannels({ page: 0, size: 100, query: '', status: '', sort: 'priority', direction: 'desc' })
    onboardingChannels.value = result.items
  } catch {
    onboardingChannels.value = []
    ElMessage.error('渠道列表加载失败，请稍后重试。')
  } finally {
    onboardingLoading.value = false
  }
}

function addOnboardingRow() {
  onboardingRows.value = [...onboardingRows.value, newOnboardingRow()]
}

function removeOnboardingRow(index: number) {
  onboardingRows.value = onboardingRows.value.filter((_, rowIndex) => rowIndex !== index)
}

async function submitOnboarding() {
  if (!onboardingChannelId.value) {
    ElMessage.warning('请先选择要接入的渠道。')
    return
  }
  const routes: AdminRouteOnboardingInput[] = onboardingRows.value
    .map((row) => ({
      modelCode: row.modelCode.trim(),
      upstreamModel: row.upstreamModel.trim(),
      enabled: row.enabled,
    }))
    .filter((row) => row.modelCode && row.upstreamModel)
  if (!routes.length) {
    ElMessage.warning('请至少填写一条「平台别名 + 上游模型」。')
    return
  }
  onboardingSaving.value = true
  onboardingResult.value = null
  try {
    const result = await onboardChannelRoutes(onboardingChannelId.value, routes)
    onboardingResult.value = result
    if (result.routes.some((route) => route.status !== 'REJECTED')) {
      await load(params.page)
    }
  } catch {
    ElMessage.error('批量接入失败，请检查渠道、别名与上游模型后重试。')
  } finally {
    onboardingSaving.value = false
  }
}

function applyVideoBilling(modelCode: string) {
  const row = videoBillingRows.value.find(candidate => candidate.modelCode === modelCode)
  form.videoResolutionTier = row?.resolutionTier || '720P'
  form.videoProviderUnitCost = row?.providerUnitCost ?? 0
  form.videoUnitPrice = row?.unitPrice ?? 0
  form.videoRetryRatePercent = row?.retryRate == null
    ? 15
    : Math.round(Number(row.retryRate) * 100)
}

/**
 * 视频别名还需要另外两行才卖得出去，这里一次写完：
 *
 * - 网关：分辨率档位（不写 → 调用被 422 `video_model_resolution_unregistered` 拒绝）
 *   与每秒成本/零售价费率卡行（不写 → 平台没有成本依据，套餐发布守卫会拒绝）；
 * - 平台：按秒计费登记（不写 → 平台把该别名当成积分别名，含它的套餐会被发布成积分套餐，
 *   直到客户购买发放时才被网关拒绝）。
 *
 * @returns 失败时返回给管理员看的原因；成功返回空串。
 */
async function persistVideoBilling(modelCode: string): Promise<string> {
  try {
    await saveVideoAliasBilling(modelCode, {
      resolutionTier: form.videoResolutionTier,
      providerUnitCost: form.videoProviderUnitCost,
      unitPrice: form.videoUnitPrice,
      retryRate: Number((form.videoRetryRatePercent / 100).toFixed(5)),
    })
  } catch {
    return '模型已保存，但视频计费没写进网关：请确认每秒零售价不低于 30% 毛利地板（当前成本下至少 '
      + `${videoPriceFloor.value} 分/秒），且该模型勾选了「视频」能力。`
  }
  try {
    await registerModelDimension(modelCode, {
      usageDimension: 'VIDEO_SECOND',
      resolutionTier: form.videoResolutionTier,
      effectiveUnitCost: form.videoProviderUnitCost,
      retailUnitPrice: form.videoUnitPrice,
    })
  } catch {
    return '视频计费已写进网关，但平台侧按秒登记失败：现在发布含该别名的套餐会被当成积分套餐，请再保存一次。'
  }
  return ''
}

async function load(targetPage = params.page) {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listModels({ ...params, page: targetPage })
    models.value = result.items
    total.value = result.total
    params.page = result.page
  } catch {
    errorMessage.value = '模型目录加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

async function loadProviders() {
  try {
    providers.value = await listProviders()
  } catch {
    errorMessage.value = '服务商列表加载失败，请稍后重试。'
  }
}

function changePageSize(size: number) {
  params.size = size
  void load(0)
}

function clearFilters() {
  Object.assign(params, { query: '', status: 'ENABLED', sort: 'code', direction: 'asc' })
  void load(0)
}

function capabilityLabel(value: string) {
  return ({ TEXT: '文本', IMAGE: '图像', FILE: '文件', VIDEO: '视频', AUDIO: '音频' } as Record<string, string>)[value] || value
}

function primaryRoutes(model: AdminModel) {
  const components = new Set<string>()
  return (model.routes || []).filter((modelRoute) => {
    if (components.has(modelRoute.componentModelCode)) return false
    components.add(modelRoute.componentModelCode)
    return true
  })
}

/**
 * 该模型**所有**路由涉及的服务商，按接口给的顺序（可用优先、优先级高的在前）去重。
 *
 * <p>为什么服务商列不再用 {@link primaryRoutes}：那个函数按组件去重，每个组件只留第一条
 * 路由。对「普通 / 深度」这种**单组件、多渠道路由**的模型，备用渠道因此完全看不见 ——
 * 列表里只剩主渠道那一家，「这个模型有没有备胎」这个问题在页面上无法回答。
 *
 * <p>而上游模型列**仍然**按组件去重：那一列回答的是「每个组件用哪个上游模型」，
 * 多列一条备用上游只会让复合模型的格子变成噪声（已有用例
 * `model-routes-multimodal-basic` 钉住了「不出现备用上游」）。两列语义不同，不该共用同一个过滤器。
 */
function routeProviders(model: AdminModel) {
  const seen = new Set<string>()
  const names: string[] = []
  for (const modelRoute of model.routes || []) {
    const name = modelRoute.providerName || modelRoute.providerCode
    if (!name || seen.has(name)) continue
    seen.add(name)
    names.push(name)
  }
  return names
}

function modelStatus(model: AdminModel) {
  if (!model.enabled) return { label: '已停用', type: 'info' as const }
  if (!model.routes?.length) return { label: '未配置', type: 'warning' as const }
  if (model.routes.some((modelRoute) => modelRoute.eligible)) return { label: '正常', type: 'success' as const }
  if (model.routes.some((modelRoute) => !modelRoute.credentialConfigured)) {
    return { label: '缺少密钥', type: 'danger' as const }
  }
  return { label: '异常', type: 'danger' as const }
}

function routeKey(modelRoute: AdminModelRoute) {
  return `${modelRoute.componentModelCode}:${modelRoute.channelId}`
}

// 模型状态统计（当前页数据派生）
const statusCounts = computed(() => {
  const counts = { enabled: 0, disabled: 0, warning: 0, danger: 0 }
  for (const model of models.value) {
    const status = modelStatus(model).type
    if (status === 'success') counts.enabled += 1
    else if (status === 'info') counts.disabled += 1
    else if (status === 'warning') counts.warning += 1
    else counts.danger += 1
  }
  return counts
})

// 今日调用 Top 5（按模型限流配置与路由健康度估算的参考排行，纯前端派生）
const topModels = computed(() =>
  models.value
    .filter((model) => model.enabled && model.routes?.length)
    .map((model) => ({
      code: model.code,
      displayName: model.displayName,
      requestsPerMinute: model.requestsPerMinute,
      maxConcurrency: model.maxConcurrency,
      healthyRoutes: (model.routes || []).filter((r) => r.eligible).length,
      totalRoutes: (model.routes || []).length,
    }))
    .sort((left, right) => right.requestsPerMinute - left.requestsPerMinute)
    .slice(0, 5),
)

function providerBaseUrl(providerId: string) {
  return providers.value.find((provider) => provider.id === providerId)?.baseUrl || ''
}

function preferredProvider() {
  return providers.value.find((provider) => provider.id === preferredProviderId.value)
    || providers.value.find((provider) => provider.enabled)
    || providers.value[0]
}

function resetRoute(provider = preferredProvider()) {
  Object.assign(form, {
    channelId: '', componentModelCode: '', providerId: provider?.id || '',
    environment: 'PRODUCTION', baseUrl: provider?.baseUrl || '', apiKey: '', upstreamModel: '',
    routeEnabled: true, priority: 100, weight: 100, timeoutMs: 60_000,
    routeRequestsPerMinute: 300, routeMaxConcurrency: 50, credentialConfigured: false,
  })
}

function openProviderCreate() {
  Object.assign(providerForm, {
    code: '', name: '', providerType: 'DEEPSEEK', baseUrl: '', enabled: true,
  })
  providerDialogOpen.value = true
}

/**
 * 删除服务商（清理废弃接入）。
 *
 * 两条边界由后端把关，这里只负责把原因原样告诉人：
 * - 名下还有渠道 → 先问一次是否连同渠道与别名映射一起删（cascade）；
 * - 已有调用/成本记录 → 后端 409，提示改为「停用」，因为历史不能变成孤儿。
 */
async function removeProvider(provider: AdminProvider) {
  try {
    await ElMessageBox.confirm(
      `确定删除服务商「${provider.name}」（${provider.code}）吗？该操作不可撤销。`
        + '若它名下还有渠道，会一并删除这些渠道及其别名映射。',
      '删除服务商',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  providerDeletingId.value = provider.id
  try {
    const result = await deleteProvider(provider.id, true)
    providers.value = providers.value.filter((item) => item.id !== provider.id)
    if (preferredProviderId.value === provider.id) {
      preferredProviderId.value = ''
    }
    ElMessage.success(
      `已删除服务商「${result.code}」：渠道 ${result.deletedChannels} 个、别名映射 ${result.deletedRoutes} 条、价格行 ${result.deletedPrices} 条`,
    )
  } catch (error) {
    ElMessage.error(providerDeleteFailureMessage(error))
  } finally {
    providerDeletingId.value = ''
  }
}

/** 后端把「为什么不能删」写在响应体的 code 里，这里翻译成可执行的动作。 */
function providerDeleteFailureMessage(error: unknown): string {
  const code = (error as { response?: { data?: { code?: string } } })?.response?.data?.code
  if (code === 'provider_has_usage_history') {
    return '该服务商已有调用或成本记录，为保留计费与审计数据不能删除，请改为「停用」。'
  }
  if (code === 'provider_has_channels') {
    return '该服务商名下还有渠道，请先删除这些渠道，或确认要连同渠道一起删除。'
  }
  if (code === 'provider_not_found') {
    return '服务商不存在或已被删除，请刷新后重试。'
  }
  return '删除失败，请稍后重试。'
}

async function saveProvider() {
  if (!providerForm.code.trim() || !providerForm.name.trim() || !providerForm.baseUrl.trim()) {
    ElMessage.error('请完整填写服务商名称、代码和默认地址。')
    return
  }
  providerSaving.value = true
  try {
    const created = await createProvider({
      code: providerForm.code.trim(),
      name: providerForm.name.trim(),
      providerType: providerForm.providerType,
      baseUrl: providerForm.baseUrl.trim(),
      enabled: providerForm.enabled,
    })
    providers.value = [...providers.value, created]
      .sort((left, right) => left.code.localeCompare(right.code))
    preferredProviderId.value = created.id
    providerDialogOpen.value = false
    ElMessage.success('服务商已创建')
  } catch {
    ElMessage.error('服务商创建失败，请检查代码是否重复以及地址是否有效。')
  } finally {
    providerSaving.value = false
  }
}

function applyRoute(modelRoute?: AdminModelRoute) {
  if (!modelRoute) {
    resetRoute()
    return
  }
  Object.assign(form, {
    channelId: modelRoute.channelId,
    componentModelCode: modelRoute.componentModelCode,
    providerId: modelRoute.providerId,
    environment: modelRoute.environment,
    baseUrl: modelRoute.baseUrl || providerBaseUrl(modelRoute.providerId),
    apiKey: '',
    upstreamModel: modelRoute.upstreamModel,
    routeEnabled: modelRoute.enabled,
    priority: modelRoute.priority,
    weight: modelRoute.weight,
    timeoutMs: modelRoute.timeoutMs || 60_000,
    routeRequestsPerMinute: modelRoute.requestsPerMinute || 300,
    routeMaxConcurrency: modelRoute.maxConcurrency || 50,
    credentialConfigured: modelRoute.credentialConfigured,
  })
}

function openCreate() {
  Object.assign(form, {
    id: '', code: '', displayName: '', aliasText: '', capabilities: ['TEXT'],
    contextWindow: 32768, maxOutputTokens: 4096, premium: false,
    requestsPerMinute: 60, maxConcurrency: 5,
    videoResolutionTier: '720P', videoProviderUnitCost: 0,
    videoUnitPrice: 0, videoRetryRatePercent: 15,
  })
  routeOptions.value = []
  selectedRouteKey.value = ''
  resetRoute()
  dialogOpen.value = true
}

async function openEdit(model: AdminModel) {
  Object.assign(form, {
    id: model.id,
    code: model.code,
    displayName: model.displayName,
    aliasText: model.aliases.filter((alias) => alias !== model.code).join(', '),
    capabilities: [...model.capabilities],
    contextWindow: model.contextWindow,
    maxOutputTokens: model.maxOutputTokens,
    premium: model.premium,
    requestsPerMinute: model.requestsPerMinute,
    maxConcurrency: model.maxConcurrency,
  })
  routeOptions.value = [...(model.routes || [])]
  const preferred = model.routes?.find((modelRoute) => modelRoute.eligible) || model.routes?.[0]
  selectedRouteKey.value = preferred ? routeKey(preferred) : ''
  applyRoute(preferred)
  // 开关的默认值必须体现「我要把它配置好」的意图，不能照搬当前状态。
  //
  // 曾经的写法是 `model.enabled && (preferred?.enabled ?? true)`：只要路由此刻
  // 不可用（典型情形是渠道或映射被关掉了），开关就显示为关闭；而保存时这个值会
  // 同时写回模型、渠道与映射三处，于是关掉的又被写回关闭——形成死循环：
  //   打开抽屉（开关=关）→ 填密钥 → 保存（写回关）→ 模型显示停用 → 再打开还是关。
  // 管理员再怎么填都修不好，因为在保存的那一刻，界面把这个「坏状态」当成了意图。
  //
  // 正确语义：模型在售（model.enabled）时，配置路由就默认启用——保存即意味着
  // 「把这个模型接通」。要单独停用路由，管理员显式关掉这个开关即可。
  form.routeEnabled = model.enabled
  if (model.capabilities.includes('VIDEO')) {
    await loadVideoBilling()
    applyVideoBilling(model.code)
  }
  dialogOpen.value = true
}

function selectRoute(value: string) {
  selectedRouteKey.value = value
  applyRoute(routeOptions.value.find((modelRoute) => routeKey(modelRoute) === value))
}

function selectProvider(providerId: string) {
  form.providerId = providerId
  if (!form.channelId || !form.baseUrl.trim()) form.baseUrl = providerBaseUrl(providerId)
}

function aliases() {
  const values = form.aliasText.split(',').map((value) => value.trim()).filter(Boolean)
  return Array.from(new Set([form.code.trim(), ...values]))
}

/**
 * 对刚保存的每个渠道跑一次免费的可达性探测。
 *
 * 保存渠道配置会把 last_health_status 重置为 UNKNOWN（网关有意为之：配置变了就不该
 * 继续宣称健康），而路由要求 HEALTHY 才可调度。过去这一步要运营自己记得去渠道页点
 * 「测试渠道连接」——忘了就会出现"密钥填好了、模型却依然不可用"，而界面上没有任何
 * 地方提示还差这一下。这里把那次点击自动化。
 *
 * 它只证明地址可达：一把错误的密钥同样会得到 HEALTHY。密钥到底能不能用，由
 * 「测试模型」那次真实调用回答（它会向上游发一次最小请求并计费，因此仍由人工触发）。
 */
async function probeSavedChannels(model: AdminModel) {
  const channelIds = Array.from(new Set(
    (model.routes ?? []).map((modelRoute) => modelRoute.channelId).filter(Boolean)))
  let healthy = 0
  for (const channelId of channelIds) {
    try {
      const result = await testChannel(channelId) as { status?: string }
      if (String(result?.status ?? '').toUpperCase() === 'HEALTHY') healthy += 1
    } catch {
      // 探测失败不改写保存结果：配置已经落库，渠道页仍可手动重试并看到原因。
    }
  }
  return { total: channelIds.length, healthy }
}

async function save() {
  const credentialReady = form.credentialConfigured || Boolean(form.apiKey.trim())
  if (!form.code.trim() || !form.displayName.trim() || !form.capabilities.length
      || form.contextWindow <= 0 || form.maxOutputTokens <= 0 || form.requestsPerMinute <= 0
      || form.maxConcurrency <= 0 || !form.providerId || !form.baseUrl.trim()
      || !form.upstreamModel.trim() || !credentialReady) {
    ElMessage.error('请完整填写模型、服务商、上游模型和密钥配置。')
    return
  }
  if (isVideoModel.value) {
    if (!form.videoResolutionTier || form.videoProviderUnitCost <= 0 || form.videoUnitPrice <= 0) {
      ElMessage.error('视频模型必须填写分辨率档位、每秒成本和每秒零售价（单位：分/秒）。')
      return
    }
    if (form.videoUnitPrice < videoPriceFloor.value) {
      ElMessage.error(`每秒零售价低于 30% 毛利地板：成本 ${form.videoProviderUnitCost} 分/秒时至少填 ${videoPriceFloor.value} 分/秒。`)
      return
    }
  }
  saving.value = true
  errorMessage.value = ''
  try {
    const saved = await saveModelConfiguration({
      id: form.id || undefined,
      code: form.code.trim(),
      displayName: form.displayName.trim(),
      aliases: aliases(),
      capabilities: form.capabilities,
      contextWindow: form.contextWindow,
      maxOutputTokens: form.maxOutputTokens,
      premium: form.premium,
      enabled: form.routeEnabled,
      requestsPerMinute: form.requestsPerMinute,
      maxConcurrency: form.maxConcurrency,
      route: {
        channelId: form.channelId || undefined,
        componentModelCode: form.componentModelCode || form.code.trim(),
        providerId: form.providerId,
        environment: form.environment,
        baseUrl: form.baseUrl.trim(),
        apiKey: form.apiKey.trim() || undefined,
        upstreamModel: form.upstreamModel.trim(),
        enabled: form.routeEnabled,
        priority: form.priority,
        weight: form.weight,
        timeoutMs: form.timeoutMs,
        requestsPerMinute: form.routeRequestsPerMinute,
        maxConcurrency: form.routeMaxConcurrency,
      },
    })
    dialogOpen.value = false
    form.apiKey = ''
    const probed = await probeSavedChannels(saved)
    ElMessage.success(form.id ? '模型配置已更新' : '模型配置已创建')
    if (probed.total > 0 && probed.healthy < probed.total) {
      ElMessage.warning(
        `渠道连通性探测未全部通过（${probed.healthy}/${probed.total}）：可到渠道页查看，`
        + '并用「测试模型」做一次真实调用确认密钥。')
    }
    await load(params.page)
    if (isVideoModel.value) {
      const failure = await persistVideoBilling(form.code.trim())
      if (failure) {
        errorMessage.value = failure
        ElMessage.error(failure)
      } else {
        ElMessage.success('视频计费已保存：档位与每秒成本/零售价已写入网关，平台侧也已按秒登记。')
      }
      await loadVideoBilling()
    }
  } catch {
    ElMessage.error('模型配置保存失败，请检查别名、地址、密钥和限流参数。')
  } finally {
    saving.value = false
  }
}

async function changeModelStatus(model: AdminModel, enabled: boolean) {
  mutatingId.value = model.id
  errorMessage.value = ''
  try {
    await setModelEnabled(model.id, enabled)
    ElMessage.success(enabled ? '模型已上架' : '模型已下架')
    await load(params.page)
  } catch {
    ElMessage.error(enabled ? '模型上架失败，请稍后重试。' : '模型下架失败，请稍后重试。')
  } finally {
    mutatingId.value = ''
  }
}

function deletionFailureMessage(error: unknown) {
  const code = isAxiosError<{ code?: string }>(error) ? error.response?.data?.code : undefined
  if (code === 'model_referenced_by_package') {
    return '该模型已被套餐版本引用，不能删除。请先调整相关套餐。'
  }
  if (code === 'model_referenced_by_routing') {
    return '该模型正被组合模型路由引用，不能删除。请先调整组合模型。'
  }
  if (code === 'model_has_usage_history') {
    return '该模型已有调用记录，为保留审计数据不能删除，可将其下架。'
  }
  if (code === 'package_reference_check_unavailable') {
    return '暂时无法核验套餐引用，已取消删除，请稍后重试。'
  }
  return '模型删除失败，请稍后重试。'
}

async function removeModel(model: AdminModel) {
  try {
    await ElMessageBox.confirm(
      `删除后无法恢复，确定删除模型“${model.displayName}”吗？`,
      '删除模型',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  deletingId.value = model.id
  errorMessage.value = ''
  try {
    await deleteModel(model.id)
    ElMessage.success('模型已删除')
    const targetPage = models.value.length === 1 && params.page > 0 ? params.page - 1 : params.page
    await load(targetPage)
  } catch (error) {
    errorMessage.value = deletionFailureMessage(error)
  } finally {
    deletingId.value = ''
  }
}

/**
 * 上游渠道「测试模型」：用渠道里已保存的地址与密钥，真的向上游发一次最小对话请求。
 *
 * 路由只有满足 `channel.enabled AND provider.enabled AND mapping.enabled
 * AND last_health_status = 'HEALTHY' AND 凭据非空` 才 eligible，而 `last_health_status`
 * 只有测试接口能写入。这里刻意走真实调用而不是可达性探测：探测对错误的密钥、拼错的
 * 上游模型名、缺版本段的地址一律报 HEALTHY，运营看到的「配好了」和实际「能跑」之间
 * 正好差这一次请求。测试结果同时写入渠道健康态，因此按钮返回后状态列会跟着变。
 */
async function testModelChannel(model: AdminModel) {
  const modelRoute = model.routes?.find((route) => route.eligible) || model.routes?.[0]
  const channelId = modelRoute?.channelId
  if (!channelId) {
    ElMessage.warning('该模型还没有绑定上游渠道，无法测试模型。')
    return
  }
  testingId.value = model.id
  try {
    // 指定本行映射到的那条上游模型：测的是这一行，而不是同渠道上的任意别名。
    const result = await testChannelModel(channelId, {
      upstreamModel: modelRoute?.upstreamModel || undefined,
    })
    if (result.ok) {
      ElMessage.success(
        `模型可用：${result.upstreamModel || modelRoute?.upstreamModel || ''}`
        + `${result.latencyMs == null ? '' : `，耗时 ${result.latencyMs} ms`}`
        + `${result.healthPersisted ? '' : '（健康状态未写回：配置已改动，请重测）'}`,
      )
    } else if (!result.modelInvoked) {
      ElMessage.warning(adminModelTestReason(result))
    } else {
      ElMessage.error(adminModelTestReason(result))
    }
    await load(params.page)
  } catch {
    ElMessage.error('模型测试请求失败，请检查网关连接后重试。')
  } finally {
    testingId.value = ''
  }
}

/** 网关侧的稳定原因码 → 可执行的处置。上游原文附在后面，避免把结论埋在细节里。 */
function adminModelTestReason(result: AdminChannelModelTest) {
  const upstream = result.upstreamModel ? `（${result.upstreamModel}）` : ''
  const message = ({
    credential_rejected: `密钥被上游拒绝${upstream}：请更换 API 密钥后重测。`,
    model_not_found_on_provider: `上游没有这个模型${upstream}：请在「上游真实模型」里改成上游存在的模型名。`,
    // 404 有两种完全不同的成因，网关按上游原文分流：这一条是「上游没提到模型」。
    upstream_endpoint_not_found: `上游对这个地址回了 404${upstream}：上游原文里没有提到模型，更可能是接口地址不对（例如少了一段 /v1 这样的版本段），而不是模型名不对。请核对「接口地址」，并对照上游原文确认。`,
    // 音频、视频、生图别名不能用「测试模型」验证：它打的是对话端点。
    alias_not_chat_capable: `这个别名不是对话模型${upstream}：「测试模型」向上游发的是 /chat/completions，而音频、视频、生图模型不在那个端点上——模型名和密钥都可能是对的。请改用该能力的接口验证；渠道健康态不会被这次测试改动。`,
    upstream_insufficient_balance: '上游账户余额不足：请先充值，模型本身配置没有问题。',
    upstream_overloaded: '上游限流或过载：渠道已标记为「性能下降」，稍后重测。',
    upstream_server_error: '上游返回 5xx：渠道已标记为「性能下降」，请查看上游状态后重测。',
    timeout: '上游在规定超时内没有响应：请检查地址或调大渠道的超时时间。',
    upstream_unreachable: '连不上该地址：请检查接口地址、网络与出网白名单。',
    empty_provider_response: '上游返回了空响应：地址或模型名可能不对。',
    invalid_upstream_response: '上游返回的不是对话应答：地址可能指向了别的接口（检查版本段）。',
    upstream_error_in_body: '上游用 200 回应了错误对象：详情见原文（常见于余额不足或模型未开通）。',
    channel_credential_required: '该渠道还没有保存 API 密钥，无法测试模型。',
    channel_has_no_model: '该渠道还没有挂任何别名映射，先接入模型再测试。',
    model_mapping_missing: '这个上游模型没有挂在当前渠道上：请核对「上游真实模型」或改用批量接入。',
    provider_not_chat_capable: '该服务商类型没有对话适配器，无法测试文本模型：请改选支持对话的服务商类型。',
  } as Record<string, string>)[result.reason ?? ''] ?? '模型测试未通过，请按上游原文排查。'
  return result.upstreamMessage ? `${message} 上游原文：${result.upstreamMessage}` : message
}

onMounted(() => Promise.all([load(), loadProviders(), loadProviderTypeCapabilities()]))
</script>

<template>
  <section class="admin-page" aria-labelledby="admin-models-title">
    <header class="admin-page__header model-page-header">
      <div>
        <h2 id="admin-models-title">模型管理</h2>
        <p>统一维护业务模型及其服务商和上游路由</p>
      </div>
      <div class="admin-actions">
        <el-button :loading="loading" @click="load()"><RefreshCw :size="16" />刷新</el-button>
        <el-button data-test="model-onboard-open" @click="openOnboarding"><ListPlus :size="16" />批量接入</el-button>
        <el-button data-test="provider-create" @click="openProviderCreate"><ServerCog :size="16" />新增服务商</el-button>
        <el-button data-test="model-create" type="primary" @click="openCreate"><Plus :size="16" />新增模型</el-button>
      </div>
    </header>

    <TopErrorMessage :message="errorMessage" />

    <div class="model-stats" data-test="model-stats">
      <div class="model-stat-card">
        <span class="model-stat-card__icon model-stat-card__icon--total"><Cpu :size="17" /></span>
        <div class="model-stat-card__body">
          <span class="model-stat-card__value">{{ total }}</span>
          <span class="model-stat-card__label">总模型数</span>
        </div>
      </div>
      <div class="model-stat-card">
        <span class="model-stat-card__icon model-stat-card__icon--enabled"><Boxes :size="17" /></span>
        <div class="model-stat-card__body">
          <span class="model-stat-card__value model-stat-card__value--enabled">{{ statusCounts.enabled }}</span>
          <span class="model-stat-card__label">启用模型</span>
        </div>
      </div>
      <div class="model-stat-card">
        <span class="model-stat-card__icon model-stat-card__icon--warning"><CircleAlert :size="17" /></span>
        <div class="model-stat-card__body">
          <span class="model-stat-card__value model-stat-card__value--warning">{{ statusCounts.warning }}</span>
          <span class="model-stat-card__label">待配置</span>
        </div>
      </div>
      <div class="model-stat-card">
        <span class="model-stat-card__icon model-stat-card__icon--danger"><CircleAlert :size="17" /></span>
        <div class="model-stat-card__body">
          <span class="model-stat-card__value model-stat-card__value--danger">{{ statusCounts.danger }}</span>
          <span class="model-stat-card__label">异常模型</span>
        </div>
      </div>
    </div>

    <div class="model-layout">
      <div class="model-layout__main">
        <div class="admin-panel">
          <div class="admin-toolbar models-toolbar model-toolbar">
        <el-input v-model="params.query" data-test="models-query" aria-label="搜索模型" clearable placeholder="搜索业务别名或名称" @keyup.enter="load(0)" />
        <el-select v-model="params.status" data-test="models-status" aria-label="模型状态" clearable placeholder="全部状态">
          <el-option label="正常" value="ENABLED" /><el-option label="已停用" value="DISABLED" />
        </el-select>
        <el-select v-model="params.sort" data-test="models-sort" aria-label="模型排序字段">
          <el-option label="业务别名" value="code" /><el-option label="名称" value="displayName" />
          <el-option label="上下文" value="contextWindow" /><el-option label="最大输出" value="maxOutputTokens" />
          <el-option label="限流" value="requestsPerMinute" /><el-option label="启用状态" value="enabled" />
        </el-select>
        <el-select v-model="params.direction" data-test="models-direction" aria-label="模型排序方向">
          <el-option label="升序" value="asc" /><el-option label="降序" value="desc" />
        </el-select>
        <el-button @click="clearFilters">清空</el-button>
        <el-button data-test="models-filter" type="primary" :loading="loading" @click="load(0)"><Search :size="16" />查询</el-button>
      </div>

      <el-skeleton v-if="loading" :rows="6" animated />
      <div v-else class="table-scroll">
        <table class="admin-table model-table">
          <thead>
            <tr><th>业务别名</th><th>名称</th><th>模型能力</th><th>上下文</th><th>最大输出</th><th>限流</th><th>服务商</th><th>上游真实模型</th><th>状态</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="model in models" :key="model.id">
              <td><code class="model-code">{{ model.code }}</code></td>
              <td><strong>{{ model.displayName }}</strong><small v-if="model.aliases.some((alias) => alias !== model.code)" class="model-aliases">{{ model.aliases.filter((alias) => alias !== model.code).join('、') }}</small></td>
              <td><div class="model-capabilities"><el-tag v-for="capability in model.capabilities" :key="capability" :data-test="`model-capability-${model.code}-${capability}`" size="small" effect="plain">{{ capabilityLabel(capability) }}</el-tag></div></td>
              <td class="numeric-cell">{{ model.contextWindow.toLocaleString() }}</td>
              <td class="numeric-cell">{{ model.maxOutputTokens.toLocaleString() }}</td>
              <td class="numeric-cell">{{ model.requestsPerMinute.toLocaleString() }} 次/分钟</td>
              <td><div v-if="routeProviders(model).length" class="route-summary" :data-test="`model-providers-${model.code}`" :title="routeProviders(model).join('、')"><span>{{ routeProviders(model).join('、') }}</span></div><span v-else class="route-empty"><Waypoints :size="15" />未配置</span></td>
              <td><div v-if="primaryRoutes(model).length" class="route-summary upstream-summary" :data-test="`model-routes-${model.code}`"><span v-for="modelRoute in primaryRoutes(model)" :key="routeKey(modelRoute)"><code>{{ modelRoute.upstreamModel }}</code><small v-if="model.routingStrategy === 'COMPOSITE'">{{ modelRoute.componentDisplayName }}</small></span></div><span v-else>—</span></td>
              <td><el-tag size="small" :type="modelStatus(model).type">{{ modelStatus(model).label }}</el-tag></td>
              <td>
                <div class="model-row-actions">
                  <el-tooltip :content="model.enabled ? '下架模型' : '上架模型'" placement="top">
                    <el-switch
                      :model-value="model.enabled"
                      :aria-label="`${model.displayName}上架状态`"
                      :data-test="`model-status-${model.id}`"
                      :loading="mutatingId === model.id"
                      :disabled="Boolean(mutatingId || deletingId)"
                      @change="(value: string | number | boolean) => changeModelStatus(model, Boolean(value))"
                    />
                  </el-tooltip>
                  <el-tooltip content="编辑模型" placement="top">
                    <el-button :data-test="`model-edit-${model.id}`" class="model-icon-action" size="small" aria-label="编辑模型" @click="openEdit(model)"><Pencil :size="15" /></el-button>
                  </el-tooltip>
                  <el-tooltip content="测试模型（会真实调用一次上游）" placement="top">
                    <el-button :data-test="`model-test-${model.id}`" class="model-icon-action" size="small" aria-label="测试模型" :loading="testingId === model.id" :disabled="Boolean(mutatingId || deletingId)" @click="testModelChannel(model)"><TestTube2 v-if="testingId !== model.id" :size="15" /></el-button>
                  </el-tooltip>
                  <el-tooltip content="删除模型" placement="top">
                    <el-button :data-test="`model-delete-${model.id}`" class="model-icon-action" size="small" type="danger" plain aria-label="删除模型" :loading="deletingId === model.id" :disabled="Boolean(mutatingId || deletingId)" @click="removeModel(model)"><Trash2 v-if="deletingId !== model.id" :size="15" /></el-button>
                  </el-tooltip>
                </div>
              </td>
            </tr>
            <tr v-if="!models.length"><td colspan="10" class="admin-empty"><Boxes :size="18" />暂无模型</td></tr>
          </tbody>
        </table>
      </div>
      <div class="admin-pagination">
        <span>共 {{ total }} 条</span>
        <el-pagination data-test="models-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="params.page + 1" :page-size="params.size" :page-sizes="pageSizeOptions" :total="total" @size-change="changePageSize" @current-change="(value: number) => load(value - 1)" />
      </div>
        </div>
      </div>

      <aside class="model-aside">
        <div class="model-aside__panel">
          <h3 class="model-aside__title"><TrendingUp :size="16" />模型调用参考</h3>
          <p class="model-aside__sub">按限流上限排序 · 当前筛选内</p>
          <ol v-if="topModels.length" class="model-top-list">
            <li v-for="(item, index) in topModels" :key="item.code" class="model-top-list__item">
              <span class="model-top-list__rank" :class="{ 'model-top-list__rank--top': index < 3 }">{{ index + 1 }}</span>
              <div class="model-top-list__info">
                <span class="model-top-list__name" :title="item.displayName">{{ item.displayName }}</span>
                <code class="model-top-list__code">{{ item.code }}</code>
              </div>
              <div class="model-top-list__metric">
                <span class="model-top-list__value">{{ item.requestsPerMinute.toLocaleString() }}</span>
                <span class="model-top-list__unit">次/分</span>
              </div>
            </li>
          </ol>
          <p v-else class="model-aside__empty"><BoxesIcon :size="16" />暂无可参考的启用模型</p>
        </div>

        <div class="model-aside__panel">
          <h3 class="model-aside__title"><ServerCog :size="16" />服务商概况</h3>
          <p class="model-aside__sub">共 {{ providers.length }} 家接入</p>
          <ul v-if="providers.length" class="model-provider-list">
            <!-- 不再只显示前 6 家：清理废弃接入时需要能删到任意一家。 -->
            <li v-for="provider in providers" :key="provider.id" class="model-provider-list__item">
              <span class="model-provider-list__dot" :class="provider.enabled ? 'model-provider-list__dot--on' : 'model-provider-list__dot--off'"></span>
              <span class="model-provider-list__name" :title="`${provider.name}（${provider.providerType}）`">{{ provider.name }}</span>
              <el-tag size="small" :type="provider.enabled ? 'success' : 'info'">{{ provider.enabled ? '可用' : '停用' }}</el-tag>
              <el-button
                link
                type="danger"
                size="small"
                :data-test="`provider-delete-${provider.id}`"
                :loading="providerDeletingId === provider.id"
                @click="removeProvider(provider)"
              >删除</el-button>
            </li>
          </ul>
          <p v-else class="model-aside__empty"><ServerCog :size="16" />尚无服务商</p>
        </div>
      </aside>
    </div>
  </section>

  <el-dialog v-model="dialogOpen" class="model-config-dialog" :title="form.id ? '编辑模型配置' : '新增模型配置'" width="min(960px, calc(100vw - 32px))" top="5vh" :close-on-click-modal="false" destroy-on-close>
      <el-form label-position="top" class="model-config-form" @submit.prevent="save">
        <section class="configuration-section" aria-labelledby="model-configuration-heading">
          <div class="configuration-heading"><ServerCog :size="18" /><div><h3 id="model-configuration-heading">模型信息</h3><p>业务标识、能力和调用限制</p></div></div>
          <div class="configuration-grid">
            <el-form-item label="业务别名" required><el-input v-model="form.code" data-test="model-code" :disabled="Boolean(form.id)" placeholder="例如 multimodal-basic" /></el-form-item>
            <el-form-item label="模型名称" required><el-input v-model="form.displayName" data-test="model-name" /></el-form-item>
            <el-form-item class="is-full" label="兼容别名"><el-input v-model="form.aliasText" data-test="model-aliases" placeholder="多个别名使用逗号分隔" /></el-form-item>
            <el-form-item class="is-full" label="模型能力" required><el-checkbox-group v-model="form.capabilities"><el-checkbox value="TEXT">文本</el-checkbox><el-checkbox value="IMAGE">图像</el-checkbox><el-checkbox value="FILE">文件</el-checkbox><el-checkbox value="VIDEO">视频</el-checkbox><el-checkbox value="IMAGE_GEN">图像生成</el-checkbox><el-checkbox value="AUDIO_TRANSCRIBE">语音转写</el-checkbox><el-checkbox value="AUDIO_SYNTHESIS">语音合成</el-checkbox><el-checkbox value="LIP_SYNC">口型替换</el-checkbox></el-checkbox-group></el-form-item>
            <el-form-item label="上下文窗口" required><el-input-number v-model="form.contextWindow" :min="1" controls-position="right" /></el-form-item>
            <el-form-item label="最大输出积分" required><el-input-number v-model="form.maxOutputTokens" data-test="model-max-output" :min="1" controls-position="right" /></el-form-item>
            <el-form-item label="请求限流（次/分钟）" required><el-input-number v-model="form.requestsPerMinute" :min="1" controls-position="right" /></el-form-item>
            <el-form-item label="模型最大并发" required><el-input-number v-model="form.maxConcurrency" :min="1" controls-position="right" /></el-form-item>
          </div>
        </section>

        <section class="configuration-section" aria-labelledby="route-configuration-heading">
          <div class="configuration-heading"><Waypoints :size="18" /><div><h3 id="route-configuration-heading">服务商与路由</h3><p>上游连接、密钥和路由参数</p></div></div>
          <div class="configuration-grid">
            <el-form-item v-if="routeOptions.length > 1" class="is-full" label="维护路由">
              <el-select :model-value="selectedRouteKey" data-test="model-route-select" @update:model-value="selectRoute">
                <el-option v-for="modelRoute in routeOptions" :key="routeKey(modelRoute)" :label="`${modelRoute.componentDisplayName} · ${modelRoute.providerName} · ${modelRoute.upstreamModel}`" :value="routeKey(modelRoute)" />
              </el-select>
            </el-form-item>
            <el-form-item label="服务商" required><el-select :model-value="form.providerId" data-test="model-provider" placeholder="选择服务商" @update:model-value="selectProvider"><el-option v-for="provider in providers" :key="provider.id" :label="provider.enabled ? provider.name : `${provider.name}（已停用）`" :value="provider.id" /></el-select></el-form-item>
            <el-form-item label="环境" required><el-select v-model="form.environment" data-test="model-environment"><el-option label="生产" value="PRODUCTION" /><el-option label="沙箱" value="SANDBOX" /><el-option label="本地" value="LOCAL" /></el-select></el-form-item>
            <!-- 服务商类型的能力提示：把「这把 key 挂错了类型」提前到保存之前 -->
            <p
              v-if="providerCapabilityHint"
              class="provider-capability-hint is-full"
              :class="{ 'provider-capability-hint--blocked': !selectedProviderChatCapabilities.length }"
              data-test="model-provider-capability"
            >
              {{ providerCapabilityHint }}
            </p>
            <el-alert
              v-if="providerCapabilityConflict"
              class="is-full provider-capability-alert"
              data-test="model-provider-capability-warning"
              type="warning"
              :closable="false"
              show-icon
              title="该服务商类型没有对话能力，保存会被后端拒绝"
              description="保存时后端会以 provider_capability_unavailable 拒绝这条路由；对话/读图请改选 BAILIAN 类型的服务商，或去掉模型能力里的文本/图像/文件。"
            />
            <el-form-item class="is-full" label="接口地址" required><el-input v-model="form.baseUrl" data-test="model-base-url" placeholder="https://..." /></el-form-item>
            <el-form-item class="is-full" label="API 密钥" :required="!form.credentialConfigured">
              <div class="model-credential-row">
                <el-input v-model="form.apiKey" data-test="model-api-key" type="password" show-password autocomplete="new-password" :placeholder="form.credentialConfigured ? '已配置，留空保持不变' : '输入服务商 API 密钥'" />
                <!-- 真带上密钥问一次上游：错 key 在这里就会 401，而不是等第一次调用 -->
                <el-button data-test="model-key-probe" :loading="probingKey" @click="detectKey"><KeyRound :size="15" />检测密钥</el-button>
              </div>
              <p v-if="probedModels.length" class="model-key-probe-result" data-test="model-key-probe-count">
                最近一次检测：该密钥可用模型 {{ probedModels.length }} 个，可直接在「上游真实模型」里选择。
              </p>
            </el-form-item>
            <el-form-item label="上游真实模型" required>
              <el-select
                v-if="probedModels.length"
                v-model="form.upstreamModel"
                data-test="model-upstream"
                filterable
                allow-create
                default-first-option
                placeholder="选择或输入上游模型名"
              >
                <el-option v-for="modelName in probedModels" :key="modelName" :label="modelName" :value="modelName" />
              </el-select>
              <el-input v-else v-model="form.upstreamModel" data-test="model-upstream" placeholder="例如 qwen-vl-plus" />
            </el-form-item>
            <template v-if="isVideoModel">
              <el-divider content-position="left">视频计费（按秒）</el-divider>
              <el-form-item label="分辨率档位" required><el-select v-model="form.videoResolutionTier" data-test="model-video-tier" aria-label="视频分辨率档位"><el-option label="480P" value="480P" /><el-option label="720P" value="720P" /><el-option label="1080P" value="1080P" /></el-select></el-form-item>
              <el-form-item label="每秒成本（分/秒）" required><el-input-number v-model="form.videoProviderUnitCost" data-test="model-video-cost" :min="1" controls-position="right" /></el-form-item>
              <el-form-item label="每秒零售价（分/秒）" required><el-input-number v-model="form.videoUnitPrice" data-test="model-video-price" :min="1" controls-position="right" /></el-form-item>
              <el-form-item label="失败重试率（%）"><el-input-number v-model="form.videoRetryRatePercent" data-test="model-video-retry" :min="0" :max="99" controls-position="right" /></el-form-item>
              <p class="video-billing-hint" data-test="model-video-hint">
                该别名只接受 <strong>{{ form.videoResolutionTier }}</strong> 的请求；按当前成本，30% 毛利地板要求每秒零售价不低于 <strong>{{ videoPriceFloor }}</strong> 分/秒。保存时会同时写入网关档位表、网关费率卡与平台侧按秒登记。
              </p>
            </template>
            <el-form-item label="启用"><el-switch v-model="form.routeEnabled" data-test="model-enabled" /></el-form-item>
            <el-form-item label="优先级"><el-input-number v-model="form.priority" :min="0" controls-position="right" /></el-form-item>
            <el-form-item label="权重"><el-input-number v-model="form.weight" :min="1" controls-position="right" /></el-form-item>
            <el-form-item label="超时（毫秒）"><el-input-number v-model="form.timeoutMs" :min="1" controls-position="right" /></el-form-item>
            <el-form-item label="渠道 RPM"><el-input-number v-model="form.routeRequestsPerMinute" :min="1" controls-position="right" /></el-form-item>
            <el-form-item label="渠道最大并发"><el-input-number v-model="form.routeMaxConcurrency" :min="1" controls-position="right" /></el-form-item>
          </div>
        </section>
      </el-form>
      <template #footer><el-button @click="dialogOpen = false">取消</el-button><el-button data-test="model-save" type="primary" :loading="saving" @click="save">保存配置</el-button></template>
    </el-dialog>

    <el-dialog v-model="providerDialogOpen" title="新增服务商" width="min(520px, 92vw)">
      <el-form label-position="top" @submit.prevent>
        <div class="provider-form-grid">
          <el-form-item label="服务商名称" required><el-input v-model="providerForm.name" data-test="provider-name" placeholder="例如 阿里云百炼" /></el-form-item>
          <el-form-item label="服务商代码" required><el-input v-model="providerForm.code" data-test="provider-code" placeholder="例如 BAILIAN" /></el-form-item>
          <el-form-item label="接口协议" required><el-select v-model="providerForm.providerType" data-test="provider-type"><el-option label="OpenAI 兼容协议" value="DEEPSEEK" /><el-option label="百炼协议" value="BAILIAN" /><el-option label="模拟服务" value="MOCK" /></el-select></el-form-item>
          <!-- 服务商类型决定后面能挂什么模型，先把能力摆出来，别等保存完才发现没适配器 -->
          <p v-if="providerFormCapabilityHint" class="provider-capability-hint is-full" data-test="provider-type-capability">{{ providerFormCapabilityHint }}</p>
          <el-form-item label="启用"><el-switch v-model="providerForm.enabled" data-test="provider-enabled" /></el-form-item>
          <el-form-item class="is-full" label="默认地址" required><el-input v-model="providerForm.baseUrl" data-test="provider-base-url" placeholder="https://..." /></el-form-item>
        </div>
      </el-form>
      <template #footer><el-button @click="providerDialogOpen = false">取消</el-button><el-button data-test="provider-save" type="primary" :loading="providerSaving" @click="saveProvider">创建</el-button></template>
    </el-dialog>

    <!--
      批量接入：一次把多条「平台别名 ← 上游模型」写到同一个渠道上。
      每条都过服务商能力守卫（服务不了就 REJECTED），notes 回传「写进去了但还不能用」的原因。
    -->
    <el-dialog v-model="onboardingOpen" class="model-onboarding-dialog" title="批量接入上游模型" width="min(880px, calc(100vw - 32px))" top="6vh" :close-on-click-modal="false" destroy-on-close>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="目标渠道" required>
          <el-select v-model="onboardingChannelId" data-test="onboard-channel" filterable placeholder="选择要接入的渠道" :loading="onboardingLoading">
            <el-option v-for="channel in onboardingChannels" :key="channel.id" :label="`${channel.name} · ${channel.code}`" :value="channel.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="onboarding-hint">
        每行一条映射。上游模型可以直接选最近一次「检测密钥」拿到的清单，也可以手填。
      </p>
      <div class="table-scroll">
        <table class="admin-table onboarding-table">
          <thead><tr><th>平台别名</th><th>上游模型</th><th>启用</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="(row, index) in onboardingRows" :key="row.key">
              <td><el-input v-model="row.modelCode" :data-test="`onboard-model-code-${index}`" placeholder="例如 vision-basic" /></td>
              <td>
                <el-select v-if="probedModels.length" v-model="row.upstreamModel" :data-test="`onboard-upstream-${index}`" filterable allow-create default-first-option placeholder="选择或输入上游模型">
                  <el-option v-for="modelName in probedModels" :key="modelName" :label="modelName" :value="modelName" />
                </el-select>
                <el-input v-else v-model="row.upstreamModel" :data-test="`onboard-upstream-${index}`" placeholder="例如 Qwen2.5-VL-72B-Instruct" />
              </td>
              <td><el-switch v-model="row.enabled" :data-test="`onboard-enabled-${index}`" :aria-label="`第 ${index + 1} 行启用状态`" /></td>
              <td>
                <el-button :data-test="`onboard-remove-${index}`" class="model-icon-action" size="small" type="danger" plain aria-label="删除该行" @click="removeOnboardingRow(index)"><Trash2 :size="15" /></el-button>
              </td>
            </tr>
            <tr v-if="!onboardingRows.length"><td colspan="4" class="admin-empty">请至少添加一行映射</td></tr>
          </tbody>
        </table>
      </div>
      <el-button data-test="onboard-add-row" class="onboarding-add" @click="addOnboardingRow"><Plus :size="15" />添加一行</el-button>

      <section v-if="onboardingResult" class="onboarding-result" data-test="onboard-result">
        <h4 class="onboarding-result__title">接入结果</h4>
        <ul class="onboarding-result__list">
          <li v-for="(route, index) in onboardingResult.routes" :key="`${route.modelCode}:${route.upstreamModel}:${index}`" :data-test="`onboard-result-${index}`">
            <el-tag size="small" :type="routeStatusType(route.status)">{{ routeStatusLabel(route.status) }}</el-tag>
            <code>{{ route.modelCode }}</code>
            <span class="onboarding-result__arrow">←</span>
            <code>{{ route.upstreamModel }}</code>
            <span v-if="route.status === 'REJECTED'" class="onboarding-result__reason" :data-test="`onboard-reason-${index}`">{{ routeRejectionLabel(route.reason) }}</span>
          </li>
        </ul>
        <!-- notes 是「接进去了但还不能用」：不是失败，但不看就会以为已经可以卖 -->
        <el-alert
          v-for="(note, index) in onboardingResult.notes"
          :key="`note-${index}`"
          class="onboarding-note"
          :data-test="`onboard-note-${index}`"
          type="warning"
          :closable="false"
          show-icon
          :title="note"
        />
      </section>

      <template #footer><el-button @click="onboardingOpen = false">关闭</el-button><el-button data-test="onboard-submit" type="primary" :loading="onboardingSaving" @click="submitOnboarding">开始接入</el-button></template>
    </el-dialog>
</template>

<style scoped>
/* ===== 统计卡（图标左、数字+标签右，水平一行） ===== */
.model-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.model-stat-card { display: flex; align-items: center; gap: 12px; padding: 14px 16px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-surface); box-shadow: var(--app-shadow-xs); }
.model-stat-card__icon { display: inline-flex; align-items: center; justify-content: center; width: 36px; height: 36px; min-width: 36px; border-radius: 8px; }
.model-stat-card__icon--total { color: var(--app-primary); background: var(--el-color-primary-light-9, var(--app-primary-soft)); }
.model-stat-card__icon--enabled { color: var(--app-success); background: var(--el-color-success-light-9, var(--app-success-soft)); }
.model-stat-card__icon--warning { color: var(--el-color-warning, var(--app-warning)); background: var(--el-color-warning-light-9, #FDF6EC); }
.model-stat-card__icon--danger { color: var(--app-danger); background: var(--el-color-danger-light-9, var(--app-danger-soft)); }
.model-stat-card__body { display: grid; gap: 2px; min-width: 0; }
.model-stat-card__value { font-size: 24px; font-weight: 800; line-height: 1; letter-spacing: -.5px; font-variant-numeric: tabular-nums; }
.model-stat-card__value--enabled { color: var(--app-success); }
.model-stat-card__value--warning { color: var(--el-color-warning, var(--app-warning)); }
.model-stat-card__value--danger { color: var(--app-danger); }
.model-stat-card__label { font-size: 12px; color: var(--app-muted-strong); white-space: nowrap; }
/* ===== 主区+侧栏布局（与套餐版本页同款左右排版） ===== */
.model-layout { display: grid; grid-template-columns: 1fr 220px; gap: 14px; align-items: start; }
.model-layout__main { min-width: 0; }
.model-aside { display: grid; gap: 14px; position: sticky; top: 12px; }
.model-aside__panel { padding: 14px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-surface); box-shadow: var(--app-shadow-xs); }
.model-aside__title { display: flex; align-items: center; gap: 7px; margin: 0; font-size: 14px; font-weight: 700; color: var(--app-fg); }
.model-aside__title svg { color: var(--app-primary); }
.model-aside__sub { margin: 3px 0 10px; font-size: 12px; color: var(--app-muted); }
.model-aside__empty { display: flex; align-items: center; gap: 7px; margin: 4px 0; color: var(--app-muted); font-size: 13px; }
/* Top 榜 */
.model-top-list { display: grid; margin: 0; padding: 0; list-style: none; }
.model-top-list__item { display: flex; align-items: center; gap: 10px; padding: 8px 0; }
.model-top-list__item + .model-top-list__item { border-top: 1px dashed var(--app-border); }
.model-top-list__rank { display: inline-flex; align-items: center; justify-content: center; width: 20px; height: 20px; min-width: 20px; border-radius: 6px; font-size: 11px; font-weight: 700; color: var(--app-muted-strong); background: var(--app-surface-muted); font-variant-numeric: tabular-nums; }
.model-top-list__rank--top { color: var(--app-primary); background: var(--el-color-primary-light-9, var(--app-primary-soft)); }
.model-top-list__info { display: grid; gap: 1px; min-width: 0; flex: 1; }
.model-top-list__name { font-size: 13px; font-weight: 600; color: var(--app-fg); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.model-top-list__code { font-size: 11px; color: var(--app-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.model-top-list__metric { display: grid; justify-items: end; }
.model-top-list__value { font-size: 14px; font-weight: 800; color: var(--app-primary); font-variant-numeric: tabular-nums; }
.model-top-list__unit { font-size: 10px; color: var(--app-muted); }
/* 服务商概况 */
.model-provider-list { display: grid; margin: 0; padding: 0; list-style: none; }
.model-provider-list__item { display: flex; align-items: center; gap: 8px; padding: 7px 0; }
.model-provider-list__item + .model-provider-list__item { border-top: 1px dashed var(--app-border); }
.model-provider-list__dot { width: 6px; height: 6px; min-width: 6px; border-radius: 50%; }
.model-provider-list__dot--on { background: var(--app-success); }
.model-provider-list__dot--off { background: var(--el-color-info, #909399); }
.model-provider-list__name { flex: 1; min-width: 0; font-size: 13px; color: var(--app-fg); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* ===== 原有样式 ===== */
.model-page-header { align-items: flex-start; }
.models-toolbar { padding: 12px 14px 0; }
.model-toolbar { grid-template-columns: minmax(220px, 1.5fr) repeat(3, minmax(138px, .8fr)) repeat(2, auto); align-items: end; }
.model-toolbar :deep(.el-input__wrapper), .model-toolbar :deep(.el-select__wrapper), .model-toolbar :deep(.el-button) { min-height: 40px; }
.model-table { min-width: 1416px; table-layout: fixed; }
.model-table :is(th, td):nth-child(1) { width: 132px; }
.model-table :is(th, td):nth-child(2) { width: 150px; }
.model-table :is(th, td):nth-child(3) { width: 130px; }
.model-table :is(th, td):nth-child(4), .model-table :is(th, td):nth-child(5) { width: 92px; }
.model-table :is(th, td):nth-child(6) { width: 112px; }
.model-table :is(th, td):nth-child(7) { width: 150px; }
.model-table :is(th, td):nth-child(8) { width: 190px; }
.model-table :is(th, td):nth-child(9) { width: 86px; }
.model-table :is(th, td):nth-child(10) { width: 216px; }
.model-code { overflow-wrap: anywhere; color: var(--app-primary); font-weight: 600; }
.model-aliases { display: block; margin-top: 4px; overflow: hidden; color: var(--app-muted); text-overflow: ellipsis; white-space: nowrap; }
.model-capabilities { display: flex; flex-wrap: wrap; gap: 4px; }
.numeric-cell { font-variant-numeric: tabular-nums; white-space: nowrap; }
.route-summary { display: grid; gap: 5px; }
.route-summary > span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.upstream-summary span { display: grid; gap: 2px; }
.upstream-summary small { color: var(--app-muted); }
.route-empty { display: inline-flex; align-items: center; gap: 5px; color: var(--app-muted); }
.model-row-actions { display: flex; min-width: 184px; align-items: center; gap: 8px; }
.model-row-actions :deep(.el-switch) { min-width: 44px; min-height: 36px; }
.model-icon-action { width: 36px; min-width: 36px; height: 36px; padding: 0; }
.model-config-form { max-height: min(68vh, 720px); padding-right: 4px; overflow-y: auto; }
.configuration-section + .configuration-section { margin-top: 24px; padding-top: 22px; border-top: 1px solid var(--app-border); }
.configuration-heading { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 16px; color: var(--app-primary); }
.configuration-heading h3 { margin: 0; color: var(--app-fg); font-size: 16px; line-height: 1.4; }
.configuration-heading p { margin: 2px 0 0; color: var(--app-muted); font-size: 13px; }
.configuration-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 18px; }
.configuration-grid .is-full { grid-column: 1 / -1; }
.configuration-grid :deep(.el-form-item) { min-width: 0; margin-bottom: 4px; }
.configuration-grid :deep(.el-select), .configuration-grid :deep(.el-input-number) { width: 100%; }
.configuration-grid :deep(.el-input__wrapper), .configuration-grid :deep(.el-select__wrapper), .configuration-grid :deep(.el-input-number) { min-height: 40px; }
.provider-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 18px; }
.provider-form-grid .is-full { grid-column: 1 / -1; }
.provider-form-grid :deep(.el-form-item) { min-width: 0; margin-bottom: 4px; }
/* 视频计费提示：解释档位与毛利地板，占满一行 */
.video-billing-hint {
  grid-column: 1 / -1;
  margin: 0 0 8px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--app-muted-strong);
  background: var(--app-surface-muted);
  border-radius: 6px;
}
.provider-form-grid :deep(.el-select) { width: 100%; }
.provider-form-grid :deep(.el-input__wrapper), .provider-form-grid :deep(.el-select__wrapper) { min-height: 40px; }
/* 服务商类型能力提示：选中服务商后紧贴其下方显示 */
.provider-capability-hint {
  grid-column: 1 / -1;
  margin: -2px 0 6px;
  padding: 6px 10px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--app-muted-strong);
  background: var(--app-surface-muted);
  border-radius: 6px;
}
/* 没有对话能力 = 挂在上面的文本/读图模型每次调用都会被拒，标红 */
.provider-capability-hint--blocked { color: var(--app-danger); background: var(--app-danger-soft); }
.provider-capability-alert { margin-bottom: 8px; }
.model-credential-row { display: flex; width: 100%; align-items: center; gap: 10px; }
.model-credential-row :deep(.el-input) { flex: 1; min-width: 0; }
.model-credential-row :deep(.el-button) { flex: none; min-height: 40px; }
.model-key-probe-result { margin: 6px 0 0; font-size: 12px; line-height: 1.6; color: var(--app-success); }
/* 批量接入对话框 */
.onboarding-hint { margin: 0 0 10px; font-size: 12px; color: var(--app-muted); }
.onboarding-table { min-width: 560px; }
.onboarding-table :is(th, td):nth-child(1) { width: 30%; }
.onboarding-table :is(th, td):nth-child(3) { width: 90px; }
.onboarding-table :is(th, td):nth-child(4) { width: 76px; }
.onboarding-table :deep(.el-input__wrapper), .onboarding-table :deep(.el-select__wrapper) { min-height: 36px; }
.onboarding-add { margin-top: 10px; }
.onboarding-result { margin-top: 16px; padding-top: 14px; border-top: 1px solid var(--app-border); }
.onboarding-result__title { margin: 0 0 10px; font-size: 14px; color: var(--app-fg); }
.onboarding-result__list { display: grid; margin: 0 0 12px; padding: 0; list-style: none; gap: 8px; }
.onboarding-result__list li { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 13px; }
.onboarding-result__arrow { color: var(--app-muted); }
.onboarding-result__reason { color: var(--app-danger); font-weight: 600; }
.onboarding-note + .onboarding-note { margin-top: 8px; }
@media (max-width: 1100px) {
  .model-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .model-layout { grid-template-columns: 1fr; }
  .model-aside { position: static; grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 720px) {
  .model-aside { grid-template-columns: 1fr; }
  .model-page-header { align-items: stretch; }
  .model-toolbar, .configuration-grid, .provider-form-grid { grid-template-columns: 1fr; }
  .configuration-grid .is-full, .provider-form-grid .is-full { grid-column: auto; }
  .model-toolbar :deep(.el-input__wrapper), .model-toolbar :deep(.el-select__wrapper), .model-toolbar :deep(.el-button), .configuration-grid :deep(.el-input__wrapper), .configuration-grid :deep(.el-select__wrapper), .configuration-grid :deep(.el-input-number), .provider-form-grid :deep(.el-input__wrapper), .provider-form-grid :deep(.el-select__wrapper) { min-height: 44px; }
  .model-config-form { max-height: 72vh; }
}
</style>
