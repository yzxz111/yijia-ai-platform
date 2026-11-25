import { defineStore } from 'pinia'

import {
  changeApiKeyStatus,
  createApiKey,
  listApiKeys,
  resetApiKey,
  revokeApiKey,
  type ApiKeyStatus,
  type ApiKeyView,
  type CreatedApiKey,
  type ResetApiKey,
} from '../api/gateway'

export const useApiKeysStore = defineStore('apiKeys', {
  state: () => ({
    keys: [] as ApiKeyView[],
    loading: false,
    userScopeGeneration: 0,
    loadSequence: 0,
    mutationSequences: {} as Record<string, number>,
  }),
  getters: {
    activeCount: (state) => state.keys.filter((key) => key.status === 'ACTIVE').length,
  },
  actions: {
    resetUserState() {
      this.userScopeGeneration += 1
      this.keys = []
      this.loading = false
      this.mutationSequences = {}
    },
    async loadKeys() {
      const generation = this.userScopeGeneration
      const sequence = ++this.loadSequence
      this.keys = []
      this.loading = true
      try {
        const result = await listApiKeys()
        if (generation === this.userScopeGeneration && sequence === this.loadSequence) {
          this.keys = result.filter((key) => key.status !== 'REVOKED')
        }
      } finally {
        if (generation === this.userScopeGeneration && sequence === this.loadSequence) {
          this.loading = false
        }
      }
    },
    async create(name: string, allowedModels: string[]): Promise<CreatedApiKey> {
      const generation = this.userScopeGeneration
      const created = await createApiKey(name, allowedModels)
      if (generation === this.userScopeGeneration) {
        this.keys.unshift({
          id: created.id,
          name: created.name,
          keyPrefix: created.keyPrefix,
          allowedModels: created.allowedModels,
          status: created.status,
          lastUsedAt: null,
          expiresAt: created.expiresAt,
          createdAt: new Date().toISOString(),
        })
      }
      return created
    },
    async revoke(id: string) {
      const generation = this.userScopeGeneration
      const sequence = (this.mutationSequences[id] ?? 0) + 1
      this.mutationSequences[id] = sequence
      await revokeApiKey(id)
      if (generation !== this.userScopeGeneration || sequence !== this.mutationSequences[id]) return
      this.keys = this.keys.filter((item) => item.id !== id)
    },
    async changeStatus(id: string, status: ApiKeyStatus) {
      const generation = this.userScopeGeneration
      const sequence = (this.mutationSequences[id] ?? 0) + 1
      this.mutationSequences[id] = sequence
      const updated = await changeApiKeyStatus(id, status)
      if (generation === this.userScopeGeneration && sequence === this.mutationSequences[id]) {
        const index = this.keys.findIndex((item) => item.id === id)
        if (index >= 0) this.keys[index] = updated
      }
      return updated
    },
    async reset(id: string, smsProof: string): Promise<ResetApiKey> {
      return resetApiKey(id, smsProof)
    },
    applyResetPrefix(id: string, keyPrefix: string) {
      const key = this.keys.find((item) => item.id === id && item.status !== 'REVOKED')
      if (key) key.keyPrefix = keyPrefix
    },
  },
})
