import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { SkillDetail, SkillFileContent } from '../api/skills'
import {
  decodeBase64Content,
  downloadSkillFile,
  downloadSkillImageBlob,
  downloadSkillJson,
  downloadSkillPackageBlob,
  clawhubPackageIconFileName,
  imageExtensionForBlob,
  isTextFile,
  packageFileName,
  skillFileBaseName,
  skillFileBlob,
  skillImageFileName,
  skillJsonText,
} from './skillDownload'

const detail: SkillDetail = {
  object: 'skill',
  name: 'example-skill',
  title: '示例 Skill',
  description: '占位示例，内容待补充。',
  version: '0.0.1',
  category: '示例',
  tags: ['示例'],
  icon: '🧩',
  highlights: ['占位卖点'],
  updatedAt: '2026-09-12T03:37:00Z',
  fileCount: 2,
  totalBytes: 6,
  files: [],
}

/** jsdom 的 Blob 没有 text()/arrayBuffer()，统一走 FileReader 读取 */
function readBlobAsText(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(reader.error)
    reader.readAsText(blob)
  })
}

function readBlobAsBytes(blob: Blob): Promise<number[]> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(Array.from(new Uint8Array(reader.result as ArrayBuffer)))
    reader.onerror = () => reject(reader.error)
    reader.readAsArrayBuffer(blob)
  })
}

const textFile: SkillFileContent = {
  path: 'docs/guide/SKILL.md',
  encoding: 'utf-8',
  bytes: 5,
  sha256: 'a'.repeat(64),
  content: 'hello',
}

const binaryFile: SkillFileContent = {
  path: 'assets/logo.bin',
  encoding: 'base64',
  bytes: 3,
  sha256: 'b'.repeat(64),
  content: 'AAEC',
}

