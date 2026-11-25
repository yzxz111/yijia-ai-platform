<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { Download, Eye, Filter, Search, FileText, Activity, CheckCircle2, XCircle, Clock } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { listUsage, type UsageEntry, type UsageFilters } from '../../api/gateway'
import { queryPageIndex, queryText } from '../../router/query'
import { useUsageStore } from '../../stores/usage'
import { formatDateTime } from '../../utils/dateTime'

type TimePreset = 'all' | '7' | '30' | 'custom'

const route = useRoute()
const router = useRouter()
const usage = useUsageStore()
const filters = reactive({ requestId: '', modelCode: '', outcome: '' })
const timePreset = ref<TimePreset>('all')
const customRange = ref<[string, string] | null>(null)
const page = ref(0)
const pageSize = ref(20)
const selectedUsage = ref<UsageEntry | null>(null)
const detailOpen = ref(false)
const exporting = ref(false)
const paginationTotal = computed(() => usage.loadingUsage
  ? Math.max(usage.usage.total, (page.value + 1) * pageSize.value)
  : usage.usage.total)
const errorMessage = ref('')
const number = new Intl.NumberFormat('zh-CN')

/**
 * 按单位计费的调用：网关按约束把 input/output/total_tokens 全部写成 0，真正计费的是
 * `usageUnits` 里的秒数并标在 `usageDimension` 上。只看 token 计数会把一次生成显示成
 * "消耗 0 积分"，所以列表、详情和导出都按这个维度分流。
 *
 * `AUDIO_SECOND` 必须在这里：音频与视频同单位（秒）却不同维度，漏掉它会让
 * 每一次转写/配音/换口型都显示成「0 积分」——而那正是这段注释开头点名的那个缺陷。
 */
function isSecondUsage(item: UsageEntry) {
  return item.usageDimension === 'VIDEO_SECOND'
    || item.usageDimension === 'IMAGE'
    || item.usageDimension === 'AUDIO_SECOND'
}

/** 列表/导出里的"总用量"单元格：积分调用是积分，视频调用是秒。 */
function usageAmountLabel(item: UsageEntry) {
  if (isSecondUsage(item)) return `${number.format(item.usageUnits ?? 0)} 秒`
  return number.format(item.totalTokens)
}
const outcomes = new Set(['SUCCEEDED', 'FAILED'])
const pageSizes = new Set([5, 10, 20])
const totalSuccess = ref(0)
const totalFailed = ref(0)
let loadGeneration = 0
onUnmounted(() => { loadGeneration += 1 })

async function loadCounts(query: UsageFilters, generation: number) {
  try {
    const [successRes, failRes] = await Promise.all([
      listUsage({ ...query, page: 0, size: 1, outcome: 'SUCCEEDED' }),
      listUsage({ ...query, page: 0, size: 1, outcome: 'FAILED' }),
    ])
    if (generation !== loadGeneration) return
    totalSuccess.value = successRes.total
    totalFailed.value = failRes.total
  } catch { /* 保留最后一次已知值              */ }
}
const timePresets = new Set<TimePreset>(['all', '7', '30', 'custom'])

const totalCalls = computed(() => usage.usage.total)
const successCount = computed(() => usage.usage.items.filter(i => i.outcome === 'SUCCEEDED').length)
const failCount = computed(() => usage.usage.items.filter(i => i.outcome === 'FAILED').length)

function dateBoundary(date: string, endExclusive: boolean) {
  const value = new Date(`${date}T00:00:00`)
  if (endExclusive) value.setDate(value.getDate() + 1)
  return value.toISOString()
}

function timeBounds() {
  if (timePreset.value === 'all') return {}
  if (timePreset.value === 'custom') {
    if (!customRange.value) return {}
    return {
      from: dateBoundary(customRange.value[0], false),
      to: dateBoundary(customRange.value[1], true),
    }
  }
  const to = new Date()
  const from = new Date(to)
  from.setDate(from.getDate() - Number(timePreset.value))
  return { from: from.toISOString(), to: to.toISOString() }
}

