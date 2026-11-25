import { execFileSync } from 'node:child_process'
import { createHash, createHmac, randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { expect, type APIRequestContext, type APIResponse } from '@playwright/test'

const COMPOSE_FILE = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../../../deploy/compose.yaml',
)
const DOCKER_COMMAND_TIMEOUT_MS = 30_000
const POLL_INTERVAL_MS = 250
const E2E_COMPOSE_PROJECT_NAME = process.env.E2E_COMPOSE_PROJECT_NAME?.trim()

if (E2E_COMPOSE_PROJECT_NAME
    && !/^yzh-[a-z0-9][a-z0-9-]{0,62}$/u.test(E2E_COMPOSE_PROJECT_NAME)) {
  throw new Error('invalid E2E_COMPOSE_PROJECT_NAME')
}

// 管理员密码与模拟支付回调密钥不再提交为
// 默认值：compose.yaml 要求它们来自部署环境。读取启动该栈时
// 所用的同一个 env 文件，能让 E2E 凭据与运行中的服务保持一致；
// 否则过期的硬编码回退值会用错误的密钥签署模拟回调。
const COMPOSE_ENV_FILE = process.env.E2E_COMPOSE_ENV_FILE
  ?? path.resolve(path.dirname(COMPOSE_FILE), 'env', '.env')

/**
 * compose.yaml 现在用 `${VAR:?}` 声明了必填密钥（本地管理员口令、内部 HMAC、登录风控 pepper 等），
 * 缺一个就整份文件插值失败。`docker compose` 默认只在工程目录找 `.env`，而部署环境文件在
 * `deploy/env/.env`，所以每个 compose 调用都必须显式带上 `--env-file`，否则 e2e 会以
 * "required variable ... is missing a value" 失败（而不是被测代码的问题）。
 */
export { COMPOSE_ENV_FILE }

/** 登录要求 32 位十六进制设备号；与本文件以外的 console-visibility.spec.ts 用同一字面量。 */
export const E2E_ADMIN_DEVICE = process.env.E2E_ADMIN_DEVICE_ID ?? '0123456789abcdef0123456789abcdef'
export const E2E_USER_DEVICE = process.env.E2E_USER_DEVICE_ID ?? 'fedcba9876543210fedcba9876543210'

