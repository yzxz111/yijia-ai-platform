<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowDownRight, ArrowUpRight, Eye, Landmark, LockKeyhole, Plus, RefreshCw, Save, Search, ShieldAlert, Trash2, Users } from 'lucide-vue-next'

import { listAudit, type AuditEntry } from '../../api/admin'
import {
  getAdminWalletOverview,
  listAdminWalletAccounts,
  listAdminWalletLedger,
  listAdminWalletRecharges,
  listAdminWalletRechargeOptions,
  replaceAdminWalletRechargeOptions,
  type AdminRechargeOption,
  type AdminWalletAccount,
  type AdminWalletLedgerEntry,
  type AdminWalletRecharge,
  type AdminWalletOverview,
  type RechargeStatus,
  type WalletLedgerEntryType,
} from '../../api/wallet'
import AppChart from '../../components/charts/AppChart.vue'
import { adminDrillQuery } from '../../router/query'
import { formatDateTime } from '../../utils/dateTime'
import { adminStatusLabel, userNameLabel, userNoLabel } from '../../utils/adminDisplay'

type Tab = 'accounts' | 'recharges' | 'ledger' | 'options'
type OptionRow = { amount: string; enabled: boolean; displayOrder: number }

const route = useRoute()
const walletTabs: readonly Tab[] = ['accounts', 'recharges', 'ledger', 'options']
const initialDrill = adminDrillQuery(route.query, walletTabs, 'accounts')
const activeTab = ref<Tab>(initialDrill.tab as Tab)
const accounts = ref<AdminWalletAccount[]>([])
const recharges = ref<AdminWalletRecharge[]>([])
const ledger = ref<AdminWalletLedgerEntry[]>([])
const optionRows = ref<OptionRow[]>([])
const accountsTotal = ref(0)
const rechargesTotal = ref(0)
const ledgerTotal = ref(0)
const accountsPage = ref(0)
const rechargesPage = ref(0)
const ledgerPage = ref(0)
const pageSizeOptions = [5, 10, 20]
const accountsSize = ref(20)
const rechargesSize = ref(20)
const ledgerSize = ref(20)

const accountsFilters = reactive<{ query: string; status: 'ACTIVE' | 'DISABLED' | ''; from: string; to: string }>({ query: initialDrill.userId || initialDrill.search, status: '', from: '', to: '' })
const rechargesFilters = reactive<{ query: string; status: RechargeStatus | ''; from: string; to: string }>({ query: initialDrill.search, status: '', from: '', to: '' })
const ledgerFilters = reactive<{ query: string; entryType: WalletLedgerEntryType | ''; from: string; to: string }>({ query: initialDrill.search, entryType: '', from: '', to: '' })
const loading = reactive({ accounts: false, recharges: false, ledger: false, options: false })
const errors = reactive({ accounts: '', recharges: '', ledger: '', options: '', overview: '' })
const loaded = reactive({ accounts: false, recharges: false, ledger: false, options: false })
const savingOptions = ref(false)
let accountsSequence = 0
let rechargesSequence = 0
let ledgerSequence = 0
let optionsSequence = 0

const tabs: Array<{ value: Tab; label: string }> = [
  { value: 'accounts', label: '余额账户' },
  { value: 'recharges', label: '充值订单' },
  { value: 'ledger', label: '余额明细' },
  { value: 'options', label: '充值选项' },
]

function money(minorUnits: number, currency = 'CNY') {
  const units = BigInt(minorUnits)
  const absolute = units < 0n ? -units : units
  const whole = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 }).format(absolute / 100n)
  const fraction = (absolute % 100n).toString().padStart(2, '0')
  return `${units < 0n ? '-' : ''}${currency === 'CNY' ? '¥' : `${currency} `}${whole}.${fraction}`
}

function yuan(minorUnits: number) {
  const units = BigInt(minorUnits)
  return `${units / 100n}.${(units % 100n).toString().padStart(2, '0')}`
}

function yuanToMinorUnits(value: string) {
  const match = /^(0|[1-9]\d*)(?:\.(\d{1,2}))?$/.exec(value.trim())
  if (!match) return null
  const result = BigInt(match[1]) * 100n + BigInt((match[2] || '').padEnd(2, '0') || '0')
  if (result > BigInt(Number.MAX_SAFE_INTEGER)) return null
  return Number(result)
}

async function loadAccounts(targetPage = accountsPage.value) {
  const sequence = ++accountsSequence
  loading.accounts = true
  errors.accounts = ''
  try {
    const result = await listAdminWalletAccounts({ page: targetPage, size: accountsSize.value, query: accountsFilters.query.trim(), status: accountsFilters.status, from: startOfDay(accountsFilters.from), to: endOfDay(accountsFilters.to), sort: 'updatedAt', direction: 'desc' })
    if (sequence !== accountsSequence) return
    accounts.value = result.items
    accountsTotal.value = result.total
    accountsPage.value = result.page
    loaded.accounts = true
  } catch {
    if (sequence === accountsSequence) errors.accounts = '余额账户加载失败，请稍后重试。'
  } finally {
    if (sequence === accountsSequence) loading.accounts = false
  }
}

