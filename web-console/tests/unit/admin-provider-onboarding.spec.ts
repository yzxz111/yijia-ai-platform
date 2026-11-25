import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import ModelsView from '../../src/views/admin/ModelsView.vue'
import type {
  AdminChannel,
  AdminModel,
  AdminModelRoute,
  AdminProvider,
  AdminProviderTypeCapabilities,
} from '../../src/api/admin'

vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRoute: () => ({ query: {} }),
}))

// 与 admin-workflows.spec.ts 相同的整模块 mock 方式：保留真实类型，只替换本页读写的接口。
const api = vi.hoisted(() => ({
  listChannels: vi.fn(),
  listModels: vi.fn(),
  listProviderTypeCapabilities: vi.fn(),
  listProviders: vi.fn(),
  onboardChannelRoutes: vi.fn(),
  probeUpstreamModels: vi.fn(),
  saveModelConfiguration: vi.fn(),
}))

vi.mock('../../src/api/admin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/api/admin')>()),
  ...api,
}))

const source = (path: string) => readFile(resolve(process.cwd(), path), 'utf8')

const providerTypes: AdminProviderTypeCapabilities[] = [
  { providerType: 'BAILIAN', chatCapabilities: ['TEXT', 'FILE', 'IMAGE'], imageGeneration: true, videoGeneration: true },
  { providerType: 'DEEPSEEK', chatCapabilities: ['TEXT'], imageGeneration: false, videoGeneration: false },
  { providerType: 'MOCK', chatCapabilities: ['TEXT', 'FILE', 'IMAGE'], imageGeneration: false, videoGeneration: false },
  { providerType: 'TIANYIYUN', chatCapabilities: [], imageGeneration: true, videoGeneration: true },
]

const providers: AdminProvider[] = [
  {
    id: 'provider-bailian', code: 'bailian', name: '阿里云百炼', providerType: 'BAILIAN',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', enabled: true,
  },
  {
    // 就是这一类：key 能用、渠道 HEALTHY，但没有对话适配器，挂上文本模型每次调用都 503。
    id: 'provider-tianyi', code: 'tianyi', name: '天翼云', providerType: 'TIANYIYUN',
    baseUrl: 'https://ai.ctaigw.cn/v1', enabled: true,
  },
]

const channels: AdminChannel[] = [
  {
    id: 'channel-bailian', providerId: 'provider-bailian', code: 'bailian-primary', name: '百炼主渠道',
    environment: 'PRODUCTION', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', enabled: true,
    priority: 100, weight: 100, timeoutMs: 60_000, requestsPerMinute: 300, maxConcurrency: 50,
    lastHealthStatus: 'HEALTHY', credentialConfigured: true,
  },
]

function modelRoute(overrides: Partial<AdminModelRoute>): AdminModelRoute {
  return {
    componentModelCode: 'vision-basic', componentDisplayName: 'Vision Basic',
    providerId: 'provider-bailian', providerCode: 'bailian', providerName: '阿里云百炼',
    channelId: 'channel-bailian', channelCode: 'bailian-primary', channelName: '百炼主渠道',
    upstreamModel: 'qwen-vl-plus', environment: 'PRODUCTION',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    enabled: true, healthStatus: 'HEALTHY', credentialConfigured: true,
    priority: 100, weight: 100, timeoutMs: 60_000, requestsPerMinute: 300, maxConcurrency: 50,
    eligible: true,
    ...overrides,
  }
}

const models: AdminModel[] = [
  {
    id: 'model-vision', code: 'vision-basic', displayName: '读图基础版',
    aliases: ['vision-basic'], capabilities: ['TEXT', 'IMAGE'], routingStrategy: 'DIRECT',
    contextWindow: 32768, maxOutputTokens: 4096, premium: false, enabled: true,
    requestsPerMinute: 60, maxConcurrency: 5, routes: [modelRoute({})],
  },
  {
    id: 'model-tianyi', code: 'tianyi-chat', displayName: '天翼云对话',
    aliases: ['tianyi-chat'], capabilities: ['TEXT'], routingStrategy: 'DIRECT',
    contextWindow: 32768, maxOutputTokens: 4096, premium: false, enabled: true,
    requestsPerMinute: 60, maxConcurrency: 5,
    routes: [modelRoute({
      componentModelCode: 'tianyi-chat', componentDisplayName: '天翼云对话',
      providerId: 'provider-tianyi', providerCode: 'tianyi', providerName: '天翼云',
      channelId: 'channel-tianyi', channelCode: 'tianyi-primary', channelName: '天翼云主渠道',
      upstreamModel: 'qwen-vl-plus', baseUrl: 'https://ai.ctaigw.cn/v1',
      healthStatus: 'HEALTHY', eligible: false,
    })],
  },
]

