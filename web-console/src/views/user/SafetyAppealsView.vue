<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ShieldAlert, ShieldCheck } from 'lucide-vue-next'
import { contentSafetyApi, type SafetyAppeal, type UserSafetyCase } from '../../api/contentSafety'
import { formatDateTime } from '../../utils/dateTime'

const appeals = ref<SafetyAppeal[]>([])
const safetyCases = ref<UserSafetyCase[]>([])
const selectedCase = ref<UserSafetyCase | null>(null)
const reason = ref('')
const caseLoading = ref(false)
const appealLoading = ref(false)
const submitting = ref(false)
const withdrawingBusinessNo = ref('')
const recordsLoaded = ref(false)
const pageSize = 20
const casePage = ref(0)
const caseTotal = ref(0)
const appealPage = ref(0)
const appealTotal = ref(0)

const pendingAppealCount = ref(0)

const appealStatusLabels: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回',
}

const caseSeverityLabels: Record<string, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  CRITICAL: '严重',
}

const caseActionLabels: Record<string, string> = {
  REVIEW: '需要复核',
  BLOCK: '已拦截',
}

function appealStatusType(status: string) {
  if (status === 'PENDING') return 'warning'
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'danger'
  return 'info'
}

function appealStatusLabel(status?: string) {
  return status ? appealStatusLabels[status] || status : '-'
}

function caseSeverityLabel(severity: string) {
  return caseSeverityLabels[severity] || severity
}

function caseSeverityType(severity: string) {
  if (severity === 'LOW') return 'success'
  if (severity === 'MEDIUM') return 'warning'
  if (severity === 'HIGH') return 'warning'
  return 'danger'
}

function caseActionLabel(action: string) {
  return caseActionLabels[action] || action
}

async function loadCases() {
  caseLoading.value = true
  try {
    const response = await contentSafetyApi.userCases({ page: casePage.value, size: pageSize })
    safetyCases.value = response.data.items
    caseTotal.value = response.data.total
    casePage.value = response.data.page
  } finally {
    caseLoading.value = false
  }
}

async function loadAppeals() {
  appealLoading.value = true
  try {
    const response = await contentSafetyApi.appeals({ page: appealPage.value, size: pageSize })
    appeals.value = response.data.items
    appealTotal.value = response.data.total
    appealPage.value = response.data.page
    pendingAppealCount.value = response.data.items.filter((a) => a.status === 'PENDING').length
  } finally {
    appealLoading.value = false
  }
}

async function load() {
  recordsLoaded.value = false
  try {
    await Promise.all([loadAppeals(), loadCases()])
    recordsLoaded.value = true
  } catch {
    ElMessage.error('申诉记录加载失败')
  }
}

async function changeCasePage(page: number) {
  casePage.value = page - 1
  try {
    await loadCases()
  } catch {
    ElMessage.error('可申诉记录加载失败')
  }
}

async function changeAppealPage(page: number) {
  appealPage.value = page - 1
  try {
    await loadAppeals()
  } catch {
    ElMessage.error('申诉记录加载失败')
  }
}

function errorCode(error: unknown): string | undefined {
  const response = (error as { response?: { data?: { code?: string } } }).response
  return response?.data?.code
}

function startAppeal(item: UserSafetyCase) {
  selectedCase.value = item
  reason.value = ''
}

function cancelAppeal() {
  selectedCase.value = null
  reason.value = ''
}

async function submit() {
  if (!selectedCase.value) {
    ElMessage.warning('请先选择要申诉的记录')
    return
  }
  if (!reason.value.trim()) {
    ElMessage.warning('请填写申诉理由')
    return
  }
  submitting.value = true
  try {
    await contentSafetyApi.createAppeal(selectedCase.value.caseNo, reason.value.trim())
    cancelAppeal()
    ElMessage.success('申诉已提交')
    casePage.value = 0
    appealPage.value = 0
    await load()
  } catch (error) {
    if (errorCode(error) === 'content_safety_case_not_found') {
      ElMessage.error('该记录已不存在或不属于当前账号，请刷新后重试')
    } else {
      ElMessage.error('申诉提交失败，请稍后重试')
    }
  } finally {
    submitting.value = false
  }
}

