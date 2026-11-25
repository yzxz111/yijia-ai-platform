import { gatewayHttp, platformHttp } from './http'
import type { AnalyticsRange, DailyUsage, ModelUsage, PageResult, UploadProgress, UsageEntry } from './gateway'
import type { PlatformPage, UsageDimension } from './platform'

export interface AdminOverview {
  users: number
  activeUsers: number
  grants: number
  outboxAttention: number
  orders: number
  paidOrders: number
  paidAmount: number
}

export interface PackageDistribution {
  packageCode: string
  packageName: string
  activeCount: number
}

export interface GatewayAdminAnalytics {
  range: AnalyticsRange
  summary: {
    todayRequests: number
    todaySuccessfulRequests: number
    todayFailedRequests: number
    todayTokens: number
    periodRequests: number
    periodTokens: number
    platformAddedP95Ms: number
  }
  daily: DailyUsage[]
  models: ModelUsage[]
  packages: PackageDistribution[]
}

export interface MonthlyOperating {
  month: string
  newUsers: number
  collectedAmount: number
  refundedAmount: number
  netAmount: number
}

export interface OperatingAnalytics {
  timezone: string
  months: 6
  summary: {
    registeredUsers: number
    activeUsers: number
    currentMonthCollectedAmount: number
    currentMonthRefundedAmount: number
    currentMonthNetAmount: number
    paidOrders: number
    currentMonthOrders: number
    currentMonthPaidOrders: number
    currentMonthInvalidOrRefunded: number
  }
  monthly: MonthlyOperating[]
}

export type AdminSortDirection = 'asc' | 'desc'

export interface AdminListParams {
  page: number
  size: number
  query: string
  status: string
  sort: string
  direction: AdminSortDirection
  packageCode?: string
}

export interface AdminUsageListParams extends AdminListParams {
  userId: string
  modelCode: string
  outcome: string
  requestId: string
}

export interface ReservationListParams extends AdminListParams {
  userId: string
  requestId: string
  anomalyOnly: boolean
}

export interface QuotaLedgerListParams extends AdminListParams {
  userId: string
  modelCode: string
  entryType: string
}

export interface ProviderPriceListParams extends AdminListParams {
  providerCode: string
  modelCode: string
}

export interface AuditListParams extends AdminListParams {
  actorUserId?: string
  action?: string
  resourceType?: string
  resourceId?: string
  outcome?: string
  from?: string
  to?: string
}

export interface AdminUser {
  id: string
  tenantId: string
  userNo?: string | null
  email: string | null
  phone?: string | null
  displayName: string
  status: 'ACTIVE' | 'DISABLED'
  role: 'USER' | 'ADMIN'
  globalAdmin: boolean
  statusManageable: boolean
  roleManageable: boolean
  authVersion: number
  lastLoginAt: string | null
  createdAt: string
  avatarUpdatedAt: string | null
  disabledReason: string | null
  disabledAt: string | null
  currentPackage: string | null
  currentPackageCode: string | null
  currentPackageVersion: number | null
}

export interface AdminUserPackageGrant {
  businessNo: string
  packageCode: string
  packageName: string
  version: number
  status: string
  reason: string
  createdAt: string
  updatedAt: string
}

export interface AdminUserDetail {
  user: AdminUser
  grants: AdminUserPackageGrant[]
}

export interface GrantRequest {
  userId: string
  packageVersionId: string
  reason: string
  businessNo: string
}

export type GrantActivationStatus = 'QUEUED' | 'ACTIVE' | 'FAILED'

export interface GrantResult {
  id: string
  eventId: string
  businessNo: string
  status: GrantActivationStatus
}

export interface AdminGrant {
  id: string
  businessNo: string
  userId: string
  userNo?: string | null
  userEmail: string | null
  userDisplayName: string
  packageCode: string
  packageName: string
  version: number
  status: GrantActivationStatus
  reason: string
  outboxId: string | null
  outboxStatus: string | null
  attemptCount: number
  failureReason: string | null
  createdAt: string
  updatedAt: string
}

export interface AdminPlanVersion {
  versionId: string
  code: string
  name: string
  description?: string
  planStatus: string
  version: number
  status: string
  priceAmount: number
  currency: string
  totalTokenQuota: number
  /** 方案 C 的第二份额度（音频秒）；null 表示这个版本不卖语音。 */
  audioSecondsQuota?: number | null
  durationDays: number
  maxApiKeys: number
  requestsPerMinute: number
  tokensPerMinute: number
  maxConcurrency: number
  stockTotal: number | null
  stockSold: number
  stockReserved: number
  lowStockThreshold: number
  maxPurchasesPerUser: number | null
  remainingStock: number | null
  soldOut: boolean
  lowStock: boolean
  estimatedCostAmount: number | null
  estimatedMarginRatio: number | null
  marginWarning: boolean
  modelCount: number
  publishedAt: string | null
  createdAt: string
}

export type PackagePlanKind = 'STANDARD' | 'FREE_CLAIM'

export interface AdminPackagePlanVersion {
  versionId: string
  version: number
  status: 'DRAFT' | 'PUBLISHED' | 'RETIRED'
  priceAmount: number
  currency: string
  totalTokenQuota: number
  /** 方案 C 的第二份额度（音频秒）；null 表示这个版本不卖语音（整块不渲染）。 */
  audioSecondsQuota?: number | null
  durationDays: number
  maxApiKeys: number
  requestsPerMinute: number
  tokensPerMinute: number
  maxConcurrency: number
  dailyRequestLimit: number | null
  maxOutputTokens: number | null
  maxFileSizeBytes: number
  oncePerUser: boolean
  estimatedCostAmount: number | null
  estimatedMarginRatio: number | null
  marginWarning: boolean
  stockTotal: number | null
  stockSold: number
  stockReserved: number
  lowStockThreshold: number
  maxPurchasesPerUser: number | null
  remainingStock: number | null
  soldOut: boolean
  lowStock: boolean
  modelCount: number
  publishedAt: string | null
  retiredAt: string | null
  createdAt: string
}

export interface AdminPackagePlan {
  code: string
  name: string
  description: string | null
  kind: PackagePlanKind
  status: 'ACTIVE' | 'ARCHIVED'
  displayOrder: number
  createdAt: string
  updatedAt: string
  versions: AdminPackagePlanVersion[]
}

export interface PackagePlanCatalogParams {
  page: number
  size: number
  query: string
  status: '' | 'ACTIVE' | 'ARCHIVED'
}

export interface CreatePackagePlanRequest {
  name: string
  description: string | null
  kind: PackagePlanKind
}

export interface ModelPermissionInput {
  modelCode: string
  modelTokenQuota: number | null
  capabilities: string[]
}

export interface CreatePlanVersionRequest {
  planCode: string
  priceAmount: number
  totalTokenQuota: number
  /**
   * 方案 C 的第二份额度（音频秒）。null = 这个版本不卖语音。
   *
   * 服务端要求它与模型对得上：含音频别名（AUDIO_TRANSCRIBE /
   * AUDIO_SYNTHESIS / LIP_SYNC）且主维度是 VIDEO_SECOND 时必须 > 0，
   * 其余情况必须为 null。因此非混合版本要**显式发 null**，
   * 而不是省略这个字段。
   */
  audioSecondsQuota: number | null
  durationDays: number
  maxApiKeys: number
  requestsPerMinute: number
  tokensPerMinute: number
  maxConcurrency: number
  dailyRequestLimit: number | null
  maxOutputTokens: number | null
  maxFileSizeBytes: number
  oncePerUser: boolean
  stockTotal: number | null
  lowStockThreshold: number
  maxPurchasesPerUser: number | null
  models: ModelPermissionInput[]
}

export interface UpdatePackagePlanRequest {
  name: string
  description: string | null
}

export interface PackagePlanResult {
  code: string
  name: string
  description: string | null
  kind: PackagePlanKind
  status: 'ACTIVE' | 'ARCHIVED'
  displayOrder: number
}

export interface AdminOrder {
  id: string
  orderNo: string
  userId: string
  userNo?: string | null
  userEmail: string | null
  userDisplayName: string
  packageCode: string
  packageName: string
  packageVersion: number
  amount: number
  currency: string
  status: string
  paymentProvider: string
  paymentStatus: string | null
  refundStatus: string | null
  paidAt: string | null
  createdAt: string
  updatedAt: string
  avatarUpdatedAt: string | null
  totalTokensUsed: number
  cumulativeSpendAmount: number
}

export interface PaymentSimulation {
  orderNo: string
  status: string
  externalTradeNo: string
}

export interface PaymentConfig {
  provider: string
  environment: string
  enabled: boolean
  configured: boolean
  credentialFingerprint: string | null
  lastValidationStatus: string | null
  lastValidatedAt: string | null
  updatedAt: string
}

export interface AdminPayment {
  id: string
  transactionNo: string
  orderId: string
  orderNo: string
  userId: string
  userEmail: string | null
  provider: string
  externalTradeNo: string | null
  amount: number
  status: string
  paidAt: string | null
  createdAt: string
  updatedAt: string
}

export type RefundStatus =
  | 'PREPARING'
  | 'PENDING_APPROVAL'
  | 'REJECTED'
  | 'CANCELED'
  | 'SUBMITTING'
  | 'PROCESSING'
  | 'REVOKING'
  | 'COMPLETED'
  | 'FAILED'

export type RefundType = 'FULL' | 'PARTIAL'

