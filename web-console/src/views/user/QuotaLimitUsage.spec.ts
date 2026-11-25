import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import QuotaView from './QuotaView.vue'
import * as gateway from '../../api/gateway'
import * as platform from '../../api/platform'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

vi.mock('../../api/gateway', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/gateway')>()),
  quotaSummary: vi.fn(),
}))

vi.mock('../../api/platform', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/platform')>()),
  getPackageLimitUsage: vi.fn(),
}))

const gatewayApi = vi.mocked(gateway)
const platformApi = vi.mocked(platform)

const activePackage = {
  packageInstanceId: '10000000-0000-4000-8000-000000000001',
  packageCode: 'VIDEO',
  packageName: '视频秒数版',
  totalTokens: 60_000,
  usedTokens: 1_000,
  reservedTokens: 0,
  remainingTokens: 59_000,
}

function mountView() {
  return mount(QuotaView, {
    global: {
      plugins: [createPinia()],
      stubs: {
        'el-skeleton': { template: '<div data-test="skeleton" />' },
      },
    },
  })
}

describe('quota page rate-limit usage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    gatewayApi.quotaSummary.mockResolvedValue({ activePackage, queuedPackages: [] })
  })

  it('requests limit usage for the active package and renders the counters', async () => {
    platformApi.getPackageLimitUsage.mockResolvedValue({
      packageInstanceId: activePackage.packageInstanceId,
      usageDimension: 'VIDEO_SECOND',
      perMinute: { limit: 600, used: 120 },
      daily: { limit: null, used: 7 },
      blockedLast24h: 2,
      blockedLast24HoursByReason: { daily_request_limit_exceeded: 1, tokens_per_minute_exceeded: 1 },
    })

    const wrapper = mountView()
    await flushPromises()

    expect(platformApi.getPackageLimitUsage).toHaveBeenCalledWith(activePackage.packageInstanceId)
    const panel = wrapper.get('[data-test="limit-usage-panel"]')
    expect(panel.text()).toContain('视频秒数版')
    expect(panel.text()).toContain('SPM（秒/分钟）')
    expect(wrapper.get('[data-test="limit-usage-per-minute"]').text()).toContain('120 / 600')
    // limit === null 表示不限，不是 0
    expect(wrapper.get('[data-test="limit-usage-daily"]').text()).toContain('7 / 不限')
    expect(wrapper.get('[data-test="limit-usage-blocked"]').text()).toContain('2 次请求')
  })

  it('renders "暂时取不到用量" when the endpoint answers 503 instead of showing zeros', async () => {
    platformApi.getPackageLimitUsage.mockRejectedValue({ response: { status: 503 } })

    const wrapper = mountView()
    await flushPromises()

    const unavailable = wrapper.get('[data-test="limit-usage-unavailable"]')
    expect(unavailable.text()).toContain('暂时取不到用量')
    expect(wrapper.find('[data-test="limit-usage-per-minute"]').exists()).toBe(false)
    // 额度面板本身仍然可用：限流计数取不到不能连累已加载的额度
    expect(wrapper.text()).toContain('积分额度管理')
  })

  it('does not query the endpoint when the active package has no instance id', async () => {
    gatewayApi.quotaSummary.mockResolvedValue({
      activePackage: { packageCode: 'STARTER', packageName: '入门版' },
      queuedPackages: [],
    })

    const wrapper = mountView()
    await flushPromises()

    expect(platformApi.getPackageLimitUsage).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="limit-usage-unavailable"]').text()).toContain('暂时取不到用量')
  })

  it('refreshes the counters when the panel asks for a refresh', async () => {
    platformApi.getPackageLimitUsage.mockResolvedValue({
      packageInstanceId: activePackage.packageInstanceId,
      usageDimension: 'VIDEO_SECOND',
      perMinute: { limit: 600, used: 120 },
      daily: { limit: 100, used: 7 },
      blockedLast24h: 0,
      blockedLast24HoursByReason: {},
    })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-test="limit-usage-refresh"]').trigger('click')
    await flushPromises()

    expect(platformApi.getPackageLimitUsage).toHaveBeenCalledTimes(2)
  })
})
