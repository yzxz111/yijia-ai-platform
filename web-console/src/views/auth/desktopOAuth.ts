import { isAxiosError } from 'axios'
import { computed, ref, type ComputedRef, type Ref } from 'vue'

import {
  cancelOAuthAuthorization,
  completeOAuthAuthorization,
} from '../../api/platform'

/** 登录页承载桌面端 OAuth 授权事务的 query 参数名。 */
export const OAUTH_TRANSACTION_PARAM = 'oauth_transaction'

/** 桌面客户端注册的回调地址前缀，用于校验后端返回的 redirectUrl。 */
export const DESKTOP_CALLBACK_PREFIX = 'yuzhhai://oauth/callback'
export const DESKTOP_LOOPBACK_CALLBACK_PATH = '/auth/callback'

const TRANSACTION_STORAGE_KEY = 'yzh_desktop_oauth_transaction'
const PASSWORD_SETUP_STORAGE_KEY = 'yzh_desktop_password_setup'

/** 为浏览器完成自定义协议解析并把回调交给桌面端预留时间。 */
export const DESKTOP_AUTH_WINDOW_CLOSE_DELAY_MS = 60_000

const TRANSACTION_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

type DesktopWindowCloseScheduler = (callback: () => void, delay: number) => unknown
type DesktopWindowCloseCanceller = (handle: unknown) => void

let disarmPendingDesktopClose: (() => void) | null = null

/**
 * 只允许已注册的自定义协议，或 OAuth 原生应用使用的 IPv4 loopback 回调。
 * Loopback 必须带动态端口并使用固定路径，禁止 localhost、用户信息、片段和其他主机。
 */
export function isAllowedDesktopCallbackUrl(value: unknown): value is string {
  if (typeof value !== 'string') return false
  try {
    const url = new URL(value)
    if (url.username || url.password || url.hash) return false
    if (url.protocol === 'yuzhhai:') {
      return url.hostname === 'oauth'
        && url.pathname === '/callback'
        && !url.port
    }
    return url.protocol === 'http:'
      && url.hostname === '127.0.0.1'
      && /^\d+$/.test(url.port)
      && Number(url.port) > 0
      && Number(url.port) <= 65_535
      && url.pathname === DESKTOP_LOOPBACK_CALLBACK_PATH
  } catch {
    return false
  }
}

/**
 * 系统浏览器对 window.close() 的支持取决于标签页的打开方式，因此这里做延迟的
 * best-effort 关闭；即使浏览器拦截关闭，事务和网页登录会话也已经被清理。
 */
export function scheduleDesktopAuthorizationWindowClose(
  closeWindow: () => void = () => window.close(),
  schedule: DesktopWindowCloseScheduler = (callback, delay) => window.setTimeout(callback, delay),
): unknown {
  return schedule(closeWindow, DESKTOP_AUTH_WINDOW_CLOSE_DELAY_MS)
}

/**
 * 浏览器失去焦点既可能是桌面端接管，也可能只是弹出了外部协议确认框。
 * 因此保留足够长的确认时间，并在浏览器重新获得焦点时取消关闭，避免确认框
 * 尚未处理就把授权页强制关掉。
 */
export function armDesktopAuthorizationWindowClose(
  closeWindow: () => void = () => window.close(),
  schedule: DesktopWindowCloseScheduler = (callback, delay) => window.setTimeout(callback, delay),
  eventTarget: EventTarget = window,
  cancel: DesktopWindowCloseCanceller = handle => window.clearTimeout(handle as number),
): () => void {
  let armed = true
  let closeHandle: unknown = null

  const cancelScheduledClose = () => {
    if (closeHandle === null) return
    cancel(closeHandle)
    closeHandle = null
  }
  const handleBlur = () => {
    if (!armed || closeHandle !== null) return
    closeHandle = scheduleDesktopAuthorizationWindowClose(closeWindow, schedule)
  }
  const handleFocus = () => cancelScheduledClose()

  eventTarget.addEventListener('blur', handleBlur)
  eventTarget.addEventListener('focus', handleFocus)
  return () => {
    if (!armed) return
    armed = false
    cancelScheduledClose()
    eventTarget.removeEventListener('blur', handleBlur)
    eventTarget.removeEventListener('focus', handleFocus)
  }
}

