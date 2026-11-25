import type { EChartsCoreOption } from 'echarts/core'

import type { MonthlyOperating, PackageDistribution } from '../../api/admin'
import type { DailyUsage, ModelUsage } from '../../api/gateway'
import { userPlanName } from '../../utils/planCopy'

// 低饱和企业级调色板：品牌青绿 · 靛蓝 · 中性灰 · 次要强调 · 弱化灰
// 与 tokens.css 设计令牌对齐；ECharts 画在 canvas 上读不到 CSS 变量，
// 因此这里在「构建 option 时」用 getComputedStyle 读积分，主题切换后图表同步换色。
function cssVar(name: string, fallback: string): string {
  if (typeof document === 'undefined') return fallback
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

function themePalette(): string[] {
  return [
    cssVar('--app-primary', '#087F6B'),
    '#6F81BA',
    cssVar('--app-muted', '#94A3B8'),
    cssVar('--app-accent', '#5969B3'),
    cssVar('--app-muted-strong', '#6E7788'),
  ]
}

const axisLabel = {
  get color() { return cssVar('--app-muted-strong', '#637083') },
  fontSize: 11,
}
const splitLine = {
  get lineStyle() { return { color: cssVar('--app-border', '#E4E8F0') } },
}
const legend = {
  top: 10,
  get textStyle() { return { color: cssVar('--app-muted-strong', '#455468'), fontSize: 12 } },
}
const tooltipStyle = {
  backgroundColor: '#172033',
  borderColor: '#172033',
  borderWidth: 0,
  padding: [8, 10],
  textStyle: { color: '#f8fafc', fontSize: 12 },
}
const currency = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' })
// 金额轴出现亚元级数值时，两位小数会把多个刻度压成同一个 ¥0.01，用四位小数区分
const currencyFine = new Intl.NumberFormat('zh-CN', {
  style: 'currency',
  currency: 'CNY',
  minimumFractionDigits: 4,
  maximumFractionDigits: 4,
})
const integer = new Intl.NumberFormat('zh-CN')

type TooltipParameter = {
  axisValueLabel?: string
  marker?: string
  seriesIndex?: number
  seriesName?: string
  value?: number | string
}

function monthDay(value: string) {
  return value.slice(5)
}

export function formatModelUsageLabel(
  model: Pick<ModelUsage, 'modelCode' | 'providerCode' | 'upstreamModel'>,
) {
  return `${model.upstreamModel || model.modelCode} · ${model.providerCode || '未路由'}`
}

function formatCnyMinor(value: number | string) {
  const yuan = Number(value) / 100
  if (!Number.isFinite(yuan)) return '—'
  // 整条轴都不到 1 元时统一用四位小数，避免同一轴出现 ¥0.01 与 ¥0.0080 混排
  if (Math.abs(yuan) < 1) return currencyFine.format(yuan)
  return currency.format(yuan)
}

function formatOperatingTooltip(params: unknown) {
  const items = Array.isArray(params) ? params as TooltipParameter[] : []
  const lines = items.map((item) => {
    const rawValue = Number(item.value ?? 0)
    const value = item.seriesIndex === 0 ? formatCnyMinor(rawValue) : integer.format(rawValue)
    return `${item.marker ?? ''}${item.seriesName ?? ''}: ${value}`
  })
  return [items[0]?.axisValueLabel, ...lines].filter(Boolean).join('<br/>')
}

export function buildUsageTrendOption(daily: DailyUsage[]): EChartsCoreOption {
  return {
    aria: { enabled: true, decal: { show: true } },
    color: themePalette(),
    tooltip: { trigger: 'axis', ...tooltipStyle },
    legend: { ...legend, data: ['请求数', '积分'] },
    graphic: [
      {
        type: 'text',
        top: 10,
        left: 12,
        style: { text: '请求数', fill: axisLabel.color, fontSize: axisLabel.fontSize },
      },
      {
        type: 'text',
        top: 10,
        right: 18,
        style: { text: '积分', fill: axisLabel.color, fontSize: axisLabel.fontSize },
      },
    ],
    grid: { left: 42, right: 58, top: 44, bottom: 22, containLabel: false },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: daily.map((item) => monthDay(item.date)),
      axisLabel: { ...axisLabel, hideOverlap: true, margin: 2 },
      axisLine: { lineStyle: { color: cssVar('--app-border-strong', '#cbd6e2') } },
    },
    yAxis: [
      { type: 'value', axisLabel, splitLine },
      { type: 'value', axisLabel, splitLine: { show: false } },
    ],
    series: [
      {
        name: '请求数',
        type: 'line',
        yAxisIndex: 0,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2 },
        itemStyle: { borderColor: cssVar('--app-surface', '#ffffff'), borderWidth: 2 },
        data: daily.map((item) => item.requestCount),
      },
      {
        name: '积分',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 2, type: 'dashed' },
        data: daily.map((item) => item.totalTokens),
      },
    ],
  }
}

