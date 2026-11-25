<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  BadgeCheck,
  Blocks,
  CircleAlert,
  Download,
  Eye,
  FileText,
  Inbox,
  Package,
  RefreshCw,
  Search,
} from 'lucide-vue-next'

import {
  getSkill,
  listSkills,
  type SkillDetail,
  type SkillFileContent,
  type SkillSummary,
} from '../../api/skills'
import { formatDateTime } from '../../utils/dateTime'
import { downloadSkillFile, downloadSkillJson, isTextFile } from '../../utils/skillDownload'
import { skillErrorMessage, type SkillFailure } from './skillErrors'

/** 预览正文的截断上限，避免超大文件把 DOM 拖垮 */
const PREVIEW_LIMIT = 20_000
/** 卡片上一屏展示的卖点条数 */
const HIGHLIGHT_LIMIT = 3

const skills = ref<SkillSummary[]>([])
const loading = ref(true)
const failure = ref<SkillFailure | null>(null)
const keyword = ref('')
const refreshing = ref(false)
const downloading = ref('')

const detailOpen = ref(false)
const detailName = ref('')
const detail = ref<SkillDetail | null>(null)
const detailLoading = ref(false)
const detailFailure = ref<SkillFailure | null>(null)
const previewPath = ref('')

let mounted = true
let loadGeneration = 0

const bytesFormatter = new Intl.NumberFormat('zh-CN', {
  style: 'unit',
  unit: 'byte',
  notation: 'compact',
  maximumFractionDigits: 1,
})

const detailSummary = computed(() => skills.value.find((skill) => skill.name === detailName.value) ?? null)
const dialogTitle = computed(() => detailSummary.value?.title || detail.value?.title || detailName.value)
const totalBytes = computed(() => skills.value.reduce((sum, skill) => sum + (skill.totalBytes || 0), 0))
const fileTotal = computed(() => skills.value.reduce((sum, skill) => sum + (skill.fileCount || 0), 0))

/** 名称 / 标题 / 描述 / 分类 / 标签 任一命中即保留 */
const filteredSkills = computed(() => {
  const needle = keyword.value.trim().toLowerCase()
  if (!needle) return skills.value
  return skills.value.filter((skill) => {
    const haystack = [
      skill.name,
      skill.title,
      skill.description ?? '',
      skill.category ?? '',
      ...(Array.isArray(skill.tags) ? skill.tags : []),
    ]
    return haystack.some((value) => String(value).toLowerCase().includes(needle))
  })
})

const searchActive = computed(() => keyword.value.trim().length > 0)
const previewFile = computed(() => {
  if (!detail.value || !previewPath.value) return null
  return detail.value.files.find((file) => file.path === previewPath.value) ?? null
})
const previewText = computed(() => {
  const content = previewFile.value?.content ?? ''
  return content.length > PREVIEW_LIMIT
    ? `${content.slice(0, PREVIEW_LIMIT)}\n…（正文过长，已截断预览，请下载完整文件）`
    : content
})

function skillIcon(skill: SkillSummary): string {
  const icon = (skill.icon ?? '').trim()
  return icon
}

function skillInitial(skill: SkillSummary): string {
  const source = (skill.title || skill.name || '?').trim()
  return source.slice(0, 1).toUpperCase()
}

function displayTitle(skill: SkillSummary): string {
  return skill.title || skill.name
}

function displayDescription(skill: SkillSummary): string {
  return skill.description || '暂无描述'
}

/** 卡片位置窄，只展示日期；完整时间戳在详情弹窗里 */
function displayUpdatedDate(skill: SkillSummary): string {
  const formatted = formatDateTime(skill.updatedAt)
  return formatted === '-' ? formatted : formatted.slice(0, 10)
}

function highlights(skill: SkillSummary): string[] {
  return Array.isArray(skill.highlights) ? skill.highlights.slice(0, HIGHLIGHT_LIMIT) : []
}

function tagsOf(skill: SkillSummary): string[] {
  return Array.isArray(skill.tags) ? skill.tags : []
}

function isCurrent(generation: number) {
  return mounted && generation === loadGeneration
}

async function load(options: { silent?: boolean } = {}) {
  const generation = ++loadGeneration
  if (options.silent) refreshing.value = true
  else loading.value = true
  failure.value = null
  try {
    const result = await listSkills()
    if (!isCurrent(generation)) return
    skills.value = Array.isArray(result?.data) ? result.data : []
  } catch (error) {
    if (!isCurrent(generation)) return
    skills.value = []
    failure.value = skillErrorMessage(error)
  } finally {
    if (isCurrent(generation)) {
      loading.value = false
      refreshing.value = false
    }
  }
}

