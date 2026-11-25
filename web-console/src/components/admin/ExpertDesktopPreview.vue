<script setup lang="ts">
import { computed } from 'vue'

import {
  createExpertCanvas,
  type ExpertCanvasNode,
  type ExpertCanvasTextRun,
  type ExpertEditorLocale,
  type ExpertWysiwygDraft,
  type ExpertWysiwygTable,
} from '../../utils/expertWysiwygDraft'

const props = withDefaults(defineProps<{
  modelValue: ExpertWysiwygDraft
  locale?: ExpertEditorLocale
  scale?: number
}>(), {
  locale: 'zh',
  scale: 46,
})

const currentPreview = computed(() => props.modelValue.preview[props.locale])
const currentCanvas = computed(() => props.modelValue.canvas[props.locale] ?? createExpertCanvas(props.modelValue, props.locale))
const currentTitle = computed(() => props.modelValue.title[props.locale])
const currentDescription = computed(() => props.modelValue.description[props.locale])
const currentUsageGuide = computed(() => props.modelValue.usageGuide[props.locale])
const currentPrompts = computed(() => props.modelValue.examplePrompts[props.locale])
const currentTaskTypes = computed(() => props.modelValue.taskTypes[props.locale])
const currentTable = computed(() => currentPreview.value.table)

const uiText = computed(() => props.locale === 'en' ? {
  previewAria: 'Desktop final result preview',
  title: 'Desktop final result',
  subtitle: 'Read-only preview using the same content, styles, and images as the desktop client.',
  live: 'Live',
  exampleImage: 'Feature image preview',
  replaceImage: 'Replace feature image',
  exampleInput: 'Example input',
  exampleRequest: 'Example request',
  exampleMaterial: 'Optional: example material',
  expertAvatar: 'Expert avatar',
  expertName: 'Expert name',
  outputExample: 'Example output',
  illustration: 'Introduction illustration',
  cloud: 'Cloud',
  description: 'Expert description',
  usageTitle: 'Before you start, tell it this',
  usageGuide: 'Usage guide',
  promptTitle: 'Start with an example',
  start: 'Start conversation',
  uninstall: 'Uninstall',
} : {
  previewAria: '桌面端最终效果预览',
  title: '桌面端最终效果',
  subtitle: '与桌面端使用同一份内容、样式和图片，实时预览只读。',
  live: '实时',
  exampleImage: '功能示例图预览',
  replaceImage: '替换功能示例图',
  exampleInput: '示例输入',
  exampleRequest: '示例请求',
  exampleMaterial: '可选：示例素材',
  expertAvatar: '专家头像',
  expertName: '专家名称',
  outputExample: '示例输出',
  illustration: '介绍插画',
  cloud: '云端',
  description: '专家简介',
  usageTitle: '开始前，告诉它这些',
  usageGuide: '使用说明',
  promptTitle: '从一个例子开始',
  start: '开始对话',
  uninstall: '卸载',
})

type PreviewLine = { text: string; tone?: string; field?: string }
type LegacyPipeTable = {
  before: string[]
  headers: string[]
  rows: Array<Array<string | number>>
  after: string[]
  start: number
}

function splitLegacyPipeTable(paragraphs: string[]): LegacyPipeTable | undefined {
  const delimiterFor = (item: string): string | undefined => {
    if (item.split('｜').length >= 3) return '｜'
    if (item.split('|').length >= 3) return '|'
    return undefined
  }
  const start = paragraphs.findIndex(item => delimiterFor(item) !== undefined)
  if (start < 0) return undefined
  const delimiter = delimiterFor(paragraphs[start])
  if (!delimiter) return undefined
  let end = start
  while (end < paragraphs.length && delimiterFor(paragraphs[end]) === delimiter) end += 1
  if (end - start < 2) return undefined
  const headers = paragraphs[start].split(delimiter).map(item => item.trim())
  const rows = paragraphs.slice(start + 1, end).map(item => item.split(delimiter).map(cell => cell.trim()))
    .filter(row => row.length === headers.length)
  if (!headers.length || !rows.length) return undefined
  return {
    before: paragraphs.slice(0, start),
    headers,
    rows,
    after: paragraphs.slice(end),
    start,
  }
}

