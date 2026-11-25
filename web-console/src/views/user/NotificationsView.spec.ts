import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import NotificationsView from './NotificationsView.vue'
import notificationsSource from './NotificationsView.vue?raw'

const api = vi.hoisted(() => ({
  getNotificationPreferences: vi.fn(),
  listNotifications: vi.fn(),
  markAllNotificationsRead: vi.fn(),
  markNotificationRead: vi.fn(),
  unsubscribeSms: vi.fn(),
  updateNotificationPreferences: vi.fn(),
}))
const confirm = vi.hoisted(() => vi.fn())

vi.mock('../../api/notifications', () => api)
vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
  ElMessageBox: { confirm },
}))

const notification = {
  id: '7b503cab-9728-4c33-bfbc-f4d49fb4e9b1', category: 'BILLING', eventType: 'PAYMENT_FAILED',
  title: '这是一条用于验证窄屏自动换行而不会产生横向滚动的很长通知标题', summary: '支付失败，请检查支付方式。',
  aggregateType: 'ORDER', aggregateId: 'O-1', readAt: null, archivedAt: null, createdAt: '2026-08-09T03:00:00Z',
}
const preferences = [
  { category: 'SECURITY', channel: 'IN_APP', enabled: true, quietStart: '22:00:00', quietEnd: '08:00:00', timezone: 'Asia/Shanghai' },
  { category: 'SECURITY', channel: 'SMS', enabled: true, quietStart: '22:00:00', quietEnd: '08:00:00', timezone: 'Asia/Shanghai' },
  { category: 'OPERATIONS', channel: 'SMS', enabled: false, quietStart: '22:00:00', quietEnd: '08:00:00', timezone: 'Asia/Shanghai' },
]

const stubs = {
  'el-tooltip': { template: '<span><slot /></span>' },
  'el-button': { props: ['disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\', $event)"><slot /></button>' },
  'el-empty': { template: '<div><slot />暂无通知</div>' },
  'el-dialog': { props: ['modelValue'], template: '<section v-if="modelValue" class="dialog"><slot /><slot name="footer" /></section>' },
  'el-pagination': { emits: ['size-change'], template: '<div class="pagination"><button class="size-5" @click="$emit(\'size-change\', 5)">5</button></div>' },
  'el-select': { props: ['modelValue'], emits: ['update:modelValue'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>' },
  'el-option': { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
  'el-switch': { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<input type="checkbox" :checked="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.checked)" />' },
  'el-time-picker': { template: '<input />' },
  'el-tabs': { props: ['modelValue'], emits: ['update:modelValue'], template: '<div><button data-test="tab-unread" @click="$emit(\'update:modelValue\', \'unread\')">未读</button><button data-test="tab-preferences" @click="$emit(\'update:modelValue\', \'preferences\')">偏好设置</button><slot /></div>' },
  'el-tab-pane': { props: ['name'], template: '<section><slot /></section>' },
  'el-skeleton': { template: '<div>加载中</div>' },
}

describe('NotificationsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    notification.readAt = null
    api.listNotifications.mockResolvedValue({ data: { items: [notification], total: 1, page: 0, size: 20 } })
    api.getNotificationPreferences.mockResolvedValue({ data: preferences })
    api.markNotificationRead.mockResolvedValue({})
    api.markAllNotificationsRead.mockResolvedValue({})
    api.updateNotificationPreferences.mockResolvedValue({ data: preferences })
    api.unsubscribeSms.mockResolvedValue({})
    confirm.mockResolvedValue('confirm')
  })

  function render() {
    return mount(NotificationsView, { global: { plugins: [createPinia()], stubs, directives: { loading: () => undefined } } })
  }

  it('loads descending notifications, applies unread/category filters, and performs item actions', async () => {
    const wrapper = render()
    await flushPromises()
    expect(api.listNotifications).toHaveBeenCalledWith({ page: 0, size: 20 })
    expect(wrapper.get('[data-test="notification-row"]').text()).toContain('很长通知标题')
    expect(notificationsSource).toMatch(/\.notif-title\s*\{[^}]*overflow-wrap:\s*anywhere/s)
    expect(wrapper.find('.pagination').exists()).toBe(true)

    await wrapper.get('[data-test="notif-tab-unread"]').trigger('click')
    await flushPromises()
    expect(api.listNotifications).toHaveBeenLastCalledWith({ page: 0, size: 20, read: false })

    await wrapper.get('[data-test="category-filter"]').setValue('BILLING')
    await flushPromises()
    expect(api.listNotifications).toHaveBeenLastCalledWith({ category: 'BILLING', page: 0, size: 20, read: false })

    expect(wrapper.find('[data-test="archive"]').exists()).toBe(false)
    await wrapper.get('[data-test="mark-read"]').trigger('click')
    expect(api.markNotificationRead).toHaveBeenCalledWith(notification.id)

    await wrapper.get('.size-5').trigger('click')
    await flushPromises()
    expect(api.listNotifications).toHaveBeenLastCalledWith({ category: 'BILLING', page: 0, size: 5, read: false })
  })

  it('opens the complete notification in one dialog and keeps security treatment semantic', async () => {
    api.listNotifications.mockResolvedValue({
      data: { items: [{ ...notification, category: 'SECURITY', summary: '完整安全通知正文' }], total: 1, page: 0, size: 20 },
    })
    const wrapper = render()
    await flushPromises()

    expect(wrapper.get('[data-test="notification-row"]').classes()).toContain('cat-security')
    await wrapper.get('[data-test="notification-row"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('.dialog').text()).toContain('完整安全通知正文')
    expect(api.markNotificationRead).toHaveBeenCalledWith(notification.id)
    expect(notificationsSource).toContain(':page-sizes="[5, 10, 20]"')
  })

  it('shows retry after an error and confirms unsubscribe while mandatory controls stay disabled', async () => {
    api.listNotifications.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ data: { items: [], total: 0, page: 0, size: 20 } })
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('通知加载失败')
    await wrapper.get('[data-test="retry-notifications"]').trigger('click')
    await flushPromises()
    expect(api.listNotifications.mock.calls.filter(([params]) => params.size === 20)).toHaveLength(2)

    await wrapper.get('[data-test="notif-tab-prefs"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-test="mandatory-channel"]').every((node) => node.attributes('disabled') !== undefined)).toBe(true)
    await wrapper.get('[data-test="unsubscribe-sms"]').trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalled()
    expect(api.unsubscribeSms).toHaveBeenCalledTimes(1)
  })
})
