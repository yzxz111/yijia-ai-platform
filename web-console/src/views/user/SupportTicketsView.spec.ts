import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import SupportTicketsView from './SupportTicketsView.vue'
import supportTicketsSource from './SupportTicketsView.vue?raw'
import { useAuthStore } from '../../stores/auth'

const api = vi.hoisted(() => ({ closeSupportTicket: vi.fn(), commentSupportTicket: vi.fn(), createSupportTicket: vi.fn(), getSupportTicket: vi.fn(), getSupportTicketAttachment: vi.fn(), listSupportTicketAttachments: vi.fn(), listSupportTickets: vi.fn() }))
const confirm = vi.hoisted(() => vi.fn())
vi.mock('../../api/support', () => api)
vi.mock('element-plus', () => ({ ElMessageBox: { confirm }, ElMessage: { error: vi.fn(), success: vi.fn(), warning: vi.fn() } }))

const ticket = {
  id: '19586ef1-6b22-44d2-af4c-d0a2412bef6c', ticketNo: 'ST-20260809-001', tenantId: 'tenant', userId: 'user', assigneeUserId: null,
  title: '一个会在手机窄屏中自然换行且不撑破布局的超长技术支持工单标题', body: '无法调用接口，需要协助排查。', status: 'OPEN', resolution: null,
  completionBusinessNo: null, completedAt: null, createdAt: '2026-08-09T03:00:00Z', updatedAt: '2026-08-09T03:00:00Z',
  comments: [
    { id: 'c0', authorUserId: 'support', authorRole: 'ADMIN', body: '正在排查请求日志。', createdAt: '2026-08-09T03:30:00Z' },
    { id: 'c1', authorUserId: 'user', authorRole: 'USER', body: '补充：问题仍然存在。', createdAt: '2026-08-09T03:35:00Z' },
  ],
}

const stubs = {
  'el-button': { props: ['disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  'el-dialog': { props: ['modelValue'], template: '<section v-if="modelValue"><slot name="header" /><slot /><slot name="footer" /></section>' },
  'el-drawer': { props: ['modelValue'], template: '<aside v-if="modelValue"><slot /></aside>' },
  'el-empty': { template: '<div>暂无工单</div>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<label><slot /></label>' },
  'el-input': { props: ['modelValue'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  'el-pagination': {
    props: ['currentPage', 'total'],
    emits: ['current-change'],
    template: '<button data-test="ticket-pagination" @click="$emit(\'current-change\', 2)">next</button>',
  },
  'el-tag': { template: '<span><slot /></span>' },
}

describe('SupportTicketsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirm.mockResolvedValue('confirm')
    api.listSupportTickets.mockResolvedValue({
      data: { items: [ticket], total: 21, page: 0, size: 5 },
    })
    api.getSupportTicket.mockResolvedValue({ data: ticket })
    api.listSupportTicketAttachments.mockResolvedValue({ data: [] })
    api.createSupportTicket.mockResolvedValue({ data: ticket })
    api.commentSupportTicket.mockResolvedValue({ data: { ...ticket, comments: [{ id: 'c1', authorUserId: 'user', authorRole: 'USER', body: '补充复现步骤', createdAt: '2026-08-09T04:00:00Z' }] } })
    api.closeSupportTicket.mockResolvedValue({ data: { ...ticket, status: 'CLOSED' } })
  })

  function render() {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.setSession('access-token', { id: 'user', tenantId: 'tenant', email: null, role: 'USER' })
    auth.replaceAvatarObjectUrl('blob:user-avatar', 'avatar-v1')
    return mount(SupportTicketsView, { global: { plugins: [pinia], stubs, directives: { loading: () => undefined } } })
  }

  it('loads and changes server-backed ticket pages', async () => {
    const wrapper = render()
    await flushPromises()

    expect(api.listSupportTickets).toHaveBeenCalledWith(0, 5)
    await wrapper.get('[data-test="ticket-pagination"]').trigger('click')
    await flushPromises()
    expect(api.listSupportTickets).toHaveBeenLastCalledWith(1, 5)
  })

  it('creates a validated ticket and keeps long titles responsive', async () => {
    const wrapper = render()
    await flushPromises()
    expect(supportTicketsSource).toMatch(/\.ticket-title\s*\{[^}]*overflow-wrap:\s*anywhere/s)
    expect(wrapper.get('.ticket-title').text()).toContain('超长技术支持工单标题')

    await wrapper.get('[data-test="new-ticket"]').trigger('click')
    const inputs = wrapper.findAll('textarea')
    await inputs[0].setValue('新工单标题')
    await inputs[1].setValue('这是足够详细的问题描述内容。')
    await wrapper.get('[data-test="create-ticket"]').trigger('click')
    await flushPromises()
    expect(api.createSupportTicket).toHaveBeenCalledWith({ title: '新工单标题', body: '这是足够详细的问题描述内容。' }, [])
  })

  it('loads detail, comments, and closes with status text rather than color alone', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.get('[data-test="ticket-row"]').trigger('click')
    await flushPromises()
    expect(api.getSupportTicket).toHaveBeenCalledWith(ticket.id)
    expect(wrapper.get('.detail-status-tag').text()).toContain('待处理')
    expect(wrapper.get('.ticket-detail-meta').text()).toContain(ticket.ticketNo)
    expect(wrapper.get('.ticket-detail-title').text()).toContain('超长技术支持工单标题')
    expect(wrapper.get('.ticket-detail-body').text()).toContain('无法调用接口')
    const commentRows = wrapper.findAll('.comments article')
    expect(commentRows).toHaveLength(2)
    expect(commentRows[0].classes()).not.toContain('mine')
    expect(commentRows[0].get('.comment-author').text()).toBe('支持人员')
    expect(commentRows[1].classes()).toContain('mine')
    expect(commentRows[1].get('.comment-author').text()).toBe('我')
    expect(commentRows[1].get('time').attributes('datetime')).toBe('2026-08-09T03:35:00Z')
    expect(wrapper.get('.reply-actions').findAll('button')).toHaveLength(2)

    await wrapper.get('[data-test="comment-input"]').setValue('补充复现步骤')
    await wrapper.get('[data-test="submit-comment"]').trigger('click')
    await flushPromises()
    expect(api.commentSupportTicket).toHaveBeenCalledWith(ticket.id, '补充复现步骤', [])

    await wrapper.get('[data-test="close-ticket"]').trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(api.closeSupportTicket).toHaveBeenCalledWith(ticket.id)
    expect(wrapper.text()).toContain('已关闭')
  })

  it('keeps the detail dialog responsive and distinguishes customer replies', () => {
    expect(supportTicketsSource).not.toContain('<el-drawer')
    expect(supportTicketsSource).toContain('width="min(820px, 94vw)"')
    expect(supportTicketsSource).toMatch(/\.ticket-detail-dialog \.el-dialog__body\)\s*\{[^}]*overflow-y:\s*auto/s)
    expect(supportTicketsSource).toContain('class="comment-meta"')
    expect(supportTicketsSource).toMatch(/\.comments article\.mine\s*\{[^}]*justify-content:\s*flex-end/)
    expect(supportTicketsSource).toMatch(/\.comment-bubble\s*\{[^}]*width:\s*min\(84%,\s*520px\)/)
    expect(supportTicketsSource).toContain('multiple :accept="attachmentAccept"')
  })
})
