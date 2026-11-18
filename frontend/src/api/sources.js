import { useQuery } from '@tanstack/react-query';
import { latestInstant } from '../lib/format';
import { usePolled } from './usePolled';
import * as E from './endpoints';

// Every dashboard data source, one polled query each, with the refresh interval from plan section 9.4.
// Queries are shared by key, so two components using the same source cost one request per interval.

export const INTERVALS = {
  services: 3000,
  racks: 2000,
  alerts: 2000,
  migrations: 3000,
  coolingActions: 3000,
  coolingUnits: 3000,
  coolingSummary: 3000,
  workloadSummary: 5000,
  workloads: 5000,
  history: 5000,
  failures: 3000,
  scheduler: 3000,
};

export const useServices = () =>
  usePolled({ key: ['services'], fn: E.fetchServices, interval: INTERVALS.services, service: 'API-GATEWAY', path: '/api/platform/services' });

/** Rack list once: coordinates and zones do not change. Not polled. */
export function useTopology() {
  const q = useQuery({ queryKey: ['topology'], queryFn: E.fetchTopology, staleTime: Infinity, retry: 2 });
  return { data: q.data, isLoading: q.isLoading, isError: q.isError };
}

export const useRacks = () =>
  usePolled({
    key: ['racks'],
    fn: E.fetchRacksLatest,
    interval: INTERVALS.racks,
    service: 'TELEMETRY-SERVICE',
    path: '/api/telemetry/racks/latest',
    stamp: (d) => latestInstant(d.map((r) => r.updatedAt)),
    freezeMs: 15000,
  });

export const useZonesLatest = () =>
  usePolled({
    key: ['zones'],
    fn: E.fetchZonesLatest,
    interval: INTERVALS.racks,
    service: 'TELEMETRY-SERVICE',
    path: '/api/telemetry/zones/latest',
    stamp: (d) => latestInstant(d.map((z) => z.recordedAt)),
  });

export const useAlerts = (state = 'ACTIVE', limit = 50) =>
  usePolled({
    key: ['alerts', state, limit],
    fn: () => E.fetchAlerts(state, limit),
    interval: INTERVALS.alerts,
    service: 'TELEMETRY-SERVICE',
    path: '/api/alerts',
    stamp: (d) => latestInstant(d.map((a) => a.updatedAt)),
  });

export const useMigrations = (limit = 20) =>
  usePolled({
    key: ['migrations', limit],
    fn: () => E.fetchMigrations(limit),
    interval: INTERVALS.migrations,
    service: 'SCHEDULER-SERVICE',
    path: '/api/scheduler/migrations',
    stamp: (d) => latestInstant(d.map((m) => m.appliedAt || m.createdAt)),
  });

export const useSchedulerStatus = () =>
  usePolled({
    key: ['scheduler-status'],
    fn: E.fetchSchedulerStatus,
    interval: INTERVALS.scheduler,
    service: 'SCHEDULER-SERVICE',
    path: '/api/scheduler/status',
    stamp: (d) => d.lastCycleAt,
  });

export const useCoolingActions = (limit = 20) =>
  usePolled({
    key: ['cooling-actions', limit],
    fn: () => E.fetchCoolingActions(limit),
    interval: INTERVALS.coolingActions,
    service: 'COOLING-CONTROLLER-SERVICE',
    path: '/api/cooling/actions',
    stamp: (d) => latestInstant(d.map((a) => a.createdAt)),
  });

export const useCoolingUnits = () =>
  usePolled({
    key: ['cooling-units'],
    fn: E.fetchCoolingUnits,
    interval: INTERVALS.coolingUnits,
    service: 'COOLING-CONTROLLER-SERVICE',
    path: '/api/cooling/units',
    stamp: (d) => latestInstant(d.map((u) => u.updatedAt)),
  });

export const useCoolingSummary = () =>
  usePolled({
    key: ['cooling-summary'],
    fn: E.fetchCoolingSummary,
    interval: INTERVALS.coolingSummary,
    service: 'COOLING-CONTROLLER-SERVICE',
    path: '/api/cooling/summary',
  });

export const useWorkloadSummary = () =>
  usePolled({
    key: ['workload-summary'],
    fn: E.fetchWorkloadSummary,
    interval: INTERVALS.workloadSummary,
    service: 'WORKLOAD-SERVICE',
    path: '/api/workloads/summary',
  });

export const useWorkloads = ({ status, rackId } = {}) =>
  usePolled({
    key: ['workloads', status || 'ALL', rackId || 'ALL'],
    fn: () => E.fetchWorkloads({ status, rackId }),
    interval: INTERVALS.workloads,
    service: 'WORKLOAD-SERVICE',
    path: '/api/workloads',
  });

export const useRackHistory = (rackId, minutes = 10) =>
  usePolled({
    key: ['rack-history', rackId, minutes],
    fn: () => E.fetchRackHistory(rackId, minutes),
    interval: INTERVALS.history,
    service: 'TELEMETRY-SERVICE',
    path: `/api/telemetry/racks/${rackId}/history`,
    stamp: (d) => latestInstant(d.points.map((p) => p.recordedAt)),
  });

export const useFailures = () =>
  usePolled({
    key: ['failures', 'active'],
    fn: () => E.fetchFailures(true),
    interval: INTERVALS.failures,
    service: 'SIMULATOR-SERVICE',
    path: '/api/sim/failures',
  });

export const useSimState = () =>
  usePolled({
    key: ['sim-state'],
    fn: E.fetchSimState,
    interval: INTERVALS.racks,
    service: 'SIMULATOR-SERVICE',
    path: '/api/sim/state',
    stamp: (d) => d.timestamp,
  });

/** Decisions are fetched only when the explainer opens (plan 9.4: PlacementExplainer, "on open"). */
export function useDecisions(enabled) {
  return useQuery({ queryKey: ['decisions'], queryFn: () => E.fetchDecisions(200), enabled, retry: 0 });
}
