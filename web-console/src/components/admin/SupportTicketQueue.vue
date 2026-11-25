<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowLeft, Check, Eye, FileText, Play, RefreshCw, Send, UserCheck, X } from 'lucide-vue-next'
import { getAdminUserAvatar, type AdminSupportAttachment, type AdminSupportTicket, type AdminUserIdentity } from '../../api/admin'
import { formatDateTime } from '../../utils/dateTime'

const props = defineProps<{
  items: AdminSupportTicket[]
  total: number
  page: number
  pageSize: number
  selected: AdminSupportTicket | null
  selectedIds: string[]
  attachments: AdminSupportAttachment[]
  replyDraft: string
  loading: boolean
  busyId: string
  users: Record<string, AdminUserIdentity>
}>()

const emit = defineEmits<{
  refresh: []
  'page-change': [page: number]
  'size-change': [size: number]
  select: [item: AdminSupportTicket]
  collapse: []
  claim: [item: AdminSupportTicket]
  'batch-claim': []
  'selection-change': [ids: string[]]
  'update:reply-draft': [value: string]
  process: [item: AdminSupportTicket]
  reply: [item: AdminSupportTicket, body: string]
  complete: [item: AdminSupportTicket]
  close: [item: AdminSupportTicket]
  'download-attachment': [item: AdminSupportAttachment]
}>()

/** 状态元信息：颜色 + 文案 + 图标名三重编码，不单靠颜色传达状态 */
const statusMeta = (status: AdminSupportTicket['status']) => ({
  OPEN: { label: '待处理', tone: 'open', glyph: '○' },
  PROCESSING: { label: '处理中', tone: 'processing', glyph: '◐' },
  COMPLETED: { label: '已完成', tone: 'done', glyph: '✓' },
  CLOSED: { label: '已关闭', tone: 'closed', glyph: '—' },
})[status]
const statusTagType = (status: AdminSupportTicket['status']) => ({ OPEN: 'warning', PROCESSING: 'primary', COMPLETED: 'success', CLOSED: 'info' } as const)[status]
const ticketAttachments = () => props.attachments.filter(item => !item.commentId)
const commentAttachments = (commentId: string) => props.attachments.filter(item => item.commentId === commentId)
const claimable = (item: AdminSupportTicket) => item.status === 'OPEN' && !item.assigneeUserId
const claimableIds = () => props.items.filter(claimable).map(item => item.id)
const allClaimableSelected = () => claimableIds().length > 0 && claimableIds().every(id => props.selectedIds.includes(id))
const canReply = (item: AdminSupportTicket) => ['OPEN', 'PROCESSING'].includes(item.status)
const dialogVisible = computed({
  get: () => Boolean(props.selected),
  set: (value: boolean) => { if (!value) emit('collapse') },
})

const avatarUrls = ref<Record<string, string>>({})

watch(() => props.selected, async (ticket) => {
  Object.values(avatarUrls.value).forEach(url => URL.revokeObjectURL(url))
  avatarUrls.value = {}
  if (!ticket) return
  const ids = new Set<string>([ticket.userId])
  if (ticket.assigneeUserId) ids.add(ticket.assigneeUserId)
  ;(ticket.comments || []).forEach(comment => ids.add(comment.authorUserId))
  await Promise.all([...ids].map(async (id) => {
    try {
      const blob = await getAdminUserAvatar(id)
      if (blob.size > 0) avatarUrls.value[id] = URL.createObjectURL(blob)
    }
    catch { /* 无头像时回退首字母色块 */ }
  }))
}, { immediate: true })

onBeforeUnmount(() => {
  Object.values(avatarUrls.value).forEach(url => URL.revokeObjectURL(url))
})

const nameOf = (userId: string, fallback: string) => props.users[userId]?.displayName || fallback
const noOf = (userId: string) => props.users[userId]?.userNo || '—'
const initialOf = (userId: string, fallback: string) => {
  const name = props.users[userId]?.displayName || fallback
  return name.trim().charAt(0).toUpperCase() || '?'
}
const commentAuthor = (authorUserId: string, authorRole: string) => ({
  name: nameOf(authorUserId, authorRole === 'ADMIN' ? '管理员' : '用户'),
  isAdmin: authorRole === 'ADMIN',
})

