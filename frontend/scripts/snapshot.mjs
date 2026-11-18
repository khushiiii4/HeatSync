// Prints the state of every dashboard panel as JSON, for the "stop a service, confirm only its panels degrade"
// checklist (plan Phase 8 testing criteria). Usage: node scripts/snapshot.mjs [baseUrl] [path]
import puppeteer from 'puppeteer-core';
import { signIn } from './_login.mjs';
import fs from 'node:fs';
import { execSync } from 'node:child_process';

const BASE = process.argv[2] || 'http://127.0.0.1:5173';
const PATH = process.argv[3] || '/';
const CHROME = [process.env.CHROME_PATH, 'C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find((p) => p && fs.existsSync(p));

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox'] });
const page = await browser.newPage();
await page.setViewport({ width: 1600, height: 1000 });
await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
await signIn(page, BASE);
await page.goto(`${BASE}${PATH}`, { waitUntil: 'networkidle2' });
await new Promise((r) => setTimeout(r, Number(process.env.SETTLE_MS || 5000)));

const read = () =>
  page.evaluate(() => {
    const txt = (el) => el?.textContent?.replace(/\s+/g, ' ').trim();
    const panels = {};
    document.querySelectorAll('main section[aria-label]').forEach((s) => {
      const badge = s.querySelector('header [role="status"]');
      const alert = s.querySelector('[role="alert"]');
      panels[s.getAttribute('aria-label')] = { badge: txt(badge) || null, error: txt(alert)?.slice(0, 80) || null };
    });
    const cells = [...document.querySelectorAll('main a[aria-label^="Rack "]')].slice(0, 3).map((a) => a.getAttribute('aria-label'));
    const strip = [...document.querySelectorAll('[aria-label="Service health"] [role="listitem"]')].map((i) => txt(i));
    const top = txt(document.querySelector('header [role="status"][aria-live]'));
    const kpis = [...document.querySelectorAll('main .card-surface svg[aria-label="Source unavailable"]')].length;
    return { top, strip, panels, firstCells: cells, kpisFlagged: kpis };
  });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const brief = (d) => ({
  top: d.top,
  notHealthy: d.strip.filter((x) => !x.includes('Healthy')),
  panels: Object.fromEntries(Object.entries(d.panels).map(([k, v]) => [k, v.badge || v.error || 'live'])),
  kpisFlagged: d.kpisFlagged,
});

if (process.env.STOP_CMD) {
  // Full cycle in ONE browser session, so panels hold real "last good" data when the service goes away.
  const before = await read();
  execSync(process.env.STOP_CMD, { stdio: 'inherit', shell: 'bash' });
  await sleep(Number(process.env.WAIT_MS || 22000));
  const during1 = await read();
  await sleep(6000);
  const during2 = await read();
  if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT, fullPage: true });
  const updating = JSON.stringify(during1.firstCells) !== JSON.stringify(during2.firstCells);
  execSync(process.env.START_CMD, { stdio: 'inherit', shell: 'bash' });
  await sleep(Number(process.env.RECOVER_MS || 40000));
  const after = await read();
  console.log(JSON.stringify({ before: brief(before), during: brief(during2), heatMapStillUpdatingDuringOutage: updating, afterRestart: brief(after) }, null, 1));
  await browser.close();
  process.exit(0);
}

const first = await read();
await new Promise((r) => setTimeout(r, 6000));
const second = await read();
const moved = JSON.stringify(first.firstCells) !== JSON.stringify(second.firstCells);
console.log(JSON.stringify({ ...second, heatMapUpdatedBetweenSamples: moved }, null, 1));
await browser.close();