function removeOAuthTransactionFromAddressBar(): void {
  try {
    const currentUrl = new URL(window.location.href)
    currentUrl.searchParams.delete(OAUTH_TRANSACTION_PARAM)
    window.history.replaceState(
      window.history.state,
      '',
      `${currentUrl.pathname}${currentUrl.search}${currentUrl.hash}`,
    )
  } catch {
    // 地址栏清理只是防止残留页面刷新后复用事务，不应阻止桌面回调。
  }
}

/**
 * 顶层跳转出口。集中在这里便于测试替换，避免测试环境触发 jsdom 的导航限制。
 * 只允许跳转到桌面客户端回调地址，防止把浏览器带去任意第三方地址。
 */
export const desktopRedirect = {
  to(url: unknown): void {
    if (!isAllowedDesktopCallbackUrl(url)) {
      throw new Error('invalid_desktop_callback_url')
    }
    removeOAuthTransactionFromAddressBar()
    disarmPendingDesktopClose?.()
    disarmPendingDesktopClose = armDesktopAuthorizationWindowClose()
    try {
      // 用替换而不是新增一条历史记录的方式离开授权页。这样在交接给桌面端之后，
      // Chromium 才能自动关闭只有一条历史记录的回调标签页。
      window.location.replace(url)
    } catch (error) {
      disarmPendingDesktopClose()
      disarmPendingDesktopClose = null
      throw error
    }
  },
}

export function isValidOAuthTransaction(value: unknown): value is string {
  return typeof value === 'string' && TRANSACTION_PATTERN.test(value.trim())
}

/** 从路由 query 中取出合法的 oauth_transaction，非法值一律忽略。 */
export function readOAuthTransaction(query: Record<string, unknown> | undefined): string | null {
  if (!query) return null
  const raw = query[OAUTH_TRANSACTION_PARAM]
  const value = Array.isArray(raw) ? raw[0] : raw
  return isValidOAuthTransaction(value) ? value.trim() : null
}

/**
 * 事务 id 只用于同一次桌面端登录，会话级保存即可。
 * 这样验证码/滑块挑战、找回密码等过程中的路由跳转不会丢失它。
 */
export function rememberOAuthTransaction(transactionId: string): void {
  if (!isValidOAuthTransaction(transactionId)) return
  try {
    sessionStorage.setItem(TRANSACTION_STORAGE_KEY, transactionId.trim())
  } catch {
    /* 隐私模式下不可写，忽略即可 */
  }
}

export function recallOAuthTransaction(): string | null {
  try {
    const stored = sessionStorage.getItem(TRANSACTION_STORAGE_KEY)
    return isValidOAuthTransaction(stored) ? stored.trim() : null
  } catch {
    return null
  }
}

export function forgetOAuthTransaction(): void {
  try {
    sessionStorage.removeItem(TRANSACTION_STORAGE_KEY)
  } catch {
    /* 忽略 */
  }
}

interface DesktopPasswordSetupContext {
  transactionId: string
  userId: string
}

let inMemoryPasswordSetup: DesktopPasswordSetupContext | null = null

/** 仅同一浏览器标签页、同一次验证码登录得到的账号可续接首次设密授权。 */
export function rememberDesktopPasswordSetup(transactionId: string, userId: string): void {
  if (!isValidOAuthTransaction(transactionId) || !isValidOAuthTransaction(userId)) return
  inMemoryPasswordSetup = { transactionId, userId }
  try {
    sessionStorage.setItem(PASSWORD_SETUP_STORAGE_KEY, JSON.stringify(inMemoryPasswordSetup))
  } catch {
    /* 隐私模式下不可写，设密页会拒绝自动授权。 */
  }
}

export function recallDesktopPasswordSetup(): DesktopPasswordSetupContext | null {
  if (inMemoryPasswordSetup) return inMemoryPasswordSetup
  try {
    const raw = sessionStorage.getItem(PASSWORD_SETUP_STORAGE_KEY)
    if (!raw) return inMemoryPasswordSetup
    const parsed = JSON.parse(raw) as Partial<DesktopPasswordSetupContext>
    return isValidOAuthTransaction(parsed.transactionId)
      && isValidOAuthTransaction(parsed.userId)
      ? { transactionId: parsed.transactionId, userId: parsed.userId }
      : inMemoryPasswordSetup
  } catch {
    return inMemoryPasswordSetup
  }
}

