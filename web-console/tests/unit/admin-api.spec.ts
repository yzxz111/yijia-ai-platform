import { beforeEach, describe, expect, it, vi } from 'vitest'

const http = vi.hoisted(() => ({
  gatewayGet: vi.fn(),
  gatewayPost: vi.fn(),
  gatewayPatch: vi.fn(),
  gatewayPut: vi.fn(),
  gatewayDelete: vi.fn(),
  platformGet: vi.fn(),
  platformPost: vi.fn(),
  platformPatch: vi.fn(),
}))

vi.mock('../../src/api/http', () => ({
  gatewayHttp: {
    get: http.gatewayGet,
    post: http.gatewayPost,
    patch: http.gatewayPatch,
    put: http.gatewayPut,
    delete: http.gatewayDelete,
  },
  platformHttp: { get: http.platformGet, post: http.platformPost, patch: http.platformPatch },
}))

import {
  approveRefund,
  createAdminSkill,
  createPackagePlan,
  createModel,
  deleteAdminSkillIcon,
  deleteAdminSkill,
  deleteSkillVersion,
  fetchAdminSkillIcon,
  getAdminSkill,
  getGatewayAdminAnalytics,
  getAdminRefund,
  getGatewayHealth,
  getOperatingAnalytics,
  getOutboxHealth,
  getReconciliationRun,
  getRoutingHealth,
  listAdminOrders,
  listAdminPlans,
  listAdminSkills,
  listPackagePlanCatalog,
  listAdminUsage,
  listAdminUsers,
  listAudit,
  listChannels,
  listGrants,
  listModels,
  listOutbox,
  listPaymentConfigs,
  listPaymentAnomalies,
  listPayments,
  listProviderPrices,
  listProviders,
  listQuotaLedger,
  listRefunds,
  listRefundPolicies,
  listReservations,
  listSkillDownloads,
  publishAdminSkill,
  publishSkillVersion,
  recoverReservations,
  rejectRefund,
  replaceAdminSkillMembers,
  replaceSkillPackage,
  replaceSkillPackageWithRetry,
  retryOutbox,
  simulatePayment,
  startReconciliation,
  publishRefundPolicy,
  saveModelConfiguration,
  skillDetailPlaceholder,
  unpublishAdminSkill,
  updateAdminSkill,
  updatePaymentAnomaly,
  updateModel,
  uploadAdminSkillIcon,
  uploadSkillVersion,
  type AdminListParams,
  type PaymentConfig,
  type SkillVersionView,
} from '../../src/api/admin'
import { getUsageAnalytics } from '../../src/api/gateway'
import { getSkill, listSkills, fetchSkillMemberAvatar, type SkillList, type SkillDetail } from '../../src/api/skills'
import * as adminApi from '../../src/api/admin'
import { fetchSkillMemberAvatar, getSkill, listSkills, type SkillDetail, type SkillList } from '../../src/api/skills'

