<script setup lang="ts">
import { CalendarClock, CirclePause, CirclePlay, Plus, RefreshCw, Send, X } from 'lucide-vue-next'
import type { NotificationBroadcast } from '../../api/admin'
import { formatDateTime } from '../../utils/dateTime'

defineProps<{ items: NotificationBroadcast[]; loading: boolean; total: number; page: number; pageSize: number; busyId: string }>()
defineEmits<{
  create: []
  refresh: []
  'page-change': [page: number]
  'size-change': [size: number]
  schedule: [item: NotificationBroadcast]
  publish: [item: NotificationBroadcast]
  pause: [item: NotificationBroadcast]
  resume: [item: NotificationBroadcast]
  cancel: [item: NotificationBroadcast]
}>()

/** 通知卡片按"轻量"处理：状态只用一枚低饱和标签 + 左侧发丝色轨，不做强色块 */
const statusMeta = (item: NotificationBroadcast) => {
  if (item.paused) return { label: '已暂停', tone: 'paused', glyph: '❙❙' }
  return ({
    DRAFT: { label: '草稿', tone: 'draft', glyph: '○' },
    SCHEDULED: { label: '已计划', tone: 'scheduled', glyph: '◷' },
    SENDING: { label: '发送中', tone: 'sending', glyph: '◐' },
    COMPLETED: { label: '已完成', tone: 'completed', glyph: '✓' },
    CANCELED: { label: '已取消', tone: 'canceled', glyph: '—' },
  })[item.status] ?? { label: item.status, tone: 'draft', glyph: '○' }
}
const audienceLabel = (value: NotificationBroadcast['audienceKind']) => value === 'ALL_ACTIVE_USERS' ? '全部活跃用户' : '指定用户'
</script>

<template>
  <section class="communications-panel ops-panel" aria-labelledby="broadcast-title">
    <header><h3 id="broadcast-title">维护广播</h3><div class="communications-actions"><el-button :loading="loading" @click="$emit('refresh')"><RefreshCw :size="15" />刷新</el-button><el-button type="primary" data-test="create-broadcast" @click="$emit('create')"><Plus :size="15" />新建广播</el-button></div></header>

    <ul class="notice-list">
      <li v-for="item in items" :key="item.id" class="notice-card" :class="`notice-card--${statusMeta(item).tone}`">
        <div class="notice-card__head">
          <strong>{{ item.title }}</strong>
          <span class="notice-card__status" :class="`notice-card__status--${statusMeta(item).tone}`"><i aria-hidden="true">{{ statusMeta(item).glyph }}</i>{{ statusMeta(item).label }}</span>
        </div>
        <p class="notice-card__body">{{ item.body }}</p>
        <dl class="notice-card__facts">
          <div><dt>业务编号</dt><dd class="ops-mono">{{ item.providerBusinessNo || '—' }}</dd></div>
          <div><dt>收件范围</dt><dd>{{ audienceLabel(item.audienceKind) }}</dd></div>
          <div><dt>收件人数</dt><dd class="ops-mono">{{ item.recipientCount }}</dd></div>
          <div><dt>计划时间</dt><dd class="ops-mono">{{ formatDateTime(item.scheduledAt) }}</dd></div>
          <div class="notice-card__facts-wide"><dt>维护时间</dt><dd>{{ item.maintainTime || '—' }}</dd></div>
        </dl>
        <div class="communications-row-actions notice-card__actions">
          <el-button v-if="item.status === 'DRAFT'" data-test="publish-broadcast" size="small" type="primary" :loading="busyId === item.id" @click="$emit('publish', item)"><Send :size="14" />发布广播</el-button>
          <el-button v-if="item.status === 'DRAFT'" size="small" :loading="busyId === item.id" @click="$emit('schedule', item)"><CalendarClock :size="14" />计划</el-button>
          <el-button v-if="['SCHEDULED', 'SENDING'].includes(item.status) && !item.paused" size="small" :loading="busyId === item.id" @click="$emit('pause', item)"><CirclePause :size="14" />暂停</el-button>
          <el-button v-if="item.paused" size="small" :loading="busyId === item.id" @click="$emit('resume', item)"><CirclePlay :size="14" />恢复</el-button>
          <el-button v-if="['DRAFT', 'SCHEDULED'].includes(item.status)" size="small" type="danger" plain :loading="busyId === item.id" @click="$emit('cancel', item)"><X :size="14" />取消</el-button>
        </div>
      </li>
      <li v-if="!items.length && !loading" class="communications-empty notice-list__empty">暂无广播记录</li>
    </ul>

    <footer><span>共 {{ total }} 条广播</span><el-pagination data-test="broadcast-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="page + 1" :page-size="pageSize" :page-sizes="[5, 10, 20]" :total="total" @size-change="$emit('size-change', $event)" @current-change="$emit('page-change', $event)" /></footer>
  </section>
