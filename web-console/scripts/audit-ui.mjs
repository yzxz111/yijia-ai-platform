/**
 * 前端页面体检：用浏览器真实度量找出「肉眼看不准」的问题。
 *
 * 检查项：
 *  1. 文字被裁：white-space 为 nowrap/ellipsis 且 scrollWidth > clientWidth
 *  2. 溢出卡片：元素右边界超出最近的卡片/面板容器
 *  3. 横向滚动：documentElement 出现横向滚动
 *  4. 控制台报错
 *  5. 失败请求（status >= 400）
 *  6. 可访问性：按钮无可读名、图片缺 alt、输入框无 label/aria-label
 *
 * 用法：node scripts/audit-ui.mjs [--who admin|user] [--width 1440]
 */
import { chromium } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const base = process.env.UI_BASE ?? 'http://127.0.0.1:18081';
const adminPassword = process.env.UI_ADMIN_PASSWORD;
const userPassword = process.env.UI_USER_PASSWORD;
if (!adminPassword || !userPassword) {
  throw new Error('UI_ADMIN_PASSWORD and UI_USER_PASSWORD are required (see deploy/env/.env)');
}

const pages = [
  { path: '/admin/overview', who: 'admin' },
  { path: '/admin/users', who: 'admin' },
  { path: '/admin/grants', who: 'admin' },
  { path: '/admin/models', who: 'admin' },
  { path: '/admin/plans', who: 'admin' },
  { path: '/admin/orders', who: 'admin' },
  { path: '/admin/usage', who: 'admin' },
  { path: '/admin/wallet', who: 'admin' },
  { path: '/admin/reports', who: 'admin' },
  { path: '/admin/operations', who: 'admin' },
  { path: '/admin/skills', who: 'admin' },
  { path: '/admin/content-safety', who: 'admin' },
  { path: '/admin/communications', who: 'admin' },
  { path: '/admin/audit', who: 'admin' },
  { path: '/admin/legal-documents', who: 'admin' },
  // 由 API 侧聚合后渲染的报表页。它们不会产生浏览器可见的失败请求（platform→gateway 的调用
  // 发生在服务端），但布局与空/错误态仍要检查：结算垫付曾因平台侧校验与网关的 truncated 契约
  // 冲突而整页降级为「暂时取不到数据」，只有把页面纳入体检才可能发现这类问题。
  { path: '/admin/settlement-deficit', who: 'admin' },
  { path: '/admin/experts', who: 'admin' },
  { path: '/app/dashboard', who: 'user' },
  { path: '/app/plans', who: 'user' },
  { path: '/app/wallet', who: 'user' },
  { path: '/app/orders', who: 'user' },
  { path: '/app/api-keys', who: 'user' },
  { path: '/app/quota', who: 'user' },
  { path: '/app/usage-logs', who: 'user' },
  { path: '/app/notifications', who: 'user' },
  { path: '/app/profile', who: 'user' },
  { path: '/app/files', who: 'user' },
  { path: '/app/support-tickets', who: 'user' },
  { path: '/app/help', who: 'user' },
  { path: '/app/safety-appeals', who: 'user' },
];

const viewportWidth = Number(process.env.UI_AUDIT_WIDTH ?? 1440);
const outDir = resolve('test-results/ui-audit');
mkdirSync(outDir, { recursive: true });

const browser = await chromium.launch({ channel: 'chromium' });
const context = await browser.newContext({
  viewport: { width: viewportWidth, height: 900 },
  reducedMotion: 'reduce',
});
// 关键：首访引导弹窗会遮住页面，先标记已看过，否则每个页面都在量幕布
const theme = process.env.UI_THEME === 'dark' ? 'dark' : 'light';
await context.addInitScript((value) => {
  try {
    window.localStorage.setItem('app-theme', value);
    window.localStorage.setItem('yzh_onboarding_seen', '1');
    window.localStorage.setItem('yzh-welcome-seen', '1');
    window.localStorage.setItem('yzh_dashboard_welcome_seen', '1');
  } catch { /* 隐私模式忽略 */ }
}, theme);

const page = await context.newPage();
const consoleErrors = [];
let failedRequests = [];
page.on('console', (message) => {
  if (message.type() === 'error') consoleErrors.push(message.text().slice(0, 160));
});
page.on('response', (response) => {
  if (response.status() >= 400) {
    failedRequests.push(`${response.status()} ${response.request().method()} ${new URL(response.url()).pathname}`);
  }
});

