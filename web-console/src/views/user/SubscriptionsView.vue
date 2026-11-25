<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { AlertCircle, CalendarClock, RefreshCw, Repeat, ShieldCheck, XCircle } from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'

import {
  cancelSubscription,
  confirmSubscriptionPrice,
  getSubscription,
  listSubscriptions,
  resumeSubscription,
  type SubscriptionCycle,
  type SubscriptionSkipReason,
  type SubscriptionSummary,
} from '../../api/subscriptions'
import { formatDateTime } from '../../utils/dateTime'

const subscriptions = ref<SubscriptionSummary[]>([])
const detail = ref<{ subscription: SubscriptionSummary; cycles: SubscriptionCycle[] } | null>(null)
const loading = ref(false)
const loadError = ref('')
const busyId = ref('')
let loadSequence = 0

const statusLabels: Record<string, string> = {
  ACTIVE: '生效中', PAST_DUE: '续费失败', CANCELED: '已关闭续费', EXPIRED: '已到期', TERMINATED: '已终止',
}
const cycleStatusLabels: Record<string, string> = {
  PENDING: '待扣款', CHARGED: '已扣款', FAILED: '扣款失败', GRANTED: '已生效',
}
/**
 * 跳过原因的用户可读文案。
 *
 * 后端刻意把「跳过」与「失败」分成两个字段（见 `subscriptions.ts` 的类型注释），
 * 因此这里也必须是两套文案：把「你已持有生效中的套餐，本次未扣款」写成「续费失败」
 * 会让用户白白紧张一次。
 */
const skipReasonLabels: Record<SubscriptionSkipReason, string> = {
  canceled_by_user: '你已关闭自动续费，本期不再扣款',
  subscription_not_open: '订阅当前不处于可续费状态',
  account_not_active: '账号状态异常，已暂停扣款',
  active_package_present: '你已持有生效中的套餐，本次未扣款',
  plan_unavailable: '该套餐已下架，续费已暂停',
  subscription_price_changed: '续费价格有调整，请确认后继续',
  renewal_instance_stuck_queued: '权益未能正常生效，已停止扣款，请联系客服',
  refund_terminated: '该订单已退款，订阅已终止',
  price_not_confirmed: '续费价格调整后长期未确认，订阅已终止',
}

/**
 * 分 → 元。与 `WalletView.vue` 同一实现：用 BigInt 而不是除法，
 * 避免 `2900 / 100 = 28.999999999999996` 这类浮点误差出现在金额展示上。
 */
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
  if (status === 'CANCELED') return 'tag tag--muted'
  return 'tag tag--danger'
}

function cycleClass(status: string) {
  if (status === 'GRANTED' || status === 'CHARGED') return 'tag tag--ok'
  if (status === 'FAILED') return 'tag tag--danger'
  return 'tag tag--muted'
}

/**
 * 卡片上的「下一次扣款」。关闭续费后不再展示扣款日——那时已不会扣款，
 * 继续显示一个扣款日期会让用户以为还会被扣。
 */
function nextChargeText(row: SubscriptionSummary) {
  if (!row.autoRenew || row.cancelAtPeriodEnd) {
    return `当前周期结束后不再续费，${formatDateTime(row.currentPeriodEnd)} 到期`
  }
  return `下次扣款 ${formatDateTime(row.nextChargeAt)}`
}

