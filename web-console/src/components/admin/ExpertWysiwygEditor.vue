<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type ComponentPublicInstance } from 'vue'

import {
  createExpertCanvas,
  EXPERT_WYSIWYG_LIMITS,
  type ExpertEditorLocale,
  type ExpertCanvas,
  type ExpertCanvasTextRun,
  type ExpertImageRole,
  type ExpertWysiwygDraft,
  type ExpertWysiwygTable,
  validateExpertWysiwygDraft,
} from '../../utils/expertWysiwygDraft'
import { downloadSkillImageBlob, imageExtensionForBlob } from '../../utils/skillDownload'
import ExpertDesktopPreview from './ExpertDesktopPreview.vue'
import ExpertRichTextField from './ExpertRichTextField.vue'

const props = withDefaults(defineProps<{
  modelValue: ExpertWysiwygDraft
  disabled?: boolean
  dirty?: boolean
}>(), { disabled: false })

const emit = defineEmits<{
  'update:modelValue': [value: ExpertWysiwygDraft]
  'asset-picked': [role: ExpertImageRole, file: File]
  'asset-cleared': [role: ExpertImageRole]
}>()

const locale = ref<ExpertEditorLocale>('zh')
const exampleTextVisible = ref<Record<ExpertEditorLocale, boolean>>({ zh: false, en: false })
const zoom = ref(100)
const tagDraft = ref('')
const imageInputRefs = new Map<ExpertImageRole, HTMLInputElement>()
const objectUrls = new Set<string>()
const activeField = ref<string | null>(null)
const activeSelection = ref({ start: 0, end: 0 })
const activeTable = ref(false)
const selectedFontSize = ref(16)
const selectedColor = ref('#1f2937')
const previewOpen = ref(true)
const previewMinimized = ref(false)
const floatingPreviewRef = ref<HTMLElement | null>(null)
const previewPosition = ref<{ left: number; top: number } | null>(null)
const previewDragging = ref(false)
const pendingRichRuns = new Map<string, ExpertCanvasTextRun[]>()
const objectUrlFiles = new Map<string, File>()

type PreviewDragSource = 'pointer' | 'mouse'

let previewDragSource: PreviewDragSource | null = null
let previewDragPointerId: number | null = null
let previewDragOffset = { x: 0, y: 0 }
let previewDragSize = { width: 0, height: 0 }
let previousBodyUserSelect = ''
let previousBodyCursor = ''

const currentTitle = computed(() => props.modelValue.title[locale.value])
const currentDescription = computed(() => props.modelValue.description[locale.value])
const currentUsageGuide = computed(() => props.modelValue.usageGuide[locale.value])
const currentTaskTypes = computed(() => props.modelValue.taskTypes[locale.value])
const currentPrompts = computed(() => props.modelValue.examplePrompts[locale.value])
const currentPreview = computed(() => props.modelValue.preview[locale.value])
/**
 * The feature image is available only for the language from which it was
 * uploaded. Legacy images without a marker are normalized to Chinese;
 * neutral images are available in both language tabs.
 */
const hasLocalizedExampleImage = computed(() => {
  const asset = props.modelValue.media.exampleImage
  if (!asset?.url) return false
  const imageLocale = asset.locale ?? 'zh'
  return imageLocale === 'neutral' || imageLocale === locale.value
})
const exampleImageVisible = computed(() => hasLocalizedExampleImage.value && !exampleTextVisible.value[locale.value])
const validationErrors = computed(() => validateExpertWysiwygDraft(props.modelValue))
const canApplyTextStyle = computed(() => activeTable.value
  || (activeField.value !== null && activeSelection.value.end > activeSelection.value.start))

const floatingPreviewStyle = computed<Record<string, string>>(() => {
  if (!previewPosition.value) return {} as Record<string, string>
  return {
    left: `${previewPosition.value.left}px`,
    top: `${previewPosition.value.top}px`,
    right: 'auto',
  }
})

function resetFormattingTarget() {
  activeField.value = null
  activeTable.value = false
  activeSelection.value = { start: 0, end: 0 }
  pendingRichRuns.clear()
}

function toggleExampleView() {
  if (!hasLocalizedExampleImage.value) return
  resetFormattingTarget()
  exampleTextVisible.value[locale.value] = !exampleTextVisible.value[locale.value]
}

function resetExampleView() {
  exampleTextVisible.value = { zh: false, en: false }
}

function selectLocale(nextLocale: ExpertEditorLocale) {
  if (locale.value === nextLocale) return
  resetFormattingTarget()
  tagDraft.value = ''
  locale.value = nextLocale
}

function clonePreview(value: ExpertWysiwygDraft['preview'][ExpertEditorLocale]): ExpertWysiwygDraft['preview'][ExpertEditorLocale] {
  return {
    ...value,
    outputParagraphs: [...value.outputParagraphs],
    ...(value.intro ? { intro: { ...value.intro } } : {}),
    ...(value.table ? {
      table: {
        ...value.table,
        headers: [...value.table.headers],
        columnWidths: [...value.table.columnWidths],
        rows: value.table.rows.map(row => [...row]),
        ...(value.table.style ? { style: { ...value.table.style } } : {}),
      },
    } : {}),
    ...(value.sections ? {
      sections: value.sections.map(section => ({
        ...section,
        blocks: section.blocks.map(block => ({ ...block })),
      })),
    } : {}),
    ...(value.blocks ? { blocks: value.blocks.map(block => ({ ...block })) } : {}),
  }
}

function cloneCanvas(value: ExpertCanvas | null): ExpertCanvas | null {
  if (!value) return null
  return {
    ...value,
    nodes: value.nodes.map(node => ({
      ...node,
      ...(node.runs ? { runs: node.runs.map(run => ({ ...run })) } : {}),
      ...(node.style ? { style: { ...node.style } } : {}),
      ...(node.table ? {
        table: {
          ...node.table,
          headers: [...node.table.headers],
          columnWidths: [...node.table.columnWidths],
          rows: node.table.rows.map(row => [...row]),
          ...(node.table.style ? { style: { ...node.table.style } } : {}),
        },
      } : {}),
    })),
  }
}

/**
 * Clone the live editor state without applying persistence normalization.
 * Empty rows are intentional while the operator is composing them; trimming
 * and filtering is deferred to validation/serialization at submit time.
 */
function cloneEditableDraft(value: ExpertWysiwygDraft): ExpertWysiwygDraft {
  return {
    title: { ...value.title },
    description: { ...value.description },
    usageGuide: { ...value.usageGuide },
    taskTypes: { zh: [...value.taskTypes.zh], en: [...value.taskTypes.en] },
    examplePrompts: { zh: [...value.examplePrompts.zh], en: [...value.examplePrompts.en] },
    preview: { zh: clonePreview(value.preview.zh), en: clonePreview(value.preview.en) },
    canvas: { zh: cloneCanvas(value.canvas.zh), en: cloneCanvas(value.canvas.en) },
    media: {
      illustration: { ...value.media.illustration },
      icon: { ...value.media.icon },
      exampleImage: { ...value.media.exampleImage },
    },
    advanced: {
      ...value.advanced,
      summary: { ...value.advanced.summary },
    },
  }
}

function updateDraft(mutator: (draft: ExpertWysiwygDraft) => void) {
  const next = cloneEditableDraft(props.modelValue)
  mutator(next)
  emit('update:modelValue', next)
}

function fieldValue(draft: ExpertWysiwygDraft, field: string, language: ExpertEditorLocale): string {
  if (field === 'title') return draft.title[language]
  if (field === 'description') return draft.description[language]
  if (field === 'usageGuide') return draft.usageGuide[language]
  if (field === 'preview.heading' || field === 'preview.scene' || field === 'preview.request'
    || field === 'preview.material' || field === 'preview.outputTitle' || field === 'preview.intro') {
    if (field === 'preview.intro') return draft.preview[language].intro?.text ?? ''
    return draft.preview[language][field.slice('preview.'.length) as keyof typeof draft.preview[ExpertEditorLocale]] as string
  }
  const prompt = /^prompt\.(\d+)$/.exec(field)
  if (prompt) return draft.examplePrompts[language][Number(prompt[1])] ?? ''
  const tag = /^tag\.(\d+)$/.exec(field)
  if (tag) return draft.taskTypes[language][Number(tag[1])] ?? ''
  const paragraph = /^preview\.outputParagraphs\.(\d+)$/.exec(field)
  if (paragraph) return draft.preview[language].outputParagraphs[Number(paragraph[1])] ?? ''
  return ''
}

function fieldRuns(field: string): ExpertCanvasTextRun[] {
  const text = fieldValue(props.modelValue, field, locale.value)
  const node = props.modelValue.canvas[locale.value]?.nodes.find(item => item.field === field)
  return normalizeRuns(node?.runs ?? [], text)
}

function rememberRichRuns(field: string, runs: ExpertCanvasTextRun[]) {
  pendingRichRuns.set(field, runs.map(run => ({ ...run })))
}

function rememberRichSelection(field: string, selection: { start: number; end: number }) {
  activeField.value = field
  activeTable.value = field.startsWith('preview.table')
  activeSelection.value = { start: selection.start, end: selection.end }
}

function updateRichField(field: string, value: string) {
  const runs = pendingRichRuns.get(field)
  pendingRichRuns.delete(field)
  updateDraft(next => {
    if (field === 'title') next.title[locale.value] = value
    else if (field === 'description') next.description[locale.value] = value
    else if (field === 'usageGuide') next.usageGuide[locale.value] = value
    else if (field.startsWith('preview.')) {
      const previewField = field.slice('preview.'.length)
      if (previewField === 'heading' || previewField === 'scene' || previewField === 'request'
        || previewField === 'material' || previewField === 'outputTitle') {
        next.preview[locale.value][previewField] = value
      } else if (previewField === 'intro') {
        next.preview[locale.value].intro = {
          ...(next.preview[locale.value].intro ?? {}),
          text: value,
        }
      } else {
        const paragraph = /^outputParagraphs\.(\d+)$/.exec(previewField)
        if (paragraph) next.preview[locale.value].outputParagraphs[Number(paragraph[1])] = value
      }
    } else {
      const prompt = /^prompt\.(\d+)$/.exec(field)
      const tag = /^tag\.(\d+)$/.exec(field)
      if (prompt) next.examplePrompts[locale.value][Number(prompt[1])] = value
      else if (tag) next.taskTypes[locale.value][Number(tag[1])] = value
    }
    const canvas = ensureCanvas(next, locale.value)
    let node = canvas.nodes.find(item => item.field === field)
    if (!node) {
      const fallback = createExpertCanvas(next, locale.value).nodes.find(item => item.field === field)
      if (fallback) {
        node = { ...fallback, runs: fallback.runs?.map(run => ({ ...run })) }
        canvas.nodes.push(node)
      }
    }
    if (node) node.runs = normalizeRuns(runs ?? node.runs ?? [], value)
  })
}

