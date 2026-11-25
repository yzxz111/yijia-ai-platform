<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  AlertCircle,
  Calendar,
  CheckCircle2,
  ChevronRight,
  CircleDot,
  Clock,
  CreditCard,
  Download,
  FileText,
  Flame,
  HeadphonesIcon,
  Image,
  KeyRound,
  Lightbulb,
  MessageSquare,
  MessageSquarePlus,
  Paperclip,
  RefreshCw,
  Rocket,
  Send,
  Sparkles,
  Trash2,
  User,
  XCircle,
} from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'

import {
  closeSupportTicket,
  commentSupportTicket,
  createSupportTicket,
  getSupportTicket,
  getSupportTicketAttachment,
  listSupportTicketAttachments,
  listSupportTickets,
  type SupportAttachment,
  type SupportTicket,
  type SupportTicketStatus,
} from '../../api/support'
import { formatDateTime } from '../../utils/dateTime'

const MAX_ATTACHMENTS = 5
const MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024
const acceptedAttachmentTypes = new Set([
  'image/jpeg', 'image/png', 'image/gif', 'image/webp',
  'application/pdf', 'text/plain', 'application/zip', 'application/msword',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.ms-excel',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
])
const attachmentAccept = [...acceptedAttachmentTypes].join(',')

const statusLabels: Record<SupportTicketStatus, string> = {
  OPEN: '待处理', PROCESSING: '处理中', COMPLETED: '已完成', CLOSED: '已关闭',
}
type StatusFilter = 'all' | SupportTicketStatus
const statusFilter = ref<StatusFilter>('all')

const tickets = ref<SupportTicket[]>([])
const page = ref(0)
const pageSize = ref(5)
const pageSizeOptions = [5, 10, 20]
const total = ref(0)
const selected = ref<SupportTicket | null>(null)
const attachments = ref<SupportAttachment[]>([])
const loading = ref(false)
const detailLoading = ref(false)
const loadError = ref(false)
const detailOpen = ref(false)
const createOpen = ref(false)
const creating = ref(false)
const commenting = ref(false)
const closing = ref(false)
type TicketCategory = 'account' | 'api' | 'billing' | 'feature' | 'bug' | 'other'

interface TicketCategoryDef {
  key: TicketCategory
  label: string
  hint: string
  placeholder: { title: string; body: string }
}

const ticketCategories: TicketCategoryDef[] = [
  {
    key: 'account',
    label: '账号问题',
    hint: '登录、权限、安全相关',
    placeholder: {
      title: '简要描述账号问题',
      body: '例如无法登录、密码重置失败、权限异常等，\n请描述遇到的具体现象和时间。',
    },
  },
  {
    key: 'api',
    label: 'API 调用',
    hint: '接口报错、限流、参数',
    placeholder: {
      title: '简要描述 API 问题',
      body: '请提供接口地址、请求参数、返回结果（成功/失败）、\n请求时间等信息，方便排查。',
    },
  },
  {
    key: 'billing',
    label: '计费与充值',
    hint: '扣费、余额、套餐',
    placeholder: {
      title: '简要描述计费问题',
      body: '例如扣费异常、充值未到账、套餐变更等，\n请提供相关订单号或交易记录。',
    },
  },
  {
    key: 'bug',
    label: '功能异常',
    hint: '页面显示、功能不可用',
    placeholder: {
      title: '简要描述异常现象',
      body: '请描述期望行为 vs 实际行为、出现频率（偶发/必现）、\n以及复现步骤。',
    },
  },
  {
    key: 'feature',
    label: '功能建议',
    hint: '新功能、体验改进',
    placeholder: {
      title: '建议的功能或改进',
      body: '描述你期望的功能、使用场景以及带来的价值，\n我们会认真评估每一条建议。',
    },
  },
  {
    key: 'other',
    label: '其他',
    hint: '以上都不是',
    placeholder: {
      title: '简要描述你的问题',
      body: '请尽量详细描述，以便我们更好地帮助你。',
    },
  },
]

const createCategory = ref<TicketCategory>('account')
const createForm = reactive({ title: '', body: '' })
const createErrors = reactive({ title: '', body: '' })
const createFiles = ref<File[]>([])

const currentCategory = computed(() => ticketCategories.find((c) => c.key === createCategory.value) ?? ticketCategories[0])
const commentBody = ref('')
const commentError = ref('')
const commentFiles = ref<File[]>([])
const commentsRef = ref<HTMLElement | null>(null)
const previewOpen = ref(false)
const previewLoading = ref(false)
const previewUrl = ref('')
const previewName = ref('')
const previewAttachmentItem = ref<SupportAttachment | null>(null)

const filteredTickets = computed(() => {
  if (statusFilter.value === 'all') return tickets.value
  return tickets.value.filter((t) => t.status === statusFilter.value)
})

const selectedStatusLabel = computed(() => selected.value ? statusLabels[selected.value.status] : '')
const canClose = computed(() => selected.value && !['COMPLETED', 'CLOSED'].includes(selected.value.status))
const ticketAttachments = computed(() => attachments.value.filter((item) => !item.commentId))
const openTicketCount = computed(() => tickets.value.filter((t) => t.status === 'OPEN' || t.status === 'PROCESSING').length)
const closedTicketCount = computed(() => tickets.value.filter((t) => t.status === 'CLOSED' || t.status === 'COMPLETED').length)

const filterChips: { key: StatusFilter; label: string }[] = [
  { key: 'all', label: '全部' },
  { key: 'OPEN', label: '待处理' },
  { key: 'PROCESSING', label: '处理中' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'CLOSED', label: '已关闭' },
]

function statusType(status: SupportTicketStatus) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'CLOSED') return 'info'
  if (status === 'PROCESSING') return 'warning'
  return 'primary'
}

function statusClass(status: SupportTicketStatus) {
  return `status-${status.toLowerCase()}`
}

