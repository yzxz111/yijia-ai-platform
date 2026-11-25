import { inflateRawSync } from 'node:zlib'

import { describe, expect, it, vi } from 'vitest'

import { buildZip, entriesFromDataTransfer, relativeEntriesFromFileList, stripContainerDirectory } from './ycpsZipWriter'

/**
 * 最小 ZIP 解析器，只服务于本测试。
 *
 * 刻意不复用被测代码里的任何偏移常量：如果写入端把中央目录位置算错，
 * 用它自己的常量去读是发现不了的，必须按 ZIP 规范从头解一遍。
 */
interface ParsedEntry {
  name: string
  method: number
  crc: number
  compressedSize: number
  uncompressedSize: number
  flags: number
  versionMadeByPlatform: number
  localOffset: number
}

interface ParsedZip {
  entries: ParsedEntry[]
  commentLength: number
  eocdOffset: number
  centralOffset: number
  centralSize: number
  bytes: Uint8Array
}

function parseZip(bytes: Uint8Array): ParsedZip {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  let eocdOffset = -1
  for (let index = bytes.length - 22; index >= 0; index -= 1) {
    if (view.getUint32(index, true) === 0x06054b50) {
      eocdOffset = index
      break
    }
  }
  if (eocdOffset < 0) throw new Error('EOCD not found')

  const total = view.getUint16(eocdOffset + 10, true)
  const centralSize = view.getUint32(eocdOffset + 12, true)
  const centralOffset = view.getUint32(eocdOffset + 16, true)
  const commentLength = view.getUint16(eocdOffset + 20, true)

  const entries: ParsedEntry[] = []
  let cursor = centralOffset
  for (let index = 0; index < total; index += 1) {
    if (view.getUint32(cursor, true) !== 0x02014b50) throw new Error('bad central header')
    const nameLength = view.getUint16(cursor + 28, true)
    const extraLength = view.getUint16(cursor + 30, true)
    const entryCommentLength = view.getUint16(cursor + 32, true)
    const name = new TextDecoder().decode(bytes.subarray(cursor + 46, cursor + 46 + nameLength))
    entries.push({
      name,
      method: view.getUint16(cursor + 10, true),
      crc: view.getUint32(cursor + 16, true),
      compressedSize: view.getUint32(cursor + 20, true),
      uncompressedSize: view.getUint32(cursor + 24, true),
      flags: view.getUint16(cursor + 8, true),
      versionMadeByPlatform: (view.getUint16(cursor + 4, true) >> 8) & 0xff,
      localOffset: view.getUint32(cursor + 42, true),
    })
    cursor += 46 + nameLength + extraLength + entryCommentLength
  }
  return { entries, commentLength, eocdOffset, centralOffset, centralSize, bytes }
}

/** 读出某个条目的原始负载，用于验证 STORE 分支的内容没被改写 */
function entryPayload(zip: ParsedZip, entry: ParsedEntry): Uint8Array {
  const view = new DataView(zip.bytes.buffer, zip.bytes.byteOffset, zip.bytes.byteLength)
  const nameLength = view.getUint16(entry.localOffset + 26, true)
  const extraLength = view.getUint16(entry.localOffset + 28, true)
  const start = entry.localOffset + 30 + nameLength + extraLength
  return zip.bytes.subarray(start, start + entry.compressedSize)
}

/** 与后端 `SkillPackageInspector` 的 CRC-32 等价实现，用来独立复算期望值 */
function expectedCrc32(bytes: Uint8Array): number {
  let crc = 0xffffffff
  for (let index = 0; index < bytes.length; index += 1) {
    crc ^= bytes[index]
    for (let bit = 0; bit < 8; bit += 1) {
      crc = crc & 1 ? 0xedb88320 ^ (crc >>> 1) : crc >>> 1
    }
  }
  return (crc ^ 0xffffffff) >>> 0
}

function encode(text: string): Uint8Array {
  return new TextEncoder().encode(text)
}

const PLUGIN = encode('{"name":"demo","version":"1.0.0"}')
const SKILL = encode('---\nname: demo\n---\n\n# Demo\n')
const MANIFEST = encode('{"entryAgent":"main"}')

