import axios from 'axios'

import { gatewayHttp } from './http'
import type { UsageDimension } from './platform'

export interface ApiKeyView {
  id: string
  name: string
  keyPrefix: string
  allowedModels: string[]
  status: ApiKeyStatus | 'REVOKED'
  lastUsedAt: string | null
  expiresAt: string | null
  createdAt: string
}

export type ApiKeyStatus = 'ACTIVE' | 'DISABLED'

export interface CreatedApiKey extends Omit<ApiKeyView, 'lastUsedAt' | 'createdAt'> {
  secret: string
}

export interface ResetApiKey {
  id: string
  keyPrefix: string
  secret: string
}

export interface ModelQuota {
  modelCode: string
  totalTokens: number
  reservedTokens: number
  usedTokens: number
  remainingTokens: number
}

/**
 * 方案 C 的第二份额度：音频秒（ASR/TTS/唇形同步）。
 *
 * 四个字段**永远是秒**，不随 `usageDimension` 变化——第二份桶只属于
 * `AUDIO_SECOND`（网关 V97 的 CHECK 就是这么写的），所以这里不再带一个
 * 单位字段，也就不会出现「读了单位却读错」的空间。
 *
 * `packageInstance.audioSeconds` 为 null/缺失表示**这个套餐不卖语音**：
 * 那种情况必须整块不渲染，而不是显示 「0 秒」——一个「包含 0 秒语音」的
 * 承诺是假的，数据库的 CHECK 也正是为此拒绝 0 额度。
 */
export interface AudioSecondsQuota {
  totalSeconds: number
  reservedSeconds: number
  usedSeconds: number
  /** 已经扣掉在途预留，与 `remainingTokens` 同一套算术，因此可以是负数。 */
  remainingSeconds: number
}

export interface ActivePackage {
  packageInstanceId?: string
  packageCode: string
  packageName?: string
  status?: string
  totalTokens?: number
  reservedTokens?: number
  usedTokens?: number
  remainingTokens?: number
  dailyRequestLimit?: number | null
  expiresAt?: string
  allowedModels?: string[]
  /**
   * 额度的计量单位。`VIDEO_SECOND` / `AUDIO_SECOND` / `IMAGE` 套餐里
   * totalTokens / usedTokens / remainingTokens 存的是**秒**（或张）而不是积分：
   * 用户端不展示视频/音频套餐的秒数额度（套餐页只写"按秒计费"），
   * 所以这些页面按本字段分流，不靠套餐代码猜。
   *
   * 类型复用 {@link UsageDimension} 而不是就地再写一份联合类型：三个文件各写
   * 一遍字面量联合，就是「新增一个维度只改了其中两处、第三处静默把新维度
   * 当成 token」的来源。
   */
  usageDimension?: UsageDimension
  modelQuotas?: ModelQuota[]
  /**
   * 方案 C 的第二份额度（音频秒），或 null/缺失表示这个套餐不卖语音。
   *
   * 它是**加法**的：上面那四个计数器描述主维度那一份（单位由
   * `usageDimension` 决定），这一份描述音频秒那一份。两者单位可能相同
   * （视频秒 + 音频秒），但属于不同的产品，因此必须分开渲染，
   * **绝不能相加**成一个总数。
   */
  audioSeconds?: AudioSecondsQuota | null
}

export interface QueuedPackage {
  packageInstanceId?: string
  packageCode: string
  packageName?: string
  totalTokens?: number
  createdAt?: string
  /** 队列按维度推进：排队的视频套餐不能按 token 套餐渲染。 */
  usageDimension?: UsageDimension
}

export interface QuotaSummary {
  /**
   * 兼容字段：文本实例优先，否则唯一那个生效实例。
   * 同时持有文本与视频套餐时它只代表其中一个 —— 需要完整视图请读 activePackages。
   */
  activePackage: ActivePackage | null
  /** 每个用量维度至多一个生效实例（V80 起唯一性按维度收窄）。 */
  activePackages?: ActivePackage[]
  queuedPackages: QueuedPackage[]
}

export interface UsageStageEntry {
  stageIndex: number
  stageType: 'FINAL_RESPONSE' | 'FILE_ANALYSIS'
  logicalModelCode: string
  providerCode: string
  channelCode: string
  upstreamModel: string
  inputTokens: number
  outputTokens: number
  totalTokens: number
  durationMs: number
  outcome: 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  providerCostAmount: number | null
  providerCostCurrency: string | null
  priceEstimated: boolean
}

