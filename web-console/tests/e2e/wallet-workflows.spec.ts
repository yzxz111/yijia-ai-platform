import { expect, test, type APIRequestContext } from '@playwright/test'

import {
  STARTER_PACKAGE_VERSION_ID,
  approveRefund,
  bearer,
  createRecharge,
  createRechargeMockPaymentAttempt,
  createPaidOrder,
  expectRefundApprovalReplayRejected,
  getWallet,
  getWalletLedger,
  payOrderFromBalance,
  pollGatewayPackageState,
  pollOutbox,
  pollRefund,
  postMockCallback,
  provisionLocalStackUser,
  pollWallet,
  readWalletWorkflowFacts,
  requestRefund,
} from './support/stack'

test.beforeAll(() => {
  expect(process.env.WALLET_E2E_ISOLATED).toBe('true')
  expect(process.env.E2E_COMPOSE_PROJECT_NAME).toMatch(/^yzh-wallet-e2e-/u)
  expect(process.env.E2E_BASE_URL).toBeDefined()
  const baseUrl = new URL(process.env.E2E_BASE_URL!)
  expect(baseUrl.hostname).toBe('127.0.0.1')
  expect(baseUrl.port).not.toBe('18081')
})

test('accepts the complete isolated and idempotent local MOCK wallet lifecycle', async ({
  request,
}) => {
  test.setTimeout(180_000)
  await acceptWalletLifecycle(request)
})

