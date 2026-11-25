<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Ban, Copy, KeyRound, LoaderCircle, Pause, Play, Plus, RotateCcw, ShieldCheck } from 'lucide-vue-next'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'

import {
  listEligibleApiKeyModels,
} from '../../api/gateway'
import { getProfile } from '../../api/platform'
import SmsVerificationDialog from '../../components/sms/SmsVerificationDialog.vue'
import { useApiKeysStore } from '../../stores/apiKeys'
import { formatDateTime } from '../../utils/dateTime'
import { statusLabel } from '../../utils/statusLabel'

const apiKeysStore = useApiKeysStore()
const { keys, loading } = storeToRefs(apiKeysStore)
const eligibleModels = ref<string[]>([])
const selectedModels = ref<string[]>([])
const keyName = ref('')
const createOpen = ref(false)
const secretOpen = ref(false)
const createdSecret = ref<string | null>(null)
const submitting = ref(false)
const statusUpdatingId = ref<string | null>(null)
const resettingId = ref<string | null>(null)
const resetTargetId = ref<string | null>(null)
const resetRetryKeyId = ref<string | null>(null)
const resetVerificationOpen = ref(false)
const phoneBound = ref(false)
const smsRequirementKnown = ref(false)
const confirmationPending = ref(false)
const errorMessage = ref('')
const configuredApiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim()
const apiBaseUrl = (configuredApiBaseUrl || `${window.location.origin}/v1`).replace(/\/+$/, '')
let identityAttempt = 0
let confirmationAttempt = 0
let resetVerificationScope: {
  lifecycleEpoch: number
  scope: ReturnType<typeof captureUserScope>
  targetId: string
} | null = null
const RESET_PROOF_RETRY_TTL_MS = 60_000
let resetRetryProof: string | null = null
let resetRetryExpiresAt = 0
let resetRetryTimer: ReturnType<typeof setTimeout> | null = null
let resetLifecycleTargetId: string | null = null
let resetTargetLifecycleEpoch = 0
let resetRequestGeneration = 0

const canSubmit = computed(() => keyName.value.trim().length > 0 && selectedModels.value.length > 0)

const apiKeyCreateErrorMessages: Record<string, string> = {
  invalid_api_key_request: '密钥名称或可调用模型不符合要求，请检查后重试。',
  api_key_limit_exceeded: 'API 密钥数量已达上限，请先撤销不再使用的密钥。',
  api_key_model_unavailable: '当前没有可用于创建 API 密钥的模型。',
  api_key_model_not_allowed: '所选模型不在当前账号的可用范围内，请重新选择。',
  api_key_owner_required: '登录信息无效，请重新登录后重试。',
}

function createFailureReason(error: unknown): string {
  const response = (error as {
    response?: { status?: number; data?: { code?: string; message?: string } }
  })?.response
  const code = response?.data?.code
  const message = response?.data?.message?.trim()
  if (code && message) return message
  if (code && apiKeyCreateErrorMessages[code]) return apiKeyCreateErrorMessages[code]
  if (response?.status === 401) return '登录状态已失效，请重新登录后再试。'
  if (response?.status === 403) return '当前账号没有创建 API 密钥的权限。'
  if (response?.status === 422) return '创建请求未通过校验，请检查密钥名称和所选模型。'
  if (response?.status && response.status >= 500) return 'API 密钥服务暂时不可用，请稍后重试。'
  return '无法连接到 API 密钥服务，请检查网络后重试。'
}

function captureUserScope() {
  return { attempt: identityAttempt, generation: apiKeysStore.userScopeGeneration }
}

function isCurrentUserScope(scope: ReturnType<typeof captureUserScope>) {
  return scope.attempt === identityAttempt && scope.generation === apiKeysStore.userScopeGeneration
}

