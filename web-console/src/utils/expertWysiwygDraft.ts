import type {
  ExpertImageLocale,
  ExpertExamplePreview,
  ExpertPresentationPayload,
  SkillAdminDetail,
  SkillAdminListItem,
} from '../api/admin'

export type ExpertEditorLocale = 'zh' | 'en'
export type ExpertImageRole = 'illustration' | 'icon' | 'exampleImage'

export interface ExpertWysiwygAsset {
  url: string
  fileName?: string
  pendingClear?: boolean
  /** The locale whose structured request/material this image replaces. */
  locale?: ExpertImageLocale | null
}

/**
 * Optional, presentation-only canvas data.  The plain text fields remain the
 * canonical editable values; runs only carry visual intent so old gateways and
 * old YCPS packages can continue to round-trip without understanding them.
 */
export interface ExpertCanvasTextRun {
  text: string
  color?: string
  fontSize?: number
  fontWeight?: number
  italic?: boolean
}

/**
 * The table is semantic content first.  Its compact style is intentionally
 * kept beside the table so the desktop renderer and YCPS can reproduce the
 * same component without depending on editor-only HTML.
 */
export interface ExpertWysiwygTableStyle {
  fontSize?: number
  color?: string
  headerColor?: string
  headerBackgroundColor?: string
  borderColor?: string
  borderRadius?: number
  cellPadding?: number
  rowHeight?: number
}

export interface ExpertWysiwygTable {
  headers: string[]
  columnWidths: number[]
  rows: Array<Array<string | number>>
  totalRow?: boolean
  style?: ExpertWysiwygTableStyle
}

export interface ExpertCanvasNode {
  id: string
  field: string
  kind: 'text' | 'card' | 'image' | 'tag' | 'table'
  x: number
  y: number
  width: number
  height: number
  runs?: ExpertCanvasTextRun[]
  style?: {
    color?: string
    backgroundColor?: string
    borderColor?: string
    borderRadius?: number
    overflow?: 'visible' | 'auto'
  }
  assetRole?: ExpertImageRole
  table?: ExpertWysiwygTable
}

export interface ExpertCanvas {
  version: 1
  width: 1080
  height: 704
  nodes: ExpertCanvasNode[]
}

export interface ExpertWysiwygDraft {
  title: Record<ExpertEditorLocale, string>
  description: Record<ExpertEditorLocale, string>
  usageGuide: Record<ExpertEditorLocale, string>
  taskTypes: Record<ExpertEditorLocale, string[]>
  examplePrompts: Record<ExpertEditorLocale, string[]>
  preview: Record<ExpertEditorLocale, ExpertWysiwygPreview>
  canvas: Record<ExpertEditorLocale, ExpertCanvas | null>
  media: Record<ExpertImageRole, ExpertWysiwygAsset>
  advanced: {
    summary: Record<ExpertEditorLocale, string>
    category: string
    icon: string
    sortWeight: number
    minClientVersion: string
  }
}

export interface ExpertWysiwygPreview {
  heading: string
  headingPrefix?: string
  scene: string
  request: string
  material: string
  outputTitle: string
  outputParagraphs: string[]
  intro?: { tone?: string; text: string }
  table?: ExpertWysiwygTable
  sections?: Array<{ title: string; blocks: Array<{ tone?: string; text: string }> }>
  blocks?: Array<{ tone?: string; text: string }>
}

export interface ExpertWysiwygFieldError {
  field: string
  message: string
  locale?: ExpertEditorLocale
  index?: number
}

export const EXPERT_WYSIWYG_LIMITS = {
  title: 128,
  description: 1_024,
  summary: 512,
  usageGuide: 1_024,
  taskTypeCount: 6,
  taskType: 64,
  promptCount: 8,
  prompt: 300,
  metadataValue: 1_024,
  previewParagraphCount: 8,
  previewHeading: 128,
  previewScene: 300,
  previewRequest: 1_024,
  previewMaterial: 1_024,
  previewOutputTitle: 128,
  previewIntro: 1_024,
  previewParagraph: 1_024,
  tableColumnCount: 8,
  tableRowCount: 32,
  tableCell: 1_024,
  category: 32,
  icon: 8,
} as const

const LOCALES: ExpertEditorLocale[] = ['zh', 'en']

const EXPERT_CANVAS_WIDTH = 1080
const EXPERT_CANVAS_HEIGHT = 704
const PREVIEW_PARAGRAPH_START_Y = 364
const PREVIEW_PARAGRAPH_MAX_HEIGHT = 38
const PREVIEW_PARAGRAPH_GAP = 4
const PROMPT_START_Y = 452
const PROMPT_MAX_HEIGHT = 42
const PROMPT_GAP = 4

const text = (value: unknown): string => typeof value === 'string' ? value : ''

const list = (value: unknown): string[] => Array.isArray(value)
  ? value.filter((item): item is string => typeof item === 'string').map(item => item.trim()).filter(Boolean)
  : []

const localeText = (value: unknown, fallback = ''): Record<ExpertEditorLocale, string> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return { zh: fallback, en: '' }
  }
  const record = value as Record<string, unknown>
  return { zh: text(record.zh), en: text(record.en) }
}

const localeList = (value: unknown, fallback: string[] = []): Record<ExpertEditorLocale, string[]> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return { zh: [...fallback], en: [] }
  }
  const record = value as Record<string, unknown>
  return { zh: list(record.zh), en: list(record.en) }
}

const emptyPreview = (): ExpertWysiwygPreview => ({
  heading: '',
  scene: '',
  request: '',
  material: '',
  outputTitle: '',
  outputParagraphs: [],
})

