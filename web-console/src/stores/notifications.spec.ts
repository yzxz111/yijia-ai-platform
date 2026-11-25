import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import NotificationBell from '../components/notifications/NotificationBell.vue'
import { useNotificationsStore } from './notifications'

const api = vi.hoisted(() => ({ getUnreadCount: vi.fn() }))
vi.mock('../api/notifications', () => api)

describe('notification unread state', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('renders a stable 99+ badge from the real unread endpoint result', async () => {
    api.getUnreadCount.mockResolvedValue({ data: { count: 130 } })
    const wrapper = mount(NotificationBell, {
      global: {
        plugins: [createPinia()],
        stubs: {
          RouterLink: { template: '<a href="/app/notifications"><slot /></a>' },
          'el-tooltip': { template: '<span><slot /></span>' },
        },
      },
    })
    await flushPromises()

    expect(api.getUnreadCount).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-test="notification-badge"]').text()).toBe('99+')
    expect(wrapper.get('[data-test="notification-bell"]').attributes('aria-label')).toContain('130')
  })

  it('ignores a stale unread refresh and stops polling cleanly', async () => {
    let resolveFirst!: (value: { data: { count: number } }) => void
    api.getUnreadCount
      .mockReturnValueOnce(new Promise((resolve) => { resolveFirst = resolve }))
      .mockResolvedValueOnce({ data: { count: 4 } })
    const store = useNotificationsStore()

    const first = store.refreshUnreadCount()
    await store.refreshUnreadCount()
    resolveFirst({ data: { count: 88 } })
    await first
    expect(store.unreadCount).toBe(4)

    vi.useFakeTimers()
    store.startPolling(10_000)
    await flushPromises()
    expect(api.getUnreadCount).toHaveBeenCalledTimes(3)
    store.stopPolling()
    await vi.advanceTimersByTimeAsync(20_000)
    expect(api.getUnreadCount).toHaveBeenCalledTimes(3)
    vi.useRealTimers()
  })
})
