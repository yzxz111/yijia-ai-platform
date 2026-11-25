<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AlertTriangle, ArrowUpRight, Gauge, LockKeyhole, Sigma, TrendingUp, WalletCards, Zap, BarChart3, PieChart, RefreshCw } from 'lucide-vue-next'

import { getPackageLimitUsage, type PackageLimitUsage } from '../../api/platform'
import PackageLimitUsagePanel from '../../components/usage/PackageLimitUsagePanel.vue'
import { useUsageStore } from '../../stores/usage'
import { userPlanName } from '../../utils/planCopy'

const usage = useUsageStore()
const router = useRouter()
const errorMessage = ref('')
/**
 * 数字面板的口径基准。网关的兼容字段 activePackage 取「文本实例优先，否则唯一那个」，
 * 因此同时持有文本 + 视频套餐时这里就是文本套餐：下面的总量/已用/剩余都按积分口径渲染，
 * 绝不会把秒数和积分加在一起。
 */
const active = computed(() => usage.quota?.activePackage)
const number = new Intl.NumberFormat('zh-CN')

/**
 * 用户持有的全部生效中套餐：自 V80 起唯一性按用量维度收窄，一个用户可以每个维度
 * 各持有一个（文本 + 视频 + 图片）。旧网关不返回 activePackages，回退到单个字段。
 */
const activePackages = computed(() => {
  const listed = usage.quota?.activePackages
  if (listed?.length) return listed
  return active.value ? [active.value] : []
})

/** 生效中套餐涉及的维度，用于在页面上说明「本页数字只覆盖哪一部分」。 */
const activeDimensionLabels = computed(() => activePackages.value
  .map((item) => dimensionLabel(item.usageDimension))
  .filter((label): label is string => Boolean(label)))

function dimensionLabel(dimension?: string): string {
  if (dimension === 'VIDEO_SECOND') return '视频（按秒）'
  // 音频与视频同单位（秒）却不同维度，因此这一句必须单独存在：
  // 落到下面的默认分支会让一个持有音频套餐的用户看到一片空白，
  // 从而无法判断自己套餐里的秒数为什么不在这一页。
  if (dimension === 'AUDIO_SECOND') return '音频（按秒）'
  if (dimension === 'IMAGE') return '图片'
  if (dimension === 'TOKEN') return '文本积分'
  return ''
}

interface LimitUsageRow {
  key: string
  label: string
  usage: PackageLimitUsage | null
  unavailable: boolean
}

const limitUsageRows = ref<LimitUsageRow[]>([])
const limitUsageLoading = ref(false)

/**
 * 视频套餐的额度单位是秒，而用户端不展示秒数额度（套餐页同样只写计费方式）。
 * 这一页的所有数字——总量/已用/预占/剩余、按模型占比——对视频套餐来说都是秒数，
 * 所以整页数字区换成口径说明；限流面板保留（那是每分钟上限，不是额度）。
 * 判定依据是网关额度接口带回来的 usageDimension，而不是猜套餐代码。
 */
const quotaIsSeconds = computed(() => {
  const dimension = active.value?.usageDimension
  // 音频必须在这里：漏掉它会让一个音频套餐的整页数字按积分的口径展示，
  // 而那一列里装的是秒数。与 isSecondDimension（api/platform.ts）同一条判据。
  return dimension === 'VIDEO_SECOND'
    || dimension === 'IMAGE'
    || dimension === 'AUDIO_SECOND'
})

/**
 * 每个生效套餐一行（每个用量维度各一个），限流计数都来自平台服务；
 * 网关不可达时接口返回 503。
 * 这种情况显示"暂时取不到用量"，绝不把失败当成 0（0 会被读成"没有被拒绝过"）。
 */
