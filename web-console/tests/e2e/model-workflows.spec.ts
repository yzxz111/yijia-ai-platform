import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { expect, test, type APIRequestContext, type APIResponse } from '@playwright/test'

import {
  apiKeyBearer,
  ADVANCED_PACKAGE_VERSION_ID,
  bearer,
  COMPOSE_ENV_FILE,
  expectStatus,
  provisionUserWithApiKey,
  STARTER_PACKAGE_VERSION_ID,
  type QuotaSummary,
} from './support/stack'

const COMPOSE_FILE = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../../deploy/compose.yaml',
)
const MODEL_WORKFLOW_PACKAGE = {
  packageVersionId: STARTER_PACKAGE_VERSION_ID,
  packageCode: 'STARTER' as const,
}
const ADVANCED_MODEL_WORKFLOW_PACKAGE = {
  packageVersionId: ADVANCED_PACKAGE_VERSION_ID,
  packageCode: 'ADVANCED' as const,
  apiKeyAllowedModels: ['text-pro'],
}
const ADVANCED_CONCURRENCY_LIMIT = 15
const TOKENS_PER_DELAYED_REQUEST = 30
const DOCKER_COMMAND_TIMEOUT_MS = 30_000

interface UsageEntry {
  requestId: string
  modelCode: string
  providerCode: string
  channelCode: string
  inputTokens: number
  outputTokens: number
  totalTokens: number
  usageEstimated: boolean
  stream: boolean
  httpStatus: number
  outcome: string
}

interface LedgerEntry {
  requestId: string
  entryType: string
  totalTokenDelta: number
  modelTokenDelta: number | null
  balanceAfter: number
  modelBalanceAfter: number | null
}

interface ReservationEntry {
  requestId: string
  modelCode: string
  reservedTokens: number
  actualInputTokens: number | null
  actualOutputTokens: number | null
  actualTotalTokens: number | null
  uncoveredTokens: number
  usageEstimated: boolean
  status: string
}

interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

interface WireMockRequestJournal {
  requests: Array<{
    request: {
      url: string
      body?: string
    }
  }>
}

