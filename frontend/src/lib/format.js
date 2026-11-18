const num = (v) => (v == null || v === '' || Number.isNaN(Number(v)) ? null : Number(v));

export const fmtTemp = (v, digits = 1) => (num(v) == null ? '—' : num(v).toFixed(digits));
export const fmtPct = (v, digits = 0) => (num(v) == null ? '—' : `${num(v).toFixed(digits)}%`);
export const fmtKw = (v, digits = 2) => (num(v) == null ? '—' : `${num(v).toFixed(digits)} kW`);

/** Clock time of an ISO instant, 24 h. The instant itself always comes from the server, never the browser clock. */
export function fmtClock(iso, withSeconds = true) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleTimeString([], {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    ...(withSeconds ? { second: '2-digit' } : {}),
  });
}

export function fmtDateTime(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return `${d.toLocaleDateString([], { month: 'short', day: '2-digit' })} ${fmtClock(iso)}`;
}

/** Latest of several ISO timestamps (server time carried in the payload), or null. */
export function latestInstant(values) {
  let best = null;
  let bestMs = -Infinity;
  for (const v of values) {
    const t = v ? Date.parse(v) : NaN;
    if (!Number.isNaN(t) && t > bestMs) {
      bestMs = t;
      best = v;
    }
  }
  return best;
}

export const titleCase = (s) => (s ? s.charAt(0) + s.slice(1).toLowerCase() : '');

const SHORT = {
  'API-GATEWAY': 'Gateway',
  'AUTH-SERVICE': 'Auth',
  'TOPOLOGY-SERVICE': 'Topology',
  'SIMULATOR-SERVICE': 'Simulator',
  'TELEMETRY-SERVICE': 'Telemetry',
  'WORKLOAD-SERVICE': 'Workload',
  'SCHEDULER-SERVICE': 'Scheduler',
  'COOLING-CONTROLLER-SERVICE': 'Cooling',
};
export const shortService = (name) => SHORT[name] || name;
