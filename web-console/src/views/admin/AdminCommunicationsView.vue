<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Bell, Inbox, MessageSquareText, RefreshCw } from 'lucide-vue-next'
import BroadcastManager from '../../components/admin/BroadcastManager.vue'
import SupportTicketQueue from '../../components/admin/SupportTicketQueue.vue'
import { formatDateTime } from '../../utils/dateTime'
import {
  batchClaimAdminSupportTickets, businessNo, cancelNotificationBroadcast, claimAdminSupportTicket, closeAdminSupportTicket,
  completeAdminSupportTicket, createNotificationBroadcast, getAdminSupportTicket,
  getAdminSupportTicketAttachment, listAdminSupportTicketAttachments,
  listAdminSupportTickets, listNotificationBroadcasts, pauseNotificationBroadcast,
  publishNotificationBroadcast,
  processAdminSupportTicket, replyAdminSupportTicket, resolveAdminUserNumbers, resumeNotificationBroadcast,
  scheduleNotificationBroadcast, type AdminSupportAttachment, type AdminSupportTicket, type AdminUserIdentity, type BroadcastInput,
  type NotificationBroadcast,
} from '../../api/admin'
import { listNotifications, type NotificationItem } from '../../api/notifications'
import { useAuthStore } from '../../stores/auth'

const activeTab = ref<'broadcasts' | 'tickets'>('broadcasts')
const broadcasts = ref<NotificationBroadcast[]>([])
const broadcastTotal = ref(0)
const broadcastPage = ref(0)
const broadcastSize = ref(20)
const tickets = ref<AdminSupportTicket[]>([])
const ticketTotal = ref(0)
const ticketPage = ref(0)
const ticketSize = ref(20)
const selectedTicket = ref<AdminSupportTicket | null>(null)
const selectedTicketAttachments = ref<AdminSupportAttachment[]>([])
const ticketReplyBody = ref('')
const broadcastLoading = ref(false)
const ticketLoading = ref(false)
const busyId = ref('')
const error = ref('')
const broadcastStatus = ref('')
const ticketStatus = ref('')
const ticketQuery = ref('')
const ticketFrom = ref('')
const ticketTo = ref('')
const selectedTicketIds = ref<string[]>([])
const createVisible = ref(false)
const scheduleVisible = ref(false)
const scheduleDate = ref<Date | null>(null)
const scheduleTarget = ref<NotificationBroadcast | null>(null)
const scheduleIsResume = ref(false)
const audienceUserIds = ref('')
const createMaintainTimeRange = ref<[string, string] | null>(null)
const createForm = reactive<BroadcastInput>({ title: '', body: '', maintainTime: '', tenantId: null, audienceKind: 'ALL_ACTIVE_USERS', userIds: [] })

const ticketStats = ref<{ open: number; processing: number; closed: number; todayNew: number } | null>(null)
const ticketUsers = ref<Record<string, AdminUserIdentity>>({})
/** 近 100 条工单快照：用于「待办队列」与「相似问题推荐」，避免额外请求 */
const recentTickets = ref<AdminSupportTicket[]>([])
/** 我的未读站内通知（轻量卡片，不做消息 App 强视觉） */
const unreadNotices = ref<NotificationItem[]>([])

/** 当前登录管理员 ID：未注入 pinia 时安全降级，不阻塞页面渲染 */
function currentUserId() {
  try { return useAuthStore().user?.id ?? '' } catch { return '' }
}

/** 待办队列：指派给我的排前面，未领取的待处理工单补足 */
const todoTickets = computed(() => {
  const me = currentUserId()
  const mine = me
    ? recentTickets.value
      .filter(item => item.assigneeUserId === me && ['OPEN', 'PROCESSING'].includes(item.status))
      .map(item => ({ item, mine: true }))
    : []
  const unclaimed = recentTickets.value
    .filter(item => item.status === 'OPEN' && !item.assigneeUserId)
    .map(item => ({ item, mine: false }))
  return [...mine, ...unclaimed].slice(0, 6)
})

const noticeCategoryLabel = (category: NotificationItem['category']) => ({
  SECURITY: '安全', BILLING: '计费', USAGE: '用量', OPERATIONS: '运维', SUPPORT: '工单',
  SUBSCRIPTION: '订阅',
})[category] ?? '通知'

/** 标题分词：中文取二元组，英文取长度 ≥3 的词 */
function titleGrams(text: string) {
  const value = text.toLowerCase()
  const grams = new Set<string>()
  for (let index = 0; index < value.length - 1; index += 1) {
    const gram = value.slice(index, index + 2).trim()
    if (gram.length === 2) grams.add(gram)
  }
  value.split(/[^a-z0-9]+/).filter(word => word.length >= 3).forEach(word => grams.add(word))
  return grams
}

function gramOverlap(a: Set<string>, b: Set<string>) {
  let score = 0
  a.forEach(gram => { if (b.has(gram)) score += 1 })
  return score
}

