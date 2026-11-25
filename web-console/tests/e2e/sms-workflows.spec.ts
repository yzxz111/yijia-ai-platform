import { randomInt, randomUUID } from 'node:crypto'

import { expect, test, type Page, type Route } from '@playwright/test'

test.use({ trace: 'off' })

type SmsPurpose = 'REGISTER' | 'PHONE_BIND' | 'API_KEY_RESET' | 'PAYMENT_CONFIRM'
const currentSourceSmsPort = '4175'
const currentSourceSmsBaseUrl = `http://127.0.0.1:${currentSourceSmsPort}`
const CAPTCHA_OFFSET_X = 272
const CAPTCHA_IMAGE = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII='
const CONSENT_VERSION = 'e2e-terms-v1'

class RecordingSmsProvider {
  private readonly challenges = new Map<SmsPurpose, { id: string; code: string; verified: boolean }>()
  private readonly proofs = new Map<string, { purpose: SmsPurpose; consumed: boolean }>()
  private readonly captchaChallenges = new Set<string>()
  private readonly captchaProofs = new Set<string>()
  private readonly pendingPhoneRegistrations = new Set<string>()
  private readonly recordedEvents: string[] = []
  private readonly userId = randomUUID()
  private readonly tenantId = randomUUID()
  private readonly apiKeyId = randomUUID()
  private readonly planVersionId = randomUUID()
  private readonly orderNo = `ord-${randomUUID()}`
  private phoneBound = false
  private lastResetProof: string | null = null
  private lastCaptchaProof: string | null = null
  private blockedExternalRequests = 0
  private readonly unknownConsoleRequests: string[] = []

  constructor(private readonly role: 'USER' | 'ADMIN' = 'USER') {}

  async install(page: Page) {
    await page.route(/(?:aliyun|dysmsapi)/iu, (route) => {
      this.blockedExternalRequests += 1
      return route.abort('blockedbyclient')
    })
    await page.route('**/console/**', (route) => this.handle(route))
  }

  codeFor(purpose: SmsPurpose) {
    const challenge = this.challenges.get(purpose)
    if (!challenge) throw new Error(`recording provider did not record ${purpose}`)
    return challenge.code
  }

  captchaProofForReplay() {
    if (!this.lastCaptchaProof) throw new Error('recording provider did not verify a captcha')
    return this.lastCaptchaProof
  }

  events() {
    return [...this.recordedEvents]
  }

  externalRequestCount() {
    return this.blockedExternalRequests
  }

  unknownConsoleRequestCount() {
    return this.unknownConsoleRequests.length
  }

  unknownConsoleRequestDescriptions() {
    return [...this.unknownConsoleRequests]
  }

  replayLastReset() {
    const proof = this.lastResetProof
    if (!proof) return false
    const accepted = this.consumeProof(proof, 'API_KEY_RESET')
    if (!accepted) this.recordedEvents.push('proof-replay-rejected')
    return accepted
  }