function normalizeTable(value: unknown): ExpertWysiwygTable | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined
  const row = value as Record<string, unknown>
  if (!Array.isArray(row.headers)) return undefined
  const rows = Array.isArray(row.rows)
    ? row.rows.filter(Array.isArray).map(cells => cells.map(cell => (
      typeof cell === 'number' && Number.isFinite(cell) ? cell : text(cell)
    )))
    : []
  const rawStyle = row.style && typeof row.style === 'object' && !Array.isArray(row.style)
    ? row.style as Record<string, unknown>
    : undefined
  const finiteStyleNumber = (value: unknown): number | undefined => (
    typeof value === 'number' && Number.isFinite(value) ? value : undefined
  )
  const style = rawStyle ? {
    ...(finiteStyleNumber(rawStyle.fontSize) !== undefined ? { fontSize: finiteStyleNumber(rawStyle.fontSize) } : {}),
    ...(typeof rawStyle.color === 'string' ? { color: rawStyle.color } : {}),
    ...(typeof rawStyle.headerColor === 'string' ? { headerColor: rawStyle.headerColor } : {}),
    ...(typeof rawStyle.headerBackgroundColor === 'string' ? { headerBackgroundColor: rawStyle.headerBackgroundColor } : {}),
    ...(typeof rawStyle.borderColor === 'string' ? { borderColor: rawStyle.borderColor } : {}),
    ...(finiteStyleNumber(rawStyle.borderRadius) !== undefined ? { borderRadius: finiteStyleNumber(rawStyle.borderRadius) } : {}),
    ...(finiteStyleNumber(rawStyle.cellPadding) !== undefined ? { cellPadding: finiteStyleNumber(rawStyle.cellPadding) } : {}),
    ...(finiteStyleNumber(rawStyle.rowHeight) !== undefined ? { rowHeight: finiteStyleNumber(rawStyle.rowHeight) } : {}),
  } : undefined
  return {
    headers: (row.headers as unknown[]).map(item => text(item)),
    columnWidths: Array.isArray(row.columnWidths)
      ? (row.columnWidths as unknown[]).map(Number).filter(Number.isFinite)
      : [],
    rows,
    ...(row.totalRow === true ? { totalRow: true } : {}),
    ...(style && Object.keys(style).length ? { style } : {}),
  }
}

const preview = (value: unknown): Record<ExpertEditorLocale, ExpertWysiwygPreview> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return { zh: emptyPreview(), en: emptyPreview() }
  }
  const record = value as Record<string, unknown>
  const normalize = (item: unknown): ExpertWysiwygPreview => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return emptyPreview()
    const row = item as Record<string, unknown>
    return {
      heading: text(row.heading),
      ...(text(row.headingPrefix) ? { headingPrefix: text(row.headingPrefix) } : {}),
      scene: text(row.scene),
      request: text(row.request ?? row.input),
      material: text(row.material),
      outputTitle: text(row.outputTitle),
      outputParagraphs: list(row.outputParagraphs ?? (row.output ? [row.output] : [])),
      ...(row.intro && typeof row.intro === 'object' && typeof (row.intro as Record<string, unknown>).text === 'string'
        ? { intro: { text: String((row.intro as Record<string, unknown>).text), ...((row.intro as Record<string, unknown>).tone ? { tone: String((row.intro as Record<string, unknown>).tone) } : {}) } }
        : {}),
      ...(row.table && typeof row.table === 'object' && Array.isArray((row.table as Record<string, unknown>).headers)
        ? { table: normalizeTable(row.table) } : {}),
      ...(Array.isArray(row.sections) ? { sections: row.sections.filter(item => item && typeof item === 'object').map(item => {
        const section = item as Record<string, unknown>
        return { title: text(section.title), blocks: Array.isArray(section.blocks) ? section.blocks.filter(block => block && typeof block === 'object').map(block => {
          const value = block as Record<string, unknown>
          return { text: text(value.text), ...(text(value.tone) ? { tone: text(value.tone) } : {}) }
        }) : [] }
      }) } : {}),
      ...(Array.isArray(row.blocks) ? { blocks: row.blocks.filter(item => item && typeof item === 'object').map(item => {
        const block = item as Record<string, unknown>
        return { text: text(block.text), ...(text(block.tone) ? { tone: text(block.tone) } : {}) }
      }) } : {}),
    }
  }
  return { zh: normalize(record.zh), en: normalize(record.en) }
}

const IMAGE_LOCALES: ExpertImageLocale[] = ['zh', 'en', 'neutral']

function imageLocale(value: unknown): ExpertImageLocale | null | undefined {
  if (value === null) return null
  return typeof value === 'string' && IMAGE_LOCALES.includes(value as ExpertImageLocale)
    ? value as ExpertImageLocale
    : undefined
}

