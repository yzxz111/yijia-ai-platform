import type { EmailRegistrationPending } from '../../api/platform'
import { isValidOAuthTransaction } from './desktopOAuth'

const STORAGE_KEY = 'yzh_pending_email_registration'

export interface PendingEmailRegistration {
  challengeId: string
  expiresAt: string
  consentVersion: string
  oauthTransaction: string | null
  email: string
}

let inMemoryRegistration: PendingEmailRegistration | null = null

function isValidRegistration(value: unknown): value is PendingEmailRegistration {
  if (!value || typeof value !== 'object') return false
  const pending = value as Partial<PendingEmailRegistration>
  return isValidOAuthTransaction(pending.challengeId)
    && typeof pending.expiresAt === 'string'
    && Number.isFinite(Date.parse(pending.expiresAt))
    && Date.parse(pending.expiresAt) > Date.now()
    && typeof pending.consentVersion === 'string'
    && pending.consentVersion.trim().length > 0
    && (pending.oauthTransaction === null || isValidOAuthTransaction(pending.oauthTransaction))
    && typeof pending.email === 'string'
    && pending.email === pending.email.trim().toLowerCase()
    && /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(pending.email)
}

/** 邮箱已验证但账号未创建；仅在当前标签页暂存一次性待注册凭证。 */
export function rememberPendingEmailRegistration(
  result: EmailRegistrationPending,
  oauthTransaction: string | null,
  consentVersion: string,
  email: string,
): void {
  const pending = {
    challengeId: result.challengeId,
    expiresAt: result.expiresAt,
    oauthTransaction,
    consentVersion,
    email: typeof email === 'string' ? email.trim().toLowerCase() : '',
  }
  if (!isValidRegistration(pending)) {
    forgetPendingEmailRegistration()
    return
  }
  inMemoryRegistration = pending
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(pending))
  } catch {
    // 私密模式中仍可在当前页面流程内继续，刷新后需要重新验证邮箱。
  }
}

export function recallPendingEmailRegistration(): PendingEmailRegistration | null {
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
  forgetPendingEmailRegistration()
  return null
}

export function forgetPendingEmailRegistration(): void {
  inMemoryRegistration = null
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // ignore
  }
}