function statusIconComponent(status: SupportTicketStatus) {
  if (status === 'COMPLETED') return CheckCircle2
  if (status === 'CLOSED') return XCircle
  if (status === 'PROCESSING') return AlertCircle
  return CircleDot
}

function commentAttachments(commentId: string) {
  return attachments.value.filter((item) => item.commentId === commentId)
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

async function scrollCommentsToEnd() {
  await nextTick()
  if (commentsRef.value) commentsRef.value.scrollTop = commentsRef.value.scrollHeight
}

async function loadTickets(targetPage = page.value) {
  loading.value = true
  loadError.value = false
  try {
    const { data } = await listSupportTickets(targetPage, pageSize.value)
    tickets.value = data.items
    total.value = data.total
    page.value = data.page
    if (selected.value && !data.items.some((item) => item.id === selected.value?.id)) {
      selected.value = null
      detailOpen.value = false
    }
  } catch {
    tickets.value = []
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function loadAttachments(ticketId: string) {
  const { data } = await listSupportTicketAttachments(ticketId)
  attachments.value = data
}

async function selectTicket(ticket: SupportTicket) {
  detailOpen.value = true
  detailLoading.value = true
  selected.value = ticket
  attachments.value = []
  commentBody.value = ''
  commentError.value = ''
  commentFiles.value = []
  try {
    const [{ data }] = await Promise.all([
      getSupportTicket(ticket.id),
      loadAttachments(ticket.id),
    ])
    selected.value = data
    await scrollCommentsToEnd()
  } catch {
    ElMessage.error('工单详情加载失败，请重试。')
    detailOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

function validateCreate() {
  createErrors.title = createForm.title.trim().length >= 4 ? '' : '标题至少输入 4 个字符'
  createErrors.body = createForm.body.trim().length >= 10 ? '' : '问题描述至少输入 10 个字符'
  return !createErrors.title && !createErrors.body
}

function addFiles(event: Event, target: 'create' | 'comment') {
  const input = event.target as HTMLInputElement
  const current = target === 'create' ? createFiles : commentFiles
  const incoming = Array.from(input.files || [])
  input.value = ''
  if (current.value.length + incoming.length > MAX_ATTACHMENTS) {
    ElMessage.warning(`每次最多上传 ${MAX_ATTACHMENTS} 个附件。`)
    return
  }
  const invalid = incoming.find((file) => file.size > MAX_ATTACHMENT_BYTES || !acceptedAttachmentTypes.has(file.type))
  if (invalid) {
    ElMessage.warning(invalid.size > MAX_ATTACHMENT_BYTES
      ? `附件 ${invalid.name} 超过 5 MB。`
      : `不支持附件 ${invalid.name} 的文件类型。`)
    return
  }
  current.value = [...current.value, ...incoming]
}

function removeFile(target: 'create' | 'comment', index: number) {
  const current = target === 'create' ? createFiles : commentFiles
  current.value = current.value.filter((_, fileIndex) => fileIndex !== index)
}

async function submitCreate() {
  if (!validateCreate()) return
  creating.value = true
  try {
    const payload = { title: createForm.title.trim(), body: createForm.body.trim() }
    const { data } = await createSupportTicket(payload, createFiles.value)
    createOpen.value = false
    createCategory.value = 'account'
    createForm.title = ''
    createForm.body = ''
    createErrors.title = ''
    createErrors.body = ''
    createFiles.value = []
    await loadTickets(0)
    await selectTicket(data)
    ElMessage.success('工单已创建。')
  } catch {
    ElMessage.error('工单创建失败，请检查附件后重试。')
  } finally {
    creating.value = false
  }
}

function getCategoryIcon(key: TicketCategory) {
  switch (key) {
    case 'account': return KeyRound
    case 'api': return Rocket
    case 'billing': return CreditCard
    case 'bug': return Flame
    case 'feature': return Lightbulb
    default: return Sparkles
  }
}

async function submitComment() {
  const body = commentBody.value.trim()
  commentError.value = body.length >= 2 ? '' : '请至少输入 2 个字符'
  if (commentError.value || !selected.value) return
  commenting.value = true
  try {
    const ticketId = selected.value.id
    const { data } = await commentSupportTicket(ticketId, body, commentFiles.value)
    selected.value = data
    commentBody.value = ''
    commentFiles.value = []
    await loadAttachments(ticketId)
    await scrollCommentsToEnd()
    ElMessage.success('补充说明已提交。')
  } catch {
    ElMessage.error('补充说明提交失败，请检查附件后重试。')
  } finally {
    commenting.value = false
  }
}

async function closeTicket() {
  if (!selected.value || !canClose.value) return
  try {
    await ElMessageBox.confirm(
      '标记已解决后工单将关闭，如有问题需要重新沟通，请新建工单。确定继续吗？',
      '确认标记已解决',
      {
        confirmButtonText: '标记已解决',
        cancelButtonText: '再想想',
        type: 'warning',
        confirmButtonClass: 'confirm-solve-btn',
      },
    )
  } catch {
    return
  }
  closing.value = true
  try {
    const { data } = await closeSupportTicket(selected.value.id)
    selected.value = data
    tickets.value = tickets.value.map((item) => item.id === data.id ? data : item)
    ElMessage.success('工单已标记为已解决。')
  } catch {
    ElMessage.error('操作失败，请重试。')
  } finally {
    closing.value = false
  }
}

async function attachmentBlob(item: SupportAttachment) {
  if (!selected.value) throw new Error('ticket_not_selected')
  const { data } = await getSupportTicketAttachment(selected.value.id, item.id)
  return data
}

async function previewAttachment(item: SupportAttachment) {
  if (!item.contentType.startsWith('image/')) {
    await downloadAttachment(item)
    return
  }
  previewLoading.value = true
  previewName.value = item.filename
  previewAttachmentItem.value = item
  previewOpen.value = true
  try {
    const blob = await attachmentBlob(item)
    if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = URL.createObjectURL(blob)
  } catch {
    previewOpen.value = false
    ElMessage.error('附件预览失败，请重试。')
  } finally {
    previewLoading.value = false
  }
}

async function downloadAttachment(item: SupportAttachment) {
  try {
    const blob = await attachmentBlob(item)
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

function closePreview() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  previewName.value = ''
  previewAttachmentItem.value = null
}

function changePage(value: number) {
  return loadTickets(value - 1)
}

function changePageSize(value: number) {
  pageSize.value = value
  return loadTickets(0)
}

onMounted(() => void loadTickets())
onBeforeUnmount(closePreview)
</script>

<template>
  <section class="support-page">
    <!-- Page heading -->
    <div class="page-heading">
      <div>
        <h1>
          <span class="heading-icon"><HeadphonesIcon :size="18" /></span>
          支持工单
        </h1>
        <p>查看处理进度并补充问题信息</p>
      </div>
      <div class="heading-right">
        <div class="heading-stat">
          <span class="stat-num">{{ total }}</span>
          <span class="stat-label">全部工单</span>
        </div>
        <div class="heading-stat">
          <span class="stat-num stat-warn">{{ openTicketCount }}</span>
          <span class="stat-label">进行中</span>
        </div>
        <el-button type="primary" data-test="new-ticket" class="create-btn" @click="createOpen = true">
          <MessageSquarePlus :size="16" />新建工单
        </el-button>
      </div>
    </div>

    <!-- Ticket list card -->
    <el-card class="ticket-list-card" v-loading="loading">
      <template #header>
        <div class="card-header">
          <span>我的工单</span>
          <span v-if="total > 0" class="card-header-count">{{ total }}</span>
          <div class="filter-chips">
            <button
              v-for="chip in filterChips"
              :key="chip.key"
              type="button"
              class="filter-chip"
              :class="{ active: statusFilter === chip.key }"
              @click="statusFilter = chip.key"
            >{{ chip.label }}</button>
          </div>
        </div>
      </template>

      <div v-if="loadError" class="state-panel">
        <el-button @click="loadTickets"><RefreshCw :size="16" />重试</el-button>
      </div>
      <div v-else-if="!loading && tickets.length === 0" class="empty-state">
        <HeadphonesIcon :size="32" aria-hidden="true" />
        <span>暂无工单记录，遇到问题可随时创建</span>
      </div>
      <div v-else-if="filteredTickets.length === 0" class="empty-state">
        <CircleDot :size="28" aria-hidden="true" />
        <span>当前筛选条件下暂无工单</span>
      </div>
      <div v-else class="ticket-list">
        <button
          v-for="ticket in filteredTickets"
          :key="ticket.id"
          type="button"
          class="ticket-item"
          :class="statusClass(ticket.status)"
          data-test="ticket-row"
          @click="selectTicket(ticket)"
        >
          <span class="ticket-accent" aria-hidden="true" />
          <span class="ticket-status-icon" aria-hidden="true">
            <component :is="statusIconComponent(ticket.status)" :size="16" />
          </span>
          <div class="ticket-main">
            <div class="ticket-top">
              <strong class="ticket-title">{{ ticket.title }}</strong>
              <el-tag
                :type="statusType(ticket.status)"
                size="small"
                effect="light"
                round
                class="status-tag"
              >{{ statusLabels[ticket.status] }}</el-tag>
            </div>
            <div class="ticket-meta">
              <code class="ticket-no">{{ ticket.ticketNo }}</code>
              <span class="ticket-dot">·</span>
              <span>{{ formatDateTime(ticket.updatedAt) }}</span>
            </div>
          </div>
        </button>
      </div>

      <div v-if="!loadError && total" class="list-footer">
        <span class="pagination-total">共 {{ total }} 条</span>
        <el-pagination
          data-test="ticket-pagination"
          background
          :page-sizes="pageSizeOptions"
          layout="sizes, prev, pager, next"
          :current-page="page + 1"
          :page-size="pageSize"
          :total="total"
          @current-change="changePage"
          @size-change="changePageSize"
        />
      </div>
    </el-card>

    <!-- Ticket detail dialog -->
    <el-dialog v-model="detailOpen" width="min(820px, 94vw)" destroy-on-close class="ticket-detail-dialog">
      <template #header>
        <div class="detail-dialog-header" v-if="selected">
          <div class="detail-header-left">
            <div class="detail-header-icon" :class="statusClass(selected.status)">
              <component :is="statusIconComponent(selected.status)" :size="18" />
            </div>
            <div class="detail-header-text">
              <small class="ticket-detail-meta">{{ selected.ticketNo }}</small>
              <h3 class="ticket-detail-title">{{ selected.title }}</h3>
            </div>
          </div>
          <el-tag :type="statusType(selected.status)" effect="light" round class="detail-status-tag">
            <CheckCircle2 v-if="selected.status === 'COMPLETED'" :size="14" />
            <XCircle v-else-if="selected.status === 'CLOSED'" :size="14" />
            <CircleDot v-else :size="14" />{{ selectedStatusLabel }}
          </el-tag>
        </div>
      </template>

      <section v-loading="detailLoading" class="ticket-detail" aria-live="polite">
        <template v-if="selected">
          <!-- Meta info bar -->
          <div class="detail-meta-bar">
            <div class="meta-item">
              <Calendar :size="13" />
              <span>创建 {{ formatDateTime(selected.createdAt) }}</span>
            </div>
            <span class="meta-sep">·</span>
            <div class="meta-item">
              <Clock :size="13" />
              <span>更新 {{ formatDateTime(selected.updatedAt) }}</span>
            </div>
            <template v-if="ticketAttachments.length">
              <span class="meta-sep">·</span>
              <div class="meta-item">
                <Paperclip :size="13" />
                <span>{{ ticketAttachments.length }} 个附件</span>
              </div>
            </template>
            <template v-if="selected.comments?.length">
              <span class="meta-sep">·</span>
              <div class="meta-item">
                <MessageSquare :size="13" />
                <span>{{ selected.comments.length }} 条沟通</span>
              </div>
            </template>
          </div>

          <!-- Problem description -->
          <div class="detail-section">
            <div class="detail-section-title">
              <span class="detail-section-accent" />
              <FileText :size="14" />
              <span>问题描述</span>
            </div>
            <p class="ticket-detail-body">{{ selected.body }}</p>
            <div v-if="ticketAttachments.length" class="attachment-list ticket-attachments">
              <button v-for="item in ticketAttachments" :key="item.id" type="button" class="attachment-item" @click="previewAttachment(item)">
                <Image v-if="item.contentType.startsWith('image/')" :size="16" aria-hidden="true" />
                <FileText v-else :size="16" aria-hidden="true" />
                <span>{{ item.filename }}</span><small>{{ formatBytes(item.sizeBytes) }}</small>
              </button>
            </div>
          </div>

          <!-- Resolution -->
          <div v-if="selected.resolution" class="resolution">
            <div class="resolution-title">
              <CheckCircle2 :size="14" />
              <span>处理结果</span>
            </div>
            <p>{{ selected.resolution }}</p>
            <small v-if="selected.completedAt" class="resolution-time">{{ formatDateTime(selected.completedAt) }}</small>
          </div>

          <!-- Comments -->
          <div ref="commentsRef" class="comments">
            <div class="detail-section-title">
              <span class="detail-section-accent" />
              <MessageSquare :size="14" />
              <span>沟通记录</span>
              <span class="comments-count" v-if="selected.comments?.length">{{ selected.comments.length }}</span>
            </div>
            <div v-if="!selected.comments?.length" class="comments-empty">
              <AlertCircle :size="22" aria-hidden="true" />
              <span>暂无沟通记录，你可以在下方补充信息</span>
            </div>
            <article v-for="comment in selected.comments || []" :key="comment.id" :class="{ mine: comment.authorRole === 'USER' }">
              <div class="comment-bubble">
                <div class="comment-meta">
                  <strong class="comment-author">{{ comment.authorRole === 'USER' ? '我' : '支持人员' }}</strong>
                  <time :datetime="comment.createdAt">{{ formatDateTime(comment.createdAt) }}</time>
                </div>
                <p>{{ comment.body }}</p>
                <div v-if="commentAttachments(comment.id).length" class="attachment-list comment-attachments">
                  <button v-for="item in commentAttachments(comment.id)" :key="item.id" type="button" class="attachment-item" @click="previewAttachment(item)">
                    <Image v-if="item.contentType.startsWith('image/')" :size="15" aria-hidden="true" />
                    <FileText v-else :size="15" aria-hidden="true" />
                    <span>{{ item.filename }}</span><small>{{ formatBytes(item.sizeBytes) }}</small>
                  </button>
                </div>
              </div>
            </article>
          </div>

          <!-- Reply box -->
          <div v-if="canClose" class="reply-section">
            <div class="reply-header">
              <div class="reply-title">
                <Send :size="14" />
                <span>补充信息</span>
              </div>
              <span class="reply-hint">有新进展？在下方告诉我们</span>
            </div>
            <el-input v-model="commentBody" data-test="comment-input" type="textarea" :rows="3" maxlength="2000" show-word-limit placeholder="补充复现步骤、最新状态或其他相关信息" />
            <small v-if="commentError" class="validation-error">{{ commentError }}</small>
            <label class="upload-zone reply-upload">
              <input type="file" multiple :accept="attachmentAccept" @change="addFiles($event, 'comment')" />
              <div class="upload-zone-inner">
                <span class="upload-icon"><Paperclip :size="20" /></span>
                <div class="upload-text">
                  <strong>点击或拖拽文件到这里上传</strong>
                  <small>图片、PDF、Office、压缩包；最多 5 个，单个 ≤ 5 MB</small>
                </div>
              </div>
            </label>
            <div v-if="commentFiles.length" class="selected-files">
              <div v-for="(file, index) in commentFiles" :key="`${file.name}-${file.size}-${index}`">
                <Paperclip :size="15" />
                <span>{{ file.name }}</span>
                <small>{{ formatBytes(file.size) }}</small>
                <button type="button" :aria-label="`移除附件 ${file.name}`" @click="removeFile('comment', index)">
                  <Trash2 :size="15" />
                </button>
              </div>
            </div>
            <div class="reply-actions">
              <span class="action-spacer" />
              <el-button data-test="close-ticket" :loading="closing" @click="closeTicket">
                <CheckCircle2 :size="14" />标记已解决
              </el-button>
              <el-button type="primary" data-test="submit-comment" :loading="commenting" @click="submitComment">
                <Send :size="15" />提交说明
              </el-button>
            </div>
          </div>

          <div v-else class="reply-closed">
            <CheckCircle2 :size="18" />
            <span>该工单{{ selected.status === 'CLOSED' ? '已标记已解决' : '已完成' }}，如有新问题请新建工单</span>
          </div>
        </template>
      </section>
    </el-dialog>

    <!-- Create ticket dialog -->
    <el-dialog v-model="createOpen" width="min(760px, 94vw)" destroy-on-close class="create-ticket-dialog">
      <template #header>
        <div class="create-dialog-header">
          <div class="create-header-icon">
            <MessageSquarePlus :size="20" />
          </div>
          <div class="create-header-text">
            <h3>新建支持工单</h3>
            <p>补充细节，我们会尽快处理</p>
          </div>
        </div>
      </template>

      <form @submit.prevent="submitCreate" class="create-form">
        <!-- Category selection -->
        <div class="create-section">
          <div class="create-section-label">
            <Sparkles :size="14" />
            <span>问题类型</span>
          </div>
          <el-select
            v-model="createCategory"
            class="category-select"
            size="large"
            popper-class="category-select-dropdown"
            placeholder="请选择问题类型"
          >
            <el-option
              v-for="cat in ticketCategories"
              :key="cat.key"
              :value="cat.key"
              :label="cat.label"
            >
              <span class="select-option">
                <span class="category-icon" :class="`cat-${cat.key}`">
                  <component :is="getCategoryIcon(cat.key)" :size="15" />
                </span>
                <span class="select-option-text">
                  <strong>{{ cat.label }}</strong>
                  <small>{{ cat.hint }}</small>
                </span>
              </span>
            </el-option>
          </el-select>
        </div>

        <!-- Title -->
        <div class="create-section">
          <div class="create-section-label">
            <FileText :size="14" />
            <span>问题标题</span>
            <em class="required">*</em>
          </div>
          <el-input
            v-model="createForm.title"
            maxlength="120"
            show-word-limit
            :placeholder="currentCategory.placeholder.title"
            size="large"
            :class="{ 'has-error': createErrors.title }"
          />
          <div v-if="createErrors.title" class="create-error">{{ createErrors.title }}</div>
        </div>

        <!-- Description -->
        <div class="create-section">
          <div class="create-section-label">
            <AlertCircle :size="14" />
            <span>详细描述</span>
            <em class="required">*</em>
          </div>
          <el-input
            v-model="createForm.body"
            type="textarea"
            :rows="6"
            maxlength="4000"
            show-word-limit
            :placeholder="currentCategory.placeholder.body"
            :class="{ 'has-error': createErrors.body }"
          />
          <div v-if="createErrors.body" class="create-error">{{ createErrors.body }}</div>
        </div>

        <!-- Attachments -->
        <div class="create-section">
          <div class="create-section-label">
            <Paperclip :size="14" />
            <span>附件（可选）</span>
          </div>
          <label class="upload-zone">
            <input type="file" multiple :accept="attachmentAccept" @change="addFiles($event, 'create')" />
            <div class="upload-zone-inner">
              <span class="upload-icon"><Paperclip :size="22" /></span>
              <div class="upload-text">
                <strong>点击或拖拽文件到这里上传</strong>
                <small>图片、PDF、Office、压缩包；最多 5 个，单个 ≤ 5 MB</small>
              </div>
            </div>
          </label>
          <div v-if="createFiles.length" class="selected-files">
            <div v-for="(file, index) in createFiles" :key="`${file.name}-${file.size}-${index}`">
              <Paperclip :size="15" /><span>{{ file.name }}</span><small>{{ formatBytes(file.size) }}</small>
              <button type="button" :aria-label="`移除附件 ${file.name}`" @click="removeFile('create', index)">
                <Trash2 :size="15" />
              </button>
            </div>
          </div>
        </div>
      </form>

      <template #footer>
        <div class="create-dialog-footer">
          <span class="footer-hint">我们通常在 24 小时内响应</span>
          <div class="footer-actions">
            <el-button @click="createOpen = false">取消</el-button>
            <el-button type="primary" data-test="create-ticket" :loading="creating" @click="submitCreate">
              提交工单
              <ChevronRight :size="15" />
            </el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <!-- Preview dialog -->
    <el-dialog v-model="previewOpen" :title="previewName" width="min(820px, 94vw)" @closed="closePreview">
      <div v-loading="previewLoading" class="attachment-preview">
        <img v-if="previewUrl" :src="previewUrl" :alt="previewName" />
      </div>
      <template #footer>
        <el-button v-if="previewAttachmentItem" @click="downloadAttachment(previewAttachmentItem)"><Download :size="16" />下载</el-button>
        <el-button type="primary" @click="previewOpen = false">关闭</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
/* ========== 布局 ========== */
.support-page {
  display: grid;
  gap: 16px;
  min-width: 0;
  max-width: 1080px;
  margin: 0 auto;
}
h1, h2, h3, h4, p { margin: 0; }

/* ========== 卡片基础样式 ========== */
.support-page :deep(.el-card) {
  border-radius: 12px;
  border: 1px solid var(--app-border);
  box-shadow: none;
}
.support-page :deep(.el-card__header) {
  padding: 14px 20px;
  border-bottom: 1px solid var(--app-border);
}
.support-page :deep(.el-card__body) {
  padding: 16px 20px;
}

/* ========== 页面标题 ========== */
.page-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}
.page-heading h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: var(--app-fg);
  letter-spacing: -0.01em;
  display: flex;
  align-items: center;
  gap: 10px;
}
.heading-icon {
  display: inline-flex;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  background: var(--app-primary);
  color: #fff;
}
.page-heading p {
  margin: 4px 0 0 46px;
  font-size: 13px;
  color: var(--app-muted);
}
.heading-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.heading-stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-width: 60px;
  padding: 6px 10px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 8px;
  line-height: 1;
}
.stat-num {
  font-size: 17px;
  font-weight: 700;
  color: var(--app-fg);
  font-variant-numeric: tabular-nums;
}
.stat-warn { color: var(--app-accent); }
.stat-label {
  margin-top: 3px;
  font-size: 11px;
  color: var(--app-muted);
}
.create-btn { display: inline-flex; align-items: center; gap: 6px; }

/* ========== 卡片头部（自定义） ========== */
.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  font-weight: 600;
  color: var(--app-fg);
}
.card-header-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 18px;
  padding: 0 6px;
  border-radius: 9px;
  background: var(--app-surface-muted);
  color: var(--app-muted);
  font-size: 11px;
  font-weight: 600;
}
.filter-chips {
  display: flex;
  gap: 4px;
  margin-left: auto;
  flex-wrap: wrap;
}
.filter-chip {
  display: inline-flex;
  align-items: center;
  height: 26px;
  padding: 0 11px;
  border: 1px solid var(--app-border);
  border-radius: 13px;
  background: transparent;
  color: var(--app-muted-strong);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 140ms ease;
}
.filter-chip:hover {
  border-color: var(--app-primary);
  color: var(--app-primary);
}
.filter-chip.active {
  background: var(--app-primary);
  border-color: var(--app-primary);
  color: #fff;
}

/* ========== 工单列表 ========== */
.ticket-list {
  display: grid;
  gap: 10px;
}

.ticket-item {
  position: relative;
  display: flex;
  width: 100%;
  min-height: 60px;
  align-items: center;
  gap: 12px;
  padding: 14px 16px 14px 20px;
  color: var(--app-fg);
  border: 1px solid var(--app-border);
  border-radius: 10px;
  text-align: left;
  cursor: pointer;
  transition: border-color 160ms ease, box-shadow 160ms ease, background-color 160ms ease;
  background: var(--app-surface);
}
.ticket-item:hover {
  border-color: var(--app-primary);
  box-shadow: 0 2px 10px rgba(37, 99, 235, 0.08);
}

.ticket-accent {
  position: absolute;
  left: 0;
  top: 12px;
  bottom: 12px;
  width: 3px;
  border-radius: 0 2px 2px 0;
}
.status-open .ticket-accent { background: var(--app-primary); }
.status-processing .ticket-accent { background: var(--app-accent); }
.status-completed .ticket-accent { background: var(--app-success); }
.status-closed .ticket-accent { background: var(--app-muted); opacity: 0.5; }

.ticket-status-icon {
  display: inline-flex;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  flex-shrink: 0;
  background: rgba(37, 99, 235, 0.08);
  color: var(--app-primary);
}
.status-processing .ticket-status-icon {
  background: rgba(245, 158, 11, 0.1);
  color: var(--app-accent);
}
.status-completed .ticket-status-icon {
  background: rgba(34, 197, 94, 0.1);
  color: var(--app-success);
}
.status-closed .ticket-status-icon {
  background: var(--app-surface-muted);
  color: var(--app-muted);
}

.ticket-main {
  min-width: 0;
  flex: 1;
  padding-left: 2px;
}
.ticket-top {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ticket-title {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 14px;
  font-weight: 600;
  color: var(--app-fg);
}
.status-tag { flex-shrink: 0; }

.ticket-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--app-muted);
  display: flex;
  align-items: center;
  gap: 6px;
}
.ticket-no {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  padding: 1px 6px;
  background: var(--app-surface-muted);
  border-radius: 4px;
  color: var(--app-muted-strong);
}
.ticket-dot { color: var(--app-border-strong); }

