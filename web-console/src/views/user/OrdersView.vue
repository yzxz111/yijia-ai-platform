<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { CreditCard, Eye, ReceiptText, RefreshCw, RotateCcw, XCircle, Download, Search, Wallet, ShoppingCart, Package, CalendarClock } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { paymentProductForDevice } from '../../utils/paymentProduct'

import {
  closeOrder,
  createPaymentAttempt,
  getOrderDetail,
  getProfile,
  isSecondDimension,
  listOrders,
  quotaUnitLabel,
  type OrderDetail,
  type OrderView,
  type PaymentAction,
  type PaymentProduct,
  type PaymentProvider,
} from '../../api/platform'
import { quotaSummary, type QuotaSummary } from '../../api/gateway'
import { listSubscriptions, type SubscriptionSummary } from '../../api/subscriptions'
import PaymentActionPane from '../../components/payment/PaymentActionPane.vue'
import RefundDialog from '../../components/refund/RefundDialog.vue'
import SmsVerificationDialog from '../../components/sms/SmsVerificationDialog.vue'
import { queryPageIndex, queryText } from '../../router/query'
import { formatDateTime } from '../../utils/dateTime'
import { userPlanName } from '../../utils/planCopy'

const route = useRoute()
const router = useRouter()
const orders = ref<OrderView[]>([])
const total = ref(0)
const statusFilter = ref('')
const searchQuery = ref('')
const page = ref(0)
const pageSize = ref(20)
const pageSizeOptions = [5, 10, 20]
const loading = ref(true)
const errorMessage = ref('')
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const selectedDetail = ref<OrderDetail | null>(null)
const selectedOrderNo = ref<string | null>(null)
const retryAction = ref<PaymentAction | null>(null)
const refundVisible = ref(false)
const detailActionLoading = ref(false)
const paymentVerificationOpen = ref(false)
const paymentResumeWarning = ref('')
const quota = ref<QuotaSummary | null>(null)
/** 当前活跃订阅（若有）。决定「下次续费」显示的是扣款时刻还是到期日。 */
const activeSubscription = ref<SubscriptionSummary | null>(null)
let pendingRetry: { orderNo: string; provider: PaymentProvider; product: PaymentProduct } | null = null
const orderStatuses = new Set(['PENDING', 'PAID', 'CLOSED', 'REFUNDED', 'PARTIALLY_REFUNDED'])
const orderStatusOptions = ['', 'PAID', 'PENDING', 'REFUNDED', 'CLOSED']
const orderStatusTabs = [
  { value: '', label: '全部' },
  { value: 'PAID', label: '已支付' },
  { value: 'PENDING', label: '待支付' },
  { value: 'REFUNDED', label: '已退款' },
  { value: 'CLOSED', label: '已关闭' },
]
const orderStatusLabels: Record<string, string> = {
  PENDING: '待支付', PAID: '已支付', CLOSED: '已关闭', REFUNDED: '已退款', PARTIALLY_REFUNDED: '部分退款',
}
const paymentStatusLabels: Record<string, string> = {
  CREATED: '已创建', PENDING: '支付中', SUCCEEDED: '支付成功', FAILED: '支付失败', CLOSED: '已关闭',
}
const grantStatusLabels: Record<string, string> = {
  QUEUED: '排队中', ACTIVE: '已生效', FAILED: '发放失败', REVOKED: '已撤销',
}
const refundStatusLabels: Record<string, string> = {
  PREPARING: '准备中', PENDING_APPROVAL: '待审核', REJECTED: '已拒绝', CANCELED: '已取消',
  SUBMITTING: '提交中', PROCESSING: '退款处理中', REVOKING: '套餐撤销中', COMPLETED: '已完成', FAILED: '退款失败',
}
const providerLabels: Record<string, string> = {
  UNSELECTED: '未选择', MOCK: '模拟支付', WECHAT_PAY: '微信支付', ALIPAY: '支付宝',
}
function orderStatusType(status: string): 'success' | 'danger' | 'info' | 'warning' {
  if (status === 'PAID') return 'success'
  if (status === 'PENDING') return 'warning'
  if (status === 'CLOSED') return 'info'
  if (status === 'REFUNDED' || status === 'PARTIALLY_REFUNDED') return 'danger'
  return 'info'
}
function orderStatusClass(status: string) {
  return `status status--${orderStatusType(status)}`
}
function settlementLabel(method?: string, provider?: string) {
  if (method === 'BALANCE') return '余额支付'
  if (method === 'EXTERNAL') return provider ? providerLabels[provider] || '未选择' : '未选择'
  return '未选择'
}
let loadSequence = 0
let detailSequence = 0
let detailActionSequence = 0

