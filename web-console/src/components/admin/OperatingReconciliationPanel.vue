<script setup lang="ts">
import type { ReconciliationResult } from '../../api/operatingReports'
import { reconciliationTypeLabel } from '../../utils/reconciliationTypeLabel'

defineProps<{
  result: ReconciliationResult | null
  total: number
  page: number
  pageSize: number
  loading?: boolean
}>()
const emit = defineEmits<{
  locate: [item: ReconciliationResult['items'][number]]
  'page-change': [page: number]
  'size-change': [size: number]
}>()

const formatters = new Map<string, Intl.NumberFormat>()
function money(value: number, currency: string) {
  let formatter = formatters.get(currency)
  if (!formatter) {
    formatter = new Intl.NumberFormat('zh-CN', { style: 'currency', currency, currencyDisplay: 'narrowSymbol' })
    formatters.set(currency, formatter)
  }
  return formatter.format(value / 100)
}
</script>

<template>
  <section v-loading="loading" class="report-panel" aria-labelledby="reconciliation-title">
    <header><div><h3 id="reconciliation-title">经营对账差异</h3><p>财务金额按分精确匹配，分摊舍入单独展示。</p></div></header>
    <div v-if="!result?.items.length" class="report-empty">当前范围未发现对账差异</div>
    <div v-else class="report-table-scroll">
      <table class="report-table">
        <thead><tr><th>差异类型</th><th>来源</th><th>业务编号</th><th>源金额</th><th>事实金额</th><th>差额</th><th>操作</th></tr></thead>
        <tbody><tr v-for="item in result.items" :key="`${item.sourceService}:${item.sourceEventId}:${item.type}`">
          <td data-test="reconciliation-type" :title="item.type">{{ reconciliationTypeLabel(item.type) }}</td><td>{{ item.sourceService }}</td><td><code>{{ item.sourceEventId }}</code></td>
          <td>{{ money(item.sourceAmount, item.currency) }}</td><td>{{ money(item.factAmount, item.currency) }}</td><td>{{ money(item.differenceAmount, item.currency) }}</td>
          <td><button data-test="locate-difference" type="button" @click="emit('locate', item)">定位</button></td>
        </tr></tbody>
      </table>
    </div>
    <el-pagination
      data-test="reconciliation-pagination"
      class="report-pagination"
      background
      layout="total, sizes, prev, pager, next"
      :current-page="page + 1"
      :page-size="pageSize"
      :page-sizes="[5, 10, 20]"
      :total="total"
      @size-change="(value: number) => emit('size-change', value)"
      @current-change="(value: number) => emit('page-change', value)"
    />
  </section>
</template>
