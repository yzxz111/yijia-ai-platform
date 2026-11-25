<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Copy, Download, RefreshCw, Search } from 'lucide-vue-next'

import { listAudit, type AuditEntry, type AuditListParams } from '../../api/admin'
import { formatDateTime } from '../../utils/dateTime'

const entries = ref<AuditEntry[]>([])
const total = ref(0)
const page = ref(0)
const size = ref(20)
const pageSizeOptions = [5, 10, 20]
const actorUserId = ref('')
const action = ref('')
const resourceType = ref('')
const resourceId = ref('')
const outcome = ref('')
const sort = ref('createdAt')
const direction = ref<'asc' | 'desc'>('desc')
const timeRange = ref<Date[]>([])
const loading = ref(true)
const exporting = ref(false)
const errorMessage = ref('')

const actionLabels: Record<string, string> = {
  USER_UPDATED: '用户状态或角色更新',
  PHONE_BOUND: '绑定手机号',
  PHONE_CHANGED: '更换手机号',
  SMS_PREFERENCE_CHANGED: '短信偏好变更',
  DISPLAY_NAME_CHANGED: '显示名称变更',
  LOGIN_DEVICE_LIMIT_CHANGED: '登录设备上限变更',
  PASSWORD_CHANGED: '密码变更',
  PAYMENT_CONFIG_UPDATED: '支付配置更新',
  PACKAGE_GRANT_CREATED: '套餐发放创建',
  PACKAGE_PLAN_CREATED: '套餐创建',
  PACKAGE_VERSION_CREATED: '套餐版本创建',
  PACKAGE_VERSION_PUBLISHED: '套餐版本发布',
  PACKAGE_PLAN_UPDATED: '套餐信息更新',
  PACKAGE_PLAN_ARCHIVED: '套餐下架',
  PACKAGE_PLAN_RESTORED: '套餐恢复上线',
  ORDER_CREATED: '订单创建',
  ORDER_CLOSED: '订单关闭',
  PAYMENT_POSTED: '订单支付入账',
  RECHARGE_PAYMENT_POSTED: '充值支付入账',
  MOCK_PAYMENT_SIMULATED: '模拟支付',
  PAYMENT_ANOMALY_UPDATED: '支付异常更新',
  REFUND_APPLIED: '退款申请',
  REFUND_APPROVED: '退款审核通过',
  REFUND_REJECTED: '退款审核驳回',
  REFUND_COMPLETED: '退款完成',
  REFUND_POLICY_PUBLISHED: '退款策略发布',
  RECONCILIATION_STARTED: '启动对账',
  OUTBOX_RETRIED: '发件箱重试',
  NOTIFICATION_BROADCAST_CREATED: '创建维护广播',
  NOTIFICATION_BROADCAST_EDITED: '编辑维护广播',
  NOTIFICATION_BROADCAST_SCHEDULED: '计划维护广播',
  NOTIFICATION_BROADCAST_PUBLISHED: '发布维护广播',
  NOTIFICATION_BROADCAST_PAUSED: '暂停维护广播',
  NOTIFICATION_BROADCAST_RESUMED: '恢复维护广播',
  NOTIFICATION_BROADCAST_CANCELED: '取消维护广播',
  SUPPORT_TICKET_CLAIMED: '领取工单',
  SUPPORT_TICKET_PROCESSING: '开始处理工单',
  SUPPORT_TICKET_REPLIED: '回复工单',
  SUPPORT_TICKET_COMPLETED: '完成工单',
  SUPPORT_TICKET_CLOSED: '关闭工单',
  SMS_DELIVERY_RETRIED: '短信投递重试',
  SMS_TEMPLATE_ACTIVATED: '短信模板启用',
  SMS_TEMPLATE_TEST_QUEUED: '短信测试排队',
  WALLET_RECHARGE_OPTIONS_REPLACED: '充值档位更新',
  OPERATING_FACT_INGEST_REJECTED: '运营数据接入拒绝',
  API_KEY_RESET: '重置 API 密钥',
  GATEWAY_RESERVATIONS_RECOVERED: '恢复过期预占',
  GATEWAY_FILES_CLEANED: '清理失效文件',
  GATEWAY_MODEL_CREATED: '创建网关模型',
  GATEWAY_MODEL_UPDATED: '更新网关模型',
  GATEWAY_MODEL_ENABLED: '上架网关模型',
  GATEWAY_MODEL_DISABLED: '下架网关模型',
  GATEWAY_MODEL_DELETED: '删除网关模型',
  GATEWAY_CHANNEL_CREATED: '创建网关渠道',
  GATEWAY_CHANNEL_UPDATED: '更新网关渠道',
  GATEWAY_PROVIDER_CREATED: '创建服务提供商',
  GATEWAY_CIRCUIT_RESET: '重置熔断器',
  'skill.create': '创建技能',
  'skill.update': '更新技能',
  'skill.publish': '上架技能',
  'skill.unpublish': '下架技能',
  'skill.delete': '删除技能',
  'skill.version.upload': '上传技能版本',
  'skill.version.replace': '替换技能版本资源包',
  'skill.version.publish': '发布技能版本',
  'skill.version.delete': '删除技能版本',
  'skill.members.replace': '更新技能成员花名册',
}