/** 工单状态标签：与队列组件保持同一套色/文案 */
const statusMeta = (status: AdminSupportTicket['status']) => ({
  OPEN: { label: '待处理', tone: 'open' },
  PROCESSING: { label: '处理中', tone: 'processing' },
  COMPLETED: { label: '已完成', tone: 'done' },
  CLOSED: { label: '已关闭', tone: 'closed' },
})[status]

/** 底部推荐：选中工单时给相似问题，未选中时给近期已闭环参考 */
const similarityTitle = computed(() => selectedTicket.value ? '相似问题推荐' : '近期已闭环参考')
const similarTickets = computed(() => {
  if (!selectedTicket.value) {
    return recentTickets.value.filter(item => item.status === 'COMPLETED' || item.status === 'CLOSED').slice(0, 3)
  }
  const baseGrams = titleGrams(selectedTicket.value.title)
  return recentTickets.value
    .filter(item => item.id !== selectedTicket.value?.id)
    .map(item => ({ item, score: gramOverlap(baseGrams, titleGrams(item.title)) }))
    .filter(entry => entry.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, 3)
    .map(entry => entry.item)
})

async function loadUnreadNotices() {
  try {
    const page = await listNotifications({ read: false, page: 0, size: 5 })
    unreadNotices.value = page.data?.items ?? []
  } catch { unreadNotices.value = [] }
}

async function loadTicketStats() {
  try {
    const [open, processing, closed, recent] = await Promise.all([
      listAdminSupportTickets({ page: 0, size: 1, status: 'OPEN', query: '' }),
      listAdminSupportTickets({ page: 0, size: 1, status: 'PROCESSING', query: '' }),
      listAdminSupportTickets({ page: 0, size: 1, status: 'CLOSED', query: '' }),
      listAdminSupportTickets({ page: 0, size: 100, status: '', query: '' }),
    ])
    const now = new Date()
    const todayNew = recent.items.filter((item) => {
      const date = new Date(item.createdAt)
      return date.getFullYear() === now.getFullYear() && date.getMonth() === now.getMonth() && date.getDate() === now.getDate()
    }).length
    recentTickets.value = recent.items
    ticketStats.value = { open: open.total, processing: processing.total, closed: closed.total, todayNew }
  } catch { ticketStats.value = null }
}

async function loadTicketUsers() {
  const ids = new Set<string>()
  tickets.value.forEach((item) => {
    ids.add(item.userId)
    if (item.assigneeUserId) ids.add(item.assigneeUserId)
  })
  ;(selectedTicket.value?.comments || []).forEach((comment) => ids.add(comment.authorUserId))
  if (!ids.size) { ticketUsers.value = {}; return }
  try {
    const identities = await resolveAdminUserNumbers([...ids])
    const map: Record<string, AdminUserIdentity> = {}
    identities.forEach((identity) => { map[identity.userId] = identity })
    ticketUsers.value = map
  } catch { /* 保留已解析的映射 */ }
}

async function loadBroadcasts(targetPage = broadcastPage.value) {
  broadcastLoading.value = true
  error.value = ''
  try {
    const result = await listNotificationBroadcasts({ page: targetPage, size: broadcastSize.value, status: broadcastStatus.value })
    broadcasts.value = result.items
    broadcastTotal.value = result.total
    broadcastPage.value = result.page
  } catch { error.value = '广播记录加载失败，请稍后重试。' }
  finally { broadcastLoading.value = false }
}

function changeBroadcastStatus() {
  broadcastPage.value = 0
  return loadBroadcasts(0)
}

function changeBroadcastPage(value: number) {
  return loadBroadcasts(value - 1)
}

function changeBroadcastSize(value: number) {
  broadcastSize.value = value
  return loadBroadcasts(0)
}

async function loadTickets(targetPage = ticketPage.value) {
  ticketLoading.value = true
  error.value = ''
  try {
    const result = await listAdminSupportTickets({ page: targetPage, size: ticketSize.value, status: ticketStatus.value, query: ticketQuery.value.trim(), from: startOfDay(ticketFrom.value), to: endOfDay(ticketTo.value) })
    const lastPage = Math.max(0, Math.ceil(result.total / ticketSize.value) - 1)
    if (result.page > lastPage) {
      ticketPage.value = lastPage
      await loadTickets(lastPage)
      return
    }
    tickets.value = result.items
    selectedTicketIds.value = selectedTicketIds.value.filter((id) => result.items.some((item) => item.id === id && item.status === 'OPEN' && !item.assigneeUserId))
    ticketTotal.value = result.total
    ticketPage.value = result.page
    const selected = selectedTicket.value && result.items.find((item) => item.id === selectedTicket.value?.id)
    if (selected) await selectTicket(selected)
    else {
      selectedTicket.value = null
      selectedTicketAttachments.value = []
    }
    void loadTicketStats()
    void loadTicketUsers()
  } catch { error.value = '工单队列加载失败，请稍后重试。' }
  finally { ticketLoading.value = false }
}

function refreshAll() { return Promise.all([loadBroadcasts(), loadTickets(), loadUnreadNotices()]) }

