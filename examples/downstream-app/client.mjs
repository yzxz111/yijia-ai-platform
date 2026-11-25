import { setTimeout as delay } from 'node:timers/promises'
import { randomBytes } from 'node:crypto'

const retryableCodes = new Set(['rate_limit_exceeded', 'rate_limited', 'tokens_per_minute_exceeded',
  'seconds_per_minute_exceeded', 'images_per_minute_exceeded', 'edge_rate_limit_exceeded'])
const desktopAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0 Safari/537.36'

export class GatewayError extends Error {
  constructor(message, { status = 0, code = 'network_error', requestId, retryAfter } = {}) {
    super(message)
    Object.assign(this, { name: 'GatewayError', status, code, requestId, retryAfter })
  }
  toJSON() {
    return { error: { message: this.message, code: this.code }, request_id: this.requestId,
      retry_after: this.retryAfter }
  }
}

export function apiBaseUrl(value) {
  const url = new URL(value)
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw new Error('API URL must be an HTTP(S) origin or a URL ending in /v1')
  }
  const pathname = url.pathname.replace(/\/+$/, '')
  if (pathname && pathname !== '/v1') throw new Error('API URL path must be /v1 or empty')
  return `${url.origin}/v1`
}

export function retryAfterSeconds(value, now = Date.now()) {
  if (!value) return undefined
  if (/^\d+$/.test(value)) return Math.max(1, Number(value))
  const date = Date.parse(value)
  return Number.isFinite(date) ? Math.max(1, Math.ceil((date - now) / 1000)) : undefined
}

async function responseError(response) {
  let body
  try { body = await response.json() } catch { /* Edge or network intermediaries may return non-JSON. */ }
  return new GatewayError(body?.error?.message || body?.message || `HTTP ${response.status}`, {
    status: response.status, code: body?.error?.code || body?.code || 'http_error',
    requestId: response.headers.get('x-request-id') || body?.request_id,
    retryAfter: retryAfterSeconds(response.headers.get('retry-after'))
  })
}

