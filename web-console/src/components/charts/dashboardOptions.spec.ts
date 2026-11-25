import { describe, expect, it } from 'vitest'

import type { DailyUsage, ModelUsage } from '../../api/gateway'
import type { MonthlyOperating, PackageDistribution } from '../../api/admin'
import {
  buildModelDistributionOption,
  buildOperatingTrendOption,
  buildPackageDistributionOption,
  buildUsageTrendOption,
} from './dashboardOptions'

type Series = {
  data: Array<number | { name: string; value: number; requestCount?: number }>
}

describe('dashboard option factories', () => {
  it('maps daily request and token facts without changing their values', () => {
    const daily: DailyUsage[] = [
      { date: '2026-08-01', requestCount: 12, inputTokens: 60, outputTokens: 40, totalTokens: 100 },
      { date: '2026-08-02', requestCount: 18, inputTokens: 90, outputTokens: 30, totalTokens: 120 },
    ]

    const option = buildUsageTrendOption(daily)
    const series = option.series as Series[]

    expect((option.xAxis as { data: string[] }).data).toEqual(['08-01', '08-02'])
    expect(series[0].data).toEqual([12, 18])
    expect(series[1].data).toEqual([100, 120])
  })

  it('applies accessible tooltip contrast and deliberate line hierarchy', () => {
    const option = buildUsageTrendOption([
      { date: '2026-08-01', requestCount: 12, inputTokens: 60, outputTokens: 40, totalTokens: 100 },
    ])
    const tooltip = option.tooltip as {
      backgroundColor?: string
      borderColor?: string
      textStyle?: { color?: string }
    }
    const series = option.series as Array<Series & {
      areaStyle?: { opacity?: number }
      lineStyle?: { width?: number }
    }>

    expect(Array.from(option.color as string[], (color) => color.toLowerCase()))
      .toEqual(expect.arrayContaining(['#087f6b', '#6f81ba']))
    expect(tooltip).toMatchObject({
      backgroundColor: '#172033',
      borderColor: '#172033',
      textStyle: { color: '#f8fafc' },
    })
    expect(series[0].lineStyle).toMatchObject({ width: 2 })
    expect(series[1].lineStyle).toMatchObject({ width: 2 })
    expect(series[1].lineStyle).toMatchObject({ type: 'dashed' })
    expect(series[1].areaStyle).toBeUndefined()
  })

  it('builds an enumerable palette from the current theme', () => {
    const root = document.documentElement
    const previous = root.style.getPropertyValue('--app-primary')
    const before = (buildUsageTrendOption([]).color as string[])[0]
    const next = before?.toLowerCase() === '#123456' ? '#654321' : '#123456'
    root.style.setProperty('--app-primary', next)
    try {
      const colors = buildUsageTrendOption([]).color as string[]
      expect(colors[0]).toBe(next)
      expect(colors[0]).not.toBe(before)
      expect(Object.keys(colors)).toContain('0')
    } finally {
      if (previous) root.style.setProperty('--app-primary', previous)
      else root.style.removeProperty('--app-primary')
    }
  })

  it('places both axis titles and the legend on one aligned chart header row', () => {
    const option = buildUsageTrendOption([])
    const legend = option.legend as { top?: number }
    const graphic = option.graphic as Array<{
      top?: number
      left?: number
      right?: number
      style?: { text?: string }
    }>
    const axes = option.yAxis as Array<{ name?: string }>

    expect(legend.top).toBe(10)
    expect(graphic).toEqual(expect.arrayContaining([
      expect.objectContaining({ top: 10, left: 12, style: expect.objectContaining({ text: '请求数' }) }),
      expect.objectContaining({ top: 10, right: 18, style: expect.objectContaining({ text: '积分' }) }),
    ]))
    expect(axes.every((axis) => axis.name === undefined)).toBe(true)
  })

  it('preserves verified and unknown model identities in the distribution', () => {
    const models: ModelUsage[] = [
      { modelCode: 'text-basic', providerCode: 'bailian', upstreamModel: 'qwen-plus', requestCount: 4, totalTokens: 90 },
      { modelCode: 'deepseek-chat', providerCode: 'deepseek', upstreamModel: null, requestCount: 2, totalTokens: 60 },
    ]

    const option = buildModelDistributionOption(models)

    expect((option.yAxis as { data: string[] }).data).toEqual([
      'qwen-plus · bailian',
      'deepseek-chat · deepseek',
    ])
    expect((option.series as Series[])[0].data).toEqual([
      { name: 'qwen-plus · bailian', value: 90, requestCount: 4 },
      { name: 'deepseek-chat · deepseek', value: 60, requestCount: 2 },
    ])
    expect((option.series as Series[])[1].data).toEqual([4, 2])
  })

  it('uses a neutral provider fallback for pre-routing analytics failures', () => {
    const option = buildModelDistributionOption([{
      modelCode: 'text-basic',
      providerCode: null,
      upstreamModel: null,
      requestCount: 1,
      totalTokens: 12,
    }])

    expect((option.yAxis as { data: string[] }).data).toEqual(['text-basic · 未路由'])
    expect((option.series as Series[])[0].data).toEqual([
      { name: 'text-basic · 未路由', value: 12, requestCount: 1 },
    ])
  })

  it('maps monthly and package facts using integer minor currency units', () => {
    const monthly: MonthlyOperating[] = [
      { month: '2026-07-01', newUsers: 3, collectedAmount: 10_000, refundedAmount: 1_000, netAmount: 9_000 },
      { month: '2026-08-01', newUsers: 5, collectedAmount: 0, refundedAmount: 0, netAmount: 0 },
    ]
    const packages: PackageDistribution[] = [
      { packageCode: 'STARTER', packageName: '入门版', activeCount: 7 },
    ]

    const operating = buildOperatingTrendOption(monthly)
    const distribution = buildPackageDistributionOption(packages)

    expect((operating.series as Series[])[0].data).toEqual([9_000, 0])
    expect((operating.series as Series[])[1].data).toEqual([3, 5])
    expect((distribution.series as Series[])[0].data).toEqual([{ name: '入门版', value: 7 }])
  })

  it('formats zero and negative operating amounts as CNY without changing source values', () => {
    const option = buildOperatingTrendOption([
      { month: '2026-08-01', newUsers: 2, collectedAmount: 0, refundedAmount: 12_345, netAmount: -12_345 },
    ])
    const yAxis = option.yAxis as Array<{ axisLabel: { formatter?: (value: number) => string } }>
    const tooltip = option.tooltip as { formatter?: (params: unknown) => string }

    expect((option.series as Series[])[0].data).toEqual([-12_345])
    expect(yAxis[0].axisLabel.formatter?.(0)).toContain('0.00')
    expect(yAxis[0].axisLabel.formatter?.(-12_345)).toMatch(/[-−]¥123\.45/)
    expect(tooltip.formatter?.([{
      axisValueLabel: '2026-08',
      marker: '',
      seriesIndex: 0,
      seriesName: 'net receipts',
      value: -12_345,
    }])).toMatch(/[-−]¥123\.45/)
  })

  it('does not fabricate chart values for empty API arrays', () => {
    for (const option of [
      buildUsageTrendOption([]),
      buildModelDistributionOption([]),
      buildOperatingTrendOption([]),
      buildPackageDistributionOption([]),
    ]) {
      expect((option.series as Series[]).flatMap((series) => series.data)).toEqual([])
    }
  })
})
