import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import PackageLimitUsagePanel from './PackageLimitUsagePanel.vue'
import type { PackageLimitUsage } from '../../api/platform'

function mountPanel(usage: PackageLimitUsage | null, extra: Record<string, unknown> = {}) {
  return mount(PackageLimitUsagePanel, {
    props: { usage, packageLabel: '入门版 · STARTER', ...extra },
    global: { stubs: { 'el-skeleton': { template: '<div data-test="skeleton" />' } } },
  })
}

const tokenUsage: PackageLimitUsage = {
  packageInstanceId: '10000000-0000-4000-8000-000000000001',
  usageDimension: 'TOKEN',
  perMinute: { limit: 20_000, used: 1_234 },
  daily: { limit: 1_000, used: 42 },
  blockedLast24h: 5,
  blockedLast24HoursByReason: {
    daily_request_limit_exceeded: 3,
    tokens_per_minute_exceeded: 2,
  },
}

describe('package limit usage panel', () => {
  it('labels the per-minute figure TPM for a credit package and shows both windows', () => {
    const wrapper = mountPanel(tokenUsage)

    expect(wrapper.get('[data-test="limit-usage-panel"]').text()).toContain('TPM（积分/分钟）')
    expect(wrapper.get('[data-test="limit-usage-per-minute"]').text()).toContain('1,234 / 20,000')
    expect(wrapper.get('[data-test="limit-usage-daily"]').text()).toContain('42 / 1,000')
    expect(wrapper.get('[data-test="limit-usage-blocked"]').text()).toContain('5 次请求')
    expect(wrapper.get('[data-test="limit-usage-reason-daily"]').text()).toContain('超出每日请求上限：3 次')
    expect(wrapper.get('[data-test="limit-usage-reason-rate"]').text()).toContain('超出每分钟额度上限：2 次')
    expect(wrapper.find('[data-test="limit-usage-unavailable"]').exists()).toBe(false)
  })

  it('labels the per-minute figure SPM for a video package', () => {
    const wrapper = mountPanel({
      ...tokenUsage,
      usageDimension: 'VIDEO_SECOND',
      perMinute: { limit: 600, used: 12 },
    })

    expect(wrapper.get('[data-test="limit-usage-panel"]').text()).toContain('SPM（秒/分钟）')
    expect(wrapper.get('[data-test="limit-usage-panel"]').text()).not.toContain('TPM')
    expect(wrapper.get('[data-test="limit-usage-per-minute"]').text()).toContain('12 / 600')
  })

  it('renders a null limit as unlimited instead of as zero', () => {
    const wrapper = mountPanel({
      ...tokenUsage,
      perMinute: { limit: null, used: 500 },
      daily: { limit: null, used: 9 },
    })

    expect(wrapper.get('[data-test="limit-usage-per-minute"]').text()).toContain('500 / 不限')
    expect(wrapper.get('[data-test="limit-usage-daily"]').text()).toContain('9 / 不限')
  })

  it('shows a dash and hides the breakdown when the API omits fields', () => {
    const wrapper = mountPanel({
      packageInstanceId: '10000000-0000-4000-8000-000000000002',
      usageDimension: 'TOKEN',
      perMinute: null,
      daily: undefined,
      blockedLast24h: undefined,
      blockedLast24HoursByReason: null,
    })

    expect(wrapper.get('[data-test="limit-usage-per-minute"]').text()).toContain('— / —')
    expect(wrapper.get('[data-test="limit-usage-daily"]').text()).toContain('— / —')
    expect(wrapper.get('[data-test="limit-usage-blocked"]').text()).toContain('—')
    expect(wrapper.find('[data-test="limit-usage-reason-daily"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="limit-usage-blocked"]').text()).toContain('没有返回拒绝原因明细')
  })

  it('renders a real zero as zero, not as a dash', () => {
    const wrapper = mountPanel({
      ...tokenUsage,
      blockedLast24h: 0,
      blockedLast24HoursByReason: { daily_request_limit_exceeded: 0, tokens_per_minute_exceeded: 0 },
    })

    expect(wrapper.get('[data-test="limit-usage-blocked"]').text()).toContain('0 次请求')
    expect(wrapper.get('[data-test="limit-usage-reason-daily"]').text()).toContain('0 次')
  })

  it('renders "暂时取不到用量" instead of zeros while the gateway is unreachable', () => {
    const wrapper = mountPanel(null, { unavailable: true })

    expect(wrapper.get('[data-test="limit-usage-unavailable"]').text()).toContain('暂时取不到用量')
    expect(wrapper.find('[data-test="limit-usage-per-minute"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="limit-usage-panel"]').text()).not.toContain('0')
  })

  it('shows a skeleton while loading and emits refresh on demand', async () => {
    const wrapper = mountPanel(null, { loading: true })
    expect(wrapper.find('[data-test="limit-usage-skeleton"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="limit-usage-unavailable"]').exists()).toBe(false)

    await wrapper.get('[data-test="limit-usage-refresh"]').trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })

  it('falls back to the unavailable state when no usage object arrived', () => {
    const wrapper = mountPanel(null)
    expect(wrapper.find('[data-test="limit-usage-unavailable"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="limit-usage-per-minute"]').exists()).toBe(false)
  })
})
