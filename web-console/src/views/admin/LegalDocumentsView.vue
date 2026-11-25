<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  History,
  Plus,
  RefreshCw,
  RotateCcw,
  Save,
  Send,
  Trash2,
} from 'lucide-vue-next'

import {
  getLegalDocuments,
  publishLegalDocument,
  restoreLegalDocumentVersion,
  saveLegalDocumentDraft,
  type AdminLegalDocument,
  type AdminLegalDocumentKind,
  type AdminLegalDocumentState,
  type LegalDocumentPayload,
  type LegalDocumentSection,
} from '../../api/admin'
import { readApiErrorCode, readApiErrorMessage } from '../../api/apiError'

/** 两份文档固定顺序：平台服务协议在前，隐私政策在后 */
const ADMIN_LEGAL_KINDS: AdminLegalDocumentKind[] = ['TERMS', 'PRIVACY']

const KIND_LABELS: Record<AdminLegalDocumentKind, string> = {
  TERMS: '平台服务协议',
  PRIVACY: '隐私政策',
}

/** 与后端 `LegalDocumentService` 的校验保持一致（标题 120 / 版本号 32） */
const MAX_TITLE_LENGTH = 120
const MAX_VERSION_LENGTH = 32

/** 编辑器里的条款：段落与列表项都是「一行一条」的纯文本，提交前才拆成数组 */
interface SectionDraft {
  heading: string
  paragraphsText: string
  itemsText: string
}

interface DocumentDraft {
  title: string
  placeholder: string
  sections: SectionDraft[]
}

const activeKind = ref<AdminLegalDocumentKind>('TERMS')
const loading = ref(true)
const loadFailed = ref(false)
const errorMessage = ref('')
const savingKind = ref<AdminLegalDocumentKind | null>(null)
const restoringKey = ref('')
const publishVisible = ref(false)
const publishKind = ref<AdminLegalDocumentKind>('TERMS')
const publishVersion = ref('')
const publishing = ref(false)

function emptyState(kind: AdminLegalDocumentKind): AdminLegalDocumentState {
  return { kind, published: null, draft: null, history: [] }
}

function emptyDraft(): DocumentDraft {
  return { title: '', placeholder: '', sections: [] }
}

const states = reactive<Record<AdminLegalDocumentKind, AdminLegalDocumentState>>({
  TERMS: emptyState('TERMS'),
  PRIVACY: emptyState('PRIVACY'),
})

const drafts = reactive<Record<AdminLegalDocumentKind, DocumentDraft>>({
  TERMS: emptyDraft(),
  PRIVACY: emptyDraft(),
})

const publishTitle = computed(() => `发布新版本 · ${KIND_LABELS[publishKind.value]}`)

/* ------------------------------------------------------------------ 错误文案 */

const LEGAL_ERROR_MESSAGES: Record<string, string> = {
  legal_document_version_exists: '该版本号已经被使用过，请换一个新的版本号后再发布。',
  legal_document_version_already_published: '该版本就是当前线上版本，无需恢复。',
  legal_document_version_not_found: '未找到该历史版本，请刷新后重试。',
  invalid_legal_document_version: '版本号不能为空，且不能超过 32 个字符。',
  invalid_legal_document_title: '标题不能为空，且不能超过 120 个字符。',
  invalid_legal_document_content: '条款内容不完整：每个条款都要有标题，并且至少有一段正文或一条列表项。',
  invalid_legal_document_kind: '文档类型无效，请刷新页面后重试。',
  legal_document_too_large: '正文内容过大，请精简后再提交。',
  legal_document_unavailable: '服务端还没有可用的协议文本，请先保存草稿后再发布。',
}

const LEGAL_STATUS_MESSAGES: Record<number, string> = {
  401: '登录状态已失效，请重新登录后再试。',
  403: '当前账号没有维护协议文本的权限。',
  409: '操作与服务器当前状态冲突，请刷新后重试。',
  422: '提交的内容不符合接口要求，请检查标题、版本号与条款内容。',
  503: '协议文本服务暂时不可用（可能尚未发布任何版本），请稍后重试。',
}

/** 后端 `message` 有时就是机器码，机器码不回显给管理员 */
function isMachineCode(value: string) {
  return /^[a-z][a-z0-9_]*$/.test(value)
}

