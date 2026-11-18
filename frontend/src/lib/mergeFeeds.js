// Merge-by-time (plan section 9.4, DecisionFeed): scheduler migrations, cooling actions and alerts become one
// newest-first stream of uniform items. Pure functions so they can be unit-tested.

const REASON_TEXT = {
  THERMAL_WARNING: 'the source rack reached a thermal WARNING',
  THERMAL_CRITICAL: 'the source rack reached a CRITICAL temperature',
  COOLING_FAILURE: 'the cooling unit in that zone failed',
  REBALANCE: 'utilization was unevenly spread',
  INITIAL_PLACEMENT: 'it was a new workload',
};

export const reasonText = (code) =>
  REASON_TEXT[code] || String(code || 'no reason recorded').toLowerCase().replaceAll('_', ' ');

const ms = (iso) => {
  const t = Date.parse(iso);
  return Number.isNaN(t) ? 0 : t;
};

/** workloads: array of {id, name, cpuDemand, priority}; returns a lookup by id. */
export const indexWorkloads = (workloads = []) => Object.fromEntries(workloads.map((w) => [w.id, w]));

export function migrationItem(m, workloadsById = {}) {
  const w = workloadsById[m.workloadId];
  const what = w ? `${w.name} (${w.priority}, ${w.cpuDemand} units)` : `workload #${m.workloadId}`;
  const from = m.fromRackId ? ` from ${m.fromRackId}` : '';
  const to = m.toRackId ? ` to ${m.toRackId}` : '';
  const verb = m.fromRackId ? 'Moved' : 'Placed';
  return {
    id: `mig-${m.id}`,
    kind: 'migration',
    at: m.appliedAt || m.createdAt,
    title: `${verb} ${what}${from}${to}`,
    reason: `Because ${reasonText(m.reason)}.`,
    outcome: m.status,
    detail: m.failureReason || null,
    racks: [m.fromRackId, m.toRackId].filter(Boolean),
    workloadId: m.workloadId,
    severity: m.status === 'FAILED' ? 'crit' : 'info',
  };
}

export function coolingItem(a) {
  const delta = a.newOutputPct - a.oldOutputPct;
  const dir = delta > 0 ? 'raised' : delta < 0 ? 'lowered' : 'held';
  return {
    id: `cool-${a.id}`,
    kind: 'cooling',
    at: a.createdAt,
    title: `${a.zoneId} cooling ${dir} ${a.oldOutputPct} → ${a.newOutputPct} %`,
    reason: a.reason ? `${a.reason}.` : '',
    outcome: a.mode,
    detail: null,
    zoneId: a.zoneId,
    delta,
    severity: a.newOutputPct >= 90 ? 'warn' : 'info',
  };
}

export function alertItem(a) {
  const cleared = a.state === 'CLEARED';
  const when = cleared ? a.clearedAt || a.updatedAt : a.raisedAt;
  const basis =
    a.basis === 'PREDICTED' ? `forecast ${Number(a.forecastC).toFixed(1)} °C` : `${Number(a.tempC).toFixed(1)} °C observed`;
  return {
    id: `alert-${a.id}-${a.state}`,
    kind: 'alert',
    at: when,
    title: cleared ? `${a.rackId} alert cleared` : `${a.rackId} ${a.level} alert`,
    reason: `${a.basis === 'PREDICTED' ? 'Predicted' : 'Observed'}: ${basis}.`,
    outcome: a.state,
    detail: null,
    racks: [a.rackId],
    level: a.level,
    severity: cleared ? 'ok' : a.level === 'CRITICAL' ? 'crit' : a.level === 'WARNING' ? 'orange' : 'warn',
  };
}

/**
 * Merge feeds newest first. Options:
 *  migrations, actions, alerts: raw API arrays (any may be missing);
 *  workloads: running workloads, used to name the moved job;
 *  minCoolingStep: hide cooling changes smaller than this many points (the controller adjusts every few seconds);
 *  limit: maximum items returned.
 * Returns { items, hiddenCooling }.
 */
export function mergeFeeds({
  migrations = [],
  actions = [],
  alerts = [],
  workloads = [],
  minCoolingStep = 0,
  limit = 50,
} = {}) {
  const byId = indexWorkloads(workloads);
  let hiddenCooling = 0;
  const items = [];
  for (const m of migrations) items.push(migrationItem(m, byId));
  for (const a of actions) {
    if (Math.abs(a.newOutputPct - a.oldOutputPct) < minCoolingStep && a.mode !== 'FAILSAFE') {
      hiddenCooling += 1;
      continue;
    }
    items.push(coolingItem(a));
  }
  for (const a of alerts) items.push(alertItem(a));
  items.sort((x, y) => ms(y.at) - ms(x.at) || String(y.id).localeCompare(String(x.id)));
  return { items: items.slice(0, limit), hiddenCooling };
}