test('settles text and SSE completions into usage, ledger, and quota', async ({ request }) => {
  test.setTimeout(60_000)

  const { apiKey, quotaSummary: initialQuota, userSession } =
    await provisionUserWithApiKey(request, MODEL_WORKFLOW_PACKAGE)
  const initialPackage = initialQuota.activePackage!

  const textResponse = await request.post('/v1/chat/completions', {
    headers: apiKeyBearer(apiKey.secret),
    data: {
      model: 'text-basic',
      stream: false,
      messages: [{ role: 'user', content: 'hello from Playwright' }],
    },
  })
  await expectStatus(textResponse, 200, 'text completion')
  const textRequestId = requiredRequestId(textResponse.headers())
  await expect(textResponse.json()).resolves.toMatchObject({
    object: 'chat.completion',
    model: 'text-basic',
    choices: [{ message: { role: 'assistant', content: 'mock response' } }],
    usage: { prompt_tokens: 10, completion_tokens: 20, total_tokens: 30 },
  })

  const textUsage = await pollUsage(request, userSession.accessToken, textRequestId)
  expect(textUsage).toMatchObject({
    requestId: textRequestId,
    modelCode: 'text-basic',
    providerCode: 'local-mock',
    channelCode: 'local-mock-primary',
    inputTokens: 10,
    outputTokens: 20,
    totalTokens: 30,
    usageEstimated: false,
    stream: false,
    httpStatus: 200,
    outcome: 'SUCCEEDED',
  })
  await expectSettledLedger(
    request,
    userSession.accessToken,
    textRequestId,
    'text-basic',
    30,
  )

  const responsesStream = await request.post('/v1/responses', {
    headers: apiKeyBearer(apiKey.secret),
    data: {
      model: 'text-basic',
      stream: true,
      input: 'Responses SSE from Playwright',
    },
  })
  await expectStatus(responsesStream, 200, 'Responses SSE completion')
  expect(responsesStream.headers()['content-type']).toContain('text/event-stream')
  const responsesRequestId = requiredRequestId(responsesStream.headers())
  const responsesBody = await responsesStream.text()
  expect(responsesBody).toContain('"type":"response.created"')
  expect(responsesBody).toContain('"type":"response.output_text.delta"')
  expect(responsesBody).toContain('mock stream response')
  expect(responsesBody).toContain('"type":"response.completed"')

  expect(await pollUsage(request, userSession.accessToken, responsesRequestId)).toMatchObject({
    requestId: responsesRequestId,
    modelCode: 'text-basic',
    channelCode: 'local-mock-primary',
    totalTokens: 30,
    usageEstimated: false,
    stream: true,
    outcome: 'SUCCEEDED',
  })
  await expectSettledLedger(
    request,
    userSession.accessToken,
    responsesRequestId,
    'text-basic',
    30,
  )

  const streamResponse = await request.post('/v1/chat/completions', {
    headers: apiKeyBearer(apiKey.secret),
    data: {
      model: 'text-basic',
      stream: true,
      stream_options: { include_usage: true },
      messages: [{ role: 'user', content: 'stream from Playwright' }],
    },
  })
  await expectStatus(streamResponse, 200, 'SSE completion')
  expect(streamResponse.headers()['content-type']).toContain('text/event-stream')
  const streamRequestId = requiredRequestId(streamResponse.headers())
  const streamBody = await streamResponse.text()
  expect(streamBody).toContain('mock stream response')
  expect(streamBody).toContain('"total_tokens":30')
  expect(streamBody).toContain('[DONE]')

  const streamUsage = await pollUsage(request, userSession.accessToken, streamRequestId)
  expect(streamUsage).toMatchObject({
    requestId: streamRequestId,
    modelCode: 'text-basic',
    providerCode: 'local-mock',
    channelCode: 'local-mock-primary',
    inputTokens: 10,
    outputTokens: 20,
    totalTokens: 30,
    usageEstimated: false,
    stream: true,
    httpStatus: 200,
    outcome: 'SUCCEEDED',
  })
  await expectSettledLedger(
    request,
    userSession.accessToken,
    streamRequestId,
    'text-basic',
    30,
  )

  const finalQuotaResponse = await request.get('/console/gateway/quota/summary', {
    headers: bearer(userSession.accessToken),
  })
  await expectStatus(finalQuotaResponse, 200, 'read quota after model calls')
  const finalQuota = await finalQuotaResponse.json() as QuotaSummary
  expect(finalQuota.activePackage).toMatchObject({
    packageInstanceId: initialPackage.packageInstanceId,
    reservedTokens: 0,
    usedTokens: initialPackage.usedTokens + 90,
    remainingTokens: initialPackage.remainingTokens - 90,
  })
})

