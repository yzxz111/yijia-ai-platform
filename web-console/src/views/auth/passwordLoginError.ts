import { isAxiosError } from 'axios'

/**
 * 登录页能自行解释的后端错误码文案。
 * 原有三条刻意保持不变：401 必须保持
 * 语义模糊（绝不透露账号是否存在），限流与
 * 设备数上限文案由 passwordLoginError.spec.ts 钉死。
 */
const CODE_MESSAGES: Record<string, string> = {
  invalid_credentials: '账号或密码错误',
  login_rate_limited: '尝试次数过多，请稍后重试',
  login_device_limit_reached: '登录设备已达上限',
  invalid_device_id: '浏览器环境异常，请清除本站点数据后重试',
  account_safety_restricted: '账号已被限制登录，请联系管理员',
  account_disabled: '账号已停用，请联系管理员',
  account_locked: '账号已锁定，请联系管理员',
  password_not_set: '该账号尚未设置密码，请联系管理员设置密码',
}

/**
 * 把失败的密码登录转成指明真实原因的消息。
 *
 * 过去，所有非已知后端错误码都会坍缩成
 * 「账号或密码错误」，使平台服务故障（nginx 返回 502）看起来
 * 像是密码错误。现在，服务故障、主机不可达与未映射的错误码
 * 都会如实上报，文案中保留原始状态码／错误码，
 * 一张截图就足以定位问题。
 */
export function passwordLoginErrorMessage(error: unknown): string {
  if (!isAxiosError<{ code?: string }>(error)) {
    return '登录失败，请检查网络后重试'
  }

  const status = error.response?.status
  const code = error.response?.data?.code

  if (typeof code === 'string' && CODE_MESSAGES[code]) {
    return CODE_MESSAGES[code]
  }

  if (status === undefined) {
    return '无法连接服务器，请检查网络后重试'
  }

  // 网关／服务故障不是凭据问题。
  if (status >= 500) {
    return `服务暂时不可用（${status}），请稍后重试`
  }

  if (status === 401) return '账号或密码错误'
  if (status === 429) return '尝试次数过多，请稍后重试'

  const detail = typeof code === 'string' && code ? code : `HTTP ${status}`
  return `登录失败：${detail}（请将本条提示反馈给管理员）`
}