.ticket-item:focus-visible, .attachment-item:focus-visible, .file-picker:focus-within {
  outline: 2px solid var(--app-primary);
  outline-offset: -2px;
}

/* 列表页脚 */
.list-footer {
  display: flex;
  min-height: 40px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--app-border);
}
.pagination-total { color: var(--app-muted); font-size: 12px; }

/* 状态面板 */
.state-panel { padding: 40px 16px; text-align: center; }
.state-panel :deep(.el-button) { margin-top: 12px; }
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 48px 16px;
  color: var(--app-muted);
  font-size: 13px;
}

/* ========== 工单详情对话框 ========== */
:global(.ticket-detail-dialog) {
  max-height: 640px !important;
  display: flex !important;
  flex-direction: column !important;
}
:global(.ticket-detail-dialog .el-dialog__header) {
  flex-shrink: 0 !important;
}
:global(.ticket-detail-dialog .el-dialog__body) {
  flex: 1 !important;
  min-height: 0 !important;
  overflow-y: auto !important;
  padding: 0 !important;
  scrollbar-width: thin;
}
:global(.ticket-detail-dialog .el-dialog__body::-webkit-scrollbar) { width: 6px; }
:global(.ticket-detail-dialog .el-dialog__body::-webkit-scrollbar-thumb) {
  background: color-mix(in srgb, var(--app-border-strong) 60%, transparent);
  border-radius: 3px;
}

