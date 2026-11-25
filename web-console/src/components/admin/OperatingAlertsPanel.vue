<script setup lang="ts">
import type { AdminModel, AdminProvider } from '../../api/admin'
import type { AlertResolutionReason, OperatingAlert } from '../../api/operatingReports'

const props = withDefaults(defineProps<{
  alerts: OperatingAlert[]
  models?: AdminModel[]
  providers?: AdminProvider[]
  busyId?: string
  total: number
  page: number
  pageSize: number
  loading?: boolean
  status?: OperatingAlert['status']
}>(), {
  status: 'OPEN',
})
const emit = defineEmits<{
  acknowledge: [id: string]
  resolve: [id: string]
  locate: [alert: OperatingAlert]
  'page-change': [page: number]
  'size-change': [size: number]
}>()

const alertTypeLabels: Record<string, string> = {
  ABNORMAL_PROVIDER_COST: '供应商成本异常',
  PROVIDER_PRICE_CHANGED: '供应商价格变动',
  LOW_MARGIN_PACKAGE: '套餐利润率过低',
  ABNORMAL_COST: '成本异常',
  LOW_GROSS_MARGIN: '毛利率过低',
  CHANNEL_PRICE_CHANGE: '渠道价格变动',
}
const alertStatusLabels: Record<OperatingAlert['status'], string> = {
  OPEN: '待处理',
  ACKED: '已确认',
  RESOLVED: '已解决',
}
const currencyFormatters = new Map<string, Intl.NumberFormat>()

function alertTypeLabel(type: string) {
  return alertTypeLabels[type] || type
}

function alertStatusLabel(status: OperatingAlert['status']) {
  return alertStatusLabels[status] || status
}

function modelLabel(code: string | null, models: AdminModel[] | undefined) {
  if (!code) return '-'
  return models?.find((model) => model.id === code || model.code === code)?.displayName || code
}

function providerLabel(code: string | null, providers: AdminProvider[] | undefined) {
  if (!code) return '-'
  return providers?.find((provider) => provider.id === code || provider.code === code)?.name || code
}

function money(value: number, currency: string) {
  let formatter = currencyFormatters.get(currency)
  if (!formatter) {
    formatter = new Intl.NumberFormat('zh-CN', {
      style: 'currency', currency, currencyDisplay: 'narrowSymbol', minimumFractionDigits: 2,
    })
    currencyFormatters.set(currency, formatter)
  }
  return formatter.format((value || 0) / 100)
}

function metric(alert: OperatingAlert, value: number) {
  if (alert.type === 'LOW_MARGIN_PACKAGE' || alert.type === 'PROVIDER_PRICE_CHANGED') {
    return `${((value || 0) * 100).toFixed(2)}%`
  }
  return money(value, alert.currency || 'CNY')
}

/** 用通俗说法说明定时巡检为何能清除该告警。 */
function machineReasonText(reason: AlertResolutionReason | null | undefined) {
  if (reason === 'RECOVERED') return '毛利回到预警线以上'
  if (reason === 'NO_LONGER_PUBLISHED') return '该版本已下架'
  return ''
}

interface ResolutionView {
  /** 该行使用哪个徽章与 data-test 键。 */
  tone: 'machine' | 'operator' | 'unresolved' | 'unknown'
  label: string
  /** The full sentence an investigator reads, e.g. 已自动解除：毛利回到预警线以上 */
  sentence: string
}

/**
 * 区分巡检自动解决与人工解决。仍处于打开状态的告警
 * 按定义就是未解决；已解决但 API 未上报来源的告警
 * 保持「—」，不去猜测是谁（或什么）关闭了它。
 */
