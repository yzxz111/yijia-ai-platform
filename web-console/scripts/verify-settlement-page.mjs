// 验证「结算垫付」页面修好后的真实渲染：KPI 数值、明细范围、明细行数，以及是否仍处于降级态。
import { chromium } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const base = process.env.UI_BASE ?? 'http://127.0.0.1:18081';
const password = process.env.UI_ADMIN_PASSWORD;
if (!password) throw new Error('UI_ADMIN_PASSWORD is required');

const outDir = resolve('test-results/ui-refresh/after');
mkdirSync(outDir, { recursive: true });

const browser = await chromium.launch({ channel: 'chromium' });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
await context.addInitScript(() => {
  try {
    window.localStorage.setItem('app-theme', 'light');
    window.localStorage.setItem('yzh_onboarding_seen', '1');
    window.localStorage.setItem('yzh-welcome-seen', '1');
  } catch { /* 忽略     */ }
});
const page = await context.newPage();
const consoleErrors = [];
const failed = [];
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 160)); });
page.on('response', (r) => { if (r.status() >= 400) failed.push(`${r.status()} ${r.request().method()} ${new URL(r.url()).pathname}`); });

await page.goto(`${base}/login`, { waitUntil: 'domcontentloaded' });
await page.evaluate(() => window.localStorage.setItem('yzh_device_id', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'));
await page.reload({ waitUntil: 'domcontentloaded' });
const tab = page.getByRole('button', { name: '密码登录' });
if (await tab.count()) await tab.first().click();
await page.locator('[data-test="login-identifier"]').fill('admin@local.test');
await page.locator('[data-test="login-password"]').fill(password);
const consent = page.locator('[data-test="login-consent"] .el-checkbox__inner');
if (await consent.count()) await consent.first().click();
await page.locator('[data-test="login-submit"]').click();
await page.waitForURL(/\/admin\//u, { timeout: 25_000 });

await page.goto(`${base}/admin/settlement-deficit`, { waitUntil: 'domcontentloaded' });
await page.waitForLoadState('networkidle', { timeout: 12_000 }).catch(() => {});
await page.waitForTimeout(1200);

const text = async (selector) => {
  const locator = page.locator(selector);
  return (await locator.count()) ? (await locator.first().innerText()).trim() : null;
};

const observed = {
  unavailable: (await page.locator('[data-test="deficit-unavailable"]').count()) > 0,
  total: await text('[data-test="deficit-total"]'),
  count: await text('[data-test="deficit-count"]'),
  tokens: await text('[data-test="deficit-tokens"]'),
  scope: await text('[data-test="deficit-scope"]'),
  detailRows: await page.locator('.settlement-deficit__table tbody tr').count(),
  emptyRow: (await page.locator('[data-test="deficit-empty"]').count()) > 0,
  consoleErrors: [...new Set(consoleErrors)],
  failedRequests: [...new Set(failed)],
};
console.log(JSON.stringify(observed, null, 2));

await page.screenshot({ path: resolve(outDir, 'settlement-deficit.png'), fullPage: false });
console.log(`screenshot -> ${resolve(outDir, 'settlement-deficit.png')}`);

const verdict = {
  rendersData: !observed.unavailable && observed.total !== null && observed.total !== '—',
  notZeroFabricated: observed.total !== '¥0.00',
  scopeSaysTruncated: observed.scope === '仅最近若干条',
  detailRowsRendered: observed.detailRows > 1,
  noConsoleErrors: observed.consoleErrors.length === 0,
  noFailedRequests: observed.failedRequests.length === 0,
};
for (const [key, value] of Object.entries(verdict)) console.log(`>>> ${key}: ${value}`);
writeFileSync(resolve('test-results/ui-refresh/after', 'settlement-deficit-page.json'),
  `${JSON.stringify({ observed, verdict }, null, 2)}\n`, 'utf8');

await browser.close();
