import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '../../stores/auth'
import TopErrorMessage from '../../components/feedback/TopErrorMessage.vue'
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
import SetPasswordView from './SetPasswordView.vue'

const api = vi.hoisted(() => ({
  changePassword: vi.fn(),
  completeEmailRegistration: vi.fn(),
  completePhoneRegistration: vi.fn(),
}))
const http = vi.hoisted(() => ({
  readCookie: vi.fn(),
  restoreSession: vi.fn(),
}))

vi.mock('../../api/platform', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/platform')>()),
  changePassword: api.changePassword,
  completeEmailRegistration: api.completeEmailRegistration,
  completePhoneRegistration: api.completePhoneRegistration,
}))
vi.mock('../../api/http', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/http')>()),
  readCookie: http.readCookie,
  restoreSession: http.restoreSession,
}))

const CHALLENGE = '2f0f2c34-5b6a-4d7e-8f90-1a2b3c4d5e6f'
const TRANSACTION = '1f0f2c34-5b6a-4d7e-8f90-1a2b3c4d5e6f'
const INVITATION_CODE = 'Aa0Bb1Cc2Dd3Ee4Ff5Gg6Hh7Ii8Jj9Kk'
const session = {
  accessToken: 'registered-access-token',
  expiresIn: 900,
  user: {
    id: '71000000-0000-4000-8000-000000000001',
    tenantId: '61000000-0000-4000-8000-000000000001',
    email: null,
    displayName: '新用户',
    role: 'USER' as const,
    status: 'ACTIVE',
    passwordSet: true,
  },
}

