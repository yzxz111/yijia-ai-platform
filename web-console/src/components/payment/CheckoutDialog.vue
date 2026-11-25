<script setup lang="ts">
import { isAxiosError } from 'axios'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Clock3, CreditCard, RefreshCw, XCircle } from 'lucide-vue-next'
import { paymentProductForDevice } from '../../utils/paymentProduct'

import {
  closeOrder,
  createOrder,
  createPaymentAttempt,
  getProfile,
  getOrderDetail,
  isSecondDimension,
  quotaUnitLabel,
  type CreatedOrder,
  type OrderDetail,
  type PackagePlan,
  type PaymentAction,
  type PaymentAttemptStatus,
  type PaymentProduct,
  type PaymentProvider,
} from '../../api/platform'
import { payOrderWithBalance } from '../../api/wallet'
import { useWalletStore } from '../../stores/wallet'
import { userPlanName } from '../../utils/planCopy'
import SmsVerificationDialog from '../sms/SmsVerificationDialog.vue'
import PaymentActionPane from './PaymentActionPane.vue'

const props = defineProps<{ modelValue: boolean; plan: PackagePlan }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  paid: [detail: OrderDetail]
}>()

type ProviderChoice = 'WECHAT' | 'ALIPAY' | 'BALANCE'

const wallet = useWalletStore()

const order = ref<CreatedOrder | null>(null)
const detail = ref<OrderDetail | null>(null)
const action = ref<PaymentAction | null>(null)
const localAttemptStatus = ref<PaymentAttemptStatus | null>(null)
const providerChoice = ref<ProviderChoice>('WECHAT')
const creatingOrder = ref(false)
const creatingAttempt = ref(false)
const closingOrder = ref(false)
const errorMessage = ref('')
const closeConfirmationPending = ref(false)
const paymentVerificationOpen = ref(false)
const phoneBound = ref(false)
const smsRequirementKnown = ref(false)
const remainingSeconds = ref(0)
let idempotencyKey = ''
let balancePaymentKey = ''
let countdownTimer: ReturnType<typeof setInterval> | null = null
let pollingTimer: ReturnType<typeof setInterval> | null = null
let pollingBackoff: ReturnType<typeof setTimeout> | null = null
let polling = false
let pollingGeneration = 0
let operationGeneration = 0
let smsRequirementGeneration = 0
let paidNotifiedOrderNo = ''
let paymentVerificationSnapshot: { generation: number; plan: string; orderNo: string } | null = null

const number = new Intl.NumberFormat('zh-CN')

const displayedName = computed(() => userPlanName(
  order.value?.packageSnapshot.packageCode || props.plan.code,
  order.value?.packageSnapshot.packageName || props.plan.name,
))
const displayedTokens = computed(() => order.value?.packageSnapshot.totalTokenQuota ?? props.plan.totalTokenQuota)
// 视频套餐的额度是秒数，而面向客户的控制台不展示该
// 数字：卡片与对比表只说明套餐如何计费。结算
// 摘要口径一致，因此收银台对话框不该成为买家
// 第一次得知秒数的地方。
const displayedDimension = computed(() => order.value?.packageSnapshot.usageDimension ?? props.plan.usageDimension)
const displayedQuotaIsSeconds = computed(() => isSecondDimension(displayedDimension.value))
const displayedQuotaLabel = computed(() => (displayedQuotaIsSeconds.value
  ? '计费方式'
  : `${quotaUnitLabel(displayedDimension.value)}额度`))
const displayedQuotaValue = computed(() => (displayedQuotaIsSeconds.value
  ? '按秒计费'
  : number.format(displayedTokens.value)))
