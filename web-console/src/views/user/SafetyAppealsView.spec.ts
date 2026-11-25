import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import SafetyAppealsView from './SafetyAppealsView.vue'
import safetyAppealsSource from './SafetyAppealsView.vue?raw'
import { contentSafetyApi } from '../../api/contentSafety'

const confirm = vi.hoisted(() => vi.fn())

vi.mock('element-plus', () => ({
  ElMessage: {
    warning: vi.fn(),
    success: vi.fn(),
    error: vi.fn(),
  },
  ElMessageBox: { confirm },
}))

vi.mock('../../api/contentSafety', () => ({
  contentSafetyApi: {
    appeals: vi.fn(),
    userCases: vi.fn(),
    createAppeal: vi.fn(),
    withdrawAppeal: vi.fn(),
  },
}))

const api = vi.mocked(contentSafetyApi)

describe('SafetyAppealsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirm.mockResolvedValue('confirm')
    api.appeals.mockResolvedValue({
      data: { items: [], total: 0, page: 0, size: 20 },
    } as never)
    api.userCases.mockResolvedValue({
      data: {
        items: [{
          caseNo: 'CS-0123456789abcdef0123',
          category: 'SENSITIVE_CONTENT',
          severity: 'HIGH',
          action: 'BLOCK',
          status: 'OPEN',
          redactedExcerpt: 'example',
          createdAt: '2026-08-01T00:00:00Z',
        }],
        total: 21,
        page: 0,
        size: 20,
      },
    } as never)
  })

  it('withdraws only a pending appeal after confirmation', async () => {
    const pending = {
      businessNo: 'AP-0123456789abcdef0123', caseId: 'case', userId: 'user',
      status: 'PENDING', reason: '请求复核', processingNote: undefined,
    }
    api.appeals.mockResolvedValue({ data: { items: [pending], total: 1, page: 0, size: 20 } } as never)
    api.withdrawAppeal.mockResolvedValue({ data: { ...pending, status: 'WITHDRAWN' } } as never)
    const wrapper = mount(SafetyAppealsView, {
      global: {
        stubs: {
          'el-card': { template: '<section><slot name="header" /><slot /></section>' },
          'el-button': { template: '<button><slot /></button>' },
          'el-form': { template: '<form><slot /></form>' },
          'el-form-item': { template: '<div><slot /></div>' },
          'el-input': { template: '<textarea />' },
          'el-table': { template: '<div><slot /></div>' },
          'el-table-column': { template: '<div />' },
          'el-tag': { template: '<span><slot /></span>' },
          'el-pagination': { template: '<div />' },
        },
        directives: { loading: () => undefined },
      },
    })
    await flushPromises()

    await (wrapper.vm as unknown as { withdrawAppeal: (item: typeof pending) => Promise<void> }).withdrawAppeal(pending)
    await flushPromises()

    expect(confirm).toHaveBeenCalled()
    expect(api.withdrawAppeal).toHaveBeenCalledWith(pending.businessNo)
  })

  it('explains account restriction triggers and withdrawal rules', () => {
    const source = safetyAppealsSource
    expect(source).toContain('账号限制与申诉说明')
    expect(source).toContain('<strong>冻结：</strong>')
    expect(source).toContain('<strong>封禁：</strong>')
    expect(source).toContain('管理员也可在审核前手动撤回')
  })

  it('shows selectable appeal records instead of a manual case number field', async () => {
    const wrapper = mount(SafetyAppealsView, {
      global: {
        stubs: {
          'el-card': { template: '<section><slot name="header" /><slot /></section>' },
          'el-button': { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
          'el-form': { template: '<form><slot /></form>' },
          'el-form-item': { template: '<div><slot /></div>' },
          'el-input': {
            props: ['modelValue'],
            emits: ['update:modelValue'],
            template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
          },
          'el-table': { template: '<div><slot /></div>' },
          'el-table-column': { template: '<div />' },
          'el-tag': { template: '<span><slot /></span>' },
          'el-pagination': {
            props: ['currentPage', 'total'],
            emits: ['current-change'],
            template: '<button class="pager" @click="$emit(\'current-change\', Number(currentPage) + 1)">{{ total }}</button>',
          },
        },
        directives: {
          loading: () => undefined,
        },
      },
    })

    await flushPromises()

    expect(api.userCases).toHaveBeenCalledWith({ page: 0, size: 20 })
    expect(api.appeals).toHaveBeenCalledWith({ page: 0, size: 20 })
    expect(wrapper.text()).toContain('CS-0123456789abcdef0123')
    expect(wrapper.text()).toContain('2026-08-01 08:00:00')
    expect(wrapper.text()).not.toContain('2026-08-01T00:00:00Z')
    expect(wrapper.text()).toContain('提交申诉')
    expect(wrapper.text()).not.toContain('违规记录编号')

    const recordButton = wrapper.findAll('button').find((button) => button.text() === '提交申诉')
    expect(recordButton).toBeDefined()
    await recordButton!.trigger('click')
    await wrapper.find('textarea').setValue('请求复核该记录')

    const confirmButton = wrapper.findAll('button').find((button) => button.text() === '确认提交申诉')
    expect(confirmButton).toBeDefined()
    api.createAppeal.mockResolvedValue({ data: {} } as never)
    await confirmButton!.trigger('click')
    await flushPromises()
    expect(api.createAppeal).toHaveBeenCalledWith('CS-0123456789abcdef0123', '请求复核该记录')
  })

  it('shows a normal-account notice after both appeal lists load empty', async () => {
    api.userCases.mockResolvedValue({
      data: { items: [], total: 0, page: 0, size: 20 },
    } as never)

    const wrapper = mount(SafetyAppealsView, {
      global: {
        stubs: {
          'el-card': { template: '<section><slot name="header" /><slot /></section>' },
          'el-button': { template: '<button><slot /></button>' },
          'el-form': { template: '<form><slot /></form>' },
          'el-form-item': { template: '<div><slot /></div>' },
          'el-input': { template: '<textarea />' },
          'el-table': { template: '<div><slot /><slot name="empty" /></div>' },
          'el-table-column': { template: '<div />' },
          'el-tag': { template: '<span><slot /></span>' },
          'el-pagination': { template: '<div />' },
        },
        directives: {
          loading: () => undefined,
        },
      },
    })

    expect(wrapper.text()).not.toContain('当前您的账号正常，无需申诉')

    await flushPromises()

    expect(wrapper.get('[role="status"]').text()).toBe('当前您的账号正常，无需申诉')
    expect(wrapper.text()).not.toContain('暂无可申诉记录')
    expect(wrapper.text()).toContain('暂无申诉记录')
  })

  it('does not show the normal-account notice when an appealable record exists', async () => {
    const wrapper = mount(SafetyAppealsView, {
      global: {
        stubs: {
          'el-card': { template: '<section><slot name="header" /><slot /></section>' },
          'el-button': { template: '<button><slot /></button>' },
          'el-form': { template: '<form><slot /></form>' },
          'el-form-item': { template: '<div><slot /></div>' },
          'el-input': { template: '<textarea />' },
          'el-table': { template: '<div><slot /></div>' },
          'el-table-column': { template: '<div />' },
          'el-tag': { template: '<span><slot /></span>' },
          'el-pagination': { template: '<div />' },
        },
        directives: {
          loading: () => undefined,
        },
      },
    })

    await flushPromises()

    expect(wrapper.text()).not.toContain('当前您的账号正常，无需申诉')
  })

  it('keeps appeal surfaces separated at the page level', () => {
    expect(safetyAppealsSource).toMatch(/\.page-stack\s*\{[^}]*display:\s*grid;[^}]*gap:\s*16px;/s)
  })

  it('keeps loading overlays scoped to each appeal collection', () => {
    expect(safetyAppealsSource).toContain('v-loading="caseLoading"')
    expect(safetyAppealsSource).toContain('v-loading="appealLoading"')
    expect(safetyAppealsSource).not.toContain('<section class="page-stack" v-loading=')
  })

  it('changes the appealable-case and appeal-history pages independently', async () => {
    const wrapper = mount(SafetyAppealsView, {
      global: {
        stubs: {
          'el-card': { template: '<section><slot name="header" /><slot /></section>' },
          'el-button': { template: '<button><slot /></button>' },
          'el-form': { template: '<form><slot /></form>' },
          'el-form-item': { template: '<div><slot /></div>' },
          'el-input': { template: '<textarea />' },
          'el-table': { template: '<div><slot /></div>' },
          'el-table-column': { template: '<div />' },
          'el-tag': { template: '<span><slot /></span>' },
          'el-pagination': {
            props: ['currentPage'],
            emits: ['current-change'],
            template: '<button class="pager" @click="$emit(\'current-change\', Number(currentPage) + 1)">next</button>',
          },
        },
        directives: { loading: () => undefined },
      },
    })

    await flushPromises()
    expect(wrapper.findAll('.pager')).toHaveLength(2)

    await wrapper.findAll('.pager')[0]!.trigger('click')
    await flushPromises()
    expect(api.userCases).toHaveBeenLastCalledWith({ page: 1, size: 20 })
    expect(api.appeals).toHaveBeenCalledTimes(1)

    await wrapper.findAll('.pager')[1]!.trigger('click')
    await flushPromises()
    expect(api.appeals).toHaveBeenLastCalledWith({ page: 1, size: 20 })
    expect(api.userCases).toHaveBeenCalledTimes(2)
  })

  it('returns both lists to the first page after creating an appeal', async () => {
    const wrapper = mount(SafetyAppealsView, {
      global: {
        stubs: {
          'el-card': { template: '<section><slot name="header" /><slot /></section>' },
          'el-button': { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
          'el-form': { template: '<form><slot /></form>' },
          'el-form-item': { template: '<div><slot /></div>' },
          'el-input': {
            props: ['modelValue'],
            emits: ['update:modelValue'],
            template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
          },
          'el-table': { template: '<div><slot /></div>' },
          'el-table-column': { template: '<div />' },
          'el-tag': { template: '<span><slot /></span>' },
          'el-pagination': {
            props: ['currentPage'],
            emits: ['current-change'],
            template: '<button class="pager" @click="$emit(\'current-change\', Number(currentPage) + 1)">next</button>',
          },
        },
        directives: { loading: () => undefined },
      },
    })

    await flushPromises()
    for (const pager of wrapper.findAll('.pager')) {
      await pager.trigger('click')
      await flushPromises()
    }

    const startButton = wrapper.findAll('button').find((button) => button.text() === '提交申诉')
    await startButton!.trigger('click')
    await wrapper.find('textarea').setValue('请求复核该记录')
    api.createAppeal.mockResolvedValue({ data: {} } as never)
    const submitButton = wrapper.findAll('button').find((button) => button.text() === '确认提交申诉')
    await submitButton!.trigger('click')
    await flushPromises()

    expect(api.userCases).toHaveBeenLastCalledWith({ page: 0, size: 20 })
    expect(api.appeals).toHaveBeenLastCalledWith({ page: 0, size: 20 })
  })
})
