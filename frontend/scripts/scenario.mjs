// Drives a demonstration scenario through the real UI (Control Room buttons and confirm dialog) and records what the
// dashboard shows over time, with screenshots. Usage: node scripts/scenario.mjs degrade|fail [seconds]
//   degrade = plan scenarios 2 to 5 (Z2 cooling degraded, then cleared)
//   fail    = plan scenario 6 (cooling unit in Z3 fails, then cleared)
import puppeteer from 'puppeteer-core';
import fs from 'node:fs';
import path from 'node:path';

const BASE = process.env.BASE || 'http://127.0.0.1:5173';
const OUT = path.resolve('..', 'docs', 'screenshots');
const MODE = process.argv[2] || 'degrade';
const SECONDS = Number(process.argv[3] || 150);
const CHROME = [process.env.CHROME_PATH, 'C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find((p) => p && fs.existsSync(p));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox'] });
const page = await browser.newPage();
await page.setViewport({ width: 1440, height: 900 });
await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
await page.evaluate(() => localStorage.setItem('thermogrid.auth', JSON.stringify({ token: null, user: { username: 'demo-admin', role: 'ADMIN' }, stub: true })));

async function clickText(selector, text) {
  const ok = await page.evaluate((sel, t) => {
    const el = [...document.querySelectorAll(sel)].find((e) => e.textContent.replace(/\s+/g, ' ').includes(t));
    if (!el) return false;
    el.click();
    return true;
  }, selector, text);
  if (!ok) throw new Error(`no ${selector} containing "${text}"`);
}

const overview = () =>
  page.evaluate(() => {
    const txt = (el) => el?.textContent?.replace(/\s+/g, ' ').trim();
    const cells = Object.fromEntries([...document.querySelectorAll('main a[aria-label^="Rack "]')].map((a) => {
      const m = a.getAttribute('aria-label').match(/Rack (R\d\d), ([\d.]+) degrees, (\w+) risk/);
      return m ? [m[1], `${m[2]}${m[3] !== 'NORMAL' ? ' ' + m[3] : ''}`] : [a.getAttribute('aria-label'), ''];
    }));
    const zoneCards = [...document.querySelectorAll('[role="progressbar"]')].map((p) => `${p.getAttribute('aria-label').replace(' cooling output', '')}=${p.getAttribute('aria-valuenow')}%`);
    const alerts = [...document.querySelectorAll('section[aria-label="Active alerts"] li')].map((li) => txt(li).slice(0, 70));
    const feed = [...document.querySelectorAll('section[aria-label="Decision feed"] li')].slice(0, 3).map((li) => txt(li).slice(0, 110));
    const zoneLabels = [...document.querySelectorAll('[role="group"][aria-label$="racks"]')].map((g) => txt(g.firstElementChild));
    const cpu = (id) => txt([...document.querySelectorAll('main a[aria-label^="Rack ' + id + '"]')][0])?.match(/CPU\s*(\d+)%/)?.[1];
    return { cells, zoneCards, alerts, feed, zoneLabels, z3cpu: ['R31', 'R32', 'R33', 'R34'].map(cpu) };
  });

const shot = (name) => page.screenshot({ path: path.join(OUT, `scenario-${name}.png`), fullPage: true });

await page.goto(`${BASE}/`, { waitUntil: 'networkidle2' });
await sleep(4000);
const t0 = Date.now();
console.log('--- baseline'); console.log(JSON.stringify(await overview(), null, 1));
await shot(`${MODE}-0-baseline`);

await page.goto(`${BASE}/control`, { waitUntil: 'networkidle2' });
await sleep(1500);
const preset = MODE === 'fail' ? 'Fail the cooling unit in Z3' : 'Degrade cooling in Z2';
const section = [...(await page.$$('div.rounded-xl'))];
await page.evaluate((title) => {
  const card = [...document.querySelectorAll('div')].find((d) => d.querySelector(':scope > p')?.textContent === title);
  card.querySelector('button').click();
}, preset);
await sleep(600);
await page.screenshot({ path: path.join(OUT, `scenario-${MODE}-1-confirm-dialog.png`) });
await clickText('[role="dialog"] button', 'Inject failure');
await sleep(1200);
await shot(`${MODE}-2-control-active`);

await page.goto(`${BASE}/`, { waitUntil: 'networkidle2' });
const marks = MODE === 'fail' ? [15, 40, 80] : [30, 70, 110];
let sample = 0;
const injectedAt = Date.now();
for (const m of marks) {
  while ((Date.now() - injectedAt) / 1000 < m) await sleep(1000);
  const s = await overview();
  console.log(`--- t+${m}s after injection`);
  console.log(JSON.stringify({ zoneLabels: s.zoneLabels, zoneCards: s.zoneCards, alerts: s.alerts, feed: s.feed, z2: ['R21', 'R22', 'R23', 'R24'].map((r) => `${r}:${s.cells[r]}`), z3: ['R31', 'R32', 'R33', 'R34'].map((r) => `${r}:${s.cells[r]}`), z3cpu: s.z3cpu }, null, 1));
  await shot(`${MODE}-3-t${m}s`);
  sample += 1;
}

// Clear it from the UI.
await page.goto(`${BASE}/control`, { waitUntil: 'networkidle2' });
await sleep(1500);
await clickText('section[aria-label="Active failures"] button', 'Clear');
await sleep(1500);
await shot(`${MODE}-4-cleared`);
await page.goto(`${BASE}/`, { waitUntil: 'networkidle2' });
await sleep(Math.max(5000, (SECONDS - (Date.now() - t0) / 1000) * 0 + 25000));
const after = await overview();
console.log('--- 25 s after clearing'); console.log(JSON.stringify({ zoneCards: after.zoneCards, alerts: after.alerts, feed: after.feed }, null, 1));
await shot(`${MODE}-5-after-clear`);
await browser.close();
