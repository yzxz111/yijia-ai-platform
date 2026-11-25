<script setup lang="ts">
import { isAxiosError } from 'axios'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Eye, EyeOff, KeyRound } from 'lucide-vue-next'

import {
  changePassword,
  completeEmailRegistration,
  completePhoneRegistration,
  isDeviceLoginRecovery,
  isLoginHandoffPending,
  logout,
} from '../../api/platform'
import { readCookie, restoreSession } from '../../api/http'
import {
  captureInvitationCodeFromQuery,
  forgetInvitationCode,
  isValidOptionalInvitationCode,
  normalizeInvitationCode,
  recallInvitationCode,
} from '../../invitationCode'
import DesktopOAuthReturnPanel from '../../components/auth/DesktopOAuthReturnPanel.vue'
import { useAuthStore } from '../../stores/auth'
import {
  forgetDesktopPasswordSetup,
  readOAuthTransaction,
  recallDesktopPasswordSetup,
  recallOAuthTransaction,
  rememberDesktopPasswordSetup,
  rememberOAuthTransaction,
  useDesktopOAuth,
} from './desktopOAuth'
import { isStrongFirstPassword } from './firstPassword'
import {
  forgetPendingEmailRegistration,
  recallPendingEmailRegistration,
} from './pendingEmailRegistration'
import {
  forgetPendingPhoneRegistration,
  recallPendingPhoneRegistration,
} from './pendingPhoneRegistration'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const transaction = ref(readOAuthTransaction(route.query) ?? recallOAuthTransaction())
const recalledPhoneRegistration = recallPendingPhoneRegistration()
const recalledEmailRegistration = recallPendingEmailRegistration()
const pendingPhoneRegistration = recalledPhoneRegistration?.oauthTransaction === transaction.value
  ? recalledPhoneRegistration : null
const pendingEmailRegistration = recalledEmailRegistration?.oauthTransaction === transaction.value
  ? recalledEmailRegistration : null
// A transaction must have one verified identity. Never choose one silently if stale
// registration challenges for both methods remain in the same browser tab.
const pendingRegistration = ref(pendingPhoneRegistration && pendingEmailRegistration
  ? null
  : pendingEmailRegistration
    ? { ...pendingEmailRegistration, kind: 'email' as const }
    : pendingPhoneRegistration
      ? { ...pendingPhoneRegistration, kind: 'phone' as const }
      : null)
function forgetPendingRegistration(): void {
  if (pendingRegistration.value?.kind === 'email') forgetPendingEmailRegistration()
  else if (pendingRegistration.value?.kind === 'phone') forgetPendingPhoneRegistration()
  pendingRegistration.value = null
}
const pendingSetup = computed(() => Boolean(!auth.isAuthenticated
  && pendingRegistration.value
  && transaction.value
  && pendingRegistration.value.oauthTransaction === transaction.value))
const samePasswordSetup = computed(() => {
  const setupContext = recallDesktopPasswordSetup()
  return Boolean(setupContext
    && setupContext.transactionId === transaction.value
    && setupContext.userId === auth.user?.id)
})
const validSetup = computed(() => pendingSetup.value || samePasswordSetup.value)
// 邀请码输入框：注册流程必显；已登录但还没设过密码的账号在桌面设密页同样要能补邀请码，
// 否则老用户的邀请归因在桌面端点进来时会丢。
const showInvitationCode = computed(() => pendingSetup.value
  || (auth.isAuthenticated && auth.user?.passwordSet === false && samePasswordSetup.value))
const password = ref('')
const confirmation = ref('')
// 邀请链接（/console/login?invite=<码>）走到桌面端设密页时同样预填，与网页端保持一致；
// 客户端自己跳转时 query 常丢，因此回退读登录页暂存的那份。显式带 invite 进入时以 query 为准。
const invitationCode = ref('invite' in route.query
  ? captureInvitationCodeFromQuery(route.query.invite)
  : recallInvitationCode())
const showPassword = ref(false)
const submitting = ref(false)
const errorMessage = ref('')
const restoringPendingEmailSession = ref(Boolean(pendingRegistration.value?.kind === 'email'
  && !auth.isAuthenticated && readCookie('yzh_csrf')))

