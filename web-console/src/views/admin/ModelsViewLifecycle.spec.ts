import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import ModelsView from './ModelsView.vue'
import * as admin from '../../api/admin'
import type { AdminChannelModelTest, AdminModel, AdminModelRoute, AdminProvider } from '../../api/admin'

vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRoute: () => ({ query: {} }),
}))

// 与 admin-workflows.spec.ts 相同的整模块 mock 方式：保留真实类型，只替换本页读写的接口。
vi.mock('../../api/admin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/admin')>()),
  listModels: vi.fn(),
  listProviders: vi.fn(),
  // 页面的「测试模型」走真实调用；旧的只探可达性的接口不再由本页使用。
  testChannelModel: vi.fn(),
  testChannel: vi.fn(),
  // 挂载时会取一次服务商类型能力目录；没 mock 就会真的发请求，用例结果取决于网络。
  listProviderTypeCapabilities: vi.fn(),
  probeUpstreamModels: vi.fn(),
  onboardChannelRoutes: vi.fn(),
}))

const api = vi.mocked(admin)

const source = (path: string) => readFile(resolve(process.cwd(), path), 'utf8')

const provider: AdminProvider = {
  id: 'provider-bailian',
  code: 'bailian',
  name: '阿里云百炼',
  providerType: 'BAILIAN',
  baseUrl: 'https://example.invalid/v1',
  enabled: true,
}

function modelRoute(overrides: Partial<AdminModelRoute>): AdminModelRoute {
  return {
    componentModelCode: 'qwen-plus', componentDisplayName: 'Qwen Plus',
    providerId: 'provider-bailian', providerCode: 'bailian', providerName: '阿里云百炼',
    channelId: 'channel-degraded', channelCode: 'bailian-degraded', channelName: '百炼降级渠道',
    upstreamModel: 'qwen-plus', environment: 'PRODUCTION', baseUrl: 'https://example.invalid/v1',
    enabled: true, healthStatus: 'UNHEALTHY', credentialConfigured: true,
    priority: 80, weight: 50, timeoutMs: 60_000, requestsPerMinute: 300, maxConcurrency: 50,
    eligible: false,
    ...overrides,
  }
}

const models: AdminModel[] = [
  {
    id: 'model-1', code: 'qwen-plus', displayName: 'Qwen Plus', aliases: ['qwen-plus'],
    capabilities: ['TEXT'], routingStrategy: 'DIRECT', contextWindow: 32768, maxOutputTokens: 4096,
    premium: false, enabled: true, requestsPerMinute: 60, maxConcurrency: 5,
    routes: [
      modelRoute({}),
      modelRoute({
        channelId: 'channel-bailian', channelCode: 'bailian-primary', channelName: '百炼主渠道',
        healthStatus: 'HEALTHY', priority: 100, weight: 100, eligible: true,
      }),
    ],
  },
  {
    id: 'model-2', code: 'text-basic', displayName: '文本基础版', aliases: ['text-basic'],
    capabilities: ['TEXT'], routingStrategy: 'DIRECT', contextWindow: 32768, maxOutputTokens: 4096,
    premium: false, enabled: true, requestsPerMinute: 60, maxConcurrency: 5,
    routes: [],
  },
]

function mountModelsView() {
  return mount(ModelsView, { global: { plugins: [ElementPlus] } })
}

/**
 * 网关侧一次真实模型调用的应答。
 *
 * `ok` 按网关的契约推导（`modelInvoked && modelOk`），而不是写死 true：
 * 只探可达性（modelInvoked=false）时网关给的 ok 就是 false，写死会让这条
 * 「地址通≠模型可用」的假阳性在测试里被掩盖掉。
 */
function modelTest(overrides: Partial<AdminChannelModelTest> = {}): AdminChannelModelTest {
  const base = {
    channelId: 'channel-bailian',
    providerType: 'BAILIAN',
    credentialConfigured: true,
    reachable: true,
    modelInvoked: true,
    modelOk: true,
    status: 'HEALTHY',
    modelCode: 'qwen-plus',
    upstreamModel: 'qwen-plus',
    reason: null,
    httpStatus: 200,
    latencyMs: 42,
    inputTokens: 7,
    outputTokens: 1,
    healthPersisted: true,
    upstreamMessage: null,
    models: [],
  }
  const merged = { ...base, ...overrides }
  return { ...merged, ok: merged.modelInvoked && merged.modelOk }
}

