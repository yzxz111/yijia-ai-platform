import { describe, expect, it } from 'vitest'

import usersSource from './UsersInvitationsView.vue?raw'

describe('administrator users table layout', () => {
  it('keeps the user-name column compact and hides the internal auth version', () => {
    expect(usersSource).toContain('class="admin-table users-table"')
    expect(usersSource).toContain('class="users-table__name"')
    expect(usersSource).toContain(':title="userNameLabel(user.displayName)"')
    expect(usersSource).toContain('class="users-table__name-text"')
    expect(usersSource).not.toContain('认证版本')
    expect(usersSource).not.toContain('user.authVersion')
    expect(usersSource).toContain('user.globalAdmin')
    expect(usersSource).toContain('user.statusManageable || user.roleManageable')
    expect(usersSource).toMatch(/\.users-table\s*\{[^}]*table-layout:\s*fixed;/s)
    expect(usersSource).toMatch(/:nth-child\(3\)\s*\{\s*width:\s*140px;\s*\}/)
    expect(usersSource).toMatch(/\.users-table__name-text\s*\{[^}]*text-overflow:\s*ellipsis;/s)
  })

  it('loads package filters from the catalog and supports banned-user filtering', () => {
    expect(usersSource).toContain('listPackagePlanCatalog')
    expect(usersSource).toContain("size: 100, query: '', status: ''")
    expect(usersSource).toContain('v-for="plan in packageFilters"')
    expect(usersSource).toContain('data-test="all-users-filter"')
    expect(usersSource).not.toContain("code: 'STARTER'")
    expect(usersSource).toContain('data-test="banned-filter"')
    expect(usersSource).toContain('userPlanName(user.currentPackageCode, user.currentPackage)')
    expect(usersSource).toContain("status: 'DISABLED'")
    expect(usersSource).toContain("'封禁用户'")
  })
})