async function loadLimitUsage() {
  const packages = activePackages.value
  limitUsageRows.value = packages.map((item) => ({
    key: item.packageInstanceId || item.packageCode,
    label: userPlanName(item.packageCode, item.packageName),
    usage: null,
    // 没有 packageInstanceId 就没有可查询的对象，同样按"取不到"处理。
    unavailable: !item.packageInstanceId,
  }))
  if (!limitUsageRows.value.length) return
  limitUsageLoading.value = true
  try {
    await Promise.all(limitUsageRows.value.map(async (row, index) => {
      const packageInstanceId = packages[index]?.packageInstanceId
      if (!packageInstanceId) return
      try {
        row.usage = await getPackageLimitUsage(packageInstanceId)
      } catch {
        row.unavailable = true
      }
    }))
  } finally {
    limitUsageLoading.value = false
  }
}

const totalTokens = computed(() => Math.max(0, active.value?.totalTokens || 0))
const reservedTokens = computed(() => Math.max(0, active.value?.reservedTokens || 0))
const usedTokens = computed(() => Math.max(0, active.value?.usedTokens || 0))
const remainingTokens = computed(() => {
  const reported = active.value?.remainingTokens
  return Math.max(0, reported ?? totalTokens.value - usedTokens.value - reservedTokens.value)
})

const usedPercent = computed(() => {
  if (totalTokens.value <= 0) return 0
  return Math.min(100, Math.round((usedTokens.value / totalTokens.value) * 100))
})
const reservedPercent = computed(() => {
  if (totalTokens.value <= 0) return 0
  return Math.min(100, Math.round((reservedTokens.value / totalTokens.value) * 100))
})
const remainingPercent = computed(() => {
  if (totalTokens.value <= 0) return 0
  return Math.min(100, Math.round((remainingTokens.value / totalTokens.value) * 100))
})

type Status = 'normal' | 'warning' | 'danger'
const overallStatus = computed<Status>(() => {
  if (remainingTokens.value <= 0) return 'danger'
  if (totalTokens.value > 0 && remainingTokens.value / totalTokens.value <= 0.15) return 'warning'
  return 'normal'
})

function statusByPercent(pct: number): Status {
  if (pct >= 80) return 'danger'
  if (pct >= 50) return 'warning'
  return 'normal'
}

interface AudioSecondsBucket {
  key: string
  label: string
  total: number
  reserved: number
  used: number
  remaining: number
  usedPercent: number
  reservedPercent: number
  remainingPercent: number
  status: Status
}

/**
 * 方案 C 的第二份额度：音频秒（转写 / 合成 / 唇形同步）。
 *
 * 只有"视频秒 + 音频秒"的混合套餐才带它；为 null/缺失表示这个套餐**不卖语音**，
 * 此时必须整块不渲染 —— 显示「0 秒」等于承诺"包含 0 秒语音"，那是个假承诺
 * （数据库的 CHECK 也正是为此拒绝 0 额度）。
 *
 * 它与主桶单位相同（秒）却属于不同的产品，因此只**并列**展示各自的进度，
 * 绝不相加：视频秒与音频秒是两个独立耗尽的桶，合成一个总数会让客户
 * 以为自己还有额度可用。
 *
 * 逐实例取值而不是只取 active：一个用户可以同时持有文本套餐与视频套餐，
 * 而"兼容字段"activePackage 那种情况下指的是文本套餐；只看它会让混合套餐的
 * 音频秒余额彻底看不见。
 */
const audioSecondsBuckets = computed<AudioSecondsBucket[]>(() => {
  const buckets: AudioSecondsBucket[] = []
  for (const item of activePackages.value) {
    const seconds = item.audioSeconds
    if (!seconds) continue
    // 0 秒的桶在契约里不存在（两份 schema 的 CHECK 都拒绝 0 额度）。万一上游
    // 回了一个，就按"这个套餐没卖语音"处理：显示"包含 0 秒语音"是假承诺。
    if (!seconds.totalSeconds || seconds.totalSeconds <= 0) continue
    const total = seconds.totalSeconds
    const reserved = Math.max(0, seconds.reservedSeconds || 0)
    const used = Math.max(0, seconds.usedSeconds || 0)
    // 网关的 remainingSeconds 已经扣掉在途预留，且允许为负；这里与主桶一样
    // 截到 0，页面用"剩余不足"的状态而不是负数表达这件事。
    const remaining = Math.max(0, seconds.remainingSeconds ?? total - used - reserved)
    const share = (value: number) => (total > 0 ? Math.min(100, Math.round((value / total) * 100)) : 0)
    buckets.push({
      key: item.packageInstanceId || item.packageCode,
      label: userPlanName(item.packageCode, item.packageName),
      total,
      reserved,
      used,
      remaining,
      usedPercent: share(used),
      reservedPercent: share(reserved),
      remainingPercent: share(remaining),
      status: remaining <= 0 ? 'danger' : (total > 0 && remaining / total <= 0.15 ? 'warning' : 'normal'),
    })
  }
  return buckets
})

