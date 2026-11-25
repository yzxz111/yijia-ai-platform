<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import InviteCodeCard from '../../components/user/InviteCodeCard.vue'
import { APP_NAME } from '../../brand'
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  BookOpen,
  ChartNoAxesCombined,
  CreditCard,
  Cpu,
  Copy,
  Crown,
  FileText,
  LayoutDashboard,
  LifeBuoy,
  RefreshCw,
  ShieldAlert,
  Timer,
  Zap,
} from 'lucide-vue-next'

import { type UsageEntry } from '../../api/gateway'
import { listSubscriptions, type SubscriptionSummary } from '../../api/subscriptions'
import AppChart from '../../components/charts/AppChart.vue'
import {
  buildModelDistributionOption,
  buildUsageTrendOption,
  formatModelUsageLabel,
} from '../../components/charts/dashboardOptions'
import { useUsageStore } from '../../stores/usage'
import { useAuthStore } from '../../stores/auth'
import { formatDateTime } from '../../utils/dateTime'
import { userPlanName } from '../../utils/planCopy'

type Source = 'analytics' | 'metrics' | 'quota' | 'usage'

const sourceMessages: Record<Source, string> = {
  analytics: '分析数据加载失败，请重试。',
  metrics: '延迟指标加载失败',
  quota: '套餐额度加载失败，请重试。',
  usage: '最近调用加载失败，请重试。',
}

const usage = useUsageStore()
const auth = useAuthStore()
const selectedDays = ref<7 | 30>(30)
const sourceErrors = ref<Partial<Record<Source, string>>>({})
const sourceAttempts: Record<Source, number> = {
  analytics: 0,
  metrics: 0,
  quota: 0,
  usage: 0,
}
const number = new Intl.NumberFormat('zh-CN')

const analytics = computed(() => (
  usage.analytics?.range.days === selectedDays.value ? usage.analytics : null
))
const active = computed(() => usage.quota?.activePackage)
/** 当前活跃订阅（若有）。决定顶部那句话说的是「续费」还是「到期」。 */
const activeSubscription = ref<SubscriptionSummary | null>(null)
const periodTokens = computed(() => analytics.value?.summary.periodTokens ?? null)
const todayRequests = computed(() => analytics.value?.summary.todayRequests ?? null)
const p95 = computed(() => (
  analytics.value?.summary.platformAddedP95Ms
  ?? usage.metrics?.platformAddedP95Ms
  ?? null
))
const consumption = computed(() => {
  if (!active.value?.totalTokens) return 0
  return Math.min(100, Math.round(((active.value.usedTokens || 0) / active.value.totalTokens) * 100))
})
const trendOption = computed(() => buildUsageTrendOption(analytics.value?.daily ?? []))
const modelOption = computed(() => buildModelDistributionOption(analytics.value?.models ?? []))
const hasTrendFacts = computed(() => analytics.value?.daily.some(
  (item) => item.requestCount > 0 || item.totalTokens > 0,
) ?? false)
const hasModelFacts = computed(() => analytics.value?.models.some(
  (item) => item.requestCount > 0 || item.totalTokens > 0,
) ?? false)

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '凌晨好'
  if (hour < 9) return '早上好'
  if (hour < 12) return '上午好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const userName = computed(() => {
  const name = auth.user?.displayName
  if (name) return name
  const phone = auth.user?.email
  return phone || '用户'
})

const planLabel = computed(() => {
  if (!active.value) return '免费版'
  return userPlanName(active.value.packageCode, active.value.packageName)
})

const todayDate = computed(() => {
  const now = new Date()
  const weekdays = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
  return `${now.getFullYear()}年${now.getMonth() + 1}月${now.getDate()}日 ${weekdays[now.getDay()]}`
})

/**
 * 距**下次自动扣款**还有几天。
 *
 * 订阅用户取真实的扣款时刻（到期前 24 小时），而不是套餐到期日——把到期日说成
 * 「距下次续费」会让用户在错误的日子等扣款。非订阅用户回退到到期日，
 * 与既有语义一致。
 */
const daysToExpiry = computed(() => {
  const target = activeSubscription.value?.nextChargeAt ?? active.value?.expiresAt
  if (!target) return null
  const expiry = new Date(target)
  const now = new Date()
  const diff = Math.ceil((expiry.getTime() - now.getTime()) / (1000 * 60 * 60 * 24))
  return diff > 0 ? diff : 0
})

/** 有订阅才说「下次续费」；否则那句话说的是到期，不该用续费的口径。 */
const renewalCaption = computed(() => (
  activeSubscription.value ? '距下次续费还有' : '距套餐到期还有'
))

const showQuotaWarning = computed(() => consumption.value >= 80)

function displayNumber(value: number | null) {
  return value === null ? '--' : number.format(value)
}

function usageModelLabel(item: UsageEntry) {
  if (!item.providerCode) return item.upstreamModel || item.modelCode
  return `${item.upstreamModel || item.modelCode} · ${item.providerCode}`
}