function mountModelsView() {
  return mount(ModelsView, { global: { plugins: [ElementPlus] } })
}

/**
 * `getComponent` 的字符串重载在 @vue/test-utils 的类型里落到 WrapperLike（没有 props/vm），
 * 这里只补上用例真正用到的成员，让测试文件自身也能过类型检查。
 */
interface ComponentLike {
  props: (name: string) => unknown
  vm: { $emit: (event: string, ...args: unknown[]) => void }
  findAllComponents: (selector: unknown) => Array<{ props: (name: string) => unknown }>
}

function component(wrapper: VueWrapper, selector: string) {
  return wrapper.getComponent(selector) as unknown as ComponentLike
}

async function openCreateDialog() {
  const wrapper = mountModelsView()
  await flushPromises()
  await wrapper.get('[data-test=model-create]').trigger('click')
  await flushPromises()
  return wrapper
}

describe('admin provider capability guidance', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listModels.mockResolvedValue({ items: models, total: models.length, page: 0, size: 20 })
    api.listProviders.mockResolvedValue(providers)
    api.listProviderTypeCapabilities.mockResolvedValue(providerTypes)
    api.listChannels.mockResolvedValue({ items: channels, total: channels.length, page: 0, size: 100 })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loads the provider type catalog once and renders it under the selected provider', async () => {
    const wrapper = mountModelsView()
    await flushPromises()

    expect(api.listProviderTypeCapabilities).toHaveBeenCalledTimes(1)

    await wrapper.get('[data-test=model-edit-model-vision]').trigger('click')
    await flushPromises()

    const hint = wrapper.get('[data-test=model-provider-capability]')
    expect(hint.text()).toBe('对话能力：TEXT/FILE/IMAGE · 生图：是 · 视频：是')
    expect(wrapper.find('[data-test=model-provider-capability-warning]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('flags a provider type without chat adapters and warns that saving will be rejected', async () => {
    const wrapper = mountModelsView()
    await flushPromises()

    await wrapper.get('[data-test=model-edit-model-tianyi]').trigger('click')
    await flushPromises()

    const hint = wrapper.get('[data-test=model-provider-capability]')
    expect(hint.text()).toBe('对话能力：无 · 生图：是 · 视频：是')
    // 没有对话能力要标红，而不是和普通提示长得一样。
    expect(hint.classes()).toContain('provider-capability-hint--blocked')

    const warning = wrapper.get('[data-test=model-provider-capability-warning]')
    // el-alert 的根是 Transition，属性会落到内层 div 上，所以按 HTML 断言类型。
    expect(warning.html()).toContain('el-alert--warning')
    expect(warning.text()).toContain('provider_capability_unavailable')
    // 建议必须落到可执行的动作上：换 BAILIAN 类型。
    expect(warning.text()).toContain('BAILIAN')

    wrapper.unmount()
  })

  it('keeps the guidance hidden until a provider is selected', async () => {
    api.listProviders.mockResolvedValue([])
    const wrapper = await openCreateDialog()

    expect(wrapper.find('[data-test=model-provider-capability]').exists()).toBe(false)
    expect(wrapper.find('[data-test=model-provider-capability-warning]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('admin upstream key detection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listModels.mockResolvedValue({ items: models, total: models.length, page: 0, size: 20 })
    api.listProviders.mockResolvedValue(providers)
    api.listProviderTypeCapabilities.mockResolvedValue(providerTypes)
    api.listChannels.mockResolvedValue({ items: channels, total: channels.length, page: 0, size: 100 })
    api.saveModelConfiguration.mockResolvedValue(models[0])
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('probes with the typed credentials and renders the upstream models as a selectable list', async () => {
    const success = vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    api.probeUpstreamModels.mockResolvedValue({
      channelId: null,
      keyValid: true,
      httpStatus: 200,
      models: ['Qwen2.5-VL-72B-Instruct', 'qwen2.5-vl-72b-instruct', 'doubao-seedream-5.0-pro'],
      error: null,
    })
    const wrapper = await openCreateDialog()

    await wrapper.get('[data-test=model-base-url]').setValue('https://ai.ctaigw.cn/v1')
    await wrapper.get('[data-test=model-api-key]').setValue('sk-plain-key')
    await wrapper.get('[data-test=model-key-probe]').trigger('click')
    await flushPromises()

    // 密钥只用于这一次请求，不能进保存载荷以外的地方。
    expect(api.probeUpstreamModels).toHaveBeenCalledWith({
      baseUrl: 'https://ai.ctaigw.cn/v1',
      apiKey: 'sk-plain-key',
    })
    expect(success).toHaveBeenCalledWith('密钥可用，上游返回 3 个模型。')
    expect(wrapper.get('[data-test=model-key-probe-count]').text()).toContain('3')

    // 上游模型名允许手填（上游清单未必完整），所以既要下拉也要 allow-create。
    const upstream = component(wrapper, '[data-test=model-upstream]')
    expect(upstream.props('filterable')).toBe(true)
    expect(upstream.props('allowCreate')).toBe(true)
    expect(upstream.findAllComponents({ name: 'ElOption' }).map((option) => option.props('value')))
      .toEqual(['Qwen2.5-VL-72B-Instruct', 'qwen2.5-vl-72b-instruct', 'doubao-seedream-5.0-pro'])

    // 检测结果要真的进得了保存载荷，且仍然走既有的 /models/configuration 链路。
    upstream.vm.$emit('update:modelValue', 'doubao-seedream-5.0-pro')
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-test=model-code]').setValue('vision-basic')
    await wrapper.get('[data-test=model-name]').setValue('读图基础版')
    await wrapper.get('[data-test=model-save]').trigger('click')
    await flushPromises()

    expect(api.saveModelConfiguration).toHaveBeenCalledWith(expect.objectContaining({
      code: 'vision-basic',
      route: expect.objectContaining({
        baseUrl: 'https://ai.ctaigw.cn/v1',
        upstreamModel: 'doubao-seedream-5.0-pro',
        apiKey: 'sk-plain-key',
      }),
    }))

    wrapper.unmount()
  })

  it('reports an invalid key with the upstream status and keeps the free-text input', async () => {
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    api.probeUpstreamModels.mockResolvedValue({
      channelId: null, keyValid: false, httpStatus: 401, models: [], error: 'upstream_rejected',
    })
    const wrapper = await openCreateDialog()

    await wrapper.get('[data-test=model-base-url]').setValue('https://ai.ctaigw.cn/v1')
    await wrapper.get('[data-test=model-api-key]').setValue('sk-revoked')
    await wrapper.get('[data-test=model-key-probe]').trigger('click')
    await flushPromises()

    expect(error).toHaveBeenCalledTimes(1)
    const message = String(error.mock.calls[0][0])
    expect(message).toContain('401')
    expect(message).toContain('密钥无效')
    expect(wrapper.find('[data-test=model-key-probe-count]').exists()).toBe(false)
    expect(wrapper.get('[data-test=model-upstream]').element.tagName).toBe('INPUT')

    wrapper.unmount()
  })

  it('explains an unreachable upstream instead of blaming the key', async () => {
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    api.probeUpstreamModels.mockResolvedValue({
      channelId: null, keyValid: false, httpStatus: 0, models: [], error: 'upstream_unreachable',
    })
    const wrapper = await openCreateDialog()

    await wrapper.get('[data-test=model-base-url]').setValue('https://ai.ctaigw.cn/v1')
    await wrapper.get('[data-test=model-api-key]').setValue('sk-plain-key')
    await wrapper.get('[data-test=model-key-probe]').trigger('click')
    await flushPromises()

    const message = String(error.mock.calls[0][0])
    expect(message).toContain('无法连接上游')
    expect(message).not.toContain('密钥无效')

    wrapper.unmount()
  })

  it('refuses to probe before both the base URL and the key are filled in', async () => {
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never)
    const wrapper = await openCreateDialog()

    await wrapper.get('[data-test=model-key-probe]').trigger('click')
    await flushPromises()

    expect(api.probeUpstreamModels).not.toHaveBeenCalled()
    expect(warning).toHaveBeenCalledWith('请先填写接口地址和 API 密钥，再检测密钥。')

    wrapper.unmount()
  })
})

describe('admin bulk route onboarding', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listModels.mockResolvedValue({ items: models, total: models.length, page: 0, size: 20 })
    api.listProviders.mockResolvedValue(providers)
    api.listProviderTypeCapabilities.mockResolvedValue(providerTypes)
    api.listChannels.mockResolvedValue({ items: channels, total: channels.length, page: 0, size: 100 })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('submits the edited rows and renders per-row status plus the notes', async () => {
    api.onboardChannelRoutes.mockResolvedValue({
      channelId: 'channel-bailian',
      routes: [
        { modelCode: 'vision-basic', upstreamModel: 'Qwen2.5-VL-72B-Instruct', status: 'CREATED', reason: null },
        { modelCode: 'text-max', upstreamModel: 'qwen-max', status: 'REJECTED', reason: 'provider_capability_unavailable' },
      ],
      notes: [
        'video-basic 是按秒计费的视频别名，还缺分辨率档位与每秒价格（PUT /video-aliases/video-basic），在此之前它只能路由、不能计价',
        'text-max 还挂在优先级不低的渠道上：bailian-primary；新渠道的 priority 需要更高才会被选中',
      ],
    })
    const wrapper = mountModelsView()
    await flushPromises()

    await wrapper.get('[data-test=model-onboard-open]').trigger('click')
    await flushPromises()

    // 分页接口取一页就够，参数保持既有封装方式。
    expect(api.listChannels).toHaveBeenCalledWith({
      page: 0, size: 100, query: '', status: '', sort: 'priority', direction: 'desc',
    })

    component(wrapper, '[data-test=onboard-channel]').vm.$emit('update:modelValue', 'channel-bailian')
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-test=onboard-model-code-0]').setValue('vision-basic')
    await wrapper.get('[data-test=onboard-upstream-0]').setValue('Qwen2.5-VL-72B-Instruct')

    await wrapper.get('[data-test=onboard-add-row]').trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-test=onboard-model-code-1]').setValue('text-max')
    await wrapper.get('[data-test=onboard-upstream-1]').setValue('qwen-max')
    component(wrapper, '[data-test=onboard-enabled-1]').vm.$emit('update:modelValue', false)
    await wrapper.vm.$nextTick()

    await wrapper.get('[data-test=onboard-submit]').trigger('click')
    await flushPromises()

    expect(api.onboardChannelRoutes).toHaveBeenCalledWith('channel-bailian', [
      { modelCode: 'vision-basic', upstreamModel: 'Qwen2.5-VL-72B-Instruct', enabled: true },
      { modelCode: 'text-max', upstreamModel: 'qwen-max', enabled: false },
    ])

    const created = wrapper.get('[data-test=onboard-result-0]')
    expect(created.text()).toContain('已创建')
    expect(created.html()).toContain('el-tag--success')

    // 被拒的那条必须红着显示，并且给中文原因，而不是把稳定错误码丢给运营。
    const rejected = wrapper.get('[data-test=onboard-result-1]')
    expect(rejected.text()).toContain('已拒绝')
    expect(rejected.html()).toContain('el-tag--danger')
    expect(wrapper.get('[data-test=onboard-reason-1]').text())
      .toBe('该服务商类型没有能服务这个模型的能力')

    // notes 是「接进去了但还不能用」，必须显眼展示，否则会被当成已经可以卖。
    const notes = wrapper.findAll('.onboarding-note')
    expect(notes).toHaveLength(2)
    expect(notes[0].html()).toContain('el-alert--warning')
    expect(notes[0].text()).toContain('还缺分辨率档位与每秒价格')
    expect(notes[1].text()).toContain('priority 需要更高才会被选中')

    wrapper.unmount()
  })

  it('maps the model_not_found rejection to a readable reason', async () => {
    api.onboardChannelRoutes.mockResolvedValue({
      channelId: 'channel-bailian',
      routes: [{ modelCode: 'nope', upstreamModel: 'qwen-max', status: 'REJECTED', reason: 'model_not_found' }],
      notes: [],
    })
    const wrapper = mountModelsView()
    await flushPromises()
    await wrapper.get('[data-test=model-onboard-open]').trigger('click')
    await flushPromises()

    component(wrapper, '[data-test=onboard-channel]').vm.$emit('update:modelValue', 'channel-bailian')
    await wrapper.get('[data-test=onboard-model-code-0]').setValue('nope')
    await wrapper.get('[data-test=onboard-upstream-0]').setValue('qwen-max')
    await wrapper.get('[data-test=onboard-submit]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test=onboard-reason-0]').text()).toBe('平台别名不存在')

    wrapper.unmount()
  })

  it('reuses the models returned by the last key detection for the row inputs', async () => {
    api.probeUpstreamModels.mockResolvedValue({
      channelId: null,
      keyValid: true,
      httpStatus: 200,
      models: ['Qwen2.5-VL-72B-Instruct', 'doubao-seedream-5.0-pro'],
      error: null,
    })
    const wrapper = await openCreateDialog()

    await wrapper.get('[data-test=model-base-url]').setValue('https://ai.ctaigw.cn/v1')
    await wrapper.get('[data-test=model-api-key]').setValue('sk-plain-key')
    await wrapper.get('[data-test=model-key-probe]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-test=model-onboard-open]').trigger('click')
    await flushPromises()

    const upstream = component(wrapper, '[data-test=onboard-upstream-0]')
    expect(upstream.findAllComponents({ name: 'ElOption' }).map((option) => option.props('value')))
      .toEqual(['Qwen2.5-VL-72B-Instruct', 'doubao-seedream-5.0-pro'])

    wrapper.unmount()
  })

  it('refuses to submit without a channel or without a complete row', async () => {
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never)
    const wrapper = mountModelsView()
    await flushPromises()
    await wrapper.get('[data-test=model-onboard-open]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-test=onboard-submit]').trigger('click')
    await flushPromises()
    expect(warning).toHaveBeenCalledWith('请先选择要接入的渠道。')

    component(wrapper, '[data-test=onboard-channel]').vm.$emit('update:modelValue', 'channel-bailian')
    await wrapper.get('[data-test=onboard-model-code-0]').setValue('vision-basic')
    await wrapper.get('[data-test=onboard-submit]').trigger('click')
    await flushPromises()
    expect(warning).toHaveBeenCalledWith('请至少填写一条「平台别名 + 上游模型」。')
    expect(api.onboardChannelRoutes).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

/**
 * 接口封装契约：三条新接口必须落在既有的 gatewayHttp 封装里，
 * 而保存模型的链路不能被顺手改掉（它还是 POST /models/configuration）。
 */
describe('admin provider capability API contract', () => {
  it('exposes the provider catalog, the probe and the onboarding calls', async () => {
    const api = await source('src/api/admin.ts')

    expect(api).toContain('export interface AdminProviderTypeCapabilities')
    expect(api).toContain('export interface AdminUpstreamModelProbe')
    expect(api).toContain('export interface AdminRouteOnboardingResult')
    expect(api).toContain('export interface AdminRoutesOnboarding')
    expect(api).toContain('/console/gateway/admin/provider-types')
    expect(api).toContain('/console/gateway/admin/upstream-models')
    expect(api).toContain('/channels/${encodeURIComponent(channelId)}/routes')
  })

  it('keeps the model save path on the existing endpoint', async () => {
    const api = await source('src/api/admin.ts')

    expect(api).toContain("gatewayHttp.post<AdminModel>('/console/gateway/admin/models/configuration'")
  })
})
