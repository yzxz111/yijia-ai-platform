<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { AlertTriangle, RotateCcw } from 'lucide-vue-next'

import {
  cancelRefund,
  getRefund,
  requestRefund,
  type OrderDetail,
  type RefundDetail,
} from '../../api/platform'

const props = defineProps<{ modelValue: boolean; order: OrderDetail }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  updated: [refund: RefundDetail]
}>()

const reason = ref('')
const refund = ref<RefundDetail | null>(null)
const submitting = ref(false)
const canceling = ref(false)
const loading = ref(false)
const errorMessage = ref('')
let loadSequence = 0

const canCancel = computed(() => refund.value?.status === 'PREPARING' || refund.value?.status === 'PENDING_APPROVAL')
const canSubmit = computed(() => reason.value.trim().length > 0 && reason.value.trim().length <= 500)
const number = new Intl.NumberFormat('zh-CN')
const refundStatusLabels: Record<string, string> = {
  PREPARING: '准备中',
  PENDING_APPROVAL: '待审核',
  REJECTED: '已拒绝',
  CANCELED: '已取消',
  SUBMITTING: '提交中',
  PROCESSING: '退款处理中',
  REVOKING: '套餐撤销中',
  COMPLETED: '已完成',
  FAILED: '退款失败',
}

function money(value: number) {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency', currency: props.order.order.currency || 'CNY', minimumFractionDigits: 2,
  }).format(value / 100)
}

async function loadExisting(refundNo: string) {
  const sequence = ++loadSequence
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await getRefund(refundNo)
    if (sequence === loadSequence) refund.value = result
  } catch {
    if (sequence === loadSequence) errorMessage.value = '退款详情加载失败，请稍后重试。'
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

async function submitRequest() {
  if (!canSubmit.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    refund.value = await requestRefund(props.order.order.orderNo, reason.value.trim())
    emit('updated', refund.value)
  } catch {
    errorMessage.value = '退款申请提交失败，请检查退款资格后重试。'
  } finally {
    submitting.value = false
  }
}

async function cancelRequest() {
  if (!refund.value || !canCancel.value) return
  canceling.value = true
  errorMessage.value = ''
  try {
    refund.value = await cancelRefund(refund.value.refundNo)
    emit('updated', refund.value)
  } catch {
    errorMessage.value = '退款申请取消失败，当前状态可能已发生变化。'
  } finally {
    canceling.value = false
  }
}

function reset() {
  loadSequence += 1
  reason.value = ''
  refund.value = null
  errorMessage.value = ''
  loading.value = false
  if (props.order.refund?.refundNo) void loadExisting(props.order.refund.refundNo)
}

watch(() => props.modelValue, (visible) => {
  if (visible) reset()
}, { immediate: true })
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="申请退款"
    width="min(600px, calc(100vw - 24px))"
    :teleported="false"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="refund-body">
      <section v-if="!refund" class="rules" data-test="refund-rules">
        <div class="rules-title"><AlertTriangle :size="18" aria-hidden="true" /><strong>提交前请确认退款规则</strong></div>
        <ul>
          <li>退款窗口、积分使用比例与最低手续费均以服务端当前政策计算。</li>
          <li>提交时会冻结对应套餐，实际退款金额以生成的计算快照为准。</li>
          <li>管理员批准前可以取消申请；批准后将进入原支付渠道处理。</li>
        </ul>
      </section>

      <TopErrorMessage :message="errorMessage" />
      <el-skeleton v-if="loading" :rows="5" animated />

      <form v-else-if="!refund" class="refund-form" data-test="refund-form" @submit.prevent="submitRequest">
        <label for="refund-reason">退款原因</label>
        <textarea
          id="refund-reason"
          name="refundReason"
          v-model="reason"
          data-test="refund-reason"
          autocomplete="off"
          maxlength="500"
          rows="4"
          placeholder="请说明退款原因…"
        />
        <div class="form-meta"><span>最多 500 字</span><span>{{ reason.length }}/500</span></div>
        <el-button type="primary" native-type="submit" class="action-button" data-test="request-refund" :loading="submitting" :disabled="!canSubmit">
          <RotateCcw :size="16" aria-hidden="true" />提交退款申请
        </el-button>
      </form>

      <template v-else>
        <div class="refund-status">
          <span>退款单</span><code>{{ refund.refundNo }}</code><el-tag size="small">{{ refundStatusLabels[refund.status] || refund.status }}</el-tag>
        </div>

        <section v-if="refund.calculation" class="calculation" data-test="refund-calculation" aria-label="退款计算快照">
          <h3>退款计算快照</h3>
          <dl>
            <div><dt>原支付金额</dt><dd>{{ money(refund.calculation.paidAmount) }}</dd></div>
            <div><dt>总积分</dt><dd>{{ number.format(refund.calculation.totalTokens) }}</dd></div>
            <div><dt>已使用积分</dt><dd>{{ number.format(refund.calculation.usedTokens) }}</dd></div>
            <div><dt>剩余积分</dt><dd>{{ number.format(refund.calculation.remainingTokens) }}</dd></div>
            <div><dt>基础退款</dt><dd>{{ money(refund.calculation.baseRefund) }}</dd></div>
            <div><dt>退款手续费</dt><dd>{{ money(refund.calculation.fee) }}</dd></div>
            <div class="total"><dt>预计退款</dt><dd>{{ money(refund.calculation.refundAmount) }}</dd></div>
          </dl>
          <p>政策版本 {{ refund.calculation.policyVersion }} · {{ refund.calculation.refundType === 'FULL' ? '全额退款' : '部分退款' }}</p>
        </section>

        <el-button v-if="canCancel" class="action-button" data-test="cancel-refund" :loading="canceling" @click="cancelRequest">
          取消退款申请
        </el-button>
      </template>
    </div>
  </el-dialog>
</template>

<style scoped>
.refund-body { display: grid; gap: 16px; }
.rules { padding: 14px 16px; background: var(--app-bg); border: 1px solid var(--app-border); border-radius: 8px; }
.rules-title { display: flex; align-items: center; gap: 8px; }
.rules ul { margin: 10px 0 0; padding-left: 20px; color: var(--app-muted); line-height: 1.7; }
.refund-form { display: grid; gap: 8px; }
.refund-form label { font-weight: 650; }
.refund-form textarea { min-height: 112px; padding: 10px 12px; resize: vertical; color: var(--app-fg); background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 7px; font: inherit; line-height: 1.5; }
.refund-form textarea:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 2px; }
.form-meta { display: flex; justify-content: space-between; color: var(--app-muted); font-size: 12px; }
.refund-status { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.refund-status span { color: var(--app-muted); }
.refund-status code { margin-right: auto; overflow-wrap: anywhere; }
.calculation { padding: 16px; border: 1px solid var(--app-border); border-radius: 8px; }
.calculation h3 { margin: 0 0 10px; font-size: 15px; }
.calculation dl { margin: 0; }
.calculation dl div { display: flex; justify-content: space-between; gap: 12px; padding: 8px 0; border-bottom: 1px solid var(--app-border); }
.calculation dt { color: var(--app-muted); }
.calculation dd { margin: 0; font-weight: 650; font-variant-numeric: tabular-nums; }
.calculation .total { border-bottom: 0; font-size: 16px; }
.calculation p { margin: 8px 0 0; color: var(--app-muted); font-size: 12px; }
.action-button { width: 100%; min-height: 44px; }
</style>
