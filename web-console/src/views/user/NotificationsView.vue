<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Bell, BellOff, Check, CheckCheck, Gauge, Info, KeyRound, RefreshCw, Save, ShieldCheck, Volume2 } from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'

import {
  getNotificationPreferences,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  unsubscribeSms,
  updateNotificationPreferences,
  type NotificationCategory,
  type NotificationFilters,
  type NotificationItem,
  type NotificationPreference,
} from '../../api/notifications'
import { useNotificationsStore } from '../../stores/notifications'
import { formatDateTime } from '../../utils/dateTime'

const categoryLabels: Record<NotificationCategory, string> = {
  SECURITY: '安全', BILLING: '账务', USAGE: '用量', OPERATIONS: '运营', SUPPORT: '支持',
  SUBSCRIPTION: '订阅',
}
const categoryIcons: Record<NotificationCategory, typeof ShieldCheck> = {
  SECURITY: ShieldCheck, BILLING: Gauge, USAGE: Volume2, OPERATIONS: Bell, SUPPORT: KeyRound,
  SUBSCRIPTION: RefreshCw,
}
const channelLabels = { IN_APP: '站内通知', SMS: '短信' }

/** relative time (zh-CN) — e.g. "刚刚", "5 分钟前", "2 小时前", "昨天", "2025-08-25" */
const nowTick = ref(Date.now())
const relativeTimer = setInterval(() => { nowTick.value = Date.now() }, 60_000)
if (typeof window !== 'undefined') {
  window.addEventListener('beforeunload', () => clearInterval(relativeTimer))
}
function formatRelative(iso: string): string {
  const now = nowTick.value
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return iso
  const diff = Math.max(0, now - then)
  const sec = Math.floor(diff / 1000)
  if (sec < 45) return '刚刚'
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min} 分钟前`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr} 小时前`
  const day = Math.floor(hr / 24)
  if (day === 1) return '昨天'
  if (day < 7) return `${day} 天前`
  return formatDateTime(iso)
}

const activeTab = ref<'all' | 'unread' | 'preferences'>('all')
const category = ref<NotificationCategory | ''>('')
const page = ref(0)
const size = ref(20)
const notifications = ref<NotificationItem[]>([])
const total = ref(0)
const totalCount = ref(0)
const loading = ref(false)
const loadError = ref(false)
const preferences = ref<NotificationPreference[]>([])
const preferencesLoading = ref(false)

const categoryOrder: NotificationCategory[] = ['SECURITY', 'BILLING', 'USAGE', 'OPERATIONS', 'SUPPORT', 'SUBSCRIPTION']
const groupedPreferences = computed(() => {
  const groups: Record<string, NotificationPreference[]> = {}
  for (const c of categoryOrder) groups[c] = []
  for (const p of preferences.value) {
    if (groups[p.category]) groups[p.category].push(p)
  }
  return groups
})
const preferencesLoaded = ref(false)
const saving = ref(false)
const detailOpen = ref(false)
const selectedNotification = ref<NotificationItem | null>(null)
const store = useNotificationsStore()

const filters = computed<NotificationFilters>(() => ({
  ...(category.value ? { category: category.value } : {}),
  page: page.value,
  size: size.value,
  ...(activeTab.value === 'unread' ? { read: false } : {}),
}))

