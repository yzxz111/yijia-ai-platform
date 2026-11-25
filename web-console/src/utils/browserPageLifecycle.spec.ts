import { describe, expect, it } from 'vitest'
import { createBrowserPageLifecycle } from './browserPageLifecycle'

describe('browser page lifecycle', () => {
  it('orders close after in-flight heartbeats and prevents further heartbeats', () => {
    const page = createBrowserPageLifecycle()
    const heartbeat = page.heartbeat()!
    const close = page.close()!
    expect(close['X-Browser-Page']).toBe(heartbeat['X-Browser-Page'])
    expect(Number(close['X-Browser-Sequence'])).toBeGreaterThan(Number(heartbeat['X-Browser-Sequence']))
    expect(page.heartbeat()).toBeNull()
    expect(page.close()).toBeNull()
  })
  it('tracks refreshed pages and other tabs independently', () => {
    const first = createBrowserPageLifecycle()
    const second = createBrowserPageLifecycle()
    expect(first.close()!['X-Browser-Page']).not.toBe(second.heartbeat()!['X-Browser-Page'])
  })
})