  private async handle(route: Route) {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const legalDocument = path.match(/^\/console\/legal\/(TERMS|PRIVACY)$/u)
    if (request.method() === 'GET' && legalDocument) {
      return this.json(route, 200, {
        kind: legalDocument[1],
        version: CONSENT_VERSION,
        title: legalDocument[1] === 'TERMS' ? '平台服务协议' : '隐私政策',
        content: { sections: [{ heading: '测试协议', paragraphs: ['仅用于本地录制式测试。'] }] },
      })
    }
    if (request.method() === 'POST' && path === '/console/platform/auth/slider-captcha/challenges') {
      const id = randomUUID()
      this.captchaChallenges.add(id)
      this.recordedEvents.push('captcha-issued')
      return this.json(route, 201, {
        id,
        backgroundImage: CAPTCHA_IMAGE,
        pieceImage: CAPTCHA_IMAGE,
        pieceY: 56,
        imageWidth: 320,
        imageHeight: 160,
        pieceSize: 48,
        expiresAt: futureIso(),
      })
    }
    const captchaVerify = path.match(/^\/console\/platform\/auth\/slider-captcha\/challenges\/([^/]+)\/verify$/u)
    if (request.method() === 'POST' && captchaVerify) {
      const { offsetX } = request.postDataJSON() as { offsetX: number }
      if (!this.captchaChallenges.has(captchaVerify[1]) || offsetX !== CAPTCHA_OFFSET_X) {
        return this.json(route, 422, { code: 'captcha_invalid' })
      }
      this.captchaChallenges.delete(captchaVerify[1])
      const captchaProof = randomUUID()
      this.captchaProofs.add(captchaProof)
      this.lastCaptchaProof = captchaProof
      this.recordedEvents.push('captcha-verified')
      return this.json(route, 200, { captchaProof, expiresAt: futureIso() })
    }
    if (request.method() === 'POST' && path === '/console/platform/auth/sms-login/challenges') {
      const { captchaProof } = request.postDataJSON() as { captchaProof: string }
      if (!this.captchaProofs.has(captchaProof)) {
        return this.json(route, 422, { code: 'captcha_rejected' })
      }
      return this.json(route, 422, { code: 'sms_login_unavailable' })
    }
    if (request.method() === 'POST' && path === '/console/platform/auth/sms/challenges') {
      const body = request.postDataJSON() as { purpose: SmsPurpose; captchaProof: string }
      if (!this.captchaProofs.delete(body.captchaProof)) {
        return this.json(route, 422, { code: 'captcha_rejected' })
      }
      return this.issueChallenge(route, body.purpose)
    }
    if (request.method() === 'POST' && path === '/console/platform/sms/challenges') {
      const body = request.postDataJSON() as { purpose: SmsPurpose }
      return this.issueChallenge(route, body.purpose)
    }
    if (request.method() === 'POST' && path === '/console/platform/auth/login') {
      const body = request.postDataJSON() as { email: string }
      this.recordedEvents.push('authenticated-user')
      return this.json(route, 200, session(this.role, body.email, 'E2E User', this.userId, this.tenantId))
    }
    if (request.method() === 'POST' && path === '/console/platform/auth/identity') {
      const body = request.postDataJSON() as {
        identifier: string
        challengeId: string
        verificationCode: string
        termsAccepted: boolean
        consentVersion: string
      }
      const challenge = this.challenges.get('REGISTER')
      if (!challenge || challenge.id !== body.challengeId
          || challenge.code !== body.verificationCode) {
        return this.json(route, 422, { code: 'challenge_rejected' })
      }
      if (!body.termsAccepted || body.consentVersion !== CONSENT_VERSION) {
        return this.json(route, 422, { code: 'terms_acceptance_required' })
      }
      this.challenges.delete('REGISTER')
      this.pendingPhoneRegistrations.add(body.challengeId)
      this.recordedEvents.push('registration-pending')
      return this.json(route, 202, {
        code: 'phone_registration_pending',
        challengeId: body.challengeId,
        expiresAt: futureIso(),
      })
    }
    if (request.method() === 'POST' && path === '/console/platform/auth/identity/complete-registration') {
      const body = request.postDataJSON() as {
        challengeId: string
        newPassword: string
        termsAccepted: boolean
        consentVersion: string
      }
      if (!this.pendingPhoneRegistrations.has(body.challengeId)
          || !body.termsAccepted || body.consentVersion !== CONSENT_VERSION
          || !/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{8,}$/u.test(body.newPassword)) {
        return this.json(route, 422, { code: 'registration_rejected' })
      }
      this.pendingPhoneRegistrations.delete(body.challengeId)
      this.recordedEvents.push('registration-complete')
      return this.json(route, 201, session(
        this.role, 'e2e@phone.local', 'E2E User', this.userId, this.tenantId,
      ))
    }
    if (request.method() === 'GET' && path === '/console/platform/profile') {
      return this.json(route, 200, {
        id: this.userId,
        tenantId: this.tenantId,
        email: 'e2e@local.test',
        displayName: 'E2E User',
        role: this.role,
        status: 'ACTIVE',
        lastLoginAt: null,
        createdAt: new Date(Date.now() - 86_400_000).toISOString(),
        maskedPhone: this.phoneBound ? '1** **** ****' : null,
        phoneVerified: this.phoneBound,
        phoneState: this.phoneBound ? 'VERIFIED' : 'UNBOUND',
        smsNotificationsEnabled: true,
        sessions: [],
      })
    }
    if (request.method() === 'POST' && path === '/console/platform/profile/phone/challenges') {
      const body = request.postDataJSON() as { purpose: SmsPurpose }
      return this.issueChallenge(route, body.purpose)
    }
    if (request.method() === 'POST' && /^\/console\/platform\/sms\/challenges\/[^/]+\/verify$/u.test(path)) {
      const body = request.postDataJSON() as { code: string }
      const challenge = [...this.challenges.entries()].find(([, value]) => path.includes(value.id))
      if (!challenge || challenge[1].code !== body.code) {
        return this.json(route, 422, { code: 'challenge_rejected' })
      }
      challenge[1].verified = true
      this.recordedEvents.push(`verified:${challenge[0]}`)
      const proof = challenge[0] === 'PHONE_BIND' ? null : randomUUID()
      if (proof) this.proofs.set(proof, { purpose: challenge[0], consumed: false })
      return this.json(route, 200, {
        smsProof: proof,
        purpose: challenge[0],
        expiresAt: futureIso(),
      })
    }
    if (request.method() === 'PUT' && path === '/console/platform/profile/phone') {
      const body = request.postDataJSON() as { challengeId: string; purpose: SmsPurpose }
      const challenge = this.challenges.get(body.purpose)
      if (!challenge || challenge.id !== body.challengeId || !challenge.verified) {
        return this.json(route, 422, { code: 'challenge_rejected' })
      }
      this.challenges.delete(body.purpose)
      this.phoneBound = true
      this.recordedEvents.push('binding')
      return route.fulfill({ status: 204 })
    }
    if (request.method() === 'GET' && path === '/console/gateway/api-keys') {
      return this.json(route, 200, [{
        id: this.apiKeyId,
        name: 'recording-key',
        keyPrefix: 'sk-e2e',
        allowedModels: ['text-basic'],
        status: 'ACTIVE',
        lastUsedAt: null,
        expiresAt: null,
        createdAt: new Date(Date.now() - 3_600_000).toISOString(),
      }])
    }
    if (request.method() === 'GET' && path === '/console/gateway/api-keys/eligible-models') {
      return this.json(route, 200, ['text-basic'])
    }
    if (request.method() === 'GET'
      && path === '/console/platform/notifications/unread-count') {
      return this.json(route, 200, { count: 0 })
    }
    if (request.method() === 'POST' && path === '/console/platform/auth/terminal-presence') {
      return this.json(route, 200, {
        status: 'ACTIVE',
        disabledReason: null,
        disabledAt: null,
      })
    }
    if (request.method() === 'GET' && path === '/console/platform/auth/login-handoffs/pending') {
      return route.fulfill({ status: 204 })
    }
    if (request.method() === 'GET' && path === '/console/platform/referrals/me') {
      return this.json(route, 200, { activeCode: null, successCount: 0 })
    }
    if (request.method() === 'GET' && path === '/console/gateway/quota/summary') {
      return this.json(route, 200, {
        activePackage: { packageCode: 'E2E', allowedModels: ['text-basic'] },
        queuedPackages: [],
      })
    }
    if (request.method() === 'GET' && path === '/console/gateway/usage') {
      return this.json(route, 200, { items: [], total: 0, page: 0, size: 20 })
    }
    if (request.method() === 'GET' && path === '/console/gateway/usage/metrics') {
      return this.json(route, 200, { sampleCount: 0, platformAddedP95Ms: 0 })
    }
    if (request.method() === 'GET' && path === '/console/gateway/usage/analytics') {
      const days = Number(new URL(request.url()).searchParams.get('days')) === 7 ? 7 : 30
      const now = new Date().toISOString()
      return this.json(route, 200, {
        range: { days, timezone: 'Asia/Shanghai', from: now, to: now },
        summary: {
          todayRequests: 0,
          todaySuccessfulRequests: 0,
          todayFailedRequests: 0,
          todayTokens: 0,
          periodRequests: 0,
          periodTokens: 0,
          platformAddedP95Ms: 0,
        },
        daily: [],
        models: [],
      })
    }
    if (request.method() === 'GET' && path === '/console/platform/plans') {
      return this.json(route, 200, [this.plan()])
    }
    if (request.method() === 'GET' && path === '/console/platform/plans/free/claim-status') {
      return this.json(route, 200, { claimed: false })
    }
    if (request.method() === 'GET' && path === '/console/platform/subscriptions') {
      return this.json(route, 200, [])
    }
    if (request.method() === 'GET' && path === '/console/platform/wallet') {
      return this.json(route, 200, {
        availableAmount: 0,
        currency: 'CNY',
        updatedAt: new Date().toISOString(),
      })
    }
    if (request.method() === 'POST' && path === '/console/platform/orders') {
      const plan = this.plan()
      this.recordedEvents.push('order-created')
      return this.json(route, 201, {
        orderNo: this.orderNo,
        status: 'PENDING',
        amount: plan.priceAmount,
        currency: plan.currency,
        expiresAt: futureIso(),
        packageSnapshot: {
          packageVersionId: plan.versionId,
          packageCode: plan.code,
          packageName: plan.name,
          priceAmount: plan.priceAmount,
          currency: plan.currency,
          totalTokenQuota: plan.totalTokenQuota,
          durationDays: plan.durationDays,
          maxApiKeys: plan.maxApiKeys,
          requestsPerMinute: plan.requestsPerMinute,
          tokensPerMinute: plan.tokensPerMinute,
          maxConcurrency: plan.maxConcurrency,
          dailyRequestLimit: plan.dailyRequestLimit,
          maxOutputTokens: plan.maxOutputTokens,
          maxFileSizeBytes: plan.maxFileSizeBytes,
          oncePerUser: plan.oncePerUser,
          models: plan.models,
        },
      })
    }
    if (request.method() === 'POST'
      && path === `/console/platform/orders/${this.orderNo}/payment-attempts`) {
      const body = request.postDataJSON() as { smsProof?: string }
      if (!body.smsProof || !this.consumeProof(body.smsProof, 'PAYMENT_CONFIRM')) {
        return this.json(route, 409, { code: 'proof_rejected' })
      }
      this.recordedEvents.push('payment-proof-consumed')
      return this.json(route, 201, {
        transactionNo: `pay-${randomUUID()}`,
        status: 'PENDING',
        action: { type: 'NONE', expiresAt: futureIso() },
        expiresAt: futureIso(),
      })
    }
    if (request.method() === 'GET' && path === '/console/gateway/admin/operations/health') {
      return this.json(route, 200, {
        status: 'UP', database: 'UP', expiredReservations: 0, cleanupCandidates: 0, circuits: [],
      })
    }
    if (request.method() === 'GET' && path === '/console/platform/admin/overview') {
      return this.json(route, 200, {
        users: 0,
        activeUsers: 0,
        grants: 0,
        outboxAttention: 0,
        orders: 0,
        paidOrders: 0,
        paidAmount: 0,
      })
    }
    if (request.method() === 'GET' && path === '/console/platform/admin/analytics') {
      return this.json(route, 200, {
        timezone: 'Asia/Shanghai',
        months: 6,
        summary: {
          registeredUsers: 0,
          activeUsers: 0,
          currentMonthCollectedAmount: 0,
          currentMonthRefundedAmount: 0,
          currentMonthNetAmount: 0,
          paidOrders: 0,
        },
        monthly: [],
      })
    }
    if (request.method() === 'GET' && path === '/console/gateway/admin/analytics') {
      const days = Number(new URL(request.url()).searchParams.get('days')) === 7 ? 7 : 30
      const now = new Date().toISOString()
      return this.json(route, 200, {
        range: { days, timezone: 'Asia/Shanghai', from: now, to: now },
        summary: {
          todayRequests: 0,
          todaySuccessfulRequests: 0,
          todayFailedRequests: 0,
          todayTokens: 0,
          periodRequests: 0,
          periodTokens: 0,
          platformAddedP95Ms: 0,
        },
        daily: [],
        models: [],
        packages: [],
      })
    }
    if (request.method() === 'GET' && path === '/console/gateway/admin/usage') {
      return this.json(route, 200, { items: [], total: 0, page: 1, size: 5 })
    }
    if (request.method() === 'GET' && path === '/console/gateway/admin/routing/health') {
      return this.json(route, 200, { channels: [], circuits: [] })
    }
    if (request.method() === 'GET' && path === '/console/platform/admin/outbox/health') {
      return this.json(route, 200, { pending: 0, delivering: 0, failed: 0, oldestSeconds: 0 })
    }
    if (request.method() === 'GET' && path === '/console/platform/admin/outbox') {
      return this.json(route, 200, { items: [], total: 0, page: 0, size: 20 })
    }
    if (request.method() === 'GET' && path === '/console/platform/admin/sms/templates') {
      this.recordedEvents.push('admin-sms-templates-read')
      return this.json(route, 200, [{
        templateKey: 'REGISTER_CODE',
        templateCode: 'SMS_511405090',
        templateType: 'VERIFICATION',
        integrationStatus: 'ACTIVE',
        createdAt: new Date(Date.now() - 86_400_000).toISOString(),
        updatedAt: new Date().toISOString(),
      }])
    }
    if (request.method() === 'GET' && path === '/console/platform/admin/sms/deliveries') {
      this.recordedEvents.push('admin-sms-deliveries-read')
      return this.json(route, 200, {
        items: [{
          id: randomUUID(),
          templateKey: 'REGISTER_CODE',
          templateCode: 'SMS_511405090',
          maskedPhone: '1** **** ****',
          status: 'SENT',
          attemptCount: 1,
          nextAttemptAt: new Date().toISOString(),
          sanitizedError: null,
          sentAt: new Date().toISOString(),
          createdAt: new Date(Date.now() - 60_000).toISOString(),
          updatedAt: new Date().toISOString(),
        }],
        total: 1,
        page: 0,
        size: 20,
      })
    }
    if (request.method() === 'POST' && path === `/console/gateway/api-keys/${this.apiKeyId}/reset`) {
      const proof = request.headers()['x-sms-proof']
      if (!proof || !this.consumeProof(proof, 'API_KEY_RESET')) {
        return this.json(route, 409, { code: 'proof_rejected' })
      }
      this.lastResetProof = proof
      this.recordedEvents.push('reset-header-consumed')
      return this.json(route, 200, {
        id: this.apiKeyId,
        keyPrefix: `sk-${randomUUID().slice(0, 8)}`,
        secret: randomUUID(),
      })
    }
    this.unknownConsoleRequests.push(`${request.method()} ${path}`)
    return route.abort('blockedbyclient')
  }