const asset = (value: unknown, legacyLocale?: ExpertImageLocale): ExpertWysiwygAsset => {
  if (typeof value === 'string') {
    return {
      url: value,
      ...(value && legacyLocale ? { locale: legacyLocale } : {}),
    }
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return { url: '' }
  const record = value as Record<string, unknown>
  const url = text(record.url)
  const locale = Object.prototype.hasOwnProperty.call(record, 'locale')
    ? imageLocale(record.locale)
    : (url && legacyLocale ? legacyLocale : undefined)
  return {
    url,
    ...(text(record.fileName) ? { fileName: text(record.fileName) } : {}),
    ...(record.pendingClear === true ? { pendingClear: true } : {}),
    ...(locale !== undefined ? { locale } : {}),
  }
}

function previewParagraphGeometry(count: number, index: number): { y: number; height: number } {
  const itemCount = Math.max(1, count)
  const availableHeight = EXPERT_CANVAS_HEIGHT - PREVIEW_PARAGRAPH_START_Y
  const minimumGap = itemCount > 1 ? PREVIEW_PARAGRAPH_GAP : 0
  const height = Math.max(
    1,
    Math.min(
      PREVIEW_PARAGRAPH_MAX_HEIGHT,
      Math.floor((availableHeight - minimumGap * Math.max(0, itemCount - 1)) / itemCount),
    ),
  )
  const gap = itemCount > 1
    ? Math.max(0, Math.min(PREVIEW_PARAGRAPH_GAP, Math.floor((availableHeight - height * itemCount) / (itemCount - 1))))
    : 0
  return { y: PREVIEW_PARAGRAPH_START_Y + index * (height + gap), height }
}

function promptGeometry(count: number, index: number): { y: number; height: number } {
  const itemCount = Math.max(1, count)
  const availableHeight = EXPERT_CANVAS_HEIGHT - PROMPT_START_Y
  const minimumGap = itemCount > 1 ? PROMPT_GAP : 0
  const height = Math.max(
    1,
    Math.min(
      PROMPT_MAX_HEIGHT,
      Math.floor((availableHeight - minimumGap * Math.max(0, itemCount - 1)) / itemCount),
    ),
  )
  const gap = itemCount > 1
    ? Math.max(0, Math.min(PROMPT_GAP, Math.floor((availableHeight - height * itemCount) / (itemCount - 1))))
    : 0
  return { y: PROMPT_START_Y + index * (height + gap), height }
}

/**
 * Older Expert profiles encoded a table as pipe-delimited output paragraphs.
 * Keep that semantic representation authoritative: a generated canvas can only
 * paint plain text and would turn the table into a duplicated/raw overlay.
 */
function hasLegacyPipeTable(paragraphs: string[]): boolean {
  const delimiterFor = (item: string): string | undefined => {
    if (item.split('｜').length >= 3) return '｜'
    if (item.split('|').length >= 3) return '|'
    return undefined
  }
  const start = paragraphs.findIndex(item => delimiterFor(item) !== undefined)
  if (start < 0) return false
  const delimiter = delimiterFor(paragraphs[start])
  if (!delimiter) return false
  let end = start
  while (end < paragraphs.length && delimiterFor(paragraphs[end]) === delimiter) end += 1
  return end - start >= 2
}

/**
 * Structured previews are rendered by the desktop semantic renderer.  They
 * should not receive a generated canvas when no layout exists, but an existing
 * layout remains an independent YCPS presentation value and must round-trip.
 */
function usesStructuredPreview(preview: ExpertWysiwygPreview): boolean {
  return preview.intro !== undefined
    || preview.table !== undefined
    || preview.sections !== undefined
    || preview.blocks !== undefined
    || hasLegacyPipeTable(preview.outputParagraphs)
}

/** Stable fallback geometry used when an older profile has no canvas snapshot yet. */
export function createExpertCanvas(draft: ExpertWysiwygDraft, language: ExpertEditorLocale): ExpertCanvas {
  const textNode = (field: string, kind: ExpertCanvasNode['kind'], x: number, y: number, width: number, height: number, textValue: string): ExpertCanvasNode => ({
    id: field.replace(/[^a-z0-9]+/gi, '-'), field, kind, x, y, width, height,
    runs: textValue ? [{ text: textValue }] : [],
  })
  const imageNode = (field: string, assetRole: ExpertImageRole, x: number, y: number, width: number, height: number): ExpertCanvasNode => ({
    id: field.replace(/[^a-z0-9]+/gi, '-'), field, kind: 'image', x, y, width, height, assetRole,
  })
  const preview = draft.preview[language]
  const introNode = preview.intro
    ? textNode('preview.intro', 'text', 48, 364, 520, 38, preview.intro.text)
    : undefined
  const tableNode = preview.table
    ? {
      id: 'preview-table',
      field: 'preview.table',
      kind: 'table' as const,
      x: 48,
      y: 364,
      width: 520,
      // This node is a presentation anchor for clients that understand the
      // canvas contract.  The semantic table remains authoritative and can
      // scroll when its content is taller than the fixed detail page.
      height: Math.min(260, Math.max(44, 34 + (preview.table.rows.length + 1) * 32)),
      style: {
        color: preview.table.style?.color ?? '#303238',
        backgroundColor: '#ffffff',
        borderColor: preview.table.style?.borderColor ?? '#dfe3e8',
        borderRadius: preview.table.style?.borderRadius ?? 10,
        overflow: 'auto' as const,
      },
      table: {
        headers: [...preview.table.headers],
        columnWidths: [...preview.table.columnWidths],
        rows: preview.table.rows.map(row => [...row]),
        ...(preview.table.totalRow ? { totalRow: true } : {}),
        ...(preview.table.style ? { style: { ...preview.table.style } } : {}),
      },
      runs: [{
        text: preview.table.headers.join(' | ') || 'Table',
        ...(preview.table.style?.fontSize ? { fontSize: preview.table.style.fontSize } : {}),
        ...(preview.table.style?.headerColor ? { color: preview.table.style.headerColor } : {}),
      }],
    }
    : undefined
  return {
    version: 1,
    width: EXPERT_CANVAS_WIDTH,
    height: EXPERT_CANVAS_HEIGHT,
    nodes: [
      textNode('preview.heading', 'text', 32, 28, 560, 42, preview.heading),
      textNode('preview.scene', 'text', 32, 78, 560, 28, preview.scene),
      textNode('preview.request', 'card', 32, 116, 560, 110, preview.request),
      textNode('preview.material', 'card', 32, 226, 560, 48, preview.material),
      textNode('preview.outputTitle', 'text', 48, 330, 520, 28, preview.outputTitle),
      ...(introNode ? [introNode] : []),
      textNode('title', 'text', 710, 150, 338, 36, draft.title[language]),
      textNode('description', 'text', 638, 278, 410, 64, draft.description[language]),
      textNode('usageGuide', 'text', 638, 350, 410, 90, draft.usageGuide[language]),
      ...draft.taskTypes[language].map((tag, index) => textNode(
        `tag.${index}`, 'tag', 710 + (index % 2) * 170, 192 + Math.floor(index / 2) * 26, 160, 22, tag,
      )),
      ...draft.examplePrompts[language].map((prompt, index) => {
        const geometry = promptGeometry(draft.examplePrompts[language].length, index)
        return textNode(`prompt.${index}`, 'card', 638, geometry.y, 410, geometry.height, prompt)
      }),
      ...preview.outputParagraphs.map((paragraph, index) => {
        const geometry = previewParagraphGeometry(preview.outputParagraphs.length, index)
        return textNode(`preview.outputParagraphs.${index}`, 'text', 48, geometry.y, 520, geometry.height, paragraph)
      }),
      ...(tableNode ? [tableNode] : []),
      imageNode('asset.illustration', 'illustration', 638, 0, 410, 136),
      imageNode('asset.icon', 'icon', 638, 150, 58, 58),
    ],
  }
}

/**
 * Repair the first-party canvas shape while keeping presentation-only content.
 *
 * The canvas is additive metadata, so older records can contain a snapshot from
 * before `preview.outputTitle` existed or before the feature image was moved out
 * of the main canvas.  Only defective/overlapping known nodes are moved to the
 * current safe geometry; an otherwise valid imported layout keeps its custom
 * positions.  Existing runs and styles are copied through unchanged so an
 * edit/save round trip does not flatten rich text.
 */
export function repairExpertCanvas(
  draft: ExpertWysiwygDraft,
  language: ExpertEditorLocale,
  existing: ExpertCanvas | null | undefined = draft.canvas[language],
): ExpertCanvas {
  const fallback = createExpertCanvas(draft, language)
  if (!existing) return fallback

  const knownFields = new Map(fallback.nodes.map(node => [node.field, node]))
  const seen = new Set<string>()
  const clamp = (value: number, minimum: number, maximum: number) => Math.max(minimum, Math.min(maximum, value))
  const records: Array<{ node: ExpertCanvasNode; template?: ExpertCanvasNode }> = []

  for (const node of existing.nodes) {
    // The feature image is intentionally managed by the independent asset panel;
    // it must never be persisted as a child of the left preview canvas.
    if (node.field === 'asset.exampleImage' || node.assetRole === 'exampleImage') continue
    if (node.field === 'preview.table' && !draft.preview[language].table) continue

    const paragraphMatch = /^preview\.outputParagraphs\.(\d+)$/.exec(node.field)
    if (paragraphMatch && Number(paragraphMatch[1]) >= draft.preview[language].outputParagraphs.length) continue
    const promptMatch = /^prompt\.(\d+)$/.exec(node.field)
    if (promptMatch && Number(promptMatch[1]) >= draft.examplePrompts[language].length) continue
    const tagMatch = /^tag\.(\d+)$/.exec(node.field)
    if (tagMatch && Number(tagMatch[1]) >= draft.taskTypes[language].length) continue

    const template = knownFields.get(node.field)
    if (template && seen.has(node.field)) continue
    if (template) seen.add(node.field)
    records.push({ node, ...(template ? { template } : {}) })
  }

  // Add newly introduced fields and fields added after a legacy snapshot.  A
  // fallback run is only used for a missing node; existing rich runs win above.
  for (const node of fallback.nodes) {
    if (seen.has(node.field)) continue
    seen.add(node.field)
    records.push({ node: { ...node, runs: node.runs?.map(run => ({ ...run })) }, template: node })
  }

  const withinCanvas = (node: ExpertCanvasNode): boolean => (
    Number.isFinite(node.x) && Number.isFinite(node.y)
    && Number.isFinite(node.width) && Number.isFinite(node.height)
    && node.x >= 0 && node.y >= 0 && node.width >= 0 && node.height >= 0
    && node.x + node.width <= EXPERT_CANVAS_WIDTH
    && node.y + node.height <= EXPERT_CANVAS_HEIGHT
  )
  const overlaps = (left: ExpertCanvasNode, right: ExpertCanvasNode): boolean => (
    left.x < right.x + right.width
    && left.x + left.width > right.x
    && left.y < right.y + right.height
    && left.y + left.height > right.y
  )
  const needsGeometryRepair = new Set<string>()
  const knownRecords = records.filter(record => record.template)
  knownRecords.forEach(record => {
    if (!withinCanvas(record.node)) needsGeometryRepair.add(record.node.field)
  })
  for (let left = 0; left < knownRecords.length; left += 1) {
    for (let right = left + 1; right < knownRecords.length; right += 1) {
      const first = knownRecords[left].node
      const second = knownRecords[right].node
      if (overlaps(first, second)) {
        needsGeometryRepair.add(first.field)
        needsGeometryRepair.add(second.field)
      }
    }
  }

  // A single bad item in a variable-length flow can leave the remaining items
  // colliding after it is moved. Reflow that whole flow deterministically.
  const reflowGroup = (prefix: string) => {
    if (!records.some(record => needsGeometryRepair.has(record.node.field) && record.node.field.startsWith(prefix))) return
    records.forEach(record => {
      if (record.node.field.startsWith(prefix)) needsGeometryRepair.add(record.node.field)
    })
  }
  reflowGroup('preview.outputParagraphs.')
  reflowGroup('prompt.')
  reflowGroup('tag.')

  const repairedNodes = records.map(({ node, template }) => {
    if (template && needsGeometryRepair.has(node.field)) {
      return { ...node, x: template.x, y: template.y, width: template.width, height: template.height }
    }
    if (withinCanvas(node)) return node
    const x = clamp(Number.isFinite(node.x) ? node.x : 0, 0, EXPERT_CANVAS_WIDTH)
    const y = clamp(Number.isFinite(node.y) ? node.y : 0, 0, EXPERT_CANVAS_HEIGHT)
    return {
      ...node,
      x,
      y,
      width: clamp(Number.isFinite(node.width) ? node.width : 0, 0, EXPERT_CANVAS_WIDTH - x),
      height: clamp(Number.isFinite(node.height) ? node.height : 0, 0, EXPERT_CANVAS_HEIGHT - y),
    }
  })

  return {
    version: 1,
    width: EXPERT_CANVAS_WIDTH,
    height: EXPERT_CANVAS_HEIGHT,
    nodes: repairedNodes,
  }
}

const canvas = (value: unknown): Record<ExpertEditorLocale, ExpertCanvas | null> => {
  const empty = { zh: null, en: null }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return empty
  const record = value as Record<string, unknown>
  const normalize = (item: unknown): ExpertCanvas | null => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return null
    const row = item as Record<string, unknown>
    if (row.version !== 1 || row.width !== 1080 || row.height !== 704 || !Array.isArray(row.nodes)) return null
    const nodes = row.nodes.filter((node): node is Record<string, unknown> => (
      !!node && typeof node === 'object' && !Array.isArray(node)
    )).map((node) => {
      const rawRuns = Array.isArray(node.runs) ? node.runs : []
      const runs = rawRuns.filter((run): run is Record<string, unknown> => (
        !!run && typeof run === 'object' && !Array.isArray(run) && typeof run.text === 'string'
      )).map((run) => ({
        text: String(run.text),
        ...(typeof run.color === 'string' ? { color: run.color } : {}),
        ...(Number.isFinite(Number(run.fontSize)) ? { fontSize: Number(run.fontSize) } : {}),
        ...(Number.isFinite(Number(run.fontWeight)) ? { fontWeight: Number(run.fontWeight) } : {}),
        ...(run.italic === true ? { italic: true } : {}),
      }))
      const style = node.style && typeof node.style === 'object' && !Array.isArray(node.style)
        ? node.style as Record<string, unknown>
        : undefined
      return {
        id: typeof node.id === 'string' ? node.id : `node-${Math.random().toString(36).slice(2)}`,
        field: typeof node.field === 'string' ? node.field : '',
        kind: (node.kind === 'image' || node.kind === 'card' || node.kind === 'tag' || node.kind === 'table' ? node.kind : 'text') as ExpertCanvasNode['kind'],
        x: Number(node.x) || 0,
        y: Number(node.y) || 0,
        width: Number(node.width) || 0,
        height: Number(node.height) || 0,
        ...(runs.length ? { runs } : {}),
        ...(style ? { style: {
          ...(typeof style.color === 'string' ? { color: style.color } : {}),
          ...(typeof style.backgroundColor === 'string' ? { backgroundColor: style.backgroundColor } : {}),
          ...(typeof style.borderColor === 'string' ? { borderColor: style.borderColor } : {}),
          ...(Number.isFinite(Number(style.borderRadius)) ? { borderRadius: Number(style.borderRadius) } : {}),
          ...(style.overflow === 'auto' || style.overflow === 'visible'
            ? { overflow: style.overflow as 'auto' | 'visible' }
            : {}),
        } } : {}),
        ...(typeof node.assetRole === 'string' ? { assetRole: node.assetRole as ExpertImageRole } : {}),
        ...(node.kind === 'table' && node.table ? { table: normalizeTable(node.table) } : {}),
      }
    })
    return { version: 1, width: 1080, height: 704, nodes }
  }
  return { zh: normalize(record.zh), en: normalize(record.en) }
}

