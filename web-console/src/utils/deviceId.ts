const STORAGE_KEY = 'yzh_device_id'

let sessionDeviceId: string | undefined

function randomHex128(): { value: string; secure: boolean } {
  const bytes = new Uint8Array(16)
  let secure = false
  if (globalThis.crypto?.getRandomValues) {
    globalThis.crypto.getRandomValues(bytes)
    secure = true
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256)
    }
  }
  return {
    value: Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join(''),
    secure,
  }
}

export function getDeviceId(): string {
  if (sessionDeviceId) return sessionDeviceId
  try {
    const persisted = globalThis.localStorage?.getItem(STORAGE_KEY)
    if (persisted && /^[0-9a-f]{32}$/.test(persisted)) {
      sessionDeviceId = persisted
      return persisted
    }
  } catch {
    // 隐私模式下存储可能不可用；下面保留内存中的 ID。
  }

  const generated = randomHex128()
  sessionDeviceId = generated.value
  if (generated.secure) {
    try {
      globalThis.localStorage?.setItem(STORAGE_KEY, sessionDeviceId)
    } catch {
      // 持久化被拒绝时，会话内的值仍然可用。
    }
  }
  return sessionDeviceId
}
