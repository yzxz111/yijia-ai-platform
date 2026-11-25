import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { createConsoleRouter } from '../../router'
import { desktopRedirect, forgetDesktopPasswordSetup, recallDesktopPasswordSetup } from './desktopOAuth'
import LoginHandoffWaitingView from './LoginHandoffWaitingView.vue'

const api = vi.hoisted(() => ({
  getLoginHandoffStatus: vi.fn(),
  completeLoginHandoff: vi.fn(),
  cancelLoginHandoff: vi.fn(),
  completeOAuthAuthorization: vi.fn(),
  cancelOAuthAuthorization: vi.fn(),
  logout: vi.fn(),
}))

vi.mock('../../api/platform', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/platform')>()),
  getLoginHandoffStatus: api.getLoginHandoffStatus,
  completeLoginHandoff: api.completeLoginHandoff,
  cancelLoginHandoff: api.cancelLoginHandoff,
  completeOAuthAuthorization: api.completeOAuthAuthorization,
  cancelOAuthAuthorization: api.cancelOAuthAuthorization,
  logout: api.logout,
}))

const REQUEST_ID = '71000000-0000-4000-8000-000000000001'
const TRANSACTION = '1f0f2c34-5b6a-4d7e-8f90-1a2b3c4d5e6f'
const REDIRECT_URL = 'http://127.0.0.1:43210/auth/callback?code=code-123&state=state-456'
const session = {
  accessToken: 'handoff-access-token',
  expiresIn: 900,
  user: {
    id: '72000000-0000-4000-8000-000000000001',
    tenantId: '61000000-0000-4000-8000-000000000001',
    email: 'user@local.test',
    displayName: '桌面端用户',
    role: 'USER' as const,
    status: 'ACTIVE',
    passwordSet: true,
  },
}

describe('desktop OAuth after a login handoff', () => {
  let redirect: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    forgetDesktopPasswordSetup()
    api.getLoginHandoffStatus.mockResolvedValue({
      requestId: REQUEST_ID,
      status: 'APPROVED',
      expiresAt: '2026-09-15T10:00:00Z',
    })
    api.completeLoginHandoff.mockResolvedValue(session)
    api.completeOAuthAuthorization.mockResolvedValue({ redirectUrl: REDIRECT_URL })
    api.logout.mockResolvedValue(undefined)
    redirect = vi.spyOn(desktopRedirect, 'to').mockImplementation(() => undefined)
  })

  afterEach(() => {
    redirect.mockRestore()
    forgetDesktopPasswordSetup()
  })

  it('continues the same desktop transaction after the original device approves', async () => {
    const router = createConsoleRouter(createMemoryHistory())
    await router.push({
      path: `/login/handoff/${REQUEST_ID}`,
      query: { oauth_transaction: TRANSACTION },
    })
    await router.isReady()
    const wrapper = mount(LoginHandoffWaitingView, {
      global: { plugins: [router, ElementPlus] },
    })

    await flushPromises()
    await flushPromises()

    expect(api.completeLoginHandoff).toHaveBeenCalledWith(REQUEST_ID)
    expect(api.completeOAuthAuthorization).toHaveBeenCalledWith(TRANSACTION)
    expect(api.logout).toHaveBeenCalledOnce()
    expect(redirect).toHaveBeenCalledWith(REDIRECT_URL)
    expect(wrapper.get('[data-test=oauth-return]').text()).toContain('网页授权已完成')

    const openDesktopLink = wrapper.get('[data-test=oauth-open-desktop]')
    expect(openDesktopLink.element.tagName).toBe('A')
    expect(openDesktopLink.attributes('href')).toBe(REDIRECT_URL)
    expect(redirect).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('sends an approved passwordless account to desktop password setup first', async () => {
    api.completeLoginHandoff.mockResolvedValue({
      ...session,
      user: { ...session.user, passwordSet: false },
    })
    const router = createConsoleRouter(createMemoryHistory())
    await router.push({
      path: `/login/handoff/${REQUEST_ID}`,
      query: { oauth_transaction: TRANSACTION },
    })
    await router.isReady()
    const wrapper = mount(LoginHandoffWaitingView, {
      global: { plugins: [router, ElementPlus] },
    })

    await vi.waitFor(() => {
      expect(router.currentRoute.value.path).toBe('/desktop-set-password')
    })
    expect(router.currentRoute.value.query.oauth_transaction).toBe(TRANSACTION)
    expect(recallDesktopPasswordSetup()).toEqual({
      transactionId: TRANSACTION,
      userId: session.user.id,
    })
    expect(api.completeOAuthAuthorization).not.toHaveBeenCalled()
    expect(redirect).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
