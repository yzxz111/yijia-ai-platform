<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { BadgeCheck, CalendarCheck2, CircleDollarSign, Download, Eye, Hourglass, ReceiptText, RefreshCw, Search, Undo2, Wallet2 } from 'lucide-vue-next'

import {
  getAdminUserAvatar,
  getOperatingAnalytics,
  listAdminOrders,
  listPaymentConfigs,
  listPayments,
  listRefunds,
  resolveAdminUserNumbers,
  simulatePayment,
  updatePaymentConfig,
  type AdminListParams,
  type AdminOrder,
  type AdminPayment,
  type AdminRefund,
  type PaymentConfig,
  type OperatingAnalytics,
} from '../../api/admin'
import PaymentAnomalyPanel from '../../components/admin/PaymentAnomalyPanel.vue'
import ReconciliationPanel from '../../components/admin/ReconciliationPanel.vue'
import RefundPolicyPanel from '../../components/admin/RefundPolicyPanel.vue'
import RefundReviewPanel from '../../components/admin/RefundReviewPanel.vue'
import { adminDrillQuery } from '../../router/query'
import { formatDateTime } from '../../utils/dateTime'
import { adminStatusLabel, fingerprintLabel, userNameLabel, userNoLabel } from '../../utils/adminDisplay'
import { userPlanName } from '../../utils/planCopy'

const route = useRoute()
const transactionTabs = ['orders', 'payments', 'refunds', 'refund-policies', 'anomalies', 'reconciliation'] as const
const initialDrill = adminDrillQuery(route.query, transactionTabs, 'orders')
const activeTab = ref(initialDrill.tab)
const refundQuery = ref(initialDrill.refundId || initialDrill.search)
const orders = ref<AdminOrder[]>([])
const payments = ref<AdminPayment[]>([])
const configs = ref<PaymentConfig[]>([])
const operating = ref<OperatingAnalytics | null>(null)
const avatarUrls = ref<Record<string, string>>({})
const userNumbers = ref<Record<string, string>>({})
const userNames = ref<Record<string, string>>({})
const selectedOrder = ref<AdminOrder | null>(null)
const orderDetailOpen = ref(false)
const ordersTotal = ref(0)
const paymentsTotal = ref(0)
const ordersLoading = ref(true)
const paymentsLoading = ref(true)
const configsLoading = ref(true)
const loading = computed(() => ordersLoading.value || paymentsLoading.value || configsLoading.value)
const updatingConfig = ref('')
const simulatingOrder = ref('')
const exportingOrders = ref(false)
const errorMessage = ref('')
const pageSizeOptions = [5, 10, 20]
let ordersLoadSequence = 0
let paymentsLoadSequence = 0
let configsLoadSequence = 0

const ordersParams = reactive<AdminListParams>({
  page: 0, size: 20, query: initialDrill.orderNo || initialDrill.refundId || initialDrill.search, status: '', sort: 'createdAt', direction: 'desc',
})
const paymentsParams = reactive<AdminListParams>({
  page: 0, size: 20, query: initialDrill.search, status: '', sort: 'createdAt', direction: 'desc',
})

async function loadOrders(targetPage = ordersParams.page) {
  const sequence = ++ordersLoadSequence
  ordersLoading.value = true
  errorMessage.value = ''
  try {
    const result = await listAdminOrders({ ...ordersParams, page: targetPage })
    if (sequence !== ordersLoadSequence) return
    orders.value = result.items
    ordersTotal.value = result.total
    ordersParams.page = result.page
    void Promise.all(result.items.filter((row) => row.avatarUpdatedAt).map(loadOrderAvatar))
  } catch {
    if (sequence === ordersLoadSequence) errorMessage.value = '订单数据加载失败，请稍后重试。'
  } finally {
    if (sequence === ordersLoadSequence) ordersLoading.value = false
  }
}

async function loadOrderAvatar(order: AdminOrder) {
  if (avatarUrls.value[order.userId] || !order.avatarUpdatedAt) return
  try {
    const blob = await getAdminUserAvatar(order.userId)
    avatarUrls.value = { ...avatarUrls.value, [order.userId]: URL.createObjectURL(blob) }
  } catch {
    // 头像加载失败时，仍显示用户名首字。
  }
}

async function loadPayments(targetPage = paymentsParams.page) {
  const sequence = ++paymentsLoadSequence
  paymentsLoading.value = true
  errorMessage.value = ''
  try {
    const result = await listPayments({ ...paymentsParams, page: targetPage })
    if (sequence !== paymentsLoadSequence) return
    payments.value = result.items
    try {
      const identities = await resolveAdminUserNumbers(result.items.map((row) => row.userId))
      userNumbers.value = {
        ...userNumbers.value,
        ...Object.fromEntries(identities.map((identity) => [identity.userId, identity.userNo])),
      }
      userNames.value = {
        ...userNames.value,
        ...Object.fromEntries(identities.map((identity) => [identity.userId, identity.displayName])),
      }
    } catch {
      // 用户信息回退为中性占位时，支付记录仍然可见。
    }
    paymentsTotal.value = result.total
    paymentsParams.page = result.page
  } catch {
    if (sequence === paymentsLoadSequence) errorMessage.value = '支付交易加载失败，请稍后重试。'
  } finally {
    if (sequence === paymentsLoadSequence) paymentsLoading.value = false
  }
}

