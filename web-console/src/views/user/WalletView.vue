<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { CircleDollarSign, ChevronDown, CreditCard, History, Plus, ReceiptText, RefreshCw, WalletCards, XCircle, TrendingUp, TrendingDown, ArrowDownToLine } from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'

import { closeRecharge, type RechargeOrder } from '../../api/wallet'
import RechargeDialog from '../../components/payment/RechargeDialog.vue'
import { useWalletStore } from '../../stores/wallet'
import { formatDateTime } from '../../utils/dateTime'

const wallet = useWalletStore()
const rechargeVisible = ref(false)
const activeTab = ref<'recharges' | 'ledger'>('recharges')
const balanceDropdownOpen = ref(false)
const rechargePage = ref(0)
const ledgerPage = ref(0)
const rechargePageSize = ref(20)
const ledgerPageSize = ref(20)
const pageSizeOptions = [5, 10, 20]
const selectedRecharge = ref<RechargeOrder | null>(null)
const closingRechargeNo = ref('')
const summaryError = ref('')
const optionsError = ref('')
const rechargesError = ref('')
const ledgerError = ref('')
let summaryLoadSequence = 0
let optionsLoadSequence = 0
let rechargesLoadSequence = 0
let ledgerLoadSequence = 0

type WalletRefreshFailure = 'summary' | 'recharges' | 'ledger'

const loadingInitial = computed(() => (
  wallet.loadingSummary || wallet.loadingRecharges || wallet.loadingLedger
))

const rechargeStatusLabels: Record<string, string> = {
  PENDING: '待支付', PAID: '已到账', CLOSED: '已关闭', FAILED: '支付失败',
}
const ledgerTypeLabels: Record<string, string> = {
  RECHARGE: '余额充值', PACKAGE_PURCHASE: '套餐购买', PACKAGE_REFUND: '套餐退款',
}

const number = new Intl.NumberFormat('zh-CN')

function money(minorUnits: number, currency = 'CNY') {
  const units = BigInt(minorUnits)
  const absolute = units < 0n ? -units : units
  const whole = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 }).format(absolute / 100n)
  const fraction = (absolute % 100n).toString().padStart(2, '0')
  const symbol = currency === 'CNY' ? '¥' : `${currency} `
  return `${units < 0n ? '-' : ''}${symbol}${whole}.${fraction}`
}

function rechargeParams(page: number) {
  return { page, size: rechargePageSize.value, sort: 'createdAt', direction: 'desc' as const }
}

function ledgerParams(page: number) {
  return { page, size: ledgerPageSize.value, sort: 'createdAt', direction: 'desc' as const }
}

function rechargeStatusType(status: RechargeOrder['status']) {
  if (status === 'PAID') return 'success'
  if (status === 'PENDING') return 'warning'
  if (status === 'FAILED') return 'danger'
  return 'info'
}

function rechargeStatusClass(status: RechargeOrder['status']) {
  const type = rechargeStatusType(status)
  return `status-badge status--${type}`
}

const totalRecharged = computed(() => {
  return wallet.recharges.items
    .filter(item => item.status === 'PAID')
    .reduce((sum, item) => sum + item.creditedAmount, 0)
})

const totalRechargedLabel = computed(() => {
  if (!wallet.recharges.items.length || !wallet.summary) return '¥0.00'
  return money(totalRecharged.value, wallet.summary.currency)
})

const rechargeCount = computed(() => wallet.recharges.total)

async function loadSummaryData() {
  const sequence = ++summaryLoadSequence
  const scope = wallet.userScopeGeneration
  summaryError.value = ''
  try {
    const result = await wallet.loadSummary()
    return sequence === summaryLoadSequence
      && scope === wallet.userScopeGeneration
      && result !== undefined
  } catch {
    if (sequence !== summaryLoadSequence || scope !== wallet.userScopeGeneration) return false
    summaryError.value = '可用余额加载失败，请稍后重试。'
    return false
  }
}

