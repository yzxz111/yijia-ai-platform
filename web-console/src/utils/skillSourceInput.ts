import {
  probeNativeSkillDirectory,
  type SourceFile,
} from './clawhubSourceImport'
import {
  normalizePackageFile,
  preparePackageArchive,
  preparePackageDirectory,
  probeNativeSkillArchive,
  type NativeSkillArchiveProbe,
} from './skillPackageBundleImport'
import {
  buildZip,
  entriesFromDataTransfer,
  readEntryBytes,
  stripContainerDirectory,
} from './ycpsZipWriter'

export type SkillSourceInputKind = 'zip' | 'folder'

export interface PreparedSkillSourceInput {
  file: File
  source: SkillSourceInputKind
  native: NativeSkillArchiveProbe | null
}

export type SkillSourceInputErrorCode =
  | 'empty_selection'
  | 'unsupported_file'
  | 'native_skill_not_allowed'
  | 'complete_bundle_not_supported'

export class SkillSourceInputError extends Error {
  readonly code: SkillSourceInputErrorCode

  constructor(code: SkillSourceInputErrorCode, message: string) {
    super(message)
    this.name = 'SkillSourceInputError'
    this.code = code
  }
}

/** Minimal shape accepted by tests as well as the browser's native DataTransfer. */
export interface SkillSourceDataTransfer {
  readonly items?: ArrayLike<DataTransferItem> | null
  readonly files?: ArrayLike<File> | null
}

/**
 * Prepare either a selected ZIP or a directory chosen with `webkitdirectory`.
 * The FileList is copied before any asynchronous work so callers may clear the
 * native input immediately after invoking this function.
 */
export function skillSourceFromFileSelection(
  files: FileList | readonly File[],
): Promise<PreparedSkillSourceInput> {
  return prepareSelectedFiles(Array.from(files))
}

/**
 * Prepare a desktop drop. Both lists are copied synchronously because the drag
 * data store is no longer readable after the drop callback yields.
 */
export function skillSourceFromDrop(
  dataTransfer: SkillSourceDataTransfer,
): Promise<PreparedSkillSourceInput> {
  const items = Array.from(dataTransfer.items ?? [])
  const files = Array.from(dataTransfer.files ?? [])
  return prepareDroppedSnapshot(items, files)
}

/** YCPS-only convenience used by detail imports; image bundles require the edit flow. */
export function packageFromFileSelection(files: FileList | readonly File[]): Promise<File> {
  return skillSourceFromFileSelection(files).then(requireYcpsPackage)
}

/** YCPS-only convenience used by detail imports; image bundles require the edit flow. */
export function packageFromDrop(dataTransfer: SkillSourceDataTransfer): Promise<File> {
  return skillSourceFromDrop(dataTransfer).then(requireYcpsPackage)
}

async function prepareSelectedFiles(files: File[]): Promise<PreparedSkillSourceInput> {
  if (files.length === 0) throw emptySelection()
  const hasDirectoryPath = files.some(file => Boolean(file.webkitRelativePath))
  if (files.length === 1 && !hasDirectoryPath) {
    if (!isZipName(files[0].name)) throw unsupportedFile()
    return prepareArchive(files[0])
  }
  return prepareDirectory(files.map(file => ({
    path: normalizePath(file.webkitRelativePath || file.name),
    file,
  })))
}

async function prepareDroppedSnapshot(
  items: DataTransferItem[],
  fallbackFiles: File[],
): Promise<PreparedSkillSourceInput> {
  if (items.length > 0) {
    const entries = await entriesFromDataTransfer(items)
    if (entries.length > 0) {
      const single = entries.length === 1 ? entries[0] : null
      if (single && !normalizePath(single.path).includes('/') && isZipName(single.file.name)) {
        return prepareArchive(single.file)
      }
      return prepareDirectory(entries)
    }
  }
  return prepareSelectedFiles(fallbackFiles)
}

async function prepareArchive(file: File): Promise<PreparedSkillSourceInput> {
  const normalized = normalizePackageFile(file)
  const native = await probeNativeSkillArchive(normalized)
  if (native) return { file: normalized, source: 'zip', native }

  const prepared = await preparePackageArchive(normalized)
  if (prepared.completeBundle) throw completeBundleNotSupported()
  return {
    file: canonicalYcpsFile(prepared.packageFile),
    source: 'zip',
    native: null,
  }
}

