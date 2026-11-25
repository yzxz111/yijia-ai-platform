import { describe, expect, it } from 'vitest'

import headerSource from '../../components/layout/HeaderBar.vue?raw'
import dashboardSource from './DashboardView.vue?raw'
import ordersSource from './OrdersView.vue?raw'
import plansSource from './PlansView.vue?raw'
import quotaSource from './QuotaView.vue?raw'
import walletSource from './WalletView.vue?raw'

describe('document two UI fixes', () => {
  it('keeps metric and quota cards responsive without empty package space', () => {
    expect(dashboardSource).not.toContain('class="package-status"')
    expect(dashboardSource).toMatch(/\.metric-body\s*\{[^}]*min-width:\s*0/s)
    expect(dashboardSource).toContain('<div class="metric-grid">')
    expect(dashboardSource).toMatch(/\.metric-grid\s*\{[^}]*grid-template-columns:\s*repeat\(4, minmax\(0, 1fr\)\);/s)
    expect(dashboardSource).toContain('v-else class="quota-cards"')
    expect(dashboardSource).toMatch(/\.quota-cards\s*\{[^}]*grid-template-columns:\s*repeat\(3, minmax\(0, 1fr\)\);/s)
    expect(dashboardSource).toMatch(/\.quota-cards\s*\{[^}]*grid-template-columns:/s)
    expect(dashboardSource).toContain('.quota-cards { grid-template-columns: minmax(0, 1fr); }')
    expect(dashboardSource).toMatch(/@media\s*\(max-width:\s*1100px\)[\s\S]*\.quota-cards\s*\{\s*grid-template-columns:\s*minmax\(0, 1fr\);/s)
  })
  it('uses readable plan names, model labels, and published limits', () => {
    expect(plansSource).toContain('userModelLabel } from \'../../utils/modelLabel\'')
    expect(plansSource).toContain("import { userPlanDescription, userPlanName } from '../../utils/planCopy'")
    expect(plansSource).toContain('{{ userPlanName(plan.code, plan.name) }}')
    expect(plansSource).toContain('{{ userPlanDescription(plan.code, plan.description) }}')
    expect(plansSource).toContain('{{ userModelLabel(model.modelCode) }}')
    expect(plansSource).toMatch(/\.plan-desc\s*\{[^}]*line-height:\s*1\.5/s)
    expect(plansSource).toMatch(/\.plan-desc\s*\{[^}]*-webkit-line-clamp:\s*2;[^}]*min-height:\s*36px;/s)
    expect(plansSource).toMatch(/\.plan-quota\s*\{[^}]*grid-template-columns:\s*repeat\(2, 1fr\);/s)
    expect(plansSource).toContain('plan.requestsPerMinute')
    expect(plansSource).toContain('plan.tokensPerMinute')
    expect(plansSource).toContain('plan.maxConcurrency')
  })
  it('keeps wallet actions and pagination aligned with recharge state', () => {
    expect(walletSource).toContain("item.status === 'PAID' ? money(item.creditedAmount, item.currency) : '—'")
    expect(walletSource).toContain("if (status === 'PENDING') return 'warning'")
    expect(walletSource).toContain("if (status === 'FAILED') return 'danger'")
    expect(walletSource).toContain('data-test="resume-recharge"')
    expect(walletSource).toContain('data-test="cancel-recharge"')
    expect(walletSource).toContain('@size-change="changeRechargePageSize"')
    expect(walletSource).toContain('@size-change="changeLedgerPageSize"')
  })
  it('shows credit units and accessible low-quota warnings', () => {
    expect(quotaSource).toContain('remainingTokens.value / totalTokens.value <= 0.15')
    expect(quotaSource).toContain("remainingTokens.value <= 0")
    expect(quotaSource).toContain('积分额度已耗尽')
    expect(quotaSource).toContain("if (totalTokens.value > 0 && remainingTokens.value / totalTokens.value <= 0.15) return 'warning'")
    expect(quotaSource).toContain('<small>积分</small>')
    expect(quotaSource).not.toContain('<small>个积分</small>')
    expect(quotaSource).toContain('class="alert-banner" role="status"')
    expect(quotaSource).toContain(':class="`alert--${overallStatus}`"')
    expect(quotaSource).toContain(':class="`card--${overallStatus}`"')
    expect(quotaSource).toContain('.card--warning')
    expect(quotaSource).toContain('.card--danger')
  })
  it('keeps order filters and pagination available on narrow screens', () => {
    expect(ordersSource).toMatch(/\.page-header\s*\{[^}]*display:\s*flex/s)
    expect(ordersSource).toMatch(/@media[^}]*[\s\S]*\.page-header\s*\{[^}]*flex-direction:\s*column/)
    expect(ordersSource).toMatch(/\.action-btn\s*\{[^}]*padding:\s*6px 10px;/s)
    expect(ordersSource).toMatch(/@media\s*\(max-width:\s*640px\)[\s\S]*\.filter-bar\s*\{[^}]*flex-direction:\s*column;[^}]*align-items:\s*stretch;/s)
    expect(ordersSource).toMatch(/@media\s*\(max-width:\s*640px\)[\s\S]*\.action-btn\s*\{[^}]*min-height:\s*44px;/s)
    expect(ordersSource).not.toContain('v-if="total > pageSize"')
  })
  it('keeps one profile affordance in the top user control', () => {
    expect(headerSource).toContain('<el-dropdown')
    expect(headerSource).toContain('command="profile"')
    expect(headerSource).toContain('command="logout"')
    expect(headerSource).toContain('class="avatar"')
    expect(headerSource).toContain('UserRound')
  })
})