export function createExpertWysiwygDraft(source?: Partial<ExpertWysiwygDraft>): ExpertWysiwygDraft {
  const value = source ?? {}
  const title = localeText(value.title)
  const description = localeText(value.description)
  const usageGuide = localeText(value.usageGuide)
  const taskTypes = localeList(value.taskTypes)
  const examplePrompts = localeList(value.examplePrompts)
  const previewValue = preview(value.preview)
  const canvasValue = canvas(value.canvas)
  const mediaSource = value.media && typeof value.media === 'object' ? value.media : {}
  const media = mediaSource as Partial<Record<ExpertImageRole, unknown>>
  const advanced = value.advanced && typeof value.advanced === 'object' ? value.advanced : {}

  const draft: ExpertWysiwygDraft = {
    title,
    description,
    usageGuide,
    taskTypes,
    examplePrompts,
    preview: previewValue,
    canvas: canvasValue,
    media: {
      illustration: asset(media.illustration),
      icon: asset(media.icon),
      // YCPS 1.0 and old cloud profiles had one Chinese example image with no
      // language marker. Keep that historical rendering while new uploads set
      // an explicit locale in the editor.
      exampleImage: asset(media.exampleImage, 'zh'),
    },
    advanced: {
      summary: localeText((advanced as Record<string, unknown>).summary),
      category: text((advanced as Record<string, unknown>).category),
      icon: text((advanced as Record<string, unknown>).icon),
      sortWeight: Number.isFinite(Number((advanced as Record<string, unknown>).sortWeight))
        ? Number((advanced as Record<string, unknown>).sortWeight)
        : 0,
      minClientVersion: text((advanced as Record<string, unknown>).minClientVersion),
    },
  }

  return draft
}

