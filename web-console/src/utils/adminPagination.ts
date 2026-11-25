export interface AdminPage<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export async function loadAllPages<T>(
  loadPage: (page: number, size: number) => Promise<AdminPage<T>>,
  size = 100,
): Promise<T[]> {
  const items: T[] = []
  let page = 0
  let totalPages = 1

  while (page < totalPages) {
    const result = await loadPage(page, size)
    items.push(...result.items)
    totalPages = Math.ceil(result.total / Math.max(1, result.size))
    page += 1
  }

  return items
}
