<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ClipboardCopy, Gift, RefreshCw, Search, TrendingUp, CheckCircle2, XCircle, Clock } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'

import {
  businessNo,
  createGrant,
  listAdminPlans,
  listAdminUsers,
  listGrants,
  type AdminListParams,
  type AdminGrant,
  type AdminPlanVersion,
  type AdminUser,
} from '../../api/admin'
import { formatDateTime } from '../../utils/dateTime'
import { adminStatusLabel, grantReasonLabel, userNameLabel, userNoLabel, userOptionLabel } from '../../utils/adminDisplay'
import { loadAllPages } from '../../utils/adminPagination'
import { userPlanName } from '../../utils/planCopy'

const users = ref<AdminUser[]>([])
const plans = ref<AdminPlanVersion[]>([])
const grants = ref<AdminGrant[]>([])
const allGrants = ref<AdminGrant[]>([])
const total = ref(0)
const optionsLoading = ref(true)
const grantsLoading = ref(true)
const expiryLoading = ref(false)
const loading = computed(() => optionsLoading.value || grantsLoading.value)
const submitting = ref(false)
const errorMessage = ref('')
const pageSizeOptions = [5, 10, 20]
const form = reactive({ userId: '', packageVersionId: '', reason: '' })
const params = reactive<AdminListParams>({
  page: 0, size: 20, query: '', status: '', sort: 'createdAt', direction: 'desc',
})

async function loadOptions() {
  optionsLoading.value = true
  errorMessage.value = ''
  try {
    const [allUsers, allPlans] = await Promise.all([
      loadAllPages((page, size) => listAdminUsers({ page, size, query: '', status: 'ACTIVE', sort: 'userNo', direction: 'asc' })),
      loadAllPages((page, size) => listAdminPlans({ page, size, query: '', status: 'PUBLISHED', sort: 'code', direction: 'asc' })),
    ])
    users.value = allUsers
    plans.value = allPlans.filter(plan => plan.planStatus === 'ACTIVE')
    if (!plans.value.some(plan => plan.versionId === form.packageVersionId)) {
      form.packageVersionId = ''
    }
  } catch {
    errorMessage.value = '用户或套餐选择项加载失败，请稍后重试。'
  } finally {
    optionsLoading.value = false
  }
}

async function loadGrants(targetPage = params.page) {
  grantsLoading.value = true
  errorMessage.value = ''
  try {
    const grantPage = await listGrants({ ...params, page: targetPage })
    grants.value = grantPage.items
    total.value = grantPage.total
    params.page = grantPage.page
  } catch {
    errorMessage.value = '套餐发放记录加载失败，请稍后重试。'
  } finally {
    grantsLoading.value = false
  }
}

function changePageSize(size: number) {
  params.size = size
  void loadGrants(0)
}

function load() {
  return Promise.all([loadOptions(), loadGrants(), loadExpiringGrants()])
}

function shortBusinessNo(value: string) {
  return value.length > 20 ? `${value.slice(0, 11)}...${value.slice(-6)}` : value
}