function usageFilters(
  nextPage: number,
  size = pageSize.value,
  bounds: Partial<Pick<UsageFilters, 'from' | 'to'>> = timeBounds(),
): UsageFilters {
  return {
    page: nextPage,
    size,
    requestId: filters.requestId.trim(),
    modelCode: filters.modelCode.trim(),
    outcome: filters.outcome,
    ...bounds,
  }
}

async function load() {
  const generation = ++loadGeneration
  const query = usageFilters(page.value)
  errorMessage.value = ''
  try {
    const requestedPage = query.page ?? 0
    const result = await usage.loadUsage(query)
    if (generation !== loadGeneration) return
    if (result && requestedPage > 0 && requestedPage * pageSize.value >= result.total) {
      await router.replace({ query: filtersQuery(0) })
    }
    void loadCounts(query, generation)
  } catch {
    if (generation === loadGeneration) errorMessage.value = '调用日志加载失败，请稍后重试。'
  }
}

function applyFilters() {
  if (timePreset.value === 'custom' && !customRange.value) {
    ElMessage.warning('请选择自定义开始和结束日期。')
    return
  }
  const query = filtersQuery(0)
  if (matchesCurrentQuery(query)) void load()
  else void router.push({ query })
}

function changePage(nextPage: number) {
  void router.push({ query: filtersQuery(nextPage - 1) })
}

function changePageSize(nextSize: number) {
  pageSize.value = nextSize
  void router.push({ query: filtersQuery(0) })
}

function filtersQuery(nextPage: number) {
  const query: Record<string, string> = {}
  const requestId = filters.requestId.trim()
  const modelCode = filters.modelCode.trim()
  if (requestId) query.requestId = requestId
  if (modelCode) query.modelCode = modelCode
  if (filters.outcome) query.outcome = filters.outcome
  if (timePreset.value !== 'all') query.period = timePreset.value
  if (timePreset.value === 'custom' && customRange.value) {
    query.from = customRange.value[0]
    query.to = customRange.value[1]
  }
  if (pageSize.value !== 20) query.size = String(pageSize.value)
  if (nextPage > 0) query.page = String(nextPage + 1)
  return query
}

function matchesCurrentQuery(query: Record<string, string>) {
  const currentKeys = Object.keys(route.query)
  const nextKeys = Object.keys(query)
  return currentKeys.length === nextKeys.length
    && nextKeys.every((key) => route.query[key] === query[key])
}

function outcomeLabel(outcome: string) {
  return outcome === 'SUCCEEDED' ? '调用成功' : '调用失败'
}

/**
 * 生成终态的中文标签（T5）。
 *
 * 三个已知值之外一律返回空字符串，**包括 null 与"键不存在"**：旧服务的响应里没有
 * 这一列，把它显示成"已完成"就是替服务编一个它没说过的结论。导出里留空表示"未知"。
 */
function generationStatusLabel(status?: string | null) {
  switch (status) {
    case 'completed':
      return '已完成'
    case 'truncated':
      return '已截断'
    case 'failed':
      return '未完成'
    default:
      return ''
  }
}

/**
 * 上限来源的中文标签（T3）。
 *
 * 这一列与"有效输出上限"必须一起看：同一个 4096，来自 `client` 要客户端改请求，
 * 来自 `package` 要运营改套餐，来自 `system` 则说明谁都没限制住。
 */
function ceilingSourceLabel(source?: string | null) {
  switch (source) {
    case 'client':
      return '客户端请求'
    case 'model':
      return '模型上限'
    case 'package':
      return '套餐上限'
    case 'system':
      return '系统上限'
    default:
      return ''
  }
}

function showDetail(item: UsageEntry) {
  selectedUsage.value = item
  detailOpen.value = true
}

function csvValue(value: unknown) {
  const valueText = value == null ? '' : String(value)
  return `"${valueText.replaceAll('"', '""')}"`
}