test('processes image and uploaded file inputs through Responses API', async ({ request }) => {
  test.setTimeout(60_000)

  const { apiKey, quotaSummary: initialQuota, userSession } =
    await provisionUserWithApiKey(request, MODEL_WORKFLOW_PACKAGE)
  const initialPackage = initialQuota.activePackage!
  const png = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
    'base64',
  )

  const imageResponse = await request.post('/v1/responses', {
    headers: apiKeyBearer(apiKey.secret),
    data: {
      model: 'vision-basic',
      input: [{
        role: 'user',
        content: [
          { type: 'input_text', text: 'describe this image' },
          { type: 'input_image', image_url: `data:image/png;base64,${png.toString('base64')}` },
        ],
      }],
    },
  })
  await expectStatus(imageResponse, 200, 'image response')
  const imageRequestId = requiredRequestId(imageResponse.headers())
  await expect(imageResponse.json()).resolves.toMatchObject({
    object: 'response',
    status: 'completed',
    model: 'vision-basic',
    output: [{
      type: 'message',
      role: 'assistant',
      content: [{ type: 'output_text', text: 'mock response' }],
    }],
    usage: { input_tokens: 10, output_tokens: 20, total_tokens: 30 },
  })
  expect(await pollUsage(request, userSession.accessToken, imageRequestId)).toMatchObject({
    requestId: imageRequestId,
    modelCode: 'vision-basic',
    totalTokens: 30,
    usageEstimated: false,
    outcome: 'SUCCEEDED',
  })
  await expectSettledLedger(
    request,
    userSession.accessToken,
    imageRequestId,
    'vision-basic',
    30,
  )

  const pdf = Buffer.from('%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n', 'ascii')
  const uploadResponse = await request.post('/v1/files', {
    headers: apiKeyBearer(apiKey.secret),
    multipart: {
      purpose: 'assistants',
      file: { name: 'e2e-document.pdf', mimeType: 'application/pdf', buffer: pdf },
    },
  })
  await expectStatus(uploadResponse, 200, 'upload PDF')
  const uploadedFile = await uploadResponse.json() as {
    id: string
    object: string
    bytes: number
    filename: string
    purpose: string
    status: string
  }
  expect(uploadedFile).toMatchObject({
    id: expect.stringMatching(/^file_/),
    object: 'file',
    bytes: pdf.length,
    filename: 'e2e-document.pdf',
    purpose: 'assistants',
    status: 'processed',
  })

  const fileResponse = await request.post('/v1/responses', {
    headers: apiKeyBearer(apiKey.secret),
    data: {
      model: 'file-basic',
      input: [{
        role: 'user',
        content: [
          { type: 'input_text', text: 'summarize this document' },
          { type: 'input_file', file_id: uploadedFile.id },
        ],
      }],
    },
  })
  await expectStatus(fileResponse, 200, 'file response')
  const fileRequestId = requiredRequestId(fileResponse.headers())
  await expect(fileResponse.json()).resolves.toMatchObject({
    object: 'response',
    status: 'completed',
    model: 'file-basic',
    output: [{ content: [{ type: 'output_text', text: 'mock response' }] }],
    usage: { input_tokens: 10, output_tokens: 20, total_tokens: 30 },
  })
  expect(await pollUsage(request, userSession.accessToken, fileRequestId)).toMatchObject({
    requestId: fileRequestId,
    modelCode: 'file-basic',
    totalTokens: 30,
    usageEstimated: false,
    outcome: 'SUCCEEDED',
  })
  await expectSettledLedger(
    request,
    userSession.accessToken,
    fileRequestId,
    'file-basic',
    30,
  )

  const deleteResponse = await request.delete(`/v1/files/${uploadedFile.id}`, {
    headers: apiKeyBearer(apiKey.secret),
  })
  await expectStatus(deleteResponse, 200, 'delete uploaded file')
  await expect(deleteResponse.json()).resolves.toMatchObject({
    id: uploadedFile.id,
    object: 'file',
    deleted: true,
  })

  const finalQuotaResponse = await request.get('/console/gateway/quota/summary', {
    headers: bearer(userSession.accessToken),
  })
  await expectStatus(finalQuotaResponse, 200, 'read quota after image and file calls')
  const finalQuota = await finalQuotaResponse.json() as QuotaSummary
  expect(finalQuota.activePackage).toMatchObject({
    packageInstanceId: initialPackage.packageInstanceId,
    reservedTokens: 0,
    usedTokens: initialPackage.usedTokens + 60,
    remainingTokens: initialPackage.remainingTokens - 60,
  })
})

