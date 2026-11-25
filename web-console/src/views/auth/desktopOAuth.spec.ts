import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  armDesktopAuthorizationWindowClose,
  DESKTOP_CALLBACK_PREFIX,
  DESKTOP_AUTH_WINDOW_CLOSE_DELAY_MS,
  desktopOAuthErrorMessage,
  desktopRedirect,
  forgetOAuthTransaction,
  isAllowedDesktopCallbackUrl,
  isValidOAuthTransaction,
  readOAuthTransaction,
  recallOAuthTransaction,
  rememberOAuthTransaction,
  scheduleDesktopAuthorizationWindowClose,
  useDesktopOAuth,
} from './desktopOAuth'

const api = vi.hoisted(() => ({
  completeOAuthAuthorization: vi.fn(),
  cancelOAuthAuthorization: vi.fn(),
}))

vi.mock('../../api/platform', () => ({
  completeOAuthAuthorization: api.completeOAuthAuthorization,
  cancelOAuthAuthorization: api.cancelOAuthAuthorization,
}))

const TRANSACTION = '1f0f2c34-5b6a-4d7e-8f90-1a2b3c4d5e6f'
const REDIRECT_URL = 'http://127.0.0.1:43210/auth/callback?code=code-123&state=state-456'

function axiosFailure(status: number, error: string) {
  return Object.assign(new Error(error), {
    isAxiosError: true,
    response: { status, data: { error } },
  })
}

describe('desktop OAuth helpers', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    forgetOAuthTransaction()
  })

  it('only accepts a uuid transaction from the query string', () => {
    expect(readOAuthTransaction({ oauth_transaction: TRANSACTION })).toBe(TRANSACTION)
    expect(readOAuthTransaction({ oauth_transaction: [TRANSACTION] })).toBe(TRANSACTION)
    expect(readOAuthTransaction({ oauth_transaction: 'not-a-uuid' })).toBeNull()
    expect(readOAuthTransaction({ oauth_transaction: '../../etc/passwd' })).toBeNull()
    expect(readOAuthTransaction({})).toBeNull()
    expect(readOAuthTransaction(undefined)).toBeNull()
    expect(isValidOAuthTransaction(TRANSACTION)).toBe(true)
    expect(isValidOAuthTransaction('')).toBe(false)
  })

  it('keeps the transaction in session storage so navigations do not drop it', () => {
    rememberOAuthTransaction(TRANSACTION)
    expect(recallOAuthTransaction()).toBe(TRANSACTION)

    forgetOAuthTransaction()
    expect(recallOAuthTransaction()).toBeNull()

    rememberOAuthTransaction('not-a-uuid')
    expect(recallOAuthTransaction()).toBeNull()
  })

  it('refuses to navigate the browser anywhere but the desktop callback', () => {
    expect(isAllowedDesktopCallbackUrl(REDIRECT_URL)).toBe(true)
    expect(isAllowedDesktopCallbackUrl(
      `${DESKTOP_CALLBACK_PREFIX}?code=code-123&state=state-456`,
    )).toBe(true)
    expect(isAllowedDesktopCallbackUrl('http://localhost:43210/auth/callback')).toBe(false)
    expect(isAllowedDesktopCallbackUrl('http://127.0.0.1:43210/other')).toBe(false)
    expect(isAllowedDesktopCallbackUrl('http://127.0.0.1:43210/auth/callback#token')).toBe(false)
    expect(() => desktopRedirect.to('https://evil.example.com/callback')).toThrow()
    expect(() => desktopRedirect.to(undefined)).toThrow()
  })

  it('schedules the authorization tab to close after returning to the desktop', () => {
    const closeWindow = vi.fn()
    const schedule = vi.fn((callback: () => void) => callback())

    scheduleDesktopAuthorizationWindowClose(closeWindow, schedule)

    expect(DESKTOP_AUTH_WINDOW_CLOSE_DELAY_MS).toBeGreaterThanOrEqual(60_000)
    expect(schedule).toHaveBeenCalledWith(closeWindow, DESKTOP_AUTH_WINDOW_CLOSE_DELAY_MS)
    expect(closeWindow).toHaveBeenCalledOnce()
  })

  it('keeps the page open when an external-protocol prompt returns focus to the browser', () => {
    const closeWindow = vi.fn()
    const schedule = vi.fn(() => 42)
    const cancel = vi.fn()
    const eventTarget = new EventTarget()

    const disarm = armDesktopAuthorizationWindowClose(closeWindow, schedule, eventTarget, cancel)

    expect(schedule).not.toHaveBeenCalled()
    eventTarget.dispatchEvent(new Event('blur'))
    eventTarget.dispatchEvent(new Event('blur'))

    expect(schedule).toHaveBeenCalledOnce()
    expect(schedule).toHaveBeenCalledWith(closeWindow, DESKTOP_AUTH_WINDOW_CLOSE_DELAY_MS)
    eventTarget.dispatchEvent(new Event('focus'))
    expect(cancel).toHaveBeenCalledWith(42)

    eventTarget.dispatchEvent(new Event('blur'))
    expect(schedule).toHaveBeenCalledTimes(2)
    disarm()
    expect(cancel).toHaveBeenCalledTimes(2)
  })

  it('maps backend error codes to stable Chinese messages', () => {
    expect(desktopOAuthErrorMessage(axiosFailure(410, 'invalid_request')))
      .toBe('本次桌面端登录已失效，请在桌面端重新发起登录。')
    expect(desktopOAuthErrorMessage(axiosFailure(403, 'access_denied')))
      .toBe('登录授权未通过，请在桌面端重新发起登录。')
    expect(desktopOAuthErrorMessage(axiosFailure(401, 'invalid_token')))
      .toBe('登录状态已失效，请重新登录后再试。')
    expect(desktopOAuthErrorMessage(new Error('boom')))
      .toBe('暂时无法返回桌面端，请稍后重试。')
  })
})

