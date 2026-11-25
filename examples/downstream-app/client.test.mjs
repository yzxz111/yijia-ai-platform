import test from 'node:test'
import assert from 'node:assert/strict'
import { GatewayClient, GatewayError, apiBaseUrl, retryAfterSeconds, sseData } from './client.mjs'

const key = 'test-only-never-a-real-key'
const json = (body, status = 200, headers = {}) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json', ...headers }
})
const frame = data => `data: ${typeof data === 'string' ? data : JSON.stringify(data)}\n\n`
const chunk = (content, finish_reason = null) => ({ choices: [{ delta: { content }, finish_reason }] })
const stream = text => new Response(text, { headers: { 'Content-Type': 'text/event-stream', 'X-Request-ID': 'req_test' } })
const collect = async iterable => { const all = []; for await (const item of iterable) all.push(item); return all }

test('base URL normalization never repeats /v1 or accepts unsafe URL components', () => {
  for (const url of ['http://localhost:18081', 'http://localhost:18081/', 'http://localhost:18081/v1', 'http://localhost:18081/v1/']) {
    assert.equal(apiBaseUrl(url), 'http://localhost:18081/v1')
  }
  for (const url of ['ftp://example.test', 'https://user:secret@example.test', 'https://example.test/v1?x=1', 'https://example.test/path']) {
    assert.throws(() => apiBaseUrl(url))
  }
})

test('Retry-After supports seconds and HTTP dates', () => {
  assert.equal(retryAfterSeconds('0'), 1)
  assert.equal(retryAfterSeconds('60'), 60)
  assert.equal(retryAfterSeconds('Tue, 24 Nov 2026 16:01:00 GMT', Date.parse('2026-11-24T16:00:00Z')), 60)
  assert.equal(retryAfterSeconds('unknown'), undefined)
})

test('login and bootstrap use a single stable device id and keep the API key server-side', async () => {
  const seen = []
  const client = await GatewayClient.login({ identifier: 'demo@local.test', password: 'test-password',
    fetchImpl: async (url, options) => {
      seen.push({ url, options })
      return seen.length === 1 ? json({ accessToken: 'test-jwt' }) : json({ apiKey: key, apiBaseUrl: 'http://127.0.0.1:18081/v1' })
    } })
  assert.equal(client.apiKey, key)
  assert.match(seen[0].options.headers['X-Device-Id'], /^[0-9a-f]{32}$/)
  assert.equal(seen[0].options.headers['X-Device-Id'], seen[1].options.headers['X-Device-Id'])
  assert.equal(seen[1].options.headers.Authorization, 'Bearer test-jwt')
  assert.ok(seen[1].url.endsWith('/bootstrap?usageDimension=TOKEN'))
})

test('bootstrap cannot send the acquired key to a different origin', async () => {
  let calls = 0
  await assert.rejects(() => GatewayClient.login({ identifier: 'demo', password: 'test', fetchImpl: async () => {
    calls++
    return calls === 1 ? json({ accessToken: 'test-jwt' }) : json({ apiKey: key, apiBaseUrl: 'https://other.test/v1' })
  } }), error => error.code === 'untrusted_api_origin')
  assert.equal(calls, 2)
})

test('an admission 429 obeys Retry-After and retries only within the configured budget', async () => {
  let calls = 0
  const waits = []
  const client = new GatewayClient({ apiKey: key, fetchImpl: async () => ++calls === 1
    ? json({ error: { code: 'rate_limit_exceeded', message: 'Rate limit exceeded' } }, 429, { 'Retry-After': '60' })
    : json({ data: [] }), sleep: async ms => { waits.push(ms) } })
  assert.deepEqual(await client.models(), { data: [] })
  assert.equal(calls, 2)
  assert.ok(waits[0] >= 60100 && waits[0] <= 60250)
})

for (const [status, code] of [[429, 'daily_request_limit_exceeded'], [429, 'provider_error'], [401, 'invalid_api_key'], [503, 'upstream_unavailable']]) {
  test(`${code} is returned without an automatic replay`, async () => {
    let calls = 0
    const client = new GatewayClient({ apiKey: key, fetchImpl: async () => {
      calls++
      return json({ error: { code, message: 'controlled error' } }, status, { 'X-Request-ID': 'req_failed', 'Retry-After': '3600' })
    } })
    await assert.rejects(() => client.complete({ model: 'chat-standard', messages: [] }), error =>
      error instanceof GatewayError && error.code === code && error.requestId === 'req_failed')
    assert.equal(calls, 1)
  })
}