async function loadOptionsData() {
  const sequence = ++optionsLoadSequence
  const scope = wallet.userScopeGeneration
  optionsError.value = ''
  try {
    const result = await wallet.loadRechargeOptions()
    return sequence === optionsLoadSequence
      && scope === wallet.userScopeGeneration
      && result !== undefined
  } catch {
    if (sequence !== optionsLoadSequence || scope !== wallet.userScopeGeneration) return false
    optionsError.value = '充值金额配置加载失败，请稍后重试。'
    return false
  }
}

async function loadRechargesPage(targetPage = rechargePage.value) {
  const sequence = ++rechargesLoadSequence
  const scope = wallet.userScopeGeneration
  rechargesError.value = ''
  try {
    const result = await wallet.loadRecharges(rechargeParams(targetPage))
    return sequence === rechargesLoadSequence
      && scope === wallet.userScopeGeneration
      && result !== undefined
  } catch {
    if (sequence !== rechargesLoadSequence || scope !== wallet.userScopeGeneration) return false
    rechargesError.value = '充值记录加载失败，请稍后重试。'
    return false
  }
}

async function loadLedgerPage(targetPage = ledgerPage.value) {
  const sequence = ++ledgerLoadSequence
  const scope = wallet.userScopeGeneration
  ledgerError.value = ''
  try {
    const result = await wallet.loadLedger(ledgerParams(targetPage))
    return sequence === ledgerLoadSequence
      && scope === wallet.userScopeGeneration
      && result !== undefined
  } catch {
    if (sequence !== ledgerLoadSequence || scope !== wallet.userScopeGeneration) return false
    ledgerError.value = '余额明细加载失败，请稍后重试。'
    return false
  }
}

async function loadAll() {
  await Promise.allSettled([
    loadSummaryData(), loadOptionsData(), loadRechargesPage(), loadLedgerPage(),
  ])
}

async function changeRechargePage(nextPage: number) {
  const targetPage = nextPage - 1
  if (await loadRechargesPage(targetPage)) rechargePage.value = targetPage
}

async function changeLedgerPage(nextPage: number) {
  const targetPage = nextPage - 1
  if (await loadLedgerPage(targetPage)) ledgerPage.value = targetPage
}

async function changeRechargePageSize(nextSize: number) {
  rechargePageSize.value = nextSize
  rechargePage.value = 0
  await loadRechargesPage(0)
}

async function changeLedgerPageSize(nextSize: number) {
  ledgerPageSize.value = nextSize
  ledgerPage.value = 0
  await loadLedgerPage(0)
}

function openNewRecharge() {
  selectedRecharge.value = null
  rechargeVisible.value = true
}

function resumeRecharge(item: RechargeOrder) {
  selectedRecharge.value = item
  rechargeVisible.value = true
}

async function cancelRecharge(item: RechargeOrder) {
  try {
    await ElMessageBox.confirm(
      `确认取消充值单 ${item.rechargeNo}？`,
      '取消充值订单',
      { confirmButtonText: '取消订单', cancelButtonText: '暂不取消', type: 'warning' },
    )
    closingRechargeNo.value = item.rechargeNo
    const result = await closeRecharge(item.rechargeNo)
    if (result.status === 'CLOSED') {
      ElMessage.success('充值订单已取消。')
    } else if (result.status === 'PAID') {
      ElMessage.success('渠道已确认支付成功，充值金额已到账。')
      await Promise.allSettled([loadSummaryData(), loadLedgerPage(0)])
    } else {
      ElMessage.warning('渠道支付状态仍在确认中，订单暂未取消，请稍后重试。')
    }
    if (wallet.recharges.items.length === 1 && rechargePage.value > 0) rechargePage.value -= 1
    await loadRechargesPage()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error('充值订单取消失败，请刷新状态后重试。')
    }
  } finally {
    closingRechargeNo.value = ''
  }
}

