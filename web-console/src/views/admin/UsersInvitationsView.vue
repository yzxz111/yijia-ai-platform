<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import { Ban, Eye, Mail, PackageCheck, Phone, RefreshCw, Search, ShieldCheck, Users } from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'

import {
  getAdminUser,
  getAdminUserAvatar,
  listPackagePlanCatalog,
  listAdminUsers,
  updateAdminUser,
  type AdminListParams,
  type AdminUser,
  type AdminUserDetail,
} from '../../api/admin'
import { adminDrillQuery } from '../../router/query'
import { formatDateTime } from '../../utils/dateTime'
import { adminRoleLabel, adminStatusLabel, grantReasonLabel, userAccountStatusLabel, userNameLabel, userNoLabel } from '../../utils/adminDisplay'
import { userPlanName } from '../../utils/planCopy'

const route = useRoute()
const initialDrill = adminDrillQuery(route.query, [], '')
const users = ref<AdminUser[]>([])
const usersTotal = ref(0)
const usersLoading = ref(true)
const summaryLoading = ref(true)
const packageFilters = ref<{ code: string; label: string }[]>([])
const allUsersCount = ref(0)
const bannedCount = ref(0)
const activeCount = computed(() => Math.max(0, allUsersCount.value - bannedCount.value))
const bannedRatio = computed(() => {
  if (!allUsersCount.value) return 0
  return Math.round((bannedCount.value / allUsersCount.value) * 1000) / 10
})
const activeRatio = computed(() => {
  if (!allUsersCount.value) return 0
  return Math.round((activeCount.value / allUsersCount.value) * 1000) / 10
})
const withPackageCount = ref(0)
const packageCoverageRatio = computed(() => {
  if (!allUsersCount.value) return 0
  return Math.round((withPackageCount.value / allUsersCount.value) * 1000) / 10
})
const withPackageOnly = ref(false)
const tipVisible = ref(false)
const tipText = ref<{ title: string; desc: string; action: string }>({ title: '', desc: '', action: '' })
const tipX = ref(0)
const tipY = ref(0)
let tipHideTimer: number | null = null
const tipData: Record<string, { title: string; desc: string; action: string }> = {
  all: { title: '全部用户', desc: '平台累计注册用户总数，含正常与封禁。', action: '点击卡片查看所有用户' },
  active: { title: '正常用户', desc: '可正常登录并调用接口的活跃用户。', action: '点击卡片筛选正常用户' },
  banned: { title: '已封禁', desc: '封禁后无法登录或调用接口，仅可提交申诉。', action: '点击卡片查看封禁原因并解封' },
  pkg: { title: '套餐覆盖率', desc: '有生效套餐的用户占比，免费版/入门版/企业版均计入。', action: '点击卡片查看各用户套餐版本' },
}

function showTip(key: string, evt: MouseEvent) {
  if (tipHideTimer) {
    window.clearTimeout(tipHideTimer)
    tipHideTimer = null
  }
  tipText.value = tipData[key]
  updateTipPos(evt)
  tipVisible.value = true
}
function moveTip(evt: MouseEvent) {
  if (!tipVisible.value) return
  updateTipPos(evt)
}
function hideTip() {
  if (tipHideTimer) window.clearTimeout(tipHideTimer)
  tipHideTimer = window.setTimeout(() => {
    tipVisible.value = false
  }, 40)
}
function updateTipPos(evt: MouseEvent) {
  const TIP_MAX_W = 200
  const TIP_MAX_H = 110
  const OFFSET_X = 14
  const OFFSET_Y = 16
  let x = evt.clientX + OFFSET_X
  let y = evt.clientY + OFFSET_Y
  if (x + TIP_MAX_W > window.innerWidth) x = evt.clientX - TIP_MAX_W - OFFSET_X
  if (y + TIP_MAX_H > window.innerHeight) y = evt.clientY - TIP_MAX_H - OFFSET_Y
  if (x < 4) x = 4
  if (y < 4) y = 4
  tipX.value = x
  tipY.value = y
}
const savingUserId = ref('')
const errorMessage = ref('')
const detailLoading = ref(false)
const detailOpen = ref(false)
const selectedDetail = ref<AdminUserDetail | null>(null)
const avatarUrls = ref<Record<string, string>>({})
const pageSizeOptions = [5, 10, 20]
const usersParams = reactive<AdminListParams>({
  page: 0, size: 20, query: initialDrill.userId || initialDrill.search, status: '', sort: 'createdAt', direction: 'desc', packageCode: '',
})