test('oversized retry delay is reported without sleeping or a premature retry', async () => {
  const client = new GatewayClient({ apiKey: key, maxRetryWaitMs: 1000,
    fetchImpl: async () => json({ error: { code: 'rate_limit_exceeded' } }, 429, { 'Retry-After': '60' }),
    sleep: () => assert.fail('must not sleep') })
  await assert.rejects(() => client.models(), error => error.retryAfter === 60)
})

test('transport errors are not automatically replayed', async () => {
  let calls = 0
  const client = new GatewayClient({ apiKey: key, fetchImpl: async () => { calls++; throw new Error('connection lost') } })
  await assert.rejects(() => client.complete({}), /connection lost/)
  assert.equal(calls, 1)
})

test('SSE handles split UTF-8, CRLF, comments and multiline data', async () => {
  const text = ': heartbeat\r\ndata: {"text":\r\ndata: "中文"}\r\n\r\n'
  const bytes = new TextEncoder().encode(text)
  const body = new ReadableStream({ start(controller) {
    for (const byte of bytes) controller.enqueue(new Uint8Array([byte]))
    controller.close()
  } })
  const data = await collect(sseData(body))
  assert.deepEqual(JSON.parse(data[0]), { text: '中文' })
})

test('normal stream accepts finish reason, usage-only frame and DONE', async () => {
  const client = new GatewayClient({ apiKey: key, fetchImpl: async () => stream(
    frame(chunk('你好')) + frame(chunk('', 'stop')) + frame({ choices: [], usage: { total_tokens: 2 } }) + frame('[DONE]')) })
  const events = await collect(client.stream({ model: 'chat-standard' }))
  assert.equal(events.length, 3)
  assert.equal(events[2].usage.total_tokens, 2)
})

test('output truncation with finish_reason=length does not require DONE', async () => {
  const client = new GatewayClient({ apiKey: key, fetchImpl: async () => stream(frame(chunk('part', 'length'))) })
  assert.equal((await collect(client.stream({})))[0].choices[0].finish_reason, 'length')
})

test('gateway-compatible content followed by DONE succeeds without a separate finish_reason frame', async () => {
  const client = new GatewayClient({ apiKey: key, fetchImpl: async () => stream(
    frame(chunk('mock stream response')) + frame({ choices: [], usage: { total_tokens: 30 } }) + frame('[DONE]')) })
  assert.equal((await collect(client.stream({})))[0].choices[0].delta.content, 'mock stream response')
})

test('a mid-stream error propagates its code and is never replayed', async () => {
  let calls = 0
  const client = new GatewayClient({ apiKey: key, fetchImpl: async () => {
    calls++
    return stream(frame(chunk('part')) + frame({ error: { code: 'upstream_error', message: 'stream failed' }, request_id: 'req_mid' }))
  } })
  await assert.rejects(() => collect(client.stream({})), error => error.code === 'upstream_error' && error.requestId === 'req_mid')
  assert.equal(calls, 1)
})

for (const text of ['', frame(chunk('unfinished')), frame(chunk('', 'stop')), frame('[DONE]'), 'data: {"choices":[]}', frame(chunk('partial')) + 'data: invalid\n\n']) {
  test(`an incomplete or malformed stream is not reported as successful: ${text.slice(0, 30)}`, async () => {
    const client = new GatewayClient({ apiKey: key, fetchImpl: async () => stream(text) })
    await assert.rejects(() => collect(client.stream({})), error => ['invalid_event_stream', 'incomplete_stream'].includes(error.code))
  })
}

test('every requested choice must reach its terminal reason', async () => {
  const client = new GatewayClient({ apiKey: key, fetchImpl: async () => stream(
    frame({ choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] }) + frame('[DONE]')) })
  await assert.rejects(() => collect(client.stream({ n: 2 })), error => error.code === 'incomplete_stream')
})

test('an oversized SSE frame is rejected', async () => {
  const body = new Response('data: 123456789\n\n').body
  await assert.rejects(() => collect(sseData(body, { maxFrameBytes: 8 })), error => error.code === 'invalid_event_stream')
})

test('caller cancellation reaches the HTTP request', async () => {
  const abort = new AbortController()
  abort.abort()
  const client = new GatewayClient({ apiKey: key, fetchImpl: async (_url, { signal }) => signal.throwIfAborted() })
  await assert.rejects(() => client.models({ signal: abort.signal }), error => error.name === 'AbortError')
})