const displayedDays = computed(() => order.value?.packageSnapshot.durationDays ?? props.plan.durationDays)
const displayedAmount = computed(() => order.value?.amount ?? order.value?.packageSnapshot.priceAmount ?? props.plan.priceAmount)
const displayedCurrency = computed(() => order.value?.currency || props.plan.currency || 'CNY')
const formattedAmount = computed(() => formatMoney(displayedAmount.value, displayedCurrency.value))
const walletAvailable = computed(() => wallet.summary?.availableAmount ?? null)
const walletCanPay = computed(() => walletAvailable.value !== null && walletAvailable.value >= displayedAmount.value)

function formatMoney(minorUnits: number, currency = 'CNY') {
  const units = BigInt(minorUnits)
  const absolute = units < 0n ? -units : units
  const whole = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 }).format(absolute / 100n)
  const fraction = (absolute % 100n).toString().padStart(2, '0')
  const symbol = currency === 'CNY' ? '¥' : `${currency} `
  return `${units < 0n ? '-' : ''}${symbol}${whole}.${fraction}`
}

const countdown = computed(() => {
  const minutes = Math.floor(remainingSeconds.value / 60)
  const seconds = remainingSeconds.value % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
})

const latestAttempt = computed(() => detail.value?.paymentAttempts.at(-1) || null)
const latestAttemptStatus = computed(() => latestAttempt.value?.status || localAttemptStatus.value)
const businessStatus = computed(() => detail.value?.order.status || order.value?.status || '')
const paymentSucceeded = computed(() => businessStatus.value === 'PAID')
const businessClosed = computed(() => businessStatus.value === 'CLOSED')
const grantStatus = computed(() => detail.value?.grant?.status || null)
const grantStatusLabel = computed(() => {
  if (grantStatus.value === 'ACTIVE') return '套餐已生效'
  if (grantStatus.value === 'QUEUED') return '套餐排队中'
  if (grantStatus.value === 'FAILED') return '套餐发放失败'
  if (grantStatus.value === 'REVOKED') return '套餐已撤销'
  return '授权状态同步中'
})
const canRetry = computed(() => !businessClosed.value && ['FAILED', 'CLOSED'].includes(latestAttemptStatus.value || ''))
const canStartPayment = computed(() => Boolean(order.value) && !latestAttemptStatus.value && !businessClosed.value && !paymentSucceeded.value)

function newIdempotencyKey() {
  const random = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `checkout-${random}`
}

function clearCountdown() {
  if (countdownTimer) clearInterval(countdownTimer)
  countdownTimer = null
}

function updateCountdown(expiresAt: string) {
  remainingSeconds.value = Math.max(0, Math.ceil((new Date(expiresAt).getTime() - Date.now()) / 1000))
  if (remainingSeconds.value === 0) clearCountdown()
}

function startCountdown(expiresAt: string) {
  clearCountdown()
  updateCountdown(expiresAt)
  if (remainingSeconds.value === 0) return
  countdownTimer = setInterval(() => updateCountdown(expiresAt), 1000)
}

function clearPolling() {
  pollingGeneration += 1
  if (pollingTimer) clearInterval(pollingTimer)
  if (pollingBackoff) clearTimeout(pollingBackoff)
  pollingTimer = null
  pollingBackoff = null
  polling = false
}

function invalidateOperations() {
  operationGeneration += 1
  creatingOrder.value = false
  creatingAttempt.value = false
  closingOrder.value = false
}

function planIdentity() {
  return `${props.plan.code}:${props.plan.versionId}`
}

function isCurrentOperation(generation: number, expectedPlan: string, expectedOrderNo?: string) {
  return generation === operationGeneration
    && props.modelValue
    && planIdentity() === expectedPlan
    && (!expectedOrderNo || order.value?.orderNo === expectedOrderNo)
}

function isTerminal(status: string) {
  return status === 'PAID' || status === 'CLOSED'
}

function emitPaidOnce(nextDetail: OrderDetail) {
  if (nextDetail.order.status !== 'PAID' || nextDetail.grant?.status === 'QUEUED' || paidNotifiedOrderNo === nextDetail.order.orderNo) return
  paidNotifiedOrderNo = nextDetail.order.orderNo
  emit('paid', nextDetail)
}

