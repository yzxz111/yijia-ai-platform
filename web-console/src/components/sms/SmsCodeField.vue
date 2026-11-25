<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import { Send } from 'lucide-vue-next'

import type { SmsChallenge } from '../../api/platform'
import TopErrorMessage from '../feedback/TopErrorMessage.vue'

const props = withDefaults(defineProps<{
  modelValue: string
  phone?: string
  requirePhone?: boolean
  disabled?: boolean
  beforeSend?: () => Promise<boolean>
  sendChallenge: () => Promise<SmsChallenge>
  formatSendError?: (error: unknown) => string
}>(), {
  phone: '',
  requirePhone: false,
  disabled: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  challenge: [value: SmsChallenge | null]
}>()

const sending = ref(false)
const remainingSeconds = ref(0)
const errorMessage = ref('')
const inputId = `sms-code-${useId()}`
let resendAt = 0
let countdownTimer: ReturnType<typeof setInterval> | null = null
let issueGeneration = 0

const sendDisabled = computed(() => props.disabled
  || sending.value
  || remainingSeconds.value > 0
  || (props.requirePhone && !props.phone.trim()))

function clearTimer() {
  if (countdownTimer) clearInterval(countdownTimer)
  countdownTimer = null
}

function updateCountdown() {
  remainingSeconds.value = Math.max(0, Math.ceil((resendAt - Date.now()) / 1000))
  if (remainingSeconds.value === 0) clearTimer()
}

function startCountdown() {
  clearTimer()
  resendAt = Date.now() + 60_000
  remainingSeconds.value = 60
  countdownTimer = setInterval(updateCountdown, 1_000)
}

async function send() {
  if (sendDisabled.value) return
  const generation = ++issueGeneration
  sending.value = true
  errorMessage.value = ''
  try {
    if (props.beforeSend && !await props.beforeSend()) return
    if (generation !== issueGeneration) return
    emit('challenge', null)
    emit('update:modelValue', '')
    const challenge = await props.sendChallenge()
    if (generation !== issueGeneration) return
    emit('challenge', challenge)
    startCountdown()
  } catch (error) {
    if (generation !== issueGeneration) return
    errorMessage.value = props.formatSendError?.(error) || '验证码发送失败，请重试。'
  } finally {
    if (generation === issueGeneration) sending.value = false
  }
}

function updateCode(value: string) {
  emit('update:modelValue', value.replace(/\D/g, '').slice(0, 6))
}

function reset() {
  issueGeneration += 1
  clearTimer()
  resendAt = 0
  remainingSeconds.value = 0
  sending.value = false
  errorMessage.value = ''
  emit('challenge', null)
  emit('update:modelValue', '')
}

watch(() => props.phone, (phone, previous) => {
  if (phone !== previous) reset()
})

onBeforeUnmount(() => {
  issueGeneration += 1
  clearTimer()
})
defineExpose({ reset })
</script>

<template>
  <div class="sms-code-field">
    <label :for="inputId">短信验证码</label>
    <div class="sms-code-row">
      <div data-test="sms-code">
        <el-input
          :id="inputId"
          :model-value="modelValue"
          type="tel"
          name="sms-code"
          inputmode="numeric"
          autocomplete="one-time-code"
          maxlength="6"
          :disabled="disabled || sending"
          @update:model-value="updateCode"
        />
      </div>
      <el-button
        class="sms-send"
        data-test="sms-send"
        type="primary"
        :loading="sending"
        :disabled="sendDisabled"
        @click="send"
      >
        <Send v-if="!sending && remainingSeconds === 0" :size="16" aria-hidden="true" />
        <span v-if="remainingSeconds > 0" class="sms-countdown">{{ remainingSeconds }} 秒</span>
        <span v-else>发送验证码</span>
      </el-button>
    </div>
    <TopErrorMessage :message="errorMessage" />
  </div>
</template>

<style scoped>
.sms-code-field { display: grid; min-width: 0; gap: 7px; }
.sms-code-field label { font-weight: 650; }
.sms-code-row { display: grid; min-width: 0; grid-template-columns: minmax(0, 1fr) 132px; gap: 8px; }
.sms-code-row > div { min-width: 0; }
.sms-code-row :deep(.el-input__wrapper) { min-height: 44px; }
.sms-send { width: 132px; min-height: 44px; }
.sms-countdown { min-width: 4.5em; font-variant-numeric: tabular-nums; }
@media (max-width: 420px) {
  .sms-code-row { grid-template-columns: minmax(0, 1fr); }
  .sms-send { width: 100%; }
}
</style>
