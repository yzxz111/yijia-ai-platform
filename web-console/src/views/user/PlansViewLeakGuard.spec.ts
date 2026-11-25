import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import PlansView from './PlansView.vue'
import * as platform from '../../api/platform'
import type { PackagePlan } from '../../api/platform'
import * as gateway from '../../api/gateway'

vi.mock('../../api/platform', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/platform')>()),
  listPlans: vi.fn(),
  claimFreePlan: vi.fn(),
  getFreePlanClaimStatus: vi.fn(),
}))

vi.mock('../../api/gateway', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/gateway')>()),
  getPackageLimitUsage: vi.fn(),
  quotaSummary: vi.fn(),
}))

const platformApi = vi.mocked(platform)
const gatewayApi = vi.mocked(gateway)

function plan(overrides: Partial<PackagePlan> & Pick<PackagePlan, 'code'>): PackagePlan {
  return {
    name: overrides.code,
    description: null,
    kind: 'STANDARD',
    displayOrder: 0,
    versionId: `version-${overrides.code}`,
    version: 1,
    priceAmount: 9900,
    currency: 'CNY',
    totalTokenQuota: 1_000_000,
    usageDimension: 'TOKEN',
    durationDays: 30,
    maxApiKeys: 3,
    requestsPerMinute: 60,
    tokensPerMinute: 20_000,
    maxConcurrency: 5,
    dailyRequestLimit: 1_000,
    maxOutputTokens: 4_096,
    maxFileSizeBytes: 0,
    oncePerUser: false,
    stockTotal: null,
    stockSold: 0,
    stockReserved: 0,
    lowStockThreshold: 0,
    maxPurchasesPerUser: null,
    remainingStock: null,
    soldOut: false,
    lowStock: false,
    estimatedCostAmount: null,
    estimatedMarginRatio: null,
    marginWarning: false,
    models: [],
    ...overrides,
  }
}

const textPlan = plan({ code: 'STARTER', name: '入门版' })

const videoPlan = plan({
  code: 'VIDEO',
  name: '视频秒数版',
  usageDimension: 'VIDEO_SECOND',
  totalTokenQuota: 60,
  tokensPerMinute: 600,
})

/**
 * 后端会把成本与毛利字段一起发给用户端套餐列表，但它们是内部成本结构：
 * 用户端不得渲染（否则等于把供应商成本与毛利率告诉每一个潜在客户）。
 */
const warnedPlan = plan({
  code: 'ADVANCED',
  name: '高级版',
  estimatedCostAmount: 1_800,
  estimatedMarginRatio: 0.37931,
  marginWarning: true,
})

const LEAK_MARKERS = ['最坏毛利', '预估成本', '毛利预警', '预警', '37.93%', '¥18']

function mountView() {
  return mount(PlansView, {
    global: {
      plugins: [createPinia()],
      stubs: { 'el-skeleton': { template: '<div />' } },
    },
  })
}

/**
 * 文字套餐与视频套餐分在两个页签里，默认停在「文字套餐」，
 * 所以要断言视频套餐先切过去。
 */
async function showVideoTab(wrapper: ReturnType<typeof mountView>) {
  await wrapper.get('[data-test="plans-tab-VIDEO"]').trigger('click')
}

