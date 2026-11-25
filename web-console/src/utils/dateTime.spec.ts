import { describe, expect, it } from 'vitest'

import adminOverviewSource from '../views/admin/AdminOverviewView.vue?raw'
import auditSource from '../views/admin/AuditView.vue?raw'
import contentSafetySource from '../views/admin/ContentSafetyView.vue?raw'
import grantPackagesSource from '../views/admin/GrantPackagesView.vue?raw'
import operationsSource from '../views/admin/OperationsView.vue?raw'
import ordersPaymentsSource from '../views/admin/OrdersPaymentsView.vue?raw'
import usageQuotaSource from '../views/admin/UsageQuotaView.vue?raw'
import usersInvitationsSource from '../views/admin/UsersInvitationsView.vue?raw'
import walletAdminSource from '../views/admin/WalletAdminView.vue?raw'
import paymentAnomalySource from '../components/admin/PaymentAnomalyPanel.vue?raw'
import reconciliationSource from '../components/admin/ReconciliationPanel.vue?raw'
import refundPolicySource from '../components/admin/RefundPolicyPanel.vue?raw'
import refundReviewSource from '../components/admin/RefundReviewPanel.vue?raw'
import broadcastManagerSource from '../components/admin/BroadcastManager.vue?raw'
import dashboardSource from '../views/user/DashboardView.vue?raw'
import filesSource from '../views/user/FilesView.vue?raw'
import notificationsSource from '../views/user/NotificationsView.vue?raw'
import ordersSource from '../views/user/OrdersView.vue?raw'
import profileSource from '../views/user/ProfileView.vue?raw'
import usageLogsSource from '../views/user/UsageLogsView.vue?raw'
import walletSource from '../views/user/WalletView.vue?raw'
import { formatDateTime } from './dateTime'

const operationalDateSources = {
  adminOverviewSource,
  auditSource,
  contentSafetySource,
  grantPackagesSource,
  operationsSource,
  ordersPaymentsSource,
  usageQuotaSource,
  usersInvitationsSource,
  walletAdminSource,
  paymentAnomalySource,
  reconciliationSource,
  refundPolicySource,
  refundReviewSource,
  broadcastManagerSource,
  dashboardSource,
  filesSource,
  notificationsSource,
  ordersSource,
  profileSource,
  usageLogsSource,
  walletSource,
}

describe('formatDateTime', () => {
  it('formats ISO timestamps consistently in Asia/Shanghai', () => {
    expect(formatDateTime('2026-08-01T00:00:00Z')).toBe('2026-08-01 08:00:00')
    expect(formatDateTime(null)).toBe('-')
    expect(formatDateTime('not-a-date')).toBe('-')
  })

  it('routes operational date rendering through the shared formatter', () => {
    for (const [name, source] of Object.entries(operationalDateSources)) {
      expect(source, name).toContain('formatDateTime(')
      expect(source, name).not.toContain('new Intl.DateTimeFormat')
      expect(source, name).not.toMatch(
        /\{\{\s*[\w.]+\.(?:createdAt|updatedAt|expiresAt|paidAt|requestedAt|calculatedAt|reviewedAt|effectiveAt|resolvedAt|completedAt|nextAttemptAt|scheduledAt|gatewaySnapshotAt|lastValidatedAt)(?:\s*\|\|[^}]*)?\s*\}\}/,
      )
    }
  })
})