export interface AdminRefund {
  id: string
  refundNo: string
  orderId: string
  orderNo: string
  userId: string
  userNo?: string | null
  userEmail: string | null
  userDisplayName: string
  paymentTransactionId: string
  externalRefundNo: string | null
  amount: number
  status: RefundStatus
  reason: string
  completedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface RefundCalculationSnapshot {
  paidAmount: number
  paidAt: string
  requestedAt: string
  totalTokens: number
  usedTokens: number
  remainingTokens: number
  feeBps: number
  minimumFee: number
  maximumUsageBps: number
  refundWindowDays: number
  baseRefund: number
  fee: number
  refundAmount: number
  refundType: RefundType
  rounding: string
  policyVersion: number
  calculatedAt: string
  packageInstanceId: string
  originalPackageStatus: string
  packageExpiresAt: string | null
  gatewaySnapshotAt: string
}

export interface AdminRefundDetail {
  refundNo: string
  orderNo: string
  status: RefundStatus
  refundType: RefundType | null
  amount: number
  reason: string
  calculation: RefundCalculationSnapshot | null
  reviewedBy: string | null
  reviewedAt: string | null
  reviewNote: string | null
  failureReason: string | null
  createdAt: string
  updatedAt: string
}

export interface RefundPolicy {
  version: number
  feeBps: number
  minimumFee: number
  refundWindowDays: number
  maximumUsageBps: number
  effectiveAt: string
  published: true
  createdBy: string
  createdAt: string
}

export interface PublishRefundPolicyRequest {
  version: number
  feeBps: number
  minimumFee: number
  refundWindowDays: number
  maximumUsageBps: number
  effectiveAt: string
}

export type PaymentAnomalyStatus = 'OPEN' | 'INVESTIGATING' | 'RESOLVED'

export interface PaymentAnomalyListParams {
  page: number
  size: number
  anomalyType: string
  status: PaymentAnomalyStatus | ''
  provider: string
  from: string
  to: string
  orderId: string
  sort: string
  direction: AdminSortDirection
}

export interface PaymentAnomaly {
  id: string
  fingerprint: string
  anomalyType: string
  provider: string | null
  orderId: string | null
  paymentTransactionId: string | null
  refundId: string | null
  status: PaymentAnomalyStatus
  assignee: string | null
  detail: Record<string, unknown>
  resolutionNote: string | null
  resolvedAt: string | null
  createdAt: string
  updatedAt: string
  lockVersion: number
}

export interface UpdatePaymentAnomalyRequest {
  status: PaymentAnomalyStatus
  assignee: string | null
  resolutionNote: string | null
}

export type ReconciliationRunStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export type ReconciliationItemStatus = 'OPEN' | 'AUTO_RESOLVED' | 'RESOLVED'

export interface ReconciliationSummary {
  totalRecords: number
  matchedCount: number
  differenceCount: number
  autoResolvedCount: number
  openCount: number
  differenceCounts: Record<string, number>
}

export interface ReconciliationItem {
  id: string
  providerRecordId: string
  transactionNo: string | null
  refundNo: string | null
  differenceType: string
  status: ReconciliationItemStatus
  providerAmount: number | null
  platformAmount: number | null
  detail: Record<string, unknown>
  resolvedBy: string | null
  resolutionNote: string | null
  resolvedAt: string | null
  createdAt: string
}

export interface ReconciliationRun {
  id: string
  provider: string
  billDate: string
  status: ReconciliationRunStatus
  billSha256: string | null
  summary: ReconciliationSummary
  startedAt: string | null
  completedAt: string | null
  createdAt: string
  items: PlatformPage<ReconciliationItem>
}

export interface OutboxEvent {
  id: string
  aggregateType: string
  aggregateId: string
  eventType: string
  businessKey: string
  status: string
  attemptCount: number
  nextAttemptAt: string
  deliveredAt: string | null
  lastError: string | null
  createdAt: string
}

export interface OutboxHealth {
  pending: number
  delivering: number
  failed: number
  oldestSeconds: number
}

export interface AuditEntry {
  id: number
  source: 'PLATFORM' | 'GATEWAY'
  tenantId: string
  actorUserId: string | null
  actorUserNo: string | null
  actorDisplayName: string | null
  action: string
  resourceType: string
  resourceId: string | null
  requestId: string | null
  outcome: string
  detail: Record<string, unknown>
  createdAt: string
}

export interface AdminModelRoute {
  componentModelCode: string
  componentDisplayName: string
  providerId: string
  providerCode: string
  providerName: string
  channelId: string
  channelCode: string
  channelName: string
  upstreamModel: string
  environment: string
  baseUrl: string
  enabled: boolean
  healthStatus: string
  credentialConfigured: boolean
  priority: number
  weight: number
  timeoutMs: number
  requestsPerMinute: number
  maxConcurrency: number
  eligible: boolean
}

export interface AdminModel {
  id: string
  code: string
  displayName: string
  aliases: string[]
  capabilities: string[]
  routingStrategy: 'DIRECT' | 'COMPOSITE'
  contextWindow: number
  maxOutputTokens: number
  premium: boolean
  enabled: boolean
  requestsPerMinute: number
  maxConcurrency: number
  routes: AdminModelRoute[]
}

export interface AdminChannel {
  id: string
  providerId: string
  code: string
  name: string
  environment: string
  baseUrl: string
  enabled: boolean
  priority: number
  weight: number
  timeoutMs: number
  requestsPerMinute: number
  maxConcurrency: number
  lastHealthStatus: string
  credentialConfigured: boolean
}

export interface AdminProvider {
  id: string
  code: string
  name: string
  providerType: string
  baseUrl: string | null
  enabled: boolean
}

export interface CircuitHealth {
  key: string
  state: string
  failureRate: number
  bufferedCalls: number
  channelId?: string | null
  modelCode?: string | null
}

export interface RoutingTarget {
  channelId: string
  channelCode: string
  channelName: string
  providerName: string
  upstreamModel: string
  channelEnabled: boolean
  mappingEnabled: boolean
  healthStatus: string
  credentialConfigured: boolean
  selected: boolean
  eligible: boolean
}

export interface RoutingHealth {
  channels: AdminChannel[]
  circuits: CircuitHealth[]
}

export interface GatewayHealth {
  status: string
  database: string
  expiredReservations: number
  cleanupCandidates: number
  circuits: CircuitHealth[]
}

export interface ReservationEntry {
  id: string
  requestId: string
  userId: string
  modelCode: string
  reservedTokens: number
  actualTotalTokens: number | null
  uncoveredTokens: number
  usageEstimated: boolean
  status: string
  expiresAt: string
  createdAt: string
}

export interface AdminLedgerEntry {
  id: string
  requestId: string
  tenantId: string
  userId: string
  reservationId: string
  packageInstanceId: string
  modelCode: string
  entryType: string
  totalTokenDelta: number
  modelTokenDelta: number | null
  balanceAfter: number
  modelBalanceAfter: number | null
  createdAt: string
}

export interface ProviderPriceEntry {
  id: string
  providerCode: string
  modelCode: string
  inputPricePerMillion: number
  outputPricePerMillion: number
  cacheReadPricePerMillion: number | null
  cacheWritePricePerMillion: number | null
  reasoningPricePerMillion: number | null
  currency: string
  internalExchangeRate: number
  officialSource: string
  effectiveFrom: string
  effectiveTo: string | null
}

export interface OperationResult {
  operation: string
  status: string
  affected: number | null
}

export function businessNo(prefix: 'grant' | 'outbox' | 'operation' | 'sms-retry' | 'support-ticket') {
  const suffix = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `${prefix}-${suffix}`
}

function operationHeaders(operationBusinessNo: string) {
  return { 'X-Request-Id': operationBusinessNo, 'Idempotency-Key': operationBusinessNo }
}

export async function getAdminOverview() {
  return (await platformHttp.get<AdminOverview>('/console/platform/admin/overview')).data
}

export type SmsTemplateStatus = 'ACTIVE' | 'PENDING_INTEGRATION' | 'RESERVED'
export type SmsDeliveryStatus = 'PENDING' | 'SENDING' | 'SENT' | 'FAILED' | 'DEAD'

export interface SmsTemplate {
  templateKey: string
  templateCode: string
  templateType: 'VERIFICATION' | 'NOTIFICATION'
  integrationStatus: SmsTemplateStatus
  createdAt: string
  updatedAt: string
}

export interface SmsDelivery {
  id: string
  userId: string | null
  userNo: string | null
  userDisplayName: string | null
  templateKey: string
  templateCode: string
  maskedPhone: string
  status: SmsDeliveryStatus
  attemptCount: number
  nextAttemptAt: string
  sanitizedError: string | null
  sentAt: string | null
  createdAt: string
  updatedAt: string
}

export interface SmsDeliveryListParams extends AdminListParams {
  templateKey: string
}

export type CreateModelRequest = Omit<AdminModel, 'id' | 'routingStrategy' | 'routes'>
export type UpdateModelRequest = Omit<AdminModel, 'code' | 'routingStrategy' | 'routes'>

export interface SaveModelConfigurationRequest {
  id?: string
  code: string
  displayName: string
  aliases: string[]
  capabilities: string[]
  contextWindow: number
  maxOutputTokens: number
  premium: boolean
  enabled: boolean
  requestsPerMinute: number
  maxConcurrency: number
  route: {
    channelId?: string
    componentModelCode?: string
    providerId: string
    environment: string
    baseUrl: string
    apiKey?: string
    upstreamModel: string
    enabled: boolean
    priority: number
    weight: number
    timeoutMs: number
    requestsPerMinute: number
    maxConcurrency: number
  }
}

export async function getGatewayAdminAnalytics(days: 7 | 30) {
  return (await gatewayHttp.get<GatewayAdminAnalytics>('/console/gateway/admin/analytics', {
    params: { days },
  })).data
}

export async function getOperatingAnalytics(months: 6 = 6) {
  return (await platformHttp.get<OperatingAnalytics>('/console/platform/admin/analytics', {
    params: { months },
  })).data
}

export async function listAdminUsers(params: AdminListParams) {
  return (await platformHttp.get<PlatformPage<AdminUser>>('/console/platform/admin/users', {
    params,
  })).data
}

export async function updateAdminUser(
  id: string,
  status: AdminUser['status'],
  role: AdminUser['role'],
  disabledReason?: string,
) {
  return (await platformHttp.patch<AdminUser>(
    `/console/platform/admin/users/${encodeURIComponent(id)}`,
    { status, role, disabledReason },
  )).data
}

export async function createGrant(request: GrantRequest) {
  return (await platformHttp.post<GrantResult>('/console/platform/admin/grants', request)).data
}

export async function listGrants(params: AdminListParams) {
  return (await platformHttp.get<PlatformPage<AdminGrant>>('/console/platform/admin/grants', {
    params,
  })).data
}

export async function listAdminPlans(params: AdminListParams) {
  return (await platformHttp.get<PlatformPage<AdminPlanVersion>>('/console/platform/admin/plans', { params })).data
}

export async function listPackagePlanCatalog(params: PackagePlanCatalogParams) {
  return (await platformHttp.get<PlatformPage<AdminPackagePlan>>(
    '/console/platform/admin/package-plans',
    { params },
  )).data
}

export async function createPackagePlan(request: CreatePackagePlanRequest) {
  return (await platformHttp.post<PackagePlanResult>(
    '/console/platform/admin/package-plans',
    request,
  )).data
}

export async function createPlanVersion(request: CreatePlanVersionRequest) {
  return (await platformHttp.post(`/console/platform/admin/plans/${encodeURIComponent(request.planCode)}/versions`, request)).data
}

export async function publishPlanVersion(code: string, version: number) {
  return (await platformHttp.post(`/console/platform/admin/plans/${encodeURIComponent(code)}/publish`, { version })).data
}

export async function updatePackagePlan(code: string, request: UpdatePackagePlanRequest) {
  return (await platformHttp.patch<PackagePlanResult>(
    `/console/platform/admin/plans/${encodeURIComponent(code)}`,
    request,
  )).data
}

export async function archivePackagePlan(code: string) {
  return (await platformHttp.post<PackagePlanResult>(
    `/console/platform/admin/plans/${encodeURIComponent(code)}/archive`,
  )).data
}

export async function restorePackagePlan(code: string) {
  return (await platformHttp.post<PackagePlanResult>(
    `/console/platform/admin/plans/${encodeURIComponent(code)}/restore`,
  )).data
}

export async function listAdminOrders(params: AdminListParams) {
  return (await platformHttp.get<PlatformPage<AdminOrder>>('/console/platform/admin/orders', {
    params,
  })).data
}

export async function simulatePayment(orderNo: string) {
  return (await platformHttp.post<PaymentSimulation>(
    `/console/platform/admin/orders/${encodeURIComponent(orderNo)}/simulate-payment`,
    null,
  )).data
}

export async function listPaymentConfigs() {
  return (await platformHttp.get<PaymentConfig[]>('/console/platform/admin/payment-configs')).data
}

export async function listPayments(params: AdminListParams) {
  return (await platformHttp.get<PlatformPage<AdminPayment>>('/console/platform/admin/payments', {
    params,
  })).data
}

export async function listRefunds(params: AdminListParams) {
  const search = new URLSearchParams()
  appendSearchParam(search, 'page', params.page)
  appendSearchParam(search, 'size', params.size)
  appendSearchParam(search, 'query', params.query)
  appendSearchParam(search, 'status', params.status)
  appendSearchParam(search, 'sort', params.sort)
  appendSearchParam(search, 'direction', params.direction)
  return (await platformHttp.get<PlatformPage<AdminRefund>>('/console/platform/admin/refunds', {
    params: search,
  })).data
}

export async function getAdminRefund(refundNo: string) {
  return (await platformHttp.get<AdminRefundDetail>(
    `/console/platform/admin/refunds/${encodeURIComponent(refundNo)}`,
  )).data
}

export async function approveRefund(refundNo: string) {
  return (await platformHttp.post<AdminRefundDetail>(
    `/console/platform/admin/refunds/${encodeURIComponent(refundNo)}/approve`,
  )).data
}

export async function rejectRefund(refundNo: string, reviewNote: string) {
  return (await platformHttp.post<AdminRefundDetail>(
    `/console/platform/admin/refunds/${encodeURIComponent(refundNo)}/reject`,
    { reviewNote },
  )).data
}

export async function listRefundPolicies(page: number, size: number) {
  return (await platformHttp.get<PlatformPage<RefundPolicy>>('/console/platform/admin/refund-policies', {
    params: { page, size },
  })).data
}

export async function publishRefundPolicy(request: PublishRefundPolicyRequest) {
  return (await platformHttp.post<RefundPolicy>('/console/platform/admin/refund-policies', request)).data
}

function appendSearchParam(params: URLSearchParams, name: string, value: string | number) {
  if (typeof value === 'number' || value) params.set(name, String(value))
}

export async function listPaymentAnomalies(filters: PaymentAnomalyListParams) {
  const params = new URLSearchParams()
  appendSearchParam(params, 'page', filters.page)
  appendSearchParam(params, 'size', filters.size)
  appendSearchParam(params, 'anomalyType', filters.anomalyType)
  appendSearchParam(params, 'status', filters.status)
  appendSearchParam(params, 'provider', filters.provider)
  appendSearchParam(params, 'from', filters.from)
  appendSearchParam(params, 'to', filters.to)
  appendSearchParam(params, 'orderId', filters.orderId)
  appendSearchParam(params, 'sort', filters.sort)
  appendSearchParam(params, 'direction', filters.direction)
  return (await platformHttp.get<PlatformPage<PaymentAnomaly>>(
    '/console/platform/admin/payment-anomalies',
    { params },
  )).data
}

export async function updatePaymentAnomaly(
  id: string,
  request: UpdatePaymentAnomalyRequest,
  expectedLockVersion: number,
) {
  const response = await platformHttp.patch<PaymentAnomaly>(
    `/console/platform/admin/payment-anomalies/${encodeURIComponent(id)}`,
    request,
    { headers: { 'If-Match': `"${expectedLockVersion}"` } },
  )
  return response?.data
}

export async function startReconciliation(provider: string, billDate: string) {
  return (await platformHttp.post<ReconciliationRun>('/console/platform/admin/reconciliation-runs', {
    provider,
    billDate,
  })).data
}

export async function getReconciliationRun(
  id: string,
  page: number,
  size: number,
  sort: string,
  direction: AdminSortDirection,
) {
  const params = new URLSearchParams()
  appendSearchParam(params, 'page', page)
  appendSearchParam(params, 'size', size)
  appendSearchParam(params, 'sort', sort)
  appendSearchParam(params, 'direction', direction)
  return (await platformHttp.get<ReconciliationRun>(
    `/console/platform/admin/reconciliation-runs/${encodeURIComponent(id)}`,
    { params },
  )).data
}

export async function updatePaymentConfig(provider: string, environment: string, enabled: boolean) {
  return (await platformHttp.patch<PaymentConfig>(
    `/console/platform/admin/payment-configs/${encodeURIComponent(provider)}/${encodeURIComponent(environment)}`,
    { enabled },
  )).data
}

export async function listOutbox(params: AdminListParams) {
  return (await platformHttp.get<PlatformPage<OutboxEvent>>('/console/platform/admin/outbox', {
    params,
  })).data
}

export async function getOutboxHealth() {
  return (await platformHttp.get<OutboxHealth>('/console/platform/admin/outbox/health')).data
}

export async function retryOutbox(id: string, retryBusinessNo: string) {
  return (await platformHttp.post<OutboxEvent>(`/console/platform/admin/outbox/${encodeURIComponent(id)}/retry`, {
    businessNo: retryBusinessNo,
  })).data
}

export async function listAudit(params: AuditListParams) {
  return (await platformHttp.get<PlatformPage<AuditEntry>>('/console/platform/admin/audit', { params })).data
}

export async function listModels(params: AdminListParams) {
  return (await gatewayHttp.get<PageResult<AdminModel>>('/console/gateway/admin/models', { params })).data
}

function modelMutationPayload<T extends object>(request: T): Omit<T, 'routingStrategy'> {
  const payload = { ...request } as Record<string, unknown>
  delete payload.routingStrategy
  return payload as Omit<T, 'routingStrategy'>
}

export async function listSmsTemplates() {
  return (await platformHttp.get<SmsTemplate[]>('/console/platform/admin/sms/templates')).data
}

export async function listSmsDeliveries(params: SmsDeliveryListParams) {
  return (await platformHttp.get<PlatformPage<SmsDelivery>>('/console/platform/admin/sms/deliveries', {
    params,
  })).data
}

export async function retrySmsDelivery(id: string, retryBusinessNo: string) {
  return (await platformHttp.post<SmsDelivery>(
    `/console/platform/admin/sms/deliveries/${encodeURIComponent(id)}/retry`,
    { businessNo: retryBusinessNo },
  )).data
}

export async function createModel(request: CreateModelRequest) {
  return (await gatewayHttp.post<AdminModel>('/console/gateway/admin/models', modelMutationPayload(request))).data
}

export async function updateModel(request: UpdateModelRequest) {
  return (await gatewayHttp.patch<AdminModel>('/console/gateway/admin/models', modelMutationPayload(request))).data
}

export async function saveModelConfiguration(request: SaveModelConfigurationRequest) {
  return (await gatewayHttp.post<AdminModel>('/console/gateway/admin/models/configuration', request)).data
}

export async function setModelEnabled(id: string, enabled: boolean) {
  return (await gatewayHttp.patch<AdminModel>(
    `/console/gateway/admin/models/${encodeURIComponent(id)}/status`,
    { enabled },
  )).data
}

export async function deleteModel(id: string) {
  await gatewayHttp.delete(`/console/gateway/admin/models/${encodeURIComponent(id)}`)
}

/**
 * 视频别名的每秒计费（网关侧）。
 *
 * `resolutionTier` 是该别名唯一允许的分辨率档位（网关会拒绝其他档位）；
 * `providerUnitCost` / `unitPrice` 是每秒成本与零售价，单位「分/秒」；
 * 价格列为 null 表示该别名只登记了档位、还不能被定价。
 */
export interface VideoAliasBilling {
  modelCode: string
  resolutionTier: string
  providerUnitCost: number | null
  unitPrice: number | null
  retryRate: number | null
  targetMarginRatio: number | null
  minAbsoluteMargin: number | null
  priceVersion: string | null
}

export async function listVideoAliasBilling(): Promise<VideoAliasBilling[]> {
  return (await gatewayHttp.get<VideoAliasBilling[]>('/console/gateway/admin/video-aliases')).data
}

export async function saveVideoAliasBilling(
  modelCode: string,
  request: { resolutionTier: string; providerUnitCost: number; unitPrice: number; retryRate?: number },
): Promise<VideoAliasBilling> {
  return (await gatewayHttp.put<VideoAliasBilling>(
    `/console/gateway/admin/video-aliases/${encodeURIComponent(modelCode)}`,
    request,
  )).data
}

/**
 * 平台侧的「该别名按秒计费」登记。缺这一行，平台会把别名当成积分别名，
 * 含它的套餐会被发布成积分套餐，直到客户购买发放时才被网关拒绝。
 */
export async function registerModelDimension(
  modelCode: string,
  request: {
    usageDimension: UsageDimension
    resolutionTier: string
    effectiveUnitCost?: number | null
    retailUnitPrice?: number | null
  },
) {
  return (await platformHttp.put(
    `/console/platform/admin/model-dimensions/${encodeURIComponent(modelCode)}`,
    request,
  )).data
}

export async function listChannels(params: AdminListParams) {
  return (await gatewayHttp.get<PageResult<AdminChannel>>('/console/gateway/admin/channels', { params })).data
}

export async function listProviders() {
  return (await gatewayHttp.get<AdminProvider[]>('/console/gateway/admin/providers')).data
}

export async function createProvider(request: Omit<AdminProvider, 'id'>) {
  return (await gatewayHttp.post<AdminProvider>('/console/gateway/admin/providers', request)).data
}

export async function createChannel(request: Omit<AdminChannel, 'id' | 'lastHealthStatus' | 'credentialConfigured'> & { apiKey: string }) {
  return (await gatewayHttp.post<AdminChannel>('/console/gateway/admin/channels', request)).data
}

export async function updateChannel(request: Omit<AdminChannel, 'providerId' | 'code' | 'environment' | 'lastHealthStatus' | 'credentialConfigured'> & { apiKey?: string }) {
  return (await gatewayHttp.patch<AdminChannel>('/console/gateway/admin/channels', request)).data
}

export async function testChannel(id: string) {
  return (await gatewayHttp.post(`/console/gateway/admin/channels/${encodeURIComponent(id)}/test`)).data
}

/**
 * 「测试模型」的结果：真的向上游发过一次最小对话请求之后的结论。
 *
 * 判定通过只看 `ok`（= `modelInvoked && modelOk`）：`modelInvoked=false` 表示这次
 * 根本没发出请求（缺密钥、映射对不上、只探可达性），此时 `ok` 必然为 false。
 * 单独的 `reachable` 只说明「地址通」，**绝不能**当成功用——一把错误的密钥同样地址通。
 */
export interface AdminChannelModelTest {
  channelId: string
  providerType: string
  credentialConfigured: boolean
  reachable: boolean
  modelInvoked: boolean
  /** 那一次真实调用本身是否成功；未调用时为 false。 */
  modelOk: boolean
  /** 整个测试是否通过：`modelInvoked && modelOk`。 */
  ok: boolean
  status: string
  modelCode: string | null
  upstreamModel: string | null
  reason: string | null
  httpStatus: number | null
  latencyMs: number | null
  inputTokens: number | null
  outputTokens: number | null
  healthPersisted: boolean
  upstreamMessage: string | null
  models: {
    modelCode: string
    displayName: string
    upstreamModel: string
    mappingEnabled: boolean
    modelEnabled: boolean
    textCapable: boolean
    probeable: boolean
  }[]
}

/**
 * 真实模型调用测试：用该渠道已保存的地址与密钥，按上游模型名真发一次
 * `max_tokens=1` 的对话请求，并按结果回写渠道健康态。
 *
 * 它替代不了的那件事必须说清楚：可达性探测（`testChannel`）对错误的密钥、
 * 拼错的模型名、缺版本段的地址一律报 HEALTHY，只有真发一次请求才能区分它们。
 */
export async function testChannelModel(
  id: string,
  request: { upstreamModel?: string; connectivity?: boolean } = {},
): Promise<AdminChannelModelTest> {
  return (await gatewayHttp.post<AdminChannelModelTest>(
    `/console/gateway/admin/channels/${encodeURIComponent(id)}/model-test`,
    request,
  )).data
}

/** 删除服务商的应答：删掉了什么，一眼可见。 */
export type AdminProviderDeletion = {
  id: string
  code: string
  deletedChannels: number
  deletedRoutes: number
  deletedPrices: number
}

/**
 * 删除服务商（清理废弃接入）。
 *
 * `cascade` 为 true 时连同它名下的渠道与别名映射一起删除；被用量、成本或生成任务
 * 引用过的服务商一律拒删（409 `provider_has_usage_history`），这类应当停用而不是删除。
 */
export async function deleteProvider(id: string, cascade = false) {
  return (
    await gatewayHttp.delete<AdminProviderDeletion>(
      `/console/gateway/admin/providers/${encodeURIComponent(id)}`,
      { params: cascade ? { cascade: true } : undefined },
    )
  ).data
}

/**
 * 服务商类型的能力目录：这种类型能服务哪些对话模态、能不能生图、能不能生成视频。
 *
 * 存在的理由是一个具体的坑：把一把能读图的 key 挂到没有对话适配器的服务商类型
 * （例如 `TIANYIYUN`）下，保存成功、渠道也显示 HEALTHY，但每一次对话/读图请求都 503。
 * 控制台拿这份目录在「选中服务商」时就提示，而不是等调用失败。
 */
export interface AdminProviderTypeCapabilities {
  providerType: string
  /** 该类型支持的对话模态（`TEXT` / `FILE` / `IMAGE` …）；空数组表示没有对话适配器。 */
  chatCapabilities: string[]
  imageGeneration: boolean
  videoGeneration: boolean
}

/**
 * 用尚未保存的地址与密钥向上游列一次模型的结果。
 *
 * `keyValid=false` 时 `httpStatus` 是上游的应答码（401/403 表示密钥无效），
 * `httpStatus=0` 配上 `error='upstream_unreachable'` 表示连不上。
 * 密钥只用于这一次请求，不落库。
 */
export interface AdminUpstreamModelProbe {
  channelId: string | null
  keyValid: boolean
  httpStatus: number
  models: string[]
  error: string | null
}

/** 一条「平台别名 ← 上游模型」的接入意图。 */
export interface AdminRouteOnboardingInput {
  modelCode: string
  upstreamModel: string
  enabled: boolean
}

export type AdminRouteOnboardingStatus = 'CREATED' | 'UPDATED' | 'REJECTED'

/** 批量接入里每一条的结果；`REJECTED` 时 `reason` 说明被拒的稳定原因。 */
export interface AdminRouteOnboardingResult {
  modelCode: string
  upstreamModel: string
  status: AdminRouteOnboardingStatus
  reason: string | null
}

/**
 * 批量接入的应答。
 *
 * `notes` 是「接进去了但还不能用」的提示（视频别名缺按秒计价、同别名还有优先级
 * 不低的旧渠道…）。它们不是失败，但必须显眼展示，否则运营会以为已经可以卖了。
 */
export interface AdminRoutesOnboarding {
  channelId: string
  routes: AdminRouteOnboardingResult[]
  notes: string[]
}

/** 服务商类型的能力目录；视图挂载时取一次即可。 */
export async function listProviderTypeCapabilities(): Promise<AdminProviderTypeCapabilities[]> {
  return (await gatewayHttp.get<AdminProviderTypeCapabilities[]>(
    '/console/gateway/admin/provider-types',
  )).data
}

/**
 * 用还没保存的 baseUrl + apiKey 探一次上游模型清单。
 *
 * 与 `testChannel` 的区别：那个只做 DNS/TCP/TLS 探测、不发 `Authorization` 头
 * （错误密钥照样显示 HEALTHY）；这个真的带着密钥问上游，401/403 会如实返回 `keyValid=false`。
 */
export async function probeUpstreamModels(
  request: { baseUrl: string; apiKey: string },
): Promise<AdminUpstreamModelProbe> {
  return (await gatewayHttp.post<AdminUpstreamModelProbe>(
    '/console/gateway/admin/upstream-models',
    request,
  )).data
}

/** 一次把多条「平台别名 ← 上游模型」映射写到指定渠道上。 */
export async function onboardChannelRoutes(
  channelId: string,
  routes: AdminRouteOnboardingInput[],
): Promise<AdminRoutesOnboarding> {
  return (await gatewayHttp.post<AdminRoutesOnboarding>(
    `/console/gateway/admin/channels/${encodeURIComponent(channelId)}/routes`,
    { routes },
  )).data
}

export async function getRoutingHealth() {
  return (await gatewayHttp.get<RoutingHealth>('/console/gateway/admin/routing/health')).data
}

function compactListParams<T extends object>(params: T): Partial<T> {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => (
      typeof value !== 'string' || value.trim().length > 0
    )),
  ) as Partial<T>
}