async function loadNotifications() {
  loading.value = true
  loadError.value = false
  try {
    const { data } = await listNotifications(filters.value)
    notifications.value = data.items
    total.value = data.total
  } catch {
    notifications.value = []
    total.value = 0
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function loadPreferences() {
  if (preferencesLoading.value) return
  preferencesLoading.value = true
  try {
    const { data } = await getNotificationPreferences()
    preferences.value = data.map((item) => ({ ...item }))
    preferencesLoaded.value = true
  } catch {
    ElMessage.error('通知偏好加载失败，请重试。')
  } finally {
    preferencesLoading.value = false
  }
}

async function markRead(item: NotificationItem) {
  if (item.readAt) return
  try {
    await markNotificationRead(item.id)
    item.readAt = new Date().toISOString()
    store.decrementUnread()
    if (activeTab.value === 'unread') await loadNotifications()
  } catch {
    ElMessage.error('标记已读失败，请重试。')
  }
}

async function openNotification(item: NotificationItem) {
  selectedNotification.value = item
  detailOpen.value = true
  await markRead(item)
}

async function markAllRead() {
  try {
    await markAllNotificationsRead()
    store.clearUnread()
    await loadNotifications()
    ElMessage.success('已将全部通知标记为已读。')
  } catch {
    ElMessage.error('全部标记已读失败，请重试。')
  }
}

function mandatory(preference: NotificationPreference) {
  return preference.channel === 'IN_APP' || (preference.category === 'SECURITY' && preference.channel === 'SMS')
}

function quietTimeError(preference: NotificationPreference) {
  if (preference.category === 'SECURITY') return ''
  if (!preference.quietStart || !preference.quietEnd) return '请选择完整的免打扰时段'
  if (preference.quietStart === preference.quietEnd) return '开始时间和结束时间不能相同'
  return ''
}

function crossesMidnight(preference: NotificationPreference) {
  return !quietTimeError(preference) && preference.quietEnd < preference.quietStart
}

const hasQuietTimeError = computed(() => preferences.value.some((item) => Boolean(quietTimeError(item))))

async function savePreferences() {
  if (hasQuietTimeError.value) {
    ElMessage.warning('请先修正免打扰时段。')
    return
  }
  saving.value = true
  try {
    const { data } = await updateNotificationPreferences(preferences.value)
    preferences.value = data.map((item) => ({ ...item }))
    ElMessage.success('通知偏好已保存。')
  } catch {
    ElMessage.error('通知偏好保存失败，请检查设置后重试。')
  } finally {
    saving.value = false
  }
}

async function unsubscribeNonSecuritySms() {
  try {
    await ElMessageBox.confirm('将关闭除安全通知外的全部短信通知，安全短信仍为必选。', '确认退订', {
      confirmButtonText: '确认退订', cancelButtonText: '取消', type: 'warning',
    })
    await unsubscribeSms()
    preferencesLoaded.value = false
    await loadPreferences()
    ElMessage.success('已退订非安全短信通知。')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error('退订失败，请重试。')
  }
}

function changePage(nextPage: number) {
  page.value = nextPage - 1
  return loadNotifications()
}

function changePageSize(nextSize: number) {
  size.value = nextSize
  page.value = 0
  return loadNotifications()
}

watch([activeTab, category], async ([tab]) => {
  page.value = 0
  if (tab === 'preferences') {
    if (!preferencesLoaded.value) await loadPreferences()
  } else {
    await loadNotifications()
  }
})

async function loadTotalCount() {
  try {
    const { data } = await listNotifications({ page: 0, size: 1 })
    totalCount.value = data.total
  } catch { /* ignore — 总数拿不到就显示 0 */ }
}

onMounted(async () => { await Promise.all([loadNotifications(), loadTotalCount()]) })
</script>

<template>
  <section class="notifications-page">
    <TopErrorMessage :message="loadError ? '通知加载失败，请检查网络后重试。' : ''" />

    <!-- Page header -->
    <header class="page-header">
      <div class="page-header-left">
        <div class="header-icon">
          <Bell :size="22" />
        </div>
        <div class="header-text">
          <h2>通知中心</h2>
          <p>查看系统通知与安全提醒，时间均按北京时间显示</p>
        </div>
      </div>
      <div class="page-header-right">
        <div class="header-stat">
          <span class="stat-num">{{ store.unreadCount }}</span>
          <span class="stat-label">未读</span>
        </div>
        <div class="header-stat">
          <span class="stat-num">{{ totalCount }}</span>
          <span class="stat-label">全部</span>
        </div>
      </div>
    </header>

    <!-- Main card container -->
    <div class="main-card">
      <nav class="notif-tabs" role="tablist" aria-label="通知分类">
        <button
          type="button"
          role="tab"
          data-test="notif-tab-all"
          :aria-selected="activeTab === 'all'"
          :class="{ active: activeTab === 'all' }"
          @click="activeTab = 'all'"
        >全部</button>
        <button
          type="button"
          role="tab"
          data-test="notif-tab-unread"
          :aria-selected="activeTab === 'unread'"
          :class="{ active: activeTab === 'unread' }"
          @click="activeTab = 'unread'"
        >未读<template v-if="store.unreadCount > 0" class="tab-badge">{{ store.unreadCount > 99 ? '99+' : store.unreadCount }}</template></button>
        <button
          type="button"
          role="tab"
          data-test="notif-tab-prefs"
          :aria-selected="activeTab === 'preferences'"
          :class="{ active: activeTab === 'preferences' }"
          @click="activeTab = 'preferences'"
        >偏好设置</button>
        <el-select
          v-if="activeTab !== 'preferences'"
          v-model="category"
          data-test="category-filter"
          aria-label="通知分类"
          placeholder="全部分类"
          clearable
          size="default"
          class="tab-filter-select"
        >
          <el-option label="全部分类" value="" />
          <el-option v-for="(label, value) in categoryLabels" :key="value" :label="label" :value="value" />
        </el-select>
        <el-button v-if="activeTab !== 'preferences'" class="mark-all-btn" :disabled="loading || store.unreadCount === 0" @click="markAllRead">
          <CheckCheck :size="15" />全部已读
        </el-button>
      </nav>

      <template v-if="activeTab !== 'preferences'">
        <el-skeleton v-if="loading" :rows="5" animated />
        <div v-else-if="loadError" class="state-panel">
          <div class="state-icon state-error"><Bell :size="22" /></div>
          <p>通知加载失败，请检查网络后重试。</p>
          <el-button data-test="retry-notifications" type="primary" @click="loadNotifications"><RefreshCw :size="16" />重试</el-button>
        </div>
        <div v-else-if="notifications.length === 0" class="state-panel">
          <div class="state-icon state-empty"><BellOff :size="26" /></div>
          <p>{{ activeTab === 'unread' ? '太棒了，没有未读通知' : '暂无通知' }}</p>
          <small v-if="category">当前筛选：{{ categoryLabels[category as NotificationCategory] }}</small>
        </div>
        <div v-else class="notif-list">
          <article
            v-for="item in notifications"
            :key="item.id"
            class="notif-card"
            :class="[`cat-${item.category.toLowerCase()}`, { unread: !item.readAt }]"
            role="button"
            tabindex="0"
            data-test="notification-row"
            @click="openNotification(item)"
            @keydown.enter.prevent="openNotification(item)"
            @keydown.space.prevent="openNotification(item)"
          >
            <span class="card-accent" aria-hidden="true" />
            <div class="notif-icon-wrap">
              <component :is="categoryIcons[item.category]" :size="18" />
            </div>
            <div class="notif-body">
              <div class="notif-row-top">
                <span class="notif-badge" :class="`cat-${item.category.toLowerCase()}`">{{ categoryLabels[item.category] }}</span>
                <el-tooltip :content="formatDateTime(item.createdAt)" placement="top">
                  <time class="notif-time" :datetime="item.createdAt">{{ formatRelative(item.createdAt) }}</time>
                </el-tooltip>
                <span v-if="!item.readAt" class="dot-unread" aria-label="未读" />
              </div>
              <h3 class="notif-title">{{ item.title }}</h3>
              <p class="notif-summary">{{ item.summary }}</p>
            </div>
            <div class="notif-actions">
              <el-tooltip content="标记已读">
                <el-button data-test="mark-read" :disabled="Boolean(item.readAt)" circle size="small" aria-label="标记已读" @click.stop="markRead(item)"><Check :size="15" /></el-button>
              </el-tooltip>
            </div>
          </article>
        </div>
        <div v-if="total > 0" class="pagination-wrap">
          <el-pagination
            :current-page="page + 1"
            :page-size="size"
            :page-sizes="[5, 10, 20]"
            :total="total"
            layout="total, sizes, prev, pager, next"
            @current-change="changePage"
            @size-change="changePageSize"
          />
        </div>
      </template>

      <template v-else>
        <el-skeleton v-if="preferencesLoading" :rows="6" animated />
        <div v-else class="preferences-inner">
          <div class="preference-toolbar">
            <div class="preference-header">
              <strong>分类与渠道</strong>
              <p>免打扰时段使用 Asia/Shanghai；安全通知不受免打扰设置变更影响。</p>
            </div>
            <div class="toolbar-actions">
              <el-button data-test="unsubscribe-sms" @click="unsubscribeNonSecuritySms">退订非安全短信</el-button>
              <el-button type="primary" :loading="saving" :disabled="hasQuietTimeError" @click="savePreferences"><Save :size="16" />保存</el-button>
            </div>
          </div>
          <div class="preference-list">
            <template v-for="cat in categoryOrder" :key="cat">
              <div class="preference-group-header">
                <span class="pref-group-label">{{ categoryLabels[cat] }}</span>
              </div>
              <div v-for="item in groupedPreferences[cat]" :key="`${item.category}-${item.channel}`" class="preference-row">
              <div class="preference-name">
                <div class="preference-title">
                  <div class="pref-icon-wrap" :class="`cat-${item.category.toLowerCase()}`">
                    <component :is="categoryIcons[item.category]" :size="14" />
                  </div>
                  <strong>{{ categoryLabels[item.category] }} · {{ channelLabels[item.channel] }}</strong>
                  <el-tooltip v-if="mandatory(item)" content="该渠道用于保障关键通知送达，无法关闭" placement="top">
                    <button type="button" class="info-button" :aria-label="`${categoryLabels[item.category]}${channelLabels[item.channel]}为必选渠道`"><Info :size="15" aria-hidden="true" /></button>
                  </el-tooltip>
                  <el-tooltip v-else-if="item.category === 'OPERATIONS'" content="运营通知默认关闭，可按需开启" placement="top">
                    <button type="button" class="info-button" aria-label="运营通知默认关闭，可按需开启"><Info :size="15" aria-hidden="true" /></button>
                  </el-tooltip>
                </div>
              </div>
              <el-switch
                v-model="item.enabled"
                :data-test="mandatory(item) ? 'mandatory-channel' : 'optional-channel'"
                :disabled="mandatory(item)"
                :aria-label="`${categoryLabels[item.category]}${channelLabels[item.channel]}`"
              />
              <div class="quiet-hours">
                <div class="quiet-time-field">
                  <el-time-picker v-model="item.quietStart" value-format="HH:mm:ss" format="HH:mm" :disabled="item.category === 'SECURITY'" aria-label="免打扰开始" />
                </div>
                <span>至</span>
                <div class="quiet-time-field">
                  <el-time-picker v-model="item.quietEnd" value-format="HH:mm:ss" format="HH:mm" :disabled="item.category === 'SECURITY'" aria-label="免打扰结束" />
                  <small v-if="crossesMidnight(item)" class="next-day-hint">次日</small>
                </div>
                <small v-if="quietTimeError(item)" class="quiet-time-error" role="alert">{{ quietTimeError(item) }}</small>
              </div>
            </div>
            </template>
          </div>
        </div>
      </template>
    </div>

    <el-dialog v-model="detailOpen" width="min(580px, 92vw)" destroy-on-close class="notif-detail-dialog" :show-close="false">
      <template #header>
        <div class="dialog-header-bar" :class="`cat-${selectedNotification?.category?.toLowerCase() ?? 'usage'}`" aria-hidden="true" />
        <div class="dialog-header-inner">
          <div class="dialog-icon-wrap" :class="`cat-${selectedNotification?.category?.toLowerCase() ?? 'usage'}`">
            <component :is="categoryIcons[selectedNotification?.category ?? 'USAGE']" :size="20" />
          </div>
          <div class="dialog-header-copy">
            <div class="dialog-meta-row">
              <span class="notif-badge" :class="`cat-${selectedNotification?.category?.toLowerCase() ?? 'usage'}`">{{ categoryLabels[selectedNotification?.category ?? 'USAGE'] }}</span>
              <time class="notif-time" :datetime="selectedNotification?.createdAt">{{ selectedNotification ? formatDateTime(selectedNotification.createdAt) : '' }}</time>
            </div>
            <h3 class="dialog-title">{{ selectedNotification?.title }}</h3>
          </div>
        </div>
      </template>
      <div v-if="selectedNotification" class="dialog-body">
        <div class="dialog-summary" :class="`cat-${selectedNotification.category.toLowerCase()}`">
          {{ selectedNotification.summary }}
        </div>
        <dl class="dialog-meta-list">
          <div class="dialog-meta-item">
            <dt>通知分类</dt>
            <dd>
              <span class="notif-badge" :class="`cat-${selectedNotification.category.toLowerCase()}`">{{ categoryLabels[selectedNotification.category] }}</span>
            </dd>
          </div>
          <div class="dialog-meta-item">
            <dt>通知时间</dt>
            <dd>{{ formatDateTime(selectedNotification.createdAt) }}</dd>
          </div>
          <div class="dialog-meta-item">
            <dt>通知 ID</dt>
            <dd><code class="dialog-id">{{ selectedNotification.id }}</code></dd>
          </div>
          <div class="dialog-meta-item">
            <dt>状态</dt>
            <dd>
              <span v-if="selectedNotification.readAt" class="status-pill read">已读 · {{ formatDateTime(selectedNotification.readAt) }}</span>
              <span v-else class="status-pill unread">未读</span>
            </dd>
          </div>
        </dl>
      </div>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="detailOpen = false">关闭</el-button>
          <el-button type="primary" @click="detailOpen = false">知道了</el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.notifications-page { min-width: 0; max-width: 1080px; margin: 0 auto; }

/* ========== 页头 ========== */
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
}
.page-header-left { display: flex; align-items: center; gap: 14px; min-width: 0; }
.header-icon {
  display: inline-flex;
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 11px;
  background: var(--app-primary);
  color: #fff;
  }
.header-text { min-width: 0; }
.header-text h2 {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: var(--app-fg);
  letter-spacing: -0.01em;
}
.header-text p {
  margin: 3px 0 0;
  color: var(--app-muted);
  font-size: 13px;
}

.page-header-right { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.header-stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-width: 64px;
  padding: 6px 12px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 8px;
  line-height: 1;
}
.stat-num { font-size: 18px; font-weight: 700; color: var(--app-fg); font-variant-numeric: tabular-nums; }
.stat-label { margin-top: 3px; font-size: 11px; color: var(--app-muted); }
.header-stat:first-child .stat-num { color: var(--app-primary); }

/* ========== 主卡片容器 ========== */
.main-card {
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  overflow: hidden;
  }

/* ========== 标签页 ========== */
.notif-tabs {
  display: flex;
  align-items: center;
  gap: 0;
  padding: 0 22px;
  border-bottom: 1px solid var(--app-border);
  background: var(--app-surface);
}
.notif-tabs button {
  position: relative;
  min-height: 48px;
  padding: 0 16px;
  color: var(--app-muted);
  background: transparent;
  border: 0;
  border-bottom: 2px solid transparent;
  font: inherit;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: color 0.18s ease, border-color 0.18s ease, background 0.18s ease;
  margin-bottom: -1px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.notif-tabs button:hover { color: var(--app-primary); }
.notif-tabs button.active {
  color: var(--app-primary);
  border-bottom-color: var(--app-primary);
  font-weight: 600;
}
.tab-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  font-size: 11px;
  font-weight: 600;
  color: #fff;
  background: var(--app-danger);
  border-radius: 9px;
}
.mark-all-btn { margin-left: auto; }
.tab-filter-select { width: 160px; margin-left: 18px; flex-shrink: 0; }

/* ========== Card list — 列表行风格, 降噪 ========== */
.notif-list {
  display: flex;
  flex-direction: column;
  padding: 4px 0;
}
.notif-card {
  position: relative;
  display: flex;
  align-items: stretch;
  gap: 14px;
  padding: 14px 22px;
  background: transparent;
  border: none;
  border-bottom: 1px solid var(--app-border);
  border-radius: 0;
  cursor: pointer;
  overflow: hidden;
  transition: background 0.15s ease;
}
.notif-card:last-child { border-bottom: none; }
.notif-card:hover { background: var(--app-surface-subtle); }
.notif-card:focus-visible { outline: 2px solid var(--app-primary); outline-offset: -2px; }

/* Left color stripe — 3px, 区分分类 + 未读权重 */
.card-accent {
  width: 3px;
  flex: 0 0 3px;
  margin: -14px 0 -14px -22px;
  background: transparent;
  border-radius: 0;
  transition: background 0.15s ease;
}
/* 未读 → accent 亮起来; 已读 → accent 隐藏 */
.notif-card.unread .card-accent { background: var(--cat-color, var(--app-primary)); }
.notif-card:not(.unread) .card-accent { background: transparent; }

/* 未读状态 — 不用 box-shadow, 用 accent 条 + 标题加粗 + 淡底色 */
.notif-card.unread { background: var(--app-surface-subtle); }
.notif-card.unread .notif-title { font-weight: 700; }

/* Category themes — accent color 统一走 CSS 变量注入 */
.cat-security { --cat-color: var(--app-danger); }
.cat-billing { --cat-color: var(--app-warning); }
.cat-usage { --cat-color: var(--app-primary); }
.cat-operations { --cat-color: var(--app-success); }
.cat-support { --cat-color: var(--app-primary); }

.cat-security .notif-icon-wrap { background: var(--app-danger-soft); color: var(--app-danger); }
.cat-security .notif-badge { background: var(--app-danger-soft); color: var(--app-danger); }

.cat-billing .notif-icon-wrap { background: var(--app-warning-soft); color: var(--app-warning); }
.cat-billing .notif-badge { background: var(--app-warning-soft); color: var(--app-warning); }

.cat-usage .notif-icon-wrap { background: var(--app-primary-soft); color: var(--app-primary); }
.cat-usage .notif-badge { background: var(--app-primary-soft); color: var(--app-primary); }

.cat-operations .notif-icon-wrap { background: var(--app-success-soft); color: var(--app-success); }
.cat-operations .notif-badge { background: var(--app-success-soft); color: var(--app-success); }

.cat-support .notif-icon-wrap { background: var(--app-primary-soft); color: var(--app-primary); }
.cat-support .notif-badge { background: var(--app-primary-soft); color: var(--app-primary); }

/* 图标容器 */
.notif-icon-wrap {
  display: inline-flex;
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  background: var(--app-surface-muted);
  color: var(--app-muted-strong);
}

/* Body */
.notif-body { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 4px; }
.notif-row-top { display: flex; align-items: center; gap: 8px; }
.notif-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.4;
  background: var(--app-surface-muted);
  color: var(--app-muted-strong);
}
.notif-time { font-size: 12px; color: var(--app-muted); }
.dot-unread {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--app-primary);
  margin-left: auto;
  flex-shrink: 0;
}
.cat-security .dot-unread { background: var(--app-danger); }
.notif-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--app-fg);
  overflow-wrap: anywhere;
}
.notif-summary {
  margin: 0;
  font-size: 13px;
  color: var(--app-muted-strong);
  overflow-wrap: anywhere;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 操作区 */
.notif-actions { flex-shrink: 0; display: flex; align-items: flex-start; }

/* 分页 */
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  padding: 18px 22px 20px;
}

