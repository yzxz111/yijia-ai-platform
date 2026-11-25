import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import GrantPackagesView from '../../src/views/admin/GrantPackagesView.vue'
import AdminOverviewView from '../../src/views/admin/AdminOverviewView.vue'
import AuditView from '../../src/views/admin/AuditView.vue'
import PaymentAnomalyPanel from '../../src/components/admin/PaymentAnomalyPanel.vue'
import ReconciliationPanel from '../../src/components/admin/ReconciliationPanel.vue'
import RefundPolicyPanel from '../../src/components/admin/RefundPolicyPanel.vue'
import RefundReviewPanel from '../../src/components/admin/RefundReviewPanel.vue'
import PlansAdminView from '../../src/views/admin/PlansAdminView.vue'
import ModelsView from '../../src/views/admin/ModelsView.vue'
import OrdersPaymentsView from '../../src/views/admin/OrdersPaymentsView.vue'
import OperationsView from '../../src/views/admin/OperationsView.vue'
import UsageQuotaView from '../../src/views/admin/UsageQuotaView.vue'
import UsersInvitationsView from '../../src/views/admin/UsersInvitationsView.vue'
import { useAuthStore } from '../../src/stores/auth'

vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRoute: () => ({ query: {} }),
}))

vi.mock('../../src/components/charts/AppChart.vue', () => ({
  default: { template: '<div />' },
}))

const api = vi.hoisted(() => ({
  approveRefund: vi.fn(),
  businessNo: vi.fn(),
  cleanupFiles: vi.fn(),
  createGrant: vi.fn(),
  createPackagePlan: vi.fn(),
  createPlanVersion: vi.fn(),
  createModel: vi.fn(),
  createProvider: vi.fn(),
  createChannel: vi.fn(),
  getAdminOverview: vi.fn(),
  getAdminRefund: vi.fn(),
  getGatewayAdminAnalytics: vi.fn(),
  getGatewayHealth: vi.fn(),
  getOperatingAnalytics: vi.fn(),
  getOutboxHealth: vi.fn(),
  getReconciliationRun: vi.fn(),
  getRoutingHealth: vi.fn(),
  listRoutingTargets: vi.fn(),
  listGrants: vi.fn(),
  listAdminPlans: vi.fn(),
  listPackagePlanCatalog: vi.fn(),
  listAdminUsers: vi.fn(),
  listAudit: vi.fn(),
  listChannels: vi.fn(),
  listModels: vi.fn(),
  listProviderTypeCapabilities: vi.fn(),
  onboardChannelRoutes: vi.fn(),
  probeUpstreamModels: vi.fn(),
  listAdminOrders: vi.fn(),
  listAdminUsage: vi.fn(),
  listPaymentConfigs: vi.fn(),
  listPaymentAnomalies: vi.fn(),
  listPayments: vi.fn(),
  listProviders: vi.fn(),
  listProviderTypeCapabilities: vi.fn(),
  listOutbox: vi.fn(),
  listQuotaLedger: vi.fn(),
  listRefunds: vi.fn(),
  listRefundPolicies: vi.fn(),
  listReservations: vi.fn(),
  listSmsDeliveries: vi.fn(),
  listSmsTemplates: vi.fn(),
  publishPlanVersion: vi.fn(),
  publishRefundPolicy: vi.fn(),
  recoverReservations: vi.fn(),
  rejectRefund: vi.fn(),
  resolveAdminUserNumbers: vi.fn(),
  retryOutbox: vi.fn(),
  retrySmsDelivery: vi.fn(),
  restoreAutomaticRouting: vi.fn(),
  saveModelConfiguration: vi.fn(),
  simulatePayment: vi.fn(),
  startReconciliation: vi.fn(),
  testChannel: vi.fn(),
  updateChannel: vi.fn(),
  updateAdminUser: vi.fn(),
  updateModel: vi.fn(),
  updatePaymentConfig: vi.fn(),
  updatePaymentAnomaly: vi.fn(),
  switchRoutingCircuit: vi.fn(),
}))

const walletApi = vi.hoisted(() => ({
  listAdminWalletAccounts: vi.fn(),
  listAdminWalletLedger: vi.fn(),
  listAdminWalletRecharges: vi.fn(),
  listAdminWalletRechargeOptions: vi.fn(),
  replaceAdminWalletRechargeOptions: vi.fn(),
}))

const platformApi = vi.hoisted(() => ({
  listPlans: vi.fn(),
}))

vi.mock('../../src/api/wallet', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/api/wallet')>()),
  ...walletApi,
}))

vi.mock('../../src/api/platform', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/api/platform')>()),
  ...platformApi,
}))

const adminViewComponents = import.meta.glob('../../src/views/admin/*.vue')
const adminViewSources = import.meta.glob('../../src/views/admin/*.vue', { import: 'default', query: '?raw' })

async function loadWalletAdminView() {
  const loader = adminViewComponents['../../src/views/admin/WalletAdminView.vue']
  expect(loader).toBeTypeOf('function')
  if (!loader) throw new Error('WalletAdminView loader missing')
  return (await loader() as { default: object }).default
}

async function loadWalletAdminSource() {
  const loader = adminViewSources['../../src/views/admin/WalletAdminView.vue']
  expect(loader).toBeTypeOf('function')
  if (!loader) throw new Error('WalletAdminView source loader missing')
  return await loader() as string
}

vi.mock('../../src/api/admin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/api/admin')>()),
  ...api,
}))

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

