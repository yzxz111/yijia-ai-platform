<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { AlertCircle, RefreshCw, Repeat, RotateCcw, Search, StopCircle } from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'

import {
  getAdminSubscription,
  listAdminSubscriptions,
  retrySubscriptionCycle,
  terminateSubscription,
  type AdminSubscription,
  type SubscriptionCycle,
  type SubscriptionSkipReason,
  type SubscriptionStatus,
} from '../../api/subscriptions'
import { readApiErrorMessage } from '../../api/apiError'
import { formatDateTime } from '../../utils/dateTime'

const PAGE_SIZE = 20

const rows = ref<AdminSubscription[]>([])
const total = ref(0)
const page = ref(0)
const statusFilter = ref<SubscriptionStatus | ''>('')
const planCodeFilter = ref('')
const loading = ref(false)
const loadError = ref('')
const busyId = ref('')
const detail = ref<{ subscription: AdminSubscription; cycles: SubscriptionCycle[] } | null>(null)
let loadSequence = 0

const statusLabels: Record<string, string> = {
  ACTIVE: '生效中', PAST_DUE: '续费失败', CANCELED: '已关闭续费', EXPIRED: '已到期', TERMINATED: '已终止',
}
const cycleStatusLabels: Record<string, string> = {
  PENDING: '待扣款', CHARGED: '已扣款', FAILED: '扣款失败', GRANTED: '已生效',
}
const skipReasonLabels: Record<SubscriptionSkipReason, string> = {
  canceled_by_user: '用户已关闭自动续费',
  subscription_not_open: '订阅不处于可续费状态',
  account_not_active: '账号状态异常，已暂停',
  active_package_present: '用户已持有生效中的套餐',
  plan_unavailable: '套餐已下架',
  subscription_price_changed: '价格调整待用户确认',
  renewal_instance_stuck_queued: '权益未生效，已停止扣款',
  refund_terminated: '订单已退款，订阅已终止',
  price_not_confirmed: '价格调整长期未确认，已终止',
}

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

function money(minorUnits: number | null, currency = 'CNY') {
  if (minorUnits == null) return '--'
  const units = BigInt(minorUnits)
  const absolute = units < 0n ? -units : units
  const whole = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 }).format(absolute / 100n)
  const fraction = (absolute % 100n).toString().padStart(2, '0')
  const symbol = currency === 'CNY' ? '¥' : `${currency} `
  return `${units < 0n ? '-' : ''}${symbol}${whole}.${fraction}`
}

function statusClass(status: string) {
  if (status === 'ACTIVE') return 'tag tag--ok'
  if (status === 'PAST_DUE') return 'tag tag--warn'
  if (status === 'TERMINATED') return 'tag tag--danger'
  return 'tag tag--muted'
}

async function load() {
  const sequence = ++loadSequence
  loading.value = true
  loadError.value = ''
  try {
    const result = await listAdminSubscriptions({
      page: page.value,
      size: PAGE_SIZE,
      status: statusFilter.value || null,
      planCode: planCodeFilter.value.trim() || null,
    })
    if (sequence !== loadSequence) return
    rows.value = result.items
    total.value = result.total
  } catch (error) {
    if (sequence !== loadSequence) return
    loadError.value = readApiErrorMessage(error) || '订阅列表加载失败'
    rows.value = []
    total.value = 0
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

function search() {
  page.value = 0
  return load()
}

watch(page, load)

async function openDetail(row: AdminSubscription) {
  busyId.value = row.id
  try {
    detail.value = await getAdminSubscription(row.id)
  } catch (error) {
    ElMessage.error(readApiErrorMessage(error) || '订阅详情加载失败')
  } finally {
    busyId.value = ''
  }
}

/**
 * 人工终止。**原因必填**——后端会把它写进审计，因此这里在提交前就拦住空原因，
 * 而不是让用户收到一个 422 之后再猜要填什么。
 */
async function terminate(row: AdminSubscription) {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      '终止后不再扣款，且该订阅不会自动恢复。请填写原因（会记入审计日志）。',
      '终止订阅',
      {
        type: 'warning',
        confirmButtonText: '确认终止',
        cancelButtonText: '取消',
        inputPlaceholder: '例如：用户投诉重复扣款',
        inputValidator: (value: string) => (value && value.trim() ? true : '必须填写原因'),
      },
    )
    reason = result.value.trim()
  } catch (cancelled) {
    return
  }
  busyId.value = row.id
  try {
    await terminateSubscription(row.id, reason)
    ElMessage.success('订阅已终止')
    await load()
  } catch (error) {
    ElMessage.error(readApiErrorMessage(error) || '终止失败')
  } finally {
    busyId.value = ''
  }
}

