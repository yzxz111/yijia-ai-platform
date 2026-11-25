import { platformHttp, readCookie } from './http'
import { getDeviceId } from '../utils/deviceId'
import { isValidOptionalInvitationCode, normalizeInvitationCode } from '../invitationCode'

export interface SessionUser {
  id: string
  tenantId: string
  email: string | null
  displayName: string
  role: 'USER' | 'ADMIN'
  status: string
  passwordSet: boolean
}

export interface SessionResponse {
  accessToken: string
  expiresIn: number
  user: SessionUser
}

export type LoginHandoffStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'DENIED'
  | 'CANCELLED'
  | 'SUPERSEDED'
  | 'COMPLETED'
  | 'EXPIRED'

export interface LoginHandoffPending {
  code: 'login_handoff_required'
  requestId: string
  status: 'PENDING'
  expiresAt: string
  pollAfterSeconds: number
}

export interface DeviceLoginRecovery {
  code: 'login_device_recovery_required'
  requestId: string
  expiresAt: string
  deviceLimit: number
  devices: { id: string; createdAt: string; recentlyOnline: boolean }[]
  csrfToken: string
}

export type LoginResult = SessionResponse | LoginHandoffPending | DeviceLoginRecovery

/**
 * 登录/注册端点可能返回的全部结果：本地的设备恢复与登录交接，加上 gitee 分支带来的
 * 「手机号/邮箱注册待设密」两种情况。守卫统一按这个最宽的联合类型收窄，
 * 调用方就不必为了类型通过而在每个文件里各写一份交叉判断。
 */
export type LoginOutcome = LoginResult | PhoneRegistrationPending | EmailRegistrationPending

export function isDeviceLoginRecovery(result: LoginOutcome): result is DeviceLoginRecovery {
  return 'code' in result && result.code === 'login_device_recovery_required'
}

export async function completeDeviceLoginRecovery(recovery: DeviceLoginRecovery, sessionIds: string[]): Promise<SessionResponse> {
  return (await platformHttp.post<SessionResponse>(
    `/console/platform/auth/device-recovery/${encodeURIComponent(recovery.requestId)}/complete`,
    { sessionIds }, { headers: { 'X-CSRF-Token': recovery.csrfToken } },
  )).data
}

export function isLoginHandoffPending(result: LoginOutcome): result is LoginHandoffPending {
  return 'code' in result && result.code === 'login_handoff_required'
}

export interface PhoneRegistrationPending {
  code: 'phone_registration_pending'
  challengeId: string
  expiresAt: string
}

export type IdentityLoginResult = LoginResult | PhoneRegistrationPending

export function isPhoneRegistrationPending(
  result: IdentityLoginResult | EmailRegistrationPending,
): result is PhoneRegistrationPending {
  return 'code' in result && result.code === 'phone_registration_pending'
}

export interface EmailRegistrationPending {
  code: 'email_registration_pending'
  challengeId: string
  expiresAt: string
}

export type EmailIdentityLoginResult = LoginResult | EmailRegistrationPending

export function isEmailRegistrationPending(
  result: EmailIdentityLoginResult | PhoneRegistrationPending,
): result is EmailRegistrationPending {
  return 'code' in result && result.code === 'email_registration_pending'
}

export interface LoginHandoffStatusView {
  requestId: string
  status: LoginHandoffStatus
  expiresAt: string
}

export interface PendingLoginApproval {
  requestId: string
  requestedAt: string
  expiresAt: string
  region: string
  clientSummary: string
}

export type SmsPurpose =
  | 'REGISTER'
  | 'LOGIN'
  | 'PASSWORD_RESET'
  | 'CHANGE_PASSWORD'
  | 'PAYMENT_CONFIRM'
  | 'API_KEY_RESET'
  | 'PHONE_BIND'
  | 'PHONE_CHANGE'
  | 'PHONE_CHANGE_CONFIRM'

export type SensitiveSmsPurpose = 'CHANGE_PASSWORD' | 'PAYMENT_CONFIRM' | 'API_KEY_RESET' | 'PHONE_CHANGE_CONFIRM'
export type PhoneSmsPurpose = 'PHONE_BIND' | 'PHONE_CHANGE'

