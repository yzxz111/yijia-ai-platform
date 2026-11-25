import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import RefundReviewPanel from './RefundReviewPanel.vue'
import * as admin from '../../api/admin'

vi.mock('../../api/admin', () => ({
  listRefunds: vi.fn(),
  getAdminRefund: vi.fn(),
  approveRefund: vi.fn(),
  rejectRefund: vi.fn(),
}))

const api = vi.mocked(admin)

describe('RefundReviewPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listRefunds.mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
  })

  it('filters completed refunds by the routed refund number and reloads when it changes', async () => {
    const wrapper = mount(RefundReviewPanel, {
      props: { initialQuery: 'RF-20260808-001' },
      global: { stubs: elementStubs() },
    })
    await flushPromises()

    expect(api.listRefunds).toHaveBeenCalledWith(expect.objectContaining({
      query: 'RF-20260808-001', page: 0,
    }))

    await wrapper.setProps({ initialQuery: 'RF-20260808-002' })
    await flushPromises()
    expect(api.listRefunds).toHaveBeenLastCalledWith(expect.objectContaining({
      query: 'RF-20260808-002', page: 0,
    }))
  })
})

function elementStubs() {
  return {
    'el-alert': true,
    'el-input': { template: '<input />' },
    'el-select': { template: '<select><slot /></select>' },
    'el-option': true,
    'el-button': { template: '<button><slot /></button>' },
    'el-skeleton': true,
    'el-pagination': true,
    'el-tag': { template: '<span><slot /></span>' },
  }
}
