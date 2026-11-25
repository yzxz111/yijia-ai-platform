<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Ban, CheckCircle2, Download, Eye, RefreshCw, Search, ShieldCheck, ShieldAlert, ScanSearch, Gavel } from 'lucide-vue-next'
import { contentSafetyApi, type SafetyAppeal, type SafetyCase } from '../../api/contentSafety'
import { adminDrillQuery } from '../../router/query'
import { formatDateTime } from '../../utils/dateTime'

const route = useRoute()
const drillSearch = ref(adminDrillQuery(route.query, [], '').search)
const cases = ref<SafetyCase[]>([])
const appeals = ref<SafetyAppeal[]>([])
const caseLoading = ref(false)
const appealLoading = ref(false)
const activeTab = ref<'cases' | 'appeals'>('cases')
const pageSizeOptions = [5, 10, 20]
const casePageSize = ref(20)
const appealPageSize = ref(20)
const caseStatus = ref('')
const appealStatus = ref('')
const exporting = ref(false)
const casePage = ref(0)
const caseTotal = ref(0)
const appealPage = ref(0)
const appealTotal = ref(0)
const selectedCase = ref<SafetyCase | null>(null)
const detailOpen = ref(false)
const decisionOpen = ref(false)
const resolutionOpen = ref(false)
const decision = ref<'CONFIRMED' | 'DISMISSED'>('CONFIRMED')
const decisionNote = ref('')
const resolutionAction = ref<'ACCOUNT_BANNED' | 'RECORDED'>('ACCOUNT_BANNED')
const resolutionNote = ref('')
const caseActionLoading = ref(false)

/** 风险等级 → 色板音调（与 token 一一对应） */
const severityTone: Record<string, string> = {
  LOW: 'low',
  MEDIUM: 'medium',
  HIGH: 'high',
  CRITICAL: 'critical',
}

const severityToneOf = (severity?: string) => severityTone[severity || ''] || 'low'

/** 全量违规记录：用于全局统计与风险分布（与分页表格数据解耦） */
const allCases = ref<SafetyCase[]>([])
const overviewLoading = ref(false)

/** 拉取全量违规记录（用于安全看板全局口径，翻页聚合直到取完） */
async function loadOverview() {
  overviewLoading.value = true
  try {
    const collected: SafetyCase[] = []
    let page = 0
    let total = 0
    do {
      const response = await contentSafetyApi.cases({ page, size: 100 })
      const items = response.data.items || []
      collected.push(...items)
      total = response.data.total
      page += 1
      // 防御：若某页返回空列表，说明已到末尾或后端异常，终止聚合避免死循环
      if (items.length === 0) break
    } while (collected.length < total)
    allCases.value = collected
  } catch {
    /* 概览拉取失败时保留旧数据，不阻塞主表格 */
  } finally {
    overviewLoading.value = false
  }
}

/** 安全统计：基于全量记录聚合，反映全局口径 */
const safetyStats = computed(() => {
  const total = allCases.value.length
  let blocked = 0
  let highRisk = 0
  let manualReview = 0
  allCases.value.forEach((item) => {
    if (item.action === 'BLOCK') blocked += 1
    if (item.severity === 'HIGH' || item.severity === 'CRITICAL') highRisk += 1
    if (item.action === 'REVIEW' || item.status === 'OPEN' || item.status === 'INVESTIGATING') manualReview += 1
  })
  return { total, blocked, highRisk, manualReview }
})

/** 风险等级分布：基于全量记录的四段占比，用于环形图 */
const severityDistribution = computed(() => {
  const buckets: Record<string, number> = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0 }
  allCases.value.forEach((item) => { if (buckets[item.severity] !== undefined) buckets[item.severity] += 1 })
  const sum = Object.values(buckets).reduce((acc, value) => acc + value, 0) || 1
  return [
    { key: 'CRITICAL', label: '严重', value: buckets.CRITICAL, ratio: buckets.CRITICAL / sum, color: 'var(--risk-critical)' },
    { key: 'HIGH', label: '高', value: buckets.HIGH, ratio: buckets.HIGH / sum, color: 'var(--risk-high)' },
    { key: 'MEDIUM', label: '中', value: buckets.MEDIUM, ratio: buckets.MEDIUM / sum, color: 'var(--risk-medium)' },
    { key: 'LOW', label: '低', value: buckets.LOW, ratio: buckets.LOW / sum, color: 'var(--risk-low)' },
  ]
})

/** 环形图描边：累计角度生成 SVG stroke-dasharray 段 */
const donutSegments = computed(() => {
  const C = 2 * Math.PI * 42
  let offset = 0
  return severityDistribution.value
    .filter((seg) => seg.value > 0)
    .map((seg) => {
      const length = seg.ratio * C
      const segment = { ...seg, dash: `${length} ${C - length}`, offset: -offset }
      offset += length
      return segment
    })
})

/** 最近高风险内容：全量记录内高/严重等级，按时间倒序取前 5 */
const recentHighRisk = computed(() => {
  return allCases.value
    .filter((item) => item.severity === 'HIGH' || item.severity === 'CRITICAL')
    .slice()
    .sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || ''))
    .slice(0, 5)
})

