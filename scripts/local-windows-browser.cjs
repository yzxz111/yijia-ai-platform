// Browser acceptance against real local APIs. No fixture responses are injected.
const fs = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')
const assert = require('node:assert/strict')
const root = path.resolve(__dirname, '..')
const runtime = process.env.YIJIA_LOCAL_RUNTIME || path.resolve(root, '../../../yijia-local-runtime')
const output = path.join(runtime, 'browser-checks')
fs.mkdirSync(output, { recursive: true })
const env = Object.fromEntries(fs.readFileSync(path.join(root, 'deploy/env/.env'), 'utf8').split(/\r?\n/)
  .filter(l => /^[A-Z][A-Z0-9_]*=/.test(l)).map(l => { const i = l.indexOf('='); return [l.slice(0, i), l.slice(i + 1)] }))
const { chromium } = createRequire(path.join(root, 'web-console/package.json'))('@playwright/test')
const base = 'http://127.0.0.1:18081'
const results = { date: new Date().toISOString(), source: 'Real local APIs; only non-local browser requests are blocked', checks: [], errors: [], failedApis: [], externalRequests: [] }

;(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  try {
    for (const [role, email, password, destination] of [
      ['admin', env.LOCAL_ADMIN_EMAIL, env.LOCAL_ADMIN_PASSWORD, '/admin/overview'],
      ['user', 'demo@local.test', env.LOCAL_DEMO_PASSWORD, '/app/dashboard']
    ]) {
      const context = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN', colorScheme: 'light', reducedMotion: 'reduce' })
      await context.addInitScript(() => localStorage.setItem('yzh_device_id', '550e8400e29b41d4a716446655440001'))
      const page = await context.newPage()
      page.on('pageerror', e => results.errors.push({ role, message: e.message }))
      page.on('response', r => {
        const url = new URL(r.url())
        if (url.pathname.startsWith('/console/') && r.status() >= 400) results.failedApis.push({ role, path: url.pathname, status: r.status() })
      })
      await context.route('**/*', route => {
        const url = new URL(route.request().url())
        if (url.origin === base) return route.continue()
        results.externalRequests.push({ origin: url.origin, path: url.pathname }); return route.abort()
      })
      await page.goto(`${base}/login`, { waitUntil: 'networkidle' })
      if (role === 'admin') await page.screenshot({ path: path.join(output, 'login-real.png'), fullPage: true })
      await page.locator('#login-identifier').fill(email)
      await page.locator('#login-password').fill(password)
      await page.locator('[data-test="login-submit"]').click()
      await page.waitForURL(`${base}${destination}`, { timeout: 25000 })
      await page.waitForLoadState('networkidle')
      const welcome = page.getByRole('button', { name: /开始使用/ })
      if (await welcome.isVisible()) {
        await welcome.click()
        await page.waitForLoadState('networkidle')
      }
      assert.ok((await page.title()).endsWith('一加AI中台'))
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)
      assert.equal(overflow, false)
      await page.screenshot({ path: path.join(output, `${role}-real.png`), fullPage: true })
      results.checks.push({ name: `${role} browser password login and page`, passed: true, path: destination, overflow })
      await page.reload({ waitUntil: 'networkidle' })
      assert.equal(new URL(page.url()).pathname, destination)
      results.checks.push({ name: `${role} session survives reload`, passed: true })
      await context.close()
    }
    const mobile = await browser.newContext({ viewport: { width: 390, height: 844 }, locale: 'zh-CN', isMobile: true })
    const mobilePage = await mobile.newPage()
    await mobilePage.goto(`${base}/login`, { waitUntil: 'networkidle' })
    assert.equal(await mobilePage.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    await mobilePage.screenshot({ path: path.join(output, 'login-mobile-real.png'), fullPage: true })
    results.checks.push({ name: '390px mobile login layout', passed: true })
    await mobile.close()
    assert.equal(results.errors.length, 0, 'Browser page errors occurred')
    assert.equal(results.externalRequests.length, 0, 'Unexpected external browser requests occurred')
    assert.equal(results.failedApis.length, 0, 'Local API requests failed in the browser')
  } catch (e) {
    results.checks.push({ name: 'browser failure', passed: false, error: e.message }); process.exitCode = 1
  } finally {
    await browser.close()
    fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(results, null, 2))
    console.log(JSON.stringify(results, null, 2))
  }
})().catch(e => { console.error(e.message); process.exitCode = 1 })