function resetDetail() {
  detail.value = null
  detailFailure.value = null
  detailLoading.value = false
  previewPath.value = ''
}

async function openDetail(skill: SkillSummary) {
  detailName.value = skill.name
  detailOpen.value = true
  resetDetail()
  detailLoading.value = true
  try {
    const loaded = await getSkill(skill.name)
    if (!mounted || detailName.value !== skill.name) return
    detail.value = loaded
  } catch (error) {
    if (!mounted || detailName.value !== skill.name) return
    detailFailure.value = skillErrorMessage(error)
  } finally {
    if (mounted && detailName.value === skill.name) detailLoading.value = false
  }
}

function closeDetail() {
  detailName.value = ''
  resetDetail()
}

/** 下载/预览前确保正文已加载 */
async function ensureDetail(skill: SkillSummary): Promise<SkillDetail | null> {
  if (detail.value?.name === skill.name) return detail.value
  const loadingName = skill.name
  downloading.value = loadingName
  try {
    const loaded = await getSkill(loadingName)
    if (!mounted) return null
    detail.value = loaded
    return loaded
  } catch (error) {
    if (mounted) failure.value = skillErrorMessage(error)
    return null
  } finally {
    if (downloading.value === loadingName) downloading.value = ''
  }
}

async function downloadSkill(skill: SkillSummary) {
  const loaded = await ensureDetail(skill)
  if (!loaded) return
  downloadSkillJson(skill.name, loaded)
}

function togglePreview(file: SkillFileContent) {
  if (!isTextFile(file)) {
    ElMessage.warning('二进制文件不提供正文预览，请下载还原后查看。')
    return
  }
  previewPath.value = previewPath.value === file.path ? '' : file.path
}

function downloadFile(file: SkillFileContent) {
  if (!detail.value) return
  downloadSkillFile(detail.value, file)
}

function clearSearch() {
  keyword.value = ''
}

onMounted(() => {
  void load()
})

onBeforeUnmount(() => {
  mounted = false
  loadGeneration += 1
})
</script>