async function load() {
  const sequence = ++loadSequence
  loading.value = true
  loadError.value = ''
  try {
    const items = await listSubscriptions()
    if (sequence !== loadSequence) return
    subscriptions.value = items
  } catch (error) {
    if (sequence !== loadSequence) return
    loadError.value = '订阅列表加载失败，请稍后重试'
    subscriptions.value = []
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

async function openDetail(row: SubscriptionSummary) {
  busyId.value = row.id
  try {
    detail.value = await getSubscription(row.id)
  } catch (error) {
    ElMessage.error('订阅详情加载失败')
  } finally {
    busyId.value = ''
  }
}

async function confirmCancel(row: SubscriptionSummary) {
  try {
    await ElMessageBox.confirm(
      '关闭后当前周期仍可使用到结束，之后不再自动扣款。确定关闭自动续费吗？',
      '关闭自动续费',
      { type: 'warning', confirmButtonText: '确认关闭', cancelButtonText: '再想想' },
    )
  } catch (cancelled) {
    return
  }
  busyId.value = row.id
  try {
    await cancelSubscription(row.id)
    ElMessage.success('已关闭自动续费，当前周期结束前仍可正常使用')
    await load()
  } catch (error) {
    ElMessage.error('关闭失败，请稍后重试')
  } finally {
    busyId.value = ''
  }
}

async function restore(row: SubscriptionSummary) {
  busyId.value = row.id
  try {
    await resumeSubscription(row.id)
    ElMessage.success('已恢复自动续费')
    await load()
  } catch (error) {
    // 当期已结束时后端会返回 409 subscription_period_ended——这是正常结果，
    // 用一句明确的说明代替「操作失败」。
    ElMessage.error('无法恢复：当前周期已结束')
  } finally {
    busyId.value = ''
  }
}

/**
 * 确认接受调整后的续费价。
 *
 * 后端在确认时会重新比对「你确认的价」与「当前已发布价」；若期间又调过价，返回 409
 * `subscription_price_changed_again`。那种情况下**不能**提示「失败」了事——用户需要知道
 * 价格又变了、要再确认一次，否则他只会反复点同一个按钮。
 */
async function confirmPrice(row: SubscriptionSummary) {
  const quoted = money(row.pendingPriceAmount)
  try {
    await ElMessageBox.confirm(
      `确认后，下一期将按 ${quoted} 自动扣款。若在此之前价格再次调整，需要重新确认。`,
      '确认续费价格',
      { type: 'warning', confirmButtonText: '确认并继续', cancelButtonText: '暂不确认' },
    )
  } catch (cancelled) {
    return
  }
  busyId.value = row.id
  try {
    await confirmSubscriptionPrice(row.id)
    ElMessage.success('已确认续费价格，下一期将按新价格扣款')
    await load()
  } catch (error) {
    const status = (error as { response?: { status?: number; data?: { code?: string } } })
      ?.response
    if (status?.status === 409 && status.data?.code === 'subscription_price_changed_again') {
      ElMessage.warning('价格又调整了，请核对新的金额后重新确认')
    } else if (status?.status === 409) {
      ElMessage.info('该价格无需确认或已确认')
    } else {
      ElMessage.error('确认失败，请稍后重试')
    }
    await load()
  } finally {
    busyId.value = ''
  }
}

const hasSubscriptions = computed(() => subscriptions.value.length > 0)

onMounted(load)
</script>

<template>
  <section class="page">
    <header class="head">
      <div>
        <h1>我的订阅</h1>
        <p class="sub">连续包月会在每个周期结束前自动从余额扣款；你可以随时关闭，当期用满为止。</p>
      </div>
      <button class="btn btn--outline" :disabled="loading" @click="load">
        <RefreshCw :size="14" /> 刷新
      </button>
    </header>

    <div v-if="loadError" class="notice notice--error" data-test="subscription-load-error">
      <AlertCircle :size="16" /> {{ loadError }}
    </div>

    <div v-if="loading && !hasSubscriptions" class="empty">加载中…</div>

    <div v-else-if="!hasSubscriptions" class="empty" data-test="subscription-empty">
      <Repeat :size="22" />
      <p>还没有订阅。到套餐页面选择「连续包月」即可开通。</p>
    </div>

    <div v-else class="grid">
      <article
        v-for="row in subscriptions"
        :key="row.id"
        class="card"
        data-test="subscription-card"
        :data-subscription-status="row.status"
      >
        <div class="card-head">
          <h2>{{ row.planName || '订阅套餐' }}</h2>
          <span :class="statusClass(row.status)" data-test="subscription-status">
            {{ statusLabels[row.status] || row.status }}
          </span>
        </div>

        <dl class="facts">
          <div>
            <dt><CalendarClock :size="14" /> 当前周期</dt>
            <dd>{{ formatDateTime(row.currentPeriodStart) }} — {{ formatDateTime(row.currentPeriodEnd) }}</dd>
          </div>
          <div>
            <dt><ShieldCheck :size="14" /> 扣款</dt>
            <dd data-test="subscription-next-charge">{{ nextChargeText(row) }}</dd>
          </div>
          <div>
            <dt>上次扣款</dt>
            <dd>{{ money(row.lastChargedAmount) }}</dd>
          </div>
          <div>
            <dt>已续费</dt>
            <dd>{{ row.renewalCount }} 期</dd>
          </div>
        </dl>

        <p v-if="row.lastSkipReason" class="hint" data-test="subscription-skip-reason">
          {{ skipReasonLabels[row.lastSkipReason] || row.lastSkipReason }}
        </p>
        <!--
          涨价待确认。这一块是**必须**显眼的：订阅此时处于「暂缓」状态，系统一次都没有按新价
          扣款，而且不会有失败通知——用户如果不处理，过了后端配置的时限（默认 30 天）订阅会被
          终止。只把它当成一条普通提示，用户就只会看到「订阅悄悄停了」。
        -->
        <div v-if="row.pendingPriceAmount !== null" class="notice notice--warn" data-test="subscription-price-change">
          <AlertCircle :size="14" />
          <div>
            <strong>续费价格已调整</strong>
            <p>
              下一期将按 <b data-test="subscription-pending-price">{{ money(row.pendingPriceAmount) }}</b> 扣款
              （上次扣款 {{ money(row.lastChargedAmount) }}）。
              确认后才会继续扣款；若不确认，订阅将在期限后终止。
            </p>
            <p v-if="row.priceChangeDetectedAt" class="hint">
              调整通知发出于 {{ formatDateTime(row.priceChangeDetectedAt) }}
            </p>
          </div>
        </div>

        <p v-if="row.consecutiveFailures > 0" class="hint hint--warn" data-test="subscription-failures">
          <AlertCircle :size="14" />
          已有 {{ row.consecutiveFailures }} 次续费未完成，请检查余额。
          <template v-if="row.graceUntil">宽限期至 {{ formatDateTime(row.graceUntil) }}。</template>
        </p>

        <div class="actions">
          <button class="btn btn--outline" :disabled="busyId === row.id" @click="openDetail(row)">
            查看扣款记录
          </button>
          <button
            v-if="row.pendingPriceAmount !== null"
            class="btn btn--primary"
            data-test="subscription-confirm-price"
            :disabled="busyId === row.id"
            @click="confirmPrice(row)"
          >
            <ShieldCheck :size="14" /> 确认新价格
          </button>
          <button
            v-if="row.autoRenew && !row.cancelAtPeriodEnd"
            class="btn btn--danger"
            data-test="subscription-cancel"
            :disabled="busyId === row.id"
            @click="confirmCancel(row)"
          >
            <XCircle :size="14" /> 关闭自动续费
          </button>
          <button
            v-else-if="row.cancelAtPeriodEnd"
            class="btn btn--primary"
            data-test="subscription-resume"
            :disabled="busyId === row.id"
            @click="restore(row)"
          >
            恢复自动续费
          </button>
        </div>
      </article>
    </div>

    <section v-if="detail" class="detail" data-test="subscription-detail">
      <header class="detail-head">
        <h2>扣款记录 · {{ detail.subscription.planName || '订阅套餐' }}</h2>
        <button class="btn btn--outline" @click="detail = null">关闭</button>
      </header>
      <table>
        <thead>
          <tr><th>期数</th><th>周期</th><th>状态</th><th>金额</th><th>订单号</th><th>失败原因</th></tr>
        </thead>
        <tbody>
          <tr v-for="cycle in detail.cycles" :key="cycle.id">
            <td>第 {{ cycle.periodIndex }} 期</td>
            <td>{{ formatDateTime(cycle.periodStart) }} — {{ formatDateTime(cycle.periodEnd) }}</td>
            <td>
              <span :class="cycleClass(cycle.status)">
                {{ cycleStatusLabels[cycle.status] || cycle.status }}
              </span>
            </td>
            <td>{{ money(cycle.amount, cycle.currency) }}</td>
            <td>{{ cycle.orderNo || '--' }}</td>
            <td>{{ cycle.failureReason || '--' }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </section>
</template>

<style scoped>
.page { padding: 24px; }
.head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 20px; }
.head h1 { font-size: 20px; margin: 0 0 6px; }
.sub { color: #6b7280; font-size: 13px; margin: 0; }
.notice { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border-radius: 8px; font-size: 13px; margin-bottom: 16px; }
.notice--error { background: #fef2f2; color: #b91c1c; }
/* 待确认的涨价用琥珀色而不是红色：它不是错误，而是一个需要用户回应的状态。 */
.notice--warn { background: #fffbeb; color: #92400e; align-items: flex-start; }
.notice--warn p { margin: 4px 0 0; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 48px 0; color: #6b7280; font-size: 14px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 16px; }
.card { display: flex; flex-direction: column; border: 1px solid #e5e7eb; border-radius: 12px; padding: 16px; background: #fff; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.card-head h2 { font-size: 15px; margin: 0; }
.facts { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 16px; margin: 0 0 12px; }
.facts dt { display: flex; align-items: center; gap: 4px; color: #6b7280; font-size: 12px; }
.facts dd { margin: 2px 0 0; font-size: 13px; }
.hint { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #6b7280; margin: 0 0 10px; }
.hint--warn { color: #b45309; }
.actions { display: flex; gap: 8px; margin-top: auto; padding-top: 16px; }
.tag { display: inline-block; padding: 2px 8px; border-radius: 999px; font-size: 12px; }
.tag--ok { background: #ecfdf5; color: #047857; }
.tag--warn { background: #fffbeb; color: #b45309; }
.tag--danger { background: #fef2f2; color: #b91c1c; }
.tag--muted { background: #f3f4f6; color: #6b7280; }
.btn { display: inline-flex; align-items: center; gap: 4px; border-radius: 8px; padding: 6px 12px; font-size: 13px; cursor: pointer; border: 1px solid transparent; }
.btn:disabled { opacity: .6; cursor: not-allowed; }
.btn--outline { border-color: #d1d5db; background: #fff; color: #374151; }
.btn--primary { background: #2563eb; color: #fff; }
.btn--danger { border-color: #fecaca; background: #fff; color: #b91c1c; }
.detail { margin-top: 24px; border-top: 1px solid #e5e7eb; padding-top: 16px; }
.detail-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.detail-head h2 { font-size: 15px; margin: 0; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f3f4f6; }
th { color: #6b7280; font-weight: 500; }
</style>