.detail-dialog-header {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 14px 18px 12px;
  background: var(--app-primary-soft);
  border-bottom: 1px solid var(--app-border);
}
.detail-header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
}
.detail-header-icon {
  display: inline-flex;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  flex-shrink: 0;
}
.detail-header-icon.status-open { background: rgba(37, 99, 235, 0.1); color: var(--app-primary); }
.detail-header-icon.status-processing { background: rgba(245, 158, 11, 0.12); color: var(--app-accent); }
.detail-header-icon.status-completed { background: rgba(34, 197, 94, 0.12); color: var(--app-success); }
.detail-header-icon.status-closed { background: var(--app-surface-muted); color: var(--app-muted); }

.detail-header-text { min-width: 0; flex: 1; }
.ticket-detail-meta {
  display: block;
  color: var(--app-muted);
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.ticket-detail-title {
  margin-top: 4px;
  overflow-wrap: anywhere;
  font-size: 17px;
  color: var(--app-fg);
  font-weight: 700;
  line-height: 1.4;
}
.detail-status-tag { flex-shrink: 0; margin-top: 4px; }

.ticket-detail { min-height: 0; padding: 14px 18px 16px; display: grid; gap: 12px; }

/* 元信息栏 */
.detail-meta-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  padding: 7px 12px;
  background: var(--app-surface-subtle);
  border-radius: 8px;
  font-size: 12px;
  color: var(--app-muted-strong);
}
.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.meta-item svg { color: var(--app-primary); flex-shrink: 0; }
.meta-sep { color: var(--app-border-strong); margin: 0 4px; }

