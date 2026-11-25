<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ChevronDown, ChevronUp, Download, RefreshCw, Search, SlidersHorizontal } from 'lucide-vue-next'

import {
  listAdminOrders,
  listAdminPlans,
  listAdminUsage,
  listAdminUsers,
  listChannels,
  listModels,
  listProviders,
  type AdminChannel,
  type AdminModel,
  type AdminOrder,
  type AdminPlanVersion,
  type AdminProvider,
  type AdminUser,
} from '../../api/admin'

import AppChart from '../../components/charts/AppChart.vue'
import OperatingAlertsPanel from '../../components/admin/OperatingAlertsPanel.vue'
import OperatingReconciliationPanel from '../../components/admin/OperatingReconciliationPanel.vue'
import {
  acknowledgeOperatingAlert,
  exportOperatingReport,
  getOperatingAlerts,
  getOperatingBreakdown,
  getOperatingReconciliation,
  getOperatingSummary,
  getOperatingTrend,
  resolveOperatingAlert,
  type BillingMode,
  type OperatingAlert,
  type OperatingReportFilter,
  type OperatingReportResult,
  type OperatingSummary,
  type ReconciliationResult,
  type ReconciliationItem,
  type ReportGrain,
} from '../../api/operatingReports'
import { userOptionLabel } from '../../utils/adminDisplay'
import { loadAllPages } from '../../utils/adminPagination'

const today = new Date()
const start = new Date(today)
start.setDate(start.getDate() - 29)

const filters = reactive<OperatingReportFilter>({
  from: dateValue(start),
  to: dateValue(today),
  grain: 'DAY',
  logicalModel: '',
  channel: '',
  provider: '',
  billingMode: '',
  currency: 'CNY',
  tenantId: '',
  userId: '',
  packagePlanVersionId: '',
  upstreamModel: '',
})
const router = useRouter()
const advancedOpen = ref(false)
const summary = ref<OperatingSummary | null>(null)
const trend = ref<OperatingReportResult | null>(null)
const breakdown = ref<OperatingReportResult | null>(null)
const modelOptions = ref<AdminModel[]>([])
const channelOptions = ref<AdminChannel[]>([])
const providerOptions = ref<AdminProvider[]>([])
const userOptions = ref<AdminUser[]>([])
const planOptions = ref<AdminPlanVersion[]>([])
const upstreamModelOptions = ref<string[]>([])
const reportDetailPage = ref(1)
const reportDetailPageSize = ref(10)
const reportPageSizeOptions = [5, 10, 20]
const reconciliation = ref<ReconciliationResult | null>(null)
const alerts = ref<OperatingAlert[]>([])
const reconciliationPageSize = ref(20)
const alertsPageSize = ref(20)
const reconciliationPage = ref(0)
const reconciliationTotal = ref(0)
const alertsPage = ref(0)
const alertsTotal = ref(0)
/**
 * 告警列表默认只看待处理；已解除告警必须能看到"是谁解除的"
 * （计划任务自动解除 / 人工解除），否则解除方式永远没有展示机会。
 */
const alertsStatus = ref<OperatingAlert['status']>('OPEN')
const alertStatusOptions: Array<OperatingAlert['status']> = ['OPEN', 'ACKED', 'RESOLVED']
const alertStatusLabels: Record<OperatingAlert['status'], string> = {
  OPEN: '待处理',
  ACKED: '已确认',
  RESOLVED: '已解决',
}
const reconciliationLoading = ref(false)
const alertsLoading = ref(false)
const loading = ref(false)
const error = ref('')
const reconciliationError = ref('')
const alertsError = ref('')
const filterError = ref('')
const optionError = ref('')
const optionLoading = ref(false)
const overviewLoading = ref(false)
const overviewError = ref('')
const monthSummary = ref<OperatingSummary | null>(null)
const recentTrend = ref<OperatingReportResult | null>(null)
const recentOrders = ref<AdminOrder[]>([])
const pendingOrderCount = ref<number | null>(null)
const activeTab = ref<'detail' | 'reconciliation' | 'alerts'>('reconciliation')
const alertBusyId = ref('')
const locatedSourceId = ref('')
let loadEpoch = 0
let reconciliationEpoch = 0
let alertsEpoch = 0

const tenantOptions = computed(() => {
  const seen = new Set<string>()
  return userOptions.value.filter((user) => {
    if (seen.has(user.tenantId)) return false
    seen.add(user.tenantId)
    return true
  })
})
const pagedBreakdownRows = computed(() => {
  const startIndex = (reportDetailPage.value - 1) * reportDetailPageSize.value
  return breakdown.value?.rows.slice(startIndex, startIndex + reportDetailPageSize.value) || []
})

const integer = new Intl.NumberFormat('zh-CN')
const moneyFormatters = new Map<string, Intl.NumberFormat>()
const money = (cents: number | undefined, currency = summary.value?.currency || filters.currency || 'CNY') => {
  let formatter = moneyFormatters.get(currency)
  if (!formatter) {
    formatter = new Intl.NumberFormat('zh-CN', { style: 'currency', currency, currencyDisplay: 'narrowSymbol' })
    moneyFormatters.set(currency, formatter)
  }
  return formatter.format((cents || 0) / 100)
}
const margin = (value: number | null | undefined) => value == null ? '-' : `${(value * 100).toFixed(2)}%`

const metricItems = computed(() => [
  ['充值入账', money(summary.value?.cashInAmount)],
  ['确认收入', money(summary.value?.confirmedRevenueAmount)],
  ['经营利润', money(summary.value?.profitAmount)],
  ['利润率', margin(summary.value?.profitMargin)],
  ['供应商成本', money(summary.value?.providerCostAmount)],
  ['渠道手续费', money((summary.value?.paymentProviderFeeAmount || 0) + (summary.value?.refundProviderFeeAmount || 0))],
  ['积分用量', integer.format(summary.value?.totalTokens || 0)],
])

