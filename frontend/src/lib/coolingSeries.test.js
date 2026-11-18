import { describe, expect, it } from 'vitest';
import { BASELINE_OUTPUT_PCT, baselineKw, buildOutputSeries, coolingKw } from './coolingSeries';

describe('cooling energy model (plan 6E)', () => {
  it('matches the documented figures', () => {
    expect(coolingKw(100)).toBeCloseTo(4.5, 2);
    expect(coolingKw(70)).toBeCloseTo(1.87, 2);
    expect(BASELINE_OUTPUT_PCT).toBe(70);
    expect(baselineKw(4)).toBeCloseTo(7.49, 1);
  });
});

describe('buildOutputSeries', () => {
  const actions = [
    { id: 3, zoneId: 'Z1', oldOutputPct: 60, newOutputPct: 40, createdAt: '2026-11-18T12:00:20Z' },
    { id: 1, zoneId: 'Z1', oldOutputPct: 30, newOutputPct: 60, createdAt: '2026-11-18T12:00:00Z' },
    { id: 2, zoneId: 'Z2', oldOutputPct: 30, newOutputPct: 50, createdAt: '2026-11-18T12:00:10Z' },
  ];

  it('is empty without actions', () => {
    expect(buildOutputSeries([], ['Z1'])).toEqual({ rows: [], zones: ['Z1'] });
  });

  it('orders by time and carries each zone forward', () => {
    const { rows } = buildOutputSeries(actions, ['Z1', 'Z2']);
    expect(rows.map((r) => [r.Z1, r.Z2])).toEqual([
      [60, 30],
      [60, 50],
      [40, 50],
    ]);
    expect(rows.map((r) => r.t)).toEqual([...rows.map((r) => r.t)].sort((a, b) => a - b));
  });

  it('computes modelled power from the outputs and always carries the baseline', () => {
    const { rows } = buildOutputSeries(actions, ['Z1', 'Z2']);
    expect(rows[2].totalKw).toBeCloseTo(coolingKw(40) + coolingKw(50), 6);
    expect(rows[0].baselineKw).toBeCloseTo(baselineKw(2), 6);
  });

  it('leaves total power unknown until every zone has a value', () => {
    const { rows } = buildOutputSeries(actions, ['Z1', 'Z2', 'Z3']);
    expect(rows.every((r) => r.totalKw === null)).toBe(true);
  });
});
