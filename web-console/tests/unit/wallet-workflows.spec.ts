import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import RechargeDialog from '../../src/components/payment/RechargeDialog.vue'
import SmsVerificationDialog from '../../src/components/sms/SmsVerificationDialog.vue'
import WalletView from '../../src/views/user/WalletView.vue'
import rechargeDialogSource from '../../src/components/payment/RechargeDialog.vue?raw'
import walletViewSource from '../../src/views/user/WalletView.vue?raw'
import { useWalletStore } from '../../src/stores/wallet'

const api = vi.hoisted(() => ({
  getProfile: vi.fn(),
  closeRecharge: vi.fn(),
  createRecharge: vi.fn(),
  createRechargePaymentAttempt: vi.fn(),
  getRechargeDetail: vi.fn(),
  getRechargeOptions: vi.fn(),
  getWalletSummary: vi.fn(),
  listRecharges: vi.fn(),
  listWalletLedger: vi.fn(),
}))

vi.mock('../../src/api/wallet', () => api)
vi.mock('../../src/api/platform', async (importOriginal) => ({
  ...await importOriginal<typeof import('../../src/api/platform')>(),
  getProfile: api.getProfile,
}))

const qr = vi.hoisted(() => ({ toDataURL: vi.fn() }))
vi.mock('qrcode', () => ({ default: { toDataURL: qr.toDataURL } }))

const summary = {
  availableAmount: 12345,
  currency: 'CNY' as const,
  updatedAt: '2026-08-05T03:04:05Z',
}

const options = {
  options: [
    { id: 'option-100', amount: 10000, displayOrder: 0 },
    { id: 'option-500', amount: 50000, displayOrder: 1 },
  ],
  custom: { minimumAmount: 100, maximumAmount: 5_000_000 },
  currency: 'CNY' as const,
}

const pendingRecharge = {
  rechargeNo: 'R202608050001', optionId: 'option-100', paidAmount: 10000,
  bonusAmount: 0, creditedAmount: 10000, currency: 'CNY' as const,
  status: 'PENDING' as const, expiresAt: '2026-08-05T04:04:05Z', paidAt: null,
  closedAt: null, createdAt: '2026-08-05T03:04:05Z', updatedAt: '2026-08-05T03:04:05Z',
}

const rechargePage = {
  items: [pendingRecharge], total: 41, page: 0, size: 20,
}

