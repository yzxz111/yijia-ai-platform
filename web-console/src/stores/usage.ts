import { defineStore } from 'pinia'

import {
  getUsageAnalytics,
  listUsage,
  quotaSummary,
  type PageResult,
  type QuotaSummary,
  type UsageEntry,
  type UsageAnalytics,
  type UsageFilters,
  type UsageMetrics,
  usageMetrics,
} from '../api/gateway'

const emptyPage = (): PageResult<UsageEntry> => ({ items: [], total: 0, page: 0, size: 20 })

export const useUsageStore = defineStore('usage', {
  state: () => ({
    quota: null as QuotaSummary | null,
    usage: emptyPage(),
    metrics: null as UsageMetrics | null,
    analytics: null as UsageAnalytics | null,
    analyticsDays: 30 as 7 | 30,
    loadingQuota: false,
    loadingUsage: false,
    loadingMetrics: false,
    loadingAnalytics: false,
    userScopeGeneration: 0,
    quotaLoadSequence: 0,
    usageLoadSequence: 0,
    metricsLoadSequence: 0,
    analyticsLoadSequence: 0,
  }),
  actions: {
    resetUserState() {
      this.userScopeGeneration += 1
      this.quota = null
      this.usage = emptyPage()
      this.metrics = null
      this.analytics = null
      this.loadingQuota = false
      this.loadingUsage = false
      this.loadingMetrics = false
      this.loadingAnalytics = false
    },
    async loadQuota() {
      const generation = this.userScopeGeneration
      const sequence = ++this.quotaLoadSequence
      this.quota = null
      this.loadingQuota = true
      try {
        const result = await quotaSummary()
        if (generation === this.userScopeGeneration && sequence === this.quotaLoadSequence) {
          this.quota = result
          return result
        }
      } catch (error) {
        if (generation === this.userScopeGeneration && sequence === this.quotaLoadSequence) {
          throw error
        }
      } finally {
        if (generation === this.userScopeGeneration && sequence === this.quotaLoadSequence) {
          this.loadingQuota = false
        }
      }
    },
    async loadUsage(filters: UsageFilters = {}) {
      const generation = this.userScopeGeneration
      const sequence = ++this.usageLoadSequence
      this.usage = emptyPage()
      this.loadingUsage = true
      try {
        const result = await listUsage(filters)
        if (generation === this.userScopeGeneration && sequence === this.usageLoadSequence) {
          this.usage = result
          return result
        }
      } catch (error) {
        if (generation === this.userScopeGeneration && sequence === this.usageLoadSequence) {
          throw error
        }
      } finally {
        if (generation === this.userScopeGeneration && sequence === this.usageLoadSequence) {
          this.loadingUsage = false
        }
      }
    },
    async loadMetrics() {
      const generation = this.userScopeGeneration
      const sequence = ++this.metricsLoadSequence
      this.metrics = null
      this.loadingMetrics = true
      try {
        const result = await usageMetrics()
        if (generation === this.userScopeGeneration && sequence === this.metricsLoadSequence) {
          this.metrics = result
          return result
        }
      } catch (error) {
        if (generation === this.userScopeGeneration && sequence === this.metricsLoadSequence) {
          throw error
        }
      } finally {
        if (generation === this.userScopeGeneration && sequence === this.metricsLoadSequence) {
          this.loadingMetrics = false
        }
      }
    },
    async loadAnalytics(days?: 7 | 30) {
      const generation = this.userScopeGeneration
      const sequence = ++this.analyticsLoadSequence
      const selectedDays = days ?? this.analyticsDays
      this.analyticsDays = selectedDays
      this.analytics = null
      this.loadingAnalytics = true
      try {
        const result = await getUsageAnalytics(selectedDays)
        if (generation === this.userScopeGeneration && sequence === this.analyticsLoadSequence) {
          this.analytics = result
          return result
        }
      } catch (error) {
        if (generation === this.userScopeGeneration && sequence === this.analyticsLoadSequence) {
          throw error
        }
      } finally {
        if (generation === this.userScopeGeneration && sequence === this.analyticsLoadSequence) {
          this.loadingAnalytics = false
        }
      }
    },
  },
})