async function copyBusinessNo(value: string) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value)
    } else {
      const input = document.createElement('textarea')
      input.value = value
      input.style.position = 'fixed'
      input.style.opacity = '0'
      document.body.appendChild(input)
      input.select()
      document.execCommand('copy')
      input.remove()
    }
    ElMessage.success('业务号已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

async function submit() {
  if (!form.userId || !form.packageVersionId || !form.reason.trim()) {
    errorMessage.value = '请选择用户和套餐版本，并填写发放原因。'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    const result = await createGrant({
      userId: form.userId,
      packageVersionId: form.packageVersionId,
      reason: form.reason.trim(),
      businessNo: businessNo('grant'),
    })
    ElMessage.success(`发放请求已创建：${result.businessNo}`)
    form.reason = ''
    await loadGrants()
  } catch {
    errorMessage.value = '套餐发放失败，请核对用户、套餐状态和业务号后重试。'
  } finally {
    submitting.value = false
  }
}

function isSameDay(iso: string): boolean {
  const d = new Date(iso)
  const now = new Date()
  return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
}

const todayCount = computed(() => allGrants.value.filter(g => isSameDay(g.createdAt)).length)
const activeCount = computed(() => allGrants.value.filter(g => g.status === 'ACTIVE').length)
const failedCount = computed(() => allGrants.value.filter(g => g.status === 'FAILED').length)

const planDurationMap = computed(() => {
  const map = new Map<string, number>()
  for (const p of plans.value) {
    map.set(`${p.code}#${p.version}`, p.durationDays)
  }
  return map
})

function grantExpiryDate(grant: AdminGrant): Date | null {
  const days = planDurationMap.value.get(`${grant.packageCode}#${grant.version}`)
  if (!days || !grant.createdAt) return null
  return new Date(new Date(grant.createdAt).getTime() + days * 86400000)
}

const recentGrants = computed(() => grants.value.slice(0, 5))

function expiryLabel(grant: AdminGrant): string {
  const expiry = grantExpiryDate(grant)
  if (!expiry) return '—'
  const now = new Date()
  const diff = Math.ceil((expiry.getTime() - now.getTime()) / 86400000)
  if (diff < 0) return '已过期'
  if (diff <= 7) return `${diff}天后到期`
  return formatDateTime(expiry.toISOString())
}

function grantStatusClass(status: string): string {
  if (status === 'ACTIVE') return 'grant-status--active'
  if (status === 'FAILED') return 'grant-status--failed'
  return 'grant-status--pending'
}

async function loadExpiringGrants() {
  expiryLoading.value = true
  try {
    allGrants.value = await loadAllPages((page, size) => listGrants({ page, size, query: '', status: 'ACTIVE', sort: 'createdAt', direction: 'desc' }))
  } catch {
    allGrants.value = []
  } finally {
    expiryLoading.value = false
  }
}

const expiringGrants = computed(() => {
  const now = Date.now()
  const threshold = now + 7 * 86400000
  return allGrants.value.filter(g => {
    if (g.status !== 'ACTIVE') return false
    const expiry = grantExpiryDate(g)
    return expiry !== null && expiry.getTime() > now && expiry.getTime() <= threshold
  }).slice(0, 10)
})

onMounted(load)
</script>

<template>
  <section class="admin-page grant-page" aria-labelledby="admin-grants-title">
    <header class="admin-page__header">
      <div><h2 id="admin-grants-title">套餐发放</h2><p>创建可审计的手工发放并跟踪事务消息同步状态</p></div>
      <el-button aria-label="刷新套餐发放" :loading="loading" @click="load"><RefreshCw :size="16" />刷新</el-button>
    </header>
    <TopErrorMessage :message="errorMessage" />

    <section class="grant-stats" aria-label="发放统计">
      <div class="grant-stat-card">
        <span class="grant-stat-card__icon"><TrendingUp :size="18" aria-hidden="true" /></span>
        <span class="grant-stat-card__label">今日发放</span>
        <strong class="grant-stat-card__value">{{ todayCount }}</strong>
        <span class="grant-stat-card__hint">本页内统计</span>
      </div>
      <div class="grant-stat-card">
        <span class="grant-stat-card__icon"><Gift :size="18" aria-hidden="true" /></span>
        <span class="grant-stat-card__label">总发放数</span>
        <strong class="grant-stat-card__value">{{ total }}</strong>
        <span class="grant-stat-card__hint">全量记录</span>
      </div>
      <div class="grant-stat-card grant-stat-card--success">
        <span class="grant-stat-card__icon"><CheckCircle2 :size="18" aria-hidden="true" /></span>
        <span class="grant-stat-card__label">已激活</span>
        <strong class="grant-stat-card__value">{{ activeCount }}</strong>
        <span class="grant-stat-card__hint">正常使用中</span>
      </div>
      <div class="grant-stat-card grant-stat-card--danger">
        <span class="grant-stat-card__icon"><XCircle :size="18" aria-hidden="true" /></span>
        <span class="grant-stat-card__label">失败/过期</span>
        <strong class="grant-stat-card__value">{{ failedCount }}</strong>
        <span class="grant-stat-card__hint">需关注</span>
      </div>
    </section>

    <div class="grant-layout">
      <div class="grant-layout__main">
        <div class="admin-panel grant-form-panel">
          <div class="admin-panel__header"><div><h3>新建发放</h3><p>每次提交自动生成唯一业务号</p></div><Gift :size="20" aria-hidden="true" /></div>
          <div class="grant-form admin-form-grid">
            <label>用户编号
              <el-select v-model="form.userId" data-test="grant-user" filterable placeholder="选择启用用户">
                <el-option v-for="user in users" :key="user.id" :label="userOptionLabel(user.userNo, user.displayName)" :value="user.id" />
              </el-select>
            </label>
            <label>套餐版本
              <el-select v-model="form.packageVersionId" data-test="grant-plan" filterable placeholder="选择已发布版本">
                <el-option v-for="plan in plans" :key="plan.versionId" :label="`${plan.name} v${plan.version}`" :value="plan.versionId" />
              </el-select>
            </label>
            <label class="is-full">发放原因
              <el-input v-model="form.reason" data-test="grant-reason" maxlength="500" show-word-limit placeholder="填写可审计的发放原因" />
            </label>
            <div class="is-full admin-actions"><el-button data-test="grant-submit" type="primary" :loading="submitting" @click="submit">确认发放</el-button></div>
          </div>
        </div>

        <div class="admin-panel">
          <div class="admin-panel__header"><div><h3>发放记录</h3><p>共 {{ total }} 条，状态由平台事务和跨服务同步共同驱动</p></div></div>
          <div class="admin-toolbar grant-toolbar">
            <el-input v-model="params.query" data-test="grant-query" aria-label="搜索发放记录" clearable placeholder="搜索业务号、用户或套餐" @keyup.enter="loadGrants(0)" />
            <el-select v-model="params.status" data-test="grant-status" aria-label="发放状态" clearable placeholder="全部状态"><el-option label="排队中" value="QUEUED" /><el-option label="已激活" value="ACTIVE" /><el-option label="失败" value="FAILED" /></el-select>
            <el-select v-model="params.sort" data-test="grant-sort" aria-label="发放排序字段"><el-option label="创建时间" value="createdAt" /><el-option label="状态" value="status" /><el-option label="业务号" value="businessNo" /></el-select>
            <el-select v-model="params.direction" data-test="grant-direction" aria-label="发放排序方向"><el-option label="降序" value="desc" /><el-option label="升序" value="asc" /></el-select>
            <el-button data-test="grant-filter" type="primary" :loading="grantsLoading" @click="loadGrants(0)"><Search :size="16" />查询</el-button>
          </div>
          <el-skeleton v-if="grantsLoading" :rows="5" animated />
          <div v-else class="table-scroll"><table class="admin-table grant-table"><thead><tr><th>业务号</th><th>用户编号</th><th>用户名</th><th>套餐</th><th>发放状态</th><th>事务消息状态</th><th>到期</th><th>尝试</th><th>原因</th><th>创建时间</th></tr></thead><tbody>
              <tr v-for="grant in grants" :key="grant.id" :class="grantStatusClass(grant.status)">
                <td><div class="business-no"><code :title="grant.businessNo">{{ shortBusinessNo(grant.businessNo) }}</code><el-button text :aria-label="`复制业务号 ${grant.businessNo}`" @click="copyBusinessNo(grant.businessNo)"><ClipboardCopy :size="15" /></el-button></div></td>
                <td>{{ userNoLabel(grant.userNo) }}</td>
                <td>{{ userNameLabel(grant.userDisplayName) }}</td>
                <td class="grant-table__plan">{{ userPlanName(grant.packageCode, grant.packageName) }} v{{ grant.version }}</td>
                <td><el-tag size="small" :type="grant.status === 'ACTIVE' ? 'success' : grant.status === 'FAILED' ? 'danger' : 'warning'">{{ adminStatusLabel(grant.status) }}</el-tag></td>
                <td><el-tag size="small" :type="grant.outboxStatus === 'DELIVERED' ? 'success' : grant.outboxStatus === 'FAILED' ? 'danger' : 'info'">{{ adminStatusLabel(grant.outboxStatus || 'PENDING') }}</el-tag></td>
                <td class="grant-table__expiry">{{ expiryLabel(grant) }}</td>
                <td class="grant-table__num">{{ grant.attemptCount }}</td>
                <td>{{ grantReasonLabel(grant.reason) }}<small v-if="grant.failureReason" class="failure-reason">{{ grant.failureReason }}</small></td>
                <td class="grant-table__time">{{ formatDateTime(grant.createdAt) }}</td>
              </tr>
              <tr v-if="!grants.length"><td colspan="10" class="admin-empty">暂无发放记录</td></tr>
            </tbody></table></div>
          <div class="admin-pagination"><span>共 {{ total }} 条</span><el-pagination data-test="grant-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="params.page + 1" :page-size="params.size" :page-sizes="pageSizeOptions" :total="total" @size-change="changePageSize" @current-change="(value: number) => loadGrants(value - 1)" /></div>
        </div>
      </div>

      <aside class="grant-layout__side">
        <div class="admin-panel grant-side-panel">
          <div class="admin-panel__header"><div><h3>最近发放记录</h3><p>最新 5 条</p></div><Clock :size="18" aria-hidden="true" /></div>
          <ul class="grant-recent-list">
            <li v-for="grant in recentGrants" :key="grant.id">
              <div class="grant-recent-item__head">
                <strong class="grant-recent-item__user">{{ userNameLabel(grant.userDisplayName) }}</strong>
                <span class="grant-recent-item__plan">{{ userPlanName(grant.packageCode, grant.packageName) }} v{{ grant.version }}</span>
              </div>
              <div class="grant-recent-item__meta">
                <span>{{ userNoLabel(grant.userNo) }}</span>
                <span class="grant-recent-item__time">{{ formatDateTime(grant.createdAt) }}</span>
              </div>
              <div class="grant-recent-item__status">
                <el-tag size="small" :type="grant.status === 'ACTIVE' ? 'success' : grant.status === 'FAILED' ? 'danger' : 'warning'">{{ adminStatusLabel(grant.status) }}</el-tag>
              </div>
            </li>
            <li v-if="!recentGrants.length" class="grant-recent-empty">暂无最近发放记录</li>
          </ul>
        </div>

        <div class="admin-panel grant-side-panel">
          <div class="admin-panel__header"><div><h3>到期提醒</h3><p>{{ expiringGrants.length }} 条即将到期</p></div><Clock :size="18" aria-hidden="true" /></div>
          <ul class="grant-recent-list">
            <li v-for="grant in expiringGrants" :key="`exp-${grant.id}`">
              <div class="grant-recent-item__head">
                <strong class="grant-recent-item__user">{{ userNameLabel(grant.userDisplayName) }}</strong>
                <span class="grant-recent-item__plan grant-recent-item__plan--warning">{{ expiryLabel(grant) }}</span>
              </div>
              <div class="grant-recent-item__meta">
                <span>{{ userPlanName(grant.packageCode, grant.packageName) }} v{{ grant.version }}</span>
              </div>
            </li>
            <li v-if="!expiringGrants.length" class="grant-recent-empty">暂无即将到期套餐</li>
          </ul>
        </div>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.grant-stats {
  display: grid;
  gap: 12px;
  margin-bottom: 16px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}
.grant-stat-card {
  display: grid;
  padding: 14px 16px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 8px;
  grid-template-columns: 32px minmax(0, 1fr);
  grid-template-rows: auto auto auto;
  transition: border-color var(--app-motion-fast) ease;
}
.grant-stat-card:hover { border-color: var(--app-border-strong); }
.grant-stat-card__icon {
  display: inline-grid;
  width: 30px;
  height: 30px;
  grid-row: 1 / span 3;
  place-items: center;
  color: var(--app-primary);
  background: var(--app-primary-soft);
  border-radius: 6px;
}
.grant-stat-card__label {
  align-self: center;
  color: var(--app-muted);
  font-size: 12px;
  letter-spacing: 0.02em;
}
.grant-stat-card__value {
  margin-top: 2px;
  font-size: 24px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.01em;
  color: var(--app-fg);
}
.grant-stat-card__hint {
  margin-top: 2px;
  color: var(--app-muted);
  font-size: 11px;
}
.grant-stat-card--success .grant-stat-card__icon { color: var(--app-success); background: var(--app-success-soft); }
.grant-stat-card--success .grant-stat-card__value { color: var(--app-success); }
.grant-stat-card--danger .grant-stat-card__icon { color: var(--app-danger); background: var(--app-danger-soft); }
.grant-stat-card--danger .grant-stat-card__value { color: var(--app-danger); }

.grant-layout {
  display: grid;
  gap: 16px;
  grid-template-columns: minmax(0, 3fr) minmax(260px, 1fr);
}
.grant-layout__main { min-width: 0; }
.grant-layout__side {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.grant-page .admin-panel {
  border-radius: 8px;
  box-shadow: var(--app-shadow-xs);
}
.grant-page .grant-form-panel { margin-bottom: 0; }

.grant-form { padding: 14px; }
.grant-form label { display: grid; gap: 6px; color: var(--app-fg); font-size: 13px; font-weight: 600; }
.grant-form .el-select { width: 100%; }
.grant-toolbar { padding: 12px 14px 0; }

.grant-table { min-width: 960px; table-layout: auto; }
.grant-table th {
  padding: 8px 10px;
  background: var(--app-surface-muted);
  color: var(--app-muted-strong);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.03em;
}
.grant-table td {
  padding: 9px 10px;
  border-bottom: 1px solid var(--app-surface-sunken);
  vertical-align: middle;
  line-height: 1.3;
}
.grant-table tbody tr.grant-status--active {
  border-left: 3px solid var(--app-success);
}
.grant-table tbody tr.grant-status--failed {
  border-left: 3px solid var(--app-danger);
}
.grant-table tbody tr.grant-status--pending {
  border-left: 3px solid var(--app-warning);
}
.grant-table__plan { font-weight: 600; color: var(--app-fg); }
.grant-table__expiry { font-size: 12px; color: var(--app-muted-strong); font-variant-numeric: tabular-nums; }
.grant-table__num { font-variant-numeric: tabular-nums; text-align: center; }
.grant-table__time { font-size: 12px; color: var(--app-muted); font-variant-numeric: tabular-nums; white-space: nowrap; }

.business-no { display: flex; align-items: center; gap: 2px; }
.failure-reason { display: block; margin-top: 3px; color: var(--el-color-danger); }

.grant-side-panel { box-shadow: var(--app-shadow-xs); }
.grant-recent-list {
  flex: 1;
  margin: 0;
  padding: 0 14px 12px;
  list-style: none;
}
.grant-recent-list li {
  display: grid;
  gap: 4px;
  padding: 11px 0;
  border-top: 1px solid var(--app-border);
}
.grant-recent-list li:first-child { border-top: 0; }
.grant-recent-item__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.grant-recent-item__user {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-fg);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.grant-recent-item__plan {
  font-size: 12px;
  color: var(--app-muted-strong);
  white-space: nowrap;
}
.grant-recent-item__plan--warning {
  color: var(--app-warning);
  font-weight: 600;
}
.grant-recent-item__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--app-muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.grant-recent-item__time { white-space: nowrap; }
.grant-recent-item__status { display: flex; align-items: center; gap: 5px; }
.grant-recent-empty {
  padding: 28px 16px;
  color: var(--app-muted);
  text-align: center;
  font-size: 13px;
}

@media (max-width: 1100px) {
  .grant-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .grant-layout { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 600px) {
  .grant-stats { grid-template-columns: minmax(0, 1fr); }
  .grant-form-grid { grid-template-columns: minmax(0, 1fr); }
}
</style>
