import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it } from 'vitest'

import SidebarNav from '../../src/components/layout/SidebarNav.vue'
import { createConsoleRouter } from '../../src/router'
import { useAuthStore } from '../../src/stores/auth'

describe('user file management navigation', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useAuthStore().setSession('user-token', {
      id: 'user-1',
      tenantId: 'tenant-1',
      email: 'user@example.com',
      role: 'USER',
    })
  })

  it('hides file management from users without changing the admin menu', async () => {
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/app/dashboard')
    await router.isReady()

    const wrapper = mount(SidebarNav, {
      global: {
        plugins: [router],
        stubs: { ElTooltip: { template: '<span><slot /></span>' } },
      },
    })

    expect(wrapper.find('[data-test=desktop-nav-files]').exists()).toBe(false)
    const mobileWrapper = mount(SidebarNav, {
      props: { mobile: true },
      global: {
        plugins: [router],
        stubs: { ElTooltip: { template: '<span><slot /></span>' } },
      },
    })
    expect(mobileWrapper.find('[data-test=nav-files]').exists()).toBe(false)

    const filesRoute = router.getRoutes().find((route) => route.name === 'files')
    expect(filesRoute).toBeUndefined()

    await router.push('/app/files')
    expect(router.currentRoute.value.path).toBe('/app/dashboard')
    wrapper.unmount()
    mobileWrapper.unmount()

    useAuthStore().setSession('admin-token', {
      id: 'admin-1',
      tenantId: 'tenant-1',
      email: 'admin@example.com',
      role: 'ADMIN',
    })
    const adminWrapper = mount(SidebarNav, {
      global: {
        plugins: [router],
        stubs: { ElTooltip: { template: '<span><slot /></span>' } },
      },
    })

    expect(adminWrapper.find('[data-test=desktop-nav-files]').exists()).toBe(false)
  })

  it('shows API key management on desktop and mobile and opens its protected page', async () => {
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/app/dashboard')
    await router.isReady()

    const wrapper = mount(SidebarNav, {
      global: {
        plugins: [router],
        stubs: { ElTooltip: { template: '<span><slot /></span>' } },
      },
    })
    const mobileWrapper = mount(SidebarNav, {
      props: { mobile: true },
      global: {
        plugins: [router],
        stubs: { ElTooltip: { template: '<span><slot /></span>' } },
      },
    })

    expect(wrapper.get('[data-test=desktop-nav-api-keys]').attributes('href')).toBe('/app/api-keys')
    expect(mobileWrapper.get('[data-test=nav-api-keys]').attributes('href')).toBe('/app/api-keys')
    const route = router.getRoutes().find((route) => route.name === 'api-keys')
    expect(router.resolve('/app/api-keys').meta.requiresAuth).toBe(true)
    expect(route?.meta.requiresUser).toBe(true)

    await router.push('/app/api-keys')
    expect(router.currentRoute.value.path).toBe('/app/api-keys')
    expect(router.currentRoute.value.name).toBe('api-keys')

    wrapper.unmount()
    mobileWrapper.unmount()
  })
})