function ensureCanvas(draft: ExpertWysiwygDraft, language: ExpertEditorLocale): ExpertCanvas {
  const existing = draft.canvas[language]
  const result = existing ?? createExpertCanvas(draft, language)
  draft.canvas[language] = result
  return result
}

function normalizeRuns(runs: ExpertCanvasTextRun[], text: string): ExpertCanvasTextRun[] {
  if (!text) return []
  const result: ExpertCanvasTextRun[] = []
  let offset = 0
  for (const run of runs) {
    if (offset >= text.length) break
    const value = text.slice(offset, offset + run.text.length)
    if (value) result.push({ ...run, text: value })
    offset += run.text.length
  }
  if (offset < text.length) result.push({ text: text.slice(offset) })
  return result.length ? result : [{ text }]
}

function rememberSelection(field: string, event: Event) {
  activeField.value = field
  activeTable.value = field.startsWith('preview.table')
  const element = event.target as HTMLInputElement | HTMLTextAreaElement
  activeSelection.value = {
    start: element.selectionStart ?? 0,
    end: element.selectionEnd ?? element.value.length,
  }
}

function defaultTable(): ExpertWysiwygTable {
  const english = locale.value === 'en'
  return {
    headers: english ? ['Name', 'Expected', 'Actual', 'Difference'] : ['姓名', '应出勤', '实际出勤', '差额'],
    columnWidths: [27, 25, 25, 23],
    rows: english
      ? [['Lin Ran', '22', '21', '1'], ['Zhou Ning', '22', '22', '0'], ['Total', '44', '43', '1']]
      : [['林然', '22', '21', '1'], ['周宁', '22', '22', '0'], ['合计', '44', '43', '1']],
    totalRow: true,
    style: {
      fontSize: 14,
      color: '#303238',
      headerColor: '#6b6b6b',
      headerBackgroundColor: '#f0f0f0',
      borderColor: '#dfe3e8',
      borderRadius: 10,
      cellPadding: 8,
      rowHeight: 34,
    },
  }
}

function cloneTable(table: ExpertWysiwygTable): ExpertWysiwygTable {
  return {
    ...table,
    headers: [...table.headers],
    columnWidths: [...table.columnWidths],
    rows: table.rows.map(row => [...row]),
    ...(table.style ? { style: { ...table.style } } : {}),
  }
}

function distributeTableWidths(count: number): number[] {
  const safeCount = Math.max(1, count)
  const base = Math.floor(100 / safeCount)
  const remainder = 100 - base * safeCount
  return Array.from({ length: safeCount }, (_, index) => base + (index < remainder ? 1 : 0))
}

function syncTableCanvas(next: ExpertWysiwygDraft, table: ExpertWysiwygTable | null) {
  const existing = next.canvas[locale.value]
  if (!existing && !table) return
  const canvas = existing ?? createExpertCanvas(next, locale.value)
  canvas.nodes = canvas.nodes.filter(node => node.field !== 'preview.table')
  if (!table) {
    next.canvas[locale.value] = canvas
    return
  }
  const fallback = createExpertCanvas(next, locale.value).nodes.find(node => node.field === 'preview.table')
  if (!fallback) return
  const tableNode = {
    ...fallback,
    kind: 'table' as const,
    style: {
      ...(fallback.style ?? {}),
      color: table.style?.color ?? '#303238',
      backgroundColor: '#ffffff',
      borderColor: table.style?.borderColor ?? '#dfe3e8',
      borderRadius: table.style?.borderRadius ?? 10,
      overflow: 'auto' as const,
    },
    runs: [{
      text: table.headers.join(' | ') || 'Table',
      ...(table.style?.fontSize ? { fontSize: table.style.fontSize } : {}),
      ...(table.style?.headerColor ? { color: table.style.headerColor } : {}),
    }],
    table: cloneTable(table),
  }
  canvas.nodes.push(tableNode)
  next.canvas[locale.value] = canvas
}

function activateTable() {
  if (!props.modelValue.preview[locale.value].table) return
  activeTable.value = true
  activeField.value = null
  activeSelection.value = { start: 0, end: 0 }
}

function addTable() {
  if (props.disabled || currentPreview.value.table) return
  updateDraft(next => {
    const table = defaultTable()
    next.preview[locale.value].table = table
    syncTableCanvas(next, table)
  })
  activeTable.value = true
  activeField.value = null
  activeSelection.value = { start: 0, end: 0 }
}

function removeTable() {
  if (props.disabled || !currentPreview.value.table) return
  resetFormattingTarget()
  updateDraft(next => {
    next.preview[locale.value].table = undefined
    syncTableCanvas(next, null)
  })
}

function updateTable(mutator: (table: ExpertWysiwygTable) => void) {
  if (props.disabled || !currentPreview.value.table) return
  updateDraft(next => {
    const table = next.preview[locale.value].table
    if (!table) return
    mutator(table)
    syncTableCanvas(next, table)
  })
  activeTable.value = true
}

function updateTableHeader(index: number, value: string) {
  updateTable(table => { table.headers[index] = value })
}

function updateTableCell(rowIndex: number, columnIndex: number, value: string) {
  updateTable(table => { table.rows[rowIndex][columnIndex] = value })
}

function addTableRow() {
  if (props.disabled || !currentPreview.value.table
    || currentPreview.value.table.rows.length >= EXPERT_WYSIWYG_LIMITS.tableRowCount) return
  updateTable(table => { table.rows.push(Array.from({ length: table.headers.length }, () => '')) })
}

function removeTableRow() {
  updateTable(table => { if (table.rows.length) table.rows.pop() })
}

function addTableColumn() {
  updateTable(table => {
    table.headers.push('')
    table.rows.forEach(row => row.push(''))
    table.columnWidths = distributeTableWidths(table.headers.length)
  })
}

function removeTableColumn() {
  updateTable(table => {
    if (table.headers.length <= 1) return
    table.headers.pop()
    table.rows.forEach(row => row.pop())
    table.columnWidths = distributeTableWidths(table.headers.length)
  })
}

function toggleTableTotalRow(value: boolean) {
  updateTable(table => { table.totalRow = value })
}

function tableEditorStyle(table: ExpertWysiwygTable): Record<string, string> {
  return {
    ...(table.style?.fontSize ? { fontSize: `${table.style.fontSize}px` } : {}),
    ...(table.style?.color ? { color: table.style.color } : {}),
    ...(table.style?.borderColor ? { borderColor: table.style.borderColor } : {}),
    ...(table.style?.borderRadius !== undefined ? { borderRadius: `${table.style.borderRadius}px` } : {}),
  }
}

function tableHeaderEditorStyle(table: ExpertWysiwygTable): Record<string, string> {
  return {
    ...(table.style?.headerColor ? { color: table.style.headerColor } : {}),
    ...(table.style?.headerBackgroundColor ? { backgroundColor: table.style.headerBackgroundColor } : {}),
    ...(table.style?.borderColor ? { borderColor: table.style.borderColor } : {}),
    ...(table.style?.cellPadding !== undefined ? { padding: `${table.style.cellPadding}px` } : {}),
    ...(table.style?.rowHeight !== undefined ? { height: `${table.style.rowHeight}px` } : {}),
  }
}

function tableCellEditorStyle(table: ExpertWysiwygTable): Record<string, string> {
  return {
    ...(table.style?.borderColor ? { borderColor: table.style.borderColor } : {}),
    ...(table.style?.cellPadding !== undefined ? { padding: `${table.style.cellPadding}px` } : {}),
    ...(table.style?.rowHeight !== undefined ? { height: `${table.style.rowHeight}px` } : {}),
  }
}

function syncFieldCanvas(next: ExpertWysiwygDraft, field: string, value: string) {
  const canvas = ensureCanvas(next, locale.value)
  let node = canvas.nodes.find(item => item.field === field)
  if (!node) {
    const fallback = createExpertCanvas(next, locale.value).nodes.find(item => item.field === field)
    if (fallback) {
      node = { ...fallback, runs: fallback.runs?.map(run => ({ ...run })) }
      canvas.nodes.push(node)
    }
  }
  if (!node) return
  node.runs = normalizeRuns(node.runs ?? [], value)
}

function reindexCanvasNodes(next: ExpertWysiwygDraft, prefix: string, count: number, removedIndex: number) {
  const canvas = ensureCanvas(next, locale.value)
  const escapedPrefix = prefix.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const pattern = new RegExp(`^${escapedPrefix}\\.(\\d+)$`)
  // List geometry belongs to a visual slot, while its text runs belong to the
  // item.  When an item is removed, move the following item's rich text into
  // the preceding slot before dropping the final node. This keeps colour and
  // font-size selections attached to the surviving content without leaving a
  // gap in an imported canvas layout.
  const previousRuns = new Map<number, ExpertCanvasTextRun[]>()
  canvas.nodes.forEach(node => {
    const match = pattern.exec(node.field)
    if (match) previousRuns.set(Number(match[1]), (node.runs ?? []).map(run => ({ ...run })))
  })
  canvas.nodes = canvas.nodes.filter(node => {
    const match = pattern.exec(node.field)
    return !match || Number(match[1]) < count
  })
  for (let index = 0; index < count; index += 1) {
    const field = `${prefix}.${index}`
    syncFieldCanvas(next, field, fieldValue(next, field, locale.value))
    if (index < removedIndex) continue
    const node = canvas.nodes.find(item => item.field === field)
    const shiftedRuns = previousRuns.get(index + 1)
    if (node && shiftedRuns) node.runs = normalizeRuns(shiftedRuns, fieldValue(next, field, locale.value))
  }
}

function swapCanvasRuns(next: ExpertWysiwygDraft, prefix: string, left: number, right: number) {
  const canvas = ensureCanvas(next, locale.value)
  const first = canvas.nodes.find(node => node.field === `${prefix}.${left}`)
  const second = canvas.nodes.find(node => node.field === `${prefix}.${right}`)
  if (!first || !second) return
  ;[first.runs, second.runs] = [second.runs, first.runs]
}