async function exportCsv() {
  if (exporting.value) return
  exporting.value = true
  errorMessage.value = ''
  try {
    const rows: UsageEntry[] = []
    const exportBounds = timeBounds()
    let exportPage = 0
    let total = 0
    do {
      const result = await listUsage(usageFilters(exportPage, 100, exportBounds))
      rows.push(...result.items)
      total = result.total
      if (!result.items.length && rows.length < total) {
        throw new Error('Usage export returned an empty page before reaching the reported total.')
      }
      exportPage += 1
    } while (rows.length < total)

    // 导出是混合维度：积分（token）调用与按秒计费的视频调用会出现在同一份 CSV 里，
    // 所以列名用中性的"输入/输出/总用量"，并单列一列计量单位说明这一行是积分还是秒。
    //
    // 后面五列是 2026-10-15 排查之后加的（T5/T3）。原因很具体：一次被套餐 4096 上限
    // 截断的请求，导出里原本是「调用成功 / HTTP 200 / 错误详情为空」，与一次正常结束
    // **逐字段相同**，读报表的人只能得出"任务完成了"的结论。现在它带着
    // 「生成结果 = 已截断」「结束原因 = length」以及「上限 4096、来自套餐」三件事出现，
    // 而"上限来自哪一层"决定了该找客户端改请求、还是找运营改套餐。
    const headers = ['请求 ID', '模型', '计量单位', '输入', '输出', '总用量', '耗时(ms)', 'HTTP 状态', '调用结果',
      '生成结果', '结束原因', '有效输出上限', '上限来源', '尝试次数', '错误类型', '错误详情', '调用时间']
    const lines = rows.map((item) => [
      item.requestId,
      item.modelCode,
      isSecondUsage(item) ? '秒' : '积分',
      isSecondUsage(item) ? '' : item.inputTokens,
      isSecondUsage(item) ? '' : item.outputTokens,
      usageAmountLabel(item),
      item.durationMs,
      item.httpStatus,
      outcomeLabel(item.outcome),
      generationStatusLabel(item.generationStatus),
      item.finishReason,
      item.effectiveMaxOutputTokens,
      ceilingSourceLabel(item.ceilingSource),
      item.attemptCount,
      item.errorCategory,
      item.errorSummary,
      formatDateTime(item.createdAt),
    ].map(csvValue).join(','))
    const blob = new Blob([`\uFEFF${headers.map(csvValue).join(',')}\r\n${lines.join('\r\n')}`], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `调用日志-${new Date().toISOString().slice(0, 10)}.csv`
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success(`已导出 ${rows.length} 条调用日志。`)
  } catch {
    errorMessage.value = '调用日志导出失败，请稍后重试。'
  } finally {
    exporting.value = false
  }
}

watch(
  () => route.query,
  (query) => {
    filters.requestId = queryText(query.requestId)
    filters.modelCode = queryText(query.modelCode)
    const outcome = queryText(query.outcome)
    filters.outcome = outcomes.has(outcome) ? outcome : ''
    const period = queryText(query.period) as TimePreset
    timePreset.value = timePresets.has(period) ? period : 'all'
    const from = queryText(query.from)
    const to = queryText(query.to)
    customRange.value = /^\d{4}-\d{2}-\d{2}$/.test(from) && /^\d{4}-\d{2}-\d{2}$/.test(to)
      ? [from, to]
      : null
    const requestedSize = Number(queryText(query.size))
    pageSize.value = pageSizes.has(requestedSize) ? requestedSize : 20
    page.value = queryPageIndex(query.page)
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <section class="usage-page" aria-labelledby="usage-title">
    <!-- Page Header -->
    <header class="page-header">
      <div class="page-title">
        <h2 id="usage-title">调用日志</h2>
        <p>查看 API 请求明细与调用结果</p>
      </div>
      <button class="primary-btn" :disabled="exporting || usage.loadingUsage" @click="exportCsv">
        <Download :size="16" />
        {{ exporting ? '导出中…' : '导出 CSV' }}
      </button>
    </header>

    <!-- Stat Cards -->
    <div class="stat-cards">
      <div class="stat-card">
        <div class="stat-body">
          <span class="stat-label">日志总数</span>
          <span class="stat-value">{{ number.format(totalCalls) }}</span>
        </div>
        <div class="stat-icon stat-icon--blue"><FileText :size="18" /></div>
      </div>
      <div class="stat-card">
        <div class="stat-body">
          <span class="stat-label">调用成功</span>
          <span class="stat-value">{{ number.format(totalSuccess) }}</span>
        </div>
        <div class="stat-icon stat-icon--green"><CheckCircle2 :size="18" /></div>
      </div>
      <div class="stat-card">
        <div class="stat-body">
          <span class="stat-label">调用失败</span>
          <span class="stat-value">{{ number.format(totalFailed) }}</span>
        </div>
        <div class="stat-icon stat-icon--red"><XCircle :size="18" /></div>
      </div>
      <div class="stat-card">
        <div class="stat-body">
          <span class="stat-label">当前页</span>
          <span class="stat-value">{{ number.format(usage.usage.items.length) }}</span>
        </div>
        <div class="stat-icon stat-icon--purple"><Activity :size="18" /></div>
      </div>
    </div>

    <!-- Filter Bar -->
    <form class="filter-bar" @submit.prevent="applyFilters">
      <el-input v-model="filters.requestId" data-test="usage-request-id" name="request-id" aria-label="请求 ID" autocomplete="off" placeholder="搜索请求 ID…">
        <template #prefix><Search :size="15" aria-hidden="true" /></template>
      </el-input>
      <el-input v-model="filters.modelCode" name="model-code" aria-label="模型代码" autocomplete="off" placeholder="模型代码…" />
      <el-select v-model="filters.outcome" aria-label="调用结果" placeholder="全部结果" clearable>
        <el-option label="调用成功" value="SUCCEEDED" />
        <el-option label="调用失败" value="FAILED" />
      </el-select>
      <el-select v-model="timePreset" aria-label="调用时间" placeholder="调用时间">
        <el-option label="全部时间" value="all" />
        <el-option label="近 7 天" value="7" />
        <el-option label="近 30 天" value="30" />
        <el-option label="自定义" value="custom" />
      </el-select>
      <el-date-picker v-if="timePreset === 'custom'" v-model="customRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" aria-label="自定义调用时间" />
      <button class="filter-btn" type="submit" data-test="usage-filter">
        <Filter :size="15" /> 筛选
      </button>
    </form>

    <TopErrorMessage :message="errorMessage" />

    <!-- Table -->
    <div class="table-card">
      <div class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>请求 ID</th><th>模型</th><th>输入</th><th>输出</th><th>总用量</th>
              <th>耗时</th><th>HTTP</th><th>结果</th><th>时间</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in usage.usage.items" :key="item.id" class="data-row">
              <td><code class="req-code">{{ item.requestId }}</code></td>
              <td>{{ item.modelCode }}</td>
              <!-- 视频调用按秒结算：token 计数器全为 0，这里显示已计费秒数而不是"0 积分" -->
              <td class="cell-num">{{ isSecondUsage(item) ? '—' : number.format(item.inputTokens) }}</td>
              <td class="cell-num">{{ isSecondUsage(item) ? '—' : number.format(item.outputTokens) }}</td>
              <td class="cell-num cell-total">{{ usageAmountLabel(item) }}</td>
              <td class="cell-num">{{ item.durationMs }} ms</td>
              <td>{{ item.httpStatus }}</td>
              <td>
                <span :class="item.outcome === 'SUCCEEDED' ? 'status-badge status--success' : 'status-badge status--danger'">
                  {{ outcomeLabel(item.outcome) }}
                </span>
              </td>
              <td class="cell-date">{{ formatDateTime(item.createdAt) }}</td>
              <td>
                <button class="action-btn action-btn--ghost" :aria-label="`查看请求 ${item.requestId} 详情`" @click="showDetail(item)">
                  <Eye :size="14" /> 详情
                </button>
              </td>
            </tr>
            <tr v-if="!usage.loadingUsage && !usage.usage.items.length">
              <td colspan="10" class="empty-row">
                <FileText :size="24" />
                <span>没有符合条件的调用日志</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="table-footer">
        <span>共 {{ paginationTotal }} 条记录</span>
        <el-pagination
          :current-page="page + 1"
          :page-size="pageSize"
          :page-sizes="[5, 10, 20]"
          :total="paginationTotal"
          layout="sizes, prev, pager, next"
          @size-change="changePageSize"
          @current-change="changePage"
        />
      </footer>
    </div>

    <!-- Detail Dialog -->
    <el-dialog v-model="detailOpen" title="调用详情" width="min(620px, calc(100vw - 24px))">
      <dl v-if="selectedUsage" class="detail-list">
        <div><dt>请求 ID</dt><dd><code>{{ selectedUsage.requestId }}</code></dd></div>
        <div><dt>模型</dt><dd>{{ selectedUsage.modelCode }}</dd></div>
        <!-- 视频调用：显示生成时长（秒），不显示恒为 0 的 token 计数 -->
        <template v-if="isSecondUsage(selectedUsage)">
          <div class="detail-wide"><dt>生成时长</dt><dd>{{ number.format(selectedUsage.usageUnits ?? 0) }} 秒（按实际生成时长计费）</dd></div>
        </template>
        <template v-else>
          <div><dt>输入积分</dt><dd>{{ number.format(selectedUsage.inputTokens) }}</dd></div>
          <div><dt>输出积分</dt><dd>{{ number.format(selectedUsage.outputTokens) }}</dd></div>
          <div><dt>总积分</dt><dd>{{ number.format(selectedUsage.totalTokens) }}</dd></div>
        </template>
        <div><dt>HTTP 状态</dt><dd>{{ selectedUsage.httpStatus }}</dd></div>
        <div><dt>调用结果</dt><dd>{{ outcomeLabel(selectedUsage.outcome) }}</dd></div>
        <div><dt>调用时间</dt><dd>{{ formatDateTime(selectedUsage.createdAt) }}</dd></div>
        <div><dt>耗时</dt><dd>{{ selectedUsage.durationMs }} ms</dd></div>
        <div class="detail-wide"><dt>错误类型</dt><dd>{{ selectedUsage.errorCategory || '无' }}</dd></div>
        <div class="detail-wide"><dt>错误详情</dt><dd>{{ selectedUsage.errorSummary || '无' }}</dd></div>
      </dl>
    </el-dialog>
  </section>
</template>

<style scoped>
.usage-page { width: 100%; min-width: 0; }

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
.primary-btn {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 16px; border: 0; border-radius: 8px;
  background: var(--app-primary); color: #fff;
  font: inherit; font-size: 14px; font-weight: 600;
  cursor: pointer; flex-shrink: 0;
  transition: background var(--app-motion-fast) ease;
}
.primary-btn:hover { background: var(--app-primary); }
.primary-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* 统计卡片 */
.stat-cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}
.stat-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  box-shadow: none;
  transition: all var(--app-motion-fast) ease;
}
.stat-card:hover {
  box-shadow: var(--app-shadow-xs);
}
.stat-icon {
  display: flex; align-items: center; justify-content: center;
  width: 36px; height: 36px; border-radius: 50%; flex-shrink: 0;
}
.stat-icon--blue { color: var(--app-primary); background: var(--app-primary-soft); }
.stat-icon--green { color: var(--app-success); background: var(--app-success-soft); }
.stat-icon--red { color: var(--app-danger); background: var(--app-danger-soft); }
.stat-icon--purple { color: var(--app-primary); background: var(--app-primary-soft); }
.stat-body { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.stat-label { font-size: 12px; color: var(--app-muted-strong); }
.stat-value { font-size: 20px; font-weight: 700; color: var(--app-fg); font-variant-numeric: tabular-nums; letter-spacing: -0.5px; }

/* 筛选栏 */
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}
.filter-bar :deep(.el-input) { width: auto; flex: 0 1 220px; }
.filter-bar :deep(.el-select) { width: auto; flex: 0 0 140px; }
.filter-bar :deep(.el-date-editor) { width: auto !important; flex: 0 0 280px; }
.filter-bar :deep(.el-input__wrapper),
.filter-bar :deep(.el-select__wrapper),
.filter-bar :deep(.el-date-editor),
.filter-bar :deep(.el-range-editor) {
  box-sizing: border-box;
  height: 40px;
  min-height: 40px;
  border-radius: 8px;
}
.filter-btn {
  display: flex; align-items: center; gap: 5px;
  padding: 0 16px; height: 40px;
  border: 0; border-radius: 8px;
  background: var(--app-primary); color: #fff;
  font: inherit; font-size: 14px; font-weight: 600;
  cursor: pointer; flex-shrink: 0;
  transition: background var(--app-motion-fast) ease;
}
.filter-btn:hover { background: var(--app-primary); }

/* 表格卡片 */
.table-card {
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 14px;
  overflow: hidden;
}
.table-scroll { overflow-x: auto; }
.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.data-table th,
.data-table td {
  padding: 13px 16px;
  border-bottom: 1px solid var(--app-border);
  text-align: left;
  white-space: nowrap;
  height: 48px;
}
.data-table thead th {
  color: var(--app-muted-strong);
  font-size: 12px;
  font-weight: 600;
  background: var(--app-surface-subtle);
  text-transform: uppercase;
  letter-spacing: 0.3px;
}
.data-row { transition: background var(--app-motion-fast) ease; }
.data-row:hover { background: var(--app-surface-subtle); }
.req-code { color: var(--app-primary); font-weight: 600; }
.cell-num { font-variant-numeric: tabular-nums; }
.cell-total { font-weight: 700; }
.cell-date { color: var(--app-muted); }

/* 状态徽章 */
.status-badge {
  display: inline-flex;
  align-items: center;
  padding: 3px 12px;
  border-radius: 9999px;
  font-size: 12px;
  font-weight: 600;
}
.status--success { color: var(--app-success); background: var(--app-success-soft); }
.status--danger { color: var(--app-danger); background: var(--app-danger-soft); }

/* 操作按钮 */
.action-btn {
  display: flex; align-items: center; gap: 4px;
  padding: 6px 10px;
  border: 1px solid var(--app-border); border-radius: 8px;
  background: transparent;
  color: var(--app-muted-strong);
  font: inherit; font-size: 12px; font-weight: 600;
  cursor: pointer;
  transition: all var(--app-motion-fast) ease;
  white-space: nowrap;
}
.action-btn--ghost:hover {
  border-color: var(--app-primary-soft);
  color: var(--app-primary);
  background: var(--app-primary-soft);
}

/* 空行 */
.empty-row {
  text-align: center;
  color: var(--app-muted);
  padding: 40px 16px;
}
.empty-row svg {
  margin-right: 8px;
  vertical-align: middle;
}

/* 表格页脚 */
.table-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  color: var(--app-muted);
  font-size: 12px;
  border-top: 1px solid var(--app-border);
}

