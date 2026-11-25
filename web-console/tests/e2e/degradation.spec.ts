import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { expect, test, type APIRequestContext, type APIResponse } from '@playwright/test'

import {
  apiKeyBearer,
  bearer,
  COMPOSE_ENV_FILE,
  expectStatus,
  provisionUserWithApiKey,
  registerStackUser,
  STARTER_PACKAGE_VERSION_ID,
} from './support/stack'

const COMPOSE_FILE = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../../deploy/compose.yaml',
)
const CONTROLLED_SERVICES = [
  'postgres',
  'redis',
  'minio',
  'platform-service',
  'gateway-service',
] as const
const LONG_RUNNING_SERVICES = [
  'postgres',
  'redis',
  'minio',
  'wiremock',
  'platform-service',
  'gateway-service',
  'web-console',
  'nginx',
] as const
const REDIS_DB_FALLBACK_MAX_CONCURRENCY = 2
const REDIS_REJECTION_BUDGET_MS = 6_000
const DOCKER_COMMAND_TIMEOUT_MS = 30_000
const STARTER_PACKAGE = {
  packageVersionId: STARTER_PACKAGE_VERSION_ID,
  packageCode: 'STARTER' as const,
}
const PDF = Buffer.from(
  '%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n',
  'ascii',
)

type ControlledService = typeof CONTROLLED_SERVICES[number]
type LongRunningService = typeof LONG_RUNNING_SERVICES[number]

interface ComposeServiceState {
  Health: string
  Service: string
  State: string
}

interface DeliveryFacts {
  grantCount: number
  grantStatuses: string
  outboxCount: number
  outboxStatuses: string
  packageInstanceCount: number
}

interface WireMockRequestJournal {
  requests: Array<{
    request: {
      body?: string
      url: string
    }
  }>
}

test.describe.configure({ mode: 'serial' })

test.beforeAll(async () => {
  await restartApplicationServices()
})

test.afterAll(async () => {
  await expectAllServicesHealthy()
})

test('rejects a billed request before provider dispatch while PostgreSQL is unavailable', async ({ request }) => {
  test.setTimeout(90_000)

  const { apiKey, runId, userSession } = await provisionUserWithApiKey(request)
  const marker = `postgres-down-${runId}`

  await withStoppedService(request, 'postgres', async () => {
    const response = await textCompletion(request, apiKey.secret, marker)

    await expectDependencyUnavailable(response, 'PostgreSQL-down completion')
    expect(readWireMockAttemptCounts(marker)).toEqual({ primary: 0, failover: 0 })
  }, async () => {
    await expectHttpStatus(
      request,
      '/console/gateway/quota/summary',
      bearer(userSession.accessToken),
    )
  })
})

test('uses the bounded database limit fallback and rejects limits above its Redis-down ceiling', async ({ request }) => {
  test.setTimeout(120_000)

  const lowConcurrency = await provisionUserWithApiKey(request)
  const highConcurrency = await provisionUserWithApiKey(request, STARTER_PACKAGE)
  const lowMarker = `redis-down-free-${lowConcurrency.runId}`
  const highMarker = `redis-down-starter-${highConcurrency.runId}`

  expect(1).toBeLessThanOrEqual(REDIS_DB_FALLBACK_MAX_CONCURRENCY)
  expect(3).toBeGreaterThan(REDIS_DB_FALLBACK_MAX_CONCURRENCY)

  await withStoppedService(request, 'redis', async () => {
    const modelsResponse = await request.get('/v1/models', {
      headers: apiKeyBearer(lowConcurrency.apiKey.secret),
      timeout: 20_000,
    })
    await expectStatus(modelsResponse, 200, 'API key authentication while Redis is down')

    const fallbackResponse = await textCompletion(
      request,
      lowConcurrency.apiKey.secret,
      lowMarker,
    )
    await expectStatus(fallbackResponse, 200, 'FREE completion through DB limit fallback')
    const fallbackBody = await fallbackResponse.json()
    expect(fallbackBody).toMatchObject({
      choices: [{ message: { content: expect.any(String) } }],
      usage: { total_tokens: expect.any(Number) },
    })
    expect(fallbackBody.choices[0].message.content.trim()).not.toBe('')
    expect(fallbackBody.usage.total_tokens).toBeGreaterThan(0)

    const rejectionStartedAt = Date.now()
    const rejectedResponse = await textCompletion(
      request,
      highConcurrency.apiKey.secret,
      highMarker,
    )
    const rejectionElapsedMs = Date.now() - rejectionStartedAt
    await expectDependencyUnavailable(rejectedResponse, 'STARTER completion above DB fallback ceiling')
    expect(
      rejectionElapsedMs,
      'Redis-down STARTER rejection should honor the bounded dependency timeout',
    ).toBeLessThan(REDIS_REJECTION_BUDGET_MS)
    expect(readWireMockAttemptCounts(highMarker)).toEqual({ primary: 0, failover: 0 })
  }, async () => {
    await expectHttpStatus(
      request,
      '/console/gateway/quota/summary',
      bearer(lowConcurrency.userSession.accessToken),
    )
  })
})