function activateTab(tab: 'recharges' | 'ledger', focus = false) {
  activeTab.value = tab
  if (focus) void nextTick(() => document.getElementById(`wallet-${tab}-tab`)?.focus())
}

function handleTabKeydown(event: KeyboardEvent, current: 'recharges' | 'ledger') {
  const tabs: Array<'recharges' | 'ledger'> = ['recharges', 'ledger']
  const currentIndex = tabs.indexOf(current)
  let target: 'recharges' | 'ledger' | undefined
  if (event.key === 'ArrowRight') target = tabs[(currentIndex + 1) % tabs.length]
  if (event.key === 'ArrowLeft') target = tabs[(currentIndex - 1 + tabs.length) % tabs.length]
  if (event.key === 'Home') target = tabs[0]
  if (event.key === 'End') target = tabs.at(-1)
  if (!target) return
  event.preventDefault()
  activateTab(target, true)
}

function handleRechargeCompleted(_order: unknown, refreshFailures: WalletRefreshFailure[] = []) {
  rechargePage.value = 0
  ledgerPage.value = 0
  summaryError.value = refreshFailures.includes('summary') ? '支付已确认，但余额刷新失败，请重试。' : ''
  rechargesError.value = refreshFailures.includes('recharges') ? '支付已确认，但充值记录刷新失败，请重试。' : ''
  ledgerError.value = refreshFailures.includes('ledger') ? '支付已确认，但余额明细刷新失败，请重试。' : ''
  void Promise.allSettled([loadSummaryData(), loadRechargesPage(0), loadLedgerPage(0)])
}

onMounted(() => void loadAll())

watch(
  rechargeVisible,
  (visible) => {
    if (!visible) selectedRecharge.value = null
  },
)

watch(
  () => wallet.userScopeGeneration,
  () => {
    summaryLoadSequence += 1
    optionsLoadSequence += 1
    rechargesLoadSequence += 1
    ledgerLoadSequence += 1
    rechargePage.value = 0
    ledgerPage.value = 0
    summaryError.value = ''
    optionsError.value = ''
    rechargesError.value = ''
    ledgerError.value = ''
    void loadAll()
  },
)
</script>

