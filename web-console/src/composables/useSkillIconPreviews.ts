import { onBeforeUnmount, ref } from 'vue'

import {
  fetchAdminSkillExampleImage,
  fetchAdminSkillIcon,
  fetchAdminSkillIllustration,
} from '../api/admin'

/** 一张「按记录 id 缓存、带 JWT 取字节」的可选图片。 */
export interface SkillImagePreviewSource {
  /** 按 id 拉字节；后端没有这张图时应抛错（可选资源，失败即视为没有）。 */
  fetch: (id: string) => Promise<Blob>
}

/** 技能图标（`skill.icon_object_key`）。 */
export const SKILL_ICON_PREVIEW: SkillImagePreviewSource = { fetch: fetchAdminSkillIcon }

/** 介绍插画（`skill.illustration_object_key`，每包一张、可缺省）。 */
export const SKILL_ILLUSTRATION_PREVIEW: SkillImagePreviewSource = {
  fetch: fetchAdminSkillIllustration,
}

/** 专家功能示例图片（`skill.example_image_object_key`，每包一张、可缺省）。 */
export const SKILL_EXAMPLE_IMAGE_PREVIEW: SkillImagePreviewSource = {
  fetch: fetchAdminSkillExampleImage,
}

/**
 * 已上传图片的本地预览（技能图标 / 介绍插画共用同一套缓存语义）。
 *
 * 图片字节存在对象存储里，`iconUrl` / `illustrationUrl` 需要带管理员 JWT 才能访问，
 * 因此响应式 `<img src="...">` 拿不到 —— 必须用 fetch 取 blob，
 * 再用 `URL.createObjectURL` 生成一个本地地址给 `<img>` 用。
 *
 * 列表、详情抽屉、编辑表单三处都要显示同一张图，所以拉取与释放集中在这里：
 * - 同一个 id 只拉一次（`urls` 就是缓存），列表滚动或重复打开详情不会重复请求；
 * - objectURL 会占用内存直到显式释放，因此组件卸载时统一 revoke，
 *   避免管理员在页面间来回切换后累积泄漏。
 *
 * 这两张图都是**可选**资源：拉取失败只表示「没有图片可显示」，
 * 由调用方回退到文本徽标 / 首字 / 不显示，不抛错、不阻塞列表与详情。
 */
export function useSkillImagePreviews(source: SkillImagePreviewSource) {
  /** 记录 id → 本地 objectURL。只在拉取成功时写入，失败不留空占位。 */
  const imageUrls = ref<Record<string, string>>({})
  /** 正在拉取的 id，供调用方显示占位而不闪烁。 */
  const loading = ref<Record<string, boolean>>({})
  /** 已经拉过且确认没有图片的 id，避免每次渲染都重试一次注定失败的请求。 */
  const missing = ref<Record<string, boolean>>({})
  /** 每次拉取的标识；forget / force / releaseAll 后，旧响应不得写回缓存。 */
  const activeLoads = new Map<string, symbol>()

  let mounted = true

  /** 取某个记录已缓存的图片地址；没有则返回空串，调用方据此回退。 */
  function imageUrlFor(id: string): string {
    return imageUrls.value[id] ?? ''
  }

  /** 是否已经确知该记录没有图片（拉取失败过一次）。 */
  function imageMissing(id: string): boolean {
    return missing.value[id] === true
  }

  function revoke(url: string): void {
    if (url && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url)
  }

  /**
   * 拉取并缓存一个记录的图片。
   *
   * `force` 用于上传新图后强制刷新 —— 此时后端已换图，
   * 必须丢掉旧地址重新拉，否则界面还是上一张。
   */
  async function load(id: string, options: { force?: boolean } = {}): Promise<void> {
    if (!id) return
    if (options.force) {
      forget(id)
    } else {
      if (imageUrls.value[id]) return
      // 已知没有图片的记录不反复重试，除非调用方明确 force
      if (missing.value[id] || loading.value[id]) return
    }

    const request = Symbol(id)
    activeLoads.set(id, request)
    loading.value = { ...loading.value, [id]: true }
    try {
      const blob = await source.fetch(id)
      if (!mounted || activeLoads.get(id) !== request) return
      const url = URL.createObjectURL(blob)
      imageUrls.value = { ...imageUrls.value, [id]: url }
      if (missing.value[id]) {
        const cleared = { ...missing.value }
        delete cleared[id]
        missing.value = cleared
      }
    } catch {
      // 图片可选：失败即视为「没有图片」，调用方回退到文本徽标
      if (mounted && activeLoads.get(id) === request) {
        missing.value = { ...missing.value, [id]: true }
      }
    } finally {
      if (mounted && activeLoads.get(id) === request) {
        activeLoads.delete(id)
        const next = { ...loading.value }
        delete next[id]
        loading.value = next
      }
    }
  }

  /**
   * 为一批记录预取图片。
   *
   * 只处理 `iconUrl` 非空的记录 —— 后端已经在列表里告诉我们哪些有图片，
   * 没有的直接跳过，不为它们发出注定 404 的请求。列表只显示图标，
   * 所以介绍插画不走这里，而是在详情/表单打开时按需 `load`。
   */
  async function loadMany(
    items: ReadonlyArray<{ id: string; iconUrl?: string | null }>,
  ): Promise<void> {
    await Promise.all(
      items
        .filter((item) => Boolean(item.iconUrl))
        .map((item) => load(item.id)),
    )
  }

  /** 丢弃某个记录的缓存（删除记录、或上传失败回滚时用）。 */
  function forget(id: string): void {
    activeLoads.delete(id)
    const url = imageUrls.value[id]
    if (url) revoke(url)
    const next = { ...imageUrls.value }
    delete next[id]
    imageUrls.value = next
    const nextMissing = { ...missing.value }
    delete nextMissing[id]
    missing.value = nextMissing
    const nextLoading = { ...loading.value }
    delete nextLoading[id]
    loading.value = nextLoading
  }

  /** 释放全部 objectURL（关闭详情、卸载组件时调用）。 */
  function releaseAll(): void {
    activeLoads.clear()
    for (const url of Object.values(imageUrls.value)) revoke(url)
    imageUrls.value = {}
    loading.value = {}
  }

  onBeforeUnmount(() => {
    mounted = false
    releaseAll()
  })

  return { imageUrls, imageUrlFor, imageMissing, load, loadMany, forget, releaseAll }
}

/**
 * 技能图标预览（保持既有调用点不变）。
 *
 * 列表、详情抽屉、编辑表单三处都要显示同一张图标，所以拉取与释放集中在这里。
 */
export function useSkillIconPreviews() {
  const previews = useSkillImagePreviews(SKILL_ICON_PREVIEW)
  return {
    iconUrls: previews.imageUrls,
    iconUrlFor: previews.imageUrlFor,
    iconMissing: previews.imageMissing,
    load: previews.load,
    loadMany: previews.loadMany,
    forget: previews.forget,
    releaseAll: previews.releaseAll,
  }
}

/** 介绍插画预览：与图标同一套缓存语义，单独一个实例以免 id 空间混在一起。 */
export function useSkillIllustrationPreviews() {
  return useSkillImagePreviews(SKILL_ILLUSTRATION_PREVIEW)
}

/** 示例图片预览使用独立缓存，避免与图标或介绍插画串图。 */
export function useSkillExampleImagePreviews() {
  return useSkillImagePreviews(SKILL_EXAMPLE_IMAGE_PREVIEW)
}