async function confirmApiKeyAction(
  scope: ReturnType<typeof captureUserScope>,
  openConfirmation: () => Promise<unknown>,
): Promise<boolean> {
  if (confirmationPending.value) return false
  const attempt = ++confirmationAttempt
  confirmationPending.value = true
  try {
    await openConfirmation()
    return attempt === confirmationAttempt && isCurrentUserScope(scope)
  } catch {
    return false
  } finally {
    if (attempt === confirmationAttempt) confirmationPending.value = false
  }
}

function closeOwnedConfirmation() {
  if (!confirmationPending.value) return
  confirmationAttempt += 1
  confirmationPending.value = false
  ElMessageBox.close()
}

async function load() {
  const scope = captureUserScope()
  errorMessage.value = ''
  phoneBound.value = false
  smsRequirementKnown.value = false
  try {
    const [, models, profile] = await Promise.all([
      apiKeysStore.loadKeys(),
      listEligibleApiKeyModels(),
      getProfile(),
    ])
    if (!isCurrentUserScope(scope)) return
    eligibleModels.value = models
    selectedModels.value = [...eligibleModels.value]
    phoneBound.value = Boolean(profile?.phoneVerified)
    smsRequirementKnown.value = true
  } catch {
    if (isCurrentUserScope(scope)) errorMessage.value = 'API 密钥加载失败，请稍后重试。'
  }
}

function openCreate() {
  keyName.value = ''
  selectedModels.value = [...eligibleModels.value]
  createOpen.value = true
}

async function submitCreate() {
  if (!canSubmit.value || submitting.value) return
  const scope = captureUserScope()
  submitting.value = true
  errorMessage.value = ''
  try {
    const created = await apiKeysStore.create(keyName.value.trim(), selectedModels.value)
    if (!isCurrentUserScope(scope)) return
    createdSecret.value = created.secret
    createOpen.value = false
    secretOpen.value = true
  } catch (error) {
    if (isCurrentUserScope(scope)) {
      void ElMessageBox.alert(
        createFailureReason(error),
        'API 密钥创建失败',
        {
          appendTo: document.body,
          confirmButtonText: '知道了',
          type: 'error',
        },
      ).catch(() => undefined)
    }
  } finally {
    if (isCurrentUserScope(scope)) submitting.value = false
  }
}

async function copyText(value: string, successMessage: string, failureMessage: string) {
  let copied = false
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value)
      copied = true
    }
  } catch {
    copied = false
  }

  if (!copied) {
    const copyField = document.createElement('textarea')
    copyField.value = value
    copyField.setAttribute('readonly', '')
    copyField.style.position = 'fixed'
    copyField.style.opacity = '0'
    copyField.style.pointerEvents = 'none'
    document.body.appendChild(copyField)
    copyField.focus()
    copyField.select()
    try {
      copied = document.execCommand?.('copy') ?? false
    } catch {
      copied = false
    } finally {
      copyField.remove()
    }
  }

  if (copied) ElMessage.success(successMessage)
  else ElMessage.error(failureMessage)
}

async function copySecret() {
  const secret = createdSecret.value
  if (!secret) return
  await copyText(secret, 'API 密钥已复制', '复制失败，请手动选择并复制 API 密钥')
}

async function copyApiBaseUrl() {
  await copyText(apiBaseUrl, 'API Base URL 已复制', '复制失败，请手动选择并复制 API Base URL')
}

