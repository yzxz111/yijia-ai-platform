// Local, deterministic UI artwork and review previews for one authored Expert.
// Usage: node scripts/render-expert-expansion.cjs --code academic-writing-mentor --assets|--preview
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('../web-console/node_modules/@playwright/test');

const args = process.argv.slice(2);
const code = args[args.indexOf('--code') + 1];
if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(code || '') || !args.includes('--code')) throw new Error('Supply --code');
const root = path.resolve(__dirname, '../fixtures/expert-expansion');
const dest = path.join(root, code);
const spec = JSON.parse(fs.readFileSync(path.join(root, 'specs', `${code}.json`), 'utf8'));
const artworkByCode = {
  'senior-software-engineer': () => require('./expert-artwork/senior-software-engineer.cjs'),
  'wechat-miniapp-developer': () => require('./expert-artwork/wechat-miniapp-developer.cjs'),
  'wechat-official-account-operator': () => require('./expert-artwork/wechat-official-account-operator.cjs'),
  'adaptive-delivery-expert': () => require('./expert-artwork/adaptive-delivery-expert.cjs'),
  'news-briefing-expert': () => require('./expert-artwork/news-briefing-expert.cjs'),
  'design-system-architect': () => require('./expert-artwork/design-system-architect.cjs'),
  'fullstack-delivery-expert': () => require('./expert-artwork/fullstack-delivery-expert.cjs'),
  'startup-partner': () => require('./expert-artwork/startup-partner.cjs'),
  'workspace-builder': () => require('./expert-artwork/workspace-builder.cjs'),
};
const artwork = artworkByCode[code]?.() || null;
if (!artwork && code !== 'academic-writing-mentor') throw new Error(`No reviewed artwork for ${code}`);
const esc = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const font = '"Microsoft YaHei", "Segoe UI", sans-serif';
function document(body, css = '') {
  return `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>${esc(spec.title.zh)}</title><style>*{box-sizing:border-box}html,body{margin:0}body{font-family:${font};color:#182a47}${css}</style>${body}</html>`;
}
const paperMark = `<g fill="none" stroke="currentColor" stroke-width="7" stroke-linecap="round" stroke-linejoin="round"><path d="M68 47h69l28 29v103H68z"/><path d="M137 47v29h28M88 94h45M88 114h27M88 135h20"/><circle cx="145" cy="146" r="28"/><path d="m166 167 21 22"/></g>`;
function icon() {
  if (artwork) return document(artwork.icon, 'svg{display:block}');
  return document(`<svg width="240" height="240" viewBox="0 0 240 240"><rect width="240" height="240" rx="56" fill="#173f4b"/><circle cx="202" cy="28" r="81" fill="#245c69"/><circle cx="20" cy="224" r="58" fill="#256776"/><g color="#f4fcfa">${paperMark}</g><circle cx="145" cy="146" r="7" fill="#9be2c6"/></svg>`);
}
function illustration() {
  if (artwork) return document(artwork.illustration, 'svg{display:block}');
  // Language-neutral artwork: paths and geometric shapes only, no text or labels.
  return document(`<svg width="1440" height="480" viewBox="0 0 1440 480">
    <rect width="1440" height="480" fill="#173f4b"/>
    <circle cx="235" cy="236" r="230" fill="#1b4a56"/><circle cx="1135" cy="247" r="260" fill="#1e505d"/>
    <path d="M360 246H1085" stroke="#4a8790" stroke-width="3" stroke-dasharray="7 12"/>
    <g transform="rotate(-9 289 238)"><rect x="176" y="99" width="208" height="276" rx="17" fill="#548892"/></g>
    <rect x="206" y="90" width="208" height="276" rx="17" fill="#ecf6f2"/>
    <rect x="233" y="120" width="71" height="19" rx="7" fill="#9be2c6"/>
    <g stroke="#a6beba" stroke-width="8" stroke-linecap="round"><path d="M237 174h146M237 200h116M237 245h146M237 271h128M237 317h81"/></g>
    <circle cx="680" cy="235" r="135" fill="#245c69"/><circle cx="680" cy="235" r="108" fill="none" stroke="#508894" stroke-dasharray="4 11" stroke-width="2"/>
    <circle cx="663" cy="217" r="61" fill="#d8eee6" stroke="#9be2c6" stroke-width="12"/>
    <path d="m708 262 60 61" stroke="#9be2c6" stroke-width="22" stroke-linecap="round"/>
    <path d="m635 216 19 19 34-39" fill="none" stroke="#286252" stroke-width="9" stroke-linecap="round" stroke-linejoin="round"/>
    <rect x="977" y="87" width="242" height="302" rx="19" fill="#ecf6f2"/>
    <rect x="1007" y="119" width="123" height="14" rx="7" fill="#7aaa9e"/>
    <g stroke="#a6beba" stroke-width="8" stroke-linecap="round"><path d="M1050 180h131M1050 207h103M1050 261h131M1050 288h112M1050 342h88"/></g>
    <g fill="#a2d4be"><rect x="1006" y="169" width="23" height="23" rx="6"/><rect x="1006" y="250" width="23" height="23" rx="6"/><rect x="1006" y="331" width="23" height="23" rx="6"/></g>
    <circle cx="1202" cy="346" r="47" fill="#f2d996"/><path d="m1180 345 16 16 29-33" fill="none" stroke="#65664b" stroke-width="8" stroke-linecap="round" stroke-linejoin="round"/>
    <g fill="#80b6ae"><circle cx="487" cy="139" r="6"/><circle cx="886" cy="348" r="7"/><circle cx="1298" cy="178" r="6"/></g>
  </svg>`, 'svg{display:block}');
}
function example() {
  const p = spec.preview.zh;
  return document(`<article class="example"><div class="request">${esc(p.request)}</div><div class="material">${esc(p.material)}</div></article>`, `.example{width:536px;height:140px;padding:14px 16px;background:#f4f4f5;border-radius:12px;display:flex;flex-direction:column;justify-content:center;gap:8px}.request{font-size:16px;line-height:24px;font-weight:500}.material{font-size:13px;line-height:19px;color:#64748b}`);
}
const textRuns = runs => (runs || []).map(r => `<span style="${r.color ? `color:${esc(r.color)};` : ''}${r.fontSize ? `font-size:${r.fontSize}px;` : ''}${r.fontWeight ? `font-weight:${r.fontWeight};` : ''}">${esc(r.text)}</span>`).join('');
function renderNode(n) {
  const cls = ['node', n.kind, n.field.startsWith('preview.') ? 'left' : 'right'];
  let html = textRuns(n.runs);
  if (n.kind === 'image') html = `<img alt="${esc(n.assetRole)}" src="source-assets/${n.assetRole === 'icon' ? 'icon' : 'illustration'}.png">`;
  if (n.kind === 'table') html = `<table><colgroup>${n.table.columnWidths.map(w => `<col style="width:${w}%">`).join('')}</colgroup><thead><tr>${n.table.headers.map(h => `<th>${esc(h)}</th>`).join('')}</tr></thead><tbody>${n.table.rows.map(row => `<tr>${row.map(c => `<td>${esc(c)}</td>`).join('')}</tr>`).join('')}</tbody></table>`;
  return `<div data-field="${esc(n.field)}" class="${cls.join(' ')}" style="left:${n.x}px;top:${n.y}px;width:${n.width}px;height:${n.height}px">${html}</div>`;
}
function preview(projection) {
  const workflow = artwork?.workflow || [["先分清材料", "记录来源、可定位片段、读取范围与核验状态。"], ["再组织论证", "让每个实质性论点对应证据，冲突与缺口保持可见。"], ["最后核查交付", "引用不冒充已核实，未运行的分析不冒充研究结果。"]];
  return document(`<main><header><div><span class="eyebrow">专家包审阅 · ${artwork?.reviewNumber || "01"}</span><h1>${esc(spec.title.zh)}</h1><p>参考七个现有专家包制作 · YCPS 1.1 · ${code}@1.0.0</p></div><a class="download" href="${code}-1.0.0.ycps.zip" download>下载可导入包</a></header><div class="note">下方为包内可编辑展示数据的独立预览。示例材料为虚构测试材料；联网核验、文件生成与发布取决于宿主实际能力。</div>${['zh','en'].map(lang => `<section><h2>${lang === 'zh' ? '中文展示' : 'English presentation'}</h2><div class="canvas" data-lang="${lang}"><div class="separator"></div>${projection.canvas[lang].nodes.map(renderNode).join('')}</div></section>`).join('')}<section class="workflow"><h2>执行方式与质量边界</h2><div class="grid">${workflow.map(([title, body]) => `<div><b>${esc(title)}</b><p>${esc(body)}</p></div>`).join('')}</div></section><details><summary>阅读完整执行指令</summary><pre>${esc(spec.instructions)}</pre></details></main>`, `body{background:#edf2f3}main{width:1180px;margin:36px auto 64px}header{display:flex;align-items:center;justify-content:space-between}.eyebrow{font-size:12px;font-weight:700;letter-spacing:2px;color:#427061}h1{font-size:32px;margin:10px 0}header p{font-size:13px;color:#64748b}a.download{color:white;background:#245c69;padding:13px 20px;border-radius:10px;text-decoration:none;font-size:14px}.note{padding:16px 20px;border:1px solid #cedfda;background:#f6fbf9;font-size:13px;margin:24px 0;border-radius:10px;color:#49635c}section{margin-top:26px}h2{font-size:18px;margin:0 0 14px}.canvas{position:relative;width:1080px;height:704px;margin:0 auto;background:#fff;border-radius:16px;box-shadow:0 8px 36px #203c4410;overflow:hidden}.separator{position:absolute;left:605px;height:704px;border-left:1px solid #ecf0f3}.node{position:absolute;font-size:14px;line-height:1.45;overflow-wrap:anywhere}.node img{display:block;width:100%;height:100%;object-fit:fill}.card{background:#f4f4f5;border-radius:10px;padding:8px 10px}.tag{background:#f1f3f5;color:#475569;padding:2px 6px;border-radius:4px;font-size:12px;line-height:18px}.table table{width:100%;border-collapse:collapse;table-layout:fixed;font-size:12px;line-height:1.4}.table td,.table th{padding:8px;border:1px solid #d8e2f3;overflow-wrap:anywhere;text-align:left}.table th{background:#eaf0ff;color:#3152a0}.table tr{min-height:39px}.workflow,.grid,details{background:#fff;border-radius:12px;padding:24px}.grid{padding:0;display:grid;grid-template-columns:repeat(3,1fr);gap:30px}.grid b{font-size:16px}.grid p{font-size:14px;line-height:1.7;color:#64748b}details{margin-top:24px}summary{cursor:pointer}pre{white-space:pre-wrap;line-height:1.8;font-family:${font};font-size:14px}`);
}

