<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Check, Search, X } from 'lucide-vue-next'

import {
  approveRefund,
  getAdminRefund,
  listRefunds,
  rejectRefund,
  type AdminListParams,
  type AdminRefund,
  type AdminRefundDetail,
  type RefundStatus,
} from '../../api/admin'
import { formatDateTime } from '../../utils/dateTime'
import { userNameLabel, userNoLabel } from '../../utils/adminDisplay'

const props = withDefaults(defineProps<{ initialQuery?: string }>(), { initialQuery: '' })

const refunds = ref<AdminRefund[]>([])
const total = ref(0)
const loading = ref(true)
const detailLoading = ref(false)
const decisionBusy = ref(false)
const detail = ref<AdminRefundDetail | null>(null)
const reviewNote = ref('')
const errorMessage = ref('')
const pageSizeOptions = [5, 10, 20]
let loadSequence = 0
let detailSequence = 0

const params = reactive<AdminListParams>({
  page: 0, size: 20, query: props.initialQuery.trim(), status: '', sort: 'createdAt', direction: 'desc',
})

async function load(targetPage = params.page) {
  const sequence = ++loadSequence
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listRefunds({ ...params, page: targetPage })
    if (sequence !== loadSequence) return
    refunds.value = result.items
    total.value = result.total
    params.page = result.page
  } catch {
    if (sequence === loadSequence) errorMessage.value = '退款记录加载失败，请稍后重试。'
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

function changePageSize(value: number) {
  params.size = value
  return load(0)
}

async function refreshDetail(refundNo: string) {
  const sequence = ++detailSequence
  detailLoading.value = true
  errorMessage.value = ''
  detail.value = null
  try {
    const result = await getAdminRefund(refundNo)
    if (sequence === detailSequence) detail.value = result
  } catch {
    if (sequence === detailSequence) errorMessage.value = '退款审核详情加载失败，请稍后重试。'
  } finally {
    if (sequence === detailSequence) detailLoading.value = false
  }
}

async function review(row: AdminRefund) {
  if (decisionBusy.value) return
  reviewNote.value = ''
  await refreshDetail(row.refundNo)
}

async function approve() {
  if (!detail.value || decisionBusy.value || detail.value.status !== 'PENDING_APPROVAL') return
  decisionBusy.value = true
  const refundNo = detail.value.refundNo
  try {
    await ElMessageBox.confirm(
      `确认批准退款 ${refundNo}？金额与费用将以服务器保存的计算快照为准。`,
      '批准退款',
      { type: 'warning', confirmButtonText: '确认批准', cancelButtonText: '取消' },
    )
    await approveRefund(refundNo)
    await refreshDetail(refundNo)
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') errorMessage.value = '退款批准失败，请刷新详情后重试。'
  } finally {
    decisionBusy.value = false
  }
}

async function reject() {
  const note = reviewNote.value.trim()
  if (!detail.value || !note || note.length > 500 || decisionBusy.value || detail.value.status !== 'PENDING_APPROVAL') return
  decisionBusy.value = true
  const refundNo = detail.value.refundNo
  try {
    await ElMessageBox.confirm(
      `确认拒绝退款 ${refundNo}？审核说明将写入不可变审计记录。`,
      '拒绝退款',
      { type: 'warning', confirmButtonText: '确认拒绝', cancelButtonText: '取消' },
    )
    await rejectRefund(refundNo, note)
    await refreshDetail(refundNo)
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') errorMessage.value = '退款拒绝失败，请刷新详情后重试。'
  } finally {
    decisionBusy.value = false
  }
}

function amount(value: number) {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value / 100)
}

function tokens(value: number) {
  return new Intl.NumberFormat('zh-CN').format(value)
}

function feeRate(value: number) {
  return `${value / 100}%`
}

const refundStatusLabels: Record<RefundStatus, string> = {
  PREPARING: '准备中',
  PENDING_APPROVAL: '待审核',
  REJECTED: '已拒绝',
  CANCELED: '已取消',
  SUBMITTING: '提交中',
  PROCESSING: '处理中',
  REVOKING: '撤销套餐中',
  COMPLETED: '已完成',
  FAILED: '失败',
}

function statusLabel(status: RefundStatus) {
  return refundStatusLabels[status]
}

onMounted(load)
watch(() => props.initialQuery, (query) => {
  const normalized = query.trim()
  if (normalized === params.query) return
  params.query = normalized
  void load(0)
})
</script>

