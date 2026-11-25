const STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  ACTIVE: '启用',
  DISABLED: '停用',
  ENABLED: '启用',
  QUEUED: '排队中',
  PENDING: '待处理',
  CREATED: '已创建',
  DELIVERING: '投递中',
  DELIVERED: '已送达',
  PAID: '已支付',
  CLOSED: '已关闭',
  REFUNDED: '已退款',
  PARTIALLY_REFUNDED: '部分退款',
  SUCCEEDED: '成功',
  FAILED: '失败',
  REVOKED: '已撤销',
  EXPIRED: '已过期',
  RESERVED: '已预留',
  SETTLED: '已结算',
  RELEASED: '已释放',
  RECHARGE: '充值',
  PACKAGE_PURCHASE: '套餐购买',
  PACKAGE_REFUND: '套餐退款',
  MODEL_USAGE: '模型调用扣款',
  RESERVE: '预留',
  SETTLE: '结算',
  RELEASE: '释放',
  VALID: '有效',
  INVALID: '无效',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  PROCESSING: '处理中',
  PENDING_APPROVAL: '待审核',
  PREPARING: '准备中',
  REJECTED: '已拒绝',
  CANCELED: '已取消',
  SUBMITTING: '提交中',
  REVOKING: '撤销套餐中',
  ADJUST: '调整',
  UP: '正常',
  HEALTHY: '健康',
  DEGRADED: '性能下降',
  DOWN: '不可用',
  UNHEALTHY: '异常',
  UNKNOWN: '未检测',
}

const ROLE_LABELS: Record<string, string> = {
  ADMIN: '管理员',
  USER: '普通用户',
}

const GRANT_REASON_LABELS: Record<string, string> = {
  MANUAL: '管理员手动发放',
  MANUAL_GRANT: '管理员手动发放',
  ADMIN_GRANT: '管理员手动发放',
  ORDER_PAYMENT: '订单支付后发放',
  PACKAGE_PURCHASE: '套餐购买',
}

const USAGE_STAGE_LABELS: Record<string, string> = {
  FINAL_RESPONSE: '最终响应',
  FILE_ANALYSIS: '文件分析',
}

const MODEL_LABELS: Record<string, string> = {
  'text-basic': '文本基础版',
  'text-standard': '文本标准版',
  'text-pro': '文本专业版',
  'text-max': '文本旗舰版',
  'vision-basic': '视觉基础版',
  'vision-pro': '视觉专业版',
  'file-basic': '文件基础版',
  'multimodal-basic': '多模态基础版',
}

const UPSTREAM_MODEL_LABELS: Record<string, string> = {
  'deepseek-chat': 'DeepSeek Chat',
  'deepseek-reasoner': 'DeepSeek Reasoner',
  'qwen-turbo': '通义千问 Turbo',
  'qwen-plus': '通义千问 Plus',
  'qwen-max': '通义千问 Max',
  'qwen-vl-plus': '通义千问视觉 Plus',
  'qwen-vl-max': '通义千问视觉 Max',
  'qwen-long': '通义千问 Long',
  'qwen3.7-flash': '通义千问 3.7 Flash',
  'qwen3.7-plus': '通义千问 3.7 Plus',
  'qwen3.7-max': '通义千问 3.7 Max',
  'qwen3-vl-flash': '通义千问 3 视觉 Flash',
  'qwen3-vl-plus': '通义千问 3 视觉 Plus',
  'text-basic': '文本基础模型',
  'vision-basic': '视觉基础模型',
  'file-basic': '文件基础模型',
}

const PROVIDER_LABELS: Record<string, string> = {
  deepseek: 'DeepSeek',
  bailian: '阿里云百炼',
  'local-mock': '本地模拟服务',
}

const CHANNEL_LABELS: Record<string, string> = {
  'deepseek-primary': 'DeepSeek 主渠道',
  'bailian-primary': '百炼主渠道',
  'local-mock-primary': '本地模拟主渠道',
  'local-mock-failover': '本地模拟故障转移渠道',
}

function normalized(value: string | null | undefined) {
  return value?.trim().toUpperCase() || ''
}

export function adminStatusLabel(status: string | null | undefined): string {
  return STATUS_LABELS[normalized(status)] || '未知状态'
}

export function adminRoleLabel(role: string | null | undefined): string {
  return ROLE_LABELS[normalized(role)] || '未知角色'
}

export function grantReasonLabel(reason: string | null | undefined): string {
  const value = reason?.trim()
  if (!value) return '未填写'
  return GRANT_REASON_LABELS[normalized(value)] || value
}

export function usageStageLabel(stage: string | null | undefined): string {
  return USAGE_STAGE_LABELS[normalized(stage)] || '未知阶段'
}

export function adminModelLabel(modelCode: string | null | undefined): string {
  const code = modelCode?.trim() || ''
  return MODEL_LABELS[code] || code || '未知模型'
}

export function adminUpstreamModelLabel(modelCode: string | null | undefined): string {
  const code = modelCode?.trim() || ''
  return UPSTREAM_MODEL_LABELS[code] || code || '未知上游模型'
}

export function adminProviderLabel(providerCode: string | null | undefined): string {
  const code = providerCode?.trim() || ''
  return PROVIDER_LABELS[code] || code || '未知来源'
}

export function adminChannelLabel(channelCode: string | null | undefined): string {
  const code = channelCode?.trim() || ''
  return CHANNEL_LABELS[code] || code || '未知渠道'
}

export function fingerprintLabel(fingerprint: string | null | undefined): string {
  const value = fingerprint?.trim()
  if (!value) return '未配置'
  return `******${value.slice(-6)}`
}

export function userNoLabel(userNo: string | null | undefined): string {
  const value = userNo?.trim().toUpperCase()
  return value && /^U[0-9A-Z]{8}$/.test(value) ? value : '未知用户'
}

export function userNameLabel(displayName: string | null | undefined): string {
  return displayName?.trim() || '-'
}

export function userAccountStatusLabel(status: string | null | undefined): string {
  return normalized(status) === 'ACTIVE' ? '正常' : normalized(status) === 'DISABLED' ? '已封禁' : '未知状态'
}

export function userOptionLabel(
  userNo: string | null | undefined,
  displayName: string | null | undefined,
): string {
  return `${userNoLabel(userNo)} · ${userNameLabel(displayName)}`
}
