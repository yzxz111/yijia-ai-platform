<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Activity, CheckCircle2, ChevronDown, ChevronRight, CircleAlert, Coins, RefreshCw, Search, TrendingUp, TriangleAlert } from 'lucide-vue-next'
import type { UsageEntry, UsageStageEntry } from '../../api/gateway'
import {
  getGatewayAdminAnalytics, listAdminUsage, listQuotaLedger, listReservations, resolveAdminUserNumbers,
  type AdminLedgerEntry, type AdminUsageListParams, type GatewayAdminAnalytics, type QuotaLedgerListParams,
  type ReservationEntry, type ReservationListParams,
} from '../../api/admin'
import AppChart from '../../components/charts/AppChart.vue'
import { buildRecentTrendOption } from '../../components/charts/dashboardOptions'
import { adminDrillQuery } from '../../router/query'
import { formatDateTime } from '../../utils/dateTime'
import {
  adminChannelLabel, adminModelLabel, adminProviderLabel, adminStatusLabel, adminUpstreamModelLabel,
  usageStageLabel, userNameLabel, userNoLabel,
} from '../../utils/adminDisplay'

const route = useRoute()
const usageTabs = ['usage', 'reservations', 'ledger'] as const
const initialDrill = adminDrillQuery(route.query, usageTabs, 'usage')
const usage = ref<UsageEntry[]>([])
const reservations = ref<ReservationEntry[]>([])
const ledger = ref<AdminLedgerEntry[]>([])
const userNumbers = ref<Record<string, string>>({})
const userNames = ref<Record<string, string>>({})
const usageTotal = ref(0)
const reservationsTotal = ref(0)
const ledgerTotal = ref(0)
const activeTab = ref(initialDrill.tab)
const usageLoading = ref(true)
const reservationsLoading = ref(true)
const ledgerLoading = ref(true)
const loading = computed(() => usageLoading.value || reservationsLoading.value || ledgerLoading.value)
const errorMessage = ref('')
const pageSizeOptions = [5, 10, 20]
const expandedUsageIds = reactive(new Set<string>())
const currencyFormatters = new Map<string, Intl.NumberFormat>()
const analytics = ref<GatewayAdminAnalytics | null>(null)
const failedRecent = ref<UsageEntry[]>([])
const lastCallAt = ref('')
const lifetime = ref<{ total: number; success: number; failed: number } | null>(null)
const anomalyUsers = ref<{ key: string; userNo: string; userName: string; count: number }[]>([])
const anomalyTotal = ref(0)

const failureRate = computed(() => {
  const summary = analytics.value?.summary
  if (!summary?.todayRequests) return null
  return Math.round((summary.todayFailedRequests / summary.todayRequests) * 1000) / 10
})
const trendOption = computed(() => buildRecentTrendOption(analytics.value?.daily ?? []))
const hasTrend = computed(() => Boolean(analytics.value?.daily.some((day) => day.requestCount > 0)))

async function loadAnalytics() {
  try {
    analytics.value = await getGatewayAdminAnalytics(7)
  } catch {
    analytics.value = null
  }
}

async function loadLifetime() {
  try {
    const [all, failed] = await Promise.all([
      listAdminUsage({
        page: 0, size: 1, query: '', status: '', sort: 'createdAt', direction: 'desc',
        userId: '', modelCode: '', outcome: '', requestId: '',
      }),
      listAdminUsage({
        page: 0, size: 1, query: '', status: '', sort: 'createdAt', direction: 'desc',
        userId: '', modelCode: '', outcome: 'FAILED', requestId: '',
      }),
    ])
    lifetime.value = { total: all.total, success: all.total - failed.total, failed: failed.total }
    lastCallAt.value = all.items[0] ? formatDateTime(all.items[0].createdAt) : ''
  } catch {
    lifetime.value = null
    lastCallAt.value = ''
  }
}

async function loadFailedRecent() {
  try {
    const result = await listAdminUsage({
      page: 0, size: 6, query: '', status: '', sort: 'createdAt', direction: 'desc',
      userId: '', modelCode: '', outcome: 'FAILED', requestId: '',
    })
    failedRecent.value = result.items
  } catch {
    failedRecent.value = []
  }
}

