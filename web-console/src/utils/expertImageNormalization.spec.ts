import { describe, expect, it } from 'vitest'

import {
  EXPERT_IMAGE_NORMALIZATION_LIMITS,
  expertImageGeometry,
} from './expertImageNormalization'

describe('expertImageGeometry', () => {
  it('center-crops an icon to a square and caps it at 512px', () => {
    expect(expertImageGeometry('icon', 1200, 800)).toEqual({
      sourceX: 200,
      sourceY: 0,
      sourceWidth: 800,
      sourceHeight: 800,
      width: 512,
      height: 512,
    })
  })

  it('center-crops an illustration to 3:1 and caps it at 1440x480', () => {
    const geometry = expertImageGeometry('illustration', 1000, 1000)
    expect(geometry.sourceX).toBe(0)
    expect(geometry.sourceY).toBeCloseTo(333.3333333333333)
    expect(geometry.sourceWidth).toBe(1000)
    expect(geometry.sourceHeight).toBeCloseTo(333.3333333333333)
    expect(geometry.width).toBe(999)
    expect(geometry.height).toBe(333)
  })

  it('only scales an example down while preserving its aspect ratio', () => {
    const geometry = expertImageGeometry('exampleImage', 2880, 2160)
    expect(geometry.width).toBe(1440)
    expect(geometry.height).toBe(1080)
    expect(geometry.sourceWidth).toBe(2880)
    expect(geometry.sourceHeight).toBe(2160)
  })

  it('keeps the published byte budgets in one browser-side contract', () => {
    expect(EXPERT_IMAGE_NORMALIZATION_LIMITS.icon.bytes).toBe(128 * 1024)
    expect(EXPERT_IMAGE_NORMALIZATION_LIMITS.illustration.bytes).toBe(640 * 1024)
    expect(EXPERT_IMAGE_NORMALIZATION_LIMITS.exampleImage.bytes).toBe(512 * 1024)
  })
})
