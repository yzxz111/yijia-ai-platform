<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Activity, ArrowRightLeft, Cpu, Database, Eye, HardDrive, Network, RefreshCw, RotateCcw, Search, Server, Settings2, ShieldAlert, Trash2, Wifi } from 'lucide-vue-next'

import {
  businessNo, cleanupFiles, getGatewayHealth, getOutboxHealth, getRoutingHealth,
  listOutbox, listRoutingTargets, listSmsDeliveries, listSmsTemplates, recoverReservations, resetRoutingCircuit, restoreAutomaticRouting, retryOutbox, retrySmsDelivery, switchRoutingCircuit,
  type AdminListParams, type GatewayHealth, type OperationResult, type OutboxEvent, type OutboxHealth,
  type CircuitHealth, type RoutingHealth, type RoutingTarget, type SmsDelivery, type SmsDeliveryListParams, type SmsTemplate,
} from '../../api/admin'
import { formatDateTime } from '../../utils/dateTime'
import { userNameLabel, userNoLabel } from '../../utils/adminDisplay'

const gateway = ref<GatewayHealth | null>(null)
const routing = ref<RoutingHealth | null>(null)
const outboxHealth = ref<OutboxHealth | null>(null)
const outbox = ref<OutboxEvent[]>([])
const outboxTotal = ref(0)
const smsTab = ref('templates')
const smsTemplates = ref<SmsTemplate[]>([])
const smsDeliveries = ref<SmsDelivery[]>([])
const smsTotal = ref(0)
const snapshotsLoading = ref(true)
const outboxLoading = ref(true)
const smsTemplatesLoading = ref(true)
const smsDeliveriesLoading = ref(true)
const smsLoading = computed(() => smsTemplatesLoading.value || smsDeliveriesLoading.value)
const loading = computed(() => snapshotsLoading.value || outboxLoading.value || smsLoading.value)
const running = ref('')
const smsRunning = ref('')
const errorMessage = ref('')
const smsErrorMessage = ref('')
const smsLastRetry = ref('')
const selectedCircuit = ref<CircuitHealth | null>(null)
const circuitDrawerOpen = ref(false)
const circuitTargets = ref<RoutingTarget[]>([])
const circuitTargetsLoading = ref(false)
const circuitRouteRunning = ref('')
const pageSizeOptions = [5, 10, 20]
const lastRun = ref<{ label: string; businessNo: string; result: OperationResult | null } | null>(null)
const outboxParams = reactive<AdminListParams>({
  page: 0, size: 20, query: '', status: '', sort: 'createdAt', direction: 'desc',
})
const smsParams = reactive<SmsDeliveryListParams>({
  page: 0, size: 20, query: '', status: '', templateKey: '', sort: 'createdAt', direction: 'desc',
})
let smsTemplateRequest = 0
let smsDeliveryRequest = 0

async function loadSnapshots() {
  snapshotsLoading.value = true
  errorMessage.value = ''
  try {
    const [gatewaySnapshot, routingSnapshot, outboxSnapshot] = await Promise.all([
      getGatewayHealth(), getRoutingHealth(), getOutboxHealth(),
    ])
    gateway.value = gatewaySnapshot
    routing.value = routingSnapshot
    outboxHealth.value = outboxSnapshot
  } catch {
    errorMessage.value = '运行状态加载失败，请稍后重试。'
  } finally {
    snapshotsLoading.value = false
  }
}

async function loadOutbox(targetPage = outboxParams.page) {
  outboxLoading.value = true
  errorMessage.value = ''
  try {
    const result = await listOutbox({ ...outboxParams, page: targetPage })
    outbox.value = result.items
    outboxTotal.value = result.total
    outboxParams.page = result.page
  } catch {
    errorMessage.value = '发件箱数据加载失败，请稍后重试。'
  } finally {
    outboxLoading.value = false
  }
}

async function loadSmsTemplates() {
  const request = ++smsTemplateRequest
  smsTemplatesLoading.value = true
  smsErrorMessage.value = ''
  try {
    const result = await listSmsTemplates()
    if (request !== smsTemplateRequest) return
    smsTemplates.value = result
  } catch {
    if (request === smsTemplateRequest) {
      smsErrorMessage.value = '短信模板状态加载失败，请稍后重试。'
    }
  } finally {
    if (request === smsTemplateRequest) smsTemplatesLoading.value = false
  }
}

async function loadSmsDeliveries(targetPage = smsParams.page) {
  const request = ++smsDeliveryRequest
  smsDeliveriesLoading.value = true
  smsErrorMessage.value = ''
  try {
    const result = await listSmsDeliveries({ ...smsParams, page: targetPage })
    if (request !== smsDeliveryRequest) return
    smsDeliveries.value = result.items
    smsTotal.value = result.total
    smsParams.page = result.page
  } catch {
    if (request === smsDeliveryRequest) {
      smsErrorMessage.value = '短信投递记录加载失败，请稍后重试。'
    }
  } finally {
    if (request === smsDeliveryRequest) smsDeliveriesLoading.value = false
  }
}

function loadSms() {
  return Promise.all([loadSmsTemplates(), loadSmsDeliveries()])
}

function load() {
  return Promise.all([loadSnapshots(), loadOutbox(), loadSms()])
}

