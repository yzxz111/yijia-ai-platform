import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  requestSliderCaptcha: vi.fn(),
  verifySliderCaptcha: vi.fn(),
}))

vi.mock('../../src/api/platform', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/api/platform')>()),
  requestSliderCaptcha: api.requestSliderCaptcha,
  verifySliderCaptcha: api.verifySliderCaptcha,
}))

import SliderCaptchaDialog from '../../src/components/auth/SliderCaptchaDialog.vue'
import sliderCaptchaSource from '../../src/components/auth/SliderCaptchaDialog.vue?raw'
import SmsCodeField from '../../src/components/sms/SmsCodeField.vue'

describe('slider captcha', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.requestSliderCaptcha.mockResolvedValue({
      id: 'captcha-1',
      backgroundImage: 'data:image/png;base64,YmFja2dyb3VuZA==',
      pieceImage: 'data:image/png;base64,cGllY2U=',
      pieceY: 48,
      imageWidth: 320,
      imageHeight: 160,
      pieceSize: 48,
      expiresAt: '2026-08-18T08:02:00Z',
    })
    api.verifySliderCaptcha.mockResolvedValue({
      captchaProof: 'one-time-captcha-proof',
      expiresAt: '2026-08-18T08:02:00Z',
    })
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('loads a responsive puzzle and returns the verified slider offset proof', async () => {
    const wrapper = mount(SliderCaptchaDialog, {
      attachTo: document.body,
      global: { plugins: [ElementPlus] },
    })

    const proof = (wrapper.vm as unknown as { open: () => Promise<string | null> }).open()
    await flushPromises()

    expect(api.requestSliderCaptcha).toHaveBeenCalledTimes(1)
    const actions = document.querySelector<HTMLElement>('.captcha-actions')
    expect(actions).not.toBeNull()
    expect(actions!.querySelectorAll('button')).toHaveLength(3)
    expect(sliderCaptchaSource).toMatch(/\.captcha-actions\s*\{[^}]*justify-content:\s*flex-end;[^}]*gap:\s*8px;/s)
    expect(sliderCaptchaSource).toMatch(/\.captcha-actions :deep\(\.el-button\)\s*\{[^}]*height:\s*44px;[^}]*margin:\s*0;/s)
    const slider = document.querySelector<HTMLInputElement>('[data-test=captcha-slider]')
    expect(slider).not.toBeNull()
    slider!.value = '128'
    slider!.dispatchEvent(new Event('input', { bubbles: true }))
    document.querySelector<HTMLButtonElement>('[data-test=captcha-submit]')!.click()
    await flushPromises()

    expect(api.verifySliderCaptcha).toHaveBeenCalledWith('captcha-1', 128)
    await expect(proof).resolves.toBe('one-time-captcha-proof')
    wrapper.unmount()
  })

  it('does not clear the current SMS state when the user cancels verification', async () => {
    const sendChallenge = vi.fn()
    const wrapper = mount(SmsCodeField, {
      props: {
        modelValue: '123456',
        phone: '13812345678',
        requirePhone: true,
        beforeSend: () => Promise.resolve(false),
        sendChallenge,
      },
      global: { plugins: [ElementPlus] },
    })

    await wrapper.get('[data-test=sms-send]').trigger('click')
    await flushPromises()

    expect(sendChallenge).not.toHaveBeenCalled()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.emitted('challenge')).toBeUndefined()
    wrapper.unmount()
  })
})