<template>
  <section class="refund-review" aria-labelledby="refund-review-title">
    <header class="workflow-header">
      <div><h3 id="refund-review-title">退款审核</h3><p>审核金额只读取服务器保存的计算快照</p></div>
    </header>
    <TopErrorMessage :message="errorMessage" />

    <div class="admin-toolbar workflow-toolbar">
      <el-input v-model="params.query" data-test="refunds-query" clearable placeholder="搜索退款、订单或用户" aria-label="搜索退款" @keyup.enter="load(0)" />
      <el-select v-model="params.status" data-test="refunds-status" clearable placeholder="全部状态" aria-label="退款状态">
        <el-option label="准备中" value="PREPARING" /><el-option label="待审核" value="PENDING_APPROVAL" />
        <el-option label="提交中" value="SUBMITTING" /><el-option label="处理中" value="PROCESSING" />
        <el-option label="撤销套餐中" value="REVOKING" /><el-option label="已完成" value="COMPLETED" />
        <el-option label="已拒绝" value="REJECTED" /><el-option label="已取消" value="CANCELED" />
        <el-option label="失败" value="FAILED" />
      </el-select>
      <el-select v-model="params.sort" data-test="refunds-sort" aria-label="退款排序字段">
        <el-option label="创建时间" value="createdAt" /><el-option label="状态" value="status" />
        <el-option label="金额" value="amount" /><el-option label="退款号" value="refundNo" />
      </el-select>
      <el-select v-model="params.direction" data-test="refunds-direction" aria-label="退款排序方向">
        <el-option label="降序" value="desc" /><el-option label="升序" value="asc" />
      </el-select>
      <el-button data-test="refunds-filter" type="primary" :loading="loading" @click="load(0)"><Search :size="16" />查询</el-button>
    </div>

    <el-skeleton v-if="loading" :rows="4" animated />
    <div v-else class="table-scroll">
      <table class="admin-table"><thead><tr><th>退款号</th><th>订单号</th><th>用户编号</th><th>用户名</th><th>金额</th><th>原因</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead><tbody>
        <tr v-for="row in refunds" :key="row.id">
          <td><code>{{ row.refundNo }}</code></td><td><code>{{ row.orderNo }}</code></td><td><code>{{ userNoLabel(row.userNo) }}</code></td><td>{{ userNameLabel(row.userDisplayName) }}</td>
              <td>{{ amount(row.amount) }}</td><td>{{ row.reason }}</td><td><el-tag size="small">{{ statusLabel(row.status) }}</el-tag></td><td>{{ formatDateTime(row.createdAt) }}</td>
          <td><el-button size="small" :data-test="`review-refund-${row.id}`" :loading="detailLoading && detail?.refundNo === row.refundNo" :disabled="decisionBusy" @click="review(row)">审核</el-button></td>
        </tr>
        <tr v-if="!refunds.length"><td colspan="9" class="admin-empty">暂无退款记录</td></tr>
      </tbody></table>
    </div>
    <div class="admin-pagination"><span>共 {{ total }} 条</span><el-pagination data-test="refunds-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="params.page + 1" :page-size="params.size" :page-sizes="pageSizeOptions" :total="total" @size-change="changePageSize" @current-change="(value: number) => load(value - 1)" /></div>

    <aside v-if="detail" data-test="refund-review-detail" class="workflow-drawer" aria-label="退款审核详情">
      <div class="workflow-drawer__header"><div><h4>{{ detail.refundNo }}</h4><p>{{ detail.orderNo }} · {{ detail.reason }}</p></div><el-tag>{{ statusLabel(detail.status) }}</el-tag></div>
      <el-skeleton v-if="detailLoading" :rows="5" animated />
      <template v-else>
        <dl v-if="detail.calculation" class="snapshot-grid">
          <div><dt>实付金额</dt><dd>{{ amount(detail.calculation.paidAmount) }}</dd></div>
          <div><dt>总积分</dt><dd>{{ tokens(detail.calculation.totalTokens) }}</dd></div>
          <div><dt>已用积分</dt><dd>{{ tokens(detail.calculation.usedTokens) }}</dd></div>
          <div><dt>剩余积分</dt><dd>{{ tokens(detail.calculation.remainingTokens) }}</dd></div>
          <div><dt>退款政策</dt><dd>政策版本 {{ detail.calculation.policyVersion }}</dd></div>
          <div><dt>手续费率</dt><dd>{{ feeRate(detail.calculation.feeBps) }}</dd></div>
          <div><dt>最低手续费</dt><dd>{{ amount(detail.calculation.minimumFee) }}</dd></div>
          <div><dt>最大使用量</dt><dd>{{ feeRate(detail.calculation.maximumUsageBps) }}</dd></div>
          <div><dt>退款窗口</dt><dd>{{ detail.calculation.refundWindowDays }} 天</dd></div>
          <div><dt>手续费</dt><dd>{{ amount(detail.calculation.fee) }}</dd></div>
          <div><dt>退款金额</dt><dd class="snapshot-primary">{{ amount(detail.calculation.refundAmount) }}</dd></div>
          <div><dt>原套餐状态</dt><dd>{{ detail.calculation.originalPackageStatus }}</dd></div>
              <div><dt>套餐到期时间</dt><dd>{{ detail.calculation.packageExpiresAt ? formatDateTime(detail.calculation.packageExpiresAt) : '永久有效' }}</dd></div>
              <div><dt>支付时间</dt><dd>{{ formatDateTime(detail.calculation.paidAt) }}</dd></div>
              <div><dt>申请时间</dt><dd>{{ formatDateTime(detail.calculation.requestedAt) }}</dd></div>
              <div><dt>计算时间</dt><dd>{{ formatDateTime(detail.calculation.calculatedAt) }}</dd></div>
          <div><dt>网关快照时间</dt><dd>{{ formatDateTime(detail.calculation.gatewaySnapshotAt) }}</dd></div>
              <div><dt>审核时间</dt><dd>{{ formatDateTime(detail.reviewedAt) }}</dd></div>
        </dl>
        <div v-else class="snapshot-empty">计算快照尚未生成</div>
        <el-input v-model="reviewNote" data-test="refund-review-note" type="textarea" :rows="3" maxlength="500" show-word-limit :disabled="decisionBusy || detail.status !== 'PENDING_APPROVAL'" placeholder="拒绝时必须填写审核说明" aria-label="退款审核说明" />
        <div class="workflow-actions">
          <el-button data-test="reject-refund" type="danger" plain :loading="decisionBusy" :disabled="decisionBusy || detail.status !== 'PENDING_APPROVAL' || !reviewNote.trim() || reviewNote.trim().length > 500" @click="reject"><X :size="16" />拒绝</el-button>
          <el-button data-test="approve-refund" type="primary" :loading="decisionBusy" :disabled="decisionBusy || detail.status !== 'PENDING_APPROVAL'" @click="approve"><Check :size="16" />批准</el-button>
        </div>
      </template>
    </aside>
  </section>