/* 区块 */
.detail-section { display: grid; gap: 8px; }
.detail-section-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 700;
  color: var(--app-muted-strong);
}
.detail-section-accent {
  width: 3px;
  height: 14px;
  border-radius: 2px;
  background: var(--app-primary);
  flex-shrink: 0;
}
.detail-section-title svg { color: var(--app-primary); flex-shrink: 0; }
.comments-count {
  margin-left: auto;
  font-size: 11px;
  font-weight: 600;
  color: var(--app-muted);
  background: var(--app-surface-subtle);
  padding: 2px 8px;
  border-radius: 10px;
}

/* 正文文本 */
.ticket-detail-body {
  padding: 10px 12px;
  background: var(--app-surface-subtle);
  border-radius: 8px;
  border: 1px solid var(--app-border);
  overflow-wrap: anywhere;
  line-height: 1.7;
  white-space: pre-wrap;
  font-size: 13px;
  color: var(--app-fg);
}

/* 处理结果 */
.resolution {
  padding: 10px 12px;
  background: rgba(34, 197, 94, 0.05);
  border: 1px solid rgba(34, 197, 94, 0.2);
  border-left: 3px solid var(--app-success);
  border-radius: 8px;
}
.resolution-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 700;
  color: var(--app-success-text);
  margin-bottom: 2px;
}
.resolution-title svg { flex-shrink: 0; }
.resolution p { font-size: 13px; color: var(--app-muted-strong); line-height: 1.6; margin: 0; white-space: pre-wrap; }
.resolution-time {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: var(--app-muted);
}

