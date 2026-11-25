import ElementPlus from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import SkillCatalogView from './SkillCatalogView.vue'
import { resetExpertCategoryCache } from './expertCategories'
import { SKILL_ADMIN_ERROR_MESSAGES } from './skillAdminErrors'
import { buildZip } from '../../utils/ycpsZipWriter'

const adminApi = vi.hoisted(() => ({
  listAdminSkills: vi.fn(),
  listDeletedAdminSkills: vi.fn(),
  getAdminSkill: vi.fn(),
  createAdminSkill: vi.fn(),
  createAdminExpertAggregate: vi.fn(),
  updateAdminSkill: vi.fn(),
  updateAdminExpertAggregate: vi.fn(),
  deleteAdminSkill: vi.fn(),
  restoreAdminSkill: vi.fn(),
  purgeAdminSkill: vi.fn(),
  publishAdminSkill: vi.fn(),
  publishAdminClawhubSkill: vi.fn(),
  listAdminClawhubVersions: vi.fn(),
  uploadAdminClawhubVersion: vi.fn(),
  downloadAdminClawhubVersion: vi.fn(),
  downloadAdminClawhubSkillPackage: vi.fn(),
  publishAdminClawhubVersion: vi.fn(),
  rescanAdminClawhubVersion: vi.fn(),
  deleteAdminClawhubVersion: vi.fn(),
  rescanAdminClawhubSkill: vi.fn(),
  updateAdminClawhubSourceVersion: vi.fn(),
  unpublishAdminSkill: vi.fn(),
  uploadAdminSkillIcon: vi.fn(),
  fetchAdminSkillIcon: vi.fn(),
  deleteAdminSkillIcon: vi.fn(),
  uploadSkillVersion: vi.fn(),
  replaceSkillPackage: vi.fn(),
  uploadSkillVersionWithRetry: vi.fn(),
  replaceSkillPackageWithRetry: vi.fn(),
  publishSkillVersion: vi.fn(),
  deleteSkillVersion: vi.fn(),
  downloadAdminSkillPackage: vi.fn(),
  downloadAdminSkillPackageBundle: vi.fn(),
  listSkillDownloads: vi.fn(),
  replaceAdminSkillMembers: vi.fn(),
  uploadAdminSkillIllustration: vi.fn(),
  deleteAdminSkillIllustration: vi.fn(),
  fetchAdminSkillIllustration: vi.fn(),
  uploadAdminSkillExampleImage: vi.fn(),
  deleteAdminSkillExampleImage: vi.fn(),
  fetchAdminSkillExampleImage: vi.fn(),
  fetchSkillVersionDependencies: vi.fn(),
  fetchSkillDependents: vi.fn(),
  fetchSkillRelations: vi.fn(),
  fetchSkillCodeAvailability: vi.fn(),
  inspectAdminSkillPackage: vi.fn(),
  previewAdminClawhubSkill: vi.fn(),
  fetchAdminRemoteClawhubSkill: vi.fn(),
  commitAdminClawhubSkill: vi.fn(),
  listSkillCategories: vi.fn(),
  createSkillCategory: vi.fn(),
  updateSkillCategory: vi.fn(),
  deleteSkillCategory: vi.fn(),
}))

const skillsApi = vi.hoisted(() => ({
  listSkills: vi.fn(),
  listNativeClawhubSkills: vi.fn(),
  getSkill: vi.fn(),
  fetchSkillIcon: vi.fn(),
  fetchSkillMemberAvatar: vi.fn(),
}))

const routerState = vi.hoisted(() => ({
  route: { query: {} as Record<string, unknown> },
  replace: vi.fn(),
}))

// 只替换被断言的方法，其余导出（如 skillDetailPlaceholder）沿用真实实现
vi.mock('../../api/admin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/admin')>()),
  ...adminApi,
}))
vi.mock('../../api/skills', () => skillsApi)
vi.mock('vue-router', () => ({
  useRoute: () => routerState.route,
  useRouter: () => ({ replace: routerState.replace }),
}))

const SHA256 = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'

function row(overrides: Record<string, unknown> = {}) {
  return {
    id: 'skill-1',
    code: 'data-analyst',
    name: '数据分析助手',
    nameEn: 'Data Analyst',
    category: '数据分析',
    tags: ['报表', 'SQL'],
    summary: '把原始数据整理成结论与图表',
    summaryEn: 'Turn raw tables into conclusions',
    descriptionEn: null,
    icon: '📊',
    iconUrl: null,
    illustrationUrl: null,
    exampleImageUrl: null,
    status: 'PUBLISHED',
    publishedVersion: '1.2.0',
    latestVersion: '1.2.0',
    minClientVersion: '2026.6.0',
    sizeBytes: 10_485_760,
    downloadCount: 128,
    createdAt: '2026-09-14T02:10:00Z',
    updatedAt: '2026-09-14T03:00:00Z',
    rowVersion: 3,
    kind: 'SKILL',
    memberCount: 0,
    dependencyCount: 0,
    dependentCount: 0,
    ...overrides,
  }
}

const published = row()

const draft = row({
  id: 'skill-2',
  code: 'content-writer',
  name: '内容生成助手',
  nameEn: null,
  category: '内容创作',
  tags: ['文案'],
  summary: '面向公众号与小红书的写作助手',
  summaryEn: null,
  icon: '✍️',
  status: 'DRAFT',
  publishedVersion: null,
  latestVersion: null,
  sizeBytes: 0,
  downloadCount: 0,
  rowVersion: 1,
  kind: 'EXPERT',
  memberCount: 2,
})

const offline = row({
  id: 'skill-3',
  code: 'ecommerce-ops',
  name: '电商运营助手',
  nameEn: null,
  category: '电商运营',
  tags: [],
  summary: '商品标题、活动策划与客服话术',
  summaryEn: null,
  icon: null,
  status: 'UNPUBLISHED',
  publishedVersion: null,
  latestVersion: '1.0.2',
  sizeBytes: 6_400_000,
  downloadCount: 36,
  rowVersion: 5,
  kind: 'TEAM',
  memberCount: 4,
})

const pending = row({
  id: 'skill-4',
  code: 'example-skill',
  name: '示例 Skill',
  nameEn: null,
  category: null,
  tags: [],
  summary: '占位示例',
  summaryEn: null,
  icon: null,
  status: 'PUBLISHED',
  publishedVersion: '1.0.0',
  latestVersion: '1.1.0',
  sizeBytes: 1_048_576,
  downloadCount: 4,
  rowVersion: 2,
})

const catalogPage = {
  items: [published, draft, offline, pending],
  total: 4,
  page: 0,
  size: 20,
}

const recycleItems = [
  {
    id: 'deleted-1', code: 'archived-writer', name: '归档写作助手', nameEn: null,
    category: '内容创作', kind: 'SKILL' as const,
    deletedAt: '2026-09-19T03:00:00Z', expiresAt: '2026-10-19T03:00:00Z',
  },
  {
    id: 'deleted-2', code: 'archived-reader', name: '归档阅读助手', nameEn: 'Archived Reader',
    category: null, kind: 'SKILL' as const,
    deletedAt: '2026-09-18T03:00:00Z', expiresAt: '2026-10-18T03:00:00Z',
  },
]

/** 与组件共用的 Intl 人类可读字节格式，避免测试里硬编码换算结果 */
const bytesFormatter = new Intl.NumberFormat('zh-CN', {
  style: 'unit',
  unit: 'byte',
  notation: 'compact',
  maximumFractionDigits: 1,
})

function version(overrides: Record<string, unknown> = {}) {
  return {
    id: 'version-1',
    version: '1.2.0',
    revision: 2,
    status: 'PUBLISHED',
    sizeBytes: 10_485_760,
    md5: 'a'.repeat(32),
    sha256: 'b'.repeat(64),
    packageFormat: 'YCPS',
    ycpsVersion: '1.0.0',
    packageKind: 'SKILL',
    pluginManifest: { schemaVersion: '1.0.0' },
    runtimeManifest: {
      permissionRequests: {
        tools: ['document.read', 'process.execute'],
        network: ['https://api.example.com'],
        filesystem: ['workspace'],
      },
      hostRequirements: { minClientVersion: '2026.6.0', platforms: ['win32', 'darwin'], architectures: ['x64'] },
    },
    manifestSha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    dependencyLockSha256: 'd'.repeat(64),
    sealed: false,
    sealedAt: null,
    fileCount: 2,
    scanStatus: 'PASSED',
    changelog: '首个线上版本',
    downloadCount: 128,
    createdAt: '2026-09-14T02:12:00Z',
    online: true,
    ...overrides,
  }
}

function nativeClawhubVersion(overrides: Record<string, unknown> = {}) {
  return {
    id: 'source-1',
    skillId: 'clawhub-skill-1',
    owner: 'publisher',
    slug: 'demo',
    version: '1.2.3',
    sourceSha256: 'a'.repeat(64),
    sizeBytes: 2048,
    scanStatus: 'PASSED',
    reviewRequired: false,
    status: 'DRAFT',
    createdAt: '2026-09-21T12:00:00Z',
    publishedAt: null,
    online: false,
    ...overrides,
  }
}

async function nativeClawhubZipFixture(version = '2.3.4') {
  const prefix = '@publisher/demo/'
  const bytes = await buildZip([
    {
      path: `${prefix}SKILL.md`,
      bytes: new TextEncoder().encode(
        `---\nname: Demo Skill\nslug: demo\ndescription: Demo instructions\nversion: ${version}\n---\nUse the demo.\n`,
      ),
    },
    {
      path: `${prefix}.clawhub/origin.json`,
      bytes: new TextEncoder().encode(JSON.stringify({
        registry: 'https://clawhub.ai', ownerHandle: 'publisher',
        slug: '@publisher/demo', installedVersion: version,
      })),
    },
  ])
  return new File([bytes as BlobPart], 'demo.zip', { type: 'application/zip' })
}

/** 管理面成员视图（A3 详情与 `PUT /{id}/members` 同形） */
function member(overrides: Record<string, unknown> = {}) {
  return {
    id: 'member-1',
    memberKey: 'atlas',
    name: '安拓',
    nameEn: 'Atlas',
    profession: '分发策略师',
    professionEn: 'Distribution Strategist',
    role: 'LEAD',
    sortOrder: 0,
    avatarUrl: null,
    ...overrides,
  }
}

const detail = {
  ...published,
  kind: 'TEAM',
  description: '把 CSV 报表变成结论。',
  descriptionEn: 'Turns CSV reports into conclusions.',
  sortWeight: 0,
  versions: [
    version({ sealed: true, sealedAt: '2026-09-14T02:13:00Z' }),
    version({
      id: 'version-2',
      version: '1.3.0',
      revision: 1,
      status: 'READY',
      scanStatus: 'PASSED',
      online: false,
      downloadCount: 0,
    }),
  ],
  manifest: {
    expertType: 'team',
    members: [
      { id: 'atlas', name: '安拓', role: 'lead' },
      { id: 'nova', name: '诺娃', role: 'member' },
    ],
  },
  members: [
    // 成员标识即专家 code，头像读该专家包自己的图标（管理面走 /console/gateway/skills/{code}/icon）
    member({ avatarUrl: '/console/gateway/skills/atlas/icon' }),
    member({
      id: 'member-2',
      memberKey: 'nova',
      name: '诺娃',
      nameEn: 'Nova',
      profession: '内容策划',
      professionEn: 'Content Planner',
      role: 'MEMBER',
      sortOrder: 1,
    }),
  ],
}

const previewCatalog = {
  object: 'list' as const,
  data: [
    {
      name: 'data-analyst',
      title: '数据分析助手',
      titleEn: 'Data Analyst',
      description: '把 CSV 报表变成结论。',
      descriptionEn: 'Turns CSV reports into conclusions.',
      version: '1.2.0',
      category: '数据分析',
      tags: ['报表'],
      icon: '📊',
      highlights: ['指标摘要'],
      updatedAt: '2026-09-14T03:00:00Z',
      fileCount: 1,
      totalBytes: 0,
      files: [{ path: 'SKILL.md', bytes: 0, sha256: SHA256 }],
      kind: 'TEAM' as const,
    },
  ],
}

const previewDetail = {
  object: 'skill' as const,
  name: 'data-analyst',
  title: '数据分析助手',
  titleEn: 'Data Analyst',
  description: '把 CSV 报表变成结论。',
  descriptionEn: 'Turns CSV reports into conclusions.',
  version: '1.2.0',
  category: '数据分析',
  tags: ['报表'],
  icon: '📊',
  highlights: ['指标摘要'],
  updatedAt: '2026-09-14T03:00:00Z',
  fileCount: 2,
  totalBytes: 8,
  kind: 'TEAM' as const,
  members: [
    {
      memberKey: 'atlas',
      name: '安拓',
      nameEn: 'Atlas',
      profession: '分发策略师',
      professionEn: 'Distribution Strategist',
      role: 'LEAD' as const,
      sortOrder: 0,
      avatarUrl: '/console/gateway/skills/atlas/icon',
    },
    {
      memberKey: 'nova',
      name: '诺娃',
      nameEn: 'Nova',
      profession: '内容策划',
      professionEn: 'Content Planner',
      role: 'MEMBER' as const,
      sortOrder: 1,
      avatarUrl: null,
    },
  ],
  files: [
    { path: 'SKILL.md', encoding: 'utf-8' as const, bytes: 5, sha256: SHA256, content: '# 内容\n' },
    { path: 'assets/logo.bin', encoding: 'base64' as const, bytes: 3, sha256: 'c'.repeat(64), content: 'AAEC' },
  ],
}

function mountView() {
  return mount(SkillCatalogView, { global: { plugins: [ElementPlus] } })
}

async function mountReady() {
  const wrapper = mountView()
  await flushPromises()
  return wrapper
}

function apiFailure(code: string, status = 409, message = `server says ${code}`) {
  return { response: { status, data: { code, message } } }
}

const publishedNativeClawhubSkill = {
  code: 'native-research',
  name: '原生研究助手',
  description: '从 ClawHub 导入的研究技能',
  descriptionEn: 'Native research skill',
  summary: '研究摘要',
  summaryEn: 'Research summary',
  status: 'PUBLISHED' as const,
  version: '2.3.4',
  packageFormat: 'CLAWHUB_SKILL' as const,
  sha256: SHA256,
  sizeBytes: 4096,
  owner: 'publisher',
  slug: 'research',
  downloadUrl: '/v1/native-clawhub/skills/native-research/package',
  iconUrl: '/v1/native-clawhub/skills/native-research/icon',
  iconUpdatedAt: '2026-09-30T00:00:00Z',
  updatedAt: '2026-09-30T00:00:00Z',
}

function expectStandalonePromptKeyword(message: unknown, keyword: string) {
  const vnode = message as {
    type?: unknown
    children?: Array<{ type?: unknown; children?: unknown; props?: { style?: Record<string, unknown> } }>
  }
  expect(vnode.type).toBe('div')
  const code = vnode.children?.[1]
  expect(code?.type).toBe('code')
  expect(code?.children).toBe(keyword)
  expect(code?.props?.style?.whiteSpace).toBe('nowrap')
  expect(code?.props?.style?.textAlign).toBe('center')
  return vnode.children ?? []
}

/** 花名册与插画相关接口的默认桩：写操作返回完整技能详情，图片字节用占位 PNG */
function mockMemberApi() {
  adminApi.replaceAdminSkillMembers.mockResolvedValue(detail)
  adminApi.uploadAdminSkillIllustration.mockResolvedValue(detail)
  adminApi.deleteAdminSkillIllustration.mockResolvedValue({
    ...detail,
    illustrationUrl: null,
  })
  adminApi.fetchAdminSkillIllustration.mockResolvedValue(
    new Blob(['png'], { type: 'image/png' }),
  )
  adminApi.fetchAdminSkillExampleImage.mockResolvedValue(
    new Blob(['png'], { type: 'image/png' }),
  )
  skillsApi.fetchSkillIcon.mockResolvedValue(new Blob(['png'], { type: 'image/png' }))
  skillsApi.fetchSkillMemberAvatar.mockResolvedValue(new Blob(['png'], { type: 'image/png' }))
  adminApi.fetchSkillVersionDependencies.mockResolvedValue({
    skillId: 'skill-1',
    code: 'data-analyst',
    kind: 'TEAM',
    versionId: 'version-1',
    version: '1.2.0',
    revision: 2,
    sealed: true,
    dependencies: [
      {
        key: 'budget-analysis',
        relation: 'SKILL_BINDING',
        required: true,
        targetCode: 'budget-analysis',
        targetKind: 'SKILL',
        targetVersion: '2.0.1',
        targetRevision: 3,
        targetSha256: 'f'.repeat(64),
        targetVersionId: 'target-version-1',
        targetSkillId: 'target-skill-1',
        targetVersionStatus: 'PUBLISHED',
        targetOnline: true,
      },
    ],
  })
  adminApi.fetchSkillDependents.mockResolvedValue({
    skillId: 'skill-1',
    code: 'data-analyst',
    dependents: [
      {
        ownerSkillId: 'owner-skill-1',
        ownerCode: 'finance-analyst',
        ownerKind: 'EXPERT',
        ownerVersionId: 'owner-version-1',
        ownerVersion: '3.1.0',
        ownerRevision: 4,
        ownerStatus: 'PUBLISHED',
        ownerOnline: true,
        key: 'data-analyst',
        relation: 'SKILL_BINDING',
        required: true,
        targetVersion: '1.2.0',
        targetRevision: 2,
      },
    ],
  })
  // A21：按包 id 的双向关系（不要求先拿到 versionId）
  adminApi.fetchSkillRelations.mockResolvedValue({
    id: 'skill-1',
    code: 'data-analyst',
    kind: 'SKILL',
    version: '1.2.0',
    revision: 2,
    dependsOn: [
      {
        key: 'budget-analysis',
        relation: 'SKILL_BINDING',
        required: true,
        code: 'budget-analysis',
        kind: 'SKILL',
        version: '2.0.1',
        revision: 3,
        status: 'PUBLISHED',
        targetSkillId: 'target-skill-1',
      },
    ],
    dependedOnBy: [
      {
        ownerSkillId: 'owner-skill-1',
        ownerCode: 'finance-analyst',
        ownerKind: 'EXPERT',
        ownerVersionId: 'owner-version-1',
        ownerVersion: '3.1.0',
        ownerRevision: 4,
        ownerStatus: 'PUBLISHED',
        ownerOnline: true,
        key: 'data-analyst',
        relation: 'SKILL_BINDING',
        required: true,
        targetVersion: '1.2.0',
        targetRevision: 2,
      },
    ],
  })
  // A22：编码预检默认「可用」，个别用例再覆盖为占用
  adminApi.fetchSkillCodeAvailability.mockResolvedValue({
    code: 'new-skill',
    available: true,
    occupiedBy: null,
  })
}

type RosterRow = {
  memberKey: string
  name: string
  nameEn: string
  profession: string
  professionEn: string
  role: string
  sortOrder: number
}

/** 取出组件 `<script setup>` 的 setupState（vue-test-utils 类型里把 `$` 标成 internal） */
function state(wrapper: ReturnType<typeof mount>) {
  return (wrapper.vm.$ as unknown as {
    setupState: {
      params: { status: string; category: string; kind: string }
      form: { kind: string; code: string; tags: string[]; description: string; descriptionEn: string }
      bulkCategory: string
      roster: RosterRow[]
      rosterDirty: boolean
      errorMessage: string
      onRoleChange: (index: number) => void
      saveRoster: () => Promise<void>
    }
  }).setupState
}

async function openDrawer(wrapper: ReturnType<typeof mount>) {
  await wrapper.get('[data-test="skill-detail-data-analyst"]').trigger('click')
  await flushPromises()
}

/** 切到「客户端预览」标签页 */
async function openPreviewTab(wrapper: ReturnType<typeof mount>) {
  const tabs = wrapper.findAll('.el-tabs__item')
  await tabs.at(1)?.trigger('click')
  await flushPromises()
}

function pickFile(wrapper: ReturnType<typeof mount>, selector: string, file: File) {
  const input = wrapper.get(selector)
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  return input.trigger('change')
}

