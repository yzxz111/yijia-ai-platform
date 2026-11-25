<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  Clock,
  Coins,
  Database,
  Network,
  PackageCheck,
  RefreshCw,
  ServerCog,
  Timer,
  UserPlus,
  Users,
  WalletCards,
} from 'lucide-vue-next'

import {
  getAdminOverview,
  getGatewayAdminAnalytics,
  getGatewayHealth,
  getOperatingAnalytics,
  listAdminUsage,
  type AdminOverview,
  type GatewayAdminAnalytics,
  type GatewayHealth,
  type OperatingAnalytics,
} from '../../api/admin'
import type { UsageEntry } from '../../api/gateway'
import AppChart from '../../components/charts/AppChart.vue'
import {
  buildOperatingTrendOption,
  buildPackageDistributionOption,
  buildRecentTrendOption,
  buildUsageTrendOption,
} from '../../components/charts/dashboardOptions'
import { formatDateTime } from '../../utils/dateTime'
import { adminStatusLabel } from '../../utils/adminDisplay'
import { userPlanName } from '../../utils/planCopy'

type Source = 'failures' | 'gatewayAnalytics' | 'health' | 'operating' | 'overview'

const sourceMessages: Record<Source, string> = {
  failures: '最近异常调用记录加载失败，请重试。',
  gatewayAnalytics: '网关分析数据加载失败，请重试。',
  health: '服务健康探测不可用，未验证组件显示为“未检测”。',
  operating: '经营数据加载失败，请重试。',
  overview: '平台运营明细加载失败，请重试。',
}

const overview = ref<AdminOverview | null>(null)
const gatewayHealth = ref<GatewayHealth | null>(null)
const operating = ref<OperatingAnalytics | null>(null)
const gatewayAnalytics = ref<GatewayAdminAnalytics | null>(null)
const recentFailures = ref<UsageEntry[]>([])
const platformVerified = ref(false)
const gatewayVerified = ref(false)
const selectedDays = ref<7 | 30>(30)
const updatedAt = ref<Date | null>(null)
const sourceErrors = ref<Partial<Record<Source, string>>>({})
const loadingSources = ref<Record<Source, boolean>>({
  failures: false,
  gatewayAnalytics: false,
  health: false,
  operating: false,
  overview: false,
})
const number = new Intl.NumberFormat('zh-CN')
const money = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' })

const loading = computed(() => Object.values(loadingSources.value).some(Boolean))
const visibleGatewayAnalytics = computed(() => (
  gatewayAnalytics.value?.range.days === selectedDays.value ? gatewayAnalytics.value : null
))
const activePackageCount = computed(() => visibleGatewayAnalytics.value?.packages.reduce(
  (total, item) => total + item.activeCount,
  0,
) ?? null)
const systems = computed(() => [
  { key: 'platform', name: '平台服务', status: platformVerified.value ? 'UP' : 'UNKNOWN' },
  { key: 'gateway', name: '网关服务', status: gatewayVerified.value ? gatewayHealth.value?.status || 'UNKNOWN' : 'UNKNOWN' },
  { key: 'postgresql', name: 'PostgreSQL', status: gatewayVerified.value ? gatewayHealth.value?.database || 'UNKNOWN' : 'UNKNOWN' },
  { key: 'redis', name: 'Redis', status: 'UNKNOWN' },
  { key: 'minio', name: 'MinIO', status: 'UNKNOWN' },
  { key: 'wiremock', name: 'WireMock', status: 'UNKNOWN' },
])
const primarySystems = computed(() => systems.value.slice(0, 3))
const operatingOption = computed(() => buildOperatingTrendOption(operating.value?.monthly ?? []))
const packageOption = computed(() => buildPackageDistributionOption(visibleGatewayAnalytics.value?.packages ?? []))
const usageOption = computed(() => buildUsageTrendOption(visibleGatewayAnalytics.value?.daily ?? []))
const hasOperatingFacts = computed(() => operating.value?.monthly.some(
  (item) => item.netAmount !== 0 || item.newUsers !== 0,
) ?? false)
const hasUsageFacts = computed(() => visibleGatewayAnalytics.value?.daily.some(
  (item) => item.requestCount > 0 || item.totalTokens > 0,
) ?? false)
const todayRequestTitle = computed(() => {
  const summary = visibleGatewayAnalytics.value?.summary
  return summary ? `成功 ${number.format(summary.todaySuccessfulRequests)}，失败 ${number.format(summary.todayFailedRequests)}` : undefined
})

