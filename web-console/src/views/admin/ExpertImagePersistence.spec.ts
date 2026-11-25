import ElementPlus, { ElMessage } from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import ExpertCatalogView from './ExpertCatalogView.vue'
import SkillCatalogView from './SkillCatalogView.vue'
import ExpertWysiwygEditor from '../../components/admin/ExpertWysiwygEditor.vue'
import { resetExpertCategoryCache } from './expertCategories'

const adminApi = vi.hoisted(() => ({
  listAdminSkills: vi.fn(),
  listDeletedAdminSkills: vi.fn(),
  getAdminSkill: vi.fn(),
  fetchAdminExpertPresentation: vi.fn(),
  fetchAdminSkillExampleImage: vi.fn(),
  updateAdminExpertAggregate: vi.fn(),
  listSkillCategories: vi.fn(),
}))

const skillsApi = vi.hoisted(() => ({
  listSkills: vi.fn(),
  listNativeClawhubSkills: vi.fn(),
}))

const routerState = vi.hoisted(() => ({
  route: { query: {} as Record<string, unknown> },
  replace: vi.fn(),
  push: vi.fn(),
}))

const imageNormalizer = vi.hoisted(() => ({ normalizeExpertImage: vi.fn() }))

vi.mock('../../api/admin', async importOriginal => ({
  ...(await importOriginal<typeof import('../../api/admin')>()),
  ...adminApi,
}))

vi.mock('../../api/skills', async importOriginal => ({
  ...(await importOriginal<typeof import('../../api/skills')>()),
  ...skillsApi,
}))

vi.mock('../../utils/imageChangeConfirm', () => ({
  confirmImageChanges: vi.fn().mockResolvedValue(true),
}))

vi.mock('../../utils/expertImageNormalization', async importOriginal => ({
  ...(await importOriginal<typeof import('../../utils/expertImageNormalization')>()),
  ...imageNormalizer,
}))

vi.mock('vue-router', () => ({
  useRoute: () => routerState.route,
  useRouter: () => ({ replace: routerState.replace, push: routerState.push }),
}))

const expertRow = {
  id: 'expert-image-1',
  code: 'expert-image-demo',
  name: '图片示例专家',
  nameEn: null,
  category: '演示',
  tags: [],
  summary: '图片示例专家',
  summaryEn: null,
  description: '处理图片示例',
  descriptionEn: null,
  icon: null,
  iconUrl: null,
  illustrationUrl: null,
  kind: 'EXPERT' as const,
  status: 'DRAFT' as const,
  publishedVersion: null,
  latestVersion: null,
  minClientVersion: '2026.6.0',
  sizeBytes: null,
  md5: null,
  sha256: null,
  downloadCount: 0,
  sortWeight: 0,
  createdAt: '2026-09-14T02:10:00Z',
  updatedAt: '2026-09-14T03:00:00Z',
  publishedAt: null,
  unpublishedAt: null,
  memberCount: 0,
  versions: [],
}

const oldImagePath = '/console/gateway/admin/skills/expert-image-1/example-image?version=1'
const newImagePath = '/console/gateway/admin/skills/expert-image-1/example-image?version=2'
const oldImageBlob = new Blob(['old-image'], { type: 'image/png' })
const newImageBlob = new Blob(['new-image'], { type: 'image/png' })

let savedImagePath: string | null
let savedImageBlob: Blob
let savedVersion: number
let selectedFile: File

function savedSkill() {
  return {
    ...expertRow,
    rowVersion: savedVersion,
    exampleImageUrl: savedImagePath,
    usageGuide: '请描述需求',
    taskTypes: ['内容生成'],
    examplePrompts: ['写一段介绍'],
    examplePreview: {
      heading: '示例预览', request: '写一段介绍', outputTitle: '结果', outputParagraphs: ['示例结果'],
    },
  }
}