// —— 概览统计：收入 / 订单 / 消费 三组指标 ——
const monthRevenue = computed(() => monthSummary.value?.confirmedRevenueAmount ?? null)
const yesterdayRevenue = computed(() => {
  const rows = recentTrend.value?.rows || []
  const yesterday = dateValue(new Date(Date.now() - 86400000))
  return rows.find((row) => row.periodStart === yesterday)?.metrics.confirmedRevenueAmount ?? null
})
const revenueDelta = computed(() => {
  const rows = recentTrend.value?.rows || []
  if (rows.length < 2) return null
  const yesterday = dateValue(new Date(Date.now() - 86400000))
  const dayBefore = dateValue(new Date(Date.now() - 2 * 86400000))
  const yesterdayValue = rows.find((r) => r.periodStart === yesterday)?.metrics.confirmedRevenueAmount
  const beforeValue = rows.find((r) => r.periodStart === dayBefore)?.metrics.confirmedRevenueAmount
  if (yesterdayValue == null || beforeValue == null || beforeValue === 0) return null
  return Math.round(((yesterdayValue - beforeValue) / beforeValue) * 100)
})

const totalOrderCount = ref<number | null>(null)
const paidOrderCount = ref<number | null>(null)
const pendingOrders = computed(() => pendingOrderCount.value)
const conversionRate = computed(() => {
  const total = totalOrderCount.value
  const paid = paidOrderCount.value
  if (!total || total === 0 || paid == null) return null
  return Math.round((paid / total) * 1000) / 10
})
const averageOrderValue = computed(() => {
  const paid = recentOrders.value.filter((o) => o.status === 'PAID')
  if (paid.length === 0) return null
  const totalAmount = paid.reduce((sum, o) => sum + o.amount, 0)
  return Math.round(totalAmount / paid.length)
})
const monthProfit = computed(() => monthSummary.value?.profitAmount ?? null)

// —— Top 5 高消费用户：按已支付订单金额聚合 ——
const topConsumers = computed(() => {
  const byUser = new Map<string, { userId: string; name: string; packageName: string; totalAmount: number; orderCount: number }>()
  for (const order of recentOrders.value) {
    if (order.status !== 'PAID') continue
    const existing = byUser.get(order.userId)
    if (existing) {
      existing.totalAmount += order.amount
      existing.orderCount += 1
    } else {
      byUser.set(order.userId, {
        userId: order.userId,
        name: order.userDisplayName || order.userNo || order.userId,
        packageName: displayPlanName(order),
        totalAmount: order.amount,
        orderCount: 1,
      })
    }
  }
  return [...byUser.values()]
    .sort((a, b) => b.totalAmount - a.totalAmount)
    .slice(0, 5)
})

// —— 套餐名称中文映射：优先取套餐版本的目录名 ——
const planNameByCode = computed(() => {
  const map = new Map<string, string>()
  for (const plan of planOptions.value) {
    if (plan.code && plan.name) map.set(plan.code, plan.name)
  }
  return map
})

function displayPlanName(order: AdminOrder): string {
  return planNameByCode.value.get(order.packageCode)
    || order.packageName
    || order.packageCode
}

// —— 套餐销售占比 ——
const packageSales = computed(() => {
  const byPackage = new Map<string, { name: string; count: number; amount: number }>()
  for (const order of recentOrders.value) {
    if (order.status !== 'PAID') continue
    const key = order.packageCode
    const existing = byPackage.get(key)
    if (existing) {
      existing.count += 1
      existing.amount += order.amount
    } else {
      byPackage.set(key, { name: displayPlanName(order), count: 1, amount: order.amount })
    }
  }
  return [...byPackage.values()].sort((a, b) => b.count - a.count)
})

const packageSalesOption = computed(() => {
  const data = packageSales.value
  if (data.length === 0) return null
  const palette = ['#4F95F5', '#0F766E', '#94A3B8', '#D97706', '#6E7788']
  return {
    aria: { enabled: true, description: '各套餐销售数量占比' },
    color: palette,
    tooltip: {
      trigger: 'item',
      formatter: (params: unknown) => {
        const p = params as { name: string; value: number; percent: number }
        return `${p.name}<br/>销售 ${p.value} 单`
      },
    },
    legend: { bottom: 8, type: 'scroll' },
    series: [{
      type: 'pie',
      radius: ['38%', '62%'],
      center: ['50%', '42%'],
      avoidLabelOverlap: true,
      itemStyle: { borderColor: '#fff', borderWidth: 2 },
      label: { show: false },
      emphasis: { label: { show: true, fontSize: 13, fontWeight: 'bold' } },
      data: data.map((item) => ({ name: item.name, value: item.count })),
    }],
  }
})

const trendOption = computed(() => ({
  aria: { enabled: true, description: `${grainLabel(filters.grain)}经营收入、成本和利润趋势` },
  color: ['#4F95F5', '#94A3B8', '#0F766E'],
  tooltip: {
    trigger: 'axis',
    valueFormatter: (value: unknown) => money(Number(value) * 100),
  },
  legend: { data: ['确认收入', '供应商成本', '经营利润'], top: 12 },
  grid: { left: 56, right: 24, top: 52, bottom: 42, containLabel: true },
  xAxis: { type: 'category', data: trend.value?.rows.map(row => row.periodStart) || [], axisLabel: { hideOverlap: true } },
  yAxis: { type: 'value', name: (summary.value?.currency || filters.currency) === 'USD' ? '美元' : '元', splitLine: { lineStyle: { color: '#e2e8f0' } } },
  series: [
    { name: '确认收入', type: 'line', smooth: false, symbolSize: 6, data: amounts('confirmedRevenueAmount') },
    { name: '供应商成本', type: 'line', smooth: false, symbolSize: 6, data: amounts('providerCostAmount') },
    { name: '经营利润', type: 'bar', data: amounts('profitAmount') },
  ],
}))

