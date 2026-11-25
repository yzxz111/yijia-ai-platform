import { readFileSync } from 'node:fs'
import path from 'node:path'

import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import DashboardView from '../../src/views/user/DashboardView.vue'
import dashboardSource from '../../src/views/user/DashboardView.vue?raw'
import AdminOverviewView from '../../src/views/admin/AdminOverviewView.vue'
import adminOverviewSource from '../../src/views/admin/AdminOverviewView.vue?raw'
import type { GatewayAdminAnalytics, OperatingAnalytics } from '../../src/api/admin'
import type { UsageAnalytics } from '../../src/api/gateway'
import { useAuthStore, type AuthenticatedUser } from '../../src/stores/auth'
import { useUsageStore } from '../../src/stores/usage'

const adminCssSource = readFileSync(path.resolve(process.cwd(), 'src/styles/admin.css'), 'utf8')

const api = vi.hoisted(() => ({
  getUsageAnalytics: vi.fn(),
  getAdminOverview: vi.fn(),
  getGatewayAdminAnalytics: vi.fn(),
  getGatewayHealth: vi.fn(),
  getOperatingAnalytics: vi.fn(),
  listApiKeys: vi.fn(),
  listUsage: vi.fn(),
  quotaSummary: vi.fn(),
  usageMetrics: vi.fn(),
}))

vi.mock('../../src/api/gateway', () => ({
  getUsageAnalytics: api.getUsageAnalytics,
  listApiKeys: api.listApiKeys,
  listUsage: api.listUsage,
  quotaSummary: api.quotaSummary,
  usageMetrics: api.usageMetrics,
}))

vi.mock('../../src/api/admin', () => ({
  getAdminOverview: api.getAdminOverview,
  getGatewayAdminAnalytics: api.getGatewayAdminAnalytics,
  getGatewayHealth: api.getGatewayHealth,
  getOperatingAnalytics: api.getOperatingAnalytics,
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, reject, resolve }
}

function user(id: string, tenantId: string): AuthenticatedUser {
  return { id, tenantId, email: `${id}@example.com`, role: 'USER' }
}

function analyticsFixture(days: 7 | 30 = 30): UsageAnalytics {
  return {
    range: {
      days,
      timezone: 'Asia/Shanghai',
      from: days === 30 ? '2026-07-04' : '2026-07-27',
      to: '2026-08-02',
    },
    summary: {
      todayRequests: 7,
      todaySuccessfulRequests: 6,
      todayFailedRequests: 1,
      todayTokens: 321,
      periodRequests: 42,
      periodTokens: 1_234,
      platformAddedP95Ms: 88,
    },
    daily: [
      { date: '2026-08-01', requestCount: 12, inputTokens: 120, outputTokens: 30, totalTokens: 150 },
      { date: '2026-08-02', requestCount: 7, inputTokens: 250, outputTokens: 71, totalTokens: 321 },
    ],
    models: [
      { modelCode: 'qwen-text', providerCode: 'bailian', upstreamModel: 'qwen-plus', requestCount: 30, totalTokens: 900 },
      { modelCode: 'text-basic', providerCode: 'bailian', upstreamModel: null, requestCount: 12, totalTokens: 334 },
    ],
  }
}

function accountAnalytics(modelCode: string, periodTokens: number): UsageAnalytics {
  return {
    ...analyticsFixture(),
    summary: {
      ...analyticsFixture().summary,
      todayRequests: periodTokens,
      periodTokens,
      platformAddedP95Ms: periodTokens,
    },
    models: [{
      modelCode,
      providerCode: modelCode === 'ACCOUNT_B' ? 'provider-b' : 'provider-a',
      upstreamModel: null,
      requestCount: 1,
      totalTokens: periodTokens,
    }],
  }
}

function accountQuota(packageCode: string) {
  return { activePackage: { packageCode }, queuedPackages: [] }
}

function accountUsage(requestId: string) {
  return {
    items: [{
      id: requestId,
      requestId,
      modelCode: 'text-basic',
      inputTokens: 1,
      outputTokens: 1,
      totalTokens: 2,
      durationMs: 3,
      httpStatus: 200,
      outcome: 'SUCCEEDED',
      createdAt: '2026-08-02T04:00:00Z',
      stages: [],
    }],
    total: 1,
    page: 0,
    size: 20,
  }
}

function mountDashboard() {
  return mount(DashboardView, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        AppChart: {
          name: 'AppChart',
          props: ['ariaLabel', 'height', 'option'],
          template: '<div data-test="chart-stub" role="img" :aria-label="ariaLabel" />',
        },
        RouterLink: { template: '<a><slot /></a>' },
      },
    },
  })
}

