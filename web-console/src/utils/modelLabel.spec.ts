import { describe, expect, it } from 'vitest'

import { userModelLabel } from './modelLabel'

describe('userModelLabel', () => {
  it.each([
    ['text-basic', '文本模型'],
    ['vision-basic', '视觉模型'],
    ['file-basic', '文件模型'],
    ['multimodal-basic', '多模态模型'],
  ])('maps %s to %s', (code, label) => {
    expect(userModelLabel(code)).toBe(label)
  })

  it('preserves unknown model codes', () => {
    expect(userModelLabel('future-model')).toBe('future-model')
  })
})
