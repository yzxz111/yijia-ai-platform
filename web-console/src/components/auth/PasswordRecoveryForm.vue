<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { isAxiosError } from 'axios'

import {
  requestEmailChallenge,
  requestPasswordResetSms,
  resetPassword,
  type EmailChallenge,
  type SmsChallenge,
} from '../../api/platform'
import SliderCaptchaDialog from './SliderCaptchaDialog.vue'
import SmsCodeField from '../sms/SmsCodeField.vue'

const emit = defineEmits<{
  completed: []
}>()

const identifier = ref('')
const verificationCode = ref('')
const challenge = ref<SmsChallenge | EmailChallenge | null>(null)
const newPassword = ref('')
const confirmation = ref('')
const submitting = ref(false)
const emailSending = ref(false)
const emailCooldown = ref(0)
const errorMessage = ref('')
const sliderCaptcha = ref<InstanceType<typeof SliderCaptchaDialog> | null>(null)
let captchaProof = ''
let cooldownTimer: ReturnType<typeof setInterval> | null = null

const isPhone = computed(() => /^1[3-9]\d{9}$/.test(identifier.value.trim()))
const isEmail = computed(() => /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(identifier.value.trim()))

function passwordError(value: string) {
  if (value.length < 8) return '密码至少需要 8 位。'
  if (new TextEncoder().encode(value).length > 72) return '密码内容过长，请缩短后重试。'
  if (!/[a-z]/.test(value)) return '密码需要包含小写字母。'
  if (!/[A-Z]/.test(value)) return '密码需要包含大写字母。'
  if (!/\d/.test(value)) return '密码需要包含数字。'
  if (!/[^A-Za-z0-9]/.test(value)) return '密码需要包含特殊字符。'
  return ''
}

const newPasswordError = computed(() => newPassword.value ? passwordError(newPassword.value) : '')
const confirmationError = computed(() => confirmation.value && confirmation.value !== newPassword.value
  ? '两次输入的新密码不一致。'
  : '')
const canSubmit = computed(() => Boolean(
  (isPhone.value || isEmail.value)
  && challenge.value
  && /^\d{6}$/.test(verificationCode.value)
  && newPassword.value
  && !passwordError(newPassword.value)
  && confirmation.value === newPassword.value,
))

watch(identifier, () => {
  challenge.value = null
  verificationCode.value = ''
  errorMessage.value = ''
})

function startEmailCooldown() {
  if (cooldownTimer) clearInterval(cooldownTimer)
  emailCooldown.value = 60
  cooldownTimer = setInterval(() => {
    emailCooldown.value -= 1
    if (emailCooldown.value <= 0 && cooldownTimer) {
      clearInterval(cooldownTimer)
      cooldownTimer = null
    }
  }, 1000)
}

async function completeCaptcha() {
  captchaProof = await sliderCaptcha.value?.open() || ''
  return Boolean(captchaProof)
}

function sendSmsCode() {
  const proof = captchaProof
  captchaProof = ''
  return requestPasswordResetSms(identifier.value.trim(), proof)
}

function formatSmsError(error: unknown) {
  if (isAxiosError<{ code?: string }>(error)) {
    const code = error.response?.data?.code
    if (code === 'password_reset_unavailable') return '未找到可使用该手机号找回的账号。'
    if (code === 'sms_template_not_active') return '密码验证码短信模板暂不可用，请联系管理员。'
  }
  return '验证码发送失败，请稍后重试。'
}

async function sendEmailCode() {
  if (!isEmail.value || emailSending.value || emailCooldown.value > 0) return
  emailSending.value = true
  errorMessage.value = ''
  try {
    challenge.value = await requestEmailChallenge(identifier.value.trim())
    verificationCode.value = ''
    startEmailCooldown()
  } catch (error) {
    errorMessage.value = isAxiosError<{ code?: string }>(error)
      && error.response?.data?.code === 'email_disabled'
      ? '邮箱验证码暂不可用，请使用手机号找回或联系管理员。'
      : '验证码发送失败，请稍后重试。'
  } finally {
    emailSending.value = false
  }
}

