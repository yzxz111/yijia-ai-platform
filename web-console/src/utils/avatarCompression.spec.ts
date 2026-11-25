import imageCompression from 'browser-image-compression'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  AVATAR_MAX_SOURCE_BYTES,
  AVATAR_MAX_UPLOAD_BYTES,
  AvatarCompressionError,
  prepareAvatarForUpload,
} from './avatarCompression'

vi.mock('browser-image-compression', () => ({ default: vi.fn() }))

const compressionMock = vi.mocked(imageCompression)

function imageFile(name: string, type: string, size: number) {
  const file = new File(['image'], name, { type, lastModified: 123 })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

describe('prepareAvatarForUpload', () => {
  beforeEach(() => {
    compressionMock.mockReset()
  })

  it('keeps an image that already fits the upload limit', async () => {
    const file = imageFile('avatar.png', 'image/png', AVATAR_MAX_UPLOAD_BYTES)

    await expect(prepareAvatarForUpload(file)).resolves.toEqual({ file, compressed: false })
    expect(compressionMock).not.toHaveBeenCalled()
  })

  it('compresses an oversized JPG or PNG into a bounded JPEG', async () => {
    const source = imageFile('holiday.photo.png', 'image/png', AVATAR_MAX_UPLOAD_BYTES + 1)
    const compressed = new File([new Uint8Array(900_000)], 'holiday.photo.png', {
      type: 'image/jpeg',
    })
    compressionMock.mockResolvedValueOnce(compressed)

    const result = await prepareAvatarForUpload(source)

    expect(compressionMock).toHaveBeenCalledWith(source, expect.objectContaining({
      maxSizeMB: 1.8,
      maxWidthOrHeight: 1024,
      useWebWorker: true,
      fileType: 'image/jpeg',
    }))
    expect(result.compressed).toBe(true)
    expect(result.file.name).toBe('holiday.photo.jpg')
    expect(result.file.type).toBe('image/jpeg')
    expect(result.file.size).toBe(900_000)
  })

  it('does not flatten an oversized animated GIF', async () => {
    const file = imageFile('animated.gif', 'image/gif', AVATAR_MAX_UPLOAD_BYTES + 1)

    await expect(prepareAvatarForUpload(file))
      .rejects.toEqual(new AvatarCompressionError('GIF 动图大小不能超过 2MB。'))
    expect(compressionMock).not.toHaveBeenCalled()
  })

  it('rejects source images that are unsafe to decode in the browser', async () => {
    const file = imageFile('huge.jpg', 'image/jpeg', AVATAR_MAX_SOURCE_BYTES + 1)

    await expect(prepareAvatarForUpload(file))
      .rejects.toEqual(new AvatarCompressionError('原图大小不能超过 20MB。'))
  })

  it('rejects output that still exceeds the server limit', async () => {
    const source = imageFile('large.jpg', 'image/jpeg', AVATAR_MAX_UPLOAD_BYTES + 1)
    compressionMock.mockResolvedValueOnce(
      imageFile('large.jpg', 'image/jpeg', AVATAR_MAX_UPLOAD_BYTES + 1),
    )

    await expect(prepareAvatarForUpload(source))
      .rejects.toEqual(new AvatarCompressionError('图片压缩后仍超过 2MB，请选择尺寸更小的图片。'))
  })
})
