export type TerminalSessionEvent =
  | { type: 'HANDOFF_RESOLVED'; requestId: string }
  | { type: 'SESSION_REPLACED' }

const CHANNEL = 'yzh_terminal_session'
const STORAGE_KEY = 'yzh_terminal_session_event'

interface TerminalSessionEnvelope {
  id: string
  event: TerminalSessionEvent
}

function isEnvelope(value: unknown): value is TerminalSessionEnvelope {
  if (!value || typeof value !== 'object') return false
  const envelope = value as { id?: unknown; event?: { type?: unknown; requestId?: unknown } }
  if (typeof envelope.id !== 'string' || !envelope.id || !envelope.event) return false
  if (envelope.event.type === 'SESSION_REPLACED') return true
  return envelope.event.type === 'HANDOFF_RESOLVED'
    && typeof envelope.event.requestId === 'string'
    && Boolean(envelope.event.requestId)
}

export function publishTerminalSessionEvent(event: TerminalSessionEvent) {
  const id = globalThis.crypto?.randomUUID?.()
    || `${Date.now()}-${Math.random().toString(16).slice(2)}`
  const envelope: TerminalSessionEnvelope = { id, event }
  if (typeof BroadcastChannel !== 'undefined') {
    const channel = new BroadcastChannel(CHANNEL)
    channel.postMessage(envelope)
    channel.close()
  }
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(envelope))
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    // 仍可通过 BroadcastChannel 进行跨标签页协同。
  }
}

export function subscribeTerminalSessionEvents(
  listener: (event: TerminalSessionEvent) => void,
): () => void {
  const seen = new Set<string>()
  const accept = (value: unknown) => {
    if (!isEnvelope(value) || seen.has(value.id)) return
    seen.add(value.id)
    listener(value.event)
  }
  const channel = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(CHANNEL)
  if (channel) channel.onmessage = ({ data }) => accept(data)
  const onStorage = (event: StorageEvent) => {
    if (event.key !== STORAGE_KEY || !event.newValue) return
    try { accept(JSON.parse(event.newValue)) } catch { /* 忽略格式错误的跨标签页数据。                   */ }
  }
  window.addEventListener('storage', onStorage)
  return () => {
    channel?.close()
    window.removeEventListener('storage', onStorage)
  }
}
