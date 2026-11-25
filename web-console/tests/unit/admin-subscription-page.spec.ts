import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

/**
 * 后台订阅治理页的契约。
 *
 * 与 `subscription-pages.spec.ts` 同一做法：断言打在**源码文本**上。本页会被
 * `admin-routes.spec.ts` 之类的测试通过路由表间接引用，因此这里不再挂载它——
 * 挂载一个会在 `onMounted` 发请求的视图会污染那些「看起来无关」的用例
 * （上一轮在 `user-workflows.spec.ts` 上刚踩过这个坑）。
 */
const read = (relative: string) => readFileSync(resolve(__dirname, relative), 'utf8')

describe('admin subscription page contracts', () => {
  const view = read('../../src/views/admin/SubscriptionsAdminView.vue')
  const router = read('../../src/router/index.ts')
  const sidebar = read('../../src/components/layout/SidebarNav.vue')

  it('is registered as an admin route and a sidebar entry', () => {
    expect(router).toMatch(/path:\s*'subscriptions'[\s\S]{0,140}SubscriptionsAdminView\.vue/)
    expect(router).toMatch(/SubscriptionsAdminView\.vue'\),\s*meta:\s*\{\s*title:\s*'订阅治理',\s*requiresAdmin:\s*true/)
    expect(sidebar).toMatch(/path:\s*'\/admin\/subscriptions'/)
  })

  it('formats money with integer minor units instead of floating point division', () => {
    expect(view).toMatch(/BigInt\(/)
    expect(view).toContain('absolute / 100n')
  })

  it('requires a reason before terminating so the audit entry is meaningful', () => {
    // 后端把原因写进审计，因此这里在提交前就拦住空原因，
    // 而不是让运维收到一个 422 之后再猜要填什么。
    expect(view).toMatch(/ElMessageBox\.prompt\(/)
    expect(view).toMatch(/inputValidator/)
    expect(view).toContain('会记入审计日志')
  })

  it('surfaces the scheduler lease so operators can tell running from idle', () => {
    // `charged` 是「正被调度实例处理」而不是「扣过款没有」。
    // 没有这个信号，运维无法区分「任务没跑」与「正在跑」。
    expect(view).toContain('data-test="admin-subscription-charging"')
    expect(view).toMatch(/row\.charged/)
  })

  it('reports an idempotent retry as information rather than as a failure', () => {
    // 后端在账期已不是 FAILED 时返回 retried=false。那不是错误——
    // 系统状态本来就是对的，报「失败」会让运维去排查一个不存在的问题。
    expect(view).toMatch(/result\.retried/)
    expect(view).toMatch(/ElMessage\.info\(/)
  })

  it('separates skip reasons from failures in the operator-facing copy', () => {
    expect(view).toContain('active_package_present')
    expect(view).toContain('data-test="admin-subscription-skip"')
    expect(view).toContain('data-test="admin-subscription-failures"')
  })

  it('supports filtering by status and plan code', () => {
    expect(view).toContain('data-test="admin-subscription-status-filter"')
    expect(view).toContain('data-test="admin-subscription-plan-filter"')
    expect(view).toMatch(/readApiErrorMessage/)
  })
})
