import { readApiErrorCode } from '../../api/apiError'

const SMS_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  invalid_sms_code: '验证码错误，请重新输入。',
  challenge_expired: '验证码已过期，请重新发送。',
  challenge_attempts_exhausted: '验证码错误次数过多，请重新发送。',
  invalid_phone_challenge: '本次手机号验证已失效，请重新发送验证码。',
  phone_unavailable: '该手机号已被其他账号使用。',
  phone_already_bound: '该手机号已绑定当前账号。',
  sms_template_not_active: '短信模板未启用，请联系管理员。',
  sms_disabled: '短信服务暂不可用，请联系管理员。',
  sms_rate_limited_60_seconds: '验证码发送过于频繁，请 60 秒后重试。',
  sms_rate_limited_phone_hour: '该手机号本小时获取验证码次数过多，请稍后重试。',
  sms_rate_limited_phone_day: '该手机号今日获取验证码次数过多，请明日再试。',
  sms_rate_limited_ip_minute: '当前网络请求过于频繁，请稍后重试。',
  sms_rate_limited_ip_hour: '当前网络获取验证码次数过多，请稍后重试。',
}

function smsErrorMessage(error: unknown, fallback: string) {
  const code = readApiErrorCode(error)
  return code ? SMS_ERROR_MESSAGES[code] || fallback : fallback
}

export function profilePhoneSendErrorMessage(error: unknown) {
  return smsErrorMessage(error, '验证码发送失败，请稍后重试。')
}

export function profilePhoneBindErrorMessage(error: unknown) {
  return smsErrorMessage(error, '手机号绑定失败，请稍后重试。')
}
