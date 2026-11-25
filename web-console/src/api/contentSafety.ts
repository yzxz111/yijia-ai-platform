import { platformHttp } from './http'

function adminOperationHeaders(action: string) {
  const operationId = globalThis.crypto?.randomUUID?.()
    ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return { 'Idempotency-Key': `${action}:${operationId}` }
}

export interface SafetyCase {
  caseNo: string
  tenantId: string
  userId: string
  requestId: string
  direction: string
  category: string
  severity: string
  action: string
  status: string
  policyVersion: number
  redactedExcerpt?: string
  createdAt: string
  reviewedAt?: string
  reviewNote?: string
  resolutionAction?: string
  enforcementState?: string
  enforcementReasonCode?: string
}

export interface SafetyAppeal {
  businessNo: string
  caseId: string
  userId: string
  status: string
  reason: string
  processingNote?: string
}

export interface UserSafetyCase {
  caseNo: string
  category: string
  severity: string
  action: string
  status: string
  redactedExcerpt?: string
  createdAt: string
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface SafetyPageParams {
  page: number
  size: number
  query?: string
  status?: string
}

export const contentSafetyApi = {
  cases(params: SafetyPageParams) {
    return platformHttp.get<PageResult<SafetyCase>>('/console/platform/admin/content-safety/cases', { params })
  },
  transition(caseNo: string, status: string, note = '') {
    return platformHttp.post(
      `/console/platform/admin/content-safety/cases/${caseNo}/transition`,
      { status, note },
      { headers: adminOperationHeaders('case-transition') },
    )
  },
  resolve(caseNo: string, action: string, note = '') {
    return platformHttp.post(
      `/console/platform/admin/content-safety/cases/${caseNo}/resolve`,
      { action, note },
      { headers: adminOperationHeaders('case-resolve') },
    )
  },
  enforce(payload: { tenantId: string; userId: string; state: string; reason: string; caseNo?: string }) {
    return platformHttp.post('/console/platform/admin/content-safety/enforcements', payload, {
      headers: adminOperationHeaders('enforcement-apply'),
    })
  },
  clear(payload: { tenantId: string; userId: string; reason?: string }) {
    return platformHttp.post('/console/platform/admin/content-safety/enforcements/clear', payload, {
      headers: adminOperationHeaders('enforcement-clear'),
    })
  },
  adminAppeals(params: SafetyPageParams) {
    return platformHttp.get<PageResult<SafetyAppeal>>('/console/platform/admin/content-safety/appeals', { params })
  },
  processAppeal(businessNo: string, status: string, note = '') {
    return platformHttp.post(
      `/console/platform/admin/content-safety/appeals/${businessNo}/process`,
      { status, note },
      { headers: adminOperationHeaders('appeal-process') },
    )
  },
  withdrawAdminAppeal(businessNo: string) {
    return platformHttp.post<SafetyAppeal>(
      `/console/platform/admin/content-safety/appeals/${businessNo}/withdraw`,
      undefined,
      { headers: adminOperationHeaders('appeal-withdraw') },
    )
  },
  appeals(params: Pick<SafetyPageParams, 'page' | 'size'>) {
    return platformHttp.get<PageResult<SafetyAppeal>>('/console/platform/safety/appeals', { params })
  },
  userCases(params: Pick<SafetyPageParams, 'page' | 'size'>) {
    return platformHttp.get<PageResult<UserSafetyCase>>('/console/platform/safety/appeals/cases', { params })
  },
  createAppeal(caseNo: string, reason: string) {
    return platformHttp.post<SafetyAppeal>('/console/platform/safety/appeals', { caseNo, reason })
  },
  withdrawAppeal(businessNo: string) {
    return platformHttp.post<SafetyAppeal>(`/console/platform/safety/appeals/${businessNo}/withdraw`)
  },
}