async function mountPage(path = '/set-password') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/set-password', component: SetPasswordView },
      { path: '/login', component: { template: '<div>login</div>' } },
      { path: '/app/dashboard', component: { template: '<div>dashboard</div>' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(SetPasswordView, { global: { plugins: [router, ElementPlus] } })
  await flushPromises()
  return { wrapper, router }
}

describe('normal website registration', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    sessionStorage.clear()
    forgetPendingEmailRegistration()
    forgetPendingPhoneRegistration()
    api.completeEmailRegistration.mockResolvedValue(session)
    api.completePhoneRegistration.mockResolvedValue(session)
    api.changePassword.mockResolvedValue(session)
    http.readCookie.mockReturnValue(null)
    http.restoreSession.mockResolvedValue(false)
  })

  afterEach(() => {
    sessionStorage.clear()
    forgetPendingEmailRegistration()
    forgetPendingPhoneRegistration()
  })

  it('creates the account only when the new password is submitted', async () => {
    rememberPendingPhoneRegistration({
      code: 'phone_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, null, '2026-09-23')
    const { wrapper, router } = await mountPage()

    expect(useAuthStore().isAuthenticated).toBe(false)
    expect(wrapper.find('[data-test=invitation-code]').exists()).toBe(true)
    expect(wrapper.get('label[for=invitation-code]').text()).toContain('选填')
    expect(api.completePhoneRegistration).not.toHaveBeenCalled()
    await wrapper.get('[data-test=first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=confirm-first-password]').setValue('Ab1!cdef')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.completePhoneRegistration).toHaveBeenCalledWith(
      CHALLENGE, 'Ab1!cdef', '2026-09-23', undefined,
    )
    expect(api.changePassword).not.toHaveBeenCalled()
    expect(useAuthStore().isAuthenticated).toBe(true)
    expect(router.currentRoute.value.path).toBe('/app/dashboard')
    expect(recallPendingPhoneRegistration()).toBeNull()
    wrapper.unmount()
  })

  it('does not let a desktop registration ticket create an account through the website page', async () => {
    rememberPendingPhoneRegistration({
      code: 'phone_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, TRANSACTION, '2026-09-23')
    const { wrapper } = await mountPage()

    await wrapper.get('[data-test=first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=confirm-first-password]').setValue('Ab1!cdef')
    expect(wrapper.get('[data-test=set-password-submit]').attributes('disabled')).toBeDefined()
    expect(api.completePhoneRegistration).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('creates a new email account only after website password submission', async () => {
    rememberPendingEmailRegistration({
      code: 'email_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, null, '2026-09-23', 'user@example.com')
    const { wrapper, router } = await mountPage()

    expect(useAuthStore().isAuthenticated).toBe(false)
    expect(wrapper.find('[data-test=invitation-code]').exists()).toBe(true)
    expect(api.completeEmailRegistration).not.toHaveBeenCalled()
    expect(http.restoreSession).not.toHaveBeenCalled()
    await wrapper.get('[data-test=first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=confirm-first-password]').setValue('Ab1!cdef')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.completeEmailRegistration).toHaveBeenCalledWith(
      CHALLENGE, 'Ab1!cdef', '2026-09-23', undefined,
    )
    expect(api.completePhoneRegistration).not.toHaveBeenCalled()
    expect(useAuthStore().isAuthenticated).toBe(true)
    expect(router.currentRoute.value.path).toBe('/app/dashboard')
    expect(recallPendingEmailRegistration()).toBeNull()
    wrapper.unmount()
  })

  it('does not let a desktop email registration ticket create an account through the website page', async () => {
    rememberPendingEmailRegistration({
      code: 'email_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, TRANSACTION, '2026-09-23', 'user@example.com')
    const { wrapper } = await mountPage()

    await wrapper.get('[data-test=first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=confirm-first-password]').setValue('Ab1!cdef')
    expect(wrapper.get('[data-test=set-password-submit]').attributes('disabled')).toBeDefined()
    expect(api.completeEmailRegistration).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('resumes a completed email registration from a matching refresh session', async () => {
    rememberPendingEmailRegistration({
      code: 'email_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, null, '2026-09-23', 'user@example.com')
    http.readCookie.mockReturnValue('csrf-token')
    http.restoreSession.mockImplementation(async () => {
      useAuthStore().setSession(session.accessToken, {
        ...session.user, email: 'User@Example.com',
      })
      return true
    })

    const { wrapper, router } = await mountPage()

    expect(http.restoreSession).toHaveBeenCalledOnce()
    expect(api.completeEmailRegistration).not.toHaveBeenCalled()
    expect(recallPendingEmailRegistration()).toBeNull()
    expect(useAuthStore().isAuthenticated).toBe(true)
    expect(router.currentRoute.value.path).toBe('/app/dashboard')
    wrapper.unmount()
  })

  it('does not accept a refresh session for another email', async () => {
    rememberPendingEmailRegistration({
      code: 'email_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, null, '2026-09-23', 'user@example.com')
    http.readCookie.mockReturnValue('csrf-token')
    http.restoreSession.mockImplementation(async () => {
      useAuthStore().setSession(session.accessToken, {
        ...session.user, email: 'other@example.com',
      })
      return true
    })

    const { wrapper, router } = await mountPage()

    expect(api.completeEmailRegistration).not.toHaveBeenCalled()
    expect(recallPendingEmailRegistration()?.email).toBe('user@example.com')
    expect(useAuthStore().isAuthenticated).toBe(false)
    expect(router.currentRoute.value.path).toBe('/set-password')
    wrapper.unmount()
  })

  it('keeps a failed registration pending without issuing a login session', async () => {
    rememberPendingPhoneRegistration({
      code: 'phone_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, null, '2026-09-23')
    api.completePhoneRegistration.mockRejectedValue(new Error('network'))
    const { wrapper } = await mountPage()

    await wrapper.get('[data-test=first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=confirm-first-password]').setValue('Ab1!cdef')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(useAuthStore().isAuthenticated).toBe(false)
    expect(recallPendingPhoneRegistration()?.challengeId).toBe(CHALLENGE)
    expect(wrapper.findComponent(TopErrorMessage).props('message')).toBe('密码设置失败，请稍后重试。')
    wrapper.unmount()
  })

  it('keeps an email registration pending when password setup fails', async () => {
    rememberPendingEmailRegistration({
      code: 'email_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, null, '2026-09-23', 'user@example.com')
    api.completeEmailRegistration.mockRejectedValue(new Error('network'))
    const { wrapper } = await mountPage()

    await wrapper.get('[data-test=first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=confirm-first-password]').setValue('Ab1!cdef')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(useAuthStore().isAuthenticated).toBe(false)
    expect(recallPendingEmailRegistration()?.challengeId).toBe(CHALLENGE)
    expect(wrapper.findComponent(TopErrorMessage).props('message')).toBe('密码设置失败，请稍后重试。')
    wrapper.unmount()
  })

  it('identifies an expired registration challenge instead of blaming password strength', async () => {
    rememberPendingPhoneRegistration({
      code: 'phone_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, null, '2026-09-23')
    api.completePhoneRegistration.mockRejectedValue(Object.assign(new Error('expired'), {
      isAxiosError: true,
      response: { status: 422, data: { code: 'invalid_registration_challenge' } },
    }))
    const { wrapper } = await mountPage()

    await wrapper.get('[data-test=first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=confirm-first-password]').setValue('Ab1!cdef')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.findComponent(TopErrorMessage).props('message'))
      .toBe('验证码注册请求已失效，请返回登录页重新验证手机号。')
    expect(recallPendingPhoneRegistration()).toBeNull()
    expect(useAuthStore().isAuthenticated).toBe(false)
    wrapper.unmount()
  })

  it('sends a trimmed mixed-case invitation code only at new phone registration', async () => {
    rememberPendingPhoneRegistration({
      code: 'phone_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, null, '2026-09-23')
    const { wrapper, router } = await mountPage()
    expect(wrapper.find('[data-test=invitation-code]').exists()).toBe(true)

    await wrapper.get('[data-test=first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=confirm-first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=invitation-code]').setValue(`  ${INVITATION_CODE}  `)
    expect(sessionStorage.getItem('yzh_pending_phone_registration') ?? '').not.toContain(INVITATION_CODE)
    expect(router.currentRoute.value.fullPath).not.toContain(INVITATION_CODE)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.completePhoneRegistration).toHaveBeenCalledWith(
      CHALLENGE, 'Ab1!cdef', '2026-09-23', INVITATION_CODE,
    )
    wrapper.unmount()
  })

  it('keeps an invitation link code through a refresh before the new account is created', async () => {
    rememberPendingPhoneRegistration({
      code: 'phone_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, null, '2026-09-23')
    const first = await mountPage(`/set-password?invite=${INVITATION_CODE}`)
    expect(sessionStorage.getItem('yzhh.invitation-code')).toBe(INVITATION_CODE)
    first.wrapper.unmount()

    const refreshed = await mountPage()
    expect(api.completePhoneRegistration).not.toHaveBeenCalled()
    await refreshed.wrapper.get('[data-test=first-password]').setValue('Ab1!cdef')
    await refreshed.wrapper.get('[data-test=confirm-first-password]').setValue('Ab1!cdef')
    await refreshed.wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.completePhoneRegistration).toHaveBeenCalledWith(
      CHALLENGE, 'Ab1!cdef', '2026-09-23', INVITATION_CODE,
    )
    expect(sessionStorage.getItem('yzhh.invitation-code')).toBeNull()
    refreshed.wrapper.unmount()
  })

  it('does not reuse an earlier invitation when the current link names an invalid code', async () => {
    rememberPendingPhoneRegistration({
      code: 'phone_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, null, '2026-09-23')
    sessionStorage.setItem('yzhh.invitation-code', INVITATION_CODE)
    const first = await mountPage('/set-password?invite=invalid')
    expect(sessionStorage.getItem('yzhh.invitation-code')).toBeNull()
    await first.wrapper.get('[data-test=first-password]').setValue('Ab1!cdef')
    await first.wrapper.get('[data-test=confirm-first-password]').setValue('Ab1!cdef')
    expect(first.wrapper.get('[data-test=set-password-submit]').attributes('disabled')).toBeDefined()
    first.wrapper.unmount()

    const refreshed = await mountPage()
    await refreshed.wrapper.get('[data-test=first-password]').setValue('Ab1!cdef')
    await refreshed.wrapper.get('[data-test=confirm-first-password]').setValue('Ab1!cdef')
    await refreshed.wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.completePhoneRegistration).toHaveBeenCalledWith(
      CHALLENGE, 'Ab1!cdef', '2026-09-23', undefined,
    )
    refreshed.wrapper.unmount()
  })

  it('blocks malformed invitation codes and preserves an email registration after rejection', async () => {
    rememberPendingEmailRegistration({
      code: 'email_registration_pending',
      challengeId: CHALLENGE,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }, null, '2026-09-23', 'user@example.com')
    api.completeEmailRegistration.mockRejectedValueOnce(Object.assign(new Error('revoked'), {
      isAxiosError: true,
      response: { status: 409, data: { code: 'invitation_code_revoked' } },
    }))
    const { wrapper } = await mountPage()
    await wrapper.get('[data-test=first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=confirm-first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=invitation-code]').setValue(`${INVITATION_CODE.slice(0, 31)}_`)
    expect(wrapper.get('[data-test=set-password-submit]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('#invitation-code-error').text()).toContain('32 位')
    expect(api.completeEmailRegistration).not.toHaveBeenCalled()

    await wrapper.get('[data-test=invitation-code]').setValue(INVITATION_CODE)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(recallPendingEmailRegistration()?.challengeId).toBe(CHALLENGE)
    expect(wrapper.find('[data-test=invitation-code]').exists()).toBe(true)
    expect(wrapper.findComponent(TopErrorMessage).props('message'))
      .toBe('邀请码无效或已撤销，请修改后重试。')

    await wrapper.get('[data-test=invitation-code]').setValue('')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.completeEmailRegistration).toHaveBeenNthCalledWith(
      2, CHALLENGE, 'Ab1!cdef', '2026-09-23', undefined,
    )
    wrapper.unmount()
  })

  it('offers an optional invitation code to an authenticated account setting its first password', async () => {
    useAuthStore().setSession('existing-account', { ...session.user, passwordSet: false })
    const { wrapper } = await mountPage()
    expect(wrapper.find('[data-test=invitation-code]').exists()).toBe(true)
    await wrapper.get('[data-test=first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=confirm-first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=invitation-code]').setValue('invalid')
    expect(wrapper.get('[data-test=set-password-submit]').attributes('disabled')).toBeDefined()
    expect(api.changePassword).not.toHaveBeenCalled()

    await wrapper.get('[data-test=invitation-code]').setValue(`  ${INVITATION_CODE}  `)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.changePassword).toHaveBeenCalledWith('', 'Ab1!cdef', undefined, INVITATION_CODE)
    expect(api.completePhoneRegistration).not.toHaveBeenCalled()
    expect(api.completeEmailRegistration).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('lets an authenticated first-password account remove a rejected invitation code and retry', async () => {
    useAuthStore().setSession('existing-account', { ...session.user, passwordSet: false })
    api.changePassword.mockRejectedValueOnce(Object.assign(new Error('code not allowed'), {
      isAxiosError: true,
      response: { status: 422, data: { code: 'invitation_code_not_allowed' } },
    }))
    const { wrapper } = await mountPage()
    await wrapper.get('[data-test=first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=confirm-first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=invitation-code]').setValue(INVITATION_CODE)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.findComponent(TopErrorMessage).props('message'))
      .toBe('此账号无法再使用邀请码，请清空后重试。')
    expect(wrapper.find('[data-test=invitation-code]').exists()).toBe(true)

    await wrapper.get('[data-test=invitation-code]').setValue('')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.changePassword).toHaveBeenNthCalledWith(2, '', 'Ab1!cdef', undefined, undefined)
    wrapper.unmount()
  })

  it('does not offer invitation attribution to an account whose password is already set', async () => {
    useAuthStore().setSession('existing-account', session.user)
    const { wrapper } = await mountPage()
    expect(wrapper.find('[data-test=invitation-code]').exists()).toBe(false)
    expect(wrapper.get('[data-test=set-password-submit]').attributes('disabled')).toBeDefined()
    expect(api.changePassword).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
