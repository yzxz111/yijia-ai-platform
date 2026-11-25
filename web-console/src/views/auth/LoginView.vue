<script setup lang="ts">
import { APP_NAME } from '../../brand'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Eye, EyeOff, LogIn } from 'lucide-vue-next'

import { captureInvitationCodeFromQuery, forgetInvitationCode } from '../../invitationCode'
import {
  isLoginHandoffPending, isDeviceLoginRecovery, login, logout,
  type DeviceLoginRecovery, type LoginResult,
} from '../../api/platform'
import { readCookie } from '../../api/http'
import AuthBrandPanel from '../../components/auth/AuthBrandPanel.vue'
import DeviceLoginRecoveryPanel from '../../components/auth/DeviceLoginRecoveryPanel.vue'
import DesktopOAuthReturnPanel from '../../components/auth/DesktopOAuthReturnPanel.vue'
import { useAuthStore } from '../../stores/auth'
import {
  OAUTH_TRANSACTION_PARAM, forgetDesktopPasswordSetup, readOAuthTransaction,
  recallOAuthTransaction, rememberDesktopPasswordSetup, rememberOAuthTransaction, useDesktopOAuth,
} from './desktopOAuth'
import { passwordLoginErrorMessage } from './passwordLoginError'
import { forgetPendingEmailRegistration } from './pendingEmailRegistration'
import { forgetPendingPhoneRegistration } from './pendingPhoneRegistration'

const props = withDefaults(defineProps<{ desktopAuthorization?: boolean }>(), {
  desktopAuthorization: false,
})
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const appName = APP_NAME

watch(() => route.query.invite, (invite) => {
  if ('invite' in route.query) captureInvitationCodeFromQuery(invite)
}, { immediate: true })

const desktopOAuthTransaction = ref<string | null>(
  props.desktopAuthorization
    ? readOAuthTransaction(route.query) ?? recallOAuthTransaction()
    : null,
)

async function clearDesktopBrowserSession(): Promise<void> {
  const hasBrowserSession = auth.isAuthenticated || Boolean(readCookie('yzh_csrf'))
  try {
    if (hasBrowserSession) await logout()
  } catch (error) {
    console.warn('[DesktopOAuth] failed to clear the browser login session.', error)
  } finally {
    auth.clearSession()
  }
}

const desktopOAuth = useDesktopOAuth(
  () => desktopOAuthTransaction.value, undefined, clearDesktopBrowserSession,
)
const identifier = ref('')
const password = ref('')
const showPassword = ref(false)
const submitting = ref(false)
const errorMessage = ref('')
const deviceRecovery = ref<DeviceLoginRecovery | null>(null)
const canSubmit = computed(() => Boolean(identifier.value.trim() && password.value))
const oauthActive = desktopOAuth.active
const oauthReadyToReturn = desktopOAuth.readyToReturn
const oauthError = desktopOAuth.error
const oauthCallbackUrl = desktopOAuth.callbackUrl
const invalidDesktopAuthorization = computed(() => (
  props.desktopAuthorization && !desktopOAuthTransaction.value
))

onMounted(async () => {
  forgetPendingPhoneRegistration()
  forgetPendingEmailRegistration()
  const transaction = desktopOAuthTransaction.value
  if (!transaction) return
  forgetDesktopPasswordSetup()
  rememberOAuthTransaction(transaction)
  await clearDesktopBrowserSession()
})

watch(identifier, () => { errorMessage.value = '' })

async function submit() {
  if (submitting.value || !canSubmit.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    // 已有账号直接校验密码；登录不请求邮件、短信或滑块验证服务。
    await acceptLoginResult(await login(identifier.value.trim(), password.value))
  } catch (error) {
    errorMessage.value = passwordLoginErrorMessage(error)
  } finally {
    submitting.value = false
  }
}

async function acceptLoginResult(result: LoginResult) {
  if (isDeviceLoginRecovery(result)) {
    deviceRecovery.value = result
    password.value = ''
    return
  }
  deviceRecovery.value = null
  if (isLoginHandoffPending(result)) {
    forgetInvitationCode()
    await router.replace({
      path: '/login/handoff/' + encodeURIComponent(result.requestId),
      query: desktopOAuthTransaction.value
        ? { [OAUTH_TRANSACTION_PARAM]: desktopOAuthTransaction.value }
        : undefined,
    })
    return
  }
  auth.setSession(result.accessToken, result.user)
  try { sessionStorage.removeItem('welcome_dialog_seen') } catch { /* 忽略 */ }
  const accountStatus = result.user.status || 'ACTIVE'
  if (accountStatus !== 'ACTIVE' || result.user.passwordSet !== false) forgetInvitationCode()
  if (oauthActive.value) {
    rememberOAuthTransaction(desktopOAuthTransaction.value || '')
    if (accountStatus !== 'ACTIVE') {
      await router.replace('/account-restricted')
      return
    }
    if (result.user.passwordSet === false) {
      rememberDesktopPasswordSetup(desktopOAuthTransaction.value || '', result.user.id)
      await router.replace({
        path: '/desktop-set-password',
        query: { [OAUTH_TRANSACTION_PARAM]: desktopOAuthTransaction.value },
      })
      return
    }
    await desktopOAuth.completeAuthorization()
    return
  }
  await router.replace(accountStatus !== 'ACTIVE'
    ? '/account-restricted'
    : result.user.passwordSet === false
      ? '/set-password'
      : result.user.role === 'ADMIN' ? '/admin/overview' : '/app/dashboard')
}
</script>