describe('admin API idempotency', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    http.gatewayGet.mockResolvedValue({ data: { items: [], total: 0, page: 0, size: 20 } })
    http.platformGet.mockResolvedValue({ data: { items: [], total: 0, page: 0, size: 20 } })
    http.gatewayPost.mockResolvedValue({ data: { operation: 'recover-reservations', status: 'COMPLETED', affected: 1 } })
    http.gatewayPatch.mockResolvedValue({ data: { id: 'model-1', code: 'text-basic' } })
    http.platformPost.mockResolvedValue({ data: { id: 'outbox-1', status: 'PENDING' } })
  })

  it('uses the visible operation business number for both gateway headers', async () => {
    await recoverReservations('operation-visible-1')

    expect(http.gatewayPost).toHaveBeenCalledWith(
      '/console/gateway/admin/operations/recover-reservations',
      null,
      { headers: { 'X-Request-Id': 'operation-visible-1', 'Idempotency-Key': 'operation-visible-1' } },
    )
  })

  it('uses the visible outbox business number in the retry request', async () => {
    await retryOutbox('outbox-1', 'outbox-visible-1')

    expect(http.platformPost).toHaveBeenCalledWith(
      '/console/platform/admin/outbox/outbox-1/retry',
      { businessNo: 'outbox-visible-1' },
    )
  })

  it('simulates payment by order number without client payment facts', async () => {
    await simulatePayment('ORD/MOCK 001')

    expect(http.platformPost).toHaveBeenCalledWith(
      '/console/platform/admin/orders/ORD%2FMOCK%20001/simulate-payment',
      null,
    )
  })

  it('sends the unified page, filter, and sort contract for every platform operational list', async () => {
    const params: AdminListParams = {
      page: 2, size: 10, query: 'needle', status: 'ACTIVE', sort: 'createdAt', direction: 'asc',
    }

    await listAdminUsers(params)
    await listGrants(params)
    await listAdminPlans(params)
    await listAdminOrders(params)
    await listPayments(params)
    await listOutbox(params)

    for (const endpoint of [
      '/console/platform/admin/users',
      '/console/platform/admin/grants',
      '/console/platform/admin/plans',
      '/console/platform/admin/orders',
      '/console/platform/admin/payments',
      '/console/platform/admin/outbox',
    ]) {
      expect(http.platformGet).toHaveBeenCalledWith(endpoint, { params })
    }
  })

  it('preserves gateway endpoint filters beside the unified list contract', async () => {
    const common: AdminListParams = {
      page: 1, size: 25, query: 'ops', status: 'FAILED', sort: 'createdAt', direction: 'desc',
    }

    await listModels(common)
    await listChannels(common)
    await listAdminUsage({ ...common, userId: 'user-1', modelCode: 'qwen-plus', outcome: 'FAILED', requestId: 'req-1' })
    await listReservations({ ...common, userId: 'user-1', requestId: 'req-1', anomalyOnly: true })
    await listQuotaLedger({ ...common, userId: 'user-1', modelCode: 'qwen-plus', entryType: 'SETTLE' })
    await listProviderPrices({ ...common, providerCode: 'bailian', modelCode: 'qwen-plus' })

    expect(http.gatewayGet).toHaveBeenCalledWith('/console/gateway/admin/models', { params: common })
    expect(http.gatewayGet).toHaveBeenCalledWith('/console/gateway/admin/channels', { params: common })
    expect(http.gatewayGet).toHaveBeenCalledWith('/console/gateway/admin/usage', {
      params: { ...common, userId: 'user-1', modelCode: 'qwen-plus', outcome: 'FAILED', requestId: 'req-1' },
    })
    expect(http.gatewayGet).toHaveBeenCalledWith('/console/gateway/admin/reservations', {
      params: { ...common, userId: 'user-1', requestId: 'req-1', anomalyOnly: true },
    })
    expect(http.gatewayGet).toHaveBeenCalledWith('/console/gateway/admin/quota-ledger', {
      params: { ...common, userId: 'user-1', modelCode: 'qwen-plus', entryType: 'SETTLE' },
    })
    expect(http.gatewayGet).toHaveBeenCalledWith('/console/gateway/admin/provider-prices', {
      params: { ...common, providerCode: 'bailian', modelCode: 'qwen-plus' },
    })
  })

  it('uses the exact administrator SMS registry, delivery filters, and retry routes', async () => {
    const sms = adminApi as unknown as {
      listSmsTemplates: () => Promise<unknown>
      listSmsDeliveries: (params: Record<string, unknown>) => Promise<unknown>
      retrySmsDelivery: (id: string, businessNo: string) => Promise<unknown>
    }
    expect(sms.listSmsTemplates).toBeTypeOf('function')
    expect(sms.listSmsDeliveries).toBeTypeOf('function')
    expect(sms.retrySmsDelivery).toBeTypeOf('function')

    const params = {
      page: 3,
      size: 25,
      query: '8963',
      status: 'DEAD',
      templateKey: 'ORDER_PAYMENT_FAILED',
      sort: 'attemptCount',
      direction: 'asc',
    }
    await sms.listSmsTemplates()
    await sms.listSmsDeliveries(params)
    await sms.retrySmsDelivery('delivery/1', 'sms-retry-visible-1')

    expect(http.platformGet).toHaveBeenCalledWith('/console/platform/admin/sms/templates')
    expect(http.platformGet).toHaveBeenCalledWith('/console/platform/admin/sms/deliveries', { params })
    expect(http.platformPost).toHaveBeenCalledWith(
      '/console/platform/admin/sms/deliveries/delivery%2F1/retry',
      { businessNo: 'sms-retry-visible-1' },
    )
  })

  it('omits empty usage filters while retaining paging, sorting, and booleans', async () => {
    const common: AdminListParams = {
      page: 0, size: 20, query: '', status: '', sort: 'createdAt', direction: 'desc',
    }

    await listAdminUsage({
      ...common, userId: '', modelCode: '', outcome: '', requestId: '',
    })
    await listReservations({ ...common, userId: '', requestId: '', anomalyOnly: false })
    await listQuotaLedger({ ...common, userId: '', modelCode: '', entryType: '' })

    const required = { page: 0, size: 20, sort: 'createdAt', direction: 'desc' }
    expect(http.gatewayGet).toHaveBeenCalledWith('/console/gateway/admin/usage', {
      params: required,
    })
    expect(http.gatewayGet).toHaveBeenCalledWith('/console/gateway/admin/reservations', {
      params: { ...required, anomalyOnly: false },
    })
    expect(http.gatewayGet).toHaveBeenCalledWith('/console/gateway/admin/quota-ledger', {
      params: required,
    })
  })

  it('preserves exact audit filters beside the unified list contract', async () => {
    const params = {
      page: 3, size: 50, query: '', status: '', sort: 'action', direction: 'asc' as const,
      actorUserId: 'admin-1', action: 'PACKAGE_GRANT_CREATED', resourceType: 'GRANT',
      resourceId: 'grant-1', outcome: 'SUCCESS', from: '2026-07-01T00:00:00Z', to: '2026-08-01T00:00:00Z',
    }

    await listAudit(params)

    expect(http.platformGet).toHaveBeenCalledWith('/console/platform/admin/audit', { params })
  })

  it('keeps bounded catalogs and health snapshots explicitly unpaged', async () => {
    await listProviders()
    await listPaymentConfigs()
    await getRoutingHealth()
    await getGatewayHealth()
    await getOutboxHealth()

    expect(http.gatewayGet).toHaveBeenCalledWith('/console/gateway/admin/providers')
    expect(http.platformGet).toHaveBeenCalledWith('/console/platform/admin/payment-configs')
    expect(http.gatewayGet).toHaveBeenCalledWith('/console/gateway/admin/routing/health')
    expect(http.gatewayGet).toHaveBeenCalledWith('/console/gateway/admin/operations/health')
    expect(http.platformGet).toHaveBeenCalledWith('/console/platform/admin/outbox/health')
  })

  it('encodes refund paging filters and sorting through URLSearchParams', async () => {
    await listRefunds({
      page: 2, size: 25, query: 'REF/001', status: 'PREPARING', sort: 'refundNo', direction: 'asc',
    })

    const refundCall = http.platformGet.mock.calls.find(([url]) => url === '/console/platform/admin/refunds')
    expect(refundCall?.[1].params).toBeInstanceOf(URLSearchParams)
    expect(refundCall?.[1].params.toString()).toBe(
      'page=2&size=25&query=REF%2F001&status=PREPARING&sort=refundNo&direction=asc',
    )
  })

  it('uses exact refund review and policy routes without browser-calculated values', async () => {
    await getAdminRefund('REF/001')
    await approveRefund('REF/001')
    await rejectRefund('REF/001', '不符合退款政策')
    await listRefundPolicies(0, 20)
    await publishRefundPolicy({
      version: 2,
      feeBps: 500,
      minimumFee: 100,
      refundWindowDays: 7,
      maximumUsageBps: 1000,
      effectiveAt: '2026-08-03T00:00:00Z',
    })

    expect(http.platformGet).toHaveBeenCalledWith('/console/platform/admin/refunds/REF%2F001')
    expect(http.platformPost).toHaveBeenCalledWith('/console/platform/admin/refunds/REF%2F001/approve')
    expect(http.platformPost).toHaveBeenCalledWith(
      '/console/platform/admin/refunds/REF%2F001/reject',
      { reviewNote: '不符合退款政策' },
    )
    expect(http.platformGet).toHaveBeenCalledWith(
      '/console/platform/admin/refund-policies',
      { params: { page: 0, size: 20 } },
    )
    expect(http.platformPost).toHaveBeenCalledWith('/console/platform/admin/refund-policies', {
      version: 2,
      feeBps: 500,
      minimumFee: 100,
      refundWindowDays: 7,
      maximumUsageBps: 1000,
      effectiveAt: '2026-08-03T00:00:00Z',
    })
  })

  it('encodes anomaly filters and sends only the allowlisted optimistic update fields', async () => {
    await listPaymentAnomalies({
      page: 1,
      size: 20,
      anomalyType: 'PAYMENT_AMOUNT_MISMATCH',
      status: 'OPEN',
      provider: 'ALIPAY',
      from: '2026-08-01T00:00:00Z',
      to: '2026-08-02T00:00:00Z',
      orderId: 'order-1',
      sort: 'createdAt',
      direction: 'desc',
    })
    await updatePaymentAnomaly('anomaly/1', {
      status: 'INVESTIGATING',
      assignee: 'admin-1',
      resolutionNote: null,
    }, 3)

    const anomalyCall = http.platformGet.mock.calls.find(([url]) => url === '/console/platform/admin/payment-anomalies')
    expect(anomalyCall?.[1].params).toBeInstanceOf(URLSearchParams)
    expect(anomalyCall?.[1].params.toString()).toBe(
      'page=1&size=20&anomalyType=PAYMENT_AMOUNT_MISMATCH&status=OPEN&provider=ALIPAY&from=2026-08-01T00%3A00%3A00Z&to=2026-08-02T00%3A00%3A00Z&orderId=order-1&sort=createdAt&direction=desc',
    )
    expect(http.platformPatch).toHaveBeenCalledWith(
      '/console/platform/admin/payment-anomalies/anomaly%2F1',
      { status: 'INVESTIGATING', assignee: 'admin-1', resolutionNote: null },
      { headers: { 'If-Match': '"3"' } },
    )
  })

  it('starts and pages reconciliation without a browser-supplied bill or result', async () => {
    await startReconciliation('WECHAT_PAY', '2026-08-01')
    await getReconciliationRun('run/1', 2, 25, 'createdAt', 'asc')

    expect(http.platformPost).toHaveBeenCalledWith('/console/platform/admin/reconciliation-runs', {
      provider: 'WECHAT_PAY', billDate: '2026-08-01',
    })
    const detailCall = http.platformGet.mock.calls.find(([url]) => url === '/console/platform/admin/reconciliation-runs/run%2F1')
    expect(detailCall?.[1].params).toBeInstanceOf(URLSearchParams)
    expect(detailCall?.[1].params.toString()).toBe('page=2&size=25&sort=createdAt&direction=asc')
  })

  it('models only sanitized payment readiness metadata', () => {
    const config: PaymentConfig = {
      provider: 'ALIPAY', environment: 'SANDBOX', enabled: false, configured: true,
      credentialFingerprint: 'sha256:abc', lastValidationStatus: 'VALID',
      lastValidatedAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
    }

    expect(config).not.toHaveProperty('credential')
    expect(config).not.toHaveProperty('privateKey')
    expect(config).not.toHaveProperty('secret')
  })

  it('never sends the read-only routing strategy in model mutations', async () => {
    const common = {
      displayName: 'Text Basic', aliases: ['text-basic'], capabilities: ['TEXT'],
      contextWindow: 32768, maxOutputTokens: 4096, premium: false, enabled: true,
      requestsPerMinute: 60, maxConcurrency: 5, routingStrategy: 'COMPOSITE',
    }

    await createModel({ code: 'text-basic', ...common } as never)
    await updateModel({ id: 'model-1', ...common } as never)

    expect(http.gatewayPost).toHaveBeenCalledWith(
      '/console/gateway/admin/models',
      expect.not.objectContaining({ routingStrategy: expect.anything() }),
    )
    expect(http.gatewayPatch).toHaveBeenCalledWith(
      '/console/gateway/admin/models',
      expect.not.objectContaining({ routingStrategy: expect.anything() }),
    )
  })

  it('uses the package-paged catalog and creates generated-code package identities', async () => {
    const params = { page: 1, size: 10, query: '开发者', status: 'ACTIVE' as const }

    await listPackagePlanCatalog(params)
    await createPackagePlan({ name: '开发者版', description: '标准套餐', kind: 'STANDARD' })

    expect(http.platformGet).toHaveBeenCalledWith('/console/platform/admin/package-plans', { params })
    expect(http.platformPost).toHaveBeenCalledWith('/console/platform/admin/package-plans', {
      name: '开发者版', description: '标准套餐', kind: 'STANDARD',
    })
  })

  it('saves model and upstream route through one endpoint', async () => {
    const request = {
      code: 'text-basic', displayName: 'Text Basic', aliases: ['text-basic'], capabilities: ['TEXT'],
      contextWindow: 32768, maxOutputTokens: 4096, premium: false, enabled: true,
      requestsPerMinute: 60, maxConcurrency: 5,
      route: {
        providerId: 'provider-1', environment: 'PRODUCTION', baseUrl: 'https://example.test/v1',
        apiKey: 'secret', upstreamModel: 'upstream-text', enabled: true, priority: 100,
        weight: 100, timeoutMs: 60_000, requestsPerMinute: 300, maxConcurrency: 50,
      },
    }

    await saveModelConfiguration(request)

    expect(http.gatewayPost).toHaveBeenCalledWith(
      '/console/gateway/admin/models/configuration',
      request,
    )
  })

  it('requests bounded dashboard analytics from their owning services', async () => {
    http.gatewayGet.mockResolvedValue({ data: { range: { days: 7 }, daily: [], models: [] } })
    http.platformGet.mockResolvedValue({ data: { timezone: 'Asia/Shanghai', months: 6, monthly: [] } })

    await getUsageAnalytics(7)
    await getGatewayAdminAnalytics(30)
    await getOperatingAnalytics(6)

    expect(http.gatewayGet).toHaveBeenCalledWith(
      '/console/gateway/usage/analytics',
      { params: { days: 7 } },
    )
    expect(http.gatewayGet).toHaveBeenCalledWith(
      '/console/gateway/admin/analytics',
      { params: { days: 30 } },
    )
    expect(http.platformGet).toHaveBeenCalledWith(
      '/console/platform/admin/analytics',
      { params: { months: 6 } },
    )
  })
})