describe('ycpsZipWriter', () => {
  it('writes a zip whose central directory sits directly before the EOCD', async () => {
    const zip = await buildZip([
      { path: 'plugin.json', bytes: PLUGIN },
      { path: 'skills/demo/SKILL.md', bytes: SKILL },
      { path: 'com.yuzhihuihe.ycps/manifest.json', bytes: MANIFEST },
    ])
    const parsed = parseZip(zip)

    // 后端 hasSymlinkEntry 用 `centralOffset + centralSize == eocdOffset` 判定结构合法，
    // 并且要求注释长度为 0（EOCD 必须正好落在文件末尾）。
    expect(parsed.commentLength).toBe(0)
    expect(parsed.centralOffset + parsed.centralSize).toBe(parsed.eocdOffset)
    expect(parsed.eocdOffset + 22).toBe(zip.length)
  })

  it('emits no ZIP64 sentinels, which the backend treats as unsafe', async () => {
    const zip = await buildZip([{ path: 'plugin.json', bytes: PLUGIN }])
    const parsed = parseZip(zip)

    // 0xFFFF / 0xFFFFFFFF 是 ZIP64 的哨兵值，后端一旦读到就直接判 symlink_or_unsupported_zip。
    expect(parsed.entries.length).toBe(1)
    expect(parsed.entries[0].compressedSize).not.toBe(0xffffffff)
    expect(parsed.entries[0].localOffset).not.toBe(0xffffffff)
  })

  it('declares the FAT platform so the backend skips the unix mode check', async () => {
    const zip = await buildZip([{ path: 'plugin.json', bytes: PLUGIN }])

    // 高字节 3=UNIX / 19=macOS 会触发文件类型位检查；FAT(0) 让那段逻辑整段跳过，
    // 因此不会因为模式位被误判成符号链接。
    expect(parseZip(zip).entries[0].versionMadeByPlatform).toBe(0)
  })

  it('falls back to STORE when no raw-deflate encoder is available', async () => {
    // jsdom 下通常没有 CompressionStream；显式摘掉它，锁住这条回退分支
    // —— 回退必须产出完整可读的 STORE 条目，而不是半截 deflate 流。
    const original = globalThis.CompressionStream
    Reflect.deleteProperty(globalThis as { CompressionStream?: unknown }, 'CompressionStream')
    try {
      const zip = await buildZip([{ path: 'skills/demo/SKILL.md', bytes: SKILL }])
      const parsed = parseZip(zip)
      const entry = parsed.entries[0]

      expect(entry.method).toBe(0)
      expect(entry.uncompressedSize).toBe(SKILL.length)
      expect(entry.compressedSize).toBe(SKILL.length)
      expect(entry.crc).toBe(expectedCrc32(SKILL))
      expect([...entryPayload(parsed, entry)]).toEqual([...SKILL])
    } finally {
      if (original) vi.stubGlobal('CompressionStream', original)
      vi.unstubAllGlobals()
    }
  })

  it('deflates compressible entries and stays readable through a standard inflater', async () => {
    if (typeof (globalThis as { CompressionStream?: unknown }).CompressionStream !== 'function') {
      // 环境没有压缩器时这条分支不存在，已由上一条用例覆盖
      expect(true).toBe(true)
      return
    }
    // 高度重复的正文才能体现压缩收益，也才可能触发 deflate 分支
    const repetitive = encode('---\nname: demo\n---\n\n' + '# Demo\n'.repeat(200))
    const zip = await buildZip([{ path: 'skills/demo/SKILL.md', bytes: repetitive }])
    const parsed = parseZip(zip)
    const entry = parsed.entries[0]

    expect(entry.method).toBe(8)
    expect(entry.compressedSize).toBeLessThan(repetitive.length)
    expect(entry.uncompressedSize).toBe(repetitive.length)
    expect(entry.crc).toBe(expectedCrc32(repetitive))
    // 用 node 自带的 inflateRaw 独立解压：证明写出的裸 deflate 流符合 ZIP 对 method 8 的要求
    // （如果误写成带 zlib 头的 deflate，这里会直接抛错）。
    const inflated = inflateRawSync(Buffer.from(entryPayload(parsed, entry)))
    expect([...inflated]).toEqual([...repetitive])
  })

  it('never sets the encryption flag or a data descriptor', async () => {
    const zip = await buildZip([{ path: 'plugin.json', bytes: PLUGIN }])
    const entry = parseZip(zip).entries[0]

    // 后端只接受 method 0/8 且要求 flags 的加密位为 0；同时 CRC 必须写在本地头里
    // （说明没走 data descriptor 模式，否则后端读到的 CRC 会是 0）。
    expect(entry.flags & 0x1).toBe(0)
    expect(entry.flags & 0x8).toBe(0)
    expect([0, 8]).toContain(entry.method)
    expect(entry.crc).toBe(expectedCrc32(PLUGIN))
  })

  it('sorts entries by path bytes so the same input always produces the same zip', async () => {
    const files = [
      { path: 'skills/demo/SKILL.md', bytes: SKILL },
      { path: 'plugin.json', bytes: PLUGIN },
      { path: 'com.yuzhihuihe.ycps/manifest.json', bytes: MANIFEST },
    ]
    const first = await buildZip(files)
    const second = await buildZip([...files].reverse())

    expect([...first]).toEqual([...second])
    expect(parseZip(first).entries.map((entry) => entry.name)).toEqual([
      'com.yuzhihuihe.ycps/manifest.json',
      'plugin.json',
      'skills/demo/SKILL.md',
    ])
  })

  it('preserves non-ascii file contents byte for byte', async () => {
    const chinese = encode('---\nname: demo\ndescription: 中文简介\n---\n\n正文\n')
    const zip = await buildZip([{ path: 'skills/demo/SKILL.md', bytes: chinese }])
    const entry = parseZip(zip).entries[0]

    expect(entry.crc).toBe(expectedCrc32(chinese))
    expect([...entryPayload(parseZip(zip), entry)]).toEqual([...chinese])
  })
})