const number = new Intl.NumberFormat('zh-CN')

function amount(order: OrderView) {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency', currency: order.currency, minimumFractionDigits: 2,
  }).format(order.amount / 100)
}

function compactOrderNo(orderNo: string) {
  if (orderNo.length <= 20) return orderNo
  return `${orderNo.slice(0, 10)}…${orderNo.slice(-6)}`
}

function queryPageSize(value: unknown) {
  const candidate = Number(queryText(value))
  return pageSizeOptions.includes(candidate) ? candidate : 20
}

function money(value: number, currency: string) {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency', currency, minimumFractionDigits: 2,
  }).format(value / 100)
}

function snapshotText(key: keyof OrderDetail['packageSnapshot'], fallback = '—') {
  const value = selectedDetail.value?.packageSnapshot[key]
  return value === null || value === undefined || value === '' ? fallback : String(value)
}

// 订单快照的额度带量纲：视频订单的数字是秒数，
// 而面向客户的控制台任何地方都不展示该数字（见 PlansView），
// 因此收据只说明计费方式。一份泄露秒数的收据
// 会让其他页面上的隐藏前功尽弃。
const orderQuotaIsSeconds = computed(() =>
  isSecondDimension(selectedDetail.value?.packageSnapshot.usageDimension))
const orderQuotaLabel = computed(() => (orderQuotaIsSeconds.value
  ? '计费方式'
  : `${quotaUnitLabel(selectedDetail.value?.packageSnapshot.usageDimension)}额度`))
const orderQuotaValue = computed(() => (orderQuotaIsSeconds.value
  ? '按秒计费'
  : snapshotText('totalTokenQuota')))

const filteredOrders = computed(() => {
  if (!searchQuery.value) return orders.value
  const q = searchQuery.value.toLowerCase()
  return orders.value.filter(o =>
    o.orderNo.toLowerCase().includes(q) ||
    (o.packageName || '').toLowerCase().includes(q)
  )
})

const totalSpent = computed(() => {
  return orders.value
    .filter(o => o.status === 'PAID')
    .reduce((sum, o) => sum + o.amount, 0)
})

const totalSpentLabel = computed(() => {
  if (!orders.value.length) return '¥0.00'
  return money(totalSpent.value, orders.value[0].currency)
})

const currentPlanLabel = computed(() => {
  const code = quota.value?.activePackage?.packageCode
  const name = quota.value?.activePackage?.packageName
  if (!code) return '—'
  return userPlanName(code, name || '')
})

/**
 * 「下次续费」时刻。
 *
 * 对**订阅用户**必须是真实的自动扣款时刻，而不是套餐到期日——两者是不同的日子
 * （扣款发生在到期前 24 小时），把到期日显示成「下次续费」会让用户在错误的日子等扣款。
 * 非订阅用户没有自动扣款，此时展示到期日并保留原有标签语义。
 */
const nextRenewalLabel = computed(() => {
  const subscription = activeSubscription.value
  if (subscription?.nextChargeAt) return formatDateTime(subscription.nextChargeAt)
  const ts = quota.value?.activePackage?.expiresAt
  if (!ts) return '—'
  return formatDateTime(ts)
})

async function openDetail(orderNo: string, preserveAction = false) {
  const sequence = ++detailSequence
  if (!preserveAction) {
    detailActionSequence += 1
    detailActionLoading.value = false
  }
  selectedOrderNo.value = orderNo
  detailVisible.value = true
  detailLoading.value = true
  detailError.value = ''
  selectedDetail.value = null
  retryAction.value = null
  paymentResumeWarning.value = ''
  try {
    const result = await getOrderDetail(orderNo)
    if (sequence === detailSequence) {
      selectedDetail.value = result
      restorePaymentAction(result)
    }
  } catch {
    if (sequence === detailSequence) detailError.value = '订单详情加载失败，请稍后重试。'
  } finally {
    if (sequence === detailSequence) detailLoading.value = false
  }
}

