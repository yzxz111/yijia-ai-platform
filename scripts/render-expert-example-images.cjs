// Reproduce the seven 1072x280 Expert example cards from the checked-in presentation text.
// Run from system/: node scripts/render-expert-example-images.cjs --check
//               node scripts/render-expert-example-images.cjs --write
//               node scripts/render-expert-example-images.cjs --output-dir <directory>
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('../web-console/node_modules/@playwright/test');

const systemRoot = path.resolve(__dirname, '..');
const curatedImageDir = path.join(systemRoot, 'fixtures/catalog-snapshot/images/skills');
const learningImage = path.join(systemRoot, 'fixtures/portable-import/ai-learning-assistant/source-assets/example-image.png');
const catalog = JSON.parse(fs.readFileSync(path.join(systemRoot, 'fixtures/catalog-snapshot/catalog.json'), 'utf8'));
const learning = JSON.parse(fs.readFileSync(path.join(systemRoot, 'fixtures/portable-import/ai-learning-assistant/expected-admin-presentation.json'), 'utf8'));
const order = [
  'ai-office-assistant',
  'content-summarizer',
  'content-writing-expert',
  'document-review-expert',
  'sales-analysis-expert',
  'social-media-operations-expert',
];
const entries = order.map(code => {
  const skill = catalog.skills.find(item => item.code === code);
  if (!skill?.preview_zh?.request || !skill.preview_zh.material) throw new Error(`Missing Chinese example text: ${code}`);
  return { code, name: skill.name, request: skill.preview_zh.request, material: skill.preview_zh.material };
});
entries.push({
  code: 'ai-learning-assistant',
  name: 'AI学习助手',
  request: learning.preview.request,
  material: learning.preview.material,
  learning: true,
});

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[char]);
}

function textMarkup(value) {
  // Keep amounts, dates, units, and file names together at a natural line break.
  // The spans add no characters, so rendered text stays byte-for-byte faithful.
  const fact = /(?:\d{1,3}(?:,\d{3})+|\d+(?:\.\d+)?)(?:\s*)(?:元|天|分钟|mL)|\d{1,2}\/\d{1,2}|v\d+\.(?:docx|pdf)|修改建议/g;
  let html = '';
  let start = 0;
  for (const match of String(value).matchAll(fact)) {
    html += escapeHtml(value.slice(start, match.index));
    html += `<span class="nobr">${escapeHtml(match[0])}</span>`;
    start = match.index + match[0].length;
  }
  return html + escapeHtml(value.slice(start));
}

const cardCss = `
  * { box-sizing: border-box; }
  html, body { margin: 0; padding: 0; }
  body { font-family: "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", sans-serif; }
  .card {
    width: 536px; height: 140px; display: flex; flex-direction: column;
    justify-content: center; gap: 7px; padding: 12px 16px;
    border-radius: 12px; background: #f4f4f5; color: #172137;
  }
  .request { width: 100%; font-size: 16px; font-weight: 500; line-height: 24px; overflow-wrap: anywhere; white-space: pre-line; }
  .material { width: 100%; font-size: 13px; font-weight: 400; line-height: 18px; color: #5b677c; overflow-wrap: anywhere; white-space: pre-line; }
  .nobr { white-space: nowrap; }
  .card.compact { gap: 6px; padding-top: 10px; padding-bottom: 10px; }
  .card.compact .material { font-size: 12.5px; line-height: 17px; }
  .card.dense { gap: 4px; padding-top: 9px; padding-bottom: 9px; }
  .card.dense .material { font-size: 12px; line-height: 16px; }
  .card.learning { background: #f4f4f5; border: 1px solid #d8e2f3; }
  .learning .request { border-left: 4px solid #5574b5; padding-left: 12px; font-weight: 700; color: #1b2b49; }
  .learning .material { border-top: 1px solid #d8e2f3; padding-top: 6px; }
  .learning .request-line, .learning .material-line { display: block; }
`;

function cardMarkup(entry) {
  const firstSentenceEnd = text => text.indexOf('。') + 1;
  const request = entry.learning
    ? `<span class="request-line">${textMarkup(entry.request.slice(0, firstSentenceEnd(entry.request)))}</span><span class="request-line">${textMarkup(entry.request.slice(firstSentenceEnd(entry.request)))}</span>`
    : textMarkup(entry.request);
  const material = entry.learning
    ? `<span class="material-line">${textMarkup(entry.material.slice(0, firstSentenceEnd(entry.material)))}</span><span class="material-line">${textMarkup(entry.material.slice(firstSentenceEnd(entry.material)))}</span>`
    : textMarkup(entry.material);
  return `<article class="card${entry.learning ? ' learning' : ''}" data-code="${escapeHtml(entry.code)}">
    <div class="request">${request}</div>
    <div class="material">${material}</div>
  </article>`;
}