function applyTextStyle(property: 'color' | 'fontSize', value: string | number) {
  const field = activeField.value
  if (props.disabled) return
  if (activeTable.value && currentPreview.value.table) {
    updateTable(table => {
      table.style = {
        ...(table.style ?? {}),
        ...(property === 'color' ? { color: String(value) } : { fontSize: Number(value) }),
      }
    })
    return
  }
  if (!field) return
  if (activeSelection.value.end <= activeSelection.value.start) return
  updateDraft(next => {
    const canvas = ensureCanvas(next, locale.value)
    const node = canvas.nodes.find(item => item.field === field)
    if (!node) return
    const text = fieldValue(next, field, locale.value)
    const runs = normalizeRuns(node.runs ?? [], text)
    const start = Math.max(0, Math.min(activeSelection.value.start, text.length))
    const end = Math.max(start, Math.min(activeSelection.value.end, text.length))
    const nextRuns: ExpertCanvasTextRun[] = []
    let cursor = 0
    for (const run of runs) {
      const runStart = cursor
      const runEnd = cursor + run.text.length
      const cuts = [runStart, Math.max(runStart, Math.min(start, runEnd)), Math.max(runStart, Math.min(end, runEnd)), runEnd]
        .filter((item, index, values) => values.indexOf(item) === index).sort((a, b) => a - b)
      for (let index = 0; index < cuts.length - 1; index += 1) {
        const from = cuts[index] - runStart
        const to = cuts[index + 1] - runStart
        const selected = cuts[index] >= start && cuts[index + 1] <= end
        nextRuns.push({
          ...run,
          text: run.text.slice(from, to),
          ...(selected && property === 'color' ? { color: String(value) } : {}),
          ...(selected && property === 'fontSize' ? { fontSize: Number(value) } : {}),
        })
      }
      cursor = runEnd
    }
    node.runs = nextRuns.filter(item => item.text.length > 0)
  })
}

function fieldStyle(field: string): Record<string, string> {
  const canvas = props.modelValue.canvas[locale.value]
  const run = canvas?.nodes.find(item => item.field === field)?.runs?.[0]
  if (!run) return {}
  return {
    ...(run.color ? { color: run.color } : {}),
    ...(run.fontSize ? { fontSize: `${run.fontSize}px` } : {}),
    ...(run.fontWeight ? { fontWeight: String(run.fontWeight) } : {}),
    ...(run.italic ? { fontStyle: 'italic' } : {}),
  }
}

function updateTitle(value: string) {
  updateDraft(next => { next.title[locale.value] = value; syncFieldCanvas(next, 'title', value) })
}

function updateDescription(value: string) {
  updateDraft(next => { next.description[locale.value] = value; syncFieldCanvas(next, 'description', value) })
}

function updateUsageGuide(value: string) {
  updateDraft(next => { next.usageGuide[locale.value] = value; syncFieldCanvas(next, 'usageGuide', value) })
}

function updatePreviewField(field: 'heading' | 'scene' | 'request' | 'material' | 'outputTitle', value: string) {
  updateDraft(next => { next.preview[locale.value][field] = value; syncFieldCanvas(next, `preview.${field}`, value) })
}

function updatePreviewParagraph(index: number, value: string) {
  updateDraft(next => { next.preview[locale.value].outputParagraphs[index] = value; syncFieldCanvas(next, `preview.outputParagraphs.${index}`, value) })
}

function addPreviewParagraph() {
  if (props.disabled || currentPreview.value.outputParagraphs.length >= EXPERT_WYSIWYG_LIMITS.previewParagraphCount) return
  updateDraft(next => {
    const index = next.preview[locale.value].outputParagraphs.length
    next.preview[locale.value].outputParagraphs.push('')
    syncFieldCanvas(next, `preview.outputParagraphs.${index}`, '')
  })
}

function removePreviewParagraph(index: number) {
  if (props.disabled) return
  resetFormattingTarget()
  updateDraft(next => {
    next.preview[locale.value].outputParagraphs.splice(index, 1)
    reindexCanvasNodes(next, 'preview.outputParagraphs', next.preview[locale.value].outputParagraphs.length, index)
  })
}

function updatePrompt(index: number, value: string) {
  updateDraft(next => { next.examplePrompts[locale.value][index] = value; syncFieldCanvas(next, `prompt.${index}`, value) })
}

function addPrompt() {
  if (props.disabled || currentPrompts.value.length >= EXPERT_WYSIWYG_LIMITS.promptCount) return
  updateDraft(next => {
    const index = next.examplePrompts[locale.value].length
    next.examplePrompts[locale.value].push('')
    syncFieldCanvas(next, `prompt.${index}`, '')
  })
}

function removePrompt(index: number) {
  if (props.disabled) return
  resetFormattingTarget()
  updateDraft(next => {
    next.examplePrompts[locale.value].splice(index, 1)
    reindexCanvasNodes(next, 'prompt', next.examplePrompts[locale.value].length, index)
  })
}

function addTag() {
  const value = tagDraft.value.trim()
  if (!value || props.disabled || currentTaskTypes.value.length >= EXPERT_WYSIWYG_LIMITS.taskTypeCount) return
  if (currentTaskTypes.value.includes(value)) {
    tagDraft.value = ''
    return
  }
  updateDraft(next => {
    const index = next.taskTypes[locale.value].length
    next.taskTypes[locale.value].push(value)
    syncFieldCanvas(next, `tag.${index}`, value)
  })
  tagDraft.value = ''
}

function removeTag(index: number) {
  if (props.disabled) return
  resetFormattingTarget()
  updateDraft(next => {
    next.taskTypes[locale.value].splice(index, 1)
    reindexCanvasNodes(next, 'tag', next.taskTypes[locale.value].length, index)
  })
}

function moveItem(items: string[], index: number, direction: -1 | 1) {
  const target = index + direction
  if (target < 0 || target >= items.length) return
  ;[items[index], items[target]] = [items[target], items[index]]
}

function moveTag(index: number, direction: -1 | 1) {
  if (props.disabled) return
  resetFormattingTarget()
  updateDraft(next => {
    const target = index + direction
    moveItem(next.taskTypes[locale.value], index, direction)
    swapCanvasRuns(next, 'tag', index, target)
  })
}

function movePrompt(index: number, direction: -1 | 1) {
  if (props.disabled) return
  resetFormattingTarget()
  updateDraft(next => {
    const target = index + direction
    moveItem(next.examplePrompts[locale.value], index, direction)
    swapCanvasRuns(next, 'prompt', index, target)
  })
}

function movePreviewParagraph(index: number, direction: -1 | 1) {
  if (props.disabled) return
  resetFormattingTarget()
  updateDraft(next => {
    const target = index + direction
    moveItem(next.preview[locale.value].outputParagraphs, index, direction)
    swapCanvasRuns(next, 'preview.outputParagraphs', index, target)
  })
}

function setZoom(value: number) {
  // Keep the 1080×704 coordinate contract while allowing the operator to inspect dense
  // typography and mixed runs at a larger scale.  The viewport already scrolls the enlarged
  // stage, so this does not change the persisted layout or the desktop's fixed dialog size.
  zoom.value = Math.max(60, Math.min(160, value))
}

function previewViewportSize(): { width: number; height: number } {
  if (typeof window === 'undefined') return { width: 0, height: 0 }
  return {
    width: Math.max(0, window.innerWidth || document.documentElement.clientWidth || 0),
    height: Math.max(0, window.innerHeight || document.documentElement.clientHeight || 0),
  }
}

function clampPreviewPosition(
  left: number,
  top: number,
  width: number,
  height: number,
): { left: number; top: number } {
  const viewport = previewViewportSize()
  return {
    left: Math.round(Math.min(Math.max(0, left), Math.max(0, viewport.width - width))),
    top: Math.round(Math.min(Math.max(0, top), Math.max(0, viewport.height - height))),
  }
}

function floatingPreviewMetrics(): { left: number; top: number; width: number; height: number } | null {
  const element = floatingPreviewRef.value
  if (!element) return null
  const rect = element.getBoundingClientRect()
  return {
    left: rect.left,
    top: rect.top,
    // jsdom does not calculate layout, so retaining the offset fallback also makes
    // the drag state deterministic in the component tests.
    width: Math.max(0, rect.width || element.offsetWidth),
    height: Math.max(0, rect.height || element.offsetHeight),
  }
}

function keepFloatingPreviewVisible() {
  const metrics = floatingPreviewMetrics()
  if (!metrics) return
  const next = clampPreviewPosition(metrics.left, metrics.top, metrics.width, metrics.height)
  // Keep the CSS right-anchored default until a drag or a resize actually needs
  // an explicit position. Once moved, preserve that position across close/reopen.
  if (previewPosition.value
    || Math.abs(next.left - metrics.left) > 1
    || Math.abs(next.top - metrics.top) > 1) {
    previewPosition.value = next
  }
}

function stopFloatingPreviewDrag() {
  if (!previewDragging.value && !previewDragSource) return
  if (typeof document !== 'undefined') {
    document.removeEventListener('pointermove', onPreviewPointerMove)
    document.removeEventListener('pointerup', onPreviewPointerUp)
    document.removeEventListener('pointercancel', onPreviewPointerCancel)
    document.removeEventListener('mousemove', onPreviewMouseMove)
    document.removeEventListener('mouseup', onPreviewMouseUp)
    if (document.body) {
      document.body.style.userSelect = previousBodyUserSelect
      document.body.style.cursor = previousBodyCursor
    }
  }
  previewDragging.value = false
  previewDragSource = null
  previewDragPointerId = null
  keepFloatingPreviewVisible()
}

function updateFloatingPreviewDrag(clientX: number, clientY: number, event: Event) {
  if (!previewDragging.value) return
  previewPosition.value = clampPreviewPosition(
    clientX - previewDragOffset.x,
    clientY - previewDragOffset.y,
    previewDragSize.width,
    previewDragSize.height,
  )
  if (event.cancelable) event.preventDefault()
}

function onPreviewPointerMove(event: PointerEvent) {
  if (previewDragSource !== 'pointer' || event.pointerId !== previewDragPointerId) return
  updateFloatingPreviewDrag(event.clientX, event.clientY, event)
}

function onPreviewPointerUp(event: PointerEvent) {
  if (previewDragSource !== 'pointer' || event.pointerId !== previewDragPointerId) return
  stopFloatingPreviewDrag()
}

function onPreviewPointerCancel(event: PointerEvent) {
  if (previewDragSource !== 'pointer' || event.pointerId !== previewDragPointerId) return
  stopFloatingPreviewDrag()
}

function onPreviewMouseMove(event: MouseEvent) {
  if (previewDragSource !== 'mouse') return
  updateFloatingPreviewDrag(event.clientX, event.clientY, event)
}

function onPreviewMouseUp() {
  if (previewDragSource !== 'mouse') return
  stopFloatingPreviewDrag()
}

