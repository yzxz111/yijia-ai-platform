interface ApiErrorResponse {
  response?: {
    data?: unknown
  }
}

function readApiErrorData(error: unknown): Record<string, unknown> | null {
  if (!error || typeof error !== 'object') return null

  const data = (error as ApiErrorResponse).response?.data
  if (!data || typeof data !== 'object') return null
  return data as Record<string, unknown>
}

export function readApiErrorCode(error: unknown): string | null {
  const data = readApiErrorData(error)
  const code = data?.code
  if (typeof code !== 'string') return null

  const trimmed = code.trim()
  return trimmed || null
}

export function readApiErrorMessage(error: unknown): string | null {
  const data = readApiErrorData(error)

  const message = data?.message
  if (typeof message !== 'string') return null

  const trimmed = message.trim()
  return trimmed || null
}
