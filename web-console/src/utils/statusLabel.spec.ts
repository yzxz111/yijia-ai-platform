import { describe, expect, it } from 'vitest'

import { statusLabel } from './statusLabel'

describe('statusLabel', () => {
  it.each([
    ['ACTIVE', '正常'],
    ['DISABLED', '已停用'],
    ['FROZEN', '已冻结'],
    ['ABNORMAL', '异常'],
    ['REVOKED', '已撤销'],
    ['EXPIRED', '已过期'],
  ])('localizes %s as %s', (status, label) => {
    expect(statusLabel(status)).toBe(label)
  })

  it('does not expose an unknown backend enum', () => {
    expect(statusLabel('UNRECOGNIZED_STATE')).toBe('未知状态')
    expect(statusLabel(null)).toBe('未知状态')
  })
})