/* 空态／错误／骨架屏 */
.state-panel {
  padding: 56px 22px 48px;
  text-align: center;
  color: var(--app-muted-strong);
}
.state-panel p { margin: 10px 0 0; font-size: 14px; color: var(--app-muted-strong); }
.state-panel small { display: block; margin-top: 4px; font-size: 12px; color: var(--app-muted); }
.state-icon {
  display: inline-flex;
  width: 56px;
  height: 56px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  margin-bottom: 4px;
}
.state-empty { background: var(--app-surface-muted); color: var(--app-muted); }
.state-error { background: var(--app-danger-soft); color: var(--app-danger); }
.el-skeleton { margin: 20px 22px; }

/* ========== 偏好设置分组 ========== */
.preference-group-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 20px;
  padding: 12px 0 8px;
  font-size: 15px;
  font-weight: 700;
  color: var(--app-fg);
  letter-spacing: 0;
  text-transform: none;
}
.preference-group-header:first-of-type {
  margin-top: 8px;
  padding-top: 4px;
}
.pref-group-icon {
  display: inline-flex;
  width: 26px;
  height: 26px;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  background: var(--app-surface-muted);
  color: var(--app-muted-strong);
}
.preference-group-header.cat-security .pref-group-icon { background: var(--app-danger-soft); color: var(--app-danger); }
.preference-group-header.cat-billing .pref-group-icon { background: var(--app-warning-soft); color: var(--app-warning); }
.preference-group-header.cat-usage .pref-group-icon { background: var(--app-primary-soft); color: var(--app-primary); }
.preference-group-header.cat-operations .pref-group-icon { background: var(--app-success-soft); color: var(--app-success); }
.preference-group-header.cat-support .pref-group-icon { background: var(--app-primary-soft); color: var(--app-primary); }
.pref-group-label { font-size: inherit; font-weight: inherit; }