// Frames are split after UTF-8 decoding, so a character or CRLF can span network chunks.
export async function* sseData(body, { maxFrameBytes = 1024 * 1024 } = {}) {
  if (!body) throw new GatewayError('Missing event stream', { code: 'invalid_event_stream' })
  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let pending = ''
  let frameBytes = 0
  let data = []
  let complete = false
  try {
    while (true) {
      const { value, done } = await reader.read()
      pending += decoder.decode(value, { stream: !done })
      let end
      while ((end = pending.indexOf('\n')) !== -1) {
        const line = pending.slice(0, end).replace(/\r$/, '')
        pending = pending.slice(end + 1)
        frameBytes += Buffer.byteLength(line, 'utf8')
        if (frameBytes > maxFrameBytes) throw new GatewayError('Event frame is too large', { code: 'invalid_event_stream' })
        if (line === '') {
          if (data.length) yield data.join('\n')
          data = []
          frameBytes = 0
        } else if (line.startsWith('data:')) {
          data.push(line.slice(5).replace(/^ /, ''))
        }
      }
      if (Buffer.byteLength(pending, 'utf8') + frameBytes > maxFrameBytes) {
        throw new GatewayError('Event frame is too large', { code: 'invalid_event_stream' })
      }
      if (done) {
        // SSE requires an empty line to dispatch a frame; incomplete tails are not trusted.
        complete = true
        break
      }
    }
  } finally {
    if (!complete) await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

export class GatewayClient {
  constructor({ baseUrl = 'http://127.0.0.1:18081/v1', apiKey, timeoutMs = 310000,
    maxRetries = 1, maxRetryWaitMs = 65000, fetchImpl = fetch, sleep = delay } = {}) {
    this.baseUrl = apiBaseUrl(baseUrl)
    if (typeof apiKey !== 'string' || !apiKey.trim()) throw new Error('An API key is required')
    if (!Number.isInteger(timeoutMs) || timeoutMs <= 0 || !Number.isInteger(maxRetries) ||
      maxRetries < 0 || maxRetries > 3 || !Number.isFinite(maxRetryWaitMs) || maxRetryWaitMs < 0) {
      throw new Error('Invalid timeout or retry budget')
    }
    this.apiKey = apiKey
    this.timeoutMs = timeoutMs
    this.maxRetries = maxRetries
    this.maxRetryWaitMs = maxRetryWaitMs
    this.fetch = fetchImpl
    this.sleep = sleep
  }

  static async login({ platformUrl = 'http://127.0.0.1:18081', identifier, password,
    usageDimension = 'TOKEN', deviceId = randomBytes(16).toString('hex'), fetchImpl = fetch, ...options }) {
    const origin = new URL(apiBaseUrl(platformUrl)).origin
    const headers = { 'Content-Type': 'application/json', 'X-Device-Id': deviceId, 'User-Agent': desktopAgent }
    const login = await fetchImpl(`${origin}/console/platform/auth/login`, {
      method: 'POST', headers, body: JSON.stringify({ identifier, password }), signal: AbortSignal.timeout(30000)
    })
    if (!login.ok) throw await responseError(login)
    const { accessToken } = await login.json()
    if (!accessToken) throw new GatewayError('Login did not return an access token', { code: 'invalid_login_response' })
    const bootstrap = await fetchImpl(`${origin}/console/gateway/client/bootstrap?usageDimension=${encodeURIComponent(usageDimension)}`, {
      method: 'POST', headers: { ...headers, Authorization: `Bearer ${accessToken}` }, signal: AbortSignal.timeout(30000)
    })
    if (!bootstrap.ok) throw await responseError(bootstrap)
    const result = await bootstrap.json()
    // The account server must not redirect credentials to a different host.
    const baseUrl = apiBaseUrl(result.apiBaseUrl)
    if (new URL(baseUrl).origin !== origin) throw new GatewayError('Bootstrap API origin differs from the login origin', { code: 'untrusted_api_origin' })
    return new GatewayClient({ ...options, baseUrl, apiKey: result.apiKey, fetchImpl })
  }

  async request(path, { method = 'GET', body, signal } = {}) {
    let waited = 0
    for (let attempt = 0; ; attempt++) {
      const timeout = AbortSignal.timeout(this.timeoutMs)
      const abort = signal ? AbortSignal.any([signal, timeout]) : timeout
      const response = await this.fetch(`${this.baseUrl}${path}`, {
        method, headers: { Authorization: `Bearer ${this.apiKey}`, 'Content-Type': 'application/json' },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: abort
      })
      if (response.ok) return response
      const error = await responseError(response)
      // Only a definite admission rejection is retried. Unknown 5xx/timeouts can already be billed.
      if (error.status !== 429 || !retryableCodes.has(error.code) || attempt >= this.maxRetries) throw error
      const waitMs = (error.retryAfter ?? Math.min(60, 2 ** attempt)) * 1000 + 100 + Math.floor(Math.random() * 150)
      if (waited + waitMs > this.maxRetryWaitMs) throw error
      await this.sleep(waitMs, undefined, { signal })
      waited += waitMs
    }
  }

  async models(options) { return (await this.request('/models', options)).json() }
  async quota(options) { return (await this.request('/quota', options)).json() }
  async complete(body, options) {
    return (await this.request('/chat/completions', { ...options, method: 'POST', body: { ...body, stream: false } })).json()
  }
  async *stream(body, options) {
    const response = await this.request('/chat/completions', { ...options, method: 'POST',
      body: { ...body, stream: true, stream_options: { include_usage: true } } })
    const requestId = response.headers.get('x-request-id')
    if (!response.headers.get('content-type')?.includes('text/event-stream')) {
      await response.body?.cancel()
      throw new GatewayError('Expected text/event-stream', { code: 'invalid_event_stream', requestId })
    }
    const expectedChoices = Number.isInteger(body?.n) && body.n > 0 ? body.n : 1
    const terminalReasons = new Map()
    const deliveredChoices = new Set()
    const finished = () => terminalReasons.size === expectedChoices
    let received = false
    for await (const data of sseData(response.body)) {
      if (data === '[DONE]') {
        if (!finished() && deliveredChoices.size !== expectedChoices) {
          throw new GatewayError('Stream ended without content or a finish reason', { code: 'incomplete_stream', requestId })
        }
        return
      }
      let event
      try { event = JSON.parse(data) } catch {
        throw new GatewayError('Invalid JSON in event stream', { code: 'invalid_event_stream', requestId })
      }
      if (event.error) throw new GatewayError(event.error.message || 'Stream failed', {
        code: event.error.code || 'stream_error', requestId: event.request_id || requestId
      })
      received = true
      for (const choice of event.choices || []) {
        const index = choice.index ?? 0
        if (!Number.isInteger(index) || index < 0 || index >= expectedChoices) {
          throw new GatewayError('Invalid choice index', { code: 'invalid_event_stream', requestId })
        }
        if ((typeof choice.delta?.content === 'string' && choice.delta.content.length > 0) ||
            choice.delta?.tool_calls?.length || choice.delta?.function_call) deliveredChoices.add(index)
        if (choice.finish_reason != null) terminalReasons.set(index, choice.finish_reason)
      }
      yield event
    }
    if (!received || !finished() || [...terminalReasons.values()].some(reason => reason !== 'length')) {
      throw new GatewayError('Stream closed before a terminal frame', { code: 'incomplete_stream', requestId })
    }
    // finish_reason=length is a valid truncated result even when the gateway omits [DONE].
  }
}
