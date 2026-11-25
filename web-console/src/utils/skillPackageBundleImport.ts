/**
 * 识别管理台下载的「含图完整包」。
 *
 * 完整包是一个普通 ZIP，目录中包含一个未改动的 `.ycps.zip`，以及按
 * `<code>-icon|illustration|example.<ext>` 命名的图片。导入时必须把内层 YCPS
 * 交给既有的后端检查/上传接口，图片则暂存在新增表单里，创建记录后上传到各自端点。
 */

export type ImportedImageRole = 'icon' | 'illustration' | 'example'

export interface PreparedPackageImport {
  packageFile: File
  images: Partial<Record<ImportedImageRole, File>>
  completeBundle: boolean
}

interface DirectoryFile {
  path: string
  file: File
}

interface CentralEntry {
  path: string
  method: number
  flags: number
  compressedSize: number
  uncompressedSize: number
  localOffset: number
}

/** Small source files used only to identify a native ClawHub ZIP before server preview. */
export interface NativeSkillArchiveProbe {
  skillPath: string
  markdown: string | null
  origin: string | null
}

/**
 * Browsers append a duplicate counter in different places when a file already exists:
 * `foo (1).ycps.zip`, `foo.ycps(1).zip`, and repeated variants are all seen in practice.
 * The gateway intentionally accepts only the canonical `.ycps.zip` suffix, so normalize the
 * local name before handing a downloaded archive to the inspect/upload API.  Bytes are never
 * changed.
 */
export function normalizePackageFileName(name: string): string {
  let normalized = name
  // Handle unusual names with a counter both before `.ycps` and before `.zip`.
  for (let attempt = 0; attempt < 4; attempt += 1) {
    const next = normalized.replace(/(?:\s*\(\d+\))+(?=(?:\.ycps)?\.zip$)/giu, '')
    if (next === normalized) break
    normalized = next
  }
  return normalized
}

function isYcpsArchiveName(name: string): boolean {
  return /\.ycps(?:\s*\(\d+\))*\.zip$/iu.test(name)
    || /(?:\s*\(\d+\))+\.ycps\.zip$/iu.test(name)
}

function isBundleArchiveName(name: string): boolean {
  return /\.bundle(?:\s*\(\d+\))*\.zip$/iu.test(name)
}

/** Return the same bytes under the canonical upload name expected by the gateway. */
export function normalizePackageFile(
  file: File,
  name = normalizePackageFileName(file.name),
): File {
  if (name === file.name) return file
  return new File([file], name, { type: file.type || 'application/zip', lastModified: file.lastModified })
}

const MAX_ENTRY_BYTES = 220 * 1024 * 1024
const MAX_ENTRY_COUNT = 32
const IMAGE_NAME = /-(icon|illustration|example)\.(png|jpe?g|webp|svg)$/iu
const MIME_BY_EXTENSION: Record<string, string> = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  webp: 'image/webp',
  svg: 'image/svg+xml',
}

/** ZIP 文件入口。普通 YCPS 包原样返回；完整包则拆出内层包与规范图片。 */
export async function preparePackageArchive(file: File): Promise<PreparedPackageImport> {
  const sourceFileName = file.name
  file = normalizePackageFile(file)
  // YCPS 本身也是 ZIP，但规范文件名已经足以区分，避免为普通导入平白解压一次。
  if (isYcpsArchiveName(file.name)) return plainPackage(file)

  let entries: CentralEntry[]
  let bytes: Uint8Array
  try {
    bytes = await readFileBytes(file)
    entries = readCentralDirectory(bytes)
  } catch (error) {
    if (isBundleArchiveName(sourceFileName) || isBundleArchiveName(file.name)) {
      throw new Error('含图完整包无法解压，请重新下载后再试。', { cause: error })
    }
    return plainPackage(file)
  }

  const packageEntries = entries.filter((entry) => isYcpsArchiveName(baseName(entry.path)))
  if (packageEntries.length === 0) return plainPackage(file)
  if (packageEntries.length !== 1) {
    throw new Error('含图完整包中必须且只能包含一个 YCPS 包。')
  }

  const packageEntry = packageEntries[0]
  const folder = directoryName(packageEntry.path)
  const packageBytes = await extractEntry(bytes, packageEntry)
  const packageFile = new File([packageBytes as BlobPart], normalizePackageFileName(baseName(packageEntry.path)), {
    type: 'application/zip',
  })
  const images: Partial<Record<ImportedImageRole, File>> = {}
  for (const entry of entries) {
    if (directoryName(entry.path) !== folder) continue
    const classified = classifyImage(baseName(entry.path))
    if (!classified) continue
    if (images[classified.role]) {
      throw new Error(`含图完整包中存在多个${roleLabel(classified.role)}，请只保留一张。`)
    }
    const imageBytes = await extractEntry(bytes, entry)
    images[classified.role] = new File([imageBytes as BlobPart], baseName(entry.path), {
      type: classified.mimeType,
    })
  }
  return { packageFile, images, completeBundle: true }
}

