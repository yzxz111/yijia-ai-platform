import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'

import ExpertWysiwygEditor from './ExpertWysiwygEditor.vue'
import ExpertDesktopPreview from './ExpertDesktopPreview.vue'
import { createExpertCanvas, createExpertWysiwygDraft, expertDraftFromAdmin } from '../../utils/expertWysiwygDraft'

const editorSource = readFileSync(resolve(process.cwd(), 'src/components/admin/ExpertWysiwygEditor.vue'), 'utf8')
const desktopPreviewSource = readFileSync(resolve(process.cwd(), 'src/components/admin/ExpertDesktopPreview.vue'), 'utf8')

const aiLearningAdminProjection = JSON.parse(readFileSync(resolve(
  process.cwd(), '../fixtures/portable-import/ai-learning-assistant/expected-admin-presentation.json',
), 'utf8'))

function draft() {
  return createExpertWysiwygDraft({
    title: { zh: '中文专家', en: 'English Expert' },
    description: { zh: '中文描述', en: 'English description' },
    usageGuide: { zh: '中文说明', en: 'English guide' },
    taskTypes: { zh: ['内容创作'], en: ['Writing'] },
    examplePrompts: { zh: ['写一篇文案'], en: ['Write a draft'] },
    preview: {
      zh: {
        heading: '一句话，开始创作', scene: '', request: '写一篇文案', material: '', outputTitle: '示例输出', outputParagraphs: ['输出'],
      },
      en: {
        heading: 'Start creating', scene: '', request: 'Write a draft', material: '', outputTitle: 'Output', outputParagraphs: ['Result'],
      },
    },
  })
}

