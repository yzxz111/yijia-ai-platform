<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  CircleCheckBig,
  Clock3,
  FileText,
  Link2,
  LoaderCircle,
  ShieldX,
  Trash2,
  TriangleAlert,
  Upload,
  X,
} from 'lucide-vue-next'

import {
  deleteFile,
  listFiles,
  uploadFile,
  type FileProcessingStatus,
  type FileView,
  type UploadProgress,
} from '../../api/gateway'
import { formatDateTime } from '../../utils/dateTime'

const acceptedExtensions = '.png,.jpg,.jpeg,.gif,.webp,.pdf,.doc,.docx,.xls,.xlsx,.csv,.txt,.md,.markdown'
const apiKeyInput = ref('')
const sessionApiKey = ref<string | null>(null)
const files = ref<FileView[]>([])
const connected = ref(false)
const connecting = ref(false)
const uploadProgress = ref<UploadProgress | null>(null)
const errorMessage = ref('')
const activeUpload = ref<AbortController | null>(null)
let connectionGeneration = 0
let mounted = true
const bytes = new Intl.NumberFormat('zh-CN', {
  style: 'unit',
  unit: 'byte',
  notation: 'compact',
  maximumFractionDigits: 1,
})

const phaseLabel = computed(() => {
  switch (uploadProgress.value?.phase) {
    case 'hashing': return '校验文件'
    case 'uploading': return '上传中'
    case 'scanning': return '安全扫描中'
    default: return ''
  }
})

const terminalStatuses = new Set<FileProcessingStatus>(['processed', 'rejected', 'failed'])
const statusPresentation: Record<FileProcessingStatus, { label: string, icon: typeof Clock3; tone: string }> = {
  pending_upload: { label: '等待上传', icon: Clock3, tone: 'pending' },
  uploaded: { label: '等待审核', icon: Clock3, tone: 'pending' },
  scanning: { label: '扫描中', icon: LoaderCircle, tone: 'scanning' },
  processed: { label: '可使用', icon: CircleCheckBig, tone: 'processed' },
  rejected: { label: '已拒绝', icon: ShieldX, tone: 'rejected' },
  failed: { label: '处理失败', icon: TriangleAlert, tone: 'failed' },
}

function uploadErrorCode(error: unknown) {
  return (error as { response?: { data?: { error?: { code?: string } } } })
    ?.response?.data?.error?.code
}

function isExpiredPolicy(error: unknown) {
  if (uploadErrorCode(error) === 'upload_policy_expired') return true
  const response = (error as { response?: { status?: number; data?: unknown } }).response
  return response?.status === 403 && /(?:expired|expire)/i.test(String(response.data ?? ''))
}

function isAbort(error: unknown) {
  const value = error as { name?: string; code?: string }
  return value?.name === 'AbortError' || value?.code === 'ERR_CANCELED'
}

async function connect() {
  if (!apiKeyInput.value.trim() || connecting.value) return
  const generation = ++connectionGeneration
  connecting.value = true
  errorMessage.value = ''
  const key = apiKeyInput.value.trim()
  apiKeyInput.value = ''
  try {
    const result = await listFiles(key)
    if (!mounted || generation !== connectionGeneration) return
    sessionApiKey.value = key
    files.value = result.data
    connected.value = true
  } catch {
    if (!mounted || generation !== connectionGeneration) return
    errorMessage.value = '连接失败，请检查 API 密钥是否有效且套餐仍在生效。'
  } finally {
    if (mounted && generation === connectionGeneration) connecting.value = false
  }
}

function insertFile(file: FileView) {
  files.value = [file, ...files.value.filter((item) => item.id !== file.id)]
}

async function uploadOnce(key: string, file: File, controller: AbortController) {
  return uploadFile(key, file, (progress) => {
    uploadProgress.value = progress
  }, controller.signal)
}

async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  const key = sessionApiKey.value
  if (!file || !key || activeUpload.value) return

  const controller = new AbortController()
  activeUpload.value = controller
  uploadProgress.value = { phase: 'hashing', percent: 0 }
  errorMessage.value = ''
  try {
    let result: FileView
    try {
      result = await uploadOnce(key, file, controller)
    } catch (error) {
      if (!isExpiredPolicy(error) || controller.signal.aborted) throw error
      uploadProgress.value = { phase: 'hashing', percent: 0 }
      result = await uploadOnce(key, file, controller)
    }
    insertFile(result)
    if (result.status === 'rejected') {
      errorMessage.value = '文件未通过安全审核，无法使用。'
    } else if (result.status === 'failed') {
      errorMessage.value = '文件处理失败，请删除后重试。'
    }
  } catch (error) {
    if (!isAbort(error) && !controller.signal.aborted) {
      errorMessage.value = '文件上传失败，请检查类型、大小和套餐限制。'
    }
  } finally {
    input.value = ''
    if (activeUpload.value === controller) {
      activeUpload.value = null
      uploadProgress.value = null
    }
  }
}