<template>
  <section class="skills-page" aria-labelledby="skills-title">
    <header class="skills-head">
      <div class="skills-head__text">
        <p class="skills-head__eyebrow">SKILLS · 专家市场</p>
        <h1 id="skills-title">Skill 市场</h1>
        <p class="skills-head__sub">选择一个专家，下载它的能力包；内容由平台统一维护，随取随用。</p>
      </div>
      <div class="skills-head__tools">
        <el-input
          v-model="keyword"
          data-test="skill-search"
          class="skills-search"
          size="large"
          clearable
          placeholder="搜索专家名称、描述或标签"
          aria-label="搜索专家"
          @clear="clearSearch"
        >
          <template #prefix>
            <Search :size="16" aria-hidden="true" />
          </template>
        </el-input>
        <el-button
          data-test="skill-refresh"
          size="large"
          :loading="refreshing"
          @click="load({ silent: true })"
        >
          <RefreshCw :size="16" aria-hidden="true" />
          刷新
        </el-button>
      </div>
    </header>

    <el-skeleton v-if="loading" data-test="skills-skeleton" :rows="6" animated />

    <div v-else-if="failure" class="skills-error" role="alert" data-test="skills-error">
      <CircleAlert :size="18" aria-hidden="true" />
      <div class="skills-error__body">
        <strong data-test="skills-error-message">{{ failure.message }}</strong>
        <span v-if="failure.code" class="skills-error__meta">
          错误码：<code>{{ failure.code }}</code>
        </span>
        <span v-if="failure.requestId" class="skills-error__meta">
          请求编号：<code data-test="skills-error-request-id">{{ failure.requestId }}</code>
        </span>
      </div>
      <el-button size="small" data-test="skills-error-retry" @click="load()">重新加载</el-button>
    </div>

    <template v-else>
      <div class="skills-summary">
        <span>共 {{ skills.length }} 个专家</span>
        <span>{{ fileTotal }} 个文件</span>
        <span>合计 {{ bytesFormatter.format(totalBytes) }}</span>
        <span v-if="searchActive">筛选出 {{ filteredSkills.length }} 个</span>
      </div>

      <div v-if="!skills.length" class="skills-empty" data-test="skills-empty">
        <Inbox :size="28" aria-hidden="true" />
        <strong>暂无可用专家</strong>
        <span>平台还没有发布任何 Skill，稍后再来看看。</span>
      </div>

      <div v-else-if="!filteredSkills.length" class="skills-empty" data-test="skills-no-match">
        <Search :size="26" aria-hidden="true" />
        <strong>没有匹配的专家</strong>
        <span>换个关键词试试，或清空搜索框查看全部专家。</span>
        <el-button size="small" data-test="skill-search-reset" @click="clearSearch">清空搜索</el-button>
      </div>

      <div v-else class="skills-grid" role="list" data-test="skills-grid">
        <article
          v-for="skill in filteredSkills"
          :key="skill.name"
          class="skill-card"
          role="listitem"
          :data-test="`skill-card-${skill.name}`"
        >
          <div class="skill-card__head">
            <span class="skill-card__icon" aria-hidden="true">
              <template v-if="skillIcon(skill)">{{ skillIcon(skill) }}</template>
              <template v-else>{{ skillInitial(skill) }}</template>
            </span>
            <div class="skill-card__identity">
              <h2 class="skill-card__title">{{ displayTitle(skill) }}</h2>
              <code class="skill-card__name">{{ skill.name }}</code>
            </div>
            <span v-if="skill.category" class="skill-card__category">{{ skill.category }}</span>
          </div>

          <p class="skill-card__desc" :data-test="`skill-desc-${skill.name}`">
            {{ displayDescription(skill) }}
          </p>

          <ul v-if="highlights(skill).length" class="skill-card__highlights">
            <li v-for="point in highlights(skill)" :key="point">
              <BadgeCheck :size="13" aria-hidden="true" />
              <span>{{ point }}</span>
            </li>
          </ul>

          <dl class="skill-card__meta">
            <div>
              <dt>版本</dt>
              <dd>v{{ skill.version || '—' }}</dd>
            </div>
            <div>
              <dt>文件</dt>
              <dd>{{ skill.fileCount }}</dd>
            </div>
            <div>
              <dt>大小</dt>
              <dd>{{ bytesFormatter.format(skill.totalBytes) }}</dd>
            </div>
            <div>
              <dt>更新</dt>
              <dd :title="formatDateTime(skill.updatedAt)">{{ displayUpdatedDate(skill) }}</dd>
            </div>
          </dl>

          <div class="skill-card__actions">
            <el-button
              size="small"
              :data-test="`skill-card-detail-${skill.name}`"
              @click="openDetail(skill)"
            >
              <Eye :size="15" aria-hidden="true" />
              查看详情
            </el-button>
            <el-button
              type="primary"
              size="small"
              :loading="downloading === skill.name"
              :data-test="`skill-card-download-${skill.name}`"
              @click="downloadSkill(skill)"
            >
              <Download :size="15" aria-hidden="true" />
              下载
            </el-button>
          </div>
        </article>
      </div>
    </template>

    <el-dialog
      v-model="detailOpen"
      :title="dialogTitle"
      class="skill-dialog"
      width="min(880px, calc(100vw - 24px))"
      :teleported="false"
      @closed="closeDetail"
    >
      <div class="skill-detail" data-test="skill-detail">
        <div v-if="detailSummary" class="skill-detail__head">
          <span class="skill-detail__icon" aria-hidden="true">
            <template v-if="skillIcon(detailSummary)">{{ skillIcon(detailSummary) }}</template>
            <template v-else>{{ skillInitial(detailSummary) }}</template>
          </span>
          <div class="skill-detail__identity">
            <strong>{{ displayTitle(detailSummary) }}</strong>
            <code>{{ detailSummary.name }}</code>
          </div>
          <span v-if="detailSummary.category" class="skill-detail__category">
            {{ detailSummary.category }}
          </span>
        </div>

        <div v-if="detailLoading" class="skill-detail__state" data-test="skill-detail-loading">
          <Package :size="16" aria-hidden="true" />
          正在读取能力包内容…
        </div>

        <div v-else-if="detailFailure" class="skills-error" role="alert" data-test="skill-detail-error">
          <CircleAlert :size="18" aria-hidden="true" />
          <div class="skills-error__body">
            <strong data-test="skill-detail-error-message">{{ detailFailure.message }}</strong>
            <span v-if="detailFailure.code" class="skills-error__meta">
              错误码：<code>{{ detailFailure.code }}</code>
            </span>
            <span v-if="detailFailure.requestId" class="skills-error__meta">
              请求编号：<code data-test="skill-detail-error-request-id">{{ detailFailure.requestId }}</code>
            </span>
          </div>
        </div>

        <template v-else-if="detail">
          <dl class="skill-detail__meta">
            <div><dt>版本</dt><dd>v{{ detail.version || '—' }}</dd></div>
            <div><dt>文件数</dt><dd>{{ detail.fileCount }}</dd></div>
            <div><dt>总大小</dt><dd>{{ bytesFormatter.format(detail.totalBytes) }}</dd></div>
            <div><dt>更新时间</dt><dd>{{ formatDateTime(detail.updatedAt) }}</dd></div>
          </dl>

          <p class="skill-detail__desc" data-test="skill-detail-description">
            {{ detail.description || '暂无描述' }}
          </p>

          <div v-if="tagsOf(detail).length" class="skill-detail__tags" data-test="skill-detail-tags">
            <span v-for="tag in tagsOf(detail)" :key="tag" class="skill-chip">{{ tag }}</span>
          </div>

          <ul v-if="detail.highlights.length" class="skill-detail__highlights">
            <li v-for="point in detail.highlights" :key="point">
              <BadgeCheck :size="13" aria-hidden="true" />
              <span>{{ point }}</span>
            </li>
          </ul>

          <h3 class="skill-detail__section">
            <Blocks :size="15" aria-hidden="true" />
            文件清单（{{ detail.files.length }}）
          </h3>

          <div class="table-scroll">
            <table class="skill-file-table" data-test="skill-detail-files">
              <thead>
                <tr>
                  <th scope="col">文件路径</th>
                  <th scope="col">大小</th>
                  <th scope="col">SHA-256</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(file, index) in detail.files"
                  :key="file.path"
                  :data-test="`skill-detail-file-${detail.name}-${index}`"
                >
                  <td>
                    <span class="skill-file-path">
                      <FileText :size="14" aria-hidden="true" />
                      <code>{{ file.path }}</code>
                    </span>
                  </td>
                  <td class="num">{{ bytesFormatter.format(file.bytes) }}</td>
                  <td><code class="sha" :title="file.sha256">{{ file.sha256 }}</code></td>
                  <td>
                    <div class="skill-file-actions">
                      <template v-if="isTextFile(file)">
                        <el-button
                          size="small"
                          text
                          :data-test="`skill-detail-preview-${detail.name}-${index}`"
                          :aria-label="`预览 ${file.path}`"
                          @click="togglePreview(file)"
                        >
                          <Eye :size="15" aria-hidden="true" />
                          预览
                        </el-button>
                      </template>
                      <span v-else class="skill-binary-hint" data-test="skill-detail-binary">二进制文件</span>
                      <el-button
                        size="small"
                        text
                        :data-test="`skill-detail-download-${detail.name}-${index}`"
                        :aria-label="`下载 ${file.path}`"
                        @click="downloadFile(file)"
                      >
                        <Download :size="15" aria-hidden="true" />
                        下载
                      </el-button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <p v-if="!detail.files.length" class="skill-detail__empty">该专家还没有对外发布的文件。</p>

          <section v-if="previewFile" class="skill-preview" data-test="skill-preview">
            <header class="skill-preview__head">
              <strong>{{ previewFile.path }}</strong>
              <span>· {{ bytesFormatter.format(previewFile.bytes) }}</span>
            </header>
            <pre class="skill-preview__body" data-test="skill-preview-body">{{ previewText }}</pre>
          </section>
        </template>

        <div class="skill-detail__foot">
          <el-button data-test="skill-detail-close" @click="detailOpen = false">关闭</el-button>
          <el-button
            type="primary"
            :disabled="!detail"
            :data-test="`skill-detail-download-json-${detailName}`"
            @click="detail && downloadSkillJson(detail.name, detail)"
          >
            <Download :size="15" aria-hidden="true" />
            下载能力包（.skill.json）
          </el-button>
        </div>
      </div>
    </el-dialog>
  </section>
