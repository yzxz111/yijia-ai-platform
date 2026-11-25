import { describe, expect, it } from 'vitest'

import { avatarUploadErrorMessage } from './avatarUploadError'

describe('avatarUploadErrorMessage', () => {
  it('explains when the declared image type does not match its content', () => {
    expect(avatarUploadErrorMessage({
      response: { data: { code: 'avatar_file_format_mismatch' } },
    })).toBe('图片格式与文件扩展名不一致，请重新保存为 JPG、PNG 或 GIF 后上传。')
  })

  it('maps each supported avatar validation code', () => {
    expect(avatarUploadErrorMessage({ response: { data: { code: 'avatar_file_too_large' } } }))
      .toBe('头像大小不能超过 2MB。')
    expect(avatarUploadErrorMessage({ response: { data: { code: 'avatar_file_dimensions_exceeded' } } }))
      .toBe('头像宽度和高度不能超过 4096 像素。')
    expect(avatarUploadErrorMessage({ response: { data: { code: 'avatar_file_invalid' } } }))
      .toBe('图片文件无法识别或已损坏，请重新选择。')
  })

  it('keeps a generic fallback for unavailable server details', () => {
    expect(avatarUploadErrorMessage(new Error('network unavailable')))
      .toBe('头像上传失败，请重试。')
  })
})
