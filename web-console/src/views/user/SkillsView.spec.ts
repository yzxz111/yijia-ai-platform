import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import SkillsView from './SkillsView.vue'

const SHA256 = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'

const mocks = vi.hoisted(() => ({
  listSkills: vi.fn(),
  getSkill: vi.fn(),
  downloadSkillJson: vi.fn(),
  downloadSkillFile: vi.fn(),
}))

vi.mock('../../api/skills', () => ({
  listSkills: mocks.listSkills,
  getSkill: mocks.getSkill,
}))

vi.mock('../../utils/skillDownload', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../utils/skillDownload')>()
  return {
    ...actual,
    downloadSkillJson: mocks.downloadSkillJson,
    downloadSkillFile: mocks.downloadSkillFile,
  }
})

function summary(overrides: Record<string, unknown> = {}) {
  return {
    name: 'content-writer',
    title: '内容生成助手',
    description: '面向公众号与小红书的写作助手。',
    version: '0.1.0',
    category: '内容创作',
    tags: ['文案', '新媒体'],
    icon: '✍️',
    highlights: ['一句话生成多平台文案初稿', '内置品牌语气', '长文拆分为短内容'],
    updatedAt: '2026-09-12T03:37:00Z',
    fileCount: 1,
    totalBytes: 0,
    files: [{ path: 'SKILL.md', bytes: 0, sha256: SHA256 }],
    ...overrides,
  }
}

const analyst = summary({
  name: 'data-analyst',
  title: '数据分析助手',
  description: '把 CSV 报表变成结论。',
  category: '数据分析',
  tags: ['报表', '指标'],
  icon: '📊',
  highlights: ['指标摘要'],
})

const ecommerce = summary({
  name: 'ecommerce-ops',
  title: '电商运营助手',
  description: '商品标题、活动策划与客服话术。',
  category: '电商运营',
  tags: ['商品', '活动', '客服'],
  icon: '🛒',
  highlights: ['平台规则优化'],
})

const legacy = summary({
  name: 'example-skill',
  title: '示例 Skill',
  description: '占位示例，内容待补充。',
  category: null,
  tags: [],
  icon: null,
  highlights: [],
})

const catalog = [summary(), analyst, ecommerce, legacy]

const detail = {
  object: 'skill' as const,
  name: 'content-writer',
  title: '内容生成助手',
  description: '面向公众号与小红书的写作助手，按品牌语气产出标题、正文与配文。',
  version: '0.1.0',
  category: '内容创作',
  tags: ['文案', '新媒体'],
  icon: '✍️',
  highlights: ['一句话生成多平台文案初稿', '内置品牌语气', '长文拆分为短内容'],
  updatedAt: '2026-09-12T03:37:00Z',
  fileCount: 2,
  totalBytes: 8,
  files: [
    { path: 'SKILL.md', encoding: 'utf-8' as const, bytes: 5, sha256: SHA256, content: '# 内容\n' },
    { path: 'assets/logo.bin', encoding: 'base64' as const, bytes: 3, sha256: 'b'.repeat(64), content: 'AAEC' },
  ],
}

function mountView() {
  return mount(SkillsView, { global: { plugins: [ElementPlus] } })
}

async function mountReady() {
  const wrapper = mountView()
  await flushPromises()
  return wrapper
}

