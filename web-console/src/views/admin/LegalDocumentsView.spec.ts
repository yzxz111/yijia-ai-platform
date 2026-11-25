import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import LegalDocumentsView from './LegalDocumentsView.vue'

/** 只桩掉被断言的接口；其余导出沿用真实实现（与 ExpertCatalogView.spec.ts 同一套写法）。 */
const adminApi = vi.hoisted(() => ({
  getLegalDocuments: vi.fn(),
  saveLegalDocumentDraft: vi.fn(),
  publishLegalDocument: vi.fn(),
  restoreLegalDocumentVersion: vi.fn(),
}))

vi.mock('../../api/admin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/admin')>()),
  ...adminApi,
}))

const termsSections = [
  {
    heading: '一、协议范围（草稿）',
    paragraphs: ['本协议是您与本平台就使用一加AI中台服务所订立的约定。'],
    items: ['仅在平台服务范围内使用'],
  },
  {
    heading: '二、账号与安全',
    paragraphs: ['您应妥善保管账号与 API 密钥。'],
  },
]

const privacySections = [
  {
    heading: '一、我们收集的信息',
    paragraphs: ['为提供账号与服务能力，我们收集以下信息：'],
    items: ['账号信息：手机号、邮箱、显示名称。'],
  },
]

const termsPublished = {
  kind: 'TERMS' as const,
  version: '2026-09-15',
  title: '平台服务协议',
  content: {
    placeholder: '占位说明 A',
    sections: [{ heading: '一、协议范围', paragraphs: ['已发布正文。'] }],
  },
}

const termsDraft = {
  kind: 'TERMS' as const,
  version: '2026-09-15-draft',
  title: '平台服务协议（草稿）',
  content: { placeholder: '占位说明 A', sections: termsSections },
}

const privacyPublished = {
  kind: 'PRIVACY' as const,
  version: '2026-09-10',
  title: '隐私政策',
  content: { placeholder: '占位说明 B', sections: privacySections },
}

const termsHistory = [
  {
    version: '2026-09-15',
    title: '平台服务协议',
    published: true,
    publishedAt: '2026-09-15T02:57:07Z',
    publishedBy: 'admin-1',
    createdAt: '2026-09-15T02:57:07Z',
    updatedAt: '2026-09-15T02:57:07Z',
  },
  {
    version: '2026-09-01',
    title: '平台服务协议（旧版）',
    published: false,
    publishedAt: null,
    publishedBy: null,
    createdAt: '2026-09-01T01:00:00Z',
    updatedAt: '2026-09-01T01:00:00Z',
  },
  {
    version: '2026-09-15-draft',
    title: '平台服务协议（草稿）',
    published: false,
    publishedAt: null,
    publishedBy: null,
    createdAt: '2026-09-16T01:00:00Z',
    updatedAt: '2026-09-16T01:00:00Z',
  },
]

const privacyHistory = [
  {
    version: '2026-09-10',
    title: '隐私政策',
    published: true,
    publishedAt: '2026-09-10T02:00:00Z',
    publishedBy: 'admin-1',
    createdAt: '2026-09-10T02:00:00Z',
    updatedAt: '2026-09-10T02:00:00Z',
  },
]

/** 后端四个接口都返回同一份全量状态。 */
const adminState = {
  documents: [
    { kind: 'TERMS', published: termsPublished, draft: termsDraft, history: termsHistory },
    { kind: 'PRIVACY', published: privacyPublished, draft: null, history: privacyHistory },
  ],
}