<template>
  <section class="wallet-page" aria-labelledby="wallet-title">
    <!-- Page Header -->
    <header class="page-header">
      <div class="page-title">
        <h2 id="wallet-title">余额钱包</h2>
        <p>管理您的账户资金与充值记录</p>
      </div>
      <button class="primary-btn" data-test="open-recharge" @click="openNewRecharge">
        <Plus :size="16" />
        充值余额
      </button>
    </header>

    <TopErrorMessage :message="optionsError" />
    <button v-if="optionsError" class="retry-btn" data-test="retry-options" @click="loadOptionsData">
      <RefreshCw :size="15" /> 重试充值配置
    </button>

    <div v-if="loadingInitial && !wallet.summary" data-test="wallet-loading" class="wallet-loading" aria-busy="true" aria-label="钱包数据加载中">
      <el-skeleton :rows="5" animated />
    </div>

    <template v-else>
      <!-- Balance Card + Stats -->
      <div class="balance-section">
        <div class="balance-card">
          <div class="balance-card-content">
            <div class="balance-left">
              <div class="balance-icon-wrap">
                <WalletCards :size="24" />
              </div>
              <div class="balance-info">
                <p class="balance-label">可用余额</p>
                <strong class="balance-amount" data-test="wallet-balance">
                  {{ wallet.summary ? money(wallet.summary.availableAmount, wallet.summary.currency) : '—' }}
                </strong>
                <small v-if="wallet.summary" class="balance-updated">更新于 {{ formatDateTime(wallet.summary.updatedAt) }}</small>
              </div>
            </div>
            <div class="balance-divider"></div>
            <div class="balance-right">
              <p class="balance-note">余额仅用于购买套餐，充值后不可提现</p>
              <div class="balance-dropdown">
                <button class="secondary-btn" @click="balanceDropdownOpen = !balanceDropdownOpen">
                  <History :size="14" /> 流水记录
                  <ChevronDown :size="14" class="dropdown-arrow" :class="{ open: balanceDropdownOpen }" />
                </button>
                <div v-if="balanceDropdownOpen" class="dropdown-menu">
                  <button class="dropdown-item" @click="activateTab('recharges'); balanceDropdownOpen = false">
                    <CircleDollarSign :size="15" /> 充值记录
                  </button>
                  <button class="dropdown-item" @click="activateTab('ledger'); balanceDropdownOpen = false">
                    <History :size="15" /> 余额明细
                  </button>
                </div>
              </div>
            </div>
          </div>
          <div v-if="summaryError" data-test="wallet-error" class="balance-error">
            <TopErrorMessage :message="summaryError" />
            <button class="retry-btn" data-test="retry-wallet" @click="loadSummaryData">
              <RefreshCw :size="15" /> 重试余额
            </button>
          </div>
        </div>

        <!-- Stat Cards -->
        <div class="stat-cards">
          <div class="stat-card">
            <div class="stat-body">
              <span class="stat-label">累计充值</span>
              <span class="stat-value">{{ totalRechargedLabel }}</span>
            </div>
            <div class="stat-icon stat-icon--green"><ArrowDownToLine :size="18" /></div>
          </div>
          <div class="stat-card">
            <div class="stat-body">
              <span class="stat-label">充值次数</span>
              <span class="stat-value">{{ number.format(rechargeCount) }}</span>
            </div>
            <div class="stat-icon stat-icon--blue"><CircleDollarSign :size="18" /></div>
          </div>
          <div class="stat-card">
            <div class="stat-body">
              <span class="stat-label">流水笔数</span>
              <span class="stat-value">{{ number.format(wallet.ledger.total) }}</span>
            </div>
            <div class="stat-icon stat-icon--purple"><History :size="18" /></div>
          </div>
        </div>

        <!-- Quick Actions Panel -->
        <div class="quick-panel">
          <h4 class="quick-panel-title">快捷操作</h4>
          <div class="quick-actions">
            <button class="quick-btn" @click="$router.push('/app/plans')">
              <div class="quick-icon quick-icon--indigo"><CreditCard :size="18" /></div>
              <span>购买套餐</span>
            </button>
            <button class="quick-btn" @click="$router.push('/app/orders')">
              <div class="quick-icon quick-icon--teal"><ReceiptText :size="18" /></div>
              <span>订单记录</span>
            </button>
            <button class="quick-btn" @click="activateTab('ledger')">
              <div class="quick-icon quick-icon--amber"><History :size="18" /></div>
              <span>余额明细</span>
            </button>
            <button class="quick-btn" @click="openNewRecharge">
              <div class="quick-icon quick-icon--rose"><Plus :size="18" /></div>
              <span>立即充值</span>
            </button>
          </div>
        </div>
      </div>

      <!-- Transaction Records -->
      <section class="history-section" aria-labelledby="wallet-history-title">
        <div class="history-heading">
          <div class="history-title">
            <h3 id="wallet-history-title">交易记录</h3>
          </div>
          <div class="tab-list" role="tablist" aria-label="钱包记录类型">
            <button
              id="wallet-recharges-tab"
              type="button"
              role="tab"
              data-test="recharge-tab"
              :aria-selected="activeTab === 'recharges'"
              aria-controls="wallet-recharges-panel"
              :tabindex="activeTab === 'recharges' ? 0 : -1"
              :class="{ active: activeTab === 'recharges' }"
              @click="activateTab('recharges')"
              @keydown="handleTabKeydown($event, 'recharges')"
            >
              <CircleDollarSign :size="16" /> 充值记录
            </button>
            <button
              id="wallet-ledger-tab"
              type="button"
              role="tab"
              data-test="ledger-tab"
              :aria-selected="activeTab === 'ledger'"
              aria-controls="wallet-ledger-panel"
              :tabindex="activeTab === 'ledger' ? 0 : -1"
              :class="{ active: activeTab === 'ledger' }"
              @click="activateTab('ledger')"
              @keydown="handleTabKeydown($event, 'ledger')"
            >
              <History :size="16" /> 余额明细
            </button>
          </div>
        </div>

        <!-- Recharges Tab -->
        <div id="wallet-recharges-panel" v-show="activeTab === 'recharges'" data-test="recharges-panel" role="tabpanel" aria-labelledby="wallet-recharges-tab">
          <TopErrorMessage :message="rechargesError" />
          <button v-if="rechargesError" class="retry-btn" data-test="retry-recharges" @click="loadRechargesPage()">
            <RefreshCw :size="15" /> 重试充值记录
          </button>
          <el-skeleton v-else-if="wallet.loadingRecharges" :rows="4" animated />
          <template v-else>
            <div class="table-scroll">
              <table class="data-table">
                <thead>
                  <tr><th>充值单号</th><th>支付金额</th><th>到账金额</th><th>状态</th><th>创建时间</th><th>操作</th></tr>
                </thead>
                <tbody>
                  <tr v-for="item in wallet.recharges.items" :key="item.rechargeNo" class="data-row">
                    <td><code class="order-code">{{ item.rechargeNo }}</code></td>
                    <td class="cell-amount">{{ money(item.paidAmount, item.currency) }}</td>
                    <td class="cell-amount">{{ item.status === 'PAID' ? money(item.creditedAmount, item.currency) : '—' }}</td>
                    <td>
                      <span :class="rechargeStatusClass(item.status)">{{ rechargeStatusLabels[item.status] }}</span>
                    </td>
                    <td class="cell-date">{{ formatDateTime(item.createdAt) }}</td>
                    <td>
                      <div v-if="item.status === 'PENDING'" class="row-actions">
                        <button class="action-btn action-btn--primary" data-test="resume-recharge" @click="resumeRecharge(item)">
                          <CreditCard :size="14" /> 去支付
                        </button>
                        <button class="action-btn action-btn--ghost" data-test="cancel-recharge" :disabled="closingRechargeNo === item.rechargeNo" @click="cancelRecharge(item)">
                          <XCircle :size="14" /> 取消
                        </button>
                      </div>
                      <span v-else class="muted-action">—</span>
                    </td>
                  </tr>
                  <tr v-if="!wallet.recharges.items.length">
                    <td colspan="6" class="empty-row">
                      <ReceiptText :size="24" />
                      <span>暂无充值记录</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <footer class="table-footer">
              <span>共 {{ wallet.recharges.total }} 条记录</span>
              <el-pagination
                v-if="wallet.recharges.total > 0"
                data-test="recharge-pagination"
                :current-page="rechargePage + 1"
                :page-size="rechargePageSize"
                :page-sizes="pageSizeOptions"
                :total="wallet.recharges.total"
                layout="sizes, prev, pager, next"
                @size-change="changeRechargePageSize"
                @current-change="changeRechargePage"
              />
            </footer>
          </template>
        </div>

        <!-- Ledger Tab -->
        <div id="wallet-ledger-panel" v-show="activeTab === 'ledger'" data-test="ledger-panel" role="tabpanel" aria-labelledby="wallet-ledger-tab">
          <TopErrorMessage :message="ledgerError" />
          <button v-if="ledgerError" class="retry-btn" data-test="retry-ledger" @click="loadLedgerPage()">
            <RefreshCw :size="15" /> 重试余额明细
          </button>
          <el-skeleton v-else-if="wallet.loadingLedger" :rows="4" animated />
          <template v-else>
            <div class="table-scroll">
              <table class="data-table">
                <thead>
                  <tr><th>流水号</th><th>类型</th><th>变动金额</th><th>变动后余额</th><th>业务编号</th><th>时间</th></tr>
                </thead>
                <tbody>
                  <tr v-for="item in wallet.ledger.items" :key="item.entryNo" class="data-row">
                    <td><code class="order-code">{{ item.entryNo }}</code></td>
                    <td>{{ ledgerTypeLabels[item.entryType] }}</td>
                    <td :class="item.amountDelta >= 0 ? 'amount-positive' : 'amount-negative'">
                      <TrendingUp v-if="item.amountDelta >= 0" :size="14" class="delta-icon delta-up" />
                      <TrendingDown v-else :size="14" class="delta-icon delta-down" />
                      {{ item.amountDelta >= 0 ? '+' : '' }}{{ money(item.amountDelta) }}
                    </td>
                    <td class="cell-amount">{{ money(item.balanceAfter) }}</td>
                    <td><code class="order-code">{{ item.businessNo }}</code></td>
                    <td class="cell-date">{{ formatDateTime(item.createdAt) }}</td>
                  </tr>
                  <tr v-if="!wallet.ledger.items.length">
                    <td colspan="6" class="empty-row">
                      <ReceiptText :size="24" />
                      <span>暂无余额明细</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <footer class="table-footer">
              <span>共 {{ wallet.ledger.total }} 条记录</span>
              <el-pagination
                v-if="wallet.ledger.total > 0"
                data-test="ledger-pagination"
                :current-page="ledgerPage + 1"
                :page-size="ledgerPageSize"
                :page-sizes="pageSizeOptions"
                :total="wallet.ledger.total"
                layout="sizes, prev, pager, next"
                @size-change="changeLedgerPageSize"
                @current-change="changeLedgerPage"
              />
            </footer>
          </template>
        </div>
      </section>
    </template>

    <RechargeDialog v-model="rechargeVisible" :existing-order="selectedRecharge" @completed="handleRechargeCompleted" />
  </section>