/* ========== 偏好设置（位于 main-card 内） ========== */
.preferences-inner { }
.preference-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 22px;
  border-bottom: 1px solid var(--app-border);
  background: var(--app-surface);
}
.preference-header strong { font-size: 14px; font-weight: 600; color: var(--app-fg); }
.preference-header p { margin: 3px 0 0; color: var(--app-muted); font-size: 12px; }
.toolbar-actions { display: flex; gap: 8px; }
.preference-list { padding: 4px 22px; }
.preference-row {
  display: grid;
  grid-template-columns: 1fr auto 360px;
  align-items: center;
  gap: 16px;
  padding: 14px 0;
  border-bottom: 1px solid var(--app-border);
}
.preference-row:last-child { border-bottom: 0; }
.preference-name { min-width: 0; }
.preference-title { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.pref-icon-wrap {
  display: inline-flex;
  width: 24px;
  height: 24px;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  background: var(--app-surface-muted);
  color: var(--app-muted-strong);
  flex-shrink: 0;
}
.pref-icon-wrap.cat-security { background: var(--app-danger-soft); color: var(--app-danger); }
.pref-icon-wrap.cat-billing { background: var(--app-warning-soft); color: var(--app-warning); }
.pref-icon-wrap.cat-usage { background: var(--app-primary-soft); color: var(--app-primary); }
.pref-icon-wrap.cat-operations { background: var(--app-success-soft); color: var(--app-success); }
.pref-icon-wrap.cat-support { background: var(--app-primary-soft); color: var(--app-primary); }
.preference-title strong { font-size: 13px; color: var(--app-fg); font-weight: 500; }
.preference-name small { display: block; color: var(--app-muted); font-size: 12px; margin-top: 4px; }
.info-button {
  display: inline-flex; width: 28px; height: 28px; align-items: center; justify-content: center; padding: 0;
  color: var(--app-muted); background: transparent; border: 0; border-radius: 6px; cursor: pointer;
}
.info-button:hover { color: var(--app-primary); background: var(--app-primary-soft); }
.info-button:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 2px; }

