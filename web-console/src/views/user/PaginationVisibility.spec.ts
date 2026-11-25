import { describe, expect, it } from 'vitest'

import ordersSource from './OrdersView.vue?raw'
import usageSource from './UsageLogsView.vue?raw'

describe('single-page pagination visibility', () => {
  it('keeps the order pager visible for a non-empty single page', () => {
    expect(ordersSource).toContain('<el-pagination')
    expect(ordersSource).not.toContain('v-if="total > pageSize"')
  })

  it('keeps the usage-log pager visible for a non-empty single page', () => {
    expect(usageSource).toContain('<el-pagination')
    expect(usageSource).not.toContain('v-if="usage.usage.total > pageSize"')
  })
})
