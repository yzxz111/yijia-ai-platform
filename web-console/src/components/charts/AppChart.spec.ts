import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const echarts = vi.hoisted(() => ({
  dispose: vi.fn(),
  init: vi.fn(),
  resize: vi.fn(),
  setOption: vi.fn(),
  use: vi.fn(),
}))
const components = vi.hoisted(() => ({ legacyGridContainLabel: { name: 'legacy-grid-contain-label' } }))

vi.mock('echarts/core', () => ({ init: echarts.init, use: echarts.use }))
vi.mock('echarts/charts', () => ({ BarChart: {}, LineChart: {}, PieChart: {} }))
vi.mock('echarts/components', () => ({
  AriaComponent: {},
  GridComponent: {},
  LegendComponent: {},
  TooltipComponent: {},
}))
vi.mock('echarts/features', () => ({ LegacyGridContainLabel: components.legacyGridContainLabel }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

import AppChart from './AppChart.vue'

describe('AppChart', () => {
  let resizeCallback: ResizeObserverCallback
  let reducedMotionListener: ((event: MediaQueryListEvent) => void) | undefined
  let reducedMotionMatches = false
  const observe = vi.fn()
  const disconnect = vi.fn()
  const removeMotionListener = vi.fn()

  beforeEach(() => {
    reducedMotionListener = undefined
    reducedMotionMatches = false
    observe.mockClear()
    disconnect.mockClear()
    removeMotionListener.mockClear()
    echarts.dispose.mockClear()
    echarts.init.mockClear()
    echarts.resize.mockClear()
    echarts.setOption.mockClear()
    echarts.init.mockReturnValue({
      dispose: echarts.dispose,
      resize: echarts.resize,
      setOption: echarts.setOption,
    })
    vi.stubGlobal('ResizeObserver', class {
      constructor(callback: ResizeObserverCallback) {
        resizeCallback = callback
      }

      observe = observe
      disconnect = disconnect
    })
    vi.stubGlobal('matchMedia', vi.fn(() => ({
      matches: reducedMotionMatches,
      media: '(prefers-reduced-motion: reduce)',
      onchange: null,
      addEventListener: (_type: 'change', listener: (event: MediaQueryListEvent) => void) => {
        reducedMotionListener = listener
      },
      removeEventListener: removeMotionListener,
      dispatchEvent: vi.fn(),
    })))
  })

  it('caps canvas motion and disables it when reduced motion is requested', async () => {
    const wrapper = mount(AppChart, {
      props: {
        ariaLabel: 'API 调用趋势',
        option: { series: [{ data: [12, 18] }] },
      },
    })

    expect(echarts.setOption).toHaveBeenLastCalledWith(
      expect.objectContaining({
        animation: true,
        animationDuration: 200,
        animationDurationUpdate: 200,
      }),
      { notMerge: true },
    )

    reducedMotionMatches = true
    reducedMotionListener?.({ matches: true } as MediaQueryListEvent)
    await flushPromises()

    expect(echarts.setOption).toHaveBeenLastCalledWith(
      expect.objectContaining({
        animation: false,
        animationDuration: 0,
        animationDurationUpdate: 0,
      }),
      { notMerge: true },
    )

    wrapper.unmount()
    expect(removeMotionListener).toHaveBeenCalledOnce()
  })

  it('owns one accessible, stable chart instance through updates and resize', async () => {
    const wrapper = mount(AppChart, {
      props: {
        ariaLabel: '最近七天 API 调用趋势',
        height: '280px',
        option: { series: [{ data: [12, 18] }] },
      },
    })

    expect(echarts.init).toHaveBeenCalledOnce()
    expect(echarts.use).toHaveBeenCalledWith(expect.arrayContaining([
      components.legacyGridContainLabel,
    ]))
    expect(echarts.setOption).toHaveBeenLastCalledWith(
      {
        animation: true,
        animationDuration: 200,
        animationDurationUpdate: 200,
        series: [{ data: [12, 18] }],
      },
      { notMerge: true },
    )
    expect(wrapper.attributes('role')).toBe('img')
    expect(wrapper.attributes('aria-label')).toBe('最近七天 API 调用趋势')
    expect(wrapper.attributes('style')).toContain('height: 280px')
    expect(observe).toHaveBeenCalledOnce()

    await wrapper.setProps({ option: { series: [{ data: [21, 34] }] } })
    await flushPromises()
    expect(echarts.setOption).toHaveBeenLastCalledWith(
      {
        animation: true,
        animationDuration: 200,
        animationDurationUpdate: 200,
        series: [{ data: [21, 34] }],
      },
      { notMerge: true },
    )

    resizeCallback([], {} as ResizeObserver)
    expect(echarts.resize).toHaveBeenCalledOnce()

    wrapper.unmount()
    expect(disconnect).toHaveBeenCalledOnce()
    expect(echarts.dispose).toHaveBeenCalledOnce()
  })
})