.quiet-hours { position: relative; display: flex; align-items: flex-start; gap: 8px; padding-bottom: 18px; }
.quiet-hours > span { padding-top: 9px; color: var(--app-muted); }
.quiet-time-field { position: relative; width: 155px; }
.quiet-hours :deep(.el-date-editor) { width: 100%; }
.next-day-hint { position: absolute; top: 34px; left: 2px; color: var(--app-muted); font-size: 12px; }
.quiet-time-error { position: absolute; right: 0; bottom: 0; left: 0; color: var(--app-danger); font-size: 12px; }

/* ========== 详情对话框——自定义头部与主体 ========== */
.notif-detail-dialog :deep(.el-dialog__header) { margin: 0; padding: 0; overflow: hidden; }
.notif-detail-dialog :deep(.el-dialog__body) { padding: 0; }
.notif-detail-dialog :deep(.el-dialog__footer) {
  padding: 14px 22px 18px;
  border-top: 1px solid var(--app-border);
}
.dialog-header-bar { height: 4px; margin: 0 -20px; }
.dialog-header-bar.cat-security { background: var(--app-danger); }
.dialog-header-bar.cat-billing { background: var(--app-warning); }
.dialog-header-bar.cat-usage { background: var(--app-primary); }
.dialog-header-bar.cat-operations { background: var(--app-success); }
.dialog-header-bar.cat-support { background: var(--app-primary); }