/* 评论 */
.comments {
  max-height: none;
  padding-top: 2px;
  overflow-y: visible;
  scroll-behavior: smooth;
  display: grid;
  gap: 6px;
}
.comments-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 14px;
  background: var(--app-surface-subtle);
  border-radius: 8px;
  border: 1px dashed var(--app-border-strong);
  color: var(--app-muted);
  font-size: 12px;
}
.comments-empty svg { color: var(--app-muted); opacity: 0.6; }
.comments article { display: flex; justify-content: flex-start; }
.comments article.mine { justify-content: flex-end; }
.comment-bubble {
  width: min(84%, 520px);
  padding: 9px 12px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 10px;
}
.comments article.mine .comment-bubble {
  background: var(--app-primary-soft);
  border-color: color-mix(in srgb, var(--app-primary) 24%, var(--app-border));
}
.comment-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.comment-author { font-size: 12px; font-weight: 600; color: var(--app-fg); }
.comments time { color: var(--app-muted); font-size: 11px; }
.comment-bubble > p {
  margin: 5px 0 0;
  overflow-wrap: anywhere;
  line-height: 1.6;
  white-space: pre-wrap;
  font-size: 13px;
  color: var(--app-fg);
}

/* 回复区 */
.reply-section {
  display: grid;
  gap: 6px;
  padding-top: 10px;
  border-top: 1px dashed var(--app-border-strong);
}
.reply-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.reply-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 700;
  color: var(--app-muted-strong);
}
.reply-title svg { color: var(--app-primary); }
.reply-hint {
  font-size: 11px;
  color: var(--app-muted);
}
.reply-section :deep(.el-textarea__inner) { border-radius: 10px; }
.reply-upload { margin: 4px 0; }
.reply-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  min-height: 40px;
}
.reply-actions .el-button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.action-spacer { flex: 1; }
.validation-error { display: block; color: var(--app-danger); font-size: 12px; margin-top: 2px; }

