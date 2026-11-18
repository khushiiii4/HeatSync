// Cooling page series, built only from the controller's own action log (GET /api/cooling/actions).
// Each action records one zone's old and new output at a server timestamp, so the log is a step function per zone.

/** Energy model from plan 6E: coolingKw(zone) = 0.5 + 4.0 x (output / 100)^3. */
export const coolingKw = (outputPct) => 0.5 + 4.0 * Math.pow(outputPct / 100, 3);

/** Static baseline policy: 70 percent output in every zone (1.87 kW each). */
export const BASELINE_OUTPUT_PCT = 70;
export const baselineKw = (zoneCount) => coolingKw(BASELINE_OUTPUT_PCT) * zoneCount;

/**
 * Turn the action log into one row per change: { t, Z1, Z2, ..., totalKw, baselineKw }.
 * Values carry forward between a zone's changes; before a zone's first logged change its value is the
 * oldOutputPct of that change (what it was running at). Zones that never changed are absent until they do.
 */
export function buildOutputSeries(actions = [], zoneIds = []) {
  const asc = [...actions].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt) || a.id - b.id);
  if (!asc.length) return { rows: [], zones: zoneIds };
  const zones = zoneIds.length ? zoneIds : [...new Set(asc.map((a) => a.zoneId))].sort();
  const current = {};
  for (const a of asc) if (!(a.zoneId in current)) current[a.zoneId] = a.oldOutputPct;

  const rows = [];
  for (const a of asc) {
    current[a.zoneId] = a.newOutputPct;
    const row = { t: Date.parse(a.createdAt) };
    let total = 0;
    let known = 0;
    for (const z of zones) {
      if (z in current) {
        row[z] = current[z];
        total += coolingKw(current[z]);
        known += 1;
      }
    }
    row.totalKw = known === zones.length ? total : null;
    row.baselineKw = baselineKw(zones.length);
    rows.push(row);
  }
  return { rows, zones };
}
