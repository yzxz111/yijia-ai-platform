const STATUS_LABELS: Record<string, string> = {
  ACTIVE: '正常',
  ENABLED: '正常',
  DISABLED: '已停用',
  FROZEN: '已冻结',
  SUSPENDED: '已暂停',
  ABNORMAL: '异常',
  BANNED: '已封禁',
  REVOKED: '已撤销',
  EXPIRED: '已过期',
  QUEUED: '排队中',
  PENDING: '处理中',
  FAILED: '失败',
  CLOSED: '已关闭',
  ARCHIVED: '已归档',
}

export function statusLabel(status: string | null | undefined): string {
  const normalized = status?.trim().toUpperCase()
  if (!normalized) return '未知状态'
  return STATUS_LABELS[normalized] || '未知状态'
}