describe('SkillCatalogView 管理列表', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routerState.route.query = {}
    adminApi.listAdminSkills.mockResolvedValue(catalogPage)
    adminApi.listDeletedAdminSkills.mockResolvedValue({ items: recycleItems, total: 2, page: 0, size: 100 })
    adminApi.getAdminSkill.mockResolvedValue(detail)
    adminApi.createAdminSkill.mockResolvedValue({ ...detail, id: 'skill-9', code: 'new-skill' })
    adminApi.updateAdminSkill.mockResolvedValue(detail)
    adminApi.deleteAdminSkill.mockResolvedValue(undefined)
    adminApi.restoreAdminSkill.mockResolvedValue(detail)
    adminApi.purgeAdminSkill.mockResolvedValue(undefined)
    adminApi.publishAdminSkill.mockResolvedValue(detail)
    adminApi.publishAdminClawhubSkill.mockResolvedValue(detail)
    adminApi.listAdminClawhubVersions.mockResolvedValue({ items: [], currentPublishedVersionId: null })
    adminApi.uploadAdminClawhubVersion.mockResolvedValue({})
    adminApi.downloadAdminClawhubVersion.mockResolvedValue({ blob: new Blob(['zip']), fileName: '' })
    adminApi.downloadAdminClawhubSkillPackage.mockResolvedValue({ blob: new Blob(['zip']), fileName: '' })
    adminApi.publishAdminClawhubVersion.mockResolvedValue(detail)
    adminApi.rescanAdminClawhubVersion.mockResolvedValue({ scanStatus: 'PASSED' })
    adminApi.deleteAdminClawhubVersion.mockResolvedValue(undefined)
    adminApi.updateAdminClawhubSourceVersion.mockResolvedValue(undefined)
    adminApi.unpublishAdminSkill.mockResolvedValue(detail)
    adminApi.deleteSkillVersion.mockResolvedValue(undefined)
    adminApi.publishSkillVersion.mockResolvedValue(version({ id: 'version-2', online: true }))
    skillsApi.listSkills.mockResolvedValue(previewCatalog)
    skillsApi.listNativeClawhubSkills.mockResolvedValue({ object: 'list', items: [] })
    skillsApi.getSkill.mockResolvedValue(previewDetail)
    adminApi.listSkillCategories.mockResolvedValue({
      kind: 'SKILL',
      categories: [
        { id: 'c1', kind: 'SKILL', code: 'documents-data', name: '文档与数据',
          nameEn: 'Documents & Data', sortOrder: 1, enabled: true, usageCount: 2 },
        { id: 'c2', kind: 'SKILL', code: 'research-retrieval', name: '研究与检索',
          nameEn: 'Research & Retrieval', sortOrder: 2, enabled: true, usageCount: 0 },
      ],
    })
    adminApi.createSkillCategory.mockResolvedValue({
      id: 'c3', kind: 'SKILL', code: 'new-cat', name: '新分类',
      nameEn: null, sortOrder: 8, enabled: true, usageCount: 0,
    })
    adminApi.updateSkillCategory.mockResolvedValue({
      id: 'c1', kind: 'SKILL', code: 'documents-data', name: '文档与资料',
      nameEn: 'Documents & Data', sortOrder: 1, enabled: true, usageCount: 2,
    })
    mockMemberApi()
  })

  afterEach(() => {
    ElMessage.closeAll()
    document.querySelectorAll('.el-message').forEach((message) => message.remove())
  })

  it('requests page 0 with the documented parameters and renders the table', async () => {
    const wrapper = await mountReady()

    expect(adminApi.listAdminSkills).toHaveBeenCalledTimes(1)
    // Skill 管理页固定 kind=SKILL：专家/专家团归「专家管理」页（/admin/experts）
    expect(adminApi.listAdminSkills).toHaveBeenCalledWith({
      page: 0, size: 20, query: '', status: '', category: '', kind: 'SKILL', sort: 'created_at', direction: 'desc',
    })

    const table = wrapper.get('[data-test="skills-table"]')
    expect(table.text()).toContain('数据分析助手')
    expect(table.text()).toContain('data-analyst')
    expect(table.text()).toContain('数据分析')
    expect(table.text()).toContain(bytesFormatter.format(10_485_760))
    expect(table.text()).toContain('128')
    expect(table.findAll('tbody tr')).toHaveLength(4)

    expect(wrapper.get('[data-test="skill-status-data-analyst"]').text()).toBe('已上架')
    expect(wrapper.get('[data-test="skill-status-content-writer"]').text()).toBe('草稿')
    expect(wrapper.get('[data-test="skill-status-ecommerce-ops"]').text()).toBe('已下架')
    expect(wrapper.find('[data-test="skills-empty"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows the original ZIP size for an unpublished native ClawHub skill', async () => {
    adminApi.listAdminSkills.mockResolvedValue({
      ...catalogPage,
      items: [row({
        id: 'native-unpublished', code: 'native-unpublished',
        sourceFormat: 'CLAWHUB_SKILL', status: 'UNPUBLISHED',
        publishedVersion: null, sizeBytes: 12_804,
      })],
      total: 1,
    })

    const wrapper = await mountReady()
    const entry = wrapper.get('[data-test="skills-table"] tbody tr')
    expect(entry.text()).toContain('12.5 KB')
    expect(entry.get('td.admin-number').attributes('title')).toContain('默认导出原始 ZIP 的大小')
    wrapper.unmount()
  })

  it('publishes the only eligible native ClawHub version from the list action', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const clawhubDraft = {
      ...draft,
      id: 'clawhub-skill-1',
      code: 'native-demo',
      name: '原生 ClawHub 技能',
      kind: 'SKILL' as const,
      status: 'DRAFT' as const,
      sourceFormat: 'CLAWHUB_SKILL' as const,
      sourceReviewRequired: true,
      sourceVersion: '1.2.3',
    }
    const clawhubPublished = {
      ...clawhubDraft,
      status: 'PUBLISHED' as const,
      publishedVersion: '1.2.3',
      latestVersion: '1.2.3',
    }
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [clawhubDraft], total: 1 })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail, ...clawhubDraft, description: null, sortWeight: 0,
      versions: [], manifest: null, members: [],
    })
    adminApi.listAdminClawhubVersions.mockResolvedValue({
      items: [nativeClawhubVersion({ id: 'source-1', skillId: 'clawhub-skill-1', reviewRequired: true })],
      currentPublishedVersionId: null,
    })
    adminApi.publishAdminClawhubSkill.mockImplementation(async () => {
      adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [clawhubPublished], total: 1 })
      adminApi.getAdminSkill.mockResolvedValue({
        ...detail, ...clawhubPublished, description: null, sortWeight: 0,
        versions: [], manifest: null, members: [],
      })
      adminApi.listAdminClawhubVersions.mockResolvedValue({
        items: [nativeClawhubVersion({
          id: 'source-1', skillId: 'clawhub-skill-1', status: 'PUBLISHED', online: true,
        })],
        currentPublishedVersionId: 'source-1',
      })
      return undefined
    })

    const wrapper = await mountReady()

    expect(wrapper.get('[data-test="skill-status-native-demo"]').text()).toContain('草稿')
    expect(wrapper.get('[data-test="skill-source-clawhub"]').text()).toContain('原生技能')
    expect(wrapper.get('[data-test="skill-publish-native-demo"]').text()).toContain('上架')
    await wrapper.get('[data-test="skill-publish-native-demo"]').trigger('click')
    await flushPromises()

    expect(adminApi.getAdminSkill).not.toHaveBeenCalled()
    expect(adminApi.listAdminClawhubVersions).toHaveBeenCalledWith('clawhub-skill-1')
    expect(wrapper.find('[data-test="clawhub-version-list"]').exists()).toBe(false)
    expect(confirm).not.toHaveBeenCalled()
    expect(adminApi.publishAdminClawhubSkill).toHaveBeenCalledOnce()
    expect(adminApi.publishAdminClawhubSkill).toHaveBeenCalledWith('clawhub-skill-1')
    expect(adminApi.publishAdminClawhubVersion).not.toHaveBeenCalled()
    expect(adminApi.publishAdminSkill).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="skill-status-native-demo"]').text()).toContain('已上架')
    expect(wrapper.find('[data-test="skill-drawer-publish-state"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('republishes the remembered native version without opening a drawer or choosing the newer draft', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const native = row({ id: 'native-return', code: 'native-return', kind: 'SKILL',
      sourceFormat: 'CLAWHUB_SKILL', status: 'UNPUBLISHED', sourceReviewRequired: true })
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [native], total: 1 })
    adminApi.listAdminClawhubVersions.mockResolvedValue({
      items: [
        nativeClawhubVersion({ id: 'new-draft', skillId: native.id, version: '2.0.0' }),
        nativeClawhubVersion({ id: 'old-online', skillId: native.id, version: '1.0.0', status: 'PUBLISHED' }),
      ],
      currentPublishedVersionId: 'old-online',
    })
    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-publish-native-return"]').trigger('click')
    await flushPromises()
    expect(adminApi.publishAdminClawhubSkill).toHaveBeenCalledExactlyOnceWith(native.id)
    expect(adminApi.publishAdminClawhubVersion).not.toHaveBeenCalled()
    expect(adminApi.getAdminSkill).not.toHaveBeenCalled()
    expect(confirm).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="clawhub-version-list"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it.each(['remembered', 'single'])('publishes the %s native version from details without a confirmation', async (mode) => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const native = row({ id: 'native-detail-default', code: 'native-detail-default', kind: 'SKILL',
      sourceFormat: 'CLAWHUB_SKILL', status: 'UNPUBLISHED', sourceReviewRequired: true })
    const target = nativeClawhubVersion({ id: 'default-source', skillId: native.id, reviewRequired: true })
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [native], total: 1 })
    adminApi.getAdminSkill.mockResolvedValue({ ...detail, ...native, versions: [], manifest: null, members: [] })
    adminApi.listAdminClawhubVersions.mockResolvedValue({
      items: mode === 'remembered'
        ? [nativeClawhubVersion({ id: 'newer-source', skillId: native.id, version: '3.0.0' }), target]
        : [target],
      currentPublishedVersionId: mode === 'remembered' ? target.id : null,
    })
    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-detail-native-detail-default"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="clawhub-version-publish-default-source"]').trigger('click')
    await flushPromises()
    expect(confirm).not.toHaveBeenCalled()
    expect(adminApi.publishAdminClawhubSkill).toHaveBeenCalledExactlyOnceWith(native.id)
    expect(adminApi.publishAdminClawhubVersion).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it.each([
    ['failed scan', 'FAILED', '1.0.0', 'old-source', '尚未通过安全扫描'],
    ['missing version', 'PASSED', null, 'old-source', '没有有效版本号'],
    ['missing selected source', 'PASSED', '1.0.0', 'missing-source', '不存在或已被删除'],
  ])('does not fall back to a newer draft when the remembered source has %s', async (_label, scanStatus, version, currentId, message) => {
    const native = row({ id: 'native-invalid-default', code: 'native-invalid-default', kind: 'SKILL',
      sourceFormat: 'CLAWHUB_SKILL', status: 'UNPUBLISHED' })
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [native], total: 1 })
    adminApi.getAdminSkill.mockResolvedValue({ ...detail, ...native, versions: [], manifest: null, members: [] })
    adminApi.listAdminClawhubVersions.mockResolvedValue({
      items: [
        nativeClawhubVersion({ id: 'newer-source', skillId: native.id, version: '2.0.0' }),
        nativeClawhubVersion({ id: 'old-source', skillId: native.id, scanStatus, version }),
      ],
      currentPublishedVersionId: currentId,
    })
    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-publish-native-invalid-default"]').trigger('click')
    await flushPromises()
    expect(adminApi.publishAdminClawhubSkill).not.toHaveBeenCalled()
    expect(adminApi.publishAdminClawhubVersion).not.toHaveBeenCalled()
    expect(state(wrapper).errorMessage).toContain(message)
    expect(wrapper.get('[data-test="skill-publish-native-invalid-default"]').classes()).not.toContain('is-loading')
    wrapper.unmount()
  })

  it('opens the native ClawHub version list when the draft has multiple versions', async () => {
    const clawhubDraft = {
      ...draft,
      id: 'clawhub-multiple-versions',
      code: 'native-multiple-demo',
      name: '多版本原生 ClawHub 技能',
      kind: 'SKILL' as const,
      status: 'DRAFT' as const,
      sourceFormat: 'CLAWHUB_SKILL' as const,
      sourceReviewRequired: false,
      sourceVersion: '2.0.0',
    }
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [clawhubDraft], total: 1 })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail, ...clawhubDraft, description: null, sortWeight: 0,
      versions: [], manifest: null, members: [],
    })
    adminApi.listAdminClawhubVersions.mockResolvedValue({
      items: [
        nativeClawhubVersion({ id: 'source-1', skillId: clawhubDraft.id, version: '1.0.0' }),
        nativeClawhubVersion({ id: 'source-2', skillId: clawhubDraft.id, version: '2.0.0' }),
      ],
      currentPublishedVersionId: null,
    })

    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-publish-native-multiple-demo"]').trigger('click')
    await flushPromises()

    expect(adminApi.listAdminClawhubVersions).toHaveBeenCalledWith(clawhubDraft.id)
    expect(wrapper.find('[data-test="clawhub-version-list"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="clawhub-version-row-source-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="clawhub-version-row-source-2"]').exists()).toBe(true)
    expect(adminApi.publishAdminClawhubVersion).not.toHaveBeenCalled()
    expect(adminApi.publishAdminClawhubSkill).not.toHaveBeenCalled()
    expect(adminApi.publishAdminSkill).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('publishes the explicitly selected ClawHub source version after review confirmation', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const clawhubDraft = {
      ...draft,
      id: 'clawhub-review-skill',
      code: 'native-review-demo',
      kind: 'SKILL' as const,
      status: 'DRAFT' as const,
      sourceFormat: 'CLAWHUB_SKILL' as const,
      sourceReviewRequired: true,
      sourceVersion: '1.2.3',
    }
    const clawhubPublished = {
      ...clawhubDraft, status: 'PUBLISHED' as const,
      publishedVersion: '1.2.3', latestVersion: '1.2.3',
    }
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [clawhubDraft], total: 1 })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail, ...clawhubDraft, description: null, sortWeight: 0,
      versions: [], manifest: null, members: [],
    })
    adminApi.listAdminClawhubVersions.mockResolvedValue({
      items: [
        nativeClawhubVersion({ id: 'source-other', skillId: 'clawhub-review-skill', version: '1.0.0' }),
        nativeClawhubVersion({ id: 'source-review', skillId: 'clawhub-review-skill', reviewRequired: true }),
      ],
      currentPublishedVersionId: null,
    })
    adminApi.publishAdminClawhubVersion.mockImplementation(async () => {
      adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [clawhubPublished], total: 1 })
      adminApi.getAdminSkill.mockResolvedValue({
        ...detail, ...clawhubPublished, description: null, sortWeight: 0,
        versions: [], manifest: null, members: [],
      })
      adminApi.listAdminClawhubVersions.mockResolvedValue({
        items: [nativeClawhubVersion({
          id: 'source-review', skillId: 'clawhub-review-skill',
          status: 'PUBLISHED', online: true, reviewRequired: true,
        })],
        currentPublishedVersionId: 'source-review',
      })
      return undefined
    })

    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-detail-native-review-demo"]').trigger('click')
    await flushPromises()
    const publishButton = wrapper.get('[data-test="clawhub-version-publish-source-review"]')
    expect(publishButton.attributes('disabled')).toBeUndefined()

    await publishButton.trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining('运行权限、依赖和安全扫描结果'),
      '确认上架原生 ClawHub 技能',
      expect.objectContaining({ confirmButtonText: '审核通过并上架', cancelButtonText: '取消' }),
    )
    expect(adminApi.publishAdminClawhubVersion).toHaveBeenCalledWith('clawhub-review-skill', 'source-review')
    expect(adminApi.publishAdminClawhubSkill).not.toHaveBeenCalled()
    expect(adminApi.publishAdminSkill).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="skill-status-native-review-demo"]').text()).toContain('已上架')
    expect(wrapper.get('[data-test="skill-drawer-publish-state"]').text()).toBe('已上架')
    expect(wrapper.get('[data-test="skill-drawer"]').text()).toContain('当前线上')
    expect(wrapper.get('[data-test="skill-drawer"]').text()).not.toContain('待适配/待审核')
    wrapper.unmount()
  })

  it('opens source versions first when the legacy source is missing a version', async () => {
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '1.2.3-rc.1' } as never)
    const clawhubDraft = {
      ...draft,
      id: 'clawhub-missing-version',
      code: 'native-missing-version',
      kind: 'SKILL' as const,
      status: 'DRAFT' as const,
      sourceFormat: 'CLAWHUB_SKILL' as const,
      sourceVersion: null,
      sourceReviewRequired: true,
    }
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [clawhubDraft], total: 1 })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail, ...clawhubDraft, description: null, sortWeight: 0,
      versions: [], manifest: null, members: [],
    })
    adminApi.listAdminClawhubVersions.mockResolvedValue({
      items: [nativeClawhubVersion({ id: 'source-missing-version', skillId: clawhubDraft.id, version: null })],
      currentPublishedVersionId: null,
    })

    const wrapper = await mountReady()
    expect(wrapper.get('[data-test="skill-publish-native-missing-version"]').attributes('disabled'))
      .toBeUndefined()
    expect(wrapper.get('[data-test="clawhub-missing-version"]').text()).toContain('待补版本号')

    await wrapper.get('[data-test="skill-publish-native-missing-version"]').trigger('click')
    await flushPromises()
    expect(prompt).not.toHaveBeenCalled()
    await wrapper.get('[data-test="clawhub-version-fill"]').trigger('click')
    await flushPromises()

    expect(prompt).toHaveBeenCalledWith(
      expect.stringContaining('严格 SemVer'),
      '补录 ClawHub 版本号',
      expect.objectContaining({ inputPattern: expect.any(RegExp), confirmButtonText: '保存版本号' }),
    )
    expect(adminApi.updateAdminClawhubSourceVersion)
      .toHaveBeenCalledWith('clawhub-missing-version', '1.2.3-rc.1')
    expect(adminApi.publishAdminClawhubSkill).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('offers the source-version completion action in native draft details', async () => {
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '2.0.0' } as never)
    const clawhubDraft = {
      ...draft,
      id: 'clawhub-detail-missing-version',
      code: 'native-detail-missing-version',
      kind: 'SKILL' as const,
      status: 'DRAFT' as const,
      sourceFormat: 'CLAWHUB_SKILL' as const,
      sourceVersion: null,
      sourceReviewRequired: true,
    }
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [clawhubDraft], total: 1 })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail,
      ...clawhubDraft,
      description: null,
      sortWeight: 0,
      versions: [],
      manifest: null,
      members: [],
    })

    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-detail-native-detail-missing-version"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="clawhub-version-missing"]').text()).toContain('补版本号')
    expect(wrapper.get('[data-test="skill-drawer-source-version"]').text()).toContain('待补版本号')

    await wrapper.get('[data-test="clawhub-version-fill"]').trigger('click')
    await flushPromises()

    expect(prompt).toHaveBeenCalledOnce()
    expect(adminApi.updateAdminClawhubSourceVersion).toHaveBeenCalledWith(
      'clawhub-detail-missing-version', '2.0.0',
    )
    expect(adminApi.publishAdminClawhubSkill).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('shows native source revisions instead of the empty YCPS table and exports a draft ZIP', async () => {
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:clawhub-source'),
      revokeObjectURL: vi.fn(),
    })
    const fileNames: string[] = []
    const anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      fileNames.push(this.download)
    })
    const clawhubDraft = {
      ...draft,
      id: 'native-export-skill',
      code: 'native-export',
      kind: 'SKILL' as const,
      sourceFormat: 'CLAWHUB_SKILL' as const,
      sourceVersion: '1.2.3',
      iconUrl: '/v1/skills/native-export-skill/icon',
    }
    const sourceRevision = nativeClawhubVersion({ id: 'source-draft', skillId: clawhubDraft.id })
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [clawhubDraft], total: 1 })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail, ...clawhubDraft, description: null, sortWeight: 0,
      versions: [], manifest: null, members: [],
    })
    adminApi.listAdminClawhubVersions.mockResolvedValue({
      items: [sourceRevision], currentPublishedVersionId: null,
    })
    adminApi.downloadAdminClawhubVersion.mockResolvedValue({
      blob: new Blob(['original-clawhub-zip']), fileName: 'native-export-1.2.3.zip',
    })
    adminApi.fetchAdminSkillIcon.mockResolvedValue(new Blob(['icon'], { type: 'image/webp' }))

    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-detail-native-export"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="skill-version-table"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-version-empty"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="clawhub-version-row-source-draft"]').text()).toContain('v1.2.3')
    expect(wrapper.get('[data-test="clawhub-version-download-source-draft"]').attributes('disabled')).toBeUndefined()

    await wrapper.get('[data-test="clawhub-version-download-source-draft"]').trigger('click')
    await flushPromises()

    expect(adminApi.downloadAdminClawhubVersion).toHaveBeenCalledWith('native-export-skill', 'source-draft')
    expect(adminApi.fetchAdminSkillIcon).toHaveBeenCalledWith('native-export-skill')
    expect(fileNames).toEqual(['native-export-1.2.3.zip', 'native-export-1.2.3.webp'])

    adminApi.fetchAdminSkillIcon.mockRejectedValueOnce(new Error('icon unavailable'))
    await wrapper.get('[data-test="clawhub-version-download-source-draft"]').trigger('click')
    await flushPromises()
    expect(fileNames).toEqual([
      'native-export-1.2.3.zip', 'native-export-1.2.3.webp', 'native-export-1.2.3.zip',
    ])
    expect(state(wrapper).errorMessage).toContain('ClawHub ZIP 已导出，但图标下载失败')
    wrapper.unmount()
    anchorClick.mockRestore()
    vi.unstubAllGlobals()
  })

  it('shows a retry action when the native package list cannot be loaded', async () => {
    const clawhubDraft = {
      ...draft, id: 'native-package-retry', code: 'native-package-retry',
      kind: 'SKILL' as const, sourceFormat: 'CLAWHUB_SKILL' as const,
      sourceVersion: '1.2.3',
    }
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [clawhubDraft], total: 1 })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail, ...clawhubDraft, description: null, sortWeight: 0,
      versions: [], manifest: null, members: [],
    })
    adminApi.listAdminClawhubVersions
      .mockRejectedValueOnce(new Error('temporary failure'))
      .mockResolvedValue({
        items: [nativeClawhubVersion({ skillId: clawhubDraft.id })],
        currentPublishedVersionId: null,
      })

    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-detail-native-package-retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="skill-drawer-source-package"]').text()).toContain('读取失败')
    expect(wrapper.get('[data-test="clawhub-version-error"]').text()).toContain('原始技能包读取失败')
    expect(wrapper.find('[data-test="clawhub-version-empty"]').exists()).toBe(false)

    await wrapper.get('[data-test="clawhub-version-retry"]').trigger('click')
    await flushPromises()

    expect(adminApi.listAdminClawhubVersions).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-test="skill-drawer-source-package"]').text()).toContain('1 个版本')
    expect(wrapper.get('[data-test="clawhub-version-row-source-1"]').text()).toContain('v1.2.3')
    wrapper.unmount()
  })

  it('downloads the current native ClawHub source from the drawer footer without using YCPS download', async () => {
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:clawhub-online'),
      revokeObjectURL: vi.fn(),
    })
    const clawhubPublished = {
      ...published,
      id: 'native-online-skill',
      code: 'native-online',
      kind: 'SKILL' as const,
      sourceFormat: 'CLAWHUB_SKILL' as const,
      sourceVersion: '2.0.0',
    }
    const onlineRevision = nativeClawhubVersion({
      id: 'source-online', skillId: clawhubPublished.id, version: '2.0.0',
      status: 'PUBLISHED', online: true,
    })
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [clawhubPublished], total: 1 })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail, ...clawhubPublished, description: null, sortWeight: 0,
      versions: [], manifest: null, members: [],
    })
    adminApi.listAdminClawhubVersions.mockResolvedValue({
      items: [onlineRevision], currentPublishedVersionId: 'source-online',
    })

    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-detail-native-online"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="clawhub-drawer-download"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-test="clawhub-drawer-download"]').text()).toContain('v2.0.0')
    const iconCallsBeforeDownload = adminApi.fetchAdminSkillIcon.mock.calls.length

    await wrapper.get('[data-test="clawhub-drawer-download"]').trigger('click')
    await flushPromises()

    expect(adminApi.downloadAdminClawhubVersion).toHaveBeenCalledWith('native-online-skill', 'source-online')
    expect(adminApi.downloadAdminSkillPackage).not.toHaveBeenCalled()
    expect(adminApi.fetchAdminSkillIcon).toHaveBeenCalledTimes(iconCallsBeforeDownload)
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('previews a new original ZIP, requires SemVer, and uploads it with the matching fingerprint', async () => {
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '2.3.4' } as never)
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const clawhubDraft = {
      ...draft,
      id: 'native-upload-skill',
      code: 'native-upload',
      kind: 'SKILL' as const,
      sourceFormat: 'CLAWHUB_SKILL' as const,
      sourceVersion: '1.2.3',
    }
    const sourceRevision = nativeClawhubVersion({ id: 'source-1', skillId: clawhubDraft.id })
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [clawhubDraft], total: 1 })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail, ...clawhubDraft, description: null, sortWeight: 0,
      versions: [], manifest: null, members: [],
    })
    adminApi.listAdminClawhubVersions
      .mockResolvedValueOnce({ items: [sourceRevision], currentPublishedVersionId: null })
      .mockResolvedValue({ items: [sourceRevision, nativeClawhubVersion({
        id: 'source-2', skillId: clawhubDraft.id, version: '2.3.4',
      })], currentPublishedVersionId: null })
    adminApi.previewAdminClawhubSkill.mockResolvedValue({
      report: {
        sourceFormat: 'CLAWHUB_SKILL', adapterVersion: '1.0.0', sourceSha256: 'f'.repeat(64),
        code: clawhubDraft.code, version: '2.3.4', status: 'REVIEW_REQUIRED',
        decisions: ['运行时权限需人工核对'], blockingReasons: [],
      },
      packageBase64: null,
    })
    adminApi.uploadAdminClawhubVersion.mockResolvedValue(
      nativeClawhubVersion({ id: 'source-2', skillId: clawhubDraft.id, version: '2.3.4' }),
    )

    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-detail-native-upload"]').trigger('click')
    await flushPromises()
    const file = await nativeClawhubZipFixture('2.3.4')
    await pickFile(wrapper, '[data-test="skill-detail-import-zip"]', file)
    await flushPromises()
    await vi.waitFor(() => expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining('运行时权限需人工核对'),
      '确认上传 ClawHub 版本',
      expect.objectContaining({ confirmButtonText: '上传并扫描' }),
    ))
    await vi.waitFor(() => expect(adminApi.uploadAdminClawhubVersion).toHaveBeenCalled())
    expect(adminApi.previewAdminClawhubSkill).toHaveBeenCalledWith(file, 'publisher', 'demo', '2.3.4')
    expect(adminApi.uploadAdminClawhubVersion).toHaveBeenCalledWith('native-upload-skill', {
      file, version: '2.3.4', expectedSha256: 'f'.repeat(64),
    })
    expect(adminApi.listAdminClawhubVersions).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('packages a native ClawHub folder from the unified detail area before preview and upload', async () => {
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '2.3.4' } as never)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const clawhubDraft = {
      ...draft,
      id: 'native-folder-skill',
      code: 'native-folder',
      kind: 'SKILL' as const,
      sourceFormat: 'CLAWHUB_SKILL' as const,
      sourceVersion: '1.2.3',
    }
    const sourceRevision = nativeClawhubVersion({ id: 'source-1', skillId: clawhubDraft.id })
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [clawhubDraft], total: 1 })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail, ...clawhubDraft, description: null, sortWeight: 0,
      versions: [], manifest: null, members: [],
    })
    adminApi.listAdminClawhubVersions
      .mockResolvedValueOnce({ items: [sourceRevision], currentPublishedVersionId: null })
      .mockResolvedValue({ items: [sourceRevision, nativeClawhubVersion({
        id: 'source-2', skillId: clawhubDraft.id, version: '2.3.4',
      })], currentPublishedVersionId: null })
    adminApi.previewAdminClawhubSkill.mockResolvedValue({
      report: {
        sourceFormat: 'CLAWHUB_SKILL', adapterVersion: '1.0.0', sourceSha256: 'c'.repeat(64),
        code: clawhubDraft.code, version: '2.3.4', status: 'IMPORTABLE',
        decisions: [], blockingReasons: [],
      },
      packageBase64: null,
    })
    adminApi.uploadAdminClawhubVersion.mockResolvedValue(
      nativeClawhubVersion({ id: 'source-2', skillId: clawhubDraft.id, version: '2.3.4' }),
    )
    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-detail-native-folder"]').trigger('click')
    await flushPromises()
    const markdown = new File([
      '---\nname: Demo Skill\nslug: demo\nversion: 2.3.4\n---\nUse the demo.\n',
    ], 'SKILL.md')
    const origin = new File([JSON.stringify({
      registry: 'https://clawhub.ai', ownerHandle: 'publisher',
      slug: '@publisher/demo', installedVersion: '2.3.4',
    })], 'origin.json')
    Object.defineProperty(markdown, 'webkitRelativePath', {
      configurable: true, value: 'demo/SKILL.md',
    })
    Object.defineProperty(origin, 'webkitRelativePath', {
      configurable: true, value: 'demo/.clawhub/origin.json',
    })
    const input = wrapper.get('[data-test="skill-detail-import-folder"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [markdown, origin] })

    await input.trigger('change')
    await flushPromises()
    await vi.waitFor(() => expect(adminApi.uploadAdminClawhubVersion).toHaveBeenCalledTimes(1))

    const prepared = adminApi.previewAdminClawhubSkill.mock.calls.at(-1)?.[0] as File
    expect(prepared.name).toBe('demo.zip')
    expect(adminApi.previewAdminClawhubSkill).toHaveBeenCalledWith(
      prepared, 'publisher', 'demo', '2.3.4',
    )
    expect(adminApi.uploadAdminClawhubVersion).toHaveBeenCalledWith('native-folder-skill', {
      file: prepared, version: '2.3.4', expectedSha256: 'c'.repeat(64),
    })
    wrapper.unmount()
  })

  it('rescans only retryable drafts and deletes only never-published native drafts', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const clawhubDraft = {
      ...draft,
      id: 'native-ops-skill',
      code: 'native-ops',
      kind: 'SKILL' as const,
      sourceFormat: 'CLAWHUB_SKILL' as const,
      sourceVersion: '1.2.3',
    }
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [clawhubDraft], total: 1 })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail, ...clawhubDraft, description: null, sortWeight: 0,
      versions: [], manifest: null, members: [],
    })
    adminApi.listAdminClawhubVersions.mockResolvedValue({
      items: [
        nativeClawhubVersion({ id: 'source-pending', skillId: clawhubDraft.id, scanStatus: 'FAILED' }),
        nativeClawhubVersion({ id: 'source-rejected', skillId: clawhubDraft.id, scanStatus: 'REJECTED' }),
        nativeClawhubVersion({ id: 'source-delete', skillId: clawhubDraft.id }),
        nativeClawhubVersion({ id: 'source-history', skillId: clawhubDraft.id, status: 'PUBLISHED' }),
      ],
      currentPublishedVersionId: null,
    })

    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-detail-native-ops"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="clawhub-version-rescan-source-pending"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="clawhub-version-rescan-source-rejected"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="clawhub-version-delete-source-delete"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-test="clawhub-version-delete-source-history"]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-test="clawhub-version-rescan-source-pending"]').trigger('click')
    await flushPromises()
    expect(adminApi.rescanAdminClawhubVersion).toHaveBeenCalledWith('native-ops-skill', 'source-pending')
    expect(adminApi.rescanAdminClawhubSkill).not.toHaveBeenCalled()

    await wrapper.get('[data-test="clawhub-version-delete-source-delete"]').trigger('click')
    await flushPromises()
    expect(adminApi.deleteAdminClawhubVersion).toHaveBeenCalledWith('native-ops-skill', 'source-delete')
    wrapper.unmount()
  })

  it('does not call a released source current after unpublish the current ClawHub badge', async () => {
    const clawhubOffline = {
      ...draft,
      id: 'native-unpublished-skill',
      code: 'native-unpublished',
      kind: 'SKILL' as const,
      status: 'UNPUBLISHED' as const,
      sourceFormat: 'CLAWHUB_SKILL' as const,
      sourceVersion: '1.2.3',
    }
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [clawhubOffline], total: 1 })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail, ...clawhubOffline, description: null, sortWeight: 0,
      versions: [], manifest: null, members: [],
    })
    adminApi.listAdminClawhubVersions.mockResolvedValue({
      items: [nativeClawhubVersion({ id: 'source-last-release', skillId: clawhubOffline.id, status: 'PUBLISHED', online: false })],
      currentPublishedVersionId: null,
    })

    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-detail-native-unpublished"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="clawhub-version-row-source-last-release"]').text()).toContain('历史已上架')
    expect(wrapper.get('[data-test="clawhub-drawer-download"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="clawhub-version-row-source-last-release"]').text()).not.toContain('当前线上')
    wrapper.unmount()
  })

  it('未勾选时保持精简提示，勾选后才展开分组批量操作', async () => {
    const wrapper = await mountReady()

    expect(wrapper.get('[data-test="skills-bulk-toolbar"]').text()).toContain('勾选条目后可批量管理状态与分类')
    expect(wrapper.find('[data-test="skills-bulk-publish"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skills-bulk-category"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skills-bulk-delete"]').exists()).toBe(false)

    await wrapper.get('[data-test="skill-select-ecommerce-ops"] input').setValue(true)

    expect(wrapper.get('[data-test="skills-selected-count"]').text()).toContain('已选 1 项')
    expect(wrapper.find('[data-test="skills-bulk-publish"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skills-bulk-category"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skills-bulk-delete"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('在新批次文件夹内写入多个 YCPS 包或按项目拆分的含图子文件夹', async () => {
    vi.stubGlobal('CompressionStream', undefined)
    const written = new Map<string, Blob>()
    const directory = (path = ''): Record<string, unknown> => ({
      getDirectoryHandle: vi.fn(async (name: string) => directory(`${path}/${name}`)),
      getFileHandle: vi.fn(async (name: string) => ({
        createWritable: vi.fn(async () => ({
          write: vi.fn(async (blob: Blob) => { written.set(`${path}/${name}`, blob) }),
          close: vi.fn(async () => undefined),
        })),
      })),
    })
    vi.stubGlobal('showDirectoryPicker', vi.fn(async () => directory()))
    adminApi.downloadAdminSkillPackage.mockImplementation(async (id: string) => ({
      blob: new Blob([`ycps-${id}`]), fileName: `${id}.ycps.zip`, md5: null, sha256: null,
    }))
    adminApi.downloadAdminSkillPackageBundle.mockImplementation(async (id: string) => {
      const archive = await buildZip([
        { path: `${id}/${id}.ycps.zip`, bytes: new TextEncoder().encode(`ycps-${id}`) },
        { path: `${id}/${id}-icon.png`, bytes: new Uint8Array([1, 2, 3]) },
      ])
      return {
        blob: new Blob([archive as BlobPart]), fileName: `${id}.bundle.zip`, assetCount: 1,
      }
    })
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-select-data-analyst"] input').setValue(true)
    await wrapper.get('[data-test="skill-select-example-skill"] input').setValue(true)

    expect(wrapper.get('[data-test="skills-bulk-download-default"]').text()).toContain('YCPS 含图完整包')
    await wrapper.get('[data-test="skills-bulk-download-default"]').trigger('click')
    await flushPromises()
    await vi.waitFor(() => {
      expect(adminApi.downloadAdminSkillPackageBundle).toHaveBeenCalledWith('skill-1')
      expect(adminApi.downloadAdminSkillPackageBundle).toHaveBeenCalledWith('skill-4')
      expect([...written.keys()].some((path) => /\/skills-with-assets-\d{8}-\d{6}-\d{3}\/data-analyst-1\.2\.0\/skill-1\.ycps\.zip$/u.test(path))).toBe(true)
      expect([...written.keys()].some((path) => /\/skills-with-assets-\d{8}-\d{6}-\d{3}\/data-analyst-1\.2\.0\/skill-1-icon\.png$/u.test(path))).toBe(true)
      expect([...written.keys()].some((path) => /\/skills-with-assets-\d{8}-\d{6}-\d{3}\/example-skill-1\.0\.0\/skill-4\.ycps\.zip$/u.test(path))).toBe(true)
    })

    await wrapper.get('[data-test="skills-bulk-download-toggle"]').trigger('click')
    expect(wrapper.get('[data-test="skills-bulk-download-menu"]').text()).toContain('仅 YCPS ZIP（不含图）')
    await wrapper.get('[data-test="skills-bulk-download-package"]').trigger('click')
    await flushPromises()
    await vi.waitFor(() => {
      expect(adminApi.downloadAdminSkillPackage).toHaveBeenCalledWith('skill-1')
      expect(adminApi.downloadAdminSkillPackage).toHaveBeenCalledWith('skill-4')
      expect([...written.keys()].some((path) => /\/skills-ycps-\d{8}-\d{6}-\d{3}\/skill-1\.ycps\.zip$/u.test(path))).toBe(true)
      expect([...written.keys()].some((path) => /\/skills-ycps-\d{8}-\d{6}-\d{3}\/skill-4\.ycps\.zip$/u.test(path))).toBe(true)
    })

    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('混选默认全部下载，复用一次目录授权并按格式分别导出', async () => {
    const written = new Map<string, Blob>()
    const directory = (path = ''): Record<string, unknown> => ({
      getDirectoryHandle: vi.fn(async (name: string) => directory(`${path}/${name}`)),
      getFileHandle: vi.fn(async (name: string) => ({
        createWritable: vi.fn(async () => ({
          write: vi.fn(async (blob: Blob) => { written.set(`${path}/${name}`, blob) }),
          close: vi.fn(async () => undefined),
        })),
      })),
    })
    const picker = vi.fn(async () => directory())
    vi.stubGlobal('showDirectoryPicker', picker)
    const clawhubItem = {
      ...draft,
      id: 'clawhub-bulk-export',
      code: 'clawhub-demo',
      status: 'DRAFT',
      publishedVersion: null,
      sourceFormat: 'CLAWHUB_SKILL',
      sourceVersion: '2.3.4',
      iconUrl: '/v1/skills/clawhub-bulk-export/icon',
    }
    const noOnlineYcps = row({ id: 'ycps-no-online', code: 'ycps-no-online', publishedVersion: null })
    adminApi.listAdminSkills.mockResolvedValue({
      ...catalogPage, items: [published, noOnlineYcps, clawhubItem], total: 3,
    })
    adminApi.downloadAdminClawhubSkillPackage.mockResolvedValue({
      blob: new Blob(['original-source']), fileName: 'clawhub-demo.zip',
    })
    adminApi.fetchAdminSkillIcon.mockResolvedValue(new Blob(['icon'], { type: 'image/svg+xml' }))
    adminApi.downloadAdminSkillPackage.mockResolvedValue({
      blob: new Blob(['ycps-source']), fileName: 'skill-1.ycps.zip', md5: null, sha256: null,
    })
    const archive = await buildZip([
      { path: 'data-analyst/skill-1.ycps.zip', bytes: new TextEncoder().encode('ycps-source') },
      { path: 'data-analyst/skill-1-icon.png', bytes: new Uint8Array([1, 2, 3]) },
    ])
    adminApi.downloadAdminSkillPackageBundle.mockResolvedValue({
      blob: new Blob([archive as BlobPart]), fileName: 'skill-1.bundle.zip', assetCount: 1,
    })
    const success = vi.spyOn(ElMessage, 'success')
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-select-data-analyst"] input').setValue(true)
    await wrapper.get('[data-test="skill-select-ycps-no-online"] input').setValue(true)
    await wrapper.get('[data-test="skill-select-clawhub-demo"] input').setValue(true)
    expect(wrapper.get('[data-test="skills-bulk-download-default"]').text()).toContain('全部下载')
    await wrapper.get('[data-test="skills-bulk-download-toggle"]').trigger('click')
    expect(wrapper.get('[data-test="skills-bulk-download-all"]').text()).toContain('YCPS 含图完整包 + ClawHub 原始 ZIP')
    expect(wrapper.find('[data-test="skills-bulk-download-bundle"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skills-bulk-download-package"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skills-bulk-download-clawhub"]').exists()).toBe(true)

    await wrapper.get('[data-test="skills-bulk-download-default"]').trigger('click')
    await flushPromises()
    await vi.waitFor(() => {
      expect(picker).toHaveBeenCalledTimes(1)
      expect(adminApi.downloadAdminSkillPackageBundle).toHaveBeenCalledWith('skill-1')
      expect(adminApi.downloadAdminClawhubSkillPackage).toHaveBeenCalledWith('clawhub-bulk-export')
    })
    expect(adminApi.downloadAdminSkillPackage).not.toHaveBeenCalled()
    expect([...written.keys()].some(path => /\/skills-all-\d{8}-\d{6}-\d{3}\/data-analyst-1\.2\.0\/skill-1\.ycps\.zip$/u.test(path)))
      .toBe(true)
    expect([...written.keys()].some(path => /\/skills-all-\d{8}-\d{6}-\d{3}\/data-analyst-1\.2\.0\/skill-1-icon\.png$/u.test(path)))
      .toBe(true)
    expect([...written.keys()].some(path => /\/skills-all-\d{8}-\d{6}-\d{3}\/clawhub-demo\.zip$/u.test(path)))
      .toBe(true)
    expect([...written.keys()].some(path => /\/skills-all-\d{8}-\d{6}-\d{3}\/clawhub-demo\.svg$/u.test(path)))
      .toBe(true)
    expect(adminApi.fetchAdminSkillIcon).toHaveBeenCalledWith('clawhub-bulk-export')
    expect(success).toHaveBeenCalledWith(expect.stringContaining('跳过 1 个无线上版本项'))

    adminApi.fetchAdminSkillIcon.mockRejectedValueOnce(new Error('icon unavailable'))
    await wrapper.get('[data-test="skills-bulk-download-toggle"]').trigger('click')
    await wrapper.get('[data-test="skills-bulk-download-clawhub"]').trigger('click')
    await flushPromises()
    expect(adminApi.downloadAdminClawhubSkillPackage).toHaveBeenCalledTimes(2)
    expect(adminApi.downloadAdminSkillPackageBundle).toHaveBeenCalledTimes(1)
    expect([...written.keys()].some(path => /\/skills-clawhub-\d{8}-\d{6}-\d{3}\/clawhub-demo\.zip$/u.test(path)))
      .toBe(true)
    expect(state(wrapper).errorMessage).toContain('图标失败 1 个')

    await wrapper.get('[data-test="skills-bulk-download-toggle"]').trigger('click')
    await wrapper.get('[data-test="skills-bulk-download-package"]').trigger('click')
    await flushPromises()
    expect(adminApi.downloadAdminSkillPackage).toHaveBeenCalledWith('skill-1')
    expect(adminApi.downloadAdminClawhubSkillPackage).toHaveBeenCalledTimes(2)

    let releasePicker: ((value: Record<string, unknown>) => void) | undefined
    picker.mockImplementationOnce(() => new Promise<Record<string, unknown>>((resolve) => { releasePicker = resolve }))
    await wrapper.get('[data-test="skills-bulk-download-toggle"]').trigger('click')
    await wrapper.get('[data-test="skills-bulk-download-all"]').trigger('click')
    expect(wrapper.get('[data-test="skills-bulk-download-default"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="skills-bulk-download-toggle"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="skills-bulk-download-menu"]').exists()).toBe(false)
    releasePicker?.(directory())
    await flushPromises()

    wrapper.unmount()
    success.mockRestore()
    vi.unstubAllGlobals()
  })

  it('混选在无目录权限浏览器分别下载两种 ZIP，改选单类时更新默认动作', async () => {
    vi.stubGlobal('URL', { ...URL, createObjectURL: vi.fn(() => 'blob:bulk-mixed'), revokeObjectURL: vi.fn() })
    vi.stubGlobal('showDirectoryPicker', undefined)
    const fileNames: string[] = []
    const anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      fileNames.push(this.download)
    })
    const clawhubItem = row({
      id: 'clawhub-browser', code: 'clawhub-browser', status: 'DRAFT',
      publishedVersion: null, sourceFormat: 'CLAWHUB_SKILL', sourceVersion: '2.3.4',
    })
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [published, clawhubItem], total: 2 })
    adminApi.downloadAdminSkillPackageBundle.mockResolvedValue({
      blob: new Blob(['bundle']), fileName: 'skill-1.bundle.zip', assetCount: 1,
    })
    adminApi.downloadAdminClawhubSkillPackage.mockResolvedValue({
      blob: new Blob(['original']), fileName: 'clawhub-browser.zip',
    })
    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-select-data-analyst"] input').setValue(true)
    await wrapper.get('[data-test="skill-select-clawhub-browser"] input').setValue(true)

    await wrapper.get('[data-test="skills-bulk-download-default"]').trigger('click')
    await vi.waitFor(() => expect(fileNames).toEqual(['skill-1.bundle.zip', 'clawhub-browser.zip']))
    expect(adminApi.downloadAdminSkillPackage).not.toHaveBeenCalled()

    await wrapper.get('[data-test="skills-bulk-download-toggle"]').trigger('click')
    await wrapper.get('[data-test="skill-select-data-analyst"] input').setValue(false)
    expect(wrapper.find('[data-test="skills-bulk-download-menu"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="skills-bulk-download-default"]').text()).toContain('ClawHub ZIP')
    expect(wrapper.find('[data-test="skills-bulk-download-toggle"]').exists()).toBe(false)
    await wrapper.get('[data-test="skills-bulk-download-default"]').trigger('click')
    await vi.waitFor(() => expect(fileNames).toHaveLength(3))
    expect(fileNames[2]).toBe('clawhub-browser.zip')

    wrapper.unmount()
    anchorClick.mockRestore()
    vi.unstubAllGlobals()
  })

  it('在 Safari、Firefox 和手机浏览器中按项目分别下载，不合并批次 ZIP', async () => {
    const createObjectURL = vi.fn(() => 'blob:bulk-package')
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL: vi.fn() })
    vi.stubGlobal('showDirectoryPicker', undefined)
    const anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    adminApi.downloadAdminSkillPackage.mockImplementation(async (id: string) => ({
      blob: new Blob([`ycps-${id}`]), fileName: `${id}.ycps.zip`, md5: null, sha256: null,
    }))
    adminApi.downloadAdminSkillPackageBundle.mockImplementation(async (id: string) => ({
      blob: new Blob([`bundle-${id}`]), fileName: `${id}.bundle.zip`, assetCount: 1,
    }))
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-select-data-analyst"] input').setValue(true)
    await wrapper.get('[data-test="skill-select-example-skill"] input').setValue(true)

    await wrapper.get('[data-test="skills-bulk-download-default"]').trigger('click')
    await flushPromises()
    await vi.waitFor(() => {
      expect(adminApi.downloadAdminSkillPackageBundle).toHaveBeenCalledWith('skill-1')
      expect(adminApi.downloadAdminSkillPackageBundle).toHaveBeenCalledWith('skill-4')
      expect(anchorClick).toHaveBeenCalledTimes(2)
    })

    await wrapper.get('[data-test="skills-bulk-download-toggle"]').trigger('click')
    await wrapper.get('[data-test="skills-bulk-download-package"]').trigger('click')
    await flushPromises()
    await vi.waitFor(() => {
      expect(adminApi.downloadAdminSkillPackage).toHaveBeenCalledWith('skill-1')
      expect(adminApi.downloadAdminSkillPackage).toHaveBeenCalledWith('skill-4')
      expect(anchorClick).toHaveBeenCalledTimes(4)
      expect(createObjectURL).toHaveBeenCalledTimes(4)
    })

    wrapper.unmount()
    anchorClick.mockRestore()
    vi.unstubAllGlobals()
  })

  it('回收站可全选后直接批量恢复或彻底删除，不弹二次确认', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm')
    const prompt = vi.spyOn(ElMessageBox, 'prompt')
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skills-recycle-open"]').trigger('click')
    await flushPromises()

    expect(adminApi.listDeletedAdminSkills).toHaveBeenCalledWith({
      page: 0, size: 100, query: '', kind: 'SKILL',
    })
    expect(wrapper.get('[data-test="skills-recycle-dialog"]').text()).toContain('技能回收站（保留 30 天）')
    expect(wrapper.get('[data-test="skills-recycle-table"]').text()).toContain('归档写作助手')

    await wrapper.get('[data-test="skills-recycle-select-all"] input').setValue(true)
    await wrapper.get('[data-test="skills-recycle-restore"]').trigger('click')
    await flushPromises()
    expect(adminApi.restoreAdminSkill.mock.calls.map(([id]) => id)).toEqual(['deleted-1', 'deleted-2'])
    expect(confirm).not.toHaveBeenCalled()
    expect(prompt).not.toHaveBeenCalled()

    await wrapper.get('[data-test="skills-recycle-select-all"] input').setValue(true)
    await wrapper.get('[data-test="skills-recycle-purge"]').trigger('click')
    await flushPromises()
    expect(adminApi.purgeAdminSkill.mock.calls.map(([id]) => id)).toEqual(['deleted-1', 'deleted-2'])
    expect(confirm).not.toHaveBeenCalled()
    expect(prompt).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('renders the kind tag with the documented colour and the English name as a secondary line', async () => {
    const wrapper = await mountReady()

    expect(wrapper.get('[data-test="skill-kind-data-analyst"]').text()).toBe('技能')
    expect(wrapper.get('[data-test="skill-kind-content-writer"]').text()).toBe('专家')
    expect(wrapper.get('[data-test="skill-kind-ecommerce-ops"]').text()).toBe('专家团')

    // 专家团=danger、专家=warning、技能=info（ElTag 的类型落在 BEM 修饰类上，而 ElTag 的根节点
    // 包在 <Transition> 里，VTU 默认把它替换成 transition-stub，所以类名要去 stub 内部的 span 上取）
    expect(wrapper.get('[data-test="skill-kind-ecommerce-ops"]').get('.el-tag').classes()).toContain('el-tag--danger')
    expect(wrapper.get('[data-test="skill-kind-content-writer"]').get('.el-tag').classes()).toContain('el-tag--warning')
    expect(wrapper.get('[data-test="skill-kind-data-analyst"]').get('.el-tag').classes()).toContain('el-tag--info')

    // 英文名作为次要行展示，未填英文的行不渲染
    expect(wrapper.get('[data-test="skill-name-en-data-analyst"]').text()).toBe('Data Analyst')
    expect(wrapper.find('[data-test="skill-name-en-content-writer"]').exists()).toBe(false)

    // 仅专家团显示成员数；单人专家与技能的旧成员记录不占用列表空间。
    expect(wrapper.get('[data-test="skill-member-count-ecommerce-ops"]').text()).toContain('4')
    expect(wrapper.find('[data-test="skill-member-count-data-analyst"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-member-count-content-writer"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps the list scoped to SKILL and hides the cross-kind filter', async () => {
    const wrapper = await mountReady()

    // 页面上不再有类型筛选下拉（类型按页面作用域固定），但每一行仍展示类型标签
    expect(wrapper.find('[data-test="skills-kind"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="skill-kind-data-analyst"]').text()).toBe('技能')

    await wrapper.get('[data-test="skills-filter"]').trigger('click')
    await flushPromises()

    expect(adminApi.listAdminSkills).toHaveBeenLastCalledWith({
      page: 0, size: 20, query: '', status: '', category: '', kind: 'SKILL', sort: 'created_at', direction: 'desc',
    })
    expect(routerState.replace).toHaveBeenCalledWith({ query: {} })
    wrapper.unmount()
  })

  it('flags a ready-but-unpublished version with the pending badge', async () => {
    const wrapper = await mountReady()

    const badge = wrapper.get('[data-test="skill-pending-example-skill"]')
    expect(badge.text()).toBe('有新版本待发布')
    expect(wrapper.find('[data-test="skill-pending-data-analyst"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('applies the search, status and category filters and writes them to the URL query', async () => {
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skills-query"]').setValue(' 报表 ')
    state(wrapper).params.status = 'PUBLISHED'
    state(wrapper).params.category = '数据分析'
    await wrapper.get('[data-test="skills-filter"]').trigger('click')
    await flushPromises()

    expect(adminApi.listAdminSkills).toHaveBeenLastCalledWith({
      page: 0, size: 20, query: '报表', status: 'PUBLISHED', category: '数据分析', kind: 'SKILL', sort: 'created_at', direction: 'desc',
    })
    expect(routerState.replace).toHaveBeenCalledWith({
      query: { query: '报表', status: 'PUBLISHED', category: '数据分析' },
    })
    wrapper.unmount()
  })

  it('resets every filter and returns to the first page', async () => {
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skills-query"]').setValue('报表')
    await wrapper.get('[data-test="skills-filter"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="skills-pagination"]').exists()).toBe(true)
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, page: 2 })

    await wrapper.get('[data-test="skills-reset"]').trigger('click')
    await flushPromises()

    expect(adminApi.listAdminSkills).toHaveBeenLastCalledWith({
      page: 0, size: 20, query: '', status: '', category: '', kind: 'SKILL', sort: 'created_at', direction: 'desc',
    })
    wrapper.unmount()
  })

  it('shows the empty state when the platform has no skill yet', async () => {
    adminApi.listAdminSkills.mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
    const wrapper = await mountReady()

    expect(wrapper.get('[data-test="skills-empty"]').text()).toContain('暂无 Skill')
    wrapper.unmount()
  })

  it('maps flat {code,message} failures to Chinese copy instead of the server message', async () => {
    adminApi.listAdminSkills.mockRejectedValue(apiFailure('skill_storage_unavailable', 503, 'io timeout'))
    const error = vi.spyOn(ElMessage, 'error')
    const wrapper = await mountReady()

    expect(error).toHaveBeenCalledWith(expect.objectContaining({ message: '技能存储暂时不可用，请稍后重试。' }))
    expect(wrapper.findAll('[data-test="skills-table"] tbody tr')).toHaveLength(1)
    expect(wrapper.get('[data-test="skills-empty"]').text()).toContain('暂无 Skill')
    wrapper.unmount()
  })

  it('maps invalid_skill_kind from the list filter to Chinese copy', async () => {
    adminApi.listAdminSkills.mockRejectedValue(apiFailure('invalid_skill_kind', 422, 'bad kind'))
    const error = vi.spyOn(ElMessage, 'error')
    const wrapper = await mountReady()

    expect(error).toHaveBeenCalledWith(expect.objectContaining({
      message: '技能类型不合法：只能在创建时选择技能、专家或专家团，创建后不可修改。',
    }))
    expect(wrapper.text()).not.toContain('bad kind')
    wrapper.unmount()
  })

  it('asks for confirmation before unpublishing and then reloads the list', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-unpublish-data-analyst"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(
      '下架后桌面客户端将立即无法搜索与下载该技能，已安装用户不受影响。',
      '确认下架 Skill',
      expect.objectContaining({ type: 'warning' }),
    )
    expect(adminApi.unpublishAdminSkill).toHaveBeenCalledWith('skill-1')
    expect(adminApi.listAdminSkills).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('does nothing when the unpublish confirmation is dismissed', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel' as never)
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-unpublish-data-analyst"]').trigger('click')
    await flushPromises()

    expect(adminApi.unpublishAdminSkill).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('publishes drafts without a confirmation and surfaces skill_has_no_ready_version', async () => {
    adminApi.publishAdminSkill.mockRejectedValueOnce(apiFailure('skill_has_no_ready_version', 409))
    const error = vi.spyOn(ElMessage, 'error')
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-publish-content-writer"]').trigger('click')
    await flushPromises()

    expect(adminApi.publishAdminSkill).toHaveBeenCalledWith('skill-2')
    expect(error).toHaveBeenCalledWith(expect.objectContaining({
      message: '该技能还没有可发布（已就绪）的版本，请先上传技能包。',
    }))
    wrapper.unmount()
  })

  it('disables delete for published skills with the 需先下架 tooltip', async () => {
    const wrapper = await mountReady()

    const publishedDelete = wrapper.get('[data-test="skill-delete-data-analyst"]')
    expect(publishedDelete.attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="skill-delete-content-writer"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('requires typing the exact code before deleting a skill', async () => {
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: 'ecommerce-ops' } as never)
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-delete-ecommerce-ops"]').trigger('click')
    await flushPromises()

    expect(prompt).toHaveBeenCalledTimes(1)
    const [message, title, options] = prompt.mock.calls[0] as unknown as [
      unknown, string, { inputPlaceholder?: string; inputValidator?: (value: string) => unknown },
    ]
    const content = expectStandalonePromptKeyword(message, 'ecommerce-ops')
    expect(content[0]?.children).toBe('删除后将移入回收站并立即从桌面端隐藏，30 天内可恢复。')
    expect(content[2]?.children).toBe('要确认，请在下方输入上方技能标识。')
    expect(title).toBe('确认删除 Skill')
    expect(options.inputPlaceholder).toBe('ecommerce-ops')
    expect(options.inputValidator?.('wrong-code')).toBe('输入的技能标识不一致')
    expect(options.inputValidator?.('ecommerce-ops')).toBe(true)
    expect(adminApi.deleteAdminSkill).toHaveBeenCalledWith('skill-3')
    wrapper.unmount()
  })

  it('cancels the delete when the prompt is dismissed', async () => {
    vi.spyOn(ElMessageBox, 'prompt').mockRejectedValue('cancel' as never)
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-delete-ecommerce-ops"]').trigger('click')
    await flushPromises()

    expect(adminApi.deleteAdminSkill).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('pages through the 0-based API while the pagination shows 1-based pages', async () => {
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, total: 60, page: 0, size: 20 })
    const wrapper = await mountReady()

    const pager = wrapper.get('.admin-pagination')
    expect(pager.text()).toContain('共 60 个 Skill')
    expect(wrapper.findAll('.el-pager li').length).toBeGreaterThanOrEqual(3)

    await wrapper.findAll('.el-pager li').at(1)!.trigger('click')
    await flushPromises()

    expect(adminApi.listAdminSkills).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
    wrapper.unmount()
  })

  // G4：列表要能直接回答「这个包能不能安全下架」，不必逐个点开关系抽屉
  it('renders both relation counts on every list row', async () => {
    adminApi.listAdminSkills.mockResolvedValue({
      ...catalogPage,
      items: [
        { ...published, dependencyCount: 2, dependentCount: 5 },
        { ...draft, dependencyCount: 0, dependentCount: 1 },
      ],
    })

    const wrapper = await mountReady()

    expect(wrapper.get('[data-test="skill-relation-counts-data-analyst"]').text()).toBe('2 / 5')
    expect(wrapper.get('[data-test="skill-relation-counts-content-writer"]').text()).toBe('0 / 1')
    expect(wrapper.get('[data-test="skill-relations-data-analyst"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-test="skill-relations-content-writer"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('disables the relation action when a package has no dependencies or dependents', async () => {
    const wrapper = await mountReady()

    expect(wrapper.get('[data-test="skill-relation-counts-data-analyst"]').text()).toBe('0 / 0')
    const action = wrapper.get('[data-test="skill-relations-data-analyst"]')
    expect(action.attributes('disabled')).toBeDefined()
    await action.trigger('click')
    expect(adminApi.fetchSkillRelations).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  // A21：抽屉一次读取双向关系，正向看依赖、反向看被引用
  it('opens the relations drawer with both directions in one read', async () => {
    adminApi.listAdminSkills.mockResolvedValue({
      ...catalogPage,
      items: [{ ...published, dependencyCount: 1, dependentCount: 1 }],
    })
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-relations-data-analyst"]').trigger('click')
    await flushPromises()

    expect(adminApi.fetchSkillRelations).toHaveBeenCalledWith('skill-1')
    const dependsOn = wrapper.get('[data-test="skill-relations-depends-on"]')
    expect(dependsOn.text()).toContain('budget-analysis')
    const dependedOnBy = wrapper.get('[data-test="skill-relations-depended-on-by"]')
    expect(dependedOnBy.text()).toContain('finance-analyst')
    wrapper.unmount()
  })

  // A21：未发布包没有依赖边，但仍要展示谁在引用它
  it('explains an empty forward direction for a package without a published revision', async () => {
    adminApi.listAdminSkills.mockResolvedValue({
      ...catalogPage,
      items: [{ ...published, dependencyCount: 0, dependentCount: 1 }],
    })
    adminApi.fetchSkillRelations.mockResolvedValue({
      id: 'skill-1',
      code: 'data-analyst',
      kind: 'SKILL',
      version: null,
      revision: null,
      dependsOn: [],
      dependedOnBy: [
        {
          ownerSkillId: 'owner-skill-1',
          ownerCode: 'finance-analyst',
          ownerKind: 'EXPERT',
          ownerVersionId: 'owner-version-1',
          ownerVersion: '3.1.0',
          ownerRevision: 4,
          ownerStatus: 'PUBLISHED',
          ownerOnline: true,
          key: 'data-analyst',
          relation: 'SKILL_BINDING',
          required: true,
          targetVersion: '1.2.0',
          targetRevision: 2,
        },
      ],
    })

    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-relations-data-analyst"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="skill-relations-depends-on-empty"]').text())
      .toContain('该包还没有已发布版本，暂无依赖关系。')
    expect(wrapper.get('[data-test="skill-relations-depended-on-by"]').text())
      .toContain('finance-analyst')
    wrapper.unmount()
  })

  // A22：编码预检在新建对话框内生效，占用时给出占用者的 kind 与状态
  it('warns inside the create dialog when the code is already taken', async () => {
    vi.useFakeTimers()
    adminApi.fetchSkillCodeAvailability.mockResolvedValue({
      code: 'data-analyst',
      available: false,
      occupiedBy: { id: 'skill-1', code: 'data-analyst', name: '数据分析助手', kind: 'EXPERT', status: 'PUBLISHED' },
    })

    try {
      const wrapper = await mountReady()

      await wrapper.get('[data-test="skill-create-open"]').trigger('click')
      await flushPromises()
      await wrapper.get('[data-test="skill-form-code"]').setValue('data-analyst')
      await vi.advanceTimersByTimeAsync(400)
      await flushPromises()

      expect(adminApi.fetchSkillCodeAvailability).toHaveBeenCalledWith('data-analyst')
      const hint = wrapper.get('[data-test="skill-form-code-taken"]')
      expect(hint.text()).toContain('专家')
      expect(hint.text()).toContain('已上架')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  // A22：可用时给出正向反馈，避免管理员以为检查没生效
  it('confirms inside the create dialog when the code is free', async () => {
    vi.useFakeTimers()

    try {
      const wrapper = await mountReady()

      await wrapper.get('[data-test="skill-create-open"]').trigger('click')
      await flushPromises()
      await wrapper.get('[data-test="skill-form-code"]').setValue('brand-new')
      await vi.advanceTimersByTimeAsync(400)
      await flushPromises()

      expect(adminApi.fetchSkillCodeAvailability).toHaveBeenCalledWith('brand-new')
      expect(wrapper.get('[data-test="skill-form-code-available"]').text()).toContain('编码可用。')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  // A22：提交前再查一次，占用则不发创建请求
  it('refuses to submit when the code turns out to be taken', async () => {
    adminApi.fetchSkillCodeAvailability.mockResolvedValue({
      code: 'brand-new',
      available: false,
      occupiedBy: { id: 'skill-7', code: 'brand-new', name: '被占用的编码', kind: 'TEAM', status: 'DRAFT' },
    })
    const error = vi.spyOn(ElMessage, 'error')

    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="skill-form-code"]').setValue('brand-new')
    await wrapper.get('[data-test="skill-form-name"]').setValue('品牌助手')
    await wrapper.get('[data-test="skill-form-summary"]').setValue('写作品牌内容')
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    expect(error).toHaveBeenLastCalledWith(expect.objectContaining({
      message: expect.stringContaining('专家团'),
    }))
    wrapper.unmount()
  })
})

describe('SkillCatalogView 技能分类管理', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // 词表有模块级缓存：前面的 describe 也会挂载 SKILL 页，必须清掉再断言请求次数
    resetExpertCategoryCache()
    routerState.route.query = {}
    adminApi.listAdminSkills.mockResolvedValue(catalogPage)
    adminApi.getAdminSkill.mockResolvedValue(detail)
    adminApi.listSkillCategories.mockResolvedValue({
      kind: 'SKILL',
      categories: [
        { id: 'c1', kind: 'SKILL', code: 'documents-data', name: '文档与数据',
          nameEn: 'Documents & Data', sortOrder: 1, enabled: true, usageCount: 2 },
        { id: 'c2', kind: 'SKILL', code: 'research-retrieval', name: '研究与检索',
          nameEn: 'Research & Retrieval', sortOrder: 2, enabled: true, usageCount: 0 },
      ],
    })
    adminApi.createSkillCategory.mockResolvedValue({
      id: 'c3', kind: 'SKILL', code: 'new-cat', name: '新分类',
      nameEn: null, sortOrder: 8, enabled: true, usageCount: 0,
    })
    adminApi.updateSkillCategory.mockResolvedValue({
      id: 'c1', kind: 'SKILL', code: 'documents-data', name: '文档与资料',
      nameEn: 'Documents & Data', sortOrder: 1, enabled: true, usageCount: 2,
    })
    mockMemberApi()
  })

  afterEach(() => {
    ElMessage.closeAll()
    document.querySelectorAll('.el-message').forEach((message) => message.remove())
  })

  // 需求：技能页也要能管理技能分类（新增 / 修改 / 删除）
  it('技能页提供管理分类入口，按 SKILL 作用域拉词表', async () => {
    const wrapper = await mountReady()

    // 技能页的二级分类筛选用受控词表，不再是「从数据里收集」
    expect(adminApi.listSkillCategories).toHaveBeenCalledWith('SKILL')

    await wrapper.get('[data-test="skills-category-manage"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="category-manage-count-documents-data"]').text()).toContain('2')
    // 占用中的分类禁用删除
    expect(wrapper.get('[data-test="category-manage-delete-documents-data"]').attributes('disabled'))
      .toBeDefined()
    expect(wrapper.find('[data-test="category-manage-edit-documents-data"]').exists()).toBe(true)
    // 需求：技能分类不需要封面上传，封面入口只在专家分类出现
    expect(wrapper.find('[data-test="category-manage-cover-documents-data"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="category-manage-image-documents-data"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('技能页修改分类名走 A25，并提示会同步迁移该分类下的技能', async () => {
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skills-category-manage"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="category-manage-edit-documents-data"]').trigger('click')
    await flushPromises()

    // 提示改名会把在用的技能包一起改过去
    expect(wrapper.get('[data-test="category-form-edit-hint"]').text())
      .toContain('技能包')
    expect((wrapper.get('[data-test="category-form-name"]').element as HTMLInputElement).value)
      .toBe('文档与数据')

    await wrapper.get('[data-test="category-form-name"]').setValue('文档与资料')
    await wrapper.get('[data-test="category-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.updateSkillCategory).toHaveBeenCalledWith('SKILL', 'documents-data', {
      name: '文档与资料', nameEn: 'Documents & Data',
    })
    // 改名后重新拉词表
    expect(adminApi.listSkillCategories).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('技能页新增分类走 A23，并把 kind 固定为 SKILL', async () => {
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skills-category-manage"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="category-form-name"]').setValue('新分类')
    await wrapper.get('[data-test="category-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.createSkillCategory).toHaveBeenCalledWith({
      kind: 'SKILL', name: '新分类', nameEn: null,
    })
    wrapper.unmount()
  })
})

describe('SkillCatalogView 新增与编辑', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routerState.route.query = {}
    adminApi.listAdminSkills.mockResolvedValue(catalogPage)
    adminApi.getAdminSkill.mockResolvedValue(detail)
    adminApi.createAdminSkill.mockResolvedValue({ ...detail, id: 'skill-9', code: 'brand-new' })
    adminApi.updateAdminSkill.mockResolvedValue(detail)
    skillsApi.listSkills.mockResolvedValue(previewCatalog)
    skillsApi.getSkill.mockResolvedValue(previewDetail)
    mockMemberApi()
  })

  afterEach(() => {
    ElMessage.closeAll()
    document.querySelectorAll('.el-message').forEach((message) => message.remove())
  })

  it('validates the create form before touching the API', async () => {
    const error = vi.spyOn(ElMessage, 'error')
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    expect(error).toHaveBeenLastCalledWith(expect.objectContaining({
      message: '技能标识只能由小写字母和数字组成，单词之间用连字符（-）分隔，例如 data-analyst；最长 64 位。',
    }))

    await wrapper.get('[data-test="skill-form-code"]').setValue('brand-new')
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()
    expect(error).toHaveBeenLastCalledWith(expect.objectContaining({ message: '请填写技能名称。' }))

    await wrapper.get('[data-test="skill-form-name"]').setValue('品牌助手')
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()
    expect(error).toHaveBeenLastCalledWith(expect.objectContaining({ message: '请填写技能简介。' }))

    await wrapper.get('[data-test="skill-form-summary"]').setValue('帮助品牌生成内容')
    await wrapper.get('[data-test="skill-form-min-client"]').setValue('2026-6')
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()
    expect(error).toHaveBeenLastCalledWith(expect.objectContaining({
      message: '最低客户端版本格式应形如 2026.6.0。',
    }))

    await wrapper.get('[data-test="skill-form-min-client"]').setValue('2026.6.0')
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.createAdminSkill).toHaveBeenCalledWith(expect.objectContaining({
      code: 'brand-new',
      name: '品牌助手',
      summary: '帮助品牌生成内容',
      minClientVersion: '2026.6.0',
    }))
    wrapper.unmount()
  })

  it('sends the kind and the three English fields on create, defaulting the kind to SKILL', async () => {
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()
    // 不选类型也能建，且 kind 明确随请求体发出
    expect(state(wrapper).form.kind).toBe('SKILL')

    await wrapper.get('[data-test="skill-form-code"]').setValue('brand-new')
    await wrapper.get('[data-test="skill-form-name"]').setValue('品牌助手')
    await wrapper.get('[data-test="skill-form-name-en"]').setValue('Brand Assistant')
    await wrapper.get('[data-test="skill-form-summary"]').setValue('帮助品牌生成内容')
    await wrapper.get('[data-test="skill-form-summary-en"]').setValue('Helps brands write copy')
    await wrapper.get('[data-test="skill-form-description-en"]').setValue('Detailed English copy')
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.createAdminSkill).toHaveBeenCalledWith(expect.objectContaining({
      kind: 'SKILL',
      name: '品牌助手',
      nameEn: 'Brand Assistant',
      summaryEn: 'Helps brands write copy',
      descriptionEn: 'Detailed English copy',
    }))
    wrapper.unmount()
  })

  it('offers only 技能 on the Skill page and always submits kind=SKILL', async () => {
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()

    // 页面作用域是 SKILL，因此类型只有「技能」一个选项，且是默认值
    const kindRadios = wrapper.get('[data-test="skill-form-kind"]')
    expect(kindRadios.findAll('label.el-radio-button')).toHaveLength(1)
    expect(kindRadios.text()).toContain('技能')
    expect(state(wrapper).form.kind).toBe('SKILL')

    await wrapper.get('[data-test="skill-form-code"]').setValue('brand-new')
    await wrapper.get('[data-test="skill-form-name"]').setValue('品牌助手')
    await wrapper.get('[data-test="skill-form-summary"]').setValue('写作品牌内容')
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.createAdminSkill).toHaveBeenCalledWith(expect.objectContaining({ kind: 'SKILL' }))
    wrapper.unmount()
  })

  it('keeps English optional but applies the Chinese length caps to both languages', async () => {
    const error = vi.spyOn(ElMessage, 'error')
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="skill-form-code"]').setValue('brand-new')
    await wrapper.get('[data-test="skill-form-name"]').setValue('品牌助手')
    await wrapper.get('[data-test="skill-form-summary"]').setValue('帮助品牌生成内容')

    // 中文名超过 128 被拦（maxlength 只挡键盘输入，直接改 value 仍要挡住）
    const nameInput = wrapper.get('[data-test="skill-form-name"]')
    ;(nameInput.element as HTMLInputElement).value = '名'.repeat(129)
    await nameInput.trigger('input')
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()
    expect(error).toHaveBeenLastCalledWith(expect.objectContaining({ message: '技能名称最多 128 个字符。' }))

    // 英文名同样受 128 上限约束
    await wrapper.get('[data-test="skill-form-name"]').setValue('品牌助手')
    const enInput = wrapper.get('[data-test="skill-form-name-en"]')
    ;(enInput.element as HTMLInputElement).value = 'E'.repeat(129)
    await enInput.trigger('input')
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()
    expect(error).toHaveBeenLastCalledWith(expect.objectContaining({ message: '英文名称最多 128 个字符。' }))

    // 英文选填：清空英文后依然可以提交
    await wrapper.get('[data-test="skill-form-name-en"]').setValue('')
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()
    expect(adminApi.createAdminSkill).toHaveBeenCalledTimes(1)
    expect(adminApi.createAdminSkill).toHaveBeenCalledWith(expect.objectContaining({ nameEn: null }))
    wrapper.unmount()
  })

  it('accepts 1000 characters in both skill descriptions and rejects 1001', async () => {
    const error = vi.spyOn(ElMessage, 'error')
    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="skill-form-code"]').setValue('description-limit')
    await wrapper.get('[data-test="skill-form-name"]').setValue('说明长度')
    await wrapper.get('[data-test="skill-form-summary"]').setValue('测试说明长度')

    expect(wrapper.get('[data-test="skill-form-description"]').attributes('maxlength')).toBe('1000')
    expect(wrapper.get('[data-test="skill-form-description-en"]').attributes('maxlength')).toBe('1000')

    state(wrapper).form.description = '中'.repeat(1001)
    state(wrapper).form.descriptionEn = 'E'.repeat(1000)
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    expect(error).toHaveBeenLastCalledWith(expect.objectContaining({ message: '详细说明最多 1000 个字符。' }))

    state(wrapper).form.description = '中'.repeat(1000)
    state(wrapper).form.descriptionEn = 'E'.repeat(1001)
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    expect(error).toHaveBeenLastCalledWith(expect.objectContaining({ message: '英文详细说明最多 1000 个字符。' }))

    state(wrapper).form.descriptionEn = 'E'.repeat(1000)
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()
    expect(adminApi.createAdminSkill).toHaveBeenCalledWith(expect.objectContaining({
      description: '中'.repeat(1000), descriptionEn: 'E'.repeat(1000),
    }))
    wrapper.unmount()
  })

  it('creates the skill, stays in the dialog and switches to the 资源包 section', async () => {
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="skill-form-code"]').setValue('brand-new')
    await wrapper.get('[data-test="skill-form-name"]').setValue('品牌助手')
    await wrapper.get('[data-test="skill-form-summary"]').setValue('帮助品牌生成内容')
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.createAdminSkill).toHaveBeenCalledTimes(1)
    const dialog = wrapper.get('.el-dialog')
    expect(dialog.text()).toContain('资源包')
    expect(dialog.find('[data-test="skill-import-zip-button"]').exists()).toBe(true)
    expect(dialog.find('[data-test="skill-version-upload"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps the code read-only when editing and sends rowVersion for the optimistic lock', async () => {
    adminApi.updateAdminSkill.mockRejectedValueOnce(apiFailure('skill_concurrent_modification', 409))
    const error = vi.spyOn(ElMessage, 'error')
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-edit-data-analyst"]').trigger('click')
    await flushPromises()

    const codeInput = wrapper.get('[data-test="skill-form-code"]')
    expect(codeInput.attributes('readonly')).toBeDefined()
    expect((codeInput.element as HTMLInputElement).value).toBe('data-analyst')

    await wrapper.get('[data-test="skill-form-name"]').setValue('数据分析助手 2')
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.updateAdminSkill).toHaveBeenCalledWith('skill-1', expect.objectContaining({
      rowVersion: 3,
    }))
    const updatePayload = adminApi.updateAdminSkill.mock.calls.at(-1)?.[1] as Record<string, unknown>
    expect(updatePayload).not.toHaveProperty('code')
    expect(error).toHaveBeenCalledWith(expect.objectContaining({
      message: '内容已被他人修改，请关闭后重新打开。',
    }))
    wrapper.unmount()
  })

  it('keeps the kind read-only when editing and explains that the server rejects changes', async () => {
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-edit-data-analyst"]').trigger('click')
    await flushPromises()

    // 详情回读把 kind 覆盖为 TEAM（老数据）：只读展示，提交时原样回传
    expect(state(wrapper).form.kind).toBe('TEAM')
    // Element Plus 把 disabled 落在每个 radio-button 的 label/input 上，radio-group 根节点
    // 只有 `el-radio-group`，所以这里断言真正的禁用位置。Skill 页作用域是 SKILL，编辑一条
    // kind=TEAM 的老记录时会额外补出它的真实类型，两个选项都只读。
    const kindRadios = wrapper.get('[data-test="skill-form-kind"]')
    expect(kindRadios.findAll('label.el-radio-button.is-disabled')).toHaveLength(2)
    expect(kindRadios.text()).toContain('技能')
    expect(kindRadios.text()).toContain('专家团')
    expect(kindRadios.find('input:not([disabled])').exists()).toBe(false)
    expect(wrapper.get('[data-test="skill-form-kind-help"]').text()).toContain('类型创建后不可修改')

    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.updateAdminSkill).toHaveBeenCalledWith('skill-1', expect.objectContaining({ kind: 'TEAM' }))
    wrapper.unmount()
  })

  it('explains the 422 invalid_skill_kind when the server refuses a kind change', async () => {
    adminApi.updateAdminSkill.mockRejectedValueOnce(apiFailure('invalid_skill_kind', 422))
    const error = vi.spyOn(ElMessage, 'error')
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-edit-data-analyst"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(error).toHaveBeenCalledWith(expect.objectContaining({
      message: '技能类型不合法：只能在创建时选择技能、专家或专家团，创建后不可修改。',
    }))
    wrapper.unmount()
  })

  it('prefills the English fields from the detail payload when editing', async () => {
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-edit-data-analyst"]').trigger('click')
    await flushPromises()

    expect((wrapper.get('[data-test="skill-form-name-en"]').element as HTMLInputElement).value).toBe('Data Analyst')
    expect((wrapper.get('[data-test="skill-form-summary-en"]').element as HTMLInputElement).value)
      .toBe('Turn raw tables into conclusions')
    expect((wrapper.get('[data-test="skill-form-description-en"]').element as HTMLTextAreaElement).value)
      .toBe('Turns CSV reports into conclusions.')
    wrapper.unmount()
  })

  it('prefills the minimum client version from the detail when the list omits it', async () => {
    adminApi.listAdminSkills.mockResolvedValue({
      ...catalogPage,
      items: [row({ minClientVersion: '' })],
      total: 1,
    })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail,
      kind: 'SKILL',
      minClientVersion: '2026.8.0',
    })
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-edit-data-analyst"]').trigger('click')
    await flushPromises()

    expect((wrapper.get('[data-test="skill-form-min-client"]').element as HTMLInputElement).value)
      .toBe('2026.8.0')
    wrapper.unmount()
  })

  it('keeps the required default when list and detail minimum client versions are blank', async () => {
    adminApi.listAdminSkills.mockResolvedValue({
      ...catalogPage,
      items: [row({ minClientVersion: '  ' })],
      total: 1,
    })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail,
      kind: 'SKILL',
      minClientVersion: '  ',
    })
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-edit-data-analyst"]').trigger('click')
    await flushPromises()

    expect((wrapper.get('[data-test="skill-form-min-client"]').element as HTMLInputElement).value)
      .toBe('2026.6.0')
    wrapper.unmount()
  })

  it('preserves a minimum client version edited while detail is loading', async () => {
    let resolveDetail!: (value: typeof detail) => void
    adminApi.getAdminSkill.mockImplementationOnce(() => new Promise((resolve) => {
      resolveDetail = resolve
    }))
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-edit-data-analyst"]').trigger('click')
    await wrapper.get('[data-test="skill-form-min-client"]').setValue('2027.3.0')
    resolveDetail({ ...detail, kind: 'SKILL', minClientVersion: '2026.8.0' })
    await flushPromises()

    expect((wrapper.get('[data-test="skill-form-min-client"]').element as HTMLInputElement).value)
      .toBe('2027.3.0')
    wrapper.unmount()
  })

  it('loads saved ClawHub tags from detail and saves later edits', async () => {
    adminApi.listAdminSkills.mockResolvedValue({
      ...catalogPage,
      items: [row({ tags: ['旧列表标签'] })],
      total: 1,
    })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail,
      kind: 'SKILL',
      tags: ['ClawHub', 'why20261'],
    })
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-edit-data-analyst"]').trigger('click')
    await flushPromises()

    expect(state(wrapper).form.tags).toEqual(['ClawHub', 'why20261'])
    expect(wrapper.get('[data-test="skill-form-tags"]').text()).toContain('why20261')
    expect(wrapper.get('[data-test="skill-form-tags"]').find('input:not([disabled])').exists()).toBe(true)

    state(wrapper).form.tags = ['ClawHub', 'why20261', '整理工具']
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.updateAdminSkill).toHaveBeenCalledWith('skill-1', expect.objectContaining({
      tags: ['ClawHub', 'why20261', '整理工具'],
    }))
    wrapper.unmount()
  })

  it('preserves tags changed while the detail request is loading', async () => {
    let resolveDetail!: (value: typeof detail) => void
    adminApi.getAdminSkill.mockImplementationOnce(() => new Promise((resolve) => {
      resolveDetail = resolve
    }))
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-edit-data-analyst"]').trigger('click')
    state(wrapper).form.tags = ['我改过的标签']
    resolveDetail({ ...detail, kind: 'SKILL', tags: ['ClawHub', 'why20261'] })
    await flushPromises()

    expect(state(wrapper).form.tags).toEqual(['我改过的标签'])
    wrapper.unmount()
  })

  it('accepts a 64-character ClawHub owner tag and rejects a longer tag', async () => {
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail,
      kind: 'SKILL',
      tags: ['ClawHub', 'a'.repeat(64)],
    })
    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-edit-data-analyst"]').trigger('click')
    await flushPromises()

    expect(state(wrapper).form.tags).toEqual(['ClawHub', 'a'.repeat(64)])

    state(wrapper).form.tags = ['ClawHub', 'a'.repeat(65)]
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    expect(adminApi.updateAdminSkill).not.toHaveBeenCalled()
    expect(state(wrapper).errorMessage).toContain('单个标签最多 64 个字符')

    state(wrapper).form.tags = ['ClawHub', 'a'.repeat(64)]
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()
    expect(adminApi.updateAdminSkill).toHaveBeenCalledWith('skill-1', expect.objectContaining({
      tags: ['ClawHub', 'a'.repeat(64)],
    }))
    wrapper.unmount()
  })
})