async function revoke(key: { id: string; name: string }) {
  const scope = captureUserScope()
  const confirmed = await confirmApiKeyAction(scope, () => ElMessageBox.confirm(
      `撤销密钥 ${key.name}？撤销后使用该密钥的请求会立即失败。`,
      '确认撤销',
      { type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消' },
    ))
  if (!confirmed) return
  try {
    await apiKeysStore.revoke(key.id)
  } catch {
    if (isCurrentUserScope(scope)) errorMessage.value = '密钥撤销失败，请稍后重试。'
  }
}

async function changeStatus(key: { id: string; name: string }, status: 'ACTIVE' | 'DISABLED') {
  if (statusUpdatingId.value || resettingId.value || resetVerificationOpen.value) return
  const scope = captureUserScope()
  const enabling = status === 'ACTIVE'
  const confirmed = await confirmApiKeyAction(scope, () => ElMessageBox.confirm(
      enabling
        ? `重新启用密钥 ${key.name}？`
        : `停用密钥 ${key.name}？停用后可以重新启用。`,
      enabling ? '确认启用' : '确认停用',
      {
        type: 'warning',
        confirmButtonText: enabling ? '启用' : '停用',
        cancelButtonText: '取消',
      },
    ))
  if (!confirmed) return

  errorMessage.value = ''
  statusUpdatingId.value = key.id
  try {
    await apiKeysStore.changeStatus(key.id, status)
  } catch {
    if (isCurrentUserScope(scope)) errorMessage.value = '密钥状态更新失败，请稍后重试。'
  } finally {
    if (isCurrentUserScope(scope)) statusUpdatingId.value = null
  }
}

function resetResponseStatus(error: unknown): number | null {
  if (typeof error !== 'object' || error === null || !('response' in error)) return null
  const response = (error as { response?: unknown }).response
  if (typeof response !== 'object' || response === null || !('status' in response)) return null
  const status = (response as { status?: unknown }).status
  return typeof status === 'number' ? status : null
}

function hasResettableKey(id: string) {
  return keys.value.some((key) => key.id === id && key.status !== 'REVOKED')
}

function clearResetRetry() {
  if (resetRetryTimer) clearTimeout(resetRetryTimer)
  resetRetryTimer = null
  resetRetryProof = null
  resetRetryExpiresAt = 0
  resetRetryKeyId.value = null
}

function beginResetLifecycle(id: string) {
  resetTargetLifecycleEpoch += 1
  resetRequestGeneration += 1
  resetLifecycleTargetId = id
  clearResetRetry()
  return resetTargetLifecycleEpoch
}

function invalidateResetLifecycle() {
  resetTargetLifecycleEpoch += 1
  resetRequestGeneration += 1
  resetLifecycleTargetId = null
  clearResetRetry()
  resetVerificationOpen.value = false
  resetTargetId.value = null
  resetVerificationScope = null
  resettingId.value = null
}

function retainResetRetry(id: string, smsProof: string) {
  if (resetRetryKeyId.value === id && resetRetryProof === smsProof && Date.now() < resetRetryExpiresAt) return
  clearResetRetry()
  resetRetryProof = smsProof
  resetRetryExpiresAt = Date.now() + RESET_PROOF_RETRY_TTL_MS
  resetRetryKeyId.value = id
  resetRetryTimer = setTimeout(clearResetRetry, RESET_PROOF_RETRY_TTL_MS)
}

function beginReset(id: string) {
  if (!phoneBound.value || !smsRequirementKnown.value || resettingId.value || resetVerificationOpen.value) return
  const lifecycleEpoch = beginResetLifecycle(id)
  closeSecret()
  errorMessage.value = ''
  resetTargetId.value = id
  resetVerificationScope = { lifecycleEpoch, scope: captureUserScope(), targetId: id }
  resetVerificationOpen.value = true
}

function isCurrentResetRequest(request: {
  generation: number
  id: string
  lifecycleEpoch: number
  scope: ReturnType<typeof captureUserScope>
}) {
  return request.generation === resetRequestGeneration
    && request.lifecycleEpoch === resetTargetLifecycleEpoch
    && request.id === resetLifecycleTargetId
    && isCurrentUserScope(request.scope)
}

async function resetWithProof(
  id: string,
  smsProof: string,
  scope: ReturnType<typeof captureUserScope>,
  lifecycleEpoch: number,
) {
  if (!isCurrentUserScope(scope)
    || lifecycleEpoch !== resetTargetLifecycleEpoch
    || resetLifecycleTargetId !== id) return
  if (!hasResettableKey(id)) {
    invalidateResetLifecycle()
    return
  }
  if (resettingId.value) return
  const request = {
    generation: ++resetRequestGeneration,
    id,
    lifecycleEpoch,
    scope,
  }
  let keepLifecycleForRetry = false
  resettingId.value = id
  errorMessage.value = ''
  try {
    const rotated = await apiKeysStore.reset(id, smsProof)
    if (!isCurrentResetRequest(request)) return
    if (!hasResettableKey(id)) {
      invalidateResetLifecycle()
      closeSecret()
      return
    }
    clearResetRetry()
    apiKeysStore.applyResetPrefix(id, rotated.keyPrefix)
    createdSecret.value = rotated.secret
    secretOpen.value = true
  } catch (error) {
    if (isCurrentResetRequest(request)) {
      closeSecret()
      if (!hasResettableKey(id)) {
        invalidateResetLifecycle()
        return
      }
      if (resetResponseStatus(error) === 503) {
        retainResetRetry(id, smsProof)
        keepLifecycleForRetry = true
        errorMessage.value = '密钥重置服务暂时不可用，请直接重试。'
      } else {
        clearResetRetry()
        errorMessage.value = '密钥重置失败，请重新验证后重试。'
      }
    }
  } finally {
    if (isCurrentResetRequest(request)) {
      resettingId.value = null
      if (!keepLifecycleForRetry) resetLifecycleTargetId = null
    }
  }
}

function completeResetVerification(smsProof: string) {
  const id = resetTargetId.value
  const scope = resetVerificationScope
  resetVerificationOpen.value = false
  resetTargetId.value = null
  resetVerificationScope = null
  if (!id || !scope || scope.targetId !== id) return
  void resetWithProof(id, smsProof, scope.scope, scope.lifecycleEpoch)
}

function retryReset(id: string) {
  if (resetRetryKeyId.value !== id || !resetRetryProof || Date.now() >= resetRetryExpiresAt) {
    clearResetRetry()
    return
  }
  const smsProof = resetRetryProof
  void resetWithProof(id, smsProof, captureUserScope(), resetTargetLifecycleEpoch)
}

function clearResetState() {
  invalidateResetLifecycle()
  phoneBound.value = false
  smsRequirementKnown.value = false
}

function closeSecret() {
  secretOpen.value = false
  createdSecret.value = null
}

watch(
  () => keys.value.map((key) => `${key.id}:${key.status}`),
  () => {
    const id = resetLifecycleTargetId
    if (id && !hasResettableKey(id)) invalidateResetLifecycle()
  },
  { flush: 'sync' },
)

watch(
  () => apiKeysStore.userScopeGeneration,
  () => {
    identityAttempt += 1
    closeOwnedConfirmation()
    eligibleModels.value = []
    selectedModels.value = []
    keyName.value = ''
    createOpen.value = false
    clearResetState()
    closeSecret()
    submitting.value = false
    statusUpdatingId.value = null
    errorMessage.value = ''
  },
  { flush: 'sync' },
)

onMounted(load)
onBeforeUnmount(() => {
  identityAttempt += 1
  closeOwnedConfirmation()
  clearResetState()
  closeSecret()
})
</script>

<template>
  <SmsVerificationDialog
    v-model="resetVerificationOpen"
    purpose="API_KEY_RESET"
    @verified="completeResetVerification"
  />
  <section class="page-shell" aria-labelledby="keys-title">
    <header class="page-header">
      <div>
        <p class="page-eyebrow">访问凭证</p>
        <h2 id="keys-title">API 密钥</h2>
        <p>持久化列表仅展示前缀；完整密钥只在创建后显示一次。</p>
      </div>
      <el-button type="primary" data-test="create-key" :disabled="!eligibleModels.length" @click="openCreate">
        <Plus :size="16" aria-hidden="true" />创建密钥
      </el-button>
    </header>

    <div class="api-endpoint" aria-label="API Base URL">
      <div class="api-endpoint__content">
        <span>API Base URL</span>
        <code data-test="api-base-url">{{ apiBaseUrl }}</code>
      </div>
      <el-tooltip content="复制 API Base URL" placement="top">
        <button
          type="button"
          class="api-endpoint__copy"
          data-test="copy-api-base-url"
          aria-label="复制 API Base URL"
          @click="copyApiBaseUrl"
        >
          <Copy :size="17" aria-hidden="true" />
        </button>
      </el-tooltip>
    </div>

    <div class="security-note">
      <ShieldCheck :size="18" aria-hidden="true" />
      <span>关闭完整密钥对话框后无法再次查看，请立即保存到安全的凭证管理系统。</span>
    </div>

    <TopErrorMessage :message="errorMessage" />
    <el-skeleton v-if="loading" :rows="5" animated />
    <div v-else-if="!keys.length" class="empty-state">
      <KeyRound :size="28" aria-hidden="true" />
      <strong>暂无 API 密钥</strong>
      <span>{{ eligibleModels.length ? '创建密钥后即可调用已授权模型。' : '当前套餐没有可用于创建密钥的模型权限。' }}</span>
    </div>
    <div v-else class="key-list">
      <article v-for="key in keys" :key="key.id" class="key-row">
        <div class="key-icon" aria-hidden="true"><KeyRound :size="17" /></div>
        <div class="key-main">
          <div class="key-title">
            <strong>{{ key.name }}</strong>
            <el-tag :type="key.status === 'ACTIVE' ? 'success' : 'info'" size="small">{{ statusLabel(key.status) }}</el-tag>
          </div>
          <code>{{ key.keyPrefix }}••••••••</code>
          <div class="model-list">
            <span v-for="model in key.allowedModels" :key="model">{{ model }}</span>
          </div>
        </div>
        <dl>
          <div><dt>最近使用</dt><dd>{{ key.lastUsedAt ? formatDateTime(key.lastUsedAt) : '尚未使用' }}</dd></div>
          <div><dt>到期时间</dt><dd>{{ key.expiresAt ? formatDateTime(key.expiresAt) : '随套餐到期' }}</dd></div>
        </dl>
        <div v-if="key.status !== 'REVOKED'" class="key-actions">
          <el-tooltip v-if="resetRetryKeyId === key.id" content="重试重置 API 密钥" placement="top">
            <button
              type="button"
              class="reset-button"
              data-test="retry-reset-key"
              :disabled="confirmationPending || statusUpdatingId !== null || resettingId !== null"
              :aria-busy="resettingId === key.id"
              aria-label="重试重置 API 密钥"
              @click="retryReset(key.id)"
            >
              <LoaderCircle
                v-if="resettingId === key.id"
                :size="17"
                class="status-spinner"
                aria-hidden="true"
              />
              <RotateCcw v-else :size="17" aria-hidden="true" />
            </button>
          </el-tooltip>
          <el-tooltip v-else-if="phoneBound" content="重置 API 密钥" placement="top">
            <button
              type="button"
              class="reset-button"
              data-test="reset-key"
              :disabled="confirmationPending || statusUpdatingId !== null || resettingId !== null"
              :aria-busy="resettingId === key.id"
              aria-label="重置 API 密钥"
              @click="beginReset(key.id)"
            >
              <LoaderCircle
                v-if="resettingId === key.id"
                :size="17"
                class="status-spinner"
                aria-hidden="true"
              />
              <RotateCcw v-else :size="17" aria-hidden="true" />
            </button>
          </el-tooltip>
          <el-tooltip :content="key.status === 'ACTIVE' ? '停用 API 密钥' : '重新启用 API 密钥'" placement="top">
            <button
              type="button"
              class="status-button"
              :data-test="key.status === 'ACTIVE' ? 'disable-key' : 'enable-key'"
              :disabled="confirmationPending || statusUpdatingId !== null || resettingId !== null || resetVerificationOpen"
              :aria-busy="statusUpdatingId === key.id"
              :aria-label="key.status === 'ACTIVE' ? '停用 API 密钥' : '重新启用 API 密钥'"
              @click="changeStatus(key, key.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')"
            >
              <LoaderCircle
                v-if="statusUpdatingId === key.id"
                :size="17"
                class="status-spinner"
                data-test="status-spinner"
                aria-hidden="true"
              />
              <Pause v-else-if="key.status === 'ACTIVE'" :size="17" aria-hidden="true" />
              <Play v-else :size="17" aria-hidden="true" />
            </button>
          </el-tooltip>
          <el-tooltip content="撤销 API 密钥" placement="top">
            <button
              type="button"
              class="revoke-button"
              data-test="revoke-key"
              :disabled="confirmationPending || statusUpdatingId !== null || resettingId !== null || resetVerificationOpen"
              aria-label="撤销 API 密钥"
              @click="revoke(key)"
            >
              <Ban :size="17" aria-hidden="true" />
            </button>
          </el-tooltip>
        </div>
      </article>
    </div>

    <el-dialog v-model="createOpen" title="创建 API 密钥" width="min(480px, calc(100vw - 24px))" :teleported="false">
      <form class="dialog-form" @submit.prevent="submitCreate">
        <label for="key-name">密钥名称</label>
        <el-input id="key-name" v-model="keyName" data-test="key-name" name="key-name" autocomplete="off" placeholder="例如：生产调用" />
        <fieldset>
          <legend>可调用模型</legend>
          <el-checkbox-group v-model="selectedModels">
            <el-checkbox v-for="model in eligibleModels" :key="model" :label="model" :value="model" />
          </el-checkbox-group>
        </fieldset>
      </form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" data-test="submit-key" :disabled="!canSubmit" :loading="submitting" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="secretOpen"
      title="立即保存 API 密钥"
      width="min(520px, calc(100vw - 24px))"
      :teleported="false"
      :show-close="false"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
    >
      <p class="secret-warning">此完整密钥关闭后不会再次显示。</p>
      <div v-if="createdSecret" class="secret-value">
        <code>{{ createdSecret }}</code>
        <button type="button" aria-label="复制 API 密钥" @click="copySecret"><Copy :size="16" aria-hidden="true" /></button>
      </div>
      <template #footer>
        <el-button type="primary" data-test="close-secret" @click="closeSecret">我已安全保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.page-shell { width: 100%; }
.page-header { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.page-eyebrow { margin: 0 0 4px; color: var(--app-primary); font-size: 12px; font-weight: 700; }
.page-header h2 { margin: 0; font-size: 22px; }
.page-header p:last-child { margin: 5px 0 0; color: var(--app-muted); }
.api-endpoint { display: flex; min-width: 0; align-items: center; gap: 12px; margin-bottom: 12px; padding: 11px 14px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 7px; }
.api-endpoint__content { display: grid; min-width: 0; flex: 1; grid-template-columns: auto minmax(0, 1fr); align-items: center; gap: 12px; }
.api-endpoint__content span { color: var(--app-muted); font-size: 12px; font-weight: 700; }
.api-endpoint__content code { min-width: 0; overflow-wrap: anywhere; color: var(--app-fg); }
.api-endpoint__copy { display: inline-flex; width: 44px; height: 44px; flex: 0 0 44px; align-items: center; justify-content: center; color: var(--app-primary); background: transparent; border: 1px solid transparent; border-radius: 7px; cursor: pointer; transition: color var(--app-motion-fast), background-color var(--app-motion-fast), border-color var(--app-motion-fast); }
.api-endpoint__copy:hover { color: var(--app-primary-hover); background: var(--app-primary-soft); border-color: var(--app-border-strong); }
.security-note { display: flex; align-items: center; gap: 9px; margin-bottom: 16px; padding: 11px 14px; color: var(--app-success); background: var(--app-success-soft); border: 1px solid var(--app-success-border); border-radius: 7px; }
.empty-state { display: flex; min-height: 220px; align-items: center; justify-content: center; flex-direction: column; gap: 8px; color: var(--app-muted); background: var(--app-surface); border: 1px solid var(--app-border); border-radius: var(--app-radius); }
.empty-state strong { color: var(--app-fg); }
.key-list { display: flex; flex-direction: column; gap: 10px; }
.key-row { display: flex; min-width: 0; align-items: center; gap: 14px; padding: 16px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: var(--app-radius); }
.key-icon { display: inline-flex; width: 36px; height: 36px; flex: 0 0 36px; align-items: center; justify-content: center; color: var(--app-primary); background: var(--app-primary-soft); border-radius: 7px; }
.key-main { min-width: 0; flex: 1; }
.key-title { display: flex; align-items: center; gap: 8px; margin-bottom: 5px; }
.key-main code { overflow-wrap: anywhere; }
.model-list { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 8px; }
.model-list span { padding: 2px 6px; color: var(--app-muted-strong); background: var(--app-surface-muted); border-radius: 4px; font-size: 11px; }
.key-row dl { margin: 0; flex: 0 0 auto; }
.key-row dl div { display: grid; grid-template-columns: 72px minmax(120px, 1fr); gap: 8px; margin-top: 5px; }
.key-row dt { color: var(--app-muted); }
.key-row dd { margin: 0; overflow-wrap: anywhere; text-align: right; }
.key-actions { display: flex; flex: 0 0 auto; align-items: center; gap: 8px; }
.reset-button, .status-button, .revoke-button { display: inline-flex; width: 44px; height: 44px; flex: 0 0 44px; align-items: center; justify-content: center; background: transparent; border: 1px solid transparent; border-radius: 7px; cursor: pointer; }
.reset-button { color: var(--app-primary); }
.reset-button:hover { background: var(--app-primary-soft); border-color: var(--app-primary-border); }
.status-button { color: var(--app-warning); }
.status-button:hover { background: var(--app-warning-soft); border-color: var(--app-warning-border); }
.revoke-button:hover { background: var(--app-danger-soft); border-color: var(--app-danger-soft); }
.revoke-button { color: var(--app-danger); }
.reset-button:disabled, .status-button:disabled, .revoke-button:disabled { opacity: .45; cursor: not-allowed; }
.status-spinner { animation: status-spin .8s linear infinite; }
@keyframes status-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .status-spinner { animation: none; } }
.dialog-form { display: flex; flex-direction: column; gap: 9px; }
.dialog-form label, .dialog-form legend { font-weight: 650; }
.dialog-form fieldset { margin: 8px 0 0; padding: 12px; border: 1px solid var(--app-border); border-radius: 7px; }
.secret-warning { color: var(--app-warning); }
.secret-value { display: flex; align-items: center; gap: 8px; padding: 12px; background: #f8fafc; border: 1px solid var(--app-border); border-radius: 7px; }
.secret-value code { min-width: 0; flex: 1; overflow-wrap: anywhere; }
.secret-value button { display: inline-flex; width: 44px; height: 44px; flex: 0 0 44px; align-items: center; justify-content: center; color: var(--app-primary); background: #fff; border: 1px solid var(--app-border); border-radius: 7px; cursor: pointer; }
@media (max-width: 720px) {
  .page-header, .key-row { align-items: stretch; flex-direction: column; }
  .api-endpoint__content { grid-template-columns: 1fr; gap: 3px; }
  .key-row dl { width: 100%; padding-top: 10px; border-top: 1px solid var(--app-border); }
}
</style>
