import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createApiClient } from '../../src/api/http'
import { useApiKeysStore } from '../../src/stores/apiKeys'
import { useAuthStore } from '../../src/stores/auth'

const profile = {
  id: '71000000-0000-4000-8000-000000000001',
  tenantId: '61000000-0000-4000-8000-000000000001',
  email: 'user@example.com',
  displayName: '测试用户',
  role: 'USER' as const,
}

const replacementProfile = {
  ...profile,
  id: '71000000-0000-4000-8000-000000000002',
  tenantId: '61000000-0000-4000-8000-000000000002',
  email: 'replacement@example.com',
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, reject, resolve }
}

function response(config: InternalAxiosRequestConfig, status: number): AxiosResponse {
  return {
    data: status === 200 ? { ok: true } : {},
    status,
    statusText: String(status),
    headers: {},
    config,
  }
}

function unauthorized(config: InternalAxiosRequestConfig) {
  return new AxiosError('unauthorized', 'ERR_BAD_REQUEST', config, undefined, response(config, 401))
}

describe('HTTP authentication recovery', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useAuthStore().setSession('expired-token', profile)
  })

  it('refreshes an expired heartbeat token without losing the page event sequence', async () => {
    const refresh = vi.fn().mockResolvedValue({ accessToken: 'renewed-token', expiresIn: 900, user: profile })
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
      if (adapter.mock.calls.length === 1) throw unauthorized(config)
      expect(config.headers.Authorization).toBe('Bearer renewed-token')
      expect(config.headers['X-Browser-Sequence']).toBe('8')
      return response(config, 200)
    })
    await createApiClient({ refresh }).post('/console/platform/auth/terminal-presence', null,
      { adapter, headers: { 'X-Browser-Sequence': '8' } })
    expect(refresh).toHaveBeenCalledOnce()
    expect(adapter).toHaveBeenCalledTimes(2)
  })

  it('does not retry a failed password login through session refresh', async () => {
    const refresh = vi.fn()
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => { throw unauthorized(config) })
    await expect(createApiClient({ refresh }).post('/console/platform/auth/login', {}, { adapter })).rejects.toThrow()
    expect(refresh).not.toHaveBeenCalled()
  })

  it('refreshes one time and retries the original request with the new token', async () => {
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
      if (adapter.mock.calls.length === 1) throw unauthorized(config)
      expect(config.headers.Authorization).toBe('Bearer renewed-token')
      return response(config, 200)
    })
    const refresh = vi.fn().mockResolvedValue({
      accessToken: 'renewed-token',
      expiresIn: 900,
      user: profile,
    })
    const client = createApiClient({ refresh })

    await expect(client.get('/console/gateway/usage', { adapter })).resolves.toMatchObject({ status: 200 })
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(adapter).toHaveBeenCalledTimes(2)
    expect(useAuthStore().accessToken).toBe('renewed-token')
  })

  it('does not replay a mutation when recovery switches to another identity', async () => {
    let originalError: AxiosError | undefined
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
      originalError = unauthorized(config)
      throw originalError
    })
    const refresh = vi.fn().mockResolvedValue({
      accessToken: 'replacement-token',
      expiresIn: 900,
      user: replacementProfile,
    })
    const apiKeys = useApiKeysStore()
    const originalGeneration = apiKeys.userScopeGeneration
    const client = createApiClient({ refresh })

    let rejection: unknown
    try {
      await client.post('/console/gateway/api-keys', { name: 'test-key' }, { adapter })
    } catch (error) {
      rejection = error
    }

    expect(rejection).toBe(originalError)
    expect(adapter).toHaveBeenCalledTimes(1)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(useAuthStore().user).toEqual(replacementProfile)
    expect(useAuthStore().accessToken).toBe('replacement-token')
    expect(apiKeys.userScopeGeneration).toBe(originalGeneration + 1)
  })

  it('leaves a newer identity untouched when the old recovery later rejects', async () => {
    const recovery = deferred<{ accessToken: string; expiresIn: number; user: typeof profile }>()
    let originalError: AxiosError | undefined
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
      originalError = unauthorized(config)
      throw originalError
    })
    const refresh = vi.fn(() => recovery.promise)
    const onExpired = vi.fn()
    const client = createApiClient({ refresh, onExpired })
    const request = client.post('/console/gateway/api-keys', { name: 'account-a-key' }, { adapter })
    await vi.waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))

    useAuthStore().setSession('token-b', replacementProfile)
    recovery.reject(new Error('late account A refresh failure'))

    await expect(request).rejects.toBe(originalError)
    expect(adapter).toHaveBeenCalledTimes(1)
    expect(useAuthStore().user).toEqual(replacementProfile)
    expect(useAuthStore().accessToken).toBe('token-b')
    expect(onExpired).not.toHaveBeenCalled()
  })

  it('leaves a newer identity untouched when the old recovery later succeeds', async () => {
    const recovery = deferred<{ accessToken: string; expiresIn: number; user: typeof profile }>()
    let originalError: AxiosError | undefined
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
      originalError = unauthorized(config)
      throw originalError
    })
    const refresh = vi.fn(() => recovery.promise)
    const onExpired = vi.fn()
    const client = createApiClient({ refresh, onExpired })
    const request = client.post('/console/gateway/api-keys', { name: 'account-a-key' }, { adapter })
    await vi.waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))

    useAuthStore().setSession('token-b', replacementProfile)
    recovery.resolve({ accessToken: 'late-token-a', expiresIn: 900, user: profile })

    await expect(request).rejects.toBe(originalError)
    expect(adapter).toHaveBeenCalledTimes(1)
    expect(useAuthStore().user).toEqual(replacementProfile)
    expect(useAuthStore().accessToken).toBe('token-b')
    expect(onExpired).not.toHaveBeenCalled()
  })

  it('uses a separate recovery for a newer identity while the old recovery is in flight', async () => {
    const recoveryA = deferred<{ accessToken: string; expiresIn: number; user: typeof profile }>()
    const recoveryB = deferred<{ accessToken: string; expiresIn: number; user: typeof replacementProfile }>()
    let originalErrorA: AxiosError | undefined
    const adapterA = vi.fn(async (config: InternalAxiosRequestConfig) => {
      if (adapterA.mock.calls.length === 1) {
        originalErrorA = unauthorized(config)
        throw originalErrorA
      }
      return response(config, 200)
    })
    const adapterB = vi.fn(async (config: InternalAxiosRequestConfig) => {
      if (adapterB.mock.calls.length === 1) throw unauthorized(config)
      expect(config.headers.Authorization).toBe('Bearer renewed-token-b')
      return response(config, 200)
    })
    const refreshA = vi.fn(() => recoveryA.promise)
    const refreshB = vi.fn(() => recoveryB.promise)
    const requestA = createApiClient({ refresh: refreshA }).get('/console/gateway/usage', { adapter: adapterA })
    await vi.waitFor(() => expect(refreshA).toHaveBeenCalledTimes(1))

    useAuthStore().setSession('token-b', replacementProfile)
    const requestB = createApiClient({ refresh: refreshB }).get('/console/gateway/usage', { adapter: adapterB })
    await vi.waitFor(() => expect(adapterB).toHaveBeenCalledTimes(1))
    const separateRecoveryStarted = refreshB.mock.calls.length === 1
    if (separateRecoveryStarted) {
      recoveryB.resolve({ accessToken: 'renewed-token-b', expiresIn: 900, user: replacementProfile })
    }
    recoveryA.resolve({ accessToken: 'late-token-a', expiresIn: 900, user: profile })

    const [accountAResult, accountBResult] = await Promise.allSettled([requestA, requestB])
    expect(refreshA).toHaveBeenCalledTimes(1)
    expect(refreshB).toHaveBeenCalledTimes(1)
    expect(accountAResult).toEqual({ status: 'rejected', reason: originalErrorA })
    expect(accountBResult.status).toBe('fulfilled')
    expect(adapterA).toHaveBeenCalledTimes(1)
    expect(adapterB).toHaveBeenCalledTimes(2)
    expect(useAuthStore().user).toEqual(replacementProfile)
    expect(useAuthStore().accessToken).toBe('renewed-token-b')
  })

  it('clears the session and emits expiration when refresh fails', async () => {
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
      throw unauthorized(config)
    })
    const refresh = vi.fn().mockRejectedValue(new Error('refresh failed'))
    const onExpired = vi.fn()
    const client = createApiClient({ refresh, onExpired })

    await expect(client.get('/console/gateway/usage', { adapter })).rejects.toBeInstanceOf(Error)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(adapter).toHaveBeenCalledTimes(1)
    expect(useAuthStore().accessToken).toBeNull()
    expect(onExpired).toHaveBeenCalledTimes(1)
  })

  it('shares one refresh across platform and gateway clients', async () => {
    let initialAttempts = 0
    let releaseInitial!: () => void
    const initialBarrier = new Promise<void>((resolve) => { releaseInitial = resolve })
    let resolveRefresh!: (session: { accessToken: string; expiresIn: number; user: typeof profile }) => void
    const refreshResult = new Promise<{ accessToken: string; expiresIn: number; user: typeof profile }>(
      (resolve) => { resolveRefresh = resolve },
    )
    const refresh = vi.fn(() => refreshResult)
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
      if (config.headers.Authorization === 'Bearer renewed-token') return response(config, 200)
      initialAttempts += 1
      if (initialAttempts === 2) releaseInitial()
      await initialBarrier
      throw unauthorized(config)
    })
    const platformClient = createApiClient({ refresh })
    const gatewayClient = createApiClient({ refresh })

    const requests = Promise.all([
      platformClient.get('/console/platform/profile', { adapter }),
      gatewayClient.get('/console/gateway/usage', { adapter }),
    ])
    await vi.waitFor(() => {
      expect(initialAttempts).toBe(2)
      expect(refresh).toHaveBeenCalledTimes(1)
    })
    resolveRefresh({ accessToken: 'renewed-token', expiresIn: 900, user: profile })

    await expect(requests).resolves.toHaveLength(2)
    expect(adapter).toHaveBeenCalledTimes(4)
  })
})