function amounts(field: 'confirmedRevenueAmount' | 'providerCostAmount' | 'profitAmount') {
  return trend.value?.rows.map(row => Number((row.metrics[field] / 100).toFixed(2))) || []
}

async function load() {
  const epoch = ++loadEpoch
  loading.value = true
  error.value = ''
  const request = { ...filters }
  void loadReconciliation(request)
  void loadAlerts()
  try {
    const [summaryValue, trendValue, breakdownValue] = await Promise.all([
      getOperatingSummary(request), getOperatingTrend(request), getOperatingBreakdown(request),
    ])
    if (epoch !== loadEpoch) return
    summary.value = summaryValue
    trend.value = trendValue
    breakdown.value = breakdownValue
    reportDetailPage.value = 1
  } catch {
    if (epoch !== loadEpoch) return
    error.value = '经营数据加载失败，请检查筛选条件后重试。'
  } finally {
    if (epoch === loadEpoch) loading.value = false
  }
}

async function loadFilterOptions() {
  optionLoading.value = true
  optionError.value = ''
  try {
    const [models, channels, providers, users, plans, usage] = await Promise.allSettled([
      loadAllPages((page, size) => listModels({ page, size, query: '', status: '', sort: 'code', direction: 'asc' })),
      loadAllPages((page, size) => listChannels({ page, size, query: '', status: '', sort: 'code', direction: 'asc' })),
      listProviders(),
      loadAllPages((page, size) => listAdminUsers({ page, size, query: '', status: '', sort: 'userNo', direction: 'asc' })),
      loadAllPages((page, size) => listAdminPlans({ page, size, query: '', status: '', sort: 'code', direction: 'asc' })),
      loadAllPages((page, size) => listAdminUsage({
        page, size, query: '', status: '', sort: 'createdAt', direction: 'desc',
        userId: '', modelCode: '', outcome: '', requestId: '',
      })),
    ])
    if (models.status === 'fulfilled') modelOptions.value = models.value
    if (channels.status === 'fulfilled') channelOptions.value = channels.value
    if (providers.status === 'fulfilled') providerOptions.value = providers.value
    if (users.status === 'fulfilled') userOptions.value = users.value
    if (plans.status === 'fulfilled') planOptions.value = plans.value
    if (usage.status === 'fulfilled') {
      upstreamModelOptions.value = [...new Set(usage.value.flatMap((entry) => (
        entry.stages?.map((stage) => stage.upstreamModel).filter(Boolean) || []
      )))].sort()
    }
    if ([models, channels, providers, users, plans, usage].some((result) => result.status === 'rejected')) {
      optionError.value = '部分筛选项加载失败，请重试。'
    }
  } finally {
    optionLoading.value = false
  }
}

async function loadReconciliation(request: OperatingReportFilter = { ...filters }) {
  const epoch = ++reconciliationEpoch
  reconciliationLoading.value = true
  reconciliationError.value = ''
  try {
    const value = await getOperatingReconciliation(
      request, reconciliationPage.value, reconciliationPageSize.value,
    )
    if (epoch !== reconciliationEpoch) return
    const lastPage = Math.max(0, Math.ceil(value.total / reconciliationPageSize.value) - 1)
    if (value.page > lastPage) {
      reconciliationPage.value = lastPage
      await loadReconciliation(request)
      return
    }
    reconciliation.value = value
    reconciliationTotal.value = value.total
    reconciliationPage.value = value.page
  } catch {
    if (epoch === reconciliationEpoch) reconciliationError.value = '对账数据加载失败，请稍后重试。'
  } finally {
    if (epoch === reconciliationEpoch) reconciliationLoading.value = false
  }
}

async function loadAlerts() {
  const epoch = ++alertsEpoch
  alertsLoading.value = true
  alertsError.value = ''
  try {
    const value = await getOperatingAlerts(alertsStatus.value, alertsPage.value, alertsPageSize.value)
    if (epoch !== alertsEpoch) return
    const lastPage = Math.max(0, Math.ceil(value.total / alertsPageSize.value) - 1)
    if (value.page > lastPage) {
      alertsPage.value = lastPage
      await loadAlerts()
      return
    }
    alerts.value = value.items
    alertsTotal.value = value.total
    alertsPage.value = value.page
  } catch {
    if (epoch === alertsEpoch) alertsError.value = '告警数据加载失败，请稍后重试。'
  } finally {
    if (epoch === alertsEpoch) alertsLoading.value = false
  }
}

async function loadOverviewStats() {
  overviewLoading.value = true
  overviewError.value = ''
  const now = new Date()
  const monthStart = dateValue(new Date(now.getFullYear(), now.getMonth(), 1))
  const todayStr = dateValue(now)
  const yesterdayStr = dateValue(new Date(Date.now() - 86400000))
  const dayBeforeStr = dateValue(new Date(Date.now() - 2 * 86400000))
  try {
    const [month, recent, allOrders, pendingResult] = await Promise.allSettled([
      getOperatingSummary({ from: monthStart, to: todayStr, grain: 'DAY', currency: filters.currency }),
      getOperatingTrend({ from: dayBeforeStr, to: todayStr, grain: 'DAY', currency: filters.currency }),
      listAdminOrders({ page: 0, size: 100, query: '', status: '', sort: 'createdAt', direction: 'desc' }),
      listAdminOrders({ page: 0, size: 1, query: '', status: 'PENDING', sort: 'createdAt', direction: 'desc' }),
    ])
    if (month.status === 'fulfilled') monthSummary.value = month.value
    if (recent.status === 'fulfilled') recentTrend.value = recent.value
    if (allOrders.status === 'fulfilled') {
      recentOrders.value = allOrders.value.items
      totalOrderCount.value = allOrders.value.total
      paidOrderCount.value = allOrders.value.items.filter((o) => o.status === 'PAID').length
    }
    if (pendingResult.status === 'fulfilled') pendingOrderCount.value = pendingResult.value.total
    if ([month, recent, allOrders, pendingResult].some((r) => r.status === 'rejected')) {
      overviewError.value = '部分概览数据加载失败，请刷新重试。'
    }
  } finally {
    overviewLoading.value = false
  }
}