function relativeTime(dateStr: string) {
  const now = Date.now()
  const past = new Date(dateStr).getTime()
  const diff = Math.floor((now - past) / 1000)
  if (diff < 60) return `${diff}秒前`
  if (diff < 3600) return `${Math.floor(diff / 60)}分钟前`
  if (diff < 86400) return `${Math.floor(diff / 3600)}小时前`
  return `${Math.floor(diff / 86400)}天前`
}

function clearSourceError(source: Source) {
  if (!sourceErrors.value[source]) return
  const next = { ...sourceErrors.value }
  delete next[source]
  sourceErrors.value = next
}

async function attemptSource(
  source: Source,
  task: (isCurrent: () => boolean) => Promise<unknown>,
) {
  const attempt = ++sourceAttempts[source]
  const scopeGeneration = usage.userScopeGeneration
  const isCurrent = () => (
    attempt === sourceAttempts[source]
    && scopeGeneration === usage.userScopeGeneration
  )
  try {
    await task(isCurrent)
    if (!isCurrent()) return
    clearSourceError(source)
  } catch (error) {
    if (!isCurrent()) return
    sourceErrors.value = { ...sourceErrors.value, [source]: sourceMessages[source] }
    throw error
  }
}

function loadAnalytics(days: 7 | 30) {
  return attemptSource('analytics', () => usage.loadAnalytics(days))
}

function loadQuota() {
  return attemptSource('quota', () => usage.loadQuota())
}

function loadRecentUsage() {
  return attemptSource('usage', () => usage.loadUsage({ page: 0, size: 20 }))
}

function loadMetrics() {
  return attemptSource('metrics', () => usage.loadMetrics())
}

async function loadDashboard() {
  await Promise.allSettled([
    loadAnalytics(selectedDays.value),
    loadQuota(),
    loadRecentUsage(),
    loadMetrics(),
  ])
}

async function selectRange(days: 7 | 30) {
  if (days === selectedDays.value || usage.loadingAnalytics) return
  selectedDays.value = days
  await Promise.allSettled([loadAnalytics(days)])
}

async function retryAnalytics() {
  await Promise.allSettled([loadAnalytics(selectedDays.value)])
}

async function retryQuota() {
  await Promise.allSettled([loadQuota()])
}

async function retryUsage() {
  await Promise.allSettled([loadRecentUsage()])
}

async function copyRequestId(requestId: string) {
  await navigator.clipboard?.writeText(requestId)
}

// ---------- 新手引导对话框 ----------
const welcomeVisible = ref(false)
const SESSION_KEY = 'welcome_dialog_seen'

const companyInfo = {
  name: APP_NAME,
  intro: '一加AI中台将模型接入、API 密钥、套餐额度与调用记录整合在一个工作空间。无论是文字、图片还是音视频，都能从这里开始连接你的 AI 应用。',
  tags: [
    { label: '模型接入', value: '统一入口' },
    { label: '用量管理', value: '清晰可查' },
    { label: '工作空间', value: '按需配置' },
  ],
}

const welcomeFeatures = [
  {
    icon: LayoutDashboard,
    title: '实时工作台',
    desc: '总览额度消耗、调用趋势、延迟指标，掌握账户全貌。',
  },
  {
    icon: Zap,
    title: 'API 调用',
    desc: '通过标准 HTTP 请求快速接入，支持流式响应和多模型。',
  },
  {
    icon: CreditCard,
    title: '套餐与额度',
    desc: '灵活的套餐方案，实时查看积分用量和剩余额度。',
  },
  {
    icon: Cpu,
    title: '调用日志',
    desc: '完整记录每次请求的耗时、结果和消耗，方便排查。',
  },
]

const welcomeQuickLinks = [
  { to: '/app/help', label: '查看帮助文档', icon: BookOpen },
  { to: '/app/support-tickets', label: '提交支持工单', icon: LifeBuoy },
  { to: '/app/plans', label: '浏览套餐方案', icon: Crown },
]

function closeWelcome() {
  welcomeVisible.value = false
  try { sessionStorage.setItem(SESSION_KEY, '1') } catch { /* 忽略     */ }
}

onMounted(async () => {
  loadDashboard()
  loadActiveSubscription()
  await nextTick()
  try {
    if (!sessionStorage.getItem(SESSION_KEY)) {
      welcomeVisible.value = true
    }
  } catch { /* 忽略     */ }
})

/**
 * 读取当前活跃订阅，决定顶部那句话说的是「续费」还是「到期」。
 *
 * 失败时静默留空并回退到套餐到期日：这是一个展示增强，不该让它拖垮工作台。
 */
async function loadActiveSubscription() {
  try {
    const items = await listSubscriptions()
    activeSubscription.value = items.find(
      (item) => item.status === 'ACTIVE' || item.status === 'PAST_DUE',
    ) ?? null
  } catch {
    activeSubscription.value = null
  }
}
</script>

