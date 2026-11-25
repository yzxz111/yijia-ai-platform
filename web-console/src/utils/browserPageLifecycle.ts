export function createBrowserPageLifecycle() {
  // This is a page label, not an authentication secret. Keep legacy HTTP hosts usable too.
  const pageId = globalThis.crypto?.randomUUID?.() ?? 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (digit) => {
    const value = Math.floor(Math.random() * 16)
    return (digit === 'x' ? value : (value & 3) | 8).toString(16)
  })
  let sequence = 0
  let closed = false
  function headers() {
    return { 'X-Browser-Page': pageId, 'X-Browser-Sequence': String(++sequence) }
  }
  return {
    heartbeat: () => closed ? null : headers(),
    close: () => {
      if (closed) return null
      closed = true
      return headers()
    },
  }
}
