import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest'

const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}))

vi.mock('../../src/api/http', () => ({
  platformHttp: { get: http.get, post: http.post, put: http.put },
}))

import {
  closeRecharge,
  createRecharge,
  createRechargePaymentAttempt,
  getRechargeDetail,
  getRechargeOptions,
  getWalletSummary,
  listAdminWalletAccounts,
  listAdminWalletLedger,
  listAdminWalletRecharges,
  listAdminWalletRechargeOptions,
  listRecharges,
  listWalletLedger,
  payOrderWithBalance,
  replaceAdminWalletRechargeOptions,
  type SettlementMethod,
  type WalletSummary,
} from '../../src/api/wallet'
import { useAuthStore } from '../../src/stores/auth'
import { useWalletStore } from '../../src/stores/wallet'

const summary: WalletSummary = {
  availableAmount: 12345,
  currency: 'CNY',
  updatedAt: '2026-08-05T03:04:05Z',
}

describe('wallet API contract', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    http.get.mockResolvedValue({ data: {} })
    http.post.mockResolvedValue({ data: {} })
    http.put.mockResolvedValue({ data: {} })
  })

  it('models CNY money as backend-owned integer minor-unit values', () => {
    expectTypeOf<WalletSummary>().toEqualTypeOf<{
      availableAmount: number
      currency: 'CNY'
      updatedAt: string
    }>()
    expectTypeOf<SettlementMethod>().toEqualTypeOf<'UNSELECTED' | 'EXTERNAL' | 'BALANCE'>()
    expect(Number.isInteger(summary.availableAmount)).toBe(true)
  })

  it('loads the wallet summary and recharge option catalog from exact user routes', async () => {
    await getWalletSummary()
    await getRechargeOptions()

    expect(http.get).toHaveBeenCalledWith('/console/platform/wallet')
    expect(http.get).toHaveBeenCalledWith('/console/platform/wallet/recharge-options')
  })

  it('creates a recharge with only minor units and an idempotency key', async () => {
    await createRecharge({ amount: 12345, optionId: 'option-1' }, 'recharge-key-1')
    await createRecharge({ amount: 6789 }, 'recharge-key-2')

    expect(http.post).toHaveBeenNthCalledWith(
      1,
      '/console/platform/wallet/recharges',
      { amount: 12345, optionId: 'option-1' },
      { headers: { 'Idempotency-Key': 'recharge-key-1' } },
    )
    expect(http.post).toHaveBeenNthCalledWith(
      2,
      '/console/platform/wallet/recharges',
      { amount: 6789 },
      { headers: { 'Idempotency-Key': 'recharge-key-2' } },
    )
  })

  it('encodes recharge numbers and sends only provider attempt fields', async () => {
    await getRechargeDetail('recharge/001')
    await closeRecharge('recharge/001')
    await createRechargePaymentAttempt(
      'recharge/001',
      { provider: 'WECHAT_PAY', product: 'WECHAT_NATIVE', smsProof: 'proof-1' },
    )

    expect(http.get).toHaveBeenCalledWith('/console/platform/wallet/recharges/recharge%2F001')
    expect(http.post).toHaveBeenCalledWith(
      '/console/platform/wallet/recharges/recharge%2F001/close',
    )
    expect(http.post).toHaveBeenCalledWith(
      '/console/platform/wallet/recharges/recharge%2F001/payment-attempts',
      { provider: 'WECHAT_PAY', product: 'WECHAT_NATIVE', smsProof: 'proof-1' },
    )
  })

  it('serializes user paging and sorting while omitting empty filters', async () => {
    await listRecharges({ page: 2, size: 25, sort: 'createdAt', direction: 'asc' })
    await listWalletLedger({ page: 0, size: 20, sort: null, direction: undefined })

    const rechargeParams = http.get.mock.calls[0][1].params as URLSearchParams
    const ledgerParams = http.get.mock.calls[1][1].params as URLSearchParams
    expect(http.get.mock.calls[0][0]).toBe('/console/platform/wallet/recharges')
    expect(rechargeParams).toBeInstanceOf(URLSearchParams)
    expect(rechargeParams.toString()).toBe('page=2&size=25&sort=createdAt&direction=asc')
    expect(http.get.mock.calls[1][0]).toBe('/console/platform/wallet/ledger')
    expect(ledgerParams.toString()).toBe('page=0&size=20')
  })

  it('encodes order numbers and sends idempotency for balance payment', async () => {
    await payOrderWithBalance('order/001', 'wallet-payment-key-1')

    expect(http.post).toHaveBeenCalledWith(
      '/console/platform/orders/order%2F001/balance-payment',
      undefined,
      { headers: { 'Idempotency-Key': 'wallet-payment-key-1' } },
    )
  })

  it('serializes exact administrator account, ledger, and recharge filters', async () => {
    await listAdminWalletAccounts({
      page: 1, size: 25, query: 'alice@example.com', sort: 'availableAmount', direction: 'asc',
    })
    await listAdminWalletLedger({
      page: 2, size: 50, query: 'ledger/001', entryType: 'PACKAGE_REFUND',
      sort: 'amountDelta', direction: 'desc',
    })
    await listAdminWalletRecharges({
      page: 3, size: 10, query: '', status: 'PAID', sort: 'updatedAt', direction: 'desc',
    })

    expect((http.get.mock.calls[0][1].params as URLSearchParams).toString()).toBe(
      'page=1&size=25&query=alice%40example.com&sort=availableAmount&direction=asc',
    )
    expect((http.get.mock.calls[1][1].params as URLSearchParams).toString()).toBe(
      'page=2&size=50&query=ledger%2F001&entryType=PACKAGE_REFUND&sort=amountDelta&direction=desc',
    )
    expect((http.get.mock.calls[2][1].params as URLSearchParams).toString()).toBe(
      'page=3&size=10&status=PAID&sort=updatedAt&direction=desc',
    )
  })

  it('loads and atomically replaces administrator recharge options', async () => {
    const options = [
      { amount: 10000, enabled: true, displayOrder: 0 },
      { amount: 20000, enabled: false, displayOrder: 1 },
    ]

    await listAdminWalletRechargeOptions()
    await replaceAdminWalletRechargeOptions(options)

    expect(http.get).toHaveBeenCalledWith('/console/platform/admin/wallet/recharge-options')
    expect(http.put).toHaveBeenCalledWith(
      '/console/platform/admin/wallet/recharge-options',
      { options },
    )
  })
})