async function prepareDirectory(rawEntries: SourceFile[]): Promise<PreparedSkillSourceInput> {
  const normalized = rawEntries
    .map(entry => ({ path: normalizePath(entry.path), file: entry.file }))
    .filter(entry => entry.path && !entry.path.endsWith('/'))
  if (normalized.length === 0) throw emptySelection()

  const native = await probeNativeSkillDirectory(normalized)
  if (native) {
    const archive = await buildZip(await readEntryBytes(normalized))
    return {
      file: new File([archive as BlobPart], nativeDirectoryArchiveName(native, normalized), {
        type: 'application/zip',
      }),
      source: 'folder',
      native,
    }
  }

  const entries = stripContainerDirectory(normalized)
  if (entries.length === 0) throw emptySelection()
  // A root plugin.json identifies a YCPS source tree. It may legitimately
  // contain nested ZIP fixtures or assets whose names end in `.ycps.zip`;
  // package the whole tree and leave structural validation to the gateway.
  const hasRootPlugin = entries.some(entry => entry.path === 'plugin.json')
  const completeBundle = hasRootPlugin ? null : preparePackageDirectory(entries)
  if (completeBundle) {
    throw completeBundleNotSupported()
  }

  const archive = await buildZip(await readEntryBytes(entries))
  return {
    file: new File([archive as BlobPart], `${directoryArchiveStem(normalized)}.ycps.zip`, {
      type: 'application/zip',
    }),
    source: 'folder',
    native: null,
  }
}

function requireYcpsPackage(prepared: PreparedSkillSourceInput): File {
  if (prepared.native) {
    throw new SkillSourceInputError(
      'native_skill_not_allowed',
      '检测到原生 ClawHub 技能包；此入口仅支持 YCPS 包。',
    )
  }
  return prepared.file
}

function canonicalYcpsFile(file: File): File {
  const normalized = normalizePackageFile(file)
  if (/\.ycps\.zip$/iu.test(normalized.name)) {
    if (normalized.type === 'application/zip') return normalized
    return new File([normalized], normalized.name, {
      type: 'application/zip',
      lastModified: normalized.lastModified,
    })
  }

  const stem = safeFileStem(normalized.name.replace(/\.zip$/iu, ''))
  return new File([normalized], `${stem}.ycps.zip`, {
    type: 'application/zip',
    lastModified: normalized.lastModified,
  })
}

function directoryArchiveStem(entries: SourceFile[]): string {
  const first = entries[0]?.path.split('/')[0] ?? ''
  const commonContainer = first
    && entries.every(entry => entry.path.startsWith(`${first}/`))
    ? first
    : 'package'
  return safeFileStem(commonContainer)
}

/**
 * Keep the selected native directory's basename as the archive stem. It is a
 * meaningful fallback for packages without origin.json or frontmatter slug.
 * For the canonical `@owner/slug/SKILL.md` layout the Skill directory is
 * `slug`, while the ZIP entries still retain the complete owner-qualified path.
 */
function nativeDirectoryArchiveName(
  probe: NativeSkillArchiveProbe,
  entries: SourceFile[],
): string {
  const skillPath = normalizePath(probe.skillPath)
  const slash = skillPath.lastIndexOf('/')
  if (slash >= 0) {
    const directory = skillPath.slice(0, slash).split('/').filter(Boolean).at(-1)
    if (directory) return `${safeFileStem(directory)}.zip`
  }

  const first = entries[0]?.path.split('/')[0] ?? ''
  if (first && entries.every(entry => entry.path.startsWith(`${first}/`))) {
    return `${safeFileStem(first)}.zip`
  }
  // A loose root-level SKILL.md has no recoverable directory name. Preserve
  // the historical neutral filename so callers do not mistake it for a slug.
  return 'clawhub-folder.zip'
}

function safeFileStem(value: string): string {
  const safe = value
    .replace(/[<>:"/\\|?*\u0000-\u001f]/gu, '-')
    .replace(/[. ]+$/gu, '')
    .trim()
  return safe || 'package'
}

function normalizePath(path: string): string {
  return path.replace(/\\/gu, '/').replace(/^\.\//u, '')
}

function isZipName(name: string): boolean {
  return /\.zip$/iu.test(name)
}

function emptySelection(): SkillSourceInputError {
  return new SkillSourceInputError(
    'empty_selection',
    '没有可导入的文件。请选择或拖入一个 .ycps.zip，或包含 plugin.json 的包目录。',
  )
}

function unsupportedFile(): SkillSourceInputError {
  return new SkillSourceInputError(
    'unsupported_file',
    '请选择 ZIP 文件，或选择一个完整的包目录。',
  )
}

function completeBundleNotSupported(): SkillSourceInputError {
  return new SkillSourceInputError(
    'complete_bundle_not_supported',
    '含图完整包不能在此直接导入，请选择标准 YCPS 包或源目录，避免包外图片遗漏。',
  )
}