test('fails file upload while MinIO is unavailable but keeps text completion available', async ({ request }) => {
  test.setTimeout(120_000)

  const { apiKey, runId } = await provisionUserWithApiKey(request, STARTER_PACKAGE)
  const marker = `minio-down-text-${runId}`

  await withStoppedService(request, 'minio', async () => {
    const uploadResponse = await request.post('/v1/files', {
      headers: apiKeyBearer(apiKey.secret),
      multipart: {
        purpose: 'assistants',
        file: {
          name: `minio-down-${runId}.pdf`,
          mimeType: 'application/pdf',
          buffer: PDF,
        },
      },
      timeout: 45_000,
    })
    expect(uploadResponse.status(), `MinIO-down upload: ${await uploadResponse.text()}`)
      .toBeGreaterThanOrEqual(500)
    expect(uploadResponse.status()).toBeLessThan(600)

    const textResponse = await textCompletion(request, apiKey.secret, marker)
    await expectStatus(textResponse, 200, 'text completion while MinIO is down')
    await expect(textResponse.json()).resolves.toMatchObject({
      choices: [{ message: { content: 'mock response' } }],
    })
    expect(readWireMockAttemptCounts(marker)).toEqual({ primary: 1, failover: 0 })
  })
})

test('keeps an existing API key and package usable while platform-service is unavailable', async ({ request }) => {
  test.setTimeout(90_000)

  const { apiKey, runId } = await provisionUserWithApiKey(request, STARTER_PACKAGE)
  const marker = `platform-down-${runId}`

  await withStoppedService(request, 'platform-service', async () => {
    const response = await textCompletion(request, apiKey.secret, marker)

    await expectStatus(response, 200, 'completion while platform-service is down')
    const responseBody = await response.json()
    expect(responseBody).toMatchObject({
      choices: [{ message: { content: expect.any(String) } }],
      usage: { total_tokens: expect.any(Number) },
    })
    expect(responseBody.choices[0].message.content.trim()).not.toBe('')
    expect(responseBody.usage.total_tokens).toBeGreaterThan(0)
  })
})

test('queues a grant while gateway-service is down and delivers it exactly once after recovery', async ({ request }) => {
  test.setTimeout(120_000)

  const { adminSession, userSession } = await registerStackUser(request)
  const businessNo = `grant-degradation-${randomUUID()}`

  try {
    stopService('gateway-service')
    await expectServiceStopped('gateway-service')

    const grantResponse = await request.post('/console/platform/admin/grants', {
      headers: bearer(adminSession.accessToken),
      data: {
        userId: userSession.user.id,
        packageVersionId: STARTER_PACKAGE_VERSION_ID,
        reason: 'Playwright gateway degradation E2E',
        businessNo,
      },
    })
    await expectStatus(grantResponse, 201, 'create grant while gateway-service is down')
    await expect(grantResponse.json()).resolves.toMatchObject({
      businessNo,
      status: 'QUEUED',
    })

    await expect.poll(() => readDeliveryFacts(businessNo), {
      message: 'the unavailable gateway must leave one grant queued without delivery',
      timeout: 4_000,
      intervals: [50, 100, 250, 500],
    }).toMatchObject({
      grantCount: 1,
      grantStatuses: 'QUEUED',
      outboxCount: 1,
      outboxStatuses: expect.stringMatching(/^(PENDING|DELIVERING)$/),
      packageInstanceCount: 0,
    })

    await restoreService(request, 'gateway-service')

    await expect.poll(() => readDeliveryFacts(businessNo), {
      message: 'the recovered gateway must deliver one outbox event and create one package instance',
      timeout: 30_000,
      intervals: [100, 250, 500, 1_000],
    }).toMatchObject({
      grantCount: 1,
      outboxCount: 1,
      outboxStatuses: 'DELIVERED',
      packageInstanceCount: 1,
    })
    await expectDeliveryFactsToRemainExactlyOnce(businessNo)
  } finally {
    await restoreService(request, 'gateway-service')
  }
})