async function loadAnomalyUsers() {
  try {
    const result = await listReservations({
      page: 0, size: 50, query: '', status: '', sort: 'createdAt', direction: 'desc',
      userId: '', requestId: '', anomalyOnly: true,
    })
    anomalyTotal.value = result.total
    const counts = new Map<string, number>()
    for (const row of result.items) counts.set(row.userId, (counts.get(row.userId) ?? 0) + 1)
    const ids = [...counts.keys()]
    await resolvePageUserNumbers(ids)
    anomalyUsers.value = ids
      .map((id) => ({ key: id, userNo: userNumbers.value[id] ?? '', userName: userNames.value[id] ?? '', count: counts.get(id) ?? 0 }))
      .sort((a, b) => b.count - a.count)
      .slice(0, 5)
  } catch {
    anomalyUsers.value = []
    anomalyTotal.value = 0
  }
}

const usageParams = reactive<AdminUsageListParams>({
  page: 0, size: 20, query: '', status: '', sort: 'createdAt', direction: 'desc',
  userId: initialDrill.userId, modelCode: initialDrill.modelCode, outcome: '', requestId: initialDrill.requestId,
})
const reservationParams = reactive<ReservationListParams>({
  page: 0, size: 20, query: '', status: '', sort: 'createdAt', direction: 'desc',
  userId: initialDrill.userId, requestId: initialDrill.requestId, anomalyOnly: false,
})
const ledgerParams = reactive<QuotaLedgerListParams>({
  page: 0, size: 20, query: '', status: '', sort: 'createdAt', direction: 'desc',
  userId: initialDrill.userId, modelCode: initialDrill.modelCode, entryType: '',
})

async function resolvePageUserNumbers(ids: string[]) {
  try {
    const identities = await resolveAdminUserNumbers(ids)
    userNumbers.value = {
      ...userNumbers.value,
      ...Object.fromEntries(identities.map((identity) => [identity.userId, identity.userNo])),
    }
    userNames.value = {
      ...userNames.value,
      ...Object.fromEntries(identities.map((identity) => [identity.userId, identity.displayName])),
    }
  } catch {
    // 身份查询不可用时，表格仍可用并显示中性占位。
  }
}

async function loadUsage(targetPage = usageParams.page) {
  usageLoading.value = true
  errorMessage.value = ''
  try {
    const result = await listAdminUsage({ ...usageParams, page: targetPage })
    usage.value = result.items
    const visibleIds = new Set(result.items.filter((row) => row.stages?.length).map((row) => row.id))
    for (const id of expandedUsageIds) {
      if (!visibleIds.has(id)) expandedUsageIds.delete(id)
    }
    usageTotal.value = result.total
    usageParams.page = result.page
  } catch {
    errorMessage.value = '调用记录加载失败，请稍后重试。'
  } finally {
    usageLoading.value = false
  }
}

function hasStages(row: UsageEntry) {
  return Boolean(row.stages?.length)
}

function toggleStages(id: string) {
  if (expandedUsageIds.has(id)) expandedUsageIds.delete(id)
  else expandedUsageIds.add(id)
}

function formatProviderCost(stage: UsageStageEntry) {
  if (stage.providerCostAmount === null || !stage.providerCostCurrency) return '-'
  try {
    let formatter = currencyFormatters.get(stage.providerCostCurrency)
    if (!formatter) {
      formatter = new Intl.NumberFormat('zh-CN', {
        style: 'currency', currency: stage.providerCostCurrency, currencyDisplay: 'narrowSymbol',
        minimumFractionDigits: 2, maximumFractionDigits: 2,
      })
      currencyFormatters.set(stage.providerCostCurrency, formatter)
    }
    return formatter.format(stage.providerCostAmount / 100)
  } catch {
    return `${stage.providerCostCurrency} ${(stage.providerCostAmount / 100).toFixed(2)}`
  }
}

async function loadReservations(targetPage = reservationParams.page) {
  reservationsLoading.value = true
  errorMessage.value = ''
  try {
    const result = await listReservations({ ...reservationParams, page: targetPage })
    reservations.value = result.items
    await resolvePageUserNumbers(result.items.map((row) => row.userId))
    reservationsTotal.value = result.total
    reservationParams.page = result.page
  } catch {
    errorMessage.value = '积分预占加载失败，请稍后重试。'
  } finally {
    reservationsLoading.value = false
  }
}

async function loadLedger(targetPage = ledgerParams.page) {
  ledgerLoading.value = true
  errorMessage.value = ''
  try {
    const result = await listQuotaLedger({ ...ledgerParams, page: targetPage })
    ledger.value = result.items
    await resolvePageUserNumbers(result.items.map((row) => row.userId))
    ledgerTotal.value = result.total
    ledgerParams.page = result.page
  } catch {
    errorMessage.value = '配额台账加载失败，请稍后重试。'
  } finally {
    ledgerLoading.value = false
  }
}

function changeUsagePageSize(size: number) {
  usageParams.size = size
  void loadUsage(0)
}