export interface UsageEntry {
  id: string
  requestId: string
  reservationId?: string
  apiKeyPrefix?: string
  modelCode: string
  providerCode?: string
  channelCode?: string
  upstreamModel?: string | null
  inputTokens: number
  outputTokens: number
  totalTokens: number
  /**
   * 本次调用实际消耗量与它的计量单位。视频生成按秒结算，token 计数器按约束全为 0
   * （`totalTokens` 会把它显示成"消耗 0 积分"），真正要显示的是 `usageUnits`（已计费秒数）。
   */
  usageDimension?: UsageDimension
  usageUnits?: number
  usageEstimated?: boolean
  stream?: boolean
  firstTokenMs?: number | null
  durationMs: number
  httpStatus: number
  outcome: string
  errorCategory?: string | null
  errorSummary?: string | null
  /**
   * 生成终态：`completed` / `failed` / `truncated`；历史行为 `null`。
   *
   * 与 `outcome` 并列而不是替代：`outcome` 是计量口径，`httpStatus` 是传输口径，
   * 而这一项回答"这次生成到底完成了没有"。被输出上限截断的请求三者分别是
   * `SUCCEEDED` / `200` / `truncated`——只看前两项，它与一次自然结束完全一样。
   *
   * 可选是因为旧服务的响应里没有这个键；展示层必须把"键不存在"与 `null` 都读成"未知"，
   * 不能读成"成功"。
   */
  generationStatus?: string | null
  /** 上游给出的结束原因（`stop` / `length` …）；未观察到为 `null`。 */
  finishReason?: string | null
  /** 本次请求被准入的输出上限；`null`/缺失表示未知。 */
  effectiveMaxOutputTokens?: number | null
  /** 上限是被哪一层压出来的：`client` / `model` / `package` / `system`。 */
  ceilingSource?: string | null
  /** 这次请求产生了几次供应商调用（含重试与换渠道）。 */
  attemptCount?: number | null
  createdAt: string
  stages: UsageStageEntry[]
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface UsageFilters {
  page?: number
  size?: number
  modelCode?: string
  outcome?: string
  requestId?: string
  from?: string
  to?: string
}

export interface UsageMetrics {
  sampleCount: number
  platformAddedP95Ms: number
}

export interface DailyUsage {
  date: string
  requestCount: number
  inputTokens: number
  outputTokens: number
  totalTokens: number
}

export interface ModelUsage {
  modelCode: string
  providerCode: string | null
  upstreamModel: string | null
  requestCount: number
  totalTokens: number
}

export interface AnalyticsRange {
  days: 7 | 30
  timezone: string
  from: string
  to: string
}

export interface UsageAnalytics {
  range: AnalyticsRange
  summary: {
    todayRequests: number
    todaySuccessfulRequests: number
    todayFailedRequests: number
    todayTokens: number
    periodRequests: number
    periodTokens: number
    platformAddedP95Ms: number
  }
  daily: DailyUsage[]
  models: ModelUsage[]
}

export interface FileView {
  id: string
  object: 'file'
  bytes: number
  created_at: number
  filename: string
  purpose: string
  status: FileProcessingStatus
  expires_at: number
}

export type FileProcessingStatus =
  | 'pending_upload'
  | 'uploaded'
  | 'scanning'
  | 'processed'
  | 'rejected'
  | 'failed'

export interface UploadProgress {
  phase: 'hashing' | 'uploading' | 'scanning'
  percent: number
}

export class FilePollingTimeoutError extends Error {
  readonly code = 'file_processing_timeout'

