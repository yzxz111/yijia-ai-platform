import { platformHttp } from './http'

/**
 * 连续包月订阅。
 *
 * 金额一律是**后端持有的整数分**（minor unit），展示时才 ÷100——与 `wallet.ts` 同一口径。
 * 前端不做任何金额运算，也不把分当元用。
 */
export type SubscriptionStatus = 'ACTIVE' | 'PAST_DUE' | 'CANCELED' | 'EXPIRED' | 'TERMINATED'
export type SubscriptionCycleStatus = 'PENDING' | 'CHARGED' | 'FAILED' | 'GRANTED'

/**
 * 正常跳过的原因（**不是失败**）。
 *
 * 后端刻意把「跳过」与「失败」分成两个字段：跳过在正常运营中会频繁发生
 * （用户提前手工续费、已持有生效中的套餐），若与「余额不足」混在一起，
 * 后台的失败率告警会长期红着，运维就会开始忽略它。
 */
export type SubscriptionSkipReason =
  | 'canceled_by_user'
  | 'subscription_not_open'
  | 'account_not_active'
  | 'active_package_present'
  | 'plan_unavailable'
  | 'subscription_price_changed'
  | 'renewal_instance_stuck_queued'
  /** 退款成功后终止（设计 §10）。 */
  | 'refund_terminated'
  /** 涨价报价长期未确认，已按终止处理（设计 §7.3 的兜底）。 */
  | 'price_not_confirmed'

export interface SubscriptionSummary {
  id: string
  packagePlanId: string
  planName: string | null
  status: SubscriptionStatus
  autoRenew: boolean
  cancelAtPeriodEnd: boolean
  currentPeriodStart: string
  currentPeriodEnd: string
  nextChargeAt: string
  /** 上一次实际扣款金额（分）。未扣过款时为 null。 */
  lastChargedAmount: number | null
  renewalCount: number
  consecutiveFailures: number
  graceUntil: string | null
  lastSkipReason: SubscriptionSkipReason | null
  /**
   * 等待确认的续费报价（分）。非空即表示该订阅因涨价处于**暂缓**状态。
   *
   * 后端在检测到「已发布价 > 用户上次实付价」时不会扣款，而是记下这个报价并通知用户；
   * 只有调用 {@link confirmSubscriptionPrice} 之后才会按新价继续扣款。若不确认，
   * 超过后端配置的时限（默认 30 天）订阅会被终止。
   */
  pendingPriceAmount: number | null
  /** 发现涨价的时刻。 */
  priceChangeDetectedAt: string | null
  /** 用户确认接受调整后价格的时刻。 */
  priceConfirmedAt: string | null
}

export interface SubscriptionCycle {
  id: string
  periodIndex: number
  periodStart: string
  periodEnd: string
  status: SubscriptionCycleStatus
  amount: number
  currency: string
  orderNo: string | null
  chargedAt: string | null
  failureReason: string | null
}

export interface SubscriptionDetail {
  subscription: SubscriptionSummary
  cycles: SubscriptionCycle[]
}

export interface AdminSubscription {
  id: string
  tenantId: string
  userId: string
  userEmail: string | null
  planCode: string
  planName: string
  status: SubscriptionStatus
  autoRenew: boolean
  cancelAtPeriodEnd: boolean
  currentPeriodStart: string
  currentPeriodEnd: string
  nextChargeAt: string
  graceUntil: string | null
  consecutiveFailures: number
  renewalCount: number
  lastChargedAmount: number | null
  lastSkipReason: SubscriptionSkipReason | null
  /** 是否正被某个调度实例处理（后端以「订阅行持有租约」判定）。 */
  charged: boolean
}

export interface AdminSubscriptionPage {
  items: AdminSubscription[]
  total: number
  page: number
  size: number
}

export interface AdminSubscriptionParams {
  page?: number
  size?: number
  status?: SubscriptionStatus | null
  planCode?: string | null
  userId?: string | null
}