/** Convert the existing admin response to the additive WYSIWYG draft. */
export function expertDraftFromAdmin(
  source: Partial<SkillAdminDetail | SkillAdminListItem> | Record<string, unknown> | null | undefined,
): ExpertWysiwygDraft {
  const value = (source ?? {}) as Partial<SkillAdminDetail> & Record<string, unknown>
  const record = value
  // Presence matters for migration: old admin responses may omit the canonical
  // field, but the reserved YCPS transport marker is never a business label.
  // New call sites always pass taskTypes/taskTypesEn explicitly; this narrow
  // bridge only keeps non-reserved legacy labels readable.
  const legacyTaskTypes = list(record.tags).filter(tag => tag.toLocaleLowerCase() !== 'ycps')
  const legacyTaskTypesEn = list(record.tagsEn).filter(tag => tag.toLocaleLowerCase() !== 'ycps')
  const fallbackTaskTypes = record.taskTypes === undefined ? legacyTaskTypes : record.taskTypes
  const fallbackTaskTypesEn = record.taskTypesEn === undefined ? legacyTaskTypesEn : record.taskTypesEn
  const legacyPreview = (locale: ExpertEditorLocale): ExpertWysiwygPreview => ({
    heading: '',
    scene: '',
    request: text(locale === 'zh' ? record.examplePreviewInput : record.examplePreviewInputEn),
    material: '',
    outputTitle: '',
    outputParagraphs: (() => {
      const value = locale === 'zh' ? record.examplePreviewOutput : record.examplePreviewOutputEn
      return typeof value === 'string' && value.trim() ? [value.trim()] : list(value)
    })(),
  })
  // As with taskTypes, an explicitly null preview is a clear and must not
  // resurrect the legacy scalar bridge. Only an absent field falls back.
  // The dedicated profile's `preview` shape wins when both generations are
  // present; the flat examplePreview fields are only a legacy fallback.
  const structuredPreviewValue = record.preview !== undefined
    ? record.preview
    : record.examplePreview
  const structuredPreviewEnValue = record.previewEn !== undefined
    ? record.previewEn
    : record.examplePreviewEn
  const structuredPreview = structuredPreviewValue === undefined
    ? null
    : structuredPreviewValue && typeof structuredPreviewValue === 'object'
      ? structuredPreviewValue as Record<string, unknown>
      : null
  const structuredPreviewEn = structuredPreviewEnValue === undefined
    ? null
    : structuredPreviewEnValue && typeof structuredPreviewEnValue === 'object'
      ? structuredPreviewEnValue as Record<string, unknown>
      : null
  const previewZh = structuredPreviewValue === undefined
    ? legacyPreview('zh')
    : (structuredPreview ?? {}) as unknown as ExpertWysiwygPreview
  const previewEn = structuredPreviewEnValue === undefined
    ? legacyPreview('en')
    : (structuredPreviewEn ?? {}) as unknown as ExpertWysiwygPreview
  // `undefined` means the additive field is absent (including an explicitly
  // present-but-undefined property); null and '' are intentional clears and
  // must not fall back to the legacy summary.
  const hasDescription = record.description !== undefined
  const hasDescriptionEn = record.descriptionEn !== undefined
  return createExpertWysiwygDraft({
    title: { zh: text(value.name ?? record.title), en: text(value.nameEn ?? record.titleEn) },
    description: {
      zh: hasDescription ? text(record.description) : text(value.summary),
      en: hasDescriptionEn ? text(record.descriptionEn) : text(value.summaryEn),
    },
    usageGuide: { zh: text(value.usageGuide), en: text(value.usageGuideEn) },
    taskTypes: { zh: list(fallbackTaskTypes), en: list(fallbackTaskTypesEn) },
    examplePrompts: { zh: list(value.examplePrompts), en: list(value.examplePromptsEn) },
    preview: { zh: previewZh, en: previewEn },
    canvas: canvas(value.canvas),
    media: {
      icon: { url: text(value.iconUrl) },
      illustration: { url: text(value.illustrationUrl) },
      exampleImage: {
        url: text(value.exampleImageUrl),
        ...(Object.prototype.hasOwnProperty.call(record, 'exampleImageLocale')
          ? { locale: imageLocale(record.exampleImageLocale) }
          : {}),
      },
    },
    advanced: {
      summary: { zh: text(value.summary), en: text(value.summaryEn) },
      category: text(value.category),
      icon: text(value.icon),
      sortWeight: Number(value.sortWeight ?? 0),
      minClientVersion: text(value.minClientVersion),
    },
  })
}