const timelineSteps = () => {
  const selected = props.selected
  if (!selected) return []
  const reached = { OPEN: 0, PROCESSING: 1, COMPLETED: 2, CLOSED: 3 }[selected.status] ?? 0
  const acceptedAt = (selected.comments || []).find(comment => comment.authorRole === 'ADMIN')?.createdAt || ''
  return [
    { label: '提交工单', time: selected.createdAt, reached: true },
    { label: '受理处理', time: acceptedAt, reached: reached >= 1 },
    { label: '处理完成', time: selected.completedAt || '', reached: reached >= 2 },
    { label: '工单关闭', time: selected.status === 'CLOSED' ? selected.updatedAt : '', reached: reached >= 3 },
  ]
}

/* 窄屏切换为卡片流：同一份数据，两种密度 */
const isCompact = ref(false)
let compactQuery: MediaQueryList | null = null
function syncCompact() { isCompact.value = Boolean(compactQuery?.matches) }
onMounted(() => {
  if (typeof window.matchMedia !== 'function') return
  compactQuery = window.matchMedia('(max-width: 900px)')
  syncCompact()
  compactQuery.addEventListener('change', syncCompact)
})
onBeforeUnmount(() => compactQuery?.removeEventListener('change', syncCompact))

function toggleOne(id: string, checked: boolean) {
  const ids = new Set(props.selectedIds)
  if (checked) ids.add(id); else ids.delete(id)
  emit('selection-change', [...ids])
}

function toggleAll(checked: boolean) {
  emit('selection-change', checked ? claimableIds() : [])
}

function updateReplyDraft(value: string) {
  emit('update:reply-draft', value)
}

function submitReply() {
  if (props.selected && props.replyDraft.trim()) emit('reply', props.selected, props.replyDraft)
}
</script>