function changeOutboxPageSize(size: number) { outboxParams.size = size; void loadOutbox(0) }
function changeSmsPageSize(size: number) { smsParams.size = size; void loadSmsDeliveries(0) }
function clearOutboxFilters() { Object.assign(outboxParams, { query: '', status: '', sort: 'createdAt', direction: 'desc' }); void loadOutbox(0) }
function clearSmsFilters() { Object.assign(smsParams, { query: '', status: '', templateKey: '', sort: 'createdAt', direction: 'desc' }); void loadSmsDeliveries(0) }
const routingPanelRef = ref<HTMLElement | null>(null)
const maintenancePanelRef = ref<HTMLElement | null>(null)
function scrollToPanel(target: 'routing' | 'maintenance') {
  const el = target === 'routing' ? routingPanelRef.value : maintenancePanelRef.value
  el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function confirmed(message: string, title: string, confirmButtonText: string) {
  try {
    await ElMessageBox.confirm(message, title, {
      type: 'warning', confirmButtonText, cancelButtonText: '取消',
    })
    return true
  } catch {
    return false
  }
}

async function runOperation(action: 'recover' | 'cleanup') {
  const recovering = action === 'recover'
  const label = recovering ? '恢复过期预占' : '清理失效文件'
  const accepted = await confirmed(
    recovering ? '确认恢复全部过期积分预占？' : '确认清理全部失效文件对象？',
    label,
    '确认执行',
  )
  if (!accepted) return

  const operationBusinessNo = businessNo('operation')
  running.value = action
  errorMessage.value = ''
  lastRun.value = { label, businessNo: operationBusinessNo, result: null }
  try {
    const result = recovering
      ? await recoverReservations(operationBusinessNo)
      : await cleanupFiles(operationBusinessNo)
    lastRun.value = { label, businessNo: operationBusinessNo, result }
  } catch {
    errorMessage.value = `${label}失败，可使用业务号查询审计记录后重试。`
  } finally {
    running.value = ''
  }
}

async function retry(event: OutboxEvent) {
  const accepted = await confirmed(
    `确认重试发件箱事件 ${event.businessKey}？`,
    '重试发件箱事件',
    '确认重试',
  )
  if (!accepted) return

  const retryBusinessNo = businessNo('outbox')
  running.value = event.id
  errorMessage.value = ''
  lastRun.value = { label: '重试发件箱事件', businessNo: retryBusinessNo, result: null }
  try {
    const updated = await retryOutbox(event.id, retryBusinessNo)
    outbox.value = outbox.value.map((item) => item.id === updated.id ? updated : item)
  } catch {
    errorMessage.value = '发件箱事件重试失败，可使用业务号查询审计记录后重试。'
  } finally {
    running.value = ''
  }
}

async function retrySms(delivery: SmsDelivery) {
  if (!['FAILED', 'DEAD'].includes(delivery.status) || smsRunning.value) return
  const accepted = await confirmed(
    `确认重试 ${delivery.templateKey} 发往 ${delivery.maskedPhone} 的短信？`,
    '重试短信投递',
    '确认重试',
  )
  if (!accepted) return

  const retryBusinessNo = businessNo('sms-retry')
  smsRunning.value = delivery.id
  smsLastRetry.value = retryBusinessNo
  smsErrorMessage.value = ''
  try {
    await retrySmsDelivery(delivery.id, retryBusinessNo)
    await loadSmsDeliveries()
  } catch {
    smsErrorMessage.value = '短信投递重试失败，可使用业务号查询审计记录后重试。'
  } finally {
    smsRunning.value = ''
  }
}

function circuitModelCode(circuit: CircuitHealth) {
  return circuit.modelCode || circuit.key.split(':').slice(1).join(':') || '未知模型'
}

async function openCircuit(circuit: CircuitHealth) {
  selectedCircuit.value = circuit
  circuitTargets.value = []
  circuitDrawerOpen.value = true
  circuitTargetsLoading.value = true
  try {
    circuitTargets.value = await listRoutingTargets(circuit.key)
  } catch {
    errorMessage.value = '路由目标加载失败，请刷新后重试。'
  } finally {
    circuitTargetsLoading.value = false
  }
}

async function resetCircuit(circuit: CircuitHealth) {
  const accepted = await confirmed(`确认重置熔断器 ${circuit.key}？重置后将重新开始采样。`, '重置熔断器', '确认重置')
  if (!accepted) return
  running.value = `circuit:${circuit.key}`
  try {
    selectedCircuit.value = await resetRoutingCircuit(circuit.key)
    circuitTargets.value = await listRoutingTargets(circuit.key)
    await loadSnapshots()
  } catch {
    errorMessage.value = '熔断器重置失败，请刷新状态后重试。'
  } finally {
    running.value = ''
  }
}

async function switchCircuitRoute(target: RoutingTarget) {
  if (!selectedCircuit.value || !target.eligible || circuitRouteRunning.value) return
  const accepted = await confirmed(
    `确认将 ${circuitModelCode(selectedCircuit.value)} 切换到 ${target.channelName}？`,
    '手动切流',
    '确认切流',
  )
  if (!accepted) return

  circuitRouteRunning.value = target.channelId
  try {
    await switchRoutingCircuit(selectedCircuit.value.key, target.channelId)
    circuitTargets.value = await listRoutingTargets(selectedCircuit.value.key)
    await loadSnapshots()
    ElMessage.success('已切换到指定渠道。')
  } catch {
    errorMessage.value = '手动切流失败，请确认目标渠道健康后重试。'
  } finally {
    circuitRouteRunning.value = ''
  }
}

async function restoreAutomaticCircuitRoute() {
  if (!selectedCircuit.value || circuitRouteRunning.value) return
  const accepted = await confirmed(
    `确认恢复 ${circuitModelCode(selectedCircuit.value)} 的自动路由？`,
    '恢复自动路由',
    '确认恢复',
  )
  if (!accepted) return

  circuitRouteRunning.value = 'automatic'
  try {
    await restoreAutomaticRouting(selectedCircuit.value.key)
    circuitTargets.value = await listRoutingTargets(selectedCircuit.value.key)
    await loadSnapshots()
    ElMessage.success('已恢复自动路由。')
  } catch {
    errorMessage.value = '恢复自动路由失败，请稍后重试。'
  } finally {
    circuitRouteRunning.value = ''
  }
}

function targetUnavailableReason(target: RoutingTarget) {
  if (!target.channelEnabled) return '渠道已停用'
  if (!target.mappingEnabled) return '模型映射已停用'
  if (!target.credentialConfigured) return '未配置凭证'
  if (target.healthStatus !== 'HEALTHY') return `渠道${statusLabel(target.healthStatus)}`
  if (!target.eligible) return '当前不可用'
  return ''
}

function statusType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (['UP', 'HEALTHY', 'CLOSED', 'DELIVERED', 'COMPLETED', 'SENT', 'ACTIVE'].includes(status)) return 'success'
  if (['DOWN', 'UNHEALTHY', 'OPEN', 'FAILED', 'DEAD'].includes(status)) return 'danger'
  if (['HALF_OPEN', 'DEGRADED', 'PENDING', 'DELIVERING', 'SENDING', 'PENDING_INTEGRATION'].includes(status)) return 'warning'
  return 'info'
}

const statusLabels: Record<string, string> = {
  UP: '正常',
  DOWN: '异常',
  HEALTHY: '正常',
  UNHEALTHY: '异常',
  CLOSED: '未熔断',
  OPEN: '已熔断',
  HALF_OPEN: '半开',
  DEGRADED: '降级',
  UNKNOWN: '未知',
  COMPLETED: '已完成',
  FAILED: '失败',
  PENDING: '待处理',
  DELIVERING: '投递中',
  DELIVERED: '已投递',
  SENDING: '发送中',
  SENT: '已发送',
  DEAD: '已终止',
  ACTIVE: '已接入',
  PENDING_INTEGRATION: '待接入',
  RESERVED: '预留',
}

function statusLabel(status?: string) {
  if (!status) return '未知'
  return statusLabels[status] || status
}

/* =========================================================
   运维看板派生数据（基于真实 health 接口聚合，不编造系统指标）
   ========================================================= */

/** 服务健康项：从网关/路由/发件箱 health 聚合出真实服务清单 */
interface ServiceHealthItem {
  key: string
  name: string
  tone: 'ok' | 'bad' | 'warn' | 'info'
  label: string
  detail: string
}

