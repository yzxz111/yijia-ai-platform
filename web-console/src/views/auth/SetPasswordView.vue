<script setup lang="ts">
import { isAxiosError } from 'axios'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Eye, EyeOff, KeyRound, ShieldCheck } from 'lucide-vue-next'

import {
  changePassword,
  completeEmailRegistration,
  completePhoneRegistration,
  isDeviceLoginRecovery,
  isLoginHandoffPending,
} from '../../api/platform'
import { readCookie, restoreSession } from '../../api/http'
import {
  captureInvitationCodeFromQuery,
  forgetInvitationCode,
  isValidOptionalInvitationCode,
  normalizeInvitationCode,
  recallInvitationCode,
} from '../../invitationCode'
import { useAuthStore } from '../../stores/auth'
import { isStrongFirstPassword } from './firstPassword'
import { forgetPendingEmailRegistration, recallPendingEmailRegistration } from './pendingEmailRegistration'
import { forgetPendingPhoneRegistration, recallPendingPhoneRegistration } from './pendingPhoneRegistration'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const password = ref('')
const confirmation = ref('')
// 从邀请链接进来时（/console/login?invite=<码>）自动填好，省得手抄 32 位；
// query 丢了就回退读登录页暂存的那份（桌面端跳转场景）。显式带 invite 进入时以 query 为准。
const invitationCode = ref('invite' in route.query
  ? captureInvitationCodeFromQuery(route.query.invite)
  : recallInvitationCode())
const showPassword = ref(false)
const submitting = ref(false)
const errorMessage = ref('')
const pendingPhoneRegistration = recallPendingPhoneRegistration()
const pendingEmailRegistration = recallPendingEmailRegistration()
const pendingRegistration = ref(pendingPhoneRegistration && pendingEmailRegistration
  ? null
  : pendingPhoneRegistration ?? pendingEmailRegistration)
const pendingRegistrationKind = ref(pendingRegistration.value
  ? pendingPhoneRegistration ? 'phone' : 'email'
  : null)
const recoveringSession = ref(!auth.isAuthenticated
  && pendingRegistrationKind.value === 'email'
  && Boolean(readCookie('yzh_csrf')))

function forgetPendingRegistration() {
  forgetPendingPhoneRegistration()
  forgetPendingEmailRegistration()
  pendingRegistration.value = null
  pendingRegistrationKind.value = null
}

const strongPassword = computed(() => isStrongFirstPassword(password.value))
const canSetPassword = computed(() => auth.isAuthenticated
  ? auth.user?.passwordSet === false
  : pendingRegistration.value?.oauthTransaction === null)
// 已登录但还没设过密码的账号同样要能补邀请码（桌面端跳转丢 query 的场景），
// 因此这里只看「能不能设密」，不再按登录态隐藏输入框。
const showInvitationCode = computed(() => canSetPassword.value)
const invitationCodeError = computed(() => isValidOptionalInvitationCode(invitationCode.value)
  ? '' : '邀请码须为 32 位大小写字母或数字。')
const canSubmit = computed(() => canSetPassword.value && strongPassword.value
  && password.value === confirmation.value
  && (!showInvitationCode.value || !invitationCodeError.value)
  && !recoveringSession.value
  && !submitting.value)

