import { beforeEach, describe, expect, it, vi } from 'vitest'

describe('device ID', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetModules()
  })

  it('creates and persists a cryptographically random 128-bit identifier', async () => {
    const { getDeviceId } = await import('./deviceId')

    const first = getDeviceId()
    const second = getDeviceId()

    expect(first).toMatch(/^[0-9a-f]{32}$/)
    expect(second).toBe(first)
    expect(localStorage.getItem('yzh_device_id')).toBe(first)
  })

  it('uses a stable per-session fallback when storage is unavailable', async () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage denied')
    })
    const { getDeviceId } = await import('./deviceId')

    expect(getDeviceId()).toMatch(/^[0-9a-f]{32}$/)
    expect(getDeviceId()).toBe(getDeviceId())

    getItem.mockRestore()
  })

  it('still returns a stable opaque identifier when crypto is unavailable', async () => {
    const originalCrypto = globalThis.crypto
    Object.defineProperty(globalThis, 'crypto', { configurable: true, value: undefined })
    const { getDeviceId } = await import('./deviceId')

    const fallback = getDeviceId()
    expect(fallback).toMatch(/^[0-9a-f]{32}$/)
    expect(getDeviceId()).toBe(fallback)
    expect(localStorage.getItem('yzh_device_id')).toBeNull()

    Object.defineProperty(globalThis, 'crypto', { configurable: true, value: originalCrypto })
  })
})