  constructor() {
    super('file_processing_timeout')
    this.name = 'FilePollingTimeoutError'
  }
}

interface UploadSession {
  file_id: string
  upload_session_id: string
  status: FileProcessingStatus
  upload: {
    method: 'POST'
    url: string
    fields: Record<string, string>
  }
  upload_expires_at: number
  file_expires_at: number
}

export interface FileList {
  object: 'list'
  data: FileView[]
}

export async function listApiKeys(): Promise<ApiKeyView[]> {
  const response = await gatewayHttp.get<ApiKeyView[]>('/console/gateway/api-keys')
  return response.data
}

export async function listEligibleApiKeyModels(): Promise<string[]> {
  const response = await gatewayHttp.get<string[]>('/console/gateway/api-keys/eligible-models')
  return response.data
}

export async function createApiKey(name: string, allowedModels: string[]): Promise<CreatedApiKey> {
  const response = await gatewayHttp.post<CreatedApiKey>('/console/gateway/api-keys', {
    name,
    allowedModels,
  })
  return response.data
}

export async function revokeApiKey(id: string): Promise<void> {
  await gatewayHttp.delete(`/console/gateway/api-keys/${encodeURIComponent(id)}`)
}

export async function changeApiKeyStatus(id: string, status: ApiKeyStatus): Promise<ApiKeyView> {
  const response = await gatewayHttp.patch<ApiKeyView>(
    `/console/gateway/api-keys/${encodeURIComponent(id)}/status`,
    { status },
  )
  return response.data
}

export async function resetApiKey(id: string, smsProof: string): Promise<ResetApiKey> {
  const response = await gatewayHttp.post<ResetApiKey>(
    `/console/gateway/api-keys/${encodeURIComponent(id)}/reset`,
    null,
    { headers: { 'X-SMS-Proof': smsProof } },
  )
  return response.data
}

export async function quotaSummary(): Promise<QuotaSummary> {
  const response = await gatewayHttp.get<QuotaSummary>('/console/gateway/quota/summary')
  return response.data
}

export async function listUsage(filters: UsageFilters = {}): Promise<PageResult<UsageEntry>> {
  const response = await gatewayHttp.get<PageResult<UsageEntry>>('/console/gateway/usage', {
    params: {
      page: filters.page ?? 0,
      size: filters.size ?? 20,
      modelCode: filters.modelCode || undefined,
      outcome: filters.outcome || undefined,
      requestId: filters.requestId || undefined,
      from: filters.from || undefined,
      to: filters.to || undefined,
    },
  })
  return response.data
}

export async function usageMetrics(): Promise<UsageMetrics> {
  const response = await gatewayHttp.get<UsageMetrics>('/console/gateway/usage/metrics')
  return response.data
}

export async function getUsageAnalytics(days: 7 | 30): Promise<UsageAnalytics> {
  const response = await gatewayHttp.get<UsageAnalytics>('/console/gateway/usage/analytics', {
    params: { days },
  })
  return response.data
}

function apiKeyHeaders(apiKey: string) {
  return { Authorization: `Bearer ${apiKey}`, Accept: 'application/json' }
}

export async function listFiles(apiKey: string): Promise<FileList> {
  const response = await axios.get<FileList>('/v1/files', { headers: apiKeyHeaders(apiKey) })
  return response.data
}

function uploadPercent(loaded: number, total?: number) {
  if (!total || total <= 0) return 0
  return Math.min(100, Math.round((loaded / total) * 100))
}

function directUploadErrorCode(error: unknown): string | undefined {
  return (error as { response?: { data?: { error?: { code?: string } } } })
    ?.response?.data?.error?.code
}

const mimeTypesByExtension: Record<string, string> = {
  csv: 'text/csv',
  doc: 'application/msword',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  gif: 'image/gif',
  jpeg: 'image/jpeg',
  jpg: 'image/jpeg',
  markdown: 'text/markdown',
  md: 'text/markdown',
  pdf: 'application/pdf',
  png: 'image/png',
  txt: 'text/plain',
  webp: 'image/webp',
  xls: 'application/vnd.ms-excel',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
}

function uploadMimeType(file: File) {
  if (file.type) return file.type
  const extension = file.name.includes('.') ? file.name.split('.').pop()!.toLowerCase() : ''
  return mimeTypesByExtension[extension] ?? 'application/octet-stream'
}

async function legacyUpload(
  apiKey: string,
  file: File,
  onProgress?: (progress: UploadProgress) => void,
  signal?: AbortSignal,
): Promise<FileView> {
  const body = new FormData()
  body.append('file', file)
  body.append('purpose', 'assistants')
  onProgress?.({ phase: 'uploading', percent: 0 })
  const response = await axios.post<FileView>('/v1/files', body, {
    headers: apiKeyHeaders(apiKey),
    signal,
    onUploadProgress: ({ loaded, total }) => {
      onProgress?.({ phase: 'uploading', percent: uploadPercent(loaded, total) })
    },
  })
  return response.data
}

function abortReason(signal: AbortSignal) {
  return signal.reason ?? new DOMException('The operation was aborted.', 'AbortError')
}

function waitForNextPoll(signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    if (signal.aborted) {
      reject(abortReason(signal))
      return
    }

    const onAbort = () => {
      clearTimeout(timer)
      reject(abortReason(signal))
    }
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort)
      resolve()
    }, 1_000)
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