function arrangeSuccessfulSources(analytics = analyticsFixture()) {
  api.getUsageAnalytics.mockResolvedValue(analytics)
  api.quotaSummary.mockResolvedValue({
    activePackage: {
      packageCode: 'PRO',
      packageName: '专业版',
      totalTokens: 10_000,
      reservedTokens: 500,
      usedTokens: 8_000,
      remainingTokens: 1_500,
      dailyRequestLimit: 50,
      expiresAt: '2026-08-31T00:00:00Z',
      modelQuotas: [
        { modelCode: 'qwen-text', totalTokens: 7_000, reservedTokens: 300, usedTokens: 5_600, remainingTokens: 1_100 },
      ],
    },
    queuedPackages: [],
  })
  api.listApiKeys.mockResolvedValue([
    { id: 'key-1', status: 'ACTIVE' },
    { id: 'key-2', status: 'REVOKED' },
  ])
  api.listUsage.mockResolvedValue({
    items: [{
      id: 'usage-1',
      requestId: 'req_full_identifier_0123456789',
      modelCode: 'qwen-text',
      providerCode: 'bailian',
      upstreamModel: 'qwen-plus',
      inputTokens: 120,
      outputTokens: 30,
      totalTokens: 150,
      durationMs: 310,
      httpStatus: 200,
      outcome: 'SUCCEEDED',
      createdAt: '2026-08-02T04:00:00Z',
    }],
    total: 1,
    page: 0,
    size: 20,
  })
  api.usageMetrics.mockResolvedValue({ sampleCount: 1, platformAddedP95Ms: 91 })
}

function operatingFixture(netAmount = 12_345): OperatingAnalytics {
  return {
    timezone: 'Asia/Shanghai',
    months: 6,
    summary: {
      registeredUsers: 120,
      activeUsers: 73,
      currentMonthCollectedAmount: 15_000,
      currentMonthRefundedAmount: 2_655,
      currentMonthNetAmount: netAmount,
      paidOrders: 8,
    },
    monthly: ['2026-03', '2026-04', '2026-05', '2026-06', '2026-07', '2026-08'].map((month, index) => ({
      month,
      newUsers: index + 1,
      collectedAmount: index * 1_000,
      refundedAmount: index * 100,
      netAmount: index * 900,
    })),
  }
}

function gatewayAdminFixture(days: 7 | 30 = 30): GatewayAdminAnalytics {
  return {
    range: {
      days,
      timezone: 'Asia/Shanghai',
      from: days === 30 ? '2026-07-04' : '2026-07-27',
      to: '2026-08-02',
    },
    summary: {
      todayRequests: 117,
      todaySuccessfulRequests: 113,
      todayFailedRequests: 4,
      todayTokens: 8_888,
      periodRequests: 900,
      periodTokens: 88_000,
      platformAddedP95Ms: 96,
    },
    daily: [
      { date: '2026-08-01', requestCount: 103, inputTokens: 4_000, outputTokens: 2_000, totalTokens: 6_000 },
      { date: '2026-08-02', requestCount: 117, inputTokens: 6_000, outputTokens: 2_888, totalTokens: 8_888 },
    ],
    models: [],
    packages: [
      { packageCode: 'STARTER', packageName: '入门版', activeCount: 3 },
      { packageCode: 'PRO', packageName: '专业版', activeCount: 2 },
    ],
  }
}

