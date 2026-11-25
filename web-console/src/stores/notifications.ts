import { defineStore } from 'pinia'

import { getUnreadCount } from '../api/notifications'

let pollingTimer: ReturnType<typeof setInterval> | null = null

export const useNotificationsStore = defineStore('notifications', {
  state: () => ({
    unreadCount: 0,
    loadingUnread: false,
    unreadRequestSequence: 0,
  }),
  getters: {
    badgeLabel: (state) => state.unreadCount > 99 ? '99+' : String(state.unreadCount),
  },
  actions: {
    async refreshUnreadCount() {
      const sequence = ++this.unreadRequestSequence
      this.loadingUnread = true
      try {
        const { data } = await getUnreadCount()
        if (sequence === this.unreadRequestSequence) {
          this.unreadCount = Math.max(0, Number.isFinite(data.count) ? data.count : 0)
        }
      } finally {
        if (sequence === this.unreadRequestSequence) this.loadingUnread = false
      }
    },
    startPolling(intervalMs = 60_000) {
      this.stopPolling()
      void this.refreshUnreadCount().catch(() => undefined)
      pollingTimer = setInterval(() => {
        void this.refreshUnreadCount().catch(() => undefined)
      }, intervalMs)
    },
    stopPolling() {
      if (pollingTimer !== null) clearInterval(pollingTimer)
      pollingTimer = null
    },
    decrementUnread() {
      this.unreadCount = Math.max(0, this.unreadCount - 1)
    },
    clearUnread() {
      this.unreadCount = 0
    },
  },
})