const summaryParams: AdminListParams = {
  page: 0, size: 1, query: '', status: '', sort: 'createdAt', direction: 'desc',
}

async function loadSummary() {
  summaryLoading.value = true
  try {
    const [allUsers, banned] = await Promise.allSettled([
      listAdminUsers({ page: 0, size: 100, query: '', status: '', sort: 'createdAt', direction: 'desc' }),
      listAdminUsers({ ...summaryParams, status: 'DISABLED' }),
    ])
    if (allUsers.status === 'fulfilled') {
      allUsersCount.value = allUsers.value.total
      withPackageCount.value = allUsers.value.items.filter((user) => user.currentPackageCode).length
    }
    if (banned.status === 'fulfilled') bannedCount.value = banned.value.total
    if ([allUsers, banned].some((r) => r.status === 'rejected')) {
      errorMessage.value = '用户统计加载失败，请稍后重试。'
    }
  } catch {
    errorMessage.value = '用户统计加载失败，请稍后重试。'
  } finally {
    summaryLoading.value = false
  }
}

async function loadPackageFilters() {
  const result = await listPackagePlanCatalog({ page: 0, size: 100, query: '', status: '' })
  packageFilters.value = result.items.map(plan => ({
    code: plan.code,
    label: userPlanName(plan.code, plan.name),
  }))
}

async function refreshUsers() {
  try {
    await loadPackageFilters()
  } catch {
    errorMessage.value = '套餐筛选项加载失败，请稍后重试。'
  }
  await Promise.all([loadUsers(), loadSummary()])
}

async function loadUsers(targetPage = usersParams.page) {
  usersLoading.value = true
  errorMessage.value = ''
  try {
    const reqParams: AdminListParams = withPackageOnly.value
      ? { page: 0, size: 100, query: usersParams.query, status: usersParams.status, sort: usersParams.sort, direction: usersParams.direction, packageCode: '' }
      : { ...usersParams, page: targetPage }
    const userPage = await listAdminUsers(reqParams)
    const rawItems = userPage.items
    const filteredItems = withPackageOnly.value
      ? rawItems.filter((user) => user.currentPackageCode)
      : rawItems
    users.value = filteredItems
    usersTotal.value = withPackageOnly.value ? filteredItems.length : userPage.total
    usersParams.page = withPackageOnly.value ? 0 : userPage.page
    void Promise.all(filteredItems.filter((user) => user.avatarUpdatedAt).map(loadAvatar))
  } catch {
    errorMessage.value = '用户数据加载失败，请稍后重试。'
  } finally {
    usersLoading.value = false
  }
}

async function loadAvatar(user: AdminUser) {
  if (avatarUrls.value[user.id] || !user.avatarUpdatedAt) return
  try {
    const blob = await getAdminUserAvatar(user.id)
    avatarUrls.value = { ...avatarUrls.value, [user.id]: URL.createObjectURL(blob) }
  } catch {
    // 头像无法加载时，仍保留姓名首字。
  }
}

async function openDetail(user: AdminUser) {
  detailOpen.value = true
  detailLoading.value = true
  selectedDetail.value = null
  errorMessage.value = ''
  try {
    selectedDetail.value = await getAdminUser(user.id)
    void loadAvatar(user)
  } catch {
    detailOpen.value = false
    errorMessage.value = '用户详情加载失败，请稍后重试。'
  } finally {
    detailLoading.value = false
  }
}

function avatarInitial(user: AdminUser) {
  return userNameLabel(user.displayName).slice(0, 1).toUpperCase()
}

function changeUsersPageSize(size: number) {
  usersParams.size = size
  void loadUsers(0)
}