test('estimates missing provider usage and settles one terminal reservation', async ({ request }) => {
  test.setTimeout(60_000)

  const { adminSession, apiKey, quotaSummary: initialQuota, userSession } =
    await provisionUserWithApiKey(request, MODEL_WORKFLOW_PACKAGE)
  const initialPackage = initialQuota.activePackage!

  const response = await request.post('/v1/chat/completions', {
    headers: apiKeyBearer(apiKey.secret),
    data: {
      model: 'text-basic',
      stream: false,
      messages: [{ role: 'user', content: 'trigger-missing-usage' }],
    },
  })
  await expectStatus(response, 200, 'completion without provider usage')
  const requestId = requiredRequestId(response.headers())
  await expect(response.json()).resolves.toMatchObject({
    choices: [{ message: { content: 'mock response without usage' } }],
  })

  const usage = await pollUsage(request, userSession.accessToken, requestId)
  expect(usage).toMatchObject({
    requestId,
    channelCode: 'local-mock-primary',
    usageEstimated: true,
    stream: false,
    httpStatus: 200,
    outcome: 'SUCCEEDED',
  })
  expect(usage.totalTokens).toBeGreaterThan(0)

  const reservation = await pollReservation(
    request,
    adminSession.accessToken,
    requestId,
  )
  expect(reservation).toMatchObject({
    requestId,
    modelCode: 'text-basic',
    actualTotalTokens: usage.totalTokens,
    uncoveredTokens: 0,
    usageEstimated: true,
    status: 'SETTLED',
  })
  await expectSettledLedger(
    request,
    userSession.accessToken,
    requestId,
    'text-basic',
    usage.totalTokens,
  )

  const finalQuota = await readQuotaSummary(request, userSession.accessToken)
  expect(finalQuota.activePackage).toMatchObject({
    packageInstanceId: initialPackage.packageInstanceId,
    reservedTokens: 0,
    usedTokens: initialPackage.usedTokens + usage.totalTokens,
    remainingTokens: initialPackage.remainingTokens - usage.totalTokens,
  })
})

test('fails over before the first byte and settles the successful channel once', async ({ request }) => {
  test.setTimeout(60_000)

  const { adminSession, apiKey, quotaSummary: initialQuota, runId, userSession } =
    await provisionUserWithApiKey(request, MODEL_WORKFLOW_PACKAGE)
  const initialPackage = initialQuota.activePackage!
  const attemptMarker = `wiremock-pre-first-byte-${runId}`

  const response = await request.post('/v1/chat/completions', {
    headers: apiKeyBearer(apiKey.secret),
    data: {
      model: 'text-basic',
      stream: false,
      messages: [{
        role: 'user',
        content: `trigger-pre-first-byte-failure ${attemptMarker}`,
      }],
    },
  })
  await expectStatus(response, 200, 'completion after primary failover')
  const requestId = requiredRequestId(response.headers())
  await expect(response.json()).resolves.toMatchObject({
    choices: [{ message: { content: 'mock failover response' } }],
    usage: { prompt_tokens: 10, completion_tokens: 20, total_tokens: 30 },
  })

  expect(await pollUsage(request, userSession.accessToken, requestId)).toMatchObject({
    requestId,
    providerCode: 'local-mock',
    channelCode: 'local-mock-failover',
    totalTokens: 30,
    usageEstimated: false,
    outcome: 'SUCCEEDED',
  })
  expect(await pollReservation(request, adminSession.accessToken, requestId)).toMatchObject({
    requestId,
    actualTotalTokens: 30,
    uncoveredTokens: 0,
    usageEstimated: false,
    status: 'SETTLED',
  })
  await expectSettledLedger(
    request,
    userSession.accessToken,
    requestId,
    'text-basic',
    30,
  )
  expect(readWireMockAttemptCounts(attemptMarker)).toEqual({
    primary: 1,
    failover: 1,
  })

  const finalQuota = await readQuotaSummary(request, userSession.accessToken)
  expect(finalQuota.activePackage).toMatchObject({
    packageInstanceId: initialPackage.packageInstanceId,
    reservedTokens: 0,
    usedTokens: initialPackage.usedTokens + 30,
    remainingTokens: initialPackage.remainingTokens - 30,
  })
})

