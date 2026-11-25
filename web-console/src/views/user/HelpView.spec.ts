import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import HelpView from './HelpView.vue'

const messages = vi.hoisted(() => ({
  error: vi.fn(),
  success: vi.fn(),
}))

vi.mock('element-plus', () => ({ ElMessage: messages }))

const stubs = {
  'el-button': {
    emits: ['click'],
    template: '<button @click="$emit(\'click\')"><slot /></button>',
  },
  'el-tooltip': { template: '<span><slot /></span>' },
}

describe('HelpView', () => {
  afterEach(() => {
    vi.clearAllMocks()
    vi.unstubAllEnvs()
  })

  it('normalizes the configured base URL and documents supported requests', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.com/v1///')

    const wrapper = mount(HelpView, { global: { stubs } })

    expect(wrapper.get('[data-test="help-api-base-url"]').text()).toBe('https://api.example.com/v1')
    expect(wrapper.text()).toContain('Authorization: Bearer <TOKEN>')
    expect(wrapper.text()).toContain('/chat/completions')
    expect(wrapper.text()).toContain('/responses')
    expect(wrapper.text()).toContain('/files')
    expect(wrapper.text()).toContain('text-basic')
    expect(wrapper.text()).toContain('HTTP 错误码')
    expect(wrapper.text()).toContain('限流与并发')
    expect(wrapper.text()).toContain('积分计量规则')
    expect(wrapper.text()).toContain('超时与重试建议')
    expect(wrapper.get('.help-navigation').attributes('aria-label')).toBe('帮助文档目录')
  })

  it('falls back to the current site v1 endpoint', () => {
    vi.stubEnv('VITE_API_BASE_URL', '')

    const wrapper = mount(HelpView, { global: { stubs } })

    expect(wrapper.get('[data-test="help-api-base-url"]').text()).toBe(`${window.location.origin}/v1`)
  })

  it('copies the resolved base URL and confirms success', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.com/v1/')
    const clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })

    try {
      const wrapper = mount(HelpView, { global: { stubs } })
      await wrapper.get('[data-test="copy-help-api-base-url"]').trigger('click')
      await flushPromises()

      expect(writeText).toHaveBeenCalledWith('https://api.example.com/v1')
      expect(messages.success).toHaveBeenCalledWith('API Base URL 已复制')
    } finally {
      if (clipboardDescriptor) Object.defineProperty(navigator, 'clipboard', clipboardDescriptor)
      else Reflect.deleteProperty(navigator, 'clipboard')
    }
  })
})
