import { expect, request as playwrightRequest, test, type APIRequestContext, type Page } from '@playwright/test'

import {
  ADMIN_EMAIL,
  ADMIN_PASSWORD,
  E2E_USER_PASSWORD,
  claimFreePackage,
  cleanupStackUsers,
  pollGatewayPackageState,
  provisionLocalStackUser,
  type RegisteredStackUser,
} from './support/stack'

/**
 * 为毛利、限流、告警解决与结算垫付工作新增的四个运营／客户可见界面，
 * 针对已部署的控制台检查（E2E_BASE_URL，默认 http://127.0.0.1:18081 ）。
 *
 * 断言对「取值」刻意宽容：平台服务
 * 可能尚未上线新接口／数据，而约定的契约是
 * 字段缺失时渲染为「—」或明确的「暂时不可用」
 * 文案。绝不允许出现的是白屏、凭空编造的 0，或者崩溃。
 */
let api: APIRequestContext
let stack: RegisteredStackUser
let userEmail: string
let packageInstanceId = ''
let activePackageCode = ''
let adminToken = ''
const integer = new Intl.NumberFormat('zh-CN')
const money = (cents: number) => new Intl.NumberFormat('zh-CN', {
  style: 'currency', currency: 'CNY', currencyDisplay: 'narrowSymbol', minimumFractionDigits: 2,
}).format(cents / 100)

test.beforeAll(async ({}, testInfo) => {
  const baseURL = String(testInfo.project.use.baseURL ?? process.env.E2E_BASE_URL ?? 'http://127.0.0.1:18081')
  api = await playwrightRequest.newContext({
    baseURL,
    // 平台登录要求 32 位十六进制设备号（见 src/utils/deviceId.ts）
    extraHTTPHeaders: { 'X-Device-Id': '0123456789abcdef0123456789abcdef' },
  })
  stack = await provisionLocalStackUser(api)
  userEmail = `e2e-wallet-${stack.runId}@local.test`
  // 免费套餐领取是用户侧接口，不依赖管理员发放端点。
  await claimFreePackage(api, stack.userSession.accessToken)
  const summary = await pollGatewayPackageState(
    api,
    stack.userSession.accessToken,
    (value) => value.activePackage?.status === 'ACTIVE',
    'free package activation',
  )
  packageInstanceId = summary.activePackage?.packageInstanceId ?? ''
  activePackageCode = summary.activePackage?.packageCode ?? ''
  const adminLogin = await api.post('/console/platform/auth/login', {
    data: {
      email: ADMIN_EMAIL,
      password: ADMIN_PASSWORD,
    },
  })
  if (adminLogin.ok()) adminToken = (await adminLogin.json()).accessToken
})

test.afterAll(async () => {
  if (stack) await cleanupStackUsers([stack])
  await api?.dispose()
})

async function loginThroughUi(page: Page, email: string, password: string) {
  await page.goto('/login')
  await page.locator('[data-test="login-identifier"]').fill(email)
  await page.locator('[data-test="login-password"]').fill(password)
  await page.locator('[data-test="login-submit"]').click()
  await expect(page.locator('#main-content')).toBeVisible({ timeout: 20_000 })
}