function createPollingControl(callerSignal?: AbortSignal) {
  const controller = new AbortController()
  let timedOut = false
  const onCallerAbort = () => controller.abort(abortReason(callerSignal!))

  if (callerSignal?.aborted) {
    onCallerAbort()
  } else {
    callerSignal?.addEventListener('abort', onCallerAbort, { once: true })
  }
  const deadlineTimer = setTimeout(() => {
    timedOut = true
    controller.abort(new FilePollingTimeoutError())
  }, 30_000)

  return {
    signal: controller.signal,
    timedOut: () => timedOut,
    cleanup: () => {
      clearTimeout(deadlineTimer)
      callerSignal?.removeEventListener('abort', onCallerAbort)
    },
  }
}

export async function uploadFile(
  apiKey: string,
  file: File,
  onProgress?: (progress: UploadProgress) => void,
  signal?: AbortSignal,
): Promise<FileView> {
  onProgress?.({ phase: 'hashing', percent: 0 })
  const fileBytes = await file.arrayBuffer()
  const hash = await crypto.subtle.digest('SHA-256', fileBytes)
  const sha256 = Array.from(new Uint8Array(hash), (byte) => byte.toString(16).padStart(2, '0')).join('')
  onProgress?.({ phase: 'hashing', percent: 100 })
  if (signal?.aborted) throw abortReason(signal)

  let session: UploadSession
  try {
    const response = await axios.post<UploadSession>(
      '/v1/files/upload-sessions',
      {
        filename: file.name,
        bytes: file.size,
        mime_type: uploadMimeType(file),
        sha256,
        purpose: 'assistants',
      },
      {
        headers: {
          ...apiKeyHeaders(apiKey),
          'Idempotency-Key': crypto.randomUUID(),
        },
        ...(signal ? { signal } : {}),
      },
    )
    session = response.data
  } catch (error) {
    if (signal?.aborted) throw abortReason(signal)
    if (directUploadErrorCode(error) === 'direct_upload_disabled') {
      return legacyUpload(apiKey, file, onProgress, signal)
    }
    throw error
  }

  const uploadBody = new FormData()
  for (const [name, value] of Object.entries(session.upload.fields)) {
    uploadBody.append(name, value)
  }
  uploadBody.append('file', file)

  onProgress?.({ phase: 'uploading', percent: 0 })
  await axios.post(session.upload.url, uploadBody, {
    signal,
    onUploadProgress: ({ loaded, total }) => {
      onProgress?.({ phase: 'uploading', percent: uploadPercent(loaded, total) })
    },
  })

  let completed: { data: FileView }
  try {
    completed = await axios.post<FileView>(
      `/v1/files/upload-sessions/${encodeURIComponent(session.upload_session_id)}/complete`,
      null,
      {
        headers: {
          ...apiKeyHeaders(apiKey),
          'Idempotency-Key': crypto.randomUUID(),
        },
        ...(signal ? { signal } : {}),
      },
    )
  } catch (error) {
    if (signal?.aborted) throw abortReason(signal)
    throw error
  }

  const pollingStartedAt = Date.now()
  const polling = createPollingControl(signal)
  try {
    onProgress?.({ phase: 'scanning', percent: 0 })
    let latest = completed.data
    while (true) {
      if (latest.status === 'processed' || latest.status === 'rejected' || latest.status === 'failed') {
        return latest
      }
      await waitForNextPoll(polling.signal)
      const response = await axios.get<FileView>(`/v1/files/${encodeURIComponent(session.file_id)}`, {
        headers: apiKeyHeaders(apiKey),
        signal: polling.signal,
      })
      latest = response.data
      const elapsed = Date.now() - pollingStartedAt
      onProgress?.({ phase: 'scanning', percent: Math.min(99, Math.round((elapsed / 30_000) * 100)) })
    }
  } catch (error) {
    if (signal?.aborted) throw abortReason(signal)
    if (polling.timedOut()) throw new FilePollingTimeoutError()
    throw error
  } finally {
    polling.cleanup()
  }
}

export async function deleteFile(apiKey: string, id: string): Promise<void> {
  await axios.delete(`/v1/files/${encodeURIComponent(id)}`, { headers: apiKeyHeaders(apiKey) })
}

// Skill 目录已改为控制台登录态访问，见 `src/api/skills.ts`（浏览器里不再出现 API Key）。