export async function listAdminUsage(params: AdminUsageListParams) {
  return (await gatewayHttp.get<PageResult<UsageEntry>>('/console/gateway/admin/usage', {
    params: compactListParams(params),
  })).data
}

export async function listReservations(params: ReservationListParams) {
  return (await gatewayHttp.get<PageResult<ReservationEntry>>('/console/gateway/admin/reservations', {
    params: compactListParams(params),
  })).data
}

export async function listQuotaLedger(params: QuotaLedgerListParams) {
  return (await gatewayHttp.get<PageResult<AdminLedgerEntry>>('/console/gateway/admin/quota-ledger', {
    params: compactListParams(params),
  })).data
}

export async function listProviderPrices(params: ProviderPriceListParams) {
  return (await gatewayHttp.get<PageResult<ProviderPriceEntry>>('/console/gateway/admin/provider-prices', { params })).data
}

export async function getGatewayHealth() {
  return (await gatewayHttp.get<GatewayHealth>('/console/gateway/admin/operations/health')).data
}

export async function recoverReservations(operationBusinessNo: string) {
  return (await gatewayHttp.post<OperationResult>('/console/gateway/admin/operations/recover-reservations', null, {
    headers: operationHeaders(operationBusinessNo),
  })).data
}

export async function cleanupFiles(operationBusinessNo: string) {
  return (await gatewayHttp.post<OperationResult>('/console/gateway/admin/operations/cleanup-files', null, {
    headers: operationHeaders(operationBusinessNo),
  })).data
}