async function textCompletion(
  request: APIRequestContext,
  apiKey: string,
  marker: string,
) {
  return request.post('/v1/chat/completions', {
    headers: apiKeyBearer(apiKey),
    data: {
      model: 'text-basic',
      stream: false,
      messages: [{ role: 'user', content: marker }],
    },
    timeout: 20_000,
  })
}

async function expectDependencyUnavailable(response: APIResponse, operation: string) {
  await expectStatus(response, 503, operation)
  await expect(response.json()).resolves.toMatchObject({
    error: {
      type: 'server_error',
      code: 'dependency_unavailable',
    },
  })
}

async function withStoppedService(
  request: APIRequestContext,
  service: ControlledService,
  assertion: () => Promise<void>,
  recoveryProbe?: () => Promise<void>,
) {
  try {
    stopService(service)
    await expectServiceStopped(service)
    await assertion()
  } finally {
    await restoreService(request, service)
    await recoveryProbe?.()
  }
}

function stopService(service: ControlledService) {
  runCompose(['stop', '--timeout', '5', service])
}

async function restoreService(request: APIRequestContext, service: ControlledService) {
  runCompose(['start', service])
  await expectServiceHealthy(service)

  if (service === 'postgres') {
    await restartApplicationServices()
  } else if (service === 'redis') {
    await expectHttpReady('/health/platform')
    await expectHttpReady('/health/gateway')
  } else if (service === 'platform-service') {
    await expectHttpReady('/health/platform')
    await restartGatewayService()
  } else if (service === 'gateway-service' || service === 'minio') {
    await expectHttpReady('/health/gateway')
  }
  await expectServiceHealthy('nginx')
}

async function restartApplicationServices() {
  runCompose(['restart', 'platform-service', 'gateway-service'])
  await expectServiceHealthy('platform-service')
  await expectServiceHealthy('gateway-service')
  await expectHttpReady('/health/platform')
  await expectHttpReady('/health/gateway')
  await expectServiceHealthy('nginx')
}

async function restartGatewayService() {
  runCompose(['restart', 'gateway-service'])
  await expectServiceHealthy('gateway-service')
  await expectHttpReady('/health/gateway')
}

async function expectServiceStopped(service: ControlledService) {
  await expect.poll(() => composeServiceState(service).State, {
    message: `${service} should be stopped`,
    timeout: 15_000,
    intervals: [100, 250, 500, 1_000],
  }).toBe('exited')
}

async function expectServiceHealthy(service: LongRunningService) {
  await expect.poll(() => {
    const state = composeServiceState(service)
    return `${state.State}/${state.Health}`
  }, {
    message: `${service} should be running and healthy`,
    timeout: 60_000,
    intervals: [100, 250, 500, 1_000, 2_000],
  }).toBe('running/healthy')
}

async function expectHttpReady(route: string) {
  await expect.poll(() => {
    try {
      runCompose([
        'exec', '-T', 'nginx',
        'wget', '-q', '-O', '-', `http://127.0.0.1:8080${route}`,
      ])
      return 200
    } catch {
      return 0
    }
  }, {
    message: `${route} should report ready through Nginx`,
    timeout: 60_000,
    intervals: [100, 250, 500, 1_000, 2_000],
  }).toBe(200)
}

async function expectHttpStatus(
  request: APIRequestContext,
  route: string,
  headers?: Record<string, string>,
) {
  await expect.poll(async () => {
    try {
      return (await request.get(route, { headers, timeout: 3_000 })).status()
    } catch {
      return 0
    }
  }, {
    message: `${route} should report ready`,
    timeout: 60_000,
    intervals: [100, 250, 500, 1_000, 2_000],
  }).toBe(200)
}

async function expectAllServicesHealthy() {
  await expect.poll(() => LONG_RUNNING_SERVICES.map((service) => {
    const state = composeServiceState(service)
    return `${service}:${state.State}/${state.Health}`
  }), {
    message: 'all eight long-running Compose services should be running and healthy',
    timeout: 90_000,
    intervals: [100, 250, 500, 1_000, 2_000],
  }).toEqual(LONG_RUNNING_SERVICES.map((service) => `${service}:running/healthy`))
}

