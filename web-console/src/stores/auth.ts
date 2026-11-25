import { defineStore } from 'pinia'

import { useApiKeysStore } from './apiKeys'
import { useUsageStore } from './usage'
import { useWalletStore } from './wallet'

export type UserRole = 'USER' | 'ADMIN'

export interface AuthenticatedUser {
  id: string
  tenantId: string
  email: string | null
  displayName?: string
  role: UserRole
  status?: string
  passwordSet?: boolean
  avatarUrl?: string | null
  avatarUpdatedAt?: string | null
}

interface AuthState {
  accessToken: string | null
  user: AuthenticatedUser | null
  sessionEpoch: number
  avatarObjectUrl: string | null
  avatarObjectVersion: string | null
}

function revokeObjectUrl(url: string | null) {
  if (url && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url)
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    accessToken: null,
    user: null,
    sessionEpoch: 0,
    avatarObjectUrl: null,
    avatarObjectVersion: null,
  }),
  getters: {
    isAuthenticated: (state) => Boolean(state.accessToken && state.user),
    isAdmin: (state) => state.user?.role === 'ADMIN',
    isRestricted: (state) => Boolean(state.user && (state.user.status || 'ACTIVE') !== 'ACTIVE'),
  },
  actions: {
    setSession(accessToken: string, user: AuthenticatedUser) {
      const sameIdentity = this.user?.tenantId === user.tenantId && this.user.id === user.id
      if (!sameIdentity) {
        this.replaceAvatarObjectUrl(null, null)
        this.sessionEpoch += 1
        useApiKeysStore().resetUserState()
        useUsageStore().resetUserState()
        useWalletStore().resetUserState()
      }
      this.accessToken = accessToken
      // 不在这里补 `status` 默认值：`isRestricted` 与 `updateStatus` 都已用
      // `status || 'ACTIVE'` 兜底，这里再注入一次会让 `store.user` 多出调用方没给的字段，
      // 使「会话就是登录返回的那个身份」这一契约失真。
      this.user = { ...user }
    },
    clearSession() {
      this.replaceAvatarObjectUrl(null, null)
      this.sessionEpoch += 1
      this.accessToken = null
      this.user = null
      useApiKeysStore().resetUserState()
      useUsageStore().resetUserState()
      useWalletStore().resetUserState()
    },
    updateDisplayName(displayName: string) {
      if (this.user) this.user = { ...this.user, displayName }
    },
    updateAvatar(avatarUrl: string | null, avatarUpdatedAt: string | null) {
      if (this.user) this.user = { ...this.user, avatarUrl, avatarUpdatedAt }
    },
    updateAccountStatus(status: string) {
      if (this.user) this.user = { ...this.user, status: status || 'ACTIVE' }
    },
    replaceAvatarObjectUrl(objectUrl: string | null, version: string | null) {
      if (this.avatarObjectUrl !== objectUrl) revokeObjectUrl(this.avatarObjectUrl)
      this.avatarObjectUrl = objectUrl
      this.avatarObjectVersion = version
    },
  },
})