export interface SmsChallenge {
  id: string
  purpose: SmsPurpose
  maskedPhone: string
  expiresAt: string
}

export interface SmsProofResponse {
  smsProof: string | null
  purpose: SmsPurpose
  expiresAt: string
}

export interface AccountState {
  status: string
  disabledReason: string | null
  disabledAt: string | null
}

export interface SliderCaptchaChallenge {
  id: string
  backgroundImage: string
  pieceImage: string
  pieceY: number
  imageWidth: number
  imageHeight: number
  pieceSize: number
  expiresAt: string
}

export interface SliderCaptchaProof {
  captchaProof: string
  expiresAt: string
}

export interface RegisterRequest {
  email: string | null
  password: string
  displayName: string
  phone: string
  challengeId: string
  verificationCode: string
}

export interface ModelPermission {
  modelCode: string
  modelTokenQuota: number | null
  capabilities: string[]
}

/**
 * 套餐额度的计量单位。
 *
 * `TOKEN` 套餐按 token 计量、显示积分。`VIDEO_SECOND` 与 `AUDIO_SECOND`
 * 套餐按**秒数**计量（生成视频的秒数 / 处理的音频秒数），因此这类套餐的
 * 所有额度文案与按模型额度都必须按秒解读——底层的 `totalTokenQuota` 列
 * 承载的是量纲，而不是 token 专用，这也是列名不变的原因。
 *
 * `IMAGE` 按**张**计量。它出现在这里的谓词中是因为控制台至今把「按秒」
 * 与「按张」都当作「非 token 的单位量」，显示时都用秒/数量的措辞；
 * 图片维度的精确文案由 `quotaUnitLabel` 之外的调用点各自处理。
 */
export type UsageDimension = 'TOKEN' | 'VIDEO_SECOND' | 'IMAGE' | 'AUDIO_SECOND'

/**
 * 该套餐的额度是否是**非 token 的单位量**（秒数或张数）。
 *
 * 这个谓词的假分支等于「按积分（token）解读」，而那个解读会算出完全错误的
 * 数字。因此它必须覆盖每一个按单位计费的维度：对它返回 false 的
 * `AUDIO_SECOND` 套餐，会把 `totalTokenQuota`（其实是秒数）当成 token 数量
 * 格式化并显示为「积分」——而 10 秒会显示成 10 积分。
 *
 * 这与后端的同一条谓词是同一个判据，两边必须同步：
 * `PackageRateLimits.isSecondRate`（网关，认 VIDEO_SECOND / IMAGE / AUDIO_SECOND）
 * 与 `PackageSecondRateLimitService` 的每分钟速率解读。
 */
export const isSecondDimension = (dimension: UsageDimension | undefined): boolean =>
  dimension === 'VIDEO_SECOND'
  || dimension === 'IMAGE'
  || dimension === 'AUDIO_SECOND'

/** 套餐额度应当使用的单位词。 */
export const quotaUnitLabel = (dimension: UsageDimension | undefined): string =>
  isSecondDimension(dimension) ? '秒' : '积分'

export interface PackagePlan {
  code: string
  name: string
  description: string | null
  kind: 'STANDARD' | 'FREE_CLAIM'
  displayOrder: number
  versionId: string
  version: number
  priceAmount: number
  currency: string
  totalTokenQuota: number
  usageDimension: UsageDimension
  /**
   * 方案 C 的第二份额度：音频秒（ASR/TTS/唇形同步）。null 表示这个版本不卖语音。
   *
   * 它与 `totalTokenQuota` 是**两份独立**的额度，单位都是秒，却属于不同的产品：
   * 视频套餐里的音频别名扣的是这一份，绝不挪用视频秒。因此页面必须把它
   * 单独渲染、并且**永远不把两者相加**。
   */
  audioSecondsQuota?: number | null
  durationDays: number
  maxApiKeys: number
  requestsPerMinute: number
  tokensPerMinute: number
  maxConcurrency: number
  dailyRequestLimit: number | null
  maxOutputTokens: number | null
  maxFileSizeBytes: number
  oncePerUser: boolean
  stockTotal: number | null
  stockSold: number
  stockReserved: number
  lowStockThreshold: number
  maxPurchasesPerUser: number | null
  remainingStock: number | null
  soldOut: boolean
  lowStock: boolean
  estimatedCostAmount: number | null
  estimatedMarginRatio: number | null
  marginWarning: boolean
  models: ModelPermission[]
}