const legacyTable = computed(() => currentTable.value ? undefined : splitLegacyPipeTable(currentPreview.value.outputParagraphs))
const displayTable = computed<ExpertWysiwygTable | undefined>(() => {
  if (currentTable.value) return currentTable.value
  if (!legacyTable.value) return undefined
  const count = legacyTable.value.headers.length
  const base = Math.floor(100 / count)
  const remainder = 100 - base * count
  return {
    headers: legacyTable.value.headers,
    columnWidths: legacyTable.value.headers.map((_, index) => base + (index < remainder ? 1 : 0)),
    rows: legacyTable.value.rows,
  }
})

const headingParts = computed(() => {
  const heading = currentPreview.value.heading
  if (currentPreview.value.headingPrefix) {
    return { prefix: currentPreview.value.headingPrefix, body: heading }
  }
  const isEnglish = /[A-Za-z]/u.test(`${heading}${currentPreview.value.request}`)
    && !/[\u4e00-\u9fff]/u.test(`${heading}${currentPreview.value.request}`)
  const match = heading.match(isEnglish ? /^(One sentence)(.*)$/u : /^(一句话)(.*)$/u)
  return match ? { prefix: match[1], body: match[2] } : { prefix: '', body: heading }
})

const legacyBeforeLines = computed<PreviewLine[]>(() => legacyTable.value
  ? legacyTable.value.before.map((text, index) => ({ text, field: `preview.outputParagraphs.${index}` }))
  : [])

function deriveSections(lines: PreviewLine[]): { plain: PreviewLine[]; sections: Array<{ title: string; paragraphs: PreviewLine[] }> } {
  const plain: PreviewLine[] = []
  const sections: Array<{ title: string; paragraphs: PreviewLine[] }> = []
  let current: { title: string; paragraphs: PreviewLine[] } | undefined
  lines.forEach(line => {
    const documentIssue = /^(\d{2}\s+[^｜|]+?)(?:[｜|](.+))?$/u.exec(line.text.trim())
    const isSectionTitle = Boolean(documentIssue)
      || /^\d{2}\s+/u.test(line.text)
      || line.text.trim() === 'Product claim check'
      || line.text.trim() === '卖点核对'
    if (isSectionTitle) {
      const title = documentIssue?.[1]?.trim() ?? line.text.trim()
      current = { title, paragraphs: [] }
      sections.push(current)
      if (documentIssue?.[2]?.trim()) current.paragraphs.push({ text: documentIssue[2].trim(), tone: 'muted' })
    } else if (current) {
      current.paragraphs.push(line)
    } else {
      plain.push(line)
    }
  })
  return { plain, sections }
}

const bodySourceLines = computed<PreviewLine[]>(() => {
  const preview = currentPreview.value
  if (preview.blocks !== undefined) return preview.blocks.map(block => ({ text: block.text, tone: block.tone } as PreviewLine))
  const source = legacyTable.value ? legacyTable.value.after : preview.outputParagraphs
  const offset = legacyTable.value ? legacyTable.value.start + legacyTable.value.headers.length * 0 + 1 + legacyTable.value.rows.length : 0
  return source.map((text, index) => ({
    text,
    field: `preview.outputParagraphs.${offset + index}`,
  }))
})

const derivedContent = computed(() => deriveSections(bodySourceLines.value))
const displayLines = computed(() => currentPreview.value.sections !== undefined
  ? bodySourceLines.value
  : derivedContent.value.plain)
const displaySections = computed(() => currentPreview.value.sections !== undefined
  ? currentPreview.value.sections.map(section => ({ title: section.title, paragraphs: section.blocks.map(block => ({ text: block.text, tone: block.tone } as PreviewLine)) }))
  : derivedContent.value.sections)
const visiblePrompts = computed(() => currentPrompts.value.slice(0, 2))

const exampleImageVisible = computed(() => {
  const asset = props.modelValue.media.exampleImage
  if (!asset?.url) return false
  const imageLocale = asset.locale ?? 'zh'
  return imageLocale === 'neutral' || imageLocale === props.locale
})

function nodeFor(field: string): ExpertCanvasNode | undefined {
  return currentCanvas.value.nodes.find(node => node.field === field)
}