describe('model lifecycle management contracts', () => {
  it('exposes dedicated status and deletion API operations', async () => {
    const api = await source('src/api/admin.ts')

    expect(api).toContain('setModelEnabled')
    expect(api).toContain('/status`')
    expect(api).toContain('export async function deleteModel')
    expect(api).toContain('gatewayHttp.delete(`/console/gateway/admin/models/')
  })

  it('defaults to enabled models and keeps the disabled filter', async () => {
    const view = await source('src/views/admin/ModelsView.vue')

    expect(view).toContain(": 'ENABLED'")
    expect(view).toContain('label="正常" value="ENABLED"')
    expect(view).toContain('label="已停用" value="DISABLED"')
  })

  it('provides enable, edit, and guarded delete controls with real error reasons', async () => {
    const view = await source('src/views/admin/ModelsView.vue')

    expect(view).toContain('model-status-')
    expect(view).toContain('model-edit-')
    expect(view).toContain('model-delete-')
    expect(view).toContain('model_referenced_by_package')
    expect(view).toContain('model_referenced_by_routing')
    expect(view).toContain('model_has_usage_history')
    expect(view).toContain('package_reference_check_unavailable')
  })

  it('tests the model for real instead of only probing reachability', async () => {
    const view = await source('src/views/admin/ModelsView.vue')
    const api = await source('src/api/admin.ts')

    expect(view).toContain('model-test-')
    expect(view).toContain('testChannelModel')
    expect(api).toContain('export async function testChannelModel')
    expect(api).toContain('/channels/${encodeURIComponent(id)}/model-test')
    // 「测试模型」这个动作必须是真实调用（会向上游发一次最小请求并计费），不能退化成
    // 只探可达性的 testChannel。这里只钉住 testModelChannel 的函数体：页面别处出现
    // testChannel 是另一回事——保存模型配置后会对刚保存的渠道自动探一次活
    // （probeSavedChannels），那正是为了避免"密钥填好了、模型却依然不可用"。
    const tested = view.indexOf('async function testModelChannel')
    expect(tested).toBeGreaterThan(-1)
    const testModelChannelBody = view.slice(tested, view.indexOf('async function', tested + 10))
    expect(testModelChannelBody).toContain('testChannelModel(')
    expect(testModelChannelBody).not.toContain('testChannel(')
  })
})