const resourceTypeLabels: Record<string, string> = {
  USER: '用户',
  USER_PHONE: '用户手机号',
  USER_SELF: '用户个人设置',
  PAYMENT_CONFIG: '支付配置',
  GRANT: '套餐发放',
  PACKAGE_VERSION: '套餐版本',
  PACKAGE_PLAN: '套餐',
  ORDER: '订单',
  RECHARGE_ORDER: '充值订单',
  PAYMENT_ANOMALY: '支付异常',
  REFUND: '退款',
  REFUND_POLICY: '退款策略',
  RECONCILIATION_RUN: '对账任务',
  OUTBOX: '发件箱',
  NOTIFICATION_BROADCAST: '维护广播',
  SUPPORT_TICKET: '支持工单',
  SMS_DELIVERY: '短信投递',
  SMS_TEMPLATE: '短信模板',
  RECHARGE_OPTION_SET: '充值档位',
  OPERATING_ALERT: '运营告警',
  USAGE_ECONOMICS_EVENT: '用量经济事件',
  API_KEY: 'API 密钥',
  GATEWAY_OPERATION: '网关维护操作',
  GATEWAY_MODEL: '网关模型',
  GATEWAY_CHANNEL: '网关渠道',
  GATEWAY_PROVIDER: '服务提供商',
  GATEWAY_CIRCUIT: '熔断器',
  SKILL: '技能',
}

const outcomeLabels: Record<string, string> = {
  SUCCESS: '成功',
  FAILURE: '失败',
  FAILED: '失败',
}

const auditActionOptions = Object.entries(actionLabels).map(([value, label]) => ({ value, label }))
const auditResourceTypeOptions = Object.entries(resourceTypeLabels).map(([value, label]) => ({ value, label }))

function labelOf(labels: Record<string, string>, value?: string) {
  return value ? labels[value] || value : '-'
}

function filterParams(targetPage: number, targetSize = size.value): AuditListParams {
  return {
    page: targetPage,
    size: targetSize,
    query: '',
    status: '',
    sort: sort.value,
    direction: direction.value,
    ...(actorUserId.value.trim() ? { actorUserId: actorUserId.value.trim() } : {}),
    ...(action.value.trim() ? { action: action.value.trim() } : {}),
    ...(resourceType.value.trim() ? { resourceType: resourceType.value.trim() } : {}),
    ...(resourceId.value.trim() ? { resourceId: resourceId.value.trim() } : {}),
    ...(outcome.value ? { outcome: outcome.value } : {}),
    ...(timeRange.value.length === 2
      ? { from: timeRange.value[0].toISOString(), to: timeRange.value[1].toISOString() }
      : {}),
  }
}

