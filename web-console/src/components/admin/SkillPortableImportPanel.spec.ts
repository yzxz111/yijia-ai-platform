import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import SkillPortableImportPanel from './SkillPortableImportPanel.vue'
import type { PortableImportResponse } from '../../api/admin'

const api = vi.hoisted(() => ({
  uploadAdminPortableImport: vi.fn(),
  commitAdminPortableImport: vi.fn(),
}))

vi.mock('../../api/admin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/admin')>()),
  ...api,
}))

function stagedResponse(): PortableImportResponse {
  return {
    importId: 'import-1',
    expiresAt: '2026-09-20T01:00:00Z',
    artifactSha256: 'a'.repeat(64),
    identity: { code: 'demo-expert', kind: 'EXPERT', version: '1.0.0', revision: 1 },
    fields: {
      summary: {
        present: true,
        packageValue: '包摘要',
        currentValue: null,
        change: 'ADD',
        defaultAction: 'FILL_EMPTY',
        allowedActions: ['FILL_EMPTY', 'OVERWRITE'],
      },
      description: {
        present: true,
        packageValue: '详情',
        currentValue: '旧详情',
        change: 'UPDATE',
        defaultAction: 'FILL_EMPTY',
        allowedActions: ['FILL_EMPTY', 'OVERWRITE'],
      },
      taskTypes: {
        present: true,
        packageValue: ['写作'],
        currentValue: ['分析'],
        change: 'UPDATE',
        defaultAction: 'FILL_EMPTY',
        allowedActions: ['FILL_EMPTY', 'OVERWRITE', 'MERGE'],
      },
    },
    images: {
      icon: {
        present: true,
        packageValue: { sha256: 'b'.repeat(64) },
        currentValue: null,
        change: 'ADD',
        defaultAction: 'FILL_EMPTY',
        allowedActions: ['FILL_EMPTY', 'OVERWRITE'],
      },
    },
    resources: { count: 1, totalBytes: 3, resources: [] },
    warnings: ['operational_fields_not_imported:category,tags,sortWeight,status,publish'],
    status: 'SCANNED',
    targetSkillId: null,
    targetRowVersion: null,
  }
}

