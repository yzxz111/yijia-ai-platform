import { describe, expect, it } from 'vitest'

import { adminDrillQuery } from './query'

describe('admin drill-through query hydration', () => {
  it('hydrates order search and selects an existing transaction tab', () => {
    expect(adminDrillQuery(
      { tab: 'orders', query: 'customer-order:42' },
      ['orders', 'payments', 'refunds', 'reconciliation'],
      'orders',
    )).toMatchObject({ tab: 'orders', search: 'customer-order:42' })
  })

  it('hydrates usage request and rejects unknown tabs', () => {
    expect(adminDrillQuery(
      { tab: 'unsafe', requestId: 'request-42', userId: 'user-7' },
      ['usage', 'reservations', 'ledger'],
      'usage',
    )).toMatchObject({ tab: 'usage', requestId: 'request-42', userId: 'user-7' })
  })
})