describe('SkillCatalogView 详情与版本管理', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routerState.route.query = {}
    adminApi.listAdminSkills.mockResolvedValue(catalogPage)
    adminApi.getAdminSkill.mockResolvedValue(detail)
    adminApi.publishSkillVersion.mockResolvedValue(version({ id: 'version-2', online: true }))
    adminApi.deleteSkillVersion.mockResolvedValue(undefined)
    skillsApi.listSkills.mockResolvedValue(previewCatalog)
    skillsApi.getSkill.mockResolvedValue(previewDetail)
    mockMemberApi()
  })

  afterEach(() => {
    ElMessage.closeAll()
    document.querySelectorAll('.el-message').forEach((message) => message.remove())
  })

  it('opens the drawer with the version table and the client download endpoints', async () => {
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    expect(adminApi.getAdminSkill).toHaveBeenCalledWith('skill-1')
    const drawer = wrapper.get('[data-test="skill-drawer"]')
    expect(drawer.text()).toContain('数据分析助手')
    expect(drawer.text()).toContain('1.2.0')
    expect(drawer.text()).toContain('YCPS')

    const table = wrapper.get('[data-test="skill-version-table"]')
    expect(table.text()).toContain('线上')
    expect(table.text()).toContain('已通过')
    expect(wrapper.get('[data-test="skill-version-format-1.2.0"]').text()).toContain('YCPS 1.0.0')
    expect(wrapper.get('[data-test="skill-version-sealed-1.2.0"]').text()).toBe('已封存')
    expect(wrapper.get('[data-test="skill-version-manifest-1.2.0"]').text()).toContain('…')
    expect(wrapper.findAll('[data-test="skill-version-table"] tbody tr')).toHaveLength(2)

    const endpoints = wrapper.get('[data-test="skill-client-endpoints"]')
    expect(endpoints.text()).toContain('/v1/skills')
    expect(endpoints.text()).toContain('/v1/skills/{code}/downloads')
    expect(endpoints.text()).toContain('/v1/skills/downloads/{id}')
    // 管理端浏览器下载是既有能力（A25 整包 + 本轮图片下载），文案不能再声称「不提供」
    expect(endpoints.text()).toContain('管理端也可在浏览器直接下载')
    expect(endpoints.text()).not.toContain('不提供浏览器下载')
    wrapper.unmount()
  })

  it('shows the kind tag and the bilingual copy in the drawer, Chinese first', async () => {
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    expect(wrapper.get('[data-test="skill-drawer-kind-TEAM"]').text()).toBe('专家团')
    expect(wrapper.get('[data-test="skill-drawer-name-en"]').text()).toBe('Data Analyst')
    expect(wrapper.get('[data-test="skill-drawer-summary-en"]').text()).toContain('Turn raw tables')
    expect(wrapper.get('[data-test="skill-drawer-description-en"]').text()).toContain('Turns CSV reports')

    const text = wrapper.get('[data-test="skill-drawer"]').text()
    expect(text.indexOf('数据分析助手')).toBeLessThan(text.indexOf('Data Analyst'))
    wrapper.unmount()
  })

  it('renders the plugin.json manifest as a read-only collapsed block', async () => {
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    const manifest = wrapper.get('[data-test="skill-manifest"]')
    expect(manifest.text()).toContain('包内清单')
    expect(wrapper.get('[data-test="skill-manifest-toggle"]').text()).toContain('plugin.json')

    // 默认折叠：Element Plus 用 v-show + aria-hidden 收起内容（节点仍在 DOM 里但不可见），
    // 因此这里断言折叠面板的 aria-hidden，而不是断言 <pre> 不存在。
    const collapseBody = wrapper.get('.el-collapse-item__wrap')
    expect(collapseBody.attributes('aria-hidden')).toBe('true')
    await wrapper.get('[data-test="skill-manifest-toggle"]').trigger('click')
    await flushPromises()

    expect(collapseBody.attributes('aria-hidden')).toBe('false')
    const body = wrapper.get('[data-test="skill-manifest-body"]')
    expect(body.element.tagName).toBe('PRE')
    expect(body.text()).toContain('"expertType": "team"')
    expect(body.text()).toContain('"name": "安拓"')
    expect(manifest.find('input, textarea').exists()).toBe(false)
    expect(manifest.text()).toContain('管理端不提供编辑入口')
    wrapper.unmount()
  })

  it('tells the administrator when the package carried no plugin.json', async () => {
    adminApi.getAdminSkill.mockResolvedValue({ ...detail, manifest: null })
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    await wrapper.get('[data-test="skill-manifest-toggle"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="skill-manifest-empty"]').text()).toContain('还没有包内清单')
    wrapper.unmount()
  })

  it('lists the roster from the detail payload with the LEAD badge and sort order', async () => {
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    expect(wrapper.get('[data-test="skill-drawer-member-count"]').text()).toBe('2')
    const rows = wrapper.findAll('[data-test="skill-member-table"] tbody tr')
    expect(rows).toHaveLength(2)
    // 成员标识是可搜索下拉（选项为已上架专家），选中值显示在 .el-select 的文本里；
    // 其余字段仍是普通输入框，值落在 input.value 上。
    const values = (selector: string) => (wrapper.get(selector).element as HTMLInputElement).value
    expect(wrapper.get('[data-test="skill-member-key-0"]').text()).toContain('atlas')
    expect(values('[data-test="skill-member-name-0"]')).toBe('安拓')
    expect(values('[data-test="skill-member-name-en-0"]')).toBe('Atlas')
    expect(values('[data-test="skill-member-profession-0"]')).toBe('分发策略师')
    expect(values('[data-test="skill-member-profession-en-0"]')).toBe('Distribution Strategist')
    expect(wrapper.get('[data-test="skill-member-key-1"]').text()).toContain('nova')
    expect(values('[data-test="skill-member-name-1"]')).toBe('诺娃')
    expect(wrapper.get('[data-test="skill-member-lead-0"]').text()).toBe('LEAD')
    expect(wrapper.find('[data-test="skill-member-lead-1"]').exists()).toBe(false)

    // 未改动时「保存花名册」不可点
    expect(wrapper.get('[data-test="skill-members-save"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('hides legacy member rows in a single Skill detail', async () => {
    adminApi.getAdminSkill.mockResolvedValue({ ...detail, kind: 'SKILL', memberCount: 2 })
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    expect(wrapper.find('[data-test="skill-roster"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-drawer-member-count"]').exists()).toBe(false)
    expect(skillsApi.fetchSkillMemberAvatar).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('guides the administrator when a team has no member yet', async () => {
    adminApi.getAdminSkill.mockResolvedValue({ ...detail, members: [], memberCount: 0 })
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    expect(wrapper.get('[data-test="skill-members-empty"]').text())
      .toContain('可从包内配置生成初始成员')
    expect(wrapper.get('[data-test="skill-members-empty"]').text())
      .toContain('或在此手动添加')
    wrapper.unmount()
  })

  it('adds, edits, reorders and deletes members locally and saves the whole roster once', async () => {
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    await wrapper.get('[data-test="skill-member-add"]').trigger('click')
    await flushPromises()
    expect(state(wrapper).roster).toHaveLength(3)
    expect(state(wrapper).rosterDirty).toBe(true)

    // 成员标识是下拉（选项为已上架专家），直接改草稿等价于选中 vega
    state(wrapper).roster[2].memberKey = 'vega'
    await wrapper.get('[data-test="skill-member-name-2"]').setValue('织女')
    await wrapper.get('[data-test="skill-member-name-en-2"]').setValue('Vega')
    await wrapper.get('[data-test="skill-member-profession-2"]').setValue('视觉设计')
    await flushPromises()

    // 删除 atlas（原主理人）后剩余两行按数组序重排
    await wrapper.get('[data-test="skill-member-remove-0"]').trigger('click')
    await flushPromises()
    expect(state(wrapper).roster.map((entry) => entry.memberKey)).toEqual(['nova', 'vega'])
    expect(state(wrapper).roster.map((entry) => entry.sortOrder)).toEqual([0, 1])

    // 下移 nova：vega 换到第 1 行，sortOrder 再次按数组序重排
    await wrapper.get('[data-test="skill-member-down-0"]').trigger('click')
    await flushPromises()
    expect(state(wrapper).roster.map((entry) => entry.memberKey)).toEqual(['vega', 'nova'])
    expect(state(wrapper).roster.map((entry) => entry.sortOrder)).toEqual([0, 1])

    await wrapper.get('[data-test="skill-members-save"]').trigger('click')
    await flushPromises()

    expect(adminApi.replaceAdminSkillMembers).toHaveBeenCalledTimes(1)
    expect(adminApi.replaceAdminSkillMembers).toHaveBeenCalledWith('skill-1', [
      {
        memberKey: 'vega',
        name: '织女',
        nameEn: 'Vega',
        profession: '视觉设计',
        professionEn: null,
        role: 'MEMBER',
        sortOrder: 0,
      },
      {
        memberKey: 'nova',
        name: '诺娃',
        nameEn: 'Nova',
        profession: '内容策划',
        professionEn: 'Content Planner',
        role: 'MEMBER',
        sortOrder: 1,
      },
    ])
    // 保存成功后回读详情，草稿回到干净状态
    expect(adminApi.getAdminSkill).toHaveBeenCalledTimes(2)
    expect(state(wrapper).rosterDirty).toBe(false)
    wrapper.unmount()
  })

  it('rejects a duplicate memberKey locally without calling the API', async () => {
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    // 成员标识现在是下拉，直接改草稿模拟「两个成员选了同一个专家」
    state(wrapper).roster[1].memberKey = 'atlas'
    await state(wrapper).saveRoster()
    await flushPromises()

    expect(adminApi.replaceAdminSkillMembers).not.toHaveBeenCalled()
    // 校验失败写进抽屉内的错误条（组件不用 ElMessage.error）
    expect(state(wrapper).errorMessage).toBe('成员标识「atlas」重复了，花名册里的标识必须唯一。')
    wrapper.unmount()
  })

  it('rejects a malformed memberKey locally with the documented pattern', async () => {
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    state(wrapper).roster[1].memberKey = '-nova'
    await state(wrapper).saveRoster()
    await flushPromises()

    expect(adminApi.replaceAdminSkillMembers).not.toHaveBeenCalled()
    expect(state(wrapper).errorMessage).toContain('成员标识不符合规则')
    wrapper.unmount()
  })

  it('keeps a single LEAD in the roster and demotes the previous one on promotion', async () => {
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    const roster = state(wrapper).roster
    expect(roster.filter((entry) => entry.role === 'LEAD')).toHaveLength(1)
    expect(wrapper.get('[data-test="skill-member-lead-0"]').text()).toBe('LEAD')
    expect(wrapper.find('[data-test="skill-member-lead-1"]').exists()).toBe(false)

    // 把 nova 提升为主理人：atlas 自动降级，全局仍然只有一个 LEAD
    roster[1].role = 'LEAD'
    state(wrapper).onRoleChange(1)
    expect(roster.filter((entry) => entry.role === 'LEAD').map((entry) => entry.memberKey)).toEqual(['nova'])
    wrapper.unmount()
  })

  it('asks before discarding an unsaved roster when the drawer is closed', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm')
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    await wrapper.get('[data-test="skill-member-add"]').trigger('click')
    await flushPromises()
    expect(state(wrapper).rosterDirty).toBe(true)

    const drawer = wrapper.findComponent({ name: 'ElDrawer' })
    const beforeClose = drawer.props('beforeClose') as ((done: () => void) => void) | undefined
    expect(typeof beforeClose).toBe('function')

    let closed = false
    confirm.mockRejectedValueOnce('cancel' as never)
    beforeClose?.(() => { closed = true })
    await flushPromises()
    expect(closed).toBe(false)
    expect(confirm).toHaveBeenCalledWith(
      '花名册还有未保存的改动，关闭后这些改动会丢失。',
      '放弃未保存的花名册改动？',
      expect.objectContaining({ type: 'warning' }),
    )

    confirm.mockResolvedValueOnce('confirm' as never)
    beforeClose?.(() => { closed = true })
    await flushPromises()
    expect(closed).toBe(true)
    wrapper.unmount()
  })

  it('renders the member portrait read-only, sourced from that expert package icon', async () => {
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    // 花名册不再提供头像上传：这一格是只读的，头像走「该专家包的图标」这条链路
    expect(wrapper.find('[data-test="skill-member-avatar-atlas"] input[type="file"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-member-avatar-remove-atlas"]').exists()).toBe(false)
    expect(skillsApi.fetchSkillMemberAvatar).toHaveBeenCalledWith('atlas')
    wrapper.unmount()
  })

  it('offers only published experts in the roster member key picker', async () => {
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    // 成员标识改为下拉：成员必须是已上架专家，选项即专家 code
    const picker = wrapper.find('[data-test="skill-member-key-0"]')
    expect(picker.exists()).toBe(true)
    wrapper.unmount()
  })

  // 需求：技能管理页不提供「介绍插画」栏 —— 插画是专家详情页的展示素材，
  // 技能没有消费方，上传了也没有地方显示。专家页仍保留（见 ExpertCatalogView.spec.ts）。
  it('does not offer the introduction illustration field on the skill page', async () => {
    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="skill-form-illustration"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-illustration-file"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-form-example-image"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-example-image-file"]').exists()).toBe(false)
    // 对照臂：同一屏里的「图片图标」必须还在，否则「都不显示」也能让上面的断言通过
    expect(wrapper.find('[data-test="skill-form-icon"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skill-icon-file"]').exists()).toBe(true)
    wrapper.unmount()
  })

  // 插画入口从技能页撤掉后，后端错误码映射仍是共享的（专家页会用到），保留这条纯函数断言
  it('maps skill_illustration_too_large from the server to Chinese copy', () => {
    expect(SKILL_ADMIN_ERROR_MESSAGES.skill_illustration_too_large).toBe(
      '介绍插画不能超过 4 MB，请压缩后重试。',
    )
  })

  it('confirms the impact before promoting a version to production', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    await wrapper.get('[data-test="skill-version-publish-1.3.0"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining('客户端将在下次检查更新时下载 1.3.0'),
      '确认设为线上版本',
      expect.objectContaining({ type: 'warning' }),
    )
    expect(adminApi.publishSkillVersion).toHaveBeenCalledWith('skill-1', 'version-2')
    wrapper.unmount()
  })

  it('downloads the online package from the drawer footer by default', async () => {
    const createObjectURL = vi.fn(() => 'blob:package')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })
    adminApi.downloadAdminSkillPackage.mockResolvedValue({
      blob: new Blob(['zip-bytes']),
      fileName: 'demo-skill-1.2.0.ycps.zip',
      md5: 'md5-value',
      sha256: 'sha256-value',
    })

    const wrapper = await mountReady()
    await openDrawer(wrapper)

    await wrapper.get('[data-test="skill-drawer-download"]').trigger('click')
    await flushPromises()

    // 默认按钮不带 versionId：后端据此回落到线上版本
    expect(adminApi.downloadAdminSkillPackage).toHaveBeenCalledWith('skill-1', 'version-1')
    expect(createObjectURL).toHaveBeenCalled()
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('collapses both download modes into one expandable button', async () => {
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:package'),
      revokeObjectURL: vi.fn(),
    })
    adminApi.downloadAdminSkillPackageBundle.mockResolvedValue({
      blob: new Blob(['bundle-bytes']),
      fileName: '',
      assetCount: 2,
    })

    const wrapper = await mountReady()
    await openDrawer(wrapper)

    // 未展开时菜单不存在：默认动作仍是纯包，不逼管理员多点一次
    expect(wrapper.find('[data-test="skill-drawer-download-menu"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="skill-drawer-download"]').text()).toContain('下载YCPS包')
    expect(wrapper.get('[data-test="skill-drawer-download-toggle"]').attributes('title'))
      .toBe('含图完整可导入包（含图标）')

    await wrapper.get('[data-test="skill-drawer-download-toggle"]').trigger('click')
    await flushPromises()

    const menu = wrapper.get('[data-test="skill-drawer-download-menu"]')
    expect(menu.find('[data-test="skill-drawer-download-menu-package"]').exists()).toBe(true)
    expect(menu.find('[data-test="skill-drawer-download-menu-bundle"]').exists()).toBe(true)

    // 选「含图完整包」走的是 A26 端点，且菜单收起
    await menu.get('[data-test="skill-drawer-download-menu-bundle"]').trigger('click')
    await flushPromises()

    expect(adminApi.downloadAdminSkillPackageBundle).toHaveBeenCalledWith('skill-1', 'version-1')
    expect(adminApi.downloadAdminSkillPackage).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="skill-drawer-download-menu"]').exists()).toBe(false)
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('downloads one exact historical version from its row', async () => {
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:package'),
      revokeObjectURL: vi.fn(),
    })
    adminApi.downloadAdminSkillPackage.mockResolvedValue({
      blob: new Blob(['zip-bytes']),
      fileName: '',
      md5: null,
      sha256: null,
    })

    const wrapper = await mountReady()
    await openDrawer(wrapper)

    await wrapper.get('[data-test="skill-version-download-1.3.0"]').trigger('click')
    await flushPromises()

    expect(adminApi.downloadAdminSkillPackage).toHaveBeenCalledWith('skill-1', 'version-2')
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('surfaces a download failure without opening a dialog', async () => {
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:package'),
      revokeObjectURL: vi.fn(),
    })
    const error = vi.spyOn(ElMessage, 'error')
    adminApi.downloadAdminSkillPackage.mockRejectedValue(new Error('boom'))

    const wrapper = await mountReady()
    await openDrawer(wrapper)

    await wrapper.get('[data-test="skill-version-download-1.3.0"]').trigger('click')
    await flushPromises()

    expect(error).toHaveBeenCalled()
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('never offers delete on the published version row', async () => {    const wrapper = await mountReady()
    await openDrawer(wrapper)

    const publishedDelete = wrapper.get('[data-test="skill-version-delete-1.2.0"]')
    expect(publishedDelete.attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="skill-version-delete-1.3.0"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-test="skill-version-publish-1.2.0"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('deletes a non-published version after confirmation', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    await wrapper.get('[data-test="skill-version-delete-1.3.0"]').trigger('click')
    await flushPromises()

    expect(adminApi.deleteSkillVersion).toHaveBeenCalledWith('skill-1', 'version-2')
    wrapper.unmount()
  })

  it('shows the pending-release hint inside the drawer', async () => {
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail,
      publishedVersion: '1.2.0',
      latestVersion: '1.3.0',
    })
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    expect(wrapper.get('[data-test="skill-drawer-pending"]').text()).toContain('有新版本待发布')
    wrapper.unmount()
  })

  it('disables replace for a sealed version and explains the SemVer path', async () => {
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    const sealed = wrapper.get('[data-test="skill-version-replace-1.2.0"]')
    expect(sealed.attributes('disabled')).toBeDefined()
    expect(sealed.attributes('title')).toContain('已封存版本不能原地替换')

    const ready = wrapper.get('[data-test="skill-version-replace-1.3.0"]')
    expect(ready.attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('opens the review dialog with the YCPS trust, permission and host summaries', async () => {
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    await wrapper.get('[data-test="skill-version-review-1.2.0"]').trigger('click')
    await flushPromises()

    expect(adminApi.fetchSkillVersionDependencies).toHaveBeenCalledWith('skill-1', 'version-1')
    expect(adminApi.fetchSkillDependents).toHaveBeenCalledWith('skill-1')

    const body = wrapper.get('[data-test="skill-review-body"]')
    expect(body.text()).toContain('YCPS')
    expect(body.text()).toContain('已封存')
    expect(wrapper.get('[data-test="skill-review-manifest-sha"]').text())
      .toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
    expect(wrapper.get('[data-test="skill-review-dependency-lock"]').text()).toBe('d'.repeat(64))

    const risky = wrapper.get('[data-test="skill-review-tool-process.execute"]')
    expect(risky.text()).toBe('process.execute')
    expect(risky.find('.el-tag').classes()).toContain('el-tag--danger')
    expect(wrapper.get('[data-test="skill-review-tool-document.read"]').find('.el-tag').classes())
      .toContain('el-tag--info')
    wrapper.unmount()
  })

  it('renders forward and reverse dependencies inside the review dialog', async () => {
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    await wrapper.get('[data-test="skill-version-review-1.2.0"]').trigger('click')
    await flushPromises()

    const dependencies = wrapper.get('[data-test="skill-review-dependencies"]')
    expect(dependencies.text()).toContain('budget-analysis')
    expect(dependencies.text()).toContain('技能绑定')
    expect(dependencies.text()).toContain('必需')
    expect(dependencies.text()).toContain('2.0.1')
    expect(dependencies.text()).toContain('线上版本')

    const dependents = wrapper.get('[data-test="skill-review-dependents"]')
    expect(dependents.text()).toContain('finance-analyst')
    expect(dependents.text()).toContain('专家')
    expect(dependents.text()).toContain('3.1.0')
    wrapper.unmount()
  })

  it('keeps the trust fields visible when the dependency lookup fails', async () => {
    adminApi.fetchSkillVersionDependencies.mockRejectedValue(new Error('boom'))
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    await wrapper.get('[data-test="skill-version-review-1.2.0"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="skill-review-error"]').text())
      .toContain('无法连接到 Skill 管理服务')
    expect(wrapper.get('[data-test="skill-review-manifest-sha"]').text())
      .toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
    wrapper.unmount()
  })
})

describe('SkillCatalogView 技能包上传', () => {
  function inspectedPackage(overrides: Record<string, unknown> = {}) {
    return {
      code: 'data-analyst', version: '1.4.0', kind: 'TEAM',
      minClientVersion: '2026.7.0', title: '包内数据分析', titleEn: 'Package Analyst',
      summary: '包内简介', summaryEn: 'Package summary',
      description: '包内详细说明', descriptionEn: 'Package description',
      keywords: ['包内标签'], fileCount: 2, extractedBytes: 200,
      sizeBytes: 100, sha256: 'a'.repeat(64), md5: 'b'.repeat(32),
      codeAvailable: false, occupiedBy: { kind: 'TEAM', status: 'PUBLISHED' },
      ...overrides,
    }
  }

  beforeEach(() => {
    vi.clearAllMocks()
    routerState.route.query = {}
    adminApi.listAdminSkills.mockResolvedValue(catalogPage)
    adminApi.getAdminSkill.mockResolvedValue(detail)
    adminApi.updateAdminSkill.mockResolvedValue(detail)
    adminApi.uploadAdminSkillIcon.mockResolvedValue(published)
    adminApi.publishSkillVersion.mockResolvedValue(version({ id: 'version-2', online: true }))
    adminApi.deleteSkillVersion.mockResolvedValue(undefined)
    adminApi.inspectAdminSkillPackage.mockResolvedValue(inspectedPackage())
    skillsApi.listSkills.mockResolvedValue(previewCatalog)
    skillsApi.getSkill.mockResolvedValue(previewDetail)
    mockMemberApi()
  })

  afterEach(() => {
    vi.useRealTimers()
    ElMessage.closeAll()
    document.querySelectorAll('.el-message').forEach((message) => message.remove())
  })

  async function openPackageUpload(wrapper: ReturnType<typeof mount>) {
    await wrapper.get('[data-test="skill-edit-data-analyst"]').trigger('click')
    await flushPromises()
  }

  function zipFile(bytes = 'zip-bytes') {
    return new File([bytes], 'data-analyst-1.4.0.ycps.zip', { type: 'application/zip' })
  }

  function pickPackage(wrapper: ReturnType<typeof mount>, file: File) {
    return pickFile(wrapper, '[data-test="skill-import-zip"]', file)
  }

  it('renders the current published version card with size, md5 and scan status', async () => {
    const wrapper = await mountReady()
    await openPackageUpload(wrapper)

    const card = wrapper.get('[data-test="skill-current-version"]')
    expect(card.text()).toContain('v1.2.0')
    expect(card.text()).toContain('revision 2')
    expect(card.text()).toContain('10.0 MB')
    expect(card.text()).toContain('a'.repeat(32))
    expect(card.text()).toContain('已通过')
    expect(wrapper.find('[data-test="skill-version-upload"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-version-file"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-import-zip-button"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('clarifies that the displayed package cap belongs to the current expert type', async () => {
    adminApi.getAdminSkill.mockResolvedValue({ ...detail, kind: 'EXPERT' })
    const wrapper = await mountReady()
    await openPackageUpload(wrapper)

    expect(wrapper.text()).toContain(
      '当前类型（专家）的单个 YCPS 包上限为 2.0 MB。各类型上限：技能 10 MB、专家 2 MB、专家团 5 MB。',
    )
    expect(wrapper.text()).not.toContain('单个 YCPS 包不超过 2.0 MB（技能 10 MB')
    wrapper.unmount()
  })

  it('uploads a new version with phases, then polls until READY', async () => {
    adminApi.getAdminSkill.mockResolvedValueOnce(detail).mockResolvedValue({
      ...detail,
      versions: [...detail.versions, version({ id: 'version-3', version: '1.4.0', status: 'READY', online: false })],
    })
    adminApi.uploadSkillVersionWithRetry.mockImplementation(async (
      _id: string,
      _file: File,
      _fields: unknown,
      options: { onProgress?: (progress: { phase: string; percent: number }) => void },
    ) => {
      options.onProgress?.({ phase: 'uploading', percent: 42 })
      return version({ id: 'version-3', version: '1.4.0', status: 'DRAFT', online: false })
    })
    const wrapper = await mountReady()
    await openPackageUpload(wrapper)

    await pickPackage(wrapper, zipFile())
    await flushPromises()

    expect(adminApi.inspectAdminSkillPackage).toHaveBeenCalledTimes(1)
    expect(adminApi.uploadSkillVersionWithRetry).not.toHaveBeenCalled()
    expect((wrapper.get('[data-test="skill-form-name"]').element as HTMLInputElement).value).toBe('包内数据分析')
    expect((wrapper.get('[data-test="skill-form-summary"]').element as HTMLInputElement).value).toBe('包内简介')
    expect((wrapper.get('[data-test="skill-version-number"]').element as HTMLInputElement).value).toBe('1.4.0')
    expect(wrapper.get('[data-test="skill-version-number"]').attributes('readonly')).toBeDefined()
    expect(wrapper.find('[data-test="skill-import-toggle"]').exists()).toBe(true)

    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.uploadSkillVersionWithRetry).toHaveBeenCalledWith(
      'skill-1',
      expect.any(File),
      expect.objectContaining({ version: '1.4.0', minClientVersion: '2026.7.0' }),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
    expect(wrapper.get('[data-test="skill-upload-phase"]').text()).toBe('安全扫描中')

    await new Promise((resolve) => setTimeout(resolve, 1_100))
    await flushPromises()

    expect(adminApi.getAdminSkill).toHaveBeenCalledWith('skill-1')
    wrapper.unmount()
  }, 15_000)

  it('maps a REJECTED scan to stable Chinese copy and never leaks the server message', async () => {
    adminApi.uploadSkillVersionWithRetry.mockResolvedValue(
      version({ id: 'version-3', version: '1.4.0', status: 'REJECTED', scanStatus: 'REJECTED', online: false }),
    )
    adminApi.getAdminSkill.mockResolvedValueOnce(detail).mockResolvedValue({
      ...detail,
      versions: [...detail.versions, version({ id: 'version-3', version: '1.4.0', status: 'REJECTED', scanStatus: 'REJECTED', online: false })],
    })
    const error = vi.spyOn(ElMessage, 'error')
    const wrapper = await mountReady()
    await openPackageUpload(wrapper)

    await pickPackage(wrapper, zipFile())
    await flushPromises()
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    await new Promise((resolve) => setTimeout(resolve, 1_100))
    await flushPromises()

    expect(error).toHaveBeenCalledWith(expect.objectContaining({ message: '技能包未通过安全扫描，已拒绝。' }))
    wrapper.unmount()
  }, 15_000)

  it('maps an upload failure to the documented code copy', async () => {
    adminApi.uploadSkillVersionWithRetry.mockRejectedValue(
      apiFailure('skill_zip_invalid', 422, 'missing root skill.json'),
    )
    const error = vi.spyOn(ElMessage, 'error')
    const wrapper = await mountReady()
    await openPackageUpload(wrapper)

    await pickPackage(wrapper, zipFile())
    await flushPromises()
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(error).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining('技能包结构校验未通过：请确认上传的是 YCPS 包'),
    }))
    expect(wrapper.text()).not.toContain('missing root skill.json')
    wrapper.unmount()
  })

  it('maps expert_kind_mismatch and invalid_expert_manifest to Chinese copy', async () => {
    const error = vi.spyOn(ElMessage, 'error')
    const wrapper = await mountReady()
    await openPackageUpload(wrapper)

    adminApi.uploadSkillVersionWithRetry.mockRejectedValueOnce(apiFailure('expert_kind_mismatch', 422))
    await pickPackage(wrapper, zipFile())
    await flushPromises()
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()
    expect(error).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining('技能包内 plugin.json 的 expertType 与技能类型不一致'),
    }))

    adminApi.uploadSkillVersionWithRetry.mockRejectedValueOnce(apiFailure('invalid_expert_manifest', 422))
    await pickPackage(wrapper, zipFile())
    await flushPromises()
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()
    expect(error).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining('技能包内的 plugin.json 无法解析或结构不合法'),
    }))
    wrapper.unmount()
  })

  it('refuses an oversized package before calling the API', async () => {
    const error = vi.spyOn(ElMessage, 'error')
    const wrapper = await mountReady()
    await openPackageUpload(wrapper)

    // 用 size 冒充超限包（jsdom 不允许真的分配 5 MB）；当前编辑对象是专家团，上限 5 MB
    const bigFile = zipFile('x')
    Object.defineProperty(bigFile, 'size', { value: 200 * 1024 * 1024 + 1 })
    await pickPackage(wrapper, bigFile)
    await flushPromises()

    expect(error).toHaveBeenCalledWith(expect.objectContaining({
      message: '技能包超出大小限制：当前类型上限 5.0 MB。',
    }))
    wrapper.unmount()
  })

  it('rejects a package for another skill without changing the edit form', async () => {
    adminApi.inspectAdminSkillPackage.mockResolvedValue(inspectedPackage({ code: 'another-skill' }))
    const wrapper = await mountReady()
    await openPackageUpload(wrapper)

    await pickPackage(wrapper, zipFile())
    await flushPromises()

    expect((wrapper.get('[data-test="skill-form-code"]').element as HTMLInputElement).value).toBe('data-analyst')
    expect((wrapper.get('[data-test="skill-form-name"]').element as HTMLInputElement).value).toBe('数据分析助手')
    expect(wrapper.find('[data-test="skill-version-number"]').exists()).toBe(false)
    expect(adminApi.uploadSkillVersionWithRetry).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('rejects a sealed package version before changing the edit form', async () => {
    adminApi.inspectAdminSkillPackage.mockResolvedValue(inspectedPackage({ version: '1.2.0' }))
    const wrapper = await mountReady()
    await openPackageUpload(wrapper)

    await pickPackage(wrapper, zipFile())
    await flushPromises()

    expect((wrapper.get('[data-test="skill-form-name"]').element as HTMLInputElement).value).toBe('数据分析助手')
    expect(wrapper.find('[data-test="skill-version-number"]').exists()).toBe(false)
    expect(adminApi.uploadSkillVersionWithRetry).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('recognizes a package dropped on the shared top import area', async () => {
    const wrapper = await mountReady()
    await openPackageUpload(wrapper)
    const zone = wrapper.get('[data-test="skill-import"]')
    const file = zipFile()
    const transfer = { items: [{ kind: 'file', getAsFile: () => file }], files: [file], dropEffect: '' }
    const drop = new Event('drop', { bubbles: true, cancelable: true })
    Object.defineProperty(drop, 'dataTransfer', { value: transfer })

    zone.element.dispatchEvent(drop)
    await flushPromises()

    expect(drop.defaultPrevented).toBe(true)
    expect((wrapper.get('[data-test="skill-form-name"]').element as HTMLInputElement).value).toBe('包内数据分析')
    expect(wrapper.get('[data-test="skill-import-staged"]').text()).toContain('v1.4.0')
    wrapper.unmount()
  })

  it('recognizes an unpacked YCPS folder selected from the edit import menu', async () => {
    const wrapper = await mountReady()
    await openPackageUpload(wrapper)
    const fileForPath = (path: string, content: string) => {
      const file = new File([content], path.split('/').at(-1) ?? 'file')
      Object.defineProperty(file, 'webkitRelativePath', { value: path })
      return file
    }
    const files = [
      fileForPath('data-analyst/plugin.json', '{}'),
      fileForPath('data-analyst/skills/data-analyst/SKILL.md', '# Data Analyst'),
    ]
    const input = wrapper.get('[data-test="skill-import-folder"]')
    Object.defineProperty(input.element, 'files', { value: files, configurable: true })

    await input.trigger('change')
    await vi.waitFor(() => expect(adminApi.inspectAdminSkillPackage).toHaveBeenCalledWith(expect.objectContaining({
      name: 'data-analyst.ycps.zip',
    })))
    await flushPromises()
    expect((wrapper.get('[data-test="skill-form-name"]').element as HTMLInputElement).value).toBe('包内数据分析')
    wrapper.unmount()
  })

  it('keeps existing edit fields when the package leaves them unspecified', async () => {
    adminApi.inspectAdminSkillPackage.mockResolvedValue(inspectedPackage({
      title: null, titleEn: null, summary: null, summaryEn: null,
      description: null, descriptionEn: null, keywords: [],
    }))
    const wrapper = await mountReady()
    await openPackageUpload(wrapper)

    await pickPackage(wrapper, zipFile())
    await flushPromises()

    expect((wrapper.get('[data-test="skill-form-name"]').element as HTMLInputElement).value).toBe('数据分析助手')
    expect((wrapper.get('[data-test="skill-form-summary"]').element as HTMLInputElement).value).toBe('把原始数据整理成结论与图表')
    expect((wrapper.get('[data-test="skill-version-number"]').element as HTMLInputElement).value).toBe('1.4.0')
    wrapper.unmount()
  })

  it('sends the replace flow through the version-scoped helper', async () => {
    // 未封存的线上版本才能原地替换（封存版本由专门的用例覆盖）
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail,
      versions: detail.versions.map((entry) => (
        entry.id === 'version-1' ? { ...entry, sealed: false, sealedAt: null } : entry
      )),
    })
    adminApi.inspectAdminSkillPackage.mockResolvedValue(inspectedPackage({ version: '1.2.0' }))
    adminApi.replaceSkillPackageWithRetry.mockResolvedValue(version({ id: 'version-1', revision: 3 }))
    const wrapper = await mountReady()
    await openPackageUpload(wrapper)

    await pickPackage(wrapper, zipFile())
    await flushPromises()
    expect(wrapper.find('[data-test="skill-version-number"]').exists()).toBe(true)
    expect((wrapper.get('[data-test="skill-version-number"]').element as HTMLInputElement).value).toBe('1.2.0')
    expect(wrapper.get('[data-test="skill-version-number"]').attributes('readonly')).toBeDefined()

    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.replaceSkillPackageWithRetry).toHaveBeenCalledWith(
      'skill-1',
      'version-1',
      expect.any(File),
      { changelog: '', minClientVersion: '2026.7.0' },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
    const replacementFields = adminApi.replaceSkillPackageWithRetry.mock.calls.at(-1)?.[3] as Record<string, unknown>
    expect(replacementFields).not.toHaveProperty('version')
    wrapper.unmount()
  }, 15_000)

  it('drops a YCPS ZIP into the Skill detail area and refreshes the scanned version', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const skillDetail = { ...detail, kind: 'SKILL' as const }
    const ready = version({ id: 'version-4', version: '1.4.0', status: 'READY', online: false })
    const refreshed = {
      ...skillDetail,
      latestVersion: '1.4.0',
      versions: [...skillDetail.versions, ready],
    }
    adminApi.getAdminSkill.mockResolvedValueOnce(skillDetail).mockResolvedValue(refreshed)
    adminApi.inspectAdminSkillPackage.mockResolvedValue(inspectedPackage({
      code: 'data-analyst', kind: 'SKILL', version: '1.4.0', minClientVersion: '2026.7.0',
    }))
    adminApi.uploadSkillVersionWithRetry.mockResolvedValue(
      version({ id: 'version-4', version: '1.4.0', status: 'DRAFT', online: false }),
    )
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    const zone = wrapper.get('[data-test="skill-detail-import"]')
    expect(zone.text()).toContain('拖拽导入技能包')
    const file = zipFile()
    const transfer = {
      items: [{ kind: 'file', webkitGetAsEntry: () => null, getAsFile: () => file }],
      files: [file],
      dropEffect: '',
    }
    const dragOver = new Event('dragover', { bubbles: true, cancelable: true })
    Object.defineProperty(dragOver, 'dataTransfer', { value: transfer })
    zone.element.dispatchEvent(dragOver)
    expect(dragOver.defaultPrevented).toBe(true)
    const drop = new Event('drop', { bubbles: true, cancelable: true })
    Object.defineProperty(drop, 'dataTransfer', { value: transfer })
    zone.element.dispatchEvent(drop)
    expect(drop.defaultPrevented).toBe(true)
    await flushPromises()

    await vi.waitFor(() => expect(adminApi.uploadSkillVersionWithRetry).toHaveBeenCalledTimes(1))
    expect(adminApi.inspectAdminSkillPackage).toHaveBeenCalledWith(expect.objectContaining({
      name: 'data-analyst-1.4.0.ycps.zip',
    }))
    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining('YCPS ZIP'),
      '确认导入 YCPS Skill',
      expect.objectContaining({ confirmButtonText: '上传并扫描' }),
    )
    expect(adminApi.uploadSkillVersionWithRetry).toHaveBeenCalledWith(
      'skill-1',
      expect.any(File),
      { version: '1.4.0', changelog: '', minClientVersion: '2026.7.0' },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )

    await new Promise(resolve => setTimeout(resolve, 1_100))
    await flushPromises()
    expect(wrapper.get('[data-test="skill-version-row-1.4.0"]').text()).toContain('v1.4.0')
    wrapper.unmount()
    confirm.mockRestore()
  }, 15_000)

  it('packages a selected YCPS folder in the Skill detail area and replaces an unsealed matching version', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const skillDetail = {
      ...detail,
      kind: 'SKILL' as const,
      versions: detail.versions.map(entry => (
        entry.version === '1.3.0' ? { ...entry, sealed: false, sealedAt: null } : entry
      )),
    }
    adminApi.getAdminSkill.mockResolvedValue(skillDetail)
    adminApi.inspectAdminSkillPackage.mockResolvedValue(inspectedPackage({
      code: 'data-analyst', kind: 'SKILL', version: '1.3.0', minClientVersion: '2026.7.0',
    }))
    adminApi.replaceSkillPackageWithRetry.mockResolvedValue(
      version({ id: 'version-2', version: '1.3.0', revision: 2, status: 'READY', online: false }),
    )
    const wrapper = await mountReady()
    await openDrawer(wrapper)
    const plugin = new File(['{}'], 'plugin.json')
    const skillDocument = new File(['# Data Analyst'], 'SKILL.md')
    Object.defineProperty(plugin, 'webkitRelativePath', {
      configurable: true, value: 'data-analyst/plugin.json',
    })
    Object.defineProperty(skillDocument, 'webkitRelativePath', {
      configurable: true, value: 'data-analyst/skills/data-analyst/SKILL.md',
    })
    const input = wrapper.get('[data-test="skill-detail-import-folder"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [plugin, skillDocument] })

    await input.trigger('change')
    await flushPromises()
    await vi.waitFor(() => expect(adminApi.replaceSkillPackageWithRetry).toHaveBeenCalledTimes(1))

    expect(adminApi.inspectAdminSkillPackage).toHaveBeenCalledWith(expect.objectContaining({
      name: 'data-analyst.ycps.zip',
    }))
    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining('YCPS 包目录'),
      '确认导入 YCPS Skill',
      expect.objectContaining({ confirmButtonText: '确认替换并扫描' }),
    )
    expect(adminApi.replaceSkillPackageWithRetry).toHaveBeenCalledWith(
      'skill-1',
      'version-2',
      expect.objectContaining({ name: 'data-analyst.ycps.zip' }),
      { changelog: '', minClientVersion: '2026.7.0' },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )

    await new Promise(resolve => setTimeout(resolve, 1_100))
    await flushPromises()
    wrapper.unmount()
    confirm.mockRestore()
  }, 15_000)

  it('rejects a same-version YCPS detail import when that version is sealed', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    adminApi.getAdminSkill.mockResolvedValue({ ...detail, kind: 'SKILL' as const })
    adminApi.inspectAdminSkillPackage.mockResolvedValue(inspectedPackage({
      code: 'data-analyst', kind: 'SKILL', version: '1.2.0',
    }))
    const wrapper = await mountReady()
    await openDrawer(wrapper)

    await pickFile(wrapper, '[data-test="skill-detail-import-zip"]', zipFile())
    await flushPromises()

    expect(state(wrapper).errorMessage).toContain('v1.2.0 已封存')
    expect(state(wrapper).errorMessage).toContain('提升 SemVer')
    expect(confirm).not.toHaveBeenCalled()
    expect(adminApi.replaceSkillPackageWithRetry).not.toHaveBeenCalled()
    expect(adminApi.uploadSkillVersionWithRetry).not.toHaveBeenCalled()
    wrapper.unmount()
    confirm.mockRestore()
  })
})

