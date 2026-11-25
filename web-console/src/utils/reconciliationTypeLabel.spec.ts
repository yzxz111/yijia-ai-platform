import { describe, expect, it } from 'vitest'

import { reconciliationTypeLabel } from './reconciliationTypeLabel'

describe('reconciliationTypeLabel', () => {
  it.each([
    ['MISSING', '经营事实缺失'],
    ['DUPLICATE', '事实记录重复'],
    ['AMOUNT_MISMATCH', '金额不一致'],
    ['ESTIMATED_NOT_ACTUAL', '估算值未转实际'],
    ['MISSING_SOURCE', '来源记录缺失'],
    ['GATEWAY_OUTBOX_BACKLOG', '网关消息积压'],
    ['AGGREGATE_MISMATCH', '汇总金额不一致'],
    ['PAYLOAD_HASH_MISMATCH', '数据摘要不一致'],
  ])('localizes %s as %s', (type, label) => {
    expect(reconciliationTypeLabel(type)).toBe(label)
  })

  it('does not expose an unknown backend enum', () => {
    expect(reconciliationTypeLabel('NEW_DIFFERENCE_TYPE')).toBe('其他差异')
    expect(reconciliationTypeLabel(null)).toBe('其他差异')
  })
})

