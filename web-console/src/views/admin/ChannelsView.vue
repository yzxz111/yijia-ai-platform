<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Cable, Copy, Plus, RefreshCw, Search, TestTube2 } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'

import {
  createChannel,
  createProvider,
  listChannels,
  listProviders,
  testChannel,
  updateChannel,
  type AdminChannel,
  type AdminListParams,
  type AdminProvider,
} from '../../api/admin'
import { adminDrillQuery } from '../../router/query'

const props = withDefaults(defineProps<{
  embedded?: boolean
}>(), {
  embedded: false,
})

const route = useRoute()
const initialDrill = adminDrillQuery(route.query, [], '')
const channels = ref<AdminChannel[]>([])
const providers = ref<AdminProvider[]>([])
const total = ref(0)
const loading = ref(true)
const saving = ref(false)
const testingId = ref('')
const drawerOpen = ref(false)
const providerDialogOpen = ref(false)
const providerSaving = ref(false)
const errorMessage = ref('')
const pageSizeOptions = [5, 10, 20]
const params = reactive<AdminListParams>({
  page: 0, size: 20, query: initialDrill.provider || initialDrill.search, status: '', sort: 'priority', direction: 'desc',
})
const form = reactive({
  id: '', providerId: '', code: '', name: '', environment: 'PRODUCTION', baseUrl: '', apiKey: '',
  enabled: true, priority: 100, weight: 100, timeoutMs: 60_000, requestsPerMinute: 300, maxConcurrency: 50,
})
const providerForm = reactive({
  code: '', name: '', providerType: 'DEEPSEEK', baseUrl: '', enabled: true,
})
async function loadChannels(targetPage = params.page) {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listChannels({ ...params, page: targetPage })
    channels.value = result.items
    total.value = result.total
    params.page = result.page
  } catch { errorMessage.value = '渠道列表加载失败，请稍后重试。' } finally { loading.value = false }
}

function changePageSize(size: number) {
  params.size = size
  void loadChannels(0)
}

function clearFilters() {
  Object.assign(params, { query: '', status: '', sort: 'priority', direction: 'desc' })
  void loadChannels(0)
}

async function loadProviders() {
  try {
    providers.value = (await listProviders()).filter((provider) => provider.enabled)
  } catch {
    errorMessage.value = '服务商列表加载失败，请稍后重试。'
  }
}

function load() {
  return Promise.all([loadChannels(), loadProviders()])
}

function openCreate() {
  const provider = providers.value[0]
  Object.assign(form, { id: '', providerId: provider?.id || '', code: '', name: '', environment: 'PRODUCTION', baseUrl: provider?.baseUrl || '', apiKey: '', enabled: true, priority: 100, weight: 100, timeoutMs: 60_000, requestsPerMinute: 300, maxConcurrency: 50 })
  drawerOpen.value = true
}

function openEdit(channel: AdminChannel) {
  Object.assign(form, { id: channel.id, providerId: channel.providerId, code: channel.code, name: channel.name, environment: channel.environment, baseUrl: channel.baseUrl, apiKey: '', enabled: channel.enabled, priority: channel.priority, weight: channel.weight, timeoutMs: channel.timeoutMs, requestsPerMinute: channel.requestsPerMinute, maxConcurrency: channel.maxConcurrency })
  drawerOpen.value = true
}

async function save() {
  if (!form.name.trim() || !form.baseUrl.trim() || (!form.id && (!form.code.trim() || !form.providerId))) {
    errorMessage.value = '请填写渠道名称、地址、代码和服务商。'
    return
  }
  saving.value = true
  errorMessage.value = ''
  try {
    let channelId = form.id
    if (form.id) {
      await updateChannel({ id: form.id, name: form.name.trim(), baseUrl: form.baseUrl.trim(), apiKey: form.apiKey || undefined, enabled: form.enabled, priority: form.priority, weight: form.weight, timeoutMs: form.timeoutMs, requestsPerMinute: form.requestsPerMinute, maxConcurrency: form.maxConcurrency })
    } else {
      const created = await createChannel({ providerId: form.providerId, code: form.code.trim(), name: form.name.trim(), environment: form.environment, baseUrl: form.baseUrl.trim(), apiKey: form.apiKey, enabled: form.enabled, priority: form.priority, weight: form.weight, timeoutMs: form.timeoutMs, requestsPerMinute: form.requestsPerMinute, maxConcurrency: form.maxConcurrency })
      channelId = created.id
    }
    let probeError = ''
    if (form.enabled && channelId) {
      try {
        const result = await testChannel(channelId)
        if (result.status !== 'HEALTHY') probeError = `渠道已保存，但连接测试状态为${healthLabel(result.status)}`
      } catch {
        probeError = '渠道已保存，但连接测试失败，请检查地址和密钥。'
      }
    }
    drawerOpen.value = false
    form.apiKey = ''
    await loadChannels()
    if (probeError) errorMessage.value = probeError
  } catch { errorMessage.value = '渠道保存失败，请检查地址、密钥和限流参数。' } finally { saving.value = false }
}

