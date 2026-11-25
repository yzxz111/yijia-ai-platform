import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { expect, test, type Page } from '@playwright/test'

import {
  ADMIN_EMAIL,
  ADMIN_PASSWORD,
  ADVANCED_PACKAGE_VERSION_ID,
  E2E_USER_PASSWORD,
  STARTER_PACKAGE_VERSION_ID,
  approveRefund,
  bearer,
  claimFreePackage,
  cleanupStackUsers,
  closeOrderForQueryRace,
  createMockPaymentAttempt,
  createPaidOrder,
  expectStatus,
  pollGatewayPackageState,
  pollOrder,
  pollOutbox,
  pollRefund,
  postMockCallback,
  readPaymentWorkflowFacts,
  registerStackUser,
  requestRefund,
  seedAmountMismatchAnomaly,
  seedReconciliationDifference,
  simulateMockPayment,
  type RegisteredStackUser,
} from './support/stack'

const ARTIFACT_ROOT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../../test-results/phase2-payments',
)

const VIEWPORTS = [
  { name: 'desktop-1440x900', width: 1440, height: 900 },
  { name: 'mobile-390x844', width: 390, height: 844 },
  { name: 'mobile-360x800', width: 360, height: 800 },
] as const

interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

interface PaymentAnomaly {
  id: string
  anomalyType: string
  orderId: string
  status: string
  detail: Record<string, unknown>
}

interface ReconciliationDetail {
  id: string
  provider: string
  billDate: string
  status: string
  billSha256: string | null
  summary: { differenceCount: number; openCount: number }
  items: PageResult<{
    transactionNo: string | null
    differenceType: string
    providerAmount: number | null
    platformAmount: number | null
    detail: Record<string, unknown>
  }>
}

interface BrowserErrors {
  console: string[]
  page: string[]
}

