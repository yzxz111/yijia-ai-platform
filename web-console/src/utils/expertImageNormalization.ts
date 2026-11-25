import type { ExpertImageRole } from './expertWysiwygDraft'

export const EXPERT_IMAGE_NORMALIZATION_LIMITS: Record<
  ExpertImageRole,
  { bytes: number; width: number; height: number; ratio?: number }
> = {
  icon: { bytes: 128 * 1024, width: 512, height: 512 },
  illustration: { bytes: 640 * 1024, width: 1440, height: 480, ratio: 3 },
  exampleImage: { bytes: 512 * 1024, width: 1440, height: 1080 },
}

const SUPPORTED_MIME_TYPES = ['image/png', 'image/jpeg', 'image/webp'] as const
const QUALITY_STEPS = [0.92, 0.84, 0.76, 0.68, 0.6, 0.52, 0.44, 0.36]

export type ExpertImageGeometry = {
  sourceX: number
  sourceY: number
  sourceWidth: number
  sourceHeight: number
  width: number
  height: number
}

/**
 * Computes the deterministic center crop/scale used by the Expert editor.
 * This is intentionally pure so the contract can be tested without a browser canvas.
 */
export function expertImageGeometry(
  role: ExpertImageRole,
  sourceWidth: number,
  sourceHeight: number,
): ExpertImageGeometry {
  if (!Number.isFinite(sourceWidth) || !Number.isFinite(sourceHeight)
    || sourceWidth < 1 || sourceHeight < 1) {
    throw new Error('图片尺寸无效。')
  }
  const limit = EXPERT_IMAGE_NORMALIZATION_LIMITS[role]
  let cropWidth = sourceWidth
  let cropHeight = sourceHeight
  if (role === 'icon') {
    const side = Math.min(sourceWidth, sourceHeight)
    cropWidth = side
    cropHeight = side
  } else if (limit.ratio) {
    const sourceRatio = sourceWidth / sourceHeight
    if (sourceRatio > limit.ratio) cropWidth = sourceHeight * limit.ratio
    else if (sourceRatio < limit.ratio) cropHeight = sourceWidth / limit.ratio
  }

  const scale = Math.min(1, limit.width / cropWidth, limit.height / cropHeight)
  let width = Math.max(1, Math.floor(cropWidth * scale))
  let height = Math.max(1, Math.floor(cropHeight * scale))
  // The gateway validator intentionally requires an integer 3:1 illustration.  Flooring
  // the two dimensions independently would turn e.g. 1000x333.33 into 1000x333 (3.003:1).
  if (limit.ratio) {
    height = Math.max(1, Math.min(limit.height, Math.floor(cropHeight * scale)))
    width = height * limit.ratio
  }
  return {
    sourceX: (sourceWidth - cropWidth) / 2,
    sourceY: (sourceHeight - cropHeight) / 2,
    sourceWidth: cropWidth,
    sourceHeight: cropHeight,
    width,
    height,
  }
}

function outputName(file: File, mimeType: string): string {
  const base = file.name.replace(/\.[^.]+$/, '') || 'expert-image'
  const extension = mimeType === 'image/jpeg' ? 'jpg' : mimeType === 'image/webp' ? 'webp' : 'png'
  return `${base}.${extension}`
}

function decodeImage(file: File): Promise<{
  width: number
  height: number
  draw: CanvasImageSource
  close?: () => void
}> {
  const createBitmap = (globalThis as typeof globalThis & {
    createImageBitmap?: (source: Blob) => Promise<ImageBitmap>
  }).createImageBitmap
  if (createBitmap) {
    return createBitmap(file).then(bitmap => ({
      width: bitmap.width,
      height: bitmap.height,
      draw: bitmap,
      close: () => bitmap.close(),
    }))
  }
  if (typeof Image === 'undefined' || typeof URL.createObjectURL !== 'function') {
    return Promise.reject(new Error('当前浏览器无法处理图片，请更换浏览器后重试。'))
  }
  const url = URL.createObjectURL(file)
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => {
      URL.revokeObjectURL(url)
      resolve({ width: image.naturalWidth, height: image.naturalHeight, draw: image })
    }
    image.onerror = () => {
      URL.revokeObjectURL(url)
      reject(new Error('无法读取图片，请重新选择文件。'))
    }
    image.src = url
  })
}

function canvasBlob(canvas: HTMLCanvasElement, mimeType: string, quality?: number): Promise<Blob | null> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(blob => {
      // Unsupported encoders are allowed to return a PNG regardless of the requested type;
      // never relabel those bytes as WebP/JPEG because the server checks magic bytes.
      if (blob && blob.type === mimeType) resolve(blob)
      else if (blob) resolve(null)
      else reject(new Error('浏览器无法编码图片。'))
    }, mimeType, quality)
  })
}

function candidateTypes(original: string): string[] {
  const preferred = SUPPORTED_MIME_TYPES.includes(original as typeof SUPPORTED_MIME_TYPES[number])
    ? original
    : 'image/webp'
  // Preserve transparency whenever possible.  If PNG cannot meet the budget, WebP/JPEG
  // are deterministic fallbacks; the server still validates the final magic bytes.
  return [preferred, ...SUPPORTED_MIME_TYPES.filter(type => type !== preferred)]
}

/**
 * Center-crops/scales and re-encodes an Expert asset before it enters FormData.
 * The function never returns an over-budget file; callers should surface its error and keep
 * the previous draft asset selected.
 */
export async function normalizeExpertImage(file: File, role: ExpertImageRole): Promise<File> {
  if (!SUPPORTED_MIME_TYPES.includes(file.type as typeof SUPPORTED_MIME_TYPES[number])) {
    throw new Error('专家图片只支持 PNG、JPG 或 WebP 格式。')
  }
  const limit = EXPERT_IMAGE_NORMALIZATION_LIMITS[role]
  const image = await decodeImage(file)
  try {
    const geometry = expertImageGeometry(role, image.width, image.height)
    const canvas = document.createElement('canvas')
    canvas.width = geometry.width
    canvas.height = geometry.height
    const context = canvas.getContext('2d')
    if (!context) throw new Error('当前浏览器无法准备图片画布。')
    context.drawImage(
      image.draw,
      geometry.sourceX,
      geometry.sourceY,
      geometry.sourceWidth,
      geometry.sourceHeight,
      0,
      0,
      geometry.width,
      geometry.height,
    )

    for (const mimeType of candidateTypes(file.type)) {
      const qualities = mimeType === 'image/png' ? [undefined] : QUALITY_STEPS
      for (const quality of qualities) {
        const blob = await canvasBlob(canvas, mimeType, quality)
        if (blob && blob.size <= limit.bytes) {
          return new File([blob], outputName(file, mimeType), {
            type: mimeType,
            lastModified: file.lastModified || Date.now(),
          })
        }
      }
    }
    throw new Error(`${role === 'icon' ? '头像' : role === 'illustration' ? '横幅插画' : '功能示例图'}压缩后仍超过大小限制。`)
  } finally {
    image.close?.()
  }
}
