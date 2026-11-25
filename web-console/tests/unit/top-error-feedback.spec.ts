import { mount } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import TopErrorMessage from '../../src/components/feedback/TopErrorMessage.vue'
import {
  profilePhoneBindErrorMessage,
  profilePhoneSendErrorMessage,
} from '../../src/views/user/profilePhoneError'

function apiError(code: string) {
  return { response: { data: { code } } }
}

describe('top error feedback', () => {
  it('shows a closeable grouped message at the system layer when the error changes', async () => {
    const error = vi.spyOn(ElMessage, 'error')
    const wrapper = mount(TopErrorMessage, { props: { message: '' } })

    await wrapper.setProps({ message: '  操作失败，请重试。  ' })

    expect(wrapper.html()).toBe('')
    expect(error).toHaveBeenCalledOnce()
    expect(error).toHaveBeenCalledWith(expect.objectContaining({
      message: '操作失败，请重试。',
      grouping: true,
      showClose: true,
    }))
  })

  it('maps phone verification failures to specific user-facing reasons', () => {
    expect(profilePhoneBindErrorMessage(apiError('invalid_sms_code'))).toBe('验证码错误，请重新输入。')
    expect(profilePhoneBindErrorMessage(apiError('challenge_expired'))).toBe('验证码已过期，请重新发送。')
    expect(profilePhoneBindErrorMessage(apiError('phone_unavailable'))).toBe('该手机号已被其他账号使用。')
    expect(profilePhoneSendErrorMessage(apiError('sms_rate_limited_60_seconds'))).toBe('验证码发送过于频繁，请 60 秒后重试。')
    expect(profilePhoneBindErrorMessage(new Error('network'))).toBe('手机号绑定失败，请稍后重试。')
  })

  it('keeps business errors out of inline Element Plus error alerts', () => {
    const sources = import.meta.glob('../../src/**/*.vue', {
      eager: true,
      import: 'default',
      query: '?raw',
    }) as Record<string, string>

    for (const [path, source] of Object.entries(sources)) {
      expect(source, path).not.toMatch(/<el-alert\b[\s\S]{0,600}?type="error"/)
    }
  })
})
