import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import ExpertWysiwygEditor from './ExpertWysiwygEditor.vue'
import type { ExpertImageLocale } from '../../api/admin'
import { createExpertCanvas, createExpertWysiwygDraft } from '../../utils/expertWysiwygDraft'

const toggleSelector = '[data-test="expert-preview-example-toggle"]'
const imageSelector = '[data-test="expert-preview-example-image"]'
const inputCardSelector = '[data-test="expert-block-preview-input"]'
const imageUrl = 'https://example.test/example.webp'

function draft(imageLocale?: ExpertImageLocale) {
  const value = createExpertWysiwygDraft()
  value.preview.zh.request = '中文请求'
  value.preview.zh.material = '中文素材'
  value.preview.en.request = 'English request'
  value.preview.en.material = 'English material'
  if (imageLocale) value.media.exampleImage = { url: imageUrl, fileName: 'example.webp', locale: imageLocale }
  return value
}

describe('ExpertWysiwygEditor example view toggle', () => {
  it('offers a toggle only when the current language has an example image', async () => {
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: draft() } })
    expect(wrapper.find(toggleSelector).exists()).toBe(false)
    expect(wrapper.get(`${inputCardSelector} textarea[aria-label="示例请求"]`).element).toHaveProperty('value', '中文请求')

    await wrapper.findAll('[data-test="expert-wysiwyg-language"]')[1].trigger('click')
    expect(wrapper.find(toggleSelector).exists()).toBe(false)

    await wrapper.setProps({ modelValue: draft('zh') })
    expect(wrapper.find(toggleSelector).exists()).toBe(false)
    expect(wrapper.find(imageSelector).exists()).toBe(false)
    expect(wrapper.get(`${inputCardSelector} textarea[aria-label="示例请求"]`).element).toHaveProperty('value', 'English request')

    await wrapper.findAll('[data-test="expert-wysiwyg-language"]')[0].trigger('click')
    expect(wrapper.get(toggleSelector).text()).toBe('切换到文本')
    expect(wrapper.get(toggleSelector).attributes('aria-pressed')).toBe('false')
    expect(wrapper.get(`${imageSelector} img`).attributes('src')).toBe(imageUrl)
  })

  it('edits request and material in text view without changing the final image or losing rich text', async () => {
    const value = draft('zh')
    value.canvas.zh = createExpertCanvas(value, 'zh')
    const requestNode = value.canvas.zh.nodes.find(node => node.field === 'preview.request')
    if (!requestNode) throw new Error('missing preview request canvas node')
    requestNode.runs = [{ text: '中文请求', color: '#2563eb' }]

    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value } })
    const finalImage = () => wrapper.get('[data-test="expert-desktop-preview"] .expert-desktop-preview__input-card img')
    expect(finalImage().attributes('src')).toBe(imageUrl)

    await wrapper.get(toggleSelector).trigger('click')
    expect(wrapper.get(toggleSelector).text()).toBe('切换到图片')
    expect(wrapper.get(toggleSelector).attributes('aria-pressed')).toBe('true')
    expect(wrapper.find(imageSelector).exists()).toBe(false)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.emitted('asset-cleared')).toBeUndefined()
    expect(finalImage().attributes('src')).toBe(imageUrl)

    await wrapper.get(`${inputCardSelector} textarea[aria-label="示例请求"]`).setValue('修改请求')
    let updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.preview.zh.request).toBe('修改请求')
    expect(updated.canvas.zh?.nodes.find(node => node.field === 'preview.request')?.runs)
      .toEqual([{ text: '修改请求', color: '#2563eb' }])
    await wrapper.setProps({ modelValue: updated })

    await wrapper.get(`${inputCardSelector} textarea[aria-label="示例素材"]`).setValue('修改素材')
    updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
    expect(updated.preview.zh.request).toBe('修改请求')
    expect(updated.preview.zh.material).toBe('修改素材')
    expect(updated.media.exampleImage.url).toBe(imageUrl)
    await wrapper.setProps({ modelValue: updated })
    expect(wrapper.get(toggleSelector).attributes('aria-pressed')).toBe('true')
    expect(finalImage().attributes('src')).toBe(imageUrl)

    const editCount = wrapper.emitted('update:modelValue')?.length
    await wrapper.get(toggleSelector).trigger('click')
    expect(wrapper.get(`${imageSelector} img`).attributes('src')).toBe(imageUrl)
    expect(wrapper.get(toggleSelector).attributes('aria-pressed')).toBe('false')
    await wrapper.get(toggleSelector).trigger('click')
    expect(wrapper.get(`${inputCardSelector} textarea[aria-label="示例请求"]`).element).toHaveProperty('value', '修改请求')
    expect(wrapper.get(`${inputCardSelector} textarea[aria-label="示例素材"]`).element).toHaveProperty('value', '修改素材')
    expect(wrapper.emitted('update:modelValue')?.length).toBe(editCount)
    expect(wrapper.emitted('asset-picked')).toBeUndefined()
    expect(wrapper.emitted('asset-cleared')).toBeUndefined()
  })

  it('remembers the text or image view independently for both languages', async () => {
    const value = draft('neutral')
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: value } })
    const languages = wrapper.findAll('[data-test="expert-wysiwyg-language"]')

    await wrapper.get(toggleSelector).trigger('click')
    expect(wrapper.get(toggleSelector).attributes('aria-pressed')).toBe('true')
    await languages[1].trigger('click')
    expect(wrapper.get(toggleSelector).attributes('aria-pressed')).toBe('false')
    expect(wrapper.get(`${imageSelector} img`).attributes('src')).toBe(imageUrl)

    await wrapper.get(toggleSelector).trigger('click')
    await languages[0].trigger('click')
    expect(wrapper.get(toggleSelector).attributes('aria-pressed')).toBe('true')
    await wrapper.get(toggleSelector).trigger('click')
    expect(wrapper.get(toggleSelector).attributes('aria-pressed')).toBe('false')

    await languages[1].trigger('click')
    expect(wrapper.get(toggleSelector).attributes('aria-pressed')).toBe('true')
    await wrapper.setProps({ modelValue: { ...value, title: { ...value.title, zh: '更新标题' } } })
    expect(wrapper.get(toggleSelector).attributes('aria-pressed')).toBe('true')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('shows a newly uploaded or replacement image, then restores text controls after clearing it', async () => {
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn()
        .mockReturnValueOnce('blob:first-example')
        .mockReturnValueOnce('blob:replacement-example'),
      revokeObjectURL: vi.fn(),
    })
    try {
      const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: draft() } })
      const input = wrapper.get('[data-test="expert-image-input-exampleImage"]')
      let file = new File(['first'], 'first.webp', { type: 'image/webp' })
      Object.defineProperty(input.element, 'files', { configurable: true, get: () => [file] })

      await input.trigger('change')
      let updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
      expect(updated.media.exampleImage.locale).toBe('zh')
      await wrapper.setProps({ modelValue: updated })
      expect(wrapper.get(`${imageSelector} img`).attributes('src')).toBe('blob:first-example')

      await wrapper.get(toggleSelector).trigger('click')
      expect(wrapper.get(toggleSelector).attributes('aria-pressed')).toBe('true')
      file = new File(['replacement'], 'replacement.png', { type: 'image/png' })
      await input.trigger('change')
      updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
      expect(wrapper.emitted('asset-picked')?.at(-1)).toEqual(['exampleImage', file])
      await wrapper.setProps({ modelValue: updated })
      expect(wrapper.get(`${imageSelector} img`).attributes('src')).toBe('blob:replacement-example')
      expect(wrapper.get(toggleSelector).attributes('aria-pressed')).toBe('false')

      await wrapper.get('[data-test="expert-preview-example-image-clear"]').trigger('click')
      expect(wrapper.emitted('asset-cleared')?.at(-1)?.[0]).toBe('exampleImage')
      const cleared = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof draft>
      await wrapper.setProps({ modelValue: cleared })
      expect(wrapper.find(toggleSelector).exists()).toBe(false)
      expect(wrapper.get(`${inputCardSelector} textarea[aria-label="示例请求"]`).element).toHaveProperty('value', '中文请求')

      await wrapper.setProps({ modelValue: draft('zh') })
      expect(wrapper.get(`${imageSelector} img`).attributes('src')).toBe(imageUrl)
      expect(wrapper.get(toggleSelector).attributes('aria-pressed')).toBe('false')
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('allows disabled users to inspect text while keeping editing and image actions disabled', async () => {
    const wrapper = mount(ExpertWysiwygEditor, { props: { modelValue: draft('zh'), disabled: true } })
    const input = wrapper.get('[data-test="expert-image-input-exampleImage"]')
    const openPicker = vi.spyOn(input.element as HTMLInputElement, 'click')

    expect(wrapper.get(toggleSelector).attributes('disabled')).toBeUndefined()
    await wrapper.get(toggleSelector).trigger('click')
    expect(wrapper.get(toggleSelector).attributes('aria-pressed')).toBe('true')
    expect(wrapper.get(`${inputCardSelector} [role="textbox"][aria-label="示例请求"]`).attributes('contenteditable')).toBe('false')
    expect(wrapper.get(`${inputCardSelector} textarea[aria-label="示例素材"]`).attributes('disabled')).toBeDefined()
    await wrapper.get(`${inputCardSelector} textarea[aria-label="示例请求"]`).setValue('不能修改')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()

    await wrapper.get(toggleSelector).trigger('click')
    expect(wrapper.get(`${imageSelector} img`).attributes('src')).toBe(imageUrl)
    expect(wrapper.get('[data-test="expert-image-replace-exampleImage"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="expert-preview-example-image-clear"]').attributes('disabled')).toBeDefined()
    await wrapper.get(imageSelector).trigger('click')
    await wrapper.get('[data-test="expert-preview-example-image-clear"]').trigger('click')
    expect(openPicker).not.toHaveBeenCalled()
    expect(wrapper.emitted('asset-picked')).toBeUndefined()
    expect(wrapper.emitted('asset-cleared')).toBeUndefined()
    openPicker.mockRestore()
  })
})
