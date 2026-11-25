import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  deleteFile: vi.fn(),
  listFiles: vi.fn(),
  uploadFile: vi.fn(),
}))

vi.mock('../../src/api/gateway', () => api)

import FilesView from '../../src/views/user/FilesView.vue'
import filesViewSource from '../../src/views/user/FilesView.vue?raw'

const processedFile = {
  id: 'file-processed',
  object: 'file' as const,
  bytes: 1024,
  created_at: 1_786_000_000,
  filename: 'report.pdf',
  purpose: 'assistants',
  status: 'processed' as const,
  expires_at: 1_786_386_400,
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, reject, resolve }
}

async function connect(wrapper: ReturnType<typeof mount>) {
  await wrapper.get('[data-test=file-api-key]').setValue('sk-yzh-memory-only')
  await wrapper.get('.connect-panel form').trigger('submit')
  await vi.waitFor(() => expect(api.listFiles).toHaveBeenCalledWith('sk-yzh-memory-only'))
}

describe('FilesView direct upload workflow', () => {
  beforeEach(() => {
    api.deleteFile.mockReset()
    api.listFiles.mockReset().mockResolvedValue({ object: 'list', data: [] })
    api.uploadFile.mockReset()
  })

  it('keeps the API key in memory and clears it when disconnected', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const wrapper = mount(FilesView, { global: { plugins: [ElementPlus] } })
    await connect(wrapper)

    expect(setItem).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test=file-api-key]').exists()).toBe(false)
    await wrapper.get('[data-test=file-disconnect]').trigger('click')
    expect(wrapper.get('[data-test=file-api-key]').element).toHaveProperty('value', '')
    setItem.mockRestore()
  })

  it('accepts the approved image, PDF, Office and text extensions', async () => {
    const wrapper = mount(FilesView, { global: { plugins: [ElementPlus] } })
    await connect(wrapper)

    expect(wrapper.get('[data-test=file-upload]').attributes('accept')).toBe(
      '.png,.jpg,.jpeg,.gif,.webp,.pdf,.doc,.docx,.xls,.xlsx,.csv,.txt,.md,.markdown',
    )
  })

  it('shows determinate phase progress and can cancel the active browser request', async () => {
    const pending = deferred<typeof processedFile>()
    let signal: AbortSignal | undefined
    api.uploadFile.mockImplementation((_key, _file, onProgress, requestSignal) => {
      signal = requestSignal
      onProgress({ phase: 'hashing', percent: 35 })
      onProgress({ phase: 'uploading', percent: 62 })
      return pending.promise
    })
    const wrapper = mount(FilesView, { global: { plugins: [ElementPlus] } })
    await connect(wrapper)

    const input = wrapper.get('[data-test=file-upload]')
    Object.defineProperty(input.element, 'files', { value: [new File(['x'], 'report.pdf')] })
    await input.trigger('change')

    expect(wrapper.get('[data-test=file-upload-phase]').text()).toContain('上传中')
    expect(wrapper.get('[data-test=file-upload-progress]').attributes('aria-valuenow')).toBe('62')
    await wrapper.get('[data-test=file-upload-cancel]').trigger('click')
    expect(signal?.aborted).toBe(true)
    pending.reject(Object.assign(new Error('cancelled'), { name: 'AbortError' }))
    await vi.waitFor(() => expect(wrapper.find('[data-test=file-upload-progress]').exists()).toBe(false))
  })

  it('recreates the upload once when the signed policy expires and inserts the processed record', async () => {
    api.uploadFile
      .mockRejectedValueOnce({ response: { data: { error: { code: 'upload_policy_expired' } } } })
      .mockImplementationOnce((_key, _file, onProgress) => {
        onProgress({ phase: 'scanning', percent: 80 })
        return Promise.resolve(processedFile)
      })
    const wrapper = mount(FilesView, { global: { plugins: [ElementPlus] } })
    await connect(wrapper)

    const input = wrapper.get('[data-test=file-upload]')
    Object.defineProperty(input.element, 'files', { value: [new File(['x'], 'report.pdf')] })
    await input.trigger('change')
    await vi.waitFor(() => expect(api.uploadFile).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(wrapper.find('[data-test=file-file-processed]').exists()).toBe(true))

    expect(wrapper.get('[data-test=file-file-processed]').text()).toContain('report.pdf')
    expect(wrapper.get('[data-test=file-status-file-processed]').text()).toContain('可使用')
    expect(wrapper.find('[data-test=file-upload-progress]').exists()).toBe(false)
  })

  it('shows a stable rejection message and permits deletion only in terminal states', async () => {
    api.listFiles.mockResolvedValue({
      object: 'list',
      data: [
        processedFile,
        { ...processedFile, id: 'file-scanning', filename: 'scan.docx', status: 'scanning' },
        { ...processedFile, id: 'file-rejected', filename: 'bad.pdf', status: 'rejected' },
        { ...processedFile, id: 'file-failed', filename: 'failed.txt', status: 'failed' },
      ],
    })
    api.uploadFile.mockResolvedValue({ ...processedFile, id: 'new-rejected', status: 'rejected' })
    const wrapper = mount(FilesView, { global: { plugins: [ElementPlus] } })
    await connect(wrapper)

    expect(wrapper.find('[data-test=file-delete-file-scanning]').exists()).toBe(false)
    expect(wrapper.find('[data-test=file-delete-file-processed]').exists()).toBe(true)
    expect(wrapper.find('[data-test=file-delete-file-rejected]').exists()).toBe(true)
    expect(wrapper.find('[data-test=file-delete-file-failed]').exists()).toBe(true)

    const input = wrapper.get('[data-test=file-upload]')
    Object.defineProperty(input.element, 'files', { value: [new File(['x'], 'bad.pdf')] })
    await input.trigger('change')
    await vi.waitFor(() => expect(document.body.textContent).toContain('文件未通过安全审核，无法使用。'))
    expect(wrapper.text()).not.toContain('文件未通过安全审核，无法使用。')
  })

  it('aborts and clears an active upload when unmounted', async () => {
    const pending = deferred<typeof processedFile>()
    let signal: AbortSignal | undefined
    api.uploadFile.mockImplementation((_key, _file, _onProgress, requestSignal) => {
      signal = requestSignal
      return pending.promise
    })
    const wrapper = mount(FilesView, { global: { plugins: [ElementPlus] } })
    await connect(wrapper)
    const input = wrapper.get('[data-test=file-upload]')
    Object.defineProperty(input.element, 'files', { value: [new File(['x'], 'report.pdf')] })
    await input.trigger('change')

    wrapper.unmount()
    expect(signal?.aborted).toBe(true)
    pending.reject(Object.assign(new Error('cancelled'), { name: 'AbortError' }))
  })

  it('does not restore a connection when listFiles resolves after unmount', async () => {
    const connection = deferred<{ object: 'list'; data: typeof processedFile[] }>()
    api.listFiles.mockReturnValue(connection.promise)
    const wrapper = mount(FilesView, { global: { plugins: [ElementPlus] } })
    await wrapper.get('[data-test=file-api-key]').setValue('sk-yzh-stale-secret')
    await wrapper.get('.connect-panel form').trigger('submit')
    await vi.waitFor(() => expect(api.listFiles).toHaveBeenCalledTimes(1))
    const state = (wrapper.vm as unknown as { $: { setupState: Record<string, unknown> } }).$.setupState

    wrapper.unmount()
    connection.resolve({ object: 'list', data: [processedFile] })
    await flushPromises()

    expect(state.sessionApiKey).toBeNull()
    expect(state.files).toEqual([])
    expect(state.connected).toBe(false)
  })

  it('shows keyboard focus without shifting the upload toolbar', () => {
    expect(filesViewSource).toMatch(/\.upload-button:focus-within\s*\{[^}]*(?:outline|box-shadow):/s)
  })

  it('wraps the mobile toolbar without forcing a 360px viewport overflow', () => {
    expect(filesViewSource).toMatch(/\.file-toolbar\s*\{[^}]*flex-wrap:\s*wrap/s)
    expect(filesViewSource).toMatch(/@media\s*\(max-width:\s*720px\)[\s\S]*\.file-toolbar\s*>\s*span\s*\{[^}]*min-width:\s*0/s)
  })
})
