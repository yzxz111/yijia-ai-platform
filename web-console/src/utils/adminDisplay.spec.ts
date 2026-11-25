import { describe, expect, it } from 'vitest'

import {
  adminChannelLabel,
  adminModelLabel,
  adminProviderLabel,
  adminUpstreamModelLabel,
  adminRoleLabel,
  adminStatusLabel,
  fingerprintLabel,
  grantReasonLabel,
  usageStageLabel,
  userNameLabel,
  userNoLabel,
  userOptionLabel,
  userAccountStatusLabel,
} from './adminDisplay'

describe('admin display helpers', () => {
  it.each([
    ['DRAFT', '草稿'],
    ['PUBLISHED', '已发布'],
    ['ACTIVE', '启用'],
    ['DISABLED', '停用'],
    ['QUEUED', '排队中'],
    ['DELIVERED', '已送达'],
    ['PAID', '已支付'],
    ['CLOSED', '已关闭'],
    ['SUCCEEDED', '成功'],
    ['SETTLED', '已结算'],
    ['PACKAGE_REFUND', '套餐退款'],
    ['VALID', '有效'],
    ['INVALID', '无效'],
    ['COMPLETED', '已完成'],
    ['CANCELLED', '已取消'],
    ['PROCESSING', '处理中'],
    ['PENDING_APPROVAL', '待审核'],
    ['PREPARING', '准备中'],
    ['REJECTED', '已拒绝'],
    ['CANCELED', '已取消'],
    ['SUBMITTING', '提交中'],
    ['REVOKING', '撤销套餐中'],
    ['ADJUST', '调整'],
    ['UP', '正常'],
    ['HEALTHY', '健康'],
    ['DEGRADED', '性能下降'],
    ['DOWN', '不可用'],
    ['UNHEALTHY', '异常'],
    ['UNKNOWN', '未检测'],
  ])('maps status %s to %s', (status, label) => {
    expect(adminStatusLabel(status)).toBe(label)
  })

  it('never exposes unknown backend status codes', () => {
    expect(adminStatusLabel('NEW_INTERNAL_STATE')).toBe('未知状态')
    expect(adminStatusLabel(null)).toBe('未知状态')
  })

  it('maps roles, grant reasons and usage stages', () => {
    expect(adminRoleLabel('ADMIN')).toBe('管理员')
    expect(adminRoleLabel('USER')).toBe('普通用户')
    expect(grantReasonLabel('MANUAL_GRANT')).toBe('管理员手动发放')
    expect(grantReasonLabel('客户补偿')).toBe('客户补偿')
    expect(usageStageLabel('FINAL_RESPONSE')).toBe('最终响应')
    expect(usageStageLabel('FILE_ANALYSIS')).toBe('文件分析')
  })

  it('maps usage models and routing sources to Chinese labels', () => {
    expect(adminModelLabel('text-standard')).toBe('文本标准版')
    expect(adminModelLabel('vision-basic')).toBe('视觉基础版')
    expect(adminModelLabel('text-basic')).toBe('文本基础版')
    expect(adminUpstreamModelLabel('deepseek-chat')).toBe('DeepSeek Chat')
    expect(adminProviderLabel('bailian')).toBe('阿里云百炼')
    expect(adminChannelLabel('bailian-primary')).toBe('百炼主渠道')
    expect(adminProviderLabel('future-provider')).toBe('future-provider')
  })

  it('uses account-management wording for user status', () => {
    expect(userAccountStatusLabel('ACTIVE')).toBe('正常')
    expect(userAccountStatusLabel('DISABLED')).toBe('已封禁')
  })

  it('shows only valid user numbers and reveals only fingerprint suffixes', () => {
    expect(userNoLabel('U00000042')).toBe('U00000042')
    expect(userNoLabel('UABCDEF12')).toBe('UABCDEF12')
    expect(userNoLabel('UABCDEF0123456789')).toBe('未知用户')
    expect(userNoLabel('75000000-0000-4000-8000-000000000002')).toBe('未知用户')
    expect(userNoLabel(null)).toBe('未知用户')
    expect(fingerprintLabel('1234567890abcdef1234567890')).toBe('******567890')
    expect(fingerprintLabel('short-value')).toBe('******-value')
    expect(fingerprintLabel(null)).toBe('未配置')
  })
})

describe('administrator user identity labels', () => {
  it('renders user names with a neutral empty fallback', () => {
    expect(userNameLabel(' 测试用户 ')).toBe('测试用户')
    expect(userNameLabel('')).toBe('-')
    expect(userNameLabel(null)).toBe('-')
  })

  it('combines the stable user number and name in a single selector option', () => {
    expect(userOptionLabel('U00000005', '测试用户')).toBe('U00000005 · 测试用户')
    expect(userOptionLabel('U00000005', null)).toBe('U00000005 · -')
  })
})