<template>
  <section class="dashboard">
    <!-- Greeting -->
    <div class="greeting-section">
      <div class="greeting-left">
        <span class="dashboard-eyebrow">{{ APP_NAME }} / WORKSPACE</span>
        <h2 class="greeting-title">{{ greeting }}，{{ userName }}</h2>
        <p class="greeting-subtitle">
          {{ todayDate }} · {{ planLabel }}
          <template v-if="daysToExpiry !== null"> · <span data-test="dashboard-renewal-caption">{{ renewalCaption }}</span> <span data-test="dashboard-days-to-renewal">{{ daysToExpiry }}</span> 天</template>
        </p>
      </div>
      <div class="greeting-actions">
        <button class="btn btn-ghost" @click="loadDashboard">
          <RefreshCw :size="16" />
          刷新
        </button>
        <RouterLink to="/app/plans" class="btn btn-primary">
          <Crown :size="16" />
          升级套餐
        </RouterLink>
      </div>
    </div>

    <!-- Quota Warning Banner -->
    <div v-if="showQuotaWarning" class="alert-banner">
      <div class="alert-content">
        <AlertTriangle :size="18" class="alert-icon" />
        <span>本月积分额度已消耗 {{ consumption }}%，建议尽快升级套餐以避免服务中断。</span>
      </div>
      <RouterLink to="/app/plans" class="btn btn-sm btn-primary">立即升级</RouterLink>
    </div>

    <!-- Error Messages -->
    <div v-if="sourceErrors.analytics || sourceErrors.metrics || sourceErrors.quota || sourceErrors.usage" class="error-banners">
      <div v-if="sourceErrors.analytics" class="error-banner">
        <span>{{ sourceErrors.analytics }}</span>
        <button data-test="dashboard-analytics-error" @click="retryAnalytics"><RefreshCw :size="14" />重试</button>
      </div>
      <div v-if="sourceErrors.metrics" class="error-banner">
        <span>{{ sourceErrors.metrics }}</span>
      </div>
      <div v-if="sourceErrors.quota" class="error-banner">
        <span>{{ sourceErrors.quota }}</span>
        <button @click="retryQuota"><RefreshCw :size="14" />重试</button>
      </div>
      <div v-if="sourceErrors.usage" class="error-banner">
        <span>{{ sourceErrors.usage }}</span>
        <button @click="retryUsage"><RefreshCw :size="14" />重试</button>
      </div>
    </div>

    <!-- Metric Cards -->
    <div class="metric-grid">
      <article class="metric-card">
        <div class="metric-icon metric-icon--blue">
          <ChartNoAxesCombined :size="20" />
        </div>
        <div class="metric-body">
          <span class="metric-label">{{ selectedDays }} 天积分消耗</span>
          <strong class="metric-value" data-test="dashboard-period-tokens">{{ displayNumber(periodTokens) }}</strong>
          <small class="metric-subtitle" v-if="active">额度上限 {{ number.format(active.totalTokens || 0) }}</small>
          <small class="metric-subtitle" v-else-if="sourceErrors.analytics">数据不可用</small>
        </div>
      </article>

      <article class="metric-card">
        <div class="metric-icon metric-icon--green">
          <Activity :size="20" />
        </div>
        <div class="metric-body">
          <span class="metric-label">今日请求</span>
          <strong class="metric-value" data-test="dashboard-today-requests">{{ displayNumber(todayRequests) }}</strong>
          <small class="metric-subtitle" v-if="analytics">
            成功 {{ number.format(analytics.summary.todaySuccessfulRequests) }} · 失败 {{ number.format(analytics.summary.todayFailedRequests) }}
          </small>
          <small class="metric-subtitle" v-else>最近 24 小时</small>
        </div>
      </article>

      <article class="metric-card">
        <div class="metric-icon metric-icon--purple">
          <Zap :size="20" />
        </div>
        <div class="metric-body">
          <span class="metric-label">积分消耗占比</span>
          <strong class="metric-value">{{ consumption }}<span class="unit">%</span></strong>
          <small class="metric-subtitle" v-if="active">
            已用 {{ number.format(active.usedTokens || 0) }}
          </small>
          <small class="metric-subtitle" v-else>当前无生效套餐</small>
        </div>
      </article>

      <article class="metric-card">
        <div class="metric-icon metric-icon--teal">
          <Timer :size="20" />
        </div>
        <div class="metric-body">
          <span class="metric-label">P95 平台新增耗时</span>
          <strong class="metric-value" data-test="dashboard-p95">{{ displayNumber(p95) }}<span v-if="p95 !== null" class="unit"> ms</span></strong>
          <small class="metric-subtitle" v-if="sourceErrors.analytics && !sourceErrors.metrics">使用独立指标口径</small>
          <small class="metric-subtitle" v-else-if="sourceErrors.analytics && sourceErrors.metrics">数据不可用</small>
          <small class="metric-subtitle" v-else>最近 24 小时</small>
        </div>
      </article>
    </div>

    <!-- 邀请码：后端 /console/platform/referrals/** 一直有，但此前控制台没有任何入口 -->
    <InviteCodeCard />

    <!-- Charts -->
    <section class="chart-grid">
      <article class="chart-panel">
        <div class="panel-heading">
          <div>
            <h3>积分用量趋势</h3>
            <span>每日消耗量 · {{ selectedDays }} 天</span>
          </div>
          <div class="range-control" role="group" aria-label="分析周期">
            <button
              type="button"
              data-test="dashboard-range-7"
              :aria-pressed="selectedDays === 7"
              :disabled="usage.loadingAnalytics"
              @click="selectRange(7)"
            >7 天</button>
            <button
              type="button"
              data-test="dashboard-range-30"
              :aria-pressed="selectedDays === 30"
              :disabled="usage.loadingAnalytics"
              @click="selectRange(30)"
            >30 天</button>
          </div>
        </div>
        <div v-if="sourceErrors.analytics" class="panel-state" data-test="dashboard-analytics-error">
          <button type="button" data-test="dashboard-analytics-retry" @click="retryAnalytics">
            <RefreshCw :size="15" />重试
          </button>
        </div>
        <div v-else-if="!analytics" class="panel-state" aria-busy="true">数据加载中</div>
        <div v-else class="chart-shell">
          <AppChart data-test="dashboard-chart"
            :option="trendOption"
            height="var(--dashboard-chart-height)"
            :ariaLabel="`${selectedDays} 天请求数和积分趋势`"
          />
          <div v-if="!hasTrendFacts" class="chart-empty chart-empty--centered">当前周期暂无真实调用</div>
        </div>
      </article>

      <article class="chart-panel">
        <div class="panel-heading">
          <div>
            <h3>按模型分布</h3>
            <span>本月 API 调用次数</span>
          </div>
        </div>
        <div v-if="sourceErrors.analytics" class="panel-state">等待分析数据</div>
        <div v-else-if="!analytics" class="panel-state" aria-busy="true">数据加载中</div>
        <template v-else>
          <div v-if="analytics.models.length" class="chart-shell model-chart">
            <AppChart data-test="dashboard-chart"
              :option="modelOption"
              height="var(--dashboard-chart-height)"
              ariaLabel="模型积分与调用次数分布"
            />
            <div v-if="!hasModelFacts" class="chart-empty chart-empty--centered">当前周期暂无模型调用</div>
          </div>
          <div v-else class="panel-state">当前周期暂无模型调用</div>
          <ul v-if="analytics.models.length" class="model-list">
            <li v-for="model in analytics.models" :key="`${model.modelCode}:${model.providerCode}:${model.upstreamModel || ''}`">
              <div>
                <strong>{{ formatModelUsageLabel(model) }}</strong>
                <small v-if="model.upstreamModel">平台模型：{{ model.modelCode }}</small>
              </div>
              <span>{{ number.format(model.totalTokens) }} 积分 · {{ number.format(model.requestCount) }} 次</span>
            </li>
          </ul>
        </template>
      </article>
    </section>

    <!-- Quota Overview -->
    <section class="quota-panel">
      <div class="panel-heading">
        <div>
          <h3>额度总览</h3>
          <span v-if="active">{{ planLabel }}</span>
        </div>
        <RouterLink to="/app/quota" class="link-more">
          查看详情 <ArrowRight :size="14" />
        </RouterLink>
      </div>
      <div v-if="sourceErrors.quota" class="panel-state" data-test="dashboard-quota-error">
        <button type="button" data-test="dashboard-quota-retry" @click="retryQuota"><RefreshCw :size="15" />重试</button>
      </div>
      <div v-else-if="usage.loadingQuota && !usage.quota" class="panel-state" aria-busy="true">数据加载中</div>
      <div v-else-if="!active" class="panel-state">当前没有生效套餐</div>
      <div v-else class="quota-cards">
        <div class="quota-card" data-test="dashboard-quota" :class="{ warning: consumption >= 80 }">
          <div class="quota-card-head">
            <span>月度积分</span>
            <strong>{{ consumption }}%</strong>
          </div>
          <el-progress :percentage="consumption" :stroke-width="8" :show-text="false" :status="consumption >= 80 ? 'warning' : undefined" />
          <div class="quota-card-foot">
            <span>{{ number.format(active.usedTokens || 0) }}</span>
            <span>{{ number.format(active.totalTokens || 0) }}</span>
          </div>
        </div>

        <div class="quota-card">
          <div class="quota-card-head">
            <span>今日请求次数</span>
            <strong>{{ todayRequests !== null ? Math.round((todayRequests / Math.max(1, active.dailyRequestLimit || 1)) * 100) : 0 }}%</strong>
          </div>
          <el-progress
            :percentage="todayRequests !== null && active.dailyRequestLimit ? Math.min(100, Math.round((todayRequests / active.dailyRequestLimit) * 100)) : 0"
            :stroke-width="8"
            :show-text="false"
            color="#10b981"
          />
          <div class="quota-card-foot">
            <span data-test="dashboard-daily-requests">{{ displayNumber(todayRequests) }}</span>
            <span aria-hidden="true">/</span>
            <span data-test="dashboard-daily-limit">{{ active.dailyRequestLimit ? number.format(active.dailyRequestLimit) : '不限' }}</span>
          </div>
        </div>

        <div class="quota-card">
          <div class="quota-card-head">
            <span>剩余额度</span>
            <strong>{{ active.remainingTokens ? Math.round((active.remainingTokens / Math.max(1, active.totalTokens || 1)) * 100) : 0 }}%</strong>
          </div>
          <el-progress
            :percentage="active.remainingTokens && active.totalTokens ? Math.round((active.remainingTokens / active.totalTokens) * 100) : 0"
            :stroke-width="8"
            :show-text="false"
            color="#6754c7"
          />
          <div class="quota-card-foot">
            <span>{{ number.format(active.remainingTokens || 0) }}</span>
            <span>到期 {{ active.expiresAt ? formatDateTime(active.expiresAt) : '未提供' }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- Recent API Requests -->
    <section class="recent-panel">
      <div class="panel-heading">
        <div>
          <h3>最近 API 请求</h3>
          <span>按请求时间倒序</span>
        </div>
        <RouterLink to="/app/usage" class="link-more">
          查看日志 <ArrowRight :size="14" />
        </RouterLink>
      </div>
      <div v-if="sourceErrors.usage" class="panel-state" data-test="dashboard-usage-error">
        <button type="button" data-test="dashboard-usage-retry" @click="retryUsage"><RefreshCw :size="15" />重试</button>
      </div>
      <div v-else class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>状态</th>
              <th>模型</th>
              <th>积分</th>
              <th>响应延迟</th>
              <th>时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in usage.usage.items.slice(0, 6)" :key="item.id">
              <td>
                <span class="status-badge" :class="item.outcome.toLowerCase()">
                  {{ item.outcome === 'SUCCEEDED' ? '成功' : item.outcome === 'FAILED' ? '失败' : '取消' }}
                </span>
              </td>
              <td>
                <div class="model-cell">
                  <strong>{{ usageModelLabel(item) }}</strong>
                  <button type="button" class="request-id-copy" data-test="dashboard-copy-request-id" :title="item.requestId" :aria-label="`复制请求 ID ${item.requestId}`" @click="copyRequestId(item.requestId)"><code>{{ item.requestId.length > 12 ? `${item.requestId.slice(0, 12)}…` : item.requestId }}</code><Copy :size="13" aria-hidden="true" /></button>
                </div>
              </td>
              <td class="num">{{ number.format(item.totalTokens) }}</td>
              <td class="num">{{ number.format(item.durationMs) }} ms</td>
              <td class="muted">{{ relativeTime(item.createdAt) }}</td>
            </tr>
            <tr v-if="!usage.loadingUsage && !usage.usage.items.length">
              <td colspan="5" class="empty-row">暂无调用记录</td>
            </tr>
            <tr v-else-if="usage.loadingUsage && !usage.usage.items.length">
              <td colspan="5" class="empty-row">数据加载中</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- Welcome tour dialog -->
    <el-dialog
      v-model="welcomeVisible"
      width="min(720px, 94vw)"
      :show-close="false"
      :close-on-click-modal="false"
      :close-on-press-escape="true"
      append-to-body
      class="welcome-dialog"
      destroy-on-close
    >
      <div class="welcome-body">
        <!-- Header -->
        <div class="welcome-header">
          <div class="welcome-icon">
            <LayoutDashboard :size="22" />
          </div>
          <div class="welcome-title-wrap">
            <h2 class="welcome-title">欢迎来到一加AI中台</h2>
            <p class="welcome-sub">一站式 API 接入与用量管理平台</p>
          </div>
          <button class="welcome-close" type="button" aria-label="关闭" @click="closeWelcome">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
          </button>
        </div>

        <!-- Company intro -->
        <div class="welcome-company">
          <div class="welcome-company-name">{{ companyInfo.name }}</div>
          <p class="welcome-company-intro">{{ companyInfo.intro }}</p>
          <div class="welcome-company-tags">
            <div v-for="t in companyInfo.tags" :key="t.label" class="welcome-company-tag">
              <span class="wct-label">{{ t.label }}</span>
              <span class="wct-value">{{ t.value }}</span>
            </div>
          </div>
        </div>

        <!-- Features grid -->
        <div class="welcome-features">
          <div v-for="f in welcomeFeatures" :key="f.title" class="welcome-feature-card">
            <div class="wf-icon">
              <component :is="f.icon" :size="18" />
            </div>
            <div>
              <div class="wf-title">{{ f.title }}</div>
              <div class="wf-desc">{{ f.desc }}</div>
            </div>
          </div>
        </div>

        <!-- Quick links -->
        <div class="welcome-links">
          <div class="welcome-links-label">快速开始</div>
          <div class="welcome-links-row">
            <router-link
              v-for="l in welcomeQuickLinks"
              :key="l.to"
              :to="l.to"
              class="welcome-link"
              @click="closeWelcome"
            >
              <component :is="l.icon" :size="15" />
              <span>{{ l.label }}</span>
              <ArrowRight :size="14" />
            </router-link>
          </div>
        </div>

        <!-- Footer -->
        <div class="welcome-footer">
          <span class="welcome-footer-hint">按 Esc 或点右上角可关闭</span>
          <button type="button" class="welcome-footer-btn" @click="closeWelcome">
            开始使用 <ArrowRight :size="15" />
          </button>
        </div>
      </div>
    </el-dialog>
  </section>
</template>

<style scoped>
.dashboard {
  --dashboard-chart-height: 320px;
  width: min(1280px, 100%);
}

.dashboard-eyebrow { color: var(--app-primary); font-size: 10px; font-weight: 650; letter-spacing: 1.6px; }

/* 问候语 */
.greeting-section {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 24px;
  padding: 30px;
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: 18px;
}
.greeting-title {
  margin: 10px 0 0;
  font-size: 28px;
  line-height: 1.35;
  font-weight: 650;
  letter-spacing: -.5px;
}
.greeting-subtitle {
  margin: 6px 0 0;
  color: var(--app-muted);
  font-size: 13px;
}
.greeting-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}
.btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 16px;
  height: 38px;
  border-radius: 12px;
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all var(--app-motion-fast) ease;
}
.btn-primary {
  color: #fff;
  background: var(--app-primary);
  border-color: var(--app-primary);
}
.btn-primary:hover {
  background: var(--app-primary-hover);
}
.btn-ghost {
  color: var(--app-fg);
  background: var(--app-surface);
  border-color: var(--app-border);
}
.btn-ghost:hover {
  border-color: var(--app-border-strong);
  background: var(--app-surface-subtle);
}
.btn-sm {
  padding: 0 12px;
  height: 32px;
  font-size: 12px;
}

