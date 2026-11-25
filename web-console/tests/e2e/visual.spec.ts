import { mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { expect, test, type Page } from '@playwright/test'

import {
  ADMIN_EMAIL,
  ADMIN_PASSWORD,
  E2E_USER_PASSWORD,
  provisionUserWithApiKey,
} from './support/stack'

const ARTIFACT_ROOT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../../.planning/2026-08-02-dashboard-visualization/artifacts/visual',
)

const VIEWPORTS = [
  { name: 'desktop-1440x1000', width: 1440, height: 1000 },
  { name: 'tablet-1024x768', width: 1024, height: 768 },
  { name: 'mobile-390x844', width: 390, height: 844 },
] as const

interface PageAudit {
  name: string
  route: string
  title: string
  consoleErrors: string[]
  pageErrors: string[]
  layout: {
    documentOverflow: number
    mainOverflow: number
    headerMainOverlap: number
    sidebarWorkspaceOverlap: number
    clippedControls: string[]
  }
}

for (const viewport of VIEWPORTS) {
  test(`visually accepts user and admin workflows at ${viewport.width}x${viewport.height}`, async ({
    page,
    request,
  }) => {
    test.setTimeout(120_000)
    mkdirSync(ARTIFACT_ROOT, { recursive: true })
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await page.emulateMedia({ reducedMotion: 'reduce' })

    const provisioned = await provisionUserWithApiKey(request)
    const consoleErrors: string[] = []
    const pageErrors: string[] = []
    const audits: PageAudit[] = []
    let consoleCursor = 0
    let pageErrorCursor = 0

    page.on('console', (message) => {
      if (message.type() === 'error') consoleErrors.push(message.text())
    })
    page.on('pageerror', (error) => pageErrors.push(error.message))

    const capture = async (name: string) => {
      await page.waitForLoadState('networkidle')
      await expect(page.locator('.el-skeleton')).toHaveCount(0)
      const currentConsoleErrors = consoleErrors.slice(consoleCursor)
      const currentPageErrors = pageErrors.slice(pageErrorCursor)
      consoleCursor = consoleErrors.length
      pageErrorCursor = pageErrors.length

      const layout = await page.evaluate(() => {
        const html = document.documentElement
        const main = document.querySelector<HTMLElement>('.main-content')
        const header = document.querySelector<HTMLElement>('.header-bar')
        const sidebar = document.querySelector<HTMLElement>('.desktop-sidebar')
        const workspace = document.querySelector<HTMLElement>('.workspace')
        const visible = (element: HTMLElement) => {
          const style = getComputedStyle(element)
          const rect = element.getBoundingClientRect()
          return style.display !== 'none'
            && style.visibility !== 'hidden'
            && Number.parseFloat(style.opacity || '1') > 0
            && rect.width > 0
            && rect.height > 0
        }
        const describe = (element: HTMLElement) => {
          const testId = element.getAttribute('data-test')
          const label = element.getAttribute('aria-label')
          const text = element.textContent?.trim().replace(/\s+/g, ' ').slice(0, 60)
          return testId || label || text || element.tagName.toLowerCase()
        }
        const clippedControls = Array.from(document.querySelectorAll<HTMLElement>(
          'button, a[href], input, .el-select__wrapper',
        )).filter((element) => {
          if (!visible(element) || element.closest('.table-scroll')) return false
          const rect = element.getBoundingClientRect()
          const intersectsViewport = rect.bottom > 0
            && rect.top < window.innerHeight
            && rect.right > 0
            && rect.left < window.innerWidth
          return intersectsViewport && (
            rect.left < -1
            || rect.right > window.innerWidth + 1
          )
        }).map(describe)
        const headerRect = header?.getBoundingClientRect()
        const mainRect = main?.getBoundingClientRect()
        const sidebarRect = sidebar && visible(sidebar) ? sidebar.getBoundingClientRect() : null
        const workspaceRect = workspace?.getBoundingClientRect()

        return {
          documentOverflow: Math.max(0, html.scrollWidth - html.clientWidth),
          mainOverflow: main ? Math.max(0, main.scrollWidth - main.clientWidth) : 0,
          headerMainOverlap: headerRect && mainRect
            ? Math.max(0, headerRect.bottom - mainRect.top)
            : 0,
          sidebarWorkspaceOverlap: sidebarRect && workspaceRect
            ? Math.max(0, sidebarRect.right - workspaceRect.left)
            : 0,
          clippedControls,
        }
      })

      const audit: PageAudit = {
        name,
        route: new URL(page.url()).pathname,
        title: await page.title(),
        consoleErrors: currentConsoleErrors,
        pageErrors: currentPageErrors,
        layout,
      }
      audits.push(audit)
      writeFileSync(
        path.join(ARTIFACT_ROOT, `${viewport.name}-audit.json`),
        `${JSON.stringify(audits, null, 2)}\n`,
        'utf8',
      )
      await page.screenshot({
        path: path.join(ARTIFACT_ROOT, `${viewport.name}-${name}.png`),
        fullPage: true,
      })

      expect(currentConsoleErrors, `${name} console errors`).toEqual([])
      expect(currentPageErrors, `${name} page errors`).toEqual([])
      expect(layout.documentOverflow, `${name} document horizontal overflow`).toBeLessThanOrEqual(1)
      expect(layout.mainOverflow, `${name} main-content horizontal overflow`).toBeLessThanOrEqual(1)
      expect(layout.headerMainOverlap, `${name} header/main overlap`).toBeLessThanOrEqual(1)
      expect(layout.sidebarWorkspaceOverlap, `${name} sidebar/workspace overlap`).toBeLessThanOrEqual(1)
      expect(layout.clippedControls, `${name} clipped controls`).toEqual([])
    }

    await page.goto('/login')
    await expect(page.locator('[data-test="login-submit"]')).toBeVisible()
    await capture('login')

    await loginThroughUi(page, provisioned.userSession.user.email, E2E_USER_PASSWORD)
    await expect(page).toHaveURL(/\/app\/dashboard$/)
    await capture('user-dashboard')
    await navigateWithConsole(page, viewport.width, 'plans', '/app/plans')
    await capture('user-plans')
    await navigateWithConsole(page, viewport.width, 'api-keys', '/app/api-keys')
    await capture('user-api-keys')
    await navigateWithConsole(page, viewport.width, 'quota', '/app/quota')
    await capture('user-quota')

    await page.locator('[data-test="logout"]').click()
    await expect(page).toHaveURL(/\/login$/)
    await loginThroughUi(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await expect(page).toHaveURL(/\/admin\/overview$/)
    await capture('admin-overview')
    await navigateWithConsole(page, viewport.width, 'admin-models', '/admin/models')
    await capture('admin-models')
    await page.locator('[data-test="model-create"]').click()
    await expect(page.locator('[data-test="model-provider"]')).toBeVisible()
    await expect(page.locator('[data-test="model-upstream"]')).toBeVisible()
    await capture('admin-model-configuration')
    await page.getByRole('button', { name: '取消' }).click()
    await navigateWithConsole(page, viewport.width, 'admin-operations', '/admin/operations')
    await capture('admin-operations')
  })
}

async function loginThroughUi(page: Page, email: string, password: string) {
  await page.locator('[data-test="login-identifier"]').fill(email)
  await page.locator('[data-test="login-password"]').fill(password)
  await page.locator('[data-test="login-submit"]').click()
}

async function navigateWithConsole(
  page: Page,
  viewportWidth: number,
  navId: string,
  expectedPath: string,
) {
  if (viewportWidth < 768) {
    await page.locator('[data-test="open-mobile-nav"]').click()
    await page.locator(`[data-test="nav-${navId}"]`).click()
  } else {
    await page.locator(`[data-test="desktop-nav-${navId}"]`).click()
  }
  await expect(page).toHaveURL(new RegExp(`${expectedPath.replaceAll('/', '\\/')}$`))
  await expect(page.locator('#main-content h2').first()).toBeVisible()
}