async function loadConfigs() {
  const sequence = ++configsLoadSequence
  configsLoading.value = true
  errorMessage.value = ''
  try {
    const result = await listPaymentConfigs()
    if (sequence === configsLoadSequence) configs.value = result
  } catch {
    if (sequence === configsLoadSequence) errorMessage.value = '支付渠道配置加载失败，请稍后重试。'
  } finally {
    if (sequence === configsLoadSequence) configsLoading.value = false
  }
}

async function loadOperating() {
  try {
    operating.value = await getOperatingAnalytics(6)
  } catch {
    // 汇总暂时不可用时，交易列表仍然可用。
  }
}

const orderStats = ref<{ todayOrders: number; paidOrders: number; pendingOrders: number; refundedOrders: number } | null>(null)
const recentPaid = ref<AdminOrder[]>([])
const recentRefunds = ref<AdminRefund[]>([])
const orderPayments = ref<AdminPayment[]>([])
const orderRefunds = ref<AdminRefund[]>([])

const financeMetrics = computed(() => {
  const summary = operating.value?.summary
  if (!summary) return null
  return {
    collected: summary.currentMonthCollectedAmount,
    refunded: summary.currentMonthRefundedAmount,
    net: summary.currentMonthNetAmount,
    conversion: summary.currentMonthOrders > 0
      ? Math.round((summary.currentMonthPaidOrders / summary.currentMonthOrders) * 1000) / 10
      : null,
    avgOrder: summary.currentMonthPaidOrders > 0
      ? Math.round(summary.currentMonthCollectedAmount / summary.currentMonthPaidOrders)
      : null,
  }
})

async function loadOrderStats() {
  try {
    const result = await listAdminOrders({ page: 0, size: 100, query: '', status: '', sort: 'createdAt', direction: 'desc' })
    const now = new Date()
    const isToday = (iso: string) => {
      const date = new Date(iso)
      return date.getFullYear() === now.getFullYear() && date.getMonth() === now.getMonth() && date.getDate() === now.getDate()
    }
    orderStats.value = {
      todayOrders: result.items.filter((row) => isToday(row.createdAt)).length,
      paidOrders: result.items.filter((row) => row.status === 'PAID').length,
      pendingOrders: result.items.filter((row) => row.status === 'PENDING').length,
      refundedOrders: result.items.filter((row) => row.refundStatus === 'COMPLETED').length,
    }
    recentPaid.value = result.items
      .filter((row) => row.paidAt)
      .sort((a, b) => (b.paidAt || '').localeCompare(a.paidAt || ''))
      .slice(0, 5)
  } catch {
    orderStats.value = null
    recentPaid.value = []
  }
}

async function loadRecentRefunds() {
  try {
    const result = await listRefunds({ page: 0, size: 5, query: '', status: '', sort: 'createdAt', direction: 'desc' })
    recentRefunds.value = result.items
  } catch {
    recentRefunds.value = []
  }
}

async function loadOrderRelations(order: AdminOrder | null) {
  orderPayments.value = []
  orderRefunds.value = []
  if (!order) return
  try {
    const result = await listPayments({ page: 0, size: 10, query: order.orderNo, status: '', sort: 'createdAt', direction: 'desc' })
    orderPayments.value = result.items
  } catch {
    // 查询失败时，支付记录保持为空。
  }
  try {
    const result = await listRefunds({ page: 0, size: 10, query: order.orderNo, status: '', sort: 'createdAt', direction: 'desc' })
    orderRefunds.value = result.items
  } catch {
    // 查询失败时，退款记录保持为空。
  }
}

function load() {
  return Promise.all([loadOrders(), loadPayments(), loadConfigs(), loadOperating(), loadOrderStats(), loadRecentRefunds()])
}

function changeOrdersPageSize(size: number) {
  ordersParams.size = size
  void loadOrders(0)
}

function changePaymentsPageSize(size: number) {
  paymentsParams.size = size
  void loadPayments(0)
}

async function toggleConfig(config: PaymentConfig) {
  if (updatingConfig.value) return
  const sequence = ++configsLoadSequence
  const key = `${config.provider}-${config.environment}`
  updatingConfig.value = key
  errorMessage.value = ''
  try {
    const updated = await updatePaymentConfig(config.provider, config.environment, !config.enabled)
    if (sequence === configsLoadSequence) {
      configs.value = configs.value.map((item) => (
        item.provider === updated.provider && item.environment === updated.environment ? updated : item
      ))
    }
  } catch {
    if (sequence === configsLoadSequence) {
      errorMessage.value = '支付渠道状态更新失败，请确认配置完整后重试。'
    }
  } finally {
    updatingConfig.value = ''
  }
}

