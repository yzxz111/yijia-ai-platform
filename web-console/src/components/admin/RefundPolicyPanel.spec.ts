import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import RefundPolicyPanel from './RefundPolicyPanel.vue'
import { listRefundPolicies, publishRefundPolicy } from '../../api/admin'

vi.mock('../../api/admin', () => ({
  listRefundPolicies: vi.fn(),
  publishRefundPolicy: vi.fn(),
}))

const listPolicies = vi.mocked(listRefundPolicies)
const publishPolicy = vi.mocked(publishRefundPolicy)

function mountPanel() {
  return mount(RefundPolicyPanel, {
    global: {
      stubs: {
        FilePlus2: { template: '<span />' },
        'el-alert': { template: '<div />' },
        'el-skeleton': { template: '<div />' },
        'el-input': {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
        'el-button': {
          props: ['disabled', 'loading'],
          emits: ['click'],
          template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
        },
        'el-pagination': {
          props: ['currentPage'],
          emits: ['current-change'],
          template: '<button class="pager" @click="$emit(\'current-change\', Number(currentPage) + 1)">next</button>',
        },
      },
    },
  })
}

describe('RefundPolicyPanel pagination', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listPolicies.mockResolvedValue({
      items: [{
        version: 1,
        feeBps: 100,
        minimumFee: 0,
        refundWindowDays: 7,
        maximumUsageBps: 1000,
        effectiveAt: '2030-01-01T00:00:00Z',
        published: true,
        createdBy: 'admin-1',
        createdAt: '2029-12-01T00:00:00Z',
      }],
      total: 21,
      page: 0,
      size: 20,
    })
  })

  it('requests and changes server pages', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(listPolicies).toHaveBeenCalledWith(0, 20)
    await wrapper.get('.pager').trigger('click')
    await flushPromises()
    expect(listPolicies).toHaveBeenLastCalledWith(1, 20)
  })

  it('returns to the first page and reloads after publishing', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.get('.pager').trigger('click')
    await flushPromises()

    const values: Record<string, string> = {
      'policy-version': '2',
      'policy-fee-bps': '200',
      'policy-minimum-fee': '0',
      'policy-window-days': '7',
      'policy-maximum-usage-bps': '1000',
      'policy-effective-at': '2099-01-01T00:00:00Z',
    }
    for (const [name, value] of Object.entries(values)) {
      await wrapper.get(`[data-test="${name}"]`).setValue(value)
    }
    publishPolicy.mockResolvedValue({} as never)
    await wrapper.get('[data-test="publish-policy"]').trigger('click')
    await flushPromises()

    expect(publishPolicy).toHaveBeenCalledTimes(1)
    expect(listPolicies).toHaveBeenLastCalledWith(0, 20)
  })
})