async function saveProvider() {
  if (!providerForm.code.trim() || !providerForm.name.trim() || !providerForm.baseUrl.trim()) {
    errorMessage.value = '请填写服务商代码、名称和默认地址。'
    return
  }
  providerSaving.value = true
  errorMessage.value = ''
  try {
    const created = await createProvider({
      code: providerForm.code.trim(), name: providerForm.name.trim(),
      providerType: providerForm.providerType, baseUrl: providerForm.baseUrl.trim(),
      enabled: providerForm.enabled,
    })
    providers.value = [...providers.value, created].sort((left, right) => left.code.localeCompare(right.code))
    form.providerId = created.id
    providerDialogOpen.value = false
    Object.assign(providerForm, { code: '', name: '', providerType: 'DEEPSEEK', baseUrl: '', enabled: true })
    ElMessage.success('服务商已创建')
  } catch {
    errorMessage.value = '服务商创建失败，请检查代码是否重复以及地址是否有效。'
  } finally {
    providerSaving.value = false
  }
}

function providerLabel(providerId: string) {
  return providers.value.find((provider) => provider.id === providerId)?.name || '未知服务商'
}

function environmentLabel(value: string) {
  return ({ PRODUCTION: '生产', SANDBOX: '沙箱', LOCAL: '本地' } as Record<string, string>)[value] || value
}

function healthLabel(value: string) {
  return ({ HEALTHY: '健康', UP: '正常', DEGRADED: '性能下降', UNHEALTHY: '异常', DOWN: '不可用', UNKNOWN: '未检测' } as Record<string, string>)[value] || value
}

function channelTestUrl(id: string) {
  const origin = typeof window !== 'undefined' ? window.location.origin : ''
  return `${origin}/console/gateway/admin/channels/${encodeURIComponent(id)}/test`
}

async function copyTestUrl() {
  if (!form.id) return
  const value = channelTestUrl(form.id)
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value)
    } else {
      const field = document.createElement('textarea')
      field.value = value
      field.setAttribute('readonly', '')
      field.style.position = 'fixed'
      field.style.opacity = '0'
      document.body.appendChild(field)
      field.focus()
      field.select()
      document.execCommand('copy')
      field.remove()
    }
    ElMessage.success('测试链接已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制测试链接')
  }
}

async function probe(channel: AdminChannel) {
  testingId.value = channel.id
  try { const result = await testChannel(channel.id); ElMessage.success(`连接测试：${healthLabel(result.status)}`); await loadChannels() } catch { ElMessage.error('渠道连接测试失败。') } finally { testingId.value = '' }
}

onMounted(load)
watch(() => route.query, (query) => {
  const drill = adminDrillQuery(query, [], '')
  params.query = drill.provider || drill.search
  void loadChannels(0)
}, { deep: true })
</script>

