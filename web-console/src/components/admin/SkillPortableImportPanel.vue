<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { FolderOpen, PackageOpen, Upload } from 'lucide-vue-next'
import {
  commitAdminPortableImport,
  uploadAdminPortableImport,
  type PortableImportCommitResponse,
  type PortableImportFieldAction,
  type PortableImportImageAction,
  type PortableImportResponse,
  type SkillAdminDetail,
} from '../../api/admin'
import {
  packageFromDrop,
  packageFromFileSelection,
} from '../../utils/skillSourceInput'
import { skillAdminErrorMessage } from '../../views/admin/skillAdminErrors'

const props = defineProps<{ skill: SkillAdminDetail | null }>()
const emit = defineEmits<{ committed: [response: PortableImportCommitResponse] }>()

const input = ref<HTMLInputElement | null>(null)
const folderInput = ref<HTMLInputElement | null>(null)
const importing = ref(false)
const committing = ref(false)
const dragDepth = ref(0)
const imported = ref<PortableImportResponse | null>(null)
const fieldActions = reactive<Record<string, PortableImportFieldAction>>({})
const imageActions = reactive<Record<string, PortableImportImageAction>>({})
const error = ref('')

const fields = computed(() => Object.entries(imported.value?.fields ?? {}))
const images = computed(() => Object.entries(imported.value?.images ?? {}))
const dragActive = computed(() => dragDepth.value > 0)
const hasTarget = computed(() => Boolean(props.skill?.id || imported.value?.targetSkillId))
const targetMismatch = computed(() => {
  const value = imported.value
  const skill = props.skill
  if (!value || !skill) return false
  return value.identity.code !== skill.code
    || value.identity.kind !== skill.kind
    || value.targetSkillId !== skill.id
    || value.targetRowVersion !== skill.rowVersion
})

function chooseFile() {
  input.value?.click()
}

function chooseFolder() {
  folderInput.value?.click()
}

async function onFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  ;(event.target as HTMLInputElement).value = ''
  if (file) await inspect(file)
}

async function onFolder(event: Event) {
  const element = event.target as HTMLInputElement
  const files = Array.from(element.files ?? [])
  element.value = ''
  if (files.length === 0) return
  await inspectResolvedPackage(() => packageFromFileSelection(files))
}

/**
 * Keep the whole import panel as a native desktop-file drop target. Drag events fire
 * again while crossing child elements, so a depth counter avoids flickering the
 * affordance until the pointer has actually left the panel.
 */
function onDragEnter(event: DragEvent) {
  event.preventDefault()
  if (importing.value) return
  dragDepth.value += 1
}

function onDragOver(event: DragEvent) {
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = importing.value ? 'none' : 'copy'
}

function onDragLeave() {
  dragDepth.value = Math.max(0, dragDepth.value - 1)
}

async function onDrop(event: DragEvent) {
  event.preventDefault()
  dragDepth.value = 0
  if (importing.value) return

  const transfer = event.dataTransfer
  if (!transfer) {
    error.value = '未读取到文件，请拖入一个 YCPS 1.1 Expert ZIP 或文件夹。'
    return
  }

  const items = Array.from(transfer.items ?? [])
  const files = Array.from(transfer.files ?? [])
  const fallbackDirectory = files.some(file => Boolean(file.webkitRelativePath))
  if (items.length > 1 || (items.length === 0 && files.length > 1 && !fallbackDirectory)) {
    error.value = '一次只能导入一个 YCPS 1.1 Expert ZIP 或文件夹。'
    return
  }

  // The helper copies DataTransfer.items/files synchronously. Chromium clears the
  // drag data store as soon as this drop handler yields for the first time.
  const packagePromise = packageFromDrop(transfer)
  await inspectResolvedPackage(() => packagePromise)
}

async function inspect(file: File) {
  await inspectResolvedPackage(() => packageFromFileSelection([file]))
}

async function inspectResolvedPackage(resolveFile: () => File | Promise<File>) {
  if (importing.value) return
  importing.value = true
  error.value = ''
  try {
    // Browsers rename repeated downloads to names such as `foo.ycps(1).zip`.
    // Keep the bytes intact but restore the canonical suffix required by the gateway.
    const result = await uploadAdminPortableImport(await resolveFile())
    imported.value = result
    Object.keys(fieldActions).forEach((key) => delete fieldActions[key])
    Object.keys(imageActions).forEach((key) => delete imageActions[key])
    Object.entries(result.fields).forEach(([key, diff]) => {
      fieldActions[key] = diff.defaultAction ?? 'FILL_EMPTY'
    })
    Object.entries(result.images).forEach(([key, diff]) => {
      imageActions[key] = diff.defaultAction ?? 'FILL_EMPTY'
    })
    ElMessage.success('已完成安全检查，请核对字段差异后确认导入')
  } catch (cause) {
    imported.value = null
    error.value = cause instanceof Error && !('response' in cause) && cause.message
      ? cause.message
      : skillAdminErrorMessage(cause, { noun: '专家', packageNoun: '专家包' })
  } finally {
    importing.value = false
  }
}