async function refreshDetail() {
  const orderNo = selectedOrderNo.value
  if (orderNo) await openDetail(orderNo, true)
}

function isCurrentDetailAction(sequence: number, orderNo: string) {
  return sequence === detailActionSequence
    && detailVisible.value
    && selectedOrderNo.value === orderNo
}

function clearDetail() {
  detailSequence += 1
  detailActionSequence += 1
  detailActionLoading.value = false
  selectedOrderNo.value = null
  selectedDetail.value = null
  retryAction.value = null
  paymentResumeWarning.value = ''
  pendingRetry = null
  paymentVerificationOpen.value = false
}

function snapshotPackageName() {
  const snapshot = selectedDetail.value?.packageSnapshot
  return snapshot ? userPlanName(snapshot.packageCode, snapshot.packageName) : '—'
}

function fallbackProduct(provider: string): PaymentProduct {
  return paymentProductForDevice(provider)
}

async function retryPayment() {
  const current = selectedDetail.value
  const attempt = current?.paymentAttempts.at(-1)
  if (!current || !attempt) return
  const orderNo = current.order.orderNo
  const request = {
    orderNo,
    provider: attempt.provider,
    product: attempt.product || fallbackProduct(attempt.provider),
  }
  const sequence = ++detailActionSequence
  detailActionLoading.value = true
  retryAction.value = null
  try {
    const profile = await getProfile()
    if (!isCurrentDetailAction(sequence, orderNo)) return
    if (profile.phoneVerified) {
      pendingRetry = request
      paymentVerificationOpen.value = true
    } else {
      await executeRetry(request)
    }
  } catch {
    if (!isCurrentDetailAction(sequence, orderNo)) return
    ElMessage.error('支付重试失败，请稍后再试。')
  } finally {
    if (isCurrentDetailAction(sequence, orderNo)) detailActionLoading.value = false
  }
}

async function closeSelectedOrder() {
  if (!selectedDetail.value) return
  const sequence = ++detailActionSequence
  const orderNo = selectedDetail.value.order.orderNo
  detailActionLoading.value = true
  try {
    await closeOrder(orderNo)
    if (!isCurrentDetailAction(sequence, orderNo)) return
    retryAction.value = null
    await Promise.all([refreshDetail(), load()])
  } catch {
    if (!isCurrentDetailAction(sequence, orderNo)) return
    ElMessage.error('订单关闭失败，请稍后再试。')
  } finally {
    if (isCurrentDetailAction(sequence, orderNo)) detailActionLoading.value = false
  }
}

function openRefund() {
  refundVisible.value = true
}

async function openRefundForOrder(orderNo: string) {
  await openDetail(orderNo)
  if (selectedDetail.value?.order.orderNo === orderNo
      && selectedDetail.value.order.status === 'PAID'
      && !selectedDetail.value.refund) {
    refundVisible.value = true
  }
}

async function executeRetry(
  request: { orderNo: string; provider: PaymentProvider; product: PaymentProduct },
  smsProof?: string,
) {
  const sequence = ++detailActionSequence
  detailActionLoading.value = true
  retryAction.value = null
  paymentResumeWarning.value = ''
  try {
    const result = smsProof
      ? await createPaymentAttempt(request.orderNo, request.provider, request.product, smsProof)
      : await createPaymentAttempt(request.orderNo, request.provider, request.product)
    if (!isCurrentDetailAction(sequence, request.orderNo)) return
    await refreshDetail()
    if (!isCurrentDetailAction(sequence, request.orderNo)) return
    retryAction.value = result.action
  } catch {
    if (!isCurrentDetailAction(sequence, request.orderNo)) return
    ElMessage.error('支付重试失败，请稍后再试。')
  } finally {
    if (isCurrentDetailAction(sequence, request.orderNo)) detailActionLoading.value = false
  }
}

function completePaymentVerification(proof: string) {
  const request = pendingRetry
  pendingRetry = null
  paymentVerificationOpen.value = false
  if (!request || selectedOrderNo.value !== request.orderNo || !detailVisible.value) return
  void executeRetry(request, proof)
}

