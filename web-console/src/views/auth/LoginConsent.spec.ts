import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { createConsoleRouter } from '../../router'
import { useAuthStore } from '../../stores/auth'
import LoginView from './LoginView.vue'

const api = vi.hoisted(() => ({
  login: vi.fn(), requestEmailChallenge: vi.fn(), requestIdentitySms: vi.fn(),
  identityLogin: vi.fn(), emailIdentityLogin: vi.fn(), requestSliderCaptcha: vi.fn(),
  getPublishedLegalDocument: vi.fn(),
}))
vi.mock('../../api/platform', async (importOriginal) => ({
  ...await importOriginal<typeof import('../../api/platform')>(), ...api,
}))
const session = {
  accessToken: 'password-session', expiresIn: 900,
  user: { id: 'user-1', tenantId: 'tenant-1', email: 'user@example.com',
    role: 'USER' as const, passwordSet: true, status: 'ACTIVE' },
}
const rejected = (code: string, status: number) => Object.assign(new Error(code), {
  isAxiosError: true, response: { status, data: { code } },
})

describe('direct password login without verification providers', () => {
  let wrapper: VueWrapper | undefined
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
    sessionStorage.clear()
    api.login.mockResolvedValue(session)
    // Login must succeed even when the expired external verification services are unavailable.
    for (const provider of [api.requestEmailChallenge, api.requestIdentitySms,
      api.requestSliderCaptcha, api.getPublishedLegalDocument]) {
      provider.mockRejectedValue(new Error('verification service unavailable'))
    }
  })
  afterEach(() => { wrapper?.unmount() })
  async function openLogin() {
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/login')
    await router.isReady()
    wrapper = mount(LoginView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    return { view: wrapper, router }
  }
  async function fill(view: VueWrapper, identifier = 'user@example.com', password = 'ValidPassw0rd!') {
    await view.get('[data-test=login-identifier]').setValue(identifier)
    await view.get('[data-test=login-password]').setValue(password)
  }
  function expectNoVerification() {
    for (const request of [api.requestEmailChallenge, api.requestIdentitySms,
      api.identityLogin, api.emailIdentityLogin, api.requestSliderCaptcha,
      api.getPublishedLegalDocument]) expect(request).not.toHaveBeenCalled()
  }
  it('shows the password form immediately and explains administrator account management', async () => {
    const { view } = await openLogin()
    expect(view.get('[data-test=login-password]').attributes('autocomplete')).toBe('current-password')
    for (const selector of ['[data-test=login-mode-code]', '[data-test=email-send]',
      '[data-test=email-code]', '[data-test=sms-send]', '[data-test=login-consent]', '.auth-tabs']) {
      expect(view.find(selector).exists()).toBe(false)
    }
    expect(view.find('a[href="/forgot-password"]').exists()).toBe(false)
    expect(view.text()).toContain('账号开通或密码重置请联系管理员')
    expectNoVerification()
  })
  it.each(['user@example.com', '13800138000'])(
    'logs in %s with only its password while verification services are unavailable', async (identifier) => {
      const { view, router } = await openLogin()
      await fill(view, identifier)
      await view.get('form').trigger('submit')
      await flushPromises()
      expect(api.login).toHaveBeenCalledWith(identifier, 'ValidPassw0rd!')
      expect(useAuthStore().accessToken).toBe(session.accessToken)
      await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/app/dashboard'), { timeout: 5000 })
      expectNoVerification()
    },
  )
  it.each([['', 'password'], ['   ', 'password'], ['user@example.com', '']])(
    'blocks an incomplete identifier/password pair %s', async (identifier, password) => {
      const { view } = await openLogin()
      await fill(view, identifier, password)
      expect(view.get('[data-test=login-submit]').attributes('disabled')).toBeDefined()
      await view.get('form').trigger('submit')
      expect(api.login).not.toHaveBeenCalled()
    },
  )
  it('trims the identifier and preserves every password character', async () => {
    const { view } = await openLogin()
    await fill(view, '  user@example.com  ', '  ValidPassw0rd!  ')
    await view.get('form').trigger('submit')
    await flushPromises()
    expect(api.login).toHaveBeenCalledWith('user@example.com', '  ValidPassw0rd!  ')
  })
  it('routes administrators to their existing management page', async () => {
    api.login.mockResolvedValue({ ...session, user: { ...session.user, role: 'ADMIN' } })
    const { view, router } = await openLogin()
    await fill(view)
    await view.get('form').trigger('submit')
    await flushPromises()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/admin/overview'), { timeout: 5000 })
  })
  it('does not establish a session for an incorrect password', async () => {
    api.login.mockRejectedValue(rejected('invalid_credentials', 401))
    const { view, router } = await openLogin()
    await fill(view)
    await view.get('form').trigger('submit')
    await flushPromises()
    expect(view.text()).toContain('账号或密码错误')
    expect(useAuthStore().isAuthenticated).toBe(false)
    expect(router.currentRoute.value.path).toBe('/login')
    expectNoVerification()
  })
  it('keeps backend outages distinct from password errors', async () => {
    api.login.mockRejectedValue(rejected('unavailable', 503))
    const { view } = await openLogin()
    await fill(view)
    await view.get('form').trigger('submit')
    await flushPromises()
    expect(view.text()).toContain('服务暂时不可用（503）')
    expect(useAuthStore().isAuthenticated).toBe(false)
  })
  it('directs accounts without passwords to the administrator', async () => {
    api.login.mockRejectedValue(rejected('password_not_set', 403))
    const { view } = await openLogin()
    await fill(view)
    await view.get('form').trigger('submit')
    await flushPromises()
    expect(view.text()).toContain('请联系管理员设置密码')
    expect(view.text()).not.toContain('请使用验证码登录')
  })
  it('prevents duplicate submissions while login is pending', async () => {
    let finish!: (value: typeof session) => void
    api.login.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const { view } = await openLogin()
    await fill(view)
    await view.get('form').trigger('submit')
    await view.get('form').trigger('submit')
    expect(api.login).toHaveBeenCalledTimes(1)
    expect(view.get('[data-test=login-identifier]').attributes('disabled')).toBeDefined()
    expect(view.get('[data-test=login-password]').attributes('disabled')).toBeDefined()
    finish(session)
    await flushPromises()
  })
  it('retains the password visibility toggle', async () => {
    const { view } = await openLogin()
    expect(view.get('[data-test=login-password]').attributes('type')).toBe('password')
    await view.get('button[aria-label="显示密码"]').trigger('click')
    expect(view.get('[data-test=login-password]').attributes('type')).toBe('text')
    await view.get('button[aria-label="隐藏密码"]').trigger('click')
    expect(view.get('[data-test=login-password]').attributes('type')).toBe('password')
  })
})
