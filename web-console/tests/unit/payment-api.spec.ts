import { beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest'

const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('../../src/api/http', () => ({
  platformHttp: { get: http.get, post: http.post },
  readCookie: vi.fn(),
}))

import {
  cancelRefund,
  claimFreePlan,
  closeOrder,
  createOrder,
  createPaymentAttempt,
  getFreePlanClaimStatus,
  getOrderDetail,
  getRefund,
  requestRefund,
  type OrderView,
  type PackageSnapshot,
  type PaymentProvider,
  type RefundCalculation,
  type RefundDetail,
} from '../../src/api/platform'

function assertImmutableSnapshot(snapshot: PackageSnapshot) {
  // @ts-expect-error 订单创建后，服务端快照不可变。
  snapshot.packageName = 'mutated'
  // @ts-expect-error 结算代码不得修改快照中的模型集合。
  snapshot.models.push({ modelCode: 'other', modelTokenQuota: null, capabilities: [] })
}

void assertImmutableSnapshot

describe('user payment API contract', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    http.get.mockResolvedValue({ data: {} })
    http.post.mockResolvedValue({ data: {} })
  })

  it('claims the free plan without browser-supplied grant facts', async () => {
    await claimFreePlan()

    expect(http.post).toHaveBeenCalledWith('/console/platform/plans/free/claim')
  })

  it('loads free-plan claim status without browser-supplied identity fields', async () => {
    http.get.mockResolvedValue({ data: { claimed: true } })

    const result = await getFreePlanClaimStatus()

    expect(result).toEqual({ claimed: true })
    expect(http.get).toHaveBeenCalledWith('/console/platform/plans/free/claim-status')
  })

  it('creates an order with only the version and idempotency key', async () => {
    await createOrder('version-1', 'checkout-001')

    expect(http.post).toHaveBeenCalledWith(
      '/console/platform/orders',
      { packageVersionId: 'version-1' },
      { headers: { 'Idempotency-Key': 'checkout-001' } },
    )
  })

  it('loads and closes an encoded order resource without browser business facts', async () => {
    await getOrderDetail('ord/1')
    await closeOrder('ord/1')

    expect(http.get).toHaveBeenCalledWith('/console/platform/orders/ord%2F1')
    expect(http.post).toHaveBeenCalledWith('/console/platform/orders/ord%2F1/close')
  })

  it('creates an attempt with only the selected provider and product', async () => {
    await createPaymentAttempt('ord_1', 'WECHAT_PAY', 'WECHAT_NATIVE')

    expect(http.post).toHaveBeenCalledWith(
      '/console/platform/orders/ord_1/payment-attempts',
      { provider: 'WECHAT_PAY', product: 'WECHAT_NATIVE' },
    )
  })

  it('requests a refund with only the user reason', async () => {
    await requestRefund('ord_1', '不再需要该套餐')

    expect(http.post).toHaveBeenCalledWith(
      '/console/platform/orders/ord_1/refunds',
      { reason: '不再需要该套餐' },
    )
  })

  it('loads and cancels an encoded refund resource without review fields', async () => {
    await getRefund('ref/1')
    await cancelRefund('ref/1')

    expect(http.get).toHaveBeenCalledWith('/console/platform/refunds/ref%2F1')
    expect(http.post).toHaveBeenCalledWith('/console/platform/refunds/ref%2F1/cancel')
  })

  it('models immutable snapshots and backend nullable transaction states', () => {
    expectTypeOf<PackageSnapshot>().toMatchTypeOf<Readonly<{
      packageVersionId: string
      packageName: string
      totalTokenQuota: number
      durationDays: number
    }>>()
    expectTypeOf<OrderView['paymentProvider']>().toEqualTypeOf<PaymentProvider | 'UNSELECTED'>()
    expectTypeOf<RefundDetail['refundType']>().toEqualTypeOf<'FULL' | 'PARTIAL' | null>()
    expectTypeOf<RefundDetail['calculation']>().toEqualTypeOf<RefundCalculation | null>()
    expectTypeOf<RefundCalculation['packageExpiresAt']>().toEqualTypeOf<string | null>()
  })
})
