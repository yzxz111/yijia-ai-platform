<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { CreditCard, RefreshCw, Smartphone } from 'lucide-vue-next'

import {
  createRecharge,
  createRechargePaymentAttempt,
  getRechargeDetail,
  type RechargeOrder,
} from '../../api/wallet'
import { getProfile, type PaymentAction, type PaymentProduct, type PaymentProvider } from '../../api/platform'
import { readApiErrorCode, readApiErrorMessage } from '../../api/apiError'
import { useWalletStore } from '../../stores/wallet'
import { paymentProductForDevice } from '../../utils/paymentProduct'
import SmsVerificationDialog from '../sms/SmsVerificationDialog.vue'
import PaymentActionPane from './PaymentActionPane.vue'

const props = withDefaults(defineProps<{
  modelValue: boolean
  existingOrder?: RechargeOrder | null
}>(), {
  existingOrder: null,
})
type WalletRefreshFailure = 'summary' | 'recharges' | 'ledger'

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  completed: [order: RechargeOrder, refreshFailures: WalletRefreshFailure[]]
}>()

const wallet = useWalletStore()
const selectedOptionId = ref('')
const customSelected = ref(false)
const customAmount = ref('')
const provider = ref<PaymentProvider | ''>('')
const amountError = ref('')
const submitError = ref('')
const loadingOptionsError = ref('')
const submitting = ref(false)
const paymentVerificationOpen = ref(false)
let paymentVerificationSnapshot: RechargeSubmission | null = null
const checkingPayment = ref(false)
const paymentAction = ref<PaymentAction | null>(null)
const currentOrder = ref<RechargeOrder | null>(null)
const idempotencyKey = ref<string | null>(null)
const customInput = ref<{ focus?: () => void } | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null
let dialogGeneration = 0
let completedRechargeNo: string | null = null

interface DialogOperation {
  dialogGeneration: number
  userScopeGeneration: number
}

interface RechargeSubmission {
  operation: DialogOperation
  request: { amount: number; optionId?: string } | null
  order: RechargeOrder | null
  idempotencyKey: string
  provider: PaymentProvider
  product: PaymentProduct
}

function operationContext(): DialogOperation {
  return {
    dialogGeneration,
    userScopeGeneration: wallet.userScopeGeneration,
  }
}

function isOperationCurrent(operation: DialogOperation) {
  return props.modelValue
    && operation.dialogGeneration === dialogGeneration
    && operation.userScopeGeneration === wallet.userScopeGeneration
}

const selectedOption = computed(() => wallet.rechargeOptions?.options.find(
  (option) => option.id === selectedOptionId.value,
))
const resuming = computed(() => Boolean(props.existingOrder))

const canSubmit = computed(() => {
  if (submitting.value || paymentVerificationOpen.value) return false
  if (currentOrder.value) return Boolean(provider.value) && currentOrder.value.status === 'PENDING'
  if (resuming.value) return Boolean(provider.value) && !submitting.value
  const hasAmount = customSelected.value ? customAmount.value.trim().length > 0 : Boolean(selectedOption.value)
  return hasAmount && Boolean(provider.value) && !submitting.value
})