async function selectTicket(ticket: AdminSupportTicket) {
  if (selectedTicket.value?.id !== ticket.id) ticketReplyBody.value = ''
  try {
    const [detail, attachments] = await Promise.all([
      getAdminSupportTicket(ticket.id),
      listAdminSupportTicketAttachments(ticket.id),
    ])
    selectedTicket.value = detail
    selectedTicketAttachments.value = attachments
    void loadTicketUsers()
  }
  catch { error.value = '工单详情加载失败，请重试。' }
}

function closeTicketDetail() {
  selectedTicket.value = null
  selectedTicketAttachments.value = []
  ticketReplyBody.value = ''
}

function startOfDay(value: string) { return value ? new Date(`${value}T00:00:00`).toISOString() : null }
function endOfDay(value: string) { return value ? new Date(`${value}T23:59:59.999`).toISOString() : null }

async function downloadTicketAttachment(item: AdminSupportAttachment) {
  if (!selectedTicket.value) return
  try {
    const blob = await getAdminSupportTicketAttachment(selectedTicket.value.id, item.id)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = item.filename
    anchor.click()
    URL.revokeObjectURL(url)
  } catch {
    ElMessage.error('附件下载失败，请重试。')
  }
}

async function submitBroadcast() {
  const maintainTime = createMaintainTimeRange.value?.join(' - ') ?? ''
  if (!createForm.title.trim() || !createForm.body.trim() || !maintainTime) {
    ElMessage.error('请完整填写广播标题、内容和维护时间。'); return
  }
  const userIds = audienceUserIds.value.split(/[\s,，]+/).map(value => value.trim()).filter(Boolean)
  if (createForm.audienceKind === 'USER_IDS' && !userIds.length) { ElMessage.error('指定用户广播至少需要填写一个用户 ID。'); return }
  busyId.value = 'create'
  try { await createNotificationBroadcast({ ...createForm, maintainTime, tenantId: createForm.tenantId || null, userIds: createForm.audienceKind === 'USER_IDS' ? [...new Set(userIds)] : [] }); createVisible.value = false; resetCreateForm(); await loadBroadcasts(); ElMessage.success('广播草稿已创建') }
  catch { ElMessage.error('广播草稿创建失败，请稍后重试。') }
  finally { busyId.value = '' }
}

function resetCreateForm() { audienceUserIds.value = ''; createMaintainTimeRange.value = null; Object.assign(createForm, { title: '', body: '', maintainTime: '', tenantId: null, audienceKind: 'ALL_ACTIVE_USERS', userIds: [] }) }

function schedule(item: NotificationBroadcast, resume = false) {
  scheduleTarget.value = item
  scheduleIsResume.value = resume
  scheduleDate.value = new Date(Date.now() + 5 * 60 * 1000)
  scheduleVisible.value = true
}

function changeTicketStatus() {
  ticketPage.value = 0
  return loadTickets(0)
}

function clearTicketFilters() {
  ticketStatus.value = ''
  ticketQuery.value = ''
  ticketFrom.value = ''
  ticketTo.value = ''
  void loadTickets(0)
}

function changeTicketSize(value: number) {
  ticketSize.value = value
  void loadTickets(0)
}

function changeTicketPage(value: number) {
  return loadTickets(value - 1)
}

function resetSchedule() {
  scheduleTarget.value = null
  scheduleIsResume.value = false
  scheduleDate.value = null
}

async function confirmSchedule() {
  const item = scheduleTarget.value
  const date = scheduleDate.value
  if (!item || !date || date.getTime() <= Date.now()) {
    ElMessage.error('计划发送时间必须晚于当前时间。')
    return
  }
  const isoTime = date.toISOString()
  const succeeded = await mutate(
    item.id,
    () => scheduleIsResume.value
      ? resumeNotificationBroadcast(item.id, isoTime)
      : scheduleNotificationBroadcast(item.id, isoTime),
    scheduleIsResume.value ? '广播已恢复' : '广播已计划',
  )
  if (succeeded) scheduleVisible.value = false
}

async function mutate(id: string, operation: () => Promise<unknown>, success: string, reload = loadBroadcasts) {
  busyId.value = id; error.value = ''
  try { await operation(); await reload(); ElMessage.success(success); return true }
  catch { error.value = `${success.replace('已', '')}失败，请刷新状态后重试。`; return false }
  finally { busyId.value = '' }
}

