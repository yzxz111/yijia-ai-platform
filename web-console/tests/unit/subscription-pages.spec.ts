import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

/**
 * 订阅页与既有一次性购买页的契约。
 *
 * 这一层断言打在**源码文本**上而不是渲染结果上：`.plan-footer` 的对齐方式与
 * `data-test` 选择器是既有测试（`PlansViewLayout.spec.ts`）锁定的跨文件约定，
 * 源码快照是验证「逐字未变」最直接的方式——渲染断言只能覆盖它碰巧渲染到的分支。
 */
const read = (relative: string) => readFileSync(resolve(__dirname, relative), 'utf8')

describe('subscription page contracts', () => {
  const subscriptionsView = read('../../src/views/user/SubscriptionsView.vue')
  const plansView = read('../../src/views/user/PlansView.vue')
  const router = read('../../src/router/index.ts')
  const sidebar = read('../../src/components/layout/SidebarNav.vue')

  it('registers the subscription page for users and in the sidebar', () => {
    expect(router).toMatch(/path:\s*'subscriptions'[\s\S]{0,120}SubscriptionsView\.vue/)
    expect(router).toMatch(/path:\s*'subscriptions'[\s\S]{0,200}requiresUser:\s*true/)
    expect(sidebar).toMatch(/path:\s*'\/app\/subscriptions'/)
  })

  it('formats money with integer minor units instead of floating point division', () => {
    // `2900 / 100` 在 JS 里是 28.999999999999996 这类值的来源；
    // 金额展示必须走 BigInt，与 WalletView.vue 同一实现。
    expect(subscriptionsView).toMatch(/BigInt\(/)
    expect(subscriptionsView).toContain("absolute / 100n")
  })

  it('states the post-cancellation period explicitly instead of hiding the date', () => {
    // 关闭续费后不能再显示「下次扣款 X 日」——那时已不会扣款，
    // 继续显示一个扣款日期会让用户以为还会被扣。
    expect(subscriptionsView).toContain('不再续费')
    expect(subscriptionsView).toMatch(/row\.autoRenew\s*&&\s*!row\.cancelAtPeriodEnd/)
  })

  it('asks for confirmation before cancelling', () => {
    expect(subscriptionsView).toMatch(/ElMessageBox\.confirm\(/)
  })

  it('separates skip reasons from failures in the user-facing copy', () => {
    // 「你已持有生效中的套餐，本次未扣款」不是失败。后端把两者分成不同字段，
    // 前端也必须有两套文案，否则用户会为一个正常状态白白紧张。
    expect(subscriptionsView).toContain('active_package_present')
    expect(subscriptionsView).toContain('已持有生效中的套餐')
    expect(subscriptionsView).toContain('data-test="subscription-failures"')
  })

  it('leaves the one-time purchase flow untouched', () => {
    // 订阅是**新增**能力：既有的购买按钮与它的 disabled 分支必须逐字保持原样，
    // 这条断言与 PlansViewLayout.spec.ts 一起构成「没碰一次性购买」的证据。
    expect(plansView).toContain('data-test="buy-plan"')
    expect(plansView).toContain('data-test="claim-free"')
    expect(plansView).toMatch(/\.plan-footer\s*\{[^}]*margin-top:\s*auto;/)
  })

  it('shows the real charge date rather than the expiry date on orders and dashboard', () => {
    // 修掉一处既有错误文案：这两个页面此前把**套餐到期日**渲染成「下次续费」。
    // 订阅用户的扣款发生在到期前 24 小时，是两个不同的日子，用户在错误的日子等扣款。
    const ordersView = read('../../src/views/user/OrdersView.vue')
    const dashboardView = read('../../src/views/user/DashboardView.vue')

    expect(ordersView).toMatch(/subscription\?\.nextChargeAt/)
    expect(ordersView).toContain('下次自动扣款')
    expect(dashboardView).toMatch(/activeSubscription\.value\?\.nextChargeAt\s*\?\?\s*active\.value\?\.expiresAt/)
    // 没有订阅时不能说「续费」——那句话说的是到期。
    expect(dashboardView).toContain('距套餐到期还有')
  })
})