function beginFloatingPreviewDrag(
  clientX: number,
  clientY: number,
  source: PreviewDragSource,
  pointerId: number | null,
  event: PointerEvent | MouseEvent,
) {
  if (props.disabled || !previewOpen.value || previewDragSource) return
  const target = event.target
  if (target instanceof Element && target.closest('button')) return
  const metrics = floatingPreviewMetrics()
  if (!metrics) return

  // Convert the initial right/top CSS position into explicit coordinates only
  // when a drag starts. This lets the window remain naturally right anchored
  // before its first move while making subsequent movement stable.
  const position = clampPreviewPosition(metrics.left, metrics.top, metrics.width, metrics.height)
  previewPosition.value = position
  previewDragOffset = { x: clientX - position.left, y: clientY - position.top }
  previewDragSize = { width: metrics.width, height: metrics.height }
  previewDragSource = source
  previewDragPointerId = pointerId
  previewDragging.value = true
  if (typeof document !== 'undefined') {
    document.addEventListener('pointermove', onPreviewPointerMove)
    document.addEventListener('pointerup', onPreviewPointerUp)
    document.addEventListener('pointercancel', onPreviewPointerCancel)
    document.addEventListener('mousemove', onPreviewMouseMove)
    document.addEventListener('mouseup', onPreviewMouseUp)
    if (document.body) {
      previousBodyUserSelect = document.body.style.userSelect
      previousBodyCursor = document.body.style.cursor
      document.body.style.userSelect = 'none'
      document.body.style.cursor = 'grabbing'
    }
  }
  if (event.cancelable) event.preventDefault()
}

function onPreviewPointerDown(event: PointerEvent) {
  if (event.button !== undefined && event.button !== 0) return
  beginFloatingPreviewDrag(event.clientX, event.clientY, 'pointer', event.pointerId, event)
}

function onPreviewMouseDown(event: MouseEvent) {
  if (event.button !== 0) return
  // Browsers dispatch a compatibility mousedown after pointerdown. The pointer
  // session owns that gesture, so do not start a second drag.
  if (previewDragging.value) return
  beginFloatingPreviewDrag(event.clientX, event.clientY, 'mouse', null, event)
}

function onViewportResize() {
  if (!previewOpen.value || previewDragging.value) return
  keepFloatingPreviewVisible()
}

watch([previewOpen, previewMinimized], async ([open]) => {
  if (!open) {
    stopFloatingPreviewDrag()
    return
  }
  await nextTick()
  keepFloatingPreviewVisible()
})

onMounted(() => {
  window.addEventListener('resize', onViewportResize)
  keepFloatingPreviewVisible()
})

function setImageInputRef(role: ExpertImageRole, element: Element | ComponentPublicInstance | null) {
  if (element instanceof HTMLInputElement) imageInputRefs.set(role, element)
  else imageInputRefs.delete(role)
}

function chooseImage(role: ExpertImageRole) {
  if (!props.disabled) imageInputRefs.get(role)?.click()
}

function onImagePicked(role: ExpertImageRole, event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || props.disabled) return
  if (role === 'exampleImage') {
    resetFormattingTarget()
    resetExampleView()
  }
  const previousUrl = props.modelValue.media[role]?.url
  if (previousUrl) releaseObjectUrl(previousUrl)
  const url = typeof URL.createObjectURL === 'function' ? URL.createObjectURL(file) : ''
  if (url) {
    objectUrls.add(url)
    objectUrlFiles.set(url, file)
  }
  updateDraft(next => {
    next.media[role] = {
      url,
      fileName: file.name,
      pendingClear: false,
      ...(role === 'exampleImage' ? { locale: locale.value } : {}),
    }
  })
  emit('asset-picked', role, file)
}

function clearImage(role: ExpertImageRole) {
  if (props.disabled) return
  if (role === 'exampleImage') resetExampleView()
  const current = props.modelValue.media[role]
  if (current.url) releaseObjectUrl(current.url)
  updateDraft(next => {
    next.media[role] = {
      url: '',
      ...(role === 'exampleImage' ? { locale: null } : {}),
      ...(current.url ? { pendingClear: true } : {}),
    }
  })
  emit('asset-cleared', role)
}

function imageUrl(role: ExpertImageRole): string {
  return props.modelValue.media[role]?.url ?? ''
}

function releaseObjectUrl(url: string) {
  if (!objectUrls.has(url)) return
  objectUrls.delete(url)
  objectUrlFiles.delete(url)
  if (typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url)
}

watch(
  () => props.modelValue.media.exampleImage?.url ? props.modelValue.media.exampleImage.locale ?? 'zh' : null,
  resetExampleView,
)

watch(
  () => ([
    props.modelValue.media.icon?.url ?? '',
    props.modelValue.media.illustration?.url ?? '',
    props.modelValue.media.exampleImage?.url ?? '',
  ]),
  (activeUrls) => {
    const active = new Set(activeUrls.filter(Boolean))
    for (const url of [...objectUrls]) {
      // The parent replaces the immediate local preview with the normalized
      // upload bytes asynchronously. Release only URLs created by this editor;
      // authenticated/cloud preview URLs remain owned by their parent caches.
      if (!active.has(url)) releaseObjectUrl(url)
    }
  },
)

