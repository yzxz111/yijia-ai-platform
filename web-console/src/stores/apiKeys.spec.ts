import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ApiKeyView, CreatedApiKey, ResetApiKey } from '../api/gateway'
import { useApiKeysStore } from './apiKeys'
import { useAuthStore, type AuthenticatedUser } from './auth'

const api = vi.hoisted(() => ({
  changeApiKeyStatus: vi.fn(),
  createApiKey: vi.fn(),
  listApiKeys: vi.fn(),
  resetApiKey: vi.fn(),
  revokeApiKey: vi.fn(),
}))

vi.mock('../api/gateway', () => api)

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, reject, resolve }
}

function user(id: string, tenantId: string): AuthenticatedUser {
  return { id, tenantId, email: `${id}@example.test`, role: 'USER' }
}

function key(id: string, name: string, status: ApiKeyView['status'] = 'ACTIVE'): ApiKeyView {
  return {
    id,
    name,
    keyPrefix: `test-prefix-${id}`,
    allowedModels: ['test-model'],
    status,
    lastUsedAt: null,
    expiresAt: null,
    createdAt: '2026-08-03T00:00:00Z',
  }
}

function createdKey(id: string, name: string): CreatedApiKey {
  return {
    id,
    name,
    keyPrefix: `test-prefix-${id}`,
    allowedModels: ['test-model'],
    status: 'ACTIVE',
    expiresAt: null,
    secret: `test-secret-${id}`,
  }
}

