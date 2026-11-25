import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { createConsoleRouter } from '../../router'
import { useAuthStore } from '../../stores/auth'
import { desktopRedirect, forgetDesktopPasswordSetup, recallDesktopPasswordSetup } from './desktopOAuth'
import { forgetPendingEmailRegistration } from './pendingEmailRegistration'
import { forgetPendingPhoneRegistration } from './pendingPhoneRegistration'
import DesktopLoginView from './DesktopLoginView.vue'
import LoginView from './LoginView.vue'

const api = vi.hoisted(() => ({
  login: vi.fn(),
  identityLogin: vi.fn(),
  emailIdentityLogin: vi.fn(),
  requestEmailChallenge: vi.fn(),
  getPublishedLegalDocument: vi.fn(),
  logout: vi.fn(),
  completeOAuthAuthorization: vi.fn(),
  cancelOAuthAuthorization: vi.fn(),
}))

vi.mock('../../api/platform', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/platform')>()),
  login: api.login,
  identityLogin: api.identityLogin,
  emailIdentityLogin: api.emailIdentityLogin,
  requestEmailChallenge: api.requestEmailChallenge,
  getPublishedLegalDocument: api.getPublishedLegalDocument,
  logout: api.logout,
  completeOAuthAuthorization: api.completeOAuthAuthorization,
  cancelOAuthAuthorization: api.cancelOAuthAuthorization,
}))

const TRANSACTION = '1f0f2c34-5b6a-4d7e-8f90-1a2b3c4d5e6f'
const INVITATION_CODE = 'Aa0Bb1Cc2Dd3Ee4Ff5Gg6Hh7Ii8Jj9Kk'
const REGISTRATION_CHALLENGE = '2f0f2c34-5b6a-4d7e-8f90-1a2b3c4d5e6f'
const REDIRECT_URL = 'http://127.0.0.1:43210/auth/callback?code=code-123&state=state-456'
const CANCEL_REDIRECT_URL = 'http://127.0.0.1:43210/auth/callback?error=access_denied&state=state-456'

const session = {
  accessToken: 'oauth-access-token',
  expiresIn: 900,
  user: {
    id: '71000000-0000-4000-8000-000000000001',
    tenantId: '61000000-0000-4000-8000-000000000001',
    email: 'user@local.test',
    displayName: 'OAuth 用户',
    role: 'USER' as const,
    status: 'ACTIVE',
    passwordSet: true,
  },
}

