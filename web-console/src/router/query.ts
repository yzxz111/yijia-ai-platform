const MAX_QUERY_PAGE_NUMBER = 21_474_837

export function queryText(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

export function queryPageIndex(value: unknown): number {
  const text = queryText(value)
  if (!/^[1-9]\d*$/.test(text)) return 0
  const pageNumber = Number(text)
  return Number.isSafeInteger(pageNumber) && pageNumber <= MAX_QUERY_PAGE_NUMBER
    ? pageNumber - 1
    : 0
}

export function adminDrillQuery(
  query: Record<string, unknown>,
  allowedTabs: readonly string[],
  defaultTab: string,
) {
  const candidateTab = queryText(query.tab)
  return {
    tab: allowedTabs.includes(candidateTab) ? candidateTab : defaultTab,
    search: queryText(query.query),
    requestId: queryText(query.requestId),
    userId: queryText(query.userId),
    orderNo: queryText(query.orderNo),
    refundId: queryText(query.refundId),
    modelCode: queryText(query.modelCode),
    provider: queryText(query.provider),
  }
}