async function applyFilters() {
  filterError.value = validateUuidFilters()
  if (filterError.value) return
  reportDetailPage.value = 1
  reconciliationPage.value = 0
  await load()
}

async function changeReconciliationPage(page: number) {
  reconciliationPage.value = page - 1
  await loadReconciliation()
}

async function changeReconciliationPageSize(size: number) {
  reconciliationPageSize.value = size
  reconciliationPage.value = 0
  await loadReconciliation()
}

async function changeAlertsPage(page: number) {
  alertsPage.value = page - 1
  await loadAlerts()
}

async function changeAlertsPageSize(size: number) {
  alertsPageSize.value = size
  alertsPage.value = 0
  await loadAlerts()
}

async function setAlertsStatus(status: OperatingAlert['status']) {
  if (alertsStatus.value === status) return
  alertsStatus.value = status
  alertsPage.value = 0
  await loadAlerts()
}

function changeReportDetailPageSize(size: number) {
  reportDetailPageSize.value = size
  reportDetailPage.value = 1
}

function validateUuidFilters() {
  const fields: Array<[keyof OperatingReportFilter, string]> = [
    ['tenantId', '租户 ID'], ['userId', '用户 ID'], ['packagePlanVersionId', '套餐版本 ID'],
  ]
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  for (const [key, label] of fields) {
    const value = filters[key]
    if (typeof value === 'string' && value && !uuid.test(value)) return `${label} 必须是有效 UUID。`
  }
  return ''
}

async function setGrain(grain: ReportGrain) {
  if (filters.grain === grain) return
  filters.grain = grain
  reportDetailPage.value = 1
  reconciliationPage.value = 0
  await load()
}

async function downloadCsv() {
  try {
    const blob = await exportOperatingReport({ ...filters })
    if (typeof URL.createObjectURL !== 'function') return
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `operating-report-${filters.from}-${filters.to}.csv`
    link.click()
    URL.revokeObjectURL(url)
  } catch {
    error.value = '经营报表导出失败，请稍后重试。'
  }
}

async function updateAlert(id: string, action: 'ack' | 'resolve') {
  alertBusyId.value = id
  try {
    if (action === 'ack') await acknowledgeOperatingAlert(id)
    else await resolveOperatingAlert(id)
    await loadAlerts()
  } catch (cause) {
    const status = httpStatus(cause)
    alertsError.value = status === 404
      ? '告警不存在或已删除，请刷新后重试。'
      : status === 409
        ? '告警状态已变更，请刷新后重试。'
        : '告警状态更新失败，请重试。'
    error.value = '告警状态更新失败，请重试。'
  } finally {
    alertBusyId.value = ''
  }
}

function httpStatus(error: unknown) {
  if (!error || typeof error !== 'object') return 0
  const response = (error as { response?: { status?: unknown } }).response
  return typeof response?.status === 'number' ? response.status : 0
}

function locateDifference(item: ReconciliationItem) {
  navigateToSource(String(item.evidence?.sourceType || ''), item.sourceEventId, item.evidence)
}

function locateAlert(alert: OperatingAlert) {
  const sourceType = alert.sourceType || String(alert.evidence?.sourceType || '')
  const sourceEventId = alert.sourceEventId || String(alert.evidence?.sourceEventId || '')
  if (sourceType && sourceEventId) {
    navigateToSource(sourceType, sourceEventId, alert.evidence)
    return
  }
  if (alert.type === 'LOW_MARGIN_PACKAGE') {
    router.push({ path: '/admin/plans', query: { query: alert.logicalModel || alert.id } })
  } else if (alert.type === 'PROVIDER_PRICE_CHANGED') {
    router.push({ path: '/admin/models', query: { query: alert.provider || alert.logicalModel || alert.id } })
  } else if (alert.type === 'ABNORMAL_PROVIDER_COST') {
    router.push({ path: '/admin/usage', query: { modelCode: alert.logicalModel || '', provider: alert.provider || '', tab: 'usage' } })
  } else {
    fallbackToDetail(alert.id)
  }
}

function navigateToSource(sourceType: string, sourceEventId: string, evidence?: Record<string, unknown>) {
  const type = sourceType.toUpperCase()
  const requestId = evidenceText(evidence, 'requestId')
  const orderNo = evidenceText(evidence, 'orderNo')
  const refundId = evidenceText(evidence, 'refundId')
  const rechargeNo = evidenceText(evidence, 'rechargeNo')
  if (type === 'PACKAGE_ORDER' && orderNo) router.push({ path: '/admin/orders', query: { orderNo, tab: 'orders' } })
  else if (type === 'COMPLETED_REFUND' && refundId) router.push({ path: '/admin/orders', query: { refundId, tab: 'refunds' } })
  else if ((type === 'MODEL_USAGE_LEDGER' || type === 'GATEWAY_ECONOMICS') && requestId) router.push({ path: '/admin/usage', query: { requestId, tab: 'usage' } })
  else if (type === 'RECHARGE_LEDGER' && rechargeNo) router.push({ path: '/admin/wallet', query: { query: rechargeNo, tab: 'recharges' } })
  else if (type === 'PROVIDER_FEE_BILL') router.push({ path: '/admin/orders', query: { query: sourceEventId, tab: 'reconciliation' } })
  else if (type === 'USER') router.push({ path: '/admin/users', query: { query: sourceEventId } })
  else if (type === 'CONTENT_SAFETY') router.push({ path: '/admin/content-safety', query: { query: sourceEventId } })
  else fallbackToDetail(sourceEventId)
}