function changeReservationPageSize(size: number) {
  reservationParams.size = size
  void loadReservations(0)
}

function changeLedgerPageSize(size: number) {
  ledgerParams.size = size
  void loadLedger(0)
}

function clearUsageFilters() {
  Object.assign(usageParams, { requestId: '', modelCode: '', outcome: '', sort: 'createdAt', direction: 'desc' })
  void loadUsage(0)
}

function clearReservationFilters() {
  Object.assign(reservationParams, { requestId: '', userId: '', status: '', anomalyOnly: false, sort: 'createdAt', direction: 'desc' })
  void loadReservations(0)
}

function clearLedgerFilters() {
  Object.assign(ledgerParams, { modelCode: '', userId: '', entryType: '', sort: 'createdAt', direction: 'desc' })
  void loadLedger(0)
}

function load() {
  return Promise.all([loadUsage(), loadReservations(), loadLedger(), loadAnalytics(), loadLifetime(), loadFailedRecent(), loadAnomalyUsers()])
}

onMounted(load)
watch(() => route.query, (query) => {
  const drill = adminDrillQuery(query, usageTabs, 'usage')
  activeTab.value = drill.tab
  Object.assign(usageParams, { requestId: drill.requestId, userId: drill.userId, modelCode: drill.modelCode, page: 0 })
  Object.assign(reservationParams, { requestId: drill.requestId, userId: drill.userId, page: 0 })
  Object.assign(ledgerParams, { userId: drill.userId, modelCode: drill.modelCode, query: drill.search, page: 0 })
  if (drill.tab === 'usage') void loadUsage(0)
  if (drill.tab === 'reservations') void loadReservations(0)
  if (drill.tab === 'ledger') void loadLedger(0)
}, { deep: true })
</script>

