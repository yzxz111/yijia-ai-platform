import { describe, expect, it } from 'vitest'

import refundsSource from '../../components/admin/RefundReviewPanel.vue?raw'
import profileSource from '../user/ProfileView.vue?raw'
import grantsSource from './GrantPackagesView.vue?raw'
import operatingReportsSource from './OperatingReportsView.vue?raw'
import ordersSource from './OrdersPaymentsView.vue?raw'
import usageSource from './UsageQuotaView.vue?raw'
import usersSource from './UsersInvitationsView.vue?raw'
import walletSource from './WalletAdminView.vue?raw'

describe('user identity display contract', () => {
  it('shows a separate user-name column in every administrator user-number table', () => {
    for (const source of [usersSource, grantsSource, ordersSource, refundsSource, usageSource, walletSource]) {
      expect(source).toContain('<th>用户编号</th><th>用户名</th>')
      expect(source).toContain('userNameLabel(')
    }
  })

  it('shows both the user number and user name in administrator selectors and wallet lookup', () => {
    for (const source of [grantsSource, operatingReportsSource]) {
      expect(source).toContain('userOptionLabel(user.userNo, user.displayName)')
    }
    expect(walletSource).toContain('data-test="accounts-query"')
    expect(walletSource).toContain('placeholder="用户编号、用户名或联系方式"')
  })

  it('shows the stable user number in the personal center', () => {
    expect(profileSource).toContain('用户编号')
    expect(profileSource).toContain('class="info-summary-label">用户编号</div>')
    expect(profileSource).toContain('class="info-summary-value mono"')
    expect(profileSource).toContain("{{ profile.userNo || '--' }}")
  })
})