<template>
  <section class="admin-page" :class="{ 'channels-embedded': props.embedded }" aria-labelledby="admin-channels-title">
    <header class="admin-page__header channels-header"><div><component :is="props.embedded ? 'h3' : 'h2'" id="admin-channels-title">{{ props.embedded ? '上游渠道' : '渠道与路由' }}</component><p>管理服务商凭据、优先级、权重、超时和容量限制</p></div><div class="admin-actions"><el-button :loading="loading" @click="load"><RefreshCw :size="16" />刷新</el-button><el-button @click="providerDialogOpen = true"><Plus :size="16" />新增服务商</el-button><el-button data-test="channel-create" type="primary" :disabled="!providers.length" @click="openCreate"><Plus :size="16" />新增渠道</el-button></div></header>
    <TopErrorMessage :message="errorMessage" />
    <div class="admin-panel">
      <div class="admin-toolbar channels-toolbar">
        <el-input v-model="params.query" data-test="channels-query" aria-label="搜索渠道" clearable placeholder="搜索渠道或服务商" @keyup.enter="loadChannels(0)" />
        <el-select v-model="params.status" data-test="channels-status" aria-label="渠道状态" clearable placeholder="全部状态"><el-option label="启用" value="ENABLED" /><el-option label="停用" value="DISABLED" /></el-select>
        <el-select v-model="params.sort" data-test="channels-sort" aria-label="渠道排序字段"><el-option label="优先级" value="priority" /><el-option label="代码" value="code" /><el-option label="名称" value="name" /><el-option label="服务商" value="provider" /><el-option label="环境" value="environment" /><el-option label="权重" value="weight" /><el-option label="超时" value="timeoutMs" /><el-option label="RPM" value="requestsPerMinute" /><el-option label="并发" value="maxConcurrency" /><el-option label="启用状态" value="enabled" /><el-option label="健康检查时间" value="lastHealthCheckedAt" /></el-select>
        <el-select v-model="params.direction" data-test="channels-direction" aria-label="渠道排序方向"><el-option label="降序" value="desc" /><el-option label="升序" value="asc" /></el-select>
        <el-button @click="clearFilters">清空</el-button>
        <el-button data-test="channels-filter" type="primary" :loading="loading" @click="loadChannels(0)"><Search :size="16" />查询</el-button>
      </div>
      <el-skeleton v-if="loading" :rows="6" animated /><div v-else class="table-scroll"><table class="admin-table"><thead><tr><th>渠道</th><th>服务商</th><th>环境</th><th>状态</th><th>健康</th><th>优先级</th><th>权重</th><th>超时</th><th>RPM</th><th>并发</th><th>凭据</th><th>操作</th></tr></thead><tbody>
      <tr v-for="channel in channels" :key="channel.id"><td><strong>{{ channel.name }}</strong><br><code>{{ channel.code }}</code></td><td>{{ providerLabel(channel.providerId) }}</td><td>{{ environmentLabel(channel.environment) }}</td><td><el-tag size="small" :type="channel.enabled ? 'success' : 'info'">{{ channel.enabled ? '启用' : '停用' }}</el-tag></td><td><el-tag size="small" :type="channel.lastHealthStatus === 'HEALTHY' || channel.lastHealthStatus === 'UP' ? 'success' : channel.lastHealthStatus === 'UNHEALTHY' || channel.lastHealthStatus === 'DOWN' ? 'danger' : 'info'">{{ healthLabel(channel.lastHealthStatus) }}</el-tag></td><td>{{ channel.priority }}</td><td>{{ channel.weight }}</td><td>{{ channel.timeoutMs }} ms</td><td>{{ channel.requestsPerMinute }}</td><td>{{ channel.maxConcurrency }}</td><td>{{ channel.credentialConfigured ? '已配置' : '未配置' }}</td><td><div class="admin-actions"><el-button size="small" @click="openEdit(channel)">编辑</el-button><el-tooltip placement="top"><template #content><code>{{ channelTestUrl(channel.id) }}</code></template><el-button size="small" :loading="testingId === channel.id" aria-label="测试渠道连接" @click="probe(channel)"><TestTube2 :size="15" /></el-button></el-tooltip></div></td></tr>
      <tr v-if="!channels.length"><td colspan="12" class="admin-empty"><Cable :size="18" /> 暂无渠道</td></tr>
    </tbody></table></div><div class="admin-pagination"><span>共 {{ total }} 条</span><el-pagination data-test="channels-pagination" size="small" background layout="sizes, prev, pager, next" :current-page="params.page + 1" :page-size="params.size" :page-sizes="pageSizeOptions" :total="total" @size-change="changePageSize" @current-change="(value: number) => loadChannels(value - 1)" /></div></div>
    <el-drawer v-model="drawerOpen" :title="form.id ? '编辑渠道' : '新增渠道'" size="min(520px, 100%)" @closed="form.apiKey = ''">
      <div class="admin-form-grid">
        <el-form-item label="服务商"><el-select v-model="form.providerId" data-test="channel-provider" :disabled="Boolean(form.id)"><el-option v-for="provider in providers" :key="provider.id" :label="`${provider.name} · ${provider.code}`" :value="provider.id" /></el-select></el-form-item>
        <el-form-item label="环境"><el-select v-model="form.environment" :disabled="Boolean(form.id)"><el-option label="生产" value="PRODUCTION" /><el-option label="本地" value="LOCAL" /></el-select></el-form-item>
        <el-form-item label="代码"><el-input v-model="form.code" :disabled="Boolean(form.id)" /></el-form-item><el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item class="is-full" label="接口地址"><el-input v-model="form.baseUrl" /></el-form-item><el-form-item class="is-full" label="API 密钥"><el-input v-model="form.apiKey" type="password" show-password autocomplete="new-password" :placeholder="form.id ? '留空则保持现有密钥' : '输入渠道密钥'" /></el-form-item>
        <el-form-item class="is-full" label="测试接口 URL"><div v-if="form.id" class="channel-test-url"><code>{{ channelTestUrl(form.id) }}</code><el-button text circle title="复制测试链接" aria-label="复制测试链接" @click="copyTestUrl"><Copy :size="16" aria-hidden="true" /></el-button></div><span v-else class="channel-test-url-empty">保存渠道后生成测试链接</span><small class="channel-field-help">POST 请求，需管理员登录后调用。</small></el-form-item>
        <el-form-item label="优先级"><el-input-number v-model="form.priority" :min="0" :max="10000" /></el-form-item><el-form-item label="权重"><el-input-number v-model="form.weight" :min="1" :max="10000" /></el-form-item>
        <el-form-item label="超时（毫秒）"><el-input-number v-model="form.timeoutMs" :min="1000" :max="300000" /></el-form-item><el-form-item label="RPM"><el-input-number v-model="form.requestsPerMinute" :min="1" /></el-form-item>
        <el-form-item label="最大并发"><el-input-number v-model="form.maxConcurrency" :min="1" /></el-form-item><el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </div>
      <template #footer><el-button @click="drawerOpen = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-drawer>
    <el-dialog v-model="providerDialogOpen" title="新增服务商" width="min(520px, 92vw)">
      <div class="admin-form-grid provider-form">
        <el-form-item label="服务商名称"><el-input v-model="providerForm.name" placeholder="例如 百炼" /></el-form-item>
        <el-form-item label="服务商代码"><el-input v-model="providerForm.code" placeholder="例如 BAILIAN" /></el-form-item>
        <el-form-item label="接口协议"><el-select v-model="providerForm.providerType"><el-option label="OpenAI 兼容协议" value="DEEPSEEK" /><el-option label="百炼协议" value="BAILIAN" /><el-option label="模拟服务" value="MOCK" /></el-select></el-form-item>
        <el-form-item label="启用"><el-switch v-model="providerForm.enabled" /></el-form-item>
        <el-form-item class="is-full" label="默认地址"><el-input v-model="providerForm.baseUrl" placeholder="https://..." /></el-form-item>
      </div>
      <template #footer><el-button @click="providerDialogOpen = false">取消</el-button><el-button type="primary" :loading="providerSaving" @click="saveProvider">创建</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
