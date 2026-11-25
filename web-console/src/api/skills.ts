/**
 * 控制台「Skill 市场」数据访问层。
 *
 * 走的是控制台登录态（`gatewayHttp` 自动附带 JWT），调用网关的
 * `/console/gateway/skills`。桌面/控制台优先使用登录 JWT；外部非交互客户端仍可在
 * 对应 `/v1/skills` 接口使用 API Key。两条通道返回结构一致，因此这里同时是
 * 前端共享的类型来源。
 */
import { gatewayHttp } from './http'

/** 文件清单条目（不含正文） */
export interface SkillFileSummary {
  path: string
  bytes: number
  sha256: string
}

/** 技能类型：普通技能 / 专家 / 专家团 */
export type SkillKind = 'SKILL' | 'EXPERT' | 'TEAM'

/** 客户端面的花名册成员 */
export interface SkillMemberSummary {
  /** 数据库目录成员 UUID；旧 filesystem 响应可能缺失 */
  id?: string
  memberKey: string
  name: string | null
  nameEn: string | null
  profession: string | null
  professionEn: string | null
  role: 'LEAD' | 'MEMBER'
  sortOrder: number
  avatarUrl: string | null
}

/**
 * 市场卡片所需的元数据。
 * `description` / `version` / `category` / `icon` 由 `skill.json` 可选提供，缺失时为 `null`；
 * `tags` / `highlights` 缺失时后端返回空数组。
 * 专家化字段在旧服务响应中可能缺失，因此保持可选。
 */
export interface SkillSummary {
  name: string
  title: string
  description: string | null
  /** 数据库目录新增字段；旧 filesystem 目录可能不返回 */
  summary?: string | null
  version: string | null
  category: string | null
  tags: string[]
  icon: string | null
  highlights: string[]
  updatedAt: string
  fileCount: number
  totalBytes: number
  files: SkillFileSummary[]
  status?: string | null
  latestVersion?: string | null
  publishedVersion?: string | null
  minClientVersion?: string | null
  sizeBytes?: number | null
  md5?: string | null
  sha256?: string | null
  downloadUrl?: string | null
  iconUrl?: string | null
  /** 专家介绍插画与功能示例图；未上传时为 null */
  illustrationUrl?: string | null
  exampleImageUrl?: string | null
  kind?: SkillKind
  titleEn?: string | null
  summaryEn?: string | null
  descriptionEn?: string | null
  memberCount?: number
}

export type SkillFileEncoding = 'utf-8' | 'base64'

export interface SkillFileContent extends SkillFileSummary {
  encoding: SkillFileEncoding
  content: string
}

export interface SkillDetail extends Omit<SkillSummary, 'files'> {
  object: 'skill'
  files: SkillFileContent[]
  /** 详情才返回花名册 */
  members?: SkillMemberSummary[]
}

export interface SkillList {
  object: 'list'
  data: SkillSummary[]
  /** 数据库目录的游标字段；旧 filesystem 目录响应中可能缺失 */
  hasMore?: boolean
  next?: string | null
}

/** 原生 ClawHub 目录与 YCPS SkillList 使用不同的响应结构。 */
export interface NativeClawhubSkillSummary {
  code: string
  name: string
  description: string | null
  descriptionEn: string | null
  summary: string | null
  summaryEn: string | null
  status: 'PUBLISHED' | 'UNPUBLISHED'
  version: string
  packageFormat: 'CLAWHUB_SKILL'
  /** 下架条目不返回包校验值、大小或下载链接。 */
  sha256?: string
  sizeBytes?: number
  owner: string
  slug: string
  downloadUrl?: string
  iconUrl?: string | null
  iconUpdatedAt?: string | null
  updatedAt: string
}

export interface NativeClawhubSkillList {
  object: 'list'
  items: NativeClawhubSkillSummary[]
}

export async function listSkills(): Promise<SkillList> {
  const response = await gatewayHttp.get<SkillList>('/console/gateway/skills')
  return response.data
}

export async function listNativeClawhubSkills(): Promise<NativeClawhubSkillList> {
  const response = await gatewayHttp.get<NativeClawhubSkillList>('/v1/native-clawhub/skills')
  return response.data
}

export async function getSkill(name: string): Promise<SkillDetail> {
  const response = await gatewayHttp.get<SkillDetail>(
    `/console/gateway/skills/${encodeURIComponent(name)}`,
  )
  return response.data
}

/**
 * 带登录态拉取客户端目录中某个包的图标字节。
 *
 * 控制台镜像使用 JWT；外部非交互客户端对应 `/v1/skills/{code}/icon` 并兼容 API Key。
 * 鉴权资源不能直接塞给 `<img src>`，页面应将响应转换成 object URL。
 */
export async function fetchSkillIcon(code: string): Promise<Blob> {
  const response = await gatewayHttp.get<Blob>(
    `/console/gateway/skills/${encodeURIComponent(code)}/icon`,
    { responseType: 'blob' },
  )
  return response.data
}

/**
 * 带登录态拉取成员头像字节。
 *
 * 花名册成员是一个**专家包引用**：成员标识就是该专家的 `code`，头像直接复用专家包图标。
 */
export async function fetchSkillMemberAvatar(memberKey: string): Promise<Blob> {
  return fetchSkillIcon(memberKey)
}

/**
 * 带登录态拉取介绍插画字节（同上，需转 object URL 再交给 `<img>`）。
 * 未上传时后端返回 404 `skill_illustration_not_found`，调用方应回退为不显示。
 */
export async function fetchSkillIllustration(name: string): Promise<Blob> {
  const response = await gatewayHttp.get<Blob>(
    `/console/gateway/skills/${encodeURIComponent(name)}/illustration`,
    { responseType: 'blob' },
  )
  return response.data
}

/** 带登录态拉取专家功能示例图片；未上传时后端返回 404。 */
export async function fetchSkillExampleImage(name: string): Promise<Blob> {
  const response = await gatewayHttp.get<Blob>(
    `/console/gateway/skills/${encodeURIComponent(name)}/example-image`,
    { responseType: 'blob' },
  )
  return response.data
}