async function loadRecharges(targetPage = rechargesPage.value) {
  const sequence = ++rechargesSequence
  loading.recharges = true
  errors.recharges = ''
  try {
    const result = await listAdminWalletRecharges({ page: targetPage, size: rechargesSize.value, query: rechargesFilters.query.trim(), status: rechargesFilters.status, from: startOfDay(rechargesFilters.from), to: endOfDay(rechargesFilters.to), sort: 'createdAt', direction: 'desc' })
    if (sequence !== rechargesSequence) return
    recharges.value = result.items
    rechargesTotal.value = result.total
    rechargesPage.value = result.page
    loaded.recharges = true
  } catch {
    if (sequence === rechargesSequence) errors.recharges = '充值订单加载失败，请稍后重试。'
  } finally {
    if (sequence === rechargesSequence) loading.recharges = false
  }
}

async function loadLedger(targetPage = ledgerPage.value) {
  const sequence = ++ledgerSequence
  loading.ledger = true
  errors.ledger = ''
  try {
    const result = await listAdminWalletLedger({ page: targetPage, size: ledgerSize.value, query: ledgerFilters.query.trim(), entryType: ledgerFilters.entryType, from: startOfDay(ledgerFilters.from), to: endOfDay(ledgerFilters.to), sort: 'createdAt', direction: 'desc' })
    if (sequence !== ledgerSequence) return
    ledger.value = result.items
    ledgerTotal.value = result.total
    ledgerPage.value = result.page
    loaded.ledger = true
  } catch {
    if (sequence === ledgerSequence) errors.ledger = '余额明细加载失败，请稍后重试。'
  } finally {
    if (sequence === ledgerSequence) loading.ledger = false
  }
}

function startOfDay(value: string) {
  return value ? new Date(`${value}T00:00:00`).toISOString() : null
}

function endOfDay(value: string) {
  return value ? new Date(`${value}T23:59:59.999`).toISOString() : null
}

function changeAccountsSize(size: number) { accountsSize.value = size; void loadAccounts(0) }
function changeRechargesSize(size: number) { rechargesSize.value = size; void loadRecharges(0) }
function changeLedgerSize(size: number) { ledgerSize.value = size; void loadLedger(0) }
function clearAccountsFilters() { Object.assign(accountsFilters, { query: '', status: '', from: '', to: '' }); void loadAccounts(0) }
function clearRechargesFilters() { Object.assign(rechargesFilters, { query: '', status: '', from: '', to: '' }); void loadRecharges(0) }
function clearLedgerFilters() { Object.assign(ledgerFilters, { query: '', entryType: '', from: '', to: '' }); void loadLedger(0) }

function toRows(options: AdminRechargeOption[]) {
  return [...options].sort((a, b) => a.displayOrder - b.displayOrder).map((option) => ({
    amount: yuan(option.amount), enabled: option.enabled, displayOrder: option.displayOrder,
  }))
}

async function loadOptions() {
  const sequence = ++optionsSequence
  loading.options = true
  errors.options = ''
  try {
    const result = await listAdminWalletRechargeOptions()
    if (sequence !== optionsSequence) return
    optionRows.value = toRows(result)
    loaded.options = true
  } catch {
    if (sequence === optionsSequence) errors.options = '充值选项加载失败，请稍后重试。'
  } finally {
    if (sequence === optionsSequence) loading.options = false
  }
}

function selectTab(tab: Tab) {
  activeTab.value = tab
  if (loaded[tab]) return
  if (tab === 'accounts') void loadAccounts()
  if (tab === 'recharges') void loadRecharges()
  if (tab === 'ledger') void loadLedger()
  if (tab === 'options') void loadOptions()
}

function addOption() {
  if (optionRows.value.length >= 20) return
  optionRows.value.push({ amount: '', enabled: true, displayOrder: optionRows.value.length + 1 })
}

