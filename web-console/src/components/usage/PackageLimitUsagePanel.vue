<script setup lang="ts">
import { computed } from 'vue'
import { Gauge, RefreshCw, ShieldAlert } from 'lucide-vue-next'

import { isSecondDimension, type PackageLimitUsage, type PackageLimitWindow } from '../../api/platform'

/**
 * 用户持有的单个套餐的限流生效情况。
 *
 * 这里的每个数字都是平台服务上报的真实计数：
 * 字段缺失时渲染为「—」（或隐藏该区块），请求失败或
 * 超时时渲染为「用量暂时不可用」——绝不渲染成 0，
 * 因为那会被读成「没有任何请求被拦截」。
 */
const props = defineProps<{
  usage: PackageLimitUsage | null
  packageLabel?: string
  loading?: boolean
  unavailable?: boolean
}>()

const emit = defineEmits<{
  refresh: []
}>()

const integer = new Intl.NumberFormat('zh-CN')

/**
 * tokensPerMinute 系列限额同样带量纲：VIDEO_SECOND 套餐
 * 按每秒数计（SPM），TOKEN 套餐按每分钟积分计（TPM）。
 */
const perMinuteUnit = computed(() => (
  isSecondDimension(props.usage?.usageDimension) ? 'SPM（秒/分钟）' : 'TPM（积分/分钟）'
))

const dimensionNote = computed(() => (
  isSecondDimension(props.usage?.usageDimension)
    ? '该套餐按秒计费，每分钟额度以秒计。'
    : '该套餐按积分计费，每分钟额度以积分计。'
))

function count(value: number | null | undefined) {
  return value == null || !Number.isFinite(value) ? '—' : integer.format(value)
}

/** `limit === null` 是契约中的「不限量」；窗口缺失则表示未知。 */
function limitText(window: PackageLimitWindow | null | undefined) {
  if (!window) return '—'
  return window.limit == null ? '不限' : integer.format(window.limit)
}

function usedText(window: PackageLimitWindow | null | undefined) {
  if (!window) return '—'
  return count(window.used)
}

const blockedTotal = computed(() => (
  props.usage?.blockedLast24h == null ? '—' : count(props.usage.blockedLast24h)
))

const reasons = computed(() => {
  const value = props.usage?.blockedLast24HoursByReason
  if (!value) return []
  const entries: Array<{ key: string; label: string; value: number }> = []
  if (value.daily_request_limit_exceeded != null) {
    entries.push({ key: 'daily', label: '超出每日请求上限', value: value.daily_request_limit_exceeded })
  }
  if (value.tokens_per_minute_exceeded != null) {
    entries.push({ key: 'rate', label: '超出每分钟额度上限', value: value.tokens_per_minute_exceeded })
  }
  return entries
})
</script>

<template>
  <section class="limit-panel" data-test="limit-usage-panel" aria-labelledby="limit-usage-title">
    <header class="limit-panel__head">
      <div class="limit-panel__titles">
        <h3 id="limit-usage-title"><Gauge :size="15" />限流生效情况</h3>
        <p>
          <template v-if="packageLabel">{{ packageLabel }} · </template>
          {{ dimensionNote }}
        </p>
      </div>
      <button type="button" class="limit-panel__refresh" data-test="limit-usage-refresh" @click="emit('refresh')">
        <RefreshCw :size="14" />刷新
      </button>
    </header>

    <el-skeleton v-if="loading" data-test="limit-usage-skeleton" :rows="2" animated />

    <div v-else-if="unavailable || !usage" class="limit-panel__unavailable" data-test="limit-usage-unavailable">
      <ShieldAlert :size="16" />
      <span>暂时取不到用量</span>
      <span class="limit-panel__hint">网关不可达时限制仍然生效，稍后刷新即可看到真实计数。</span>
    </div>

    <div v-else class="limit-panel__grid">
      <div class="limit-cell" data-test="limit-usage-per-minute">
        <span class="limit-cell__lbl">本分钟用量 · {{ perMinuteUnit }}</span>
        <strong class="limit-cell__val">
          {{ usedText(usage.perMinute) }}<small> / {{ limitText(usage.perMinute) }}</small>
        </strong>
        <span class="limit-cell__foot">当前这一分钟已经用掉的额度与上限</span>
      </div>

      <div class="limit-cell" data-test="limit-usage-daily">
        <span class="limit-cell__lbl">今日请求</span>
        <strong class="limit-cell__val">
          {{ usedText(usage.daily) }}<small> / {{ limitText(usage.daily) }}</small>
        </strong>
        <span class="limit-cell__foot">今天发起的请求数与每日请求上限</span>
      </div>

      <div class="limit-cell" data-test="limit-usage-blocked">
        <span class="limit-cell__lbl">近 24 小时被拒绝</span>
        <strong class="limit-cell__val" :class="{ 'limit-cell__val--blocked': (usage.blockedLast24h || 0) > 0 }">
          {{ blockedTotal }}<small> 次请求</small>
        </strong>
        <ul v-if="reasons.length" class="limit-cell__reasons">
          <li v-for="reason in reasons" :key="reason.key" :data-test="`limit-usage-reason-${reason.key}`">
            {{ reason.label }}：{{ count(reason.value) }} 次
          </li>
        </ul>
        <span v-else class="limit-cell__foot">没有返回拒绝原因明细</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.limit-panel {
  margin-bottom: 20px;
  padding: 16px 20px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 12px;
}
.limit-panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
.limit-panel__titles h3 {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: var(--app-fg);
}
.limit-panel__titles p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--app-muted);
}
.limit-panel__refresh {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  flex-shrink: 0;
  color: var(--app-muted-strong);
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 8px;
  font: inherit;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
}
.limit-panel__refresh:hover { border-color: var(--app-primary); color: var(--app-primary); }

.limit-panel__unavailable {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 14px 16px;
  color: var(--app-warning-text);
  background: var(--app-warning-soft);
  border: 1px solid var(--app-warning-border);
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
}
.limit-panel__hint {
  color: var(--app-muted-strong);
  font-size: 12px;
  font-weight: 400;
}

.limit-panel__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.limit-cell {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  padding: 12px 14px;
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: 10px;
}
.limit-cell__lbl {
  font-size: 11.5px;
  color: var(--app-muted);
}
.limit-cell__val {
  font-size: 22px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--app-fg);
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.limit-cell__val small {
  margin-left: 2px;
  font-size: 12px;
  font-weight: 500;
  color: var(--app-muted);
}
.limit-cell__val--blocked { color: var(--app-danger-text); }
.limit-cell__foot {
  font-size: 11px;
  line-height: 1.5;
  color: var(--app-muted);
}
.limit-cell__reasons {
  margin: 0;
  padding: 0 0 0 2px;
  list-style: none;
  display: grid;
  gap: 3px;
  font-size: 11.5px;
  color: var(--app-muted-strong);
}

@media (max-width: 900px) {
  .limit-panel__grid { grid-template-columns: minmax(0, 1fr); }
}
</style>