function restorePaymentAction(detail: OrderDetail) {
  const attempt = detail.paymentAttempts.at(-1)
  if (detail.order.status !== 'PENDING') return
  if (!attempt) {
    paymentResumeWarning.value = '尚未创建支付请求，请关闭订单后重新购买。'
    return
  }
  if (!['CREATED', 'PENDING'].includes(attempt.status)) return
  if (!attempt.action) {
    paymentResumeWarning.value = '支付信息暂时无法恢复，请稍后刷新；仍无法恢复时可关闭订单后重新购买。'
    return
  }
  const actionExpiresAt = Date.parse(attempt.action.expiresAt)
  const orderExpiresAt = Date.parse(detail.expiresAt)
  if (!Number.isFinite(actionExpiresAt) || !Number.isFinite(orderExpiresAt)
      || Math.min(actionExpiresAt, orderExpiresAt) <= Date.now()) {
    paymentResumeWarning.value = '支付信息已失效，请关闭订单后重新购买。'
    return
  }
  retryAction.value = attempt.action
}

function startNewOrder() {
  detailVisible.value = false
  void router.push('/app/plans')
}

async function load() {
  const sequence = ++loadSequence
  const requestedPage = page.value
  const requestedStatus = statusFilter.value
  loading.value = true
  errorMessage.value = ''
  try {
    const requestedSize = pageSize.value
    const result = await listOrders(requestedPage, requestedSize, requestedStatus)
    if (sequence !== loadSequence) return
    if (requestedPage > 0 && requestedPage * requestedSize >= result.total) {
      await router.replace({ query: buildQuery(1, requestedSize) })
      return
    }
    orders.value = result.items
    total.value = result.total
  } catch {
    if (sequence === loadSequence) errorMessage.value = '订单加载失败，请稍后重试。'
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

function buildQuery(nextPage: number, nextSize = pageSize.value) {
  const query: Record<string, string> = {}
  if (statusFilter.value) query.status = statusFilter.value
  if (nextPage > 1) query.page = String(nextPage)
  if (nextSize !== 20) query.size = String(nextSize)
  return query
}

function changePage(nextPage: number) {
  void router.push({ query: buildQuery(nextPage) })
}

function changePageSize(nextSize: number) {
  void router.push({ query: buildQuery(1, nextSize) })
}

function changeStatus(status: string) {
  statusFilter.value = status
  void router.push({ query: buildQuery(1) })
}

function exportCSV() {
  const rows = filteredOrders.value
  if (!rows.length) {
    ElMessage.info('没有可导出的订单')
    return
  }
  const headers = ['订单编号', '下单时间', '套餐名称', '支付金额', '订单状态', '结算方式', '支付渠道', '退款状态']
  const csvRows = [headers.join(',')]
  for (const order of rows) {
    const cells = [
      order.orderNo,
      formatDateTime(order.createdAt),
      userPlanName(order.packageCode, order.packageName),
      amount(order),
      orderStatusLabels[order.status] || order.status,
      settlementLabel(order.settlementMethod, order.paymentProvider),
      order.paymentProvider ? providerLabels[order.paymentProvider] : '未选择',
      order.refundStatus ? refundStatusLabels[order.refundStatus] : '无',
    ].map(cell => `"${String(cell).replace(/"/g, '""')}"`)
    csvRows.push(cells.join(','))
  }
  const csv = '\uFEFF' + csvRows.join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `订单记录_${new Date().toISOString().slice(0, 10)}.csv`
  link.click()
  URL.revokeObjectURL(url)
  ElMessage.success('CSV 已导出')
}

watch(
  () => route.query,
  (query) => {
    const status = queryText(query.status)
    statusFilter.value = orderStatuses.has(status) ? status : ''
    pageSize.value = queryPageSize(query.size)
    page.value = queryPageIndex(query.page)
    void load()
  },
  { immediate: true },
)

onMounted(async () => {
  try {
    quota.value = await quotaSummary()
  } catch {
    // 额度仅用于展示，允许缺失
  }
  await loadActiveSubscription()
})

/**
 * 读取当前用户的活跃订阅，用于把「下次续费」显示成真实的扣款时刻。
 *
 * 失败时静默回退到套餐到期日：这是一个展示增强，不该因为它失败而让整页报错。
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
  <section class="orders-page" aria-labelledby="orders-title">
    <!-- Page Header -->
    <header class="page-header">
      <div class="page-title">
        <h2 id="orders-title">订单管理</h2>
        <p>查看订单状态、支付明细与套餐授权</p>
      </div>
      <button class="export-btn" @click="exportCSV">
        <Download :size="16" />
        导出 CSV
      </button>
    </header>

    <!-- Summary Cards -->
    <div class="stat-cards">
      <div class="stat-card">
        <div class="stat-icon stat-icon--blue"><Wallet :size="20" /></div>
        <div class="stat-body">
          <span class="stat-label">累计消费</span>
          <span class="stat-value">{{ totalSpentLabel }}</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon stat-icon--green"><ShoppingCart :size="20" /></div>
        <div class="stat-body">
          <span class="stat-label">订单总数</span>
          <span class="stat-value">{{ number.format(total) }}</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon stat-icon--purple"><Package :size="20" /></div>
        <div class="stat-body">
          <span class="stat-label">当前套餐</span>
          <span class="stat-value stat-value--text">{{ currentPlanLabel }}</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon stat-icon--orange"><CalendarClock :size="20" /></div>
        <div class="stat-body">
          <span class="stat-label" data-test="next-renewal-caption">{{ activeSubscription ? '下次自动扣款' : '套餐到期' }}</span>
          <span class="stat-value stat-value--text" data-test="next-renewal-value">{{ nextRenewalLabel }}</span>
        </div>
      </div>
    </div>

    <TopErrorMessage :message="errorMessage" />

    <!-- Filter Bar -->
    <div class="filter-bar">
      <div class="search-box">
        <Search :size="16" class="search-icon" />
        <input
          v-model="searchQuery"
          type="text"
          placeholder="搜索订单号或套餐名..."
          aria-label="搜索订单"
        />
      </div>
      <div class="status-tabs">
        <button
          v-for="tab in orderStatusTabs"
          :key="tab.value"
          class="status-tab"
          :data-test="`orders-status-${tab.value || 'all'}`"
          :aria-pressed="statusFilter === tab.value"
          :class="{ active: statusFilter === tab.value }"
          @click="changeStatus(tab.value)"
        >
          {{ tab.label }}
        </button>
      </div>
    </div>

    <el-skeleton v-if="loading" :rows="6" animated />
    <div v-else class="orders-panel">
      <div class="orders-table-wrap">
        <table class="orders-table">
          <thead>
            <tr>
              <th>订单编号</th>
              <th>下单时间</th>
              <th>套餐名称</th>
              <th>支付金额</th>
              <th>订单状态</th>
              <th>结算方式</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="order in filteredOrders" :key="order.id" class="order-row">
              <td>
                <el-tooltip :content="order.orderNo" placement="top">
                  <code class="order-number">{{ compactOrderNo(order.orderNo) }}</code>
                </el-tooltip>
              </td>
              <td class="cell-date">{{ formatDateTime(order.createdAt) }}</td>
              <td><strong class="plan-name">{{ userPlanName(order.packageCode, order.packageName) }}</strong></td>
              <td><span class="amount">{{ amount(order) }}</span></td>
              <td>
                <span class="status-badge" :class="orderStatusClass(order.status)">
                  {{ orderStatusLabels[order.status] || order.status }}
                </span>
              </td>
              <td class="cell-settlement">{{ settlementLabel(order.settlementMethod, order.paymentProvider) }}</td>
              <td>
                <div class="row-actions">
                  <button
                    v-if="order.status === 'PENDING'"
                    class="action-btn action-btn--primary"
                    data-test="list-pay-order"
                    @click="openDetail(order.orderNo)"
                  >
                    <CreditCard :size="14" /> 去支付
                  </button>
                  <button
                    v-if="order.status === 'PAID' && !order.refundStatus"
                    class="action-btn action-btn--warning"
                    data-test="list-refund-order"
                    @click="openRefundForOrder(order.orderNo)"
                  >
                    <RotateCcw :size="14" /> 退款
                  </button>
                  <button
                    class="action-btn action-btn--ghost"
                    data-test="view-order"
                    aria-label="查看订单详情"
                    @click="openDetail(order.orderNo)"
                  >
                    <Eye :size="14" /> 详情
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="!filteredOrders.length">
              <td colspan="7" class="empty-row">
                <ReceiptText :size="24" aria-hidden="true" />
                <span>暂无订单</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="table-footer">
        <span>共 {{ total }} 条订单记录</span>
        <el-pagination
          :current-page="page + 1"
          :page-size="pageSize"
          :page-sizes="pageSizeOptions"
          :total="total"
          layout="sizes, prev, pager, next"
          @size-change="changePageSize"
          @current-change="changePage"
        />
      </footer>
    </div>

    <el-drawer
      v-model="detailVisible"
      title="订单详情"
      direction="rtl"
      size="min(620px, 100vw)"
      :teleported="false"
      @closed="clearDetail"
    >
      <div class="detail-body" data-test="order-detail">
        <el-skeleton v-if="detailLoading" :rows="8" animated />
        <TopErrorMessage v-else-if="detailError" :message="detailError" />
        <template v-else-if="selectedDetail">
          <section class="detail-summary">
            <div class="detail-title">
              <div><span>订单号</span><code class="detail-value">{{ selectedDetail.order.orderNo }}</code></div>
              <span class="status-badge" :class="orderStatusClass(selectedDetail.order.status)">
                {{ orderStatusLabels[selectedDetail.order.status] }}
              </span>
            </div>
            <dl>
              <div><dt>套餐</dt><dd class="detail-value">{{ snapshotPackageName() }}</dd></div>
              <div><dt>套餐版本</dt><dd class="detail-value">{{ snapshotText('packageVersionId') }}</dd></div>
              <div><dt>{{ orderQuotaLabel }}</dt><dd class="detail-value">{{ orderQuotaValue }}</dd></div>
              <div><dt>有效期</dt><dd class="detail-value">{{ snapshotText('durationDays') }} 天</dd></div>
              <div><dt>订单金额</dt><dd class="detail-value">{{ money(selectedDetail.order.amount, selectedDetail.order.currency) }}</dd></div>
              <div><dt>结算方式</dt><dd class="detail-value">{{ settlementLabel(selectedDetail.order.settlementMethod, selectedDetail.order.paymentProvider) }}</dd></div>
              <div><dt>订单失效时间</dt><dd class="detail-value">{{ formatDateTime(selectedDetail.expiresAt) }}</dd></div>
            </dl>
          </section>

          <section class="detail-section">
            <h3>支付尝试</h3>
            <el-timeline v-if="selectedDetail.paymentAttempts.length">
              <el-timeline-item
                v-for="attempt in selectedDetail.paymentAttempts"
                :key="attempt.transactionNo"
                :timestamp="formatDateTime(attempt.createdAt)"
                placement="top"
                :type="attempt.status === 'SUCCEEDED' ? 'success' : attempt.status === 'FAILED' ? 'danger' : 'primary'"
              >
                <div class="attempt-heading"><code>{{ attempt.transactionNo }}</code><el-tag size="small">{{ paymentStatusLabels[attempt.status] }}</el-tag></div>
                <p>{{ attempt.provider }} · {{ attempt.product || '未指定产品' }}</p>
                <p v-if="attempt.paidAt" data-test="attempt-paid-at">到账时间：{{ formatDateTime(attempt.paidAt) }}</p>
                <p v-if="attempt.providerErrorCode" class="error-copy">渠道错误：{{ attempt.providerErrorCode }}</p>
              </el-timeline-item>
            </el-timeline>
            <p v-else class="muted">尚未创建支付尝试。</p>
            <PaymentActionPane v-if="retryAction" class="retry-action" :action="retryAction" />
            <el-alert
              v-else-if="paymentResumeWarning"
              data-test="payment-resume-warning"
              :title="paymentResumeWarning"
              type="warning"
              :closable="false"
              show-icon
            />
          </section>

          <section v-if="selectedDetail.grant" class="detail-section grant-state">
            <div><h3>套餐授权</h3><p>授权由系统自动处理，无需创建新的 API 密钥。</p></div>
            <el-tag :type="selectedDetail.grant.status === 'ACTIVE' ? 'success' : 'warning'">{{ grantStatusLabels[selectedDetail.grant.status] }}</el-tag>
          </section>

          <section v-if="selectedDetail.refund" class="detail-section refund-summary">
            <div><h3>退款状态</h3><code>{{ selectedDetail.refund.refundNo }}</code></div>
            <div><el-tag>{{ refundStatusLabels[selectedDetail.refund.status] }}</el-tag><strong v-if="selectedDetail.refund.amount !== null">{{ money(selectedDetail.refund.amount, selectedDetail.order.currency) }}</strong></div>
          </section>

          <div class="detail-actions">
            <el-button
              v-if="selectedDetail.order.status === 'PENDING' && ['FAILED', 'CLOSED'].includes(selectedDetail.paymentAttempts.at(-1)?.status || '')"
              type="primary"
              data-test="retry-order-payment"
              :loading="detailActionLoading"
              @click="retryPayment"
            ><RefreshCw :size="16" aria-hidden="true" />重试支付</el-button>
            <el-button
              v-if="selectedDetail.order.status === 'PENDING'"
              data-test="close-detail-order"
              :loading="detailActionLoading"
              @click="closeSelectedOrder"
            ><XCircle :size="16" aria-hidden="true" />关闭订单</el-button>
            <el-button v-if="selectedDetail.order.status === 'CLOSED'" type="primary" data-test="new-order" @click="startNewOrder">
              <RefreshCw :size="16" aria-hidden="true" />购买新套餐
            </el-button>
            <el-button
              v-if="selectedDetail.order.status === 'PAID' && !selectedDetail.refund"
              type="primary"
              data-test="open-refund"
              @click="openRefund"
            ><RotateCcw :size="16" aria-hidden="true" />申请退款</el-button>
            <el-button v-else-if="selectedDetail.refund" data-test="view-refund" @click="openRefund">查看退款详情</el-button>
          </div>
        </template>
      </div>
    </el-drawer>

    <RefundDialog
      v-if="selectedDetail"
      v-model="refundVisible"
      :order="selectedDetail"
      @updated="refreshDetail"
    />
    <SmsVerificationDialog
      v-model="paymentVerificationOpen"
      purpose="PAYMENT_CONFIRM"
      @verified="completePaymentVerification"
    />
  </section>
</template>

<style scoped>
.orders-page {
  width: 100%;
  overflow-x: clip;
}

/* 页头 */
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
}
.page-title h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
}
.page-title p {
  margin: 6px 0 0;
  color: var(--app-muted);
  font-size: 14px;
}
.export-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 9px 16px;
  border: 1px solid var(--app-border);
  border-radius: 10px;
  background: var(--app-surface);
  color: var(--app-fg);
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--app-motion-fast) ease;
  flex-shrink: 0;
}
.export-btn:hover {
  border-color: var(--app-primary);
  color: var(--app-primary);
  background: var(--app-primary-soft);
}