function imageDownloadName(role: ExpertImageRole, blob?: Blob): string {
  const asset = props.modelValue.media[role]
  const fallback = role === 'illustration' ? 'expert-illustration' : role === 'icon' ? 'expert-avatar' : 'expert-example-image'
  const raw = asset?.fileName?.trim() || fallback
  const safe = raw.replace(/[\\/:*?"<>|\u0000-\u001f]/gu, '-').replace(/\s+/gu, '-').slice(0, 120)
  const base = safe.replace(/\.[^.]+$/u, '') || fallback
  const existingExtension = /\.([a-z0-9]+)$/iu.exec(safe)?.[1]?.toLowerCase()
  const extension = blob ? imageExtensionForBlob(blob) : (existingExtension || 'png')
  return `${base}.${extension}`
}

async function downloadImage(role: ExpertImageRole) {
  if (props.disabled) return
  const url = imageUrl(role)
  if (!url) return
  try {
    const source = objectUrlFiles.get(url)
    const blob: Blob = source ?? await fetch(url).then(response => {
      if (!response.ok) throw new Error(`image download failed: ${response.status}`)
      return response.blob()
    })
    downloadSkillImageBlob(imageDownloadName(role, blob), blob)
  } catch {
    // A remote object may reject CORS even though it is still downloadable by
    // the browser. Fall back to a direct download navigation in that case.
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = imageDownloadName(role)
    anchor.rel = 'noopener'
    anchor.style.display = 'none'
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
  }
}

function mediaLabel(role: ExpertImageRole): string {
  return role === 'illustration' ? '横幅插画' : role === 'icon' ? '头像' : '功能示例图'
}

function hasError(field: string): boolean {
  return validationErrors.value.some(error => error.field === field && (!error.locale || error.locale === locale.value))
}

onBeforeUnmount(() => {
  stopFloatingPreviewDrag()
  window.removeEventListener('resize', onViewportResize)
  if (typeof URL.revokeObjectURL === 'function') objectUrls.forEach(url => URL.revokeObjectURL(url))
  objectUrls.clear()
  objectUrlFiles.clear()
})
</script>

<template>
  <section class="expert-wysiwyg" :class="{ 'expert-wysiwyg--preview-dragging': previewDragging }" data-test="expert-wysiwyg-editor">
    <header class="expert-wysiwyg__toolbar">
      <div>
        <h3 class="expert-wysiwyg__title">专家详情所见即所得编辑</h3>
        <p class="expert-wysiwyg__hint">画布按桌面端 1080 × 704 详情页比例预览，悬停或聚焦区域即可编辑。</p>
        <span v-if="dirty" class="expert-wysiwyg__dirty" data-test="expert-wysiwyg-dirty">有未保存修改</span>
      </div>
      <div class="expert-wysiwyg__languages" role="tablist" aria-label="编辑语言">
        <button
          v-for="item in [{ key: 'zh', label: '中文' }, { key: 'en', label: 'English' }]"
          :key="item.key"
          type="button"
          role="tab"
          :aria-selected="locale === item.key"
          :class="{ 'is-active': locale === item.key }"
          :disabled="disabled"
          data-test="expert-wysiwyg-language"
          @click="selectLocale(item.key as ExpertEditorLocale)"
        >
          {{ item.label }}
        </button>
        <span class="expert-wysiwyg__toolbar-divider" aria-hidden="true" />
        <button type="button" :disabled="zoom <= 60" aria-label="缩小画布" @click="setZoom(zoom - 10)">−</button>
        <output class="expert-wysiwyg__zoom" aria-live="polite">{{ zoom }}%</output>
        <button type="button" :disabled="zoom >= 160" aria-label="放大画布" @click="setZoom(zoom + 10)">＋</button>
        <span class="expert-wysiwyg__toolbar-divider" aria-hidden="true" />
        <button
          v-if="!previewOpen"
          type="button"
          data-test="expert-preview-reopen"
          aria-label="打开实时预览"
          @click="previewOpen = true; previewMinimized = false"
        >打开实时预览</button>
        <button
          v-else
          type="button"
          data-test="expert-preview-minimize"
          aria-label="最小化实时预览"
          @click="previewMinimized = !previewMinimized"
        >{{ previewMinimized ? '恢复预览' : '最小化预览' }}</button>
      </div>
    </header>

    <div class="expert-wysiwyg__format-toolbar" role="toolbar" aria-label="文字格式">
      <span class="expert-wysiwyg__format-hint">选中文字后设置：</span>
      <label>字号
        <select v-model.number="selectedFontSize" data-test="expert-font-size" :disabled="disabled" @change="applyTextStyle('fontSize', selectedFontSize)">
          <option v-for="size in [12, 14, 16, 18, 20, 24, 28, 32]" :key="size" :value="size">{{ size }}px</option>
        </select>
      </label>
      <label>颜色
        <input v-model="selectedColor" data-test="expert-font-color" type="color" :disabled="disabled" aria-label="文字颜色" @change="applyTextStyle('color', selectedColor)" />
      </label>
      <button type="button" data-test="expert-apply-font-size" :disabled="disabled || !canApplyTextStyle" @click="applyTextStyle('fontSize', selectedFontSize)">应用字号</button>
      <button type="button" data-test="expert-apply-font-color" :disabled="disabled || !canApplyTextStyle" @click="applyTextStyle('color', selectedColor)">应用颜色</button>
      <span class="expert-wysiwyg__format-divider" aria-hidden="true" />
      <span class="expert-wysiwyg__table-tools" role="group" aria-label="自定义表格">
        <button type="button" data-test="expert-add-table" :disabled="disabled || !!currentPreview.table" @click="addTable">＋表格</button>
        <button type="button" data-test="expert-add-table-row" :disabled="disabled || !currentPreview.table || currentPreview.table.rows.length >= EXPERT_WYSIWYG_LIMITS.tableRowCount" @click="addTableRow">＋行</button>
        <button type="button" data-test="expert-remove-table-row" :disabled="disabled || !currentPreview.table || currentPreview.table.rows.length === 0" @click="removeTableRow">−行</button>
        <button type="button" data-test="expert-add-table-column" :disabled="disabled || !currentPreview.table || currentPreview.table.headers.length >= EXPERT_WYSIWYG_LIMITS.tableColumnCount" @click="addTableColumn">＋列</button>
        <button type="button" data-test="expert-remove-table-column" :disabled="disabled || !currentPreview.table || currentPreview.table.headers.length <= 1" @click="removeTableColumn">−列</button>
        <label class="expert-wysiwyg__table-total"><input type="checkbox" :checked="!!currentPreview.table?.totalRow" :disabled="disabled || !currentPreview.table" data-test="expert-table-total-row" @change="toggleTableTotalRow(($event.target as HTMLInputElement).checked)" />合计行</label>
        <button type="button" class="expert-wysiwyg__table-delete" data-test="expert-delete-table" :disabled="disabled || !currentPreview.table" @click="removeTable">删除表格</button>
      </span>
    </div>

    <div class="expert-wysiwyg__workbench">
    <div class="expert-wysiwyg__viewport">
      <div class="expert-wysiwyg__canvas-stage" :style="{ width: `${1080 * zoom / 100}px`, height: `${704 * zoom / 100}px` }">
      <div class="expert-wysiwyg__canvas" data-test="expert-wysiwyg-canvas" :style="{ transform: `scale(${zoom / 100})` }">
        <section class="expert-wysiwyg__preview-column">
           <ExpertRichTextField
             editor-class="expert-wysiwyg__preview-heading"
             :model-value="currentPreview.heading"
             :runs="fieldRuns('preview.heading')"
             :maxlength="EXPERT_WYSIWYG_LIMITS.previewHeading"
             :disabled="disabled"
             label="示例预览标题"
             placeholder="一句话，开始创作"
             :multiline="false"
             @selection-change="rememberRichSelection('preview.heading', $event)"
             @update:runs="rememberRichRuns('preview.heading', $event)"
             @update:model-value="updateRichField('preview.heading', $event)"
           />
           <ExpertRichTextField
             editor-class="expert-wysiwyg__preview-scene"
             :model-value="currentPreview.scene"
             :runs="fieldRuns('preview.scene')"
             :maxlength="EXPERT_WYSIWYG_LIMITS.previewScene"
             :disabled="disabled"
              label="示例场景"
             placeholder="可选：场景描述"
             :multiline="false"
             @selection-change="rememberRichSelection('preview.scene', $event)"
             @update:runs="rememberRichRuns('preview.scene', $event)"
             @update:model-value="updateRichField('preview.scene', $event)"
           />
          <div class="expert-wysiwyg__block expert-wysiwyg__preview-input" :class="{ 'expert-wysiwyg__preview-input--image': exampleImageVisible }" :tabindex="exampleImageVisible ? -1 : 0" data-block="8" data-test="expert-block-preview-input">
            <span v-if="!exampleImageVisible" class="expert-wysiwyg__card-label">示例输入</span>
            <template v-if="exampleImageVisible">
              <div
                class="expert-wysiwyg__example-image"
                :tabindex="disabled ? -1 : 0"
                role="button"
                data-test="expert-preview-example-image"
                aria-label="替换功能示例图"
                :aria-disabled="disabled"
                @click="chooseImage('exampleImage')"
                @keydown.enter.self.prevent="chooseImage('exampleImage')"
                @keydown.space.self.prevent="chooseImage('exampleImage')"
              >
                 <img :src="imageUrl('exampleImage')" alt="功能示例图预览" />
                 <span class="expert-wysiwyg__example-image-tip">点击替换</span>
                 <div class="expert-wysiwyg__media-actions" role="group" aria-label="功能示例图操作" @click.stop>
                   <button type="button" :disabled="disabled" data-test="expert-image-replace-exampleImage" aria-label="替换功能示例图" @click.stop="chooseImage('exampleImage')">替换</button>
                   <button type="button" :disabled="disabled" data-test="expert-image-download-exampleImage" aria-label="下载功能示例图" @click.stop="downloadImage('exampleImage')">下载</button>
                   <button type="button" :disabled="disabled" data-test="expert-preview-example-image-clear" aria-label="清除功能示例图" @click.stop="clearImage('exampleImage')">清除</button>
                 </div>
               </div>
            </template>
            <template v-else>
              <div class="expert-wysiwyg__preview-input-fields">
               <ExpertRichTextField
                 editor-class="expert-wysiwyg__preview-input-text"
                 :model-value="currentPreview.request"
                 :runs="fieldRuns('preview.request')"
                 :maxlength="EXPERT_WYSIWYG_LIMITS.previewRequest"
                 :disabled="disabled"
                  label="示例请求"
                 placeholder="示例输入"
                 @selection-change="rememberRichSelection('preview.request', $event)"
                 @update:runs="rememberRichRuns('preview.request', $event)"
                 @update:model-value="updateRichField('preview.request', $event)"
               />
               <ExpertRichTextField
                 editor-class="expert-wysiwyg__preview-input-text"
                 :model-value="currentPreview.material"
                 :runs="fieldRuns('preview.material')"
                 :maxlength="EXPERT_WYSIWYG_LIMITS.previewMaterial"
                 :disabled="disabled"
                  label="示例素材"
                 placeholder="可选：示例素材"
                 @selection-change="rememberRichSelection('preview.material', $event)"
                 @update:runs="rememberRichRuns('preview.material', $event)"
                 @update:model-value="updateRichField('preview.material', $event)"
               />
              </div>
              <button v-if="!hasLocalizedExampleImage" type="button" class="expert-wysiwyg__example-image-upload" :disabled="disabled" data-test="expert-preview-example-image-upload" @click="chooseImage('exampleImage')">
                上传功能示例图
              </button>
            </template>
            <button
              v-if="hasLocalizedExampleImage"
              type="button"
              class="expert-wysiwyg__example-toggle"
              data-test="expert-preview-example-toggle"
              :aria-label="exampleImageVisible ? '切换到文本示例' : '切换到图片示例'"
              :aria-pressed="!exampleImageVisible"
              @click.stop="toggleExampleView"
            >
              {{ exampleImageVisible ? '切换到文本' : '切换到图片' }}
            </button>
            <input
              :ref="element => setImageInputRef('exampleImage', element)"
              type="file"
              accept="image/png,image/jpeg,image/webp"
              hidden
              data-test="expert-image-input-exampleImage"
              @change="onImagePicked('exampleImage', $event)"
            />
          </div>
          <div class="expert-wysiwyg__preview-author" aria-hidden="true">
            <span class="expert-wysiwyg__preview-author-avatar">
              <img v-if="imageUrl('icon')" :src="imageUrl('icon')" alt="" />
              <span v-else>AI</span>
            </span>
            <strong>{{ currentTitle || '专家名称' }}</strong>
          </div>
          <div class="expert-wysiwyg__block expert-wysiwyg__preview-output" tabindex="0" data-block="8" data-test="expert-block-preview-output">
             <ExpertRichTextField
               editor-class="expert-wysiwyg__card-label"
               :model-value="currentPreview.outputTitle"
               :runs="fieldRuns('preview.outputTitle')"
               :maxlength="EXPERT_WYSIWYG_LIMITS.previewOutputTitle"
               :disabled="disabled"
               label="示例输出标题"
               placeholder="示例输出标题"
               :multiline="false"
               @selection-change="rememberRichSelection('preview.outputTitle', $event)"
               @update:runs="rememberRichRuns('preview.outputTitle', $event)"
               @update:model-value="updateRichField('preview.outputTitle', $event)"
             />
            <div
              v-if="currentPreview.intro"
              class="expert-wysiwyg__output-intro"
              :class="{
                'expert-wysiwyg__output-intro--muted': currentPreview.intro.tone === 'muted',
                'expert-wysiwyg__output-intro--accent': currentPreview.intro.tone === 'accent',
              }"
              :data-tone="currentPreview.intro.tone ?? 'body'"
            >
              <ExpertRichTextField
                editor-class="expert-wysiwyg__output-intro-field"
                :model-value="currentPreview.intro.text"
                :runs="fieldRuns('preview.intro')"
                :maxlength="EXPERT_WYSIWYG_LIMITS.previewIntro"
                :disabled="disabled"
                label="示例输出导语"
                placeholder="示例输出导语"
                @selection-change="rememberRichSelection('preview.intro', $event)"
                @update:runs="rememberRichRuns('preview.intro', $event)"
                @update:model-value="updateRichField('preview.intro', $event)"
              />
            </div>
            <div v-if="currentPreview.table" class="expert-wysiwyg__table-editor" data-test="expert-table-editor" @click.stop="activateTable">
              <table :style="tableEditorStyle(currentPreview.table)">
                <colgroup>
                  <col v-for="(width, index) in currentPreview.table.columnWidths" :key="`editor-width-${index}`" :style="{ width: `${width}%` }" />
                </colgroup>
                <thead>
                  <tr>
                    <th v-for="(header, index) in currentPreview.table.headers" :key="`editor-header-${index}`" :style="tableHeaderEditorStyle(currentPreview.table)">
                      <input :value="header" :maxlength="EXPERT_WYSIWYG_LIMITS.tableCell" :disabled="disabled" :aria-label="`表头 ${index + 1}`" @focus="rememberSelection(`preview.table.header.${index}`, $event)" @select="rememberSelection(`preview.table.header.${index}`, $event)" @input="updateTableHeader(index, ($event.target as HTMLInputElement).value)" />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(row, rowIndex) in currentPreview.table.rows" :key="`editor-row-${rowIndex}`" :class="{ 'is-total': currentPreview.table.totalRow && rowIndex === currentPreview.table.rows.length - 1 }">
                    <td v-for="(cell, cellIndex) in row" :key="`editor-cell-${rowIndex}-${cellIndex}`" :style="tableCellEditorStyle(currentPreview.table)">
                      <input :value="String(cell)" :maxlength="EXPERT_WYSIWYG_LIMITS.tableCell" :disabled="disabled" :aria-label="`第 ${rowIndex + 1} 行第 ${cellIndex + 1} 列`" @focus="rememberSelection(`preview.table.cell.${rowIndex}.${cellIndex}`, $event)" @select="rememberSelection(`preview.table.cell.${rowIndex}.${cellIndex}`, $event)" @input="updateTableCell(rowIndex, cellIndex, ($event.target as HTMLInputElement).value)" />
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-for="(paragraph, index) in currentPreview.outputParagraphs" :key="index" class="expert-wysiwyg__ordered-row">
               <ExpertRichTextField
                 editor-class="expert-wysiwyg__output-paragraph"
                 :model-value="paragraph"
                 :runs="fieldRuns(`preview.outputParagraphs.${index}`)"
                 :maxlength="EXPERT_WYSIWYG_LIMITS.previewParagraph"
                 :disabled="disabled"
                 :label="`示例输出段落 ${index + 1}`"
                 placeholder="示例输出段落"
                 @selection-change="rememberRichSelection(`preview.outputParagraphs.${index}`, $event)"
                 @update:runs="rememberRichRuns(`preview.outputParagraphs.${index}`, $event)"
                 @update:model-value="updateRichField(`preview.outputParagraphs.${index}`, $event)"
               />
              <span class="expert-wysiwyg__order-actions">
                <button type="button" :disabled="disabled || index === 0" :aria-label="`上移示例输出段落 ${index + 1}`" @click="movePreviewParagraph(index, -1)">↑</button>
                <button type="button" :disabled="disabled || index === currentPreview.outputParagraphs.length - 1" :aria-label="`下移示例输出段落 ${index + 1}`" @click="movePreviewParagraph(index, 1)">↓</button>
                <button type="button" :disabled="disabled" :aria-label="`删除示例输出段落 ${index + 1}`" @click.stop="removePreviewParagraph(index)">×</button>
              </span>
            </div>
            <button type="button" data-test="expert-add-output-paragraph" aria-label="添加示例输出段落" :disabled="disabled || currentPreview.outputParagraphs.length >= EXPERT_WYSIWYG_LIMITS.previewParagraphCount" @click="addPreviewParagraph">+ 输出段落</button>
          </div>
        </section>

        <section class="expert-wysiwyg__detail-column">
          <section class="expert-wysiwyg__block expert-wysiwyg__banner" :tabindex="disabled ? -1 : 0" :aria-disabled="disabled" aria-label="编辑横幅插画" role="button" data-block="1" data-test="expert-block-illustration" @click="chooseImage('illustration')" @keydown.enter.self.prevent="chooseImage('illustration')" @keydown.space.self.prevent="chooseImage('illustration')">
           <img v-if="imageUrl('illustration')" :src="imageUrl('illustration')" alt="横幅插画" />
           <div v-else class="expert-wysiwyg__empty-media">点击上传 3:1 横幅插画</div>
           <span class="expert-wysiwyg__edit-tip">编辑横幅</span>
             <div v-if="imageUrl('illustration')" class="expert-wysiwyg__media-actions" role="group" aria-label="横幅插画操作" @click.stop>
               <button type="button" :disabled="disabled" data-test="expert-image-replace-illustration" aria-label="替换横幅插画" @click.stop="chooseImage('illustration')">替换</button>
               <button type="button" :disabled="disabled" data-test="expert-image-download-illustration" aria-label="下载横幅插画" @click.stop="downloadImage('illustration')">下载</button>
               <button type="button" :disabled="disabled" data-test="expert-image-clear-illustration" aria-label="清除横幅插画" @click.stop="clearImage('illustration')">清除</button>
             </div>
            <input :ref="element => setImageInputRef('illustration', element)" type="file" accept="image/png,image/jpeg,image/webp" hidden data-test="expert-image-input-illustration" @change="onImagePicked('illustration', $event)" />
          </section>

          <div class="expert-wysiwyg__identity">
            <section class="expert-wysiwyg__block expert-wysiwyg__avatar" :tabindex="disabled ? -1 : 0" :aria-disabled="disabled" aria-label="编辑头像" role="button" data-block="2" data-test="expert-block-icon" @click="chooseImage('icon')" @keydown.enter.self.prevent="chooseImage('icon')" @keydown.space.self.prevent="chooseImage('icon')">
               <img v-if="imageUrl('icon')" :src="imageUrl('icon')" alt="头像" />
               <span v-else>头像</span>
               <div v-if="imageUrl('icon')" class="expert-wysiwyg__media-actions" role="group" aria-label="头像操作" @click.stop>
                 <button type="button" :disabled="disabled" data-test="expert-image-replace-icon" aria-label="替换头像" @click.stop="chooseImage('icon')">替换</button>
                 <button type="button" :disabled="disabled" data-test="expert-image-download-icon" aria-label="下载头像" @click.stop="downloadImage('icon')">下载</button>
                 <button type="button" :disabled="disabled" data-test="expert-image-clear-icon" aria-label="清除头像" @click.stop="clearImage('icon')">清除</button>
               </div>
              <input :ref="element => setImageInputRef('icon', element)" type="file" accept="image/png,image/jpeg,image/webp" hidden data-test="expert-image-input-icon" @change="onImagePicked('icon', $event)" />
            </section>
            <section class="expert-wysiwyg__block expert-wysiwyg__identity-fields" tabindex="0" data-block="3">
               <ExpertRichTextField
                 editor-class="expert-wysiwyg__identity-title"
                 :model-value="currentTitle"
                 :runs="fieldRuns('title')"
                 :maxlength="EXPERT_WYSIWYG_LIMITS.title"
                 :disabled="disabled"
                 label="专家名称"
                 placeholder="专家名称"
                 :multiline="false"
                 @selection-change="rememberRichSelection('title', $event)"
                 @update:runs="rememberRichRuns('title', $event)"
                 @update:model-value="updateRichField('title', $event)"
               />
              <div class="expert-wysiwyg__block expert-wysiwyg__tags" tabindex="0" data-block="4">
                <span class="expert-wysiwyg__runtime-tag">云端</span>
                <span v-for="(tag, index) in currentTaskTypes" :key="`${tag}-${index}`" class="expert-wysiwyg__tag">
                  {{ tag }}
                  <button type="button" :disabled="disabled || index === 0" aria-label="上移任务类型" @click.stop="moveTag(index, -1)">←</button>
                  <button type="button" :disabled="disabled || index === currentTaskTypes.length - 1" aria-label="下移任务类型" @click.stop="moveTag(index, 1)">→</button>
                  <button type="button" :disabled="disabled" aria-label="删除任务类型" @click.stop="removeTag(index)">×</button>
                </span>
                <span class="expert-wysiwyg__tag-adder">
                  <input v-model="tagDraft" :disabled="disabled || currentTaskTypes.length >= EXPERT_WYSIWYG_LIMITS.taskTypeCount" :maxlength="EXPERT_WYSIWYG_LIMITS.taskType" aria-label="新增任务类型" placeholder="添加任务类型" @keydown.enter.prevent="addTag" />
                  <button
                    type="button"
                    class="expert-wysiwyg__tag-add"
                    data-test="expert-add-task-type"
                    aria-label="确认添加任务类型"
                    :disabled="disabled || !tagDraft.trim() || currentTaskTypes.length >= EXPERT_WYSIWYG_LIMITS.taskTypeCount"
                    @click.stop="addTag"
                  >+</button>
                </span>
              </div>
            </section>
          </div>

          <section class="expert-wysiwyg__block expert-wysiwyg__text-block" tabindex="0" data-block="5">
             <ExpertRichTextField
               editor-class="expert-wysiwyg__description"
               :model-value="currentDescription"
               :runs="fieldRuns('description')"
               :maxlength="EXPERT_WYSIWYG_LIMITS.description"
               :disabled="disabled"
                label="专家描述"
               placeholder="写一段桌面端详情页显示的专家描述"
               @selection-change="rememberRichSelection('description', $event)"
               @update:runs="rememberRichRuns('description', $event)"
               @update:model-value="updateRichField('description', $event)"
             />
          </section>
          <div class="expert-wysiwyg__detail-scroll" data-test="expert-detail-scroll">
          <section class="expert-wysiwyg__block expert-wysiwyg__text-block" tabindex="0" data-block="6">
            <h4>开始前，告诉它这些</h4>
             <ExpertRichTextField
               editor-class="expert-wysiwyg__usage-guide"
               :model-value="currentUsageGuide"
               :runs="fieldRuns('usageGuide')"
               :maxlength="EXPERT_WYSIWYG_LIMITS.usageGuide"
               :disabled="disabled"
                label="使用说明"
               placeholder="说明发布平台、目标读者、内容主题……"
               @selection-change="rememberRichSelection('usageGuide', $event)"
               @update:runs="rememberRichRuns('usageGuide', $event)"
               @update:model-value="updateRichField('usageGuide', $event)"
             />
          </section>
          <section class="expert-wysiwyg__block expert-wysiwyg__prompt-preview" tabindex="0" data-block="7">
            <div class="expert-wysiwyg__section-heading">
              <h4>从一个例子开始</h4>
              <button type="button" class="expert-wysiwyg__inline-add" data-test="expert-add-prompt" :disabled="disabled || currentPrompts.length >= EXPERT_WYSIWYG_LIMITS.promptCount" @click.stop="addPrompt">
                + 添加示例
              </button>
            </div>
            <p v-if="currentPrompts.length === 0" class="expert-wysiwyg__placeholder">添加一个可直接使用的示例</p>
            <div v-for="(prompt, index) in currentPrompts" :key="index" class="expert-wysiwyg__prompt-card">
               <ExpertRichTextField
                 editor-class="expert-wysiwyg__prompt-input"
                 :model-value="prompt"
                 :runs="fieldRuns(`prompt.${index}`)"
                 :maxlength="EXPERT_WYSIWYG_LIMITS.prompt"
                 :disabled="disabled"
                  :label="`示例 ${index + 1}`"
                 placeholder="示例"
                 :multiline="false"
                 @selection-change="rememberRichSelection(`prompt.${index}`, $event)"
                 @update:runs="rememberRichRuns(`prompt.${index}`, $event)"
                 @update:model-value="updateRichField(`prompt.${index}`, $event)"
               />
              <button type="button" :disabled="disabled || index === 0" :aria-label="`上移第 ${index + 1} 条示例`" @click.stop="movePrompt(index, -1)">↑</button>
              <button type="button" :disabled="disabled || index === currentPrompts.length - 1" :aria-label="`下移第 ${index + 1} 条示例`" @click.stop="movePrompt(index, 1)">↓</button>
              <button type="button" :disabled="disabled" aria-label="删除示例" @click.stop="removePrompt(index)">×</button>
            </div>
          </section>
          </div>
          <footer class="expert-wysiwyg__footer">
            <button type="button" disabled>开始对话</button>
            <button type="button" disabled>卸载</button>
          </footer>
        </section>
      </div>
      </div>
    </div>
    </div>

    <div
      v-if="previewOpen"
      ref="floatingPreviewRef"
      class="expert-wysiwyg__floating-preview"
      :class="{ 'expert-wysiwyg__floating-preview--minimized': previewMinimized }"
      :style="floatingPreviewStyle"
      data-test="expert-floating-preview"
    >
      <header
        class="expert-wysiwyg__floating-preview-header"
        data-test="expert-preview-drag-handle"
        @pointerdown="onPreviewPointerDown"
        @mousedown="onPreviewMouseDown"
      >
        <strong>实时预览</strong>
        <span>独立窗口</span>
        <button type="button" data-test="expert-preview-toggle" aria-label="切换预览大小" @click="previewMinimized = !previewMinimized">
          {{ previewMinimized ? '恢复' : '最小化' }}
        </button>
        <button type="button" data-test="expert-preview-close" aria-label="关闭实时预览" @click="previewOpen = false">×</button>
      </header>
      <ExpertDesktopPreview v-if="!previewMinimized" :model-value="modelValue" :locale="locale" :scale="34" />
    </div>

    <p v-if="validationErrors.length" class="expert-wysiwyg__validation" role="status" data-test="expert-wysiwyg-validation">
      {{ validationErrors[0].message }}
    </p>
  </section>
</template>

<style scoped>
.expert-wysiwyg {
  display: flex;
  flex-direction: column;
  gap: 16px;
  color: #1f2937;
}

.expert-wysiwyg__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.expert-wysiwyg__title {
  margin: 0;
  color: #111827;
  font-size: 17px;
  font-weight: 700;
}

.expert-wysiwyg__hint {
  margin: 4px 0 0;
  color: #6b7280;
  font-size: 12px;
  line-height: 18px;
}

.expert-wysiwyg__dirty {
  display: inline-flex;
  margin-top: 6px;
  border-radius: 999px;
  background: #fff7ed;
  color: #c2410c;
  font-size: 11px;
  padding: 3px 8px;
}

.expert-wysiwyg__languages {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #f9fafb;
}

.expert-wysiwyg__format-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #f8fafc;
  padding: 7px 10px;
  color: #475569;
  font-size: 12px;
}