export function buildModelDistributionOption(models: ModelUsage[]): EChartsCoreOption {
  const compactAxisLabel = {
    ...axisLabel,
    hideOverlap: true,
    formatter: (value: number) => Math.abs(value) >= 10_000
      ? `${Number((value / 10_000).toFixed(1))}万`
      : Math.abs(value) >= 1_000 ? `${Number((value / 1_000).toFixed(1))}千` : String(value),
  }
  return {
    aria: { enabled: true, decal: { show: true } },
    color: themePalette(),
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgb(99 112 131 / 8%)' } },
      ...tooltipStyle,
    },
    grid: { left: 16, right: 48, top: 32, bottom: 28, containLabel: false },
    xAxis: [
      { type: 'value', name: '积分', min: 0, splitNumber: 3, axisLabel: compactAxisLabel, splitLine },
      {
        type: 'value',
        name: '调用次数',
        position: 'top',
        min: 0,
        splitNumber: 3,
        minInterval: 1,
        axisLabel: compactAxisLabel,
        splitLine: { show: false },
      },
    ],
    yAxis: {
      type: 'category',
      data: models.map(formatModelUsageLabel),
      axisLabel: { ...axisLabel, width: 150, overflow: 'truncate' },
    },
    series: [
      {
        name: '积分',
        type: 'bar',
        xAxisIndex: 0,
        barMaxWidth: 20,
        itemStyle: { borderRadius: [0, 4, 4, 0] },
        data: models.map((item) => ({
          name: formatModelUsageLabel(item),
          value: item.totalTokens,
          requestCount: item.requestCount,
        })),
      },
      {
        name: '调用次数',
        type: 'bar',
        xAxisIndex: 1,
        barMaxWidth: 20,
        itemStyle: { borderRadius: [0, 4, 4, 0] },
        data: models.map((item) => item.requestCount),
      },
    ],
  }
}

export function buildOperatingTrendOption(monthly: MonthlyOperating[]): EChartsCoreOption {
  const colors = themePalette()
  return {
    aria: { enabled: true, decal: { show: true } },
    color: [colors[1], colors[0]],
    tooltip: { trigger: 'axis', formatter: formatOperatingTooltip, ...tooltipStyle },
    legend: { ...legend, data: ['净收', '新增用户'] },
    grid: { left: 56, right: 32, top: 40, bottom: 18, containLabel: false },
    xAxis: {
      type: 'category',
      data: monthly.map((item) => item.month.slice(0, 7)),
      axisLabel,
      axisLine: { lineStyle: { color: cssVar('--app-border-strong', '#cbd6e2') } },
    },
    yAxis: [
      { type: 'value', name: '金额', axisLabel: { ...axisLabel, formatter: formatCnyMinor }, splitLine },
      { type: 'value', name: '用户', minInterval: 1, axisLabel, splitLine: { show: false } },
    ],
    series: [
      {
        name: '净收',
        type: 'bar',
        yAxisIndex: 0,
        barMaxWidth: 30,
        itemStyle: { borderRadius: [4, 4, 0, 0] },
        data: monthly.map((item) => item.netAmount),
      },
      {
        name: '新增用户',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbolSize: 6,
        lineStyle: { width: 2 },
        itemStyle: { borderColor: cssVar('--app-surface', '#ffffff'), borderWidth: 2 },
        data: monthly.map((item) => item.newUsers),
      },
    ],
  }
}

export function buildPackageDistributionOption(packages: PackageDistribution[]): EChartsCoreOption {
  return {
    aria: { enabled: true, decal: { show: true } },
    color: themePalette(),
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgb(99 112 131 / 8%)' } },
      ...tooltipStyle,
    },
    grid: { left: 16, right: 48, top: 10, bottom: 18, containLabel: false },
    xAxis: { type: 'value', minInterval: 1, axisLabel, splitLine },
    yAxis: {
      type: 'category',
      data: packages.map((item) => userPlanName(item.packageCode, item.packageName)),
      axisLabel,
    },
    series: [{
      name: '活动套餐',
      type: 'bar',
      barMaxWidth: 28,
      itemStyle: { borderRadius: [0, 4, 4, 0] },
      data: packages.map((item) => ({
        name: userPlanName(item.packageCode, item.packageName),
        value: item.activeCount,
      })),
    }],
  }
}

// 近 7 天 API 调用量迷你趋势：纯净折线，无坐标轴标签，强调走势
export function buildRecentTrendOption(daily: DailyUsage[]): EChartsCoreOption {
  const recent = daily.slice(-7)
  const colors = themePalette()
  return {
    aria: { enabled: true, decal: { show: true } },
    color: [colors[0]],
    tooltip: {
      trigger: 'axis',
      ...tooltipStyle,
      formatter: (params: unknown) => {
        const item = Array.isArray(params) ? params[0] as TooltipParameter : undefined
        return `${item?.axisValueLabel ?? ''}<br/>${item?.marker ?? ''}调用 ${integer.format(Number(item?.value ?? 0))} 次`
      },
    },
    grid: { left: 4, right: 8, top: 8, bottom: 4, containLabel: false },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: recent.map((item) => monthDay(item.date)),
      axisLabel: { show: false },
      axisLine: { show: false },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'value',
      axisLabel: { show: false },
      splitLine: { show: false },
      axisLine: { show: false },
      axisTick: { show: false },
    },
    series: [{
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: 5,
      lineStyle: { width: 2 },
      itemStyle: { borderColor: cssVar('--app-surface', '#ffffff'), borderWidth: 2 },
      areaStyle: { color: cssVar('--app-primary-soft', '#EAF0FA'), opacity: 0.6 },
      data: recent.map((item) => item.requestCount),
    }],
  }
}