function savedPresentation() {
  return {
    title: '图片示例专家', titleEn: null,
    summary: '图片示例专家', summaryEn: null,
    description: '处理图片示例', descriptionEn: null,
    taskTypes: ['内容生成'], taskTypesEn: [],
    usageGuide: '请描述需求', usageGuideEn: null,
    examplePrompts: ['写一段介绍'], examplePromptsEn: [],
    preview: {
      heading: '示例预览', request: '写一段介绍', outputTitle: '结果', outputParagraphs: ['示例结果'],
    },
    previewEn: null,
    assets: savedImagePath ? { exampleImage: savedImagePath } : {},
    exampleImageLocale: savedImagePath ? 'zh' : null,
    portableRevision: 0,
    rowVersion: savedVersion,
    updatedAt: null,
  }
}

function setupState(wrapper: ReturnType<typeof mountExpertPage>) {
  const catalog = wrapper.getComponent(SkillCatalogView)
  return (catalog.vm.$ as unknown as {
    setupState: {
      expertDraft: { media: { exampleImage: { url: string; locale: string | null } } }
      exampleImagePreviews: { imageUrlFor: (id: string) => string }
    }
  }).setupState
}

function mountExpertPage() {
  return mount(ExpertCatalogView, { global: { plugins: [ElementPlus] } })
}

async function openExpert(wrapper: ReturnType<typeof mountExpertPage>) {
  const edit = wrapper.findAll('[data-test="skill-edit-expert-image-demo"]').at(0)
  expect(edit).toBeDefined()
  await edit!.trigger('click')
  await flushPromises()
  expect(wrapper.findComponent(ExpertWysiwygEditor).exists()).toBe(true)
}

async function saveExpert(wrapper: ReturnType<typeof mountExpertPage>) {
  await wrapper.get('[data-test="skill-form-submit"]').trigger('click')
  await flushPromises()
  expect(adminApi.updateAdminExpertAggregate).toHaveBeenCalled()
}