<template>
  <main
    class="auth-shell"
    :class="{ 'auth-shell--desktop': props.desktopAuthorization }"
  >
    <template v-if="!props.desktopAuthorization">
      <AuthBrandPanel :app-name="appName" />
    </template>
    <section
      class="auth-form-panel"
      :aria-labelledby="oauthReadyToReturn
        ? 'desktop-return-title'
        : invalidDesktopAuthorization ? 'oauth-invalid-title' : 'login-title'"
    >
      <div class="auth-form-card">
        <DesktopOAuthReturnPanel
          v-if="oauthReadyToReturn"
          :error="oauthError"
          :callback-url="oauthCallbackUrl || ''"
        />

        <section
          v-else-if="invalidDesktopAuthorization"
          class="oauth-invalid"
          data-test="oauth-invalid"
          aria-labelledby="oauth-invalid-title"
        >
          <p>桌面端登录</p>
          <h2 id="oauth-invalid-title">登录请求已失效</h2>
          <span>请返回一加AI桌面端重新发起登录。</span>
        </section>

        <DeviceLoginRecoveryPanel v-else-if="deviceRecovery" :recovery="deviceRecovery"
          @completed="acceptLoginResult" @cancel="deviceRecovery = null" />
        <template v-else>
        <div class="auth-form-header">
          <span class="auth-eyebrow">YOUR AI WORKSPACE</span>
          <h2 id="login-title">欢迎回来</h2>
          <p class="auth-form-sub">使用账号和密码登录 {{ appName }}</p>
        </div>
        <form class="auth-form" @submit.prevent="submit">
          <div class="form-field">
            <label for="login-identifier">账号（邮箱或手机号）</label>
            <el-input
              id="login-identifier"
              v-model="identifier"
              data-test="login-identifier"
              type="text"
              name="identifier"
              :disabled="submitting"
              autocomplete="username"
              spellcheck="false"
              placeholder="手机号或 name@example.com"
              size="large"
            />
          </div>

            <div class="form-field">
              <div class="form-field-label-row">
                <label for="login-password">密码</label>
              </div>
              <el-input
                id="login-password"
                v-model="password"
                data-test="login-password"
                :type="showPassword ? 'text' : 'password'"
                name="password"
                autocomplete="current-password"
                :disabled="submitting"
                placeholder="请输入密码"
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
            </div>
          <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>
          <p v-if="oauthError" class="form-error" data-test="oauth-error">{{ oauthError }}</p>

          <el-button
            native-type="submit"
            type="primary"
            size="large"
            class="submit-btn"
            data-test="login-submit"
            :loading="submitting"
            :disabled="!canSubmit"
          >
            <LogIn :size="17" aria-hidden="true" />
            <span>登录</span>
          </el-button>
        </form>

        <div class="auth-divider"><span>账号密码登录</span></div>
        <div class="auth-alt-hint">
          无需邮箱或短信验证码。账号开通或密码重置请联系管理员。
        </div>
        </template>
      </div>
    </section>
  </main>
</template>

<style scoped>
.auth-shell {
  display: grid;
  min-width: 320px;
  min-height: 100dvh;
  grid-template-columns: minmax(360px, 48%) minmax(0, 1fr);
  background: var(--app-bg);
}

.auth-shell--desktop {
  display: block;
  background: var(--app-surface);
}

.auth-shell--desktop .auth-form-panel {
  min-height: 100dvh;
  align-items: center;
  padding: clamp(28px, 6vw, 56px) 24px;
  background: var(--app-surface);
}

.auth-shell--desktop .auth-form-card {
  width: min(460px, 100%);
  padding: 0;
  border: 0;
  box-shadow: none;
}

.auth-eyebrow { color: var(--app-primary); font-size: 10px; font-weight: 650; letter-spacing: 2px; }
.auth-tab input { width: 14px; height: 14px; margin: 0; accent-color: var(--app-primary); }

/* ---- 右侧表单区 ---- */
.auth-form-panel {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: center;
  padding: 48px 32px;
  background: var(--app-bg);
}

/* ---- 表单卡片 ---- */
.auth-form-card {
  width: min(460px, 100%);
  padding: 38px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 22px;
  box-shadow: 0 16px 60px #18352908;
}

.oauth-invalid {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 24px;
  text-align: center;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 10px;
}

