import { mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { expect, test, type Locator, type Page } from '@playwright/test'

import {
  ADMIN_EMAIL,
  ADMIN_PASSWORD,
  bearer,
  E2E_USER_PASSWORD,
  expectStatus,
  provisionUserWithApiKey,
} from './support/stack'

const ARTIFACT_ROOT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../../.planning/2026-08-02-dashboard-visualization/artifacts/dashboard',
)

const VIEWPORTS = [
  { name: 'desktop-1440x1000', width: 1440, height: 1000, metricColumns: 4 },
  { name: 'tablet-1024x768', width: 1024, height: 768, metricColumns: 2 },
  { name: 'mobile-390x844', width: 390, height: 844, metricColumns: 1 },
] as const

const number = new Intl.NumberFormat('zh-CN')
const money = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' })

for (const viewport of VIEWPORTS) {
  test(`renders real user and admin analytics at ${viewport.width}x${viewport.height}`, async ({
    page,
    request,
  }) => {
    test.setTimeout(180_000)
    mkdirSync(ARTIFACT_ROOT, { recursive: true })
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await page.emulateMedia({ reducedMotion: 'reduce' })

    const consoleErrors: string[] = []
    const pageErrors: string[] = []
    page.on('console', (message) => {
      if (message.type() === 'error') consoleErrors.push(message.text())
    })
    page.on('pageerror', (error) => pageErrors.push(error.message))

    const provisioned = await provisionUserWithApiKey(request)
    const userAnalyticsResponse = await request.get('/console/gateway/usage/analytics?days=30', {
      headers: bearer(provisioned.userSession.accessToken),
    })
    await expectStatus(userAnalyticsResponse, 200, 'load real user analytics')
    const userAnalytics = await userAnalyticsResponse.json() as {
      range: { days: number }
      summary: { periodTokens: number; todayRequests: number; platformAddedP95Ms: number }
      daily: unknown[]
      models: unknown[]
    }

    const gatewayAdminResponse = await request.get('/console/gateway/admin/analytics?days=30', {
      headers: bearer(provisioned.adminSession.accessToken),
    })
    await expectStatus(gatewayAdminResponse, 200, 'load real gateway admin analytics')
    const gatewayAdmin = await gatewayAdminResponse.json() as {
      range: { days: number }
      summary: { todayRequests: number }
      daily: unknown[]
      packages: Array<{ activeCount: number }>
    }

    const operatingResponse = await request.get('/console/platform/admin/analytics?months=6', {
      headers: bearer(provisioned.adminSession.accessToken),
    })
    await expectStatus(operatingResponse, 200, 'load real operating analytics')
    const operating = await operatingResponse.json() as {
      months: number
      summary: { registeredUsers: number; currentMonthNetAmount: number }
      monthly: unknown[]
    }

    await page.goto('/login')
    await login(page, provisioned.userSession.user.email, E2E_USER_PASSWORD)
    await expect(page).toHaveURL(/\/app\/dashboard$/)
    await expect(page.locator('[data-test="dashboard-period-tokens"] strong')).toHaveText(
      number.format(userAnalytics.summary.periodTokens),
    )
    await expect(page.locator('[data-test="dashboard-today-requests"] strong')).toHaveText(
      number.format(userAnalytics.summary.todayRequests),
    )
    expect(userAnalytics.range.days).toBe(30)
    expect(userAnalytics.daily).toHaveLength(30)
    const userCanvas = await expectNonBlankCanvases(page, '[data-test="dashboard-chart"]')
    await expectMetricColumns(page.locator('.metric-grid > article'), viewport.metricColumns)
    const userLayout = await auditLayout(page, [
      '.metric-grid > article',
      '.chart-grid > article',
      '.quota-panel',
      '.recent-panel',
    ])
    expect(userLayout.documentOverflow).toBeLessThanOrEqual(1)
    expect(userLayout.mainOverflow).toBeLessThanOrEqual(1)
    expect(userLayout.overlaps).toEqual([])
    await page.screenshot({
      path: path.join(ARTIFACT_ROOT, `${viewport.name}-user-dashboard.png`),
      fullPage: true,
    })

    await page.locator('[data-test="logout"]').click()
    await expect(page).toHaveURL(/\/login$/)
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await expect(page).toHaveURL(/\/admin\/overview$/)
    await expect(page.locator('[data-test="admin-registered-users"] strong')).toHaveText(
      number.format(operating.summary.registeredUsers),
    )
    await expect(page.locator('[data-test="admin-active-packages"] strong')).toHaveText(
      number.format(gatewayAdmin.packages.reduce((sum, item) => sum + item.activeCount, 0)),
    )
    await expect(page.locator('[data-test="admin-net-receipts"] strong')).toHaveText(
      money.format(operating.summary.currentMonthNetAmount / 100),
    )
    await expect(page.locator('[data-test="admin-today-requests"] strong')).toHaveText(
      number.format(gatewayAdmin.summary.todayRequests),
    )
    expect(gatewayAdmin.range.days).toBe(30)
    expect(gatewayAdmin.daily).toHaveLength(30)
    expect(operating.months).toBe(6)
    expect(operating.monthly).toHaveLength(6)
    const adminCanvas = await expectNonBlankCanvases(page, '[data-test="admin-dashboard-chart"]')
    await expectMetricColumns(page.locator('.admin-metrics > article'), viewport.metricColumns)
    const adminLayout = await auditLayout(page, [
      '.admin-health-band',
      '.admin-metrics > article',
      '.admin-dashboard-grid > article',
      '.admin-usage-chart',
      '.admin-system-panel',
    ])
    expect(adminLayout.documentOverflow).toBeLessThanOrEqual(1)
    expect(adminLayout.mainOverflow).toBeLessThanOrEqual(1)
    expect(adminLayout.overlaps).toEqual([])
    await page.screenshot({
      path: path.join(ARTIFACT_ROOT, `${viewport.name}-admin-dashboard.png`),
      fullPage: true,
    })

    expect(consoleErrors).toEqual([])
    expect(pageErrors).toEqual([])
    writeFileSync(
      path.join(ARTIFACT_ROOT, `${viewport.name}-audit.json`),
      `${JSON.stringify({ userAnalytics, gatewayAdmin, operating, userCanvas, adminCanvas, userLayout, adminLayout }, null, 2)}\n`,
      'utf8',
    )
  })
}