.expert-wysiwyg__format-toolbar label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.expert-wysiwyg__format-toolbar select,
.expert-wysiwyg__format-toolbar input[type='color'] {
  height: 28px;
  border: 1px solid #dbe1e8;
  border-radius: 6px;
  background: white;
}

.expert-wysiwyg__format-toolbar input[type='color'] {
  width: 34px;
  padding: 2px;
}

.expert-wysiwyg__format-toolbar button {
  border: 1px solid #dbe1e8;
  border-radius: 6px;
  background: white;
  color: #334155;
  cursor: pointer;
  padding: 5px 8px;
}

.expert-wysiwyg__format-toolbar button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.expert-wysiwyg__format-divider {
  width: 1px;
  height: 22px;
  margin: 0 2px;
  background: #dbe1e8;
}

.expert-wysiwyg__table-tools {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  border: 1px solid #dbe1e8;
  border-radius: 7px;
  background: #fff;
  padding: 3px;
}

.expert-wysiwyg__table-tools button {
  border-color: transparent;
  padding: 4px 7px;
}

.expert-wysiwyg__table-tools .expert-wysiwyg__table-delete {
  color: #b91c1c;
}

.expert-wysiwyg__table-total {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 0 4px;
  white-space: nowrap;
}

.expert-wysiwyg__table-total input {
  width: auto;
}