/** Return only additive fields so the existing create/update payload stays compatible. */
export function expertDraftToAdminFields(draft: ExpertWysiwygDraft): ExpertPresentationPayload & {
  description: string | null
  descriptionEn: string | null
  summary: string | null
  summaryEn: string | null
  /** Legacy aliases stay local to the compatibility bridge; endpoint callers use preview. */
  examplePreview: ExpertExamplePreview
  examplePreviewEn: ExpertExamplePreview
  examplePreviewInput: string | null
  examplePreviewInputEn: string | null
  examplePreviewOutput: string | null
    examplePreviewOutputEn: string | null
  exampleImageLocale: ExpertImageLocale | null
} {
  const normalized = createExpertWysiwygDraft(draft)
  const toApiPreview = (value: ExpertWysiwygPreview): ExpertExamplePreview => ({
    heading: value.heading.trim(),
    ...(value.headingPrefix?.trim() ? { headingPrefix: value.headingPrefix.trim() } : {}),
    scene: value.scene.trim() || null,
    request: value.request.trim(),
    material: value.material.trim() || null,
    outputTitle: value.outputTitle.trim(),
    outputParagraphs: value.outputParagraphs.map(item => item.trim()).filter(Boolean),
    ...(value.intro?.text?.trim() ? { intro: { text: value.intro.text.trim(), ...(value.intro.tone ? { tone: value.intro.tone } : {}) } } : {}),
    ...(value.table ? { table: {
      headers: value.table.headers.map(item => item.trim()),
      columnWidths: [...value.table.columnWidths],
      rows: value.table.rows.map(row => row.map(cell => typeof cell === 'number' ? cell : cell.trim())),
      ...(value.table.totalRow ? { totalRow: true } : {}),
      ...(value.table.style ? { style: { ...value.table.style } } : {}),
    } } : {}),
    ...(value.sections ? { sections: value.sections } : {}),
    ...(value.blocks ? { blocks: value.blocks } : {}),
  })
  const hasPreviewContent = (value: ExpertWysiwygPreview): boolean => [
    value.heading,
    value.scene,
    value.request,
    value.material,
    value.outputTitle,
    ...value.outputParagraphs,
  ].some(item => item.trim().length > 0)
  const canvasFor = (language: ExpertEditorLocale): ExpertCanvas | null => {
    if (normalized.canvas[language]) return normalized.canvas[language]
    if (usesStructuredPreview(normalized.preview[language])) return null
    return language === 'zh' ? createExpertCanvas(normalized, language) : null
  }
  return {
    description: normalized.description.zh.trim() || null,
    descriptionEn: normalized.description.en.trim() || null,
    summary: normalized.advanced.summary.zh.trim() || null,
    summaryEn: normalized.advanced.summary.en.trim() || null,
    usageGuide: normalized.usageGuide.zh.trim() || null,
    usageGuideEn: normalized.usageGuide.en.trim() || null,
    examplePrompts: normalized.examplePrompts.zh.length ? normalized.examplePrompts.zh : null,
    examplePromptsEn: normalized.examplePrompts.en.length ? normalized.examplePrompts.en : null,
    taskTypes: normalized.taskTypes.zh.length ? normalized.taskTypes.zh : null,
    taskTypesEn: normalized.taskTypes.en.length ? normalized.taskTypes.en : null,
    preview: toApiPreview(normalized.preview.zh),
    // English is optional. An all-empty editor represents an absent locale,
    // not a malformed preview object whose required members happen to be blank.
    previewEn: hasPreviewContent(normalized.preview.en)
      ? toApiPreview(normalized.preview.en)
      : null,
    canvas: {
      zh: canvasFor('zh'),
      // English is optional.  Persist an explicit null instead of manufacturing an
      // all-empty layout, otherwise the desktop would treat the empty English canvas
      // as authoritative and hide the Chinese fallback when the locale switches.
      en: canvasFor('en'),
    },
    exampleImageLocale: normalized.media.exampleImage.url
      ? (normalized.media.exampleImage.locale ?? null)
      : null,
    examplePreview: toApiPreview(normalized.preview.zh),
    examplePreviewEn: toApiPreview(normalized.preview.en),
    // Keep these only as a compatibility read/write bridge for older gateways.
    examplePreviewInput: normalized.preview.zh.request.trim() || null,
    examplePreviewInputEn: normalized.preview.en.request.trim() || null,
    examplePreviewOutput: normalized.preview.zh.outputParagraphs.join('\n').trim() || null,
    examplePreviewOutputEn: normalized.preview.en.outputParagraphs.join('\n').trim() || null,
  }
}

