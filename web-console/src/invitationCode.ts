const INVITATION_CODE_PATTERN = /^[A-Za-z0-9]{32}$/

const STORAGE_KEY = 'yzhh.invitation-code'

export function normalizeInvitationCode(value: string | undefined): string | undefined {
  const normalized = value?.trim()
  return normalized || undefined
}

export function isValidOptionalInvitationCode(value: string | undefined): boolean {
  const normalized = normalizeInvitationCode(value)
  return normalized === undefined || INVITATION_CODE_PATTERN.test(normalized)
}

/**
 * 把邀请链接里的码暂存到当前标签页。
 *
 * 为什么需要：桌面端（软件内）的注册/设密是客户端自己跳转的，`?invite=` 很容易在中途丢掉，
 * 只靠 query 传递会让软件里的邀请码输入框是空的。存一份会话级副本，
 * 设密页在 query 没有时回退读它。
 *
 * 用 sessionStorage（不是 localStorage）：换标签页/换人注册不会被上一个邀请码污染。
 */
export function rememberInvitationCode(value: string | undefined): void {
  const code = normalizeInvitationCode(value)
  if (!code || !INVITATION_CODE_PATTERN.test(code)) {
    return
  }
  try {
    window.sessionStorage.setItem(STORAGE_KEY, code)
  } catch {
    // 隐私模式等禁用 storage 的场景：当作没暂存，用户仍可手输。
  }
}

export function recallInvitationCode(): string {
  try {
    return window.sessionStorage.getItem(STORAGE_KEY) ?? ''
  } catch {
    return ''
  }
}

export function forgetInvitationCode(): void {
  try {
    window.sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // 同上。
  }
}

/**
 * 处理邀请链接带来的 invite 参数：显式带码进入时，先丢掉此前流程暂存的那份再存新的，
 * 避免上一轮注册的邀请码污染这一轮。返回归一化后的码（不合法时返回空串）。
 */
export function captureInvitationCodeFromQuery(value: unknown): string {
  forgetInvitationCode()
  if (typeof value !== 'string') {
    return ''
  }
  const normalized = normalizeInvitationCode(value) ?? ''
  rememberInvitationCode(normalized)
  return normalized
}