</template>

<style scoped>
.skills-page {
  width: min(1200px, 100%);
  min-width: 0;
}

/* ---------- 页头：标题 + 搜索 ---------- */
.skills-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.skills-head__text { min-width: 0; }

.skills-head__eyebrow {
  margin: 0 0 var(--space-1);
  color: var(--app-primary);
  font-size: var(--text-caption);
  font-weight: 700;
  letter-spacing: 0.04em;
}

.skills-head h1 {
  margin: 0;
  font-size: var(--text-page-title);
  letter-spacing: -0.01em;
}

.skills-head__sub {
  margin: var(--space-1) 0 0;
  color: var(--app-muted);
  font-size: var(--text-secondary);
}

.skills-head__tools {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: var(--space-2);
}

.skills-search { width: 300px; }

.skills-summary {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-4);
  margin-bottom: var(--space-3);
  color: var(--app-muted);
  font-size: var(--text-caption);
  font-variant-numeric: tabular-nums;
}

/* ---------- 卡片网格：桌面 3–4 列 / 平板 2 列 / 手机 1 列 ---------- */
.skills-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(268px, 1fr));
  gap: var(--space-4);
}

.skill-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  padding: var(--space-4);
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius);
  box-shadow: var(--app-shadow-xs);
  transition: border-color var(--app-motion-fast) ease, box-shadow var(--app-motion-fast) ease,
    transform var(--app-motion-fast) ease;
}