async function saveOptions() {
  errors.options = ''
  if (optionRows.value.length > 20) {
    errors.options = '最多可配置 20 个充值选项。'
    return
  }
  const mapped = optionRows.value.map((row) => ({
    amount: yuanToMinorUnits(row.amount), enabled: row.enabled, displayOrder: Number(row.displayOrder),
  }))
  if (mapped.some((row) => row.amount === null || row.amount < 100 || row.amount > 5_000_000)) {
    errors.options = '充值金额须为 ¥1.00 至 ¥50,000.00，最多两位小数。'
    return
  }
  const amounts = mapped.map((row) => row.amount as number)
  const orders = mapped.map((row) => row.displayOrder)
  if (new Set(amounts).size !== amounts.length) {
    errors.options = '充值金额不能重复。'
    return
  }
  if (orders.some((order) => !Number.isInteger(order) || order < 0 || order > 2_147_483_647)) {
    errors.options = '显示顺序须在 0 至 2147483647 的非负正整数范围内。'
    return
  }
  savingOptions.value = true
  try {
    const result = await replaceAdminWalletRechargeOptions(mapped.map((row) => ({
      amount: row.amount as number, enabled: row.enabled, displayOrder: row.displayOrder,
    })))
    optionRows.value = toRows(result)
  } catch {
    errors.options = '充值选项保存失败，请稍后重试。'
  } finally {
    savingOptions.value = false
  }
}

const walletStats = ref<AdminWalletOverview | null>(null)
const recentRecharges = ref<AdminWalletRecharge[]>([])
const recentDebits = ref<AdminWalletLedgerEntry[]>([])
const recentLedger = ref<AdminWalletLedgerEntry[]>([])
const abnormalLedger = ref<AdminWalletLedgerEntry[]>([])
const selectedAccount = ref<AdminWalletAccount | null>(null)
const accountDetailOpen = ref(false)
const accountLedger = ref<AdminWalletLedgerEntry[]>([])
const accountAudit = ref<AuditEntry[]>([])

async function loadWalletStats() {
  errors.overview = ''
  try {
    walletStats.value = await getAdminWalletOverview()
    trendDays.value = walletStats.value.trend.map(row => ({
      label: row.date.slice(5).replace('-', '/'), recharge: row.recharge, debit: row.debit,
    }))
  } catch (error) {
    walletStats.value = null
    trendDays.value = []
    errors.overview = error instanceof Error ? error.message : '钱包统计加载失败，请重试'
  }
}

async function loadRecentActivity() {
  try {
    const [ledger, recharges] = await Promise.all([
      listAdminWalletLedger({ page: 0, size: 100, query: '', entryType: '', from: null, to: null, sort: 'createdAt', direction: 'desc' }),
      listAdminWalletRecharges({ page: 0, size: 5, query: '', status: 'PAID', from: null, to: null, sort: 'updatedAt', direction: 'desc' }),
    ])
    recentRecharges.value = recharges.items.slice(0, 5)
    recentDebits.value = ledger.items.filter((row) => row.entryType === 'PACKAGE_PURCHASE' || row.entryType === 'MODEL_USAGE').slice(0, 5)
    recentLedger.value = ledger.items.slice(0, 6)
    abnormalLedger.value = ledger.items.filter((row) => row.entryType === 'PACKAGE_REFUND' || row.amountDelta < 0)
  } catch {
    recentRecharges.value = []
    recentDebits.value = []
    recentLedger.value = []
    abnormalLedger.value = []
  }
}

const trendOption = computed(() => {
  const days = trendDays.value
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['充值', '扣款'], right: 0, top: 0, itemWidth: 14, textStyle: { fontSize: 11, color: '#667085' } },
    grid: { left: 8, right: 8, top: 26, bottom: 4, containLabel: true },
    xAxis: { type: 'category', data: days.map((day) => day.label), axisLine: { lineStyle: { color: '#d5dae2' } }, axisTick: { show: false }, axisLabel: { fontSize: 10, color: '#8a94a6' } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#edf0f4' } }, axisLabel: { fontSize: 10, color: '#8a94a6', formatter: (value: number) => `¥${value}` } },
    series: [
      { name: '充值', type: 'bar', stack: 'flow', barWidth: 10, itemStyle: { color: '#7ea6d8', borderRadius: [2, 2, 0, 0] }, data: days.map((day) => day.recharge / 100) },
      { name: '扣款', type: 'bar', stack: 'flow', barWidth: 10, itemStyle: { color: '#d89a94', borderRadius: [2, 2, 0, 0] }, data: days.map((day) => day.debit / 100) },
    ],
  }
})

const trendDays = ref<Array<{ label: string; recharge: number; debit: number }>>([])

async function openAccountDetail(account: AdminWalletAccount) {
  selectedAccount.value = account
  accountDetailOpen.value = true
  accountLedger.value = []
  accountAudit.value = []
  try {
    const result = await listAdminWalletLedger({ page: 0, size: 20, query: account.userNo || account.displayName, entryType: '', from: null, to: null, sort: 'createdAt', direction: 'desc' })
    accountLedger.value = result.items
  } catch {
    accountLedger.value = []
  }
  try {
    const result = await listAudit({ page: 0, size: 10, query: account.userNo || account.displayName, status: '', sort: 'createdAt', direction: 'desc' })
    accountAudit.value = result.items
  } catch {
    accountAudit.value = []
  }
}