onMounted(async () => {
  if (!recoveringSession.value) return
  try {
    const restored = await restoreSession()
    if (!restored || !auth.isAuthenticated) return
    const pending = recallPendingEmailRegistration()
    const restoredEmail = auth.user?.email?.trim().toLowerCase()
    if (pending && pending.oauthTransaction === null
        && pending.challengeId === pendingRegistration.value?.challengeId
        && pending.email === restoredEmail && auth.user?.passwordSet === true) {
      forgetPendingRegistration()
      forgetInvitationCode()
      await router.replace(auth.isRestricted
        ? '/account-restricted'
        : auth.isAdmin ? '/admin/overview' : '/app/dashboard')
      return
    }
    // Cookie 中的其他账号不得借用此邮箱待注册凭证。
    auth.clearSession()
  } finally {
    recoveringSession.value = false
  }
})

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    if (!auth.isAuthenticated) {
      // 新手机号或邮箱尚无账号；只有普通网页登录的待注册凭证可在此完成注册。
      const pending = pendingRegistrationKind.value === 'phone'
        ? recallPendingPhoneRegistration()
        : pendingRegistrationKind.value === 'email'
          ? recallPendingEmailRegistration()
          : null
      if (!pending || pending.oauthTransaction !== null
          || pending.challengeId !== pendingRegistration.value?.challengeId) {
        throw new Error('registration_pending_expired')
      }
      const result = pendingRegistrationKind.value === 'phone'
        ? await completePhoneRegistration(
          pending.challengeId, password.value, pending.consentVersion,
          normalizeInvitationCode(invitationCode.value),
        )
        : await completeEmailRegistration(
          pending.challengeId, password.value, pending.consentVersion,
          normalizeInvitationCode(invitationCode.value),
        )
      forgetPendingRegistration()
      forgetInvitationCode()
      if (isLoginHandoffPending(result)) {
        await router.replace(`/login/handoff/${encodeURIComponent(result.requestId)}`)
        return
      }
      if (isDeviceLoginRecovery(result)) {
        // 设备登录恢复只在登录页处理；此处遇到说明会话状态异常，回登录页重来一次。
        await router.replace('/login')
        return
      }
      auth.setSession(result.accessToken, result.user)
      await router.replace(result.user.role === 'ADMIN' ? '/admin/overview' : '/app/dashboard')
      return
    }
    const session = await changePassword('', password.value, undefined,
      normalizeInvitationCode(invitationCode.value))
    if (!session) throw new Error('password_setup_session_missing')
    forgetInvitationCode()
    auth.setSession(session.accessToken, session.user)
    await router.replace(session.user.role === 'ADMIN' ? '/admin/overview' : '/app/dashboard')
  } catch (error) {
    const errorCode = isAxiosError<{ code?: string }>(error)
      ? error.response?.data?.code
      : undefined
    const invalidRegistration = errorCode === 'invalid_registration_challenge'
      || errorCode === 'challenge_expired'
      || errorCode === 'challenge_consumed'
      || errorCode === 'challenge_not_found'
    const legalDocumentsChanged = errorCode === 'legal_document_version_stale'
      || errorCode === 'invalid_consent_version'
      || errorCode === 'terms_acceptance_required'
    const invalidInvitationCode = errorCode === 'invalid_invitation_code'
      || errorCode === 'invitation_code_revoked'
    const invitationCodeNotAllowed = errorCode === 'invitation_code_not_allowed'
    const locallyExpired = error instanceof Error && error.message === 'registration_pending_expired'
    const registrationKind = pendingRegistrationKind.value
    if (locallyExpired || invalidRegistration || legalDocumentsChanged
        || errorCode === 'phone_unavailable' || errorCode === 'email_unavailable') {
      forgetPendingRegistration()
    }
    errorMessage.value = locallyExpired
      ? '注册请求已过期，请返回登录页重新获取验证码。'
      : invalidInvitationCode
        ? '邀请码无效或已撤销，请修改后重试。'
      : invitationCodeNotAllowed
        ? '此账号无法再使用邀请码，请清空后重试。'
      : invalidRegistration
        ? `验证码注册请求已失效，请返回登录页重新验证${registrationKind === 'email' ? '邮箱' : '手机号'}。`
      : legalDocumentsChanged
        ? '服务协议已更新，请返回登录页重新阅读并同意。'
      : errorCode === 'phone_unavailable'
        ? '该手机号已注册，请返回登录页直接登录。'
      : errorCode === 'email_unavailable'
        ? '该邮箱已注册，请返回登录页直接登录。'
      : isAxiosError(error) && error.response?.status === 422
        ? '密码不符合安全规则，请重新输入。'
      : isAxiosError(error) && (error.response?.status === 409 || error.response?.status === 410)
        ? '注册请求已失效或账号已创建，请返回登录页使用验证码或密码登录。'
      : '密码设置失败，请稍后重试。'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="password-setup-shell">
    <section class="setup-context" aria-labelledby="setup-brand">
      <div class="brand-lockup">
        <span class="brand-mark" aria-hidden="true"><ShieldCheck :size="22" /></span>
        <strong id="setup-brand">一加AI中台</strong>
      </div>
      <div>
        <p>账户保护</p>
        <h1>首次登录请设置密码</h1>
      </div>
    </section>

    <section class="setup-form-panel" aria-labelledby="setup-title">
      <form @submit.prevent="submit">
        <div class="form-heading">
          <KeyRound :size="22" aria-hidden="true" />
          <div><p>完成账户初始化</p><h2 id="setup-title">设置登录密码</h2></div>
        </div>
        <p class="setup-hint" data-test="password-setup-hint">
          {{ auth.isAuthenticated ? '新用户注册需设置登录密码。' : '设置密码成功后才会创建账号。' }}
        </p>
        <p v-if="recoveringSession" class="setup-hint">正在恢复已完成的注册…</p>
        <p v-if="!canSetPassword" class="form-error" data-test="registration-invalid">
          注册请求已失效，请返回 <router-link to="/login">登录页</router-link> 重新验证手机号或邮箱。
        </p>

        <label for="first-password">新密码</label>
        <el-input
          id="first-password"
          v-model="password"
          data-test="first-password"
          :type="showPassword ? 'text' : 'password'"
          autocomplete="new-password"
          size="large"
        >
          <template #suffix>
            <button
              type="button"
              class="password-toggle"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >
              <EyeOff v-if="showPassword" :size="17" aria-hidden="true" />
              <Eye v-else :size="17" aria-hidden="true" />
            </button>
          </template>
        </el-input>

        <label for="confirm-first-password">确认密码</label>
        <el-input
          id="confirm-first-password"
          v-model="confirmation"
          data-test="confirm-first-password"
          type="password"
          autocomplete="new-password"
          size="large"
        />
        <small>至少 8 位，包含大小写字母、数字和特殊字符。</small>
        <template v-if="showInvitationCode">
          <label for="invitation-code">邀请码（选填）</label>
          <el-input
            id="invitation-code"
            v-model="invitationCode"
            data-test="invitation-code"
            autocomplete="off"
            autocapitalize="off"
            :spellcheck="false"
            size="large"
            placeholder="32 位大小写字母和数字"
            :aria-invalid="Boolean(invitationCodeError)"
            :aria-describedby="invitationCodeError ? 'invitation-code-error' : undefined"
          />
          <small v-if="invitationCodeError" id="invitation-code-error" class="form-error" role="alert">{{ invitationCodeError }}</small>
        </template>
        <TopErrorMessage :message="errorMessage" />
        <el-button
          native-type="submit"
          type="primary"
          size="large"
          data-test="set-password-submit"
          :disabled="!canSubmit"
          :loading="submitting"
        >设置密码</el-button>
      </form>
    </section>
  </main>
</template>

<style scoped>
.password-setup-shell { display: grid; min-width: 320px; min-height: 100dvh; grid-template-columns: minmax(360px, 40%) minmax(0, 1fr); background: var(--app-bg); }
.setup-context { display: flex; min-height: 100dvh; flex-direction: column; justify-content: space-between; padding: clamp(32px, 5vw, 64px); color: var(--app-fg); background: var(--app-sidebar); border-right: 1px solid var(--app-sidebar-border); box-shadow: var(--app-shadow); }
.brand-lockup { display: flex; align-items: center; gap: 12px; font-size: 17px; }
.brand-lockup strong { color: #eef6ed; font-weight: 700; }
.brand-mark { display: inline-flex; width: 42px; height: 42px; align-items: center; justify-content: center; color: #fff; background: var(--app-primary); border: 1px solid rgb(255 255 255 / 18%); border-radius: 8px; box-shadow: 0 6px 16px rgb(37 99 235 / 28%); }
.setup-context > div:last-child { max-width: 460px; padding-left: 22px; border-left: 3px solid var(--app-primary); }
.setup-context p { margin: 0 0 10px; color: var(--app-primary); font-size: 13px; font-weight: 700; }
.setup-context h1 { max-width: 420px; margin: 0; color: #eef6ed; font-size: 32px; line-height: 1.3; }
.setup-form-panel { display: flex; min-width: 0; align-items: center; justify-content: center; padding: 36px; background: var(--app-bg); }
form { display: flex; width: min(448px, 100%); flex-direction: column; gap: 13px; padding: 28px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: var(--app-radius); box-shadow: var(--app-shadow); }
.form-heading { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; color: var(--app-primary); }
.form-heading > svg { width: 38px; height: 38px; padding: 8px; background: var(--app-primary-soft); border-radius: 7px; }
.form-heading p { margin: 0 0 3px; color: var(--app-accent); font-size: 12px; font-weight: 700; }
.form-heading h2 { margin: 0; color: var(--app-text); font-size: 24px; line-height: 1.3; }
.setup-hint { margin: -2px 0 2px; color: var(--app-muted); font-size: 14px; line-height: 1.6; }
label { margin-top: 4px; color: var(--app-muted-strong); font-weight: 650; }
small { color: var(--app-muted); }
.password-toggle { display: inline-flex; width: 44px; height: 44px; align-items: center; justify-content: center; color: var(--app-muted); background: transparent; border: 0; border-radius: 6px; cursor: pointer; transition: color var(--app-motion-fast) ease, background-color var(--app-motion-fast) ease; }
.password-toggle:hover { color: var(--app-primary); background: var(--app-primary-soft); }
.password-toggle:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 2px; }
.form-error { margin: 0; padding: 9px 10px; color: var(--app-danger-text); background: var(--app-danger-soft); border: 1px solid var(--app-danger-soft); border-radius: 6px; }
@media (max-width: 767px) {
  .password-setup-shell { display: block; }
  .setup-context { min-height: 72px; padding: 14px 20px; box-shadow: 0 3px 12px rgb(23 32 51 / 12%); }
  .brand-mark { width: 36px; height: 36px; }
  .setup-context > div:last-child { display: none; }
  .setup-form-panel { min-height: calc(100dvh - 72px); align-items: flex-start; padding: 28px 12px; }
  form { padding: 22px 18px; }
}
</style>