function readComposeEnvironmentValue(name: string): string | undefined {
  try {
    const source = readFileSync(COMPOSE_ENV_FILE, 'utf8')
    for (const line of source.split(/\r?\n/u)) {
      if (!line || line.startsWith('#')) continue
      const separator = line.indexOf('=')
      if (separator > 0 && line.slice(0, separator).trim() === name) {
        return line.slice(separator + 1).trim().replace(/^["']|["']$/gu, '')
      }
    }
  } catch {
    return undefined
  }
  return undefined
}

function requireComposeEnvironmentValue(name: string, override: string): string {
  const value = readComposeEnvironmentValue(name)
  if (!value) {
    throw new Error(
      `${name} is not configured; set ${override} or add ${name} to ${COMPOSE_ENV_FILE}`,
    )
  }
  return value
}

export const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL ?? 'admin@local.test'
export const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD
  ?? requireComposeEnvironmentValue('LOCAL_ADMIN_PASSWORD', 'E2E_ADMIN_PASSWORD')
export const E2E_USER_PASSWORD = 'E2eUser!2026'

/**
 * 套餐版本 id **不能写死**：定价迁移会把旧版本置为 RETIRED（例如 V61 就退役了最初种子的
 * `…0101/0102/0103`），于是写死的常量会让"发放套餐"以 `published_package_version_not_found`
 * 变成 404，看起来像功能坏了。这里按 plan code 查当前 PUBLISHED 的最高版本，迁移换代后自动跟上。
 */
function publishedVersionId(planCode: string): string {
  const id = runPostgres(`
    SELECT v.id
    FROM platform.package_plan_version v
    JOIN platform.package_plan p ON p.id = v.package_plan_id
    WHERE p.code = ${sqlLiteral(planCode)} AND v.status = 'PUBLISHED'
    ORDER BY v.version DESC
    LIMIT 1
  `).trim()
  if (!id) {
    throw new Error(`no PUBLISHED package version for ${planCode}; run the stack migrations first`)
  }
  return id
}

export const FREE_PACKAGE_VERSION_ID = publishedVersionId('FREE')
export const STARTER_PACKAGE_VERSION_ID = publishedVersionId('STARTER')
export const ADVANCED_PACKAGE_VERSION_ID = publishedVersionId('ADVANCED')
export const ALLOWED_MODELS = [
  'text-basic',
  'vision-basic',
  'file-basic',
  'multimodal-basic',
]

export interface ProvisioningOptions {
  packageVersionId?: string
  packageCode?: 'FREE' | 'STARTER' | 'ADVANCED' | 'ENTERPRISE'
  apiKeyAllowedModels?: string[]
}

export interface SessionUser {
  id: string
  tenantId: string
  email: string
  displayName: string
  role: 'USER' | 'ADMIN'
}

export interface SessionResponse {
  accessToken: string
  expiresIn: number
  user: SessionUser
}

export interface QuotaSummary {
  activePackage: null | {
    packageInstanceId: string
    packageCode: string
    status: string
    totalTokens: number
    reservedTokens: number
    usedTokens: number
    remainingTokens: number
    allowedModels: string[]
    modelQuotas: Array<{
      modelCode: string
      totalTokens: number
      reservedTokens: number
      usedTokens: number
      remainingTokens: number
    }>
  }
  queuedPackages: Array<{ packageInstanceId: string; packageCode: string }>
}

export interface ApiKeyView {
  id: string
  name: string
  keyPrefix: string
  allowedModels: string[]
  status: 'ACTIVE' | 'DISABLED' | 'REVOKED'
  expiresAt: string
  createdAt?: string
  lastUsedAt?: string | null
}

export interface CreatedApiKey extends Omit<ApiKeyView, 'createdAt' | 'lastUsedAt'> {
  secret: string
}

export interface ProvisionedStackUser {
  adminSession: SessionResponse
  userSession: SessionResponse
  quotaSummary: QuotaSummary
  apiKey: CreatedApiKey
  runId: string
}

export interface RegisteredStackUser {
  adminSession: SessionResponse
  userSession: SessionResponse
  runId: string
}

export interface ProvisionedStackAdmin extends RegisteredStackUser {
  email: string
  password: string
  deviceId: string
}

/**
 * 为浏览器验收测试创建一个隔离的非全局管理员。
 *
 * 本地 E2E 套件本来就通过数据库创建用户，并用 cleanupStackUsers 删除它们。
 * 专家编辑器验收需要一个 ADMIN 角色，但不能占用或替换运维已初始化的
 * 全局管理员所占的设备位，因此它复用同一套隔离生命周期，
 * 并使用按次运行生成的设备号。
 */
export async function provisionLocalStackAdmin(
  request: APIRequestContext,
): Promise<ProvisionedStackAdmin> {
  const runId = `${Date.now().toString(36)}-${randomUUID().slice(0, 8)}`
  const userId = randomUUID()
  const email = `e2e-expert-admin-${runId}@local.test`
  const password = process.env.E2E_EXPERT_ADMIN_PASSWORD ?? 'E2eExpertAdmin!2026'
  const deviceId = createHash('sha256').update(`${runId}:${userId}`).digest('hex').slice(0, 32)

  runPostgres(`
    INSERT INTO platform.app_user(
      id, tenant_id, email, password_hash, display_name, status, global_admin)
    VALUES (
      ${sqlLiteral(userId)}::uuid, ${sqlLiteral(userId)}::uuid,
      ${sqlLiteral(email)}, crypt(${sqlLiteral(password)}, gen_salt('bf', 12)),
      ${sqlLiteral(`E2E Expert Admin ${runId}`)}, 'ACTIVE', false);
    INSERT INTO platform.user_role(tenant_id, user_id, role_id)
    SELECT ${sqlLiteral(userId)}::uuid, ${sqlLiteral(userId)}::uuid, id
    FROM platform.app_role WHERE code = 'ADMIN';
  `)

  const loginResponse = await request.post('/console/platform/auth/login', {
    headers: { 'X-Device-Id': deviceId },
    data: { email, password },
  })
  await expectStatus(loginResponse, 200, 'login isolated E2E expert administrator')
  const session = await loginResponse.json() as SessionResponse
  expect(session).toMatchObject({
    accessToken: expect.any(String),
    user: { id: userId, tenantId: userId, email, role: 'ADMIN' },
  })
  return { adminSession: session, userSession: session, runId, email, password, deviceId }
}

/**
 * 只回收隔离的浏览器管理员，绝不回收共享的运维租户。登录通知/审计记录
 * 刻意作为不可变的 E2E 证据保留，与 cleanupStackUsers 的
 * 「保留证据」行为保持一致；该账号会被禁用，其所有会话都会被撤销。
 */
export async function cleanupLocalStackAdmin(account: ProvisionedStackAdmin) {
  const userId = account.userSession.user.id
  runPostgres(`
    BEGIN;
    UPDATE platform.app_user
    SET status = 'DISABLED', auth_version = auth_version + 1, updated_at = now()
    WHERE id = ${sqlLiteral(userId)}::uuid;
    UPDATE platform.refresh_token
    SET revoked_at = COALESCE(revoked_at, now()),
        revocation_reason = COALESCE(revocation_reason, 'E2E_CLEANUP')
    WHERE user_id = ${sqlLiteral(userId)}::uuid AND revoked_at IS NULL;
    UPDATE platform.terminal_session
    SET status = 'REVOKED', revoked_at = COALESCE(revoked_at, now()),
        revocation_reason = COALESCE(revocation_reason, 'E2E_CLEANUP')
    WHERE user_id = ${sqlLiteral(userId)}::uuid AND status = 'ACTIVE';
    COMMIT;
  `)
}

export async function provisionLocalStackUser(
  request: APIRequestContext,
): Promise<RegisteredStackUser> {
  const runId = `${Date.now().toString(36)}-${randomUUID().slice(0, 8)}`
  const userId = randomUUID()
  const userEmail = `e2e-wallet-${runId}@local.test`

  const adminLoginResponse = await request.post('/console/platform/auth/login', {
    data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
    // 登录接口要求设备号；缺了会得到 invalid_device_id，与"管理员口令错误"完全不同的原因
    headers: { 'X-Device-Id': E2E_ADMIN_DEVICE },
  })
  await expectStatus(adminLoginResponse, 200, 'admin login')
  const adminSession = await adminLoginResponse.json() as SessionResponse

  runPostgres(`
    INSERT INTO platform.app_user(
      id, tenant_id, email, password_hash, display_name, status)
    VALUES (
      ${sqlLiteral(userId)}::uuid, ${sqlLiteral(userId)}::uuid,
      ${sqlLiteral(userEmail)}, crypt(${sqlLiteral(E2E_USER_PASSWORD)}, gen_salt('bf', 12)),
      ${sqlLiteral(`E2E Wallet ${runId}`)}, 'ACTIVE');
    INSERT INTO platform.user_role(tenant_id, user_id, role_id)
    SELECT ${sqlLiteral(userId)}::uuid, ${sqlLiteral(userId)}::uuid, id
    FROM platform.app_role WHERE code = 'USER';
    DO $provision$
    BEGIN
      IF to_regclass('platform.account_balance') IS NOT NULL THEN
        EXECUTE format(
          'INSERT INTO platform.account_balance(id, tenant_id, user_id, currency) '
          || 'VALUES (gen_random_uuid(), %L::uuid, %L::uuid, ''CNY'') '
          || 'ON CONFLICT (tenant_id, user_id, currency) DO NOTHING',
          ${sqlLiteral(userId)}, ${sqlLiteral(userId)});
      END IF;
    END
    $provision$;
  `)

  const userLoginResponse = await request.post('/console/platform/auth/login', {
    data: { email: userEmail, password: E2E_USER_PASSWORD },
    headers: { 'X-Device-Id': E2E_USER_DEVICE },
  })
  await expectStatus(userLoginResponse, 200, 'login local wallet test user')
  const userSession = await userLoginResponse.json() as SessionResponse
  expect(userSession).toMatchObject({
    accessToken: expect.any(String),
    user: { id: userId, tenantId: userId, email: userEmail, role: 'USER' },
  })
  return { adminSession, userSession, runId }
}

export interface GrantResult {
  id: string
  eventId: string
  businessNo: string
  status: string
}

export interface CreatedOrder {
  orderNo: string
  status: string
  amount: number
  currency: string
  expiresAt: string
  packageSnapshot: Record<string, unknown>
}

export interface PaymentAction {
  type: 'NONE' | 'QR_CODE' | 'REDIRECT_URL' | 'HTML_FORM'
  targetUrl: string | null
  fields: Record<string, string>
  qrContent: string | null
  expiresAt: string
}

export interface PaymentAttemptResult {
  transactionNo: string
  status: string
  action: PaymentAction
  expiresAt: string
}

export interface OrderDetail {
  order: {
    id: string
    orderNo: string
    packageCode: string
    packageName: string
    amount: number
    currency: string
    status: string
    paymentProvider: string | null
    paymentStatus: string | null
    refundStatus: string | null
  }
  packageSnapshot: Record<string, unknown>
  expiresAt: string
  paymentAttempts: Array<{
    transactionNo: string
    provider: string
    product: string
    status: string
    providerStatus: string | null
    providerErrorCode: string | null
    paidAt: string | null
    createdAt: string
  }>
  grant: { grantRequestId: string; status: string; updatedAt: string } | null
  refund: {
    refundNo: string
    status: string
    refundType: string | null
    amount: number | null
    requestedAt: string
    reviewedAt: string | null
    completedAt: string | null
  } | null
}

export interface RefundCalculation {
  paidAmount: number
  paidAt: string
  requestedAt: string
  totalTokens: number
  usedTokens: number
  remainingTokens: number
  feeBps: number
  minimumFee: number
  maximumUsageBps: number
  refundWindowDays: number
  baseRefund: number
  fee: number
  refundAmount: number
  refundType: string
  rounding: string
  policyVersion: number
  calculatedAt: string
  packageInstanceId: string
  originalPackageStatus: string
  packageExpiresAt: string | null
  gatewaySnapshotAt: string
}

export interface RefundView {
  refundNo: string
  orderNo: string
  status: string
  refundType: string | null
  amount: number
  reason: string
  calculation: RefundCalculation | null
  reviewedBy: string | null
  reviewedAt: string | null
  reviewNote: string | null
  failureReason: string | null
  createdAt: string
  updatedAt: string
}

export interface WalletSummary {
  availableAmount: number
  currency: 'CNY'
  updatedAt: string
}

export interface WalletLedgerEntry {
  entryNo: string
  entryType: 'RECHARGE' | 'PACKAGE_PURCHASE' | 'PACKAGE_REFUND'
  amountDelta: number
  balanceAfter: number
  businessNo: string
  description: string
  createdAt: string
}

export interface WalletWorkflowFacts {
  availableAmount: number
  rechargeStatus: string
  orderStatus: string
  settlementMethod: string
  refundStatus: string
  fundingSource: string
  packageStatus: string
  rechargeCredits: number
  purchaseDebits: number
  refundCredits: number
}

export interface PaymentWorkflowFacts {
  freeGrantCount: number
  freeOutboxCount: number
  freePackageCount: number
  orders: Record<string, {
    orderId: string
    orderStatus: string
    attemptCount: number
    grantCount: number
    paymentPostAuditCount: number
    lastQueried: boolean
    refundStatus: string | null
    packageId: string | null
    packageStatus: string | null
  }>
}

export function bearer(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` }
}

export function apiKeyBearer(secret: string) {
  return { Authorization: `Bearer ${secret}` }
}

export async function expectStatus(
  response: APIResponse,
  expected: number,
  operation: string,
) {
  const detail = response.status() === expected ? '' : `: ${await response.text()}`
  expect(response.status(), `${operation}${detail}`).toBe(expected)
}

export async function registerStackUser(
  request: APIRequestContext,
): Promise<RegisteredStackUser> {
  return provisionLocalStackUser(request)
}

export async function claimFreePackage(
  request: APIRequestContext,
  accessToken: string,
) {
  const response = await request.post('/console/platform/plans/free/claim', {
    headers: bearer(accessToken),
  })
  await expectStatus(response, 200, 'claim FREE package')
  return response.json() as Promise<GrantResult>
}

export async function createPaidOrder(
  request: APIRequestContext,
  accessToken: string,
  packageVersionId: string,
  idempotencyKey: string,
) {
  const response = await request.post('/console/platform/orders', {
    headers: { ...bearer(accessToken), 'Idempotency-Key': idempotencyKey },
    data: { packageVersionId },
  })
  await expectStatus(response, 201, 'create paid order')
  return response.json() as Promise<CreatedOrder>
}

export async function createMockPaymentAttempt(
  request: APIRequestContext,
  accessToken: string,
  orderNo: string,
) {
  const response = await request.post(
    `/console/platform/orders/${encodeURIComponent(orderNo)}/payment-attempts`,
    {
      headers: bearer(accessToken),
      data: { provider: 'MOCK', product: 'MOCK' },
    },
  )
  await expectStatus(response, 201, 'create MOCK payment attempt')
  return response.json() as Promise<PaymentAttemptResult>
}

export async function simulateMockPayment(
  request: APIRequestContext,
  adminAccessToken: string,
  orderNo: string,
) {
  const response = await request.post(
    `/console/platform/admin/orders/${encodeURIComponent(orderNo)}/simulate-payment`,
    { headers: bearer(adminAccessToken) },
  )
  await expectStatus(response, 200, 'simulate MOCK payment')
  return response.json() as Promise<{
    orderNo: string
    status: string
    externalTradeNo: string
  }>
}

export async function postMockCallback(
  request: APIRequestContext,
  callback: { externalTradeNo: string; orderNo: string; amount: number },
) {
  const secret = process.env.E2E_PAYMENT_MOCK_CALLBACK_SECRET
    ?? requireComposeEnvironmentValue('PAYMENT_MOCK_CALLBACK_SECRET', 'E2E_PAYMENT_MOCK_CALLBACK_SECRET')
  const signature = createHmac('sha256', secret)
    .update(`${callback.externalTradeNo}\n${callback.orderNo}\n${callback.amount}`)
    .digest('hex')
  const response = await request.post('/console/platform/payments/mock/callback', {
    headers: { 'X-Payment-Signature': signature },
    data: callback,
  })
  await expectStatus(response, 200, 'post signed MOCK callback')
  return response.json() as Promise<{ orderNo: string; status: string }>
}

export async function getWallet(
  request: APIRequestContext,
  accessToken: string,
) {
  const response = await request.get('/console/platform/wallet', {
    headers: bearer(accessToken),
  })
  await expectStatus(response, 200, 'read wallet')
  return response.json() as Promise<WalletSummary>
}

export async function getWalletLedger(
  request: APIRequestContext,
  accessToken: string,
) {
  const response = await request.get('/console/platform/wallet/ledger?size=100', {
    headers: bearer(accessToken),
  })
  await expectStatus(response, 200, 'read wallet ledger')
  return response.json() as Promise<{
    items: WalletLedgerEntry[]
    total: number
    page: number
    size: number
  }>
}

export async function createRecharge(
  request: APIRequestContext,
  accessToken: string,
  amount: number,
  idempotencyKey: string,
) {
  const response = await request.post('/console/platform/wallet/recharges', {
    headers: { ...bearer(accessToken), 'Idempotency-Key': idempotencyKey },
    data: { amount },
  })
  await expectStatus(response, 201, 'create recharge order')
  return response.json() as Promise<{
    rechargeNo: string
    paidAmount: number
    status: string
  }>
}

export async function createRechargeMockPaymentAttempt(
  request: APIRequestContext,
  accessToken: string,
  rechargeNo: string,
) {
  const response = await request.post(
    `/console/platform/wallet/recharges/${encodeURIComponent(rechargeNo)}/payment-attempts`,
    {
      headers: bearer(accessToken),
      data: { provider: 'MOCK', product: 'MOCK' },
    },
  )
  await expectStatus(response, 201, 'create recharge MOCK payment attempt')
  return response.json() as Promise<PaymentAttemptResult>
}

export async function payOrderFromBalance(
  request: APIRequestContext,
  accessToken: string,
  orderNo: string,
  idempotencyKey: string,
) {
  const response = await request.post(
    `/console/platform/orders/${encodeURIComponent(orderNo)}/balance-payment`,
    { headers: { ...bearer(accessToken), 'Idempotency-Key': idempotencyKey } },
  )
  await expectStatus(response, 200, 'pay package order from wallet')
  return response.json() as Promise<{
    orderNo: string
    orderStatus: string
    settlementMethod: string
    balanceBefore: number
    balanceAfter: number
    grantRequestId: string
    grantStatus: string
  }>
}

export async function pollWallet(
  request: APIRequestContext,
  accessToken: string,
  accepted: (wallet: WalletSummary) => boolean,
  label: string,
  deadlineMs = 30_000,
) {
  return pollUntil(label, deadlineMs, () => getWallet(request, accessToken), accepted)
}

export function readWalletWorkflowFacts(
  userId: string,
  rechargeNo: string,
  orderNo: string,
  refundNo: string,
): WalletWorkflowFacts {
  const output = runPostgres(`
    SELECT json_build_object(
      'availableAmount', account.available_amount,
      'rechargeStatus', recharge.status,
      'orderStatus', orders.status,
      'settlementMethod', orders.settlement_method,
      'refundStatus', refund.status,
      'fundingSource', refund.funding_source,
      'packageStatus', package_instance.status,
      'rechargeCredits', (
        SELECT count(*) FROM platform.balance_ledger ledger
        WHERE ledger.user_id = account.user_id
          AND ledger.entry_type = 'RECHARGE'
          AND ledger.business_no = recharge.recharge_no),
      'purchaseDebits', (
        SELECT count(*) FROM platform.balance_ledger ledger
        WHERE ledger.user_id = account.user_id
          AND ledger.entry_type = 'PACKAGE_PURCHASE'
          AND ledger.business_no = orders.order_no),
      'refundCredits', (
        SELECT count(*) FROM platform.balance_ledger ledger
        WHERE ledger.user_id = account.user_id
          AND ledger.entry_type = 'PACKAGE_REFUND'
          AND ledger.business_no = refund.refund_no))
    FROM platform.account_balance account
    JOIN platform.recharge_order recharge ON recharge.user_id = account.user_id
    JOIN platform.customer_order orders ON orders.user_id = account.user_id
    JOIN platform.refund refund ON refund.order_id = orders.id
    JOIN platform.grant_request grant_request
      ON grant_request.business_no = 'order:' || orders.order_no
    JOIN gateway.package_instance package_instance
      ON package_instance.grant_request_id = grant_request.id
    WHERE account.user_id = ${sqlLiteral(userId)}::uuid
      AND recharge.recharge_no = ${sqlLiteral(rechargeNo)}
      AND orders.order_no = ${sqlLiteral(orderNo)}
      AND refund.refund_no = ${sqlLiteral(refundNo)}
  `).trim()
  if (!output) throw new Error(`wallet facts missing for ${orderNo}`)
  return JSON.parse(output) as WalletWorkflowFacts
}

export async function closeOrderForQueryRace(
  request: APIRequestContext,
  accessToken: string,
  orderNo: string,
) {
  const response = await request.post(
    `/console/platform/orders/${encodeURIComponent(orderNo)}/close`,
    { headers: bearer(accessToken) },
  )
  await expectStatus(response, 200, 'query-before-close order race')
  return response.json() as Promise<{
    orderNo: string
    orderStatus: string
    paymentStatus: string
    retryScheduled: boolean
  }>
}

export async function requestRefund(
  request: APIRequestContext,
  accessToken: string,
  orderNo: string,
  reason: string,
) {
  const response = await request.post(
    `/console/platform/orders/${encodeURIComponent(orderNo)}/refunds`,
    { headers: bearer(accessToken), data: { reason } },
  )
  await expectStatus(response, 201, 'request zero-usage refund')
  return response.json() as Promise<RefundView>
}

export async function approveRefund(
  request: APIRequestContext,
  adminAccessToken: string,
  refundNo: string,
) {
  const response = await request.post(
    `/console/platform/admin/refunds/${encodeURIComponent(refundNo)}/approve`,
    { headers: bearer(adminAccessToken) },
  )
  await expectStatus(response, 200, 'approve refund')
  return response.json() as Promise<RefundView>
}

export async function expectRefundApprovalReplayRejected(
  request: APIRequestContext,
  adminAccessToken: string,
  refundNo: string,
) {
  const response = await request.post(
    `/console/platform/admin/refunds/${encodeURIComponent(refundNo)}/approve`,
    { headers: bearer(adminAccessToken) },
  )
  await expectStatus(response, 409, 'reject replayed terminal refund approval')
}

export async function pollOrder(
  request: APIRequestContext,
  accessToken: string,
  orderNo: string,
  accepted: (detail: OrderDetail) => boolean,
  label: string,
  deadlineMs = 20_000,
) {
  return pollUntil(label, deadlineMs, async () => {
    const response = await request.get(
      `/console/platform/orders/${encodeURIComponent(orderNo)}`,
      { headers: bearer(accessToken) },
    )
    await expectStatus(response, 200, `poll order ${orderNo}`)
    return response.json() as Promise<OrderDetail>
  }, accepted)
}

export async function pollRefund(
  request: APIRequestContext,
  accessToken: string,
  refundNo: string,
  expectedStatus: string,
  deadlineMs = 30_000,
) {
  return pollUntil(`refund ${refundNo} -> ${expectedStatus}`, deadlineMs, async () => {
    const response = await request.get(
      `/console/platform/refunds/${encodeURIComponent(refundNo)}`,
      { headers: bearer(accessToken) },
    )
    await expectStatus(response, 200, `poll refund ${refundNo}`)
    return response.json() as Promise<RefundView>
  }, ({ status }) => status === expectedStatus)
}

export async function pollOutbox(
  request: APIRequestContext,
  adminAccessToken: string,
  businessKey: string,
  expectedStatus: string,
  deadlineMs = 20_000,
) {
  return pollUntil(`Outbox ${businessKey} -> ${expectedStatus}`, deadlineMs, async () => {
    const response = await request.get(
      `/console/platform/admin/outbox?size=100&query=${encodeURIComponent(businessKey)}`,
      { headers: bearer(adminAccessToken) },
    )
    await expectStatus(response, 200, `poll Outbox ${businessKey}`)
    const page = await response.json() as {
      items: Array<{
        id: string
        businessKey: string
        status: string
        attemptCount: number
        nextAttemptAt: string
      }>
    }
    return page.items.find((item) => item.businessKey === businessKey) ?? null
  }, (event) => event?.status === expectedStatus)
}

export async function pollGatewayPackageState(
  request: APIRequestContext,
  accessToken: string,
  accepted: (summary: QuotaSummary) => boolean,
  label: string,
  deadlineMs = 20_000,
) {
  return pollUntil(label, deadlineMs, async () => {
    const response = await request.get('/console/gateway/quota/summary', {
      headers: bearer(accessToken),
    })
    await expectStatus(response, 200, 'poll gateway package state')
    return response.json() as Promise<QuotaSummary>
  }, accepted)
}

export function seedAmountMismatchAnomaly(
  userId: string,
  orderNo: string,
  transactionNo: string,
  expectedAmount: number,
) {
  const anomalyId = randomUUID()
  const fingerprint = createHash('sha256')
    .update(`PAYMENT_AMOUNT_MISMATCH\n${transactionNo}\n${expectedAmount + 1}`)
    .digest('hex')
  const output = runPostgres(`
    WITH target AS (
      SELECT orders.id AS order_id, payment.id AS payment_id
      FROM platform.customer_order orders
      JOIN platform.payment_transaction payment ON payment.order_id = orders.id
      WHERE orders.user_id = ${sqlLiteral(userId)}::uuid
        AND orders.order_no = ${sqlLiteral(orderNo)}
        AND payment.transaction_no = ${sqlLiteral(transactionNo)}
    ), inserted AS (
      INSERT INTO platform.payment_anomaly(
        id, fingerprint, anomaly_type, provider, order_id,
        payment_transaction_id, status, detail)
      SELECT ${sqlLiteral(anomalyId)}::uuid, ${sqlLiteral(fingerprint)},
        'PAYMENT_AMOUNT_MISMATCH', 'MOCK', order_id, payment_id, 'OPEN',
        jsonb_build_object(
          'reason', 'AMOUNT_MISMATCH',
          'expectedAmount', ${expectedAmount},
          'observedAmount', ${expectedAmount + 1})
      FROM target
      RETURNING order_id
    )
    SELECT order_id::text FROM inserted
  `).trim()
  if (!output) throw new Error(`failed to seed amount mismatch for ${orderNo}`)
  return { anomalyId, orderId: output }
}

export function seedReconciliationDifference(
  userId: string,
  transactionNo: string,
  platformAmount: number,
  runId: string,
) {
  const reconciliationRunId = randomUUID()
  const itemId = randomUUID()
  const fixtureHash = createHash('sha256').update(`bill:${runId}`).digest('hex')
  const dateSeed = Number.parseInt(fixtureHash.slice(0, 8), 16) % 70_000
  const date = new Date(Date.UTC(1800, 0, 1 + dateSeed)).toISOString().slice(0, 10)
  const providerRecordId = `fixture-${runId}`
  const output = runPostgres(`
    WITH target AS (
      SELECT payment.transaction_no
      FROM platform.payment_transaction payment
      JOIN platform.customer_order orders ON orders.id = payment.order_id
      WHERE orders.user_id = ${sqlLiteral(userId)}::uuid
        AND payment.transaction_no = ${sqlLiteral(transactionNo)}
    ), inserted_run AS (
      INSERT INTO platform.reconciliation_run(
        id, provider, bill_date, status, bill_sha256, summary,
        started_at, completed_at)
      SELECT ${sqlLiteral(reconciliationRunId)}::uuid, 'MOCK', ${sqlLiteral(date)}::date,
        'COMPLETED', ${sqlLiteral(fixtureHash)},
        '{"totalRecords":1,"matchedCount":0,"differenceCount":1,"autoResolvedCount":0,"openCount":1,"differenceCounts":{"AMOUNT_MISMATCH":1}}'::jsonb,
        now(), now()
      FROM target
      RETURNING id
    ), inserted_item AS (
      INSERT INTO platform.reconciliation_item(
        id, run_id, provider_record_id, transaction_no,
        difference_type, status, provider_amount, platform_amount, detail)
      SELECT ${sqlLiteral(itemId)}::uuid, inserted_run.id,
        ${sqlLiteral(providerRecordId)}, target.transaction_no,
        'AMOUNT_MISMATCH', 'OPEN', ${platformAmount + 1}, ${platformAmount},
        jsonb_build_object('reason', 'AMOUNT_MISMATCH')
      FROM inserted_run CROSS JOIN target
      RETURNING run_id
    )
    SELECT run_id::text FROM inserted_item
  `).trim()
  if (output !== reconciliationRunId) {
    throw new Error(`failed to seed reconciliation difference for ${transactionNo}: ${output}`)
  }
  return { runId: reconciliationRunId, billDate: date }
}

export async function readPaymentWorkflowFacts(
  userId: string,
  orderNos: string[],
): Promise<PaymentWorkflowFacts> {
  const freeBusinessNo = `free:${userId}`
  const freeFields = runPostgres(`
    SELECT
      (SELECT count(*) FROM platform.grant_request
       WHERE user_id = ${sqlLiteral(userId)}::uuid
         AND business_no = ${sqlLiteral(freeBusinessNo)}),
      (SELECT count(*) FROM platform.outbox_event
       WHERE business_key = ${sqlLiteral(freeBusinessNo)}),
      (SELECT count(*) FROM gateway.package_instance package_instance
       JOIN platform.grant_request grant_request
         ON grant_request.id = package_instance.grant_request_id
       WHERE grant_request.user_id = ${sqlLiteral(userId)}::uuid
         AND grant_request.business_no = ${sqlLiteral(freeBusinessNo)})
  `).trim().split('|')
  if (freeFields.length !== 3) {
    throw new Error(`unexpected FREE workflow facts: ${freeFields.join('|')}`)
  }

  const orders: PaymentWorkflowFacts['orders'] = {}
  for (const orderNo of orderNos) {
    const fields = runPostgres(`
      SELECT
        orders.id::text,
        orders.status,
        (SELECT count(*) FROM platform.payment_transaction payment
         WHERE payment.order_id = orders.id),
        (SELECT count(*) FROM platform.grant_request grant_request
         WHERE grant_request.business_no = 'order:' || orders.order_no),
        (SELECT count(*) FROM platform.admin_audit_log audit
         WHERE audit.action = 'PAYMENT_POSTED'
           AND audit.resource_type = 'ORDER'
           AND audit.resource_id = orders.order_no),
        EXISTS(SELECT 1 FROM platform.payment_transaction payment
          WHERE payment.order_id = orders.id AND payment.last_queried_at IS NOT NULL),
        COALESCE((SELECT refund.status FROM platform.refund refund
          WHERE refund.order_id = orders.id ORDER BY refund.created_at DESC LIMIT 1), ''),
        COALESCE((SELECT package_instance.id::text
          FROM gateway.package_instance package_instance
          JOIN platform.grant_request grant_request
            ON grant_request.id = package_instance.grant_request_id
          WHERE grant_request.business_no = 'order:' || orders.order_no), ''),
        COALESCE((SELECT package_instance.status
          FROM gateway.package_instance package_instance
          JOIN platform.grant_request grant_request
            ON grant_request.id = package_instance.grant_request_id
          WHERE grant_request.business_no = 'order:' || orders.order_no), '')
      FROM platform.customer_order orders
      WHERE orders.user_id = ${sqlLiteral(userId)}::uuid
        AND orders.order_no = ${sqlLiteral(orderNo)}
    `).trim().split('|')
    if (fields.length !== 9 || !fields[0]) {
      throw new Error(`unexpected order workflow facts for ${orderNo}: ${fields.join('|')}`)
    }
    orders[orderNo] = {
      orderId: fields[0],
      orderStatus: fields[1],
      attemptCount: Number.parseInt(fields[2], 10),
      grantCount: Number.parseInt(fields[3], 10),
      paymentPostAuditCount: Number.parseInt(fields[4], 10),
      lastQueried: fields[5] === 't',
      refundStatus: fields[6] || null,
      packageId: fields[7] || null,
      packageStatus: fields[8] || null,
    }
  }

  return {
    freeGrantCount: Number.parseInt(freeFields[0], 10),
    freeOutboxCount: Number.parseInt(freeFields[1], 10),
    freePackageCount: Number.parseInt(freeFields[2], 10),
    orders,
  }
}

export async function cleanupStackUsers(
  users: RegisteredStackUser[],
  reconciliationRunIds: string[] = [],
) {
  if (!users.length && !reconciliationRunIds.length) return
  const requestedUserIds = users.map(({ userSession }) => userSession.user.id)
  const walletSchemaAvailable = runPostgres(
    "SELECT to_regclass('platform.balance_ledger') IS NOT NULL",
  ).trim() === 't'
  const notificationSchemaAvailable = runPostgres(
    "SELECT to_regclass('platform.notification_event') IS NOT NULL",
  ).trim() === 't'
  const preservedUserIds = requestedUserIds.filter((id) => {
    const evidenceQueries = [
      ...(walletSchemaAvailable
        ? [`SELECT 1 FROM platform.balance_ledger WHERE user_id = ${sqlLiteral(id)}::uuid`]
        : []),
      ...(notificationSchemaAvailable
        ? [`SELECT 1 FROM platform.notification_event WHERE user_id = ${sqlLiteral(id)}::uuid`]
        : []),
    ]
    if (!evidenceQueries.length) return false
    return runPostgres(`SELECT EXISTS(${evidenceQueries.join(' UNION ALL ')})`).trim() === 't'
  })
  const preservedIds = new Set(preservedUserIds)
  const userIds = requestedUserIds.filter((id) => !preservedIds.has(id))
  const userList = userIds.length
    ? userIds.map((id) => `${sqlLiteral(id)}::uuid`).join(', ')
    : 'NULL::uuid'
  const preservedUserList = preservedUserIds.length
    ? preservedUserIds.map((id) => `${sqlLiteral(id)}::uuid`).join(', ')
    : 'NULL::uuid'
  const runList = reconciliationRunIds.length
    ? reconciliationRunIds.map((id) => `${sqlLiteral(id)}::uuid`).join(', ')
    : 'NULL::uuid'
  runPostgres(`
    BEGIN;
    UPDATE platform.app_user
    SET status = 'DISABLED', auth_version = auth_version + 1, updated_at = now()
    WHERE id IN (${preservedUserList});
    UPDATE platform.refresh_token
    SET revoked_at = COALESCE(revoked_at, now())
    WHERE user_id IN (${preservedUserList});
    UPDATE gateway.api_key
    SET status = 'REVOKED', updated_at = now()
    WHERE user_id IN (${preservedUserList}) AND status <> 'REVOKED';

    CREATE TEMP TABLE e2e_packages ON COMMIT DROP AS
      SELECT id FROM gateway.package_instance WHERE user_id IN (${userList});
    CREATE TEMP TABLE e2e_outbox ON COMMIT DROP AS
      SELECT event.id
      FROM platform.outbox_event event
      LEFT JOIN platform.grant_request grant_request
        ON event.aggregate_type = 'GRANT' AND grant_request.id = event.aggregate_id
      LEFT JOIN platform.refund refund
        ON event.aggregate_type = 'REFUND' AND refund.id = event.aggregate_id
      LEFT JOIN platform.customer_order orders ON orders.id = refund.order_id
      WHERE grant_request.user_id IN (${userList}) OR orders.user_id IN (${userList});

    DELETE FROM gateway.package_lifecycle_event
    WHERE package_instance_id IN (SELECT id FROM e2e_packages)
       OR activated_package_instance_id IN (SELECT id FROM e2e_packages);
    DELETE FROM gateway.package_refund_hold
    WHERE package_instance_id IN (SELECT id FROM e2e_packages);
    DELETE FROM gateway.provider_cost_record
    WHERE usage_log_id IN (
      SELECT id FROM gateway.usage_log WHERE user_id IN (${userList}));
    DELETE FROM gateway.usage_log WHERE user_id IN (${userList});
    -- Ledger rows are immutable. Preserve only their required reservation,
    -- API-key, and package dependency chain until the E2E volume is destroyed.
    DELETE FROM gateway.quota_reservation reservation
    WHERE reservation.user_id IN (${userList})
      AND NOT EXISTS (
        SELECT 1 FROM gateway.quota_ledger ledger
        WHERE ledger.reservation_id = reservation.id);
    DELETE FROM gateway.api_key api_key
    WHERE api_key.user_id IN (${userList})
      AND NOT EXISTS (
        SELECT 1 FROM gateway.quota_reservation reservation
        WHERE reservation.api_key_id = api_key.id);
    DELETE FROM gateway.provider_file_binding
    WHERE file_object_id IN (
      SELECT id FROM gateway.file_object WHERE owner_user_id IN (${userList}));
    DELETE FROM gateway.file_object WHERE owner_user_id IN (${userList});
    DELETE FROM gateway.request_limit_fallback_lease WHERE user_id IN (${userList});
    DELETE FROM gateway.request_limit_fallback_event WHERE user_id IN (${userList});
    DELETE FROM gateway.user_request_limit WHERE user_id IN (${userList});
    DELETE FROM gateway.admin_operation_audit WHERE actor_user_id IN (${userList});
    DELETE FROM gateway.consumed_event WHERE event_id IN (SELECT id FROM e2e_outbox);
    DELETE FROM gateway.package_instance package_instance
    WHERE package_instance.id IN (SELECT id FROM e2e_packages)
      AND NOT EXISTS (
        SELECT 1 FROM gateway.quota_ledger ledger
        WHERE ledger.package_instance_id = package_instance.id
           OR ledger.source_package_instance_id = package_instance.id);

    DELETE FROM platform.reconciliation_run WHERE id IN (${runList});
    DELETE FROM platform.payment_anomaly
    WHERE order_id IN (
      SELECT id FROM platform.customer_order WHERE user_id IN (${userList}))
       OR payment_transaction_id IN (
      SELECT payment.id FROM platform.payment_transaction payment
      JOIN platform.customer_order orders ON orders.id = payment.order_id
      WHERE orders.user_id IN (${userList}))
       ${walletSchemaAvailable ? `OR payment_transaction_id IN (
      SELECT payment.id FROM platform.payment_transaction payment
      JOIN platform.recharge_order recharge
        ON recharge.id = payment.recharge_order_id
      WHERE recharge.user_id IN (${userList}))` : ''}
       OR refund_id IN (
      SELECT refund.id FROM platform.refund refund
      JOIN platform.customer_order orders ON orders.id = refund.order_id
      WHERE orders.user_id IN (${userList}));
    DELETE FROM platform.admin_audit_log
    WHERE tenant_id IN (
      SELECT tenant_id FROM platform.app_user WHERE id IN (${userList}));
    DELETE FROM platform.outbox_event WHERE id IN (SELECT id FROM e2e_outbox);
    DELETE FROM platform.refund
    WHERE order_id IN (
      SELECT id FROM platform.customer_order WHERE user_id IN (${userList}));
    UPDATE platform.customer_order
    SET successful_payment_transaction_id = NULL
    WHERE user_id IN (${userList});
    DELETE FROM platform.payment_transaction
    WHERE order_id IN (
      SELECT id FROM platform.customer_order WHERE user_id IN (${userList}));
    ${walletSchemaAvailable ? `
    DELETE FROM platform.payment_transaction
    WHERE recharge_order_id IN (
      SELECT id FROM platform.recharge_order WHERE user_id IN (${userList}));
    DELETE FROM platform.recharge_order WHERE user_id IN (${userList});
    ` : ''}
    DELETE FROM platform.customer_order WHERE user_id IN (${userList});
    DELETE FROM platform.grant_request WHERE user_id IN (${userList});
    ${walletSchemaAvailable
      ? `DELETE FROM platform.account_balance WHERE user_id IN (${userList});`
      : ''}
    DELETE FROM platform.app_user WHERE id IN (${userList});
    COMMIT;
  `)
}

async function pollUntil<T>(
  label: string,
  deadlineMs: number,
  observe: () => Promise<T>,
  accepted: (state: T) => boolean,
) {
  const deadline = Date.now() + deadlineMs
  let lastObserved: T | undefined
  while (Date.now() <= deadline) {
    lastObserved = await observe()
    if (accepted(lastObserved)) return lastObserved
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS))
  }
  throw new Error(
    `${label} timed out after ${deadlineMs} ms; last observed state: ${safeJson(lastObserved)}`,
  )
}

function runPostgres(sql: string) {
  return execFileSync('docker', [
    'compose',
    ...(E2E_COMPOSE_PROJECT_NAME
      ? ['--project-name', E2E_COMPOSE_PROJECT_NAME]
      : []),
    '--env-file', COMPOSE_ENV_FILE,
    '-f', COMPOSE_FILE,
    'exec', '-T', 'postgres',
    'psql', '-U', 'postgres', '-d', 'api_model_platform',
    '-X', '-A', '-t', '-F', '|',
    '-v', 'ON_ERROR_STOP=1', '-c', sql,
  ], {
    encoding: 'utf8',
    timeout: DOCKER_COMMAND_TIMEOUT_MS,
    windowsHide: true,
  })
}

function sqlLiteral(value: string) {
  return `'${value.replaceAll("'", "''")}'`
}

function safeJson(value: unknown) {
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

export async function listApiKeys(request: APIRequestContext, accessToken: string) {
  const response = await request.get('/console/gateway/api-keys', {
    headers: bearer(accessToken),
  })
  await expectStatus(response, 200, 'list API keys')
  return response.json() as Promise<ApiKeyView[]>
}

export function expectAllowedModels(models: string[], exact = true) {
  if (exact) expect(models).toHaveLength(ALLOWED_MODELS.length)
  expect(models).toEqual(expect.arrayContaining(ALLOWED_MODELS))
}

export async function provisionUserWithApiKey(
  request: APIRequestContext,
  options: ProvisioningOptions = {},
): Promise<ProvisionedStackUser> {
  const packageVersionId = options.packageVersionId ?? FREE_PACKAGE_VERSION_ID
  const packageCode = options.packageCode ?? 'FREE'
  const apiKeyAllowedModels = options.apiKeyAllowedModels ?? ALLOWED_MODELS
  const { adminSession, userSession, runId } = await provisionLocalStackUser(request)

  const businessNo = `grant-e2e-${randomUUID()}`
  const grantResponse = await request.post('/console/platform/admin/grants', {
    headers: bearer(adminSession.accessToken),
    data: {
      userId: userSession.user.id,
      packageVersionId,
      reason: 'Playwright E2E',
      businessNo,
    },
  })
  await expectStatus(grantResponse, 201, 'grant FREE package')
  const grant = await grantResponse.json() as {
    id: string
    eventId: string
    businessNo: string
    status: string
  }
  expect(grant).toMatchObject({
    id: expect.any(String),
    eventId: expect.any(String),
    businessNo,
    status: 'QUEUED',
  })

  let quotaSummary: QuotaSummary | undefined
  await expect.poll(async () => {
    const response = await request.get('/console/gateway/quota/summary', {
      headers: bearer(userSession.accessToken),
    })
    await expectStatus(response, 200, 'poll quota summary')
    quotaSummary = await response.json() as QuotaSummary
    return quotaSummary.activePackage?.status ?? null
  }, {
    message: 'the queued FREE grant should become active',
    timeout: 20_000,
    intervals: [100, 250, 500, 1_000],
  }).toBe('ACTIVE')

  expect(quotaSummary?.activePackage).toMatchObject({
    packageInstanceId: expect.any(String),
    packageCode,
    status: 'ACTIVE',
  })
  expectAllowedModels(quotaSummary!.activePackage!.allowedModels, packageCode === 'FREE')

  const keyName = `E2E key ${runId}`
  const createKeyResponse = await request.post('/console/gateway/api-keys', {
    headers: bearer(userSession.accessToken),
    data: { name: keyName, allowedModels: apiKeyAllowedModels },
  })
  await expectStatus(createKeyResponse, 201, 'create API key')
  const apiKey = await createKeyResponse.json() as CreatedApiKey
  expect(apiKey).toMatchObject({
    id: expect.any(String),
    name: keyName,
    keyPrefix: expect.stringMatching(/^sk-yzh-/),
    status: 'ACTIVE',
    expiresAt: expect.any(String),
    secret: expect.stringMatching(/^sk-yzh-/),
  })
  expect(apiKey.allowedModels).toHaveLength(apiKeyAllowedModels.length)
  expect(apiKey.allowedModels).toEqual(expect.arrayContaining(apiKeyAllowedModels))

  return { adminSession, userSession, quotaSummary: quotaSummary!, apiKey, runId }
}
