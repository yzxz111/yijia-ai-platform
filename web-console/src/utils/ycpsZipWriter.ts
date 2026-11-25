/**
 * 浏览器侧的最小 ZIP 写入器（无第三方依赖）。
 *
 * 为什么需要它：管理后台要支持「选一个本地目录 → 直接导入 YCPS 包」，
 * 而浏览器 `File` 对象只能逐个读字节，没有现成的打包能力。后端只接受
 * `.ycps.zip`，所以目录必须先在前端打成 zip 才能走同一条解析链路。
 *
 * 约束全部来自后端 `SkillPackageInspector.hasSymlinkEntry()` 的校验：
 * - 不能出现 ZIP64（该函数遇到 EOCD 里的 0xFFFF/0xFFFFFFFF 哨兵值即判失败）；
 * - 中央目录必须紧接在 EOCD 之前（`centralOffset + centralSize == eocdOffset`）；
 * - 每个条目的 method 只能是 0（STORE）或 8（DEFLATE），且不得置加密标志位；
 * - 「version made by」的高字节若为 UNIX(3)/macOS(19)，会触发文件类型位检查。
 *   本实现统一写 FAT(0)，因此该检查直接跳过，不会因为文件模式位被误判成符号链接。
 *
 * 确定性：mtime 固定为 1980-01-01，条目按路径字节序排序。同一个目录重复打包
 * 得到逐字节相同的 zip，与 `scripts/ycps/build-package.mjs` 的产物语义一致。
 */

/** DOS 时间戳的固定基点（1980-01-01 00:00:00），保证打包结果可复现 */
const DOS_DATE_1980_01_01 = 0x0021
const DOS_TIME_MIDNIGHT = 0

/** method 0 = 原样存储；backend 允许 0 与 8 两种 */
const METHOD_STORE = 0
const METHOD_DEFLATE = 8

let crcTable: Uint32Array | null = null

function crc32Table(): Uint32Array {
  if (crcTable) return crcTable
  const table = new Uint32Array(256)
  for (let index = 0; index < 256; index += 1) {
    let value = index
    for (let bit = 0; bit < 8; bit += 1) {
      value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1
    }
    table[index] = value >>> 0
  }
  crcTable = table
  return table
}

function crc32(bytes: Uint8Array): number {
  const table = crc32Table()
  let crc = 0xffffffff
  for (let index = 0; index < bytes.length; index += 1) {
    crc = table[(crc ^ bytes[index]) & 0xff] ^ (crc >>> 8)
  }
  return (crc ^ 0xffffffff) >>> 0
}

/** `deflate-raw` 是否可用。jsdom 与老浏览器没有 CompressionStream，需要回退到 STORE。 */
function hasRawDeflate(): boolean {
  return typeof (globalThis as { CompressionStream?: unknown }).CompressionStream === 'function'
}

/**
 * raw deflate 压缩。返回 null 表示环境不支持，调用方应回退到 STORE。
 *
 * 用 `deflate-raw` 而不是 `deflate`：ZIP 条目存的是裸 deflate 流，
 * 带 zlib/gzip 头会让后端解压失败。
 *
 * 输入流手工构造而不是 `new Blob([bytes]).stream()`：jsdom 的 Blob 没有 `stream()`，
 * 测试环境里会直接 TypeError。ReadableStream 在浏览器与 Node 下都存在。
 */
async function deflateRaw(bytes: Uint8Array): Promise<Uint8Array | null> {
  if (!hasRawDeflate()) return null
  const source = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(bytes)
      controller.close()
    },
  })
  const compressed = source.pipeThrough(new CompressionStream('deflate-raw' as CompressionFormat))
  const chunks: Uint8Array[] = []
  const reader = compressed.getReader()
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    if (value) chunks.push(value)
  }
  return concat(chunks)
}

interface ZipEntryInput {
  /** 包内相对路径，必须用 POSIX `/`，且不能有前导 `/` */
  path: string
  bytes: Uint8Array
}

interface CentralRecord {
  pathBytes: Uint8Array
  crc: number
  compressedSize: number
  uncompressedSize: number
  method: number
  localOffset: number
}

/**
 * 把一组文件打成 ZIP。
 *
 * 条目按路径的 UTF-8 字节序排序，保证结果确定；同名或路径重复由调用方负责去重
 * （后端会用 `duplicate_entry_name` 拒绝，这里不静默改写）。
 */
