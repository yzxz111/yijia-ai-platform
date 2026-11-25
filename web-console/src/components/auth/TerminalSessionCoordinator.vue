<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ShieldAlert } from 'lucide-vue-next'

import {
  approveLoginHandoff,
  denyLoginHandoff,
  getPendingLoginHandoff,
  heartbeatTerminalSession,
  requestTerminalClose,
  type PendingLoginApproval,
} from '../../api/platform'
import { useAuthStore } from '../../stores/auth'
import { createBrowserPageLifecycle } from '../../utils/browserPageLifecycle'
import {
  publishTerminalSessionEvent,
  subscribeTerminalSessionEvents,
  type TerminalSessionEvent,
} from '../../utils/terminalSessionChannel'

const auth = useAuthStore()
const router = useRouter()
const pending = ref<PendingLoginApproval | null>(null)
const deciding = ref(false)
const polling = ref(false)
let heartbeatTimer: number | undefined
let pendingTimer: number | undefined
let unsubscribe: (() => void) | undefined
const browserPage = createBrowserPageLifecycle()

/** 后台标签页暂停轮询；服务端保留页面登记，不用心跳超时推断关闭或休眠。 */
const PENDING_POLL_INTERVAL_MS = 15_000
const HEARTBEAT_INTERVAL_MS = 30_000
/** 窗口 focus 事件很密集（在窗口之间来回点击也会触发），限流到至少间隔 3 秒才补一次。 */
const FOCUS_REFRESH_MIN_GAP_MS = 3_000
let heartbeatBusy = false
let lastRefreshAt = 0

const dialogVisible = computed(() => Boolean(pending.value))

async function heartbeat() {
  if (!auth.isAuthenticated || heartbeatBusy) return
  const headers = browserPage.heartbeat()
  if (!headers) return
  heartbeatBusy = true
  try {
    const state = await heartbeatTerminalSession(headers)
    const wasRestricted = auth.isRestricted
    auth.updateAccountStatus(state.status)
    const current = router.currentRoute.value
    const allowsRestricted = current.matched.some((record) => record.meta.allowsRestricted)
    if (auth.isRestricted && !allowsRestricted) {
      await router.replace('/account-restricted')
    } else if (wasRestricted && !auth.isRestricted && current.path === '/account-restricted') {
      await router.replace(auth.isAdmin ? '/admin/overview' : '/app/dashboard')
    }
  } catch { /* 下一个定时间隔会重试。                */ } finally {
    heartbeatBusy = false
  }
}

async function pollPending() {
  if (!auth.isAuthenticated || auth.isRestricted || polling.value) return
  polling.value = true
  try {
    pending.value = await getPendingLoginHandoff()
  } catch {
    // 保持已展示的判定不变，按正常间隔重试。
  } finally {
    polling.value = false
  }
}

async function approve() {
  if (!pending.value || deciding.value) return
  deciding.value = true
  const requestId = pending.value.requestId
  try {
    await approveLoginHandoff(requestId)
    pending.value = null
    publishTerminalSessionEvent({ type: 'SESSION_REPLACED' })
    auth.clearSession()
    await router.replace('/login')
    ElMessage.success({ message: '已允许新设备登录，本设备已退出', duration: 5000, showClose: true })
  } finally {
    deciding.value = false
  }
}

async function deny() {
  if (!pending.value || deciding.value) return
  deciding.value = true
  const requestId = pending.value.requestId
  try {
    await denyLoginHandoff(requestId)
    pending.value = null
    publishTerminalSessionEvent({ type: 'HANDOFF_RESOLVED', requestId })
    ElMessage.success({ message: '已拒绝本次登录', duration: 3000, showClose: true })
  } finally {
    deciding.value = false
  }
}

function receive(event: TerminalSessionEvent) {
  if (event.type === 'HANDOFF_RESOLVED') {
    if (pending.value?.requestId === event.requestId) pending.value = null
    return
  }
  if (!auth.isAuthenticated) return
  auth.clearSession()
  void router.replace('/login')
}

function startTimers() {
  stopTimers()
  heartbeatTimer = window.setInterval(() => { void heartbeat() }, HEARTBEAT_INTERVAL_MS)
  pendingTimer = window.setInterval(() => { void pollPending() }, PENDING_POLL_INTERVAL_MS)
}

