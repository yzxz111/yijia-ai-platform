// Local-only verification. Real gateway/storage/counters; model requests use the existing local WireMock.
import assert from 'node:assert/strict'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { dirname, resolve, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const runtime = process.env.YIJIA_LOCAL_RUNTIME || resolve(root, '../../../yijia-local-runtime')
const base = 'http://127.0.0.1:18081'
const app = 'http://127.0.0.1:18084'
const checks = []
const passed = (name, detail = {}) => { checks.push({ name, passed: true, ...detail }); console.log(`PASS ${name}`) }
async function request(url, options = {}) {
  const response = await fetch(url, { ...options, signal: AbortSignal.timeout(30000) })
  const text = await response.text()
  let body
  try { body = JSON.parse(text) } catch { body = undefined }
  return { status: response.status, headers: response.headers, body, text }
}

try {
  for (const service of ['platform', 'gateway']) {
    const r = await request(`${base}/health/${service}`)
    assert.equal(r.status, 200); assert.equal(r.body.status, 'UP')
    passed(`${service} readiness`)
  }
  const denied = await request(`${base}/v1/models`)
  assert.equal(denied.status, 401)
  assert.equal(denied.body.error.code, 'invalid_api_key')
  assert.equal(denied.headers.get('x-request-id'), denied.body.request_id)
  passed('Unauthenticated model request keeps a traceable JSON 401')

  const key = JSON.parse(await readFile(join(runtime, '.secrets/smoke-api-key.json'), 'utf8')).secret
  assert.ok(key)
  const headers = { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' }
  const models = await request(`${base}/v1/models`, { headers })
  assert.equal(models.status, 200)
  assert.ok(models.body.data.some(m => m.id === 'chat-standard'))
  passed('Managed API key model catalog', { models: models.body.data.map(m => m.id) })

  const before = await request(`${base}/v1/quota`, { headers })
  assert.equal(before.status, 200)
  assert.equal(before.headers.get('cache-control'), 'no-store')
  passed('Real-time quota is explicitly not cached', { usedTokens: before.body.usedTokens })

  const burst = await Promise.all(Array.from({ length: 6 }, () => request(`${base}/v1/chat/completions`, {
    method: 'POST', headers, body: JSON.stringify({ model: 'chat-standard',
      messages: [{ role: 'user', content: 'Local admission verification.' }], max_tokens: 64 })
  })))
  assert.ok(burst.every(r => [200, 429].includes(r.status)))
  const rejected = burst.filter(r => r.status === 429)
  assert.ok(rejected.length > 0, 'Expected a real admission rejection for the local free plan')
  for (const r of rejected) {
    assert.equal(r.body.error.type, 'rate_limit_error')
    assert.ok(Number(r.headers.get('retry-after')) > 0)
    assert.equal(r.body.request_id, r.headers.get('x-request-id'))
    assert.equal(r.headers.get('cache-control'), 'no-store')
  }
  passed('Real gateway admission 429 includes code, Retry-After, no-store and request ID', {
    statuses: burst.map(r => r.status), codes: [...new Set(rejected.map(r => r.body.error.code))],
    retryAfter: [...new Set(rejected.map(r => r.headers.get('retry-after')))]
  })
  const after = await request(`${base}/v1/quota`, { headers })
  assert.equal(after.status, 200)
  assert.ok(after.body.usedTokens >= before.body.usedTokens)
  passed('Quota remains readable after rejected traffic', { before: before.body.usedTokens, after: after.body.usedTokens })

  const page = await request(app)
  assert.equal(page.status, 200); assert.ok(page.text.includes('下游接入示例'))
  assert.ok(!page.text.includes(key))
  assert.equal((await request(`${app}/api/quota`)).status, 401)
  assert.equal((await request(`${app}/api/session`, { headers: { Origin: 'https://untrusted.example' } })).status, 403)
  passed('Downstream page serves no API key and rejects unauthenticated or cross-origin API access')

  const spec = JSON.parse(await readFile(join(root, 'docs/downstream-openapi.json'), 'utf8'))
  assert.equal(spec.openapi, '3.1.0')
  for (const route of ['/console/platform/auth/login', '/console/gateway/client/bootstrap', '/v1/models', '/v1/quota', '/v1/chat/completions']) {
    assert.ok(spec.paths[route])
  }
  const refs = JSON.stringify(spec).matchAll(/"\$ref":"#\/components\/schemas\/([^"/]+)"/g)
  for (const [, name] of refs) assert.ok(spec.components.schemas[name], `Missing schema ${name}`)
  passed('Minimal OpenAPI contract parses and all local schema references resolve')

  // Unauthenticated catalog probes exercise only the edge limiter, never model generation.
  let edge = []
  for (let batch = 0; batch < 5; batch++) {
    edge.push(...await Promise.all(Array.from({ length: 24 }, () => request(`${base}/v1/models`))))
  }
  const edgeRejected = edge.filter(r => r.status === 429)
  assert.ok(edgeRejected.length > 0, 'Expected Nginx to reject the bounded local probe burst')
  for (const r of edgeRejected) {
    assert.ok(r.headers.get('content-type')?.includes('application/json'))
    assert.equal(r.body.error.code, 'edge_rate_limit_exceeded')
    assert.equal(r.headers.get('retry-after'), '1')
    assert.equal(r.body.request_id, r.headers.get('x-request-id'))
  }
  assert.ok(edge.filter(r => r.status !== 429).every(r => r.status === 401))
  passed('Real Nginx 429 uses JSON and preserves upstream 401', {
    probes: edge.length, rejected: edgeRejected.length, retryAfter: '1'
  })
  await delay(3500)
  assert.equal((await request(`${base}/health/gateway`)).body.status, 'UP')
  passed('Gateway stays healthy after bounded edge traffic')
} catch (error) {
  checks.push({ name: 'smoke failure', passed: false, error: error.message })
  console.error(error.message)
  process.exitCode = 1
} finally {
  await mkdir(join(runtime, 'downstream-checks'), { recursive: true })
  await writeFile(join(runtime, 'downstream-checks/api-results.json'), JSON.stringify({
    date: new Date().toISOString(), environment: 'Local only; model provider: WireMock', checks
  }, null, 2))
}