const serviceHealth = computed<ServiceHealthItem[]>(() => {
  const items: ServiceHealthItem[] = []

  // 网关
  const gwStatus = gateway.value?.status || 'UNKNOWN'
  items.push({ key: 'gateway', name: 'API 网关', tone: toneOf(gwStatus), label: statusLabel(gwStatus), detail: gateway.value ? '核心接入层' : '暂无数据' })

  // 数据库
  const dbStatus = gateway.value?.database || 'UNKNOWN'
  items.push({ key: 'database', name: '数据库', tone: toneOf(dbStatus), label: statusLabel(dbStatus), detail: gateway.value ? '主存储' : '暂无数据' })

  // 路由渠道（取健康/异常计数汇总）
  const channels = routing.value?.channels || []
  const unhealthyChannels = channels.filter((c) => c.lastHealthStatus !== 'HEALTHY' && c.lastHealthStatus !== 'UNKNOWN')
  if (channels.length) {
    items.push({
      key: 'routing',
      name: '路由渠道',
      tone: unhealthyChannels.length ? 'warn' : 'ok',
      label: unhealthyChannels.length ? `${unhealthyChannels.length} 个异常` : '全部正常',
      detail: `${channels.length} 个渠道`,
    })
  }

  // 熔断器（有 OPEN 熔断则异常，HALF_OPEN 警告）
  const circuits = routing.value?.circuits || []
  const openCircuits = circuits.filter((c) => c.state === 'OPEN')
  const halfCircuits = circuits.filter((c) => c.state === 'HALF_OPEN')
  if (circuits.length) {
    items.push({
      key: 'circuits',
      name: '熔断器',
      tone: openCircuits.length ? 'bad' : halfCircuits.length ? 'warn' : 'ok',
      label: openCircuits.length ? `${openCircuits.length} 个熔断` : halfCircuits.length ? `${halfCircuits.length} 个半开` : '正常',
      detail: `${circuits.length} 个模型`,
    })
  }

  // 发件箱
  const ob = outboxHealth.value
  if (ob) {
    const unhealthy = ob.failed > 0
    items.push({
      key: 'outbox',
      name: '发件箱',
      tone: unhealthy ? 'warn' : 'ok',
      label: unhealthy ? `${ob.failed} 失败` : '正常',
      detail: `待处理 ${ob.pending} · 投递中 ${ob.delivering}`,
    })
  }

  return items
})

/** 健康计数汇总：正常/异常/警告 */
const healthSummary = computed(() => {
  const total = serviceHealth.value.length
  const ok = serviceHealth.value.filter((s) => s.tone === 'ok').length
  const bad = serviceHealth.value.filter((s) => s.tone === 'bad').length
  const warn = serviceHealth.value.filter((s) => s.tone === 'warn').length
  return { total, ok, bad, warn }
})

function toneOf(status: string): 'ok' | 'bad' | 'warn' | 'info' {
  if (['UP', 'HEALTHY', 'CLOSED', 'DELIVERED', 'COMPLETED', 'SENT', 'ACTIVE'].includes(status)) return 'ok'
  if (['DOWN', 'UNHEALTHY', 'OPEN', 'FAILED', 'DEAD'].includes(status)) return 'bad'
  if (['HALF_OPEN', 'DEGRADED', 'PENDING', 'DELIVERING', 'SENDING', 'PENDING_INTEGRATION'].includes(status)) return 'warn'
  return 'info'
}

/** 最近系统异常事件：异常渠道 + 已熔断电路 + 失败发件箱（真实异常来源） */
interface AnomalyEvent {
  kind: '渠道异常' | '熔断器熔断' | '发件箱失败'
  title: string
  detail: string
  suggestion: string
  owner: string
  time?: string
}

const anomalyEvents = computed<AnomalyEvent[]>(() => {
  const events: AnomalyEvent[] = []

  ;(routing.value?.channels || [])
    .filter((c) => c.lastHealthStatus === 'UNHEALTHY' || c.lastHealthStatus === 'DOWN')
    .forEach((c) => {
      events.push({
        kind: '渠道异常',
        title: c.name,
        detail: `${c.code} · 状态 ${statusLabel(c.lastHealthStatus)}`,
        suggestion: '检查渠道凭证与上游可用性，必要时停用或切流',
        owner: '渠道负责人',
      })
    })

  ;(routing.value?.circuits || [])
    .filter((c) => c.state === 'OPEN')
    .forEach((c) => {
      events.push({
        kind: '熔断器熔断',
        title: circuitModelCode(c),
        detail: `失败率 ${c.failureRate.toFixed(1)}% · 样本 ${c.bufferedCalls}`,
        suggestion: '查看路由目标，手动切流或重置熔断器',
        owner: '路由负责人',
      })
    })

  outbox.value
    .filter((e) => e.status === 'FAILED')
    .slice(0, 3)
    .forEach((e) => {
      events.push({
        kind: '发件箱失败',
        title: eventLabel(e.eventType),
        detail: `业务键 ${e.businessKey} · 尝试 ${e.attemptCount} 次`,
        suggestion: e.lastError ? outboxErrorLabel(e.lastError) : '重试或排查上游',
        owner: '运维负责人',
        time: formatDateTime(e.createdAt),
      })
    })

  return events.slice(0, 8)
})

/** 定时任务执行记录：发件箱最近事件（含失败/重试） */
const taskRunLog = computed(() => {
  return outbox.value
    .slice()
    .sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || ''))
    .slice(0, 6)
    .map((e) => ({
      id: e.id,
      name: eventLabel(e.eventType),
      status: e.status,
      attempts: e.attemptCount,
      time: formatDateTime(e.createdAt),
      error: e.lastError ? outboxErrorLabel(e.lastError) : '',
    }))
})