export function forgetDesktopPasswordSetup(): void {
  inMemoryPasswordSetup = null
  try {
    sessionStorage.removeItem(PASSWORD_SETUP_STORAGE_KEY)
  } catch {
    /* ignore */
  }
}

interface BackendAuthorizationError {
  error?: string
  error_description?: string
}

/** 只依据后端 error 字段（缺失时退回状态码）给出稳定中文文案。 */
export function desktopOAuthErrorMessage(error: unknown): string {
  const status = isAxiosError(error) ? error.response?.status : undefined
  const code = isAxiosError<BackendAuthorizationError>(error)
    ? error.response?.data?.error
    : undefined

  if (code === 'invalid_request' || status === 410) {
    return '本次桌面端登录已失效，请在桌面端重新发起登录。'
  }
  if (code === 'access_denied' || status === 403) {
    return '登录授权未通过，请在桌面端重新发起登录。'
  }
  if (code === 'invalid_token' || status === 401) {
    return '登录状态已失效，请重新登录后再试。'
  }
  return '暂时无法返回桌面端，请稍后重试。'
}

export interface DesktopOAuthController {
  active: ComputedRef<boolean>
  readyToReturn: ComputedRef<boolean>
  pending: Ref<boolean>
  error: Ref<string>
  callbackUrl: Ref<string | null>
  completeAuthorization: () => Promise<void>
  cancelAuthorization: () => Promise<void>
}

/**
 * 桌面端 OAuth 授权收尾逻辑。
 *
 * - `completeAuthorization()`：调用 complete 接口并把浏览器顶层跳到 redirectUrl。
 * - `cancelAuthorization()`：用户主动放弃时调用 cancel 接口并回到桌面端。
 *
 * 两条路径都不会把 code / token 写入本地存储或页面文本。
 */
export function useDesktopOAuth(
  transactionId: () => string | null,
  redirect: (url: string) => void = (url) => desktopRedirect.to(url),
  beforeCompleteRedirect: () => Promise<void> | void = () => undefined,
): DesktopOAuthController {
  const pending = ref(false)
  const error = ref('')
  const finished = ref(false)
  const callbackUrl = ref<string | null>(null)
  const active = computed(() => !finished.value && isValidOAuthTransaction(transactionId()))
  const readyToReturn = computed(() => callbackUrl.value !== null)

  function prepareDesktopReturn(url: unknown): string {
    if (!isAllowedDesktopCallbackUrl(url)) {
      throw new Error('invalid_desktop_callback_url')
    }
    callbackUrl.value = url
    finished.value = true
    return url
  }

  function tryOpenDesktop(url: string) {
    try {
      redirect(url)
    } catch {
      error.value = '浏览器未能自动打开桌面端，请点击“打开桌面端”重试。'
    }
  }

  async function completeAuthorization() {
    const transaction = transactionId()
    if (!isValidOAuthTransaction(transaction) || pending.value) return
    pending.value = true
    error.value = ''
    try {
      const result = await completeOAuthAuthorization(transaction.trim())
      forgetOAuthTransaction()
      try {
        await beforeCompleteRedirect()
      } catch {
        // 授权码已经签发，浏览器会话清理失败不能把桌面端卡在等待状态。
      }
      tryOpenDesktop(prepareDesktopReturn(result.redirectUrl))
    } catch (failure) {
      if (isAxiosError(failure) && failure.response?.status === 410) {
        forgetOAuthTransaction()
      }
      error.value = desktopOAuthErrorMessage(failure)
    } finally {
      pending.value = false
    }
  }

  async function cancelAuthorization() {
    const transaction = transactionId()
    if (!isValidOAuthTransaction(transaction) || pending.value) return
    pending.value = true
    error.value = ''
    try {
      const result = await cancelOAuthAuthorization(transaction.trim())
      forgetOAuthTransaction()
      if (result?.redirectUrl) {
        tryOpenDesktop(prepareDesktopReturn(result.redirectUrl))
      } else {
        finished.value = true
      }
    } catch (failure) {
      error.value = desktopOAuthErrorMessage(failure)
    } finally {
      pending.value = false
    }
  }

  return {
    active,
    readyToReturn,
    pending,
    error,
    callbackUrl,
    completeAuthorization,
    cancelAuthorization,
  }
}
