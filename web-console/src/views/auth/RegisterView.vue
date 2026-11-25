<script setup lang="ts">
import { APP_NAME } from '../../brand'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { UserPlus } from 'lucide-vue-next'

import {
  isLoginHandoffPending,
  isDeviceLoginRecovery,
  register,
  requestRegistrationSms,
  type SmsChallenge,
} from '../../api/platform'
import AuthBrandPanel from '../../components/auth/AuthBrandPanel.vue'
import SliderCaptchaDialog from '../../components/auth/SliderCaptchaDialog.vue'
import SmsCodeField from '../../components/sms/SmsCodeField.vue'
import { useAuthStore } from '../../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const appName = APP_NAME

const displayName = ref('')
const email = ref('')
const password = ref('')
const confirmation = ref('')
const phone = ref('')
const verificationCode = ref('')
const registrationChallenge = ref<SmsChallenge | null>(null)
const consent = ref(false)
const submitting = ref(false)
const errorMessage = ref('')
const sliderCaptcha = ref<InstanceType<typeof SliderCaptchaDialog> | null>(null)
const captchaProof = ref('')

const strongPassword = computed(() => {
  const value = password.value
  return value.length >= 12
    && value.length <= 128
    && new TextEncoder().encode(value).length <= 72
    && /[a-z]/.test(value)
    && /[A-Z]/.test(value)
    && /\d/.test(value)
    && /[^A-Za-z0-9]/.test(value)
})

const canSubmit = computed(() => Boolean(
  displayName.value.trim()
  && email.value.trim()
  && strongPassword.value
  && password.value === confirmation.value
  && /^1[3-9]\d{9}$/.test(phone.value.trim())
  && registrationChallenge.value
  && /^\d{6}$/.test(verificationCode.value)
  && consent.value,
))

async function completeCaptcha() {
  const proof = await sliderCaptcha.value?.open()
  captchaProof.value = proof || ''
  return Boolean(proof)
}

function sendRegistrationSms() {
  const proof = captchaProof.value
  captchaProof.value = ''
  return requestRegistrationSms(phone.value.trim(), proof)
}

async function submit() {
  if (!canSubmit.value || submitting.value) {
    errorMessage.value = password.value !== confirmation.value
      ? '两次输入的密码不一致。'
      : '请完整填写表单，并使用至少 12 位且包含大小写字母、数字和特殊字符的密码。'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    const result = await register({
      email: email.value.trim(),
      password: password.value,
      displayName: displayName.value.trim(),
      phone: phone.value.trim(),
      challengeId: registrationChallenge.value!.id,
      verificationCode: verificationCode.value,
    })
    if (isLoginHandoffPending(result)) {
      await router.replace(`/login/handoff/${encodeURIComponent(result.requestId)}`)
      return
    }
    if (isDeviceLoginRecovery(result)) {
      await router.replace('/login')
      return
    }
    auth.setSession(result.accessToken, result.user)
    await router.replace('/app/dashboard')
  } catch {
    errorMessage.value = '注册失败，请检查短信验证码、邮箱和手机号后重试。'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="register-shell">
    <AuthBrandPanel :app-name="appName">
      <template #footer>
        <RouterLink to="/login">返回账号登录</RouterLink>
      </template>
    </AuthBrandPanel>

    <section class="register-form-panel" aria-labelledby="register-title">
      <form class="register-form" @submit.prevent="submit">
        <div>
          <p class="section-label">创建账号</p>
          <h2 id="register-title">创建账号</h2>
          <p class="form-intro">所有字段均来自平台身份服务的真实注册契约。</p>
        </div>

        <label for="register-name">显示名称</label>
        <el-input id="register-name" v-model="displayName" data-test="register-name" name="name" autocomplete="name" placeholder="例如：张伟" />

        <label for="register-email">邮箱</label>
        <el-input id="register-email" v-model="email" data-test="register-email" type="email" name="email" autocomplete="email" spellcheck="false" placeholder="name@example.com" />

        <label for="register-phone">手机号</label>
        <el-input
          id="register-phone"
          v-model="phone"
          data-test="register-phone"
          type="tel"
          name="tel"
          inputmode="tel"
          autocomplete="tel"
          maxlength="11"
        />
        <SmsCodeField
          v-model="verificationCode"
          :phone="phone"
          require-phone
          :before-send="completeCaptcha"
          :send-challenge="sendRegistrationSms"
          @challenge="registrationChallenge = $event"
        />

        <div class="password-grid">
          <div>
            <label for="register-password">密码</label>
            <el-input id="register-password" v-model="password" data-test="register-password" type="password" name="new-password" autocomplete="new-password" placeholder="至少 12 位…" />
          </div>
          <div>
            <label for="register-confirm">确认密码</label>
            <el-input id="register-confirm" v-model="confirmation" data-test="register-confirm" type="password" name="confirm-password" autocomplete="new-password" placeholder="再次输入密码…" />
          </div>
        </div>

        <p class="password-hint">密码需包含大写字母、小写字母、数字和特殊字符，且不得超过 72 字节。</p>
        <el-checkbox v-model="consent" data-test="register-consent">我同意平台服务条款与隐私政策</el-checkbox>
        <TopErrorMessage :message="errorMessage" />

        <el-button data-test="register-submit" native-type="submit" type="primary" size="large" :loading="submitting" :disabled="!canSubmit">
          <UserPlus :size="17" aria-hidden="true" />创建账号
        </el-button>
      </form>
    </section>
    <SliderCaptchaDialog ref="sliderCaptcha" />
  </main>
</template>

<style scoped>
.register-shell { display: grid; min-width: 320px; min-height: 100dvh; grid-template-columns: minmax(360px, 40%) minmax(0, 1fr); background: var(--app-surface); }
.register-form-panel { display: flex; min-width: 0; align-items: center; justify-content: center; padding: 32px; }
.register-form { display: flex; width: min(540px, 100%); flex-direction: column; gap: 10px; }
.section-label { margin: 0 0 7px; color: var(--app-accent); font-size: 12px; font-weight: 700; }
.register-form h2 { margin: 0; font-size: 24px; }
.form-intro { margin: 6px 0 10px; color: var(--app-muted); }
.register-form label { display: block; margin: 5px 0 6px; font-weight: 650; }
.password-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.password-hint { margin: 2px 0 5px; color: var(--app-muted); font-size: 12px; }
.form-error { margin: 0; color: var(--app-danger); }
@media (max-width: 767px) {
  .register-shell { display: block; }
  .register-form-panel { min-height: calc(100dvh - 72px); align-items: flex-start; padding: 32px 20px; }
  .password-grid { grid-template-columns: minmax(0, 1fr); }
}
</style>