test('releases the full reservation when every channel fails before the first byte', async ({ request }) => {
  test.setTimeout(60_000)

  const { adminSession, apiKey, quotaSummary: initialQuota, runId, userSession } =
    await provisionUserWithApiKey(request, MODEL_WORKFLOW_PACKAGE)
  const initialPackage = initialQuota.activePackage!
  const attemptMarker = `wiremock-all-pre-first-byte-${runId}`

  const response = await request.post('/v1/chat/completions', {
    headers: apiKeyBearer(apiKey.secret),
    data: {
      model: 'text-basic',
      stream: false,
      messages: [{
        role: 'user',
        content: `trigger-pre-first-byte-failure trigger-all-pre-first-byte-failure ${attemptMarker}`,
      }],
    },
  })
  await expectStatus(response, 502, 'completion when every channel fails')
  const requestId = requiredRequestId(response.headers())
  await expect(response.json()).resolves.toMatchObject({
    error: { type: 'server_error' },
    request_id: requestId,
  })

  expect(await pollReservation(request, adminSession.accessToken, requestId)).toMatchObject({
    requestId,
    actualInputTokens: null,
    actualOutputTokens: null,
    actualTotalTokens: null,
    uncoveredTokens: 0,
    status: 'RELEASED',
  })
  await expectReleasedLedger(
    request,
    userSession.accessToken,
    requestId,
    'text-basic',
    initialPackage.remainingTokens,
  )
  expect(readWireMockAttemptCounts(attemptMarker)).toEqual({
    primary: 1,
    failover: 1,
  })

  expect(await readQuotaSummary(request, userSession.accessToken)).toMatchObject({
    activePackage: {
      packageInstanceId: initialPackage.packageInstanceId,
      reservedTokens: 0,
      usedTokens: initialPackage.usedTokens,
      remainingTokens: initialPackage.remainingTokens,
    },
  })
})