/**
 * Probe a generic ZIP without changing its bytes. YCPS and complete bundles keep their
 * existing path; the gateway remains the authority for native-skill validation.
 */
export async function probeNativeSkillArchive(file: File): Promise<NativeSkillArchiveProbe | null> {
  if (/\.(?:ycps|bundle)\.zip$/iu.test(normalizePackageFileName(file.name))) return null
  // Match the native source endpoint's 50 MiB cap. Larger archives remain on
  // the ordinary import path and will be rejected by its own size check.
  if (file.size > 50 * 1024 * 1024) return null
  let archive: Uint8Array
  let entries: CentralEntry[]
  try {
    archive = await readFileBytes(file)
    entries = readCentralDirectory(archive, 4096)
  } catch {
    return null
  }
  const paths = entries.map(entry => entry.path)
  const skillPath = nativeSkillPath(paths)
  if (!skillPath) return null
  const slash = skillPath.lastIndexOf('/')
  const prefix = slash < 0 ? '' : skillPath.slice(0, slash + 1)
  const skillEntry = entries.find(entry => entry.path === skillPath)
  const originEntry = entries.find(entry => entry.path === `${prefix}.clawhub/origin.json`)
  return {
    skillPath,
    markdown: skillEntry ? await readSmallZipText(archive, skillEntry, 256 * 1024) : null,
    origin: originEntry ? await readSmallZipText(archive, originEntry, 64 * 1024) : null,
  }
}

/** `plugin.json` identifies an existing YCPS source tree, not a native Skill. */
export function nativeSkillPath(paths: string[]): string | null {
  const normalized = paths.map(original => ({
    original,
    path: original.replace(/\\/gu, '/').replace(/^\.\//u, ''),
  }))
  const skills = normalized.filter(({ path }) => isNativeSkillDocument(path))
  if (skills.length === 0) return null
  // YCPS has plugin.json at the package root and its entrypoint under skills/.
  // A native Skill may legitimately carry references/plugin.json as support data.
  const ycpsRoot = normalized
    .filter(({ path }) => path === 'plugin.json' || path.endsWith('/plugin.json'))
    .some((plugin) => {
      const slash = plugin.path.lastIndexOf('/')
      const prefix = slash < 0 ? '' : plugin.path.slice(0, slash + 1)
      return skills.some(({ path }) => path.startsWith(`${prefix}skills/`))
    })
  if (ycpsRoot) return null

  const origins = normalized
    .filter(({ path }) => path === '.clawhub/origin.json' || path.endsWith('/.clawhub/origin.json'))
    .map(({ path }) => path.slice(0, -'.clawhub/origin.json'.length))
  const roots = skills.filter(({ path }) => {
    const slash = path.lastIndexOf('/')
    const parent = slash < 0 ? '' : path.slice(0, slash + 1)
    if (origins.includes(parent)) return true

    const parentSegments = parent.split('/').filter(Boolean)
    if (parentSegments.some(segment => NATIVE_SKILL_SUPPORT_DIRECTORIES.has(segment.toLowerCase()))) {
      return false
    }
    // A single wrapping folder is common in downloaded ZIPs. Also allow the
    // canonical @owner/slug folder layout when origin.json is absent.
    return parentSegments.length === 0
      || parentSegments.length === 1
      || (parentSegments.length === 2 && parentSegments[0].startsWith('@'))
  })

  // Pick one candidate only as a harmless hint; never drop or rewrite source
  // bytes here. If a root carries both `skill.md` and `skills.md` (or multiple
  // roots), the gateway sees the complete archive and reports it as BLOCKED.
  return roots[0]?.original ?? null
}

const NATIVE_SKILL_SUPPORT_DIRECTORIES = new Set([
  'assets', 'docs', 'doc', 'examples', 'images', 'references', 'scripts', 'templates', 'test', 'tests',
])

function isNativeSkillDocument(path: string) {
  const fileName = path.slice(path.lastIndexOf('/') + 1).toLowerCase()
  return fileName === 'skill.md' || fileName === 'skills.md'
}

async function readSmallZipText(
  archive: Uint8Array,
  entry: CentralEntry,
  maxBytes: number,
): Promise<string | null> {
  if (entry.uncompressedSize > maxBytes) return null
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(await extractEntry(archive, entry))
  } catch {
    // Hint parsing must never replace the backend's complete archive validation.
    return null
  }
}

