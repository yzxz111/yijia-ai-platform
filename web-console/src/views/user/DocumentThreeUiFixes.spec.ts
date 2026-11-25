import { readFileSync } from 'node:fs'
import path from 'node:path'

import { describe, expect, it } from 'vitest'

import authBrandSource from '../../components/auth/AuthBrandPanel.vue?raw'
import sidebarSource from '../../components/layout/SidebarNav.vue?raw'
import routerSource from '../../router/index.ts?raw'
import notificationsSource from './NotificationsView.vue?raw'
import profileSource from './ProfileView.vue?raw'
import quotaSource from './QuotaView.vue?raw'
import usageSource from './UsageLogsView.vue?raw'
import walletSource from './WalletView.vue?raw'

const adminCssSource = readFileSync(path.resolve(process.cwd(), 'src/styles/admin.css'), 'utf8')

describe('document three UI fixes', () => {
  it('keeps wallet history and usage filters aligned across desktop and mobile', () => {
    expect(walletSource).toContain('v-if="wallet.recharges.total > 0"')
    expect(walletSource).toContain('v-if="wallet.ledger.total > 0"')
    expect(walletSource).toMatch(/\.history-heading\s*\{[^}]*align-items:\s*center;/s)
    expect(usageSource).toMatch(/\.filter-bar\s*\{[^}]*display:\s*flex;[^}]*flex-wrap:\s*wrap;[^}]*align-items:\s*center;/s)
    expect(usageSource).toMatch(/\.filter-bar :deep\(\.el-input__wrapper\)[\s\S]*?\{[^}]*min-height:\s*40px;/s)
    expect(usageSource).toMatch(/@media\s*\(max-width:\s*640px\)[\s\S]*\.filter-bar :deep\(\.el-input\)[\s\S]*?width:\s*100% !important;/s)
  })

  it('uses independent quota wording and removes duplicated profile subscription content', () => {
    expect(quotaSource).toContain('模型独立额度')
    expect(quotaSource).not.toContain('模型子额度')
    expect(profileSource).not.toContain('subscription-panel')
  })

  it('keeps phone and email binding layouts and current-contact verification', () => {
    expect(profileSource).toContain('class="email-binding-inline"')
    expect(profileSource).toContain('class="phone-binding-inline"')
    expect(profileSource).toContain('v-if="!emailChangeReady"')
    expect(profileSource).toContain('v-model="currentEmailCode"')
    expect(profileSource).toContain('当前邮箱验证码')
    expect(profileSource).toMatch(/\.email-binding-inline,\s*\.phone-binding-inline\s*\{[^}]*flex-direction:\s*column/s)
    expect(profileSource).toContain('.inline-code-row { grid-template-columns: minmax(0, 1fr); }')
    expect(profileSource).toContain('v-if="profile.email && emailChangeVerificationOpen"')
    expect(profileSource).toContain('@click="sendCurrentEmailCode"')
    expect(profileSource).toContain('aria-label="邮箱验证码"')
    expect(profileSource).toContain('purpose="PHONE_CHANGE_CONFIRM"')
    expect(profileSource).toContain('<SmsCodeField')
    expect(profileSource).toMatch(/\.form-grid\s*\{[^}]*grid-template-columns:\s*repeat\(2, minmax\(0, 1fr\)\);[^}]*align-items:\s*start;/s)
    expect(profileSource).toMatch(/\.inline-code-row\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\) auto;/s)
    expect(profileSource).toMatch(/@media\s*\(max-width:\s*860px\)[\s\S]*\.form-grid\s*\{\s*grid-template-columns:\s*minmax\(0, 1fr\);/s)
  })
  it('removes notification archive and adds authenticated help navigation', () => {
    expect(notificationsSource).not.toContain('archiveNotification')
    expect(notificationsSource).not.toContain('data-test="archive"')
    expect(sidebarSource).toContain("path: '/app/help'")
    expect(routerSource).toContain("path: 'help'")
  })

  it('leaves customer branding empty and aligns paired admin chart headers', () => {
    expect(authBrandSource).toContain('让 AI 能力，')
    expect(authBrandSource).toContain('触手可及。')
    expect(authBrandSource).toContain('统一管理模型接入')
    expect(authBrandSource).not.toContain('汇聚企业级 AI 能力')
    expect(authBrandSource).not.toContain('brand-mark')
    expect(authBrandSource).not.toContain('Zap')
    expect(adminCssSource).toContain('.admin-dashboard-grid > .admin-chart-panel > .admin-panel__header')
    expect(adminCssSource).toMatch(/\.admin-dashboard-grid\s*>\s*\.admin-chart-panel\s*\{[^}]*margin-top:\s*0;/s)
  })
})
