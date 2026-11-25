import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore, type AuthenticatedUser } from './auth'
import { useUsageStore } from './usage'

const api = vi.hoisted(() => ({
  getUsageAnalytics: vi.fn(),
  listUsage: vi.fn(),
  quotaSummary: vi.fn(),
  usageMetrics: vi.fn(),
}))

vi.mock('../api/gateway', () => api)

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

const quota = (packageCode: string) => ({
  activePackage: { packageCode },
  queuedPackages: [],
})

const usagePage = (requestId: string) => ({
  items: [{ id: requestId, requestId }],
  total: 1,
  page: 0,
  size: 20,
})

const metrics = (platformAddedP95Ms: number) => ({ sampleCount: 1, platformAddedP95Ms })

function analytics(periodTokens: number) {
  return {
    range: { days: 30 as const, timezone: 'Asia/Shanghai', from: '2026-07-04', to: '2026-08-02' },
    summary: {
      todayRequests: 1,
      todaySuccessfulRequests: 1,
      todayFailedRequests: 0,
      todayTokens: periodTokens,
      periodRequests: 1,
      periodTokens,
      platformAddedP95Ms: periodTokens,
    },
    daily: [],
    models: [],
  }
}

describe('usage analytics store', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('keeps the selected range and exact server analytics together', async () => {
    const response = {
      range: { days: 7, timezone: 'Asia/Shanghai', from: '2026-07-27', to: '2026-08-02' },
      summary: {
        todayRequests: 2,
        todaySuccessfulRequests: 1,
        todayFailedRequests: 1,
        todayTokens: 30,
        periodRequests: 3,
        periodTokens: 45,
        platformAddedP95Ms: 12,
      },
      daily: [],
      models: [],
    }
    api.getUsageAnalytics.mockResolvedValue(response)
    const store = useUsageStore()

    await store.loadAnalytics(7)

    expect(api.getUsageAnalytics).toHaveBeenCalledWith(7)
    expect(store.analyticsDays).toBe(7)
    expect(store.analytics).toEqual(response)
    expect(store.loadingAnalytics).toBe(false)
  })

  it('always clears the analytics loading state after a failed request', async () => {
    api.getUsageAnalytics.mockRejectedValue(new Error('unavailable'))
    const store = useUsageStore()

    await expect(store.loadAnalytics(30)).rejects.toThrow('unavailable')

    expect(store.analyticsDays).toBe(30)
    expect(store.loadingAnalytics).toBe(false)
  })

  it('clears a previous analytics snapshot when the same range refresh fails', async () => {
    const response = {
      range: { days: 30, timezone: 'Asia/Shanghai', from: '2026-07-04', to: '2026-08-02' },
      summary: {
        todayRequests: 2,
        todaySuccessfulRequests: 2,
        todayFailedRequests: 0,
        todayTokens: 30,
        periodRequests: 3,
        periodTokens: 45,
        platformAddedP95Ms: 12,
      },
      daily: [],
      models: [],
    }
    api.getUsageAnalytics
      .mockResolvedValueOnce(response)
      .mockRejectedValueOnce(new Error('refresh unavailable'))
    const store = useUsageStore()

    await store.loadAnalytics(30)
    await expect(store.loadAnalytics(30)).rejects.toThrow('refresh unavailable')

    expect(store.analytics).toBeNull()
    expect(store.loadingAnalytics).toBe(false)
  })

  it('resets user-scoped data on logout and identity change but not same-user refresh', () => {
    const auth = useAuthStore()
    const store = useUsageStore()
    const accountA = user('user-a', 'tenant-a')

    auth.setSession('token-a', accountA)
    store.$patch({
      quota: quota('ACCOUNT_A'),
      usage: usagePage('req_account_a'),
      metrics: metrics(101),
      analytics: analytics(101),
      loadingQuota: true,
      loadingUsage: true,
      loadingAnalytics: true,
    })

    auth.setSession('token-a-refreshed', { ...accountA, email: 'new-address@example.com' })
    expect(store.quota?.activePackage?.packageCode).toBe('ACCOUNT_A')
    expect(store.usage.items[0]?.requestId).toBe('req_account_a')

    auth.setSession('token-b', user('user-b', 'tenant-b'))
    expect(store.quota).toBeNull()
    expect(store.usage.items).toEqual([])
    expect(store.metrics).toBeNull()
    expect(store.analytics).toBeNull()
    expect(store.loadingQuota).toBe(false)
    expect(store.loadingUsage).toBe(false)
    expect(store.loadingAnalytics).toBe(false)

    store.$patch({ quota: quota('ACCOUNT_B'), usage: usagePage('req_account_b') })
    auth.clearSession()
    expect(store.quota).toBeNull()
    expect(store.usage.items).toEqual([])
  })

  it('invalidates every deferred source when auth is cleared', async () => {
    const quotaRequest = deferred<ReturnType<typeof quota>>()
    const usageRequest = deferred<ReturnType<typeof usagePage>>()
    const metricsRequest = deferred<ReturnType<typeof metrics>>()
    const analyticsRequest = deferred<ReturnType<typeof analytics>>()
    api.quotaSummary.mockReturnValue(quotaRequest.promise)
    api.listUsage.mockReturnValue(usageRequest.promise)
    api.usageMetrics.mockReturnValue(metricsRequest.promise)
    api.getUsageAnalytics.mockReturnValue(analyticsRequest.promise)
    const auth = useAuthStore()
    const store = useUsageStore()
    auth.setSession('token-a', user('user-a', 'tenant-a'))

    const pending = [
      store.loadQuota(),
      store.loadUsage(),
      store.loadMetrics(),
      store.loadAnalytics(30),
    ]
    auth.clearSession()

    expect(store.quota).toBeNull()
    expect(store.usage.items).toEqual([])
    expect(store.metrics).toBeNull()
    expect(store.analytics).toBeNull()
    expect(store.loadingQuota).toBe(false)
    expect(store.loadingUsage).toBe(false)
    expect(store.loadingAnalytics).toBe(false)

    quotaRequest.resolve(quota('LATE_ACCOUNT_A'))
    usageRequest.resolve(usagePage('req_late_account_a'))
    metricsRequest.resolve(metrics(909))
    analyticsRequest.resolve(analytics(909))
    await Promise.all(pending)

    expect(store.quota).toBeNull()
    expect(store.usage.items).toEqual([])
    expect(store.metrics).toBeNull()
    expect(store.analytics).toBeNull()
  })

  it('clears every source snapshot before a refresh that fails', async () => {
    api.quotaSummary.mockResolvedValueOnce(quota('ACCOUNT_A'))
    api.listUsage.mockResolvedValueOnce(usagePage('req_account_a'))
    api.usageMetrics.mockResolvedValueOnce(metrics(101))
    api.getUsageAnalytics.mockResolvedValueOnce(analytics(101))
    const store = useUsageStore()
    await Promise.all([
      store.loadQuota(),
      store.loadUsage(),
      store.loadMetrics(),
      store.loadAnalytics(30),
    ])
    api.quotaSummary.mockRejectedValueOnce(new Error('quota unavailable'))
    api.listUsage.mockRejectedValueOnce(new Error('usage unavailable'))
    api.usageMetrics.mockRejectedValueOnce(new Error('metrics unavailable'))
    api.getUsageAnalytics.mockRejectedValueOnce(new Error('analytics unavailable'))

    const refreshes = [
      store.loadQuota(),
      store.loadUsage(),
      store.loadMetrics(),
      store.loadAnalytics(30),
    ]
    const settledRefreshes = Promise.allSettled(refreshes)

    expect(store.quota).toBeNull()
    expect(store.usage.items).toEqual([])
    expect(store.metrics).toBeNull()
    expect(store.analytics).toBeNull()
    const results = await settledRefreshes
    expect(results.every((result) => result.status === 'rejected')).toBe(true)
    expect(store.quota).toBeNull()
    expect(store.usage.items).toEqual([])
    expect(store.metrics).toBeNull()
    expect(store.analytics).toBeNull()
  })
})