test('accepts the complete local MOCK purchase, replay, race, refund, anomaly, and reconciliation path', async ({
  page,
  request,
}) => {
  test.setTimeout(600_000)
  const users: RegisteredStackUser[] = []
  const reconciliationRunIds: string[] = []
  const browserErrors = observeBrowserErrors(page)

  try {
    const freeUser = await registerStackUser(request)
    users.push(freeUser)
    const firstClaim = await claimFreePackage(request, freeUser.userSession.accessToken)
    const replayedClaim = await claimFreePackage(request, freeUser.userSession.accessToken)
    expect(replayedClaim.id).toBe(firstClaim.id)
    expect(replayedClaim.businessNo).toBe(`free:${freeUser.userSession.user.id}`)
    await pollOutbox(
      request,
      freeUser.adminSession.accessToken,
      replayedClaim.businessNo,
      'DELIVERED',
    )
    const freeFacts = await readPaymentWorkflowFacts(
      freeUser.userSession.user.id,
      [],
    )
    expect(freeFacts.freeGrantCount).toBe(1)
    expect(freeFacts.freeOutboxCount).toBe(1)
    expect(freeFacts.freePackageCount).toBe(1)

    const paidUser = await registerStackUser(request)
    users.push(paidUser)
    const firstOrder = await createPaidOrder(
      request,
      paidUser.userSession.accessToken,
      STARTER_PACKAGE_VERSION_ID,
      `paid-first-${paidUser.runId}`,
    )
    const firstAttempt = await createMockPaymentAttempt(
      request,
      paidUser.userSession.accessToken,
      firstOrder.orderNo,
    )
    expect(firstAttempt.status).toBe('PENDING')
    expect(firstAttempt.action.type).toBe('NONE')

    const firstSimulation = await simulateMockPayment(
      request,
      paidUser.adminSession.accessToken,
      firstOrder.orderNo,
    )
    const replayedSimulation = await simulateMockPayment(
      request,
      paidUser.adminSession.accessToken,
      firstOrder.orderNo,
    )
    expect(replayedSimulation).toEqual(firstSimulation)

    const paidDetail = await pollOrder(
      request,
      paidUser.userSession.accessToken,
      firstOrder.orderNo,
      ({ order, grant }) => order.status === 'PAID' && grant?.status === 'ACTIVE',
      'first paid order and package activation',
    )
    expect(paidDetail.paymentAttempts).toHaveLength(1)
    await pollOutbox(
      request,
      paidUser.adminSession.accessToken,
      `order:${firstOrder.orderNo}`,
      'DELIVERED',
    )
    const initialGateway = await pollGatewayPackageState(
      request,
      paidUser.userSession.accessToken,
      ({ activePackage }) => activePackage?.packageCode === 'STARTER',
      'first paid package activation',
    )
    const firstPackageId = initialGateway.activePackage!.packageInstanceId

    const secondOrder = await createPaidOrder(
      request,
      paidUser.userSession.accessToken,
      ADVANCED_PACKAGE_VERSION_ID,
      `paid-second-${paidUser.runId}`,
    )
    const secondAttempt = await createMockPaymentAttempt(
      request,
      paidUser.userSession.accessToken,
      secondOrder.orderNo,
    )
    const mismatch = seedAmountMismatchAnomaly(
      paidUser.userSession.user.id,
      secondOrder.orderNo,
      secondAttempt.transactionNo,
      secondOrder.amount,
    )

    const callback = {
      externalTradeNo: `mock-${secondAttempt.transactionNo}`,
      orderNo: secondOrder.orderNo,
      amount: secondOrder.amount,
    }
    const [closeResult, callbackResult, replayedCallbackResult] = await Promise.all([
      closeOrderForQueryRace(
        request,
        paidUser.userSession.accessToken,
        secondOrder.orderNo,
      ),
      postMockCallback(request, callback),
      postMockCallback(request, callback),
    ])
    expect(closeResult.orderNo).toBe(secondOrder.orderNo)
    expect(replayedCallbackResult).toEqual(callbackResult)
    expect([
      { orderStatus: 'PENDING', paymentStatus: 'PENDING', retryScheduled: true },
      { orderStatus: 'PAID', paymentStatus: 'SUCCEEDED', retryScheduled: false },
    ]).toContainEqual({
      orderStatus: closeResult.orderStatus,
      paymentStatus: closeResult.paymentStatus,
      retryScheduled: closeResult.retryScheduled,
    })

    const queuedDetail = await pollOrder(
      request,
      paidUser.userSession.accessToken,
      secondOrder.orderNo,
      ({ order, grant }) => order.status === 'PAID' && grant?.status === 'QUEUED',
      'second paid purchase queueing after callback/query race',
    )
    expect(queuedDetail.paymentAttempts).toHaveLength(1)
    const queuedGateway = await pollGatewayPackageState(
      request,
      paidUser.userSession.accessToken,
      ({ queuedPackages }) => queuedPackages.some(({ packageCode }) => packageCode === 'ADVANCED'),
      'second paid package queueing',
    )
    const queuedOutbox = await pollOutbox(
      request,
      paidUser.adminSession.accessToken,
      `order:${secondOrder.orderNo}`,
      'PENDING',
    )
    expect(queuedOutbox.attemptCount).toBe(0)
    expect(Date.parse(queuedOutbox.nextAttemptAt)).toBeGreaterThan(Date.now() + 4 * 60_000)
    const secondPackageId = queuedGateway.queuedPackages.find(
      ({ packageCode }) => packageCode === 'ADVANCED',
    )!.packageInstanceId

    const factsAfterPayments = await readPaymentWorkflowFacts(
      paidUser.userSession.user.id,
      [firstOrder.orderNo, secondOrder.orderNo],
    )
    expect(factsAfterPayments.orders[firstOrder.orderNo]).toMatchObject({
      attemptCount: 1,
      grantCount: 1,
      paymentPostAuditCount: 1,
    })
    expect(factsAfterPayments.orders[secondOrder.orderNo]).toMatchObject({
      attemptCount: 1,
      grantCount: 1,
      paymentPostAuditCount: 1,
    })

    const anomalyResponse = await request.get(
      `/console/platform/admin/payment-anomalies?anomalyType=PAYMENT_AMOUNT_MISMATCH&orderId=${encodeURIComponent(mismatch.orderId)}`,
      { headers: bearer(paidUser.adminSession.accessToken) },
    )
    await expectStatus(anomalyResponse, 200, 'list seeded payment amount mismatch')
    const anomalyPage = await anomalyResponse.json() as PageResult<PaymentAnomaly>
    expect(anomalyPage.items).toContainEqual(expect.objectContaining({
      id: mismatch.anomalyId,
      anomalyType: 'PAYMENT_AMOUNT_MISMATCH',
      orderId: mismatch.orderId,
      status: 'OPEN',
      detail: expect.objectContaining({
        expectedAmount: secondOrder.amount,
        observedAmount: secondOrder.amount + 1,
      }),
    }))

    const reconciliation = seedReconciliationDifference(
      paidUser.userSession.user.id,
      secondAttempt.transactionNo,
      secondOrder.amount,
      paidUser.runId,
    )
    reconciliationRunIds.push(reconciliation.runId)
    const reconciliationResponse = await request.get(
      `/console/platform/admin/reconciliation-runs/${encodeURIComponent(reconciliation.runId)}`,
      { headers: bearer(paidUser.adminSession.accessToken) },
    )
    await expectStatus(reconciliationResponse, 200, 'read seeded reconciliation difference')
    const reconciliationDetail = await reconciliationResponse.json() as ReconciliationDetail
    expect(reconciliationDetail).toMatchObject({
      id: reconciliation.runId,
      provider: 'MOCK',
      billDate: reconciliation.billDate,
      status: 'COMPLETED',
      summary: { differenceCount: 1, openCount: 1 },
    })
    expect(reconciliationDetail.items.items).toContainEqual(expect.objectContaining({
      transactionNo: secondAttempt.transactionNo,
      differenceType: 'AMOUNT_MISMATCH',
      providerAmount: secondOrder.amount + 1,
      platformAmount: secondOrder.amount,
    }))
    expect(JSON.stringify(reconciliationDetail)).not.toMatch(/rawBill|billContent|rawContent/iu)

    const requestedRefund = await requestRefund(
      request,
      paidUser.userSession.accessToken,
      firstOrder.orderNo,
      `zero usage refund ${paidUser.runId}`,
    )
    expect(requestedRefund).toMatchObject({
      orderNo: firstOrder.orderNo,
      status: 'PENDING_APPROVAL',
      refundType: 'FULL',
      amount: firstOrder.amount,
      calculation: {
        paidAmount: firstOrder.amount,
        usedTokens: 0,
        baseRefund: firstOrder.amount,
        fee: 0,
        refundAmount: firstOrder.amount,
        refundType: 'FULL',
      },
    })
    const immutableSnapshot = JSON.stringify(requestedRefund.calculation)

    const approvedRefund = await approveRefund(
      request,
      paidUser.adminSession.accessToken,
      requestedRefund.refundNo,
    )
    expect(approvedRefund.status).toBe('SUBMITTING')
    await pollOutbox(
      request,
      paidUser.adminSession.accessToken,
      `refund-revoke:${requestedRefund.refundNo}`,
      'DELIVERED',
    )
    const completedRefund = await pollRefund(
      request,
      paidUser.userSession.accessToken,
      requestedRefund.refundNo,
      'COMPLETED',
    )
    expect(JSON.stringify(completedRefund.calculation)).toBe(immutableSnapshot)

    const activatedGateway = await pollGatewayPackageState(
      request,
      paidUser.userSession.accessToken,
      ({ activePackage, queuedPackages }) =>
        activePackage?.packageInstanceId === secondPackageId
        && !queuedPackages.some(({ packageInstanceId }) => packageInstanceId === secondPackageId),
      'refund revocation and queued package activation',
    )
    expect(activatedGateway.activePackage?.packageInstanceId).toBe(secondPackageId)
    const terminalFacts = await readPaymentWorkflowFacts(
      paidUser.userSession.user.id,
      [firstOrder.orderNo, secondOrder.orderNo],
    )
    expect(terminalFacts.orders[firstOrder.orderNo]).toMatchObject({
      orderStatus: 'REFUNDED',
      refundStatus: 'COMPLETED',
      packageId: firstPackageId,
      packageStatus: 'REVOKED',
    })
    expect(terminalFacts.orders[secondOrder.orderNo]).toMatchObject({
      orderStatus: 'PAID',
      packageId: secondPackageId,
      packageStatus: 'ACTIVE',
    })

    await verifyResponsivePaymentViews(page, {
      userEmail: paidUser.userSession.user.email,
      orderNo: firstOrder.orderNo,
      refundNo: requestedRefund.refundNo,
      anomalyId: mismatch.anomalyId,
      reconciliationBillDate: reconciliation.billDate,
      reconciliationRunId: reconciliation.runId,
    }, browserErrors)
  } finally {
    await cleanupStackUsers(users, reconciliationRunIds)
  }
})