async function clearDesktopBrowserSession(): Promise<void> {
  const hasBrowserSession = auth.isAuthenticated || Boolean(readCookie('yzh_csrf'))
  try {
    if (hasBrowserSession) await logout()
  } catch (error) {
    console.warn('[DesktopOAuth] failed to clear the browser login session.', error)
  } finally {
    forgetDesktopPasswordSetup()
    auth.clearSession()
  }
}

const desktopOAuth = useDesktopOAuth(
  () => transaction.value,
  undefined,
  clearDesktopBrowserSession,
)
const invitationCodeError = computed(() => isValidOptionalInvitationCode(invitationCode.value)
  ? '' : '邀请码须为 32 位大小写字母或数字。')
const canSubmit = computed(() => (pendingSetup.value
  || (auth.user?.passwordSet === false && samePasswordSetup.value))
  && desktopOAuth.active.value
  && isStrongFirstPassword(password.value)
  && password.value === confirmation.value
  && (!showInvitationCode.value || !invitationCodeError.value)
  && !restoringPendingEmailSession.value
  && !submitting.value)

async function completeAuthorization() {
  if (!samePasswordSetup.value || auth.user?.passwordSet !== true
    || !desktopOAuth.active.value || desktopOAuth.pending.value) return
  await desktopOAuth.completeAuthorization()
}

async function recoverPendingEmailSession(): Promise<void> {
  const pending = pendingRegistration.value
  if (pending?.kind !== 'email' || !restoringPendingEmailSession.value) return
  try {
    if (!await restoreSession()) return
    const restoredEmail = auth.user?.email?.trim().toLowerCase()
    if (restoredEmail === pending.email && auth.user?.passwordSet === true) {
      rememberDesktopPasswordSetup(transaction.value || '', auth.user.id)
      forgetPendingRegistration()
      await completeAuthorization()
      return
    }
    // A refresh cookie from another account must not be authorized for this transaction.
    await clearDesktopBrowserSession()
  } finally {
    restoringPendingEmailSession.value = false
  }
}

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    let session
    if (pendingSetup.value && pendingRegistration.value) {
      const currentPending = pendingRegistration.value.kind === 'email'
        ? recallPendingEmailRegistration()
        : recallPendingPhoneRegistration()
      if (!currentPending || currentPending.challengeId !== pendingRegistration.value.challengeId
        || currentPending.oauthTransaction !== transaction.value) {
        throw new Error('registration_pending_expired')
      }
      session = pendingRegistration.value.kind === 'email'
        ? await completeEmailRegistration(
          currentPending.challengeId, password.value, currentPending.consentVersion,
          normalizeInvitationCode(invitationCode.value),
        )
        : await completePhoneRegistration(
          currentPending.challengeId, password.value, currentPending.consentVersion,
          normalizeInvitationCode(invitationCode.value),
        )
    } else {
      session = await changePassword('', password.value, undefined,
        normalizeInvitationCode(invitationCode.value))
    }
    if (!session) throw new Error('password_setup_session_missing')
    forgetInvitationCode()
    if (isLoginHandoffPending(session)) {
      forgetPendingRegistration()
      await router.replace({
        path: `/login/handoff/${encodeURIComponent(session.requestId)}`,
        query: { oauth_transaction: transaction.value },
      })
      return
    }
    if (isDeviceLoginRecovery(session)) {
      // 设备登录恢复只在登录页处理；桌面设密流程里遇到说明会话异常，回登录页重来。
      await router.replace('/login')
      return
    }
    auth.setSession(session.accessToken, session.user)
    if (pendingRegistration.value) {
      rememberDesktopPasswordSetup(transaction.value || '', session.user.id)
      forgetPendingRegistration()
    }
    await completeAuthorization()
  } catch (error) {
    const code = isAxiosError<{ code?: string }>(error) ? error.response?.data?.code : undefined
    const locallyExpired = error instanceof Error && error.message === 'registration_pending_expired'
    const invalidRegistration = code === 'invalid_registration_challenge' || code === 'challenge_expired'
      || code === 'challenge_consumed' || code === 'challenge_not_found'
    const legalDocumentsChanged = code === 'legal_document_version_stale'
      || code === 'invalid_consent_version' || code === 'terms_acceptance_required'
    const invalidInvitationCode = code === 'invalid_invitation_code'
      || code === 'invitation_code_revoked'
    const invitationCodeNotAllowed = code === 'invitation_code_not_allowed'
    const unavailable = code === 'phone_unavailable' || code === 'email_unavailable'
    const registrationConflict = isAxiosError(error)
      && (error.response?.status === 409 || error.response?.status === 410)
    if (pendingRegistration.value && (locallyExpired || invalidRegistration
      || legalDocumentsChanged || unavailable || (registrationConflict && !invalidInvitationCode))) {
      forgetPendingRegistration()
    }
    errorMessage.value = locallyExpired
      ? '注册验证已失效，请返回桌面端重新发起登录。'
      : invalidInvitationCode
        ? '邀请码无效或已撤销，请修改后重试。'
      : invitationCodeNotAllowed
        ? '此账号无法再使用邀请码，请清空后重试。'
      : code === 'legal_document_version_stale'
      ? '协议已更新，请返回登录页重新阅读并同意。'
      : code === 'invalid_consent_version'
        ? '协议已更新，请返回登录页重新阅读并同意。'
      : code === 'terms_acceptance_required'
        ? '注册协议确认已失效，请返回登录页重新阅读并同意。'
      : code === 'phone_unavailable'
        ? '该手机号已注册，请返回桌面端重新发起登录。'
      : code === 'email_unavailable'
        ? '该邮箱已注册，请返回桌面端重新发起登录。'
      : invalidRegistration
        ? '注册验证已失效，请返回桌面端重新发起登录。'
        : isAxiosError(error) && error.response?.status === 422
          ? '密码不符合安全规则，请重新输入。'
          : registrationConflict
            ? '注册请求已失效或账号已创建，请返回桌面端重新发起登录。'
          : '密码设置失败，请稍后重试。'
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  if (!transaction.value || !validSetup.value) return
  rememberOAuthTransaction(transaction.value)
  if (restoringPendingEmailSession.value) {
    void recoverPendingEmailSession()
    return
  }
  // 设密接口可能已成功、但页面在授权回跳前刷新；用新会话继续原授权。
  if (auth.isAuthenticated && auth.user?.passwordSet === true) {
    void completeAuthorization()
  }
})
</script>

