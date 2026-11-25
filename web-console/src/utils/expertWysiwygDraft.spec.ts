import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

import {
  createExpertCanvas,
  createExpertWysiwygDraft,
  expertDraftFromAdmin,
  expertDraftToAdminFields,
  EXPERT_WYSIWYG_LIMITS,
  validateExpertWysiwygDraft,
} from './expertWysiwygDraft'

const aiLearningFixtureRoot = resolve(process.cwd(), '../fixtures/portable-import/ai-learning-assistant')

describe('AI Learning Assistant package admin projection', () => {
  it('hydrates the editor and saves bilingual canvas, table, rich runs and image roles without drift', () => {
    const archive = readFileSync(resolve(aiLearningFixtureRoot, 'ai-learning-assistant-1.0.0.ycps.zip'))
    expect(createHash('sha256').update(archive).digest('hex'))
      // 与 fixture README 及目录快照登记的原包字节保持一致。
      .toBe('38c104271e0e94a30fbcc7445d919bfa68019d79c5b84b71e551b58451ba4771')
    const profile = JSON.parse(readFileSync(resolve(aiLearningFixtureRoot, 'expected-admin-presentation.json'), 'utf8'))
    const draft = expertDraftFromAdmin(profile)

    expect(draft.title).toEqual({ zh: 'AI学习助手', en: 'AI Learning Assistant' })
    expect(draft.taskTypes.zh).toEqual(['学习计划', '主动回忆', '间隔复习', '错题复盘'])
    expect(draft.preview.zh.table?.headers).toEqual(['效率方法', '用时', '具体行动', '检查点'])
    expect(draft.preview.en.table?.headers).toEqual(['Technique', 'Time', 'Action', 'Check'])
    expect(draft.canvas.zh?.nodes.find(node => node.field === 'preview.heading')?.runs).toEqual([
      { text: '一句话', color: '#5574b5', fontSize: 22, fontWeight: 700 },
      { text: '，学得更高效', fontSize: 24, fontWeight: 700 },
    ])
    expect(draft.canvas.zh?.nodes.filter(node => node.field.startsWith('tag.'))
      .every(node => node.runs?.every(run => !run.color))).toBe(true)
    expect(draft.media.exampleImage.locale).toBe('zh')
    expect(draft.media.exampleImage.url).toBe('/v1/skills/ai-learning-assistant/example-image')
    expect(validateExpertWysiwygDraft(draft)).toEqual([])

    const saved = expertDraftToAdminFields(draft)
    expect(saved.preview?.table).toEqual(profile.preview.table)
    expect(saved.previewEn?.table).toEqual(profile.previewEn.table)
    expect(saved.canvas?.zh).toEqual(profile.canvas.zh)
    expect(saved.canvas?.en).toEqual(profile.canvas.en)
    expect(saved.exampleImageLocale).toBe('zh')
  })
})