const templateLabels: Record<string, string> = {
  REGISTER_CODE: '注册验证码', LOGIN_CODE: '登录验证码', PASSWORD_CODE: '密码验证码',
  PAYMENT_CONFIRM_CODE: '支付确认验证码', API_KEY_RESET_CODE: '密钥重置验证码',
  PHONE_CHANGE_CODE: '更换手机号验证码', PACKAGE_PAYMENT_SUCCEEDED: '套餐支付成功',
  PACKAGE_EXPIRES_IN_3_DAYS: '套餐即将到期', ORDER_PAYMENT_FAILED: '订单支付失败',
  PACKAGE_EXPIRED: '套餐已过期', API_RATE_LIMITED: '接口限流提醒',
  REFUND_SUCCEEDED: '退款成功', ORDER_TIMED_OUT: '订单超时',
}
const templateTypeLabels: Record<string, string> = { VERIFICATION: '验证码', NOTIFICATION: '业务通知' }
const outboxEventLabels: Record<string, string> = {
  PACKAGE_GRANT_REQUESTED: '套餐发放请求', PACKAGE_GRANT_ACTIVATED: '套餐发放完成',
  PACKAGE_GRANTED: '套餐发放', PACKAGE_GRANTED_V1: '套餐发放', PACKAGE_REFUNDED_V1: '套餐退款',
  ORDER_PAID: '订单支付完成', REFUND_COMPLETED: '退款完成',
  NOTIFICATION_BROADCAST_PUBLISHED: '维护广播发布', SUPPORT_TICKET_COMPLETED: '工单完成',
}
const outboxErrorLabels: Record<string, string> = {
  IllegalArgumentException: '参数异常',
  IllegalStateException: '状态异常',
  DataIntegrityViolationException: '数据一致性异常',
  TimeoutException: '处理超时',
  ConnectException: '连接失败',
  WebClientRequestException: '上游请求失败',
}
function templateLabel(value: string) { return templateLabels[value] || value }
function eventLabel(value: string) { return outboxEventLabels[value] || value }
function outboxErrorLabel(value?: string | null) {
  const normalized = value?.trim()
  if (!normalized) return '-'
  const type = normalized.match(/^([\w.$]+)(?::|\s|$)/)?.[1]
  if (type && outboxErrorLabels[type]) return outboxErrorLabels[type]
  return /[\u4e00-\u9fff]/.test(normalized) ? normalized : '处理失败'
}

onMounted(load)
</script>