:deep(.el-drawer) { --channel-drawer-inset: 22px; }
:deep(.el-drawer__header), :deep(.el-drawer__body) { padding-left: var(--channel-drawer-inset); padding-right: var(--channel-drawer-inset); }
:deep(.el-drawer__header) { margin-bottom: 0; padding-bottom: 14px; border-bottom: 1px solid var(--app-border); }
:deep(.el-drawer__body) { padding-top: 16px; }
.admin-form-grid :deep(.el-form-item) { display: block; margin-bottom: 4px; }
.admin-form-grid :deep(.el-form-item__label) { display: block; height: auto; margin-bottom: 5px; color: #334155; font-weight: 600; }
.admin-form-grid .el-select { width: 100%; }
.channels-toolbar { padding: 12px 14px 0; }
.channel-test-url { display: flex; min-width: 0; align-items: center; gap: 6px; }
.channel-test-url code { min-width: 0; flex: 1; overflow-wrap: anywhere; color: var(--app-fg); line-height: 1.5; }
.channel-test-url .el-button { flex: 0 0 32px; }
.channel-test-url-empty, .channel-field-help { color: var(--app-muted); }
.channel-field-help { display: block; margin-top: 5px; line-height: 1.45; }
.channels-embedded { gap: 14px; }
.channels-embedded .channels-header h3 { margin: 0; color: var(--app-fg); font-size: 17px; line-height: 1.4; }
</style>