test('holds concurrent delayed reservations and keeps the advanced model subquota nonnegative', async ({ request }) => {
  test.setTimeout(60_000)

  const { adminSession, apiKey, quotaSummary: initialQuota, runId, userSession } =
    await provisionUserWithApiKey(request, ADVANCED_MODEL_WORKFLOW_PACKAGE)
  const initialPackage = initialQuota.activePackage!
  const initialModelQuota = initialPackage.modelQuotas.find(
    ({ modelCode }) => modelCode === 'text-pro',
  )
  expect(initialModelQuota).toBeDefined()
  const attemptMarker = `wiremock-delay-${runId}`
  const overflowMarker = `wiremock-delay-overflow-${runId}`
  const expectedTokenUsage = ADVANCED_CONCURRENCY_LIMIT * TOKENS_PER_DELAYED_REQUEST
  const startedAt = Date.now()

  const pendingResponses = Array.from({ length: ADVANCED_CONCURRENCY_LIMIT }, (_, index) =>
    request.post('/v1/chat/completions', {
      headers: apiKeyBearer(apiKey.secret),
      data: {
        model: 'text-pro',
        stream: false,
        messages: [{
          role: 'user',
          content: `trigger-delay ${attemptMarker} concurrent-${index}`,
        }],
      },
    }))

  await expect.poll(async () => countReservations(
    request,
    adminSession.accessToken,
    userSession.user.id,
    'RESERVED',
    'text-pro',
  ), {
    message: 'ADVANCED requests should occupy every package concurrency slot',
    timeout: 5_000,
    intervals: [20, 50, 100, 200],
  }).toBe(ADVANCED_CONCURRENCY_LIMIT)

  let responses: APIResponse[] = []
  try {
    const overflowResponse = await request.post('/v1/chat/completions', {
      headers: apiKeyBearer(apiKey.secret),
      data: {
        model: 'text-pro',
        stream: false,
        messages: [{
          role: 'user',
          content: `trigger-delay ${attemptMarker} ${overflowMarker}`,
        }],
      },
    })
    await expectStatus(overflowResponse, 429, 'completion above the ADVANCED concurrency limit')
    const overflowRequestId = requiredRequestId(overflowResponse.headers())
    await expect(overflowResponse.json()).resolves.toMatchObject({
      error: {
        type: 'rate_limit_error',
        code: 'rate_limit_exceeded',
      },
      request_id: overflowRequestId,
    })
    expect(readWireMockAttemptCounts(overflowMarker)).toEqual({
      primary: 0,
      failover: 0,
    })
  } finally {
    responses = await Promise.all(pendingResponses)
  }

  expect(responses).toHaveLength(ADVANCED_CONCURRENCY_LIMIT)
  expect(Date.now() - startedAt).toBeGreaterThanOrEqual(1_200)
  const requestIds: string[] = []
  for (const response of responses) {
    await expectStatus(response, 200, 'delayed concurrent completion')
    requestIds.push(requiredRequestId(response.headers()))
    await expect(response.json()).resolves.toMatchObject({
      choices: [{ message: { content: 'delayed mock response' } }],
      usage: { total_tokens: TOKENS_PER_DELAYED_REQUEST },
    })
  }
  expect(new Set(requestIds).size).toBe(ADVANCED_CONCURRENCY_LIMIT)
  expect(readWireMockAttemptCounts(attemptMarker)).toEqual({
    primary: ADVANCED_CONCURRENCY_LIMIT,
    failover: 0,
  })

  for (const requestId of requestIds) {
    expect(await pollReservation(request, adminSession.accessToken, requestId)).toMatchObject({
      requestId,
      modelCode: 'text-pro',
      actualTotalTokens: TOKENS_PER_DELAYED_REQUEST,
      uncoveredTokens: 0,
      status: 'SETTLED',
    })
    const ledger = await expectSettledLedger(
      request,
      userSession.accessToken,
      requestId,
      'text-pro',
      TOKENS_PER_DELAYED_REQUEST,
    )
    expect(ledger.every(({ modelBalanceAfter }) =>
      modelBalanceAfter !== null && modelBalanceAfter >= 0)).toBe(true)
  }

  await expect.poll(async () => countReservations(
    request,
    adminSession.accessToken,
    userSession.user.id,
    'RESERVED',
    'text-pro',
  ), {
    message: 'all accepted delayed reservations should settle',
    timeout: 10_000,
    intervals: [20, 50, 100, 200],
  }).toBe(0)

  const finalQuota = await readQuotaSummary(request, userSession.accessToken)
  const finalModelQuota = finalQuota.activePackage!.modelQuotas.find(
    ({ modelCode }) => modelCode === 'text-pro',
  )
  expect(finalQuota.activePackage).toMatchObject({
    packageInstanceId: initialPackage.packageInstanceId,
    reservedTokens: 0,
    usedTokens: initialPackage.usedTokens + expectedTokenUsage,
    remainingTokens: initialPackage.remainingTokens - expectedTokenUsage,
  })
  expect(finalModelQuota).toMatchObject({
    reservedTokens: 0,
    usedTokens: initialModelQuota!.usedTokens + expectedTokenUsage,
    remainingTokens: initialModelQuota!.remainingTokens - expectedTokenUsage,
  })
  expect(finalModelQuota!.remainingTokens).toBeGreaterThanOrEqual(0)
})