</template>

<style scoped>
.wallet-page {
  width: 100%;
  min-width: 0;
  overflow-x: hidden;
}

/* 页头 */
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
}
.page-title h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
}
.page-title p {
  margin: 6px 0 0;
  color: var(--app-muted);
  font-size: 14px;
}
.primary-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border: 0;
  border-radius: 8px;
  background: var(--app-primary);
  color: #fff;
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--app-motion-fast) ease;
  flex-shrink: 0;
}
.primary-btn:hover {
  background: var(--app-primary);
}
.secondary-btn {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 7px 14px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
  color: var(--app-fg);
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--app-motion-fast) ease;
}
.secondary-btn:hover {
  border-color: var(--app-primary);
  color: var(--app-primary);
  background: var(--app-primary-soft);
}
.retry-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
  color: var(--app-muted);
  font: inherit;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--app-motion-fast) ease;
  margin: 8px 0;
}
.retry-btn:hover {
  color: var(--app-primary);
  border-color: var(--app-primary);
  background: var(--app-primary-soft);
}
.wallet-loading {
  min-height: 320px;
  padding: 20px 0;
}

/* 余额区块 */
.balance-section {
  display: flex;
  gap: 24px;
  margin-bottom: 24px;
  align-items: stretch;
}
.balance-card {
  position: relative;
  max-width: 620px;
  flex: 0 1 620px;
  display: flex;
  align-items: center;
  padding: 24px 28px;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid var(--app-border);
  background: var(--app-surface);
  box-shadow: var(--app-shadow-xs);
}
.balance-card-content {
  position: relative;
  display: flex;
  align-items: center;
  gap: 24px;
  width: 100%;
}
.balance-left {
  display: flex;
  align-items: center;
  gap: 14px;
  flex: 1;
  min-width: 0;
}
.balance-icon-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  color: #fff;
  background: var(--app-primary);
  border-radius: 12px;
  flex-shrink: 0;
}
.balance-info {
  min-width: 0;
}
.balance-label {
  margin: 0;
  font-size: 13px;
  color: var(--app-primary);
  font-weight: 600;
}
.balance-amount {
  display: block;
  margin-top: 2px;
  font-size: 30px;
  font-weight: 800;
  color: var(--app-fg);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.5px;
}
.balance-updated {
  display: block;
  margin-top: 3px;
  color: var(--app-muted);
  font-size: 12px;
}
.balance-divider {
  width: 1px;
  height: 60px;
  background: var(--app-border);
  flex-shrink: 0;
}
.balance-right {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 10px;
}
.balance-note {
  margin: 0;
  font-size: 12px;
  color: var(--app-muted);
  line-height: 1.5;
  text-align: left;
}
.balance-dropdown {
  position: relative;
}
.dropdown-arrow {
  transition: transform var(--app-motion-fast) ease;
}
.dropdown-arrow.open {
  transform: rotate(180deg);
}
.dropdown-menu {
  position: absolute;
  top: 100%;
  left: 0;
  margin-top: 6px;
  background: var(--app-surface-raised);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  box-shadow: var(--app-shadow-md);
  overflow: hidden;
  z-index: 10;
  min-width: 130px;
}
.dropdown-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 9px 14px;
  border: 0;
  background: transparent;
  color: var(--app-fg);
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--app-motion-fast) ease;
  text-align: left;
}
.dropdown-item:hover {
  background: var(--app-primary-soft);
  color: var(--app-primary);
}
.balance-error {
  position: relative;
  width: 100%;
}