/**
 * 解压后的完整包目录入口。返回 null 表示目录本身就是 YCPS 源目录，应继续走原有打包逻辑。
 */
export function preparePackageDirectory(entries: DirectoryFile[]): PreparedPackageImport | null {
  const packageEntries = entries.filter((entry) => isYcpsArchiveName(baseName(entry.path)))
  if (packageEntries.length === 0) return null
  if (packageEntries.length !== 1) {
    throw new Error('含图完整包目录中必须且只能包含一个 YCPS 包。')
  }

  const selected = packageEntries[0]
  const folder = directoryName(selected.path)
  const images: Partial<Record<ImportedImageRole, File>> = {}
  for (const entry of entries) {
    if (directoryName(entry.path) !== folder) continue
    const classified = classifyImage(baseName(entry.path))
    if (!classified) continue
    if (images[classified.role]) {
      throw new Error(`含图完整包目录中存在多个${roleLabel(classified.role)}，请只保留一张。`)
    }
    images[classified.role] = withMimeType(entry.file, classified.mimeType)
  }
  return {
    packageFile: withMimeType(
      normalizePackageFile(selected.file),
      'application/zip',
      normalizePackageFileName(baseName(selected.path)),
    ),
    images,
    completeBundle: true,
  }
}

function plainPackage(file: File): PreparedPackageImport {
  return { packageFile: file, images: {}, completeBundle: false }
}

function withMimeType(file: File, type: string, name = file.name): File {
  if (file.type === type && file.name === name) return file
  return new File([file], name, { type, lastModified: file.lastModified })
}

function classifyImage(name: string): { role: ImportedImageRole; mimeType: string } | null {
  const match = IMAGE_NAME.exec(name)
  if (!match) return null
  return {
    role: match[1].toLowerCase() as ImportedImageRole,
    mimeType: MIME_BY_EXTENSION[match[2].toLowerCase()],
  }
}

function roleLabel(role: ImportedImageRole): string {
  if (role === 'icon') return '头像/图标'
  if (role === 'illustration') return '介绍插画'
  return '示例图片'
}

function baseName(path: string): string {
  return path.replace(/\\/gu, '/').split('/').filter(Boolean).at(-1) ?? ''
}

function directoryName(path: string): string {
  const normalized = path.replace(/\\/gu, '/')
  const slash = normalized.lastIndexOf('/')
  return slash < 0 ? '' : normalized.slice(0, slash)
}

