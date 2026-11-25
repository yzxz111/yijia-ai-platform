import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import OperatingReportsView from './OperatingReportsView.vue'
import operatingReportsSource from './OperatingReportsView.vue?raw'
import * as admin from '../../api/admin'
import * as reports from '../../api/operatingReports'

const routerPush = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRouter: () => ({ push: routerPush }) }))

vi.mock('../../api/operatingReports', () => ({
  getOperatingSummary: vi.fn(),
  getOperatingTrend: vi.fn(),
  getOperatingBreakdown: vi.fn(),
  getOperatingReconciliation: vi.fn(),
  getOperatingAlerts: vi.fn(),
  acknowledgeOperatingAlert: vi.fn(),
  resolveOperatingAlert: vi.fn(),
  exportOperatingReport: vi.fn(),
}))

vi.mock('../../api/admin', () => ({
  listAdminOrders: vi.fn(),
  listAdminPlans: vi.fn(),
  listAdminUsage: vi.fn(),
  listAdminUsers: vi.fn(),
  listChannels: vi.fn(),
  listModels: vi.fn(),
  listProviders: vi.fn(),
}))

const api = vi.mocked(reports)
const adminApi = vi.mocked(admin)
const metrics = {
  confirmedRevenueAmount: 129900,
  cashInAmount: 200000,
  refundAmount: 1000,
  providerCostAmount: 30000,
  paymentProviderFeeAmount: 800,
  refundProviderFeeAmount: 20,
  allocatedUsageRevenueAmount: 70000.125,
  profitAmount: 98080,
  profitMargin: 0.761055,
  inputTokens: 100,
  outputTokens: 60,
  totalTokens: 160,
  factCount: 4,
  estimatedCount: 1,
  missingSourceCount: 0,
}