function runsFor(field: string, value: string, prefix = ''): ExpertCanvasTextRun[] {
  if (!value) return [{ text: value }]
  const runs = nodeFor(field)?.runs
  if (!runs?.length) return [{ text: value }]
  const fullText = runs.map(run => run.text).join('')
  if (fullText === value) return runs
  if (!prefix || fullText !== `${prefix}${value}`) return [{ text: value }]
  let remainingPrefix = prefix.length
  return runs.flatMap(run => {
    if (remainingPrefix >= run.text.length) {
      remainingPrefix -= run.text.length
      return []
    }
    const text = run.text.slice(remainingPrefix)
    remainingPrefix = 0
    return text ? [{ ...run, text }] : []
  })
}

function runStyle(run: ExpertCanvasTextRun, field: string): Record<string, string> {
  const node = nodeFor(field)
  return {
    ...(run.color || node?.style?.color ? { color: run.color ?? node?.style?.color } : {}),
    ...(run.fontSize ? { fontSize: `${run.fontSize}px` } : {}),
    ...(run.fontWeight ? { fontWeight: String(run.fontWeight) } : {}),
    ...(run.italic ? { fontStyle: 'italic' } : {}),
  }
}

function headingPrefixStyle(): Record<string, string> {
  const run = nodeFor('preview.heading')?.runs?.[0]
  return run && headingParts.value.prefix && run.text.startsWith(headingParts.value.prefix)
    ? runStyle(run, 'preview.heading')
    : {}
}

function fieldStyle(field: string, fallback: Record<string, string> = {}, omitColor = false): Record<string, string> {
  const node = nodeFor(field)
  const run = node?.runs?.[0]
  return {
    ...fallback,
    ...(!omitColor && (run?.color || node?.style?.color) ? { color: run?.color ?? node?.style?.color } : {}),
    ...(run?.fontSize ? { fontSize: `${run.fontSize}px` } : {}),
    ...(run?.fontWeight ? { fontWeight: String(run.fontWeight) } : {}),
    ...(run?.italic ? { fontStyle: 'italic' } : {}),
  }
}

function tableStyle(table: ExpertWysiwygTable | undefined): Record<string, string> {
  return {
    ...(table?.style?.fontSize ? { fontSize: `${table.style.fontSize}px` } : {}),
    ...(table?.style?.color ? { color: table.style.color } : {}),
    ...(table?.style?.borderColor ? { borderColor: table.style.borderColor } : {}),
    ...(table?.style?.borderRadius !== undefined ? { borderRadius: `${table.style.borderRadius}px` } : {}),
  }
}

function tableHeaderStyle(table: ExpertWysiwygTable | undefined): Record<string, string> {
  return {
    ...(table?.style?.headerColor ? { color: table.style.headerColor } : {}),
    ...(table?.style?.headerBackgroundColor ? { backgroundColor: table.style.headerBackgroundColor } : {}),
    ...(table?.style?.borderColor ? { borderColor: table.style.borderColor } : {}),
    ...(table?.style?.cellPadding !== undefined ? { padding: `${table.style.cellPadding}px` } : {}),
    ...(table?.style?.rowHeight !== undefined ? { height: `${table.style.rowHeight}px` } : {}),
  }
}

function tableCellStyle(table: ExpertWysiwygTable | undefined): Record<string, string> {
  return {
    ...(table?.style?.borderColor ? { borderColor: table.style.borderColor } : {}),
    ...(table?.style?.cellPadding !== undefined ? { padding: `${table.style.cellPadding}px` } : {}),
    ...(table?.style?.rowHeight !== undefined ? { height: `${table.style.rowHeight}px` } : {}),
  }
}

function lineStyle(line: PreviewLine): Record<string, string> {
  if (line.tone === 'muted') return { color: '#6b7280' }
  if (line.tone === 'accent') return { color: '#1d4ed8', fontWeight: '600' }
  return {}
}
</script>