/** 用户历史风险记录：全量记录内同一用户的其他违规记录 */
const userHistoryCases = computed(() => {
  const id = selectedCase.value?.userId
  if (!id) return []
  return allCases.value
    .filter((item) => item.userId === id && item.caseNo !== selectedCase.value?.caseNo)
    .slice(0, 5)
})

/** 相似风险内容：全量记录内同类风险类型 + 相同等级的其他记录 */
const similarRiskCases = computed(() => {
  const sel = selectedCase.value
  if (!sel) return []
  return allCases.value
    .filter((item) => item.caseNo !== sel.caseNo && (item.category === sel.category || item.severity === sel.severity))
    .slice(0, 4)
})

const caseStatusLabels: Record<string, string> = {
  OPEN: '待审核',
  INVESTIGATING: '审核中',
  CONFIRMED: '确认违规，待处置',
  DISMISSED: '已判定无违规',
  RESOLVED: '已结案',
}

const appealStatusLabels: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回',
}

const severityLabels: Record<string, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  CRITICAL: '严重',
}

const categoryLabels: Record<string, string> = {
  SENSITIVE_CONTENT: '敏感内容',
  PROMPT_INJECTION: '提示词注入',
  MALWARE: '恶意文件',
  UNSAFE_FILE: '风险文件',
  POLICY: '策略违规',
  UNSAFE_CONTENT: '违规内容',
  SAFETY: '安全风险',
}

const actionLabels: Record<string, string> = {
  ALLOW: '放行',
  REVIEW: '人工复核',
  BLOCK: '拦截',
}

const directionLabels: Record<string, string> = {
  INPUT: '输入',
  OUTPUT: '输出',
  FILE: '附件',
}

const enforcementStateLabels: Record<string, string> = {
  FROZEN: '已冻结',
  BANNED: '已封禁',
  CLEARED: '正常',
}

const resolutionActionLabels: Record<string, string> = {
  FALSE_POSITIVE: '判定无违规',
  ACCOUNT_BANNED: '封禁账号并结案',
  RECORDED: '仅记录并结案',
}

function labelOf(labels: Record<string, string>, value?: string) {
  return value ? labels[value] || value : '-'
}

function caseStatusType(status: string) {
  if (status === 'OPEN') return 'warning'
  if (status === 'INVESTIGATING') return 'primary'
  if (status === 'CONFIRMED') return 'danger'
  if (status === 'DISMISSED' || status === 'RESOLVED') return 'success'
  return 'info'
}

function appealStatusType(status: string) {
  if (status === 'PENDING') return 'warning'
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'info'
  if (status === 'WITHDRAWN') return 'info'
  return 'info'
}

function formatDate(value?: string) {
  return formatDateTime(value)
}

function enforcementStateType(state?: string) {
  if (state === 'BANNED') return 'danger'
  if (state === 'FROZEN') return 'warning'
  return 'success'
}

function resolutionActionLabel(action?: string) {
  return action ? resolutionActionLabels[action] || action : '待处置'
}

function primaryCaseActionLabel(item?: SafetyCase) {
  if (item?.status === 'OPEN') return '开始审核'
  if (item?.status === 'INVESTIGATING') return '提交审核结论'
  if (item?.status === 'CONFIRMED') return item.enforcementState === 'BANNED' ? '确认结案' : '进入账号处置'
  return ''
}

function openCaseDetail(item: SafetyCase) {
  selectedCase.value = item
  detailOpen.value = true
}

function refreshSelectedCase() {
  if (!selectedCase.value) return
  const refreshed = cases.value.find(item => item.caseNo === selectedCase.value?.caseNo)
  if (refreshed) selectedCase.value = refreshed
}

function openDecisionDialog() {
  decision.value = 'CONFIRMED'
  decisionNote.value = ''
  decisionOpen.value = true
}

function openResolutionDialog() {
  resolutionAction.value = selectedCase.value?.enforcementState === 'BANNED' ? 'RECORDED' : 'ACCOUNT_BANNED'
  resolutionNote.value = ''
  resolutionOpen.value = true
}

async function loadCases() {
  caseLoading.value = true
  try {
    const response = await contentSafetyApi.cases({
      page: casePage.value,
      size: casePageSize.value,
      query: drillSearch.value,
      status: caseStatus.value,
    })
    cases.value = response.data.items
    caseTotal.value = response.data.total
    casePage.value = response.data.page
  } finally {
    caseLoading.value = false
  }
}

async function loadAppeals() {
  appealLoading.value = true
  try {
    const response = await contentSafetyApi.adminAppeals({
      page: appealPage.value,
      size: appealPageSize.value,
      query: drillSearch.value,
      status: appealStatus.value,
    })
    appeals.value = response.data.items
    appealTotal.value = response.data.total
    appealPage.value = response.data.page
  } finally {
    appealLoading.value = false
  }
}

async function load() {
  try {
    await Promise.all([loadCases(), loadAppeals()])
  } catch {
    ElMessage.error('内容安全记录加载失败')
  }
}

async function changeCasePage(page: number) {
  casePage.value = page - 1
  try {
    await loadCases()
  } catch {
    ElMessage.error('违规记录加载失败')
  }
}

async function changeAppealPage(page: number) {
  appealPage.value = page - 1
  try {
    await loadAppeals()
  } catch {
    ElMessage.error('申诉记录加载失败')
  }
}