export type OrderStatus = 'PENDING' | 'PAID' | 'CLOSED' | 'REFUNDED' | 'PARTIALLY_REFUNDED'
export type PaymentProvider = 'MOCK' | 'WECHAT_PAY' | 'ALIPAY'
export type OrderPaymentProvider = PaymentProvider | 'UNSELECTED'
export type SettlementMethod = 'UNSELECTED' | 'EXTERNAL' | 'BALANCE'
export type PaymentProduct = 'MOCK' | 'WECHAT_NATIVE' | 'WECHAT_H5' | 'ALIPAY_PAGE' | 'ALIPAY_WAP'
export type PaymentAttemptStatus = 'CREATED' | 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'CLOSED'
export type GrantStatus = 'QUEUED' | 'ACTIVE' | 'FAILED' | 'REVOKED'
export type RefundStatus =
  | 'PREPARING'
  | 'PENDING_APPROVAL'
  | 'REJECTED'
  | 'CANCELED'
  | 'SUBMITTING'
  | 'PROCESSING'
  | 'REVOKING'
  | 'COMPLETED'
  | 'FAILED'
export type RefundType = 'FULL' | 'PARTIAL'

export type PaymentAction =
  | { type: 'NONE'; expiresAt: string }
  | { type: 'QR_CODE'; qrContent: string; expiresAt: string }
  | { type: 'REDIRECT_URL'; targetUrl: string; expiresAt: string }
  | { type: 'HTML_FORM'; targetUrl: string; fields: Record<string, string>; expiresAt: string }

export interface PackageSnapshotModel {
  readonly modelCode: string
  readonly modelTokenQuota: number | null
  readonly capabilities: readonly string[]
}

export interface PackageSnapshot {
  readonly packageVersionId: string
  readonly packageCode: string
  readonly packageName: string
  readonly priceAmount: number
  readonly currency: string
  readonly totalTokenQuota: number
  /** totalTokenQuota 与各模型额度的单位。 */
  readonly usageDimension?: UsageDimension
  /**
   * 方案 C 的第二份额度（音频秒），随订单快照从版本一路带到网关实例。
   * 与 {@link PackagePlan.audioSecondsQuota} 同义：null 表示这个版本不卖语音，
   * 展示时必须整块不渲染，而不是显示 0 秒。
   */
  readonly audioSecondsQuota?: number | null
  readonly durationDays: number
  readonly maxApiKeys: number
  readonly requestsPerMinute: number
  readonly tokensPerMinute: number
  readonly maxConcurrency: number
  readonly dailyRequestLimit: number | null
  readonly maxOutputTokens: number | null
  readonly maxFileSizeBytes: number
  readonly oncePerUser: boolean
  readonly models: readonly PackageSnapshotModel[]
}