.skill-card:hover {
  border-color: var(--app-border-strong);
  box-shadow: var(--app-shadow-sm);
  transform: translateY(-1px);
}

.skill-card__head {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
}

.skill-card__icon {
  display: inline-flex;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  align-items: center;
  justify-content: center;
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-md);
  color: var(--app-primary);
  font-size: 20px;
  font-weight: 700;
  line-height: 1;
}

.skill-card__identity { min-width: 0; flex: 1; }

.skill-card__title {
  margin: 0;
  font-size: var(--text-section);
  line-height: var(--leading-tight);
  overflow-wrap: anywhere;
}

.skill-card__name {
  display: block;
  margin-top: 2px;
  overflow-wrap: anywhere;
  color: var(--app-muted);
  font-family: var(--font-mono);
  font-size: var(--text-caption);
}

.skill-card__category {
  flex: 0 0 auto;
  padding: 2px 8px;
  color: var(--app-primary);
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-xs);
  font-size: var(--text-caption);
  font-weight: 600;
  white-space: nowrap;
}

.skill-card__desc {
  display: -webkit-box;
  margin: var(--space-3) 0 0;
  overflow: hidden;
  color: var(--app-muted-strong);
  font-size: var(--text-secondary);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

.skill-card__highlights {
  display: grid;
  gap: var(--space-1);
  margin: var(--space-3) 0 0;
  padding: 0;
  list-style: none;
}

.skill-card__highlights li {
  display: flex;
  align-items: flex-start;
  gap: var(--space-1);
  color: var(--app-muted-strong);
  font-size: var(--text-caption);
}

.skill-card__highlights svg {
  flex: 0 0 auto;
  margin-top: 2px;
  color: var(--app-success-text);
}

.skill-card__meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-2) var(--space-3);
  margin: var(--space-4) 0 0;
  padding-top: var(--space-3);
  border-top: 1px solid var(--app-border-subtle);
}

.skill-card__meta div { display: flex; min-width: 0; gap: var(--space-1); }
.skill-card__meta dt { flex: 0 0 auto; color: var(--app-muted); font-size: var(--text-caption); }
.skill-card__meta dd {
  margin: 0;
  overflow: hidden;
  color: var(--app-muted-strong);
  font-size: var(--text-caption);
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-card__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-2);
  /* 撑到底部：等高卡片里按钮始终对齐 */
  margin-top: auto;
  padding-top: var(--space-4);
}

/* ---------- 空态 / 错误态 ---------- */
.skills-empty {
  display: flex;
  min-height: 240px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-5);
  text-align: center;
  color: var(--app-muted);
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius);
}

.skills-empty svg { color: var(--app-primary); }
.skills-empty strong { color: var(--app-fg); }

.skills-error {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  color: var(--app-danger-text);
  background: var(--app-danger-soft);
  border: 1px solid var(--app-danger-border);
  border-radius: var(--app-radius);
  font-size: var(--text-secondary);
}

.skills-error svg { flex: 0 0 auto; }
.skills-error__body { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 2px; }
.skills-error__meta { color: var(--app-muted-strong); font-size: var(--text-caption); }
.skills-error code { font-family: var(--font-mono); }

/* ---------- 详情弹窗 ---------- */
.skill-detail { display: flex; flex-direction: column; gap: var(--space-3); }

.skill-detail__head { display: flex; align-items: center; gap: var(--space-3); }

.skill-detail__icon {
  display: inline-flex;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  align-items: center;
  justify-content: center;
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-md);
  font-size: 20px;
  line-height: 1;
}

