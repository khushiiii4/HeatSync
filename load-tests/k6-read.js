// k6 read-API load test (plan 12.3 P-01): 50 virtual users each log in ONCE, then call the two busiest read APIs for
// 2 minutes through the public entry point (nginx -> gateway). Targets: error rate below 1 percent, p95 below 500 ms.
//
//   k6 run -e BASE_URL=http://localhost -e VIEWER_PASSWORD=... load-tests/k6-read.js
//   (no local k6? run it in Docker, see load-tests/README.md)
// The password is passed on the command line or from the environment and is never stored in this file.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost';
const USER = __ENV.VIEWER_USER || 'viewer';
const PASS = __ENV.VIEWER_PASSWORD;
const VUS = Number(__ENV.VUS || 50);
const DURATION = __ENV.DURATION || '2m';

const loginFailures = new Counter('login_failures');

export const options = {
  vus: VUS,
  duration: DURATION,
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
    login_failures: ['count==0'],
  },
};

let token = null; // module state is per virtual user in k6, so each VU logs in once

export default function () {
  if (!token) {
    const res = http.post(`${BASE}/api/auth/login`,
      JSON.stringify({ username: USER, password: PASS }), { headers: { 'Content-Type': 'application/json' },
        tags: { name: 'login' } });
    const ok = check(res, { 'login 200': (r) => r.status === 200 });
    if (!ok) { loginFailures.add(1); console.error(`login failed: HTTP ${res.status} ${String(res.body).slice(0, 120)}`); sleep(1); return; }
    token = res.json('token');
  }
  const headers = { Authorization: `Bearer ${token}` };
  const racks = http.get(`${BASE}/api/telemetry/racks/latest`, { headers, tags: { name: 'racks_latest' } });
  check(racks, { 'racks 200': (r) => r.status === 200, 'racks has data': (r) => r.body && r.body.length > 2 });
  const alerts = http.get(`${BASE}/api/alerts?state=ACTIVE&limit=50`, { headers, tags: { name: 'alerts' } });
  check(alerts, { 'alerts 200': (r) => r.status === 200 });
  sleep(1);
}