/**
 * 平台已向上游付款、却无法从客户处收回到账的一笔已结算请求。
 * `amount` 以分为单位，`tokens` 是未能结算的额度。
 */
export interface SettlementDeficitItem {
  reservationId: string
  amount: number
  tokens: number
  occurredAt: string
  planCode: string | null
  userId: string | null
}

/**
 * 平台自己承担的成本：已结算但分文未收到的预留。
 * `truncated` 表示条目列表只是最近一段切片，而非全量数据——
 * 但合计仍覆盖全部。该字段可选，因为服务可能不返回它，
 * 缺失时绝不能渲染成「全部明细」。
 */
export interface SettlementDeficit {
  totalAmount: number
  totalTokens: number
  count: number
  items: SettlementDeficitItem[]
  truncated?: boolean | null
}

/**
 * 面向管理员的结算垫付报表。金额单位为分，
 * 由调用方用控制台现有的货币格式化工具处理。
 */
export async function getSettlementDeficit() {
  return (await platformHttp.get<SettlementDeficit>('/console/platform/admin/operations/settlement-deficit')).data
}

export type BroadcastAudienceKind = 'ALL_ACTIVE_USERS' | 'USER_IDS'

export interface NotificationBroadcast {
  id: string
  eventType: string
  title: string
  body: string
  maintainTime: string
  providerBusinessNo: string
  tenantId: string | null
  audienceKind: BroadcastAudienceKind
  status: 'DRAFT' | 'SCHEDULED' | 'SENDING' | 'COMPLETED' | 'CANCELED'
  scheduledAt: string | null
  paused: boolean
  recipientCount: number
}

export interface BroadcastPage {
  items: NotificationBroadcast[]
  total: number
  page: number
  size: number
}

export interface BroadcastInput {
  title: string
  body: string
  maintainTime: string
  tenantId: string | null
  audienceKind: BroadcastAudienceKind
  userIds: string[]
}

export interface AdminSupportComment {
  id: string
  authorUserId: string
  authorRole: string
  body: string
  createdAt: string
}

export async function resetRoutingCircuit(key: string) {
  return (await gatewayHttp.post<CircuitHealth>(`/console/gateway/admin/routing/circuits/${encodeURIComponent(key)}/reset`)).data
}

export async function listRoutingTargets(key: string) {
  return (await gatewayHttp.get<RoutingTarget[]>(`/console/gateway/admin/routing/circuits/${encodeURIComponent(key)}/targets`)).data
}

export async function switchRoutingCircuit(key: string, channelId: string) {
  return (await gatewayHttp.post<RoutingTarget>(`/console/gateway/admin/routing/circuits/${encodeURIComponent(key)}/switch`, { channelId })).data
}

export async function restoreAutomaticRouting(key: string) {
  await gatewayHttp.delete(`/console/gateway/admin/routing/circuits/${encodeURIComponent(key)}/switch`)
}

export async function getAdminUser(id: string) {
  return (await platformHttp.get<AdminUserDetail>(`/console/platform/admin/users/${encodeURIComponent(id)}`)).data
}

export async function getAdminUserAvatar(id: string) {
  return (await platformHttp.get<Blob>(`/console/platform/admin/users/${encodeURIComponent(id)}/avatar`, {
    responseType: 'blob',
  })).data
}

export interface AdminSupportAttachment {
  id: string
  commentId: string | null
  filename: string
  contentType: string
  sizeBytes: number
  createdAt: string
}

export interface AdminSupportTicket {
  id: string
  ticketNo: string
  tenantId: string
  userId: string
  assigneeUserId: string | null
  title: string
  body: string
  status: 'OPEN' | 'PROCESSING' | 'COMPLETED' | 'CLOSED'
  resolution: string | null
  completionBusinessNo: string | null
  completedAt: string | null
  createdAt: string
  updatedAt: string
  comments: AdminSupportComment[] | null
}

export interface AdminSupportTicketPage {
  items: AdminSupportTicket[]
  total: number
  page: number
  size: number
}

export function listNotificationBroadcasts(params: { page: number; size: number; status: string }) {
  return platformHttp.get<BroadcastPage>('/console/platform/admin/notification-broadcasts', { params }).then(response => response.data)
}

export function createNotificationBroadcast(payload: BroadcastInput) {
  return platformHttp.post<NotificationBroadcast>('/console/platform/admin/notification-broadcasts', payload).then(response => response.data)
}

export function scheduleNotificationBroadcast(id: string, scheduledAt: string) {
  return platformHttp.post<NotificationBroadcast>(`/console/platform/admin/notification-broadcasts/${encodeURIComponent(id)}/schedule`, { scheduledAt }).then(response => response.data)
}

export interface AdminUserIdentity {
  userId: string
  userNo: string
  displayName: string
}

export async function resolveAdminUserNumbers(ids: string[]) {
  const uniqueIds = [...new Set(ids.filter(Boolean))]
  if (!uniqueIds.length) return []
  if (uniqueIds.length > 200) throw new Error('too_many_user_ids')
  const params = new URLSearchParams()
  uniqueIds.forEach((id) => params.append('ids', id))
  return (await platformHttp.get<AdminUserIdentity[]>('/console/platform/admin/users/identities', {
    params,
  })).data
}

export function publishNotificationBroadcast(id: string) {
  return platformHttp.post<NotificationBroadcast>(`/console/platform/admin/notification-broadcasts/${encodeURIComponent(id)}/publish`).then(response => response.data)
}

export function pauseNotificationBroadcast(id: string) {
  return platformHttp.post<NotificationBroadcast>(`/console/platform/admin/notification-broadcasts/${encodeURIComponent(id)}/pause`).then(response => response.data)
}

export function resumeNotificationBroadcast(id: string, scheduledAt: string) {
  return platformHttp.post<NotificationBroadcast>(`/console/platform/admin/notification-broadcasts/${encodeURIComponent(id)}/resume`, { scheduledAt }).then(response => response.data)
}

export function cancelNotificationBroadcast(id: string) {
  return platformHttp.post<NotificationBroadcast>(`/console/platform/admin/notification-broadcasts/${encodeURIComponent(id)}/cancel`).then(response => response.data)
}

export function listAdminSupportTickets(params: { page: number; size: number; status: string; query?: string; from?: string | null; to?: string | null }) {
  return platformHttp.get<AdminSupportTicketPage>('/console/platform/admin/support-tickets', { params }).then(response => response.data)
}

export function batchClaimAdminSupportTickets(ids: string[]) {
  return platformHttp.post<AdminSupportTicket[]>('/console/platform/admin/support-tickets/batch-claim', { ids }).then(response => response.data)
}

export function getAdminSupportTicket(id: string) {
  return platformHttp.get<AdminSupportTicket>(`/console/platform/admin/support-tickets/${encodeURIComponent(id)}`).then(response => response.data)
}

export function listAdminSupportTicketAttachments(id: string) {
  return platformHttp.get<AdminSupportAttachment[]>(`/console/platform/admin/support-tickets/${encodeURIComponent(id)}/attachments`).then(response => response.data)
}

export function getAdminSupportTicketAttachment(id: string, attachmentId: string) {
  return platformHttp.get<Blob>(`/console/platform/admin/support-tickets/${encodeURIComponent(id)}/attachments/${encodeURIComponent(attachmentId)}`, {
    responseType: 'blob',
  }).then(response => response.data)
}

export function claimAdminSupportTicket(id: string) {
  return platformHttp.post<AdminSupportTicket>(`/console/platform/admin/support-tickets/${encodeURIComponent(id)}/claim`).then(response => response.data)
}

export function processAdminSupportTicket(id: string) {
  return platformHttp.post<AdminSupportTicket>(`/console/platform/admin/support-tickets/${encodeURIComponent(id)}/process`).then(response => response.data)
}

export function replyAdminSupportTicket(id: string, body: string) {
  return platformHttp.post<AdminSupportTicket>(`/console/platform/admin/support-tickets/${encodeURIComponent(id)}/reply`, { body }).then(response => response.data)
}

export function completeAdminSupportTicket(id: string, resolution: string, providerBusinessNo: string) {
  return platformHttp.post<AdminSupportTicket>(`/console/platform/admin/support-tickets/${encodeURIComponent(id)}/complete`, { resolution, providerBusinessNo }).then(response => response.data)
}

export function closeAdminSupportTicket(id: string) {
  return platformHttp.post<AdminSupportTicket>(`/console/platform/admin/support-tickets/${encodeURIComponent(id)}/close`).then(response => response.data)
}

/* ==========================================================================
 * Skill 管理（设计 2026-09-14-skill-management-client-download-design.md §5.1 A1–A13）
 *
 * 全部走 `gatewayHttp` → `/console/gateway/admin/skills/**`（ADMIN JWT）。
 * 错误体是管理端既有的**扁平 `{code,message}`**（不是 `/v1/**` 的 OpenAI 信封），
 * 因此调用方用 `src/views/admin/skillAdminErrors.ts` 映射文案。
 * 与只读目录 `/console/gateway/skills`（OpenAI 信封）是两套通道，不要混用。
 * ========================================================================== */

export type SkillAdminStatus = 'DRAFT' | 'PUBLISHED' | 'UNPUBLISHED' | 'DELETED'
export type SkillVersionStatus = 'DRAFT' | 'READY' | 'PUBLISHED' | 'ARCHIVED' | 'REJECTED' | 'FAILED'
export type SkillScanStatus = 'PENDING' | 'SCANNING' | 'PASSED' | 'REJECTED' | 'FAILED'

/**
 * 技能类型（专家化改造 D1）：
 * `SKILL` 普通技能 / `EXPERT` 专家 / `TEAM` 专家团。
 * `kind` 创建后不可更改；更新时回传当前值，若试图修改，后端返回
 * 422 `invalid_skill_kind`（E-01）。
 */
export type SkillKind = 'SKILL' | 'EXPERT' | 'TEAM'
export type SkillKindFilter = SkillKind | 'EXPERT,TEAM' | ''
/** Language binding for the single Expert feature-image asset. `null` is the
 * legacy/absent marker; clients default an existing unmarked image to Chinese. */
export type ExpertImageLocale = 'zh' | 'en' | 'neutral'

/** 花名册成员角色；一个技能最多一个 `LEAD`（E-04，服务层校验） */
export type SkillMemberRole = 'LEAD' | 'MEMBER'

/** A3/A14 详情中的成员视图（设计 §3.1） */
export interface SkillMemberView {
  id: string
  memberKey: string
  name: string | null
  nameEn: string | null
  profession: string | null
  professionEn: string | null
  role: SkillMemberRole
  sortOrder: number
  /** 成员引用专家包；页面用登录态 `fetchSkillMemberAvatar` 读取该专家的图标字节。 */
  avatarUrl: string | null
}

/** `PUT /{id}/members` 的请求项（整体替换语义，`memberKey` 必填且唯一） */
export interface SkillMemberPayload {
  memberKey: string
  name: string | null
  nameEn: string | null
  profession: string | null
  professionEn: string | null
  role: SkillMemberRole
  sortOrder: number
}

/** A1 列表条目（设计 §5.1 的 JSON 示例） */
export interface SkillAdminListItem {
  id: string
  code: string
  name: string
  category: string | null
  tags: string[]
  summary: string | null
  icon: string | null
  iconUrl: string | null
  /** 介绍插画预览路径；未上传时为 null */
  illustrationUrl?: string | null
  /** 专家功能示例图片预览路径；未上传时为 null */
  exampleImageUrl?: string | null
  /** 示例图片替代哪一种语言的结构化示例；旧数据缺失时桌面/编辑器按中文处理。 */
  exampleImageLocale?: ExpertImageLocale | null
  status: SkillAdminStatus
  publishedVersion: string | null
  latestVersion: string | null
  minClientVersion: string | null
  sizeBytes: number | null
  downloadCount: number
  createdAt: string
  updatedAt: string
  rowVersion: number
  /** 专家化改造新增（A1 起返回） */
  kind: SkillKind
  nameEn: string | null
  summaryEn: string | null
  descriptionEn: string | null
  /** Expert 目录展示字段；旧后端未返回时保持 undefined，前端回退到旧字段。 */
  usageGuide?: string | null
  usageGuideEn?: string | null
  taskTypes?: string[] | null
  taskTypesEn?: string[] | null
  examplePrompts?: string[] | null
  examplePromptsEn?: string[] | null
  examplePreview?: ExpertExamplePreview | null
  examplePreviewEn?: ExpertExamplePreview | null
  /** 部署了较旧网关版本时仍会接受的旧标量 preview 字段。 */
  examplePreviewInput?: string | null
  examplePreviewInputEn?: string | null
  examplePreviewOutput?: string | null
  examplePreviewOutputEn?: string | null
  tagsEn?: string[] | null
  /** D5：列表只给计数，不带 `manifest` / `members` */
  memberCount: number
  /** G4：已发布版本的依赖边数（我依赖谁），由 A1 列表聚合返回 */
  dependencyCount: number
  /** G4：引用了该编码的可用版本数（谁依赖我），由 A1 列表聚合返回 */
  dependentCount: number
  /** Native source format for records created from external skill registries. */
  sourceFormat?: 'CLAWHUB_SKILL' | string | null
  /** Version declared by the native source record, separate from YCPS package versions. */
  sourceVersion?: string | null
  /** Whether this native source still needs an operator review before publish. */
  sourceReviewRequired?: boolean | null
  /** Native source malware-scan state, independent of YCPS version scans. */
  sourceScanStatus?: SkillScanStatus | null
}