/* 告警横幅 */
.alert-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  margin-bottom: 16px;
  background: var(--app-warning-soft);
  border: 1px solid var(--app-warning);
  border-radius: 12px;
}
.alert-content {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--app-warning);
  font-size: 13px;
}
.alert-icon { flex-shrink: 0; }

/* 错误横幅 */
.error-banners {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}
.error-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  background: var(--app-danger-soft);
  border: 1px solid var(--app-danger);
  border-radius: 6px;
  font-size: 13px;
  color: var(--app-danger);
}
.error-banner button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0 10px;
  height: 28px;
  background: transparent;
  border: 1px solid var(--app-border);
  border-radius: 5px;
  font: inherit;
  font-size: 12px;
  font-weight: 600;
  color: var(--app-primary);
  cursor: pointer;
}
.error-banner button:hover {
  background: var(--app-primary-soft);
}

/* 指标卡片 */
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 16px;
}
.metric-card {
  display: flex;
  gap: 14px;
  padding: 22px 18px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 14px;
  box-shadow: var(--app-shadow-xs);
  transition: border-color var(--app-motion-fast) ease, box-shadow var(--app-motion-fast) ease;
}
.metric-card:hover {
  border-color: var(--app-border-strong);
  box-shadow: var(--app-shadow-sm);
}
.metric-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 8px;
  flex-shrink: 0;
}
.metric-icon--blue {
  color: var(--app-primary);
  background: var(--app-primary-soft);
}
.metric-icon--green {
  color: var(--app-success);
  background: var(--app-success-soft);
}
.metric-icon--purple {
  color: var(--app-primary);
  background: var(--app-primary-soft);
}
.metric-icon--teal {
  color: var(--app-accent);
  background: var(--app-accent-soft);
}
.metric-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.metric-label {
  font-size: 12px;
  color: var(--app-muted);
  font-weight: 500;
}
.metric-value {
  margin-top: 7px;
  font-size: 27px;
  line-height: 1.2;
  font-weight: 650;
  letter-spacing: -.5px;
  font-variant-numeric: tabular-nums;
}
.metric-value .unit {
  font-size: 14px;
  font-weight: 600;
  color: var(--app-muted);
}
.metric-subtitle {
  margin-top: 4px;
  font-size: 12px;
  color: var(--app-muted);
  min-height: 18px;
}