.dialog-header-inner {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 18px 20px 16px;
  border-bottom: 1px solid var(--app-border);
}
.dialog-icon-wrap {
  display: inline-flex;
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 11px;
  background: var(--app-surface-muted);
  color: var(--app-muted-strong);
}
.dialog-icon-wrap.cat-security { background: var(--app-danger-soft); color: var(--app-danger); }
.dialog-icon-wrap.cat-billing { background: var(--app-warning-soft); color: var(--app-warning); }
.dialog-icon-wrap.cat-usage { background: var(--app-primary-soft); color: var(--app-primary); }
.dialog-icon-wrap.cat-operations { background: var(--app-success-soft); color: var(--app-success); }
.dialog-icon-wrap.cat-support { background: var(--app-primary-soft); color: var(--app-primary); }

.dialog-header-copy { min-width: 0; flex: 1; }
.dialog-meta-row { display: flex; align-items: center; gap: 8px; }
.dialog-title {
  margin: 6px 0 0;
  font-size: 17px;
  font-weight: 700;
  color: var(--app-fg);
  line-height: 1.3;
  overflow-wrap: anywhere;
}

.dialog-body { padding: 18px 20px 8px; }
.dialog-summary {
  padding: 12px 14px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.65;
  color: var(--app-muted-strong);
  background: var(--app-surface-subtle);
  border-left: 3px solid var(--app-border);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin-bottom: 16px;
}
.dialog-summary.cat-security { border-left-color: var(--app-danger); background: var(--app-danger-soft); color: var(--app-danger); }
.dialog-summary.cat-billing { border-left-color: var(--app-warning); background: var(--app-warning-soft); color: var(--app-warning); }
.dialog-summary.cat-usage { border-left-color: var(--app-primary); background: var(--app-primary-soft); color: var(--app-primary); }
.dialog-summary.cat-operations { border-left-color: var(--app-success); background: var(--app-success-soft); color: var(--app-success); }
.dialog-summary.cat-support { border-left-color: var(--app-primary); background: var(--app-primary-soft); color: var(--app-primary); }

