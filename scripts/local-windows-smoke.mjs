// Exercises the real local services. Only the model provider is WireMock.
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { resolve, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomBytes } from 'node:crypto'
import { spawnSync } from 'node:child_process'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const runtime = process.env.YIJIA_LOCAL_RUNTIME || resolve(root, '../../../yijia-local-runtime')
const envFile = join(root, 'deploy/env/.env')
const env = Object.fromEntries(readFileSync(envFile, 'utf8').split(/\r?\n/).filter(l => /^[A-Z][A-Z0-9_]*=/.test(l))
  .map(l => { const i = l.indexOf('='); return [l.slice(0, i), l.slice(i + 1)] }))
const base = 'http://127.0.0.1:18081'
const checks = []
const deviceId = '550e8400e29b41d4a716446655440000'
const userId = '71000000-0000-4000-8000-000000000001'
const model = 'multimodal-basic'
const agent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0 Safari/537.36'
const delay = ms => new Promise(r => setTimeout(r, ms))
function sql(query) {
  const r = spawnSync(join(runtime, 'tools/pgsql/bin/psql.exe'), ['-h', '127.0.0.1', '-p', '55432', '-U', 'postgres',
    '-d', 'api_model_platform', '-v', 'ON_ERROR_STOP=1', '-qtA', '-X'], {
    input: Buffer.from(query, 'utf8'), encoding: 'utf8', windowsHide: true,
    env: { ...process.env, PGPASSWORD: env.POSTGRES_PASSWORD, PGCLIENTENCODING: 'UTF8' }
  })
  if (r.status !== 0) throw new Error(`Local SQL check failed: ${r.stderr}`)
  return r.stdout.trim()
}
async function request(path, { token, method = 'GET', body, expected = 200, raw = false, attempt = 0 } = {}) {
  const r = await fetch(`${base}${path}`, {
    method, headers: { 'Content-Type': 'application/json', 'X-Device-Id': deviceId, 'User-Agent': agent,
      ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(30000)
  })
  const text = await r.text()
  if (r.status === 429 && path.startsWith('/v1/') && text.includes('rate_limit_exceeded') && attempt < 2) {
    const seconds = Number(r.headers.get('retry-after')) || 60
    console.log(`Rate limit observed; waiting ${Math.min(seconds, 60)} seconds before retrying the local API.`)
    await delay(Math.min(60000, Math.max(1000, seconds * 1000 + 250)))
    return request(path, { token, method, body, expected, raw, attempt: attempt + 1 })
  }
  if (r.status !== expected) throw new Error(`${method} ${path}: expected ${expected}, got ${r.status}: ${text.slice(0, 1000)}`)
  return raw ? text : text ? JSON.parse(text) : null
}
function passed(name, detail = {}) { checks.push({ name, passed: true, ...detail }); console.log(`PASS ${name}`) }
async function main() {
  for (const service of ['platform', 'gateway']) {
    assert.equal((await request(`/health/${service}`)).status, 'UP'); passed(`${service} readiness`)
  }
  await request('/console/platform/auth/me', { expected: 401 }); passed('Unauthenticated request denied')
  const admin = await request('/console/platform/auth/login', { method: 'POST', body: {
    identifier: env.LOCAL_ADMIN_EMAIL, password: env.LOCAL_ADMIN_PASSWORD
  } })
  assert.ok(admin.accessToken); const adminToken = admin.accessToken
  const me = await request('/console/platform/auth/me', { token: adminToken })
  assert.equal(me.email, env.LOCAL_ADMIN_EMAIL); passed('Real administrator password login', { role: me.role })
  await request('/console/platform/admin/overview', { token: adminToken }); passed('Protected platform management API')

  if (!env.LOCAL_DEMO_PASSWORD) {
    env.LOCAL_DEMO_PASSWORD = `Local!${randomBytes(12).toString('hex')}`
    writeFileSync(envFile, `${readFileSync(envFile, 'utf8').trimEnd()}\nLOCAL_DEMO_PASSWORD=${env.LOCAL_DEMO_PASSWORD}\n`)
  }
  sql(`INSERT INTO platform.app_user(id,tenant_id,email,password_hash,display_name,status)
    VALUES ('${userId}','${userId}','demo@local.test',crypt('${env.LOCAL_DEMO_PASSWORD}',gen_salt('bf',12)),'本地演示用户','ACTIVE')
    ON CONFLICT DO NOTHING;
    INSERT INTO platform.user_role(tenant_id,user_id,role_id)
    SELECT '${userId}','${userId}',id FROM platform.app_role WHERE code='USER' ON CONFLICT DO NOTHING;
    INSERT INTO platform.account_balance(id,tenant_id,user_id,currency,available_amount)
    VALUES(gen_random_uuid(),'${userId}','${userId}','CNY',0) ON CONFLICT DO NOTHING;
    INSERT INTO gateway.safety_enforcement_cache(id,tenant_id,user_id,state,reason_code,event_version,effective_at,updated_at)
    VALUES(gen_random_uuid(),'${userId}','${userId}','CLEARED','account_initialized',1,now(),now())
    ON CONFLICT (tenant_id,user_id) DO NOTHING;`)
  const user = await request('/console/platform/auth/login', { method: 'POST', body: {
    identifier: 'demo@local.test', password: env.LOCAL_DEMO_PASSWORD
  } })
  assert.ok(user.accessToken); const userToken = user.accessToken; passed('Real normal-user password login')

  let summary = await request('/console/gateway/quota/summary', { token: userToken })
  if (!summary.activePackage) {
    const plan = sql(`SELECT v.id FROM platform.package_plan_version v JOIN platform.package_plan p ON p.id=v.package_plan_id
      WHERE v.status='PUBLISHED' AND p.status='ACTIVE' AND EXISTS (
        SELECT 1 FROM platform.package_plan_version_model m WHERE m.package_plan_version_id=v.id AND m.model_code='${model}')
      ORDER BY v.price_amount,v.id LIMIT 1`)
    assert.ok(plan, `No published package contains ${model}`)
    await request('/console/platform/admin/grants', { method: 'POST', expected: 201, token: adminToken,
      body: { userId, packageVersionId: plan, reason: '本地运行验证', businessNo: 'local-windows-smoke-initial-grant-v1' } })
    for (let i = 0; i < 50; i++) {
      summary = await request('/console/gateway/quota/summary', { token: userToken })
      if (summary.activePackage?.status === 'ACTIVE') break
      await delay(1000)
    }
  }
  assert.equal(summary.activePackage?.status, 'ACTIVE'); passed('Grant delivered and package activated')
  const apiSecretFile = join(runtime, '.secrets/smoke-api-key.json')
  const manualKeys = await request('/console/gateway/api-keys', { token: userToken })
  assert.ok(Array.isArray(manualKeys))
  const eligibleModels = await request('/console/gateway/api-keys/eligible-models', { token: userToken })
  assert.ok(Array.isArray(eligibleModels) && eligibleModels.length > 0)
  passed('User API Key management and eligible models are available')
  let key
  if (existsSync(apiSecretFile)) key = JSON.parse(readFileSync(apiSecretFile, 'utf8'))
  else {
    const bootstrap = await request('/console/gateway/client/bootstrap?usageDimension=TOKEN', {
      method: 'POST', token: userToken })
    assert.equal(bootstrap.apiBaseUrl, `${base}/v1`)
    key = { secret: bootstrap.apiKey }
    assert.ok(key.secret); writeFileSync(apiSecretFile, JSON.stringify(key), { mode: 0o600 })
  }
  const token = key.secret
  const models = await request('/v1/models', { token })
  assert.ok(Array.isArray(models.data) && models.data.some(m => m.id === 'chat-standard'))
  passed('API Key and model catalog', { visibleModelIds: models.data.map(m => m.id) })
  await request('/v1/quota', { token }); passed('Live API quota')
  const before = Number(sql(`SELECT used_tokens FROM gateway.package_instance WHERE id='${summary.activePackage.id || summary.activePackage.packageInstanceId}'`))
  const response = await request('/v1/chat/completions', { method: 'POST', token,
    body: { model, messages: [{ role: 'user', content: 'Local connectivity test.' }], max_tokens: 64 } })
  assert.ok(response.choices?.[0]?.message?.content); passed('Chat completion through local WireMock', { usage: response.usage })
  const publicAlias = await request('/v1/chat/completions', { method: 'POST', token,
    body: { model: 'chat-standard', messages: [{ role: 'user', content: 'Public alias connectivity test.' }], max_tokens: 64 } })
  assert.ok(publicAlias.choices?.[0]?.message?.content); passed('Public chat-standard alias routed successfully')
  const stream = await request('/v1/chat/completions', { method: 'POST', token, raw: true,
    body: { model, stream: true, stream_options: { include_usage: true },
      messages: [{ role: 'user', content: 'Local streaming test.' }], max_tokens: 64 } })
  assert.ok(stream.includes('data:') && stream.includes('[DONE]')); passed('SSE stream completed')
  await delay(1200)
  const after = Number(sql(`SELECT used_tokens FROM gateway.package_instance WHERE id='${summary.activePackage.id || summary.activePackage.packageInstanceId}'`))
  assert.ok(after > before); passed('Usage charged in real local database', { before, after })
  const rows = JSON.parse(sql(`SELECT COALESCE(json_agg(t),'[]'::json) FROM
    (SELECT outcome,http_status,total_tokens FROM gateway.usage_log WHERE user_id='${userId}' ORDER BY created_at DESC LIMIT 2) t`))
  assert.equal(rows.length, 2); assert.ok(rows.every(r => r.outcome === 'SUCCEEDED' && r.http_status === 200))
  passed('Successful usage logs persisted', { rows })
  const form = new FormData()
  form.append('purpose', 'assistants')
  form.append('file', new Blob(['Local storage and scanner connectivity test.\n'], { type: 'text/plain' }), 'local-smoke.txt')
  const uploadedResponse = await fetch(`${base}/v1/files`, { method: 'POST',
    headers: { Authorization: `Bearer ${token}` }, body: form, signal: AbortSignal.timeout(60000) })
  assert.equal(uploadedResponse.status, 200, `File upload returned ${uploadedResponse.status}: ${(await uploadedResponse.clone().text()).slice(0, 800)}`)
  const uploaded = await uploadedResponse.json()
  assert.equal(uploaded.status, 'processed'); passed('File upload, MinIO write and ClamAV scan')
  const metadata = await request(`/v1/files/${uploaded.id}`, { token })
  assert.equal(metadata.filename, 'local-smoke.txt'); passed('Uploaded file metadata read')
  const deleted = await request(`/v1/files/${uploaded.id}`, { method: 'DELETE', token })
  assert.equal(deleted.deleted, true); passed('Uploaded test file deleted')
  console.log('Console http://127.0.0.1:18081/login; demo user demo@local.test; password is LOCAL_DEMO_PASSWORD in deploy/env/.env.')
}
try { await main() } catch (e) { checks.push({ name: 'smoke failure', passed: false, error: e.message }); console.error(e.message); process.exitCode = 1 }
writeFileSync(join(runtime, 'smoke-results.json'), JSON.stringify({ date: new Date().toISOString(),
  environment: 'Real local frontend, Java services, PostgreSQL, Garnet; model provider: WireMock', checks }, null, 2))