describe('LegalDocumentsView（协议管理）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    adminApi.getLegalDocuments.mockResolvedValue(adminState)
    adminApi.saveLegalDocumentDraft.mockResolvedValue(adminState)
    adminApi.publishLegalDocument.mockResolvedValue(adminState)
    adminApi.restoreLegalDocumentVersion.mockResolvedValue(adminState)
  })

  afterEach(() => {
    ElMessage.closeAll()
    document.querySelectorAll('.el-message').forEach((message) => message.remove())
  })

  async function mountPage() {
    const wrapper = mount(LegalDocumentsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    return wrapper
  }

  it('加载后展示已发布版本号与草稿条款', async () => {
    const wrapper = await mountPage()

    expect(adminApi.getLegalDocuments).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-test="legal-published-version"]').text()).toBe('2026-09-15')
    expect(wrapper.get('[data-test="legal-published-title"]').text()).toBe('平台服务协议')
    expect(wrapper.get('[data-test="legal-published-notice"]').text()).toContain('不可修改')

    // 编辑器展示的是草稿（标题与条款标题都取自 draft）
    expect((wrapper.get('[data-test="legal-draft-title"]').element as HTMLInputElement).value)
      .toBe('平台服务协议（草稿）')
    const headings = wrapper.findAll('[data-test="legal-section-heading"]')
    expect(headings).toHaveLength(2)
    expect((headings[0].element as HTMLInputElement).value).toBe('一、协议范围（草稿）')
    expect((wrapper.get('[data-test="legal-section-paragraphs"]').element as HTMLTextAreaElement).value)
      .toBe('本协议是您与本平台就使用一加AI中台服务所订立的约定。')
    expect((wrapper.get('[data-test="legal-section-items"]').element as HTMLTextAreaElement).value)
      .toBe('仅在平台服务范围内使用')

    // 历史表：已发布行不可恢复，草稿行与旧版本行可恢复
    expect(wrapper.findAll('[data-test="legal-history-table"] tbody tr')).toHaveLength(3)
    expect(wrapper.get('[data-test="legal-history-table"]').text()).toContain('已发布')
    expect(wrapper.findAll('[data-test="legal-restore-version"]')).toHaveLength(2)

    wrapper.unmount()
  })

  it('保存草稿时把「一行一段」的文本拆回数组提交', async () => {
    const wrapper = await mountPage()

    await wrapper.findAll('[data-test="legal-section-paragraphs"]')[0].setValue('第一段正文\n第二段正文\n')
    await wrapper.get('[data-test="legal-save-draft"]').trigger('click')
    await flushPromises()

    expect(adminApi.saveLegalDocumentDraft).toHaveBeenCalledTimes(1)
    expect(adminApi.saveLegalDocumentDraft).toHaveBeenCalledWith('TERMS', {
      title: '平台服务协议（草稿）',
      content: {
        placeholder: '占位说明 A',
        sections: [
          {
            heading: '一、协议范围（草稿）',
            paragraphs: ['第一段正文', '第二段正文'],
            items: ['仅在平台服务范围内使用'],
          },
          {
            heading: '二、账号与安全',
            paragraphs: ['您应妥善保管账号与 API 密钥。'],
          },
        ],
      },
    })

    wrapper.unmount()
  })

  it('发布时使用管理员输入的版本号', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountPage()

    await wrapper.get('[data-test="legal-publish"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="legal-publish-version"]').setValue('2026-10-01')
    await wrapper.get('[data-test="legal-publish-confirm"]').trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(adminApi.publishLegalDocument).toHaveBeenCalledTimes(1)
    expect(adminApi.publishLegalDocument).toHaveBeenCalledWith('TERMS', '2026-10-01', {
      title: '平台服务协议（草稿）',
      content: {
        placeholder: '占位说明 A',
        sections: [
          {
            heading: '一、协议范围（草稿）',
            paragraphs: ['本协议是您与本平台就使用一加AI中台服务所订立的约定。'],
            items: ['仅在平台服务范围内使用'],
          },
          {
            heading: '二、账号与安全',
            paragraphs: ['您应妥善保管账号与 API 密钥。'],
          },
        ],
      },
    })

    wrapper.unmount()
  })

  it('版本号重复（409 legal_document_version_exists）时显示错误而不是抛出', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    adminApi.publishLegalDocument.mockRejectedValueOnce({
      response: {
        status: 409,
        data: { code: 'legal_document_version_exists', message: 'legal_document_version_exists' },
      },
    })
    const wrapper = await mountPage()

    await wrapper.get('[data-test="legal-publish"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="legal-publish-version"]').setValue('2026-09-15')
    await wrapper.get('[data-test="legal-publish-confirm"]').trigger('click')
    await flushPromises()

    const messages = Array.from(document.querySelectorAll('.el-message'))
      .map(element => element.textContent ?? '')
      .join(' ')
    expect(messages).toContain('该版本号已经被使用过')
    // 弹窗保留，管理员可以改成新的版本号后重试
    expect(wrapper.find('[data-test="legal-publish-version"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('恢复该版本会对所选历史版本调用恢复接口', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountPage()

    const restores = wrapper.findAll('[data-test="legal-restore-version"]')
    expect(restores).toHaveLength(2)
    await restores[0].trigger('click')
    await flushPromises()

    expect(adminApi.restoreLegalDocumentVersion).toHaveBeenCalledTimes(1)
    expect(adminApi.restoreLegalDocumentVersion).toHaveBeenCalledWith('TERMS', '2026-09-01')

    wrapper.unmount()
  })

  it('切换页签后编辑隐私政策草稿（没有草稿时以已发布正文为起点）', async () => {
    const wrapper = await mountPage()

    const tabs = wrapper.findAll('.el-tabs__item')
    expect(tabs.map(tab => tab.text())).toEqual(['平台服务协议', '隐私政策'])
    await tabs[1].trigger('click')
    await flushPromises()

    const privacy = wrapper.get('[data-test="legal-kind-PRIVACY"]')
    expect((privacy.get('[data-test="legal-draft-title"]').element as HTMLInputElement).value).toBe('隐私政策')
    expect(privacy.findAll('[data-test="legal-section-heading"]')).toHaveLength(1)

    wrapper.unmount()
  })

  it('管理端状态加载失败（503）时显示错误状态与重试按钮', async () => {
    adminApi.getLegalDocuments.mockRejectedValueOnce({
      response: {
        status: 503,
        data: { code: 'legal_document_unavailable', message: 'legal_document_unavailable' },
      },
    })
    const wrapper = await mountPage()

    expect(wrapper.find('[data-test="legal-load-failure"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="legal-draft-title"]').exists()).toBe(false)

    await wrapper.get('[data-test="legal-retry"]').trigger('click')
    await flushPromises()

    expect(adminApi.getLegalDocuments).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-test="legal-load-failure"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="legal-published-version"]').text()).toBe('2026-09-15')

    wrapper.unmount()
  })

  it('正文缺少 sections 时渲染空编辑器而不是抛错', async () => {
    adminApi.getLegalDocuments.mockResolvedValueOnce({
      documents: [
        {
          kind: 'TERMS',
          published: { kind: 'TERMS', version: '2026-09-15', title: '平台服务协议', content: {} },
          draft: null,
          history: [],
        },
        { kind: 'PRIVACY', published: null, draft: null, history: null },
      ],
    })
    const wrapper = await mountPage()

    expect(wrapper.findAll('[data-test="legal-section-heading"]')).toHaveLength(0)
    expect(wrapper.find('[data-test="legal-sections-empty"]').exists()).toBe(true)

    // 空编辑器仍可新增条款；标题沿用已发布标题
    await wrapper.get('[data-test="legal-add-section"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-test="legal-section-heading"]')).toHaveLength(1)
    expect((wrapper.get('[data-test="legal-draft-title"]').element as HTMLInputElement).value).toBe('平台服务协议')

    wrapper.unmount()
  })
})
