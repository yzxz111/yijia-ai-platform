/**
 * 视觉升级用：批量抓取关键页面截图，用于 before / after 对比。
 *
 * 用法（在 web-console 目录下）：
 *   node scripts/capture-ui.mjs
 * 可选环境变量：
 *   UI_BASE           默认 http://127.0.0.1:18081
 *   UI_OUT            默认 test-results/ui-refresh/<before|after>
 *   UI_ADMIN_EMAIL / UI_ADMIN_PASSWORD
 *   UI_USER_EMAIL  / UI_USER_PASSWORD
 */
import { chromium } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

// 凭据绝不设默认值：必须从环境（deploy/env/.env）中读取。
function requireEnv(name) {
  const value = process.env[name]
  if (!value) {
    throw new Error(`${name} is required; take it from deploy/env/.env`)
  }
  return value
}

const base = process.env.UI_BASE ?? 'http://127.0.0.1:18081'
const outDir = resolve(process.env.UI_OUT ?? 'test-results/ui-refresh/before')
const admin = {
  email: process.env.UI_ADMIN_EMAIL ?? 'admin@local.test',
  password: requireEnv('UI_ADMIN_PASSWORD'),
}
const user = {
  email: process.env.UI_USER_EMAIL ?? 'skilltest-1789184820@local.test',
  password: requireEnv('UI_USER_PASSWORD'),
}

/** 页面清单：覆盖仪表盘 / 列表+筛选 / 表单 / 卡片 / 钱包 五类版式 */
const pages = [
  { name: '01-login', path: '/login', who: null },
  { name: '02-admin-overview', path: '/admin/overview', who: 'admin' },
  { name: '03-admin-users', path: '/admin/users', who: 'admin' },
  { name: '04-admin-grants', path: '/admin/grants', who: 'admin' },
  { name: '05-admin-models', path: '/admin/models', who: 'admin' },
  { name: '06-user-dashboard', path: '/app/dashboard', who: 'user' },
  { name: '07-user-plans', path: '/app/plans', who: 'user' },
  { name: '08-user-wallet', path: '/app/wallet', who: 'user' },
  { name: '09-user-orders', path: '/app/orders', who: 'user' },
  // Skill 目录只对管理员开放（普通用户端不再有入口），走控制台登录态
  { name: '10-admin-skills', path: '/admin/skills', who: 'admin' },
]

mkdirSync(outDir, { recursive: true })

const [vw, vh] = (process.env.UI_VIEWPORT ?? '1440x900').split('x').map(Number)
const browser = await chromium.launch({
  // 本机 Playwright 只装了完整 chromium（没有 chrome-headless-shell），
  // 用 channel: 'chromium' 走新 headless；也可用 UI_CHANNEL=msedge 走系统 Edge。
  channel: process.env.UI_CHANNEL ?? 'chromium',
})
const context = await browser.newContext({
  viewport: { width: vw, height: vh },
  deviceScaleFactor: 1,
  reducedMotion: 'reduce',
  isMobile: vw < 768,
  hasTouch: vw < 768,
})
const page = await context.newPage()

// 主题：light（默认）或 dark，通过 localStorage 预置，避免闪烁
const theme = process.env.UI_THEME === 'dark' ? 'dark' : 'light'
await context.addInitScript((value) => {
  try {
    window.localStorage.setItem('app-theme', value)
  } catch {
    // 忽略隐私模式下的存储失败
  }
}, theme)

async function settle() {
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {})
  await page.waitForTimeout(900) // 让图表/表格动画收尾
}

/** 固定设备号：避免每次跑截图都注册一个新设备，撞上 5 设备上限 */
const deviceIds = {
  admin: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  user: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
}

async function login(who, account) {
  await page.goto(`${base}/login`, { waitUntil: 'domcontentloaded' })
  // 先写入固定设备号再重载，让 SPA 的 getDeviceId() 读到它
  await page.evaluate(
    ([key, value]) => window.localStorage.setItem(key, value),
    ['yzh_device_id', deviceIds[who]],
  )
  await page.reload({ waitUntil: 'domcontentloaded' })
  // 登录方式已改为标签切换（.auth-tab），老选择器 login-mode-password 已不存在
  const passwordTab = page.getByRole('button', { name: '密码登录' })
  if (await passwordTab.count()) {
    await passwordTab.first().click()
  } else {
    await page.locator('[data-test="login-mode-password"]').click()
  }
  await page.locator('[data-test="login-identifier"]').fill(account.email)
  await page.locator('[data-test="login-password"]').fill(account.password)
  // 登录按钮由「同意服务条款」勾选门控（未勾选时 submit 一直是 disabled），
  // 与 tests/e2e 的 loginThroughUi 保持一致，否则这里会一直等到超时。
  const consent = page.locator('[data-test="login-consent"] .el-checkbox__inner')
  if (await consent.count()) {
    await consent.first().click()
  }
  await page.locator('[data-test="login-submit"]').click()
  await page.waitForURL(/\/(app|admin)\//u, { timeout: 25000 })
  await settle()
}

async function signOut() {
  await context.clearCookies()
  await page.goto(`${base}/login`, { waitUntil: 'domcontentloaded' })
  await page.evaluate(() => { try { window.localStorage.clear(); window.sessionStorage.clear() } catch {} })
}

let current = null
for (const entry of pages) {
  if (entry.who && entry.who !== current) {
    if (current) await signOut()
    await login(entry.who, entry.who === 'admin' ? admin : user)
    current = entry.who
  }
  // 需要预置会话存储的页面（如 /app/skills 的 API Key）：同源跳转不会清空 sessionStorage
  if (entry.sessionStorage) {
    await page.evaluate((pairs) => {
      try {
        for (const [key, value] of Object.entries(pairs)) window.sessionStorage.setItem(key, value)
      } catch {
        // 忽略隐私模式下的存储失败
      }
    }, entry.sessionStorage)
  }
  await page.goto(`${base}${entry.path}`, { waitUntil: 'domcontentloaded' })
  await settle()
  const file = `${outDir}/${entry.name}.png`
  await page.screenshot({ path: file })
  console.log(`captured ${entry.path} -> ${file}`)
}

await browser.close()
console.log(`done: ${pages.length} screenshots -> ${outDir}`)
