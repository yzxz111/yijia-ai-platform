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
  // Model the user confirming deliberate deletion or replacement in this local trial.
  page.on('dialog', dialog => dialog.accept());
  await page.goto(base);
});
after(async () => { if (browser) await browser.close(); if (server) await new Promise(resolve => server.close(resolve)); });

async function add(title) { await page.locator('#title').fill(title); await page.locator('#add-form button').click(); }
async function importData(data) { await page.locator('#import').setInputFiles({name:'backup.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(data))}); }
async function items() { return page.locator('#tasks li').count(); }
test('blank input does not create a task',async()=>{await add('   ');assert.equal(await items(),0);});
test('trimmed task persists after reload',async()=>{await add('  准备材料  ');await page.reload();assert.equal(await items(),1);assert.equal((await page.locator('#tasks li span').textContent()).trim(),'准备材料');});
test('toggle persists after reload',async()=>{await add('测试');await page.locator('#tasks input[type=checkbox]').check();await page.reload();assert.equal(await page.locator('#tasks input').isChecked(),true);});
test('delete persists and empty state returns',async()=>{await add('删除');await page.locator('#tasks li button').click();await page.reload();assert.equal(await items(),0);assert.equal(await page.locator('#empty').isVisible(),true);});
test('user text cannot create executable markup',async()=>{await add('<img src=x onerror=alert(1)>');assert.equal(await page.locator('#tasks img').count(),0);assert.ok((await page.locator('#tasks').textContent()).includes('<img'));});
test('valid import replaces records and survives reload',async()=>{await importData({version:1,tasks:[{id:'stable',title:'导入项',done:true}]});await page.waitForFunction(()=>document.querySelector('#tasks').textContent.includes('导入项'));await page.reload();assert.equal(await items(),1);assert.equal(await page.locator('#tasks input').isChecked(),true);});
test('invalid import preserves current list and storage',async()=>{await add('保留');const original=await page.evaluate(()=>localStorage.getItem('task-desk-v1'));await importData({version:1,tasks:[{id:'x',title:'A',done:false},{id:'x',title:'B',done:false}]});await page.waitForTimeout(150);assert.equal(await items(),1);assert.equal(await page.evaluate(()=>localStorage.getItem('task-desk-v1')),original);assert.ok((await page.locator('#status').textContent()).length>0);});
test('corrupt storage is reported and not overwritten during startup',async()=>{await page.evaluate(()=>localStorage.setItem('task-desk-v1','{broken'));await page.reload();assert.equal(await page.evaluate(()=>localStorage.getItem('task-desk-v1')),'{broken');assert.ok((await page.locator('#status').textContent()).length>0);});
test('blocked storage retains working view and reports failure',async()=>{await page.addInitScript(()=>{Storage.prototype.setItem=function(){throw new DOMException('Blocked','QuotaExceededError')}});await page.reload();await add('临时项');assert.equal(await items(),1);const status=await page.locator('#status').textContent();assert.match(status,/无法|失败|未保存|受阻|导出/);});
test('export produces recoverable JSON and mobile layout fits',async()=>{await add('备份');const promise=page.waitForEvent('download',{timeout:2000});await page.locator('#export').click();const download=await promise;const saved=await download.path();const value=JSON.parse(fs.readFileSync(saved,'utf8'));assert.equal(value.version,1);assert.equal(value.tasks[0].title,'备份');assert.equal(typeof value.tasks[0].done,'boolean');assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.screenshot({path:path.join(__dirname,'trial-mobile.png'),fullPage:true});});
