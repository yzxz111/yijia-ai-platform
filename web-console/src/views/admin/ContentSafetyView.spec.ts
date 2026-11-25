import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import ContentSafetyView from './ContentSafetyView.vue'
import contentSafetySource from './ContentSafetyView.vue?raw'
import { contentSafetyApi } from '../../api/contentSafety'

const route = { query: { query: 'tenant-a' } }

vi.mock('vue-router', () => ({
  useRoute: () => route,
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

vi.mock('../../api/contentSafety', () => ({
  contentSafetyApi: {
    cases: vi.fn(),
    adminAppeals: vi.fn(),
    transition: vi.fn(),
    processAppeal: vi.fn(),
    withdrawAdminAppeal: vi.fn(),
    enforce: vi.fn(),
  },
}))

const api = vi.mocked(contentSafetyApi)

function mountView() {
  return mount(ContentSafetyView, {
    global: {
      stubs: {
        'el-card': { template: '<section><slot name="header" /><slot /></section>' },
        'el-table': { template: '<div><slot /></div>' },
        'el-table-column': { template: '<div />' },
        'el-button': { template: '<button><slot /></button>' },
        'el-tag': { template: '<span><slot /></span>' },
        'el-tabs': { template: '<div><slot /></div>' },
        'el-tab-pane': { template: '<div><slot /></div>' },
        'el-input': { template: '<textarea />' },
        'el-select': { template: '<select><slot /></select>' },
        'el-option': { template: '<option><slot /></option>' },
        'el-drawer': { props: ['modelValue'], template: '<aside v-if="modelValue"><slot /></aside>' },
        'el-dialog': { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
        'el-empty': { template: '<div />' },
        'el-pagination': {
          props: ['currentPage'],
          emits: ['current-change'],
          template: '<button class="pager" @click="$emit(\'current-change\', Number(currentPage) + 1)">next</button>',
        },
      },
      directives: { loading: () => undefined },
    },
  })
}

describe('ContentSafetyView pagination', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    route.query = { query: 'tenant-a' }
    api.cases.mockResolvedValue({
      data: { items: [], total: 21, page: 0, size: 20 },
    } as never)
    api.adminAppeals.mockResolvedValue({
      data: { items: [], total: 22, page: 0, size: 20 },
    } as never)
  })

  it('uses the shared sizing contract for content safety filters', () => {
    expect(contentSafetySource).toContain('class="admin-toolbar safety-filters"')
    expect(contentSafetySource).toMatch(/@media \(max-width: 600px\)[\s\S]*?\.safety-filters \.el-select \{ width: 100%; \}/)
  })

  it('passes the drill query to both independent server pages', async () => {
    mountView()
    await flushPromises()

    expect(api.cases).toHaveBeenCalledWith({ page: 0, size: 20, query: 'tenant-a', status: '' })
    expect(api.adminAppeals).toHaveBeenCalledWith({ page: 0, size: 20, query: 'tenant-a', status: '' })
  })

  it('changes each page without reloading the other collection', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.findAll('.pager')).toHaveLength(2)

    await wrapper.findAll('.pager')[0]!.trigger('click')
    await flushPromises()
    expect(api.cases).toHaveBeenLastCalledWith({ page: 1, size: 20, query: 'tenant-a', status: '' })
    expect(api.adminAppeals).toHaveBeenCalledTimes(1)

    await wrapper.findAll('.pager')[1]!.trigger('click')
    await flushPromises()
    expect(api.adminAppeals).toHaveBeenLastCalledWith({ page: 1, size: 20, query: 'tenant-a', status: '' })
    // 翻页只触发分页列表的 cases 调用（size=20），不触发全量概览（size=100）
    const pagedCalls = api.cases.mock.calls.filter((call) => call[0]?.size === 20)
    expect(pagedCalls).toHaveLength(2)
  })

  it('refreshes only the mutated collection', () => {
    expect(contentSafetySource).toMatch(/async function transition[\s\S]*?await loadCases\(\)[\s\S]*?async function resolveSelectedCase/)
    expect(contentSafetySource).toMatch(/async function processAppeal[\s\S]*?await loadAppeals\(\)[\s\S]*?function changeCasePageSize/)
  })

  it('uses server results directly instead of filtering the current page locally', () => {
    expect(contentSafetySource).not.toContain('visibleCases')
    expect(contentSafetySource).not.toContain('visibleAppeals')
  })

  it('keeps loading overlays scoped to their own collection', () => {
    expect(contentSafetySource).toContain('v-loading="caseLoading"')
    expect(contentSafetySource).toContain('v-loading="appealLoading"')
    expect(contentSafetySource).not.toContain('<section class="page-stack" v-loading=')
  })

  it('exposes the administrator withdrawal action only for pending appeals', async () => {
    api.withdrawAdminAppeal.mockResolvedValue({ data: {} } as never)
    const wrapper = mountView()
    await flushPromises()

    const pending = { businessNo: 'AP-0123456789abcdef0123', status: 'PENDING' }
    await (wrapper.vm as unknown as { withdrawAppeal: (item: typeof pending) => Promise<void> })
      .withdrawAppeal(pending)
    await flushPromises()
    expect(api.withdrawAdminAppeal).toHaveBeenCalledWith('AP-0123456789abcdef0123')
    expect(contentSafetySource).toContain('撤回申诉')
    expect(contentSafetySource).toContain("scope.row.status === 'PENDING'")
  })

  it('maps withdrawn appeals to a terminal status', () => {
    expect(contentSafetySource).toContain("WITHDRAWN: '已撤回'")
    expect(contentSafetySource).toContain("if (status === 'WITHDRAWN') return 'info'")
  })

  it('keeps one stable details entry in every case row', () => {
    expect(contentSafetySource).toContain('class="case-row-actions"')
    expect(contentSafetySource).toContain('openCaseDetail(scope.row)')
    expect(contentSafetySource).toContain('记录编号')
    expect(contentSafetySource).toContain('账号状态')
    expect(contentSafetySource).not.toContain('<el-dropdown')
    expect(contentSafetySource).not.toContain('transitionTargets')
    expect(contentSafetySource).not.toContain('handleCaseMenuCommand')
  })

  it('moves review and resolution actions into the detail drawer', () => {
    expect(contentSafetySource).toContain('<el-drawer v-model="detailOpen" title="内容安全详情"')
    expect(contentSafetySource).toContain('开始审核')
    expect(contentSafetySource).toContain('提交审核结论')
    expect(contentSafetySource).toContain('进入账号处置')
    expect(contentSafetySource).toContain('contentSafetyApi.resolve')
    expect(contentSafetySource).toContain("OPEN: '待审核'")
    expect(contentSafetySource).toContain("DISMISSED: '已判定无违规'")
    expect(contentSafetySource).toContain("RESOLVED: '已结案'")
  })
})