<template>
  <section class="admin-page ops-page" aria-labelledby="admin-operations-title">
    <header class="ops-head">
      <div class="ops-head__title">
        <p class="ops-head__eyebrow">SRE CONSOLE · 系统运维</p>
        <h2 id="admin-operations-title">运行维护</h2>
        <p class="ops-head__sub">检查网关、数据库、路由熔断与发件箱状态，执行可审计的恢复操作。</p>
      </div>
      <el-button :loading="loading" @click="load"><RefreshCw :size="16" />刷新</el-button>
    </header>
    <TopErrorMessage :message="errorMessage" />

    <!-- 顶部：运行状态概览（真实 health 指标，左侧状态色轨） -->
    <div class="admin-metrics operations-metrics">
      <button class="admin-metric ops-metric" :class="`ops-metric--${toneOf(gateway?.status || 'UNKNOWN')}`" type="button" @click="scrollToPanel('routing')"><span><Activity :size="14" />网关</span><strong><el-tag size="small" :type="statusType(gateway?.status || 'UNKNOWN')">{{ statusLabel(gateway?.status) }}</el-tag></strong></button>
      <button class="admin-metric ops-metric" :class="`ops-metric--${toneOf(gateway?.database || 'UNKNOWN')}`" type="button" @click="scrollToPanel('routing')"><span><Database :size="14" />数据库</span><strong><el-tag size="small" :type="statusType(gateway?.database || 'UNKNOWN')">{{ statusLabel(gateway?.database) }}</el-tag></strong></button>
      <button class="admin-metric ops-metric" type="button" @click="scrollToPanel('maintenance')"><span><HardDrive :size="14" />过期预占</span><strong>{{ gateway?.expiredReservations ?? '-' }}</strong></button>
      <button class="admin-metric ops-metric" type="button" @click="scrollToPanel('maintenance')"><span><Trash2 :size="14" />待清理文件</span><strong>{{ gateway?.cleanupCandidates ?? '-' }}</strong></button>
    </div>

    <!-- 服务健康 + 最近异常事件 -->
    <div class="ops-health-grid">
      <div class="ops-panel-card ops-health">
        <header class="ops-health__head"><h3><Server :size="14" />服务健康状态</h3><span class="ops-health__summary"><i class="dot dot--ok" />{{ healthSummary.ok }} 正常<i class="dot dot--warn" />{{ healthSummary.warn }} 警告<i class="dot dot--bad" />{{ healthSummary.bad }} 异常</span></header>
        <ul v-if="serviceHealth.length" class="ops-health__list">
          <li v-for="svc in serviceHealth" :key="svc.key" class="ops-health__item" :class="`ops-health__item--${svc.tone}`">
            <span class="ops-health__status" :class="`ops-health__status--${svc.tone}`" />
            <div class="ops-health__meta"><strong>{{ svc.name }}</strong><span>{{ svc.detail }}</span></div>
            <span class="ops-health__tag" :class="`ops-health__tag--${svc.tone}`">{{ svc.label }}</span>
          </li>
        </ul>
        <p v-else class="ops-panel-card__empty">健康数据加载中…</p>
      </div>

      <div class="ops-panel-card ops-anomaly">
        <header><h3><ShieldAlert :size="14" />最近系统异常事件</h3></header>
        <ul v-if="anomalyEvents.length" class="ops-anomaly__list">
          <li v-for="(event, index) in anomalyEvents" :key="index" class="ops-anomaly__item">
            <span class="ops-anomaly__kind">{{ event.kind }}</span>
            <strong class="ops-anomaly__title">{{ event.title }}</strong>
            <p class="ops-anomaly__detail">{{ event.detail }}</p>
            <div class="ops-anomaly__foot"><span class="ops-anomaly__suggestion">{{ event.suggestion }}</span><span class="ops-anomaly__owner">{{ event.owner }}<template v-if="event.time"> · {{ event.time }}</template></span></div>
          </li>
        </ul>
        <p v-else class="ops-panel-card__empty">暂无系统异常，运行平稳</p>
      </div>
    </div>

    <div ref="maintenancePanelRef" class="admin-panel operation-panel">
      <div class="admin-panel__header"><div><h3>维护操作</h3><p>操作提交前需要二次确认，同一业务号可安全重放</p></div></div>
      <div class="operation-controls">
        <el-button data-test="operation-recover" :loading="running === 'recover'" :disabled="running !== ''" @click="runOperation('recover')"><RotateCcw :size="16" />恢复过期预占</el-button>
        <el-button data-test="operation-cleanup" type="danger" plain :loading="running === 'cleanup'" :disabled="running !== ''" @click="runOperation('cleanup')"><Trash2 :size="16" />清理失效文件</el-button>
        <div v-if="lastRun" class="operation-result" aria-live="polite"><span>{{ lastRun.label }}</span><code>{{ lastRun.businessNo }}</code><el-tag v-if="lastRun.result" size="small" :type="statusType(lastRun.result.status)">{{ statusLabel(lastRun.result.status) }}<template v-if="lastRun.result.affected != null"> · {{ lastRun.result.affected }}</template></el-tag></div>
      </div>
    </div>

    <div ref="routingPanelRef" class="admin-panel">
      <div class="admin-panel__header"><div><h3>路由与熔断</h3><p>渠道健康状态及当前熔断器窗口</p></div></div>
      <div class="admin-status-grid routing-grid">
        <div v-for="channel in routing?.channels || []" :key="channel.id" class="admin-status-item"><div><strong>{{ channel.name }}</strong><div class="operation-meta">{{ channel.code }}</div></div><el-tag size="small" :type="statusType(channel.lastHealthStatus)">{{ statusLabel(channel.lastHealthStatus) }}</el-tag></div>
        <div v-for="circuit in routing?.circuits || []" :key="circuit.key" class="admin-status-item"><div><strong>{{ circuitModelCode(circuit) }}</strong><div class="operation-meta"><code>{{ circuit.key }}</code> · <template v-if="circuit.failureRate < 0">暂无统计样本</template><template v-else>失败率 {{ circuit.failureRate.toFixed(1) }}%</template> · 样本 {{ circuit.bufferedCalls }}</div></div><div class="circuit-actions"><el-tag size="small" :type="statusType(circuit.state)">{{ statusLabel(circuit.state) }}</el-tag><el-button text :data-test="`circuit-open-${circuit.key}`" :aria-label="circuit.state === 'OPEN' ? '处理异常渠道' : '查看熔断器详情'" @click="openCircuit(circuit)"><Settings2 v-if="circuit.state === 'OPEN'" :size="16" /><Eye v-else :size="16" /></el-button></div></div>
        <div v-if="!routing?.channels.length && !routing?.circuits.length && !loading" class="admin-empty">暂无路由状态</div>
      </div>
    </div>

    <div class="admin-panel sms-panel">
      <div class="admin-panel__header">
        <div><h3>短信运营</h3><p>查看模板接入状态与脱敏投递记录，失败记录可安全重试</p></div>
        <div class="sms-header-actions">
          <span v-if="smsLastRetry" class="sms-last-retry" aria-live="polite">业务号 <code>{{ smsLastRetry }}</code></span>
          <el-button data-test="sms-refresh" :loading="smsLoading" @click="loadSms"><RefreshCw :size="16" />刷新</el-button>
        </div>
      </div>
      <TopErrorMessage :message="smsErrorMessage" />
      <el-tabs v-model="smsTab" class="sms-tabs">
        <el-tab-pane name="templates">
          <template #label><span data-test="sms-templates-tab">模板状态</span></template>
          <el-skeleton v-if="smsTemplatesLoading" data-test="sms-templates-loading" :rows="5" animated />
          <div v-else class="table-scroll sms-table-scroll" tabindex="0" aria-label="短信模板状态表">
            <table data-test="sms-templates-table" class="admin-table">
              <thead><tr><th>模板键</th><th>模板编码</th><th>类型</th><th>接入状态</th><th>更新时间</th></tr></thead>
              <tbody>
                <tr v-for="template in smsTemplates" :key="template.templateKey">
                  <td><strong>{{ templateLabel(template.templateKey) }}</strong><div class="operation-meta"><code>{{ template.templateKey }}</code></div></td>
                  <td><code>{{ template.templateCode }}</code></td>
                  <td>{{ templateTypeLabels[template.templateType] || template.templateType }}</td>
                  <td><el-tag size="small" :type="statusType(template.integrationStatus)">{{ statusLabel(template.integrationStatus) }}</el-tag></td>
                <td>{{ formatDateTime(template.updatedAt) }}</td>
                </tr>
                <tr v-if="!smsTemplates.length"><td data-test="sms-templates-empty" colspan="5" class="admin-empty">暂无短信模板</td></tr>
              </tbody>
            </table>
          </div>
        </el-tab-pane>
        <el-tab-pane name="deliveries">
          <template #label><span data-test="sms-deliveries-tab">投递记录</span></template>
          <div class="admin-toolbar sms-toolbar">
            <el-input v-model="smsParams.query" data-test="sms-query" aria-label="搜索短信投递" clearable placeholder="精确查询投递 ID、手机后四位或模板键" @keyup.enter="loadSmsDeliveries(0)" />
            <el-select v-model="smsParams.status" data-test="sms-status" aria-label="短信投递状态" clearable placeholder="全部状态">
              <el-option label="待处理" value="PENDING" /><el-option label="发送中" value="SENDING" /><el-option label="已发送" value="SENT" /><el-option label="失败" value="FAILED" /><el-option label="已终止" value="DEAD" />
            </el-select>
            <el-select v-model="smsParams.templateKey" data-test="sms-template" aria-label="短信模板" clearable filterable placeholder="全部模板">
              <el-option v-for="template in smsTemplates" :key="template.templateKey" :label="template.templateKey" :value="template.templateKey" />
            </el-select>
            <el-select v-model="smsParams.sort" data-test="sms-sort" aria-label="短信投递排序字段">
              <el-option label="创建时间" value="createdAt" /><el-option label="更新时间" value="updatedAt" /><el-option label="状态" value="status" /><el-option label="尝试次数" value="attemptCount" /><el-option label="下次执行" value="nextAttemptAt" />
            </el-select>
            <el-select v-model="smsParams.direction" data-test="sms-direction" aria-label="短信投递排序方向"><el-option label="降序" value="desc" /><el-option label="升序" value="asc" /></el-select>
            <el-button @click="clearSmsFilters">清空</el-button>
            <el-button data-test="sms-filter" type="primary" :loading="smsDeliveriesLoading" @click="loadSmsDeliveries(0)"><Search :size="16" />查询</el-button>
          </div>
          <el-skeleton v-if="smsDeliveriesLoading" data-test="sms-deliveries-loading" :rows="5" animated />
          <div v-else class="table-scroll sms-table-scroll" tabindex="0" aria-label="短信投递记录表">
            <table data-test="sms-deliveries-table" class="admin-table sms-delivery-table">
              <thead><tr><th>用户</th><th>模板</th><th>接收号码</th><th>状态</th><th>尝试</th><th>下次执行</th><th>发送时间</th><th>失败原因</th><th class="sms-action-cell">操作</th></tr></thead>
              <tbody>
                <tr v-for="delivery in smsDeliveries" :key="delivery.id">
                  <td><strong>{{ userNameLabel(delivery.userDisplayName) }}</strong><div class="operation-meta">{{ userNoLabel(delivery.userNo) }}</div></td>
                  <td><strong>{{ templateLabel(delivery.templateKey) }}</strong><div class="operation-meta">{{ delivery.templateCode }}</div></td>
                  <td><code>{{ delivery.maskedPhone }}</code></td>
                  <td><el-tag size="small" :type="statusType(delivery.status)">{{ statusLabel(delivery.status) }}</el-tag></td>
                  <td>{{ delivery.attemptCount }}</td>
                <td>{{ formatDateTime(delivery.nextAttemptAt) }}</td>
                <td>{{ formatDateTime(delivery.sentAt) }}</td>
                  <td><span class="sms-error-cell" :title="delivery.sanitizedError || ''">{{ delivery.sanitizedError || '-' }}</span></td>
                  <td class="sms-action-cell">
                    <el-button
                      v-if="['FAILED', 'DEAD'].includes(delivery.status)"
                      :data-test="`sms-retry-${delivery.id}`"
                      size="small"
                      :aria-label="`重试 ${delivery.templateKey} 短信`"
                      :loading="smsRunning === delivery.id"
                      :disabled="smsRunning !== ''"
                      @click="retrySms(delivery)"
                    ><RotateCcw :size="14" />重试</el-button>
                    <span v-else>-</span>
                  </td>
                </tr>
                <tr v-if="!smsDeliveries.length"><td data-test="sms-deliveries-empty" colspan="9" class="admin-empty">暂无短信投递记录</td></tr>
              </tbody>
            </table>
          </div>
          <div class="admin-pagination"><span>共 {{ smsTotal }} 条</span><el-pagination data-test="sms-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="smsParams.page + 1" :page-size="smsParams.size" :page-sizes="pageSizeOptions" :total="smsTotal" @size-change="changeSmsPageSize" @current-change="(value: number) => loadSmsDeliveries(value - 1)" /></div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <div class="admin-panel">
      <div class="admin-panel__header"><div><h3>发件箱</h3><p>待处理 {{ outboxHealth?.pending ?? '-' }} · 投递中 {{ outboxHealth?.delivering ?? '-' }} · 失败 {{ outboxHealth?.failed ?? '-' }} · 最老积压 {{ outboxHealth?.oldestSeconds ?? '-' }} 秒</p></div><el-button :loading="outboxLoading" @click="loadOutbox()"><RefreshCw :size="16" />刷新</el-button></div>
      <div class="admin-toolbar outbox-toolbar">
        <el-input v-model="outboxParams.query" data-test="outbox-query" aria-label="搜索发件箱事件" clearable placeholder="搜索业务键、事件或聚合 ID" @keyup.enter="loadOutbox(0)" />
        <el-select v-model="outboxParams.status" data-test="outbox-status" aria-label="发件箱状态" clearable placeholder="全部状态"><el-option label="待处理" value="PENDING" /><el-option label="投递中" value="DELIVERING" /><el-option label="已投递" value="DELIVERED" /><el-option label="失败" value="FAILED" /></el-select>
        <el-select v-model="outboxParams.sort" data-test="outbox-sort" aria-label="发件箱排序字段"><el-option label="创建时间" value="createdAt" /><el-option label="状态" value="status" /><el-option label="尝试次数" value="attemptCount" /></el-select>
        <el-select v-model="outboxParams.direction" data-test="outbox-direction" aria-label="发件箱排序方向"><el-option label="降序" value="desc" /><el-option label="升序" value="asc" /></el-select>
        <el-button @click="clearOutboxFilters">清空</el-button>
        <el-button data-test="outbox-filter" type="primary" :loading="outboxLoading" @click="loadOutbox(0)"><Search :size="16" />查询</el-button>
      </div>
      <el-skeleton v-if="outboxLoading" :rows="5" animated />
      <div v-else class="table-scroll outbox-scroll"><table class="admin-table"><thead><tr><th>业务键</th><th>事件</th><th>状态</th><th>尝试</th><th>下次执行</th><th>最近错误</th><th>操作</th></tr></thead><tbody>
          <tr v-for="event in outbox" :key="event.id"><td><code>{{ event.businessKey }}</code></td><td>{{ eventLabel(event.eventType) }}</td><td><el-tag size="small" :type="statusType(event.status)">{{ statusLabel(event.status) }}</el-tag></td><td>{{ event.attemptCount }}</td><td>{{ formatDateTime(event.nextAttemptAt) }}</td><td><el-tooltip v-if="event.lastError" :content="event.lastError" placement="top" effect="dark"><span class="outbox-error" :title="event.lastError">{{ outboxErrorLabel(event.lastError) }}</span></el-tooltip><span v-else class="outbox-error">-</span></td><td><el-button v-if="event.status === 'FAILED'" :data-test="`outbox-retry-${event.id}`" size="small" :loading="running === event.id" :disabled="running !== ''" @click="retry(event)"><RotateCcw :size="14" />重试</el-button><span v-else>-</span></td></tr>
        <tr v-if="!outbox.length"><td colspan="7" class="admin-empty">暂无发件箱事件</td></tr>
      </tbody></table></div>
      <div class="admin-pagination"><span>共 {{ outboxTotal }} 条</span><el-pagination data-test="outbox-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="outboxParams.page + 1" :page-size="outboxParams.size" :page-sizes="pageSizeOptions" :total="outboxTotal" @size-change="changeOutboxPageSize" @current-change="(value: number) => loadOutbox(value - 1)" /></div>
    </div>

    <!-- 定时任务执行记录 -->
    <div class="ops-panel-card ops-tasks">
      <header><h3><Cpu :size="14" />定时任务执行记录</h3><span class="ops-tasks__hint">最近发件箱事件执行情况</span></header>
      <ul v-if="taskRunLog.length" class="ops-tasks__list">
        <li v-for="task in taskRunLog" :key="task.id" class="ops-tasks__item">
          <span class="ops-tasks__status" :class="`ops-tasks__status--${toneOf(task.status)}`" />
          <strong class="ops-tasks__name">{{ task.name }}</strong>
          <span class="ops-tasks__meta">尝试 {{ task.attempts }} 次<template v-if="task.error"> · {{ task.error }}</template></span>
          <span class="ops-health__tag" :class="`ops-health__tag--${toneOf(task.status)}`">{{ statusLabel(task.status) }}</span>
          <time class="ops-mono">{{ task.time }}</time>
        </li>
      </ul>
      <p v-else class="ops-panel-card__empty">暂无任务执行记录</p>
    </div>

    <el-drawer v-model="circuitDrawerOpen" title="熔断器详情与路由处理" size="min(520px, 100%)"><div v-if="selectedCircuit" class="circuit-drawer"><dl class="circuit-detail"><div><dt>模型</dt><dd>{{ circuitModelCode(selectedCircuit) }}</dd></div><div><dt>标识</dt><dd><code>{{ selectedCircuit.key }}</code></dd></div><div><dt>当前状态</dt><dd><el-tag size="small" :type="statusType(selectedCircuit.state)">{{ statusLabel(selectedCircuit.state) }}</el-tag></dd></div><div><dt>失败率</dt><dd>{{ selectedCircuit.failureRate < 0 ? '暂无统计样本' : `${selectedCircuit.failureRate.toFixed(1)}%` }}</dd></div><div><dt>窗口样本</dt><dd>{{ selectedCircuit.bufferedCalls }}</dd></div></dl><div class="circuit-target-heading"><strong>可用路由渠道</strong><span>切流只影响当前模型</span></div><el-skeleton v-if="circuitTargetsLoading" :rows="4" animated /><div v-else class="circuit-target-list"><div v-for="target in circuitTargets" :key="target.channelId" class="circuit-target"><div class="circuit-target__meta"><strong>{{ target.channelName }}</strong><span>{{ target.providerName }} · {{ target.upstreamModel }}</span><span v-if="target.selected" class="circuit-target__selected">当前手动渠道</span><span v-else-if="!target.eligible" class="circuit-target__reason">{{ targetUnavailableReason(target) }}</span></div><div class="circuit-target__status"><el-tag size="small" :type="statusType(target.healthStatus)">{{ statusLabel(target.healthStatus) }}</el-tag><el-button v-if="target.eligible && !target.selected" size="small" type="primary" :data-test="`circuit-switch-${target.channelId}`" :loading="circuitRouteRunning === target.channelId" :disabled="Boolean(circuitRouteRunning)" @click="switchCircuitRoute(target)"><ArrowRightLeft :size="14" />切换到此渠道</el-button></div></div><div v-if="!circuitTargets.length" class="admin-empty">暂无该模型的路由渠道</div></div></div><template #footer><el-button type="warning" :loading="Boolean(selectedCircuit && running === `circuit:${selectedCircuit.key}`)" @click="selectedCircuit && resetCircuit(selectedCircuit)"><RotateCcw :size="16" />重置熔断器</el-button><el-button v-if="circuitTargets.some((target) => target.selected)" data-test="circuit-restore-automatic" :loading="circuitRouteRunning === 'automatic'" :disabled="Boolean(circuitRouteRunning)" @click="restoreAutomaticCircuitRoute">恢复自动路由</el-button></template></el-drawer>
  </section>