async function verifyResponsivePaymentViews(page: Page, fixture: {
  userEmail: string
  orderNo: string
  refundNo: string
  anomalyId: string
  reconciliationBillDate: string
  reconciliationRunId: string
}, browserErrors: BrowserErrors) {
  mkdirSync(ARTIFACT_ROOT, { recursive: true })

  for (const viewport of VIEWPORTS) {
    await page.setViewportSize(viewport)
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.context().clearCookies()
    await page.goto('/login')
    await loginThroughUi(page, fixture.userEmail, E2E_USER_PASSWORD)
    await expect(page).toHaveURL(/\/app\/dashboard$/u)

    await page.goto('/app/plans')
    await expect(page.locator('[data-test="buy-plan"]').first()).toBeVisible()
    await page.locator('[data-test="buy-plan"]').first().click()
    await expect(page.locator('[data-test="checkout-summary"]')).toBeVisible()
    await capturePaymentState(page, viewport.name, 'checkout', browserErrors)
    const visualOrderNo = `visual-order-${viewport.name}`
    const visualExpiresAt = new Date(Date.now() + 15 * 60_000).toISOString()
    await page.route('**/console/platform/orders', async (route) => {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          orderNo: visualOrderNo,
          status: 'PENDING',
          amount: 1900,
          currency: 'CNY',
          expiresAt: visualExpiresAt,
          packageSnapshot: {
            packageVersionId: `visual-version-${viewport.name}`,
            packageCode: 'STARTER',
            packageName: 'Starter',
            priceAmount: 1900,
            currency: 'CNY',
            totalTokenQuota: 5_000_000,
            durationDays: 30,
            maxApiKeys: 3,
            requestsPerMinute: 20,
            tokensPerMinute: 100_000,
            maxConcurrency: 3,
            dailyRequestLimit: null,
            maxOutputTokens: null,
            maxFileSizeBytes: 26_214_400,
            oncePerUser: false,
            models: [],
          },
        }),
      })
    }, { times: 1 })
    await page.locator('[data-test="checkout-create-order"]').click()
    await expect(page.locator('[data-test="start-payment"]')).toBeVisible()

    await page.route('**/console/platform/orders/*/payment-attempts', async (route) => {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          transactionNo: `visual-${viewport.name}`,
          status: 'PENDING',
          action: {
            type: 'QR_CODE',
            targetUrl: null,
            fields: {},
            qrContent: `weixin://wxpay/bizpayurl?fixture=${viewport.name}`,
            expiresAt: visualExpiresAt,
          },
          expiresAt: visualExpiresAt,
        }),
      })
    }, { times: 1 })
    await page.locator('[data-test="start-payment"]').click()
    const qr = page.locator('[data-test="payment-qr"]')
    await expect(qr).toBeVisible()
    expect(await qrPixelDiversity(qr)).toBeGreaterThan(1)
    await capturePaymentState(page, viewport.name, 'qr', browserErrors)
    await page.locator('.el-dialog__headerbtn').click()

    await page.goto('/app/orders')
    const orderRow = page.locator('tbody tr').filter({ hasText: fixture.orderNo })
    await expect(orderRow).toBeVisible()
    await orderRow.locator('[data-test="view-order"]').click()
    await expect(page.getByText(fixture.orderNo, { exact: true }).first()).toBeVisible()
    const viewRefund = page.locator('[data-test="view-refund"]')
    if (await viewRefund.isVisible()) await viewRefund.click()
    await expect(page.getByText(fixture.refundNo, { exact: true }).first()).toBeVisible()
    await capturePaymentState(page, viewport.name, 'order-refund', browserErrors)

    await page.context().clearCookies()
    await page.goto('/login')
    await loginThroughUi(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    await expect(page).toHaveURL(/\/admin\/overview$/u)
    await page.goto('/admin/orders')

    await page.getByRole('tab', { name: '退款审核' }).click()
    const refundRow = page.locator('tbody tr').filter({ hasText: fixture.refundNo })
    await expect(refundRow).toBeVisible()
    await refundRow.getByRole('button', { name: '审核' }).click()
    await expect(page.locator('[data-test="refund-review-detail"]')).toContainText(fixture.refundNo)
    await capturePaymentState(page, viewport.name, 'admin-refund', browserErrors)

    await page.getByRole('tab', { name: '异常队列' }).click()
    await expect(page.locator(`[data-test="investigate-anomaly-${fixture.anomalyId}"]`)).toBeVisible()
    await capturePaymentState(page, viewport.name, 'anomaly', browserErrors)

    await page.getByRole('tab', { name: '对账' }).click()
    await page.locator('[data-test="reconciliation-provider"]').click()
    await page.getByRole('option', { name: '本地模拟' }).click()
    await page.locator('[data-test="reconciliation-date"]').fill(fixture.reconciliationBillDate)
    await page.locator('[data-test="start-reconciliation"]').click()
    await expect(page.getByText(fixture.reconciliationRunId, { exact: true })).toHaveCount(0)
    await expect(page.getByText('AMOUNT_MISMATCH', { exact: true })).toBeVisible()
    await capturePaymentState(page, viewport.name, 'reconciliation', browserErrors)
  }
}

