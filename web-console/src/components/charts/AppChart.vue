<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import {
  AriaComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from 'echarts/components'
import { LegacyGridContainLabel } from 'echarts/features'
import { init, use, type ECharts, type EChartsCoreOption } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'

use([
  AriaComponent,
  BarChart,
  CanvasRenderer,
  GridComponent,
  LegacyGridContainLabel,
  LegendComponent,
  LineChart,
  PieChart,
  TooltipComponent,
])

const props = withDefaults(defineProps<{
  option: EChartsCoreOption
  height?: string
  ariaLabel: string
}>(), {
  height: '320px',
})

const container = ref<HTMLDivElement | null>(null)
let chart: ECharts | null = null
let resizeObserver: ResizeObserver | null = null
let themeObserver: MutationObserver | null = null
let reducedMotionQuery: MediaQueryList | null = null
let reduceMotion = false

function optionWithMotion(option: EChartsCoreOption): EChartsCoreOption {
  const duration = reduceMotion ? 0 : 200
  return {
    ...option,
    animation: !reduceMotion,
    animationDuration: duration,
    animationDurationUpdate: duration,
  }
}

function setChartOption(option: EChartsCoreOption) {
  chart?.setOption(optionWithMotion(option), { notMerge: true })
}

// Canvas does not inherit CSS: refresh presentation when the page theme changes.
function refreshChartTheme() {
  const style = getComputedStyle(document.documentElement)
  const foreground = style.getPropertyValue('--app-muted-strong').trim()
  const border = style.getPropertyValue('--app-border').trim()
  const primary = style.getPropertyValue('--app-primary').trim()
  if (!foreground || !border || !primary) return

  const axisTheme = (value: unknown): unknown => {
    if (!value || typeof value !== 'object') return value
    const axis = value as Record<string, unknown>
    const split = (axis.splitLine || {}) as Record<string, unknown>
    return {
      ...axis,
      axisLabel: { ...(axis.axisLabel as object), color: foreground },
      nameTextStyle: { ...(axis.nameTextStyle as object), color: foreground },
      axisLine: { ...(axis.axisLine as object), lineStyle: { color: border } },
      splitLine: { ...split, lineStyle: { ...(split.lineStyle as object), color: border } },
    }
  }
  const themed = { ...props.option } as Record<string, unknown>
  for (const key of ['xAxis', 'yAxis']) {
    if (themed[key]) themed[key] = Array.isArray(themed[key])
      ? (themed[key] as unknown[]).map(axisTheme) : axisTheme(themed[key])
  }
  if (Array.isArray(themed.color)) {
    themed.color = themed.color.map((color, index) => index === 0 ? primary : color)
  }
  if (themed.legend) {
    const legend = themed.legend as Record<string, unknown>
    themed.legend = { ...legend, textStyle: { ...(legend.textStyle as object), color: foreground } }
  }
  setChartOption(themed as EChartsCoreOption)
}

function handleReducedMotion(event: MediaQueryListEvent) {
  reduceMotion = event.matches
  setChartOption(props.option)
}

watch(
  () => props.option,
  setChartOption,
  { deep: true },
)

onMounted(() => {
  if (!container.value) return
  reducedMotionQuery = window.matchMedia?.('(prefers-reduced-motion: reduce)') ?? null
  reduceMotion = reducedMotionQuery?.matches ?? false
  reducedMotionQuery?.addEventListener('change', handleReducedMotion)
  chart = init(container.value, undefined, { renderer: 'canvas' })
  setChartOption(props.option)
  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(container.value)
  themeObserver = new MutationObserver(refreshChartTheme)
  themeObserver.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['class', 'data-theme'],
  })
})

onBeforeUnmount(() => {
  reducedMotionQuery?.removeEventListener('change', handleReducedMotion)
  reducedMotionQuery = null
  resizeObserver?.disconnect()
  resizeObserver = null
  themeObserver?.disconnect()
  themeObserver = null
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div
    ref="container"
    class="app-chart"
    role="img"
    :aria-label="ariaLabel"
    :style="{ height }"
  />
</template>

<style scoped>
.app-chart {
  width: 100%;
  min-width: 0;
}
</style>
