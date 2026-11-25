import { beforeEach, describe, expect, it, vi } from 'vitest'

const http = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('./http', () => ({ gatewayHttp: http }))

import { listNativeClawhubSkills, listSkills } from './skills'

describe('client skill catalogs', () => {
  beforeEach(() => {
    http.get.mockReset()
  })

  it('keeps the native ClawHub items contract separate from the YCPS data contract', async () => {
    const nativeCatalog = {
      object: 'list',
      items: [{
        code: 'owner-demo', name: 'Demo', description: 'Original Skill',
        descriptionEn: null, summary: 'Original Skill', summaryEn: null,
        status: 'PUBLISHED', version: '1.2.3', packageFormat: 'CLAWHUB_SKILL',
        sha256: 'a'.repeat(64), sizeBytes: 1024,
        owner: 'owner', slug: 'demo',
        downloadUrl: '/v1/native-clawhub/skills/owner-demo/package',
      }],
    }
    const ycpsCatalog = { object: 'list', data: [] }
    http.get.mockResolvedValueOnce({ data: nativeCatalog })
      .mockResolvedValueOnce({ data: ycpsCatalog })

    expect(await listNativeClawhubSkills()).toEqual(nativeCatalog)
    expect(await listSkills()).toEqual(ycpsCatalog)
    expect(http.get.mock.calls).toEqual([
      ['/v1/native-clawhub/skills'],
      ['/console/gateway/skills'],
    ])
  })
})