const publish = (item: NotificationBroadcast) => mutate(item.id, () => publishNotificationBroadcast(item.id), '广播已发布')
const pause = (item: NotificationBroadcast) => mutate(item.id, () => pauseNotificationBroadcast(item.id), '广播已暂停')
const cancel = async (item: NotificationBroadcast) => {
  const confirmed = await ElMessageBox.confirm('取消后不可恢复，确认取消这条广播吗？', '取消广播', { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '返回' }).then(() => true).catch(() => false)
  if (confirmed) await mutate(item.id, () => cancelNotificationBroadcast(item.id), '广播已取消')
}
const claim = (item: AdminSupportTicket) => mutate(item.id, () => claimAdminSupportTicket(item.id), '工单已领取', loadTickets)
async function batchClaim() {
  if (!selectedTicketIds.value.length) return
  const confirmed = await ElMessageBox.confirm(`确认领取选中的 ${selectedTicketIds.value.length} 条工单？`, '批量领取', { type: 'warning', confirmButtonText: '确认领取', cancelButtonText: '取消' }).then(() => true).catch(() => false)
  if (!confirmed) return
  await mutate('batch-claim', () => batchClaimAdminSupportTickets(selectedTicketIds.value), '工单已批量领取', async () => {
    selectedTicketIds.value = []
    await loadTickets()
  })
}
const processTicket = (item: AdminSupportTicket) => mutate(item.id, () => processAdminSupportTicket(item.id), '工单已进入处理', loadTickets)
const closeTicket = (item: AdminSupportTicket) => mutate(item.id, () => closeAdminSupportTicket(item.id), '工单已关闭', loadTickets)

async function reply(item: AdminSupportTicket, body: string) {
  const content = body.trim()
  if (!content) {
    ElMessage.error('请输入回复内容。')
    return
  }
  const succeeded = await mutate(item.id, () => replyAdminSupportTicket(item.id, content), '回复已发送', loadTickets)
  if (succeeded) ticketReplyBody.value = ''
}

async function complete(item: AdminSupportTicket) {
  const value = await ElMessageBox.prompt('请输入处理结果', `完成 ${item.ticketNo}`, { inputType: 'textarea', confirmButtonText: '完成工单', cancelButtonText: '取消', inputValidator: value => value.trim().length > 0 || '处理结果不能为空' }).catch(() => null)
  if (value?.value) await mutate(item.id, () => completeAdminSupportTicket(item.id, value.value.trim(), businessNo('support-ticket')), '工单已完成', loadTickets)
}

onMounted(() => { void Promise.all([loadBroadcasts(), loadTickets(), loadUnreadNotices()]) })
</script>