function display(value: unknown) {
  if (value === null || value === undefined) return '（包未提供）'
  if (Array.isArray(value)) return value.join('、') || '（空）'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value) || '（空）'
}

function label(name: string) {
  return ({
    name: '名称', nameEn: '英文名称', summary: '卡片摘要', summaryEn: '英文卡片摘要',
    description: '详情简介', descriptionEn: '英文详情简介', minClientVersion: '最低客户端版本',
    taskTypes: '任务类型', taskTypesEn: '英文任务类型', usageGuide: '使用指南',
    usageGuideEn: '英文使用指南', examplePrompts: '示例提示词',
    examplePromptsEn: '英文示例提示词', preview: '示例预览', previewEn: '英文示例预览',
    exampleImageLocale: '示例图片语言',
  } as Record<string, string>)[name] ?? name
}

function actionLabel(action: PortableImportFieldAction | PortableImportImageAction) {
  return ({
    FILL_EMPTY: '仅填空',
    OVERWRITE: '覆盖',
    MERGE: '去重合并',
  } as Record<string, string>)[action] ?? action
}

async function commit() {
  const value = imported.value
  if (!value || committing.value || targetMismatch.value) return
  committing.value = true
  error.value = ''
  try {
    const response = await commitAdminPortableImport(value.importId, {
      // In an edit surface, bind the commit to the skill/version currently shown by
      // that surface.  The backend also compares it with the stage-time snapshot, so
      // a stale diff fails closed with 409 instead of updating another Expert by code.
      skillId: props.skill?.id ?? value.targetSkillId,
      expectedRowVersion: props.skill?.rowVersion ?? value.targetRowVersion,
      fields: { ...fieldActions },
      images: { ...imageActions },
    })
    imported.value = null
    emit('committed', response)
    ElMessage.success('YCPS 1.1 专家包已导入并生成待发布修订，请在版本管理中设为线上')
  } catch (cause) {
    error.value = skillAdminErrorMessage(cause, { noun: '专家', packageNoun: '专家包' })
  } finally {
    committing.value = false
  }
}
</script>

<template>
  <section
    class="portable-import-panel"
    :class="{ 'portable-import-panel--dragging': dragActive }"
    :aria-busy="importing"
    aria-label="导入 Expert YCPS 1.1"
    role="region"
    data-test="portable-import-panel"
    @dragenter="onDragEnter"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <div class="portable-import-panel__head">
      <div class="portable-import-panel__title">
        <span class="portable-import-panel__icon" aria-hidden="true">
          <PackageOpen :size="17" stroke-width="2" />
        </span>
        <h3 data-test="portable-import-title">导入 Expert YCPS 1.1</h3>
      </div>
    </div>
    <p class="field-help portable-import-panel__hint" data-test="portable-import-hint">
      支持点击选择 .ycps.zip 或 YCPS 文件夹，也可将它们拖入此框；系统先检查，再由你确认字段差异。包内的展示内容与三类图片会进入确认，分类、标签、排序、状态、发布等运营字段不导入。导入后需在版本管理中设为线上。
    </p>

    <input
      ref="input"
      type="file"
      accept=".ycps.zip,application/zip"
      :disabled="importing"
      data-test="portable-import-file"
      hidden
      @change="onFile"
    />
    <input
      ref="folderInput"
      type="file"
      webkitdirectory
      directory
      multiple
      :disabled="importing"
      data-test="portable-import-folder"
      hidden
      @change="onFolder"
    />
    <div class="portable-import-panel__pick-actions">
      <el-button
        class="portable-import-panel__main"
        type="primary"
        :loading="importing"
        data-test="portable-import-pick"
        @click="chooseFile"
      >
        <Upload :size="15" aria-hidden="true" />
        选择 YCPS 包
      </el-button>
      <el-button
        :disabled="importing"
        data-test="portable-import-folder-pick"
        @click="chooseFolder"
      >
        <FolderOpen :size="15" aria-hidden="true" />
        选择文件夹
      </el-button>
      <span v-if="importing" class="portable-import-panel__busy" role="status" aria-live="polite">
        正在检查包内容…
      </span>
    </div>

    <div v-if="dragActive" class="portable-import-panel__drop-hint" data-test="portable-import-drop-hint">
      <Upload :size="17" aria-hidden="true" />
      松开后检查 ZIP 或文件夹
    </div>

    <template v-if="imported">
      <div class="portable-import-panel__identity">
        <strong>{{ imported.identity.code }}</strong>
        <span>· {{ imported.identity.kind }} · v{{ imported.identity.version }} · r{{ imported.identity.revision }}</span>
        <small>SHA-256 {{ imported.artifactSha256 }}</small>
      </div>
      <p v-for="warning in imported.warnings" :key="warning" class="field-help portable-import-warning">⚠ {{ warning }}</p>
      <p v-if="targetMismatch" class="field-error" data-test="portable-import-target-mismatch">
        当前编辑的专家与包内 code/kind 或 rowVersion 不一致，暂存快照已失效。请在对应专家页面重新上传。
      </p>

      <div class="portable-import-panel__diff">
        <div v-for="([name, diff]) in fields" :key="name" class="portable-import-field">
          <div class="portable-import-field__name">{{ label(name) }}</div>
          <div class="portable-import-field__value">{{ display(diff.currentValue) }}</div>
          <span>→</span>
          <div class="portable-import-field__value">{{ display(diff.packageValue) }}</div>
          <el-select v-model="fieldActions[name]" size="small" :disabled="!diff.present" :data-test="`portable-import-action-${name}`">
            <el-option v-for="action in diff.allowedActions" :key="action" :label="actionLabel(action)" :value="action" />
          </el-select>
        </div>
        <div v-for="([name, diff]) in images" :key="`image-${name}`" class="portable-import-field">
          <div class="portable-import-field__name">图片 · {{ label(name) }}</div>
          <div class="portable-import-field__value">{{ display(diff.currentValue) }}</div>
          <span>→</span>
          <div class="portable-import-field__value">{{ display(diff.packageValue) }}</div>
          <el-select v-model="imageActions[name]" size="small" :disabled="!diff.present">
            <el-option v-for="action in diff.allowedActions" :key="action" :label="actionLabel(action)" :value="action" />
          </el-select>
        </div>
      </div>

      <div class="portable-import-inventory">
        资源清单：{{ imported.resources.count }} 项，共 {{ imported.resources.totalBytes }} bytes
        <span v-for="resource in imported.resources.resources" :key="resource.role"> · {{ resource.role }} ({{ resource.sha256.slice(0, 12) }})</span>
      </div>
      <div class="portable-import-panel__actions">
        <el-button :loading="committing" :disabled="targetMismatch" type="primary" data-test="portable-import-commit" @click="commit">
          {{ hasTarget ? '确认导入到当前专家' : '新建专家并导入' }}
        </el-button>
        <el-button :disabled="committing" @click="imported = null">取消</el-button>
      </div>
    </template>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.portable-import-panel {
  position: relative;
  margin: 18px 0;
  padding: 16px 18px;
  border: 1px dashed var(--app-border-subtle, var(--el-border-color));
  border-radius: var(--radius-md, 8px);
  background: var(--app-surface-subtle, var(--el-fill-color-lighter));
  transition: border-color 0.15s ease, background-color 0.15s ease, box-shadow 0.15s ease;
}

