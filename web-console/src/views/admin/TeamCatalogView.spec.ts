import ElementPlus from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import TeamCatalogView from './TeamCatalogView.vue'
import { resetExpertCategoryCache } from './expertCategories'

/** 只桩掉被断言的接口；其余导出沿用真实实现（与 ExpertCatalogView.spec.ts 同一套写法）。 */
const adminApi = vi.hoisted(() => ({
  listAdminSkills: vi.fn(),
  listDeletedAdminSkills: vi.fn(),
  getAdminSkill: vi.fn(),
  createAdminSkill: vi.fn(),
  deleteAdminSkill: vi.fn(),
  restoreAdminSkill: vi.fn(),
  purgeAdminSkill: vi.fn(),
  listSkillCategories: vi.fn(),
}))

const skillsApi = vi.hoisted(() => ({
  listSkills: vi.fn(),
  listNativeClawhubSkills: vi.fn(),
  getSkill: vi.fn(),
  fetchSkillMemberAvatar: vi.fn(),
}))

const routerState = vi.hoisted(() => ({
  route: { query: {} as Record<string, unknown> },
  replace: vi.fn(),
  push: vi.fn(),
}))

vi.mock('../../api/admin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/admin')>()),
  ...adminApi,
}))

vi.mock('../../api/skills', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/skills')>()),
  ...skillsApi,
}))

vi.mock('vue-router', () => ({
  useRoute: () => routerState.route,
  useRouter: () => ({ replace: routerState.replace, push: routerState.push }),
}))

const teamRow = {
  id: 'team-1',
  code: 'demo-expert-team',
  name: '演示专家团',
  nameEn: 'Demo Expert Team',
  category: '演示',
  tags: [],
  summary: '多人协作',
  summaryEn: null,
  description: null,
  descriptionEn: null,
  icon: null,
  iconUrl: null,
  kind: 'TEAM' as const,
  status: 'DRAFT' as const,
  publishedVersion: null,
  latestVersion: null,
  minClientVersion: null,
  sizeBytes: null,
  md5: null,
  sha256: null,
  downloadCount: 0,
  sortWeight: 0,
  rowVersion: 1,
  createdAt: '2026-09-17T02:10:00Z',
  updatedAt: '2026-09-17T03:00:00Z',
  publishedAt: null,
  unpublishedAt: null,
  memberCount: 3,
  versions: [],
}

function expectStandalonePromptKeyword(message: unknown, keyword: string) {
  const vnode = message as {
    children?: Array<{ type?: unknown; children?: unknown; props?: { style?: Record<string, unknown> } }>
  }
  const code = vnode.children?.[1]
  expect(code?.type).toBe('code')
  expect(code?.children).toBe(keyword)
  expect(code?.props?.style?.whiteSpace).toBe('nowrap')
  expect(code?.props?.style?.textAlign).toBe('center')
}

