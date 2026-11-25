import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { createConsoleRouter } from '../../router'
import { useAuthStore } from '../../stores/auth'
import {
  desktopRedirect,
  forgetDesktopPasswordSetup,
  rememberDesktopPasswordSetup,
} from './desktopOAuth'
import {
  forgetPendingEmailRegistration,
  recallPendingEmailRegistration,
  rememberPendingEmailRegistration,
} from './pendingEmailRegistration'
import {
  forgetPendingPhoneRegistration,
  recallPendingPhoneRegistration,
  rememberPendingPhoneRegistration,
} from './pendingPhoneRegistration'
import DesktopSetPasswordView from './DesktopSetPasswordView.vue'

const api = vi.hoisted(() => ({
  changePassword: vi.fn(),
  completeEmailRegistration: vi.fn(),
  completePhoneRegistration: vi.fn(),
  completeOAuthAuthorization: vi.fn(),
  logout: vi.fn(),
  restoreSession: vi.fn(),
}))

vi.mock('../../api/http', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/http')>()),
  restoreSession: api.restoreSession,
}))

vi.mock('../../api/platform', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/platform')>()),
  changePassword: api.changePassword,
  completeEmailRegistration: api.completeEmailRegistration,
  completePhoneRegistration: api.completePhoneRegistration,
  completeOAuthAuthorization: api.completeOAuthAuthorization,
  logout: api.logout,
}))

const TRANSACTION = '1f0f2c34-5b6a-4d7e-8f90-1a2b3c4d5e6f'
const CHALLENGE = '2f0f2c34-5b6a-4d7e-8f90-1a2b3c4d5e6f'
const CONSENT_VERSION = '2026-09-23'
const INVITATION_CODE = 'Aa0Bb1Cc2Dd3Ee4Ff5Gg6Hh7Ii8Jj9Kk'
const REDIRECT_URL = 'http://127.0.0.1:43210/auth/callback?code=code-123&state=state-456'
const passwordlessUser = {
  id: '71000000-0000-4000-8000-000000000001',
  tenantId: '61000000-0000-4000-8000-000000000001',
  email: null,
  displayName: '桌面端新用户',
  role: 'USER' as const,
  status: 'ACTIVE',
  passwordSet: false,
}
const passwordSession = {
  accessToken: 'new-password-token',
  expiresIn: 900,
  user: { ...passwordlessUser, passwordSet: true },
}