describe('skill management admin API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    http.gatewayGet.mockResolvedValue({ data: { items: [], total: 0, page: 0, size: 20 } })
    http.gatewayPost.mockResolvedValue({ data: { id: 'version-2', version: '1.3.0', revision: 1 } })
    http.gatewayPut.mockResolvedValue({ data: { id: 'skill-1', code: 'data-analyst' } })
    http.gatewayDelete.mockResolvedValue({ data: null })
  })

  it('keeps list filters and limits download history parameters to page and size', async () => {
    const listParams = {
      page: 0,
      size: 20,
      query: '数据',
      status: 'PUBLISHED' as const,
      category: '数据分析',
      kind: 'EXPERT,TEAM' as const,
      sort: 'updated_at',
      direction: 'desc' as const,
    }

    await listAdminSkills(listParams)
    await listSkillDownloads('skill-1', { page: 0, size: 10 })

    expect(http.gatewayGet).toHaveBeenCalledWith('/console/gateway/admin/skills', { params: listParams })
    expect(http.gatewayGet).toHaveBeenCalledWith(
      '/console/gateway/admin/skills/skill-1/downloads',
      { params: { page: 0, size: 10 } },
    )
    const listCall = http.gatewayGet.mock.calls.find(([url]) => url === '/console/gateway/admin/skills')
    expect(listCall?.[1].params).toHaveProperty('page', 0)
    expect(listCall?.[1].params).toHaveProperty('kind', 'EXPERT,TEAM')
  })

  it('drops blank list filters without dropping zero-valued pagination', async () => {
    await listAdminSkills({
      page: 0,
      size: 10,
      query: '',
      status: '',
      category: '',
      kind: '',
      sort: '',
      direction: 'desc',
    })

    expect(http.gatewayGet).toHaveBeenCalledWith('/console/gateway/admin/skills', {
      params: { page: 0, size: 10, direction: 'desc' },
    })
  })

  it('returns full Skill details for roster changes and expert icon mutations', async () => {
    const members = [{
      memberKey: 'atlas',
      name: '安拓',
      nameEn: 'Atlas',
      profession: '分发策略师',
      professionEn: 'Distribution Strategist',
      role: 'LEAD' as const,
      sortOrder: 0,
    }]
    const detail = {
      id: 'skill-1', code: 'data-analyst',
      members: [{ ...members[0], id: 'member-1', avatarUrl: '/v1/skills/atlas/icon' }],
    }
    http.gatewayPut.mockResolvedValueOnce({ data: detail })
    expect(await replaceAdminSkillMembers('skill/1', members)).toBe(detail)

    const avatarDetail = { ...detail, members: [{ ...members[0], id: 'member-1', avatarUrl: '/avatar' }] }
    const file = new File(['png'], 'atlas.png', { type: 'image/png' })

    http.gatewayPost.mockResolvedValueOnce({ data: avatarDetail })
    expect(await uploadAdminSkillIcon('atlas/lead', file)).toBe(avatarDetail)

    http.gatewayDelete.mockResolvedValueOnce({ data: detail })
    expect(await deleteAdminSkillIcon('atlas/lead')).toBe(detail)
    expect(http.gatewayPut).toHaveBeenCalledWith(
      '/console/gateway/admin/skills/skill%2F1/members',
      { members },
    )
    expect(http.gatewayPut).toHaveBeenCalledTimes(1)
  })

  it('reads expert icons as authenticated blobs', async () => {
    const expected = new Blob(['png'], { type: 'image/png' })
    http.gatewayGet.mockResolvedValueOnce({ data: expected })

    const result = await fetchAdminSkillIcon('atlas/lead')

    expect(http.gatewayGet).toHaveBeenCalledWith(
      '/console/gateway/admin/skills/atlas%2Flead/icon',
      { responseType: 'blob' },
    )
    expect(result).toBe(expected)
  })

  it('uses separate create and update payloads and exact encoded CRUD routes', async () => {
    const payload = {
      name: '数据分析助手',
      category: '数据分析',
      tags: ['报表'],
      summary: '把原始数据整理成结论',
      description: null,
      icon: '📊',
      minClientVersion: '2026.6.0',
      sortWeight: 0,
      kind: 'EXPERT' as const,
      nameEn: 'Data Analyst',
      summaryEn: null,
      descriptionEn: null,
    }
    const createPayload = { ...payload, code: 'data-analyst' }
    const updatePayload = { ...payload, rowVersion: 3 }

    await getAdminSkill('skill/1')
    await createAdminSkill(createPayload)
    await updateAdminSkill('skill/1', updatePayload)
    await deleteAdminSkill('skill/1')
    await publishAdminSkill('skill/1')
    await unpublishAdminSkill('skill/1')
    await publishSkillVersion('skill/1', 'version/2')
    await deleteSkillVersion('skill/1', 'version/2')

    expect(http.gatewayGet).toHaveBeenCalledWith('/console/gateway/admin/skills/skill%2F1')
    expect(http.gatewayPost).toHaveBeenCalledWith('/console/gateway/admin/skills', createPayload)
    expect(http.gatewayPut).toHaveBeenCalledWith('/console/gateway/admin/skills/skill%2F1', updatePayload)
    expect(updatePayload).not.toHaveProperty('code')
    expect(http.gatewayDelete).toHaveBeenCalledWith('/console/gateway/admin/skills/skill%2F1')
    expect(http.gatewayPost).toHaveBeenCalledWith('/console/gateway/admin/skills/skill%2F1/publish')
    expect(http.gatewayPost).toHaveBeenCalledWith('/console/gateway/admin/skills/skill%2F1/unpublish')
    expect(http.gatewayPost).toHaveBeenCalledWith(
      '/console/gateway/admin/skills/skill%2F1/versions/version%2F2/publish',
    )
    expect(http.gatewayDelete).toHaveBeenCalledWith(
      '/console/gateway/admin/skills/skill%2F1/versions/version%2F2',
    )
  })

  it('reports upload progress and omits version from replacement multipart bodies', async () => {
    const uploadBody = new FormData()
    const uploadFile = new File(['zip-bytes'], 'skill-package.zip', { type: 'application/zip' })
    uploadBody.append('file', uploadFile)
    uploadBody.append('version', '1.3.0')
    uploadBody.append('changelog', '修复若干问题')
    const uploadProgress: Array<{ phase: string; percent: number }> = []
    http.gatewayPost.mockImplementationOnce(async (_url, _payload, config: {
      onUploadProgress?: (event: { loaded: number; total: number }) => void
    }) => {
      config.onUploadProgress?.({ loaded: 512, total: 1024 })
      return { data: { id: 'version-2', version: '1.3.0', revision: 1 } }
    })

    await uploadSkillVersion('skill-1', uploadBody, (value) => uploadProgress.push(value))

    expect([...uploadBody.keys()]).toEqual(['file', 'version', 'changelog'])
    expect(uploadProgress).toEqual([{ phase: 'uploading', percent: 50 }])

    const replacementFile = new File(['replacement'], 'replacement.zip', { type: 'application/zip' })
    const replacementProgress: Array<{ phase: string; percent: number }> = []
    http.gatewayPut.mockImplementationOnce(async (_url, _payload, config: {
      onUploadProgress?: (event: { loaded: number; total: number }) => void
    }) => {
      config.onUploadProgress?.({ loaded: 1, total: 1 })
      return { data: { id: 'version-1', version: '1.2.0', revision: 2 } }
    })

    await replaceSkillPackageWithRetry(
      'skill-1',
      'version-1',
      replacementFile,
      { changelog: '仅替换包体', minClientVersion: '' },
      { onProgress: (value) => replacementProgress.push(value) },
    )

    const replacementCall = http.gatewayPut.mock.calls.at(-1)
    const replacementBody = replacementCall?.[1] as FormData
    expect(replacementCall?.[0]).toBe('/console/gateway/admin/skills/skill-1/versions/version-1/package')
    expect([...replacementBody.keys()]).toEqual(['file', 'changelog'])
    expect(replacementBody.has('version')).toBe(false)
    expect(replacementProgress).toEqual([{ phase: 'uploading', percent: 100 }])
  })

  it('models nullable package sizes without inventing server values', () => {
    const placeholder = skillDetailPlaceholder({
      id: 'skill-1',
      code: 'data-analyst',
      name: '数据分析助手',
      nameEn: null,
      category: '数据分析',
      tags: ['报表'],
      summary: null,
      summaryEn: null,
      descriptionEn: null,
      icon: null,
      iconUrl: null,
      status: 'DRAFT',
      publishedVersion: null,
      latestVersion: null,
      minClientVersion: null,
      sizeBytes: null,
      downloadCount: 0,
      createdAt: '2026-09-14T02:10:00Z',
      updatedAt: '2026-09-14T02:10:00Z',
      rowVersion: 1,
      kind: 'SKILL',
      memberCount: 0,
    })
    const version: SkillVersionView = {
      id: 'version-1',
      version: '1.2.0',
      revision: 1,
      status: 'DRAFT',
      sizeBytes: null,
      md5: null,
      sha256: null,
      fileCount: 0,
      scanStatus: 'PENDING',
      changelog: null,
      downloadCount: 0,
      createdAt: '2026-09-14T02:12:00Z',
      online: false,
    }

    expect(placeholder.sizeBytes).toBeNull()
    expect(version.sizeBytes).toBeNull()
    expect(placeholder.versions).toEqual([])
    expect(placeholder.members).toEqual([])
  })
})