async function load(targetPage = page.value) {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listAudit(filterParams(targetPage))
    entries.value = result.items
    total.value = result.total
    page.value = result.page
  } catch {
    errorMessage.value = '审计记录加载失败，请检查筛选条件或稍后重试。'
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  return load(0)
}

function clearFilters() {
  actorUserId.value = ''
  action.value = ''
  resourceType.value = ''
  resourceId.value = ''
  outcome.value = ''
  timeRange.value = []
  return load(0)
}

function changePageSize(value: number) {
  size.value = value
  return load(0)
}

function outcomeType(value: string): 'success' | 'danger' | 'info' {
  if (value === 'SUCCESS') return 'success'
  if (['FAILURE', 'FAILED'].includes(value)) return 'danger'
  return 'info'
}

function detail(value: Record<string, unknown>) {
  return JSON.stringify(value)
}

function entryKey(entry: AuditEntry) {
  return `${entry.source}:${entry.id}`
}

function actorName(entry: AuditEntry) {
  if (!entry.actorUserId) return '系统任务'
  return entry.actorDisplayName || entry.actorUserNo || '管理员'
}

async function copyText(value: string, label: string) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value)
    } else {
      const field = document.createElement('textarea')
      field.value = value
      field.setAttribute('readonly', '')
      field.style.position = 'fixed'
      field.style.opacity = '0'
      document.body.appendChild(field)
      field.select()
      document.execCommand('copy')
      field.remove()
    }
    ElMessage.success(`${label}已复制`)
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