function composeServiceState(service: LongRunningService): ComposeServiceState {
  const output = runCompose(['ps', '--all', '--format', 'json', service]).trim()
  const lines = output.split(/\r?\n/).filter(Boolean)
  if (lines.length !== 1) {
    throw new Error(`expected one Compose state for ${service}, got ${lines.length}`)
  }
  return JSON.parse(lines[0]) as ComposeServiceState
}

function runCompose(args: readonly string[]) {
  // compose.yaml 用 ${VAR:?} 声明必填密钥，缺 --env-file 会整份插值失败
  return execFileSync('docker', ['compose', '--env-file', COMPOSE_ENV_FILE, '-f', COMPOSE_FILE, ...args], {
    encoding: 'utf8',
    maxBuffer: 10 * 1024 * 1024,
    timeout: DOCKER_COMMAND_TIMEOUT_MS,
    windowsHide: true,
  })
}

function readWireMockAttemptCounts(marker: string) {
  const output = runCompose([
    'exec', '-T', 'wiremock',
    'wget', '-q', '-O', '-', 'http://127.0.0.1:8080/__admin/requests',
  ])
  const journal = JSON.parse(output) as WireMockRequestJournal
  const markedRequests = journal.requests
    .map(({ request }) => request)
    .filter(({ body }) => body?.includes(marker))

  return {
    primary: markedRequests.filter(({ url }) => url.startsWith('/mock-primary/')).length,
    failover: markedRequests.filter(({ url }) => url.startsWith('/mock-failover/')).length,
  }
}

function readDeliveryFacts(businessNo: string): DeliveryFacts {
  const sql = `
    WITH matching_grant AS (
      SELECT id, status
      FROM platform.grant_request
      WHERE business_no = :'business_no'
    ), matching_outbox AS (
      SELECT event.id, event.status
      FROM platform.outbox_event event
      JOIN matching_grant grant_request ON grant_request.id = event.aggregate_id
      WHERE event.aggregate_type = 'GRANT'
        AND event.event_type = 'PACKAGE_GRANTED_V1'
        AND event.business_key = :'business_no'
    )
    SELECT
      (SELECT count(*) FROM matching_grant),
      COALESCE((SELECT string_agg(status, ',' ORDER BY status) FROM matching_grant), ''),
      (SELECT count(*) FROM matching_outbox),
      COALESCE((SELECT string_agg(status, ',' ORDER BY status) FROM matching_outbox), ''),
      (SELECT count(*)
       FROM gateway.package_instance package_instance
       JOIN matching_grant grant_request
         ON grant_request.id = package_instance.grant_request_id)
  `
  const output = execFileSync('docker', [
    'compose', '--env-file', COMPOSE_ENV_FILE, '-f', COMPOSE_FILE,
    'exec', '-T', 'postgres',
    'psql', '-U', 'postgres', '-d', 'api_model_platform',
    '-X', '-A', '-t', '-F', '|',
    '-v', 'ON_ERROR_STOP=1',
    '-v', `business_no=${businessNo}`,
    '-f', '-',
  ], {
    encoding: 'utf8',
    input: sql,
    timeout: DOCKER_COMMAND_TIMEOUT_MS,
    windowsHide: true,
  })
  const fields = output.trim().split('|')
  if (fields.length !== 5) {
    throw new Error(`unexpected grant delivery facts: ${fields.join('|')}`)
  }
  return {
    grantCount: Number.parseInt(fields[0], 10),
    grantStatuses: fields[1],
    outboxCount: Number.parseInt(fields[2], 10),
    outboxStatuses: fields[3],
    packageInstanceCount: Number.parseInt(fields[4], 10),
  }
}

async function expectDeliveryFactsToRemainExactlyOnce(businessNo: string) {
  let consecutiveMatches = 0
  await expect.poll(() => {
    const facts = readDeliveryFacts(businessNo)
    const remainsExactlyOnce = facts.grantCount === 1
      && facts.outboxCount === 1
      && facts.outboxStatuses === 'DELIVERED'
      && facts.packageInstanceCount === 1
    consecutiveMatches = remainsExactlyOnce ? consecutiveMatches + 1 : 0
    return consecutiveMatches
  }, {
    message: 'delivered grant state should remain exactly once across consecutive observations',
    timeout: 5_000,
    intervals: [100, 250, 500],
  }).toBeGreaterThanOrEqual(3)
}
