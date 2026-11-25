import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'

import { fetchAdminSkillIcon } from '../api/admin'
import { useSkillIconPreviews } from './useSkillIconPreviews'

vi.mock('../api/admin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/admin')>()),
  fetchAdminSkillIcon: vi.fn(),
}))

const fetchIcon = vi.mocked(fetchAdminSkillIcon)

function deferredBlob() {
  let resolve!: (value: Blob) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<Blob>((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}

/**
 * 在真实组件生命周期里跑 composable —— 它用 onBeforeUnmount 释放 objectURL，
 * 只有在组件里挂载/卸载才能验证那条清理路径。
 */
const mountPreviews = () => {
  let api: ReturnType<typeof useSkillIconPreviews> | null = null
  const wrapper = mount(
    defineComponent({
      setup() {
        api = useSkillIconPreviews()
        return () => null
      },
    }),
  )
  return { wrapper, api: api as unknown as ReturnType<typeof useSkillIconPreviews> }
}

describe('useSkillIconPreviews', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // jsdom 有 createObjectURL，但测试里要能断言 revoke 被调用
    URL.createObjectURL = vi.fn(() => `blob:icon-${Math.random().toString(36).slice(2)}`)
    URL.revokeObjectURL = vi.fn()
  })

  it('拉取成功后按 id 缓存，同一 id 不重复请求', async () => {
    fetchIcon.mockResolvedValue(new Blob(['a']))
    const { api } = mountPreviews()

    await api.load('skill-1')
    const first = api.iconUrlFor('skill-1')
    expect(first).toBeTruthy()
    expect(fetchIcon).toHaveBeenCalledTimes(1)

    // 第二次读命中缓存，不再打接口
    await api.load('skill-1')
    expect(fetchIcon).toHaveBeenCalledTimes(1)
    expect(api.iconUrlFor('skill-1')).toBe(first)
  })

  it('拉取失败视为「没有图片」，不抛错且不反复重试', async () => {
    fetchIcon.mockRejectedValue(new Error('404'))
    const { api } = mountPreviews()

    await expect(api.load('skill-x')).resolves.toBeUndefined()
    expect(api.iconUrlFor('skill-x')).toBe('')
    expect(api.iconMissing('skill-x')).toBe(true)

    // 已确知没有图标的记录，后续调用直接跳过（列表重渲染时不会反复打接口）
    await api.load('skill-x')
    expect(fetchIcon).toHaveBeenCalledTimes(1)
  })

  it('forget 清除失败标记，允许重新拉取后来上传的图片', async () => {
    fetchIcon.mockRejectedValueOnce(new Error('404')).mockResolvedValueOnce(new Blob(['new']))
    const { api } = mountPreviews()

    await api.load('skill-1')
    expect(api.iconMissing('skill-1')).toBe(true)

    api.forget('skill-1')
    expect(api.iconMissing('skill-1')).toBe(false)
    await api.load('skill-1')

    expect(fetchIcon).toHaveBeenCalledTimes(2)
    expect(api.iconUrlFor('skill-1')).toBeTruthy()
  })

  it('forget 后未完成的旧响应不能覆盖重新拉取的图片', async () => {
    const stale = deferredBlob()
    fetchIcon.mockReturnValueOnce(stale.promise).mockResolvedValueOnce(new Blob(['new']))
    const { api } = mountPreviews()

    const pending = api.load('skill-1')
    api.forget('skill-1')
    await api.load('skill-1')
    const fresh = api.iconUrlFor('skill-1')

    stale.resolve(new Blob(['old']))
    await pending

    expect(fetchIcon).toHaveBeenCalledTimes(2)
    expect(api.iconUrlFor('skill-1')).toBe(fresh)
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
  })

  it('forget 后旧请求失败也不能重新标记缺图', async () => {
    const stale = deferredBlob()
    fetchIcon.mockReturnValueOnce(stale.promise)
    const { api } = mountPreviews()

    const pending = api.load('skill-1')
    api.forget('skill-1')
    stale.reject(new Error('old 404'))
    await pending

    expect(api.iconMissing('skill-1')).toBe(false)
  })

  it('force 重新拉取会释放旧地址并换上新地址', async () => {
    fetchIcon.mockResolvedValue(new Blob(['a']))
    const { api } = mountPreviews()

    await api.load('skill-1')
    const old = api.iconUrlFor('skill-1')

    fetchIcon.mockResolvedValue(new Blob(['b']))
    await api.load('skill-1', { force: true })

    const fresh = api.iconUrlFor('skill-1')
    expect(fresh).not.toBe(old)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith(old)
    expect(fetchIcon).toHaveBeenCalledTimes(2)
  })

  it('force 在旧请求进行中仍重新拉取，并忽略稍后返回的旧图', async () => {
    const stale = deferredBlob()
    const fresh = deferredBlob()
    fetchIcon.mockReturnValueOnce(stale.promise).mockReturnValueOnce(fresh.promise)
    const { api } = mountPreviews()

    const oldLoad = api.load('skill-1')
    const forcedLoad = api.load('skill-1', { force: true })
    expect(fetchIcon).toHaveBeenCalledTimes(2)

    fresh.resolve(new Blob(['new']))
    await forcedLoad
    const freshUrl = api.iconUrlFor('skill-1')
    stale.resolve(new Blob(['old']))
    await oldLoad

    expect(api.iconUrlFor('skill-1')).toBe(freshUrl)
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
  })

  it('loadMany 只拉有 iconUrl 的记录，跳过没有图片的', async () => {
    fetchIcon.mockResolvedValue(new Blob(['a']))
    const { api } = mountPreviews()

    await api.loadMany([
      { id: 'has-icon', iconUrl: '/v1/skills/has-icon/icon' },
      { id: 'no-icon', iconUrl: null },
      { id: 'also-none' },
    ])

    expect(fetchIcon).toHaveBeenCalledTimes(1)
    expect(fetchIcon).toHaveBeenCalledWith('has-icon')
    expect(api.iconUrlFor('has-icon')).toBeTruthy()
    expect(api.iconUrlFor('no-icon')).toBe('')
  })

  it('组件卸载时释放全部 objectURL，避免内存泄漏', async () => {
    fetchIcon.mockResolvedValue(new Blob(['a']))
    const { wrapper, api } = mountPreviews()

    await api.load('skill-1')
    await api.load('skill-2')
    const urls = [api.iconUrlFor('skill-1'), api.iconUrlFor('skill-2')]
    expect(urls.every(Boolean)).toBe(true)

    wrapper.unmount()

    for (const url of urls) {
      expect(URL.revokeObjectURL).toHaveBeenCalledWith(url)
    }
  })

  it('卸载后落地的响应不再写入，也不会生成泄漏的 objectURL', async () => {
    // 显式持有 resolve，才能在卸载之后再放行响应，验证那条竞态分支
    let resolveFetch: (value: Blob) => void = () => {}
    fetchIcon.mockReturnValue(new Promise<Blob>((resolve) => { resolveFetch = resolve }))
    const { wrapper, api } = mountPreviews()

    const pending = api.load('slow')
    wrapper.unmount()
    resolveFetch(new Blob(['a']))
    await pending

    expect(api.iconUrlFor('slow')).toBe('')
  })

  it('releaseAll 使进行中的请求失效，稍后返回也不会复活缓存', async () => {
    const stale = deferredBlob()
    fetchIcon.mockReturnValueOnce(stale.promise)
    const { api } = mountPreviews()

    const pending = api.load('skill-1')
    api.releaseAll()
    stale.resolve(new Blob(['old']))
    await pending

    expect(api.iconUrlFor('skill-1')).toBe('')
    expect(URL.createObjectURL).not.toHaveBeenCalled()
  })
})