.skill-detail__identity { display: flex; min-width: 0; flex: 1; flex-direction: column; }
.skill-detail__identity strong { font-size: var(--text-section); }
.skill-detail__identity code { color: var(--app-muted); font-family: var(--font-mono); font-size: var(--text-caption); }

.skill-detail__category {
  padding: 2px 8px;
  color: var(--app-primary);
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-xs);
  font-size: var(--text-caption);
  font-weight: 600;
}

.skill-detail__state {
  display: flex;
  min-height: 120px;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  color: var(--app-muted);
  font-size: var(--text-secondary);
}

.skill-detail__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-5);
  margin: 0;
  padding: var(--space-3);
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-sm);
}

.skill-detail__meta div { display: flex; gap: var(--space-1); }
.skill-detail__meta dt { color: var(--app-muted); font-size: var(--text-caption); }
.skill-detail__meta dd {
  margin: 0;
  color: var(--app-fg);
  font-size: var(--text-caption);
  font-variant-numeric: tabular-nums;
}

.skill-detail__desc {
  margin: 0;
  color: var(--app-muted-strong);
  font-size: var(--text-secondary);
  line-height: var(--leading-base);
  overflow-wrap: anywhere;
}

.skill-detail__tags { display: flex; flex-wrap: wrap; gap: var(--space-1); }

.skill-chip {
  padding: 2px 8px;
  color: var(--app-muted-strong);
  background: var(--app-surface-muted);
  border: 1px solid var(--app-border-subtle);
  border-radius: var(--radius-xs);
  font-size: var(--text-caption);
}

.skill-detail__highlights {
  display: grid;
  gap: var(--space-1);
  margin: 0;
  padding: 0;
  list-style: none;
}

.skill-detail__highlights li {
  display: flex;
  align-items: flex-start;
  gap: var(--space-1);
  color: var(--app-muted-strong);
  font-size: var(--text-secondary);
}

.skill-detail__highlights svg { flex: 0 0 auto; margin-top: 3px; color: var(--app-success-text); }

.skill-detail__section {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  margin: var(--space-2) 0 0;
  font-size: var(--text-body);
}

.skill-detail__section svg { color: var(--app-primary); }

.skill-detail__empty { margin: 0; color: var(--app-muted); font-size: var(--text-secondary); }

.skill-file-table { width: 100%; border-collapse: collapse; }
.skill-file-table th,
.skill-file-table td {
  padding: var(--space-2) var(--space-3);
  text-align: left;
  vertical-align: middle;
  border-bottom: 1px solid var(--app-border);
  font-size: var(--text-caption);
}
.skill-file-table th { color: var(--app-muted); font-weight: 700; }
.skill-file-table tr:last-child td { border-bottom: 0; }

.skill-file-path { display: inline-flex; align-items: center; gap: var(--space-1); }
.skill-file-path svg { flex: 0 0 auto; color: var(--app-primary); }
.skill-file-path code { overflow-wrap: anywhere; font-family: var(--font-mono); }
.sha { color: var(--app-muted-strong); font-family: var(--font-mono); }
.skill-file-actions { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-1); }
.skill-binary-hint { color: var(--app-warning-text); font-size: var(--text-caption); font-weight: 600; }
.num { font-variant-numeric: tabular-nums; }

.skill-preview {
  padding: var(--space-3);
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-sm);
}

.skill-preview__head {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  margin-bottom: var(--space-2);
  color: var(--app-muted-strong);
  font-size: var(--text-caption);
}

.skill-preview__head strong { color: var(--app-fg); font-family: var(--font-mono); }

.skill-preview__body {
  max-height: 40vh;
  margin: 0;
  overflow: auto;
  color: var(--app-fg);
  font-family: var(--font-mono);
  font-size: var(--text-caption);
  line-height: var(--leading-base);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.skill-detail__foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-2);
}

@media (max-width: 900px) {
  .skills-head { align-items: stretch; flex-direction: column; }
  .skills-head__tools { flex-wrap: wrap; }
  .skills-search { width: min(360px, 100%); }
  .skills-grid { grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); }
}

@media (max-width: 720px) {
  .skills-grid { grid-template-columns: minmax(0, 1fr); }
  .skills-head__tools { width: 100%; }
  .skills-search { width: 100%; }
  .skill-file-table th:nth-child(3),
  .skill-file-table td:nth-child(3) { display: none; }
  .skill-detail__foot { flex-direction: column-reverse; }
  .skill-detail__foot :deep(.el-button) { width: 100%; margin-left: 0; }
}
</style>