async function withdrawAppeal(item: SafetyAppeal) {
  if (item.status !== 'PENDING') return
  try {
    await ElMessageBox.confirm('撤回后该申诉将不再进入审核，确定继续吗？', '撤回申诉', {
      confirmButtonText: '确认撤回',
      cancelButtonText: '取消',
      type: 'warning',
    })
    withdrawingBusinessNo.value = item.businessNo
    await contentSafetyApi.withdrawAppeal(item.businessNo)
    ElMessage.success('申诉已撤回')
    await Promise.all([loadAppeals(), loadCases()])
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(errorCode(error) === 'appeal_not_withdrawable' ? '该申诉已处理，无法撤回' : '撤回申诉失败，请稍后重试')
    }
  } finally {
    withdrawingBusinessNo.value = ''
  }
}

onMounted(load)
</script>

<template>
  <section class="safety-page page-stack">
    <!-- Page header -->
    <div class="page-heading">
      <div>
        <h1>
          <span class="heading-icon"><ShieldAlert :size="18" /></span>
          安全申诉
        </h1>
        <p>仅展示当前账号可申诉的安全记录，提交后系统会自动生成申诉编号。</p>
      </div>
      <div class="heading-stats">
        <div class="heading-stat">
          <span class="stat-num">{{ caseTotal }}</span>
          <span class="stat-label">可申诉</span>
        </div>
        <div class="heading-stat">
          <span class="stat-num stat-warn">{{ pendingAppealCount }}</span>
          <span class="stat-label">待审核</span>
        </div>
        <div class="heading-stat">
          <span class="stat-num">{{ appealTotal }}</span>
          <span class="stat-label">我的申诉</span>
        </div>
      </div>
    </div>

    <!-- Policy card -->
    <el-card class="safety-policy-card">
      <div class="safety-policy-content">
        <span class="policy-icon"><ShieldAlert :size="22" aria-hidden="true" /></span>
        <div>
          <h2>账号限制与申诉说明</h2>
          <p>常见处理规则如下：</p>
          <ul>
            <li><strong>冻结：</strong>持续发送高频、异常或自动化请求，或账号、支付、API 密钥存在异常风险。</li>
            <li><strong>封禁：</strong>提交敏感、违法或恶意内容，上传恶意文件，绕过安全策略，或重复违规。</li>
          </ul>
          <p class="safety-policy-note">如认为处理有误，可提交申诉。待审核申诉可由您主动撤回，管理员也可在审核前手动撤回。</p>
        </div>
      </div>
    </el-card>

    <!-- Cases to appeal -->
    <el-card v-loading="caseLoading">
      <template #header>
        <div class="card-header">
          <span>可申诉记录</span>
          <span v-if="caseTotal > 0" class="card-header-count">{{ caseTotal }}</span>
        </div>
      </template>
      <div v-if="safetyCases.length" class="safety-case-list">
        <div
          v-for="item in safetyCases"
          :key="item.caseNo"
          class="safety-case-item"
          :class="`sev-${item.severity.toLowerCase()}`"
        >
          <div class="case-left-accent" aria-hidden="true" />
          <div class="safety-case-main">
            <div class="safety-case-title">
              <strong>{{ item.caseNo }}</strong>
              <el-tag size="small" :type="caseSeverityType(item.severity)" effect="light" round>
                {{ caseSeverityLabel(item.severity) }}
              </el-tag>
              <el-tag size="small" type="info" effect="plain" round>{{ caseActionLabel(item.action) }}</el-tag>
            </div>
            <div class="safety-case-meta">{{ item.category }} · {{ formatDateTime(item.createdAt) }}</div>
            <div v-if="item.redactedExcerpt" class="safety-case-excerpt">{{ item.redactedExcerpt }}</div>
          </div>
          <el-button type="primary" class="case-appeal-btn" @click="startAppeal(item)">提交申诉</el-button>
        </div>
      </div>
      <div
        v-else-if="recordsLoaded && !appeals.length"
        class="account-normal-notice"
        role="status"
      >
        <ShieldCheck :size="20" aria-hidden="true" />
        <span>当前您的账号正常，无需申诉</span>
      </div>
      <div v-else class="empty-state">暂无申诉记录</div>
      <el-pagination
        class="safety-pagination"
        background
        layout="total, prev, pager, next"
        :current-page="casePage + 1"
        :page-size="pageSize"
        :total="caseTotal"
        @current-change="changeCasePage"
      />
    </el-card>

    <!-- Appeal form (inline) -->
    <el-card v-if="selectedCase" class="appeal-form-card">
      <template #header>
        <div class="card-header">
          <span>提交申诉</span>
          <code class="case-no-inline">{{ selectedCase.caseNo }}</code>
        </div>
      </template>
      <el-form label-position="top">
        <el-form-item label="申诉理由">
          <el-input v-model="reason" type="textarea" :rows="4" maxlength="2000" show-word-limit placeholder="请说明你认为处理有误的原因（2000 字以内）" />
        </el-form-item>
        <div class="form-actions">
          <el-button @click="cancelAppeal">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="submit">确认提交申诉</el-button>
        </div>
      </el-form>
    </el-card>

    <!-- My appeals -->
    <el-card v-loading="appealLoading">
      <template #header>
        <div class="card-header">
          <span>我的申诉</span>
          <span v-if="appealTotal > 0" class="card-header-count">{{ appealTotal }}</span>
        </div>
      </template>
      <el-table :data="appeals" stripe size="default" class="appeal-table">
        <el-table-column prop="businessNo" label="申诉编号" width="190">
          <template #default="{ row }">
            <code class="appeal-no">{{ row.businessNo }}</code>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="appealStatusType(row.status)" effect="light" round>{{ appealStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="申诉理由" show-overflow-tooltip />
        <el-table-column prop="processingNote" label="审核备注" show-overflow-tooltip />
        <el-table-column label="操作" width="100" align="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'PENDING'"
              type="danger"
              link
              size="small"
              :loading="withdrawingBusinessNo === row.businessNo"
              :aria-label="`撤回申诉 ${row.businessNo}`"
              @click="withdrawAppeal(row)"
            >撤回</el-button>
            <span v-else class="operation-placeholder">-</span>
          </template>
        </el-table-column>
        <template #empty>暂无申诉记录</template>
      </el-table>
      <el-pagination
        class="safety-pagination"
        background
        layout="total, prev, pager, next"
        :current-page="appealPage + 1"
        :page-size="pageSize"
        :total="appealTotal"
        @current-change="changeAppealPage"
      />
    </el-card>
  </section>
</template>

<style scoped>
.safety-page {
  display: grid;
  gap: 16px;
  min-width: 0;
  max-width: 1080px;
  margin: 0 auto;
}
.page-stack {
  display: grid;
  gap: 16px;
}

.safety-page :deep(.el-card) {
  border-radius: 12px;
  border: 1px solid var(--app-border);
  box-shadow: none;
}
.safety-page :deep(.el-card__header) {
  padding: 14px 20px;
  border-bottom: 1px solid var(--app-border);
  font-size: 15px;
  font-weight: 600;
}
.safety-page :deep(.el-card__body) {
  padding: 18px 20px;
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
.heading-stats {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.heading-stat {
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
.stat-num {
  font-size: 17px;
  font-weight: 700;
  color: var(--app-fg);
  font-variant-numeric: tabular-nums;
}
.stat-warn { color: var(--app-danger); }
.stat-label {
  margin-top: 3px;
  font-size: 11px;
  color: var(--app-muted);
}

/* ========== 卡片头部（自定义） ========== */
.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
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
.case-no-inline {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  font-weight: 500;
  color: var(--app-muted-strong);
  padding: 1px 7px;
  background: var(--app-surface-muted);
  border-radius: 5px;
}

/* ========== 政策卡片 ========== */
.safety-policy-card {
  border-color: var(--app-primary-border) !important;
  background: var(--app-primary-soft);
  position: relative;
}
.safety-policy-card :deep(.el-card__body) {
  padding: 16px 20px;
}
.safety-policy-content {
  display: flex;
  gap: 14px;
  color: #245b91;
}
.policy-icon {
  display: inline-flex;
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: rgba(37, 99, 235, 0.1);
  color: var(--app-primary);
}
.safety-policy-content h2 {
  margin: 0;
  color: var(--app-fg);
  font-size: 15px;
  font-weight: 700;
  line-height: 24px;
}
.safety-policy-content p,
.safety-policy-content ul {
  margin: 6px 0 0;
  color: var(--app-muted-strong);
  font-size: 13px;
  line-height: 22px;
}
.safety-policy-content ul { padding-left: 20px; }
.safety-policy-note {
  color: var(--app-muted) !important;
  font-size: 12px !important;
}

/* ========== 案件列表 ========== */
.safety-case-list {
  display: grid;
  gap: 10px;
}

.safety-case-item {
  position: relative;
  display: flex;
  align-items: stretch;
  gap: 14px;
  padding: 14px 18px;
  border: 1px solid var(--app-border);
  border-radius: 10px;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
  overflow: hidden;
}
.safety-case-item:hover {
  border-color: var(--app-primary);
  box-shadow: 0 2px 10px rgba(37, 99, 235, 0.08);
}
.case-left-accent {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
}
.sev-low .case-left-accent { background: var(--app-success); }
.sev-medium .case-left-accent { background: var(--app-accent); }
.sev-high .case-left-accent { background: #f97316; }
.sev-critical .case-left-accent { background: var(--app-danger); }

.safety-case-main { min-width: 0; flex: 1; padding-left: 6px; }
.safety-case-title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.safety-case-title strong {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
  font-weight: 600;
  color: var(--app-fg);
}
.safety-case-meta {
  margin-top: 5px;
  font-size: 12px;
  color: var(--app-muted);
}
.safety-case-excerpt {
  margin-top: 6px;
  color: var(--app-muted-strong);
  font-size: 13px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.case-appeal-btn { flex-shrink: 0; align-self: center; }

/* ========== 账号正常提示 ========== */
.account-normal-notice {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 44px;
  padding: 12px 16px;
  color: #237a3b;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  background: #f0f9f2;
  border: 1px solid #b9e1c3;
  border-radius: 8px;
}
.account-normal-notice svg {
  flex: 0 0 auto;
  color: #2f9e4f;
}

.empty-state {
  color: var(--app-muted);
  font-size: 13px;
  padding: 20px 0;
  text-align: center;
}

/* ========== 申诉表格 ========== */
.appeal-no {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
  font-weight: 600;
  color: var(--app-fg);
}
.operation-placeholder {
  color: var(--app-muted);
}
.appeal-table :deep(.el-table__row) {
  height: 48px;
}
.appeal-table :deep(.el-table__body tr:hover > td) {
  background: rgba(37, 99, 235, 0.04) !important;
}

/* ========== 分页 ========== */
.safety-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

/* ========== 申诉表单卡片 ========== */
.appeal-form-card :deep(.el-form-item) {
  margin-bottom: 12px;
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* ========== 响应式 ========== */
@media (max-width: 767px) {
  .page-heading {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
  .heading-stats { width: 100%; }
  .heading-stat { flex: 1; min-width: 0; }
  .safety-case-item {
    flex-direction: column;
    align-items: stretch;
  }
  .case-appeal-btn { align-self: flex-end; }
}
</style>
