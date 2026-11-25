import { defineStore } from 'pinia'

import { listPlans, type PackagePlan } from '../api/platform'

export const usePackageStore = defineStore('package', {
  state: () => ({ plans: [] as PackagePlan[], loading: false }),
  actions: {
    async loadPlans() {
      this.loading = true
      try {
        this.plans = await listPlans()
      } finally {
        this.loading = false
      }
    },
  },
})