export async function buildZip(files: ZipEntryInput[]): Promise<Uint8Array> {
  const encoder = new TextEncoder()
  const sorted = [...files].sort((left, right) =>
    compareBytes(encoder.encode(left.path), encoder.encode(right.path)),
  )

  const parts: Uint8Array[] = []
  const central: CentralRecord[] = []
  let offset = 0

  for (const file of sorted) {
    const pathBytes = encoder.encode(file.path)
    const crc = crc32(file.bytes)
    let method = METHOD_STORE
    let payload = file.bytes
    // 压缩后反而更大时保留原样，避免平白多出一段解压开销
    const compressed = await deflateRaw(file.bytes)
    if (compressed && compressed.length < file.bytes.length) {
      method = METHOD_DEFLATE
      payload = compressed
    }

    const header = new Uint8Array(30 + pathBytes.length)
    const view = new DataView(header.buffer)
    view.setUint32(0, 0x04034b50, true) // local file header signature
    view.setUint16(4, 20, true) // version needed to extract (2.0)
    view.setUint16(6, 0, true) // flags: 不加密、不做 data descriptor
    view.setUint16(8, method, true)
    view.setUint16(10, DOS_TIME_MIDNIGHT, true)
    view.setUint16(12, DOS_DATE_1980_01_01, true)
    view.setUint32(14, crc, true)
    view.setUint32(18, payload.length, true)
    view.setUint32(22, file.bytes.length, true)
    view.setUint16(26, pathBytes.length, true)
    view.setUint16(28, 0, true) // extra field length
    header.set(pathBytes, 30)

    parts.push(header, payload)
    central.push({
      pathBytes,
      crc,
      compressedSize: payload.length,
      uncompressedSize: file.bytes.length,
      method,
      localOffset: offset,
    })
    offset += header.length + payload.length
  }

  const centralStart = offset
  for (const record of central) {
    const header = new Uint8Array(46 + record.pathBytes.length)
    const view = new DataView(header.buffer)
    view.setUint32(0, 0x02014b50, true) // central directory header signature
    // version made by：高字节 0 = FAT，让后端的 Unix 文件类型位检查整段跳过
    view.setUint16(4, 20, true)
    view.setUint16(6, 20, true)
    view.setUint16(8, 0, true)
    view.setUint16(10, record.method, true)
    view.setUint16(12, DOS_TIME_MIDNIGHT, true)
    view.setUint16(14, DOS_DATE_1980_01_01, true)
    view.setUint32(16, record.crc, true)
    view.setUint32(20, record.compressedSize, true)
    view.setUint32(24, record.uncompressedSize, true)
    view.setUint16(28, record.pathBytes.length, true)
    view.setUint16(30, 0, true) // extra
    view.setUint16(32, 0, true) // comment
    view.setUint16(34, 0, true) // disk number start
    view.setUint16(36, 0, true) // internal attributes
    view.setUint32(38, 0, true) // external attributes：留 0，避免任何模式位被解读
    view.setUint32(42, record.localOffset, true)
    header.set(record.pathBytes, 46)
    parts.push(header)
    offset += header.length
  }

  const centralSize = offset - centralStart
  const eocd = new Uint8Array(22)
  const eocdView = new DataView(eocd.buffer)
  eocdView.setUint32(0, 0x06054b50, true) // end of central directory signature
  eocdView.setUint16(4, 0, true) // this disk
  eocdView.setUint16(6, 0, true) // disk with central directory
  eocdView.setUint16(8, central.length, true)
  eocdView.setUint16(10, central.length, true)
  eocdView.setUint32(12, centralSize, true)
  eocdView.setUint32(16, centralStart, true)
  eocdView.setUint16(20, 0, true) // comment length：必须为 0，EOCD 要正好落在文件末尾
  parts.push(eocd)

  return concat(parts)
}

function compareBytes(left: Uint8Array, right: Uint8Array): number {
  const limit = Math.min(left.length, right.length)
  for (let index = 0; index < limit; index += 1) {
    if (left[index] !== right[index]) return left[index] - right[index]
  }
  return left.length - right.length
}

function concat(parts: Uint8Array[]): Uint8Array {
  const total = parts.reduce((sum, part) => sum + part.length, 0)
  const merged = new Uint8Array(total)
  let cursor = 0
  for (const part of parts) {
    merged.set(part, cursor)
    cursor += part.length
  }
  return merged
}

/**
 * 目录选择（`<input webkitdirectory>`）拿到的文件列表转成包内相对路径。
 *
 * 浏览器给的 `webkitRelativePath` 形如 `content-summarizer/plugin.json`；
 * 剥壳规则与拖拽来源共用 {@link stripContainerDirectory}，避免两处漂移。
 */
export function relativeEntriesFromFileList(files: File[]): { path: string; file: File }[] {
  return stripContainerDirectory(
    files.map((file) => ({
      path: (file.webkitRelativePath || file.name).replace(/\\/gu, '/'),
      file,
    })),
  )
}

/** 逐文件读字节，供 `buildZip` 使用 */
export async function readEntryBytes(entries: { path: string; file: File }[]): Promise<ZipEntryInput[]> {
  return Promise.all(
    entries.map(async (entry) => ({
      path: entry.path,
      bytes: await readFileBytes(entry.file),
    })),
  )
}

/**
 * 从拖拽事件的 `DataTransfer` 里取出文件与目录。
 *
 * 拖拽拿不到 `webkitRelativePath`（那是 `<input webkitdirectory>` 专有的），
 * 必须走 `webkitGetAsEntry()` 递归遍历目录树自己拼相对路径。
 *
 * 两个容易踩的点：
 * 1. `dataTransfer.items` 只在事件回调**同步**阶段可读 —— 一旦 `await` 过，
 *    items 会被清空。所以调用方必须在 drop 处理器里立刻把 items 转成数组再传进来。
 * 2. `readEntries()` 每次最多返回 100 条，**必须循环读到返回空数组**为止，
 *    否则大目录会静默丢文件。
 *
 * 返回的相对路径带最外层目录名（与 `webkitRelativePath` 一致），
 * 由调用方交给 {@link stripContainerDirectory} 统一剥壳。
 */
