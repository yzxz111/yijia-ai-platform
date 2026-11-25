import { beforeEach, describe, expect, it, vi } from 'vitest'

const http = vi.hoisted(() => ({
  gatewayPost: vi.fn(),
  platformPatch: vi.fn(),
  platformPost: vi.fn(),
  platformPut: vi.fn(),
}))

vi.mock('../../src/api/http', () => ({
  gatewayHttp: { post: http.gatewayPost },
  platformHttp: {
    patch: http.platformPatch,
    post: http.platformPost,
    put: http.platformPut,
  },
  readCookie: vi.fn(),
}))

import * as gatewayApi from '../../src/api/gateway'
import * as platformApi from '../../src/api/platform'

type PlatformSmsApi = {
  bindPhone?: (challengeId: string, purpose: 'PHONE_BIND' | 'PHONE_CHANGE', currentPhoneProof?: string) => Promise<void>
  changePassword: (currentPassword: string, newPassword: string, smsProof?: string) => Promise<void>
  createPaymentAttempt: (
    orderNo: string,
    provider: 'MOCK',
    product: 'MOCK',
    smsProof?: string,
  ) => Promise<unknown>
  register: (request: {
    email: string
    password: string
    displayName: string
    phone: string
    challengeId: string
    verificationCode: string
  }) => Promise<unknown>
  requestPhoneChallenge?: (
    purpose: 'PHONE_BIND' | 'PHONE_CHANGE',
    phone: string,
  ) => Promise<unknown>
  requestIdentitySms?: (phone: string, captchaProof: string) => Promise<unknown>
  requestRegistrationSms?: (phone: string, captchaProof: string) => Promise<unknown>
  requestLoginSms?: (phone: string, captchaProof: string) => Promise<unknown>
  requestPasswordResetSms?: (phone: string, captchaProof: string) => Promise<unknown>
  resetPassword?: (
    identifier: string,
    challengeId: string,
    verificationCode: string,
    newPassword: string,
  ) => Promise<void>
  requestSliderCaptcha?: () => Promise<unknown>
  verifySliderCaptcha?: (challengeId: string, offsetX: number) => Promise<unknown>
  requestSmsChallenge?: (
    purpose: 'CHANGE_PASSWORD' | 'PAYMENT_CONFIRM' | 'API_KEY_RESET' | 'PHONE_CHANGE_CONFIRM',
  ) => Promise<unknown>
  updateSmsPreference?: (enabled: boolean) => Promise<void>
  verifySmsChallenge?: (challengeId: string, code: string) => Promise<unknown>
  smsLogin?: (challengeId: string, code: string) => Promise<unknown>
}

type GatewaySmsApi = {
  resetApiKey?: (id: string, smsProof: string) => Promise<unknown>
}

const sms = platformApi as unknown as PlatformSmsApi
const gatewaySms = gatewayApi as unknown as GatewaySmsApi