<template>
  <section class="admin-page communications-page ops-page" aria-labelledby="communications-title">
    <header class="ops-head">
      <div class="ops-head__title">
        <p class="ops-head__eyebrow">OPS DESK · 运营闭环</p>
        <h2 id="communications-title">通知与工单</h2>
        <p class="ops-head__sub">集中处理用户反馈、系统广播与问题闭环：发现 → 认领 → 处理 → 归档。</p>
      </div>
      <el-button :loading="broadcastLoading || ticketLoading" @click="refreshAll"><RefreshCw :size="16" />全部刷新</el-button>
    </header>
    <TopErrorMessage :message="error" />

    <div class="communications-tabs ops-tabs" role="tablist" aria-label="通知与工单视图"><button type="button" :aria-selected="activeTab === 'broadcasts'" @click="activeTab = 'broadcasts'">维护广播</button><button data-test="communications-tab-tickets" type="button" :aria-selected="activeTab === 'tickets'" @click="activeTab = 'tickets'">用户工单</button></div>

    <template v-if="activeTab === 'broadcasts'">
      <div class="admin-toolbar communications-filter"><label><span>广播状态</span><el-select v-model="broadcastStatus" aria-label="广播状态" placeholder="全部状态" @change="changeBroadcastStatus"><el-option label="全部状态" value="" /><el-option label="草稿" value="DRAFT" /><el-option label="已计划" value="SCHEDULED" /><el-option label="发送中" value="SENDING" /><el-option label="已完成" value="COMPLETED" /><el-option label="已取消" value="CANCELED" /></el-select></label></div>
      <BroadcastManager :items="broadcasts" :total="broadcastTotal" :page="broadcastPage" :page-size="broadcastSize" :loading="broadcastLoading" :busy-id="busyId" @create="createVisible = true" @refresh="loadBroadcasts" @page-change="changeBroadcastPage" @size-change="changeBroadcastSize" @publish="publish" @schedule="schedule" @pause="pause" @resume="item => schedule(item, true)" @cancel="cancel" />
    </template>

    <template v-else>
      <!-- 顶部：工单统计带 -->
      <section class="ops-stats" aria-label="工单统计">
        <article class="ops-stat ops-stat--open">
          <p class="ops-stat__label"><Inbox :size="14" />待处理</p>
          <strong class="ops-stat__value">{{ ticketStats ? ticketStats.open.toLocaleString() : '—' }}</strong>
          <p class="ops-stat__hint">等待认领与首次响应</p>
        </article>
        <article class="ops-stat ops-stat--processing">
          <p class="ops-stat__label"><MessageSquareText :size="14" />处理中</p>
          <strong class="ops-stat__value">{{ ticketStats ? ticketStats.processing.toLocaleString() : '—' }}</strong>
          <p class="ops-stat__hint">已受理，等待结论</p>
        </article>
        <article class="ops-stat ops-stat--closed">
          <p class="ops-stat__label"><RefreshCw :size="14" />已关闭</p>
          <strong class="ops-stat__value">{{ ticketStats ? ticketStats.closed.toLocaleString() : '—' }}</strong>
          <p class="ops-stat__hint">已闭环，可回溯</p>
        </article>
        <article class="ops-stat ops-stat--new">
          <p class="ops-stat__label"><Bell :size="14" />今日新增</p>
          <strong class="ops-stat__value">{{ ticketStats ? ticketStats.todayNew.toLocaleString() : '—' }}</strong>
          <p class="ops-stat__hint">近 24 小时提交量</p>
        </article>
      </section>

      <div class="ops-grid">
        <div class="ops-grid__main">
          <div class="admin-toolbar communications-filter ticket-filter"><label><span>工单搜索</span><el-input v-model="ticketQuery" clearable placeholder="工单编号或标题" @keyup.enter="loadTickets(0)" /></label><label><span>工单状态</span><el-select v-model="ticketStatus" aria-label="工单状态" placeholder="全部状态" @change="changeTicketStatus"><el-option label="全部状态" value="" /><el-option label="待处理" value="OPEN" /><el-option label="处理中" value="PROCESSING" /><el-option label="已完成" value="COMPLETED" /><el-option label="已关闭" value="CLOSED" /></el-select></label><label><span>开始日期</span><el-input v-model="ticketFrom" type="date" /></label><label><span>结束日期</span><el-input v-model="ticketTo" type="date" /></label><el-button @click="clearTicketFilters">清空</el-button><el-button type="primary" @click="loadTickets(0)">查询</el-button></div>
          <SupportTicketQueue :items="tickets" :total="ticketTotal" :page="ticketPage" :page-size="ticketSize" :selected="selectedTicket" :selected-ids="selectedTicketIds" :attachments="selectedTicketAttachments" :reply-draft="ticketReplyBody" :loading="ticketLoading" :busy-id="busyId" :users="ticketUsers" @refresh="loadTickets" @page-change="changeTicketPage" @size-change="changeTicketSize" @select="selectTicket" @collapse="closeTicketDetail" @update:reply-draft="ticketReplyBody = $event" @selection-change="selectedTicketIds = $event" @claim="claim" @batch-claim="batchClaim" @process="processTicket" @reply="reply" @complete="complete" @close="closeTicket" @download-attachment="downloadTicketAttachment" />
        </div>

        <!-- 右侧：待办 + 未读通知 -->
        <aside class="ops-aside">
          <section class="ops-panel-card">
            <header><h3><Inbox :size="14" />待我处理工单</h3><span class="ops-panel-card__count">{{ todoTickets.length }}</span></header>
            <ul v-if="todoTickets.length" class="ops-todo">
              <li v-for="entry in todoTickets" :key="entry.item.id">
                <button type="button" :data-test="`aside-ticket-${entry.item.id}`" @click="selectTicket(entry.item)">
                  <span class="ops-todo__title" :title="entry.item.title">{{ entry.item.title }}</span>
                  <span class="ops-todo__meta"><code class="ops-mono">{{ entry.item.ticketNo }}</code><span class="ops-todo__flag" :class="{ 'is-mine': entry.mine }">{{ entry.mine ? '我负责' : '待领取' }}</span></span>
                </button>
              </li>
            </ul>
            <p v-else class="ops-panel-card__empty">队列已清空，暂无待处理工单</p>
          </section>

          <section class="ops-panel-card">
            <header><h3><Bell :size="14" />最近未读通知</h3><a class="ops-panel-card__link" href="#/app/notifications">全部</a></header>
            <ul v-if="unreadNotices.length" class="ops-notice">
              <li v-for="item in unreadNotices" :key="item.id">
                <span class="ops-notice__cat">{{ noticeCategoryLabel(item.category) }}</span>
                <strong class="ops-notice__title" :title="item.title">{{ item.title }}</strong>
                <p class="ops-notice__summary">{{ item.summary }}</p>
                <time class="ops-mono">{{ formatDateTime(item.createdAt) }}</time>
              </li>
            </ul>
            <p v-else class="ops-panel-card__empty">没有未读通知</p>
          </section>
        </aside>
      </div>

      <!-- 底部：常见问题入口 + 相似问题推荐 -->
      <section class="ops-bottom" aria-label="处理参考">
        <div class="ops-panel-card ops-faq">
          <header><h3>常见问题入口</h3></header>
          <div class="ops-faq__grid">
            <article class="ops-faq__item"><strong>充值未到账</strong><p>引导用户核对支付渠道回执，确认钱包流水与支付记录后再补发。</p></article>
            <article class="ops-faq__item"><strong>模型调用失败</strong><p>先检查模型启用状态与路由熔断记录，再核对用户套餐额度是否耗尽。</p></article>
            <article class="ops-faq__item"><strong>套餐额度异常</strong><p>对比配额台账与调用记录，确认是否存在积分预占未释放的异常单。</p></article>
            <article class="ops-faq__item"><strong>账号申诉</strong><p>核实封禁原因与审计日志后，按解封流程处理并保留操作记录。</p></article>
          </div>
        </div>
        <div class="ops-panel-card ops-similar">
          <header><h3>{{ similarityTitle }}</h3></header>
          <ul v-if="similarTickets.length" class="ops-similar__list">
            <li v-for="item in similarTickets" :key="item.id">
              <button type="button" @click="selectTicket(item)">
                <span class="ops-similar__title" :title="item.title">{{ item.title }}</span>
                <span class="ops-similar__meta"><code class="ops-mono">{{ item.ticketNo }}</code><span class="ops-status" :class="`ops-status--${statusMeta(item.status).tone}`">{{ statusMeta(item.status).label }}</span></span>
              </button>
            </li>
          </ul>
          <p v-else class="ops-panel-card__empty">选中一条工单后，这里会推荐标题相近的历史工单</p>
        </div>
      </section>
    </template>

    <el-dialog v-model="createVisible" title="新建维护广播" width="min(560px, calc(100vw - 32px))" @closed="resetCreateForm"><div class="broadcast-form"><label>标题<el-input v-model="createForm.title" maxlength="120" /></label><label>广播内容<el-input v-model="createForm.body" type="textarea" :rows="4" maxlength="1000" /></label><label>维护时间说明<el-date-picker v-model="createMaintainTimeRange" type="datetimerange" value-format="YYYY-MM-DD HH:mm" format="YYYY-MM-DD HH:mm" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" /></label><label>收件范围<el-select v-model="createForm.audienceKind"><el-option label="全部活跃用户" value="ALL_ACTIVE_USERS" /><el-option label="指定用户" value="USER_IDS" /></el-select></label><label>租户 ID（可选）<el-input v-model="createForm.tenantId" maxlength="36" placeholder="留空表示不限租户" /></label><label v-if="createForm.audienceKind === 'USER_IDS'">用户 ID<el-input v-model="audienceUserIds" type="textarea" :rows="3" placeholder="多个 UUID 使用逗号或换行分隔" /></label></div><template #footer><el-button @click="createVisible = false">取消</el-button><el-button type="primary" :loading="busyId === 'create'" @click="submitBroadcast">保存草稿</el-button></template></el-dialog>
    <el-dialog v-model="scheduleVisible" :title="scheduleIsResume ? '恢复广播' : '计划广播'" width="min(420px, calc(100vw - 32px))" @closed="resetSchedule">
      <div class="broadcast-form"><label>计划发送时间<el-date-picker v-model="scheduleDate" data-test="schedule-date-time" type="datetime" format="YYYY-MM-DD HH:mm:ss" placeholder="选择计划发送时间" /></label></div>
      <template #footer><el-button @click="scheduleVisible = false">取消</el-button><el-button type="primary" :loading="Boolean(scheduleTarget && busyId === scheduleTarget.id)" @click="confirmSchedule">确认</el-button></template>
    </el-dialog>
  </section>