<template>
  <main class="desktop-password-shell">
    <div class="desktop-password-card">
      <DesktopOAuthReturnPanel
        v-if="desktopOAuth.readyToReturn.value"
        :error="desktopOAuth.error.value"
        :callback-url="desktopOAuth.callbackUrl.value || ''"
      />

      <section v-else-if="restoringPendingEmailSession" class="desktop-password-state" data-test="desktop-password-restoring">
        <p class="eyebrow">一加AI桌面端</p>
        <h1>正在恢复登录</h1>
        <p>正在确认设密结果，请稍候。</p>
      </section>

      <section v-else-if="!validSetup" class="desktop-password-state" data-test="desktop-password-invalid">
        <p class="eyebrow">一加AI桌面端</p>
        <h1>登录请求已失效</h1>
        <p v-if="errorMessage" class="form-error" data-test="desktop-password-error">{{ errorMessage }}</p>
        <p v-else>请返回桌面端重新发起登录。</p>
      </section>

      <section v-else-if="!pendingSetup && auth.user?.passwordSet !== false" class="desktop-password-state" data-test="desktop-password-returning">
        <p class="eyebrow">一加AI桌面端</p>
        <h1>正在返回桌面端</h1>
        <p>密码已设置，正在完成桌面端登录授权。此页面不会进入中台工作台。</p>
        <p v-if="desktopOAuth.error.value" class="form-error" data-test="desktop-password-oauth-error">
          {{ desktopOAuth.error.value }}
        </p>
        <el-button
          v-if="desktopOAuth.error.value"
          type="primary"
          :loading="desktopOAuth.pending.value"
          data-test="desktop-password-retry"
          @click="completeAuthorization"
        >重试返回桌面端</el-button>
      </section>

      <form v-else data-test="desktop-password-form" @submit.prevent="submit">
        <div class="form-heading">
          <span class="form-icon"><KeyRound :size="24" aria-hidden="true" /></span>
          <div>
            <p class="eyebrow">一加AI桌面端</p>
            <h1>首次登录请设置密码</h1>
          </div>
        </div>
        <p class="form-hint">设置完成后会自动继续桌面端登录，不会跳转到中台工作台。今后也可以使用此密码在中台官网登录。</p>

        <label for="desktop-first-password">新密码</label>
        <el-input
          id="desktop-first-password"
          v-model="password"
          data-test="desktop-first-password"
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

        <label for="desktop-confirm-password">确认密码</label>
        <el-input
          id="desktop-confirm-password"
          v-model="confirmation"
          data-test="desktop-confirm-password"
          type="password"
          autocomplete="new-password"
          size="large"
        />
        <small>至少 8 位，包含大小写字母、数字和特殊字符。</small>
        <template v-if="showInvitationCode">
          <label for="desktop-invitation-code">邀请码（选填）</label>
          <el-input
            id="desktop-invitation-code"
            v-model="invitationCode"
            data-test="desktop-invitation-code"
            autocomplete="off"
            autocapitalize="off"
            :spellcheck="false"
            size="large"
            placeholder="32 位大小写字母和数字"
            :aria-invalid="Boolean(invitationCodeError)"
            :aria-describedby="invitationCodeError ? 'desktop-invitation-code-error' : undefined"
          />
          <small v-if="invitationCodeError" id="desktop-invitation-code-error" class="form-error" role="alert">{{ invitationCodeError }}</small>
        </template>
        <p v-if="errorMessage" class="form-error" data-test="desktop-password-error">{{ errorMessage }}</p>
        <el-button
          native-type="submit"
          type="primary"
          size="large"
          class="submit-button"
          data-test="desktop-password-submit"
          :disabled="!canSubmit"
          :loading="submitting"
        >设置密码并继续登录</el-button>
      </form>
    </div>
  </main>
