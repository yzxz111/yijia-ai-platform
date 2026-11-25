import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import OperatingAlertsPanel from './OperatingAlertsPanel.vue'
import type { OperatingAlert } from '../../api/operatingReports'

function alert(overrides: Partial<OperatingAlert> & Pick<OperatingAlert, 'id' | 'status'>): OperatingAlert {
  return {
    type: 'LOW_MARGIN_PACKAGE',
    logicalModel: 'STARTER',
    provider: null,
    threshold: 0.4,
    observed: 0.37931,
    currency: 'CNY',
    firstObservedAt: '2026-09-01T00:00:00Z',
    lastObservedAt: '2026-09-01T00:00:00Z',
    ...overrides,
  }
}

function mountPanel(alerts: OperatingAlert[], status: OperatingAlert['status'] = 'RESOLVED') {
  return mount(OperatingAlertsPanel, {
    props: { alerts, total: alerts.length, page: 0, pageSize: 20, status },
    global: {
      directives: { loading: () => undefined },
      stubs: { 'el-pagination': { template: '<div />' } },
    },
  })
}

describe('operating alert resolution origin', () => {
  it('badges a monitor-cleared alert and says the margin recovered', () => {
    const wrapper = mountPanel([
      alert({ id: 'machine-recovered', status: 'RESOLVED', resolutionOrigin: 'MACHINE', resolutionReason: 'RECOVERED' }),
    ])

    const badge = wrapper.get('[data-test="alert-resolution-machine"]')
    expect(badge.text()).toContain('自动解除')
    expect(wrapper.get('[data-test="alert-resolution-reason"]').text()).toBe('已自动解除：毛利回到预警线以上')
    expect(wrapper.find('[data-test="alert-resolution-operator"]').exists()).toBe(false)
  })

  it('says the version was withdrawn when the monitor cleared an unpublished plan', () => {
    const wrapper = mountPanel([
      alert({ id: 'machine-withdrawn', status: 'RESOLVED', resolutionOrigin: 'MACHINE', resolutionReason: 'NO_LONGER_PUBLISHED' }),
    ])

    expect(wrapper.get('[data-test="alert-resolution-machine"]').text()).toContain('自动解除')
    expect(wrapper.get('[data-test="alert-resolution-reason"]').text()).toBe('已自动解除：该版本已下架')
  })

  it('badges a human resolution differently from a machine one', () => {
    const wrapper = mountPanel([
      alert({ id: 'operator', status: 'RESOLVED', resolutionOrigin: 'OPERATOR', resolutionReason: null }),
    ])

    const badge = wrapper.get('[data-test="alert-resolution-operator"]')
    expect(badge.text()).toContain('人工解除')
    expect(wrapper.get('[data-test="alert-resolution-reason"]').text()).toBe('已人工解除')
    expect(wrapper.find('[data-test="alert-resolution-machine"]').exists()).toBe(false)
  })

  it('shows both origins side by side in one list', () => {
    const wrapper = mountPanel([
      alert({ id: 'machine', status: 'RESOLVED', resolutionOrigin: 'MACHINE', resolutionReason: 'RECOVERED' }),
      alert({ id: 'operator', status: 'RESOLVED', resolutionOrigin: 'OPERATOR' }),
    ])

    expect(wrapper.findAll('[data-test="alert-resolution-machine"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-test="alert-resolution-operator"]')).toHaveLength(1)
  })

  it('marks an unresolved alert as not cleared', () => {
    const wrapper = mountPanel([
      alert({ id: 'open', status: 'OPEN', resolutionOrigin: null, resolutionReason: null }),
    ], 'OPEN')

    const cell = wrapper.get('[data-test="alert-resolution-unresolved"]')
    expect(cell.text()).toContain('未解除')
    // 未解除的告警不该出现"是谁解除的"说明
    expect(wrapper.find('[data-test="alert-resolution-reason"]').exists()).toBe(false)
  })

  it('renders a resolved alert without an origin as a dash instead of guessing', () => {
    const wrapper = mountPanel([
      alert({ id: 'legacy', status: 'RESOLVED' }),
    ])

    const cell = wrapper.get('[data-test="alert-resolution-unknown"]')
    expect(cell.text()).toContain('—')
    expect(cell.text()).not.toContain('人工解除')
    expect(cell.text()).not.toContain('自动解除')
  })

  it('explains the empty state for the selected status', () => {
    expect(mountPanel([], 'RESOLVED').get('.report-empty').text()).toContain('当前没有已解除的经营告警')
    expect(mountPanel([], 'OPEN').get('.report-empty').text()).toContain('当前没有待处理经营告警')
  })
})
