<script setup lang="ts">
import { ref } from 'vue'
import { Play, Search } from 'lucide-vue-next'

import {
  getReconciliationRun,
  startReconciliation,
  type ReconciliationRun,
} from '../../api/admin'
import { formatDateTime } from '../../utils/dateTime'

const provider = ref('')
const billDate = ref('')
const run = ref<ReconciliationRun | null>(null)
const starting = ref(false)
const loadingItems = ref(false)
const errorMessage = ref('')
const pageSize = ref(20)
const pageSizeOptions = [5, 10, 20]

async function start() {
  if (!provider.value || !/^\d{4}-\d{2}-\d{2}$/.test(billDate.value) || starting.value) return
  starting.value = true
  errorMessage.value = ''
  try {
    run.value = await startReconciliation(provider.value, billDate.value)
  } catch {
    errorMessage.value = '对账任务启动失败，请确认渠道账单已就绪。'
  } finally {
    starting.value = false
  }
}

async function loadPage(targetPage: number) {
  if (!run.value || loadingItems.value) return
  loadingItems.value = true
  errorMessage.value = ''
  try {
    run.value = await getReconciliationRun(
      run.value.id, targetPage, pageSize.value, 'createdAt', 'desc',
    )
  } catch {
    errorMessage.value = '对账差异明细加载失败，请稍后重试。'
  } finally {
    loadingItems.value = false
  }
}

function changePageSize(value: number) {
  pageSize.value = value
  return loadPage(0)
}

function amount(value: number | null) {
  if (value === null) return '-'
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value / 100)
}
</script>

<template>
  <section class="reconciliation-panel" aria-labelledby="reconciliation-title">
    <header class="workflow-header"><div><h3 id="reconciliation-title">对账</h3><p>下载与解析账单均在服务器执行，浏览器仅显示汇总和分页差异</p></div></header>
    <TopErrorMessage :message="errorMessage" />

    <div class="admin-toolbar workflow-toolbar">
      <el-select v-model="provider" data-test="reconciliation-provider" placeholder="选择支付渠道" aria-label="对账支付渠道">
        <el-option label="微信支付" value="WECHAT_PAY" /><el-option label="支付宝" value="ALIPAY" /><el-option label="本地模拟" value="MOCK" />
      </el-select>
      <el-input v-model="billDate" data-test="reconciliation-date" placeholder="YYYY-MM-DD" aria-label="账单日期" />
      <el-button data-test="start-reconciliation" type="primary" :loading="starting" :disabled="starting || !provider || !/^\d{4}-\d{2}-\d{2}$/.test(billDate)" @click="start"><Play :size="16" />开始对账</el-button>
    </div>

    <template v-if="run">
      <div class="run-banner">
        <div><span>渠道 / 账单日</span><strong>{{ run.provider }} · {{ run.billDate }}</strong></div>
        <div><span>任务状态</span><strong>{{ run.status }}</strong></div>
        <div><span>账单记录</span><strong>{{ run.summary.totalRecords }}</strong></div>
        <div><span>匹配记录</span><strong>{{ run.summary.matchedCount }}</strong></div>
        <div><span>差异总数</span><strong>{{ run.summary.differenceCount }}</strong></div>
        <div><span>自动解决</span><strong>{{ run.summary.autoResolvedCount }}</strong></div>
        <div><span>待处理</span><strong>{{ run.summary.openCount }}</strong></div>
      </div>
      <div v-if="Object.keys(run.summary.differenceCounts).length" class="difference-summary" aria-label="差异类型汇总">
        <el-tag v-for="(count, type) in run.summary.differenceCounts" :key="type" type="warning" effect="plain">{{ type }} · {{ count }}</el-tag>
      </div>
      <el-skeleton v-if="loadingItems" :rows="4" animated />
      <div v-else class="table-scroll">
        <table class="admin-table"><thead><tr><th>渠道记录</th><th>平台交易 / 退款</th><th>差异类型</th><th>渠道金额</th><th>平台金额</th><th>状态</th><th>创建时间</th></tr></thead><tbody>
          <tr v-for="item in run.items.items" :key="item.id">
            <td><code>{{ item.providerRecordId }}</code></td><td><code>{{ item.transactionNo || item.refundNo || '-' }}</code></td>
            <td><strong>{{ item.differenceType }}</strong></td><td>{{ amount(item.providerAmount) }}</td><td>{{ amount(item.platformAmount) }}</td>
            <td><el-tag size="small">{{ item.status }}</el-tag></td><td>{{ formatDateTime(item.createdAt) }}</td>
          </tr>
          <tr v-if="!run.items.items.length"><td colspan="7" class="admin-empty">没有对账差异</td></tr>
        </tbody></table>
      </div>
      <div class="admin-pagination"><span>共 {{ run.items.total }} 条差异</span><el-pagination data-test="reconciliation-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="run.items.page + 1" :page-size="run.items.size" :page-sizes="pageSizeOptions" :total="run.items.total" @size-change="changePageSize" @current-change="(value: number) => loadPage(value - 1)" /></div>
    </template>
    <div v-else class="reconciliation-empty"><Search :size="20" /><span>尚未启动对账任务</span></div>
  </section>
</template>

<style scoped>
.reconciliation-panel { min-width: 0; }
.workflow-header { padding: 12px 14px; border-bottom: 1px solid var(--app-border); }
.workflow-header h3 { margin: 0; font-size: 16px; letter-spacing: 0; }
.workflow-header p { margin: 3px 0 0; color: var(--app-muted); font-size: 12px; }
.workflow-toolbar { padding: 12px 14px 0; }
.run-banner { display: grid; border-block: 1px solid var(--app-border); grid-template-columns: repeat(7, minmax(0, 1fr)); }
.run-banner > div { min-width: 0; padding: 12px 14px; border-right: 1px solid var(--app-border); }
.run-banner > div:last-child { border-right: 0; }
.run-banner span, .run-banner strong { display: block; overflow-wrap: anywhere; }
.run-banner span { color: var(--app-muted); font-size: 12px; }
.run-banner strong { margin-top: 4px; font-variant-numeric: tabular-nums; }
.difference-summary { display: flex; flex-wrap: wrap; gap: 8px; padding: 12px 14px; border-bottom: 1px solid var(--app-border); }
.reconciliation-empty { display: flex; min-height: 160px; align-items: center; justify-content: center; gap: 8px; color: var(--app-muted); }
@media (max-width: 900px) { .run-banner { grid-template-columns: repeat(3, minmax(0, 1fr)); } .run-banner > div { border-bottom: 1px solid var(--app-border); } }
@media (max-width: 600px) { .run-banner { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
