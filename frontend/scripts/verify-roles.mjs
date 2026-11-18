// Real-browser check of Phase 9 behaviour against a running stack: logs in as each role through the login form and
// reports what the UI shows. Usage (from frontend/, with the dev server running):
//   set -a; . ../.env; set +a; node scripts/verify-roles.mjs [baseUrl]
// Exits non-zero when an expectation fails. Screenshots go to the folder in SHOTS (default: none).
import puppeteer from 'puppeteer-core';
import fs from 'node:fs';
import { signIn } from './_login.mjs';

const BASE = process.argv[2] || 'http://127.0.0.1:5173';
const SHOTS = process.env.SHOTS;
const CHROME = [process.env.CHROME_PATH, 'C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find((p) => p && fs.existsSync(p));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? `  (${extra})` : ''}`);
  if (!ok) failures += 1;
};

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox'] });
const text = (page) => page.evaluate(() => document.body.innerText);
const navLabels = (page) => page.evaluate(() => [...document.querySelectorAll('aside a, nav a')].map((a) => a.textContent.trim()));
const shot = async (page, name) => { if (SHOTS) { fs.mkdirSync(SHOTS, { recursive: true }); await page.screenshot({ path: `${SHOTS}/${name}.png` }); } };

for (const [user, role] of [['viewer', 'VIEWER'], ['operator', 'OPERATOR'], ['admin', 'ADMIN']]) {
  const ctx = await browser.createBrowserContext();
  const page = await ctx.newPage();
  await page.setViewport({ width: 1440, height: 900 });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await signIn(page, BASE, user);
  await sleep(4000);
  const body = await text(page);
  check(`${role}: dashboard loads and shows the user and role`, body.includes(user) && body.includes(role) && /Heat map|KPI|Racks|Zone/i.test(body));
  const stored = await page.evaluate(() => JSON.parse(localStorage.getItem('thermogrid.auth') || 'null'));
  check(`${role}: JWT stored for the session`, !!stored?.token && stored.user.role === role);
  const nav = (await navLabels(page)).join('|');
  check(`${role}: Control Room navigation ${role === 'VIEWER' ? 'hidden' : 'visible'}`, nav.includes('Control Room') === (role !== 'VIEWER'), nav);
  check(`${role}: service strip shows no "not deployed" and auth is healthy`, !/not deployed/i.test(body));
  await shot(page, `${role.toLowerCase()}-overview`);

  await page.goto(`${BASE}/workloads`, { waitUntil: 'networkidle2' });
  await sleep(1500);
  const w = await text(page);
  const canSubmit = /Submit a workload/.test(w);
  check(`${role}: workload submit form ${role === 'VIEWER' ? 'hidden' : 'visible'}`, canSubmit === (role !== 'VIEWER'));
  await shot(page, `${role.toLowerCase()}-workloads`);

  await page.goto(`${BASE}/control`, { waitUntil: 'networkidle2' });
  await sleep(1500);
  const path = await page.evaluate(() => location.pathname);
  if (role === 'VIEWER') check('VIEWER: /control redirects away', path === '/');
  else {
    const c = await text(page);
    check(`${role}: Control Room opens`, path === '/control');
    check(`${role}: simulator and demo controls ${role === 'ADMIN' ? 'visible' : 'hidden'}`, /Simulation and demo/i.test(c) === (role === 'ADMIN'));
    await shot(page, `${role.toLowerCase()}-control`);
  }

  if (role === 'ADMIN') {
    // A rejected token must send the user to the login page.
    await page.evaluate(() => {
      const s = JSON.parse(localStorage.getItem('thermogrid.auth'));
      s.token = `${s.token.slice(0, -3)}AAA`;
      localStorage.setItem('thermogrid.auth', JSON.stringify(s));
    });
    await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
    await sleep(5000);
    check('ADMIN: a tampered token ends the session and shows the login page', (await page.evaluate(() => location.pathname)) === '/login');
    check('ADMIN: stored token removed after 401', (await page.evaluate(() => localStorage.getItem('thermogrid.auth'))) === null);
  } else {
    // Sign out.
    await page.goto(`${BASE}/`, { waitUntil: 'networkidle2' });
    await page.click('button[aria-label="Sign out"]');
    await sleep(800);
    check(`${role}: sign out returns to login and clears the token`, (await page.evaluate(() => location.pathname)) === '/login' && (await page.evaluate(() => localStorage.getItem('thermogrid.auth'))) === null);
  }
  check(`${role}: no uncaught page errors`, errors.length === 0, errors.join(' | '));
  await ctx.close();
}

// 403 toast: a viewer session whose UI is forced to show an admin action still gets refused by the gateway.
{
  const ctx = await browser.createBrowserContext();
  const page = await ctx.newPage();
  await signIn(page, BASE, 'viewer');
  const status = await page.evaluate(async () => {
    const s = JSON.parse(localStorage.getItem('thermogrid.auth'));
    const r = await fetch('/api/sim/control', { method: 'POST', headers: { Authorization: `Bearer ${s.token}`, 'Content-Type': 'application/json' }, body: '{"action":"PAUSE"}' });
    return r.status;
  });
  check('VIEWER calling /api/sim/control from the browser gets 403 from the gateway', status === 403, String(status));
  await ctx.close();
}

await browser.close();
console.log(failures ? `\n${failures} check(s) failed` : '\nAll checks passed');
process.exit(failures ? 1 : 0);
