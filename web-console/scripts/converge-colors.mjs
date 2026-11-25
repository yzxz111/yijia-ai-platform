/**
 * 把 <style> 块里的硬编码色值收敛为设计 token。
 *
 * 安全护栏：
 *  1. 只处理 .vue 的 <style> 块（<script>/<template> 里的图表色不动）；
 *  2. 只替换「色值 == 某个 token 的亮色值」的精确匹配，映射表显式声明；
 *  3. 跳过被任何 *.spec.ts 断言过的色值，避免把测试从一种失败变成另一种失败；
 *  4. 跳过 #ffffff / #000000（白字、遮罩等必须保持字面值）。
 *
 * 用法：node scripts/converge-colors.mjs [--write]
 * 不加 --write 时只做 dry-run 统计。
 */
import { readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'

const write = process.argv.includes('--write')
const srcDir = resolve('src')

/** 亮色 token 值 → CSS 变量（含既有页面冻结的旧 token 值与等价 Tailwind 值） */
const MAP = {
  // 旧正文/次要/主色（页面里冻结的旧调色板）
  '#3f4654': 'var(--app-fg)',
  '#334155': 'var(--app-fg)',
  '#111827': 'var(--app-fg)',
  '#8c94a4': 'var(--app-muted)',
  '#9ca3af': 'var(--app-muted)',
  '#6e7788': 'var(--app-muted-strong)',
  '#6b7280': 'var(--app-muted-strong)',
  '#4f95f5': 'var(--app-primary)',
  '#3b85e6': 'var(--app-primary-hover)',
  '#2e75d4': 'var(--app-primary-pressed)',
  '#5b8def': 'var(--app-sidebar-active)',
  '#eaf0fa': 'var(--app-primary-soft)',
  '#eff6ff': 'var(--app-primary-soft)',
  '#eff4ff': 'var(--app-primary-soft)',
  // 表面 / 背景
  '#f6f7f9': 'var(--app-bg)',
  '#f1f3f7': 'var(--app-surface-subtle)',
  '#f8fafc': 'var(--app-surface-subtle)',
  '#f7f9fc': 'var(--app-surface-subtle)',
  '#e8ecf2': 'var(--app-surface-muted)',
  '#f1f5f9': 'var(--app-surface-muted)',
  '#f2f4f7': 'var(--app-surface-subtle)',
  // 边框
  '#e2e6ee': 'var(--app-border)',
  '#e4e8f0': 'var(--app-border)',
  '#e5e7eb': 'var(--app-border)',
  '#c8cedb': 'var(--app-border-strong)',
  '#c9d1de': 'var(--app-border-strong)',
  // 强调色
  '#d97706': 'var(--app-accent)',
  '#f59e0b': 'var(--app-accent)',
  '#fef3c7': 'var(--app-accent-soft)',
  // 语义状态
  '#166534': 'var(--app-success)',
  '#16a34a': 'var(--app-success)',
  '#10b981': 'var(--app-success)',
  '#dcfce7': 'var(--app-success-soft)',
  '#b45309': 'var(--app-warning)',
  '#b91c1c': 'var(--app-danger)',
  '#fef2f2': 'var(--app-danger-soft)',
  '#fee2e2': 'var(--app-danger-soft)',
  '#fecaca': 'var(--app-danger-border)',
  // 扩展批次：主色/成功/警告/危险/中性 的浅色调
  '#bfdbfe': 'var(--app-primary-border)',
  '#c7dbfb': 'var(--app-primary-border)',
  '#1d4ed8': 'var(--app-primary-hover)',
  '#f0f9eb': 'var(--app-success-soft)',
  '#dcfce7': 'var(--app-success-soft)',
  '#bfe0d0': 'var(--app-success-border)',
  '#bbf7d0': 'var(--app-success-border)',
  '#15803d': 'var(--app-success-text)',
  '#22c55e': 'var(--app-success)',
  '#16a34a': 'var(--app-success)',
  '#fffbeb': 'var(--app-warning-soft)',
  '#fde68a': 'var(--app-warning-border)',
  '#ecd9a6': 'var(--app-warning-border)',
  '#e6a23c': 'var(--app-warning)',
  '#b08a3c': 'var(--app-warning-text)',
  '#dc2626': 'var(--app-danger-text)',
  '#ef4444': 'var(--app-danger-text)',
  '#fef0f0': 'var(--app-danger-soft)',
  '#eef2f7': 'var(--app-surface-sunken)',
  '#e8edf3': 'var(--app-surface-sunken)',
  '#dfe3e9': 'var(--app-border-subtle)',
  '#cbd5e1': 'var(--app-border-subtle)',
  '#9fb0c0': 'var(--app-muted)',
  '#64748b': 'var(--app-muted-strong)',
  '#1f2937': 'var(--app-fg)',
  // 第三批：长尾页面自有色调，按语义归入已有 token
  '#eef1f5': 'var(--app-surface-sunken)',
  '#e8edf4': 'var(--app-surface-sunken)',
  '#ecf5ff': 'var(--app-primary-soft)',
  '#f8fbff': 'var(--app-primary-soft)',
  '#dbeafe': 'var(--app-primary-border)',
  '#d0e3fa': 'var(--app-primary-border)',
  '#93c5fd': 'var(--app-primary-border)',
  '#9bc7ff': 'var(--app-primary-border)',
  '#1e40af': 'var(--app-primary-pressed)',
  '#ecc6c6': 'var(--app-danger-border)',
  '#991b1b': 'var(--app-danger-text)',
  '#c0504a': 'var(--app-danger-text)',
  '#5eead4': 'var(--app-success-border)',
  '#ccfbf1': 'var(--app-success-soft)',
  '#f0fdf4': 'var(--app-success-soft)',
  '#92400e': 'var(--app-warning-text)',
  '#b46a15': 'var(--app-warning-text)',
  '#9499a3': 'var(--app-muted)',
  '#c4d3e4': 'var(--app-border-strong)',
}

/** 被测试断言过的色值一律不动 */
function assertedHexValues() {
  const asserted = new Set()
  const walk = (dir) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name)
      if (entry.isDirectory()) { walk(full); continue }
      if (!entry.name.endsWith('.spec.ts')) continue
      const text = readFileSync(full, 'utf8')
      for (const match of text.matchAll(/#[0-9a-fA-F]{6}\b/gu)) {
        asserted.add(match[0].toLowerCase())
      }
    }
  }
  walk(srcDir)
  return asserted
}