async function pollOrder() {
  if (!order.value || polling) return
  const generation = pollingGeneration
  polling = true
  try {
    const nextDetail = await getOrderDetail(order.value.orderNo)
    if (generation !== pollingGeneration) return
    detail.value = nextDetail
    errorMessage.value = ''
    localAttemptStatus.value = nextDetail.paymentAttempts.at(-1)?.status || localAttemptStatus.value
    const waitingForGrant = nextDetail.order.status === 'PAID' && nextDetail.grant?.status === 'QUEUED'
    if (isTerminal(nextDetail.order.status) && !waitingForGrant) {
      closeConfirmationPending.value = false
      clearPolling()
      clearCountdown()
      emitPaidOnce(nextDetail)
    }
  } catch {
    if (generation !== pollingGeneration) return
    if (pollingTimer) clearInterval(pollingTimer)
    pollingTimer = null
    errorMessage.value = '订单状态暂时无法更新，将稍后重试。'
    pollingBackoff = setTimeout(() => {
      pollingBackoff = null
      if (generation === pollingGeneration) {
        void pollOrder()
        startPolling()
      }
    }, 5000)
  } finally {
    if (generation === pollingGeneration) polling = false
  }
}

function startPolling(force = false) {
  const waitingForGrant = businessStatus.value === 'PAID' && grantStatus.value === 'QUEUED'
  if (!order.value || (!force && isTerminal(businessStatus.value) && !waitingForGrant)) return
  if (pollingTimer) clearInterval(pollingTimer)
  pollingTimer = setInterval(() => void pollOrder(), 2000)
}

// Only public order codes may reach the customer; never render an arbitrary server error.
const orderErrorMessages: Record<string, string> = {
  package_quota_remaining: '当前套餐仍有剩余额度或正在排队，暂时无法重复购买。',
  package_purchase_limit_reached: '该套餐已达到购买次数上限。',
  package_sold_out: '该套餐已售罄，请选择其他套餐。',
  published_package_version_not_found: '套餐版本已下架或不存在，请刷新页面后重试。',
  package_version_not_found: '套餐版本已下架或不存在，请刷新页面后重试。',
  paid_package_required: '请选择已发布的付费套餐。',
  order_idempotency_payload_mismatch: '订单信息已发生变化，请关闭结算窗口后重试。',
  invalid_order_request: '订单信息无效，请刷新页面后重试。',
  invalid_idempotency_key: '订单请求已失效，请关闭结算窗口后重试。',
}

async function createBusinessOrder(fresh = false) {
  if (!props.plan.versionId) return
  invalidateOperations()
  const generation = operationGeneration
  const expectedPlan = planIdentity()
  const packageVersionId = props.plan.versionId
  if (fresh) idempotencyKey = newIdempotencyKey()
  idempotencyKey ||= newIdempotencyKey()
  creatingOrder.value = true
  errorMessage.value = ''
  try {
    const created = await createOrder(packageVersionId, idempotencyKey)
    if (!isCurrentOperation(generation, expectedPlan)) return
    order.value = created
    detail.value = null
    action.value = null
    localAttemptStatus.value = null
    paidNotifiedOrderNo = ''
    closeConfirmationPending.value = false
    balancePaymentKey = newBalancePaymentKey()
    startCountdown(created.expiresAt)
  } catch (error) {
    if (!isCurrentOperation(generation, expectedPlan)) return
    const code = isAxiosError<{ code?: string }>(error) ? error.response?.data?.code : undefined
    errorMessage.value = (code && Object.hasOwn(orderErrorMessages, code) ? orderErrorMessages[code] : undefined)
      || '订单创建失败，请稍后重试。'
  } finally {
    if (isCurrentOperation(generation, expectedPlan)) creatingOrder.value = false
  }
}

function paymentSelection(): { provider: PaymentProvider; product: PaymentProduct } {
  const provider = providerChoice.value === 'WECHAT' ? 'WECHAT_PAY' : 'ALIPAY'
  return { provider, product: paymentProductForDevice(provider) }
}