/* 图表栅格 */
.chart-grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(300px, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}
.chart-panel, .quota-panel, .recent-panel { display: flex; flex-direction: column;
  min-width: 0;
  overflow: hidden;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  box-shadow: var(--app-shadow-sm);
}
.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 56px;
  padding: 12px 16px;
  background: var(--app-surface-subtle);
  border-bottom: 1px solid var(--app-border);
}
.panel-heading > div { min-width: 0; }
.panel-heading h3 {
  margin: 0;
  font-size: 15px;
}
.panel-heading span {
  display: block;
  margin-top: 3px;
  font-size: 12px;
  color: var(--app-muted);
}
.link-more {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--app-primary);
  font-weight: 600;
  font-size: 13px;
  text-decoration: none;
}
.link-more:hover {
  text-decoration: underline;
}

/* 时间范围控件 */
.range-control {
  display: inline-flex;
  padding: 3px;
  background: var(--app-surface-muted);
  border: 1px solid var(--app-border);
  border-radius: 7px;
}
.range-control button {
  min-height: 30px;
  padding: 0 12px;
  color: var(--app-muted);
  background: transparent;
  border: 0;
  border-radius: 5px;
  cursor: pointer;
  font: inherit;
  font-weight: 600;
  font-size: 12px;
}
.range-control button[aria-pressed='true'] {
  color: var(--app-primary);
  background: var(--app-surface);
  box-shadow: var(--app-shadow-xs);
}
.range-control button:disabled { cursor: wait; opacity: .65; }

