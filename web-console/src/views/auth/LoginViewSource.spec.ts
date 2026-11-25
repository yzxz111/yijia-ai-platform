import { describe, expect, it } from 'vitest'
import loginSource from './LoginView.vue?raw'

describe('password-only login entry', () => {
  it('uses the existing password API without verification or registration calls', () => {
    expect(loginSource).toContain('login(identifier.value.trim(), password.value)')
    for (const dependency of ['requestEmailChallenge', 'requestIdentitySms', 'SliderCaptchaDialog',
      'emailIdentityLogin', 'smsLogin', 'loginMode', 'ConsentCheckbox']) {
      expect(loginSource).not.toContain(dependency)
    }
  })
  it('offers account management guidance without an unavailable email recovery entry', () => {
    expect(loginSource).toContain('账号开通或密码重置请联系管理员')
    expect(loginSource).not.toContain('忘记密码？')
    expect(loginSource).not.toContain('class="register-link"')
  })
})
