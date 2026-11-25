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

/**
 * 方案 C 的第二份额度：网关额度接口多返回一份 `audioSeconds`（单位恒为秒）。
 *
 * 它只属于"视频秒 + 音频秒"的混合套餐；为 null/缺失表示这个套餐不卖语音，
 * 那种情况必须整块不渲染——显示「0 秒」等于承诺"包含 0 秒语音"，
 * 而那是个假承诺（数据库的 CHECK 也正是为此拒绝 0 额度）。
 */
const mixedPackage = {
  packageInstanceId: '20000000-0000-4000-8000-000000000001',
  packageCode: 'VIDEO_WITH_VOICE',
  packageName: '视频+语音套餐',
  totalTokens: 100,
  reservedTokens: 0,
  usedTokens: 10,
  remainingTokens: 90,
  usageDimension: 'VIDEO_SECOND' as const,
  audioSeconds: {
    totalSeconds: 1800,
    reservedSeconds: 60,
    usedSeconds: 240,
    remainingSeconds: 1500,
  },
}

const videoOnlyPackage = {
  packageInstanceId: '20000000-0000-4000-8000-000000000002',
  packageCode: 'VIDEO_ONLY',
  packageName: '纯视频套餐',
  totalTokens: 600,
  reservedTokens: 0,
  usedTokens: 0,
  remainingTokens: 600,
  usageDimension: 'VIDEO_SECOND' as const,
  audioSeconds: null,
}

const textPackage = {
  packageInstanceId: '20000000-0000-4000-8000-000000000003',
  packageCode: 'STARTER',
  packageName: '入门版',
  totalTokens: 1_000_000,
  reservedTokens: 0,
  usedTokens: 1_000,
  remainingTokens: 999_000,
  usageDimension: 'TOKEN' as const,
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

describe('quota page audio-seconds bucket', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    platformApi.getPackageLimitUsage.mockResolvedValue({
      packageInstanceId: mixedPackage.packageInstanceId,
      usageDimension: 'VIDEO_SECOND',
      perMinute: { limit: 600, used: 0 },
      daily: { limit: null, used: 0 },
      blockedLast24h: 0,
      blockedLast24HoursByReason: {},
    })
  })

  it('renders the audio bucket as its own block whose unit is always 秒', async () => {
    gatewayApi.quotaSummary.mockResolvedValue({
      activePackage: mixedPackage,
      activePackages: [mixedPackage],
      queuedPackages: [],
    })

    const wrapper = mountView()
    await flushPromises()

    const block = wrapper.get('[data-test="audio-seconds-block"]')
    expect(block.text()).toContain('语音额度（按秒）')
    expect(wrapper.get('[data-test="audio-seconds-total"]').text()).toBe('1,800秒')
    expect(wrapper.get('[data-test="audio-seconds-reserved"]').text()).toBe('60秒')
    expect(wrapper.get('[data-test="audio-seconds-used"]').text()).toBe('240秒')
    expect(wrapper.get('[data-test="audio-seconds-remaining"]').text()).toBe('1,500秒')
    // 两份额度并列时页头不能只声明视频
    expect(wrapper.get('#quota-title').text()).toBe('视频与语音额度管理')
    // 两个桶绝不相加：1,500 + 90 这个数不许出现在任何地方
    expect(wrapper.text()).not.toContain('1,590')
  })

  it('renders nothing at all for a package that sells no audio', async () => {
    gatewayApi.quotaSummary.mockResolvedValue({
      activePackage: videoOnlyPackage,
      activePackages: [videoOnlyPackage],
      queuedPackages: [],
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="audio-seconds-block"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('语音额度')
    // 「0 秒」是一次假承诺，因此不显示 0，而是什么都不显示
    expect(wrapper.text()).not.toContain('0秒')
    expect(wrapper.get('#quota-title').text()).toBe('视频额度管理')
  })

  it('renders nothing when an older gateway omits the field entirely', async () => {
    gatewayApi.quotaSummary.mockResolvedValue({
      activePackage: videoOnlyPackage,
      activePackages: [videoOnlyPackage],
      queuedPackages: [],
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="audio-seconds-block"]').exists()).toBe(false)
  })

  it('treats a zero-second bucket as "no audio" instead of promising 0 秒', async () => {
    // 0 秒的额度在两份 schema 里都被 CHECK 拒绝，因此它不该出现；万一出现，
    // 渲染成「包含 0 秒语音」就是假承诺，这里按"没卖语音"处理。
    const zeroBucket = {
      ...videoOnlyPackage,
      audioSeconds: { totalSeconds: 0, reservedSeconds: 0, usedSeconds: 0, remainingSeconds: 0 },
    }
    gatewayApi.quotaSummary.mockResolvedValue({
      activePackage: zeroBucket,
      activePackages: [zeroBucket],
      queuedPackages: [],
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="audio-seconds-block"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('0秒')
  })

  it('shows the audio bucket even when the compatibility field points at another package', async () => {
    // 同时持有文本套餐与混合套餐时，网关的兼容字段 activePackage 指文本套餐；
    // 只读它会让混合套餐的音频秒余额彻底看不见。
    gatewayApi.quotaSummary.mockResolvedValue({
      activePackage: textPackage,
      activePackages: [textPackage, mixedPackage],
      queuedPackages: [],
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('[data-test="audio-seconds-block"]').attributes('data-test-package'))
      .toBe(mixedPackage.packageInstanceId)
    expect(wrapper.get('[data-test="audio-seconds-total"]').text()).toBe('1,800秒')
    // 一份是积分套餐，音频秒块出现不代表本页可以按积分口径渲染它
    expect(wrapper.get('#quota-title').text()).toBe('视频与语音额度管理')
  })
})
