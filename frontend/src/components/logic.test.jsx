import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { classify, overallStatus } from './ServiceHealthStrip';
import { summariseRacks } from './KpiStrip';
import { buildRackStacks } from './WorkloadDistribution';
import { validateSubmission } from '../pages/Workloads';
import { hasRole } from '../lib/roles';
import { RackCell } from './RackHeatMap';
import { latestInstant } from '../lib/format';

const services = [
  { name: 'API-GATEWAY', instances: 1, status: 'UP' },
  { name: 'AUTH-SERVICE', instances: 0, status: 'DOWN' },
  { name: 'TOPOLOGY-SERVICE', instances: 1, status: 'UP' },
  { name: 'SIMULATOR-SERVICE', instances: 1, status: 'UP' },
  { name: 'TELEMETRY-SERVICE', instances: 3, status: 'UP' },
  { name: 'WORKLOAD-SERVICE', instances: 1, status: 'UP' },
  { name: 'SCHEDULER-SERVICE', instances: 0, status: 'DOWN' },
  { name: 'COOLING-CONTROLLER-SERVICE', instances: 1, status: 'UP' },
];
const byName = (items) => Object.fromEntries(items.map((i) => [i.name, i.state]));

describe('service health classification', () => {
  it('distinguishes healthy and unavailable', () => {
    const s = byName(classify(services, {}, false));
    expect(s['TELEMETRY-SERVICE']).toBe('healthy');
    expect(s['SCHEDULER-SERVICE']).toBe('unavailable');
    expect(s['AUTH-SERVICE']).toBe('unavailable'); // not in the registry list above
  });

  it('marks a registered service as degraded when its data source is failing', () => {
    const s = byName(classify(services, { 'racks': { failing: true, service: 'TELEMETRY-SERVICE' } }, false));
    expect(s['TELEMETRY-SERVICE']).toBe('degraded');
  });

  it('marks everything stale when the registry itself could not be refreshed', () => {
    expect(new Set(classify(services, {}, true).map((i) => i.state))).toEqual(new Set(['stale']));
  });

  it('summarises the whole platform', () => {
    expect(overallStatus(classify(services, {}, false), false)).toEqual({ tone: 'crit', text: '2 services unavailable' });
    expect(overallStatus([], true).text).toBe('Gateway unreachable');
    const ok = services.map((s) => ({ ...s, status: 'UP' }));
    expect(overallStatus(classify(ok, {}, false), false).text).toBe('All systems operational');
  });
});

describe('roles', () => {
  it('ranks VIEWER < OPERATOR < ADMIN', () => {
    expect(hasRole('VIEWER', 'OPERATOR')).toBe(false);
    expect(hasRole('OPERATOR', 'OPERATOR')).toBe(true);
    expect(hasRole('OPERATOR', 'ADMIN')).toBe(false);
    expect(hasRole('ADMIN', 'OPERATOR')).toBe(true);
    expect(hasRole(null, 'VIEWER')).toBe(false);
  });
});

describe('workload submission validation (plan A-02)', () => {
  it('rejects cpuDemand 0 and 101 and an empty name', () => {
    expect(validateSubmission({ name: 'x', cpuDemand: '0' }).cpuDemand).toBeTruthy();
    expect(validateSubmission({ name: 'x', cpuDemand: '101' }).cpuDemand).toBeTruthy();
    expect(validateSubmission({ name: ' ', cpuDemand: '20' }).name).toBeTruthy();
    expect(validateSubmission({ name: 'x', cpuDemand: '2.5' }).cpuDemand).toBeTruthy();
  });
  it('accepts 1 to 100', () => {
    expect(validateSubmission({ name: 'batch', cpuDemand: '1' })).toEqual({});
    expect(validateSubmission({ name: 'batch', cpuDemand: '100' })).toEqual({});
  });
});

describe('KPI and chart derivations', () => {
  it('averages only live racks and finds the hottest', () => {
    const r = summariseRacks([
      { rackId: 'R11', tempC: 25, sensorStatus: 'OK' },
      { rackId: 'R12', tempC: 31, sensorStatus: 'OK' },
      { rackId: 'R13', tempC: 90, sensorStatus: 'STALE' },
    ]);
    expect(r.avg).toBe(28);
    expect(r.hottest.rackId).toBe('R12');
    expect(r.count).toBe(2);
  });
  it('handles no readings', () => {
    expect(summariseRacks([])).toEqual({ avg: null, hottest: null, count: 0 });
  });
  it('stacks CPU per rack by priority and keeps empty racks', () => {
    const rows = buildRackStacks([{ rackId: 'R11', priority: 'LOW', cpuDemand: 20 }, { rackId: 'R11', priority: 'HIGH', cpuDemand: 15 }], ['R11', 'R12']);
    expect(rows).toEqual([{ rack: 'R11', LOW: 20, MEDIUM: 0, HIGH: 15 }, { rack: 'R12', LOW: 0, MEDIUM: 0, HIGH: 0 }]);
  });
  it('takes the latest server instant', () => {
    expect(latestInstant(['2026-11-18T12:00:00Z', null, '2026-11-18T12:00:05Z'])).toBe('2026-11-18T12:00:05Z');
    expect(latestInstant([])).toBeNull();
  });
});

describe('RackCell', () => {
  const wrap = (ui) => render(<MemoryRouter>{ui}</MemoryRouter>);
  const rack = { id: 'R23', status: 'ACTIVE' };

  it('prints id, temperature to one decimal and CPU, and describes itself for screen readers', () => {
    wrap(<RackCell rack={rack} reading={{ tempC: 29.84, forecast60C: 35.2, riskLevel: 'WARNING', trendCPerMin: 3.6, cpuLoadPct: 72.5, sensorStatus: 'OK' }} />);
    expect(screen.getByText('R23')).toBeInTheDocument();
    expect(screen.getByText('29.8')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Rack R23, 29.8 degrees, WARNING risk, rising/ })).toBeInTheDocument();
  });

  it('flags a stale sensor and maintenance without relying on colour', () => {
    wrap(<RackCell rack={{ ...rack, status: 'MAINTENANCE' }} reading={{ tempC: 27, riskLevel: 'NORMAL', sensorStatus: 'STALE' }} />);
    expect(screen.getByText('Stale')).toBeInTheDocument();
    expect(screen.getByRole('link').getAttribute('aria-label')).toMatch(/sensor stale, in maintenance/);
  });

  it('copes with a rack that has not reported', () => {
    wrap(<RackCell rack={rack} reading={undefined} />);
    expect(screen.getByRole('link', { name: /no reading/ })).toBeInTheDocument();
  });
});