/* 已关闭／已完成页脚 */
.reply-closed {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 14px;
  border-radius: 10px;
  background: var(--app-surface-subtle);
  color: var(--app-muted);
  font-size: 13px;
}
.reply-closed svg { color: var(--app-muted); }

/* ========== 附件 ========== */
.file-picker {
  display: inline-flex;
  height: 36px;
  align-items: center;
  gap: 7px;
  padding: 0 13px;
  color: var(--app-primary);
  border: 1px solid var(--app-border-strong);
  border-radius: 8px;
  cursor: pointer;
  background: var(--app-surface);
  font-size: 13px;
}
.file-picker:hover { background: var(--app-primary-soft); }
.file-picker input { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
.attachment-help { margin-top: 8px; font-size: 12px; line-height: 1.5; color: var(--app-muted); }
.selected-files { display: grid; gap: 6px; margin-top: 10px; }
.selected-files > div {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  background: var(--app-surface-muted);
  border-radius: 7px;
  font-size: 13px;
}
.selected-files span { min-width: 0; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.selected-files small { color: var(--app-muted); flex-shrink: 0; }
.selected-files button {
  display: inline-flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  padding: 0;
  color: var(--app-danger);
  background: transparent;
  border: 0;
  border-radius: 5px;
  cursor: pointer;
}
.selected-files button:hover { background: rgba(220, 38, 38, 0.08); }

.attachment-list { display: flex; flex-wrap: wrap; gap: 8px; }
.ticket-attachments { margin-top: 12px; }
.comment-attachments { margin-top: 10px; }
.attachment-item {
  display: inline-flex;
  min-width: 0;
  min-height: 36px;
  align-items: center;
  gap: 7px;
  padding: 7px 10px;
  color: var(--app-primary);
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  transition: border-color 140ms ease, background-color 140ms ease;
}
.attachment-item:hover {
  border-color: var(--app-primary);
  background: var(--app-primary-soft);
}
.attachment-item span { max-width: 230px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.attachment-item small { color: var(--app-muted); }

.attachment-preview { display: grid; min-height: 180px; place-items: center; }
.attachment-preview img { display: block; max-width: 100%; max-height: 65vh; object-fit: contain; }

/* ========== 创建工单对话框 ========== */
:global(.create-ticket-dialog) {
  border-radius: 16px !important;
  overflow: hidden !important;
}
:global(.create-ticket-dialog .el-dialog__header) {
  padding: 0 !important;
  margin: 0 !important;
}
:global(.create-ticket-dialog .el-dialog__body) {
  padding: 0 !important;
}
:global(.create-ticket-dialog .el-dialog__footer) {
  padding: 14px 24px 18px !important;
  border-top: 1px solid var(--app-border);
  background: var(--app-surface);
}
:global(.create-ticket-dialog .el-dialog__close) {
  top: 14px;
  right: 16px;
}

/* 头部 */
.create-dialog-header {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 20px 24px 16px;
  background: var(--app-primary-soft);
  border-bottom: 1px solid var(--app-border);
}
.create-header-icon {
  display: inline-flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: var(--app-primary);
  color: #fff;
  flex-shrink: 0;
}
.create-header-text { flex: 1; min-width: 0; }
.create-header-text h3 {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: var(--app-fg);
}
.create-header-text p {
  margin: 3px 0 0;
  font-size: 13px;
  color: var(--app-muted);
}

/* 表单区块 */
.create-form {
  display: grid;
  gap: 18px;
  padding: 20px 24px 8px;
}
.create-section { display: grid; gap: 8px; }
.create-section-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--app-muted-strong);
}
.create-section-label svg { color: var(--app-primary); flex-shrink: 0; }
.required {
  font-style: normal;
  font-size: 12px;
  font-weight: 600;
  color: var(--app-danger);
}
.create-error {
  font-size: 12px;
  color: var(--app-danger);
  padding-left: 2px;
}

/* 分类选择器 */
.category-select {
  width: 100%;
}
.category-select :deep(.el-input__wrapper) {
  padding: 4px 12px;
}
.category-select :deep(.el-input__inner) {
  padding: 0;
}
.category-icon {
  display: inline-flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  flex-shrink: 0;
}
.category-icon.cat-account { background: rgba(16, 185, 129, 0.1); color: #059669; }
.category-icon.cat-api { background: rgba(99, 102, 241, 0.1); color: var(--app-primary); }
.category-icon.cat-billing { background: rgba(245, 158, 11, 0.12); color: var(--app-accent); }
.category-icon.cat-bug { background: rgba(239, 68, 68, 0.1); color: var(--app-danger-text); }
.category-icon.cat-feature { background: rgba(139, 92, 246, 0.1); color: var(--app-primary); }
.category-icon.cat-other { background: rgba(107, 114, 128, 0.1); color: var(--app-muted-strong); }

/* 选择器内的自定义选项渲染 */
.select-option {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}
.select-option-text {
  display: inline-flex;
  flex-direction: column;
  min-width: 0;
}
.select-option-text strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-fg);
}
.select-option-text small {
  font-size: 11px;
  color: var(--app-muted);
  margin-top: 1px;
}