export function validateExpertWysiwygDraft(draft: ExpertWysiwygDraft): ExpertWysiwygFieldError[] {
  const value = createExpertWysiwygDraft(draft)
  const errors: ExpertWysiwygFieldError[] = []
  for (const locale of LOCALES) {
    if (value.title[locale].length > EXPERT_WYSIWYG_LIMITS.title) {
      errors.push({ field: 'title', locale, message: `名称最多 ${EXPERT_WYSIWYG_LIMITS.title} 个字符。` })
    }
    if (value.description[locale].length > EXPERT_WYSIWYG_LIMITS.description) {
      errors.push({ field: 'description', locale, message: `简介最多 ${EXPERT_WYSIWYG_LIMITS.description} 个字符。` })
    }
    if (value.usageGuide[locale].length > EXPERT_WYSIWYG_LIMITS.usageGuide) {
      errors.push({ field: 'usageGuide', locale, message: `使用说明最多 ${EXPERT_WYSIWYG_LIMITS.usageGuide} 个字符。` })
    }
    if (value.taskTypes[locale].length > EXPERT_WYSIWYG_LIMITS.taskTypeCount) {
      errors.push({ field: 'taskTypes', locale, message: `最多 ${EXPERT_WYSIWYG_LIMITS.taskTypeCount} 个任务类型。` })
    }
    value.taskTypes[locale].forEach((tag, index) => {
      if (tag.length > EXPERT_WYSIWYG_LIMITS.taskType) {
        errors.push({ field: 'taskTypes', locale, index, message: `单个任务类型最多 ${EXPERT_WYSIWYG_LIMITS.taskType} 个字符。` })
      }
    })
    if (new Set(value.taskTypes[locale]).size !== value.taskTypes[locale].length) {
      errors.push({ field: 'taskTypes', locale, message: '任务类型不能重复。' })
    }
    if (value.examplePrompts[locale].length > EXPERT_WYSIWYG_LIMITS.promptCount) {
      errors.push({ field: 'examplePrompts', locale, message: `最多 ${EXPERT_WYSIWYG_LIMITS.promptCount} 条示例。` })
    }
    value.examplePrompts[locale].forEach((prompt, index) => {
      if (prompt.length > EXPERT_WYSIWYG_LIMITS.prompt) {
        errors.push({ field: 'examplePrompts', locale, index, message: `示例最多 ${EXPERT_WYSIWYG_LIMITS.prompt} 个字符。` })
      }
    })
    if (new Set(value.examplePrompts[locale]).size !== value.examplePrompts[locale].length) {
      errors.push({ field: 'examplePrompts', locale, message: '示例内容不能重复。' })
    }
    const item = value.preview[locale]
    const previewChecks: Array<[string, string, number]> = [
      ['preview.heading', item.heading, EXPERT_WYSIWYG_LIMITS.previewHeading],
      ['preview.scene', item.scene, EXPERT_WYSIWYG_LIMITS.previewScene],
      ['preview.request', item.request, EXPERT_WYSIWYG_LIMITS.previewRequest],
      ['preview.material', item.material, EXPERT_WYSIWYG_LIMITS.previewMaterial],
      ['preview.outputTitle', item.outputTitle, EXPERT_WYSIWYG_LIMITS.previewOutputTitle],
      ['preview.intro', item.intro?.text ?? '', EXPERT_WYSIWYG_LIMITS.previewIntro],
    ]
    previewChecks.forEach(([field, content, limit]) => {
      if (content.length > limit) errors.push({ field, locale, message: `${field} 最多 ${limit} 个字符。` })
    })
    if (item.outputParagraphs.length > EXPERT_WYSIWYG_LIMITS.previewParagraphCount) {
      errors.push({ field: 'preview.outputParagraphs', locale, message: `最多 ${EXPERT_WYSIWYG_LIMITS.previewParagraphCount} 段输出。` })
    }
    item.outputParagraphs.forEach((paragraph, index) => {
      if (paragraph.length > EXPERT_WYSIWYG_LIMITS.previewParagraph) {
        errors.push({ field: 'preview.outputParagraphs', locale, index, message: `输出段落最多 ${EXPERT_WYSIWYG_LIMITS.previewParagraph} 个字符。` })
      }
    })
    if (item.table) {
      const table = item.table
      if (table.headers.length === 0 || table.headers.length > EXPERT_WYSIWYG_LIMITS.tableColumnCount) {
        errors.push({ field: 'preview.table', locale, message: `表格至少需要 1 列，最多 ${EXPERT_WYSIWYG_LIMITS.tableColumnCount} 列。` })
      }
      if (table.rows.length > EXPERT_WYSIWYG_LIMITS.tableRowCount) {
        errors.push({ field: 'preview.table', locale, message: `表格最多 ${EXPERT_WYSIWYG_LIMITS.tableRowCount} 行。` })
      }
      if (table.columnWidths.length !== table.headers.length
        || table.columnWidths.some(width => !Number.isFinite(width) || width <= 0)
        || table.columnWidths.reduce((sum, width) => sum + width, 0) !== 100) {
        errors.push({ field: 'preview.table', locale, message: '表格列宽必须与列数一致，且总和为 100%。' })
      }
      table.headers.forEach((header, index) => {
        if (!header.trim() || header.length > EXPERT_WYSIWYG_LIMITS.tableCell) {
          errors.push({ field: 'preview.table.header', locale, index, message: '表头不能为空且最多 1024 个字符。' })
        }
      })
      table.rows.forEach((row, rowIndex) => {
        if (row.length !== table.headers.length) {
          errors.push({ field: 'preview.table.row', locale, index: rowIndex, message: '每一行的单元格数量必须与表头一致。' })
          return
        }
        row.forEach((cell, cellIndex) => {
          if (typeof cell === 'string' && (!cell.trim() || cell.length > EXPERT_WYSIWYG_LIMITS.tableCell)) {
            errors.push({ field: 'preview.table.cell', locale, index: rowIndex * Math.max(1, table.headers.length) + cellIndex, message: '单元格不能为空且最多 1024 个字符。' })
          }
          if (typeof cell === 'number' && !Number.isFinite(cell)) {
            errors.push({ field: 'preview.table.cell', locale, index: rowIndex * Math.max(1, table.headers.length) + cellIndex, message: '单元格数字必须有效。' })
          }
        })
      })
      const style = table.style
      const colors = [style?.color, style?.headerColor, style?.headerBackgroundColor, style?.borderColor]
      if (style?.fontSize !== undefined && (!Number.isFinite(style.fontSize) || style.fontSize < 8 || style.fontSize > 72)) {
        errors.push({ field: 'preview.table.style.fontSize', locale, message: '表格字号必须在 8px 到 72px 之间。' })
      }
      if (style?.borderRadius !== undefined && (!Number.isFinite(style.borderRadius) || style.borderRadius < 0 || style.borderRadius > 32)) {
        errors.push({ field: 'preview.table.style.borderRadius', locale, message: '表格圆角必须在 0px 到 32px 之间。' })
      }
      if (style?.cellPadding !== undefined && (!Number.isFinite(style.cellPadding) || style.cellPadding < 0 || style.cellPadding > 64)) {
        errors.push({ field: 'preview.table.style.cellPadding', locale, message: '表格单元格内边距必须在 0px 到 64px 之间。' })
      }
      if (style?.rowHeight !== undefined && (!Number.isFinite(style.rowHeight) || style.rowHeight < 1 || style.rowHeight > 256)) {
        errors.push({ field: 'preview.table.style.rowHeight', locale, message: '表格行高必须在 1px 到 256px 之间。' })
      }
      colors.forEach((color, index) => {
        if (color !== undefined && !/^#[0-9a-f]{6}$/iu.test(color)) {
          errors.push({ field: 'preview.table.style', locale, index, message: '表格颜色必须是六位十六进制颜色。' })
        }
      })
    }
    const hasAnyPreviewContent = [
      item.heading,
      item.scene,
      item.request,
      item.material,
      item.outputTitle,
      ...item.outputParagraphs,
    ].some(content => content.trim().length > 0)
    if (locale === 'en' && hasAnyPreviewContent) {
      if (!item.heading.trim() || !item.request.trim() || !item.outputTitle.trim()
        || item.outputParagraphs.length === 0) {
        errors.push({
          field: 'preview',
          locale,
          message: '填写英文示例预览时，标题、请求、输出标题和至少一段输出必须完整。',
        })
      }
    }
  }
  if (!value.title.zh.trim()) errors.push({ field: 'title', locale: 'zh', message: '中文名称不能为空。' })
  if (!value.description.zh.trim()) errors.push({ field: 'description', locale: 'zh', message: '中文简介不能为空。' })
  if (!value.usageGuide.zh.trim()) errors.push({ field: 'usageGuide', locale: 'zh', message: '中文使用说明不能为空。' })
  if (value.taskTypes.zh.length === 0) errors.push({ field: 'taskTypes', locale: 'zh', message: '至少填写一个中文任务类型。' })
  if (value.examplePrompts.zh.length === 0) errors.push({ field: 'examplePrompts', locale: 'zh', message: '至少填写一条中文示例。' })
  const requiredPreview: Array<[string, string]> = [
    ['preview.heading', value.preview.zh.heading],
    ['preview.request', value.preview.zh.request],
    ['preview.outputTitle', value.preview.zh.outputTitle],
  ]
  requiredPreview.forEach(([field, content]) => {
    if (!content.trim()) errors.push({ field, locale: 'zh', message: '中文示例预览的必填字段不能为空。' })
  })
  if (value.preview.zh.outputParagraphs.length === 0) {
    errors.push({ field: 'preview.outputParagraphs', locale: 'zh', message: '至少填写一段中文示例输出。' })
  }
  if (value.advanced.category.length > EXPERT_WYSIWYG_LIMITS.category) {
    errors.push({ field: 'category', message: `分类最多 ${EXPERT_WYSIWYG_LIMITS.category} 个字符。` })
  }
  if (value.advanced.icon.length > EXPERT_WYSIWYG_LIMITS.icon) {
    errors.push({ field: 'icon', message: `徽标文本最多 ${EXPERT_WYSIWYG_LIMITS.icon} 个字符。` })
  }
  for (const locale of LOCALES) {
    const layout = value.canvas[locale]
    if (!layout) continue
    if (layout.version !== 1 || layout.width !== 1080 || layout.height !== 704 || layout.nodes.length > 128) {
      errors.push({ field: 'canvas', locale, message: '画布必须为 1080×704，且最多包含 128 个元素。' })
    }
    layout.nodes.forEach((node, index) => {
      if (![node.x, node.y, node.width, node.height].every(Number.isFinite)
        || node.width < 0 || node.height < 0 || node.x < 0 || node.y < 0
        || node.x + node.width > 1080 || node.y + node.height > 704) {
        errors.push({ field: 'canvas', locale, index, message: `画布元素 ${index + 1} 超出 1080×704 范围。` })
      }
      node.runs?.forEach((run, runIndex) => {
        if (run.text.length > EXPERT_WYSIWYG_LIMITS.metadataValue) {
          errors.push({ field: 'canvas', locale, index: runIndex, message: '画布文字单段最多 1024 个字符。' })
        }
        if (run.fontSize !== undefined && (run.fontSize < 8 || run.fontSize > 72)) {
          errors.push({ field: 'canvas', locale, index, message: '字号必须在 8px 到 72px 之间。' })
        }
        if (run.color !== undefined && !/^#[0-9a-f]{6}$/iu.test(run.color)) {
          errors.push({ field: 'canvas', locale, index, message: '文字颜色必须是六位十六进制颜色。' })
        }
      })
    })
  }
  return errors
}