describe('user SMS API contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    http.platformPost.mockResolvedValue({ data: {} })
    http.platformPut.mockResolvedValue({ data: undefined })
    http.platformPatch.mockResolvedValue({ data: undefined })
    http.gatewayPost.mockResolvedValue({ data: { id: 'key-1', keyPrefix: 'sk-yzh-next', secret: 'one-time-secret' } })
  })

  it('adds a valid invitation code only to final phone and email registration requests', async () => {
    const invitationCode = 'Aa0Bb1Cc2Dd3Ee4Ff5Gg6Hh7Ii8Jj9Kk'
    await platformApi.completePhoneRegistration('phone-challenge', 'Ab1!cdef', 'consent-v1', `  ${invitationCode}  `)
    await platformApi.completeEmailRegistration('email-challenge', 'Ab1!cdef', 'consent-v1', invitationCode)
    expect(http.platformPost).toHaveBeenNthCalledWith(
      1, '/console/platform/auth/identity/complete-registration', {
        challengeId: 'phone-challenge', newPassword: 'Ab1!cdef', termsAccepted: true,
        consentVersion: 'consent-v1', invitationCode,
      },
    )
    expect(http.platformPost).toHaveBeenNthCalledWith(
      2, '/console/platform/auth/email-identity/complete-registration', {
        challengeId: 'email-challenge', newPassword: 'Ab1!cdef', termsAccepted: true,
        consentVersion: 'consent-v1', invitationCode,
      },
    )

    vi.clearAllMocks()
    await platformApi.completePhoneRegistration('phone-challenge', 'Ab1!cdef', 'consent-v1', '  ')
    await platformApi.completeEmailRegistration('email-challenge', 'Ab1!cdef', 'consent-v1')
    expect(http.platformPost.mock.calls[0]?.[1]).not.toHaveProperty('invitationCode')
    expect(http.platformPost.mock.calls[1]?.[1]).not.toHaveProperty('invitationCode')

    vi.clearAllMocks()
    await expect(platformApi.completeEmailRegistration(
      'email-challenge', 'Ab1!cdef', 'consent-v1', `${invitationCode.slice(0, 31)}_`,
    )).rejects.toThrow('invalid_invitation_code')
    expect(http.platformPost).not.toHaveBeenCalled()
  })

  it('includes a valid optional invitation code only when supplied for password setup', async () => {
    const invitationCode = 'Aa0Bb1Cc2Dd3Ee4Ff5Gg6Hh7Ii8Jj9Kk'
    await platformApi.changePassword('', 'Ab1!cdef', undefined, `  ${invitationCode}  `)
    expect(http.platformPost).toHaveBeenLastCalledWith('/console/platform/profile/password', {
      currentPassword: '', newPassword: 'Ab1!cdef', invitationCode,
    })

    await platformApi.changePassword('', 'Ab1!cdef')
    expect(http.platformPost).toHaveBeenLastCalledWith('/console/platform/profile/password', {
      currentPassword: '', newPassword: 'Ab1!cdef',
    })

    vi.clearAllMocks()
    await expect(platformApi.changePassword('', 'Ab1!cdef', undefined, 'invalid'))
      .rejects.toThrow('invalid_invitation_code')
    expect(http.platformPost).not.toHaveBeenCalled()
  })

  it('creates a public registration challenge and forwards its phone, challenge, and code at registration', async () => {
    expect(sms.requestRegistrationSms).toBeTypeOf('function')
    if (!sms.requestRegistrationSms) return

    await sms.requestRegistrationSms('13812345678', 'captcha-proof')
    await sms.register({
      email: 'user@example.com',
      password: 'StrongPassword123!',
      displayName: 'Console User',
      phone: '13812345678',
      challengeId: 'challenge-register',
      verificationCode: '123456',
    })

    expect(http.platformPost).toHaveBeenNthCalledWith(
      1,
      '/console/platform/auth/sms/challenges',
      { purpose: 'REGISTER', phone: '13812345678', captchaProof: 'captcha-proof' },
    )
    expect(http.platformPost).toHaveBeenNthCalledWith(
      2,
      '/console/platform/auth/register',
      {
        email: 'user@example.com',
        password: 'StrongPassword123!',
        displayName: 'Console User',
        phone: '13812345678',
        challengeId: 'challenge-register',
        verificationCode: '123456',
      },
    )
  })

  it('uses the login SMS template for existing users and falls back to registration for new users', async () => {
    expect(sms.requestIdentitySms).toBeTypeOf('function')
    if (!sms.requestIdentitySms) return

    http.platformPost
      .mockRejectedValueOnce({ response: { data: { code: 'sms_login_unavailable' } } })
      .mockResolvedValueOnce({
        data: { id: 'challenge-register', purpose: 'REGISTER', maskedPhone: '138****5678', expiresAt: '2026-08-04T00:05:00Z' },
      })

    await sms.requestIdentitySms('13812345678', 'captcha-proof')

    expect(http.platformPost).toHaveBeenNthCalledWith(
      1,
      '/console/platform/auth/sms-login/challenges',
      { phone: '13812345678', captchaProof: 'captcha-proof' },
    )
    expect(http.platformPost).toHaveBeenNthCalledWith(
      2,
      '/console/platform/auth/sms/challenges',
      { purpose: 'REGISTER', phone: '13812345678', captchaProof: 'captcha-proof' },
    )
  })

  it('creates and completes an SMS login with the exact public contract', async () => {
    expect(sms.requestLoginSms).toBeTypeOf('function')
    expect(sms.smsLogin).toBeTypeOf('function')
    if (!sms.requestLoginSms || !sms.smsLogin) return

    await sms.requestLoginSms('13812345678', 'captcha-proof')
    await sms.smsLogin('challenge-login', '123456')

    expect(http.platformPost).toHaveBeenNthCalledWith(
      1,
      '/console/platform/auth/sms-login/challenges',
      { phone: '13812345678', captchaProof: 'captcha-proof' },
    )
    expect(http.platformPost).toHaveBeenNthCalledWith(
      2,
      '/console/platform/auth/sms-login',
      { challengeId: 'challenge-login', code: '123456' },
      // 登录类端点在 b9f8258 起带上设备恢复标记，用它释放这台浏览器占用的设备槽位。
      { headers: { 'X-Device-Recovery': 'true' } },
    )
  })

  it('uses the dedicated password-reset challenge and reset contracts', async () => {
    expect(sms.requestPasswordResetSms).toBeTypeOf('function')
    expect(sms.resetPassword).toBeTypeOf('function')
    if (!sms.requestPasswordResetSms || !sms.resetPassword) return

    await sms.requestPasswordResetSms('13812345678', 'captcha-proof')
    await sms.resetPassword('13812345678', 'challenge-reset', '123456', 'ResetPass456!')

    expect(http.platformPost).toHaveBeenNthCalledWith(
      1,
      '/console/platform/auth/password-reset/sms/challenges',
      { phone: '13812345678', captchaProof: 'captcha-proof' },
    )
    expect(http.platformPost).toHaveBeenNthCalledWith(
      2,
      '/console/platform/auth/password-reset',
      {
        identifier: '13812345678',
        challengeId: 'challenge-reset',
        verificationCode: '123456',
        newPassword: 'ResetPass456!',
      },
    )
  })

  it('issues and verifies a slider captcha before public SMS requests', async () => {
    expect(sms.requestSliderCaptcha).toBeTypeOf('function')
    expect(sms.verifySliderCaptcha).toBeTypeOf('function')
    if (!sms.requestSliderCaptcha || !sms.verifySliderCaptcha) return

    await sms.requestSliderCaptcha()
    await sms.verifySliderCaptcha('captcha/id', 128)

    expect(http.platformPost).toHaveBeenNthCalledWith(
      1,
      '/console/platform/auth/slider-captcha/challenges',
    )
    expect(http.platformPost).toHaveBeenNthCalledWith(
      2,
      '/console/platform/auth/slider-captcha/challenges/captcha%2Fid/verify',
      { offsetX: 128 },
    )
  })

  it('creates bound-phone sensitive challenges without sending a phone and verifies the code', async () => {
    expect(sms.requestSmsChallenge).toBeTypeOf('function')
    expect(sms.verifySmsChallenge).toBeTypeOf('function')
    if (!sms.requestSmsChallenge || !sms.verifySmsChallenge) return

    await sms.requestSmsChallenge('CHANGE_PASSWORD')
    await sms.verifySmsChallenge('challenge-sensitive', '654321')

    expect(http.platformPost).toHaveBeenNthCalledWith(
      1,
      '/console/platform/sms/challenges',
      { purpose: 'CHANGE_PASSWORD' },
    )
    expect(http.platformPost).toHaveBeenNthCalledWith(
      2,
      '/console/platform/sms/challenges/challenge-sensitive/verify',
      { code: '654321' },
    )
  })

  it('verifies a phone challenge before binding it without forwarding code or proof to the bind request', async () => {
    expect(sms.requestPhoneChallenge).toBeTypeOf('function')
    expect(sms.verifySmsChallenge).toBeTypeOf('function')
    expect(sms.bindPhone).toBeTypeOf('function')
    if (!sms.requestPhoneChallenge || !sms.verifySmsChallenge || !sms.bindPhone) return

    await sms.requestPhoneChallenge('PHONE_BIND', '13912345678')
    await sms.verifySmsChallenge('challenge-phone', '112233')
    await sms.bindPhone('challenge-phone', 'PHONE_BIND')

    expect(http.platformPost).toHaveBeenNthCalledWith(
      1,
      '/console/platform/profile/phone/challenges',
      { purpose: 'PHONE_BIND', phone: '13912345678' },
    )
    expect(http.platformPost).toHaveBeenNthCalledWith(
      2,
      '/console/platform/sms/challenges/challenge-phone/verify',
      { code: '112233' },
    )
    expect(http.platformPut).toHaveBeenCalledWith(
      '/console/platform/profile/phone',
      { challengeId: 'challenge-phone', purpose: 'PHONE_BIND' },
    )
  })

  it('forwards the current-phone proof when replacing a bound phone', async () => {
    expect(sms.bindPhone).toBeTypeOf('function')
    if (!sms.bindPhone) return

    await sms.bindPhone('challenge-phone-change', 'PHONE_CHANGE', 'current-phone-proof')

    expect(http.platformPut).toHaveBeenCalledWith(
      '/console/platform/profile/phone',
      {
        challengeId: 'challenge-phone-change',
        purpose: 'PHONE_CHANGE',
        currentPhoneProof: 'current-phone-proof',
      },
    )
  })

  it('forwards proofs once in the backend-defined body or header location', async () => {
    expect(gatewaySms.resetApiKey).toBeTypeOf('function')
    if (!gatewaySms.resetApiKey) return

    await sms.changePassword('CurrentPassword123!', 'NewPassword123!', 'password-proof')
    await sms.createPaymentAttempt('order/1', 'MOCK', 'MOCK', 'payment-proof')
    const rotated = await gatewaySms.resetApiKey('key/1', 'api-key-proof')

    expect(http.platformPost).toHaveBeenNthCalledWith(
      1,
      '/console/platform/profile/password',
      {
        currentPassword: 'CurrentPassword123!',
        newPassword: 'NewPassword123!',
        smsProof: 'password-proof',
      },
    )
    expect(http.platformPost).toHaveBeenNthCalledWith(
      2,
      '/console/platform/orders/order%2F1/payment-attempts',
      { provider: 'MOCK', product: 'MOCK', smsProof: 'payment-proof' },
    )
    expect(http.gatewayPost).toHaveBeenCalledWith(
      '/console/gateway/api-keys/key%2F1/reset',
      null,
      { headers: { 'X-SMS-Proof': 'api-key-proof' } },
    )
    expect(rotated).toEqual({ id: 'key-1', keyPrefix: 'sk-yzh-next', secret: 'one-time-secret' })
  })

  it('updates the business SMS preference with the exact boolean payload', async () => {
    expect(sms.updateSmsPreference).toBeTypeOf('function')
    if (!sms.updateSmsPreference) return

    await sms.updateSmsPreference(false)

    expect(http.platformPatch).toHaveBeenCalledWith(
      '/console/platform/profile/sms-preference',
      { enabled: false },
    )
  })
})