describe('OperatingReportsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    adminApi.listAdminOrders.mockResolvedValue({ items: [], total: 0, page: 0, size: 100 })
    api.getOperatingSummary.mockResolvedValue({ timezone: 'Asia/Shanghai', currency: 'CNY', grain: 'DAY', ...metrics })
    api.getOperatingTrend.mockResolvedValue({ timezone: 'Asia/Shanghai', currency: 'CNY', grain: 'DAY', total: metrics, rows: [
      { periodStart: '2026-08-01', metrics },
    ] })
    api.getOperatingBreakdown.mockResolvedValue({ timezone: 'Asia/Shanghai', currency: 'CNY', grain: 'DAY', total: metrics, rows: [
      { periodStart: '2026-08-01', metrics },
    ] })
    api.getOperatingReconciliation.mockImplementation(async (_filter, page, size) => ({ allocationRoundingDifference: 0, total: 21, page, size, items: [{
      type: 'AMOUNT_MISMATCH', sourceService: 'platform', sourceEventId: 'order:1', currency: 'CNY',
      sourceAmount: 129900, factAmount: 129800, differenceAmount: -100,
      evidence: { sourceType: 'PACKAGE_ORDER', orderNo: 'ORDER-1' },
    }] }))
    api.getOperatingAlerts.mockImplementation(async (_status, page = 0, size = 20) => ({ items: [{ id: 'alert-1', type: 'LOW_MARGIN_PACKAGE', status: 'OPEN',
      logicalModel: 'qwen-plus', provider: 'bailian', threshold: 0.3, observed: 0.2, currency: 'CNY',
      sourceType: 'GATEWAY_ECONOMICS', sourceEventId: '10000000-0000-4000-8000-000000000042',
      evidence: { requestId: 'request-42' },
      firstObservedAt: '2026-08-01T00:00:00Z', lastObservedAt: '2026-08-01T00:00:00Z' }],
      total: 21, page, size }))
    api.exportOperatingReport.mockResolvedValue(new Blob(['csv']))
    adminApi.listModels.mockResolvedValue({
      items: [{ id: 'model-1', code: 'qwen-plus', displayName: 'Qwen Plus' }],
      total: 1, page: 0, size: 100,
    } as never)
    adminApi.listChannels.mockResolvedValue({
      items: [{ id: 'channel-1', code: 'primary-channel', name: 'Primary Channel' }],
      total: 1, page: 0, size: 100,
    } as never)
    adminApi.listProviders.mockResolvedValue([
      { id: 'provider-1', code: 'bailian', name: 'Bailian' },
    ] as never)
    adminApi.listAdminUsers.mockResolvedValue({
      items: [{
        id: '71000000-0000-4000-8000-000000000002',
        tenantId: '71000000-0000-4000-8000-000000000001',
        userNo: 'U00000001',
      }],
      total: 1, page: 0, size: 100,
    } as never)
    adminApi.listAdminPlans.mockResolvedValue({
      items: [{ versionId: '71000000-0000-4000-8000-000000000003', code: 'STARTER', version: 1 }],
      total: 1, page: 0, size: 100,
    } as never)
    adminApi.listAdminUsage.mockResolvedValue({
      items: [{ stages: [{ upstreamModel: 'qwen-plus' }] }],
      total: 1, page: 0, size: 100,
    } as never)
    adminApi.listAdminOrders.mockResolvedValue({ items: [], total: 0, page: 0, size: 100 } as never)
  })

  it('loads the default 30-day report and formats cents as yuan', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(api.getOperatingSummary).toHaveBeenCalledTimes(2)
    const recharge = wrapper.findAll('.report-metrics > div').find(item => item.text().includes('充值入账'))
    expect(recharge?.text()).toContain('2,000.00')
    expect(wrapper.findAll('.report-metrics > div').find(item => item.text().includes('确认收入'))?.text()).toContain('1,299.00')
    const filter = api.getOperatingSummary.mock.calls[0][0]
    expect(daysBetween(filter.from, filter.to)).toBe(29)
    expect(wrapper.text()).toContain('经营报表')
    expect(wrapper.text()).toContain('¥1,299.00')
    expect(wrapper.text()).toContain('Asia/Shanghai')
    expect(wrapper.text()).not.toContain('当前筛选范围暂无经营数据')
  })

  it('keeps searchable selects as single controls aligned with native filters', () => {
    expect(operatingReportsSource).toMatch(
      /\.report-filters label > input,\s*\.report-filters label > select,\s*\.report-filters \.el-select__wrapper/,
    )
    expect(operatingReportsSource).not.toMatch(/\.report-filters input,\s*\.report-filters select/)
    expect(operatingReportsSource).not.toContain(':deep(')
  })

  it('does not show an empty detail state when the report has fewer than one page of rows', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-test="tab-detail"]').trigger('click')

    const detail = wrapper.get('[aria-labelledby="detail-title"]')
    expect(detail.text()).toContain('2026-08-01')
    expect(detail.text()).not.toContain('当前筛选范围暂无经营数据')
  })

  it('keeps the detail pager visible for one non-empty page', () => {
    expect(operatingReportsSource).toContain('data-test="report-detail-pagination"')
    expect(operatingReportsSource).not.toContain('v-if="(breakdown?.rows.length || 0) > reportDetailPageSize"')
  })

  it('gives reconciliation and alert pages independent loading state', () => {
    expect(operatingReportsSource).toContain(':loading="reconciliationLoading"')
    expect(operatingReportsSource).toContain(':loading="alertsLoading"')
  })

  it('loads every page of database-backed filter options', async () => {
    adminApi.listAdminUsers.mockReset()
      .mockResolvedValueOnce({
        items: [{ id: 'user-1', tenantId: 'tenant-1', userNo: 'U00000001' }],
        total: 101, page: 0, size: 100,
      } as never)
      .mockResolvedValueOnce({
        items: [{ id: 'user-101', tenantId: 'tenant-101', userNo: 'U00000101' }],
        total: 101, page: 1, size: 100,
      } as never)

    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-test="advanced-toggle"]').trigger('click')

    expect(adminApi.listAdminUsers).toHaveBeenCalledTimes(2)
    expect(adminApi.listAdminUsers).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
    expect(wrapper.text()).toContain('U00000101')
  })

  it('keeps successful filter options and offers retry when one option source fails', async () => {
    adminApi.listModels.mockRejectedValueOnce(new Error('models unavailable'))

    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-test="advanced-toggle"]').trigger('click')

    expect(wrapper.find('[data-test="report-options-error"]').exists()).toBe(false)
    expect(document.body.textContent).toContain('筛选项加载失败')
    expect(wrapper.text()).toContain('U00000001')
    await wrapper.get('[data-test="report-options-retry"]').trigger('click')
    await flushPromises()
    expect(adminApi.listModels).toHaveBeenCalledTimes(2)
  })

  it('keeps query and advanced filter actions aligned with filter controls', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('[data-test="report-query"]').classes()).toContain('report-filter-action')
    expect(wrapper.get('[data-test="advanced-toggle"]').classes()).toContain('report-filter-action')
  })

  it('retries failed loading and switches time grain', async () => {
    api.getOperatingSummary.mockRejectedValueOnce(new Error('network'))
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).not.toContain('经营数据加载失败')
    expect(document.body.textContent).toContain('经营数据加载失败')

    await wrapper.get('[data-test="report-retry"]').trigger('click')
    await flushPromises()
    expect(api.getOperatingSummary).toHaveBeenCalledTimes(3)

    await wrapper.get('[data-test="grain-WEEK"]').trigger('click')
    await flushPromises()
    expect(api.getOperatingTrend).toHaveBeenLastCalledWith(expect.objectContaining({ grain: 'WEEK' }))
  })

  it('exports current filters and navigates to the reconciliation source', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-test="report-export"]').trigger('click')
    await flushPromises()
    expect(api.exportOperatingReport).toHaveBeenCalledWith(expect.objectContaining({ grain: 'DAY' }))

    await wrapper.get('[data-test="tab-reconciliation"]').trigger('click')
    expect(wrapper.get('[data-test="reconciliation-type"]').text()).toBe('金额不一致')
    expect(wrapper.text()).not.toContain('AMOUNT_MISMATCH')
    await wrapper.get('[data-test="locate-difference"]').trigger('click')
    expect(routerPush).toHaveBeenCalledWith({ path: '/admin/orders', query: { orderNo: 'ORDER-1', tab: 'orders' } })
  })

  it('submits all advanced filters from database-backed selectors', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-test="advanced-toggle"]').trigger('click')

    await wrapper.get('[data-test="filter-tenant-id"]').setValue('71000000-0000-4000-8000-000000000001')
    await wrapper.get('[data-test="filter-user-id"]').setValue('71000000-0000-4000-8000-000000000002')
    await wrapper.get('[data-test="filter-package-id"]').setValue('71000000-0000-4000-8000-000000000003')
    await wrapper.get('[data-test="filter-upstream-model"]').setValue('qwen-plus')
    await wrapper.get('[data-test="filter-currency"]').setValue('USD')
    await wrapper.get('form.report-filters').trigger('submit')
    await flushPromises()
    expect(api.getOperatingSummary).toHaveBeenLastCalledWith(expect.objectContaining({
      tenantId: '71000000-0000-4000-8000-000000000001',
      userId: '71000000-0000-4000-8000-000000000002',
      packagePlanVersionId: '71000000-0000-4000-8000-000000000003',
      upstreamModel: 'qwen-plus', currency: 'USD',
    }))
    expect(wrapper.text()).toContain('U00000001')
    expect(adminApi.listAdminUsers).toHaveBeenCalledWith(expect.objectContaining({ sort: 'userNo' }))
  })

  it('navigates from alert evidence and falls back to a highlighted detail filter', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-test="tab-alerts"]').trigger('click')
    await wrapper.get('[data-test="locate-alert"]').trigger('click')
    expect(routerPush).toHaveBeenCalledWith({ path: '/admin/usage', query: { requestId: 'request-42', tab: 'usage' } })

    api.getOperatingReconciliation.mockResolvedValueOnce({ allocationRoundingDifference: 0, total: 1, page: 0, size: 20, items: [{
      type: 'MISSING', sourceService: 'other', sourceEventId: 'unknown:77', currency: 'CNY',
      sourceAmount: 0, factAmount: 0, differenceAmount: 0, evidence: { sourceType: 'UNKNOWN' },
    }] })
    await wrapper.get('form.report-filters').trigger('submit')
    await flushPromises()
    await wrapper.get('[data-test="tab-reconciliation"]').trigger('click')
    await wrapper.get('[data-test="locate-difference"]').trigger('click')
    expect(wrapper.get('[data-test="tab-detail"]').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('[data-test="detail-source-filter"]').text()).toContain('unknown:77')
  })

  it('uses real request evidence and never treats a gateway event UUID as a request ID', async () => {
    api.getOperatingReconciliation.mockResolvedValueOnce({ allocationRoundingDifference: 0, total: 1, page: 0, size: 20, items: [{
      type: 'AMOUNT_MISMATCH', sourceService: 'platform',
      sourceEventId: 'wallet-reservation:10000000-0000-4000-8000-000000000009:settled:v1',
      currency: 'CNY', sourceAmount: 10, factAmount: 11, differenceAmount: 1,
      evidence: { sourceType: 'MODEL_USAGE_LEDGER', requestId: 'req-real-42' },
    }] })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-test="tab-reconciliation"]').trigger('click')
    await wrapper.get('[data-test="locate-difference"]').trigger('click')
    expect(routerPush).toHaveBeenCalledWith({ path: '/admin/usage', query: { requestId: 'req-real-42', tab: 'usage' } })

    api.getOperatingReconciliation.mockResolvedValueOnce({ allocationRoundingDifference: 0, total: 1, page: 0, size: 20, items: [{
      type: 'MISSING', sourceService: 'gateway', sourceEventId: '10000000-0000-4000-8000-000000000001',
      currency: 'CNY', sourceAmount: 0, factAmount: 0, differenceAmount: 0,
      evidence: { sourceType: 'GATEWAY_ECONOMICS' },
    }] })
    await wrapper.get('form.report-filters').trigger('submit')
    await flushPromises()
    await wrapper.get('[data-test="tab-reconciliation"]').trigger('click')
    await wrapper.get('[data-test="locate-difference"]').trigger('click')
    expect(wrapper.get('[data-test="tab-detail"]').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('[data-test="detail-source-filter"]').text()).toContain('10000000-0000-4000-8000-000000000001')
  })

  it('ignores an older report request that completes after a newer filter request', async () => {
    const first = deferred<Awaited<ReturnType<typeof reports.getOperatingSummary>>>()
    api.getOperatingSummary
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce({ timezone: 'Asia/Shanghai', currency: 'CNY', grain: 'DAY', ...metrics })
      .mockResolvedValueOnce({ timezone: 'Asia/Shanghai', currency: 'CNY', grain: 'WEEK', ...metrics, confirmedRevenueAmount: 22200 })
    const wrapper = mountView()
    await wrapper.get('[data-test="grain-WEEK"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('¥222.00')

    first.resolve({ timezone: 'Asia/Shanghai', currency: 'CNY', grain: 'DAY', ...metrics, confirmedRevenueAmount: 11100 })
    await flushPromises()
    expect(wrapper.text()).toContain('¥222.00')
    expect(wrapper.text()).not.toContain('¥111.00')
  })

  it('keeps core reporting visible when reconciliation and alerts fail and offers retries', async () => {
    api.getOperatingReconciliation.mockRejectedValueOnce(new Error('reconciliation unavailable'))
    api.getOperatingAlerts.mockRejectedValueOnce(new Error('alerts unavailable'))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('¥1,299.00')
    expect(wrapper.find('[data-test="reconciliation-retry"]').exists()).toBe(true)
    await wrapper.get('[data-test="reconciliation-retry"]').trigger('click')
    await wrapper.get('[data-test="tab-alerts"]').trigger('click')
    expect(wrapper.find('[data-test="alerts-retry"]').exists()).toBe(true)
    await wrapper.get('[data-test="alerts-retry"]').trigger('click')
    await flushPromises()
    expect(api.getOperatingReconciliation).toHaveBeenCalledTimes(2)
    expect(api.getOperatingAlerts).toHaveBeenCalledTimes(2)
  })

  it('keeps an alert visible when its state changed concurrently', async () => {
    api.acknowledgeOperatingAlert.mockRejectedValueOnce({ response: { status: 409 } })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-test="tab-alerts"]').trigger('click')
    await wrapper.findAll('.report-actions button')[1].trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('套餐利润率过低')
    expect(api.getOperatingAlerts).toHaveBeenCalledTimes(1)
  })

  it('pages reconciliation and alerts independently and refreshes the current alert page', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(api.getOperatingReconciliation).toHaveBeenCalledWith(expect.any(Object), 0, 20)
    expect(api.getOperatingAlerts).toHaveBeenCalledWith('OPEN', 0, 20)

    await wrapper.get('[data-test="reconciliation-pagination"]').trigger('click')
    await flushPromises()
    expect(api.getOperatingReconciliation).toHaveBeenLastCalledWith(expect.any(Object), 1, 20)
    expect(api.getOperatingAlerts).toHaveBeenCalledTimes(1)

    await wrapper.get('[data-test="tab-alerts"]').trigger('click')
    await wrapper.get('[data-test="alerts-pagination"]').trigger('click')
    await flushPromises()
    expect(api.getOperatingAlerts).toHaveBeenLastCalledWith('OPEN', 1, 20)
    expect(api.getOperatingReconciliation).toHaveBeenCalledTimes(2)

    await wrapper.findAll('.report-actions button')[1].trigger('click')
    await flushPromises()
    expect(api.acknowledgeOperatingAlert).toHaveBeenCalledWith('alert-1')
    expect(api.getOperatingAlerts).toHaveBeenLastCalledWith('OPEN', 1, 20)
  })

  it('switches the alert list to resolved alerts and shows how they were cleared', async () => {
    api.getOperatingAlerts.mockResolvedValue({ items: [{
      id: 'alert-resolved', type: 'LOW_MARGIN_PACKAGE', status: 'RESOLVED',
      logicalModel: 'STARTER', provider: null, threshold: 0.4, observed: 0.37931, currency: 'CNY',
      firstObservedAt: '2026-08-01T00:00:00Z', lastObservedAt: '2026-08-01T00:00:00Z',
      resolutionOrigin: 'MACHINE', resolutionReason: 'RECOVERED',
    }], total: 1, page: 0, size: 20 })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-test="tab-alerts"]').trigger('click')
    await wrapper.get('[data-test="alert-status-RESOLVED"]').trigger('click')
    await flushPromises()

    expect(api.getOperatingAlerts).toHaveBeenLastCalledWith('RESOLVED', 0, 20)
    expect(wrapper.get('[data-test="alert-resolution-machine"]').text()).toContain('自动解除')
    expect(wrapper.text()).toContain('已自动解除：毛利回到预警线以上')
  })

  it('shows an empty state when all report datasets are empty', async () => {
    api.getOperatingTrend.mockResolvedValue({ timezone: 'Asia/Shanghai', currency: 'CNY', grain: 'DAY', total: metrics, rows: [] })
    api.getOperatingBreakdown.mockResolvedValue({ timezone: 'Asia/Shanghai', currency: 'CNY', grain: 'DAY', total: metrics, rows: [] })
    api.getOperatingReconciliation.mockResolvedValue({ allocationRoundingDifference: 0, items: [], total: 0, page: 0, size: 20 })
    api.getOperatingAlerts.mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('当前筛选范围暂无经营数据')
  })
  it('formats USD reports and reconciliation rows without a CNY symbol', async () => {
    api.getOperatingSummary.mockResolvedValue({ timezone: 'Asia/Shanghai', currency: 'USD', grain: 'DAY', ...metrics })
    api.getOperatingTrend.mockResolvedValue({ timezone: 'Asia/Shanghai', currency: 'USD', grain: 'DAY', total: metrics, rows: [] })
    api.getOperatingBreakdown.mockResolvedValue({ timezone: 'Asia/Shanghai', currency: 'USD', grain: 'DAY', total: metrics, rows: [] })
    api.getOperatingReconciliation.mockResolvedValue({ allocationRoundingDifference: 0, total: 1, page: 0, size: 20, items: [{
      type: 'AMOUNT_MISMATCH', sourceService: 'gateway', sourceEventId: 'usd-item', currency: 'USD',
      sourceAmount: 1234, factAmount: 1200, differenceAmount: -34, evidence: {},
    }] })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('$1,299.00')
    expect(wrapper.text()).toContain('$12.34')
    expect(wrapper.text()).not.toContain('¥1,299.00')
    expect(api.getOperatingSummary).toHaveBeenCalledWith(expect.objectContaining({ currency: 'CNY' }))
  })
})

function mountView() {
  return mount(OperatingReportsView, {
    global: {
      stubs: {
        AppChart: { template: '<div data-test="trend-chart" />' },
        'el-date-picker': true,
        'el-input': true,
        'el-select': {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>',
        },
        'el-option': {
          props: ['label', 'value'],
          template: '<option :value="value">{{ label }}</option>',
        },
        'el-pagination': {
          props: ['currentPage'],
          emits: ['current-change'],
          template: '<button :data-test="$attrs[\'data-test\']" @click="$emit(\'current-change\', Number(currentPage) + 1)">next</button>',
        },
        'el-tag': { template: '<span><slot /></span>' },
      },
      directives: { loading: () => undefined },
    },
  })
}

function daysBetween(from: string, to: string) {
  return Math.round((new Date(`${to}T00:00:00Z`).getTime() - new Date(`${from}T00:00:00Z`).getTime()) / 86_400_000)
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}