/* 图表外壳 */
.chart-shell {
  position: relative;
  flex: 1 1 auto;
  min-height: var(--dashboard-chart-height);
}
.chart-empty {
  position: absolute;
  max-width: calc(100% - 32px);
  padding: 7px 10px;
  color: var(--app-muted);
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 6px;
  text-align: center;
  pointer-events: none;
}
.chart-empty--centered {
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
}
.panel-state {
  display: grid;
  place-items: center;
  min-height: var(--dashboard-chart-height);
  padding: 24px;
  color: var(--app-muted);
  text-align: center;
}
.panel-state button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  padding: 0 12px;
  color: var(--app-primary);
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 6px;
  cursor: pointer;
  font: inherit;
  font-weight: 600;
  font-size: 13px;
}
.panel-state button:hover {
  background: var(--app-primary-soft);
  border-color: var(--app-primary-soft);
}

/* 模型列表 */
.model-list {
  display: grid;
  gap: 0;
  margin: 0;
  padding: 0 16px 12px;
  list-style: none;
}
.model-list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  padding: 10px 4px;
  border-top: 1px solid var(--app-border);
  transition: background-color var(--app-motion-fast) ease;
}
.model-list li:hover { background: var(--app-surface-subtle); }
.model-list strong, .model-list small {
  display: block;
  overflow-wrap: anywhere;
}
.model-list small { color: var(--app-muted); }
.model-list span {
  color: var(--app-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* 额度卡片 */
.quota-panel { margin-bottom: 16px; }
.quota-panel .panel-state { min-height: 140px; }
.quota-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  padding: 16px;
}
.quota-card {
  padding: 16px;
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  border-left: 3px solid transparent;
}
.quota-card.warning {
  background: var(--app-warning-soft);
  border-left-color: var(--app-warning);
}
.quota-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.quota-card-head span {
  font-size: 13px;
  color: var(--app-muted);
}
.quota-card-head strong {
  font-size: 16px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.quota-card-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  font-size: 12px;
  color: var(--app-muted);
  font-variant-numeric: tabular-nums;
}

/* 最近记录表格 */
.recent-panel .panel-state { min-height: 120px; }
.table-scroll {
  max-width: 100%;
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
}
th, td {
  padding: 11px 14px;
  border-bottom: 1px solid var(--app-border);
  text-align: left;
  white-space: nowrap;
}
th {
  color: var(--app-muted);
  background: var(--app-surface-subtle);
  font-size: 12px;
  font-weight: 700;
}
tbody tr {
  transition: background-color var(--app-motion-fast) ease;
}
tbody tr:hover {
  background: var(--app-surface-subtle);
}
.num {
  font-variant-numeric: tabular-nums;
}
.muted {
  color: var(--app-muted);
}
.request-id-copy { padding: 0; border: 0; background: transparent; color: inherit; cursor: pointer; text-align: left; }
.request-id-copy:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 2px; }
.model-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.model-cell code {
  font-size: 11px;
  color: var(--app-muted);
  overflow: hidden;
  text-overflow: ellipsis;
}
.request-id { display: inline-flex; align-items: center; gap: 4px; }
.request-id button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 28px;
  min-height: 28px;
  border: 0;
  border-radius: 4px;
  color: var(--app-muted);
  background: transparent;
  cursor: pointer;
}
.request-id button:hover { color: var(--app-primary); background: var(--app-primary-soft); }
.status-badge {
  display: inline-block;
  padding: 3px 8px;
  border-radius: 5px;
  font-size: 12px;
  font-weight: 700;
}
.status-badge.succeeded {
  color: var(--app-success);
  background: var(--app-success-soft);
}
.status-badge.failed {
  color: var(--app-danger);
  background: var(--app-danger-soft);
}
.status-badge.cancelled {
  color: var(--app-muted);
  background: var(--app-surface-muted);
}
.empty-row {
  height: 80px;
  color: var(--app-muted);
  text-align: center;
}

