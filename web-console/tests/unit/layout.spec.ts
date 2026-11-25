import { createPinia, setActivePinia } from 'pinia'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { readFileSync } from 'node:fs'
import { defineComponent, h } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../src/api/platform', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/api/platform')>()),
  getProfile: vi.fn(async () => ({
    id: '71000000-0000-4000-8000-000000000001',
    avatarUrl: null,
    avatarUpdatedAt: null,
  })),
}))

vi.mock('../../src/api/notifications', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/api/notifications')>()),
  getUnreadCount: vi.fn(async () => ({ data: { count: 3 } })),
}))

import ConsoleLayout from '../../src/layouts/ConsoleLayout.vue'
import HeaderBar from '../../src/components/layout/HeaderBar.vue'
import SidebarNav from '../../src/components/layout/SidebarNav.vue'
import NotificationBell from '../../src/components/notifications/NotificationBell.vue'
import { createConsoleRouter } from '../../src/router'
import { useAuthStore } from '../../src/stores/auth'

enableAutoUnmount(afterEach)

const elementOverrides = readFileSync(
  'src/styles/element-overrides.css',
  'utf8',
)
const tokens = readFileSync(
  'src/styles/tokens.css',
  'utf8',
)
const adminStyles = readFileSync(
  'src/styles/admin.css',
  'utf8',
)
const layoutSource = readFileSync(
  'src/layouts/ConsoleLayout.vue',
  'utf8',
)
const sidebarSource = readFileSync(
  'src/components/layout/SidebarNav.vue',
  'utf8',
)

function authenticated(role: 'USER' | 'ADMIN') {
  const store = useAuthStore()
  store.$patch({
    accessToken: 'test-token',
    user: {
      id: '71000000-0000-4000-8000-000000000001',
      tenantId: '61000000-0000-4000-8000-000000000001',
      email: role === 'ADMIN' ? 'admin@example.com' : 'user@example.com',
      role,
    },
  })
  return store
}

async function readyRouter(path = '/app/dashboard'): Promise<Router> {
  const router = isolatedRouter()
  await router.push(path)
  await router.isReady()
  return router
}

function isolatedRouter(): Router {
  // Anonymous guard tests must not call the real refresh-session endpoint.
  return createConsoleRouter(createMemoryHistory(), async () => false)
}

async function triggerNavigation(router: Router, trigger: () => Promise<void> | void) {
  const push = vi.spyOn(router, 'push')
  try {
    await trigger()
    expect(push).toHaveBeenCalledTimes(1)
    // Await the actual lazy-route navigation, not waitFor's one-second polling
    // budget, which can expire during concurrent component transformation.
    await push.mock.results[0]!.value
    await flushPromises()
  } finally {
    push.mockRestore()
  }
}