// —— 二级指标：成功率 / P95 响应时长 / 本月新增用户 / 今日积分 ——
const successRate = computed(() => {
  const summary = visibleGatewayAnalytics.value?.summary
  if (!summary || summary.todayRequests === 0) return null
  return Math.round((summary.todaySuccessfulRequests / summary.todayRequests) * 1000) / 10
})
const p95LatencyMs = computed(() => visibleGatewayAnalytics.value?.summary.platformAddedP95Ms ?? null)
const currentMonthNewUsers = computed(() => {
  const monthly = operating.value?.monthly ?? []
  return monthly.length ? monthly[monthly.length - 1].newUsers : null
})
const todayTokens = computed(() => visibleGatewayAnalytics.value?.summary.todayTokens ?? null)

// —— 近 7 天调用趋势迷你图 ——
const recentDaily = computed(() => {
  const daily = visibleGatewayAnalytics.value?.daily ?? []
  return daily.slice(-7)
})
const recentTrendOption = computed(() => buildRecentTrendOption(recentDaily.value))
const recentTrendTotal = computed(() => recentDaily.value.reduce((sum, item) => sum + item.requestCount, 0))
const recentTrendDelta = computed(() => {
  const daily = recentDaily.value
  if (daily.length < 2) return null
  const last = daily[daily.length - 1].requestCount
  const prev = daily[daily.length - 2].requestCount
  if (prev === 0) return null
  return Math.round(((last - prev) / prev) * 100)
})

// 主状态带已展示网关与数据库；下方仅列其余组件，避免重复状态。
const componentSystems = computed(() => {
  const health = gatewayVerified.value ? gatewayHealth.value : null
  const outbox = overview.value?.outboxAttention
  return [
    {
      key: 'cache',
      name: '缓存',
      detail: 'Redis',
      status: 'UNKNOWN',
    },
    {
      key: 'mq',
      name: '消息队列',
      detail: outbox != null ? `待处理 ${number.format(outbox)}` : '未检测',
      status: outbox != null ? (outbox === 0 ? 'UP' : 'DEGRADED') : 'UNKNOWN',
    },
    {
      key: 'scheduler',
      name: '定时任务',
      detail: health ? `待回收 ${number.format(health.expiredReservations)}` : '未检测',
      status: health ? (health.expiredReservations === 0 ? 'UP' : 'DEGRADED') : 'UNKNOWN',
    },
  ]
})

function componentStatusClass(status: string) {
  if (['UP', 'HEALTHY'].includes(status)) return 'is-success'
  if (['DOWN', 'UNHEALTHY'].includes(status)) return 'is-danger'
  return ''
}

function statusType(status: string) {
  if (['UP', 'HEALTHY'].includes(status)) return 'success'
  if (['DOWN', 'UNHEALTHY'].includes(status)) return 'danger'
  return 'info'
}

function displayNumber(value: number | null | undefined) {
  return value == null ? '--' : number.format(value)
}

function displayMoney(value: number | null | undefined) {
  return value == null ? '--' : money.format(value / 100)
}

function displayLatency(value: number | null | undefined) {
  if (value == null) return '--'
  return value >= 1000 ? `${(value / 1000).toFixed(2)}s` : `${Math.round(value)}ms`
}

function setLoading(source: Source, value: boolean) {
  loadingSources.value = { ...loadingSources.value, [source]: value }
}

function clearSourceError(source: Source) {
  if (!sourceErrors.value[source]) return
  const next = { ...sourceErrors.value }
  delete next[source]
  sourceErrors.value = next
}

async function attemptSource<T>(
  source: Source,
  task: () => Promise<T>,
  commit: (value: T) => void,
) {
  setLoading(source, true)
  try {
    commit(await task())
    clearSourceError(source)
  } catch (error) {
    sourceErrors.value = { ...sourceErrors.value, [source]: sourceMessages[source] }
    throw error
  } finally {
    setLoading(source, false)
  }
}

