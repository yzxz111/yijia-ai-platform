import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import PlansAdminView from './PlansAdminView.vue'
import * as admin from '../../api/admin'
import type { AdminModel, AdminPackagePlan } from '../../api/admin'
import * as platform from '../../api/platform'

const route = { query: {} as Record<string, unknown> }

vi.mock('vue-router', () => ({
  useRoute: () => route,
}))

vi.mock('../../api/admin', () => ({
  archivePackagePlan: vi.fn(),
  createPackagePlan: vi.fn(),
  createPlanVersion: vi.fn(),
  listModels: vi.fn(),
  listPackagePlanCatalog: vi.fn(),
  publishPlanVersion: vi.fn(),
  restorePackagePlan: vi.fn(),
  updatePackagePlan: vi.fn(),
}))

vi.mock('../../api/platform', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/platform')>()),
  listPlans: vi.fn(),
}))

const api = vi.mocked(admin)
const platformApi = vi.mocked(platform)

function model(code: string, displayName: string, capabilities: string[]): AdminModel {
  return {
    id: `model-${code}`,
    code,
    displayName,
    aliases: [],
    capabilities,
    routingStrategy: 'DIRECT',
    contextWindow: 128_000,
    maxOutputTokens: 4_096,
    premium: false,
    enabled: true,
    requestsPerMinute: 60,
    maxConcurrency: 5,
    routes: [],
  }
}

const videoBasic = model('video-basic', '视频基础', ['VIDEO'])
const videoMax = model('video-max', '视频超清', ['VIDEO'])
const textBasic = model('text-basic', '文本基础', ['CHAT'])
// 音频别名的三种能力名（网关 V93 / 平台 ALLOWED_CAPABILITIES）：
// 转写、合成、唇形同步同属 AUDIO_SECOND 一个维度，共用同一份音频秒额度。
const audioAsr = model('audio-asr', '语音转写', ['AUDIO_TRANSCRIBE'])
const audioTts = model('audio-tts', '语音合成', ['AUDIO_SYNTHESIS'])

const catalogPlan: AdminPackagePlan = {
  code: 'PLAN-VIDEO',
  name: '视频秒数额度套餐',
  description: null,
  kind: 'STANDARD',
  status: 'ACTIVE',
  displayOrder: 0,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  versions: [],
}

