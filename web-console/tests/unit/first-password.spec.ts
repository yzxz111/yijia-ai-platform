import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { readFileSync } from 'node:fs'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createConsoleRouter } from '../../src/router'
import { useAuthStore } from '../../src/stores/auth'
import SetPasswordView from '../../src/views/auth/SetPasswordView.vue'

const api = vi.hoisted(() => ({ changePassword: vi.fn() }))
const authBrandSource = readFileSync('src/components/auth/AuthBrandPanel.vue', 'utf8')
const loginSource = readFileSync('src/views/auth/LoginView.vue', 'utf8')
const setPasswordSource = readFileSync('src/views/auth/SetPasswordView.vue', 'utf8')

vi.mock('../../src/api/platform', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/api/platform')>()),
  changePassword: api.changePassword,
}))

const passwordlessUser = {
  id: '71000000-0000-4000-8000-000000000001',
  tenantId: '61000000-0000-4000-8000-000000000001',
  email: null,
  displayName: '150用户',
  role: 'USER' as const,
  passwordSet: false,
}

describe('first password setup', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    api.changePassword.mockReset()
  })

  it('keeps routed authentication views in one visual system', () => {
    expect(loginSource).toMatch(/<AuthBrandPanel\s+:app-name="appName"/)
    expect(authBrandSource).toMatch(/background:\s*#15291d/)
    expect(authBrandSource).toMatch(/box-shadow:/)
    expect(setPasswordSource).toMatch(/background:\s*var\(--app-sidebar\)/)
    expect(setPasswordSource).toMatch(/box-shadow:\s*var\(--app-shadow\)/)

    for (const source of [authBrandSource, setPasswordSource]) {
      expect(source).toMatch(/@media \(max-width: 767px\)/)
    }
  })

  it('keeps a passwordless session out of the console until setup is complete', async () => {
    useAuthStore().setSession('bootstrap-token', passwordlessUser)
    const router = createConsoleRouter(createMemoryHistory())

    await router.push('/app/dashboard')

    expect(router.currentRoute.value.path).toBe('/set-password')
  })

  it('explains the first-login password requirement in the form', () => {
    // 这句提示是**分情况**的（SetPasswordView.vue:199-201）：
    //   已登录的待设密用户      -> 「新用户注册需设置登录密码。」
    //   尚无会话、正在补完注册  -> 「设置密码成功后才会创建账号。」
    // 原用例既没有建立会话、又断言了前者，于是它一直在测另一条分支的文案，
    // 文案一改就红。两条分支都在这里钉住，避免下次只覆盖到一半。
    const anonymous = mount(SetPasswordView, {
      global: { plugins: [createConsoleRouter(createMemoryHistory()), ElementPlus] },
    })
    expect(anonymous.get('[data-test=password-setup-hint]').text())
      .toBe('设置密码成功后才会创建账号。')

    useAuthStore().setSession('bootstrap-token', passwordlessUser)
    const authenticated = mount(SetPasswordView, {
      global: { plugins: [createConsoleRouter(createMemoryHistory()), ElementPlus] },
    })
    expect(authenticated.get('[data-test=password-setup-hint]').text())
      .toBe('新用户注册需设置登录密码。')
  })

  it('accepts an eight-character complex password and enters the console without a second login', async () => {
    api.changePassword.mockResolvedValue({
      accessToken: 'post-password-token',
      expiresIn: 900,
      user: { ...passwordlessUser, passwordSet: true },
    })
    const auth = useAuthStore()
    auth.setSession('bootstrap-token', passwordlessUser)
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/set-password')
    const wrapper = mount(SetPasswordView, {
      global: { plugins: [router, ElementPlus] },
    })

    await wrapper.get('[data-test=first-password]').setValue('Ab1!cde')
    await wrapper.get('[data-test=confirm-first-password]').setValue('Ab1!cde')
    expect(wrapper.get('[data-test=set-password-submit]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-test=first-password]').setValue('Ab1!cdef')
    await wrapper.get('[data-test=confirm-first-password]').setValue('Ab1!cdef')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.changePassword).toHaveBeenCalledWith('', 'Ab1!cdef', undefined, undefined)
    expect(auth.accessToken).toBe('post-password-token')
    expect(auth.user?.passwordSet).toBe(true)
    // 预热目标路由的懒加载模块。首次进入 /app/dashboard 会触发
    // DashboardView 的动态 import（router/index.ts:12），在 jsdom 下这一步
    // 要花掉数秒；全量套件并行跑时它超过了下面 5 秒的上限，于是这条用例
    // 变成一条与功能无关的抖动（单跑本文件时它一直是绿的）。
    // 先把这个模块拉起来，断言就只测「设密成功后会导航」这件事本身。
    await import('../../src/views/user/DashboardView.vue')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/app/dashboard'), { timeout: 5000 })
  })
})