function loadOverview() {
  platformVerified.value = false
  overview.value = null
  return attemptSource('overview', getAdminOverview, (value) => {
    overview.value = value
    platformVerified.value = true
  })
}

function loadHealth() {
  gatewayVerified.value = false
  gatewayHealth.value = null
  return attemptSource('health', getGatewayHealth, (value) => {
    gatewayHealth.value = value
    gatewayVerified.value = true
  })
}

function loadOperating() {
  operating.value = null
  return attemptSource('operating', () => getOperatingAnalytics(6), (value) => {
    operating.value = value
  })
}

function loadGatewayAnalytics(days: 7 | 30) {
  gatewayAnalytics.value = null
  return attemptSource('gatewayAnalytics', () => getGatewayAdminAnalytics(days), (value) => {
    gatewayAnalytics.value = value
  })
}

function loadFailures() {
  recentFailures.value = []
  return attemptSource('failures', () => listAdminUsage({
    page: 1,
    size: 5,
    query: '',
    status: '',
    sort: 'createdAt',
    direction: 'desc',
    userId: '',
    modelCode: '',
    outcome: 'FAILED',
    requestId: '',
  }), (value) => {
    recentFailures.value = value.items
  })
}

async function load() {
  await Promise.allSettled([
    loadOverview(),
    loadHealth(),
    loadOperating(),
    loadGatewayAnalytics(selectedDays.value),
    loadFailures(),
  ])
  updatedAt.value = new Date()
}

async function selectRange(days: 7 | 30) {
  if (days === selectedDays.value || loadingSources.value.gatewayAnalytics) return
  selectedDays.value = days
  await Promise.allSettled([loadGatewayAnalytics(days)])
  updatedAt.value = new Date()
}

async function retryOperating() {
  await Promise.allSettled([loadOperating()])
  updatedAt.value = new Date()
}

async function retryGatewayAnalytics() {
  await Promise.allSettled([loadGatewayAnalytics(selectedDays.value)])
  updatedAt.value = new Date()
}

async function retryOverview() {
  await Promise.allSettled([loadOverview()])
  updatedAt.value = new Date()
}

async function retryHealth() {
  await Promise.allSettled([loadHealth()])
  updatedAt.value = new Date()
}

async function retryFailures() {
  await Promise.allSettled([loadFailures()])
  updatedAt.value = new Date()
}

onMounted(load)
</script>