describe('SkillCatalogView 客户端预览标签页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routerState.route.query = {}
    adminApi.listAdminSkills.mockResolvedValue(catalogPage)
    adminApi.getAdminSkill.mockResolvedValue(detail)
    skillsApi.listSkills.mockResolvedValue(previewCatalog)
    skillsApi.listNativeClawhubSkills.mockResolvedValue({ object: 'list', items: [] })
    skillsApi.getSkill.mockResolvedValue(previewDetail)
    mockMemberApi()
  })

  afterEach(() => {
    ElMessage.closeAll()
    document.querySelectorAll('.el-message').forEach((message) => message.remove())
  })

  it('shows published native ClawHub cards but excludes unpublished sources, and opens native metadata without YCPS detail', async () => {
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:native-preview-icon'),
      revokeObjectURL: vi.fn(),
    })
    skillsApi.listNativeClawhubSkills.mockResolvedValue({
      object: 'list',
      items: [
        publishedNativeClawhubSkill,
        { ...publishedNativeClawhubSkill, code: 'native-offline', status: 'UNPUBLISHED', downloadUrl: null },
      ],
    })
    const wrapper = await mountReady()
    await openPreviewTab(wrapper)

    const card = wrapper.get('[data-test="skill-card-native-research"]')
    expect(wrapper.get('[data-test="skills-grid-SKILL"]').text()).toContain('原生研究助手')
    expect(card.text()).toContain('ClawHub')
    expect(card.text()).toContain('v2.3.4')
    expect(card.text()).toContain('@publisher/research')
    expect(card.get('.skill-card__meta').text()).toContain('4.0 KB')
    expect(card.get('.skill-card__meta').text()).toContain('2026-09-30')
    expect(wrapper.find('[data-test="skill-card-native-offline"]').exists()).toBe(false)
    expect(skillsApi.listNativeClawhubSkills).toHaveBeenCalledTimes(1)
    expect(skillsApi.fetchSkillIcon).toHaveBeenCalledWith('native-research')
    expect(skillsApi.fetchSkillIcon).not.toHaveBeenCalledWith('native-offline')
    expect(wrapper.get('[data-test="skill-card-avatar-native-research"] img').attributes('src'))
      .toBe('blob:native-preview-icon')

    await wrapper.get('[data-test="skill-card-detail-native-research"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="skill-detail-native-meta"]').text()).toContain('publisher')
    expect(wrapper.get('[data-test="skill-detail-native-meta"]').text()).toContain('4.0 KB')
    expect(wrapper.get('[data-test="skill-detail-native-meta"]').text()).toContain('2026-09-30 08:00:00')
    expect(wrapper.get('[data-test="skill-detail-description"]').text()).toContain('从 ClawHub 导入')
    expect(wrapper.get('[data-test="skill-detail-avatar"] img').attributes('src'))
      .toBe('blob:native-preview-icon')
    expect(skillsApi.getSkill).not.toHaveBeenCalled()
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('refreshes native cards when returning to preview after a new publish', async () => {
    skillsApi.listNativeClawhubSkills
      .mockResolvedValueOnce({ object: 'list', items: [] })
      .mockResolvedValue({ object: 'list', items: [publishedNativeClawhubSkill] })
    const wrapper = await mountReady()
    await openPreviewTab(wrapper)
    expect(wrapper.find('[data-test="skill-card-native-research"]').exists()).toBe(false)

    await wrapper.findAll('.el-tabs__item').at(0)?.trigger('click')
    await openPreviewTab(wrapper)
    expect(wrapper.find('[data-test="skill-card-native-research"]').exists()).toBe(true)
    expect(skillsApi.listNativeClawhubSkills).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('loads the read-only catalog lazily and offers no browser download', async () => {
    const wrapper = await mountReady()

    expect(skillsApi.listSkills).not.toHaveBeenCalled()
    await openPreviewTab(wrapper)

    expect(skillsApi.listSkills).toHaveBeenCalledTimes(1)
    const card = wrapper.get('[data-test="skill-card-data-analyst"]')
    expect(card.text()).toContain('数据分析助手')
    expect(card.text()).toContain('v1.2.0')
    expect(skillsApi.fetchSkillIcon).not.toHaveBeenCalled()
    expect(card.find('[data-test="skill-card-avatar-data-analyst"] img').exists()).toBe(false)
    expect(card.get('[data-test="skill-card-avatar-data-analyst"]').text()).toContain('📊')

    expect(wrapper.find('[data-test^="skill-card-download"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('下载能力包')
    expect(wrapper.find('[data-test="skill-card-detail-data-analyst"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('shows the kind tag and the English title on the preview card', async () => {
    const wrapper = await mountReady()
    await openPreviewTab(wrapper)

    const card = wrapper.get('[data-test="skill-card-data-analyst"]')
    expect(wrapper.get('[data-test="skill-card-kind-data-analyst"]').text()).toBe('专家团')
    expect(
      wrapper.get('[data-test="skill-card-kind-data-analyst"]').get('.el-tag').classes(),
    ).toContain('el-tag--danger')
    expect(wrapper.get('[data-test="skill-card-title-en-data-analyst"]').text()).toBe('Data Analyst')
    expect(wrapper.get('[data-test="skill-card-desc-en-data-analyst"]').text()).toContain('Turns CSV reports')
    expect(card.text().indexOf('数据分析助手')).toBeLessThan(card.text().indexOf('Data Analyst'))
    wrapper.unmount()
  })

  it('勾选多条后可统一上架和下架', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-select-content-writer"] input').setValue(true)
    await wrapper.get('[data-test="skill-select-ecommerce-ops"] input').setValue(true)
    expect(wrapper.get('[data-test="skills-selected-count"]').text()).toContain('已选 2 项')

    await wrapper.get('[data-test="skills-bulk-publish"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining('统一上架'),
      '确认批量上架技能',
      expect.objectContaining({ confirmButtonText: '批量上架' }),
    )
    expect(adminApi.publishAdminSkill).toHaveBeenCalledWith('skill-2')
    expect(adminApi.publishAdminSkill).toHaveBeenCalledWith('skill-3')

    await wrapper.get('[data-test="skill-select-data-analyst"] input').setValue(true)
    await wrapper.get('[data-test="skills-bulk-unpublish"]').trigger('click')
    await flushPromises()

    expect(adminApi.unpublishAdminSkill).toHaveBeenCalledWith('skill-1')
    wrapper.unmount()
  })

  it.each([false, true])('publishes native defaults in bulk and retains ambiguous selections (mixed=%s)', async (mixed) => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const natives = ['remembered', 'single', 'choose'].map((key) => row({
      id: `native-${key}`, code: `native-${key}`, kind: 'SKILL',
      status: 'UNPUBLISHED', sourceFormat: 'CLAWHUB_SKILL', sourceReviewRequired: true,
    }))
    const items = mixed ? [...natives, draft] : natives
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items, total: items.length })
    adminApi.publishAdminClawhubSkill.mockResolvedValue(detail)
    adminApi.listAdminClawhubVersions.mockImplementation(async (skillId: string) => {
      const old = nativeClawhubVersion({ id: `${skillId}-old`, skillId, version: '1.0.0' })
      const newer = nativeClawhubVersion({ id: `${skillId}-new`, skillId, version: '2.0.0' })
      return {
        items: skillId === 'native-single' ? [old] : [newer, old],
        currentPublishedVersionId: skillId === 'native-remembered' ? old.id : null,
      }
    })
    const wrapper = await mountReady()
    for (const item of items) {
      await wrapper.get(`[data-test="skill-select-${item.code}"] input`).setValue(true)
    }
    expect(wrapper.get('[data-test="skills-bulk-publish"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('[data-test="skills-bulk-publish"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(mixed ? 1 : 0)
    expect(adminApi.publishAdminClawhubSkill).toHaveBeenCalledTimes(2)
    expect(adminApi.publishAdminClawhubSkill).toHaveBeenCalledWith('native-remembered')
    expect(adminApi.publishAdminClawhubSkill).toHaveBeenCalledWith('native-single')
    expect(adminApi.publishAdminClawhubSkill).not.toHaveBeenCalledWith('native-choose')
    expect(adminApi.publishAdminClawhubVersion).not.toHaveBeenCalled()
    if (mixed) expect(adminApi.publishAdminSkill).toHaveBeenCalledWith(draft.id)
    else expect(adminApi.publishAdminSkill).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="skills-selected-count"]').text()).toContain('已选 1 项')
    expect((wrapper.get('[data-test="skill-select-native-choose"] input').element as HTMLInputElement).checked).toBe(true)
    expect((wrapper.get('[data-test="skill-select-native-remembered"] input').element as HTMLInputElement).checked).toBe(false)
    expect((wrapper.get('[data-test="skill-select-native-single"] input').element as HTMLInputElement).checked).toBe(false)
    expect(state(wrapper).errorMessage).toContain('请在详情中明确选择一个版本')
    expect(wrapper.get('[data-test="skills-bulk-publish"]').classes()).not.toContain('is-loading')
    wrapper.unmount()
  })

  it('将勾选项统一切换分类，并保留详情中的其他字段', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-select-ecommerce-ops"] input').setValue(true)
    state(wrapper).bulkCategory = '文档与数据'
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-test="skills-bulk-category-apply"]').trigger('click')
    await flushPromises()

    expect(adminApi.getAdminSkill).toHaveBeenCalledWith('skill-3')
    expect(adminApi.updateAdminSkill).toHaveBeenCalledWith('skill-3', expect.objectContaining({
      category: '文档与数据',
      name: detail.name,
      description: detail.description,
      rowVersion: detail.rowVersion,
      kind: detail.kind,
    }))
    wrapper.unmount()
  })

  it('批量删除仅允许已下架项，并在正文给出固定确认短语', async () => {
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '我确认批量删除' } as never)
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-select-content-writer"] input').setValue(true)
    expect(wrapper.get('[data-test="skills-bulk-delete"]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-test="skills-clear-selection"]').trigger('click')
    await wrapper.get('[data-test="skill-select-ecommerce-ops"] input').setValue(true)
    expect(wrapper.get('[data-test="skills-bulk-delete"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('[data-test="skills-bulk-delete"]').trigger('click')
    await flushPromises()

    const [message, title, options] = prompt.mock.calls[0] as unknown as [
      unknown, string, { inputPlaceholder?: string; inputValidator?: (value: string) => unknown },
    ]
    const content = expectStandalonePromptKeyword(message, '我确认批量删除')
    expect(content[2]?.children).toBe('要确认，请在下方输入上方确认短语。')
    expect(title).toBe('确认批量删除技能')
    expect(options.inputPlaceholder).toBe('我确认批量删除')
    expect(options.inputValidator?.('确认删除')).toBe('请输入“我确认批量删除”')
    expect(options.inputValidator?.('我确认批量删除')).toBe(true)
    expect(adminApi.deleteAdminSkill).toHaveBeenCalledWith('skill-3')
    wrapper.unmount()
  })

  it('renders uploaded avatars for skill, expert and team client previews', async () => {
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:client-preview-avatar'),
      revokeObjectURL: vi.fn(),
    })
    skillsApi.listSkills.mockResolvedValue({
      object: 'list' as const,
      data: [
        {
          ...previewCatalog.data[0],
          name: 'report-skill',
          title: '报表技能',
          kind: 'SKILL' as const,
          iconUrl: '/v1/skills/report-skill/icon',
        },
        {
          ...previewCatalog.data[0],
          name: 'report-expert',
          title: '报表专家',
          kind: 'EXPERT' as const,
          iconUrl: '/v1/skills/report-expert/icon',
        },
        {
          ...previewCatalog.data[0],
          name: 'report-team',
          title: '报表专家团',
          kind: 'TEAM' as const,
          iconUrl: '/v1/skills/report-team/icon',
        },
      ],
    })

    const wrapper = await mountReady()
    await openPreviewTab(wrapper)

    for (const code of ['report-skill', 'report-expert', 'report-team']) {
      expect(skillsApi.fetchSkillIcon).toHaveBeenCalledWith(code)
      expect(wrapper.get(`[data-test="skill-card-avatar-${code}"] img`).attributes('src'))
        .toBe('blob:client-preview-avatar')
    }

    await wrapper.get('[data-test="skill-card-detail-report-expert"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="skill-detail-avatar"] img').attributes('src'))
      .toBe('blob:client-preview-avatar')

    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('renders the member cards with the LEAD badge inside the preview detail', async () => {
    const wrapper = await mountReady()
    await openPreviewTab(wrapper)

    await wrapper.get('[data-test="skill-card-detail-data-analyst"]').trigger('click')
    await flushPromises()

    const members = wrapper.get('[data-test="skill-detail-members"]')
    expect(members.text()).toContain('专家团成员（2）')

    const lead = wrapper.get('[data-test="skill-member-card-atlas"]')
    expect(lead.text()).toContain('安拓')
    expect(lead.text()).toContain('Atlas')
    expect(lead.text()).toContain('分发策略师')
    expect(lead.text()).toContain('主理人')

    const plain = wrapper.get('[data-test="skill-member-card-nova"]')
    expect(plain.text()).toContain('诺娃')
    expect(plain.text()).not.toContain('主理人')

    // 头像需要鉴权：只对带 avatarUrl 的成员拉字节，且按成员标识（即专家 code）取图标
    expect(skillsApi.fetchSkillMemberAvatar).toHaveBeenCalledWith('atlas')
    expect(skillsApi.fetchSkillMemberAvatar).not.toHaveBeenCalledWith('nova')
    wrapper.unmount()
  })

  it.each(['SKILL', 'EXPERT'] as const)('does not show a %s runtime agent as a team roster in preview', async (kind) => {
    skillsApi.getSkill.mockResolvedValue({ ...previewDetail, kind })
    const wrapper = await mountReady()
    await openPreviewTab(wrapper)

    await wrapper.get('[data-test="skill-card-detail-data-analyst"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="skill-detail-members"]').exists()).toBe(false)
    expect(skillsApi.fetchSkillMemberAvatar).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('previews the skill body from the detail dialog but exposes no download buttons', async () => {
    const wrapper = await mountReady()
    await openPreviewTab(wrapper)

    await wrapper.get('[data-test="skill-card-detail-data-analyst"]').trigger('click')
    await flushPromises()

    expect(skillsApi.getSkill).toHaveBeenCalledWith('data-analyst')
    const dialog = wrapper.get('[data-test="skill-detail"]')
    expect(dialog.text()).toContain('SKILL.md')
    expect(dialog.text()).toContain('assets/logo.bin')
    expect(dialog.text()).toContain('二进制文件')
    expect(wrapper.get('[data-test="skill-detail-description-en"]').text()).toContain('Turns CSV reports')

    await wrapper.get('[data-test="skill-detail-preview-data-analyst-0"]').trigger('click')
    expect(wrapper.get('[data-test="skill-preview-body"]').element.textContent).toBe('# 内容\n')

    expect(wrapper.find('[data-test^="skill-detail-download"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('.skill.json')
    wrapper.unmount()
  })

  it('filters the preview grid and keeps the read-only error copy in the OpenAI envelope', async () => {
    skillsApi.listSkills.mockRejectedValue({
      response: {
        status: 404,
        headers: { 'x-request-id': 'req_console_404' },
        data: {
          error: { message: 'Skill not found', type: 'invalid_request_error', code: 'skill_not_found' },
          request_id: 'req_console_404',
        },
      },
    })
    const wrapper = await mountReady()
    await openPreviewTab(wrapper)

    const failure = wrapper.get('[data-test="skills-preview-error-message"]')
    expect(failure.text()).toBe('Skill 不存在或已下架。')
    expect(wrapper.text()).not.toContain('Skill not found')
    expect(wrapper.find('[data-test^="skills-grid"]').exists()).toBe(false)
    expect(wrapper.find('[data-test^="skills-preview-section"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('filters the preview cards by keyword', async () => {
    const wrapper = await mountReady()
    await openPreviewTab(wrapper)

    await wrapper.get('[data-test="skills-preview-query"]').setValue('不存在的技能')
    expect(wrapper.get('[data-test="skills-preview-no-match"]').text()).toContain('没有匹配的技能')

    await wrapper.get('[data-test="skills-preview-query"]').setValue('data-analyst')
    expect(wrapper.findAll('[data-test="skills-grid-TEAM"] article')).toHaveLength(1)
    wrapper.unmount()
  })

  // 需求3：Skills 不再与专家/专家团同屏，三类各自独立成块
  it('splits the client preview into skill, expert and team sections', async () => {
    skillsApi.listSkills.mockResolvedValue({
      object: 'list' as const,
      data: [
        { ...previewCatalog.data[0], name: 'data-analyst', title: '数据分析助手', kind: 'TEAM' as const },
        { ...previewCatalog.data[0], name: 'content-writer', title: '内容生成助手', kind: 'SKILL' as const },
        { ...previewCatalog.data[0], name: 'pet-care', title: '萌宠管家', kind: 'EXPERT' as const },
      ],
    })
    const wrapper = await mountReady()
    await openPreviewTab(wrapper)

    // 三个区块恒在，标题逐字对应三类，互不同屏混排
    expect(wrapper.get('[data-test="skills-preview-title-SKILL"]').text()).toContain('技能桌面端预览')
    expect(wrapper.get('[data-test="skills-preview-title-EXPERT"]').text()).toContain('专家桌面端预览')
    expect(wrapper.get('[data-test="skills-preview-title-TEAM"]').text()).toContain('专家团桌面端预览')

    // 每条卡片只落在自己的区块里
    expect(wrapper.findAll('[data-test="skills-grid-SKILL"] article')).toHaveLength(1)
    expect(wrapper.findAll('[data-test="skills-grid-EXPERT"] article')).toHaveLength(1)
    expect(wrapper.findAll('[data-test="skills-grid-TEAM"] article')).toHaveLength(1)

    expect(wrapper.get('[data-test="skills-grid-SKILL"]').text()).toContain('内容生成助手')
    expect(wrapper.get('[data-test="skills-grid-SKILL"]').text()).not.toContain('数据分析助手')
    expect(wrapper.get('[data-test="skills-grid-EXPERT"]').text()).toContain('萌宠管家')
    expect(wrapper.get('[data-test="skills-grid-TEAM"]').text()).toContain('数据分析助手')
    wrapper.unmount()
  })

  it('shows a per-kind empty hint instead of mixing the kinds', async () => {
    const wrapper = await mountReady()
    await openPreviewTab(wrapper)

    // 只有一条 TEAM，技能与专家区块各自给自己的空态
    expect(wrapper.get('[data-test="skills-preview-empty-SKILL"]').text()).toContain('暂无已上架技能')
    expect(wrapper.get('[data-test="skills-preview-empty-EXPERT"]').text()).toContain('暂无已上架专家')
    expect(wrapper.find('[data-test="skills-grid-SKILL"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skills-preview-empty-TEAM"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

/**
 * 分步向导与图标可视化（2026-09-19 重构）。
 *
 * 两块都是「让管理端看得见、少滚一点」的改动：
 * - 表单原本 16 个字段平铺一屏，现在分三步；字段用 v-show 保留在 DOM 里，
 *   所以「跨步填完后直接提交」与既有用例的写法都仍然成立。
 * - 图标原本只显示文本徽标，上传的图片在列表与详情里都看不见。
 */
describe('SkillCatalogView 分步向导与图标可视化', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routerState.route.query = {}
    adminApi.listAdminSkills.mockResolvedValue(catalogPage)
    adminApi.getAdminSkill.mockResolvedValue(detail)
    adminApi.createAdminSkill.mockResolvedValue({ ...detail, id: 'skill-9', code: 'brand-new' })
    adminApi.updateAdminSkill.mockResolvedValue(detail)
    adminApi.fetchAdminSkillIcon.mockResolvedValue(new Blob(['png'], { type: 'image/png' }))
    skillsApi.listSkills.mockResolvedValue(previewCatalog)
    skillsApi.getSkill.mockResolvedValue(previewDetail)
    mockMemberApi()
  })

  afterEach(() => {
    ElMessage.closeAll()
    document.querySelectorAll('.el-message').forEach((message) => message.remove())
  })

  /**
   * `v-show` 的可见性用 style 判断，不用 `isVisible()`：
   * 步骤容器在 el-dialog 内部，`isVisible()` 会受对话框自身的可见性链路影响，
   * 而这里要断言的是「哪一步被 v-show 隐藏」这件独立的事。
   */
  const stepHidden = (wrapper: ReturnType<typeof mount>, step: string) =>
    wrapper.get(`[data-test="skill-form-step-${step}"]`).attributes('style')?.includes('display: none')
    ?? false

  it('opens on the first step and moves forward and back', async () => {
    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="skill-form-steps"]').exists()).toBe(true)
    // 首屏：只有第 1 步可见，后两步被 v-show 隐藏（仍在 DOM 里）
    expect(stepHidden(wrapper, 'identity')).toBe(false)
    expect(stepHidden(wrapper, 'presentation')).toBe(true)
    expect(stepHidden(wrapper, 'delivery')).toBe(true)
    // 中英文名称与分类都属于基础信息，不应再散落到「展示与说明」。
    const identityStep = wrapper.get('[data-test="skill-form-step-identity"]').element
    expect(
      wrapper.get('[data-test="skill-form-name-en"]').element.closest('[data-test="skill-form-step-identity"]'),
    ).toBe(identityStep)
    expect(
      wrapper.get('[data-test="skill-form-category"]').element.closest('[data-test="skill-form-step-identity"]'),
    ).toBe(identityStep)
    // 首屏没有「上一步」
    expect(wrapper.find('[data-test="skill-form-prev"]').exists()).toBe(false)

    await wrapper.get('[data-test="skill-form-next"]').trigger('click')
    await flushPromises()
    expect(stepHidden(wrapper, 'presentation')).toBe(false)
    expect(stepHidden(wrapper, 'identity')).toBe(true)
    expect(wrapper.find('[data-test="skill-form-prev"]').exists()).toBe(true)

    await wrapper.get('[data-test="skill-form-prev"]').trigger('click')
    await flushPromises()
    expect(stepHidden(wrapper, 'identity')).toBe(false)
    expect(stepHidden(wrapper, 'presentation')).toBe(true)
    wrapper.unmount()
  })

  it('keeps every field in the DOM so a cross-step fill can still submit', async () => {
    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()

    // 字段始终存在（分步只是隐藏，不卸载），这是既有用例能继续直接提交的前提
    expect(wrapper.find('[data-test="skill-form-name"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skill-form-summary"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skill-form-min-client"]').exists()).toBe(true)

    await wrapper.get('[data-test="skill-form-code"]').setValue('brand-new')
    await wrapper.get('[data-test="skill-form-name"]').setValue('品牌助手')
    await wrapper.get('[data-test="skill-form-summary"]').setValue('写作品牌内容')
    // 停在第一步就提交，也应成功（不强制点完三步）
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.createAdminSkill).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('jumps to the step that owns the failing field', async () => {
    const error = vi.spyOn(ElMessage, 'error')
    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()

    // 走到第 3 步，但简介（第 2 步的字段）没填
    await wrapper.get('[data-test="skill-form-next"]').trigger('click')
    await wrapper.get('[data-test="skill-form-next"]').trigger('click')
    await flushPromises()
    expect(stepHidden(wrapper, 'delivery')).toBe(false)

    await wrapper.get('[data-test="skill-form-code"]').setValue('brand-new')
    await wrapper.get('[data-test="skill-form-name"]').setValue('品牌助手')
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    // 报错字段在「展示与说明」这一步，界面自动跳回去
    expect(error).toHaveBeenCalled()
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    expect(stepHidden(wrapper, 'presentation')).toBe(false)
    wrapper.unmount()
  })

  it('renders the uploaded icon as an image and falls back to text without one', async () => {
    // jsdom 的 createObjectURL 不产出可用的 blob: 地址，按仓库既有约定打桩
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:icon'),
      revokeObjectURL: vi.fn(),
    })
    adminApi.listAdminSkills.mockResolvedValue({
      items: [
        row({ id: 'with-icon', code: 'with-icon', iconUrl: '/v1/skills/with-icon/icon' }),
        row({ id: 'no-icon', code: 'no-icon', iconUrl: null, icon: '📦' }),
      ],
      total: 2,
      page: 0,
      size: 20,
    })

    const wrapper = await mountReady()

    // 有图的记录：拉取图标字节并渲染 <img>
    expect(adminApi.fetchAdminSkillIcon).toHaveBeenCalledWith('with-icon')
    const withIcon = wrapper.get('[data-test="skill-row-with-icon"] .skill-avatar')
    expect(withIcon.find('img').exists()).toBe(true)
    // 没有 iconUrl 的记录不为它发请求，且回退到文本徽标
    expect(adminApi.fetchAdminSkillIcon).not.toHaveBeenCalledWith('no-icon')
    const noIcon = wrapper.get('[data-test="skill-row-no-icon"] .skill-avatar')
    expect(noIcon.find('img').exists()).toBe(false)
    expect(noIcon.text()).toContain('📦')
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('shows the uploaded icon in the detail drawer header', async () => {
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:icon'),
      revokeObjectURL: vi.fn(),
    })
    adminApi.listAdminSkills.mockResolvedValue({
      items: [row({ id: 'skill-1', iconUrl: '/v1/skills/skill-1/icon' })],
      total: 1,
      page: 0,
      size: 20,
    })

    const wrapper = await mountReady()
    await openDrawer(wrapper)

    const head = wrapper.get('[data-test="skill-drawer"] .skill-drawer__head')
    expect(head.find('.skill-avatar img').exists()).toBe(true)
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  /*
   * 2026-09-19 需求：图片的更新/删除必须先经编辑页的**二次最终确认**。
   *
   * 改动前的行为是「点一下立即生效」：点「清空已上传图标」当场调删除端点、
   * 删掉对象存储里的图并刷新列表，误点没有反悔余地。现在改成两段式：
   * 点「清空」只置标记（可撤销、不碰后端），点「保存修改」时才弹确认并提交。
   *
   * 这组用例锁住三件事：
   * - 点「清空」绝不产生任何后端调用（这是防误操作的核心）；
   * - 标记期间可以撤销，撤销后回到原状态；
   * - 确认后确实提交，且选择「返回修改」时不提交。
   */
  it('marks the icon for clearing without touching the backend', async () => {
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:icon'),
      revokeObjectURL: vi.fn(),
    })
    adminApi.listAdminSkills.mockResolvedValue({
      items: [row({ id: 'skill-1', iconUrl: '/v1/skills/skill-1/icon' })],
      total: 1,
      page: 0,
      size: 20,
    })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail,
      iconUrl: '/v1/skills/skill-1/icon',
    })

    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-edit-data-analyst"]').trigger('click')
    await flushPromises()
    // 图标字段在第 3 步，先跳过去
    await wrapper.get('[data-test="skill-form-next"]').trigger('click')
    await wrapper.get('[data-test="skill-form-next"]').trigger('click')
    await flushPromises()

    // 已上传图标时才出现「清空已上传图标」；此时没有本地待上传文件
    const clearBtn = wrapper.find('[data-test="skill-icon-delete"]')
    expect(clearBtn.exists()).toBe(true)

    await clearBtn.trigger('click')
    await flushPromises()

    // 关键：标记阶段不写后端 —— 这正是「二次确认」的前提
    expect(adminApi.deleteAdminSkillIcon).not.toHaveBeenCalled()
    expect(adminApi.updateAdminSkill).not.toHaveBeenCalled()
    // 标记可见、可撤销
    expect(wrapper.find('[data-test="skill-icon-pending"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skill-icon-delete"]').exists()).toBe(false)
    const undoBtn = wrapper.find('[data-test="skill-icon-undo-clear"]')
    expect(undoBtn.exists()).toBe(true)

    await undoBtn.trigger('click')
    await flushPromises()

    // 撤销后回到原状态，且依然没有碰后端
    expect(wrapper.find('[data-test="skill-icon-pending"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-icon-delete"]').exists()).toBe(true)
    expect(adminApi.deleteAdminSkillIcon).not.toHaveBeenCalled()
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('clears the icon only after the final confirmation on save', async () => {
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:icon'),
      revokeObjectURL: vi.fn(),
    })
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    adminApi.listAdminSkills.mockResolvedValue({
      items: [row({ id: 'skill-1', iconUrl: '/v1/skills/skill-1/icon' })],
      total: 1,
      page: 0,
      size: 20,
    })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail,
      iconUrl: '/v1/skills/skill-1/icon',
    })
    adminApi.deleteAdminSkillIcon.mockResolvedValue({ ...detail, iconUrl: null })

    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-edit-data-analyst"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="skill-form-next"]').trigger('click')
    await wrapper.get('[data-test="skill-form-next"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-test="skill-icon-delete"]').trigger('click')
    await flushPromises()
    expect(adminApi.deleteAdminSkillIcon).not.toHaveBeenCalled()

    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    // 最终确认里列出了这次要做的图片改动
    expect(confirm).toHaveBeenCalled()
    expect(String(confirm.mock.calls.at(-1)?.[0])).toContain('清空已上传的图标')
    expect(adminApi.deleteAdminSkillIcon).toHaveBeenCalledWith('skill-1')
    // 列表要重拉，否则行上还显示着已删除的图
    expect(adminApi.listAdminSkills).toHaveBeenCalledTimes(2)
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('keeps the icon when the operator backs out of the final confirmation', async () => {
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:icon'),
      revokeObjectURL: vi.fn(),
    })
    // 「返回修改」= 取消确认
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel' as never)
    adminApi.listAdminSkills.mockResolvedValue({
      items: [row({ id: 'skill-1', iconUrl: '/v1/skills/skill-1/icon' })],
      total: 1,
      page: 0,
      size: 20,
    })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail,
      iconUrl: '/v1/skills/skill-1/icon',
    })

    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-edit-data-analyst"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="skill-form-next"]').trigger('click')
    await wrapper.get('[data-test="skill-form-next"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-test="skill-icon-delete"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    // 取消确认 → 整个提交放弃：不改表单、不删图、对话框还开着
    expect(adminApi.updateAdminSkill).not.toHaveBeenCalled()
    expect(adminApi.deleteAdminSkillIcon).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="skill-form-submit"]').exists()).toBe(true)
    // 标记仍在，操作者可以改主意再存一次
    expect(wrapper.find('[data-test="skill-icon-pending"]').exists()).toBe(true)
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  // 需求：管理员要把已上传的图标下载到本地（下载是只读的，不写后端状态）
  it('downloads the uploaded icon under <code>-icon.<ext>', async () => {
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:icon'),
      revokeObjectURL: vi.fn(),
    })
    const clicked: HTMLAnchorElement[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      clicked.push(this)
    })
    adminApi.listAdminSkills.mockResolvedValue({
      items: [row({ id: 'skill-1', iconUrl: '/v1/skills/skill-1/icon' })],
      total: 1,
      page: 0,
      size: 20,
    })
    adminApi.getAdminSkill.mockResolvedValue({
      ...detail,
      iconUrl: '/v1/skills/skill-1/icon',
    })
    adminApi.fetchAdminSkillIcon.mockResolvedValue(new Blob(['png'], { type: 'image/png' }))

    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-edit-data-analyst"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="skill-form-next"]').trigger('click')
    await wrapper.get('[data-test="skill-form-next"]').trigger('click')
    await flushPromises()

    // 对照臂：没有本地待上传文件时，「下载」仍应出现（它下载的是已上传的那张）
    const downloadBtn = wrapper.find('[data-test="skill-icon-download"]')
    expect(downloadBtn.exists()).toBe(true)
    expect(wrapper.find('[data-test="skill-icon-clear"]').exists()).toBe(false)

    await downloadBtn.trigger('click')
    await flushPromises()

    expect(adminApi.fetchAdminSkillIcon).toHaveBeenCalledWith('skill-1')
    expect(clicked.map((anchor) => anchor.download)).toEqual(['data-analyst-icon.png'])
    // 下载是只读操作：不该顺手改后端状态
    expect(adminApi.deleteAdminSkillIcon).not.toHaveBeenCalled()
    wrapper.unmount()
    vi.unstubAllGlobals()
  })
})

