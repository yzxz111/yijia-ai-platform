import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = (path: string) => readFile(resolve(process.cwd(), path), 'utf8')

describe('package plan lifecycle contracts', () => {
  it('exposes edit, archive and restore API operations', async () => {
    const api = await source('src/api/admin.ts')

    expect(api).toContain('updatePackagePlan')
    expect(api).toContain('platformHttp.patch<PackagePlanResult>')
    expect(api).toContain('/archive`')
    expect(api).toContain('/restore`')
  })

  it('shows package lifecycle and version-history actions', async () => {
    const view = await source('src/views/admin/PlansAdminView.vue')

    expect(view).toContain("plan.status === 'ACTIVE' ? '已上线' : '已下架'")
    expect(view).toContain('编辑套餐信息')
    expect(view).toContain('plan-archive-')
    expect(view).toContain('确认下架套餐')
    expect(view).toContain('plan-restore-')
    expect(view).toContain('historyVersions(plan)')
    expect(view).toContain("ElMessage.success('套餐已恢复上线')")
  })

  it('only offers active package plans for new grants', async () => {    const view = await source('src/views/admin/GrantPackagesView.vue')

    expect(view).toContain("allPlans.filter(plan => plan.planStatus === 'ACTIVE')")
    expect(view).toContain("form.packageVersionId = ''")
  })

  it('keeps per-model quota inputs and the derived usage dimension in the version dialog', async () => {
    const view = await source('src/views/admin/PlansAdminView.vue')

    expect(view).toContain('data-test="plan-model-quota"')
    expect(view).toContain('modelQuotas')
    expect(view).toContain('mixed_usage_dimensions_in_package')
    expect(view).toContain("'秒数'")
    expect(view).toContain('${totalQuotaUnit.value}额度')
    expect(view).toContain('${quotaUnit.value} / 分钟')
    expect(view).toContain('modelTokenQuota: versionForm.modelQuotas[modelCode] ?? null')
  })

  it('localizes package plan audit actions', async () => {
    const audit = await source('src/views/admin/AuditView.vue')

    expect(audit).toContain("PACKAGE_PLAN_CREATED: '套餐创建'")
    expect(audit).toContain("PACKAGE_PLAN_UPDATED: '套餐信息更新'")
    expect(audit).toContain("PACKAGE_PLAN_ARCHIVED: '套餐下架'")
    expect(audit).toContain("PACKAGE_PLAN_RESTORED: '套餐恢复上线'")
    expect(audit).toContain("PACKAGE_PLAN: '套餐'")
  })
})