test('a customer sees dimension labels but none of the internal economics', async ({ page }, testInfo) => {
  await loginThroughUi(page, userEmail, E2E_USER_PASSWORD)
  await page.goto('/app/plans')

  const card = page.locator('[data-test="plan-card"]').first()
  await expect(card).toBeVisible()
  // 维度修正保留：比较表里每分钟限速是按维度标注的
  await expect(page.locator('.compare-table')).toContainText('每分钟限速')
  console.log(`[live] customer plan page has 每分钟限速 row: ${await page.locator('.compare-table').innerText().then(t => t.includes('每分钟限速'))}`)

  // 泄漏守卫：供应商成本与毛利是内部成本结构，用户端一个字都不能出现
  for (const marker of ['最坏毛利', '预估成本', '毛利预警']) {
    await expect(page.locator('#main-content')).not.toContainText(marker)
    console.log(`[live] customer page free of "${marker}": true`)
  }
  await expect(page.locator('[data-test="plan-margin"]')).toHaveCount(0)
  await expect(page.locator('[data-test="plan-margin-badge"]')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('plans-customer.png'), fullPage: true })

  await page.goto('/app/quota')
  const panel = page.locator('[data-test="limit-usage-panel"]')
  await expect(panel).toBeVisible()
  await expect(page.locator('#quota-title')).toBeVisible()

  // 直接问接口要一次原始状态与响应体：区分"端点没上线"和"控制台渲染错"。
  const probe = await api.get(`/console/platform/packages/${packageInstanceId}/limit-usage`, {
    headers: { Authorization: `Bearer ${stack.userSession.accessToken}` },
  })
  const probeBody = await probe.text()
  console.log(`[probe] GET /console/platform/packages/${packageInstanceId}/limit-usage -> ${probe.status()} ${probeBody}`)

  if (probe.ok()) {
    const usage = JSON.parse(probeBody) as {
      usageDimension: string
      perMinute: { limit: number | null; used: number }
      daily: { limit: number | null; used: number }
      blockedLast24h: number
      blockedLast24HoursByReason: Record<string, number>
    }
    const window = (value: { limit: number | null; used: number }) =>
      `${integer.format(value.used)} / ${value.limit == null ? '不限' : integer.format(value.limit)}`
    // 按单位计费的维度（视频秒、音频秒、图片张）在限流面板上都读作「每分钟单位数」，
    // 只有 TOKEN 才读作积分/分钟。AUDIO_SECOND 必须在这里出现：
    // 漏掉它会让一个音频套餐的每分钟上限被断言成 TPM，而那一列装的是秒数。
    const isUnitDimension = (dimension?: string) =>
      dimension === 'VIDEO_SECOND' || dimension === 'IMAGE' || dimension === 'AUDIO_SECOND'
    const perMinuteLabel = isUnitDimension(usage.usageDimension)
      ? 'SPM（秒/分钟）'
      : 'TPM（积分/分钟）'

    // 标签必须跟着方案的维度走：视频/音频套餐按秒（SPM），文本套餐按积分（TPM）
    const planList = await api.get('/console/platform/plans', {
      headers: { Authorization: `Bearer ${stack.userSession.accessToken}` },
    })
    if (planList.ok()) {
      const plans = await planList.json() as Array<{ code: string; usageDimension?: string }>
      const activePlan = plans.find((value) => value.code === activePackageCode)
      console.log(`[live] plan dimension for ${activePackageCode}: ${activePlan?.usageDimension} / limits report ${usage.usageDimension}`)
      if (activePlan?.usageDimension) {
        expect(perMinuteLabel).toBe(isUnitDimension(activePlan.usageDimension)
          ? 'SPM（秒/分钟）'
          : 'TPM（积分/分钟）')
      }
    }

    await expect(page.locator('[data-test="limit-usage-unavailable"]')).toHaveCount(0)
    await expect(page.locator('[data-test="limit-usage-per-minute"]')).toContainText(perMinuteLabel)
    await expect(page.locator('[data-test="limit-usage-per-minute"]')).toContainText(window(usage.perMinute))
    await expect(page.locator('[data-test="limit-usage-daily"]')).toContainText(window(usage.daily))
    await expect(page.locator('[data-test="limit-usage-blocked"]')).toContainText(integer.format(usage.blockedLast24h))
    if (usage.blockedLast24HoursByReason?.daily_request_limit_exceeded != null) {
      await expect(page.locator('[data-test="limit-usage-reason-daily"]'))
        .toContainText(integer.format(usage.blockedLast24HoursByReason.daily_request_limit_exceeded))
    }
    if (usage.blockedLast24HoursByReason?.tokens_per_minute_exceeded != null) {
      await expect(page.locator('[data-test="limit-usage-reason-rate"]'))
        .toContainText(integer.format(usage.blockedLast24HoursByReason.tokens_per_minute_exceeded))
    }
    console.log(`[live] limit usage matches API: dimension=${usage.usageDimension} label=${perMinuteLabel} perMinute=${window(usage.perMinute)} daily=${window(usage.daily)} blocked=${usage.blockedLast24h} reasons=${JSON.stringify(usage.blockedLast24HoursByReason)}`)
  } else {
    await expect(page.locator('[data-test="limit-usage-unavailable"]')).toContainText('暂时取不到用量')
    console.log('[live] limit usage panel degraded to 暂时取不到用量 for the status above')
  }

  console.log(`[live] limit usage panel: ${(await panel.innerText()).replaceAll('\n', ' | ')}`)
  await page.screenshot({ path: testInfo.outputPath('quota-limit-usage.png'), fullPage: true })
})

