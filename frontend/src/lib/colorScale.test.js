import { describe, expect, it } from 'vitest';
import { bandFor, compareRisk, fillFor, outlineFor, SCALE, STALE_FILL, trendDirection } from './colorScale';

describe('colour scale (plan 9.3)', () => {
  it.each([
    [20, 'cool'],
    [25.99, 'cool'],
    [26, 'normal'],
    [29.99, 'normal'],
    [30, 'watch'],
    [33.99, 'watch'],
    [34, 'warning'],
    [37.99, 'warning'],
    [38, 'critical'],
    [55, 'critical'],
  ])('%s °C falls in the %s band', (t, id) => {
    expect(bandFor(t).id).toBe(id);
  });

  it('has five contiguous bands that match the telemetry thresholds', () => {
    expect(SCALE.map((b) => b.id)).toEqual(['cool', 'normal', 'watch', 'warning', 'critical']);
    for (let i = 1; i < SCALE.length; i += 1) expect(SCALE[i].min).toBe(SCALE[i - 1].max);
    expect(SCALE[2].min).toBe(30);
    expect(SCALE[3].min).toBe(34);
    expect(SCALE[4].min).toBe(38);
  });

  it('gives a stale sensor or a missing value the grey fill', () => {
    expect(fillFor(31, { stale: true })).toBe(STALE_FILL);
    expect(fillFor(null)).toBe(STALE_FILL);
    expect(fillFor(undefined)).toBe(STALE_FILL);
    expect(fillFor('abc')).toBe(STALE_FILL);
  });

  it('accepts numeric strings (the API sends decimals as numbers, but be forgiving)', () => {
    expect(bandFor('34.5').id).toBe('warning');
  });

  it('draws an outline only when the forecast risk is above NORMAL', () => {
    expect(outlineFor('NORMAL')).toBeNull();
    expect(outlineFor('WATCH')).toMatch(/^#/);
    expect(outlineFor('WARNING')).not.toBe(outlineFor('CRITICAL'));
    expect(outlineFor('bogus')).toBeNull();
  });

  it('shows the predicted hotspot: green fill with an orange outline', () => {
    expect(bandFor(28).id).toBe('normal');
    expect(outlineFor('WARNING')).toBe('#fb923c');
  });

  it('classifies trend direction with a dead band', () => {
    expect(trendDirection(0.1)).toBe('flat');
    expect(trendDirection(-0.29)).toBe('flat');
    expect(trendDirection(0.9)).toBe('up');
    expect(trendDirection(-1.2)).toBe('down');
    expect(trendDirection(undefined)).toBe('flat');
  });

  it('sorts risk levels most severe first', () => {
    expect(['NORMAL', 'CRITICAL', 'WATCH', 'WARNING'].sort(compareRisk)).toEqual(['CRITICAL', 'WARNING', 'WATCH', 'NORMAL']);
  });
});