async function toggleStatus(user: AdminUser) {
  if (!user.statusManageable) return
  const banning = user.status === 'ACTIVE'
  let disabledReason: string | undefined
  if (banning) {
    const result = await ElMessageBox.prompt(
      `封禁后 ${userNameLabel(user.displayName)} 只能访问账号状态、安全申诉和支持工单。`,
      '封禁用户',
      {
        type: 'warning',
        confirmButtonText: '确认封禁',
        cancelButtonText: '取消',
        inputPlaceholder: '请输入用户可见的封禁原因',
        inputValidator: (value: string) => {
          const reason = value.trim()
          if (!reason) return '请输入封禁原因'
          if (reason.length > 500) return '封禁原因不能超过 500 个字符'
          return true
        },
      },
    ).catch(() => null)
    if (!result) return
    disabledReason = result.value.trim()
  } else {
    const confirmed = await ElMessageBox.confirm(
      `确认解除 ${userNameLabel(user.displayName)} 的封禁吗？`,
      '解除封禁',
      { type: 'info', confirmButtonText: '确认解封', cancelButtonText: '取消' },
    ).then(() => true).catch(() => false)
    if (!confirmed) return
  }
  savingUserId.value = user.id
  errorMessage.value = ''
  try {
    const updated = await updateAdminUser(
      user.id,
      user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE',
      user.role,
      disabledReason,
    )
    users.value = users.value.map((candidate) => candidate.id === updated.id ? updated : candidate)
    ElMessage.success(banning ? '用户已封禁' : '用户已解除封禁')
    await Promise.all([loadUsers(usersParams.page), loadSummary()])
  } catch {
    errorMessage.value = '用户状态更新失败。当前管理员不能封禁自己，请刷新后重试。'
  } finally {
    savingUserId.value = ''
  }
}

function selectAllUsers() {
  withPackageOnly.value = false
  usersParams.status = ''
  usersParams.packageCode = ''
  void loadUsers(0)
}

function selectActive() {
  withPackageOnly.value = false
  usersParams.status = usersParams.status === 'ACTIVE' ? '' : 'ACTIVE'
  usersParams.packageCode = ''
  void loadUsers(0)
}

function selectWithPackage() {
  withPackageOnly.value = !withPackageOnly.value
  usersParams.status = ''
  usersParams.packageCode = ''
  void loadUsers(0)
}

function selectBanned() {
  withPackageOnly.value = false
  usersParams.status = usersParams.status === 'DISABLED' ? '' : 'DISABLED'
  usersParams.packageCode = ''
  void loadUsers(0)
}

function currentPackageLabel(user: AdminUser) {
  if (!user.currentPackageCode) return '暂无生效套餐'
  const version = user.currentPackageVersion ? ` v${user.currentPackageVersion}` : ''
  return `${userPlanName(user.currentPackageCode, user.currentPackage)}${version}`
}

