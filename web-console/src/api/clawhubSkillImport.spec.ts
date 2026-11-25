import { beforeEach, describe, expect, it, vi } from 'vitest'

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))

vi.mock('./http', () => ({
  gatewayHttp: http,
  platformHttp: {},
}))

import {
  commitAdminClawhubSkill,
  deleteAdminClawhubVersion,
  downloadAdminClawhubVersion,
  downloadAdminClawhubSkillPackage,
  fetchAdminRemoteClawhubSkill,
  listAdminClawhubVersions,
  previewAdminClawhubSkill,
  publishAdminClawhubVersion,
  publishAdminClawhubSkill,
  rescanAdminClawhubVersion,
  rescanAdminClawhubSkill,
  uploadAdminClawhubVersion,
  updateAdminClawhubSourceVersion,
} from './admin'

describe('native ClawHub skill import API', () => {
  beforeEach(() => {
    http.post.mockReset().mockResolvedValue({ data: {} })
    http.put.mockReset().mockResolvedValue({ data: {} })
    http.get.mockReset().mockResolvedValue({ data: {}, headers: {} })
    http.delete.mockReset().mockResolvedValue({ data: undefined })
  })

  it('previews the original archive with an optional version', async () => {
    const file = new File(['source'], 'demo.zip', { type: 'application/zip' })
    http.post.mockResolvedValueOnce({ data: { report: { status: 'REVIEW_REQUIRED' }, packageBase64: null } })

    await previewAdminClawhubSkill(file, 'publisher', 'demo', null, 'Demo Skill')

    expect(http.post).toHaveBeenCalledWith(
      '/console/gateway/admin/skill-source-imports/clawhub/preview',
      expect.any(FormData),
    )
    const body = http.post.mock.calls[0][1] as FormData
    expect(body.get('file')).toBe(file)
    expect(body.get('owner')).toBe('publisher')
    expect(body.get('slug')).toBe('demo')
    expect(body.get('version')).toBeNull()
    expect(body.get('displayName')).toBe('Demo Skill')
  })

  it('commits the original archive with the preview fingerprint and allows no version', async () => {
    const file = new File(['source'], 'demo.zip', { type: 'application/zip' })
    await commitAdminClawhubSkill({
      file, owner: 'publisher', slug: 'demo', version: null,
      displayName: 'Demo Skill', expectedSha256: 'a'.repeat(64),
    })

    expect(http.post).toHaveBeenCalledWith(
      '/console/gateway/admin/skill-source-imports/clawhub/commit',
      expect.any(FormData),
    )
    const body = http.post.mock.calls[0][1] as FormData
    expect(body.get('file')).toBe(file)
    expect(body.get('owner')).toBe('publisher')
    expect(body.get('slug')).toBe('demo')
    expect(body.get('version')).toBeNull()
    expect(body.get('displayName')).toBe('Demo Skill')
    expect(body.get('expectedSha256')).toBe('a'.repeat(64))
  })

  it('returns the exact fetched remote ZIP and provenance for preview and commit', async () => {
    const url = 'https://github.com/alice/demo/tree/main/skills/demo'
    const metadata = {
      sourceKind: 'GITHUB', owner: 'github.alice', slug: 'demo-skill-1234',
      version: '1.2.3', displayName: 'Demo Skill', sourceUrl: url,
      fileName: 'demo-skill.zip', sourceRevision: 'a'.repeat(40),
    }
    http.post.mockResolvedValueOnce({
      data: new Blob(['remote-zip']),
      headers: {
        'x-skill-remote-metadata': encodeURIComponent(JSON.stringify(metadata)),
        'x-skill-sha256': 'a'.repeat(64),
      },
    })
    const controller = new AbortController()

    const result = await fetchAdminRemoteClawhubSkill(url, controller.signal)

    expect(http.post).toHaveBeenCalledWith(
      '/console/gateway/admin/skill-source-imports/clawhub/remote/fetch',
      { url },
      { responseType: 'blob', signal: controller.signal },
    )
    expect(result.file).toBeInstanceOf(File)
    expect(result.file.name).toBe('demo-skill.zip')
    const reader = new FileReader()
    const content = await new Promise<string>((resolve, reject) => {
      reader.onload = () => resolve(String(reader.result))
      reader.onerror = () => reject(reader.error)
      reader.readAsText(result.file)
    })
    expect(content).toBe('remote-zip')
    expect(result.metadata).toEqual(metadata)
    expect(result.sha256).toBe('a'.repeat(64))
  })

  it('decodes JSON errors returned as Blob so the admin error mapper can read the code', async () => {
    const failure = Object.assign(new Error('Request failed'), {
      response: { data: new Blob([JSON.stringify({ code: 'clawhub_remote_not_found', message: 'missing' })]) },
    })
    http.post.mockRejectedValueOnce(failure)

    await expect(fetchAdminRemoteClawhubSkill('https://clawhub.ai/skills/demo')).rejects.toMatchObject({
      response: { data: { code: 'clawhub_remote_not_found', message: 'missing' } },
    })
  })

  it('sends edited presentation and remote provenance in the native commit metadata part', async () => {
    const metadata = {
      name: 'Edited title', nameEn: null, category: null, tags: ['GitHub', 'alice'],
      summary: 'Edited summary', summaryEn: null,
      description: 'Edited description', descriptionEn: null,
      minClientVersion: '2026.6.0', sortWeight: 0,
      sourceKind: 'GITHUB' as const, sourceUrl: 'https://github.com/alice/demo',
      sourceRevision: 'a'.repeat(40),
    }
    await commitAdminClawhubSkill({
      file: new File(['zip'], 'demo.zip'), owner: 'github.alice', slug: 'demo', metadata,
    })
    const body = http.post.mock.calls[0][1] as FormData
    expect(JSON.parse(String(body.get('metadata')))).toEqual(metadata)
  })

  it('publishes only through the explicit ClawHub endpoint', async () => {
    await publishAdminClawhubSkill('skill/with space')

    expect(http.post).toHaveBeenCalledWith(
      '/console/gateway/admin/skill-source-imports/clawhub/skill%2Fwith%20space/publish',
    )
  })

  it('sets only the missing source-level version through the ClawHub admin endpoint', async () => {
    await updateAdminClawhubSourceVersion('skill/with space', '1.2.3-rc.1')

    expect(http.put).toHaveBeenCalledWith(
      '/console/gateway/admin/skill-source-imports/clawhub/skill%2Fwith%20space/version',
      { version: '1.2.3-rc.1' },
    )
  })

  it('retries a stored draft scan through the native endpoint', async () => {
    await rescanAdminClawhubSkill('skill/with space')

    expect(http.post).toHaveBeenCalledWith(
      '/console/gateway/admin/skill-source-imports/clawhub/skill%2Fwith%20space/rescan',
    )
  })

  it('lists native archives by Skill id, separately from YCPS versions', async () => {
    await listAdminClawhubVersions('skill/with space')

    expect(http.get).toHaveBeenCalledWith(
      '/console/gateway/admin/skill-source-imports/clawhub/skill%2Fwith%20space/versions',
    )
  })

  it('uploads an immutable ClawHub ZIP with its version and preview fingerprint', async () => {
    const file = new File(['native-zip'], 'demo.zip', { type: 'application/zip' })
    await uploadAdminClawhubVersion('skill-1', {
      file, version: '2.0.0', expectedSha256: 'b'.repeat(64),
    })

    expect(http.post).toHaveBeenCalledWith(
      '/console/gateway/admin/skill-source-imports/clawhub/skill-1/versions',
      expect.any(FormData),
      expect.objectContaining({ onUploadProgress: expect.any(Function) }),
    )
    const body = http.post.mock.calls[0][1] as FormData
    expect(body.get('file')).toBe(file)
    expect(body.get('version')).toBe('2.0.0')
    expect(body.get('expectedSha256')).toBe('b'.repeat(64))
  })

  it('exports the exact original ZIP for a draft or published source revision', async () => {
    const blob = new Blob(['original-clawhub-zip'])
    http.get.mockResolvedValueOnce({
      data: blob,
      headers: { 'content-disposition': 'attachment; filename="demo-2.0.0.zip"' },
    })

    const result = await downloadAdminClawhubVersion('skill/1', 'source/2')

    expect(http.get).toHaveBeenCalledWith(
      '/console/gateway/admin/skill-source-imports/clawhub/skill%2F1/versions/source%2F2/download',
      { responseType: 'blob' },
    )
    expect(result).toEqual({ blob, fileName: 'demo-2.0.0.zip' })
  })

  it('exports the current ClawHub source through the separate admin package route', async () => {
    const blob = new Blob(['current-original-zip'])
    http.get.mockResolvedValueOnce({
      data: blob,
      headers: { 'content-disposition': 'attachment; filename="clawhub-demo.zip"' },
    })

    const result = await downloadAdminClawhubSkillPackage('skill/1')

    expect(http.get).toHaveBeenCalledWith(
      '/console/gateway/admin/skill-source-imports/clawhub/skill%2F1/package',
      { responseType: 'blob' },
    )
    expect(result).toEqual({ blob, fileName: 'clawhub-demo.zip' })
  })

  it('publishes, rescans, and deletes by both Skill and native source revision ids', async () => {
    await publishAdminClawhubVersion('skill-1', 'source-2')
    await rescanAdminClawhubVersion('skill-1', 'source-2')
    await deleteAdminClawhubVersion('skill-1', 'source-2')

    expect(http.post).toHaveBeenNthCalledWith(
      1,
      '/console/gateway/admin/skill-source-imports/clawhub/skill-1/versions/source-2/publish',
    )
    expect(http.post).toHaveBeenNthCalledWith(
      2,
      '/console/gateway/admin/skill-source-imports/clawhub/skill-1/versions/source-2/rescan',
    )
    expect(http.delete).toHaveBeenCalledWith(
      '/console/gateway/admin/skill-source-imports/clawhub/skill-1/versions/source-2',
    )
  })
})