/* 汇总卡片 */
.stat-cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}
.stat-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 20px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 14px;
  box-shadow: none;
  transition: all var(--app-motion-base) ease;
}
.stat-card:hover {
  box-shadow: var(--app-shadow-xs);
}
.stat-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  flex-shrink: 0;
}
.stat-icon--blue {
  color: var(--app-primary);
  background: var(--app-primary-soft);
}
.stat-icon--green {
  color: var(--app-success);
  background: var(--app-success-soft);
}
.stat-icon--purple {
  color: var(--app-primary);
  background: var(--app-primary-soft);
}
.stat-icon--orange {
  color: var(--app-warning);
  background: var(--app-warning-soft);
}
.stat-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.stat-label {
  font-size: 12px;
  color: var(--app-muted);
}
.stat-value {
  font-size: 22px;
  font-weight: 700;
  color: var(--app-fg);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.5px;
}
.stat-value--text {
  font-size: 16px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 筛选栏 */
.filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.search-box {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 14px;
  min-width: 280px;
  height: 40px;
  border: 1px solid var(--app-border);
  border-radius: 10px;
  background: var(--app-surface);
  transition: border-color var(--app-motion-fast) ease;
}
.search-box:focus-within {
  border-color: var(--app-primary);
  box-shadow: 0 0 0 3px var(--app-primary-soft);
}
.search-icon {
  color: var(--app-muted);
  flex-shrink: 0;
}
.search-box input {
  flex: 1;
  border: 0;
  background: transparent;
  font: inherit;
  font-size: 13px;
  color: var(--app-fg);
}
.search-box input:focus {
  outline: 0;
}
.status-tabs {
  display: flex;
  gap: 4px;
  padding: 4px;
  border: 1px solid var(--app-border);
  border-radius: 10px;
  background: var(--app-surface);
}
.status-tab {
  padding: 6px 14px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--app-muted);
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--app-motion-fast) ease;
}
.status-tab:hover {
  color: var(--app-fg);
}
.status-tab.active {
  background: var(--app-primary);
  color: #fff;
  font-weight: 600;
}