describe('admin workflows', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    useAuthStore().setSession('admin-token', {
      id: 'admin-1', tenantId: 'admin-1', email: 'admin@example.com', role: 'ADMIN',
    })
    api.listAdminUsers.mockResolvedValue({
      items: [
        { id: 'admin-1', tenantId: 'admin-1', userNo: 'U00000000', email: 'admin@example.com', displayName: '管理员', status: 'ACTIVE', role: 'ADMIN', globalAdmin: true, statusManageable: false, roleManageable: false },
        { id: 'user-1', tenantId: 'tenant-1', userNo: 'U00000001', email: 'user@example.com', displayName: '测试用户', status: 'ACTIVE', role: 'USER', globalAdmin: false, statusManageable: true, roleManageable: true },
      ],
      total: 1,
      page: 0,
      size: 100,
    })
    api.resolveAdminUserNumbers.mockImplementation((ids: string[]) => Promise.resolve(ids.map((userId, index) => ({
      userId,
      userNo: userId === 'user-1' ? 'U00000001' : `U${String(index + 2).padStart(8, '0')}`,
    }))))
    api.listAdminPlans.mockResolvedValue({
      items: [{
        versionId: 'plan-version-1', code: 'STARTER', name: '入门版', description: '', planStatus: 'ACTIVE',
        version: 1, status: 'PUBLISHED', priceAmount: 9900, currency: 'CNY', totalTokenQuota: 1_000_000,
        durationDays: 30, maxApiKeys: 2, requestsPerMinute: 60, tokensPerMinute: 100_000,
        maxConcurrency: 5, estimatedCostAmount: 3000, estimatedMarginRatio: 0.69, marginWarning: false,
        modelCount: 1, publishedAt: '2026-07-01T00:00:00Z', createdAt: '2026-06-30T00:00:00Z',
      }],
      total: 1, page: 0, size: 20,
    })
    api.listPackagePlanCatalog.mockResolvedValue({
      items: [{
        code: 'STARTER', name: '入门版', description: '适合个人开发者', kind: 'STANDARD',
        status: 'ACTIVE', displayOrder: 20,
        createdAt: '2026-06-01T00:00:00Z', updatedAt: '2026-07-01T00:00:00Z',
        versions: [{
          versionId: 'plan-version-1', version: 1, status: 'PUBLISHED', priceAmount: 9900,
          currency: 'CNY', totalTokenQuota: 1_000_000, durationDays: 30, maxApiKeys: 2,
          requestsPerMinute: 60, tokensPerMinute: 100_000, maxConcurrency: 5,
          dailyRequestLimit: null, maxOutputTokens: 4096, maxFileSizeBytes: 20 * 1024 * 1024,
          oncePerUser: false, estimatedCostAmount: 3000, estimatedMarginRatio: 0.69,
          marginWarning: false, stockTotal: null, stockSold: 2, stockReserved: 0,
          lowStockThreshold: 0, maxPurchasesPerUser: null, remainingStock: null,
          soldOut: false, lowStock: false, modelCount: 1,
          publishedAt: '2026-07-01T00:00:00Z', retiredAt: null,
          createdAt: '2026-06-30T00:00:00Z',
        }],
      }],
      total: 1, page: 0, size: 10,
    })
    platformApi.listPlans.mockResolvedValue([])
    api.createGrant.mockResolvedValue({ id: 'grant-1', eventId: 'event-1', businessNo: 'grant-result', status: 'QUEUED' })
    api.listGrants.mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
    api.listChannels.mockResolvedValue({
      items: [{
        id: 'channel-1', providerId: 'provider-1', code: 'bailian-primary', name: '百炼主渠道',
        environment: 'PRODUCTION', baseUrl: 'https://example.invalid', enabled: true,
        priority: 100, weight: 100, timeoutMs: 60_000, requestsPerMinute: 300,
        maxConcurrency: 50, lastHealthStatus: 'UP', credentialConfigured: true,
        apiKey: 'provider-plain-secret', secret: 'provider-plain-secret',
      }],
      total: 1, page: 0, size: 20,
    })
    api.listProviders.mockResolvedValue([{ id: 'provider-1', code: 'bailian', name: 'Alibaba Bailian', providerType: 'BAILIAN', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', enabled: true }])
    // 模型抽屉挂载时会取一次服务商类型能力目录；没 mock 就会真的发请求。
    api.listProviderTypeCapabilities.mockResolvedValue([
      { providerType: 'BAILIAN', chatCapabilities: ['TEXT', 'FILE', 'IMAGE'], imageGeneration: true, videoGeneration: true },
      { providerType: 'DEEPSEEK', chatCapabilities: ['TEXT'], imageGeneration: false, videoGeneration: false },
      { providerType: 'MOCK', chatCapabilities: ['TEXT', 'FILE', 'IMAGE'], imageGeneration: false, videoGeneration: false },
      { providerType: 'TIANYIYUN', chatCapabilities: [], imageGeneration: true, videoGeneration: true },
    ])
    api.createProvider.mockResolvedValue({ id: 'provider-new', code: 'openai-compatible', name: 'OpenAI 兼容服务', providerType: 'DEEPSEEK', baseUrl: 'https://provider.example.com/v1', enabled: true })
    api.testChannel.mockResolvedValue({ channelId: 'channel-1', configured: true, status: 'HEALTHY' })
    api.updateAdminUser.mockResolvedValue({
      id: 'user-1', email: 'user@example.com', displayName: '测试用户', status: 'DISABLED', role: 'USER',
      globalAdmin: false, statusManageable: true, roleManageable: true,
    })
    api.getAdminOverview.mockResolvedValue({
      users: 12, activeUsers: 10, grants: 8, outboxAttention: 1,
      orders: 5, paidOrders: 3, paidAmount: 9900,
    })
    api.getGatewayHealth.mockResolvedValue({
      status: 'UP', database: 'UP', expiredReservations: 2, cleanupCandidates: 4, circuits: [],
    })
    walletApi.listAdminWalletAccounts.mockResolvedValue({
      items: [{ id: 'account-1', userId: 'user-1', userNo: 'U00000001', email: 'user@example.com', displayName: '测试用户', currency: 'CNY', availableAmount: 12_345, version: 1, createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:01:00Z' }],
      total: 1, page: 0, size: 20,
    })
    walletApi.listAdminWalletRecharges.mockResolvedValue({
      items: [{ rechargeNo: 'R-001', userId: 'user-1', userNo: 'U00000001', email: 'user@example.com', optionId: null, paidAmount: 10_000, bonusAmount: 0, creditedAmount: 10_000, currency: 'CNY', status: 'PAID', expiresAt: '2026-08-05T00:15:00Z', paidAt: '2026-08-05T00:01:00Z', closedAt: null, createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:01:00Z' }],
      total: 1, page: 0, size: 20,
    })
    walletApi.listAdminWalletLedger.mockResolvedValue({
      items: [{ entryNo: 'L-001', userId: 'user-1', userNo: 'U00000001', email: 'user@example.com', entryType: 'RECHARGE', amountDelta: 10_000, balanceAfter: 12_345, businessNo: 'R-001', description: '充值到账', createdAt: '2026-08-05T00:01:00Z' }],
      total: 1, page: 0, size: 20,
    })
    walletApi.listAdminWalletRechargeOptions.mockResolvedValue([
      { id: 'option-10', amount: 1_000, enabled: true, displayOrder: 1, createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z' },
      { id: 'option-30', amount: 3_000, enabled: true, displayOrder: 2, createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z' },
    ])
    walletApi.replaceAdminWalletRechargeOptions.mockImplementation((options) => Promise.resolve(options.map((option: { amount: number; enabled: boolean; displayOrder: number }, index: number) => ({
      ...option, id: `saved-${index}`, createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z',
    }))))
    api.getOperatingAnalytics.mockResolvedValue({
      timezone: 'Asia/Shanghai',
      months: 6,
      summary: {
        registeredUsers: 12, activeUsers: 10, currentMonthCollectedAmount: 9900,
        currentMonthRefundedAmount: 0, currentMonthNetAmount: 9900, paidOrders: 3,
      },
      monthly: [],
    })
    api.getGatewayAdminAnalytics.mockResolvedValue({
      range: { days: 30, timezone: 'Asia/Shanghai', from: '2026-07-04', to: '2026-08-02' },
      summary: {
        todayRequests: 2, todaySuccessfulRequests: 2, todayFailedRequests: 0,
        todayTokens: 300, periodRequests: 2, periodTokens: 300, platformAddedP95Ms: 90,
      },
      daily: [], models: [], packages: [],
    })
    api.listModels.mockResolvedValue({ items: [
      { id: 'model-1', code: 'qwen-plus', displayName: 'Qwen Plus', aliases: ['qwen-plus'], capabilities: ['TEXT'], routingStrategy: 'DIRECT', contextWindow: 32768, maxOutputTokens: 4096, premium: false, enabled: true, requestsPerMinute: 60, maxConcurrency: 5, routes: [
        { componentModelCode: 'qwen-plus', componentDisplayName: 'Qwen Plus', providerId: 'provider-bailian', providerCode: 'bailian', providerName: '阿里云百炼', channelId: 'channel-bailian', channelCode: 'bailian-primary', channelName: '百炼主渠道', upstreamModel: 'qwen-plus', environment: 'PRODUCTION', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', enabled: true, healthStatus: 'HEALTHY', credentialConfigured: true, priority: 100, weight: 100, timeoutMs: 60_000, requestsPerMinute: 300, maxConcurrency: 50, eligible: true },
      ] },
      { id: 'model-composite', code: 'multimodal-basic', displayName: '多模态基础版', aliases: ['multimodal-basic'], capabilities: ['TEXT', 'IMAGE', 'FILE'], routingStrategy: 'COMPOSITE', contextWindow: 32768, maxOutputTokens: 4096, premium: false, enabled: true, requestsPerMinute: 60, maxConcurrency: 5, routes: [
        { componentModelCode: 'text-basic', componentDisplayName: '基础文本', providerId: 'provider-deepseek', providerCode: 'deepseek', providerName: 'DeepSeek', channelId: 'channel-deepseek', channelCode: 'deepseek-primary', channelName: 'DeepSeek 主渠道', upstreamModel: 'deepseek-chat', environment: 'PRODUCTION', baseUrl: 'https://api.deepseek.com/v1', enabled: true, healthStatus: 'HEALTHY', credentialConfigured: true, priority: 100, weight: 100, timeoutMs: 60_000, requestsPerMinute: 300, maxConcurrency: 50, eligible: true },
        { componentModelCode: 'text-basic', componentDisplayName: '基础文本', providerId: 'provider-bailian', providerCode: 'bailian', providerName: '阿里云百炼', channelId: 'channel-bailian-text', channelCode: 'bailian-text-backup', channelName: '百炼文本备用渠道', upstreamModel: 'deepseek-v4', environment: 'PRODUCTION', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', enabled: true, healthStatus: 'HEALTHY', credentialConfigured: true, priority: 80, weight: 50, timeoutMs: 60_000, requestsPerMinute: 300, maxConcurrency: 50, eligible: true },
        { componentModelCode: 'vision-basic', componentDisplayName: '基础视觉', providerId: 'provider-bailian', providerCode: 'bailian', providerName: '阿里云百炼', channelId: 'channel-bailian-vision', channelCode: 'bailian-vision', channelName: '百炼视觉渠道', upstreamModel: 'qwen-vl-plus', environment: 'PRODUCTION', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', enabled: true, healthStatus: 'HEALTHY', credentialConfigured: true, priority: 100, weight: 100, timeoutMs: 60_000, requestsPerMinute: 300, maxConcurrency: 50, eligible: true },
        { componentModelCode: 'file-basic', componentDisplayName: '基础文件', providerId: 'provider-bailian', providerCode: 'bailian', providerName: '阿里云百炼', channelId: 'channel-bailian-file', channelCode: 'bailian-file', channelName: '百炼文件渠道', upstreamModel: 'qwen-long', environment: 'PRODUCTION', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', enabled: true, healthStatus: 'HEALTHY', credentialConfigured: true, priority: 100, weight: 100, timeoutMs: 60_000, requestsPerMinute: 300, maxConcurrency: 50, eligible: true },
      ] },
    ], total: 2, page: 0, size: 20 })
    api.createModel.mockResolvedValue({ id: 'model-2', code: 'new-model' })
    api.updateModel.mockResolvedValue({ id: 'model-1', code: 'qwen-plus' })
    api.saveModelConfiguration.mockResolvedValue({ id: 'model-1', code: 'qwen-plus' })
    api.createPlanVersion.mockResolvedValue({ code: 'STARTER', version: 2, status: 'DRAFT' })
    api.createPackagePlan.mockResolvedValue({
      code: 'PKG_NEW', name: '开发者版', description: null, kind: 'STANDARD',
      status: 'ACTIVE', displayOrder: 50,
    })
    api.publishPlanVersion.mockResolvedValue({ code: 'STARTER', version: 1, status: 'PUBLISHED' })
    api.listAdminUsage.mockResolvedValue({ items: [
      { id: 'usage-1', requestId: 'req-1', modelCode: 'qwen-plus', inputTokens: 20, outputTokens: 40, totalTokens: 60, usageEstimated: true, durationMs: 300, httpStatus: 200, outcome: 'SUCCEEDED', createdAt: '2026-07-31T00:00:00Z', stages: [] },
      { id: 'usage-composite', requestId: 'req-composite', modelCode: 'multimodal-basic', inputTokens: 100, outputTokens: 25, totalTokens: 125, usageEstimated: false, durationMs: 520, httpStatus: 200, outcome: 'SUCCEEDED', createdAt: '2026-07-31T00:01:00Z', stages: [{ stageIndex: 0, stageType: 'FINAL_RESPONSE', logicalModelCode: 'vision-basic', providerCode: 'bailian', channelCode: 'bailian-primary', upstreamModel: 'qwen-vl-plus', inputTokens: 100, outputTokens: 25, totalTokens: 125, durationMs: 480, outcome: 'SUCCEEDED', providerCostAmount: 7, providerCostCurrency: 'CNY', priceEstimated: false }] },
    ], total: 2, page: 0, size: 20 })
    api.listReservations.mockResolvedValue({ items: [{ id: 'reservation-1', requestId: 'req-1', userId: 'user-1', modelCode: 'qwen-plus', reservedTokens: 100, actualTotalTokens: 60, uncoveredTokens: 17, usageEstimated: true, status: 'SETTLED', expiresAt: '2026-07-31T00:05:00Z', createdAt: '2026-07-31T00:00:00Z' }], total: 1, page: 0, size: 20 })
    api.listQuotaLedger.mockResolvedValue({ items: [{ id: 'ledger-1', requestId: 'req-1', userId: 'user-1', modelCode: 'qwen-plus', entryType: 'SETTLE', totalTokenDelta: -60, balanceAfter: 940, createdAt: '2026-07-31T00:00:00Z' }], total: 1, page: 0, size: 20 })
    api.listAdminOrders.mockResolvedValue({ items: [{ totalTokensUsed: 0, cumulativeSpendAmount: 0, id: 'order-1', orderNo: 'ORD-20260731-001', userId: 'user-1', userNo: 'U00000001', userEmail: 'user@example.com', packageCode: 'STARTER', packageName: '入门版', packageVersion: 1, amount: 9900, currency: 'CNY', status: 'PAID', paymentProvider: 'ALIPAY', paymentStatus: 'SUCCEEDED', refundStatus: null, paidAt: '2026-07-31T00:01:00Z', createdAt: '2026-07-31T00:00:00Z', updatedAt: '2026-07-31T00:01:00Z' }], total: 1, page: 0, size: 20 })
    api.listPayments.mockResolvedValue({ items: [{ id: 'payment-1', transactionNo: 'PAY-20260731-001', orderId: 'order-1', orderNo: 'ORD-20260731-001', userId: 'user-1', userEmail: 'user@example.com', provider: 'ALIPAY', externalTradeNo: 'ALI-001', amount: 9900, status: 'SUCCEEDED', paidAt: '2026-07-31T00:01:00Z', createdAt: '2026-07-31T00:00:30Z', updatedAt: '2026-07-31T00:01:00Z' }], total: 1, page: 0, size: 20 })
    api.listRefunds.mockResolvedValue({ items: [{ id: 'refund-1', refundNo: 'REF-20260731-001', orderId: 'order-1', orderNo: 'ORD-20260731-001', userId: 'user-1', userNo: 'U00000001', userEmail: 'user@example.com', paymentTransactionId: 'payment-1', externalRefundNo: null, amount: 7425, status: 'PENDING_APPROVAL', reason: '套餐调整', completedAt: null, createdAt: '2026-07-31T00:04:00Z', updatedAt: '2026-07-31T00:05:00Z' }], total: 1, page: 0, size: 20 })
    const refundDetail = {
      refundNo: 'REF-20260731-001', orderNo: 'ORD-20260731-001', status: 'PENDING_APPROVAL',
      refundType: 'PARTIAL', amount: 7425, reason: '套餐调整', reviewedBy: null, reviewedAt: null,
      reviewNote: null, failureReason: null, createdAt: '2026-07-31T00:04:00Z', updatedAt: '2026-07-31T00:05:00Z',
      calculation: {
        paidAmount: 9900, paidAt: '2026-07-30T00:00:00Z', requestedAt: '2026-07-31T00:04:00Z',
        totalTokens: 10000, usedTokens: 2000, remainingTokens: 8000, feeBps: 500,
        minimumFee: 100, maximumUsageBps: 1000, refundWindowDays: 7, baseRefund: 7920,
        fee: 495, refundAmount: 7425, refundType: 'PARTIAL', rounding: 'BASE_FLOOR_FEE_HALF_UP',
        policyVersion: 1, calculatedAt: '2026-07-31T00:04:01Z', packageInstanceId: 'package-1',
        originalPackageStatus: 'ACTIVE', packageExpiresAt: '2026-08-30T00:00:00Z',
        gatewaySnapshotAt: '2026-07-31T00:04:00Z',
      },
    }
    api.getAdminRefund.mockResolvedValue(refundDetail)
    api.approveRefund.mockResolvedValue({ ...refundDetail, status: 'SUBMITTING', reviewedBy: 'admin-1', reviewedAt: '2026-07-31T00:06:00Z' })
    api.rejectRefund.mockResolvedValue({ ...refundDetail, status: 'REJECTED', reviewedBy: 'admin-1', reviewedAt: '2026-07-31T00:06:00Z', reviewNote: '不符合退款政策' })
    api.listPaymentConfigs.mockResolvedValue([{
      provider: 'ALIPAY', environment: 'SANDBOX', enabled: false, configured: true,
      credentialFingerprint: 'sha256:abc123', lastValidationStatus: 'VALID',
      lastValidatedAt: '2026-07-31T00:00:00Z', updatedAt: '2026-07-31T00:00:00Z',
      privateKey: 'must-not-render', credential: 'must-not-render',
    }])
    api.updatePaymentConfig.mockResolvedValue({
      provider: 'ALIPAY', environment: 'SANDBOX', enabled: true, configured: true,
      credentialFingerprint: 'sha256:abc123', lastValidationStatus: 'VALID',
      lastValidatedAt: '2026-07-31T00:00:00Z', updatedAt: '2026-07-31T00:10:00Z',
    })
    const anomaly = {
      id: 'anomaly-1', fingerprint: 'fingerprint-1', anomalyType: 'PAYMENT_AMOUNT_MISMATCH',
      provider: 'ALIPAY', orderId: 'order-1', paymentTransactionId: 'payment-1', refundId: null,
      status: 'OPEN', assignee: null, detail: { expectedAmount: 9900, actualAmount: 9800 },
      resolutionNote: null, resolvedAt: null, createdAt: '2026-07-31T00:00:00Z',
      updatedAt: '2026-07-31T00:00:00Z', lockVersion: 0,
    }
    api.listPaymentAnomalies.mockResolvedValue({ items: [anomaly], total: 1, page: 0, size: 20 })
    api.updatePaymentAnomaly.mockImplementation((_id: string, command: { status: string; assignee: string | null; resolutionNote: string | null }) => Promise.resolve({
      ...anomaly, ...command, lockVersion: 1,
    }))
    const run = {
      id: 'run-1', provider: 'WECHAT_PAY', billDate: '2026-07-31', status: 'COMPLETED',
      billSha256: 'sha256-safe', summary: { totalRecords: 10, matchedCount: 8, differenceCount: 2, autoResolvedCount: 1, openCount: 1, differenceCounts: { AMOUNT_MISMATCH: 1 } },
      startedAt: '2026-08-01T00:00:00Z', completedAt: '2026-08-01T00:01:00Z', createdAt: '2026-08-01T00:00:00Z',
      items: { items: [{ id: 'item-1', providerRecordId: 'provider-record-1', transactionNo: 'txn-1', refundNo: null, differenceType: 'AMOUNT_MISMATCH', status: 'OPEN', providerAmount: 9800, platformAmount: 9900, detail: { expectedAmount: 9900 }, resolvedBy: null, resolutionNote: null, resolvedAt: null, createdAt: '2026-08-01T00:00:01Z' }], total: 1, page: 0, size: 20 },
      rawBill: 'must-not-render',
    }
    api.startReconciliation.mockResolvedValue(run)
    api.getReconciliationRun.mockResolvedValue(run)
    api.listRefundPolicies.mockResolvedValue({
      items: [{
        version: 1, feeBps: 500, minimumFee: 100, refundWindowDays: 7, maximumUsageBps: 1000,
        effectiveAt: '2026-08-01T00:00:00Z', published: true, createdBy: 'admin-1', createdAt: '2026-07-31T00:00:00Z',
      }],
      total: 1, page: 0, size: 20,
    })
    api.publishRefundPolicy.mockResolvedValue({
      version: 2, feeBps: 400, minimumFee: 100, refundWindowDays: 14, maximumUsageBps: 1200,
      effectiveAt: '2026-08-03T00:00:00Z', published: true, createdBy: 'admin-1', createdAt: '2026-08-02T00:00:00Z',
    })
    api.simulatePayment.mockResolvedValue({ orderNo: 'ORD-MOCK-001', status: 'PAID', externalTradeNo: 'admin-sim-1' })
    api.businessNo.mockImplementation((prefix: string) => `${prefix}-visible-1`)
    api.getOutboxHealth.mockResolvedValue({ pending: 2, delivering: 1, failed: 1, oldestSeconds: 120 })
    api.getRoutingHealth.mockResolvedValue({ channels: [{ id: 'channel-1', code: 'bailian-primary', name: '百炼主渠道', lastHealthStatus: 'UP', enabled: true }], circuits: [{ key: 'bailian-primary', state: 'CLOSED', failureRate: 0, bufferedCalls: 1 }] })
    api.listOutbox.mockResolvedValue({ items: [{ id: 'outbox-1', aggregateType: 'GRANT', aggregateId: 'grant-1', eventType: 'PACKAGE_GRANTED', businessKey: 'grant-business-1', status: 'FAILED', attemptCount: 3, nextAttemptAt: '2026-07-31T00:05:00Z', deliveredAt: null, lastError: 'gateway timeout', createdAt: '2026-07-31T00:00:00Z' }], total: 1, page: 0, size: 20 })
    api.recoverReservations.mockResolvedValue({ operation: 'recover-reservations', status: 'COMPLETED', affected: 2 })
    api.cleanupFiles.mockResolvedValue({ operation: 'cleanup-files', status: 'COMPLETED', affected: 4 })
    api.retryOutbox.mockResolvedValue({ id: 'outbox-1', status: 'PENDING' })
    api.listSmsTemplates.mockResolvedValue([
      {
        templateKey: 'ORDER_PAYMENT_FAILED', templateCode: 'SMS_511295121',
        templateType: 'NOTIFICATION', integrationStatus: 'ACTIVE',
        createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
      },
      {
        templateKey: 'LOGIN_CODE', templateCode: 'SMS_511430077',
        templateType: 'VERIFICATION', integrationStatus: 'PENDING_INTEGRATION',
        createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
      },
    ])
    api.listSmsDeliveries.mockResolvedValue({
      items: [
        {
          id: 'sms-failed', templateKey: 'ORDER_PAYMENT_FAILED', templateCode: 'SMS_511295121',
          maskedPhone: '*******8963', status: 'FAILED', attemptCount: 3,
          nextAttemptAt: '2026-08-04T00:05:00Z', sentAt: null,
          sanitizedError: 'TRANSIENT:PROVIDER_NETWORK_ERROR',
          createdAt: '2026-08-04T00:00:00Z', updatedAt: '2026-08-04T00:01:00Z',
          phone: '15012348963', variables: { code: '654321' }, renderedMessage: 'must-not-render',
          credential: 'must-not-render', smsProof: 'must-not-render',
        },
        {
          id: 'sms-dead', templateKey: 'PACKAGE_EXPIRED', templateCode: 'SMS_511180141',
          maskedPhone: '*******4321', status: 'DEAD', attemptCount: 8,
          nextAttemptAt: '2026-08-04T00:06:00Z', sentAt: null,
          sanitizedError: 'PERMANENT:INVALID_PHONE',
          createdAt: '2026-08-04T00:00:00Z', updatedAt: '2026-08-04T00:01:00Z',
        },
        {
          id: 'sms-pending', templateKey: 'REGISTER_CODE', templateCode: 'SMS_511405090',
          maskedPhone: '*******0000', status: 'PENDING', attemptCount: 0,
          nextAttemptAt: '2026-08-04T00:07:00Z', sentAt: null, sanitizedError: null,
          createdAt: '2026-08-04T00:00:00Z', updatedAt: '2026-08-04T00:01:00Z',
        },
      ],
      total: 3, page: 0, size: 20,
    })
    api.retrySmsDelivery.mockResolvedValue({
      id: 'sms-failed', templateKey: 'ORDER_PAYMENT_FAILED', templateCode: 'SMS_511295121',
      maskedPhone: '*******8963', status: 'PENDING', attemptCount: 0,
      nextAttemptAt: '2026-08-04T00:08:00Z', sentAt: null, sanitizedError: null,
      createdAt: '2026-08-04T00:00:00Z', updatedAt: '2026-08-04T00:08:00Z',
    })
    api.listAudit.mockResolvedValue({ items: [{ id: 1, source: 'PLATFORM', tenantId: 'tenant-1', actorUserId: '71000000-0000-4000-8000-000000000001', action: 'PACKAGE_GRANT_CREATED', resourceType: 'GRANT', resourceId: 'grant-1', requestId: 'request-1', outcome: 'SUCCESS', detail: { businessNo: 'grant-visible-1' }, createdAt: '2026-07-30T12:00:00Z' }], total: 1, page: 0, size: 20 })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('submits a package grant with a unique business number', async () => {
    const wrapper = mount(GrantPackagesView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const userSelect = wrapper.getComponent('[data-test=grant-user]')
    const planSelect = wrapper.getComponent('[data-test=grant-plan]')
    userSelect.vm.$emit('update:modelValue', 'user-1')
    planSelect.vm.$emit('update:modelValue', 'plan-version-1')
    const reason = wrapper.getComponent({ name: 'ElInput' })
    reason.vm.$emit('update:modelValue', '本地测试')
    await wrapper.vm.$nextTick()
    expect(userSelect.props('modelValue')).toBe('user-1')
    expect(planSelect.props('modelValue')).toBe('plan-version-1')
    expect(reason.props('modelValue')).toBe('本地测试')
    await wrapper.get('[data-test=grant-submit]').trigger('click')
    await flushPromises()

    expect(api.createGrant).toHaveBeenCalledWith(expect.objectContaining({
      userId: 'user-1',
      packageVersionId: 'plan-version-1',
      reason: '本地测试',
      businessNo: expect.stringMatching(/^grant-/),
    }))
  })

  it('shows activation lifecycle independently from outbox delivery status', async () => {
    api.listGrants.mockResolvedValueOnce({
      items: [
        {
          id: 'grant-active', businessNo: 'grant-active-001', userId: 'user-1',
          userNo: 'U00000001', userEmail: 'user@example.com', packageCode: 'STARTER', packageName: 'Starter', version: 1,
          status: 'ACTIVE', reason: 'activated', outboxId: 'outbox-active', outboxStatus: 'DELIVERED',
          attemptCount: 1, failureReason: null, createdAt: '2026-07-31T00:00:00Z',
          updatedAt: '2026-07-31T00:01:00Z',
        },
        {
          id: 'grant-queued', businessNo: 'grant-queued-001', userId: 'user-1',
          userNo: 'U00000001', userEmail: 'user@example.com', packageCode: 'STARTER', packageName: 'Starter', version: 1,
          status: 'QUEUED', reason: 'queued', outboxId: 'outbox-queued', outboxStatus: 'DELIVERED',
          attemptCount: 1, failureReason: null, createdAt: '2026-07-31T00:00:00Z',
          updatedAt: '2026-07-31T00:01:00Z',
        },
      ],
      total: 2,
      page: 0,
      size: 20,
    })

    const wrapper = mount(GrantPackagesView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const activeTag = wrapper.findAll('.el-tag').find((tag) => tag.text() === '启用')
    const queuedTag = wrapper.findAll('.el-tag').find((tag) => tag.text() === '排队中')
    expect(activeTag?.classes()).toContain('el-tag--success')
    expect(queuedTag?.classes()).toContain('el-tag--warning')
    expect(wrapper.text()).toContain('已送达')
  })

  it('never renders provider secrets in the unified model list', async () => {
    const fixture = await api.listModels({})
    api.listModels.mockResolvedValueOnce({ ...fixture, items: fixture.items.map(model => ({ ...model, apiKey: 'provider-plain-secret' })) })
    const wrapper = mount(ModelsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.get('[data-test=model-edit-model-1]').exists()).toBe(true)
    expect(wrapper.html()).not.toContain('provider-plain-secret')
  })

  it('can select a provider before the first model exists', async () => {
    api.listModels.mockResolvedValueOnce({ items: [], total: 0, page: 0, size: 20 })
    const wrapper = mount(ModelsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=model-create]').trigger('click')
    await flushPromises()
    expect(api.listProviders).toHaveBeenCalled()
    expect(wrapper.getComponent('[data-test=model-provider]').props('modelValue')).toBe('provider-1')
  })

  it('does not expose invitation administration', async () => {
    const wrapper = mount(UsersInvitationsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[data-test=invitation-create]').exists()).toBe(false)
    expect(wrapper.find('[data-test=invitations-filter]').exists()).toBe(false)
  })

  it('protects the global administrator while allowing ordinary-user disable', async () => {
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '账号存在异常登录', action: 'confirm' })
    const wrapper = mount(UsersInvitationsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[data-test=user-toggle-admin-1]').exists()).toBe(false)
    expect(wrapper.find('[data-test=user-role-admin-1]').exists()).toBe(false)
    expect(wrapper.get('[data-test=user-protected-admin-1]').text()).toContain('不可操作')
    expect(wrapper.get('[data-test=global-admin-label]').text()).toContain('全局管理员')
    await wrapper.get('[data-test=user-toggle-user-1]').trigger('click')
    await flushPromises()

    expect(api.updateAdminUser).toHaveBeenCalledWith('user-1', 'DISABLED', 'USER', '账号存在异常登录')
  })

  it('promotes a user without changing account status', async () => {
    const wrapper = mount(UsersInvitationsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=user-role-user-1]').trigger('click')
    await flushPromises()

    expect(api.updateAdminUser).toHaveBeenCalledWith('user-1', 'ACTIVE', 'ADMIN')
  })

  it('hides role changes from ordinary administrators but keeps ordinary-user status controls', async () => {
    useAuthStore().setSession('ordinary-admin-token', {
      id: 'ordinary-admin-1', tenantId: 'ordinary-admin-1', email: 'ordinary-admin@example.com', role: 'ADMIN',
    })
    api.listAdminUsers.mockResolvedValueOnce({
      items: [
        { id: 'admin-1', tenantId: 'admin-1', userNo: 'U00000000', email: 'admin@example.com', displayName: '管理员', status: 'ACTIVE', role: 'ADMIN', globalAdmin: true, statusManageable: false, roleManageable: false },
        { id: 'user-1', tenantId: 'tenant-1', userNo: 'U00000001', email: 'user@example.com', displayName: '测试用户', status: 'ACTIVE', role: 'USER', globalAdmin: false, statusManageable: true, roleManageable: false },
      ],
      total: 2, page: 0, size: 20,
    })
    const wrapper = mount(UsersInvitationsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[data-test=user-role-user-1]').exists()).toBe(false)
    expect(wrapper.find('[data-test=user-toggle-user-1]').exists()).toBe(true)
  })

  it('filters, sorts, and pages users', async () => {
    const wrapper = mount(UsersInvitationsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=users-query]').setValue('user@example.com')
    wrapper.getComponent('[data-test=users-sort]').vm.$emit('update:modelValue', 'email')
    wrapper.getComponent('[data-test=users-direction]').vm.$emit('update:modelValue', 'asc')
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-test=users-filter]').trigger('click')
    await flushPromises()
    expect(api.listAdminUsers).toHaveBeenLastCalledWith({
      page: 0, size: 20, query: 'user@example.com', status: '', packageCode: '', sort: 'email', direction: 'asc',
    })

    wrapper.getComponent('[data-test=users-pagination]').vm.$emit('current-change', 2)
    await flushPromises()
    expect(api.listAdminUsers).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, query: 'user@example.com' }))
  })

  it('gives compact list controls explicit accessible names', async () => {
    const wrappers = [
      mount(UsersInvitationsView, { global: { plugins: [ElementPlus] } }),
      mount(GrantPackagesView, { global: { plugins: [ElementPlus] } }),
      mount(ModelsView, { global: { plugins: [ElementPlus] } }),
      mount(UsageQuotaView, { global: { plugins: [ElementPlus] } }),
      mount(OrdersPaymentsView, { global: { plugins: [ElementPlus] } }),
      mount(OperationsView, { global: { plugins: [ElementPlus] } }),
      mount(PlansAdminView, { global: { plugins: [ElementPlus] } }),
      mount(AuditView, { global: { plugins: [ElementPlus] } }),
    ]
    await flushPromises()

    const controls = [
      { selector: '[data-test=users-query]', control: wrappers[0].get('[data-test=users-query]') },
      { selector: '[data-test=grant-status]', control: wrappers[1].get('[data-test=grant-status]') },
      { selector: '[data-test=models-query]', control: wrappers[2].get('[data-test=models-query]') },
      { selector: '[data-test=usage-request-id]', control: wrappers[3].get('[data-test=usage-request-id]') },
      { selector: '[data-test=orders-query]', control: wrappers[4].get('[data-test=orders-query]') },
      { selector: '[data-test=outbox-status]', control: wrappers[5].get('[data-test=outbox-status]') },
      { selector: '[data-test=plans-query]', control: wrappers[6].get('[data-test=plans-query]') },
      { selector: '[data-test=audit-actor]', control: wrappers[7].get('[data-test=audit-actor]') },
    ]
    for (const { selector, control } of controls) {
      const nestedControl = control.find('[aria-label]')
      const wrappingLabel = control.element.closest('label')
      const name = control.attributes('aria-label')
        || (nestedControl.exists() ? nestedControl.attributes('aria-label') : undefined)
        || wrappingLabel?.textContent?.trim()
      expect(name, selector).toBeTruthy()
    }
    wrappers.forEach((wrapper) => wrapper.unmount())
  })

  it('shows only verified system health and never invents a CDN component', async () => {
    const wrapper = mount(AdminOverviewView, {
      global: { plugins: [ElementPlus], stubs: { AppChart: { template: '<div />' } } },
    })
    await flushPromises()

    for (const component of ['平台服务', '网关服务', 'PostgreSQL', 'Redis', '消息队列', '定时任务']) {
      expect(wrapper.text()).toContain(component)
    }
    expect(wrapper.text()).not.toContain('CDN')
    expect(wrapper.get('[data-test=system-cache]').text()).toContain('未检测')
    expect(wrapper.get('[data-test=system-cache]').text()).not.toContain('正常')
    expect(wrapper.get('[data-test=system-scheduler]').text()).toContain('待回收 2')
  })

  it('rejects a plan draft with a non-positive platform price', async () => {
    const wrapper = mount(PlansAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=plan-version-create-STARTER]').trigger('click')
    await wrapper.get('[data-test=plan-version-create]').trigger('click')

    expect(api.createPlanVersion).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('标准套餐售价必须大于 0')
  })

  it('creates an arbitrary package catalog entry before its first version', async () => {
    const wrapper = mount(PlansAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=plan-create-toggle]').trigger('click')
    await wrapper.get('[data-test=new-plan-name]').setValue('开发者版')
    await wrapper.get('[data-test=plan-create]').trigger('click')
    await flushPromises()

    expect(api.createPackagePlan).toHaveBeenCalledWith({
      name: '开发者版', description: null, kind: 'STANDARD',
    })
  })

  it('creates a validated package version from the gateway model catalog', async () => {
    const wrapper = mount(PlansAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=plan-version-create-STARTER]').trigger('click')
    wrapper.getComponent('[data-test=plan-price]').vm.$emit('update:modelValue', 9900)
    wrapper.getComponent('[data-test=plan-models]').vm.$emit('update:modelValue', ['qwen-plus', 'multimodal-basic'])
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-test=plan-version-create]').trigger('click')
    await flushPromises()

    expect(api.createPlanVersion).toHaveBeenCalledWith(expect.objectContaining({
      planCode: 'STARTER',
      priceAmount: 9900,
      totalTokenQuota: 1_000_000,
      models: [
        { modelCode: 'qwen-plus', modelTokenQuota: null, capabilities: ['TEXT'] },
        { modelCode: 'multimodal-basic', modelTokenQuota: null, capabilities: ['TEXT', 'IMAGE', 'FILE'] },
      ],
    }))
  })

  it('shows the safe package validation reason returned by the platform', async () => {
    api.createPlanVersion.mockRejectedValueOnce({
      response: { data: { code: 'invalid_model_permission', message: '模型权限无效，请至少选择一个模型并检查模型能力。' } },
    })
    const wrapper = mount(PlansAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=plan-version-create-STARTER]').trigger('click')
    wrapper.getComponent('[data-test=plan-price]').vm.$emit('update:modelValue', 9900)
    wrapper.getComponent('[data-test=plan-models]').vm.$emit('update:modelValue', ['qwen-plus'])
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-test=plan-version-create]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('模型权限无效，请至少选择一个模型并检查模型能力。')
    expect(document.body.textContent).toContain('模型权限无效，请至少选择一个模型并检查模型能力。')
  })

  it('uses the compact package version form layout', async () => {
    const wrapper = mount(PlansAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=plan-version-create-STARTER]').trigger('click')
    expect(wrapper.get('[data-test=plan-form]').classes()).toContain('plan-form--compact')
    expect(wrapper.find('.plan-code-field').exists()).toBe(true)
  })

  it('publishes a draft and confirms retirement of the current version', async () => {
    const current = (await api.listPackagePlanCatalog()).items[0]
    api.listPackagePlanCatalog.mockResolvedValueOnce({
      items: [{
        ...current,
        versions: [{
          ...current.versions[0], versionId: 'plan-version-2', version: 2, status: 'DRAFT',
          publishedAt: null, createdAt: '2026-07-31T00:00:00Z',
        }, current.versions[0]],
      }], total: 1, page: 0, size: 10,
    })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mount(PlansAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=plan-publish-plan-version-2]').trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('当前 v1 将自动退役'),
      '确认发布版本',
      expect.any(Object),
    )
    expect(api.publishPlanVersion).toHaveBeenCalledWith('STARTER', 2)
  })

  it('loads bounded grant and plan selectors while paging operational records', async () => {
    const grants = mount(GrantPackagesView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(api.listAdminUsers).toHaveBeenCalledWith({
      page: 0, size: 100, query: '', status: 'ACTIVE', sort: 'userNo', direction: 'asc',
    })
    expect(api.listAdminPlans).toHaveBeenCalledWith({
      page: 0, size: 100, query: '', status: 'PUBLISHED', sort: 'code', direction: 'asc',
    })
    await grants.get('[data-test=grant-query]').setValue('business-42')
    grants.getComponent('[data-test=grant-status]').vm.$emit('update:modelValue', 'FAILED')
    await grants.vm.$nextTick()
    await grants.get('[data-test=grant-filter]').trigger('click')
    await flushPromises()
    expect(api.listGrants).toHaveBeenLastCalledWith({
      page: 0, size: 20, query: 'business-42', status: 'FAILED', sort: 'createdAt', direction: 'desc',
    })
    grants.unmount()

    const plans = mount(PlansAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(api.listModels).toHaveBeenCalledWith({
      page: 0, size: 100, query: '', status: 'ENABLED', sort: 'code', direction: 'asc',
    })
    plans.getComponent('[data-test=plans-pagination]').vm.$emit('current-change', 2)
    await flushPromises()
    expect(api.listPackagePlanCatalog).toHaveBeenLastCalledWith({ page: 1, size: 10, query: '', status: '' })
  })

  it('shows provider and upstream model in the unified paged model table', async () => {
    const models = mount(ModelsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await models.get('[data-test=models-query]').setValue('qwen')
    models.getComponent('[data-test=models-sort]').vm.$emit('update:modelValue', 'displayName')
    await models.vm.$nextTick()
    await models.get('[data-test=models-filter]').trigger('click')
    await flushPromises()
    expect(api.listModels).toHaveBeenLastCalledWith({
      page: 0, size: 20, query: 'qwen', status: 'ENABLED', sort: 'displayName', direction: 'asc',
    })
    expect(models.find('[data-test=model-edit-model-1]').exists()).toBe(true)
    expect(models.text()).toContain('DeepSeek')
    expect(models.get('[data-test=model-routes-multimodal-basic]').text()).toContain('deepseek-chat')
    expect(models.get('[data-test=model-routes-multimodal-basic]').text()).toContain('qwen-vl-plus')
    expect(models.get('[data-test=model-routes-multimodal-basic]').text()).toContain('qwen-long')
    expect(models.get('[data-test=model-routes-multimodal-basic]').text()).not.toContain('deepseek-v4')
    // 服务商列回答的是「这个模型由谁在服务」，所以必须列全：多渠道路由里的备用服务商
    // 在列表上要看得见，否则「有没有备胎」这个问题在页面上根本回答不了。
    expect(models.get('[data-test=model-providers-multimodal-basic]').text()).toContain('DeepSeek')
    expect(models.get('[data-test=model-providers-multimodal-basic]').text()).toContain('阿里云百炼')
    // 单服务商模型不能被拼出重复的名字。
    expect(models.get('[data-test=model-providers-qwen-plus]').text()).toBe('阿里云百炼')
    expect(models.find('.model-management-tabs').exists()).toBe(false)
    expect(models.text()).not.toContain('渠道配置')
    models.unmount()


  })

  it('updates model and upstream route in one configuration request', async () => {
    const wrapper = mount(ModelsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=model-edit-model-1]').trigger('click')
    await wrapper.get('[data-test=model-aliases]').setValue('qwen-plus, compatible-chat')
    wrapper.getComponent('[data-test=model-max-output]').vm.$emit('update:modelValue', 8192)
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-test=model-save]').trigger('click')
    await flushPromises()

    expect(api.saveModelConfiguration).toHaveBeenCalledWith(expect.objectContaining({
      id: 'model-1',
      aliases: ['qwen-plus', 'compatible-chat'],
      capabilities: ['TEXT'],
      maxOutputTokens: 8192,
      route: expect.objectContaining({
        channelId: 'channel-bailian',
        providerId: 'provider-bailian',
        upstreamModel: 'qwen-plus',
        apiKey: undefined,
      }),
    }))
  })

  it('creates a provider from the unified model page and selects it for the next model', async () => {
    const wrapper = mount(ModelsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=provider-create]').trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.getComponent('[data-test=provider-type]')
      .findAllComponents({ name: 'ElOption' })
      .map((option) => option.props('label')))
      .toEqual(['OpenAI 兼容协议', '百炼协议', '模拟服务'])
    await wrapper.get('[data-test=provider-name]').setValue('OpenAI 兼容服务')
    await wrapper.get('[data-test=provider-code]').setValue('openai-compatible')
    await wrapper.get('[data-test=provider-base-url]').setValue('https://provider.example.com/v1')
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-test=provider-save]').trigger('click')
    await flushPromises()

    expect(api.createProvider).toHaveBeenCalledWith({
      code: 'openai-compatible',
      name: 'OpenAI 兼容服务',
      providerType: 'DEEPSEEK',
      baseUrl: 'https://provider.example.com/v1',
      enabled: true,
    })

    await wrapper.get('[data-test=model-create]').trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.getComponent('[data-test=model-provider]').props('modelValue')).toBe('provider-new')
    expect((wrapper.get('[data-test=model-base-url]').element as HTMLInputElement).value)
      .toBe('https://provider.example.com/v1')
  })

  it('renders capability badges without exposing a separate routing strategy control', async () => {
    const wrapper = mount(ModelsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    for (const [capability, label] of Object.entries({ TEXT: '文本', IMAGE: '图像', FILE: '文件' })) {
      expect(wrapper.get(`[data-test=model-capability-multimodal-basic-${capability}]`).text()).toBe(label)
    }
    expect(wrapper.find('[data-test=model-routing-input]').exists()).toBe(false)
  })

  it('shows usage, reservations, ledger, estimates, and uncovered tokens', async () => {
    const wrapper = mount(UsageQuotaView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(api.listAdminUsage).toHaveBeenCalled()
    expect(api.listReservations).toHaveBeenCalled()
    expect(api.listQuotaLedger).toHaveBeenCalled()
    expect(wrapper.text()).toContain('估算')
    expect(wrapper.text()).toContain('未覆盖 17')
    expect(wrapper.text()).toContain('结算')
    expect(wrapper.text()).toContain('2026-07-31 08:00:00')
  })

  it('expands sanitized composite stages and formats CNY minor units', async () => {
    const wrapper = mount(UsageQuotaView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[data-test=usage-stage-toggle-usage-1]').exists()).toBe(false)
    const toggle = wrapper.get('[data-test=usage-stage-toggle-usage-composite]')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(toggle.text()).toBe('')

    await toggle.trigger('click')

    expect(toggle.attributes('aria-expanded')).toBe('true')
    const stages = wrapper.get('[data-test=usage-stages-usage-composite]')
    expect(stages.text()).toContain('视觉基础版')
    expect(stages.text()).toContain('阿里云百炼')
    expect(stages.text()).toContain('百炼主渠道')
    expect(stages.text()).toContain('通义千问视觉 Plus')
    expect(stages.text()).toContain('125')
    expect(stages.text()).toContain('成功')
    expect(stages.text()).toContain('¥0.07')
  })

  it('filters the reservation view to authoritative uncovered-token anomalies', async () => {
    const wrapper = mount(UsageQuotaView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const anomalyOnly = wrapper.getComponent('[data-test=reservation-anomaly-only]')
    anomalyOnly.vm.$emit('update:modelValue', true)
    anomalyOnly.vm.$emit('change', true)
    await flushPromises()

    expect(api.listReservations).toHaveBeenLastCalledWith({
      page: 0, size: 20, query: '', status: '', sort: 'createdAt', direction: 'desc',
      userId: '', requestId: '', anomalyOnly: true,
    })
  })

  it('keeps usage, reservation, and ledger list state independent', async () => {
    const wrapper = mount(UsageQuotaView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const reservationCalls = api.listReservations.mock.calls.length
    const ledgerCalls = api.listQuotaLedger.mock.calls.length
    await wrapper.get('[data-test=usage-request-id]').setValue('req-usage')
    await wrapper.get('[data-test=usage-filter]').trigger('click')
    await flushPromises()
    expect(api.listAdminUsage).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, requestId: 'req-usage' }))
    expect(api.listReservations).toHaveBeenCalledTimes(reservationCalls)
    expect(api.listQuotaLedger).toHaveBeenCalledTimes(ledgerCalls)

    const anomaly = wrapper.getComponent('[data-test=reservation-anomaly-only]')
    anomaly.vm.$emit('update:modelValue', true)
    anomaly.vm.$emit('change', true)
    await flushPromises()
    wrapper.getComponent('[data-test=reservations-pagination]').vm.$emit('current-change', 2)
    await flushPromises()
    expect(api.listReservations).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, anomalyOnly: true }))

    await wrapper.get('[data-test=ledger-model-code]').setValue('qwen-plus')
    wrapper.getComponent('[data-test=ledger-sort]').vm.$emit('update:modelValue', 'balanceAfter')
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-test=ledger-filter]').trigger('click')
    await flushPromises()
    expect(api.listQuotaLedger).toHaveBeenLastCalledWith(expect.objectContaining({
      page: 0, modelCode: 'qwen-plus', sort: 'balanceAfter',
    }))
  })

  it('shows orders, payment transactions, and refunds as separate ledgers', async () => {
    const wrapper = mount(OrdersPaymentsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(api.listAdminOrders).toHaveBeenCalledWith({ page: 0, size: 20, query: '', status: '', sort: 'createdAt', direction: 'desc' })
    expect(api.listPayments).toHaveBeenCalledWith({ page: 0, size: 20, query: '', status: '', sort: 'createdAt', direction: 'desc' })
    expect(api.listRefunds).toHaveBeenCalledWith({ page: 0, size: 20, query: '', status: '', sort: 'createdAt', direction: 'desc' })
    expect(wrapper.text()).toContain('ORD-20260731-001')
    expect(wrapper.text()).toContain('2026-07-31 08:00:00')
    await wrapper.get('#tab-payments').trigger('click')
    expect(wrapper.text()).toContain('PAY-20260731-001')
    expect(wrapper.text()).toContain('2026-07-31 08:01:00')
    await wrapper.get('#tab-refunds').trigger('click')
    expect(wrapper.text()).toContain('REF-20260731-001')
  })

  it('keeps the newest order, payment, and configuration refresh authoritative', async () => {
    const wrapper = mount(OrdersPaymentsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const oldOrders = deferred<unknown>()
    const oldPayments = deferred<unknown>()
    const oldConfigs = deferred<unknown>()
    const newOrders = deferred<unknown>()
    const newPayments = deferred<unknown>()
    const newConfigs = deferred<unknown>()
    const orderPages = [oldOrders.promise, newOrders.promise]
    api.listAdminOrders.mockImplementation(params => params.size === 100
      ? Promise.resolve({ items: [], total: 0, page: 0, size: 100 })
      : orderPages.shift()!)
    api.listPayments.mockReturnValueOnce(oldPayments.promise).mockReturnValueOnce(newPayments.promise)
    api.listPaymentConfigs.mockReturnValueOnce(oldConfigs.promise).mockReturnValueOnce(newConfigs.promise)

    const refresh = wrapper.find('.admin-page__header').findAllComponents({ name: 'ElButton' }).find(button => button.text() === '刷新')!
    refresh.vm.$emit('click', new MouseEvent('click'))
    await wrapper.vm.$nextTick()
    refresh.vm.$emit('click', new MouseEvent('click'))
    await wrapper.vm.$nextTick()

    oldOrders.resolve({
      items: [{
        totalTokensUsed: 0, cumulativeSpendAmount: 0, id: 'order-old', orderNo: 'ORD-OLD', userId: 'user-old', userEmail: 'old@example.com',
        packageCode: 'OLD', packageName: 'Old', amount: 100, currency: 'CNY', status: 'PAID',
        paymentProvider: 'ALIPAY', paymentStatus: 'SUCCEEDED', refundStatus: null,
        paidAt: null, createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-01T00:00:00Z',
      }],
      total: 1, page: 4, size: 20,
    })
    oldPayments.reject(new Error('stale payment request'))
    oldConfigs.resolve([{
      provider: 'ALIPAY', environment: 'SANDBOX', enabled: false, configured: false,
      credentialFingerprint: 'stale-fingerprint', lastValidationStatus: 'INVALID',
      lastValidatedAt: null, updatedAt: '2026-07-01T00:00:00Z',
    }])
    await flushPromises()

    expect(refresh.props('loading')).toBe(true)
    expect(wrapper.text()).not.toContain('ORD-OLD')
    expect(wrapper.text()).not.toContain('stale-fingerprint')
    expect(wrapper.find('.el-alert').exists()).toBe(false)

    newOrders.resolve({
      items: [{
        totalTokensUsed: 0, cumulativeSpendAmount: 0, id: 'order-new', orderNo: 'ORD-NEW', userId: 'user-new', userEmail: 'new@example.com',
        packageCode: 'NEW', packageName: 'New', packageVersion: 2, amount: 200, currency: 'CNY', status: 'PAID',
        paymentProvider: 'WECHAT_PAY', paymentStatus: 'SUCCEEDED', refundStatus: null,
        paidAt: null, createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
      }],
      total: 61, page: 2, size: 20,
    })
    newPayments.resolve({
      items: [{
        id: 'payment-new', transactionNo: 'PAY-NEW', orderId: 'order-new', orderNo: 'ORD-NEW',
        userId: 'user-new', userEmail: 'new@example.com', provider: 'WECHAT_PAY',
        externalTradeNo: 'WX-NEW', amount: 200, status: 'SUCCEEDED', paidAt: null,
        createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
      }],
      total: 23, page: 1, size: 20,
    })
    newConfigs.resolve([{
      provider: 'WECHAT_PAY', environment: 'SANDBOX', enabled: true, configured: true,
      credentialFingerprint: 'new-fingerprint', lastValidationStatus: 'VALID',
      lastValidatedAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
    }])
    await flushPromises()

    expect(refresh.props('loading')).toBe(false)
    expect(wrapper.text()).toContain('ORD-NEW')
    expect(wrapper.text()).toContain('PAY-NEW')
    expect(wrapper.text()).toContain('******rprint')
    expect(wrapper.html()).not.toContain('new-fingerprint')
    expect(wrapper.getComponent('[data-test=orders-pagination]').props('currentPage')).toBe(3)
    expect(wrapper.getComponent('[data-test=orders-pagination]').props('total')).toBe(61)
    expect(wrapper.getComponent('[data-test=payments-pagination]').props('currentPage')).toBe(2)
    expect(wrapper.getComponent('[data-test=payments-pagination]').props('total')).toBe(23)
    expect(wrapper.find('.el-alert').exists()).toBe(false)
  })

  it('toggles only the selected payment provider configuration', async () => {
    const wrapper = mount(OrdersPaymentsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=payment-config-ALIPAY-SANDBOX]').trigger('click')
    await flushPromises()

    expect(api.updatePaymentConfig).toHaveBeenCalledWith('ALIPAY', 'SANDBOX', true)
  })

  it('ignores a stale configuration toggle result after a newer refresh succeeds', async () => {
    const wrapper = mount(OrdersPaymentsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const staleToggle = deferred<unknown>()
    api.updatePaymentConfig.mockReturnValueOnce(staleToggle.promise)
    await wrapper.get('[data-test=payment-config-ALIPAY-SANDBOX]').trigger('click')
    await wrapper.vm.$nextTick()

    api.listPaymentConfigs.mockResolvedValueOnce([{
      provider: 'ALIPAY', environment: 'SANDBOX', enabled: false, configured: true,
      credentialFingerprint: 'refresh-fingerprint', lastValidationStatus: 'VALID',
      lastValidatedAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
    }])
    wrapper.find('.admin-page__header').findAllComponents({ name: 'ElButton' }).find(button => button.text() === '刷新')!
      .vm.$emit('click', new MouseEvent('click'))
    await flushPromises()

    staleToggle.resolve({
      provider: 'ALIPAY', environment: 'SANDBOX', enabled: true, configured: true,
      credentialFingerprint: 'stale-toggle-fingerprint', lastValidationStatus: 'VALID',
      lastValidatedAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-01T00:00:00Z',
    })
    await flushPromises()

    expect(wrapper.text()).toContain('******rprint')
    expect(wrapper.html()).not.toContain('refresh-fingerprint')
    expect(wrapper.text()).not.toContain('stale-toggle-fingerprint')
    expect(wrapper.getComponent('[data-test=payment-config-ALIPAY-SANDBOX]').props('loading')).toBe(false)
  })

  it('ignores a stale configuration toggle error after a newer refresh succeeds', async () => {
    const wrapper = mount(OrdersPaymentsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const staleToggle = deferred<unknown>()
    api.updatePaymentConfig.mockReturnValueOnce(staleToggle.promise)
    await wrapper.get('[data-test=payment-config-ALIPAY-SANDBOX]').trigger('click')
    await wrapper.vm.$nextTick()

    api.listPaymentConfigs.mockResolvedValueOnce([{
      provider: 'ALIPAY', environment: 'SANDBOX', enabled: false, configured: true,
      credentialFingerprint: 'authoritative-fingerprint', lastValidationStatus: 'VALID',
      lastValidatedAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
    }])
    wrapper.find('.admin-page__header').findAllComponents({ name: 'ElButton' }).find(button => button.text() === '刷新')!
      .vm.$emit('click', new MouseEvent('click'))
    await flushPromises()

    staleToggle.reject(new Error('stale toggle failure'))
    await flushPromises()

    expect(wrapper.text()).toContain('******rprint')
    expect(wrapper.html()).not.toContain('authoritative-fingerprint')
    expect(wrapper.find('.el-alert').exists()).toBe(false)
    expect(wrapper.getComponent('[data-test=payment-config-ALIPAY-SANDBOX]').props('loading')).toBe(false)
  })

  it('confirms and simulates a pending mock payment on the server', async () => {
    api.listAdminOrders.mockResolvedValueOnce({
      items: [{
        totalTokensUsed: 0, cumulativeSpendAmount: 0, id: 'order-mock', orderNo: 'ORD-MOCK-001', userId: 'user-1', userEmail: 'user@example.com',
        packageCode: 'STARTER', packageName: '入门版', amount: 9900, currency: 'CNY', status: 'PENDING',
        paymentProvider: 'MOCK', paymentStatus: null, refundStatus: null, paidAt: null,
        createdAt: '2026-07-31T00:00:00Z', updatedAt: '2026-07-31T00:00:00Z',
      }],
      total: 1, page: 0, size: 20,
    })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mount(OrdersPaymentsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=simulate-payment-order-mock]').trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalled()
    expect(api.simulatePayment).toHaveBeenCalledWith('ORD-MOCK-001')
    expect(api.listAdminOrders.mock.calls.filter(([params]) => params.size === 20)).toHaveLength(2)
  })

  it('keeps order, payment, and refund list controls independent', async () => {
    const wrapper = mount(OrdersPaymentsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const paymentCalls = api.listPayments.mock.calls.length
    const refundCalls = api.listRefunds.mock.calls.length
    await wrapper.get('[data-test=orders-query]').setValue('ORD-42')
    await wrapper.get('[data-test=orders-filter]').trigger('click')
    await flushPromises()
    expect(api.listAdminOrders.mock.calls.filter(([params]) => params.size === 20).at(-1)?.[0]).toEqual(expect.objectContaining({ page: 0, query: 'ORD-42' }))
    expect(api.listPayments).toHaveBeenCalledTimes(paymentCalls)
    expect(api.listRefunds).toHaveBeenCalledTimes(refundCalls)

    await wrapper.get('#tab-payments').trigger('click')
    wrapper.getComponent('[data-test=payments-pagination]').vm.$emit('current-change', 2)
    await flushPromises()
    expect(api.listPayments).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
    await wrapper.get('#tab-refunds').trigger('click')
    wrapper.getComponent('[data-test=refunds-status]').vm.$emit('update:modelValue', 'FAILED')
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-test=refunds-filter]').trigger('click')
    await flushPromises()
    expect(api.listRefunds).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, status: 'FAILED' }))
  })

  it('ignores a stale refund page after a newer filtered request completes', async () => {
    const wrapper = mount(RefundReviewPanel, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const stale = deferred<unknown>()
    api.listRefunds.mockReturnValueOnce(stale.promise).mockResolvedValueOnce({
      items: [{
        id: 'refund-new', refundNo: 'REF-NEW', orderId: 'order-new', orderNo: 'ORD-NEW',
        userId: 'user-new', userEmail: 'new@example.com', paymentTransactionId: 'payment-new',
        externalRefundNo: null, amount: 500, status: 'PENDING_APPROVAL', reason: 'new request',
        completedAt: null, createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
      }],
      total: 3, page: 0, size: 20,
    })

    wrapper.getComponent('[data-test=refunds-pagination]').vm.$emit('current-change', 2)
    await wrapper.get('[data-test=refunds-query]').setValue('REF-NEW')
    wrapper.getComponent('[data-test=refunds-filter]').vm.$emit('click', new MouseEvent('click'))
    await flushPromises()
    stale.resolve({
      items: [{
        id: 'refund-old', refundNo: 'REF-OLD', orderId: 'order-old', orderNo: 'ORD-OLD',
        userId: 'user-old', userEmail: 'old@example.com', paymentTransactionId: 'payment-old',
        externalRefundNo: null, amount: 100, status: 'FAILED', reason: 'stale request',
        completedAt: null, createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-01T00:00:00Z',
      }],
      total: 41, page: 1, size: 20,
    })
    await flushPromises()

    expect(wrapper.text()).toContain('REF-NEW')
    expect(wrapper.text()).not.toContain('REF-OLD')
    expect(wrapper.getComponent('[data-test=refunds-pagination]').props('currentPage')).toBe(1)
    expect(wrapper.getComponent('[data-test=refunds-pagination]').props('total')).toBe(3)
    expect(wrapper.getComponent('[data-test=refunds-filter]').props('loading')).toBe(false)
    expect(wrapper.find('.el-alert').exists()).toBe(false)
  })

  it('separates transaction workflows into tabs and renders only sanitized payment readiness', async () => {
    const wrapper = mount(OrdersPaymentsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const panes = wrapper.findAllComponents({ name: 'ElTabPane' })
    expect(panes.map((pane) => pane.props('name'))).toEqual([
      'orders', 'payments', 'refunds', 'refund-policies', 'anomalies', 'reconciliation',
    ])
    const pane = (name: string) => panes.find((candidate) => candidate.props('name') === name)!
    expect(pane('orders').find('[data-test=orders-filter]').exists()).toBe(true)
    expect(pane('payments').find('[data-test=payments-filter]').exists()).toBe(true)
    expect(pane('refunds').findComponent(RefundReviewPanel).exists()).toBe(true)
    expect(pane('refund-policies').findComponent(RefundPolicyPanel).exists()).toBe(true)
    expect(pane('anomalies').findComponent(PaymentAnomalyPanel).exists()).toBe(true)
    expect(pane('reconciliation').findComponent(ReconciliationPanel).exists()).toBe(true)
    for (const label of ['订单', '支付', '退款审核', '退款策略', '异常队列', '对账']) {
      expect(wrapper.text()).toContain(label)
    }
    expect(wrapper.text()).toContain('******abc123')
    expect(wrapper.html()).not.toContain('sha256:abc123')
    expect(wrapper.text()).toContain('有效')
    expect(wrapper.text()).not.toContain('must-not-render')
  })

  it('shows an immutable refund calculation and prevents duplicate approval submission', async () => {
    const approved = { ...await api.getAdminRefund(), status: 'SUBMITTING', reviewedBy: 'admin-1', reviewedAt: '2026-07-31T00:06:00Z' }
    api.getAdminRefund.mockClear()
    api.getAdminRefund.mockResolvedValueOnce({ ...approved, status: 'PENDING_APPROVAL', reviewedBy: null, reviewedAt: null })
      .mockResolvedValueOnce(approved)
    let releaseConfirmation!: () => void
    const confirmation = new Promise<string>((resolve) => { releaseConfirmation = () => resolve('confirm') })
    vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(confirmation as never)
    const wrapper = mount(RefundReviewPanel, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=review-refund-refund-1]').trigger('click')
    await flushPromises()

    const detail = wrapper.get('[data-test=refund-review-detail]').text()
    for (const value of [
      '¥99.00', '10,000', '2,000', '8,000', '政策版本 1', '5%', '¥1.00', '¥4.95', '¥74.25',
      '10%', '7 天', 'ACTIVE', '2026-08-30 08:00:00',
      '2026-07-30 08:00:00', '2026-07-31 08:04:00', '2026-07-31 08:04:01',
    ]) {
      expect(detail).toContain(value)
    }
    const approve = wrapper.get('[data-test=approve-refund]')
    await approve.trigger('click')
    await approve.trigger('click')
    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(approve.attributes('disabled')).toBeDefined()
    releaseConfirmation()
    await flushPromises()

    expect(api.approveRefund).toHaveBeenCalledTimes(1)
    expect(api.approveRefund).toHaveBeenCalledWith('REF-20260731-001')
    expect(api.getAdminRefund).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('提交中')
  })

  it('rejects a refund with only the confirmed review note and refreshes detail', async () => {
    let releaseConfirmation!: () => void
    const confirmation = new Promise<string>((resolve) => { releaseConfirmation = () => resolve('confirm') })
    vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(confirmation as never)
    const wrapper = mount(RefundReviewPanel, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=review-refund-refund-1]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=refund-review-note]').setValue('不符合退款政策')
    const reject = wrapper.get('[data-test=reject-refund]')
    await reject.trigger('click')
    await reject.trigger('click')
    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(reject.attributes('disabled')).toBeDefined()
    releaseConfirmation()
    await flushPromises()

    expect(api.rejectRefund).toHaveBeenCalledTimes(1)
    expect(api.rejectRefund).toHaveBeenCalledWith('REF-20260731-001', '不符合退款政策')
    expect(api.getAdminRefund).toHaveBeenCalledTimes(2)
  })

  it('offers every backend refund lifecycle state in the review filter', async () => {
    const wrapper = mount(RefundReviewPanel, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const status = wrapper.getComponent('[data-test=refunds-status]')
    const values = status.findAllComponents({ name: 'ElOption' }).map((option) => option.props('value'))
    expect(values).toEqual(expect.arrayContaining([
      'PREPARING', 'PENDING_APPROVAL', 'SUBMITTING', 'PROCESSING', 'REVOKING',
      'COMPLETED', 'REJECTED', 'CANCELED', 'FAILED',
    ]))
  })

  it('renders refund user numbers without exposing email addresses', async () => {
    const wrapper = mount(RefundReviewPanel, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('用户编号')
    expect(wrapper.text()).toContain('U00000001')
    expect(wrapper.text()).not.toContain('user@example.com')
  })

  it('clears a stale refund detail when the next authoritative detail request fails', async () => {
    api.listRefunds.mockResolvedValueOnce({
      items: [
        { id: 'refund-1', refundNo: 'REF-20260731-001', orderId: 'order-1', orderNo: 'ORD-1', userId: 'user-1', userEmail: 'user@example.com', paymentTransactionId: 'payment-1', externalRefundNo: null, amount: 7425, status: 'PENDING_APPROVAL', reason: '套餐调整', completedAt: null, createdAt: '2026-07-31T00:04:00Z', updatedAt: '2026-07-31T00:05:00Z' },
        { id: 'refund-2', refundNo: 'REF-20260731-002', orderId: 'order-2', orderNo: 'ORD-2', userId: 'user-2', userEmail: 'other@example.com', paymentTransactionId: 'payment-2', externalRefundNo: null, amount: 1000, status: 'PENDING_APPROVAL', reason: '重复购买', completedAt: null, createdAt: '2026-07-31T00:06:00Z', updatedAt: '2026-07-31T00:06:00Z' },
      ],
      total: 2, page: 0, size: 20,
    })
    api.getAdminRefund.mockResolvedValueOnce(await api.getAdminRefund()).mockRejectedValueOnce(new Error('network'))
    const wrapper = mount(RefundReviewPanel, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=review-refund-refund-1]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test=refund-review-detail]').text()).toContain('REF-20260731-001')

    await wrapper.get('[data-test=review-refund-refund-2]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test=refund-review-detail]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('退款审核详情加载失败')
    expect(document.body.textContent).toContain('退款审核详情加载失败')
  })

  it('keeps the newest refund detail when an older selection finishes later', async () => {
    const baseDetail = await api.getAdminRefund()
    api.listRefunds.mockResolvedValueOnce({
      items: [
        { id: 'refund-1', refundNo: 'REF-OLD-DETAIL', orderId: 'order-1', orderNo: 'ORD-1', userId: 'user-1', userEmail: 'user@example.com', paymentTransactionId: 'payment-1', externalRefundNo: null, amount: 7425, status: 'PENDING_APPROVAL', reason: 'old', completedAt: null, createdAt: '2026-07-31T00:04:00Z', updatedAt: '2026-07-31T00:05:00Z' },
        { id: 'refund-2', refundNo: 'REF-NEW-DETAIL', orderId: 'order-2', orderNo: 'ORD-2', userId: 'user-2', userEmail: 'other@example.com', paymentTransactionId: 'payment-2', externalRefundNo: null, amount: 1000, status: 'PENDING_APPROVAL', reason: 'new', completedAt: null, createdAt: '2026-07-31T00:06:00Z', updatedAt: '2026-07-31T00:06:00Z' },
      ],
      total: 2, page: 0, size: 20,
    })
    const older = deferred<unknown>()
    const newer = deferred<unknown>()
    api.getAdminRefund.mockReset()
    api.getAdminRefund.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise)
    const wrapper = mount(RefundReviewPanel, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=review-refund-refund-1]').trigger('click')
    await wrapper.get('[data-test=review-refund-refund-2]').trigger('click')
    newer.resolve({ ...baseDetail, refundNo: 'REF-NEW-DETAIL', orderNo: 'ORD-2', reason: 'new' })
    older.reject(new Error('stale detail request'))
    await flushPromises()

    expect(api.getAdminRefund).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-test=refund-review-detail]').text()).toContain('REF-NEW-DETAIL')
    expect(wrapper.get('[data-test=refund-review-detail]').text()).not.toContain('REF-OLD-DETAIL')
    expect(wrapper.find('.el-alert').exists()).toBe(false)
  })

  it('renders a preparing refund safely before its calculation snapshot exists', async () => {
    api.listRefunds.mockResolvedValueOnce({
      items: [{ id: 'refund-preparing', refundNo: 'REF-PREPARING', orderId: 'order-1', orderNo: 'ORD-1', userId: 'user-1', userEmail: 'user@example.com', paymentTransactionId: 'payment-1', externalRefundNo: null, amount: 0, status: 'PREPARING', reason: '套餐调整', completedAt: null, createdAt: '2026-07-31T00:04:00Z', updatedAt: '2026-07-31T00:05:00Z' }],
      total: 1, page: 0, size: 20,
    })
    api.getAdminRefund.mockResolvedValueOnce({
      refundNo: 'REF-PREPARING', orderNo: 'ORD-1', status: 'PREPARING', refundType: null,
      amount: 0, reason: '套餐调整', calculation: null, reviewedBy: null, reviewedAt: null,
      reviewNote: null, failureReason: null, createdAt: '2026-07-31T00:04:00Z', updatedAt: '2026-07-31T00:05:00Z',
    })
    const wrapper = mount(RefundReviewPanel, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=review-refund-refund-preparing]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test=refund-review-detail]').text()).toContain('计算快照尚未生成')
    expect(wrapper.get('[data-test=approve-refund]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test=reject-refund]').attributes('disabled')).toBeDefined()
  })

  it('enforces the backend refund rejection note limit before confirmation', async () => {
    const wrapper = mount(RefundReviewPanel, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=review-refund-refund-1]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=refund-review-note]').setValue('a'.repeat(501))

    expect(wrapper.get('[data-test=reject-refund]').attributes('disabled')).toBeDefined()
  })

  it('filters anomalies independently and moves them through the administrator lifecycle', async () => {
    const openPage = await api.listPaymentAnomalies()
    const open = openPage.items[0]
    const investigating = { ...open, status: 'INVESTIGATING', assignee: 'admin-1', lockVersion: 1 }
    const resolved = {
      ...investigating, status: 'RESOLVED', resolutionNote: 'confirmed',
      resolvedAt: '2026-08-01T00:00:00Z', lockVersion: 2,
    }
    api.listPaymentAnomalies.mockReset()
    api.listPaymentAnomalies
      .mockResolvedValueOnce(openPage)
      .mockResolvedValueOnce(openPage)
      .mockResolvedValueOnce({ items: [], total: 0, page: 0, size: 20 })
      .mockResolvedValueOnce({ items: [investigating], total: 1, page: 0, size: 20 })
      .mockResolvedValueOnce({ items: [], total: 0, page: 0, size: 20 })
    api.updatePaymentAnomaly.mockReset()
    api.updatePaymentAnomaly.mockResolvedValueOnce(investigating).mockResolvedValueOnce(resolved)
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '已核对渠道账单', action: 'confirm' })
    const wrapper = mount(PaymentAnomalyPanel, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    wrapper.getComponent('[data-test=anomaly-type]').vm.$emit('update:modelValue', 'PAYMENT_AMOUNT_MISMATCH')
    wrapper.getComponent('[data-test=anomaly-status]').vm.$emit('update:modelValue', 'OPEN')
    wrapper.getComponent('[data-test=anomaly-provider]').vm.$emit('update:modelValue', 'ALIPAY')
    await wrapper.get('[data-test=anomaly-from]').setValue('2026-08-01T00:00:00Z')
    await wrapper.get('[data-test=anomaly-to]').setValue('2026-08-02T00:00:00Z')
    await wrapper.get('[data-test=anomaly-order]').setValue('order-1')
    await wrapper.get('[data-test=anomaly-filter]').trigger('click')
    await flushPromises()

    expect(api.listPaymentAnomalies).toHaveBeenLastCalledWith(expect.objectContaining({
      page: 0, anomalyType: 'PAYMENT_AMOUNT_MISMATCH', status: 'OPEN', provider: 'ALIPAY',
      from: '2026-08-01T00:00:00Z', to: '2026-08-02T00:00:00Z', orderId: 'order-1',
    }))
    await wrapper.get('[data-test=investigate-anomaly-anomaly-1]').trigger('click')
    await flushPromises()
    expect(api.updatePaymentAnomaly).toHaveBeenLastCalledWith('anomaly-1', {
      status: 'INVESTIGATING', assignee: 'admin-1', resolutionNote: null,
    }, 0)
    expect(wrapper.find('[data-test=investigate-anomaly-anomaly-1]').exists()).toBe(false)
    wrapper.getComponent('[data-test=anomaly-status]').vm.$emit('update:modelValue', 'INVESTIGATING')
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-test=anomaly-filter]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=resolve-anomaly-anomaly-1]').trigger('click')
    await flushPromises()
    expect(api.updatePaymentAnomaly).toHaveBeenLastCalledWith('anomaly-1', {
      status: 'RESOLVED', assignee: 'admin-1', resolutionNote: '已核对渠道账单',
    }, 1)
    expect(wrapper.find('[data-test=resolve-anomaly-anomaly-1]').exists()).toBe(false)
  })

  it('ignores an older anomaly page that resolves after a newer filter', async () => {
    const wrapper = mount(PaymentAnomalyPanel, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const stale = deferred<unknown>()
    api.listPaymentAnomalies.mockReturnValueOnce(stale.promise).mockResolvedValueOnce({
      items: [{
        id: 'anomaly-new', fingerprint: 'fingerprint-new', anomalyType: 'PAYMENT_STATUS_MISMATCH',
        provider: 'WECHAT_PAY', orderId: 'order-new', paymentTransactionId: 'payment-new', refundId: null,
        status: 'OPEN', assignee: null, detail: { reason: 'NEW' }, resolutionNote: null,
        resolvedAt: null, createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
        lockVersion: 0,
      }],
      total: 2, page: 0, size: 20,
    })

    wrapper.getComponent('[data-test=anomalies-pagination]').vm.$emit('current-change', 2)
    await wrapper.get('[data-test=anomaly-order]').setValue('order-new')
    wrapper.getComponent('[data-test=anomaly-filter]').vm.$emit('click', new MouseEvent('click'))
    await flushPromises()
    stale.resolve({
      items: [{
        id: 'anomaly-old', fingerprint: 'fingerprint-old', anomalyType: 'PAYMENT_AMOUNT_MISMATCH',
        provider: 'ALIPAY', orderId: 'order-old', paymentTransactionId: 'payment-old', refundId: null,
        status: 'OPEN', assignee: null, detail: { reason: 'OLD' }, resolutionNote: null,
        resolvedAt: null, createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-01T00:00:00Z',
        lockVersion: 0,
      }],
      total: 41, page: 1, size: 20,
    })
    await flushPromises()

    expect(wrapper.text()).toContain('fingerprint-new')
    expect(wrapper.text()).not.toContain('fingerprint-old')
    expect(wrapper.getComponent('[data-test=anomalies-pagination]').props('currentPage')).toBe(1)
    expect(wrapper.getComponent('[data-test=anomalies-pagination]').props('total')).toBe(2)
    expect(wrapper.getComponent('[data-test=anomaly-filter]').props('loading')).toBe(false)
    expect(wrapper.find('.el-alert').exists()).toBe(false)
  })

  it('starts a reconciliation and renders bounded summary and difference items without raw bills', async () => {
    const wrapper = mount(ReconciliationPanel, { global: { plugins: [ElementPlus] } })
    wrapper.getComponent('[data-test=reconciliation-provider]').vm.$emit('update:modelValue', 'WECHAT_PAY')
    await wrapper.get('[data-test=reconciliation-date]').setValue('2026-07-31')
    await wrapper.get('[data-test=start-reconciliation]').trigger('click')
    await flushPromises()

    expect(api.startReconciliation).toHaveBeenCalledWith('WECHAT_PAY', '2026-07-31')
    expect(wrapper.text()).toContain('匹配记录8')
    expect(wrapper.text()).toContain('差异总数2')
    expect(wrapper.text()).toContain('AMOUNT_MISMATCH')
    expect(wrapper.text()).toContain('provider-record-1')
    expect(wrapper.text()).not.toContain('must-not-render')
    wrapper.getComponent('[data-test=reconciliation-pagination]').vm.$emit('current-change', 2)
    await flushPromises()
    expect(api.getReconciliationRun).toHaveBeenCalledWith('run-1', 1, 20, 'createdAt', 'desc')
  })

  it('keeps anomaly and reconciliation pagination state independent', async () => {
    const wrapper = mount(OrdersPaymentsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const reconciliationCalls = api.getReconciliationRun.mock.calls.length
    wrapper.getComponent('[data-test=anomalies-pagination]').vm.$emit('current-change', 2)
    await flushPromises()
    expect(api.listPaymentAnomalies).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
    expect(api.getReconciliationRun).toHaveBeenCalledTimes(reconciliationCalls)

    wrapper.getComponent('[data-test=reconciliation-provider]').vm.$emit('update:modelValue', 'WECHAT_PAY')
    await wrapper.get('[data-test=reconciliation-date]').setValue('2026-07-31')
    await wrapper.get('[data-test=start-reconciliation]').trigger('click')
    await flushPromises()
    const anomalyCalls = api.listPaymentAnomalies.mock.calls.length
    wrapper.getComponent('[data-test=reconciliation-pagination]').vm.$emit('current-change', 3)
    await flushPromises()
    expect(api.getReconciliationRun).toHaveBeenLastCalledWith('run-1', 2, 20, 'createdAt', 'desc')
    expect(api.listPaymentAnomalies).toHaveBeenCalledTimes(anomalyCalls)
  })

  it('validates and publishes a future immutable refund policy version', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2030-01-01T00:00:00Z'))
    api.listRefundPolicies
      .mockResolvedValueOnce({
        items: [{
          version: 1, feeBps: 500, minimumFee: 100, refundWindowDays: 7, maximumUsageBps: 1000,
          effectiveAt: '2026-08-01T00:00:00Z', published: true, createdBy: 'admin-1', createdAt: '2026-07-31T00:00:00Z',
        }],
        total: 1, page: 0, size: 20,
      })
      .mockResolvedValueOnce({
        items: [{
          version: 2, feeBps: 400, minimumFee: 100, refundWindowDays: 14, maximumUsageBps: 1200,
          effectiveAt: '2030-01-02T00:00:00Z', published: true, createdBy: 'admin-1', createdAt: '2030-01-01T00:00:00Z',
        }],
        total: 1, page: 0, size: 20,
      })
    const wrapper = mount(RefundPolicyPanel, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('版本 1')
    await wrapper.get('[data-test=policy-version]').setValue('2')
    await wrapper.get('[data-test=policy-fee-bps]').setValue('400')
    await wrapper.get('[data-test=policy-minimum-fee]').setValue('100')
    await wrapper.get('[data-test=policy-window-days]').setValue('14')
    await wrapper.get('[data-test=policy-maximum-usage-bps]').setValue('10001')
    await wrapper.get('[data-test=policy-effective-at]').setValue('2030-01-02T00:00:00Z')
    expect(wrapper.get('[data-test=publish-policy]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-test=policy-maximum-usage-bps]').setValue('1200')
    await wrapper.get('[data-test=publish-policy]').trigger('click')
    await flushPromises()

    expect(api.publishRefundPolicy).toHaveBeenCalledWith({
      version: 2, feeBps: 400, minimumFee: 100, refundWindowDays: 14,
      maximumUsageBps: 1200, effectiveAt: '2030-01-02T00:00:00Z',
    })
    expect(wrapper.text()).toContain('版本 2')
  })

  it('requires every refund policy field and an ISO instant accepted by the backend', async () => {
    const wrapper = mount(RefundPolicyPanel, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=policy-version]').setValue('2')
    await wrapper.get('[data-test=policy-window-days]').setValue('14')
    await wrapper.get('[data-test=policy-effective-at]').setValue('2099-01-01T00:00:00Z')
    expect(wrapper.get('[data-test=publish-policy]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-test=policy-fee-bps]').setValue('0')
    await wrapper.get('[data-test=policy-minimum-fee]').setValue('0')
    await wrapper.get('[data-test=policy-maximum-usage-bps]').setValue('0')
    await wrapper.get('[data-test=policy-effective-at]').setValue('2099-01-01')

    expect(wrapper.get('[data-test=publish-policy]').attributes('disabled')).toBeDefined()
  })

  it('rejects refund policy numbers the browser or backend cannot represent exactly', async () => {
    const wrapper = mount(RefundPolicyPanel, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=policy-version]').setValue('2147483648')
    await wrapper.get('[data-test=policy-fee-bps]').setValue('0')
    await wrapper.get('[data-test=policy-minimum-fee]').setValue('0')
    await wrapper.get('[data-test=policy-window-days]').setValue('14')
    await wrapper.get('[data-test=policy-maximum-usage-bps]').setValue('0')
    await wrapper.get('[data-test=policy-effective-at]').setValue('2099-01-01T00:00:00Z')
    expect(wrapper.get('[data-test=publish-policy]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-test=policy-version]').setValue('2')
    await wrapper.get('[data-test=policy-minimum-fee]').setValue('9007199254740992')

    expect(wrapper.get('[data-test=publish-policy]').attributes('disabled')).toBeDefined()
  })

  it('renders backend circuit keys and distinguishes an unsampled failure rate', async () => {
    api.getRoutingHealth.mockResolvedValueOnce({
      channels: [],
      circuits: [
        { key: 'channel-1:text-basic', state: 'CLOSED', failureRate: -1, bufferedCalls: 0 },
        { key: 'channel-2:vision-basic', state: 'CLOSED', failureRate: 12.5, bufferedCalls: 8 },
      ],
    })
    const wrapper = mount(OperationsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('channel-1:text-basic')
    expect(wrapper.text()).toContain('暂无统计样本')
    expect(wrapper.text()).toContain('channel-2:vision-basic')
    expect(wrapper.text()).toContain('失败率 12.5%')
    expect(wrapper.text()).not.toContain('1250.0%')
  })

  it('handles an unhealthy circuit through manual switch and automatic restore', async () => {
    const key = '11111111-1111-1111-1111-111111111111:text-basic'
    const target = {
      channelId: '22222222-2222-2222-2222-222222222222', channelCode: 'bailian-backup',
      channelName: '百炼备用渠道', providerName: '百炼', upstreamModel: 'qwen-plus',
      channelEnabled: true, mappingEnabled: true, healthStatus: 'HEALTHY',
      credentialConfigured: true, selected: false, eligible: true,
    }
    api.getRoutingHealth.mockResolvedValueOnce({
      channels: [], circuits: [{ key, state: 'OPEN', failureRate: 80, bufferedCalls: 5 }],
    })
    api.listRoutingTargets
      .mockResolvedValueOnce([target])
      .mockResolvedValueOnce([{ ...target, selected: true }])
      .mockResolvedValueOnce([target])
    api.switchRoutingCircuit.mockResolvedValue({ ...target, selected: true })
    api.restoreAutomaticRouting.mockResolvedValue(undefined)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')

    const wrapper = mount(OperationsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get(`[data-test="circuit-open-${key}"]`).trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('百炼备用渠道')
    await wrapper.get(`[data-test="circuit-switch-${target.channelId}"]`).trigger('click')
    await flushPromises()
    expect(api.switchRoutingCircuit).toHaveBeenCalledWith(key, target.channelId)
    expect(wrapper.get('[data-test=circuit-restore-automatic]').exists()).toBe(true)

    await wrapper.get('[data-test=circuit-restore-automatic]').trigger('click')
    await flushPromises()
    expect(api.restoreAutomaticRouting).toHaveBeenCalledWith(key)
  })

  it('uses the same compact tag size for gateway and routing health statuses', async () => {
    const wrapper = mount(OperationsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const gatewayTags = wrapper.findAll('.operations-metrics .el-tag')
    const routingTags = wrapper.findAll('.routing-grid .el-tag')
    expect(gatewayTags).toHaveLength(2)
    expect(routingTags.length).toBeGreaterThan(0)
    expect(gatewayTags.every((tag) => tag.classes().includes('el-tag--small'))).toBe(true)
    expect(routingTags.every((tag) => tag.classes().includes('el-tag--small'))).toBe(true)
  })

  it('requires confirmation before reservation recovery and shows the submitted business number', async () => {
    vi.spyOn(ElMessageBox, 'confirm')
      .mockRejectedValueOnce('cancel')
      .mockResolvedValueOnce('confirm')
    const wrapper = mount(OperationsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=operation-recover]').trigger('click')
    await flushPromises()
    expect(api.recoverReservations).not.toHaveBeenCalled()

    await wrapper.get('[data-test=operation-recover]').trigger('click')
    await flushPromises()
    expect(api.recoverReservations).toHaveBeenCalledWith('operation-visible-1')
    expect(wrapper.text()).toContain('operation-visible-1')
  })

  it('confirms and retries a failed outbox event with its visible business number', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mount(OperationsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=outbox-retry-outbox-1]').trigger('click')
    await flushPromises()

    expect(api.retryOutbox).toHaveBeenCalledWith('outbox-1', 'outbox-visible-1')
    expect(wrapper.text()).toContain('outbox-visible-1')
  })

  it('localizes outbox events and keeps the full error for hover inspection', async () => {
    const wrapper = mount(OperationsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.get('.outbox-scroll').text()).toContain('套餐发放')
    const error = wrapper.get('.outbox-error')
    expect(error.text()).toBe('处理失败')
    expect(error.attributes('title')).toBe('gateway timeout')
  })

  it('filters and pages only outbox while health and routing stay snapshot-based', async () => {
    const wrapper = mount(OperationsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    const gatewayCalls = api.getGatewayHealth.mock.calls.length
    const routingCalls = api.getRoutingHealth.mock.calls.length
    const outboxHealthCalls = api.getOutboxHealth.mock.calls.length

    await wrapper.get('[data-test=outbox-query]').setValue('grant-business')
    wrapper.getComponent('[data-test=outbox-status]').vm.$emit('update:modelValue', 'FAILED')
    wrapper.getComponent('[data-test=outbox-sort]').vm.$emit('update:modelValue', 'attemptCount')
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-test=outbox-filter]').trigger('click')
    await flushPromises()
    wrapper.getComponent('[data-test=outbox-pagination]').vm.$emit('current-change', 2)
    await flushPromises()

    expect(api.listOutbox).toHaveBeenLastCalledWith({
      page: 1, size: 20, query: 'grant-business', status: 'FAILED', sort: 'attemptCount', direction: 'desc',
    })
    expect(api.getGatewayHealth).toHaveBeenCalledTimes(gatewayCalls)
    expect(api.getRoutingHealth).toHaveBeenCalledTimes(routingCalls)
    expect(api.getOutboxHealth).toHaveBeenCalledTimes(outboxHealthCalls)
  })

  it('renders SMS template and delivery states without exposing protected payloads', async () => {
    const wrapper = mount(OperationsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(api.listSmsTemplates).toHaveBeenCalledTimes(1)
    expect(api.listSmsDeliveries).toHaveBeenCalledWith({
      page: 0, size: 20, query: '', status: '', templateKey: '', sort: 'createdAt', direction: 'desc',
    })
    expect(wrapper.get('[data-test=sms-templates-table]').text()).toContain('ORDER_PAYMENT_FAILED')
    expect(wrapper.get('[data-test=sms-templates-table]').text()).toContain('待接入')
    expect(wrapper.get('[data-test=sms-templates-table]').text()).toContain('2026-08-01 08:00:00')

    await wrapper.get('[data-test=sms-deliveries-tab]').trigger('click')
    await flushPromises()
    const deliveries = wrapper.get('[data-test=sms-deliveries-table]').text()
    expect(deliveries).toContain('*******8963')
    expect(deliveries).toContain('TRANSIENT:PROVIDER_NETWORK_ERROR')
    expect(deliveries).not.toContain('15012348963')
    expect(deliveries).not.toContain('654321')
    expect(deliveries).not.toContain('must-not-render')
    expect(wrapper.find('[data-test=sms-retry-sms-failed]').exists()).toBe(true)
    expect(wrapper.find('[data-test=sms-retry-sms-dead]').exists()).toBe(true)
    expect(wrapper.find('[data-test=sms-retry-sms-pending]').exists()).toBe(false)
  })

  it('keeps exact SMS delivery filters and pagination independent from outbox state', async () => {
    const wrapper = mount(OperationsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    const outboxCalls = api.listOutbox.mock.calls.length

    await wrapper.get('[data-test=sms-deliveries-tab]').trigger('click')
    expect(wrapper.get('[data-test=sms-query]').attributes('placeholder')).toBe(
      '精确查询投递 ID、手机后四位或模板键',
    )
    await wrapper.get('[data-test=sms-query]').setValue('8963')
    wrapper.getComponent('[data-test=sms-status]').vm.$emit('update:modelValue', 'DEAD')
    wrapper.getComponent('[data-test=sms-template]').vm.$emit('update:modelValue', 'ORDER_PAYMENT_FAILED')
    wrapper.getComponent('[data-test=sms-sort]').vm.$emit('update:modelValue', 'attemptCount')
    wrapper.getComponent('[data-test=sms-direction]').vm.$emit('update:modelValue', 'asc')
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-test=sms-filter]').trigger('click')
    await flushPromises()
    wrapper.getComponent('[data-test=sms-pagination]').vm.$emit('current-change', 2)
    await flushPromises()

    expect(api.listSmsDeliveries).toHaveBeenLastCalledWith({
      page: 1, size: 20, query: '8963', status: 'DEAD',
      templateKey: 'ORDER_PAYMENT_FAILED', sort: 'attemptCount', direction: 'asc',
    })
    expect(api.listOutbox).toHaveBeenCalledTimes(outboxCalls)
  })

  it('reloads the same filtered SMS delivery page after a successful retry', async () => {
    const failedDelivery = {
      id: 'sms-failed', templateKey: 'ORDER_PAYMENT_FAILED', templateCode: 'SMS_511295121',
      maskedPhone: '*******8963', status: 'FAILED', attemptCount: 3,
      nextAttemptAt: '2026-08-04T00:05:00Z', sentAt: null,
      sanitizedError: 'TRANSIENT:PROVIDER_NETWORK_ERROR',
      createdAt: '2026-08-04T00:00:00Z', updatedAt: '2026-08-04T00:01:00Z',
    }
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mount(OperationsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=sms-deliveries-tab]').trigger('click')

    api.listSmsDeliveries.mockResolvedValueOnce({
      items: [failedDelivery], total: 21, page: 0, size: 20,
    })
    await wrapper.get('[data-test=sms-query]').setValue('8963')
    wrapper.getComponent('[data-test=sms-status]').vm.$emit('update:modelValue', 'FAILED')
    wrapper.getComponent('[data-test=sms-sort]').vm.$emit('update:modelValue', 'attemptCount')
    wrapper.getComponent('[data-test=sms-direction]').vm.$emit('update:modelValue', 'asc')
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-test=sms-filter]').trigger('click')
    await flushPromises()

    api.listSmsDeliveries.mockResolvedValueOnce({
      items: [failedDelivery], total: 21, page: 1, size: 20,
    })
    wrapper.getComponent('[data-test=sms-pagination]').vm.$emit('current-change', 2)
    await flushPromises()

    api.listSmsDeliveries.mockResolvedValueOnce({ items: [], total: 21, page: 1, size: 20 })
    await wrapper.get('[data-test=sms-retry-sms-failed]').trigger('click')
    await flushPromises()

    expect(api.listSmsDeliveries).toHaveBeenCalledTimes(4)
    expect(api.listSmsDeliveries).toHaveBeenLastCalledWith({
      page: 1, size: 20, query: '8963', status: 'FAILED',
      templateKey: '', sort: 'attemptCount', direction: 'asc',
    })
    expect(wrapper.find('[data-test=sms-deliveries-empty]').exists()).toBe(true)
  })

  it('confirms SMS retry and disables the eligible row while the request is running', async () => {
    const confirmation = vi.spyOn(ElMessageBox, 'confirm')
      .mockRejectedValueOnce('cancel')
      .mockResolvedValueOnce('confirm')
    const pending = deferred<unknown>()
    api.retrySmsDelivery.mockReturnValueOnce(pending.promise)
    const wrapper = mount(OperationsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=sms-deliveries-tab]').trigger('click')

    await wrapper.get('[data-test=sms-retry-sms-failed]').trigger('click')
    await flushPromises()
    expect(api.retrySmsDelivery).not.toHaveBeenCalled()

    await wrapper.get('[data-test=sms-retry-sms-failed]').trigger('click')
    await flushPromises()
    expect(api.retrySmsDelivery).toHaveBeenCalledWith('sms-failed', 'sms-retry-visible-1')
    expect(wrapper.getComponent('[data-test=sms-retry-sms-failed]').props('loading')).toBe(true)
    expect(wrapper.getComponent('[data-test=sms-retry-sms-dead]').props('disabled')).toBe(true)

    const retriedDelivery = {
      id: 'sms-failed', templateKey: 'ORDER_PAYMENT_FAILED', templateCode: 'SMS_511295121',
      maskedPhone: '*******8963', status: 'PENDING', attemptCount: 0,
      nextAttemptAt: '2026-08-04T00:08:00Z', sentAt: null, sanitizedError: null,
      createdAt: '2026-08-04T00:00:00Z', updatedAt: '2026-08-04T00:08:00Z',
    }
    api.listSmsDeliveries.mockResolvedValueOnce({
      items: [retriedDelivery], total: 3, page: 0, size: 20,
    })
    pending.resolve(retriedDelivery)
    await flushPromises()
    expect(wrapper.find('[data-test=sms-retry-sms-failed]').exists()).toBe(false)
    expect(confirmation).toHaveBeenCalledTimes(2)
  })

  it('shows stable SMS loading, empty, and recoverable error states', async () => {
    const templates = deferred<unknown>()
    const deliveries = deferred<unknown>()
    api.listSmsTemplates.mockReturnValueOnce(templates.promise)
    api.listSmsDeliveries.mockReturnValueOnce(deliveries.promise)
    const wrapper = mount(OperationsView, { global: { plugins: [ElementPlus] } })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-test=sms-templates-loading]').exists()).toBe(true)

    templates.resolve([])
    deliveries.resolve({ items: [], total: 0, page: 0, size: 20 })
    await flushPromises()
    expect(wrapper.find('[data-test=sms-templates-empty]').exists()).toBe(true)
    await wrapper.get('[data-test=sms-deliveries-tab]').trigger('click')
    expect(wrapper.find('[data-test=sms-deliveries-empty]').exists()).toBe(true)

    api.listSmsTemplates.mockRejectedValueOnce(new Error('credential raw-response must-not-render'))
    api.listSmsDeliveries.mockRejectedValueOnce(new Error('phone=15012348963 code=654321'))
    await wrapper.get('[data-test=sms-refresh]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test=sms-error]').exists()).toBe(false)
    expect(document.body.textContent).toMatch(/短信.+加载失败/)
    expect(document.body.textContent).not.toContain('credential raw-response must-not-render')
    expect(document.body.textContent).not.toContain('15012348963')
  })

  it('keeps the newest overlapping SMS template and delivery refresh authoritative', async () => {
    const wrapper = mount(OperationsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    const oldTemplates = deferred<unknown>()
    const oldDeliveries = deferred<unknown>()
    const newTemplates = deferred<unknown>()
    const newDeliveries = deferred<unknown>()
    api.listSmsTemplates
      .mockReturnValueOnce(oldTemplates.promise)
      .mockReturnValueOnce(newTemplates.promise)
    api.listSmsDeliveries
      .mockReturnValueOnce(oldDeliveries.promise)
      .mockReturnValueOnce(newDeliveries.promise)

    const refresh = wrapper.getComponent('[data-test=sms-refresh]')
    refresh.vm.$emit('click', new MouseEvent('click'))
    await wrapper.vm.$nextTick()
    refresh.vm.$emit('click', new MouseEvent('click'))
    await wrapper.vm.$nextTick()

    oldTemplates.resolve([{
      templateKey: 'STALE_TEMPLATE', templateCode: 'SMS_STALE', templateType: 'NOTIFICATION',
      integrationStatus: 'ACTIVE', createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
    }])
    oldDeliveries.resolve({
      items: [{
        id: 'sms-stale', templateKey: 'STALE_TEMPLATE', templateCode: 'SMS_STALE',
        maskedPhone: '*******0000', status: 'FAILED', attemptCount: 1,
        nextAttemptAt: '2026-08-04T00:05:00Z', sentAt: null, sanitizedError: 'TRANSIENT:STALE',
        createdAt: '2026-08-04T00:00:00Z', updatedAt: '2026-08-04T00:01:00Z',
      }], total: 1, page: 0, size: 20,
    })
    await flushPromises()

    expect(wrapper.find('[data-test=sms-templates-loading]').exists()).toBe(true)
    expect(refresh.props('loading')).toBe(true)
    expect(wrapper.text()).not.toContain('STALE_TEMPLATE')

    newTemplates.resolve([{
      templateKey: 'LATEST_TEMPLATE', templateCode: 'SMS_LATEST', templateType: 'VERIFICATION',
      integrationStatus: 'ACTIVE', createdAt: '2026-08-02T00:00:00Z', updatedAt: '2026-08-02T00:00:00Z',
    }])
    newDeliveries.resolve({
      items: [{
        id: 'sms-latest', templateKey: 'LATEST_TEMPLATE', templateCode: 'SMS_LATEST',
        maskedPhone: '*******1234', status: 'FAILED', attemptCount: 2,
        nextAttemptAt: '2026-08-04T00:06:00Z', sentAt: null, sanitizedError: 'TRANSIENT:LATEST',
        createdAt: '2026-08-04T00:00:00Z', updatedAt: '2026-08-04T00:02:00Z',
      }], total: 1, page: 0, size: 20,
    })
    await flushPromises()

    expect(refresh.props('loading')).toBe(false)
    expect(wrapper.get('[data-test=sms-templates-table]').text()).toContain('LATEST_TEMPLATE')
    await wrapper.get('[data-test=sms-deliveries-tab]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test=sms-deliveries-table]').text()).toContain('LATEST_TEMPLATE')
    expect(wrapper.text()).not.toContain('STALE_TEMPLATE')
  })

  it('submits the exact administrator, action, object, outcome, and time audit filters', async () => {
    const wrapper = mount(AuditView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=audit-actor]').setValue('71000000-0000-4000-8000-000000000001')
    wrapper.getComponent('[data-test=audit-action]').vm.$emit('update:modelValue', 'PACKAGE_GRANT_CREATED')
    wrapper.getComponent('[data-test=audit-resource-type]').vm.$emit('update:modelValue', 'GRANT')
    await wrapper.get('[data-test=audit-resource-id]').setValue('grant-1')
    wrapper.getComponent('[data-test=audit-outcome]').vm.$emit('update:modelValue', 'SUCCESS')
    wrapper.getComponent('[data-test=audit-sort]').vm.$emit('update:modelValue', 'action')
    wrapper.getComponent('[data-test=audit-direction]').vm.$emit('update:modelValue', 'asc')
    wrapper.get('[data-test=audit-time]').getComponent({ name: 'ElDatePicker' }).vm.$emit('update:modelValue', [
      new Date('2026-07-30T00:00:00+08:00'), new Date('2026-07-31T00:00:00+08:00'),
    ])
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-test=audit-filter]').trigger('click')
    await flushPromises()

    expect(api.listAudit).toHaveBeenLastCalledWith({
      page: 0,
      size: 20,
      query: '',
      status: '',
      sort: 'action',
      direction: 'asc',
      actorUserId: '71000000-0000-4000-8000-000000000001',
      action: 'PACKAGE_GRANT_CREATED',
      resourceType: 'GRANT',
      resourceId: 'grant-1',
      outcome: 'SUCCESS',
      from: '2026-07-29T16:00:00.000Z',
      to: '2026-07-30T16:00:00.000Z',
    })
  })

  it('uses source-qualified identities for colliding audit row ids', async () => {
    api.listAudit.mockResolvedValueOnce({
      items: [
        { id: 1, source: 'PLATFORM', tenantId: 'tenant-1', actorUserId: null, action: 'USER_UPDATED', resourceType: 'USER', resourceId: 'user-1', requestId: 'platform-request', outcome: 'SUCCESS', detail: {}, createdAt: '2026-07-30T12:00:00Z' },
        { id: 1, source: 'GATEWAY', tenantId: 'tenant-1', actorUserId: null, action: 'GATEWAY_CHANNEL_UPDATED', resourceType: 'GATEWAY_CHANNEL', resourceId: 'channel-1', requestId: 'gateway-request', outcome: 'SUCCESS', detail: {}, createdAt: '2026-07-30T12:00:00Z' },
      ],
      total: 2,
      page: 0,
      size: 20,
    })

    const wrapper = mount(AuditView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.findAll('tbody tr').map((row) => row.attributes('data-audit-key')))
      .toEqual(['PLATFORM:1', 'GATEWAY:1'])
    expect(wrapper.text()).toContain('2026-07-30 20:00:00')
  })

  it('loads, filters, and paginates administrator wallet accounts with exact server parameters and CNY formatting', async () => {
    const WalletAdminView = await loadWalletAdminView()
    const wrapper = mount(WalletAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(walletApi.listAdminWalletAccounts).toHaveBeenCalledWith({
      page: 0, size: 20, query: '', status: '', from: null, to: null, sort: 'updatedAt', direction: 'desc',
    })
    expect(wrapper.get('[data-test=accounts-panel]').text()).toContain('¥123.45')
    expect(wrapper.get('[data-test=accounts-panel]').text()).toContain('U00000001')
    expect(wrapper.get('[data-test=accounts-panel]').text()).toContain('测试用户')
    expect(wrapper.get('[data-test=wallet-tab-accounts]').attributes('aria-selected')).toBe('true')

    walletApi.listAdminWalletAccounts.mockImplementation(async ({ page, size }) => ({
      items: [], total: 45, page, size,
    }))
    await wrapper.get('[data-test=accounts-query]').setValue(' U00000001 ')
    expect(wrapper.get('[data-test=filter-accounts]').attributes('type')).toBe('submit')
    await wrapper.get('[data-test=accounts-panel] form.filters').trigger('submit')
    await flushPromises()
    expect(walletApi.listAdminWalletAccounts).toHaveBeenLastCalledWith({
      page: 0, size: 20, query: 'U00000001', status: '', from: null, to: null,
      sort: 'updatedAt', direction: 'desc',
    })

    wrapper.getComponent('[data-test=accounts-pagination]').vm.$emit('current-change', 2)
    await flushPromises()
    expect(walletApi.listAdminWalletAccounts).toHaveBeenLastCalledWith({
      page: 1, size: 20, query: 'U00000001', status: '', from: null, to: null,
      sort: 'updatedAt', direction: 'desc',
    })
    expect(wrapper.getComponent('[data-test=accounts-pagination]').props('currentPage')).toBe(2)
  })

  it('keeps administrator wallet tabs, filters, and pages independent', async () => {
    const WalletAdminView = await loadWalletAdminView()
    const wrapper = mount(WalletAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=wallet-tab-recharges]').trigger('click')
    walletApi.listAdminWalletRecharges.mockImplementation((params) => Promise.resolve({
      items: [], total: 45, page: params.page ?? 0, size: params.size ?? 20,
    }))
    await wrapper.get('[data-test=recharges-query]').setValue('user-1')
    await wrapper.get('[data-test=recharges-status]').setValue('PAID')
    await wrapper.get('[data-test=filter-recharges]').trigger('click')
    wrapper.getComponent('[data-test=recharges-pagination]').vm.$emit('current-change', 2)
    await flushPromises()
    expect(walletApi.listAdminWalletRecharges).toHaveBeenLastCalledWith({
      page: 1, size: 20, query: 'user-1', status: 'PAID', from: null, to: null, sort: 'createdAt', direction: 'desc',
    })

    await wrapper.get('[data-test=wallet-tab-ledger]').trigger('click')
    walletApi.listAdminWalletLedger.mockImplementation((params) => Promise.resolve({
      items: [], total: 65, page: params.page ?? 0, size: params.size ?? 20,
    }))
    await wrapper.get('[data-test=ledger-query]').setValue('user-1')
    await wrapper.get('[data-test=ledger-entry-type]').setValue('RECHARGE')
    await wrapper.get('[data-test=filter-ledger]').trigger('click')
    wrapper.getComponent('[data-test=ledger-pagination]').vm.$emit('current-change', 3)
    await flushPromises()
    expect(walletApi.listAdminWalletLedger).toHaveBeenLastCalledWith({
      page: 2, size: 20, query: 'user-1', entryType: 'RECHARGE', from: null, to: null, sort: 'createdAt', direction: 'desc',
    })
    expect(wrapper.getComponent('[data-test=recharges-pagination]').props('currentPage')).toBe(2)
  })

  it('keeps the newest administrator wallet page authoritative', async () => {
    const older = deferred<{ items: never[]; total: number; page: number; size: number }>()
    const newer = deferred<{ items: never[]; total: number; page: number; size: number }>()
    const WalletAdminView = await loadWalletAdminView()
    const wrapper = mount(WalletAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    walletApi.listAdminWalletAccounts.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise)
    const pagination = wrapper.getComponent('[data-test=accounts-pagination]')
    pagination.vm.$emit('current-change', 2)
    pagination.vm.$emit('current-change', 3)
    newer.resolve({ items: [], total: 45, page: 2, size: 20 })
    await flushPromises()
    older.reject(new Error('obsolete page failure'))
    await flushPromises()

    expect(wrapper.getComponent('[data-test=accounts-pagination]').props('currentPage')).toBe(3)
    expect(wrapper.find('[data-test=accounts-error]').exists()).toBe(false)
  })

  it('shows independent administrator wallet empty and recoverable error states', async () => {
    walletApi.listAdminWalletAccounts.mockRejectedValueOnce(new Error('accounts unavailable'))
    walletApi.listAdminWalletRecharges.mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
    const WalletAdminView = await loadWalletAdminView()
    const wrapper = mount(WalletAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[data-test=accounts-error]').exists()).toBe(false)
    expect(document.body.textContent).toContain('余额账户加载失败')
    expect(wrapper.find('[data-test=retry-accounts]').exists()).toBe(true)
    await wrapper.get('[data-test=retry-accounts]').trigger('click')
    await flushPromises()
    expect(walletApi.listAdminWalletAccounts.mock.calls.filter(([params]) => params.size === 20)).toHaveLength(2)
    await wrapper.get('[data-test=wallet-tab-recharges]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test=recharges-empty]').text()).toContain('暂无充值订单')
  })

  it('validates and replaces administrator recharge options using integer minor units only', async () => {
    const WalletAdminView = await loadWalletAdminView()
    const wrapper = mount(WalletAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=wallet-tab-options]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=save-recharge-options]').trigger('click')
    await flushPromises()
    expect(walletApi.replaceAdminWalletRechargeOptions).toHaveBeenCalledWith([
      { amount: 1_000, enabled: true, displayOrder: 1 },
      { amount: 3_000, enabled: true, displayOrder: 2 },
    ])

    walletApi.replaceAdminWalletRechargeOptions.mockClear()
    await wrapper.get('[data-test=option-amount-1]').setValue('10.00')
    await wrapper.get('[data-test=save-recharge-options]').trigger('click')
    await flushPromises()
    expect(walletApi.replaceAdminWalletRechargeOptions).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test=options-error]').exists()).toBe(false)
    expect(document.body.textContent).toContain('充值金额不能重复')
  })

  it('keeps recharge options editable after validating a newly added blank amount', async () => {
    const WalletAdminView = await loadWalletAdminView()
    const wrapper = mount(WalletAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=wallet-tab-options]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-test=add-recharge-option]').trigger('click')
    await wrapper.get('[data-test=save-recharge-options]').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('充值金额须为')
    const optionsPanel = wrapper.get('[data-test=options-panel]')
    expect(optionsPanel.find('[data-test=options-error]').exists()).toBe(false)
    expect(optionsPanel.find('.wallet-admin-top-error').exists()).toBe(false)
    expect(wrapper.find('[data-test=option-amount-2]').exists()).toBe(true)

    await wrapper.get('[data-test=add-recharge-option]').trigger('click')
    expect(wrapper.find('[data-test=option-amount-3]').exists()).toBe(true)
  })

  it('allows empty options, display order zero, and duplicate display orders exactly like the backend', async () => {
    walletApi.listAdminWalletRechargeOptions.mockResolvedValueOnce([
      { id: 'option-10', amount: 1_000, enabled: true, displayOrder: 0, createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z' },
      { id: 'option-30', amount: 3_000, enabled: false, displayOrder: 0, createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z' },
    ])
    const WalletAdminView = await loadWalletAdminView()
    const wrapper = mount(WalletAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=wallet-tab-options]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=save-recharge-options]').trigger('click')
    await flushPromises()
    expect(walletApi.replaceAdminWalletRechargeOptions).toHaveBeenLastCalledWith([
      { amount: 1_000, enabled: true, displayOrder: 0 },
      { amount: 3_000, enabled: false, displayOrder: 0 },
    ])

    walletApi.replaceAdminWalletRechargeOptions.mockClear()
    await wrapper.get('[data-test=remove-recharge-option-1]').trigger('click')
    await wrapper.get('[data-test=remove-recharge-option-0]').trigger('click')
    await wrapper.get('[data-test=save-recharge-options]').trigger('click')
    await flushPromises()
    expect(walletApi.replaceAdminWalletRechargeOptions).toHaveBeenCalledWith([])
  })

  it.each([
    ['-1', '正整数范围'],
    ['2147483648', '正整数范围'],
    ['1.5', '正整数范围'],
  ])('rejects administrator recharge option display order %s outside the backend integer contract', async (displayOrder, message) => {
    const WalletAdminView = await loadWalletAdminView()
    const wrapper = mount(WalletAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=wallet-tab-options]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=option-display-order-0]').setValue(displayOrder)
    walletApi.replaceAdminWalletRechargeOptions.mockClear()
    await wrapper.get('[data-test=save-recharge-options]').trigger('click')
    await flushPromises()

    expect(walletApi.replaceAdminWalletRechargeOptions).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test=options-error]').exists()).toBe(false)
    expect(document.body.textContent).toContain(message)
  })

  it('exposes no administrator wallet balance mutation, withdrawal, recharge refund, or secrets', async () => {
    const source = await loadWalletAdminSource()
    expect(source).not.toMatch(/editBalance|updateBalance|adjustBalance|withdraw|refundRecharge|bonusAmount|apiKey|secret|credential/i)
    expect(source).not.toMatch(/data-test=["'][^"']*(withdraw|refund|balance-edit)/i)
    expect(source).toMatch(/overflow-x:\s*auto/)
    expect(source).toMatch(/min-height:\s*44px/)
  })
})