async function login(page: Page, email: string, password: string) {
  await page.locator('[data-test="login-identifier"]').fill(email)
  await page.locator('[data-test="login-password"]').fill(password)
  await page.locator('[data-test="login-submit"]').click()
}

async function expectNonBlankCanvases(page: Page, rootSelector: string) {
  const roots = page.locator(rootSelector)
  const rootCount = await roots.count()
  expect(rootCount).toBeGreaterThan(0)
  await expect.poll(async () => page.locator(`${rootSelector} canvas`).count(), {
    timeout: 15_000,
  }).toBe(rootCount)
  const stats = await page.locator(`${rootSelector} canvas`).evaluateAll((canvases) => (
    canvases.map((element) => {
      const canvas = element as HTMLCanvasElement
      const context = canvas.getContext('2d', { willReadFrequently: true })
      if (!context || canvas.width === 0 || canvas.height === 0) {
        return { width: canvas.width, height: canvas.height, paintedSamples: 0 }
      }
      const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data
      let paintedSamples = 0
      for (let index = 3; index < pixels.length; index += 64) {
        if (pixels[index] > 0) paintedSamples += 1
      }
      return { width: canvas.width, height: canvas.height, paintedSamples }
    })
  ))
  for (const canvas of stats) {
    expect(canvas.width).toBeGreaterThan(0)
    expect(canvas.height).toBeGreaterThan(0)
    expect(canvas.paintedSamples).toBeGreaterThan(20)
  }
  return stats
}

async function expectMetricColumns(locator: Locator, expectedColumns: number) {
  const boxes = await locator.evaluateAll((elements) => elements.map((element) => {
    const rect = element.getBoundingClientRect()
    return { left: rect.left, top: rect.top }
  }))
  expect(boxes).toHaveLength(4)
  const firstRowTop = boxes[0].top
  expect(boxes.filter((box) => Math.abs(box.top - firstRowTop) <= 2)).toHaveLength(expectedColumns)
}

async function auditLayout(page: Page, selectors: string[]) {
  return page.evaluate((targetSelectors) => {
    const main = document.querySelector<HTMLElement>('.main-content')
    const elements = targetSelectors.flatMap((selector) => (
      Array.from(document.querySelectorAll<HTMLElement>(selector))
    )).filter((element) => {
      const rect = element.getBoundingClientRect()
      const style = getComputedStyle(element)
      return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0
    })
    const overlaps: string[] = []
    for (let left = 0; left < elements.length; left += 1) {
      for (let right = left + 1; right < elements.length; right += 1) {
        const first = elements[left].getBoundingClientRect()
        const second = elements[right].getBoundingClientRect()
        const overlapWidth = Math.min(first.right, second.right) - Math.max(first.left, second.left)
        const overlapHeight = Math.min(first.bottom, second.bottom) - Math.max(first.top, second.top)
        if (overlapWidth > 1 && overlapHeight > 1) {
          overlaps.push(`${elements[left].className} <> ${elements[right].className}`)
        }
      }
    }
    return {
      documentOverflow: Math.max(0, document.documentElement.scrollWidth - document.documentElement.clientWidth),
      mainOverflow: main ? Math.max(0, main.scrollWidth - main.clientWidth) : 0,
      overlaps,
    }
  }, selectors)
}