test('settles an interrupted stream without failing over after the first byte', async ({ request, page }) => {
  test.setTimeout(60_000)

  const { adminSession, apiKey, quotaSummary: initialQuota, runId, userSession } =
    await provisionUserWithApiKey(request, MODEL_WORKFLOW_PACKAGE)
  const initialPackage = initialQuota.activePackage!
  const attemptMarker = `wiremock-mid-stream-${runId}`
  await page.goto('/')

  const interrupted = await page.evaluate(async ({ attemptMarker, secret }) => {
    const response = await fetch('/v1/chat/completions', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${secret}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: 'text-basic',
        stream: true,
        stream_options: { include_usage: true },
        messages: [{
          role: 'user',
          content: `trigger-mid-stream-failure ${attemptMarker}`,
        }],
      }),
    })
    const requestId = response.headers.get('x-request-id')
    const reader = response.body!.getReader()
    const decoder = new TextDecoder()
    let body = ''
    let bodyError: string | null = null
    try {
      while (true) {
        const chunk = await reader.read()
        if (chunk.done) break
        body += decoder.decode(chunk.value, { stream: true })
      }
      body += decoder.decode()
    } catch (error) {
      bodyError = error instanceof Error ? error.message : String(error)
    }
    return { status: response.status, requestId, body, bodyError }
  }, { attemptMarker, secret: apiKey.secret })

  expect(interrupted).toMatchObject({
    status: 200,
    requestId: expect.stringMatching(/^req_[a-zA-Z0-9_-]+$/),
    bodyError: expect.any(String),
  })
  expect(interrupted.body).toContain('partial')
  expect(interrupted.body).not.toContain('mock failover response')
  const requestId = interrupted.requestId!

  const usage = await pollUsage(request, userSession.accessToken, requestId, 'FAILED')
  expect(usage).toMatchObject({
    requestId,
    channelCode: 'local-mock-primary',
    usageEstimated: true,
    stream: true,
    outcome: 'FAILED',
  })
  expect(usage.totalTokens).toBeGreaterThan(0)

  const reservation = await pollReservation(
    request,
    adminSession.accessToken,
    requestId,
  )
  expect(reservation).toMatchObject({
    requestId,
    actualTotalTokens: usage.totalTokens,
    uncoveredTokens: 0,
    usageEstimated: true,
    status: 'SETTLED',
  })
  expect(reservation.actualTotalTokens!).toBeLessThanOrEqual(reservation.reservedTokens)
  await expectSettledLedger(
    request,
    userSession.accessToken,
    requestId,
    'text-basic',
    usage.totalTokens,
  )
  expect(readWireMockAttemptCounts(attemptMarker)).toEqual({
    primary: 1,
    failover: 0,
  })

  const finalQuota = await readQuotaSummary(request, userSession.accessToken)
  expect(finalQuota.activePackage).toMatchObject({
    packageInstanceId: initialPackage.packageInstanceId,
    reservedTokens: 0,
    usedTokens: initialPackage.usedTokens + usage.totalTokens,
    remainingTokens: initialPackage.remainingTokens - usage.totalTokens,
  })
})

async function pollUsage(
  request: APIRequestContext,
  accessToken: string,
  requestId: string,
  expectedOutcome = 'SUCCEEDED',
) {
  let entry: UsageEntry | undefined
  await expect.poll(async () => {
    const response = await request.get(
      `/console/gateway/usage?requestId=${encodeURIComponent(requestId)}`,
      { headers: bearer(accessToken) },
    )
    await expectStatus(response, 200, 'poll usage log')
    const page = await response.json() as PageResult<UsageEntry>
    entry = page.items.find((item) => item.requestId === requestId)
    return entry?.outcome ?? null
  }, {
    message: `usage ${requestId} should become terminal`,
    timeout: 10_000,
    intervals: [50, 100, 250, 500],
  }).toBe(expectedOutcome)
  return entry!
}

async function pollReservation(
  request: APIRequestContext,
  adminAccessToken: string,
  requestId: string,
) {
  let reservation: ReservationEntry | undefined
  await expect.poll(async () => {
    const response = await request.get(
      `/console/gateway/admin/reservations?requestId=${encodeURIComponent(requestId)}&size=100`,
      { headers: bearer(adminAccessToken) },
    )
    await expectStatus(response, 200, 'poll terminal reservation')
    const page = await response.json() as PageResult<ReservationEntry>
    expect(page.items).toHaveLength(1)
    reservation = page.items[0]
    return reservation.status
  }, {
    message: `reservation ${requestId} should become terminal`,
    timeout: 10_000,
    intervals: [50, 100, 250, 500],
  }).toMatch(/^(SETTLED|RELEASED|EXPIRED)$/)
  return reservation!
}

async function readQuotaSummary(request: APIRequestContext, accessToken: string) {
  const response = await request.get('/console/gateway/quota/summary', {
    headers: bearer(accessToken),
  })
  await expectStatus(response, 200, 'read quota summary')
  return response.json() as Promise<QuotaSummary>
}