.expert-wysiwyg__toolbar-divider {
  width: 1px;
  height: 20px;
  margin: 0 2px;
  background: #dbe1e8;
}

.expert-wysiwyg__zoom {
  min-width: 38px;
  color: #475569;
  font-size: 11px;
  text-align: center;
}

.expert-wysiwyg__languages button {
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #4b5563;
  cursor: pointer;
  font-size: 12px;
  padding: 7px 10px;
}

.expert-wysiwyg__languages button.is-active {
  background: #2563eb;
  color: white;
}

.expert-wysiwyg__languages button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.expert-wysiwyg__viewport {
  width: 100%;
  overflow: auto;
  border: 1px solid #e5e7eb;
  border-radius: 16px;
  background: #f3f5f8;
  padding: 12px;
}

.expert-wysiwyg__workbench {
  display: block;
  align-items: start;
  gap: 14px;
}

.expert-wysiwyg__workbench > .expert-wysiwyg__viewport {
  min-width: 0;
}

.expert-wysiwyg__floating-preview {
  position: fixed;
  z-index: 1200;
  top: 76px;
  right: 18px;
  display: flex;
  width: min(520px, calc(100vw - 36px));
  max-height: calc(100vh - 94px);
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #cbd5e1;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 18px 48px rgba(15, 23, 42, .22);
}

.expert-wysiwyg__floating-preview--minimized {
  width: auto;
  min-width: 190px;
  max-height: none;
}

.expert-wysiwyg__floating-preview-header {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 38px;
  border-bottom: 1px solid #e5e7eb;
  background: #f8fafc;
  color: #475569;
  cursor: grab;
  padding: 0 10px;
  font-size: 11px;
  touch-action: none;
  user-select: none;
}

.expert-wysiwyg--preview-dragging,
.expert-wysiwyg--preview-dragging * {
  cursor: grabbing !important;
  user-select: none !important;
}

.expert-wysiwyg__floating-preview-header strong {
  color: #111827;
  font-size: 12px;
}

.expert-wysiwyg__floating-preview-header span {
  flex: 1;
  color: #94a3b8;
}

.expert-wysiwyg__floating-preview-header button {
  border: 1px solid #dbe1e8;
  border-radius: 5px;
  background: #fff;
  color: #475569;
  cursor: pointer;
  font-size: 11px;
  line-height: 1;
  padding: 4px 7px;
}

.expert-wysiwyg__floating-preview-header button:last-child {
  color: #b91c1c;
}

.expert-wysiwyg__floating-preview > .expert-desktop-preview {
  min-height: 0;
  border: 0;
  border-radius: 0;
  overflow: auto;
}

.expert-wysiwyg__canvas-stage {
  position: relative;
  margin: 0 auto;
}

.expert-wysiwyg__canvas {
  position: relative;
  display: grid;
  grid-template-columns: 55.55% 44.45%;
  grid-template-rows: minmax(0, 1fr);
  width: 1080px;
  height: 704px;
  overflow: hidden;
  border-radius: 15px;
  background: #ffffff;
  box-shadow: 0 12px 36px rgba(15, 23, 42, 0.14);
  transform-origin: top left;
}

.expert-wysiwyg__block {
  position: relative;
  outline: 1px dashed transparent;
  outline-offset: 3px;
  transition: outline-color 0.15s ease, background 0.15s ease;
}

.expert-wysiwyg__block:hover,
.expert-wysiwyg__block:focus-visible {
  z-index: 2;
  outline-color: #3b82f6;
  background: rgba(239, 246, 255, 0.48);
}

.expert-wysiwyg__edit-tip {
  position: absolute;
  top: 8px;
  right: 8px;
  display: none;
  border-radius: 999px;
  background: #2563eb;
  color: white;
  font-size: 11px;
  padding: 4px 8px;
}

.expert-wysiwyg__clear-media {
  position: absolute;
  right: 8px;
  bottom: 8px;
  z-index: 1;
  border: 0;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.76);
  color: white;
  cursor: pointer;
  font-size: 11px;
  padding: 4px 8px;
}

.expert-wysiwyg__clear-media:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.expert-wysiwyg__media-actions {
  position: absolute;
  right: 8px;
  bottom: 8px;
  z-index: 3;
  display: inline-flex;
  gap: 4px;
  border: 1px solid rgba(255, 255, 255, .34);
  border-radius: 8px;
  background: rgba(15, 23, 42, .78);
  padding: 4px;
  opacity: 0;
  pointer-events: none;
  transform: translateY(4px);
  transition: opacity .15s ease, transform .15s ease;
}

.expert-wysiwyg__media-actions button {
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: #fff;
  cursor: pointer;
  font-size: 11px;
  padding: 4px 7px;
}

.expert-wysiwyg__media-actions button:hover,
.expert-wysiwyg__media-actions button:focus-visible {
  background: rgba(255, 255, 255, .18);
  outline: none;
}

.expert-wysiwyg__media-actions button:disabled {
  cursor: not-allowed;
  opacity: .5;
}

.expert-wysiwyg__block:hover > .expert-wysiwyg__media-actions,
.expert-wysiwyg__block:focus-within > .expert-wysiwyg__media-actions,
.expert-wysiwyg__example-image:hover > .expert-wysiwyg__media-actions,
.expert-wysiwyg__example-image:focus-within > .expert-wysiwyg__media-actions {
  opacity: 1;
  pointer-events: auto;
  transform: translateY(0);
}

.expert-wysiwyg__block:hover .expert-wysiwyg__edit-tip,
.expert-wysiwyg__block:focus-visible .expert-wysiwyg__edit-tip {
  display: inline-flex;
}

.expert-wysiwyg__banner {
  flex: 0 0 auto;
  width: calc(100% + 48px);
  aspect-ratio: 3 / 1;
  min-height: 0;
  margin: 0 -24px;
  overflow: hidden;
  background: linear-gradient(120deg, #e9eef5, #d7e0ea);
  cursor: pointer;
}

.expert-wysiwyg__banner img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.expert-wysiwyg__empty-media {
  display: grid;
  height: 100%;
  place-items: center;
  color: #64748b;
  font-size: 14px;
}

.expert-wysiwyg__columns {
  display: contents;
  min-height: 0;
}

.expert-wysiwyg__preview-column {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
  padding: 28px 32px;
  background: #fafbfd;
  grid-column: 1;
  grid-row: 1;
}

.expert-wysiwyg__preview-heading,
.expert-wysiwyg__card-label {
  color: #475569;
  font-size: 13px;
  font-weight: 700;
}

.expert-wysiwyg__preview-heading {
  color: #252525;
  font-size: 28px;
  font-weight: 750;
  line-height: 36px;
}

.expert-wysiwyg__preview-scene {
  color: #64748b;
  font-size: 14px;
  line-height: 20px;
}

.expert-wysiwyg__preview-input,
.expert-wysiwyg__preview-output {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 11px;
  background: white;
}

.expert-wysiwyg__preview-input {
  position: relative;
  flex: 0 0 140px;
  background: #f2f3f5;
}

.expert-wysiwyg__preview-input-fields {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.expert-wysiwyg__preview-input-fields :deep(.expert-rich-text-field) {
  flex-shrink: 0;
}

.expert-wysiwyg__preview-input--image {
  gap: 0;
  border: 0;
  overflow: hidden;
  padding: 0;
}

.expert-wysiwyg__example-image {
  position: relative;
  min-height: 0;
  flex: 1;
  overflow: hidden;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: #f8fafc;
  cursor: pointer;
}

.expert-wysiwyg__preview-input--image .expert-wysiwyg__example-image {
  border: 0;
  border-radius: 10px;
}

.expert-wysiwyg__example-image:focus-visible,
.expert-wysiwyg__example-image:hover {
  border-color: #3b82f6;
  outline: none;
}

.expert-wysiwyg__example-image img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: fill;
}

.expert-wysiwyg__example-image-tip {
  position: absolute;
  right: auto;
  left: 8px;
  bottom: 8px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.72);
  color: white;
  font-size: 11px;
  padding: 3px 7px;
  pointer-events: none;
}

.expert-wysiwyg__example-image-upload,
.expert-wysiwyg__example-toggle {
  position: absolute;
  top: 12px;
  right: 12px;
  z-index: 1;
  border: 0;
  border-radius: 6px;
  background: #e2e8f0;
  color: #475569;
  cursor: pointer;
  font-size: 11px;
  padding: 4px 8px;
}

.expert-wysiwyg__example-image-upload {
  background: #2563eb;
  color: white;
}

.expert-wysiwyg__example-toggle {
  top: 8px;
  right: 8px;
  z-index: 4;
  background: #2563eb;
  color: white;
}

