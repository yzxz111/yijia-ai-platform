import { AxiosError, AxiosHeaders } from 'axios'
import { describe, expect, it } from 'vitest'

import { passwordLoginErrorMessage } from './passwordLoginError'

function apiError(code: string, status = code === 'login_rate_limited' ? 429 : 401) {
  return new AxiosError(
    'raw backend message',
    undefined,
    undefined,
    undefined,
    {
      data: { code },
      status,
      statusText: 'error',
      headers: {},
      config: { headers: new AxiosHeaders() },
    },
  )
}

function statusOnlyError(status: number, data: unknown = undefined) {
  return new AxiosError('raw backend message', undefined, undefined, undefined, {
    data,
    status,
    statusText: 'error',
    headers: {},
    config: { headers: new AxiosHeaders() },
  })
}

describe('passwordLoginErrorMessage', () => {
  it('maps invalid credentials to the approved generic message', () => {
    expect(passwordLoginErrorMessage(apiError('invalid_credentials')))
      .toBe('账号或密码错误')
  })

  it('maps rate limiting to the approved retry message', () => {
    expect(passwordLoginErrorMessage(apiError('login_rate_limited')))
      .toBe('尝试次数过多，请稍后重试')
  })

  it('maps the five-device cap to an actionable message', () => {
    expect(passwordLoginErrorMessage(apiError('login_device_limit_reached')))
      .toBe('登录设备已达上限')
  })

  it('keeps a bare 401 generic so account existence is never revealed', () => {
    expect(passwordLoginErrorMessage(statusOnlyError(401)))
      .toBe('账号或密码错误')
  })

  it('reports a gateway outage as an outage, not a credential error', () => {
    expect(passwordLoginErrorMessage(statusOnlyError(502))).toBe('服务暂时不可用（502），请稍后重试')
    expect(passwordLoginErrorMessage(statusOnlyError(500))).toBe('服务暂时不可用（500），请稍后重试')
    expect(passwordLoginErrorMessage(statusOnlyError(503))).toBe('服务暂时不可用（503），请稍后重试')
  })

  it('reports an unreachable server instead of blaming the password', () => {
    expect(passwordLoginErrorMessage(new AxiosError('Network Error', 'ERR_NETWORK')))
      .toBe('无法连接服务器，请检查网络后重试')
  })

  it('names the reason for a malformed device id', () => {
    expect(passwordLoginErrorMessage(apiError('invalid_device_id', 422)))
      .toBe('浏览器环境异常，请清除本站点数据后重试')
  })

  it('surfaces restricted or disabled accounts', () => {
    expect(passwordLoginErrorMessage(apiError('account_safety_restricted', 403)))
      .toBe('账号已被限制登录，请联系管理员')
    expect(passwordLoginErrorMessage(apiError('account_disabled', 403)))
      .toBe('账号已停用，请联系管理员')
  })

  it('asks an administrator to set a missing password without code login', () => {
    expect(passwordLoginErrorMessage(apiError('password_not_set', 403)))
      .toBe('该账号尚未设置密码，请联系管理员设置密码')
  })

  it('falls back to the raw code plus status for unmapped failures', () => {
    expect(passwordLoginErrorMessage(apiError('some_new_code', 409)))
      .toBe('登录失败：some_new_code（请将本条提示反馈给管理员）')
    expect(passwordLoginErrorMessage(statusOnlyError(418)))
      .toBe('登录失败：HTTP 418（请将本条提示反馈给管理员）')
  })

  it('never hides a non-axios failure behind a credential message', () => {
    expect(passwordLoginErrorMessage(new Error('boom'))).toBe('登录失败，请检查网络后重试')
  })
})