/**
 * 人工重试失败的一期。
 *
 * 后端是幂等的：账期已不是 FAILED 时返回 `retried=false`，这里如实转述而不是
 * 报一个「失败」——那种情况下系统状态本来就是对的。
 */
async function retry(cycle: SubscriptionCycle) {
  busyId.value = cycle.id
  try {
    const result = await retrySubscriptionCycle(cycle.id, '管理端人工重试')
    if (result.retried) {
      ElMessage.success(`第 ${result.periodIndex} 期已重新排队，调度器将尽快重试`)
    } else {
      ElMessage.info('该期已不在失败状态，无需重试')
    }
    if (detail.value) {
      detail.value = await getAdminSubscription(detail.value.subscription.id)
    }
    await load()
  } catch (error) {
    ElMessage.error(readApiErrorMessage(error) || '重试失败')
  } finally {
    busyId.value = ''
  }
}

onMounted(load)
</script>

<template>
  <section class="page" aria-labelledby="admin-subscriptions-title">
    <header class="head">
      <div>
        <h2 id="admin-subscriptions-title">订阅治理</h2>
        <p class="sub">查看连续包月订阅、终止异常订阅、人工重试失败的扣款。</p>
      </div>
      <button class="btn btn--outline" :disabled="loading" @click="load">
        <RefreshCw :size="14" /> 刷新
      </button>
    </header>

    <div class="filters">
      <label>
        状态
        <select v-model="statusFilter" data-test="admin-subscription-status-filter" @change="search">
          <option value="">全部</option>
          <option value="ACTIVE">生效中</option>
          <option value="PAST_DUE">续费失败</option>
          <option value="CANCELED">已关闭续费</option>
          <option value="EXPIRED">已到期</option>
          <option value="TERMINATED">已终止</option>
        </select>
      </label>
      <label>
        套餐代码
        <input
          v-model="planCodeFilter"
          data-test="admin-subscription-plan-filter"
          placeholder="例如 STARTER"
          @keyup.enter="search"
        >
      </label>
      <button class="btn btn--outline" data-test="admin-subscription-search" @click="search">
        <Search :size="14" /> 查询
      </button>
    </div>

    <div v-if="loadError" class="notice notice--error" data-test="admin-subscription-error">
      <AlertCircle :size="16" /> {{ loadError }}
    </div>

    <table data-test="admin-subscription-table">
      <thead>
        <tr>
          <th>用户</th><th>套餐</th><th>状态</th><th>扣款</th><th>下次扣款</th>
          <th>失败</th><th>已续费</th><th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="loading"><td colspan="8" class="muted">加载中…</td></tr>
        <tr v-else-if="!rows.length"><td colspan="8" class="muted">没有符合条件的订阅</td></tr>
        <tr
          v-for="row in rows"
          v-else
          :key="row.id"
          data-test="admin-subscription-row"
          :data-subscription-id="row.id"
        >
          <td>{{ row.userEmail || row.userId }}</td>
          <td>{{ row.planName }}<small class="muted"> · {{ row.planCode }}</small></td>
          <td>
            <span :class="statusClass(row.status)">{{ statusLabels[row.status] || row.status }}</span>
            <small v-if="!row.autoRenew" class="muted"> · 不自动续费</small>
          </td>
          <td>
            <!-- 租约非空 = 正被某个调度实例处理。运维最需要这个信号：
                 它区分「任务没跑」与「正在跑」。 -->
            <span v-if="row.charged" class="tag tag--warn" data-test="admin-subscription-charging">处理中</span>
            <span v-else class="muted">—</span>
          </td>
          <td>{{ formatDateTime(row.nextChargeAt) }}</td>
          <td>
            <span v-if="row.consecutiveFailures > 0" data-test="admin-subscription-failures">
              {{ row.consecutiveFailures }} 次
            </span>
            <span v-else class="muted">—</span>
            <small v-if="row.lastSkipReason" class="muted" data-test="admin-subscription-skip">
              <br>{{ skipReasonLabels[row.lastSkipReason] || row.lastSkipReason }}
            </small>
          </td>
          <td>{{ row.renewalCount }} 期 · {{ money(row.lastChargedAmount) }}</td>
          <td class="actions">
            <button class="btn btn--outline" :disabled="busyId === row.id" @click="openDetail(row)">
              详情
            </button>
            <button
              class="btn btn--danger"
              data-test="admin-subscription-terminate"
              :disabled="busyId === row.id || row.status === 'TERMINATED'"
              @click="terminate(row)"
            >
              <StopCircle :size="14" /> 终止
            </button>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="pageCount > 1" class="pager">
      <button class="btn btn--outline" :disabled="page === 0" @click="page -= 1">上一页</button>
      <span>第 {{ page + 1 }} / {{ pageCount }} 页 · 共 {{ total }} 条</span>
      <button class="btn btn--outline" :disabled="page + 1 >= pageCount" @click="page += 1">下一页</button>
    </div>

    <section v-if="detail" class="detail" data-test="admin-subscription-detail">
      <header class="detail-head">
        <h3>
          <Repeat :size="14" />
          扣款记录 · {{ detail.subscription.planName }}
          <small class="muted"> · {{ detail.subscription.userEmail || detail.subscription.userId }}</small>
        </h3>
        <button class="btn btn--outline" @click="detail = null">关闭</button>
      </header>
      <table>
        <thead>
          <tr><th>期数</th><th>周期</th><th>状态</th><th>金额</th><th>订单号</th><th>失败原因</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="cycle in detail.cycles" :key="cycle.id" data-test="admin-subscription-cycle">
            <td>第 {{ cycle.periodIndex }} 期</td>
            <td>{{ formatDateTime(cycle.periodStart) }} — {{ formatDateTime(cycle.periodEnd) }}</td>
            <td>{{ cycleStatusLabels[cycle.status] || cycle.status }}</td>
            <td>{{ money(cycle.amount, cycle.currency) }}</td>
            <td>{{ cycle.orderNo || '--' }}</td>
            <td>{{ cycle.failureReason || '--' }}</td>
            <td>
              <button
                v-if="cycle.status === 'FAILED'"
                class="btn btn--outline"
                data-test="admin-subscription-retry"
                :disabled="busyId === cycle.id"
                @click="retry(cycle)"
              >
                <RotateCcw :size="14" /> 重试
              </button>
              <span v-else class="muted">—</span>
            </td>
          </tr>
        </tbody>
      </table>
    </section>
  </section>
