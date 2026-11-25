import { beforeEach, describe, expect, it, vi } from 'vitest'

const http = vi.hoisted(() => ({
  post: vi.fn(),
  put: vi.fn(),
}))

vi.mock('./http', () => ({
  gatewayHttp: http,
  platformHttp: {},
}))

import {
  createAdminExpertAggregate,
  updateAdminExpertAggregate,
  type ExpertAggregatePayload,
} from './admin'

const payload: ExpertAggregatePayload = {
  code: 'demo-expert',
  name: '演示专家',
  nameEn: null,
  category: null,
  tags: [],
  summary: '中文摘要',
  summaryEn: null,
  description: '中文详情',
  descriptionEn: null,
  icon: null,
  minClientVersion: null,
  sortWeight: 0,
  kind: 'EXPERT',
  taskTypes: ['写作'],
  taskTypesEn: [],
  usageGuide: '使用说明',
  usageGuideEn: null,
  examplePrompts: ['写一段介绍'],
  examplePromptsEn: [],
  preview: {
    heading: '示例', request: '请求', outputTitle: '输出', outputParagraphs: ['结果'],
  },
  previewEn: null,
  images: { icon: 'REPLACE', illustration: 'CLEAR', exampleImage: 'KEEP' },
}

function readBlob(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(reader.error)
    reader.readAsText(blob)
  })
}

describe('Expert aggregate admin API', () => {
  beforeEach(() => {
    http.post.mockReset().mockResolvedValue({ data: { skillId: 'expert-1' } })
    http.put.mockReset().mockResolvedValue({ data: { skillId: 'expert-1' } })
  })

  it('sends one multipart create body with JSON actions and replacement files', async () => {
    const icon = new File(['icon'], 'icon.png', { type: 'image/png' })
    await createAdminExpertAggregate(payload, { icon })

    expect(http.post).toHaveBeenCalledWith(
      '/console/gateway/admin/skills/experts',
      expect.any(FormData),
    )
    const body = http.post.mock.calls[0][1] as FormData
    const json = JSON.parse(await readBlob(body.get('data') as Blob))
    expect(json).toEqual(expect.objectContaining({
      code: 'demo-expert',
      images: { icon: 'REPLACE', illustration: 'CLEAR', exampleImage: 'KEEP' },
    }))
    expect(body.get('icon')).toBe(icon)
    expect(body.get('illustration')).toBeNull()
  })

  it('sends the expected row version and all image actions in one update body', async () => {
    const example = new File(['example'], 'example.webp', { type: 'image/webp' })
    await updateAdminExpertAggregate('expert-1', { ...payload, expectedRowVersion: 7 }, {
      exampleImage: example,
    })

    expect(http.put).toHaveBeenCalledWith(
      '/console/gateway/admin/skills/expert-1/expert-presentation',
      expect.any(FormData),
    )
    const body = http.put.mock.calls[0][1] as FormData
    const json = JSON.parse(await readBlob(body.get('data') as Blob))
    expect(json.expectedRowVersion).toBe(7)
    expect(json.images).toEqual({ icon: 'REPLACE', illustration: 'CLEAR', exampleImage: 'KEEP' })
    expect(body.get('exampleImage')).toBe(example)
  })
})