</template>

<style scoped>
.refund-review { min-width: 0; }
.workflow-header, .workflow-drawer__header, .workflow-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.workflow-header { padding: 12px 14px; border-bottom: 1px solid var(--app-border); }
.workflow-header h3, .workflow-drawer h4 { margin: 0; font-size: 16px; letter-spacing: 0; }
.workflow-header p, .workflow-drawer p { margin: 3px 0 0; color: var(--app-muted); font-size: 12px; }
.workflow-toolbar { padding: 12px 14px 0; }
.workflow-drawer { padding: 16px; border-top: 1px solid var(--app-border); background: var(--app-bg); }
.snapshot-grid { display: grid; gap: 1px; margin: 14px 0; overflow: hidden; border: 1px solid var(--app-border); border-radius: 6px; background: var(--app-border); grid-template-columns: repeat(2, minmax(0, 1fr)); }
.snapshot-grid > div { min-width: 0; padding: 10px 12px; background: var(--app-surface); }
.snapshot-grid dt { color: var(--app-muted); font-size: 12px; }
.snapshot-grid dd { margin: 3px 0 0; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
.snapshot-primary { font-weight: 700; }
.snapshot-empty { margin: 14px 0; padding: 24px 12px; border: 1px dashed var(--app-border); border-radius: 6px; color: var(--app-muted); text-align: center; }
.workflow-actions { justify-content: flex-end; margin-top: 12px; }
@media (max-width: 600px) {
  .snapshot-grid { grid-template-columns: minmax(0, 1fr); }
  .workflow-actions > * { flex: 1; }
}
</style>