/* 响应式 */
@media (max-width: 1100px) {
  .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .chart-grid { grid-template-columns: minmax(0, 1fr); }
  .quota-cards { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 600px) {
  .dashboard { --dashboard-chart-height: 280px; }
  .greeting-section {
    flex-direction: column;
    align-items: stretch;
  }
  .greeting-actions { justify-content: flex-end; }
  .metric-grid { grid-template-columns: minmax(0, 1fr); }
  .chart-grid { grid-template-columns: minmax(0, 1fr); }
  .alert-banner {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
}

/* ========== 新手引导对话框 ========== */
:global(.welcome-dialog .el-dialog__body) {
  padding: 0;
  border-radius: 12px;
  overflow: hidden;
}
:global(.welcome-dialog .el-dialog) {
  border-radius: 12px;
  box-shadow: var(--app-shadow-lg);
  padding: 0;
  background: var(--app-surface);
}
:global(.welcome-dialog .el-dialog__header) { display: none; }
:global(.welcome-dialog .el-dialog__footer) { display: none; }

.welcome-body {
  padding: 0;
}

/* 头部 */
.welcome-header {
  position: relative;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 24px 26px 20px;
  background: var(--app-primary-soft);
  border-bottom: 1px solid var(--app-border);
}
.welcome-icon {
  display: inline-flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: var(--app-primary);
  color: #fff;
    flex-shrink: 0;
}
.welcome-title-wrap {
  flex: 1;
  min-width: 0;
}
.welcome-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--app-fg);
  letter-spacing: -0.01em;
}
.welcome-sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--app-muted);
}
.welcome-close {
  display: inline-flex;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  border: 0;
  background: transparent;
  color: var(--app-muted);
  cursor: pointer;
  transition: background-color 140ms ease, color 140ms ease;
  flex-shrink: 0;
}
.welcome-close:hover {
  background: var(--app-surface-subtle);
  color: var(--app-fg);
}

