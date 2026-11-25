/**
 * 专家 / 专家团 的**二次分类**分类法。
 *
 * 一级分类 = 类型（`EXPERT` 专家 / `TEAM` 专家团）。类型由页面作用域固定
 * （`/admin/experts` 只出专家，`/admin/teams` 只出专家团），与 YCPS 的 `kind` 一一对应，
 * 因此这里只承载**二级分类**，也就是业务分类。
 *
 * 二级分类是**后端管理的词表**（A23/A24）：管理员可以在后台自行新增或删除分类，
 * 删除时后端会校验该分类下是否还有包，有包则拒绝并说明是哪些包。
 * 前端只负责拉取与展示，不再把分类写死在代码里 —— 这样加一个分类不需要发版。
 *
 * 分类作用域按包类型分开：技能用技能侧词表，专家与专家团共用专家侧词表。
 */
import { listSkillCategories, type SkillCategoryView } from '../../api/admin'

/** 分类作用域：技能与专家/专家团各自一套词表 */
export type ExpertCategoryScope = 'SKILL' | 'EXPERT'

/**
 * 一条二级分类。
 *
 * `name` 是写入 `gateway.skill.category` 的值，也是卡片分组标题；
 * `usageCount` 是仍在用它的包数，前端据此在删除前就把按钮置灰。
 */
export interface ExpertCategoryDefinition {
  /** 稳定标识（kebab），来自后端词表；改名不改 code，因此筛选与内置图都按它对齐 */
  code: string
  /** 中文名，也是写库值 */
  name: string
  /** 英文名，客户端与英文界面使用 */
  nameEn: string
  /** 下拉与分组顺序 */
  order: number
  /** 仍在用它的包数；0 表示可安全删除 */
  usageCount: number
  /** 是否已上传分类封面（即精选场景卡片图）；未上传时回退到占位徽标 */
  hasImage: boolean
}

/** 把包类型映射到词表作用域：专家团共用专家侧词表 */
export const expertCategoryScope = (kind: string | null | undefined): ExpertCategoryScope =>
  (kind ?? '').trim().toUpperCase() === 'SKILL' ? 'SKILL' : 'EXPERT'

/**
 * 无自定义 icon 时的卡片兜底徽标（分类名首字）。
 *
 * 后端词表只给名称，不给徽标；首字兜底既适用于中文分类，也不会在管理员新增
 * 一个未预期的分类名时露出空白。
 */
export const expertCategoryBadge = (name: string | null | undefined): string => {
  const value = (name ?? '').trim()
  return value ? value.slice(0, 1) : '?'
}

/** 分类的展示名：空值给「未分类」，其余原样（历史数据不会因为词表变更而消失） */
export const expertCategoryLabel = (name: string | null | undefined): string => {
  const value = (name ?? '').trim()
  return value || '未分类'
}

/** 分组 key：空值给 `uncategorized`，其余原样（未知分类不会被并进已知分类） */
export const expertCategoryKey = (name: string | null | undefined): string => {
  const value = (name ?? '').trim().toLowerCase()
  return value || 'uncategorized'
}

/**
 * 写库值：已知分类给词表里的正式名称，未知值原样保留、空值给 `null`。
 *
 * 词表由后端管理，前端拿到的就是权威名称，所以这里只做空值归一，
 * 不做「把未知值映射到某个已知分类」的臆造 —— 那会让管理员的分类悄悄被改掉。
 */
export const normalizeExpertCategoryInput = (raw: string | null | undefined): string | null => {
  const value = (raw ?? '').trim()
  return value || null
}

/** 分类排序：按后端给的顺序，未知分类排到词表之后（按名称稳定排序） */
export const compareExpertCategories = (
  left: ExpertCategoryDefinition,
  right: ExpertCategoryDefinition,
): number => {
  const delta = left.order - right.order
  return delta !== 0 ? delta : left.name.localeCompare(right.name, 'zh-CN')
}

/** 按展示名排序（分组标题用）：已知分类按 order，未知分类排后 */
export const compareExpertCategoryNames = (left: string, right: string): number => {
  const leftLabel = expertCategoryLabel(left)
  const rightLabel = expertCategoryLabel(right)
  return leftLabel.localeCompare(rightLabel, 'zh-CN')
}

/** 管理端是中文界面：英文名只在非空时作为辅助展示，不替换中文名。 */
export const expertCategoryDisplayName = (category: ExpertCategoryDefinition): string =>
  category.name || category.nameEn

/**
 * 本地缓存：作用域 → 词表。
 *
 * 分类词表是**低频变更**的辅助数据（管理员偶尔增删一次），但页面每次挂载、每次切作用域都会读。
 * 缓存它能省掉重复请求；同时在增删分类后主动失效，保证管理员刚改的分类立刻可见。
 */
const categoryCache = new Map<ExpertCategoryScope, ExpertCategoryDefinition[]>()

/**
 * 进行中的请求，避免并发挂载时对同一作用域发出多次请求。
 *
 * 例如路由在两个专家页之间切换时组件会重建，两个实例可能同时读同一作用域。
 */
const inFlight = new Map<ExpertCategoryScope, Promise<ExpertCategoryDefinition[]>>()

/**
 * 拉取某个作用域的完整词表（带本地缓存）。
 *
 * 失败时返回空数组而不是抛错：分类词表是辅助信息，拉不到时页面应退化为「未分类」
 * 而不是整页报错 —— 包本身的可见性比分组更重要。**失败结果不写缓存**，
 * 这样下一次挂载还能重试，而不是把一次网络抖动永久记住。
 */
export async function loadExpertCategories(
  scope: ExpertCategoryScope,
): Promise<ExpertCategoryDefinition[]> {
  const cached = categoryCache.get(scope)
  if (cached) return cached

  const pending = inFlight.get(scope)
  if (pending) return pending

  const request = (async () => {
    try {
      const result = await listSkillCategories(scope)
      const definitions = result.categories
        .map((category) => toDefinition(category))
        .sort(compareExpertCategories)
      categoryCache.set(scope, definitions)
      return definitions
    } catch {
      return []
    } finally {
      inFlight.delete(scope)
    }
  })()

  inFlight.set(scope, request)
  return request
}

/**
 * 失效某个作用域的缓存，下次读取会重新拉取。
 *
 * 管理员新增或删除分类后必须调用它：否则页面会拿旧词表渲染，
 * 刚加的分类看不见、刚删的分类还在下拉里。
 */
export function invalidateExpertCategories(scope: ExpertCategoryScope): void {
  categoryCache.delete(scope)
}

/** 清空全部缓存（测试与登出时用，避免跨用例串味） */
export function resetExpertCategoryCache(): void {
  categoryCache.clear()
  inFlight.clear()
}

/** 后端词表条目 → 前端定义；顺序缺失时按名称兜底，保证排序稳定 */
function toDefinition(category: SkillCategoryView): ExpertCategoryDefinition {
  return {
    code: category.code,
    name: category.name,
    nameEn: category.nameEn ?? '',
    order: category.sortOrder,
    usageCount: category.usageCount,
    hasImage: Boolean(category.imageUrl),
  }
}
