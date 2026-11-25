<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { FilePlus2 } from 'lucide-vue-next'

import {
  listRefundPolicies,
  publishRefundPolicy,
  type PublishRefundPolicyRequest,
  type RefundPolicy,
} from '../../api/admin'
import { formatDateTime } from '../../utils/dateTime'

const policies = ref<RefundPolicy[]>([])
const loading = ref(true)
const publishing = ref(false)
const errorMessage = ref('')
const page = ref(0)
const pageSize = ref(20)
const pageSizeOptions = [5, 10, 20]
const total = ref(0)
const latestVersion = ref(0)

const form = reactive({
  version: '', feeBps: '', minimumFee: '', refundWindowDays: '', maximumUsageBps: '', effectiveAt: '',
})
const MAX_BACKEND_INT = 2_147_483_647
const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/

const command = computed<PublishRefundPolicyRequest>(() => ({
  version: Number(form.version),
  feeBps: Number(form.feeBps),
  minimumFee: Number(form.minimumFee),
  refundWindowDays: Number(form.refundWindowDays),
  maximumUsageBps: Number(form.maximumUsageBps),
  effectiveAt: form.effectiveAt.trim(),
}))
const canPublish = computed(() => {
  const value = command.value
  return [form.version, form.feeBps, form.minimumFee, form.refundWindowDays, form.maximumUsageBps, form.effectiveAt]
    .every((field) => field.trim().length > 0)
    && Number.isSafeInteger(value.version) && value.version > latestVersion.value && value.version <= MAX_BACKEND_INT
    && Number.isInteger(value.feeBps) && value.feeBps >= 0 && value.feeBps <= 10_000
    && Number.isSafeInteger(value.minimumFee) && value.minimumFee >= 0
    && Number.isInteger(value.refundWindowDays) && value.refundWindowDays > 0 && value.refundWindowDays <= 365
    && Number.isInteger(value.maximumUsageBps) && value.maximumUsageBps >= 0 && value.maximumUsageBps <= 10_000
    && ISO_INSTANT.test(value.effectiveAt) && Number.isFinite(Date.parse(value.effectiveAt))
    && Date.parse(value.effectiveAt) > Date.now()
})

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await listRefundPolicies(page.value, pageSize.value)
    policies.value = response.items
    total.value = response.total
    page.value = response.page
    latestVersion.value = response.items.reduce(
      (latest, policy) => Math.max(latest, policy.version),
      latestVersion.value,
    )
  } catch {
    errorMessage.value = '退款政策加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

async function publish() {
  if (!canPublish.value || publishing.value) return
  publishing.value = true
  errorMessage.value = ''
  try {
    await publishRefundPolicy(command.value)
    page.value = 0
    await load()
    Object.assign(form, {
      version: '', feeBps: '', minimumFee: '', refundWindowDays: '', maximumUsageBps: '', effectiveAt: '',
    })
  } catch {
    errorMessage.value = '退款政策发布失败，请检查版本号和生效时间。'
  } finally {
    publishing.value = false
  }
}

async function changePage(nextPage: number) {
  page.value = nextPage - 1
  await load()
}

async function changePageSize(value: number) {
  pageSize.value = value
  page.value = 0
  await load()
}

function amount(value: number) {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value / 100)
}

onMounted(load)
</script>

<template>
  <section class="policy-panel" aria-labelledby="refund-policy-title">
    <header class="workflow-header"><div><h3 id="refund-policy-title">退款策略</h3><p>已发布版本不可修改，新版本必须在未来生效</p></div></header>
    <TopErrorMessage :message="errorMessage" />

    <div class="policy-form" aria-label="发布退款政策">
      <label><span>版本号</span><el-input v-model="form.version" data-test="policy-version" type="number" min="1" placeholder="新版本" /></label>
      <label><span>手续费基点（BPS）</span><el-input v-model="form.feeBps" data-test="policy-fee-bps" type="number" min="0" max="10000" placeholder="0 - 10000" /></label>
      <label><span>最低手续费（分）</span><el-input v-model="form.minimumFee" data-test="policy-minimum-fee" type="number" min="0" placeholder="金额（分）" /></label>
      <label><span>退款窗口（天）</span><el-input v-model="form.refundWindowDays" data-test="policy-window-days" type="number" min="1" max="365" placeholder="1 - 365" /></label>
      <label><span>最大使用比例基点（BPS）</span><el-input v-model="form.maximumUsageBps" data-test="policy-maximum-usage-bps" type="number" min="0" max="10000" placeholder="0 - 10000" /></label>
      <label><span>生效时间</span><el-input v-model="form.effectiveAt" data-test="policy-effective-at" placeholder="YYYY-MM-DDTHH:mm:ssZ" /></label>
      <el-button data-test="publish-policy" class="publish-button" type="primary" :loading="publishing" :disabled="publishing || !canPublish" @click="publish"><FilePlus2 :size="16" />发布新版本</el-button>
    </div>

    <el-skeleton v-if="loading" :rows="4" animated />
    <template v-else>
      <div class="table-scroll">
        <table class="admin-table"><thead><tr><th>版本</th><th>手续费</th><th>最低手续费</th><th>退款窗口</th><th>最大使用量</th><th>生效时间</th><th>发布人</th><th>创建时间</th></tr></thead><tbody>
          <tr v-for="policy in policies" :key="policy.version">
            <td><strong>版本 {{ policy.version }}</strong></td><td>{{ policy.feeBps }} BPS</td><td>{{ amount(policy.minimumFee) }}</td>
            <td>{{ policy.refundWindowDays }} 天</td><td>{{ policy.maximumUsageBps }} BPS</td><td>{{ formatDateTime(policy.effectiveAt) }}</td><td><code>{{ policy.createdBy }}</code></td><td>{{ formatDateTime(policy.createdAt) }}</td>
          </tr>
          <tr v-if="!policies.length"><td colspan="8" class="admin-empty">暂无已发布退款政策</td></tr>
        </tbody></table>
      </div>
      <el-pagination
        class="policy-pagination"
        background
        layout="total, sizes, prev, pager, next"
        :current-page="page + 1"
        :page-size="pageSize"
        :page-sizes="pageSizeOptions"
        :total="total"
        @size-change="changePageSize"
        @current-change="changePage"
      />
    </template>
  </section>
</template>

<style scoped>
.policy-panel { min-width: 0; }
.workflow-header { padding: 12px 14px; border-bottom: 1px solid var(--app-border); }
.workflow-header h3 { margin: 0; font-size: 16px; letter-spacing: 0; }
.workflow-header p { margin: 3px 0 0; color: var(--app-muted); font-size: 12px; }
.policy-form { display: grid; align-items: end; gap: 12px; padding: 14px; border-bottom: 1px solid var(--app-border); grid-template-columns: repeat(3, minmax(0, 1fr)); }
.policy-form label { display: grid; min-width: 0; gap: 6px; color: var(--app-muted); font-size: 12px; }
.publish-button { justify-self: start; }
.policy-pagination { justify-content: flex-end; padding: 14px; }
@media (max-width: 900px) { .policy-form { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .policy-form { grid-template-columns: minmax(0, 1fr); } .publish-button { width: 100%; } }
</style>
