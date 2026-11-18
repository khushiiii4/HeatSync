import { useMemo } from 'react';
import { CloudOff } from 'lucide-react';
import { useServices } from '../api/sources';
import { useSourceHealth } from '../state/sourceHealth';
import { fmtClock, shortService } from '../lib/format';
import { Skeleton, cx } from './ui';

const ORDER = ['API-GATEWAY', 'AUTH-SERVICE', 'TOPOLOGY-SERVICE', 'SIMULATOR-SERVICE', 'TELEMETRY-SERVICE', 'WORKLOAD-SERVICE', 'SCHEDULER-SERVICE', 'COOLING-CONTROLLER-SERVICE'];

const STATE = {
  healthy: { dot: 'bg-ok', text: 'text-fg', label: 'Healthy' },
  degraded: { dot: 'bg-warn', text: 'text-warn', label: 'Degraded' },
  unavailable: { dot: 'bg-crit', text: 'text-crit', label: 'Unavailable' },
  stale: { dot: 'bg-fg-mute', text: 'text-fg-mute', label: 'Stale' },
};

/** Classify every expected service: healthy, degraded, unavailable, stale. */
export function classify(services, sources, registryStale) {
  const failingByService = new Set(Object.values(sources).filter((s) => s.failing && s.service).map((s) => s.service));
  return ORDER.map((name) => {
    const svc = services.find((s) => s.name === name) || { name, instances: 0, status: 'DOWN' };
    let state;
    if (registryStale) state = 'stale';
    else if (svc.status !== 'UP') state = 'unavailable';
    else if (failingByService.has(name)) state = 'degraded';
    else state = 'healthy';
    return { ...svc, state };
  });
}

export function overallStatus(items, registryDown) {
  if (registryDown) return { tone: 'crit', text: 'Gateway unreachable' };
  const down = items.filter((i) => i.state === 'unavailable');
  const degraded = items.filter((i) => i.state === 'degraded');
  if (down.length) return { tone: 'crit', text: `${down.length} service${down.length > 1 ? 's' : ''} unavailable` };
  if (degraded.length) return { tone: 'warn', text: `${degraded.length} degraded` };
  if (items.every((i) => i.state === 'stale')) return { tone: 'warn', text: 'Status stale' };
  return { tone: 'ok', text: 'All systems operational' };
}

export function ServiceHealthStrip() {
  const reg = useServices();
  const sources = useSourceHealth();
  const items = useMemo(() => (reg.hasData ? classify(reg.data, sources, reg.stale) : []), [reg.hasData, reg.data, sources, reg.stale]);

  return (
    <div className="flex flex-wrap items-center gap-x-1 gap-y-1.5" role="list" aria-label="Service health">
      {reg.isLoading && !reg.hasData && Array.from({ length: 7 }).map((_, i) => <Skeleton key={i} className="h-8 w-24" />)}
      {reg.down && (
        <span className="inline-flex items-center gap-2 rounded-lg border border-crit/40 bg-crit/10 px-3 py-1.5 text-sm font-medium text-crit" role="alert">
          <CloudOff className="h-4 w-4" aria-hidden /> Gateway unreachable — service status unknown
        </span>
      )}
      {items.map((s) => {
        const st = STATE[s.state];
        const tip =
          `${s.name}: ${st.label.toLowerCase()}${s.status === 'UP' ? `, ${s.instances} instance${s.instances === 1 ? '' : 's'}` : ', no registered instance'}${s.state === 'stale' ? `, last update ${fmtClock(reg.lastServerTime)}` : ''}`;
        return (
          <span key={s.name} role="listitem" title={tip} className={cx('inline-flex items-center gap-2 rounded-lg px-2.5 py-1.5 text-sm font-medium', st.text, s.state === 'healthy' ? '' : 'bg-ink-800/80 ring-1 ring-inset ring-ink-600')}>
            <span className={cx('h-2 w-2 rounded-full', st.dot, s.state === 'healthy' && 'live-dot')} aria-hidden />
            {shortService(s.name)}
            {s.instances > 1 && <span className="num text-xs text-fg-mute">×{s.instances}</span>}
            <span className="sr-only"> — {st.label}</span>
          </span>
        );
      })}
    </div>
  );
}