/**
 * 页头文案。同时持有两份额度时，"视频额度管理"会漏掉这一页正在展示的语音秒，
 * 而"按实际生成时长扣减"对转写是错的：一次转写扣的是**输入音频**的时长，
 * 不是生成的时长。只有混合套餐才需要这两句改写。
 */
const pageTitle = computed(() => (audioSecondsBuckets.value.length
  ? '视频与语音额度管理'
  : quotaIsSeconds.value ? '视频额度管理' : '积分额度管理'))
const pageSubtitle = computed(() => (audioSecondsBuckets.value.length
  ? '视频按生成时长、语音按处理时长分别扣减 · 两份额度互不挤占'
  : quotaIsSeconds.value ? '按秒计费 · 按实际生成时长扣减' : '实时额度追踪与用量分析'))

function formatTokens(n: number) {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万`
  return number.format(n)
}

const modelData = computed(() => {
  const quotas = active.value?.modelQuotas || []
  const total = quotas.reduce((s, m) => s + (m.usedTokens || 0), 0)
  const colors = ['var(--app-primary)', 'var(--app-primary)', '#06b6d4', '#10b981', '#f59e0b', '#ef4444']
  return quotas.map((m, i) => ({
    code: m.modelCode,
    used: m.usedTokens || 0,
    total: m.totalTokens || 0,
    remaining: m.remainingTokens || 0,
    percent: total > 0 ? Math.round(((m.usedTokens || 0) / total) * 100) : 0,
    color: colors[i % colors.length],
  }))
})

const showAlertBanner = computed(() => overallStatus.value !== 'normal')

onMounted(async () => {
  try { await usage.loadQuota() } catch { errorMessage.value = '额度信息加载失败，请稍后重试。' }
  await loadLimitUsage()
})
</script>

<template>
  <section class="quota-page" aria-labelledby="quota-title">
    <!-- Page Header -->
    <header class="page-header">
      <div class="page-title">
        <h2 id="quota-title">{{ pageTitle }}</h2>
        <p>{{ pageSubtitle }}</p>
      </div>
      <div class="header-actions">
        <button class="ghost-btn" @click="router.push('/app/logs')">
          <BarChart3 :size="16" /> 用量报告
        </button>
        <button class="primary-btn" @click="router.push('/app/plans')">
          <Zap :size="16" /> {{ quotaIsSeconds ? '购买视频套餐' : '购买积分' }}
        </button>
      </div>
    </header>

    <TopErrorMessage :message="errorMessage" />
    <el-skeleton v-if="usage.loadingQuota" :rows="6" animated />
    <template v-else-if="active">
      <!-- 同时持有多个维度时说明本页数字只覆盖积分（文本）套餐，避免把两个口径读成一个 -->
      <div v-if="activePackages.length > 1" class="multi-package-note" data-test="multi-package-note">
        <strong>你同时持有 {{ activePackages.length }} 个生效套餐</strong>
        <p>本页的总量/已用/剩余只统计文本积分套餐；{{ activeDimensionLabels.join('、') }}各自独立计费，互不挤占。</p>
      </div>

      <!-- 视频套餐：额度单位是秒，用户端不展示视频秒的剩余秒数，因此本页不出现视频秒的数字 -->
      <div v-if="quotaIsSeconds" class="video-quota-note" data-test="video-quota-note">
        <strong>视频套餐按秒计费</strong>
        <p>每次生成按实际生成时长扣减，本页不展示视频秒的剩余秒数。</p>
        <!-- 混合套餐（视频秒 + 音频秒）的语音那半份在下方单独成块 -->
        <p v-if="audioSecondsBuckets.length">语音转写与合成按处理时长扣减，它单独一份，见下方的“语音额度”。</p>
        <p>想核对每次生成消耗了多少，去<a href="/app/logs">调用日志</a>看记录。</p>
      </div>

      <!-- 方案 C 的第二份额度：音频秒。与主桶**并列**的独立区块，绝不与主桶相加。
           audioSeconds 为 null 时这一块整体不渲染：显示「0 秒」等于承诺包含 0 秒语音。 -->
      <section
        v-for="bucket in audioSecondsBuckets"
        :key="bucket.key"
        class="audio-seconds-section"
        data-test="audio-seconds-block"
        :data-test-package="bucket.key"
        :aria-labelledby="`audio-seconds-title-${bucket.key}`"
      >
        <div class="audio-seconds-header">
          <h3 :id="`audio-seconds-title-${bucket.key}`">语音额度（按秒）</h3>
          <p>{{ bucket.label }} · 语音转写与合成按处理时长扣减，与视频秒各自独立、互不挤占。</p>
        </div>
        <div class="metric-grid">
          <div class="metric-card">
            <div class="metric-header">
              <div class="metric-icon metric-icon--blue"><Sigma :size="18" /></div>
              <span class="metric-label">语音总量</span>
            </div>
            <strong class="metric-value" data-test="audio-seconds-total">{{ formatTokens(bucket.total) }}<small>秒</small></strong>
            <div class="metric-bar"><div class="bar-fill bar--blue" style="width: 100%"></div></div>
            <p class="metric-foot">套餐包含的音频秒</p>
          </div>
          <div class="metric-card">
            <div class="metric-header">
              <div class="metric-icon metric-icon--green"><TrendingUp :size="18" /></div>
              <span class="metric-label">语音已用</span>
              <span class="metric-pct" :class="`pct--${statusByPercent(bucket.usedPercent)}`">{{ bucket.usedPercent }}%</span>
            </div>
            <strong class="metric-value" data-test="audio-seconds-used">{{ formatTokens(bucket.used) }}<small>秒</small></strong>
            <div class="metric-bar"><div class="bar-fill" :class="`bar--${statusByPercent(bucket.usedPercent)}`" :style="{ width: bucket.usedPercent + '%' }"></div></div>
            <p class="metric-foot">共 {{ formatTokens(bucket.total) }} · 剩余 {{ formatTokens(bucket.remaining) }}</p>
          </div>
          <div class="metric-card">
            <div class="metric-header">
              <div class="metric-icon metric-icon--amber"><LockKeyhole :size="18" /></div>
              <span class="metric-label">语音预占</span>
              <span class="metric-pct pct--normal">{{ bucket.reservedPercent }}%</span>
            </div>
            <strong class="metric-value" data-test="audio-seconds-reserved">{{ formatTokens(bucket.reserved) }}<small>秒</small></strong>
            <div class="metric-bar"><div class="bar-fill bar--amber" :style="{ width: bucket.reservedPercent + '%' }"></div></div>
            <p class="metric-foot">冻结中 · 请求完成后释放</p>
          </div>
          <div class="metric-card" :class="`card--${bucket.status}`">
            <div class="metric-header">
              <div class="metric-icon" :class="`metric-icon--${bucket.status === 'danger' ? 'red' : bucket.status === 'warning' ? 'amber' : 'purple'}`"><Gauge :size="18" /></div>
              <span class="metric-label">语音剩余</span>
              <span class="metric-pct" :class="`pct--${bucket.status}`">{{ bucket.remainingPercent }}%</span>
            </div>
            <strong class="metric-value" data-test="audio-seconds-remaining">{{ formatTokens(bucket.remaining) }}<small>秒</small></strong>
            <div class="metric-bar"><div class="bar-fill" :class="`bar--${bucket.status}`" :style="{ width: bucket.remainingPercent + '%' }"></div></div>
            <p class="metric-foot">共 {{ formatTokens(bucket.total) }} · 已用 {{ formatTokens(bucket.used) }}</p>
          </div>
        </div>
      </section>

      <!-- Alert Banner（积分口径；视频套餐用上面的说明代替） -->
      <div v-if="showAlertBanner && !quotaIsSeconds" class="alert-banner" role="status" :class="`alert--${overallStatus}`">
        <div class="alert-left">
          <AlertTriangle :size="18" class="alert-icon" />
          <span v-if="overallStatus === 'danger'">本月积分额度已耗尽，请尽快充值或升级套餐。</span>
          <span v-else>本月积分额度已消耗 {{ usedPercent }}%，请注意控制用量。</span>
        </div>
        <button class="alert-btn">立即升级</button>
      </div>

      <!-- Metric Cards（数字都是额度单位：视频套餐不展示，见上面的说明） -->
      <div v-if="!quotaIsSeconds" class="metric-grid">
        <!-- Total -->
        <div class="metric-card">
          <div class="metric-header">
            <div class="metric-icon metric-icon--blue"><Sigma :size="18" /></div>
            <span class="metric-label">总量</span>
          </div>
          <strong class="metric-value">{{ formatTokens(totalTokens) }}<small>积分</small></strong>
          <div class="metric-bar"><div class="bar-fill bar--blue" style="width: 100%"></div></div>
          <p class="metric-foot">套餐总额度</p>
        </div>

        <!-- Used -->
        <div class="metric-card">
          <div class="metric-header">
            <div class="metric-icon metric-icon--green"><TrendingUp :size="18" /></div>
            <span class="metric-label">已用</span>
            <span class="metric-pct" :class="`pct--${statusByPercent(usedPercent)}`">{{ usedPercent }}%</span>
          </div>
          <strong class="metric-value">{{ formatTokens(usedTokens) }}<small>积分</small></strong>
          <div class="metric-bar"><div class="bar-fill" :class="`bar--${statusByPercent(usedPercent)}`" :style="{ width: usedPercent + '%' }"></div></div>
          <p class="metric-foot">共 {{ formatTokens(totalTokens) }} · 剩余 {{ formatTokens(remainingTokens) }}</p>
        </div>

        <!-- Reserved -->
        <div class="metric-card">
          <div class="metric-header">
            <div class="metric-icon metric-icon--amber"><LockKeyhole :size="18" /></div>
            <span class="metric-label">预占</span>
            <span class="metric-pct pct--normal">{{ reservedPercent }}%</span>
          </div>
          <strong class="metric-value">{{ formatTokens(reservedTokens) }}<small>积分</small></strong>
          <div class="metric-bar"><div class="bar-fill bar--amber" :style="{ width: reservedPercent + '%' }"></div></div>
          <p class="metric-foot">冻结中 · 请求完成后释放</p>
        </div>

        <!-- Remaining -->
        <div class="metric-card" :class="`card--${overallStatus}`">
          <div class="metric-header">
            <div class="metric-icon" :class="`metric-icon--${overallStatus === 'danger' ? 'red' : overallStatus === 'warning' ? 'amber' : 'purple'}`"><Gauge :size="18" /></div>
            <span class="metric-label">剩余</span>
            <span class="metric-pct" :class="`pct--${overallStatus}`">{{ remainingPercent }}%</span>
          </div>
          <strong class="metric-value">{{ formatTokens(remainingTokens) }}<small>积分</small></strong>
          <div class="metric-bar"><div class="bar-fill" :class="`bar--${overallStatus}`" :style="{ width: remainingPercent + '%' }"></div></div>
          <p class="metric-foot">共 {{ formatTokens(totalTokens) }} · 已用 {{ formatTokens(usedTokens) }}</p>
        </div>
      </div>

      <!-- 限流生效情况：每个生效中套餐的真实分钟/每日计数与近 24 小时拒绝明细 -->
      <PackageLimitUsagePanel
        v-for="row in limitUsageRows"
        :key="row.key"
        :usage="row.usage"
        :package-label="row.label"
        :loading="limitUsageLoading"
        :unavailable="row.unavailable"
        @refresh="loadLimitUsage"
      />

      <!-- Charts Section（按模型占比是额度口径的分布，视频套餐不展示） -->
      <div v-if="!quotaIsSeconds" class="charts-section">
        <!-- Model Distribution -->
        <div class="chart-card">
          <div class="chart-header">
            <div>
              <h3>按模型占比</h3>
              <p>积分用量分布</p>
            </div>
            <PieChart :size="18" class="chart-header-icon" />
          </div>
          <div class="donut-container" v-if="modelData.length">
            <svg viewBox="0 0 200 200" class="donut-chart">
              <circle cx="100" cy="100" r="70" fill="none" stroke="#f1f5f9" stroke-width="28" />
              <template v-for="(m, idx) in modelData" :key="m.code">
                <circle
                  cx="100" cy="100" r="70" fill="none"
                  :stroke="m.color" stroke-width="28"
                  :stroke-dasharray="`${(m.percent / 100) * 440} 440`"
                  :stroke-dashoffset="modelData.slice(0, idx).reduce((s, x) => s - (x.percent / 100) * 440, 0)"
                  transform="rotate(-90 100 100)"
                  class="donut-segment"
                />
              </template>
            </svg>
            <div class="donut-center">
              <span class="donut-total">{{ formatTokens(usedTokens) }}</span>
              <span class="donut-label">已用积分</span>
            </div>
          </div>
          <div class="legend-list" v-if="modelData.length">
            <div v-for="m in modelData" :key="m.code" class="legend-item">
              <span class="legend-dot" :style="{ background: m.color }"></span>
              <span class="legend-name">{{ m.code }}</span>
              <span class="legend-pct">{{ m.percent }}%</span>
            </div>
          </div>
          <div v-else class="chart-empty">
            <PieChart :size="32" />
            <span>当前套餐未配置模型独立额度</span>
          </div>
        </div>

        <!-- Model Quota Table -->
        <div class="chart-card">
          <div class="chart-header">
            <div>
              <h3>模型独立额度</h3>
              <p>各模型积分使用明细</p>
            </div>
            <BarChart3 :size="18" class="chart-header-icon" />
          </div>
          <div class="table-scroll" v-if="modelData.length">
            <table class="data-table">
              <thead>
                <tr><th>模型</th><th>总量</th><th>预占</th><th>已用</th><th>剩余</th><th>使用率</th></tr>
              </thead>
              <tbody>
                <tr v-for="m in modelData" :key="m.code" class="data-row">
                  <td><code class="model-code">{{ m.code }}</code></td>
                  <td class="cell-num">{{ formatTokens(m.total) }}</td>
                  <td class="cell-num">{{ formatTokens(m.used) }}</td>
                  <td class="cell-num">{{ formatTokens(m.remaining) }}</td>
                  <td class="cell-num">{{ formatTokens(m.remaining) }}</td>
                  <td>
                    <div class="mini-bar">
                      <div class="mini-fill" :style="{ width: Math.min(100, m.total > 0 ? (m.used / m.total) * 100 : 0) + '%', background: m.color }"></div>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="chart-empty">
            <BarChart3 :size="32" />
            <span>暂无模型独立额度数据</span>
          </div>
        </div>
      </div>
    </template>
    <div v-else class="empty-state">
      <WalletCards :size="40" />
      <p>当前没有生效中的套餐</p>
      <button class="primary-btn">前往购买</button>
    </div>
  </section>
</template>

<style scoped>
.quota-page { width: 100%; min-width: 0; }

/* 页头 */
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}
.page-title h2 { margin: 0; font-size: 24px; font-weight: 700; }
.page-title p { margin: 6px 0 0; color: var(--app-muted); font-size: 14px; }
.header-actions { display: flex; gap: 10px; flex-shrink: 0; }
.primary-btn {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 16px; border: 0; border-radius: 8px;
  background: var(--app-primary); color: #fff;
  font: inherit; font-size: 14px; font-weight: 600;
  cursor: pointer; transition: background var(--app-motion-fast) ease;
}
.primary-btn:hover { background: var(--app-primary); }
.ghost-btn {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 16px; border: 1px solid var(--app-border); border-radius: 8px;
  background: var(--app-surface); color: var(--app-muted-strong);
  font: inherit; font-size: 14px; font-weight: 500;
  cursor: pointer; transition: all var(--app-motion-fast) ease;
}
.ghost-btn:hover { border-color: #c7d2fe; color: var(--app-primary); background: #f5f7ff; }

/* 告警横幅 */
.alert-banner {
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px; padding: 12px 18px; border-radius: 10px;
  margin-bottom: 20px; border: 1px solid;
}
.alert--warning {
  background: var(--app-warning-soft); border-color: var(--app-warning-border);
}
.alert--danger {
  background: var(--app-danger-soft); border-color: var(--app-danger-soft);
}
.alert-left { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.alert--warning .alert-icon { color: var(--app-accent); }
.alert--danger .alert-icon { color: var(--app-danger-text); }
.alert-btn {
  padding: 6px 14px; border: 0; border-radius: 6px;
  background: var(--app-primary); color: #fff;
  font: inherit; font-size: 13px; font-weight: 600;
  cursor: pointer; flex-shrink: 0; white-space: nowrap;
  transition: background var(--app-motion-fast) ease;
}
.alert-btn:hover { background: var(--app-primary); }

/* 同时持有多个维度：说明本页数字只覆盖积分套餐，避免两个口径被读成一个 */
.multi-package-note {
  padding: 16px 20px;
  margin-bottom: 20px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 12px;
}
.multi-package-note strong {
  display: block;
  margin-bottom: 6px;
  font-size: 14px;
  color: var(--app-fg);
}
.multi-package-note p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--app-muted-strong);
}

/* 视频套餐：不展示秒数额度，用一段口径说明代替整块数字区 */
.video-quota-note {
  padding: 20px 22px;
  margin-bottom: 20px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-left: 3px solid var(--app-primary);
  border-radius: 12px;
}
.video-quota-note strong {
  display: block;
  margin-bottom: 6px;
  font-size: 15px;
  color: var(--app-fg);
}
.video-quota-note p {
  margin: 0 0 4px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--app-muted-strong);
}
.video-quota-note a { color: var(--app-primary); }

/* 方案 C 的第二份额度（音频秒）：与主桶并列的独立区块，单位恒为秒 */
.audio-seconds-section {
  padding: 18px 20px;
  margin-bottom: 20px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-left: 3px solid var(--app-accent);
  border-radius: 12px;
}
.audio-seconds-header { margin-bottom: 14px; }
.audio-seconds-header h3 { margin: 0; font-size: 15px; font-weight: 700; }
.audio-seconds-header p {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--app-muted);
}
.audio-seconds-section .metric-grid { margin-bottom: 0; }

/* 指标网格 */
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}
.metric-card {
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  transition: all var(--app-motion-fast) ease;
}
.metric-card:hover {
  box-shadow: 0 2px 12px rgb(0 0 0 / 5%);
}
.card--warning { border-color: var(--app-warning-border); background: var(--app-warning-soft); }
.card--danger { border-color: var(--app-danger-soft); background: var(--app-danger-soft); }
.metric-header {
  display: flex; align-items: center; gap: 8px;
}
.metric-icon {
  display: flex; align-items: center; justify-content: center;
  width: 32px; height: 32px; border-radius: 8px; flex-shrink: 0;
}
.metric-icon--blue { color: var(--app-primary); background: var(--app-primary-soft); }
.metric-icon--green { color: var(--app-success); background: #d1fae5; }
.metric-icon--amber { color: var(--app-accent); background: var(--app-accent-soft); }
.metric-icon--purple { color: var(--app-primary); background: var(--app-primary-soft); }
.metric-icon--red { color: var(--app-danger-text); background: var(--app-danger-soft); }
.metric-label { font-size: 13px; color: var(--app-muted); font-weight: 500; }
.metric-pct {
  margin-left: auto; font-size: 13px; font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.pct--normal { color: var(--app-success); }
.pct--warning { color: var(--app-accent); }
.pct--danger { color: var(--app-danger-text); }
.metric-value {
  font-size: 26px; font-weight: 800; color: var(--app-fg);
  font-variant-numeric: tabular-nums; letter-spacing: -0.5px;
}
.metric-value small {
  margin-left: 5px; font-size: 12px; font-weight: 500; color: var(--app-muted);
}
.metric-bar {
  height: 6px; border-radius: 3px; background: var(--app-surface-muted); overflow: hidden;
}
.bar-fill {
  height: 100%; border-radius: 3px;
  transition: width 0.6s ease;
}
.bar--normal { background: var(--app-success); }
.bar--warning { background: var(--app-accent); }
.bar--danger { background: var(--app-danger-text); }
.bar--blue { background: var(--app-primary); }
.bar--amber { background: var(--app-accent); }
.bar--purple { background: var(--app-primary); }
.metric-foot {
  margin: 0; font-size: 12px; color: var(--app-muted);
}

/* 图表区块 */
.charts-section {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.chart-card {
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  overflow: hidden;
}
.chart-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--app-border);
}
.chart-header h3 { margin: 0; font-size: 15px; font-weight: 700; }
.chart-header p { margin: 4px 0 0; font-size: 12px; color: var(--app-muted); }
.chart-header-icon { color: var(--app-muted); flex-shrink: 0; }

/* 环形图 */
.donut-container {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.donut-chart {
  width: 180px; height: 180px;
}
.donut-segment {
  transition: stroke-dasharray 0.6s ease;
}
.donut-center {
  position: absolute;
  top: 50%; left: 50%;
  transform: translate(-50%, -50%);
  display: flex; flex-direction: column; align-items: center;
}
.donut-total {
  font-size: 20px; font-weight: 800; color: var(--app-fg);
  font-variant-numeric: tabular-nums;
}
.donut-label {
  font-size: 11px; color: var(--app-muted); margin-top: 2px;
}

/* 图例 */
.legend-list {
  padding: 0 20px 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.legend-dot {
  width: 10px; height: 10px; border-radius: 3px; flex-shrink: 0;
}
.legend-name { color: var(--app-fg); flex: 1; }
.legend-pct { font-weight: 600; color: var(--app-muted); font-variant-numeric: tabular-nums; }

/* 表格 */
.table-scroll { overflow-x: auto; }
.data-table {
  width: 100%; border-collapse: collapse; font-size: 13px;
}
.data-table th, .data-table td {
  padding: 10px 16px; border-bottom: 1px solid var(--app-border);
  text-align: left; white-space: nowrap; height: 44px;
}
.data-table th {
  color: var(--app-muted); font-size: 12px; font-weight: 600;
  background: var(--app-surface-subtle); text-transform: uppercase; letter-spacing: 0.3px;
}
.data-row { transition: background var(--app-motion-fast) ease; }
.data-row:hover { background: var(--app-surface-subtle); }
.model-code { color: var(--app-primary); font-weight: 600; }
.cell-num { font-variant-numeric: tabular-nums; font-weight: 600; }
.mini-bar {
  width: 60px; height: 5px; border-radius: 3px;
  background: var(--app-surface-muted); overflow: hidden;
}
.mini-fill {
  height: 100%; border-radius: 3px;
  transition: width 0.6s ease;
}

/* 空状态 */
.chart-empty {
  display: flex; flex-direction: column; align-items: center;
  gap: 8px; padding: 40px 20px; color: var(--app-muted); font-size: 13px;
}
.empty-state {
  display: flex; flex-direction: column; align-items: center;
  gap: 12px; padding: 60px 20px; color: var(--app-muted);
  background: var(--app-surface); border: 1px solid var(--app-border);
  border-radius: 12px;
}
.empty-state p { margin: 0; }

/* 响应式 */
@media (max-width: 1080px) {
  .charts-section { grid-template-columns: 1fr; }
}
@media (max-width: 768px) {
  .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .page-header { flex-direction: column; }
  .header-actions { width: 100%; }
  .header-actions button { flex: 1; justify-content: center; }
}
@media (max-width: 480px) {
  .metric-grid { grid-template-columns: 1fr; }
}
</style>
