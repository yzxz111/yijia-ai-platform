import { beforeEach, describe, expect, it, vi } from 'vitest'

import { listSkillCategories } from '../../api/admin'
import {
  compareExpertCategoryNames,
  expertCategoryBadge,
  expertCategoryDisplayName,
  expertCategoryKey,
  expertCategoryLabel,
  expertCategoryScope,
  invalidateExpertCategories,
  loadExpertCategories,
  normalizeExpertCategoryInput,
  resetExpertCategoryCache,
  type ExpertCategoryDefinition,
} from './expertCategories'

vi.mock('../../api/admin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/admin')>()),
  listSkillCategories: vi.fn(),
}))

const listCategories = vi.mocked(listSkillCategories)

/** 后端词表条目（A23 响应形状） */
const category = (overrides: Partial<{
  id: string; kind: 'SKILL' | 'EXPERT'; code: string; name: string
  nameEn: string | null; sortOrder: number; enabled: boolean; usageCount: number
}> = {}) => ({
  id: 'cat-1',
  kind: 'SKILL' as const,
  code: 'documents-data',
  name: '文档与数据',
  nameEn: 'Documents & Data',
  sortOrder: 1,
  enabled: true,
  usageCount: 0,
  ...overrides,
})

describe('expertCategories（后端驱动的分类词表）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetExpertCategoryCache()
    listCategories.mockResolvedValue({ kind: 'SKILL', categories: [] })
  })

  it('按包类型映射词表作用域，专家团共用专家侧', () => {
    expect(expertCategoryScope('SKILL')).toBe('SKILL')
    expect(expertCategoryScope('skill')).toBe('SKILL')
    expect(expertCategoryScope('EXPERT')).toBe('EXPERT')
    expect(expertCategoryScope('TEAM')).toBe('EXPERT')
    expect(expertCategoryScope(null)).toBe('EXPERT')
    expect(expertCategoryScope(undefined)).toBe('EXPERT')
  })

  it('把后端词表转成前端定义并按 order 排序', async () => {
    listCategories.mockResolvedValue({
      kind: 'SKILL',
      categories: [
        category({ code: 'other', name: '其他能力', sortOrder: 7, usageCount: 0 }),
        category({ code: 'research', name: '研究与检索', sortOrder: 2, usageCount: 3 }),
      ],
    } as never)

    const result = await loadExpertCategories('SKILL')

    expect(listCategories).toHaveBeenCalledWith('SKILL')
    expect(result.map((entry) => entry.name)).toEqual(['研究与检索', '其他能力'])
    expect(result[0]).toMatchObject({
      code: 'research', name: '研究与检索', nameEn: 'Documents & Data', order: 2, usageCount: 3,
    })
  })

  it('展示名用中文名，英文名只作辅助不替换', async () => {
    listCategories.mockResolvedValue({
      kind: 'SKILL',
      categories: [category({ name: '其他能力', nameEn: null, sortOrder: 7 })],
    } as never)

    const [entry] = await loadExpertCategories('SKILL')
    expect(entry.nameEn).toBe('')
    expect(expertCategoryDisplayName(entry)).toBe('其他能力')
    expect(expertCategoryDisplayName({ ...entry, nameEn: 'Other Capabilities' })).toBe('其他能力')
  })

  it('拉取失败时返回空数组而不是抛错，页面退化为未分类', async () => {
    listCategories.mockRejectedValue(new Error('network down'))

    await expect(loadExpertCategories('EXPERT')).resolves.toEqual([])
  })

  // 需求：词表做本地缓存，避免每次挂载/切作用域都重复请求
  describe('本地缓存', () => {
    it('同一作用域第二次读取不再请求后端', async () => {
      listCategories.mockResolvedValue({
        kind: 'SKILL', categories: [category()],
      } as never)

      const first = await loadExpertCategories('SKILL')
      const second = await loadExpertCategories('SKILL')

      expect(listCategories).toHaveBeenCalledTimes(1)
      expect(second).toEqual(first)
    })

    it('不同作用域各自缓存，互不干扰', async () => {
      listCategories.mockResolvedValue({ kind: 'SKILL', categories: [category()] } as never)

      await loadExpertCategories('SKILL')
      await loadExpertCategories('EXPERT')
      await loadExpertCategories('SKILL')
      await loadExpertCategories('EXPERT')

      expect(listCategories).toHaveBeenCalledTimes(2)
    })

    it('并发读取同一作用域只发一次请求', async () => {
      listCategories.mockResolvedValue({ kind: 'SKILL', categories: [category()] } as never)

      const [first, second] = await Promise.all([
        loadExpertCategories('SKILL'),
        loadExpertCategories('SKILL'),
      ])

      expect(listCategories).toHaveBeenCalledTimes(1)
      expect(second).toEqual(first)
    })

    it('失效后重新请求，管理员新增的分类立刻可见', async () => {
      listCategories.mockResolvedValue({ kind: 'SKILL', categories: [category()] } as never)
      await loadExpertCategories('SKILL')
      expect(listCategories).toHaveBeenCalledTimes(1)

      listCategories.mockResolvedValue({
        kind: 'SKILL',
        categories: [category(), category({ code: 'new-cat', name: '新分类', sortOrder: 8 })],
      } as never)
      invalidateExpertCategories('SKILL')

      const refreshed = await loadExpertCategories('SKILL')
      expect(listCategories).toHaveBeenCalledTimes(2)
      expect(refreshed.map((entry) => entry.name)).toEqual(['文档与数据', '新分类'])
    })

    it('失败结果不写缓存，下次读取还能重试', async () => {
      listCategories.mockRejectedValue(new Error('network down'))
      await expect(loadExpertCategories('SKILL')).resolves.toEqual([])

      listCategories.mockResolvedValue({ kind: 'SKILL', categories: [category()] } as never)
      const retried = await loadExpertCategories('SKILL')

      expect(listCategories).toHaveBeenCalledTimes(2)
      expect(retried.map((entry) => entry.name)).toEqual(['文档与数据'])
    })
  })

  it('分组 key 与展示名对未知值原样兜底，空值给未分类', () => {
    expect(expertCategoryKey('内容与营销')).toBe('内容与营销')
    expect(expertCategoryKey('  研究  ')).toBe('研究')
    expect(expertCategoryKey(null)).toBe('uncategorized')
    expect(expertCategoryKey('')).toBe('uncategorized')

    expect(expertCategoryLabel('文档与数据')).toBe('文档与数据')
    expect(expertCategoryLabel('自建分类')).toBe('自建分类')
    expect(expertCategoryLabel(null)).toBe('未分类')
    expect(expertCategoryLabel('  ')).toBe('未分类')
  })

  it('写库值只做空值归一，不臆造分类', () => {
    expect(normalizeExpertCategoryInput('文档与数据')).toBe('文档与数据')
    expect(normalizeExpertCategoryInput(' 内容与营销 ')).toBe('内容与营销')
    // 未知分类原样保留：管理员的分类不会被前端悄悄改成别的
    expect(normalizeExpertCategoryInput('自建分类')).toBe('自建分类')
    expect(normalizeExpertCategoryInput('')).toBeNull()
    expect(normalizeExpertCategoryInput(null)).toBeNull()
  })

  it('卡片兜底徽标取分类名首字，空值给问号', () => {
    expect(expertCategoryBadge('腾讯专家')).toBe('腾')
    expect(expertCategoryBadge('文档与数据')).toBe('文')
    expect(expertCategoryBadge('Other')).toBe('O')
    expect(expertCategoryBadge(null)).toBe('?')
    expect(expertCategoryBadge('  ')).toBe('?')
  })

  it('分组标题按中文名拼音稳定排序', () => {
    // zh-CN 排序按拼音：本(ben) < 未(wei) < 文(wen)
    expect(['未分类', '文档与数据', '本地系统与工具'].sort(compareExpertCategoryNames))
      .toEqual(['本地系统与工具', '未分类', '文档与数据'])
  })

  it('定义按 order 比较，同序时按名称', () => {
    const build = (name: string, order: number): ExpertCategoryDefinition => ({
      code: name, name, nameEn: '', order, usageCount: 0, hasImage: false,
    })
    const sorted = [build('乙', 2), build('甲', 1), build('丙', 1)]
      .sort((left, right) => (left.order - right.order)
        || left.name.localeCompare(right.name, 'zh-CN'))
    expect(sorted.map((entry) => entry.name)).toEqual(['丙', '甲', '乙'])
  })
})