/* 全局下拉浮层（popper-class 作用于 body） */
:global(.category-select-dropdown) {
  border-radius: 10px !important;
  padding: 6px !important;
}
:global(.category-select-dropdown .el-select-dropdown__item) {
  height: auto;
  padding: 8px 10px;
  border-radius: 6px;
  line-height: 1.4;
}
:global(.category-select-dropdown .el-select-dropdown__item:hover) {
  background: var(--app-primary-soft);
}

/* 输入框错误态 */
.create-form :deep(.el-input__wrapper.has-error),
.create-form :deep(.el-textarea__inner.has-error) {
  border-color: var(--app-danger) !important;
}

/* 上传区 */
.upload-zone {
  position: relative;
  display: block;
  cursor: pointer;
}
.upload-zone input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  cursor: pointer;
}
.upload-zone-inner {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 18px 20px;
  border: 1.5px dashed var(--app-border-strong);
  border-radius: 10px;
  background: var(--app-surface-subtle);
  transition: border-color 160ms ease, background-color 160ms ease;
}
.upload-zone:hover .upload-zone-inner {
  border-color: var(--app-primary);
  background: var(--app-primary-soft);
}
.upload-icon {
  display: inline-flex;
  width: 40px;
  height: 40px;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: rgba(37, 99, 235, 0.1);
  color: var(--app-primary);
  flex-shrink: 0;
}
.upload-text strong {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--app-fg);
}
.upload-text small {
  display: block;
  margin-top: 3px;
  font-size: 12px;
  color: var(--app-muted);
}

/* 页脚 */
.create-dialog-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.footer-hint {
  font-size: 12px;
  color: var(--app-muted);
}
.footer-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.footer-actions :deep(.el-button--primary) {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

/* ========== 响应式 ========== */
@media (max-width: 767px) {
  .page-heading {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
  .page-heading p { margin-left: 0; }
  .heading-right { width: 100%; flex-wrap: wrap; }
  .heading-stat { min-width: 0; flex: 1; }
  .create-btn { flex: 1; justify-content: center; }
  .card-header { flex-wrap: wrap; }
  .filter-chips { margin-left: 0; width: 100%; }
  .ticket-item { min-height: 72px; padding-inline: 14px; }
  .list-footer {
    align-items: flex-start;
    flex-direction: column;
  }
  .detail-dialog-header { flex-direction: column; align-items: stretch; }
  .detail-status-tag { align-self: flex-start; }
  .comment-bubble { width: 92%; }
  .reply-actions { flex-wrap: wrap; }
  .reply-header { flex-wrap: wrap; }
  .reply-hint { margin-left: auto; }
  .action-spacer { display: none; }
  .reply-actions .file-picker { width: 100%; justify-content: center; }
  .reply-actions :deep(.el-button) { min-width: 0; flex: 1; }
  /* 创建对话框响应式 */
  .create-dialog-header { padding: 16px 18px 14px; }
  .create-form { padding: 16px 18px 4px; }
  .create-dialog-footer {
    flex-direction: column;
    align-items: stretch;
  }
  .footer-hint { text-align: center; }
  .footer-actions :deep(.el-button) { flex: 1; }
}
</style>