<template>
  <section class="communications-panel ops-panel" aria-labelledby="ticket-title">
    <header>
      <div><h3 id="ticket-title">用户工单</h3><p>领取后处理并回复用户，完成时会发送一次站内通知。</p></div>
      <div class="communications-actions">
        <el-button v-if="!selected" :disabled="!selectedIds.length" :loading="busyId === 'batch-claim'" @click="$emit('batch-claim')"><UserCheck :size="15" />批量领取（{{ selectedIds.length }}）</el-button>
        <el-button :loading="loading" @click="$emit('refresh')"><RefreshCw :size="15" />刷新</el-button>
      </div>
    </header>

    <template v-if="!selected">
      <!-- 宽屏：保留表格形态，状态轨 + 处理人列 -->
      <div v-if="!isCompact" class="communications-table-wrap" tabindex="0">
        <table class="communications-table ops-table">
          <thead><tr><th class="ticket-checkbox"><input type="checkbox" aria-label="全选当前页可领取工单" :checked="allClaimableSelected()" :disabled="!claimableIds().length" @change="toggleAll(($event.target as HTMLInputElement).checked)" /></th><th>工单编号</th><th>标题</th><th>状态</th><th>处理人</th><th>创建时间</th><th>更新时间</th><th class="ops-th--actions">操作</th></tr></thead>
          <tbody>
            <tr v-for="item in items" :key="item.id" class="ticket-table-row" :class="`ticket-row--${statusMeta(item.status).tone}`" tabindex="0" @click="$emit('select', item)" @keydown.enter.self="$emit('select', item)">
              <td class="ticket-checkbox"><input type="checkbox" :aria-label="`选择工单 ${item.ticketNo}`" :checked="selectedIds.includes(item.id)" :disabled="!claimable(item)" @click.stop @change="toggleOne(item.id, ($event.target as HTMLInputElement).checked)" /></td>
              <td><code class="ops-mono">{{ item.ticketNo }}</code></td>
              <td class="ops-table__title">{{ item.title }}</td>
              <td><span class="ops-status" :class="`ops-status--${statusMeta(item.status).tone}`"><i aria-hidden="true">{{ statusMeta(item.status).glyph }}</i>{{ statusMeta(item.status).label }}</span></td>
              <td><span class="ops-assignee"><i class="ops-assignee__dot" :class="{ 'is-empty': !item.assigneeUserId }" aria-hidden="true" />{{ item.assigneeUserId ? nameOf(item.assigneeUserId, '管理员') : '未领取' }}</span></td>
              <td><time class="ops-mono ops-time">{{ formatDateTime(item.createdAt) }}</time></td>
              <td><time class="ops-mono ops-time">{{ formatDateTime(item.updatedAt) }}</time></td>
              <td><div class="communications-row-actions">
                <el-button v-if="item.status === 'OPEN' && !item.assigneeUserId" :data-test="`claim-ticket-${item.id}`" size="small" :loading="busyId === item.id" @click.stop="$emit('claim', item)"><UserCheck :size="14" />领取</el-button>
                <el-button v-if="item.status === 'OPEN' && item.assigneeUserId" size="small" :loading="busyId === item.id" @click.stop="$emit('process', item)"><Play :size="14" />开始处理</el-button>
                <el-button :data-test="`ticket-detail-toggle-${item.id}`" size="small" aria-controls="support-ticket-detail" @click.stop="$emit('select', item)"><Eye :size="14" />查看详情</el-button>
              </div></td>
            </tr>
            <tr v-if="!items.length && !loading"><td colspan="8" class="communications-empty">暂无工单</td></tr>
          </tbody>
        </table>
      </div>

      <!-- 窄屏：卡片形态，标题 / 状态 / 处理人 / 创建时间 一眼可读 -->
      <ul v-else class="ops-cards">
        <li v-for="item in items" :key="item.id" class="ops-card" :class="`ops-card--${statusMeta(item.status).tone}`">
          <label class="ops-card__check"><input type="checkbox" :aria-label="`选择工单 ${item.ticketNo}`" :checked="selectedIds.includes(item.id)" :disabled="!claimable(item)" @change="toggleOne(item.id, ($event.target as HTMLInputElement).checked)" /></label>
          <button type="button" class="ops-card__body" @click="$emit('select', item)">
            <span class="ops-card__top"><code class="ops-mono">{{ item.ticketNo }}</code><span class="ops-status" :class="`ops-status--${statusMeta(item.status).tone}`"><i aria-hidden="true">{{ statusMeta(item.status).glyph }}</i>{{ statusMeta(item.status).label }}</span></span>
            <strong class="ops-card__title">{{ item.title }}</strong>
            <span class="ops-card__meta"><span class="ops-assignee"><i class="ops-assignee__dot" :class="{ 'is-empty': !item.assigneeUserId }" aria-hidden="true" />{{ item.assigneeUserId ? nameOf(item.assigneeUserId, '管理员') : '未领取' }}</span><time class="ops-mono">{{ formatDateTime(item.createdAt) }}</time></span>
          </button>
          <div class="ops-card__actions">
            <el-button v-if="item.status === 'OPEN' && !item.assigneeUserId" :data-test="`claim-ticket-${item.id}`" size="small" :loading="busyId === item.id" @click.stop="$emit('claim', item)"><UserCheck :size="14" />领取</el-button>
            <el-button v-if="item.status === 'OPEN' && item.assigneeUserId" size="small" :loading="busyId === item.id" @click.stop="$emit('process', item)"><Play :size="14" />开始处理</el-button>
            <el-button :data-test="`ticket-detail-toggle-${item.id}`" size="small" @click.stop="$emit('select', item)"><Eye :size="14" />详情</el-button>
          </div>
        </li>
        <li v-if="!items.length && !loading" class="communications-empty ops-cards__empty">暂无工单</li>
      </ul>
      <footer><span>共 {{ total }} 条工单</span><el-pagination data-test="support-ticket-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="page + 1" :page-size="pageSize" :page-sizes="[5, 10, 20]" :total="total" @size-change="$emit('size-change', $event)" @current-change="$emit('page-change', $event)" /></footer>
    </template>

    <el-dialog v-model="dialogVisible" title="工单详情" width="min(1020px, calc(100vw - 32px))" :close-on-click-modal="false" @closed="$emit('collapse')">
      <div v-if="selected" id="support-ticket-detail" class="ticket-detail">
        <div class="ticket-detail__header">
          <div class="ticket-detail__headline">
            <code class="ticket-detail__no ops-mono">{{ selected.ticketNo }}</code>
            <span class="ops-status" :class="`ops-status--${statusMeta(selected.status).tone}`"><i aria-hidden="true">{{ statusMeta(selected.status).glyph }}</i>工单状态：{{ statusMeta(selected.status).label }}</span>
          </div>
          <div class="ticket-detail__headmeta">
            <span>创建 <time class="ops-mono">{{ formatDateTime(selected.createdAt) }}</time></span>
            <span>更新 <time class="ops-mono">{{ formatDateTime(selected.updatedAt) }}</time></span>
          </div>
        </div>
        <h4 class="ticket-detail__title">{{ selected.title }}</h4>
        <p class="ticket-detail__body">{{ selected.body }}</p>
        <div v-if="ticketAttachments().length" class="admin-ticket-attachments ticket-detail__attachments">
          <button v-for="item in ticketAttachments()" :key="item.id" type="button" @click="$emit('download-attachment', item)"><FileText :size="14" />{{ item.filename }}</button>
        </div>

        <div class="ticket-detail__layout">
          <div class="ticket-detail__main">
            <h5 class="ticket-detail__section-title">回复记录<span class="ticket-detail__count">{{ selected.comments?.length || 0 }}</span></h5>
            <div class="ticket-thread" tabindex="0">
              <article v-for="comment in selected.comments || []" :key="comment.id" class="ticket-thread__item" :class="commentAuthor(comment.authorUserId, comment.authorRole).isAdmin ? 'is-admin' : 'is-user'">
                <span class="ticket-thread__avatar" aria-hidden="true">
                  <img v-if="avatarUrls[comment.authorUserId]" :src="avatarUrls[comment.authorUserId]" alt="">
                  <template v-else>{{ initialOf(comment.authorUserId, commentAuthor(comment.authorUserId, comment.authorRole).isAdmin ? '管' : '用') }}</template>
                </span>
                <div class="ticket-thread__content">
                  <header>
                    <strong>{{ commentAuthor(comment.authorUserId, comment.authorRole).name }}</strong>
                    <span class="ticket-thread__badge">{{ commentAuthor(comment.authorUserId, comment.authorRole).isAdmin ? '处理方' : '提交方' }}</span>
                    <time class="ops-mono" :datetime="comment.createdAt">{{ formatDateTime(comment.createdAt) }}</time>
                  </header>
                  <p>{{ comment.body }}</p>
                  <div v-if="commentAttachments(comment.id).length" class="admin-ticket-attachments">
                    <button v-for="item in commentAttachments(comment.id)" :key="item.id" type="button" @click="$emit('download-attachment', item)"><FileText :size="14" />{{ item.filename }}</button>
                  </div>
                </div>
              </article>

              <p v-if="!selected.comments?.length" class="ticket-thread__empty">暂无回复记录，可在下方补充说明。</p>
            </div>

            <div v-if="canReply(selected)" class="ticket-detail__reply">
              <el-input data-test="ticket-reply-input" :model-value="replyDraft" type="textarea" :rows="3" maxlength="2000" show-word-limit resize="none" placeholder="补充复现步骤或其他信息" @update:model-value="updateReplyDraft" />
              <div class="ticket-detail__reply-actions">
                <el-button text><FileText :size="14" />添加附件</el-button>
                <div class="ticket-detail__reply-btns">
                  <el-button v-if="selected.status === 'COMPLETED'" :loading="busyId === selected.id" @click="$emit('close', selected)"><X :size="14" />关闭工单</el-button>
                  <el-button v-if="selected.status === 'PROCESSING'" type="primary" :loading="busyId === selected.id" @click="$emit('complete', selected)"><Check :size="14" />完成</el-button>
                  <el-button data-test="send-ticket-reply" type="primary" :disabled="!replyDraft.trim()" :loading="busyId === selected.id" @click="submitReply"><Send :size="14" />提交说明</el-button>
                </div>
              </div>
            </div>
            <div v-else class="ticket-detail__closed">当前工单已{{ selected.status === 'CLOSED' ? '关闭' : '完成' }}，无法继续回复。</div>
          </div>

          <aside class="ticket-detail__side">
            <section class="ops-side-block">
              <h6>关联用户信息</h6>
              <div class="ops-identity">
                <span class="ops-identity__avatar" aria-hidden="true">
                  <img v-if="avatarUrls[selected.userId]" :src="avatarUrls[selected.userId]" alt="">
                  <template v-else>{{ initialOf(selected.userId, '用') }}</template>
                </span>
                <div>
                  <strong>{{ nameOf(selected.userId, '提交用户') }}</strong>
                  <span class="ops-mono">编号 {{ noOf(selected.userId) }}</span>
                </div>
              </div>
              <dl class="ops-kv">
                <div><dt>提交人 ID</dt><dd class="ops-mono">{{ selected.userId }}</dd></div>
                <div><dt>当前处理人</dt><dd>{{ selected.assigneeUserId ? nameOf(selected.assigneeUserId, '管理员') : '未领取' }}</dd></div>
              </dl>
            </section>

            <section class="ops-side-block">
              <h6>处理时间线</h6>
              <ol class="ticket-timeline">
                <li v-for="step in timelineSteps()" :key="step.label" :class="{ 'is-reached': step.reached }">
                  <i aria-hidden="true" />
                  <div><span>{{ step.label }}</span><time v-if="step.time" class="ops-mono">{{ formatDateTime(step.time) }}</time></div>
                </li>
              </ol>
            </section>

            <section class="ops-side-block">
              <h6>问题闭环</h6>
              <dl class="ops-kv">
                <div><dt>处理状态</dt><dd>{{ statusMeta(selected.status).label }}</dd></div>
                <div><dt>完成时间</dt><dd class="ops-mono">{{ selected.completedAt ? formatDateTime(selected.completedAt) : '—' }}</dd></div>
                <div><dt>业务编号</dt><dd class="ops-mono">{{ selected.completionBusinessNo || '—' }}</dd></div>
              </dl>
              <p class="ops-resolution"><span>处理结论 / 关闭原因</span>{{ selected.resolution || '暂未填写处理结论' }}</p>
            </section>
          </aside>
        </div>
      </div>
    </el-dialog>
  </section>
