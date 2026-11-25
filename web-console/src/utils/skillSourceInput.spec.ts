import { afterEach, describe, expect, it, vi } from 'vitest'

import { buildZip } from './ycpsZipWriter'
import { inferClawhubIdentity } from './clawhubSourceImport'
import {
  packageFromDrop,
  packageFromFileSelection,
  skillSourceFromDrop,
  skillSourceFromFileSelection,
  SkillSourceInputError,
} from './skillSourceInput'

function withRelativePath(file: File, path: string): File {
  Object.defineProperty(file, 'webkitRelativePath', { configurable: true, value: path })
  return file
}

function fileBytes(file: File): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer))
    reader.onerror = () => reject(reader.error)
    reader.readAsArrayBuffer(file)
  })
}

describe('skillSourceInput', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('normalizes a selected YCPS download before upload', async () => {
    const source = new File(['ycps'], 'demo.ycps(1).zip', { type: 'application/octet-stream' })

    const prepared = await skillSourceFromFileSelection([source])

    expect(prepared).toMatchObject({ source: 'zip', native: null })
    expect(prepared.file.name).toBe('demo.ycps.zip')
    expect(prepared.file.type).toBe('application/zip')
    expect([...await fileBytes(prepared.file)]).toEqual([...new TextEncoder().encode('ycps')])
  })

  it('packs a selected YCPS source directory with plugin.json at the archive root', async () => {
    vi.stubGlobal('CompressionStream', undefined)
    const files = [
      withRelativePath(new File(['{}'], 'plugin.json'), 'demo/plugin.json'),
      withRelativePath(new File(['# Demo'], 'SKILL.md'), 'demo/skills/demo/SKILL.md'),
    ]

    const prepared = await skillSourceFromFileSelection(files)
    const archiveText = new TextDecoder().decode(await fileBytes(prepared.file))

    expect(prepared).toMatchObject({ source: 'folder', native: null })
    expect(prepared.file.name).toBe('demo.ycps.zip')
    expect(archiveText).toContain('plugin.json')
    expect(archiveText).toContain('skills/demo/SKILL.md')
    expect(archiveText).not.toContain('demo/plugin.json')
  })

  it('keeps a native ClawHub directory intact and reports its probe', async () => {
    vi.stubGlobal('CompressionStream', undefined)
    const files = [
      withRelativePath(new File(['---\nname: Demo\n---\n'], 'SKILL.md'), 'demo/SKILL.md'),
      withRelativePath(
        new File(['{"ownerHandle":"owner","slug":"demo"}'], 'origin.json'),
        'demo/.clawhub/origin.json',
      ),
    ]

    const prepared = await skillSourceFromFileSelection(files)
    const archiveText = new TextDecoder().decode(await fileBytes(prepared.file))

    expect(prepared.source).toBe('folder')
    expect(prepared.file.name).toBe('demo.zip')
    expect(prepared.native?.skillPath).toBe('demo/SKILL.md')
    expect(archiveText).toContain('demo/SKILL.md')
    expect(archiveText).toContain('demo/.clawhub/origin.json')
  })

  it('uses the selected native directory basename as the metadata fallback', async () => {
    vi.stubGlobal('CompressionStream', undefined)
    const files = [
      withRelativePath(new File(['# Demo'], 'SKILL.md'), 'fallback-demo/SKILL.md'),
    ]

    const prepared = await skillSourceFromFileSelection(files)
    const identity = inferClawhubIdentity(prepared.native, prepared.file.name)

    expect(prepared.file.name).toBe('fallback-demo.zip')
    expect(identity).toMatchObject({ owner: null, slug: 'fallback-demo' })
  })

  it('names a canonical owner-qualified native directory after its slug without stripping paths', async () => {
    vi.stubGlobal('CompressionStream', undefined)
    const files = [
      withRelativePath(new File(['# Demo'], 'SKILL.md'), '@owner/real-slug/SKILL.md'),
      withRelativePath(new File(['reference'], 'guide.md'), '@owner/real-slug/references/guide.md'),
    ]

    const prepared = await skillSourceFromFileSelection(files)
    const identity = inferClawhubIdentity(prepared.native, prepared.file.name)
    const archiveText = new TextDecoder().decode(await fileBytes(prepared.file))

    expect(prepared.file.name).toBe('real-slug.zip')
    expect(prepared.native?.skillPath).toBe('@owner/real-slug/SKILL.md')
    expect(identity).toMatchObject({ owner: 'owner', slug: 'real-slug' })
    expect(archiveText).toContain('@owner/real-slug/SKILL.md')
    expect(archiveText).toContain('@owner/real-slug/references/guide.md')
  })

  it('rejects a selected complete bundle instead of discarding its images', async () => {
    vi.stubGlobal('CompressionStream', undefined)
    const inner = new TextEncoder().encode('inner-ycps')
    const bundle = await buildZip([
      { path: 'demo/demo.ycps.zip', bytes: inner },
      { path: 'demo/demo-icon.png', bytes: new Uint8Array([1, 2]) },
    ])

    const error = await packageFromFileSelection([
      new File([bundle as BlobPart], 'demo.bundle.zip', { type: 'application/zip' }),
    ]).catch(value => value)

    expect(error).toBeInstanceOf(SkillSourceInputError)
    expect(error).toMatchObject({ code: 'complete_bundle_not_supported' })
    expect(error.message).toContain('含图完整包不能在此直接导入')
  })

  it('rejects an extracted complete bundle directory instead of discarding its images', async () => {
    const files = [
      withRelativePath(new File(['inner-ycps'], 'demo.ycps.zip'), 'demo-bundle/demo.ycps.zip'),
      withRelativePath(new File(['icon'], 'demo-icon.png'), 'demo-bundle/demo-icon.png'),
    ]

    const error = await packageFromFileSelection(files).catch(value => value)

    expect(error).toBeInstanceOf(SkillSourceInputError)
    expect(error).toMatchObject({ code: 'complete_bundle_not_supported' })
    expect(error.message).toContain('含图完整包不能在此直接导入')
  })

  it('packages a YCPS source tree with root plugin.json even when it contains a nested YCPS archive', async () => {
    vi.stubGlobal('CompressionStream', undefined)
    const files = [
      withRelativePath(new File(['{}'], 'plugin.json'), 'demo/plugin.json'),
      withRelativePath(new File(['# Demo'], 'SKILL.md'), 'demo/skills/demo/SKILL.md'),
      withRelativePath(new File(['fixture'], 'fixture.ycps.zip'), 'demo/fixtures/fixture.ycps.zip'),
    ]

    const prepared = await skillSourceFromFileSelection(files)
    const archiveText = new TextDecoder().decode(await fileBytes(prepared.file))

    expect(prepared).toMatchObject({ source: 'folder', native: null })
    expect(prepared.file.name).toBe('demo.ycps.zip')
    expect(archiveText).toContain('plugin.json')
    expect(archiveText).toContain('fixtures/fixture.ycps.zip')
  })

  it('walks a dropped directory and packages it as YCPS', async () => {
    vi.stubGlobal('CompressionStream', undefined)
    const fileEntry = (name: string, content: string) => ({
      isFile: true,
      isDirectory: false,
      name,
      file: (done: (file: File) => void) => done(new File([content], name)),
    })
    let read = false
    const root = {
      isFile: false,
      isDirectory: true,
      name: 'demo',
      createReader: () => ({
        readEntries: (done: (entries: unknown[]) => void) => {
          const entries = read ? [] : [
            fileEntry('plugin.json', '{}'),
            {
              isFile: false,
              isDirectory: true,
              name: 'skills',
              createReader: () => {
                let nestedRead = false
                return {
                  readEntries: (nestedDone: (entries: unknown[]) => void) => {
                    const nested = nestedRead ? [] : [fileEntry('SKILL.md', '# Demo')]
                    nestedRead = true
                    nestedDone(nested)
                  },
                }
              },
            },
          ]
          read = true
          done(entries)
        },
      }),
    }
    const transfer = {
      items: [{ webkitGetAsEntry: () => root, getAsFile: () => null }],
      files: [],
    }

    const prepared = await skillSourceFromDrop(transfer as unknown as DataTransfer)

    expect(prepared).toMatchObject({ source: 'folder', native: null })
    expect(prepared.file.name).toBe('demo.ycps.zip')
  })

  it('rejects a native source at the YCPS-only convenience boundary', async () => {
    vi.stubGlobal('CompressionStream', undefined)
    const archive = await buildZip([
      { path: 'demo/SKILL.md', bytes: new TextEncoder().encode('# Demo') },
    ])
    const transfer = {
      items: [],
      files: [new File([archive as BlobPart], 'demo.zip')],
    }

    const error = await packageFromDrop(transfer).catch(value => value)

    expect(error).toBeInstanceOf(SkillSourceInputError)
    expect(error).toMatchObject({ code: 'native_skill_not_allowed' })
  })

  it('reports an empty selection with a stable error code', async () => {
    const error = await packageFromFileSelection([]).catch(value => value)

    expect(error).toBeInstanceOf(SkillSourceInputError)
    expect(error).toMatchObject({ code: 'empty_selection' })
  })
})
