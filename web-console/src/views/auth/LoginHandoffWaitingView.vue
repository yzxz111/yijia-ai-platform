<script setup lang="ts">
import { APP_NAME } from '../../brand'
import { isAxiosError } from 'axios'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Clock3, LogIn, X } from 'lucide-vue-next'

import {
  cancelLoginHandoff,
  completeLoginHandoff,
  getLoginHandoffStatus,
  logout,
  type LoginHandoffStatus,
} from '../../api/platform'
import { readCookie } from '../../api/http'
import AuthBrandPanel from '../../components/auth/AuthBrandPanel.vue'
import DesktopOAuthReturnPanel from '../../components/auth/DesktopOAuthReturnPanel.vue'
import { useAuthStore } from '../../stores/auth'
import {
  OAUTH_TRANSACTION_PARAM,
  readOAuthTransaction,
  recallOAuthTransaction,
  rememberDesktopPasswordSetup,
  rememberOAuthTransaction,
  useDesktopOAuth,
} from './desktopOAuth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const appName = APP_NAME
const requestId = computed(() => String(route.params.requestId || ''))
const desktopOAuthTransaction = ref<string | null>(
  readOAuthTransaction(route.query) ?? recallOAuthTransaction(),
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
  () => desktopOAuthTransaction.value,
  undefined,
  clearDesktopBrowserSession,
)
const oauthReadyToReturn = desktopOAuth.readyToReturn
const oauthError = desktopOAuth.error
const oauthCallbackUrl = desktopOAuth.callbackUrl

const status = ref<LoginHandoffStatus>('PENDING')
const expiresAt = ref<string | null>(null)
const remainingSeconds = ref(0)
const transientError = ref('')
const completing = ref(false)
const cancelling = ref(false)
const stopped = ref(false)
const handoffSessionCompleted = ref(false)
let pollTimer: number | undefined
let countdownTimer: number | undefined

const validRequestId = computed(() => /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  .test(requestId.value))
const countdown = computed(() => {
  const minutes = Math.floor(remainingSeconds.value / 60).toString().padStart(2, '0')
  const seconds = (remainingSeconds.value % 60).toString().padStart(2, '0')
  return `${minutes}:${seconds}`
})
const terminalMessages: Partial<Record<LoginHandoffStatus, string>> = {
  DENIED: '原设备已拒绝本次登录。',
  CANCELLED: '本次登录确认已取消。',
  SUPERSEDED: '已有更新的登录请求，请重新登录。',
  EXPIRED: '登录确认已过期，请重新登录。',
}
const terminalMessage = computed(() => terminalMessages[status.value] || '')

function updateCountdown() {
  if (!expiresAt.value) return
  remainingSeconds.value = Math.max(0, Math.ceil((Date.parse(expiresAt.value) - Date.now()) / 1000))
}

async function complete() {
  if (stopped.value || completing.value) return
  completing.value = true
  transientError.value = ''
  try {
    if (handoffSessionCompleted.value && desktopOAuth.active.value) {
      if (auth.user?.passwordSet === false) {
        rememberDesktopPasswordSetup(desktopOAuthTransaction.value || '', auth.user.id)
        await router.replace({
          path: '/desktop-set-password',
          query: { [OAUTH_TRANSACTION_PARAM]: desktopOAuthTransaction.value },
        })
        return
      }
      await desktopOAuth.completeAuthorization()
      return
    }
    const session = await completeLoginHandoff(requestId.value)
    if (stopped.value) return
    handoffSessionCompleted.value = true
    auth.setSession(session.accessToken, session.user)
    if (desktopOAuth.active.value) {
      if (session.user.status !== 'ACTIVE') {
        await router.replace('/account-restricted')
        return
      }
      if (session.user.passwordSet === false) {
        rememberDesktopPasswordSetup(desktopOAuthTransaction.value || '', session.user.id)
        await router.replace({
          path: '/desktop-set-password',
          query: { [OAUTH_TRANSACTION_PARAM]: desktopOAuthTransaction.value },
        })
        return
      }
      await desktopOAuth.completeAuthorization()
      return
    }
    await router.replace(session.user.passwordSet === false
      ? '/set-password'
      : session.user.role === 'ADMIN' ? '/admin/overview' : '/app/dashboard')
  } catch (error) {
    const code = isAxiosError<{ code?: string }>(error) ? error.response?.data?.code : undefined
    if (code === 'login_handoff_pending') return
    if (code === 'login_handoff_denied') status.value = 'DENIED'
    else if (code === 'login_handoff_cancelled') status.value = 'CANCELLED'
    else if (code === 'login_handoff_superseded') status.value = 'SUPERSEDED'
    else if (code === 'login_handoff_expired') status.value = 'EXPIRED'
    else transientError.value = '连接暂时中断，正在重试。'
  } finally {
    completing.value = false
  }
}