  private issueChallenge(route: Route, purpose: SmsPurpose) {
    const challenge = { id: randomUUID(), code: runtimeCode(), verified: false }
    this.challenges.set(purpose, challenge)
    this.recordedEvents.push(`challenge:${purpose}`)
    return this.json(route, 201, {
      id: challenge.id,
      purpose,
      maskedPhone: '1** **** ****',
      expiresAt: futureIso(),
    })
  }

  private json(route: Route, status: number, body: unknown) {
    return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
  }

  private consumeProof(value: string, purpose: SmsPurpose) {
    const proof = this.proofs.get(value)
    if (!proof || proof.purpose !== purpose || proof.consumed) return false
    proof.consumed = true
    return true
  }

  private plan() {
    return {
      code: 'E2E_PAID',
      name: 'Recording Plan',
      description: null,
      versionId: this.planVersionId,
      version: 1,
      priceAmount: 100,
      currency: 'CNY',
      totalTokenQuota: 10_000,
      durationDays: 30,
      maxApiKeys: 2,
      requestsPerMinute: 60,
      tokensPerMinute: 10_000,
      maxConcurrency: 2,
      dailyRequestLimit: null,
      maxOutputTokens: null,
      maxFileSizeBytes: 1_048_576,
      oncePerUser: false,
      estimatedCostAmount: null,
      estimatedMarginRatio: null,
      marginWarning: false,
      models: [{ modelCode: 'text-basic', modelTokenQuota: null, capabilities: ['TEXT'] }],
    }
  }
}