async function login(who, email, password) {
  await page.goto(`${base}/login`, { waitUntil: 'domcontentloaded' });
  await page.evaluate((id) => window.localStorage.setItem('yzh_device_id', id),
    who === 'admin' ? 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' : 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb');
  await page.reload({ waitUntil: 'domcontentloaded' });
  const tab = page.getByRole('button', { name: '密码登录' });
  if (await tab.count()) await tab.first().click();
  await page.locator('[data-test="login-identifier"]').fill(email);
  await page.locator('[data-test="login-password"]').fill(password);
  const consent = page.locator('[data-test="login-consent"] .el-checkbox__inner');
  if (await consent.count()) await consent.first().click();
  await page.locator('[data-test="login-submit"]').click();
  await page.waitForURL(/\/(app|admin)\//u, { timeout: 25_000 });
  // 引导弹窗（若仍出现）直接关掉
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(400);
}

/** 在页面里执行的度量逻辑：只返回「确定有问题」的项，避免噪声。 */
const probe = () => {
  const isVisible = (el) => {
    const rect = el.getBoundingClientRect();
    const style = getComputedStyle(el);
    if (rect.width <= 0 || rect.height <= 0) return false;
    if (style.visibility === 'hidden' || style.display === 'none' || style.opacity === '0') return false;
    // 视觉隐藏工具类（sr-only）：1px + clip 是设计意图，不是「文字被裁」
    if (style.clipPath && style.clipPath !== 'none') return false;
    if (rect.width <= 1 || rect.height <= 1) return false;
    return true;
  };
  const label = (el) => {
    const text = (el.textContent ?? '').trim().replace(/\s+/gu, ' ').slice(0, 28);
    const cls = typeof el.className === 'string' ? el.className.split(/\s+/u)[0] : '';
    return `${el.tagName.toLowerCase()}${cls ? `.${cls}` : ''}${text ? ` "${text}"` : ''}`;
  };
  const ownerCard = (el) => el.closest(
    '.app-card, .admin-card, .panel, .card, section, .el-card, .el-dialog, [class*="-card"]',
  );
  /** 元素与卡片之间是否存在「可横向滚动的祖先」——表格放在滚动容器里是设计意图，不算溢出。 */
  const insideScroller = (el, card) => {
    for (let node = el.parentElement; node && node !== card; node = node.parentElement) {
      const style = getComputedStyle(node);
      const scrollable = ['auto', 'scroll'].includes(style.overflowX);
      if (scrollable && node.scrollWidth > node.clientWidth + 1) return true;
    }
    return false;
  };

  const clipped = [];
  const clippedWithTooltip = [];
  const overflow = [];
  const unnamedButtons = [];
  const missingAlt = [];
  const unlabeledInputs = [];

  for (const el of document.querySelectorAll('body *')) {
    if (!isVisible(el)) continue;
    const style = getComputedStyle(el);
    const nowrap = style.whiteSpace === 'nowrap' || style.textOverflow === 'ellipsis';
    // 文字被裁：内容宽度超过可视宽度
    if (nowrap && el.scrollWidth > el.clientWidth + 2 && (el.textContent ?? '').trim()) {
      const entry = { el: label(el), scrollWidth: el.scrollWidth, clientWidth: el.clientWidth };
      // 有 title 说明省略号配了完整文本提示，属可接受
      if (el.getAttribute('title') || el.closest('[title]')) clippedWithTooltip.push(entry);
      else clipped.push(entry);
    }
    // 溢出卡片：右边界超出所属卡片，且不是放在合法的横向滚动容器里
    const card = ownerCard(el);
    if (card && el !== card && !insideScroller(el, card)) {
      const cardRect = card.getBoundingClientRect();
      const rect = el.getBoundingClientRect();
      if (rect.width > 24 && rect.right > cardRect.right + 1) {
        overflow.push({ el: label(el), overBy: Math.round(rect.right - cardRect.right), card: label(card) });
      }
    }
    if (el.tagName === 'BUTTON' || el.getAttribute('role') === 'button') {
      const name = (el.getAttribute('aria-label') ?? el.textContent ?? '').trim()
        || (el.getAttribute('title') ?? '');
      if (!name) unnamedButtons.push(label(el));
    }
    if (el.tagName === 'IMG' && !el.getAttribute('alt') && el.getAttribute('aria-hidden') !== 'true') {
      missingAlt.push(label(el));
    }
    // Element Plus 的 select 内部输入框由外层 aria-label 承载语义，跳过
    if (['INPUT', 'SELECT', 'TEXTAREA'].includes(el.tagName) && el.type !== 'hidden'
      && !el.classList.contains('el-select__input')) {
      const id = el.getAttribute('id');
      const hasLabel = (id && document.querySelector(`label[for="${CSS.escape(id)}"]`))
        || el.getAttribute('aria-label') || el.getAttribute('aria-labelledby')
        || el.closest('label') || el.getAttribute('placeholder');
      if (!hasLabel) unlabeledInputs.push(label(el));
    }
  }

  const doc = document.documentElement;
  return {
    horizontalScroll: Math.max(0, doc.scrollWidth - doc.clientWidth),
    clipped: clipped.slice(0, 12),
    clippedWithTooltip: clippedWithTooltip.slice(0, 6),
    overflow: overflow.slice(0, 12),
    unnamedButtons: unnamedButtons.slice(0, 8),
    missingAlt: missingAlt.slice(0, 8),
    unlabeledInputs: unlabeledInputs.slice(0, 8),
    counts: {
      clipped: clipped.length,
      clippedWithTooltip: clippedWithTooltip.length,
      overflow: overflow.length,
      unnamedButtons: unnamedButtons.length,
      missingAlt: missingAlt.length,
      unlabeledInputs: unlabeledInputs.length,
    },
  };
};

const accounts = {
  admin: { email: 'admin@local.test', password: adminPassword },
  user: { email: process.env.UI_USER_EMAIL ?? 'skilltest-1789184820@local.test', password: userPassword },
};

const results = [];
let current = null;
for (const entry of pages) {
  if (entry.who !== current) {
    await context.clearCookies();
    await login(entry.who, accounts[entry.who].email, accounts[entry.who].password);
    current = entry.who;
  }
  consoleErrors.length = 0;
  failedRequests = [];
  await page.goto(`${base}${entry.path}`, { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle', { timeout: 12_000 }).catch(() => {});
  await page.waitForTimeout(700);
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(200);

  const measured = await page.evaluate(probe);
  const record = {
    path: entry.path,
    ...measured,
    consoleErrors: [...new Set(consoleErrors)].slice(0, 6),
    failedRequests: [...new Set(failedRequests)].slice(0, 8),
  };
  results.push(record);

  const flag = (n) => (n > 0 ? ` ${n}` : ' -');
  console.log(
    `${entry.path.padEnd(26)} 裁${flag(measured.counts.clipped)}`
    + ` 溢出${flag(measured.counts.overflow)}`
    + ` 横滚${measured.horizontalScroll > 0 ? ` ${measured.horizontalScroll}px` : ' -'}`
    + ` 报错${flag(record.consoleErrors.length)}`
    + ` 失败请求${flag(record.failedRequests.length)}`
    + ` 无名按钮${flag(measured.counts.unnamedButtons)}`
    + ` 缺alt${flag(measured.counts.missingAlt)}`
    + ` 无标签输入${flag(measured.counts.unlabeledInputs)}`,
  );
}

writeFileSync(resolve(outDir, `audit-${viewportWidth}-${theme}.json`), `${JSON.stringify(results, null, 2)}\n`, 'utf8');
console.log(`\nwrote ${resolve(outDir, `audit-${viewportWidth}-${theme}.json`)}`);

console.log('\n=== 需要处理的明细 ===');
for (const record of results) {
  const issues = [];
  if (record.counts.clipped) issues.push(`  文字被裁 ${record.counts.clipped}: ${JSON.stringify(record.clipped.slice(0, 4))}`);
  if (record.counts.overflow) issues.push(`  溢出卡片 ${record.counts.overflow}: ${JSON.stringify(record.overflow.slice(0, 4))}`);
  if (record.horizontalScroll > 0) issues.push(`  横向滚动 ${record.horizontalScroll}px`);
  if (record.consoleErrors.length) issues.push(`  控制台报错: ${JSON.stringify(record.consoleErrors.slice(0, 3))}`);
  if (record.failedRequests.length) issues.push(`  失败请求: ${JSON.stringify(record.failedRequests.slice(0, 4))}`);
  if (record.counts.unnamedButtons) issues.push(`  无名按钮 ${record.counts.unnamedButtons}: ${JSON.stringify(record.unnamedButtons.slice(0, 3))}`);
  if (record.counts.missingAlt) issues.push(`  缺 alt ${record.counts.missingAlt}`);
  if (record.counts.unlabeledInputs) issues.push(`  无标签输入 ${record.counts.unlabeledInputs}: ${JSON.stringify(record.unlabeledInputs.slice(0, 3))}`);
  if (issues.length) console.log(`${record.path}\n${issues.join('\n')}`);
}

await browser.close();
