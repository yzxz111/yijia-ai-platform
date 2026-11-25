// Local browser regression: real accounts and APIs, no verification provider or paid model calls.
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const { createRequire } = require('node:module')
const root = path.resolve(__dirname, '..')
const runtime = process.env.YIJIA_LOCAL_RUNTIME || path.resolve(root, '../../../yijia-local-runtime')
const output = path.join(runtime, 'direct-login-checks')
const { chromium } = createRequire(path.join(root, 'web-console/package.json'))('@playwright/test')
const env = Object.fromEntries(fs.readFileSync(path.join(root, 'deploy/env/.env'), 'utf8')
  .split(/\r?\n/).filter(l => /^[A-Z][A-Z0-9_]*=/.test(l))
  .map(l => { const i = l.indexOf('='); return [l.slice(0, i), l.slice(i + 1)] }))
const result = {
  date: new Date().toISOString(), scope: 'Real local accounts and APIs; no production access',
  checks: [], pageErrors: [], failedApis: [], verificationRequests: [], externalRequests: [],
}
const pass = name => { result.checks.push({ name, passed: true }); console.log(`PASS ${name}`) }
fs.mkdirSync(output, { recursive: true })

;(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  try {
    for (const [label, base] of [['unified', 'http://127.0.0.1:18081'], ['dev', 'http://127.0.0.1:5178']]) {
      for (const [role, email, password, destination] of [
        ['admin', env.LOCAL_ADMIN_EMAIL, env.LOCAL_ADMIN_PASSWORD, '/admin/overview'],
        ['user', 'demo@local.test', env.LOCAL_DEMO_PASSWORD, '/app/dashboard'],
      ]) {
        assert.ok(password, 'Existing local account password is required')
        const context = await browser.newContext({ viewport: { width: 1440, height: 960 },
          locale: 'zh-CN', reducedMotion: 'reduce' })
        await context.addInitScript(() => localStorage.setItem('yzh_device_id', '550e8400e29b41d4a716446655440000'))
        await context.route('**/*', route => {
          const url = new URL(route.request().url())
          if (url.origin !== base) {
            result.externalRequests.push({ origin: url.origin, path: url.pathname })
            return route.abort()
          }
          if (/\/auth\/(?:email|identity|sms)|\/captcha(?:\/|$)/.test(url.pathname)) {
            result.verificationRequests.push(url.pathname)
            return route.abort()
          }
          return route.continue()
        })
        const page = await context.newPage()
        page.on('pageerror', error => result.pageErrors.push(error.message))
        page.on('response', response => {
          const url = new URL(response.url())
          if (url.pathname.startsWith('/console/') && response.status() >= 400)
            result.failedApis.push({ path: url.pathname, status: response.status() })
        })
        await page.goto(base + '/login', { waitUntil: 'networkidle' })
        await page.locator('#login-password').waitFor({ state: 'visible' })
        for (const selector of ['[data-test=login-mode-code]', '[data-test=email-send]',
          '[data-test=sms-send]', '[data-test=login-consent]', 'a[href="/forgot-password"]']) {
          assert.equal(await page.locator(selector).count(), 0, `${selector} should be absent`)
        }
        assert.equal(await page.locator('[data-test=login-submit]').isDisabled(), true)
        if (role === 'admin') {
          await page.screenshot({ path: path.join(output, `login-${label}-desktop.png`), fullPage: true })
          pass(`${label}: password form is immediately visible without verification controls`)
        }
        await page.locator('#login-identifier').fill(email)
        await page.locator('#login-password').fill(password)
        const response = page.waitForResponse(r => r.url().endsWith('/console/platform/auth/login')
          && r.request().method() === 'POST')
        await page.locator('[data-test=login-submit]').click()
        const login = await response
        assert.equal(login.status(), 200)
        assert.ok((await login.json()).accessToken)
        await page.waitForURL(base + destination, { timeout: 25000 })
        await page.waitForLoadState('networkidle')
        pass(`${label}: real ${role} password login reaches its role-specific page`)
        await page.reload({ waitUntil: 'networkidle' })
        assert.equal(new URL(page.url()).pathname, destination)
        pass(`${label}: ${role} session survives page reload`)
        await context.close()
      }
    }
    const base = 'http://127.0.0.1:18081'
    const anonymous = await browser.newContext()
    const denied = await anonymous.request.get(base + '/console/platform/auth/me')
    assert.equal(denied.status(), 401)
    pass('Unauthenticated access remains denied')
    const wrongPassword = await anonymous.request.post(base + '/console/platform/auth/login', {
      headers: { 'X-Device-Id': '550e8400e29b41d4a716446655440000' },
      data: { identifier: 'demo@local.test', password: 'IntentionallyWrong!2026' },
    })
    assert.equal(wrongPassword.status(), 401)
    assert.equal((await wrongPassword.json()).code, 'invalid_credentials')
    assert.equal((await anonymous.request.get(base + '/console/platform/auth/me')).status(), 401)
    pass('Wrong passwords are rejected without creating an authenticated session')
    await anonymous.close()
    const mobile = await browser.newContext({ viewport: { width: 390, height: 844 },
      locale: 'zh-CN', isMobile: true, reducedMotion: 'reduce' })
    const page = await mobile.newPage()
    await page.goto(base + '/login', { waitUntil: 'networkidle' })
    assert.equal(await page.locator('#login-password').isVisible(), true)
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    await page.screenshot({ path: path.join(output, 'login-mobile.png'), fullPage: true })
    pass('390px mobile password login fits the viewport')
    await page.goto(base + '/desktop-login?oauth_transaction=invalid', { waitUntil: 'networkidle' })
    assert.equal(await page.locator('[data-test=oauth-invalid]').isVisible(), true)
    assert.equal(await page.locator('[data-test=login-submit]').count(), 0)
    pass('Expired or malformed desktop authorization stays blocked')
    await mobile.close()
    assert.equal(result.pageErrors.length, 0, 'Unexpected page errors')
    assert.equal(result.failedApis.length, 0, 'Unexpected UI API failures')
    assert.equal(result.verificationRequests.length, 0, 'Login attempted to use verification services')
    assert.equal(result.externalRequests.length, 0, 'Unexpected external requests')
    pass('Login makes zero verification or external requests and has no page/API errors')
  } catch (error) {
    result.checks.push({ name: 'browser regression', passed: false, error: error.message })
    process.exitCode = 1
  } finally {
    await browser.close()
    fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(result, null, 2))
    console.log(JSON.stringify(result, null, 2))
  }
})()