</template>

<style>
/* =========================================================
   通知与工单页 — 运维工单台 (Industrial / utilitarian)
   密度可控：发丝边框 + 状态色轨 + 等宽编号，层级靠边框不靠阴影
   ========================================================= */
.communications-page { position: relative; min-width: 0; max-width: 1520px; }
.ops-mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; letter-spacing: -0.01em; }

/* 网格底纹已全局应用到 body，此处不再重复绘制，避免与全局网格叠加 */

/* ===== 页头 ===== */
.ops-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 16px; }
.ops-head__eyebrow { margin: 0 0 4px; font-family: var(--font-mono); font-size: 11px; font-weight: 500; letter-spacing: 0.18em; color: var(--app-muted); }
.ops-head h2 { margin: 0; font-family: var(--font-display); font-size: 27px; font-weight: 400; letter-spacing: -0.02em; color: var(--app-fg); }
.ops-head__sub { margin: 6px 0 0; max-width: 62ch; font-size: 12.5px; line-height: 1.65; color: var(--app-muted-strong); }

/* ===== 标签页 ===== */
.communications-tabs.ops-tabs { display: inline-grid; grid-template-columns: repeat(2, minmax(120px, 1fr)); margin: 0 0 16px; padding: 3px; background: var(--app-surface-muted); border: 1px solid var(--app-border); border-radius: 10px; }
.ops-tabs button { min-height: 38px; padding: 0 18px; color: var(--app-muted-strong); background: transparent; border: 0; border-radius: 7px; cursor: pointer; font-weight: 600; font-size: 13px; transition: color var(--app-motion-fast) ease, background-color var(--app-motion-fast) ease; }
.ops-tabs button:hover { color: var(--app-fg); }
.ops-tabs button[aria-selected='true'] { color: var(--app-fg); background: var(--app-surface); box-shadow: var(--app-shadow-xs); }

