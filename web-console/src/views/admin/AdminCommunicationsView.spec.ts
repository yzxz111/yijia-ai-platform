import { flushPromises, mount } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AdminCommunicationsView from './AdminCommunicationsView.vue'
import BroadcastManager from '../../components/admin/BroadcastManager.vue'
import SupportTicketQueue from '../../components/admin/SupportTicketQueue.vue'
import communicationsSource from './AdminCommunicationsView.vue?raw'
import broadcastManagerSource from '../../components/admin/BroadcastManager.vue?raw'
import supportTicketQueueSource from '../../components/admin/SupportTicketQueue.vue?raw'
import * as admin from '../../api/admin'

vi.mock('../../api/admin', () => ({
  listNotificationBroadcasts: vi.fn(),
  createNotificationBroadcast: vi.fn(),
  scheduleNotificationBroadcast: vi.fn(),
  publishNotificationBroadcast: vi.fn(),
  pauseNotificationBroadcast: vi.fn(),
  resumeNotificationBroadcast: vi.fn(),
  cancelNotificationBroadcast: vi.fn(),
  listAdminSupportTickets: vi.fn(),
  getAdminSupportTicket: vi.fn(),
  listAdminSupportTicketAttachments: vi.fn(),
  getAdminSupportTicketAttachment: vi.fn(),
  claimAdminSupportTicket: vi.fn(),
  batchClaimAdminSupportTickets: vi.fn(),
  processAdminSupportTicket: vi.fn(),
  replyAdminSupportTicket: vi.fn(),
  completeAdminSupportTicket: vi.fn(),
  closeAdminSupportTicket: vi.fn(),
}))

const api = vi.mocked(admin)
const broadcast = {
  id: 'broadcast-1', eventType: 'MAINTENANCE_NOTICE', title: '系统维护通知', body: '今晚升级',
  maintainTime: '2026-08-10 01:00-02:00', providerBusinessNo: 'notice-1', tenantId: null,
  audienceKind: 'ALL_ACTIVE_USERS' as const, status: 'DRAFT' as const, scheduledAt: null, paused: false, recipientCount: 12,
}
const ticket = {
  id: 'ticket-1', ticketNo: 'TK-20260809-001', tenantId: 'tenant-1', userId: 'user-1',
  assigneeUserId: null, title: '调用异常', body: '请求持续失败', status: 'OPEN' as const,
  resolution: null, completionBusinessNo: null, completedAt: null,
  createdAt: '2026-08-09T00:00:00Z', updatedAt: '2026-08-09T00:00:00Z', comments: [],
}

