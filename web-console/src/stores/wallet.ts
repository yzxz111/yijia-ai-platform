import { defineStore } from 'pinia'

import {
  getRechargeOptions,
  getWalletSummary,
  listRecharges,
  listWalletLedger,
  type PageResult,
  type RechargeOptionCatalog,
  type RechargeOrder,
  type WalletLedgerEntry,
  type WalletPageParams,
  type WalletSummary,
} from '../api/wallet'

function emptyPage<T>(): PageResult<T> {
  return { items: [], total: 0, page: 0, size: 20 }
}

export const useWalletStore = defineStore('wallet', {
  state: () => ({
    summary: null as WalletSummary | null,
    rechargeOptions: null as RechargeOptionCatalog | null,
    recharges: emptyPage<RechargeOrder>(),
    ledger: emptyPage<WalletLedgerEntry>(),
    loadingSummary: false,
    loadingRechargeOptions: false,
    loadingRecharges: false,
    loadingLedger: false,
    userScopeGeneration: 0,
    summaryLoadSequence: 0,
    optionsLoadSequence: 0,
    rechargesLoadSequence: 0,
    ledgerLoadSequence: 0,
  }),
  actions: {
    resetUserState() {
      this.userScopeGeneration += 1
      this.summary = null
      this.rechargeOptions = null
      this.recharges = emptyPage()
      this.ledger = emptyPage()
      this.loadingSummary = false
      this.loadingRechargeOptions = false
      this.loadingRecharges = false
      this.loadingLedger = false
    },
    async loadSummary() {
      const generation = this.userScopeGeneration
      const sequence = ++this.summaryLoadSequence
      this.summary = null
      this.loadingSummary = true
      try {
        const result = await getWalletSummary()
        if (generation === this.userScopeGeneration && sequence === this.summaryLoadSequence) {
          this.summary = result
          return result
        }
      } catch (error) {
        if (generation === this.userScopeGeneration && sequence === this.summaryLoadSequence) {
          throw error
        }
      } finally {
        if (generation === this.userScopeGeneration && sequence === this.summaryLoadSequence) {
          this.loadingSummary = false
        }
      }
    },
    async loadRechargeOptions() {
      const generation = this.userScopeGeneration
      const sequence = ++this.optionsLoadSequence
      this.rechargeOptions = null
      this.loadingRechargeOptions = true
      try {
        const result = await getRechargeOptions()
        if (generation === this.userScopeGeneration && sequence === this.optionsLoadSequence) {
          this.rechargeOptions = result
          return result
        }
      } catch (error) {
        if (generation === this.userScopeGeneration && sequence === this.optionsLoadSequence) {
          throw error
        }
      } finally {
        if (generation === this.userScopeGeneration && sequence === this.optionsLoadSequence) {
          this.loadingRechargeOptions = false
        }
      }
    },
    async loadRecharges(params: WalletPageParams = {}) {
      const generation = this.userScopeGeneration
      const sequence = ++this.rechargesLoadSequence
      this.recharges = emptyPage()
      this.loadingRecharges = true
      try {
        const result = await listRecharges(params)
        if (generation === this.userScopeGeneration && sequence === this.rechargesLoadSequence) {
          this.recharges = result
          return result
        }
      } catch (error) {
        if (generation === this.userScopeGeneration && sequence === this.rechargesLoadSequence) {
          throw error
        }
      } finally {
        if (generation === this.userScopeGeneration && sequence === this.rechargesLoadSequence) {
          this.loadingRecharges = false
        }
      }
    },
    async loadLedger(params: WalletPageParams = {}) {
      const generation = this.userScopeGeneration
      const sequence = ++this.ledgerLoadSequence
      this.ledger = emptyPage()
      this.loadingLedger = true
      try {
        const result = await listWalletLedger(params)
        if (generation === this.userScopeGeneration && sequence === this.ledgerLoadSequence) {
          this.ledger = result
          return result
        }
      } catch (error) {
        if (generation === this.userScopeGeneration && sequence === this.ledgerLoadSequence) {
          throw error
        }
      } finally {
        if (generation === this.userScopeGeneration && sequence === this.ledgerLoadSequence) {
          this.loadingLedger = false
        }
      }
    },
  },
})