/* ===== 统计带：左侧色轨 + 等宽大数字 ===== */
.ops-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.ops-stat { display: grid; gap: 2px; padding: 12px 14px 12px 16px; background: var(--app-surface); border: 1px solid var(--app-border); border-left: 3px solid var(--ops-closed-rail); border-radius: 10px; }
.ops-stat--open { border-left-color: var(--ops-open-rail); background: linear-gradient(90deg, var(--ops-open-soft) 0%, var(--app-surface) 42%); }
.ops-stat--processing { border-left-color: var(--ops-processing-rail); background: linear-gradient(90deg, var(--ops-processing-soft) 0%, var(--app-surface) 42%); }
.ops-stat--closed { border-left-color: var(--ops-closed-rail); background: linear-gradient(90deg, var(--ops-closed-soft) 0%, var(--app-surface) 42%); }
.ops-stat--new { border-left-color: var(--app-border-strong); background: linear-gradient(90deg, var(--app-surface-subtle) 0%, var(--app-surface) 42%); }
.ops-stat__label { display: flex; align-items: center; gap: 6px; margin: 0; font-size: 11px; font-weight: 600; letter-spacing: 0.06em; color: var(--app-muted-strong); }
.ops-stat__value { font-family: var(--font-display); font-size: 30px; font-weight: 400; line-height: 1.1; letter-spacing: -0.03em; font-variant-numeric: tabular-nums; color: var(--app-fg); }
.ops-stat--open .ops-stat__value { color: var(--ops-open); }
.ops-stat--processing .ops-stat__value { color: var(--ops-processing); }
.ops-stat--closed .ops-stat__value { color: var(--ops-closed); }
.ops-stat__hint { margin: 2px 0 0; font-size: 11px; color: var(--app-muted); }

/* ===== 主区 + 侧栏 ===== */
.ops-grid { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 14px; align-items: start; }
.ops-grid__main { min-width: 0; }
.ops-aside { display: grid; gap: 12px; position: sticky; top: 12px; margin-top: 48px; }

.ops-panel-card { padding: 12px 14px 14px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 10px; }
.ops-panel-card > header { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 10px; padding-bottom: 8px; border-bottom: 1px solid var(--app-border); }
.ops-panel-card h3 { display: flex; align-items: center; gap: 6px; margin: 0; font-size: 12.5px; font-weight: 700; color: var(--app-fg); }
.ops-panel-card__count { display: inline-flex; align-items: center; justify-content: center; min-width: 22px; height: 18px; padding: 0 6px; font-family: var(--font-mono); font-size: 11px; color: var(--app-muted-strong); background: var(--app-surface-muted); border-radius: 999px; }
.ops-panel-card__link { font-size: 11px; color: var(--app-primary); text-decoration: none; }
.ops-panel-card__link:hover { text-decoration: underline; }
.ops-panel-card__empty { margin: 8px 0 2px; font-size: 11.5px; line-height: 1.6; color: var(--app-muted); }