onMounted(() => {
  selectTab(activeTab.value)
  void loadWalletStats()
  void loadRecentActivity()
})
watch(() => route.query, (query) => {
  const drill = adminDrillQuery(query, walletTabs, 'accounts')
  accountsFilters.query = drill.userId || drill.search
  rechargesFilters.query = drill.search
  ledgerFilters.query = drill.search
  loaded[drill.tab as Tab] = false
  selectTab(drill.tab as Tab)
}, { deep: true })
</script>

<template>
  <section class="wallet-admin wallet-admin-page" aria-labelledby="wallet-admin-title">
    <TopErrorMessage :message="errors.accounts" />
    <TopErrorMessage :message="errors.recharges" />
    <TopErrorMessage :message="errors.ledger" />
    <TopErrorMessage :message="errors.options" />
    <TopErrorMessage :message="errors.overview" />
    <header class="page-header">
      <div><h2 id="wallet-admin-title">钱包管理</h2><p>查询用户余额、充值与余额明细，维护固定充值选项</p></div>
    </header>

    <section class="wallet-stats" aria-label="钱包统计">
      <div class="wallet-stat">
        <span class="wallet-stat__icon wallet-stat__icon--users"><Users :size="15" /></span>
        <div><strong>{{ walletStats ? walletStats.accounts.toLocaleString() : '—' }}</strong><span>钱包账户数</span></div>
      </div>
      <div class="wallet-stat">
        <span class="wallet-stat__icon wallet-stat__icon--bank"><Landmark :size="15" /></span>
        <div><strong class="wallet-stat__balance">{{ walletStats ? money(walletStats.totalBalance) : '—' }}</strong><span>总可用余额</span></div>
      </div>
      <div class="wallet-stat">
        <span class="wallet-stat__icon wallet-stat__icon--in"><ArrowUpRight :size="15" /></span>
        <div><strong class="wallet-stat__in">{{ walletStats ? money(walletStats.todayRecharge) : '—' }}</strong><span>今日充值</span></div>
      </div>
      <div class="wallet-stat">
        <span class="wallet-stat__icon wallet-stat__icon--out"><ArrowDownRight :size="15" /></span>
        <div><strong class="wallet-stat__out">{{ walletStats ? money(walletStats.todayDebit) : '—' }}</strong><span>今日扣款</span></div>
      </div>
    </section>

    <button v-if="errors.overview" type="button" @click="loadWalletStats">重试钱包统计</button>

    <div class="wallet-layout">
      <div class="wallet-layout__main">
    <div class="tab-list" role="tablist" aria-label="钱包管理视图">
      <button v-for="tab in tabs" :id="`wallet-admin-tab-${tab.value}`" :key="tab.value" type="button" role="tab" :aria-selected="activeTab === tab.value" :aria-controls="`wallet-admin-panel-${tab.value}`" :data-test="`wallet-tab-${tab.value}`" @click="selectTab(tab.value)">{{ tab.label }}</button>
    </div>

    <section v-show="activeTab === 'accounts'" id="wallet-admin-panel-accounts" class="data-band" role="tabpanel" aria-labelledby="wallet-admin-tab-accounts" data-test="accounts-panel">
      <form class="filters" @submit.prevent="accountsPage = 0; loadAccounts(0)"><label>用户搜索<input v-model="accountsFilters.query" data-test="accounts-query" placeholder="用户编号、用户名或联系方式" /></label><label>账号状态<select v-model="accountsFilters.status"><option value="">全部状态</option><option value="ACTIVE">启用</option><option value="DISABLED">停用</option></select></label><label>开始日期<input v-model="accountsFilters.from" type="date" data-test="accounts-from" /></label><label>结束日期<input v-model="accountsFilters.to" type="date" data-test="accounts-to" /></label><button type="button" @click="clearAccountsFilters">清空</button><button type="submit" data-test="filter-accounts"><Search :size="16" />查询</button></form>
      <div v-if="errors.accounts" class="state"><button type="button" data-test="retry-accounts" @click="loadAccounts()"><RefreshCw :size="16" />重试余额账户</button></div>
      <div v-else-if="loading.accounts" class="state" aria-busy="true">正在加载余额账户...</div>
      <div v-else-if="!accounts.length" class="state" data-test="accounts-empty">暂无余额账户</div>
      <div v-else class="table-scroll"><table><thead><tr><th>用户编号</th><th>用户名</th><th>可用余额</th><th>币种</th><th>更新时间</th><th>操作</th></tr></thead><tbody><tr v-for="row in accounts" :key="row.id"><td><strong>{{ userNoLabel(row.userNo) }}</strong></td><td>{{ userNameLabel(row.displayName) }}</td><td class="wallet-balance-cell">{{ money(row.availableAmount, row.currency) }}</td><td>{{ row.currency }}</td><td>{{ formatDateTime(row.updatedAt) }}</td><td><button type="button" class="wallet-detail-btn" :data-test="`account-detail-${row.id}`" :aria-label="`查看 ${userNameLabel(row.displayName)} 钱包详情`" @click="openAccountDetail(row)"><Eye :size="15" />详情</button></td></tr></tbody></table></div>
      <el-pagination data-test="accounts-pagination" :current-page="accountsPage + 1" :page-size="accountsSize" :page-sizes="pageSizeOptions" :total="accountsTotal" layout="sizes, prev, pager, next" @size-change="changeAccountsSize" @current-change="loadAccounts($event - 1)" />
    </section>

    <section v-show="activeTab === 'recharges'" id="wallet-admin-panel-recharges" class="data-band" role="tabpanel" aria-labelledby="wallet-admin-tab-recharges" data-test="recharges-panel">
      <form class="filters" @submit.prevent="rechargesPage = 0; loadRecharges(0)"><label>用户/充值单搜索<input v-model="rechargesFilters.query" data-test="recharges-query" placeholder="编号、用户名或充值单号" /></label><label>状态<select v-model="rechargesFilters.status" data-test="recharges-status"><option value="">全部</option><option value="PENDING">待支付</option><option value="PAID">已到账</option><option value="CLOSED">已关闭</option><option value="FAILED">失败</option></select></label><label>开始日期<input v-model="rechargesFilters.from" type="date" /></label><label>结束日期<input v-model="rechargesFilters.to" type="date" /></label><button type="button" @click="clearRechargesFilters">清空</button><button type="submit" data-test="filter-recharges"><Search :size="16" />查询</button></form>
      <div v-if="errors.recharges" class="state"><button type="button" data-test="retry-recharges" @click="loadRecharges()"><RefreshCw :size="16" />重试充值订单</button></div>
      <div v-else-if="loading.recharges" class="state" aria-busy="true">正在加载充值订单...</div><div v-else-if="!recharges.length" class="state" data-test="recharges-empty">暂无充值订单</div>
      <div v-else class="table-scroll"><table><thead><tr><th>充值单号</th><th>用户编号</th><th>用户名</th><th>金额</th><th>状态</th><th>创建时间</th></tr></thead><tbody><tr v-for="row in recharges" :key="row.rechargeNo"><td><code>{{ row.rechargeNo }}</code></td><td>{{ userNoLabel(row.userNo) }}</td><td>{{ userNameLabel(row.displayName) }}</td><td>{{ money(row.paidAmount, row.currency) }}</td><td>{{ adminStatusLabel(row.status) }}</td><td>{{ formatDateTime(row.createdAt) }}</td></tr></tbody></table></div>
      <el-pagination data-test="recharges-pagination" :current-page="rechargesPage + 1" :page-size="rechargesSize" :page-sizes="pageSizeOptions" :total="rechargesTotal" layout="sizes, prev, pager, next" @size-change="changeRechargesSize" @current-change="loadRecharges($event - 1)" />
    </section>

    <section v-show="activeTab === 'ledger'" id="wallet-admin-panel-ledger" class="data-band" role="tabpanel" aria-labelledby="wallet-admin-tab-ledger" data-test="ledger-panel">
      <form class="filters" @submit.prevent="ledgerPage = 0; loadLedger(0)"><label>用户/流水搜索<input v-model="ledgerFilters.query" data-test="ledger-query" placeholder="用户、流水号或业务号" /></label><label>类型<select v-model="ledgerFilters.entryType" data-test="ledger-entry-type"><option value="">全部</option><option value="RECHARGE">充值</option><option value="PACKAGE_PURCHASE">套餐购买</option><option value="PACKAGE_REFUND">套餐退款</option></select></label><label>开始日期<input v-model="ledgerFilters.from" type="date" /></label><label>结束日期<input v-model="ledgerFilters.to" type="date" /></label><button type="button" @click="clearLedgerFilters">清空</button><button type="submit" data-test="filter-ledger"><Search :size="16" />查询</button></form>
      <div v-if="errors.ledger" class="state"><button type="button" data-test="retry-ledger" @click="loadLedger()"><RefreshCw :size="16" />重试余额明细</button></div>
      <div v-else-if="loading.ledger" class="state" aria-busy="true">正在加载余额明细...</div><div v-else-if="!ledger.length" class="state" data-test="ledger-empty">暂无余额明细</div>
      <div v-else class="table-scroll"><table><thead><tr><th>流水号</th><th>用户编号</th><th>用户名</th><th>类型</th><th>变动</th><th>变动后余额</th><th>业务号</th><th>时间</th></tr></thead><tbody><tr v-for="row in ledger" :key="row.entryNo"><td><code>{{ row.entryNo }}</code></td><td>{{ userNoLabel(row.userNo) }}</td><td>{{ userNameLabel(row.displayName) }}</td><td>{{ adminStatusLabel(row.entryType) }}</td><td>{{ money(row.amountDelta) }}</td><td>{{ money(row.balanceAfter) }}</td><td><code>{{ row.businessNo }}</code></td><td>{{ formatDateTime(row.createdAt) }}</td></tr></tbody></table></div>
      <el-pagination data-test="ledger-pagination" :current-page="ledgerPage + 1" :page-size="ledgerSize" :page-sizes="pageSizeOptions" :total="ledgerTotal" layout="sizes, prev, pager, next" @size-change="changeLedgerSize" @current-change="loadLedger($event - 1)" />
    </section>

    <section v-show="activeTab === 'options'" id="wallet-admin-panel-options" class="data-band" role="tabpanel" aria-labelledby="wallet-admin-tab-options" data-test="options-panel">
      <button v-if="errors.options && !loaded.options" type="button" data-test="retry-options" @click="loadOptions"><RefreshCw :size="16" />重试充值选项</button>
      <div class="option-actions"><button type="button" data-test="add-recharge-option" :disabled="optionRows.length >= 20" @click="addOption"><Plus :size="16" />新增选项</button><button type="button" data-test="save-recharge-options" :disabled="savingOptions" @click="saveOptions"><Save :size="16" />保存配置</button></div>
      <div v-if="loading.options" class="state" aria-busy="true">正在加载充值选项...</div><div v-else-if="loaded.options && !optionRows.length" class="state" data-test="options-empty">暂无充值选项</div>
      <div v-else-if="loaded.options" class="table-scroll"><table><thead><tr><th>金额（元）</th><th>显示顺序</th><th>启用</th><th>操作</th></tr></thead><tbody><tr v-for="(row, index) in optionRows" :key="index"><td><input v-model="row.amount" :data-test="`option-amount-${index}`" inputmode="decimal" aria-label="充值金额" /></td><td><input v-model.number="row.displayOrder" :data-test="`option-display-order-${index}`" type="number" min="0" aria-label="显示顺序" /></td><td><input v-model="row.enabled" type="checkbox" aria-label="启用充值选项" /></td><td><button type="button" :data-test="`remove-recharge-option-${index}`" :aria-label="`移除第 ${index + 1} 个充值选项`" @click="optionRows.splice(index, 1)"><Trash2 :size="16" /></button></td></tr></tbody></table></div>
    </section>
      </div>

      <aside class="wallet-aside">
        <div class="wallet-aside__panel">
          <h3 class="wallet-aside__title">余额变动趋势 · 近 7 天</h3>
          <AppChart v-if="trendDays.length" :option="trendOption" height="120px" ariaLabel="近 7 天充值与扣款柱状图" />
          <p v-else class="wallet-aside__empty">暂无流水数据</p>
        </div>
        <div class="wallet-aside__panel">
          <h3 class="wallet-aside__title"><ArrowUpRight :size="14" />最近充值</h3>
          <ul v-if="recentRecharges.length" class="wallet-mini-list">
            <li v-for="row in recentRecharges" :key="row.rechargeNo">
              <div class="wallet-mini-list__main"><strong>{{ userNameLabel(row.displayName) }}</strong><span>{{ formatDateTime(row.createdAt) }}</span></div>
              <b class="wallet-mini-list__in">+{{ money(row.paidAmount, row.currency) }}</b>
            </li>
          </ul>
          <p v-else class="wallet-aside__empty">暂无已到账充值</p>
        </div>
        <div class="wallet-aside__panel">
          <h3 class="wallet-aside__title"><ArrowDownRight :size="14" />最近扣款</h3>
          <ul v-if="recentDebits.length" class="wallet-mini-list">
            <li v-for="row in recentDebits" :key="row.entryNo">
              <div class="wallet-mini-list__main"><strong>{{ userNameLabel(row.displayName) }}</strong><span>{{ formatDateTime(row.createdAt) }}</span></div>
              <b class="wallet-mini-list__out">{{ money(row.amountDelta) }}</b>
            </li>
          </ul>
          <p v-else class="wallet-aside__empty">暂无扣款流水</p>
        </div>
        <div class="wallet-aside__panel">
          <h3 class="wallet-aside__title"><RefreshCw :size="14" />最近钱包流水</h3>
          <ul v-if="recentLedger.length" class="wallet-mini-list">
            <li v-for="row in recentLedger" :key="row.entryNo">
              <div class="wallet-mini-list__main"><strong>{{ userNameLabel(row.displayName) }}</strong><span>{{ adminStatusLabel(row.entryType) }} · {{ formatDateTime(row.createdAt) }}</span></div>
              <b :class="row.amountDelta >= 0 ? 'wallet-mini-list__in' : 'wallet-mini-list__out'">{{ money(row.amountDelta) }}</b>
            </li>
          </ul>
          <p v-else class="wallet-aside__empty">暂无钱包流水</p>
        </div>
        <div class="wallet-aside__panel wallet-aside__panel--alert">
          <h3 class="wallet-aside__title"><ShieldAlert :size="14" />异常余额变动</h3>
          <p class="wallet-aside__sub">退款回补与负向变动，便于审计追踪</p>
          <ul v-if="abnormalLedger.length" class="wallet-mini-list">
            <li v-for="row in abnormalLedger.slice(0, 5)" :key="row.entryNo">
              <div class="wallet-mini-list__main"><strong>{{ userNameLabel(row.displayName) }}</strong><span>{{ adminStatusLabel(row.entryType) }} · {{ formatDateTime(row.createdAt) }}</span></div>
              <b class="wallet-mini-list__out">{{ money(row.amountDelta) }}</b>
            </li>
          </ul>
          <p v-else class="wallet-aside__empty">近期没有异常余额变动</p>
        </div>
      </aside>
    </div>

    <el-drawer v-model="accountDetailOpen" title="钱包账户详情" size="min(480px, 100%)">
      <div v-if="selectedAccount" class="wallet-detail">
        <div class="wallet-detail__head">
          <div><strong>{{ userNameLabel(selectedAccount.displayName) }}</strong><span>{{ userNoLabel(selectedAccount.userNo) }}</span></div>
          <div class="wallet-detail__balance"><span>可用余额</span><strong>{{ money(selectedAccount.availableAmount, selectedAccount.currency) }}</strong></div>
        </div>
        <h4 class="wallet-aside__title">流水明细</h4>
        <ul v-if="accountLedger.length" class="wallet-mini-list">
          <li v-for="row in accountLedger" :key="row.entryNo">
            <div class="wallet-mini-list__main"><strong>{{ adminStatusLabel(row.entryType) }}</strong><span>{{ formatDateTime(row.createdAt) }} · 余 {{ money(row.balanceAfter) }}</span></div>
            <b :class="row.amountDelta >= 0 ? 'wallet-mini-list__in' : 'wallet-mini-list__out'">{{ money(row.amountDelta) }}</b>
          </li>
        </ul>
        <p v-else class="wallet-aside__empty">该账户暂无流水</p>
        <h4 class="wallet-aside__title"><LockKeyhole :size="14" />相关操作记录</h4>
        <ul v-if="accountAudit.length" class="wallet-mini-list">
          <li v-for="row in accountAudit" :key="row.id">
            <div class="wallet-mini-list__main"><strong>{{ userNameLabel(row.actorDisplayName) }}</strong><span>{{ row.action }} · {{ formatDateTime(row.createdAt) }}</span></div>
            <el-tag size="small" :type="row.outcome === 'SUCCESS' ? 'success' : 'danger'">{{ row.outcome === 'SUCCESS' ? '成功' : '失败' }}</el-tag>
          </li>
        </ul>
        <p v-else class="wallet-aside__empty">暂无相关操作记录</p>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.wallet-admin { width: min(1280px, 100%); overflow-x: clip; }
