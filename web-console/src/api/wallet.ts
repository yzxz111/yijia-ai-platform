import { platformHttp } from './http'
import type {
  PaymentAction,
  PaymentAttemptStatus,
  PaymentProduct,
  PaymentProvider,
} from './platform'

export type Currency = 'CNY'
export type SettlementMethod = 'UNSELECTED' | 'EXTERNAL' | 'BALANCE'
export type WalletSortDirection = 'asc' | 'desc'
export type WalletLedgerEntryType = 'RECHARGE' | 'PACKAGE_PURCHASE' | 'PACKAGE_REFUND' | 'MODEL_USAGE'
export type RechargeStatus = 'PENDING' | 'PAID' | 'CLOSED' | 'FAILED'

export interface WalletSummary {
  availableAmount: number
  currency: 'CNY'
  updatedAt: string
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface WalletPageParams {
  page?: number | null
  size?: number | null
  sort?: string | null
  direction?: WalletSortDirection | null
}

export interface WalletLedgerEntry {
  entryNo: string
  entryType: WalletLedgerEntryType
  amountDelta: number
  balanceAfter: number
  businessNo: string
  description: string
  createdAt: string
}

export interface RechargeOption {
  id: string
  amount: number
  displayOrder: number
}

export interface RechargeOptionCatalog {
  options: RechargeOption[]
  custom: {
    minimumAmount: number
    maximumAmount: number
  }
  currency: 'CNY'
}

export interface RechargeOrder {
  rechargeNo: string
  optionId: string | null
  paidAmount: number
  bonusAmount: number
  creditedAmount: number
  currency: 'CNY'
  status: RechargeStatus
  expiresAt: string
  paidAt: string | null
  closedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateRechargeRequest {
  amount: number
  optionId?: string | null
}

export interface CreateRechargePaymentAttemptRequest {
  provider: PaymentProvider
  product: PaymentProduct
  smsProof?: string
}

export interface RechargePaymentAttemptResult {
  transactionNo: string
  status: PaymentAttemptStatus
  action: PaymentAction
  expiresAt: string
}

export interface BalancePaymentResult {
  orderNo: string
  orderStatus: string
  settlementMethod: SettlementMethod
  balanceBefore: number
  balanceAfter: number
  grantRequestId: string
  grantStatus: string
}

export interface AdminWalletPageParams extends WalletPageParams {
  query?: string | null
  from?: string | null
  to?: string | null
}

export interface AdminWalletAccountParams extends AdminWalletPageParams {
  status?: 'ACTIVE' | 'DISABLED' | '' | null
}

export interface AdminWalletLedgerParams extends AdminWalletPageParams {
  entryType?: WalletLedgerEntryType | '' | null
}

export interface AdminWalletRechargeParams extends AdminWalletPageParams {
  status?: RechargeStatus | '' | null
}

export interface AdminWalletAccount {
  id: string
  userId: string
  userNo?: string | null
  email: string | null
  displayName: string
  currency: 'CNY'
  availableAmount: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface AdminWalletLedgerEntry extends WalletLedgerEntry {
  userId: string
  userNo?: string | null
  email: string | null
  displayName: string
}

export interface AdminWalletRecharge extends RechargeOrder {
  userId: string
  userNo?: string | null
  email: string | null
  displayName: string
}

export interface AdminRechargeOption {
  id: string
  amount: number
  enabled: boolean
  displayOrder: number
  createdAt: string
  updatedAt: string
}

export interface AdminRechargeOptionReplacement {
  amount: number
  enabled: boolean
  displayOrder: number
}

type QueryValue = string | number | null | undefined

function queryParams(entries: Array<[string, QueryValue]>): URLSearchParams {
  const params = new URLSearchParams()
  for (const [name, value] of entries) {
    if (value !== null && value !== undefined && value !== '') {
      params.set(name, String(value))
    }
  }
  return params
}

function pageParams(params: WalletPageParams = {}): URLSearchParams {
  return queryParams([
    ['page', params.page],
    ['size', params.size],
    ['sort', params.sort],
    ['direction', params.direction],
  ])
}

export async function getWalletSummary(): Promise<WalletSummary> {
  return (await platformHttp.get<WalletSummary>('/console/platform/wallet')).data
}

export async function listWalletLedger(
  params: WalletPageParams = {},
): Promise<PageResult<WalletLedgerEntry>> {
  const response = await platformHttp.get<PageResult<WalletLedgerEntry>>(
    '/console/platform/wallet/ledger',
    { params: pageParams(params) },
  )
  return response.data
}

export async function getRechargeOptions(): Promise<RechargeOptionCatalog> {
  return (await platformHttp.get<RechargeOptionCatalog>(
    '/console/platform/wallet/recharge-options',
  )).data
}

export async function listRecharges(
  params: WalletPageParams = {},
): Promise<PageResult<RechargeOrder>> {
  const response = await platformHttp.get<PageResult<RechargeOrder>>(
    '/console/platform/wallet/recharges',
    { params: pageParams(params) },
  )
  return response.data
}

export async function createRecharge(
  request: CreateRechargeRequest,
  idempotencyKey: string,
): Promise<RechargeOrder> {
  const response = await platformHttp.post<RechargeOrder>(
    '/console/platform/wallet/recharges',
    request,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return response.data
}

export async function getRechargeDetail(rechargeNo: string): Promise<RechargeOrder> {
  return (await platformHttp.get<RechargeOrder>(
    `/console/platform/wallet/recharges/${encodeURIComponent(rechargeNo)}`,
  )).data
}

export async function closeRecharge(rechargeNo: string): Promise<RechargeOrder> {
  return (await platformHttp.post<RechargeOrder>(
    `/console/platform/wallet/recharges/${encodeURIComponent(rechargeNo)}/close`,
  )).data
}

export async function createRechargePaymentAttempt(
  rechargeNo: string,
  request: CreateRechargePaymentAttemptRequest,
): Promise<RechargePaymentAttemptResult> {
  const response = await platformHttp.post<RechargePaymentAttemptResult>(
    `/console/platform/wallet/recharges/${encodeURIComponent(rechargeNo)}/payment-attempts`,
    request,
  )
  return response.data
}

export async function payOrderWithBalance(
  orderNo: string,
  idempotencyKey: string,
): Promise<BalancePaymentResult> {
  const response = await platformHttp.post<BalancePaymentResult>(
    `/console/platform/orders/${encodeURIComponent(orderNo)}/balance-payment`,
    undefined,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return response.data
}

export interface AdminWalletOverview {
  accounts: number
  totalBalance: number
  todayRecharge: number
  todayDebit: number
  timezone: string
  trend: Array<{ date: string; recharge: number; debit: number }>
}

export async function getAdminWalletOverview(): Promise<AdminWalletOverview> {
  return (await platformHttp.get<AdminWalletOverview>('/console/platform/admin/wallet/overview')).data
}

export async function listAdminWalletAccounts(
  params: AdminWalletAccountParams = {},
): Promise<PageResult<AdminWalletAccount>> {
  const response = await platformHttp.get<PageResult<AdminWalletAccount>>(
    '/console/platform/admin/wallet/accounts',
    { params: queryParams([
      ['page', params.page],
      ['size', params.size],
      ['query', params.query],
      ['status', params.status],
      ['from', params.from],
      ['to', params.to],
      ['sort', params.sort],
      ['direction', params.direction],
    ]) },
  )
  return response.data
}

export async function listAdminWalletLedger(
  params: AdminWalletLedgerParams = {},
): Promise<PageResult<AdminWalletLedgerEntry>> {
  const response = await platformHttp.get<PageResult<AdminWalletLedgerEntry>>(
    '/console/platform/admin/wallet/ledger',
    { params: queryParams([
      ['page', params.page],
      ['size', params.size],
      ['query', params.query],
      ['entryType', params.entryType],
      ['from', params.from],
      ['to', params.to],
      ['sort', params.sort],
      ['direction', params.direction],
    ]) },
  )
  return response.data
}

export async function listAdminWalletRecharges(
  params: AdminWalletRechargeParams = {},
): Promise<PageResult<AdminWalletRecharge>> {
  const response = await platformHttp.get<PageResult<AdminWalletRecharge>>(
    '/console/platform/admin/wallet/recharges',
    { params: queryParams([
      ['page', params.page],
      ['size', params.size],
      ['query', params.query],
      ['status', params.status],
      ['from', params.from],
      ['to', params.to],
      ['sort', params.sort],
      ['direction', params.direction],
    ]) },
  )
  return response.data
}

export async function listAdminWalletRechargeOptions(): Promise<AdminRechargeOption[]> {
  return (await platformHttp.get<AdminRechargeOption[]>(
    '/console/platform/admin/wallet/recharge-options',
  )).data
}

export async function replaceAdminWalletRechargeOptions(
  options: AdminRechargeOptionReplacement[],
): Promise<AdminRechargeOption[]> {
  return (await platformHttp.put<AdminRechargeOption[]>(
    '/console/platform/admin/wallet/recharge-options',
    { options },
  )).data
}