</template>

<style scoped>
.operation-panel { margin-bottom: 14px; }
.operations-metrics .admin-metric { display: flex; min-height: 64px; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 14px; }
.operations-metrics button.admin-metric { width: 100%; border: 0; color: inherit; text-align: left; cursor: pointer; }
.operations-metrics button.admin-metric:hover { background: var(--el-fill-color-light); }
.operations-metrics .admin-metric strong { display: flex; min-height: 22px; align-items: center; margin-top: 0; font-size: 18px; line-height: 22px; }
.operations-metrics .admin-metric .el-tag { box-sizing: border-box; height: 22px; line-height: 20px; padding: 0 7px; vertical-align: middle; }
.operation-controls { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding: 14px; }
.operation-result { display: flex; min-width: 0; align-items: center; flex-wrap: wrap; gap: 8px; color: var(--app-muted); font-size: 12px; }
.operation-result code { overflow-wrap: anywhere; color: var(--app-fg); }
.routing-grid { padding: 12px 14px; }
.circuit-actions { display: flex; align-items: center; gap: 8px; }
.operation-meta { margin-top: 3px; color: var(--app-muted); font-size: 12px; }
.outbox-toolbar { padding: 12px 14px 0; }
.sms-panel :deep(.el-alert) { border-radius: 0; }
.sms-tabs :deep(.el-tabs__header) { margin: 0; padding: 0 14px; border-bottom: 1px solid var(--app-border); }
.sms-tabs :deep(.el-tabs__nav-wrap::after) { display: none; }
.sms-tabs :deep(.el-tabs__content) { overflow: visible; }
.sms-header-actions { display: flex; min-width: 0; align-items: center; justify-content: flex-end; gap: 10px; }
.sms-last-retry { min-width: 0; color: var(--app-muted); font-size: 12px; }
.sms-last-retry code { overflow-wrap: anywhere; color: var(--app-fg); }
.sms-toolbar { margin: 0; padding: 12px 14px; border-bottom: 1px solid var(--app-border); }
.sms-table-scroll { max-height: 420px; overflow: auto; }
.sms-table-scroll:focus-visible { outline: 2px solid var(--app-primary); outline-offset: -2px; }
.sms-delivery-table { min-width: 1080px; }
.sms-error-cell, .outbox-error { display: block; max-width: 280px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.outbox-scroll { max-height: 460px; overflow: auto; }
.circuit-detail { display: grid; margin: 0; }
.circuit-detail > div { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--app-border); }
.circuit-detail dt { color: var(--app-muted); }.circuit-detail dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.circuit-drawer { display: flex; flex-direction: column; gap: 18px; }
.circuit-target-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; color: var(--app-muted); font-size: 12px; }
.circuit-target-heading strong { color: var(--app-text); font-size: 14px; }
.circuit-target-list { display: grid; gap: 8px; }
.circuit-target { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 12px; padding: 12px; border: 1px solid var(--app-border); border-radius: 6px; }
.circuit-target__meta { display: grid; min-width: 0; gap: 4px; }
.circuit-target__meta > span { overflow-wrap: anywhere; color: var(--app-muted); font-size: 12px; }
.circuit-target__status { display: flex; flex-shrink: 0; align-items: center; gap: 8px; }
.circuit-target__selected { color: var(--el-color-success) !important; }
.circuit-target__reason { color: var(--app-muted) !important; }
.sms-action-cell { width: 104px; min-width: 104px; text-align: center !important; }
@media (max-width: 600px) {
  .operations-metrics .admin-metric { min-height: 56px; }
  .operation-controls > .el-button { width: 100%; margin-left: 0; }
  .sms-header-actions { align-items: stretch; flex-direction: column; }
  .sms-header-actions .el-button { width: 100%; margin-left: 0; }
  .sms-table-scroll { max-height: 360px; }
}