async function transition(item: SafetyCase, status: string, note = '') {
  caseActionLoading.value = true
  try {
    await contentSafetyApi.transition(item.caseNo, status, note)
    ElMessage.success(status === 'INVESTIGATING' ? '案件已进入审核' : status === 'CONFIRMED' ? '已确认违规' : '已判定无违规')
    await loadCases()
    refreshSelectedCase()
    decisionOpen.value = false
  } catch {
    ElMessage.error('审核结论提交失败')
  } finally {
    caseActionLoading.value = false
  }
}

async function resolveSelectedCase() {
  if (!selectedCase.value) return
  caseActionLoading.value = true
  try {
    await contentSafetyApi.resolve(selectedCase.value.caseNo, resolutionAction.value, resolutionNote.value)
    ElMessage.success(resolutionActionLabel(resolutionAction.value))
    await loadCases()
    refreshSelectedCase()
    resolutionOpen.value = false
  } catch {
    ElMessage.error('案件结案失败')
  } finally {
    caseActionLoading.value = false
  }
}

async function processAppeal(item: SafetyAppeal, status: string) {
  try {
    await contentSafetyApi.processAppeal(item.businessNo, status, appealStatusLabels[status] || '')
    ElMessage.success('申诉已处理')
    await loadAppeals()
  } catch {
    ElMessage.error('申诉处理失败')
  }
}

function changeCasePageSize(size: number) { casePageSize.value = size; casePage.value = 0; void loadCases() }
function changeAppealPageSize(size: number) { appealPageSize.value = size; appealPage.value = 0; void loadAppeals() }
function searchCurrent() {
  if (activeTab.value === 'cases') { casePage.value = 0; void loadCases() }
  else { appealPage.value = 0; void loadAppeals() }
}
function clearFilters() {
  drillSearch.value = ''
  if (activeTab.value === 'cases') { caseStatus.value = ''; casePage.value = 0; void loadCases() }
  else { appealStatus.value = ''; appealPage.value = 0; void loadAppeals() }
}

function csvCell(value: unknown) {
  return `"${String(value ?? '').replaceAll('"', '""')}"`
}