test('legacy registration links show direct password login without external verification', async ({ page }) => {
  const provider = new RecordingSmsProvider()
  await provider.install(page)
  await page.goto(currentSourceUrl('/register'))
  await expectManagedCurrentSource(page)
  await expect(page).toHaveURL(/\/login$/u)
  await expect(page.locator('[data-test="login-password"]')).toBeVisible()
  await expect(page.locator('[data-test="sms-send"]')).toHaveCount(0)
  await expect(page.locator('[data-test="email-send"]')).toHaveCount(0)
  await page.locator('[data-test="login-identifier"]').fill(randomUUID() + '@local.test')
  await page.locator('[data-test="login-password"]').fill(runtimePassword())
  await page.locator('[data-test="login-submit"]').click()
  await expect(page).toHaveURL(/\/app\/dashboard$/u)
  expect(provider.events()).toEqual(['authenticated-user'])
  expect(provider.externalRequestCount()).toBe(0)
  expect(provider.unknownConsoleRequestDescriptions()).toEqual([])
})

test('a legacy user enters the authenticated SMS workflow through the recording provider', async ({ page }) => {
  const provider = new RecordingSmsProvider()
  await provider.install(page)

  await page.goto(currentSourceUrl('/login'))
  await expectManagedCurrentSource(page)
  await page.locator('[data-test="login-identifier"]').fill(`${randomUUID()}@local.test`)
  await page.locator('[data-test="login-password"]').fill(runtimePassword())
  await page.locator('[data-test="login-submit"]').click()

  await expect(page).toHaveURL(/\/app\/dashboard$/u)
  const welcome = page.locator('.welcome-dialog')
  await expect(welcome).toBeVisible()
  await welcome.getByRole('button', { name: '开始使用' }).click()
  await expect(welcome).toBeHidden()

  await page.locator('[data-test="desktop-nav-profile"]').click()
  await expect(page).toHaveURL(/\/app\/profile$/u)
  await expect(page.locator('[data-test="profile-phone"]')).toBeVisible()
  await fillSensitive(page, '[data-test="profile-phone"]', runtimePhone())
  await page.locator('[data-test="sms-send"]').click()
  await expect.poll(() => provider.events()).toContain('challenge:PHONE_BIND')
  await fillSensitive(page, '[data-test="sms-code"] input', provider.codeFor('PHONE_BIND'))
  await page.locator('[data-test="save-phone"]').click()

  await expect.poll(() => provider.events()).toContain('binding')

  await page.locator('a[href="/app/plans"]').click()
  await expect(page.locator('[data-test="buy-plan"]')).toBeVisible()
  await page.locator('[data-test="buy-plan"]').click()
  await page.locator('[data-test="checkout-create-order"]').click()
  await expect(page.locator('[data-test="start-payment"]')).toBeVisible()
  await page.locator('[data-test="start-payment"]').click()
  await page.locator('[data-test="sms-send"]').click()
  await expect.poll(() => provider.events()).toContain('challenge:PAYMENT_CONFIRM')
  await fillSensitive(page, '[data-test="sms-code"] input', provider.codeFor('PAYMENT_CONFIRM'))
  await page.locator('[data-test="sms-verify"]').click()

  await expect.poll(() => provider.events()).toContain('payment-proof-consumed')
  expect(provider.externalRequestCount()).toBe(0)
  expect(provider.unknownConsoleRequestDescriptions()).toEqual([])
})