function newIdempotencyKey() {
  const random = globalThis.crypto?.randomUUID?.()
    ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`
  return `recharge-${random}`
}

function selectFixedOption(optionId: string) {
  selectedOptionId.value = optionId
  customSelected.value = false
  customAmount.value = ''
  amountError.value = ''
  idempotencyKey.value = null
}

function selectCustom() {
  selectedOptionId.value = ''
  customSelected.value = true
  amountError.value = ''
  idempotencyKey.value = null
  void nextTick(() => customInput.value?.focus?.())
}

function decimalYuanToMinorUnits(value: string): number | null {
  const match = /^(0|[1-9]\d*)(?:\.(\d{1,2}))?$/.exec(value)
  if (!match) return null
  const whole = BigInt(match[1])
  const fraction = BigInt((match[2] ?? '').padEnd(2, '0') || '0')
  const minorUnits = whole * 100n + fraction
  if (minorUnits > BigInt(Number.MAX_SAFE_INTEGER)) return null
  return Number(minorUnits)
}

function selectedAmount(): { amount: number; optionId?: string } | null {
  amountError.value = ''
  if (!customSelected.value && selectedOption.value) {
    return { amount: selectedOption.value.amount, optionId: selectedOption.value.id }
  }

  const raw = customAmount.value.trim()
  const amount = decimalYuanToMinorUnits(raw)
  const limits = wallet.rechargeOptions?.custom
  if (amount === null || !limits || amount < limits.minimumAmount || amount > limits.maximumAmount) {
    const minimum = limits ? formatMoney(limits.minimumAmount) : '¥1.00'
    const maximum = limits ? formatMoney(limits.maximumAmount) : '¥50,000.00'
    amountError.value = `请输入 ${minimum} 至 ${maximum}，最多保留两位小数。`
    void nextTick(() => customInput.value?.focus?.())
    return null
  }
  return { amount }
}

function formatMoney(minorUnits: number) {
  const units = BigInt(minorUnits)
  const absolute = units < 0n ? -units : units
  const whole = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 }).format(absolute / 100n)
  const fraction = (absolute % 100n).toString().padStart(2, '0')
  return `${units < 0n ? '-' : ''}¥${whole}.${fraction}`
}

async function loadOptions() {
  const operation = operationContext()
  loadingOptionsError.value = ''
  try {
    await wallet.loadRechargeOptions()
    if (!isOperationCurrent(operation)) return
  } catch {
    if (!isOperationCurrent(operation)) return
    loadingOptionsError.value = '充值金额加载失败，请稍后重试。'
  }
}

async function submitRecharge() {
  if (submitting.value || paymentVerificationOpen.value || !provider.value) return
  const existingOrder = currentOrder.value ?? props.existingOrder
  const request = existingOrder ? null : selectedAmount()
  if (!existingOrder && !request) return

  submitting.value = true
  submitError.value = ''
  idempotencyKey.value ||= newIdempotencyKey()
  const operation = operationContext()
  const submission: RechargeSubmission = {
    operation, request, order: existingOrder, idempotencyKey: idempotencyKey.value,
    provider: provider.value, product: paymentProductForDevice(provider.value),
  }
  try {
    const profile = await getProfile()
    if (!isOperationCurrent(operation)) return
    if (profile.phoneVerified) {
      paymentVerificationSnapshot = submission
      paymentVerificationOpen.value = true
    } else {
      await createRechargePayment(submission)
    }
  } catch {
    if (!isOperationCurrent(operation)) return
    submitError.value = '账户安全状态加载失败，请稍后重试。'
  } finally {
    if (isOperationCurrent(operation)) submitting.value = false
  }
}

function completePaymentVerification(proof: string) {
  const submission = paymentVerificationSnapshot
  paymentVerificationSnapshot = null
  paymentVerificationOpen.value = false
  if (!submission || !proof || !isOperationCurrent(submission.operation)) return
  void createRechargePayment(submission, proof)
}

async function createRechargePayment(submission: RechargeSubmission, smsProof?: string) {
  const { operation } = submission
  if (!isOperationCurrent(operation)) return
  submitting.value = true
  let stage = submission.order ? 'payment' : 'order'
  try {
    const order = submission.order ?? await createRecharge(submission.request!, submission.idempotencyKey)
    if (!isOperationCurrent(operation)) return
    currentOrder.value = order
    stage = 'payment'
    const attempt = await createRechargePaymentAttempt(order.rechargeNo, {
      provider: submission.provider,
      product: submission.product,
      ...(smsProof ? { smsProof } : {}),
    })
    if (!isOperationCurrent(operation)) return
    if (attempt.status === 'FAILED') {
      submitError.value = '支付渠道未能创建支付请求，请稍后重试或更换支付方式。'
      return
    }
    paymentAction.value = attempt.action
    schedulePaymentCheck(operation)
  } catch (error) {
    if (!isOperationCurrent(operation)) return
    const messages: Record<string, string> = {
      invalid_sms_proof: '支付验证已失效，请重新完成短信验证后重试。',
      active_payment_attempt_exists: '该充值单已有支付请求，请在充值记录中查看支付状态。',
      recharge_order_not_payable: '该充值单已关闭或过期，请重新充值。',
      payment_checkout_unavailable: '该支付方式暂不可用，请更换支付方式或稍后重试。',
    }
    submitError.value = messages[readApiErrorCode(error) ?? '']
      ?? readApiErrorMessage(error)
      ?? (stage === 'order' ? '充值订单创建失败，请稍后重试。' : '充值订单已创建，但发起支付失败，请重试或更换支付方式。')
  } finally {
    if (isOperationCurrent(operation)) submitting.value = false
  }
}

function stopPolling() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
}

function schedulePaymentCheck(operation = operationContext()) {
  stopPolling()
  pollTimer = setTimeout(() => {
    if (isOperationCurrent(operation)) void refreshPaymentStatus()
  }, 2_000)
}

async function refreshPaymentStatus() {
  if (!currentOrder.value || checkingPayment.value) return
  stopPolling()
  const operation = operationContext()
  const rechargeNo = currentOrder.value.rechargeNo
  checkingPayment.value = true
  submitError.value = ''
  try {
    const order = await getRechargeDetail(rechargeNo)
    if (!isOperationCurrent(operation)) return
    currentOrder.value = order
    if (order.status === 'PAID') {
      stopPolling()
      const refreshResults = await Promise.allSettled([
        wallet.loadSummary(),
        wallet.loadRecharges({ page: 0, size: 20, sort: 'createdAt', direction: 'desc' }),
        wallet.loadLedger({ page: 0, size: 20, sort: 'createdAt', direction: 'desc' }),
      ])
      if (!isOperationCurrent(operation)) return
      const resources: WalletRefreshFailure[] = ['summary', 'recharges', 'ledger']
      const refreshFailures = refreshResults.flatMap((result, index) => (
        result.status === 'rejected' || result.value === undefined ? [resources[index]] : []
      ))
      if (refreshFailures.length) {
        const labels: Record<WalletRefreshFailure, string> = {
          summary: '余额',
          recharges: '充值记录',
          ledger: '余额明细',
        }
        submitError.value = `支付已确认，但${refreshFailures.map((resource) => labels[resource]).join('、')}刷新失败，请在钱包页重试。`
      }
      if (completedRechargeNo !== order.rechargeNo) {
        completedRechargeNo = order.rechargeNo
        emit('completed', order, refreshFailures)
      }
    } else if (order.status === 'PENDING') {
      schedulePaymentCheck(operation)
    } else {
      stopPolling()
    }
  } catch {
    if (!isOperationCurrent(operation)) return
    submitError.value = '支付状态查询失败，请稍后重试。'
  } finally {
    if (isOperationCurrent(operation)) checkingPayment.value = false
  }
}

function invalidateOperations() {
  dialogGeneration += 1
  paymentVerificationOpen.value = false
  paymentVerificationSnapshot = null
  stopPolling()
}

function resetDialog() {
  invalidateOperations()
  selectedOptionId.value = ''
  customSelected.value = false
  customAmount.value = ''
  provider.value = ''
  amountError.value = ''
  submitError.value = ''
  paymentAction.value = null
  currentOrder.value = null
  idempotencyKey.value = null
  completedRechargeNo = null
  submitting.value = false
  checkingPayment.value = false
}

function startNewRecharge() {
  if (resuming.value) {
    emit('update:modelValue', false)
    return
  }
  resetDialog()
  void loadOptions()
}

function rechargeStatusLabel(status: RechargeOrder['status']) {
  return ({ PENDING: '等待支付', PAID: '已到账', CLOSED: '已关闭', FAILED: '支付失败' })[status]
}

function rechargeStatusType(status: RechargeOrder['status']) {
  if (status === 'PAID') return 'success'
  if (status === 'FAILED') return 'danger'
  return status === 'CLOSED' ? 'info' : 'warning'
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      currentOrder.value = props.existingOrder
      void loadOptions()
    }
    else resetDialog()
  },
  { immediate: true },
)

watch(
  () => wallet.userScopeGeneration,
  () => {
    if (!props.modelValue) return
    resetDialog()
    void loadOptions()
  },
)

onBeforeUnmount(invalidateOperations)

watch(paymentVerificationOpen, (visible) => {
  if (!visible) paymentVerificationSnapshot = null
})

watch(() => props.existingOrder, () => {
  resetDialog()
  currentOrder.value = props.existingOrder
})
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="resuming ? '继续支付充值单' : '充值余额'"
    width="min(560px, calc(100vw - 24px))"
    :teleported="false"
    :close-on-click-modal="!submitting"
    @update:model-value="emit('update:modelValue', $event)"
    @closed="resetDialog"
  >
    <div class="recharge-flow">
      <TopErrorMessage :message="loadingOptionsError" />
      <el-button v-if="loadingOptionsError" text @click="loadOptions">重新加载</el-button>
      <el-skeleton v-if="wallet.loadingRechargeOptions" :rows="4" animated />

      <template v-else-if="wallet.rechargeOptions">
        <section v-if="currentOrder" class="existing-order-summary" aria-label="充值单信息">
          <div><span>充值单号</span><code>{{ currentOrder.rechargeNo }}</code></div>
          <div><span>支付金额</span><strong>{{ formatMoney(currentOrder.paidAmount) }}</strong></div>
        </section>

        <fieldset v-else class="choice-group" :disabled="submitting || paymentVerificationOpen">
          <legend>充值金额</legend>
          <div class="amount-grid">
            <button
              v-for="option in wallet.rechargeOptions.options"
              :key="option.id"
              type="button"
              class="choice-button amount-button"
              :class="{ selected: selectedOptionId === option.id && !customSelected }"
              :data-test="`recharge-option-${option.id}`"
              :aria-pressed="selectedOptionId === option.id && !customSelected"
              @click="selectFixedOption(option.id)"
            >
              {{ formatMoney(option.amount) }}
            </button>
            <button
              type="button"
              class="choice-button amount-button"
              :class="{ selected: customSelected }"
              data-test="recharge-custom"
              :aria-pressed="customSelected"
              @click="selectCustom"
            >自定义金额</button>
          </div>
          <div v-if="customSelected" class="custom-field">
            <label for="wallet-custom-amount">自定义金额（元）</label>
            <el-input
              id="wallet-custom-amount"
              ref="customInput"
              v-model="customAmount"
              :disabled="submitting || paymentVerificationOpen"
              data-test="custom-amount"
              inputmode="decimal"
              autocomplete="off"
              placeholder="1.00 - 50000.00"
              :aria-invalid="Boolean(amountError)"
              aria-describedby="wallet-amount-help wallet-amount-error"
              @input="amountError = ''; idempotencyKey = null"
            >
              <template #prepend>¥</template>
            </el-input>
            <p id="wallet-amount-help" class="field-help">最多保留两位小数，充值余额不可提现。</p>
            <p v-if="amountError" id="wallet-amount-error" data-test="amount-error" class="field-error" role="alert">{{ amountError }}</p>
          </div>
        </fieldset>

        <fieldset class="choice-group" :disabled="submitting || paymentVerificationOpen || Boolean(paymentAction)">
          <legend>支付方式</legend>
          <div class="provider-grid">
            <button
              type="button"
              class="choice-button provider-button"
              :class="{ selected: provider === 'WECHAT_PAY' }"
              data-test="provider-wechat"
              :aria-pressed="provider === 'WECHAT_PAY'"
              @click="provider = 'WECHAT_PAY'"
            ><Smartphone :size="18" aria-hidden="true" /><span>微信支付</span></button>
            <button
              type="button"
              class="choice-button provider-button"
              :class="{ selected: provider === 'ALIPAY' }"
              data-test="provider-alipay"
              :aria-pressed="provider === 'ALIPAY'"
              @click="provider = 'ALIPAY'"
            ><CreditCard :size="18" aria-hidden="true" /><span>支付宝</span></button>
          </div>
        </fieldset>

        <TopErrorMessage :message="submitError" />

        <section v-if="paymentAction && currentOrder" class="payment-stage" aria-labelledby="payment-stage-title">
          <div class="payment-stage-heading">
            <div><span>充值单号</span><code>{{ currentOrder.rechargeNo }}</code></div>
            <el-tag :type="rechargeStatusType(currentOrder.status)">{{ rechargeStatusLabel(currentOrder.status) }}</el-tag>
          </div>
          <h3 id="payment-stage-title">完成渠道支付</h3>
          <PaymentActionPane :action="paymentAction" />
          <el-button
            v-if="currentOrder.status === 'PENDING'"
            data-test="refresh-payment-status"
            :loading="checkingPayment"
            @click="refreshPaymentStatus"
          ><RefreshCw :size="16" aria-hidden="true" />我已支付，刷新状态</el-button>
          <el-button
            v-else-if="currentOrder.status === 'FAILED' || currentOrder.status === 'CLOSED'"
            type="primary"
            data-test="start-new-recharge"
            @click="startNewRecharge"
          ><RefreshCw :size="16" aria-hidden="true" />重新充值</el-button>
        </section>
      </template>
    </div>

    <template #footer>
      <el-button :disabled="submitting" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button
        v-if="!paymentAction"
        type="primary"
        :data-test="resuming ? 'resume-recharge-payment' : 'submit-recharge'"
        :loading="submitting"
        :disabled="!canSubmit"
        @click="submitRecharge"
      >{{ currentOrder ? '继续支付' : '创建充值订单' }}</el-button>
    </template>
  </el-dialog>
  <SmsVerificationDialog
    v-model="paymentVerificationOpen"
    purpose="PAYMENT_CONFIRM"
    @verified="completePaymentVerification"
  />
</template>

<style scoped>
.recharge-flow { display: grid; gap: 20px; min-height: 300px; }
.choice-group { min-width: 0; margin: 0; padding: 0; border: 0; }
.choice-group legend { margin-bottom: 10px; font-weight: 650; }
.existing-order-summary { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 16px; padding: 12px; background: var(--app-bg); border: 1px solid var(--app-border); border-radius: 7px; }
.existing-order-summary > div { display: grid; min-width: 0; gap: 4px; }
.existing-order-summary span { color: var(--app-muted); font-size: 12px; }
.existing-order-summary code { overflow-wrap: anywhere; }
.existing-order-summary strong { font-size: 18px; font-variant-numeric: tabular-nums; }
.amount-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.provider-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.choice-button { min-width: 0; min-height: 44px; padding: 9px 12px; color: var(--app-fg); background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 7px; cursor: pointer; transition: border-color var(--app-motion-fast), background-color var(--app-motion-fast); }
.choice-button:hover { border-color: var(--app-primary); }
.choice-button.selected { color: var(--app-primary); background: color-mix(in srgb, var(--app-primary) 9%, var(--app-surface)); border-color: var(--app-primary); box-shadow: inset 0 0 0 1px var(--app-primary); }
.provider-button { display: inline-flex; align-items: center; justify-content: center; gap: 8px; }
.custom-field { display: grid; gap: 7px; margin-top: 12px; }
.custom-field label { font-weight: 600; }
.custom-field :deep(.el-input__wrapper) { min-height: 44px; }
.field-help, .field-error { margin: 0; font-size: 12px; line-height: 1.5; }
.field-help { color: var(--app-muted); }
.field-error { color: var(--app-danger); }
.payment-stage { display: grid; gap: 12px; padding-top: 16px; border-top: 1px solid var(--app-border); }
.payment-stage h3 { margin: 0; font-size: 16px; }
.payment-stage-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.payment-stage-heading > div { display: grid; min-width: 0; gap: 2px; }
.payment-stage-heading span { color: var(--app-muted); font-size: 12px; }
.payment-stage-heading code { overflow-wrap: anywhere; }
.payment-stage > :deep(.el-button) { min-height: 44px; margin: 0; }
@media (max-width: 520px) {
  .amount-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .provider-grid { grid-template-columns: minmax(0, 1fr); }
}
</style>
