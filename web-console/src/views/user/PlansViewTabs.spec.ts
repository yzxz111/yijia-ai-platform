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

/** 积分套餐：额度以「积分」计 */
const textPlan = plan({ code: 'STARTER', name: '入门版' })
/** 视频套餐：额度以「秒」计，同一个字段存的是秒数 */
const videoPlan = plan({
  code: 'VIDEO_STARTER',
  name: '视频入门版',
  usageDimension: 'VIDEO_SECOND',
  totalTokenQuota: 60,
  priceAmount: 12_900,
  tokensPerMinute: 60,
})

function mountView() {
  return mount(PlansView, {
    global: {
      plugins: [createPinia()],
      stubs: { 'el-skeleton': { template: '<div />' } },
    },
  })
}

function shownPlanCodes(wrapper: ReturnType<typeof mountView>) {
  return wrapper.findAll('[data-plan-code]').map(card => card.attributes('data-plan-code'))
}

describe('customer plan page: credit and video plans sit on separate tabs', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    platformApi.listPlans.mockResolvedValue([textPlan, videoPlan])
    platformApi.getFreePlanClaimStatus.mockResolvedValue({ claimed: false })
    gatewayApi.quotaSummary.mockResolvedValue({ activePackage: null, queuedPackages: [] })
  })

  it('opens on the credit tab and shows only credit plans there', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('[data-test="plans-tab-TEXT"]').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('[data-test="plans-tab-VIDEO"]').attributes('aria-selected')).toBe('false')
    expect(shownPlanCodes(wrapper)).toEqual(['STARTER'])

    // 对比表同理：只有积分套餐一列，额度单位不会和秒混在一起
    expect(wrapper.get('.compare-table').text()).toContain('1,000,000 积分')
    expect(wrapper.get('.compare-table').text()).not.toContain('秒')
  })

  it('shows only video plans, cards and comparison, once the video tab is chosen', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-test="plans-tab-VIDEO"]').trigger('click')

    expect(wrapper.get('[data-test="plans-tab-VIDEO"]').attributes('aria-selected')).toBe('true')
    expect(shownPlanCodes(wrapper)).toEqual(['VIDEO_STARTER'])
    // 视频套餐不显示秒数额度，只说明按秒计费
    expect(wrapper.get('[data-plan-code="VIDEO_STARTER"]').text()).toContain('按秒计费')

    const comparison = wrapper.get('.compare-table')
    expect(comparison.text()).toContain('按秒计费')
    expect(comparison.text()).toContain('SPM（秒） 60')
    // 视频页签整张表都不该出现积分口径，也不该出现秒数额度
    expect(comparison.text()).not.toContain('积分')
    expect(comparison.text()).not.toContain('秒额度')
    // 常见问题两个页签共用，切页签不会把它弄丢
    expect(wrapper.text()).toContain('常见问题')
  })

  it('lists the video line entry-first, with the annual package beside the monthly one', async () => {
    platformApi.listPlans.mockResolvedValue([
      textPlan,
      plan({ code: 'VIDEO_PRO', name: 'Video Pro', usageDimension: 'VIDEO_SECOND', totalTokenQuota: 600, priceAmount: 139_900 }),
      plan({ code: 'VIDEO_CREATOR', name: 'Video Creator', usageDimension: 'VIDEO_SECOND', totalTokenQuota: 190, priceAmount: 39_900 }),
      plan({ code: 'VIDEO_LITE_ANNUAL', name: 'Video Lite Annual', usageDimension: 'VIDEO_SECOND', totalTokenQuota: 1_100, durationDays: 365, priceAmount: 59_900 }),
      videoPlan,
      plan({ code: 'VIDEO_LITE', name: 'Video Lite', usageDimension: 'VIDEO_SECOND', totalTokenQuota: 100, priceAmount: 5_900 }),
    ])
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-test="plans-tab-VIDEO"]').trigger('click')

    // 入门档排最前, 年付紧跟月付(同一个产品两种买法), 然后才是 40/50/60 三个更高档位.
    // 顺序来自 PLAN_TIER_ORDER, 与 platform.package_plan.display_order(V67)一致;
    // 靠"未知档位按价格升序"兜底会把 ¥599 的年付插到 ¥399 和 ¥1399 中间.
    expect(shownPlanCodes(wrapper)).toEqual([
      'VIDEO_LITE', 'VIDEO_LITE_ANNUAL', 'VIDEO_STARTER', 'VIDEO_CREATOR', 'VIDEO_PRO',
    ])
  })

  it('opens on the video tab when the package in effect is a video plan', async () => {
    gatewayApi.quotaSummary.mockResolvedValue({
      activePackage: { packageCode: 'VIDEO_STARTER' },
      queuedPackages: [],
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('[data-test="plans-tab-VIDEO"]').attributes('aria-selected')).toBe('true')
    expect(shownPlanCodes(wrapper)).toEqual(['VIDEO_STARTER'])
  })

  it('keeps the tab strip out of the way when only one kind of plan is published', async () => {
    platformApi.listPlans.mockResolvedValue([textPlan])
    const wrapper = mountView()
    await flushPromises()

    // 一个点进去空空如也的页签不如没有：只有积分套餐时不摆页签，卡片照常显示
    expect(wrapper.find('.plans-tabs').exists()).toBe(false)
    expect(shownPlanCodes(wrapper)).toEqual(['STARTER'])
  })

  it('says so instead of rendering an empty grid when nothing is published', async () => {
    platformApi.listPlans.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()

    expect(shownPlanCodes(wrapper)).toEqual([])
    expect(wrapper.get('.plans-empty').text()).toContain('暂无已发布的套餐版本')
    expect(wrapper.find('.compare-table').exists()).toBe(false)
  })
})