</template>

<style scoped>
/* =========================================================
   共享原子：等宽数字、状态标签、处理人
   ========================================================= */
.ops-mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; letter-spacing: -0.01em; }
.ops-status { display: inline-flex; align-items: center; gap: 5px; padding: 2px 9px 2px 7px; border: 1px solid transparent; border-radius: 999px; font-size: 12px; font-weight: 600; white-space: nowrap; }
.ops-status i { font-style: normal; font-size: 10px; line-height: 1; }
.ops-status--open { color: var(--ops-open); background: var(--ops-open-soft); border-color: var(--app-warning-border); }
.ops-status--processing { color: var(--ops-processing); background: var(--ops-processing-soft); border-color: var(--app-primary-border); }
.ops-status--closed { color: var(--ops-closed); background: var(--ops-closed-soft); border-color: var(--app-border-subtle); }
.ops-status--done { color: var(--ops-done); background: var(--ops-done-soft); border-color: var(--app-success-border); }
.ops-assignee { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--app-muted-strong); }
.ops-assignee__dot { width: 7px; height: 7px; border-radius: 50%; background: var(--ops-processing-rail); }
.ops-assignee__dot.is-empty { background: transparent; border: 1.5px dashed var(--ops-closed-rail); }

.admin-ticket-attachments { display: flex; flex-wrap: wrap; gap: 6px; margin: 8px 0; }
.admin-ticket-attachments button { display: inline-flex; min-height: 36px; align-items: center; gap: 6px; padding: 6px 8px; color: var(--app-primary); background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 6px; cursor: pointer; }
.admin-ticket-attachments button:focus-visible, .ticket-table-row:focus-visible { outline: 2px solid var(--app-primary); outline-offset: -2px; }
.ticket-checkbox { width: 42px; text-align: center !important; }
.ticket-checkbox input { width: 16px; height: 16px; margin: 0; vertical-align: middle; }

