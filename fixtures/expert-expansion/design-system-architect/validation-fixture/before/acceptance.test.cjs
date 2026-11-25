const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
let modulePath;
for (let candidate = __dirname; ; candidate = path.dirname(candidate)) {
  for (const rel of ['web-console/node_modules/@playwright/test', 'system/web-console/node_modules/@playwright/test']) {
    if (fs.existsSync(path.join(candidate, rel))) modulePath = path.join(candidate, rel);
  }
  if (modulePath || path.dirname(candidate) === candidate) break;
}
const { chromium } = require(modulePath || process.env.PLAYWRIGHT_MODULE);
let server, browser, page, base;
before(async () => {
  server = http.createServer((req, res) => {
    const name = req.url === '/' ? 'index.html' : req.url.slice(1);
    if (!['index.html', 'styles.css', 'app.js'].includes(name) || !fs.existsSync(path.join(__dirname, name))) { res.writeHead(404); return res.end(); }
    res.writeHead(200, { 'Content-Type': name.endsWith('.css') ? 'text/css' : name.endsWith('.js') ? 'text/javascript' : 'text/html; charset=utf-8' });
    res.end(fs.readFileSync(path.join(__dirname, name)));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
  browser = await chromium.launch({ executablePath: process.env.EXPERT_RENDER_BROWSER || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
});
beforeEach(async () => {
  if (page) await page.context().close();
  const context = await browser.newContext({viewport:{width:390,height:844}});
  page = await context.newPage();
  await page.goto(base);
});
after(async () => { if (browser) await browser.close(); if (server) await new Promise(resolve => server.close(resolve)); });

function luminance(rgb) {
  const values = rgb.match(/[\d.]+/g).slice(0,3).map(Number).map(v => { v /= 255; return v <= .04045 ? v / 12.92 : ((v+.055)/1.055)**2.4; });
  return .2126*values[0]+.7152*values[1]+.0722*values[2];
}
for (const theme of ['light','dark']) test(`${theme} enabled button label contrast is at least 4.5`, async () => {
  const colors = await page.locator(`#${theme}`).evaluate(el=>({fg:getComputedStyle(el).color,bg:getComputedStyle(el).backgroundColor}));
  const a=luminance(colors.fg),b=luminance(colors.bg);
  assert.ok((Math.max(a,b)+.05)/(Math.min(a,b)+.05)>=4.5,JSON.stringify(colors));
});
test('Tab focus is visible on both enabled controls', async () => {
  for (const id of ['light','dark']) {
    await page.keyboard.press('Tab'); assert.equal(await page.evaluate(()=>document.activeElement.id),id);
    const focus=await page.locator(`#${id}`).evaluate(el=>({width:parseFloat(getComputedStyle(el).outlineWidth),style:getComputedStyle(el).outlineStyle,shadow:getComputedStyle(el).boxShadow}));
    assert.ok((focus.width>=2 && focus.style!=='none') || focus.shadow!=='none');
  }
});
test('native keyboard activation works and disabled controls remain inert', async () => {
  await page.keyboard.press('Tab'); await page.keyboard.press('Enter'); assert.equal(await page.locator('#result').textContent(),'1');
  await page.locator('button:disabled').evaluateAll(els=>els.forEach(el=>el.click())); assert.equal(await page.locator('#result').textContent(),'1');
});
test('same semantic tokens are populated for each theme', async () => {
  for (const id of ['light','dark']) {
    for (const name of ['--action-bg','--action-fg','--focus-ring','--radius-control','--space-control-x'])
      assert.ok((await page.locator(`#${id}`).evaluate((el,name)=>getComputedStyle(el).getPropertyValue(name),name)).trim());
  }
});
test('mobile viewport has no horizontal overflow and notice is preserved', async () => {
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  assert.ok((await page.locator('main').textContent()).includes('保留这段用户说明'));
  await page.screenshot({path:path.join(__dirname,'trial-mobile.png'),fullPage:true});
});