describe('SkillPortableImportPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.uploadAdminPortableImport.mockResolvedValue(stagedResponse())
    api.commitAdminPortableImport.mockResolvedValue({ status: 'COMMITTED' })
  })

  it('presents the Expert import as a compact, accessible drop card', async () => {
    const wrapper = mount(SkillPortableImportPanel, {
      props: { skill: null },
      global: { plugins: [ElementPlus] },
    })
    const panel = wrapper.get('[data-test="portable-import-panel"]')

    expect(panel.attributes('role')).toBe('region')
    expect(panel.attributes('aria-label')).toBe('导入 Expert YCPS 1.1')
    expect(wrapper.get('[data-test="portable-import-title"]').text()).toBe('导入 Expert YCPS 1.1')
    expect(wrapper.get('[data-test="portable-import-hint"]').text()).toContain('展示内容与三类图片')
    expect(wrapper.get('[data-test="portable-import-hint"]').text()).toContain('运营字段不导入')
    expect(wrapper.get('[data-test="portable-import-hint"]').text()).toContain('版本管理中设为线上')
    expect(wrapper.get('[data-test="portable-import-hint"]').text()).toContain('.ycps.zip 或 YCPS 文件夹')
    expect(wrapper.get('[data-test="portable-import-pick"]').classes()).toContain('portable-import-panel__main')
    expect(wrapper.get('[data-test="portable-import-folder-pick"]').text()).toContain('选择文件夹')
    expect(wrapper.get('[data-test="portable-import-folder"]').attributes()).toEqual(expect.objectContaining({
      directory: '',
      multiple: '',
      webkitdirectory: '',
    }))
    expect(wrapper.find('[data-test="portable-import-toggle"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('uses clear Chinese labels while preserving action enum values for commit', async () => {
    const wrapper = mount(SkillPortableImportPanel, {
      props: { skill: null },
      global: { plugins: [ElementPlus] },
    })
    const input = wrapper.get('[data-test="portable-import-file"]')
    const file = new File(['ycps'], 'demo.ycps.zip', { type: 'application/zip' })
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await flushPromises()

    expect(wrapper.text()).toContain('卡片摘要')
    expect(wrapper.text()).toContain('详情简介')
    const labels = wrapper.findAllComponents({ name: 'ElOption' })
      .map(option => option.props('label'))
    expect(labels).toEqual(expect.arrayContaining(['仅填空', '覆盖', '去重合并']))

    await wrapper.get('[data-test="portable-import-action-taskTypes"]').trigger('click')
    await flushPromises()
    const mergeOption = Array.from(document.querySelectorAll('.el-select-dropdown__item'))
      .find(option => option.textContent?.trim() === '去重合并') as HTMLElement | undefined
    expect(mergeOption).toBeDefined()
    mergeOption?.click()
    await flushPromises()
    await wrapper.get('[data-test="portable-import-commit"]').trigger('click')
    await flushPromises()
    expect(api.commitAdminPortableImport).toHaveBeenCalledWith(
      'import-1',
      expect.objectContaining({
        fields: expect.objectContaining({ taskTypes: 'MERGE' }),
        images: expect.objectContaining({ icon: 'FILL_EMPTY' }),
      }),
    )
    wrapper.unmount()
  })

  it('imports one YCPS package dropped from the desktop', async () => {
    const wrapper = mount(SkillPortableImportPanel, {
      props: { skill: null },
      global: { plugins: [ElementPlus] },
    })
    const zone = wrapper.get('[data-test="portable-import-panel"]')
    const file = new File(['ycps'], 'demo.ycps.zip', { type: 'application/zip' })
    const transfer = { files: [file], dropEffect: '' }

    const dragOver = new Event('dragover', { bubbles: true, cancelable: true })
    Object.defineProperty(dragOver, 'dataTransfer', { value: transfer })
    zone.element.dispatchEvent(dragOver)
    expect(dragOver.defaultPrevented).toBe(true)
    expect(transfer.dropEffect).toBe('copy')

    const drop = new Event('drop', { bubbles: true, cancelable: true })
    Object.defineProperty(drop, 'dataTransfer', { value: transfer })
    zone.element.dispatchEvent(drop)
    await flushPromises()

    expect(drop.defaultPrevented).toBe(true)
    expect(api.uploadAdminPortableImport).toHaveBeenCalledTimes(1)
    expect(api.uploadAdminPortableImport).toHaveBeenCalledWith(file)
    expect(wrapper.text()).toContain('demo-expert')
    wrapper.unmount()
  })

  it('packs a selected YCPS Expert folder before running the same inspection', async () => {
    const wrapper = mount(SkillPortableImportPanel, {
      props: { skill: null },
      global: { plugins: [ElementPlus] },
    })
    const plugin = new File(['{"schemaVersion":"1.1.0"}'], 'plugin.json', { type: 'application/json' })
    const skill = new File(['# Demo'], 'SKILL.md', { type: 'text/markdown' })
    Object.defineProperty(plugin, 'webkitRelativePath', { value: 'demo-expert/plugin.json' })
    Object.defineProperty(skill, 'webkitRelativePath', { value: 'demo-expert/skills/demo-expert/SKILL.md' })
    const input = wrapper.get('[data-test="portable-import-folder"]')
    Object.defineProperty(input.element, 'files', { value: [plugin, skill] })

    await input.trigger('change')
    await vi.waitFor(() => expect(api.uploadAdminPortableImport).toHaveBeenCalledTimes(1))

    const uploaded = api.uploadAdminPortableImport.mock.calls[0]?.[0] as File
    expect(uploaded.name).toBe('demo-expert.ycps.zip')
    expect(uploaded.type).toBe('application/zip')
    expect(uploaded.size).toBeGreaterThan(plugin.size + skill.size)
    expect(wrapper.text()).toContain('demo-expert')
    wrapper.unmount()
  })

  it('recursively packs one YCPS Expert folder dropped from the desktop', async () => {
    const wrapper = mount(SkillPortableImportPanel, {
      props: { skill: null },
      global: { plugins: [ElementPlus] },
    })
    const plugin = new File(['{"schemaVersion":"1.1.0"}'], 'plugin.json', { type: 'application/json' })
    const pluginEntry = {
      name: 'plugin.json', isFile: true, isDirectory: false,
      file: (resolve: (file: File) => void) => resolve(plugin),
    }
    let readCount = 0
    const directory = {
      name: 'demo-expert', isFile: false, isDirectory: true,
      createReader: () => ({
        readEntries: (resolve: (entries: unknown[]) => void) => {
          resolve(readCount++ === 0 ? [pluginEntry] : [])
        },
      }),
    }
    const item = {
      kind: 'file',
      getAsFile: () => null,
      webkitGetAsEntry: () => directory,
    }
    const drop = new Event('drop', { bubbles: true, cancelable: true })
    Object.defineProperty(drop, 'dataTransfer', {
      value: { items: [item], files: [], dropEffect: '' },
    })

    wrapper.get('[data-test="portable-import-panel"]').element.dispatchEvent(drop)
    await vi.waitFor(() => expect(api.uploadAdminPortableImport).toHaveBeenCalledTimes(1))

    const uploaded = api.uploadAdminPortableImport.mock.calls[0]?.[0] as File
    expect(drop.defaultPrevented).toBe(true)
    expect(uploaded.name).toBe('demo-expert.ycps.zip')
    expect(uploaded.type).toBe('application/zip')
    expect(wrapper.text()).toContain('demo-expert')
    wrapper.unmount()
  })

  it.each([
    'demo-expert-1.0.0.ycps(1).zip',
    'demo-expert-1.0.0.ycps (1).zip',
    'demo-expert-1.0.0 (1).ycps.zip',
  ])('restores browser-renamed Expert package %s before inspection', async (name) => {
    const wrapper = mount(SkillPortableImportPanel, {
      props: { skill: null },
      global: { plugins: [ElementPlus] },
    })
    const input = wrapper.get('[data-test="portable-import-file"]')
    const file = new File(['ycps'], name, { type: 'application/zip' })
    Object.defineProperty(input.element, 'files', { value: [file] })

    await input.trigger('change')
    await flushPromises()

    const uploaded = api.uploadAdminPortableImport.mock.calls[0]?.[0] as File
    expect(uploaded.name).toBe('demo-expert-1.0.0.ycps.zip')
    expect(uploaded.size).toBe(file.size)
    wrapper.unmount()
  })

  it('shows the precise gateway validation message instead of hiding every failure', async () => {
    api.uploadAdminPortableImport.mockRejectedValue({
      response: { status: 422, data: { code: 'skill_portable_import_profile_required' } },
    })
    const wrapper = mount(SkillPortableImportPanel, {
      props: { skill: null },
      global: { plugins: [ElementPlus] },
    })
    const input = wrapper.get('[data-test="portable-import-file"]')
    Object.defineProperty(input.element, 'files', {
      value: [new File(['ycps'], 'demo-expert-1.0.0.ycps.zip')],
    })

    await input.trigger('change')
    await flushPromises()

    expect(wrapper.text()).toContain('仅支持 YCPS 1.1 Expert 包')
    wrapper.unmount()
  })

  it('advertises and highlights the desktop drop target', async () => {
    const wrapper = mount(SkillPortableImportPanel, {
      props: { skill: null },
      global: { plugins: [ElementPlus] },
    })
    const zone = wrapper.get('[data-test="portable-import-panel"]')
    expect(wrapper.get('[data-test="portable-import-hint"]').text()).toContain('拖入此框')

    const enter = new Event('dragenter', { bubbles: true, cancelable: true })
    Object.defineProperty(enter, 'dataTransfer', { value: { files: [], dropEffect: '' } })
    zone.element.dispatchEvent(enter)
    await flushPromises()

    expect(enter.defaultPrevented).toBe(true)
    expect(zone.classes()).toContain('portable-import-panel--dragging')
    expect(wrapper.get('[data-test="portable-import-drop-hint"]').text()).toContain('松开')

    const leave = new Event('dragleave', { bubbles: true, cancelable: true })
    zone.element.dispatchEvent(leave)
    await flushPromises()

    expect(zone.classes()).not.toContain('portable-import-panel--dragging')
    expect(wrapper.find('[data-test="portable-import-drop-hint"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('rejects dropping more than one package at a time', async () => {
    const wrapper = mount(SkillPortableImportPanel, {
      props: { skill: null },
      global: { plugins: [ElementPlus] },
    })
    const files = [
      new File(['one'], 'one.ycps.zip', { type: 'application/zip' }),
      new File(['two'], 'two.ycps.zip', { type: 'application/zip' }),
    ]
    const drop = new Event('drop', { bubbles: true, cancelable: true })
    Object.defineProperty(drop, 'dataTransfer', { value: { files, dropEffect: '' } })
    wrapper.get('[data-test="portable-import-panel"]').element.dispatchEvent(drop)
    await flushPromises()

    expect(api.uploadAdminPortableImport).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('一次只能导入一个')
    wrapper.unmount()
  })
})