describe('ExpertWysiwygEditor', () => {
  it('shows the real AI Learning package projection in both editor languages and the live preview', async () => {
    const wrapper = mount(ExpertWysiwygEditor, {
      props: { modelValue: expertDraftFromAdmin(aiLearningAdminProjection) },
    })
    expect(wrapper.text()).toContain('AI学习助手')
    expect(wrapper.text()).toContain('效率方法')
    expect(wrapper.get('[data-test="expert-preview-example-image"] img').attributes('src'))
      .toBe('/v1/skills/ai-learning-assistant/example-image')
    const prefix = wrapper.get('[data-test="expert-desktop-preview"] .expert-desktop-preview__heading-prefix')
    expect(prefix.text()).toBe('一句话')
    expect(prefix.attributes('style')).toContain('color: rgb(85, 116, 181)')

    await wrapper.findAll('[data-test="expert-wysiwyg-language"]')[1].trigger('click')
    expect(wrapper.text()).toContain('AI Learning Assistant')
    expect(wrapper.text()).toContain('Technique')
    expect(wrapper.find('[data-test="expert-preview-example-image"]').exists()).toBe(false)
  })

  it('renders a 1080x704 canvas with editable blocks and keeps footer read-only', () => {
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: draft() } })
    expect(wrapper.findAll('[data-test="expert-wysiwyg-canvas"]')).not.toHaveLength(0)
    expect(wrapper.get('[data-test="expert-desktop-preview"]')).toBeTruthy()
    expect(wrapper.findAll('[data-block="1"]').length).toBe(1)
    expect(wrapper.findAll('[data-block="2"]').length).toBe(1)
    expect(wrapper.findAll('[data-block="3"]').length).toBe(1)
    expect(wrapper.findAll('[data-block="4"]').length).toBe(1)
    expect(wrapper.findAll('[data-block="5"]').length).toBe(1)
    expect(wrapper.findAll('[data-block="6"]').length).toBe(1)
    expect(wrapper.findAll('[data-block="7"]').length).toBe(1)
    expect(wrapper.findAll('[data-block="8"]').length).toBe(2)
    expect(wrapper.find('[data-test="expert-preview-example-image"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="expert-block-example-image"]').exists()).toBe(false)
    expect(wrapper.findAll('.expert-wysiwyg__footer [disabled]')).not.toHaveLength(0)
  })

  it('adds a semantic table, edits cells, and updates the read-only desktop preview', async () => {
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: draft() } })

    await wrapper.get('[data-test="expert-add-table"]').trigger('click')
    let updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.preview.zh.table).toEqual(expect.objectContaining({
      headers: ['姓名', '应出勤', '实际出勤', '差额'],
      totalRow: true,
    }))
    expect(updated.canvas.zh?.nodes.find(node => node.field === 'preview.table')).toEqual(expect.objectContaining({ kind: 'table' }))

    await wrapper.setProps({ modelValue: updated })
    expect(wrapper.find('[data-test="expert-desktop-preview"] table').exists()).toBe(true)
    expect(wrapper.get('[data-test="expert-desktop-preview"]').text()).toContain('林然')

    await wrapper.get('[aria-label="第 1 行第 1 列"]').setValue('Lin Ran')
    updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.preview.zh.table?.rows[0][0]).toBe('Lin Ran')

    await wrapper.setProps({ modelValue: updated })
    await wrapper.get('[data-test="expert-add-table-row"]').trigger('click')
    updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.preview.zh.table?.rows).toHaveLength(4)

    await wrapper.setProps({ modelValue: updated })
    await wrapper.get('[data-test="expert-remove-table-row"]').trigger('click')
    updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.preview.zh.table?.rows).toHaveLength(3)
  })

  it('edits the structured output intro as rich text while preserving its tone', async () => {
    const value = draft()
    value.preview.zh.intro = { text: '已确认两张表。', tone: 'muted' }
    value.canvas.zh = createExpertCanvas(value, 'zh')
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value }, attachTo: document.body })

    const intro = wrapper.get('[contenteditable="true"][aria-label="示例输出导语"]')
    expect(intro.attributes('data-tone')).toBeUndefined()
    expect(wrapper.find('[data-test="expert-block-preview-output"] [data-tone="muted"]').exists()).toBe(true)
    intro.element.textContent = '已确认三张表。'
    await intro.trigger('input')

    let updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.preview.zh.intro).toEqual({ text: '已确认三张表。', tone: 'muted' })
    expect(updated.canvas.zh?.nodes.find(node => node.field === 'preview.intro')?.runs).toEqual([{ text: '已确认三张表。' }])

    await wrapper.setProps({ modelValue: updated })
    await nextTick()
    const introAfter = wrapper.get('[contenteditable="true"][aria-label="示例输出导语"]')
    ;(introAfter.element as HTMLElement).focus()
    const textNode = introAfter.element.firstChild?.firstChild
    if (!textNode) throw new Error('missing intro text')
    const range = document.createRange()
    range.setStart(textNode, 0)
    range.setEnd(textNode, 2)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
    await introAfter.trigger('mouseup')
    await introAfter.trigger('keyup')
    expect((wrapper.get('[data-test="expert-apply-font-color"]').element as HTMLButtonElement).disabled).toBe(false)
    await wrapper.get('[data-test="expert-font-color"]').setValue('#dc2626')
    updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.preview.zh.intro?.tone).toBe('muted')
    expect(updated.canvas.zh?.nodes.find(node => node.field === 'preview.intro')?.runs).toEqual([
      { text: '已确', color: '#dc2626' },
      { text: '认三张表。' },
    ])
  })

  it('keeps the editor row limit enforced at 32 rows', async () => {
    const value = draft()
    value.preview.zh.table = {
      headers: ['列'], columnWidths: [100], rows: Array.from({ length: 32 }, (_, index) => [`第 ${index + 1} 行`]),
    }
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value } })
    const addRow = wrapper.get('[data-test="expert-add-table-row"]')

    expect(addRow.attributes('disabled')).toBeDefined()
    await addRow.trigger('click')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(value.preview.zh.table.rows).toHaveLength(32)
  })

  it('removes upload controls from the tab order when the editor is disabled', () => {
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: draft(), disabled: true } })
    for (const selector of ['[data-test="expert-block-illustration"]', '[data-test="expert-block-icon"]']) {
      const block = wrapper.get(selector)
      expect(block.attributes('tabindex')).toBe('-1')
      expect(block.attributes('aria-disabled')).toBe('true')
    }
  })

  it('localizes the final preview UI and limits desktop prompts to two', async () => {
    const value = draft()
    value.examplePrompts.en = ['one', 'two', 'three']
    const wrapper = mount(ExpertDesktopPreview, { props: { modelValue: value, locale: 'en' } })

    expect(wrapper.text()).toContain('Desktop final result')
    expect(wrapper.text()).toContain('Before you start, tell it this')
    expect(wrapper.text()).toContain('Start conversation')
    expect(wrapper.text()).not.toContain('three')
    expect(wrapper.find('img[alt="Introduction illustration"]').exists()).toBe(false)
  })

  it('renders structured and legacy preview content without duplicating the semantic table', () => {
    const value = draft()
    value.preview.zh = {
      heading: '理清表格', headingPrefix: '一句话，', scene: '以考勤表为例', request: '整理表格', material: '', outputTitle: '考勤汇总',
      outputParagraphs: ['姓名 | 应出勤 | 实际出勤', '林然 | 22 | 21', '合计 | 44 | 43', '补充说明'],
      intro: { text: '已确认两张表。', tone: 'muted' },
      blocks: [{ text: '正文块', tone: 'accent' }],
      sections: [{ title: '核对结果', blocks: [{ text: '段落内容' }] }],
    }
    const wrapper = mount(ExpertDesktopPreview, { props: { modelValue: value, locale: 'zh' } })
    expect(wrapper.find('.expert-desktop-preview__heading-prefix').text()).toBe('一句话，')
    expect(wrapper.text()).toContain('正文块')
    expect(wrapper.text()).toContain('核对结果')
    expect(wrapper.find('table').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('姓名 | 应出勤 | 实际出勤')

    value.preview.zh.table = {
      headers: ['A', 'B'], columnWidths: [50, 50], rows: [['1', '2']],
    }
    value.preview.zh.outputParagraphs = ['A | B', '1 | 2']
    const semantic = mount(ExpertDesktopPreview, { props: { modelValue: value, locale: 'zh' } })
    expect(semantic.findAll('table')).toHaveLength(1)
    expect(semantic.text()).toContain('A')
  })

  it('applies the top typography controls to the active table style', async () => {
    const value = draft()
    value.preview.zh.table = {
      headers: ['列'], columnWidths: [100], rows: [['内容']],
      style: { fontSize: 14, color: '#303238' },
    }
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value } })
    await wrapper.get('[data-test="expert-table-editor"]').trigger('click')
    await wrapper.get('[data-test="expert-font-size"]').setValue('24')
    let updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.preview.zh.table?.style?.fontSize).toBe(24)

    await wrapper.setProps({ modelValue: updated })
    const color = wrapper.get('[data-test="expert-font-color"]')
    await color.setValue('#123456')
    updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.preview.zh.table?.style?.color).toBe('#123456')
  })

  it('renders mixed rich-text runs for every desktop field in the live preview', () => {
    const value = draft()
    value.canvas.zh = createExpertCanvas(value, 'zh')
    const fields = ['preview.request', 'preview.material', 'title', 'description', 'usageGuide', 'prompt.0']
    fields.forEach((field) => {
      const node = value.canvas.zh?.nodes.find(item => item.field === field)
      if (node) node.runs = [{ text: node.runs?.[0]?.text ?? 'x', color: '#123456', fontSize: 22 }]
    })
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value } })
    const preview = wrapper.get('[data-test="expert-desktop-preview"]')
    expect(preview.findAll('span').some(span => span.attributes('style')?.includes('color: rgb(18, 52, 86)'))).toBe(true)
    expect(preview.findAll('span').some(span => span.attributes('style')?.includes('font-size: 22px'))).toBe(true)
  })

  it('shows the unsaved indicator only when the parent draft is dirty', async () => {
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: draft(), dirty: true } })
    expect(wrapper.get('[data-test="expert-wysiwyg-dirty"]').text()).toContain('未保存')
    await wrapper.setProps({ dirty: false })
    expect(wrapper.find('[data-test="expert-wysiwyg-dirty"]').exists()).toBe(false)
  })

  it('switches language and emits edits for description and task types', async () => {
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: draft() } })
    const languages = wrapper.findAll('[data-test="expert-wysiwyg-language"]')
    await languages[1].trigger('click')
    expect((wrapper.get('[aria-label="专家描述"]').element as HTMLTextAreaElement).value).toBe('English description')

    await wrapper.get('[aria-label="专家描述"]').setValue('Changed')
    const updates = wrapper.emitted('update:modelValue') ?? []
    expect((updates.at(-1)?.[0] as ReturnType<typeof draft>).description.en).toBe('Changed')

    const typeInput = wrapper.get('[aria-label="新增任务类型"]')
    await typeInput.setValue('Design')
    await typeInput.trigger('keydown.enter')
    const latest = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(latest.taskTypes.en).toContain('Design')
  })

  it('adds and removes examples from the canvas example block', async () => {
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: draft() } })
    const promptBlock = wrapper.get('[data-block="7"]')
    const addButton = promptBlock.findAll('button').find(button => button.text().includes('添加示例'))

    expect(addButton).toBeDefined()
    await addButton!.trigger('click')

    let updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.examplePrompts.zh).toEqual(['写一篇文案', ''])
    expect(updated.canvas.zh?.nodes.some(node => node.field === 'prompt.1')).toBe(true)

    await wrapper.setProps({ modelValue: updated })
    await promptBlock.get('[aria-label="示例 2"]').setValue('第二个示例')
    updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    await wrapper.setProps({ modelValue: updated })

    const deleteButtons = promptBlock.findAll('[aria-label="删除示例"]')
    expect(deleteButtons).toHaveLength(2)
    await deleteButtons.at(-1)!.trigger('click')

    updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.examplePrompts.zh).toEqual(['写一篇文案'])
    expect(updated.canvas.zh?.nodes.some(node => node.field === 'prompt.1')).toBe(false)
  })

  it('adds and removes canvas task types without coupling them to another tag list', async () => {
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: draft() } })
    const tagsBlock = wrapper.get('[data-block="4"]')
    const input = tagsBlock.get('[aria-label="新增任务类型"]')

    await input.setValue('设计')
    await tagsBlock.get('[data-test="expert-add-task-type"]').trigger('click')

    let updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.taskTypes.zh).toEqual(['内容创作', '设计'])
    expect(updated.canvas.zh?.nodes.some(node => node.field === 'tag.1')).toBe(true)

    await wrapper.setProps({ modelValue: updated })
    const deleteButtons = tagsBlock.findAll('[aria-label="删除任务类型"]')
    expect(deleteButtons).toHaveLength(2)
    await deleteButtons.at(-1)!.trigger('click')

    updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.taskTypes.zh).toEqual(['内容创作'])
    expect(updated.canvas.zh?.nodes.some(node => node.field === 'tag.1')).toBe(false)
  })

  it('adds and removes output paragraphs inside the output card and keeps the card scrollable', async () => {
    const value = draft()
    value.preview.zh.outputParagraphs = ['第一段']
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value } })
    const outputBlock = wrapper.get('[data-test="expert-block-preview-output"]')
    const addButton = outputBlock.get('[data-test="expert-add-output-paragraph"]')

    // Both overflow regions live inside a fixed 1080 × 704 grid item.  The
    // min-height reset is what lets flex children shrink and expose their own
    // native scrollbar instead of being clipped by the canvas.
    expect(editorSource).toMatch(/\.expert-wysiwyg__canvas\s*\{[^}]*grid-template-rows:\s*minmax\(0,\s*1fr\);/s)
    expect(editorSource).toMatch(/\.expert-wysiwyg__preview-column\s*\{[^}]*min-height:\s*0;[^}]*overflow:\s*hidden;/s)
    expect(editorSource).toMatch(/\.expert-wysiwyg__preview-output\s*\{[^}]*overflow(?:-y)?\s*:\s*auto/s)
    expect(editorSource).toMatch(/\.expert-wysiwyg__detail-scroll\s*\{[^}]*min-height:\s*0;[^}]*overflow-y:\s*auto/s)
    await addButton.trigger('click')

    let updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.preview.zh.outputParagraphs).toEqual(['第一段', ''])
    expect(updated.canvas.zh?.nodes.some(node => node.field === 'preview.outputParagraphs.1')).toBe(true)

    await wrapper.setProps({ modelValue: updated })
    await outputBlock.get('[aria-label="示例输出段落 2"]').setValue('第二段')
    updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    await wrapper.setProps({ modelValue: updated })

    const deleteButton = outputBlock.get('[aria-label="删除示例输出段落 2"]')
    await deleteButton.trigger('click')
    updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.preview.zh.outputParagraphs).toEqual(['第一段'])
    expect(updated.canvas.zh?.nodes.some(node => node.field === 'preview.outputParagraphs.1')).toBe(false)
  })

  it('does not render the legacy external content and resources editor', () => {
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: draft() } })

    expect(wrapper.find('[data-test="expert-prompt-list"]').exists()).toBe(false)
    expect(wrapper.find('.expert-wysiwyg__controls').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('内容与资源')
  })

  it('keeps newly added empty rows while other canvas fields are edited', async () => {
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: draft() } })

    await wrapper.get('[data-test="expert-add-prompt"]').trigger('click')
    let updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    await wrapper.setProps({ modelValue: updated })
    await wrapper.get('[data-test="expert-add-output-paragraph"]').trigger('click')
    updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    await wrapper.setProps({ modelValue: updated })

    await wrapper.get('[aria-label="专家描述"]').setValue('继续编辑其他字段')
    updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>

    expect(updated.examplePrompts.zh).toEqual(['写一篇文案', ''])
    expect(updated.preview.zh.outputParagraphs).toEqual(['输出', ''])
  })

  it('keeps existing canvas nodes when an unrelated field is edited', async () => {
    const value = draft()
    value.canvas.zh = {
      version: 1,
      width: 1080,
      height: 704,
      nodes: [
        {
          id: 'heading', field: 'preview.heading', kind: 'text', x: 96, y: 18,
          width: 300, height: 24, runs: [{ text: '标题', color: '#80ffff' }],
        },
        {
          id: 'example-image', field: 'asset.exampleImage', kind: 'image', x: 32, y: 500,
          width: 560, height: 180, assetRole: 'exampleImage',
        },
      ],
    }
    const before = structuredClone(value.canvas.zh)
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value } })

    await wrapper.get('[aria-label="专家描述"]').setValue('Changed')

    const updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    const retained = updated.canvas.zh?.nodes.filter(node =>
      before?.nodes.some(previous => previous.id === node.id))
    expect(retained).toEqual(before?.nodes)
  })

  it('edits prompt list and emits image selection without accepting SVG', async () => {
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: draft() } })
    await wrapper.get('[data-block="7"] [aria-label="示例 1"]').setValue('Updated prompt')
    expect((wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>).examplePrompts.zh[0]).toBe('Updated prompt')

    const inputs = wrapper.findAll('input[type="file"]')
    expect(inputs).toHaveLength(3)
    inputs.forEach(input => expect(input.attributes('accept')).toBe('image/png,image/jpeg,image/webp'))
    const file = new File(['image'], 'icon.webp', { type: 'image/webp' })
    const iconInput = wrapper.get('[data-test="expert-image-input-icon"]')
    Object.defineProperty(iconInput.element, 'files', { value: [file] })
    await iconInput.trigger('change')
    expect(wrapper.emitted('asset-picked')?.at(-1)?.[0]).toBe('icon')
    expect(wrapper.emitted('asset-picked')?.at(-1)?.[1]).toBe(file)
  })

  it('embeds the feature image in the input card and restores text after clear', async () => {
    const value = draft()
    value.media.exampleImage = { url: 'https://example.test/feature.webp', fileName: 'feature.webp', locale: 'zh' }
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value } })
    expect(wrapper.get('[data-test="expert-preview-example-image"] img').attributes('src'))
      .toBe('https://example.test/feature.webp')
    expect(wrapper.find('[data-test="expert-block-preview-input"] textarea').exists()).toBe(false)
    expect(wrapper.find('[data-test="expert-block-example-image"]').exists()).toBe(false)

    await wrapper.get('[data-test="expert-preview-example-image-clear"]').trigger('click')
    expect(wrapper.emitted('asset-cleared')?.at(-1)?.[0]).toBe('exampleImage')
    const updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.media.exampleImage.url).toBe('')
    expect(updated.media.exampleImage.pendingClear).toBe(true)
    expect(updated.media.exampleImage.locale).toBeNull()

    await wrapper.setProps({ modelValue: updated })
    expect(wrapper.find('[data-test="expert-preview-example-image"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="expert-block-preview-input"] [aria-label="示例请求"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="expert-block-preview-input"] [aria-label="示例素材"]').exists()).toBe(true)
  })

  it('stretches the feature image to its fixed frame without cropping and preserves hover actions', () => {
    const value = draft()
    value.media.exampleImage = { url: 'https://example.test/feature.webp', fileName: 'feature.webp', locale: 'zh' }
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value } })
    const imageFrame = wrapper.get('[data-test="expert-preview-example-image"]')

    expect(editorSource).toMatch(/\.expert-wysiwyg__example-image img\s*\{[^}]*width:\s*100%;[^}]*height:\s*100%;[^}]*object-fit:\s*fill;/s)
    expect(editorSource).toMatch(/\.expert-wysiwyg__preview-input--image\s*\{[^}]*border:\s*0;[^}]*padding:\s*0;/s)
    expect(desktopPreviewSource).toContain("'expert-desktop-preview__input-card--image': exampleImageVisible")
    expect(desktopPreviewSource).toMatch(/\.expert-desktop-preview__input-card--image\s*\{[^}]*flex:\s*0 0 140px;[^}]*border:\s*0;[^}]*padding:\s*0;/s)
    expect(desktopPreviewSource).toMatch(/\.expert-desktop-preview__input-card--image img\s*\{[^}]*width:\s*100%;[^}]*height:\s*100%;[^}]*object-fit:\s*fill;/s)
    expect(imageFrame.findAll('img')).toHaveLength(1)
    expect(imageFrame.findAll('[data-test="expert-preview-example-image-clear"]')).toHaveLength(1)
    expect(imageFrame.findAll('[data-test="expert-image-replace-exampleImage"]')).toHaveLength(1)
  })

  it('keeps only the heading prefix blue in the live desktop preview', () => {
    const value = draft()
    value.preview.zh.heading = '一句话，学得更高效'
    const canvas = value.canvas.zh ?? createExpertCanvas(value, 'zh')
    const headingNode = canvas.nodes.find(node => node.field === 'preview.heading')
    expect(headingNode).toBeDefined()
    headingNode!.runs = [
      { text: '一句话', color: '#5574b5', fontSize: 22 },
      { text: '，学得更高效', color: '#252525', fontSize: 24 },
    ]
    value.canvas.zh = canvas

    const wrapper = mount(ExpertDesktopPreview, { props: { modelValue: value, locale: 'zh' } })
    const heading = wrapper.get('.expert-desktop-preview__left h1')
    expect(heading.attributes('style')).not.toContain('color:')
    const prefix = heading.get('.expert-desktop-preview__heading-prefix')
    expect(prefix.text()).toBe('一句话')
    expect(prefix.attributes('style')).toContain('color: rgb(85, 116, 181)')
    expect(prefix.attributes('style')).toContain('font-size: 22px')
    const body = heading.findAll('span').at(-1)!
    expect(body.text()).toBe('，学得更高效')
    expect(body.attributes('style')).toContain('color: rgb(37, 37, 37)')
  })

  it('uses the feature image only in its bound locale and falls back to localized text', async () => {
    const value = draft()
    value.media.exampleImage = { url: 'https://example.test/feature.webp', locale: 'zh' }
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value } })

    expect(wrapper.find('[data-test="expert-preview-example-image"]').exists()).toBe(true)
    await wrapper.findAll('[data-test="expert-wysiwyg-language"]')[1].trigger('click')

    expect(wrapper.find('[data-test="expert-preview-example-image"]').exists()).toBe(false)
    expect((wrapper.get('[data-test="expert-block-preview-input"] [aria-label="示例请求"]').element as HTMLTextAreaElement).value)
      .toBe('Write a draft')

    await wrapper.get('[data-test="expert-preview-example-image-upload"]').trigger('click')
    const input = wrapper.get('[data-test="expert-image-input-exampleImage"]')
    const file = new File(['english'], 'feature-en.webp', { type: 'image/webp' })
    Object.defineProperty(input.element, 'files', { configurable: true, get: () => [file] })
    await input.trigger('change')

    const updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.media.exampleImage.locale).toBe('en')
  })

  it('uploads and replaces the feature image through the embedded input card', async () => {
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:feature-image'),
      revokeObjectURL: vi.fn(),
    })
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: draft() } })
    const upload = wrapper.get('[data-test="expert-preview-example-image-upload"]')
    expect(upload.text()).toContain('上传')
    await upload.trigger('click')

    const file = new File(['feature'], 'feature.webp', { type: 'image/webp' })
    const input = wrapper.get('[data-test="expert-image-input-exampleImage"]')
    let selectedFiles = [file]
    Object.defineProperty(input.element, 'files', { configurable: true, get: () => selectedFiles })
    await input.trigger('change')
    expect(wrapper.emitted('asset-picked')?.at(-1)?.[0]).toBe('exampleImage')
    expect(wrapper.emitted('asset-picked')?.at(-1)?.[1]).toBe(file)

    const uploaded = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(uploaded.media.exampleImage.fileName).toBe('feature.webp')
    await wrapper.setProps({ modelValue: uploaded })
    expect(wrapper.get('[data-test="expert-preview-example-image"] img').attributes('src')).toBe('blob:feature-image')
    expect(wrapper.get('[data-test="expert-preview-example-image"]').attributes('aria-label')).toBe('替换功能示例图')

    await wrapper.get('[data-test="expert-preview-example-image"]').trigger('click')
    const replacement = new File(['replacement'], 'replacement.png', { type: 'image/png' })
    selectedFiles = [replacement]
    await input.trigger('change')
    expect(wrapper.emitted('asset-picked')?.at(-1)?.[0]).toBe('exampleImage')
    expect(wrapper.emitted('asset-picked')?.at(-1)?.[1]).toBe(replacement)

    const replaced = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    await wrapper.setProps({ modelValue: replaced })
    await wrapper.get('[data-test="expert-preview-example-image-clear"]').trigger('click')
    expect(wrapper.emitted('asset-cleared')?.at(-1)?.[0]).toBe('exampleImage')
    const cleared = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    await wrapper.setProps({ modelValue: cleared })
    expect(wrapper.find('[data-test="expert-preview-example-image"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="expert-block-preview-input"] [aria-label="示例请求"]').exists()).toBe(true)
    vi.unstubAllGlobals()
  })

  it('releases an immediate upload preview after the parent swaps in normalized image bytes', async () => {
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:pending-avatar'),
      revokeObjectURL,
    })
    try {
      const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: draft() } })
      const input = wrapper.get('[data-test="expert-image-input-icon"]')
      const file = new File(['avatar'], 'avatar.webp', { type: 'image/webp' })
      Object.defineProperty(input.element, 'files', { configurable: true, get: () => [file] })
      await input.trigger('change')

      const pending = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
      await wrapper.setProps({ modelValue: pending })
      expect(revokeObjectURL).not.toHaveBeenCalledWith('blob:pending-avatar')

      const normalized = structuredClone(pending)
      normalized.media.icon.url = 'blob:normalized-avatar'
      await wrapper.setProps({ modelValue: normalized })
      expect(revokeObjectURL).toHaveBeenCalledWith('blob:pending-avatar')
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('does not replace or clear the embedded feature image while disabled', async () => {
    const value = draft()
    value.media.exampleImage = { url: 'https://example.test/feature.webp', fileName: 'feature.webp' }
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value, disabled: true } })
    const input = wrapper.get('[data-test="expert-image-input-exampleImage"]')
    const click = vi.spyOn(input.element as HTMLInputElement, 'click')

    expect(wrapper.get('[data-test="expert-block-preview-input"]').attributes('tabindex')).toBe('-1')
    expect(wrapper.get('[data-test="expert-preview-example-image"]').attributes('tabindex')).toBe('-1')
    await wrapper.get('[data-test="expert-preview-example-image"]').trigger('click')
    await wrapper.get('[data-test="expert-preview-example-image"]').trigger('keydown.enter')
    await wrapper.get('[data-test="expert-preview-example-image-clear"]').trigger('click')

    expect(click).not.toHaveBeenCalled()
    expect(wrapper.emitted('asset-cleared')).toBeUndefined()
    click.mockRestore()
  })

  it('zooms the fixed canvas and reorders list values without losing content', async () => {
    const value = draft()
    value.examplePrompts.zh = ['第一条', '第二条']
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value } })

    await wrapper.get('[aria-label="缩小画布"]').trigger('click')
    expect(wrapper.get('.expert-wysiwyg__zoom').text()).toBe('90%')
    expect(wrapper.get('[data-test="expert-wysiwyg-canvas"]').attributes('style')).toContain('scale(0.9)')

    await wrapper.get('[aria-label="下移第 1 条示例"]').trigger('click')
    const updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.examplePrompts.zh).toEqual(['第二条', '第一条'])
  })

  it('keeps rich text runs with the surviving item when deleting an earlier list entry', async () => {
    const value = draft()
    value.examplePrompts.zh = ['第一条', '保留蓝色样式']
    value.canvas.zh = createExpertCanvas(value, 'zh')
    const second = value.canvas.zh.nodes.find(node => node.field === 'prompt.1')
    if (!second) throw new Error('missing prompt.1 canvas node')
    second.runs = [{ text: '保留蓝色样式', color: '#2563eb', fontSize: 18 }]
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value } })

    await wrapper.findAll('[aria-label="删除示例"]')[0].trigger('click')

    const updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.examplePrompts.zh).toEqual(['保留蓝色样式'])
    expect(updated.canvas.zh?.nodes.find(node => node.field === 'prompt.0')?.runs).toEqual([
      { text: '保留蓝色样式', color: '#2563eb', fontSize: 18 },
    ])
    expect(updated.canvas.zh?.nodes.some(node => node.field === 'prompt.1')).toBe(false)
  })

  it('supports zooming in as well as out without changing the persisted canvas geometry', async () => {
    const value = draft()
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value } })

    await wrapper.get('[aria-label="放大画布"]').trigger('click')

    expect(wrapper.get('.expert-wysiwyg__zoom').text()).toBe('110%')
    expect(wrapper.get('[data-test="expert-wysiwyg-canvas"]').attributes('style')).toContain('scale(1.1)')
    expect((wrapper.emitted('update:modelValue') ?? []).length).toBe(0)
  })

  it('keeps the editor full width while the final preview lives in a closable floating window', async () => {
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: draft() } })

    expect(wrapper.find('[data-test="expert-floating-preview"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="expert-floating-preview"]').attributes('class')).toContain('expert-wysiwyg__floating-preview')
    expect(wrapper.find('.expert-wysiwyg__workbench > .expert-wysiwyg__viewport').exists()).toBe(true)
    expect(wrapper.find('.expert-wysiwyg__workbench [data-test="expert-desktop-preview"]').exists()).toBe(false)

    await wrapper.get('[data-test="expert-preview-close"]').trigger('click')
    expect(wrapper.find('[data-test="expert-floating-preview"]').exists()).toBe(false)
    await wrapper.get('[data-test="expert-preview-reopen"]').trigger('click')
    expect(wrapper.find('[data-test="expert-floating-preview"]').exists()).toBe(true)
  })

  it('drags the independent preview by its title bar, clamps it to the viewport, and keeps its position after reopen', async () => {
    const wrapper = mount(ExpertWysiwygEditor, {
      props: { modelValue: draft() },
      attachTo: document.body,
    })
    const floating = wrapper.get('[data-test="expert-floating-preview"]')
    const handle = wrapper.get('[data-test="expert-preview-drag-handle"]')
    const originalWidth = window.innerWidth
    const originalHeight = window.innerHeight
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1000 })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 800 })

    const readPosition = (element: HTMLElement, property: 'left' | 'top', fallback: number) => {
      const value = element.getAttribute('style')?.match(new RegExp(`${property}: ([\\d.]+)px`))?.[1]
      return value ? Number(value) : fallback
    }
    const nativeGetBoundingClientRect = HTMLElement.prototype.getBoundingClientRect
    const rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      if (this.getAttribute('data-test') !== 'expert-floating-preview') return nativeGetBoundingClientRect.call(this)
      const left = readPosition(this, 'left', 420)
      const top = readPosition(this, 'top', 76)
      return { left, top, width: 520, height: 360, right: left + 520, bottom: top + 360, x: left, y: top, toJSON: () => ({}) } as DOMRect
    })

    await handle.trigger('mousedown', { button: 0, clientX: 450, clientY: 100 })
    expect(wrapper.get('[data-test="expert-wysiwyg-editor"]').classes()).toContain('expert-wysiwyg--preview-dragging')
    document.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX: 5000, clientY: 5000 }))
    await nextTick()
    expect(floating.attributes('style')).toContain('left: 480px')
    expect(floating.attributes('style')).toContain('top: 440px')

    document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
    await nextTick()
    expect(wrapper.get('[data-test="expert-wysiwyg-editor"]').classes()).not.toContain('expert-wysiwyg--preview-dragging')

    await wrapper.get('[data-test="expert-preview-close"]').trigger('click')
    await wrapper.get('[data-test="expert-preview-reopen"]').trigger('click')
    expect(wrapper.get('[data-test="expert-floating-preview"]').attributes('style')).toContain('left: 480px')

    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 600 })
    window.dispatchEvent(new Event('resize'))
    await nextTick()
    expect(wrapper.get('[data-test="expert-floating-preview"]').attributes('style')).toContain('left: 80px')

    rectSpy.mockRestore()
    wrapper.unmount()
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: originalWidth })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: originalHeight })
  })

  it('renders mixed runs directly inside the editable surface', () => {
    const value = draft()
    value.canvas.zh = createExpertCanvas(value, 'zh')
    const title = value.canvas.zh.nodes.find(node => node.field === 'title')
    if (!title) throw new Error('missing title canvas node')
    title.runs = [{ text: '中文', color: '#dc2626', fontSize: 24 }, { text: '专家' }]
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value } })
    const runs = wrapper.get('[contenteditable="true"][aria-label="专家名称"]').findAll('[data-rich-run]')

    expect(runs).toHaveLength(2)
    expect(runs[0].attributes('data-color')).toBe('#dc2626')
    expect(runs[1].attributes('data-color')).toBeUndefined()
  })

  it('applies typography only to the selected characters and clears the selection when language changes', async () => {
    const value = draft()
    value.title.zh = '中文专家'
    value.canvas.zh = createExpertCanvas(value, 'zh')
    const wrapper = mount(ExpertWysiwygEditor, {
      props: { modelValue: value },
      attachTo: document.body,
    })
    const surface = wrapper.get('[contenteditable="true"][aria-label="专家名称"]')
    ;(surface.element as HTMLElement).focus()
    const textNode = surface.element.firstChild?.firstChild
    if (!textNode) throw new Error('missing editable title text')
    const range = document.createRange()
    range.setStart(textNode, 1)
    range.setEnd(textNode, 3)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
    await surface.trigger('keyup')

    expect((wrapper.get('[data-test="expert-apply-font-color"]').element as HTMLButtonElement).disabled).toBe(false)
    await wrapper.get('[data-test="expert-font-color"]').setValue('#dc2626')
    const updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.canvas.zh?.nodes.find(node => node.field === 'title')?.runs).toEqual([
      { text: '中' },
      { text: '文专', color: '#dc2626' },
      { text: '家' },
    ])

    await wrapper.setProps({ modelValue: updated })
    await wrapper.findAll('[data-test="expert-wysiwyg-language"]')[1].trigger('click')
    expect((wrapper.get('[data-test="expert-apply-font-color"]').element as HTMLButtonElement).disabled).toBe(true)
    wrapper.unmount()
  })

  it('exposes replace, download and clear for all images without opening upload from a nested keyboard action', async () => {
    const value = draft()
    value.media.icon = { url: 'https://example.test/avatar.webp', fileName: 'avatar.webp' }
    value.media.illustration = { url: 'https://example.test/banner.webp', fileName: 'banner.webp' }
    value.media.exampleImage = { url: 'https://example.test/example.webp', fileName: 'example.webp', locale: 'zh' }
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value } })

    for (const role of ['icon', 'illustration', 'exampleImage']) {
      expect(wrapper.find(`[data-test="expert-image-replace-${role}"]`).exists()).toBe(true)
      expect(wrapper.find(`[data-test="expert-image-download-${role}"]`).exists()).toBe(true)
    }
    expect(wrapper.find('[data-test="expert-image-clear-icon"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="expert-image-clear-illustration"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="expert-preview-example-image-clear"]').exists()).toBe(true)

    const iconInput = wrapper.get('[data-test="expert-image-input-icon"]')
    const openPicker = vi.spyOn(iconInput.element as HTMLInputElement, 'click')
    await wrapper.get('[data-test="expert-image-download-icon"]').trigger('keydown.enter')
    expect(openPicker).not.toHaveBeenCalled()
    openPicker.mockRestore()
  })

  it('downloads the original image bytes with a MIME-correct filename', async () => {
    const value = draft()
    value.media.icon = { url: 'https://example.test/avatar', fileName: '专家头像.png' }
    const createDescriptor = Object.getOwnPropertyDescriptor(URL, 'createObjectURL')
    const revokeDescriptor = Object.getOwnPropertyDescriptor(URL, 'revokeObjectURL')
    const downloaded: string[] = []
    const anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      downloaded.push(this.download)
    })
    const createObjectUrl = vi.fn(() => 'blob:downloaded-image')
    const revokeObjectUrl = vi.fn()
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createObjectUrl })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeObjectUrl })
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true,
      blob: async () => new Blob(['webp'], { type: 'image/webp' }),
    })))

    try {
      const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value } })
      await wrapper.get('[data-test="expert-image-download-icon"]').trigger('click')
      await flushPromises()
      await new Promise(resolve => setTimeout(resolve, 0))

      expect(downloaded).toEqual(['专家头像.webp'])
      expect(createObjectUrl).toHaveBeenCalledOnce()
      expect(revokeObjectUrl).toHaveBeenCalledWith('blob:downloaded-image')
    } finally {
      anchorClick.mockRestore()
      vi.unstubAllGlobals()
      if (createDescriptor) Object.defineProperty(URL, 'createObjectURL', createDescriptor)
      else Reflect.deleteProperty(URL, 'createObjectURL')
      if (revokeDescriptor) Object.defineProperty(URL, 'revokeObjectURL', revokeDescriptor)
      else Reflect.deleteProperty(URL, 'revokeObjectURL')
    }
  })
})
