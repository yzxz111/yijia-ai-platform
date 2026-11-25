import { getProfileAvatar, type ProfileView } from '../api/platform'
import { useAuthStore } from '../stores/auth'

let pendingSync: { key: string; promise: Promise<void> } | null = null

export async function syncProfileAvatar(
  profile: Pick<ProfileView, 'id' | 'avatarUrl' | 'avatarUpdatedAt'>,
): Promise<void> {
  const auth = useAuthStore()
  if (auth.user?.id !== profile.id) return

  auth.updateAvatar(profile.avatarUrl, profile.avatarUpdatedAt)
  if (!profile.avatarUrl || !profile.avatarUpdatedAt) {
    auth.replaceAvatarObjectUrl(null, null)
    return
  }
  if (auth.avatarObjectUrl && auth.avatarObjectVersion === profile.avatarUpdatedAt) return
  if (typeof URL.createObjectURL !== 'function') return

  const key = `${profile.id}:${profile.avatarUpdatedAt}`
  if (pendingSync?.key === key) return pendingSync.promise

  const promise = getProfileAvatar().then((blob) => {
    const objectUrl = URL.createObjectURL(blob)
    const current = auth.user
    if (current?.id === profile.id && current.avatarUpdatedAt === profile.avatarUpdatedAt) {
      auth.replaceAvatarObjectUrl(objectUrl, profile.avatarUpdatedAt)
    } else if (typeof URL.revokeObjectURL === 'function') {
      URL.revokeObjectURL(objectUrl)
    }
  }).finally(() => {
    if (pendingSync?.key === key) pendingSync = null
  })

  pendingSync = { key, promise }
  return promise
}