export interface ExpertExamplePreview {
  heading: string
  headingPrefix?: string | null
  scene?: string | null
  request: string
  material?: string | null
  outputTitle: string
  outputParagraphs: string[]
  intro?: { tone?: string | null; text: string } | null
  table?: {
    headers: string[]
    columnWidths: number[]
    rows: Array<Array<string | number>>
    totalRow?: boolean | null
    /** Optional visual contract shared by the console and desktop semantic renderer. */
    style?: {
      fontSize?: number
      color?: string
      headerColor?: string
      headerBackgroundColor?: string
      borderColor?: string
      borderRadius?: number
      cellPadding?: number
      rowHeight?: number
    }
  } | null
  sections?: Array<{ title: string; blocks: Array<{ tone?: string | null; text: string }> }> | null
  blocks?: Array<{ tone?: string | null; text: string }> | null
}

/** 专家档案端点返回的嵌套展示数据。 */
export interface AdminExpertPresentation {
  title: string
  titleEn: string | null
  summary: string | null
  summaryEn: string | null
  description: string | null
  descriptionEn: string | null
  taskTypes: string[]
  taskTypesEn: string[]
  usageGuide: string | null
  usageGuideEn: string | null
  examplePrompts: string[]
  examplePromptsEn: string[]
  preview: ExpertExamplePreview | null
  previewEn: ExpertExamplePreview | null
  /** 可选的固定 1080x704 布局与字符样式 run。 */
  canvas?: ExpertLocalizedCanvasPayload | null
  assets: Record<string, string>
  /** The feature image is only rendered for this locale, or both when neutral. */
  exampleImageLocale?: ExpertImageLocale | null
  portableRevision: number
  rowVersion: number
  updatedAt: string | null
}

/** 专家专用展示端点的 GET/PUT 信封。 */
export interface AdminExpertPresentationResponse {
  skillId: string
  code: string
  kind: SkillKind
  presentation: AdminExpertPresentation
}

/** 专家专用的展示写入契约；与运营类技能字段相互独立。 */
export interface ExpertPresentationPayload {
  usageGuide: string | null
  usageGuideEn: string | null
  taskTypes: string[] | null
  taskTypesEn: string[] | null
  examplePrompts: string[] | null
  examplePromptsEn: string[] | null
  preview: ExpertExamplePreview | null
  previewEn: ExpertExamplePreview | null
  canvas?: ExpertLocalizedCanvasPayload | null
  exampleImageLocale?: ExpertImageLocale | null
  rowVersion?: number
}

export type ExpertImageAction = 'KEEP' | 'REPLACE' | 'CLEAR'

/** 完整的专家创作载荷；运营字段与展示数据共用一次 CAS 保存。 */
export interface ExpertAggregatePayload extends SkillAdminPayload {
  code: string
  expectedRowVersion?: number
  status?: string | null
  taskTypes: string[]
  taskTypesEn: string[]
  usageGuide: string | null
  usageGuideEn: string | null
  examplePrompts: string[]
  examplePromptsEn: string[]
  preview: ExpertExamplePreview | null
  previewEn: ExpertExamplePreview | null
  canvas?: ExpertLocalizedCanvasPayload | null
  exampleImageLocale?: ExpertImageLocale | null
  images: {
    icon: ExpertImageAction
    illustration: ExpertImageAction
    exampleImage: ExpertImageAction
  }
}

export interface ExpertCanvasTextRunPayload {
  text: string
  color?: string
  fontSize?: number
  fontWeight?: number
  italic?: boolean
}

export interface ExpertCanvasNodePayload {
  id: string
  field: string
  kind: 'text' | 'card' | 'image' | 'tag' | 'table'
  x: number
  y: number
  width: number
  height: number
  runs?: ExpertCanvasTextRunPayload[]
  style?: {
    color?: string
    backgroundColor?: string
    borderColor?: string
    borderRadius?: number
    overflow?: 'visible' | 'auto'
  }
  assetRole?: 'illustration' | 'icon' | 'exampleImage'
  table?: {
    headers: string[]
    columnWidths: number[]
    rows: Array<Array<string | number>>
    totalRow?: boolean
    style?: {
      fontSize?: number
      color?: string
      headerColor?: string
      headerBackgroundColor?: string
      borderColor?: string
      borderRadius?: number
      cellPadding?: number
      rowHeight?: number
    }
  }
}

export interface ExpertCanvasPayload {
  version: 1
  width: 1080
  height: 704
  nodes: ExpertCanvasNodePayload[]
}

/** API 会把两个可选语言的布局一起保存，以便一次原子写入。 */
export interface ExpertLocalizedCanvasPayload {
  zh: ExpertCanvasPayload | null
  en?: ExpertCanvasPayload | null
}

export interface ExpertAggregateResponse {
  skillId: string
  skill: SkillAdminDetail
  presentation: AdminExpertPresentation
}

/** 回收站条目；只包含定位、展示和保留期限所需字段。 */
export interface DeletedSkillView {
  id: string
  code: string
  name: string
  nameEn: string | null
  category: string | null
  kind: SkillKind
  deletedAt: string
  expiresAt: string
}

/** A3 详情里的版本行（字段与后端 `SkillAdminResponses.SkillVersionView` 对齐） */
export interface SkillVersionView {
  id: string
  skillId: string
  version: string
  revision: number
  status: SkillVersionStatus
  sizeBytes: number | null
  md5: string | null
  sha256: string | null
  /** YCPS 信任字段（A3/A14 起返回；旧 Legacy 版本为 null / false） */
  packageFormat: string | null
  ycpsVersion: string | null
  packageKind: SkillKind | null
  /** 版本级 plugin.json 原文（只读） */
  pluginManifest: Record<string, unknown> | null
  /** 版本级 runtime manifest 原文（只读；审核面据此渲染权限与 hostRequirements） */
  runtimeManifest: Record<string, unknown> | null
  manifestSha256: string | null
  dependencyLockSha256: string | null
  /** 已发布并封存的版本不可原地替换，只能发布新的 SemVer */
  sealed: boolean
  sealedAt: string | null
  fileCount: number
  extractedBytes: number
  minClientVersion: string | null
  scanStatus: SkillScanStatus
  changelog: string | null
  downloadCount: number
  createdAt: string
  /**
   * 是否是当前线上版本。后端字段名是 `online`（**不是** `isPublished`）；
   * 保留 `status === 'PUBLISHED'` 作为历史/兜底判断。
   */
  online: boolean
}

/** A3 详情 */
export interface SkillAdminDetail extends SkillAdminListItem {
  description: string | null
  sortWeight: number
  versions: SkillVersionView[]
  /** 包内 `plugin.json` 原文，**只读**（D4：管理端不提供编辑入口） */
  manifest: Record<string, unknown> | null
  /** 仅详情返回成员花名册（D5）；列表只有 `memberCount` */
  members: SkillMemberView[]
}

export interface SkillAdminListParams {
  page: number
  size: number
  query: string
  status: SkillAdminStatus | ''
  category: string
  /** A1 新增可选筛选；非法值后端返回 422 `invalid_skill_kind` */
  kind: SkillKindFilter
  sort: string
  direction: AdminSortDirection
}

export interface SkillAdminPayload {
  name: string
  category: string | null
  tags: string[]
  summary: string | null
  description: string | null
  icon: string | null
  minClientVersion: string | null
  sortWeight: number
  /** 创建时缺省为 `SKILL`；更新时必须保持原值 */
  kind: SkillKind
  nameEn: string | null
  summaryEn: string | null
  descriptionEn: string | null
}

export interface UpdateSkillAdminPayload extends SkillAdminPayload {
  /** A4 乐观锁；不匹配后端返回 409 skill_concurrent_modification */
  rowVersion: number
}

export interface SkillVersionUploadFields {
  version: string
  changelog?: string
  minClientVersion?: string
}

export type SkillVersionUploadProgress = UploadProgress

const SKILL_ADMIN_BASE = '/console/gateway/admin/skills'

/** 列表只丢掉空字符串筛选项，`page: 0` 必须原样发出（设计 §8.0 硬约束） */
function skillListParams(params: SkillAdminListParams): Partial<SkillAdminListParams> {
  return compactListParams(params)
}

export async function listAdminSkills(params: SkillAdminListParams) {
  return (await gatewayHttp.get<PlatformPage<SkillAdminListItem>>(SKILL_ADMIN_BASE, {
    params: skillListParams(params),
  })).data
}

export async function listDeletedAdminSkills(params: {
  page: number
  size: number
  query?: string
  kind?: SkillKindFilter
}) {
  return (await gatewayHttp.get<PlatformPage<DeletedSkillView>>(`${SKILL_ADMIN_BASE}/trash`, {
    params: compactListParams(params),
  })).data
}

export async function getAdminSkill(id: string) {
  return (await gatewayHttp.get<SkillAdminDetail>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}`,
  )).data
}

export async function createAdminSkill(payload: CreateSkillAdminPayload) {
  return (await gatewayHttp.post<SkillAdminDetail>(SKILL_ADMIN_BASE, payload)).data
}

/**
 * 把 A1 列表行补成 A3 详情形状（本地占位，不发请求）。
 * 用于「新建成功后立刻切到资源包段」：此时还没有任何版本，`versions` 为空数组，
 * 花名册与包内清单同样为空（D4/D5）。
 */
export function skillDetailPlaceholder(item: SkillAdminListItem): SkillAdminDetail {
  return { ...item, description: null, sortWeight: 0, versions: [], manifest: null, members: [] }
}

export async function updateAdminSkill(id: string, payload: UpdateSkillAdminPayload) {
  return (await gatewayHttp.put<SkillAdminDetail>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}`,
    payload,
  )).data
}

/** 读取结构化的专家展示数据；旧网关可能返回 404/405。 */
export async function fetchAdminExpertPresentation(id: string) {
  return (await gatewayHttp.get<AdminExpertPresentationResponse>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/expert-presentation`,
  )).data
}

/** 专家专用的目录展示写入。与运营类技能 PUT 相互独立。 */
export async function updateAdminExpertPresentation(
  id: string,
  payload: ExpertPresentationPayload,
) {
  return (await gatewayHttp.put<AdminExpertPresentationResponse>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/expert-presentation`,
    payload,
  )).data
}

function expertAggregateForm(payload: ExpertAggregatePayload, files: {
  icon?: File | null
  illustration?: File | null
  exampleImage?: File | null
}) {
  const form = new FormData()
  form.append('data', new Blob([JSON.stringify(payload)], { type: 'application/json' }))
  if (files.icon) form.append('icon', files.icon)
  if (files.illustration) form.append('illustration', files.illustration)
  if (files.exampleImage) form.append('exampleImage', files.exampleImage)
  return form
}

/** 原子化创建专家：基础字段、档案与三张图片的处置决定都在一次请求里完成。 */
export async function createAdminExpertAggregate(
  payload: ExpertAggregatePayload,
  files: { icon?: File | null; illustration?: File | null; exampleImage?: File | null } = {},
) {
  return (await gatewayHttp.post<ExpertAggregateResponse>(
    `${SKILL_ADMIN_BASE}/experts`,
    expertAggregateForm(payload, files),
  )).data
}

/** 原子化保存专家；只有对应的动作为 REPLACE 时才会发送图片文件。 */
export async function updateAdminExpertAggregate(
  id: string,
  payload: ExpertAggregatePayload,
  files: { icon?: File | null; illustration?: File | null; exampleImage?: File | null } = {},
) {
  return (await gatewayHttp.put<ExpertAggregateResponse>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/expert-presentation`,
    expertAggregateForm(payload, files),
  )).data
}

export async function deleteAdminSkill(id: string) {
  await gatewayHttp.delete(`${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}`)
}

/** 从 30 天回收站恢复；服务端保证恢复后仍为草稿或已下架状态。 */
export async function restoreAdminSkill(id: string) {
  return (await gatewayHttp.post<SkillAdminDetail>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/restore`,
  )).data
}

/** 立即彻底删除回收站记录及其资源对象。 */
export async function purgeAdminSkill(id: string) {
  await gatewayHttp.delete(`${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/purge`)
}

export async function publishAdminSkill(id: string) {
  return (await gatewayHttp.post<SkillAdminDetail>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/publish`,
  )).data
}

export async function unpublishAdminSkill(id: string) {
  return (await gatewayHttp.post<SkillAdminDetail>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/unpublish`,
  )).data
}