const ledgerPage = {
  items: [{
    entryNo: 'L202608050001', entryType: 'RECHARGE' as const, amountDelta: 10000,
    balanceAfter: 12345, businessNo: 'R202608050001', description: '余额充值',
    createdAt: '2026-08-05T03:05:05Z',
  }],
  total: 61, page: 0, size: 20,
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

const mountedWrappers: Array<{ unmount: () => void }> = []

function mountWallet() {
  const wrapper = mount(WalletView, { global: { plugins: [ElementPlus] } })
  mountedWrappers.push(wrapper)
  return wrapper
}

function mountRecharge() {
  const wrapper = mount(RechargeDialog, {
    props: { modelValue: true },
    global: { plugins: [ElementPlus] },
  })
  mountedWrappers.push(wrapper)
  return wrapper
}

describe('user wallet workflows', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    api.getProfile.mockResolvedValue({ phoneVerified: false })
    api.getWalletSummary.mockResolvedValue(summary)
    api.getRechargeOptions.mockResolvedValue(options)
    api.listRecharges.mockResolvedValue(rechargePage)
    api.listWalletLedger.mockResolvedValue(ledgerPage)
    api.createRecharge.mockResolvedValue(pendingRecharge)
    api.closeRecharge.mockResolvedValue({
      ...pendingRecharge,
      status: 'CLOSED',
      closedAt: '2026-08-05T03:10:05Z',
    })
    api.createRechargePaymentAttempt.mockResolvedValue({
      transactionNo: 'T202608050001', status: 'PENDING',
      action: { type: 'QR_CODE', qrContent: 'weixin://wallet-pay', expiresAt: pendingRecharge.expiresAt },
      expiresAt: pendingRecharge.expiresAt,
    })
    api.getRechargeDetail.mockResolvedValue(pendingRecharge)
    qr.toDataURL.mockResolvedValue('data:image/png;base64,wallet')
  })

  afterEach(() => {
    for (const wrapper of mountedWrappers) {
      try { wrapper.unmount() } catch { /* 已处于卸载状态           */ }
    }
    mountedWrappers.length = 0
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('renders the authoritative integer-minor-unit balance and real history', async () => {
    const wrapper = mountWallet()
    await flushPromises()

    expect(api.getWalletSummary).toHaveBeenCalledTimes(1)
    expect(api.getRechargeOptions).toHaveBeenCalledTimes(1)
    expect(api.listRecharges).toHaveBeenCalledTimes(1)
    expect(api.listWalletLedger).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-test=wallet-balance]').text()).toContain('¥123.45')
    expect(wrapper.text()).toContain('R202608050001')
    expect(wrapper.text()).toContain('L202608050001')
    expect(wrapper.text()).toContain('2026-08-05 11:04:05')
    expect(api.getWalletSummary).toHaveBeenCalledTimes(1)
    expect(api.listRecharges).toHaveBeenCalledWith({ page: 0, size: 20, sort: 'createdAt', direction: 'desc' })
  })

  it('resumes a pending recharge without creating a second recharge order', async () => {
    const wrapper = mountWallet()
    await flushPromises()

    await wrapper.get('[data-test=resume-recharge]').trigger('click')
    await flushPromises()
    expect(wrapper.getComponent(RechargeDialog).props('existingOrder')).toEqual(pendingRecharge)

    await wrapper.get('[data-test=provider-wechat]').trigger('click')
    await wrapper.get('[data-test=resume-recharge-payment]').trigger('click')
    await flushPromises()

    expect(api.createRecharge).not.toHaveBeenCalled()
    expect(api.createRechargePaymentAttempt).toHaveBeenCalledWith('R202608050001', {
      provider: 'WECHAT_PAY', product: 'WECHAT_NATIVE',
    })
  })

  it('closes an owned pending recharge and refreshes the recharge list', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mountWallet()
    await flushPromises()

    await wrapper.get('[data-test=cancel-recharge]').trigger('click')
    await flushPromises()

    expect(api.closeRecharge).toHaveBeenCalledWith('R202608050001')
    expect(api.listRecharges).toHaveBeenCalledTimes(2)
  })

  it('creates one WeChat payment from a fixed option and guards a double submit', async () => {
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-option-option-100]').trigger('click')
    await wrapper.get('[data-test=provider-wechat]').trigger('click')

    const submit = wrapper.get('[data-test=submit-recharge]')
    await Promise.all([submit.trigger('click'), submit.trigger('click')])
    await flushPromises()

    expect(api.createRecharge).toHaveBeenCalledTimes(1)
    const [request, idempotencyKey] = api.createRecharge.mock.calls[0]
    expect(request).toEqual({ amount: 10000, optionId: 'option-100' })
    expect(idempotencyKey).toMatch(/^recharge-/)
    expect(api.createRechargePaymentAttempt).toHaveBeenCalledWith('R202608050001', {
      provider: 'WECHAT_PAY', product: 'WECHAT_NATIVE',
    })
    expect(wrapper.get('[data-test=payment-qr]').attributes('src')).toBe('data:image/png;base64,wallet')
  })

  it('validates custom yuan text and converts it deterministically to integer cents', async () => {
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-custom]').trigger('click')
    await wrapper.get('[data-test=provider-alipay]').trigger('click')
    const input = wrapper.get('[data-test=custom-amount]')

    for (const invalid of ['1e2', '0.99', '50000.01', '1.001']) {
      await input.setValue(invalid)
      await wrapper.get('[data-test=submit-recharge]').trigger('click')
      await flushPromises()
      expect(api.createRecharge).not.toHaveBeenCalled()
      expect(wrapper.get('[data-test=amount-error]').attributes('role')).toBe('alert')
    }

    await input.setValue('1.01')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()

    expect(api.createRecharge).toHaveBeenCalledWith({ amount: 101 }, expect.any(String))
    expect(api.createRechargePaymentAttempt).toHaveBeenCalledWith('R202608050001', {
      provider: 'ALIPAY', product: 'ALIPAY_PAGE',
    })
    expect(rechargeDialogSource).not.toMatch(/parseFloat|parseInt/)
  })

  it('accepts both exact custom boundaries', async () => {
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-custom]').trigger('click')
    const input = wrapper.get('[data-test=custom-amount]')

    await input.setValue('1.00')
    await wrapper.get('[data-test=provider-alipay]').trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()
    expect(api.createRecharge).toHaveBeenLastCalledWith({ amount: 100 }, expect.any(String))

    wrapper.unmount()
    api.createRecharge.mockClear()
    api.createRechargePaymentAttempt.mockClear()
    const maximumWrapper = mountRecharge()
    await flushPromises()
    await maximumWrapper.get('[data-test=recharge-custom]').trigger('click')
    await maximumWrapper.get('[data-test=custom-amount]').setValue('50000.00')
    await maximumWrapper.get('[data-test=provider-wechat]').trigger('click')
    await maximumWrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()
    expect(api.createRecharge).toHaveBeenCalledWith({ amount: 5_000_000 }, expect.any(String))
  })

  it('keeps the idempotency key stable when order creation must be retried', async () => {
    api.createRecharge.mockRejectedValueOnce(new Error('gateway timeout')).mockResolvedValueOnce(pendingRecharge)
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-option-option-100]').trigger('click')
    await wrapper.get('[data-test=provider-wechat]').trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()

    expect(api.createRecharge).toHaveBeenCalledTimes(2)
    expect(api.createRecharge.mock.calls[0][1]).toBe(api.createRecharge.mock.calls[1][1])
  })

  it('requires SMS verification before creating an order for a bound phone', async () => {
    api.getProfile.mockResolvedValue({ phoneVerified: true })
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-custom]').trigger('click')
    await wrapper.get('[data-test=custom-amount]').setValue('1')
    await wrapper.get('[data-test=provider-alipay]').trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()

    const verification = wrapper.getComponent(SmsVerificationDialog)
    expect(verification.props()).toMatchObject({ modelValue: true, purpose: 'PAYMENT_CONFIRM' })
    expect(api.createRecharge).not.toHaveBeenCalled()
    expect(api.createRechargePaymentAttempt).not.toHaveBeenCalled()
    // The real verification component closes and emits the proof in the same tick.
    verification.vm.$emit('update:modelValue', false)
    verification.vm.$emit('verified', 'valid-payment-proof')
    await flushPromises()
    expect(api.createRecharge).toHaveBeenCalledWith({ amount: 100 }, expect.any(String))
    expect(api.createRechargePaymentAttempt).toHaveBeenCalledWith(pendingRecharge.rechargeNo, {
      provider: 'ALIPAY', product: 'ALIPAY_PAGE', smsProof: 'valid-payment-proof',
    })
  })

  it.each(['cancel', 'close', 'account'] as const)('ignores verification after %s', async (action) => {
    api.getProfile.mockResolvedValue({ phoneVerified: true })
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-option-option-100]').trigger('click')
    await wrapper.get('[data-test=provider-alipay]').trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()
    const verification = wrapper.getComponent(SmsVerificationDialog)
    if (action === 'cancel') verification.vm.$emit('update:modelValue', false)
    if (action === 'close') await wrapper.setProps({ modelValue: false })
    if (action === 'account') useWalletStore().resetUserState()
    await flushPromises()
    verification.vm.$emit('verified', 'stale-proof')
    await flushPromises()
    expect(api.createRecharge).not.toHaveBeenCalled()
    expect(api.createRechargePaymentAttempt).not.toHaveBeenCalled()
  })

  it('fails safely when the account security requirement cannot be loaded', async () => {
    api.getProfile.mockRejectedValueOnce(new Error('unavailable'))
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-option-option-100]').trigger('click')
    await wrapper.get('[data-test=provider-alipay]').trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()
    expect(api.createRecharge).not.toHaveBeenCalled()
    expect(api.createRechargePaymentAttempt).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('账户安全状态加载失败')
  })

  it('reuses the created recharge and shows the payment error at the top on retry', async () => {
    api.createRechargePaymentAttempt.mockRejectedValueOnce({ response: { data: { code: 'invalid_sms_proof' } } })
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-option-option-100]').trigger('click')
    await wrapper.get('[data-test=provider-alipay]').trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('支付验证已失效')
    expect(wrapper.text()).not.toContain('支付验证已失效')
    expect(wrapper.text()).not.toContain('充值订单创建失败')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()
    expect(api.createRecharge).toHaveBeenCalledTimes(1)
    expect(api.createRechargePaymentAttempt).toHaveBeenCalledTimes(2)
    expect(api.createRechargePaymentAttempt.mock.calls[1][0]).toBe(pendingRecharge.rechargeNo)
  })

  it('uses desktop Alipay even in a narrow desktop window', async () => {
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: true }))
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-option-option-100]').trigger('click')
    await wrapper.get('[data-test=provider-alipay]').trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()
    expect(api.createRechargePaymentAttempt).toHaveBeenCalledWith(pendingRecharge.rechargeNo, {
      provider: 'ALIPAY', product: 'ALIPAY_PAGE',
    })
  })

  it('posts the returned desktop Alipay form into a new payment window', async () => {
    const fields = { app_id: 'test-app', method: 'alipay.trade.page.pay', sign: 'test-signature' }
    api.createRechargePaymentAttempt.mockResolvedValueOnce({
      status: 'PENDING', action: { type: 'HTML_FORM', targetUrl: 'https://openapi.alipay.com/gateway.do', fields },
    })
    const submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(function (this: HTMLFormElement) {
      expect(this.method).toBe('post')
      expect(this.target).toBe('_blank')
      expect(this.action).toBe('https://openapi.alipay.com/gateway.do')
      expect(Object.fromEntries(new FormData(this).entries())).toEqual(fields)
    })
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-option-option-100]').trigger('click')
    await wrapper.get('[data-test=provider-alipay]').trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=submit-payment-form]').trigger('click')
    expect(submit).toHaveBeenCalledTimes(1)
    expect(document.querySelector('form[action="https://openapi.alipay.com/gateway.do"]')).toBeNull()
  })

  it('does not display a failed provider attempt as a successful payment initiation', async () => {
    api.createRechargePaymentAttempt.mockResolvedValueOnce({ status: 'FAILED', action: { type: 'NONE' } })
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-option-option-100]').trigger('click')
    await wrapper.get('[data-test=provider-alipay]').trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test=refresh-payment-status]').exists()).toBe(false)
    expect(document.body.textContent).toContain('支付渠道未能创建支付请求')
  })

  it('refreshes the balance and histories after payment is confirmed', async () => {
    api.getRechargeDetail.mockResolvedValue({
      ...pendingRecharge, status: 'PAID', paidAt: '2026-08-05T03:06:05Z',
    })
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-option-option-100]').trigger('click')
    await wrapper.get('[data-test=provider-wechat]').trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=refresh-payment-status]').trigger('click')
    await flushPromises()

    expect(api.getRechargeDetail).toHaveBeenCalledWith('R202608050001')
    expect(api.getWalletSummary).toHaveBeenCalledTimes(1)
    expect(api.listRecharges).toHaveBeenCalledWith({ page: 0, size: 20, sort: 'createdAt', direction: 'desc' })
    expect(api.listWalletLedger).toHaveBeenCalledWith({ page: 0, size: 20, sort: 'createdAt', direction: 'desc' })
    expect(wrapper.emitted('completed')).toHaveLength(1)
  })

  it.each([
    ['summary', '余额'],
    ['recharges', '充值记录'],
    ['ledger', '余额明细'],
  ] as const)('keeps PAID authoritative when the %s refresh fails', async (failedResource, failedLabel) => {
    vi.useFakeTimers()
    api.getRechargeDetail.mockResolvedValueOnce({
      ...pendingRecharge, status: 'PAID', paidAt: '2026-08-05T03:06:05Z',
    })
    if (failedResource === 'summary') api.getWalletSummary.mockRejectedValueOnce(new Error('summary refresh failed'))
    if (failedResource === 'recharges') api.listRecharges.mockRejectedValueOnce(new Error('recharges refresh failed'))
    if (failedResource === 'ledger') api.listWalletLedger.mockRejectedValueOnce(new Error('ledger refresh failed'))
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-option-option-100]').trigger('click')
    await wrapper.get('[data-test=provider-wechat]').trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=refresh-payment-status]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('已到账')
    expect(wrapper.text()).not.toContain(`${failedLabel}刷新失败`)
    expect(document.body.textContent).toContain(`${failedLabel}刷新失败`)
    expect(wrapper.text()).not.toContain('支付状态查询失败')
    expect(wrapper.emitted('completed')).toHaveLength(1)
    expect(wrapper.emitted('completed')?.[0]?.[1]).toEqual([failedResource])
    await vi.advanceTimersByTimeAsync(2_100)
    expect(api.getRechargeDetail).toHaveBeenCalledTimes(1)
  })

  it('discards an order creation response after the dialog closes', async () => {
    const creation = deferred<typeof pendingRecharge>()
    api.createRecharge.mockReturnValueOnce(creation.promise)
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-option-option-100]').trigger('click')
    await wrapper.get('[data-test=provider-wechat]').trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await wrapper.setProps({ modelValue: false })

    creation.resolve(pendingRecharge)
    await flushPromises()

    expect(api.createRechargePaymentAttempt).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test=refresh-payment-status]').exists()).toBe(false)
  })

  it('discards an order creation response after the dialog unmounts', async () => {
    const creation = deferred<typeof pendingRecharge>()
    api.createRecharge.mockReturnValueOnce(creation.promise)
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-option-option-100]').trigger('click')
    await wrapper.get('[data-test=provider-wechat]').trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    wrapper.unmount()

    creation.resolve(pendingRecharge)
    await flushPromises()

    expect(api.createRechargePaymentAttempt).not.toHaveBeenCalled()
  })

  it('discards a payment-attempt response after the dialog closes', async () => {
    const attempt = deferred<{
      transactionNo: string
      status: 'PENDING'
      action: { type: 'NONE'; expiresAt: string }
      expiresAt: string
    }>()
    api.createRechargePaymentAttempt.mockReturnValueOnce(attempt.promise)
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-option-option-100]').trigger('click')
    await wrapper.get('[data-test=provider-wechat]').trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()
    expect(api.createRechargePaymentAttempt).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ modelValue: false })

    attempt.resolve({
      transactionNo: 'T202608050002', status: 'PENDING',
      action: { type: 'NONE', expiresAt: pendingRecharge.expiresAt },
      expiresAt: pendingRecharge.expiresAt,
    })
    await flushPromises()

    expect(wrapper.find('[data-test=refresh-payment-status]').exists()).toBe(false)
    expect(api.getRechargeDetail).not.toHaveBeenCalled()
  })

  it('discards a paid status response after the dialog closes without refreshing stores or emitting', async () => {
    vi.useFakeTimers()
    const status = deferred<typeof pendingRecharge>()
    api.getRechargeDetail.mockReturnValueOnce(status.promise)
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-option-option-100]').trigger('click')
    await wrapper.get('[data-test=provider-wechat]').trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=refresh-payment-status]').trigger('click')
    await wrapper.setProps({ modelValue: false })

    status.resolve({ ...pendingRecharge, status: 'PAID', paidAt: '2026-08-05T03:06:05Z' })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(2_100)

    expect(api.getWalletSummary).not.toHaveBeenCalled()
    expect(api.listRecharges).not.toHaveBeenCalled()
    expect(api.listWalletLedger).not.toHaveBeenCalled()
    expect(wrapper.emitted('completed')).toBeUndefined()
    expect(api.getRechargeDetail).toHaveBeenCalledTimes(1)
  })

  it('discards a status response when the authenticated wallet scope changes', async () => {
    vi.useFakeTimers()
    const status = deferred<typeof pendingRecharge>()
    api.getRechargeDetail.mockReturnValueOnce(status.promise)
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-option-option-100]').trigger('click')
    await wrapper.get('[data-test=provider-wechat]').trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=refresh-payment-status]').trigger('click')
    useWalletStore().userScopeGeneration += 1

    status.resolve({ ...pendingRecharge, status: 'PAID', paidAt: '2026-08-05T03:06:05Z' })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(2_100)

    expect(api.getWalletSummary).not.toHaveBeenCalled()
    expect(api.listRecharges).not.toHaveBeenCalled()
    expect(api.listWalletLedger).not.toHaveBeenCalled()
    expect(wrapper.emitted('completed')).toBeUndefined()
    expect(api.getRechargeDetail).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['FAILED', '支付失败'],
    ['CLOSED', '已关闭'],
  ] as const)('renders terminal recharge status %s and offers a new recharge without polling', async (terminalStatus, label) => {
    vi.useFakeTimers()
    api.getRechargeDetail.mockResolvedValueOnce({ ...pendingRecharge, status: terminalStatus })
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-option-option-100]').trigger('click')
    await wrapper.get('[data-test=provider-wechat]').trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=refresh-payment-status]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain(label)
    expect(wrapper.text()).not.toContain('等待支付')
    expect(wrapper.get('[data-test=start-new-recharge]').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(2_100)
    expect(api.getRechargeDetail).toHaveBeenCalledTimes(1)
    await wrapper.get('[data-test=start-new-recharge]').trigger('click')
    expect(wrapper.get('[data-test=submit-recharge]').exists()).toBe(true)
  })

  it('keeps recharge and ledger pagination independent across tabs', async () => {
    const wrapper = mountWallet()
    await flushPromises()
    wrapper.getComponent('[data-test=recharge-pagination]').vm.$emit('current-change', 2)
    await flushPromises()
    expect(api.listRecharges).toHaveBeenLastCalledWith({ page: 1, size: 20, sort: 'createdAt', direction: 'desc' })

    await wrapper.get('[data-test=ledger-tab]').trigger('click')
    wrapper.getComponent('[data-test=ledger-pagination]').vm.$emit('current-change', 3)
    await flushPromises()
    expect(api.listWalletLedger).toHaveBeenLastCalledWith({ page: 2, size: 20, sort: 'createdAt', direction: 'desc' })
    expect(api.listRecharges).toHaveBeenCalledTimes(2)
  })

  it('ignores an older recharge page failure after a newer page succeeds', async () => {
    const older = deferred<typeof rechargePage>()
    const newer = deferred<typeof rechargePage>()
    const wrapper = mountWallet()
    await flushPromises()
    api.listRecharges.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise)
    const pagination = wrapper.getComponent('[data-test=recharge-pagination]')
    pagination.vm.$emit('current-change', 2)
    pagination.vm.$emit('current-change', 3)

    newer.resolve({ ...rechargePage, page: 2 })
    await flushPromises()
    expect(wrapper.getComponent('[data-test=recharge-pagination]').props('currentPage')).toBe(3)
    older.reject(new Error('stale page failure'))
    await flushPromises()
    expect(wrapper.getComponent('[data-test=recharge-pagination]').props('currentPage')).toBe(3)
    expect(wrapper.find('[data-test=retry-recharges]').exists()).toBe(false)
  })

  it('ignores an older ledger page response after a newer page succeeds', async () => {
    const older = deferred<typeof ledgerPage>()
    const newer = deferred<typeof ledgerPage>()
    const wrapper = mountWallet()
    await flushPromises()
    await wrapper.get('[data-test=ledger-tab]').trigger('click')
    api.listWalletLedger.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise)
    const pagination = wrapper.getComponent('[data-test=ledger-pagination]')
    pagination.vm.$emit('current-change', 2)
    pagination.vm.$emit('current-change', 3)

    newer.resolve({ ...ledgerPage, page: 2 })
    await flushPromises()
    expect(wrapper.getComponent('[data-test=ledger-pagination]').props('currentPage')).toBe(3)
    older.resolve({ ...ledgerPage, page: 1 })
    await flushPromises()
    expect(wrapper.getComponent('[data-test=ledger-pagination]').props('currentPage')).toBe(3)
    expect(wrapper.find('[data-test=retry-ledger]').exists()).toBe(false)
  })

  it('does not advance a page cursor when the wallet scope changes during loading', async () => {
    const pendingPage = deferred<typeof rechargePage>()
    const wrapper = mountWallet()
    await flushPromises()
    api.listRecharges.mockReturnValueOnce(pendingPage.promise)
    wrapper.getComponent('[data-test=recharge-pagination]').vm.$emit('current-change', 2)
    useWalletStore().userScopeGeneration += 1
    pendingPage.resolve({ ...rechargePage, page: 1 })
    await flushPromises()

    expect(wrapper.getComponent('[data-test=recharge-pagination]').props('currentPage')).toBe(1)
    expect(wrapper.find('[data-test=retry-recharges]').exists()).toBe(false)
  })

  it('resets page cursors and stale data errors when the recharge dialog completes', async () => {
    api.getWalletSummary.mockRejectedValueOnce(new Error('stale summary error'))
    api.getRechargeOptions.mockRejectedValueOnce(new Error('keep options error'))
    const wrapper = mountWallet()
    await flushPromises()

    wrapper.getComponent('[data-test=recharge-pagination]').vm.$emit('current-change', 2)
    await flushPromises()
    await wrapper.get('[data-test=ledger-tab]').trigger('click')
    wrapper.getComponent('[data-test=ledger-pagination]').vm.$emit('current-change', 3)
    await flushPromises()
    expect(wrapper.getComponent('[data-test=recharge-pagination]').props('currentPage')).toBe(2)
    expect(wrapper.getComponent('[data-test=ledger-pagination]').props('currentPage')).toBe(3)

    api.listRecharges.mockRejectedValueOnce(new Error('stale recharge error'))
    api.listWalletLedger.mockRejectedValueOnce(new Error('stale ledger error'))
    wrapper.getComponent('[data-test=recharge-pagination]').vm.$emit('current-change', 3)
    wrapper.getComponent('[data-test=ledger-pagination]').vm.$emit('current-change', 4)
    await flushPromises()
    expect(wrapper.find('[data-test=retry-wallet]').exists()).toBe(true)
    expect(wrapper.find('[data-test=retry-recharges]').exists()).toBe(true)
    expect(wrapper.find('[data-test=retry-ledger]').exists()).toBe(true)

    useWalletStore().$patch({ summary, recharges: rechargePage, ledger: ledgerPage })
    wrapper.getComponent(RechargeDialog).vm.$emit('completed', {
      ...pendingRecharge, status: 'PAID', paidAt: '2026-08-05T03:06:05Z',
    })
    await flushPromises()

    expect(wrapper.getComponent('[data-test=recharge-pagination]').props('currentPage')).toBe(1)
    expect(wrapper.getComponent('[data-test=ledger-pagination]').props('currentPage')).toBe(1)
    expect(wrapper.find('[data-test=retry-wallet]').exists()).toBe(false)
    expect(wrapper.find('[data-test=retry-recharges]').exists()).toBe(false)
    expect(wrapper.find('[data-test=retry-ledger]').exists()).toBe(false)
    expect(wrapper.find('[data-test=retry-options]').exists()).toBe(true)
  })

  it('shows stable loading, empty, and recoverable error states', async () => {
    const pending = deferred<typeof summary>()
    api.getWalletSummary.mockReturnValueOnce(pending.promise)
    api.listRecharges.mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
    api.listWalletLedger.mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
    const loadingWrapper = mountWallet()
    await flushPromises()
    expect(loadingWrapper.get('[data-test=wallet-loading]').attributes('aria-busy')).toBe('true')
    pending.resolve(summary)
    await flushPromises()
    expect(loadingWrapper.text()).toContain('暂无充值记录')
    expect(loadingWrapper.text()).toContain('暂无余额明细')

    loadingWrapper.unmount()
    api.getWalletSummary.mockRejectedValueOnce(new Error('network unavailable'))
    const errorWrapper = mountWallet()
    await flushPromises()
    expect(errorWrapper.get('[data-test=wallet-error]').find('[role=alert]').exists()).toBe(false)
    expect(document.body.textContent).toContain('可用余额加载失败')
    await errorWrapper.get('[data-test=retry-wallet]').trigger('click')
    await flushPromises()
    expect(api.getWalletSummary).toHaveBeenCalledTimes(3)
  })

  it('shows a recharge-only error and targeted retry instead of a false empty state', async () => {
    api.listRecharges.mockRejectedValueOnce(new Error('recharge unavailable'))
    const wrapper = mountWallet()
    await flushPromises()

    const panel = wrapper.get('[data-test=recharges-panel]')
    expect(panel.find('[role=alert]').exists()).toBe(false)
    expect(panel.find('[data-test=retry-recharges]').exists()).toBe(true)
    expect(panel.text()).not.toContain('暂无充值记录')
    expect(wrapper.text()).toContain('L202608050001')

    await panel.get('[data-test=retry-recharges]').trigger('click')
    await flushPromises()
    expect(api.listRecharges).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('R202608050001')
  })

  it('shows a ledger-only error and targeted retry instead of a false empty state', async () => {
    api.listWalletLedger.mockRejectedValueOnce(new Error('ledger unavailable'))
    const wrapper = mountWallet()
    await flushPromises()
    await wrapper.get('[data-test=ledger-tab]').trigger('click')

    const panel = wrapper.get('[data-test=ledger-panel]')
    expect(panel.find('[role=alert]').exists()).toBe(false)
    expect(panel.find('[data-test=retry-ledger]').exists()).toBe(true)
    expect(panel.text()).not.toContain('暂无余额明细')
    expect(wrapper.text()).toContain('R202608050001')

    await panel.get('[data-test=retry-ledger]').trigger('click')
    await flushPromises()
    expect(api.listWalletLedger).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('L202608050001')
  })

  it('keeps summary and option errors independent with targeted recovery', async () => {
    api.getWalletSummary.mockRejectedValueOnce(new Error('summary unavailable'))
    api.getRechargeOptions.mockRejectedValueOnce(new Error('options unavailable'))
    const wrapper = mountWallet()
    await flushPromises()

    expect(wrapper.get('[data-test=wallet-error]').find('[role=alert]').exists()).toBe(false)
    expect(wrapper.find('[data-test=options-error]').exists()).toBe(false)
    expect(wrapper.find('[data-test=retry-options]').exists()).toBe(true)
    expect(wrapper.text()).toContain('R202608050001')
    expect(wrapper.text()).toContain('L202608050001')
    await wrapper.get('[data-test=retry-wallet]').trigger('click')
    await wrapper.get('[data-test=retry-options]').trigger('click')
    await flushPromises()
    expect(api.getWalletSummary).toHaveBeenCalledTimes(2)
    expect(api.getRechargeOptions).toHaveBeenCalledTimes(2)
  })

  it('renders a summary failure only at the top with one semantic retry button', async () => {
    api.getWalletSummary.mockRejectedValueOnce(new Error('summary unavailable'))
    const wrapper = mountWallet()
    await flushPromises()

    const error = wrapper.get('[data-test=wallet-error]')
    expect(error.findAll('[role=alert]')).toHaveLength(0)
    expect(document.body.textContent).toContain('可用余额加载失败')
    expect(error.findAll('button')).toHaveLength(1)
    expect(error.get('[data-test=retry-wallet]').element.tagName).toBe('BUTTON')
  })

  it.each([
    ['provider-wechat', 'WECHAT_PAY', 'WECHAT_H5'],
    ['provider-alipay', 'ALIPAY', 'ALIPAY_WAP'],
  ])('selects the mobile provider product for %s', async (selector, expectedProvider, expectedProduct) => {
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Mobile')
    const wrapper = mountRecharge()
    await flushPromises()
    await wrapper.get('[data-test=recharge-option-option-100]').trigger('click')
    await wrapper.get(`[data-test=${selector}]`).trigger('click')
    await wrapper.get('[data-test=submit-recharge]').trigger('click')
    await flushPromises()

    expect(api.createRechargePaymentAttempt).toHaveBeenCalledWith('R202608050001', {
      provider: expectedProvider, product: expectedProduct,
    })
  })

  it('provides keyboard-connected tabs and constrained mobile table scrolling', async () => {
    const wrapper = mountWallet()
    await flushPromises()
    const rechargeTab = wrapper.get('[data-test=recharge-tab]')
    expect(rechargeTab.attributes('aria-controls')).toBe('wallet-recharges-panel')
    expect(wrapper.get('[data-test=recharges-panel]').attributes('aria-labelledby')).toBe('wallet-recharges-tab')
    await rechargeTab.trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.get('[data-test=ledger-tab]').attributes('aria-selected')).toBe('true')
    expect(walletViewSource).toMatch(/\.table-scroll\s*\{[^}]*max-width:\s*100%[^}]*overflow-x:\s*auto/s)
    expect(walletViewSource).toMatch(/\.tab-list button\s*\{[^}]*min-height:\s*44px/s)
    expect(walletViewSource).not.toMatch(/#eff6ff|#f1f5f9/i)
    expect(rechargeDialogSource).not.toMatch(/#eff6ff|#f1f5f9/i)
  })

  it('does not expose withdrawal or recharge-refund controls', () => {
    expect(walletViewSource).not.toMatch(/data-test=["'][^"']*(withdraw|refund)|<[^>]*>\s*(提现|充值退款)\s*</i)
    expect(rechargeDialogSource).not.toMatch(/data-test=["'][^"']*(withdraw|refund)|<[^>]*>\s*(提现|充值退款)\s*</i)
  })

  it('never divides minor units through binary floating-point arithmetic', () => {
    expect(walletViewSource).not.toMatch(/minorUnits\s*\/\s*100/)
    expect(rechargeDialogSource).not.toMatch(/minorUnits\s*\/\s*100/)
  })
})
