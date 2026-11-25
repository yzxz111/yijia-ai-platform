import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { randomBytes } from 'node:crypto'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { resolve, dirname } from 'node:path'
import { GatewayClient, GatewayError, apiBaseUrl } from './client.mjs'

export async function startServer({ port = Number(process.env.PORT || 18084),
  platformUrl = process.env.YIJIA_PLATFORM_URL || 'http://127.0.0.1:18081', localDemo = false } = {}) {
  const origin = new URL(apiBaseUrl(platformUrl)).origin
  const localOrigin = ['127.0.0.1', 'localhost', '[::1]'].includes(new URL(origin).hostname)
  if (localDemo && !localOrigin) throw new Error('--local-demo only supports a loopback platform URL')
  const html = await readFile(new URL('./index.html', import.meta.url))
  const sessions = new Map()
  const ttl = 30 * 60 * 1000
  async function readJson(req) {
    let size = 0
    const chunks = []
    for await (const chunk of req) {
      size += chunk.length
      if (size > 64 * 1024) throw new GatewayError('请求内容过大', { status: 413, code: 'request_too_large' })
      chunks.push(chunk)
    }
    try { return JSON.parse(Buffer.concat(chunks).toString('utf8')) } catch {
      throw new GatewayError('请求必须为 JSON', { status: 400, code: 'invalid_json' })
    }
  }
  function json(res, status, value, extra = {}) {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...extra })
    res.end(JSON.stringify(value))
  }
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1')
    // Reject cross-site requests and DNS rebinding against this local demonstration server.
    const expectedHost = `127.0.0.1:${server.address().port}`
    if (req.headers.host !== expectedHost || (req.headers.origin && req.headers.origin !== `http://${expectedHost}`)) {
      return json(res, 403, { error: { code: 'origin_not_allowed', message: '请从本地示例地址访问' } })
    }
    const now = Date.now()
    for (const [id, session] of sessions) if (session.expires <= now && !session.busy) sessions.delete(id)
    const sid = /(?:^|;\s*)yijia_demo=([a-f0-9]{64})(?:;|$)/.exec(req.headers.cookie || '')?.[1]
    let session = sid ? sessions.get(sid) : undefined
    if (session) session.expires = now + ttl
    try {
      if (req.method === 'GET' && url.pathname === '/') {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store',
          'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
          'Content-Security-Policy': "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'" })
        return res.end(html)
      }
      if (req.method === 'GET' && url.pathname === '/api/session') {
        return json(res, 200, { loggedIn: !!session, localDemo, platformUrl: origin })
      }
      if (req.method === 'POST' && ['/api/login', '/api/demo-login'].includes(url.pathname)) {
        if (sessions.size >= 100) throw new GatewayError('示例会话过多，请稍后再试', { status: 503, code: 'demo_capacity' })
        let credentials
        if (url.pathname === '/api/demo-login') {
          if (!localDemo) throw new GatewayError('未启用本地演示登录', { status: 403, code: 'demo_disabled' })
          const envPath = resolve(dirname(fileURLToPath(import.meta.url)), '../../deploy/env/.env')
          const text = await readFile(envPath, 'utf8')
          const password = /^LOCAL_DEMO_PASSWORD=(.+)$/m.exec(text)?.[1]?.trim()
          if (!password) throw new GatewayError('本地演示账号尚未初始化，请先执行本地冒烟脚本', { status: 503, code: 'demo_not_initialized' })
          credentials = { identifier: 'demo@local.test', password }
        } else {
          credentials = await readJson(req)
          if (typeof credentials.identifier !== 'string' || typeof credentials.password !== 'string' ||
              !credentials.identifier.trim() || !credentials.password) {
            throw new GatewayError('请输入账号和密码', { status: 400, code: 'invalid_login' })
          }
        }
        const client = await GatewayClient.login({ platformUrl: origin,
          identifier: credentials.identifier, password: credentials.password })
        const id = randomBytes(32).toString('hex')
        if (sid) sessions.delete(sid)
        sessions.set(id, { client, expires: Date.now() + ttl, busy: false })
        return json(res, 200, { loggedIn: true }, {
          'Set-Cookie': `yijia_demo=${id}; HttpOnly; SameSite=Strict; Path=/; Max-Age=1800`
        })
      }
      if (!session) throw new GatewayError('请先登录', { status: 401, code: 'login_required' })
      if (req.method === 'POST' && url.pathname === '/api/logout') {
        sessions.delete(sid)
        return json(res, 200, { loggedIn: false }, { 'Set-Cookie': 'yijia_demo=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0' })
      }
      if (req.method === 'GET' && url.pathname === '/api/models') return json(res, 200, await session.client.models())
      if (req.method === 'GET' && url.pathname === '/api/quota') return json(res, 200, await session.client.quota())
      if (req.method !== 'POST' || url.pathname !== '/api/chat') {
        return json(res, 404, { error: { code: 'not_found', message: '接口不存在' } })
      }
      if (session.busy) throw new GatewayError('上一条消息仍在生成', { status: 429, code: 'demo_busy', retryAfter: 1 })
      const body = await readJson(req)
      if (typeof body.model !== 'string' || !Array.isArray(body.messages) || body.messages.length === 0) {
        throw new GatewayError('请选择模型并输入消息', { status: 400, code: 'invalid_request' })
      }
      session.busy = true
      const abort = new AbortController()
      const close = () => { if (!res.writableEnded) abort.abort() }
      res.on('close', close)
      try {
        for await (const event of session.client.stream({ model: body.model, messages: body.messages, max_tokens: 256 }, { signal: abort.signal })) {
          if (!res.headersSent) res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8',
            'Cache-Control': 'no-store', 'X-Accel-Buffering': 'no' })
          if (!res.write(`data: ${JSON.stringify(event)}\n\n`)) {
            await new Promise((resolve, reject) => {
              const cleanup = () => { res.off('drain', drain); res.off('close', closed) }
              const drain = () => { cleanup(); resolve() }
              const closed = () => { cleanup(); reject(new Error('Client disconnected')) }
              res.once('drain', drain); res.once('close', closed)
            })
          }
        }
        if (!res.headersSent) throw new GatewayError('未收到模型响应', { status: 502, code: 'empty_stream' })
        res.end('data: [DONE]\n\n')
      } finally {
        session.busy = false
        res.off('close', close)
      }
    } catch (error) {
      const safe = error instanceof GatewayError ? error : new GatewayError('连接失败或请求已中止，请检查服务状态', { status: 502 })
      if (res.destroyed) return
      if (res.headersSent) return res.end(`data: ${JSON.stringify(safe.toJSON())}\n\n`)
      const headers = safe.retryAfter ? { 'Retry-After': String(safe.retryAfter) } : {}
      json(res, safe.status || 502, safe.toJSON(), headers)
    }
  })
  server.requestTimeout = 30000
  server.headersTimeout = 15000
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve) })
  return server
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const server = await startServer({ localDemo: process.argv.includes('--local-demo') })
  console.log(`下游示例已启动：http://127.0.0.1:${server.address().port}`)
  console.log('调用凭证只保存在本进程内存；聊天会消耗所登录账号的套餐额度。')
}