describe('TeamCatalogView（独立专家团页面）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // 词表有模块级缓存：用例之间必须清掉，否则前一个用例的词表会串到下一个
    resetExpertCategoryCache()
    routerState.route.query = {}
    adminApi.listAdminSkills.mockResolvedValue({ items: [teamRow], total: 1, page: 0, size: 20 })
    adminApi.listDeletedAdminSkills.mockResolvedValue({ items: [], total: 0, page: 0, size: 100 })
    adminApi.createAdminSkill.mockResolvedValue({ ...teamRow, id: 'team-new', rowVersion: 1 })
    adminApi.getAdminSkill.mockResolvedValue({ ...teamRow, versions: [] })
    adminApi.listSkillCategories.mockResolvedValue({ kind: 'EXPERT', categories: [] })
    skillsApi.listSkills.mockResolvedValue({ object: 'list', data: [] })
    skillsApi.listNativeClawhubSkills.mockResolvedValue({ object: 'list', items: [] })
  })

  afterEach(() => {
    ElMessage.closeAll()
    document.querySelectorAll('.el-message').forEach((message) => message.remove())
  })

  function mountTeamPage() {
    return mount(TeamCatalogView, { global: { plugins: [ElementPlus] } })
  }

  it('固定以 kind=TEAM 拉取列表，与技能页、专家页互不重叠', async () => {
    const wrapper = mountTeamPage()
    await flushPromises()

    // 列表请求的参数由 SkillCatalogView 统一发出（TeamCatalogView 只是传 kinds="TEAM"）。
    // sort 的默认值是 'created_at'（SkillCatalogView.vue:625 起），这里曾写 ''——
    // 默认排序改成按创建时间倒序之后，这条断言就与组件对不上了。
    expect(adminApi.listAdminSkills).toHaveBeenCalledWith({
      page: 0, size: 20, query: '', status: '', category: '', kind: 'TEAM',
      sort: 'created_at', direction: 'desc',
    })
    expect(wrapper.get('[data-test="skill-kind-demo-expert-team"]').text()).toBe('专家团')
    expect(wrapper.get('[data-test="skill-member-count-demo-expert-team"]').text()).toContain('3')
    wrapper.unmount()
  })

  it('页面标题、新增按钮与搜索框按「专家团」措辞', async () => {
    const wrapper = mountTeamPage()
    await flushPromises()

    expect(wrapper.get('#admin-skills-title').text()).toBe('专家团管理')
    expect(wrapper.get('[data-test="skill-create-open"]').text()).toContain('新增专家团')
    expect(wrapper.find('input[aria-label="搜索专家团"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('专家描述上限收紧后，专家团仍保留 4000 字符上限', async () => {
    const wrapper = mountTeamPage()
    await flushPromises()
    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="skill-form-description"]').attributes('maxlength')).toBe('4000')
    expect(wrapper.get('[data-test="skill-form-description-en"]').attributes('maxlength')).toBe('4000')
    wrapper.unmount()
  })

  it.each([
    ['a stored minimum client version', '2026.7.0', '2026.7.0'],
    ['a legacy empty minimum client version', null, '2026.6.0'],
  ])('prefills %s while editing an expert team', async (_label, detailVersion, expectedVersion) => {
    adminApi.getAdminSkill.mockResolvedValue({
      ...teamRow,
      minClientVersion: detailVersion,
      versions: [],
    })

    const wrapper = mountTeamPage()
    await flushPromises()
    await wrapper.get('[data-test="skill-edit-demo-expert-team"]').trigger('click')
    await flushPromises()

    expect((wrapper.get('[data-test="skill-form-min-client"]').element as HTMLInputElement).value)
      .toBe(expectedVersion)
    wrapper.unmount()
  })

  it('删除确认正文显示可复制的专家团标识', async () => {
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: 'demo-expert-team' } as never)
    const wrapper = mountTeamPage()
    await flushPromises()

    await wrapper.get('[data-test="skill-delete-demo-expert-team"]').trigger('click')
    await flushPromises()

    expectStandalonePromptKeyword(prompt.mock.calls[0]?.[0], 'demo-expert-team')
    expect(adminApi.deleteAdminSkill).toHaveBeenCalledWith('team-1')
    wrapper.unmount()
  })

  it('专家团卡片可勾选批量删除，确认正文给出固定短语', async () => {
    adminApi.listAdminSkills.mockResolvedValue({
      items: [{ ...teamRow, status: 'UNPUBLISHED' }], total: 1, page: 0, size: 20,
    })
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '我确认批量删除' } as never)
    const wrapper = mountTeamPage()
    await flushPromises()

    await wrapper.get('[data-test="skill-select-demo-expert-team"] input').setValue(true)
    await wrapper.get('[data-test="skills-bulk-delete"]').trigger('click')
    await flushPromises()

    expectStandalonePromptKeyword(prompt.mock.calls[0]?.[0], '我确认批量删除')
    expect(prompt.mock.calls[0]?.[1]).toBe('确认批量删除专家团')
    expect(adminApi.deleteAdminSkill).toHaveBeenCalledWith('team-1')
    wrapper.unmount()
  })

  it('新增对话框只能创建「专家团」，并提交 kind=TEAM', async () => {
    const wrapper = mountTeamPage()
    await flushPromises()

    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()

    const kindRadios = wrapper.get('[data-test="skill-form-kind"]')
    expect(kindRadios.text()).toContain('专家团')
    expect(kindRadios.text()).not.toContain('技能')

    await wrapper.get('[data-test="skill-form-code"]').setValue('brand-team')
    await wrapper.get('[data-test="skill-form-name"]').setValue('品牌专家团')
    await wrapper.get('[data-test="skill-form-summary"]').setValue('多人协作产出品牌内容')
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.createAdminSkill).toHaveBeenCalledWith(expect.objectContaining({ kind: 'TEAM' }))
    wrapper.unmount()
  })

  it('表单字段 label 按「专家团」措辞，不残留「技能」', async () => {
    const wrapper = mountTeamPage()
    await flushPromises()

    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()

    const labels = wrapper.get('.skill-form').findAll('.el-form-item__label').map((el) => el.text()).join('|')
    expect(labels).toContain('专家团标识')
    expect(labels).toContain('专家团名称（中文，必填）')
    expect(labels).not.toContain('技能标识')
    expect(labels).not.toContain('技能名称')
    expect(wrapper.find('[data-test="skill-form-example-image"]').exists()).toBe(false)
    wrapper.unmount()
  })

  // 需求：三份 scope 文案都要如实说明「管理端可下载」—— 漏改一份该页就会自相矛盾。
  // Skill / 专家页在各自 spec 里用抽屉断言，专家团页在这里补上。
  it('下载接口说明不再声称「管理端不提供浏览器下载」', async () => {
    const wrapper = mountTeamPage()
    await flushPromises()

    await wrapper.get('[data-test="skill-detail-demo-expert-team"]').trigger('click')
    await flushPromises()

    const text = wrapper.get('[data-test="skill-client-endpoints"]').text()
    expect(text).toContain('专家团包由桌面软件端优先携带登录 JWT 拉取')
    expect(text).toContain('管理端也可在浏览器直接下载')
    expect(text).not.toContain('不提供浏览器下载')
    wrapper.unmount()
  })
})