function stopTimers() {
  if (heartbeatTimer !== undefined) {
    window.clearInterval(heartbeatTimer)
    heartbeatTimer = undefined
  }
  if (pendingTimer !== undefined) {
    window.clearInterval(pendingTimer)
    pendingTimer = undefined
  }
}

function refreshNow() {
  lastRefreshAt = Date.now()
  void heartbeat()
  void pollPending()
}

function onFocus() {
  if (document.visibilityState !== 'visible') return
  if (Date.now() - lastRefreshAt < FOCUS_REFRESH_MIN_GAP_MS) return
  refreshNow()
}

function onVisibility() {
  if (document.visibilityState === 'visible') {
    // 回到前台：立刻补一次状态，并恢复定时轮询
    startTimers()
    onFocus()
  } else {
    // 后台标签页不再产生任何请求
    stopTimers()
  }
}
function onPageHide(event: PageTransitionEvent) {
  if (!event.persisted) closeBrowserPage()
}
function closeBrowserPage() {
  stopTimers()
  const headers = browserPage.close()
  if (headers && auth.isAuthenticated) requestTerminalClose(headers)
}
function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium', timeStyle: 'medium', hour12: false,
  }).format(new Date(value))
}

onMounted(() => {
  refreshNow()
  // 首次挂载即按当前可见性决定是否开始轮询，避免在后台标签页里空转
  if (document.visibilityState === 'visible') startTimers()
  window.addEventListener('focus', onFocus)
  window.addEventListener('pagehide', onPageHide)
  document.addEventListener('visibilitychange', onVisibility)
  unsubscribe = subscribeTerminalSessionEvents(receive)
})

onBeforeUnmount(() => {
  closeBrowserPage()
  window.removeEventListener('focus', onFocus)
  window.removeEventListener('pagehide', onPageHide)
  document.removeEventListener('visibilitychange', onVisibility)
  unsubscribe?.()
})
</script>

<template>
  <el-dialog
    :model-value="dialogVisible"
    append-to-body
    :z-index="4000"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    width="min(440px, calc(100vw - 24px))"
    aria-label="新设备登录确认"
  >
    <template #header>
      <div class="dialog-heading">
        <span class="heading-icon"><ShieldAlert :size="21" /></span>
        <div><h2>是否允许新的电脑端登录？</h2><p>请确认这是否由本人发起</p></div>
      </div>
    </template>
    <dl v-if="pending" class="handoff-details">
      <div><dt>申请时间</dt><dd>{{ formatTime(pending.requestedAt) }}</dd></div>
      <div><dt>大致位置</dt><dd>{{ pending.region || '未知地区' }}</dd></div>
      <div><dt>浏览器环境</dt><dd>{{ pending.clientSummary || '未知环境' }}</dd></div>
    </dl>
    <template #footer>
      <div class="dialog-actions">
        <el-button data-test="handoff-deny" :disabled="deciding" @click="deny">不是本人</el-button>
        <el-button type="primary" data-test="handoff-approve" :loading="deciding" @click="approve">
          是本人，切换登录
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.dialog-heading { display: flex; align-items: flex-start; gap: 12px; text-align: left; }
.heading-icon { display: inline-flex; width: 40px; height: 40px; flex: 0 0 40px; align-items: center; justify-content: center; color: var(--app-warning); background: var(--app-warning-soft); border-radius: 50%; }
.dialog-heading h2 { margin: 0; font-size: 18px; line-height: 1.4; }
.dialog-heading p { margin: 4px 0 0; color: var(--app-muted); font-size: 13px; }
.handoff-details { display: grid; margin: 0; border-block: 1px solid var(--app-border); }
.handoff-details div { display: grid; grid-template-columns: 88px minmax(0, 1fr); gap: 12px; padding: 12px 0; }
.handoff-details div + div { border-top: 1px solid var(--app-border); }
.handoff-details dt { color: var(--app-muted); }
.handoff-details dd { min-width: 0; margin: 0; overflow-wrap: anywhere; color: var(--app-text); }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; }
@media (max-width: 420px) {
  .dialog-actions { display: grid; grid-template-columns: 1fr; }
  .dialog-actions :deep(.el-button) { width: 100%; margin: 0; }
}
</style>