describe('专家功能示例图保存后重新打开', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetExpertCategoryCache()
    routerState.route.query = {}
    savedImagePath = oldImagePath
    savedImageBlob = oldImageBlob
    savedVersion = 1
    selectedFile = new File(['selected-image'], 'replacement.png', { type: 'image/png' })

    const createObjectURL = vi.fn((blob: Blob) => {
      if (blob === oldImageBlob) return 'blob:old-cloud-image'
      if (blob === newImageBlob) return 'blob:new-cloud-image'
      if (blob === selectedFile) return 'blob:selected-local-image'
      return 'blob:unexpected-image'
    })
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', Object.assign(class extends URL {}, { createObjectURL, revokeObjectURL }))

    adminApi.listAdminSkills.mockImplementation(() => Promise.resolve({
      items: [savedSkill()], total: 1, page: 0, size: 20,
    }))
    adminApi.listDeletedAdminSkills.mockResolvedValue({ items: [], total: 0, page: 0, size: 100 })
    adminApi.getAdminSkill.mockImplementation(() => Promise.resolve(savedSkill()))
    adminApi.fetchAdminExpertPresentation.mockImplementation(() => Promise.resolve({
      skillId: expertRow.id, code: expertRow.code, kind: 'EXPERT', presentation: savedPresentation(),
    }))
    adminApi.fetchAdminSkillExampleImage.mockImplementation(() => savedImagePath
      ? Promise.resolve(savedImageBlob)
      : Promise.reject({ response: { status: 404 } }))
    adminApi.updateAdminExpertAggregate.mockImplementation((_id, payload, files) => {
      const action = payload.images.exampleImage
      if (action === 'CLEAR') savedImagePath = null
      if (action === 'REPLACE') {
        expect(files.exampleImage).toBe(selectedFile)
        savedImagePath = newImagePath
        savedImageBlob = newImageBlob
      }
      savedVersion += 1
      return Promise.resolve({
        skillId: expertRow.id, skill: savedSkill(), presentation: savedPresentation(),
      })
    })
    adminApi.listSkillCategories.mockResolvedValue({ kind: 'EXPERT', categories: [] })
    skillsApi.listSkills.mockResolvedValue({ object: 'list', data: [] })
    skillsApi.listNativeClawhubSkills.mockResolvedValue({ object: 'list', items: [] })
    imageNormalizer.normalizeExpertImage.mockImplementation(file => Promise.resolve(file))
  })

  afterEach(() => {
    ElMessage.closeAll()
    document.querySelectorAll('.el-message').forEach(message => message.remove())
    vi.unstubAllGlobals()
  })

  it('清除云端示例图后发送 CLEAR，并释放旧缓存；重新打开同一专家不回显', async () => {
    const wrapper = mountExpertPage()
    await flushPromises()
    await openExpert(wrapper)

    expect(wrapper.get('[data-test="expert-preview-example-image"] img').attributes('src'))
      .toBe('blob:old-cloud-image')
    expect(setupState(wrapper).exampleImagePreviews.imageUrlFor(expertRow.id)).toBe('blob:old-cloud-image')

    await wrapper.get('[data-test="expert-preview-example-image-clear"]').trigger('click')
    await flushPromises()
    await saveExpert(wrapper)

    expect(adminApi.updateAdminExpertAggregate).toHaveBeenCalledWith(
      expertRow.id,
      expect.objectContaining({ images: expect.objectContaining({ exampleImage: 'CLEAR' }), exampleImageLocale: null }),
      expect.objectContaining({ exampleImage: null }),
    )
    expect(savedImagePath).toBeNull()
    expect(setupState(wrapper).exampleImagePreviews.imageUrlFor(expertRow.id)).toBe('')
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:old-cloud-image')

    await openExpert(wrapper)
    expect(adminApi.fetchAdminSkillExampleImage).toHaveBeenCalledTimes(1)
    expect(setupState(wrapper).expertDraft.media.exampleImage.url).toBe('')
    expect(setupState(wrapper).expertDraft.media.exampleImage.locale).toBeNull()
    expect(wrapper.find('[data-test="expert-preview-example-image"] img').exists()).toBe(false)
    wrapper.unmount()
  })

  it('选新图后又清除时仍发送 CLEAR，保存后不恢复原云端图', async () => {
    const wrapper = mountExpertPage()
    await flushPromises()
    await openExpert(wrapper)

    wrapper.getComponent(ExpertWysiwygEditor).vm.$emit('asset-picked', 'exampleImage', selectedFile)
    await flushPromises()
    expect(setupState(wrapper).expertDraft.media.exampleImage.url).toBe('blob:selected-local-image')

    await wrapper.get('[data-test="expert-preview-example-image-clear"]').trigger('click')
    await flushPromises()
    await saveExpert(wrapper)

    expect(adminApi.updateAdminExpertAggregate).toHaveBeenCalledWith(
      expertRow.id,
      expect.objectContaining({ images: expect.objectContaining({ exampleImage: 'CLEAR' }) }),
      expect.objectContaining({ exampleImage: null }),
    )
    await openExpert(wrapper)
    expect(wrapper.find('[data-test="expert-preview-example-image"] img').exists()).toBe(false)
    wrapper.unmount()
  })

  it('替换云端示例图后发送 REPLACE，重新打开应从服务端显示新图', async () => {
    const wrapper = mountExpertPage()
    await flushPromises()
    await openExpert(wrapper)

    wrapper.getComponent(ExpertWysiwygEditor).vm.$emit('asset-picked', 'exampleImage', selectedFile)
    await flushPromises()
    await saveExpert(wrapper)

    expect(adminApi.updateAdminExpertAggregate).toHaveBeenCalledWith(
      expertRow.id,
      expect.objectContaining({ images: expect.objectContaining({ exampleImage: 'REPLACE' }) }),
      expect.objectContaining({ exampleImage: selectedFile }),
    )
    await openExpert(wrapper)
    expect(adminApi.fetchAdminSkillExampleImage).toHaveBeenCalledTimes(2)
    expect(setupState(wrapper).expertDraft.media.exampleImage.url).toBe('blob:new-cloud-image')
    expect(wrapper.get('[data-test="expert-preview-example-image"] img').attributes('src'))
      .toBe('blob:new-cloud-image')
    wrapper.unmount()
  })
})
