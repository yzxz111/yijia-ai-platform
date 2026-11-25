import { readApiErrorCode } from '../../api/apiError'

const avatarUploadMessages: Record<string, string> = {
  avatar_file_required: '请选择头像文件。',
  avatar_file_too_large: '头像大小不能超过 2MB。',
  avatar_file_type_not_supported: '头像仅支持 JPG、PNG 或 GIF 图片。',
  avatar_file_format_mismatch: '图片格式与文件扩展名不一致，请重新保存为 JPG、PNG 或 GIF 后上传。',
  avatar_file_dimensions_exceeded: '头像宽度和高度不能超过 4096 像素。',
  avatar_file_invalid: '图片文件无法识别或已损坏，请重新选择。',
}

export function avatarUploadErrorMessage(error: unknown): string {
  const code = readApiErrorCode(error)
  return (code && avatarUploadMessages[code]) || '头像上传失败，请重试。'
}