/* 统计卡片 */
.stat-cards {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 280px;
  flex-shrink: 0;
}
.stat-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  box-shadow: var(--app-shadow-xs);
  transition: all var(--app-motion-base) ease;
  flex: 1;
}
.stat-card:hover {
  box-shadow: var(--app-shadow-sm);
  border-color: var(--app-border-strong);
}
.stat-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  flex-shrink: 0;
}
.stat-icon--green {
  color: var(--app-success);
  background: var(--app-success-soft);
}
.stat-icon--blue {
  color: var(--app-primary);
  background: var(--app-primary-soft);
}
.stat-icon--purple {
  color: var(--app-primary);
  background: var(--app-primary-soft);
}
.stat-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.stat-label {
  font-size: 12px;
  color: var(--app-muted-strong);
}
.stat-value {
  font-size: 20px;
  font-weight: 700;
  color: var(--app-fg);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.5px;
}

/* 快捷操作面板 */
.quick-panel {
  flex: 1;
  padding: 18px 20px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  box-shadow: var(--app-shadow-xs);
  display: flex;
  flex-direction: column;
  gap: 14px;
  align-self: stretch;
}
.quick-panel-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--app-muted-strong);
}
.quick-actions {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  flex: 1;
  align-content: center;
}
.quick-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 14px 8px;
  border: 1px solid var(--app-border);
  border-radius: 12px;
  background: var(--app-surface-subtle);
  color: var(--app-fg);
  font: inherit;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--app-motion-fast) ease;
}
.quick-btn:hover {
  border-color: var(--app-primary);
  background: var(--app-primary-soft);
  color: var(--app-primary);
}
.quick-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 12px;
}
.quick-icon--indigo {
  color: var(--app-primary);
  background: var(--app-primary-soft);
}
.quick-icon--teal {
  color: #0d9488;
  background: var(--app-success-soft);
}
.quick-icon--amber {
  color: var(--app-accent);
  background: var(--app-accent-soft);
}
.quick-icon--rose {
  color: var(--app-danger);
  background: var(--app-danger-soft);
}