<template>
  <section class="expert-desktop-preview" data-test="expert-desktop-preview" :aria-label="uiText.previewAria">
    <header class="expert-desktop-preview__header">
      <div>
        <h3>{{ uiText.title }}</h3>
        <p>{{ uiText.subtitle }}</p>
      </div>
      <span class="expert-desktop-preview__live"><i aria-hidden="true" />{{ uiText.live }}</span>
    </header>
    <div class="expert-desktop-preview__viewport">
      <div
        class="expert-desktop-preview__stage"
        :style="{ width: `${1080 * scale / 100}px`, height: `${704 * scale / 100}px` }"
      >
        <article class="expert-desktop-preview__page" :style="{ transform: `scale(${scale / 100})` }">
          <section class="expert-desktop-preview__left">
            <h1 :style="fieldStyle('preview.heading', { fontSize: '28px', lineHeight: '1.3' }, true)">
              <span v-if="headingParts.prefix" class="expert-desktop-preview__heading-prefix" :style="headingPrefixStyle()">{{ headingParts.prefix }}</span>
              <template v-for="(run, index) in runsFor('preview.heading', headingParts.body, headingParts.prefix)" :key="`heading-${index}`">
                <span :style="runStyle(run, 'preview.heading')">{{ run.text }}</span>
              </template>
            </h1>
            <p v-if="currentPreview.scene" class="expert-desktop-preview__scene" :style="fieldStyle('preview.scene')">
              <template v-for="(run, index) in runsFor('preview.scene', currentPreview.scene)" :key="`scene-${index}`">
                <span :style="runStyle(run, 'preview.scene')">{{ run.text }}</span>
              </template>
            </p>
            <div class="expert-desktop-preview__input-card" :class="{ 'expert-desktop-preview__input-card--image': exampleImageVisible }">
              <img v-if="exampleImageVisible" :src="modelValue.media.exampleImage.url" :alt="uiText.exampleImage" />
              <template v-else>
                <span class="expert-desktop-preview__label">{{ uiText.exampleInput }}</span>
                <p :style="fieldStyle('preview.request')">
                  <template v-if="currentPreview.request">
                    <template v-for="(run, index) in runsFor('preview.request', currentPreview.request)" :key="`request-${index}`">
                      <span :style="runStyle(run, 'preview.request')">{{ run.text }}</span>
                    </template>
                  </template>
                  <span v-else class="expert-desktop-preview__placeholder">{{ uiText.exampleRequest }}</span>
                </p>
                <p v-if="currentPreview.material" :style="fieldStyle('preview.material')">
                  <template v-for="(run, index) in runsFor('preview.material', currentPreview.material)" :key="`material-${index}`">
                    <span :style="runStyle(run, 'preview.material')">{{ run.text }}</span>
                  </template>
                </p>
              </template>
            </div>
            <div class="expert-desktop-preview__author">
              <span class="expert-desktop-preview__avatar">
                <img v-if="modelValue.media.icon.url" :src="modelValue.media.icon.url" :alt="uiText.expertAvatar" />
                <span v-else>AI</span>
              </span>
              <strong :style="fieldStyle('title')">
                <template v-if="currentTitle">
                  <template v-for="(run, index) in runsFor('title', currentTitle)" :key="`author-title-${index}`"><span :style="runStyle(run, 'title')">{{ run.text }}</span></template>
                </template>
                <span v-else class="expert-desktop-preview__placeholder">{{ uiText.expertName }}</span>
              </strong>
            </div>
            <div class="expert-desktop-preview__output-card">
              <h2 :style="fieldStyle('preview.outputTitle')">
                <template v-if="currentPreview.outputTitle">
                  <template v-for="(run, index) in runsFor('preview.outputTitle', currentPreview.outputTitle)" :key="`output-title-${index}`"><span :style="runStyle(run, 'preview.outputTitle')">{{ run.text }}</span></template>
                </template>
                <span v-else class="expert-desktop-preview__placeholder">{{ uiText.outputExample }}</span>
              </h2>
              <p v-if="currentPreview.intro" class="expert-desktop-preview__output-line" :style="lineStyle({ text: currentPreview.intro.text, tone: currentPreview.intro.tone })">
                <template v-for="(run, index) in runsFor('preview.intro', currentPreview.intro.text)" :key="`intro-${index}`"><span :style="runStyle(run, 'preview.intro')">{{ run.text }}</span></template>
              </p>
              <p v-for="(line, index) in legacyBeforeLines" :key="`before-${index}`" class="expert-desktop-preview__output-line" :style="lineStyle(line)">
                <template v-for="(run, runIndex) in runsFor(line.field ?? '', line.text)" :key="`before-${index}-${runIndex}`"><span :style="runStyle(run, line.field ?? '')">{{ run.text }}</span></template>
              </p>
              <div v-if="displayTable" class="expert-desktop-preview__table-wrap">
                <table class="expert-desktop-preview__table" :style="tableStyle(displayTable)">
                  <colgroup>
                    <col v-for="(width, index) in displayTable.columnWidths" :key="`width-${index}`" :style="{ width: `${width}%` }" />
                  </colgroup>
                  <thead>
                    <tr>
                      <th v-for="(header, index) in displayTable.headers" :key="`header-${index}`" :style="tableHeaderStyle(displayTable)">{{ header }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, rowIndex) in displayTable.rows" :key="`row-${rowIndex}`" :class="{ 'is-total': displayTable.totalRow && rowIndex === displayTable.rows.length - 1 }">
                      <template v-for="(cell, cellIndex) in row" :key="`cell-${rowIndex}-${cellIndex}`">
                        <th v-if="cellIndex === 0" scope="row" class="expert-desktop-preview__table-row-header" :style="tableCellStyle(displayTable)">{{ cell }}</th>
                        <td v-else :style="tableCellStyle(displayTable)">{{ cell }}</td>
                      </template>
                    </tr>
                  </tbody>
                </table>
              </div>
              <p v-for="(line, index) in displayLines" :key="`paragraph-${index}`" class="expert-desktop-preview__output-line" :data-tone="line.tone ?? 'body'" :style="{ ...lineStyle(line), ...(line.field ? fieldStyle(line.field) : {}) }">
                <template v-for="(run, runIndex) in runsFor(line.field ?? '', line.text)" :key="`paragraph-${index}-${runIndex}`">
                  <span :style="runStyle(run, line.field ?? '')">{{ run.text }}</span>
                </template>
              </p>
              <section v-for="(section, sectionIndex) in displaySections" :key="`section-${sectionIndex}-${section.title}`" class="expert-desktop-preview__section">
                <h3>{{ section.title }}</h3>
                <p v-for="(line, lineIndex) in section.paragraphs" :key="`section-${sectionIndex}-${lineIndex}`" class="expert-desktop-preview__output-line" :data-tone="line.tone ?? 'body'" :style="{ ...lineStyle(line), ...(line.field ? fieldStyle(line.field) : {}) }">
                  <template v-for="(run, runIndex) in runsFor(line.field ?? '', line.text)" :key="`section-${sectionIndex}-${lineIndex}-${runIndex}`"><span :style="runStyle(run, line.field ?? '')">{{ run.text }}</span></template>
                </p>
              </section>
            </div>
          </section>

          <section class="expert-desktop-preview__right">
            <div class="expert-desktop-preview__illustration">
              <img v-if="modelValue.media.illustration.url" :src="modelValue.media.illustration.url" :alt="uiText.illustration" />
              <span v-else>{{ uiText.illustration }}</span>
            </div>
            <div class="expert-desktop-preview__identity">
              <span class="expert-desktop-preview__identity-avatar">
                <img v-if="modelValue.media.icon.url" :src="modelValue.media.icon.url" :alt="uiText.expertAvatar" />
                <span v-else>{{ uiText.expertAvatar }}</span>
              </span>
              <div>
                <h2 :style="fieldStyle('title')">
                  <template v-if="currentTitle">
                    <template v-for="(run, index) in runsFor('title', currentTitle)" :key="`detail-title-${index}`"><span :style="runStyle(run, 'title')">{{ run.text }}</span></template>
                  </template>
                  <span v-else class="expert-desktop-preview__placeholder">专家名称</span>
                </h2>
                <div class="expert-desktop-preview__tags">
                  <span class="expert-desktop-preview__tag expert-desktop-preview__tag--runtime">{{ uiText.cloud }}</span>
                  <span v-for="(tag, index) in currentTaskTypes" :key="`tag-${index}`" class="expert-desktop-preview__tag" :style="fieldStyle(`tag.${index}`)">
                    <template v-for="(run, runIndex) in runsFor(`tag.${index}`, tag)" :key="`tag-${index}-${runIndex}`"><span :style="runStyle(run, `tag.${index}`)">{{ run.text }}</span></template>
                  </span>
                </div>
              </div>
            </div>
            <p class="expert-desktop-preview__description" :style="fieldStyle('description')">
              <template v-if="currentDescription">
                <template v-for="(run, index) in runsFor('description', currentDescription)" :key="`description-${index}`"><span :style="runStyle(run, 'description')">{{ run.text }}</span></template>
              </template>
              <span v-else class="expert-desktop-preview__placeholder">{{ uiText.description }}</span>
            </p>
            <div class="expert-desktop-preview__scroll">
              <section>
                <h3>{{ uiText.usageTitle }}</h3>
                <p :style="fieldStyle('usageGuide')">
                  <template v-if="currentUsageGuide">
                    <template v-for="(run, index) in runsFor('usageGuide', currentUsageGuide)" :key="`usage-${index}`"><span :style="runStyle(run, 'usageGuide')">{{ run.text }}</span></template>
                  </template>
                  <span v-else class="expert-desktop-preview__placeholder">{{ uiText.usageGuide }}</span>
                </p>
              </section>
              <section>
                <h3>{{ uiText.promptTitle }}</h3>
                <div v-for="(prompt, index) in visiblePrompts" :key="`prompt-${index}`" class="expert-desktop-preview__prompt" :style="fieldStyle(`prompt.${index}`)">
                  <template v-for="(run, runIndex) in runsFor(`prompt.${index}`, prompt)" :key="`prompt-${index}-${runIndex}`"><span :style="runStyle(run, `prompt.${index}`)">{{ run.text }}</span></template>
                </div>
              </section>
            </div>
            <footer>
              <button type="button" disabled>{{ uiText.start }}</button>
              <button type="button" disabled>{{ uiText.uninstall }}</button>
            </footer>
          </section>
        </article>
      </div>
    </div>
  </section>