describe('useDesktopOAuth', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    forgetOAuthTransaction()
  })

  it('completes the transaction and redirects to the desktop callback', async () => {
    api.completeOAuthAuthorization.mockResolvedValue({
      redirectUrl: REDIRECT_URL,
      scopes: ['openid', 'profile'],
      expiresAt: '2026-09-12T04:10:00Z',
    })
    const redirect = vi.fn()
    const beforeRedirect = vi.fn()
    const oauth = useDesktopOAuth(() => TRANSACTION, redirect, beforeRedirect)

    expect(oauth.active.value).toBe(true)
    await oauth.completeAuthorization()

    expect(api.completeOAuthAuthorization).toHaveBeenCalledWith(TRANSACTION)
    expect(beforeRedirect).toHaveBeenCalledOnce()
    expect(redirect).toHaveBeenCalledWith(REDIRECT_URL)
    expect(beforeRedirect.mock.invocationCallOrder[0]).toBeLessThan(
      redirect.mock.invocationCallOrder[0],
    )
    expect(oauth.active.value).toBe(false)
    expect(oauth.readyToReturn.value).toBe(true)
    expect(oauth.callbackUrl.value).toBe(REDIRECT_URL)
    expect(oauth.error.value).toBe('')
  })

  it('still returns to the desktop when browser-session cleanup fails', async () => {
    api.completeOAuthAuthorization.mockResolvedValue({ redirectUrl: REDIRECT_URL })
    const redirect = vi.fn()
    const oauth = useDesktopOAuth(
      () => TRANSACTION,
      redirect,
      vi.fn().mockRejectedValue(new Error('logout failed')),
    )

    await oauth.completeAuthorization()

    expect(redirect).toHaveBeenCalledWith(REDIRECT_URL)
    expect(oauth.active.value).toBe(false)
    expect(oauth.readyToReturn.value).toBe(true)
    expect(oauth.error.value).toBe('')
  })

  it('reports an expired transaction without redirecting', async () => {
    api.completeOAuthAuthorization.mockRejectedValue(axiosFailure(410, 'invalid_request'))
    const redirect = vi.fn()
    const oauth = useDesktopOAuth(() => TRANSACTION, redirect)

    await oauth.completeAuthorization()

    expect(redirect).not.toHaveBeenCalled()
    expect(oauth.error.value).toBe('本次桌面端登录已失效，请在桌面端重新发起登录。')
  })

  it('cancels the transaction and follows the desktop redirect when provided', async () => {
    api.cancelOAuthAuthorization.mockResolvedValue({ redirectUrl: REDIRECT_URL })
    const redirect = vi.fn()
    const oauth = useDesktopOAuth(() => TRANSACTION, redirect)

    await oauth.cancelAuthorization()

    expect(api.cancelOAuthAuthorization).toHaveBeenCalledWith(TRANSACTION)
    expect(redirect).toHaveBeenCalledWith(REDIRECT_URL)
    expect(oauth.active.value).toBe(false)
    expect(oauth.readyToReturn.value).toBe(true)
    expect(oauth.error.value).toBe('')
  })

  it('reports a cancellation request that failed before receiving its OAuth redirect', async () => {
    api.cancelOAuthAuthorization.mockRejectedValue(new Error('net::ERR_UNKNOWN_URL_SCHEME'))
    const redirect = vi.fn()
    const oauth = useDesktopOAuth(() => TRANSACTION, redirect)

    await oauth.cancelAuthorization()

    expect(oauth.error.value).toBe('暂时无法返回桌面端，请稍后重试。')
    expect(redirect).not.toHaveBeenCalled()
    expect(oauth.active.value).toBe(true)
  })

  it('stays idle without a transaction', async () => {
    const oauth = useDesktopOAuth(() => null, vi.fn())

    expect(oauth.active.value).toBe(false)
    expect(oauth.readyToReturn.value).toBe(false)
    await oauth.completeAuthorization()
    await oauth.cancelAuthorization()

    expect(api.completeOAuthAuthorization).not.toHaveBeenCalled()
    expect(api.cancelOAuthAuthorization).not.toHaveBeenCalled()
    expect(oauth.callbackUrl.value).toBeNull()
  })
})