</template>

<style scoped>
.page { padding: 24px; }
.head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.head h2 { font-size: 20px; margin: 0 0 6px; }
.sub { color: #6b7280; font-size: 13px; margin: 0; }
.filters { display: flex; align-items: flex-end; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.filters label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #6b7280; }
.filters select, .filters input { border: 1px solid #d1d5db; border-radius: 8px; padding: 6px 10px; font-size: 13px; }
.notice { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border-radius: 8px; font-size: 13px; margin-bottom: 16px; }
.notice--error { background: #fef2f2; color: #b91c1c; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f3f4f6; vertical-align: top; }
th { color: #6b7280; font-weight: 500; }
.muted { color: #9ca3af; }
.actions { display: flex; gap: 6px; }
.pager { display: flex; align-items: center; gap: 12px; margin-top: 14px; font-size: 13px; color: #6b7280; }
.tag { display: inline-block; padding: 2px 8px; border-radius: 999px; font-size: 12px; }
.tag--ok { background: #ecfdf5; color: #047857; }
.tag--warn { background: #fffbeb; color: #b45309; }
.tag--danger { background: #fef2f2; color: #b91c1c; }
.tag--muted { background: #f3f4f6; color: #6b7280; }
.btn { display: inline-flex; align-items: center; gap: 4px; border-radius: 8px; padding: 6px 12px; font-size: 13px; cursor: pointer; border: 1px solid transparent; }
.btn:disabled { opacity: .6; cursor: not-allowed; }
.btn--outline { border-color: #d1d5db; background: #fff; color: #374151; }
.btn--danger { border-color: #fecaca; background: #fff; color: #b91c1c; }
.detail { margin-top: 24px; border-top: 1px solid #e5e7eb; padding-top: 16px; }
.detail-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.detail-head h3 { display: flex; align-items: center; gap: 6px; font-size: 15px; margin: 0; }
</style>