<template>
  <section class="admin-page" aria-labelledby="admin-usage-title">
    <header class="admin-page__header"><div><h2 id="admin-usage-title">调用与额度</h2><p>全局调用、积分预占、估算使用与配额变更台账</p></div><el-button :loading="loading" @click="load"><RefreshCw :size="16" />刷新</el-button></header>
    <TopErrorMessage :message="errorMessage" />

    <p v-if="analytics && analytics.summary.todayRequests === 0 && lastCallAt" class="usage-stats__hint">今日暂无调用 · 最近一次调用 {{ lastCallAt }}</p>
    <div class="usage-stats">
      <div class="usage-stats__items">
        <div class="usage-stat">
          <span class="usage-stat__icon usage-stat__icon--total"><Activity :size="15" /></span>
          <div><span class="usage-stat__value">{{ lifetime ? lifetime.total.toLocaleString() : '—' }}</span><span class="usage-stat__label">总调用</span></div>
        </div>
        <div class="usage-stat">
          <span class="usage-stat__icon usage-stat__icon--ok"><CheckCircle2 :size="15" /></span>
          <div><span class="usage-stat__value usage-stat__value--ok">{{ lifetime ? lifetime.success.toLocaleString() : '—' }}</span><span class="usage-stat__label">成功调用</span></div>
        </div>
        <div class="usage-stat">
          <span class="usage-stat__icon usage-stat__icon--bad"><CircleAlert :size="15" /></span>
          <div><span class="usage-stat__value usage-stat__value--bad">{{ lifetime ? lifetime.failed.toLocaleString() : '—' }}</span><span class="usage-stat__label">失败调用</span></div>
        </div>
        <div class="usage-stat">
          <span class="usage-stat__icon usage-stat__icon--quota"><Coins :size="15" /></span>
          <div><span class="usage-stat__value">{{ analytics ? analytics.summary.periodTokens.toLocaleString() : '—' }}</span><span class="usage-stat__label">消耗额度 · 近 7 天（积分）</span></div>
        </div>
      </div>
      <div class="usage-stats__trend">
        <span class="usage-stats__trend-title"><TrendingUp :size="14" />调用趋势 · 近 7 天</span>
        <AppChart v-if="hasTrend" :option="trendOption" height="72px" ariaLabel="近 7 天调用趋势折线图" />
        <span v-else class="usage-stats__trend-empty">暂无调用数据</span>
      </div>
    </div>

    <div class="usage-layout">
      <div class="usage-layout__main">
    <el-tabs v-model="activeTab" class="admin-panel admin-tabs">
      <el-tab-pane label="调用记录" name="usage">
        <div class="admin-toolbar usage-toolbar">
          <el-input v-model="usageParams.requestId" data-test="usage-request-id" aria-label="调用请求 ID" clearable placeholder="请求 ID" @keyup.enter="loadUsage(0)" />
          <el-input v-model="usageParams.modelCode" data-test="usage-model-code" aria-label="调用模型代码" clearable placeholder="模型名称或代码" @keyup.enter="loadUsage(0)" />
          <el-select v-model="usageParams.outcome" data-test="usage-outcome" aria-label="调用结果" clearable placeholder="全部结果"><el-option label="成功" value="SUCCEEDED" /><el-option label="失败" value="FAILED" /></el-select>
          <el-select v-model="usageParams.sort" data-test="usage-sort" aria-label="调用排序字段"><el-option label="创建时间" value="createdAt" /><el-option label="请求 ID" value="requestId" /><el-option label="模型" value="modelCode" /><el-option label="积分" value="totalTokens" /><el-option label="耗时" value="durationMs" /><el-option label="结果" value="outcome" /></el-select>
          <el-select v-model="usageParams.direction" data-test="usage-direction" aria-label="调用排序方向"><el-option label="降序" value="desc" /><el-option label="升序" value="asc" /></el-select>
          <el-button @click="clearUsageFilters">清空</el-button>
          <el-button data-test="usage-filter" type="primary" :loading="usageLoading" @click="loadUsage(0)"><Search :size="16" />查询</el-button>
        </div>
        <el-skeleton v-if="usageLoading" :rows="5" animated />
        <div v-else class="table-scroll"><table class="admin-table usage-table"><thead><tr><th class="usage-expand-column"><span class="sr-only">阶段详情</span></th><th>请求</th><th>模型</th><th>积分</th><th>来源</th><th>耗时</th><th>结果</th><th>时间</th></tr></thead><tbody>
          <template v-for="row in usage" :key="row.id">
          <tr><td class="usage-expand-cell"><el-tooltip v-if="hasStages(row)" :content="expandedUsageIds.has(row.id) ? '收起调用阶段' : '展开调用阶段'" placement="top"><span class="stage-tooltip-anchor"><button :data-test="`usage-stage-toggle-${row.id}`" class="stage-toggle" type="button" :aria-label="expandedUsageIds.has(row.id) ? '收起调用阶段' : '展开调用阶段'" :aria-expanded="expandedUsageIds.has(row.id) ? 'true' : 'false'" :aria-controls="`usage-stages-${row.id}`" @click="toggleStages(row.id)"><ChevronDown v-if="expandedUsageIds.has(row.id)" :size="17" aria-hidden="true" /><ChevronRight v-else :size="17" aria-hidden="true" /></button></span></el-tooltip></td><td><code>{{ row.requestId }}</code></td><td><span class="usage-model-label">{{ adminModelLabel(row.modelCode) }}</span></td><td>{{ row.totalTokens.toLocaleString() }}</td><td><span class="usage-source"><span>{{ adminProviderLabel(row.providerCode) }}</span><small>{{ adminChannelLabel(row.channelCode) }}</small></span></td><td>{{ row.durationMs }} ms</td><td><div class="admin-actions"><el-tag size="small" :type="row.outcome === 'SUCCEEDED' ? 'success' : 'danger'">{{ adminStatusLabel(row.outcome) }}</el-tag><el-tag v-if="row.usageEstimated" size="small" type="warning">估算</el-tag></div></td><td>{{ formatDateTime(row.createdAt) }}</td></tr>
            <tr v-if="hasStages(row) && expandedUsageIds.has(row.id)" :id="`usage-stages-${row.id}`" :data-test="`usage-stages-${row.id}`" class="usage-stage-row"><td colspan="8"><div class="usage-stage-scroll"><table class="usage-stage-table"><thead><tr><th>序号</th><th>阶段</th><th>路由模型</th><th>来源</th><th>上游模型</th><th>积分</th><th>耗时</th><th>结果</th><th>成本</th></tr></thead><tbody><tr v-for="stage in row.stages" :key="stage.stageIndex"><td>{{ stage.stageIndex }}</td><td>{{ usageStageLabel(stage.stageType) }}</td><td><span class="usage-model-label">{{ adminModelLabel(stage.logicalModelCode) }}</span></td><td><span class="usage-source"><span>{{ adminProviderLabel(stage.providerCode) }}</span><small>{{ adminChannelLabel(stage.channelCode) }}</small></span></td><td><span class="usage-upstream-model">{{ adminUpstreamModelLabel(stage.upstreamModel) }}</span></td><td>{{ stage.totalTokens.toLocaleString() }} <span class="stage-token-detail">({{ stage.inputTokens.toLocaleString() }} / {{ stage.outputTokens.toLocaleString() }})</span></td><td>{{ stage.durationMs }} ms</td><td><el-tag size="small" :type="stage.outcome === 'SUCCEEDED' ? 'success' : stage.outcome === 'CANCELLED' ? 'info' : 'danger'">{{ adminStatusLabel(stage.outcome) }}</el-tag></td><td><span class="stage-cost">{{ formatProviderCost(stage) }}</span><el-tag v-if="stage.priceEstimated" size="small" type="warning">估算</el-tag></td></tr></tbody></table></div></td></tr>
          </template>
          <tr v-if="!usage.length"><td colspan="8" class="admin-empty">暂无调用记录</td></tr>
        </tbody></table></div>
        <div class="admin-pagination"><span>共 {{ usageTotal }} 条</span><el-pagination data-test="usage-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="usageParams.page + 1" :page-size="usageParams.size" :page-sizes="pageSizeOptions" :total="usageTotal" @size-change="changeUsagePageSize" @current-change="(value: number) => loadUsage(value - 1)" /></div>
      </el-tab-pane>
      <el-tab-pane label="积分预占" name="reservations">
        <div class="admin-toolbar usage-toolbar reservation-toolbar">
          <el-input v-model="reservationParams.requestId" data-test="reservations-request-id" aria-label="预占请求 ID" clearable placeholder="请求 ID" @keyup.enter="loadReservations(0)" />
          <el-input v-model="reservationParams.userId" data-test="reservations-user-id" aria-label="预占用户 ID" clearable placeholder="用户 ID" @keyup.enter="loadReservations(0)" />
          <el-select v-model="reservationParams.status" data-test="reservations-status" aria-label="预占状态" clearable placeholder="全部状态"><el-option label="预占" value="RESERVED" /><el-option label="已结算" value="SETTLED" /><el-option label="已释放" value="RELEASED" /><el-option label="已过期" value="EXPIRED" /></el-select>
          <el-select v-model="reservationParams.sort" data-test="reservations-sort" aria-label="预占排序字段"><el-option label="创建时间" value="createdAt" /><el-option label="请求 ID" value="requestId" /><el-option label="模型" value="modelCode" /><el-option label="状态" value="status" /><el-option label="预占积分" value="reservedTokens" /><el-option label="实际积分" value="actualTotalTokens" /><el-option label="未覆盖积分" value="uncoveredTokens" /><el-option label="过期时间" value="expiresAt" /></el-select>
          <el-select v-model="reservationParams.direction" data-test="reservations-direction" aria-label="预占排序方向"><el-option label="降序" value="desc" /><el-option label="升序" value="asc" /></el-select>
          <el-checkbox v-model="reservationParams.anomalyOnly" data-test="reservation-anomaly-only" @change="loadReservations(0)">仅显示未覆盖异常</el-checkbox>
          <el-button @click="clearReservationFilters">清空</el-button>
          <el-button data-test="reservations-filter" type="primary" :loading="reservationsLoading" @click="loadReservations(0)"><Search :size="16" />查询</el-button>
        </div>
        <el-skeleton v-if="reservationsLoading" :rows="5" animated />
        <div v-else class="table-scroll"><table class="admin-table"><thead><tr><th>请求</th><th>用户编号</th><th>用户名</th><th>模型</th><th>预占</th><th>实际</th><th>覆盖</th><th>状态</th></tr></thead><tbody>
          <tr v-for="reservation in reservations" :key="reservation.id"><td><code>{{ reservation.requestId }}</code></td><td><code>{{ userNoLabel(userNumbers[reservation.userId]) }}</code></td><td>{{ userNameLabel(userNames[reservation.userId]) }}</td><td>{{ reservation.modelCode }}</td><td>{{ reservation.reservedTokens }}</td><td>{{ reservation.actualTotalTokens ?? '-' }} <el-tag v-if="reservation.usageEstimated" size="small" type="warning">估算</el-tag></td><td><el-tag size="small" :type="reservation.uncoveredTokens ? 'warning' : 'success'">未覆盖 {{ reservation.uncoveredTokens }}</el-tag></td><td>{{ adminStatusLabel(reservation.status) }}</td></tr>
          <tr v-if="!reservations.length"><td colspan="8" class="admin-empty">暂无积分预占</td></tr>
        </tbody></table></div>
        <div class="admin-pagination"><span>共 {{ reservationsTotal }} 条</span><el-pagination data-test="reservations-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="reservationParams.page + 1" :page-size="reservationParams.size" :page-sizes="pageSizeOptions" :total="reservationsTotal" @size-change="changeReservationPageSize" @current-change="(value: number) => loadReservations(value - 1)" /></div>
      </el-tab-pane>
      <el-tab-pane label="配额台账" name="ledger">
        <div class="admin-toolbar usage-toolbar">
          <el-input v-model="ledgerParams.modelCode" data-test="ledger-model-code" aria-label="台账模型代码" clearable placeholder="模型代码" @keyup.enter="loadLedger(0)" />
          <el-input v-model="ledgerParams.userId" data-test="ledger-user-id" aria-label="台账用户 ID" clearable placeholder="用户 ID" @keyup.enter="loadLedger(0)" />
          <el-select v-model="ledgerParams.entryType" data-test="ledger-entry-type" aria-label="台账类型" clearable placeholder="全部类型"><el-option label="预占" value="RESERVE" /><el-option label="结算" value="SETTLE" /><el-option label="释放" value="RELEASE" /><el-option label="调整" value="ADJUST" /></el-select>
          <el-select v-model="ledgerParams.sort" data-test="ledger-sort" aria-label="台账排序字段"><el-option label="创建时间" value="createdAt" /><el-option label="请求 ID" value="requestId" /><el-option label="模型" value="modelCode" /><el-option label="类型" value="entryType" /><el-option label="积分变更" value="totalTokenDelta" /><el-option label="余额" value="balanceAfter" /></el-select>
          <el-select v-model="ledgerParams.direction" data-test="ledger-direction" aria-label="台账排序方向"><el-option label="降序" value="desc" /><el-option label="升序" value="asc" /></el-select>
          <el-button @click="clearLedgerFilters">清空</el-button>
          <el-button data-test="ledger-filter" type="primary" :loading="ledgerLoading" @click="loadLedger(0)"><Search :size="16" />查询</el-button>
        </div>
        <el-skeleton v-if="ledgerLoading" :rows="5" animated />
        <div v-else class="table-scroll"><table class="admin-table"><thead><tr><th>请求</th><th>用户编号</th><th>用户名</th><th>模型</th><th>类型</th><th>变更（积分）</th><th>余额（积分）</th><th>时间</th></tr></thead><tbody>
          <tr v-for="entry in ledger" :key="entry.id"><td><code>{{ entry.requestId }}</code></td><td><code>{{ userNoLabel(userNumbers[entry.userId]) }}</code></td><td>{{ userNameLabel(userNames[entry.userId]) }}</td><td>{{ entry.modelCode }}</td><td><el-tag size="small" type="info">{{ adminStatusLabel(entry.entryType) }}</el-tag></td><td>{{ entry.totalTokenDelta.toLocaleString() }}</td><td>{{ entry.balanceAfter.toLocaleString() }}</td><td>{{ formatDateTime(entry.createdAt) }}</td></tr>
          <tr v-if="!ledger.length"><td colspan="8" class="admin-empty">暂无额度台账</td></tr>
        </tbody></table></div>
        <div class="admin-pagination"><span>共 {{ ledgerTotal }} 条</span><el-pagination data-test="ledger-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="ledgerParams.page + 1" :page-size="ledgerParams.size" :page-sizes="pageSizeOptions" :total="ledgerTotal" @size-change="changeLedgerPageSize" @current-change="(value: number) => loadLedger(value - 1)" /></div>
      </el-tab-pane>
    </el-tabs>
      </div>

      <aside class="usage-aside">
        <div class="usage-aside__panel">
          <h3 class="usage-aside__title"><TriangleAlert :size="15" />失败率统计</h3>
          <div class="usage-fail-rate">
            <span class="usage-fail-rate__value" :class="{ 'usage-fail-rate__value--high': failureRate !== null && failureRate >= 5 }">{{ failureRate === null ? '—' : `${failureRate}%` }}</span>
            <span class="usage-fail-rate__sub">今日失败 / 今日总调用</span>
          </div>
          <div class="usage-fail-rate__row"><span>今日失败</span><b class="usage-fail-rate__bad">{{ analytics ? analytics.summary.todayFailedRequests.toLocaleString() : '—' }}</b></div>
          <div class="usage-fail-rate__row"><span>今日成功</span><b class="usage-fail-rate__ok">{{ analytics ? analytics.summary.todaySuccessfulRequests.toLocaleString() : '—' }}</b></div>
          <div class="usage-fail-rate__row"><span>预占异常单</span><b>{{ anomalyTotal }}</b></div>
        </div>

        <div class="usage-aside__panel">
          <h3 class="usage-aside__title"><CircleAlert :size="15" />异常调用 Top 用户</h3>
          <p class="usage-aside__sub">按未覆盖异常的预占单统计</p>
          <ol v-if="anomalyUsers.length" class="usage-top-users">
            <li v-for="(item, index) in anomalyUsers" :key="item.key" class="usage-top-users__item">
              <span class="usage-top-users__rank" :class="{ 'usage-top-users__rank--top': index < 3 }">{{ index + 1 }}</span>
              <div class="usage-top-users__info">
                <span class="usage-top-users__name" :title="item.userName">{{ userNameLabel(item.userName) }}</span>
                <code class="usage-top-users__no">{{ userNoLabel(item.userNo) }}</code>
              </div>
              <span class="usage-top-users__count">{{ item.count }} 单</span>
            </li>
          </ol>
          <p v-else class="usage-aside__empty">暂无异常预占记录</p>
        </div>
      </aside>
    </div>

    <div class="usage-failed-panel">
      <h3 class="usage-aside__title"><CircleAlert :size="15" />最近失败调用</h3>
      <div v-if="failedRecent.length" class="table-scroll">
        <table class="admin-table usage-failed-table">
          <thead><tr><th>请求</th><th>模型</th><th>来源</th><th>耗时</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="row in failedRecent" :key="row.id">
              <td><code>{{ row.requestId }}</code></td>
              <td><span class="usage-model-label">{{ adminModelLabel(row.modelCode) }}</span></td>
              <td><span class="usage-source"><span>{{ adminProviderLabel(row.providerCode) }}</span><small>{{ adminChannelLabel(row.channelCode) }}</small></span></td>
              <td>{{ row.durationMs }} ms</td>
              <td>{{ formatDateTime(row.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="usage-aside__empty">近期没有失败调用</p>
    </div>
  </section>
</template>

<style scoped>
.usage-expand-column, .usage-expand-cell { box-sizing: border-box; width: 52px; padding-inline: 6px; text-align: center; }
.stage-tooltip-anchor { display: inline-flex; }
.stage-toggle { display: inline-grid; width: 40px; height: 40px; padding: 0; place-items: center; border: 0; border-radius: 4px; color: #475569; background: transparent; cursor: pointer; }
.stage-toggle:hover { color: var(--el-color-primary); background: var(--el-fill-color-light); }
.stage-toggle:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.usage-stage-row > td { padding: 0 16px 12px 58px; background: var(--el-fill-color-lighter); }
.usage-stage-scroll { overflow-x: auto; }
.usage-stage-table { width: 100%; min-width: 960px; border-collapse: collapse; font-size: 12px; }
.usage-stage-table th, .usage-stage-table td { padding: 8px 10px; border-bottom: 1px solid var(--el-border-color-lighter); text-align: left; white-space: nowrap; }
.usage-stage-table th { color: var(--el-text-color-secondary); font-weight: 600; }
.usage-stage-table tbody tr:last-child td { border-bottom: 0; }
.stage-token-detail { color: var(--el-text-color-secondary); }
.stage-cost { margin-right: 6px; font-variant-numeric: tabular-nums; }
.usage-model-label { display: inline-block; max-width: 220px; line-height: 1.4; }
.usage-source { display: inline-flex; flex-direction: column; gap: 2px; line-height: 1.35; }
.usage-source small, .usage-upstream-model { color: var(--el-text-color-secondary); font-size: 12px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 640px) {
  .usage-stage-row > td { padding-left: 8px; }
}
.admin-tabs { padding: 0 14px 14px; }
.admin-toolbar { padding-top: 2px; }
.usage-toolbar { grid-template-columns: repeat(2, minmax(170px, 1fr)) repeat(3, minmax(130px, .75fr)) auto; align-items: end; }
.reservation-toolbar { grid-template-columns: repeat(2, minmax(170px, 1fr)) repeat(3, minmax(130px, .75fr)) auto auto; }
.usage-toolbar :deep(.el-input__wrapper), .usage-toolbar :deep(.el-select__wrapper), .usage-toolbar :deep(.el-button) { min-height: 40px; }
@media (max-width: 720px) { .usage-toolbar, .reservation-toolbar { grid-template-columns: 1fr; } .usage-toolbar :deep(.el-input__wrapper), .usage-toolbar :deep(.el-select__wrapper), .usage-toolbar :deep(.el-button) { min-height: 44px; } }

/* ===== 顶部统计条（轻量，不做大卡片） ===== */
.usage-stats__hint { margin: 0 0 8px; font-size: 12px; color: var(--app-muted-strong); }
.usage-stats { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 14px; margin-bottom: 14px; }
.usage-stats__items { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.usage-stat { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-surface); box-shadow: var(--app-shadow-xs); }
.usage-stat__icon { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; min-width: 32px; border-radius: 8px; }
.usage-stat__icon--total { color: var(--app-primary); background: var(--app-primary-soft); }
.usage-stat__icon--ok { color: var(--app-success); background: var(--app-success-soft); }
.usage-stat__icon--bad { color: var(--app-danger); background: var(--app-danger-soft); }
.usage-stat__icon--quota { color: var(--app-accent); background: var(--app-accent-soft); }
.usage-stat__value { display: block; font-size: 18px; font-weight: 700; line-height: 1.2; color: var(--app-fg); font-variant-numeric: tabular-nums; }
.usage-stat__value--ok { color: var(--app-success); }
.usage-stat__value--bad { color: var(--app-danger); }
.usage-stat__label { display: block; font-size: 12px; color: var(--app-muted-strong); white-space: nowrap; }
.usage-stats__trend { display: flex; flex-direction: column; gap: 4px; padding: 10px 14px 6px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-surface); box-shadow: var(--app-shadow-xs); }
.usage-stats__trend-title { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; color: var(--app-muted-strong); }
.usage-stats__trend-empty { padding: 24px 0; font-size: 12px; color: var(--app-muted); text-align: center; }

/* ===== 主区 + 侧栏 ===== */
.usage-layout { display: grid; grid-template-columns: minmax(0, 1fr) 240px; gap: 14px; align-items: start; }
.usage-layout__main { min-width: 0; }
.usage-aside { display: grid; gap: 14px; position: sticky; top: 12px; }
.usage-aside__panel { padding: 14px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-surface); box-shadow: var(--app-shadow-xs); }
.usage-aside__title { display: flex; align-items: center; gap: 7px; margin: 0; font-size: 14px; font-weight: 700; color: var(--app-fg); }
.usage-aside__sub { margin: 6px 0 0; font-size: 12px; color: var(--app-muted); }
.usage-aside__empty { margin: 14px 0 4px; font-size: 12px; color: var(--app-muted); text-align: center; }

.usage-fail-rate { display: flex; align-items: baseline; gap: 8px; margin: 10px 0 8px; }
.usage-fail-rate__value { font-size: 24px; font-weight: 700; color: var(--app-fg); font-variant-numeric: tabular-nums; }
.usage-fail-rate__value--high { color: var(--app-danger); }
.usage-fail-rate__sub { font-size: 12px; color: var(--app-muted); }
.usage-fail-rate__row { display: flex; justify-content: space-between; padding: 6px 0; border-top: 1px dashed var(--app-border); font-size: 12px; color: var(--app-muted-strong); }
.usage-fail-rate__row b { font-variant-numeric: tabular-nums; color: var(--app-fg); }
.usage-fail-rate__row b.usage-fail-rate__bad { color: var(--app-danger); }
.usage-fail-rate__row b.usage-fail-rate__ok { color: var(--app-success); }

.usage-top-users { list-style: none; margin: 10px 0 0; padding: 0; display: grid; gap: 8px; }
.usage-top-users__item { display: flex; align-items: center; gap: 8px; }
.usage-top-users__rank { display: inline-flex; align-items: center; justify-content: center; width: 20px; height: 20px; min-width: 20px; border-radius: 6px; font-size: 11px; font-weight: 700; color: var(--app-muted-strong); background: var(--app-surface-muted); }
.usage-top-users__rank--top { color: var(--app-danger); background: var(--app-danger-soft); }
.usage-top-users__info { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.usage-top-users__name { font-size: 13px; color: var(--app-fg); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.usage-top-users__no { font-size: 11px; color: var(--app-muted); }
.usage-top-users__count { font-size: 12px; font-weight: 600; color: var(--app-danger); font-variant-numeric: tabular-nums; white-space: nowrap; }

/* ===== 底部最近失败调用 ===== */
.usage-failed-panel { margin-top: 14px; padding: 14px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-surface); box-shadow: var(--app-shadow-xs); }
.usage-failed-panel :deep(.admin-table) { margin-top: 10px; }
.usage-failed-table tbody tr { background: color-mix(in srgb, var(--app-danger-soft) 22%, transparent); }
.usage-failed-table tbody tr:hover { background: color-mix(in srgb, var(--app-danger-soft) 38%, transparent); }

@media (max-width: 1100px) {
  .usage-stats { grid-template-columns: 1fr; }
  .usage-stats__trend { min-height: 96px; }
  .usage-layout { grid-template-columns: 1fr; }
  .usage-aside { position: static; grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 720px) {
  .usage-stats__items { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .usage-aside { grid-template-columns: 1fr; }
}
</style>
