import type { PhoneRegistrationPending } from '../../api/platform'
import { isValidOAuthTransaction } from './desktopOAuth'

const STORAGE_KEY = 'yzh_pending_phone_registration'

export interface PendingPhoneRegistration {
  challengeId: string
  expiresAt: string
  consentVersion: string
  oauthTransaction: string | null
}

let inMemoryRegistration: PendingPhoneRegistration | null = null

function isValidRegistration(value: unknown): value is PendingPhoneRegistration {
  if (!value || typeof value !== 'object') return false
  const pending = value as Partial<PendingPhoneRegistration>
  return isValidOAuthTransaction(pending.challengeId)
    && typeof pending.expiresAt === 'string'
    && Number.isFinite(Date.parse(pending.expiresAt))
    && Date.parse(pending.expiresAt) > Date.now()
    && typeof pending.consentVersion === 'string'
    && pending.consentVersion.trim().length > 0
    && (pending.oauthTransaction === null || isValidOAuthTransaction(pending.oauthTransaction))
}

/** 手机号已验证但账号未创建；仅在当前标签页暂存一次性待注册凭证。 */
export function rememberPendingPhoneRegistration(
  result: PhoneRegistrationPending,
  oauthTransaction: string | null,
  consentVersion: string,
): void {
  const pending = {
    challengeId: result.challengeId,
    expiresAt: result.expiresAt,
    oauthTransaction,
    consentVersion,
  }
  if (!isValidRegistration(pending)) {
    forgetPendingPhoneRegistration()
    return
  }
  inMemoryRegistration = pending
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(pending))
  } catch {
    // 私密模式中仍可在当前页面流程内继续，刷新后需要重新验证短信。
  }
}

export function recallPendingPhoneRegistration(): PendingPhoneRegistration | null {
  if (isValidRegistration(inMemoryRegistration)) return inMemoryRegistration
  let stored: unknown = null
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    stored = raw ? JSON.parse(raw) : null
  } catch {
    // 不可读取或损坏的凭证均视为失效。
  }
  if (isValidRegistration(stored)) {
    inMemoryRegistration = stored
    return stored
  }
  forgetPendingPhoneRegistration()
  return null
}

export function forgetPendingPhoneRegistration(): void {
  inMemoryRegistration = null
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // ignore
  }
}