</template>

<style scoped>
.ops-mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; letter-spacing: -0.01em; }

.notice-list { list-style: none; display: grid; gap: 10px; margin: 0; padding: 12px 14px; }

/* 轻量卡片：白底 + 发丝边框 + 左侧 2px 色轨，避免消息 App 的气泡/重色 */
.notice-card {
  display: grid; gap: 8px; padding: 12px 14px 12px 15px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-left: 2px solid var(--ops-closed-rail);
  border-radius: 10px;
  transition: border-color var(--app-motion-fast) ease, transform var(--app-motion-fast) ease;
}
.notice-card:hover { border-color: var(--app-border-strong); transform: translateX(2px); }
.notice-card--scheduled { border-left-color: var(--ops-open-rail); }
.notice-card--sending { border-left-color: var(--ops-processing-rail); }
.notice-card--completed { border-left-color: var(--ops-done-rail); }
.notice-card--paused { border-left-color: var(--ops-open-rail); border-left-style: dashed; }

.notice-card__head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.notice-card__head strong { min-width: 0; font-size: 14px; color: var(--app-fg); overflow-wrap: anywhere; }
.notice-card__status { display: inline-flex; align-items: center; gap: 5px; padding: 2px 9px; border: 1px solid transparent; border-radius: 999px; font-size: 11px; font-weight: 600; white-space: nowrap; }
.notice-card__status i { font-style: normal; font-size: 9px; line-height: 1; }
.notice-card__status--draft, .notice-card__status--canceled { color: var(--ops-closed); background: var(--ops-closed-soft); border-color: var(--app-border-subtle); }
.notice-card__status--scheduled, .notice-card__status--paused { color: var(--ops-open); background: var(--ops-open-soft); border-color: var(--app-warning-border); }
.notice-card__status--sending { color: var(--ops-processing); background: var(--ops-processing-soft); border-color: var(--app-primary-border); }
.notice-card__status--completed { color: var(--ops-done); background: var(--ops-done-soft); border-color: var(--app-success-border); }

.notice-card__body { margin: 0; font-size: 12px; line-height: 1.65; color: var(--app-muted-strong); overflow-wrap: anywhere; }

.notice-card__facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, max-content)); gap: 6px 22px; margin: 2px 0 0; }
.notice-card__facts-wide { grid-column: 1 / -1; }
.notice-card__facts div { display: grid; gap: 1px; }
.notice-card__facts dt { font-size: 10px; letter-spacing: 0.04em; color: var(--app-muted); }
.notice-card__facts dd { margin: 0; font-size: 12px; color: var(--app-fg); overflow-wrap: anywhere; }

.notice-card__actions { padding-top: 2px; border-top: 1px dashed var(--app-border); margin-top: 2px; padding-top: 8px; }
.notice-list__empty { list-style: none; padding: 28px !important; }

@media (prefers-reduced-motion: reduce) { .notice-card { transition: none; } .notice-card:hover { transform: none; } }
</style>
