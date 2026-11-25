<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { AlertOctagon, RefreshCw } from 'lucide-vue-next'

import { getSettlementDeficit, type SettlementDeficit } from '../../api/admin'
import { formatDateTime } from '../../utils/dateTime'

/**
 * 结算垫付（platform 吸收的未收回金额）。
 *
 * 数字口径必须诚实：总额、积分、受影响请求数全部来自接口；
 * 接口返回 404/503（平台服务还没上线该报表，或网关不可达）时显示"暂时取不到数据"，
 * 绝不把失败渲染成 0 —— 0 会被读成"平台没有被垫付过"。
 */
const deficit = ref<SettlementDeficit | null>(null)
const loading = ref(true)
const unavailable = ref(false)
const errorMessage = ref('')

const integer = new Intl.NumberFormat('zh-CN')
const moneyFormatters = new Map<string, Intl.NumberFormat>()

/** 金额单位为分，沿用经营报表的货币格式。 */
function money(cents: number | null | undefined) {
  if (cents == null || !Number.isFinite(cents)) return '—'
  let formatter = moneyFormatters.get('CNY')
  if (!formatter) {
    formatter = new Intl.NumberFormat('zh-CN', {
      style: 'currency', currency: 'CNY', currencyDisplay: 'narrowSymbol', minimumFractionDigits: 2,
    })
    moneyFormatters.set('CNY', formatter)
  }
  return formatter.format(cents / 100)
}

const totalAmount = computed(() => (deficit.value ? money(deficit.value.totalAmount) : '—'))
const totalTokens = computed(() => (deficit.value ? integer.format(deficit.value.totalTokens) : '—'))
const affectedCount = computed(() => (deficit.value ? integer.format(deficit.value.count) : '—'))

/**
 * 接口没返回 truncated 时显示破折号：无法判断明细是否只是最近一小段，
 * 就不能替它宣称"全部明细"。
 */
const scopeLabel = computed(() => {
  const truncated = deficit.value?.truncated
  if (typeof truncated !== 'boolean') return '—'
  return truncated ? '仅最近若干条' : '全部明细'
})

async function load() {
  loading.value = true
  errorMessage.value = ''
  unavailable.value = false
  try {
    deficit.value = await getSettlementDeficit()
  } catch {
    deficit.value = null
    unavailable.value = true
    errorMessage.value = '结算垫付数据加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="admin-page settlement-deficit" aria-labelledby="settlement-deficit-title">
    <TopErrorMessage :message="errorMessage" />
    <header class="admin-page__header">
      <div>
        <h2 id="settlement-deficit-title">结算垫付</h2>
        <p>平台已向上游付出、但没能从客户收回的金额。这些钱是平台的损失，不是收入。</p>
      </div>
      <el-button data-test="deficit-refresh" :loading="loading" @click="load"><RefreshCw :size="16" />刷新</el-button>
    </header>

    <el-skeleton v-if="loading" data-test="deficit-loading" :rows="5" animated />

    <template v-else-if="unavailable">
      <div class="deficit-unavailable" data-test="deficit-unavailable" role="status">
        <AlertOctagon :size="18" />
        <div>
          <strong>暂时取不到结算垫付数据</strong>
          <p>金额不明不等于没有垫付：接口恢复后再看这里，不要按 0 处理。</p>
        </div>
      </div>
    </template>

    <template v-else-if="deficit">
      <div class="admin-metrics">
        <div class="admin-metric">
          <span>平台未收回金额（合计）</span>
          <strong data-test="deficit-total">{{ totalAmount }}</strong>
        </div>
        <div class="admin-metric">
          <span>受影响请求数</span>
          <strong data-test="deficit-count">{{ affectedCount }}</strong>
        </div>
        <div class="admin-metric">
          <span>未结算额度（积分/秒）</span>
          <strong data-test="deficit-tokens">{{ totalTokens }}</strong>
        </div>
        <div class="admin-metric">
          <span>明细范围</span>
          <strong data-test="deficit-scope">{{ scopeLabel }}</strong>
        </div>
      </div>

      <p class="deficit-note" data-test="deficit-note">
        平台已经为这些请求付过上游成本，但没有从客户收到对应的钱。
        <template v-if="deficit.truncated === true">下方只列出最近的部分请求，合计金额覆盖全部 {{ affectedCount }} 笔。</template>
      </p>

      <section class="admin-panel" aria-labelledby="deficit-items-title">
        <div class="admin-panel__header">
          <div><h3 id="deficit-items-title">最近未收回明细</h3><p>按发生时间倒序，金额单位元。</p></div>
        </div>
        <div class="table-scroll">
          <table class="admin-table settlement-deficit__table">
            <thead>
              <tr><th>发生时间</th><th>套餐</th><th>用户</th><th>预留编号</th><th>金额</th><th>额度</th></tr>
            </thead>
            <tbody>
              <tr v-for="item in deficit.items" :key="item.reservationId">
                <td>{{ formatDateTime(item.occurredAt) }}</td>
                <td><code>{{ item.planCode || '—' }}</code></td>
                <td><code>{{ item.userId || '—' }}</code></td>
                <td><code>{{ item.reservationId }}</code></td>
                <td class="settlement-deficit__amount">{{ money(item.amount) }}</td>
                <td>{{ integer.format(item.tokens) }}</td>
              </tr>
              <tr v-if="!deficit.items.length">
                <td data-test="deficit-empty" colspan="6" class="admin-empty">
                  {{ deficit.count === 0 ? '当前没有结算垫付记录' : '接口未返回明细，但合计仍有垫付金额' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.settlement-deficit { max-width: 1440px; min-width: 0; }
.deficit-unavailable {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 16px;
  color: var(--app-warning-text);
  background: var(--app-warning-soft);
  border: 1px solid var(--app-warning-border);
  border-radius: var(--app-radius);
}
.deficit-unavailable strong { display: block; font-size: 14px; }
.deficit-unavailable p { margin: 4px 0 0; color: var(--app-muted-strong); font-size: 12.5px; }
.deficit-note {
  margin: 0 0 12px;
  color: var(--app-muted-strong);
  font-size: 12.5px;
  line-height: 1.7;
}
.settlement-deficit__table code { font-variant-numeric: tabular-nums; }
.settlement-deficit__amount { color: var(--app-danger-text); font-weight: 700; }
.table-scroll { max-width: 100%; overflow-x: auto; }
</style>