describe('SkillsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.listSkills.mockResolvedValue({ object: 'list', data: catalog })
    mocks.getSkill.mockResolvedValue(detail)
  })

  it('renders one marketplace card per expert with icon, category, highlights and metadata', async () => {
    const wrapper = await mountReady()

    expect(mocks.listSkills).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('[data-test="skills-grid"] article')).toHaveLength(4)

    const card = wrapper.get('[data-test="skill-card-content-writer"]')
    expect(card.text()).toContain('内容生成助手')
    expect(card.text()).toContain('content-writer')
    expect(card.text()).toContain('内容创作')
    expect(card.text()).toContain('面向公众号与小红书的写作助手。')
    expect(card.text()).toContain('✍️')
    expect(card.text()).toContain('v0.1.0')
    expect(card.text()).toContain('2026-09-12')
    expect(card.text()).not.toContain('2026-09-12T03:37:00Z')
    expect(card.findAll('li')).toHaveLength(3)

    expect(wrapper.find('[data-test="skills-empty"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skills-error"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('caps the card highlights at three and falls back to the first letter when icon is missing', async () => {
    mocks.listSkills.mockResolvedValue({
      object: 'list',
      data: [summary({ highlights: ['一', '二', '三', '四'] }), legacy],
    })
    const wrapper = await mountReady()

    const card = wrapper.get('[data-test="skill-card-content-writer"]')
    expect(card.findAll('li')).toHaveLength(3)
    expect(card.text()).toContain('三')
    expect(card.text()).not.toContain('四')

    // example-skill 没有 icon/category/tags/highlights：首字母兜底，不报错也不留空块
    const fallback = wrapper.get('[data-test="skill-card-example-skill"]')
    expect(fallback.text()).toContain('示')
    expect(fallback.findAll('li')).toHaveLength(0)
    wrapper.unmount()
  })

  it('filters the grid by name, description and tags, then restores the full catalog', async () => {
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-search"]').setValue('data-analyst')
    expect(wrapper.findAll('[data-test="skills-grid"] article')).toHaveLength(1)
    expect(wrapper.find('[data-test="skill-card-data-analyst"]').exists()).toBe(true)

    await wrapper.get('[data-test="skill-search"]').setValue('CSV')
    expect(wrapper.findAll('[data-test="skills-grid"] article')).toHaveLength(1)
    expect(wrapper.find('[data-test="skill-card-data-analyst"]').exists()).toBe(true)

    await wrapper.get('[data-test="skill-search"]').setValue('客服')
    expect(wrapper.findAll('[data-test="skills-grid"] article')).toHaveLength(1)
    expect(wrapper.find('[data-test="skill-card-ecommerce-ops"]').exists()).toBe(true)

    await wrapper.get('[data-test="skill-search"]').setValue('不存在的专家')
    expect(wrapper.findAll('[data-test="skills-grid"] article')).toHaveLength(0)
    expect(wrapper.get('[data-test="skills-no-match"]').text()).toContain('没有匹配的专家')

    await wrapper.get('[data-test="skill-search-reset"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-test="skills-grid"] article')).toHaveLength(4)
    wrapper.unmount()
  })

  it('maps the error code to stable Chinese copy and keeps the request id visible', async () => {
    mocks.listSkills.mockRejectedValue({
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

    expect(wrapper.get('[data-test="skills-error-message"]').text()).toBe('Skill 不存在或已下架。')
    expect(wrapper.get('[data-test="skills-error-request-id"]').text()).toBe('req_console_404')
    expect(wrapper.text()).not.toContain('Skill not found')
    expect(wrapper.find('[data-test="skills-grid"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('falls back to the status copy and the body request_id when no code is returned', async () => {
    mocks.listSkills.mockRejectedValue({
      response: { status: 401, headers: {}, data: { request_id: 'req_body_only' } },
    })
    const wrapper = await mountReady()

    expect(wrapper.get('[data-test="skills-error-message"]').text()).toBe('登录状态已失效，请重新登录后再试。')
    expect(wrapper.get('[data-test="skills-error-request-id"]').text()).toBe('req_body_only')
    wrapper.unmount()
  })

  it('shows the empty state when the platform has published nothing yet', async () => {
    mocks.listSkills.mockResolvedValue({ object: 'list', data: [] })
    const wrapper = await mountReady()

    expect(wrapper.get('[data-test="skills-empty"]').text()).toContain('暂无可用专家')
    expect(wrapper.find('[data-test="skills-grid"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('opens the detail dialog with the file list, sha256 and a utf-8 preview', async () => {
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-card-detail-content-writer"]').trigger('click')
    await flushPromises()

    expect(mocks.getSkill).toHaveBeenCalledWith('content-writer')
    const dialog = wrapper.get('[data-test="skill-detail"]')
    expect(dialog.text()).toContain('面向公众号与小红书的写作助手，按品牌语气产出标题、正文与配文。')
    expect(dialog.get('[data-test="skill-detail-tags"]').text()).toContain('文案')

    const files = wrapper.get('[data-test="skill-detail-files"]')
    expect(files.text()).toContain('SKILL.md')
    expect(files.text()).toContain('assets/logo.bin')
    expect(files.text()).toContain(SHA256)
    expect(files.text()).toContain('二进制文件')

    await wrapper.get('[data-test="skill-detail-preview-content-writer-0"]').trigger('click')
    // 预览必须原样保留换行与缩进，因此断言 textContent 而不是被 trim 过的 text()
    expect(wrapper.get('[data-test="skill-preview-body"]').element.textContent).toBe('# 内容\n')

    // base64 文件没有预览按钮，只能下载还原
    expect(wrapper.find('[data-test="skill-detail-preview-content-writer-1"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('downloads the skill snapshot as <name>.skill.json without any API key', async () => {
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-card-download-content-writer"]').trigger('click')
    await flushPromises()

    expect(mocks.getSkill).toHaveBeenCalledWith('content-writer')
    expect(mocks.downloadSkillJson).toHaveBeenCalledWith('content-writer', detail)
    expect(wrapper.text()).not.toContain('API Key')
    expect(wrapper.find('[data-test="skill-api-key"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('downloads a single binary file through the shared helper', async () => {
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-card-detail-content-writer"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="skill-detail-download-content-writer-1"]').trigger('click')

    expect(mocks.downloadSkillFile).toHaveBeenCalledWith(detail, detail.files[1])
    wrapper.unmount()
  })

  it('keeps detail failures inside the dialog with their own copy and request id', async () => {
    mocks.getSkill.mockRejectedValue({
      response: {
        status: 422,
        headers: { 'x-request-id': 'req_bad_meta' },
        data: {
          error: { message: 'Skill metadata is invalid', code: 'invalid_skill_metadata' },
          request_id: 'req_bad_meta',
        },
      },
    })
    const wrapper = await mountReady()

    await wrapper.get('[data-test="skill-card-detail-content-writer"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="skill-detail-error-message"]').text())
      .toBe('Skill 元数据不合法，请联系管理员检查 skill.json。')
    expect(wrapper.get('[data-test="skill-detail-error-request-id"]').text()).toBe('req_bad_meta')
    // 列表仍然可用，失败只影响弹窗内部
    expect(wrapper.findAll('[data-test="skills-grid"] article')).toHaveLength(4)
    wrapper.unmount()
  })
})