async function mountLogin(transaction: string | null, invite?: string) {
  const router = createConsoleRouter(createMemoryHistory())
  const path = transaction ? `/desktop-login?oauth_transaction=${transaction}` : '/login'
  await router.push(invite === undefined ? path : `${path}${transaction ? '&' : '?'}invite=${encodeURIComponent(invite)}`)
  await router.isReady()
  const wrapper = mount(transaction ? DesktopLoginView : LoginView, {
    global: { plugins: [router, ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

async function submitPasswordLogin(wrapper: VueWrapper) {
  await wrapper.get('[data-test=login-identifier]').setValue('user@local.test')
  await wrapper.get('[data-test=login-password]').setValue('Desktop!2345')
  // 密码登录是老账号路径：后端登录不校验同意，页面上也不再出现同意勾选。
  expect(wrapper.find('[data-test=login-consent]').exists()).toBe(false)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

describe('desktop OAuth authorization on the login page', () => {
  let redirect: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    sessionStorage.clear()
    forgetDesktopPasswordSetup()
    forgetPendingEmailRegistration()
    forgetPendingPhoneRegistration()
    document.cookie = 'yzh_csrf=; Max-Age=0; Path=/'
    api.login.mockResolvedValue(session)
    api.requestEmailChallenge.mockResolvedValue({
      id: REGISTRATION_CHALLENGE,
      maskedEmail: 'u***@local.test',
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    })
    api.getPublishedLegalDocument.mockImplementation(async (kind: string) => ({
      kind,
      version: '2026-09-15',
      title: kind === 'TERMS' ? '平台服务协议' : '隐私政策',
      content: { sections: [] },
    }))
    api.logout.mockResolvedValue(undefined)
    api.completeOAuthAuthorization.mockResolvedValue({
      redirectUrl: REDIRECT_URL,
      scopes: ['openid', 'profile'],
      expiresAt: '2026-09-12T04:10:00Z',
    })
    api.cancelOAuthAuthorization.mockResolvedValue({ redirectUrl: CANCEL_REDIRECT_URL })
    redirect = vi.spyOn(desktopRedirect, 'to').mockImplementation(() => undefined)
  })

  afterEach(() => {
    sessionStorage.clear()
    document.cookie = 'yzh_csrf=; Max-Age=0; Path=/'
    redirect.mockRestore()
    forgetDesktopPasswordSetup()
    forgetPendingEmailRegistration()
    forgetPendingPhoneRegistration()
  })

  it('completes the authorization and redirects after a successful login', async () => {
    const wrapper = await mountLogin(TRANSACTION)

    expect(wrapper.get('.auth-shell').classes()).toContain('auth-shell--desktop')
    expect(wrapper.find('.auth-brand-panel').exists()).toBe(false)
    expect(wrapper.get('#login-title').text()).toBe('欢迎回来')
    expect(wrapper.find('[data-test=oauth-desktop]').exists()).toBe(false)

    await submitPasswordLogin(wrapper)

    expect(api.login).toHaveBeenCalledWith('user@local.test', 'Desktop!2345')
    expect(api.completeOAuthAuthorization).toHaveBeenCalledWith(TRANSACTION)
    expect(api.logout).toHaveBeenCalledOnce()
    expect(redirect).toHaveBeenCalledWith(REDIRECT_URL)
    expect(api.logout.mock.invocationCallOrder[0]).toBeLessThan(
      redirect.mock.invocationCallOrder[0],
    )
    expect(api.cancelOAuthAuthorization).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test=oauth-return]').text()).toContain('网页授权已完成')
    expect(wrapper.text()).not.toContain('code-123')

    const openDesktopLink = wrapper.get('[data-test=oauth-open-desktop]')
    expect(openDesktopLink.element.tagName).toBe('A')
    expect(openDesktopLink.attributes('href')).toBe(REDIRECT_URL)
    expect(redirect).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('clears an existing browser account without silently authorizing it', async () => {
    useAuthStore().setSession(session.accessToken, session.user)

    const wrapper = await mountLogin(TRANSACTION)

    expect(api.logout).toHaveBeenCalledOnce()
    expect(useAuthStore().isAuthenticated).toBe(false)
    expect(api.completeOAuthAuthorization).not.toHaveBeenCalled()
    expect(redirect).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('keeps an invitation through the registration link and clears it after an existing-account login', async () => {
    const router = createConsoleRouter(createMemoryHistory())
    await router.push(`/invite-register?invite=${INVITATION_CODE}`)
    await router.isReady()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.invite).toBe(INVITATION_CODE)
    const wrapper = mount(LoginView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    expect(sessionStorage.getItem('yzhh.invitation-code')).toBe(INVITATION_CODE)

    await submitPasswordLogin(wrapper)
    expect(sessionStorage.getItem('yzhh.invitation-code')).toBeNull()
    wrapper.unmount()
  })

  it('clears a stale saved invitation when a new link explicitly provides an invalid code', async () => {
    sessionStorage.setItem('yzhh.invitation-code', INVITATION_CODE)
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/invite-register?invite=invalid')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    expect(sessionStorage.getItem('yzhh.invitation-code')).toBeNull()
    wrapper.unmount()
  })

  it('retains the invitation when a passwordless signed-in account follows a registration link', async () => {
    useAuthStore().setSession(session.accessToken, { ...session.user, passwordSet: false })
    const router = createConsoleRouter(createMemoryHistory())
    await router.push(`/register?invite=${INVITATION_CODE}`)
    await router.isReady()
    expect(router.currentRoute.value.path).toBe('/set-password')
    expect(router.currentRoute.value.query.invite).toBe(INVITATION_CODE)
  })

  it('sends a passwordless desktop login to the dedicated password page before authorizing', async () => {
    api.login.mockResolvedValue({
      ...session,
      user: { ...session.user, passwordSet: false },
    })
    const wrapper = await mountLogin(TRANSACTION, INVITATION_CODE)

    await submitPasswordLogin(wrapper)

    expect(wrapper.find('[data-test=oauth-return]').exists()).toBe(false)
    expect(api.completeOAuthAuthorization).not.toHaveBeenCalled()
    await vi.waitFor(() => {
      expect(wrapper.vm.$router.currentRoute.value.path).toBe('/desktop-set-password')
    })
    expect(wrapper.vm.$router.currentRoute.value.query.oauth_transaction).toBe(TRANSACTION)
    expect(sessionStorage.getItem('yzhh.invitation-code')).toBe(INVITATION_CODE)
    expect(recallDesktopPasswordSetup()).toEqual({
      transactionId: TRANSACTION,
      userId: session.user.id,
    })
    wrapper.unmount()
  })

  it.each([null, TRANSACTION])('requires no verification code on the login entry %s', async (transaction) => {
    const wrapper = await mountLogin(transaction)
    expect(wrapper.find('[data-test=login-password]').exists()).toBe(true)
    expect(wrapper.find('[data-test=email-send]').exists()).toBe(false)
    expect(wrapper.find('[data-test=sms-send]').exists()).toBe(false)
    await submitPasswordLogin(wrapper)
    expect(api.identityLogin).not.toHaveBeenCalled()
    expect(api.emailIdentityLogin).not.toHaveBeenCalled()
    expect(api.requestEmailChallenge).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('clears a refresh-cookie browser session without silently authorizing it', async () => {
    document.cookie = 'yzh_csrf=stale-session; Path=/'

    const wrapper = await mountLogin(TRANSACTION)

    expect(api.logout).toHaveBeenCalledOnce()
    expect(api.completeOAuthAuthorization).not.toHaveBeenCalled()
    expect(redirect).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('shows a stable message when the transaction is already finished', async () => {
    api.completeOAuthAuthorization.mockRejectedValue(Object.assign(new Error('gone'), {
      isAxiosError: true,
      response: {
        status: 410,
        data: {
          error: 'invalid_request',
          error_description: 'The login transaction is unknown or already finished.',
        },
      },
    }))
    const wrapper = await mountLogin(TRANSACTION)

    await submitPasswordLogin(wrapper)

    expect(redirect).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test=oauth-error]').text())
      .toBe('本次桌面端登录已失效，请在桌面端重新发起登录。')
    wrapper.unmount()
  })

  it('keeps the plain login page when no authorization transaction is present', async () => {
    const wrapper = await mountLogin(null)

    expect(wrapper.get('.auth-shell').classes()).not.toContain('auth-shell--desktop')
    expect(wrapper.find('.auth-brand-panel').exists()).toBe(true)
    expect(wrapper.find('[data-test=oauth-desktop]').exists()).toBe(false)
    expect(wrapper.find('[data-test=oauth-cancel]').exists()).toBe(false)
    expect(api.completeOAuthAuthorization).not.toHaveBeenCalled()
    expect(api.cancelOAuthAuthorization).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('redirects legacy desktop authorization links to the dedicated page', async () => {
    const router = createConsoleRouter(createMemoryHistory())

    await router.push(`/login?oauth_transaction=${TRANSACTION}`)

    expect(router.currentRoute.value.path).toBe('/desktop-login')
    expect(router.currentRoute.value.query.oauth_transaction).toBe(TRANSACTION)
  })

  it('rejects a dedicated desktop login page without a valid transaction', async () => {
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/desktop-login?oauth_transaction=invalid')
    await router.isReady()
    const wrapper = mount(DesktopLoginView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.get('[data-test=oauth-invalid]').text()).toContain('登录请求已失效')
    expect(wrapper.find('[data-test=login-submit]').exists()).toBe(false)
    expect(api.login).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
