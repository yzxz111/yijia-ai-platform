import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import OrdersView from './OrdersView.vue'

const api = vi.hoisted(() => ({
  closeOrder: vi.fn(),
  createPaymentAttempt: vi.fn(),
  getOrderDetail: vi.fn(),
  getProfile: vi.fn(),
  listOrders: vi.fn(),
}))
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }))

vi.mock('../../api/platform', async (importOriginal) => ({
  ...await importOriginal<typeof import('../../api/platform')>(),
  ...api,
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => router,
}))
vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn() },
}))

const future = '2099-08-19T08:00:00Z'
const createdAt = '2026-08-19T08:00:00Z'
const order = {
  id: 'order-id',
  orderNo: 'ord_resume_test',
  packageCode: 'STARTER',
  packageName: '入门套餐',
  amount: 1900,
  currency: 'CNY',
  status: 'PENDING',
  settlementMethod: 'EXTERNAL',
  paymentProvider: 'WECHAT_PAY',
  paymentStatus: 'PENDING',
  refundStatus: null,
  paidAt: null,
  closedAt: null,
  createdAt,
  updatedAt: createdAt,
}
const snapshot = {
  packageVersionId: 'version-id', packageCode: 'STARTER', packageName: '入门套餐',
  priceAmount: 1900, currency: 'CNY', totalTokenQuota: 1000, durationDays: 30,
  maxApiKeys: 1, requestsPerMinute: 10, tokensPerMinute: 1000, maxConcurrency: 1,
  dailyRequestLimit: null, maxOutputTokens: null, maxFileSizeBytes: 1024,
  oncePerUser: false, models: [],
}
const action = { type: 'QR_CODE', qrContent: 'weixin://pay/resume-test', expiresAt: future }

function detail(status = 'PENDING', paymentAction: typeof action | null = action) {
  return {
    order: { ...order, paymentStatus: status },
    packageSnapshot: snapshot,
    expiresAt: future,
    paymentAttempts: [{
      transactionNo: 'pay_resume_test', provider: 'WECHAT_PAY', product: 'WECHAT_NATIVE',
      status, providerStatus: null, providerErrorCode: null, action: paymentAction,
      paidAt: null, createdAt,
    }],
    grant: null,
    refund: null,
  }
}

const stubs = {
  'el-alert': { props: ['title'], template: '<div role="alert">{{ title }}</div>' },
  'el-button': { props: ['disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  'el-drawer': { props: ['modelValue'], emits: ['update:modelValue'], template: '<aside v-if="modelValue"><slot /></aside>' },
  'el-option': { template: '<option><slot /></option>' },
  'el-pagination': { template: '<div />' },
  'el-select': { template: '<select><slot /></select>' },
  'el-skeleton': { template: '<div>加载中</div>' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-timeline': { template: '<div><slot /></div>' },
  'el-timeline-item': { template: '<div><slot /></div>' },
  'el-tooltip': { template: '<span><slot /></span>' },
  PaymentActionPane: {
    props: ['action'],
    template: '<div data-test="restored-payment-action">{{ action.type }}</div>',
  },
  RefundDialog: { template: '<div />' },
  SmsVerificationDialog: {
    props: ['modelValue'],
    emits: ['verified', 'update:modelValue'],
    template: '<button v-if="modelValue" data-test="complete-payment-verification" @click="$emit(\'verified\', \'sms-proof\')">验证</button>',
  },
}

function render() {
  return mount(OrdersView, { global: { stubs } })
}

describe('order payment resume', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listOrders.mockResolvedValue({ items: [order], total: 1, page: 0, size: 20 })
    api.getOrderDetail.mockResolvedValue(detail())
    api.getProfile.mockResolvedValue({ phoneVerified: false })
  })

  it('restores the active payment action when the user clicks pay', async () => {
    const wrapper = render()
    await flushPromises()

    await wrapper.get('[data-test="list-pay-order"]').trigger('click')
    await flushPromises()

    expect(api.getOrderDetail).toHaveBeenCalledWith(order.orderNo)
    expect(wrapper.get('[data-test="restored-payment-action"]').text()).toBe('QR_CODE')
    expect(api.createPaymentAttempt).not.toHaveBeenCalled()
  })

  it('warns instead of creating a duplicate attempt for legacy active payments', async () => {
    api.getOrderDetail.mockResolvedValue(detail('PENDING', null))
    const wrapper = render()
    await flushPromises()

    await wrapper.get('[data-test="list-pay-order"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="payment-resume-warning"]').text()).toContain('支付信息暂时无法恢复')
    expect(api.createPaymentAttempt).not.toHaveBeenCalled()
  })

  it('requires the bound-phone proof before retrying a failed payment', async () => {
    const failed = detail('FAILED', null)
    api.getOrderDetail.mockResolvedValueOnce(failed).mockResolvedValueOnce(detail())
    api.getProfile.mockResolvedValue({ phoneVerified: true })
    api.createPaymentAttempt.mockResolvedValue({
      transactionNo: 'pay_retry_test', status: 'PENDING', action, expiresAt: future,
    })
    const wrapper = render()
    await flushPromises()

    await wrapper.get('[data-test="list-pay-order"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="retry-order-payment"]').trigger('click')
    await flushPromises()

    expect(api.createPaymentAttempt).not.toHaveBeenCalled()
    await wrapper.get('[data-test="complete-payment-verification"]').trigger('click')
    await flushPromises()

    expect(api.createPaymentAttempt).toHaveBeenCalledWith(
      order.orderNo, 'WECHAT_PAY', 'WECHAT_NATIVE', 'sms-proof',
    )
  })
})
