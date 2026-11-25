import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import SettlementDeficitView from './SettlementDeficitView.vue'
import * as admin from '../../api/admin'

vi.mock('../../api/admin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/admin')>()),
  getSettlementDeficit: vi.fn(),
}))

const adminApi = vi.mocked(admin)

function mountView() {
  return mount(SettlementDeficitView, {
    global: {
      stubs: {
        'el-button': { template: '<button><slot /></button>' },
        'el-skeleton': { template: '<div data-test="deficit-skeleton" />' },
      },
    },
  })
}

describe('settlement deficit view', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows what the platform paid and could not collect, in yuan', async () => {
    adminApi.getSettlementDeficit.mockResolvedValue({
      totalAmount: 123_456,
      totalTokens: 98_765,
      count: 17,
      truncated: true,
      items: [
        {
          reservationId: '21000000-0000-4000-8000-000000000001',
          amount: 9_900,
          tokens: 12_000,
          occurredAt: '2026-09-14T03:04:05Z',
          planCode: 'STARTER',
          userId: '31000000-0000-4000-8000-000000000002',
        },
      ],
    })

    const wrapper = mountView()
    await flushPromises()

    expect(adminApi.getSettlementDeficit).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-test="deficit-total"]').text()).toBe('¥1,234.56')
    expect(wrapper.get('[data-test="deficit-count"]').text()).toBe('17')
    expect(wrapper.get('[data-test="deficit-tokens"]').text()).toBe('98,765')
    expect(wrapper.get('[data-test="deficit-scope"]').text()).toBe('仅最近若干条')
    expect(wrapper.get('[data-test="deficit-note"]').text()).toContain('没有从客户收到对应的钱')
    expect(wrapper.get('[data-test="deficit-note"]').text()).toContain('覆盖全部 17 笔')
    expect(wrapper.text()).toContain('STARTER')
    expect(wrapper.text()).toContain('21000000-0000-4000-8000-000000000001')
    expect(wrapper.get('.settlement-deficit__amount').text()).toBe('¥99.00')
  })

  it('marks a complete item list as covering everything', async () => {
    adminApi.getSettlementDeficit.mockResolvedValue({
      totalAmount: 100,
      totalTokens: 10,
      count: 1,
      truncated: false,
      items: [{
        reservationId: 'r-1', amount: 100, tokens: 10,
        occurredAt: '2026-09-14T03:04:05Z', planCode: null, userId: null,
      }],
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('[data-test="deficit-scope"]').text()).toBe('全部明细')
    expect(wrapper.get('[data-test="deficit-note"]').text()).not.toContain('只列出最近')
  })

  it('renders missing plan and user as dashes instead of blanks', async () => {
    adminApi.getSettlementDeficit.mockResolvedValue({
      totalAmount: 1, totalTokens: 1, count: 1, truncated: false,
      items: [{ reservationId: 'r-2', amount: 1, tokens: 1, occurredAt: '2026-09-14T03:04:05Z', planCode: null, userId: null }],
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.findAll('tbody code').map((cell) => cell.text())).toEqual(['—', '—', 'r-2'])
  })

  it('keeps the totals honest when the endpoint is not available yet', async () => {
    adminApi.getSettlementDeficit.mockRejectedValue({ response: { status: 503 } })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('[data-test="deficit-unavailable"]').text()).toContain('暂时取不到结算垫付数据')
    expect(wrapper.find('[data-test="deficit-total"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('¥0.00')
    expect(document.body.textContent).toContain('结算垫付数据加载失败')
    expect(wrapper.find('[data-test="deficit-refresh"]').exists()).toBe(true)
  })

  it('reloads the report on demand', async () => {
    adminApi.getSettlementDeficit.mockResolvedValue({
      totalAmount: 0, totalTokens: 0, count: 0, truncated: false, items: [],
    })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-test="deficit-refresh"]').trigger('click')
    await flushPromises()

    expect(adminApi.getSettlementDeficit).toHaveBeenCalledTimes(2)
  })

  it('says so when the totals have no item detail behind them', async () => {
    adminApi.getSettlementDeficit.mockResolvedValue({
      totalAmount: 500, totalTokens: 5, count: 3, truncated: true, items: [],
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('[data-test="deficit-empty"]').text()).toContain('接口未返回明细')
    expect(wrapper.get('[data-test="deficit-total"]').text()).toBe('¥5.00')
  })

  it('reports a truthful zero as zero, with zero-request wording', async () => {
    adminApi.getSettlementDeficit.mockResolvedValue({
      totalAmount: 0, totalTokens: 0, count: 0, truncated: false, items: [],
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('[data-test="deficit-total"]').text()).toBe('¥0.00')
    expect(wrapper.get('[data-test="deficit-count"]').text()).toBe('0')
    expect(wrapper.get('[data-test="deficit-empty"]').text()).toBe('当前没有结算垫付记录')
    expect(wrapper.find('[data-test="deficit-unavailable"]').exists()).toBe(false)
  })

  it('does not claim the item list is complete when the API omits truncated', async () => {
    // 线上实测：结算接口没有返回 truncated 字段
    adminApi.getSettlementDeficit.mockResolvedValue({
      totalAmount: 0, totalTokens: 0, count: 0, items: [],
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('[data-test="deficit-scope"]').text()).toBe('—')
    expect(wrapper.get('[data-test="deficit-note"]').text()).not.toContain('只列出最近')
  })
})