export async function entriesFromDataTransfer(items: DataTransferItem[]): Promise<DroppedEntry[]> {
  // Resolve every root entry before the first await. Chromium can invalidate the
  // drag data store as soon as the drop callback yields; retaining only the
  // DataTransferItem objects is therefore insufficient for multi-root drops.
  const snapshots = items.map((item) => {
    const entry = typeof item.webkitGetAsEntry === 'function' ? item.webkitGetAsEntry() : null
    return {
      entry,
      file: entry ? null : item.getAsFile(),
    }
  })
  const roots: DroppedEntry[] = []
  for (const snapshot of snapshots) {
    const { entry } = snapshot
    if (!entry) {
      // 没有 FileSystem API 时退回普通文件（老浏览器/部分 Linux 环境）
      const { file } = snapshot
      if (file) roots.push({ path: file.name, file })
      continue
    }
    if (entry.isDirectory) {
      await walkDirectory(entry as FileSystemDirectoryEntry, entry.name, roots)
    } else {
      const file = await readFileEntry(entry as FileSystemFileEntry)
      if (file) roots.push({ path: entry.name, file })
    }
  }
  return roots
}

interface DroppedEntry {
  path: string
  file: File
}

function readFileEntry(entry: FileSystemFileEntry): Promise<File | null> {
  return new Promise((resolve) => {
    entry.file(
      (file) => resolve(file),
      () => resolve(null),
    )
  })
}

/**
 * 循环读目录直到空数组；单次 `readEntries` 只给 100 条。
 *
 * 用 `while` + 显式 Promise 包装而不是回调递归：真实环境回调是异步的、递归不会爆栈，
 * 但同步替身会直接把栈打穿，而且迭代写法本身更直白。
 */
async function readAllDirectoryEntries(reader: FileSystemDirectoryReader): Promise<FileSystemEntry[]> {
  const collected: FileSystemEntry[] = []
  for (;;) {
    const batch = await new Promise<FileSystemEntry[]>((resolve, reject) => {
      reader.readEntries(
        (entries) => resolve(entries),
        (error) => reject(error),
      )
    })
    // 读到空数组才算读完：单次调用最多给 100 条，提前停会静默丢文件
    if (batch.length === 0) return collected
    collected.push(...batch)
  }
}

async function walkDirectory(
  directory: FileSystemDirectoryEntry,
  prefix: string,
  sink: DroppedEntry[],
): Promise<void> {
  const children = await readAllDirectoryEntries(directory.createReader())
  for (const child of children) {
    const path = `${prefix}/${child.name}`
    if (child.isDirectory) {
      await walkDirectory(child as FileSystemDirectoryEntry, path, sink)
    } else {
      const file = await readFileEntry(child as FileSystemFileEntry)
      if (file) sink.push({ path, file })
    }
  }
}

/**
 * 剥掉最外层目录名，让 `plugin.json` 落在包根。
 *
 * 拖拽与 `<input webkitdirectory>` 两条来源都带最外层目录名，
 * 而 YCPS 要求 `plugin.json` 直接位于包根 —— 这正是
 * `scripts/ycps/build-package.mjs <package-root>` 的语义。
 *
 * 与 {@link relativeEntriesFromFileList} 共用同一套判定：只有当**存在嵌套路径**时
 * 才剥第一段。若拖进来的是一堆散文件（没有目录层级），原样保留，
 * 让后端的结构校验给出明确错误，而不是在这里静默猜。
 */
export function stripContainerDirectory(entries: DroppedEntry[]): { path: string; file: File }[] {
  const usable = entries.filter((entry) => entry.path && !entry.path.endsWith('/'))
  const hasContainerDirectory = usable.some((entry) => entry.path.includes('/'))
  return usable.map((entry) => ({
    // 只剥第一段；更深层的目录结构属于包内容，必须原样保留
    path: hasContainerDirectory ? entry.path.replace(/^[^/]+\//u, '') : entry.path,
    file: entry.file,
  }))
}

/**
 * 读取 `File` 的字节。
 *
 * 现代浏览器用 `Blob.arrayBuffer()`；jsdom（以及更老的 WebView）没有这个方法，
 * 因此回退到 `FileReader`。少了这条回退，组件测试里整条导入链路会以
 * 「`arrayBuffer` is not a function」失败，看起来像功能坏了。
 */
async function readFileBytes(file: File): Promise<Uint8Array> {
  if (typeof file.arrayBuffer === 'function') {
    return new Uint8Array(await file.arrayBuffer())
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer))
    reader.onerror = () => reject(reader.error ?? new Error('ycps_zip_read_failed'))
    reader.readAsArrayBuffer(file)
  })
}