async function simulate(row: AdminOrder) {
  if (simulatingOrder.value) return
  try {
    await ElMessageBox.confirm(
      `确认使用服务器端 MOCK 配置模拟订单 ${row.orderNo} 的支付回调？`,
      '模拟支付确认',
      { type: 'warning', confirmButtonText: '确认模拟', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  simulatingOrder.value = row.id
  errorMessage.value = ''
  try {
    await simulatePayment(row.orderNo)
    await loadOrders()
  } catch {
    errorMessage.value = '模拟支付失败，请确认订单仍处于待支付状态。'
  } finally {
    simulatingOrder.value = ''
  }
}

function amount(value: number, currency = 'CNY') {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency }).format(value / 100)
}

function openOrderDetail(order: AdminOrder) {
  selectedOrder.value = order
  orderDetailOpen.value = true
  void loadOrderRelations(order)
}

function paymentProviderLabel(provider: string | null | undefined) {
  return ({ MOCK: '模拟支付', ALIPAY: '支付宝', WECHAT_PAY: '微信支付', BALANCE: '余额支付' } as Record<string, string>)[provider || ''] || '未选择'
}

function paymentEnvironmentLabel(environment: string) {
  return ({ LOCAL: '本地', SANDBOX: '沙箱', PRODUCTION: '生产' } as Record<string, string>)[environment] || environment
}

function statusType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (['PAID', 'SUCCEEDED', 'COMPLETED', 'VALID'].includes(status)) return 'success'
  if (['FAILED', 'REJECTED', 'CANCELED', 'CANCELLED', 'CLOSED', 'INVALID'].includes(status)) return 'danger'
  if (['PENDING', 'PROCESSING', 'REFUNDING'].includes(status)) return 'warning'
  return 'info'
}

function shortValue(value: string | null | undefined, max = 22) {
  if (!value) return '-'
  return value.length > max ? `${value.slice(0, Math.max(8, max - 9))}...${value.slice(-6)}` : value
}

function userInitial(order: AdminOrder) {
  return userNameLabel(order.userDisplayName).slice(0, 1).toUpperCase()
}