function cancelUpload() {
  activeUpload.value?.abort()
}

async function remove(file: FileView) {
  if (!sessionApiKey.value || !terminalStatuses.has(file.status)) return
  try {
    await ElMessageBox.confirm(`删除文件 ${file.filename}？`, '确认删除', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }

  errorMessage.value = ''
  try {
    await deleteFile(sessionApiKey.value, file.id)
    files.value = files.value.filter((item) => item.id !== file.id)
  } catch {
    errorMessage.value = '文件删除失败，请稍后重试。'
  }
}

function disconnect() {
  connectionGeneration += 1
  connecting.value = false
  cancelUpload()
  activeUpload.value = null
  uploadProgress.value = null
  apiKeyInput.value = ''
  sessionApiKey.value = null
  files.value = []
  connected.value = false
  errorMessage.value = ''
}

onBeforeUnmount(() => {
  mounted = false
  disconnect()
})
</script>

<template>
  <section class="files-page" aria-labelledby="files-title">
    <header>
      <div>
        <p>OpenAI 兼容文件</p>
        <h2 id="files-title">文件管理</h2>
      </div>
      <el-button
        v-if="connected"
        data-test="file-disconnect"
        aria-label="断开文件 API 密钥"
        @click="disconnect"
      >
        断开 API 密钥
      </el-button>
    </header>

    <div v-if="!connected" class="connect-panel">
      <Link2 :size="24" aria-hidden="true" />
      <h3>连接一个 API 密钥</h3>
      <p>文件 API 使用 API 密钥鉴权。密钥仅保存在当前页面内存中，不会写入浏览器存储。</p>
      <form @submit.prevent="connect">
        <el-input
          v-model="apiKeyInput"
          data-test="file-api-key"
          type="password"
          name="api-key"
          aria-label="文件 API 密钥"
          autocomplete="off"
          spellcheck="false"
          placeholder="sk-yzh-…"
        />
        <el-button native-type="submit" type="primary" data-test="file-connect" :loading="connecting">
          连接
        </el-button>
      </form>
    </div>

    <template v-else>
      <div class="file-toolbar">
        <label class="upload-button" :class="{ disabled: Boolean(activeUpload) }">
          <Upload :size="16" aria-hidden="true" />
          上传文件
          <input
            type="file"
            data-test="file-upload"
            :accept="acceptedExtensions"
            :disabled="Boolean(activeUpload)"
            aria-label="选择要上传的文件"
            @change="upload"
          />
        </label>
        <span>支持图片、PDF、Office 和文本文件；大小由当前套餐限制。</span>
      </div>

      <div v-if="uploadProgress" class="upload-progress" aria-live="polite">
        <div class="upload-progress-heading">
          <span data-test="file-upload-phase">{{ phaseLabel }}</span>
          <el-button
            text
            data-test="file-upload-cancel"
            aria-label="取消文件上传"
            @click="cancelUpload"
          >
            <X :size="16" aria-hidden="true" />
            取消
          </el-button>
        </div>
        <el-progress
          data-test="file-upload-progress"
          :percentage="uploadProgress.percent"
          :stroke-width="8"
        />
      </div>

      <div class="file-list">
        <article v-for="file in files" :key="file.id" :data-test="`file-${file.id}`">
          <FileText :size="20" aria-hidden="true" />
          <div class="file-identity">
            <strong>{{ file.filename }}</strong>
            <code>{{ file.id }}</code>
          </div>
          <span
            class="file-status"
            :class="`is-${statusPresentation[file.status].tone}`"
            :data-test="`file-status-${file.id}`"
          >
            <component
              :is="statusPresentation[file.status].icon"
              :class="{ spinning: file.status === 'scanning' }"
              :size="15"
              aria-hidden="true"
            />
            {{ statusPresentation[file.status].label }}
          </span>
          <dl>
            <div><dt>大小</dt><dd>{{ bytes.format(file.bytes) }}</dd></div>
              <div><dt>到期</dt><dd>{{ formatDateTime(new Date(file.expires_at * 1000)) }}</dd></div>
          </dl>
          <button
            v-if="terminalStatuses.has(file.status)"
            type="button"
            :data-test="`file-delete-${file.id}`"
            :aria-label="`删除文件 ${file.filename}`"
            @click="remove(file)"
          >
            <Trash2 :size="16" aria-hidden="true" />
          </button>
        </article>
        <div v-if="!files.length" class="empty-state">暂无文件</div>
      </div>
    </template>

    <TopErrorMessage :message="errorMessage" />
  </section>
</template>

<style scoped>
.files-page {
  width: min(1100px, 100%);
  min-width: 0;
}

header {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

header p { margin: 0 0 3px; color: var(--app-primary); font-size: 12px; font-weight: 700; }
header h2 { margin: 0; font-size: 22px; }

.connect-panel {
  display: flex;
  min-height: 280px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  padding: 28px;
  text-align: center;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius);
}

.connect-panel svg { color: var(--app-primary); }
.connect-panel h3 { margin: 12px 0 5px; }
.connect-panel p { max-width: 560px; margin: 0 0 16px; color: var(--app-muted); }
.connect-panel form { display: grid; width: min(520px, 100%); grid-template-columns: 1fr auto; gap: 8px; }

.file-toolbar {
  display: flex;
  min-width: 0;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 12px;
}

.file-toolbar > span { min-width: 0; color: var(--app-muted); font-size: 12px; }

.upload-button {
  display: inline-flex;
  min-height: 44px;
  flex: 0 0 auto;
  align-items: center;
  gap: 7px;
  padding: 0 14px;
  color: #fff;
  background: var(--app-primary);
  border-radius: 7px;
  cursor: pointer;
  font-weight: 650;
  transition: opacity var(--app-motion-fast) ease;
}

.upload-button:hover { opacity: .9; }
.upload-button:focus-within { outline: 2px solid var(--app-primary-border); outline-offset: 2px; }
.upload-button.disabled { cursor: not-allowed; opacity: .55; }
.upload-button input { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); }