/* 待办列表 */
.ops-todo, .ops-notice, .ops-similar__list { list-style: none; margin: 0; padding: 0; display: grid; gap: 6px; }
.ops-todo button { display: grid; gap: 4px; width: 100%; padding: 8px 10px; text-align: left; background: var(--app-surface-subtle); border: 1px solid transparent; border-radius: 8px; cursor: pointer; transition: border-color var(--app-motion-fast) ease, transform var(--app-motion-fast) ease; }
.ops-todo button:hover { border-color: var(--app-border-strong); transform: translateX(2px); }
.ops-todo__title { font-size: 12.5px; font-weight: 600; color: var(--app-fg); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ops-todo__meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.ops-todo__meta code { font-size: 10.5px; color: var(--app-muted); }
.ops-todo__flag { padding: 1px 6px; font-size: 10px; font-weight: 600; color: var(--ops-open); background: var(--ops-open-soft); border-radius: 4px; }
.ops-todo__flag.is-mine { color: var(--ops-processing); background: var(--ops-processing-soft); }

/* 未读通知：轻量卡片，只有一条 2px 左轨，不做气泡 */
.ops-notice li { position: relative; padding: 8px 10px 8px 12px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 8px; }
.ops-notice li::before { content: ''; position: absolute; left: 0; top: 8px; bottom: 8px; width: 2px; background: var(--ops-processing-rail); border-radius: 2px; }
.ops-notice__cat { display: inline-block; margin-bottom: 3px; font-size: 10px; font-weight: 600; letter-spacing: 0.04em; color: var(--app-muted); }
.ops-notice__title { display: block; font-size: 12.5px; color: var(--app-fg); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ops-notice__summary { margin: 3px 0 4px; font-size: 11.5px; line-height: 1.6; color: var(--app-muted-strong); display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; }
.ops-notice time { font-size: 10.5px; color: var(--app-muted); }

/* ===== 底部：常见问题 + 相似问题 ===== */
.ops-bottom { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr); gap: 12px; margin-top: 14px; }
.ops-faq__grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.ops-faq__item { padding: 10px 12px; background: var(--app-surface-subtle); border: 1px solid var(--app-border); border-left: 2px solid var(--ops-done-rail); border-radius: 8px; }
.ops-faq__item strong { font-size: 12.5px; color: var(--app-fg); }
.ops-faq__item p { margin: 4px 0 0; font-size: 11.5px; line-height: 1.65; color: var(--app-muted-strong); }
.ops-similar__list button { display: grid; gap: 4px; width: 100%; padding: 8px 10px; text-align: left; background: var(--app-surface-subtle); border: 1px solid transparent; border-radius: 8px; cursor: pointer; }
.ops-similar__list button:hover { border-color: var(--app-border-strong); }
.ops-similar__title { font-size: 12.5px; color: var(--app-fg); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ops-similar__meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.ops-similar__meta code { font-size: 10.5px; color: var(--app-muted); }

/* ===== 状态标签（底部推荐复用，与队列组件同款） ===== */
.ops-status { display: inline-flex; align-items: center; padding: 1px 8px; border: 1px solid transparent; border-radius: 999px; font-size: 11px; font-weight: 600; white-space: nowrap; }
.ops-status--open { color: var(--ops-open); background: var(--ops-open-soft); border-color: var(--app-warning-border); }
.ops-status--processing { color: var(--ops-processing); background: var(--ops-processing-soft); border-color: var(--app-primary-border); }
.ops-status--closed { color: var(--ops-closed); background: var(--ops-closed-soft); border-color: var(--app-border-subtle); }
.ops-status--done { color: var(--ops-done); background: var(--ops-done-soft); border-color: var(--app-success-border); }

/* =========================================================
   以下为原有共享样式（队列/广播组件依赖），保持兼容
   ========================================================= */
.communications-filter { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 10px 12px; margin: 0 0 12px; }
.communications-filter label { display: grid; gap: 3px; color: var(--app-muted-strong); font-size: 12px; }
.communications-filter .el-select { width: 160px; }
.communications-filter.ticket-filter label { min-width: 150px; }
.communications-filter.ticket-filter label:first-child { min-width: 220px; }
.communications-panel { min-width: 0; overflow: hidden; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: var(--app-radius); }
.communications-panel > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 14px; border-bottom: 1px solid var(--app-border); }
.communications-panel h3, .communications-panel p { margin: 0; }.communications-panel h3 { font-size: 14px; }.communications-panel header p { margin-top: 2px; color: var(--app-muted); font-size: 12px; }
.communications-actions, .communications-row-actions { display: flex; flex-wrap: wrap; gap: 6px; }.communications-actions .el-button, .communications-row-actions .el-button { margin-left: 0; }
.communications-table-wrap { max-width: 100%; overflow-x: auto; }.communications-table-wrap:focus-visible { outline: 2px solid var(--app-primary); outline-offset: -2px; }
.communications-table { width: 100%; border-collapse: collapse; font-size: 13px; }.communications-table th, .communications-table td { padding: 10px 12px; border-bottom: 1px solid var(--app-surface-muted); text-align: left; vertical-align: middle; }.communications-table th { color: var(--app-muted); background: #f8fafc; font-size: 12px; white-space: nowrap; }.communications-table td strong { display: block; }.communications-table tr.selected { background: var(--app-primary-soft); }.communications-table .communications-row-actions { flex-wrap: nowrap; }.communications-table .communications-row-actions .el-button { white-space: nowrap; }.communications-table .ticket-checkbox { vertical-align: middle; line-height: 1; padding: 0; }
.communications-panel > footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 9px 12px; color: var(--app-muted); font-size: 12px; }.communications-empty { min-height: 100px; padding: 28px !important; color: var(--app-muted); text-align: center !important; }
.broadcast-form { display: grid; gap: 12px; }.broadcast-form label { display: grid; gap: 5px; color: var(--app-muted); font-size: 13px; }

/* =========================================================
   入场序列：一组错峰上浮，胜过零散微交互
   ========================================================= */
@keyframes ops-rise { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: none; } }
.ops-stat, .ops-panel-card { animation: ops-rise 420ms cubic-bezier(0.16, 1, 0.3, 1) both; }
.ops-stat:nth-child(2) { animation-delay: 70ms; }
.ops-stat:nth-child(3) { animation-delay: 140ms; }
.ops-stat:nth-child(4) { animation-delay: 210ms; }
.ops-aside .ops-panel-card:nth-child(2) { animation-delay: 120ms; }
.ops-bottom .ops-panel-card:nth-child(2) { animation-delay: 90ms; }

@media (prefers-reduced-motion: reduce) {
  .ops-stat, .ops-panel-card { animation: none; }
  .ops-todo button:hover, .ops-similar__list button:hover { transform: none; }
}

/* =========================================================
   响应式：构成决策有明确答案，而非简单堆叠
   ========================================================= */
@media (max-width: 1240px) {
  .ops-grid { grid-template-columns: 1fr; }
  .ops-aside { position: static; grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 1000px) {
  .ops-bottom { grid-template-columns: 1fr; }
  .ops-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 800px) {
  .ops-head { align-items: flex-start; flex-direction: column; }
  .communications-filter, .communications-filter .el-select, .communications-filter label { width: 100%; }
  .ops-aside { grid-template-columns: 1fr; }
  .ops-faq__grid { grid-template-columns: 1fr; }
  .ops-tabs { display: grid; width: 100%; }
  .ops-tabs button { min-height: 44px; }
}
@media (max-width: 600px) {
  .ops-stats { grid-template-columns: 1fr; }
  .communications-panel > header { align-items: stretch; flex-direction: column; }
  .communications-actions { display: grid; grid-template-columns: 1fr 1fr; }
  .communications-actions .el-button { width: 100%; }
}
</style>