function elementStubs() {
  return {
    'el-button': { template: '<button><slot /></button>' },
    'el-checkbox': { props: ['modelValue'], template: '<label><slot /></label>' },
    'el-dialog': { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
    'el-form': { template: '<form><slot /></form>' },
    'el-form-item': { props: ['label'], template: '<div class="el-form-item"><span class="el-form-item__label">{{ label }}</span><slot /></div>' },
    'el-input': {
      props: ['modelValue'],
      emits: ['update:modelValue'],
      template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
    },
    'el-input-number': {
      props: ['modelValue'],
      emits: ['update:modelValue'],
      template: '<input type="number" :value="modelValue ?? \'\'" @input="$emit(\'update:modelValue\', $event.target.value === \'\' ? null : Number($event.target.value))" />',
    },
    'el-option': { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
    'el-pagination': { template: '<div />' },
    'el-select': {
      props: ['modelValue'],
      emits: ['update:modelValue'],
      template: '<select multiple @change="$emit(\'update:modelValue\', Array.from($event.target.selectedOptions).map(option => option.value))"><slot /></select>',
    },
    'el-skeleton': { template: '<div />' },
    'el-tag': { template: '<span><slot /></span>' },
  }
}

function mountView() {
  return mount(PlansAdminView, { global: { stubs: elementStubs() } })
}

async function openVersionDialog(wrapper: VueWrapper) {
  await wrapper.get(`[data-test="plan-version-create-${catalogPlan.code}"]`).trigger('click')
  await nextTick()
  await wrapper.get('[data-test="plan-price"]').setValue('9900')
  await nextTick()
}

async function selectModels(wrapper: VueWrapper, codes: string[]) {
  await wrapper.get('[data-test="plan-models"]').setValue(codes)
  await nextTick()
}

function quotaInputs(wrapper: VueWrapper) {
  return wrapper.findAll('[data-test="plan-model-quota"]')
}

describe('plan version per-model quota and usage dimension', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    route.query = {}
    api.listPackagePlanCatalog.mockResolvedValue({ items: [catalogPlan], total: 1, page: 0, size: 10 })
    api.listModels.mockResolvedValue({
      items: [videoBasic, videoMax, textBasic, audioAsr, audioTts],
      total: 5,
      page: 0,
      size: 100,
    })
    api.createPlanVersion.mockResolvedValue({})
    platformApi.listPlans.mockResolvedValue([])
  })

  it('renders one optional quota input per selected model', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openVersionDialog(wrapper)

    expect(wrapper.find('[data-test="plan-form"]').exists()).toBe(true)
    expect(quotaInputs(wrapper)).toHaveLength(0)
    await selectModels(wrapper, ['video-basic', 'video-max'])

    expect(quotaInputs(wrapper)).toHaveLength(2)
    expect(wrapper.get('[data-test="plan-model-quota-help"]').text()).toContain('留空表示该模型不设单独额度')
    const labels = wrapper.findAll('.el-form-item__label').map(label => label.text())
    expect(labels).toContain('视频基础 · video-basic · 秒额度')
    expect(labels).toContain('视频超清 · video-max · 秒额度')
  })

  it('sends the real per-model quota and null for a cleared input', async () => {
    vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    const wrapper = mountView()
    await flushPromises()
    await openVersionDialog(wrapper)
    await selectModels(wrapper, ['video-basic', 'video-max'])

    await quotaInputs(wrapper)[0]!.setValue('600')
    await quotaInputs(wrapper)[1]!.setValue('900')
    // 清空输入表示该模型不设单独额度
    await quotaInputs(wrapper)[1]!.setValue('')

    await wrapper.get('[data-test="plan-version-create"]').trigger('click')
    await flushPromises()

    expect(api.createPlanVersion).toHaveBeenCalledTimes(1)
    expect(api.createPlanVersion).toHaveBeenCalledWith(expect.objectContaining({
      planCode: 'PLAN-VIDEO',
      models: [
        { modelCode: 'video-basic', modelTokenQuota: 600, capabilities: ['VIDEO'] },
        { modelCode: 'video-max', modelTokenQuota: null, capabilities: ['VIDEO'] },
      ],
    }))
  })

  it('labels the package quota in seconds for video models and in credits otherwise', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openVersionDialog(wrapper)

    await selectModels(wrapper, ['video-basic'])
    expect(wrapper.text()).toContain('总秒数额度')
    expect(wrapper.text()).toContain('秒额度')
    expect(wrapper.text()).toContain('秒 / 分钟')
    expect(wrapper.text()).not.toContain('总积分额度')

    await selectModels(wrapper, ['text-basic'])
    expect(wrapper.text()).toContain('总积分额度')
    expect(wrapper.text()).toContain('积分 / 分钟')
    expect(wrapper.text()).not.toContain('总秒数额度')
    expect(wrapper.text()).not.toContain('秒额度')
  })

  it('blocks mixing video and text models without calling the API', async () => {
    const errorMessage = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    const wrapper = mountView()
    await flushPromises()
    await openVersionDialog(wrapper)
    await selectModels(wrapper, ['video-basic', 'text-basic'])

    expect(wrapper.find('[data-test="plan-mixed-dimension-warning"]').exists()).toBe(true)
    await wrapper.get('[data-test="plan-version-create"]').trigger('click')
    await flushPromises()

    expect(api.createPlanVersion).not.toHaveBeenCalled()
    expect(errorMessage).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining('mixed_usage_dimensions_in_package'),
    }))
    expect(errorMessage).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining('不能同时选择视频模型'),
    }))
  })

  it('blocks mixing an audio model with a token model without calling the API', async () => {
    const errorMessage = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    const wrapper = mountView()
    await flushPromises()
    await openVersionDialog(wrapper)
    await selectModels(wrapper, ['audio-asr', 'text-basic'])

    expect(wrapper.find('[data-test="plan-mixed-dimension-warning"]').exists()).toBe(true)
    await wrapper.get('[data-test="plan-version-create"]').trigger('click')
    await flushPromises()

    expect(api.createPlanVersion).not.toHaveBeenCalled()
    expect(errorMessage).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining('mixed_usage_dimensions_in_package'),
    }))
  })

  it('accepts video + audio as a mixed version and requires the audio-seconds quota', async () => {
    vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    const errorMessage = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    const wrapper = mountView()
    await flushPromises()
    await openVersionDialog(wrapper)
    await selectModels(wrapper, ['video-basic', 'audio-asr'])

    // 混合版本不再是"维度混用"：视频秒与音频秒各有一列额度（方案 C）
    expect(wrapper.find('[data-test="plan-mixed-dimension-warning"]').exists()).toBe(false)
    // 主维度仍是视频秒，因此总额度与速率都按秒文案
    expect(wrapper.text()).toContain('总秒数额度')
    expect(wrapper.text()).toContain('秒 / 分钟')
    expect(wrapper.find('[data-test="plan-audio-seconds-input"]').exists()).toBe(true)

    // 空着音频秒额度提交：必须由本地校验拦下，而不是等一个 422
    await wrapper.get('[data-test="plan-version-create"]').trigger('click')
    await flushPromises()
    expect(api.createPlanVersion).not.toHaveBeenCalled()
    expect(errorMessage).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining('audio_alias_requires_audio_seconds_quota'),
    }))

    await wrapper.get('[data-test="plan-audio-seconds-input"]').setValue('600')
    await wrapper.get('[data-test="plan-version-create"]').trigger('click')
    await flushPromises()

    expect(api.createPlanVersion).toHaveBeenCalledTimes(1)
    expect(api.createPlanVersion).toHaveBeenCalledWith(expect.objectContaining({
      planCode: 'PLAN-VIDEO',
      totalTokenQuota: 1_000_000,
      audioSecondsQuota: 600,
    }))
  })

  it('resolves a pure-audio selection to AUDIO_SECOND and sends no audio-seconds quota', async () => {
    vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    const wrapper = mountView()
    await flushPromises()
    await openVersionDialog(wrapper)
    // 转写与合成同属 AUDIO_SECOND：纯音频版本合法，额度就是主额度那一列
    await selectModels(wrapper, ['audio-asr', 'audio-tts'])

    expect(wrapper.find('[data-test="plan-mixed-dimension-warning"]').exists()).toBe(false)
    // 纯音频版本不得出现第二份额度那一格：它的音频秒在主额度列里
    expect(wrapper.find('[data-test="plan-audio-seconds-input"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('音频秒额度')
    // 纯音频也是按秒计费，绝不能退回"积分"文案
    expect(wrapper.text()).toContain('总秒数额度')
    expect(wrapper.text()).toContain('秒 / 分钟')

    await wrapper.get('[data-test="plan-version-create"]').trigger('click')
    await flushPromises()

    expect(api.createPlanVersion).toHaveBeenCalledTimes(1)
    expect(api.createPlanVersion).toHaveBeenCalledWith(expect.objectContaining({
      totalTokenQuota: 1_000_000,
      audioSecondsQuota: null,
    }))
  })

  it('caps an audio model quota by the audio-seconds bucket instead of the video quota', async () => {
    vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    const errorMessage = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    const wrapper = mountView()
    await flushPromises()
    await openVersionDialog(wrapper)
    await selectModels(wrapper, ['video-basic', 'audio-asr'])
    await wrapper.get('[data-test="plan-audio-seconds-input"]').setValue('600')

    // 音频模型的单独额度超过音频秒总额度 → 本地拦下。用视频总额度（1,000,000）
    // 或"是不是秒维度"来判断都会放行这 900，服务端则会回 invalid_model_permission。
    await quotaInputs(wrapper)[1]!.setValue('900')
    await wrapper.get('[data-test="plan-version-create"]').trigger('click')
    await flushPromises()

    expect(api.createPlanVersion).not.toHaveBeenCalled()
    expect(errorMessage).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining('invalid_model_permission'),
    }))

    // 同一个数字落在音频桶之内就必须放行：上限不是"一律拒绝"
    await quotaInputs(wrapper)[1]!.setValue('600')
    await wrapper.get('[data-test="plan-version-create"]').trigger('click')
    await flushPromises()

    expect(api.createPlanVersion).toHaveBeenCalledTimes(1)
    expect(api.createPlanVersion).toHaveBeenCalledWith(expect.objectContaining({
      audioSecondsQuota: 600,
      models: [
        { modelCode: 'video-basic', modelTokenQuota: null, capabilities: ['VIDEO'] },
        { modelCode: 'audio-asr', modelTokenQuota: 600, capabilities: ['AUDIO_TRANSCRIBE'] },
      ],
    }))
  })

  it('clears the audio-seconds quota when the selection stops being mixed', async () => {
    vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    const wrapper = mountView()
    await flushPromises()
    await openVersionDialog(wrapper)
    await selectModels(wrapper, ['video-basic', 'audio-asr'])
    await wrapper.get('[data-test="plan-audio-seconds-input"]').setValue('600')

    // 删掉视频别名 → 纯音频：那一格必须收起并清空，否则残留的 600 会被发出去，
    // 而服务端对纯音频版本要求它是 null（audio_seconds_quota_on_a_pure_audio_version）。
    await selectModels(wrapper, ['audio-asr'])
    expect(wrapper.find('[data-test="plan-audio-seconds-input"]').exists()).toBe(false)

    await wrapper.get('[data-test="plan-version-create"]').trigger('click')
    await flushPromises()

    expect(api.createPlanVersion).toHaveBeenCalledWith(expect.objectContaining({
      audioSecondsQuota: null,
    }))
  })
})
