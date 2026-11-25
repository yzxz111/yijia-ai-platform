/** 首次设密与桌面端首次设密共用同一套密码规则。 */
export function isStrongFirstPassword(value: string): boolean {
  return value.length >= 8 && value.length <= 128
    && new TextEncoder().encode(value).length <= 72
    && /[a-z]/.test(value) && /[A-Z]/.test(value) && /\d/.test(value)
    && /[^A-Za-z0-9]/.test(value)
}
