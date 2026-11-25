<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Search, ShieldCheck } from 'lucide-vue-next'

import {
  listPaymentAnomalies,
  updatePaymentAnomaly,
  type PaymentAnomaly,
  type PaymentAnomalyListParams,
} from '../../api/admin'
import { useAuthStore } from '../../stores/auth'
import { formatDateTime } from '../../utils/dateTime'

const auth = useAuthStore()
const anomalies = ref<PaymentAnomaly[]>([])
const total = ref(0)
const loading = ref(true)
const updatingId = ref('')
const errorMessage = ref('')
const pageSizeOptions = [5, 10, 20]
let loadSequence = 0

const filters = reactive<PaymentAnomalyListParams>({
  page: 0,
  size: 20,
  anomalyType: '',
  status: '',
  provider: '',
  from: '',
  to: '',
  orderId: '',
  sort: 'createdAt',
  direction: 'desc',
})

async function load(targetPage = filters.page) {
  const sequence = ++loadSequence
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listPaymentAnomalies({ ...filters, page: targetPage })
    if (sequence !== loadSequence) return
    anomalies.value = result.items
    total.value = result.total
    filters.page = result.page
  } catch {
    if (sequence === loadSequence) errorMessage.value = '支付异常加载失败，请稍后重试。'
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

async function applyAuthoritativeUpdate() {
  await load()
}

function changePageSize(value: number) {
  filters.size = value
  return load(0)
}

async function investigate(row: PaymentAnomaly) {
  if (row.status !== 'OPEN' || updatingId.value) return
  updatingId.value = row.id
  errorMessage.value = ''
  try {
    await updatePaymentAnomaly(row.id, {
      status: 'INVESTIGATING',
      assignee: auth.user?.id ?? null,
      resolutionNote: null,
    }, row.lockVersion)
    await applyAuthoritativeUpdate()
  } catch {
    errorMessage.value = '异常认领失败，记录可能已被其他管理员更新。'
  } finally {
    updatingId.value = ''
  }
}

async function resolve(row: PaymentAnomaly) {
  if (row.status !== 'INVESTIGATING' || updatingId.value) return
  let resolutionNote = ''
  try {
    const result = await ElMessageBox.prompt(
      '填写核对结论后将异常标记为已解决。',
      '解决支付异常',
      { confirmButtonText: '确认解决', cancelButtonText: '取消', inputPattern: /\S+/, inputErrorMessage: '必须填写核对结论' },
    )
    resolutionNote = result.value.trim()
  } catch {
    return
  }
  if (!resolutionNote) return
  updatingId.value = row.id
  errorMessage.value = ''
  try {
    await updatePaymentAnomaly(row.id, {
      status: 'RESOLVED',
      assignee: auth.user?.id ?? row.assignee,
      resolutionNote,
    }, row.lockVersion)
    await applyAuthoritativeUpdate()
  } catch {
    errorMessage.value = '异常解决失败，记录可能已被其他管理员更新。'
  } finally {
    updatingId.value = ''
  }
}

onMounted(load)
</script>

<template>
  <section class="anomaly-panel" aria-labelledby="payment-anomaly-title">
    <header class="workflow-header"><div><h3 id="payment-anomaly-title">异常队列</h3><p>按责任人认领并通过乐观锁推进处理状态</p></div></header>
    <TopErrorMessage :message="errorMessage" />

    <div class="admin-toolbar workflow-toolbar">
      <el-select v-model="filters.anomalyType" data-test="anomaly-type" clearable filterable allow-create placeholder="异常类型" aria-label="异常类型">
        <el-option label="支付金额不一致" value="PAYMENT_AMOUNT_MISMATCH" /><el-option label="外部交易冲突" value="EXTERNAL_TRADE_CONFLICT" />
        <el-option label="退款金额不一致" value="REFUND_AMOUNT_MISMATCH" /><el-option label="对账差异" value="RECONCILIATION_DIFFERENCE" />
      </el-select>
      <el-select v-model="filters.status" data-test="anomaly-status" clearable placeholder="全部状态" aria-label="异常状态">
        <el-option label="待处理" value="OPEN" /><el-option label="处理中" value="INVESTIGATING" /><el-option label="已解决" value="RESOLVED" />
      </el-select>
      <el-select v-model="filters.provider" data-test="anomaly-provider" clearable placeholder="全部渠道" aria-label="支付渠道">
        <el-option label="支付宝" value="ALIPAY" /><el-option label="微信支付" value="WECHAT_PAY" /><el-option label="本地模拟" value="MOCK" />
      </el-select>
      <el-input v-model="filters.from" data-test="anomaly-from" clearable placeholder="开始时间（ISO 8601）" aria-label="异常开始时间" />
      <el-input v-model="filters.to" data-test="anomaly-to" clearable placeholder="结束时间（ISO 8601）" aria-label="异常结束时间" />
      <el-input v-model="filters.orderId" data-test="anomaly-order" clearable placeholder="订单 ID" aria-label="异常订单 ID" />
      <el-button data-test="anomaly-filter" type="primary" :loading="loading" @click="load(0)"><Search :size="16" />查询</el-button>
    </div>

    <el-skeleton v-if="loading" :rows="4" animated />
    <div v-else class="table-scroll">
      <table class="admin-table"><thead><tr><th>异常类型</th><th>渠道</th><th>订单</th><th>支付 / 退款</th><th>状态</th><th>负责人</th><th>解决说明</th><th>更新时间</th><th>操作</th></tr></thead><tbody>
        <tr v-for="row in anomalies" :key="row.id">
          <td><strong>{{ row.anomalyType }}</strong><br><code>{{ row.fingerprint }}</code></td><td>{{ row.provider || '-' }}</td><td><code>{{ row.orderId || '-' }}</code></td>
          <td><code>{{ row.paymentTransactionId || row.refundId || '-' }}</code></td><td><el-tag size="small">{{ row.status }}</el-tag></td>
            <td><code>{{ row.assignee || '-' }}</code></td><td>{{ row.resolutionNote || '-' }}</td><td>{{ formatDateTime(row.updatedAt) }}</td>
          <td>
            <el-button v-if="row.status === 'OPEN'" size="small" :data-test="`investigate-anomaly-${row.id}`" :loading="updatingId === row.id" :disabled="updatingId !== ''" @click="investigate(row)"><ShieldCheck :size="15" />认领</el-button>
            <el-button v-else-if="row.status === 'INVESTIGATING'" size="small" type="primary" :data-test="`resolve-anomaly-${row.id}`" :loading="updatingId === row.id" :disabled="updatingId !== ''" @click="resolve(row)"><ShieldCheck :size="15" />解决</el-button>
            <span v-else>-</span>
          </td>
        </tr>
        <tr v-if="!anomalies.length"><td colspan="9" class="admin-empty">暂无支付异常</td></tr>
      </tbody></table>
    </div>
    <div class="admin-pagination"><span>共 {{ total }} 条</span><el-pagination data-test="anomalies-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="filters.page + 1" :page-size="filters.size" :page-sizes="pageSizeOptions" :total="total" @size-change="changePageSize" @current-change="(value: number) => load(value - 1)" /></div>
  </section>
</template>

<style scoped>
.anomaly-panel { min-width: 0; }
.workflow-header { padding: 12px 14px; border-bottom: 1px solid var(--app-border); }
.workflow-header h3 { margin: 0; font-size: 16px; letter-spacing: 0; }
.workflow-header p { margin: 3px 0 0; color: var(--app-muted); font-size: 12px; }
.workflow-toolbar { padding: 12px 14px 0; }
</style>