describe('expertWysiwygDraft', () => {
  it('keeps the main canvas free of the independent feature image and fits output paragraphs', () => {
    const draft = createExpertWysiwygDraft({
      title: { zh: '专家', en: '' },
      description: { zh: '描述', en: '' },
      usageGuide: { zh: '说明', en: '' },
      taskTypes: { zh: ['内容'], en: [] },
      examplePrompts: { zh: [], en: [] },
      preview: {
        zh: {
          heading: '标题', scene: '', request: '请求', material: '', outputTitle: '输出',
          outputParagraphs: Array.from({ length: EXPERT_WYSIWYG_LIMITS.previewParagraphCount }, (_, index) => `段落 ${index + 1}`),
        },
        en: { heading: '', scene: '', request: '', material: '', outputTitle: '', outputParagraphs: [] },
      },
    })

    const canvas = createExpertCanvas(draft, 'zh')
    const outputTitle = canvas.nodes.find(node => node.field === 'preview.outputTitle')
    const paragraphs = canvas.nodes
      .filter(node => node.field.startsWith('preview.outputParagraphs.'))
      .sort((left, right) => left.y - right.y)

    expect(outputTitle).toEqual(expect.objectContaining({ x: 48, y: 330, width: 520, height: 28 }))
    expect(canvas.nodes.some(node => node.field === 'asset.exampleImage')).toBe(false)
    expect(paragraphs).toHaveLength(EXPERT_WYSIWYG_LIMITS.previewParagraphCount)
    paragraphs.forEach((node, index) => {
      expect(node.x).toBeGreaterThanOrEqual(0)
      expect(node.y).toBeGreaterThanOrEqual(0)
      expect(node.x + node.width).toBeLessThanOrEqual(600)
      expect(node.y + node.height).toBeLessThanOrEqual(704)
      if (index > 0) expect(node.y).toBeGreaterThanOrEqual(paragraphs[index - 1].y + paragraphs[index - 1].height)
    })
  })

  it('keeps the maximum task and prompt lists inside non-overlapping right-panel slots', () => {
    const draft = createExpertWysiwygDraft({
      title: { zh: '专家', en: '' },
      description: { zh: '描述', en: '' },
      usageGuide: { zh: '说明', en: '' },
      taskTypes: { zh: Array.from({ length: EXPERT_WYSIWYG_LIMITS.taskTypeCount }, (_, index) => `类型 ${index + 1}`), en: [] },
      examplePrompts: { zh: Array.from({ length: EXPERT_WYSIWYG_LIMITS.promptCount }, (_, index) => `示例 ${index + 1}`), en: [] },
      preview: {
        zh: { heading: '标题', scene: '', request: '请求', material: '', outputTitle: '输出', outputParagraphs: ['段落'] },
        en: { heading: '', scene: '', request: '', material: '', outputTitle: '', outputParagraphs: [] },
      },
    })

    const canvas = createExpertCanvas(draft, 'zh')
    const rightPanel = canvas.nodes.filter(node => node.x >= 600)
    rightPanel.forEach(node => {
      expect(node.x + node.width).toBeLessThanOrEqual(1080)
      expect(node.y + node.height).toBeLessThanOrEqual(704)
    })
    for (let left = 0; left < rightPanel.length; left += 1) {
      for (let right = left + 1; right < rightPanel.length; right += 1) {
        const first = rightPanel[left]
        const second = rightPanel[right]
        const overlaps = first.x < second.x + second.width
          && first.x + first.width > second.x
          && first.y < second.y + second.height
          && first.y + first.height > second.y
        expect(overlaps, `${first.field} overlaps ${second.field}`).toBe(false)
      }
    }
  })

  it('preserves an existing canvas on read/save without flattening rich text runs', () => {
    const draft = createExpertWysiwygDraft({
      title: { zh: '专家', en: '' },
      description: { zh: '描述', en: '' },
      usageGuide: { zh: '说明', en: '' },
      taskTypes: { zh: ['内容'], en: [] },
      examplePrompts: { zh: [], en: [] },
      preview: {
        zh: {
          heading: '标题', scene: '', request: '请求', material: '', outputTitle: '输出',
          outputParagraphs: ['第一段', '第二段'],
        },
        en: { heading: '', scene: '', request: '', material: '', outputTitle: '', outputParagraphs: [] },
      },
      canvas: {
        zh: {
          version: 1,
          width: 1080,
          height: 704,
          nodes: [
            {
              id: 'custom-heading',
              field: 'preview.heading',
              kind: 'text',
              x: 96,
              y: 18,
              width: 300,
              height: 24,
              runs: [{ text: '标题', color: '#123456' }],
            },
            {
              id: 'old-output',
              field: 'preview.outputParagraphs.0',
              kind: 'text',
              x: 48,
              y: 330,
              width: 520,
              height: 38,
              runs: [
                { text: '第一', color: '#ff0000', fontSize: 22 },
                { text: '段', italic: true },
              ],
            },
            { id: 'old-example-image', field: 'asset.exampleImage', kind: 'image', x: 32, y: 500, width: 560, height: 180, assetRole: 'exampleImage' },
          ],
        },
        en: null,
      },
    })

    const preserved = draft.canvas.zh!
    const customHeading = preserved.nodes.find(node => node.field === 'preview.heading')!
    const paragraph = preserved.nodes.find(node => node.field === 'preview.outputParagraphs.0')!
    expect(preserved.nodes.some(node => node.field === 'asset.exampleImage' || node.assetRole === 'exampleImage')).toBe(true)
    expect(preserved.nodes.find(node => node.field === 'preview.outputTitle')).toBeFalsy()
    expect(customHeading).toEqual(expect.objectContaining({ x: 96, y: 18, width: 300, height: 24 }))
    expect(customHeading.runs).toEqual([{ text: '标题', color: '#123456' }])
    expect(paragraph.runs).toEqual([
      { text: '第一', color: '#ff0000', fontSize: 22 },
      { text: '段', italic: true },
    ])
    expect(paragraph.y).toBe(330)

    const saved = expertDraftToAdminFields(draft)
    expect(saved.canvas?.zh?.nodes.some(node => node.field === 'asset.exampleImage')).toBe(true)
    expect(saved.canvas?.zh?.nodes.find(node => node.field === 'preview.outputTitle')).toBeFalsy()
    expect(saved.canvas?.zh?.nodes.find(node => node.field === 'preview.outputParagraphs.0')?.runs).toEqual(paragraph.runs)
  })

  it('preserves an existing canvas alongside semantic legacy pipe tables on save', () => {
    const draft = createExpertWysiwygDraft({
      title: { zh: 'AI办公助手', en: '' },
      description: { zh: '办公表格处理', en: '' },
      usageGuide: { zh: '说明', en: '' },
      taskTypes: { zh: ['表格汇总'], en: [] },
      examplePrompts: { zh: ['汇总考勤'], en: [] },
      preview: {
        zh: {
          heading: '一句话，理清表格',
          scene: '以跨表考勤汇总为例',
          request: '帮我汇总两张考勤表',
          material: '演示材料与应出勤数据',
          outputTitle: '考勤汇总与核对',
          outputParagraphs: [
            '单位：天',
            '姓名 | 应出勤 | 实际出勤 | 差额',
            '林然 | 22 | 21 | 1',
          ],
        },
        en: { heading: '', scene: '', request: '', material: '', outputTitle: '', outputParagraphs: [] },
      },
      // This is the malformed shape seen after an old editor tab saved AI Office.
      canvas: {
        zh: {
          version: 1,
          width: 1080,
          height: 704,
          nodes: [
            { id: 'raw-table', field: 'preview.outputParagraphs.1', kind: 'text', x: 48, y: 364, width: 520, height: 38, runs: [{ text: '姓名 | 应出勤 | 实际出勤 | 差额' }] },
            { id: 'duplicate-example', field: 'asset.exampleImage', kind: 'image', x: 32, y: 500, width: 560, height: 180, assetRole: 'exampleImage' },
          ],
        },
        en: null,
      },
    })

    const saved = expertDraftToAdminFields(draft)
    expect(saved.canvas?.zh?.nodes).toHaveLength(2)
    expect(saved.canvas?.zh?.nodes.some(node => node.field === 'asset.exampleImage')).toBe(true)
  })

  it('keeps a null canvas null for semantic table, sections, blocks, and intro previews', () => {
    const semanticPreviews = [
      { intro: { text: '先说明处理口径。' } },
      { table: { headers: ['姓名', '结果'], columnWidths: [50, 50], rows: [['林然', '通过']] } },
      { sections: [{ title: '结果', blocks: [{ text: '说明' }] }] },
      { blocks: [{ text: '说明' }] },
    ]

    semanticPreviews.forEach((semantic) => {
      const draft = createExpertWysiwygDraft({
        title: { zh: '专家', en: '' },
        description: { zh: '描述', en: '' },
        usageGuide: { zh: '说明', en: '' },
        taskTypes: { zh: ['内容'], en: [] },
        examplePrompts: { zh: ['示例'], en: [] },
        preview: {
          zh: {
            heading: '标题', scene: '', request: '请求', material: '', outputTitle: '输出', outputParagraphs: ['段落'],
            ...semantic,
          },
          en: { heading: '', scene: '', request: '', material: '', outputTitle: '', outputParagraphs: [] },
        },
        canvas: { zh: null, en: null },
      })

      expect(expertDraftToAdminFields(draft).canvas?.zh).toBeNull()
    })
  })

  it('round-trips the semantic table component and its visual style', () => {
    const value = createExpertWysiwygDraft({
      title: { zh: '表格专家', en: '' },
      description: { zh: '说明', en: '' },
      usageGuide: { zh: '指南', en: '' },
      taskTypes: { zh: ['汇总'], en: [] },
      examplePrompts: { zh: ['汇总考勤'], en: [] },
      preview: {
        zh: {
          heading: '考勤', scene: '', request: '两张表', material: '', outputTitle: '结果', outputParagraphs: [],
          intro: { text: '单位：天', tone: 'muted' },
          table: {
            headers: ['Name', 'Expected', 'Actual', 'Difference'],
            columnWidths: [27, 25, 25, 23],
            rows: [['Lin Ran', '22', '21', '1'], ['Total', '44', '43', '1']],
            totalRow: true,
            style: {
              fontSize: 15, color: '#303238', headerColor: '#6b6b6b',
              headerBackgroundColor: '#f0f0f0', borderColor: '#dfe3e8',
              borderRadius: 10, cellPadding: 8, rowHeight: 34,
            },
          },
        },
        en: { heading: '', scene: '', request: '', material: '', outputTitle: '', outputParagraphs: [] },
      },
    })

    const fields = expertDraftToAdminFields(value)
    expect(fields.preview?.table).toEqual(expect.objectContaining({
      totalRow: true,
      style: expect.objectContaining({ fontSize: 15, cellPadding: 8, rowHeight: 34 }),
    }))
    expect(fields.preview?.intro).toEqual({ text: '单位：天', tone: 'muted' })

    const restored = expertDraftFromAdmin({
      name: '表格专家',
      description: '说明',
      usageGuide: '指南',
      taskTypes: ['汇总'],
      examplePrompts: ['汇总考勤'],
      examplePreview: fields.preview!,
      canvas: fields.canvas,
    })
    expect(restored.preview.zh.intro).toEqual({ text: '单位：天', tone: 'muted' })
    expect(restored.preview.zh.table).toEqual(value.preview.zh.table)
    expect(restored.canvas.zh?.nodes.find(node => node.field === 'preview.table')).toBeUndefined()
  })

  it('omits null table style values instead of coercing them to zero', () => {
    const restored = expertDraftFromAdmin({
      name: '表格专家',
      description: '说明',
      usageGuide: '指南',
      taskTypes: ['汇总'],
      examplePrompts: [],
      examplePreview: {
        heading: '考勤', scene: '', request: '两张表', material: '', outputTitle: '结果', outputParagraphs: [],
        table: {
          headers: ['姓名', '差额'],
          columnWidths: [50, 50],
          rows: [['林然', '1']],
          style: { fontSize: null, borderRadius: null, cellPadding: null, rowHeight: null },
        },
      } as never,
    })

    expect(restored.preview.zh.table?.style).toBeUndefined()
    expect(validateExpertWysiwygDraft(restored).map(error => error.field)).not.toEqual(expect.arrayContaining([
      'preview.table.style.fontSize',
      'preview.table.style.borderRadius',
      'preview.table.style.cellPadding',
      'preview.table.style.rowHeight',
    ]))
  })

  it('rejects malformed table dimensions and style values before save', () => {
    const value = createExpertWysiwygDraft()
    value.preview.zh.table = {
      headers: ['A', 'B'], columnWidths: [70, 20], rows: [['']],
      style: { fontSize: 80, borderRadius: 40, cellPadding: -1, rowHeight: 0, color: 'red' },
    }
    const errors = validateExpertWysiwygDraft(value)
    expect(errors.map(error => error.field)).toEqual(expect.arrayContaining([
      'preview.table', 'preview.table.row', 'preview.table.style.fontSize',
      'preview.table.style.borderRadius', 'preview.table.style.cellPadding',
      'preview.table.style.rowHeight', 'preview.table.style',
    ]))
  })

  it('preserves both supported canvas overflow styles while normalizing a draft', () => {
    const draft = createExpertWysiwygDraft({
      title: { zh: '专家', en: '' },
      description: { zh: '描述', en: '' },
      usageGuide: { zh: '说明', en: '' },
      taskTypes: { zh: ['内容'], en: [] },
      examplePrompts: { zh: [], en: [] },
      preview: {
        zh: { heading: '标题', scene: '', request: '请求', material: '', outputTitle: '输出', outputParagraphs: ['段落'] },
        en: { heading: '', scene: '', request: '', material: '', outputTitle: '', outputParagraphs: [] },
      },
      canvas: {
        zh: {
          version: 1,
          width: 1080,
          height: 704,
          nodes: [
            { id: 'visible', field: 'preview.heading', kind: 'text', x: 32, y: 28, width: 560, height: 42, style: { overflow: 'visible' }, runs: [{ text: '标题' }] },
            { id: 'auto', field: 'preview.request', kind: 'card', x: 32, y: 116, width: 560, height: 110, style: { overflow: 'auto' }, runs: [{ text: '请求' }] },
          ],
        },
        en: null,
      },
    })

    const nodes = expertDraftToAdminFields(draft).canvas?.zh?.nodes ?? []
    expect(nodes.find(node => node.id === 'visible')?.style?.overflow).toBe('visible')
    expect(nodes.find(node => node.id === 'auto')?.style?.overflow).toBe('auto')
  })

  it('reads legacy tags and scalar preview fields without making them canonical', () => {
    const draft = expertDraftFromAdmin({
      name: '中文专家',
      nameEn: null,
      summary: '运营摘要',
      summaryEn: null,
      tags: ['写作'],
      tagsEn: ['Writing'],
      examplePreviewInput: '旧输入',
      examplePreviewOutput: '旧输出',
    })

    expect(draft.taskTypes.zh).toEqual(['写作'])
    expect(draft.taskTypes.en).toEqual(['Writing'])
    expect(draft.description.zh).toBe('运营摘要')
    expect(draft.preview.zh.request).toBe('旧输入')
    expect(draft.preview.zh.outputParagraphs).toEqual(['旧输出'])
  })

  it('defaults a legacy example image without a locale marker to Chinese and round-trips it', () => {
    const draft = expertDraftFromAdmin({
      name: '旧专家',
      exampleImageUrl: '/protected/example.webp',
    })

    expect(draft.media.exampleImage.locale).toBe('zh')
    expect(expertDraftToAdminFields(draft).exampleImageLocale).toBe('zh')

    const english = expertDraftFromAdmin({
      name: '新专家',
      exampleImageUrl: '/protected/example-en.webp',
      exampleImageLocale: 'en',
    })
    expect(english.media.exampleImage.locale).toBe('en')
    expect(expertDraftToAdminFields(english).exampleImageLocale).toBe('en')
  })

  it('clears the image locale with the image and preserves neutral images', () => {
    const neutral = createExpertWysiwygDraft()
    neutral.media.exampleImage = { url: 'blob:neutral', locale: 'neutral' }
    expect(expertDraftToAdminFields(neutral).exampleImageLocale).toBe('neutral')

    const cleared = createExpertWysiwygDraft()
    cleared.media.exampleImage = { url: '', locale: null }
    expect(expertDraftToAdminFields(cleared).exampleImageLocale).toBeNull()
  })

  it('does not promote the reserved YCPS operational tag into task types', () => {
    const draft = expertDraftFromAdmin({
      name: 'AI 办公助手',
      tags: ['ycps'],
      tagsEn: ['ycps'],
    })

    expect(draft.taskTypes).toEqual({ zh: [], en: [] })
  })

  it('only falls back to summary when description is absent, not when it is explicitly cleared', () => {
    const cleared = expertDraftFromAdmin({
      name: '专家',
      summary: '旧摘要',
      description: null,
      descriptionEn: '',
      summaryEn: 'Old summary',
    })

    expect(cleared.description.zh).toBe('')
    expect(cleared.description.en).toBe('')

    const omitted = expertDraftFromAdmin({
      name: '专家',
      summary: '旧摘要',
      summaryEn: 'Old summary',
      description: undefined,
      descriptionEn: undefined,
    })
    expect(omitted.description.zh).toBe('旧摘要')
    expect(omitted.description.en).toBe('Old summary')
  })

  it('treats explicit canonical null and empty values as clears instead of legacy fallbacks', () => {
    const draft = expertDraftFromAdmin({
      tags: ['旧标签'],
      tagsEn: ['Legacy tag'],
      taskTypes: null,
      taskTypesEn: null,
      examplePreview: null,
      examplePreviewEn: null,
      examplePreviewInput: '旧输入',
      examplePreviewInputEn: 'Legacy input',
      examplePreviewOutput: '旧输出',
      examplePreviewOutputEn: 'Legacy output',
    })

    expect(draft.taskTypes.zh).toEqual([])
    expect(draft.taskTypes.en).toEqual([])
    expect(draft.preview.zh.request).toBe('')
    expect(draft.preview.zh.outputParagraphs).toEqual([])
    expect(draft.preview.en.request).toBe('')
    expect(draft.preview.en.outputParagraphs).toEqual([])
  })

  it('prefers the dedicated preview shape when a legacy alias is also present', () => {
    const draft = expertDraftFromAdmin({
      examplePreview: { heading: '旧', request: '旧请求', outputTitle: '旧输出', outputParagraphs: ['旧'] },
      preview: { heading: '新', request: '新请求', outputTitle: '新输出', outputParagraphs: ['新'] },
    })

    expect(draft.preview.zh.heading).toBe('新')
    expect(draft.preview.zh.request).toBe('新请求')
  })

  it('writes structured task types and preview while retaining the old bridge fields', () => {
    const draft = createExpertWysiwygDraft({
      title: { zh: '专家', en: '' },
      description: { zh: '描述', en: '' },
      usageGuide: { zh: '说明', en: '' },
      taskTypes: { zh: ['内容'], en: ['Content'] },
      examplePrompts: { zh: ['请写一段文案'], en: [] },
      preview: {
        zh: {
          heading: '一句话',
          scene: '场景',
          request: '请求',
          material: '素材',
          outputTitle: '输出',
          outputParagraphs: ['第一段', '第二段'],
        },
        en: {
          heading: '', scene: '', request: '', material: '', outputTitle: '', outputParagraphs: [],
        },
      },
    })
    const fields = expertDraftToAdminFields(draft)

    expect(fields.taskTypes).toEqual(['内容'])
    expect(fields.taskTypesEn).toEqual(['Content'])
    expect(fields.preview).toEqual({
      heading: '一句话', scene: '场景', request: '请求', material: '素材',
      outputTitle: '输出', outputParagraphs: ['第一段', '第二段'],
    })
    expect(fields.previewEn).toBeNull()
    expect(fields.examplePreviewInput).toBe('请求')
    expect(fields.examplePreviewOutput).toBe('第一段\n第二段')
    expect(fields).not.toHaveProperty('tagsEn')
  })

  it('requires a complete Chinese canvas but leaves English optional', () => {
    const valid = createExpertWysiwygDraft({
      title: { zh: '专家', en: '' },
      description: { zh: '描述', en: '' },
      usageGuide: { zh: '说明', en: '' },
      taskTypes: { zh: ['内容'], en: [] },
      examplePrompts: { zh: ['示例'], en: [] },
      preview: {
        zh: {
          heading: '标题', scene: '', request: '请求', material: '', outputTitle: '输出', outputParagraphs: ['段落'],
        },
        en: {
          heading: '', scene: '', request: '', material: '', outputTitle: '', outputParagraphs: [],
        },
      },
    })
    expect(validateExpertWysiwygDraft(valid)).toEqual([])

    const invalid = createExpertWysiwygDraft(valid)
    invalid.taskTypes.zh = Array.from({ length: EXPERT_WYSIWYG_LIMITS.taskTypeCount + 1 }, () => '类型')
    invalid.examplePrompts.zh = ['x'.repeat(EXPERT_WYSIWYG_LIMITS.prompt + 1)]
    expect(validateExpertWysiwygDraft(invalid).map(error => error.field)).toEqual(expect.arrayContaining(['taskTypes', 'examplePrompts']))
  })

  it('rejects partially filled English previews and duplicate list values', () => {
    const draft = createExpertWysiwygDraft({
      title: { zh: '专家', en: '' },
      description: { zh: '描述', en: '' },
      usageGuide: { zh: '说明', en: '' },
      taskTypes: { zh: ['内容', '内容'], en: [] },
      examplePrompts: { zh: ['示例', '示例'], en: [] },
      preview: {
        zh: {
          heading: '标题', scene: '', request: '请求', material: '', outputTitle: '输出', outputParagraphs: ['段落'],
        },
        en: {
          heading: 'Example', scene: '', request: '', material: '', outputTitle: '', outputParagraphs: [],
        },
      },
    })

    const errors = validateExpertWysiwygDraft(draft)
    expect(errors).toEqual(expect.arrayContaining([
      expect.objectContaining({ field: 'taskTypes', locale: 'zh' }),
      expect.objectContaining({ field: 'examplePrompts', locale: 'zh' }),
      expect.objectContaining({ field: 'preview', locale: 'en' }),
    ]))
    expect(expertDraftToAdminFields(draft).previewEn).toEqual(expect.objectContaining({ heading: 'Example' }))
  })
})