function downloadCsv(filename: string, rows: unknown[][]) {
  const blob = new Blob([`\uFEFF${rows.map(row => row.map(csvCell).join(',')).join('\n')}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}

async function exportCurrent() {
  exporting.value = true
  try {
    if (activeTab.value === 'cases') {
      const all: SafetyCase[] = []
      let page = 0
      let total = 0
      do {
        const response = await contentSafetyApi.cases({ page, size: 100, query: drillSearch.value, status: caseStatus.value })
        all.push(...response.data.items); total = response.data.total; page += 1
      } while (all.length < total)
      downloadCsv('违规记录.csv', [['记录编号', '方向', '等级', '类型', '处置', '状态', '脱敏内容', '时间'], ...all.map(item => [item.caseNo, labelOf(directionLabels, item.direction), labelOf(severityLabels, item.severity), labelOf(categoryLabels, item.category), labelOf(actionLabels, item.action), labelOf(caseStatusLabels, item.status), item.redactedExcerpt || '', formatDate(item.createdAt)])])
    } else {
      const all: SafetyAppeal[] = []
      let page = 0
      let total = 0
      do {
        const response = await contentSafetyApi.adminAppeals({ page, size: 100, query: drillSearch.value, status: appealStatus.value })
        all.push(...response.data.items); total = response.data.total; page += 1
      } while (all.length < total)
      downloadCsv('申诉记录.csv', [['申诉编号', '关联记录', '用户', '状态', '申诉理由', '审核备注'], ...all.map(item => [item.businessNo, item.caseId, item.userId, labelOf(appealStatusLabels, item.status), item.reason, item.processingNote || ''])])
    }
    ElMessage.success('导出已生成')
  } catch {
    ElMessage.error('导出失败，请稍后重试')
  } finally {
    exporting.value = false
  }
}

async function withdrawAppeal(item: SafetyAppeal) {
  if (item.status !== 'PENDING') return
  try {
    await contentSafetyApi.withdrawAdminAppeal(item.businessNo)
    ElMessage.success('申诉已撤回')
    await loadAppeals()
  } catch {
    ElMessage.error('申诉撤回失败')
  }
}

onMounted(() => { void load(); void loadOverview() })
watch(() => route.query, (query) => {
  drillSearch.value = adminDrillQuery(query, [], '').search
  casePage.value = 0
  appealPage.value = 0
  void load()
}, { deep: true })
</script>

<template>
  <section class="safety-page page-stack">
    <header class="safety-head">
      <div class="safety-head__title">
        <p class="safety-head__eyebrow">TRUST &amp; SAFETY · 内容安全治理</p>
        <h1>内容安全</h1>
        <p class="safety-head__sub">识别风险内容、跟踪拦截与人工审核，闭环处置每一例安全事件。</p>
      </div>
      <div class="safety-actions">
        <el-button :loading="activeTab === 'cases' ? caseLoading : appealLoading" @click="activeTab === 'cases' ? loadCases() : loadAppeals()"><RefreshCw :size="16" />刷新</el-button>
        <el-button :loading="exporting" @click="exportCurrent"><Download :size="16" />导出当前页签</el-button>
      </div>
    </header>

    <!-- 顶部：安全统计带（全量口径） -->
    <section class="safety-stats" aria-label="安全统计" v-loading="overviewLoading">
      <article class="safety-stat safety-stat--total">
        <p class="safety-stat__label"><ScanSearch :size="14" />检测总数</p>
        <strong class="safety-stat__value">{{ safetyStats.total.toLocaleString() }}</strong>
        <p class="safety-stat__hint">全量违规记录</p>
      </article>
      <article class="safety-stat safety-stat--blocked">
        <p class="safety-stat__label"><Ban :size="14" />拦截数量</p>
        <strong class="safety-stat__value">{{ safetyStats.blocked.toLocaleString() }}</strong>
        <p class="safety-stat__hint">系统拦截记录</p>
      </article>
      <article class="safety-stat safety-stat--high">
        <p class="safety-stat__label"><ShieldAlert :size="14" />高风险数量</p>
        <strong class="safety-stat__value">{{ safetyStats.highRisk.toLocaleString() }}</strong>
        <p class="safety-stat__hint">高 / 严重等级记录</p>
      </article>
      <article class="safety-stat safety-stat--review">
        <p class="safety-stat__label"><Gavel :size="14" />人工审核数量</p>
        <strong class="safety-stat__value">{{ safetyStats.manualReview.toLocaleString() }}</strong>
        <p class="safety-stat__hint">待复核 / 审核中记录</p>
      </article>
    </section>

    <!-- 风险分布环形图（全量口径，纯 SVG，不引图表库） -->
    <section class="safety-overview" aria-label="风险等级分布" v-loading="overviewLoading">
      <div class="safety-donut-card">
        <div class="safety-donut">
          <svg viewBox="0 0 100 100" role="img" aria-label="风险等级分布环形图">
            <circle cx="50" cy="50" r="42" fill="none" stroke="var(--app-surface-muted)" stroke-width="12" />
            <circle
              v-for="seg in donutSegments"
              :key="seg.key"
              cx="50" cy="50" r="42" fill="none"
              :stroke="seg.color" stroke-width="12"
              :stroke-dasharray="seg.dash" :stroke-dashoffset="seg.offset"
              stroke-linecap="butt" transform="rotate(-90 50 50)"
            />
          </svg>
          <div class="safety-donut__center">
            <strong>{{ severityDistribution.reduce((acc, seg) => acc + seg.value, 0) }}</strong>
            <span>全部记录</span>
          </div>
        </div>
        <ul class="safety-legend">
          <li v-for="seg in severityDistribution" :key="seg.key">
            <span class="safety-legend__dot" :style="{ background: seg.color }" />
            <span class="safety-legend__label">{{ seg.label }}风险</span>
            <span class="safety-legend__count">{{ seg.value }}</span>
          </li>
        </ul>
      </div>

      <!-- 最近高风险内容 -->
      <div class="safety-highrisk">
        <header><h3><ShieldAlert :size="14" />最近高风险内容</h3></header>
        <ul v-if="recentHighRisk.length" class="safety-highrisk__list">
          <li v-for="item in recentHighRisk" :key="item.caseNo" class="safety-highrisk__item">
            <button type="button" @click="openCaseDetail(item)">
              <span class="safety-risk-tag" :class="`safety-risk-tag--${severityToneOf(item.severity)}`">{{ labelOf(severityLabels, item.severity) }}</span>
              <span class="safety-highrisk__excerpt">{{ item.redactedExcerpt || '（内容已脱敏）' }}</span>
              <span class="safety-highrisk__meta"><code class="ops-mono">{{ item.caseNo }}</code><time class="ops-mono">{{ formatDate(item.createdAt) }}</time></span>
            </button>
          </li>
        </ul>
        <p v-else class="safety-highrisk__empty">暂无高风险内容</p>
      </div>
    </section>

    <el-tabs v-model="activeTab" class="safety-tabs">
      <el-tab-pane label="违规记录" name="cases" />
      <el-tab-pane label="申诉记录" name="appeals" />
    </el-tabs>
    <div class="admin-toolbar safety-filters"><el-input v-model="drillSearch" clearable placeholder="搜索记录编号、申诉编号或用户" @keyup.enter="searchCurrent" /><el-select v-if="activeTab === 'cases'" v-model="caseStatus" clearable placeholder="全部状态"><el-option v-for="(label, value) in caseStatusLabels" :key="value" :label="label" :value="value" /></el-select><el-select v-else v-model="appealStatus" clearable placeholder="全部状态"><el-option v-for="(label, value) in appealStatusLabels" :key="value" :label="label" :value="value" /></el-select><el-button @click="clearFilters">清空</el-button><el-button type="primary" @click="searchCurrent"><Search :size="16" />查询</el-button></div>

    <div class="content-safety-sections">
    <section v-show="activeTab === 'cases'" v-loading="caseLoading" data-test="safety-cases" class="content-safety-card">
      <el-table :data="cases" size="small">
        <el-table-column prop="caseNo" label="记录编号" width="180" />
        <el-table-column label="方向" width="72">
          <template #default="scope">{{ labelOf(directionLabels, scope.row.direction) }}</template>
        </el-table-column>
        <el-table-column label="等级" width="88">
          <template #default="scope">
            <span class="safety-risk-tag" :class="`safety-risk-tag--${severityToneOf(scope.row.severity)}`">{{ labelOf(severityLabels, scope.row.severity) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="scope">{{ labelOf(categoryLabels, scope.row.category) }}</template>
        </el-table-column>
        <el-table-column label="处置" width="90">
          <template #default="scope">{{ labelOf(actionLabels, scope.row.action) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="112">
          <template #default="scope">
            <el-tag :type="caseStatusType(scope.row.status)">{{ labelOf(caseStatusLabels, scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="账号状态" width="94">
          <template #default="scope">
            <el-tag size="small" :type="enforcementStateType(scope.row.enforcementState)">{{ labelOf(enforcementStateLabels, scope.row.enforcementState || 'CLEARED') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="redactedExcerpt" label="脱敏内容" min-width="150" show-overflow-tooltip />
        <el-table-column label="时间" width="135">
          <template #default="scope">{{ formatDate(scope.row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="96">
          <template #default="scope">
            <div class="case-row-actions">
              <el-button size="small" @click="openCaseDetail(scope.row)">
                <Eye :size="15" aria-hidden="true" />详情
              </el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>暂无违规记录</template>
      </el-table>
      <el-pagination
        class="content-safety-pagination"
        background
        layout="total, sizes, prev, pager, next"
        :current-page="casePage + 1"
        :page-size="casePageSize"
        :page-sizes="pageSizeOptions"
        :total="caseTotal"
        @size-change="changeCasePageSize"
        @current-change="changeCasePage"
      />
    </section>

    <section v-show="activeTab === 'appeals'" v-loading="appealLoading" data-test="safety-appeals" class="content-safety-card">
      <el-table :data="appeals" size="small">
        <el-table-column prop="businessNo" label="申诉编号" width="190" />
        <el-table-column label="状态" width="120">
          <template #default="scope">
            <el-tag :type="appealStatusType(scope.row.status)">{{ labelOf(appealStatusLabels, scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="申诉理由" show-overflow-tooltip />
        <el-table-column prop="processingNote" label="审核备注" show-overflow-tooltip />
        <el-table-column label="操作" width="250">
          <template #default="scope">
            <template v-if="scope.row.status === 'PENDING'">
              <el-button size="small" type="success" @click="processAppeal(scope.row, 'APPROVED')">通过</el-button>
              <el-button size="small" @click="processAppeal(scope.row, 'REJECTED')">驳回</el-button>
              <el-button size="small" type="warning" @click="withdrawAppeal(scope.row)">撤回申诉</el-button>
            </template>
            <span v-else>已处理</span>
          </template>
        </el-table-column>
        <template #empty>暂无申诉记录</template>
      </el-table>
      <el-pagination
        class="content-safety-pagination"
        background
        layout="total, sizes, prev, pager, next"
        :current-page="appealPage + 1"
        :page-size="appealPageSize"
        :page-sizes="pageSizeOptions"
        :total="appealTotal"
        @size-change="changeAppealPageSize"
        @current-change="changeAppealPage"
      />
    </section>
    </div>

    <el-drawer v-model="detailOpen" title="内容安全详情" size="min(560px, 100%)">
      <template v-if="selectedCase">
        <div class="case-detail-heading">
          <div>
            <span class="case-detail-label">记录编号</span>
            <code>{{ selectedCase.caseNo }}</code>
          </div>
          <div class="case-detail-tags">
            <el-tag :type="caseStatusType(selectedCase.status)">{{ labelOf(caseStatusLabels, selectedCase.status) }}</el-tag>
            <el-tag :type="enforcementStateType(selectedCase.enforcementState)">{{ labelOf(enforcementStateLabels, selectedCase.enforcementState || 'CLEARED') }}</el-tag>
          </div>
        </div>

        <dl class="case-detail-list">
          <div><dt>用户编号</dt><dd><code>{{ selectedCase.userId }}</code></dd></div>
          <div><dt>请求编号</dt><dd><code>{{ selectedCase.requestId }}</code></dd></div>
          <div><dt>方向</dt><dd>{{ labelOf(directionLabels, selectedCase.direction) }}</dd></div>
          <div><dt>风险等级</dt><dd>{{ labelOf(severityLabels, selectedCase.severity) }}</dd></div>
          <div><dt>风险类型</dt><dd>{{ labelOf(categoryLabels, selectedCase.category) }}</dd></div>
          <div><dt>系统处置</dt><dd>{{ labelOf(actionLabels, selectedCase.action) }}</dd></div>
          <div><dt>策略版本</dt><dd>{{ selectedCase.policyVersion }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ formatDate(selectedCase.createdAt) }}</dd></div>
          <div v-if="selectedCase.reviewedAt"><dt>审核时间</dt><dd>{{ formatDate(selectedCase.reviewedAt) }}</dd></div>
        </dl>

        <section class="case-detail-section">
          <h3>脱敏内容</h3>
          <p class="case-detail-excerpt">{{ selectedCase.redactedExcerpt || '暂无脱敏内容' }}</p>
        </section>

        <section class="case-detail-section">
          <h3>审核意见</h3>
          <p v-if="selectedCase.reviewNote" class="case-detail-note">{{ selectedCase.reviewNote }}</p>
          <p v-else class="case-detail-muted">暂无审核意见，完成审核后可在此补充判断依据。</p>
        </section>

        <section v-if="userHistoryCases.length" class="case-detail-section">
          <h3>用户历史风险记录<span class="case-detail-count">{{ userHistoryCases.length }}</span></h3>
          <ul class="case-detail-rel">
            <li v-for="item in userHistoryCases" :key="item.caseNo">
              <button type="button" @click="openCaseDetail(item)">
                <span class="safety-risk-tag" :class="`safety-risk-tag--${severityToneOf(item.severity)}`">{{ labelOf(severityLabels, item.severity) }}</span>
                <span class="case-detail-rel__excerpt">{{ item.redactedExcerpt || '（内容已脱敏）' }}</span>
                <time class="ops-mono">{{ formatDate(item.createdAt) }}</time>
              </button>
            </li>
          </ul>
        </section>

        <section v-if="similarRiskCases.length" class="case-detail-section">
          <h3>相似风险内容</h3>
          <ul class="case-detail-rel">
            <li v-for="item in similarRiskCases" :key="item.caseNo">
              <button type="button" @click="openCaseDetail(item)">
                <span class="case-detail-rel__cat">{{ labelOf(categoryLabels, item.category) }}</span>
                <span class="case-detail-rel__excerpt">{{ item.redactedExcerpt || '（内容已脱敏）' }}</span>
                <code class="ops-mono">{{ item.caseNo }}</code>
              </button>
            </li>
          </ul>
        </section>

        <section class="case-detail-section">
          <h3>处理结果</h3>
          <p class="case-detail-result">{{ resolutionActionLabel(selectedCase.resolutionAction) }}</p>
          <p v-if="selectedCase.reviewNote" class="case-detail-note">{{ selectedCase.reviewNote }}</p>
        </section>

        <div v-if="primaryCaseActionLabel(selectedCase)" class="case-detail-footer-action">
          <p class="case-detail-hint">
            {{ selectedCase.status === 'OPEN' ? '案件尚未进入人工审核。' : selectedCase.status === 'INVESTIGATING' ? '审核完成后请选择最终审核结论。' : selectedCase.enforcementState === 'BANNED' ? '账号已封禁，请确认案件结案。' : '确认违规后请选择账号处置方式并结案。' }}
          </p>
          <el-button
            v-if="selectedCase.status === 'OPEN'"
            type="primary"
            :loading="caseActionLoading"
            @click="transition(selectedCase, 'INVESTIGATING')"
          ><ShieldCheck :size="15" aria-hidden="true" />开始审核</el-button>
          <el-button v-else-if="selectedCase.status === 'INVESTIGATING'" type="primary" @click="openDecisionDialog"><CheckCircle2 :size="15" aria-hidden="true" />提交审核结论</el-button>
          <el-button v-else :type="selectedCase.enforcementState === 'BANNED' ? 'primary' : 'danger'" @click="openResolutionDialog"><CheckCircle2 v-if="selectedCase.enforcementState === 'BANNED'" :size="15" aria-hidden="true" /><Ban v-else :size="15" aria-hidden="true" />{{ primaryCaseActionLabel(selectedCase) }}</el-button>
        </div>
      </template>
      <el-empty v-else description="暂无案件详情" />
    </el-drawer>

    <el-dialog v-model="decisionOpen" title="提交审核结论" width="min(460px, calc(100vw - 32px))">
      <div class="case-action-form">
        <label>审核结论<el-select v-model="decision"><el-option label="确认违规" value="CONFIRMED" /><el-option label="判定无违规" value="DISMISSED" /></el-select></label>
        <label>审核备注<el-input v-model="decisionNote" type="textarea" :rows="4" maxlength="1000" placeholder="填写本次审核的判断依据（可选）" /></label>
      </div>
      <template #footer><el-button @click="decisionOpen = false">取消</el-button><el-button type="primary" :loading="caseActionLoading" @click="selectedCase && transition(selectedCase, decision, decisionNote)">提交结论</el-button></template>
    </el-dialog>

    <el-dialog v-model="resolutionOpen" title="账号处置与结案" width="min(460px, calc(100vw - 32px))">
      <div class="case-action-form">
        <label>结案方式<el-select v-model="resolutionAction"><el-option v-if="selectedCase?.enforcementState !== 'BANNED'" label="封禁账号并结案" value="ACCOUNT_BANNED" /><el-option label="仅记录并结案" value="RECORDED" /></el-select></label>
        <label>处理备注<el-input v-model="resolutionNote" type="textarea" :rows="4" maxlength="1000" placeholder="填写处置原因或结案说明（可选）" /></label>
      </div>
      <template #footer><el-button @click="resolutionOpen = false">取消</el-button><el-button :type="resolutionAction === 'ACCOUNT_BANNED' ? 'danger' : 'primary'" :loading="caseActionLoading" @click="resolveSelectedCase">确认结案</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
/* =========================================================
   内容安全页 — 安全治理后台（克制专业，无刺眼红警）
   风险靠「色轨 + 标签 + 文字」三重编码，不靠大面积警示色
   ========================================================= */
.safety-page { position: relative; max-width: 1520px; }
.ops-mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; letter-spacing: -0.01em; }

/* ===== 页头 ===== */
.safety-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 16px; }
.safety-head__eyebrow { margin: 0 0 4px; font-family: var(--font-mono); font-size: 11px; font-weight: 500; letter-spacing: 0.18em; color: var(--app-muted); }
.safety-head h1 { margin: 0; font-family: var(--font-display); font-size: 27px; font-weight: 400; letter-spacing: -0.02em; color: var(--app-fg); }
.safety-head__sub { margin: 6px 0 0; max-width: 62ch; font-size: 12.5px; line-height: 1.65; color: var(--app-muted-strong); }

/* ===== 安全统计带：左侧色轨 + 等宽大数字 ===== */
.safety-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.safety-stat { display: grid; gap: 2px; padding: 12px 14px 12px 16px; background: var(--app-surface); border: 1px solid var(--app-border); border-left: 3px solid var(--app-border-strong); border-radius: 10px; }
.safety-stat--total { border-left-color: var(--app-primary); background: linear-gradient(90deg, var(--app-primary-soft) 0%, var(--app-surface) 42%); }
.safety-stat--blocked { border-left-color: var(--risk-critical-rail); background: linear-gradient(90deg, var(--risk-critical-soft) 0%, var(--app-surface) 42%); }
.safety-stat--high { border-left-color: var(--risk-high-rail); background: linear-gradient(90deg, var(--risk-high-soft) 0%, var(--app-surface) 42%); }
.safety-stat--review { border-left-color: var(--risk-medium-rail); background: linear-gradient(90deg, var(--risk-medium-soft) 0%, var(--app-surface) 42%); }
.safety-stat__label { display: flex; align-items: center; gap: 6px; margin: 0; font-size: 11px; font-weight: 600; letter-spacing: 0.04em; color: var(--app-muted-strong); }
.safety-stat__value { font-family: var(--font-display); font-size: 30px; font-weight: 400; line-height: 1.1; letter-spacing: -0.03em; font-variant-numeric: tabular-nums; color: var(--app-fg); }
.safety-stat--blocked .safety-stat__value { color: var(--risk-critical); }
.safety-stat--high .safety-stat__value { color: var(--risk-high); }
.safety-stat__hint { margin: 2px 0 0; font-size: 11px; color: var(--app-muted); }

/* ===== 概览区：环形图 + 高风险列表 ===== */
.safety-overview { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 12px; margin-bottom: 14px; }
.safety-donut-card { display: flex; align-items: center; gap: 18px; padding: 14px 16px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 10px; }
.safety-donut { position: relative; width: 108px; height: 108px; flex: none; }
.safety-donut svg { width: 100%; height: 100%; }
.safety-donut__center { position: absolute; inset: 0; display: grid; place-content: center; text-align: center; }
.safety-donut__center strong { font-family: var(--font-display); font-size: 22px; font-weight: 400; line-height: 1; color: var(--app-fg); }
.safety-donut__center span { margin-top: 2px; font-size: 10px; color: var(--app-muted); }
.safety-legend { list-style: none; display: grid; gap: 6px; margin: 0; padding: 0; }
.safety-legend li { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.safety-legend__dot { width: 9px; height: 9px; border-radius: 3px; }
.safety-legend__label { color: var(--app-muted-strong); }
.safety-legend__count { margin-left: auto; font-family: var(--font-mono); color: var(--app-fg); font-variant-numeric: tabular-nums; }

.safety-highrisk { padding: 14px 16px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 10px; }
.safety-highrisk > header { margin-bottom: 10px; padding-bottom: 8px; border-bottom: 1px solid var(--app-border); }
.safety-highrisk h3 { display: flex; align-items: center; gap: 6px; margin: 0; font-size: 12.5px; font-weight: 700; color: var(--app-fg); }
.safety-highrisk__list { list-style: none; display: grid; gap: 4px; margin: 0; padding: 0; }
.safety-highrisk__item button { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 8px; align-items: center; width: 100%; padding: 8px 10px; text-align: left; background: var(--app-surface-subtle); border: 1px solid transparent; border-radius: 8px; cursor: pointer; transition: border-color var(--app-motion-fast) ease; }
.safety-highrisk__item button:hover { border-color: var(--app-border-strong); }
.safety-highrisk__excerpt { font-size: 12.5px; color: var(--app-fg); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.safety-highrisk__meta { display: flex; gap: 10px; font-size: 10.5px; color: var(--app-muted); white-space: nowrap; }
.safety-highrisk__empty { margin: 8px 0 2px; font-size: 11.5px; color: var(--app-muted); }

/* ===== 风险等级标签：低饱和胶囊，明显不刺眼 ===== */
.safety-risk-tag { display: inline-flex; align-items: center; gap: 4px; padding: 1px 8px; border: 1px solid transparent; border-radius: 999px; font-size: 11px; font-weight: 600; white-space: nowrap; }
.safety-risk-tag::before { content: ''; width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.safety-risk-tag--critical { color: var(--risk-critical); background: var(--risk-critical-soft); border-color: #e6b8c5; }
.safety-risk-tag--high { color: var(--risk-high); background: var(--risk-high-soft); border-color: var(--app-danger-border); }
.safety-risk-tag--medium { color: var(--risk-medium); background: var(--risk-medium-soft); border-color: var(--app-warning-border); }
.safety-risk-tag--low { color: var(--risk-low); background: var(--risk-low-soft); border-color: var(--app-border-strong); }

/* ===== 详情抽屉：补充区块 ===== */
.case-detail-count { display: inline-flex; align-items: center; justify-content: center; min-width: 20px; height: 18px; margin-left: 6px; padding: 0 6px; font-family: var(--font-mono); font-size: 11px; color: var(--app-muted-strong); background: var(--app-surface-muted); border-radius: 999px; }
.case-detail-muted { margin: 0; padding: 11px 12px; border: 1px dashed var(--app-border); border-radius: 6px; color: var(--app-muted); font-size: 13px; }
.case-detail-rel { list-style: none; display: grid; gap: 6px; margin: 0; padding: 0; }
.case-detail-rel button { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 8px; align-items: center; width: 100%; padding: 8px 10px; text-align: left; background: var(--app-surface-subtle); border: 1px solid var(--app-border); border-radius: 8px; cursor: pointer; transition: border-color var(--app-motion-fast) ease; }
.case-detail-rel button:hover { border-color: var(--app-border-strong); }
.case-detail-rel__excerpt { font-size: 12.5px; color: var(--app-fg); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.case-detail-rel__cat { font-size: 11px; font-weight: 600; color: var(--app-muted-strong); white-space: nowrap; }
.case-detail-rel time, .case-detail-rel code { font-size: 10.5px; color: var(--app-muted); white-space: nowrap; }

/* =========================================================
   以下为原有样式，保持兼容
   ========================================================= */
.content-safety-sections {
  display: grid;
  gap: 16px;
}

.safety-actions, .safety-filters { display: flex; align-items: center; gap: 10px; }
.safety-filters { flex-wrap: wrap; margin-bottom: 12px; }
.safety-filters .el-input { width: min(360px, 100%); }
.safety-filters .el-select { width: 180px; }
.safety-tabs :deep(.el-tabs__header) { margin-bottom: 12px; }

@media (max-width: 600px) {
  .safety-filters .el-select { width: 100%; }
}

.content-safety-card {
  min-width: 0;
  min-height: 240px;
  padding: 0;
  overflow: hidden;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius);
}

.content-safety-pagination {
  justify-content: flex-end;
  margin-top: 16px;
}

.case-row-actions {
  display: flex;
  min-height: 32px;
  align-items: center;
  justify-content: center;
  white-space: nowrap;
}

.case-row-actions :deep(.el-button) { margin-left: 0; }
.case-detail-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; padding-bottom: 16px; border-bottom: 1px solid var(--app-border); }
.case-detail-heading code { display: block; margin-top: 5px; color: var(--app-text); font-size: 13px; word-break: break-all; }
.case-detail-label { display: block; color: var(--app-muted); font-size: 12px; }
.case-detail-tags { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; }
.case-detail-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px 16px; margin: 18px 0 0; }
.case-detail-list > div { min-width: 0; }
.case-detail-list dt { color: var(--app-muted); font-size: 12px; }
.case-detail-list dd { margin: 4px 0 0; color: var(--app-text); font-size: 13px; overflow-wrap: anywhere; }
.case-detail-section { margin-top: 22px; }
.case-detail-section h3 { margin: 0 0 9px; color: var(--app-text); font-size: 14px; }
.case-detail-excerpt, .case-detail-note { margin: 0; padding: 11px 12px; border: 1px solid var(--app-border); border-radius: 6px; background: var(--el-fill-color-light); color: var(--app-text); line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
.case-detail-result { margin: 0; color: var(--app-text); font-weight: 600; }
.case-detail-note { margin-top: 9px; color: var(--app-muted); font-size: 13px; }
.case-detail-footer-action { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-top: 28px; padding-top: 18px; border-top: 1px solid var(--app-border); }
.case-detail-hint { margin: 0; color: var(--app-muted); font-size: 13px; line-height: 1.5; }
.case-action-form { display: grid; gap: 16px; }
.case-action-form label { display: grid; gap: 6px; color: var(--app-text); font-size: 13px; font-weight: 600; }
.case-action-form .el-select { width: 100%; }
:deep(.content-safety-card .el-table__fixed-right) { height: 100% !important; }
:deep(.el-drawer__header) { margin-bottom: 0; padding-bottom: 14px; border-bottom: 1px solid var(--app-border); }
:deep(.el-drawer__body) { padding-top: 18px; }

/* ===== 入场序列 ===== */
@keyframes safety-rise { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: none; } }
.safety-stat, .safety-donut-card, .safety-highrisk { animation: safety-rise 420ms cubic-bezier(0.16, 1, 0.3, 1) both; }
.safety-stat:nth-child(2) { animation-delay: 70ms; }
.safety-stat:nth-child(3) { animation-delay: 140ms; }
.safety-stat:nth-child(4) { animation-delay: 210ms; }
.safety-highrisk { animation-delay: 120ms; }

@media (prefers-reduced-motion: reduce) {
  .safety-stat, .safety-donut-card, .safety-highrisk { animation: none; }
}

/* ===== 响应式 ===== */
@media (max-width: 1080px) {
  .safety-overview { grid-template-columns: 1fr; }
}
@media (max-width: 1000px) {
  .safety-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 800px) {
  .safety-head { align-items: flex-start; flex-direction: column; }
  .safety-donut-card { flex-direction: column; align-items: flex-start; }
  .safety-highrisk__item button { grid-template-columns: auto minmax(0, 1fr); }
  .safety-highrisk__meta { grid-column: 2; }
}
@media (max-width: 640px) {
  .safety-stats { grid-template-columns: 1fr; }
  .case-detail-list { grid-template-columns: 1fr; }
  .case-detail-footer-action { align-items: stretch; flex-direction: column; }
  .case-detail-footer-action .el-button { width: 100%; }
}
</style>