/* 历史记录区块 */
.history-section {
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  overflow: hidden;
}
.history-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--app-border);
}
.history-title h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
}
.tab-list {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border: 1px solid var(--app-border);
  border-radius: 12px;
  background: var(--app-surface-subtle);
}
.tab-list button {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 44px;
  padding: 7px 14px;
  color: var(--app-muted);
  background: transparent;
  border: 0;
  border-radius: 8px;
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--app-motion-fast) ease;
}
.tab-list button:hover {
  color: var(--app-fg);
}
.tab-list button.active {
  color: #fff;
  background: var(--app-primary);
  font-weight: 600;
  box-shadow: var(--app-shadow-sm);
}

/* 表格 */
.table-scroll {
  max-width: 100%;
  overflow-x: auto;
}
.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.data-table th,
.data-table td {
  padding: 14px 16px;
  border-bottom: 1px solid var(--app-border);
  text-align: left;
  white-space: nowrap;
  height: 48px;
}
.data-table thead th {
  color: var(--app-muted-strong);
  font-size: 12px;
  font-weight: 600;
  background: var(--app-surface-subtle);
  text-transform: uppercase;
  letter-spacing: 0.3px;
}
.data-row {
  transition: background var(--app-motion-fast) ease;
}
.data-row:hover {
  background: var(--app-surface-subtle);
}
.order-code {
  color: var(--app-primary);
  font-weight: 600;
}
.cell-amount {
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.cell-date {
  color: var(--app-muted);
}
.amount-positive {
  color: var(--app-success);
  font-weight: 600;
}
.amount-negative {
  color: var(--app-danger);
  font-weight: 600;
}
.delta-icon {
  vertical-align: -2px;
  margin-right: 2px;
}
.delta-up { color: var(--app-success); }
.delta-down { color: var(--app-danger); }

/* 状态徽章 */
.status-badge {
  display: inline-flex;
  align-items: center;
  padding: 3px 12px;
  border-radius: 9999px;
  font-size: 12px;
  font-weight: 600;
}
.status--success {
  color: var(--app-success);
  background: var(--app-success-soft);
}
.status--warning {
  color: var(--app-warning);
  background: var(--app-warning-soft);
}
.status--danger {
  color: var(--app-danger);
  background: var(--app-danger-soft);
}
.status--info {
  color: var(--app-muted-strong);
  background: var(--app-surface-subtle);
}

/* 行操作 */
.row-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}
.action-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  border: 1px solid transparent;
  border-radius: 8px;
  font: inherit;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--app-motion-fast) ease;
  white-space: nowrap;
}
.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.action-btn--primary {
  color: #fff;
  background: var(--app-primary);
}
.action-btn--primary:hover:not(:disabled) {
  background: var(--app-primary);
  box-shadow: var(--app-shadow-sm);
}
.action-btn--ghost {
  color: var(--app-muted);
  background: transparent;
  border-color: var(--app-border);
}
.action-btn--ghost:hover:not(:disabled) {
  color: var(--app-danger);
  border-color: var(--app-danger-soft);
  background: var(--app-danger-soft);
}
.muted-action {
  color: var(--app-muted);
}