export async function listSubscriptions(): Promise<SubscriptionSummary[]> {
  return (await platformHttp.get<SubscriptionSummary[]>('/console/platform/subscriptions')).data
}

export async function getSubscription(id: string): Promise<SubscriptionDetail> {
  return (await platformHttp.get<SubscriptionDetail>(
    `/console/platform/subscriptions/${id}`,
  )).data
}

/**
 * 开通连续包月，并在同一请求里完成首期支付。
 *
 * `Idempotency-Key` 必填：重复点击「开通」不能产生第二笔扣款。首期支付的 TTL 只有
 * 15 分钟，因此这个调用必须与用户点击是同一次请求，不能拆成两步。
 */
export async function openSubscription(
  packageVersionId: string,
  idempotencyKey: string,
): Promise<SubscriptionSummary> {
  return (await platformHttp.post<SubscriptionSummary>(
    '/console/platform/subscriptions',
    { packageVersionId },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )).data
}

/** 解约：当期用满为止，不再扣下一期。 */
export async function cancelSubscription(id: string): Promise<SubscriptionSummary> {
  return (await platformHttp.post<SubscriptionSummary>(
    `/console/platform/subscriptions/${id}/cancel`,
  )).data
}

/** 撤销解约。仅当当期尚未结束时允许。 */
export async function resumeSubscription(id: string): Promise<SubscriptionSummary> {
  return (await platformHttp.post<SubscriptionSummary>(
    `/console/platform/subscriptions/${id}/resume`,
  )).data
}

/**
 * 确认接受调整后的续费价（设计 §7.3）。
 *
 * 这是「因涨价而暂缓」的订阅**唯一**的恢复入口：确认后下一轮调度即按新价扣款。
 * 后端会把确认的价与**当前已发布价**再比对一次——若期间又调过价，则返回 409
 * `subscription_price_changed_again` 并要求重新确认，绝不会按一个用户没见过的价扣款。
 */
export async function confirmSubscriptionPrice(id: string): Promise<SubscriptionSummary> {
  return (await platformHttp.post<SubscriptionSummary>(
    `/console/platform/subscriptions/${id}/confirm-price`,
  )).data
}

// ---------------------------------------------------------------------------
// 后台治理
// ---------------------------------------------------------------------------

export async function listAdminSubscriptions(
  params: AdminSubscriptionParams = {},
): Promise<AdminSubscriptionPage> {
  return (await platformHttp.get<AdminSubscriptionPage>(
    '/console/platform/admin/subscriptions',
    {
      params: {
        page: params.page ?? 0,
        size: params.size ?? 20,
        ...(params.status ? { status: params.status } : {}),
        ...(params.planCode ? { planCode: params.planCode } : {}),
        ...(params.userId ? { userId: params.userId } : {}),
      },
    },
  )).data
}

export async function getAdminSubscription(id: string): Promise<SubscriptionDetail & {
  subscription: AdminSubscription
}> {
  return (await platformHttp.get<SubscriptionDetail & { subscription: AdminSubscription }>(
    `/console/platform/admin/subscriptions/${id}`,
  )).data
}

/** 人工终止。原因必填——后端会把它写进审计。 */
export async function terminateSubscription(id: string, reason: string): Promise<AdminSubscription> {
  return (await platformHttp.post<AdminSubscription>(
    `/console/platform/admin/subscriptions/${id}/terminate`,
    { reason },
  )).data
}

export interface RetryResult {
  retried: boolean
  subscriptionId: string
  periodIndex: number
  outcome: string
}

/** 人工触发一次失败账期的重试。幂等：账期已不是 FAILED 时返回 retried=false。 */
export async function retrySubscriptionCycle(
  cycleId: string,
  reason?: string,
): Promise<RetryResult> {
  return (await platformHttp.post<RetryResult>(
    `/console/platform/admin/subscriptions/cycles/${cycleId}/retry`,
    reason ? { reason } : {},
  )).data
}