async function createBalancePayment() {
  if (!order.value || creatingAttempt.value || !walletCanPay.value) return
  invalidateOperations()
  const generation = operationGeneration
  const walletGeneration = wallet.userScopeGeneration
  const expectedPlan = planIdentity()
  const orderNo = order.value.orderNo
  balancePaymentKey ||= newBalancePaymentKey()
  creatingAttempt.value = true
  errorMessage.value = ''
  try {
    let result
    try {
      result = await payOrderWithBalance(orderNo, balancePaymentKey)
    } catch {
      if (!isCurrentOperation(generation, expectedPlan, orderNo) || walletGeneration !== wallet.userScopeGeneration) return
      errorMessage.value = '余额支付失败，请确认余额和订单状态后重试。'
      await wallet.loadSummary().catch(() => undefined)
      return
    }
    if (!isCurrentOperation(generation, expectedPlan, orderNo) || walletGeneration !== wallet.userScopeGeneration) return
    order.value = { ...order.value, status: result.orderStatus as CreatedOrder['status'] }
    closeConfirmationPending.value = false
    clearCountdown()

    const [detailRefresh, walletRefresh] = await Promise.allSettled([
      getOrderDetail(orderNo),
      wallet.loadSummary(),
    ])
    if (!isCurrentOperation(generation, expectedPlan, orderNo) || walletGeneration !== wallet.userScopeGeneration) return
    const refreshMessages: string[] = []
    if (detailRefresh.status === 'fulfilled') {
      detail.value = detailRefresh.value
      emitPaidOnce(detailRefresh.value)
    } else {
      refreshMessages.push('订单详情暂未同步，系统将继续更新。')
    }
    if (walletRefresh.status === 'rejected') refreshMessages.push('余额暂未刷新，请稍后查看。')
    errorMessage.value = refreshMessages.join(' ')
    startPolling(detailRefresh.status === 'rejected')
  } finally {
    if (isCurrentOperation(generation, expectedPlan, orderNo)) creatingAttempt.value = false
  }
}

async function createPayment(smsProof?: string) {
  if (!order.value || creatingAttempt.value) return
  invalidateOperations()
  const generation = operationGeneration
  const expectedPlan = planIdentity()
  const orderNo = order.value.orderNo
  creatingAttempt.value = true
  errorMessage.value = ''
  try {
    const selection = paymentSelection()
    const attempt = smsProof
      ? await createPaymentAttempt(orderNo, selection.provider, selection.product, smsProof)
      : await createPaymentAttempt(orderNo, selection.provider, selection.product)
    if (!isCurrentOperation(generation, expectedPlan, orderNo)) return
    closeConfirmationPending.value = false
    action.value = attempt.action
    localAttemptStatus.value = attempt.status
    startCountdown(attempt.expiresAt)
    detail.value = detail.value
      ? { ...detail.value, paymentAttempts: [...detail.value.paymentAttempts, {
          transactionNo: attempt.transactionNo,
          provider: selection.provider,
          product: selection.product,
          status: attempt.status,
          providerStatus: null,
          providerErrorCode: null,
          action: attempt.action,
          paidAt: null,
          createdAt: new Date().toISOString(),
        }] }
      : null
    startPolling()
  } catch {
    if (!isCurrentOperation(generation, expectedPlan, orderNo)) return
    errorMessage.value = '支付请求创建失败，请稍后重试。'
  } finally {
    if (isCurrentOperation(generation, expectedPlan, orderNo)) creatingAttempt.value = false
  }
}

function newBalancePaymentKey() {
  const random = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `balance-checkout-${random}`
}

function beginPayment() {
  if (!order.value || creatingAttempt.value) return
  if (providerChoice.value === 'BALANCE') {
    void createBalancePayment()
    return
  }
  if (!smsRequirementKnown.value) return
  if (phoneBound.value) {
    paymentVerificationSnapshot = {
      generation: operationGeneration,
      plan: planIdentity(),
      orderNo: order.value.orderNo,
    }
    paymentVerificationOpen.value = true
    return
  }
  void createPayment()
}