async function acceptWalletLifecycle(request: APIRequestContext) {
    const owner = await provisionLocalStackUser(request)
    const otherTenant = await provisionLocalStackUser(request)

    const initialWallet = await getWallet(request, owner.userSession.accessToken)
    expect(initialWallet).toMatchObject({ availableAmount: 0, currency: 'CNY' })

    const rechargeAmount = 50_000
    const recharge = await createRecharge(
      request,
      owner.userSession.accessToken,
      rechargeAmount,
      `wallet-recharge-${owner.runId}`,
    )
    const rechargeAttempt = await createRechargeMockPaymentAttempt(
      request,
      owner.userSession.accessToken,
      recharge.rechargeNo,
    )
    const callback = {
      externalTradeNo: `mock-${rechargeAttempt.transactionNo}`,
      orderNo: recharge.rechargeNo,
      amount: rechargeAmount,
    }
    const posted = await postMockCallback(request, callback)
    const replayedCallback = await postMockCallback(request, callback)
    expect(replayedCallback).toEqual(posted)

    const creditedWallet = await pollWallet(
      request,
      owner.userSession.accessToken,
      ({ availableAmount }) => availableAmount === rechargeAmount,
      'recharge wallet credit',
    )
    expect(creditedWallet.availableAmount).toBe(rechargeAmount)

    const order = await createPaidOrder(
      request,
      owner.userSession.accessToken,
      STARTER_PACKAGE_VERSION_ID,
      `wallet-order-${owner.runId}`,
    )
    const paymentKey = `wallet-payment-${owner.runId}`
    const payment = await payOrderFromBalance(
      request,
      owner.userSession.accessToken,
      order.orderNo,
      paymentKey,
    )
    const replayedPayment = await payOrderFromBalance(
      request,
      owner.userSession.accessToken,
      order.orderNo,
      paymentKey,
    )
    expect(replayedPayment).toEqual(payment)
    expect(payment).toMatchObject({
      orderNo: order.orderNo,
      orderStatus: 'PAID',
      settlementMethod: 'BALANCE',
      balanceBefore: rechargeAmount,
      balanceAfter: rechargeAmount - order.amount,
    })

    await pollOutbox(
      request,
      owner.adminSession.accessToken,
      `order:${order.orderNo}`,
      'DELIVERED',
    )
    await pollGatewayPackageState(
      request,
      owner.userSession.accessToken,
      ({ activePackage }) => activePackage?.packageCode === 'STARTER',
      'wallet package grant',
    )

    const refund = await requestRefund(
      request,
      owner.userSession.accessToken,
      order.orderNo,
      `wallet refund ${owner.runId}`,
    )
    const approved = await approveRefund(
      request,
      owner.adminSession.accessToken,
      refund.refundNo,
    )
    expect(['REVOKING', 'SUBMITTING']).toContain(approved.status)
    await pollOutbox(
      request,
      owner.adminSession.accessToken,
      `refund-revoke:${refund.refundNo}`,
      'DELIVERED',
    )
    await pollRefund(
      request,
      owner.userSession.accessToken,
      refund.refundNo,
      'COMPLETED',
    )
    await pollGatewayPackageState(
      request,
      owner.userSession.accessToken,
      ({ activePackage }) => activePackage == null,
      'wallet refund entitlement revocation',
    )

    const finalWallet = await pollWallet(
      request,
      owner.userSession.accessToken,
      ({ availableAmount }) => availableAmount === rechargeAmount,
      'wallet refund credit',
    )
    expect(finalWallet.availableAmount).toBe(rechargeAmount)
    await expectRefundApprovalReplayRejected(
      request,
      owner.adminSession.accessToken,
      refund.refundNo,
    )
    expect((await getWallet(request, owner.userSession.accessToken)).availableAmount)
      .toBe(rechargeAmount)

    const ledger = await getWalletLedger(request, owner.userSession.accessToken)
    expect(ledger.items).toEqual(expect.arrayContaining([
      expect.objectContaining({
        entryType: 'RECHARGE',
        amountDelta: rechargeAmount,
        businessNo: recharge.rechargeNo,
      }),
      expect.objectContaining({
        entryType: 'PACKAGE_PURCHASE',
        amountDelta: -order.amount,
        businessNo: order.orderNo,
      }),
      expect.objectContaining({
        entryType: 'PACKAGE_REFUND',
        amountDelta: order.amount,
        businessNo: refund.refundNo,
      }),
    ]))
    expect(ledger.items.filter(({ entryType }) => entryType === 'RECHARGE')).toHaveLength(1)
    expect(ledger.items.filter(({ entryType }) => entryType === 'PACKAGE_PURCHASE')).toHaveLength(1)
    expect(ledger.items.filter(({ entryType }) => entryType === 'PACKAGE_REFUND')).toHaveLength(1)

    expect(readWalletWorkflowFacts(
      owner.userSession.user.id,
      recharge.rechargeNo,
      order.orderNo,
      refund.refundNo,
    )).toEqual({
      availableAmount: rechargeAmount,
      rechargeStatus: 'PAID',
      orderStatus: 'REFUNDED',
      settlementMethod: 'BALANCE',
      refundStatus: 'COMPLETED',
      fundingSource: 'BALANCE',
      packageStatus: 'REVOKED',
      rechargeCredits: 1,
      purchaseDebits: 1,
      refundCredits: 1,
    })

    await expectOtherTenantIsolation(request, otherTenant.userSession.accessToken, {
      rechargeNo: recharge.rechargeNo,
      orderNo: order.orderNo,
      refundNo: refund.refundNo,
    })
    expect((await getWallet(request, owner.userSession.accessToken)).availableAmount)
      .toBe(rechargeAmount)
}

async function expectOtherTenantIsolation(
  request: APIRequestContext,
  accessToken: string,
  identifiers: { rechargeNo: string; orderNo: string; refundNo: string },
) {
  for (const path of [
    `/console/platform/wallet/recharges/${encodeURIComponent(identifiers.rechargeNo)}`,
    `/console/platform/orders/${encodeURIComponent(identifiers.orderNo)}`,
    `/console/platform/refunds/${encodeURIComponent(identifiers.refundNo)}`,
  ]) {
    const response = await request.get(path, { headers: bearer(accessToken) })
    expect(response.status(), `cross-tenant query ${path}`).toBe(404)
  }
  for (const path of [
    '/console/platform/wallet/recharges?size=100',
    '/console/platform/orders?size=100',
  ]) {
    const response = await request.get(path, { headers: bearer(accessToken) })
    expect(response.status(), `cross-tenant list ${path}`).toBe(200)
    const page = await response.json() as { items: unknown[] }
    expect(page.items, `cross-tenant list ${path}`).toHaveLength(0)
  }
  expect((await getWalletLedger(request, accessToken)).items).toHaveLength(0)
}