.wallet-admin-page { width: 100%; }
.page-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.page-header h2 { margin: 0; font-size: 22px; }
.page-header p { margin: 4px 0 0; color: var(--app-muted); }
.tab-list { display: flex; gap: 4px; overflow-x: auto; border-bottom: 1px solid var(--app-border); }
.tab-list button { min-height: 44px; padding: 0 14px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--app-muted); cursor: pointer; white-space: nowrap; }
.tab-list button[aria-selected="true"] { border-bottom-color: var(--app-primary); color: var(--app-primary); font-weight: 700; }
.data-band { min-height: 360px; padding-top: 16px; }
.filters, .option-actions { display: flex; flex-wrap: wrap; align-items: end; gap: 10px; margin-bottom: 12px; }
.filters label { display: grid; gap: 5px; min-width: 180px; color: var(--app-muted); font-size: 12px; }
.filters > button { width: 76px; min-width: 76px; }
input, select, button { min-height: 44px; box-sizing: border-box; }
input, select { padding: 0 11px; border: 1px solid var(--app-border); border-radius: 6px; background: var(--app-surface); color: var(--app-text); }
button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 0 12px; border: 1px solid var(--app-border); border-radius: 6px; background: var(--app-surface); color: var(--app-text); cursor: pointer; }
button:focus-visible, input:focus-visible, select:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 2px; }
button:disabled { cursor: not-allowed; opacity: .55; }
.table-scroll { max-width: 100%; overflow-x: auto; border-top: 1px solid var(--app-border); border-bottom: 1px solid var(--app-border); }
table { width: 100%; min-width: 760px; border-collapse: collapse; }
th, td { padding: 11px 12px; border-bottom: 1px solid var(--app-border); text-align: left; white-space: nowrap; }
th { color: var(--app-muted); font-size: 12px; }
td { font-variant-numeric: tabular-nums; }
td strong, td small { display: block; }
td small { margin-top: 2px; color: var(--app-muted); }
.state { min-height: 180px; display: flex; align-items: center; justify-content: center; gap: 12px; color: var(--app-muted); }
:deep(.el-pagination) { justify-content: flex-end; padding-top: 12px; }