</template>

<style scoped>
.expert-desktop-preview {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 10px;
  border: 1px solid #e5e7eb;
  border-radius: 14px;
  background: #fff;
  padding: 12px;
}

.expert-desktop-preview__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.expert-desktop-preview__header h3 {
  margin: 0;
  color: #111827;
  font-size: 14px;
}

.expert-desktop-preview__header p {
  margin: 3px 0 0;
  color: #64748b;
  font-size: 11px;
  line-height: 16px;
}

.expert-desktop-preview__live {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border-radius: 999px;
  background: #ecfdf5;
  color: #047857;
  font-size: 11px;
  padding: 4px 7px;
  white-space: nowrap;
}

.expert-desktop-preview__live i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #10b981;
}

.expert-desktop-preview__viewport {
  min-width: 0;
  overflow: auto;
  border-radius: 10px;
  background: #f3f5f8;
  padding: 8px;
}

.expert-desktop-preview__stage {
  position: relative;
  margin: 0 auto;
}

.expert-desktop-preview__page {
  display: grid;
  grid-template-columns: 55.55% 44.45%;
  width: 1080px;
  height: 704px;
  overflow: hidden;
  border-radius: 15px;
  background: #fff;
  box-shadow: 0 10px 28px rgba(15, 23, 42, .14);
  transform-origin: top left;
}