describe('desktop first password setup', () => {
  let redirect: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    sessionStorage.clear()
    document.cookie = 'yzh_csrf=; Max-Age=0; path=/'
    forgetDesktopPasswordSetup()
    forgetPendingEmailRegistration()
    forgetPendingPhoneRegistration()
    api.changePassword.mockResolvedValue(passwordSession)
    api.completeEmailRegistration.mockResolvedValue(passwordSession)
    api.completePhoneRegistration.mockResolvedValue(passwordSession)
    api.completeOAuthAuthorization.mockResolvedValue({ redirectUrl: REDIRECT_URL })
    api.logout.mockResolvedValue(undefined)
    api.restoreSession.mockResolvedValue(false)
    redirect = vi.spyOn(desktopRedirect, 'to').mockImplementation(() => undefined)
  })

  afterEach(() => {
    redirect.mockRestore()
    sessionStorage.clear()
    document.cookie = 'yzh_csrf=; Max-Age=0; path=/'
    forgetDesktopPasswordSetup()
    forgetPendingEmailRegistration()
    forgetPendingPhoneRegistration()
  })

  async function mountPasswordPage(passwordSet = false, validSetup = true, invite?: string) {
    useAuthStore().setSession('registration-token', { ...passwordlessUser, passwordSet })
    if (validSetup) rememberDesktopPasswordSetup(TRANSACTION, passwordlessUser.id)
    const router = createConsoleRouter(createMemoryHistory())
    await router.push(`/desktop-set-password?oauth_transaction=${TRANSACTION}${invite === undefined ? '' : `&invite=${encodeURIComponent(invite)}`}`)
    await router.isReady()
    const wrapper = mount(DesktopSetPasswordView, {
      global: { plugins: [router, ElementPlus] },
    })
    await flushPromises()
    return { wrapper, router }
  }

  async function mountPendingRegistration(transaction = TRANSACTION) {
    rememberPendingPhoneRegistration({
      code: 'phone_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 600_000).toISOString(),
    }, TRANSACTION, CONSENT_VERSION)
    const router = createConsoleRouter(createMemoryHistory())
    await router.push(`/desktop-set-password?oauth_transaction=${transaction}`)
    await router.isReady()
    const wrapper = mount(DesktopSetPasswordView, {
      global: { plugins: [router, ElementPlus] },
    })
    await flushPromises()
    return { wrapper, router }
  }

  async function mountPendingEmailRegistration(transaction = TRANSACTION) {
    rememberPendingEmailRegistration({
      code: 'email_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 600_000).toISOString(),
    }, TRANSACTION, CONSENT_VERSION, 'new@local.test')
    const router = createConsoleRouter(createMemoryHistory())
    await router.push(`/desktop-set-password?oauth_transaction=${transaction}`)
    await router.isReady()
    const wrapper = mount(DesktopSetPasswordView, {
      global: { plugins: [router, ElementPlus] },
    })
    await flushPromises()
    return { wrapper, router }
  }

  it('does not create or authorize a new account before password submission', async () => {
    const { wrapper, router } = await mountPendingRegistration()

    expect(router.currentRoute.value.path).toBe('/desktop-set-password')
    expect(useAuthStore().isAuthenticated).toBe(false)
    expect(wrapper.find('.desktop-password-shell').exists()).toBe(true)
    expect(wrapper.find('[data-test=desktop-password-form]').exists()).toBe(true)
    expect(api.completePhoneRegistration).not.toHaveBeenCalled()
    expect(api.completeOAuthAuthorization).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('creates the phone account with its password, then completes desktop authorization', async () => {
    let tokenAtAuthorization: string | null = null
    api.completeOAuthAuthorization.mockImplementation(async () => {
      tokenAtAuthorization = useAuthStore().accessToken
      return { redirectUrl: REDIRECT_URL }
    })
    const { wrapper } = await mountPendingRegistration()

    expect(wrapper.find('[data-test=desktop-invitation-code]').exists()).toBe(true)
    expect(wrapper.get('label[for=desktop-invitation-code]').text()).toContain('选填')
    await wrapper.get('[data-test=desktop-first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=desktop-confirm-password]').setValue('Ab1!cdef')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.completePhoneRegistration).toHaveBeenCalledWith(CHALLENGE, 'Ab1!cdef', CONSENT_VERSION, undefined)
    expect(api.changePassword).not.toHaveBeenCalled()
    expect(tokenAtAuthorization).toBe('new-password-token')
    expect(api.completeOAuthAuthorization).toHaveBeenCalledWith(TRANSACTION)
    expect(redirect).toHaveBeenCalledWith(REDIRECT_URL)
    wrapper.unmount()
  })

  it('keeps a verified email unregistered until the desktop password is submitted', async () => {
    const { wrapper, router } = await mountPendingEmailRegistration()

    expect(router.currentRoute.value.path).toBe('/desktop-set-password')
    expect(useAuthStore().isAuthenticated).toBe(false)
    expect(wrapper.find('[data-test=desktop-password-form]').exists()).toBe(true)
    expect(api.completeEmailRegistration).not.toHaveBeenCalled()
    expect(api.completeOAuthAuthorization).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('creates the email account with its password, then completes desktop authorization', async () => {
    let tokenAtAuthorization: string | null = null
    api.completeOAuthAuthorization.mockImplementation(async () => {
      tokenAtAuthorization = useAuthStore().accessToken
      return { redirectUrl: REDIRECT_URL }
    })
    const { wrapper } = await mountPendingEmailRegistration()

    expect(wrapper.find('[data-test=desktop-invitation-code]').exists()).toBe(true)
    await wrapper.get('[data-test=desktop-first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=desktop-confirm-password]').setValue('Ab1!cdef')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.completeEmailRegistration).toHaveBeenCalledWith(CHALLENGE, 'Ab1!cdef', CONSENT_VERSION, undefined)
    expect(api.completePhoneRegistration).not.toHaveBeenCalled()
    expect(api.changePassword).not.toHaveBeenCalled()
    expect(tokenAtAuthorization).toBe('new-password-token')
    expect(api.completeOAuthAuthorization).toHaveBeenCalledWith(TRANSACTION)
    expect(redirect).toHaveBeenCalledWith(REDIRECT_URL)
    wrapper.unmount()
  })

  it('retries desktop authorization without registering the email account twice', async () => {
    api.completeOAuthAuthorization.mockRejectedValueOnce(new Error('network'))
    const { wrapper } = await mountPendingEmailRegistration()

    await wrapper.get('[data-test=desktop-first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=desktop-confirm-password]').setValue('Ab1!cdef')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.completeEmailRegistration).toHaveBeenCalledOnce()
    expect(wrapper.get('[data-test=desktop-password-oauth-error]').text())
      .toContain('暂时无法返回桌面端')
    await wrapper.get('[data-test=desktop-password-retry]').trigger('click')
    await flushPromises()

    expect(api.completeEmailRegistration).toHaveBeenCalledOnce()
    expect(api.completeOAuthAuthorization).toHaveBeenCalledTimes(2)
    expect(redirect).toHaveBeenCalledWith(REDIRECT_URL)
    wrapper.unmount()
  })

  it('keeps the desktop user signed out when email registration conflicts', async () => {
    api.completeEmailRegistration.mockRejectedValueOnce(Object.assign(new Error('conflict'), {
      isAxiosError: true,
      response: { status: 409, data: { code: 'email_unavailable' } },
    }))
    const { wrapper } = await mountPendingEmailRegistration()

    await wrapper.get('[data-test=desktop-first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=desktop-confirm-password]').setValue('Ab1!cdef')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(useAuthStore().isAuthenticated).toBe(false)
    expect(api.completeOAuthAuthorization).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test=desktop-password-error]').text())
      .toContain('该邮箱已注册')
    expect(recallPendingEmailRegistration()).toBeNull()
    expect(wrapper.find('[data-test=desktop-password-form]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('clears an invalid email challenge and stops further registration attempts', async () => {
    api.completeEmailRegistration.mockRejectedValueOnce(Object.assign(new Error('invalid'), {
      isAxiosError: true,
      response: { status: 422, data: { code: 'invalid_registration_challenge' } },
    }))
    const { wrapper } = await mountPendingEmailRegistration()

    await wrapper.get('[data-test=desktop-first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=desktop-confirm-password]').setValue('Ab1!cdef')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(recallPendingEmailRegistration()).toBeNull()
    expect(wrapper.find('[data-test=desktop-password-form]').exists()).toBe(false)
    expect(wrapper.get('[data-test=desktop-password-error]').text())
      .toContain('注册验证已失效')
    expect(api.completeEmailRegistration).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it.each([
    ['weak password', Object.assign(new Error('invalid password'), {
      isAxiosError: true,
      response: { status: 422, data: { code: 'weak_password' } },
    })],
    ['network failure', new Error('network')],
  ])('keeps the verified email available for retry after %s', async (_, failure) => {
    api.completeEmailRegistration.mockRejectedValueOnce(failure)
    const { wrapper } = await mountPendingEmailRegistration()

    await wrapper.get('[data-test=desktop-first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=desktop-confirm-password]').setValue('Ab1!cdef')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(recallPendingEmailRegistration()?.challengeId).toBe(CHALLENGE)
    expect(wrapper.find('[data-test=desktop-password-form]').exists()).toBe(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.completeEmailRegistration).toHaveBeenCalledTimes(2)
    expect(api.completeOAuthAuthorization).toHaveBeenCalledWith(TRANSACTION)
    wrapper.unmount()
  })

  it('recovers a completed email registration from its refresh cookie after a reload', async () => {
    document.cookie = 'yzh_csrf=recovered; path=/'
    api.restoreSession.mockImplementation(async () => {
      useAuthStore().setSession('recovered-token', {
        ...passwordSession.user, email: 'NEW@LOCAL.TEST',
      })
      return true
    })

    const { wrapper } = await mountPendingEmailRegistration()

    expect(api.restoreSession).toHaveBeenCalledOnce()
    expect(api.completeEmailRegistration).not.toHaveBeenCalled()
    expect(api.completeOAuthAuthorization).toHaveBeenCalledWith(TRANSACTION)
    expect(recallPendingEmailRegistration()).toBeNull()
    expect(redirect).toHaveBeenCalledWith(REDIRECT_URL)
    wrapper.unmount()
  })

  it('does not authorize a different refresh-cookie account for a pending email', async () => {
    document.cookie = 'yzh_csrf=other-account; path=/'
    api.restoreSession.mockImplementation(async () => {
      useAuthStore().setSession('other-account-token', {
        ...passwordSession.user, email: 'other@local.test',
      })
      return true
    })

    const { wrapper } = await mountPendingEmailRegistration()

    expect(api.restoreSession).toHaveBeenCalledOnce()
    expect(api.logout).toHaveBeenCalledOnce()
    expect(useAuthStore().isAuthenticated).toBe(false)
    expect(recallPendingEmailRegistration()?.challengeId).toBe(CHALLENGE)
    expect(wrapper.find('[data-test=desktop-password-form]').exists()).toBe(true)
    expect(api.completeOAuthAuthorization).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('does not allow an email registration to finish through another desktop transaction', async () => {
    const otherTransaction = '3f0f2c34-5b6a-4d7e-8f90-1a2b3c4d5e6f'
    const { wrapper, router } = await mountPendingEmailRegistration(otherTransaction)

    expect(router.currentRoute.value.path).toBe('/desktop-login')
    expect(wrapper.find('[data-test=desktop-password-form]').exists()).toBe(false)
    expect(api.completeEmailRegistration).not.toHaveBeenCalled()
    expect(api.completeOAuthAuthorization).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('can retry desktop authorization without creating the account twice', async () => {
    api.completeOAuthAuthorization.mockRejectedValueOnce(new Error('network'))
    const { wrapper } = await mountPendingRegistration()

    await wrapper.get('[data-test=desktop-first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=desktop-confirm-password]').setValue('Ab1!cdef')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.completePhoneRegistration).toHaveBeenCalledOnce()
    expect(wrapper.get('[data-test=desktop-password-oauth-error]').text())
      .toContain('暂时无法返回桌面端')
    await wrapper.get('[data-test=desktop-password-retry]').trigger('click')
    await flushPromises()

    expect(api.completePhoneRegistration).toHaveBeenCalledOnce()
    expect(api.completeOAuthAuthorization).toHaveBeenCalledTimes(2)
    expect(redirect).toHaveBeenCalledWith(REDIRECT_URL)
    wrapper.unmount()
  })

  it('does not allow a pending registration to finish through another desktop transaction', async () => {
    const otherTransaction = '3f0f2c34-5b6a-4d7e-8f90-1a2b3c4d5e6f'
    const { wrapper, router } = await mountPendingRegistration(otherTransaction)

    expect(router.currentRoute.value.path).toBe('/desktop-login')
    expect(wrapper.find('[data-test=desktop-password-form]').exists()).toBe(false)
    expect(api.completePhoneRegistration).not.toHaveBeenCalled()
    expect(api.completeOAuthAuthorization).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('uses an independent white page and the same first-password rule', async () => {
    const { wrapper, router } = await mountPasswordPage()

    expect(router.currentRoute.value.path).toBe('/desktop-set-password')
    expect(wrapper.find('.desktop-password-shell').exists()).toBe(true)
    expect(wrapper.find('.auth-brand-panel').exists()).toBe(false)
    expect(wrapper.get('[data-test=desktop-password-form]').text())
      .toContain('不会跳转到中台工作台')
    await wrapper.get('[data-test=desktop-first-password]').setValue('Ab1!cde')
    await wrapper.get('[data-test=desktop-confirm-password]').setValue('Ab1!cde')
    expect(wrapper.get('[data-test=desktop-password-submit]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('sets the password, authorizes with the new session, and returns to desktop', async () => {
    let tokenAtAuthorization: string | null = null
    api.completeOAuthAuthorization.mockImplementation(async () => {
      tokenAtAuthorization = useAuthStore().accessToken
      return { redirectUrl: REDIRECT_URL }
    })
    const { wrapper, router } = await mountPasswordPage()

    expect(wrapper.find('[data-test=desktop-invitation-code]').exists()).toBe(true)

    await wrapper.get('[data-test=desktop-first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=desktop-confirm-password]').setValue('Ab1!cdef')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.changePassword).toHaveBeenCalledWith('', 'Ab1!cdef', undefined, undefined)
    expect(tokenAtAuthorization).toBe('new-password-token')
    expect(api.completeOAuthAuthorization).toHaveBeenCalledWith(TRANSACTION)
    expect(api.logout).toHaveBeenCalledOnce()
    expect(redirect).toHaveBeenCalledWith(REDIRECT_URL)
    expect(router.currentRoute.value.path).toBe('/desktop-set-password')
    expect(wrapper.get('[data-test=oauth-return]').text()).toContain('网页授权已完成')
    wrapper.unmount()
  })

  it('validates and submits an invitation code for an authenticated first-password desktop account', async () => {
    const { wrapper } = await mountPasswordPage()
    await wrapper.get('[data-test=desktop-first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=desktop-confirm-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=desktop-invitation-code]').setValue('invalid')
    expect(wrapper.get('[data-test=desktop-password-submit]').attributes('disabled')).toBeDefined()
    expect(api.changePassword).not.toHaveBeenCalled()

    await wrapper.get('[data-test=desktop-invitation-code]').setValue(`  ${INVITATION_CODE}  `)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.changePassword).toHaveBeenCalledWith('', 'Ab1!cdef', undefined, INVITATION_CODE)
    expect(api.completePhoneRegistration).not.toHaveBeenCalled()
    expect(api.completeEmailRegistration).not.toHaveBeenCalled()
    expect(api.completeOAuthAuthorization).toHaveBeenCalledWith(TRANSACTION)
    wrapper.unmount()
  })

  it('keeps an invitation link code through a desktop password page refresh', async () => {
    const first = await mountPasswordPage(false, true, INVITATION_CODE)
    expect(sessionStorage.getItem('yzhh.invitation-code')).toBe(INVITATION_CODE)
    first.wrapper.unmount()

    const refreshed = await mountPasswordPage()
    await refreshed.wrapper.get('[data-test=desktop-first-password]').setValue('Ab1!cdef')
    await refreshed.wrapper.get('[data-test=desktop-confirm-password]').setValue('Ab1!cdef')
    await refreshed.wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.changePassword).toHaveBeenCalledWith('', 'Ab1!cdef', undefined, INVITATION_CODE)
    expect(sessionStorage.getItem('yzhh.invitation-code')).toBeNull()
    refreshed.wrapper.unmount()
  })

  it('keeps the authenticated desktop setup available when a code is not allowed', async () => {
    api.changePassword.mockRejectedValueOnce(Object.assign(new Error('code not allowed'), {
      isAxiosError: true,
      response: { status: 422, data: { code: 'invitation_code_not_allowed' } },
    }))
    const { wrapper } = await mountPasswordPage()
    await wrapper.get('[data-test=desktop-first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=desktop-confirm-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=desktop-invitation-code]').setValue(INVITATION_CODE)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[data-test=desktop-password-error]').text())
      .toContain('此账号无法再使用邀请码')
    expect(api.completeOAuthAuthorization).not.toHaveBeenCalled()

    await wrapper.get('[data-test=desktop-invitation-code]').setValue('')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.changePassword).toHaveBeenNthCalledWith(2, '', 'Ab1!cdef', undefined, undefined)
    expect(api.completeOAuthAuthorization).toHaveBeenCalledWith(TRANSACTION)
    wrapper.unmount()
  })

  it('sends the exact mixed-case code only when completing a pending desktop email account', async () => {
    const { wrapper, router } = await mountPendingEmailRegistration()
    await wrapper.get('[data-test=desktop-first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=desktop-confirm-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=desktop-invitation-code]').setValue(` \t${INVITATION_CODE} `)
    expect(sessionStorage.getItem('yzh_pending_email_registration') ?? '').not.toContain(INVITATION_CODE)
    expect(router.currentRoute.value.fullPath).not.toContain(INVITATION_CODE)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.completeEmailRegistration).toHaveBeenCalledWith(
      CHALLENGE, 'Ab1!cdef', CONSENT_VERSION, INVITATION_CODE,
    )
    wrapper.unmount()
  })

  it('keeps the pending desktop phone registration after an invalid invitation code', async () => {
    api.completePhoneRegistration.mockRejectedValueOnce(Object.assign(new Error('invalid code'), {
      isAxiosError: true,
      response: { status: 422, data: { code: 'invalid_invitation_code' } },
    }))
    const { wrapper } = await mountPendingRegistration()
    await wrapper.get('[data-test=desktop-first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=desktop-confirm-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=desktop-invitation-code]').setValue(`${INVITATION_CODE.slice(0, 31)}!`)
    expect(wrapper.get('[data-test=desktop-password-submit]').attributes('disabled')).toBeDefined()
    expect(api.completePhoneRegistration).not.toHaveBeenCalled()

    await wrapper.get('[data-test=desktop-invitation-code]').setValue(INVITATION_CODE)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(recallPendingPhoneRegistration()?.challengeId).toBe(CHALLENGE)
    expect(wrapper.find('[data-test=desktop-password-form]').exists()).toBe(true)
    expect(wrapper.get('[data-test=desktop-password-error]').text())
      .toContain('邀请码无效或已撤销')

    await wrapper.get('[data-test=desktop-invitation-code]').setValue('  ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.completePhoneRegistration).toHaveBeenNthCalledWith(
      2, CHALLENGE, 'Ab1!cdef', CONSENT_VERSION, undefined,
    )
    wrapper.unmount()
  })

  it('keeps the password and lets the user retry if authorization fails', async () => {
    api.completeOAuthAuthorization.mockRejectedValueOnce(new Error('network'))
    const { wrapper } = await mountPasswordPage()

    await wrapper.get('[data-test=desktop-first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=desktop-confirm-password]').setValue('Ab1!cdef')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.changePassword).toHaveBeenCalledOnce()
    expect(wrapper.get('[data-test=desktop-password-oauth-error]').text())
      .toContain('暂时无法返回桌面端')
    await wrapper.get('[data-test=desktop-password-retry]').trigger('click')
    await flushPromises()

    expect(api.changePassword).toHaveBeenCalledOnce()
    expect(api.completeOAuthAuthorization).toHaveBeenCalledTimes(2)
    expect(redirect).toHaveBeenCalledWith(REDIRECT_URL)
    wrapper.unmount()
  })

  it('continues authorization after a refresh following successful password setup', async () => {
    const { wrapper } = await mountPasswordPage(true)

    expect(wrapper.find('[data-test=desktop-invitation-code]').exists()).toBe(false)
    expect(api.changePassword).not.toHaveBeenCalled()
    expect(api.completeOAuthAuthorization).toHaveBeenCalledWith(TRANSACTION)
    expect(redirect).toHaveBeenCalledWith(REDIRECT_URL)
    wrapper.unmount()
  })

  it('never silently authorizes an existing browser account from a transaction link', async () => {
    const { wrapper } = await mountPasswordPage(true, false)

    expect(wrapper.get('[data-test=desktop-password-invalid]').text())
      .toContain('登录请求已失效')
    expect(api.completeOAuthAuthorization).not.toHaveBeenCalled()
    expect(redirect).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('rejects a setup marker belonging to a different account', async () => {
    rememberDesktopPasswordSetup(TRANSACTION, '72000000-0000-4000-8000-000000000002')
    const { wrapper } = await mountPasswordPage(true, false)

    expect(wrapper.find('[data-test=desktop-password-invalid]').exists()).toBe(true)
    expect(api.completeOAuthAuthorization).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