function resolutionView(alert: OperatingAlert): ResolutionView {
  if (alert.status !== 'RESOLVED') {
    return { tone: 'unresolved', label: '未解除', sentence: '告警尚未解除' }
  }
  const origin = alert.resolutionOrigin
  if (origin === 'MACHINE') {
    const reason = machineReasonText(alert.resolutionReason)
    return { tone: 'machine', label: '自动解除', sentence: reason ? `已自动解除：${reason}` : '已自动解除' }
  }
  if (origin === 'OPERATOR') {
    const reason = machineReasonText(alert.resolutionReason)
    return { tone: 'operator', label: '人工解除', sentence: reason ? `已人工解除：${reason}` : '已人工解除' }
  }
  return { tone: 'unknown', label: '—', sentence: '已解除，但接口未返回解除来源' }
}

const emptyText = () => ({
  OPEN: '当前没有待处理经营告警',
  ACKED: '当前没有已确认的经营告警',
  RESOLVED: '当前没有已解除的经营告警',
}[props.status])
</script>

<template>
  <section v-loading="loading" class="report-panel" aria-labelledby="alerts-title">
    <header><div><h3 id="alerts-title">经营异常告警</h3><p>价格变化、低毛利与供应商成本异常；解除方式区分计划任务自动解除与人工解除。</p></div></header>
    <div v-if="!alerts.length" class="report-empty">{{ emptyText() }}</div>
    <div v-else class="report-table-scroll">
      <table class="report-table">
        <thead><tr><th>类型</th><th>模型</th><th>供应商</th><th>阈值</th><th>观察值</th><th>状态</th><th>解除方式</th><th>操作</th></tr></thead>
        <tbody><tr v-for="alert in alerts" :key="alert.id">
          <td><span :title="alert.type">{{ alertTypeLabel(alert.type) }}</span></td>
          <td><span :title="alert.logicalModel || ''">{{ modelLabel(alert.logicalModel, models) }}</span></td>
          <td><span :title="alert.provider || ''">{{ providerLabel(alert.provider, providers) }}</span></td>
          <td>{{ metric(alert, alert.threshold) }}</td><td>{{ metric(alert, alert.observed) }}</td>
          <td>{{ alertStatusLabel(alert.status) }}</td>
          <td>
            <div class="alert-resolution" :data-test="`alert-resolution-${resolutionView(alert).tone}`">
              <span
                class="alert-resolution__badge"
                :class="`alert-resolution__badge--${resolutionView(alert).tone}`"
                :title="resolutionView(alert).sentence"
              >{{ resolutionView(alert).label }}</span>
              <span
                v-if="resolutionView(alert).tone === 'machine' || resolutionView(alert).tone === 'operator'"
                class="alert-resolution__reason"
                data-test="alert-resolution-reason"
              >{{ resolutionView(alert).sentence }}</span>
            </div>
          </td>
          <td class="report-actions">
            <button data-test="locate-alert" type="button" @click="emit('locate', alert)">查看详情</button>
            <button type="button" :disabled="busyId === alert.id || alert.status !== 'OPEN'" @click="emit('acknowledge', alert.id)">确认</button>
            <button type="button" :disabled="busyId === alert.id || alert.status === 'RESOLVED'" @click="emit('resolve', alert.id)">解决</button>
          </td>
        </tr></tbody>
      </table>
    </div>
    <el-pagination
      data-test="alerts-pagination"
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

<style scoped>
.alert-resolution { display: grid; gap: 3px; justify-items: start; }
.alert-resolution__badge {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: 4px;
  border: 1px solid transparent;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
}
.alert-resolution__badge--machine { color: var(--app-primary); background: var(--app-primary-soft); border-color: var(--app-primary-border); }
.alert-resolution__badge--operator { color: var(--app-warning-text); background: var(--app-warning-soft); border-color: var(--app-warning-border); }
.alert-resolution__badge--unresolved { color: var(--app-muted-strong); background: var(--app-surface-muted); border-color: var(--app-border); }
.alert-resolution__badge--unknown { color: var(--app-muted); background: transparent; border-color: var(--app-border); }
.alert-resolution__reason { color: var(--app-muted-strong); font-size: 11.5px; white-space: normal; }
</style>