.oauth-invalid p,
.oauth-invalid h2,
.oauth-invalid span { margin: 0; }
.oauth-invalid p { color: var(--app-primary); font-size: 12px; font-weight: 700; }
.oauth-invalid h2 { color: var(--app-fg); font-size: 24px; }
.oauth-invalid span { color: var(--app-muted-strong); font-size: 14px; }

/* ---- 标签切换 ---- */
.auth-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 26px;
  padding: 4px;
  background: var(--app-surface-muted);
  border-radius: 10px;
}

.auth-tab {
  position: relative;
  display: flex;
  flex: 1;
  min-height: 40px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px 10px;
  background: transparent;
  border: 0;
  border-radius: 7px;
  color: var(--app-muted-strong);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background var(--app-motion-fast) ease;
}

.auth-tab:hover { color: var(--app-muted-strong); }

.auth-tab--active {
  color: var(--app-primary);
  background: var(--app-surface);
  box-shadow: var(--app-shadow-xs);
}

.auth-tab--active::after {
  display: none;
  content: '';
}

/* ---- 表单标题 ---- */
.auth-form-header {
  margin-bottom: 28px;
}

.auth-form-header h2 {
  margin: 10px 0 0;
  color: var(--app-fg);
  font-size: 30px;
  font-weight: 650;
  letter-spacing: -.6px;
}

.auth-form-sub {
  margin: 6px 0 0;
  color: var(--app-muted-strong);
  font-size: 14px;
}

/* ---- 表单字段 ---- */
.auth-form { display: flex; flex-direction: column; gap: 16px; }

.form-field { display: flex; flex-direction: column; gap: 6px; }

.form-field label,
.auth-form label {
  color: var(--app-muted-strong);
  font-size: 13px;
  font-weight: 600;
}

.form-field-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.forgot-link {
  color: var(--app-primary);
  font-size: 13px;
  font-weight: 500;
  text-decoration: none;
  transition: color var(--app-motion-fast) ease;
}
.forgot-link:hover { color: var(--app-primary-hover); text-decoration: underline; }

/* ---- 输入框 ---- */
.auth-form :deep(.el-input__wrapper) {
  border-radius: 8px;
  background: var(--app-surface);
  box-shadow: 0 0 0 1px var(--app-border) inset !important;
  transition: all var(--app-motion-fast) ease;
}

.auth-form :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #d1d5db inset !important;
}

.auth-form :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--app-primary) inset, 0 0 0 3px var(--app-primary-soft) !important;
}

.auth-form :deep(.el-input__inner) {
  height: 42px;
  color: var(--app-fg);
}

.auth-form :deep(.el-input__inner::placeholder) {
  color: var(--app-muted);
}

.auth-form :deep(.el-input__inner:-webkit-autofill) {
  -webkit-text-fill-color: var(--app-fg);
  -webkit-box-shadow: 0 0 0 1000px var(--app-surface) inset;
  transition: background-color 5000s ease-in-out 0s;
}

/* ---- 邮箱验证码行 ---- */
.email-code-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 110px;
  gap: 8px;
}
.email-code-row :deep(.el-button) {
  width: 110px;
  border-radius: 8px;
}

.form-hint {
  margin: 4px 0 0;
  color: var(--app-muted);
  font-size: 12px;
}

/* ---- 密码切换按钮 ---- */
.password-toggle {
  display: inline-flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  color: var(--app-muted);
  background: transparent;
  border: 0;
  border-radius: 6px;
  cursor: pointer;
  transition: color var(--app-motion-fast) ease;
}
.password-toggle:hover { color: var(--app-muted-strong); }

/* ---- 错误提示 ---- */
.form-error {
  margin: 0;
  padding: 10px 12px;
  color: var(--app-danger-text);
  background: var(--app-danger-soft);
  border: 1px solid var(--app-danger-soft);
  border-radius: 8px;
  font-size: 13px;
}

/* ---- 登录按钮 ---- */
.submit-btn {
  height: 46px;
  margin-top: 4px;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 600;
  background: var(--app-primary);
  border: 0;
  box-shadow: 0 1px 3px transparent;
  transition: all var(--app-motion-base) ease;
}

.submit-btn:hover {
  background: var(--app-primary-hover);
  box-shadow: 0 2px 8px var(--app-primary-soft);
}

.submit-btn:active {
  transform: translateY(0);
}

.submit-btn.is-disabled {
  opacity: 0.5;
  transform: none;
  box-shadow: none;
}

/* ---- 分割线 ---- */
.auth-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 24px 0 16px;
  color: var(--app-muted);
  font-size: 12px;
}

.auth-divider::before,
.auth-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--app-border);
}

/* ---- 底部提示 ---- */
.auth-alt-hint {
  text-align: center;
  color: var(--app-muted-strong);
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 767px) {
  .auth-shell { display: block; }
  .auth-form-panel {
    min-height: calc(100dvh - 72px);
    align-items: flex-start;
    padding: 28px 16px;
  }
  .auth-form-card { width: min(460px, 100%); padding: 28px 24px; border-radius: 18px; }
  .auth-tabs { gap: 20px; }
}
</style>
