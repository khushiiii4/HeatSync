// Visual check and screenshots with the installed Chrome (puppeteer-core, no browser download).
// Usage: node scripts/screenshots.mjs [baseUrl] [outDir]   (the app and the gateway must be running)
// Also reports console errors, horizontal overflow and elements that overflow their parent, per page and size.
import puppeteer from 'puppeteer-core';
import { signIn } from './_login.mjs';
import fs from 'node:fs';
import path from 'node:path';

const BASE = process.argv[2] || 'http://127.0.0.1:5173';
const OUT = process.argv[3] || path.resolve('..', 'docs', 'screenshots');
const CHROME = [
  process.env.CHROME_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].find((p) => p && fs.existsSync(p));
if (!CHROME) throw new Error('No Chrome or Edge found; set CHROME_PATH');

const SIZES = [
  { name: 'laptop-1440x900', width: 1440, height: 900 },
  { name: 'desktop-1920x1080', width: 1920, height: 1080 },
  { name: 'small-laptop-1280x720', width: 1280, height: 720 },
];
const PAGES = [
  { name: 'overview', url: '/' },
  { name: 'rack-detail', url: '/racks/R23' },
  { name: 'workloads', url: '/workloads' },
  { name: 'cooling', url: '/cooling' },
  { name: 'events', url: '/events' },
  { name: 'control-room', url: '/control' },
];
const ONLY = process.env.ONLY_SIZE; // e.g. ONLY_SIZE=laptop to limit

fs.mkdirSync(OUT, { recursive: true });
const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox'] });
const report = [];

for (const size of SIZES.filter((s) => !ONLY || s.name.includes(ONLY))) {
  const page = await browser.newPage();
  await page.setViewport({ width: size.width, height: size.height, deviceScaleFactor: 1 });
  const errors = [];
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));

  // Login page first (signed out).
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle2' });
  await page.screenshot({ path: path.join(OUT, `${size.name}-login.png`) });

  await signIn(page, BASE);
  for (const p of PAGES) {
    await page.goto(`${BASE}${p.url}`, { waitUntil: 'networkidle2' });
    await new Promise((r) => setTimeout(r, 3500)); // let the polled panels fill
    const overflow = await page.evaluate(() => {
      const doc = document.documentElement;
      const bad = [];
      document.querySelectorAll('main *').forEach((el) => {
        const r = el.getBoundingClientRect();
        const style = getComputedStyle(el);
        if (r.width === 0 || style.position === 'fixed') return;
        if (el.scrollWidth > el.clientWidth + 2 && ['visible'].includes(style.overflowX) && el.children.length === 0 && el.textContent.trim().length > 0) {
          bad.push(`${el.tagName.toLowerCase()}.${String(el.className).slice(0, 40)} "${el.textContent.trim().slice(0, 30)}"`);
        }
      });
      return { pageScrollsHorizontally: doc.scrollWidth > doc.clientWidth + 1, clipped: bad.slice(0, 6) };
    });
    await page.screenshot({ path: path.join(OUT, `${size.name}-${p.name}.png`), fullPage: true });
    report.push({ size: size.name, page: p.name, ...overflow });
  }
  report.push({ size: size.name, page: '(console)', errors: [...new Set(errors)].slice(0, 8) });
  await page.close();
}
await browser.close();
console.log(JSON.stringify(report, null, 1));