.portable-import-panel--dragging {
  border-color: var(--app-primary, var(--el-color-primary));
  background: var(--app-primary-soft, var(--el-color-primary-light-9));
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--app-primary, var(--el-color-primary)) 12%, transparent);
}

.portable-import-panel__head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.portable-import-panel__title {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.portable-import-panel__title h3 {
  margin: 0;
  color: var(--app-fg, var(--el-text-color-primary));
  font-size: 15px;
  line-height: 1.35;
}

.portable-import-panel__icon {
  display: inline-flex;
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  align-items: center;
  justify-content: center;
  color: var(--app-primary, var(--el-color-primary));
  background: var(--app-primary-soft, var(--el-color-primary-light-9));
  border: 1px solid var(--app-primary-border, var(--el-color-primary-light-7));
  border-radius: var(--radius-sm, 6px);
}

.portable-import-panel__hint {
  max-width: 920px;
  margin: 8px 0 0;
  color: var(--app-muted-strong, var(--el-text-color-secondary));
  line-height: 1.55;
}

.portable-import-panel__pick-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.portable-import-panel__pick-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.portable-import-panel__busy {
  color: var(--app-muted, var(--el-text-color-secondary));
  font-size: 12px;
}

.portable-import-panel__drop-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  margin-top: 12px;
  padding: 10px 12px;
  color: var(--app-primary, var(--el-color-primary));
  font-size: 13px;
  font-weight: 650;
  pointer-events: none;
}

.portable-import-panel__identity,
.portable-import-panel__actions {
  display: flex;
  align-items: center;
  gap: 12px;
  justify-content: space-between;
}

.portable-import-panel__identity {
  justify-content: flex-start;
  margin: 14px 0 12px;
  flex-wrap: wrap;
}

.portable-import-panel__identity small {
  color: var(--el-text-color-secondary);
  word-break: break-all;
}

.portable-import-warning { color: var(--el-color-warning); }

.portable-import-field {
  display: grid;
  grid-template-columns: 120px minmax(90px, 1fr) 18px minmax(90px, 1fr) 130px;
  gap: 8px;
  align-items: center;
  padding: 7px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.portable-import-field__name { font-weight: 600; }

.portable-import-field__value {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.portable-import-inventory {
  margin: 12px 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.portable-import-panel__actions { justify-content: flex-end; }

@media (max-width: 900px) {
  .portable-import-panel { padding: 14px; }
  .portable-import-panel__hint { max-width: none; }
  .portable-import-field { grid-template-columns: 100px 1fr 18px 1fr; }
  .portable-import-field .el-select { grid-column: 1 / -1; }
}

@media (max-width: 560px) {
  .portable-import-panel__pick-actions { flex-direction: column; }
}
</style>