function arrangeSuccessfulAdminSources() {
  api.getAdminOverview.mockResolvedValue({
    users: 120, activeUsers: 73, grants: 45, outboxAttention: 2,
    orders: 10, paidOrders: 8, paidAmount: 15_000,
  })
  api.getGatewayHealth.mockResolvedValue({
    status: 'UP', database: 'UP', expiredReservations: 1, cleanupCandidates: 2, circuits: [],
  })
  api.getOperatingAnalytics.mockResolvedValue(operatingFixture())
  api.getGatewayAdminAnalytics.mockResolvedValue(gatewayAdminFixture())
}

function mountAdminDashboard() {
  return mount(AdminOverviewView, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        AppChart: {
          name: 'AppChart',
          props: ['ariaLabel', 'height', 'option'],
          template: '<div role="img" :aria-label="ariaLabel" />',
        },
      },
    },
  })
}

describe('real-data user dashboard', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } })
  })

  it('renders persisted analytics, quota, and model identity values without API key data', async () => {
    arrangeSuccessfulSources()
    const wrapper = mountDashboard()
    await flushPromises()

    expect(api.getUsageAnalytics).toHaveBeenCalledWith(30)
    expect(wrapper.get('[data-test="dashboard-period-tokens"]').text()).toContain('1,234')
    expect(wrapper.get('[data-test="dashboard-today-requests"]').text()).toContain('7')
    expect(wrapper.find('[data-test="dashboard-active-keys"]').exists()).toBe(false)
    expect(api.listApiKeys).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="dashboard-p95"]').text()).toContain('88 ms')
    expect(wrapper.text()).toContain('qwen-plus · bailian')
    expect(wrapper.text()).toContain('平台模型：qwen-text')
    expect(wrapper.text()).toContain('text-basic · bailian')
    expect(wrapper.get('[data-test="dashboard-quota"]').classes()).toContain('warning')
    expect(wrapper.get('[data-test="dashboard-daily-limit"]').text()).toContain('50')
    expect(wrapper.get('[data-test=dashboard-daily-requests]').text()).toBe('7')
    expect(wrapper.findAll('[data-test="dashboard-chart"]')).toHaveLength(2)
  })

  it('reloads only analytics when the selected range changes', async () => {
    arrangeSuccessfulSources()
    api.getUsageAnalytics
      .mockResolvedValueOnce(analyticsFixture(30))
      .mockResolvedValueOnce({
        ...analyticsFixture(7),
        summary: { ...analyticsFixture(7).summary, periodTokens: 77 },
      })
    const wrapper = mountDashboard()
    await flushPromises()

    await wrapper.get('[data-test="dashboard-range-7"]').trigger('click')
    await flushPromises()

    expect(api.getUsageAnalytics).toHaveBeenNthCalledWith(2, 7)
    expect(api.quotaSummary).toHaveBeenCalledTimes(1)
    expect(api.listUsage).toHaveBeenCalledTimes(1)
    expect(api.listApiKeys).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="dashboard-period-tokens"]').text()).toContain('77')
    expect(wrapper.get('[data-test="dashboard-range-7"]').attributes('aria-pressed')).toBe('true')
  })

  it('shows honest zeros, empty regions, and fallback model labels', async () => {
    arrangeSuccessfulSources({
      ...analyticsFixture(),
      summary: {
        todayRequests: 0,
        todaySuccessfulRequests: 0,
        todayFailedRequests: 0,
        todayTokens: 0,
        periodRequests: 0,
        periodTokens: 0,
        platformAddedP95Ms: 0,
      },
      daily: [],
      models: [{ modelCode: 'text-basic', providerCode: 'bailian', upstreamModel: null, requestCount: 0, totalTokens: 0 }],
    })
    api.quotaSummary.mockResolvedValue({ activePackage: null, queuedPackages: [] })
    api.listUsage.mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
    const wrapper = mountDashboard()
    await flushPromises()

    expect(wrapper.get('[data-test="dashboard-period-tokens"]').text()).toContain('0')
    expect(wrapper.get('[data-test="dashboard-today-requests"]').text()).toContain('0')
    expect(wrapper.get('[data-test="dashboard-p95"]').text()).toContain('0 ms')
    expect(wrapper.text()).toContain('text-basic · bailian')
    expect(wrapper.text()).toContain('当前周期暂无真实调用')
    expect(wrapper.get('.chart-empty').classes()).toContain('chart-empty--centered')
    expect(wrapper.text()).toContain('当前没有生效套餐')
    expect(wrapper.text()).toContain('暂无调用记录')
  })

  it('renders an unrouted fallback when analytics has no provider', async () => {
    arrangeSuccessfulSources({
      ...analyticsFixture(),
      models: [{
        modelCode: 'text-basic',
        providerCode: null,
        upstreamModel: null,
        requestCount: 1,
        totalTokens: 12,
      }],
    })

    const wrapper = mountDashboard()
    await flushPromises()

    expect(wrapper.text()).toContain('text-basic · 未路由')
    expect(wrapper.text()).not.toContain('text-basic · null')
  })

  it('never renders or commits late account data after logout and login', async () => {
    const oldAnalytics = deferred<UsageAnalytics>()
    const oldQuota = deferred<ReturnType<typeof accountQuota>>()
    const oldUsage = deferred<ReturnType<typeof accountUsage>>()
    const oldMetrics = deferred<{ sampleCount: number; platformAddedP95Ms: number }>()
    const newAnalytics = deferred<UsageAnalytics>()
    const newQuota = deferred<ReturnType<typeof accountQuota>>()
    const newUsage = deferred<ReturnType<typeof accountUsage>>()
    const newMetrics = deferred<{ sampleCount: number; platformAddedP95Ms: number }>()
    api.getUsageAnalytics
      .mockReturnValueOnce(oldAnalytics.promise)
      .mockReturnValueOnce(newAnalytics.promise)
    api.quotaSummary.mockReturnValueOnce(oldQuota.promise).mockReturnValueOnce(newQuota.promise)
    api.listUsage.mockReturnValueOnce(oldUsage.promise).mockReturnValueOnce(newUsage.promise)
    api.usageMetrics.mockReturnValueOnce(oldMetrics.promise).mockReturnValueOnce(newMetrics.promise)
    const auth = useAuthStore()
    const store = useUsageStore()
    auth.setSession('token-a', user('user-a', 'tenant-a'))
    const accountA = mountDashboard()
    await Promise.resolve()

    auth.clearSession()
    auth.setSession('token-b', user('user-b', 'tenant-b'))
    accountA.unmount()
    const accountB = mountDashboard()
    await Promise.resolve()
    expect(api.getUsageAnalytics).toHaveBeenCalledTimes(2)
    expect(api.quotaSummary).toHaveBeenCalledTimes(2)
    expect(api.listUsage).toHaveBeenCalledTimes(2)
    expect(api.usageMetrics).toHaveBeenCalledTimes(2)

    oldAnalytics.resolve(accountAnalytics('ACCOUNT_A', 901))
    oldQuota.resolve(accountQuota('ACCOUNT_A_PACKAGE'))
    oldUsage.resolve(accountUsage('req_account_a'))
    oldMetrics.resolve({ sampleCount: 1, platformAddedP95Ms: 901 })
    await flushPromises()

    expect(accountB.text()).not.toContain('ACCOUNT_A')
    expect(accountB.find('[title="req_account_a"]').exists()).toBe(false)
    expect(store.quota).toBeNull()
    expect(store.usage.items).toEqual([])
    expect(store.metrics).toBeNull()
    expect(store.analytics).toBeNull()
    expect(store.loadingQuota).toBe(true)
    expect(store.loadingUsage).toBe(true)
    expect(store.loadingAnalytics).toBe(true)

    newAnalytics.resolve(accountAnalytics('ACCOUNT_B', 202))
    newQuota.resolve(accountQuota('ACCOUNT_B_PACKAGE'))
    newUsage.resolve(accountUsage('req_account_b'))
    newMetrics.resolve({ sampleCount: 1, platformAddedP95Ms: 23 })
    await flushPromises()

    expect(accountB.text()).toContain('ACCOUNT_B_PACKAGE')
    expect(accountB.get('[data-test=dashboard-copy-request-id]').attributes('title')).toBe('req_account_b')
    expect(accountB.get('[title="req_account_b"]').text()).toContain('req_account')
    expect(accountB.text()).toContain('ACCOUNT_B · provider-b')
    expect(accountB.text()).not.toContain('ACCOUNT_A')
    expect(accountB.find('[title="req_account_a"]').exists()).toBe(false)
    expect(store.quota?.activePackage?.packageCode).toBe('ACCOUNT_B_PACKAGE')
    expect(store.usage.items[0]?.requestId).toBe('req_account_b')
    expect(store.metrics?.platformAddedP95Ms).toBe(23)
    expect(store.analytics?.summary.periodTokens).toBe(202)
  })

  it('keeps successful regions visible when analytics fails and retries only that source', async () => {
    arrangeSuccessfulSources()
    api.getUsageAnalytics
      .mockRejectedValueOnce(new Error('analytics unavailable'))
      .mockResolvedValueOnce(analyticsFixture())
    const wrapper = mountDashboard()
    await flushPromises()

    expect(wrapper.get('[data-test="dashboard-period-tokens"]').text()).toContain('--')
    expect(wrapper.get('[data-test="dashboard-p95"]').text()).toContain('91 ms')
    expect(wrapper.text()).toContain('专业版')
    expect(wrapper.get('[data-test=dashboard-copy-request-id]').attributes('title')).toBe('req_full_identifier_0123456789')
    expect(wrapper.get('[data-test="dashboard-analytics-error"]').text()).toBe('重试')
    expect(wrapper.text()).toContain('分析数据加载失败')

    await wrapper.get('[data-test="dashboard-analytics-retry"]').trigger('click')
    await flushPromises()
    expect(api.getUsageAnalytics).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-test="dashboard-period-tokens"]').text()).toContain('1,234')
  })

  it('keeps the newest analytics error when an older retry completes later', async () => {
    arrangeSuccessfulSources()
    const staleRetry = deferred<UsageAnalytics>()
    const newestRetry = deferred<UsageAnalytics>()
    api.getUsageAnalytics
      .mockRejectedValueOnce(new Error('initial analytics failure'))
      .mockReturnValueOnce(staleRetry.promise)
      .mockReturnValueOnce(newestRetry.promise)
    const wrapper = mountDashboard()
    await flushPromises()
    const retry = wrapper.get('[data-test="dashboard-analytics-retry"]')

    await retry.trigger('click')
    await retry.trigger('click')
    newestRetry.reject(new Error('newest analytics failure'))
    await flushPromises()
    expect(wrapper.get('[data-test="dashboard-analytics-error"]').exists()).toBe(true)

    staleRetry.resolve(analyticsFixture())
    await flushPromises()

    expect(wrapper.get('[data-test="dashboard-analytics-error"]').exists()).toBe(true)
    expect(useUsageStore().analytics).toBeNull()
    expect(useUsageStore().loadingAnalytics).toBe(false)
  })

  it('does not reuse analytics from a previous route visit after refresh fails', async () => {
    arrangeSuccessfulSources()
    const firstVisit = mountDashboard()
    await flushPromises()
    firstVisit.unmount()
    api.getUsageAnalytics.mockRejectedValueOnce(new Error('analytics unavailable'))

    const secondVisit = mountDashboard()
    await flushPromises()

    expect(secondVisit.get('[data-test="dashboard-period-tokens"]').text()).toContain('--')
    expect(secondVisit.get('[data-test="dashboard-today-requests"]').text()).toContain('--')
    expect(secondVisit.get('[data-test="dashboard-p95"]').text()).toContain('91 ms')
    expect(secondVisit.get('[data-test="dashboard-analytics-error"]').exists()).toBe(true)
  })

  it('copies the complete persisted request identifier', async () => {
    arrangeSuccessfulSources()
    const wrapper = mountDashboard()
    await flushPromises()

    await wrapper.get('[data-test="dashboard-copy-request-id"]').trigger('click')
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('req_full_identifier_0123456789')
  })

  it('switches user and admin KPI grids to tablet layout by 1100px', () => {
    expect(dashboardSource).toMatch(/@media \(max-width: 1100px\)[\s\S]*?\.metric-grid/)
    expect(adminCssSource).toMatch(/@media \(max-width: 1100px\)[\s\S]*?\.admin-metrics/)
  })

  it('keeps KPI labels aligned when metric footers have different content', () => {
    expect(dashboardSource).toMatch(/\.metric-body\s*\{[^}]*flex-direction:\s*column;/)
    expect(dashboardSource).toMatch(/\.metric-card\s*\{[^}]*display:\s*flex;/)
    expect(dashboardSource).toMatch(/\.metric-body\s*\{[^}]*display:\s*flex;[^}]*flex-direction:\s*column;/)
  })

  it('keeps administrator KPI labels aligned when metric footers differ', () => {
    expect(adminCssSource).toMatch(
      /\.admin-dashboard-metric\s*\{[^}]*align-content:\s*start;[^}]*grid-template-rows:\s*30px auto minmax\(17px, auto\);/s,
    )
    expect(adminCssSource).toMatch(
      /\.admin-dashboard-metric\s*>\s*span:not\(\.admin-dashboard-metric__icon\)\s*\{[^}]*align-items:\s*center;/s,
    )
  })

  it('contains no embedded demo model names or chart series fixtures', () => {
    expect(dashboardSource).not.toMatch(/deepseek-chat|qwen-plus|doubao/i)
    expect(dashboardSource).not.toMatch(/series\s*:\s*\[\s*\{[^}]*data\s*:\s*\[\s*\d/s)
  })
})

