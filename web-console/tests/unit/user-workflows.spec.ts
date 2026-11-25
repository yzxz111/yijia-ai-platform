import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import ApiKeysView from '../../src/views/user/ApiKeysView.vue'
import RegisterView from '../../src/views/auth/RegisterView.vue'
import LoginView from '../../src/views/auth/LoginView.vue'
import loginViewSource from '../../src/views/auth/LoginView.vue?raw'
import DashboardView from '../../src/views/user/DashboardView.vue'
import FilesView from '../../src/views/user/FilesView.vue'
import filesViewSource from '../../src/views/user/FilesView.vue?raw'
import OrdersView from '../../src/views/user/OrdersView.vue'
import ordersViewSource from '../../src/views/user/OrdersView.vue?raw'
import PlansView from '../../src/views/user/PlansView.vue'
import plansViewSource from '../../src/views/user/PlansView.vue?raw'
import CheckoutDialog from '../../src/components/payment/CheckoutDialog.vue'
import TopErrorMessage from '../../src/components/feedback/TopErrorMessage.vue'
import PaymentActionPane from '../../src/components/payment/PaymentActionPane.vue'
import RefundDialog from '../../src/components/refund/RefundDialog.vue'
import paymentActionSource from '../../src/components/payment/PaymentActionPane.vue?raw'
import ProfileView from '../../src/views/user/ProfileView.vue'
import QuotaView from '../../src/views/user/QuotaView.vue'
import UsageLogsView from '../../src/views/user/UsageLogsView.vue'
import HeaderBar from '../../src/components/layout/HeaderBar.vue'
import { createConsoleRouter } from '../../src/router'
import { listSubscriptions } from '../../src/api/subscriptions'
import { useApiKeysStore } from '../../src/stores/apiKeys'
import { useAuthStore } from '../../src/stores/auth'
import { usePackageStore } from '../../src/stores/package'
import { useUsageStore } from '../../src/stores/usage'
import { useWalletStore } from '../../src/stores/wallet'
import type { CreatedOrder, OrderDetail, RefundDetail } from '../../src/api/platform'

const api = vi.hoisted(() => ({
  changeApiKeyStatus: vi.fn(),
  createApiKey: vi.fn(),
  changePassword: vi.fn(),
  cancelRefund: vi.fn(),
  claimFreePlan: vi.fn(),
  closeOrder: vi.fn(),
  createOrder: vi.fn(),
  createPaymentAttempt: vi.fn(),
  getWalletSummary: vi.fn(),
  payOrderWithBalance: vi.fn(),
  deleteFile: vi.fn(),
  getFreePlanClaimStatus: vi.fn(),
  getUsageAnalytics: vi.fn(),
  getProfile: vi.fn(),
  getPublishedLegalDocument: vi.fn(),
  getOrderDetail: vi.fn(),
  getRefund: vi.fn(),
  listFiles: vi.fn(),
  listApiKeys: vi.fn(),
  listEligibleApiKeyModels: vi.fn(),
  listUsage: vi.fn(),
  listPlans: vi.fn(),
  listOrders: vi.fn(),
  login: vi.fn(),
  identityLogin: vi.fn(),
  emailIdentityLogin: vi.fn(),
  completeEmailRegistration: vi.fn(),
  requestIdentitySms: vi.fn(),
  requestEmailChallenge: vi.fn(),
  getPublishedLegalDocument: vi.fn(),
  logout: vi.fn(),
  quotaSummary: vi.fn(),
  register: vi.fn(),
  requestPhoneChallenge: vi.fn(),
  requestLoginSms: vi.fn(),
  requestRegistrationSms: vi.fn(),
  requestSliderCaptcha: vi.fn(),
  requestSmsChallenge: vi.fn(),
  requestRefund: vi.fn(),
  resetApiKey: vi.fn(),
  revokeApiKey: vi.fn(),
  bindPhone: vi.fn(),
  bindEmail: vi.fn(),
  updateSmsPreference: vi.fn(),
  updateDisplayName: vi.fn(),
  updateLoginDeviceLimit: vi.fn(),
  usageMetrics: vi.fn(),
  verifySmsChallenge: vi.fn(),
  verifySliderCaptcha: vi.fn(),
  smsLogin: vi.fn(),
}))

vi.mock('../../src/api/wallet', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/api/wallet')>()),
  getWalletSummary: api.getWalletSummary,
  payOrderWithBalance: api.payOrderWithBalance,
}))

vi.mock('../../src/api/platform', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/api/platform')>()),
  cancelRefund: api.cancelRefund,
  claimFreePlan: api.claimFreePlan,
  closeOrder: api.closeOrder,
  createOrder: api.createOrder,
  createPaymentAttempt: api.createPaymentAttempt,
  getFreePlanClaimStatus: api.getFreePlanClaimStatus,
  getOrderDetail: api.getOrderDetail,
  getRefund: api.getRefund,
  listPlans: api.listPlans,
  listOrders: api.listOrders,
  login: api.login,
  identityLogin: api.identityLogin,
  emailIdentityLogin: api.emailIdentityLogin,
  completeEmailRegistration: api.completeEmailRegistration,
  requestIdentitySms: api.requestIdentitySms,
  requestEmailChallenge: api.requestEmailChallenge,
  getPublishedLegalDocument: api.getPublishedLegalDocument,
  logout: api.logout,
  getProfile: api.getProfile,
  getPublishedLegalDocument: api.getPublishedLegalDocument,
  changePassword: api.changePassword,
  register: api.register,
  requestPhoneChallenge: api.requestPhoneChallenge,
  requestLoginSms: api.requestLoginSms,
  requestRegistrationSms: api.requestRegistrationSms,
  requestSliderCaptcha: api.requestSliderCaptcha,
  requestSmsChallenge: api.requestSmsChallenge,
  requestRefund: api.requestRefund,
  bindPhone: api.bindPhone,
  bindEmail: api.bindEmail,
  updateSmsPreference: api.updateSmsPreference,
  updateDisplayName: api.updateDisplayName,
  updateLoginDeviceLimit: api.updateLoginDeviceLimit,
  verifySmsChallenge: api.verifySmsChallenge,
  verifySliderCaptcha: api.verifySliderCaptcha,
  smsLogin: api.smsLogin,
}))

const qr = vi.hoisted(() => ({ toDataURL: vi.fn() }))

vi.mock('../../src/api/http', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/api/http')>()),
  restoreSession: vi.fn().mockResolvedValue(false),
}))

vi.mock('qrcode', () => ({ default: qr, toDataURL: qr.toDataURL }))

vi.mock('../../src/api/gateway', () => ({
  changeApiKeyStatus: api.changeApiKeyStatus,
  createApiKey: api.createApiKey,
  deleteFile: api.deleteFile,
  listFiles: api.listFiles,
  listApiKeys: api.listApiKeys,
  listEligibleApiKeyModels: api.listEligibleApiKeyModels,
  listUsage: api.listUsage,
  getUsageAnalytics: api.getUsageAnalytics,
  quotaSummary: api.quotaSummary,
  resetApiKey: api.resetApiKey,
  revokeApiKey: api.revokeApiKey,
  usageMetrics: api.usageMetrics,
}))

/**
 * 订阅接口也必须被替身化。
 *
 * `OrdersView` 与 `DashboardView` 会在挂载时读取活跃订阅（用于把「下次续费」显示成
 * 真实的扣款时刻）。这个文件在模块级 import 了那两个视图，因此不 mock 它的话，
 * 每个挂载它们的用例都会走真实 HTTP 客户端并留下未处理的拒绝——
 * **症状是别的用例失败，而不是这些用例失败**，定位成本很高。
 * 默认返回空列表即「没有订阅」，视图回退到既有的套餐到期日展示。
 */
vi.mock('../../src/api/subscriptions', () => ({
  listSubscriptions: vi.fn().mockResolvedValue([]),
  getSubscription: vi.fn().mockResolvedValue({ subscription: null, cycles: [] }),
  openSubscription: vi.fn(),
  cancelSubscription: vi.fn(),
  resumeSubscription: vi.fn(),
  listAdminSubscriptions: vi.fn().mockResolvedValue({ items: [], total: 0, page: 0, size: 20 }),
  getAdminSubscription: vi.fn(),
  terminateSubscription: vi.fn(),
  retrySubscriptionCycle: vi.fn(),
}))

const smsComponents = import.meta.glob('../../src/components/sms/*.vue')
const smsComponentSources = import.meta.glob('../../src/components/sms/*.vue', {
  import: 'default',
  query: '?raw',
})

const sliderCaptchaStub = {
  template: '<div data-test="slider-captcha-stub" />',
  methods: {
    open: () => Promise.resolve('captcha-proof'),
  },
}

/** el-dialog 会被 teleport 到 <body>，并在一个微任务 tick 之后挂载。 */
async function waitForElement(selector: string): Promise<Element | null> {
  for (let attempt = 0; attempt < 10; attempt += 1) {
    const found = document.querySelector(selector)
    if (found) return found
    await nextTick()
    await flushPromises()
  }
  return document.querySelector(selector)
}

function user() {
  return {
    id: '71000000-0000-4000-8000-000000000001',
    tenantId: '61000000-0000-4000-8000-000000000001',
    email: 'user@example.com',
    displayName: '测试用户',
    role: 'USER' as const,
    passwordSet: true,
  }
}

function quotaFixture() {
  return {
    activePackage: {
      packageCode: 'ADVANCED',
      packageName: '高级版',
      totalTokens: 10_000,
      reservedTokens: 500,
      usedTokens: 3_000,
      remainingTokens: 6_500,
      expiresAt: '2026-08-30T00:00:00Z',
      modelQuotas: [
        { modelCode: 'deepseek-chat', totalTokens: 4_000, reservedTokens: 200, usedTokens: 1_000, remainingTokens: 2_800 },
      ],
    },
    queuedPackages: [],
  }
}

/**
 * An ACTIVE subscription. Only the fields the two views read are meaningful here; the
 * rest exist so the fixture satisfies SubscriptionSummary.
 */
function subscriptionFixture() {
  return {
    id: 'sub-1',
    packagePlanId: 'plan-1',
    planName: '入门版',
    status: 'ACTIVE' as const,
    autoRenew: true,
    cancelAtPeriodEnd: false,
    currentPeriodStart: '2026-09-01T00:00:00Z',
    currentPeriodEnd: '2026-10-01T00:00:00Z',
    nextChargeAt: '2026-09-30T00:00:00Z',
    lastChargedAmount: 2900,
    renewalCount: 0,
    consecutiveFailures: 0,
    graceUntil: null,
    lastSkipReason: null,
  }
}

/** An ISO instant N days from now, used to make recency assertions unambiguous. */
function expiresAtInDays(days: number) {
  return new Date(Date.now() + days * 24 * 60 * 60 * 1000).toISOString()
}

function usageFixture() {
  return {
    items: [
      { id: 'usage-1', requestId: 'req_123', modelCode: 'deepseek-chat', inputTokens: 120, outputTokens: 80, totalTokens: 200, durationMs: 310, httpStatus: 200, outcome: 'SUCCEEDED', createdAt: '2026-07-30T04:00:00Z', stages: [{ stageIndex: 0, stageType: 'FINAL_RESPONSE', logicalModelCode: 'vision-basic', providerCode: 'stage-provider-only', channelCode: 'stage-channel-only', upstreamModel: 'stage-upstream-only', inputTokens: 120, outputTokens: 80, totalTokens: 200, durationMs: 300, outcome: 'SUCCEEDED', providerCostAmount: 7, providerCostCurrency: 'CNY', priceEstimated: false }] },
      { id: 'usage-2', requestId: 'req_456', modelCode: 'qwen-max', inputTokens: 80, outputTokens: 20, totalTokens: 100, durationMs: 120, httpStatus: 200, outcome: 'SUCCEEDED', createdAt: '2026-07-30T03:00:00Z' },
    ],
    total: 2,
    page: 0,
    size: 20,
  }
}

function orderFixture() {
  return {
    items: [{
      id: 'order-id', orderNo: 'order-user-001', packageCode: 'STARTER', packageName: '入门版',
      amount: 12_345, currency: 'CNY', status: 'PAID', paymentProvider: 'UNSELECTED',
      paymentStatus: 'SUCCEEDED', refundStatus: 'PENDING_APPROVAL', paidAt: '2026-07-30T04:00:00Z',
      createdAt: '2026-07-30T03:00:00Z', updatedAt: '2026-07-30T04:00:00Z',
    }],
    total: 45,
    page: 0,
    size: 20,
  }
}

function paidPlan() {
  return {
    code: 'STARTER', name: '入门版', description: '适合个人开发者', kind: 'STANDARD', displayOrder: 20,
    versionId: 'version-1', version: 3,
    priceAmount: 9900, currency: 'CNY', totalTokenQuota: 10_000, durationDays: 30,
    maxApiKeys: 3, requestsPerMinute: 20, tokensPerMinute: 10_000, maxConcurrency: 2,
    oncePerUser: false, estimatedCostAmount: 3000, estimatedMarginRatio: 0.69, marginWarning: false, models: [],
  }
}

function createdOrder(): CreatedOrder {
  return {
    orderNo: 'ord_1', status: 'PENDING' as const, amount: 12_345, currency: 'CNY',
    expiresAt: '2026-08-02T12:01:05Z',
    packageSnapshot: {
      packageVersionId: 'version-snapshot-42', packageCode: 'STARTER_SNAPSHOT', packageName: '服务端快照套餐',
      priceAmount: 12_345, currency: 'CNY', totalTokenQuota: 88_888, durationDays: 45,
      maxApiKeys: 4, requestsPerMinute: 50, tokensPerMinute: 25_000, maxConcurrency: 4,
      dailyRequestLimit: null, maxOutputTokens: 8192, maxFileSizeBytes: 10_485_760,
      oncePerUser: false,
      models: [{ modelCode: 'qwen-max', modelTokenQuota: null, capabilities: ['chat'] }],
    },
  }
}

function orderDetail(overrides: Partial<OrderDetail> = {}): OrderDetail {
  return {
    order: {
      ...orderFixture().items[0], status: 'PENDING', paymentStatus: 'PENDING', refundStatus: null,
    },
    packageSnapshot: createdOrder().packageSnapshot,
    expiresAt: createdOrder().expiresAt,
    paymentAttempts: [{
      transactionNo: 'txn_1', provider: 'WECHAT_PAY', product: 'WECHAT_NATIVE', status: 'PENDING',
      providerStatus: null, providerErrorCode: null, paidAt: null, createdAt: '2026-08-02T12:00:00Z',
    }],
    grant: null,
    refund: null,
    ...overrides,
  }
}

