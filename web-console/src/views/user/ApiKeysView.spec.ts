import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import ApiKeysView from './ApiKeysView.vue'

const mocks = vi.hoisted(() => ({
  createApiKey: vi.fn(),
  listApiKeys: vi.fn(),
  listEligibleApiKeyModels: vi.fn(),
  quotaSummary: vi.fn(),
  getProfile: vi.fn(),
}))

vi.mock('../../api/gateway', () => ({
  listApiKeys: mocks.listApiKeys,
  listEligibleApiKeyModels: mocks.listEligibleApiKeyModels,
  quotaSummary: mocks.quotaSummary,
  createApiKey: mocks.createApiKey,
  revokeApiKey: vi.fn(),
  changeApiKeyStatus: vi.fn(),
  resetApiKey: vi.fn(),
}))

vi.mock('../../api/platform', () => ({
  getProfile: mocks.getProfile,
}))

describe('ApiKeysView', () => {
  beforeEach(() => {
    vi.unstubAllEnvs()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mocks.listApiKeys.mockResolvedValue([])
    mocks.listEligibleApiKeyModels.mockResolvedValue(['text-basic'])
    mocks.quotaSummary.mockResolvedValue({ activePackage: null, queuedPackages: [] })
    mocks.getProfile.mockResolvedValue({ phoneVerified: false })
  })

  it('shows the current site OpenAI base URL by default', async () => {
    vi.stubEnv('VITE_API_BASE_URL', '')
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })

    await flushPromises()

    expect(wrapper.get('[data-test="api-base-url"]').text()).toBe(`${window.location.origin}/v1`)
    wrapper.unmount()
  })

  it('shows the configured API base URL without trailing slashes', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://gateway.example.com/v1///')
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })

    await flushPromises()

    expect(wrapper.get('[data-test="api-base-url"]').text()).toBe('https://gateway.example.com/v1')
    wrapper.unmount()
  })

  it('copies the API base URL without the secure-context clipboard API', async () => {
    vi.stubEnv('VITE_API_BASE_URL', '')
    const clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
    const execCommandDescriptor = Object.getOwnPropertyDescriptor(document, 'execCommand')
    let copiedValue = ''
    const execCommand = vi.fn().mockImplementation(() => {
      copiedValue = (document.activeElement as HTMLTextAreaElement | null)?.value ?? ''
      return true
    })
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined })
    Object.defineProperty(document, 'execCommand', { configurable: true, value: execCommand })
    const success = vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })

    try {
      await flushPromises()
      await wrapper.get('[data-test="copy-api-base-url"]').trigger('click')
      await flushPromises()

      expect(copiedValue).toBe(`${window.location.origin}/v1`)
      expect(execCommand).toHaveBeenCalledWith('copy')
      expect(success).toHaveBeenCalledWith('API Base URL 已复制')
    } finally {
      wrapper.unmount()
      success.mockRestore()
      if (clipboardDescriptor) Object.defineProperty(navigator, 'clipboard', clipboardDescriptor)
      else Reflect.deleteProperty(navigator, 'clipboard')
      if (execCommandDescriptor) Object.defineProperty(document, 'execCommand', execCommandDescriptor)
      else Reflect.deleteProperty(document, 'execCommand')
    }
  })

  it('explains how to recover when copying the API base URL fails', async () => {
    const clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
    const execCommandDescriptor = Object.getOwnPropertyDescriptor(document, 'execCommand')
    const writeText = vi.fn().mockRejectedValue(new Error('clipboard unavailable'))
    const execCommand = vi.fn().mockReturnValue(false)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    Object.defineProperty(document, 'execCommand', { configurable: true, value: execCommand })
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })

    try {
      await flushPromises()
      await wrapper.get('[data-test="copy-api-base-url"]').trigger('click')
      await flushPromises()

      expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/v1`)
      expect(execCommand).toHaveBeenCalledWith('copy')
      expect(error).toHaveBeenCalledWith('复制失败，请手动选择并复制 API Base URL')
    } finally {
      wrapper.unmount()
      error.mockRestore()
      if (clipboardDescriptor) Object.defineProperty(navigator, 'clipboard', clipboardDescriptor)
      else Reflect.deleteProperty(navigator, 'clipboard')
      if (execCommandDescriptor) Object.defineProperty(document, 'execCommand', execCommandDescriptor)
      else Reflect.deleteProperty(document, 'execCommand')
    }
  })

  it('allows key creation from enabled models when the user has no package', async () => {
    const wrapper = mount(ApiKeysView, {
      global: {
        stubs: {
          SmsVerificationDialog: true,
          'el-button': {
            props: ['disabled'],
            template: '<button :disabled="disabled"><slot /></button>',
          },
          'el-alert': true,
          'el-dialog': true,
          'el-form': true,
          'el-form-item': true,
          'el-input': true,
          'el-checkbox-group': true,
          'el-checkbox': true,
          'el-skeleton': true,
          'el-tag': true,
          'el-tooltip': true,
        },
      },
    })

    await flushPromises()

    expect(mocks.listEligibleApiKeyModels).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-test="create-key"]').attributes('disabled')).toBeUndefined()
  })

  it('formats API key timestamps instead of exposing raw ISO values', async () => {
    mocks.listApiKeys.mockResolvedValue([{
      id: 'key-1', name: 'Production', keyPrefix: 'sk-test', allowedModels: ['text-basic'],
      status: 'ACTIVE', lastUsedAt: '2026-08-01T00:00:00Z',
      expiresAt: '2026-09-10T02:14:16Z', createdAt: '2026-08-01T00:00:00Z',
    }])
    const wrapper = mount(ApiKeysView, {
      global: {
        stubs: {
          SmsVerificationDialog: true,
          'el-button': { template: '<button><slot /></button>' },
          'el-alert': true,
          'el-dialog': true,
          'el-form': true,
          'el-form-item': true,
          'el-input': true,
          'el-checkbox-group': true,
          'el-checkbox': true,
          'el-skeleton': true,
          'el-tag': { template: '<span><slot /></span>' },
          'el-tooltip': { template: '<span><slot /></span>' },
        },
      },
    })

    await flushPromises()

    expect(wrapper.text()).toContain('2026-08-01 08:00:00')
    expect(wrapper.text()).toContain('2026-09-10 10:14:16')
    expect(wrapper.text()).not.toContain('2026-09-10T02:14:16Z')
    expect(wrapper.text()).toContain('正常')
    expect(wrapper.text()).not.toContain('ACTIVE')
  })

  it('copies a newly created secret without the secure-context clipboard API', async () => {
    mocks.createApiKey.mockResolvedValue({
      id: 'key-1',
      name: 'Production',
      keyPrefix: 'sk-test',
      allowedModels: ['text-basic'],
      status: 'ACTIVE',
      expiresAt: null,
      secret: 'sk-yzh-secret',
    })
    const execCommand = vi.fn().mockReturnValue(true)
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      value: execCommand,
    })
    const success = vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test="create-key"]').trigger('click')
    await wrapper.get('[data-test="key-name"]').setValue('Production')
    await wrapper.get('[data-test="submit-key"]').trigger('click')
    await flushPromises()
    await wrapper.get('[aria-label="复制 API 密钥"]').trigger('click')
    await flushPromises()

    expect(execCommand).toHaveBeenCalledWith('copy')
    expect(success).toHaveBeenCalledWith('API 密钥已复制')
    wrapper.unmount()
    success.mockRestore()
    Reflect.deleteProperty(document, 'execCommand')
  })

  it('shows the real create failure in a top-level dialog without a page error alert', async () => {
    const reason = 'API 密钥数量已达上限，请先撤销不再使用的密钥。'
    mocks.createApiKey.mockRejectedValue({
      response: {
        status: 422,
        data: { code: 'api_key_limit_exceeded', message: reason },
      },
    })
    const alert = vi.spyOn(ElMessageBox, 'alert')
      .mockResolvedValue({} as never)
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test="create-key"]').trigger('click')
    await wrapper.get('[data-test="key-name"]').setValue('Production')
    await wrapper.get('[data-test="submit-key"]').trigger('click')
    await flushPromises()

    expect(alert).toHaveBeenCalledWith(
      reason,
      'API 密钥创建失败',
      expect.objectContaining({
        appendTo: document.body,
        confirmButtonText: '知道了',
        type: 'error',
      }),
    )
    expect(wrapper.text()).not.toContain(reason)
    expect(wrapper.find('[data-test="key-name"]').exists()).toBe(true)

    wrapper.unmount()
    alert.mockRestore()
  })

  it('does not expose an unstructured server error message', async () => {
    mocks.createApiKey.mockRejectedValue({
      response: {
        status: 500,
        data: { message: 'duplicate key value violates internal_constraint' },
      },
    })
    const alert = vi.spyOn(ElMessageBox, 'alert')
      .mockResolvedValue({} as never)
    const wrapper = mount(ApiKeysView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test="create-key"]').trigger('click')
    await wrapper.get('[data-test="key-name"]').setValue('Production')
    await wrapper.get('[data-test="submit-key"]').trigger('click')
    await flushPromises()

    expect(alert).toHaveBeenCalledWith(
      'API 密钥服务暂时不可用，请稍后重试。',
      'API 密钥创建失败',
      expect.any(Object),
    )

    wrapper.unmount()
    alert.mockRestore()
  })
})