describe('console layout', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('removes text decoration from shared user and admin navigation links', () => {
    expect(sidebarSource).toMatch(
      /\.nav-item\s*\{[^}]*text-decoration:\s*none;/,
    )
  })

  it('defines the approved quiet professional visual hierarchy', () => {
    expect(tokens).toMatch(/--app-surface-subtle:/)
    expect(tokens).toMatch(/--app-border-strong:/)
    expect(tokens).toMatch(/--app-shadow-sm:/)
    expect(tokens).toMatch(/--app-content-width:/)
    expect(adminStyles).toMatch(/\.admin-table tbody tr:hover/)
    expect(elementOverrides).toMatch(/\.el-input__wrapper:hover/)
  })

  it('keeps shared admin toolbar controls at the same height', () => {
    expect(adminStyles).toMatch(/--admin-filter-control-height:\s*40px/)
    expect(adminStyles).toMatch(/\.admin-toolbar \.el-date-editor/)
    expect(adminStyles).toMatch(/\.admin-toolbar \.el-range-editor/)
    expect(adminStyles).toMatch(/height:\s*var\(--admin-filter-control-height\)/)
    expect(adminStyles).toMatch(/\.admin-toolbar \.el-button\s*\{[\s\S]*?margin-left:\s*0/)
    expect(adminStyles).toMatch(/@media \(max-width:\s*600px\)[\s\S]*--admin-filter-control-height:\s*44px/)
  })

  it('frames console content and anchors the active navigation state', () => {
    expect(layoutSource).toMatch(/class="content-frame"/)
    expect(layoutSource).toMatch(/max-width:\s*var\(--app-content-width\)/)
    expect(sidebarSource).toMatch(/\.nav-item\.active::before/)
  })

  it('shows administrative routes only for ADMIN users', async () => {
    const auth = authenticated('USER')
    const wrapper = mount(SidebarNav, {
      props: { collapsed: false },
      global: {
        plugins: [await readyRouter()],
        stubs: {
          ElTooltip: { template: '<span><slot /></span>' },
        },
      },
    })

    expect(wrapper.text()).toContain('调用日志')
    expect(wrapper.text()).not.toContain('Skill 市场')
    expect(wrapper.text()).not.toContain('文件管理')
    expect(wrapper.text()).toContain('余额钱包')
    expect(wrapper.text()).not.toContain('渠道与路由')
    expect(wrapper.text()).not.toContain('系统运维')
    expect(wrapper.get('[data-test=desktop-nav-usage]').element.tagName).toBe('A')
    expect(wrapper.get('[data-test=desktop-nav-usage]').attributes('href')).toBe('/app/usage')
    expect(wrapper.get('[data-test=desktop-nav-wallet]').attributes('href')).toBe('/app/wallet')
    expect(wrapper.get('[data-test=desktop-nav-wallet] svg').classes()).toContain('lucide-wallet-cards')

    auth.user = { ...auth.user!, role: 'ADMIN' }
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('模型管理')
    expect(wrapper.text()).toContain('Skill 管理')
    expect(wrapper.text()).toContain('专家管理')
    expect(wrapper.get('[data-test=desktop-nav-admin-skills]').attributes('href')).toBe('/admin/skills')
    expect(wrapper.get('[data-test=desktop-nav-admin-experts]').attributes('href')).toBe('/admin/experts')
    expect(wrapper.text()).not.toContain('渠道与路由')
    expect(wrapper.find('[data-test=desktop-nav-admin-channels]').exists()).toBe(false)
    expect(wrapper.text()).toContain('系统运维')
    expect(wrapper.text()).toContain('审计日志')
    expect(wrapper.text()).toContain('钱包管理')
    expect(wrapper.get('[data-test=desktop-nav-admin-wallet]').attributes('href')).toBe('/admin/wallet')
    expect(wrapper.get('[data-test=desktop-nav-admin-wallet] svg').classes()).toContain('lucide-wallet-cards')
  })

  it('keeps the administrator personal center and communications destinations reachable', async () => {
    authenticated('ADMIN')
    const router = await readyRouter('/admin/communications')
    const wrapper = mount(SidebarNav, {
      props: { collapsed: false },
      global: { plugins: [router], stubs: { ElTooltip: { template: '<span><slot /></span>' } } },
    })
    const communications = wrapper.get('[data-test=desktop-nav-admin-communications]')
    expect(communications.attributes('href')).toBe('/admin/communications')
    expect(communications.attributes('aria-current')).toBe('page')
    const profile = wrapper.get('[aria-label="个人中心"]')
    expect(profile.text()).toContain('管理员')
    expect(profile.attributes('href')).toBe('/app/profile')
    // 预热目标路由的懒加载模块。点击后 RouterLink 会异步导航到 /app/profile，
    // 而这一步要先动态 import ProfileView（router/index.ts:20）——在 jsdom 下
    // 它要花掉约两秒，全量套件并行跑时会超过 vi.waitFor 的上限，于是这条用例
    // 变成与功能无关的抖动（单跑本文件时一直是绿的）。先拉起模块，
    // 断言就只测「点个人中心能到达个人中心」这件事本身。
    await import('../../src/views/user/ProfileView.vue')
    await profile.trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/app/profile'))

    // 管理员通知入口挂在顶部通知铃铛上（侧边栏在改版后不再有账号服务分组），
    // 因此单独挂载 NotificationBell 覆盖未读角标与无障碍文案。
    const bell = mount(NotificationBell, {
      global: { plugins: [router], stubs: { ElTooltip: { template: '<span><slot /></span>' } } },
    })
    const notifications = bell.get('[data-test=notification-bell]')
    expect(notifications.attributes('href')).toBe('/app/notifications')
    await flushPromises()
    expect(bell.get('[data-test=notification-badge]').text()).toBe('3')
    expect(notifications.attributes('aria-label')).toBe('通知中心，3 条未读通知')
  })

  it('uses the product brand and groups security navigation prominently', async () => {
    authenticated('USER')
    const wrapper = mount(SidebarNav, {
      props: { collapsed: false },
      global: {
        plugins: [await readyRouter()],
        stubs: { ElTooltip: { template: '<span><slot /></span>' } },
      },
    })

    expect(wrapper.get('.brand-copy strong').text()).toBe('一加AI中台')
    const security = wrapper.get('[data-test=nav-section-account-security]')
    expect(security.text()).toContain('账户与安全')
    expect(security.find('[data-test=desktop-nav-profile]').exists()).toBe(true)
    expect(security.find('[data-test=desktop-nav-safety-appeals]').exists()).toBe(true)
    expect(wrapper.get('[data-test=nav-section-support]').text()).toContain('支持工单')
  })

  it('places content safety at the third position from the end of admin navigation', async () => {
    authenticated('ADMIN')
    const wrapper = mount(SidebarNav, {
      props: { collapsed: false },
      global: {
        plugins: [await readyRouter()],
        stubs: {
          ElTooltip: { template: '<span><slot /></span>' },
        },
      },
    })

    const labels = wrapper.findAll('a[data-test^="desktop-nav-admin-"]').map((link) => link.text())
    expect(labels.at(-3)).toBe('内容安全')
  })

  it('navigates the administrator communications item from the sidebar', async () => {
    authenticated('ADMIN')
    const router = await readyRouter('/admin/content-safety')
    const wrapper = mount(SidebarNav, {
      props: { collapsed: false },
      global: {
        plugins: [router],
        stubs: { ElTooltip: { template: '<span><slot /></span>' } },
      },
    })

    await triggerNavigation(router, () => wrapper.get('[data-test=desktop-nav-admin-communications]').trigger('click'))
    expect(router.currentRoute.value.path).toBe('/admin/communications')
  })

  it('shows a phone-only user identity when the session has no email', async () => {
    const auth = authenticated('USER')
    auth.user = {
      ...auth.user!,
      email: null,
      displayName: '手机用户',
    }
    const wrapper = mount(HeaderBar, {
      props: { collapsed: false },
      global: {
        plugins: [await readyRouter(), ElementPlus],
      },
    })

    expect(wrapper.get('.identity-copy strong').text()).toBe('手机用户')
    expect(wrapper.get('.avatar svg').classes()).toContain('lucide-user-round')
    expect(wrapper.text()).not.toContain('未登录')
  })

  it('opens personal center from both identity shortcuts', async () => {
    authenticated('USER')
    const router = await readyRouter()
    const sidebar = mount(SidebarNav, {
      props: { collapsed: false },
      global: {
        plugins: [router],
        stubs: { ElTooltip: { template: '<span><slot /></span>' } },
      },
    })
    const header = mount(HeaderBar, {
      props: { collapsed: false },
      global: { plugins: [router, ElementPlus] },
    })

    expect(sidebar.get('.role-label').element.tagName).toBe('A')
    expect(sidebar.get('.role-label').attributes('href')).toBe('/app/profile')
    expect(header.get('.identity').element.tagName).toBe('BUTTON')
    expect(header.get('.identity').attributes('aria-haspopup')).toBe('menu')

    await triggerNavigation(router, () => sidebar.get('.role-label').trigger('click'))
    expect(router.currentRoute.value.path).toBe('/app/profile')
    await router.push('/app/dashboard')
    await header.get('.identity').trigger('click')
    await vi.waitFor(() => expect(document.body.textContent).toContain('个人中心'))
    await triggerNavigation(router, () => {
      header.getComponent({ name: 'ElDropdown' }).vm.$emit('command', 'profile')
    })
    expect(router.currentRoute.value.path).toBe('/app/profile')
  })

  it('opens the mobile drawer and closes it after navigation', async () => {
    authenticated('USER')
    const router = await readyRouter()
    const wrapper = mount(ConsoleLayout, {
      global: {
        plugins: [router],
        stubs: {
          RouterView: { template: '<div data-test="router-view" />' },
          TerminalSessionCoordinator: true,
          ElDropdown: { template: '<div><slot /></div>' },
          ElDropdownMenu: true,
          ElDropdownItem: true,
          ElDrawer: {
            props: ['modelValue'],
            emits: ['update:modelValue'],
            template: '<aside v-if="modelValue" data-test="mobile-drawer"><slot /></aside>',
          },
          ElTooltip: { template: '<span><slot /></span>' },
        },
      },
    })

    await wrapper.get('[data-test=open-mobile-nav]').trigger('click')
    expect(wrapper.find('[data-test=mobile-drawer]').exists()).toBe(true)
    expect(wrapper.get('[data-test=open-mobile-nav] svg').attributes('aria-hidden')).toBe('true')

    await triggerNavigation(router, () => wrapper.get('[data-test=nav-usage]').trigger('click'))
    expect(router.currentRoute.value.path).toBe('/app/usage')
    expect(wrapper.find('[data-test=mobile-drawer]').exists()).toBe(false)

    await wrapper.get('[data-test=open-mobile-nav]').trigger('click')
    expect(wrapper.find('[data-test=mobile-drawer]').exists()).toBe(true)
    await triggerNavigation(router, () => wrapper.get('[data-test=mobile-drawer] .role-label').trigger('click'))
    expect(router.currentRoute.value.path).toBe('/app/profile')
    expect(wrapper.find('[data-test=mobile-drawer]').exists()).toBe(false)
  })

  it('recreates a shared catalog component when switching between Skill and expert routes', async () => {
    let instanceCount = 0
    const SharedCatalog = defineComponent({
      name: 'SharedCatalog',
      setup() {
        const instanceId = ++instanceCount
        return () => h('div', { 'data-test': 'shared-catalog' }, String(instanceId))
      },
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/admin/skills', name: 'admin-skills', component: SharedCatalog },
        { path: '/admin/experts', name: 'admin-experts', component: SharedCatalog },
      ],
    })
    await router.push('/admin/skills')
    await router.isReady()
    const wrapper = mount(ConsoleLayout, {
      global: {
        plugins: [router],
        stubs: {
          HeaderBar: true,
          NotificationBell: true,
          SidebarNav: true,
          TerminalSessionCoordinator: true,
          ElDrawer: true,
        },
      },
    })
    await flushPromises()

    expect(wrapper.get('[data-test=shared-catalog]').text()).toBe('1')
    await router.push('/admin/experts')
    await flushPromises()

    expect(wrapper.get('[data-test=shared-catalog]').text()).toBe('2')
    expect(instanceCount).toBe(2)
  })

  it('provides keyboard access to the main content and contains drawer scrolling', async () => {
    authenticated('USER')
    const wrapper = mount(ConsoleLayout, {
      global: {
        plugins: [await readyRouter()],
        stubs: {
          RouterView: { template: '<div />' },
          HeaderBar: true,
          NotificationBell: true,
          SidebarNav: true,
          TerminalSessionCoordinator: true,
          ElDrawer: { template: '<aside />' },
          ElTooltip: { template: '<span><slot /></span>' },
        },
      },
    })

    expect(wrapper.get('[data-test=skip-main]').attributes('href')).toBe('#main-content')
    expect(elementOverrides).toMatch(/mobile-nav-drawer[\s\S]*overscroll-behavior:\s*contain/)
  })
})

describe('route guards', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('redirects anonymous users to login with their intended destination', async () => {
    const router = isolatedRouter()
    await router.push('/app/quota')

    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/app/quota')
  })

  it('allows an authenticated user to open the wallet route', async () => {
    authenticated('USER')
    const router = isolatedRouter()
    await router.push('/app/wallet')

    expect(router.currentRoute.value.path).toBe('/app/wallet')
    expect(router.currentRoute.value.matched.some((route) => route.meta.requiresAuth)).toBe(true)
  })

  it('redirects USER access to admin routes back to the user dashboard', async () => {
    authenticated('USER')
    const router = isolatedRouter()
    await router.push('/admin/channels')

    expect(router.currentRoute.value.path).toBe('/app/dashboard')
  })

  it('redirects anonymous wallet access to login with the wallet destination', async () => {
    const router = isolatedRouter()
    await router.push('/app/wallet')

    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/app/wallet')
  })

  it('restores the refresh session before the first protected navigation', async () => {
    const restore = vi.fn(async () => {
      authenticated('USER')
      return true
    })
    const router = createConsoleRouter(createMemoryHistory(), restore)

    await router.push('/app/quota')

    expect(restore).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.path).toBe('/app/quota')
  })
})
