import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import PasswordRecoveryForm from './PasswordRecoveryForm.vue'

const api = vi.hoisted(() => ({
  requestEmailChallenge: vi.fn(),
  resetPassword: vi.fn(),
}))

vi.mock('../../api/platform', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/platform')>()),
  requestEmailChallenge: api.requestEmailChallenge,
  resetPassword: api.resetPassword,
}))

describe('PasswordRecoveryForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.requestEmailChallenge.mockResolvedValue({
      id: 'challenge-email-reset',
      maskedEmail: 'u***@example.com',
      expiresAt: '2026-08-19T10:05:00Z',
    })
    api.resetPassword.mockResolvedValue(undefined)
  })

  it('resets a password through the shared email verification flow', async () => {
    const wrapper = mount(PasswordRecoveryForm, { global: { plugins: [ElementPlus] } })

    await wrapper.get('[data-test=recovery-identifier]').setValue('user@example.com')
    await wrapper.get('[data-test=recovery-email-send]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=recovery-code]').setValue('123456')
    await wrapper.get('[data-test=recovery-password]').setValue('ResetPass456!')
    await wrapper.get('[data-test=recovery-confirmation]').setValue('ResetPass456!')
    await wrapper.get('[data-test=password-recovery-form]').trigger('submit')
    await flushPromises()

    expect(api.requestEmailChallenge).toHaveBeenCalledWith('user@example.com')
    expect(api.resetPassword).toHaveBeenCalledWith(
      'user@example.com',
      'challenge-email-reset',
      '123456',
      'ResetPass456!',
    )
    expect(wrapper.emitted('completed')).toHaveLength(1)
    wrapper.unmount()
  })
})