describe('customer plan page: dimension labels stay, internal economics never leak', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    platformApi.listPlans.mockResolvedValue([textPlan, videoPlan, warnedPlan])
    platformApi.getFreePlanClaimStatus.mockResolvedValue({ claimed: false })
    gatewayApi.quotaSummary.mockResolvedValue({ activePackage: null, queuedPackages: [] })
  })

  it('keeps SPM for seconds plans and TPM for credit plans in cards and comparison', async () => {
    const wrapper = mountView()
    await flushPromises()

    const cardText = (code: string) => wrapper.get(`[data-plan-code="${code}"]`).text()
    expect(cardText('STARTER')).toContain('TPM 20,000')
    expect(wrapper.get('.compare-table').text()).toContain('TPM（积分） 20,000')

    await showVideoTab(wrapper)
    expect(cardText('VIDEO')).toContain('SPM 600')
    expect(cardText('VIDEO')).not.toContain('TPM 600')

    const comparison = wrapper.get('.compare-table')
    expect(comparison.text()).toContain('SPM（秒） 600')
    // 旧的固定 TPM 行会把每秒额度写成积分额度
    expect(comparison.text()).not.toContain('TPM 600')
  })

  it('never shows a video plan\u2019s second quota, and still shows the credit quota', async () => {
    const wrapper = mountView()
    await flushPromises()

    const cardText = (code: string) => wrapper.get(`[data-plan-code="${code}"]`).text()
    // 积分套餐照旧显示额度数字
    expect(cardText('STARTER')).toContain('1,000,000 积分额度')

    const rowCells = (label: string) => {
      const row = wrapper.findAll('.compare-table tbody tr')
        .find(candidate => candidate.get('td').text() === label)
      expect(row, `comparison row ${label} is missing`).toBeDefined()
      return row!.findAll('td').map(cell => cell.text())
    }
    expect(rowCells('额度与计费')[1]).toBe('1,000,000 积分')
    expect(rowCells('单次最大输出')[1]).toBe('4,096 积分')

    await showVideoTab(wrapper)

    // 视频套餐不出现秒数额度：卡片、权益列表、对比表都只说明计费方式
    const videoCard = cardText('VIDEO')
    expect(videoCard).toContain('按秒计费')
    expect(videoCard).not.toContain('秒额度')
    expect(videoCard).not.toMatch(/\d+\s*秒额度/)
    // 折算单价同样不能出现：价格除以单价就能反推出秒数
    expect(videoCard).not.toContain('折合每秒')
    // 60 是这张卡片的秒数额度，用户端一个数字都不该看到
    expect(videoCard).not.toContain('60 秒额度')

    const comparison = wrapper.get('.compare-table')
    expect(comparison.text()).toContain('额度与计费')
    expect(comparison.text()).toContain('按秒计费')
    expect(comparison.text()).not.toContain('积分')
    expect(rowCells('额度与计费')[1]).toBe('按秒计费')
    // 单次时长上限是请求约束（1–60 秒），不是套餐额度，保留
    expect(rowCells('单次最大输出')[1]).toBe('1–60 秒')
  })

  it('hides the plan API-key allowance on the customer page while the data keeps it', async () => {
    const wrapper = mountView()
    await flushPromises()

    // 字段本身保留（后端照旧下发 maxApiKeys），只是用户端不再渲染成权益
    expect(textPlan.maxApiKeys).toBe(3)
    expect(wrapper.text()).not.toContain('API 密钥')

    await showVideoTab(wrapper)
    expect(wrapper.text()).not.toContain('API 密钥')
  })

  it('never renders provider cost, margin or the margin warning badge', async () => {
    const wrapper = mountView()
    await flushPromises()

    // 两个页签的卡片都渲染出来了（否则"没泄漏"就成了空断言）
    expect(wrapper.findAll('[data-plan-code]')).toHaveLength(2)

    await showVideoTab(wrapper)
    expect(wrapper.findAll('[data-plan-code]')).toHaveLength(1)

    for (const marker of LEAK_MARKERS) {
      expect(wrapper.text()).not.toContain(marker)
    }
    expect(wrapper.find('[data-test="plan-margin"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="plan-margin-badge"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="plan-margin-ratio"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="plan-margin-cost"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="plan-margin-warning-text"]').exists()).toBe(false)
    expect(wrapper.find('.plan-margin-warning').exists()).toBe(false)
  })

  it('keeps the marketing content that is allowed to be public', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('¥99')
    expect(wrapper.text()).toContain('30 天')
    expect(wrapper.text()).toContain('1,000,000')
    expect(wrapper.find('[data-test="buy-plan"]').exists()).toBe(true)
  })
})
