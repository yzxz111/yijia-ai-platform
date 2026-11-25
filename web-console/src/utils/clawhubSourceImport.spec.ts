import { describe, expect, it, vi } from 'vitest'

import { fetchClawhubDisplayName } from './clawhubSourceImport'

function response(payload: unknown, ok = true) {
  return { ok, json: vi.fn().mockResolvedValue(payload) } as unknown as Response
}

describe('ClawHub publisher display name lookup', () => {
  it('reads the official title over HTTPS without credentials and verifies the requested identity', async () => {
    const fetcher = vi.fn().mockResolvedValue(response({
      skill: {
        slug: 'self-improving',
        displayName: 'Self-Improving + Proactive Agent',
      },
      owner: { handle: 'publisher' },
    }))

    await expect(fetchClawhubDisplayName('publisher', 'self-improving', fetcher))
      .resolves.toBe('Self-Improving + Proactive Agent')

    const [url, options] = fetcher.mock.calls[0] as [URL, RequestInit]
    expect(url.href).toBe('https://clawhub.ai/api/v1/skills/self-improving?ownerHandle=publisher')
    expect(options).toMatchObject({ credentials: 'omit', redirect: 'error' })
    expect(options.headers).toEqual({ Accept: 'application/json' })
  })

  it('does not trust a title returned for a different skill identity', async () => {
    const fetcher = vi.fn().mockResolvedValue(response({
      skill: {
        slug: 'another-skill',
        displayName: 'Wrong title',
      },
      owner: { handle: 'publisher' },
    }))

    await expect(fetchClawhubDisplayName('publisher', 'self-improving', fetcher)).resolves.toBeNull()
  })

  it('treats unavailable, invalid, or oversized registry titles as a safe fallback', async () => {
    const fetcher = vi.fn()
    await expect(fetchClawhubDisplayName('../other', 'demo', fetcher)).resolves.toBeNull()
    expect(fetcher).not.toHaveBeenCalled()

    fetcher.mockResolvedValueOnce(response({ skill: {
      slug: 'demo', displayName: ' ',
    }, owner: { handle: 'publisher' } }))
    await expect(fetchClawhubDisplayName('publisher', 'demo', fetcher)).resolves.toBeNull()

    fetcher.mockRejectedValueOnce(new Error('network unavailable'))
    await expect(fetchClawhubDisplayName('publisher', 'demo', fetcher)).resolves.toBeNull()

    fetcher.mockResolvedValueOnce(response({ skill: {
      slug: 'demo', displayName: 'x'.repeat(129),
    }, owner: { handle: 'publisher' } }))
    await expect(fetchClawhubDisplayName('publisher', 'demo', fetcher)).resolves.toBeNull()

    fetcher.mockResolvedValueOnce(response({ skill: {
      slug: 'demo', displayName: 'bad\nname',
    }, owner: { handle: 'publisher' } }))
    await expect(fetchClawhubDisplayName('publisher', 'demo', fetcher)).resolves.toBeNull()
  })
})