export interface OrderView {
  id: string
  orderNo: string
  packageCode: string
  packageName: string
  amount: number
  currency: string
  status: OrderStatus
  settlementMethod?: SettlementMethod
  paymentProvider: OrderPaymentProvider
  paymentStatus: PaymentAttemptStatus | null
  refundStatus: RefundStatus | null
  paidAt: string | null
  closedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface CreatedOrder {
  orderNo: string
  status: OrderStatus
  amount: number
  currency: string
  expiresAt: string
  packageSnapshot: PackageSnapshot
}

export interface PaymentAttemptResult {
  transactionNo: string
  status: PaymentAttemptStatus
  action: PaymentAction
  expiresAt: string
}

export interface PaymentAttemptView {
  transactionNo: string
  provider: PaymentProvider
  product: PaymentProduct | null
  status: PaymentAttemptStatus
  providerStatus: string | null
  providerErrorCode: string | null
  action: PaymentAction | null
  paidAt: string | null
  createdAt: string
}

export interface GrantView {
  grantRequestId: string
  status: GrantStatus
  updatedAt: string
}

export interface OrderRefundSummary {
  refundNo: string
  status: RefundStatus
  refundType: RefundType | null
  amount: number
  requestedAt: string
  reviewedAt: string | null
  completedAt: string | null
}

export interface OrderDetail {
  order: OrderView
  packageSnapshot: PackageSnapshot
  expiresAt: string
  paymentAttempts: PaymentAttemptView[]
  grant: GrantView | null
  refund: OrderRefundSummary | null
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
  refundType: RefundType
  rounding: string
  policyVersion: number
  calculatedAt: string
  packageInstanceId: string
  originalPackageStatus: string
  packageExpiresAt: string | null
  gatewaySnapshotAt: string
}

export interface RefundDetail {
  refundNo: string
  orderNo: string
  status: RefundStatus
  refundType: RefundType | null
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

export interface FreePlanClaim {
  id: string
  eventId: string
  businessNo: string
  status: GrantStatus
}

export interface FreePlanClaimStatus {
  claimed: boolean
}

export interface CloseOrderResult {
  orderNo: string
  orderStatus: OrderStatus
  paymentStatus: PaymentAttemptStatus | null
  retryScheduled: boolean
}

export interface PlatformPage<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface SessionView {
  id: string
  status: string
  createdAt: string
  expiresAt: string
  revokedAt: string | null
}

export interface ProfileView extends SessionUser {
  userNo: string
  status: string
  lastLoginAt: string | null
  createdAt: string
  maskedPhone: string | null
  phoneVerified: boolean
  phoneState: string
  smsNotificationsEnabled: boolean
  passwordSet: boolean
  avatarUrl: string | null
  avatarUpdatedAt: string | null
  maxLoginDevices: number
  activeDeviceCount: number
  sessions: SessionView[]
}

export interface EmailChallenge {
  id: string
  maskedEmail: string
  expiresAt: string
}

export async function login(identifier: string, password: string): Promise<LoginResult> {
  const response = await platformHttp.post<LoginResult>('/console/platform/auth/login', {
    identifier,
    password,
  }, { headers: { 'X-Device-Recovery': 'true' } })
  return response.data
}

export async function register(request: RegisterRequest): Promise<LoginResult> {
  const response = await platformHttp.post<LoginResult>('/console/platform/auth/register', request)
  return response.data
}

export type LegalDocumentKind = 'TERMS' | 'PRIVACY'

export interface LegalDocumentSection {
  heading: string
  paragraphs: string[]
  items?: string[]
}

export interface LegalDocumentContent {
  placeholder?: string
  sections: LegalDocumentSection[]
}

export interface PublishedLegalDocument {
  kind: LegalDocumentKind
  version: string
  title: string
  content: LegalDocumentContent
}

/**
 * 已发布的服务条款／隐私政策。无需登录即可读取，因为登录页在认证前就会展示它；
 * 当该部署尚未发布任何文档时拒绝请求。
 */
export async function getPublishedLegalDocument(
  kind: LegalDocumentKind,
): Promise<PublishedLegalDocument> {
  const response = await platformHttp.get<PublishedLegalDocument>(`/console/legal/${kind}`)
  return response.data
}

export async function requestSliderCaptcha(): Promise<SliderCaptchaChallenge> {
  const response = await platformHttp.post<SliderCaptchaChallenge>(
    '/console/platform/auth/slider-captcha/challenges',
  )
  return response.data
}

export async function verifySliderCaptcha(
  challengeId: string,
  offsetX: number,
): Promise<SliderCaptchaProof> {
  const response = await platformHttp.post<SliderCaptchaProof>(
    `/console/platform/auth/slider-captcha/challenges/${encodeURIComponent(challengeId)}/verify`,
    { offsetX },
  )
  return response.data
}

export async function requestLoginSms(phone: string, captchaProof: string): Promise<SmsChallenge> {
  const response = await platformHttp.post<SmsChallenge>('/console/platform/auth/sms-login/challenges', {
    phone,
    captchaProof,
  })
  return response.data
}

export async function smsLogin(challengeId: string, code: string): Promise<LoginResult> {
  const response = await platformHttp.post<LoginResult>('/console/platform/auth/sms-login', {
    challengeId,
    code,
  }, { headers: { 'X-Device-Recovery': 'true' } })
  return response.data
}

export async function requestIdentitySms(phone: string, captchaProof: string): Promise<SmsChallenge> {
  try {
    return await requestLoginSms(phone, captchaProof)
  } catch (error) {
    const code = (error as { response?: { data?: { code?: string } } })
      .response?.data?.code
    if (code !== 'sms_login_unavailable') throw error
    return requestRegistrationSms(phone, captchaProof)
  }
}

export async function identityLogin(
  identifier: string,
  challengeId: string,
  verificationCode: string,
  termsAccepted = false,
  consentVersion?: string,
): Promise<IdentityLoginResult> {
  const response = await platformHttp.post<IdentityLoginResult>('/console/platform/auth/identity', {
    identifier,
    challengeId,
    verificationCode,
    termsAccepted,
    consentVersion,
  }, { headers: { 'X-Device-Recovery': 'true' } })
  return response.data
}

export async function completePhoneRegistration(
  challengeId: string,
  newPassword: string,
  consentVersion: string,
  invitationCode?: string,
): Promise<LoginResult> {
  const normalizedInvitationCode = normalizeInvitationCode(invitationCode)
  if (!isValidOptionalInvitationCode(normalizedInvitationCode)) {
    throw new Error('invalid_invitation_code')
  }
  const response = await platformHttp.post<LoginResult>('/console/platform/auth/identity/complete-registration', {
    challengeId,
    newPassword,
    termsAccepted: true,
    consentVersion,
    ...(normalizedInvitationCode ? { invitationCode: normalizedInvitationCode } : {}),
  })
  return response.data
}

export async function requestEmailChallenge(email: string): Promise<EmailChallenge> {
  const response = await platformHttp.post<EmailChallenge>('/console/platform/auth/email/challenges', { email })
  return response.data
}

export async function emailIdentityLogin(
  email: string,
  challengeId: string,
  verificationCode: string,
  termsAccepted = false,
  consentVersion?: string,
): Promise<EmailIdentityLoginResult> {
  const response = await platformHttp.post<EmailIdentityLoginResult>('/console/platform/auth/email-identity', {
    email,
    challengeId,
    verificationCode,
    termsAccepted,
    consentVersion,
  }, { headers: { 'X-Device-Recovery': 'true' } })
  return response.data
}

export async function completeEmailRegistration(
  challengeId: string,
  newPassword: string,
  consentVersion: string,
  invitationCode?: string,
): Promise<LoginResult> {
  const normalizedInvitationCode = normalizeInvitationCode(invitationCode)
  if (!isValidOptionalInvitationCode(normalizedInvitationCode)) {
    throw new Error('invalid_invitation_code')
  }
  const response = await platformHttp.post<LoginResult>(
    '/console/platform/auth/email-identity/complete-registration',
    {
      challengeId,
      newPassword,
      termsAccepted: true,
      consentVersion,
      ...(normalizedInvitationCode ? { invitationCode: normalizedInvitationCode } : {}),
    },
  )
  return response.data
}

export async function getAccountState(): Promise<AccountState> {
  return (await platformHttp.get<AccountState>('/console/platform/auth/account-state')).data
}

export async function heartbeatTerminalSession(headers?: Record<string, string>): Promise<AccountState> {
  return (await platformHttp.post<AccountState>('/console/platform/auth/terminal-presence', null, { headers })).data
}

export function requestTerminalClose(headers: Record<string, string>): void {
  const csrf = readCookie('yzh_csrf')
  if (!csrf) return
  void fetch('/console/platform/auth/browser-close', {
    method: 'POST',
    credentials: 'include',
    keepalive: true,
    headers: {
      Accept: 'application/json',
      'X-CSRF-Token': csrf,
      'X-Device-Id': getDeviceId(),
      ...headers,
    },
  }).catch(() => undefined)
}

export async function getPendingLoginHandoff(): Promise<PendingLoginApproval | null> {
  const response = await platformHttp.get<PendingLoginApproval>(
    '/console/platform/auth/login-handoffs/pending',
    { validateStatus: (status) => status === 200 || status === 204 },
  )
  return response.status === 204 ? null : response.data
}

export async function approveLoginHandoff(requestId: string): Promise<void> {
  await platformHttp.post(`/console/platform/auth/login-handoffs/${encodeURIComponent(requestId)}/approve`)
}

export async function denyLoginHandoff(requestId: string): Promise<void> {
  await platformHttp.post(`/console/platform/auth/login-handoffs/${encodeURIComponent(requestId)}/deny`)
}

export async function getLoginHandoffStatus(requestId: string): Promise<LoginHandoffStatusView> {
  const response = await platformHttp.get<LoginHandoffStatusView>(
    `/console/platform/auth/login-handoffs/${encodeURIComponent(requestId)}`,
  )
  return response.data
}

export async function completeLoginHandoff(requestId: string): Promise<SessionResponse> {
  const csrf = readCookie('yzh_login_handoff_csrf')
  const response = await platformHttp.post<SessionResponse>(
    `/console/platform/auth/login-handoffs/${encodeURIComponent(requestId)}/complete`,
    null,
    { headers: { 'X-CSRF-Token': csrf || '' } },
  )
  return response.data
}

export async function cancelLoginHandoff(requestId: string): Promise<void> {
  const csrf = readCookie('yzh_login_handoff_csrf')
  await platformHttp.post(
    `/console/platform/auth/login-handoffs/${encodeURIComponent(requestId)}/cancel`,
    null,
    { headers: { 'X-CSRF-Token': csrf || '' } },
  )
}

export async function requestRegistrationSms(phone: string, captchaProof: string): Promise<SmsChallenge> {
  const response = await platformHttp.post<SmsChallenge>('/console/platform/auth/sms/challenges', {
    purpose: 'REGISTER',
    phone,
    captchaProof,
  })
  return response.data
}

export async function requestSmsChallenge(purpose: SensitiveSmsPurpose): Promise<SmsChallenge> {
  const response = await platformHttp.post<SmsChallenge>('/console/platform/sms/challenges', { purpose })
  return response.data
}

export async function verifySmsChallenge(challengeId: string, code: string): Promise<SmsProofResponse> {
  const response = await platformHttp.post<SmsProofResponse>(
    `/console/platform/sms/challenges/${encodeURIComponent(challengeId)}/verify`,
    { code },
  )
  return response.data
}

export async function listPlans(): Promise<PackagePlan[]> {
  const response = await platformHttp.get<PackagePlan[]>('/console/platform/plans')
  return response.data
}

/** 套餐限流的一个时间窗口。`limit === null` 表示不限量。 */
export interface PackageLimitWindow {
  limit: number | null
  used: number
}

/** 网关为套餐限额记录的两种拒绝原因。 */
export interface PackageLimitBlockedReasons {
  daily_request_limit_exceeded?: number | null
  tokens_per_minute_exceeded?: number | null
}

/**
 * 调用方持有的某个套餐实例的实际限流用量。
 *
 * `usageDimension` 决定 `perMinute` 该怎么读：VIDEO_SECOND 套餐
 * 按每秒数计（SPM），TOKEN 套餐按每分钟积分计（TPM）。
 * 除 id 与量纲外全部可选，这样灰度未完成的部署
 * 会退化为「—」，而不是凭空编造一个 0。
 */
export interface PackageLimitUsage {
  packageInstanceId: string
  usageDimension: UsageDimension
  perMinute?: PackageLimitWindow | null
  daily?: PackageLimitWindow | null
  blockedLast24h?: number | null
  blockedLast24HoursByReason?: PackageLimitBlockedReasons | null
}

/**
 * 用户所持套餐的限流生效情况。平台服务在无法访问网关时
 * 会返回 503，调用方必须渲染为
 * 「用量暂时不可用」，而不是渲染成 0。
 */
export async function getPackageLimitUsage(packageInstanceId: string): Promise<PackageLimitUsage> {
  const response = await platformHttp.get<PackageLimitUsage>(
    `/console/platform/packages/${encodeURIComponent(packageInstanceId)}/limit-usage`,
  )
  return response.data
}

export async function listOrders(page = 0, size = 20, status = ''): Promise<PlatformPage<OrderView>> {
  const response = await platformHttp.get<PlatformPage<OrderView>>('/console/platform/orders', {
    params: { page, size, status: status || undefined },
  })
  return response.data
}

export async function claimFreePlan(): Promise<FreePlanClaim> {
  const response = await platformHttp.post<FreePlanClaim>('/console/platform/plans/free/claim')
  return response.data
}

export async function getFreePlanClaimStatus(): Promise<FreePlanClaimStatus> {
  const response = await platformHttp.get<FreePlanClaimStatus>('/console/platform/plans/free/claim-status')
  return response.data
}

export async function createOrder(packageVersionId: string, idempotencyKey: string): Promise<CreatedOrder> {
  const response = await platformHttp.post<CreatedOrder>(
    '/console/platform/orders',
    { packageVersionId },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return response.data
}

export async function getOrderDetail(orderNo: string): Promise<OrderDetail> {
  const response = await platformHttp.get<OrderDetail>(`/console/platform/orders/${encodeURIComponent(orderNo)}`)
  return response.data
}

export async function closeOrder(orderNo: string): Promise<CloseOrderResult> {
  const response = await platformHttp.post<CloseOrderResult>(`/console/platform/orders/${encodeURIComponent(orderNo)}/close`)
  return response.data
}

export async function createPaymentAttempt(
  orderNo: string,
  provider: PaymentProvider,
  product: PaymentProduct,
  smsProof?: string,
): Promise<PaymentAttemptResult> {
  const response = await platformHttp.post<PaymentAttemptResult>(
    `/console/platform/orders/${encodeURIComponent(orderNo)}/payment-attempts`,
    { provider, product, ...(smsProof ? { smsProof } : {}) },
  )
  return response.data
}

export async function requestRefund(orderNo: string, reason: string): Promise<RefundDetail> {
  const response = await platformHttp.post<RefundDetail>(
    `/console/platform/orders/${encodeURIComponent(orderNo)}/refunds`,
    { reason },
  )
  return response.data
}

export async function getRefund(refundNo: string): Promise<RefundDetail> {
  const response = await platformHttp.get<RefundDetail>(`/console/platform/refunds/${encodeURIComponent(refundNo)}`)
  return response.data
}

export async function cancelRefund(refundNo: string): Promise<RefundDetail> {
  const response = await platformHttp.post<RefundDetail>(`/console/platform/refunds/${encodeURIComponent(refundNo)}/cancel`)
  return response.data
}

export async function getProfile(): Promise<ProfileView> {
  const response = await platformHttp.get<ProfileView>('/console/platform/profile')
  return response.data
}

export async function requestPasswordResetSms(
  phone: string,
  captchaProof: string,
): Promise<SmsChallenge> {
  const response = await platformHttp.post<SmsChallenge>(
    '/console/platform/auth/password-reset/sms/challenges',
    { phone, captchaProof },
  )
  return response.data
}

export async function resetPassword(
  identifier: string,
  challengeId: string,
  verificationCode: string,
  newPassword: string,
): Promise<void> {
  await platformHttp.post('/console/platform/auth/password-reset', {
    identifier,
    challengeId,
    verificationCode,
    newPassword,
  })
}

export async function uploadAvatar(file: File): Promise<void> {
  const form = new FormData()
  form.append('file', file)
  await platformHttp.post('/console/platform/profile/avatar', form)
}

export async function getProfileAvatar(): Promise<Blob> {
  const response = await platformHttp.get<Blob>('/console/platform/profile/avatar', {
    responseType: 'blob',
  })
  return response.data
}

export async function requestPhoneChallenge(purpose: PhoneSmsPurpose, phone: string): Promise<SmsChallenge> {
  const response = await platformHttp.post<SmsChallenge>('/console/platform/profile/phone/challenges', {
    purpose,
    phone,
  })
  return response.data
}

export async function bindPhone(
  challengeId: string,
  purpose: PhoneSmsPurpose,
  currentPhoneProof?: string,
): Promise<void> {
  await platformHttp.put('/console/platform/profile/phone', {
    challengeId,
    purpose,
    ...(currentPhoneProof ? { currentPhoneProof } : {}),
  })
}

export async function bindEmail(
  email: string,
  challengeId: string,
  verificationCode: string,
  currentChallengeId?: string,
  currentVerificationCode?: string,
): Promise<void> {
  await platformHttp.put('/console/platform/profile/email', {
    email,
    challengeId,
    verificationCode,
    ...(currentChallengeId ? { currentChallengeId, currentVerificationCode } : {}),
  })
}

export async function updateSmsPreference(enabled: boolean): Promise<void> {
  await platformHttp.patch('/console/platform/profile/sms-preference', { enabled })
}

export async function updateDisplayName(displayName: string): Promise<{ displayName: string }> {
  const response = await platformHttp.patch<{ displayName: string }>(
    '/console/platform/profile/display-name',
    { displayName },
  )
  return response.data
}

export async function updateLoginDeviceLimit(maxLoginDevices: number): Promise<{ maxLoginDevices: number }> {
  const response = await platformHttp.patch<{ maxLoginDevices: number }>(
    '/console/platform/profile/device-limit',
    { maxLoginDevices },
  )
  return response.data
}

export async function changePassword(
  currentPassword: string | null,
  newPassword: string,
  smsProof?: string,
  invitationCode?: string,
): Promise<SessionResponse | null> {
  const normalizedInvitationCode = normalizeInvitationCode(invitationCode)
  if (!isValidOptionalInvitationCode(normalizedInvitationCode)) {
    throw new Error('invalid_invitation_code')
  }
  const response = await platformHttp.post<SessionResponse | null>('/console/platform/profile/password', {
    currentPassword,
    newPassword,
    ...(smsProof ? { smsProof } : {}),
    ...(normalizedInvitationCode ? { invitationCode: normalizedInvitationCode } : {}),
  })
  return response.data
}

export async function logout(): Promise<void> {
  const csrfToken = readCookie('yzh_csrf')
  await platformHttp.post('/console/platform/auth/logout', null, {
    headers: csrfToken ? { 'X-CSRF-Token': csrfToken } : undefined,
  })
}

export interface DesktopAuthorizationCompletion {
  redirectUrl: string
  scopes: string[]
  expiresAt: string
}

export interface DesktopAuthorizationCancellation {
  redirectUrl?: string
}

/**
 * 完成桌面客户端（yuzhh-desktop）的 OAuth 授权。
 * 调用方拿到 redirectUrl 后必须做浏览器顶层跳转，不要用 fetch/XHR 跟随。
 */
export async function completeOAuthAuthorization(
  transactionId: string,
): Promise<DesktopAuthorizationCompletion> {
  const response = await platformHttp.post<DesktopAuthorizationCompletion>(
    '/console/platform/auth/authorize/complete',
    { transactionId },
  )
  return response.data
}

/**
 * 用户主动放弃桌面端授权。后端按契约直接 302 回桌面客户端回调地址，
 * 因此这里对 200/204 都视为成功，不解析具体响应体。
 */
/**
 * 用户主动放弃桌面端授权。拿到 OAuth 错误回调地址后仍由浏览器做顶层跳转，
 * 避免 XHR 跨源跟随本机 loopback 地址时被浏览器拦截。
 */
export async function cancelOAuthAuthorization(
  transactionId: string,
): Promise<DesktopAuthorizationCancellation | null> {
  const response = await platformHttp.post<DesktopAuthorizationCancellation>(
    '/console/platform/auth/authorize/cancel',
    { transactionId },
  )
  return response.data
}
