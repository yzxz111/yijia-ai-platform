import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import PlansAdminView from './PlansAdminView.vue'
import * as admin from '../../api/admin'
import type { AdminPackagePlan, AdminPackagePlanVersion } from '../../api/admin'
import * as platform from '../../api/platform'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))

vi.mock('../../api/admin', () => ({
  archivePackagePlan: vi.fn(),
  createPackagePlan: vi.fn(),
  createPlanVersion: vi.fn(),
  listModels: vi.fn(),
  listPackagePlanCatalog: vi.fn(),
  publishPlanVersion: vi.fn(),
  restorePackagePlan: vi.fn(),
  updatePackagePlan: vi.fn(),
}))

vi.mock('../../api/platform', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/platform')>()),
  listPlans: vi.fn(),
}))

const api = vi.mocked(admin)
const platformApi = vi.mocked(platform)

function version(overrides: Partial<AdminPackagePlanVersion> & Pick<AdminPackagePlanVersion, 'versionId'>): AdminPackagePlanVersion {
  return {
    version: 1,
    status: 'PUBLISHED',
    priceAmount: 2_900,
    currency: 'CNY',
    totalTokenQuota: 2_000_000,
    durationDays: 30,
    maxApiKeys: 3,
    requestsPerMinute: 20,
    tokensPerMinute: 100_000,
    maxConcurrency: 3,
    dailyRequestLimit: 2_000,
    maxOutputTokens: 4_096,
    maxFileSizeBytes: 20 * 1024 * 1024,
    oncePerUser: false,
    estimatedCostAmount: null,
    estimatedMarginRatio: null,
    marginWarning: false,
    stockTotal: null,
    stockSold: 0,
    stockReserved: 0,
    lowStockThreshold: 0,
    maxPurchasesPerUser: null,
    remainingStock: null,
    soldOut: false,
    lowStock: false,
    modelCount: 2,
    publishedAt: '2026-09-01T00:00:00Z',
    retiredAt: null,
    createdAt: '2026-09-01T00:00:00Z',
    ...overrides,
  }
}

function plan(code: string, name: string, versionValue: AdminPackagePlanVersion): AdminPackagePlan {
  return {
    code,
    name,
    description: null,
    kind: 'STANDARD',
    status: 'ACTIVE',
    displayOrder: 0,
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-01T00:00:00Z',
    versions: [versionValue],
  }
}

const warnedPlan = plan('STARTER', '入门版', version({
  versionId: 'version-starter-1',
  estimatedCostAmount: 1_800,
  estimatedMarginRatio: 0.37931,
  marginWarning: true,
}))

const healthyPlan = plan('ADVANCED', '高级版', version({
  versionId: 'version-advanced-1',
  estimatedCostAmount: 61_000,
  estimatedMarginRatio: 0.62,
  marginWarning: false,
}))

// 成本口径还没算出来的版本：null 必须显示破折号，不能补 0
const unknownCostPlan = plan('VIDEO', '视频秒数版', version({
  versionId: 'version-video-1',
  estimatedCostAmount: null,
  estimatedMarginRatio: null,
  marginWarning: false,
}))

function elementStubs() {
  return {
    'el-button': { template: '<button><slot /></button>' },
    'el-checkbox': { props: ['modelValue'], template: '<label><slot /></label>' },
    'el-dialog': { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
    'el-form': { template: '<form><slot /></form>' },
    'el-form-item': { props: ['label'], template: '<div class="el-form-item"><slot /></div>' },
    'el-input': { props: ['modelValue'], template: '<input :value="modelValue" />' },
    'el-input-number': { props: ['modelValue'], template: '<input type="number" :value="modelValue ?? \'\'" />' },
    'el-option': { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
    'el-pagination': { template: '<div />' },
    'el-select': { props: ['modelValue'], template: '<select><slot /></select>' },
    'el-skeleton': { template: '<div />' },
    'el-tag': { props: ['title'], template: '<span :title="title"><slot /></span>' },
  }
}

function mountView() {
  return mount(PlansAdminView, { global: { stubs: elementStubs() } })
}

function planCard(wrapper: VueWrapper, name: string) {
  const card = wrapper.findAll('.plan-item').find(item => item.text().includes(name))
  if (!card) throw new Error(`plan card not found: ${name}`)
  return card
}

describe('admin plan versions: cost, worst-case margin and the 预警 badge', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listPackagePlanCatalog.mockResolvedValue({
      items: [warnedPlan, healthyPlan, unknownCostPlan], total: 3, page: 0, size: 10,
    })
    api.listModels.mockResolvedValue({ items: [], total: 0, page: 0, size: 100 })
    platformApi.listPlans.mockResolvedValue([])
  })

  it('shows the worst-case margin, the estimated cost and a 预警 badge with the 30–40% explanation', async () => {
    const wrapper = mountView()
    await flushPromises()

    const warned = planCard(wrapper, '入门版')
    expect(warned.get('[data-test="plan-margin-ratio"]').text()).toBe('37.9%')
    expect(warned.get('[data-test="plan-cost-amount"]').text()).toBe('¥18')
    expect(warned.get('[data-test="plan-margin-badge"]').text()).toBe('预警')
    expect(warned.get('[data-test="plan-margin-badge"]').attributes('title')).toContain('30%–40%')
    expect(warned.get('[data-test="plan-margin-warning"]').text()).toBe('毛利预警：最坏情况毛利落在 30%–40% 目标区间内')

    expect(wrapper.findAll('[data-test="plan-margin-badge"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-test="plan-margin-warning"]')).toHaveLength(1)
  })

  it('shows cost and margin without the warning wear for a healthy plan', async () => {
    const wrapper = mountView()
    await flushPromises()

    const healthy = planCard(wrapper, '高级版')
    expect(healthy.get('[data-test="plan-margin-ratio"]').text()).toBe('62.0%')
    expect(healthy.get('[data-test="plan-cost-amount"]').text()).toBe('¥610')
    expect(healthy.find('[data-test="plan-margin-badge"]').exists()).toBe(false)
    expect(healthy.find('[data-test="plan-margin-warning"]').exists()).toBe(false)
  })

  it('renders an unknown cost basis as a dash instead of inventing a zero', async () => {
    const wrapper = mountView()
    await flushPromises()

    const unknown = planCard(wrapper, '视频秒数版')
    expect(unknown.get('[data-test="plan-margin-ratio"]').text()).toBe('—')
    expect(unknown.get('[data-test="plan-cost-amount"]').text()).toBe('—')
    expect(unknown.find('[data-test="plan-margin-badge"]').exists()).toBe(false)
    expect(unknown.get('[data-test="plan-margin"]').text()).not.toContain('0')
  })

  it('renders the margin panel for every plan version row', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.findAll('[data-test="plan-margin"]')).toHaveLength(3)
    expect(wrapper.findAll('[data-test="plan-cost"]')).toHaveLength(3)
    // 这句只在运营端出现：用户端套餐列表不得包含成本/毛利（见 PlansViewLeakGuard.spec.ts）
    expect(wrapper.text()).toContain('毛利预警：最坏情况毛利落在 30%–40% 目标区间内')
  })
})