describe('skillDownload', () => {
  const clicked: HTMLAnchorElement[] = []
  const blobs: Blob[] = []

  beforeEach(() => {
    clicked.length = 0
    blobs.length = 0
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push(this)
    })
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      value: vi.fn((blob: Blob) => {
        blobs.push(blob)
        return `blob:mock/${blobs.length}`
      }),
    })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    Reflect.deleteProperty(URL, 'createObjectURL')
    Reflect.deleteProperty(URL, 'revokeObjectURL')
  })

  it('serializes the whole skill as pretty-printed JSON', () => {
    const text = skillJsonText(detail)

    expect(text).toBe(JSON.stringify(detail, null, 2))
    expect(JSON.parse(text)).toMatchObject({ name: 'example-skill', version: '0.0.1' })
  })

  it('restores base64 content into the original bytes', () => {
    expect(Array.from(decodeBase64Content('AAEC'))).toEqual([0, 1, 2])
    expect(new TextDecoder().decode(decodeBase64Content('SGVsbG8='))).toBe('Hello')
  })

  it('builds a binary blob for base64 files and a text blob for utf-8 files', async () => {
    const binary = skillFileBlob(binaryFile)
    expect(binary.type).toBe('application/octet-stream')
    expect(await readBlobAsBytes(binary)).toEqual([0, 1, 2])

    const text = skillFileBlob(textFile)
    expect(text.type).toContain('text/plain')
    expect(await readBlobAsText(text)).toBe('hello')

    expect(isTextFile(textFile)).toBe(true)
    expect(isTextFile(binaryFile)).toBe(false)
  })

  it('keeps only the basename of nested paths', () => {
    expect(skillFileBaseName('docs/guide/SKILL.md')).toBe('SKILL.md')
    expect(skillFileBaseName('SKILL.md')).toBe('SKILL.md')
    expect(skillFileBaseName('docs\\windows\\a.txt')).toBe('a.txt')
    expect(skillFileBaseName('docs/..')).toBe('skill-file')
  })

  it('downloads the skill snapshot as <name>.skill.json', async () => {
    downloadSkillJson('example-skill', detail)

    expect(clicked).toHaveLength(1)
    expect(clicked[0].download).toBe('example-skill.skill.json')
    expect(blobs).toHaveLength(1)
    expect(blobs[0].type).toContain('application/json')
    expect(JSON.parse(await readBlobAsText(blobs[0]))).toMatchObject({ name: 'example-skill' })
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
    expect(document.querySelector('a[download]')).toBeNull()
  })

  it('downloads a single file decoded by its encoding', async () => {
    downloadSkillFile(detail, textFile)
    downloadSkillFile(detail, binaryFile)

    expect(clicked.map((anchor) => anchor.download)).toEqual([
      'example-skill-SKILL.md',
      'example-skill-logo.bin',
    ])
    expect(await readBlobAsText(blobs[0])).toBe('hello')
    expect(await readBlobAsBytes(blobs[1])).toEqual([0, 1, 2])
  })

  describe('整包下载', () => {
    it('prefers the filename the server put in Content-Disposition', () => {
      expect(packageFileName('demo-skill-1.2.0.ycps.zip', 'demo-skill', '1.2.0'))
        .toBe('demo-skill-1.2.0.ycps.zip')
    })

    it('falls back to <code>-<version>.ycps.zip when the header is missing', () => {
      expect(packageFileName(null, 'demo-skill', '1.3.0')).toBe('demo-skill-1.3.0.ycps.zip')
      expect(packageFileName('   ', 'demo-skill', '1.3.0')).toBe('demo-skill-1.3.0.ycps.zip')
    })

    it('strips characters that would break the download filename', () => {
      expect(packageFileName(null, 'a/b:c', '1.0.0')).toBe('a-b-c-1.0.0.ycps.zip')
    })

    it('triggers a blob download under the resolved name', () => {
      downloadSkillPackageBlob('demo-skill-1.2.0.ycps.zip', new Blob(['zip']))

      expect(clicked).toHaveLength(1)
      expect(clicked[0].download).toBe('demo-skill-1.2.0.ycps.zip')
      expect(blobs).toHaveLength(1)
    })
  })

  // 需求：图标 / 介绍插画 / 分类封面都要能下载到本地。
  // 三条取字节接口都不带扩展名，文件名只能由前端拼，因此单独测这层拼接规则。
  describe('图片下载命名', () => {
    it('derives the extension from the blob MIME type', () => {
      expect(imageExtensionForBlob(new Blob(['x'], { type: 'image/png' }))).toBe('png')
      expect(imageExtensionForBlob(new Blob(['x'], { type: 'image/jpeg' }))).toBe('jpg')
      expect(imageExtensionForBlob(new Blob(['x'], { type: 'image/webp' }))).toBe('webp')
      // SVG 的 Content-Type 常带 charset，必须先剥参数再查表
      expect(imageExtensionForBlob(new Blob(['x'], { type: 'image/svg+xml;charset=utf-8' })))
        .toBe('svg')
    })

    it('uses the exported ClawHub ZIP basename for its separate icon', () => {
      expect(clawhubPackageIconFileName('clawhub-demo-1.2.3.zip', new Blob(['x'], { type: 'image/jpeg' })))
        .toBe('clawhub-demo-1.2.3.jpg')
      expect(clawhubPackageIconFileName('folder\\native-export.ZIP', new Blob(['x'], { type: 'image/svg+xml' })))
        .toBe('native-export.svg')
      expect(clawhubPackageIconFileName('native-export.zip', new Blob(['x'], { type: 'image/webp' })))
        .toBe('native-export.webp')
    })

    it('falls back to png when the type is missing or unknown', () => {
      expect(imageExtensionForBlob(new Blob(['x']))).toBe('png')
      expect(imageExtensionForBlob(new Blob(['x'], { type: 'application/octet-stream' })))
        .toBe('png')
    })

    it('names the file <code>-<role>.<ext>', () => {
      const png = new Blob(['x'], { type: 'image/png' })

      expect(skillImageFileName('content-writing-expert', 'icon', png))
        .toBe('content-writing-expert-icon.png')
      expect(skillImageFileName('content-writing-expert', 'illustration', png))
        .toBe('content-writing-expert-illustration.png')
      expect(skillImageFileName('content-creation', 'cover', png))
        .toBe('content-creation-cover.png')
    })

    it('strips characters that would break the download filename', () => {
      const png = new Blob(['x'], { type: 'image/png' })

      expect(skillImageFileName('a/b:c', 'icon', png)).toBe('a-b-c-icon.png')
      expect(skillImageFileName('   ', 'icon', png)).toBe('skill-icon.png')
    })

    it('triggers a blob download under the resolved name', () => {
      downloadSkillImageBlob('demo-skill-icon.png', new Blob(['png'], { type: 'image/png' }))

      expect(clicked).toHaveLength(1)
      expect(clicked[0].download).toBe('demo-skill-icon.png')
      expect(blobs).toHaveLength(1)
    })
  })
})