function csvCell(value: unknown) {
  const text = value == null ? '' : String(value)
  const protectedText = /^[=+\-@]/.test(text) ? `'${text}` : text
  return /[",\r\n]/.test(protectedText)
    ? `"${protectedText.replace(/"/g, '""')}"`
    : protectedText
}

async function exportOrdersCsv() {
  if (exportingOrders.value) return
  exportingOrders.value = true
  errorMessage.value = ''
  try {
    const exportSize = 200
    const exportLimit = 5_000
    const firstPage = await listAdminOrders({ ...ordersParams, page: 0, size: exportSize })
    if (firstPage.total > exportLimit) {
      errorMessage.value = `导出上限为 ${exportLimit} 条，当前筛选结果 ${firstPage.total} 条，请缩小筛选范围后重试。`
      return
    }
    const rows = [...firstPage.items]
    const pageCount = Math.ceil(firstPage.total / exportSize)
    for (let page = 1; page < pageCount; page += 1) {
      const result = await listAdminOrders({ ...ordersParams, page, size: exportSize })
      rows.push(...result.items)
    }
    const header = ['订单号', '用户编号', '用户', '套餐', '金额', '币种', '积分用量', '累计消费', '订单状态', '支付渠道', '支付状态', '退款状态', '创建时间', '支付时间']
    const body = rows.map((row) => [
      row.orderNo,
      userNoLabel(row.userNo),
      userNameLabel(row.userDisplayName),
      `${userPlanName(row.packageCode, row.packageName)} v${row.packageVersion}`,
      (row.amount / 100).toFixed(2),
      row.currency,
      row.totalTokensUsed.toLocaleString('en-US'),
      (row.cumulativeSpendAmount / 100).toFixed(2),
      adminStatusLabel(row.status),
      paymentProviderLabel(row.paymentProvider),
      row.paymentStatus ? adminStatusLabel(row.paymentStatus) : '',
      row.refundStatus ? adminStatusLabel(row.refundStatus) : '',
      formatDateTime(row.createdAt),
      formatDateTime(row.paidAt),
    ].map(csvCell).join(','))
    const csv = `\uFEFF${[header.map(csvCell).join(','), ...body].join('\r\n')}`
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `订单-${new Date().toISOString().slice(0, 10)}.csv`
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success(`已导出 ${rows.length} 条订单`)
  } catch {
    errorMessage.value = '订单导出失败，请稍后重试。'
  } finally {
    exportingOrders.value = false
  }
}

onMounted(load)
onBeforeUnmount(() => Object.values(avatarUrls.value).forEach((url) => URL.revokeObjectURL(url)))
watch(() => route.query, (query) => {
  const drill = adminDrillQuery(query, transactionTabs, 'orders')
  activeTab.value = drill.tab
  const search = drill.orderNo || drill.refundId || drill.search
  ordersParams.query = search
  paymentsParams.query = search
  refundQuery.value = drill.refundId || drill.search
  if (drill.tab === 'orders') void loadOrders(0)
  if (drill.tab === 'payments') void loadPayments(0)
}, { deep: true })
</script>

<template>
  <section class="admin-page transactions-page" aria-labelledby="admin-orders-title">
    <header class="admin-page__header">
      <div><h2 id="admin-orders-title">订单与支付</h2><p>管理订单、支付、退款审核、异常与对账流程</p></div>
      <div class="page-header-actions">
        <el-button :loading="exportingOrders" @click="exportOrdersCsv"><Download :size="16" />导出 CSV</el-button>
        <el-button :loading="loading" @click="load"><RefreshCw :size="16" />刷新</el-button>
      </div>
    </header>
    <TopErrorMessage :message="errorMessage" />

    <section class="finance-overview" aria-label="订单与财务概览">
      <div class="finance-overview__counts" :class="{ 'is-loading': !orderStats }">
        <div class="fin-count">
          <span class="fin-count__icon fin-count__icon--total"><ReceiptText :size="15" /></span>
          <div><strong>{{ orderStats ? orderStats.todayOrders.toLocaleString() : '—' }}</strong><span>今日订单</span></div>
        </div>
        <div class="fin-count">
          <span class="fin-count__icon fin-count__icon--paid"><BadgeCheck :size="15" /></span>
          <div><strong class="fin-count__paid">{{ orderStats ? orderStats.paidOrders.toLocaleString() : '—' }}</strong><span>支付成功</span></div>
        </div>
        <div class="fin-count">
          <span class="fin-count__icon fin-count__icon--pending"><Hourglass :size="15" /></span>
          <div><strong class="fin-count__pending">{{ orderStats ? orderStats.pendingOrders.toLocaleString() : '—' }}</strong><span>待支付</span></div>
        </div>
        <div class="fin-count">
          <span class="fin-count__icon fin-count__icon--refund"><Undo2 :size="15" /></span>
          <div><strong class="fin-count__refund">{{ orderStats ? orderStats.refundedOrders.toLocaleString() : '—' }}</strong><span>退款订单</span></div>
        </div>
      </div>
      <div class="finance-overview__money">
        <div class="fin-money__head"><Wallet2 :size="15" />本月财务<small v-if="operating">已收款减已退款为净收益</small></div>
        <div class="fin-money__main"><span>净收益</span><strong>{{ financeMetrics ? amount(financeMetrics.net) : '—' }}</strong></div>
        <div class="fin-money__rows">
          <div><span>成交金额</span><b>{{ financeMetrics ? amount(financeMetrics.collected) : '—' }}</b></div>
          <div><span>退款金额</span><b class="fin-money__refund">{{ financeMetrics ? amount(financeMetrics.refunded) : '—' }}</b></div>
          <div><span>支付转化率</span><b>{{ financeMetrics?.conversion != null ? `${financeMetrics.conversion}%` : '—' }}</b></div>
          <div><span>客单价</span><b>{{ financeMetrics?.avgOrder != null ? amount(financeMetrics.avgOrder) : '—' }}</b></div>
        </div>
      </div>
    </section>

    <section class="payment-readiness" aria-labelledby="payment-readiness-title">
      <header><div><h3 id="payment-readiness-title">支付渠道就绪状态</h3><p>仅显示启用状态、配置指纹与最近校验结果</p></div></header>
      <el-skeleton v-if="configsLoading" :rows="2" animated />
      <div v-else class="admin-status-grid payment-config-grid">
        <div v-for="config in configs" :key="`${config.provider}-${config.environment}`" class="admin-status-item payment-config-item">
          <div class="config-copy">
            <div class="config-title"><strong>{{ paymentProviderLabel(config.provider) }}</strong><el-tag size="small" :type="config.enabled ? 'success' : 'info'">{{ config.enabled ? '已启用' : '已停用' }}</el-tag></div>
            <div class="config-meta">{{ paymentEnvironmentLabel(config.environment) }} · {{ config.configured ? '凭据已配置' : '凭据未配置' }}</div>
            <div class="config-meta">凭据指纹：<code>{{ fingerprintLabel(config.credentialFingerprint) }}</code></div>
            <div class="config-meta">校验：<el-tag size="small" :type="statusType(config.lastValidationStatus || '')">{{ config.lastValidationStatus ? adminStatusLabel(config.lastValidationStatus) : '未校验' }}</el-tag> · {{ formatDateTime(config.lastValidatedAt) }}</div>
          </div>
          <el-switch :model-value="config.enabled" :loading="updatingConfig === `${config.provider}-${config.environment}`" :disabled="updatingConfig !== ''" :data-test="`payment-config-${config.provider}-${config.environment}`" :aria-label="`${config.provider} ${config.environment} 支付渠道`" @change="toggleConfig(config)" />
        </div>
        <div v-if="!configs.length" class="admin-empty">暂无支付渠道配置</div>
      </div>
    </section>

    <el-tabs v-model="activeTab" class="transaction-tabs">
      <el-tab-pane label="订单" name="orders">
        <section class="tab-workflow" aria-labelledby="orders-ledger-title">
          <header class="tab-header"><div><h3 id="orders-ledger-title">订单</h3><p>客户订单及支付、退款状态</p></div></header>
          <div class="admin-toolbar ledger-toolbar">
            <el-input v-model="ordersParams.query" data-test="orders-query" aria-label="搜索订单" clearable placeholder="搜索订单、用户或套餐" @keyup.enter="loadOrders(0)" />
            <el-select v-model="ordersParams.status" data-test="orders-status" aria-label="订单状态" clearable placeholder="全部状态"><el-option label="待支付" value="PENDING" /><el-option label="已支付" value="PAID" /><el-option label="已关闭" value="CLOSED" /></el-select>
            <el-select v-model="ordersParams.sort" data-test="orders-sort" aria-label="订单排序字段"><el-option label="创建时间" value="createdAt" /><el-option label="状态" value="status" /><el-option label="金额" value="amount" /><el-option label="订单号" value="orderNo" /></el-select>
            <el-select v-model="ordersParams.direction" data-test="orders-direction" aria-label="订单排序方向"><el-option label="降序" value="desc" /><el-option label="升序" value="asc" /></el-select>
            <el-button data-test="orders-filter" type="primary" :loading="ordersLoading" @click="loadOrders(0)"><Search :size="16" />查询</el-button>
          </div>
          <el-skeleton v-if="ordersLoading" :rows="4" animated />
          <div v-else class="table-scroll"><table class="admin-table orders-table"><thead><tr><th>订单号</th><th>用户</th><th>套餐</th><th>金额</th><th>累计积分</th><th>累计使用金额</th><th>订单状态</th><th>支付</th><th>退款</th><th>创建时间</th><th>操作</th></tr></thead><tbody>
          <tr v-for="row in orders" :key="row.id"><td><code :title="row.orderNo">{{ shortValue(row.orderNo) }}</code></td><td><div class="order-user"><el-avatar :size="32" :src="avatarUrls[row.userId]">{{ userInitial(row) }}</el-avatar><span><strong :title="userNameLabel(row.userDisplayName)">{{ userNameLabel(row.userDisplayName) }}</strong><small :title="userNoLabel(row.userNo)">{{ userNoLabel(row.userNo) }}</small></span></div></td><td><strong class="truncate-cell" :title="`${userPlanName(row.packageCode, row.packageName)} v${row.packageVersion}`">{{ userPlanName(row.packageCode, row.packageName) }} v{{ row.packageVersion }}</strong></td><td class="amount-cell">{{ amount(row.amount, row.currency) }}</td><td>{{ row.totalTokensUsed.toLocaleString() }}</td><td class="amount-cell">{{ amount(row.cumulativeSpendAmount, row.currency) }}</td><td><el-tag size="small" :type="statusType(row.status)">{{ adminStatusLabel(row.status) }}</el-tag></td><td>{{ paymentProviderLabel(row.paymentProvider) }}<br><el-tag v-if="row.paymentStatus" size="small" :type="statusType(row.paymentStatus)">{{ adminStatusLabel(row.paymentStatus) }}</el-tag></td><td><el-tag v-if="row.refundStatus" size="small" :type="statusType(row.refundStatus)">{{ adminStatusLabel(row.refundStatus) }}</el-tag><span v-else>-</span></td><td>{{ formatDateTime(row.createdAt) }}</td><td><div class="admin-actions order-actions"><el-button size="small" :data-test="`order-detail-${row.id}`" @click="openOrderDetail(row)"><Eye :size="15" />订单详情</el-button><el-button v-if="row.paymentProvider === 'MOCK' && row.status === 'PENDING'" size="small" type="warning" :loading="simulatingOrder === row.id" :disabled="simulatingOrder !== ''" :data-test="`simulate-payment-${row.id}`" :aria-label="`模拟支付 ${row.orderNo}`" @click="simulate(row)"><CircleDollarSign :size="15" />模拟支付</el-button></div></td></tr>
            <tr v-if="!orders.length"><td colspan="11" class="admin-empty">暂无订单</td></tr>
          </tbody></table></div>
          <div class="admin-pagination"><span>共 {{ ordersTotal }} 条</span><el-pagination data-test="orders-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="ordersParams.page + 1" :page-size="ordersParams.size" :page-sizes="pageSizeOptions" :total="ordersTotal" @size-change="changeOrdersPageSize" @current-change="(value: number) => loadOrders(value - 1)" /></div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="支付" name="payments">
        <section class="tab-workflow" aria-labelledby="payments-ledger-title">
          <header class="tab-header"><div><h3 id="payments-ledger-title">支付交易</h3><p>平台交易号、外部交易号与到账状态</p></div></header>
          <div class="admin-toolbar ledger-toolbar">
            <el-input v-model="paymentsParams.query" data-test="payments-query" aria-label="搜索支付交易" clearable placeholder="搜索交易、订单或用户" @keyup.enter="loadPayments(0)" />
            <el-select v-model="paymentsParams.status" data-test="payments-status" aria-label="支付状态" clearable placeholder="全部状态"><el-option label="待处理" value="PENDING" /><el-option label="成功" value="SUCCEEDED" /><el-option label="失败" value="FAILED" /><el-option label="已关闭" value="CLOSED" /></el-select>
            <el-select v-model="paymentsParams.sort" data-test="payments-sort" aria-label="支付排序字段"><el-option label="创建时间" value="createdAt" /><el-option label="状态" value="status" /><el-option label="金额" value="amount" /><el-option label="交易号" value="transactionNo" /></el-select>
            <el-select v-model="paymentsParams.direction" data-test="payments-direction" aria-label="支付排序方向"><el-option label="降序" value="desc" /><el-option label="升序" value="asc" /></el-select>
            <el-button data-test="payments-filter" type="primary" :loading="paymentsLoading" @click="loadPayments(0)"><Search :size="16" />查询</el-button>
          </div>
          <el-skeleton v-if="paymentsLoading" :rows="4" animated />
          <div v-else class="table-scroll"><table class="admin-table"><thead><tr><th>交易号</th><th>订单号</th><th>用户编号</th><th>用户名</th><th>渠道</th><th>外部交易号</th><th>金额</th><th>状态</th><th>支付时间</th></tr></thead><tbody>
          <tr v-for="row in payments" :key="row.id"><td><code :title="row.transactionNo">{{ shortValue(row.transactionNo) }}</code></td><td><code :title="row.orderNo">{{ shortValue(row.orderNo) }}</code></td><td><code>{{ userNoLabel(userNumbers[row.userId]) }}</code></td><td>{{ userNameLabel(userNames[row.userId]) }}</td><td>{{ paymentProviderLabel(row.provider) }}</td><td><code :title="row.externalTradeNo || ''">{{ shortValue(row.externalTradeNo) }}</code></td><td class="amount-cell">{{ amount(row.amount) }}</td><td><el-tag size="small" :type="statusType(row.status)">{{ adminStatusLabel(row.status) }}</el-tag></td><td>{{ formatDateTime(row.paidAt) }}</td></tr>
            <tr v-if="!payments.length"><td colspan="9" class="admin-empty">暂无支付交易</td></tr>
          </tbody></table></div>
          <div class="admin-pagination"><span>共 {{ paymentsTotal }} 条</span><el-pagination data-test="payments-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="paymentsParams.page + 1" :page-size="paymentsParams.size" :page-sizes="pageSizeOptions" :total="paymentsTotal" @size-change="changePaymentsPageSize" @current-change="(value: number) => loadPayments(value - 1)" /></div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="退款审核" name="refunds"><RefundReviewPanel :initial-query="refundQuery" /></el-tab-pane>
      <el-tab-pane label="退款策略" name="refund-policies"><RefundPolicyPanel /></el-tab-pane>
      <el-tab-pane label="异常队列" name="anomalies"><PaymentAnomalyPanel /></el-tab-pane>
      <el-tab-pane label="对账" name="reconciliation"><ReconciliationPanel /></el-tab-pane>
    </el-tabs>

    <section class="recent-duo" aria-label="最近支付与退款">
      <div class="recent-panel">
        <h3 class="recent-panel__title"><BadgeCheck :size="15" />最近支付订单</h3>
        <ul v-if="recentPaid.length" class="recent-list">
          <li v-for="row in recentPaid" :key="row.id" class="recent-list__item">
            <div class="recent-list__main">
              <strong :title="userNameLabel(row.userDisplayName)">{{ userNameLabel(row.userDisplayName) }}</strong>
              <span class="recent-list__sub">{{ userPlanName(row.packageCode, row.packageName) }} v{{ row.packageVersion }} · {{ formatDateTime(row.paidAt) }}</span>
            </div>
            <b class="recent-list__amount">{{ amount(row.amount, row.currency) }}</b>
          </li>
        </ul>
        <p v-else class="recent-panel__empty">暂无已支付订单</p>
      </div>
      <div class="recent-panel">
        <h3 class="recent-panel__title"><Undo2 :size="15" />最近退款订单</h3>
        <ul v-if="recentRefunds.length" class="recent-list">
          <li v-for="row in recentRefunds" :key="row.id" class="recent-list__item">
            <div class="recent-list__main">
              <strong :title="userNameLabel(row.userDisplayName)">{{ userNameLabel(row.userDisplayName) }}</strong>
              <span class="recent-list__sub"><el-tag size="small" :type="statusType(row.status)">{{ adminStatusLabel(row.status) }}</el-tag> · {{ formatDateTime(row.createdAt) }}</span>
            </div>
            <b class="recent-list__amount recent-list__amount--refund">-{{ amount(row.amount) }}</b>
          </li>
        </ul>
        <p v-else class="recent-panel__empty">暂无退款记录</p>
      </div>
    </section>

    <el-drawer v-model="orderDetailOpen" title="订单详情" size="min(520px, 100%)">
      <dl v-if="selectedOrder" class="order-detail-list">
        <div><dt>订单号</dt><dd><code>{{ selectedOrder.orderNo }}</code></dd></div>
        <div><dt>用户编号</dt><dd>{{ userNoLabel(selectedOrder.userNo) }}</dd></div>
        <div><dt>用户名</dt><dd>{{ userNameLabel(selectedOrder.userDisplayName) }}</dd></div>
        <div><dt>套餐</dt><dd>{{ userPlanName(selectedOrder.packageCode, selectedOrder.packageName) }} v{{ selectedOrder.packageVersion }}</dd></div>
        <div><dt>金额</dt><dd>{{ amount(selectedOrder.amount, selectedOrder.currency) }}</dd></div>
        <div><dt>订单状态</dt><dd>{{ adminStatusLabel(selectedOrder.status) }}</dd></div>
        <div><dt>支付渠道</dt><dd>{{ paymentProviderLabel(selectedOrder.paymentProvider) }}</dd></div>
        <div><dt>支付状态</dt><dd>{{ selectedOrder.paymentStatus ? adminStatusLabel(selectedOrder.paymentStatus) : '暂无' }}</dd></div>
        <div><dt>退款状态</dt><dd>{{ selectedOrder.refundStatus ? adminStatusLabel(selectedOrder.refundStatus) : '暂无' }}</dd></div>
        <div><dt>累计积分</dt><dd>{{ selectedOrder.totalTokensUsed.toLocaleString() }}</dd></div>
        <div><dt>累计使用金额</dt><dd>{{ amount(selectedOrder.cumulativeSpendAmount, selectedOrder.currency) }}</dd></div>
        <div><dt>创建时间</dt><dd>{{ formatDateTime(selectedOrder.createdAt) }}</dd></div>
        <div><dt>支付时间</dt><dd>{{ formatDateTime(selectedOrder.paidAt) }}</dd></div>
      </dl>

      <div v-if="selectedOrder" class="order-flows">
        <h4 class="order-flows__title"><CalendarCheck2 :size="14" />支付记录</h4>
        <ul v-if="orderPayments.length" class="order-flows__list">
          <li v-for="row in orderPayments" :key="row.id">
            <code :title="row.transactionNo">{{ shortValue(row.transactionNo) }}</code>
            <span>{{ paymentProviderLabel(row.provider) }}</span>
            <el-tag size="small" :type="statusType(row.status)">{{ adminStatusLabel(row.status) }}</el-tag>
            <b>{{ amount(row.amount) }}</b>
            <small>{{ formatDateTime(row.paidAt) }}</small>
          </li>
        </ul>
        <p v-else class="order-flows__empty">该订单暂无支付流水</p>

        <h4 class="order-flows__title"><Undo2 :size="14" />退款记录</h4>
        <ul v-if="orderRefunds.length" class="order-flows__list">
          <li v-for="row in orderRefunds" :key="row.id">
            <code :title="row.refundNo">{{ shortValue(row.refundNo) }}</code>
            <el-tag size="small" :type="statusType(row.status)">{{ adminStatusLabel(row.status) }}</el-tag>
            <b class="order-flows__refund">-{{ amount(row.amount) }}</b>
            <small>{{ formatDateTime(row.createdAt) }}</small>
          </li>
        </ul>
        <p v-else class="order-flows__empty">该订单暂无退款记录</p>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.transactions-page { overflow-x: clip; }
.page-header-actions { display: flex; align-items: center; gap: 8px; }
/* ===== 财务概览带：左侧计数 + 右侧深色金额面板 ===== */
.finance-overview { display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: 14px; margin-bottom: 14px; }
.finance-overview__counts { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.fin-count { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-surface); box-shadow: var(--app-shadow-xs); }
.fin-count__icon { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; min-width: 32px; border-radius: 8px; }
.fin-count__icon--total { color: var(--app-primary); background: var(--app-primary-soft); }
.fin-count__icon--paid { color: var(--app-success); background: var(--app-success-soft); }
.fin-count__icon--pending { color: var(--app-warning); background: var(--app-warning-soft); }
.fin-count__icon--refund { color: var(--app-muted-strong); background: var(--app-surface-muted); }
.fin-count strong { display: block; font-size: 18px; font-weight: 700; line-height: 1.2; color: var(--app-fg); font-variant-numeric: tabular-nums; }
.fin-count__paid { color: var(--app-success); }
.fin-count__pending { color: var(--app-warning); }
.fin-count__refund { color: var(--app-muted-strong); }
.fin-count span { display: block; font-size: 12px; color: var(--app-muted-strong); white-space: nowrap; }
.finance-overview__money { display: flex; flex-direction: column; gap: 8px; padding: 14px 16px; border-radius: 8px; background: #22303f; color: var(--app-surface-sunken); box-shadow: var(--app-shadow-xs); }
.fin-money__head { display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; color: var(--app-muted); }
.fin-money__head small { margin-left: auto; font-weight: 400; font-size: 11px; color: #7b8b9b; }
.fin-money__main { display: flex; align-items: baseline; justify-content: space-between; padding-bottom: 8px; border-bottom: 1px solid rgba(255, 255, 255, 0.12); }
.fin-money__main span { font-size: 12px; color: var(--app-muted); }
.fin-money__main strong { font-size: 22px; font-weight: 700; letter-spacing: 0.2px; color: #ffffff; font-variant-numeric: tabular-nums; }
.fin-money__rows { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 14px; }
.fin-money__rows div { display: flex; justify-content: space-between; font-size: 12px; }
.fin-money__rows span { color: var(--app-muted); }
.fin-money__rows b { font-weight: 600; color: var(--app-surface-sunken); font-variant-numeric: tabular-nums; }
.fin-money__rows b.fin-money__refund { color: #e6a08c; }
.orders-table { min-width: 1460px; }
.amount-cell { font-weight: 700; color: var(--app-fg); font-variant-numeric: tabular-nums; }
.order-user { display: flex; min-width: 150px; align-items: center; gap: 8px; }
.order-user span { min-width: 0; }
.order-user strong, .order-user small { display: block; max-width: 130px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.order-user small { margin-top: 2px; color: var(--app-muted); }
.truncate-cell { display: block; max-width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.payment-readiness, .transaction-tabs { overflow: hidden; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: var(--app-radius); }
.payment-readiness { margin-bottom: 14px; }
.payment-readiness > header, .tab-header { padding: 12px 14px; border-bottom: 1px solid var(--app-border); }
.payment-readiness h3, .tab-header h3 { margin: 0; font-size: 16px; letter-spacing: 0; }
.payment-readiness p, .tab-header p { margin: 3px 0 0; color: var(--app-muted); font-size: 12px; }
.payment-config-grid { grid-auto-rows: 1fr; padding: 12px 14px; }
.payment-config-item { min-width: 0; height: 100%; align-items: flex-start; }
.config-copy { min-width: 0; }
.config-title { display: flex; align-items: center; gap: 8px; }
.config-meta { margin-top: 4px; overflow-wrap: anywhere; color: var(--app-muted); font-size: 12px; }
.config-meta code { white-space: normal; }
.transaction-tabs :deep(.el-tabs__header) { margin: 0; padding: 0 14px; }
.transaction-tabs :deep(.el-tabs__content) { overflow: visible; }
.transaction-tabs :deep(.el-tabs__nav-scroll) { overflow-x: auto; }
.tab-workflow { min-width: 0; }
.ledger-toolbar { padding: 12px 14px 0; }
.order-actions { flex-wrap: nowrap; }
.order-detail-list { display: grid; margin: 0; }
.order-detail-list > div { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 16px; padding: 12px 0; border-bottom: 1px solid var(--app-border); }
.order-detail-list dt { color: var(--app-muted); }
.order-detail-list dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }

/* ===== 底部最近支付/退款 ===== */
.recent-duo { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; margin-top: 14px; }
.recent-panel { padding: 14px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-surface); box-shadow: var(--app-shadow-xs); }
.recent-panel__title { display: flex; align-items: center; gap: 7px; margin: 0 0 10px; font-size: 14px; font-weight: 700; color: var(--app-fg); }
.recent-panel__empty { margin: 18px 0; font-size: 12px; color: var(--app-muted); text-align: center; }
.recent-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 4px; }
.recent-list__item { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 7px 8px; border-radius: 6px; }
.recent-list__item:hover { background: var(--app-surface-muted); }
.recent-list__main { min-width: 0; display: flex; flex-direction: column; }
.recent-list__main strong { font-size: 13px; font-weight: 600; color: var(--app-fg); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.recent-list__sub { margin-top: 2px; font-size: 11px; color: var(--app-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.recent-list__amount { font-size: 13px; font-weight: 700; color: var(--app-fg); font-variant-numeric: tabular-nums; white-space: nowrap; }
.recent-list__amount--refund { color: var(--app-muted-strong); }

/* ===== 详情抽屉流水 ===== */
.order-flows { margin-top: 16px; }
.order-flows__title { display: flex; align-items: center; gap: 6px; margin: 14px 0 8px; font-size: 13px; font-weight: 700; color: var(--app-fg); }
.order-flows__empty { margin: 6px 0 2px; font-size: 12px; color: var(--app-muted); }
.order-flows__list { list-style: none; margin: 0; padding: 0; display: grid; gap: 6px; }
.order-flows__list li { display: grid; grid-template-columns: minmax(0, 1.2fr) auto auto auto; align-items: center; gap: 8px; padding: 8px 10px; border: 1px solid var(--app-border); border-radius: 6px; background: var(--app-surface-muted); }
.order-flows__list code { font-size: 11px; color: var(--app-muted-strong); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.order-flows__list span { font-size: 12px; color: var(--app-muted-strong); }
.order-flows__list b { font-size: 13px; font-weight: 700; color: var(--app-fg); font-variant-numeric: tabular-nums; }
.order-flows__list b.order-flows__refund { color: var(--app-muted-strong); }
.order-flows__list small { grid-column: 1 / -1; font-size: 11px; color: var(--app-muted); }

@media (max-width: 1100px) {
  .finance-overview { grid-template-columns: 1fr; }
  .finance-overview__counts { grid-template-columns: repeat(4, minmax(0, 1fr)); }
}
@media (max-width: 720px) {
  .finance-overview__counts { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .recent-duo { grid-template-columns: 1fr; }
}
@media (max-width: 600px) {
  .page-header-actions { width: 100%; justify-content: flex-end; }
  .payment-config-item { align-items: flex-start; }
  .transaction-tabs :deep(.el-tabs__header) { padding-inline: 8px; }
}
</style>