test('an administrator inspects recording-provider deliveries with masked recipients', async ({ page }) => {
  const provider = new RecordingSmsProvider('ADMIN')
  await provider.install(page)

  await page.goto(currentSourceUrl('/login'))
  await expectManagedCurrentSource(page)
  await page.locator('[data-test="login-identifier"]').fill(`${randomUUID()}@local.test`)
  await page.locator('[data-test="login-password"]').fill(runtimePassword())
  await page.locator('[data-test="login-submit"]').click()
  await expect(page).toHaveURL(/\/admin\/overview$/u)
  await page.locator('a[href="/admin/operations"]').click()

  await expect.poll(() => provider.events()).toContain('admin-sms-templates-read')
  await expect.poll(() => provider.events()).toContain('admin-sms-deliveries-read')
  await expect(page.locator('[data-test="sms-templates-table"]')).toBeVisible()
  await page.locator('[data-test="sms-deliveries-tab"]').click()
  const deliveries = page.locator('[data-test="sms-deliveries-table"]')
  await expect(deliveries).toBeVisible()
  await expect(deliveries).toContainText('1** **** ****')
  await expect(deliveries).not.toContainText(/\b1[3-9]\d{9}\b/u)
  expect(provider.externalRequestCount()).toBe(0)
  expect(provider.unknownConsoleRequestDescriptions()).toEqual([])
})