/**
 * A5 上传/替换图片图标（multipart，字段 `file`）。
 * `iconUrl` 需要带管理员 JWT 才能访问，因此响应式 `<img src>` 无法直接显示；
 * 页面用 `fetch` + `URL.createObjectURL` 取预览（见 loadIconObjectUrl）。
 */
export async function uploadAdminSkillIcon(id: string, file: File) {
  const body = new FormData()
  body.append('file', file)
  return (await gatewayHttp.post<SkillAdminDetail>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/icon`,
    body,
  )).data
}

/** 带鉴权拉取图标字节流，供页面做本地预览 */
export async function fetchAdminSkillIcon(id: string): Promise<Blob> {
  return (await gatewayHttp.get<Blob>(`${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/icon`, {
    responseType: 'blob',
  })).data
}

/**
 * 清空已上传的图片图标，记录回退到文本徽标。
 *
 * 幂等：本来就没有图标时也返回当前详情（不会 404），
 * 因为「清空」按钮在列表里对每一行都可见，重复点击不该报错。
 */
export async function deleteAdminSkillIcon(id: string) {
  return (await gatewayHttp.delete<SkillAdminDetail>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/icon`,
  )).data
}

/**
 * A25 下载整包（YCPS zip）。
 *
 * `versionId` 省略即下载**当前线上版本**，这是默认按钮的行为；指定时下载该历史版本。
 * 与客户端 `/v1/skills/**` 的下载会话不同，这条路径不做设备指纹与配额记账，
 * 只记一条 `skill.package.download` 管理审计。
 *
 * 包体最大 200 MB，因此调用方必须走 blob 而不是文本；文件名与校验值从
 * `Content-Disposition` / `X-Skill-Md5` 等响应头取，见 `utils/skillPackageDownload.ts`。
 */
export async function downloadAdminSkillPackage(
  id: string,
  versionId?: string,
): Promise<{ blob: Blob; fileName: string; md5: string | null; sha256: string | null }> {
  const path = versionId
    ? `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/versions/${encodeURIComponent(versionId)}/package`
    : `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/package`
  const response = await gatewayHttp.get<Blob>(path, { responseType: 'blob' })
  return {
    blob: response.data,
    fileName: contentDispositionFileName(response.headers['content-disposition']) ?? '',
    md5: (response.headers['x-skill-md5'] as string | undefined) ?? null,
    sha256: (response.headers['x-skill-sha256'] as string | undefined) ?? null,
  }
}

/** Download the original source ZIP for the legacy single-source ClawHub record. */
export async function downloadAdminClawhubSkillPackage(
  skillId: string,
): Promise<{ blob: Blob; fileName: string }> {
  const response = await gatewayHttp.get<Blob>(
    `/console/gateway/admin/skill-source-imports/clawhub/${encodeURIComponent(skillId)}/package`,
    { responseType: 'blob' },
  )
  return {
    blob: response.data,
    fileName: contentDispositionFileName(response.headers['content-disposition']) ?? '',
  }
}

/**
 * 下载**含图完整包**（设计 A26）：外层是一个普通 ZIP，里面一个文件夹同时装
 * 未改动的 `.ycps.zip` 与已上传的图标 / 介绍插画 / 示例图片。
 *
 * 与 `downloadAdminSkillPackage` 的区别是 `Content-Type` 为 `application/zip`、
 * 且**不返回 md5/sha256** —— 外层包是现场拼的，两个哈希描述的是内层包体。
 */
export async function downloadAdminSkillPackageBundle(
  id: string,
  versionId?: string,
): Promise<{ blob: Blob; fileName: string; assetCount: number }> {
  const path = versionId
    ? `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/versions/${encodeURIComponent(versionId)}/package/bundle`
    : `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/package/bundle`
  const response = await gatewayHttp.get<Blob>(path, { responseType: 'blob' })
  return {
    blob: response.data,
    fileName: contentDispositionFileName(response.headers['content-disposition']) ?? '',
    assetCount: Number(response.headers['x-skill-bundle-assets'] ?? 0) || 0,
  }
}

/** 从 `Content-Disposition` 取出 `filename="..."`；缺失时返回 null 由调用方兜底命名。 */
function contentDispositionFileName(header: unknown): string | null {
  if (typeof header !== 'string') return null
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/iu.exec(header)
  return match ? decodeURIComponent(match[1]) : null
}

/**
 * 专家花名册：**整体替换**（设计 §3.1 `PUT /{id}/members`）。
 *
 * 语义是「本次请求体即最终花名册」，因此页面必须带上完整列表（含未改动行）与
 * 重排后的 `sortOrder`；`memberKey` 必填、`memberKey` 重复或出现多个 `LEAD`
 * 都会 422 `invalid_skill_member`（E-04 / E-05）。
 */
export async function replaceAdminSkillMembers(id: string, members: SkillMemberPayload[]) {
  return (await gatewayHttp.put<SkillAdminDetail>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/members`,
    { members },
  )).data
}

/**
 * 上传/替换介绍插画（multipart，字段 `file`）：PNG/JPEG/WebP/SVG，≤4 MB。
 * 超限后端返回 413 `skill_illustration_too_large`。
 */
export async function uploadAdminSkillIllustration(id: string, file: File) {
  const body = new FormData()
  body.append('file', file)
  return (await gatewayHttp.post<SkillAdminDetail>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/illustration`,
    body,
  )).data
}

/**
 * 清空已上传的介绍插画，返回 `illustrationUrl=null` 的详情。
 * 幂等：本来就没有插画时也返回当前详情（不会 404）。
 */
export async function deleteAdminSkillIllustration(id: string) {
  return (await gatewayHttp.delete<SkillAdminDetail>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/illustration`,
  )).data
}

/** 带鉴权拉取介绍插画字节流（`illustrationUrl` 不能直接给 `<img src>` 用） */
export async function fetchAdminSkillIllustration(id: string): Promise<Blob> {
  return (await gatewayHttp.get<Blob>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/illustration`,
    { responseType: 'blob' },
  )).data
}

/** 上传/替换专家功能示例图片（PNG/JPEG/WebP/SVG，≤4 MB）。 */
export async function uploadAdminSkillExampleImage(id: string, file: File) {
  const body = new FormData()
  body.append('file', file)
  return (await gatewayHttp.post<SkillAdminDetail>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/example-image`,
    body,
  )).data
}

/** 清空示例图片；幂等。 */
export async function deleteAdminSkillExampleImage(id: string) {
  return (await gatewayHttp.delete<SkillAdminDetail>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/example-image`,
  )).data
}

/** 带鉴权拉取示例图片字节流。 */
export async function fetchAdminSkillExampleImage(id: string): Promise<Blob> {
  return (await gatewayHttp.get<Blob>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/example-image`,
    { responseType: 'blob' },
  )).data
}

function skillPackageBody(file: File, fields: SkillVersionUploadFields) {
  const body = new FormData()
  body.append('file', file)
  body.append('version', fields.version)
  if (fields.changelog) body.append('changelog', fields.changelog)
  if (fields.minClientVersion) body.append('minClientVersion', fields.minClientVersion)
  return body
}

function skillPackageReplacementBody(file: File, fields: SkillPackageReplacementFields) {
  const body = new FormData()
  body.append('file', file)
  if (fields.changelog) body.append('changelog', fields.changelog)
  if (fields.minClientVersion) body.append('minClientVersion', fields.minClientVersion)
  return body
}

/** A6 上传技能压缩包（multipart，字段 file/version/changelog/minClientVersion） */
export async function uploadSkillVersion(
  id: string,
  form: FormData,
  onProgress?: (progress: SkillVersionUploadProgress) => void,
  signal?: AbortSignal,
) {
  return (await gatewayHttp.post<SkillVersionView>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/versions`,
    form,
    {
      signal,
      onUploadProgress: ({ loaded, total }) => {
        onProgress?.({ phase: 'uploading', percent: skillUploadPercent(loaded, total) })
      },
    },
  )).data
}

/** A7 替换资源包（同版本新 revision） */
export async function replaceSkillPackage(
  id: string,
  versionId: string,
  form: FormData,
  onProgress?: (progress: SkillVersionUploadProgress) => void,
  signal?: AbortSignal,
) {
  return (await gatewayHttp.put<SkillVersionView>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/versions/${encodeURIComponent(versionId)}/package`,
    form,
    {
      signal,
      onUploadProgress: ({ loaded, total }) => {
        onProgress?.({ phase: 'uploading', percent: skillUploadPercent(loaded, total) })
      },
    },
  )).data
}

/** A8 设为线上版本 */
export async function publishSkillVersion(id: string, versionId: string) {
  return (await gatewayHttp.post<SkillVersionView>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/versions/${encodeURIComponent(versionId)}/publish`,
  )).data
}

/** A12 删除非线上版本 */
export async function deleteSkillVersion(id: string, versionId: string) {
  await gatewayHttp.delete(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/versions/${encodeURIComponent(versionId)}`,
  )
}

/** A13 下载与安装日志（分页，与 A1 同形 `{items,total,page,size}`） */
export async function listSkillDownloads(id: string, params: SkillDownloadListParams) {
  return (await gatewayHttp.get<PlatformPage<SkillDownloadEntry>>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/downloads`,
    { params },
  )).data
}

/** 构造上传请求体，供页面直接传给 uploadSkillVersion / replaceSkillPackage */
export function skillPackageForm(file: File, fields: SkillVersionUploadFields): FormData {
  return skillPackageBody(file, fields)
}

function skillUploadPercent(loaded: number, total?: number) {
  if (!total) return 0
  return Math.max(0, Math.min(100, Math.round((loaded / total) * 100)))
}

/**
 * 上传层重试闸门：扫描失败（fail closed，设计 R-15）与并发冲突是**瞬时**问题，
 * 自动重试一两次；结构非法（ZIP/manifest/超限）是确定性拒绝，不重试。
 */
const SKILL_UPLOAD_RETRY_CODES = new Set([
  'skill_scan_failed',
  'skill_concurrent_modification',
  'skill_version_conflict',
  'skill_storage_unavailable',
])
const SKILL_UPLOAD_ATTEMPTS = 3
const SKILL_UPLOAD_RETRY_DELAY_MS = 1_500