describe('wallet store identity isolation', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    http.get.mockResolvedValue({ data: summary })
  })

  it('retains same-user state but resets balance and history on identity change and logout', () => {
    const auth = useAuthStore()
    const wallet = useWalletStore()
    const user = {
      id: 'user-1', tenantId: 'tenant-1', email: 'one@example.com', role: 'USER' as const,
    }
    auth.setSession('token-1', user)
    wallet.$patch({
      summary,
      recharges: { items: [{ rechargeNo: 'recharge-1' } as never], total: 1, page: 0, size: 20 },
      ledger: { items: [{ entryNo: 'ledger-1' } as never], total: 1, page: 0, size: 20 },
    })

    auth.setSession('token-2', { ...user, email: 'updated@example.com' })
    expect(wallet.summary).toEqual(summary)
    expect(wallet.recharges.total).toBe(1)
    expect(wallet.ledger.total).toBe(1)

    auth.setSession('token-3', { ...user, id: 'user-2' })
    expect(wallet.summary).toBeNull()
    expect(wallet.recharges.items).toEqual([])
    expect(wallet.ledger.items).toEqual([])

    wallet.summary = summary
    auth.clearSession()
    expect(wallet.summary).toBeNull()
  })

  it('ignores a previous user summary response after identity changes', async () => {
    let resolveRequest!: (value: { data: WalletSummary }) => void
    http.get.mockReturnValueOnce(new Promise((resolve) => {
      resolveRequest = resolve
    }))
    const auth = useAuthStore()
    const wallet = useWalletStore()
    auth.setSession('token-1', {
      id: 'user-1', tenantId: 'tenant-1', email: 'one@example.com', role: 'USER',
    })

    const pending = wallet.loadSummary()
    auth.setSession('token-2', {
      id: 'user-2', tenantId: 'tenant-1', email: 'two@example.com', role: 'USER',
    })
    resolveRequest({ data: summary })
    await pending

    expect(wallet.summary).toBeNull()
    expect(wallet.loadingSummary).toBe(false)
  })
})