function runtimePhone() {
  return `1${randomInt(3, 10)}${randomInt(0, 1_000_000_000).toString().padStart(9, '0')}`
}

function runtimePassword() {
  return `${randomUUID()}Aa!`
}

function currentSourceUrl(path: string) {
  return new URL(path, currentSourceSmsBaseUrl).toString()
}

async function completeSliderCaptcha(page: Page) {
  const dialog = page.locator('.slider-captcha-dialog')
  await expect(dialog).toBeVisible()
  const slider = dialog.locator('[data-test="captcha-slider"]')
  await expect(slider).toBeEnabled()
  await slider.focus()
  await slider.press('End')
  await expect(slider).toHaveValue(String(CAPTCHA_OFFSET_X))
  await dialog.locator('[data-test="captcha-submit"]').click()
  await expect(dialog).toBeHidden()
}

async function expectManagedCurrentSource(page: Page) {
  expect(new URL(page.url()).port).toBe(currentSourceSmsPort)
  await expect(page.locator('script[type="module"][src="/@vite/client"]')).toHaveCount(1)
}

async function fillSensitive(page: Page, selector: string, value: string) {
  await page.locator(selector).evaluate((element, nextValue) => {
    const input = element as HTMLInputElement
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
    setter?.call(input, nextValue)
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new Event('change', { bubbles: true }))
  }, value)
}

function runtimeCode() {
  return randomInt(0, 1_000_000).toString().padStart(6, '0')
}

function futureIso() {
  return new Date(Date.now() + 5 * 60_000).toISOString()
}

function session(
  role: 'USER' | 'ADMIN',
  email: string,
  displayName: string,
  id = randomUUID(),
  tenantId = randomUUID(),
) {
  return {
    accessToken: randomUUID(),
    expiresIn: 900,
    user: {
      id,
      tenantId,
      email,
      displayName,
      role,
      status: 'ACTIVE',
    },
  }
}