async function main() {
  fs.mkdirSync(path.join(dest, 'source-assets'), {recursive:true});
  const executablePath = [process.env.EXPERT_RENDER_BROWSER, chromium.executablePath(),
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(p => p && fs.existsSync(p));
  if (!executablePath) throw new Error('Install a Chromium browser or set EXPERT_RENDER_BROWSER');
  const browser = await chromium.launch({executablePath,headless:true});
  try {
    if (args.includes('--assets')) {
      for (const [name, width, height, scale, html] of [['icon',240,240,1,icon()],['illustration',1440,480,1,illustration()],['example-image',536,140,2,example()]]) {
        const page = await browser.newPage({viewport:{width,height},deviceScaleFactor:scale});
        await page.setContent(html);
        await page.evaluate(() => document.fonts.ready);
        if (name === 'illustration') {
          const hasText = await page.evaluate(() => Boolean(document.body.textContent.trim()) || Boolean(document.querySelector('svg text, svg foreignObject')));
          if (hasText) throw new Error('Expert banners must be language-neutral and contain no text');
        }
        if (name === 'example-image') {
          const metrics = await page.evaluate(() => {
            const card=document.querySelector('.example').getBoundingClientRect();
            return [...document.querySelectorAll('.request,.material')].map(el=>({text:el.textContent,top:el.getBoundingClientRect().top,bottom:el.getBoundingClientRect().bottom,limit:card.bottom}));
          });
          if (metrics.some(m=>m.bottom>m.limit-6||m.top<6)) throw new Error(`Example text overflow: ${JSON.stringify(metrics)}`);
        }
        fs.writeFileSync(path.join(dest,'source-assets',`${name}.html`), html);
        await page.screenshot({path:path.join(dest,'source-assets',`${name}.png`),animations:'disabled'});
        await page.close();
      }
      console.log(`Rendered 3 assets for ${code}`);
    } else if (args.includes('--preview')) {
      const projection=JSON.parse(fs.readFileSync(path.join(dest,'expected-admin-presentation.json'),'utf8'));
      const html=preview(projection);
      fs.writeFileSync(path.join(dest,'preview.html'),html);
      const page=await browser.newPage({viewport:{width:1280,height:900},deviceScaleFactor:1});
      await page.goto(require('node:url').pathToFileURL(path.join(dest,'preview.html')).href);
      await page.evaluate(()=>document.fonts.ready);
      const overflow=await page.locator('.node').evaluateAll(nodes=>nodes.flatMap(n=>{
        const over=n.scrollHeight>n.clientHeight+1||n.scrollWidth>n.clientWidth+1;
        return over?[{lang:n.parentElement.dataset.lang,field:n.dataset.field,actual:[n.scrollWidth,n.scrollHeight],box:[n.clientWidth,n.clientHeight]}]:[];
      }));
      fs.writeFileSync(path.join(dest,'visual-check.json'),JSON.stringify({overflow,previewType:'Standalone rendering of package presentation; not a live application screenshot.'},null,2)+'\n');
      for (const lang of ['zh','en']) await page.locator(`[data-lang="${lang}"]`).screenshot({path:path.join(dest,`preview-${lang}.png`)});
      if(overflow.length) throw new Error(`Preview overflow: ${JSON.stringify(overflow)}`);
      console.log(`Rendered bilingual previews; no text overflow for ${code}`);
      await page.close();
    } else throw new Error('Supply --assets or --preview');
  } finally {await browser.close();}
}
main().catch(error=>{console.error(error);process.exitCode=1});