/* 空状态 */
.empty-row {
  text-align: center;
  color: var(--app-muted);
  padding: 40px 16px;
}
.empty-row svg {
  margin-right: 8px;
  vertical-align: middle;
}

/* 表格页脚 */
.table-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  color: var(--app-muted);
  font-size: 12px;
  border-top: 1px solid var(--app-border);
}

/* 响应式 */
@media (max-width: 1080px) {
  .balance-section {
    flex-direction: column;
  }
  .balance-card {
    flex: 1;
  }
  .stat-cards {
    width: 100%;
    flex-direction: row;
  }
  .stat-card {
    flex: 1;
  }
}
@media (max-width: 640px) {
  .page-header {
    flex-direction: column;
  }
  .primary-btn {
    width: 100%;
    justify-content: center;
  }
  .balance-card {
    padding: 20px;
    flex-direction: column;
    align-items: flex-start;
  }
  .balance-card-content {
    flex-wrap: wrap;
    /* 手机上改为纵向排布：此前金额与说明文字会重叠，标签也被拆成两行 */
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
    width: 100%;
  }
  .balance-card-content > * {
    width: 100%;
    min-width: 0;
  }
  .balance-divider {
    display: none;
  }
  .balance-amount {
    font-size: 26px;
  }
  .stat-cards {
    flex-direction: column;
  }
  .history-heading {
    flex-direction: column;
    align-items: stretch;
  }
  .tab-list {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .tab-list button {
    justify-content: center;
  }
  .table-footer {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
