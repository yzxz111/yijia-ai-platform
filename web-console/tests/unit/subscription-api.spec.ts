import { beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest'

const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('../../src/api/http', () => ({
  platformHttp: { get: http.get, post: http.post },
}))

import {
  cancelSubscription,
  getSubscription,
  listAdminSubscriptions,
  listSubscriptions,
  openSubscription,
  resumeSubscription,
  retrySubscriptionCycle,
  terminateSubscription,
  type AdminSubscription,
  type SubscriptionCycle,
  type SubscriptionSummary,
} from '../../src/api/subscriptions'

const summary: SubscriptionSummary = {
  id: 'sub-1',
  packagePlanId: 'plan-1',
  planName: 'STARTER 连续包月',
  status: 'ACTIVE',
  autoRenew: true,
  cancelAtPeriodEnd: false,
  currentPeriodStart: '2026-09-24T00:00:00Z',
  currentPeriodEnd: '2026-10-24T00:00:00Z',
  nextChargeAt: '2026-10-23T00:00:00Z',
  lastChargedAmount: 2900,
  renewalCount: 0,
  consecutiveFailures: 0,
  graceUntil: null,
  lastSkipReason: null,
}

describe('subscription API contract', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    http.get.mockResolvedValue({ data: [] })
    http.post.mockResolvedValue({ data: summary })
  })

  it('keeps money as backend-owned integer minor units', () => {
    expectTypeOf<SubscriptionSummary['lastChargedAmount']>().toEqualTypeOf<number | null>()
    expectTypeOf<SubscriptionCycle['amount']>().toEqualTypeOf<number>()
    // 金额绝不是字符串：把它当字符串会让「分/元」的换算悄悄发生在比较与排序里。
    expectTypeOf<SubscriptionSummary['lastChargedAmount']>().not.toEqualTypeOf<string>()
  })

  it('opens a subscription with a mandatory idempotency key', async () => {
    await openSubscription('version-1', 'open-key-1')

    expect(http.post).toHaveBeenCalledExactlyOnceWith(
      '/console/platform/subscriptions',
      { packageVersionId: 'version-1' },
      { headers: { 'Idempotency-Key': 'open-key-1' } },
    )
  })

  it('lists and reads the caller own subscriptions', async () => {
    await listSubscriptions()
    expect(http.get).toHaveBeenCalledWith('/console/platform/subscriptions')

    await getSubscription('sub-1')
    expect(http.get).toHaveBeenCalledWith('/console/platform/subscriptions/sub-1')
  })

  it('cancels and resumes through distinct endpoints', async () => {
    await cancelSubscription('sub-1')
    expect(http.post).toHaveBeenCalledWith('/console/platform/subscriptions/sub-1/cancel')

    await resumeSubscription('sub-1')
    expect(http.post).toHaveBeenCalledWith('/console/platform/subscriptions/sub-1/resume')
  })

  it('omits empty admin filters instead of sending blank query values', async () => {
    // 空字符串筛选会让后端把 status='' 当成一个未知状态而返回 422，
    // 因此「没有选择」必须是「不发送该参数」，而不是发送空值。
    await listAdminSubscriptions({ page: 0, size: 20, status: null, planCode: '', userId: null })

    expect(http.get).toHaveBeenCalledExactlyOnceWith(
      '/console/platform/admin/subscriptions',
      { params: { page: 0, size: 20 } },
    )
  })

  it('sends the admin filters that were actually chosen', async () => {
    await listAdminSubscriptions({ status: 'PAST_DUE', planCode: 'STARTER', userId: 'u-1' })

    expect(http.get).toHaveBeenCalledExactlyOnceWith(
      '/console/platform/admin/subscriptions',
      { params: { page: 0, size: 20, status: 'PAST_DUE', planCode: 'STARTER', userId: 'u-1' } },
    )
  })

  it('requires a reason when terminating so the audit entry is meaningful', async () => {
    await terminateSubscription('sub-1', '用户投诉重复扣款')

    expect(http.post).toHaveBeenCalledExactlyOnceWith(
      '/console/platform/admin/subscriptions/sub-1/terminate',
      { reason: '用户投诉重复扣款' },
    )
  })

  it('retries a failed cycle through the cycle-scoped endpoint', async () => {
    await retrySubscriptionCycle('cycle-1', '用户已充值')

    expect(http.post).toHaveBeenCalledExactlyOnceWith(
      '/console/platform/admin/subscriptions/cycles/cycle-1/retry',
      { reason: '用户已充值' },
    )
  })

  it('models the admin lease flag so operators can tell running from idle', () => {
    expectTypeOf<AdminSubscription['charged']>().toEqualTypeOf<boolean>()
    expectTypeOf<AdminSubscription['lastSkipReason']>().toEqualTypeOf<
      SubscriptionSummary['lastSkipReason']
    >()
  })
})
