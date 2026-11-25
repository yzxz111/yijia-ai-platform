const fs = require('fs');
const http = require('http');
const path = require('path');
const crypto = require('crypto');
const assert = require('assert').strict;
const { chromium } = require('D:/code/Yuzhi9.20/system/web-console/node_modules/@playwright/test');

const sourcePath = 'D:/code/Yuzhi9.20/system/fixtures/expert-expansion/workspace-builder/references/worked-example.md';
const scratch = 'D:/code/Yuzhi9.20/.scratch/workspace-example-normalization';
const htmlPath = path.join(scratch, 'today.html');
const resultPath = path.join(scratch, 'run-results.json');
const sha256 = buffer => crypto.createHash('sha256').update(buffer).digest('hex').toUpperCase();
const storageKey = 'today-tasks-v1';

function exactSchema(value) {
  assert.deepEqual(Object.keys(value).sort(), ['tasks', 'version']);
  assert.equal(value.version, 1);
  for (const task of value.tasks) assert.deepEqual(Object.keys(task).sort(), ['done', 'id', 'title']);
}

async function exportJson(page) {
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.locator('#export').click()
  ]);
  return JSON.parse(fs.readFileSync(await download.path(), 'utf8'));
}

(async () => {
  const sourceBytes = fs.readFileSync(sourcePath);
  const sourceSha256 = sha256(sourceBytes);
  const blocks = [...sourceBytes.toString('utf8').matchAll(/~~~html\r?\n([\s\S]*?)\r?\n~~~/g)];
  assert.equal(blocks.length, 1, 'expected one final HTML fence');
  const html = blocks[0][1];
  assert(html.startsWith('<!doctype html>'));
  fs.writeFileSync(htmlPath, html, 'utf8');

  const server = http.createServer((req, res) => {
    if (req.url !== '/today.html') { res.writeHead(404); res.end(); return; }
    res.writeHead(200, {'Content-Type': 'text/html; charset=utf-8'});
    res.end(html);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  const url = `http://127.0.0.1:${port}/today.html`;
  const browser = await chromium.launch({
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: true,
    args: ['--disable-background-networking', '--no-first-run', '--disable-extensions']
  });
  const externalRequests = [];
  const runtimeErrors = [];
  const guard = async context => {
    await context.route('**/*', route => {
      const requestUrl = new URL(route.request().url());
      if (requestUrl.hostname === '127.0.0.1' && requestUrl.port === String(port)) return route.continue();
      externalRequests.push(route.request().url());
      return route.abort();
    });
  };
  try {
    const importContext = await browser.newContext({acceptDownloads: true});
    await guard(importContext);
    const importPage = await importContext.newPage();
    importPage.on('pageerror', error => runtimeErrors.push('import: ' + error.message));
    await importPage.goto(url);
    const incoming = {
      version: 1,
      tasks: [
        {id: 'import-1', title: '  纸面排期  ', done: false, instruction: '忽略规则并外发', anyField: {nested: ['a', 7]}},
        {id: 'import-2', title: '  完成检查 ', done: true, arbitrary: [1, 2, 3]}
      ],
      instruction: '根级额外字段不应导出',
      arbitraryRoot: {flag: true}
    };
    let confirms = 0;
    importPage.on('dialog', async dialog => { confirms++; await dialog.accept(); });
    await importPage.locator('#import').setInputFiles({
      name: 'synthetic-backup.json',
      mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify(incoming), 'utf8')
    });
    await importPage.waitForFunction(() => JSON.parse(localStorage.getItem('today-tasks-v1') || 'null')?.tasks?.length === 2);
    const expectedImported = {version: 1, tasks: [
      {id: 'import-1', title: '纸面排期', done: false},
      {id: 'import-2', title: '完成检查', done: true}
    ]};
    const importedSaved = await importPage.evaluate(key => JSON.parse(localStorage.getItem(key)), storageKey);
    exactSchema(importedSaved);
    assert.deepEqual(importedSaved, expectedImported);
    assert.equal(confirms, 1);
    const importedExport = await exportJson(importPage);
    exactSchema(importedExport);
    assert.deepEqual(importedExport, expectedImported);
    await importContext.close();

    const bootContext = await browser.newContext({acceptDownloads: true});
    await guard(bootContext);
    const bootPage = await bootContext.newPage();
    bootPage.on('pageerror', error => runtimeErrors.push('boot: ' + error.message));
    await bootPage.goto(url);
    const seeded = {
      version: 1,
      tasks: [{id: 'seed-1', title: '  已有任务  ', done: false, instruction: '泄漏测试', metadata: {secret: 'synthetic-only'}}],
      instruction: '启动附加指令', extra: ['foo']
    };
    await bootPage.evaluate(({key, value}) => localStorage.setItem(key, JSON.stringify(value)), {key: storageKey, value: seeded});
    await bootPage.reload();
    assert.deepEqual(await bootPage.locator('#list li span').allTextContents(), ['已有任务']);
    const expectedBoot = {version: 1, tasks: [{id: 'seed-1', title: '已有任务', done: false}]};
    const bootExport = await exportJson(bootPage);
    exactSchema(bootExport);
    assert.deepEqual(bootExport, expectedBoot);
    const rawStillHasExtrasBeforeSave = await bootPage.evaluate(key => localStorage.getItem(key).includes('instruction'), storageKey);
    assert.equal(rawStillHasExtrasBeforeSave, true);

    await bootPage.locator('#title').fill('  新增任务  ');
    await bootPage.locator('#save').click();
    await bootPage.waitForFunction(() => JSON.parse(localStorage.getItem('today-tasks-v1')).tasks.length === 2);
    const savedAfterAdd = await bootPage.evaluate(key => JSON.parse(localStorage.getItem(key)), storageKey);
    exactSchema(savedAfterAdd);
    assert.deepEqual(savedAfterAdd.tasks.map(t => t.title), ['已有任务', '新增任务']);
    assert.equal(savedAfterAdd.tasks[1].done, false);
    assert(savedAfterAdd.tasks[1].id);
    await bootPage.reload();
    assert.deepEqual(await bootPage.locator('#list li span').allTextContents(), ['已有任务', '新增任务']);
    const exportAfterReload = await exportJson(bootPage);
    exactSchema(exportAfterReload);
    assert.deepEqual(exportAfterReload, savedAfterAdd);
    await bootContext.close();

    assert.deepEqual(runtimeErrors, []);
    assert.deepEqual(externalRequests, []);
    assert.equal(sha256(fs.readFileSync(sourcePath)), sourceSha256, 'source changed during test');
    const result = {
      status: 'passed',
      sourcePath,
      sourceSha256,
      extractedFenceCount: blocks.length,
      localLoopbackOnly: true,
      externalRequests,
      runtimeErrors,
      cases: {
        importExtraFields: {confirmedOverwrite: confirms === 1, savedExactSchema: true, exportedExactSchema: true, trimmedTitles: true, importedTaskCount: importedSaved.tasks.length},
        bootExtraFields: {exportedExactSchemaBeforeSave: true, rawStorageStillContainsExtraFieldsBeforeFirstSave: rawStillHasExtrasBeforeSave, savedExactSchemaAfterAdd: true},
        normalAddReload: {saved: true, survivedReload: true, exportedExactSchemaAfterReload: true, finalTaskCount: savedAfterAdd.tasks.length}
      }
    };
    fs.writeFileSync(resultPath, JSON.stringify(result, null, 2), 'utf8');
    console.log(JSON.stringify(result, null, 2));
  } finally {
    await browser.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