/** 统一把接口失败转成可直接展示的中文文案，绝不把原始异常抛给页面 */
function legalErrorMessage(error: unknown, fallback: string) {
  const code = readApiErrorCode(error)
  if (code && LEGAL_ERROR_MESSAGES[code]) return LEGAL_ERROR_MESSAGES[code]
  const serverMessage = readApiErrorMessage(error)
  if (serverMessage && !isMachineCode(serverMessage)) return serverMessage
  const response = (error as { response?: { status?: number } } | null)?.response
  if (!response) return '无法连接到协议管理服务，请检查网络后重试。'
  const status = response.status
  if (typeof status === 'number' && LEGAL_STATUS_MESSAGES[status]) return LEGAL_STATUS_MESSAGES[status]
  return fallback
}

/* ------------------------------------------------------------ 数据形状收敛 */

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' ? value as Record<string, unknown> : null
}

function asText(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function asLines(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((line): line is string => typeof line === 'string') : []
}

/** 文本域「一行一段」→ 数组：忽略空行并去掉首尾空白 */
function splitLines(value: string): string[] {
  return value.split('\n').map(line => line.trim()).filter(Boolean)
}

/** 后端正文缺字段、被改坏或返回 null 时都收敛成空编辑器，不让页面抛错 */
function toSections(content: unknown): SectionDraft[] {
  const sections = asRecord(content)?.sections
  if (!Array.isArray(sections)) return []
  return sections.flatMap((raw) => {
    const section = asRecord(raw)
    if (!section) return []
    return [{
      heading: asText(section.heading),
      paragraphsText: asLines(section.paragraphs).join('\n'),
      itemsText: asLines(section.items).join('\n'),
    }]
  })
}

function toDraft(document: AdminLegalDocument | null | undefined): DocumentDraft {
  return {
    title: asText(document?.title),
    placeholder: asText(asRecord(document?.content)?.placeholder),
    sections: toSections(document?.content),
  }
}

function toSectionPayload(section: SectionDraft): LegalDocumentSection {
  const payload: LegalDocumentSection = {
    heading: section.heading.trim(),
    paragraphs: splitLines(section.paragraphsText),
  }
  const items = splitLines(section.itemsText)
  if (items.length) payload.items = items
  return payload
}

function payloadOf(kind: AdminLegalDocumentKind): LegalDocumentPayload {
  const draft = drafts[kind]
  return {
    title: draft.title.trim(),
    content: {
      placeholder: draft.placeholder.trim(),
      sections: draft.sections.map(toSectionPayload),
    },
  }
}

/** 草稿优先；还没有草稿时以已发布正文为起点（服务端首次保存也会这样建草稿） */
function resetDrafts() {
  for (const kind of ADMIN_LEGAL_KINDS) {
    drafts[kind] = toDraft(states[kind].draft || states[kind].published)
  }
}

/** 四个接口都返回同一份全量状态，直接整体覆盖 */
function applyState(response: { documents?: AdminLegalDocumentState[] } | null | undefined) {
  const documents = Array.isArray(response?.documents) ? response.documents : []
  for (const kind of ADMIN_LEGAL_KINDS) {
    const match = documents.find(document => document?.kind === kind)
    states[kind] = match
      ? {
          kind,
          published: match.published ?? null,
          draft: match.draft ?? null,
          history: Array.isArray(match.history) ? match.history : [],
        }
      : emptyState(kind)
  }
  resetDrafts()
}

function formatTime(value: string | null | undefined) {
  if (!value) return '-'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN', { hour12: false })
}

/** 建议一个没被用过的版本号：优先当天日期，被占用则加序号 */
function suggestedVersion(kind: AdminLegalDocumentKind) {
  const base = new Date().toISOString().slice(0, 10)
  const used = new Set(states[kind].history.map(row => row.version))
  if (!used.has(base)) return base
  let suffix = 2
  while (used.has(`${base}-${suffix}`)) suffix += 1
  return `${base}-${suffix}`
}

/** 与后端 `validateContent` 对齐的前置校验，避免可预期的 422 */
function validateDraft(kind: AdminLegalDocumentKind) {
  const draft = drafts[kind]
  const title = draft.title.trim()
  if (!title) return '文档标题不能为空。'
  if (title.length > MAX_TITLE_LENGTH) return `文档标题不能超过 ${MAX_TITLE_LENGTH} 个字符。`
  if (!draft.sections.length) return '至少需要一个条款，请点击「添加条款」。'
  for (const [index, section] of draft.sections.entries()) {
    if (!section.heading.trim()) return `第 ${index + 1} 个条款的标题不能为空。`
    if (!splitLines(section.paragraphsText).length && !splitLines(section.itemsText).length) {
      return `第 ${index + 1} 个条款至少需要一段正文或一条列表项。`
    }
  }
  return ''
}

/* ---------------------------------------------------------------- 数据加载 */

async function load() {
  loading.value = true
  loadFailed.value = false
  errorMessage.value = ''
  try {
    applyState(await getLegalDocuments())
  } catch (error: unknown) {
    loadFailed.value = true
    errorMessage.value = legalErrorMessage(error, '协议文本加载失败，请稍后重试。')
  } finally {
    loading.value = false
  }
}

/* ---------------------------------------------------------------- 编辑动作 */

function addSection(kind: AdminLegalDocumentKind) {
  drafts[kind].sections.push({ heading: '', paragraphsText: '', itemsText: '' })
}

function removeSection(kind: AdminLegalDocumentKind, index: number) {
  drafts[kind].sections.splice(index, 1)
}

function moveSection(kind: AdminLegalDocumentKind, index: number, delta: number) {
  const sections = drafts[kind].sections
  const target = index + delta
  if (target < 0 || target >= sections.length) return
  const [moved] = sections.splice(index, 1)
  sections.splice(target, 0, moved)
}

async function saveDraft(kind: AdminLegalDocumentKind) {
  const validation = validateDraft(kind)
  if (validation) {
    errorMessage.value = validation
    return
  }
  savingKind.value = kind
  errorMessage.value = ''
  try {
    applyState(await saveLegalDocumentDraft(kind, payloadOf(kind)))
    ElMessage.success(`${KIND_LABELS[kind]}草稿已保存`)
  } catch (error: unknown) {
    errorMessage.value = legalErrorMessage(error, '草稿保存失败，请稍后重试。')
  } finally {
    savingKind.value = null
  }
}

function openPublish(kind: AdminLegalDocumentKind) {
  publishKind.value = kind
  publishVersion.value = suggestedVersion(kind)
  publishVisible.value = true
}

async function confirmPublish() {
  const kind = publishKind.value
  const version = publishVersion.value.trim()
  if (!version || version.length > MAX_VERSION_LENGTH) {
    errorMessage.value = `版本号不能为空，且不能超过 ${MAX_VERSION_LENGTH} 个字符。`
    return
  }
  const validation = validateDraft(kind)
  if (validation) {
    errorMessage.value = validation
    return
  }
  const confirmed = await ElMessageBox.confirm(
    `发布「${KIND_LABELS[kind]}」版本 ${version} 后，已发布版本不可再修改，用户需要重新阅读并同意。`,
    '确认发布新版本',
    { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '取消' },
  ).then(() => true).catch(() => false)
  if (!confirmed) return

  publishing.value = true
  errorMessage.value = ''
  try {
    applyState(await publishLegalDocument(kind, version, payloadOf(kind)))
    publishVisible.value = false
    ElMessage.success(`已发布版本 ${version}，用户需重新确认`)
  } catch (error: unknown) {
    errorMessage.value = legalErrorMessage(error, '版本发布失败，请稍后重试。')
  } finally {
    publishing.value = false
  }
}

async function restoreVersion(kind: AdminLegalDocumentKind, version: string) {
  const confirmed = await ElMessageBox.confirm(
    `确认把「${KIND_LABELS[kind]}」恢复为版本 ${version}？该版本会重新成为线上版本，用户需要重新同意。`,
    '确认恢复历史版本',
    { type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消' },
  ).then(() => true).catch(() => false)
  if (!confirmed) return

  restoringKey.value = `${kind}:${version}`
  errorMessage.value = ''
  try {
    applyState(await restoreLegalDocumentVersion(kind, version))
    ElMessage.success(`已恢复到版本 ${version}`)
  } catch (error: unknown) {
    errorMessage.value = legalErrorMessage(error, '版本恢复失败，请稍后重试。')
  } finally {
    restoringKey.value = ''
  }
}

onMounted(load)
</script>

<template>
  <section class="admin-page" aria-labelledby="admin-legal-documents-title">
    <header class="admin-page__header">
      <div>
        <h2 id="admin-legal-documents-title">协议管理</h2>
        <p>查看并手动编辑平台服务协议与隐私政策，发布新版本后用户需重新确认</p>
      </div>
      <el-button data-test="legal-refresh" :loading="loading" @click="load">
        <RefreshCw :size="16" />刷新
      </el-button>
    </header>

    <TopErrorMessage :message="errorMessage" />

    <el-skeleton v-if="loading" :rows="8" animated />

    <div v-else-if="loadFailed" class="admin-panel legal-failure" data-test="legal-load-failure">
      <AlertTriangle :size="30" aria-hidden="true" />
      <h3>协议文本加载失败</h3>
      <p>{{ errorMessage || '无法获取协议文本，请稍后重试。' }}</p>
      <el-button data-test="legal-retry" type="primary" :loading="loading" @click="load">
        <RefreshCw :size="16" />重试
      </el-button>
    </div>

    <el-tabs v-else v-model="activeKind" data-test="legal-kind-tabs" class="legal-tabs">
      <el-tab-pane
        v-for="kind in ADMIN_LEGAL_KINDS"
        :key="kind"
        :label="KIND_LABELS[kind]"
        :name="kind"
        lazy
      >
        <div :data-test="`legal-kind-${kind}`" class="legal-kind">
          <!-- 当前发布版本（只读） -->
          <section class="admin-panel legal-published">
            <div class="admin-panel__header">
              <div>
                <h3>当前发布版本（只读）</h3>
                <p>线上用户阅读到的正文，发布后不可修改</p>
              </div>
              <el-tag v-if="states[kind].published" size="small" type="success">已发布</el-tag>
              <el-tag v-else size="small" type="warning">未发布</el-tag>
            </div>
            <div class="legal-published__body">
              <div v-if="states[kind].published" class="legal-published__meta">
                <div>
                  <span>版本号</span>
                  <strong data-test="legal-published-version">{{ states[kind].published?.version }}</strong>
                </div>
                <div>
                  <span>标题</span>
                  <strong data-test="legal-published-title">{{ states[kind].published?.title }}</strong>
                </div>
                <div>
                  <span>条款数量</span>
                  <strong>{{ toSections(states[kind].published?.content).length }}</strong>
                </div>
              </div>
              <p v-else class="legal-published__empty" data-test="legal-published-empty">
                线上还没有发布任何版本，用户端会回退到内置的默认文本；请编辑下方草稿并发布第一个版本。
              </p>
              <p class="legal-published__notice" data-test="legal-published-notice">
                <AlertTriangle :size="15" aria-hidden="true" />
                已发布版本不可修改：用户同意记录绑定在版本号上。需要调整条款时，请先保存草稿，再用
                <b>新的版本号</b> 发布，用户会被要求重新阅读并同意。
              </p>
            </div>
          </section>

          <!-- 草稿编辑 -->
          <section class="admin-panel legal-editor">
            <div class="admin-panel__header">
              <div>
                <h3>草稿编辑</h3>
                <p>草稿只对管理员可见，发布后才会对用户生效</p>
              </div>
              <div class="legal-editor__actions">
                <el-button
                  data-test="legal-save-draft"
                  type="primary"
                  :loading="savingKind === kind"
                  @click="saveDraft(kind)"
                >
                  <Save :size="16" />保存草稿
                </el-button>
                <el-button data-test="legal-publish" @click="openPublish(kind)">
                  <Send :size="16" />发布
                </el-button>
              </div>
            </div>

            <div class="legal-editor__body">
              <div class="legal-editor__meta">
                <el-form label-position="top" @submit.prevent>
                  <el-form-item label="文档标题">
                    <el-input
                      v-model="drafts[kind].title"
                      data-test="legal-draft-title"
                      :maxlength="MAX_TITLE_LENGTH"
                      show-word-limit
                      placeholder="例如：平台服务协议"
                    />
                  </el-form-item>
                  <el-form-item label="正文顶部说明（脚注）">
                    <el-input
                      v-model="drafts[kind].placeholder"
                      data-test="legal-placeholder"
                      type="textarea"
                      :rows="2"
                      resize="none"
                      placeholder="显示在正文最前面的说明文字，可留空"
                    />
                  </el-form-item>
                </el-form>
              </div>

              <div class="legal-sections">
                <article
                  v-for="(section, index) in drafts[kind].sections"
                  :key="`${kind}-${index}`"
                  class="legal-section"
                >
                  <header class="legal-section__header">
                    <span class="legal-section__index">条款 {{ index + 1 }}</span>
                    <div class="legal-section__tools">
                      <el-button
                        data-test="legal-move-section-up"
                        size="small"
                        text
                        :disabled="index === 0"
                        @click="moveSection(kind, index, -1)"
                      >
                        <ArrowUp :size="14" />上移
                      </el-button>
                      <el-button
                        data-test="legal-move-section-down"
                        size="small"
                        text
                        :disabled="index === drafts[kind].sections.length - 1"
                        @click="moveSection(kind, index, 1)"
                      >
                        <ArrowDown :size="14" />下移
                      </el-button>
                      <el-button
                        data-test="legal-remove-section"
                        size="small"
                        text
                        type="danger"
                        @click="removeSection(kind, index)"
                      >
                        <Trash2 :size="14" />删除本条款
                      </el-button>
                    </div>
                  </header>
                  <el-form label-position="top" @submit.prevent>
                    <el-form-item label="条款标题">
                      <el-input
                        v-model="section.heading"
                        data-test="legal-section-heading"
                        placeholder="例如：一、协议范围"
                      />
                    </el-form-item>
                    <el-form-item label="正文段落（一行一段）">
                      <el-input
                        v-model="section.paragraphsText"
                        data-test="legal-section-paragraphs"
                        type="textarea"
                        :rows="4"
                        resize="none"
                        placeholder="每行一段，保存后会拆成多个段落"
                      />
                    </el-form-item>
                    <el-form-item label="项目列表（可选，一行一条）">
                      <el-input
                        v-model="section.itemsText"
                        data-test="legal-section-items"
                        type="textarea"
                        :rows="3"
                        resize="none"
                        placeholder="每行一条，没有列表时留空"
                      />
                    </el-form-item>
                  </el-form>
                </article>
              </div>

              <p v-if="!drafts[kind].sections.length" class="legal-sections__empty" data-test="legal-sections-empty">
                还没有任何条款，点击下方「添加条款」开始编辑；发布时至少需要一个条款。
              </p>

              <el-button data-test="legal-add-section" class="legal-add-section" @click="addSection(kind)">
                <Plus :size="16" />添加条款
              </el-button>
            </div>
          </section>

          <!-- 版本历史 -->
          <section class="admin-panel legal-history">
            <div class="admin-panel__header">
              <div>
                <h3>版本历史</h3>
                <p>最近 50 个版本；未发布的即当前草稿，可恢复任意历史版本</p>
              </div>
              <History :size="18" aria-hidden="true" />
            </div>
            <el-table
              :data="states[kind].history"
              data-test="legal-history-table"
              size="small"
              empty-text="暂无版本记录"
            >
              <el-table-column prop="version" label="版本号" min-width="160" />
              <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
              <el-table-column label="状态" width="96">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.published ? 'success' : 'info'">
                    {{ row.published ? '已发布' : '草稿' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="发布于" width="170">
                <template #default="{ row }">{{ formatTime(row.publishedAt) }}</template>
              </el-table-column>
              <el-table-column label="创建于" width="170">
                <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="150">
                <template #default="{ row }">
                  <el-button
                    v-if="!row.published"
                    data-test="legal-restore-version"
                    size="small"
                    :loading="restoringKey === `${kind}:${row.version}`"
                    @click="restoreVersion(kind, row.version)"
                  >
                    <RotateCcw :size="14" />恢复该版本
                  </el-button>
                  <span v-else class="legal-history__current">当前版本</span>
                </template>
              </el-table-column>
            </el-table>
          </section>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="publishVisible"
      :title="publishTitle"
      width="min(520px, calc(100vw - 32px))"
      :teleported="false"
      destroy-on-close
    >
      <el-form label-position="top" @submit.prevent="confirmPublish">
        <el-form-item label="新版本号">
          <el-input
            v-model="publishVersion"
            data-test="legal-publish-version"
            :maxlength="MAX_VERSION_LENGTH"
            placeholder="例如：2026-10-01"
          />
        </el-form-item>
        <p class="field-help">
          版本号用于记录用户同意的文本，必须是<strong>从未使用过</strong>的新字符串（最多 32 个字符）；
          重复使用会被拒绝。
        </p>
      </el-form>
      <template #footer>
        <el-button @click="publishVisible = false">取消</el-button>
        <el-button data-test="legal-publish-confirm" type="primary" :loading="publishing" @click="confirmPublish">
          确认发布
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.legal-tabs :deep(.el-tabs__item) {
  font-weight: 600;
}
.legal-kind {
  display: grid;
  gap: 16px;
}
/* 区块间距由 grid gap 控制，抵消全局 `.admin-panel + .admin-panel` 的 margin */
.legal-kind .admin-panel + .admin-panel {
  margin-top: 0;
}
.legal-published__body {
  display: grid;
  gap: 12px;
  padding: 14px;
}
.legal-published__meta {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.legal-published__meta > div {
  min-width: 0;
  padding: 10px 12px;
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: 8px;
}
.legal-published__meta span {
  display: block;
  margin-bottom: 4px;
  color: var(--app-muted);
  font-size: 12px;
}
.legal-published__meta strong {
  display: block;
  overflow: hidden;
  color: var(--app-fg);
  font-size: 15px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.legal-published__empty {
  margin: 0;
  padding: 12px 14px;
  color: var(--app-muted-strong);
  background: var(--app-surface-muted);
  border: 1px dashed var(--app-border-strong);
  border-radius: 8px;
  font-size: 13px;
}
.legal-published__notice {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0;
  padding: 12px 14px;
  color: var(--app-warning-text);
  background: var(--app-warning-soft);
  border: 1px solid var(--app-warning-border);
  border-left: 4px solid var(--app-warning);
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
}
.legal-published__notice svg {
  flex: 0 0 auto;
  margin-top: 2px;
}
.legal-editor__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.legal-editor__body {
  display: grid;
  gap: 14px;
  padding: 14px;
}
.legal-editor__meta {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.legal-editor__meta :deep(.el-form-item),
.legal-section :deep(.el-form-item) {
  display: block;
  margin-bottom: 0;
}
.legal-editor__meta :deep(.el-form-item__label),
.legal-section :deep(.el-form-item__label) {
  display: block;
  height: auto;
  margin-bottom: 5px;
  color: var(--app-fg);
  font-weight: 600;
}
.legal-sections {
  display: grid;
  gap: 12px;
}
.legal-section {
  padding: 12px 14px 14px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius);
  box-shadow: var(--app-shadow);
}
.legal-section__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--app-border);
}
.legal-section__index {
  color: var(--app-muted-strong);
  font-size: 13px;
  font-weight: 700;
}
.legal-section__tools {
  display: flex;
  align-items: center;
  gap: 4px;
}
.legal-sections__empty {
  margin: 0;
  padding: 18px 14px;
  color: var(--app-muted);
  background: var(--app-surface-subtle);
  border: 1px dashed var(--app-border-strong);
  border-radius: 8px;
  font-size: 13px;
  text-align: center;
}
.legal-add-section {
  justify-self: start;
}
.legal-history__current {
  color: var(--app-muted);
  font-size: 12px;
}
.legal-failure {
  display: grid;
  justify-items: center;
  gap: 10px;
  padding: 36px 20px;
  color: var(--app-muted-strong);
  text-align: center;
}
.legal-failure svg {
  color: var(--app-danger);
}
.legal-failure h3 {
  margin: 0;
  color: var(--app-fg);
  font-size: 16px;
}
.legal-failure p {
  margin: 0;
  max-width: 520px;
  font-size: 13px;
  line-height: 1.6;
}
.field-help {
  display: block;
  margin: 0;
  color: var(--app-muted);
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .legal-editor__meta,
  .legal-published__meta {
    grid-template-columns: minmax(0, 1fr);
  }
  .legal-section__header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
