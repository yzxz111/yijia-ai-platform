import ElementPlus from 'element-plus'
import { createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { RouterView } from 'vue-router'

const adminApi = vi.hoisted(() => ({
  listAdminOrders: vi.fn(),
  listPaymentAnomalies: vi.fn(),
  listPaymentConfigs: vi.fn(),
  listPayments: vi.fn(),
  listRefundPolicies: vi.fn(),
  listRefunds: vi.fn(),
  startReconciliation: vi.fn(),
}))

const dashboardApi = vi.hoisted(() => ({
  listApiKeys: vi.fn(),
  listUsage: vi.fn(),
  quotaSummary: vi.fn(),
  usageMetrics: vi.fn(),
}))

vi.mock('../../src/api/admin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/api/admin')>()),
  ...adminApi,
}))

vi.mock('../../src/api/gateway', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/api/gateway')>()),
  ...dashboardApi,
}))

import { createConsoleRouter } from '../../src/router'
import { useAuthStore } from '../../src/stores/auth'

describe('admin routes', () => {
  it('lazy loads every operational admin view instead of a placeholder', () => {
    const router = createConsoleRouter(createMemoryHistory(), async () => false)
    const adminRoutes = [
      'admin-overview',
      'admin-users',
      'admin-grants',
      'admin-plans',
      'admin-models',
      'admin-skills',
      'admin-experts',
      'admin-teams',
      'admin-usage',
      'admin-orders',
      'admin-wallet',
      'admin-communications',
      'admin-operations',
      'admin-legal-documents',
      'admin-audit',
    ]

    for (const name of adminRoutes) {
      const route = router.getRoutes().find((candidate) => candidate.name === name)
      expect(route?.components?.default, `${name} must be lazy loaded`).toBeTypeOf('function')
    }
  })

  it('keeps the Skill catalog administrator-only in the Web console', () => {
    const router = createConsoleRouter(createMemoryHistory(), async () => false)

    expect(router.getRoutes().find((candidate) => candidate.name === 'skills')).toBeUndefined()
    expect(router.getRoutes().find((candidate) => candidate.name === 'admin-skills')?.meta.requiresAdmin).toBe(true)
    expect(router.getRoutes().find((candidate) => candidate.name === 'admin-experts')?.meta.requiresAdmin).toBe(true)
    expect(router.getRoutes().find((candidate) => candidate.name === 'admin-teams')?.meta.requiresAdmin).toBe(true)
  })

  it('redirects the legacy channel page without dropping its filters', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().setSession('admin-token', {
      id: 'admin-1', tenantId: 'tenant-1', email: 'admin@example.com', role: 'ADMIN',
    })
    const router = createConsoleRouter(createMemoryHistory(), async () => false)

    await router.push('/admin/channels?query=bailian&status=ENABLED')

    expect(router.currentRoute.value.path).toBe('/admin/models')
    expect(router.currentRoute.value.query).toEqual({
      query: 'bailian', status: 'ENABLED',
    })
  })

  it('allows ADMIN users to open the guarded wallet administration route', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().setSession('admin-token', {
      id: 'admin-1', tenantId: 'tenant-1', email: 'admin@example.com', role: 'ADMIN',
    })
    const router = createConsoleRouter(createMemoryHistory(), async () => true)

    await router.push('/admin/wallet')

    expect(router.currentRoute.value.fullPath).toBe('/admin/wallet')
    expect(router.currentRoute.value.matched.some((route) => route.meta.requiresAdmin)).toBe(true)
  })

  it('allows ADMIN users to navigate to notifications and support from the sidebar route', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().setSession('admin-token', {
      id: 'admin-1', tenantId: 'tenant-1', email: 'admin@example.com', role: 'ADMIN',
    })
    const router = createConsoleRouter(createMemoryHistory(), async () => true)

    await router.push('/admin/content-safety')
    await router.push('/admin/communications')

    expect(router.currentRoute.value.fullPath).toBe('/admin/communications')
    expect(router.currentRoute.value.name).toBe('admin-communications')
  })

  it('redirects ordinary users away from wallet administration', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().setSession('user-token', {
      id: 'user-1', tenantId: 'tenant-1', email: 'user@example.com', role: 'USER',
    })
    const router = createConsoleRouter(createMemoryHistory(), async () => true)

    await router.push('/admin/wallet')

    expect(router.currentRoute.value.fullPath).toBe('/app/dashboard')
    expect(router.currentRoute.value.matched.some((route) => route.meta.requiresAdmin)).toBe(false)
  })

  it('redirects an ordinary user away from administrator transaction controls', async () => {
    dashboardApi.listUsage.mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
    dashboardApi.quotaSummary.mockResolvedValue({ activePackage: null, queuedPackages: [] })
    dashboardApi.usageMetrics.mockResolvedValue({ sampleCount: 0, platformAddedP95Ms: 0 })
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().setSession('user-token', {
      id: 'user-1', tenantId: 'tenant-1', email: 'user@example.com', role: 'USER',
    })
    const router = createConsoleRouter(createMemoryHistory(), async () => true)
    const wrapper = mount(RouterView, { global: { plugins: [pinia, router, ElementPlus] } })

    await router.push('/admin/orders')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/app/dashboard')
    expect(router.currentRoute.value.matched.some((route) => route.meta.requiresAdmin)).toBe(false)
    expect(wrapper.find('[data-test=orders-filter]').exists()).toBe(false)
    expect(wrapper.find('[data-test=anomaly-filter]').exists()).toBe(false)
    expect(wrapper.find('[data-test=start-reconciliation]').exists()).toBe(false)
    // 用户 Dashboard 当前只拉用量、额度与用量指标；API Key 管理已从用户端移除，
    // 因此 listApiKeys 不再是 Dashboard 的调用，断言只覆盖它真正发起的请求。
    expect(dashboardApi.listUsage).toHaveBeenCalledOnce()
    expect(dashboardApi.quotaSummary).toHaveBeenCalledOnce()
    expect(dashboardApi.usageMetrics).toHaveBeenCalledOnce()
    expect(dashboardApi.listApiKeys).not.toHaveBeenCalled()
    for (const method of Object.values(adminApi)) expect(method).not.toHaveBeenCalled()
  })
})