.expert-desktop-preview__left {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
  background: #fafbfd;
  padding: 32px 32px 24px;
}

.expert-desktop-preview__left h1 {
  min-height: 36px;
  margin: 0;
  color: #252525;
  font-weight: 750;
}

.expert-desktop-preview__heading-prefix {
  color: #5b7db5;
  font-weight: 650;
}

.expert-desktop-preview__scene {
  min-height: 20px;
  margin: -4px 0 0;
  color: #64748b;
  font-size: 14px;
  line-height: 20px;
}

.expert-desktop-preview__input-card,
.expert-desktop-preview__output-card {
  min-width: 0;
  border: 1px solid #e2e8f0;
  border-radius: 11px;
  background: #fff;
  padding: 12px;
}

.expert-desktop-preview__input-card {
  display: flex;
  min-height: 116px;
  flex-direction: column;
  gap: 6px;
  background: #f2f3f5;
}

.expert-desktop-preview__input-card--image {
  flex: 0 0 140px;
  min-height: 0;
  border: 0;
  padding: 0;
  overflow: hidden;
}

.expert-desktop-preview__input-card--image img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: fill;
}

.expert-desktop-preview__label,
.expert-desktop-preview__output-card h2 {
  color: #475569;
  font-size: 13px;
  font-weight: 700;
}