function sleep(ms: number, signal?: AbortSignal) {
  if (signal?.aborted) return Promise.reject(abortSignalReason(signal))
  return new Promise<void>((resolve, reject) => {
    const onAbort = () => {
      clearTimeout(timer)
      reject(abortSignalReason(signal!))
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

function abortSignalReason(signal: AbortSignal) {
  return signal.reason ?? new DOMException('The operation was aborted.', 'AbortError')
}

export interface SkillUploadRetryOptions {
  onProgress?: (progress: SkillVersionUploadProgress) => void
  onRetry?: (attempt: number, error: unknown) => void
  signal?: AbortSignal
}

function isReusableBody(form: FormData, body: FormData) {
  return body === form && typeof FormData !== 'undefined' && FormData.prototype.entries !== undefined
}

/** 失败后能否安全地重新发送同一个 multipart 请求体 */
function canReuseForm(form: FormData, file: File) {
  if (typeof File === 'undefined') return false
  if (isReusableBody(form, form)) return true
  return file instanceof File
}

function rebuildForm(form: FormData, file: File) {
  if (isReusableBody(form, form)) return form
  const rebuilt = new FormData()
  for (const [name, value] of form.entries()) {
    if (value === file) rebuilt.append(name, file, file.name)
    else rebuilt.append(name, value as string)
  }
  return rebuilt
}

async function withSkillUploadRetry<T>(
  form: FormData,
  file: File,
  send: (body: FormData, options: SkillUploadRetryOptions) => Promise<T>,
  options: SkillUploadRetryOptions = {},
): Promise<T> {
  let lastError: unknown
  for (let attempt = 1; attempt <= SKILL_UPLOAD_ATTEMPTS; attempt += 1) {
    if (options.signal?.aborted) throw abortSignalReason(options.signal)
    try {
      const body = attempt === 1 ? form : rebuildForm(form, file)
      return await send(body, options)
    } catch (error) {
      lastError = error
      const code = readAdminErrorCode(error)
      const retryable = Boolean(code) && SKILL_UPLOAD_RETRY_CODES.has(code!)
      if (!retryable || attempt === SKILL_UPLOAD_ATTEMPTS || options.signal?.aborted) throw error
      options.onRetry?.(attempt, error)
      await sleep(SKILL_UPLOAD_RETRY_DELAY_MS * attempt, options.signal)
    }
  }
  throw lastError
}

function readAdminErrorCode(error: unknown) {
  const data = (error as { response?: { data?: unknown } })?.response?.data
  if (!data || typeof data !== 'object') return null
  const code = (data as { code?: unknown }).code
  return typeof code === 'string' && code.trim() ? code.trim() : null
}

/** A6 + 瞬时失败重试（用于页面「上传新版本」） */
export function uploadSkillVersionWithRetry(
  id: string,
  file: File,
  fields: SkillVersionUploadFields,
  options: SkillUploadRetryOptions = {},
) {
  const form = skillPackageBody(file, fields)
  return withSkillUploadRetry(
    form,
    file,
    (body, opts) => uploadSkillVersion(id, body, opts.onProgress, opts.signal),
    options,
  )
}

/** A7 + 瞬时失败重试（用于页面「替换当前版本资源包」） */
export function replaceSkillPackageWithRetry(
  id: string,
  versionId: string,
  file: File,
  fields: SkillPackageReplacementFields,
  options: SkillUploadRetryOptions = {},
) {
  const form = skillPackageReplacementBody(file, fields)
  return withSkillUploadRetry(
    form,
    file,
    (body, opts) => replaceSkillPackage(id, versionId, body, opts.onProgress, opts.signal),
    options,
  )
}

/* ==========================================================================
 * 协议管理（平台服务协议 / 隐私政策）
 *
 * 接口在 platform-service 的 `/console/platform/admin/legal-documents/**`（ADMIN JWT），
 * 因此统一走 `platformHttp`。四个接口都返回同一份 `{ documents: [...] }` 全量状态，
 * 页面拿到后整体覆盖本地状态即可，不需要单独回查。
 *
 * 正文结构与登录页内置文本 `src/consent.ts` 同形（placeholder + sections）；
 * 线上尚无已发布版本时公开接口返回 503，前端回退到内置文本，管理端则显示空编辑器。
 * ========================================================================== */

/** 只有这两份文档；其它值后端返回 422 `invalid_legal_document_kind` */
export type AdminLegalDocumentKind = 'TERMS' | 'PRIVACY'

export interface LegalDocumentSection {
  heading: string
  paragraphs: string[]
  /** 可选的项目符号列表，渲染在段落之后 */
  items?: string[]
}

export interface LegalDocumentContent {
  placeholder: string
  sections: LegalDocumentSection[]
}

/** 已发布版本或草稿的正文（后端 `DocumentView`） */
export interface AdminLegalDocument {
  kind: AdminLegalDocumentKind
  version: string
  title: string
  content: LegalDocumentContent
}

/** 历史版本行；`published` 为 false 的那一行就是当前草稿 */
export interface AdminLegalDocumentVersion {
  version: string
  title: string
  published: boolean
  publishedAt: string | null
  publishedBy: string | null
  createdAt: string
  updatedAt: string
}

/** 单份文档的管理端状态（后端 `KindState`）：已发布版本、可编辑草稿与历史版本 */
export interface AdminLegalDocumentState {
  kind: AdminLegalDocumentKind
  published: AdminLegalDocument | null
  draft: AdminLegalDocument | null
  history: AdminLegalDocumentVersion[]
}

/** 保存草稿与发布新版本的请求体：版本号只在发布时提交 */
export interface LegalDocumentPayload {
  title: string
  content: LegalDocumentContent
}

const LEGAL_DOCUMENT_BASE = '/console/platform/admin/legal-documents'

export async function getLegalDocuments() {
  return (await platformHttp.get<{ documents: AdminLegalDocumentState[] }>(LEGAL_DOCUMENT_BASE)).data
}

/** 保存草稿；首次保存时后端会用已发布正文自动建草稿 */
export async function saveLegalDocumentDraft(
  kind: AdminLegalDocumentKind,
  payload: LegalDocumentPayload,
) {
  return (await platformHttp.put<{ documents: AdminLegalDocumentState[] }>(
    `${LEGAL_DOCUMENT_BASE}/${encodeURIComponent(kind)}/draft`,
    payload,
  )).data
}

/** 发布新版本；版本号重复返回 409 `legal_document_version_exists` */
export async function publishLegalDocument(
  kind: AdminLegalDocumentKind,
  version: string,
  payload: LegalDocumentPayload,
) {
  return (await platformHttp.post<{ documents: AdminLegalDocumentState[] }>(
    `${LEGAL_DOCUMENT_BASE}/${encodeURIComponent(kind)}/publish`,
    { version, title: payload.title, content: payload.content },
  )).data
}

/** 把某个历史版本重新发布为当前版本（用于回滚） */
export async function restoreLegalDocumentVersion(
  kind: AdminLegalDocumentKind,
  version: string,
) {
  return (await platformHttp.post<{ documents: AdminLegalDocumentState[] }>(
    `${LEGAL_DOCUMENT_BASE}/${encodeURIComponent(kind)}/versions/${encodeURIComponent(version)}/restore`,
  )).data
}


export interface CreateSkillAdminPayload extends SkillAdminPayload {
  code: string
}

export type SkillPackageReplacementFields = Omit<SkillVersionUploadFields, 'version'>

/** A13 下载日志行 */
export interface SkillDownloadEntry {
  id: string
  skillId: string
  skillVersionId: string
  version: string
  userId: string | null
  apiKeyId: string | null
  deviceIdHash: string | null
  status: string
  servedBytes: number
  contentLength: number
  rangeFrom: number | null
  rangeTo: number | null
  md5Verified: boolean | null
  installed: boolean | null
  failureCode: string | null
  clientVersion: string | null
  clientPlatform: string | null
  startedAt: string
  completedAt: string | null
}

export interface SkillDownloadListParams {
  page: number
  size: number
}

export interface SkillDependencyView {
  key: string
  relation: 'SKILL_BINDING' | 'TEAM_MEMBER'
  required: boolean
  targetCode: string
  targetKind: SkillKind
  targetVersion: string
  targetRevision: number
  targetSha256: string
  targetVersionId: string
  targetSkillId: string
  /** 目标版本状态；目标版本已被删除时后端返回 `DELETED` */
  targetVersionStatus: SkillVersionStatus | 'DELETED'
  /** 目标版本是否是目标技能当前的线上版本 */
  targetOnline: boolean
}

export interface SkillVersionDependenciesView {
  skillId: string
  code: string
  kind: SkillKind
  versionId: string
  version: string
  revision: number
  sealed: boolean
  dependencies: SkillDependencyView[]
}

export interface SkillDependentEntry {
  ownerSkillId: string
  ownerCode: string
  ownerKind: SkillKind
  ownerVersionId: string
  ownerVersion: string
  ownerRevision: number
  ownerStatus: SkillAdminStatus
  ownerOnline: boolean
  key: string
  relation: 'SKILL_BINDING' | 'TEAM_MEMBER'
  required: boolean
  targetVersion: string
  targetRevision: number
}

export interface SkillDependentsView {
  skillId: string
  code: string
  dependents: SkillDependentEntry[]
}

export interface SkillRelationEdgeView {
  key: string
  relation: 'SKILL_BINDING' | 'TEAM_MEMBER'
  required: boolean
  code: string
  kind: 'SKILL' | 'EXPERT' | 'TEAM'
  version: string | null
  revision: number | null
  status: SkillAdminStatus | null
  skillId: string | null
}

export interface SkillRelationsView {
  skillId: string
  code: string
  kind: 'SKILL' | 'EXPERT' | 'TEAM'
  version?: string
  revision?: number
  dependsOn: SkillRelationEdgeView[]
  dependedOnBy: SkillDependentEntry[]
}

export interface SkillCodeHolderView {
  id: string
  code: string
  name: string | null
  kind: 'SKILL' | 'EXPERT' | 'TEAM'
  status: SkillAdminStatus
}

export interface SkillCodeAvailabilityView {
  code: string
  available: boolean
  occupiedBy?: SkillCodeHolderView | null
}

/**
 * A27。解析一个 YCPS 包，供「新增」表单预填。
 *
 * 严格只读：后端不会建记录、不落对象存储、不写审计。因此可以放心在用户刚选完文件时
 * 立刻调用，失败了也不留痕迹。
 *
 * `summary`/`description` 是同一个值（后端把一句话简介同时写进两列），
 * `summaryEn`/`descriptionEn` 同理 —— 服务端已经做完了这层扇出，
 * 前端逐字段照抄即可，不要自己再拼一次。
 *
 * `codeAvailable`/`occupiedBy` 与 A22 同义：编码冲突是包自己看不出来的唯一冲突，
 * 所以顺手在同一趟里问掉。
 */
export interface SkillPackageInspectView {
  code: string
  version: string
  kind: SkillKind
  minClientVersion?: string | null
  title?: string | null
  titleEn?: string | null
  summary?: string | null
  summaryEn?: string | null
  description?: string | null
  descriptionEn?: string | null
  usageGuide?: string | null
  usageGuideEn?: string | null
  taskTypes?: string[] | null
  taskTypesEn?: string[] | null
  examplePrompts?: string[] | null
  examplePromptsEn?: string[] | null
  examplePreview?: ExpertExamplePreview | null
  examplePreviewEn?: ExpertExamplePreview | null
  /** 从较旧的包检查器接受的旧标量 preview 字段。 */
  examplePreviewInput?: string | null
  examplePreviewInputEn?: string | null
  examplePreviewOutput?: string | null
  examplePreviewOutputEn?: string | null
  canvas?: ExpertLocalizedCanvasPayload | null
  /** YCPS 1.1 `expert.asset.exampleImage.locale`, when present. */
  exampleImageLocale?: ExpertImageLocale | null
  /** 包内 `plugin.json` 的 keywords，用作预填标签 */
  keywords: string[]
  fileCount: number
  extractedBytes: number
  sizeBytes: number
  sha256: string
  md5: string
  manifestSha256?: string | null
  codeAvailable: boolean
  occupiedBy?: SkillCodeHolderView | null
}

/** A27。上传一个 `.ycps.zip` 读取它的身份与文案，不创建任何记录 */
export async function inspectAdminSkillPackage(file: File) {
  const body = new FormData()
  body.append('file', file)
  return (await gatewayHttp.post<SkillPackageInspectView>(
    `${SKILL_ADMIN_BASE}/inspect-package`,
    body,
  )).data
}

export type SkillCategoryScope = 'SKILL' | 'EXPERT'

/** A23 一条分类。`name` 是写入 gateway.skill.category 的值，`usageCount` 是仍在用它的包数。 */
export interface SkillCategoryView {
  id: string
  kind: SkillCategoryScope
  code: string
  name: string
  nameEn: string | null
  sortOrder: number
  enabled: boolean
  usageCount: number
  /** 分类封面地址；未上传时为 null，页面回退到占位徽标 */
  imageUrl?: string | null
  /** 封面最后变更时间；桌面端据此判断本地缓存是否还有效 */
  imageUpdatedAt?: string | null
}

export interface SkillCategoryListView {
  kind: SkillCategoryScope
  categories: SkillCategoryView[]
}

export interface SkillCategoryPayload {
  kind: SkillCategoryScope
  name: string
  nameEn?: string | null
  code?: string | null
}

export interface SkillCategoryUpdatePayload {
  name: string
  nameEn?: string | null
}

export async function fetchSkillVersionDependencies(id: string, versionId: string) {
  return (await gatewayHttp.get<SkillVersionDependenciesView>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/versions/${encodeURIComponent(versionId)}/dependencies`,
  )).data
}

export async function fetchSkillDependents(id: string) {
  return (await gatewayHttp.get<SkillDependentsView>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/dependents`,
  )).data
}

export async function fetchSkillRelations(id: string) {
  return (await gatewayHttp.get<SkillRelationsView>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/relations`,
  )).data
}

export async function fetchSkillCodeAvailability(code: string) {
  return (await gatewayHttp.get<SkillCodeAvailabilityView>(
    `${SKILL_ADMIN_BASE}/code-availability`,
    { params: { code } },
  )).data
}

export async function listSkillCategories(kind: string) {
  return (await gatewayHttp.get<SkillCategoryListView>(
    `${SKILL_ADMIN_BASE}/categories`,
    { params: { kind } },
  )).data
}

export async function createSkillCategory(payload: SkillCategoryPayload) {
  return (await gatewayHttp.post<SkillCategoryView>(
    `${SKILL_ADMIN_BASE}/categories`,
    payload,
  )).data
}

export async function deleteSkillCategory(kind: string, code: string) {
  await gatewayHttp.delete(`${SKILL_ADMIN_BASE}/categories/${encodeURIComponent(code)}`, {
    params: { kind },
  })
}

export async function updateSkillCategory(
  kind: string,
  code: string,
  payload: SkillCategoryUpdatePayload,
) {
  return (await gatewayHttp.put<SkillCategoryView>(
    `${SKILL_ADMIN_BASE}/categories/${encodeURIComponent(code)}`,
    payload,
    { params: { kind } },
  )).data
}

export async function uploadSkillCategoryImage(kind: string, code: string, file: File) {
  const form = new FormData()
  form.append('file', file)
  return (await gatewayHttp.post<SkillCategoryView>(
    `${SKILL_ADMIN_BASE}/categories/${encodeURIComponent(code)}/image`,
    form,
    { params: { kind } },
  )).data
}

export async function fetchSkillCategoryImage(kind: string, code: string): Promise<Blob> {
  return (await gatewayHttp.get<Blob>(
    `${SKILL_ADMIN_BASE}/categories/${encodeURIComponent(code)}/image`,
    { params: { kind }, responseType: 'blob' },
  )).data
}

/**
 * 清空分类封面，回退到占位徽标。
 *
 * 幂等：本来就没有封面时也返回当前分类视图（不会 404），
 * 「清空」按钮对每个分类都可见，重复点击不该报错。
 */
export async function deleteSkillCategoryImage(kind: string, code: string) {
  return (await gatewayHttp.delete<SkillCategoryView>(
    `${SKILL_ADMIN_BASE}/categories/${encodeURIComponent(code)}/image`,
    { params: { kind } },
  )).data
}

export type PortableImportFieldAction = 'FILL_EMPTY' | 'OVERWRITE' | 'MERGE'
export type PortableImportImageAction = 'FILL_EMPTY' | 'OVERWRITE'

export interface PortableImportFieldDiff {
  present: boolean
  packageValue: unknown
  currentValue: unknown
  change: 'MISSING' | 'ADD' | 'UPDATE' | 'UNCHANGED'
  defaultAction: PortableImportFieldAction
  allowedActions: PortableImportFieldAction[]
}

export interface PortableImportImageDiff {
  present: boolean
  packageValue: unknown
  currentValue: unknown
  change: 'MISSING' | 'ADD' | 'UPDATE' | 'UNCHANGED'
  defaultAction: PortableImportImageAction
  allowedActions: PortableImportImageAction[]
}

export interface PortableImportResourceInventory {
  count: number
  totalBytes: number
  resources: Array<{
    role: string
    path: string
    mediaType: string
    sizeBytes: number
    sha256: string
  }>
}

export interface PortableImportResponse {
  importId: string
  expiresAt: string
  artifactSha256: string
  identity: { code: string; kind: SkillKind; version: string; revision: number }
  fields: Record<string, PortableImportFieldDiff>
  images: Record<string, PortableImportImageDiff>
  resources: PortableImportResourceInventory
  warnings: string[]
  status: string
  targetSkillId: string | null
  targetRowVersion: number | null
}

export interface PortableImportCommitRequest {
  skillId?: string | null
  expectedRowVersion?: number | null
  fields?: Record<string, PortableImportFieldAction>
  images?: Record<string, PortableImportImageAction>
}

export interface PortableImportCommitResponse {
  importId: string
  status: 'COMMITTED'
  skillId: string
  revision: number
  skill: SkillAdminDetail
  packageRevision: SkillVersionView
  fieldActions: Record<string, PortableImportFieldAction>
  imageActions: Record<string, PortableImportImageAction>
}

export async function uploadAdminPortableImport(file: File) {
  const body = new FormData()
  body.append('file', file)
  return (await gatewayHttp.post<PortableImportResponse>(
    '/console/gateway/admin/skill-portable-imports', body,
  )).data
}

export interface ClawhubConversionReport {
  sourceFormat: 'CLAWHUB_SKILL'
  adapterVersion: string
  sourceSha256: string
  code: string | null
  /** Server-normalized publisher handle used for the saved source tag. */
  owner?: string | null
  /** Publisher-authored name returned by the ClawHub adapter preview. */
  name: string | null
  slug?: string | null
  description?: string | null
  version: string | null
  status: 'IMPORTABLE' | 'REVIEW_REQUIRED' | 'BLOCKED'
  decisions: string[]
  blockingReasons: string[]
}

export interface ClawhubConversionPreview {
  report: ClawhubConversionReport
  packageBase64: string | null
}

export interface ClawhubSkillImportPayload {
  file: File
  owner: string
  slug: string
  version?: string | null
  /** ClawHub registry's publisher-authored skill.displayName, when available. */
  displayName?: string | null
  expectedSha256?: string
  /** Editable presentation fields saved in the same transaction as the native source. */
  metadata?: ClawhubSkillImportMetadata
}

export type ClawhubSkillImportMetadata = Pick<SkillAdminPayload,
  'name' | 'nameEn' | 'category' | 'tags' | 'summary' | 'summaryEn'
  | 'description' | 'descriptionEn' | 'minClientVersion' | 'sortWeight'> & {
    sourceKind?: 'CLAWHUB' | 'GITHUB'
    sourceUrl?: string
    sourceRevision?: string | null
  }

export interface ClawhubRemoteMetadata {
  sourceKind: 'CLAWHUB' | 'GITHUB'
  owner: string
  slug: string
  version: string | null
  displayName: string | null
  sourceUrl: string
  fileName: string
  sourceRevision?: string | null
}

export interface ClawhubRemoteFetchResult {
  file: File
  metadata: ClawhubRemoteMetadata
  sha256: string
}

function readRemoteErrorBlob(blob: Blob): Promise<string> {
  if (typeof blob.text === 'function') return blob.text()
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(reader.error)
    reader.readAsText(blob)
  })
}

/** Fetch a remote source on the server, then retain its exact ZIP for preview and commit. */
export async function fetchAdminRemoteClawhubSkill(
  url: string, signal?: AbortSignal,
): Promise<ClawhubRemoteFetchResult> {
  const response = await gatewayHttp.post<Blob>(
    '/console/gateway/admin/skill-source-imports/clawhub/remote/fetch',
    { url },
    { responseType: 'blob', signal },
  ).catch(async (error: unknown) => {
    // Axios also gives error responses as Blob when responseType is blob.
    // Decode the ordinary {code,message} envelope for the existing Chinese error mapper.
    const apiResponse = (error as { response?: { data?: unknown } })?.response
    if (apiResponse?.data instanceof Blob) {
      try {
        apiResponse.data = JSON.parse(await readRemoteErrorBlob(apiResponse.data)) as unknown
      } catch { /* Keep the original error when the body is not JSON. */ }
    }
    throw error
  })
  const encoded = response.headers['x-skill-remote-metadata']
  const sha256 = response.headers['x-skill-sha256']
  let metadata: ClawhubRemoteMetadata
  try {
    metadata = JSON.parse(decodeURIComponent(String(encoded ?? ''))) as ClawhubRemoteMetadata
  } catch {
    throw new Error('远程来源信息缺失，请重新拉取。')
  }
  if (!metadata || !['CLAWHUB', 'GITHUB'].includes(metadata.sourceKind)
    || typeof metadata.owner !== 'string' || !metadata.owner
    || typeof metadata.slug !== 'string' || !metadata.slug
    || typeof metadata.sourceUrl !== 'string' || !metadata.sourceUrl
    || typeof metadata.fileName !== 'string'
    || metadata.version != null && typeof metadata.version !== 'string'
    || metadata.displayName != null && typeof metadata.displayName !== 'string'
    || metadata.sourceRevision != null && typeof metadata.sourceRevision !== 'string'
    || !(response.data instanceof Blob)
    || typeof sha256 !== 'string' || !/^[a-f0-9]{64}$/iu.test(sha256)) {
    throw new Error('远程来源信息不完整，请重新拉取。')
  }
  const fileName = metadata.fileName?.split(/[\\/]/u).pop() || 'remote-skill.zip'
  return {
    file: new File([response.data], fileName, { type: 'application/zip' }),
    metadata,
    sha256: sha256.toLowerCase(),
  }
}

export interface ClawhubSkillImportCommitResponse {
  skillId: string
  sourceId: string
  code: string
  version: string | null
  status: 'DRAFT'
  packageFormat: 'CLAWHUB_SKILL'
  sourceSha256: string
  sizeBytes: number
  scanStatus: SkillScanStatus
  reviewRequired: boolean
}

/** One immutable native ClawHub source archive; independent from YCPS SkillVersionView rows. */
export interface NativeClawhubVersionView {
  id: string
  skillId: string
  owner: string
  slug: string
  version: string | null
  sourceSha256: string
  sizeBytes: number
  scanStatus: SkillScanStatus
  reviewRequired: boolean
  status: 'DRAFT' | 'PUBLISHED' | string
  createdAt: string
  publishedAt: string | null
  online: boolean
}

export interface NativeClawhubVersionList {
  items: NativeClawhubVersionView[]
  currentPublishedVersionId: string | null
}

export type ClawhubSkillImportPublishResponse = SkillAdminDetail

function clawhubSkillImportBody(payload: ClawhubSkillImportPayload) {
  const body = new FormData()
  body.append('file', payload.file)
  body.append('owner', payload.owner)
  body.append('slug', payload.slug)
  if (payload.version) body.append('version', payload.version)
  if (payload.displayName) body.append('displayName', payload.displayName)
  if (payload.expectedSha256) body.append('expectedSha256', payload.expectedSha256)
  if (payload.metadata) body.append('metadata', JSON.stringify(payload.metadata))
  return body
}

export async function previewAdminClawhubSkill(
  file: File, owner: string, slug: string, version?: string | null, displayName?: string | null,
) {
  return (await gatewayHttp.post<ClawhubConversionPreview>(
    '/console/gateway/admin/skill-source-imports/clawhub/preview',
    clawhubSkillImportBody({ file, owner, slug, version, displayName }),
  )).data
}

export async function commitAdminClawhubSkill(payload: ClawhubSkillImportPayload) {
  return (await gatewayHttp.post<ClawhubSkillImportCommitResponse>(
    '/console/gateway/admin/skill-source-imports/clawhub/commit',
    clawhubSkillImportBody(payload),
  )).data
}

/** Explicit, separately-reviewed publication path for native ClawHub source records. */
export async function publishAdminClawhubSkill(skillId: string) {
  return (await gatewayHttp.post<ClawhubSkillImportPublishResponse>(
    `/console/gateway/admin/skill-source-imports/clawhub/${encodeURIComponent(skillId)}/publish`,
  )).data
}

/** Complete a missing source-level ClawHub version without creating or publishing a YCPS version. */
export async function updateAdminClawhubSourceVersion(skillId: string, version: string) {
  await gatewayHttp.put(
    `/console/gateway/admin/skill-source-imports/clawhub/${encodeURIComponent(skillId)}/version`,
    { version },
  )
}

/** Retry a pending native source scan without publishing or replacing its ZIP. */
export async function rescanAdminClawhubSkill(skillId: string) {
  return (await gatewayHttp.post<ClawhubSkillImportCommitResponse>(
    `/console/gateway/admin/skill-source-imports/clawhub/${encodeURIComponent(skillId)}/rescan`,
  )).data
}

const CLAWHUB_SKILL_IMPORT_BASE = '/console/gateway/admin/skill-source-imports/clawhub'

/** List original ClawHub source archives, separate from the ordinary YCPS version list. */
export async function listAdminClawhubVersions(skillId: string) {
  return (await gatewayHttp.get<NativeClawhubVersionList>(
    `${CLAWHUB_SKILL_IMPORT_BASE}/${encodeURIComponent(skillId)}/versions`,
  )).data
}

export interface NativeClawhubVersionUploadPayload {
  file: File
  version?: string | null
  expectedSha256?: string
}

/** Upload a new immutable original ClawHub ZIP. The server revalidates and scans it. */
export async function uploadAdminClawhubVersion(
  skillId: string,
  payload: NativeClawhubVersionUploadPayload,
  onProgress?: (progress: UploadProgress) => void,
) {
  const body = new FormData()
  body.append('file', payload.file)
  if (payload.version) body.append('version', payload.version)
  if (payload.expectedSha256) body.append('expectedSha256', payload.expectedSha256)
  return (await gatewayHttp.post<NativeClawhubVersionView>(
    `${CLAWHUB_SKILL_IMPORT_BASE}/${encodeURIComponent(skillId)}/versions`,
    body,
    {
      onUploadProgress: ({ loaded, total }) => {
        onProgress?.({ phase: 'uploading', percent: skillUploadPercent(loaded, total) })
      },
    },
  )).data
}

/** Download the exact original ClawHub ZIP; draft archives are exportable too. */
export async function downloadAdminClawhubVersion(skillId: string, versionId: string) {
  const response = await gatewayHttp.get<Blob>(
    `${CLAWHUB_SKILL_IMPORT_BASE}/${encodeURIComponent(skillId)}/versions/${encodeURIComponent(versionId)}/download`,
    { responseType: 'blob' },
  )
  return {
    blob: response.data,
    fileName: contentDispositionFileName(response.headers['content-disposition']) ?? '',
  }
}

/** Promote or roll back to one eligible native ClawHub source archive. */
export async function publishAdminClawhubVersion(skillId: string, versionId: string) {
  return (await gatewayHttp.post<unknown>(
    `${CLAWHUB_SKILL_IMPORT_BASE}/${encodeURIComponent(skillId)}/versions/${encodeURIComponent(versionId)}/publish`,
  )).data
}

/** Retry malware scanning for a pending or failed ClawHub archive only. */
export async function rescanAdminClawhubVersion(skillId: string, versionId: string) {
  return (await gatewayHttp.post<NativeClawhubVersionView>(
    `${CLAWHUB_SKILL_IMPORT_BASE}/${encodeURIComponent(skillId)}/versions/${encodeURIComponent(versionId)}/rescan`,
  )).data
}

/** Delete only a never-published native ClawHub draft. */
export async function deleteAdminClawhubVersion(skillId: string, versionId: string) {
  await gatewayHttp.delete(
    `${CLAWHUB_SKILL_IMPORT_BASE}/${encodeURIComponent(skillId)}/versions/${encodeURIComponent(versionId)}`,
  )
}

export async function commitAdminPortableImport(
  importId: string,
  payload: PortableImportCommitRequest,
) {
  return (await gatewayHttp.post<PortableImportCommitResponse>(
    `/console/gateway/admin/skill-portable-imports/${encodeURIComponent(importId)}/commit`,
    payload,
  )).data
}

/** 依据当前档案生成一个 READY 的 YCPS 1.1 专家展示修订版本。 */
export async function generateAdminSkillPortableRevision(id: string, versionId: string) {
  return (await gatewayHttp.post<SkillVersionView>(
    `${SKILL_ADMIN_BASE}/${encodeURIComponent(id)}/versions/${encodeURIComponent(versionId)}/portable-revisions`,
  )).data
}
