import type { SkillDetail, SkillFileContent } from '../api/skills'

/** 下载为 `<name>.skill.json` 时的正文（2 空格缩进，便于人读与 diff） */
export function skillJsonText(detail: SkillDetail): string {
  return JSON.stringify(detail, null, 2)
}

/** `encoding=base64` 的文件正文还原成原始字节 */
export function decodeBase64Content(content: string): Uint8Array {
  const binary = atob(content)
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index)
  }
  return bytes
}

/** 单个文件按 `encoding` 还原成下载用的 Blob */
export function skillFileBlob(file: SkillFileContent): Blob {
  if (file.encoding === 'base64') {
    return new Blob([decodeBase64Content(file.content)], { type: 'application/octet-stream' })
  }
  return new Blob([file.content], { type: 'text/plain;charset=utf-8' })
}

/** 取文件路径的 basename，避免带目录的 path 变成非法文件名 */
export function skillFileBaseName(path: string): string {
  const normalized = path.replace(/\\/gu, '/')
  const base = normalized.slice(normalized.lastIndexOf('/') + 1)
  return base && base !== '.' && base !== '..' ? base : 'skill-file'
}

/** 触发浏览器下载；jsdom 下没有 createObjectURL，需要调用方自行降级 */
export function triggerDownload(filename: string, blob: Blob): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.rel = 'noopener'
  anchor.style.display = 'none'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => {
    if (typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url)
  }, 0)
}

/** 整个 skill 的 JSON 快照 */
export function downloadSkillJson(name: string, detail: SkillDetail): void {
  triggerDownload(
    `${name}.skill.json`,
    new Blob([skillJsonText(detail)], { type: 'application/json;charset=utf-8' }),
  )
}

/** 单个文件：utf-8 原样文本，base64 还原成二进制 */
export function downloadSkillFile(detail: SkillDetail, file: SkillFileContent): void {
  triggerDownload(`${detail.name}-${skillFileBaseName(file.path)}`, skillFileBlob(file))
}

/** 文本文件才允许直接复制/预览正文 */
export function isTextFile(file: SkillFileContent): boolean {
  return file.encoding !== 'base64'
}

/**
 * 整包下载（A25）的文件名。
 *
 * 后端在 `Content-Disposition` 里给了 `<code>-<version>.ycps.zip`，正常情况下直接用；
 * 响应头缺失（或在测试替身里）时按同样的规则兜底，避免落到浏览器默认的 `download`。
 */
export function packageFileName(
  serverFileName: string | null | undefined,
  code: string,
  version: string,
): string {
  const provided = serverFileName?.trim()
  if (provided) return provided
  const safeCode = (code || 'skill').replace(/[\\/:*?"<>|]/gu, '-')
  const safeVersion = (version || '0').replace(/[\\/:*?"<>|]/gu, '-')
  return `${safeCode}-${safeVersion}.ycps.zip`
}

/** 触发整包下载；`blob` 由 `downloadAdminSkillPackage` 返回 */
export function downloadSkillPackageBlob(fileName: string, blob: Blob): void {
  triggerDownload(fileName, blob)
}

/**
 * 含图完整包（A26）的文件名，规则与 `packageFileName` 一致，只是扩展名不同。
 *
 * 外层是普通 ZIP（内层才是 `.ycps.zip`），所以用 `.bundle.zip` 与纯包区分开，
 * 避免管理员把外层包误当成可上传的 YCPS 包。
 */
export function packageBundleFileName(
  serverFileName: string | null | undefined,
  code: string,
  version: string,
): string {
  const provided = serverFileName?.trim()
  if (provided) return provided
  const safeCode = (code || 'skill').replace(/[\\/:*?"<>|]/gu, '-')
  const safeVersion = (version || '0').replace(/[\\/:*?"<>|]/gu, '-')
  return `${safeCode}-${safeVersion}.bundle.zip`
}

/**
 * 图片类资源（技能图标 / 介绍插画 / 示例图片 / 分类封面）在下载文件名里的角色后缀。
 *
 * 三者取字节的接口都不带扩展名（`/{id}/icon`、`/{id}/illustration`、
 * `/categories/{code}/image`），所以文件名要由前端拼：`<code>-<role>.<ext>`。
 */
export type SkillImageRole = 'icon' | 'illustration' | 'example' | 'cover'

/** `image/jpeg` → `jpg`；未知类型回退 `png`（后台只接受这四种格式） */
const IMAGE_EXTENSIONS: Record<string, string> = {
  'image/png': 'png',
  'image/jpeg': 'jpg',
  'image/jpg': 'jpg',
  'image/webp': 'webp',
  'image/svg+xml': 'svg',
}

/** 取文件名里不能出现的字符（与整包下载同一套规则） */
function safeFileSegment(value: string, fallback: string): string {
  const cleaned = value.trim().replace(/[\\/:*?"<>|]/gu, '-')
  return cleaned || fallback
}

/**
 * 从 blob 的 MIME 推断扩展名。
 *
 * 后端取图接口返回的 `Content-Type` 会带 `;charset=...`（SVG 尤其常见），
 * 所以先剥参数再查表。未知类型回退 `png` 而不是留空 —— 浏览器拿到
 * 无扩展名的文件名会当未知类型处理，双击打不开。
 */
export function imageExtensionForBlob(blob: Blob): string {
  const mime = (blob.type || '').split(';')[0]?.trim().toLowerCase() ?? ''
  return IMAGE_EXTENSIONS[mime] ?? 'png'
}

/** Keep an exported ClawHub icon beside its ZIP with the same filename stem. */
export function clawhubPackageIconFileName(zipFileName: string, icon: Blob): string {
  const baseName = skillFileBaseName(zipFileName).replace(/\.zip$/iu, '')
  return `${baseName}.${imageExtensionForBlob(icon)}`
}

/**
 * 拼图片下载文件名：`<code>-<role>.<ext>`。
 *
 * 用包编码（`code`）而非中文名，是因为编码是稳定标识：同一张图反复下载得到同一个
 * 文件名，管理员本地不会堆出 `xxx(1).png` 这种副本。
 */
export function skillImageFileName(code: string, role: SkillImageRole, blob: Blob): string {
  const safeCode = safeFileSegment(code, 'skill')
  return `${safeCode}-${role}.${imageExtensionForBlob(blob)}`
}

/** 触发图片下载；`blob` 由各自的取字节接口返回 */
export function downloadSkillImageBlob(fileName: string, blob: Blob): void {
  triggerDownload(fileName, blob)
}
