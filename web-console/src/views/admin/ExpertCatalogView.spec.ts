import ElementPlus from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import ExpertCatalogView from './ExpertCatalogView.vue'
import SkillCatalogView from './SkillCatalogView.vue'
import { resetExpertCategoryCache } from './expertCategories'

/** 只桩掉被断言的接口；其余导出沿用真实实现（与 SkillCatalogView.spec.ts 同一套写法）。 */
const adminApi = vi.hoisted(() => ({
  listAdminSkills: vi.fn(),
  listDeletedAdminSkills: vi.fn(),
  getAdminSkill: vi.fn(),
  fetchAdminExpertPresentation: vi.fn(),
  createAdminSkill: vi.fn(),
  createAdminExpertAggregate: vi.fn(),
  downloadAdminSkillPackage: vi.fn(),
  generateAdminSkillPortableRevision: vi.fn(),
  deleteAdminSkill: vi.fn(),
  restoreAdminSkill: vi.fn(),
  purgeAdminSkill: vi.fn(),
  listSkillCategories: vi.fn(),
  createSkillCategory: vi.fn(),
  updateSkillCategory: vi.fn(),
  deleteSkillCategory: vi.fn(),
  fetchSkillCategoryImage: vi.fn(),
  uploadSkillCategoryImage: vi.fn(),
  deleteSkillCategoryImage: vi.fn(),
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

/** 专家页只需要列表能渲染：一条专家 + 一条专家团。 */
const expertRow = {
  id: 'expert-1',
  code: 'demo-expert',
  name: '演示专家',
  nameEn: 'Demo Expert',
  category: '演示',
  tags: [],
  summary: '单人专家',
  summaryEn: null,
  description: null,
  descriptionEn: null,
  icon: null,
  iconUrl: null,
  kind: 'EXPERT' as const,
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
  createdAt: '2026-09-14T02:10:00Z',
  updatedAt: '2026-09-14T03:00:00Z',
  publishedAt: null,
  unpublishedAt: null,
  memberCount: 0,
  versions: [],
}

const teamRow = {
  ...expertRow,
  id: 'team-1',
  code: 'demo-expert-team',
  name: '演示专家团',
  kind: 'TEAM' as const,
  memberCount: 3,
}

/** 两个 describe 共用：挂载专家页（完整渲染，子组件内的 data-test 也能查到） */
function mountExpertPage() {
  return mount(ExpertCatalogView, { global: { plugins: [ElementPlus] } })
}

async function setRichText(wrapper: ReturnType<typeof mountExpertPage>, label: string, value: string) {
  const field = wrapper.get(`[contenteditable="true"][role="textbox"][aria-label="${label}"]`)
  ;(field.element as HTMLElement).textContent = value
  await field.trigger('input')
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

describe('ExpertCatalogView（独立专家页面）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // 词表有模块级缓存：用例之间必须清掉，否则前一个用例的词表会串到下一个
    resetExpertCategoryCache()
    routerState.route.query = {}
    adminApi.listAdminSkills.mockResolvedValue({ items: [expertRow, teamRow], total: 2, page: 0, size: 20 })
    adminApi.listDeletedAdminSkills.mockResolvedValue({
      items: [{
        id: 'expert-deleted', code: 'deleted-expert', name: '已删除专家', nameEn: null,
        category: '演示', kind: 'EXPERT', deletedAt: '2026-09-19T03:00:00Z',
        expiresAt: '2026-10-19T03:00:00Z',
      }],
      total: 1, page: 0, size: 100,
    })
    adminApi.restoreAdminSkill.mockResolvedValue(expertRow)
    adminApi.purgeAdminSkill.mockResolvedValue(undefined)
    adminApi.createAdminSkill.mockResolvedValue({ ...expertRow, id: 'expert-new', rowVersion: 1 })
    adminApi.createAdminExpertAggregate.mockResolvedValue({
      skillId: 'expert-new',
      skill: { ...expertRow, id: 'expert-new', rowVersion: 1 },
      presentation: {
        title: '品牌专家', titleEn: null, summary: '帮助品牌生成内容', summaryEn: null,
        description: null, descriptionEn: null, taskTypes: [], taskTypesEn: [],
        usageGuide: null, usageGuideEn: null, examplePrompts: [], examplePromptsEn: [],
        preview: null, previewEn: null, assets: {}, portableRevision: 0, rowVersion: 1,
        updatedAt: null,
      },
    })
    adminApi.getAdminSkill.mockResolvedValue({ ...expertRow, versions: [] })
    adminApi.fetchAdminExpertPresentation.mockRejectedValue({ response: { status: 404 } })
    adminApi.listSkillCategories.mockResolvedValue({
      kind: 'EXPERT',
      categories: [
        { id: 'c1', kind: 'EXPERT', code: 'content-creation', name: '内容创作',
          nameEn: 'Content Creation', sortOrder: 10, enabled: true, usageCount: 0 },
        { id: 'c2', kind: 'EXPERT', code: 'industry-advisory', name: '行业顾问',
          nameEn: 'Industry Advisory', sortOrder: 15, enabled: true, usageCount: 0 },
      ],
    })
    adminApi.createSkillCategory.mockResolvedValue({
      id: 'cat-new', kind: 'EXPERT', code: 'new-cat', name: '新分类',
      nameEn: null, sortOrder: 1, enabled: true, usageCount: 0,
    })
    adminApi.updateSkillCategory.mockResolvedValue({
      id: 'c1', kind: 'EXPERT', code: 'content-creation', name: '内容生产',
      nameEn: 'Content Creation', sortOrder: 10, enabled: true, usageCount: 0,
    })
    adminApi.deleteSkillCategory.mockResolvedValue(undefined)
    adminApi.uploadSkillCategoryImage.mockResolvedValue({
      id: 'c1', kind: 'EXPERT', code: 'content-creation', name: '内容创作',
      nameEn: 'Content Creation', sortOrder: 10, enabled: true, usageCount: 0,
    })
    skillsApi.listSkills.mockResolvedValue({ object: 'list', data: [] })
    skillsApi.listNativeClawhubSkills.mockResolvedValue({ object: 'list', items: [] })
  })

  afterEach(() => {
    ElMessage.closeAll()
    document.querySelectorAll('.el-message').forEach((message) => message.remove())
  })

  it('固定以 kind=EXPERT 拉取列表，只展示专家', async () => {
    const wrapper = mountExpertPage()
    await flushPromises()

    expect(adminApi.listAdminSkills).toHaveBeenCalledWith({
      page: 0, size: 20, query: '', status: '', category: '', kind: 'EXPERT', sort: 'created_at', direction: 'desc',
    })
    expect(wrapper.get('[data-test="skill-kind-demo-expert"]').text()).toBe('专家')
    wrapper.unmount()
  })

  it('专家列表仅启用有依赖或被引用的关系按钮', async () => {
    adminApi.listAdminSkills.mockResolvedValue({
      items: [
        { ...expertRow, dependencyCount: 1, dependentCount: 0 },
        { ...expertRow, id: 'expert-empty', code: 'expert-empty', dependencyCount: 0, dependentCount: 0 },
      ],
      total: 2, page: 0, size: 20,
    })
    const wrapper = mountExpertPage()
    await flushPromises()

    expect(wrapper.get('[data-test="skill-relations-demo-expert"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-test="skill-relations-expert-empty"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('页面标题与新增按钮按「专家」措辞，且不再提供类型筛选下拉', async () => {
    const wrapper = mountExpertPage()
    await flushPromises()

    expect(wrapper.get('#admin-skills-title').text()).toBe('专家管理')
    expect(wrapper.get('[data-test="skill-create-open"]').text()).toContain('新增专家')
    expect(wrapper.find('[data-test="skills-kind"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('新增对话框只能创建「专家」，并提交 kind=EXPERT', async () => {
    const wrapper = mountExpertPage()
    await flushPromises()

    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()

    // 专家页的类型由路由作用域锁定；WYSIWYG 不再呈现一个可修改的类型控件。
    expect(wrapper.find('[data-test="skill-form-kind"]').exists()).toBe(false)

    await wrapper.get('[data-test="expert-advanced-code"]').setValue('brand-expert')
    await setRichText(wrapper, '专家名称', '品牌专家')
    await setRichText(wrapper, '专家描述', '帮助品牌生成内容')
    await setRichText(wrapper, '使用说明', '请提供品牌、受众和发布渠道')
    await wrapper.get('input[aria-label="新增任务类型"]').setValue('品牌内容')
    await wrapper.get('input[aria-label="新增任务类型"]').trigger('keydown', { key: 'Enter' })
    await wrapper.get('[data-test="expert-add-prompt"]').trigger('click')
    await setRichText(wrapper, '示例 1', '为新品写一条社媒文案')
    await setRichText(wrapper, '示例预览标题', '品牌内容示例')
    await setRichText(wrapper, '示例请求', '写一条新品上市文案')
    await setRichText(wrapper, '示例输出标题', '示例输出')
    await wrapper.get('.expert-wysiwyg__preview-output > button').trigger('click')
    await setRichText(wrapper, '示例输出段落 1', '新品登场，从一次真实体验开始。')
    await wrapper.get('[data-test="expert-advanced-summary"]').setValue('帮助品牌生成内容')
    expect(wrapper.get('[data-test="expert-wysiwyg-dirty"]').text()).toContain('未保存')
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.createAdminExpertAggregate).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'EXPERT', code: 'brand-expert' }),
      expect.objectContaining({ icon: null, illustration: null, exampleImage: null }),
    )
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('专家管理的客户端预览在技能区块展示已上架原生 ClawHub 卡片', async () => {
    skillsApi.listNativeClawhubSkills.mockResolvedValue({
      object: 'list',
      items: [{
        code: 'native-copywriter',
        name: '原生文案助手',
        description: '来自 ClawHub 的文案技能',
        descriptionEn: null,
        summary: '文案技能',
        summaryEn: null,
        status: 'PUBLISHED',
        version: '1.2.3',
        packageFormat: 'CLAWHUB_SKILL',
        sha256: 'a'.repeat(64),
        sizeBytes: 1024,
        owner: 'publisher',
        slug: 'copywriter',
        downloadUrl: '/v1/native-clawhub/skills/native-copywriter/package',
      }],
    })
    const wrapper = mountExpertPage()
    await flushPromises()

    await wrapper.findAll('.el-tabs__item').at(1)?.trigger('click')
    await flushPromises()

    expect(skillsApi.listNativeClawhubSkills).toHaveBeenCalledTimes(1)
    const skillSection = wrapper.get('[data-test="skills-grid-SKILL"]')
    expect(skillSection.get('[data-test="skill-card-native-copywriter"]').text())
      .toContain('原生文案助手')
    expect(skillSection.get('[data-test="skill-card-native-native-copywriter"]').text())
      .toBe('原生技能')
    expect(wrapper.find('[data-test="skills-grid-EXPERT"] [data-test="skill-card-native-copywriter"]').exists())
      .toBe(false)
    wrapper.unmount()
  })

  it('专家描述超过后端 1024 字符时先拦截，提交错误指出具体字段', async () => {
    adminApi.createAdminExpertAggregate.mockRejectedValueOnce({
      response: { status: 422, data: {
        code: 'skill_summary_en_invalid',
        message: 'internal storage key and SQL details',
        details: { field: 'summaryEn' },
      } },
    })
    const wrapper = mountExpertPage()
    await flushPromises()
    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-test="expert-advanced-code"]').setValue('brand-expert')
    await setRichText(wrapper, '专家名称', '品牌专家')
    await setRichText(wrapper, '专家描述', '帮助品牌生成内容')
    await setRichText(wrapper, '使用说明', '请提供品牌、受众和发布渠道')
    await wrapper.get('input[aria-label="新增任务类型"]').setValue('品牌内容')
    await wrapper.get('input[aria-label="新增任务类型"]').trigger('keydown', { key: 'Enter' })
    await wrapper.get('[data-test="expert-add-prompt"]').trigger('click')
    await setRichText(wrapper, '示例 1', '为新品写一条社媒文案')
    await setRichText(wrapper, '示例预览标题', '品牌内容示例')
    await setRichText(wrapper, '示例请求', '写一条新品上市文案')
    await setRichText(wrapper, '示例输出标题', '示例输出')
    await wrapper.get('.expert-wysiwyg__preview-output > button').trigger('click')
    await setRichText(wrapper, '示例输出段落 1', '新品登场，从一次真实体验开始。')
    await wrapper.get('[data-test="expert-advanced-summary"]').setValue('帮助品牌生成内容')

    const catalog = wrapper.getComponent(SkillCatalogView)
    const setup = (catalog.vm.$ as unknown as {
      setupState: { form: { description: string }; errorMessage: string }
    }).setupState
    // 老记录或包导入可绕过富文本输入框的 maxlength；提交前仍需校验整份表单。
    setup.form.description = '中'.repeat(1025)
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()
    expect(adminApi.createAdminExpertAggregate).not.toHaveBeenCalled()
    expect(setup.errorMessage).toContain('详细说明最多 1024 个字符')

    setup.form.description = '帮助品牌生成内容'
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()
    expect(adminApi.createAdminExpertAggregate).toHaveBeenCalledTimes(1)
    expect(setup.errorMessage).toContain('问题位置：英文卡片摘要')
    expect(setup.errorMessage).toContain('不能超过 512 个字符')
    expect(setup.errorMessage).not.toContain('internal storage key')
    wrapper.unmount()
  })

  it('含图完整包中的专家图片超额时提示各自的真实上限', async () => {
    const wrapper = mountExpertPage()
    await flushPromises()
    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()
    const catalog = wrapper.getComponent(SkillCatalogView)
    const setup = (catalog.vm.$ as unknown as {
      setupState: { stageImportedImages: (images: Record<string, File>) => void }
    }).setupState

    for (const [role, bytes, limit] of [
      ['icon', 128 * 1024 + 1, '128 KiB'],
      ['illustration', 640 * 1024 + 1, '640 KiB'],
      ['example', 512 * 1024 + 1, '512 KiB'],
    ] as const) {
      expect(() => setup.stageImportedImages({
        [role]: new File([new Uint8Array(bytes)], `${role}.png`, { type: 'image/png' }),
      })).toThrow(limit)
    }
    wrapper.unmount()
  })

  it('单人专家包不展示由运行 Agent 生成的花名册', async () => {
    adminApi.listAdminSkills.mockResolvedValue({
      items: [{ ...expertRow, memberCount: 1 }], total: 1, page: 0, size: 20,
    })
    adminApi.getAdminSkill.mockResolvedValue({
      ...expertRow,
      memberCount: 1,
      members: [{
        id: 'member-main', memberKey: 'main', name: 'main', nameEn: 'main',
        profession: null, professionEn: null, role: 'LEAD', sortOrder: 0, avatarUrl: null,
      }],
      versions: [],
    })
    const wrapper = mountExpertPage()
    await flushPromises()

    expect(wrapper.find('[data-test="skill-member-count-demo-expert"]').exists()).toBe(false)
    await wrapper.get('[data-test="skill-detail-demo-expert"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="skill-roster"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-drawer-member-count"]').exists()).toBe(false)
    expect(skillsApi.fetchSkillMemberAvatar).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('专家详情导入区同时提供 YCPS ZIP 与文件夹入口', async () => {
    const wrapper = mountExpertPage()
    await flushPromises()

    await wrapper.get('[data-test="skill-detail-demo-expert"]').trigger('click')
    await flushPromises()

    const panel = wrapper.get('[data-test="portable-import-panel"]')
    expect(panel.get('[data-test="portable-import-file"]').attributes('accept')).toContain('.ycps.zip')
    expect(panel.get('[data-test="portable-import-folder"]').attributes()).toEqual(expect.objectContaining({
      directory: '',
      multiple: '',
      webkitdirectory: '',
    }))
    expect(panel.get('[data-test="portable-import-folder-pick"]').text()).toContain('选择文件夹')
    wrapper.unmount()
  })

  it('keeps the local example-image preview after aggregate creation returns an authenticated asset path', async () => {
    adminApi.createAdminExpertAggregate.mockResolvedValue({
      skillId: 'expert-new',
      skill: {
        ...expertRow,
        id: 'expert-new',
        rowVersion: 1,
        exampleImageUrl: '/console/gateway/admin/skills/expert-new/example-image',
      },
      presentation: {
        title: '品牌专家', titleEn: null, summary: '帮助品牌生成内容', summaryEn: null,
        description: '帮助品牌生成内容', descriptionEn: null,
        taskTypes: ['品牌内容'], taskTypesEn: [],
        usageGuide: '请提供品牌、受众和发布渠道', usageGuideEn: null,
        examplePrompts: ['为新品写一条社媒文案'], examplePromptsEn: [],
        preview: {
          heading: '品牌内容示例', request: '写一条新品上市文案',
          outputTitle: '示例输出', outputParagraphs: ['新品登场，从一次真实体验开始。'],
        },
        previewEn: null,
        assets: { exampleImage: '/console/gateway/admin/skills/expert-new/example-image' },
        portableRevision: 0, rowVersion: 1, updatedAt: null,
      },
    })

    const wrapper = mountExpertPage()
    await flushPromises()
    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-test="expert-advanced-code"]').setValue('brand-expert')
    await setRichText(wrapper, '专家名称', '品牌专家')
    await setRichText(wrapper, '专家描述', '帮助品牌生成内容')
    await setRichText(wrapper, '使用说明', '请提供品牌、受众和发布渠道')
    await wrapper.get('input[aria-label="新增任务类型"]').setValue('品牌内容')
    await wrapper.get('input[aria-label="新增任务类型"]').trigger('keydown', { key: 'Enter' })
    await wrapper.get('[data-test="expert-add-prompt"]').trigger('click')
    await setRichText(wrapper, '示例 1', '为新品写一条社媒文案')
    await setRichText(wrapper, '示例预览标题', '品牌内容示例')
    await setRichText(wrapper, '示例请求', '写一条新品上市文案')
    await setRichText(wrapper, '示例输出标题', '示例输出')
    await wrapper.get('.expert-wysiwyg__preview-output > button').trigger('click')
    await setRichText(wrapper, '示例输出段落 1', '新品登场，从一次真实体验开始。')
    await wrapper.get('[data-test="expert-advanced-summary"]').setValue('帮助品牌生成内容')

    const catalog = wrapper.getComponent(SkillCatalogView)
    const setupState = (catalog.vm.$ as unknown as {
      setupState: { exampleImagePreviewUrl: string }
    }).setupState
    setupState.exampleImagePreviewUrl = 'blob:pending-example-image'

    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="expert-preview-example-image"] img').attributes('src'))
      .toBe('blob:pending-example-image')
    expect(wrapper.find('[data-test="skill-form-submit"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('新增专家对话框的表单字段与标题按「专家」措辞，而非「技能」', async () => {
    const wrapper = mountExpertPage()
    await flushPromises()

    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()

    // 对话框标题应是「新增专家」，而不是沿用 Skill 页的「新增 Skill」
    expect(wrapper.get('.el-dialog__title').text()).toBe('新增专家')

    // 主画布直接编辑专家详情，运营字段收口到高级设置。
    const form = wrapper.get('.skill-form')
    const labels = form.findAll('.el-form-item__label').map((el) => el.text())
    const joined = labels.join('|')
    expect(joined).toContain('编码')
    expect(joined).not.toContain('运营标签')
    expect(joined).toContain('卡片摘要（中文）')
    expect(joined).not.toContain('技能标识')
    expect(joined).not.toContain('技能名称')
    expect(joined).not.toContain('技能简介')
    expect(form.find('[data-test="expert-wysiwyg-canvas"]').exists()).toBe(true)
    expect(form.find('[contenteditable="true"][role="textbox"][aria-label="专家名称"]').exists()).toBe(true)
    expect(form.find('[contenteditable="true"][role="textbox"][aria-label="专家描述"]').exists()).toBe(true)
    expect(form.find('[data-test="expert-advanced-category"]').exists()).toBe(true)
    expect(form.find('[data-test="expert-block-illustration"]').exists()).toBe(true)
    expect(form.find('[data-test="expert-block-preview-input"]').exists()).toBe(true)
    expect(form.find('[data-test="expert-block-example-image"]').exists()).toBe(false)
    expect(form.text()).toContain('功能示例图')
    wrapper.unmount()
  })

  it('keeps operational ycps tags separate from the loaded Expert task types', async () => {
    const loaded = {
      ...expertRow,
      tags: ['ycps'],
      taskTypes: ['办公文件处理'],
      taskTypesEn: ['Office-file processing'],
      description: '办公文件处理专家',
      usageGuide: '请提供待处理文件。',
      examplePrompts: ['整理这份文件'],
      examplePreview: {
        heading: '文件整理', request: '整理这份文件', outputTitle: '结果', outputParagraphs: ['已整理'],
      },
      versions: [],
    }
    adminApi.listAdminSkills.mockResolvedValue({
      items: [{ ...expertRow, tags: ['ycps'] }], total: 1, page: 0, size: 20,
    })
    adminApi.getAdminSkill.mockResolvedValue(loaded)
    adminApi.fetchAdminExpertPresentation.mockResolvedValue({
      skillId: loaded.id,
      code: loaded.code,
      kind: 'EXPERT',
      presentation: {
        title: loaded.name,
        titleEn: loaded.nameEn,
        summary: loaded.summary,
        summaryEn: loaded.summaryEn,
        description: loaded.description,
        descriptionEn: loaded.descriptionEn,
        taskTypes: loaded.taskTypes,
        taskTypesEn: loaded.taskTypesEn,
        usageGuide: loaded.usageGuide,
        usageGuideEn: null,
        examplePrompts: loaded.examplePrompts,
        examplePromptsEn: [],
        preview: loaded.examplePreview,
        previewEn: null,
        assets: {},
        portableRevision: 0,
        rowVersion: loaded.rowVersion,
        updatedAt: null,
      },
    })

    const wrapper = mountExpertPage()
    await flushPromises()
    await wrapper.get('[data-test="skill-edit-demo-expert"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('.expert-wysiwyg__tags').text()).toContain('办公文件处理')
    expect(wrapper.find('[data-test="expert-advanced-tags"]').exists()).toBe(false)
    expect(wrapper.get('.expert-wysiwyg__tags').text()).not.toContain('ycps')
    wrapper.unmount()
  })

  it.each([
    ['a stored minimum client version', '2026.7.0', '2026.7.0'],
    ['a legacy empty minimum client version', null, '2026.6.0'],
  ])('prefills %s while editing an expert', async (_label, detailVersion, expectedVersion) => {
    adminApi.getAdminSkill.mockResolvedValue({
      ...expertRow,
      minClientVersion: detailVersion,
      versions: [],
    })

    const wrapper = mountExpertPage()
    await flushPromises()
    await wrapper.get('[data-test="skill-edit-demo-expert"]').trigger('click')
    await flushPromises()

    expect((wrapper.get('[data-test="expert-advanced-min-client"]').element as HTMLInputElement).value)
      .toBe(expectedVersion)
    wrapper.unmount()
  })

  it('keeps a minimum client version edit made during loading marked as unsaved', async () => {
    let resolveDetail!: (value: Record<string, unknown>) => void
    adminApi.getAdminSkill.mockImplementationOnce(() => new Promise((resolve) => {
      resolveDetail = resolve
    }))
    adminApi.fetchAdminExpertPresentation.mockResolvedValue({
      skillId: expertRow.id,
      code: expertRow.code,
      kind: 'EXPERT',
      presentation: {
        title: expertRow.name,
        titleEn: expertRow.nameEn,
        summary: expertRow.summary,
        summaryEn: null,
        description: null,
        descriptionEn: null,
        usageGuide: null,
        usageGuideEn: null,
        taskTypes: [],
        taskTypesEn: [],
        examplePrompts: [],
        examplePromptsEn: [],
        preview: null,
        previewEn: null,
        assets: {},
        portableRevision: 0,
        rowVersion: expertRow.rowVersion,
        updatedAt: null,
      },
    })

    const wrapper = mountExpertPage()
    await flushPromises()
    await wrapper.get('[data-test="skill-edit-demo-expert"]').trigger('click')
    await wrapper.get('[data-test="expert-advanced-min-client"]').setValue('2027.3.0')
    resolveDetail({ ...expertRow, minClientVersion: '2026.7.0', versions: [] })
    await flushPromises()

    expect((wrapper.get('[data-test="expert-advanced-min-client"]').element as HTMLInputElement).value)
      .toBe('2027.3.0')
    expect(wrapper.get('[data-test="expert-wysiwyg-dirty"]').text()).toContain('未保存')
    wrapper.unmount()
  })

  it('列表搜索输入框的 aria-label 也按专家页措辞', async () => {
    const wrapper = mountExpertPage()
    await flushPromises()

    const search = wrapper.find('input[aria-label="搜索专家"]')
    expect(search.exists()).toBe(true)
    expect(wrapper.find('input[aria-label="搜索技能"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('删除确认正文显示可复制的专家标识', async () => {
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: 'demo-expert' } as never)
    const wrapper = mountExpertPage()
    await flushPromises()

    await wrapper.get('[data-test="skill-delete-demo-expert"]').trigger('click')
    await flushPromises()

    expectStandalonePromptKeyword(prompt.mock.calls[0]?.[0], 'demo-expert')
    expect(adminApi.deleteAdminSkill).toHaveBeenCalledWith('expert-1')
    wrapper.unmount()
  })

  it('专家回收站按 EXPERT 隔离，并可直接批量恢复', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm')
    const prompt = vi.spyOn(ElMessageBox, 'prompt')
    const wrapper = mountExpertPage()
    await flushPromises()

    await wrapper.get('[data-test="skills-recycle-open"]').trigger('click')
    await flushPromises()
    expect(adminApi.listDeletedAdminSkills).toHaveBeenCalledWith({
      page: 0, size: 100, query: '', kind: 'EXPERT',
    })
    await wrapper.get('[data-test="skills-recycle-select-deleted-expert"] input').setValue(true)
    await wrapper.get('[data-test="skills-recycle-restore"]').trigger('click')
    await flushPromises()

    expect(adminApi.restoreAdminSkill).toHaveBeenCalledWith('expert-deleted')
    expect(confirm).not.toHaveBeenCalled()
    expect(prompt).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('专家卡片可勾选批量删除，确认正文给出固定短语', async () => {
    adminApi.listAdminSkills.mockResolvedValue({
      items: [{ ...expertRow, status: 'UNPUBLISHED' }], total: 1, page: 0, size: 20,
    })
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '我确认批量删除' } as never)
    const wrapper = mountExpertPage()
    await flushPromises()

    await wrapper.get('[data-test="skill-select-demo-expert"] input').setValue(true)
    await wrapper.get('[data-test="skills-bulk-delete"]').trigger('click')
    await flushPromises()

    expectStandalonePromptKeyword(prompt.mock.calls[0]?.[0], '我确认批量删除')
    expect(prompt.mock.calls[0]?.[1]).toBe('确认批量删除专家')
    expect(adminApi.deleteAdminSkill).toHaveBeenCalledWith('expert-1')
    wrapper.unmount()
  })

  it('专家只保留一个明确包含三类图片的 YCPS 1.1 下载入口', async () => {
    const wrapper = mountExpertPage()
    await flushPromises()

    await wrapper.get('[data-test="skill-detail-demo-expert"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="skill-drawer-download"]').text())
      .toContain('下载 YCPS 1.1 包（包含图片）')
    expect(wrapper.find('[data-test="skill-drawer-download-toggle"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-drawer-download-menu"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('专家唯一下载入口即使线上已是 YCPS 1.1 仍幂等生成并下载精确含图修订包', async () => {
    const createObjectURL = vi.fn(() => 'blob:expert-portable')
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL: vi.fn() })
    const downloads: Array<{ href: string; fileName: string }> = []
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      downloads.push({ href: this.href, fileName: this.download })
    })
    const online = {
      id: 'expert-version-1', version: '1.1.0', revision: 1, status: 'PUBLISHED',
      packageFormat: 'YCPS', ycpsVersion: '1.1.0', packageKind: 'EXPERT',
      sizeBytes: 100, md5: null, sha256: null, manifestSha256: null,
      dependencyLockSha256: null, scanStatus: 'PASSED', sealed: true,
      downloadCount: 0, createdAt: '2026-09-14T03:00:00Z', publishedAt: '2026-09-14T03:00:00Z',
    }
    const portable = { ...online, id: 'expert-version-portable', revision: 2, ycpsVersion: '1.1.0', status: 'READY' }
    adminApi.listAdminSkills.mockResolvedValue({
      items: [{ ...expertRow, publishedVersion: '1.1.0' }], total: 1, page: 0, size: 20,
    })
    adminApi.getAdminSkill.mockResolvedValue({ ...expertRow, publishedVersion: '1.1.0', versions: [online] })
    adminApi.generateAdminSkillPortableRevision.mockResolvedValue(portable)
    adminApi.downloadAdminSkillPackage.mockResolvedValue({
      blob: new Blob(['portable-ycps']), fileName: 'demo-expert-1.1.0.ycps.zip', md5: null, sha256: null,
    })

    const wrapper = mountExpertPage()
    await flushPromises()
    await wrapper.get('[data-test="skill-detail-demo-expert"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="skill-version-portable-revision-1.1.0"]').exists()).toBe(false)
    await wrapper.get('[data-test="skill-drawer-download"]').trigger('click')
    await flushPromises()

    expect(adminApi.generateAdminSkillPortableRevision).toHaveBeenCalledWith('expert-1', 'expert-version-1')
    expect(adminApi.downloadAdminSkillPackage).toHaveBeenCalledWith('expert-1', 'expert-version-portable')
    expect(createObjectURL).toHaveBeenCalled()
    expect(downloads).toEqual([{ href: 'blob:expert-portable', fileName: 'demo-expert-1.1.0.ycps.zip' }])
    wrapper.unmount()
    click.mockRestore()
    vi.unstubAllGlobals()
  })

  // 需求1：专家页列表改为「二次分类卡片」，一级=类型、二级=业务分类
  it('按二级分类把专家渲染成分组卡片，而不是表格', async () => {
    adminApi.listAdminSkills.mockResolvedValue({
      items: [
        { ...expertRow, code: 'content-writing-expert', name: '内容创作', category: '内容创作' },
        { ...expertRow, code: 'pet-care', name: '萌宠管家', category: '行业顾问' },
        { ...expertRow, code: 'sales-analysis-expert', name: '销售分析', category: '内容创作' },
      ],
      total: 3, page: 0, size: 20,
    })
    const wrapper = mountExpertPage()
    await flushPromises()

    // 不再渲染表格
    expect(wrapper.find('[data-test="skills-table"]').exists()).toBe(false)

    // 二级分类成为分组标题
    const groups = wrapper.findAll('[data-test^="skills-category-group-"]')
    expect(groups).toHaveLength(2)
    expect(wrapper.get('[data-test="skills-category-group-content-creation"]').text())
      .toContain('内容创作')
    expect(wrapper.get('[data-test="skills-category-group-industry-advisory"]').text())
      .toContain('行业顾问')

    // 卡片仍带类型标签与原有操作入口
    expect(wrapper.get('[data-test="skill-kind-content-writing-expert"]').text()).toBe('专家')
    expect(wrapper.find('[data-test="skill-edit-pet-care"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skill-detail-pet-care"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('二级分类筛选是本地过滤，不重复请求列表', async () => {
    adminApi.listAdminSkills.mockResolvedValue({
      items: [
        { ...expertRow, code: 'content-writing-expert', category: '内容创作' },
        { ...expertRow, code: 'pet-care', category: '行业顾问' },
      ],
      total: 2, page: 0, size: 20,
    })
    const wrapper = mountExpertPage()
    await flushPromises()
    expect(adminApi.listAdminSkills).toHaveBeenCalledTimes(1)

    await wrapper.get('[data-test="skills-category-chip-content-creation"]').trigger('click')
    await flushPromises()

    // 只保留所选分类的卡片，且列表请求没有因为本地筛选而增加
    expect(wrapper.find('[data-test="skills-category-group-content-creation"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skills-category-group-industry-advisory"]').exists()).toBe(false)
    expect(adminApi.listAdminSkills).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  // 需求2/3：分类词表由后端管理，管理对话框可增删
  it('分类下拉与分组使用后端词表，并支持在管理对话框内新增', async () => {
    adminApi.listSkillCategories.mockResolvedValue({
      kind: 'EXPERT',
      categories: [
        { id: 'c1', kind: 'EXPERT', code: 'content-creation', name: '内容创作',
          nameEn: 'Content Creation', sortOrder: 10, enabled: true, usageCount: 2 },
        { id: 'c2', kind: 'EXPERT', code: 'industry-advisory', name: '行业顾问',
          nameEn: 'Industry Advisory', sortOrder: 15, enabled: true, usageCount: 1 },
      ],
    })
    const wrapper = mountExpertPage()
    await flushPromises()

    // 词表按作用域拉取（专家页 = EXPERT）
    expect(adminApi.listSkillCategories).toHaveBeenCalledWith('EXPERT')

    // 分类下拉用后端词表（在新增对话框内）
    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()
    const select = wrapper.getComponent('[data-test="expert-advanced-category"]')
    const optionLabels = select.findAllComponents({ name: 'ElOption' })
      .map((option) => option.props('label'))
    expect(optionLabels).toEqual(['内容创作', '行业顾问'])
    await wrapper.get('[data-test="skill-form-cancel"]').trigger('click')
    await flushPromises()

    // 管理对话框列出分类与占用计数，占用中的分类禁用删除
    await wrapper.get('[data-test="skills-category-manage"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="category-manage-count-content-creation"]').text())
      .toContain('2')
    expect(wrapper.find('[data-test="category-manage-delete-content-creation"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="category-manage-delete-content-creation"]').attributes('disabled'))
      .toBeDefined()
    // 封面只属于专家分类：专家页在修改态里提供换封面入口
    await wrapper.get('[data-test="category-manage-edit-content-creation"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="category-form-cover"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="category-form-cover-file"]').exists()).toBe(true)
    // 回到新增态，下面的新增断言才走 A23 而不是 A25
    await wrapper.get('[data-test="category-form-cancel-edit"]').trigger('click')
    await flushPromises()

    // 新增分类走 A23
    await wrapper.get('[data-test="category-form-name"]').setValue('自建分类')
    await wrapper.get('[data-test="category-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.createSkillCategory).toHaveBeenCalledWith({
      kind: 'EXPERT', name: '自建分类', nameEn: null,
    })
    wrapper.unmount()
  })

  // 需求：已有分类可以修改分类名，并把该分类下的技能归属同步改掉（A25）
  it('修改分类名走 A25，预填原名并在改完后失效词表缓存', async () => {
    adminApi.listSkillCategories.mockResolvedValue({
      kind: 'EXPERT',
      categories: [
        { id: 'c1', kind: 'EXPERT', code: 'content-creation', name: '内容创作',
          nameEn: 'Content Creation', sortOrder: 10, enabled: true, usageCount: 0 },
      ],
    })
    adminApi.updateSkillCategory.mockResolvedValue({
      id: 'c1', kind: 'EXPERT', code: 'content-creation', name: '内容生产',
      nameEn: 'Content Creation', sortOrder: 10, enabled: true, usageCount: 0,
    })
    const wrapper = mountExpertPage()
    await flushPromises()

    await wrapper.get('[data-test="skills-category-manage"]').trigger('click')
    await flushPromises()

    // 修改按钮存在于每一条分类上
    await wrapper.get('[data-test="category-manage-edit-content-creation"]').trigger('click')
    await flushPromises()

    // 预填当前中文名与英文名；code 不出现在表单里（稳定标识不可改）
    expect((wrapper.get('[data-test="category-form-name"]').element as HTMLInputElement).value)
      .toBe('内容创作')
    expect((wrapper.get('[data-test="category-form-name-en"]').element as HTMLInputElement).value)
      .toBe('Content Creation')

    await wrapper.get('[data-test="category-form-name"]').setValue('内容生产')
    await wrapper.get('[data-test="category-form-submit"]').trigger('click')
    await flushPromises()

    // 走 A25：code 在路径上，body 只带新名称；包归类由后端在同一事务里迁移
    expect(adminApi.updateSkillCategory).toHaveBeenCalledWith('EXPERT', 'content-creation', {
      name: '内容生产', nameEn: 'Content Creation',
    })
    // 改名后必须重新拉词表，否则列表还是旧名
    expect(adminApi.listSkillCategories).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('修改分类不改动时不提交，未填名称时先本地拦截', async () => {
    adminApi.listSkillCategories.mockResolvedValue({
      kind: 'EXPERT',
      categories: [
        { id: 'c1', kind: 'EXPERT', code: 'content-creation', name: '内容创作',
          nameEn: 'Content Creation', sortOrder: 10, enabled: true, usageCount: 0 },
      ],
    })
    const wrapper = mountExpertPage()
    await flushPromises()

    await wrapper.get('[data-test="skills-category-manage"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="category-manage-edit-content-creation"]').trigger('click')
    await flushPromises()

    // 清空名称后提交：本地拦截，不发请求
    await wrapper.get('[data-test="category-form-name"]').setValue('   ')
    await wrapper.get('[data-test="category-form-submit"]').trigger('click')
    await flushPromises()
    expect(adminApi.updateSkillCategory).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="category-form-submit"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('删除空分类走 A24，并把后端返回的占用包说明原样展示', async () => {
    adminApi.listSkillCategories.mockResolvedValue({
      kind: 'EXPERT',
      categories: [
        { id: 'c1', kind: 'EXPERT', code: 'empty-cat', name: '空分类',
          nameEn: null, sortOrder: 1, enabled: true, usageCount: 0 },
      ],
    })
    adminApi.deleteSkillCategory.mockRejectedValue({
      response: {
        status: 409,
        data: {
          code: 'skill_category_in_use',
          message: 'server copy',
          details: { kind: 'EXPERT', name: '空分类', usageCount: 2, packages: ['pet-care'] },
        },
      },
    })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const error = vi.spyOn(ElMessage, 'error')

    const wrapper = mountExpertPage()
    await flushPromises()

    await wrapper.get('[data-test="skills-category-manage"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-test="category-manage-delete-empty-cat"]').trigger('click')
    await flushPromises()

    expect(adminApi.deleteSkillCategory).toHaveBeenCalledWith('EXPERT', 'empty-cat')
    // 文案要点出占用的包，而不是只说「仍在使用」
    expect(error).toHaveBeenCalledWith(expect.stringContaining('pet-care'))
    expect(error).toHaveBeenCalledWith(expect.stringContaining('空分类'))
    expect(wrapper.text()).not.toContain('server copy')
    wrapper.unmount()
  })
})

/**
 * 分类管理主从布局（2026-09-19 重做）。
 *
 * 重做前清单与「新增/修改」表单上下堆叠成一张长表单，看不出在改哪一条，
 * 也看不到已上传的封面。重做后左栏是清单（带缩略图与占用数），右栏是常驻编辑面板。
 */
describe('分类管理主从布局', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetExpertCategoryCache()
    routerState.route.query = {}
    adminApi.listAdminSkills.mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
    skillsApi.listSkills.mockResolvedValue({ object: 'list', data: [] })
  })

  afterEach(() => {
    ElMessage.closeAll()
    document.querySelectorAll('.el-message').forEach((message) => message.remove())
  })

  // 后端用 imageUrl（null = 没有封面），toDefinition 再映射成 hasImage
  const twoCategories = {
    kind: 'EXPERT',
    categories: [
      {
        id: 'c1', kind: 'EXPERT', code: 'content-creation', name: '内容创作',
        nameEn: 'Content Creation', sortOrder: 10, enabled: true, usageCount: 2,
        imageUrl: '/console/gateway/admin/skills/categories/content-creation/image',
      },
      {
        id: 'c2', kind: 'EXPERT', code: 'empty-cat', name: '空分类',
        nameEn: null, sortOrder: 20, enabled: true, usageCount: 0, imageUrl: null,
      },
    ],
  }

  it('左栏给出分类摘要，并按占用情况区分「在用」', async () => {
    adminApi.listSkillCategories.mockResolvedValue(twoCategories)
    const wrapper = mountExpertPage()
    await flushPromises()

    await wrapper.get('[data-test="skills-category-manage"]').trigger('click')
    await flushPromises()

    // 摘要让管理员不用点开就知道规模与占用
    expect(wrapper.get('[data-test="category-manage-summary"]').text()).toContain('2 个')
    expect(wrapper.get('[data-test="category-manage-summary"]').text()).toContain('1 个在用')
    wrapper.unmount()
  })

  it('点「修改」高亮该行并把面板切到编辑态，点「取消修改」回到新增态', async () => {
    adminApi.listSkillCategories.mockResolvedValue(twoCategories)
    const wrapper = mountExpertPage()
    await flushPromises()

    await wrapper.get('[data-test="skills-category-manage"]').trigger('click')
    await flushPromises()

    // 初始是新增态：没有「取消修改」，输入框为空
    expect(wrapper.find('[data-test="category-form-cancel-edit"]').exists()).toBe(false)
    expect((wrapper.get('[data-test="category-form-name"]').element as HTMLInputElement).value).toBe('')

    await wrapper.get('[data-test="category-manage-edit-content-creation"]').trigger('click')
    await flushPromises()

    // 切到编辑态：出现取消入口、提示文案点出正在改哪一条、该行被高亮
    expect(wrapper.find('[data-test="category-form-cancel-edit"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="category-form-edit-hint"]').text()).toContain('内容创作')
    expect(wrapper.get('[data-test="category-manage-item-content-creation"]').classes())
      .toContain('category-manage-item--active')

    await wrapper.get('[data-test="category-form-cancel-edit"]').trigger('click')
    await flushPromises()

    // 回到新增态：高亮消失、输入框清空
    expect(wrapper.find('[data-test="category-form-cancel-edit"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="category-manage-item-content-creation"]').classes())
      .not.toContain('category-manage-item--active')
    expect((wrapper.get('[data-test="category-form-name"]').element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })

  /*
   * 2026-09-19 需求：封面的更新/删除必须先经编辑页的**二次最终确认**。
   *
   * 改动前「清空」是清单行上的即时删除 —— 点一下当场删对象并刷新词表，误点没有反悔余地，
   * 而它改的又是直接决定桌面端精选场景卡片观感的线上数据。
   * 现在与「换封面」一起收进修改态：标记 → 可撤销 → 点「保存修改」时确认后提交。
   */
  it('清单行不再提供即时清空封面，改由修改态标记后再确认提交', async () => {
    adminApi.listSkillCategories.mockResolvedValue(twoCategories)
    adminApi.fetchSkillCategoryImage.mockResolvedValue(new Blob(['png'], { type: 'image/png' }))
    adminApi.deleteSkillCategoryImage.mockResolvedValue({
      id: 'c1', kind: 'EXPERT', code: 'content-creation', name: '内容创作',
      nameEn: 'Content Creation', sortOrder: 10, enabled: true, usageCount: 2, hasImage: false,
    })
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:cover'),
      revokeObjectURL: vi.fn(),
    })
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    const wrapper = mountExpertPage()
    await flushPromises()
    await wrapper.get('[data-test="skills-category-manage"]').trigger('click')
    await flushPromises()

    // 清单行上不再有「清空」或重复的「下载」入口；封面操作统一放在修改态
    expect(wrapper.find('[data-test="category-manage-image-clear-content-creation"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="category-manage-image-download-content-creation"]').exists()).toBe(false)
    // 管理视图里能看到已上传的封面缩略图
    expect(wrapper.find('[data-test="category-manage-cover-content-creation"] img').exists()).toBe(true)

    // 进入修改态，清空入口只在这里、且只做标记
    await wrapper.get('[data-test="category-manage-edit-content-creation"]').trigger('click')
    await flushPromises()
    // 下载入口仍保留在修改页，避免分类清单与修改页重复展示
    expect(wrapper.find('[data-test="category-form-cover-download"]').exists()).toBe(true)
    const clearBtn = wrapper.find('[data-test="category-form-cover-clear"]')
    expect(clearBtn.exists()).toBe(true)

    await clearBtn.trigger('click')
    await flushPromises()

    // 关键：标记阶段不写后端
    expect(adminApi.deleteSkillCategoryImage).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="category-form-cover-pending"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="category-form-cover-clear"]').exists()).toBe(false)

    await wrapper.get('[data-test="category-form-cover-undo-clear"]').trigger('click')
    await flushPromises()

    // 撤销后回到原状态，依然没碰后端
    expect(wrapper.find('[data-test="category-form-cover-pending"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="category-form-cover-clear"]').exists()).toBe(true)
    expect(adminApi.deleteSkillCategoryImage).not.toHaveBeenCalled()

    // 再标记一次并保存：这时才出现最终确认，确认后才真正提交
    await wrapper.get('[data-test="category-form-cover-clear"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="category-form-submit"]').trigger('click')
    await flushPromises()

    expect(String(confirm.mock.calls.at(-1)?.[0])).toContain('清空')
    expect(adminApi.deleteSkillCategoryImage).toHaveBeenCalledWith('EXPERT', 'content-creation')
    // 清空后必须重拉词表，否则行上还挂着「已上传封面」标记
    expect(adminApi.listSkillCategories).toHaveBeenCalledTimes(2)
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('取消最终确认时不删封面、也不改名', async () => {
    adminApi.listSkillCategories.mockResolvedValue(twoCategories)
    adminApi.fetchSkillCategoryImage.mockResolvedValue(new Blob(['png'], { type: 'image/png' }))
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:cover'),
      revokeObjectURL: vi.fn(),
    })
    // 「返回修改」= 取消确认
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel' as never)

    const wrapper = mountExpertPage()
    await flushPromises()
    await wrapper.get('[data-test="skills-category-manage"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="category-manage-edit-content-creation"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="category-form-cover-clear"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="category-form-submit"]').trigger('click')
    await flushPromises()

    // 整个提交被放弃：不改名、不删封面，标记仍在
    expect(adminApi.updateSkillCategory).not.toHaveBeenCalled()
    expect(adminApi.deleteSkillCategoryImage).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="category-form-cover-pending"]').exists()).toBe(true)
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  // 需求：管理员要把已上传的分类封面下载到本地；入口位于修改页
  it('在修改页把已上传的封面下载为 <code>-cover.<ext>，且不改动后端状态', async () => {
    adminApi.listSkillCategories.mockResolvedValue(twoCategories)
    adminApi.fetchSkillCategoryImage.mockResolvedValue(new Blob(['png'], { type: 'image/png' }))
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:cover'),
      revokeObjectURL: vi.fn(),
    })
    const clicked: HTMLAnchorElement[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      clicked.push(this)
    })

    const wrapper = mountExpertPage()
    await flushPromises()
    await wrapper.get('[data-test="skills-category-manage"]').trigger('click')
    await flushPromises()

    // 分类清单不再提供重复的下载入口；没封面的分类也不应出现修改页下载按钮
    expect(wrapper.find('[data-test="category-manage-image-download-content-creation"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="category-manage-image-download-empty-cat"]').exists()).toBe(false)

    await wrapper.get('[data-test="category-manage-edit-content-creation"]').trigger('click')
    await flushPromises()
    const downloadBtn = wrapper.get('[data-test="category-form-cover-download"]')

    await downloadBtn.trigger('click')
    await flushPromises()

    expect(adminApi.fetchSkillCategoryImage).toHaveBeenCalledWith('EXPERT', 'content-creation')
    expect(clicked.map((anchor) => anchor.download)).toEqual(['content-creation-cover.png'])
    // 下载是只读操作：不该顺手改后端状态
    expect(adminApi.deleteSkillCategoryImage).not.toHaveBeenCalled()
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  // 需求：换封面移到修改按钮的二次界面，选中文件不立即上传，点「保存修改」并确认后才上传
  it('换封面只在修改态可选，且要等「保存修改」并确认后才上传', async () => {
    adminApi.listSkillCategories.mockResolvedValue(twoCategories)
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:pending-cover'),
      revokeObjectURL: vi.fn(),
    })
    // 2026-09-19：图片写路径统一由「保存修改」时的最终确认把关
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    const wrapper = mountExpertPage()
    await flushPromises()
    await wrapper.get('[data-test="skills-category-manage"]').trigger('click')
    await flushPromises()

    // 新增态没有封面槽位：换封面是「改某一条」的操作，不属于新增
    expect(wrapper.find('[data-test="category-form-cover"]').exists()).toBe(false)

    await wrapper.get('[data-test="category-manage-edit-content-creation"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="category-form-cover"]').exists()).toBe(true)

    const fileInput = wrapper.get('[data-test="category-form-cover-file"]')
    const picked = new File(['png-bytes'], 'cover.png', { type: 'image/png' })
    Object.defineProperty(fileInput.element, 'files', { value: [picked], configurable: true })
    await fileInput.trigger('change')
    await flushPromises()

    // 关键：选完文件还没保存，绝不能已经上传
    expect(adminApi.uploadSkillCategoryImage).not.toHaveBeenCalled()
    // 本地预览要立刻可见，否则管理员不知道自己选了什么
    expect(wrapper.get('[data-test="category-form-cover-preview"] img').attributes('src'))
      .toBe('blob:pending-cover')

    await wrapper.get('[data-test="category-form-submit"]').trigger('click')
    await flushPromises()

    // 最终确认里列出了这次要替换封面
    expect(String(confirm.mock.calls.at(-1)?.[0])).toContain('替换')
    expect(adminApi.updateSkillCategory).toHaveBeenCalledWith('EXPERT', 'content-creation', {
      name: '内容创作', nameEn: 'Content Creation',
    })
    expect(adminApi.uploadSkillCategoryImage).toHaveBeenCalledWith('EXPERT', 'content-creation', picked)
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('取消选择只丢掉本地文件，不上传也不影响已上传的封面', async () => {
    adminApi.listSkillCategories.mockResolvedValue(twoCategories)
    // 两次 createObjectURL 要能区分开：第一次是打开对话框时拉的已上传封面，
    // 第二次才是刚选的本地文件。用固定返回值会让「取消后回落到哪张」断言失真。
    let objectUrlSeq = 0
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => {
        objectUrlSeq += 1
        return objectUrlSeq === 1 ? 'blob:uploaded-cover' : 'blob:pending-cover'
      }),
      revokeObjectURL: vi.fn(),
    })

    const wrapper = mountExpertPage()
    await flushPromises()
    await wrapper.get('[data-test="skills-category-manage"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="category-manage-edit-content-creation"]').trigger('click')
    await flushPromises()

    const fileInput = wrapper.get('[data-test="category-form-cover-file"]')
    const picked = new File(['png-bytes'], 'cover.png', { type: 'image/png' })
    Object.defineProperty(fileInput.element, 'files', { value: [picked], configurable: true })
    await fileInput.trigger('change')
    await flushPromises()

    await wrapper.get('[data-test="category-form-cover-cancel"]').trigger('click')
    await flushPromises()

    // 取消后回到「已上传的那张」：既没有待上传文件，也没有把线上封面清掉
    expect(wrapper.find('[data-test="category-form-cover-cancel"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="category-form-cover-preview"] img').attributes('src'))
      .toBe('blob:uploaded-cover')
    expect(adminApi.uploadSkillCategoryImage).not.toHaveBeenCalled()
    expect(adminApi.deleteSkillCategoryImage).not.toHaveBeenCalled()
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('新增提交后回到新增态，方便连续录入', async () => {
    adminApi.listSkillCategories.mockResolvedValue(twoCategories)
    adminApi.createSkillCategory.mockResolvedValue({
      id: 'c3', kind: 'EXPERT', code: 'new-cat', name: '新分类',
      nameEn: null, sortOrder: 30, enabled: true, usageCount: 0, hasImage: false,
    })

    const wrapper = mountExpertPage()
    await flushPromises()
    await wrapper.get('[data-test="skills-category-manage"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-test="category-form-name"]').setValue('新分类')
    await wrapper.get('[data-test="category-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.createSkillCategory).toHaveBeenCalledWith({
      kind: 'EXPERT', name: '新分类', nameEn: null,
    })
    // 提交后输入框清空、面板留在新增态
    expect((wrapper.get('[data-test="category-form-name"]').element as HTMLInputElement).value).toBe('')
    expect(wrapper.find('[data-test="category-form-cancel-edit"]').exists()).toBe(false)
    wrapper.unmount()
  })
})


