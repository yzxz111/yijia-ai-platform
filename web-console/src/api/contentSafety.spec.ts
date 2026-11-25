import { beforeEach, describe, expect, it, vi } from 'vitest'

const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('./http', () => ({ platformHttp: http }))

import { contentSafetyApi } from './contentSafety'

describe('content safety administrator API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('adds an operation-scoped idempotency key to every mutation', () => {
    contentSafetyApi.transition('CS-00000000000000000000', 'REVIEWING')
    contentSafetyApi.resolve('CS-00000000000000000000', 'DISMISSED')
    contentSafetyApi.enforce({
      tenantId: 'tenant-1', userId: 'user-1', state: 'FROZEN', reason: 'manual_review',
    })
    contentSafetyApi.clear({ tenantId: 'tenant-1', userId: 'user-1' })
    contentSafetyApi.processAppeal('AP-00000000000000000000', 'APPROVED')
    contentSafetyApi.withdrawAdminAppeal('AP-00000000000000000000')

    expect(http.post).toHaveBeenCalledTimes(6)
    const headers = http.post.mock.calls.map((call) => call[2]?.headers?.['Idempotency-Key'])
    expect(headers).toHaveLength(6)
    expect(headers.every((value) => typeof value === 'string' && value.length >= 8)).toBe(true)
    expect(new Set(headers).size).toBe(6)
  })
})
