// Signs the headless browser in through the real login form (no demo session exists any more).
// Credentials: DEMO_USER (default "admin") and DEMO_PASSWORD, or the matching SEED_<ROLE>_PASSWORD variable from .env
// (load it first, for example: set -a; . ../.env; set +a). Nothing is hard-coded.
export async function signIn(page, base, user = process.env.DEMO_USER || 'admin') {
  const password = process.env.DEMO_PASSWORD || process.env[`SEED_${user.toUpperCase()}_PASSWORD`];
  if (!password) throw new Error(`No password for "${user}": set DEMO_PASSWORD or SEED_${user.toUpperCase()}_PASSWORD`);
  await page.goto(`${base}/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#username');
  await page.type('#username', user);
  await page.type('#password', password);
  await Promise.all([page.waitForFunction(() => !location.pathname.startsWith('/login'), { timeout: 15000 }), page.click('button[type=submit]')]);
}