async function submit() {
  if (!canSubmit.value || submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await resetPassword(
      identifier.value.trim(),
      challenge.value!.id,
      verificationCode.value,
      newPassword.value,
    )
    emit('completed')
  } catch {
    challenge.value = null
    verificationCode.value = ''
    errorMessage.value = '密码重置失败，请重新获取验证码后重试。'
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(() => {
  if (cooldownTimer) clearInterval(cooldownTimer)
})
</script>

<template>
  <form class="password-recovery-form" data-test="password-recovery-form" novalidate @submit.prevent="submit">
    <p class="form-intro">使用账号绑定的手机号或邮箱验证身份。</p>
    <label for="recovery-identifier">手机号或邮箱</label>
    <el-input
      id="recovery-identifier"
      v-model="identifier"
      data-test="recovery-identifier"
      name="identifier"
      autocomplete="username"
      placeholder="手机号或 name@example.com"
      size="large"
    />

    <label>验证码</label>
    <SmsCodeField
      v-if="isPhone"
      v-model="verificationCode"
      :phone="identifier"
      require-phone
      :disabled="submitting"
      :before-send="completeCaptcha"
      :send-challenge="sendSmsCode"
      :format-send-error="formatSmsError"
      @challenge="challenge = $event"
    />
    <div v-else class="email-code-row">
      <el-input
        v-model="verificationCode"
        data-test="recovery-code"
        inputmode="numeric"
        maxlength="6"
        autocomplete="one-time-code"
        placeholder="输入 6 位验证码"
      />
      <el-button
        type="primary"
        native-type="button"
        data-test="recovery-email-send"
        :loading="emailSending"
        :disabled="!isEmail || emailCooldown > 0"
        @click="sendEmailCode"
      >{{ emailCooldown > 0 ? `${emailCooldown}s` : '发送验证码' }}</el-button>
    </div>

    <label for="recovery-password">新密码</label>
    <el-input
      id="recovery-password"
      v-model="newPassword"
      data-test="recovery-password"
      type="password"
      name="new-password"
      autocomplete="new-password"
      show-password
      size="large"
    />
    <p class="password-help">至少 8 位，包含大小写字母、数字和特殊字符。</p>
    <p v-if="newPasswordError" class="field-error" role="alert">{{ newPasswordError }}</p>

    <label for="recovery-confirmation">确认新密码</label>
    <el-input
      id="recovery-confirmation"
      v-model="confirmation"
      data-test="recovery-confirmation"
      type="password"
      name="confirm-password"
      autocomplete="new-password"
      show-password
      size="large"
    />
    <p v-if="confirmationError" class="field-error" role="alert">{{ confirmationError }}</p>
    <TopErrorMessage :message="errorMessage" />
    <el-button
      class="submit-button"
      data-test="recovery-submit"
      native-type="submit"
      type="primary"
      size="large"
      :loading="submitting"
      :disabled="!canSubmit"
    >重置密码</el-button>
    <SliderCaptchaDialog ref="sliderCaptcha" />
  </form>
</template>

<style scoped>
.password-recovery-form { display: flex; min-width: 0; flex-direction: column; gap: 12px; }
.form-intro, .password-help { margin: 0; color: var(--app-muted); font-size: 13px; line-height: 1.5; }
label { margin-top: 3px; color: var(--app-muted-strong); font-weight: 650; }
.email-code-row { display: grid; min-width: 0; grid-template-columns: minmax(0, 1fr) 116px; gap: 8px; }
.email-code-row :deep(.el-button) { width: 116px; }
.field-error { margin: -6px 0 0; color: var(--app-danger); font-size: 12px; line-height: 1.5; }
.form-error { margin: 0; padding: 9px 10px; color: var(--app-danger); background: var(--app-danger-soft); border: 1px solid var(--app-danger); border-radius: 6px; }
.submit-button { align-self: stretch; }
.password-recovery-form :deep(.el-input__wrapper), .password-recovery-form :deep(.el-button) { min-height: 44px; }
@media (max-width: 420px) {
  .email-code-row { grid-template-columns: minmax(0, 1fr); }
  .email-code-row :deep(.el-button) { width: 100%; }
}
</style>
