<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import type { ExpertCanvasTextRun } from '../../utils/expertWysiwygDraft'

type RichTextSelection = { start: number; end: number }

const props = withDefaults(defineProps<{
  modelValue: string
  runs?: ExpertCanvasTextRun[]
  maxlength: number
  disabled?: boolean
  label: string
  placeholder?: string
  editorClass?: string
  editorStyle?: Record<string, string>
  multiline?: boolean
}>(), {
  runs: () => [],
  disabled: false,
  placeholder: '',
  editorClass: '',
  editorStyle: () => ({}),
  multiline: true,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'update:runs': [runs: ExpertCanvasTextRun[]]
  'selection-change': [selection: RichTextSelection]
}>()

const editor = ref<HTMLDivElement | null>(null)
const compatibilityInput = ref<HTMLTextAreaElement | null>(null)
const composing = ref(false)
let renderQueued = false

function normalizeText(value: string): string {
  return value.replace(/\r\n?/g, '\n').slice(0, props.maxlength)
}

function styleFromRun(run: ExpertCanvasTextRun): Record<string, string> {
  return {
    ...(run.color ? { color: run.color } : {}),
    ...(run.fontSize ? { fontSize: `${run.fontSize}px` } : {}),
    ...(run.fontWeight ? { fontWeight: String(run.fontWeight) } : {}),
    ...(run.italic ? { fontStyle: 'italic' } : {}),
  }
}

function runKey(run: ExpertCanvasTextRun): string {
  return JSON.stringify([
    run.color ?? '',
    run.fontSize ?? '',
    run.fontWeight ?? '',
    run.italic === true,
  ])
}

function normalizeRuns(runs: ExpertCanvasTextRun[], text: string): ExpertCanvasTextRun[] {
  const value = normalizeText(text)
  if (!value) return []
  const next: ExpertCanvasTextRun[] = []
  let offset = 0
  runs.forEach((run) => {
    if (offset >= value.length || !run.text) return
    const item = value.slice(offset, offset + run.text.length)
    offset += run.text.length
    if (!item) return
    const previous = next[next.length - 1]
    if (previous && runKey(previous) === runKey(run)) previous.text += item
    else next.push({ ...run, text: item })
  })
  if (offset < value.length) next.push({ text: value.slice(offset) })
  return next.length ? next : [{ text: value }]
}

function runFromElement(element: Element): Partial<ExpertCanvasTextRun> {
  const node = element.closest('[data-rich-run]') as HTMLElement | null
  if (!node) return {}
  const fontSize = Number(node.dataset.fontSize)
  const fontWeight = Number(node.dataset.fontWeight)
  return {
    ...(node.dataset.color ? { color: node.dataset.color } : {}),
    ...(Number.isFinite(fontSize) && fontSize > 0 ? { fontSize } : {}),
    ...(Number.isFinite(fontWeight) && fontWeight > 0 ? { fontWeight } : {}),
    ...(node.dataset.italic === 'true' ? { italic: true } : {}),
  }
}

const BLOCK_TAGS = /^(DIV|P|LI|H[1-6])$/u

function isBlockElement(node: Node | null): node is HTMLElement {
  return node?.nodeType === Node.ELEMENT_NODE && BLOCK_TAGS.test((node as HTMLElement).tagName)
}

function isFlowParent(node: Node | null): boolean {
  return node?.nodeType === Node.DOCUMENT_FRAGMENT_NODE
    || node === editor.value
    || (node?.nodeType === Node.ELEMENT_NODE && !isBlockElement(node))
}

function needsLeadingBlockBreak(element: HTMLElement, value: string): boolean {
  const previous = element.previousSibling
  if (!previous || isBlockElement(previous) || value.startsWith('\n')) return false
  return !runsForNode(previous).map(run => run.text).join('').endsWith('\n')
}

function mergeRuns(runs: ExpertCanvasTextRun[]): ExpertCanvasTextRun[] {
  const result: ExpertCanvasTextRun[] = []
  runs.forEach((run) => {
    if (!run.text) return
    const previous = result[result.length - 1]
    if (previous && runKey(previous) === runKey(run)) previous.text += run.text
    else result.push({ ...run })
  })
  return result
}

/**
 * Flatten the browser's contenteditable DOM into the serializable run model.
 * Chromium represents Enter as DIV/BR combinations. A trailing empty DIV is
 * only a browser placeholder when the previous sibling is already a block;
 * suppress that duplicate marker while preserving a real final newline.
 */
function runsForNode(node: Node, inherited: Partial<ExpertCanvasTextRun> = {}): ExpertCanvasTextRun[] {
  if (node.nodeType === Node.TEXT_NODE) {
    const value = (node.textContent ?? '').replace(/\r\n?/g, '\n')
    return value ? [{ text: value, ...inherited }] : []
  }
  if (node.nodeType === Node.DOCUMENT_FRAGMENT_NODE) {
    return mergeRuns(Array.from(node.childNodes).flatMap(child => runsForNode(child, inherited)))
  }
  if (node.nodeType !== Node.ELEMENT_NODE) return []
  const element = node as HTMLElement
  if (element.tagName === 'BR') return [{ text: '\n', ...inherited }]

  const ownStyle = { ...inherited, ...runFromElement(element) }
  const children = mergeRuns(Array.from(element.childNodes).flatMap(child => runsForNode(child, ownStyle)))
  if (!isBlockElement(element) || !isFlowParent(element.parentNode)) return children

  const value = children.map(run => run.text).join('')
  const previous = element.previousSibling
  const next = element.nextSibling
  if (!next && (value === '' || value === '\n') && previous && isBlockElement(previous)) return []
  if (needsLeadingBlockBreak(element, value)) {
    children.unshift({ text: '\n', ...inherited })
  }
  if (next && !value.endsWith('\n')) children.push({ text: '\n', ...inherited })
  return mergeRuns(children)
}

function nodeText(node: Node): string {
  return runsForNode(node).map(run => run.text).join('')
}

function textFromEditor(): string {
  return editor.value ? nodeText(editor.value) : ''
}

function runsFromEditor(): ExpertCanvasTextRun[] {
  if (!editor.value) return []
  return normalizeRuns(runsForNode(editor.value), textFromEditor())
}

function renderRuns(value = props.modelValue, sourceRuns = props.runs) {
  const root = editor.value
  if (!root) return
  const normalizedValue = normalizeText(value)
  root.replaceChildren()
  const runs = normalizeRuns(sourceRuns, normalizedValue)
  if (!runs.length && normalizedValue) runs.push({ text: normalizedValue })
  runs.forEach((run) => {
    const span = document.createElement('span')
    span.dataset.richRun = 'true'
    if (run.color) span.dataset.color = run.color
    if (run.fontSize) span.dataset.fontSize = String(run.fontSize)
    if (run.fontWeight) span.dataset.fontWeight = String(run.fontWeight)
    if (run.italic) span.dataset.italic = 'true'
    Object.assign(span.style, styleFromRun(run))
    span.appendChild(document.createTextNode(run.text))
    root.appendChild(span)
  })
  if (compatibilityInput.value && compatibilityInput.value.value !== normalizedValue) compatibilityInput.value.value = normalizedValue
}

function queueRender() {
  if (renderQueued) return
  renderQueued = true
  void nextTick(() => {
    renderQueued = false
    // Do not replace a live DOM selection while the operator is typing. The
    // input handler has already emitted its sanitized runs in that case.
    if (document.activeElement !== editor.value) renderRuns()
  })
}

function boundaryOffset(container: Node, offset: number): number {
  const root = editor.value
  if (!root || !root.contains(container)) return 0
  let total = 0
  let current: Node | null = container
  while (current && current !== root) {
    const parentNode: ParentNode | null = current.parentNode
    if (!parentNode) return 0
    if (isBlockElement(current) && isFlowParent(parentNode)) {
      const blockValue = Array.from(current.childNodes)
        .flatMap(child => runsForNode(child))
        .map(run => run.text)
        .join('')
      if (needsLeadingBlockBreak(current, blockValue)) total += 1
    }
    const index = Array.prototype.indexOf.call(parentNode.childNodes, current) as number
    for (let siblingIndex = 0; siblingIndex < index; siblingIndex += 1) {
      total += runsForNode(parentNode.childNodes[siblingIndex]).reduce((length, run) => length + run.text.length, 0)
    }
    current = parentNode
  }
  if (container.nodeType === Node.TEXT_NODE) {
    total += (container.textContent ?? '').slice(0, offset).replace(/\r\n?/g, '\n').length
  } else {
    const children = Array.from(container.childNodes).slice(0, offset)
    total += children.flatMap(child => runsForNode(child)).reduce((length, run) => length + run.text.length, 0)
  }
  return Math.min(total, props.maxlength)
}

function selectionInsideEditor(): RichTextSelection | null {
  const root = editor.value
  const selection = window.getSelection?.()
  if (!root || !selection || !selection.rangeCount) return null
  const range = selection.getRangeAt(0)
  if (!root.contains(range.startContainer) || !root.contains(range.endContainer)) return null
  const start = boundaryOffset(range.startContainer, range.startOffset)
  const end = boundaryOffset(range.endContainer, range.endOffset)
  return { start: Math.min(start, end), end: Math.max(start, end) }
}

function pointAtOffset(root: Node, target: number): { node: Text; offset: number } | null {
  let remaining = Math.max(0, target)
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  let node = walker.nextNode() as Text | null
  while (node) {
    const length = node.data.length
    if (remaining <= length) return { node, offset: remaining }
    remaining -= length
    node = walker.nextNode() as Text | null
  }
  const last = root.lastChild
  if (last?.nodeType === Node.TEXT_NODE) return { node: last as Text, offset: (last.textContent ?? '').length }
  return null
}

function restoreSelection(selection: RichTextSelection | null) {
  if (!selection || !editor.value) return
  const start = pointAtOffset(editor.value, selection.start)
  const end = pointAtOffset(editor.value, selection.end)
  if (!start || !end) return
  const range = document.createRange()
  range.setStart(start.node, start.offset)
  range.setEnd(end.node, end.offset)
  const target = window.getSelection()
  target?.removeAllRanges()
  target?.addRange(range)
}

function emitSelection() {
  const selection = selectionInsideEditor()
  if (selection) emit('selection-change', selection)
}

function emitInput() {
  const rawValue = editor.value ? nodeText(editor.value) : ''
  const value = normalizeText(rawValue)
  const runs = runsFromEditor()
  const selection = selectionInsideEditor()
  if (rawValue.length > value.length) {
    renderRuns(value, runs)
    restoreSelection(selection && {
      start: Math.min(selection.start, value.length),
      end: Math.min(selection.end, value.length),
    })
  }
  emit('update:runs', runs)
  emit('update:modelValue', value)
  if (selection) emit('selection-change', selection)
}

function onInput() {
  if (props.disabled || composing.value) return
  emitInput()
}

function onCompatibilityInput() {
  if (props.disabled || !compatibilityInput.value) return
  const value = normalizeText(compatibilityInput.value.value)
  const runs = normalizeRuns(props.runs, value)
  emit('update:runs', runs)
  emit('update:modelValue', value)
  emit('selection-change', {
    start: compatibilityInput.value.selectionStart ?? value.length,
    end: compatibilityInput.value.selectionEnd ?? value.length,
  })
}

function onPaste(event: ClipboardEvent) {
  if (props.disabled) return
  event.preventDefault()
  const plainText = event.clipboardData?.getData('text/plain') ?? ''
  if (!plainText || !editor.value) return
  const selection = selectionInsideEditor()
  const current = textFromEditor()
  const start = selection?.start ?? current.length
  const end = selection?.end ?? start
  const remaining = Math.max(0, props.maxlength - (current.length - (end - start)))
  const inserted = plainText
    .replace(/\r\n?|\n/gu, props.multiline ? '\n' : ' ')
    .slice(0, remaining)
  const range = window.getSelection()?.rangeCount ? window.getSelection()!.getRangeAt(0) : null
  if (!range || !editor.value.contains(range.startContainer)) return
  range.deleteContents()
  const textNode = document.createTextNode(inserted)
  range.insertNode(textNode)
  range.setStartAfter(textNode)
  range.collapse(true)
  const target = window.getSelection()
  target?.removeAllRanges()
  target?.addRange(range)
  emitInput()
}

function onBeforeInput(event: InputEvent) {
  if (props.disabled) return
  if (!props.multiline && (event.inputType === 'insertParagraph' || event.inputType === 'insertLineBreak')) {
    event.preventDefault()
    return
  }
  if (event.inputType !== 'insertText' || !event.data) return
  const selection = selectionInsideEditor()
  const current = textFromEditor()
  const selected = selection ? selection.end - selection.start : 0
  if (current.length - selected + event.data.length <= props.maxlength) return
  // Let the browser update the DOM, then the input handler will rebuild it to
  // the allowed length. This keeps IME/composition behavior intact.
}

function onKeyup() { emitSelection() }
function onMouseup() { emitSelection() }
function onFocus() { emitSelection() }
function onKeydown(event: KeyboardEvent) {
  if (!props.multiline && event.key === 'Enter') event.preventDefault()
}

function onCompositionStart() { composing.value = true }
function onCompositionEnd() {
  composing.value = false
  emitInput()
}

function onDocumentSelectionChange() {
  if (document.activeElement === editor.value) emitSelection()
}

watch(() => [props.modelValue, props.runs], () => {
  queueRender()
}, { deep: true })

onMounted(() => {
  renderRuns()
  document.addEventListener('selectionchange', onDocumentSelectionChange)
})

onBeforeUnmount(() => {
  document.removeEventListener('selectionchange', onDocumentSelectionChange)
})
</script>

<template>
  <div class="expert-rich-text-field" :class="{ 'expert-rich-text-field--multiline': multiline }">
    <!--
      This native mirror deliberately stays first for keyboard/form adapters
      that only understand input/textarea. It is hidden from assistive tech;
      the contenteditable surface below is the sole user-facing control.
    -->
    <textarea
      ref="compatibilityInput"
      class="expert-rich-text-field__compat"
      :value="modelValue"
      :maxlength="maxlength"
      :disabled="disabled"
      :aria-label="label"
      aria-hidden="true"
      tabindex="-1"
      @input="onCompatibilityInput"
    />
    <div
      ref="editor"
      class="expert-rich-text-field__editor"
      :class="editorClass"
      :style="editorStyle"
      role="textbox"
      :aria-label="label"
      :aria-multiline="multiline ? 'true' : 'false'"
      :aria-disabled="disabled ? 'true' : 'false'"
      :data-placeholder="placeholder"
      :data-maxlength="maxlength"
      :contenteditable="disabled ? 'false' : 'true'"
      spellcheck="false"
      @beforeinput="onBeforeInput"
      @compositionstart="onCompositionStart"
      @compositionend="onCompositionEnd"
      @input="onInput"
      @paste="onPaste"
      @keydown="onKeydown"
      @focus="onFocus"
      @select="emitSelection"
      @keyup="onKeyup"
      @mouseup="onMouseup"
    />
  </div>
</template>

<style scoped>
.expert-rich-text-field {
  position: relative;
  min-width: 0;
}

.expert-rich-text-field__compat {
  position: absolute;
  width: 1px !important;
  height: 1px !important;
  overflow: hidden;
  clip: rect(0 0 0 0);
  clip-path: inset(50%);
  white-space: nowrap;
  pointer-events: none;
}

.expert-rich-text-field__editor {
  min-width: 0;
  min-height: 1.5em;
  outline: none;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.expert-rich-text-field__editor:empty::before {
  content: attr(data-placeholder);
  color: #94a3b8;
  pointer-events: none;
}

.expert-rich-text-field__editor[aria-disabled='true'] {
  cursor: not-allowed;
}
</style>
