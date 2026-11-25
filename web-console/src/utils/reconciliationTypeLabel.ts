const RECONCILIATION_TYPE_LABELS: Record<string, string> = {
  MISSING: '经营事实缺失',
  DUPLICATE: '事实记录重复',
  AMOUNT_MISMATCH: '金额不一致',
  ESTIMATED_NOT_ACTUAL: '估算值未转实际',
  MISSING_SOURCE: '来源记录缺失',
  GATEWAY_OUTBOX_BACKLOG: '网关消息积压',
  AGGREGATE_MISMATCH: '汇总金额不一致',
  PAYLOAD_HASH_MISMATCH: '数据摘要不一致',
}

export function reconciliationTypeLabel(type: string | null | undefined): string {
  const normalized = type?.trim().toUpperCase()
  if (!normalized) return '其他差异'
  return RECONCILIATION_TYPE_LABELS[normalized] || '其他差异'
}