async function capturePaymentState(
  page: Page,
  viewport: string,
  name: string,
  browserErrors: BrowserErrors,
) {
  await page.waitForLoadState('networkidle')
  const layout = await page.evaluate(() => {
    const html = document.documentElement
    const visible = (element: HTMLElement) => {
      const rect = element.getBoundingClientRect()
      const style = getComputedStyle(element)
      return style.display !== 'none' && style.visibility !== 'hidden'
        && rect.width > 0 && rect.height > 0
    }
    const dialogs = Array.from(document.querySelectorAll<HTMLElement>(
      '.el-dialog, .el-drawer, [role="dialog"]',
    )).filter(visible).map((element) => {
      const rect = element.getBoundingClientRect()
      return {
        left: rect.left,
        top: rect.top,
        right: rect.right,
        bottom: rect.bottom,
      }
    })
    const controls = Array.from(document.querySelectorAll<HTMLElement>(
      '.el-dialog button, .workflow-actions button, .provider-control [role="radio"]',
    )).filter(visible).map((element) => element.getBoundingClientRect())
    let overlaps = 0
    for (let first = 0; first < controls.length; first++) {
      for (let second = first + 1; second < controls.length; second++) {
        const a = controls[first]
        const b = controls[second]
        const width = Math.min(a.right, b.right) - Math.max(a.left, b.left)
        const height = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top)
        if (width > 1 && height > 1) overlaps += 1
      }
    }
    return {
      documentOverflow: Math.max(0, html.scrollWidth - html.clientWidth),
      dialogs,
      overlaps,
      viewport: { width: window.innerWidth, height: window.innerHeight },
    }
  })

  expect(layout.documentOverflow, `${viewport}/${name} document overflow`).toBeLessThanOrEqual(1)
  expect(layout.overlaps, `${viewport}/${name} controls overlap`).toBe(0)
  expect(browserErrors.console, `${viewport}/${name} console errors`).toEqual([])
  expect(browserErrors.page, `${viewport}/${name} page errors`).toEqual([])
  for (const dialog of layout.dialogs) {
    expect(dialog.left, `${viewport}/${name} dialog left`).toBeGreaterThanOrEqual(-1)
    expect(dialog.right, `${viewport}/${name} dialog right`).toBeLessThanOrEqual(layout.viewport.width + 1)
    expect(dialog.top, `${viewport}/${name} dialog top`).toBeGreaterThanOrEqual(-1)
    expect(dialog.bottom, `${viewport}/${name} dialog bottom`).toBeLessThanOrEqual(layout.viewport.height + 1)
  }
  await page.screenshot({
    path: path.join(ARTIFACT_ROOT, `${viewport}-${name}.png`),
    fullPage: true,
  })
}

