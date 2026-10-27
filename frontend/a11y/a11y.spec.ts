import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// Rule-based accessibility checks (the deterministic half of the auditor project).
// Reports land in a11y-report/*.json; AI-suggested findings should be added separately
// and labelled AI_SUGGESTED, never mixed with RULE_DETECTED results.

const here = dirname(fileURLToPath(import.meta.url));
const reportDir = join(here, '..', 'a11y-report');
mkdirSync(reportDir, { recursive: true });

interface SeededIssue { id: string; selector: string; axeRule: string | null; description: string }
const answerKey = JSON.parse(readFileSync(join(here, 'seeded-issues.json'), 'utf-8')) as { page: string; issues: SeededIssue[] };

const APP_PAGES = ['/', '/assets/BTC', '/portfolio', '/trade', '/activity', '/wallet', '/status', '/lab', '/knowledge'];

for (const page of APP_PAGES) {
  test(`app page ${page} has no serious or critical violations`, async ({ page: p }, testInfo) => {
    await p.goto(page);
    await p.waitForTimeout(1500); // let polling fill the page
    const results = await new AxeBuilder({ page: p }).analyze();
    const findings = results.violations.map(v => ({
      label: 'RULE_DETECTED', rule: v.id, impact: v.impact, help: v.help, nodes: v.nodes.map(n => n.target.join(' ')),
    }));
    writeFileSync(join(reportDir, `app-${page.replace(/\W+/g, '_') || 'home'}.json`), JSON.stringify(findings, null, 2));
    // Visible in the HTML report: what was scanned, and every finding (not only the blocking ones).
    testInfo.annotations.push({ type: 'axe', description: `${findings.length} findings · ${results.passes.length} rules passed` });
    await testInfo.attach('axe-findings.json', { body: JSON.stringify(findings, null, 2), contentType: 'application/json' });
    await testInfo.attach('page.png', { body: await p.screenshot({ fullPage: true }), contentType: 'image/png' });
    const blocking = findings.filter(f => f.impact === 'serious' || f.impact === 'critical');
    expect(blocking, JSON.stringify(blocking, null, 2)).toEqual([]);
  });
}

test('seeded lab: measure what axe detects and misses', async ({ page }, testInfo) => {
  await page.goto(answerKey.page);
  const results = await new AxeBuilder({ page }).analyze();
  const firedRules = new Set(results.violations.map(v => v.id));

  const rows = answerKey.issues.map(i => ({
    ...i,
    detected: i.axeRule != null && firedRules.has(i.axeRule),
  }));
  const detectable = rows.filter(r => r.axeRule != null);
  const summary = {
    seeded: rows.length,
    axeDetectable: detectable.length,
    detected: rows.filter(r => r.detected).length,
    missedDetectable: detectable.filter(r => !r.detected).map(r => r.id),
    needsHumanOrAiReview: rows.filter(r => r.axeRule == null).map(r => `${r.id}: ${r.description}`),
    extraRulesFired: [...firedRules].filter(rule => !rows.some(r => r.axeRule === rule)),
  };
  writeFileSync(join(reportDir, 'seeded-lab.json'), JSON.stringify({ summary, rows }, null, 2));
  console.log(JSON.stringify(summary, null, 2));
  testInfo.annotations.push({ type: 'axe', description: `detected ${summary.detected}/${summary.axeDetectable} detectable seeded issues; ${summary.needsHumanOrAiReview.length} need human/AI review` });
  await testInfo.attach('seeded-lab-summary.json', { body: JSON.stringify({ summary, rows }, null, 2), contentType: 'application/json' });

  // Every issue axe is expected to catch must be caught; the rest are your AI reviewer's job.
  expect(summary.missedDetectable).toEqual([]);
});