describe('model channel model test action', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listProviders.mockResolvedValue([provider])
    api.listProviderTypeCapabilities.mockResolvedValue([])
    api.listModels.mockResolvedValue({ items: models, total: models.length, page: 0, size: 20 })
    api.testChannelModel.mockResolvedValue(modelTest())
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders a model test control for a model bound to an upstream channel', async () => {
    const wrapper = mountModelsView()
    await flushPromises()

    const button = wrapper.get('[data-test="model-test-model-1"]')
    expect(button.attributes('aria-label')).toBe('测试模型')
    expect(wrapper.find('[data-test="model-test-model-2"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('calls the upstream for real with the row upstream model and reloads the list', async () => {
    const success = vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    const wrapper = mountModelsView()
    await flushPromises()
    api.listModels.mockClear()

    await wrapper.get('[data-test="model-test-model-1"]').trigger('click')
    await flushPromises()

    // 用 eligible 的那条路由，并且明确测这一行映射的上游模型名。
    expect(api.testChannelModel).toHaveBeenCalledWith('channel-bailian', { upstreamModel: 'qwen-plus' })
    // 只测了一条路由：降级渠道（同模型上的另一条路由）不该被顺带测一遍。
    expect(api.testChannelModel).toHaveBeenCalledTimes(1)
    expect(api.testChannelModel.mock.calls.map(([id]) => id)).not.toContain('channel-degraded')
    expect(success).toHaveBeenCalledWith('模型可用：qwen-plus，耗时 42 ms')
    // 测试后重新拉取列表，让新的健康状态（eligible）立刻反映到「状态」列。
    expect(api.listModels).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it('warns instead of calling the API when the model has no channel bound', async () => {
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never)
    const wrapper = mountModelsView()
    await flushPromises()
    api.listModels.mockClear()

    await wrapper.get('[data-test="model-test-model-2"]').trigger('click')
    await flushPromises()

    expect(api.testChannelModel).not.toHaveBeenCalled()
    expect(warning).toHaveBeenCalledWith('该模型还没有绑定上游渠道，无法测试模型。')
    expect(api.listModels).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('explains a rejected key with the actionable reason and the upstream message', async () => {
    api.testChannelModel.mockResolvedValue(modelTest({
      modelOk: false, status: 'UNHEALTHY', reason: 'credential_rejected', httpStatus: 401,
      upstreamMessage: 'provider_http_401',
    }))
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    const wrapper = mountModelsView()
    await flushPromises()

    await wrapper.get('[data-test="model-test-model-1"]').trigger('click')
    await flushPromises()

    expect(error).toHaveBeenCalledWith(
      '密钥被上游拒绝（qwen-plus）：请更换 API 密钥后重测。 上游原文：provider_http_401')
    expect(wrapper.get('[data-test="model-test-model-1"]').classes()).not.toContain('is-loading')

    wrapper.unmount()
  })

  it('warns (not errors) when nothing was actually invoked', async () => {
    api.testChannelModel.mockResolvedValue(modelTest({
      modelInvoked: false, modelOk: false, status: 'UNKNOWN', reason: 'model_mapping_missing',
      modelCode: null, upstreamModel: null, httpStatus: null, latencyMs: null,
      inputTokens: null, outputTokens: null, healthPersisted: false,
    }))
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never)
    const wrapper = mountModelsView()
    await flushPromises()

    await wrapper.get('[data-test="model-test-model-1"]').trigger('click')
    await flushPromises()

    expect(warning).toHaveBeenCalledWith(
      '这个上游模型没有挂在当前渠道上：请核对「上游真实模型」或改用批量接入。')

    wrapper.unmount()
  })

  it('flags a probe that could not be written back to the channel health', async () => {
    api.testChannelModel.mockResolvedValue(modelTest({ healthPersisted: false }))
    const success = vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    const wrapper = mountModelsView()
    await flushPromises()

    await wrapper.get('[data-test="model-test-model-1"]').trigger('click')
    await flushPromises()

    expect(success).toHaveBeenCalledWith(
      '模型可用：qwen-plus，耗时 42 ms（健康状态未写回：配置已改动，请重测）')

    wrapper.unmount()
  })

  it('warns instead of blaming the model name when the alias is not a chat model', async () => {
    // 音频/视频/生图别名挂在同一个服务商类型下，但不在 /chat/completions 上：
    // 网关不发请求，于是这里必须是警告，而不是一条「上游没有这个模型」的错误。
    api.testChannelModel.mockResolvedValue(modelTest({
      modelInvoked: false, modelOk: false, status: 'UNKNOWN', reason: 'alias_not_chat_capable',
      modelCode: 'audio-asr-basic', upstreamModel: 'qwen-audio-3.0-asr-flash',
      httpStatus: null, latencyMs: null, inputTokens: null, outputTokens: null,
      healthPersisted: false, upstreamMessage: null,
    }))
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never)
    const wrapper = mountModelsView()
    await flushPromises()

    await wrapper.get('[data-test="model-test-model-1"]').trigger('click')
    await flushPromises()

    expect(warning).toHaveBeenCalledWith(
      '这个别名不是对话模型（qwen-audio-3.0-asr-flash）：「测试模型」向上游发的是 /chat/completions，而音频、视频、生图模型不在那个端点上——模型名和密钥都可能是对的。请改用该能力的接口验证；渠道健康态不会被这次测试改动。')

    wrapper.unmount()
  })

  it('tells an unexplained 404 apart from a missing model', async () => {
    // 同一个 404，两种处置：上游原文没提模型时，要指向地址而不是模型名。
    api.testChannelModel.mockResolvedValue(modelTest({
      modelOk: false, status: 'UNHEALTHY', reason: 'upstream_endpoint_not_found',
      httpStatus: 404, upstreamMessage: 'url path not found',
    }))
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    const wrapper = mountModelsView()
    await flushPromises()

    await wrapper.get('[data-test="model-test-model-1"]').trigger('click')
    await flushPromises()

    expect(error).toHaveBeenCalledWith(
      '上游对这个地址回了 404（qwen-plus）：上游原文里没有提到模型，更可能是接口地址不对（例如少了一段 /v1 这样的版本段），而不是模型名不对。请核对「接口地址」，并对照上游原文确认。 上游原文：url path not found')

    wrapper.unmount()
  })

  it('surfaces a failing test request as an error and clears the pending state', async () => {
    api.testChannelModel.mockRejectedValue(new Error('gateway down'))
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    const wrapper = mountModelsView()
    await flushPromises()

    await wrapper.get('[data-test="model-test-model-1"]').trigger('click')
    await flushPromises()

    expect(error).toHaveBeenCalledWith('模型测试请求失败，请检查网关连接后重试。')
    expect(wrapper.get('[data-test="model-test-model-1"]').classes()).not.toContain('is-loading')

    wrapper.unmount()
  })
})