.expert-desktop-preview__input-card p,
.expert-desktop-preview__output-card p {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.expert-desktop-preview__placeholder {
  color: #94a3b8;
}

.expert-desktop-preview__author {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 32px;
  color: #303238;
  font-size: 14px;
}

.expert-desktop-preview__avatar,
.expert-desktop-preview__identity-avatar {
  display: grid;
  overflow: hidden;
  place-items: center;
  border-radius: 50%;
  background: #e5e7eb;
  color: #64748b;
  font-size: 10px;
}

.expert-desktop-preview__avatar {
  width: 30px;
  height: 30px;
}

.expert-desktop-preview__avatar img,
.expert-desktop-preview__identity-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.expert-desktop-preview__output-card {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.expert-desktop-preview__output-card h2 {
  margin: 0 0 8px;
  color: #303238;
}

.expert-desktop-preview__table-wrap {
  margin: 2px 0 10px;
  overflow-x: auto;
}

.expert-desktop-preview__table {
  width: 100%;
  border: 1px solid #dfe3e8;
  border-collapse: separate;
  border-spacing: 0;
  overflow: hidden;
  table-layout: fixed;
}

.expert-desktop-preview__table th,
.expert-desktop-preview__table td {
  border-right: 1px solid #dfe3e8;
  border-bottom: 1px solid #dfe3e8;
  padding: 8px 10px;
  text-align: left;
  overflow-wrap: anywhere;
}

.expert-desktop-preview__table-row-header {
  background: transparent;
  color: inherit;
  font-weight: 400;
}

.expert-desktop-preview__table th:last-child,
.expert-desktop-preview__table td:last-child {
  border-right: 0;
}

.expert-desktop-preview__table tr:last-child td {
  border-bottom: 0;
}

.expert-desktop-preview__table th {
  background: #f0f0f0;
  color: #6b6b6b;
  font-weight: 700;
}

.expert-desktop-preview__table th.expert-desktop-preview__table-row-header {
  background: transparent;
  color: inherit;
  font-weight: 400;
}

.expert-desktop-preview__table tbody tr.is-total td {
  color: #262626;
  font-weight: 700;
}

.expert-desktop-preview__right {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
  padding: 0 24px 16px;
}

.expert-desktop-preview__illustration {
  display: grid;
  flex: 0 0 auto;
  width: calc(100% + 48px);
  height: 149px;
  margin: 0 -24px;
  place-items: center;
  overflow: hidden;
  background: linear-gradient(120deg, #e9eef5, #d7e0ea);
  color: #64748b;
  font-size: 13px;
}

.expert-desktop-preview__illustration img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.expert-desktop-preview__identity {
  display: flex;
  align-items: center;
  gap: 13px;
}

.expert-desktop-preview__identity-avatar {
  width: 64px;
  height: 64px;
  flex: 0 0 64px;
}

.expert-desktop-preview__identity h2 {
  margin: 0 0 7px;
  color: #1f2937;
  font-size: 24px;
  line-height: 34px;
}

.expert-desktop-preview__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.expert-desktop-preview__tag {
  border-radius: 999px;
  background: #e8eef8;
  color: #475569;
  font-size: 11px;
  padding: 4px 8px;
}

.expert-desktop-preview__tag--runtime {
  background: #dbeafe;
  color: #2563eb;
}

.expert-desktop-preview__description {
  margin: 0;
  color: #475569;
  font-size: 14px;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.expert-desktop-preview__scroll {
  min-height: 0;
  flex: 1;
  overflow: auto;
  padding-right: 3px;
}

.expert-desktop-preview__scroll section + section {
  margin-top: 12px;
}

.expert-desktop-preview__scroll h3 {
  margin: 0 0 5px;
  color: #111827;
  font-size: 17px;
}

.expert-desktop-preview__section {
  margin-top: 12px;
}

.expert-desktop-preview__section h3 {
  margin-bottom: 5px;
}

.expert-desktop-preview__scroll p {
  margin: 0;
  color: #475569;
  font-size: 14px;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.expert-desktop-preview__output-line {
  margin: 0;
}

.expert-desktop-preview__prompt {
  margin-top: 5px;
  border-radius: 7px;
  background: #f8fafc;
  color: #334155;
  font-size: 13px;
  padding: 8px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.expert-desktop-preview__right footer {
  display: flex;
  gap: 8px;
  margin-top: auto;
  padding-top: 5px;
}

.expert-desktop-preview__right footer button {
  min-height: 48px;
  flex: 1;
  border: 0;
  border-radius: 8px;
  background: #e5e7eb;
  color: #6b7280;
  cursor: not-allowed;
  font-size: 12px;
}

.expert-desktop-preview__right footer button:first-child {
  background: #2563eb;
  color: white;
}
</style>