function completePaymentVerification(proof: string) {
  const snapshot = paymentVerificationSnapshot
  paymentVerificationSnapshot = null
  paymentVerificationOpen.value = false
  if (!snapshot || !isCurrentOperation(snapshot.generation, snapshot.plan, snapshot.orderNo)) return
  void createPayment(proof)
}

async function loadSmsRequirement() {
  const generation = ++smsRequirementGeneration
  const expectedPlan = planIdentity()
  smsRequirementKnown.value = false
  try {
    const profile = await getProfile()
    if (generation !== smsRequirementGeneration || !props.modelValue || planIdentity() !== expectedPlan) return
    phoneBound.value = Boolean(profile?.phoneVerified)
    smsRequirementKnown.value = true
  } catch {
    if (generation !== smsRequirementGeneration || !props.modelValue || planIdentity() !== expectedPlan) return
    errorMessage.value = '账户安全状态加载失败，请关闭结算窗口后重试。'
  }
}

async function closeBusinessOrder() {
  if (!order.value) return
  invalidateOperations()
  const generation = operationGeneration
  const expectedPlan = planIdentity()
  const orderNo = order.value.orderNo
  closingOrder.value = true
  errorMessage.value = ''
  try {
    const result = await closeOrder(orderNo)
    if (!isCurrentOperation(generation, expectedPlan, orderNo)) return
    localAttemptStatus.value = result.paymentStatus
    order.value = { ...order.value, status: result.orderStatus }
    if (detail.value) {
      const paymentAttempts = [...detail.value.paymentAttempts]
      if (result.paymentStatus && paymentAttempts.length > 0) {
        paymentAttempts[paymentAttempts.length - 1] = {
          ...paymentAttempts[paymentAttempts.length - 1], status: result.paymentStatus,
        }
      }
      detail.value = {
        ...detail.value,
        order: {
          ...detail.value.order,
          status: result.orderStatus,
          paymentStatus: result.paymentStatus,
        },
        paymentAttempts,
      }
    }
    closeConfirmationPending.value = result.orderStatus === 'PENDING' && result.retryScheduled
    if (isTerminal(result.orderStatus)) {
      clearPolling()
      clearCountdown()
      if (result.orderStatus === 'PAID') await pollOrder()
    } else {
      startPolling()
    }
  } catch {
    if (!isCurrentOperation(generation, expectedPlan, orderNo)) return
    errorMessage.value = '订单关闭失败，请稍后重试。'
  } finally {
    if (isCurrentOperation(generation, expectedPlan, orderNo)) closingOrder.value = false
  }
}

async function createNewOrder() {
  invalidateOperations()
  clearPolling()
  clearCountdown()
  order.value = null
  detail.value = null
  action.value = null
  localAttemptStatus.value = null
  paidNotifiedOrderNo = ''
  closeConfirmationPending.value = false
  await createBusinessOrder(true)
}

function reset() {
  invalidateOperations()
  smsRequirementGeneration += 1
  clearPolling()
  clearCountdown()
  order.value = null
  detail.value = null
  action.value = null
  localAttemptStatus.value = null
  closeConfirmationPending.value = false
  paymentVerificationSnapshot = null
  paymentVerificationOpen.value = false
  phoneBound.value = false
  smsRequirementKnown.value = false
  errorMessage.value = ''
  providerChoice.value = 'WECHAT'
  remainingSeconds.value = 0
  idempotencyKey = newIdempotencyKey()
  balancePaymentKey = ''
  paidNotifiedOrderNo = ''
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      reset()
      void loadSmsRequirement()
      void wallet.loadSummary().catch(() => undefined)
    }
    else {
      invalidateOperations()
      smsRequirementGeneration += 1
      clearPolling()
      clearCountdown()
      paymentVerificationSnapshot = null
      paymentVerificationOpen.value = false
      phoneBound.value = false
      smsRequirementKnown.value = false
    }
  },
  { immediate: true },
)