/* ===== 顶部统计带 ===== */
.wallet-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; margin-bottom: 14px; }
.wallet-stat { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-surface); box-shadow: var(--app-shadow-xs); }
.wallet-stat__icon { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; min-width: 32px; border-radius: 8px; }
.wallet-stat__icon--users { color: var(--app-primary); background: var(--app-primary-soft); }
.wallet-stat__icon--bank { color: var(--app-fg); background: var(--app-surface-muted); }
.wallet-stat__icon--in { color: var(--app-primary); background: var(--app-primary-soft); }
.wallet-stat__icon--out { color: var(--app-danger); background: var(--app-danger-soft); }
.wallet-stat strong { display: block; font-size: 17px; font-weight: 700; line-height: 1.25; color: var(--app-fg); font-variant-numeric: tabular-nums; }
.wallet-stat__balance { letter-spacing: 0.2px; }
.wallet-stat__in { color: var(--app-primary); }
.wallet-stat__out { color: var(--app-danger); }
.wallet-stat span { display: block; font-size: 12px; color: var(--app-muted-strong); white-space: nowrap; }

/* ===== 主区 + 侧栏 ===== */
.wallet-layout { display: grid; grid-template-columns: minmax(0, 1fr) 250px; gap: 14px; align-items: start; }
.wallet-layout__main { min-width: 0; }
.wallet-aside { display: grid; gap: 14px; position: sticky; top: 12px; }
.wallet-aside__panel { padding: 12px 14px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-surface); box-shadow: var(--app-shadow-xs); }
.wallet-aside__title { display: flex; align-items: center; gap: 7px; margin: 0; font-size: 13px; font-weight: 700; color: var(--app-fg); }
.wallet-aside__empty { margin: 14px 0 6px; font-size: 12px; color: var(--app-muted); text-align: center; }
.wallet-mini-list { list-style: none; margin: 10px 0 0; padding: 0; display: grid; gap: 6px; }
.wallet-mini-list li { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.wallet-mini-list__main { min-width: 0; display: flex; flex-direction: column; }
.wallet-mini-list__main strong { font-size: 12px; font-weight: 600; color: var(--app-fg); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.wallet-mini-list__main span { margin-top: 1px; font-size: 11px; color: var(--app-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.wallet-mini-list b { font-size: 12px; font-weight: 700; font-variant-numeric: tabular-nums; white-space: nowrap; }
.wallet-mini-list__in { color: var(--app-primary); }
.wallet-mini-list__out { color: var(--app-danger); }

/* ===== 异常变动侧栏面板 ===== */
.wallet-aside__panel--alert { border-color: var(--app-border-strong, #d8dce3); background: var(--app-surface-muted); }
.wallet-aside__sub { margin: 5px 0 0; font-size: 11px; color: var(--app-muted); }

/* ===== 表格余额与详情 ===== */
.wallet-balance-cell { font-weight: 700; color: var(--app-fg); }
.wallet-detail-btn { min-height: 32px; padding: 0 10px; font-size: 12px; color: var(--app-primary); border-color: var(--app-border); }
.wallet-detail-btn:hover { border-color: var(--app-primary); }

/* ===== 详情抽屉 ===== */
.wallet-detail__head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 14px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-surface-muted); }
.wallet-detail__head strong { display: block; font-size: 14px; color: var(--app-fg); }
.wallet-detail__head span { font-size: 12px; color: var(--app-muted); }
.wallet-detail__balance { text-align: right; }
.wallet-detail__balance span { display: block; font-size: 11px; color: var(--app-muted); }
.wallet-detail__balance strong { font-size: 18px; font-weight: 700; color: var(--app-fg); font-variant-numeric: tabular-nums; }
.wallet-detail .wallet-aside__title { margin: 16px 0 4px; }

@media (max-width: 1100px) {
  .wallet-layout { grid-template-columns: 1fr; }
  .wallet-aside { position: static; grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 720px) { .filters { align-items: stretch; flex-direction: column; } .filters label, .filters button { width: 100%; } .page-header p { max-width: 30ch; } .wallet-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); } .wallet-aside { grid-template-columns: 1fr; } }
</style>