describe('relativeEntriesFromFileList', () => {
  function file(name: string, relativePath: string): File {
    const created = new File(['x'], name)
    Object.defineProperty(created, 'webkitRelativePath', { value: relativePath })
    return created
  }

  it('strips the container directory so plugin.json lands at the package root', () => {
    const entries = relativeEntriesFromFileList([
      file('plugin.json', 'content-summarizer/plugin.json'),
      file('SKILL.md', 'content-summarizer/skills/content-summarizer/SKILL.md'),
      file('manifest.json', 'content-summarizer/com.yuzhihuihe.ycps/manifest.json'),
    ])

    // YCPS 要求 plugin.json 直接位于包根；用户选中的目录名不是包的一部分。
    expect(entries.map((entry) => entry.path)).toEqual([
      'plugin.json',
      'skills/content-summarizer/SKILL.md',
      'com.yuzhihuihe.ycps/manifest.json',
    ])
  })

  it('keeps deeper directories intact', () => {
    const entries = relativeEntriesFromFileList([
      file('agent.md', 'demo/com.yuzhihuihe.ycps/agents/agent.md'),
    ])

    // 只剥第一段；再往下的结构属于包内容，剥多了会让 embedded agents 找不到。
    expect(entries[0].path).toBe('com.yuzhihuihe.ycps/agents/agent.md')
  })

  it('drops directory entries and normalises windows separators', () => {
    const entries = relativeEntriesFromFileList([
      file('sub', 'demo/skills/'),
      file('SKILL.md', 'demo\\skills\\demo\\SKILL.md'),
    ])

    expect(entries.map((entry) => entry.path)).toEqual(['skills/demo/SKILL.md'])
  })
})

/**
 * 拖拽导入：`DataTransferItem.webkitGetAsEntry()` 的目录遍历。
 *
 * jsdom 没有 FileSystem API，所以这里按规范形状造出最小替身。
 * 重点验证两件容易写错的事：
 * - `readEntries()` 每次最多 100 条，必须循环读到空数组，否则大目录静默丢文件；
 * - 拖入的路径要剥掉最外层目录名，让 plugin.json 落在包根。
 */