/* =========================================================
   系统运维页 — 运维控制台装修（克制专业，不做监控大屏炫光）
   健康色：正常=柔和绿 / 异常=低饱和红 / 警告=黄
   ========================================================= */
.ops-page { max-width: 1520px; }
.ops-mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; letter-spacing: -0.01em; }

/* ===== 页头 ===== */
.ops-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 16px; }
.ops-head__eyebrow { margin: 0 0 4px; font-family: var(--font-mono); font-size: 11px; font-weight: 500; letter-spacing: 0.18em; color: var(--app-muted); }
.ops-head h2 { margin: 0; font-family: var(--font-display); font-size: 27px; font-weight: 400; letter-spacing: -0.02em; color: var(--app-fg); }
.ops-head__sub { margin: 6px 0 0; max-width: 62ch; font-size: 12.5px; line-height: 1.65; color: var(--app-muted-strong); }

/* ===== 顶部 metric：左侧健康色轨 + 等宽数字 ===== */
.operations-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.operations-metrics .admin-metric { position: relative; padding: 12px 14px 12px 16px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 8px; }
.operations-metrics .admin-metric::before { content: ''; position: absolute; left: 0; top: 8px; bottom: 8px; width: 3px; border-radius: 2px; background: var(--app-border-strong); }
.ops-metric--ok::before { background: var(--health-ok-rail) !important; }
.ops-metric--bad::before { background: var(--health-bad-rail) !important; }
.ops-metric--warn::before { background: var(--health-warn-rail) !important; }
.ops-metric--info::before { background: var(--health-info-rail) !important; }
.ops-metric--ok { background: linear-gradient(90deg, var(--health-ok-soft) 0%, var(--app-surface) 42%) !important; }
.ops-metric--bad { background: linear-gradient(90deg, var(--health-bad-soft) 0%, var(--app-surface) 42%) !important; }
.ops-metric--warn { background: linear-gradient(90deg, var(--health-warn-soft) 0%, var(--app-surface) 42%) !important; }
.operations-metrics .admin-metric span { display: flex; align-items: center; gap: 6px; }
.operations-metrics .admin-metric strong { font-family: var(--font-display); font-variant-numeric: tabular-nums; }

