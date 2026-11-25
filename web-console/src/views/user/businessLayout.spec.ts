import { describe, expect, it } from 'vitest'

import apiKeysSource from './ApiKeysView.vue?raw'
import ordersSource from './OrdersView.vue?raw'
import plansSource from './PlansView.vue?raw'
import quotaSource from './QuotaView.vue?raw'
import usageSource from './UsageLogsView.vue?raw'
import walletSource from './WalletView.vue?raw'

const businessPages = [
  ['套餐管理', plansSource, '.plans-page'],
  ['订单记录', ordersSource, '.orders-page'],
  ['余额钱包', walletSource, '.wallet-page'],
  ['API 密钥', apiKeysSource, '.page-shell'],
  ['积分额度', quotaSource, '.quota-page'],
  ['调用日志', usageSource, '.usage-page'],
] as const

describe('business page layout', () => {
  it.each(businessPages)('%s fills the shared content frame', (_name, source, selector) => {
    const ruleStart = source.indexOf(`${selector} {`)
    const ruleEnd = source.indexOf('}', ruleStart)
    const rule = source.slice(ruleStart, ruleEnd)

    expect(ruleStart).toBeGreaterThanOrEqual(0)
    expect(rule).toMatch(/width:\s*100%;/)
    expect(rule).not.toMatch(/width:\s*min\(/)
  })

  it('keeps the plan cards in a centered readable column within the full-width page', () => {
    expect(plansSource).toMatch(/\.page-container\s*\{[^}]*max-width:\s*1180px;[^}]*margin:\s*0 auto;/s)
  })
})