function cardDocument(entry) {
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><style>${cardCss}</style></head><body>${cardMarkup(entry)}</body></html>`;
}

function options(args) {
  let mode = 'check';
  let modeSet = false;
  let outputDir;
  let only;
  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    if (arg === '--check' || arg === '--write' || arg === '--output-dir') {
      if (modeSet) throw new Error('Choose exactly one output mode');
      modeSet = true;
      mode = arg.slice(2);
      if (arg === '--output-dir') {
        const value = args[++index];
        if (!value || value.startsWith('--')) throw new Error('--output-dir needs a directory');
        outputDir = path.resolve(value);
      }
    } else if (arg === '--only') {
      only = args[++index];
      if (!order.includes(only) && only !== 'ai-learning-assistant') throw new Error(`Unknown Expert: ${only}`);
    } else {
      throw new Error(`Unknown option: ${arg}`);
    }
  }
  return { mode, outputDir, only };
}

function destination(entry, selected) {
  if (selected.mode === 'output-dir') return path.join(selected.outputDir, `${entry.code}-example.png`);
  return entry.learning ? learningImage : path.join(curatedImageDir, `${entry.code}-example.png`);
}

async function measure(page, entry) {
  return page.evaluate(({ request, material }) => {
    const card = document.querySelector('.card');
    const main = card.querySelector('.request');
    const detail = card.querySelector('.material');
    const box = card.getBoundingClientRect();
    const a = main.getBoundingClientRect();
    const b = detail.getBoundingClientRect();
    const css = getComputedStyle(detail);
    const eps = 0.6;
    const checks = {
      exactText: main.textContent.trim() === request && detail.textContent.trim() === material,
      correctSize: box.width === 536 && box.height === 140,
      horizontal: card.scrollWidth <= card.clientWidth + eps && main.scrollWidth <= main.clientWidth + eps && detail.scrollWidth <= detail.clientWidth + eps,
      vertical: card.scrollHeight <= card.clientHeight + eps && main.scrollHeight <= main.clientHeight + eps && detail.scrollHeight <= detail.clientHeight + eps,
      inside: a.top >= box.top + 8 - eps && b.bottom <= box.bottom - 8 + eps,
      separate: b.top >= a.bottom - eps,
    };
    return {
      checks,
      requestPx: { fontSize: getComputedStyle(main).fontSize, lineHeight: getComputedStyle(main).lineHeight, height: a.height },
      materialPx: { fontSize: css.fontSize, lineHeight: css.lineHeight, height: b.height },
      topSpace: +(a.top - box.top).toFixed(2),
      bottomSpace: +(box.bottom - b.bottom).toFixed(2),
    };
  }, entry);
}

async function main() {
  const selected = options(process.argv.slice(2));
  const targets = selected.only ? entries.filter(entry => entry.code === selected.only) : entries;
  const cached = chromium.executablePath();
  const chrome = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
  const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
  const executablePath = [cached, chrome, edge].find(candidate => fs.existsSync(candidate));
  if (!executablePath) throw new Error('No Chromium-family browser found');
  const browser = await chromium.launch({ executablePath, headless: true });
  try {
    const context = await browser.newContext({ viewport: { width: 536, height: 140 }, deviceScaleFactor: 2, locale: 'zh-CN' });
    const page = await context.newPage();
    for (const entry of targets) {
      await page.setContent(cardDocument(entry), { waitUntil: 'load' });
      await page.evaluate(() => document.fonts.ready);
      let metrics;
      for (const density of ['', 'compact', 'dense']) {
        await page.locator('.card').evaluate((element, value) => {
          element.classList.remove('compact', 'dense');
          if (value) element.classList.add(value);
        }, density);
        metrics = await measure(page, entry);
        if (Object.values(metrics.checks).every(Boolean)) {
          metrics.density = density || 'normal';
          break;
        }
      }
      if (!Object.values(metrics.checks).every(Boolean)) {
        throw new Error(`${entry.code} does not fit: ${JSON.stringify(metrics)}`);
      }
      const filename = `${entry.code}-example.png`;
      const png = await page.screenshot({ animations: 'disabled' });
      const target = destination(entry, selected);
      if (selected.mode === 'check') {
        if (!fs.existsSync(target) || !png.equals(fs.readFileSync(target))) {
          throw new Error(`${filename} differs from the checked-in image: ${target}`);
        }
      } else {
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.writeFileSync(target, png);
      }
      console.log(`${selected.mode}: ${filename}, ${metrics.density}, request ${metrics.requestPx.height}px, material ${metrics.materialPx.height}px, top/bottom ${metrics.topSpace}/${metrics.bottomSpace}px; no overflow`);
    }
    await context.close();
    console.log(`${selected.mode}: ${targets.length} Expert example images`);
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
