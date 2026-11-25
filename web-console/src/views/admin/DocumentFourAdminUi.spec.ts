import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const view = (name: string) => readFile(resolve(process.cwd(), 'src/views/admin', name), 'utf8')
const component = (name: string) => readFile(resolve(process.cwd(), 'src/components/admin', name), 'utf8')

describe('document four admin UI contracts', () => {
  it('does not duplicate primary service statuses on the overview', async () => {
    const source = await view('AdminOverviewView.vue')
    expect(source).toContain('componentSystems')
    expect(source).toMatch(/v-for="system in componentSystems"/)
    expect(source).toContain('primarySystems')
    expect(source).toMatch(/v-for="system in primarySystems"/)
    const componentSection = source.slice(
      source.indexOf('const componentSystems'), source.indexOf('function componentStatusClass'),
    )
    expect(componentSection).not.toMatch(/key: '(?:platform|gateway|database|postgresql)'/)
    expect(componentSection).toContain("key: 'cache'")
    expect(componentSection).toContain("key: 'mq'")
    expect(componentSection).toContain("key: 'scheduler'")
  })

  it('uses real report selects and paginates detail rows', async () => {
    const source = await view('OperatingReportsView.vue')
    expect(source).not.toContain('<datalist')
    expect(source).toContain('reportDetailPage')
    expect(source).toContain('pagedBreakdownRows')
    expect(source).toContain('report-detail-pagination')
    expect(source).toContain('userOptionLabel')
    expect(source).toContain('loadAllPages')
    expect(source).toContain('TopErrorMessage :message="optionError"')
    expect(source).not.toContain('report-options-error')
  })

  it('shows user numbers and names in users and grant workflows', async () => {
    const [users, grants] = await Promise.all([
      view('UsersInvitationsView.vue'),
      view('GrantPackagesView.vue'),
    ])
    expect(users).toContain('<th>用户编号</th><th>用户名</th>')
    expect(users).toContain('userNoLabel(user.userNo)')
    expect(users).toContain('userNameLabel(user.displayName)')
    expect(users).not.toContain('{{ user.email }}')
    expect(grants).toContain('copyBusinessNo')
    expect(grants).toContain('<th>用户编号</th><th>用户名</th>')
    expect(grants).toContain('userNoLabel(grant.userNo)')
    expect(grants).toContain('userNameLabel(grant.userDisplayName)')
    expect(grants).toContain('userOptionLabel(user.userNo, user.displayName)')
    expect(grants).toContain('loadAllPages')
    expect(grants).not.toContain('grant.userEmail')
  })

  it('keeps plan and model forms aligned', async () => {
    const [plans, models] = await Promise.all([
      view('PlansAdminView.vue'),
      view('ModelsView.vue'),
    ])
    expect(plans).toContain('plan-create-toggle')
    expect(plans).toContain('plan-form-actions')
    expect(plans).toContain('aria-label="版本统计"')
    expect(plans).toContain('class="plan-stats-grid"')
    expect(plans).toContain('data-test="plan-catalog"')
    expect(plans).toContain('class="plan-spec-grid"')
    expect(plans).toContain('可调用模型：')
    expect(plans).toContain('累计销量')
    expect(models).toContain('model-toolbar')
    expect(models).toContain('{{ model.displayName }}')
    expect(models).not.toContain('adminModelName')
    expect(models).not.toContain('class="model-translation"')
  })

  it('localizes usage and quota fields without exposing UUIDs', async () => {
    const source = await view('UsageQuotaView.vue')
    expect(source).toContain('resolveAdminUserNumbers')
    expect(source).toContain('用户编号')
    expect(source).toContain('用户名')
    expect(source).toContain('变更（积分）')
    expect(source).toContain('余额（积分）')
    expect(source).not.toMatch(/\{\{\s*(reservation|entry)\.userId\s*\}\}/)
  })

  it('adds order details, masks credentials and limits simulation', async () => {
    const source = await view('OrdersPaymentsView.vue')
    expect(source).toContain('fingerprintLabel')
    expect(source).toContain('凭据指纹')
    expect(source).toContain('订单详情')
    expect(source).toMatch(/paymentProvider === 'MOCK'.*status === 'PENDING'/s)
    expect(source).not.toContain('order.userEmail')
    expect(source).toContain('row.packageVersion')
    expect(source).not.toMatch(/\{\{\s*row\.packageCode\s*\}\}/)
    expect(source).toContain('userPlanName(row.packageCode, row.packageName)')
    expect(source).not.toContain(':title="config.credentialFingerprint')
    expect(source).toContain('class="order-user"')
    expect(source).toContain('userNameLabel(row.userDisplayName)')
    expect(source).toContain('userNoLabel(row.userNo)')
  })

  it('renders refund users by number and name without contact details', async () => {
    const source = await component('RefundReviewPanel.vue')
    expect(source).toContain('<th>用户编号</th><th>用户名</th>')
    expect(source).toContain('userNoLabel(row.userNo)')
    expect(source).toContain('userNameLabel(row.userDisplayName)')
    expect(source).not.toContain('row.userEmail')
  })

  it('makes wallet full-width and shows user numbers with names', async () => {
    const source = await view('WalletAdminView.vue')
    expect(source).toContain('listAdminWalletAccounts')
    expect(source).toContain('query: accountsFilters.query.trim()')
    expect(source).toContain('data-test="accounts-query"')
    expect(source).toContain('<th>用户编号</th><th>用户名</th>')
    expect(source).toContain('userNoLabel')
    expect(source).toContain('userNameLabel')
    expect(source).toMatch(/\.wallet-admin-page\s*\{[^}]*width:\s*100%/s)
    expect(source).not.toMatch(/\{\{\s*(row|account|recharge|entry)\.email\s*\}\}/)
  })
})