/* 订单面板 */
.orders-panel {
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 14px;
  overflow: hidden;
}
.orders-table-wrap {
  overflow-x: auto;
}
.orders-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.orders-table th,
.orders-table td {
  padding: 13px 16px;
  border-bottom: 1px solid var(--app-border);
  text-align: left;
  white-space: nowrap;
}
.orders-table thead th {
  color: var(--app-muted);
  font-size: 12px;
  font-weight: 600;
  background: var(--app-surface-subtle);
  text-transform: uppercase;
  letter-spacing: 0.3px;
}
.order-row {
  transition: background var(--app-motion-fast) ease;
}
.order-row:hover {
  background: var(--app-surface-subtle);
}
.order-number {
  cursor: help;
  color: var(--app-primary);
  font-weight: 600;
}
.cell-date {
  color: var(--app-muted);
}
.plan-name {
  font-weight: 600;
}
.amount {
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.cell-settlement {
  color: var(--app-muted);
}

/* 状态徽章 */
.status-badge {
  display: inline-flex;
  align-items: center;
  padding: 3px 12px;
  border-radius: 9999px;
  font-size: 12px;
  font-weight: 600;
}
.status--success {
  color: var(--app-success);
  background: var(--app-success-soft);
}
.status--warning {
  color: var(--app-warning);
  background: var(--app-warning-soft);
}
.status--danger {
  color: var(--app-danger);
  background: var(--app-danger-soft);
}
.status--info {
  color: var(--app-muted-strong);
  background: var(--app-surface-muted);
}

/* 行操作 */
.row-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}
.action-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  border: 1px solid transparent;
  border-radius: 8px;
  font: inherit;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--app-motion-fast) ease;
  white-space: nowrap;
}
.action-btn--primary {
  color: #fff;
  background: var(--app-primary);
}
.action-btn--primary:hover {
  background: var(--app-primary);
  box-shadow: 0 2px 8px rgb(91 159 248 / 25%);
}
.action-btn--warning {
  color: var(--app-warning);
  background: var(--app-warning-soft);
  border-color: var(--app-warning-soft);
}
.action-btn--warning:hover {
  background: var(--app-warning-soft);
}
.action-btn--ghost {
  color: var(--app-muted-strong);
  background: transparent;
  border-color: var(--app-border);
}
.action-btn--ghost:hover {
  color: var(--app-primary);
  border-color: var(--app-primary-soft);
  background: var(--app-primary-soft);
}