const asserted = assertedHexValues()
const skip = new Set(['#ffffff', '#000000'])
const perFile = new Map()
let total = 0
let skippedAsserted = 0

function walkVue(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) { walkVue(full); continue }
    if (!entry.name.endsWith('.vue')) continue

    const original = readFileSync(full, 'utf8')
    let count = 0
    const updated = original.replace(
      /(<style[^>]*>)([\s\S]*?)(<\/style>)/gu,
      (whole, open, body, close) => {
        const replaced = body.replace(/#[0-9a-fA-F]{6}\b/gu, (hex) => {
          const key = hex.toLowerCase()
          if (skip.has(key)) return hex
          if (asserted.has(key)) { skippedAsserted += 1; return hex }
          const token = MAP[key]
          if (!token) return hex
          count += 1
          return token
        })
        return open + replaced + close
      },
    )
    if (count > 0) {
      perFile.set(full.replace(srcDir + '\\', '').replace(srcDir + '/', ''), count)
      total += count
      if (write) writeFileSync(full, updated)
    }
  }
}

walkVue(srcDir)

console.log(`${write ? '已写入' : 'DRY-RUN'}：替换 ${total} 处，涉及 ${perFile.size} 个文件`)
console.log(`因被测试断言而跳过：${skippedAsserted} 处`)
console.log('--- 替换最多的 12 个文件 ---')
for (const [file, count] of [...perFile.entries()].sort((a, b) => b[1] - a[1]).slice(0, 12)) {
  console.log(`${String(count).padStart(4)} 处  ${file}`)
}
if (!write) console.log('\n加 --write 真正写入')
