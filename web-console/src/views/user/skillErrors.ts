/**
 * `/console/gateway/skills` 错误码 → 稳定中文文案。
 *
 * 契约与 `/v1/skills` 同构：错误体为
 * `{"error":{"message","type","code"},"request_id":"req_..."}`，
 * 兜底还会读响应头 `x-request-id`。页面只展示这里的映射文案，
 * 不直接把服务端 `message` 渲染出来（避免泄露英文原文与内部细节）。
 */
export interface SkillFailure {
  /** 后端错误码，未识别时为空字符串 */
  code: string
  /** 可直接展示的中文文案 */
  message: string
  /** 便于排查的请求编号，缺失时为空字符串 */
  requestId: string
}

export const SKILL_ERROR_MESSAGES: Record<string, string> = {
  invalid_skill_name: 'Skill 名称不符合命名规则。',
  skill_not_found: 'Skill 不存在或已下架。',
  invalid_skill_metadata: 'Skill 元数据不合法，请联系管理员检查 skill.json。',
  skill_file_too_large: 'Skill 内单个文件超出大小限制。',
  skill_too_large: 'Skill 超出大小限制。',
  skill_too_many_files: 'Skill 文件数量超出限制。',
  skill_storage_unavailable: 'Skill 存储暂时不可用，请稍后重试。',
}

const SKILL_STATUS_MESSAGES: Record<number, string> = {
  400: '请求不符合 Skill 接口要求，请检查输入。',
  401: '登录状态已失效，请重新登录后再试。',
  403: '当前账号没有查看 Skill 市场的权限。',
  404: 'Skill 不存在或已下架。',
  422: 'Skill 内容不符合接口要求。',
  429: '请求过于频繁，请稍后重试。',
  500: 'Skill 服务暂时不可用，请稍后重试。',
  503: 'Skill 存储暂时不可用，请稍后重试。',
}

export const SKILL_NETWORK_MESSAGE = '无法连接到 Skill 服务，请检查网络后重试。'
export const SKILL_UNKNOWN_MESSAGE = 'Skill 服务暂时不可用，请稍后重试。'

function firstText(...values: unknown[]): string {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) return value.trim()
  }
  return ''
}

/** 把任意异常收敛成「文案 + code + request_id」三元组 */
export function skillFailure(error: unknown): SkillFailure {
  const response = (error as {
    response?: { status?: number; headers?: Record<string, unknown>; data?: unknown }
  })?.response
  const body = response?.data as {
    error?: { code?: unknown; message?: unknown }
    request_id?: unknown
    requestId?: unknown
  } | undefined
  const code = firstText(body?.error?.code)
  const requestId = firstText(body?.request_id, body?.requestId, response?.headers?.['x-request-id'])
  const status = typeof response?.status === 'number' ? response.status : undefined
  const message = code && SKILL_ERROR_MESSAGES[code]
    ? SKILL_ERROR_MESSAGES[code]
    : (status !== undefined && SKILL_STATUS_MESSAGES[status]) || SKILL_UNKNOWN_MESSAGE

  return { code, message, requestId }
}

/** 网络层失败（没有响应）与接口失败的区分 */
export function skillErrorMessage(error: unknown): SkillFailure {
  const response = (error as { response?: unknown })?.response
  const failure = skillFailure(error)
  if (!response) return { ...failure, message: SKILL_NETWORK_MESSAGE }
  return failure
}