async function poll() {
  if (stopped.value || !validRequestId.value) return
  try {
    const current = await getLoginHandoffStatus(requestId.value)
    if (stopped.value) return
    status.value = current.status
    expiresAt.value = current.expiresAt
    transientError.value = ''
    updateCountdown()
    if (current.status === 'APPROVED'
        || (current.status === 'PENDING' && remainingSeconds.value === 0)) {
      await complete()
    }
    if (oauthReadyToReturn.value || oauthError.value) return
    if (['DENIED', 'CANCELLED', 'SUPERSEDED', 'EXPIRED'].includes(status.value)) return
  } catch {
    transientError.value = '连接暂时中断，正在重试。'
  }
  if (!stopped.value) pollTimer = window.setTimeout(poll, 5_000)
}

async function cancel() {
  if (cancelling.value) return
  cancelling.value = true
  try {
    if (validRequestId.value) await cancelLoginHandoff(requestId.value)
  } finally {
    await router.replace('/login')
  }
}

onMounted(() => {
  if (!validRequestId.value) {
    status.value = 'EXPIRED'
    return
  }
  if (desktopOAuthTransaction.value) rememberOAuthTransaction(desktopOAuthTransaction.value)
  void poll()
  countdownTimer = window.setInterval(updateCountdown, 1_000)
})

onBeforeUnmount(() => {
  stopped.value = true
  if (pollTimer !== undefined) window.clearTimeout(pollTimer)
  if (countdownTimer !== undefined) window.clearInterval(countdownTimer)
})
</script>

<template>
  <main class="handoff-shell">
    <AuthBrandPanel :app-name="appName" />
    <section
      class="handoff-panel"
      :aria-labelledby="oauthReadyToReturn ? 'desktop-return-title' : 'handoff-title'"
    >
      <DesktopOAuthReturnPanel
        v-if="oauthReadyToReturn"
        :error="oauthError"
        :callback-url="oauthCallbackUrl || ''"
      />
      <div v-else class="handoff-content">
        <div class="status-icon" aria-hidden="true"><Clock3 :size="28" /></div>
        <p class="section-label">设备登录确认</p>
        <h1 id="handoff-title">等待原设备确认</h1>
        <p v-if="!terminalMessage" class="summary">账号已在另一台电脑登录，请在原设备确认是否由本人发起。</p>
        <p v-else class="terminal-message" role="status">{{ terminalMessage }}</p>
        <div v-if="!terminalMessage" class="countdown" aria-live="polite">
          <span>剩余确认时间</span><strong>{{ countdown }}</strong>
        </div>
        <p v-if="transientError" class="transient" role="status">{{ transientError }}</p>
        <p v-if="oauthError" class="transient" role="alert">{{ oauthError }}</p>
        <el-button v-if="terminalMessage" type="primary" size="large" data-test="handoff-return" @click="router.replace('/login')">
          <LogIn :size="17" />返回登录
        </el-button>
        <el-button v-else text size="large" data-test="handoff-cancel" :loading="cancelling" @click="cancel">
          <X :size="17" />取消本次登录
        </el-button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.handoff-shell { display: grid; min-width: 320px; min-height: 100dvh; grid-template-columns: minmax(360px, 40%) minmax(0, 1fr); background: var(--app-bg); }
.handoff-panel { display: flex; min-width: 0; align-items: center; justify-content: center; padding: 36px; }
.handoff-content { display: flex; width: min(448px, 100%); flex-direction: column; align-items: stretch; gap: 14px; text-align: center; }
.status-icon { display: inline-flex; width: 56px; height: 56px; align-self: center; align-items: center; justify-content: center; color: var(--app-primary); background: var(--app-primary-soft); border-radius: 50%; }
.section-label { margin: 0; color: var(--app-accent); font-size: 12px; font-weight: 700; }
h1 { margin: 0; font-size: 26px; line-height: 1.3; }
.summary, .transient, .terminal-message { margin: 0; color: var(--app-muted); line-height: 1.7; }
.terminal-message { color: var(--app-muted-strong); font-weight: 650; }
.transient { font-size: 13px; }
.countdown { display: grid; gap: 6px; margin: 8px 0; padding: 20px 0; border-block: 1px solid var(--app-border); }
.countdown span { color: var(--app-muted); font-size: 13px; }
.countdown strong { color: var(--app-text); font-size: 34px; font-variant-numeric: tabular-nums; letter-spacing: 0; }
@media (max-width: 767px) {
  .handoff-shell { display: block; }
  .handoff-panel { min-height: calc(100dvh - 72px); align-items: flex-start; padding: 44px 18px 28px; }
  h1 { font-size: 23px; }
}
</style>