/* ===== 宽屏表格：左侧状态轨 + 悬停擦除 ===== */
.ops-table th, .ops-table td { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ops-table td, .ops-table th { position: relative; }
.ticket-table-row { cursor: pointer; transition: background-color var(--app-motion-fast) ease; }
.ticket-table-row::before {
  content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 3px;
  background: transparent; transition: background-color var(--app-motion-fast) ease;
}
.ticket-row--open::before { background: var(--ops-open-rail); }
.ticket-row--processing::before { background: var(--ops-processing-rail); }
.ticket-row--closed::before { background: var(--ops-closed-rail); }
.ticket-row--done::before { background: var(--ops-done-rail); }
.ticket-table-row:hover { background: var(--app-surface-subtle); }
.ops-table__title { max-width: 320px; }
.ops-table__title, .ops-time { font-size: 13px; }
.ops-time { color: var(--app-muted-strong); }
@media (prefers-reduced-motion: reduce) { .ticket-table-row, .ticket-table-row::before { transition: none; } }

/* ===== 窄屏卡片 ===== */
.ops-cards { list-style: none; display: grid; gap: 10px; margin: 0; padding: 12px 14px; }
.ops-card { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 4px 10px; padding: 12px 12px 12px 14px; background: var(--app-surface); border: 1px solid var(--app-border); border-left: 3px solid var(--ops-closed-rail); border-radius: 10px; }
.ops-card--open { border-left-color: var(--ops-open-rail); }
.ops-card--processing { border-left-color: var(--ops-processing-rail); }
.ops-card--done { border-left-color: var(--ops-done-rail); }
.ops-card__check { grid-row: span 2; padding-top: 2px; }
.ops-card__check input { width: 16px; height: 16px; }
.ops-card__body { display: grid; gap: 6px; min-width: 0; padding: 0; text-align: left; background: transparent; border: 0; cursor: pointer; }
.ops-card__top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.ops-card__top code { font-size: 11px; color: var(--app-muted); }
.ops-card__title { font-size: 14px; color: var(--app-fg); overflow-wrap: anywhere; }
.ops-card__meta { display: flex; flex-wrap: wrap; gap: 10px; font-size: 11px; color: var(--app-muted); }
.ops-card__actions { grid-column: 2; display: flex; flex-wrap: wrap; gap: 6px; margin-top: 4px; }
.ops-cards__empty { list-style: none; padding: 28px !important; }

/* =========================================================
   工单详情
   ========================================================= */
.ticket-detail { display: grid; gap: 0; }
.ticket-detail__header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 10px; }
.ticket-detail__headline { display: flex; align-items: center; gap: 10px; }
.ticket-detail__headmeta { display: flex; gap: 14px; font-size: 11px; color: var(--app-muted); }
.ticket-detail__no { font-size: 12px; color: var(--app-muted); }
.ticket-detail__title { margin: 0 0 8px; font-size: 17px; font-weight: 700; letter-spacing: -0.01em; color: var(--app-fg); }
.ticket-detail__body { margin: 0 0 12px; padding-bottom: 12px; border-bottom: 1px solid var(--app-border); font-size: 13px; line-height: 1.7; color: var(--app-muted-strong); white-space: pre-wrap; }
.ticket-detail__attachments { margin-bottom: 12px; }
.ticket-detail__layout { display: grid; grid-template-columns: minmax(0, 1fr) 272px; gap: 20px; align-items: start; }
.ticket-detail__main { min-width: 0; }
.ticket-detail__section-title { display: flex; align-items: center; gap: 8px; margin: 0 0 10px; font-size: 13px; font-weight: 700; letter-spacing: 0.02em; color: var(--app-fg); }
.ticket-detail__count { display: inline-flex; align-items: center; justify-content: center; min-width: 20px; height: 18px; padding: 0 6px; font-family: var(--font-mono); font-size: 11px; color: var(--app-muted-strong); background: var(--app-surface-muted); border-radius: 999px; }

