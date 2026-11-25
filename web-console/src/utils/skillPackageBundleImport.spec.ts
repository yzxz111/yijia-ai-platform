import { afterEach, describe, expect, it, vi } from 'vitest'

import { buildZip } from './ycpsZipWriter'
import {
  nativeSkillPath,
  normalizePackageFileName,
  preparePackageArchive,
  preparePackageDirectory,
  probeNativeSkillArchive,
} from './skillPackageBundleImport'

async function fileBytes(file: File): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer))
    reader.onerror = () => reject(reader.error)
    reader.readAsArrayBuffer(file)
  })
}

describe('skillPackageBundleImport', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('recognizes native Skill support files without misrouting a YCPS package', () => {
    expect(nativeSkillPath([
      'demo/SKILL.md', 'demo/references/plugin.json',
    ])).toBe('demo/SKILL.md')
    expect(nativeSkillPath([
      'demo/plugin.json', 'demo/skills/demo/SKILL.md',
    ])).toBeNull()
    expect(nativeSkillPath(['demo/skill.md'])).toBe('demo/skill.md')
    expect(nativeSkillPath(['demo/skills.md'])).toBe('demo/skills.md')
    expect(nativeSkillPath([
      'demo/SKILL.md', 'demo/references/SKILL.md',
    ])).toBe('demo/SKILL.md')
    expect(nativeSkillPath(['demo/references/SKILL.md'])).toBeNull()
    // Conflicting naming variants still route the untouched source to the gateway,
    // which is responsible for returning BLOCKED rather than silently choosing one.
    expect(nativeSkillPath(['demo/skill.md', 'demo/skills.md'])).toBe('demo/skill.md')
  })

  it('reads ClawHub origin and frontmatter hints from a native ZIP', async () => {
    const archive = await buildZip([
      { path: '@publisher/demo/SKILL.md', bytes: new TextEncoder().encode('---\nname: Demo\nversion: 1.2.3\n---\nBody\n') },
      { path: '@publisher/demo/.clawhub/origin.json', bytes: new TextEncoder().encode('{"ownerHandle":"publisher","slug":"demo","installedVersion":"1.2.3"}') },
    ])
    const source = new File([archive as BlobPart], 'demo.zip')
    const probe = await probeNativeSkillArchive(source)
    expect(probe?.skillPath).toBe('@publisher/demo/SKILL.md')
    expect(probe?.markdown).toContain('version: 1.2.3')
    expect(probe?.origin).toContain('ownerHandle')
  })

  it('routes a native ZIP above 10 MiB to ClawHub preview within the 50 MiB limit', async () => {
    vi.stubGlobal('CompressionStream', undefined)
    const archive = await buildZip([
      { path: 'demo/SKILL.md', bytes: new TextEncoder().encode('---\nname: Demo\n---\n') },
      { path: 'demo/assets/sample.bin', bytes: new Uint8Array(11 * 1024 * 1024) },
    ])
    const source = new File([archive as BlobPart], 'demo.zip')

    expect(source.size).toBeGreaterThan(10 * 1024 * 1024)
    expect(await probeNativeSkillArchive(source)).toMatchObject({ skillPath: 'demo/SKILL.md' })
  })

  it('keeps a normal YCPS archive byte-identical', async () => {
    const source = new File(['ycps'], 'writer-1.0.0.ycps.zip', { type: 'application/zip' })

    const prepared = await preparePackageArchive(source)

    expect(prepared.packageFile).toBe(source)
    expect(prepared.completeBundle).toBe(false)
    expect(prepared.images).toEqual({})
  })

  it.each([
    ['writer-1.0.0.ycps(1).zip', 'writer-1.0.0.ycps.zip'],
    ['writer-1.0.0.ycps (1).zip', 'writer-1.0.0.ycps.zip'],
    ['writer-1.0.0 (1).ycps.zip', 'writer-1.0.0.ycps.zip'],
    ['writer-1.0.0 (1) (2).ycps.zip', 'writer-1.0.0.ycps.zip'],
  ])('normalizes browser duplicate name %s before upload', async (name, expected) => {
    expect(normalizePackageFileName(name)).toBe(expected)
    const source = new File(['ycps'], name, { type: 'application/zip' })

    const prepared = await preparePackageArchive(source)

    expect(prepared.packageFile.name).toBe(expected)
    expect([...await fileBytes(prepared.packageFile)]).toEqual([...new TextEncoder().encode('ycps')])
    expect(prepared.completeBundle).toBe(false)
  })

  it.each(['writer-1.0.0.bundle(1).zip', 'writer-1.0.0.bundle (1).zip'])(
    'keeps the complete-bundle error for browser collision name %s',
    async (name) => {
      const source = new File(['not-a-zip'], name, { type: 'application/zip' })

      await expect(preparePackageArchive(source)).rejects.toThrow('含图完整包无法解压')
    },
  )

  it('extracts the inner YCPS and all three conventionally named images', async () => {
    // 强制 writer 使用 STORE，测试不依赖运行时是否实现 deflate-raw；生产下载包的
    // method 8 分支由同一读取器通过 DecompressionStream 处理。
    vi.stubGlobal('CompressionStream', undefined)
    const inner = new TextEncoder().encode('inner-ycps')
    const archive = await buildZip([
      { path: 'writer-1.0.0/writer-1.0.0.ycps.zip', bytes: inner },
      { path: 'writer-1.0.0/writer-icon.png', bytes: new Uint8Array([1, 2]) },
      { path: 'writer-1.0.0/writer-example.webp', bytes: new Uint8Array([3, 4]) },
      { path: 'writer-1.0.0/writer-illustration.svg', bytes: new Uint8Array([5, 6]) },
    ])
    const source = new File([archive as BlobPart], 'writer-1.0.0.bundle.zip', {
      type: 'application/zip',
    })

    const prepared = await preparePackageArchive(source)

    expect(prepared.completeBundle).toBe(true)
    expect(prepared.packageFile.name).toBe('writer-1.0.0.ycps.zip')
    expect([...await fileBytes(prepared.packageFile)]).toEqual([...inner])
    expect(prepared.images.icon?.type).toBe('image/png')
    expect(prepared.images.example?.type).toBe('image/webp')
    expect(prepared.images.illustration?.type).toBe('image/svg+xml')
  })

  it('reads DEFLATE entries used by the server-generated bundle ZIP', async () => {
    const inner = new TextEncoder().encode('ycps-content-'.repeat(1_000))
    const archive = await buildZip([
      { path: 'writer-1.0.0/writer-1.0.0.ycps.zip', bytes: inner },
      { path: 'writer-1.0.0/writer-icon.png', bytes: new Uint8Array(2_000).fill(7) },
    ])
    const source = new File([archive as BlobPart], 'writer-1.0.0.bundle.zip')

    const prepared = await preparePackageArchive(source)

    expect([...await fileBytes(prepared.packageFile)]).toEqual([...inner])
    expect(prepared.images.icon?.size).toBe(2_000)
  })

  it('recognizes the folder produced by extracting a complete bundle', () => {
    const prepared = preparePackageDirectory([
      {
        path: 'writer-1.0.0/writer-1.0.0.ycps.zip',
        file: new File(['ycps'], 'writer-1.0.0.ycps.zip'),
      },
      {
        path: 'writer-1.0.0/writer-icon.jpg',
        file: new File(['icon'], 'writer-icon.jpg'),
      },
      {
        path: 'writer-1.0.0/writer-example.png',
        file: new File(['example'], 'writer-example.png'),
      },
    ])

    expect(prepared?.completeBundle).toBe(true)
    expect(prepared?.packageFile.type).toBe('application/zip')
    expect(prepared?.images.icon?.type).toBe('image/jpeg')
    expect(prepared?.images.example?.type).toBe('image/png')
  })

  it('recognizes a YCPS file renamed by the browser inside an extracted bundle folder', () => {
    const prepared = preparePackageDirectory([
      {
        path: 'writer-1.0.0/writer-1.0.0.ycps(1).zip',
        file: new File(['ycps'], 'writer-1.0.0.ycps(1).zip'),
      },
    ])

    expect(prepared?.completeBundle).toBe(true)
    expect(prepared?.packageFile.name).toBe('writer-1.0.0.ycps.zip')
  })

  it('leaves a YCPS source directory to the existing directory packer', () => {
    expect(preparePackageDirectory([
      { path: 'plugin.json', file: new File(['{}'], 'plugin.json') },
      { path: 'skills/demo/SKILL.md', file: new File(['# demo'], 'SKILL.md') },
    ])).toBeNull()
  })
})