.dialog-meta-list {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 20px;
  margin: 0;
  padding: 14px 16px;
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: 8px;
}
.dialog-meta-item { display: flex; flex-direction: column; gap: 4px; margin: 0 0 12px; }
.dialog-meta-item:nth-last-child(2),
.dialog-meta-item:last-child { margin-bottom: 0; }
.dialog-meta-item dt {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.02em;
  text-transform: uppercase;
  color: var(--app-muted);
}
.dialog-meta-item dd {
  margin: 0;
  font-size: 13px;
  color: var(--app-fg);
  font-weight: 500;
  overflow-wrap: anywhere;
}
.dialog-id {
  padding: 1px 6px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  background: var(--app-surface-muted);
  border-radius: 4px;
  color: var(--app-muted-strong);
}
.status-pill {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 600;
}
.status-pill.read { background: var(--app-surface-muted); color: var(--app-muted); }
.status-pill.unread { background: var(--app-primary-soft); color: var(--app-primary); }
.dialog-footer { display: flex; justify-content: flex-end; gap: 8px; }

/* ========== 响应式 ========== */
@media (max-width: 767px) {
  .page-header { flex-direction: column; align-items: flex-start; gap: 12px; }
  .page-header-right { width: 100%; justify-content: flex-start; }
  .header-stat { min-width: 56px; flex: 1; }
  .notif-tabs { flex-wrap: wrap; padding: 0 14px; }
  .tab-filter-select { width: 100%; margin-left: 0; margin-top: 8px; }
  .mark-all-btn { margin-left: 0; margin-top: 8px; width: 100%; }
  .notif-list { padding: 12px 14px 0; }
  .notif-card { gap: 10px; padding: 12px 14px; }
  .notif-icon-wrap { width: 32px; height: 32px; }
  .card-accent { margin: -12px 0 -12px -14px; }
  .pagination-wrap { justify-content: flex-start; padding: 14px; }
  .preference-toolbar { flex-direction: column; align-items: flex-start; padding: 14px; }
  .preference-list { padding: 4px 14px; }
  .toolbar-actions { width: 100%; flex-wrap: wrap; }
  .preference-row { grid-template-columns: 1fr; gap: 10px; }
  .quiet-hours { width: 100%; }
  .quiet-time-field { min-width: 0; flex: 1; }
  .dialog-meta-list { grid-template-columns: 1fr; }
}
</style>