</template>

<style scoped>
.desktop-password-shell { display: grid; min-width: 320px; min-height: 100dvh; place-items: center; padding: 28px 18px; background: #fff; }
.desktop-password-card { width: min(458px, 100%); }
form, .desktop-password-state { display: flex; flex-direction: column; gap: 14px; }
.form-heading { display: flex; align-items: center; gap: 14px; margin-bottom: 4px; }
.form-icon { display: grid; width: 48px; height: 48px; flex: 0 0 auto; place-items: center; color: var(--app-primary); background: var(--app-primary-soft); border-radius: 12px; }
.eyebrow { margin: 0 0 4px; color: var(--app-primary); font-size: 12px; font-weight: 700; }
h1 { margin: 0; color: var(--app-fg); font-size: 26px; line-height: 1.3; }
.form-hint, .desktop-password-state > p:not(.eyebrow, .form-error) { margin: 0 0 8px; color: var(--app-muted-strong); font-size: 14px; line-height: 1.7; }
label { margin-top: 3px; color: var(--app-muted-strong); font-size: 13px; font-weight: 650; }
small { color: var(--app-muted); font-size: 12px; }
.password-toggle { display: inline-flex; width: 44px; height: 44px; align-items: center; justify-content: center; color: var(--app-muted); background: transparent; border: 0; border-radius: 6px; cursor: pointer; }
.password-toggle:hover { color: var(--app-primary); background: var(--app-primary-soft); }
.form-error { margin: 0; padding: 10px 12px; color: var(--app-danger-text); background: var(--app-danger-soft); border: 1px solid var(--app-danger-soft); border-radius: 8px; }
.submit-button { width: 100%; margin-top: 5px; }
@media (max-width: 480px) { .desktop-password-shell { place-items: start center; padding-top: 64px; } }
</style>
