// Verifies the restored page against real local services; the model provider remains WireMock.
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const { createRequire } = require('node:module')
const root = path.resolve(__dirname, '..')
const runtime = process.env.YIJIA_LOCAL_RUNTIME || path.resolve(root, '../../../yijia-local-runtime')
const output = path.join(runtime, 'api-keys-checks')
const { chromium } = createRequire(path.join(root, 'web-console/package.json'))('@playwright/test')
const base = 'http://127.0.0.1:18081'
const deviceId = '550e8400e29b41d4a716446655440013'
const env = Object.fromEntries(fs.readFileSync(path.join(root, 'deploy/env/.env'), 'utf8').split(/\r?\n/)
  .filter(l => /^[A-Z][A-Z0-9_]*=/.test(l)).map(l => { const i = l.indexOf('='); return [l.slice(0, i), l.slice(i + 1)] }))
const result = { date: new Date().toISOString(), scope: 'Real local APIs and UI; model replies use WireMock', checks: [], pageErrors: [], failedUiApis: [], externalRequests: [] }
const pass = (name, detail = {}) => { result.checks.push({ name, passed: true, ...detail }); console.log(`PASS ${name}`) }
fs.mkdirSync(output, { recursive: true })

;(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  const context = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN', reducedMotion: 'reduce' })
  let userHeaders, keyId, secret
  async function api(url, options = {}, expected = 200) {
    const r = await context.request.fetch(base + url, { ...options, timeout: 35000 })
    assert.equal(r.status(), expected, `${options.method || 'GET'} ${url}: unexpected HTTP status`)
    return r
  }
  try {
    await context.addInitScript(id => localStorage.setItem('yzh_device_id', id), deviceId)
    await context.route('**/*', route => {
      const url = new URL(route.request().url())
      if (url.origin === base) return route.continue()
      result.externalRequests.push({ origin: url.origin, path: url.pathname })
      return route.abort()
    })
    const page = await context.newPage()
    page.on('pageerror', e => result.pageErrors.push(e.message))
    page.on('response', r => {
      if (new URL(r.url()).pathname.startsWith('/console/') && r.status() >= 400)
        result.failedUiApis.push({ path: new URL(r.url()).pathname, status: r.status() })
    })
    await api('/console/gateway/api-keys', {}, 401)
    pass('Unauthenticated key management is denied')
    await page.goto(base + '/login', { waitUntil: 'networkidle' })
    await page.locator('#login-identifier').fill('demo@local.test')
    await page.locator('#login-password').fill(env.LOCAL_DEMO_PASSWORD)
    const loginPromise = page.waitForResponse(r => r.url().endsWith('/console/platform/auth/login') && r.request().method() === 'POST')
    await page.locator('[data-test=login-submit]').click()
    const login = await (await loginPromise).json()
    assert.ok(login.accessToken, 'Real login must return an access token')
    userHeaders = { Authorization: `Bearer ${login.accessToken}`, 'X-Device-Id': deviceId }
    await page.waitForURL(base + '/app/dashboard')
    const welcome = page.getByRole('button', { name: /开始使用/ })
    if (await welcome.isVisible()) await welcome.click()
    await page.locator('[data-test=desktop-nav-api-keys]').click()
    await page.waitForURL(base + '/app/api-keys')
    await page.waitForLoadState('networkidle')
    assert.equal(await page.locator('#keys-title').textContent(), 'API 密钥')
    assert.equal(await page.locator('[data-test=api-base-url]').textContent(), base + '/v1')
    pass('Real user login and desktop API key menu open the restored page')
    const existing = await (await api('/console/gateway/api-keys', { headers: userHeaders })).json()
    assert.equal(existing.length, 0, 'This local demo check requires no pre-existing self-service keys; it never removes existing keys')
    const models = await (await api('/console/gateway/api-keys/eligible-models', { headers: userHeaders })).json()
    assert.ok(models.includes('chat-standard'))
    pass('Self-service list and eligible models are available; managed keys stay separate')
    await page.locator('[data-test=create-key]').click()
    await page.locator('#key-name').fill('本地恢复验证')
    const createdPromise = page.waitForResponse(r => new URL(r.url()).pathname === '/console/gateway/api-keys' && r.request().method() === 'POST')
    await page.locator('[data-test=submit-key]').click()
    const createdResponse = await createdPromise
    assert.equal(createdResponse.status(), 201)
    const created = await createdResponse.json()
    keyId = created.id; secret = created.secret
    assert.ok(keyId && secret?.startsWith('sk-yzh-'))
    await page.locator('.secret-value code').waitFor()
    assert.ok((await page.locator('.secret-value code').textContent()) === secret)
    await page.locator('[data-test=close-secret]').click()
    await page.locator('.secret-value code').waitFor({ state: 'hidden' })
    assert.ok(!(await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }))).includes(secret))
    const listed = await (await api('/console/gateway/api-keys', { headers: userHeaders })).json()
    assert.equal(listed.length, 1); assert.ok(!JSON.stringify(listed).includes(secret))
    pass('UI creates a platform key; secret is shown once and excluded from list and browser storage')
    const keyHeaders = { Authorization: `Bearer ${secret}` }
    const catalog = await (await api('/v1/models', { headers: keyHeaders })).json()
    assert.ok(catalog.data.some(m => m.id === 'chat-standard'))
    const before = await (await api('/v1/quota', { headers: keyHeaders })).json()
    pass('Page-generated key authenticates model catalog and live quota')
    const chat = await (await api('/v1/chat/completions', { method: 'POST', headers: keyHeaders,
      data: { model: 'chat-standard', messages: [{ role: 'user', content: 'Local restored API key check.' }], max_tokens: 64 } })).json()
    assert.ok(chat.choices?.[0]?.message?.content)
    const stream = await (await api('/v1/chat/completions', { method: 'POST', headers: keyHeaders,
      data: { model: 'chat-standard', stream: true, messages: [{ role: 'user', content: 'Local restored key stream check.' }], max_tokens: 64 } })).text()
    assert.ok(stream.includes('data:') && stream.includes('[DONE]'))
    const after = await (await api('/v1/quota', { headers: keyHeaders })).json()
    assert.ok(after.usedTokens > before.usedTokens)
    pass('New key runs chat and SSE with real local quota settlement', { before: before.usedTokens, after: after.usedTokens })
    for (const [name, data, code] of [
      ['Model permissions remain enforced', { name: 'invalid model', allowedModels: ['not-authorized-model'] }, 'api_key_model_not_allowed'],
      ['Existing plan key limit remains enforced', { name: 'over limit', allowedModels: ['chat-standard'] }, 'api_key_limit_exceeded']
    ]) {
      const r = await api('/console/gateway/api-keys', { method: 'POST', headers: userHeaders, data }, 422)
      assert.equal((await r.json()).code, code); pass(name)
    }
    const reset = await api(`/console/gateway/api-keys/${keyId}/reset`, { method: 'POST', headers: userHeaders }, 422)
    assert.equal((await reset.json()).code, 'invalid_sms_proof'); pass('Reset still requires SMS proof')
    await page.reload({ waitUntil: 'networkidle' })
    assert.equal(new URL(page.url()).pathname, '/app/api-keys')
    assert.equal(await page.locator('.key-row').count(), 1)
    assert.ok(!(await page.locator('body').textContent()).includes(secret))
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    await page.screenshot({ path: path.join(output, 'desktop.png'), fullPage: true })
    pass('Reload preserves the page and key prefix without revealing the secret')
    for (const [action, label, expected] of [['disable-key', '停用', 401], ['enable-key', '启用', 200]]) {
      const responsePromise = page.waitForResponse(r => r.url().endsWith(`/api-keys/${keyId}/status`) && r.request().method() === 'PATCH')
      await page.locator(`[data-test=${action}]`).click()
      await page.locator('.el-message-box').getByRole('button', { name: label, exact: true }).click()
      assert.equal((await responsePromise).status(), 200)
      await api('/v1/models', { headers: keyHeaders }, expected)
      pass(`${label}: existing key authentication changes immediately`)
    }
    await page.setViewportSize({ width: 390, height: 844 })
    await page.locator('[data-test=open-mobile-nav]').click()
    await page.locator('[data-test=nav-api-keys]').click()
    await page.locator('.el-drawer').waitFor({ state: 'hidden' })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    await page.screenshot({ path: path.join(output, 'mobile.png'), fullPage: true })
    pass('390px mobile navigation opens the same page without horizontal overflow')
    const revokePromise = page.waitForResponse(r => r.url().endsWith(`/api-keys/${keyId}`) && r.request().method() === 'DELETE')
    await page.locator('[data-test=revoke-key]').click()
    await page.locator('.el-message-box').getByRole('button', { name: '撤销', exact: true }).click()
    assert.equal((await revokePromise).status(), 204)
    await api('/v1/models', { headers: keyHeaders }, 401)
    pass('UI revocation immediately invalidates the created key')
    keyId = null
    const managed = JSON.parse(fs.readFileSync(path.join(runtime, '.secrets/smoke-api-key.json'), 'utf8'))
    await api('/v1/models', { headers: { Authorization: `Bearer ${managed.secret}` } })
    pass('Existing managed key still works')
    assert.equal(result.pageErrors.length, 0)
    assert.equal(result.failedUiApis.length, 0)
    assert.equal(result.externalRequests.length, 0)
    pass('No browser exceptions, failed UI APIs or external requests')
  } catch (e) {
    result.checks.push({ name: 'Failure', passed: false, message: e.message }); process.exitCode = 1
  } finally {
    if (keyId && userHeaders) {
      try { await api(`/console/gateway/api-keys/${keyId}`, { method: 'DELETE', headers: userHeaders }, 204) }
      catch { result.cleanupFailed = true; process.exitCode = 1 }
    }
    await browser.close()
    fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(result, null, 2))
    console.log(JSON.stringify(result, null, 2))
  }
})().catch(e => { console.error(e.message); process.exitCode = 1 })