function evidenceText(evidence: Record<string, unknown> | undefined, key: string) {
  const value = evidence?.[key]
  return typeof value === 'string' ? value.trim() : ''
}

function fallbackToDetail(sourceEventId: string) {
  locatedSourceId.value = sourceEventId
  activeTab.value = 'detail'
}

function dateValue(value: Date) {
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function grainLabel(grain: ReportGrain) {
  return ({ DAY: '按日', WEEK: '按周', MONTH: '按月' })[grain]
}

onMounted(() => {
  void loadFilterOptions()
  void load()
  void loadOverviewStats()
})
</script>

<template>
  <section class="admin-page operating-reports" aria-labelledby="operating-reports-title">
    <TopErrorMessage :message="optionError" />
    <TopErrorMessage :message="error" />
    <TopErrorMessage :message="overviewError" />
    <TopErrorMessage :message="reconciliationError" />
    <TopErrorMessage :message="alertsError" />
    <header class="admin-page__header">
      <div><h2 id="operating-reports-title">经营报表</h2><p>收入、成本、利润、经营对账与异常告警</p></div>
      <button data-test="report-export" class="report-button" type="button" :disabled="loading" @click="downloadCsv">
        <Download :size="16" aria-hidden="true" />导出 CSV
      </button>
    </header>

    <form class="report-filters" aria-label="经营报表筛选" @submit.prevent="applyFilters">
      <label>开始日期<input v-model="filters.from" type="date" required /></label>
      <label>结束日期<input v-model="filters.to" type="date" required /></label>
      <label>逻辑模型<el-select v-model="filters.logicalModel" filterable clearable placeholder="全部模型"><el-option v-for="model in modelOptions" :key="model.code" :label="model.code" :value="model.code" /></el-select></label>
      <label>渠道<el-select v-model="filters.channel" filterable clearable placeholder="全部渠道"><el-option v-for="channel in channelOptions" :key="channel.code" :label="channel.code" :value="channel.code" /></el-select></label>
      <label>供应商<el-select v-model="filters.provider" filterable clearable placeholder="全部供应商"><el-option v-for="provider in providerOptions" :key="provider.code" :label="provider.code" :value="provider.code" /></el-select></label>
      <label>计费方式<select v-model="filters.billingMode"><option value="">全部</option><option value="PACKAGE_ONLY">仅套餐</option><option value="WALLET_ONLY">仅余额</option><option value="HYBRID">混合</option></select></label>
      <button data-test="report-query" class="report-button report-filter-action is-primary" type="submit" :disabled="loading"><Search :size="16" aria-hidden="true" />查询</button>
      <button data-test="advanced-toggle" class="report-button report-filter-action report-advanced-toggle" type="button" :aria-expanded="advancedOpen" @click="advancedOpen = !advancedOpen">
        <SlidersHorizontal :size="16" aria-hidden="true" />高级筛选<ChevronUp v-if="advancedOpen" :size="15" aria-hidden="true" /><ChevronDown v-else :size="15" aria-hidden="true" />
      </button>
      <div v-if="advancedOpen" class="report-advanced-fields">
        <label>租户<el-select data-test="filter-tenant-id" v-model="filters.tenantId" filterable clearable placeholder="全部租户"><el-option v-for="user in tenantOptions" :key="user.tenantId" :label="userOptionLabel(user.userNo, user.displayName)" :value="user.tenantId" /></el-select></label>
        <label>用户编号<el-select data-test="filter-user-id" v-model="filters.userId" filterable clearable placeholder="全部用户"><el-option v-for="user in userOptions" :key="user.id" :label="userOptionLabel(user.userNo, user.displayName)" :value="user.id" /></el-select></label>
        <label>套餐版本<el-select data-test="filter-package-id" v-model="filters.packagePlanVersionId" filterable clearable placeholder="全部套餐"><el-option v-for="plan in planOptions" :key="plan.versionId" :label="`${plan.name || plan.code} v${plan.version}`" :value="plan.versionId" /></el-select></label>
        <label>上游模型<el-select data-test="filter-upstream-model" v-model="filters.upstreamModel" filterable clearable placeholder="全部上游模型"><el-option v-for="model in upstreamModelOptions" :key="model" :label="model" :value="model" /></el-select></label>
        <label>币种<el-select data-test="filter-currency" v-model="filters.currency"><el-option label="人民币（CNY）" value="CNY" /><el-option label="美元（USD）" value="USD" /></el-select></label>
      </div>
    </form>
    <div v-if="filterError" class="report-filter-error" role="alert">{{ filterError }}</div>
    <button v-if="optionError" data-test="report-options-retry" class="report-button" type="button" :disabled="optionLoading" @click="loadFilterOptions"><RefreshCw :size="15" aria-hidden="true" />重试筛选项</button>

    <div class="report-grain" role="group" aria-label="时间粒度">
      <button v-for="grain in (['DAY', 'WEEK', 'MONTH'] as ReportGrain[])" :key="grain"
        :data-test="`grain-${grain}`" type="button" :aria-pressed="filters.grain === grain" @click="setGrain(grain)">{{ grainLabel(grain) }}</button>
    </div>

    <section class="report-overview" aria-label="经营概览">
      <div class="report-overview__group">
        <h4>收入概览</h4>
        <div class="report-overview__metrics">
          <div><span>本月收入</span><strong>{{ monthRevenue == null ? '--' : money(monthRevenue) }}</strong></div>
          <div><span>昨日收入</span><strong>{{ yesterdayRevenue == null ? '--' : money(yesterdayRevenue) }}</strong></div>
          <div><span>收入环比</span><strong :class="revenueDelta == null ? '' : revenueDelta >= 0 ? 'is-up' : 'is-down'">{{ revenueDelta == null ? '--' : `${revenueDelta > 0 ? '+' : ''}${revenueDelta}%` }}</strong></div>
        </div>
      </div>
      <div class="report-overview__group">
        <h4>订单概览</h4>
        <div class="report-overview__metrics">
          <div><span>订单总数</span><strong>{{ totalOrderCount == null ? '--' : integer.format(totalOrderCount) }}</strong></div>
          <div><span>支付成功</span><strong>{{ paidOrderCount == null ? '--' : integer.format(paidOrderCount) }}</strong></div>
          <div><span>待支付</span><strong>{{ pendingOrders == null ? '--' : integer.format(pendingOrders) }}</strong></div>
        </div>
      </div>
      <div class="report-overview__group">
        <h4>消费概览</h4>
        <div class="report-overview__metrics">
          <div><span>支付转化率</span><strong>{{ conversionRate == null ? '--' : `${conversionRate}%` }}</strong></div>
          <div><span>客单价</span><strong>{{ averageOrderValue == null ? '--' : money(averageOrderValue) }}</strong></div>
          <div><span>本月利润</span><strong>{{ monthProfit == null ? '--' : money(monthProfit) }}</strong></div>
        </div>
      </div>
    </section>

    <button v-if="error" data-test="report-retry" class="report-button" type="button" @click="load"><RefreshCw :size="15" aria-hidden="true" />重试报表</button>
    <div v-if="loading && !summary" class="report-state" aria-busy="true">数据加载中</div>

    <template v-else-if="summary">
      <section class="report-metrics" aria-label="经营核心指标">
        <div v-for="item in metricItems" :key="item[0]"><span>{{ item[0] }}</span><strong>{{ item[1] }}</strong></div>
      </section>
      <p class="report-context">充值入账计入钱包余额，充值本身不计入确认收入。</p>

      <p class="report-context">{{ filters.from }} 至 {{ filters.to }} · {{ grainLabel(filters.grain) }} · {{ summary.timezone }}<span v-if="summary.estimatedCount"> · {{ summary.estimatedCount }} 条估算数据</span></p>

      <section class="report-panel" aria-labelledby="trend-title">
        <header><div><h3 id="trend-title">经营趋势</h3><p>图表金额单位为元，悬浮提示同时保留分。</p></div></header>
        <AppChart v-if="trend?.rows.length" :option="trendOption" height="320px" ariaLabel="经营收入、成本和利润趋势" />
        <div v-else class="report-empty">当前筛选范围暂无经营数据</div>
      </section>

      <section class="report-auxiliary" aria-label="消费排行与套餐销售">
        <article class="report-panel report-consumers" aria-labelledby="consumers-title">
          <header><div><h3 id="consumers-title">Top 5 高消费用户</h3><p>按累计消费金额排序</p></div></header>
          <ol v-if="topConsumers.length" class="report-consumer-list">
            <li v-for="(consumer, index) in topConsumers" :key="consumer.userId">
              <span class="report-consumer__rank">{{ index + 1 }}</span>
              <span class="report-consumer__name">{{ consumer.name }}</span>
              <span class="report-consumer__package">{{ consumer.packageName }} · {{ consumer.orderCount }} 单</span>
              <span class="report-consumer__amount">{{ money(consumer.totalAmount) }}</span>
            </li>
          </ol>
          <div v-else class="report-empty">暂无已支付订单数据</div>
        </article>
        <article class="report-panel report-packages" aria-labelledby="packages-title">
          <header><div><h3 id="packages-title">套餐销售占比</h3><p>已支付订单按套餐分组</p></div></header>
          <AppChart v-if="packageSalesOption" :option="packageSalesOption" height="240px" ariaLabel="套餐销售数量占比" />
          <div v-else class="report-empty">暂无套餐销售数据</div>
        </article>
      </section>

      <div class="report-tabs" role="tablist" aria-label="经营核查">
        <button data-test="tab-detail" role="tab" type="button" :aria-selected="activeTab === 'detail'" @click="activeTab = 'detail'">经营明细</button>
        <button data-test="tab-reconciliation" role="tab" type="button" :aria-selected="activeTab === 'reconciliation'" @click="activeTab = 'reconciliation'">对账差异</button>
        <button data-test="tab-alerts" role="tab" type="button" :aria-selected="activeTab === 'alerts'" @click="activeTab = 'alerts'">异常告警 <span v-if="alertsTotal">{{ alertsTotal }}</span></button>
      </div>
      <section v-if="activeTab === 'detail'" class="report-panel" aria-labelledby="detail-title">
        <header><div><h3 id="detail-title">期间明细</h3><p>收入、成本与利润均来自不可变经营事实。</p></div></header>
        <div v-if="locatedSourceId" data-test="detail-source-filter" class="report-located" role="status">已按业务编号定位：<code>{{ locatedSourceId }}</code></div>
        <div v-if="breakdown?.rows.length" class="report-table-scroll"><table class="report-table"><thead><tr><th>期间</th><th>确认收入</th><th>退款</th><th>供应商成本</th><th>手续费</th><th>利润</th><th>利润率</th><th>积分</th></tr></thead><tbody><tr v-for="row in pagedBreakdownRows" :key="row.periodStart"><td>{{ row.periodStart }}</td><td>{{ money(row.metrics.confirmedRevenueAmount) }}</td><td>{{ money(row.metrics.refundAmount) }}</td><td>{{ money(row.metrics.providerCostAmount) }}</td><td>{{ money(row.metrics.paymentProviderFeeAmount + row.metrics.refundProviderFeeAmount) }}</td><td>{{ money(row.metrics.profitAmount) }}</td><td>{{ margin(row.metrics.profitMargin) }}</td><td>{{ integer.format(row.metrics.totalTokens) }}</td></tr></tbody></table></div>
        <el-pagination v-model:current-page="reportDetailPage" data-test="report-detail-pagination" background layout="sizes, prev, pager, next" :page-size="reportDetailPageSize" :page-sizes="reportPageSizeOptions" :total="breakdown?.rows.length || 0" @size-change="changeReportDetailPageSize" />
        <div v-if="!breakdown?.rows.length" class="report-empty">当前筛选范围暂无经营数据</div>
      </section>
      <template v-else-if="activeTab === 'reconciliation'">
        <button v-if="reconciliationError" data-test="reconciliation-retry" class="report-button" type="button" @click="loadReconciliation()"><RefreshCw :size="15" aria-hidden="true" />重试对账</button>
        <OperatingReconciliationPanel
          :result="reconciliation"
          :total="reconciliationTotal"
          :page="reconciliationPage"
          :page-size="reconciliationPageSize"
          :loading="reconciliationLoading"
          @locate="locateDifference"
          @page-change="changeReconciliationPage"
          @size-change="changeReconciliationPageSize"
        />
      </template>
      <template v-else>
        <button v-if="alertsError" data-test="alerts-retry" class="report-button" type="button" @click="loadAlerts"><RefreshCw :size="15" aria-hidden="true" />重试告警</button>
        <div class="report-grain alerts-status" role="group" aria-label="告警状态">
          <button
            v-for="status in alertStatusOptions"
            :key="status"
            :data-test="`alert-status-${status}`"
            type="button"
            :aria-pressed="alertsStatus === status"
            @click="setAlertsStatus(status)"
          >{{ alertStatusLabels[status] }}</button>
        </div>
        <OperatingAlertsPanel
          :alerts="alerts"
          :models="modelOptions"
          :providers="providerOptions"
          :busy-id="alertBusyId"
          :status="alertsStatus"
          :total="alertsTotal"
          :page="alertsPage"
          :page-size="alertsPageSize"
          :loading="alertsLoading"
          @locate="locateAlert"
          @acknowledge="id => updateAlert(id, 'ack')"
          @resolve="id => updateAlert(id, 'resolve')"
          @page-change="changeAlertsPage"
          @size-change="changeAlertsPageSize"
        />
      </template>
    </template>
  </section>
</template>

<style>
.operating-reports { max-width: 1440px; min-width: 0; --report-radius: 8px; }
.report-button, .report-error button, .report-table button { display: inline-flex; min-height: 36px; align-items: center; justify-content: center; gap: 6px; padding: 0 12px; color: var(--app-fg); background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 6px; cursor: pointer; font-weight: 650; }
.report-button.is-primary { color: #fff; background: var(--app-primary); border-color: var(--app-primary); }
.report-button:disabled, .report-table button:disabled { cursor: not-allowed; opacity: .55; }
.report-filters { display: grid; align-items: end; gap: 10px; margin-bottom: 10px; padding: 12px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: var(--report-radius); grid-template-columns: repeat(6, minmax(130px, 1fr)) auto auto; }
.report-filters label { display: grid; gap: 4px; color: var(--app-muted); font-size: 12px; }
.report-filters label > input, .report-filters label > select, .report-filters .el-select__wrapper { box-sizing: border-box; height: 40px; min-height: 40px; }
.report-filters label > input, .report-filters label > select { width: 100%; padding: 0 9px; color: var(--app-fg); background: #fff; border: 1px solid var(--app-border); border-radius: 6px; }
.report-filters .el-select { width: 100%; }
.report-filters > .report-filter-action { width: 100%; height: 40px; min-height: 40px; }
.report-advanced-fields { display: grid; grid-column: 1 / -1; gap: 10px; padding-top: 10px; border-top: 1px solid var(--app-border); grid-template-columns: repeat(5, minmax(0, 1fr)); }
.report-filter-error { margin: -2px 0 10px; padding: 9px 11px; color: var(--app-danger); background: var(--app-danger-soft); border: 1px solid var(--app-danger-soft); border-radius: 6px; }
.report-grain { display: inline-grid; margin-bottom: 10px; padding: 3px; background: var(--app-surface-sunken); border-radius: 7px; grid-template-columns: repeat(3, minmax(64px, 1fr)); }
.report-grain button, .report-tabs button { min-height: 36px; padding: 0 12px; color: var(--app-muted); background: transparent; border: 0; border-radius: 5px; cursor: pointer; font-weight: 650; }
.report-grain button[aria-pressed='true'], .report-tabs button[aria-selected='true'] { color: var(--app-primary); background: var(--app-surface); box-shadow: 0 1px 2px rgb(15 23 42 / 12%); }
.report-error { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; padding: 10px 12px; color: var(--app-danger); background: var(--app-danger-soft); border: 1px solid var(--app-danger-soft); border-radius: 6px; }
.report-state, .report-empty { display: grid; min-height: 120px; place-items: center; padding: 20px; color: var(--app-muted); text-align: center; }
.report-metrics { display: grid; margin-bottom: 8px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: var(--report-radius); grid-template-columns: repeat(7, minmax(0, 1fr)); box-shadow: var(--app-shadow-xs); }
.report-metrics div { min-width: 0; padding: 12px 14px; border-right: 1px solid var(--app-border); }
.report-metrics div:last-child { border-right: 0; }
.report-metrics span, .report-metrics strong { display: block; }
.report-metrics span { color: var(--app-muted); font-size: 12px; }
.report-metrics strong { margin-top: 3px; overflow-wrap: anywhere; font-size: 19px; font-variant-numeric: tabular-nums; }
.report-context { margin: 0 0 10px; color: var(--app-muted); font-size: 12px; }
.report-panel { min-width: 0; margin-bottom: 12px; overflow: hidden; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: var(--report-radius); box-shadow: var(--app-shadow-xs); }
.report-panel > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 11px 14px; border-bottom: 1px solid var(--app-border); }
.report-panel h3, .report-panel p { margin: 0; }
.report-panel h3 { font-size: 15px; }
.report-panel p { margin-top: 2px; color: var(--app-muted); font-size: 12px; }
.report-table-scroll { max-width: 100%; overflow-x: auto; }
.report-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.report-table th, .report-table td { padding: 9px 12px; border-bottom: 1px solid var(--app-surface-muted); text-align: left; white-space: nowrap; font-variant-numeric: tabular-nums; }
.report-table th { color: var(--app-muted); background: #f8fafc; font-size: 12px; font-weight: 600; }
.report-table button { min-height: 32px; padding-inline: 10px; }
.report-pagination { justify-content: flex-end; padding: 12px 14px; }
.report-actions { display: flex; gap: 6px; }
.report-tabs { display: inline-grid; margin: 2px 0 10px; padding: 3px; background: var(--app-surface-sunken); border-radius: 7px; grid-template-columns: repeat(3, minmax(100px, 1fr)); }
/* 告警状态切换：复用粒度切换的样式语言，<template v-else> 内需要自行留出间距 */
.alerts-status { margin: 0 0 10px; grid-template-columns: repeat(3, minmax(84px, 1fr)); }
.report-located { margin-top: -4px; padding: 10px 12px; color: var(--app-muted); background: var(--app-primary-soft); border: 1px solid var(--app-primary-border); border-radius: 6px; }

/* —— 概览指标卡：收入 / 订单 / 消费 —— */
.report-overview { display: grid; gap: 12px; margin-bottom: 12px; grid-template-columns: repeat(3, minmax(0, 1fr)); }
.report-overview__group { padding: 14px 16px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: var(--report-radius); box-shadow: var(--app-shadow-xs); }
.report-overview__group h4 { margin: 0 0 10px; font-size: 13px; font-weight: 700; color: var(--app-fg); }
.report-overview__metrics { display: grid; gap: 8px; grid-template-columns: repeat(3, minmax(0, 1fr)); }
.report-overview__metrics div { min-width: 0; }
.report-overview__metrics span { display: block; color: var(--app-muted); font-size: 11px; }
.report-overview__metrics strong { display: block; margin-top: 2px; font-size: 17px; font-weight: 700; font-variant-numeric: tabular-nums; color: var(--app-fg); overflow-wrap: anywhere; }
.report-overview__metrics strong.is-up { color: var(--app-success); }
.report-overview__metrics strong.is-down { color: var(--app-danger-text); }

/* —— 辅助双列：Top 消费 + 套餐占比 —— */
.report-auxiliary { display: grid; gap: 12px; margin-bottom: 12px; grid-template-columns: minmax(0, 2fr) minmax(0, 3fr); }
.report-auxiliary .report-panel { margin-bottom: 0; }
.report-consumer-list { margin: 0; padding: 0 14px; list-style: none; }
.report-consumer-list li { display: flex; align-items: center; gap: 10px; padding: 10px 0; border-top: 1px solid var(--app-border); }
.report-consumer-list li:first-child { border-top: 0; }
.report-consumer__rank { display: inline-grid; width: 22px; height: 22px; flex: 0 0 22px; place-items: center; font-size: 12px; font-weight: 700; color: var(--app-muted-strong); background: var(--app-surface-subtle); border-radius: 4px; }
.report-consumer__name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; font-weight: 600; color: var(--app-fg); }
.report-consumer__package { color: var(--app-muted); font-size: 12px; white-space: nowrap; }
.report-consumer__amount { font-size: 13px; font-weight: 700; font-variant-numeric: tabular-nums; color: var(--app-fg); white-space: nowrap; }

@media (max-width: 1100px) { .report-filters { grid-template-columns: repeat(3, minmax(0, 1fr)); } .report-advanced-fields { grid-template-columns: repeat(3, minmax(0, 1fr)); } .report-metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); } .report-metrics div:nth-child(3n) { border-right: 0; } .report-metrics div:nth-child(-n + 3) { border-bottom: 1px solid var(--app-border); } .report-overview { grid-template-columns: minmax(0, 1fr); } .report-auxiliary { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 600px) { .report-filters, .report-advanced-fields { grid-template-columns: minmax(0, 1fr); } .report-filters label > input, .report-filters label > select, .report-filters .el-select__wrapper, .report-filters .report-button { height: 44px; min-height: 44px; } .report-filters .report-button { width: 100%; } .report-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } .report-metrics div { border-bottom: 1px solid var(--app-border); } .report-metrics div:nth-child(2n) { border-right: 0; } .report-metrics div:nth-last-child(-n + 2) { border-bottom: 0; } .report-grain, .report-tabs { display: grid; width: 100%; } .report-grain button, .report-tabs button { min-height: 44px; } .report-error { align-items: stretch; flex-direction: column; } .report-overview__metrics { grid-template-columns: minmax(0, 1fr); } }
</style>
