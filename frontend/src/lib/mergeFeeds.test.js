import { describe, expect, it } from 'vitest';
import { alertItem, coolingItem, mergeFeeds, migrationItem, reasonText } from './mergeFeeds';

const migrations = [
  { id: 'm1', workloadId: 19, fromRackId: 'R23', toRackId: 'R34', reason: 'THERMAL_WARNING', status: 'APPLIED', failureReason: null, createdAt: '2026-11-18T12:40:06.120Z', appliedAt: '2026-11-18T12:40:06.310Z' },
  { id: 'm2', workloadId: 99, fromRackId: 'R21', toRackId: null, reason: 'COOLING_FAILURE', status: 'FAILED', failureReason: 'workload unavailable', createdAt: '2026-11-18T12:41:00.000Z', appliedAt: null },
];
const actions = [
  { id: 1, zoneId: 'Z2', oldOutputPct: 60, newOutputPct: 78, mode: 'AUTO', reason: 'hotspot forecast above target', createdAt: '2026-11-18T12:40:04.000Z' },
  { id: 2, zoneId: 'Z4', oldOutputPct: 34, newOutputPct: 36, mode: 'AUTO', reason: 'zone max 26.0 C', createdAt: '2026-11-18T12:40:08.000Z' },
];
const alerts = [
  { id: 7, rackId: 'R23', zoneId: 'Z2', level: 'WARNING', basis: 'PREDICTED', tempC: 30.4, forecastC: 35.2, state: 'ACTIVE', raisedAt: '2026-11-18T12:40:02.000Z', updatedAt: '2026-11-18T12:40:02.000Z', clearedAt: null },
];
const workloads = [{ id: 19, name: 'batch-etl-7', cpuDemand: 30, priority: 'LOW' }];

describe('mergeFeeds', () => {
  it('returns one newest-first stream of all three sources', () => {
    const { items } = mergeFeeds({ migrations, actions, alerts, workloads });
    expect(items.map((i) => i.id)).toEqual(['mig-m2', 'cool-2', 'mig-m1', 'cool-1', 'alert-7-ACTIVE']);
    const times = items.map((i) => Date.parse(i.at));
    expect([...times].sort((a, b) => b - a)).toEqual(times);
  });

  it('writes a decision line that names the job, the racks and the reason', () => {
    const item = migrationItem(migrations[0], { 19: workloads[0] });
    expect(item.title).toBe('Moved batch-etl-7 (LOW, 30 units) from R23 to R34');
    expect(item.reason).toBe('Because the source rack reached a thermal WARNING.');
    expect(item.outcome).toBe('APPLIED');
  });

  it('falls back to the workload id when the job is not known', () => {
    expect(migrationItem(migrations[1], {}).title).toContain('workload #99');
    expect(migrationItem(migrations[1], {}).severity).toBe('crit');
    expect(migrationItem(migrations[1], {}).detail).toBe('workload unavailable');
  });

  it('hides small cooling steps and reports how many it hid', () => {
    const { items, hiddenCooling } = mergeFeeds({ migrations: [], actions, alerts: [], minCoolingStep: 5 });
    expect(items.map((i) => i.id)).toEqual(['cool-1']);
    expect(hiddenCooling).toBe(1);
  });

  it('never hides a fail-safe change', () => {
    const failsafe = { id: 3, zoneId: 'Z1', oldOutputPct: 78, newOutputPct: 80, mode: 'FAILSAFE', reason: 'no reading for 10 s', createdAt: '2026-11-18T12:42:00.000Z' };
    expect(mergeFeeds({ actions: [failsafe], minCoolingStep: 5 }).items).toHaveLength(1);
  });

  it('honours the limit', () => {
    expect(mergeFeeds({ migrations, actions, alerts, limit: 2 }).items).toHaveLength(2);
  });

  it('copes with empty and missing inputs', () => {
    expect(mergeFeeds()).toEqual({ items: [], hiddenCooling: 0 });
    expect(mergeFeeds({ migrations: [] }).items).toEqual([]);
  });

  it('describes cooling changes by direction', () => {
    expect(coolingItem(actions[0]).title).toBe('Z2 cooling raised 60 → 78 %');
    expect(coolingItem({ ...actions[0], oldOutputPct: 80, newOutputPct: 55 }).title).toContain('lowered');
  });

  it('describes predicted and cleared alerts', () => {
    expect(alertItem(alerts[0]).reason).toBe('Predicted: forecast 35.2 °C.');
    const cleared = alertItem({ ...alerts[0], state: 'CLEARED', clearedAt: '2026-11-18T12:45:00.000Z' });
    expect(cleared.title).toBe('R23 alert cleared');
    expect(cleared.at).toBe('2026-11-18T12:45:00.000Z');
    expect(cleared.severity).toBe('ok');
  });

  it('turns unknown reason codes into plain words', () => {
    expect(reasonText('SOME_NEW_REASON')).toBe('some new reason');
  });
});