watch(() => [props.plan.code, props.plan.versionId], () => {
  if (props.modelValue) {
    reset()
    void loadSmsRequirement()
  }
})

onBeforeUnmount(() => {
  invalidateOperations()
  smsRequirementGeneration += 1
  clearPolling()
  clearCountdown()
  paymentVerificationSnapshot = null
  paymentVerificationOpen.value = false
})
</script>

<template>
  <SmsVerificationDialog
    v-model="paymentVerificationOpen"
    purpose="PAYMENT_CONFIRM"
    @verified="completePaymentVerification"
  />
  <el-dialog
    :model-value="modelValue"
    class="checkout-dialog"
    title="套餐结算"
    width="min(620px, calc(100vw - 24px))"
    :teleported="false"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="checkout-body">
      <section class="summary" data-test="checkout-summary" aria-label="订单套餐快照">
        <div class="summary-title"><CreditCard :size="18" aria-hidden="true" /><strong>{{ displayedName }}</strong></div>
        <dl>
          <div><dt>{{ displayedQuotaLabel }}</dt><dd>{{ displayedQuotaValue }}</dd></div>
          <div><dt>有效期</dt><dd>{{ displayedDays }} 天</dd></div>
          <div><dt>应付金额</dt><dd>{{ formattedAmount }}</dd></div>
        </dl>
      </section>

      <TopErrorMessage :message="errorMessage" />
      <el-alert
        v-if="closeConfirmationPending"
        data-test="close-confirmation-pending"
        title="支付结果确认中，请勿重复下单"
        type="warning"
        :closable="false"
        show-icon
      />

      <div v-if="order" class="order-strip">
        <div><span>订单号</span><code>{{ order.orderNo }}</code></div>
        <div class="countdown-wrap"><Clock3 :size="15" aria-hidden="true" /><span>支付剩余</span><strong data-test="payment-countdown">{{ countdown }}</strong></div>
      </div>

      <template v-if="!order">
        <el-button
          type="primary"
          class="primary-action"
          data-test="checkout-create-order"
          :loading="creatingOrder"
          :disabled="!plan.versionId"
          @click="createBusinessOrder()"
        >确认订单并选择支付</el-button>
      </template>

      <template v-else-if="paymentSucceeded">
        <el-result icon="success" title="支付成功" sub-title="套餐授权由系统自动处理，无需创建新的 API 密钥。">
          <template #extra>
            <el-tag
              data-test="checkout-grant-status"
              :data-status="grantStatus || undefined"
              :type="grantStatus === 'ACTIVE' ? 'success' : 'warning'"
            >{{ grantStatusLabel }}</el-tag>
          </template>
        </el-result>
      </template>

      <template v-else-if="businessClosed">
        <el-result icon="info" title="订单已关闭" sub-title="原订单不可继续支付，请创建新订单。" />
        <el-button type="primary" class="primary-action" data-test="create-new-order" :loading="creatingOrder" @click="createNewOrder">
          <RefreshCw :size="16" aria-hidden="true" />创建新订单
        </el-button>
      </template>

      <template v-else>
        <div class="provider-control">
          <label id="payment-provider-label">支付方式</label>
          <el-segmented
            v-model="providerChoice"
            :options="[{ label: '微信支付', value: 'WECHAT' }, { label: '支付宝', value: 'ALIPAY' }, { label: '余额支付', value: 'BALANCE' }]"
            aria-labelledby="payment-provider-label"
          />
        </div>

        <div v-if="providerChoice === 'BALANCE'" class="wallet-payment" data-test="wallet-payment-summary">
          <div><span>可用余额</span><strong data-test="wallet-available">{{ walletAvailable === null ? '加载中' : formatMoney(walletAvailable) }}</strong></div>
          <p v-if="walletAvailable !== null && !walletCanPay" role="alert">余额不足，请先充值后再支付。</p>
          <a v-if="walletAvailable !== null && !walletCanPay" data-test="wallet-recharge-link" href="/app/wallet">前往余额钱包</a>
        </div>

        <PaymentActionPane v-if="action" :action="action" />

        <el-button
          v-if="canStartPayment"
          type="primary"
          class="primary-action"
          :data-test="providerChoice === 'BALANCE' ? 'start-balance-payment' : 'start-payment'"
          :loading="creatingAttempt"
          :disabled="remainingSeconds === 0 || (providerChoice !== 'BALANCE' && !smsRequirementKnown) || (providerChoice === 'BALANCE' && !walletCanPay)"
          @click="beginPayment"
        >发起支付</el-button>
        <el-button
          v-else-if="canRetry"
          type="primary"
          class="primary-action"
          data-test="retry-payment"
          :loading="creatingAttempt"
          :disabled="!smsRequirementKnown"
          @click="beginPayment"
        ><RefreshCw :size="16" aria-hidden="true" />重试支付</el-button>

        <el-button class="secondary-action" data-test="close-order" :loading="closingOrder" @click="closeBusinessOrder">
          <XCircle :size="16" aria-hidden="true" />关闭订单
        </el-button>
      </template>
    </div>
  </el-dialog>
