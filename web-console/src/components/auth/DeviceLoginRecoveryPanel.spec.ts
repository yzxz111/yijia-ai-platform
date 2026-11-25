import ElementPlus, { ElMessage } from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DeviceLoginRecoveryPanel from './DeviceLoginRecoveryPanel.vue'
import type { DeviceLoginRecovery } from '../../api/platform'

const complete = vi.hoisted(() => vi.fn())
vi.mock('../../api/platform', () => ({ completeDeviceLoginRecovery: complete }))

function recovery(): DeviceLoginRecovery {
  return {
    code: 'login_device_recovery_required', requestId: 'request', csrfToken: 'csrf',
    expiresAt: new Date(Date.now() + 300000).toISOString(), deviceLimit: 1,
    devices: [
      { id: 'old-device-1', createdAt: '2026-09-07T01:00:00Z', recentlyOnline: false },
      { id: 'old-device-2', createdAt: '2026-09-21T01:00:00Z', recentlyOnline: true },
    ],
  }
}

describe('verified device replacement', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('requires enough selected devices and emits a session only after the server succeeds', async () => {
    const challenge = recovery()
    const session = { accessToken: 'new-session', expiresIn: 900, user: { id: 'user' } }
    complete.mockResolvedValue(session)
    const view = mount(DeviceLoginRecoveryPanel, { props: { recovery: challenge }, global: { plugins: [ElementPlus] } })
    expect(view.get('[data-test=device-recovery-submit]').attributes('disabled')).toBeDefined()
    await view.findAll('input')[0].setValue(true)
    expect(view.get('[data-test=device-recovery-submit]').attributes('disabled')).toBeDefined()
    await view.findAll('input')[1].setValue(true)
    await view.get('form').trigger('submit')
    await flushPromises()
    expect(complete).toHaveBeenCalledWith(challenge, ['old-device-1', 'old-device-2'])
    expect(view.emitted('completed')).toEqual([[session]])
    expect(view.text()).toContain('休眠或断网')
    view.unmount()
  })

  it('returns expired verification to login without trying to evict a device', async () => {
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close() {} }))
    const view = mount(DeviceLoginRecoveryPanel, {
      props: { recovery: { ...recovery(), deviceLimit: 2, expiresAt: '2020-01-01T00:00:00Z' } },
      global: { plugins: [ElementPlus] },
    })
    await view.findAll('input')[0].setValue(true)
    await view.get('form').trigger('submit')
    expect(complete).not.toHaveBeenCalled()
    expect(view.emitted('cancel')).toHaveLength(1)
    view.unmount(); warning.mockRestore()
  })
})