/* 空状态 */
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

/* 详情抽屉 */
.detail-body {
  display: grid;
  gap: 16px;
}
.detail-summary, .detail-section {
  padding: 18px;
  border: 1px solid var(--app-border);
  border-radius: 12px;
}
.detail-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
.detail-title span {
  display: block;
  margin-bottom: 3px;
  color: var(--app-muted);
  font-size: 12px;
}
.detail-summary dl {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 0;
}
.detail-summary dt {
  margin-bottom: 3px;
  color: var(--app-muted);
  font-size: 12px;
}
.detail-summary dd {
  margin: 0;
}
.detail-value {
  overflow-wrap: anywhere;
  color: inherit;
  font-family: inherit;
  font-size: 14px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 1.5;
  letter-spacing: 0;
}
.detail-section h3 {
  margin: 0 0 10px;
  font-size: 15px;
}
.attempt-heading, .grant-state, .refund-summary, .refund-summary > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.detail-section p {
  margin: 5px 0 0;
  color: var(--app-muted);
}
.error-copy {
  color: var(--el-color-danger) !important;
}
.grant-state h3, .refund-summary h3 {
  margin-bottom: 4px;
}
.grant-state p {
  margin: 0;
}
.refund-summary > div {
  flex-direction: column;
  align-items: flex-end;
}
.detail-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}
.detail-actions :deep(.el-button) {
  width: 100%;
  min-height: 44px;
  margin: 0;
}
.muted {
  color: var(--app-muted);
}

/* 响应式 */
@media (max-width: 1080px) {
  .stat-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 640px) {
  .page-header {
    flex-direction: column;
  }
  .stat-cards {
    grid-template-columns: minmax(0, 1fr);
  }
  .filter-bar {
    flex-direction: column;
    align-items: stretch;
  }
  .search-box {
    min-width: 0;
  }
  .status-tabs {
    overflow-x: auto;
    flex-wrap: nowrap;
  }
  .action-btn {
    min-height: 44px;
  }
  .detail-summary dl, .detail-actions {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