describe('API key store identity isolation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('resets synchronously on logout and identity change but not same-identity refresh', () => {
    const auth = useAuthStore()
    const store = useApiKeysStore()
    const accountA = user('user-a', 'tenant-a')

    auth.setSession('token-a', accountA)
    store.$patch({ keys: [key('a', 'Account A')], loading: true })
    const accountAGeneration = store.userScopeGeneration
    const accountAEpoch = auth.sessionEpoch

    auth.setSession('token-a-refreshed', { ...accountA, email: 'updated@example.test' })
    expect(store.keys).toEqual([key('a', 'Account A')])
    expect(store.loading).toBe(true)
    expect(store.userScopeGeneration).toBe(accountAGeneration)
    expect(auth.sessionEpoch).toBe(accountAEpoch)

    auth.setSession('token-b', user('user-b', 'tenant-b'))
    expect(store.keys).toEqual([])
    expect(store.loading).toBe(false)
    expect(store.userScopeGeneration).toBe(accountAGeneration + 1)
    expect(auth.sessionEpoch).toBe(accountAEpoch + 1)

    store.$patch({ keys: [key('b', 'Account B')], loading: true })
    auth.clearSession()
    expect(store.keys).toEqual([])
    expect(store.loading).toBe(false)
    expect(store.userScopeGeneration).toBe(accountAGeneration + 2)
    expect(auth.sessionEpoch).toBe(accountAEpoch + 2)
  })

  it('clears the previous snapshot before an identity-B load that fails', async () => {
    const auth = useAuthStore()
    const store = useApiKeysStore()
    auth.setSession('token-a', user('user-a', 'tenant-a'))
    store.keys = [key('a', 'Account A')]

    auth.setSession('token-b', user('user-b', 'tenant-b'))
    const accountBRequest = deferred<ApiKeyView[]>()
    api.listApiKeys.mockReturnValue(accountBRequest.promise)
    const pending = store.loadKeys()

    expect(store.keys).toEqual([])
    expect(store.loading).toBe(true)
    accountBRequest.reject(new Error('account B unavailable'))
    await expect(pending).rejects.toThrow('account B unavailable')
    expect(store.keys).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('does not apply an identity-A load that resolves after identity B', async () => {
    const auth = useAuthStore()
    const store = useApiKeysStore()
    const accountARequest = deferred<ApiKeyView[]>()
    api.listApiKeys.mockReturnValue(accountARequest.promise)

    auth.setSession('token-a', user('user-a', 'tenant-a'))
    const pendingA = store.loadKeys()
    auth.setSession('token-b', user('user-b', 'tenant-b'))
    store.keys = [key('b', 'Account B')]

    accountARequest.resolve([key('a', 'Account A')])
    await pendingA
    expect(store.keys).toEqual([key('b', 'Account B')])
    expect(store.loading).toBe(false)
  })

  it('does not let an older same-identity load overwrite a newer result', async () => {
    const store = useApiKeysStore()
    const olderRequest = deferred<ApiKeyView[]>()
    const newerRequest = deferred<ApiKeyView[]>()
    api.listApiKeys
      .mockReturnValueOnce(olderRequest.promise)
      .mockReturnValueOnce(newerRequest.promise)

    const olderLoad = store.loadKeys()
    const newerLoad = store.loadKeys()
    newerRequest.resolve([key('new', 'New Snapshot')])
    await newerLoad

    olderRequest.resolve([key('old', 'Old Snapshot')])
    await olderLoad
    expect(store.keys).toEqual([key('new', 'New Snapshot')])
    expect(store.loading).toBe(false)
  })

  it('hides revoked keys from a loaded snapshot', async () => {
    const store = useApiKeysStore()
    api.listApiKeys.mockResolvedValue([
      key('active', 'Active Key'),
      key('revoked', 'Revoked Key', 'REVOKED'),
      key('disabled', 'Disabled Key', 'DISABLED'),
    ])

    await store.loadKeys()

    expect(store.keys.map((item) => item.id)).toEqual(['active', 'disabled'])
  })

  it('rejects an older same-identity failure without running its finalizer', async () => {
    const store = useApiKeysStore()
    const olderRequest = deferred<ApiKeyView[]>()
    const newerRequest = deferred<ApiKeyView[]>()
    api.listApiKeys
      .mockReturnValueOnce(olderRequest.promise)
      .mockReturnValueOnce(newerRequest.promise)

    const olderLoad = store.loadKeys()
    const newerLoad = store.loadKeys()
    olderRequest.reject(new Error('stale failure'))
    await expect(olderLoad).rejects.toThrow('stale failure')
    expect(store.keys).toEqual([])
    expect(store.loading).toBe(true)

    newerRequest.resolve([key('new', 'New Snapshot')])
    await newerLoad
    expect(store.keys).toEqual([key('new', 'New Snapshot')])
    expect(store.loading).toBe(false)
  })

  it('keeps mutation results scoped and applies reset prefixes only explicitly', async () => {
    const auth = useAuthStore()
    const store = useApiKeysStore()
    const createRequest = deferred<CreatedApiKey>()
    const revokeRequest = deferred<void>()
    const resetRequest = deferred<ResetApiKey>()
    const statusRequest = deferred<ApiKeyView>()
    api.createApiKey.mockReturnValue(createRequest.promise)
    api.revokeApiKey.mockReturnValue(revokeRequest.promise)
    api.resetApiKey.mockReturnValue(resetRequest.promise)
    api.changeApiKeyStatus.mockReturnValue(statusRequest.promise)

    auth.setSession('token-a', user('user-a', 'tenant-a'))
    store.keys = [key('shared-id', 'Account A')]
    const pendingReset = store.reset('shared-id', 'reset-proof')
    const rotated = { id: 'shared-id', keyPrefix: 'test-prefix-rotated', secret: 'test-secret-rotated' }
    resetRequest.resolve(rotated)
    await expect(pendingReset).resolves.toEqual(rotated)
    expect(store.keys[0]?.keyPrefix).toBe('test-prefix-shared-id')
    const resetPrefixStore = store as typeof store & {
      applyResetPrefix: (id: string, keyPrefix: string) => void
    }
    expect(resetPrefixStore.applyResetPrefix).toBeTypeOf('function')
    resetPrefixStore.applyResetPrefix('shared-id', rotated.keyPrefix)
    expect(store.keys[0]?.keyPrefix).toBe(rotated.keyPrefix)

    const pendingCreate = store.create('Late Account A Key', ['test-model'])
    const pendingRevoke = store.revoke('shared-id')
    const pendingStatus = store.changeStatus('shared-id', 'DISABLED')

    auth.setSession('token-b', user('user-b', 'tenant-b'))
    store.keys = [key('shared-id', 'Account B')]
    const created = createdKey('late-a', 'Late Account A Key')
    const statusUpdate = key('shared-id', 'Account A', 'DISABLED')
    createRequest.resolve(created)
    revokeRequest.resolve()
    statusRequest.resolve(statusUpdate)

    await expect(pendingCreate).resolves.toEqual(created)
    await expect(pendingRevoke).resolves.toBeUndefined()
    await expect(pendingStatus).resolves.toEqual(statusUpdate)
    expect(store.keys).toEqual([key('shared-id', 'Account B')])
  })

  it('keeps a later revoke authoritative when an older status update resolves last', async () => {
    const store = useApiKeysStore()
    const statusRequest = deferred<ApiKeyView>()
    const revokeRequest = deferred<void>()
    api.changeApiKeyStatus.mockReturnValue(statusRequest.promise)
    api.revokeApiKey.mockReturnValue(revokeRequest.promise)
    store.keys = [key('shared-id', 'Shared Key')]

    const pendingStatus = store.changeStatus('shared-id', 'DISABLED')
    const pendingRevoke = store.revoke('shared-id')
    revokeRequest.resolve()
    await pendingRevoke
    expect(store.keys).toEqual([])

    const statusUpdate = key('shared-id', 'Shared Key', 'DISABLED')
    statusRequest.resolve(statusUpdate)
    await expect(pendingStatus).resolves.toEqual(statusUpdate)
    expect(store.keys).toEqual([])
  })

  it('keeps a later status update authoritative when an older revoke resolves last', async () => {
    const store = useApiKeysStore()
    const revokeRequest = deferred<void>()
    const statusRequest = deferred<ApiKeyView>()
    api.revokeApiKey.mockReturnValue(revokeRequest.promise)
    api.changeApiKeyStatus.mockReturnValue(statusRequest.promise)
    store.keys = [key('shared-id', 'Shared Key')]

    const pendingRevoke = store.revoke('shared-id')
    const pendingStatus = store.changeStatus('shared-id', 'DISABLED')
    const statusUpdate = key('shared-id', 'Shared Key', 'DISABLED')
    statusRequest.resolve(statusUpdate)
    await expect(pendingStatus).resolves.toEqual(statusUpdate)
    expect(store.keys[0]?.status).toBe('DISABLED')

    revokeRequest.resolve()
    await pendingRevoke
    expect(store.keys[0]?.status).toBe('DISABLED')
  })
})