describe('entriesFromDataTransfer', () => {
  /** 造一个「文件条目」替身，`file()` 回调给回 File。 */
  function fileEntry(name: string, content = 'x') {
    return {
      isFile: true,
      isDirectory: false,
      name,
      file: (onSuccess: (file: File) => void) => onSuccess(new File([content], name)),
    }
  }

  /**
   * 造一个「目录条目」替身；`batches` 用来模拟分页返回。
   *
   * 必须复刻真实 API 的一条契约：**批次用尽后要持续返回空数组**（而不是继续给旧数据），
   * 否则调用方的「读到空数组为止」会永远读不到终点。
   */
  function directoryEntry(name: string, batches: unknown[][]) {
    let call = 0
    return {
      isFile: false,
      isDirectory: true,
      name,
      createReader: () => ({
        readEntries: (onSuccess: (entries: unknown[]) => void) => {
          // 先取批次再递增：onSuccess 会同步触发下一次 readEntries，
          // 若把 call += 1 写在后面，游标永远停在 0，批次会被无限重复投喂。
          const batch = batches[call] ?? []
          call += 1
          onSuccess(batch)
        },
      }),
    }
  }

  function itemOf(entry: unknown) {
    return { kind: 'file', webkitGetAsEntry: () => entry, getAsFile: () => null }
  }

  it('walks a directory and keeps the nested structure', async () => {
    const dir = directoryEntry('content-summarizer', [
      [
        fileEntry('plugin.json', '{"name":"content-summarizer"}'),
        directoryEntry('skills', [[directoryEntry('content-summarizer', [[fileEntry('SKILL.md')]])]]),
      ],
    ])

    const entries = await entriesFromDataTransfer([itemOf(dir)] as unknown as DataTransferItem[])
    const paths = stripContainerDirectory(entries).map((entry) => entry.path).sort()

    expect(paths).toEqual(['plugin.json', 'skills/content-summarizer/SKILL.md'])
  })

  it('reads every batch instead of stopping at the first hundred entries', async () => {
    // 真实浏览器单次 readEntries 最多给 100 条；这里用两批模拟被截断的情形。
    const first = Array.from({ length: 100 }, (_, index) => fileEntry(`f${index}.md`))
    const second = [fileEntry('tail.md')]
    const dir = directoryEntry('demo', [first, second])

    const entries = await entriesFromDataTransfer([itemOf(dir)] as unknown as DataTransferItem[])

    // 少读一批就会是 100；必须是 101
    expect(entries).toHaveLength(101)
    expect(entries.map((entry) => entry.path)).toContain('demo/tail.md')
  })

  it('falls back to getAsFile when the filesystem api is missing', async () => {
    const plain = new File(['zip'], 'pkg.ycps.zip')
    const item = { kind: 'file', getAsFile: () => plain }

    const entries = await entriesFromDataTransfer([item] as unknown as DataTransferItem[])

    expect(entries.map((entry) => entry.path)).toEqual(['pkg.ycps.zip'])
  })

  it('captures every root before directory traversal yields', async () => {
    let releaseFirstDirectory!: (entries: unknown[]) => void
    const firstDirectoryBatch = new Promise<unknown[]>((resolve) => {
      releaseFirstDirectory = resolve
    })
    let secondRootCaptured = false
    const first = {
      isFile: false,
      isDirectory: true,
      name: 'first',
      createReader: () => ({
        readEntries: (onSuccess: (entries: unknown[]) => void) => {
          void firstDirectoryBatch.then(onSuccess)
        },
      }),
    }
    const second = fileEntry('second.txt')
    const pending = entriesFromDataTransfer([
      itemOf(first),
      {
        kind: 'file',
        webkitGetAsEntry: () => {
          secondRootCaptured = true
          return second
        },
        getAsFile: () => null,
      },
    ] as unknown as DataTransferItem[])

    expect(secondRootCaptured).toBe(true)
    releaseFirstDirectory([])
    await expect(pending).resolves.toMatchObject([{ path: 'second.txt' }])
  })

  it('leaves loose files untouched rather than guessing a container directory', () => {
    // 拖进来的是一堆散文件（没有目录层级）→ 不剥壳，交给后端报结构错误
    const entries = stripContainerDirectory([
      { path: 'plugin.json', file: new File(['x'], 'plugin.json') },
      { path: 'SKILL.md', file: new File(['x'], 'SKILL.md') },
    ])

    expect(entries.map((entry) => entry.path)).toEqual(['plugin.json', 'SKILL.md'])
  })
})