async function countReservations(
  request: APIRequestContext,
  adminAccessToken: string,
  userId: string,
  status: string,
  modelCode: string,
) {
  const response = await request.get(
    `/console/gateway/admin/reservations?userId=${encodeURIComponent(userId)}`
      + `&status=${encodeURIComponent(status)}&size=100`,
    { headers: bearer(adminAccessToken) },
  )
  await expectStatus(response, 200, 'count concurrent reservations')
  const page = await response.json() as PageResult<ReservationEntry>
  return page.items.filter((item) => item.modelCode === modelCode).length
}

async function expectSettledLedger(
  request: APIRequestContext,
  accessToken: string,
  requestId: string,
  modelCode: string,
  totalTokens: number,
) {
  const response = await request.get(
    `/console/gateway/quota/ledger?modelCode=${encodeURIComponent(modelCode)}&size=100`,
    { headers: bearer(accessToken) },
  )
  await expectStatus(response, 200, 'read quota ledger')
  const page = await response.json() as PageResult<LedgerEntry>
  const entries = page.items.filter((item) => item.requestId === requestId)
  expect(entries).toHaveLength(2)

  const reserve = entries.filter((item) => item.entryType === 'RESERVE')
  const settle = entries.filter((item) => item.entryType === 'SETTLE')
  expect(reserve).toHaveLength(1)
  expect(settle).toHaveLength(1)
  expect(reserve[0].totalTokenDelta).toBeLessThan(0)
  expect(settle[0].totalTokenDelta).toBeGreaterThanOrEqual(0)
  expect(entries.reduce((sum, entry) => sum + entry.totalTokenDelta, 0)).toBe(-totalTokens)
  expect(entries.every((entry) => entry.balanceAfter >= 0)).toBe(true)
  return entries
}

async function expectReleasedLedger(
  request: APIRequestContext,
  accessToken: string,
  requestId: string,
  modelCode: string,
  expectedRestoredBalance: number,
) {
  const response = await request.get(
    `/console/gateway/quota/ledger?modelCode=${encodeURIComponent(modelCode)}&size=100`,
    { headers: bearer(accessToken) },
  )
  await expectStatus(response, 200, 'read released quota ledger')
  const page = await response.json() as PageResult<LedgerEntry>
  const entries = page.items.filter((item) => item.requestId === requestId)
  expect(entries).toHaveLength(2)

  const reserve = entries.filter((item) => item.entryType === 'RESERVE')
  const release = entries.filter((item) => item.entryType === 'RELEASE')
  expect(reserve).toHaveLength(1)
  expect(release).toHaveLength(1)
  expect(reserve[0].totalTokenDelta).toBeLessThan(0)
  expect(release[0].totalTokenDelta).toBe(-reserve[0].totalTokenDelta)
  expect(entries.reduce((sum, entry) => sum + entry.totalTokenDelta, 0)).toBe(0)
  expect(release[0].balanceAfter).toBe(expectedRestoredBalance)
  expect(entries.every((entry) => entry.balanceAfter >= 0)).toBe(true)
}

function readWireMockAttemptCounts(marker: string) {
  const output = execFileSync('docker', [
    'compose', '--env-file', COMPOSE_ENV_FILE, '-f', COMPOSE_FILE,
    'exec', '-T', 'wiremock',
    'wget', '-q', '-O', '-', 'http://127.0.0.1:8080/__admin/requests',
  ], {
    encoding: 'utf8',
    maxBuffer: 10 * 1024 * 1024,
    timeout: DOCKER_COMMAND_TIMEOUT_MS,
    windowsHide: true,
  })
  const journal = JSON.parse(output) as WireMockRequestJournal
  const markedRequests = journal.requests
    .map(({ request }) => request)
    .filter(({ body }) => body?.includes(marker))

  return {
    primary: markedRequests.filter(({ url }) => url.startsWith('/mock-primary/')).length,
    failover: markedRequests.filter(({ url }) => url.startsWith('/mock-failover/')).length,
  }
}

function requiredRequestId(headers: Record<string, string>) {
  const requestId = headers['x-request-id']
  expect(requestId).toMatch(/^req_[a-zA-Z0-9_-]+$/)
  return requestId
}