function refundDetail(status: RefundDetail['status'] = 'PENDING_APPROVAL'): RefundDetail {
  return {
    refundNo: 'ref_1', orderNo: 'ord_1', status, refundType: 'PARTIAL', amount: 7425,
    reason: '不再需要该套餐',
    calculation: {
      paidAmount: 9900, paidAt: '2026-08-01T12:00:00Z', requestedAt: '2026-08-02T12:00:00Z',
      totalTokens: 10_000, usedTokens: 2_000, remainingTokens: 8_000, feeBps: 500,
      minimumFee: 100, maximumUsageBps: 8000, refundWindowDays: 7, baseRefund: 7920,
      fee: 495, refundAmount: 7425, refundType: 'PARTIAL', rounding: 'HALF_UP', policyVersion: 1,
      calculatedAt: '2026-08-02T12:00:00Z', packageInstanceId: 'pkg_1', originalPackageStatus: 'ACTIVE',
      packageExpiresAt: '2026-08-31T12:00:00Z', gatewaySnapshotAt: '2026-08-02T12:00:00Z',
    },
    reviewedBy: null, reviewedAt: null, reviewNote: null, failureReason: null,
    createdAt: '2026-08-02T12:00:00Z', updatedAt: '2026-08-02T12:00:00Z',
  }
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

async function authenticatedRouterAt(path: string) {
  useAuthStore().setSession('access-token', user())
  const router = createConsoleRouter(createMemoryHistory())
  await router.push(path)
  return router
}

function usagePageCalls() {
  return api.listUsage.mock.calls.filter(([filters]) => filters?.size === 20)
}

describe('user workflows', () => {
  beforeEach(() => {
    vi.useRealTimers()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
    vi.clearAllMocks()
    // 存储基线必须干净。本文件里有两条断言检查「这次验证流程没有往
    // localStorage / sessionStorage 写任何东西」（见下面 API key 重置的两条用例），
    // 它们要测的是**本次流程**有没有写，而不是整个文件跑到现在攒下了什么。
    // 不去清空的话，更早的用例留下的 key（终端的跨标签页会话通道
    // terminalSessionChannel、主题偏好 stores/theme 都会写）会让那两条断言
    // 按执行顺序时红时绿——单跑本文件时它们一直是绿的。
    localStorage.clear()
    sessionStorage.clear()
    api.getPublishedLegalDocument.mockResolvedValue({ version: '2026-09-15', content: {} })
    api.getWalletSummary.mockResolvedValue({
      availableAmount: 12_345, currency: 'CNY', updatedAt: '2026-08-05T00:00:00Z',
    })
    api.getProfile.mockResolvedValue({ phoneVerified: false })
    // 合并保留两侧：本地新增的套餐额度 mock 与对方新增的已发布法律文本 mock 互不冲突。
    api.quotaSummary.mockResolvedValue({ activePackage: null, queuedPackages: [] })
    api.getPublishedLegalDocument.mockImplementation(async (kind: string) => ({
      kind,
      version: '2026-09-15',
      title: kind === 'TERMS' ? '平台服务协议' : '隐私政策',
      content: { sections: [] },
    }))
    api.payOrderWithBalance.mockResolvedValue({
      orderNo: 'ord_1', orderStatus: 'PAID', settlementMethod: 'BALANCE',
      balanceBefore: 12_345, balanceAfter: 0, grantRequestId: 'grant-balance', grantStatus: 'ACTIVE',
    })
    api.getFreePlanClaimStatus.mockResolvedValue({ claimed: false })
    api.listEligibleApiKeyModels.mockResolvedValue(['text-basic'])
    api.requestSliderCaptcha.mockResolvedValue({
      id: 'captcha-challenge',
      backgroundImage: 'data:image/png;base64,YmFja2dyb3VuZA==',
      pieceImage: 'data:image/png;base64,cGllY2U=',
      pieceY: 48,
      imageWidth: 320,
      imageHeight: 160,
      pieceSize: 48,
      expiresAt: '2026-08-18T08:02:00Z',
    })
    api.verifySliderCaptcha.mockResolvedValue({
      captchaProof: 'captcha-proof',
      expiresAt: '2026-08-18T08:02:00Z',
    })
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({
      matches: false,
      media: '',
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }))
  })

  it('claims a semantic free package once and keeps a historical claim disabled after refresh', async () => {
    api.listPlans.mockResolvedValue([{ ...paidPlan(), code: 'PKG_TRIAL', name: '体验套餐', kind: 'FREE_CLAIM', priceAmount: 0, oncePerUser: true }])
    api.quotaSummary.mockResolvedValue({ activePackage: null, queuedPackages: [] })
    api.claimFreePlan.mockResolvedValue({ id: 'grant-1', eventId: 'event-1', businessNo: 'FREE', status: 'PENDING' })
    const wrapper = mount(PlansView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const claim = wrapper.get('[data-test=claim-free]')
    expect(claim.text()).toContain('免费领取')
    await claim.trigger('click')
    await flushPromises()
    expect(api.claimFreePlan).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-test=claim-free]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test=claim-free]').text()).toContain('已领取')

    wrapper.unmount()
    api.getFreePlanClaimStatus.mockResolvedValue({ claimed: true })
    api.quotaSummary.mockResolvedValue({ activePackage: null, queuedPackages: [] })
    const claimedWrapper = mount(PlansView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(claimedWrapper.get('[data-test=claim-free]').attributes('disabled')).toBeDefined()
    expect(claimedWrapper.get('[data-test=claim-free]').text()).toContain('已领取')
    expect(api.getFreePlanClaimStatus).toHaveBeenCalledTimes(2)
  })

  it('ignores an obsolete unclaimed status after a successful free claim', async () => {
    const staleStatus = deferred<{ claimed: boolean }>()
    api.listPlans.mockResolvedValue([{ ...paidPlan(), code: 'PKG_TRIAL', name: '体验套餐', kind: 'FREE_CLAIM', priceAmount: 0, oncePerUser: true }])
    api.quotaSummary.mockResolvedValue({ activePackage: null, queuedPackages: [] })
    api.getFreePlanClaimStatus.mockImplementation(() => staleStatus.promise)
    api.claimFreePlan.mockResolvedValue({ id: 'grant-1', eventId: 'event-1', businessNo: 'free:user', status: 'QUEUED' })
    const wrapper = mount(PlansView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=claim-free]').trigger('click')
    await flushPromises()
    staleStatus.resolve({ claimed: false })
    await flushPromises()

    expect(api.getFreePlanClaimStatus).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-test=claim-free]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test=claim-free]').text()).toContain('已领取')
  })

  it('renders a video package without ever printing its second quota', async () => {
    // VIDEO_SECOND 套餐按秒计量，而面向客户的控制台完全不展示
    // 该数字：卡片只说明套餐如何计费，绝不说包含多少秒。
    // 每秒单价也随之隐藏——卡片显示的是总价，
    // 总价／每秒单价就会把秒数直接交还回去。
    api.listPlans.mockResolvedValue([{
      ...paidPlan(),
      code: 'VIDEO_STARTER',
      name: 'Video Starter',
      priceAmount: 12_900,
      totalTokenQuota: 60,
      usageDimension: 'VIDEO_SECOND',
      tokensPerMinute: 60,
      models: [
        { modelCode: 'video-basic', modelTokenQuota: null, capabilities: ['TEXT', 'IMAGE', 'VIDEO'] },
      ],
    }])
    api.quotaSummary.mockResolvedValue({ activePackage: null, queuedPackages: [] })

    const wrapper = mount(PlansView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const card = wrapper.get('[data-plan-code=VIDEO_STARTER]').text()
    expect(card).toContain('按秒计费')
    expect(card).not.toContain('秒额度')
    expect(card).not.toContain('积分额度')
    // "单次视频 1–60 秒" is the per-request bound and stays; "60 秒额度" is the quota and
    // 不得出现（上一条断言覆盖措辞，本条覆盖配对关系）。
    expect(card).toContain('单次视频 1–60 秒')
    expect(card).not.toMatch(/60\s*秒额度/)
    expect(card).not.toContain('折合每秒')
    // 每秒每分钟上限不得标注为 TPM。
    expect(card).toContain('SPM 60')
    // 视频别名使用自己的标签，而不是原始代码。
    expect(card).toContain('视频模型 · 720P')
    // 该按钮仍会打开收银台，所以视频套餐可以购买。
    expect(wrapper.find('[data-plan-code=VIDEO_STARTER] [data-test=buy-plan]').exists()).toBe(true)
  })

  it('opens paid checkout with an immutable package summary', async () => {
    api.listPlans.mockResolvedValue([paidPlan()])
    api.quotaSummary.mockResolvedValue({ activePackage: null, queuedPackages: [] })
    api.createOrder.mockResolvedValue(createdOrder())
    const wrapper = mount(PlansView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=buy-plan]').trigger('click')
    await flushPromises()

    const summary = wrapper.get('[data-test=checkout-summary]').text()
    expect(summary).toContain('入门版')
    expect(summary).toContain('10,000')
    expect(summary).toContain('30 天')
    expect(summary).toContain('¥99')

    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    const snapshotSummary = wrapper.get('[data-test=checkout-summary]').text()
    expect(snapshotSummary).toContain('服务端快照套餐')
    expect(snapshotSummary).toContain('88,888')
    expect(snapshotSummary).toContain('45 天')
    expect(snapshotSummary).toContain('¥123.45')
  })

  it('shows the safe backend reason when an order cannot be created', async () => {
    const errorMessage = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    api.createOrder.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: {
          code: 'package_quota_remaining',
          message: '当前套餐仍有剩余额度或正在排队，暂时无法重复购买。',
        },
      },
    })
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() },
      global: { plugins: [ElementPlus], components: { TopErrorMessage } },
    })
    await flushPromises()

    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()

    expect(errorMessage).toHaveBeenCalledWith(expect.objectContaining({
      message: '当前套餐仍有剩余额度或正在排队，暂时无法重复购买。',
    }))
  })

  it('keeps an unknown order creation failure generic', async () => {
    const errorMessage = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    api.createOrder.mockRejectedValue({
      isAxiosError: true,
      response: { data: { code: 'internal_order_failure', message: 'internal database details' } },
    })
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() },
      global: { plugins: [ElementPlus], components: { TopErrorMessage } },
    })
    await flushPromises()

    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()

    expect(errorMessage).toHaveBeenCalledWith(expect.objectContaining({
      message: '订单创建失败，请稍后重试。',
    }))
  })

  it('keeps unknown order errors generic without exposing server details', async () => {
    const errorMessage = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    api.createOrder.mockRejectedValue({ isAxiosError: true, response: { data: { code: 'internal_database_error', message: 'private-database-details' } } })
    const wrapper = mount(CheckoutDialog, { props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus], components: { TopErrorMessage } } })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    expect(errorMessage).toHaveBeenCalledWith(expect.objectContaining({ message: '订单创建失败，请稍后重试。' }))
    expect(JSON.stringify(errorMessage.mock.calls)).not.toContain('private-database-details')
    wrapper.unmount()
  })

  it('selects provider products by device and polls every two seconds until paid', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    api.createOrder.mockResolvedValue(createdOrder())
    api.createPaymentAttempt.mockResolvedValue({
      transactionNo: 'txn_1', status: 'PENDING', expiresAt: createdOrder().expiresAt,
      action: { type: 'NONE', expiresAt: createdOrder().expiresAt },
    })
    api.getOrderDetail.mockResolvedValueOnce(orderDetail()).mockResolvedValueOnce(orderDetail({
      order: { ...orderDetail().order, status: 'PAID', paymentStatus: 'SUCCEEDED' },
      grant: { grantRequestId: 'grant-1', status: 'ACTIVE', updatedAt: '2026-08-02T12:00:02Z' },
    }))
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(0)

    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test=checkout-summary]').text()).toContain('服务端快照套餐')
    expect(wrapper.get('[data-test=payment-countdown]').text()).toBe('01:05')
    await wrapper.get('[data-test=start-payment]').trigger('click')
    await flushPromises()
    expect(api.createPaymentAttempt).toHaveBeenCalledWith('ord_1', 'WECHAT_PAY', 'WECHAT_NATIVE')

    await vi.advanceTimersByTimeAsync(2000)
    expect(api.getOrderDetail).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(2000)
    expect(api.getOrderDetail).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(6000)
    expect(api.getOrderDetail).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('支付成功')
    expect(wrapper.get('[data-test=checkout-grant-status]').attributes('data-status')).toBe('ACTIVE')
    expect(wrapper.get('[data-test=checkout-grant-status]').text()).toContain('已生效')
    expect(wrapper.text()).toContain('无需创建新的 API 密钥')
    wrapper.unmount()
  })

  it('uses the desktop Alipay page product', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    api.createOrder.mockResolvedValue(createdOrder())
    api.createPaymentAttempt.mockResolvedValue({
      transactionNo: 'txn_alipay', status: 'PENDING', expiresAt: createdOrder().expiresAt,
      action: { type: 'NONE', expiresAt: createdOrder().expiresAt },
    })
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(0)
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    wrapper.getComponent({ name: 'ElSegmented' }).vm.$emit('update:modelValue', 'ALIPAY')
    await wrapper.get('[data-test=start-payment]').trigger('click')
    await flushPromises()

    expect(api.createPaymentAttempt).toHaveBeenCalledWith('ord_1', 'ALIPAY', 'ALIPAY_PAGE')
    wrapper.unmount()
  })

  it('uses mobile H5 and WAP products and stops polling on unmount', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    const mobileUserAgent = vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('iPhone Mobile')
    api.createOrder.mockResolvedValue(createdOrder())
    api.createPaymentAttempt.mockResolvedValue({
      transactionNo: 'txn_1', status: 'PENDING', expiresAt: createdOrder().expiresAt,
      action: { type: 'NONE', expiresAt: createdOrder().expiresAt },
    })
    api.getOrderDetail.mockResolvedValue(orderDetail())
    const wechatWrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(0)
    await wechatWrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    await wechatWrapper.get('[data-test=start-payment]').trigger('click')
    await flushPromises()
    expect(api.createPaymentAttempt).toHaveBeenLastCalledWith('ord_1', 'WECHAT_PAY', 'WECHAT_H5')
    wechatWrapper.unmount()

    const alipayWrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(0)
    await alipayWrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    alipayWrapper.getComponent({ name: 'ElSegmented' }).vm.$emit('update:modelValue', 'ALIPAY')
    await alipayWrapper.get('[data-test=start-payment]').trigger('click')
    await flushPromises()
    expect(api.createPaymentAttempt).toHaveBeenLastCalledWith('ord_1', 'ALIPAY', 'ALIPAY_WAP')
    alipayWrapper.unmount()
    api.getOrderDetail.mockClear()
    await vi.advanceTimersByTimeAsync(6000)
    expect(api.getOrderDetail).not.toHaveBeenCalled()
    mobileUserAgent.mockRestore()
  })

  it('stops the fixed-width server countdown at zero', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
    api.createOrder.mockResolvedValue({ ...createdOrder(), expiresAt: '2026-08-02T12:00:02Z' })
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(0)
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test=payment-countdown]').text()).toBe('00:02')

    await vi.advanceTimersByTimeAsync(2000)
    expect(wrapper.get('[data-test=payment-countdown]').text()).toBe('00:00')
    await vi.advanceTimersByTimeAsync(10_000)
    expect(wrapper.get('[data-test=payment-countdown]').text()).toBe('00:00')
    wrapper.unmount()
  })

  it('renders the paid result returned by query-before-close', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
    api.createOrder.mockResolvedValue(createdOrder())
    api.closeOrder.mockResolvedValue({
      orderNo: 'ord_1', orderStatus: 'PAID', paymentStatus: 'SUCCEEDED', retryScheduled: false,
    })
    api.getOrderDetail.mockResolvedValue(orderDetail({
      order: { ...orderDetail().order, status: 'PAID', paymentStatus: 'SUCCEEDED' },
      paymentAttempts: [{ ...orderDetail().paymentAttempts[0], status: 'SUCCEEDED' }],
      grant: { grantRequestId: 'grant-close-race', status: 'ACTIVE', updatedAt: '2026-08-02T12:00:01Z' },
    }))
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=close-order]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('支付成功')
    expect(wrapper.text()).not.toContain('订单已关闭')
    expect(wrapper.emitted('paid')).toHaveLength(1)
    wrapper.unmount()
  })

  it('keeps polling when query-before-close remains uncertain', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
    api.createOrder.mockResolvedValue(createdOrder())
    api.closeOrder.mockResolvedValue({
      orderNo: 'ord_1', orderStatus: 'PENDING', paymentStatus: 'PENDING', retryScheduled: true,
    })
    api.getOrderDetail.mockResolvedValue(orderDetail())
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=close-order]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('支付结果确认中，请勿重复下单')
    expect(wrapper.find('[data-test=create-new-order]').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(2000)
    expect(api.getOrderDetail).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('renders a confirmed closed result and stops polling', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
    api.createOrder.mockResolvedValue(createdOrder())
    api.closeOrder.mockResolvedValue({
      orderNo: 'ord_1', orderStatus: 'CLOSED', paymentStatus: 'CLOSED', retryScheduled: false,
    })
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=close-order]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('订单已关闭')
    await vi.advanceTimersByTimeAsync(6000)
    expect(api.getOrderDetail).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it.each(['resolve', 'reject'] as const)(
    'ignores a stale create-order %s after checkout resets for another plan',
    async (outcome) => {
      const staleCreate = deferred<CreatedOrder>()
      const currentCreate = deferred<CreatedOrder>()
      api.createOrder
        .mockImplementationOnce(() => staleCreate.promise)
        .mockImplementationOnce(() => currentCreate.promise)
      const wrapper = mount(CheckoutDialog, {
        props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
      })
      await flushPromises()
      await wrapper.get('[data-test=checkout-create-order]').trigger('click')
      await flushPromises()

      const replacementPlan = {
        ...paidPlan(), code: 'ADVANCED', name: '高级版', versionId: 'version-2',
      }
      await wrapper.setProps({ modelValue: false })
      await wrapper.setProps({ plan: replacementPlan, modelValue: true })
      await flushPromises()
      await wrapper.get('[data-test=checkout-create-order]').trigger('click')
      await flushPromises()

      if (outcome === 'resolve') {
        staleCreate.resolve(createdOrder())
      } else {
        staleCreate.reject(new Error('obsolete create failure'))
      }
      await flushPromises()

      const currentButton = wrapper.getComponent('[data-test=checkout-create-order]')
      expect(currentButton.props('loading')).toBe(true)
      expect(wrapper.text()).toContain('高级版')
      expect(wrapper.text()).not.toContain('ord_1')
      expect(wrapper.text()).not.toContain('订单创建失败')

      currentCreate.resolve({
        ...createdOrder(), orderNo: 'ord_2',
        packageSnapshot: {
          ...createdOrder().packageSnapshot,
          packageVersionId: 'version-2', packageCode: 'ADVANCED', packageName: '高级版服务端快照',
        },
      })
      await flushPromises()
      expect(wrapper.text()).toContain('ord_2')
      expect(wrapper.text()).toContain('高级版服务端快照')
      wrapper.unmount()
    },
  )

  it.each(['resolve', 'reject'] as const)(
    'ignores a stale payment-attempt %s after checkout reopens on a replacement order',
    async (outcome) => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
      const staleAttempt = deferred<Awaited<ReturnType<typeof api.createPaymentAttempt>>>()
      const currentAttempt = deferred<Awaited<ReturnType<typeof api.createPaymentAttempt>>>()
      api.createOrder
        .mockResolvedValueOnce(createdOrder())
        .mockResolvedValueOnce({ ...createdOrder(), orderNo: 'ord_2' })
      api.createPaymentAttempt
        .mockImplementationOnce(() => staleAttempt.promise)
        .mockImplementationOnce(() => currentAttempt.promise)
      const wrapper = mount(CheckoutDialog, {
        props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
      })
      await flushPromises()
      await wrapper.get('[data-test=checkout-create-order]').trigger('click')
      await flushPromises()
      await wrapper.get('[data-test=start-payment]').trigger('click')
      await flushPromises()
      expect(api.createPaymentAttempt).toHaveBeenCalledTimes(1)
      expect(wrapper.getComponent('[data-test=start-payment]').props('loading')).toBe(true)

      await wrapper.setProps({ modelValue: false })
      await wrapper.setProps({ modelValue: true })
      await flushPromises()
      await wrapper.get('[data-test=checkout-create-order]').trigger('click')
      await flushPromises()
      await wrapper.get('[data-test=start-payment]').trigger('click')
      await flushPromises()
      expect(api.createPaymentAttempt).toHaveBeenCalledTimes(2)
      expect(wrapper.getComponent('[data-test=start-payment]').props('loading')).toBe(true)

      if (outcome === 'resolve') {
        staleAttempt.resolve({
          transactionNo: 'txn_stale', status: 'PENDING', expiresAt: createdOrder().expiresAt,
          action: { type: 'QR_CODE', qrContent: 'weixin://pay/stale', expiresAt: createdOrder().expiresAt },
        })
      } else {
        staleAttempt.reject(new Error('obsolete payment attempt failure'))
      }
      await flushPromises()

      const currentButton = wrapper.getComponent('[data-test=start-payment]')
      expect(currentButton.props('loading')).toBe(true)
      expect(wrapper.text()).toContain('ord_2')
      expect(wrapper.text()).not.toContain('支付请求创建失败')
      expect(wrapper.find('[data-test=payment-qr]').exists()).toBe(false)

      currentAttempt.resolve({
        transactionNo: 'txn_current', status: 'PENDING', expiresAt: createdOrder().expiresAt,
        action: { type: 'NONE', expiresAt: createdOrder().expiresAt },
      })
      await flushPromises()
      expect(wrapper.text()).toContain('支付请求已创建')
      wrapper.unmount()
    },
  )

  it('keeps the current checkout poll in flight when an obsolete poll settles', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
    const obsoletePoll = deferred<OrderDetail>()
    const currentPoll = deferred<OrderDetail>()
    api.createOrder
      .mockResolvedValueOnce(createdOrder())
      .mockResolvedValueOnce({ ...createdOrder(), orderNo: 'ord_2' })
    api.createPaymentAttempt.mockResolvedValue({
      transactionNo: 'txn_pending', status: 'PENDING', expiresAt: createdOrder().expiresAt,
      action: { type: 'NONE', expiresAt: createdOrder().expiresAt },
    })
    api.getOrderDetail
      .mockImplementationOnce(() => obsoletePoll.promise)
      .mockImplementationOnce(() => currentPoll.promise)
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=start-payment]').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(2000)
    expect(api.getOrderDetail).toHaveBeenCalledTimes(1)

    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=start-payment]').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(2000)
    expect(api.getOrderDetail).toHaveBeenCalledTimes(2)

    obsoletePoll.resolve(orderDetail({ order: { ...orderDetail().order, orderNo: 'ord_1' } }))
    await flushPromises()
    await vi.advanceTimersByTimeAsync(2000)
    expect(api.getOrderDetail).toHaveBeenCalledTimes(2)

    currentPoll.resolve(orderDetail({
      order: { ...orderDetail().order, orderNo: 'ord_2', status: 'PAID', paymentStatus: 'SUCCEEDED' },
    }))
    await flushPromises()
    wrapper.unmount()
  })

  it('does not restart polling when an in-flight request fails after unmount', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
    api.createOrder.mockResolvedValue(createdOrder())
    api.createPaymentAttempt.mockResolvedValue({
      transactionNo: 'txn_1', status: 'PENDING', expiresAt: createdOrder().expiresAt,
      action: { type: 'NONE', expiresAt: createdOrder().expiresAt },
    })
    const inFlight = deferred<ReturnType<typeof orderDetail>>()
    api.getOrderDetail.mockImplementation(() => inFlight.promise)
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(0)
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=start-payment]').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(2000)
    expect(api.getOrderDetail).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    inFlight.reject(new Error('network unavailable'))
    await flushPromises()
    await vi.advanceTimersByTimeAsync(10_000)

    expect(api.getOrderDetail).toHaveBeenCalledTimes(1)
  })

  it('backs off after a polling error, resumes, and clears stale feedback', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
    api.createOrder.mockResolvedValue(createdOrder())
    api.createPaymentAttempt.mockResolvedValue({
      transactionNo: 'txn_1', status: 'PENDING', expiresAt: createdOrder().expiresAt,
      action: { type: 'NONE', expiresAt: createdOrder().expiresAt },
    })
    api.getOrderDetail
      .mockRejectedValueOnce(new Error('temporary outage'))
      .mockResolvedValueOnce(orderDetail())
      .mockResolvedValueOnce(orderDetail({
        order: { ...orderDetail().order, status: 'PAID', paymentStatus: 'SUCCEEDED' },
        grant: { grantRequestId: 'grant-1', status: 'ACTIVE', updatedAt: '2026-08-02T12:00:09Z' },
      }))
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(0)
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=start-payment]').trigger('click')
    await flushPromises()

    await vi.advanceTimersByTimeAsync(2000)
    expect(api.getOrderDetail).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).not.toContain('稍后重试')
    expect(document.body.textContent).toContain('稍后重试')
    await vi.advanceTimersByTimeAsync(4999)
    expect(api.getOrderDetail).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()

    expect(api.getOrderDetail).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('稍后重试')
    await vi.advanceTimersByTimeAsync(2000)
    expect(api.getOrderDetail).toHaveBeenCalledTimes(3)
    expect(wrapper.text()).toContain('支付成功')
    expect(wrapper.text()).toContain('已生效')
    await vi.advanceTimersByTimeAsync(6000)
    expect(api.getOrderDetail).toHaveBeenCalledTimes(3)
    wrapper.unmount()
  })

  it.each(['FAILED', 'CLOSED'] as const)('offers retry when attempt creation returns %s', async (status) => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
    api.createOrder.mockResolvedValue(createdOrder())
    api.createPaymentAttempt.mockResolvedValue({
      transactionNo: 'txn_terminal', status, expiresAt: createdOrder().expiresAt,
      action: { type: 'NONE', expiresAt: createdOrder().expiresAt },
    })
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(0)
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=start-payment]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test=retry-payment]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('renders QR bitmaps and rejects non-HTTPS browser actions', async () => {
    qr.toDataURL.mockResolvedValue('data:image/png;base64,qr-bitmap')
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const wrapper = mount(PaymentActionPane, {
      props: { action: { type: 'QR_CODE', qrContent: 'weixin://pay/123', expiresAt: createdOrder().expiresAt } },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    expect(qr.toDataURL).toHaveBeenCalledWith('weixin://pay/123', expect.any(Object))
    expect(wrapper.get('[data-test=payment-qr]').attributes('src')).toBe('data:image/png;base64,qr-bitmap')
    expect(wrapper.get('.qr-frame').attributes('aria-busy')).toBe('false')

    await wrapper.setProps({ action: { type: 'REDIRECT_URL', targetUrl: 'http://pay.example.test', expiresAt: createdOrder().expiresAt } })
    await wrapper.get('[data-test=open-payment]').trigger('click')
    expect(open).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('仅支持 HTTPS')

    await wrapper.setProps({ action: { type: 'REDIRECT_URL', targetUrl: 'https://pay.example.test', expiresAt: createdOrder().expiresAt } })
    await wrapper.get('[data-test=open-payment]').trigger('click')
    expect(open).toHaveBeenCalledWith('https://pay.example.test', '_blank', 'noopener,noreferrer')
  })

  it('stops the QR skeleton after bitmap generation fails', async () => {
    qr.toDataURL.mockRejectedValue(new Error('QR encoder unavailable'))
    const wrapper = mount(PaymentActionPane, {
      props: { action: { type: 'QR_CODE', qrContent: 'weixin://pay/failure', expiresAt: createdOrder().expiresAt } },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.find('.el-alert').exists()).toBe(false)
    expect(document.body.textContent).toContain('二维码生成失败')
    expect(wrapper.find('.el-skeleton').exists()).toBe(false)
    expect(wrapper.get('.qr-frame').attributes('aria-busy')).toBe('false')
  })

  it('submits only structured HTTPS form fields without HTML injection', async () => {
    let submitted: HTMLFormElement | null = null
    vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(function (this: HTMLFormElement) {
      submitted = this.cloneNode(true) as HTMLFormElement
    })
    const wrapper = mount(PaymentActionPane, {
      props: { action: { type: 'HTML_FORM', targetUrl: 'https://pay.example.test/form', fields: { token: 'abc', order: 'ord_1' }, expiresAt: createdOrder().expiresAt } },
      global: { plugins: [ElementPlus] },
    })
    await wrapper.get('[data-test=submit-payment-form]').trigger('click')

    expect(paymentActionSource).not.toContain('v-html')
    expect(submitted?.method).toBe('post')
    expect(submitted?.action).toBe('https://pay.example.test/form')
    expect(submitted?.target).toBe('_blank')
    expect(submitted?.getAttribute('rel')).toBe('noopener noreferrer')
    expect(Array.from(submitted?.querySelectorAll('input') || []).map((input) => [input.name, input.value])).toEqual([
      ['token', 'abc'], ['order', 'ord_1'],
    ])
  })

  it('shows retry for a failed attempt and creates a new order after business closure', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
    api.createOrder.mockResolvedValue(createdOrder())
    api.createPaymentAttempt.mockResolvedValue({
      transactionNo: 'txn_1', status: 'PENDING', expiresAt: createdOrder().expiresAt,
      action: { type: 'NONE', expiresAt: createdOrder().expiresAt },
    })
    api.getOrderDetail.mockResolvedValueOnce(orderDetail({ paymentAttempts: [{ ...orderDetail().paymentAttempts[0], status: 'FAILED' }] }))
      .mockResolvedValueOnce(orderDetail({ order: { ...orderDetail().order, status: 'CLOSED' } }))
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(0)
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=start-payment]').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(2000)
    expect(wrapper.find('[data-test=retry-payment]').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(2000)
    expect(wrapper.find('[data-test=create-new-order]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('shows order grant state and immutable payment attempts in the detail drawer', async () => {
    api.listOrders.mockResolvedValue(orderFixture())
    api.getOrderDetail.mockResolvedValue(orderDetail({
      order: { ...orderDetail().order, status: 'PAID', paymentStatus: 'SUCCEEDED' },
      paymentAttempts: [{ ...orderDetail().paymentAttempts[0], status: 'SUCCEEDED', paidAt: '2026-08-02T12:00:02Z' }],
      grant: { grantRequestId: 'grant-1', status: 'ACTIVE', updatedAt: '2026-08-02T12:00:02Z' },
      refund: {
        refundNo: 'ref_1', status: 'PENDING_APPROVAL', refundType: 'PARTIAL', amount: 7425,
        requestedAt: '2026-08-02T12:00:03Z', reviewedAt: null, completedAt: null,
      },
    }))
    const router = await authenticatedRouterAt('/app/orders')
    const wrapper = mount(OrdersView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('共 45 条订单记录')
    expect(wrapper.text()).not.toContain('真实订单记录')
    await wrapper.get('[data-test=view-order]').trigger('click')
    await flushPromises()

    expect(api.getOrderDetail).toHaveBeenCalledWith('order-user-001')
    const detail = wrapper.get('[data-test=order-detail]')
    expect(detail.text()).toContain('服务端快照套餐')
    expect(detail.text()).toContain('version-snapshot-42')
    expect(detail.text()).toContain('已支付')
    expect(detail.text()).toContain('支付成功')
    expect(detail.text()).toContain('已生效')
    expect(detail.text()).toContain('待审核')
    expect(detail.get('[data-test=attempt-paid-at]').text()).toContain('到账时间')
    expect(detail.text()).toContain('txn_1')
    expect(detail.text()).not.toContain('创建 API Key')
    expect(detail.text()).not.toContain('SUCCEEDED')
    expect(ordersViewSource).toMatch(/\.page-header\s*\{[^}]*display:\s*flex;/s)
    expect(ordersViewSource).toMatch(/@media \(max-width:\s*640px\)\s*\{[\s\S]*?\.page-header\s*\{[^}]*flex-direction:\s*column;/)
  })

  it('renders the returned payment action when retrying from order detail', async () => {
    qr.toDataURL.mockResolvedValue('data:image/png;base64,retry-qr')
    api.listOrders.mockResolvedValue(orderFixture())
    api.getOrderDetail.mockResolvedValue(orderDetail({
      paymentAttempts: [{ ...orderDetail().paymentAttempts[0], status: 'FAILED' }],
    }))
    api.createPaymentAttempt.mockResolvedValue({
      transactionNo: 'txn_retry', status: 'PENDING', expiresAt: createdOrder().expiresAt,
      action: { type: 'QR_CODE', qrContent: 'weixin://pay/retry', expiresAt: createdOrder().expiresAt },
    })
    const router = await authenticatedRouterAt('/app/orders')
    const wrapper = mount(OrdersView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=view-order]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=retry-order-payment]').trigger('click')
    await flushPromises()

    expect(api.createPaymentAttempt).toHaveBeenCalledWith('order-user-001', 'WECHAT_PAY', 'WECHAT_NATIVE')
    expect(wrapper.get('[data-test=payment-qr]').attributes('src')).toBe('data:image/png;base64,retry-qr')
  })

  it.each(['resolve', 'reject'] as const)(
    'keeps order B retry state isolated when order A retry %s arrives late',
    async (outcome) => {
      const staleAttempt = deferred<Awaited<ReturnType<typeof api.createPaymentAttempt>>>()
      const currentAttempt = deferred<Awaited<ReturnType<typeof api.createPaymentAttempt>>>()
      const messageError = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
      qr.toDataURL.mockImplementation((content: string) => Promise.resolve(`data:image/png;base64,${content}`))
      api.listOrders.mockResolvedValue({
        ...orderFixture(),
        items: [
          { ...orderFixture().items[0], id: 'order-a-id', orderNo: 'order-user-001' },
          { ...orderFixture().items[0], id: 'order-b-id', orderNo: 'order-user-002' },
        ],
        total: 2,
      })
      api.getOrderDetail.mockImplementation((orderNo: string) => Promise.resolve(orderDetail({
        order: { ...orderDetail().order, orderNo },
        paymentAttempts: [{ ...orderDetail().paymentAttempts[0], transactionNo: `txn-${orderNo}`, status: 'FAILED' }],
      })))
      api.createPaymentAttempt
        .mockImplementationOnce(() => staleAttempt.promise)
        .mockImplementationOnce(() => currentAttempt.promise)

      const router = await authenticatedRouterAt('/app/orders')
      const wrapper = mount(OrdersView, { global: { plugins: [router, ElementPlus] } })
      await flushPromises()
      const detailButtons = wrapper.findAll('[data-test=view-order]')
      await detailButtons[0]!.trigger('click')
      await flushPromises()
      await wrapper.get('[data-test=retry-order-payment]').trigger('click')
      await detailButtons[1]!.trigger('click')
      await flushPromises()
      await wrapper.get('[data-test=retry-order-payment]').trigger('click')
      await flushPromises()

      if (outcome === 'resolve') {
        staleAttempt.resolve({
          transactionNo: 'txn-a-retry', status: 'PENDING', expiresAt: createdOrder().expiresAt,
          action: { type: 'QR_CODE', qrContent: 'order-a-action', expiresAt: createdOrder().expiresAt },
        })
      } else {
        staleAttempt.reject(new Error('obsolete order A retry failure'))
      }
      await flushPromises()

      expect(wrapper.text()).toContain('order-user-002')
      expect(wrapper.find('[data-test=payment-qr]').exists()).toBe(false)
      expect(messageError).not.toHaveBeenCalled()
      expect(wrapper.getComponent('[data-test=retry-order-payment]').props('loading')).toBe(true)

      currentAttempt.resolve({
        transactionNo: 'txn-b-retry', status: 'PENDING', expiresAt: createdOrder().expiresAt,
        action: { type: 'QR_CODE', qrContent: 'order-b-action', expiresAt: createdOrder().expiresAt },
      })
      await flushPromises()

      expect(wrapper.get('[data-test=payment-qr]').attributes('src')).toBe('data:image/png;base64,order-b-action')
      expect(wrapper.getComponent('[data-test=retry-order-payment]').props('loading')).toBe(false)
    },
  )

  it('shows refund rules, immutable calculation, and cancellation only before approval', async () => {
    api.requestRefund.mockResolvedValue(refundDetail())
    api.cancelRefund.mockResolvedValue(refundDetail('CANCELED'))
    const wrapper = mount(RefundDialog, {
      props: { modelValue: true, order: orderDetail({ order: { ...orderDetail().order, status: 'PAID' } }) },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    expect(wrapper.get('[data-test=refund-rules]').text()).toContain('退款窗口')
    await wrapper.get('[data-test=refund-reason]').setValue('不再需要该套餐')
    await flushPromises()
    await wrapper.get('[data-test=refund-form]').trigger('submit')
    await flushPromises()

    expect(api.requestRefund).toHaveBeenCalledWith('order-user-001', '不再需要该套餐')
    const calculation = wrapper.get('[data-test=refund-calculation]').text()
    expect(calculation).toContain('10,000')
    expect(calculation).toContain('2,000')
    expect(calculation).toContain('¥4.95')
    expect(calculation).toContain('¥74.25')
    expect(wrapper.text()).toContain('待审核')
    expect(wrapper.text()).toContain('部分退款')
    expect(wrapper.find('[data-test=cancel-refund]').exists()).toBe(true)
    await wrapper.get('[data-test=cancel-refund]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test=cancel-refund]').exists()).toBe(false)
  })

  it('renders a preparing refund before its type and calculation are available', async () => {
    const preparing = {
      ...refundDetail('PREPARING'), refundType: null, calculation: null,
    } satisfies RefundDetail
    api.getRefund.mockResolvedValue(preparing)
    const wrapper = mount(RefundDialog, {
      props: {
        modelValue: true,
        order: orderDetail({
          order: { ...orderDetail().order, status: 'PAID' },
          refund: {
            refundNo: 'ref_1', status: 'PREPARING', refundType: null, amount: 0,
            requestedAt: preparing.createdAt, reviewedAt: null, completedAt: null,
          },
        }),
      },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('准备中')
    expect(wrapper.find('[data-test=refund-calculation]').exists()).toBe(false)
    expect(wrapper.find('[data-test=cancel-refund]').exists()).toBe(true)
  })

  it('logs in with the platform session and routes by the returned role', async () => {
    api.login.mockResolvedValue({ accessToken: 'access-token', expiresIn: 900, user: user() })
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/login')
    const wrapper = mount(LoginView, {
      global: { plugins: [router, ElementPlus], stubs: { SliderCaptchaDialog: sliderCaptchaStub } },
    })

    expect(loginViewSource).toMatch(/\.password-toggle\s*\{[^}]*width:\s*44px;[^}]*height:\s*44px;/s)

    await wrapper.get('[data-test=login-identifier]').setValue('user@example.com')
    await wrapper.get('[data-test=login-password]').setValue('ValidPassw0rd!')
    await flushPromises()
    // 密码登录是"老账号"路径，后端不校验同意，因此这里不再有同意勾选。
    expect(wrapper.find('[data-test=login-consent]').exists()).toBe(false)
    expect(wrapper.get('[data-test=login-submit]').attributes('disabled')).toBeUndefined()
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.login).toHaveBeenCalledWith('user@example.com', 'ValidPassw0rd!')
    expect(useAuthStore().accessToken).toBe('access-token')
    expect(router.currentRoute.value.path).toBe('/app/dashboard')
  })

  it('enables balance payment for an exact authoritative wallet balance', async () => {
    api.createOrder.mockResolvedValue({ ...createdOrder(), expiresAt: '2099-08-05T00:01:05Z' })
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()

    const segmented = wrapper.getComponent({ name: 'ElSegmented' })
    expect(segmented.props('options')).toEqual([
      { label: '微信支付', value: 'WECHAT' },
      { label: '支付宝', value: 'ALIPAY' },
      { label: '余额支付', value: 'BALANCE' },
    ])
    segmented.vm.$emit('update:modelValue', 'BALANCE')
    await flushPromises()

    expect(wrapper.get('[data-test=wallet-available]').text()).toContain('¥123.45')
    expect(wrapper.get('[data-test=start-balance-payment]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-test=wallet-recharge-link]').exists()).toBe(false)
  })

  it('disables balance payment when funds are insufficient and links to the wallet', async () => {
    api.getWalletSummary.mockResolvedValueOnce({
      availableAmount: 12_344, currency: 'CNY', updatedAt: '2026-08-05T00:00:00Z',
    })
    api.createOrder.mockResolvedValue({ ...createdOrder(), expiresAt: '2099-08-05T00:01:05Z' })
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    wrapper.getComponent({ name: 'ElSegmented' }).vm.$emit('update:modelValue', 'BALANCE')
    await flushPromises()

    expect(wrapper.get('[data-test=start-balance-payment]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test=wallet-recharge-link]').attributes('href')).toBe('/app/wallet')
    expect(wrapper.text()).toContain('余额不足')
  })

  it('uses one stable balance payment debit and never creates an external attempt', async () => {
    const paid = orderDetail({
      order: {
        ...orderDetail().order,
        status: 'PAID', paymentStatus: null, paymentProvider: 'UNSELECTED', settlementMethod: 'BALANCE',
      },
      paymentAttempts: [],
      grant: { grantRequestId: 'grant-balance', status: 'ACTIVE', updatedAt: '2026-08-05T00:00:01Z' },
    })
    api.createOrder.mockResolvedValue({ ...createdOrder(), expiresAt: '2099-08-05T00:01:05Z' })
    api.getOrderDetail.mockResolvedValue(paid)
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    wrapper.getComponent({ name: 'ElSegmented' }).vm.$emit('update:modelValue', 'BALANCE')
    await flushPromises()

    const pay = wrapper.get('[data-test=start-balance-payment]')
    await Promise.all([pay.trigger('click'), pay.trigger('click')])
    await flushPromises()

    expect(api.payOrderWithBalance).toHaveBeenCalledTimes(1)
    expect(api.payOrderWithBalance).toHaveBeenCalledWith('ord_1', expect.stringMatching(/^balance-checkout-/))
    expect(api.createPaymentAttempt).not.toHaveBeenCalled()
    expect(api.getOrderDetail).toHaveBeenCalledWith('ord_1')
    expect(api.getWalletSummary).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('支付成功')
  })

  it('allows retry after a balance payment request fails', async () => {
    api.createOrder.mockResolvedValue({ ...createdOrder(), expiresAt: '2099-08-05T00:01:05Z' })
    api.payOrderWithBalance
      .mockRejectedValueOnce(new Error('debit unavailable'))
      .mockResolvedValueOnce({
        orderNo: 'ord_1', orderStatus: 'PAID', settlementMethod: 'BALANCE',
        balanceBefore: 12_345, balanceAfter: 0, grantRequestId: 'grant-balance', grantStatus: 'ACTIVE',
      })
    api.getOrderDetail.mockResolvedValue(orderDetail({
      order: { ...orderDetail().order, status: 'PAID', settlementMethod: 'BALANCE' },
      paymentAttempts: [],
      grant: { grantRequestId: 'grant-balance', status: 'ACTIVE', updatedAt: '2026-08-05T00:00:01Z' },
    }))
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    wrapper.getComponent({ name: 'ElSegmented' }).vm.$emit('update:modelValue', 'BALANCE')
    await flushPromises()

    await wrapper.get('[data-test=start-balance-payment]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('余额支付失败')
    expect(document.body.textContent).toContain('余额支付失败')
    await wrapper.get('[data-test=start-balance-payment]').trigger('click')
    await flushPromises()

    expect(api.payOrderWithBalance).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('支付成功')
  })

  it('keeps balance payment committed when order detail refresh fails', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
    api.createOrder.mockResolvedValue(createdOrder())
    api.getOrderDetail
      .mockRejectedValueOnce(new Error('detail unavailable'))
      .mockResolvedValueOnce(orderDetail({
        order: { ...orderDetail().order, status: 'PAID', settlementMethod: 'BALANCE' },
        paymentAttempts: [],
        grant: { grantRequestId: 'grant-balance', status: 'ACTIVE', updatedAt: '2026-08-02T12:00:02Z' },
      }))
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    wrapper.getComponent({ name: 'ElSegmented' }).vm.$emit('update:modelValue', 'BALANCE')
    await flushPromises()
    await wrapper.get('[data-test=start-balance-payment]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('支付成功')
    expect(wrapper.text()).not.toContain('余额支付失败')
    expect(wrapper.text()).not.toContain('订单详情暂未同步')
    expect(document.body.textContent).toContain('订单详情暂未同步')
    expect(api.payOrderWithBalance).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(2_000)
    expect(api.getOrderDetail).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('paid')).toHaveLength(1)
    wrapper.unmount()
  })

  it('keeps balance payment committed when wallet summary refresh fails', async () => {
    api.getWalletSummary
      .mockResolvedValueOnce({ availableAmount: 12_345, currency: 'CNY', updatedAt: '2026-08-05T00:00:00Z' })
      .mockRejectedValueOnce(new Error('wallet unavailable'))
    api.createOrder.mockResolvedValue({ ...createdOrder(), expiresAt: '2099-08-05T00:01:05Z' })
    api.getOrderDetail.mockResolvedValue(orderDetail({
      order: { ...orderDetail().order, status: 'PAID', settlementMethod: 'BALANCE' },
      paymentAttempts: [],
      grant: { grantRequestId: 'grant-balance', status: 'ACTIVE', updatedAt: '2026-08-05T00:00:01Z' },
    }))
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    wrapper.getComponent({ name: 'ElSegmented' }).vm.$emit('update:modelValue', 'BALANCE')
    await flushPromises()
    await wrapper.get('[data-test=start-balance-payment]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('支付成功')
    expect(wrapper.text()).not.toContain('余额支付失败')
    expect(wrapper.text()).not.toContain('余额暂未刷新')
    expect(document.body.textContent).toContain('余额暂未刷新')
    expect(api.payOrderWithBalance).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('paid')).toHaveLength(1)
  })

  it('ignores a stale balance payment response after the wallet user scope changes', async () => {
    const payment = deferred<Awaited<ReturnType<typeof api.payOrderWithBalance>>>()
    api.createOrder.mockResolvedValue({ ...createdOrder(), expiresAt: '2099-08-05T00:01:05Z' })
    api.payOrderWithBalance.mockReturnValueOnce(payment.promise)
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    wrapper.getComponent({ name: 'ElSegmented' }).vm.$emit('update:modelValue', 'BALANCE')
    await flushPromises()
    await wrapper.get('[data-test=start-balance-payment]').trigger('click')
    useWalletStore().userScopeGeneration += 1
    payment.resolve({
      orderNo: 'ord_1', orderStatus: 'PAID', settlementMethod: 'BALANCE',
      balanceBefore: 12_345, balanceAfter: 0, grantRequestId: 'grant-old-user', grantStatus: 'ACTIVE',
    })
    await flushPromises()

    expect(api.getOrderDetail).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('支付成功')
  })

  it('waits for a queued balance grant before emitting paid exactly once', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
    api.createOrder.mockResolvedValue(createdOrder())
    const queued = orderDetail({
      order: { ...orderDetail().order, status: 'PAID', settlementMethod: 'BALANCE' },
      paymentAttempts: [],
      grant: { grantRequestId: 'grant-balance', status: 'QUEUED', updatedAt: '2026-08-02T12:00:01Z' },
    })
    api.getOrderDetail
      .mockResolvedValueOnce(queued)
      .mockResolvedValueOnce(queued)
      .mockResolvedValueOnce({ ...queued, grant: { ...queued.grant!, status: 'ACTIVE' } })
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    wrapper.getComponent({ name: 'ElSegmented' }).vm.$emit('update:modelValue', 'BALANCE')
    await flushPromises()
    await wrapper.get('[data-test=start-balance-payment]').trigger('click')
    await flushPromises()

    expect(api.getOrderDetail).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('paid')).toBeUndefined()
    await vi.advanceTimersByTimeAsync(2_000)
    expect(api.getOrderDetail).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('paid')).toBeUndefined()
    await vi.advanceTimersByTimeAsync(2_000)
    expect(api.getOrderDetail).toHaveBeenCalledTimes(3)
    expect(wrapper.get('[data-test=checkout-grant-status]').attributes('data-status')).toBe('ACTIVE')
    expect(wrapper.emitted('paid')).toHaveLength(1)
    wrapper.unmount()
  })

  it('allows balance payment when profile loading fails', async () => {
    api.getProfile.mockRejectedValueOnce(new Error('profile unavailable'))
    api.createOrder.mockResolvedValue({ ...createdOrder(), expiresAt: '2099-08-05T00:01:05Z' })
    api.getOrderDetail.mockResolvedValue(orderDetail({
      order: { ...orderDetail().order, status: 'PAID', settlementMethod: 'BALANCE' },
      paymentAttempts: [],
      grant: { grantRequestId: 'grant-balance', status: 'ACTIVE', updatedAt: '2026-08-05T00:00:01Z' },
    }))
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test=start-payment]').attributes('disabled')).toBeDefined()
    wrapper.getComponent({ name: 'ElSegmented' }).vm.$emit('update:modelValue', 'BALANCE')
    await flushPromises()

    const pay = wrapper.get('[data-test=start-balance-payment]')
    expect(pay.attributes('disabled')).toBeUndefined()
    await pay.trigger('click')
    await flushPromises()

    expect(api.payOrderWithBalance).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('支付成功')
  })

  it.each([
    ['BALANCE', 'UNSELECTED', '余额支付'],
    ['EXTERNAL', 'WECHAT_PAY', '微信支付'],
    ['EXTERNAL', 'ALIPAY', '支付宝'],
    ['UNSELECTED', 'UNSELECTED', '未选择'],
  ] as const)('renders settlement method %s with provider %s as %s in list and detail', async (settlementMethod, paymentProvider, label) => {
    api.listOrders.mockResolvedValue({
      ...orderFixture(),
      items: [{ ...orderFixture().items[0], settlementMethod, paymentProvider }],
    })
    api.getOrderDetail.mockResolvedValue(orderDetail({
      order: { ...orderDetail().order, settlementMethod, paymentProvider },
    }))
    const router = await authenticatedRouterAt('/app/orders')
    const wrapper = mount(OrdersView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    const row = wrapper.get('tbody tr')
    expect(row.text()).toContain(label)
    await wrapper.get('[data-test=view-order]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test=order-detail]').text()).toContain(`结算方式${label}`)
  })

  it('allows existing-account password login without registration consent', async () => {
    api.login.mockResolvedValue({ accessToken: 'password-token', expiresIn: 900, user: user() })
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/login')
    const wrapper = mount(LoginView, {
      global: { plugins: [router, ElementPlus], stubs: { SliderCaptchaDialog: sliderCaptchaStub } },
    })

    await wrapper.get('[data-test=login-identifier]').setValue('existing@example.com')
    await wrapper.get('[data-test=login-password]').setValue('ValidPassw0rd!')
    await flushPromises()

    expect(wrapper.find('[data-test=login-consent]').exists()).toBe(false)
    expect(wrapper.get('[data-test=login-submit]').attributes('disabled')).toBeUndefined()

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.login).toHaveBeenCalledWith('existing@example.com', 'ValidPassw0rd!')
  })

  it('keeps SMS send and resend stable, accessible, and recoverable after failure', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-04T00:00:00Z'))
    const loader = smsComponents['../../src/components/sms/SmsCodeField.vue']
    const sourceLoader = smsComponentSources['../../src/components/sms/SmsCodeField.vue']
    expect(loader).toBeTypeOf('function')
    expect(sourceLoader).toBeTypeOf('function')
    if (!loader || !sourceLoader) return
    const component = (await loader() as { default: object }).default
    const source = await sourceLoader() as string
    const send = vi.fn()
      .mockRejectedValueOnce(new Error('provider unavailable'))
      .mockResolvedValue({
        id: 'challenge-register', purpose: 'REGISTER', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
      })
    const wrapper = mount(component, {
      props: {
        modelValue: '',
        phone: '13812345678',
        sendChallenge: send,
        formatSendError: () => 'custom send failure',
      },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    const codeInput = wrapper.get('[data-test=sms-code] input')
    expect(codeInput.attributes('inputmode')).toBe('numeric')
    expect(codeInput.attributes('autocomplete')).toBe('one-time-code')
    expect(wrapper.get(`label[for=${codeInput.attributes('id')}]`).exists()).toBe(true)
    expect(wrapper.get('[data-test=sms-send]').classes()).toContain('el-button--primary')
    expect(source).toMatch(/min-height:\s*44px/)
    expect(source).toMatch(/\.sms-code-row\s+:deep\(\.el-input__wrapper\)\s*\{[^}]*min-height:\s*44px/s)
    expect(source).toMatch(/font-variant-numeric:\s*tabular-nums/)
    expect(source).toMatch(/minmax\(0,\s*1fr\)/)

    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role=alert]').exists()).toBe(false)
    expect(document.body.textContent).toContain('custom send failure')
    expect(wrapper.get('[data-test=sms-send]').attributes('disabled')).toBeUndefined()

    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test=sms-send]').text()).toContain('60')
    expect(wrapper.get('[data-test=sms-send]').attributes('disabled')).toBeDefined()
    await vi.advanceTimersByTimeAsync(59_000)
    expect(wrapper.get('[data-test=sms-send]').text()).toContain('1')
    await vi.advanceTimersByTimeAsync(1_000)
    expect(wrapper.get('[data-test=sms-send]').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-test=sms-send]').text()).not.toContain('0')
  })

  it('clears the previous SMS code as soon as send or resend starts', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-04T00:00:00Z'))
    const loader = smsComponents['../../src/components/sms/SmsCodeField.vue']
    expect(loader).toBeTypeOf('function')
    if (!loader) return
    const component = (await loader() as { default: object }).default
    const first = deferred<{ id: string; purpose: 'REGISTER'; maskedPhone: string; expiresAt: string }>()
    const second = deferred<{ id: string; purpose: 'REGISTER'; maskedPhone: string; expiresAt: string }>()
    const send = vi.fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
    const wrapper = mount(component, {
      props: { modelValue: '123456', phone: '13812345678', sendChallenge: send },
      global: { plugins: [ElementPlus] },
    })

    await wrapper.get('[data-test=sms-send]').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toEqual([['']])

    first.resolve({
      id: 'challenge-first', purpose: 'REGISTER', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    await flushPromises()
    await wrapper.setProps({ modelValue: '654321' })
    await vi.advanceTimersByTimeAsync(60_000)
    await wrapper.get('[data-test=sms-send]').trigger('click')

    expect(wrapper.emitted('update:modelValue')).toEqual([[''], ['']])
  })

  it('keeps SMS input labels unique and correctly associated on the same page', async () => {
    const loader = smsComponents['../../src/components/sms/SmsCodeField.vue']
    expect(loader).toBeTypeOf('function')
    if (!loader) return
    const component = (await loader() as { default: object }).default
    const challenge = {
      id: 'challenge-shared', purpose: 'REGISTER' as const, maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    }
    const wrapper = mount({
      components: { SmsCodeField: component },
      data: () => ({ firstCode: '', secondCode: '' }),
      methods: { send: () => Promise.resolve(challenge) },
      template: `<div>
        <SmsCodeField v-model="firstCode" :send-challenge="send" />
        <SmsCodeField v-model="secondCode" :send-challenge="send" />
      </div>`,
    }, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const inputs = wrapper.findAll('[data-test=sms-code] input')
    expect(inputs).toHaveLength(2)
    const ids = inputs.map((input) => input.attributes('id'))
    expect(ids[0]).toBeTruthy()
    expect(ids[1]).toBeTruthy()
    expect(ids[0]).not.toBe(ids[1])
    expect(wrapper.findAll(`label[for="${ids[0]}"]`)).toHaveLength(1)
    expect(wrapper.findAll(`label[for="${ids[1]}"]`)).toHaveLength(1)
  })

  it('ignores an obsolete SMS issue after a phone change while the new issue remains loading', async () => {
    const loader = smsComponents['../../src/components/sms/SmsCodeField.vue']
    expect(loader).toBeTypeOf('function')
    if (!loader) return
    const component = (await loader() as { default: object }).default
    const obsolete = deferred<{ id: string; purpose: 'REGISTER'; maskedPhone: string; expiresAt: string }>()
    const current = deferred<{ id: string; purpose: 'REGISTER'; maskedPhone: string; expiresAt: string }>()
    const send = vi.fn()
      .mockReturnValueOnce(obsolete.promise)
      .mockReturnValueOnce(current.promise)
    const wrapper = mount(component, {
      props: { modelValue: '', phone: '13812345678', requirePhone: true, sendChallenge: send },
      global: { plugins: [ElementPlus] },
    })

    await wrapper.get('[data-test=sms-send]').trigger('click')
    await wrapper.setProps({ phone: '13912345678' })
    await wrapper.get('[data-test=sms-send]').trigger('click')
    obsolete.resolve({
      id: 'challenge-obsolete', purpose: 'REGISTER', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    await flushPromises()

    const issuedBeforeCurrent = (wrapper.emitted('challenge') || []).map((event) => event[0]).filter(Boolean)
    expect(issuedBeforeCurrent).toEqual([])
    expect(wrapper.get('[data-test=sms-send]').classes()).toContain('is-loading')
    expect(wrapper.get('[data-test=sms-send]').text()).not.toContain('60')

    current.resolve({
      id: 'challenge-current', purpose: 'REGISTER', maskedPhone: '139****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    await flushPromises()
    const issued = (wrapper.emitted('challenge') || []).map((event) => event[0]).filter(Boolean)
    expect(issued).toEqual([expect.objectContaining({ id: 'challenge-current' })])
    expect(wrapper.get('[data-test=sms-send]').text()).toContain('60')
  })

  it('invalidates a pending SMS issue when reset is exposed to a closing parent', async () => {
    const loader = smsComponents['../../src/components/sms/SmsCodeField.vue']
    expect(loader).toBeTypeOf('function')
    if (!loader) return
    const component = (await loader() as { default: object }).default
    const pending = deferred<{ id: string; purpose: 'REGISTER'; maskedPhone: string; expiresAt: string }>()
    const wrapper = mount(component, {
      props: { modelValue: '', sendChallenge: () => pending.promise },
      global: { plugins: [ElementPlus] },
    })

    await wrapper.get('[data-test=sms-send]').trigger('click')
    ;(wrapper.vm as unknown as { reset: () => void }).reset()
    pending.resolve({
      id: 'challenge-after-reset', purpose: 'REGISTER', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    await flushPromises()

    const issued = (wrapper.emitted('challenge') || []).map((event) => event[0]).filter(Boolean)
    expect(issued).toEqual([])
    expect(wrapper.get('[data-test=sms-send]').text()).not.toContain('60')
    expect(wrapper.get('[data-test=sms-send]').classes()).not.toContain('is-loading')
  })

  it('does not emit a pending SMS issue after unmount', async () => {
    const loader = smsComponents['../../src/components/sms/SmsCodeField.vue']
    expect(loader).toBeTypeOf('function')
    if (!loader) return
    const component = (await loader() as { default: object }).default
    const pending = deferred<{ id: string; purpose: 'REGISTER'; maskedPhone: string; expiresAt: string }>()
    const wrapper = mount(component, {
      props: { modelValue: '', sendChallenge: () => pending.promise },
      global: { plugins: [ElementPlus] },
    })

    await wrapper.get('[data-test=sms-send]').trigger('click')
    wrapper.unmount()
    pending.resolve({
      id: 'challenge-after-unmount', purpose: 'REGISTER', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    await flushPromises()

    const issued = (wrapper.emitted('challenge') || []).map((event) => event[0]).filter(Boolean)
    expect(issued).toEqual([])
  })

  it('registers only after phone verification code and explicit consent', async () => {
    api.requestRegistrationSms.mockResolvedValue({
      id: 'challenge-register', purpose: 'REGISTER', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.register.mockResolvedValue({ accessToken: 'registered-token', expiresIn: 900, user: user() })
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/register')
    const wrapper = mount(RegisterView, {
      global: { plugins: [router, ElementPlus], stubs: { SliderCaptchaDialog: sliderCaptchaStub } },
    })

    expect(wrapper.find('[data-test=register-invitation]').exists()).toBe(false)
    await wrapper.get('[data-test=register-name]').setValue('测试用户')
    await wrapper.get('[data-test=register-email]').setValue('user@example.com')
    await wrapper.get('[data-test=register-password]').setValue('ValidPassw0rd!')
    await wrapper.get('[data-test=register-confirm]').setValue('ValidPassw0rd!')
    await wrapper.get('[data-test=register-phone]').setValue('13812345678')
    expect(wrapper.get('[data-test=register-submit]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('123456')
    await wrapper.get('[data-test=register-consent] input').setValue(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.requestRegistrationSms).toHaveBeenCalledWith('13812345678', 'captcha-proof')
    expect(api.register).toHaveBeenCalledWith({
      email: 'user@example.com',
      password: 'ValidPassw0rd!',
      displayName: '测试用户',
      phone: '13812345678',
      challengeId: 'challenge-register',
      verificationCode: '123456',
    })
    expect(useAuthStore().accessToken).toBe('registered-token')
    expect(router.currentRoute.value.path).toBe('/app/dashboard')
  })

  it('marks the current and queued package versions from real quota state', async () => {
    api.listPlans.mockResolvedValue([
      { code: 'FREE', name: '免费版', kind: 'FREE_CLAIM', priceAmount: 0, currency: 'CNY', totalTokenQuota: 1000, durationDays: 30, maxApiKeys: 1, requestsPerMinute: 5, tokensPerMinute: 1000, maxConcurrency: 1, models: [{ modelCode: 'text-basic', modelTokenQuota: null, capabilities: ['TEXT'] }] },
      { code: 'STARTER', name: '入门版', kind: 'STANDARD', versionId: 'starter-v1', priceAmount: 9900, currency: 'CNY', totalTokenQuota: 10000, durationDays: 30, maxApiKeys: 3, requestsPerMinute: 20, tokensPerMinute: 10000, maxConcurrency: 2, models: [] },
      { code: 'ADVANCED', name: '高级版', kind: 'STANDARD', versionId: 'advanced-v1', priceAmount: 29900, currency: 'CNY', totalTokenQuota: 50000, durationDays: 30, maxApiKeys: 10, requestsPerMinute: 60, tokensPerMinute: 50000, maxConcurrency: 8, models: [] },
      { code: 'ENTERPRISE', name: '企业版', kind: 'STANDARD', versionId: 'enterprise-v1', priceAmount: 99900, currency: 'CNY', totalTokenQuota: 200000, durationDays: 30, maxApiKeys: 30, requestsPerMinute: 200, tokensPerMinute: 200000, maxConcurrency: 30, models: [] },
    ])
    api.quotaSummary.mockResolvedValue({
      activePackage: { packageCode: 'STARTER', remainingTokens: 6500 },
      queuedPackages: [{ packageCode: 'ADVANCED' }],
    })
    const wrapper = mount(PlansView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.findAll('[data-test=plan-card]')).toHaveLength(4)
    expect(usePackageStore().plans).toHaveLength(4)
    expect(wrapper.get('[data-plan-code=STARTER]').text()).toContain('当前使用')
    expect(wrapper.get('[data-plan-code=ADVANCED]').text()).toContain('排队中')
    expect(wrapper.get('[data-plan-code=STARTER]').text()).toContain('当前套餐仍有剩余额度，暂时无法重复购买')
    expect(wrapper.get('[data-plan-code=ADVANCED]').text()).toContain('该套餐已在排队中，无需重复购买')
    expect(wrapper.get('[data-plan-code=STARTER]').get('[data-test=buy-plan]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-plan-code=ADVANCED]').get('[data-test=buy-plan]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-plan-code=FREE]').text()).toContain('文本模型')
    expect(wrapper.text()).not.toContain('无限积分')
  })

  it('allows repurchase after the active package quota is fully consumed', async () => {
    api.listPlans.mockResolvedValue([paidPlan()])
    api.quotaSummary.mockResolvedValue({
      activePackage: { packageCode: 'STARTER', remainingTokens: 0 },
      queuedPackages: [],
    })
    const wrapper = mount(PlansView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.get('[data-test=buy-plan]').attributes('disabled')).toBeUndefined()
  })

  it('keeps package actions aligned when model badge counts differ', () => {
    expect(plansViewSource).toMatch(
      /\.plan-card\s*\{[^}]*display:\s*flex;[^}]*flex-direction:\s*column;/,
    )
    expect(plansViewSource).toMatch(
      /\.plan-footer\s*\{[^}]*margin-top:\s*auto;/,
    )
    const actionStart = plansViewSource.indexOf('<div class="plan-footer">')
    const actionEnd = plansViewSource.indexOf('</div>', actionStart)
    const actionSource = plansViewSource.slice(actionStart, actionEnd)
    expect(actionSource.indexOf('class="plan-disabled"')).toBeGreaterThan(-1)
    expect(actionSource.indexOf('class="plan-disabled"')).toBeLessThan(
      actionSource.indexOf('<button'),
    )
  })

  it('shows a new API key once and clears it after the secret dialog closes', async () => {
    api.listApiKeys.mockResolvedValue([])
    api.listEligibleApiKeyModels.mockResolvedValue(['text-basic', 'multimodal-basic'])
    api.quotaSummary.mockResolvedValue({
      activePackage: { allowedModels: ['text-basic', 'multimodal-basic'], modelQuotas: [] },
      queuedPackages: [],
    })
    api.createApiKey.mockResolvedValue({
      id: 'key-1',
      name: '生产调用',
      keyPrefix: 'sk-yzh-abcd',
      allowedModels: ['text-basic', 'multimodal-basic'],
      status: 'ACTIVE',
      expiresAt: null,
      secret: 'sk-yzh-secret',
    })
    const wrapper = mount(ApiKeysView, {
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    await wrapper.get('[data-test=create-key]').trigger('click')
    expect(wrapper.text()).toContain('multimodal-basic')
    await wrapper.get('[data-test=key-name]').setValue('生产调用')
    await wrapper.get('[data-test=submit-key]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('sk-yzh-secret')
    expect(api.createApiKey).toHaveBeenCalledWith('生产调用', ['text-basic', 'multimodal-basic'])
    expect(useApiKeysStore().keys).toEqual([
      expect.objectContaining({ id: 'key-1', keyPrefix: 'sk-yzh-abcd' }),
    ])
    await wrapper.get('[data-test=close-secret]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('sk-yzh-secret')
  })

  it.each(['resolve', 'reject'] as const)(
    'ignores an identity-A API key page load that later %s after identity B takes over',
    async (outcome) => {
      const listRequest = deferred<unknown[]>()
      const quotaRequest = deferred<{
        activePackage: { allowedModels: string[] }
        queuedPackages: unknown[]
      }>()
      api.listApiKeys.mockReturnValue(listRequest.promise)
      api.quotaSummary.mockReturnValue(quotaRequest.promise)
      const auth = useAuthStore()
      auth.setSession('token-a', user())
      const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })

      auth.setSession('token-b', {
        ...user(),
        id: '71000000-0000-4000-8000-000000000002',
        tenantId: '61000000-0000-4000-8000-000000000002',
      })
      quotaRequest.resolve({
        activePackage: { allowedModels: ['account-a-only-model'] },
        queuedPackages: [],
      })
      if (outcome === 'resolve') listRequest.resolve([])
      else listRequest.reject(new Error('late account A load failure'))
      await flushPromises()

      expect(wrapper.text()).not.toContain('account-a-only-model')
      expect(wrapper.get('[data-test=create-key]').attributes('disabled')).toBeDefined()
      expect(wrapper.find('.el-alert').exists()).toBe(false)
    },
  )

  it('does not reveal a late identity-A create result after identity B takes over', async () => {
    api.listApiKeys.mockResolvedValue([])
    api.quotaSummary.mockResolvedValue({
      activePackage: { allowedModels: ['test-model'] },
      queuedPackages: [],
    })
    const createRequest = deferred<Awaited<ReturnType<typeof api.createApiKey>>>()
    api.createApiKey.mockReturnValue(createRequest.promise)
    const auth = useAuthStore()
    auth.setSession('token-a', user())
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=create-key]').trigger('click')
    await wrapper.get('[data-test=key-name]').setValue('Late Account A Key')
    await wrapper.get('[data-test=submit-key]').trigger('click')
    await Promise.resolve()
    auth.setSession('token-b', {
      ...user(),
      id: '71000000-0000-4000-8000-000000000002',
      tenantId: '61000000-0000-4000-8000-000000000002',
    })
    createRequest.resolve({
      id: 'late-a',
      name: 'Late Account A Key',
      keyPrefix: 'test-prefix-late-a',
      allowedModels: ['test-model'],
      status: 'ACTIVE',
      expiresAt: null,
      secret: 'test-secret-late-a',
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain('test-secret-late-a')
    expect(wrapper.find('[data-test=close-secret]').exists()).toBe(false)
  })

  it.each(['identity change', 'logout'] as const)(
    'closes and clears an open API key secret on %s',
    async (transition) => {
      api.listApiKeys.mockResolvedValue([])
      api.quotaSummary.mockResolvedValue({
        activePackage: { allowedModels: ['test-model'] },
        queuedPackages: [],
      })
      api.createApiKey.mockResolvedValue({
        id: 'created-a',
        name: 'Account A Key',
        keyPrefix: 'test-prefix-created-a',
        allowedModels: ['test-model'],
        status: 'ACTIVE',
        expiresAt: null,
        secret: 'test-secret-created-a',
      })
      const auth = useAuthStore()
      auth.setSession('token-a', user())
      const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
      await flushPromises()

      await wrapper.get('[data-test=create-key]').trigger('click')
      await wrapper.get('[data-test=key-name]').setValue('Account A Key')
      await wrapper.get('[data-test=submit-key]').trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('test-secret-created-a')

      if (transition === 'logout') auth.clearSession()
      else {
        auth.setSession('token-b', {
          ...user(),
          id: '71000000-0000-4000-8000-000000000002',
          tenantId: '61000000-0000-4000-8000-000000000002',
        })
      }
      await flushPromises()

      expect(wrapper.text()).not.toContain('test-secret-created-a')
      expect(wrapper.get('[data-test=close-secret]').isVisible()).toBe(false)
    },
  )

  it.each(['status', 'revoke'] as const)(
    'does not show a late identity-A %s error after identity B takes over',
    async (operation) => {
      const activeKey = {
        id: 'key-a', name: 'Account A', keyPrefix: 'test-prefix-a', allowedModels: ['test-model'],
        status: 'ACTIVE', lastUsedAt: null, expiresAt: null, createdAt: '2026-08-03T00:00:00Z',
      }
      api.listApiKeys.mockResolvedValue([activeKey])
      api.quotaSummary.mockResolvedValue({
        activePackage: { allowedModels: ['test-model'] },
        queuedPackages: [],
      })
      const mutation = deferred<never>()
      if (operation === 'status') api.changeApiKeyStatus.mockReturnValue(mutation.promise)
      else api.revokeApiKey.mockReturnValue(mutation.promise)
      vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
      const auth = useAuthStore()
      auth.setSession('token-a', user())
      const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
      await flushPromises()

      await wrapper.get(operation === 'status' ? '[data-test=disable-key]' : '[data-test=revoke-key]').trigger('click')
      await Promise.resolve()
      auth.setSession('token-b', {
        ...user(),
        id: '71000000-0000-4000-8000-000000000002',
        tenantId: '61000000-0000-4000-8000-000000000002',
      })
      mutation.reject(new Error(`late account A ${operation} failure`))
      await flushPromises()

      expect(wrapper.find('.el-alert').exists()).toBe(false)
    },
  )

  it.each(['identity change', 'logout', 'unmount'] as const)(
    'closes an identity-A confirmation on %s without continuing the mutation',
    async (transition) => {
      api.listApiKeys.mockResolvedValue([{
        id: 'key-a', name: 'Account A', keyPrefix: 'test-prefix-a', allowedModels: ['test-model'],
        status: 'ACTIVE', lastUsedAt: null, expiresAt: null, createdAt: '2026-08-03T00:00:00Z',
      }])
      api.quotaSummary.mockResolvedValue({
        activePackage: { allowedModels: ['test-model'] },
        queuedPackages: [],
      })
      api.revokeApiKey.mockResolvedValue(undefined)
      const confirmation = deferred<string>()
      vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(confirmation.promise)
      const close = vi.spyOn(ElMessageBox, 'close').mockImplementation(() => undefined)
      const auth = useAuthStore()
      auth.setSession('token-a', user())
      const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
      await flushPromises()

      await wrapper.get('[data-test=revoke-key]').trigger('click')
      await Promise.resolve()
      if (transition === 'unmount') wrapper.unmount()
      else if (transition === 'logout') auth.clearSession()
      else {
        auth.setSession('token-b', {
          ...user(),
          id: '71000000-0000-4000-8000-000000000002',
          tenantId: '61000000-0000-4000-8000-000000000002',
        })
      }
      confirmation.resolve('confirm')
      await flushPromises()

      expect(close).toHaveBeenCalledTimes(1)
      expect(api.revokeApiKey).not.toHaveBeenCalled()
    },
  )

  it.each(['identity change', 'logout', 'unmount'] as const)(
    'does not close a global confirmation on %s when this page has none pending',
    async (transition) => {
      api.listApiKeys.mockResolvedValue([])
      api.quotaSummary.mockResolvedValue({ activePackage: null, queuedPackages: [] })
      const close = vi.spyOn(ElMessageBox, 'close').mockImplementation(() => undefined)
      const auth = useAuthStore()
      auth.setSession('token-a', user())
      const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
      await flushPromises()

      if (transition === 'unmount') wrapper.unmount()
      else if (transition === 'logout') auth.clearSession()
      else {
        auth.setSession('token-b', {
          ...user(),
          id: '71000000-0000-4000-8000-000000000002',
          tenantId: '61000000-0000-4000-8000-000000000002',
        })
      }
      await flushPromises()

      expect(close).not.toHaveBeenCalled()
    },
  )

  it('allows only one API key confirmation at a time', async () => {
    api.listApiKeys.mockResolvedValue([{
      id: 'key-a', name: 'Account A', keyPrefix: 'test-prefix-a', allowedModels: ['test-model'],
      status: 'ACTIVE', lastUsedAt: null, expiresAt: null, createdAt: '2026-08-03T00:00:00Z',
    }])
    api.quotaSummary.mockResolvedValue({
      activePackage: { allowedModels: ['test-model'] },
      queuedPackages: [],
    })
    const confirmation = deferred<string>()
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(confirmation.promise)
    const auth = useAuthStore()
    auth.setSession('token-a', user())
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=revoke-key]').trigger('click')
    await wrapper.get('[data-test=disable-key]').trigger('click')
    await Promise.resolve()
    wrapper.unmount()
    confirmation.resolve('confirm')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(api.revokeApiKey).not.toHaveBeenCalled()
    expect(api.changeApiKeyStatus).not.toHaveBeenCalled()
  })

  it('revokes an active API key after explicit confirmation', async () => {
    api.listApiKeys.mockResolvedValue([{
      id: 'key-1', name: 'Production', keyPrefix: 'sk-yzh-abcd', allowedModels: ['deepseek-chat'],
      status: 'ACTIVE', lastUsedAt: null, expiresAt: null, createdAt: '2026-07-30T04:00:00Z',
    }])
    api.quotaSummary.mockResolvedValue({
      activePackage: { modelQuotas: [{ modelCode: 'deepseek-chat' }] },
      queuedPackages: [],
    })
    api.revokeApiKey.mockResolvedValue(undefined)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const consoleWarn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=revoke-key]').trigger('click')
    await flushPromises()

    expect(api.revokeApiKey).toHaveBeenCalledWith('key-1')
    expect(useApiKeysStore().keys).toEqual([])
    expect(wrapper.find('.key-row').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('sk-yzh-abcd')
    expect(wrapper.text()).not.toContain('REVOKED')
    expect(consoleWarn).not.toHaveBeenCalled()
  })

  it('changes an active API key to disabled after confirmation and shows pending state', async () => {
    const activeKey = {
      id: 'key-1', name: 'Production', keyPrefix: 'sk-yzh-abcd', allowedModels: ['deepseek-chat'],
      status: 'ACTIVE', lastUsedAt: null, expiresAt: null, createdAt: '2026-07-30T04:00:00Z',
    }
    api.listApiKeys.mockResolvedValue([activeKey])
    api.quotaSummary.mockResolvedValue({
      activePackage: { modelQuotas: [{ modelCode: 'deepseek-chat' }] },
      queuedPackages: [],
    })
    let resolveStatus!: (key: typeof activeKey) => void
    api.changeApiKeyStatus.mockImplementation(() => new Promise((resolve) => {
      resolveStatus = resolve
    }))
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[data-test=revoke-key]').exists()).toBe(true)
    await wrapper.get('[data-test=disable-key]').trigger('click')
    await Promise.resolve()

    expect(api.changeApiKeyStatus).toHaveBeenCalledWith('key-1', 'DISABLED')
    expect(wrapper.get('[data-test=disable-key]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test=status-spinner]').exists()).toBe(true)

    resolveStatus({ ...activeKey, status: 'DISABLED' })
    await flushPromises()
    expect(useApiKeysStore().keys[0]?.status).toBe('DISABLED')
    expect(wrapper.find('[data-test=enable-key]').exists()).toBe(true)
    expect(wrapper.find('[data-test=revoke-key]').exists()).toBe(true)
  })

  it('changes a disabled API key back to active after confirmation', async () => {
    const disabledKey = {
      id: 'key-1', name: 'Production', keyPrefix: 'sk-yzh-abcd', allowedModels: ['deepseek-chat'],
      status: 'DISABLED', lastUsedAt: null, expiresAt: null, createdAt: '2026-07-30T04:00:00Z',
    }
    api.listApiKeys.mockResolvedValue([disabledKey])
    api.quotaSummary.mockResolvedValue({
      activePackage: { modelQuotas: [{ modelCode: 'deepseek-chat' }] },
      queuedPackages: [],
    })
    api.changeApiKeyStatus.mockResolvedValue({ ...disabledKey, status: 'ACTIVE' })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=enable-key]').trigger('click')
    await flushPromises()

    expect(api.changeApiKeyStatus).toHaveBeenCalledWith('key-1', 'ACTIVE')
    expect(useApiKeysStore().keys[0]?.status).toBe('ACTIVE')
    expect(wrapper.find('[data-test=disable-key]').exists()).toBe(true)
    expect(wrapper.find('[data-test=revoke-key]').exists()).toBe(true)
  })

  it('retains the API key status and shows feedback when a status update fails', async () => {
    api.listApiKeys.mockResolvedValue([{
      id: 'key-1', name: 'Production', keyPrefix: 'sk-yzh-abcd', allowedModels: ['deepseek-chat'],
      status: 'ACTIVE', lastUsedAt: null, expiresAt: null, createdAt: '2026-07-30T04:00:00Z',
    }])
    api.quotaSummary.mockResolvedValue({
      activePackage: { modelQuotas: [{ modelCode: 'deepseek-chat' }] },
      queuedPackages: [],
    })
    api.changeApiKeyStatus.mockRejectedValue(new Error('network unavailable'))
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=disable-key]').trigger('click')
    await flushPromises()

    expect(useApiKeysStore().keys[0]?.status).toBe('ACTIVE')
    expect(wrapper.text()).not.toContain('密钥状态更新失败，请稍后重试。')
    expect(document.body.textContent).toContain('密钥状态更新失败，请稍后重试。')
  })

  it('renders dashboard metrics from quota, key, and usage APIs', async () => {
    api.quotaSummary.mockResolvedValue(quotaFixture())
    api.listApiKeys.mockResolvedValue([
      { id: 'key-1', status: 'ACTIVE' },
      { id: 'key-2', status: 'REVOKED' },
    ])
    api.listUsage.mockResolvedValue(usageFixture())
    api.usageMetrics.mockResolvedValue({ sampleCount: 2, platformAddedP95Ms: 900 })
    api.getUsageAnalytics.mockResolvedValue({
      range: { days: 30, timezone: 'Asia/Shanghai', from: '2026-07-04', to: '2026-08-02' },
      summary: {
        todayRequests: 2,
        todaySuccessfulRequests: 2,
        todayFailedRequests: 0,
        todayTokens: 300,
        periodRequests: 2,
        periodTokens: 3_000,
        platformAddedP95Ms: 900,
      },
      daily: [],
      models: [],
    })
    const wrapper = mount(DashboardView, {
      global: {
        plugins: [ElementPlus],
        stubs: {
          AppChart: { template: '<div />' },
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.get('[data-test=dashboard-period-tokens]').text()).toContain('3,000')
    expect(wrapper.get('[data-test=dashboard-today-requests]').text()).toContain('2')
    expect(wrapper.find('[data-test=dashboard-active-keys]').exists()).toBe(false)
    expect(api.listApiKeys).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test=dashboard-p95]').text()).toContain('900')
    expect(wrapper.text()).toContain('P95 平台新增耗时')
    expect(wrapper.text()).toContain('2026-08-30 08:00:00')
    expect(wrapper.get('[data-test=dashboard-quota]').text()).toContain('3,000')
    expect(wrapper.text()).not.toContain('可用余额')
    expect(wrapper.text()).not.toContain('¥')
  })

  it('renders total and model quota without hiding reservations', async () => {
    api.quotaSummary.mockResolvedValue(quotaFixture())
    const wrapper = mount(QuotaView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('预占')
    expect(wrapper.text()).toContain('500')
    expect(wrapper.text()).toContain('deepseek-chat')
    expect(wrapper.text()).toContain('2,800')
  })

  it('keeps usage loading active when an obsolete request settles first', async () => {
    const stalePage = deferred<ReturnType<typeof usageFixture>>()
    const currentPage = deferred<ReturnType<typeof usageFixture>>()
    api.listUsage
      .mockImplementationOnce(() => stalePage.promise)
      .mockImplementationOnce(() => currentPage.promise)
    const router = await authenticatedRouterAt('/app/usage?requestId=old-query')
    mount(UsageLogsView, { global: { plugins: [router, ElementPlus] } })
    await vi.waitFor(() => expect(api.listUsage.mock.calls.filter(([query]) => query?.size !== 1)).toHaveLength(1))

    await router.push('/app/usage?requestId=current-query')
    await vi.waitFor(() => expect(api.listUsage.mock.calls.filter(([query]) => query?.size !== 1)).toHaveLength(2))

    stalePage.resolve(usageFixture())
    await flushPromises()
    expect(useUsageStore().loadingUsage).toBe(true)

    currentPage.resolve(usageFixture())
    await flushPromises()
    expect(useUsageStore().loadingUsage).toBe(false)
  })

  it('ignores an obsolete usage failure after the current request succeeds', async () => {
    const stalePage = deferred<ReturnType<typeof usageFixture>>()
    const currentPage = deferred<ReturnType<typeof usageFixture>>()
    api.listUsage
      .mockImplementationOnce(() => stalePage.promise)
      .mockImplementationOnce(() => currentPage.promise)
    const router = await authenticatedRouterAt('/app/usage?requestId=old-query')
    const wrapper = mount(UsageLogsView, { global: { plugins: [router, ElementPlus] } })
    await vi.waitFor(() => expect(api.listUsage.mock.calls.filter(([query]) => query?.size !== 1)).toHaveLength(1))

    await router.push('/app/usage?requestId=current-query')
    await vi.waitFor(() => expect(api.listUsage.mock.calls.filter(([query]) => query?.size !== 1)).toHaveLength(2))

    currentPage.resolve(usageFixture())
    await flushPromises()
    stalePage.reject(new Error('obsolete failure'))
    await flushPromises()

    expect(wrapper.findComponent({ name: 'ElAlert' }).exists()).toBe(false)
  })

  it('keeps restored usage data when a pre-back-navigation request resolves last', async () => {
    const stalePage = deferred<ReturnType<typeof usageFixture>>()
    const restoredPage = deferred<ReturnType<typeof usageFixture>>()
    const pages = [Promise.resolve(usageFixture()), stalePage.promise, restoredPage.promise]
    api.listUsage.mockImplementation(query => query?.size === 1
      ? Promise.resolve({ ...usageFixture(), total: 0 })
      : pages.shift()!)
    const router = await authenticatedRouterAt('/app/usage?requestId=restored-query')
    const wrapper = mount(UsageLogsView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    await router.push('/app/usage?requestId=stale-query')
    await vi.waitFor(() => expect(api.listUsage.mock.calls.filter(([query]) => query?.size !== 1)).toHaveLength(2))

    router.back()
    await vi.waitFor(() => {
      expect(router.currentRoute.value.query).toEqual({ requestId: 'restored-query' })
      expect(api.listUsage.mock.calls.filter(([query]) => query?.size !== 1)).toHaveLength(3)
    })

    restoredPage.resolve({
      ...usageFixture(),
      items: [{ ...usageFixture().items[0]!, requestId: 'restored-usage' }],
    })
    await flushPromises()
    expect(wrapper.text()).toContain('restored-usage')

    stalePage.resolve({
      ...usageFixture(),
      items: [{ ...usageFixture().items[0]!, requestId: 'stale-usage' }],
    })
    await flushPromises()
    expect(wrapper.text()).toContain('restored-usage')
    expect(wrapper.text()).not.toContain('stale-usage')
  })

  it('keeps the newest usage counts when an older filter completes later', async () => {
    const oldSuccess = deferred<ReturnType<typeof usageFixture>>()
    const oldFailed = deferred<ReturnType<typeof usageFixture>>()
    api.listUsage.mockImplementation(query => {
      if (query?.size !== 1) return Promise.resolve(usageFixture())
      if (query.requestId === 'old-query') return query.outcome === 'SUCCEEDED' ? oldSuccess.promise : oldFailed.promise
      return Promise.resolve({ ...usageFixture(), total: query.outcome === 'SUCCEEDED' ? 7 : 2 })
    })
    const router = await authenticatedRouterAt('/app/usage?requestId=old-query')
    const wrapper = mount(UsageLogsView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    await router.push('/app/usage?requestId=new-query')
    await flushPromises()
    expect(wrapper.findAll('.stat-value').slice(1, 3).map(node => node.text())).toEqual(['7', '2'])
    oldSuccess.resolve({ ...usageFixture(), total: 999 })
    oldFailed.resolve({ ...usageFixture(), total: 888 })
    await flushPromises()
    expect(wrapper.findAll('.stat-value').slice(1, 3).map(node => node.text())).toEqual(['7', '2'])
    wrapper.unmount()
  })

  it('restores usage filters and page from a direct query', async () => {
    api.listUsage.mockResolvedValue({ ...usageFixture(), total: 45 })
    const router = await authenticatedRouterAt('/app/usage?requestId=req_123&modelCode=deepseek-chat&outcome=FAILED&page=2')
    const wrapper = mount(UsageLogsView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.get('[data-test=usage-request-id]').attributes('aria-label')).toBe('请求 ID')
    expect(wrapper.get('input[name=model-code]').attributes('aria-label')).toBe('模型代码')
    expect((wrapper.get('[data-test=usage-request-id]').element as HTMLInputElement).value).toBe('req_123')
    expect((wrapper.get('input[name=model-code]').element as HTMLInputElement).value).toBe('deepseek-chat')
    expect(wrapper.getComponent({ name: 'ElSelect' }).props('modelValue')).toBe('FAILED')
    expect(wrapper.getComponent({ name: 'ElPagination' }).props('currentPage')).toBe(2)
    expect(api.listUsage.mock.calls.filter(([query]) => query?.size !== 1).at(-1)?.[0]).toEqual({
      page: 1, size: 20, requestId: 'req_123', modelCode: 'deepseek-chat', outcome: 'FAILED',
    })
    expect(wrapper.text()).toContain('deepseek-chat')
    expect(wrapper.text()).toContain('200')
    expect(wrapper.text()).toContain('310 ms')
    expect(wrapper.text()).toContain('2026-07-30 12:00:00')
    expect(wrapper.text()).not.toContain('vision-basic')
    expect(wrapper.text()).not.toContain('stage-provider-only')
    expect(wrapper.text()).not.toContain('stage-channel-only')
    expect(wrapper.text()).not.toContain('stage-upstream-only')
    expect(wrapper.text()).not.toContain('¥0.07')
  })

  it('normalizes a usage deep link beyond the last result page', async () => {
    api.listUsage.mockResolvedValue({ ...usageFixture(), total: 1 })
    const router = await authenticatedRouterAt('/app/usage?modelCode=deepseek-chat&page=3')
    mount(UsageLogsView, { global: { plugins: [router, ElementPlus] } })

    await vi.waitFor(() => {
      expect(router.currentRoute.value.query).toEqual({ modelCode: 'deepseek-chat' })
      expect(api.listUsage.mock.calls.filter(([query]) => query?.size !== 1).at(-1)?.[0]).toEqual({
        page: 0, size: 20, requestId: '', modelCode: 'deepseek-chat', outcome: '',
      })
    })
  })

  it('falls back to safe usage defaults for invalid query values', async () => {
    api.listUsage.mockResolvedValue(usageFixture())
    const router = await authenticatedRouterAt('/app/usage?requestId=req_123&requestId=req_456&modelCode=qwen&outcome=UNKNOWN&page=2147483647')
    const wrapper = mount(UsageLogsView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    expect((wrapper.get('[data-test=usage-request-id]').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('input[name=model-code]').element as HTMLInputElement).value).toBe('qwen')
    expect(wrapper.getComponent({ name: 'ElSelect' }).props('modelValue')).toBe('')
    expect(api.listUsage.mock.calls.filter(([query]) => query?.size !== 1).at(-1)?.[0]).toEqual({
      page: 0, size: 20, requestId: '', modelCode: 'qwen', outcome: '',
    })
  })

  it('pushes usage state to the query and restores it on back navigation', async () => {
    api.listUsage.mockResolvedValue({ ...usageFixture(), total: 45 })
    const router = await authenticatedRouterAt('/app/usage')
    const wrapper = mount(UsageLogsView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=usage-request-id]').setValue(' req_123 ')
    await wrapper.get('input[name=model-code]').setValue(' deepseek-chat ')
    const outcome = wrapper.getComponent({ name: 'ElSelect' })
    outcome.vm.$emit('update:modelValue', 'SUCCEEDED')
    await wrapper.get('.filter-bar').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({
      requestId: 'req_123', modelCode: 'deepseek-chat', outcome: 'SUCCEEDED',
    })
    expect(api.listUsage.mock.calls.filter(([query]) => query?.size !== 1).at(-1)?.[0]).toEqual({
      page: 0, size: 20, requestId: 'req_123', modelCode: 'deepseek-chat', outcome: 'SUCCEEDED',
    })

    await vi.waitFor(() => {
      expect(wrapper.getComponent({ name: 'ElPagination' }).props('total')).toBe(45)
    })
    await wrapper.get('.btn-next').trigger('click')
    await vi.waitFor(() => {
      expect(router.currentRoute.value.query).toEqual({
        requestId: 'req_123', modelCode: 'deepseek-chat', outcome: 'SUCCEEDED', page: '2',
      })
    })
    await flushPromises()
    expect(api.listUsage.mock.calls.filter(([query]) => query?.size !== 1).at(-1)?.[0]).toEqual({
      page: 1, size: 20, requestId: 'req_123', modelCode: 'deepseek-chat', outcome: 'SUCCEEDED',
    })

    router.back()
    await vi.waitFor(() => {
      expect(router.currentRoute.value.query).toEqual({
        requestId: 'req_123', modelCode: 'deepseek-chat', outcome: 'SUCCEEDED',
      })
    })
    await flushPromises()
    expect(api.listUsage.mock.calls.filter(([query]) => query?.size !== 1).at(-1)?.[0]).toEqual({
      page: 0, size: 20, requestId: 'req_123', modelCode: 'deepseek-chat', outcome: 'SUCCEEDED',
    })
  })

  it('retries usage loading when the submitted query is already current', async () => {
    api.listUsage
      .mockRejectedValueOnce(new Error('temporary failure'))
      .mockResolvedValueOnce(usageFixture())
    const router = await authenticatedRouterAt('/app/usage?requestId=req_123')
    const wrapper = mount(UsageLogsView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('调用日志加载失败，请稍后重试。')
    expect(document.body.textContent).toContain('调用日志加载失败，请稍后重试。')

    await wrapper.get('.filter-bar').trigger('submit')
    await flushPromises()

    expect(api.listUsage.mock.calls.filter(([query]) => query?.size !== 1)).toHaveLength(2)
    expect(wrapper.text()).toContain('deepseek-chat')
  })

  it('uses a session-only API key to list files from the OpenAI-compatible endpoint', async () => {
    api.listFiles.mockResolvedValue({
      object: 'list',
      data: [{ id: 'file-1', filename: 'requirements.pdf', bytes: 1024, status: 'processed', created_at: 1_775_000_000, expires_at: 1_775_086_400 }],
    })
    const wrapper = mount(FilesView, { global: { plugins: [ElementPlus] } })

    await wrapper.get('[data-test=file-api-key]').setValue('sk-yzh-session-secret')
    await wrapper.get('.connect-panel form').trigger('submit')
    await flushPromises()

    expect(api.listFiles).toHaveBeenCalledWith('sk-yzh-session-secret')
    expect(wrapper.text()).toContain('requirements.pdf')
    expect(wrapper.text()).not.toContain('sk-yzh-session-secret')
    expect(filesViewSource).toMatch(/\.upload-button\s*\{[^}]*min-height:\s*44px;/s)
  })

  it('clears the file API key input after a failed connection', async () => {
    api.listFiles.mockRejectedValue(new Error('unauthorized'))
    const wrapper = mount(FilesView, { global: { plugins: [ElementPlus] } })
    const input = wrapper.get('[data-test=file-api-key]')

    expect(input.attributes('aria-label')).toBe('文件 API 密钥')

    await input.setValue('sk-yzh-invalid-secret')
    await wrapper.get('.connect-panel form').trigger('submit')
    await flushPromises()

    expect((input.element as HTMLInputElement).value).toBe('')
  })

  it('treats file deletion cancellation as a no-op', async () => {
    api.listFiles.mockResolvedValue({
      object: 'list',
      data: [{ id: 'file-1', filename: 'requirements.pdf', bytes: 1024, status: 'processed', created_at: 1_775_000_000, expires_at: 1_775_086_400 }],
    })
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = mount(FilesView, { global: { plugins: [ElementPlus] } })
    await wrapper.get('[data-test=file-api-key]').setValue('sk-yzh-session-secret')
    await wrapper.get('.connect-panel form').trigger('submit')
    await flushPromises()

    await wrapper.get('[data-test=file-delete-file-1]').trigger('click')
    await flushPromises()

    expect(api.deleteFile).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('requirements.pdf')
    expect(wrapper.text()).not.toContain('文件删除失败')
  })

  it('retains the file row and shows feedback when deletion fails', async () => {
    api.listFiles.mockResolvedValue({
      object: 'list',
      data: [{ id: 'file-1', filename: 'requirements.pdf', bytes: 1024, status: 'processed', created_at: 1_775_000_000, expires_at: 1_775_086_400 }],
    })
    api.deleteFile.mockRejectedValue(new Error('storage unavailable'))
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mount(FilesView, { global: { plugins: [ElementPlus] } })
    await wrapper.get('[data-test=file-api-key]').setValue('sk-yzh-session-secret')
    await wrapper.get('.connect-panel form').trigger('submit')
    await flushPromises()

    await wrapper.get('[data-test=file-delete-file-1]').trigger('click')
    await flushPromises()

    expect(api.deleteFile).toHaveBeenCalledWith('sk-yzh-session-secret', 'file-1')
    expect(wrapper.text()).toContain('requirements.pdf')
    expect(wrapper.text()).not.toContain('文件删除失败，请稍后重试。')
    expect(document.body.textContent).toContain('文件删除失败，请稍后重试。')
  })

  it('keeps order loading active when an obsolete request settles first', async () => {
    const stalePage = deferred<ReturnType<typeof orderFixture>>()
    const currentPage = deferred<ReturnType<typeof orderFixture>>()
    api.listOrders
      .mockImplementationOnce(() => stalePage.promise)
      .mockImplementationOnce(() => currentPage.promise)
    const router = await authenticatedRouterAt('/app/orders?status=PAID')
    const wrapper = mount(OrdersView, { global: { plugins: [router, ElementPlus] } })
    await vi.waitFor(() => expect(api.listOrders).toHaveBeenCalledTimes(1))

    await router.push('/app/orders?status=CLOSED')
    await vi.waitFor(() => expect(api.listOrders).toHaveBeenCalledTimes(2))

    stalePage.resolve(orderFixture())
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ElSkeleton' }).exists()).toBe(true)

    currentPage.resolve(orderFixture())
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ElSkeleton' }).exists()).toBe(false)
  })

  it('ignores an obsolete order failure after the current request succeeds', async () => {
    const stalePage = deferred<ReturnType<typeof orderFixture>>()
    const currentPage = deferred<ReturnType<typeof orderFixture>>()
    api.listOrders
      .mockImplementationOnce(() => stalePage.promise)
      .mockImplementationOnce(() => currentPage.promise)
    const router = await authenticatedRouterAt('/app/orders?status=PAID')
    const wrapper = mount(OrdersView, { global: { plugins: [router, ElementPlus] } })
    await vi.waitFor(() => expect(api.listOrders).toHaveBeenCalledTimes(1))

    await router.push('/app/orders?status=CLOSED')
    await vi.waitFor(() => expect(api.listOrders).toHaveBeenCalledTimes(2))

    currentPage.resolve(orderFixture())
    await flushPromises()
    stalePage.reject(new Error('obsolete failure'))
    await flushPromises()

    expect(wrapper.findComponent({ name: 'ElAlert' }).exists()).toBe(false)
  })

  it('keeps restored order data when a pre-back-navigation request resolves last', async () => {
    const stalePage = deferred<ReturnType<typeof orderFixture>>()
    const restoredPage = deferred<ReturnType<typeof orderFixture>>()
    api.listOrders
      .mockResolvedValueOnce(orderFixture())
      .mockImplementationOnce(() => stalePage.promise)
      .mockImplementationOnce(() => restoredPage.promise)
    const router = await authenticatedRouterAt('/app/orders?status=PAID')
    const wrapper = mount(OrdersView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    wrapper.getComponent({ name: 'ElPagination' }).vm.$emit('current-change', 2)
    await vi.waitFor(() => {
      expect(router.currentRoute.value.query).toEqual({ status: 'PAID', page: '2' })
      expect(api.listOrders).toHaveBeenCalledTimes(2)
    })

    router.back()
    await vi.waitFor(() => {
      expect(router.currentRoute.value.query).toEqual({ status: 'PAID' })
      expect(api.listOrders).toHaveBeenCalledTimes(3)
    })

    restoredPage.resolve({
      ...orderFixture(),
      items: [{ ...orderFixture().items[0]!, orderNo: 'restored-order' }],
    })
    await flushPromises()
    expect(wrapper.text()).toContain('restored-order')

    stalePage.resolve({
      ...orderFixture(),
      items: [{ ...orderFixture().items[0]!, orderNo: 'stale-order' }],
    })
    await flushPromises()
    expect(wrapper.text()).toContain('restored-order')
    expect(wrapper.text()).not.toContain('stale-order')
  })

  it('restores order status and page from a direct query', async () => {
    api.listOrders.mockResolvedValue(orderFixture())
    api.getOrderDetail.mockResolvedValue(orderDetail({
      order: { ...orderDetail().order, status: 'PAID', paymentStatus: 'SUCCEEDED' },
      paymentAttempts: [{ ...orderDetail().paymentAttempts[0], status: 'SUCCEEDED' }],
      refund: { refundNo: 'ref_1', status: 'PENDING_APPROVAL', refundType: 'PARTIAL', amount: 7425, requestedAt: '2026-08-02T12:00:03Z', reviewedAt: null, completedAt: null },
    }))
    const router = await authenticatedRouterAt('/app/orders?status=PAID&page=2&size=5')
    const wrapper = mount(OrdersView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('order-user-001')
    expect(wrapper.text()).toContain('已支付')
    expect(wrapper.text()).toContain('2026-07-30 11:00:00')
    expect(wrapper.text()).not.toContain('发票')
    expect(wrapper.get('[data-test=orders-status-PAID]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.findAll('.status-tab').map((tab) => tab.text())).toEqual(['全部', '已支付', '待支付', '已退款', '已关闭'])
    expect(wrapper.getComponent({ name: 'ElPagination' }).props('currentPage')).toBe(2)
    expect(wrapper.getComponent({ name: 'ElPagination' }).props('pageSize')).toBe(5)
    expect(api.listOrders).toHaveBeenLastCalledWith(1, 5, 'PAID')
    await wrapper.get('[data-test=view-order]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test=order-detail]').text()).toContain('支付成功')
    expect(wrapper.get('[data-test=order-detail]').text()).toContain('待审核')
  })

  it('uses semantic colors for pending paid and closed orders', async () => {
    const baseOrder = orderFixture().items[0]!
    api.listOrders.mockResolvedValue({
      ...orderFixture(),
      total: 3,
      items: [
        { ...baseOrder, id: 'pending-id', orderNo: 'pending-order', status: 'PENDING' },
        { ...baseOrder, id: 'paid-id', orderNo: 'paid-order', status: 'PAID' },
        { ...baseOrder, id: 'closed-id', orderNo: 'closed-order', status: 'CLOSED' },
      ],
    })
    api.getOrderDetail.mockResolvedValue(orderDetail({
      order: { ...orderDetail().order, id: 'closed-id', orderNo: 'closed-order', status: 'CLOSED' },
    }))
    const router = await authenticatedRouterAt('/app/orders')
    const wrapper = mount(OrdersView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    const statusTags = wrapper.findAll('.orders-table tbody .status-badge')
    expect(statusTags).toHaveLength(3)
    expect(statusTags[0]!.classes()).toContain('status--warning')
    expect(statusTags[1]!.classes()).toContain('status--success')
    expect(statusTags[2]!.classes()).toContain('status--info')

    await wrapper.findAll('[data-test=view-order]')[2]!.trigger('click')
    await flushPromises()
    expect(wrapper.get('.detail-title .status-badge').classes()).toContain('status--info')
    const summaryValues = wrapper.findAll('.detail-summary .detail-value')
    expect(summaryValues).toHaveLength(8)
    expect(wrapper.get('.detail-title code').classes()).toContain('detail-value')
    expect(wrapper.findAll('.detail-summary dd').every((value) => value.classes().includes('detail-value'))).toBe(true)
  })

  it('normalizes an order deep link beyond the last result page', async () => {
    api.listOrders.mockResolvedValue({ ...orderFixture(), total: 1 })
    const router = await authenticatedRouterAt('/app/orders?status=PAID&page=3')
    mount(OrdersView, { global: { plugins: [router, ElementPlus] } })

    await vi.waitFor(() => {
      expect(router.currentRoute.value.query).toEqual({ status: 'PAID' })
      expect(api.listOrders).toHaveBeenLastCalledWith(0, 20, 'PAID')
    })
  })

  it('falls back to safe order defaults for invalid query values', async () => {
    api.listOrders.mockResolvedValue({ ...orderFixture(), total: 0, items: [] })
    const router = await authenticatedRouterAt('/app/orders?status=UNKNOWN&page=-1')
    const wrapper = mount(OrdersView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.get('[data-test=orders-status-all]').attributes('aria-pressed')).toBe('true')
    expect(api.listOrders).toHaveBeenLastCalledWith(0, 20, '')
  })

  it('pushes order state to the query and restores it on back navigation', async () => {
    api.listOrders.mockResolvedValue(orderFixture())
    const router = await authenticatedRouterAt('/app/orders')
    const wrapper = mount(OrdersView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=orders-status-PAID]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ status: 'PAID' })
    expect(api.listOrders).toHaveBeenLastCalledWith(0, 20, 'PAID')

    wrapper.getComponent({ name: 'ElPagination' }).vm.$emit('current-change', 2)
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ status: 'PAID', page: '2' })
    expect(api.listOrders).toHaveBeenLastCalledWith(1, 20, 'PAID')

    router.back()
    await vi.waitFor(() => {
      expect(router.currentRoute.value.query).toEqual({ status: 'PAID' })
    })
    await flushPromises()
    expect(api.listOrders).toHaveBeenLastCalledWith(0, 20, 'PAID')
  })

  it('clears sensitive verification state after failure and emits only an opaque proof on recovery', async () => {
    const loader = smsComponents['../../src/components/sms/SmsVerificationDialog.vue']
    expect(loader).toBeTypeOf('function')
    if (!loader) return
    const component = (await loader() as { default: object }).default
    api.requestSmsChallenge
      .mockResolvedValueOnce({
        id: 'challenge-failed', purpose: 'API_KEY_RESET', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
      })
      .mockResolvedValueOnce({
        id: 'challenge-retry', purpose: 'API_KEY_RESET', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
      })
    api.verifySmsChallenge
      .mockRejectedValueOnce(new Error('invalid code'))
      .mockResolvedValueOnce({ smsProof: 'opaque-proof', purpose: 'API_KEY_RESET', expiresAt: '2026-08-04T00:05:00Z' })
    const wrapper = mount(component, {
      props: { modelValue: true, purpose: 'API_KEY_RESET' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('000000')
    await wrapper.get('[data-test=sms-verify]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role=alert]').exists()).toBe(false)
    expect(document.body.textContent).toContain('验证失败，请重新发送验证码后重试。')
    expect((wrapper.get('[data-test=sms-code] input').element as HTMLInputElement).value).toBe('')
    expect(wrapper.get('[data-test=sms-send]').attributes('disabled')).toBeUndefined()

    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('123456')
    await wrapper.get('[data-test=sms-verify]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('verified')).toEqual([['opaque-proof']])
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
    expect(wrapper.text()).not.toContain('opaque-proof')
  })

  it('ignores an obsolete verification after close, purpose change, and reopen', async () => {
    const loader = smsComponents['../../src/components/sms/SmsVerificationDialog.vue']
    expect(loader).toBeTypeOf('function')
    if (!loader) return
    const component = (await loader() as { default: object }).default
    const obsolete = deferred<{ smsProof: string; purpose: 'API_KEY_RESET'; expiresAt: string }>()
    const current = deferred<{ smsProof: string; purpose: 'PAYMENT_CONFIRM'; expiresAt: string }>()
    api.requestSmsChallenge
      .mockResolvedValueOnce({
        id: 'challenge-obsolete-verify', purpose: 'API_KEY_RESET', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
      })
      .mockResolvedValueOnce({
        id: 'challenge-current-verify', purpose: 'PAYMENT_CONFIRM', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
      })
    api.verifySmsChallenge
      .mockReturnValueOnce(obsolete.promise)
      .mockReturnValueOnce(current.promise)
    const wrapper = mount(component, {
      props: { modelValue: true, purpose: 'API_KEY_RESET' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('111111')
    await wrapper.get('[data-test=sms-verify]').trigger('click')
    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ purpose: 'PAYMENT_CONFIRM' })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('222222')
    await wrapper.get('[data-test=sms-verify]').trigger('click')

    obsolete.resolve({ smsProof: 'obsolete-proof', purpose: 'API_KEY_RESET', expiresAt: '2026-08-04T00:05:00Z' })
    await flushPromises()
    expect(wrapper.emitted('verified')).toBeUndefined()
    expect((wrapper.get('[data-test=sms-code] input').element as HTMLInputElement).value).toBe('222222')
    expect(wrapper.find('[role=alert]').exists()).toBe(false)

    current.resolve({ smsProof: 'current-proof', purpose: 'PAYMENT_CONFIRM', expiresAt: '2026-08-04T00:05:00Z' })
    await flushPromises()
    expect(wrapper.emitted('verified')).toEqual([['current-proof']])
    wrapper.unmount()
  })

  it('does not emit an obsolete verification after unmount', async () => {
    const loader = smsComponents['../../src/components/sms/SmsVerificationDialog.vue']
    expect(loader).toBeTypeOf('function')
    if (!loader) return
    const component = (await loader() as { default: object }).default
    const pending = deferred<{ smsProof: string; purpose: 'API_KEY_RESET'; expiresAt: string }>()
    api.requestSmsChallenge.mockResolvedValue({
      id: 'challenge-unmount-verify', purpose: 'API_KEY_RESET', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.verifySmsChallenge.mockReturnValue(pending.promise)
    const wrapper = mount(component, {
      props: { modelValue: true, purpose: 'API_KEY_RESET' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('111111')
    await wrapper.get('[data-test=sms-verify]').trigger('click')
    wrapper.unmount()

    pending.resolve({ smsProof: 'proof-after-unmount', purpose: 'API_KEY_RESET', expiresAt: '2026-08-04T00:05:00Z' })
    await flushPromises()
    expect(wrapper.emitted('verified')).toBeUndefined()
  })

  it('verifies a bound payment before creating the payment attempt with one proof', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
    api.getProfile.mockResolvedValue({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
      sessions: [],
    })
    api.createOrder.mockResolvedValue(createdOrder())
    api.requestSmsChallenge.mockResolvedValue({
      id: 'challenge-payment', purpose: 'PAYMENT_CONFIRM', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.verifySmsChallenge.mockResolvedValue({
      smsProof: 'payment-proof', purpose: 'PAYMENT_CONFIRM', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.createPaymentAttempt.mockResolvedValue({
      transactionNo: 'txn-proof', status: 'PENDING', expiresAt: createdOrder().expiresAt,
      action: { type: 'NONE', expiresAt: createdOrder().expiresAt },
    })
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=start-payment]').trigger('click')
    await flushPromises()
    expect(api.createPaymentAttempt).not.toHaveBeenCalled()

    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('223344')
    await wrapper.get('[data-test=sms-verify]').trigger('click')
    await flushPromises()

    expect(api.createPaymentAttempt).toHaveBeenCalledTimes(1)
    expect(api.createPaymentAttempt).toHaveBeenCalledWith(
      'ord_1', 'WECHAT_PAY', 'WECHAT_NATIVE', 'payment-proof',
    )
    wrapper.unmount()
  })

  it('keeps a late profile requirement current while an order is being created', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
    const profileRequest = deferred<Awaited<ReturnType<typeof api.getProfile>>>()
    api.getProfile.mockReturnValue(profileRequest.promise)
    api.createOrder.mockResolvedValue(createdOrder())
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    profileRequest.resolve({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
      sessions: [],
    })
    await flushPromises()

    expect(wrapper.get('[data-test=start-payment]').attributes('disabled')).toBeUndefined()
    await wrapper.get('[data-test=start-payment]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test=sms-verification-dialog]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('ignores a payment proof captured for an order replaced while verification was open', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T12:00:00Z'))
    api.getProfile.mockResolvedValue({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
      sessions: [],
    })
    api.createOrder
      .mockResolvedValueOnce(createdOrder())
      .mockResolvedValueOnce({ ...createdOrder(), orderNo: 'ord_2' })
    const wrapper = mount(CheckoutDialog, {
      props: { modelValue: true, plan: paidPlan() },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=start-payment]').trigger('click')
    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    await wrapper.get('[data-test=checkout-create-order]').trigger('click')
    await flushPromises()

    wrapper.getComponent({ name: 'SmsVerificationDialog' }).vm.$emit('verified', 'proof-for-ord-1')
    await flushPromises()
    expect(api.createPaymentAttempt).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('rotates a bound API key after verification and clears the returned secret after one display', async () => {
    api.listApiKeys.mockResolvedValue([{
      id: 'key-1', name: 'Production', keyPrefix: 'sk-yzh-old', allowedModels: ['text-basic'],
      status: 'ACTIVE', lastUsedAt: null, expiresAt: null, createdAt: '2026-08-04T00:00:00Z',
    }])
    api.quotaSummary.mockResolvedValue({ activePackage: { allowedModels: ['text-basic'] }, queuedPackages: [] })
    api.getProfile.mockResolvedValue({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
      sessions: [],
    })
    api.requestSmsChallenge.mockResolvedValue({
      id: 'challenge-key', purpose: 'API_KEY_RESET', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.verifySmsChallenge.mockResolvedValue({
      smsProof: 'key-proof', purpose: 'API_KEY_RESET', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.resetApiKey.mockResolvedValue({ id: 'key-1', keyPrefix: 'sk-yzh-new', secret: 'sk-yzh-rotated-once' })
    useAuthStore().setSession('access-token', user())
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=reset-key]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('334455')
    await wrapper.get('[data-test=sms-verify]').trigger('click')
    await flushPromises()

    expect(api.resetApiKey).toHaveBeenCalledTimes(1)
    expect(api.resetApiKey).toHaveBeenCalledWith('key-1', 'key-proof')
    expect(wrapper.text()).toContain('sk-yzh-rotated-once')
    expect(useApiKeysStore().keys[0]?.keyPrefix).toBe('sk-yzh-new')
    await wrapper.get('[data-test=close-secret]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('sk-yzh-rotated-once')
  })

  it('retries an API key reset after 503 with the same transient proof and one secret display', async () => {
    api.listApiKeys.mockResolvedValue([{
      id: 'key-503', name: 'Production', keyPrefix: 'sk-yzh-old', allowedModels: ['text-basic'],
      status: 'ACTIVE', lastUsedAt: null, expiresAt: null, createdAt: '2026-08-04T00:00:00Z',
    }])
    api.quotaSummary.mockResolvedValue({ activePackage: { allowedModels: ['text-basic'] }, queuedPackages: [] })
    api.getProfile.mockResolvedValue({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
      sessions: [],
    })
    api.requestSmsChallenge.mockResolvedValue({
      id: 'challenge-key-503', purpose: 'API_KEY_RESET', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.verifySmsChallenge.mockResolvedValue({
      smsProof: 'proof-reused-after-503', purpose: 'API_KEY_RESET', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.resetApiKey
      .mockRejectedValueOnce(Object.assign(new Error('temporary gateway failure'), { response: { status: 503 } }))
      .mockResolvedValueOnce({ id: 'key-503', keyPrefix: 'sk-yzh-new', secret: 'sk-yzh-once-after-retry' })
    useAuthStore().setSession('access-token', user())
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=reset-key]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('334455')
    await wrapper.get('[data-test=sms-verify]').trigger('click')
    await flushPromises()

    expect(api.resetApiKey).toHaveBeenCalledTimes(1)
    expect(api.resetApiKey).toHaveBeenLastCalledWith('key-503', 'proof-reused-after-503')
    expect(wrapper.find('[data-test=sms-verification-dialog]').exists()).toBe(false)
    expect(wrapper.find('[data-test=retry-reset-key]').exists()).toBe(true)
    await wrapper.get('[data-test=retry-reset-key]').trigger('click')
    await flushPromises()

    expect(api.resetApiKey).toHaveBeenCalledTimes(2)
    expect(api.resetApiKey).toHaveBeenLastCalledWith('key-503', 'proof-reused-after-503')
    expect(api.requestSmsChallenge).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('.secret-value')).toHaveLength(1)
    expect(wrapper.text()).toContain('sk-yzh-once-after-retry')
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('discards a reset success and retry proof after the target API key disappears', async () => {
    const targetKey = {
      id: 'key-removed', name: 'Production', keyPrefix: 'sk-yzh-old', allowedModels: ['text-basic'],
      status: 'ACTIVE' as const, lastUsedAt: null, expiresAt: null, createdAt: '2026-08-04T00:00:00Z',
    }
    api.listApiKeys.mockResolvedValue([targetKey])
    api.quotaSummary.mockResolvedValue({ activePackage: { allowedModels: ['text-basic'] }, queuedPackages: [] })
    api.getProfile.mockResolvedValue({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
      sessions: [],
    })
    api.requestSmsChallenge.mockResolvedValue({
      id: 'challenge-key-removed', purpose: 'API_KEY_RESET', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.verifySmsChallenge.mockResolvedValue({
      smsProof: 'removed-key-proof', purpose: 'API_KEY_RESET', expiresAt: '2026-08-04T00:05:00Z',
    })
    const pendingRetry = deferred<{ id: string; keyPrefix: string; secret: string }>()
    api.resetApiKey
      .mockRejectedValueOnce(Object.assign(new Error('temporary gateway failure'), { response: { status: 503 } }))
      .mockReturnValueOnce(pendingRetry.promise)
    useAuthStore().setSession('access-token', user())
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=reset-key]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('334455')
    await wrapper.get('[data-test=sms-verify]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test=retry-reset-key]').exists()).toBe(true)

    await wrapper.get('[data-test=retry-reset-key]').trigger('click')
    await flushPromises()
    expect(api.resetApiKey).toHaveBeenCalledTimes(2)
    expect(api.resetApiKey).toHaveBeenLastCalledWith('key-removed', 'removed-key-proof')

    const apiKeysStore = useApiKeysStore()
    apiKeysStore.keys = []
    pendingRetry.resolve({ id: 'key-removed', keyPrefix: 'sk-yzh-new', secret: 'sk-yzh-stale-secret' })
    await flushPromises()

    expect(wrapper.find('.secret-value').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('sk-yzh-stale-secret')
    apiKeysStore.keys = [{ ...targetKey }]
    await flushPromises()
    expect(wrapper.text()).toContain('sk-yzh-old')
    expect(wrapper.text()).not.toContain('sk-yzh-new')
    expect(wrapper.find('[data-test=retry-reset-key]').exists()).toBe(false)

    await wrapper.get('[data-test=reset-key]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test=sms-verification-dialog]').exists()).toBe(true)
    expect(api.resetApiKey).toHaveBeenCalledTimes(2)
  })

  it('ignores an old reset success after the same key ID starts a new lifecycle', async () => {
    const targetKey = {
      id: 'key-success-epoch', name: 'Production', keyPrefix: 'sk-yzh-old', allowedModels: ['text-basic'],
      status: 'ACTIVE' as const, lastUsedAt: null, expiresAt: null, createdAt: '2026-08-04T00:00:00Z',
    }
    api.listApiKeys.mockResolvedValue([targetKey])
    api.quotaSummary.mockResolvedValue({ activePackage: { allowedModels: ['text-basic'] }, queuedPackages: [] })
    api.getProfile.mockResolvedValue({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
      sessions: [],
    })
    api.requestSmsChallenge.mockResolvedValue({
      id: 'challenge-key-success-epoch', purpose: 'API_KEY_RESET', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.verifySmsChallenge
      .mockResolvedValueOnce({ smsProof: 'old-success-proof', purpose: 'API_KEY_RESET', expiresAt: '2026-08-04T00:05:00Z' })
      .mockResolvedValueOnce({ smsProof: 'current-success-proof', purpose: 'API_KEY_RESET', expiresAt: '2026-08-04T00:05:00Z' })
    const oldReset = deferred<{ id: string; keyPrefix: string; secret: string }>()
    const currentReset = deferred<{ id: string; keyPrefix: string; secret: string }>()
    api.resetApiKey
      .mockReturnValueOnce(oldReset.promise)
      .mockReturnValueOnce(currentReset.promise)
    useAuthStore().setSession('access-token', user())
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=reset-key]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('334455')
    await wrapper.get('[data-test=sms-verify]').trigger('click')
    await flushPromises()
    expect(api.resetApiKey).toHaveBeenCalledTimes(1)

    const apiKeysStore = useApiKeysStore()
    apiKeysStore.keys = []
    apiKeysStore.keys = [{ ...targetKey }]
    await flushPromises()
    expect(wrapper.get('[data-test=reset-key]').attributes('disabled')).toBeUndefined()

    await wrapper.get('[data-test=reset-key]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('556677')
    await wrapper.get('[data-test=sms-verify]').trigger('click')
    await flushPromises()
    expect(api.requestSmsChallenge).toHaveBeenCalledTimes(2)
    expect(api.resetApiKey).toHaveBeenCalledTimes(2)

    oldReset.resolve({ id: 'key-success-epoch', keyPrefix: 'sk-yzh-stale', secret: 'sk-yzh-stale-secret' })
    await flushPromises()
    expect(wrapper.find('.secret-value').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('sk-yzh-stale-secret')
    expect(wrapper.text()).toContain('sk-yzh-old')
    expect(wrapper.text()).not.toContain('sk-yzh-stale')
    expect(wrapper.get('[data-test=reset-key]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test=retry-reset-key]').exists()).toBe(false)

    currentReset.resolve({ id: 'key-success-epoch', keyPrefix: 'sk-yzh-current', secret: 'sk-yzh-current-secret' })
    await flushPromises()
    expect(wrapper.text()).toContain('sk-yzh-current')
    expect(wrapper.text()).toContain('sk-yzh-current-secret')
  })

  it('ignores an old reset 503 after the same key ID enters a new lifecycle', async () => {
    const targetKey = {
      id: 'key-503-epoch', name: 'Production', keyPrefix: 'sk-yzh-old', allowedModels: ['text-basic'],
      status: 'ACTIVE' as const, lastUsedAt: null, expiresAt: null, createdAt: '2026-08-04T00:00:00Z',
    }
    api.listApiKeys.mockResolvedValue([targetKey])
    api.quotaSummary.mockResolvedValue({ activePackage: { allowedModels: ['text-basic'] }, queuedPackages: [] })
    api.getProfile.mockResolvedValue({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
      sessions: [],
    })
    api.requestSmsChallenge.mockResolvedValue({
      id: 'challenge-key-503-epoch', purpose: 'API_KEY_RESET', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.verifySmsChallenge.mockResolvedValue({
      smsProof: 'old-503-proof', purpose: 'API_KEY_RESET', expiresAt: '2026-08-04T00:05:00Z',
    })
    const oldReset = deferred<{ id: string; keyPrefix: string; secret: string }>()
    api.resetApiKey.mockReturnValue(oldReset.promise)
    useAuthStore().setSession('access-token', user())
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=reset-key]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('334455')
    await wrapper.get('[data-test=sms-verify]').trigger('click')
    await flushPromises()
    expect(api.resetApiKey).toHaveBeenCalledTimes(1)

    const apiKeysStore = useApiKeysStore()
    apiKeysStore.keys[0]!.status = 'REVOKED'
    await flushPromises()
    apiKeysStore.keys = [{ ...targetKey, status: 'ACTIVE' }]
    await flushPromises()
    oldReset.reject(Object.assign(new Error('old gateway failure'), { response: { status: 503 } }))
    await flushPromises()

    expect(wrapper.find('[data-test=retry-reset-key]').exists()).toBe(false)
    expect(wrapper.get('[data-test=reset-key]').attributes('disabled')).toBeUndefined()
    await wrapper.get('[data-test=reset-key]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    expect(api.requestSmsChallenge).toHaveBeenCalledTimes(2)
    expect(api.resetApiKey).toHaveBeenCalledTimes(1)
  })

  it.each(['removed', 'revoked'] as const)(
    'clears a retained reset proof when its target key is %s',
    async (targetState) => {
      const targetKey = {
        id: 'key-lifecycle', name: 'Production', keyPrefix: 'sk-yzh-old', allowedModels: ['text-basic'],
        status: 'ACTIVE' as const, lastUsedAt: null, expiresAt: null, createdAt: '2026-08-04T00:00:00Z',
      }
      api.listApiKeys.mockResolvedValue([targetKey])
      api.quotaSummary.mockResolvedValue({ activePackage: { allowedModels: ['text-basic'] }, queuedPackages: [] })
      api.getProfile.mockResolvedValue({
        ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
        maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
        sessions: [],
      })
      api.requestSmsChallenge.mockResolvedValue({
        id: 'challenge-key-lifecycle', purpose: 'API_KEY_RESET', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
      })
      api.verifySmsChallenge.mockResolvedValue({
        smsProof: 'lifecycle-proof', purpose: 'API_KEY_RESET', expiresAt: '2026-08-04T00:05:00Z',
      })
      api.resetApiKey.mockRejectedValue(
        Object.assign(new Error('temporary gateway failure'), { response: { status: 503 } }),
      )
      useAuthStore().setSession('access-token', user())
      const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
      await flushPromises()

      await wrapper.get('[data-test=reset-key]').trigger('click')
      await flushPromises()
      await wrapper.get('[data-test=sms-send]').trigger('click')
      await flushPromises()
      await wrapper.get('[data-test=sms-code] input').setValue('334455')
      await wrapper.get('[data-test=sms-verify]').trigger('click')
      await flushPromises()
      expect(api.resetApiKey).toHaveBeenCalledTimes(1)
      expect(api.requestSmsChallenge).toHaveBeenCalledTimes(1)

      const staleRetryButton = wrapper.get('[data-test=retry-reset-key]')
      const apiKeysStore = useApiKeysStore()
      if (targetState === 'removed') apiKeysStore.keys = []
      else apiKeysStore.keys[0]!.status = 'REVOKED'
      await staleRetryButton.trigger('click')
      expect(api.resetApiKey).toHaveBeenCalledTimes(1)
      expect(wrapper.find('[data-test=retry-reset-key]').exists()).toBe(false)

      apiKeysStore.keys = [{ ...targetKey, status: 'ACTIVE' }]
      await flushPromises()
      expect(wrapper.find('[data-test=retry-reset-key]').exists()).toBe(false)
      await wrapper.get('[data-test=reset-key]').trigger('click')
      await flushPromises()
      await wrapper.get('[data-test=sms-send]').trigger('click')
      await flushPromises()
      expect(api.requestSmsChallenge).toHaveBeenCalledTimes(2)
      expect(api.resetApiKey).toHaveBeenCalledTimes(1)
    },
  )

  it('clears a retained reset proof when this page revokes its target key', async () => {
    const targetKey = {
      id: 'key-revoked-here', name: 'Production', keyPrefix: 'sk-yzh-old', allowedModels: ['text-basic'],
      status: 'ACTIVE' as const, lastUsedAt: null, expiresAt: null, createdAt: '2026-08-04T00:00:00Z',
    }
    api.listApiKeys.mockResolvedValue([targetKey])
    api.quotaSummary.mockResolvedValue({ activePackage: { allowedModels: ['text-basic'] }, queuedPackages: [] })
    api.getProfile.mockResolvedValue({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
      sessions: [],
    })
    api.requestSmsChallenge.mockResolvedValue({
      id: 'challenge-key-revoked-here', purpose: 'API_KEY_RESET', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.verifySmsChallenge.mockResolvedValue({
      smsProof: 'revoked-here-proof', purpose: 'API_KEY_RESET', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.resetApiKey.mockRejectedValue(
      Object.assign(new Error('temporary gateway failure'), { response: { status: 503 } }),
    )
    const pendingRevoke = deferred<void>()
    api.revokeApiKey.mockReturnValue(pendingRevoke.promise)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    useAuthStore().setSession('access-token', user())
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=reset-key]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('334455')
    await wrapper.get('[data-test=sms-verify]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test=retry-reset-key]').exists()).toBe(true)

    await wrapper.get('[data-test=revoke-key]').trigger('click')
    await flushPromises()
    expect(api.revokeApiKey).toHaveBeenCalledWith('key-revoked-here')
    expect(wrapper.find('[data-test=retry-reset-key]').exists()).toBe(true)
    pendingRevoke.resolve()
    await flushPromises()
    expect(useApiKeysStore().keys).toEqual([])
    expect(wrapper.text()).not.toContain('sk-yzh-old')
    expect(wrapper.find('[data-test=retry-reset-key]').exists()).toBe(false)

    useApiKeysStore().keys = [{ ...targetKey, status: 'ACTIVE' }]
    await flushPromises()
    expect(wrapper.find('[data-test=retry-reset-key]').exists()).toBe(false)
    await wrapper.get('[data-test=reset-key]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    expect(api.requestSmsChallenge).toHaveBeenCalledTimes(2)
    expect(api.resetApiKey).toHaveBeenCalledTimes(1)
  })

  it('discards an API key reset proof after a terminal response', async () => {
    api.listApiKeys.mockResolvedValue([{
      id: 'key-terminal', name: 'Production', keyPrefix: 'sk-yzh-old', allowedModels: ['text-basic'],
      status: 'ACTIVE', lastUsedAt: null, expiresAt: null, createdAt: '2026-08-04T00:00:00Z',
    }])
    api.quotaSummary.mockResolvedValue({ activePackage: { allowedModels: ['text-basic'] }, queuedPackages: [] })
    api.getProfile.mockResolvedValue({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
      sessions: [],
    })
    api.requestSmsChallenge.mockResolvedValue({
      id: 'challenge-key-terminal', purpose: 'API_KEY_RESET', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.verifySmsChallenge.mockResolvedValue({
      smsProof: 'terminal-proof', purpose: 'API_KEY_RESET', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.resetApiKey.mockRejectedValue(Object.assign(new Error('proof consumed'), { response: { status: 422 } }))
    useAuthStore().setSession('access-token', user())
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=reset-key]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('334455')
    await wrapper.get('[data-test=sms-verify]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test=retry-reset-key]').exists()).toBe(false)
    await wrapper.get('[data-test=reset-key]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test=sms-verification-dialog]').exists()).toBe(true)
    expect(api.resetApiKey).toHaveBeenCalledTimes(1)
  })

  it('clears a recoverable API key reset proof on identity change', async () => {
    api.listApiKeys.mockResolvedValue([{
      id: 'key-identity', name: 'Production', keyPrefix: 'sk-yzh-old', allowedModels: ['text-basic'],
      status: 'ACTIVE', lastUsedAt: null, expiresAt: null, createdAt: '2026-08-04T00:00:00Z',
    }])
    api.quotaSummary.mockResolvedValue({ activePackage: { allowedModels: ['text-basic'] }, queuedPackages: [] })
    api.getProfile.mockResolvedValue({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
      sessions: [],
    })
    api.requestSmsChallenge.mockResolvedValue({
      id: 'challenge-key-identity', purpose: 'API_KEY_RESET', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.verifySmsChallenge.mockResolvedValue({
      smsProof: 'identity-proof', purpose: 'API_KEY_RESET', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.resetApiKey.mockRejectedValue(Object.assign(new Error('temporary gateway failure'), { response: { status: 503 } }))
    const auth = useAuthStore()
    auth.setSession('token-a', user())
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=reset-key]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('334455')
    await wrapper.get('[data-test=sms-verify]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test=retry-reset-key]').exists()).toBe(true)

    auth.setSession('token-b', {
      ...user(), id: '71000000-0000-4000-8000-000000000002', tenantId: '61000000-0000-4000-8000-000000000002',
    })
    await flushPromises()
    expect(wrapper.find('[data-test=retry-reset-key]').exists()).toBe(false)
  })

  it('expires a recoverable API key reset proof after a fixed short TTL', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-04T00:00:00Z'))
    api.listApiKeys.mockResolvedValue([{
      id: 'key-expiry', name: 'Production', keyPrefix: 'sk-yzh-old', allowedModels: ['text-basic'],
      status: 'ACTIVE', lastUsedAt: null, expiresAt: null, createdAt: '2026-08-04T00:00:00Z',
    }])
    api.quotaSummary.mockResolvedValue({ activePackage: { allowedModels: ['text-basic'] }, queuedPackages: [] })
    api.getProfile.mockResolvedValue({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
      sessions: [],
    })
    api.requestSmsChallenge.mockResolvedValue({
      id: 'challenge-key-expiry', purpose: 'API_KEY_RESET', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.verifySmsChallenge.mockResolvedValue({
      smsProof: 'expiring-proof', purpose: 'API_KEY_RESET', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.resetApiKey.mockRejectedValue(Object.assign(new Error('temporary gateway failure'), { response: { status: 503 } }))
    useAuthStore().setSession('access-token', user())
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=reset-key]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('334455')
    await wrapper.get('[data-test=sms-verify]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test=retry-reset-key]').exists()).toBe(true)

    await vi.advanceTimersByTimeAsync(60_000)
    expect(wrapper.find('[data-test=retry-reset-key]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('binds a phone through issue, verify, and challenge consumption then updates SMS preference', async () => {
    const unboundProfile = {
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: null, phoneVerified: false, phoneState: 'UNBOUND', smsNotificationsEnabled: true,
      sessions: [],
    }
    api.getProfile
      .mockResolvedValueOnce(unboundProfile)
      .mockResolvedValue({
        ...unboundProfile,
        maskedPhone: '139****5678',
        phoneVerified: true,
        phoneState: 'VERIFIED',
      })
    api.requestPhoneChallenge.mockResolvedValue({
      id: 'challenge-phone', purpose: 'PHONE_BIND', maskedPhone: '139****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.verifySmsChallenge.mockResolvedValue({ smsProof: null, purpose: 'PHONE_BIND', expiresAt: '2026-08-04T00:05:00Z' })
    api.bindPhone.mockResolvedValue(undefined)
    api.updateSmsPreference.mockResolvedValue(undefined)
    const router = await authenticatedRouterAt('/app/profile')
    const wrapper = mount(ProfileView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=profile-phone]').setValue('13912345678')
    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('112233')
    await wrapper.get('[data-test=save-phone]').trigger('click')
    await flushPromises()

    expect(api.requestPhoneChallenge).toHaveBeenCalledWith('PHONE_BIND', '13912345678')
    expect(api.verifySmsChallenge).toHaveBeenCalledWith('challenge-phone', '112233')
    expect(api.bindPhone).toHaveBeenCalledWith('challenge-phone', 'PHONE_BIND')
    expect(wrapper.text()).toContain('139****5678')

    wrapper.getComponent({ name: 'ElSwitch' }).vm.$emit('change', false)
    await flushPromises()
    expect(api.updateSmsPreference).toHaveBeenCalledWith(false)
  })

  it('shows account and binding sections and loads optional subscription quota', async () => {
    api.quotaSummary.mockResolvedValue({ activePackage: null, queuedPackages: [] })
    api.getProfile.mockResolvedValue({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
      passwordSet: true, sessions: [{ id: 'session-1', status: 'ACTIVE', createdAt: '2026-07-30T04:00:00Z', expiresAt: '2026-08-30T04:00:00Z', revokedAt: null }],
    })
    const router = await authenticatedRouterAt('/app/profile')
    const wrapper = mount(ProfileView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.get('[data-test=profile-tab-basic]').attributes('aria-selected')).toBe('true')
    expect(wrapper.text()).toContain('基本信息')
    expect(wrapper.text()).toContain('短信通知')
    expect(wrapper.text()).toContain('邮箱地址')
    expect(wrapper.text()).not.toContain('当前订阅套餐')
    expect(wrapper.text()).toContain('正常')
    expect(wrapper.text()).not.toContain('ACTIVE')
    expect(wrapper.text()).toContain('手机号')
    expect(wrapper.get('[aria-label="账户摘要"]').text()).toContain('当前套餐')
    expect(wrapper.get('.sidebar-meta').text()).toContain('账户状态')
    expect(wrapper.get('.sidebar-meta').text()).toContain('最近登录')
    expect(wrapper.text()).not.toContain('登录会话')
    expect(wrapper.find('[data-test=password-form]').exists()).toBe(false)
    expect(api.quotaSummary).toHaveBeenCalledTimes(1)

    await wrapper.get('[data-test=profile-tab-security]').trigger('click')
    expect(wrapper.get('[data-test=profile-tab-security]').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('[data-test=password-form]').exists()).toBe(true)
    expect(wrapper.get('[data-test=password-mode-current]').attributes('aria-selected')).toBe('true')
    const recoveryButton = wrapper.get('[data-test=password-mode-recovery]')
    expect(recoveryButton.text()).toBe('通过验证码重置')
    expect(router.currentRoute.value.fullPath).toBe('/app/profile')
    await recoveryButton.trigger('click')
    expect(recoveryButton.attributes('aria-selected')).toBe('true')
    expect(wrapper.find('[data-test=password-form]').exists()).toBe(false)
    expect(wrapper.get('[data-test=password-recovery-panel]').text()).toContain('当前登录会话将失效')
    expect(router.currentRoute.value.fullPath).toBe('/app/profile')
    expect(wrapper.find('[aria-label="账户摘要"]').exists()).toBe(false)
  })

  it('updates the profile display name and the authenticated identity', async () => {
    api.getProfile.mockResolvedValue({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
      passwordSet: true, sessions: [],
    })
    api.updateDisplayName.mockResolvedValue({ displayName: '宇智用户' })
    const auth = useAuthStore()
    auth.setSession('access-token', user())
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/app/profile')
    const wrapper = mount(ProfileView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=edit-display-name]').trigger('click')
    await wrapper.get('[data-test=display-name-input]').setValue('宇智用户')
    await wrapper.get('[data-test=save-display-name]').trigger('click')
    await flushPromises()

    expect(api.updateDisplayName).toHaveBeenCalledWith('宇智用户')
    expect(wrapper.text()).toContain('宇智用户')
    expect(auth.user?.displayName).toBe('宇智用户')
  })

  it('updates the maximum login-device count from security settings', async () => {
    api.getProfile.mockResolvedValue({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: null, phoneVerified: false, phoneState: 'UNBOUND', smsNotificationsEnabled: false,
      avatarUrl: null, avatarUpdatedAt: null, maxLoginDevices: 5,
      activeDeviceCount: 1, sessions: [{ id: 'session-1', status: 'ACTIVE', createdAt: '2026-08-20T04:00:00Z', expiresAt: '2026-09-20T04:00:00Z', revokedAt: null }],
    })
    api.updateLoginDeviceLimit.mockResolvedValue({ maxLoginDevices: 7 })
    const router = await authenticatedRouterAt('/app/profile')
    const wrapper = mount(ProfileView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=profile-tab-security]').trigger('click')

    const input = wrapper.getComponent('[data-test=device-limit-input]')
    expect(input.props('modelValue')).toBe(5)
    input.vm.$emit('update:modelValue', 7)
    await flushPromises()
    await wrapper.get('[data-test=save-device-limit]').trigger('click')
    await flushPromises()

    expect(api.updateLoginDeviceLimit).toHaveBeenCalledWith(7)
    expect(wrapper.get('.device-limit-summary').text()).toContain('1 / 7')
    expect(wrapper.getComponent('[data-test=device-limit-input]').props('modelValue')).toBe(7)
  })

  it('binds a verified email from account security', async () => {
    const unboundEmailProfile = {
      ...user(), email: null, status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
      passwordSet: false, sessions: [],
    }
    api.getProfile
      .mockResolvedValueOnce(unboundEmailProfile)
      .mockResolvedValueOnce({ ...unboundEmailProfile, email: 'bound@example.com' })
    api.requestEmailChallenge.mockResolvedValue({
      id: 'challenge-email-bind', maskedEmail: 'b***@example.com', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.bindEmail.mockResolvedValue(undefined)
    const router = await authenticatedRouterAt('/app/profile')
    const wrapper = mount(ProfileView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test=profile-email]').setValue('bound@example.com')
    await wrapper.get('.email-inline-actions button').trigger('click')
    await flushPromises()
    await wrapper.get('input[aria-label="邮箱验证码"]').setValue('445566')
    await wrapper.get('[data-test=save-email]').trigger('click')
    await flushPromises()

    expect(api.requestEmailChallenge).toHaveBeenCalledWith('bound@example.com')
    expect(api.bindEmail).toHaveBeenCalledWith('bound@example.com', 'challenge-email-bind', '445566')
    expect(wrapper.text()).toContain('bound@example.com')
  })

  it('refreshes the authoritative SMS preference after a 204 update', async () => {
    const serverProfile = {
      ...user(), status: 'ACTIVE' as const, lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED' as const, smsNotificationsEnabled: true,
      sessions: [],
    }
    api.getProfile
      .mockResolvedValueOnce(serverProfile)
      .mockResolvedValueOnce({ ...serverProfile, smsNotificationsEnabled: true })
    api.updateSmsPreference.mockResolvedValue(undefined)
    const router = await authenticatedRouterAt('/app/profile')
    const wrapper = mount(ProfileView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    wrapper.getComponent({ name: 'ElSwitch' }).vm.$emit('change', false)
    await flushPromises()

    expect(api.updateSmsPreference).toHaveBeenCalledWith(false)
    expect(api.getProfile).toHaveBeenCalledTimes(2)
    expect(wrapper.getComponent({ name: 'ElSwitch' }).props('modelValue')).toBe(true)
  })

  it('requires shared SMS verification for a bound password change and forwards the proof once', async () => {
    api.getProfile.mockResolvedValue({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
      sessions: [],
    })
    api.requestSmsChallenge.mockResolvedValue({
      id: 'challenge-password', purpose: 'CHANGE_PASSWORD', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.verifySmsChallenge.mockResolvedValue({
      smsProof: 'password-proof', purpose: 'CHANGE_PASSWORD', expiresAt: '2026-08-04T00:05:00Z',
    })
    api.changePassword.mockResolvedValue(undefined)
    const auth = useAuthStore()
    auth.setSession('access-token', user())
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/app/profile')
    const wrapper = mount(ProfileView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=profile-tab-security]').trigger('click')

    await wrapper.get('[data-test=current-password]').setValue('ValidPassw0rd!')
    await wrapper.get('[data-test=new-password]').setValue('ChangedPass456!')
    await wrapper.get('[data-test=confirm-new-password]').setValue('ChangedPass456!')
    await wrapper.get('[data-test=password-form]').trigger('submit')
    await flushPromises()
    expect(api.changePassword).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test=sms-verification-dialog]').exists()).toBe(true)

    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=sms-code] input').setValue('654321')
    await wrapper.get('[data-test=sms-verify]').trigger('click')
    await flushPromises()

    expect(api.requestSmsChallenge).toHaveBeenCalledWith('CHANGE_PASSWORD')
    expect(api.verifySmsChallenge).toHaveBeenCalledWith('challenge-password', '654321')
    expect(api.changePassword).toHaveBeenCalledTimes(1)
    expect(api.changePassword).toHaveBeenCalledWith('ValidPassw0rd!', 'ChangedPass456!', 'password-proof')
    expect(auth.accessToken).toBeNull()
  })

  it('explains invalid password fields, focuses the first error, and exposes visibility toggles', async () => {
    api.getProfile.mockResolvedValue({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: null, phoneVerified: false, phoneState: 'UNBOUND', smsNotificationsEnabled: false,
      passwordSet: true, sessions: [],
    })
    const auth = useAuthStore()
    auth.setSession('access-token', user())
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/app/profile')
    const wrapper = mount(ProfileView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test=profile-tab-security]').trigger('click')

    const passwordInputs = wrapper.findAllComponents({ name: 'ElInput' })
      .filter((input) => input.props('type') === 'password')
    expect(passwordInputs).toHaveLength(3)
    expect(passwordInputs.every((input) => input.props('showPassword') === true)).toBe(true)
    expect(wrapper.get('[data-test=update-password]').attributes('disabled')).toBeUndefined()

    await wrapper.get('[data-test=password-form]').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-test=current-password-error]').text()).toBe('请输入当前密码。')
    expect(wrapper.get('[data-test=new-password-error]').text()).toBe('请输入新密码。')
    expect(wrapper.get('[data-test=confirm-new-password-error]').text()).toBe('请再次输入新密码。')
    expect(document.activeElement?.id).toBe('current-password')
    expect(api.changePassword).not.toHaveBeenCalled()

    await wrapper.get('[data-test=current-password]').setValue('ValidPassw0rd!')
    await wrapper.get('[data-test=new-password]').setValue('lowercase1!')
    await wrapper.get('[data-test=new-password]').trigger('blur')
    await wrapper.get('[data-test=confirm-new-password]').setValue('Different1!')
    await wrapper.get('[data-test=confirm-new-password]').trigger('blur')

    expect(wrapper.get('[data-test=new-password-error]').text()).toBe('新密码需要包含大写字母。')
    expect(wrapper.get('[data-test=confirm-new-password-error]').text()).toBe('两次输入的新密码不一致。')

    await wrapper.get('[data-test=new-password]').setValue('ValidPassw0rd!')
    await wrapper.get('[data-test=confirm-new-password]').setValue('ValidPassw0rd!')
    await wrapper.get('[data-test=password-form]').trigger('submit')
    expect(wrapper.get('[data-test=new-password-error]').text()).toBe('新密码不能与当前密码相同。')
    expect(document.activeElement?.id).toBe('new-password')
    expect(api.changePassword).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('ignores a password proof captured before the authenticated identity changes', async () => {
    api.getProfile.mockResolvedValue({
      ...user(), status: 'ACTIVE', lastLoginAt: null, createdAt: '2026-07-01T04:00:00Z',
      maskedPhone: '138****5678', phoneVerified: true, phoneState: 'VERIFIED', smsNotificationsEnabled: true,
      sessions: [],
    })
    const auth = useAuthStore()
    auth.setSession('token-a', user())
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/app/profile')
    const wrapper = mount(ProfileView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test=profile-tab-security]').trigger('click')
    await wrapper.get('[data-test=current-password]').setValue('ValidPassw0rd!')
    await wrapper.get('[data-test=new-password]').setValue('ChangedPass456!')
    await wrapper.get('[data-test=confirm-new-password]').setValue('ChangedPass456!')
    await wrapper.get('[data-test=password-form]').trigger('submit')
    await flushPromises()

    auth.setSession('token-b', {
      ...user(), id: '71000000-0000-4000-8000-000000000002', tenantId: '61000000-0000-4000-8000-000000000002',
    })
    wrapper.getComponent({ name: 'SmsVerificationDialog' }).vm.$emit('verified', 'proof-for-account-a')
    await flushPromises()
    expect(api.changePassword).not.toHaveBeenCalled()
  })

  it('changes the password, clears revoked sessions, and returns to login', async () => {
    api.getProfile.mockResolvedValue({
      ...user(),
      status: 'ACTIVE',
      lastLoginAt: '2026-07-30T04:00:00Z',
      createdAt: '2026-07-01T04:00:00Z',
      sessions: [{ id: 'session-1', status: 'ACTIVE', createdAt: '2026-07-30T04:00:00Z', expiresAt: '2026-08-30T04:00:00Z', revokedAt: null }],
    })
    api.changePassword.mockResolvedValue(undefined)
    const auth = useAuthStore()
    auth.setSession('access-token', user())
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/app/profile')
    const wrapper = mount(ProfileView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('测试用户')
    expect(wrapper.text()).toContain('正常')
    expect(wrapper.text()).not.toContain('ACTIVE')
    await wrapper.get('[data-test=profile-tab-security]').trigger('click')
    await wrapper.get('[data-test=current-password]').setValue('ValidPassw0rd!')
    await wrapper.get('[data-test=new-password]').setValue('ChangedPass456!')
    await wrapper.get('[data-test=confirm-new-password]').setValue('ChangedPass456!')
    await wrapper.get('[data-test=password-form]').trigger('submit')
    await flushPromises()

    expect(api.changePassword).toHaveBeenCalledWith('ValidPassw0rd!', 'ChangedPass456!')
    expect(auth.accessToken).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('logs out through the platform endpoint and clears the local session', async () => {
    api.logout.mockResolvedValue(undefined)
    const auth = useAuthStore()
    auth.setSession('access-token', user())
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/app/dashboard')
    const wrapper = mount(HeaderBar, {
      props: { collapsed: false },
      global: { plugins: [router, ElementPlus] },
    })

    await wrapper.get('.identity').trigger('click')
    await vi.waitFor(() => expect(document.body.querySelector('[data-test="logout"]')).not.toBeNull())
    ;(document.body.querySelector('[data-test="logout"]') as HTMLElement).click()
    await flushPromises()

    expect(api.logout).toHaveBeenCalledTimes(1)
    expect(auth.accessToken).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('keeps the local session when server logout fails', async () => {
    api.logout.mockRejectedValue(new Error('csrf rejected'))
    const errorMessage = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    const auth = useAuthStore()
    auth.setSession('access-token', user())
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/app/dashboard')
    const wrapper = mount(HeaderBar, {
      props: { collapsed: false },
      global: { plugins: [router, ElementPlus] },
    })

    await wrapper.get('.identity').trigger('click')
    await vi.waitFor(() => expect(document.body.querySelector('[data-test="logout"]')).not.toBeNull())
    ;(document.body.querySelector('[data-test="logout"]') as HTMLElement).click()
    await flushPromises()

    expect(auth.accessToken).toBe('access-token')
    expect(router.currentRoute.value.path).toBe('/app/dashboard')
    expect(errorMessage).toHaveBeenCalledTimes(1)
  })

  // ---------------------------------------------------------------------------
  // Design 10 requires that a subscriber's "next renewal" shows the real automatic
  // charge time, not the package expiry. The two are different days (the charge
  // happens 24h before expiry), so showing the expiry would make the user wait for a
  // charge on the wrong day. A non-subscriber has no automatic charge at all, so those
  // surfaces must keep the expiry semantics they had before.
  // ---------------------------------------------------------------------------
  it('shows the real automatic charge time for a subscriber instead of the package expiry', async () => {
    api.quotaSummary.mockResolvedValue({
      activePackage: { ...quotaFixture().activePackage, expiresAt: '2026-09-30T00:00:00Z' },
      queuedPackages: [],
    })
    vi.mocked(listSubscriptions).mockResolvedValue([
      { ...subscriptionFixture(), nextChargeAt: '2026-09-29T08:00:00Z' },
    ])

    // OrdersView watches route.query, so it needs a real router instance. An
    // authenticated session keeps the router guard from calling the restore() mock.
    useAuthStore().setSession('access-token', user())
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/app/orders')
    const wrapper = mount(OrdersView, {
      global: {
        plugins: [router, ElementPlus],
        stubs: { RouterLink: { template: '<a><slot /></a>' } },
      },
    })
    await flushPromises()

    expect(wrapper.get('[data-test="next-renewal-caption"]').text()).toBe('下次自动扣款')
    // 08:00Z is 16:00 in Asia/Shanghai, and formatDateTime is pinned to that zone.
    expect(wrapper.get('[data-test="next-renewal-value"]').text()).toBe('2026-09-29 16:00:00')
    expect(wrapper.get('[data-test="next-renewal-value"]').text()).not.toContain('2026-09-30')
  })

  it('keeps the package expiry wording for a one-time purchaser with no subscription', async () => {
    api.quotaSummary.mockResolvedValue({
      activePackage: { ...quotaFixture().activePackage, expiresAt: '2026-09-30T00:00:00Z' },
      queuedPackages: [],
    })
    // The shared mock already resolves to an empty list, which is the "no subscription"
    // case; assert it explicitly so this test cannot pass for the wrong reason.
    vi.mocked(listSubscriptions).mockResolvedValue([])

    useAuthStore().setSession('access-token', user())
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/app/orders')
    const wrapper = mount(OrdersView, {
      global: {
        plugins: [router, ElementPlus],
        stubs: { RouterLink: { template: '<a><slot /></a>' } },
      },
    })
    await flushPromises()

    expect(wrapper.get('[data-test="next-renewal-caption"]').text()).toBe('套餐到期')
    expect(wrapper.get('[data-test="next-renewal-value"]').text()).toBe('2026-09-30 08:00:00')
  })

  it('says "time until next renewal" on the dashboard only when a subscription exists', async () => {
    api.quotaSummary.mockResolvedValue({
      activePackage: { ...quotaFixture().activePackage, expiresAt: expiresAtInDays(10) },
      queuedPackages: [],
    })
    vi.mocked(listSubscriptions).mockResolvedValue([
      { ...subscriptionFixture(), nextChargeAt: expiresAtInDays(20) },
    ])
    // Same supporting mocks the existing dashboard test uses; without them the other
    // dashboard panels error out and can leave the greeting without a loaded quota.
    api.listUsage.mockResolvedValue(usageFixture())
    api.usageMetrics.mockResolvedValue({ sampleCount: 2, platformAddedP95Ms: 900 })
    api.getUsageAnalytics.mockResolvedValue({
      range: { days: 7 },
      summary: { periodTokens: 3_000, todayRequests: 2 },
      daily: [],
      models: [],
    })

    useAuthStore().setSession('access-token', user())
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/app/dashboard')
    const wrapper = mount(DashboardView, {
      global: {
        plugins: [router, ElementPlus],
        stubs: {
          AppChart: { template: '<div />' },
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.get('[data-test="dashboard-renewal-caption"]').text()).toBe('距下次续费还有')
    const days = Number(wrapper.get('[data-test="dashboard-days-to-renewal"]').text())
    // Driven by nextChargeAt (20 days out), not by the package expiry (10 days out).
    // The computed value rounds up, so it is 20 or 21 depending on the time of day.
    expect(days).toBeGreaterThanOrEqual(20)
    expect(days).toBeLessThanOrEqual(21)
  })

  it('keeps "time until package expiry" on the dashboard when there is no subscription', async () => {
    api.quotaSummary.mockResolvedValue({
      activePackage: { ...quotaFixture().activePackage, expiresAt: expiresAtInDays(10) },
      queuedPackages: [],
    })
    vi.mocked(listSubscriptions).mockResolvedValue([])
    api.listUsage.mockResolvedValue(usageFixture())
    api.usageMetrics.mockResolvedValue({ sampleCount: 2, platformAddedP95Ms: 900 })
    api.getUsageAnalytics.mockResolvedValue({
      range: { days: 7 },
      summary: { periodTokens: 3_000, todayRequests: 2 },
      daily: [],
      models: [],
    })

    useAuthStore().setSession('access-token', user())
    const router = createConsoleRouter(createMemoryHistory())
    await router.push('/app/dashboard')
    const wrapper = mount(DashboardView, {
      global: {
        plugins: [router, ElementPlus],
        stubs: {
          AppChart: { template: '<div />' },
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.get('[data-test="dashboard-renewal-caption"]').text()).toBe('距套餐到期还有')
    const days = Number(wrapper.get('[data-test="dashboard-days-to-renewal"]').text())
    expect(days).toBeGreaterThanOrEqual(10)
    expect(days).toBeLessThanOrEqual(11)
  })
})