function observeBrowserErrors(page: Page): BrowserErrors {
  const errors: BrowserErrors = { console: [], page: [] }
  page.on('console', (message) => {
    if (message.type() === 'error') {
      const location = message.location()
      errors.console.push(`${location.url || 'unknown'}:${location.lineNumber ?? 0} ${message.text()}`)
    }
  })
  page.on('pageerror', (error) => {
    errors.page.push(error.stack || error.message)
  })
  return errors
}

async function qrPixelDiversity(qr: ReturnType<Page['locator']>) {
  return qr.evaluate((image: HTMLImageElement) => {
    const canvas = document.createElement('canvas')
    canvas.width = image.naturalWidth
    canvas.height = image.naturalHeight
    const context = canvas.getContext('2d')
    if (!context || !image.naturalWidth || !image.naturalHeight) return 0
    context.drawImage(image, 0, 0)
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data
    const colors = new Set<string>()
    for (let index = 0; index < pixels.length; index += 4) {
      if (pixels[index + 3] > 0) {
        colors.add(`${pixels[index]},${pixels[index + 1]},${pixels[index + 2]}`)
      }
      if (colors.size > 8) break
    }
    return colors.size
  })
}

async function loginThroughUi(page: Page, email: string, password: string) {
  await page.locator('[data-test="login-identifier"]').fill(email)
  await page.locator('[data-test="login-password"]').fill(password)
  await page.locator('[data-test="login-submit"]').click()
}
