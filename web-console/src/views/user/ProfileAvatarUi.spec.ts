import { describe, expect, it } from 'vitest'

import profileSource from './ProfileView.vue?raw'

describe('profile avatar UI', () => {
  it('lets the uploaded image cover the full avatar button', () => {
    expect(profileSource).toMatch(/\.profile-avatar\s*\{[^}]*width:\s*72px;[^}]*height:\s*72px;[^}]*padding:\s*0;[^}]*overflow:\s*hidden;/s)
    expect(profileSource).toMatch(/\.profile-avatar img\s*\{[^}]*width:\s*100%;[^}]*height:\s*100%;[^}]*object-fit:\s*cover;/s)
  })

  it('shows avatar failures only in the top message toast', () => {
    expect(profileSource).toContain("function showAvatarError(message: string)")
    expect(profileSource).toMatch(/function showAvatarError\(message: string\)\s*\{\s*errorMessage\.value = ''\s*ElMessage\.error\(message\)\s*\}/s)
    expect(profileSource).toContain("showAvatarError('头像仅支持 JPG、PNG 或 GIF 图片。')")
    expect(profileSource).toContain(': avatarUploadErrorMessage(error))')
  })

  it('compresses oversized avatars and exposes a processing state', () => {
    expect(profileSource).toContain('const prepared = await prepareAvatarForUpload(file)')
    expect(profileSource).toContain("prepared.compressed ? '头像已压缩并更新。' : '头像更新成功。'")
    expect(profileSource).toContain(':disabled="avatarUploading"')
    expect(profileSource).toContain(':aria-busy="avatarUploading"')
  })
})
