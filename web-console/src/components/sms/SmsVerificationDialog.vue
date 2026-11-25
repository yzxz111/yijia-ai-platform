<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ShieldCheck } from 'lucide-vue-next'

import {
  requestSmsChallenge,
  verifySmsChallenge,
  type SensitiveSmsPurpose,
  type SmsChallenge,
} from '../../api/platform'
import SmsCodeField from './SmsCodeField.vue'

const props = defineProps<{
  modelValue: boolean
  purpose: SensitiveSmsPurpose
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  verified: [proof: string]
}>()

const field = ref<InstanceType<typeof SmsCodeField> | null>(null)
const code = ref('')
const challenge = ref<SmsChallenge | null>(null)
const verifying = ref(false)
const errorMessage = ref('')
let verifyGeneration = 0

const title = computed(() => ({
  CHANGE_PASSWORD: '验证后修改密码',
  PAYMENT_CONFIRM: '验证后发起支付',
  API_KEY_RESET: '验证后重置密钥',
  PHONE_CHANGE_CONFIRM: '验证当前手机号',
})[props.purpose])

const canVerify = computed(() => Boolean(challenge.value && /^\d{6}$/.test(code.value) && !verifying.value))

function requestChallenge() {
  return requestSmsChallenge(props.purpose)
}

function clearTransient() {
  verifyGeneration += 1
  code.value = ''
  challenge.value = null
  verifying.value = false
  errorMessage.value = ''
  field.value?.reset()
}

function close() {
  clearTransient()
  emit('update:modelValue', false)
}

async function verify() {
  const activeChallenge = challenge.value
  if (!activeChallenge || !canVerify.value) return
  const generation = ++verifyGeneration
  const purpose = props.purpose
  verifying.value = true
  errorMessage.value = ''
  try {
    const result = await verifySmsChallenge(activeChallenge.id, code.value)
    if (generation !== verifyGeneration || !props.modelValue || props.purpose !== purpose) return
    if (!result.smsProof || result.purpose !== purpose) throw new Error('invalid_sms_proof')
    const proof = result.smsProof
    clearTransient()
    emit('update:modelValue', false)
    emit('verified', proof)
  } catch {
    if (generation !== verifyGeneration || !props.modelValue || props.purpose !== purpose) return
    clearTransient()
    errorMessage.value = '验证失败，请重新发送验证码后重试。'
  } finally {
    if (generation === verifyGeneration) verifying.value = false
  }
}

watch(() => props.modelValue, (visible) => {
  if (!visible) clearTransient()
})
watch(() => props.purpose, clearTransient)
onBeforeUnmount(() => {
  verifyGeneration += 1
})
</script>

<template>
  <div v-if="modelValue" data-test="sms-verification-dialog">
    <el-dialog
      :model-value="modelValue"
      :title="title"
      width="min(440px, calc(100vw - 24px))"
      :teleported="false"
      :close-on-click-modal="!verifying"
      :close-on-press-escape="!verifying"
      @update:model-value="$event ? undefined : close()"
    >
      <div class="verification-heading">
        <ShieldCheck :size="20" aria-hidden="true" />
        <span>{{ challenge?.maskedPhone || '已绑定手机号' }}</span>
      </div>
      <SmsCodeField
        ref="field"
        v-model="code"
        :disabled="verifying"
        :send-challenge="requestChallenge"
        @challenge="challenge = $event"
      />
      <TopErrorMessage :message="errorMessage" />
      <template #footer>
        <el-button :disabled="verifying" @click="close">取消</el-button>
        <el-button
          type="primary"
          data-test="sms-verify"
          :disabled="!canVerify"
          :loading="verifying"
          @click="verify"
        >确认验证</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.verification-heading { display: flex; min-width: 0; align-items: center; gap: 8px; margin-bottom: 16px; color: var(--app-muted); }
.verification-heading svg { flex: 0 0 auto; color: var(--app-primary); }
.verification-heading span { min-width: 0; overflow-wrap: anywhere; }
.verification-error { margin: 10px 0 0; color: var(--app-danger); font-size: 13px; }
</style>