</template>

<style scoped>
.checkout-body { display: grid; gap: 16px; }
.summary { padding: 16px; background: var(--app-bg); border: 1px solid var(--app-border); border-radius: 8px; }
.summary-title { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; font-size: 16px; }
.summary dl { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin: 0; }
.summary dl div { min-width: 0; }
.summary dt { margin-bottom: 3px; color: var(--app-muted); font-size: 12px; }
.summary dd { margin: 0; overflow-wrap: anywhere; font-weight: 700; font-variant-numeric: tabular-nums; }
.order-strip { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 11px 12px; border: 1px solid var(--app-border); border-radius: 8px; }
.order-strip span { color: var(--app-muted); font-size: 12px; }
.order-strip code { display: block; margin-top: 2px; overflow-wrap: anywhere; }
.countdown-wrap { display: grid; grid-template-columns: auto auto; align-items: center; justify-items: end; gap: 1px 6px; }
.countdown-wrap strong { grid-column: 1 / -1; min-width: 5ch; font-size: 18px; font-variant-numeric: tabular-nums; text-align: right; }
.provider-control { display: grid; gap: 8px; }
.provider-control label { font-size: 13px; font-weight: 650; }
.wallet-payment { display: flex; min-height: 64px; align-items: center; justify-content: space-between; gap: 12px; padding: 12px; border: 1px solid var(--app-border); border-radius: 8px; }
.wallet-payment div { display: grid; gap: 3px; }
.wallet-payment span, .wallet-payment p { color: var(--app-muted); font-size: 12px; }
.wallet-payment strong { font-variant-numeric: tabular-nums; }
.wallet-payment p { margin: 0; color: var(--el-color-danger); }
.wallet-payment a { min-height: 44px; display: inline-flex; align-items: center; color: var(--app-primary); font-weight: 650; }
.primary-action, .secondary-action { width: 100%; min-height: 44px; }
:global(.checkout-dialog.el-dialog) {
  display: flex;
  max-height: calc(100dvh - 24px);
  margin: 12px auto !important;
  flex-direction: column;
}
:global(.checkout-dialog .el-dialog__header) { flex: 0 0 auto; }
:global(.checkout-dialog .el-dialog__body) {
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}
@media (max-width: 520px) {
  .summary dl { grid-template-columns: minmax(0, 1fr); }
  .order-strip { align-items: flex-start; flex-direction: column; }
  .countdown-wrap { width: 100%; grid-template-columns: auto 1fr auto; justify-items: start; }
  .countdown-wrap strong { grid-column: auto; justify-self: end; }
}
</style>
