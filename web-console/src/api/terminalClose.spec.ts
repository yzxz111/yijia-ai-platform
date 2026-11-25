import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { requestTerminalClose } from './platform'

describe('terminal close notification', () => {
  const fetchMock = vi.fn((_url: string, _options: RequestInit) => Promise.resolve({ ok: true }))

  beforeEach(() => {
    fetchMock.mockClear()
    vi.stubGlobal('fetch', fetchMock)
    document.cookie = 'yzh_csrf=csrf-token; Path=/'
  })
  afterEach(() => {
    document.cookie = 'yzh_csrf=; Max-Age=0; Path=/'
    vi.unstubAllGlobals()
  })

  it('uses a keepalive authenticated request during page shutdown', () => {
    requestTerminalClose({ 'X-Browser-Page': 'page-id', 'X-Browser-Sequence': '2' })

    expect(fetchMock).toHaveBeenCalledOnce()
    expect(fetchMock).toHaveBeenCalledWith(
      '/console/platform/auth/browser-close',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        keepalive: true,
        headers: expect.objectContaining({
          'X-CSRF-Token': 'csrf-token',
          'X-Browser-Page': 'page-id',
          'X-Browser-Sequence': '2',
          'X-Device-Id': expect.stringMatching(/^[0-9a-f]{32}$/),
        }),
      }),
    )
    expect(fetchMock.mock.calls[0]?.[1]?.headers).not.toHaveProperty('Authorization')
  })

  it('does not send a close request without the session CSRF credential', () => {
    document.cookie = 'yzh_csrf=; Max-Age=0; Path=/'
    requestTerminalClose({ 'X-Browser-Page': 'page-id', 'X-Browser-Sequence': '2' })
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
