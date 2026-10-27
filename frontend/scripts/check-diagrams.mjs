// Parse + render every ```mermaid block in docs/ with the real Mermaid library (from the CDN) and save a
// PNG of each to diagram-report/, so a broken or unreadable diagram is caught before it is committed.
//   npm run docs:diagrams        (uses Edge; set PW_CHANNEL to change)
import { chromium } from '@playwright/test';
import { readFileSync, readdirSync, mkdirSync } from 'node:fs';

const out = process.argv[2] ?? "diagram-report";
const docs = '../docs/';
const files = [...readdirSync(docs + 'engineering').map(f => docs + 'engineering/' + f), docs + 'architecture/messaging.md'].filter(f => f.endsWith('.md'));
const blocks = [];
for (const f of files) {
  for (const m of readFileSync(f, 'utf8').matchAll(/```mermaid\r?\n([\s\S]*?)```/g)) blocks.push({ f: f.split('/').pop(), code: m[1] });
}
console.log(blocks.length, 'diagrams');

const b = await chromium.launch({ channel: process.env.PW_CHANNEL ?? 'msedge' });
const p = await b.newPage({ viewport: { width: 1100, height: 800 } });
await p.setContent('<html><body style="background:#fff;margin:0"><div id="out"></div><script type="module">import m from "https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs"; m.initialize({ startOnLoad: false, theme: "default" }); window.mm = m; window.ready = true;</script></body></html>');
await p.waitForFunction(() => window.ready, null, { timeout: 30000 });
mkdirSync(out, { recursive: true });
let bad = 0;
for (const [i, blk] of blocks.entries()) {
  const r = await p.evaluate(async ({ code, i }) => {
    try {
      await window.mm.parse(code);
      const { svg } = await window.mm.render('d' + i, code);
      document.getElementById('out').innerHTML = '<div id="w" style="width:1000px;padding:8px">' + svg + '</div>';
      return 'ok';
    } catch (e) { return 'ERR ' + (e.message || String(e)).slice(0, 400); }
  }, { code: blk.code, i });
  if (r !== 'ok') { bad++; console.log('#' + i, blk.f, r); }
  else await p.locator('#w').screenshot({ path: `${out}/${String(i).padStart(2, '0')}-${blk.f.replace('.md', '')}.png` });
}
console.log(bad ? `${bad} broken` : 'all diagrams parse and render');
if (bad) process.exitCode = 1;
await b.close();
