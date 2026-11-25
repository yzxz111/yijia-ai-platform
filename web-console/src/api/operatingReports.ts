import { platformHttp } from './http'

export type ReportGrain = 'DAY' | 'WEEK' | 'MONTH'
export type BillingMode = '' | 'PACKAGE_ONLY' | 'WALLET_ONLY' | 'HYBRID'

export interface OperatingReportFilter {
  from: string
  to: string
  grain: ReportGrain
  tenantId?: string
  userId?: string
  packagePlanVersionId?: string
  logicalModel?: string
  upstreamModel?: string
  channel?: string
  provider?: string
  billingMode?: BillingMode
  currency?: string
}

export interface OperatingMetrics {
  confirmedRevenueAmount: number
  cashInAmount: number
  refundAmount: number
  providerCostAmount: number
  paymentProviderFeeAmount: number
  refundProviderFeeAmount: number
  allocatedUsageRevenueAmount: number
  profitAmount: number
  profitMargin: number | null
  inputTokens: number
  outputTokens: number
  totalTokens: number
  factCount: number
  estimatedCount: number
  missingSourceCount: number
}

export interface OperatingSummary extends OperatingMetrics {
  timezone: string
  currency: string
  grain: ReportGrain
}

export interface OperatingReportResult {
  timezone: string
  currency: string
  grain: ReportGrain
  rows: Array<{ periodStart: string; metrics: OperatingMetrics }>
  total: OperatingMetrics
}

export interface ReconciliationItem {
  type: string
  sourceService: string
  sourceEventId: string
  currency: string
  sourceAmount: number
  factAmount: number
  differenceAmount: number
  evidence: Record<string, unknown>
}

export interface ReconciliationResult {
  items: ReconciliationItem[]
  total: number
  page: number
  size: number
  allocationRoundingDifference: number
}

export interface OperatingPage<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/**
 * 运营告警的关闭方。
 *
 * `MACHINE` = 条件不再成立后由定时巡检自动清除（套餐已恢复，
 * 或版本已下架）。
 * `OPERATOR` = 人工在本控制台处理。
 * `null` = 尚未解决。
 */
export type AlertResolutionOrigin = 'MACHINE' | 'OPERATOR'

/** 定时巡检为何能自行清除低毛利告警。 */
export type AlertResolutionReason = 'RECOVERED' | 'NO_LONGER_PUBLISHED'

export interface OperatingAlert {
  id: string
  type: string
  status: 'OPEN' | 'ACKED' | 'RESOLVED'
  logicalModel: string | null
  provider: string | null
  threshold: number
  observed: number
  currency: string
  sourceType?: string | null
  sourceEventId?: string | null
  evidence?: Record<string, unknown>
  firstObservedAt: string
  lastObservedAt: string
  /**
   * 缺失表示该部署尚未上报来源；已解决但无来源的告警
   * 必须渲染为「—」，绝不能显示为「由人工清除」或
   * 仍未解决。
   */
  resolutionOrigin?: AlertResolutionOrigin | null
  resolutionReason?: AlertResolutionReason | null
}

function params(filter: OperatingReportFilter) {
  return Object.fromEntries(Object.entries(filter).filter(([, value]) => value !== '' && value != null))
}

export async function getOperatingSummary(filter: OperatingReportFilter) {
  return (await platformHttp.get<OperatingSummary>('/console/platform/admin/operating-reports/summary', { params: params(filter) })).data
}

export async function getOperatingTrend(filter: OperatingReportFilter) {
  return (await platformHttp.get<OperatingReportResult>('/console/platform/admin/operating-reports/trend', { params: params(filter) })).data
}

export async function getOperatingBreakdown(filter: OperatingReportFilter) {
  return (await platformHttp.get<OperatingReportResult>('/console/platform/admin/operating-reports/breakdown', { params: params(filter) })).data
}

export async function getOperatingReconciliation(
  filter: Pick<OperatingReportFilter, 'from' | 'to'>,
  page: number,
  size: number,
) {
  return (await platformHttp.get<ReconciliationResult>('/console/platform/admin/operating-reports/reconciliation', {
    params: { ...filter, page, size },
  })).data
}

export async function getOperatingAlerts(
  status: OperatingAlert['status'] = 'OPEN',
  page = 0,
  size = 20,
) {
  return (await platformHttp.get<OperatingPage<OperatingAlert>>('/console/platform/admin/operating-reports/alerts', {
    params: { status, page, size },
  })).data
}

export async function acknowledgeOperatingAlert(id: string) {
  await platformHttp.post(`/console/platform/admin/operating-reports/alerts/${encodeURIComponent(id)}/ack`)
}

export async function resolveOperatingAlert(id: string) {
  await platformHttp.post(`/console/platform/admin/operating-reports/alerts/${encodeURIComponent(id)}/resolve`)
}

export async function exportOperatingReport(filter: OperatingReportFilter) {
  return (await platformHttp.get<Blob>('/console/platform/admin/operating-reports/export', {
    params: params(filter),
    headers: { Accept: 'text/csv' },
    responseType: 'blob',
  })).data
}