describe('AdminCommunicationsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listNotificationBroadcasts.mockResolvedValue({ items: [broadcast], total: 1, page: 0, size: 20 })
    api.listAdminSupportTickets.mockResolvedValue({ items: [ticket], total: 21, page: 0, size: 20 })
    api.getAdminSupportTicket.mockResolvedValue(ticket)
    api.listAdminSupportTicketAttachments.mockResolvedValue([])
    api.claimAdminSupportTicket.mockResolvedValue({ ...ticket, assigneeUserId: 'admin-1' })
    api.replyAdminSupportTicket.mockResolvedValue(ticket)
    api.createNotificationBroadcast.mockResolvedValue(broadcast)
  })

  it('uses the shared sizing contract for ticket filter controls', () => {
    expect(communicationsSource).toContain('class="admin-toolbar communications-filter ticket-filter"')
  })

  it('loads real broadcast and support queues and lets an administrator claim a ticket', async () => {
    const wrapper = mount(AdminCommunicationsView, { global: { stubs: elementStubs() } })
    await flushPromises()

    expect(api.listNotificationBroadcasts).toHaveBeenCalledWith({ page: 0, size: 20, status: '' })
    expect(api.listAdminSupportTickets).toHaveBeenCalledWith({
      page: 0, size: 20, status: '', query: '', from: null, to: null,
    })
    expect(wrapper.text()).toContain('系统维护通知')

    await wrapper.get('[data-test="communications-tab-tickets"]').trigger('click')
    expect(wrapper.text()).toContain('TK-20260809-001')
    await wrapper.get('[data-test="claim-ticket-ticket-1"]').trigger('click')
    await flushPromises()
    expect(api.claimAdminSupportTicket).toHaveBeenCalledWith('ticket-1')
  })

  it('opens ticket details in a dialog and returns to the list', async () => {
    const wrapper = mount(AdminCommunicationsView, { global: { stubs: elementStubs() } })
    await flushPromises()
    await wrapper.get('[data-test="communications-tab-tickets"]').trigger('click')

    expect(wrapper.find('.ticket-detail').exists()).toBe(false)
    expect(wrapper.get('[data-test="ticket-detail-toggle-ticket-1"]').text()).toContain('查看详情')
    await wrapper.get('.ticket-table-row').trigger('click')
    await flushPromises()

    const detail = wrapper.get('.ticket-detail')
    expect(detail.text()).toContain('调用异常')
    expect(detail.text()).toContain('TK-20260809-001')
    expect(detail.text()).toContain('2026-08-09')
    expect(detail.classes()).toContain('ticket-detail')
    expect(wrapper.find('.communications-table').exists()).toBe(false)

    wrapper.findComponent(SupportTicketQueue).vm.$emit('collapse')
    await flushPromises()
    expect(wrapper.find('#support-ticket-detail').exists()).toBe(false)
    expect(wrapper.find('.communications-table').exists()).toBe(true)
  })

  it('keeps the conversation and reply composer inside the bounded ticket dialog', async () => {
    const wrapper = mount(AdminCommunicationsView, { global: { stubs: elementStubs() } })
    await flushPromises()
    await wrapper.get('[data-test="communications-tab-tickets"]').trigger('click')
    await wrapper.get('[data-test="ticket-detail-toggle-ticket-1"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('#support-ticket-detail').text()).toContain('回复记录')
    expect(wrapper.find('[data-test="ticket-reply-input"]').exists()).toBe(true)
    expect(supportTicketQueueSource).toContain('width="min(1020px, calc(100vw - 32px))"')
    expect(supportTicketQueueSource).toContain('class="ticket-thread"')

    await wrapper.get('[data-test="ticket-reply-input"]').setValue(' 已核实并恢复服务 ')
    await wrapper.get('[data-test="send-ticket-reply"]').trigger('click')
    await flushPromises()

    expect(api.replyAdminSupportTicket).toHaveBeenCalledWith('ticket-1', '已核实并恢复服务')
  })

  it('shows draft validation above the dialog instead of in the page alert', async () => {
    const message = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    const wrapper = mount(AdminCommunicationsView, { global: { stubs: elementStubs() } })
    await flushPromises()

    await wrapper.get('[data-test="create-broadcast"]').trigger('click')
    const save = wrapper.findAll('button').find((button) => button.text().includes('保存草稿'))
    expect(save).toBeDefined()
    await save!.trigger('click')

    expect(message).toHaveBeenCalledWith('请完整填写广播标题、内容和维护时间。')
  })

  it('lets the server generate the business number and shows it in the list', async () => {
    vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    const wrapper = mount(AdminCommunicationsView, { global: { stubs: elementStubs() } })
    await flushPromises()

    await wrapper.get('[data-test="create-broadcast"]').trigger('click')
    expect(wrapper.findAll('label').map(label => label.text())).not.toContain('业务编号')

    const view = wrapper.vm as unknown as {
      createForm: { title: string; body: string; maintainTime: string }
      createMaintainTimeRange: [string, string] | null
      submitBroadcast: () => Promise<void>
    }
    Object.assign(view.createForm, {
      title: '维护通知',
      body: '维护内容',
    })
    view.createMaintainTimeRange = ['2026-08-16 18:00', '2026-08-16 19:00']
    await view.submitBroadcast()
    await flushPromises()

    expect(api.createNotificationBroadcast).toHaveBeenCalledWith(
      expect.not.objectContaining({ providerBusinessNo: expect.anything() }),
    )
    expect(api.createNotificationBroadcast).toHaveBeenCalledWith(
      expect.objectContaining({ maintainTime: '2026-08-16 18:00 - 2026-08-16 19:00' }),
    )
    expect(wrapper.text()).toContain('notice-1')
  })

  it('offers immediate publishing and a local date-time picker for scheduling', async () => {
    const wrapper = mount(AdminCommunicationsView, { global: { stubs: elementStubs() } })
    await flushPromises()

    expect(wrapper.find('[data-test="publish-broadcast"]').exists()).toBe(true)
    expect(broadcastManagerSource).toContain('发布广播')
    expect(communicationsSource).toContain('<el-date-picker')
    expect(communicationsSource).toContain('type="datetimerange"')
    expect(communicationsSource).not.toContain('请输入计划发送时间（ISO 8601')
  })

  it('loads the selected broadcast page through the paginated broadcast panel', async () => {
    const wrapper = mount(AdminCommunicationsView, { global: { stubs: elementStubs() } })
    await flushPromises()

    wrapper.findComponent(BroadcastManager).vm.$emit('page-change', 2)
    await flushPromises()

    expect(api.listNotificationBroadcasts).toHaveBeenLastCalledWith({
      page: 1,
      size: 20,
      status: '',
    })
  })

  it('loads the selected support-ticket page through its own pager', async () => {
    api.listAdminSupportTickets.mockImplementation(async ({ page, size }) => ({
      items: [ticket], total: 21, page, size,
    }))
    const wrapper = mount(AdminCommunicationsView, { global: { stubs: elementStubs() } })
    await flushPromises()
    await wrapper.get('[data-test="communications-tab-tickets"]').trigger('click')

    wrapper.findComponent(SupportTicketQueue).vm.$emit('page-change', 2)
    await flushPromises()

    expect(api.listAdminSupportTickets.mock.calls.filter(([params]) => params.size === 20).at(-1)?.[0]).toEqual({
      page: 1,
      size: 20,
      status: '',
      query: '',
      from: null,
      to: null,
    })
    expect(wrapper.findComponent(SupportTicketQueue).props('page')).toBe(1)
  })

  it('returns to the last valid support-ticket page after a filtered mutation empties the current page', async () => {
    let pageOneLoads = 0
    api.listAdminSupportTickets.mockImplementation(async ({ page, size }) => {
      if (page === 1) {
        pageOneLoads += 1
        return pageOneLoads === 1
          ? { items: [ticket], total: 21, page: 1, size }
          : { items: [], total: 20, page: 1, size }
      }
      return { items: [ticket], total: 20, page: 0, size }
    })

    const wrapper = mount(AdminCommunicationsView, { global: { stubs: elementStubs() } })
    await flushPromises()
    await wrapper.get('[data-test="communications-tab-tickets"]').trigger('click')
    wrapper.findComponent(SupportTicketQueue).vm.$emit('page-change', 2)
    await flushPromises()

    await wrapper.get('[data-test="claim-ticket-ticket-1"]').trigger('click')
    await flushPromises()

    expect(api.listAdminSupportTickets.mock.calls.filter(([params]) => params.size === 20).at(-1)?.[0]).toEqual({
      page: 0,
      size: 20,
      status: '',
      query: '',
      from: null,
      to: null,
    })
    expect(wrapper.findComponent(SupportTicketQueue).props('page')).toBe(0)
  })
})

function elementStubs() {
  return {
    'el-alert': { template: '<div><slot /></div>', props: ['title'] },
    'el-button': { template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>' },
    'el-dialog': { template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>', props: ['modelValue'] },
    'el-date-picker': { template: '<input data-test="schedule-date-time" />' },
    'el-input': {
      props: ['modelValue', 'type'],
      emits: ['update:modelValue'],
      template: '<textarea v-if="type === \'textarea\'" v-bind="$attrs" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" /><input v-else v-bind="$attrs" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
    },
    'el-option': true,
    'el-pagination': true,
    'el-select': { template: '<select><slot /></select>' },
    'el-tag': { template: '<span><slot /></span>' },
  }
}
