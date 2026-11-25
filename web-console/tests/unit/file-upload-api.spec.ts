import { beforeEach, describe, expect, it, vi } from 'vitest'

const axiosMock = vi.hoisted(() => ({
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('axios', () => ({
  default: axiosMock,
}))

vi.mock('../../src/api/http', () => ({
  gatewayHttp: {},
}))

import { uploadFile, type UploadProgress } from '../../src/api/gateway'

const apiKey = 'sk-yzh-browser-only'
const digest = Uint8Array.from({ length: 32 }, (_, index) => index).buffer
const expectedSha256 = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')

function testFile() {
  const file = new File(['hello oss'], 'report.pdf', { type: 'application/pdf' })
  Object.defineProperty(file, 'arrayBuffer', {
    value: vi.fn().mockResolvedValue(new TextEncoder().encode('hello oss').buffer),
  })
  return file
}

function sessionResponse() {
  return {
    data: {
      file_id: 'file_123',
      upload_session_id: 'upl_123',
      status: 'pending_upload',
      upload: {
        method: 'POST',
        url: 'https://files.example-oss.com/',
        fields: {
          key: 'quarantine/tenant/file_123/report.pdf',
          policy: 'opaque-policy',
          OSSAccessKeyId: 'opaque-key-id',
          signature: 'opaque-signature',
        },
      },
      upload_expires_at: 1_786_300_000,
      file_expires_at: 1_786_386_400,
    },
  }
}

function fileView(status: 'pending_upload' | 'uploaded' | 'scanning' | 'processed' | 'rejected' | 'failed') {
  return {
    id: 'file_123',
    object: 'file' as const,
    bytes: 9,
    created_at: 1_786_000_000,
    filename: 'report.pdf',
    purpose: 'assistants',
    status,
    expires_at: 1_786_386_400,
  }
}

describe('OSS direct file upload API', () => {
  beforeEach(() => {
    vi.useRealTimers()
    axiosMock.delete.mockReset()
    axiosMock.get.mockReset()
    axiosMock.post.mockReset()
    vi.stubGlobal('crypto', {
      subtle: { digest: vi.fn().mockResolvedValue(digest) },
      randomUUID: vi.fn()
        .mockReturnValueOnce('11111111-1111-4111-8111-111111111111')
        .mockReturnValueOnce('22222222-2222-4222-8222-222222222222'),
    })
  })

  it('hashes, creates a session, uploads opaque fields in order, completes, and polls to processed', async () => {
    const progress: UploadProgress[] = []
    const file = testFile()
    axiosMock.post
      .mockResolvedValueOnce(sessionResponse())
      .mockImplementationOnce((_url, body: FormData, config) => {
        config.onUploadProgress({ loaded: 9, total: 9 })
        return Promise.resolve({ status: 204, data: undefined, config, body })
      })
      .mockResolvedValueOnce({ data: fileView('uploaded') })
    axiosMock.get
      .mockResolvedValueOnce({ data: fileView('scanning') })
      .mockResolvedValueOnce({ data: fileView('processed') })

    vi.useFakeTimers()
    const resultPromise = uploadFile(apiKey, file, (value) => progress.push(value))
    await vi.advanceTimersByTimeAsync(2_000)
    const result = await resultPromise

    expect(crypto.subtle.digest).toHaveBeenCalledTimes(1)
    expect(vi.mocked(crypto.subtle.digest).mock.calls[0][0]).toBe('SHA-256')
    expect(vi.mocked(crypto.subtle.digest).mock.calls[0][1].byteLength).toBe(9)
    expect(axiosMock.post).toHaveBeenNthCalledWith(
      1,
      '/v1/files/upload-sessions',
      {
        filename: 'report.pdf',
        bytes: 9,
        mime_type: 'application/pdf',
        sha256: expectedSha256,
        purpose: 'assistants',
      },
      {
        headers: {
          Authorization: `Bearer ${apiKey}`,
          Accept: 'application/json',
          'Idempotency-Key': '11111111-1111-4111-8111-111111111111',
        },
      },
    )

    const [uploadUrl, uploadBody, uploadConfig] = axiosMock.post.mock.calls[1]
    expect(uploadUrl).toBe('https://files.example-oss.com/')
    expect(Array.from((uploadBody as FormData).entries()).map(([name]) => name)).toEqual([
      'key',
      'policy',
      'OSSAccessKeyId',
      'signature',
      'file',
    ])
    expect(uploadConfig).not.toHaveProperty('headers')
    expect(uploadConfig).not.toHaveProperty('withCredentials')
    expect(JSON.stringify(uploadConfig)).not.toContain(apiKey)

    expect(axiosMock.post).toHaveBeenNthCalledWith(
      3,
      '/v1/files/upload-sessions/upl_123/complete',
      null,
      {
        headers: {
          Authorization: `Bearer ${apiKey}`,
          Accept: 'application/json',
          'Idempotency-Key': '22222222-2222-4222-8222-222222222222',
        },
      },
    )
    expect(axiosMock.get).toHaveBeenCalledTimes(2)
    expect(axiosMock.get).toHaveBeenCalledWith('/v1/files/file_123', {
      headers: { Authorization: `Bearer ${apiKey}`, Accept: 'application/json' },
      signal: expect.anything(),
    })
    expect(progress).toContainEqual({ phase: 'hashing', percent: 0 })
    expect(progress).toContainEqual({ phase: 'hashing', percent: 100 })
    expect(progress).toContainEqual({ phase: 'uploading', percent: 100 })
    expect(progress).toContainEqual({ phase: 'scanning', percent: 0 })
    expect(result).toEqual(fileView('processed'))
  })

  it('forwards AbortSignal only as request control and never falls back on cancellation', async () => {
    const controller = new AbortController()
    axiosMock.post
      .mockResolvedValueOnce(sessionResponse())
      .mockRejectedValueOnce(Object.assign(new Error('canceled'), { code: 'ERR_CANCELED' }))

    await expect(uploadFile(apiKey, testFile(), undefined, controller.signal)).rejects.toMatchObject({
      code: 'ERR_CANCELED',
    })

    expect(axiosMock.post.mock.calls[1][2]).toMatchObject({ signal: controller.signal })
    expect(axiosMock.post).toHaveBeenCalledTimes(2)
  })

  it('aborts session creation without continuing to the OSS upload', async () => {
    const controller = new AbortController()
    axiosMock.post.mockImplementationOnce((_url, _body, config) => new Promise((_resolve, reject) => {
      const requestSignal = config.signal as AbortSignal | undefined
      if (!requestSignal) {
        reject(new Error('missing create signal'))
        return
      }
      requestSignal.addEventListener('abort', () => reject(requestSignal.reason), { once: true })
    }))

    let rejection: unknown
    const resultPromise = uploadFile(apiKey, testFile(), undefined, controller.signal).catch((error) => {
      rejection = error
    })
    await vi.waitFor(() => expect(axiosMock.post).toHaveBeenCalledTimes(1))
    controller.abort()

    await resultPromise
    expect(rejection).toMatchObject({ name: 'AbortError' })
    expect(axiosMock.post).toHaveBeenCalledTimes(1)
    expect(axiosMock.get).not.toHaveBeenCalled()
    expect(axiosMock.delete).not.toHaveBeenCalled()
  })

  it('aborts completion without starting status polling', async () => {
    const controller = new AbortController()
    axiosMock.post
      .mockResolvedValueOnce(sessionResponse())
      .mockResolvedValueOnce({ status: 204 })
      .mockImplementationOnce((_url, _body, config) => new Promise((_resolve, reject) => {
        const requestSignal = config.signal as AbortSignal | undefined
        if (!requestSignal) {
          reject(new Error('missing complete signal'))
          return
        }
        requestSignal.addEventListener('abort', () => reject(requestSignal.reason), { once: true })
      }))

    let rejection: unknown
    const resultPromise = uploadFile(apiKey, testFile(), undefined, controller.signal).catch((error) => {
      rejection = error
    })
    await vi.waitFor(() => expect(axiosMock.post).toHaveBeenCalledTimes(3))
    controller.abort()

    await resultPromise
    expect(rejection).toMatchObject({ name: 'AbortError' })
    expect(axiosMock.post).toHaveBeenCalledTimes(3)
    expect(axiosMock.get).not.toHaveBeenCalled()
    expect(axiosMock.delete).not.toHaveBeenCalled()
  })

  it('infers text/markdown when a markdown file has an empty browser MIME type', async () => {
    const file = new File(['# title'], 'README.markdown', { type: '' })
    Object.defineProperty(file, 'arrayBuffer', {
      value: vi.fn().mockResolvedValue(new TextEncoder().encode('# title').buffer),
    })
    axiosMock.post
      .mockResolvedValueOnce(sessionResponse())
      .mockResolvedValueOnce({ status: 204 })
      .mockResolvedValueOnce({ data: fileView('processed') })

    await uploadFile(apiKey, file)

    expect(axiosMock.post.mock.calls[0][1]).toMatchObject({ mime_type: 'text/markdown' })
  })

  it.each(['rejected', 'failed'] as const)('returns the terminal %s file state', async (status) => {
    axiosMock.post
      .mockResolvedValueOnce(sessionResponse())
      .mockResolvedValueOnce({ status: 204 })
      .mockResolvedValueOnce({ data: fileView('uploaded') })
    axiosMock.get.mockResolvedValueOnce({ data: fileView(status) })

    vi.useFakeTimers()
    const resultPromise = uploadFile(apiKey, testFile())
    await vi.advanceTimersByTimeAsync(1_000)

    await expect(resultPromise).resolves.toEqual(fileView(status))
  })

  it('aborts immediately during the scanning delay without issuing a poll request', async () => {
    const controller = new AbortController()
    axiosMock.post
      .mockResolvedValueOnce(sessionResponse())
      .mockResolvedValueOnce({ status: 204 })
      .mockResolvedValueOnce({ data: fileView('uploaded') })

    vi.useFakeTimers()
    const resultPromise = uploadFile(apiKey, testFile(), (progress) => {
      if (progress.phase === 'scanning') controller.abort()
    }, controller.signal)
    const rejection = expect(resultPromise).rejects.toMatchObject({ name: 'AbortError' })
    await vi.advanceTimersByTimeAsync(1_000)

    await rejection
    expect(axiosMock.post).toHaveBeenCalledTimes(3)
    expect(axiosMock.get).not.toHaveBeenCalled()
    expect(axiosMock.delete).not.toHaveBeenCalled()
  })

  it('aborts a hung poll at the unified 30-second deadline with a stable timeout error', async () => {
    let pollingSignal: AbortSignal | undefined
    axiosMock.post
      .mockResolvedValueOnce(sessionResponse())
      .mockResolvedValueOnce({ status: 204 })
      .mockResolvedValueOnce({ data: fileView('uploaded') })
    axiosMock.get.mockImplementation((_url, config) => new Promise((_resolve, reject) => {
      pollingSignal = config.signal
      pollingSignal?.addEventListener('abort', () => reject(pollingSignal?.reason), { once: true })
    }))

    vi.useFakeTimers()
    let rejection: unknown
    const resultPromise = uploadFile(apiKey, testFile()).catch((error) => {
      rejection = error
    })
    await vi.advanceTimersByTimeAsync(30_000)
    await resultPromise

    expect(pollingSignal?.aborted).toBe(true)
    expect(rejection).toMatchObject({ name: 'FilePollingTimeoutError', code: 'file_processing_timeout' })
    expect(axiosMock.get).toHaveBeenCalledTimes(1)
    expect(axiosMock.delete).not.toHaveBeenCalled()
  })

  it('uses legacy multipart only when session creation returns direct_upload_disabled', async () => {
    axiosMock.post
      .mockRejectedValueOnce({ response: { data: { error: { code: 'direct_upload_disabled' } } } })
      .mockResolvedValueOnce({ data: fileView('processed') })

    const result = await uploadFile(apiKey, testFile())

    expect(result).toEqual(fileView('processed'))
    expect(axiosMock.post).toHaveBeenCalledTimes(2)
    const [url, body, config] = axiosMock.post.mock.calls[1]
    expect(url).toBe('/v1/files')
    expect(Array.from((body as FormData).entries()).map(([name]) => name)).toEqual(['file', 'purpose'])
    expect(config.headers).toEqual({ Authorization: `Bearer ${apiKey}`, Accept: 'application/json' })
  })

  it('does not fall back for any other session-creation error', async () => {
    const error = { response: { data: { error: { code: 'billing_source_required' } } } }
    axiosMock.post.mockRejectedValueOnce(error)

    await expect(uploadFile(apiKey, testFile())).rejects.toBe(error)
    expect(axiosMock.post).toHaveBeenCalledTimes(1)
  })
})