/**
 * A27 从资源包导入（2026-09-19）。
 *
 * 改动前的缺口：新增只能手打 16 个字段，而包内已经声明了标识、名称、简介与类型，
 * 打错了要等到上传时才以 `package_identity_mismatch` 报出来。
 *
 * 这组用例锁住三件事：
 * - 解析严格只读（不因为「看了一眼」就产生记录）；
 * - 包内声明会覆盖到表单的对应字段，`kind` 以包为准；
 * - 导入产生的记录会把那个包一并上传，不再要求用户重新选一遍文件。
 */
describe('SkillCatalogView 从资源包导入', () => {
  function inspectView(overrides: Record<string, unknown> = {}) {
    return {
      code: 'imported-skill',
      version: '2.0.0',
      kind: 'EXPERT',
      minClientVersion: '2026.7.0',
      title: '导入的专家',
      titleEn: 'Imported Expert',
      summary: '包内声明的简介',
      summaryEn: 'Summary from the package',
      description: '包内声明的简介',
      descriptionEn: 'Summary from the package',
      keywords: ['summary', 'minutes'],
      fileCount: 3,
      extractedBytes: 2048,
      sizeBytes: 1024,
      sha256: 'b'.repeat(64),
      md5: 'c'.repeat(32),
      manifestSha256: 'd'.repeat(64),
      codeAvailable: true,
      occupiedBy: null,
      ...overrides,
    }
  }

  function importZipFile() {
    return new File(['zip-bytes'], 'imported-skill-2.0.0.ycps.zip', { type: 'application/zip' })
  }

  async function clawhubZipFile(withOrigin = true, withVersion = true, owner = 'publisher') {
    const prefix = `@${owner}/demo/`
    const source = [
      {
        path: `${prefix}SKILL.md`,
        bytes: new TextEncoder().encode(`---\nname: Demo Skill\nslug: demo\ndescription: Demo instructions\n${withVersion ? 'version: 2.3.4\n' : ''}---\nUse the demo.\n`),
      },
    ]
    if (withOrigin) {
      source.push({
        path: `${prefix}.clawhub/origin.json`,
        bytes: new TextEncoder().encode(JSON.stringify({
          registry: 'https://clawhub.ai', ownerHandle: owner,
          slug: `@${owner}/demo`, installedVersion: withVersion ? '2.3.4' : undefined,
        })),
      })
    }
    const bytes = await buildZip(source)
    return new File([bytes as BlobPart], 'demo.zip', { type: 'application/zip' })
  }

  function mockClawhubPreview(
    status: 'IMPORTABLE' | 'REVIEW_REQUIRED' | 'BLOCKED' = 'IMPORTABLE',
    version: string | null = '2.3.4',
    code: string | null = 'demo',
  ) {
    adminApi.previewAdminClawhubSkill.mockResolvedValue({
      report: {
        sourceFormat: 'CLAWHUB_SKILL', adapterVersion: '1.0.0',
        sourceSha256: 'a'.repeat(64), code, name: 'Official Demo Title', owner: 'publisher', version,
        status, decisions: [], blockingReasons: status === 'BLOCKED'
          ? ['OpenClaw metadata needs explicit runtime and permission mapping'] : [],
      },
      packageBase64: null,
    })
    adminApi.commitAdminClawhubSkill.mockResolvedValue({
      skillId: 'skill-clawhub-1', sourceId: 'source-1', code: 'demo', version,
      status: 'DRAFT', packageFormat: 'CLAWHUB_SKILL', sourceSha256: 'a'.repeat(64),
      sizeBytes: 2048, scanStatus: 'PENDING',
      reviewRequired: status === 'REVIEW_REQUIRED' || version === null,
    })
  }

  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        skill: {
          slug: 'demo', displayName: 'Official Demo Title',
        },
        owner: { handle: 'publisher' },
      }),
    }))
    routerState.route.query = {}
    adminApi.listAdminSkills.mockResolvedValue(catalogPage)
    adminApi.getAdminSkill.mockResolvedValue(detail)
    adminApi.inspectAdminSkillPackage.mockResolvedValue(inspectView())
    adminApi.createAdminSkill.mockResolvedValue({ ...detail, id: 'skill-9', code: 'imported-skill' })
    adminApi.createAdminExpertAggregate.mockResolvedValue({
      skillId: 'skill-9',
      skill: { ...detail, id: 'skill-9', code: 'imported-skill', kind: 'EXPERT' },
      presentation: {
        title: '导入的专家', titleEn: 'Imported Expert', summary: '包内声明的简介',
        summaryEn: 'Summary from the package', description: '包内声明的简介',
        descriptionEn: 'Summary from the package', taskTypes: [], taskTypesEn: [],
        usageGuide: null, usageGuideEn: null, examplePrompts: [], examplePromptsEn: [],
        preview: null, previewEn: null, assets: {}, portableRevision: 0, rowVersion: 1,
        updatedAt: null,
      },
    })
    adminApi.uploadSkillVersionWithRetry.mockResolvedValue(
      version({ id: 'version-9', version: '2.0.0', status: 'READY', online: false }),
    )
    skillsApi.listSkills.mockResolvedValue(previewCatalog)
    skillsApi.getSkill.mockResolvedValue(previewDetail)
    mockMemberApi()
  })

  afterEach(() => {
    ElMessage.closeAll()
    document.querySelectorAll('.el-message').forEach((message) => message.remove())
    vi.unstubAllGlobals()
  })

  async function openCreateDialog() {
    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()
    return wrapper
  }

  function expectRemoteImportPanel(wrapper: Awaited<ReturnType<typeof openCreateDialog>>) {
    const panel = wrapper.get('[data-test="skill-import"]').get('[data-test="skill-import-remote-panel"]')
    expect(panel.find('[data-test="skill-import-remote-url"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skill-import-menu-remote"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-import-remote-close"]').exists()).toBe(false)
    return panel
  }

  function remoteArchive(url: string, sourceKind: 'CLAWHUB' | 'GITHUB' = 'GITHUB') {
    const file = new File(['same-zip-from-server'], 'remote-skill.zip', { type: 'application/zip' })
    return {
      file,
      metadata: {
        sourceKind,
        owner: sourceKind === 'GITHUB' ? 'github.alice' : 'publisher',
        slug: 'demo-source',
        version: '1.2.3',
        displayName: 'Remote Demo',
        sourceUrl: url,
        fileName: file.name,
        sourceRevision: 'a'.repeat(40),
      },
      sha256: 'a'.repeat(64),
    }
  }

  it('fetches a GitHub link, fills editable fields, and saves metadata with the exact fetched package', async () => {
    const url = 'https://github.com/alice/demo/tree/main/skills/demo'
    const fetched = remoteArchive(url)
    adminApi.fetchAdminRemoteClawhubSkill.mockResolvedValue(fetched)
    adminApi.previewAdminClawhubSkill.mockResolvedValue({
      report: {
        sourceFormat: 'CLAWHUB_SKILL', adapterVersion: '1.0.0',
        sourceSha256: fetched.sha256, code: 'demo', owner: 'github.alice',
        slug: 'demo-source', name: 'Remote Demo', version: '1.2.3',
        description: 'Description from SKILL.md', status: 'IMPORTABLE',
        decisions: [], blockingReasons: [],
      },
      packageBase64: null,
    })
    adminApi.commitAdminClawhubSkill.mockResolvedValue({
      skillId: 'remote-1', sourceId: 'source-1', code: 'demo', version: '1.2.3',
      status: 'DRAFT', packageFormat: 'CLAWHUB_SKILL', sourceSha256: fetched.sha256,
      sizeBytes: fetched.file.size, scanStatus: 'PENDING', reviewRequired: false,
    })
    const wrapper = await openCreateDialog()
    await expectRemoteImportPanel(wrapper)
    await wrapper.get('[data-test="skill-import-remote-url"]').setValue(url)
    await wrapper.get('[data-test="skill-import-remote-fetch"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('[data-test="clawhub-confirm-import"]').exists()).toBe(true))

    expect(adminApi.fetchAdminRemoteClawhubSkill).toHaveBeenCalledWith(url, expect.any(AbortSignal))
    expect(adminApi.previewAdminClawhubSkill).toHaveBeenCalledWith(
      fetched.file, 'github.alice', 'demo-source', '1.2.3', 'Remote Demo',
    )
    expect(wrapper.get('[data-test="clawhub-conversion-report"]').text()).toContain(`GitHub 来源 ${url}`)
    expect(wrapper.get('[data-test="skill-import-staged"]').text()).toContain('GitHub · Remote Demo')
    expect((wrapper.get('[data-test="skill-form-summary"]').element as HTMLInputElement).value)
      .toBe('Description from SKILL.md')
    expect(state(wrapper).form.tags).toEqual(['GitHub', 'alice'])
    await wrapper.get('[data-test="skill-form-name"]').setValue('Edited title')
    await wrapper.get('[data-test="skill-form-summary"]').setValue('Edited summary')
    await wrapper.get('[data-test="skill-form-description"]').setValue('Edited description')
    await wrapper.get('[data-test="clawhub-confirm-import"]').trigger('click')
    await vi.waitFor(() => expect(adminApi.commitAdminClawhubSkill).toHaveBeenCalledTimes(1))
    expect(adminApi.commitAdminClawhubSkill).toHaveBeenCalledWith(expect.objectContaining({
      file: fetched.file, owner: 'github.alice', slug: 'demo-source', version: '1.2.3',
      expectedSha256: fetched.sha256,
      metadata: expect.objectContaining({
        name: 'Edited title', summary: 'Edited summary', description: 'Edited description',
        tags: ['GitHub', 'alice'], sourceKind: 'GITHUB', sourceUrl: url,
        sourceRevision: 'a'.repeat(40),
      }),
    }))
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('uses Enter in the remote URL field only for fetch, never a form submit', async () => {
    const wrapper = await openCreateDialog()
    await expectRemoteImportPanel(wrapper)
    const input = wrapper.get('[data-test="skill-import-remote-url"]').element
    const enter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    input.dispatchEvent(enter)
    await flushPromises()
    expect(enter.defaultPrevented).toBe(true)
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="skill-import-remote-error"]').text()).toContain('HTTPS')
    wrapper.unmount()
  })

  it('discards a slower remote response after its URL changes', async () => {
    const oldUrl = 'https://clawhub.ai/skills/publisher/old'
    const newUrl = 'https://github.com/alice/demo/tree/main/skills/demo'
    let resolveOld!: (value: ReturnType<typeof remoteArchive>) => void
    adminApi.fetchAdminRemoteClawhubSkill
      .mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
      .mockResolvedValueOnce(remoteArchive(newUrl))
    mockClawhubPreview()
    const wrapper = await openCreateDialog()
    await expectRemoteImportPanel(wrapper)
    await wrapper.get('[data-test="skill-import-remote-url"]').setValue(oldUrl)
    await wrapper.get('[data-test="skill-import-remote-fetch"]').trigger('click')
    await vi.waitFor(() => expect(adminApi.fetchAdminRemoteClawhubSkill).toHaveBeenCalledTimes(1))
    await wrapper.get('[data-test="skill-import-remote-url"]').setValue(newUrl)
    await wrapper.get('[data-test="skill-import-remote-fetch"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('[data-test="clawhub-confirm-import"]').exists()).toBe(true))
    resolveOld(remoteArchive(oldUrl, 'CLAWHUB'))
    await flushPromises()
    expect(adminApi.previewAdminClawhubSkill).toHaveBeenCalledTimes(1)
    expect(adminApi.previewAdminClawhubSkill).toHaveBeenCalledWith(
      expect.any(File), 'github.alice', 'demo-source', '1.2.3', 'Remote Demo',
    )
    expect(wrapper.get('[data-test="skill-import-staged"]').text()).toContain('GitHub')
    wrapper.unmount()
  })

  it('keeps failed fetches retryable and cancels a pending preview when the dialog closes', async () => {
    const url = 'https://clawhub.ai/skills/publisher/demo'
    const fetched = remoteArchive(url, 'CLAWHUB')
    adminApi.fetchAdminRemoteClawhubSkill.mockRejectedValueOnce(new Error('远程服务暂不可用'))
      .mockResolvedValueOnce(fetched)
    let resolvePreview!: (value: unknown) => void
    adminApi.previewAdminClawhubSkill.mockReturnValue(new Promise(resolve => { resolvePreview = resolve }))
    const wrapper = await openCreateDialog()
    await expectRemoteImportPanel(wrapper)
    await wrapper.get('[data-test="skill-import-remote-url"]').setValue(url)
    await wrapper.get('[data-test="skill-import-remote-fetch"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.get('[data-test="skill-import-remote-error"]').text())
      .toContain('远程服务暂不可用'))
    await wrapper.get('[data-test="skill-import-remote-fetch"]').trigger('click')
    await vi.waitFor(() => expect(adminApi.previewAdminClawhubSkill).toHaveBeenCalledTimes(1))
    await wrapper.get('[data-test="skill-form-cancel"]').trigger('click')
    resolvePreview({
      report: {
        sourceFormat: 'CLAWHUB_SKILL', adapterVersion: '1.0.0', sourceSha256: fetched.sha256,
        code: 'demo', owner: 'publisher', name: 'Remote Demo', version: '1.2.3',
        status: 'IMPORTABLE', decisions: [], blockingReasons: [],
      }, packageBase64: null,
    })
    await flushPromises()
    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="clawhub-confirm-import"]').exists()).toBe(false)
    expect(adminApi.commitAdminClawhubSkill).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('invalidates a staged remote package when the link changes', async () => {
    const url = 'https://github.com/alice/demo/tree/main/skills/demo'
    adminApi.fetchAdminRemoteClawhubSkill.mockResolvedValue(remoteArchive(url))
    mockClawhubPreview()
    const wrapper = await openCreateDialog()
    await expectRemoteImportPanel(wrapper)
    await wrapper.get('[data-test="skill-import-remote-url"]').setValue(url)
    await wrapper.get('[data-test="skill-import-remote-fetch"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('[data-test="clawhub-confirm-import"]').exists()).toBe(true))
    await wrapper.get('[data-test="skill-import-remote-url"]').setValue(`${url}?changed=1`)
    expect(wrapper.find('[data-test="clawhub-confirm-import"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-import-staged"]').exists()).toBe(false)
    expect(adminApi.commitAdminClawhubSkill).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('rejects a remote ZIP whose fetch and preview digests differ', async () => {
    const url = 'https://clawhub.ai/skills/publisher/demo'
    const fetched = remoteArchive(url, 'CLAWHUB')
    fetched.sha256 = 'b'.repeat(64)
    adminApi.fetchAdminRemoteClawhubSkill.mockResolvedValue(fetched)
    mockClawhubPreview()
    const wrapper = await openCreateDialog()
    await expectRemoteImportPanel(wrapper)
    await wrapper.get('[data-test="skill-import-remote-url"]').setValue(url)
    await wrapper.get('[data-test="skill-import-remote-fetch"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.get('[data-test="skill-import-remote-error"]').text())
      .toContain('预检过程中发生变化'))
    expect(wrapper.find('[data-test="clawhub-confirm-import"]').exists()).toBe(false)
    expect(adminApi.commitAdminClawhubSkill).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('sends a staged remote import only once when confirm is clicked twice', async () => {
    const url = 'https://clawhub.ai/skills/publisher/demo'
    const fetched = remoteArchive(url, 'CLAWHUB')
    adminApi.fetchAdminRemoteClawhubSkill.mockResolvedValue(fetched)
    mockClawhubPreview()
    let resolveCommit!: (value: unknown) => void
    adminApi.commitAdminClawhubSkill.mockReturnValue(new Promise(resolve => { resolveCommit = resolve }))
    const wrapper = await openCreateDialog()
    await expectRemoteImportPanel(wrapper)
    await wrapper.get('[data-test="skill-import-remote-url"]').setValue(url)
    await wrapper.get('[data-test="skill-import-remote-fetch"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('[data-test="clawhub-confirm-import"]').exists()).toBe(true))
    const confirm = wrapper.get('[data-test="clawhub-confirm-import"]')
    await confirm.trigger('click')
    await confirm.trigger('click')
    expect(adminApi.commitAdminClawhubSkill).toHaveBeenCalledTimes(1)
    resolveCommit({
      skillId: 'remote-1', sourceId: 'source-1', code: 'demo', version: '1.2.3',
      status: 'DRAFT', packageFormat: 'CLAWHUB_SKILL', sourceSha256: fetched.sha256,
      sizeBytes: fetched.file.size, scanStatus: 'PENDING', reviewRequired: false,
    })
    await flushPromises()
    wrapper.unmount()
  })

  it('fills the create form from the archive instead of asking the operator to retype it', async () => {
    const wrapper = await openCreateDialog()

    await pickFile(wrapper, '[data-test="skill-import-zip"]', importZipFile())
    await flushPromises()

    expect(adminApi.inspectAdminSkillPackage).toHaveBeenCalledTimes(1)
    const code = wrapper.get('[data-test="skill-form-code"]').element as HTMLInputElement
    const name = wrapper.get('[data-test="skill-form-name"]').element as HTMLInputElement
    const summary = wrapper.get('[data-test="skill-form-summary"]').element as HTMLInputElement
    expect(code.value).toBe('imported-skill')
    expect(name.value).toBe('导入的专家')
    expect(summary.value).toBe('包内声明的简介')
    // Expert 包的 keywords 不属于运营标签；中文/英文文案仍各自落位。
    expect(wrapper.get('[data-test="skill-form-name-en"]').element).toHaveProperty(
      'value',
      'Imported Expert',
    )
    const operationalTags = wrapper.get('[data-test="skill-form-tags"]')
    expect(operationalTags.html()).not.toContain('summary')
    expect(operationalTags.html()).not.toContain('minutes')
    wrapper.unmount()
  })

  it('rejects ClawHub archives from the YCPS picker instead of silently switching formats', async () => {
    const wrapper = await openCreateDialog()

    await pickFile(wrapper, '[data-test="skill-import-zip"]', await clawhubZipFile())
    await vi.waitFor(() => expect(document.body.textContent).toContain('当前入口只接收 YCPS 包'))

    expect(adminApi.previewAdminClawhubSkill).not.toHaveBeenCalled()
    expect(adminApi.inspectAdminSkillPackage).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('rejects YCPS archives from the ClawHub picker', async () => {
    const wrapper = await openCreateDialog()

    await pickFile(wrapper, '[data-test="skill-import-clawhub"]', importZipFile())
    await vi.waitFor(() => expect(document.body.textContent).toContain('不是可识别的 ClawHub 原生包'))

    expect(adminApi.previewAdminClawhubSkill).not.toHaveBeenCalled()
    expect(adminApi.inspectAdminSkillPackage).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('previews a native ClawHub ZIP and shows review state without converting it through YCPS', async () => {
    mockClawhubPreview('REVIEW_REQUIRED')
    const wrapper = await openCreateDialog()

    await pickFile(wrapper, '[data-test="skill-import-clawhub"]',
      await clawhubZipFile())
    await vi.waitFor(() => expect(adminApi.previewAdminClawhubSkill).toHaveBeenCalled())
    await flushPromises()

    expect(adminApi.previewAdminClawhubSkill).toHaveBeenCalledWith(
      expect.any(File), 'publisher', 'demo', '2.3.4', 'Official Demo Title',
    )
    expect(adminApi.inspectAdminSkillPackage).not.toHaveBeenCalled()
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    expect((wrapper.get('[data-test="skill-form-name"]').element as HTMLInputElement).value)
      .toBe('Official Demo Title')
    expect(wrapper.get('[data-test="clawhub-conversion-report"]').text())
      .toContain('技能名称 Official Demo Title')
    expect(wrapper.get('[data-test="clawhub-conversion-report"]').text())
      .toContain('aaaaaaaaaaaa')
    expect(wrapper.get('[data-test="clawhub-conversion-report"]').text())
      .toContain('运行时权限或依赖需上架前核对')
    expect(wrapper.get('[data-test="skill-import-staged"]').text())
      .toContain('ClawHub · Official Demo Title')
    expect(state(wrapper).form.tags).toEqual(['ClawHub', 'publisher'])
    expect(wrapper.get('[data-test="clawhub-import-tags"]').text())
      .toContain('ClawHub、publisher')
    expect(wrapper.get('[data-test="skill-form-tags"]').find('input[disabled]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('shows one source tag when the ClawHub publisher has the same name', async () => {
    mockClawhubPreview()
    adminApi.previewAdminClawhubSkill.mockResolvedValueOnce({
      report: {
        sourceFormat: 'CLAWHUB_SKILL', adapterVersion: '1.0.0',
        sourceSha256: 'a'.repeat(64), code: 'demo', name: 'Demo',
        owner: 'clawhub', version: '2.3.4', status: 'IMPORTABLE',
        decisions: [], blockingReasons: [],
      },
      packageBase64: null,
    })
    const wrapper = await openCreateDialog()
    await pickFile(wrapper, '[data-test="skill-import-clawhub"]',
      await clawhubZipFile(true, true, 'clawhub'))
    await vi.waitFor(() => expect(wrapper.find('[data-test="clawhub-import-tags"]').exists()).toBe(true))

    expect(state(wrapper).form.tags).toEqual(['ClawHub'])
    expect(wrapper.get('[data-test="clawhub-import-tags"]').text())
      .toContain('自动保存标签：ClawHub。')
    wrapper.unmount()
  })

  it('shows blocked reasons and disables confirm for a ClawHub archive the adapter cannot safely map', async () => {
    mockClawhubPreview('BLOCKED')
    const wrapper = await openCreateDialog()

    await pickFile(wrapper, '[data-test="skill-import-clawhub"]',
      await clawhubZipFile())
    await vi.waitFor(() => expect(adminApi.previewAdminClawhubSkill).toHaveBeenCalled())
    await flushPromises()

    expect(adminApi.inspectAdminSkillPackage).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="skill-import-staged"]').exists()).toBe(true)
    expect(document.body.textContent)
      .toContain('OpenClaw metadata needs explicit runtime and permission mapping')
    expect((wrapper.get('[data-test="clawhub-confirm-import"]').element as HTMLButtonElement).disabled)
      .toBe(true)
    expect(adminApi.commitAdminClawhubSkill).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('handles a blocked preview with no code and restores the draft when cancelled', async () => {
    mockClawhubPreview('BLOCKED', '2.3.4', null)
    const wrapper = await openCreateDialog()

    await pickFile(wrapper, '[data-test="skill-import-clawhub"]', await clawhubZipFile())
    await vi.waitFor(() => expect(adminApi.previewAdminClawhubSkill).toHaveBeenCalled())
    await flushPromises()

    expect(wrapper.get('[data-test="clawhub-conversion-report"]').text()).toContain('编码 无法确定')
    expect(document.body.textContent)
      .toContain('OpenClaw metadata needs explicit runtime and permission mapping')
    expect((wrapper.get('[data-test="clawhub-confirm-import"]').element as HTMLButtonElement).disabled)
      .toBe(true)
    expect(adminApi.commitAdminClawhubSkill).not.toHaveBeenCalled()

    await wrapper.get('[data-test="clawhub-cancel-import"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="clawhub-confirm-import"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-import-staged"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('rejects a ClawHub source over 50 MiB locally without requesting preview', async () => {
    const prompt = vi.spyOn(ElMessageBox, 'prompt')
    const wrapper = await openCreateDialog()
    const oversized = new File([new Uint8Array(50 * 1024 * 1024 + 1)], 'demo.zip', {
      type: 'application/zip',
    })

    await pickFile(wrapper, '[data-test="skill-import-clawhub"]', oversized)
    await vi.waitFor(() => expect(document.body.textContent).toContain('超过 50 MB 预检上限'))

    expect(prompt).not.toHaveBeenCalled()
    expect(adminApi.previewAdminClawhubSkill).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="skill-import-staged"]').exists()).toBe(false)
    prompt.mockRestore()
    wrapper.unmount()
  })

  it('detects a dropped native ClawHub ZIP and commits only after the explicit confirm button', async () => {
    mockClawhubPreview()
    const prompt = vi.spyOn(ElMessageBox, 'prompt')
    const wrapper = await openCreateDialog()
    const file = await clawhubZipFile()
    const item = { kind: 'file', getAsFile: () => file }
    const drop = new Event('drop', { bubbles: true, cancelable: true })
    Object.defineProperty(drop, 'dataTransfer', {
      value: { items: [item], files: [file], dropEffect: '' },
    })
    wrapper.get('[data-test="skill-import"]').element.dispatchEvent(drop)
    await vi.waitFor(() => expect(adminApi.previewAdminClawhubSkill).toHaveBeenCalledWith(
      file, 'publisher', 'demo', '2.3.4', 'Official Demo Title',
    ))
    await flushPromises()

    expect(prompt).not.toHaveBeenCalled()
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    expect(adminApi.uploadSkillVersionWithRetry).not.toHaveBeenCalled()
    await wrapper.get('.skill-form').trigger('submit')
    await flushPromises()
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="clawhub-confirm-import"]').text())
      .toContain('确认导入并创建草稿')
    expect(wrapper.get('[data-test="clawhub-import-no-publish"]').text())
      .toContain('不会发布到桌面')
    const footer = wrapper.get('[data-test="clawhub-confirm-import"]')
      .element.closest('.el-dialog__footer')
    expect(footer).not.toBeNull()
    expect(Array.from(footer?.querySelectorAll('button') ?? [], button => button.textContent?.trim()))
      .toEqual(['确认导入并创建草稿', '取消'])
    expect(footer?.querySelector('[data-test="skill-form-cancel"], [data-test="skill-form-prev"], [data-test="skill-form-next"], [data-test="skill-form-submit"]'))
      .toBeNull()
    expect(wrapper.get('[data-test="skill-import"]').find('[data-test="clawhub-confirm-import"]').exists())
      .toBe(false)
    await wrapper.get('[data-test="clawhub-confirm-import"]').trigger('click')
    await vi.waitFor(() => expect(adminApi.commitAdminClawhubSkill).toHaveBeenCalled())
    expect(adminApi.commitAdminClawhubSkill).toHaveBeenCalledWith(expect.objectContaining({
      file, owner: 'publisher', slug: 'demo', version: '2.3.4', expectedSha256: 'a'.repeat(64),
    }))
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    expect(adminApi.uploadSkillVersionWithRetry).not.toHaveBeenCalled()
    expect(adminApi.publishAdminClawhubSkill).not.toHaveBeenCalled()
    await vi.waitFor(() => expect(document.body.textContent).toContain('草稿（未上架）'))
    expect(adminApi.getAdminSkill).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="skill-drawer"]').exists()).toBe(false)
    prompt.mockRestore()
    wrapper.unmount()
  }, 20_000)

  it('uploads an icon selected with a native ClawHub ZIP to the newly created skill', async () => {
    mockClawhubPreview()
    adminApi.uploadAdminSkillIcon.mockResolvedValue(detail)
    const wrapper = await openCreateDialog()
    await pickFile(wrapper, '[data-test="skill-import-clawhub"]', await clawhubZipFile())
    await vi.waitFor(() => expect(wrapper.find('[data-test="clawhub-confirm-import"]').exists()).toBe(true))
    const icon = new File(['icon-bytes'], 'demo-icon.png', { type: 'image/png' })
    await pickFile(wrapper, '[data-test="skill-icon-file"]', icon)

    await wrapper.get('[data-test="clawhub-confirm-import"]').trigger('click')

    await vi.waitFor(() => expect(adminApi.uploadAdminSkillIcon).toHaveBeenCalledWith('skill-clawhub-1', icon))
    expect(adminApi.commitAdminClawhubSkill).toHaveBeenCalledTimes(1)
    expect(adminApi.uploadAdminSkillIcon.mock.invocationCallOrder[0])
      .toBeGreaterThan(adminApi.commitAdminClawhubSkill.mock.invocationCallOrder[0])
    await vi.waitFor(() => expect(document.body.textContent).toContain('草稿（未上架）'))
    wrapper.unmount()
  }, 20_000)

  it('keeps a committed ClawHub draft and reports how to retry when its icon upload fails', async () => {
    mockClawhubPreview()
    adminApi.uploadAdminSkillIcon.mockRejectedValue(new Error('upload unavailable'))
    const wrapper = await openCreateDialog()
    await pickFile(wrapper, '[data-test="skill-import-clawhub"]', await clawhubZipFile())
    await vi.waitFor(() => expect(wrapper.find('[data-test="clawhub-confirm-import"]').exists()).toBe(true))
    await pickFile(wrapper, '[data-test="skill-icon-file"]',
      new File(['icon-bytes'], 'demo-icon.png', { type: 'image/png' }))

    await wrapper.get('[data-test="clawhub-confirm-import"]').trigger('click')

    await vi.waitFor(() => expect(document.body.textContent).toContain('已创建为草稿，但图标上传失败'))
    expect(document.body.textContent).toContain('请在编辑页重新上传')
    expect(adminApi.commitAdminClawhubSkill).toHaveBeenCalledTimes(1)
    expect(adminApi.uploadAdminSkillIcon).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-test="clawhub-confirm-import"]').exists()).toBe(false)
    wrapper.unmount()
  }, 20_000)

  it('locks the staged ClawHub identity and refuses a changed code or kind before creating', async () => {
    mockClawhubPreview()
    const wrapper = await openCreateDialog()
    await pickFile(wrapper, '[data-test="skill-import-clawhub"]', await clawhubZipFile())
    await vi.waitFor(() => expect(wrapper.find('[data-test="clawhub-confirm-import"]').exists()).toBe(true))

    expect(wrapper.get('[data-test="skill-form-code"]').attributes('readonly')).toBeDefined()
    const kindInputs = wrapper.get('[data-test="skill-form-kind"]').findAll('input')
    expect(kindInputs.length).toBeGreaterThan(0)
    expect(kindInputs.every(input => (input.element as HTMLInputElement).disabled)).toBe(true)

    state(wrapper).form.code = 'another-skill'
    await wrapper.get('[data-test="clawhub-confirm-import"]').trigger('click')
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('与预检结果不一致')

    state(wrapper).form.code = 'demo'
    state(wrapper).form.kind = 'EXPERT'
    await wrapper.get('[data-test="clawhub-confirm-import"]').trigger('click')
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    expect(adminApi.uploadSkillVersionWithRetry).not.toHaveBeenCalled()
    wrapper.unmount()
  }, 20_000)

  it('accepts a native ClawHub folder and allows cancelling its staged conversion', async () => {
    mockClawhubPreview()
    const wrapper = await openCreateDialog()
    await wrapper.get('[data-test="skill-form-name"]').setValue('未提交的自写名称')
    const fileForPath = (path: string, content: string) => {
      const file = new File([content], path.split('/').at(-1) ?? 'source')
      Object.defineProperty(file, 'webkitRelativePath', { value: path })
      return file
    }
    const files = [
      fileForPath('demo/SKILL.md', '---\nname: Demo Skill\nslug: demo\ndescription: Demo\nversion: 2.3.4\n---\nUse the demo.\n'),
      fileForPath('demo/.clawhub/origin.json', JSON.stringify({
        registry: 'https://clawhub.ai', ownerHandle: 'publisher',
        slug: '@publisher/demo', installedVersion: '2.3.4',
      })),
    ]
    const input = wrapper.get('[data-test="skill-import-clawhub-folder"]')
    Object.defineProperty(input.element, 'files', { value: files, configurable: true })
    await input.trigger('change')
    await vi.waitFor(() => expect(adminApi.previewAdminClawhubSkill).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'clawhub-folder.zip' }), 'publisher', 'demo', '2.3.4', 'Official Demo Title',
    ))
    await flushPromises()
    expect(wrapper.find('[data-test="clawhub-confirm-import"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skill-form-submit"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-form-cancel"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-form-next"]').exists()).toBe(false)

    await wrapper.get('[data-test="clawhub-cancel-import"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="clawhub-confirm-import"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-import-staged"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-form-submit"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skill-form-cancel"]').exists()).toBe(true)
    expect((wrapper.get('[data-test="skill-form-name"]').element as HTMLInputElement).value)
      .toBe('未提交的自写名称')
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    expect(adminApi.uploadSkillVersionWithRetry).not.toHaveBeenCalled()
    wrapper.unmount()
  }, 20_000)

  it('accepts a native ClawHub folder dropped from the desktop', async () => {
    mockClawhubPreview()
    const wrapper = await openCreateDialog()
    const markdown = new File([
      '---\nname: Demo Skill\nslug: demo\ndescription: Demo\nversion: 2.3.4\n---\nUse the demo.\n',
    ], 'SKILL.md')
    const origin = new File([JSON.stringify({
      registry: 'https://clawhub.ai', ownerHandle: 'publisher',
      slug: '@publisher/demo', installedVersion: '2.3.4',
    })], 'origin.json')
    const fileEntry = (file: File) => ({
      name: file.name, isDirectory: false,
      file: (done: (source: File) => void) => done(file),
    })
    const directoryEntry = (name: string, children: unknown[]) => ({
      name, isDirectory: true,
      createReader: () => {
        let read = false
        return {
          readEntries: (done: (items: unknown[]) => void) => {
            done(read ? [] : children)
            read = true
          },
        }
      },
    })
    const root = directoryEntry('demo', [
      fileEntry(markdown), directoryEntry('.clawhub', [fileEntry(origin)]),
    ])
    const item = { kind: 'file', webkitGetAsEntry: () => root, getAsFile: () => null }
    const drop = new Event('drop', { bubbles: true, cancelable: true })
    Object.defineProperty(drop, 'dataTransfer', {
      value: { items: [item], files: [], dropEffect: '' },
    })
    wrapper.get('[data-test="skill-import"]').element.dispatchEvent(drop)
    await vi.waitFor(() => expect(adminApi.previewAdminClawhubSkill).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'clawhub-folder.zip' }), 'publisher', 'demo', '2.3.4', 'Official Demo Title',
    ))
    await flushPromises()
    expect(drop.defaultPrevented).toBe(true)
    expect(wrapper.find('[data-test="clawhub-confirm-import"]').exists()).toBe(true)
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    wrapper.unmount()
  }, 20_000)

  it('uses a qualified archive path and frontmatter version without a prompt, but flags missing origin for review', async () => {
    mockClawhubPreview('REVIEW_REQUIRED')
    const prompt = vi.spyOn(ElMessageBox, 'prompt')
    const wrapper = await openCreateDialog()
    const file = await clawhubZipFile(false)
    await pickFile(wrapper, '[data-test="skill-import-clawhub"]', file)
    await vi.waitFor(() => expect(adminApi.previewAdminClawhubSkill).toHaveBeenCalledWith(
      file, 'publisher', 'demo', '2.3.4', 'Official Demo Title',
    ))
    await flushPromises()
    expect(prompt).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="clawhub-conversion-report"]').text())
      .toContain('需上架前核对')
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    expect(adminApi.commitAdminClawhubSkill).not.toHaveBeenCalled()
    prompt.mockRestore()
    wrapper.unmount()
  }, 20_000)

  it('allows importing a source with no version as a review-required draft', async () => {
    mockClawhubPreview('REVIEW_REQUIRED', null)
    const wrapper = await openCreateDialog()
    const file = await clawhubZipFile(false, false)
    await pickFile(wrapper, '[data-test="skill-import-clawhub"]', file)
    await vi.waitFor(() => expect(wrapper.find('[data-test="clawhub-confirm-import"]').exists()).toBe(true))

    expect(wrapper.get('[data-test="clawhub-conversion-report"]').text()).toContain('版本 待补充')
    expect(adminApi.previewAdminClawhubSkill).toHaveBeenCalledWith(
      file, 'publisher', 'demo', undefined, 'Official Demo Title',
    )
    await wrapper.get('[data-test="clawhub-confirm-import"]').trigger('click')
    await vi.waitFor(() => expect(adminApi.commitAdminClawhubSkill).toHaveBeenCalled())
    expect(adminApi.commitAdminClawhubSkill).toHaveBeenCalledWith(expect.objectContaining({
      file, owner: 'publisher', slug: 'demo', version: null,
      displayName: 'Official Demo Title', expectedSha256: 'a'.repeat(64),
    }))
    expect(adminApi.publishAdminClawhubSkill).not.toHaveBeenCalled()
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    expect(adminApi.uploadSkillVersionWithRetry).not.toHaveBeenCalled()
    wrapper.unmount()
  }, 20_000)

  it('never creates a record just because a package was inspected', async () => {
    const wrapper = await openCreateDialog()

    await pickFile(wrapper, '[data-test="skill-import-zip"]', importZipFile())
    await flushPromises()

    // 解析是只读的：用户还没点「创建」，就不能有任何写操作。
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    expect(adminApi.uploadSkillVersionWithRetry).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('keeps the package kind and reports an occupied code before submit is attempted', async () => {
    adminApi.inspectAdminSkillPackage.mockResolvedValue(
      inspectView({
        codeAvailable: false,
        occupiedBy: {
          id: 'skill-2',
          code: 'imported-skill',
          name: '已有记录',
          kind: 'EXPERT',
          status: 'PUBLISHED',
        },
      }),
    )
    const wrapper = await openCreateDialog()

    await pickFile(wrapper, '[data-test="skill-import-zip"]', importZipFile())
    await flushPromises()

    // 编码冲突必须在这里就说清楚，而不是等用户点完「创建」收到 409
    expect(wrapper.find('[data-test="skill-form-code-taken"]').exists()).toBe(true)
    // kind 以包内声明为准：让用户在这里改只会制造一次必然失败的提交
    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('uploads the imported package right after creating the record', async () => {
    const wrapper = await openCreateDialog()

    // The legacy local A27 path remains a generic SKILL compatibility path; Expert
    // creation now uses the dedicated YCPS 1.1 portable panel below.
    adminApi.inspectAdminSkillPackage.mockResolvedValue(inspectView({ kind: 'SKILL' }))
    await pickFile(wrapper, '[data-test="skill-import-zip"]', importZipFile())
    await flushPromises()

    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await flushPromises()

    expect(adminApi.createAdminSkill).toHaveBeenCalledWith(
      expect.objectContaining({ code: 'imported-skill', kind: 'SKILL' }),
    )
    // 创建成功后自动把同一个包传上去，用户不必再进编辑态重选一次文件
    expect(adminApi.uploadSkillVersionWithRetry).toHaveBeenCalledWith(
      'skill-9',
      expect.any(File),
      expect.objectContaining({ version: '2.0.0' }),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
    wrapper.unmount()
  }, 20_000)

  it('uses the portable import panel for Expert instead of the legacy local bundle section', async () => {
    const wrapper = mount(SkillCatalogView, {
      props: { kinds: 'EXPERT' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test="skill-create-open"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="portable-import-panel"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="expert-wysiwyg-editor"]').exists()).toBe(true)
    const formHtml = wrapper.get('.skill-form').html()
    expect(formHtml.indexOf('portable-import-panel')).toBeGreaterThanOrEqual(0)
    expect(formHtml.indexOf('portable-import-panel')).toBeLessThan(formHtml.indexOf('expert-wysiwyg-editor'))
    expect(wrapper.find('[data-test="skill-import"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('uses one Expert package import entry while editing and keeps version details read-only', async () => {
    adminApi.listAdminSkills.mockResolvedValue({ ...catalogPage, items: [row({ kind: 'EXPERT' })], total: 1 })
    adminApi.getAdminSkill.mockResolvedValue({ ...detail, kind: 'EXPERT', members: [] })
    const wrapper = mount(SkillCatalogView, {
      props: { kinds: 'EXPERT' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-test="skill-edit-data-analyst"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="portable-import-panel"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="expert-current-version"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="expert-package-import-hint"]').text()).toContain('设为线上')
    expect(wrapper.find('[data-test="expert-version-upload"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="expert-version-replace"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="expert-version-file"]').exists()).toBe(false)

    await wrapper.get('[data-test="skill-form-cancel"]').trigger('click')
    await flushPromises()
    await openDrawer(wrapper)
    expect(wrapper.find('[data-test="skill-version-replace-1.2.0"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-version-publish-1.3.0"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('imports a complete skill bundle and uploads its avatar with the inner YCPS', async () => {
    vi.stubGlobal('CompressionStream', undefined)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    adminApi.inspectAdminSkillPackage.mockResolvedValue(inspectView({ kind: 'SKILL' }))
    adminApi.createAdminSkill.mockResolvedValue({
      ...detail,
      id: 'skill-9',
      code: 'imported-skill',
      kind: 'SKILL',
    })
    adminApi.uploadAdminSkillIcon.mockResolvedValue(detail)
    const bundleBytes = await buildZip([
      {
        path: 'imported-skill-2.0.0/imported-skill-2.0.0.ycps.zip',
        bytes: new TextEncoder().encode('inner-ycps'),
      },
      {
        path: 'imported-skill-2.0.0/imported-skill-icon.png',
        bytes: new Uint8Array([1]),
      },
    ])
    const wrapper = await openCreateDialog()

    await pickFile(
      wrapper,
      '[data-test="skill-import-zip"]',
      new File([bundleBytes as BlobPart], 'imported-skill-2.0.0.bundle.zip'),
    )
    await vi.waitFor(() => expect(adminApi.inspectAdminSkillPackage).toHaveBeenCalledTimes(1))
    await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
    await vi.waitFor(() => expect(adminApi.uploadSkillVersionWithRetry).toHaveBeenCalled())

    expect(adminApi.uploadAdminSkillIcon).toHaveBeenCalledWith(
      'skill-9',
      expect.objectContaining({ name: 'imported-skill-icon.png', type: 'image/png' }),
    )
    expect(adminApi.uploadAdminSkillIllustration).not.toHaveBeenCalled()
    expect(adminApi.uploadAdminSkillExampleImage).not.toHaveBeenCalled()
    expect(adminApi.uploadSkillVersionWithRetry.mock.calls.at(-1)?.[1]).toEqual(
      expect.objectContaining({ name: 'imported-skill-2.0.0.ycps.zip' }),
    )
    wrapper.unmount()
    vi.unstubAllGlobals()
  }, 20_000)

  it('surfaces a parse failure without creating anything', async () => {
    adminApi.inspectAdminSkillPackage.mockRejectedValue(
      apiFailure('ycps_package_invalid', 422),
    )
    const wrapper = await openCreateDialog()

    await pickFile(wrapper, '[data-test="skill-import-zip"]', importZipFile())
    await flushPromises()

    expect(adminApi.createAdminSkill).not.toHaveBeenCalled()
    // 解析失败时表单不该被半填成看起来合法的状态
    expect((wrapper.get('[data-test="skill-form-code"]').element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })

  it('reuses the top ZIP/folder import area in edit mode', async () => {
    const wrapper = await mountReady()
    await wrapper.get('[data-test="skill-edit-data-analyst"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="skill-import"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skill-import-zip-button"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skill-import-toggle"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skill-version-file"]').exists()).toBe(false)
    await wrapper.get('[data-test="skill-import-toggle"]').trigger('click')
    const menu = wrapper.get('[data-test="skill-import-menu"]')
    expect(menu.find('[data-test="skill-import-menu-zip"]').exists()).toBe(true)
    expect(menu.find('[data-test="skill-import-menu-folder"]').exists()).toBe(true)
    expect(menu.find('[data-test="skill-import-menu-clawhub"]').exists()).toBe(false)
    wrapper.unmount()
  })

  /**
   * 合并后的「导入」是分裂按钮：主按钮直接开 zip，箭头展开二选一。
   *
   * 断言两条：下拉默认关闭（不占位、不误导），展开后两个入口都在。
   */
  it('collapses both import entries into one split button with a dropdown', async () => {
    const wrapper = await openCreateDialog()

    // 未展开时菜单不存在，但两个原生 input 都在（按钮靠它们触发系统选择器）
    expect(wrapper.find('[data-test="skill-import-menu"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-import-zip"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skill-import-folder"]').exists()).toBe(true)
    // 原先并排的第二个按钮已被合并掉
    expect(wrapper.find('[data-test="skill-import-folder-button"]').exists()).toBe(false)

    await wrapper.get('[data-test="skill-import-toggle"]').trigger('click')
    await flushPromises()

    const menu = wrapper.get('[data-test="skill-import-menu"]')
    expect(menu.find('[data-test="skill-import-menu-zip"]').exists()).toBe(true)
    expect(menu.find('[data-test="skill-import-menu-folder"]').exists()).toBe(true)
    // 与参考图一致：下拉顶部有一条第三方包安全提示
    expect(menu.get('[data-test="skill-import-notice"]').text()).toContain('安全性')

    // 选中一项后菜单收起，避免挡住下面的表单
    await menu.get('[data-test="skill-import-menu-folder"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="skill-import-menu"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('states that the box accepts a drag from the desktop', async () => {
    const wrapper = await openCreateDialog()

    // 用户要求把「本框支持拖拽」写进文案，否则没人会想到可以拖
    expect(wrapper.get('[data-test="skill-import-hint"]').text()).toContain('拖')
    wrapper.unmount()
  })

  /**
   * 拖拽导入：从桌面直接拖 zip 或文件夹进来。
   *
   * jsdom 没有 `DataTransferItem.webkitGetAsEntry`，所以这里只喂
   * `getAsFile()` 这条回退路径 —— 它同样是真实浏览器在老环境下的降级分支。
   */
  it('imports a package dropped onto the box', async () => {
    const wrapper = await openCreateDialog()
    const zone = wrapper.get('[data-test="skill-import"]')

    const file = importZipFile()
    const item = { kind: 'file', getAsFile: () => file }
    const transfer = { items: [item], files: [file], dropEffect: '' }

    // dragover 必须 preventDefault，否则浏览器会直接把文件当导航打开
    const dragOver = new Event('dragover', { bubbles: true, cancelable: true })
    Object.defineProperty(dragOver, 'dataTransfer', { value: transfer })
    zone.element.dispatchEvent(dragOver)
    expect(dragOver.defaultPrevented).toBe(true)

    const drop = new Event('drop', { bubbles: true, cancelable: true })
    Object.defineProperty(drop, 'dataTransfer', { value: transfer })
    zone.element.dispatchEvent(drop)
    await flushPromises()

    expect(drop.defaultPrevented).toBe(true)
    expect(adminApi.inspectAdminSkillPackage).toHaveBeenCalledTimes(1)
    expect((wrapper.get('[data-test="skill-form-code"]').element as HTMLInputElement).value).toBe(
      'imported-skill',
    )
    wrapper.unmount()
  })

  it('highlights the box while a drag hovers over it', async () => {
    const wrapper = await openCreateDialog()
    const zone = wrapper.get('[data-test="skill-import"]')

    const transfer = { items: [], files: [], dropEffect: '' }
    const enter = new Event('dragenter', { bubbles: true, cancelable: true })
    Object.defineProperty(enter, 'dataTransfer', { value: transfer })
    zone.element.dispatchEvent(enter)
    await flushPromises()

    // 悬停时必须给出「可以放这里」的反馈，否则用户不敢松手
    expect(wrapper.find('[data-test="skill-import-drop-hint"]').exists()).toBe(true)
    expect(zone.classes()).toContain('skill-import--dragging')

    const leave = new Event('dragleave', { bubbles: true, cancelable: true })
    Object.defineProperty(leave, 'dataTransfer', { value: transfer })
    zone.element.dispatchEvent(leave)
    await flushPromises()

    expect(wrapper.find('[data-test="skill-import-drop-hint"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