/* 公司简介 */
.welcome-company {
  padding: 20px 26px 8px;
}
.welcome-company-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--app-fg);
  margin-bottom: 8px;
}
.welcome-company-intro {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.75;
  color: var(--app-muted);
}
.welcome-company-tags {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.welcome-company-tag {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
}
.wct-label {
  font-size: 11px;
  color: var(--app-muted);
}
.wct-value {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-fg);
}

/* 功能栅格 */
.welcome-features {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
  padding: 20px 26px 6px;
}
.welcome-feature-card {
  display: flex;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--app-border);
  border-radius: 12px;
  background: var(--app-surface);
  transition: border-color 160ms ease, box-shadow 160ms ease;
}
.welcome-feature-card:hover {
  border-color: color-mix(in srgb, var(--app-primary) 35%, var(--app-border));
  }
.wf-icon {
  display: inline-flex;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: var(--app-primary-soft);
  color: var(--app-primary);
  flex-shrink: 0;
}
.wf-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--app-fg);
  margin-bottom: 2px;
}
.wf-desc {
  font-size: 12px;
  color: var(--app-muted);
  line-height: 1.55;
}

/* 快捷入口 */
.welcome-links {
  padding: 18px 26px 4px;
}
.welcome-links-label {
  font-size: 12px;
  font-weight: 700;
  color: var(--app-muted-strong);
  margin-bottom: 10px;
}
.welcome-links-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.welcome-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border-radius: 12px;
  border: 1px solid var(--app-border);
  background: var(--app-surface);
  color: var(--app-fg);
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  transition: all 150ms ease;
  cursor: pointer;
}
.welcome-link:hover {
  border-color: var(--app-primary);
  background: var(--app-primary-soft);
  color: var(--app-primary);
}

/* 页脚 */
.welcome-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 26px 22px;
  border-top: 1px solid var(--app-border);
}
.welcome-footer-hint {
  font-size: 12px;
  color: var(--app-muted);
}
.welcome-footer-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 20px;
  border: 0;
  border-radius: 12px;
  background: var(--app-primary);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
    transition: transform 140ms ease, box-shadow 140ms ease;
}
.welcome-footer-btn:hover {
  }
.welcome-footer-btn:active { transform: translateY(0); }

/* 响应式 */
@media (max-width: 560px) {
  .welcome-features { grid-template-columns: 1fr; }
  .welcome-header { padding: 20px 18px 16px; }
  .welcome-company { padding: 16px 18px 8px; }
  .welcome-company-tags { grid-template-columns: repeat(3, 1fr); }
  .welcome-features { padding: 16px 18px 6px; }
  .welcome-links { padding: 16px 18px 4px; }
  .welcome-footer { padding: 14px 18px 18px; flex-direction: column; align-items: stretch; }
  .welcome-footer-btn { justify-content: center; width: 100%; }
  .welcome-footer-hint { text-align: center; }
}
@media (max-width: 600px) { .greeting-section { padding: 22px 18px; } .greeting-title { font-size: 23px; } }
</style>