function relativeTime(iso: string | null): string {
  if (!iso) return ''
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return ''
  const diffMs = Date.now() - t
  const min = Math.round(diffMs / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hr = Math.round(min / 60)
  if (hr < 24) return `${hr} 小时前`
  const day = Math.round(hr / 24)
  if (day < 30) return `${day} 天前`
  const mo = Math.round(day / 30)
  if (mo < 12) return `${mo} 个月前`
  return `${Math.round(mo / 12)} 年前`
}

function userContact(user: AdminUser): string {
  return user.email || '未绑定邮箱'
}

function userPhone(user: AdminUser): string {
  return user.phone || '未绑定手机'
}

async function toggleRole(user: AdminUser) {
  if (!user.roleManageable) return
  savingUserId.value = user.id
  errorMessage.value = ''
  try {
    const updated = await updateAdminUser(
      user.id,
      user.status,
      user.role === 'ADMIN' ? 'USER' : 'ADMIN',
    )
    users.value = users.value.map((candidate) => candidate.id === updated.id ? updated : candidate)
  } catch {
    errorMessage.value = '用户角色更新失败，请刷新后重试。'
  } finally {
    savingUserId.value = ''
  }
}

onMounted(refreshUsers)
onBeforeUnmount(() => Object.values(avatarUrls.value).forEach((url) => URL.revokeObjectURL(url)))
watch(() => route.query, (query) => {
  const drill = adminDrillQuery(query, [], '')
  usersParams.query = drill.userId || drill.search
  void loadUsers(0)
}, { deep: true })
</script>

<template>
  <section class="admin-page" aria-labelledby="admin-users-title">
    <header class="admin-page__header">
      <div>
        <h2 id="admin-users-title">用户管理</h2>
        <p>共 {{ usersTotal }} 名匹配用户，可按套餐或封禁状态筛选</p>
      </div>
      <el-button :loading="usersLoading || summaryLoading" aria-label="刷新用户" @click="refreshUsers">
        <RefreshCw :size="16" />刷新
      </el-button>
    </header>

    <TopErrorMessage :message="errorMessage" />

    <section class="user-summary-grid" aria-label="用户统计与封禁洞察">
      <button
        type="button"
        class="user-summary-card"
        :class="{ active: !usersParams.packageCode && !usersParams.status }"
        :aria-pressed="!usersParams.packageCode && !usersParams.status"
        data-test="all-users-filter"
        @click="selectAllUsers"
        @mouseenter="showTip('all', $event)"
        @mousemove="moveTip($event)"
        @mouseleave="hideTip"
      >
        <span class="user-summary-card__icon"><Users :size="18" aria-hidden="true" /></span>
        <span class="user-summary-card__label">全部用户</span>
        <strong class="user-summary-card__value">{{ allUsersCount }}</strong>
        <span class="user-summary-card__hint">正常 {{ activeCount }} · 封禁 {{ bannedCount }}</span>
      </button>
      <button
        type="button"
        class="user-summary-card active-users"
        :class="{ active: !usersParams.packageCode && usersParams.status === 'ACTIVE' }"
        :aria-pressed="!usersParams.packageCode && usersParams.status === 'ACTIVE'"
        data-test="active-users-filter"
        @click="selectActive"
        @mouseenter="showTip('active', $event)"
        @mousemove="moveTip($event)"
        @mouseleave="hideTip"
      >
        <span class="user-summary-card__icon"><ShieldCheck :size="18" aria-hidden="true" /></span>
        <span class="user-summary-card__label">正常用户</span>
        <strong class="user-summary-card__value">{{ activeCount }}</strong>
        <span class="user-summary-card__hint">占比 {{ activeRatio }}%</span>
      </button>
      <button
        type="button"
        class="user-summary-card banned"
        :class="{ active: usersParams.status === 'DISABLED' }"
        :aria-pressed="usersParams.status === 'DISABLED'"
        data-test="banned-filter"
        @click="selectBanned"
        @mouseenter="showTip('banned', $event)"
        @mousemove="moveTip($event)"
        @mouseleave="hideTip"
      >
        <span class="user-summary-card__icon"><Ban :size="18" aria-hidden="true" /></span>
        <span class="user-summary-card__label">已封禁</span>
        <strong class="user-summary-card__value">{{ bannedCount }}</strong>
        <span class="user-summary-card__hint">占比 {{ bannedRatio }}%</span>
      </button>
      <button
        type="button"
        class="user-summary-card user-summary-card--insight"
        :class="{ active: withPackageOnly }"
        :aria-pressed="withPackageOnly"
        aria-label="套餐覆盖率"
        data-test="with-package-filter"
        @click="selectWithPackage"
        @mouseenter="showTip('pkg', $event)"
        @mousemove="moveTip($event)"
        @mouseleave="hideTip"
      >
        <span class="user-summary-card__icon"><PackageCheck :size="18" aria-hidden="true" /></span>
        <span class="user-summary-card__label">套餐覆盖率</span>
        <strong class="user-summary-card__value">{{ packageCoverageRatio }}%</strong>
        <div class="user-summary-card__bar" :title="`有套餐 ${withPackageCount} / 共 ${allUsersCount}`">
          <div class="user-summary-card__bar-fill" :style="{ width: `${packageCoverageRatio}%` }"></div>
        </div>
      </button>
    </section>

    <div v-show="tipVisible" class="user-follow-tip" :style="{ left: `${tipX}px`, top: `${tipY}px` }" role="tooltip" aria-hidden="true">
      <strong>{{ tipText.title }}</strong>
      <p>{{ tipText.desc }}</p>
      <span class="user-follow-tip__action">{{ tipText.action }}</span>
    </div>

    <section class="admin-panel users-panel">
      <div class="admin-toolbar">
        <el-input v-model="usersParams.query" data-test="users-query" aria-label="搜索用户" clearable placeholder="搜索用户编号或联系方式" @keyup.enter="loadUsers(0)" />
        <el-select v-model="usersParams.status" data-test="users-status" aria-label="用户状态" clearable placeholder="全部状态"><el-option label="正常" value="ACTIVE" /><el-option label="已封禁" value="DISABLED" /></el-select>
        <el-select v-model="usersParams.packageCode" data-test="users-package" aria-label="当前套餐" clearable placeholder="全部套餐"><el-option v-for="plan in packageFilters" :key="plan.code" :label="plan.label" :value="plan.code" /></el-select>
        <el-select v-model="usersParams.sort" data-test="users-sort" aria-label="用户排序字段"><el-option label="创建时间" value="createdAt" /><el-option label="用户编号" value="userNo" /><el-option label="状态" value="status" /></el-select>
        <el-select v-model="usersParams.direction" data-test="users-direction" aria-label="用户排序方向"><el-option label="降序" value="desc" /><el-option label="升序" value="asc" /></el-select>
        <el-button data-test="users-filter" type="primary" :loading="usersLoading" @click="loadUsers(0)"><Search :size="16" />查询</el-button>
      </div>

      <el-skeleton v-if="usersLoading" :rows="5" animated />
      <div v-else class="table-scroll">
        <table class="admin-table users-table">
          <thead><tr><th>用户</th><th>用户编号</th><th>用户名</th><th>当前套餐</th><th>状态</th><th>角色</th><th>最近登录</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="user in users" :key="user.id" class="users-table__row">
              <td class="users-table__cell-avatar"><el-avatar :size="32" :src="avatarUrls[user.id]">{{ avatarInitial(user) }}</el-avatar></td>
              <td class="users-table__cell-no"><strong>{{ userNoLabel(user.userNo) }}</strong></td>
              <td class="users-table__name" :title="userNameLabel(user.displayName)">
                <span class="users-table__name-text">{{ userNameLabel(user.displayName) }}</span>
              </td>
              <td>
                <el-tag v-if="user.currentPackageCode" size="small" type="primary" effect="light" class="users-table__tag">{{ currentPackageLabel(user) }}</el-tag>
                <el-tag v-else size="small" effect="plain" class="users-table__tag users-table__tag--muted">暂无套餐</el-tag>
              </td>
              <td><el-tag size="small" :type="user.status === 'ACTIVE' ? 'success' : 'danger'" class="users-table__tag">{{ userAccountStatusLabel(user.status) }}</el-tag></td>
              <td>
                <el-tag v-if="user.globalAdmin" data-test="global-admin-label" size="small" type="warning" class="users-table__tag">全局管理员</el-tag>
                <el-tag v-else-if="user.role === 'ADMIN'" size="small" effect="plain" class="users-table__tag users-table__tag--role">管理员</el-tag>
                <el-tag v-else size="small" effect="plain" class="users-table__tag users-table__tag--muted users-table__tag--role">{{ adminRoleLabel(user.role) }}</el-tag>
              </td>
              <td class="users-table__cell-login">
                <div v-if="user.lastLoginAt" class="users-table__login-time">{{ formatDateTime(user.lastLoginAt) }}</div>
                <span v-else class="users-table__login-never">从未登录</span>
                <div class="users-table__login-sub">{{ user.lastLoginAt ? relativeTime(user.lastLoginAt) : '账号未激活使用' }}</div>
              </td>
              <td class="users-table__cell-actions">
                <div class="admin-actions users-table__actions">
                  <el-button size="small" text class="users-table__btn" @click="openDetail(user)"><Eye :size="14" class="users-table__btn-icon" />详情</el-button>
                  <template v-if="user.statusManageable || user.roleManageable">
                    <el-button
                      v-if="user.statusManageable"
                      :data-test="`user-toggle-${user.id}`"
                      size="small"
                      :type="user.status === 'ACTIVE' ? 'danger' : 'success'"
                      plain
                      class="users-table__btn"
                      :loading="savingUserId === user.id"
                      @click="toggleStatus(user)"
                    >
                      <Ban v-if="user.status === 'ACTIVE'" :size="14" class="users-table__btn-icon" />
                      <ShieldCheck v-else :size="14" class="users-table__btn-icon" />
                      {{ user.status === 'ACTIVE' ? '封禁' : '解封' }}
                    </el-button>
                    <el-button
                      v-if="user.roleManageable"
                      :data-test="`user-role-${user.id}`"
                      size="small"
                      plain
                      class="users-table__btn users-table__btn--ghost"
                      :loading="savingUserId === user.id"
                      @click="toggleRole(user)"
                    >{{ user.role === 'ADMIN' ? '设为用户' : '设为管理员' }}</el-button>
                  </template>
                  <span v-else :data-test="`user-protected-${user.id}`" class="admin-muted users-table__protected">不可操作</span>
                </div>
              </td>
            </tr>
            <tr v-if="!users.length"><td colspan="8" class="admin-empty">暂无用户</td></tr>
          </tbody>
        </table>
      </div>
      <div class="admin-pagination"><span>共 {{ usersTotal }} 条</span><el-pagination data-test="users-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="usersParams.page + 1" :page-size="usersParams.size" :page-sizes="pageSizeOptions" :total="usersTotal" @size-change="changeUsersPageSize" @current-change="(value: number) => loadUsers(value - 1)" /></div>
    </section>

    <el-drawer v-model="detailOpen" title="用户详情" size="min(620px, 100%)">
      <el-skeleton v-if="detailLoading" :rows="6" animated />
      <template v-else-if="selectedDetail">
        <div class="user-detail-summary">
          <el-avatar :size="52" :src="avatarUrls[selectedDetail.user.id]">{{ avatarInitial(selectedDetail.user) }}</el-avatar>
          <div class="user-detail-summary__info">
            <strong class="user-detail-summary__name">{{ userNameLabel(selectedDetail.user.displayName) }}</strong>
            <p class="user-detail-summary__meta">{{ userNoLabel(selectedDetail.user.userNo) }} · {{ adminRoleLabel(selectedDetail.user.role) }} · {{ userAccountStatusLabel(selectedDetail.user.status) }}</p>
            <div class="user-detail-summary__contacts">
              <div class="user-detail-summary__contact user-detail-summary__contact--phone" :title="userPhone(selectedDetail.user)">
                <span class="user-detail-summary__contact-icon"><Phone :size="14" /></span>
                <span>{{ userPhone(selectedDetail.user) }}</span>
              </div>
              <div class="user-detail-summary__contact user-detail-summary__contact--email" :title="userContact(selectedDetail.user)">
                <span class="user-detail-summary__contact-icon"><Mail :size="14" /></span>
                <span>{{ userContact(selectedDetail.user) }}</span>
              </div>
            </div>
          </div>
        </div>
        <dl v-if="selectedDetail.user.status === 'DISABLED'" class="disabled-account-detail">
          <div><dt>封禁原因</dt><dd>{{ selectedDetail.user.disabledReason || '账号存在异常，请提交申诉或联系管理员。' }}</dd></div>
          <div><dt>封禁时间</dt><dd>{{ selectedDetail.user.disabledAt ? formatDateTime(selectedDetail.user.disabledAt) : '未记录' }}</dd></div>
        </dl>
        <h3 class="detail-heading">套餐记录</h3>
        <div class="table-scroll"><table class="admin-table detail-grants"><thead><tr><th>套餐</th><th>状态</th><th>原因</th><th>发放时间</th></tr></thead><tbody><tr v-for="grant in selectedDetail.grants" :key="grant.businessNo"><td><strong>{{ userPlanName(grant.packageCode, grant.packageName) }} v{{ grant.version }}</strong><br><code>{{ grant.packageCode }}</code></td><td><el-tag size="small" :type="grant.status === 'ACTIVE' ? 'success' : grant.status === 'FAILED' ? 'danger' : 'warning'">{{ adminStatusLabel(grant.status) }}</el-tag></td><td>{{ grantReasonLabel(grant.reason) }}</td><td>{{ formatDateTime(grant.createdAt) }}</td></tr><tr v-if="!selectedDetail.grants.length"><td colspan="4" class="admin-empty">暂无套餐记录</td></tr></tbody></table></div>
      </template>
    </el-drawer>
  </section>
</template>

<style scoped>
.users-panel { padding: 14px; }
.user-summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.user-summary-card {
  display: grid;
  align-content: start;
  grid-template-columns: 34px minmax(0, 1fr);
  grid-template-rows: 30px auto auto;
  gap: 2px 12px;
  min-height: 118px;
  padding: 16px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
  color: var(--app-text);
  text-align: left;
  cursor: pointer;
  font: inherit;
  transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}
.user-summary-card--insight { cursor: default; }
.user-summary-card--insight:hover { transform: none; }
.user-summary-card__icon {
  display: inline-grid;
  width: 30px;
  height: 30px;
  grid-row: 1 / span 3;
  place-items: center;
  color: var(--app-primary);
  background: var(--app-primary-soft);
  border-radius: 6px;
}
.user-summary-card__label {
  align-self: center;
  color: var(--app-muted);
  font-size: 12px;
  letter-spacing: 0.02em;
}
.user-summary-card__value {
  margin-top: 4px;
  font-size: 24px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.01em;
  color: var(--app-fg);
}
.user-summary-card__hint {
  margin-top: 4px;
  color: var(--app-muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.user-summary-card__bar {
  grid-column: 2;
  margin-top: 8px;
  height: 6px;
  background: var(--app-surface-muted);
  border-radius: 3px;
  overflow: hidden;
}
.user-summary-card__bar-fill {
  height: 100%;
  background: var(--app-accent);
  border-radius: 3px;
  transition: width 320ms ease;
}
.user-summary-card:hover { border-color: var(--app-border-strong); transform: translateY(-1px); }
.user-summary-card.active { border-color: var(--app-primary); box-shadow: 0 0 0 2px var(--app-primary-soft); }
.user-summary-card.active-users .user-summary-card__icon { color: var(--app-success); background: var(--app-success-soft); }
.user-summary-card.active-users.active { border-color: var(--app-success); box-shadow: 0 0 0 2px var(--app-success-soft); }
.user-summary-card.banned .user-summary-card__icon { color: var(--app-danger); background: var(--app-danger-soft); }
.user-summary-card.banned .user-summary-card__value { color: var(--app-danger); }
.user-summary-card.banned.active { border-color: var(--app-danger); box-shadow: 0 0 0 2px var(--app-danger-soft); }
.user-summary-card--insight .user-summary-card__icon { color: var(--app-accent); background: var(--app-accent-soft); }
.user-summary-card--insight { cursor: pointer; }
.user-summary-card--insight:hover { border-color: var(--app-border-strong); transform: translateY(-1px); }
.user-summary-card--insight.active { border-color: var(--app-accent); box-shadow: 0 0 0 2px var(--app-accent-soft); }
.admin-toolbar { padding-top: 2px; }
.users-table { min-width: 1120px; table-layout: fixed; border-collapse: separate; border-spacing: 0; }
/* table-layout: fixed 依赖 thead 第 1 行的列宽，这里统一给 th 和 td 按语义列设置宽度，与 BEM class 配套 */
.users-table th:nth-child(1), .users-table td:nth-child(1) { width: 56px; }   /* 头像 */
.users-table th:nth-child(2), .users-table td:nth-child(2) { width: 120px; }  /* 用户编号 */
.users-table th:nth-child(3), .users-table td:nth-child(3) { width: 140px; }  /* 用户名 */
.users-table th:nth-child(4), .users-table td:nth-child(4) { width: 150px; }  /* 当前套餐 */
.users-table th:nth-child(5), .users-table td:nth-child(5) { width: 80px; }   /* 状态 */
.users-table th:nth-child(6), .users-table td:nth-child(6) { width: 100px; }  /* 角色 */
.users-table th:nth-child(7), .users-table td:nth-child(7) { width: 172px; }  /* 最近登录 */
.users-table th:nth-child(8), .users-table td:nth-child(8) { width: 260px; }  /* 操作 */
.users-table th {
  padding: 8px 10px;
  background: var(--app-surface-muted);
  color: var(--app-muted-strong);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.03em;
  text-align: left;
  border-bottom: 1px solid var(--app-border);
}
.users-table td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--app-surface-sunken);
  vertical-align: middle;
  line-height: 1.25;
  color: var(--app-fg);
}
.users-table__row:nth-child(even) td { background: #FAFBFD; }
.users-table__row:hover td { background: #F3F6FB !important; }
.users-table__cell-avatar { text-align: center; }
.users-table__cell-no { font-variant-numeric: tabular-nums; color: var(--app-muted-strong); }
.users-table__cell-no strong { display: block; color: var(--app-fg); font-size: 13px; font-weight: 600; letter-spacing: -0.01em; }
.users-table__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.users-table__name-text {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--app-fg);
  font-size: 13px;
  font-weight: 600;
}
.users-table__tag {
  padding: 2px 8px !important;
  border-radius: 4px !important;
  font-size: 11px !important;
  font-weight: 600 !important;
  height: auto !important;
  line-height: 1.4 !important;
  border: 0 !important;
  display: inline-block !important;
  vertical-align: middle;
}
.users-table__tag--muted { color: var(--app-muted-strong) !important; background: var(--app-surface-subtle) !important; border: 1px solid var(--app-border) !important; }
.users-table__tag--role { min-height: 20px; }
.users-table__cell-login { line-height: 1.15; }
.users-table__login-time {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--app-fg);
  line-height: 1.2;
}
.users-table__login-never {
  display: inline-block;
  padding: 1px 7px;
  color: var(--app-muted-strong);
  background: var(--app-surface-subtle);
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
  line-height: 1.4;
}
.users-table__login-sub {
  margin-top: 2px;
  color: var(--app-muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}
.users-table__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  align-items: center;
  gap: 6px;
  min-height: 0;
}
.users-table__btn {
  padding: 4px 8px !important;
  font-size: 12px !important;
  border-radius: 5px !important;
  height: 26px !important;
  min-height: 26px !important;
  line-height: 1.2 !important;
}
.users-table__btn-icon { vertical-align: -1px; }
.users-table__btn--ghost { color: var(--app-muted-strong) !important; border-color: var(--app-border) !important; background: var(--app-surface) !important; }
.users-table__btn--ghost:hover { color: var(--app-primary) !important; border-color: var(--app-primary) !important; }
.users-table__protected { font-size: 11px; padding-right: 4px; color: var(--app-muted); }
.user-detail-summary { display: flex; align-items: flex-start; gap: 14px; padding-bottom: 16px; border-bottom: 1px solid var(--app-border); }
.user-detail-summary__info { display: grid; gap: 3px; min-width: 0; }
.user-detail-summary__name { font-size: 17px; line-height: 1.3; color: var(--app-fg); }
.user-detail-summary__meta { margin: 0; color: var(--app-muted); font-size: 13px; line-height: 1.3; }
.user-detail-summary__contacts {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 6px;
}
.user-detail-summary__contact {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  max-width: 100%;
}
.user-detail-summary__contact-icon { display: inline-flex; opacity: 0.9; }
.user-detail-summary__contact span:last-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.user-detail-summary__contact--email {
  color: var(--app-primary);
  background: var(--app-primary-soft);
}
.user-detail-summary__contact--phone {
  color: var(--app-success);
  background: var(--app-success-soft);
}
.disabled-account-detail { display: grid; gap: 10px; margin: 16px 0 0; padding: 14px; border: 1px solid var(--el-color-danger-light-7); border-radius: 6px; background: var(--el-color-danger-light-9); }
.disabled-account-detail div { display: grid; grid-template-columns: 76px minmax(0, 1fr); gap: 12px; }
.disabled-account-detail dt { color: var(--app-muted); }
.disabled-account-detail dd { margin: 0; overflow-wrap: anywhere; }
.detail-heading { margin: 20px 0 10px; font-size: 15px; }
.detail-grants { min-width: 560px; }
@media (max-width: 1000px) { .user-summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .user-summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>

<style>
/* 鼠标跟随悬浮 — 全局 position: fixed，跟随 clientX/clientY */
.user-follow-tip {
  position: fixed;
  z-index: 3000;
  max-width: 200px;
  padding: 6px 10px;
  color: var(--app-fg);
  font-size: 12px;
  line-height: 1.45;
  background: #FFFFFF;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  box-shadow: 0 2px 8px rgb(21 33 58 / 10%);
  pointer-events: none;
}
.user-follow-tip strong {
  display: block;
  margin-bottom: 2px;
  font-size: 12px;
  font-weight: 700;
  color: var(--app-fg);
}
.user-follow-tip p {
  margin: 0;
  color: #4B5563;
}
.user-follow-tip__action {
  display: inline-block;
  margin-top: 5px;
  padding: 2px 7px;
  color: var(--app-primary);
  background: var(--app-primary-soft);
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.4;
}
</style>