function readCentralDirectory(bytes: Uint8Array, maxEntries = MAX_ENTRY_COUNT): CentralEntry[] {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const minimum = Math.max(0, bytes.length - 65_557)
  let eocd = -1
  for (let cursor = bytes.length - 22; cursor >= minimum; cursor -= 1) {
    if (view.getUint32(cursor, true) === 0x06054b50) {
      eocd = cursor
      break
    }
  }
  if (eocd < 0) throw new Error('zip_eocd_missing')
  const count = view.getUint16(eocd + 10, true)
  const centralSize = view.getUint32(eocd + 12, true)
  const centralOffset = view.getUint32(eocd + 16, true)
  if (count === 0xffff || centralSize === 0xffffffff || centralOffset === 0xffffffff) {
    throw new Error('zip64_not_supported')
  }
  if (count > maxEntries || centralOffset + centralSize > eocd) {
    throw new Error('zip_directory_invalid')
  }

  const decoder = new TextDecoder('utf-8', { fatal: true })
  const entries: CentralEntry[] = []
  const names = new Set<string>()
  let cursor = centralOffset
  for (let index = 0; index < count; index += 1) {
    if (cursor + 46 > bytes.length || view.getUint32(cursor, true) !== 0x02014b50) {
      throw new Error('zip_central_header_invalid')
    }
    const flags = view.getUint16(cursor + 8, true)
    const method = view.getUint16(cursor + 10, true)
    const compressedSize = view.getUint32(cursor + 20, true)
    const uncompressedSize = view.getUint32(cursor + 24, true)
    const nameLength = view.getUint16(cursor + 28, true)
    const extraLength = view.getUint16(cursor + 30, true)
    const commentLength = view.getUint16(cursor + 32, true)
    const localOffset = view.getUint32(cursor + 42, true)
    const end = cursor + 46 + nameLength + extraLength + commentLength
    if (end > bytes.length || (flags & 0x0001) !== 0 || ![0, 8].includes(method)) {
      throw new Error('zip_entry_invalid')
    }
    if (uncompressedSize > MAX_ENTRY_BYTES) throw new Error('zip_entry_too_large')
    const path = decoder.decode(bytes.subarray(cursor + 46, cursor + 46 + nameLength))
      .replace(/\\/gu, '/')
    if (!safePath(path.endsWith('/') ? path.slice(0, -1) : path) || names.has(path)) {
      throw new Error('zip_entry_path_invalid')
    }
    names.add(path)
    if (!path.endsWith('/')) {
      entries.push({ path, method, flags, compressedSize, uncompressedSize, localOffset })
    }
    cursor = end
  }
  return entries
}

function safePath(path: string): boolean {
  if (!path || path.startsWith('/') || /^[A-Za-z]:/u.test(path)) return false
  return path.split('/').every((part) => part !== '' && part !== '.' && part !== '..')
}

async function extractEntry(archive: Uint8Array, entry: CentralEntry): Promise<Uint8Array> {
  const view = new DataView(archive.buffer, archive.byteOffset, archive.byteLength)
  if (entry.localOffset + 30 > archive.length
    || view.getUint32(entry.localOffset, true) !== 0x04034b50) {
    throw new Error('zip_local_header_invalid')
  }
  const nameLength = view.getUint16(entry.localOffset + 26, true)
  const extraLength = view.getUint16(entry.localOffset + 28, true)
  const start = entry.localOffset + 30 + nameLength + extraLength
  const end = start + entry.compressedSize
  if (end > archive.length) throw new Error('zip_entry_truncated')
  const payload = archive.subarray(start, end)
  const result = entry.method === 0 ? payload.slice() : await inflateRaw(payload)
  if (result.length !== entry.uncompressedSize) throw new Error('zip_entry_size_mismatch')
  return result
}

async function inflateRaw(bytes: Uint8Array): Promise<Uint8Array> {
  if (typeof DecompressionStream !== 'function') {
    throw new Error('当前浏览器不支持解压含图完整包，请先解压后选择文件夹导入。')
  }
  const source = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(bytes)
      controller.close()
    },
  })
  const stream = source.pipeThrough(new DecompressionStream('deflate-raw' as CompressionFormat))
  const chunks: Uint8Array[] = []
  const reader = stream.getReader()
  let total = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    if (!value) continue
    total += value.length
    if (total > MAX_ENTRY_BYTES) throw new Error('zip_entry_too_large')
    chunks.push(value)
  }
  const merged = new Uint8Array(total)
  let offset = 0
  for (const chunk of chunks) {
    merged.set(chunk, offset)
    offset += chunk.length
  }
  return merged
}

async function readFileBytes(file: File): Promise<Uint8Array> {
  if (typeof file.arrayBuffer === 'function') return new Uint8Array(await file.arrayBuffer())
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer))
    reader.onerror = () => reject(reader.error ?? new Error('bundle_read_failed'))
    reader.readAsArrayBuffer(file)
  })
}
