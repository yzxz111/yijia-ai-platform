import imageCompression from 'browser-image-compression'

export const AVATAR_MAX_UPLOAD_BYTES = 2 * 1024 * 1024
export const AVATAR_MAX_SOURCE_BYTES = 20 * 1024 * 1024

const AVATAR_TARGET_SIZE_MB = 1.8
const AVATAR_MAX_EDGE = 1024

export class AvatarCompressionError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'AvatarCompressionError'
  }
}

export interface PreparedAvatar {
  file: File
  compressed: boolean
}

export async function prepareAvatarForUpload(file: File): Promise<PreparedAvatar> {
  if (file.size <= AVATAR_MAX_UPLOAD_BYTES) return { file, compressed: false }
  if (file.type === 'image/gif') {
    throw new AvatarCompressionError('GIF 动图大小不能超过 2MB。')
  }
  if (file.size > AVATAR_MAX_SOURCE_BYTES) {
    throw new AvatarCompressionError('原图大小不能超过 20MB。')
  }

  let compressed: File
  try {
    compressed = await imageCompression(file, {
      maxSizeMB: AVATAR_TARGET_SIZE_MB,
      maxWidthOrHeight: AVATAR_MAX_EDGE,
      useWebWorker: true,
      fileType: 'image/jpeg',
      initialQuality: 0.88,
      preserveExif: false,
    })
  } catch {
    throw new AvatarCompressionError('图片压缩失败，请选择其他图片重试。')
  }

  if (compressed.size > AVATAR_MAX_UPLOAD_BYTES) {
    throw new AvatarCompressionError('图片压缩后仍超过 2MB，请选择尺寸更小的图片。')
  }

  const baseName = file.name.replace(/\.[^.]+$/, '') || 'avatar'
  return {
    file: new File([compressed], `${baseName}.jpg`, {
      type: 'image/jpeg',
      lastModified: file.lastModified,
    }),
    compressed: true,
  }
}