.upload-progress {
  margin-bottom: 12px;
  padding: 12px 14px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius);
}

.upload-progress-heading { display: flex; min-height: 32px; align-items: center; justify-content: space-between; gap: 12px; font-size: 13px; font-weight: 650; }
.upload-progress-heading :deep(.el-button) { min-height: 40px; }

.file-list { display: flex; min-width: 0; flex-direction: column; gap: 9px; }
.file-list article { display: flex; min-width: 0; align-items: center; gap: 12px; padding: 14px 16px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: var(--app-radius); }
.file-list article > svg { flex: 0 0 auto; color: var(--app-primary); }
.file-identity { min-width: 0; flex: 1; }
.file-list strong, .file-list code { display: block; overflow-wrap: anywhere; }
.file-list code { margin-top: 3px; color: var(--app-muted); font-size: 11px; }
.file-list dl { display: flex; gap: 18px; margin: 0; }
.file-list dl div { display: flex; gap: 6px; }
.file-list dt { color: var(--app-muted); }
.file-list dd { margin: 0; }

.file-status { display: inline-flex; flex: 0 0 auto; align-items: center; gap: 4px; font-size: 12px; font-weight: 650; }
.file-status.is-pending, .file-status.is-scanning { color: var(--app-warning); }
.file-status.is-processed { color: var(--app-success-text); }
.file-status.is-rejected, .file-status.is-failed { color: var(--app-danger); }
.spinning { animation: spin 1s linear infinite; }

.file-list article button {
  display: inline-flex;
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  align-items: center;
  justify-content: center;
  color: var(--app-danger);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 7px;
  cursor: pointer;
}

.file-list article button:hover, .file-list article button:focus-visible { background: var(--app-danger-soft); border-color: var(--app-danger-soft); }
.empty-state { padding: 40px; color: var(--app-muted); text-align: center; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: var(--app-radius); }

@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 720px) {
  .connect-panel { padding-inline: 18px; }
  .connect-panel form { grid-template-columns: minmax(0, 1fr); }
  .file-toolbar > span { min-width: 0; flex: 1 1 180px; overflow-wrap: anywhere; }
  .file-list article { align-items: flex-start; flex-wrap: wrap; }
  .file-status { margin-left: 32px; }
  .file-list dl { width: 100%; flex-direction: column; gap: 4px; padding-left: 32px; }
}
</style>