/* ===== 回复记录：单列档案式，不做气泡 ===== */
.ticket-thread { display: grid; gap: 2px; max-height: 300px; min-height: 140px; overflow-y: auto; padding: 2px 2px 10px; margin-bottom: 12px; }
.ticket-thread__item { display: grid; grid-template-columns: 32px minmax(0, 1fr); gap: 10px; padding: 10px 0; border-bottom: 1px dashed var(--app-border); }
.ticket-thread__item:last-child { border-bottom: 0; }
.ticket-thread__avatar { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; font-size: 12px; font-weight: 600; color: #fff; background: #98a4b3; border-radius: 50%; overflow: hidden; }
.ticket-thread__item.is-admin .ticket-thread__avatar { background: var(--ops-processing-rail); }
.ticket-thread__item.is-user .ticket-thread__avatar { background: var(--app-warning-text); }
.ticket-thread__avatar img { width: 100%; height: 100%; object-fit: cover; }
.ticket-thread__content header { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 4px; }
.ticket-thread__content strong { font-size: 12px; color: var(--app-fg); }
.ticket-thread__badge { padding: 1px 7px; font-size: 10px; font-weight: 600; color: var(--app-muted-strong); background: var(--app-surface-muted); border-radius: 999px; }
.ticket-thread__item.is-admin .ticket-thread__badge { color: var(--ops-processing); background: var(--ops-processing-soft); }
.ticket-thread__content time { font-size: 11px; color: var(--app-muted); }
.ticket-thread__content p { margin: 0; font-size: 13px; line-height: 1.7; color: var(--app-fg); white-space: pre-wrap; overflow-wrap: anywhere; }
.ticket-thread__empty { margin: 24px 0; color: var(--app-muted); font-size: 12px; text-align: center; }

/* ===== 侧栏信息块 ===== */
.ticket-detail__side { display: grid; gap: 12px; }
.ops-side-block { padding: 12px 14px; background: var(--app-surface-subtle); border: 1px solid var(--app-border); border-radius: 10px; }
.ops-side-block h6 { margin: 0 0 10px; font-size: 11px; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: var(--app-muted); }
.ops-identity { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.ops-identity__avatar { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px; min-width: 34px; font-size: 13px; font-weight: 600; color: #fff; background: var(--app-warning-text); border-radius: 50%; overflow: hidden; }
.ops-identity__avatar img { width: 100%; height: 100%; object-fit: cover; }
.ops-identity div { display: grid; min-width: 0; }
.ops-identity strong { font-size: 13px; color: var(--app-fg); }
.ops-identity span { font-size: 11px; color: var(--app-muted); }
.ops-kv { display: grid; gap: 0; margin: 0; }
.ops-kv div { display: grid; grid-template-columns: 68px minmax(0, 1fr); gap: 8px; padding: 3px 0; }
.ops-kv dt { font-size: 11px; color: var(--app-muted); }
.ops-kv dd { min-width: 0; margin: 0; font-size: 12px; color: var(--app-fg); overflow-wrap: anywhere; }
.ops-resolution { margin: 10px 0 0; padding: 8px 10px; font-size: 11px; line-height: 1.65; color: var(--app-muted-strong); background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 6px; }
.ops-resolution span { display: block; margin-bottom: 3px; font-size: 10px; font-weight: 700; letter-spacing: 0.06em; color: var(--app-muted); }

/* ===== 处理时间线 ===== */
.ticket-timeline { list-style: none; display: grid; gap: 0; margin: 0; padding: 0; }
.ticket-timeline li { display: flex; gap: 9px; }
.ticket-timeline i { position: relative; display: block; width: 10px; height: 10px; margin-top: 4px; background: var(--app-surface); border: 2px solid var(--ops-closed-rail); border-radius: 50%; }
.ticket-timeline li:not(:last-child) i::after { content: ''; position: absolute; top: 12px; left: 3px; width: 2px; height: 16px; background: var(--ops-closed-rail); }
.ticket-timeline li.is-reached i { background: var(--ops-processing-rail); border-color: var(--ops-processing-rail); }
.ticket-timeline li.is-reached:not(:last-child) i::after { background: var(--ops-processing-soft); }
.ticket-timeline li > div { display: grid; gap: 1px; padding-bottom: 10px; }
.ticket-timeline span { font-size: 12px; font-weight: 600; color: var(--app-muted); }
.ticket-timeline li.is-reached span { color: var(--app-fg); }
.ticket-timeline time { font-size: 11px; color: var(--app-muted); }

/* ===== 底部回复区 ===== */
.ticket-detail__reply { border-top: 1px solid var(--app-border); padding-top: 12px; }
.ticket-detail__reply-actions { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; }
.ticket-detail__reply-btns { display: flex; gap: 8px; }

.ticket-detail__closed { border-top: 1px solid var(--app-border); padding-top: 12px; color: var(--app-muted); font-size: 12px; text-align: center; }

@media (max-width: 720px) {
  .ticket-detail__layout { grid-template-columns: 1fr; }
  .ticket-detail__side { order: -1; }
}
@media (max-width: 600px) {
  .ticket-detail__reply-actions { flex-direction: column; gap: 8px; }
  .ticket-detail__reply-btns { width: 100%; justify-content: flex-end; }
}
</style>