test('an administrator sees cost, worst-case margin and the 预警 badge on plan versions', async ({ page }, testInfo) => {
  await loginThroughUi(page, ADMIN_EMAIL, ADMIN_PASSWORD)

  // 运营口径：成本与毛利只出现在管理端套餐版本页
  await page.locator('[data-test="desktop-nav-admin-plans"]').click()
  const marginPanel = page.locator('[data-test="plan-margin"]').first()
  await expect(marginPanel).toContainText('最坏毛利')
  await expect(page.locator('[data-test="plan-cost"]').first()).toContainText('预估成本')
  const badges = page.locator('[data-test="plan-margin-badge"]')
  await expect(badges.first()).toHaveText('预警')
  await expect(page.locator('[data-test="plan-margin-warning"]').first())
    .toContainText('毛利预警：最坏情况毛利落在 30%–40% 目标区间内')
  console.log(`[live] admin plan versions: ${(await marginPanel.innerText()).replaceAll('\n', ' | ')} / badges=${await badges.count()}`)
  console.log(`[live] admin warning line: ${(await page.locator('[data-test="plan-margin-warning"]').first().innerText())}`)
  await page.screenshot({ path: testInfo.outputPath('admin-plan-margin.png'), fullPage: true })

  await page.locator('[data-test="desktop-nav-admin-operating-reports"]').click()
  await page.locator('[data-test="tab-alerts"]').click()
  await expect(page.locator('[data-test="alert-status-OPEN"]')).toBeVisible()
  await page.locator('[data-test="alert-status-RESOLVED"]').click()
  await expect(page.locator('[data-test="alert-status-RESOLVED"]')).toHaveAttribute('aria-pressed', 'true')
  await expect(page.locator('[data-test="alerts-pagination"]')).toBeVisible()
  console.log(`[live] resolved alerts tab: ${(await page.locator('.report-panel').last().innerText()).replaceAll('\n', ' | ')}`)

  await page.locator('[data-test="desktop-nav-admin-settlement-deficit"]').click()
  await expect(page.locator('#settlement-deficit-title')).toBeVisible()

  const probe = adminToken
    ? await api.get('/console/platform/admin/operations/settlement-deficit', {
      headers: { Authorization: `Bearer ${adminToken}` },
    })
    : null
  const probeBody = probe ? await probe.text() : '(no admin API token)'
  console.log(`[probe] GET /console/platform/admin/operations/settlement-deficit -> ${probe?.status()} ${probeBody}`)

  if (probe?.ok()) {
    const deficit = JSON.parse(probeBody) as {
      totalAmount: number
      totalTokens: number
      count: number
      truncated: boolean
      items: Array<{ reservationId: string; amount: number; tokens: number; occurredAt: string; planCode: string | null; userId: string | null }>
    }
    await expect(page.locator('[data-test="deficit-unavailable"]')).toHaveCount(0)
    await expect(page.locator('[data-test="deficit-total"]')).toHaveText(money(deficit.totalAmount))
    await expect(page.locator('[data-test="deficit-count"]')).toHaveText(integer.format(deficit.count))
    await expect(page.locator('[data-test="deficit-tokens"]')).toHaveText(integer.format(deficit.totalTokens))
    console.log(`[live] settlement deficit matches API: total=${money(deficit.totalAmount)} count=${deficit.count} tokens=${deficit.totalTokens} items=${deficit.items.length} truncated=${deficit.truncated}`)
    if (deficit.items.length) {
      await expect(page.locator('.settlement-deficit__table tbody tr').first())
        .toContainText(deficit.items[0]!.reservationId)
    }
  } else {
    await expect(page.locator('[data-test="deficit-unavailable"]')).toContainText('暂时取不到结算垫付数据')
    console.log('[live] settlement deficit degraded to 暂时取不到结算垫付数据 for the status above')
  }

  console.log(`[live] settlement deficit page: ${(await page.locator('#main-content').innerText()).replaceAll('\n', ' | ')}`)
  await page.screenshot({ path: testInfo.outputPath('settlement-deficit.png'), fullPage: true })
})
