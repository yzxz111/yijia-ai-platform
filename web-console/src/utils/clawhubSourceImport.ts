import { nativeSkillPath, type NativeSkillArchiveProbe } from './skillPackageBundleImport'

export interface SourceFile {
  path: string
  file: File
}

export interface ClawhubIdentityHints {
  owner: string | null
  slug: string | null
  version: string | null
}

const OWNER = /^[a-z0-9][a-z0-9._-]*$/u
const SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/u

interface ClawhubSkillDetailResponse {
  skill?: {
    slug?: unknown
    displayName?: unknown
  } | null
  owner?: { handle?: unknown } | null
}

/**
 * Read the title explicitly chosen by the ClawHub publisher. The registry API
 * allows cross-origin reads; credentials are omitted and the fixed HTTPS host
 * prevents package metadata from choosing an arbitrary URL.
 *
 * A failed lookup is deliberately non-fatal: the gateway has safe package-local
 * fallbacks. Only accept a title when the response still identifies the exact
 * owner/slug requested, so a stale or unexpected API response cannot rename it.
 */
export async function fetchClawhubDisplayName(
  owner: string,
  slug: string,
  fetcher: typeof fetch = globalThis.fetch,
): Promise<string | null> {
  if (!validOwner(owner) || !validSlug(slug) || typeof fetcher !== 'function') return null
  const url = new URL(`https://clawhub.ai/api/v1/skills/${encodeURIComponent(slug)}`)
  url.searchParams.set('ownerHandle', owner)
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 5_000)
  try {
    const response = await fetcher(url, {
      headers: { Accept: 'application/json' },
      credentials: 'omit',
      redirect: 'error',
      signal: controller.signal,
    })
    if (!response.ok) return null
    const payload = await response.json() as ClawhubSkillDetailResponse
    const skill = payload?.skill
    if (skill?.slug !== slug || payload?.owner?.handle !== owner) return null
    if (typeof skill.displayName !== 'string') return null
    const displayName = skill.displayName.trim()
    return displayName
      && displayName.length <= 128
      && !/[\u0000-\u001f\u007f-\u009f]/u.test(displayName)
      ? displayName
      : null
  } catch {
    return null
  } finally {
    clearTimeout(timeout)
  }
}

/** A selected or dropped native directory is archived unchanged for gateway preview. */
export async function probeNativeSkillDirectory(
  entries: SourceFile[],
): Promise<NativeSkillArchiveProbe | null> {
  const skillPath = nativeSkillPath(entries.map(entry => entry.path))
  if (!skillPath) return null
  const slash = skillPath.lastIndexOf('/')
  const prefix = slash < 0 ? '' : skillPath.slice(0, slash + 1)
  const markdown = entries.find(entry => entry.path === skillPath)
  const origin = entries.find(entry => entry.path === `${prefix}.clawhub/origin.json`)
  return {
    skillPath,
    markdown: markdown ? await readSmallText(markdown.file, 256 * 1024) : null,
    origin: origin ? await readSmallText(origin.file, 64 * 1024) : null,
  }
}

/** Hints only: the server verifies origin, frontmatter, publisher and version. */
export function inferClawhubIdentity(
  probe: NativeSkillArchiveProbe | null,
  archiveName = '',
): ClawhubIdentityHints {
  if (!probe) return { owner: null, slug: fileStemSlug(archiveName), version: null }
  const origin = parseOrigin(probe.origin)
  const declared = parseFrontmatterHints(probe.markdown)
  const segments = probe.skillPath.split('/')
  const parent = segments.at(-2) ?? ''
  const grandparent = segments.at(-3) ?? ''
  const qualifiedOrigin = /^@([^/]+)\/([^/]+)$/u.exec(origin.slug ?? '')
  return {
    owner: validOwner(origin.ownerHandle)
      ?? validOwner(qualifiedOrigin?.[1])
      ?? validOwner(grandparent.startsWith('@') ? grandparent.slice(1) : null),
    slug: validSlug(qualifiedOrigin?.[2])
      ?? validSlug(origin.slug)
      ?? validSlug(declared.slug)
      ?? validSlug(parent)
      ?? validSlug(declared.name)
      ?? fileStemSlug(archiveName),
    version: origin.installedVersion ?? declared.version,
  }
}

function parseOrigin(text: string | null): Record<string, string | null> {
  if (!text) return {}
  try {
    const value: unknown = JSON.parse(text)
    if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
    const object = value as Record<string, unknown>
    return {
      ownerHandle: typeof object.ownerHandle === 'string' ? object.ownerHandle : null,
      slug: typeof object.slug === 'string' ? object.slug : null,
      installedVersion: typeof object.installedVersion === 'string' ? object.installedVersion : null,
    }
  } catch {
    return {}
  }
}

function parseFrontmatterHints(markdown: string | null): Record<string, string | null> {
  const header = /^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/u.exec(markdown ?? '')
  if (!header) return {}
  const result: Record<string, string | null> = {}
  for (const line of header[1].split(/\r?\n/u)) {
    const field = /^(name|slug|version):\s*(.*?)\s*$/u.exec(line)
    if (!field) continue
    let value = field[2]
    if ((value.startsWith('"') && value.endsWith('"'))
      || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1)
    }
    result[field[1]] = value || null
  }
  return result
}

function validOwner(value: string | null | undefined): string | null {
  return value && OWNER.test(value) ? value : null
}

function validSlug(value: string | null | undefined): string | null {
  return value && SLUG.test(value) ? value : null
}

function fileStemSlug(name: string): string | null {
  return validSlug(name.replace(/\.zip$/iu, ''))
}

function readSmallText(file: File, maxBytes: number): Promise<string | null> {
  if (file.size > maxBytes) return Promise.resolve(null)
  if (typeof file.text === 'function') return file.text().catch(() => null)
  return new Promise((resolve) => {
    const reader = new FileReader()
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : null)
    reader.onerror = () => resolve(null)
    reader.readAsText(file)
  })
}