/* ===== 服务健康 + 异常事件 双栏 ===== */
.ops-health-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr); gap: 12px; margin-bottom: 14px; }
.ops-panel-card { padding: 14px 16px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 10px; }
.ops-panel-card > header { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 10px; padding-bottom: 8px; border-bottom: 1px solid var(--app-border); }
.ops-panel-card h3 { display: flex; align-items: center; gap: 6px; margin: 0; font-size: 12.5px; font-weight: 700; color: var(--app-fg); }
.ops-panel-card__empty { margin: 8px 0 2px; font-size: 11.5px; line-height: 1.6; color: var(--app-muted); }

.ops-health__head { flex-wrap: wrap; }
.ops-health__summary { display: inline-flex; align-items: center; gap: 5px; font-size: 11px; color: var(--app-muted-strong); }
.ops-health__summary .dot { width: 7px; height: 7px; border-radius: 50%; margin-left: 8px; }
.ops-health__summary .dot:first-child { margin-left: 0; }
.ops-health__summary .dot--ok { background: var(--health-ok-rail); }
.ops-health__summary .dot--warn { background: var(--health-warn-rail); }
.ops-health__summary .dot--bad { background: var(--health-bad-rail); }
.ops-health__list { list-style: none; display: grid; gap: 4px; margin: 0; padding: 0; }
.ops-health__item { display: flex; align-items: center; gap: 10px; padding: 8px 10px; background: var(--app-surface-subtle); border: 1px solid transparent; border-radius: 8px; }
.ops-health__status { width: 8px; height: 8px; border-radius: 50%; flex: none; }
.ops-health__status--ok { background: var(--health-ok-rail); }
.ops-health__status--bad { background: var(--health-bad-rail); }
.ops-health__status--warn { background: var(--health-warn-rail); }
.ops-health__status--info { background: var(--health-info-rail); }
.ops-health__meta { display: grid; min-width: 0; flex: 1; }
.ops-health__meta strong { font-size: 12.5px; color: var(--app-fg); }
.ops-health__meta span { font-size: 11px; color: var(--app-muted); }
.ops-health__tag { display: inline-flex; align-items: center; padding: 1px 8px; border: 1px solid transparent; border-radius: 999px; font-size: 11px; font-weight: 600; white-space: nowrap; }
.ops-health__tag--ok { color: var(--health-ok); background: var(--health-ok-soft); border-color: var(--app-success-border); }
.ops-health__tag--bad { color: var(--health-bad); background: var(--health-bad-soft); border-color: var(--app-danger-border); }
.ops-health__tag--warn { color: var(--health-warn); background: var(--health-warn-soft); border-color: var(--app-warning-border); }
.ops-health__tag--info { color: var(--health-info); background: var(--health-info-soft); border-color: var(--app-border-strong); }

/* ===== 异常事件 ===== */
.ops-anomaly__list { list-style: none; display: grid; gap: 8px; margin: 0; padding: 0; }
.ops-anomaly__item { display: grid; gap: 3px; padding: 9px 10px 9px 12px; background: var(--app-surface-subtle); border: 1px solid var(--app-border); border-left: 2px solid var(--health-bad-rail); border-radius: 8px; }
.ops-anomaly__kind { font-size: 10px; font-weight: 600; letter-spacing: 0.04em; color: var(--health-bad); }
.ops-anomaly__title { font-size: 12.5px; color: var(--app-fg); }
.ops-anomaly__detail { margin: 0; font-size: 11px; color: var(--app-muted-strong); }
.ops-anomaly__foot { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
.ops-anomaly__suggestion { font-size: 11px; color: var(--app-muted-strong); }
.ops-anomaly__owner { font-size: 10.5px; color: var(--app-muted); white-space: nowrap; }

/* ===== 定时任务执行记录 ===== */
.ops-tasks { margin-bottom: 14px; }
.ops-tasks__hint { font-size: 11px; color: var(--app-muted); }
.ops-tasks__list { list-style: none; display: grid; gap: 4px; margin: 0; padding: 0; }
/* 5 个子元素（状态点 / 名称 / 说明 / 状态标签 / 时间）必须有 5 列：
   原来只声明 4 列，时间会被挤到第二行并落进 8px 的状态点列，整列看不见（实测可视宽 8px）。 */
.ops-tasks__item { display: grid; grid-template-columns: 8px minmax(0, 1fr) auto auto auto; gap: 10px; align-items: center; padding: 8px 10px; background: var(--app-surface-subtle); border: 1px solid transparent; border-radius: 8px; }
.ops-tasks__status { width: 8px; height: 8px; border-radius: 50%; }
.ops-tasks__status--ok { background: var(--health-ok-rail); }
.ops-tasks__status--bad { background: var(--health-bad-rail); }
.ops-tasks__status--warn { background: var(--health-warn-rail); }
.ops-tasks__status--info { background: var(--health-info-rail); }
.ops-tasks__name { font-size: 12.5px; color: var(--app-fg); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ops-tasks__meta { font-size: 11px; color: var(--app-muted); }
.ops-tasks__item time { font-size: 10.5px; color: var(--app-muted); white-space: nowrap; }

/* ===== 入场序列 ===== */
@keyframes ops-rise { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: none; } }
.operations-metrics .admin-metric, .ops-health, .ops-anomaly, .ops-tasks { animation: ops-rise 420ms cubic-bezier(0.16, 1, 0.3, 1) both; }
.operations-metrics .admin-metric:nth-child(2) { animation-delay: 70ms; }
.operations-metrics .admin-metric:nth-child(3) { animation-delay: 140ms; }
.operations-metrics .admin-metric:nth-child(4) { animation-delay: 210ms; }
.ops-anomaly { animation-delay: 120ms; }

@media (prefers-reduced-motion: reduce) {
  .operations-metrics .admin-metric, .ops-health, .ops-anomaly, .ops-tasks { animation: none; }
}

/* ===== 响应式 ===== */
@media (max-width: 1080px) {
  .ops-health-grid { grid-template-columns: 1fr; }
}
@media (max-width: 800px) {
  .operations-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .ops-head { align-items: flex-start; flex-direction: column; }
  .ops-tasks__item { grid-template-columns: 8px minmax(0, 1fr) auto; }
  .ops-tasks__item time { grid-column: 2; }
}
@media (max-width: 500px) {
  .operations-metrics { grid-template-columns: 1fr; }
}
</style>