/* 详情对话框 */
.detail-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0;
  margin: 0;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  overflow: hidden;
}
.detail-list > div {
  min-width: 0;
  padding: 12px 16px;
  border-right: 1px solid var(--app-border);
  border-bottom: 1px solid var(--app-border);
}
.detail-list > div:nth-child(even),
.detail-list > .detail-wide {
  border-right: 0;
}
.detail-list > .detail-wide {
  grid-column: 1 / -1;
}
.detail-list dt {
  margin-bottom: 4px;
  color: var(--app-muted-strong);
  font-size: 12px;
}
.detail-list dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}

/* 响应式 */
@media (max-width: 1080px) {
  .stat-cards { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 640px) {
  .page-header { flex-direction: column; }
  .primary-btn { width: 100%; justify-content: center; }
  .stat-cards { grid-template-columns: 1fr; }
  .filter-bar :deep(.el-input),
  .filter-bar :deep(.el-select),
  .filter-bar :deep(.el-date-editor) { flex: 1 1 100% !important; width: 100% !important; }
  .filter-btn { width: 100%; justify-content: center; }
  .table-footer { flex-direction: column; align-items: flex-start; }
  .detail-list { grid-template-columns: 1fr; }
  .detail-list > div { grid-column: 1; border-right: 0; }
}
</style>