<template>
  <section class="admin-page admin-dashboard" aria-labelledby="admin-overview-title">
    <TopErrorMessage :message="sourceErrors.operating" />
    <TopErrorMessage :message="sourceErrors.gatewayAnalytics" />
    <TopErrorMessage :message="sourceErrors.health" />
    <TopErrorMessage :message="sourceErrors.overview" />
    <TopErrorMessage :message="sourceErrors.failures" />
    <header class="admin-page__header">
      <div>
        <h2 id="admin-overview-title">系统数据概览</h2>
        <p>更新时间：{{ updatedAt ? formatDateTime(updatedAt) : '尚未更新' }}</p>
      </div>
      <el-button :loading="loading" aria-label="刷新系统数据概览" @click="load">
        <RefreshCw :size="16" aria-hidden="true" />刷新
      </el-button>
    </header>

    <section class="admin-health-band" aria-label="已验证服务状态">
      <div v-for="system in primarySystems" :key="system.key" class="admin-health-band__item">
        <span class="admin-health-band__indicator" :class="statusType(system.status)" aria-hidden="true" />
        <div><strong>{{ system.name }}</strong><span>{{ adminStatusLabel(system.status) }}</span></div>
      </div>
    </section>

    <div class="admin-metrics" aria-label="运营关键指标">
      <article data-test="admin-registered-users" class="admin-metric admin-dashboard-metric">
        <span class="admin-dashboard-metric__icon"><Users :size="18" aria-hidden="true" /></span>
        <span>注册用户</span>
        <strong>{{ displayNumber(operating?.summary.registeredUsers) }}</strong>
        <small v-if="sourceErrors.operating">数据不可用</small>
      </article>
      <article data-test="admin-active-packages" class="admin-metric admin-dashboard-metric">
        <span class="admin-dashboard-metric__icon"><PackageCheck :size="18" aria-hidden="true" /></span>
        <span>活动套餐</span>
        <strong>{{ displayNumber(activePackageCount) }}</strong>
        <small v-if="sourceErrors.gatewayAnalytics">数据不可用</small>
      </article>
      <article data-test="admin-net-receipts" class="admin-metric admin-dashboard-metric">
        <span class="admin-dashboard-metric__icon"><WalletCards :size="18" aria-hidden="true" /></span>
        <span>本月净收</span>
        <strong>{{ displayMoney(operating?.summary.currentMonthNetAmount) }}</strong>
        <small v-if="operating">收款 {{ displayMoney(operating.summary.currentMonthCollectedAmount) }} · 退款 {{ displayMoney(operating.summary.currentMonthRefundedAmount) }}</small>
      </article>
      <article
        data-test="admin-today-requests"
        class="admin-metric admin-dashboard-metric"
        :title="todayRequestTitle"
      >
        <span class="admin-dashboard-metric__icon"><Activity :size="18" aria-hidden="true" /></span>
        <span>今日 API 调用</span>
        <strong>{{ displayNumber(visibleGatewayAnalytics?.summary.todayRequests) }}</strong>
        <small v-if="visibleGatewayAnalytics">成功 {{ number.format(visibleGatewayAnalytics.summary.todaySuccessfulRequests) }} · 失败 {{ number.format(visibleGatewayAnalytics.summary.todayFailedRequests) }}</small>
      </article>
    </div>

    <div class="admin-metrics admin-metrics--secondary" aria-label="调用质量与增长指标">
      <article data-test="admin-success-rate" class="admin-metric admin-dashboard-metric">
        <span class="admin-dashboard-metric__icon"><CheckCircle2 :size="18" aria-hidden="true" /></span>
        <span>接口成功率</span>
        <strong>{{ successRate == null ? '--' : `${successRate}%` }}</strong>
        <small v-if="visibleGatewayAnalytics">今日成功 {{ number.format(visibleGatewayAnalytics.summary.todaySuccessfulRequests) }} 次</small>
      </article>
      <article data-test="admin-p95-latency" class="admin-metric admin-dashboard-metric">
        <span class="admin-dashboard-metric__icon"><Timer :size="18" aria-hidden="true" /></span>
        <span>P95 响应时长</span>
        <strong>{{ displayLatency(p95LatencyMs) }}</strong>
        <small>平台新增耗时 · 今日</small>
      </article>
      <article data-test="admin-new-users" class="admin-metric admin-dashboard-metric">
        <span class="admin-dashboard-metric__icon"><UserPlus :size="18" aria-hidden="true" /></span>
        <span>本月新增用户</span>
        <strong>{{ displayNumber(currentMonthNewUsers) }}</strong>
        <small v-if="operating">累计 {{ displayNumber(operating.summary.registeredUsers) }} 注册</small>
      </article>
      <article data-test="admin-today-tokens" class="admin-metric admin-dashboard-metric">
        <span class="admin-dashboard-metric__icon"><Coins :size="18" aria-hidden="true" /></span>
        <span>今日积分消耗</span>
        <strong>{{ displayNumber(todayTokens) }}</strong>
        <small v-if="visibleGatewayAnalytics">周期 {{ number.format(visibleGatewayAnalytics.summary.periodTokens) }}</small>
      </article>
    </div>

    <section class="admin-dashboard-grid" aria-label="经营与套餐分析">
      <article class="admin-panel admin-chart-panel" aria-labelledby="admin-operating-title">
        <div class="admin-panel__header"><div><h3 id="admin-operating-title">月度净收与新增用户</h3><p>近 6 个上海自然月</p></div></div>
        <div v-if="sourceErrors.operating" data-test="admin-operating-error" class="admin-dashboard-state">
          <button data-test="admin-operating-retry" type="button" @click="retryOperating"><RefreshCw :size="15" aria-hidden="true" />重试</button>
        </div>
        <div v-else-if="!operating" class="admin-dashboard-state" aria-busy="true">数据加载中</div>
        <div v-else class="admin-dashboard-chart-shell">
          <AppChart
            data-test="admin-dashboard-chart"
            :option="operatingOption"
            height="var(--admin-dashboard-chart-height)"
            ariaLabel="近六个月净收与新增用户趋势"
          />
          <div v-if="!hasOperatingFacts" class="admin-dashboard-chart-empty">当前周期暂无经营变化</div>
        </div>
      </article>

      <article class="admin-panel admin-chart-panel" aria-labelledby="admin-packages-title">
        <div class="admin-panel__header"><div><h3 id="admin-packages-title">活动套餐分布</h3><p>当前生效中的套餐</p></div></div>
        <div v-if="sourceErrors.gatewayAnalytics" class="admin-dashboard-state">
          <button type="button" @click="retryGatewayAnalytics"><RefreshCw :size="15" aria-hidden="true" />重试</button>
        </div>
        <div v-else-if="!visibleGatewayAnalytics" class="admin-dashboard-state" aria-busy="true">数据加载中</div>
        <template v-else-if="visibleGatewayAnalytics.packages.length">
          <AppChart
            data-test="admin-dashboard-chart"
            :option="packageOption"
            height="var(--admin-dashboard-chart-height)"
            ariaLabel="活动套餐数量分布"
          />
          <ul class="admin-package-list" aria-label="活动套餐明细">
            <li v-for="item in visibleGatewayAnalytics.packages" :key="item.packageCode">
              <strong>{{ userPlanName(item.packageCode, item.packageName) }}</strong>
              <span>{{ number.format(item.activeCount) }} 个</span>
            </li>
          </ul>
        </template>
        <div v-else class="admin-dashboard-state">暂无活动套餐</div>
      </article>
    </section>

    <section class="admin-dashboard-grid" aria-label="调用趋势分析">
      <article class="admin-panel admin-chart-panel admin-usage-chart" aria-labelledby="admin-usage-title">
        <div class="admin-panel__header">
          <div><h3 id="admin-usage-title">API 调用与积分趋势</h3><p>{{ selectedDays }} 天 · Asia/Shanghai</p></div>
          <div class="admin-range-control" role="group" aria-label="网关分析周期">
            <button data-test="admin-range-7" type="button" :aria-pressed="selectedDays === 7" :disabled="loadingSources.gatewayAnalytics" @click="selectRange(7)">7 天</button>
            <button data-test="admin-range-30" type="button" :aria-pressed="selectedDays === 30" :disabled="loadingSources.gatewayAnalytics" @click="selectRange(30)">30 天</button>
          </div>
        </div>
        <div v-if="sourceErrors.gatewayAnalytics" class="admin-dashboard-state">
          <button type="button" @click="retryGatewayAnalytics"><RefreshCw :size="15" aria-hidden="true" />重试</button>
        </div>
        <div v-else-if="!visibleGatewayAnalytics" class="admin-dashboard-state" aria-busy="true">数据加载中</div>
        <div v-else class="admin-dashboard-chart-shell">
          <AppChart
            data-test="admin-dashboard-chart"
            :option="usageOption"
            height="var(--admin-dashboard-chart-height)"
            :ariaLabel="`${selectedDays} 天 API 调用与积分趋势`"
          />
          <div v-if="!hasUsageFacts" class="admin-dashboard-chart-empty">当前周期暂无真实调用</div>
        </div>
      </article>

      <article class="admin-panel admin-chart-panel admin-mini-trend" aria-labelledby="admin-mini-trend-title">
        <div class="admin-panel__header"><div><h3 id="admin-mini-trend-title">近 7 天调用概览</h3><p>请求总数<span v-if="recentTrendDelta !== null"> · 环比昨日</span></p></div></div>
        <div v-if="sourceErrors.gatewayAnalytics" class="admin-dashboard-state">
          <button type="button" @click="retryGatewayAnalytics"><RefreshCw :size="15" aria-hidden="true" />重试</button>
        </div>
        <div v-else-if="!visibleGatewayAnalytics" class="admin-dashboard-state" aria-busy="true">数据加载中</div>
        <template v-else>
          <div class="admin-mini-trend__summary">
            <strong>{{ number.format(recentTrendTotal) }}</strong>
            <span>次调用</span>
            <span
              v-if="recentTrendDelta != null"
              class="admin-mini-trend__delta"
              :class="recentTrendDelta > 0 ? 'is-up' : recentTrendDelta < 0 ? 'is-down' : 'is-flat'"
            >{{ recentTrendDelta > 0 ? `↑ ${recentTrendDelta}%` : recentTrendDelta < 0 ? `↓ ${Math.abs(recentTrendDelta)}%` : '持平' }}</span>
          </div>
          <div class="admin-mini-trend__chart">
            <AppChart
              :option="recentTrendOption"
              height="96px"
              ariaLabel="近 7 天 API 调用量趋势"
            />
          </div>
        </template>
      </article>
    </section>

    <section class="admin-dashboard-tail" aria-label="系统与异常监控">
      <article class="admin-panel admin-system-panel" aria-labelledby="admin-system-title">
        <div class="admin-panel__header"><div><h3 id="admin-system-title">系统组件状态</h3><p>“未检测”表示当前没有可验证的真实探测结果</p></div></div>
        <div v-if="sourceErrors.health" class="admin-dashboard-state">
          <button type="button" @click="retryHealth"><RefreshCw :size="15" aria-hidden="true" />重试</button>
        </div>
        <div v-else class="admin-component-grid">
          <div
            v-for="system in componentSystems"
            :key="system.key"
            :data-test="`system-${system.key}`"
            class="admin-component-item"
          >
            <span class="admin-component-item__icon">
              <Database v-if="system.key === 'database'" :size="16" aria-hidden="true" />
              <ServerCog v-else-if="system.key === 'cache'" :size="16" aria-hidden="true" />
              <Network v-else-if="system.key === 'mq'" :size="16" aria-hidden="true" />
              <Network v-else-if="system.key === 'gateway'" :size="16" aria-hidden="true" />
              <Clock v-else :size="16" aria-hidden="true" />
            </span>
            <div class="admin-component-item__meta">
              <span class="admin-component-item__name">{{ system.name }}</span>
              <span class="admin-component-item__detail">{{ system.detail }}</span>
            </div>
            <span
              class="admin-component-item__status"
              :class="componentStatusClass(system.status)"
            >{{ adminStatusLabel(system.status) }}</span>
          </div>
        </div>
        <div v-if="sourceErrors.overview" class="admin-dashboard-state">
          <button type="button" @click="retryOverview"><RefreshCw :size="15" aria-hidden="true" />重试</button>
        </div>
        <dl v-if="overview" class="admin-operational-facts">
          <div><dt>活跃用户</dt><dd>{{ number.format(overview.activeUsers) }}</dd></div>
          <div><dt>套餐发放</dt><dd>{{ number.format(overview.grants) }}</dd></div>
          <div><dt>事务消息待处理</dt><dd>{{ number.format(overview.outboxAttention) }}</dd></div>
          <div><dt>已支付订单</dt><dd>{{ number.format(overview.paidOrders) }} · {{ displayMoney(overview.paidAmount) }}</dd></div>
        </dl>
      </article>

      <article class="admin-panel admin-failure-panel" aria-labelledby="admin-failure-title">
        <div class="admin-panel__header"><div><h3 id="admin-failure-title">最近异常调用记录</h3><p>失败请求 · 按时间倒序</p></div></div>
        <div v-if="sourceErrors.failures" class="admin-dashboard-state">
          <button type="button" @click="retryFailures"><RefreshCw :size="15" aria-hidden="true" />重试</button>
        </div>
        <ul v-else-if="recentFailures.length" class="admin-failure-list">
          <li v-for="item in recentFailures" :key="item.id">
            <span class="admin-failure-list__model">{{ item.upstreamModel || item.modelCode }}</span>
            <span class="admin-failure-list__time">{{ formatDateTime(item.createdAt) }}</span>
            <span class="admin-failure-list__error">{{ item.errorSummary || item.errorCategory || `HTTP ${item.httpStatus}` }}</span>
            <span class="admin-failure-list__meta">{{ Math.round(item.durationMs) }}ms · {{ item.outcome }}</span>
          </li>
        </ul>
        <div v-else class="admin-failure-empty">
          <AlertTriangle :size="20" aria-hidden="true" style="margin-bottom: 6px" />
          <div>近期无失败调用记录</div>
        </div>
      </article>
    </section>
  </section>
</template>
