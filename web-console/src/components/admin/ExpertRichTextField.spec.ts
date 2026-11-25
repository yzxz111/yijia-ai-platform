import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import ExpertRichTextField from './ExpertRichTextField.vue'

describe('ExpertRichTextField', () => {
  it('renders each rich run independently instead of inheriting the first run style', () => {
    const wrapper = mount(ExpertRichTextField, {
      props: {
        modelValue: '红色默认',
        runs: [
          { text: '红色', color: '#dc2626', fontSize: 24 },
          { text: '默认' },
        ],
        maxlength: 32,
        label: '文本',
      },
    })

    const runs = wrapper.find('[contenteditable="true"]').findAll('[data-rich-run]')
    expect(runs).toHaveLength(2)
    expect(runs[0].attributes('data-color')).toBe('#dc2626')
    expect(runs[0].attributes('data-font-size')).toBe('24')
    expect(runs[1].attributes('data-color')).toBeUndefined()
    expect(runs[1].attributes('data-font-size')).toBeUndefined()
  })

  it('emits character offsets for a keyboard selection', async () => {
    const wrapper = mount(ExpertRichTextField, {
      props: { modelValue: '选择其中几个字', maxlength: 32, label: '文本' },
      attachTo: document.body,
    })
    const surface = wrapper.get('[contenteditable="true"]')
    const textNode = surface.element.firstChild?.firstChild
    if (!textNode) throw new Error('missing rich text node')
    const range = document.createRange()
    range.setStart(textNode, 1)
    range.setEnd(textNode, 4)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
    surface.element.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }))

    expect(wrapper.emitted('selection-change')?.at(-1)?.[0]).toEqual({ start: 1, end: 4 })
  })

  it('keeps pasted content plain and truncates the visible DOM at maxlength', async () => {
    const wrapper = mount(ExpertRichTextField, {
      props: { modelValue: '原文', maxlength: 4, label: '文本' },
    })
    const surface = wrapper.get('[contenteditable="true"]')
    ;(surface.element as HTMLElement).textContent = '超出长度的文字'
    await surface.trigger('input')

    expect(surface.text()).toBe('超出长度')
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toBe('超出长度')
  })

  it('preserves the newline represented by a trailing empty DIV and BR', async () => {
    const wrapper = mount(ExpertRichTextField, {
      props: { modelValue: '第一行', maxlength: 32, label: '文本' },
      attachTo: document.body,
    })
    const surface = wrapper.get('[contenteditable="true"]')
    ;(surface.element as HTMLElement).innerHTML = '<div>第一行</div><div><br></div>'
    await surface.trigger('input')

    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toBe('第一行\n')
    expect(wrapper.emitted('update:runs')?.at(-1)?.[0]).toEqual([{ text: '第一行\n' }])

    const emptyLine = surface.element.lastChild
    if (!(emptyLine instanceof HTMLElement)) throw new Error('missing trailing line')
    const range = document.createRange()
    range.setStart(emptyLine, 0)
    range.collapse(true)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
    surface.element.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }))

    expect(wrapper.emitted('selection-change')?.at(-1)?.[0]).toEqual({ start: 4, end: 4 })
  })

  it('keeps the block boundary when inline text is followed by a DIV', async () => {
    const wrapper = mount(ExpertRichTextField, {
      props: { modelValue: '红色新内容', maxlength: 32, label: '文本' },
      attachTo: document.body,
    })
    const surface = wrapper.get('[contenteditable="true"]')
    ;(surface.element as HTMLElement).innerHTML = '<span data-rich-run="true" data-color="#dc2626">红色</span><div>新内容</div>'
    await surface.trigger('input')

    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toBe('红色\n新内容')
    expect(wrapper.emitted('update:runs')?.at(-1)?.[0]).toEqual([
      { text: '红色', color: '#dc2626' },
      { text: '\n新内容' },
    ])

    const block = surface.element.lastChild
    if (!(block instanceof HTMLElement) || !block.firstChild) throw new Error('missing block text')
    const range = document.createRange()
    range.setStart(block.firstChild, 0)
    range.setEnd(block.firstChild, 1)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
    surface.element.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }))

    expect(wrapper.emitted('selection-change')?.at(-1)?.[0]).toEqual({ start: 3, end: 4 })
  })

  it('converts line breaks to spaces when pasting into a single-line field', async () => {
    const wrapper = mount(ExpertRichTextField, {
      props: { modelValue: '', maxlength: 32, label: '文本', multiline: false },
      attachTo: document.body,
    })
    const surface = wrapper.get('[contenteditable="true"]')
    const range = document.createRange()
    range.setStart(surface.element, 0)
    range.collapse(true)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
    await surface.trigger('paste', {
      clipboardData: { getData: () => '第一行\n第二行\r\n第三行' },
    })

    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toBe('第一行 第二行 第三行')
  })

  it('blocks Enter in single-line mode', async () => {
    const wrapper = mount(ExpertRichTextField, {
      props: { modelValue: '单行', maxlength: 32, label: '文本', multiline: false },
    })
    const event = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true, bubbles: true })
    wrapper.get('[contenteditable="true"]').element.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
  })
})