describe('skill catalog client API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('preserves additive catalog metadata and cursor fields', async () => {
    const summary = {
      name: 'expert-team',
      title: '专家团',
      description: '协作解决复杂任务',
      summary: '多专家协作',
      version: '1.2.0',
      category: 'analysis',
      tags: ['team'],
      icon: null,
      highlights: [],
      status: 'PUBLISHED',
      latestVersion: '1.2.0',
      publishedVersion: '1.2.0',
      minClientVersion: '2026.6.0',
      sizeBytes: null,
      md5: null,
      sha256: null,
      downloadUrl: '/v1/skills/expert-team/downloads',
      iconUrl: null,
      updatedAt: '2026-09-14T02:10:00Z',
      fileCount: 0,
      totalBytes: 0,
      kind: 'TEAM' as const,
      titleEn: 'Expert Team',
      summaryEn: null,
      descriptionEn: null,
      memberCount: 2,
      files: [],
    }
    const catalog: SkillList = { object: 'list', data: [summary], hasMore: true, next: 'expert-team' }
    http.gatewayGet.mockResolvedValueOnce({ data: catalog })

    expect(await listSkills()).toBe(catalog)
    expect(http.gatewayGet).toHaveBeenCalledWith('/console/gateway/skills')
    expect(catalog.data[0]?.sizeBytes).toBeNull()
    expect(catalog.hasMore).toBe(true)
  })

  it('reads details and member avatars through the authenticated console mirror', async () => {
    const detail: SkillDetail = {
      object: 'skill',
      name: 'expert-team',
      title: '专家团',
      description: null,
      version: '1.2.0',
      category: null,
      tags: [],
      icon: null,
      highlights: [],
      updatedAt: '2026-09-14T02:10:00Z',
      fileCount: 0,
      totalBytes: 0,
      files: [],
      kind: 'TEAM',
      members: [{
        id: 'member-1',
        memberKey: 'atlas/lead',
        name: '安拓',
        nameEn: 'Atlas',
        profession: null,
        professionEn: null,
        role: 'LEAD',
        sortOrder: 0,
        avatarUrl: '/v1/skills/expert-team/members/atlas%2Flead/avatar',
      }],
    }
    http.gatewayGet.mockResolvedValueOnce({ data: detail })
    expect(await getSkill('expert/team')).toBe(detail)
    expect(http.gatewayGet).toHaveBeenCalledWith('/console/gateway/skills/expert%2Fteam')

    const avatar = new Blob(['png'], { type: 'image/png' })
    http.gatewayGet.mockResolvedValueOnce({ data: avatar })
    expect(await fetchSkillMemberAvatar('atlas/lead')).toBe(avatar)
    expect(http.gatewayGet).toHaveBeenCalledWith(
      '/console/gateway/skills/atlas%2Flead/icon',
      { responseType: 'blob' },
    )
  })
})