describe('real-data administrator dashboard', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })

  it('renders operating, package, gateway, and verified health facts', async () => {
    arrangeSuccessfulAdminSources()
    const wrapper = mountAdminDashboard()
    await flushPromises()

    expect(api.getOperatingAnalytics).toHaveBeenCalledWith(6)
    expect(api.getGatewayAdminAnalytics).toHaveBeenCalledWith(30)
    expect(wrapper.get('[data-test="admin-registered-users"]').text()).toContain('120')
    expect(wrapper.get('[data-test="admin-active-packages"]').text()).toContain('5')
    expect(wrapper.get('[data-test="admin-net-receipts"]').text()).toContain('¥123.45')
    expect(wrapper.get('[data-test="admin-today-requests"]').text()).toContain('117')
    expect(wrapper.get('[data-test="admin-today-requests"]').attributes('title')).toContain('成功 113')
    expect(wrapper.get('[data-test="admin-today-requests"]').attributes('title')).toContain('失败 4')
    expect(wrapper.text()).toContain('入门版')
    expect(wrapper.text()).toContain('专业版')
    expect(wrapper.findAll('[data-test="admin-dashboard-chart"]')).toHaveLength(3)
    expect(wrapper.get('[aria-label="已验证服务状态"]').text()).toContain('平台服务正常')
    expect(wrapper.get('[data-test="system-cache"]').text()).toContain('未检测')
    expect(wrapper.get('[data-test="system-cache"]').text()).toContain('Redis')
    expect(wrapper.find('[data-test="system-database"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="system-gateway"]').exists()).toBe(false)
  })

  it('switches only gateway analytics between 30 and 7 days', async () => {
    arrangeSuccessfulAdminSources()
    api.getGatewayAdminAnalytics
      .mockResolvedValueOnce(gatewayAdminFixture(30))
      .mockResolvedValueOnce({
        ...gatewayAdminFixture(7),
        summary: { ...gatewayAdminFixture(7).summary, todayRequests: 7 },
      })
    const wrapper = mountAdminDashboard()
    await flushPromises()

    await wrapper.get('[data-test="admin-range-7"]').trigger('click')
    await flushPromises()

    expect(api.getGatewayAdminAnalytics).toHaveBeenNthCalledWith(2, 7)
    expect(api.getOperatingAnalytics).toHaveBeenCalledTimes(1)
    expect(api.getAdminOverview).toHaveBeenCalledTimes(1)
    expect(api.getGatewayHealth).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-test="admin-range-7"]').attributes('aria-pressed')).toBe('true')
  })

  it('renders honest zero revenue and empty distributions without invented growth', async () => {
    arrangeSuccessfulAdminSources()
    api.getOperatingAnalytics.mockResolvedValue({
      ...operatingFixture(0),
      summary: {
        ...operatingFixture(0).summary,
        registeredUsers: 0,
        currentMonthCollectedAmount: 0,
        currentMonthRefundedAmount: 0,
        currentMonthNetAmount: 0,
      },
      monthly: [],
    })
    api.getGatewayAdminAnalytics.mockResolvedValue({
      ...gatewayAdminFixture(),
      summary: {
        ...gatewayAdminFixture().summary,
        todayRequests: 0,
        todaySuccessfulRequests: 0,
        todayFailedRequests: 0,
        periodRequests: 0,
        periodTokens: 0,
      },
      daily: [],
      packages: [],
    })
    const wrapper = mountAdminDashboard()
    await flushPromises()

    expect(wrapper.get('[data-test="admin-registered-users"]').text()).toContain('0')
    expect(wrapper.get('[data-test="admin-net-receipts"]').text()).toContain('¥0.00')
    expect(wrapper.get('[data-test="admin-today-requests"]').text()).toContain('0')
    expect(wrapper.text()).toContain('暂无活动套餐')
    expect(wrapper.text()).toContain('当前周期暂无真实调用')
    expect(wrapper.text()).not.toContain('MRR')
    expect(wrapper.find('.admin-mini-trend__delta').exists()).toBe(false)
  })

  it('retains gateway and health facts when operating analytics fails, then retries that source', async () => {
    arrangeSuccessfulAdminSources()
    api.getOperatingAnalytics
      .mockRejectedValueOnce(new Error('operating unavailable'))
      .mockResolvedValueOnce(operatingFixture())
    const wrapper = mountAdminDashboard()
    await flushPromises()

    expect(wrapper.get('[data-test="admin-net-receipts"]').text()).toContain('--')
    expect(wrapper.get('[data-test="admin-today-requests"]').text()).toContain('117')
    expect(wrapper.get('[aria-label="已验证服务状态"]').text()).toContain('网关服务正常')
    expect(wrapper.get('[data-test="admin-operating-error"]').text()).toBe('重试')
    expect(document.body.textContent).toContain('经营数据加载失败')

    await wrapper.get('[data-test="admin-operating-retry"]').trigger('click')
    await flushPromises()
    expect(api.getOperatingAnalytics).toHaveBeenCalledTimes(2)
    expect(api.getGatewayAdminAnalytics).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-test="admin-net-receipts"]').text()).toContain('¥123.45')
  })

  it('clears stale KPI snapshots when a later admin refresh fails', async () => {
    arrangeSuccessfulAdminSources()
    const wrapper = mountAdminDashboard()
    await flushPromises()
    api.getOperatingAnalytics.mockRejectedValueOnce(new Error('operating unavailable'))
    api.getGatewayAdminAnalytics.mockRejectedValueOnce(new Error('gateway analytics unavailable'))

    await wrapper.get('.admin-page__header button').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="admin-registered-users"]').text()).toContain('--')
    expect(wrapper.get('[data-test="admin-active-packages"]').text()).toContain('--')
    expect(wrapper.get('[data-test="admin-net-receipts"]').text()).toContain('--')
    expect(wrapper.get('[data-test="admin-today-requests"]').text()).toContain('--')
  })

  it('contains no demo revenue, fixed chart arrays, or MRR labels', () => {
    expect(adminOverviewSource).not.toMatch(/MRR|doubao|deepseek-chat|qwen-plus/i)
    expect(adminOverviewSource).not.toMatch(/series\s*:\s*\[\s*\{[^}]*data\s*:\s*\[\s*\d/s)
  })
})