.expert-wysiwyg__example-toggle:hover {
  background: #1d4ed8;
}

.expert-wysiwyg__example-toggle:focus-visible {
  outline: 2px solid #93c5fd;
  outline-offset: 2px;
}

.expert-wysiwyg__example-image-upload:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.expert-wysiwyg__preview-output {
  flex: 1;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}

.expert-wysiwyg__preview-author {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 32px;
  color: #303238;
  font-size: 14px;
}

.expert-wysiwyg__preview-author-avatar {
  display: grid;
  width: 30px;
  height: 30px;
  overflow: hidden;
  place-items: center;
  border-radius: 50%;
  background: #e5e7eb;
  color: #64748b;
  font-size: 10px;
}

.expert-wysiwyg__preview-author-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.expert-wysiwyg__detail-column {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  min-width: 0;
  /* The usage guide and quick-example cards share the only vertical scroll region below. */
  overflow: hidden;
  padding: 0 24px 16px;
  grid-column: 2;
  grid-row: 1;
}

.expert-wysiwyg__detail-scroll {
  display: flex;
  min-height: 0;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  overflow-x: hidden;
  overflow-y: auto;
  padding-right: 3px;
  scrollbar-gutter: stable;
}

.expert-wysiwyg__identity {
  display: flex;
  align-items: center;
  gap: 13px;
  margin-top: 0;
}

.expert-wysiwyg__avatar {
  display: grid;
  flex: 0 0 58px;
  width: 58px;
  height: 58px;
  overflow: hidden;
  place-items: center;
  border-radius: 50%;
  background: #e2e8f0;
  color: #64748b;
  cursor: pointer;
  font-size: 11px;
}

.expert-wysiwyg__avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.expert-wysiwyg__identity-fields {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 7px;
}

.expert-wysiwyg input,
.expert-wysiwyg textarea {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid transparent;
  border-radius: 7px;
  background: transparent;
  color: #1f2937;
  font: inherit;
  outline: none;
  resize: vertical;
}

.expert-wysiwyg :deep(.expert-rich-text-field__editor) {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid transparent;
  border-radius: 7px;
  background: transparent;
  color: #1f2937;
  font: inherit;
  outline: none;
  overflow-wrap: anywhere;
}

.expert-wysiwyg :deep(.expert-rich-text-field__editor:focus) {
  border-color: #93c5fd;
  background: #eff6ff;
}

.expert-wysiwyg :deep(.expert-wysiwyg__preview-heading) {
  min-height: 36px;
  color: #252525;
  font-size: 28px;
  font-weight: 750;
  line-height: 36px;
  padding: 0;
}

.expert-wysiwyg :deep(.expert-wysiwyg__preview-scene) {
  min-height: 20px;
  color: #64748b;
  font-size: 14px;
  line-height: 20px;
  padding: 0;
}

.expert-wysiwyg :deep(.expert-wysiwyg__preview-input-text) {
  min-height: 32px;
  color: #1f2937;
  font-size: 14px;
  line-height: 1.55;
  padding: 2px 0;
}

.expert-wysiwyg :deep(.expert-wysiwyg__card-label) {
  min-height: 20px;
  color: #475569;
  font-size: 13px;
  font-weight: 700;
  padding: 0;
}

.expert-wysiwyg__output-intro {
  flex: 0 0 auto;
  min-height: 32px;
}

.expert-wysiwyg__output-intro--muted,
.expert-wysiwyg__output-intro--muted :deep(.expert-rich-text-field__editor) {
  color: #6b7280;
}

.expert-wysiwyg__output-intro--accent,
.expert-wysiwyg__output-intro--accent :deep(.expert-rich-text-field__editor) {
  color: #1d4ed8;
  font-weight: 600;
}

.expert-wysiwyg :deep(.expert-wysiwyg__output-intro-field) {
  min-height: 32px;
  line-height: 1.55;
  padding: 2px 0;
}

.expert-wysiwyg :deep(.expert-wysiwyg__output-paragraph) {
  min-height: 32px;
  flex: 1;
  color: #1f2937;
  line-height: 1.55;
  padding: 2px 0;
}

.expert-wysiwyg :deep(.expert-wysiwyg__identity-title) {
  min-height: 34px;
  padding: 0;
}

.expert-wysiwyg :deep(.expert-wysiwyg__description),
.expert-wysiwyg :deep(.expert-wysiwyg__usage-guide) {
  min-height: 52px;
  color: #475569;
  font-size: 14px;
  line-height: 1.55;
  padding: 3px 0;
}

.expert-wysiwyg :deep(.expert-wysiwyg__prompt-input) {
  min-height: 28px;
  flex: 1;
  font-size: 13px;
  padding: 3px 0;
}

.expert-wysiwyg input:focus,
.expert-wysiwyg textarea:focus {
  border-color: #93c5fd;
  background: #eff6ff;
}

.expert-wysiwyg input.has-error,
.expert-wysiwyg textarea.has-error {
  border-color: #f87171;
}

.expert-wysiwyg__identity-fields > input,
.expert-wysiwyg__identity-fields > .expert-rich-text-field {
  font-size: 26px;
  font-weight: 700;
}

.expert-wysiwyg__tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
}

.expert-wysiwyg__tag,
.expert-wysiwyg__runtime-tag {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  border-radius: 999px;
  background: #e8eef8;
  color: #475569;
  font-size: 11px;
  padding: 4px 8px;
}

.expert-wysiwyg__runtime-tag {
  background: #dbeafe;
  color: #2563eb;
}

.expert-wysiwyg__tag button {
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  padding: 0 2px;
}

.expert-wysiwyg__tags input {
  width: 76px;
  font-size: 11px;
}

.expert-wysiwyg__tag-adder {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.expert-wysiwyg__tag-add {
  display: inline-grid;
  width: 22px;
  height: 22px;
  flex: 0 0 auto;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: #2563eb;
  color: white;
  cursor: pointer;
  line-height: 1;
  padding: 0;
}

.expert-wysiwyg__tag-add:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.expert-wysiwyg__text-block {
  padding: 5px 0;
}

.expert-wysiwyg__text-block textarea {
  min-height: 52px;
  color: #475569;
  font-size: 14px;
  line-height: 1.55;
}

.expert-wysiwyg__text-block h4,
.expert-wysiwyg__prompt-preview h4 {
  margin: 0 0 5px;
  color: #111827;
  font-size: 17px;
}

.expert-wysiwyg__prompt-preview {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.expert-wysiwyg__section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.expert-wysiwyg__section-heading h4 {
  margin-bottom: 0;
}

.expert-wysiwyg__inline-add,
.expert-wysiwyg__preview-output > button {
  flex: 0 0 auto;
  border: 1px solid #bfdbfe;
  border-radius: 6px;
  background: #eff6ff;
  color: #2563eb;
  cursor: pointer;
  font-size: 11px;
  padding: 4px 8px;
}

.expert-wysiwyg__inline-add:disabled,
.expert-wysiwyg__preview-output > button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.expert-wysiwyg__table-editor {
  max-width: 100%;
  overflow-x: auto;
  border: 1px solid #dfe3e8;
  border-radius: 10px;
  background: #fff;
}

.expert-wysiwyg__table-editor table {
  width: 100%;
  min-width: 420px;
  border-collapse: separate;
  border-spacing: 0;
  table-layout: fixed;
}

.expert-wysiwyg__table-editor th,
.expert-wysiwyg__table-editor td {
  border-right: 1px solid #dfe3e8;
  border-bottom: 1px solid #dfe3e8;
  padding: 5px 7px;
}

.expert-wysiwyg__table-editor th:last-child,
.expert-wysiwyg__table-editor td:last-child {
  border-right: 0;
}

.expert-wysiwyg__table-editor tr:last-child td {
  border-bottom: 0;
}

.expert-wysiwyg__table-editor th {
  background: #f0f0f0;
  color: #6b6b6b;
  font-size: 12px;
  font-weight: 700;
}

.expert-wysiwyg__table-editor tr.is-total td {
  font-weight: 700;
}

.expert-wysiwyg__table-editor input {
  min-width: 0;
  border-radius: 4px;
  padding: 3px;
}

.expert-wysiwyg__prompt-card {
  display: flex;
  align-items: center;
  gap: 5px;
  min-height: 42px;
  border-radius: 7px;
  background: #f8fafc;
  padding: 3px 7px;
}

.expert-wysiwyg__prompt-card input {
  min-width: 0;
  font-size: 13px;
}

.expert-wysiwyg__prompt-card > .expert-rich-text-field,
.expert-wysiwyg__ordered-row > .expert-rich-text-field {
  min-width: 0;
  flex: 1;
}

.expert-wysiwyg__prompt-card button,
.expert-wysiwyg__tag button {
  flex: 0 0 auto;
}

.expert-wysiwyg__prompt-card button {
  width: 22px;
  height: 22px;
  border: 0;
  border-radius: 5px;
  background: #eef2f7;
  color: #64748b;
  cursor: pointer;
  line-height: 1;
  padding: 0;
}

.expert-wysiwyg__prompt-card button:disabled {
  cursor: not-allowed;
  opacity: 0.4;
}

.expert-wysiwyg__preview-input textarea,
.expert-wysiwyg__preview-output textarea {
  min-height: 32px;
}

.expert-wysiwyg__ordered-row {
  display: flex;
  align-items: flex-start;
  flex: 0 0 auto;
  gap: 5px;
}

.expert-wysiwyg__ordered-row textarea {
  flex: 1;
}

.expert-wysiwyg__order-actions {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.expert-wysiwyg__order-actions button {
  width: 22px;
  height: 22px;
  border: 0;
  border-radius: 5px;
  background: #eef2f7;
  color: #64748b;
  cursor: pointer;
  line-height: 1;
  padding: 0;
}

.expert-wysiwyg__order-actions button:disabled {
  cursor: not-allowed;
  opacity: 0.4;
}

.expert-wysiwyg__footer {
  display: flex;
  gap: 8px;
  margin-top: auto;
  padding-top: 5px;
}

.expert-wysiwyg__footer button {
  min-height: 48px;
  flex: 1;
  border: 0;
  border-radius: 8px;
  background: #e5e7eb;
  color: #6b7280;
  cursor: not-allowed;
  font-size: 12px;
}

.expert-wysiwyg__footer button:first-child {
  background: #2563eb;
  color: white;
}

.expert-wysiwyg__placeholder {
  margin: 0;
  color: #94a3b8;
  font-size: 12px;
}

.expert-wysiwyg__validation {
  margin: 0;
  color: #b91c1c;
  font-size: 12px;
}

@media (max-width: 760px) {
  .expert-wysiwyg__toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .expert-wysiwyg__floating-preview {
    top: 56px;
    right: 8px;
    width: calc(100vw - 16px);
  }
}
</style>