function csvCell(value: unknown) {
  let text = value == null ? '' : String(value)
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`
  return /[",\n\r]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text
}

async function exportAudit() {
  exporting.value = true
  try {
    const batchSize = 100
    const first = await listAudit(filterParams(0, batchSize))
    if (first.total > 50_000) {
      ElMessage.warning('筛选结果超过 50000 条，请缩小时间范围后导出')
      return
    }
    const exported = [...first.items]
    const pageCount = Math.ceil(first.total / batchSize)
    for (let targetPage = 1; targetPage < pageCount; targetPage += 1) {
      const result = await listAudit(filterParams(targetPage, batchSize))
      exported.push(...result.items)
    }
    const rows = exported.map((entry) => [
      entry.source,
      entry.actorUserNo || '',
      entry.actorDisplayName || '',
      entry.actorUserId || 'SYSTEM',
      labelOf(actionLabels, entry.action),
      labelOf(resourceTypeLabels, entry.resourceType),
      entry.resourceId || '',
      entry.requestId || '',
      labelOf(outcomeLabels, entry.outcome),
      detail(entry.detail),
      entry.createdAt,
    ])
    const csv = [
      ['来源', '管理员编号', '管理员名称', '管理员ID', '动作', '对象类型', '对象ID', '请求ID', '结果', '详情', '时间'],
      ...rows,
    ].map((row) => row.map(csvCell).join(',')).join('\r\n')
    const blob = new Blob([`\ufeff${csv}`], { type: 'text/csv;charset=UTF-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `审计日志-${new Date().toISOString().slice(0, 10)}.csv`
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success(`已导出 ${exported.length} 条审计记录`)
  } catch {
    ElMessage.error('审计记录导出失败，请稍后重试')
  } finally {
    exporting.value = false
  }
}

onMounted(() => load(0))
</script>

<template>
  <section class="admin-page" aria-labelledby="admin-audit-title">
    <header class="admin-page__header">
      <div><h2 id="admin-audit-title">审计日志</h2><p>按管理员、动作、业务对象、结果与时间范围追踪管理操作</p></div>
      <div class="admin-actions"><el-button :loading="exporting" @click="exportAudit"><Download :size="16" />导出</el-button><el-button :loading="loading" @click="load(page)"><RefreshCw :size="16" />刷新</el-button></div>
    </header>
    <TopErrorMessage :message="errorMessage" />

    <div class="admin-panel audit-filter-panel">
      <div class="audit-filters">
        <label class="audit-field"><span>管理员 ID</span><el-input v-model="actorUserId" data-test="audit-actor" clearable /></label>
        <label class="audit-field"><span>动作</span><el-select v-model="action" data-test="audit-action" clearable filterable placeholder="全部动作"><el-option v-for="option in auditActionOptions" :key="option.value" :label="option.label" :value="option.value" /></el-select></label>
        <label class="audit-field"><span>对象类型</span><el-select v-model="resourceType" data-test="audit-resource-type" clearable filterable placeholder="全部对象类型"><el-option v-for="option in auditResourceTypeOptions" :key="option.value" :label="option.label" :value="option.value" /></el-select></label>
        <label class="audit-field"><span>对象 ID</span><el-input v-model="resourceId" data-test="audit-resource-id" clearable /></label>
        <label class="audit-field"><span>结果</span><el-select v-model="outcome" data-test="audit-outcome" clearable placeholder="全部结果"><el-option label="成功" value="SUCCESS" /><el-option label="失败" value="FAILURE" /></el-select></label>
        <label class="audit-field"><span>排序</span><el-select v-model="sort" data-test="audit-sort" placeholder="选择排序字段"><el-option label="创建时间" value="createdAt" /><el-option label="动作" value="action" /><el-option label="对象类型" value="resourceType" /><el-option label="结果" value="outcome" /></el-select></label>
        <label class="audit-field"><span>方向</span><el-select v-model="direction" data-test="audit-direction" placeholder="选择排序方向"><el-option label="降序" value="desc" /><el-option label="升序" value="asc" /></el-select></label>
        <label class="audit-field audit-time" data-test="audit-time"><span>时间范围（结束时间不包含）</span><el-date-picker v-model="timeRange" type="datetimerange" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" /></label>
        <div class="admin-actions audit-filter-actions"><el-button data-test="audit-filter" type="primary" :loading="loading" @click="applyFilters"><Search :size="16" />查询</el-button><el-button @click="clearFilters">清空</el-button></div>
      </div>
    </div>

    <div class="admin-panel">
      <div class="admin-panel__header"><div><h3>操作记录</h3><p>敏感字段由服务端统一脱敏后返回</p></div></div>
      <el-skeleton v-if="loading" :rows="6" animated />
      <div v-else class="table-scroll"><table class="admin-table"><thead><tr><th>管理员</th><th>动作</th><th>业务对象</th><th>请求 ID</th><th>结果</th><th>详情</th><th>时间</th></tr></thead><tbody>
          <tr v-for="entry in entries" :key="entryKey(entry)" :data-audit-key="entryKey(entry)">
            <td><div class="audit-actor"><strong>{{ actorName(entry) }}</strong><span>{{ entry.actorUserNo || (entry.actorUserId ? '编号未设置' : 'SYSTEM') }}</span><el-button v-if="entry.actorUserId" text circle title="复制管理员 ID" aria-label="复制管理员 ID" @click="copyText(entry.actorUserId, '管理员 ID')"><Copy :size="15" /></el-button></div></td>
            <td>{{ labelOf(actionLabels, entry.action) }}</td>
            <td><span>{{ labelOf(resourceTypeLabels, entry.resourceType) }}</span><div v-if="entry.resourceId" class="audit-copy-value"><code :title="entry.resourceId">{{ entry.resourceId }}</code><el-button text circle title="复制对象 ID" aria-label="复制对象 ID" @click="copyText(entry.resourceId, '对象 ID')"><Copy :size="15" /></el-button></div><span v-else>-</span></td>
            <td><div v-if="entry.requestId" class="audit-copy-value"><code :title="entry.requestId">{{ entry.requestId }}</code><el-button text circle title="复制请求 ID" aria-label="复制请求 ID" @click="copyText(entry.requestId, '请求 ID')"><Copy :size="15" /></el-button></div><span v-else>-</span></td>
            <td><el-tag size="small" :type="outcomeType(entry.outcome)">{{ labelOf(outcomeLabels, entry.outcome) }}</el-tag></td>
            <td><div class="audit-copy-value"><code class="audit-detail" :title="detail(entry.detail)">{{ detail(entry.detail) }}</code><el-button text circle title="复制详情" aria-label="复制详情" @click="copyText(detail(entry.detail), '详情')"><Copy :size="15" /></el-button></div></td>
            <td>{{ formatDateTime(entry.createdAt) }}</td>
          </tr>
        <tr v-if="!entries.length"><td colspan="7" class="admin-empty">暂无审计记录</td></tr>
      </tbody></table></div>
      <div class="admin-pagination"><span>共 {{ total }} 条</span><el-pagination data-test="audit-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="page + 1" :page-size="size" :page-sizes="pageSizeOptions" :total="total" @size-change="changePageSize" @current-change="(value: number) => load(value - 1)" /></div>
    </div>
  </section>
</template>

<style scoped>
.audit-filter-panel { margin-bottom: 14px; }
.audit-filters { display: grid; gap: 12px; padding: 14px; grid-template-columns: repeat(3, minmax(0, 1fr)); }
.audit-field { display: flex; min-width: 0; flex-direction: column; gap: 5px; color: var(--app-fg); font-size: 12px; font-weight: 600; }
.audit-field .el-select, .audit-field :deep(.el-date-editor) { width: 100%; }
.audit-field :deep(.el-input__wrapper), .audit-field :deep(.el-select__wrapper), .audit-field :deep(.el-date-editor), .audit-field :deep(.el-range-editor) { box-sizing: border-box; height: 40px; min-height: 40px; }
.audit-time { grid-column: span 2; }
.audit-filter-actions { grid-column: 1 / -1; justify-content: flex-end; }
.audit-filter-actions .el-button { width: 88px; height: 40px; min-height: 40px; margin-left: 0; }
.audit-actor { display: grid; grid-template-columns: minmax(0, 1fr) 28px; align-items: center; min-width: 140px; }
.audit-actor strong, .audit-actor span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 两行文字都必须留在第 1 列：只给按钮显式定位时，自动排布会把 span 放进 28px 的按钮列，
   "SYSTEM" 这类编号因此只剩 28px 并被裁掉（实测内容 43px / 可视 28px）。 */
.audit-actor strong { grid-column: 1; grid-row: 1; }
.audit-actor span { grid-column: 1; grid-row: 2; color: var(--app-muted-strong); font-size: 12px; }
/* 按钮列是 28px，而 el-button 默认带 8px 内边距、实际宽 32px，会撑破网格（实测容器 144 > 可视 140）；
   显式压到 28px，与列宽及 .audit-copy-value 的复制按钮保持一致。 */
.audit-actor .el-button { grid-column: 2; grid-row: 1 / span 2; width: 28px; height: 28px; padding: 0; }
.audit-copy-value { display: flex; max-width: 260px; align-items: center; gap: 4px; }
.audit-copy-value code { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.audit-copy-value .el-button { flex: 0 0 28px; }
.audit-detail { display: block; max-width: 280px; }
@media (max-width: 1000px) { .audit-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .audit-filters { grid-template-columns: minmax(0, 1fr); } .audit-field :deep(.el-input__wrapper), .audit-field :deep(.el-select__wrapper), .audit-field :deep(.el-date-editor), .audit-field :deep(.el-range-editor) { height: 44px; min-height: 44px; } .audit-time, .audit-filter-actions { grid-column: auto; } .audit-filter-actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); } .audit-filter-actions .el-button { width: 100%; height: 44px; min-height: 44px; } }
</style>
